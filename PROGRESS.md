# Pixels DML 研究进度

更新时间：2026-11-25（Asia/Shanghai）。本文件是两个DML仓库的统一进度入口。
前半部分依据远端提交、源码和仓库实验记录；文末新增的exchange探针包含本轮现场
核验，具体版本、端口、query ID和排除原因以RFC为准。

**当前结论：** 原型与局部优化已有运行证据，但INSERT、UPDATE相对可比原路径的
总体收益尚未证明。当前阶段仍以双路径总体成本为主；新增的
`redistribute_writes=false` 在空目标上有跨writer收益迹象；同目标soak进一步显示，
32 writers下收益可复现，24 writers下基本持平。clean Trino crossover及预热交错
复测也已完成；追加到两张目标各900万行的更长交错soak中，关闭redistribution的
client wall time均值约3.990秒、开启约4.823秒，且内存采样未见增长异常。因此当前
INSERT候选仍是固定32 writers加关闭redistribution；当前观测到的Trino SQL elapsed
吞吐约350K--508K行/秒，最近三次false样本约350K--400K行/秒，客户端wall（含CLI
开销）约201K--260K行/秒，尚未达到百万行/秒，也尚未改变默认配置。正确指向8191
的JFR已把剩余成本定位到UTF-8解码、VariableWidthBlock复制、字符串读写与高对象分配；
重复字符串统计批处理候选未获E2E收益，且候选混合目标的后续完整读回出现
DecimalColumnReader越界，因此已撤回，不得推广为默认优化。常量字符串RLE候选此前的
计时因JVM属性误放在主类之后而未真正启用，已作废；修正参数后，主测例在PageSink
入口观测到string_blocks=0、string_rows=0，证明该RLE sink路径不适用于当前主测例。
随后在清理远端候选源码后，纯 `StringStatsRecorder` 解码缓存也完成了独立新表
对照：warm SQL 4.31秒，对照4.12秒，完整2M行/全标签读回通过但仍略慢，已撤回。

本轮修正并完成了RLE sink探针：一次完整1M行主测例在PageSink入口的
`string_blocks=0`、`string_rows=0`，说明字符串列已经以普通Block到达写入端，
因此此前RLE sink候选的计时全部作废，不能作为负优化证据。探针已撤回；后续应转向
上游投影/Exchange的Block构造和`VariableWidthBlock.copyPositions`分配路径。
对应分布式计划显示源TableScan为ROUND_ROBIN、100个输入分区，随后经
`LocalExchange[partitioning=SINGLE]`进入TableWriter；因此当前优先级已转向该
重布局边界，而不是PageSink的RLE分支。

本轮现场状态（2026-11-25）：8191已恢复stable connector/core与默认args，
`starting=false`；共享8090未重启。JFR有效样本、探针query ID、作废原因与最终jar哈希
见RFC末尾。本次修正推送前已核对当前远端文件版本为
`a79addbcb33997b15e95c4cd78aeb556aa915112`。

## 1. 已确认的目标与边界

- INSERT与UPDATE并列研究、分别评估，允许收益和贡献不对称；百万行/秒是探索目标。
- INSERT研究完整批量导入。UPDATE主实验为主键筛选、常量赋值和独立多轮打标，
  包含最终必要物化；非主键筛选、依赖更新及复杂表达式先核对能力边界。
- 报告SQL完成时间及必要的最终可查询时间，披露持久化配置。正常运行结果必须正确；
  故障恢复、故障注入不作为本阶段目标或性能门槛，不因此宣称已有恢复保证。
- 先建立可比总体对照，再选择优化。内部对照不能代替业界比较；外部实测在开题后
  推进，主要平台拟为鲲鹏。整体架构变更需另行讨论。

## 2. 本次核对的版本

两个仓库分支均为 `codex/million-update-validation`。下列SHA是证据截止点，
不代表所有实验都在同一版本执行；具体运行以RFC中的部署哈希、参数和query ID为准。

| 仓库 | 本次核对SHA | 主要变化 |
| --- | --- | --- |
| pixels-dml | [`e5946aa2`](https://github.com/LeaseQ/pixels-dml/commit/e5946aa2) | 反向映射惰性版本及位置迁移测试；修正固定writer数对照，拒绝INSERT候选与并发上调 |
| pixels-trino-dml | [`6b56954`](https://github.com/LeaseQ/pixels-trino-dml/commit/6b569544b7579ac3b0482941027b5e89e6b06534) | 主键解析默认并行度由1改为16；新增INSERT收尾计时 |
| 已确认研究范围 | [`93d98a51`](https://github.com/LeaseQ/pixels-dml/commit/93d98a517ab66da5403a7dc647b92e14ade3b3e4) | INSERT/UPDATE双目标、收窄实验范围及基线验证顺序 |

## 3. 已有什么证据，还缺什么

状态只表示证据进展；“代码已提交”“记录有实测”“运行中已部署”不能互相替代。
表中运行事实均来自[固定版本实验记录][runs]，不是本次现场复验。

| 工作项 | 当前状态 | 已有证据 | 尚缺证据 |
| --- | --- | --- | --- |
| 运行包一致性 | 记录有修正与读回 | 混用Connector/common/core后出现方法缺失及可见性错误；整体重建部署后新表读回通过 | 后续每臂继续固定全套jar与实际端口；不能沿用混包运行的性能结论 |
| INSERT | grouped对照完成；32-writer exchange候选有clean crossover和更长soak证据 | 带主键索引、grouped写入及完整标签门禁已完成；追加到12M/10M行后，false最新三臂约2.50--2.86s、true近邻对照2.94s；encoded-column-vector opt-out严格A/B反而慢约10%，已拒绝 | 更长采样或组件级instrumentation；默认化前仍需受控对照；8-writer臂因Retina OOM排除 |
| UPDATE | 代码默认值已改；记录有重复实测 | 主键解析由串行约8.27～10秒降至并行16下约1.39～1.45秒；新默认SQL elapsed 4.215秒，约23.7万行/秒 | 对应正常运行物化、最终标签与完整周期配对；并发负载表现尚不能推断 |
| MainIndexBuffer反向映射 | 记录有测试与对照；INSERT优化未采纳 | 惰性版本记录3项测试通过；同32 fragments配对为稳定版5.759秒、候选6.446秒，未获收益，运行包已恢复稳定版本 | UPDATE/COMPACT收益及内存代价仍待证明；分支保留代码不等于默认部署采用 |
| 原Retina对照 | 已识别语义缺口 | 现有测试“更新”为删除旧键并插入新键 | 保留主键、相同标签赋值及完成边界的对照适配；INSERT也需对齐输入准备及发布成本 |
| Hudi/StarRocks/Iceberg | 计划阶段 | 已列选型与语义核验要求 | 本次所读记录未见可比外部实测闭环，不能宣称领先 |
| 鲲鹏 | 计划阶段 | 已列平台兼容性与实验设计 | 机器规格、部署与同平台实验结果尚未核验 |

读数限制：

- 4.02秒是特定实验配置下的带索引结果；后续新表剖析为6.625秒，不能将前者视为
  所有初始状态下的稳定成绩。最新固定32-writer稳定臂为5.759秒；不能把不同轮次
  直接合成加速比。无主键索引的3.531秒不是支持UPDATE路径的替代结果。
- 上述SQL数字为Trino elapsed，不自动等于客户端wall time，也未包含UPDATE后续
  COMPACT。不能直接与旧环境约59万行/秒的结果计算回退或提升比例。
- 已有读回主要列出行数、主键和指定标签，不能自动推广为所有列、全部SQL语义
  均已验证。最新候选必须补与其修改相关的完整结果检查。
- RFC中的历史恢复门槛不增加当前研究范围；本阶段正常运行物化与读回仍然必要。

## 4. 下一步与完成条件

详细步骤维护在[执行方案阶段A](docs/superpowers/plans/2026-11-23-tagging-research-roadmap.md#4-阶段a双路径总体收益验证最高优先级)。本表只跟踪状态与交付，不另起一套实验计划。

| 优先级 / ID | 当前状态 | 下一项工作 | 完成条件 |
| --- | --- | --- | --- |
| P0 / BASE-01 | UPDATE/INSERT路由对照完成 | 确定INSERT、UPDATE各自可比的原路径或优化前版本 | UPDATE已完成parallelism=1/16控制；INSERT已完成grouped与非grouped路由对照；原Retina删旧键/插新键路径仍不可直接等价 |
| P0 / ENV-01 | 现场核验完成（两条路由） | 固定匹配版本、有效参数、源数据及每臂初始状态 | 两条路由的jar哈希、端口、schema、独立空目标、输入及最终校验已写入RFC |
| P1 / INS-01 | 已有总体配对；32-writer exchange候选已完成clean crossover和9M-row soak | 完整表到表INSERT对照，并验证 `redistribute_writes=false` 的计划、client wall time和标签正确性；两目标均9M行/9M distinct/全标签通过 | 重复clean-run并观察内存后，再决定是否改默认；当前不要把201K--260K行/秒推广为百万行/秒 |
| P1 / UPD-01 | 已有总体配对，待多轮扩展 | 主键常量赋值及独立多轮UPDATE对照 | 已完成两目标交叉顺序的SQL UPDATE与COMPACT/标签读回；独立多轮打标及重启不属于本轮新增门槛 |
| P2 / OPT-01 | INSERT候选未采纳 | 保留反向映射负结果；按总体成本证据决定是否继续研究定位开销 | 不将其作为已成立的INSERT优化；如另评估UPDATE/COMPACT，需独立位置迁移、内存和端到端配对证据 |
| 后续 / EXT-01 | 待平台与接口核验 | 外部基线与鲲鹏实验 | 按既有方案推进；正常运行语义与计时可比，实际结果留证 |

BASE-01与ENV-01可以并行推进；INS-01与UPD-01需要对应基线和环境就绪。
单个候选测试通过不能直接把总体收益标为“已证明”。

## 5. 当前阻塞与已知风险

| 项目 | 事实与影响 | 解除条件 |
| --- | --- | --- |
| 本地执行端到验证机器 | 2026-11-24从本会话环境连接此前指定机器的SSH 22端口超时，未进入认证；11月25日未重试。其他执行端有运行记录，不能推断机器整体不可用 | 从实际执行环境重新核验连通性、身份及隔离实例 |
| 原路径基线不可直接复用 | [Retina测试][retina-test]的删旧键/插新键负载不等价于保留主键的标签更新 | 完成BASE-01的适配或选定可比对照 |
| 输入与运行状态不等价 | RFC记录过旧源文件decimal读取错误、查询发往共享端口、插件混包；`count(*)`不能发现所有列读取问题 | 每臂核验端口和产物，完整投影检查输入，使用等价独立目标；无效样本保留排除原因 |

## 6. 文档分工与更新规则

- 本文件：当前状态、下一步、阻塞与证据入口。每次同步新提交或取得实验结果后更新，
  即使结果无收益或撤回也要记录；未检查的新变化不推断为完成。
- [开题报告](docs/thesis-proposal-20261123.md)：研究问题、相关工作、贡献候选与实验设计。
- [执行方案](docs/superpowers/plans/2026-11-23-tagging-research-roadmap.md)：对照步骤、配置要求与决策条件。
- [实验RFC](RFC_MILLION_UPDATE_INSERT_DURABILITY.md)：完整样本、运行配置、query ID及候选接受/拒绝原因。
- 更新性能结论必须带对应版本、输入/状态、完成边界、正确性检查与全部重复样本；
  不把单次最好值改写为稳定水平，不以组件速率替代SQL或全周期速率。
- 本文件保留当前状态及最近关键变化；更早进度通过Git历史追踪，不复制全部commit日志。

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

[runs]: https://github.com/LeaseQ/pixels-dml/blob/e5946aa2/RFC_MILLION_UPDATE_INSERT_DURABILITY.md
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
