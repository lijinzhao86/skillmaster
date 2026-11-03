# 给 agent 读的文档怎么组织：粒度、路由与召回的证据

> **核对日期**：2026-11-03。
> **用途**：决定 SkillMaster 官方指南 skill（`skillmaster/skillmaster-guide`）的目录形状与文档粒度。同样的结论用于优化本仓库自己的 skill，以及以后指导用户写 skill。
> **口径**：分三档逐条标注——**[一手]** 论文、官方仓库或官方文档原文，本次直接读过；**[转引]** 只拿到摘要、镜像或第三方整理，没读到原文；**[未核实]** 找不到可靠出处。数字带样本与条件；单案例与规模实验分开写，不合并成「实测」。
> **本文不下决定**——跨版本的取舍属于 [`docs/decisions/`](../decisions/)；某一版做到哪属于 `docs/versions/`。
> **结论先行**：**每一篇自足**是证据最硬的一条；**按步骤把文档切成一串引用链**有直接反例（内联 3/3，抽到引用文件 0/5）；决定成败的第一阶变量是**步数与每步的约束数**，文档粒度是第二阶。

## 三句话结论

1. **「拆开」几乎不花钱，「跨轮」才贵。** 同一份指令一次给全、拆开但同一轮给、拆成多轮逐步给，完成度是 100% / 95.1% / 61%——损失来自多轮格式，不是来自拆分本身。
2. **步数是最强的衰减项。** GAIA 按步数分层：≤5 步 30.3%，5–10 步 9.7%，长序列 0%（人类 93.9 / 91.8 / 87.3）；成功率近似按单步可靠性 `p^N` 衰减。文档切几篇救不了这个指数结构。
3. **导航要一张完整的地图，不要一串链。** 有损索引做唯一入口时命中 52.0%，而同题库用 grep 是 80.4%；一旦找对文件，准确率 86.8%——**坏在到达叶子那一步，不在叶子本身**。

## 一、粒度：一篇长文档 vs N 篇一步

### 1.1 最贴近的对照实验

**《LLMs Get Lost In Multi-Turn Conversation》（arXiv:2505.06120，ICLR 2026 oral）【一手】**

6 类生成任务、600 条分片指令、15 个模型、20 万+ 对话，约 5000 美元成本。三种给法：

| 给法 | 等价于 | 相对 Full 的完成度 |
|---|---|---|
| Full：一次性给完整指令 | 一篇长文档 | 100% |
| Concat：分片全部放在**同一轮** | 一次上下文里读多篇 | **95.1%** |
| Sharded：每轮只揭示一片 | 跨轮、跨次逐步加载 | **约 61%** |

各模型 Sharded/Full 落在 50.5%–66.1%。GPT-4o 分任务（Full / Concat / Sharded）：Code 88.4 / 82.9 / 61.3；Database 93.6 / 91.7 / 42.3；Actions 96.1 / 97.1 / 65.0；Math 93.8 / 91.9 / 67.9；Summary 23.9 / 23.9 / 10.6。

机制：模型**过早做假设、过早给最终答案**，然后过度依赖早期假设；作者原话是「一旦走错一个弯，就迷路且不恢复」。损失主要体现为**可靠性变差**（分位数展宽），不是能力上限下降。

**关键控制**：Concat 证明「信息被拆开、被改写」本身不是主因，**多轮格式**才是。

### 1.2 长文档自身的代价

**Lost in the Middle（arXiv:2307.03172，TACL 2024）【一手】**

20 篇文档，答案放在开头/中间/结尾的准确率：GPT-3.5-Turbo **75.8% / 53.8% / 63.2%**；Claude-1.3 59.9 / 56.8 / 60.1。两点值得记：

- 中间位置的 53.8%，**低于完全不提供文档的 closed-book 56.1%**——答案在上下文里反而更差。
- 文档数 10 → 30 时，中间位置从 61.0% → 54.1% → 50.5%；开头与结尾基本稳定。

**RULER（arXiv:2404.06654）【一手】**：17 个模型、13 类任务，全部宣称 ≥32K；只有约一半在 32K 仍维持满意表现。可对齐的 10 个模型平均分从 4K 的 **87.6 掉到 128K 的 40.0**。

**NoLiMa（arXiv:2502.05167，ICML 2025）【一手】**：去掉问题与目标之间的字面重合、强制潜在关联后，**13 个模型里 11 个在 32K 时跌到自身短上下文基线的 50% 以下**；GPT-4o 从 99.3% 掉到 69.7%。

**Chroma《Context Rot》【转引／厂商报告，未同行评审】**：18 个模型、194,480 次调用，结论是性能**随输入长度每一档都在下降**，不是只在接近窗口上限时下降。

### 1.3 能装下时长上下文通常优于 RAG

**arXiv:2407.16833（EMNLP 2024 Industry）【一手】**：9 个数据集、3 个模型；资源足够时 Long-Context 平均稳定优于 RAG，RAG 的优势主要在成本。RAG 的典型失败场景正是「后一步的检索依赖前一步的答案」。

### 1.4 小结

- 文档**内容**拆开没关系（95.1%），**到达它的次数**才是成本（61%）。
- 单篇过长有位置风险（中间内容被漏），但真正的上界由**有效上下文**决定，不是由「篇数」决定。

## 二、第一阶变量：步数与约束数

这一节的结论与「文档怎么切」无关——**无论切几篇，这些衰减都在**。

### 2.1 步数

| 来源 | 现象 |
|---|---|
| GAIA（arXiv:2311.12983，ICLR 2024）【一手】 | GPT-4+插件：Level 1（≤5 步）**30.3%** → Level 2（5–10 步）**9.7%** → Level 3（任意长序列）**0%**；人类 93.9 / 91.8 / 87.3 |
| AppWorld（arXiv:2407.18901，ACL 2024）【一手】 | GPT-4o ReAct：难度 level 1 **58.3%** → level 3 **21.0%**；需要 60 行以上代码的任务 TGC **低于 20%** |
| The Agent Company（arXiv:2412.14161）【一手】 | Claude 3.5 Sonnet 完整完成 **24%**、部分 34.4%，平均约 **30 步**、每任务成本超 6 美元 |
| τ-bench（arXiv:2406.12045）【一手】 | GPT-4o 在 retail 域 **pass^8 < 25%**——同一任务重复 8 次全部成功的概率不到四分之一 |
| PlanBench（arXiv:2206.10498，NeurIPS 2023）【一手】 | GPT-4 在 Blocksworld 600 个实例上只生成 **206 个正确计划（34.3%）**，并随最优计划长度增加而下降 |

**《The Illusion of Diminishing Returns》（arXiv:2509.09677，ICLR 2026）【一手】**：把知识与计划都先给模型、只测「按步骤执行」，发现**单步准确率随步数增加本身就在下降**（self-conditioning：看到自己前面的错误后更容易继续错）。

**《How Fast Do Agents Rot?》（arXiv:2609.01660，预印本，未评审）【转引】**：9 个模型、10,664 条轨迹，成功率服从单一每步可靠性的几何衰减；agentic tool-use 上所有模型在**约 16 步内**从接近满分掉到接近零；**限制上下文窗口反而让衰减更陡**。这一条样本大但未评审，只作方向参考。

### 2.2 约束数

**ManyIFEval（arXiv:2509.21051）【一手】**：同一提示里所有指令都满足才算对，n=1 / 5 / 10：

| 模型 | n=1 | n=5 | n=10 |
|---|---:|---:|---:|
| GPT-4o | 94% | 57% | **21%** |
| Claude 3.5 Sonnet | 95% | 72% | 48% |
| Gemini 1.5 Pro | 96% | 71% | 39% |

指令数与准确率的关系高度规律（r=0.988）。**FollowBench（arXiv:2310.20410，ACL 2024）【一手】**：GPT-4 从 L1 的 84.7% 掉到 L5 的 61.9%，作者结论是「即使最强模型也只能稳定跟随约 3 条约束」。

### 2.3 任务分解有帮助，但机制不是「文件拆分」

**ADaPT（arXiv:2311.05772，NAACL 2024 Findings）【一手】**：只在执行器失败时递归分解子任务，ALFWorld **+28.3 点**、WebShop **+27 点**、TextCraft **+33 点**。收益来自**失败时重试与递归**，不是来自把文档分文件——把 N 步写成 N 篇，若没有额外的规划与验证，拿不到这份收益。

**反例**：分解在判断型、整合型任务上收益消失甚至反转，协调开销可能让表现差 3.4 倍【转引，较新的预印本，未评审】。

## 三、导航：地图 vs 链

### 3.1 有损的地图比没有地图更糟

**《Reading More, Finding Less》（Zenodo DOI 10.5281/zenodo.21960138，预注册预印本）【一手，单作者未评审】**

102 道冻结问题，把「有损的一行摘要索引」作为唯一定位器，与 grep 搜索对照：

- 命中金标文档：索引 **52.0%** vs grep **80.4%**。
- 索引臂 47 次错误停止里，**43 次是「读了错文件」，只有 4 次是「什么都没读」**。
- **一旦找对文档，准确率 86.8%。**
- 作者结论：这是 **localization deficit，不是 comprehension deficit**。

复现（141 文档 / 120 题）：搜索比索引 **+12.5 个百分点**（63.3% vs 50.8%）；44% 的题与自己的标准摘要**没有任何共同实词**——这就是摘要式索引失效的原因。

### 3.2 地图的价值取决于它是否完整、是否按 agent 的需要排序

| 来源 | 事实 |
|---|---|
| Aider repo map（官方文档与博客）【一手】 | 每次请求随附一张「文件 + 关键符号」地图，默认预算 **1k token**；SWE-bench Lite 上**正确找到要改的文件 70.3%**。没有公开 on/off 消融 |
| Cursor 语义索引（官方博客）【一手】 | 用 agent 会话轨迹训练排序目标；问答准确率平均 **+12.5%**（6.5–23.5%），千文件以上的代码库上 code retention **+2.6%**；没有语义搜索时，用户不满意的追问 **+2.2%** |
| CorpusMap（arXiv:2609.37226，预印本）【转引】 | 给语料建实体导航层，四个模型质量 **+6.4–11.7 分**，输入 token 降到 **0.43–0.66×** |
| ripwire 四方对照（n=60）【转引，工程基准】 | 同一批题上 aider repo-map 的 file@10 只有 **13.3%**——**有地图不等于地图好用** |

### 3.3 链接本身：有用，但它省的是成本

**跨引用实验（Zenodo record 21881798）【转引，未能读到正文】**：709/5000 页 wiki 语料，保留作者写的交叉引用 vs 去掉链接。有搜索时，有链接臂每个事实的搜索次数稳定在 0.59–0.67，去链接臂升到 0.73–1.44；**把搜索完全拿掉时，有链接臂 1.00 grounded、零搜索，去链接臂 0.00**。

结论：链接是**在没有检索能力时的唯一通路**，有检索时它省成本、增鲁棒——但它**不提升覆盖率**。

### 3.4 多跳导航没有干净的对照实验

现有实验比较的是「有损索引 vs grep」「有链接 vs 无链接」「导航层 vs 原始语料」，**没有一篇是「一次给全量目录」与「只给入口、逐步跟链接」在同一任务上的头对头**。有一个预注册实验（OSF）把「全量索引基线」列为一个臂，但目前**数据尚不存在**；它的 pilot 里有一个值得记住的观察：**agent 可能根本不加载索引，而是按问题猜路径直接读**——「有地图」和「agent 用了地图」是两件事。

### 3.5 agent 真实的导航失败

**SWE-agent 的失败分类（arXiv:2405.15793，Appendix Table 9）【一手】** 单列了两条：**Failed to Find Relevant File**（「从未打开或看到正确的文件」）与 **Failed to Find Edit Location**（「打开了正确文件但没找到要改的地方」），另有 **Ran Out of Budget** 与 **Failed Edit Recovery**。

**OpenHands 轨迹研究（arXiv:2511.00197）【一手】**：失败轨迹里「从没碰到金标文件」的比例，SWE-bench Lite 上是 20.2%（OpenHands）/ 18.6%（SWE-agent）/ 27.5%（Prometheus），Verified 上分别升到 37.3% / 40.7% / 32.9%；原话是「大多数失败轨迹其实找到了问题文件」「文件级定位是必要但非充分条件」。**仓库越大、任务越难，路由失败越显著。**

### 3.6 超链接的认知代价：只有隐喻，没有 agent 实验

**Conklin 1987 的 hypertext 综述【一手】** 把 **disorientation（在空间里迷路，需要知道自己在哪、从哪来、怎么去别处）** 与 **cognitive overhead（判断跟哪条链接的决策成本）** 列为超文本最棘手的问题，并提出用**总览图**缓解；Nielsen 1990 的反面 caveat 是**大系统的总览图自身也会变复杂，引入新的导航问题**。

**这套研究没有被直接搬到 agent 上做实验。** 目前只有 Anthropic 用「轻量标识符 + 按需探索」的框架描述 agent 导航，属于类比而非实证。

## 四、官方规范怎么说

### 4.1 Agent Skills 规范

【一手】`SKILL.md` 建议 `<500 lines`；**"Keep file references one level deep from `SKILL.md`. Avoid deeply nested reference chains."**；渐进披露三级：metadata ≈100 token、正文 <5,000 token、资源按需。

### 4.2 OpenAI `skill-creator`

【一手】原文要点：

- "Keep SKILL.md body to the essentials and under 500 lines."
- "When splitting out content into other files, it is very important to **reference them from SKILL.md and describe clearly when to read them**."
- "**Avoid deeply nested references - Keep references one level deep from SKILL.md. All reference files should link directly from SKILL.md.**"
- "For files longer than **100 lines**, include a table of contents at the top."
- "If files are large (>10k words), include grep search patterns in SKILL.md."
- "Information should live in either SKILL.md or references files, not both."
- "Do NOT create extraneous documentation … README.md / QUICK_REFERENCE.md"

### 4.3 Codex 的运行时要求

【一手】commit `5655490`（PR #27044）：主 agent 必须把 `SKILL.md` **完整读完**才能动手；它指向的每个参考文件也必须由主 agent **自己整篇读完**，不许转给子 agent 摘要。commit message 的理由：「部分读取会跳过后面的路由与验证要求；委派的摘要也会漏掉约束」。

### 4.4 Anthropic `skill-creator`

【一手】`<500 lines`；接近上限时「add an additional layer of hierarchy **along with clear pointers** about where the model should go next」；大文件（**>300 行**）给 TOC；**「按变体分文件，Claude 只读相关的那一份」**。

**注意**：`one level deep` 是 **OpenAI 明文写的**；Anthropic 的对应说法只在被引用的 best-practices 页，本次因地区重定向**未能读到原文**（标「未核实」）。TOC 阈值两家不同：Anthropic 300 行、OpenAI 100 行，不要混成一条。

### 4.5 Anthropic 的团队复盘

【一手】[Lessons from building Claude Code: How we use skills](https://claude.com/blog/lessons-from-building-claude-code-how-we-use-skills)：

- 「skill 里**信号最强的是 Gotchas 部分**」，应当从 Claude 实际踩过的坑积累。
- 配图给的是 **hub-and-spokes**：`SKILL.md` 是 hub，`stuck-jobs.md` / `dead-letters.md` / `retry-storms.md` 是 spokes，**`SKILL.md` 里放一张「症状 → 文件」表**。
- 反例：「做得太多的 skill 横跨好几件事，反而让 agent 困惑。」

### 4.6 清单层的容量

【转引】Codex 文档镜像：技能清单占用上限为**上下文的 2%，或 8,000 字符**；超了先截 description，再**整个把 skill 从初始清单里丢掉**。这决定了 `description` 是唯一的触发器，必须写「什么时候用」。

## 五、真实故障样本

| 样本 | 事实 |
|---|---|
| EveryInc 的路由内联复盘【一手，作者自测】 | 把「菜单每项点下去之后做什么」从 SKILL.md 抽到 `references/plan-handoff.md`：内联 **3/3**，抽出 **0/5**，重新内联 **5/5**。失败形态是「渲染了菜单、拿到了用户的选择，**然后就停了**」，两条轨迹只输出了一句 `READ: references/post-fix-handoff.md`。后续测量：required-read 48/48、inline 48/48，但 reference 的**实际打开率只有 11/48**。原话：**"Inline the trigger; keep the substance in the one reference that owns it."** 另一个教训是——**摘要写得完整到能照做，反而抑制 agent 去读原文** |
| openai/skills#109【一手】 | `skill-creator/SKILL.md` 引用了两个不存在的文件，报告者原话是「This confused Codex as it couldn't find the files」——死链直接让 agent 困惑 |
| anthropics/claude-code#56325【一手】 | SKILL.md 里写相对路径 `references/style-guide.md`，模型**无法解析**（它不知道 skill 的安装目录），于是改用 Search/Glob，结果找不到文件 |
| anthropics/claude-code#15757 / #28047【一手】 | 读自己打包的文件还要用户授权；斜杠命令没有把 SKILL.md 展开进上下文，模型退回默认行为 |
| flux D-190【一手】 | 另一个 harness 的记录：「多文件 skill 目前退化成只有 `SKILL.md` 正文——同目录文件既没被加载也没被提及，模型没有任何锚点去读它们」 |
| 17,244 个真实会话的第三方分析【转引，作者自称「careful look, not a study」】 | 首次打开时**完整读完 85%**；**14%** 的会话从头到尾没读完过；部分读通常只读 **10–100 行**；读不完的比例随长度上升：≤100 行 **9%**、101–200 行 **13.5%**、更长约 **18%** |
| Cloudflare 安全审计复盘【一手】 | 最初是一个约 **450 行**的 skill，7 个阶段塞进一次会话；结果「单次只找到大约**一半**的 bug」「一小时后**上下文窗口填满**，模型开始啃自己的记忆」。解法不是再拆 reference 文件，而是**把每个阶段提升成独立 agent + 数据库 + 编排器**，并要求每个 agent 的上下文占用**低于窗口的 25%** |

## 六、反例与证据缺口

### 6.1 反向证据（别只挑支持自己的）

- **切得更碎不一定更好**：Chroma 的评测里，MiniLM 上 200 token 的 recall（75.7）低于 250 token（82.4）【一手】。
- **语义切分不值得额外算力**：NAACL 2025 Findings 在 HotpotQA（固定 90.59 vs 语义 87.37 / 84.79）、MSMARCO（93.58 vs 92.23 / 93.18）上固定大小反而更好【一手】。
- **标题感知切分结果混杂**：有实验在 Recall@5 上 +3–22%，也有实验 R@5 / R@10 完全持平、MRR 反而下降【转引，样本小】。
- **元数据硬过滤会删掉答案**：硬过滤把某类问题直接清零，软加权才能保住收益【转引】。
- **上下文化会伤「文档内检索」**：chunk 之间太像时，定位反而变难【转引】。

### 6.2 证据缺口（要回答我们的问题还缺什么）

1. **没有头对头**：固定总 token、总步数、总约束，只变「1 篇 N 步 vs N 篇 1 步 + 链接」，测端到端完成率。目前没人做。
2. **没有区分「同一轮里连续读多篇」与「跨轮加载」**：前者接近 Concat（95.1%），后者接近 Sharded（61%），现有实验只覆盖后者。
3. **没有「重复加载同一文档」的准确率实验**，只有成本与延迟数据。
4. **没有程序性文档（SOP）的长上下文评测**：现有长上下文评测几乎都是 QA / 检索 / 聚合。
5. **没有「每多一跳，成功率下降多少」**：只有延迟（单次模型回合约 3–4 秒的实测）与「预算耗尽」的间接证据。

## 七、本平台自己的事实

这些不是外部研究，是我们自己代码与实测的约束（2026-11-03 核对）：

- **`invoke` 只加载正文，不接文件路径。** 源码原话：「which of these directories is a skill and which is an asset folder is **the author's to say, in the body**, which is the index a caller already has in hand.」——**正文就是索引**，子目录不会自动被发现。
- **`skmr skill check <地址>@<版本码>` 校验三类引用**：markdown 链接、反引号里的路径、`skill read/cat` 的路径；有断链退出 1。链接漂移可以被机检拦住。
- **上限**：文件数、单文件大小与文本上限见 [`limits.md`](../current/limits.md)——那里是权威，本文不复述数字（复述就会漂）。
- **嵌套目录可用**：本机实测写入 `a/b/c/deep.md` 三层成功，空目录删除后自动消失。

## 八、可执行规则

**规则正文在 [`../../.skill-authoring/rules.md`](../../.skill-authoring/rules.md)**——那里是权威，本文不复述（同一份事实写两处必漂）。这一节只留一张「规则编号 → 依据在哪」的对照，给将来要回溯的人。

| 规则 | 依据 |
|---|---|
| A1 一篇一件事 | §3.1（找对文件后准确率 86.8%，坏在路由） |
| A2 线性流程不拆 | §1.1（Concat 95.1% vs Sharded 61%） |
| A3 互斥分支才拆 | §4.4（Anthropic：mutually exclusive or rarely used together） |
| A4 短分支同篇分节 | §1.4 |
| A5 每篇 ≤100 行 | §5（17,244 个会话的读完整率） |
| B1 从入口一层可达 | §4.1 / §4.2 |
| B2 路由写到文件 + 何时读 | §3.1（52.0% vs 80.4%） |
| B3 叶子不是中转 | §5（EveryInc 的 0/5） |
| B4 入口 100–150 行 | §4.3（Codex 要求整篇读完） |
| C1 开头两句给答案 | §1.1（模型过早做假设） |
| C2 不用「如上所述」「它」 | §一 1.2 与 [Contextual Retrieval](https://www.anthropic.com/news/contextual-retrieval) |
| C3 一篇一个说法 | §6.1（BM25 对同义词不友好） |
| D1 常见坑放进入口 | §4.5（Gotchas 是最高信号） |
| D2 同一事实只写一处 | §4.2（OpenAI：not both） |
| D3 每步给完成信号 | §2.3（ADaPT 的收益来自验证与重试） |
| D4 网页与命令行同篇 | **判断**，无直接实验 |
| D5 不写「如果用户是……」 | **判断**，与三层分层的设计有关 |
| E1 description 最后写 | §4.6（清单层会截断、会整个丢弃） |
| E2 验收要测打开率 | §5（reference 实际打开率 11/48） |
| E3 发布前跑 check | §七（`skmr skill check` 校验三类引用） |

## 九、未核实清单

- Anthropic 官方 best-practices 页关于 `one level deep` 的原文：本次因地区重定向未读到，规范页的一层深要求来自 OpenAI 一手原文。
- OpenAI `developers.openai.com/codex/skills` 与 `learn.chatgpt.com/docs/build-skills`：返回 403 / 连接失败，相关结论来自镜像与仓库。
- Cloudflare 是否公开解释过 `cloudflare-deploy` 的目录设计：**未查到**。该 skill 出自 OpenAI 仓库的一次伙伴合并（commit `add-partner deploy skills (#68)`），没有 Cloudflare 邮件的作者信息；「按领域五个文件」是结构观察，不是作者意图。
- WebWalkerQA 的「agent 约 33.5 EM vs 人类 72.8 EM」：二手摘要，未读到正文表格。
- 《How Fast Do Agents Rot?》的具体衰减参数：预印本，未同行评审。
- 跨引用实验（Zenodo 21881798）：只读到摘要数字，正文未能打开。

## 十、来源

**论文与预印本**
- LLMs Get Lost In Multi-Turn Conversation：https://arxiv.org/abs/2505.06120
- Lost in the Middle：https://arxiv.org/abs/2307.03172
- RULER：https://arxiv.org/abs/2404.06654
- NoLiMa：https://arxiv.org/abs/2502.05167
- Long-Context vs RAG：https://arxiv.org/abs/2407.16833
- GAIA：https://arxiv.org/abs/2311.12983
- AppWorld：https://arxiv.org/abs/2407.18901
- The Agent Company：https://arxiv.org/abs/2412.14161
- τ-bench：https://arxiv.org/abs/2406.12045
- PlanBench：https://arxiv.org/abs/2206.10498
- The Illusion of Diminishing Returns：https://arxiv.org/abs/2509.09677
- How Fast Do Agents Rot?：https://arxiv.org/abs/2609.01660
- ManyIFEval：https://arxiv.org/abs/2509.21051
- FollowBench：https://arxiv.org/abs/2310.20410
- ADaPT：https://arxiv.org/abs/2311.05772
- Reading More, Finding Less：https://github.com/mentu-ai/agent-memory-allocation
- CorpusMap：https://arxiv.org/html/2609.37226v1
- SWE-agent（失败分类）：https://arxiv.org/html/2405.15793
- OpenHands 轨迹研究：https://ar5iv.labs.arxiv.org/html/2511.00197
- Semantic chunking 的代价（NAACL 2025 Findings）：https://aclanthology.org/2025.findings-naacl.114/
- Chroma 切块评测：https://www.trychroma.com/research/evaluating-chunking
- Contextual Retrieval：https://www.anthropic.com/news/contextual-retrieval

**官方规范与工程实践**
- Agent Skills 规范：https://agentskills.io/specification
- Anthropic 工程博客：https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills
- Anthropic skill-creator：https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md
- OpenAI skill-creator：https://github.com/openai/skills/blob/main/skills/.system/skill-creator/SKILL.md
- Codex 运行时要求：https://github.com/openai/codex/commit/56554904babcaacf4444a2cc90716880837dff7c
- Claude Code 团队复盘：https://claude.com/blog/lessons-from-building-claude-code-how-we-use-skills
- Aider repo map：https://aider.chat/docs/repomap.html
- Cursor 语义索引：https://cursor.com/blog/semsearch
- EveryInc 路由内联复盘：https://github.com/EveryInc/compound-engineering-plugin/blob/86e26c89ee1d5f13ee9dc6d9fdc1993966ae8075/docs/solutions/skill-design/post-menu-routing-belongs-inline.md
- Cloudflare 安全审计复盘：https://blog.cloudflare.com/build-your-own-vulnerability-harness/
- Redocly 对 llms.txt 的复盘：https://redocly.com/blog/llms-txt-still-overhyped

**故障样本**
- openai/skills#109：https://github.com/openai/skills/issues/109
- anthropics/claude-code#56325：https://github.com/anthropics/claude-code/issues/56325
- anthropics/claude-code#15757：https://github.com/anthropics/claude-code/issues/15757
- anthropics/claude-code#28047：https://github.com/anthropics/claude-code/issues/28047
- flux D-190：https://github.com/codewandler/flux/blob/main/docs/stories/D-190-skill-supporting-file-disclosure.md
- 17,244 个会话的第三方分析：https://app.stationx.net/articles/claude-code-skills
