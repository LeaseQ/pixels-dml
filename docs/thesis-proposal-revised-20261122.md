# 硕士学位论文开题报告（修订版）

> 修订日期：2026-11-22。本文以 `docs/thesis-proposal.md` 为基础，结合
> `pixels-dml` 与 `pixels-trino-dml` 的提交历史、验证分支 RFC、cds20 隔离
> runtime 的真实 E2E 结果重新整理。
>
> 本修订版的首要前提是：先保证数据打标语义、物化结果和重启恢复正确，
> 再讨论吞吐优化。任何只在协议、组件或内部计时中成立、但没有通过表级
> SQL 和恢复验证的结果，不作为最终性能结论。

## 一、课题名称

面向大规模数据打标的云原生列存引擎 SQL 行级更新机制研究与实现

英文题目（参考）：Research and Implementation of SQL Row-level Update
Mechanism for Large-scale Data Tagging on a Cloud-native Columnar Engine

## 二、研究背景与核心问题

机器学习特征工程、数据治理和数据资产管理经常需要对已经生成的宽表进行
批量打标。业务希望继续使用标准 SQL，通过 INSERT 写入基础数据，通过
UPDATE 为大量行设置标签，最后统一查询和消费打标结果。该类工作负载通常
允许“先记录变更、后统一物化”，但不允许已成功提交的标签在 Compaction 或
进程重启后丢失，也不允许不同标签列相互污染。

Pixels 是面向分析负载的云原生列存引擎。其基础文件不可原地更新，因此本
课题采用“增量记录与物理物化分离”的路线：INSERT 直接生成列存文件；标签
UPDATE 将标签值与主键集合写入独立 Tag Index；打标阶段结束后，由
Compaction 将标签变更物化到新的列存文件，并修正主索引和文件可见性。

本课题的核心问题不是简单地把某个 RPC 做快，而是：

1. 如何在 Trino Connector 中正确承接 INSERT/UPDATE 语义，并区分可精确
   下推的主键谓词和必须走 merge/fallback 的一般谓词；
2. 如何以按列隔离的增量表示支持 varchar、decimal 等标签类型和多轮覆盖；
3. 如何在不原地改写列存文件的情况下，通过 Compaction 生成正确的物化快照；
4. 如何让元数据、主索引、Tag Index、Retina 可见性和恢复 checkpoint 的
   生命周期保持闭合；
5. 如何在真实 68 列宽表上，将 INSERT、UPDATE 和完整打标周期从当前约
   70--85k rows/s 推向最低几十万 rows/s，并研究是否存在接近百万 rows/s
   的适用条件。

## 三、研究目标与边界

### 3.1 总体目标

在 Pixels + Trino 体系内实现一套面向大规模数据打标的 SQL 行级 DML 机制，
使其在以下条件下成立：

- 标签 UPDATE 不直接改写原始列存文件，而是先持久化到按列隔离的 Tag Index；
- 统一 Compaction 后，普通扫描和标签谓词能读取正确的最终值；
- INSERT、UPDATE、Compaction 和 Retina 重启恢复均有可复现实验和日志证据；
- 在固定 68 列主测例上，吞吐和完整周期成本能够被分阶段测量、解释和优化。

### 3.2 工程性能目标

工程目标分为两个层次，不能混写：

| 层次 | 目标 |
| --- | --- |
| 基础目标 | 在真实宽表和完整 SQL 路径上稳定达到最低几十万 rows/s，并保持正确性和可恢复性 |
| 挑战目标 | 在适合的更新列数、键分布和资源配置下探索接近百万 rows/s 的 SQL 提交吞吐 |

“百万行/秒”是本课题的挑战目标，不是可以先验宣称的结果。单次内部 RPC、
候选键枚举或窄表最好值不能替代 SQL wall time，也不能替代包含必要
Compaction 的完整打标周期。

### 3.3 研究边界

首期范围是 INSERT、标签 UPDATE、标签感知 Compaction 和恢复；DELETE、
任意表达式更新、完整跨服务原子事务不作为已经完成的能力。精确 UPDATE
当前主要覆盖有限主键域和常量赋值，非精确谓词必须明确走 fallback 或被拒绝，
不能把候选键数量直接当成实际匹配行数。

## 四、研究现状与方案定位

不可变列存系统通常采用基线文件加增量变更，再通过异步合并收敛物理布局。
Hudi 的 Copy-on-Write/Merge-on-Read、Iceberg 的 position/equality delete、
Delta Lake 的 deletion vector、Paimon 的主键 LSM 表，分别代表了文件重写、
读时合并、位图标记和 LSM 更新等路线。

本课题不把“使用 RocksDB Tag Index”本身作为算法创新，也不预设它优于所有
湖仓表格式。研究价值在于针对 Pixels 的列存文件、Retina 可见性、Trino
Connector 和打标业务约束，系统验证以下取舍：

- 独立标签增量是否能减少高频打标期间的物理写放大；
- 延后 Compaction 带来的写入收益是否会被物化和查询延迟抵消；
- 按列隔离、共享主键请求和分块增量表示能否控制宽表、多轮更新的增长；
- 该方案在什么数据分布和资源条件下具有可扩展性。

## 五、系统设计

### 5.1 写入路径与物化路径分离

INSERT 由 Trino Worker 创建 Pixels Writer，生成列存文件、主键索引和
元数据 fragment，再发布文件行号区间和可见性。UPDATE 在 Connector 内先
判断谓词：精确主键域走 exact 路径，以一次共享主键请求写入多个标签；其他
谓词走 merge/fallback 路径。Tag Index 按标签列隔离命名空间，保存标签值
到主键集合的版本化映射。

Compaction 读取基础文件和标签索引，批量解析主键、重写物理文件、修正主
索引，并在元数据发布和 Retina 可见性更新完成后使新文件成为可读版本。
因此，UPDATE 成功返回与普通扫描立即读到新物理值是两个不同事件，实验必须
分别报告：SQL 提交成功、Tag Index 状态、Compaction 后可见和重启后可见。

### 5.2 正确性契约

本课题把“支持打标”定义为不可破坏的前提，包括：

1. 实际匹配行集合正确，缺失主键不能被误记为更新成功；
2. 不同标签列互不污染，同一列多轮覆盖遵循最后一次有效赋值；
3. decimal 的精度、scale、NULL 和字符串编码在 Trino、RPC、RocksDB、
   Compaction 四层一致；
4. Compaction 后行数、边界主键、标签谓词和基础列均正确；
5. 已提交文件和标签在 Retina 重启、checkpoint 重发和同一事务时间戳下
   仍然可恢复；
6. 失败或重试不造成重复发布、悬空 checkpoint 或不可解释的部分可见。

### 5.3 68 列主测例

主测例包含 1 个 bigint 主键、1 个 timestamp、6 个维度列、30 对
`tag_N_type/tag_N_para` 列，共 68 列。初始标签列为 SQL NULL，使用单一
root URI，由 connector 派生 `v-0-ordered` 和 `v-0-compact` 两种布局。
正式测量必须保存生成参数、字段数、NULL 计数、主键范围和输入校验值。

## 六、基于提交历史的阶段性成果

提交历史显示，优化工作应分成“功能主线、性能探索、正确性纠错”三类，不能
把所有历史 commit 都视为有效优化。

### 6.1 功能主线

| 阶段 | 代表提交 | 形成的能力 |
| --- | --- | --- |
| 标签索引原型 | `29296642`、`df8d46d3`、`46eff83e` | Tag Index、标签物化和初始 DML 主线 |
| 中央索引服务 | `4f60a345`、`5b81b53` | 通过 IndexServer 路由标签和 Compaction 访问 |
| Exact UPDATE | `b2b3bba`、`4bc89bb`、`819ec41` | 有限主键域、批量更新和百万候选键路径 |
| 列隔离与 decimal | `bc094956`、`93fc3c70`、`892b9b1f`、trino `0d27988`、`1973c10` | 标签列命名空间和 decimal scale 修复 |
| 共享主键协议 | `7055aa8`、trino `78a9ab5`、`85de23c` | 多标签共享一次主键请求，减少重复传输 |

### 6.2 已被证据支持的优化

- Exact UPDATE 增加实际主键存在性解析，避免将缺失候选键算作更新成功；
  对应 dml `d13afa70` 及其 RFC 记录。
- RocksDB 批量主键解析复用 iterator 和缓冲，减少 Java 对象与重复打开成本；
  对应 dml `735a2254`。
- INSERT 的 primary-index flush 采用受控并行度；当前实验中 parallelism=4
  优于串行和更高并行度，但它仍只把 INSERT 推到约 70--85k rows/s，不能
  直接外推为百万吞吐。
- Retina checkpoint 增加 visibility generation。当事务时间戳不变、但
  Compaction 新增可见文件时，强制重新发布 checkpoint；对应 `8cb3e5d0`
  和编译修复 `a82ad7fa`。

### 6.3 已被否决或只能作为诊断的方案

提交历史中有大量明确的 rejection RFC，包括 WAL 开关、跨 column family
WriteBatch、WriteBatch 预分配、数组/范围 bucket、compact vnode、重复
bookkeeping 消除、盲目提升 RocksDB/flush 并发等。这些尝试没有在完整 SQL
周期上形成稳定收益，部分还出现冷启动排队或资源争用退化。

因此，论文不会把“尝试过的优化数量”当作贡献。每个候选方案必须回答：
它改变了哪一阶段、是否降低了可解释的瓶颈、是否改善完整 SQL/周期指标、
是否保持正确性和恢复能力。

## 七、已完成的真实验证与当前结论

### 7.1 candidate8 完整 E2E

在 cds20 的隔离 runtime 上，使用干净元数据、独立 etcd、Trino 和 daemon
完成了 68 列 candidate8 的真实链路：

```text
INSERT 1,000,000 rows
UPDATE 1,000,000 rows
tag-aware COMPACT 1,000,000 rows
Retina restart + recovery epoch markReady
post-restart SQL verification
```

结果如下：

| 阶段 | Trino/内部耗时 | CLI shell wall | 换算吞吐 |
| --- | ---: | ---: | ---: |
| INSERT | 12.216 s | 14.289 s | 81.9k / 70.0k rows/s |
| UPDATE | 11.723 s | 13.592 s | 85.3k / 73.6k rows/s |

INSERT 的 primary-index wall 为 6.125 s；UPDATE 的
`primary_key_resolve_ms` 为 9.931 s，Connector total 为 11.322 s。
Compaction 的 tag resolve 为 12.205 s，物理重写为 17.186 s，publish 为
8.634 s，最终 1,000,000 行全部变为 `tag_1_type='R'` 且
`tag_1_para=9.0000`。

### 7.2 恢复修复的关键证据

修复前 candidate5 曾出现：Compaction 后同一时间戳没有重新生成 checkpoint，
重启后 `baselineFiles=0`、结果归零。该失败被保留为回归记录，没有被删除或
人工修复后冒充通过。

candidate8 中，Compaction 前 checkpoint 为 `checkpointAppliedTs=4,
rgs=305`；Compaction 后时间戳仍为 4，但 checkpoint 被重新发布为
`rgs=306`。随后 Retina 重启日志显示 `baselineFiles=1`，完成
`RECOVERING -> READY` 握手后，查询仍得到：

```text
total=1,000,000
updated tag count=1,000,000
tag predicate count=1,000,000
boundary rows=(R, 9.0000)
```

这证明当前修复已经通过“INSERT → UPDATE → COMPACT → 重启 → 读回”的
持久性门槛，但不证明吞吐目标已经达到。

### 7.3 当前性能结论

当前最可靠的 68 列宽表结果约为 INSERT 70--82k rows/s、UPDATE
74--85k rows/s，仍低于最低几十万 rows/s 的工程目标，更没有达到挑战性的
百万 rows/s。INSERT 主要瓶颈仍集中在 primary-index 发布、flush 和提交收尾；
UPDATE 主要瓶颈仍是百万主键解析、Java 分配/分组和 RPC/服务端协调。两者
不能用同一套“增加并发”解释，也不能用 3 列窄表或内部 RPC 计时替代主测例。

## 八、研究假设与后续实验

### 8.1 研究假设

1. 共享主键集合和按列隔离的增量表示能够降低多标签 UPDATE 的重复序列化，
   但其收益会受到主键解析和 Tag Index 历史版本增长限制；
2. 对连续主键可采用压缩或分块集合，但必须保留实际存在集合，不能把带孔
   洞候选范围当作有效行集合；
3. Compaction 的全量标签快照、主键解析和 relocation 可能成为百万行以上
   的主要周期瓶颈，应研究按文件/键分区的有界消费；
4. INSERT 的瓶颈更可能在 primary-index 初始化与发布，而不是单纯的文件
   writer，因此需要 writer、primary-index、main-index 三段消融；
5. checkpoint visibility generation 是恢复正确性的必要条件，但不带来
   直接吞吐收益，正确性修复与性能优化必须分开评价。

### 8.2 实验矩阵

后续实验固定使用单一 root URI 和干净 runtime，并至少覆盖：

| 维度 | 取值 |
| --- | --- |
| 基础规模 | 1M、10M，随后根据资源评估 100M；1B 只作为扩展性边界 |
| 更新列数 | 2、8、60 列 |
| 更新分布 | 连续主键、随机键、带孔洞、缺失键、空结果 |
| 更新轮数 | 一次多列、分列更新、同列重复覆盖 |
| 路径 | exact、merge/fallback、冷启动、重启后 warm |
| 结果口径 | SQL 提交、Compaction 后可见、完整周期、重启后可见 |

每组至少重复三次，报告中位数、波动、CPU、内存、物理写入字节和
checkpoint/历史增量增长。所有结果必须记录 commit、runtime、配置、SQL、
输入校验和、完整日志；协议 probe 和组件单测不能单独作为表级验收。

### 8.3 优化顺序

1. 完成缺键、随机键、60 列、多轮覆盖和失败重试的正确性矩阵；
2. 对 INSERT 分离测量 writer、primary-index column-family 初始化、批量
   flush、main-index 和 metadata publish；
3. 对 UPDATE 分离测量键解析、存在性过滤、编码、请求构造、Tag Index
   storage 和提交；
4. 研究分块/压缩主键集合、按文件分区的 Compaction 消费和历史版本清理；
5. 最后再扩大到 10M/100M，并据实判断能否接近百万吞吐。

## 九、关键难点与风险

1. Exact 路径候选域可能大于实际匹配集合，缺键过滤和复合键语义必须继续
   完善；
2. 多列标签、decimal scale、NULL 和重复覆盖需要协议、存储、物化三层
   一致；
3. Tag Index 全量列表和 Compaction 全局快照可能产生随行数、列数和轮数
   增长的内存与网络成本；
4. INSERT 元数据发布、文件 REGULAR 状态和 Retina 可见性仍是多个服务操作，
   需要故障注入检验幂等性和补偿；
5. checkpoint body、pointer、metadata file state 之间必须保持生命周期
   闭合，不能依赖人工 `markReady` 或手工元数据修复作为最终方案；
6. 外部 Iceberg/Hudi/Paimon 对比必须固定版本、硬件、数据和完成语义，不能
   把不同事务强度、不同可见性定义下的吞吐直接排序。

## 十、预期创新与可交付成果

本课题预期形成的贡献不是“某个参数调大后更快”，而是：

1. 面向 Pixels 列存文件的、可审计的 SQL 标签 UPDATE 双路由机制；
2. 按标签列隔离、支持多列共享主键请求的增量表示与服务接口；
3. 与主索引、Compaction、元数据和 Retina checkpoint 联动的可恢复物化
   流程，以及同 timestamp visibility generation 的恢复修复；
4. 一套区分 SQL 提交、物化可见、完整周期和重启读回的宽表 benchmark
   方法，包含被否决方案和失败证据；
5. 对当前吞吐瓶颈、适用数据分布和扩展边界的可复现实验结论。

交付物包括论文、两个验证分支的可构建实现、68 列数据集与生成说明、
benchmark/验收脚本、RFC、配置和完整日志。达到的最大规模和吞吐据实报告，
不把尚未完成的 100M/1B 或百万 rows/s 写成既成事实。

## 十一、进度安排

| 阶段 | 计划 | 工作内容 |
| --- | --- | --- |
| 已完成 | 2026.10–11 | Tag Index、exact UPDATE、共享主键 RPC、列隔离、INSERT/Compaction 埋点 |
| 已完成 | 2026.11 | 68 列真实 E2E、优化 probe、checkpoint 同 timestamp 修复及 candidate8 重启验收 |
| 第一阶段 | 2026.12 | 完成缺键/随机键/60 列/多轮覆盖矩阵，固化干净 1M 基线 |
| 第二阶段 | 2027.01 | 完成 INSERT 分阶段消融和 10M 实验，核验 Iceberg 同版本外部基线 |
| 第三阶段 | 2027.02–03 | 研究分块增量表示、分区 Compaction 和历史版本清理 |
| 第四阶段 | 2027.03–04 | 故障注入、自动恢复、100M 扩展性评估和完整周期比较 |
| 第五阶段 | 2027.04–05 | 整理论文、图表、消融结果和答辩材料 |

## 十二、主要工程证据

以下文档作为工程证据，不能替代同行评议文献：

- [VALIDATION_HANDOFF.md](../VALIDATION_HANDOFF.md)
- [RFC_68COL_TAG_BENCHMARK.md](../RFC_68COL_TAG_BENCHMARK.md)
- [RFC_E2E_COLD_WARM_20261119.md](../RFC_E2E_COLD_WARM_20261119.md)
- [RFC_MILLION_UPDATE_INSERT_DURABILITY.md](../RFC_MILLION_UPDATE_INSERT_DURABILITY.md)
- [optimization-direction-review.md](optimization-direction-review.md)

关键修订提交包括：dml `d13afa70`、`735a2254`、`8cb3e5d0`、`a82ad7fa`、
`e0fe02b8`，以及 trino `78a9ab5`、`85de23c`、`750313c`。其中
`e0fe02b8` 记录了 candidate8 的完整持久性验收结果。

## 十三、结论

本课题的首要前提是“在支持可靠打标的基础上优化”，不是为了追求吞吐而
牺牲实际匹配集合、标签隔离、物化结果或重启恢复。当前工程已经证明了
68 列、百万行的 INSERT → UPDATE → COMPACT → 重启读回闭环，并修复了
同一事务时间戳下 checkpoint 丢失新可见文件的严重问题；但当前真实 SQL
吞吐仍处于约 70--85k rows/s，距离最低几十万和挑战性的百万 rows/s 尚有
明显差距。

因此，下一阶段的研究重点应从“继续盲目增加并发”转向可解释的阶段拆分、
正确性矩阵、增量表示、Compaction 有界消费和 primary-index 发布优化，最终
以完整周期和可恢复 E2E 结果判断方案是否达到目标。
