# Pixels DML 研究进度

更新时间：2026-11-25（Asia/Shanghai）。本文件是两个DML仓库的统一进度入口。
本次更新依据远端提交、源码和仓库实验记录，未独立重跑压测或核验当前部署。

**当前结论：** 原型与局部优化已有运行证据，但INSERT、UPDATE相对可比原路径的
总体收益尚未证明。当前阶段是建立双路径对照；下一项主要交付是总体成本解释，
不是继续增加微优化提交。

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
| INSERT | 记录有局部对照 | 带主键索引、grouped写入、32 writers，百万行Trino elapsed为4.025/4.023秒，约24.85万行/秒 | 等价初始状态下更多配对、客户端wall time、与可比原路径的总体对照 |
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
| P1 / INS-01 | 已有总体配对，待稳定性/阶段扩展 | 完整表到表INSERT对照 | 已完成两组fresh target grouped/非grouped对照；继续补齐更多样本及finish/exchange成本解释 |
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

[runs]: https://github.com/LeaseQ/pixels-dml/blob/e5946aa2/RFC_MILLION_UPDATE_INSERT_DURABILITY.md
[retina-test]: https://github.com/LeaseQ/pixels-dml/blob/a8e821b038dc365619510b09dac8db33bcfdf6b3/pixels-common/src/test/java/io/pixelsdb/pixels/common/retina/TestRetinaService.java#L283
