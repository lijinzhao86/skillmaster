# agent skill 的触发率：事实、数字与提升方法

> **核对日期**：2026-11-02。
> **用途**：决定 SkillMaster 的「agent 自主检索并加载 skill」要不要换机制、换成什么；为后续版本的 PRD 与 ADR 提供事实底稿。
> **口径**：分三档逐条标注——**[一手]** 厂商官方文档、论文摘要或仓库 issue 原文，本次直接读过；**[转引]** 搜索摘要或第三方整理，本次没有读到原文，只作线索；**[未核实]** 找不到可靠出处。数字带样本与条件；单案例与规模实验分开写，不合并成「实测」。
> **本文不下决定**——跨版本的取舍属于 [`docs/decisions/`](../decisions/)；某一版做到哪属于 `docs/versions/`。文末 §八 只登记候选与未决问题。
> **结论先行**：自主触发不可靠是行业公认事实，不是提示词水平问题；它的上限由**机制**决定，不由措辞决定。对 SkillMaster 最要紧的一条是：**只给名字和描述、藏起正文（progressive disclosure），学术上测出路由准确率掉 37–44 个百分点**——而这是本产品当前的形态。

## 三句话结论

1. **「没触发」不是一种失败，是三种。** 发现（模型不知道有这个 skill）、路由（知道但选错/不选）、执行（选了但没照做或中途丢）——各由不同机制负责，混在一起谈就找不到解。
2. **确定性只有三个来源**：显式调用（`/name`、`$name`）、宿主 hook、自建 router / broker。业界凡是「必须每次发生」的事，都从提示词移进了这三种机制。Anthropic 官方 troubleshooting 的原话就是「must hold every time → move the rule into a hook」。
3. **远程 skill 是当前最弱的一格。** 各宿主的每轮路由都只读本机 skill 的 `name + description`；本机零副本的产品没有这条通道。学界测出：只给名字与描述、藏起正文，代价是 **37–44 个百分点**；而 SkillMaster 的正文本来就在服务端——**路由搬到服务端做，是别人没有的条件**。

## 一、先拆问题：三种「没触发」

| 层 | 症状 | 典型原因 | 谁负责 |
|---|---|---|---|
| **发现** | 模型不知道库里有这个 skill | 本机目录结构不对；`disable-model-invocation`；列表预算溢出把描述静默丢掉；远程不可见；harness bug | 宿主 / CLI / 产品 |
| **路由** | 知道有，但没选或选错 | 描述太宽或太窄；与内置能力重叠；多个 skill 语义撞车；只给 L1 不给正文 | 描述工程 / router |
| **执行** | 选了但没照做，或中途丢 | 上下文压缩后只重附开头；正文太长；模型觉得「这一步我会」 | hook / 正文结构 |

当前产品把三层都压在「模型读一段文字然后自觉执行」上，所以三层会同时漏。

## 二、各宿主现在的机制（事实）

| 宿主 | 每轮进上下文的东西 | 隐式触发 | 显式触发 | 已知硬限制 |
|---|---|---|---|---|
| **Claude Code** [一手] | 每个 skill 的 `name` + `description`（`when_to_use` 追加在后） | 模型判断，官方明说「model judgment, not a deterministic matcher」 | `/skill-name`（必须位于消息开头） | 列表预算约**上下文 1%**，超了按「最少使用」**静默丢描述**；每条 `description + when_to_use` 封顶 **1,536 字符**；压缩后每个 skill 只重附**前 5,000 tokens**（合计 25,000） |
| **Codex** [转引] | skill 的 `name` + `description` + 路径 | 按描述匹配 | `$skill-name`、`/skills` | 列表预算约**上下文 2%**（约 8,000 字符），超了先缩短再省略；`AGENTS.md` 常驻（默认 32 KiB）；`policy.allow_implicit_invocation: false` 可关隐式 |
| **Gemini CLI** [转引] | `name` + `description` | 模型调 `activate_skill` 工具 | 斜杠命令 | **默认弹确认**，同意后才注入正文；内置 skill 零确认；Plan Mode 强制确认 |
| **GitHub Copilot** [转引] | instructions 常驻；skills 的 `name` + `description` | 模型按描述选 | `#skill-name`、`/skill-name` | skills 在 VS Code 稳定、JetBrains 预览、Eclipse/Xcode 不支持 |
| **Cursor** [转引] | 取决于规则模式 | Agent Requested 模式由模型判断 | `@` 提及 / Manual | 四种模式里只有 `alwaysApply: true` 每轮必进；社区结论：「a rule is context, not a control」 |

**共同点**：所有「自动」路径都靠一段进上下文的描述；所有「确定」路径都要么显式、要么 hook/router。没有一个宿主为**远程 skill**提供原生发现通道。

## 三、社区与厂商的实测数字

单案例与规模实验分开看，不要合并成「实测」。

| 来源 | 方法 / 样本 | 数字 | 性质 |
|---|---|---|---|
| **Anthropic 官方 blog**（2026-03-03）[一手] | 对公开文档类 skill 做描述调优 | **5 / 6** 个 skill 的触发改善；未披露提升幅度 | 官方自报 |
| **GitHub issue #30387** [一手] | 用户自报，8 个 skill + 3 个 agent，预算 3,700 / 15,000 字符 | 与模型内置能力重叠的 skill（git、shell）**约 50% 被忽略**；不重叠的（自定义 Jira）稳定触发；加 `CLAUDE.md` 的 ALWAYS 也无改善；**未被维护者处理，按 stale 关闭** | 单用户报告，非受控实验 |
| **Scott Spence** [转引] | 200+ 次，Haiku 4.5；后续用 Sonnet 4.5 复测 | 基线 ~20%（Sonnet 复测 ~55%）；CLAUDE.md ~60%；SessionStart hook ~65%；LLM 评估 hook ~80%；**强制评估 hook ~84%**；手动 100%；Task 子 agent 内 **0%**（issue #14016） | 个人评测，条件披露不全 |
| **Ivan Seleznov** [转引] | 650 次试验 | 被动「Use when creating Dockerfiles」**77%** → 指令式「ALWAYS invoke… Do not attempt…」**100%**（OR=20.6，p<0.0001） | 单 skill、稀疏库 |
| **Show HN：lint 216 个公开 skill** [转引] | 对 216 个公开 skill 做静态检查 | **69% 不能可靠触发**：40% 描述「明显不会触发」，28% 边缘；83% 的 subagent 描述缺「use when」 | 静态检查，不等于运行时实测 |
| **GitHub issue #100813** [转引] | 用户报告，v2.1.295 | `/skills` 显示已加载，但**主 agent 收不到列表**（subagent 收得到） | harness bug 报告 |
| **Vercel 评测** [转引] | 同一内容两种形态 | 写在 `AGENTS.md` 里比做成 skill 触发更稳 | 厂商自评 |

**一个必须写进来的反例**：Spence 后续用 Sonnet 4.5 复测时，基线从 ~20% 升到 ~55%，强制评估与 LLM 评估都到 100%；而且他发现 hook 会被模型逐渐跳过，需要再加一条全局提醒。**所以「某个数字」绑定模型、宿主版本与库的规模，不能当常数。**

## 四、学术界：六个直接相关的结果

| 论文 | 结论 | 关键数字 | 性质 |
|---|---|---|---|
| **SkillRouter**（arXiv 2603.22455） | **只给名字 + 描述、藏起正文**，路由显著变差；缺失的信号在正文里，不在描述里 | 掉 **37–44 个百分点**；从正文蒸馏描述仍差 7–21 分；纯元数据编码器比全字段低 14.0 分；1.2B 的 body-aware 检索+重排做到 **74.0% Hit@1**（约 8 万 skill），小 13 倍、快 5.8 倍 | 摘要直接读过 |
| **技能库的相变**（arXiv 2601.04748） | 准确率不是线性下降：库小时稳定，过临界点**陡降**；主因是**语义相似的 skill 互相混淆**，不是数量 | 摘要未给临界点与百分比 | 摘要直接读过 |
| **Routing law**（arXiv 2605.16508） | 单步路由准确率随库大小**对数衰减**；过度通用的「黑洞 skill」会劫持 | 15 个前沿模型、1,141 个 skill、300 万次决策，**R² > 0.97**；法则引导的优化：`71.3% → 91.7%`，劫持率 `22.4% → 4.1%`，ClawBench `49.3% → 61.6%` | 摘要直接读过 |
| **Gavel / The Router Within**（arXiv 2609.15982） | 不把 skill 文本放进上下文：用冻结模型自己的隐状态做路由 | 比 progressive disclosure 与检索重排高 **13.4 分**（书面任务）、**21.9 分**（中途才需要 skill）；Qwen3-32B | 摘要直接读过 |
| **When2Tool**（arXiv 2605.09252） | 「该不该调工具」本身可测、可与「调哪个」解耦；必要性在隐状态里线性可读 | AUROC **0.89–0.96**；强制预判可少调 **48%** 的工具而只损 **1.7%** 准确率 | 转引 |
| **工具目录扩展与 Tool RAG**（ICTC 2026 等） | 目录越大准确率越低；**检索召回率封顶了可达到的准确率**；对大模型，RAG 的价值主要是省 token 与延迟 | 2B 模型随目录增长 `97% → 66.3%`；bge-m3 检索器 k=5 时 RAG 不提准确率、27B 反而 −18pp；召回 98% 时 2B +16pp、≥4B ≤4pp；≥4B 的收益是 **−91% prompt token、−71% 延迟** | 转引 |

补充两条基准事实 [转引]：

- **BFCL v4**：前沿模型整体 77–86% 的 relevance、78–88% 的 irrelevance；`multiple` / `live_multiple` 类别普遍 **0–10%**——多候选选择仍是硬问题。
- **基准本身不可全信**：Epoch AI 复核 BFCL v4，抽样约一半题目有缺陷，irrelevance 类别缺陷率最高。**单一榜单分数不能当结论。**

## 五、提升调用率的杠杆（按证据强度排序）

| # | 杠杆 | 证据 | 效果 | 代价 / 边界 |
|---|---|---|---|---|
| 1 | **显式调用** `/name`、`$name` | 各宿主一致 [一手/转引] | **100%** | 要用户感知；与「用户不用感知」直接冲突 |
| 2 | **强制评估 hook**：先让模型逐条写 YES/NO，再调用 | Spence 200+ 次 [转引]；issue #30387 的 workaround [一手] | 基线 20% → **84%** | 每轮多一次判断；会被模型逐渐绕过，需常驻提醒；宿主相关 |
| 3 | **描述工程**：命令式、面向意图、故意 pushy、写清「何时不该用」 | Anthropic 官方 [一手] 5/6；Seleznov 650 次 [转引] 77→100 | +20~25 个点 | 单 skill、稀疏库的结果；**拥挤列表里互相喊 ALWAYS 是否失效，没人测过** |
| 4 | **always-on 层**：CLAUDE.md / AGENTS.md / `alwaysApply` | Spence ~60% [转引]；Vercel 评测 AGENTS.md 优于 skill [转引] | 中等 | 占上下文；仍是「建议」不是控制 |
| 5 | **body-aware 路由**（服务端看正文再选） | SkillRouter [一手] | **74% Hit@1** @ 8 万 skill | 要自建 router；**正文在你们服务端，这是天然优势** |
| 6 | **层级化 / 命名空间** | 相变论文 [一手]、routing law [一手] | 抬高临界点、抑制语义撞车 | 需要产品层的组织设计 |
| 7 | **触发率评估 + 遥测** | agentskills 官方方法 [一手]；broker 记录 hit/misroute/fallback [转引] | 让每次改动可证伪 | 一次性投入：约 20 条查询 × 3 次，0.5 阈值，60/40 训练验证 |
| 8 | **上下文卫生**：预算、字符上限、压缩后重载 | Claude Code 文档 [一手] | 防止「静默不触发」 | 配置与正文结构 |
| 9 | **工具裁剪**：暴露越少、选错越少 | *Less is More*、*ToolChoiceConfusion* [转引] | 减少误选 | 要判断该露多少 |

**读这张表的正确方式**：1、2、5 是「机制」，收益确定但各有代价；3、4 是「概率」，能抬高但不能保证；6–9 是「放大器」，决定前面几项在库里还成不成立。

## 六、成本：Jev 与国产替代

### 6.1 Jev 是什么、多少钱

Jev 是 TypeSafe AI 的 **System One 决策模型**（`jev-1.13.0`）：输入一段文本 state 和一个受约束的问题，返回 `Choice` / `Score` / `Noul` 三种类型化决策，自带置信度；不生成文本。[转引]

- **官方价：$0.042 / 百万输入 token，输出不计费。**[转引：TypeSafe 报价页、OpenRouter、Vercel AI Gateway 口径一致；本次未读到官方 pricing 页原件（访问 404），价格以官方为准]
- **限制**：单请求 64K token，state + 最长问题合计 32K；限流来源不一致（80–100K token/s、80 req/s vs 250K token/s、1,200 req/min），按低的算。[转引]
- **不能自托管官方权重**。OneJev（0.8B–27B）、JevLite（Qwen3-4B）等是**独立实现**，不是官方 Jev。[转引]

**每次调用成本**（按 $0.042/M 输入、输出免费）：

| 每次送什么 | 估算输入 tokens | 每次成本 | 100 万次 |
|---|---|---|---|
| 全库描述（50 个 × ~80） | ~4,300 | **$0.00018** | $180 |
| top-5 正文（5 × ~1,500） | ~7,800 | **$0.00033** | $330 |
| top-10 正文（10 × ~1,500） | ~15,300 | **$0.00064** | $640 |
| 50 份正文（撞 64K 上限） | 64,000 | **$0.00269** | $2,690 |
| 极简判定（~447 token 的行业示例） | 447 | **$0.0000188** | $18.77 |

**口径**：token 数是按「一段意图 + 候选描述 / 正文 + 固定指令」估的，正文按每个 ~1,500 token 计；实际值随 skill 长度变化。行业示例（447 token、$18.77/百万次）来自第三方整理，不是官方数字。[转引]

**论文口径**（REFLEX，摘要直接读过）：

- REFLEX-Sim：**$0.00763 / 任务**，强模型单独做 **$0.01508 / 任务**；强模型调用 1.12 vs 4.10 次（−72.7%）。
- τ²-bench：REFLEX **$0.0572 / 局**，强模型单独做 **$0.2111 / 局**；但一个便宜的生成式级联（B3）是 **$0.0411 / 局**，成功率 0.917 vs 0.850。
- **结论：省钱来自「少调强模型」，不是「Jev 本身免费」。** 路由本来就简单时，便宜的小模型或级联可能同样便宜；Jev 的价值是**带校准的置信度**，让「什么时候升级」可判定。

### 6.2 国产替代：决策模型

2026 年 9 月 Jev 走红后，国内两周内出现同一品类的开源模型。**它们不是按 token 卖，而是开源权重、自部署；下表数字全部是厂商自测。**[转引]

| 模型 | 厂商 | 规模 | 能力 / 数字（厂商自测） | 形态 |
|---|---|---|---|---|
| **Intern-Decision** | 上海 AI 实验室 | 0.8B / 2B / 4B | 多模态；比 Jev 快 2–3 倍；4B 端到端 <45ms；0.8B/2B 约 30 次/秒；Brier 0.550 / ECE 0.089（Jev 0.595 / 0.130）；JevBench-hard 73.87%，低置信升级后 87.39% | 开源，自部署 |
| **StartLux-Decision** | 原点星辉 | 0.8B–27B 五档 + 量化 | 宣称 27B 在 38 项里 31 项超 Jev，综合 63.88 vs 57.91 | 开源，自部署 |
| **LingDT-Decision-1.0** | 蚂蚁数科 | 4B / 27B | 意图识别、任务分类、**工具选择**、风险判断；token 降 >90%、速度最高约 10 倍；支持原生多选 + 按序决策（DAG） | 开源，自部署 |
| **Xing4.0-29B-Intent** | 中国电信 | 29B | 意图分类 + 参数抽取合一，输出 `category+args` JSON；<150ms；5606 道冷启动测试类别 F1 ≈70、严格全对 61 | 开源，本地部署 |
| **decision-model-preview** | 阿里云百炼 | 未公开 | 国内大厂里唯一上架的决策模型；限时免费 | API |

**判断**：这类模型和 Jev 同属「类型化决策」，但没有一家给出按 token 的正式报价；成本是 GPU 时间。对 SkillMaster 最贴合的是 **Intern-Decision 0.8B / 2B**——小、快、开源、自带置信度。

### 6.3 国产替代：通用小模型 API

如果不想自部署，直接用通用小模型 + 约束输出（JSON schema）也能做决策层。按「4,300 输入 + 50 输出」估算：

| 模型 | 输入（元/百万） | 输出（元/百万） | **每次约** |
|---|---|---|---|
| 豆包 doubao-seed-2.0-mini | 0.1–0.2 | 1.5–2 | **¥0.0005** |
| 豆包 doubao-seed-1.6-flash | 0.15 | 1.5 | ¥0.0007 |
| DeepSeek flash（空闲 / 高峰，缓存未命中） | 1 / 2 | 4 / 8 | **¥0.0045 / ¥0.009** |
| Qwen-long | 0.5 | 2 | **¥0.0023** |
| Qwen-plus | 0.8 | 2 | ¥0.0035 |
| 对照：Jev | ≈¥0.30（$0.042） | 0 | **≈¥0.0013** |

**口径**：价格来自各家官方价格页的搜索摘要（2026-11-02），未逐页复核；豆包与 DeepSeek 都有分档/时段价，表里取最低档。Qwen 系列价格随型号与地域变化。[转引]

**读法**：豆包 mini 最便宜（比 Jev 低一半以上），但它**不带校准置信度**，要自己做阈值与评估；Jev 在这个对比里并不贵，贵的是它的「类型化 + 置信度」这套工程。

### 6.4 自部署的成本口径

```
每次成本 ≈ GPU 实例小时价 ÷ 该小时处理的判断数
```

按 30 次/秒算，一小时约 10.8 万次；即使 GPU 实例 ¥10/小时，每次也只要约 ¥0.0001。**但低频时不划算**——实例闲着也在计费。另外，生产 ECS 是 2 核 4G（`ecs.u1-c1m2.large`），跑不动 0.8B 的在线推理，自部署要先加 GPU 实例。

### 6.5 选型顺序（建议，不是决定）

1. **先试 API，不改基础设施**：豆包 mini 或 DeepSeek flash 空闲档，配约束输出。每次 ¥0.0005–0.009，先把 Recall@50 / Hit@1 / `none` 判对率量出来。
2. **量出来之后再考虑自部署**：如果升级率低、量又大，`Intern-Decision 0.8B` 把边际成本压到接近零，而且**用户意图不出 VPC**——这对隐私优先的产品是实质优势。
3. **阿里云 `decision-model-preview` 可以先试用**（限时免费），但它是 preview，不要当长期依赖。

**三个风险**：国产决策模型的数字全部是厂商自测，没有独立复现；这个品类才出现两个月，API/权重/价格都会变；通用小模型便宜，但要自己补置信度校准。

## 七、对 SkillMaster 的位置

| 事实 | 对本产品的含义 |
|---|---|
| 各宿主的每轮路由只读**本机** skill 的 `name + description` | 远程 + 本机零副本，拿不到这条通道；[`ADR 0089`](../decisions/0089-no-local-skills.md) 与 [`ADR 0002`](../decisions/0002-gateway-skill-and-cli.md) 选的是网关模型，代价在此兑现 |
| 只给 L1、藏正文，路由掉 37–44 分 | 本产品当前就是 L1-only；[`docs/research/agent-context-loading.md`](agent-context-loading.md) 已记录「不要把希望寄托在 skill 自动加载上」 |
| 正文在服务端 | **body-aware 路由可以在服务端做**——这是客户端做不到、本产品天生具备的条件；SkillRouter 的 74% Hit@1 说明这条路有效，不是理论 |
| 「必须每次先查库」属于 must hold every time | 按 Anthropic 官方 troubleshooting，它应该进 **hook**，而不是继续加长提示词；[`ADR 0094`](../decisions/0094-load-the-library-at-session-start.md) 当年已写下「这是指令不是保证」，[`ADR 0096`](../decisions/0096-guidance-lives-on-the-platform.md) 保留了这个判断 |
| 库越大、语义越像，路由越差；且是对数衰减 + 相变 | 命名空间 / 集合不是分类学，是**路由准确率的一等变量** |
| 没有宿主的远程发现机制 | 「远程 + 零本地 + 不要求用户感知」目前没有标准答案；最接近的工程样本（skills-broker、skill-router、Solon Remote Skills）都是**自建 router + 遥测** [转引] |

**一句话**：本产品的触发问题不是「提示词还没调好」，而是「选了一个没有宿主原生通道的形态，又指望模型自己补上通道」。要么补机制（hook / 服务端 router），要么承认概率并把它量出来。

## 八、未决问题与候选方向（不决策）

| # | 问题 | 当前已知 | 什么证据能回答 |
|---|---|---|---|
| Q1 | 路由放在哪：客户端 hook / 服务端 router / 本机目录 | 三条路各有先例，没有一条被证明在「远程 + 零本地」下成立 | 一个对照实验：同一批任务，三种机制下量「检索 → 选对 → 使用」三步 |
| Q2 | 拥挤库里「人人 pushy」会不会互相抵消 | 没有实验；只有单 skill、稀疏库的数据 | 20+ 个都写 ALWAYS 的 skill，量触发率与误触发率 |
| Q3 | 「不要求用户感知」与「确定性」是否必须二选一 | 显式调用 100%，但要求感知；Gemini 用确认换确定 | 产品取舍，不是技术问题——需要所有者定 |
| Q4 | 现有基准能否支撑结论 | BFCL 被查出约一半题目有缺陷；社区数字多为单案例 | 自建固定的触发率评测集，作为发版闸 |
| Q5 | 远程 skill 的发现有没有标准路径 | 没有；Agent Skills 规范不规定装在哪，宿主的发现索引也只读本机 | 持续跟踪宿主是否开放远程 skill 注册（目前未核实） |

**候选方向**（只登记，不在本文选）：

- **A · 客户端 hook**：`UserPromptSubmit` 每轮把「可能相关」注入上下文，`PreToolUse` 闸住第一次写操作。确定、零本机副本、只对支持 hook 的宿主有效。
- **B · 服务端 router**：把任务描述发给服务端，由读过正文的 router 返回少量候选；客户端只负责把结果注入。**利用了本产品独有的正文优势**，但多一次网络往返，且需要设计「怎么把结果交给模型」。
- **C · 本机目录/存根**：`skmr sync` 生成存根或紧凑 catalog，交给宿主原生路由。跨宿主、确定，但要重开 [`ADR 0089`](../decisions/0089-no-local-skills.md)，且 token 随库增长。
- **D · 维持指令 + 测量**：先把规则块真正装上，再建触发率评测，接受概率保证。

## 九、来源

**一手（本次直接读过）**

- [Claude Code — Skills](https://code.claude.com/docs/en/skills)
- [Claude Code — Hooks](https://code.claude.com/docs/en/hooks)
- [Anthropic — Improving skill-creator](https://claude.com/blog/improving-skill-creator-test-measure-and-refine-agent-skills)
- [agentskills — Optimizing descriptions（镜像）](https://github.com/shalomb/agent-skills/blob/main/docs/how-to/optimizing-descriptions.md)
- [GitHub issue #30387](https://github.com/anthropics/claude-code/issues/30387)
- [SkillRouter](https://arxiv.org/abs/2603.22455) · [技能库相变](https://arxiv.org/abs/2601.04748) · [Routing law](https://arxiv.org/abs/2605.16508) · [Gavel](https://arxiv.org/abs/2609.15982)
- [REFLEX with Jev](https://arxiv.org/abs/2609.26532)（全文 HTML：成本表与 τ²-bench 对照）

**转引（搜索摘要或第三方整理，只作线索）**

- [Gemini CLI — Managing Agent Skills](https://geminicli.com/docs/cli/using-agent-skills/) · [PR #24946](https://github.com/google-gemini/gemini-cli/pull/24946)
- [Codex skills 指南](https://test.verdent.ai/zh-CN/guides/codex-app-skills-guide)
- [Cursor — Rules](https://cursor.com/docs/rules) · [Why Cursor rules never fire](https://dev.to/rulestack/why-your-cursor-rules-never-fire-globs-alwaysapply-and-description-explained-b33)
- [Show HN — 216 skills lint](https://news.ycombinator.com/item?id=49744398)
- [Scott Spence — Skills activate reliably](https://scottspence.com/posts/how-to-make-claude-code-skills-activate-reliably) · [Follow-up](https://scottspence.com/posts/how-to-make-claude-code-follow-hook-instructions)
- [GitHub issue #100813](https://github.com/anthropics/claude-code/issues/100813) · [#14016](https://github.com/anthropics/claude-code/issues/14016)
- [When2Tool](https://arxiv.org/abs/2605.09252) · [When2Call](https://preview.aclanthology.org/bulk-verify-all/2025.naacl-long.174/) · [Tool-Catalog Scaling](https://ksp.etri.re.kr/ksp/article/read?id=73481) · [Toolshed](https://arxiv.org/abs/2410.14594)
- [BFCL leaderboard](https://gorilla.cs.berkeley.edu/leaderboard.html) · [Epoch AI 复核](https://epoch.ai/benchmarks/berkeley-function-calling-leaderboard/review)
- [skills-broker](https://www.npmjs.com/package/skills-broker) · [skill-router](https://lobehub.com/skills/jacob-balslev-skill-graph-skill-router) · [Solon Remote Skills](https://dev.to/noear/solon-ai-remote-skills-enabling-the-perception-of-distributed-skills-3edo)

**价格与国产替代（§六）**

- [TypeSafe — Jev pricing（指南页）](https://www.typesafeai.org/guides/jev-pricing) · [Jev pricing calculator](https://jev-agent.com/pricing) · [awesome-jev](https://github.com/Promethe-us/awesome-jev)
- [阿里云百炼 — 模型调用价格](https://help.aliyun.com/zh/model-studio/model-pricing)
- [DeepSeek — Models & Pricing](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)
- [火山方舟 — 模型价格](https://docs.volcengine.com/docs/ark/model-pricing?lang=zh)
- 国产决策模型报道：[智东西](https://www.zhidx.com/p/602211.html) · [36氪](https://www.36kr.com/p/4011046457872258) · [量子位](https://www.qbitai.com/2026/10/504879.html) · [腾讯云社区](https://cloud.tencent.com.cn/developer/article/2761732) · [BAAI hub — Intern-Decision](https://hub.baai.ac.cn/view/58343)

## 十、未核实清单

- **When2Tool / When2Call / To Call or Not to Call / Toolshed / Tool-Catalog Scaling**：只读到搜索摘要，未读全文；数字按摘要转述。
- **Codex / Gemini CLI / Copilot / Cursor 的机制细节**：来自搜索摘要与第三方整理，未逐页核对官方文档原文。
- **Show HN 216 skill 的统计**、**Spence 的 200+ 次评测**、**Seleznov 的 650 次试验**：只读到二手转述，未见原始数据与方法细节。
- **BFCL 与 Epoch 复核的具体数字**：只读到搜索摘要。
- **远程 skill 是否有宿主原生注册通道**：未找到官方依据，标未核实。
- **Jev 官方定价**：未读到 TypeSafe 官方 pricing 页原件（访问 404）；$0.042/M 来自多个第三方口径的一致转述，下单前须以官方为准。
- **Jev 限流**：来源不一致（80–100K token/s、80 req/s vs 250K token/s、1,200 req/min）。
- **国产决策模型的数字**：全部是厂商自测，本次未独立复现；Intern-Decision、StartLux、LingDT、Xing4.0 的具体评测集与方法未读原文。
- **国产小模型 API 价格**：来自官方价格页的搜索摘要，未逐页核对；豆包与 DeepSeek 有分档/时段价，表里只取最低档，不代表全量场景。
