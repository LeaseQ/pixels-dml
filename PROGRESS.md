# Pixels DML 研究进度

更新时间：2026-11-25（Asia/Shanghai）。本文件是两个DML仓库的统一进度入口。
本次核对截止Pixels `195fb105`（2026-11-25 18:14:06 +08:00）、Trino `6b56954`。
依据fetch后的提交、源码及实验RFC核对，未独立复跑或连接部署环境。下文保留的
“现场”“本轮”等历史补记属于原执行端记录，不能理解为本次核对重新验收。

**当前结论：** INSERT历史约47.6万/33.9万行每秒高值已被执行端纠正为无主索引写入，
不能作为带索引导入成绩。当前稳定带索引追加样本主要约20万至26万行/秒。本轮新增
daemon聚合候选E2E失败、pipelined write候选未显示收益的记录，均未进入分支源码。
UPDATE没有新增实验，保持p16的结论不变。
两条路径相对原Retina的完整任务成本收益仍未证明，不据此改变整体研究方向。
自上次进度摘要提交`8ce6778c`新增2条实验文档提交及1条合并提交，仅修改PROGRESS和实验RFC；
无新运行时代码或默认参数改动。本次提交仅同步进度摘要，不是新增实测或优化实现。

本轮主要变化：

- **daemon聚合候选没有有效性能结果：** `94bd93f5`记录尝试消除每行`List<Boolean>`，
  但查询以`GENERIC_INTERNAL_ERROR`/GOAWAY失败，32个文件缺少对应Retina visibility，
  后续读回报`RGVisibility not found`。不能把失败当作吞吐下降证据，也不能继续将原23M
  目标视为当前可读已复验。记录称文件保留、stable已恢复并完成READY握手；没有提供该
  失败目标恢复后的全量读回。本轮未连接现场，不能独立确认部署或目标恢复状态。
- **pipelined write没有显示收益：** `195fb105`记录两个独立新索引目标，同为32 writers、
  task64、WAL开启；候选首写/追加6.903/4.711秒，对照5.236/4.704秒。追加约212.3K对
  212.6K rows/s；两目标累计各2M行、唯一键、范围及tag_1/tag_30指定谓词检查通过。
  单组顺序样本不足以证明性能等价或排除RocksDB写入瓶颈，只支持不采纳这次候选；
  默认与源码均未改变。后续仍需重复配对和finish关键路径证据。
- **实验隔离约束：** 记录发现daemon与Retina共用active jar路径会导致替换时类加载失败。
  后续候选需独立jar路径；如果发生恢复，应先确认READY和目标读回再计时。这是避免
  污染正常性能样本的运行前检查，不增加故障注入或恢复能力研究目标。

此前已确认、此次保持的结论：

- **纠正样本资格：** 历史两张matrix16目标的9M soak曾被标为indexed INSERT；最新
  审核记录显示追加查询没有`PIXELS_INSERT_INDEX_METRIC`，SHOW CREATE未显示
  `pk/pk_scheme`，且路径元数据重复。执行端将其重新归为无主索引观察。原行数与指定
  标签读回仍可保留，但约476K/339K高值不能用于带索引性能结论；也不能据其直接算出
  索引开销，因为两类目标的状态不等价。详见[分类纠正][insert-classification]。
- 真正索引目标新增writer/task矩阵：24 writers（min=24,max=32，实际24 fragments）
  配task32的两次百万追加为3.812/4.330秒，约262.3K/230.9K rows/s；两次合计吞吐
  约245.6K。8/task32为5.836秒，24/task64为4.755秒。均是同一增长目标顺序追加，
  不能把24/task32定为最佳默认或代替空表首写；此前“16 writers收敛”的结论应保留为
  当时候选，不是最终结论。后续stable 32-writer追加也有4.103至4.790秒记录。
- [复用grouped bucket分段的候选][bucket-candidate]避免abort bookkeeping重复hash，
  热轮4.951秒，对照4.667秒，候选约慢6.1%。[批次共享主键字节数组候选][key-candidate]
  热轮5.689秒，对照4.196秒，候选耗时约高35.6%。两候选均拒绝，记录称隔离8191已
  恢复stable。两组只有少量顺序追加样本，不能据此严格排除hash或分配成本；只能说
  这些具体实现未证明端到端收益。继续优先定位主索引与finish收尾，避免凭局部热点猜测。
- 同一索引目标在上述失败候选执行前累计23M行/23M唯一键、范围及tag_1/tag_30指定
  谓词读回记录通过。这是历史累计校验，不是失败后的恢复确认，不是单次23M批量导入
  性能，也不是68列全值正确性证明。
- UPDATE仍以此前四张标准根新表的p16/p32正反顺序实验为依据：p32优势随顺序反转。
  p16同表热态两次SQL为1.531/1.535秒（约65.3/65.1万行/秒），connector total为
  1335.349/1350.100ms（约74.9/74.1万行/秒）。最新JFR29有物化与指定标签读回记录，
  但同表短序列不是独立多轮总体评估；本轮没有补充新的UPDATE或总体基线证据。
- [COMPACT内部计时][compact-timer]仍在标签快照准备、文件枚举之后才开始并截整秒数，
  16至17秒不能当作完整物化墙钟。主键查找singlePoint占并发RPC累计service约63%，
  [源码][rocks-lookup]确认逐key seek，但不能将此占比当成端到端CPU或线程池等待占比。
- `U222`赋值却校验`P2`的单条疑点仍未解释，继续保留EVID-01。原Retina同语义总体
  对照、外部基线和鲲鹏实测仍未补齐；不新增故障恢复验收条件。

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
| pixels-dml | [`195fb105`](https://github.com/LeaseQ/pixels-dml/commit/195fb105826ffeb7091868f1f81fb8b20af71da3) | 自摘要8ce6778c新增daemon聚合失败及pipelined write无收益记录、1条合并提交；无新运行时代码 |
| pixels-trino-dml | [`6b56954`](https://github.com/LeaseQ/pixels-trino-dml/commit/6b569544b7579ac3b0482941027b5e89e6b06534) | 与上次核对相同；主键解析默认并行度16，已有INSERT收尾计时 |
| 已确认研究范围 | [`93d98a51`](https://github.com/LeaseQ/pixels-dml/commit/93d98a517ab66da5403a7dc647b92e14ade3b3e4) | INSERT/UPDATE双目标、收窄实验范围及基线验证顺序 |

## 3. 已有什么证据，还缺什么

状态只表示证据进展；“代码已提交”“记录有实测”“运行中已部署”不能互相替代。
表中运行事实均来自[固定版本实验记录][runs]，不是本次现场复验。

| 工作项 | 当前状态 | 已有证据 | 尚缺证据 |
| --- | --- | --- | --- |
| 运行包与路径一致性 | 新增共享jar故障记录，执行端称已隔离 | daemon候选替换共用路径影响Retina类加载；已改为独立jar并记录stable恢复 | 逐臂核验实际加载产物、READY及目标读回；端口健康不等于失败目标数据已恢复 |
| INSERT | 新增一次无效E2E和一次未见收益的对照 | 稳定追加主要约200K–260K rows/s；pipelined候选/对照追加4.711/4.704秒，两新目标各2M指定检查通过；历史23M目标随后发生读回错误 | 重复且交错的等初态对照；失败目标后续读回；收尾关键路径与原Retina同口径总体对照 |
| UPDATE | 本轮无新实验；既有p32优势不稳定 | 早期串行/并行SQL对照已有；既有p16热态重复1.531/1.535秒、最新标签物化通过，首组两侧有CLI内部17/16秒记录 | 包含快照准备与发布的完整墙钟、相对既定基线的配对及独立多轮；U222/P2待核实 |
| MainIndexBuffer反向映射 | 记录有测试与对照；INSERT优化未采纳 | 惰性版本记录3项测试通过；同32 fragments稳定版5.759秒、候选6.446秒，运行包已恢复稳定版本 | UPDATE/COMPACT收益及内存代价；分支保留代码不等于默认部署采用 |
| 原Retina对照 | 已识别语义缺口 | 现有测试“更新”为删除旧键并插入新键 | 保留主键、相同标签赋值及完成边界的适配；INSERT需对齐输入准备及发布成本 |
| Hudi/StarRocks/Iceberg | 计划阶段 | 已列选型与语义核验要求 | 本次记录未见可比外部实测闭环，不能宣称领先 |
| 鲲鹏 | 计划阶段 | 已列平台兼容性与实验设计 | 机器规格、部署与同平台实验结果尚未核验 |

读数限制：

- 历史matrix16高值已重新归为无主索引观察；第7节保留原记录，仅作历史，不沿用其
  indexed标签及默认候选判断。此前bucket/key候选的热轮差异不构成单因素因果证明，顺序追加
  的目标大小、缓存与进程状态仍需控制。23M累计校验不等于23M单次导入测量。
- 新pipelined write两臂各只有一次首写和一次追加；约0.15%的追加时间差不足以宣称
  加速或统计等价。daemon聚合候选没有有效性能样本，且部署故障干扰了归因，不能
  直接认定聚合算法自身引起失败。该轮反例不否定整体方向，也未证明该方向有总体收益。

- INSERT早期热态追加最好值、新表导入、当前增长目标追加不是同一负载。正反顺序并不
  自动消除目标生命周期差异；均值4.426/5.844秒可描述样本，不能称为稳定24%收益。
- Trino elapsed、含CLI启动的client wall、connector total、COMPACT内部计时分别报告。
  多RPC/worker累计时间可能重叠，不能求和为墙钟；旧约35秒进程时间与新16～17秒内部
  时间口径不同，不能据此宣称COMPACT加速了一倍。
- singlePoint/service约63%来自并发RPC累计时间。[服务端计时][resolve-metrics]从
  方法进入后开始，未单独测量进入方法前的executor等待；当前数据不能证明线程池排队
  或RocksDB I/O的独立占比。源码只确认逐key seek存在，需在正确服务进程补采样/分段。
- “完整标签门禁”需看具体谓词：多数核对tag_1或tag_1/tag_30，不等于68列全值正确；
  历史候选曾通过这些聚合后仍被stable reader发现decimal错误。
- 同表两次热态UPDATE及最终版本读回覆盖的是一个短序列，尚未系统改变轮数/覆盖率，
  统计累计更新与最终物化成本；不要求每轮都COMPACT。历史恢复记录仅作背景。

## 4. 下一步与完成条件

详细步骤维护在[执行方案阶段A](docs/superpowers/plans/2026-11-23-tagging-research-roadmap.md#4-阶段a双路径总体收益验证最高优先级)。本表只跟踪状态与交付，不另起一套实验计划。

| 优先级 / ID | 当前状态 | 下一项工作 | 完成条件 |
| --- | --- | --- | --- |
| P0 / EVID-01 | 单条正确性证据冲突 | 核对U222/P2记录的原始SQL、query日志、读回谓词和中间覆盖 | 解释并修正文档；如仍无法解释，在新标准根复测相同赋值；此前不把该条算作正确性通过 |
| P0 / BASE-01 | 内部基线已有；原Retina仍缺 | 区分并行解析、分组写入收益与原架构收益 | 补原Retina同主键标签赋值及输入/发布适配，不以内部路由对照替代整行回放比较 |
| P0 / ENV-01 | 新增共享jar与缺失visibility记录；本会话未复验 | 每臂固定独立运行包、参数、端口、表DDL/元数据与索引路径 | 保留部署哈希、READY、索引指标和读回；失败目标未经复验不再使用；历史无索引数据单列 |
| P1 / INS-01 | daemon聚合E2E失败；pipelined write未见收益 | 不保留这两次候选；固定首写/追加初态，继续既定交错配对与转换、索引、finish关键路径测量 | 逐臂真实索引证据、等价数据状态、重复SQL与可查询墙钟、指定标签读回；以净总体收益决定候选 |
| P1 / UPD-01 | 既有四表物化；本轮总体计时未补 | 保留p16，补既定基线与候选的连续任务墙钟，解析singlePoint访问代价 | 包含SQL、快照准备、COMPACT/发布，明确读回计时边界；独立多轮正确；p32已反转，不再以单点推动默认变更 |
| P2 / OPT-01 | primary lookup热点收窄，改进尚未成立 | 先确认路径覆盖及查找分段，再探索保序的本地索引访问 | 若排序/批量访问必须还原输入位置并保持可见性语义；旧漏行和变慢负结果不得忽略；最终以完整任务配对验收 |
| 后续 / EXT-01 | 待平台与接口核验 | 外部基线与鲲鹏实验 | 按既有方案推进；正常运行语义与计时可比，实际结果留证 |

证据核对与基线适配可以并行推进。当前优先收敛可比总体实验及两个已识别热点，
不因多次微优化失败就推翻整体架构，也不将候选测试通过视为总体收益已证明。

## 5. 当前阻塞与已知风险

| 项目 | 事实与影响 | 解除条件 |
| --- | --- | --- |
| 本地执行端到验证机器 | 2026-11-24从本会话环境连接此前指定机器的SSH 22端口超时，未进入认证；11月25日未重试。其他执行端有运行记录，不能推断机器整体不可用 | 从实际执行环境核验连通性、身份及隔离实例 |
| 单条标签证据冲突 | RFC的Full UPDATE-to-queryable段赋值U222却校验P2；[赋值编码][update-executor]与[字符串转换][string-conversion]未提供该映射依据 | 完成EVID-01，不能沿用该条“正确性通过”解释 |
| 索引负载资格误标 | 历史matrix16高值已更正为无主索引；行数和标签检查不能证明执行索引维护 | 核对表元数据及每臂索引指标，重新建立带索引基线，禁止混用两类headline |
| 失败目标可读性未重新证明 | daemon聚合候选失败留下32个缺失visibility文件；记录称服务恢复READY，但未给出原23M目标恢复后的全量读回 | 保留现场，不把健康端口或新目标通过当作旧目标恢复；复用前单独验证，性能测试沿用新标准根 |
| 历史目标无法物化 | 早期p32和部分候选使用双路径目标而无法COMPACT；最新四张标准根新表已补正常物化，旧样本仍不能作为可查询结果 | 后续沿用等价标准根，旧失败记录不混入性能通过样本 |
| 计时与目标生命周期 | 新CLI报告排除快照准备；INSERT反序及concurrency sweep混用首写/追加与增长目标 | 在实际执行脚本保留连续墙钟并固定目标初态，不能只汇总最好分段 |
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

[runs]: https://github.com/LeaseQ/pixels-dml/blob/195fb105826ffeb7091868f1f81fb8b20af71da3/RFC_MILLION_UPDATE_INSERT_DURABILITY.md
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

2026-11-25 补做了真正的fresh single-root UPDATE完整闭环，修复了此前只在历史
precreate负例上比较p32的问题。以同一物化68列源表
`e2e68_fresh_candidate86_20261124`分别INSERT到新建的
`e2e68_update_p16_20261125`和`e2e68_update_p32_20261125`，两个目标均显式使用
单一table-root、`pk='id', pk_scheme='rocksdb'`，INSERT前均通过1M行、1M distinct
和精确ID范围门禁。p16 UPDATE冷/暖SQL elapsed为2.275/2.054秒，connector
total为1975.294/1871.248ms，resolve为1076.703/1027.485ms；p32为4.060/1.852秒，
connector total为2257.253/1487.603ms，resolve为1357.309/911.537ms。p32首臂有明显
planning/waiting/scheduling排队，暖臂更可比；暖轮connector total较p16低20.5%，但
仍只有一组fresh pair，暂不修改源码默认值。

两目标随后用修复目录过滤的`pixels-cli` target full jar（SHA
`e0d46cf1a3ed908b85b3037339abf63c12cafb69a30db66af9cbacd0c091d4aa`）、Java 23
`java.nio/sun.nio.ch`模块开放参数、`primary-resolve-parallelism=8`完成tag-aware
COMPACT；p16的tag snapshot/physical rewrite/resolve/publish为2.290/7.335/16.544/
9.991秒，CLI约17秒；p32为2.281/6.910/12.087/9.046秒，CLI约16秒。两个目标最终均
通过1M rows、1M distinct、精确范围、全量新tag及旧`U111`清零；Trino 8191重启后
再次读回仍通过。隔离Trino已恢复p16并ready，共享8090未触碰。Retina本轮未重启，
因此不把该记录扩展成新的Retina重启耐久性保证。本次推送前检查的远端
`PROGRESS.md` blob为`e6f41d8707bf9d05e08415b0c73cbcc735dc0679`。

随后做了反向顺序fresh-root交叉，以排除p16先跑造成的缓存/队列偏差：新建
`e2e68_update_rev_p32_20261125`和`e2e68_update_rev_p16_20261125`，先p32后p16，
两张表均完成1M INSERT门禁、UPDATE、tag-aware COMPACT和最终读回。p32冷/暖SQL
elapsed为3.829/1.809秒，connector total为2090.934/1538.005ms，resolve为
1255.115/930.887ms；p16为4.181/1.639秒，connector total为2438.027/1353.059ms，
resolve为1580.068/816.544ms。反向暖轮p16反而比p32快12.0%，两目标最终均为1M
rows、1M distinct、范围完整、指定新tag全量且旧U tag为0。两组完整E2E合并后，
p32不能被解释为稳定算法收益，更像resolver服务队列/缓存状态交互；源码默认继续
保持p16，下一步优先做队列深度/JFR和调度证据，不推广单个并发点。本次推送前检查的
远端`PROGRESS.md` blob为`f146bfaaedd3510d5c5345b2447ccdb5b4fab95b`。

2026-11-25 继续做真实single-root resolver探针：隔离8191保持stable p16，daemon
SHA仍为`44615acdc6271b72599cf679def05f86b863e87f1de962d93b8fe4998fa8dfc1`。
在`validationfresh.e2e68_update_rev_p16_20261125`上连续执行两次1M行精确UPDATE，
query分别为`20261125_073000_00008_9kijf`和`20261125_073004_00009_9kijf`，
Trino timeline elapsed为1.531/1.535秒，connector total为1335.349/1350.100ms，
primary-key resolve为790.521/706.468ms，折算connector吞吐约741K/秒和741K/秒。
此前直接用shell包住trino CLI的3.497/3.551秒包含CLI启动开销，不作为SQL吞吐。

同步在真实UPDATE期间对coordinator做JFR显式`jdk.ExecutionSample`采样，并检查
daemon的`PIXELS_PRIMARY_RESOLVE_METRIC`。一轮62个RPC共1,000,000 keys，累计
bucket/singlePoint/location/service分别为1037.490/5413.876/2139.612/8592.746ms；
单RPC均值为16.734/87.321/34.510/138.593ms，singlePoint约占service的63%，且是
最稳定的主要分段。gRPC IndexServer没有自建固定executor，resolver直接运行在gRPC
默认worker上；本轮JFR没有捕获到可归因的Java RocksDB栈，不能据此声称线程池是
瓶颈。当前证据更支持“RocksDB逐key seek/本地索引访问”为主热段，p16/p32差异仍
受队列和缓存状态影响。

随后将该目标最新`JFR29`版本执行tag-aware COMPACT（修复版CLI，`-c 8`，compact
primary resolve parallelism=8）。COMPACT的tag snapshot为2.612秒，physical
rewrite为6.806秒，compact resolve为13.741秒，publish为9.112秒，CLI报告约16秒；
最终读回门禁通过：1,000,000 rows、1,000,000 distinct、ID范围
`10000000001..10001000000`完整、`tag_1_type='JFR29'`为1,000,000、旧`U111`为0。
这轮没有重启Retina，共享8090未触碰；隔离8191仍为stable p16。下一步不直接推广
p32，而是围绕RocksDB primary lookup设计带正确性置换的排序/批量访问候选，并保留
UPDATE->COMPACT->readback门禁。

本次推送前重新检查的远端`PROGRESS.md` blob为
`7231a67475b2314c21fe2045353bb58d76aa13a6`。

2026-11-25 补做了当前稳定runtime上的table-to-table INSERT基线，使用同一物化
68列源表`e2e68_fresh_candidate86_20261124`、single-root indexed目标、stable
connector/daemon、grouped-primary和`task_concurrency=64`。16 fixed writers的默认
`redistribute_writes=true`目标`e2e68_insert_probe_20261125`，query
`20261125_074108_00012_9kijf`，Trino elapsed为6.821秒（约146.6K rows/s）；1M行、
1M distinct、ID范围`10000000001..10001000000`全部通过。其timeline为
planning/waiting/scheduling/running/finishing=`71/74/250/3644/2856ms`；16个sink的
`index_finish_ms`总和28.083秒、最大2.395秒，publish只有159ms。

同配置强制32 writers的目标`e2e68_insert_probe_32_20261125`，query
`20261125_074440_00015_9kijf`，elapsed为6.929秒（约144.3K rows/s），同样通过1M
行完整性门禁。32 writer使`finishing`升到3.774秒，`main_flush_ms`总和从16 writer
的0.858秒升到20.370秒，`index_finish_ms`最大值升到3.223秒；因此在该机器状态下
增加writer进入main-index/RocksDB flush争用，writer=32被否定。

随后在新的single-root目标`e2e68_insert_probe_off16_20261125`以16 writers打开
session级`redistribute_writes=false`，query`20261125_074719_00018_9kijf`，elapsed
为4.353秒（约229.7K rows/s），并通过同一1M行/1M distinct/精确ID范围门禁。该臂的
`finishing`仅184ms，16个sink的`main_flush_ms`总和0.663秒、`index_finish_ms`总和
30.679秒，publish为134ms。它是当前强候选，但因为执行顺序和热身状态仍不完全对称，
只记录为session级方向性证据，不修改默认值；下一步应做反向顺序的严格交叉，确认
exchange bypass收益是否稳定，再考虑代码级收尾调度实验。共享8090未触碰，8191仍为
stable runtime。

本次推送前重新检查的远端`PROGRESS.md` blob为
`e16e36d0857cc68d7b90d04518599964d45a46a9`。

为排除session顺序偏差，随后在新目标`e2e68_insert_probe_reverse_20261125`做了
反向顺序：先16 writers + `redistribute_writes=false`，query
`20261125_075100_00022_9kijf` elapsed 4.499秒（约222.3K rows/s）；再使用同一
目标、同一源表和16 writers + `redistribute_writes=true`，仅将id平移
`+1000000000000`避免主键冲突，query`20261125_075205_00024_9kijf` elapsed
4.867秒（约205.5K rows/s）。两臂均返回1M rows；最终目标为2M rows、2M distinct、
ID范围`10000000001..1010001000000`。标签保真也通过：`tag_1_type='U111'`且
`tag_1_para=111.0000`为2M，`tag_30_type='AAA'`且`tag_30_para=3.1400`为2M。

结合上一组`true->false`的6.821->4.353秒，当前两组的false平均约4.426秒、true
平均约5.844秒；但每组仍有冷/暖状态差异，不把这个均值当成全局默认收益。当前结论
收紧为：`redistribute_writes=false`是可重复方向一致的session级benchmark候选，
下一步若要推广必须在更多相同生命周期的交叉/soak中确认；默认仍不改。8191和
shared 8090均未改变。

本次推送前重新检查的远端`PROGRESS.md` blob为
`86eda394564942e6cab01384a29e6ad454e0e25b`。

在已通过2M行标签门禁的`e2e68_insert_probe_reverse_20261125`上又追加了1M行，
使用32 fixed writers + `redistribute_writes=false`，id平移`+2000000000000`。
query`20261125_075520_00028_9kijf` elapsed为4.983秒（约200.7K rows/s），目标最终
达到3M rows、3M distinct，范围`10000000001..2010001000000`，两组标签仍各为3M。
该臂的32个sink `main_flush_ms`总和13.896秒、`index_finish_ms`最大2.018秒，明显
不如16 writers + false的4.353秒；因此当前INSERT benchmark组合收敛为
`redistribute_writes=false`配16 writers，32 writers不再继续堆叠。该组合仍是会话级
候选，不修改全局默认，也没有改变共享8090。

本次推送前重新检查的远端`PROGRESS.md` blob为
`3d9186b8b01c8c81c9985ce4bf7ef348550567ef`。

在同一3M正确性目标继续做`task_concurrency`探针，保持16 writers和
`redistribute_writes=false`，每次平移1M ID。`task_concurrency=32`的query
`20261125_075752_00030_9kijf` elapsed为3.989秒（约250.7K rows/s），而
`task_concurrency=16`的query`20261125_075912_00032_9kijf` elapsed为4.866秒
（约205.5K rows/s）。后者的scheduling升到613ms，说明并发继续降低并不能消除
尾部。累计目标达到5M rows、5M distinct、范围`10000000001..4010001000000`，
`tag_1`和`tag_30`两组全量标签均保持正确。当前隔离benchmark候选收敛为：
16 writers + `redistribute_writes=false` + `task_concurrency=32`；这是运行时
profile，不修改Trino/connector默认。共享8090未触碰。

本次推送前重新检查的远端`PROGRESS.md` blob为
`95fc7b81c6b02c05685d3168ed328fb22cbbe772`。

[compact-timer]: https://github.com/LeaseQ/pixels-dml/blob/d407414101519abf554d5af246cc5a9155862a11/pixels-cli/src/main/java/io/pixelsdb/pixels/cli/executor/CompactExecutor.java#L267
[rocks-lookup]: https://github.com/LeaseQ/pixels-dml/blob/d407414101519abf554d5af246cc5a9155862a11/pixels-index/pixels-index-rocksdb/src/main/java/io/pixelsdb/pixels/index/rocksdb/RocksDBIndex.java#L138
[resolve-metrics]: https://github.com/LeaseQ/pixels-dml/blob/d407414101519abf554d5af246cc5a9155862a11/pixels-daemon/src/main/java/io/pixelsdb/pixels/daemon/index/IndexServiceImpl.java#L1255

2026-11-25 继续在同一已验证目标上做INSERT组合矩阵，均保持
`redistribute_writes=false`，每臂追加1M平移ID并做完整标签读回。8 writers +
`task_concurrency=32`的query`20261125_080141_00034_9kijf` elapsed为5.836秒
（约171.3K rows/s），低于16/24 writers。24 writers使用合法的
`min=24,max=32`，实际publish fragments=24；其task32 query
`20261125_080420_00036_9kijf` elapsed为3.812秒（约262.3K rows/s），优于此前
16/task32的3.989秒。相同24 writers切换task64，query
`20261125_080513_00038_9kijf` elapsed为4.755秒（约210.3K rows/s），说明task32
确实是当前较好的并发点。

对24/task32做warm repeat，query`20261125_080605_00040_9kijf` elapsed为4.330秒
（约230.9K rows/s）。9M累计目标最终通过9M rows、9M distinct、完整ID范围
`10000000001..8010001000000`，`tag_1`和`tag_30`全量标签均正确。两次24/task32
平均约245.6K rows/s；仍未达到百万级，因此不把单次262K当作稳定上限，也不修改
Trino/connector默认。当前下一步转向代码级减少finish/main-index flush等待；共享8090
未触碰。

本次推送前重新检查的远端`PROGRESS.md` blob为
`e3c3157abb60a9115578ae84bcdae2ae27fddf06`。

2026-11-25 对历史高值记录做了索引路径复核。此前记录为`e2e68_matrix16a/b_20261125`
的9M soak曾写成“indexed INSERT”，并据此报告false/true约476K/339K rows/s；但在
当前8191日志中复用这两张表追加1M时，query
`20261125_083021_00060_9kijf`（false，3.019s，331K/s）和
`20261125_083026_00061_9kijf`（true，2.780s，360K/s）均没有产生任何
`PIXELS_INSERT_INDEX_METRIC`，只有sink/write指标。表的`SHOW CREATE`也没有显示
`pk`/`pk_scheme`，且路径已经出现重复的`v-0-compact`/`v-0-ordered`组合。
因此这组历史高值只能归类为“无主索引宽表写入观察”，不能作为支持打标/主索引的
table-to-table INSERT吞吐，也不能用来证明百万行目标已接近。该纠正不影响它们的
行数、distinct、范围和标签读回结果，但收紧了测例资格。

当前真实索引路径的可复核点仍是
`e2e68_insert_probe_reverse_20261125`：32 fixed writers、
`redistribute_writes=false`追加query`20261125_081617_00046_9kijf`为4.103s
（约244K/s），随后同配置的`082114_00049`、`082729_00055`、
`082736_00056`分别为4.642/4.790/4.762s；累计15M rows、15M distinct、精确范围、
`tag_1`和`tag_30`全量门禁均通过。这个结果再次说明当前真实索引路径仍处于约
200K--260K/s区间，下一步必须继续优化主索引写入/finish尾部，而不是引用无索引高值。

本次修订前重新检查的远端`PROGRESS.md` blob为
`90dfeabcee9820313052e49d27894d8cca19f3d5`。

2026-11-25 对grouped primary INSERT做了一个低风险候选：RPC成功后的abort
bookkeeping不再对每行重复计算SHA-256 bucket，而是复用flatten时的bucket分段。
候选只部署到隔离8191，stable daemon/Retina、共享8090均未改变；候选在已通过15M
门禁的`e2e68_insert_probe_reverse_20261125`上追加两轮，query
`20261125_085308_00000_usju3`冷轮8.540s、`20261125_085403_00002_usju3`暖轮4.951s，
均保持distinct、精确范围、`tag_1`/`tag_30`全量正确。恢复stable connector后同目标
追加的交叉轮`20261125_085522_00000_vkwnd`冷轮9.171s、
`20261125_085617_00002_vkwnd`暖轮4.667s；可比暖轮候选约202K/s、stable约214K/s，
候选慢约6%。因此该候选不进入分支源码或默认配置；它证明重复hash存在，但不是当前
indexed INSERT的主导瓶颈。8191已恢复stable connector并`starting=false`，目标累计
19M rows/19M distinct、范围和两组标签门禁均通过。

本次修订前重新检查的远端`PROGRESS.md` blob为
`c9229cd50b9bb13a3ddf554b65a9fe20105728e7`。

2026-11-25 继续探测真实索引INSERT的主键构造成本：针对当前单`LONG`主键，候选在
每个row-batch分配连续8-byte backing array，并用`UnsafeByteOperations.unsafeWrap(array,
offset,8)`生成切片，避免每行分配独立短`byte[]`；编码字节、protobuf key和索引协议均
保持不变。候选只部署到隔离8191，在已通过19M门禁的同一目标追加两轮：
`20261125_090436_00000_5cb2y`为8.863s，`20261125_090447_00001_5cb2y`为5.689s，
最终21M门禁通过。恢复stable后追加交叉两轮：
`20261125_090624_00000_f48rh`为9.019s，`20261125_090635_00001_f48rh`为4.196s，
最终23M rows/23M distinct、精确范围及`tag_1`/`tag_30`全量门禁通过。可比热轮候选
约176K/s，stable约238K/s，候选明显变慢；因此该候选拒绝，不进入分支源码或默认配置。
8191已恢复stable connector并`starting=false`。

本次修订前重新检查的远端`PROGRESS.md` blob为
`48891a065ffed2392523e08b8daded1f3532fae1`。

[insert-classification]: https://github.com/LeaseQ/pixels-dml/blob/d407414101519abf554d5af246cc5a9155862a11/RFC_MILLION_UPDATE_INSERT_DURABILITY.md#correction-the-historical-476ks-soak-was-not-an-indexed-insert-2026-11-25
[bucket-candidate]: https://github.com/LeaseQ/pixels-dml/blob/d407414101519abf554d5af246cc5a9155862a11/RFC_MILLION_UPDATE_INSERT_DURABILITY.md#rejected-reuse-grouped-bucket-ranges-for-abort-bookkeeping-2026-11-25
[key-candidate]: https://github.com/LeaseQ/pixels-dml/blob/d407414101519abf554d5af246cc5a9155862a11/RFC_MILLION_UPDATE_INSERT_DURABILITY.md#rejected-batch-backed-long-primary-key-bytestring-slices-2026-11-25
2026-11-25 继续探测 grouped primary 路径的 daemon 聚合开销。候选把
`List<Boolean>` 结果改为 `boolean` 聚合返回，试图消除每行 Boolean 列表；候选 daemon
构建SHA为`664f1ce6485930f08453b4a962b6d0f71ad25d690117f5873c06f6e73b4883d6`。
首次部署时发现 daemon full jar 与正在运行的 Retina 共用路径，直接替换会让 Retina
加载到不完整/不匹配的类；随后改为候选 daemon jar 与 stable Retina jar 分离，候选
不会覆盖 stable active jar。

该候选没有形成有效吞吐样本：在已有23M索引目标上执行的 query
`20261125_091949_00005_f48rh` 以 Trino `GENERIC_INTERNAL_ERROR`/连接GOAWAY失败，
失败写入留下32个没有对应 Retina visibility 的 `.pxl` 文件，并触发读回时的
`PIXELS_BAD_DATA`（`RGVisibility not found`）。现场文件已保留并恢复到原目录用于
运行态恢复，没有据此计算吞吐，也没有把该目标的失败结果当作性能结论。stable
daemon/connector 已恢复，Retina 已通过现有 `get status -> markReady(recoveryEpoch)`
流程回到READY，19989/19990和8191均正常；共享8090未触碰。

候选源码已回退，不进入分支或默认配置；该轮结论为“未通过E2E门禁，拒绝”，不是
“性能变慢”的有效A/B证据。新增的运行时教训是：任何 daemon full jar 候选都必须
使用独立daemon路径，Retina保持stable jar；checkpoint recovery后必须先完成
`markReady`，再开始任何性能测试。本次推送前重新检查的远端`PROGRESS.md` blob为
`5de9596bf16b53d93010b18079e8d96d50fbe417`。

2026-11-25 对配置中已存在但源码未接通的`index.rocksdb.enable.pipelined.write`
做了一个默认关闭的隔离候选。候选 daemon SHA为
`127f1ed0e6f1ba208a87e2590e7fae9b63e463ee0b8efd1a9ee0e43786d62c72`，只在独立
daemon jar和候选配置中打开；WAL仍保持开启，Retina使用stable jar。候选新建标准根
索引目标后，首写 query`20261125_100827_00030_f48rh`为6.903s（约144.9K/s），
热追加`20261125_100918_00033_f48rh`为4.711s（约212.3K/s）。stable独立控制目标
首写`20261125_101105_00035_f48rh`为5.236s（约191.0K/s），热追加
`20261125_101146_00037_f48rh`为4.704s（约212.6K/s）。两目标均完成2M rows、2M
distinct、精确范围与tag_1/tag_30全量门禁；候选热态与stable无差异，首写反而较慢，
因此拒绝，不进入源码或默认配置。8191已恢复stable daemon/connector，active jar
SHA为`44615acdc6271b72599cf679def05f86b863e87f1de962d93b8fe4998fa8dfc1`，共享8090
未触碰。本次推送前重新检查的远端`PROGRESS.md` blob为
`370f5689ecd8c357e6566180b41ea34d0acbfd4b`。

2026-11-25 继续验证“connector 已算 bucket、daemon 再算一次 SHA-256”的重复路径。
候选给`PrimaryIndexEntry`增加向后兼容的`bucketId/bucketIdValid`字段，connector写入，
daemon仅在显式`pixels.index.grouped.primary.write.trust-bucket-hint=true`时使用；旧客户端
和默认路径仍走daemon hash。候选daemon full jar SHA为
`b23851e79f9a8e408b79e7bce34c4170ab93f546c4a4a8bcf5f0887026c09abd`，只部署到隔离8191，
stable Retina和共享8090未改变。

候选新建完整`pk=id, pk_scheme=rocksdb`的68列目标，query
`20261125_103345_00003_jwp4f`耗时11.785秒，约84.9K rows/s；count/distinct/min/max为
`1000000/1000000/19000000002/19001000001`。stable新建同形control目标，query
`20261125_103628_00002_pfxhr`耗时10.501秒，约95.2K rows/s；count/distinct/min/max为
`1000000/1000000/30000000002/30001000001`。候选慢约12.2%。更关键的是切回stable
daemon重启后，候选表的count和id/dim读取仍可用，但读取tag列报Trino
`GENERIC_INTERNAL_ERROR`；control可正常读回`U111/111.0000`，所以候选未通过支持打标的
读回/耐久门禁，直接拒绝，不进入源码或默认配置。候选proto、connector、daemon改动均已
回退；8191恢复stable daemon/connector，active SHA仍为
`44615acdc6271b72599cf679def05f86b863e87f1de962d93b8fe4998fa8dfc1`。

本次修订前重新检查的远端`PROGRESS.md` blob为
`5c4492f8750a8ea5cb54c20186b97151622b1edc`。
