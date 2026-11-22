# 硕士学位论文开题报告（修订版）

> 修订日期：2026-11-22。本文以 `docs/thesis-proposal.md` 为基础，结合
> `pixels-dml` 与 `pixels-trino-dml` 的提交历史、验证分支 RFC、cds20 隔离
> runtime 的真实 E2E 结果重新整理。
>
> 本修订版的首要前提是：先保证数据打标语义、物化结果和重启恢复正确，
> 再讨论吞吐优化。任何只在协议、组件或内部计时中成立、但没有通过表级
> SQL 和恢复验证的结果，不作为最终性能结论。

> 本次核对补充的代码基线：`codex/million-update-validation` 分支上的
> Pixels `408ad4bfeecbe1e3d2e4185284799c1cd2e566a7`、Trino Connector
> `750313cd0dcba40eb820987dd78952c7f209ff26`。本次仅核对提交、代码及仓库
> 实验记录，没有重新执行远程压测或故障注入。文中区分已有运行记录、静态
> 风险与待验证假设；未将正常重启成功推广为所有故障条件下的持久性保证。

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
5. 如何在真实 68 列宽表上提升单条 INSERT、UPDATE 的 SQL 吞吐，并独立
   降低包含必要 Compaction 和发布的完整打标周期成本。当前约 70--85k
   rows/s 仅指 candidate8 的单条 SQL 结果，不能作为完整周期吞吐；
   几十万及接近百万 rows/s 的 SQL 目标仍需验证其适用条件。

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

原始会议确定了打标期间不查询、最后统一比对的条件，没有将百万 rows/s、
每次只改两列或连续主键范围确定为业务前提。以上吞吐是工程探索目标，
不是会议验收标准。完整周期另报告从首条打标 SQL 开始到最终结果可查询的
wall time；若包含初始 INSERT，应另外说明导入是否计入。重复更新同一批行
时，同时报告不同受影响行数、累计行更新次数及单元格赋值次数。

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

当前 exact 路径在写标签前，先以每批 65,536 个候选键解析实际存在集合。
“一次共享主键请求”指标签写入阶段，不表示整条 UPDATE 只发生一次 RPC；
百万候选键的存在性解析还需要 16 批请求。

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

### 6.2 正确性修复与优化候选的证据边界

- Exact UPDATE 增加实际主键存在性解析，避免将缺失候选键算作更新成功；
  对应 dml `d13afa70`、trino `750313c` 及其 RFC 记录。这是必须保留的
  正确性修复，不是已经证明的性能收益。
- RocksDB 批量主键解析按 vnode 复用 iterator，对应 dml `735a2254`。
  已有编译和定向版本/缺键语义测试记录，但尚缺同环境配对实验量化独立收益。
  RFC 同时保留了整个旧测试类中 `testPutEntries` 失败的记录，不能将定向
  测试通过写成整个测试类全部通过，也不能由此推算 SQL 加速比。
- INSERT 的 primary-index flush 采用受控并行度；当前实验中 parallelism=4
  在历史特定暖态对照中表现较好。candidate8 的 INSERT 约 70--82k rows/s
  是另一组完整链路记录，不能将其直接归因于该参数，更不能外推为百万吞吐。
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

### 7.1 candidate8 百万行两列打标 E2E

在 cds20 的隔离 runtime 上，使用干净元数据、独立 etcd、Trino 和 daemon
完成了 68 列 candidate8 的真实链路：

```text
INSERT 1,000,000 rows
UPDATE 1,000,000 rows
tag-aware COMPACT 1,000,000 rows
Retina restart + recovery epoch markReady
post-restart SQL verification
```

仓库记录的结果如下；本次文档补充未重新运行：

| 阶段 | Trino query elapsed | CLI shell wall | 换算吞吐（Trino / shell） |
| --- | ---: | ---: | ---: |
| INSERT | 12.216 s | 14.289 s | 81.9k / 70.0k rows/s |
| UPDATE | 11.723 s | 13.592 s | 85.3k / 73.6k rows/s |

INSERT 的 primary-index wall 为 6.125 s；UPDATE 的
`primary_key_resolve_ms` 为 9.931 s，Connector total 为 11.322 s。
Compaction 的 tag resolve 为 12.205 s，物理重写为 17.186 s，publish 为
8.634 s，最终 1,000,000 行全部变为 `tag_1_type='R'` 且
`tag_1_para=9.0000`。

这些 Compaction 阶段可能嵌套或重叠，不能相加来代替总 wall time；本节
所引用的 candidate8 记录没有给出可直接引用的完整打标周期 wall time。
也不能将另一张表或另一轮实验的 Compaction 总时间与本表 SQL 时间拼接。

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

该记录支持当前配置下“INSERT → UPDATE → COMPACT → 正常重启 → readiness
握手 → 读回”的验证通过，但不证明任意并发或崩溃时刻都可恢复，也不证明
吞吐目标已经达到。恢复握手是所记录流程的一部分，不能称为无人干预的自动恢复。

### 7.3 当前性能结论

当前具有完整重启读回记录的 68 列宽表结果约为 INSERT 70--82k rows/s、UPDATE
74--85k rows/s，仍低于最低几十万 rows/s 的工程目标，更没有达到挑战性的
百万 rows/s。这是单条 SQL、百万行、两列 UPDATE 的成绩，不代表 60 列或
多轮实验结论。INSERT 的 primary-index wall 为 6.125 s，应优先拆测索引
写入与提交关键路径，不能将每个 worker 内部累计值直接相加解释 wall time。

UPDATE 主键解析的 9.931 s 占 Connector 总时间 11.322 s 约 88%；标签写入
RPC 约 0.962 s。因此当前已有证据支持优先分析主键解析，而 Java 分配、
分组、位置查找和传输各自占比仍需埋点或 profile，不能提前宣称某个子步骤
已被证明是根因。

历史 501K--763K rows/s 的暖态 exact UPDATE 记录没有包含现有存在性过滤，
不能继续作为修复后路径的性能验收成绩。两组运行状态也不同，不能把全部
耗时差距归因于此次修复，需同环境配对测量。

### 7.4 缺失主键回归及尚缺的验收

RFC 已记录缺陷复现：表中只有 `id=1,3`，对 `id BETWEEN 1 AND 3` 更新，
旧路径返回三行；后来插入 `id=2` 并合并，该行会错误继承先前标签。
修复后返回两行，解析日志为三键输入、两键存在，标签写入也仅包含两键。

但最初修复后实验的完整 Compaction 重跑因恢复故障未被接纳为验收结果；
candidate8 验证的是密集键，不能替代稀疏键回归。仍需补齐“稀疏范围 UPDATE
→ 插入原本缺失键 → COMPACT → Retina 重启 → 逐键核对”，确认缺失键不会
被历史标签污染。整个过程同时核对实际更新行数与最终值。

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
5. 时间戳未变化时也必须记录可见文件变化；visibility generation 是当前
   实现采用的检测机制，其并发快照一致性仍需验证。正确性修复与性能优化
   分开评价，不由机制名称推断所有恢复场景已经成立。

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

另记录部署 jar 校验值与 query ID，确保源码版本和实际运行包一致。
冷、暖状态分别说明是否首次建索引、是否重启、是否完成预热；重启后首轮
不自动归为暖态。不同实验臂从等价数据状态开始，保留全部样本。

### 8.3 优化顺序

1. 完成缺键、随机键、60 列、多轮覆盖和失败重试的正确性矩阵；
2. 对 INSERT 分离测量 writer、primary-index column-family 初始化、批量
   flush、main-index 和 metadata publish；
3. 对 UPDATE 分离测量键解析、存在性过滤、编码、请求构造、Tag Index
   storage 和提交；
4. 研究分块/压缩主键集合、按文件分区的 Compaction 消费和历史版本清理；
5. 最后再扩大到 10M/100M，并据实判断能否接近百万吞吐。

### 8.4 主键解析的具体测量与优化候选

当前 [Connector][connector-resolve] 分批调用 `resolvePrimaryEntries`，
仅消费返回的主键；[服务端][server-resolve] 仍为每个存在键执行
`mainIndex.getLocation(rowId)`，并返回 rowId 与 RowLocation。这说明当前
存在性检查复用了完整位置解析接口，不证明位置查找本身占据了全部 9.931 s。

下一步依次测量：版本可见的主键查找、逐键位置查找、分桶/对象分配、响应
编码和传输，以及各批串行等待时间。以修复后的正确实现作为对照，比较
iterator 复用等候选的配对样本。若位置解析或响应成本显著，再评估轻量
存在性接口；必须先明确主索引、行可见性和快照之间的契约，不能删除必要
一致性检查，不能将“不存在”与“索引损坏或位置缺失”混为一类。

### 8.5 全周期与外部对照的决定性实验

在相同 68 列基表上，对比“一次更新 60 列”与“30 次各更新两列、最后仅
合并一次”，最终值必须一致；另设同列重复覆盖实验。报告总完成时间、
实际读写字节、峰值内存及不同/累计修改量，验证延迟合并能否摊薄成本。

在不同表规模上分别固定修改行数和修改比例，加入聚集与随机分布，记录
实际触及及重写文件数，判断成本随修改量还是全表规模增长。不能预设稀疏
行更新必然只触及少量文件。

外部主要候选为相同 Trino 版本下的 Iceberg，先核验部署版本能力和配置；
Retina 作为内部基线。固定资源、输入和最终查询可见状态，披露事务与
持久性差异，不为基线强加其本来不需要的 optimize/compaction。
本次尚未新增这些对照实验结果。

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
   闭合；区分正常 recovery epoch readiness 握手和人工数据修补，明确
   前者的自动化范围，禁止通过手工元数据修补冒充恢复验证通过；
6. 外部 Iceberg/Hudi/Paimon 对比必须固定版本、硬件、数据和完成语义，不能
   把不同事务强度、不同可见性定义下的吞吐直接排序。

### 9.1 静态检查发现的 checkpoint 风险

以下两项来自本次代码检查，尚未执行故障注入，不能写成已复现或已修复。

**可见性版本确认与快照内容可能不一致。**
[runGC][gc-generation] 先遍历生成 checkpoint 条目，结束时以最新
`visibilityGeneration.get()` 更新 `lastCheckpointVisibilityGeneration`。
若文件在快照收集完成后、确认 generation 前加入，它可能未写入本次 body，
但其版本已被确认为保存；时间戳不前进时，下一轮可能跳过。
需用并发测试在这两个阶段之间插入可见文件，核对下一轮一定生成包含该文件
的 checkpoint。候选修复应使已确认 generation 对应实际快照，或至少保守
地保留再次生成的需求，不能仅调整计数器而不验证并发时序。

**同时间戳重新生成会覆盖已发布的 body。**
[RecoveryCheckpoint.generate][checkpoint-write] 使用时间戳构建路径，
在发布指针前以 overwrite 模式写入；[LocalFS.create][localfs-overwrite]
遇到同名文件时先删除旧文件。因此同时间戳重写中断可能使当前指针指向
缺失或不完整 body。candidate8 的写完后正常重启没有覆盖此窗口。
需分别在旧 body 删除、新 body 写入中和指针发布前中断并重启验证。
可讨论每次生成独立 body、写完后切换指针并在安全时机回收旧版本，但这
仍是待设计和验证的候选，不是已完成的恢复保证。

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
`e0fe02b8` 记录了 candidate8 正常重启与 readiness 握手后的读回结果，
其范围不包括任意写入中断或并发更新窗口。

## 十三、结论

本课题的首要前提是“在支持可靠打标的基础上优化”，不是为了追求吞吐而
牺牲实际匹配集合、标签隔离、物化结果或重启恢复。仓库已有 68 列、百万行、
两列打标的 INSERT → UPDATE → COMPACT → 正常重启并握手 → 读回记录，
支持同一事务时间戳下遗漏新可见文件的特定回归已得到处理；但并发 generation
确认和 body 覆盖中断仍需测试，不能宣布所有持久性风险已经消除。当前单条 SQL
吞吐仍处于约 70--85k rows/s，距离最低几十万和挑战性的百万 rows/s 尚有
明显差距。

因此，下一阶段的研究重点应从“继续盲目增加并发”转向可解释的阶段拆分、
正确性矩阵、增量表示、Compaction 有界消费和 primary-index 发布优化，最终
以完整周期和可恢复 E2E 结果判断方案是否达到目标。

延迟合并具有业务依据，整体性能优势尚未证实。若多轮全周期与资源成本
稳定优于基线，应继续优化并说明适用范围；若代表性负载上的完整周期始终
无优势，应重新评审索引组织或合并策略。现有证据支持调整优化重点，尚不
足以要求立刻推翻整体架构；主要架构变更须与项目负责人讨论后再实施。

[connector-resolve]: https://github.com/LeaseQ/pixels-trino-dml/blob/750313cd0dcba40eb820987dd78952c7f209ff26/connector/src/main/java/io/pixelsdb/pixels/trino/PixelsMetadata.java#L575-L603
[server-resolve]: https://github.com/LeaseQ/pixels-dml/blob/408ad4bfeecbe1e3d2e4185284799c1cd2e566a7/pixels-daemon/src/main/java/io/pixelsdb/pixels/daemon/index/IndexServiceImpl.java#L1291-L1336
[gc-generation]: https://github.com/LeaseQ/pixels-dml/blob/408ad4bfeecbe1e3d2e4185284799c1cd2e566a7/pixels-retina/src/main/java/io/pixelsdb/pixels/retina/RetinaResourceManager.java#L965-L1088
[checkpoint-write]: https://github.com/LeaseQ/pixels-dml/blob/408ad4bfeecbe1e3d2e4185284799c1cd2e566a7/pixels-retina/src/main/java/io/pixelsdb/pixels/retina/RecoveryCheckpoint.java#L470-L484
[localfs-overwrite]: https://github.com/LeaseQ/pixels-dml/blob/408ad4bfeecbe1e3d2e4185284799c1cd2e566a7/pixels-storage/pixels-storage-localfs/src/main/java/io/pixelsdb/pixels/storage/localfs/LocalFS.java#L239-L255
