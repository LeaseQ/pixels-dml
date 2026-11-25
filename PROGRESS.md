# Pixels DML 研究进度

更新时间：2026-11-25（Asia/Shanghai）。本文件是两个DML仓库的统一进度入口。
本次核对截止Pixels `6108b702`（2026-11-25 14:44 +08:00）、Trino `6b56954`。
依据fetch后的提交、源码及实验RFC核对，未独立复跑或连接部署环境。下文保留的
“现场”“本轮”等历史补记属于原执行端记录，不能理解为本次核对重新验收。

**当前结论：** INSERT分组写入、UPDATE并行解析已有内部SQL对照，新增六张标准根
新表的UPDATE→COMPACT→读回记录，正常物化证据有所加强。但尚未证明两条路径的
总体成本收益：原Retina对照、UPDATE完整周期配对计时和独立多轮实验仍缺。
本轮13条新增提交均为实验/进度文档，没有新的运行时代码或默认参数改动。

本轮主要变化：

- UPDATE六张新表的百万行SQL：p16为4.122/1.847秒，p8为4.654/2.390秒，
  p32为4.159/1.984秒。记录称六表均完成COMPACT，行数、唯一键、范围、最终标签
  及旧标签清零检查通过。p16第二臂约54.1万行/秒，只是热态单点；各设置第二次
  查询都明显更快，不能据此宣称稳定百万、确定最佳并行度或证明全周期收益。
- 六表记录没有各臂COMPACT墙钟，早期串行/并行对照也只有一臂保留约28秒COMPACT。
  另一个“完整闭环”记录有SQL 14.892秒、client wall 15.332秒和约35秒CLI COMPACT，
  但SQL赋值`U222`、最终校验`P2`存在未解释的不一致，暂不认可该条正确性结论。
  已检查的赋值编码路径按UTF-8编码字符串，未找到该标签转换依据；需核对原始SQL、
  query日志与后续覆盖顺序，不能直接判定运行时代码有错，也不能认可其自动映射解释。
- INSERT新增标准根交错实验固定16 writers，grouped threads按16→32→16→32切换，
  SQL为7.410/7.432/6.561/7.979秒，finishing为4.574/4.911/4.091/5.297秒，
  占约六成至三分之二。32 writers补测为7.976/8.240秒。瓶颈范围已收窄到收尾及
  索引flush协调；这些样本中planning/scheduling不是主要耗时，具体排队原因仍待追踪。
  新表交错及32-writer补测约12.1～15.2万行/秒，与此前热态追加35～50万不可混用。
- 最新p16/p24/p32/p48探索中，p32热态connector total为1263.357ms，较p16的
  1563.407ms少约19%。这是组件内部计时，且目标为无法COMPACT的历史双路径表；
  不属于SQL/完整周期收益，记录已恢复p16，当前无理由据此推广p32默认值。
- DictionaryBlock首轮候选部署目录错误，原A/B性能标签已撤回。修正后候选热态
  2.948/3.358秒、stable 3.079/3.317秒，均值仅差约1.4%，不足以证明收益。
  primitive sort和precreate快路径也未获采纳。UPDATE ordinal cache修改的是
  MergeSink，主键精确更新记录却对应executeUpdate路径，尚未证明命中被改代码；
  strict-resolve候选更已确认未命中本负载的`ignoreMissing=true`分支。
  后续先证明候选路径实际执行，再解释性能，避免继续投入未命中热点的微优化。

## 1. 已确认的目标与边界

- INSERT与UPDATE并列研究、分别评估，允许收益和贡献不对称；百万行/秒是探索目标。
- INSERT研究完整批量导入。UPDATE主实验为主键筛选、常量赋值和独立多轮打标，
  包含最终必要物化；非主键筛选、依赖更新及复杂表达式先核对能力边界。
- 报告SQL完成时间及必要的最终可查询时间，披露持久化配置。正常运行结果必须正确；
  故障恢复、故障注入不作为本阶段目标或性能门槛，不因此宣称已有恢复保证。
- 先建立可比总体对照，再选择优化。内部对照不能代替业界比较；外部实测在开题后
  推进，主要平台拟为鲲鹏。整体架构变更需另行讨论。

## 2. 本次核对的版本

两个仓库研究分支均为 `codex/million-update-validation`。下列SHA是证据截止点，
不代表所有实验都在同一版本执行；具体运行以RFC中的部署哈希、参数和query ID为准。
Pixels本地已快进至下列版本；Trino按fetch后的远端引用核对，未用落后的本地HEAD代表最新代码。

| 仓库 | 本次核对SHA | 主要变化 |
| --- | --- | --- |
| pixels-dml | [`6108b702`](https://github.com/LeaseQ/pixels-dml/commit/6108b7029d79d2abbdb574130f17a2d13b7830f1) | 自上次进度提交4a73ae76新增13条文档提交，仅修改PROGRESS与实验RFC；无新运行时代码 |
| pixels-trino-dml | [`6b56954`](https://github.com/LeaseQ/pixels-trino-dml/commit/6b569544b7579ac3b0482941027b5e89e6b06534) | 与上次核对相同；主键解析默认并行度16，已有INSERT收尾计时 |
| 已确认研究范围 | [`93d98a51`](https://github.com/LeaseQ/pixels-dml/commit/93d98a517ab66da5403a7dc647b92e14ade3b3e4) | INSERT/UPDATE双目标、收窄实验范围及基线验证顺序 |

## 3. 已有什么证据，还缺什么

状态只表示证据进展；“代码已提交”“记录有实测”“运行中已部署”不能互相替代。
表中运行事实均来自[固定版本实验记录][runs]，不是本次现场复验。

| 工作项 | 当前状态 | 已有证据 | 尚缺证据 |
| --- | --- | --- | --- |
| 运行包与路径一致性 | 记录有修正，仍需逐臂核验 | 混包曾导致方法缺失及读回错误；DictionaryBlock首轮改错插件目录；部分候选未命中被改代码 | 固定实际加载的jar、端口与执行路径，排除无效A/B标签 |
| INSERT | 内部分组SQL对照已有；新表热点收窄 | 非grouped 21.509/19.608秒，grouped 10.524/5.681秒；新表交错finishing占约六成至三分之二，指定标签读回通过 | 相同输入/状态的更多重复，收尾关键路径与队列追踪，原Retina及外部同口径对照 |
| UPDATE | 内部SQL配对已有；新增六表正常物化记录 | 串行9.937/11.567秒，对应并行16为3.921/3.989秒；另六张新表完成物化及指定标签读回，p16热态单点1.847秒 | 两臂从等价初态到可查询的完整计时、独立多轮扩展；U222/P2单条正确性记录待核实 |
| MainIndexBuffer反向映射 | 记录有测试与对照；INSERT优化未采纳 | 惰性版本记录3项测试通过；同32 fragments稳定版5.759秒、候选6.446秒，运行包已恢复稳定版本 | UPDATE/COMPACT收益及内存代价；分支保留代码不等于默认部署采用 |
| 原Retina对照 | 已识别语义缺口 | 现有测试“更新”为删除旧键并插入新键 | 保留主键、相同标签赋值及完成边界的适配；INSERT需对齐输入准备及发布成本 |
| Hudi/StarRocks/Iceberg | 计划阶段 | 已列选型与语义核验要求 | 本次记录未见可比外部实测闭环，不能宣称领先 |
| 鲲鹏 | 计划阶段 | 已列平台兼容性与实验设计 | 机器规格、部署与同平台实验结果尚未核验 |

读数限制：

- 此前32-writer追加redistribution交错false为2.077/3.491秒，true为3.155/3.320秒，
  均值2.784/3.238秒，约14%耗时差异。最新新表实验的源表、标签和目标状态不同，
  不能把其较慢的结果直接当作性能回退，也不能用旧追加最好值代表新表导入。
- Trino elapsed、包含CLI启动的client wall、connector total分别报告。多RPC及worker
  累计时间可能重叠，不能相加为墙钟；约35秒COMPACT与单次SQL也不是连续计时或配对加速比。
- UPDATE 14.892秒样本中primary resolve约13.262秒，encoding仅62.891ms，支持先查
  主键解析。服务端bucketMs偏大和高并发退化支持排队假设，但仅凭方法总时长不能区分
  executor等待、锁竞争与RocksDB执行，应补相应分段及队列数据。
- “完整标签门禁”需看具体谓词：多数记录核对全体行的tag_1或tag_1/tag_30，
  不等于68列全值正确；候选曾通过这些聚合后仍被stable reader发现decimal错误。
- RFC历史恢复过程作为运行背景保留，不增加研究范围。部署记录代表对应时刻，
  不代表本次已核实当前运行态；单条证据存在问题也不自动否定另外六张表的独立记录。

## 4. 下一步与完成条件

详细步骤维护在[执行方案阶段A](docs/superpowers/plans/2026-11-23-tagging-research-roadmap.md#4-阶段a双路径总体收益验证最高优先级)。本表只跟踪状态与交付，不另起一套实验计划。

| 优先级 / ID | 当前状态 | 下一项工作 | 完成条件 |
| --- | --- | --- | --- |
| P0 / EVID-01 | 单条正确性证据冲突 | 核对U222/P2记录的原始SQL、query日志、读回谓词和中间覆盖 | 解释并修正文档；如仍无法解释，在新标准根复测相同赋值；此前不把该条算作正确性通过 |
| P0 / BASE-01 | 内部基线已有；原Retina仍缺 | 区分并行解析、分组写入收益与原架构收益 | 补原Retina同主键标签赋值及输入/发布适配，不以内部路由对照替代整行回放比较 |
| P0 / ENV-01 | 运行记录已有；本会话未复验 | 每臂固定实际加载的包、参数、隔离端口及候选执行路径 | 保留部署哈希与路径命中证据；排除改错插件目录、未执行候选代码和历史多路径表的无效对照 |
| P1 / INS-01 | 新表finishing热点已有 | 在等价新表上追踪finish/index_finish与primary/main-index flush依赖及等待 | 保留全样本、队列/分段证据、SQL与客户端时间及读回；不能只用线程调参单点晋升默认 |
| P1 / UPD-01 | 六表物化记录已有；全周期配对缺失 | 补齐等价标准根两臂的SQL→COMPACT/发布计时，再做独立多轮 | 连续记录各臂总时间与阶段时间，最终指定标签正确；p32需同口径复测，故障恢复不新增为门槛 |
| P2 / OPT-01 | 多个候选未采纳或未证明命中 | 先验证候选路径覆盖，再按总体热点选择改动 | 不把未命中路径的波动解释为算法效果；编码/排序改动需总体配对收益与类型正确性证据 |
| 后续 / EXT-01 | 待平台与接口核验 | 外部基线与鲲鹏实验 | 按既有方案推进；正常运行语义与计时可比，实际结果留证 |

证据核对与基线适配可以并行推进。当前优先收敛可比总体实验及两个已识别热点，
不因多次微优化失败就推翻整体架构，也不将候选测试通过视为总体收益已证明。

## 5. 当前阻塞与已知风险

| 项目 | 事实与影响 | 解除条件 |
| --- | --- | --- |
| 本地执行端到验证机器 | 2026-11-24从本会话环境连接此前指定机器的SSH 22端口超时，未进入认证；11月25日未重试。其他执行端有运行记录，不能推断机器整体不可用 | 从实际执行环境核验连通性、身份及隔离实例 |
| 单条标签证据冲突 | RFC的Full UPDATE-to-queryable段赋值U222却校验P2；[赋值编码][update-executor]与[字符串转换][string-conversion]未提供该映射依据 | 完成EVID-01，不能沿用该条“正确性通过”解释 |
| 历史目标无法物化 | 最新p32探索及部分候选使用双路径目标，COMPACT报path metadata not found；旧嵌套布局也记录过读回旧标签 | 用标准根等价目标完成物化与读回，组件计时不能替代验收 |
| 原路径基线不可直接复用 | [Retina测试][retina-test]的删旧键/插新键负载不等价于保留主键的标签更新 | 完成BASE-01适配或选定可比对照 |
| 输入与运行状态不等价 | 旧源文件decimal错误、共享端口、插件混包和错误目录均曾干扰判断 | 每臂核验端口、实际产物及完整投影输入；无效样本保留排除原因 |

## 6. 文档分工与更新规则

- 本文件：当前状态、下一步、阻塞与证据入口。每次同步新提交或取得实验结果后更新，
  即使结果无收益或撤回也要记录；未检查的新变化不推断为完成。
- 用户要求更新进度时，核对后将本文件的修订提交并推送到共享million分支，再报告
  提交链接；不只保留本地修改。推送前同步远端、保留其他工作，正常执行Git hook。
- [开题报告](docs/thesis-proposal-20261123.md)：研究问题、相关工作、贡献候选与实验设计。
- [执行方案](docs/superpowers/plans/2026-11-23-tagging-research-roadmap.md)：对照步骤、配置要求与决策条件。
- [实验RFC](RFC_MILLION_UPDATE_INSERT_DURABILITY.md)：完整样本、运行配置、query ID及候选接受/拒绝原因。
- 更新性能结论必须带对应版本、输入/状态、完成边界、正确性检查与全部重复样本；
  不把单次最好值改写为稳定水平，不以组件速率替代SQL或全周期速率。
- 本文件保留当前状态及最近关键变化；更早进度通过Git历史追踪，不复制全部commit日志。

## 7. 历史执行端补记（按原记录保留）

以下为累计实验经过；当前结论以第2节版本对应的摘要与状态表为准。完整参数及样本
以RFC为准，旧的候选接受/否决及端口说明需结合后续纠正阅读。

最近关键变化：2026-11-24确认双目标与收窄范围；11月24日晚记录产物一致性修正、
并行解析默认值及32-writer实验；11月25日修正反向映射A/B的writer数差异，惰性
候选在同32 fragments配对中较慢，未采纳为INSERT优化；并发cap32复查同样未获收益。
本轮现场核验在隔离端口8191完成了UPDATE总体对照：parallelism=1的两次SQL
timeline为9.937/11.567秒，parallelism=16为3.921/3.989秒，均为1M行、1 RPC、
最终COMPACT标签读回全量正确；结果与完整阶段指标已记录在RFC末尾。profile_idx
目标的旧CLI目录元数据报错已由最新构建CLI复验通过，旧失败不计入性能样本。
随后完成INSERT grouped/非grouped的两组1M表到表对照：非grouped为21.509/19.608秒，
grouped为10.524/5.681秒；四个独立空目标均通过行数、唯一键、范围和标签校验。当前
可接受的warm grouped实测约176K rows/s，尚未达到最低几十万目标；下一步优先解释
Trino finish/exchange与sink/index_finish长尾，不再把单次最好值当作稳定吞吐。
对finish overlap候选的首次实现已撤回：候选jar在两个fresh target上使INSERT长期
RUNNING（90 drivers中89 blocked、零输出），查询已取消，原jar与默认参数已恢复。
该候选作为生命周期hang负结果记录在RFC，不计入吞吐，也不保留为默认优化。
随后发现该隔离实例的控制重测受Retina写缓冲`OutOfMemoryError`影响，节点被从hash
ring移除；该次卡住不计入性能。按原stable args并显式设置`PIXELS_HOME`重启Retina、
完成checkpoint恢复并重新加入hash ring后，1行smoke和999,999行stable INSERT均成功，
重测约112.5K rows/s，最终目标为1M行/1M distinct id/精确U111标签。该结果是恢复后
stable-route recheck，不替换此前grouped配对的176K结论；后续每个性能臂必须先检查
Retina健康，避免把运行时故障误判成吞吐结果。本轮记录基于进度文件版本
`497fe2ee1ced69cd0325c5f97eba7bb1d07543b4`。

恢复后进一步做了session-only writer-count探索：8 writers的SQL elapsed为6.93/2.76/2.45秒，
24 writers（min=24,max=32）为5.05/3.50秒；query detail中的TableWriter finishWall也随
writer数明显累积，但冷/热顺序仍造成较大波动。随后完成8/16/24/32 writers sweep：16
writers两次为3.05/2.68秒（约328K/373K rows/s），且finishWall为4.33/4.68秒；8 writers
虽有408K最好值但存在6.93秒冷尾，24/32 writers的finish尾部更大。严格重启交叉后发现
第一臂无论16还是24都约7秒、第二臂都约3.2--3.4秒，warm pair也没有稳定端到端胜者；
因此writer数只确认影响finish生命周期，不能据此改默认。所有矩阵目标均通过1M行、唯一键、
范围和U111标签门禁，当前默认Trino args未改变。
本轮交叉执行前已同步检查远端进度文件版本
`e93fca3629266372ebc500aab1703fa812b0f80c`。

随后对Trino写入前的`redistribute_writes`做了计划和E2E探针：关闭该选项后不再
产生独立的stage-2 source/task-output与stage-1 remote exchange，仍保留本地
ROUND_ROBIN和Pixels writer。固定16 writers下的两组顺序交叉为关闭5.02/3.96秒、
开启5.91/5.57秒，四个目标均通过1M行、唯一键、范围和U111/111.0000标签门禁；
恢复后再跑的开启/关闭为11.03/7.86秒，作为健康复核而非新headline。该运行时
候选尚未改默认；复用目标继续做同目标追加soak后，24 writers的false/true为
3.26/2.71、2.14/2.74秒，均值基本持平；32 writers的false/true为1.96/2.61、
2.00/2.48秒，收益可复现，且四个目标均通过300万行、唯一键、范围和标签门禁。
因此下一轮INSERT基准固定为32 writers加`redistribute_writes=false`；clean Trino
首臂/次臂的冷热差异已确认，预热交错的false/true SQL均值为2.72/3.45秒，且
目标已通过700万行、唯一键、范围和标签门禁。仍需更长soak和内存护栏后才考虑
默认化。本次修订前检查的远端进度文件版本为
`b0a4ac0d2d2f1ba898380f7f09198cf404e6a2c7`。
8-writer扩展因连续创建索引目标触发隔离Retina OOM而排除；Retina已从
`recovery_cds20_860063.bin`恢复、重新加入hash ring，并通过一行打标读回。
本次提交前检查的远端进度文件版本为
`3b772a903309ff83923127e1f040433059c64edf`。

本轮在不创建新索引目标的前提下，把固定32-writer交错soak从700万行扩展到
两张目标各900万行：false/true的SQL elapsed均值为2.10/2.96秒（约476K/339K
rows/s），client wall均值为3.990/4.823秒，四臂均成功，
两张目标均通过900万行、900万distinct id、范围和完整tag_1门禁；Retina保持存活，
前后RSS与G1已用堆仅小幅波动。该结果支持继续使用候选session profile，但没有把
端到端吞吐提升宣称为百万行/秒。本次修订前检查的远端进度文件版本为
`62d33b0fba8919469e961b3ea1251fca83bbbc90`。

随后追加的三次false固定32-writer臂SQL elapsed为2.82/2.86/2.50秒，近邻true
对照为2.94秒；false目标与true目标分别通过1200万、1000万行的distinct-ID和完整
tag_1门禁。JFR仅得到4个Trino、2个Retina execution sample，主机没有perf命令，
因此没有从这次CPU探针推出新的代码热点。本次提交前检查的远端进度文件版本为
`c3c6e1a25c1511ed363f1e8f2d751315f6e9467d`。

随后对`enableEncodedColumnVector`做了严格A/B：false候选冷/热SQL elapsed为6.92/2.56秒，
stable true对照为6.22/2.32秒；候选约慢10%，已撤回且没有pixels-trino-dml代码提交。
两张目标最终通过1600万/1400万行、distinct-ID、范围和完整tag门禁；8191已恢复stable
connector与默认args。本次提交前检查的远端进度文件版本为
`5cf8f3a78d4489984382721824ea8b0c886eb518`。

[runs]: https://github.com/LeaseQ/pixels-dml/blob/6108b7029d79d2abbdb574130f17a2d13b7830f1/RFC_MILLION_UPDATE_INSERT_DURABILITY.md
[retina-test]: https://github.com/LeaseQ/pixels-dml/blob/a8e821b038dc365619510b09dac8db33bcfdf6b3/pixels-common/src/test/java/io/pixelsdb/pixels/common/retina/TestRetinaService.java#L283

2026-11-25 reader-side single-entry dictionary fast path：主测例的 tag 列确实是单值字典，
但三种批量填充变体（包括修复 writeIndex、保留逐行 readInt 的保守版）都被完整跨版本
E2E 门禁淘汰。候选进程内可读出 1M 行和精确标签聚合；恢复 stable core 后读取候选产物
在 `DecimalColumnReader.readSelected` 抛 `IndexOutOfBoundsException`，而 matched control
可读，因此候选耗时一律不计入吞吐。8191 已恢复 stable core/connector，PID `2091020`，
共享 8090 未触碰。结论：不能直接改 encoded-vector 的状态来绕过逐行字典 ID 写入；下一步
应转向只读 instrumentation 或 Exchange/block copy 的上游路径，并继续保留完整产物读回门禁。
本轮提交前检查的远端 `PROGRESS.md` blob 为
`639fa112af9fbaa25b61e1294060f4697219f639`。

2026-11-25 uniform dictionary-ID RLE reuse候选已完成严格冷/热交叉：候选
`3ad542a27a73cf6a58afa0a0be8b528e533e22e63ec59a17ca67d502bfde728f`冷态
8.480秒、稳定版8.560秒，热追加候选8.502秒、稳定版8.139秒；四个目标均通过
1M/2M行、distinct-ID、范围和完整标签门禁，候选未形成可复现收益，已拒绝，8191
已恢复stable core。该轮还确认Retina重启后的非首次部署会停在RECOVERING，手动调用
现有markReady RPC后恢复READY并重新提供19989；共享8090未触碰。下一步继续聚焦
VariableWidthBlock/Exchange上游分配与完整双路径总体成本，不再重复窄RLE缓存变体。
本轮提交前检查的远端 `PROGRESS.md` blob 为
`7229cf51c6b298ad6cc036f1a110c488e24a3295`。

随后对单值字典的`VarcharArrayBlock` backing复用候选完成了编译和6/6单测，
但首次隔离E2E在零输出时以`PIXELS_DATA_TYPE_ERROR`失败；稳定connector恢复后，
同源同参数控制臂成功写入1M行并完整读回（SQL 8.989秒）。候选源码已回退，未产生
吞吐证据，也未提交代码。JFR的`DictionaryBlockEncoding -> VariableWidthBlock.copyPositions`
调用栈仍指向Trino Exchange解码层，后续候选必须保持标准VariableWidthBlock类型契约，
或先完成完整的Block encoding集成测试。本轮提交前检查的远端`PROGRESS.md` blob为
`2bf2c8f6b745fbfc1e4d9adbdd5da1c0081068d3`。

随后完成了Trino-core私有classpath探针：`DictionaryBlockEncoding.readBlock`的
uniform-ID特判把单值字典转为原生RLE。SPI微基准显示重复flatten约3.20秒，而RLE
构造约0.020秒；真实E2E候选冷/热为7.024/4.789秒，stable对应8.303/4.484秒，
四臂均通过完整1M行门禁。该方向有机制和冷态信号，但热态慢6.8%，且冷热/重启状态
尚未完全交错，暂不默认化；原生DictionaryBlock保留探针8.314秒也已拒绝。8191已
恢复默认classpath和stable connector/core，共享8090未触碰。下一步需要真正的
Trino-core源码改动、block级回归测试和交错冷/热矩阵，而不是继续使用classpath覆盖。
本轮提交前检查的远端`PROGRESS.md` blob为
`6227b463612e5cc6d790ed05aba7315238fa4331`。

2026-11-25 对 Trino 466 的 `PageSplitterUtil` 做了隔离 2MiB 最小分页阈值
探针，私有 jar SHA 为 `e88b1913581b61a00a6bb7de768db1189c37b898d1f95ac44ddf4fdd0d697eff`。
候选在新建索引目标上冷/热 SQL 为 7.27/4.74 秒（约 137.6K/211.0K 行每秒），
默认 1MiB 对照为 9.71/4.47 秒（约 103.0K/223.7K 行每秒）；两张目标均通过
2M 行、distinct-ID、连续范围和完整 tag 门禁，但候选热态慢 5.7%，因此拒绝默认化。
本轮同时发现隔离 `pixels.properties` 的 etcd 端口曾被改成 12389，而实际隔离 etcd
监听 12379；已保留备份并修正为 12379，否则 Trino 重启会卡在 catalog 初始化等待。
8191 已恢复默认 classpath、stable connector/core 且 `starting=false`，共享 8090 未触碰。
本轮提交前检查的远端 `PROGRESS.md` blob 为
`ad74a9859427de269923e11d3d3b29524d82d79f`。

2026-11-25 对普通 INSERT 的 reader 全选路径做了隔离候选：无隐藏列、无
Retina 可见性过滤时跳过全量 Bitmap 并调用已有 `read`。候选冷/热 SQL 为
7.69/4.54 秒，稳定对照为 8.44/6.78 秒；候选初看约 130K/220K rows/s，
但 2M 行候选物理输出约 103MB，稳定对照约 582MB。稳定 core 跨版本读取
候选产物时，`tag_1_para` 等 decimal 列稳定复现
`DecimalColumnReader.readSelected` 的 `IndexOutOfBoundsException`，所以
候选被否决，未提交代码；远端源码和 8191 已恢复 stable core，SHA
`baa636cc67772c5c078a30a10cd7c3891aab11ec3dd0f02a48a32254b2cdfa12`，共享
8090 未触碰。该轮说明普通全选优化必须增加全列类型、跨 stable core 读回和
物理输出等价门禁，不能只看 count/distinct/tag_1/tag_30。完整证据见 RFC
末尾。本次提交同步记录本轮结果和进度文件版本。

2026-11-25 完成 grouped-primary 线程、Trino writer 和 index flush 并行度的
隔离扫参。当前 branch connector 仅用于埋点，未推广代码；稳定 core/connector
已恢复，8191 与共享 8090 的隔离边界保持不变。Retina/index-service 实际是
grouped primary executor 所在进程，因此线程参数必须注入 Retina，而不是只改
coordinator/worker。32/64/16 grouped threads 的代表性 SQL 分别为
7.237s/11.868s/6.601s，64 线程因 RocksDB 竞争退化；16 threads + 16 writers
的 warm 追加为 4.671s，即约 214.1K rows/s，完整 3M 行门禁通过。16 threads
+ 8 writers 为 5.015s（约199.4K），不如 writer=16。flush.parallelism=8
冷/热为8.721s/4.873s，仍略慢于默认4.671s，拒绝默认化。完整记录见
`RFC_MILLION_UPDATE_INSERT_DURABILITY.md` 末尾。

本轮还纠正了运行环境记录：独立 e2e etcd 实际为 12389，共享 runtime etcd
为12379；独立 pixels.properties 已恢复12389。Retina 重启可能清除尚未进入
checkpoint 的临时可见性，因此跨重启累计行数不作为吞吐证据；本轮每个性能臂
均在当次成功提交后完成正确性门禁。独立服务最终恢复稳定参数和 SELECT 1
smoke test。本次提交前检查的远端 `PROGRESS.md` blob 为
`55cbfbd9b1c5aef8166500a071b59619ad083735`。

2026-11-25现场交错复测进一步收窄了`redistribute_writes=false`的结论：在恢复后的
稳定隔离运行态、同一组已存在索引目标上，固定32 writers、`task_concurrency=64`、
关闭writer scaling的两臂交错追加分别为2.077/3.491秒，开启为3.155/3.320秒；
平均SQL elapsed约2.78秒（约360K rows/s）对3.24秒（约309K rows/s），false约快14%。
两张目标均通过4M行、4M distinct id、精确范围以及tag_1/tag_30全量校验；Retina RSS
约33GB，未见本轮追加导致的异常增长。由于首轮冷热和目标状态仍有影响，该结果只保留
为条件性benchmark profile，不改Trino默认，也不宣称百万行/秒。对应query为
`20261125_021804_00025_ew9z2`、`20261125_021813_00028_ew9z2`（false）和
`20261125_021806_00026_ew9z2`、`20261125_021810_00027_ew9z2`（true），完整门禁与
分析见RFC末尾。本次提交前检查的远端`PROGRESS.md` blob为
`faaed7bd6661d0a042eb5bb59c1893da8ed896c0`。

随后完成了一次只读定位和严格connector候选复核。JFR（真实1M行INSERT，query
`20261125_022249_00031_ew9z2`）的热点集中在`VarcharType.getSlice`、
`VariableWidthBlock.getSlice`、UTF-8 decode、`StringColumnWriter.writeCurPartWithDict`
以及`StringColumnReader.readSelected`；allocation视图中`double[]`约35%、`byte[]`
约10%，说明剩余成本确实在Block/字符串转换与对象分配边界。基于此做的DictionaryBlock
批量预解引用候选已编译且`PixelsPageSinkTest`通过，但两轮重启交叉的热臂为candidate
3.087/2.943秒、stable 3.329/2.954秒，优势不复现，冷臂也基本持平；四目标最终
均通过完整标签门禁。候选已撤回，stable connector恢复，未提交代码。完整证据和query
ID见RFC末尾。本次提交前检查的远端`PROGRESS.md` blob为
`1a0c36ebba0ddffe296b846df9949c403bd9cea8`。

更正：上一段DictionaryBlock候选的首轮部署曾误替换`/opt`下的插件；8191实际加载
的是`runtime/trino/plugin`，因此上一段列出的candidate/stable query不能作为候选A/B，
只能视为stable追加，现以本段覆盖。随后在正确的8191 plugin目录完成有效交叉：
candidate热臂为2.948/3.358秒，stable热臂为3.079/3.317秒；candidate两次冷臂
6.992/7.342秒，stable为6.877/7.374秒。有效热均值仅约1.4%差异，冷均值candidate
略慢，故候选仍拒绝。有效query为`20261125_024650_00000_wv7nf`、
`20261125_024658_00001_wv7nf`、`20261125_024746_00000_bmpqj`、
`20261125_024754_00001_bmpqj`以及第二组
`20261125_024846_00000_tx9rs`、`20261125_024853_00001_tx9rs`、
`20261125_024939_00000_98up5`、`20261125_024947_00001_98up5`。8191最终恢复
connector SHA `62ecabdcb2c4ae384ee7f1603f938bc3a27c1d93f139a274c61cff490fe94039`；
两张目标最终为13M/12M行，distinct、范围、tag_1/tag_30全量通过。本次提交前检查的
远端`PROGRESS.md` blob为`298c33d55812588bb13a63dede825f3515f4ebd7`。

2026-11-25 完成 fresh-root 的 grouped-primary 线程/Trino writer 交叉复测。固定
`task_concurrency=64`、`task_min_writer_count=16`、`task_max_writer_count=16`、
关闭writer scaling，只在隔离 Retina 中切换 grouped primary threads：32线程臂的
SQL elapsed 为8.218/7.166秒（约121.7K/139.5K rows/s），16线程臂为7.275/6.179秒
（约137.5K/161.8K rows/s）。四个全新标准根目标均通过1M行、1M distinct、精确
主键范围和全量`U666/666.0000`标签门禁；在恢复32线程后再次读回仍全部通过。
本轮因此确认16 grouped threads在固定16 writers下有约13%--16%的交叉优势，但
样本仍只有两点/臂，不能宣称百万行/秒，也暂不改生产默认。隔离运行态已恢复32
线程稳定配置，共享8090未触碰。Retina重启后的`RECOVERING -> READY`使用既有
`markReady(recoveryEpoch)`本地流程完成。本轮提交前检查的远端`PROGRESS.md` blob
为`353b26305b1e738bff794ece8a4f834a8db88efa`。

2026-11-25 继续完成交错 fresh-root INSERT 和路由分解。固定16个Trino writer时，
按16→32→16→32 Retina grouped threads交错的SQL elapsed为7.410/7.432/6.561/7.979秒，
约135.0K/134.5K/152.4K/125.3K rows/s；四臂均通过1M行、distinct、精确范围和
全量`U666/666.0000`标签门禁。Trino日志显示planning/waiting/scheduling仅约100--240ms，
finishing为4.091--5.297秒；Retina grouped聚合日志显示32线程臂的main-index耗时汇总
更高，支持“索引finish/队列竞争”是当前主要瓶颈的判断，但不把16线程写成全局最优。

随后固定32个Trino writer再交叉16/32 Retina线程：7.976秒（125.4K）对8.240秒
（121.4K），仅约3.3%差异，说明线程收益依赖writer组合。两臂也都通过完整门禁。
最终隔离运行态恢复Retina 32线程、Trino p16，`SELECT 1`通过，共享8090未触碰。
当前仍不修改生产默认，下一步转向connector `index_finish`/flush边界的JFR或队列深度
证据。本轮提交前检查的远端`PROGRESS.md` blob为`8f9d573fd0be43540747736714df2dfc5fcd8d08`。

2026-11-25 对JFR指向的`MainIndexBuffer.snapshotForFlush()`做了primitive sort候选：
将百万级`Long[] + Collections.sort`改为`long[] + Arrays.sort`。cds20上
`TestMainIndexBuffer`通过3/3，daemon fat jar也成功构建。固定Retina 16线程和
Trino 16 writer的候选/稳定交错四臂SQL elapsed为7.741/6.306/6.735/6.887秒，
候选均值7.238秒，稳定均值6.597秒，候选反而慢约9.7%，故拒绝，不提交代码。
四臂初始1M行、distinct、范围和`U666/666.0000`标签门禁均通过；重启后第一组
出现已知的checkpoint前`TEMPORARY_INGEST`可见性丢失（候选940641、稳定0），
第二组均保留1M，不能把重启结果当耐久性证明。候选源文件保留为未提交本地改动，
远端源码和隔离运行态均恢复稳定版本。本轮提交前检查的远端`PROGRESS.md` blob
为`0cca896a7b720b0463421ea9e91284c6b1990863`。

2026-11-25 继续验证JFR暴露的`RocksDBFactory.preCreateColumnFamilies`全局锁：候选
保留缺失CF创建的串行慢路径，对已缓存CF走无锁快路径，并将状态map改为
`ConcurrentHashMap`。候选daemon SHA为`0c2f221c64c85ec4940c78586f4603ca863518a6f3b631702fc868e23ca3902b`，
稳定对照为`44615acdc6271b72599cf679def05f86b863e87f1de962d93b8fe4998fa8dfc1`。
固定16个Trino writer、task_concurrency=64、关闭writer scaling，在同一68列物化源上
使用四个独立fresh indexed root：候选重启/暖臂为9.32/7.04秒（约107.3K/142.0K），
稳定重启/暖臂为9.00/6.58秒（约111.1K/152.0K）。四目标均通过1M行、distinct、精确
范围与全量`U111/111.0000`标签门禁；候选均值8.18秒反而比对照7.79秒慢约5%，暖臂慢约7%，
拒绝，不提交、不改变默认。远端源码恢复SHA
`c890238edaaa7c9ffa7d32a656e85a62786da9a7c65080ef3fe2e9f7e83b9732`，8191/19990已恢复
稳定jar并完成`SELECT 1`，共享8090未触碰。对应query为
`20261125_051345_00081_bwk6b`、`20261125_051436_00084_bwk6b`、
`20261125_051938_00090_bwk6b`、`20261125_052016_00092_bwk6b`。本次提交前检查的远端
`PROGRESS.md` blob为`8fb0e75dbee2b8b6f27cf4c605f39aa4c1cb3847`。

补充读取隔离Trino server log后的准确timeline：候选query
`20261125_051345_00081_bwk6b`/`20261125_051436_00084_bwk6b`的SQL elapsed为
7.521/5.290秒，稳定对照`20261125_051938_00090_bwk6b`/
`20261125_052016_00092_bwk6b`为7.246/4.725秒；planning/waiting/scheduling均较小，
finishing分别为4.573/2.855秒对4.623/2.625秒。sink日志的最大
`index_finish_ms`约为候选4.02/2.46秒、对照4.08/2.39秒，未显示快路径消除finish尾部。
该补充不改变候选拒绝结论。本次提交前检查的远端`PROGRESS.md` blob为
`7e655401692721aa9c9bda7e534fd092156dd79e`。

2026-11-25 继续检查daemon主键解析：候选将`ignoreMissing=false`分支从逐key
`getUniqueRowId()`改为按vnode调用已有的`getUniqueRowIds()`，候选daemon SHA为
`d457b322362299dc14379139059dadd462aed8233556f13c4690bd671483f63a`，稳定SHA为
`44615acdc6271b72599cf679def05f86b863e87f1de962d93b8fe4998fa8dfc1`。随后确认
connector的`resolvePrimaryKeyBatch()`实际调用`resolvePrimaryEntries(..., true)`，
百万UPDATE已经走现有批量分支，因此候选不覆盖目标热路径；候选warm query
`20261125_062105_00002_7w7ux`为total=1799.703ms、primary resolve=1126.415ms，
稳定交叉query`20261125_062345_00000_dgeth`为total=5620.913ms、primary resolve=
4495.885ms，差异属于服务队列波动，不能归因于strict分支。候选源码和运行态均恢复
稳定，8191通过`SELECT 1`，共享8090未触碰；候选拒绝不推送。下一步应针对已经批量化
的RocksDB/队列路径做证据化优化，并继续使用single-root目标做完整物化门禁。
本次提交前检查的远端`PROGRESS.md` blob为`a86609c395b4040309035c13466876ae7f9468c4`。

2026-11-25 继续验证UPDATE的低风险connector候选：在`PixelsMergeSink`构造时预计算
更新列ordinal，消除每行/每列的`List.indexOf`。候选jar SHA为
`856bbd9e10b9024948f4f1df6402b3a7551815285778dbcf223478c68537d350`，稳定jar为
`62ecabdcb2c4ae384ee7f1603f938bc3a27c1d93f139a274c61cff490fe94039`。候选目标
`e2e68_precreate_fp_candidate2_20261125`的1M UPDATE query
`20261125_055514_00001_5j9pn`耗时11.431秒，connector total=8429.514ms，
encoding=109.157ms，primary-key resolve=7148.172ms；独立稳定控制目标
`e2e68_precreate_fp_control2_20261125`的query`20261125_060411_00000_qg6tf`
耗时6.296秒，total=2615.908ms，encoding=50.607ms，primary-key resolve=1621.180ms。
候选没有改善编码，且主要瓶颈仍是主键解析/服务队列，故拒绝并恢复稳定connector。
两目标为历史双path负例，COMPACT因`path metadata not found`不能作为最终物化正确性门禁；
后续完整E2E必须使用single-root目标。8191已恢复稳定并通过ready/SELECT 1，共享8090未触碰。
本轮推送同步记录本轮结果；本次提交前检查的远端`PROGRESS.md` blob为
`7e655401692721aa9c9bda7e534fd092156dd79e`。

2026-11-25 做了不改源码的UPDATE resolver并发探针：保持stable daemon
SHA`44615acdc6271b72599cf679def05f86b863e87f1de962d93b8fe4998fa8dfc`，只在
隔离8191重启时改变`pixels.update.primary-key-resolve-parallelism`，同一个
1M indexed precreate目标各跑冷/暖两臂。p16的connector total为2706.168/1563.407ms，
p24为2225.275/1477.153ms，p32为2401.190/1263.357ms，p48为2035.988/1459.271ms；
对应暖轮primary resolve分别为999.658/895.359/726.503/826.353ms。当前交叉点集的
暖轮最低点是p32，约比p16快19%，p24和p48没有超过p32。该目标是历史双path
precreate负例，不能做tag-aware COMPACT，所以只记录为resolver方向性证据，不能当作
完整UPDATE吞吐或正确性结论。8191已恢复stable p16并通过ready检查，daemon SHA未变，
共享8090未触碰；下一步仍需在single-root目标上完成UPDATE->COMPACT->readback交叉后，
再考虑是否提升默认并发。本次推送前检查的远端`PROGRESS.md` blob为
`5ee444a979185b8acc51efdd3c7bc37684635e0b`。

[update-executor]: https://github.com/LeaseQ/pixels-trino-dml/blob/6b569544b7579ac3b0482941027b5e89e6b06534/connector/src/main/java/io/pixelsdb/pixels/trino/PixelsUpdateExecutor.java
[string-conversion]: https://github.com/LeaseQ/pixels-dml/blob/6108b7029d79d2abbdb574130f17a2d13b7830f1/pixels-core/src/main/java/io/pixelsdb/pixels/core/TypeDescription.java#L1801
