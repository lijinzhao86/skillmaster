# 入门清单与 CLI 交互：外部做法

> **核对日期**：2026-10-27。**用途**：给 v26 的 startup 清单、请示与进度定交互口径。
> **口径**：以公开的 UX 指南与 CLI 指南为准（NN/g、GOV.UK、clig.dev、UI Patterns、MetLife）；只读到二手转述的，文中标明；数字都带出处。

## 四句话

1. **清单不是文档目录，是「下一步」。** 3–7 项、按用户得到什么说、自动勾、把下一项推到最前、完成后收起。
2. **确认只给严重后果，且说清会发生什么、怎么退。** 不给泛泛的「确定吗」，不默认同意，能撤就撤。
3. **等待要可见、可控、可停。** 超过 1 秒要有反馈；2–10 秒给忙碌指示；10 秒以上给步数/百分比与「3/50」这样的文字；不造假进度。
4. **读者不懂技术时，先把话说明白。** 短句、口语、一次一个问题；技术动作由 agent 代做；路径与命令不进入对话（用户必须自己操作时给一行可复制的内容）。

## 一、清单：先给下一步，不是一长串并列

UI Patterns 的 Onboarding Checklist 条目写得很具体：

- **三到七项，按结果说**（"phrase each task as something the user wants"），不要按功能说（"Visit Automations" 是反例）。
- **在用户真的做了之后自动勾**（"Mark it complete automatically when the user does it anywhere in the product"），"Never mark a task complete that the user has not done"。
- **一开始就有一项是勾上的**（"Start with something ticked"），让人看到已经走了多远。
- **可见的进度、紧凑、可收起**（"Keep the checklist visible but small"），**全部完成后收起**（"retire it once it is complete"）。
- **可选项可以跳，但要告诉人以后在哪做**（"Skip must be available for optional work and state where it can be completed later"——来自 kin-design-system 的同一模式说明，**二手摘要，未逐字核对原文**）。

NN/g 的渐进披露与「识别优于回忆」补两条：

- **先给最重要的少量选项**（"Initially, show users only a few of the most important options"），并且"it must be obvious how users progress from the primary to the secondary disclosure levels"。
- **把状态摆出来，别让人记**（"Recognition is easier than recall because it involves more cues"；命令行界面本质上是靠回忆的，所以更需要把状态写出来）。

**对 v26 的含义**：清单开头一句话说整件事的目的；**只列还没做的，第一件就是下一步**；已完成收成一行；可选项写「以后也能做」；全部完成后不再列清单。

## 二、确认：只在后果严重时问，问得具体

NN/g 的 Confirmation Dialogs 指南：

- **只给严重后果用**（"actions with serious consequences"），**日常动作不要弹**——弹多了人就会条件反射地点过去（"if you warn people too much, they stop paying attention"）。
- **说清后果**（"Be specific and inform users about the consequence of their action"），不要问「你确定吗？」。
- **按钮/选项写会发生什么**（"response options that summarize what will happen"），不要 Yes/No。
- **不要默认 Yes**（"Avoid giving confirmation dialogs a default Yes answer"）。
- **尽量给 undo**（"do go to great lengths to provide undo"）；确认减少错误，但不能消灭错误。

**对 v26 的含义**：五句话请示里必须写清「会发生什么、能不能退」；最后一句用具体动作问（「要我现在给 Codex 加上这段说明吗？」），不是「确定吗？」；不默认同意；`prompt uninstall` 与删掉电脑上原来的那份之前的备份是退路。

## 三、进度：等待要可见、可控

NN/g 的三个时间阈值与进度指示：

- **0.1 秒**：感觉是瞬时；**1 秒**：思路不断；超过 1 秒就该有「系统在工作」的信号。
- **2–10 秒**：真正的百分比往往过头；用不显眼的忙碌指示 + 一个会变的数字。
- **10 秒以上**：给百分比或步数；文字说明在处理什么（"Updating address 3 of 50"），并给**中断**的办法。
- **不造假**：假的百分比、卡在 99%、重置的进度条，比没有更伤信任（UX Tigers 的 "progress theater"，**二手摘要，未逐字核对原文**）。

**对 v26 的含义**：`--plan` 开始先打一行「正在检查你的电脑和 SkillMaster…」；传技能 / 电脑上原来的那份这种会碰到很多个东西的步骤报「第 3 个，共 33 个」，不报假百分比；长步骤可以停。

## 四、CLI：人类优先、不要等输入、给出下一条命令

clig.dev（Command Line Interface Guidelines）里与本版直接相关的几条：

- **人类优先**（"Human-readable output is paramount"）；stdout 放结果，stderr 放消息/进度/错误。
- **只在 TTY 上才交互**（"Only use prompts or interactive elements if stdin is an interactive terminal"）；非交互要有 `--no-input` 一类的开关，且**每种输入都要有 flag/参数对应**（"Never require a prompt"）。对 agent 更严格：**一句都不问**。
- **给下一条命令**（"Suggest commands the user should run"），尤其是在多步操作之后。
- **改了状态要告诉人**（"If you change state, tell the user"），并**让当前状态一眼可见**（"Make it easy to see the current state of the system"）。
- **错误要改写成人类能读的**：原因 + 可能的修法；退出码映射到主要失败类型。
- **危险动作先确认**；中等风险可以给 dry-run；严重动作要有意难确认（`--force` / `--confirm=...`），非交互的靠 flag 而不是等输入。

**对 v26 的含义**：`--plan` 不读 stdin、不开浏览器；stdout 是给人的清单，stderr 最后一行给 agent 指下一步；每条错误说清原因与下一步；退出码沿用 0/1/2/4。

## 五、落到 v26 的清单上

> **2026-10-28 补记**：下表是 v26 当时的落点。`startup --plan` 已在 v27 删除（[ADR 0111](../decisions/0111-startup-takes-no-flags.md)），清单与「下一步」现在由旅程正文给；`prompt install` / `status` / `update` / `uninstall` 同批退役（[ADR 0110](../decisions/0110-the-block-is-a-paragraph-the-cli-stops-managing.md)）。表里提到 `--plan` 或 `prompt uninstall` 的行（七项清单、可选项那句、stdout/stderr 分工、确认与退路）描述的是当时，不是现在——清单现在只有两项（v26 迭代 0004）。

| 外部做法 | v26 的落点 |
|---|---|
| 3–7 项、按结果说 | 七项（CLI / 登录 / 传技能 / 正式上线 / 让 Agent 先来 SkillMaster 找技能 / 让 Agent 用 SkillMaster 自己的文件夹 / 电脑上原来的那份），每项一句「用户得到什么」 |
| 下一项最前、已完成收起 | 只列还没做的，第一件就是下一步；已完成收成一行 |
| 自动勾、不谎报 | 状态从本机与平台实际算：文件、记录、草稿、登录；未知写「登录后检查 / 暂时读不到」 |
| 可选项告诉人去哪做 | 电脑上原来的那份标「可选，以后跑 `startup --plan` 还能看到」 |
| 完成后收起 | 没有待做项时只说「完成了」和最后一件真实的事（以后开新任务时，问一个需要用到技能的问题） |
| 确认说后果、给退路 | 五句话（我要做什么 / 只会动到什么 / 做完会怎样 / 不想做了怎么办 / 不做会怎样）+ 具体动作的问法；`prompt uninstall` / 备份 |
| 等待可见、可停 | 开始一行「正在检查…」；多对象步骤「第 3 个，共 33 个」；不报假百分比 |
| 人类优先、给下一条命令 | stdout 清单 / stderr 下一步；错误带原因与修法；退出码 |

## 六、边界（本版不做的）

- **不做 `--json`**：这一版的消费者是 agent 与人，用户看到的是文本；以后脚本要消费再加。
- **不做常驻的清单 UI / 仪表盘**：清单是一条命令的输出，不是网页；不存进度（见 v26 PRD 不做 #11）。
- **不做教程式 tour**：用户要的是「我现在该做什么」，不是一节产品课。
- **不报假百分比**：清单说「还差 2 件」，长步骤说「第 3 个，共 33 个」，没有可靠的剩余时间就不给时间。

## 七、面向非技术用户：先把话说明白（2026-10-27 补）

v26 的第一版措辞范本按 `voice-spec` 的书面陈述写，用户看完的反馈是「还是过于生硬且技术」。这一节补的是「读者不是开发者」时的口径。

**GOV.UK 的 clear language**（英国政府数字服务手册，面向公众的强制口径）：

- 用短词、短句：一句话不超过 25 个词；一段不超过 5 句；能用主动语态就用主动语态。
- 不用正式的官样词；必须用行话时，第一次出现就解释。
- 平实语言不是「降级」：约 80% 的读者更喜欢清楚的写法；识字水平很低的人约占 1/6；即使专业读者也读得快、扫得多。

**NN/g 的平实语言与错误**：

- 用平实语言；用户只读页面上约 20–28% 的文字；「即使专家也忙、会分心、在扫读」。NN/g 给英文的阅读年级目标是 6–8 年级；中文没有直接对应物，落实为短句、常用词、少术语。
- 行话只在读者预期并使用它时用；混合读者先给平实说法，再把行话放在括号里；缩写不要只在第一次定义（读者会跳读），能写全就写全。
- 错误消息：说清发生了什么、为什么（必要时）、怎么修；具体、可读、不责备、不开玩笑；保留用户已经做的；给出低成本的下一步。

**对话式引导**（UI Patterns / MetLife）：

- 一次只问一个问题；一段对话短；一个问题要有理由，也要有退路；允许回看、修改、撤销。
- MetLife 的对话式 UI：不超过 5 步，一次一个问题，用自然语言，友好，复用已知信息，避免长段落，图和文字一起用。

**对 v26 的含义**：

- 安装对话不是「产品说明书」，是「一个人在旁边帮你装」：先看状态、说还差几件、一次做一件、每件前说清我要做什么 / 只会动到什么 / 做完会怎样 / 不想做了怎么办 / 不做会怎样。
- 技术动作由 agent 代做；用户只回答做不做。路径、命令、文件名不进入对话；用户必须自己操作时，给一行可复制的内容 + 粘贴到哪里 + 做完说什么。
- 一个东西只用一个名字；第一次出现的词先解释；不写「简单 / 只需要 / 小白」。
- 错误说「发生了什么 / 什么没变 / 你可以怎么办」；不责备、不开玩笑、不道歉。
- 进度说「还差 2 件」「这是第 1 件」；大步骤说「第 3 个，共 12 个」；不报 6/7、不报假百分比。

## 八、第一次接触：先说什么、先查什么（2026-10-28 补）

**核对日期**：2026-10-28。**用途**：给安装说明页的开场句、「先说再查」和失败话术定依据。规则本身写在 [`design/voice-spec.md`](../../design/voice-spec.md) §八，这里只放证据。

**开场结构有强共识，但没有对照实验。** 身份 → 能做什么 → 接下来几步 → 可随时退出 → 大概多久。Oracle 的对话设计文档要求欢迎语说清「能做什么、接下来发生什么」并允许退出；clig.dev 要求无参数时直接给简短帮助、提示下一条命令；微软 HAX 的 G1/G2 要求说清能力与边界（**规范级**，无随机对照实验）。

**说短、说在前。** NN/g 的眼动研究：用户平均只读网页 20–28% 的文字；NN/g 的渐进披露要求先给最重要的少量信息。Apple HIG 的原话：*"If you can use fewer words, do so."*、*"put the most important information first"*。

**入门流程越短越好。** Apple HIG Onboarding：*"design a flow that's fast, fun, and optional"*、*"Teach through interactivity"*、不要让人先背一堆信息。NN/g 更激进——能不用入门就不用。

**先查什么：分两层，不是「一律先查」。** ① 装不了的事实先查——Homebrew 的安装脚本先查 bash、uname 平台/架构、prefix、root、sudo，再宣布「将安装什么、会动到哪些目录」；uv 用 uname、glibc/musl、Rosetta 判目标平台，装完还查 PATH 冲突。② 网络可达性**普遍不预检**——两家都是下载到一半才失败，失败时说清原因与下一步。

**非技术读者。** GOV.UK 的 clear language：句子 ≤25 词、一段 ≤5 句、术语第一次出现就用平实说法解释；1/6 的英格兰成年人读写能力很低。**Apple 简体中文用「你」不用「您」**——这是观察到的做法，Apple 没有公开的中文规范，社区里对这个范围有争议（见来源 25）。NN/g 还提醒：用户只扫读约 20–28% 的文字。

**失败时。** NN/g 的错误消息研究是最扎实的一类：贴近出错处、用人话、说清问题、给可执行的下一步、不指责用户；clig.dev 要求退出码如实反映成败，不许把失败说成成功。

**两条反直觉的。** ① 听起来合理但没有依据的解释会增加**错误的**信任（Google PAIR）——所以第一次接触别急着讲原理。② 主动披露「我是 AI」会降低信任（12 项实验、4093 个观测的元分析，d≈0.5），但被发现隐瞒伤害更大（d=1.66）；场景是品牌与写作，不是 CLI。

**实测（本项目内）**：2026-10-28 用两个全新 subagent 按安装说明页各跑一次。第一轮把欢迎词放在检查之后、并按「首次 / 再来」分两版——变体选对了，但用户先对着空白等了一次命令。第二轮改成先说一句、再检查——三句开场都在最前，且每次耗时动作之前都出现了「我要做什么」。**结论：「先说再查」改善的是等待感；变体选择两轮都对。** 注意：2026-10-28 产品负责人随后决定**过程状态一律不播报**（安装页因此删掉了「正在下载」那一行）——这与本节引用的 NN/g「超过 1 秒要有反馈」相反，是有意为之：这一次的对话只有一两分钟，且节奏由人掌控。

## 来源

- Command Line Interface Guidelines：https://clig.dev/
- NN/g · Confirmation Dialogs Can Prevent User Errors：https://www.nngroup.com/articles/confirmation-dialog/
- NN/g · Progress Indicators Make a Slow System Less Insufferable：https://www.nngroup.com/articles/progress-indicators/
- NN/g · Response Times: The 3 Important Limits：https://www.nngroup.com/articles/response-times-3-important-limits/
- NN/g · Progressive Disclosure：https://www.nngroup.com/articles/progressive-disclosure/
- NN/g · Recognition and Recall in User Interfaces：https://www.nngroup.com/articles/recognition-and-recall/
- UI Patterns · Onboarding Checklist：https://ui-patterns.com/patterns/OnboardingChecklist
- UX Tigers · Progress indicators（二手摘要）：https://www.uxtigers.com/post/progress-indicators
- kin-design-system · Onboarding and setup（二手摘要）：https://github.com/yehyakin/kin-design-system/blob/a03b508654967529c0c03798ffc77493be33fa65/pages/onboarding-and-setup.md
- GOV.UK · Clear language：https://guidance.publishing.service.gov.uk/writing-to-gov-uk-standards/writing-guidelines/clear-language/
- NN/g · UX Writing FAQs（阅读年级、行话、缩写）：https://www.nngroup.com/articles/ux-writing-faqs/
- NN/g · Plain Language for Experts：https://www.nngroup.com/videos/plain-language-for-experts/
- NN/g · Error Message Guidelines：https://www.nngroup.com/articles/error-message-guidelines/
- UI Patterns · Onboarding is becoming a conversation：https://ui-patterns.com/blog/onboarding-is-becoming-a-conversation
- MetLife · Conversational UI：https://design.metlife.com/foundations/core-guidance/conversational-ui/
- Apple HIG · Writing：https://developer.apple.com/design/human-interface-guidelines/writing
- Apple HIG · Onboarding：https://developer.apple.com/design/human-interface-guidelines/onboarding
- Homebrew `install.sh`：https://github.com/Homebrew/install/blob/master/install.sh
- uv installer：https://releases.astral.sh/installers/uv/latest/uv-installer.sh
- clig.dev：https://clig.dev/
- Apple 简体中文用「你」（社区讨论，非官方规范）：https://www.zhihu.com/question/295195379
