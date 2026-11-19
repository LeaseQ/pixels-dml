# 硕士学位论文开题报告

## 一、课题名称

面向大规模数据打标的云原生列存引擎 SQL 行级更新机制研究与实现

英文题目（参考）：Research and Implementation of SQL Row-level Update
Mechanism for Large-scale Data Tagging on a Cloud-native Columnar Engine

## 二、研究背景与意义

### 2.1 业务背景

在机器学习特征工程、数据治理和数据资产管理等场景中，需要对存量大规模
数据集进行增量式“打标”：通过 SQL 的 INSERT、UPDATE 语句，为大量数据行
的标签列赋值，并在全部打标完成后统一进行数据比对与下游消费。该类负载
具有三个鲜明特征：单次修改行数大（十万至百万级，远期面向亿级）、修改
集中在少数标签列、打标期间不要求在线查询被修改的数据，对事务的 ACID
要求宽松，但要求最终结果正确、过程可恢复、吞吐可接受。

本课题依托的 Pixels 是一套云原生无服务器（serverless）OLAP 引擎，
通过 Trino 对外提供 SQL 接口，底层采用列存文件组织，并由 Retina 组件
基于 MVCC 日志提供变更回放能力。现有 Retina 接口面向整行回放设计，在
短时间内修改大量行、且每行只修改少数列的打标场景下，会把未修改列一并
重放，造成显著的数据膨胀和写放大，无法满足打标负载的性能要求。同时，
Pixels 底层存储不支持原地更新，存储侧接口复杂，直接改写数据文件代价
高昂。

### 2.2 问题提出

在不改造底层存储格式、不要求严格在线事务的前提下，如何让一个面向分析
负载设计的列存引擎具备高吞吐的 SQL 行级 INSERT/UPDATE 能力，并保证
打标结果在异步合并、进程重启之后仍然正确可见，是本课题要解决的核心
问题。具体可分解为：

1. 如何在 Trino Connector 的 SPI 框架内承接 INSERT/UPDATE 语义，并把
   谓词信息转化为可下推的有限主键域；
2. 如何在不原地改写列存文件的情况下，持久化记录“标签值到主键集合”的
   映射，并支持多标签列、多种数据类型的正确共存；
3. 如何通过异步 Compaction 将索引变更物化为新的列存文件，同时维护主索引
   与标签索引的一致性；
4. 如何在组件重启、检查点重发等异常场景下保证已提交数据不丢失、不重复、
   可恢复；
5. 如何刻画并消除百万行以上规模的性能瓶颈，使系统具备向千万、亿级扩展
   的工程基础。

### 2.3 研究意义

理论层面，本课题是“写入路径与物化路径分离”思想在云原生列存引擎上的
一次完整工程实践：以独立索引承接高频行级变更、以异步合并收敛物理布局，
与主流湖仓表格式的行级变更技术处于同一技术谱系，可为列存系统支持行级
DML 提供设计参照和量化评测依据。

工程层面，课题成果直接支撑业务打标流程，使数据工作者可以使用标准 SQL
完成百万级标签赋值，而无需依赖专用回放接口；其中关于 vnode 索引冷启动、
服务端分组写入、Compaction 重复解析等问题的定位与优化，对 Pixels 主干
的 DML 能力建设具有回馈价值。

## 三、国内外研究现状

### 3.1 列存系统的行级变更

传统列存系统面向只读分析负载设计，普遍不支持原地行级更新。LSM-Tree
（Log-Structured Merge-Tree）通过将随机写转化为顺序写、后台合并 SSTable
来支撑高频更新，是写读分离架构的经典基础，但其合并不避免地引入写放大。
分析型引擎在引入行级变更时，普遍采用“基线文件 + 增量变更 + 异步合并”
的组织方式，本课题的 Tag Index 与 Compaction 设计遵循同一思路。

### 3.2 湖仓表格式的行级变更机制

近年出现的开放湖仓表格式围绕行级 INSERT/UPDATE/DELETE 形成了代表性
方案：

- Apache Hudi 采用 Copy-on-Write 与 Merge-on-Read 两种表类型，配合记录级
  索引（record-level index）支撑快速 upsert，写入与合并在时间上解耦；
- Apache Iceberg 通过 position delete 与 equality delete 文件描述行级
  删除与变更，读时合并，后台 compaction 负责收敛；
- Delta Lake 引入 deletion vector 以位图方式标记被删除/变更行，降低小
  文件数量与读时合并成本；
- Apache Paimon 面向流式更新构建主键表，底层采用 LSM 结构与多级
  compaction，支撑高吞吐 changelog 写入。

这些系统共同验证了“快速写入路径只维护持久化的行/键映射，垃圾回收与
合并异步进行”的权衡。与它们相比，本课题的差异在于：变更状态不落入
表格式自带的元数据文件，而是托管在独立的 RocksDB 索引服务中，通过
gRPC 与 Trino Worker 交互，并需要与 Pixels 既有的主索引、Retina MVCC
恢复机制协同。

### 3.3 查询引擎写能力与嵌入式索引

Trino（前身 Presto）以交互式联邦查询见长，其 Connector SPI 长期以读
为主，写接口的工程实现需要自行处理页汇聚、文件提交与元数据发布。
RocksDB 作为高性能嵌入式有序 KV 存储，被广泛用于需要持久化键值映射的
系统组件中，适合承接标签值到主键集合的追加式写入与点查，但在多列命名
空间、批量请求体积、列族（column family）初始化调度等方面需要针对性
设计。

### 3.4 现状评述

现有研究与工业系统证明了写读分离路线的可行性，但缺少在“无服务器
OLAP + 独立索引服务 + 单列存文件不可原地更新”这一具体约束下的端到端
实现与定量评测。本课题以真实业务打标负载为驱动，覆盖从 Trino SQL 入口
到 RocksDB 索引、再到 Compaction 物化与故障恢复的完整链路，具有明确
的工程问题和可验证的评测目标。

## 四、研究目标

### 4.1 总体目标

在 Pixels + Trino 体系内实现一套面向数据打标的 SQL 行级 INSERT/UPDATE
机制：UPDATE 不直接改写列存文件，而将标签映射持久化到 RocksDB Tag
Index；打标完成后通过异步 Compaction 物化为新文件并修正主索引；系统在
百万行规模下具备可接受的端到端吞吐、正确的多列标签语义和可验证的重启
恢复能力，并为千万至亿级规模给出瓶颈分析与优化路径。

### 4.2 具体目标

1. 在 Trino Connector 中实现 INSERT/UPDATE 的完整 SPI 链路，支持精确
   主键更新（exact path，单次批量索引请求）与非精确谓词的 merge 兜底
   路径，并提供路由可观测性；
2. 设计并实现按列隔离的 Tag Index 存储结构，保证多标签列更新互不污染，
   正确处理 varchar、decimal 等类型的编码与精度；
3. 实现标签感知的 Compaction，将索引变更重写到物理文件，批量修正主索引
   与标签索引，消除全量索引扫描和重复主键解析；
4. 保证 INSERT 行号区间（row-id hull）随元数据持久化发布，修复检查点
   同路径重发等恢复缺陷，使已提交数据经进程重启后可正确恢复；
5. 建立 68 列真实打标 schema 的分级 benchmark（1M、10M、100M、1B），
   分离冷启动与稳态写入，定位 primary index、Compaction 各阶段瓶颈，
   完成 vnode 预热与服务端分组写入的优化设计与部分实现。

## 五、研究内容

### 5.1 总体架构：写入路径与物化路径分离

系统划分为两条时序解耦的路径。写入路径上，Trino 接收 SQL DML，
Connector 在 Worker 内为 INSERT 直接创建 Pixels Writer 写文件，为
UPDATE 构造标签到主键集合的映射，经 gRPC 批量写入 IndexServer 的
RocksDB Tag Index。物化路径上，打标结束后由 CLI/RPC 触发 Compaction，
读取标签索引与原文件，重写出新的列存文件，并同步修正主索引与标签索引，
最终由元数据服务发布可见性。点查可直接经标签索引定位、无需回表，表
扫描则消费 Compaction 后的新文件。

### 5.2 UPDATE 双路由与谓词下推

当 UPDATE 谓词可归约为有限主键域时走 exact 路径：规划阶段提取主键域，
执行阶段以单次批量 Tag Index RPC 完成全部行的标记；否则回退到
beginMerge/PixelsMergeSink 的按页处理路径，按批刷新标签。通过路由日志
与分段指标（路由判定、RPC 次数、请求字节数、编码耗时）确保真实 SQL
的路径可审计，避免“以为走了批量、实际走了逐行合并”的测量错误。

### 5.3 按列隔离的 Tag Index 设计

在索引协议（proto）与 RocksDB 逻辑键中引入列身份（columnName），以
“标记位 + 列名长度 + 列名 + 标签值”的编码方式为不同标签列维护独立
命名空间，同时保留旧的无列名条目以兼容历史数据。针对 decimal 类型，
在 Trino 侧将原生未缩放常量规范化为标准 SQL 小数字符串，防止标签值
在 Compaction 回写时被重复乘以列精度。

### 5.4 标签感知 Compaction 与主索引协同

Compaction 为所有重写行构造主键，批量调用主键解析与批量写入接口修正
主索引；标签索引解析共享同一快照，每个合并任务只保留自身源行的
relocation 映射，避免复制全量映射；Compaction 写入器先关闭再读取行组
数，保证发布的元数据行组计数与 row-id 区间正确。

### 5.5 INSERT 持久化与发布协议

将 INSERT fragment 版本化，携带 minRowId/maxRowId 行号区间，索引写入器
记录分配区间，元数据在 finishInsert 时将行号区间与文件 REGULAR 状态
原子发布；旧版本 fragment 保留保守解码。发布过程保持串行确定性，并对
writer、primary-index RPC、main-index flush 分别埋点。

### 5.6 故障恢复

研究 Retina 检查点在进程重启、同一时间戳重复发布等场景下的正确性，
修复检查点 body 被错误清理导致悬空指针的问题，并以回归测试覆盖；
梳理重启后元数据文件生命周期状态（REGULAR/RETIRED）与可见性注册的
恢复流程，识别当前需要人工介入的环节并给出自动化方案。

### 5.7 性能评测与瓶颈优化

围绕 68 列打标 schema（1 个主键、1 个时间戳、6 个维度、30 组
tag_type/tag_para）构建分级 benchmark，独立计时 base load、在线
INSERT、exact UPDATE、Compaction、标签 SELECT、重启恢复六个阶段。
在已观测到 128 个 vnode 索引冷启动串行建列族、稳态下每百万行
primary-index 发布耗时占比过半的基础上，研究 vnode 预热调度与服务端
分组主键写入（单 RPC 承载多 vnode 批次），并评估 Compaction 中标签
索引与主索引重复解析的消除方案。

## 六、关键问题与难点

1. 路由正确性：谓词能否归约为有限主键域决定了 UPDATE 是否走批量路径，
   判定过宽会退化到逐行合并，判定过窄会漏行，需要严密的规划期约束与
   日志级验证手段。
2. 多列标签正确性：多标签列共享单一命名空间会导致物化值互相污染，
   decimal 等类型在未缩放值与字符串规范形式之间的转换极易引入精度
   错误，需要协议、编码、回写三层协同修复。
3. 索引服务扩展性：单 IndexServer、单 RocksDB writer 下，客户端并发
   fan-out 会因锁竞争反而退化（已有实验证实），优化必须落在服务端
   写入路径与 vnode 组织方式上，而不是简单增加客户端线程。
4. Compaction 一致性与成本：合并过程同时改写物理文件、主索引、标签
   索引，任一环节的行组计数、行号区间或 relocation 映射错误都会造成
   重启后数据不可见；同时合并阶段的重复解析是百万行以上规模的主要
   成本之一。
5. 可恢复性：检查点指针与 body 文件的生命周期、元数据文件状态迁移
   需要在异常重启下保持闭合，不能依赖人工修复。

## 七、技术路线与实验方案

### 7.1 技术路线

采用“原型实现 — 埋点观测 — 受控实验 — 瓶颈定位 — 局部优化”的迭代
路线。所有优化以不推翻写读分离总体架构为前提，先保证打标、合并、恢复
的正确性，再追求吞吐；每个阶段的结论以日志和分段指标为证据，无法复现
的历史数据一律降级标注，不纳入验收基线。

### 7.2 实验环境

使用隔离的单机验证运行时（独立 PIXELS_HOME、独立 Trino/Metadata/
IndexServer/Retina 端口），不影响既有部署；构建与运行严格区分 JDK
版本（Retina 侧 Java 11 兼容性已处理）。数据生成器输出 68 列 CSV，
空值统一以 NULL 语义输出，避免空字符串与 SQL NULL 混淆。

### 7.3 数据集与规模阶梯

按 1M、10M、100M、1B 行四级推进，每级独立建表，冷启动加载与稳态
写入分开测量；同时保留 3 列窄表作为列宽对照基线，区分“行宽带来的
编码成本”与“索引发布的固定成本”。

### 7.4 测量指标与对照实验

- 端到端指标：各阶段 SQL wall-clock、rows/s，UPDATE 计时严格排除
  Compaction；
- 路径指标：路由判定结果、RPC 次数与请求体积、Tag Index 服务端
  decode/storage 分段耗时；
- 索引指标：single-point 与 main-index 写入耗时、vnode 初始化耗时、
  cold/warm 对比；
- 对照组：exact 与 merge 路径、宽表与窄表、物理表对表 INSERT 与
  UNNEST 合成 INSERT、串行与客户端并发发布；
- 正确性与恢复：Compaction 后标签谓词行数校验、重启后 count/min/max
  与标签计数校验、同时间戳检查点重发回归测试。

### 7.5 验收规则

每次结果必须记录提交 ID、运行时路径、配置、精确 SQL 与完整日志；
满足路由可验证、UPDATE/Compaction 计时分离、合并后谓词结果正确、
重启后计数不变四项条件方可计入论文数据。

## 八、已有工作基础

课题已在两个私有仓库（pixels-dml、pixels-trino-dml）的验证分支上
完成主体实现并取得阶段性证据：

1. Trino exact UPDATE 已支持百万行单 RPC 批量标记，merge 兜底批大小
   提升并补齐路由指标；窄表精确主键 UPDATE 实测约 271k–362k
   rows/s，日志确认 exact_eligible=true、rpc_count=1；
2. Tag Index 完成按列隔离改造与 decimal 精度修复，68 列双标签列
   端到端正确性（UPDATE → Compaction → 标签 SELECT）在万行级干净
   环境验证通过；
3. Compaction 实现批量主键解析与主索引修正，INSERT fragment 行号
   区间持久化发布，检查点同路径重发缺陷已修复并配有回归测试；
4. 瓶颈已量化定位：冷启动下 128 个 vnode 列族串行初始化主导首次
   INSERT 成本，稳态下 primary-index 发布约 7.5–7.9 s/百万行；客户端
   4/16 线程并发发布经对照实验证实退化，已否决；
5. 68 列宽表稳态实测约 INSERT 55.6k rows/s、UPDATE 61.5k rows/s，
   与窄表历史高吞吐的差距已通过对照实验部分归因于列宽与运行时状态
   差异，历史 410k rows/s 的 INSERT 记录因日志不可复现已主动降级为
   历史参考而非验收基线。

待完成工作包括：vnode 预热与服务端分组写入、Compaction 重复解析
消除、恢复流程自动化、10M 以上规模阶梯实验。

## 九、进度安排

（按春季答辩节奏安排，具体以学校日历为准）

| 阶段 | 时间 | 主要工作 |
| --- | --- | --- |
| 第一阶段 | 2026.12 | 完善文献综述与开题答辩；修正数据生成器 NULL 语义；固化 1M 干净基线 |
| 第二阶段 | 2027.01 | 68 列 warm 1M/10M 对照实验；实现并评测 vnode 预热 |
| 第三阶段 | 2027.02–03 | 服务端分组 primary-index 写入设计与实现；Compaction 重复解析优化 |
| 第四阶段 | 2027.03 | 恢复流程自动化；100M 规模阶梯与重启恢复验收 |
| 第五阶段 | 2027.04 | 论文初稿，整理图表与全部实验证据 |
| 第六阶段 | 2027.05 | 论文修改、盲审应对与答辩准备 |

## 十、预期成果

1. 一篇硕士学位论文，系统阐述列存引擎上基于 Tag Index 的行级更新
   机制、关键算法、正确性论证与性能评测；
2. 两个私有仓库中可构建、可复现的完整实现，以及配套的 benchmark
   数据集、指标埋点与验收脚本；
3. 一套覆盖 1M–100M 规模的实测数据与瓶颈分析结论，形成服务端索引
   优化的工程建议；
4. 在方案成熟后，评估将通用 DML 能力回馈 Pixels 公共主干的可行性。

## 十一、参考文献（待按学校格式核校）

1. O'Neil P, Cheng E, Gawlick D, et al. The Log-Structured Merge-Tree
   (LSM-Tree). Acta Informatica, 1996, 33(4): 351-385.
2. Sethi R, Traverso M, Sundararaman D, et al. Presto: SQL on Everything.
   ICDE, 2019: 682-693.
3. Armbrust M, Xin R S, Lian C, et al. Delta Lake: High-Performance ACID
   Table Storage over Cloud Object Stores. PVLDB, 2020, 13(11).
4. Apache Hudi 官方文档与技术博客：Merge-on-Read、record-level index
   设计. https://hudi.apache.org/
5. Apache Iceberg 规范：row-level delete（position/equality delete
   files）与 compaction. https://iceberg.apache.org/spec/
6. Apache Paimon 官方文档：primary-key table 与 LSM compaction.
   https://paimon.apache.org/
7. Facebook Engineering. RocksDB: An Embedded Persistent Key-Value Store
   for Fast Storage. 技术文档. https://rocksdb.org/
8. Pixels 项目文档与架构说明（serverless OLAP、Retina MVCC、Turbo
   弹性执行）. 项目仓库 docs/。
9. 本课题私有仓库验证分支上的两份技术 RFC：百万行 UPDATE/INSERT
   耐久性验证报告、68 列打标 benchmark 规范与实测记录。
