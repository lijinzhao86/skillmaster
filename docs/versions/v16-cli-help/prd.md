# v16-cli-help · 产品需求

> **最后更新**：2026-10-24（多轮三路循环审计后修订）
> **所属版本**：[`README.md`](README.md)
> **前身**：v16 原本是「文档为 agent 优先」，已整体回退（`git tag archive/v16-agent-docs`）；这一版换成同一个问题的更前置一步——**先让每条命令能自述**。

<!--
这是本版需求的权威文档。README 只放摘要，别处不要重复这里的内容。
-->

## 用户与场景

**谁在用**：编码 agent（主要读者），以及照文档敲命令的人。

**在什么场景下用**：

- agent 手上有 CLI，接到的是任务而不是命令——「把这个 skill 装上」「查一下有没有能解析 PDF 的」「帮我发一版」「删掉这个 skill」。它不认识具体命令时，**第一反应是敲 `<命令> --help`**；敲错了会再往上敲一级。
- 人照着 README 或提示敲，打错一个词想知道「是不是该用另一个」。

**今天的事实**（口径：2026-10-24，`go/ast` 读 `cmd/skillmaster` 的 dispatch 派生命令树，并逐个实跑 `--help`；排除 `help` / `-h` / `--help` 本身）：

| 事实 | 数值 |
|---|---|
| 可执行命令路径（dispatch 里每个能被调用的 case，减去只负责转发的六个分组：`auth` / `skills` / `skill` / `version` / `skill tag` / `skill category`；**裸 `skills my-tags` 算一条**——它不带动词时自己列出标签；**`skills my-tags delete` 也算一条**——它给的是拒绝与去处） | **56** |
| 其中接受 `--help` 的 | **20** |
| 不接受 `--help` 的 | **36** |

那 20 条是：`login`、`skill grep` / `edit` / `show` / `tags` / `join` / `leave` / `restore`、`skills list` / `search` / `categories` / `public-tags` / `my-tags`、`skills my-tags add` / `rename` / `describe`、`skill tag add` / `remove`、`skill category set` / `clear`。**它们全都打在 stderr**，stdout 一个字节都没有。`skill show a/b help` 是**另一类**：`show` 只接一个地址，多出来的 `help` 让它报用法错误、退出 2（见验收 #17 的变化清单）。

四处具体的痛处（都已实跑确认）：

| 命令 | 今天 |
|---|---|
| `skillmaster skill invoke --help` | 退出 1，`错误：地址要写成 <命名空间>/<名字>[@版本]，收到 "--help"`——`--help` 被当成地址（**已有凭据时**；无凭据且能开浏览器时会先尝试登录） |
| `skillmaster version publish --help` | 退出 1，`不认识的参数 "--help"` |
| `skillmaster skill invoke a/b --help` | 退出 1——`--help` 写在后面就只被当成多余参数 |
| `skillmaster foo`（打错一个词） | 退出 1，**把 8,708 字节的顶层帮助连同那行 34 字节的错误一起吐到 stderr（合计 8,742 字节）** |

另外两条现状：

- **顶层 `skillmaster help` 本身又打在 stderr、又有 8,708 字节 / 98 行**，其中约三分之一是讲设计取舍与环境变量的段落（「为什么 skill 与 skills 是两个组」「invoke 必须先跑」）——对一个来找命令的 agent 是纯上下文税。
- **退出码没有契约**：绝大多数失败是 1；`skill grep` 有 2，但它**把认证失败也报成 2**（`files.go:507`），所以「2 = 用法错误」今天并不成立。

**现在的替代做法为什么不够**：顶层那份长帮助已经列到动词与一句话，但**36 条命令完全没有自己的帮助**；已有的 20 条有用法行，却打在 stderr、没有例子与失败去处，退出码也没有契约（只有 `skill edit` 那两处写了「拒绝，退出 1」）；仓库里的 `skillmaster-cli/README.md` 装在 agent 手里没有。

**调研结论（2026-06 前后三家现状）**：

| CLI | 怎么让 agent 找到正确的命令 |
|---|---|
| **AWS CLI** | 每一层 `aws help` → 服务 → 操作，每个操作页带 **Examples**；2026-06 起 `aws configure agent-toolkit` 还能把 MCP Server + 40 多个 agent skills 装进 Kiro / Claude Code / Codex / Cursor |
| **GitHub CLI** | 每条命令 `--help`，**带 Examples 与 See also**；`gh help <topic>` 有 environment / exit-codes / formatting / reference；退出码集中定义（0 成功 / 1 错误 / 2 取消 / **4 需要认证**）；2026-04 预览了 `gh skill`「安装与管理 agent skills」 |
| **阿里云 CLI** | `aliyun --help`（产品）→ `<产品> --help`（操作）→ `<操作> --help`（参数），另有 `aliyun help <product> <api>`；**AI 模式**（≥3.5.0）：紧凑帮助、**错误带原因与下一步**、本地参数校验、去 ANSI、User-Agent 标明来自 agent |

三家的共同点正是本版要补的：**导航是「每一层都能 `--help`」，不是一份外面的总纲**；三家里最实用、也最容易被漏掉的两样是**例子**与**退出码**。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | **每条命令路径接受 `--help` / `-h`，出现在命令参数的任意位置都算**（`skill invoke --help`、`skill invoke a/b --help`、`version list --help` 都必须有效）；**裸 `help` 只在「有子命令的节点」的第一个参数位置算**（`skillmaster help`、`skillmaster skill help`、`skillmaster skills my-tags help`）——叶子命令上它是数据（`skill grep a/b help` 的 pattern、`skill read a/b help` 的文件名、`skill download a/b --into help` 的目录、`skills my-tags add help` 的名字）——**今天部分叶子命令会把裸 `help` 当帮助**（`skills search help`、`skill show a/b help` 都打印用法），本版改成数据，写进验收 #17 的变化清单；**不加** `--` 终止符；打印该命令的帮助到 **stdout**，退出 **0**。**help 在认证、参数校验与任何副作用之前处理**：没登录时敲 `--help` 得到的是帮助，不是退出码 4；**`logout --help` 今天会真的把本机凭据清掉**，本版要求它只打印帮助 | CLI |
| 2 | **帮助内容契约**，每条**可执行**命令八项：一行说清干什么；用法行（flag 的值写法也写出来）；只列**改变行为的** flag——**声明里只登记这类 flag**，`--help` / `-h` 与 `--json` 这类通用 flag 由一份共享登记表提供、不进每个命令的声明——**登记表按命令标适用性**（`--help` / `-h` 每条都有；`--json` 只在本来就支持它的命令上有）（每项 ≤ 25 字，**按 rune 计**）；**成功时打印什么**（有 `--json` 的命令列出字段，并用一句话区分「正文走 stdout、诊断走 stderr」；**只拒绝的命令**——七个 version 页面专用动词与 `skills my-tags delete`——改写成「拒绝时说什么 + 去处」）；**会以哪几种方式失败、每种的下一步**（每类 ≤ 35 字，按 rune 计），以及退出码一项写清**本命令可能返回的码**；**0–2 条可粘贴的例子**（**由声明机械判定**：位置参数 ≥ 2 或 flag ≥ 2 时至少一条；链路 `skills search` / `skill invoke` / `skill read` 各至少一条）；**上级命令地址**（`skill invoke` → `skillmaster skill --help`；有明确的下一步时再加一行，如 `skill invoke` → `skill read`）。**每个命令的参数解析要能单独调用**（只解析、不执行：不连服务端、不改状态），供测试把 fixture argv 绑到字段上。**每条帮助 ≤ 2 KB**（含分组帮助的「子命令：」块）、无 ANSI 颜色、不写背景与设计理由、不用相对时间词（目前 / 即将 / 暂时 / 以后 / 未来） | CLI |
| 3 | **分组帮助列全动词**：`skillmaster skill help` / `skillmaster skill --help` / `skillmaster skill -h` 都列出该组全部动词，**放在一段以「子命令：」开头、以空行结束的块里**，块内一行一条（名字 + 一句 ≤ 12 字的说明），到 **stdout**、退出 **0**；**纯分组帮助只要求四项**——描述、用法、这个块、上级/顶层地址——不套八项契约（纯分组自己不执行，没有成功输出与失败类别）；**任何有子节点的命令都适用「子命令：」块这条**，其中 `skills my-tags` 是**可执行**节点：它自己仍按八项契约（做 #2），只是额外带这个块；`skills my-tags` 的块里 `delete` 那一行要写明「只在控制台」；**纯分组只给分组、不带动词**（`skillmaster skill`）打出同一张表，到 **stderr**、退出 **2**（没做事，是用法错误）。**`skills my-tags` 是例外**：它不带动词时照旧列标签（它是一个可执行命令），子命令列表只在 `help` / `--help` / `-h` 时给出。顶层 `skillmaster help` 列出四个分组与 `login` / `logout` / `setup`，每个名字给一行 `--help` 地址；**`skillmaster --help` 与 `-h` 与 `skillmaster help` 同义**（stdout、退出 0）；**裸 `skillmaster`（无参数）**与纯分组同规则：stderr + 命令表 + 退出 **2**（今天退出 1 并吐 8,739 字节 / 99 行） | CLI |
| 4 | **未知命令 / 动词 / flag 给建议**：`skillmaster skil`、`skillmaster skill invok`、`version list --jsno` → stderr **一行** + 「你是不是想用 `skil`→`skill` / `invoke` / `--json`」+ 上级帮助地址，退出 **2**。**判据**：候选 = **同一层的兄弟名字**（顶层就是顶层命令名；分组内就是该组的动词名；flag 就是该命令声明的 flag ∪ 该命令适用的登记表条目），取编辑距离（Levenshtein）**最小且 ≤ 2** 的那个。**两种情况都只给上级帮助地址、不猜**：没有任何名字的距离 ≤ 2（`skillmaster foo` 就走这一条——它和七个顶层名字的距离都 > 2），或最小距离并列有多个。**一律不再把整份顶层帮助吐出来**。**所有退出 2 的用法错误**（地址写错、参数个数不对、flag 值非法…）都以 `skillmaster <该命令路径> --help` 收尾——今天 `invoke` 的地址错误只说「地址要写成…」。**建议行的 stdout + stderr 合计 ≤ 200 字节**（给建议时也一样） | CLI |
| 5 | **退出码写成公开契约并写进顶层 help**：`0` 成功、`1` 普通失败、`2` 用法错误、**`4` 凭据不可用，且重试无用**——要换一份凭据。**判 4 的三类**：① 命令带了 `--no-login` 且本机没有凭据；② `SKILLMASTER_API_KEY` 不是 key（**判据是既有的 `apikey.IsKey`**，与服务端同一套前缀规则）；③ 服务端 401 且码是 `unauthenticated` / `token_idle` / `token_expired` / `token_revoked`（登录态与 API key 两种都算）。**这几类的下一步各不相同**（重新登录 / 控制台续期或新签 / 检查环境变量），但都属于「要换一份凭据」，所以同一个码。**判 1 的**：`account_suspended`（换凭据也没用）；**登录流程本身失败**（网络、浏览器打不开——可以重试，自动登录失败也走这条）；**本机有凭据但读不出来**（钥匙串被锁、文件权限坏了——修环境比重登更对）；**401 但没有码、或码不在上面四个里**；以及除此以外的一切失败。**优先级**：先 help（0），再参数校验（2），再认证（4 / 1）——`skill invoke` 给一个坏地址且本机无凭据时报 **2**。**`skill grep` 的归一**：它今天把一切失败都报 2；本版 **2 只留用法错误**，`apikey.IsKey` 不成立与四个 401 码改成 **4**，其余（403、网络、`account_suspended`）改成 **1**；它**没有 `--no-login`**，所以「无凭据」场景照旧先自动登录、登录失败按 1，不归 4。另外**只拒绝的命令**要显式排除在 2 之外、仍退 1（它们是对的命令，只是不在 CLI 做）。两类今天的机制不同：七个 version 页面专用动词（`publish` / `rollback` / `revoke` / `restore` / `offline` / `online` / `delete`）经 `usagef` 被归成用法错误——退出码今天恰好是 1，但按新表会被归成 2；`skills my-tags delete` 是纯 `errors.New`，今天就走 1，只需在声明里登记成「不是用法错误」。`auth status` 把服务端的拒绝原因原样带出来（今天被包成一句 `服务端：…`）。同步更新 `skillmaster-cli/README.md` 的退出码表（本版新增，与顶层帮助、声明三处一致）。`--json` 的错误对象形状不变（`{ok,error:{code,message,hint,server_code?,issue?}}`，对象仍打在 stderr），只是进程退出码跟着改（唯一的内容例外见验收 #17） | CLI |
| 6 | **已有的 20 条帮助与顶层 `usage()` 按同一契约重写**：20 条从 stderr 改到 stdout、补齐缺失的项；顶层从 **8,708 字节 / 98 行**压到 ≤ 2 KB，讲设计取舍的段落挪进代码注释或文档 | CLI |
| 7 | **规范：任何 CLI 能力的新增或变更，必须在同一个提交里带上它的帮助**——一句话描述、用法行、只登记改变行为的 flag、成功输出（只拒绝的命令写「拒绝 + 去处」）、例子、失败类别与下一步、退出码、上级命令地址，**共八项**都不能少（与做 #2 的八项是同一张单子）；错误码必须是声明里的一份 **registry 常量**，处理函数不许自己拼一个未登记的码；**帮助与命令只有一个来源**：每个命令一份声明式的说明（与命令实现放在同一处，可被程序打印、可被测试读），注释只解释「为什么」，不复述帮助内容 | 仓库 |
| 8 | **规范要有强制手段**：一个测试扫描**包内所有 dispatch 的 `switch args[0]`**（今天八处）派生**命令树**（本版删掉 26 处帮助分支后，剩下的每一处 `switch args[0]` 都是 dispatch），任何命令路径没有声明就失败并打印路径；**包内出现一个不在登记表里的 dispatch 函数也失败**；声明里写了命令树里不存在的路径，同样失败 | 测试 |
| 9 | **`skillmaster help env`**：环境变量（`SKILLMASTER_SERVER` / `SKILLMASTER_API_KEY` / `SKILLMASTER_SKILLS_DIR` / `SKILLMASTER_WEB_URL`）搬到这个 topic（≤ 512 字节；**CLI 代码里读的就是这四个**，服务端另有自己的 `SKILLMASTER_*`，不属于这个 topic），顶层只留一行指过去——否则「顶层 ≤ 2 KB」与「信息不丢」只能二选一。形状照 `gh help environment` | CLI |
| 10 | 顶层帮助末尾留一行指到文档（地址等文档那一版落地再填，本版只留位置） | CLI |

**`help env` 不计入 56 条命令路径**：它是一页 topic，不做事，也没有自己的参数。

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 文档（CLI 用法之外的那层「该用哪条」） | 已回退成 `archive/v16-agent-docs`；先把每条命令能自述做扎实 | 本版交付后重新评估 |
| 2 | shell 补全（bash/zsh/fish） | 给终端里的人用，agent 不按 Tab | 有明确的人机体验需求时 |
| 3 | AI 模式（紧凑输出、去 ANSI、结构化错误的完整形态） | 阿里云那套是一整份输出契约，改动面远超帮助；本版的错误已经带 `hint`（既有字段，`cliFailure.Hint`） | 有明确的 agent 反馈说输出难解析时 |
| 4 | MCP server / agent toolkit 那样的分发 | 是另一条通道（AWS 走的是它），但不能替代命令自述 | 有 MCP 客户端集成需求时 |
| 5 | 交互式提示（`--cli-auto-prompt` 之类） | agent 不按回车；人也没有这个需求 | 除非出现明确的非交互使用场景 |
| 6 | 改任何命令**正常调用路径**的参数与输出 | 本版改的是帮助文本、用法错误提示、退出码，以及 `auth status` 把服务端拒绝原因原样带出这一处（#5 的既有缺陷）；正常调用的参数、成功输出、`--json` 形状零改动 | 不打算做（除非验收证明必须改正常路径） |
| 7 | 英文帮助 | CLI 的帮助、错误消息本来就是中文；标识符照抄代码 | 有明确的英文用户时 |
| 8 | `gh reference` 那样的全量命令参考 | 顶层与单条各 ≤ 2 KB 的预算下，全量参考必然是第二份权威，会漂；56 条靠「分组 + 单条帮助」已能覆盖 | 命令数大幅增长时 |

## 控制台操作与 CLI 对应

本版**不新增任何控制台操作**：改的是 CLI 自己的帮助文本与退出码。

| # | 控制台操作 | CLI 命令 | 备注 |
|---|---|---|---|
| — | （无） | — | [ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 管的是反方向，不受影响 |

## 验收与指标

### 验收标准

每条都写成「跑什么、看到什么算过」。命令路径的集合由命令树测试每次现扫源码派生（不留共享快照，避免第二份会过期的副本），不手写数字；**fixture argv、解析规则与 eval harness 的参数钉在本版的 `test-plan.md`**。

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | **`--help` / `-h` 覆盖 56 条命令路径、七个有子节点的节点与顶层**：退出 0、stdout 非空且 ≤ 2048 字节、不含 ANSI（正则 `\x1b\[[0-9;]*m`）、stderr 为空。**裸 `help` 只在七个有子节点的节点与顶层上跑**（叶子命令上它是数据，见 #12），所以三种写法都跑的对象只有那八个节点 | 命令树测试逐条跑；路径集合来自测试每次派生的结果 |
| 2 | **帮助与声明一致**：帮助里渲染出的 flag 集合 == 声明里的 flag ∪ **该命令适用的**共享登记表条目；处理函数里出现的 `case "-…"` 字面量（**长短都算**，`-F` / `-i` / `-n` / `-l` 也是 flag；`case arg == "--x"` 同样算）⊆ 同一集合；用法行的位置参数名字、必填与顺序 == 声明；**「描述」「用法」「成功输出」「失败类别」「退出码」五项非空，且声明的每一类失败都有非空的「下一步」**（六个纯分组与顶层按四项契约，不在此列；`skills my-tags` 是可执行节点，仍在这八项里）；每个 flag 的说明 ≤ 25 rune、每一类失败的说明与下一步各 ≤ 35 rune（**量的是说明，不是整行**——行里的 flag 名与占位符不计；按 rune，不用字节）；`--flag <值>` 与 `--flag=<值>` 的写法与声明一致；有 `--json` 的命令带「正文走 stdout、诊断走 stderr」这句；例子 0–2 条；帮助含上级路径 + `--help`；有 `--json` 的命令，帮助里的字段列表 == 声明里的 `jsonFields`。**「只列改变行为的 flag」不做自动判定**（声明只登记这类 flag 本身就是判据），与「不写背景与设计理由」一起列入评审清单，由人看一遍。**真正有判别力的是反向**：把每个声明的 flag 传进命令必须被接受（不出现「不认识的参数」）；按声明的顺序给一份 fixture argv，每个位置参数必须绑定到预期字段——flag 解析散在 helper（`parseGrepArgs` / `parseEditArgs` / `splitNewDraftFlags`）里，只扫 `case` 会漏 | 命令树测试的第二部分（按声明比对 + 运行时 fixture，不靠从 AST 反推语义） |
| 3 | **新增命令必须带帮助**：把命令树派生抽成纯函数，喂它一个没有声明的节点 → 返回错误且含 `skill invok` 这样的路径；另喂一个**未登记前缀**的新分组（模拟 `switch` 里多出 `case "future"`）→ 必须失败并要求登记；另跑一次真实 dispatch 的派生，要求「派生集合 == 声明集合」；**并断言包内每个 dispatch 函数都在登记表里**（多出一个未登记的即失败） | 单测（fixture 节点）+ 全量派生比对 |
| 4 | 分组帮助，**七个有子节点的节点各跑 `--help` / `-h` / `help` 三种写法**（`auth` / `skills` / `skill` / `version` / `skill tag` / `skill category` / `skills my-tags`）：stdout 里**「子命令：」块**（从该行起、到下一个空行止）解析出的子命令集合 == 命令树里该节点的**直接子节点**集合（含子分组名，不展开后代）；stdout 非空、退出 0；**描述、用法、上级/顶层地址三项也非空**；**块内每条都有一个非空说明，且说明 ≤ 12 字**；**`skills my-tags` 的块里 `delete` 那一行含「只在控制台」** | 21 条命令 + 集合比对 |
| 5 | 顶层 `skillmaster help`：**stdout**、退出 0、≤ 2 KB（2048 字节）；`auth` / `skills` / `skill` / `version` / `login` / `logout` / `setup` 每个各占一行，行内含该名字与 `<name> --help`（名字因此在一行里出现两次：行首一次、地址里一次）；`skillmaster --help` 与 `-h` 的输出与它逐字节相同（退出 0）；stdout 里含 `0 / 1 / 2 / 4` 四行退出码说明；**末行以 `文档：` 开头，且后面至少还有一个非空 token**（本版允许是占位地址） | 重定向后解析 |
| 6 | 未知动词（`skillmaster skill invok`）：退出 **2**，stdout 为空，stderr 恰好一行，且该行含 `invoke` 与 `skillmaster skill --help`；stdout + stderr 合计 ≤ 200 字节 | 跑一次 |
| 7 | 未知顶层命令（`skillmaster foo`）：退出 **2**，stdout 为空，stderr 恰好一行，含 `skillmaster help`、**不含**「你是不是想用」；`skillmaster skil`：同样一行但含 `skill`。两种情况 stdout + stderr 合计 ≤ 200 字节 | 各跑一次并 `wc -c` |
| 8 | 未知 flag（`version list --jsno`）：退出 2、一行、含 `--json`；**地址写错**（`skill invoke 坏地址`）：退出 2、stderr 以 `skillmaster skill invoke --help` 收尾；**参数个数不对**（`skill edit a/b` 少给一个位置参数）：退出 2 并同样以 `skillmaster skill edit --help` 收尾；**flag 值非法**（`skill grep a/b pat --path` 缺值）：退出 2 并同上。各种情况 stdout + stderr 合计 ≤ 200 字节 | 各跑一次并 `wc -c` |
| 9 | 退出码四个各有一个可复现场景：`0`（`skills list` 对空数据的假服务端）、`1`（`version publish` 的页面专用拒绝与 `skills my-tags delete`，今天都已是 1；`skill grep` 对 403 今天报 2、本版改成 1）、`2`（`skill invok`）、`4`（`skills list --no-login`，`HOME=$(mktemp -d)` + `SKILLMASTER_SERVER=http://127.0.0.1:<随机端口>`、清空 `SKILLMASTER_API_KEY`——**钥匙串按 service + 服务器寻址**，只换 HOME 藏不住本机已有的凭据） | 六个场景各跑一次，只断言退出码 |
| 10 | **凭据不可用都退 4**（假服务端按 `code` 各回一次 401）：没有凭据但**不让它自动登录**（`skills list --no-login`，`HOME=$(mktemp -d)` + `SKILLMASTER_SERVER` 用本次运行随机端口、清空 key——`keychain.go` 的 `keyring.Get/Set/Delete`（`:29` / `:48` / `:55`）都按 service + 服务器寻址，只换 HOME 藏不住本机已有的凭据）；`unauthenticated`；`token_idle`；`token_expired`；`token_revoked`；`SKILLMASTER_API_KEY=not-a-key`（`apikey.IsKey` 不成立）。**`skill grep` 没有 `--no-login`**，它的「无凭据」场景会先走自动登录、不确定，所以对 grep 只用「key 形状不对」与两种 401 来测两类失败都退 4（key 形状、401；此前它们被包成 2）；**grep 的无凭据（自动登录失败）退 1** | 逐场景跑，断言退出码 + 提示里出现该场景的下一步关键词（重新登录 / 控制台续期或新签 / 检查环境变量） |
| 11 | **不是 4 的两类**：`auth status` 遇到 401 `account_suspended` 时退 **1**，stderr 含「账号已被停用」且**不含**「登录」；登录流程本身失败（网络不通）也退 **1**。另外（这一条只断言 stderr，退出码按 #10 是 4）`auth status` 遇到 401 `token_expired` 时，stderr 里能看到**服务端的原始码**（断言出现 `token_expired` 这个字面量；今天被包成一句 `服务端：…`；**`auth status` 没有 `--json`**，只看 stderr） | 假服务端 401 + `account_suspended`；登录指向一个不可达地址；`auth status` 对 `token_expired`；**凭据读不出来**（把文件 store 的凭据文件权限改成 000，或让假钥匙串报错）→ 1；**401 没有 body 里的 `code`**、**401 带一个不在那四个里的码** → 各 1。**这三个场景钉在 `auth status` / `skills list` 上跑**——放在 `skill grep` 上今天会被它的 `exitError{2}` 截走，测的就不是这条 |
| 12 | **`--help` / `-h` 在任意位置**：对**全部 56 条路径**的 fixture argv，把 `--help` 插到每个位置（0..n），每次都退出 0，且 stdout 与它在位置 0 时逐字节相同。**裸 `help` 的边界**：`skillmaster help`、`skillmaster skill help`、`skillmaster skills my-tags help` 打印帮助；`skill grep a/b help`（pattern）、`skill read a/b help`（文件名）、`skill download a/b --into help`（目录）、`skills search help`（关键词）、`skills my-tags add help`（名字）都照常执行，不触发帮助。**无凭据的服务器地址 + 空 HOME 时，`--help` 仍退 0 并打印帮助**（帮助在认证之前处理） | 属性式跑法，复用 #1 的路径派生结果 |
| 13 | 只给分组：`skillmaster skill` / `skills` / `auth` / `version` / `skill tag` / `skill category` 各跑一次——stderr 打出该组动词表、stdout 为空、退出 **2**；**裸 `skillmaster`（无参数）**同样 stdout 为空、stderr 是命令表、退出 **2**（今天这七个场景都退 1） | 六次 + 裸顶层一次 |
| 14 | 顶层帮助从 **8,708 字节 / 98 行**降到 **≤ 2048 字节**；20 条已有帮助全部改成 stdout、且满足 #1 | 同样的测试 + `wc -c` |
| 15 | **失败类别与运行时一致**：每条命令的声明里有 `failures[{code, 类别, 下一步}]`；**静态 lint**：处理函数不许直接构造未登记的错误码（所有码必须经一份 registry 常量）；**每个声明的码必须有一个 fixture 能真实触发**——标注「仅防御、不可触发」的必须在交付时逐条写明理由并列在本节末尾的清单里，不能默认放行（起始为空，本版交付时 1 条：`login_failed`） | lint + 逐条 fixture；「仅防御」清单在本节末尾维护 |
| 16 | `skillmaster help env`：退出 0、在 stdout、≤ 512 字节，四个环境变量名都在；**顶层帮助里不再列这四个变量，但有一行指向它** | `wc -c` + 文本比对 |
| 17 | **正常调用的参数与成功输出零改动**：参数不变、成功输出逐字节不变、`--json` 的**成功对象**不变。**有意的变化逐项列在快照差里**：帮助从 stderr 到 stdout（20 条 + 顶层）；裸 `skillmaster` 从 8,739 字节的顶层帮助变成命令表、六个纯分组从各自的长帮助（`skill` 5,360 / `skills` 1,808 / `version` 2,155 / `auth` 509 / `skill tag` 168 / `skill category` 146 字节）变成同一张表，两者退出码 1→2；`skill` 组的「子命令：」块新增；**顶层各组的一行说明改由声明提供**（`skill` → 「单个 skill 的动词」、`skills` → 「整个库的动词」，与旧帮助的措辞不同，其余五条按声明里的一行话重写）；带 `--help` / `-h` 的调用从报错变成帮助，**包括它出现在某个 flag 的值位置上**（今天 `version list a/b --query --help` 会去搜这个字符串、`skill submit --help` 会把它当目录，之后都打印帮助——这是「任意位置都算」的代价）；**裸 `help` 在叶子命令上从「打印帮助」变成数据**（`skills search help` 今天打用法、改后按关键词搜；`skills my-tags add help` 今天打用法、改后建一个叫 help 的标签；`skill show a/b help` 今天打用法、改后**报用法错误退 2**——`show` 只接一个地址，`help` 成了多余的位置参数）；用法错误 1→2（含三处过去**不是**用法错误的：`--offset` 缺值或越界、`@new` 那一组 flag 缺值、`--role` 给了不认识的档位；以及两处过去**静默放过**的：`logout` 带多余参数从忽略变成用法错误、`skill submit` 把不认识的旗标当目录读）；`skill grep` 的认证失败 2→4 与其余 2→1；`auth status` 401 的 stderr 文案（#11，它没有 `--json`，变的是文案不是对象）。`--json` 的错误对象除这一处外不变 | golden 快照 diff（错误对象按命令断言应有的字段；`server_code` 只在服务端有响应时存在；并断言对象仍在 stderr）；测试里列出预期要更新的旧断言（帮助改到 stdout 的那几条） |
| 18 | 帮助里没有相对时间词：对全部 56 条帮助的 stdout 与 `help env` 扫 `目前` / `即将` / `暂时` / `以后` / `未来` 这五个词（`当前` / `最近` / `稍后` 不在扫描表里——「当前目录」「最近一次」「稍后重试」都是合法表述） | 扫快照文本 |
| 19 | 退出码三处一致：**声明里全部命令的 `Exits` 取并集** → 顶层帮助里的四行 → `skillmaster-cli/README.md` 的退出码表。**权威只有一份**：`internal/help` 的 registry 定「码 → 退出类」，声明只决定「这条命令能出哪几类」，两处表格是 registry 的投影。**判据**：三处都投影成同一个码集合——声明并集 **== {0,1,2,4}**，两处表格**恰好四行**，码与含义都来自同一份 registry 常量 | 测试读三处，不一致即失败 |
| 20 | **例子与下一步在交付里存在**：每条「2+ 位置参数或 2+ 改变行为的 flag」的命令、以及 `skills search` / `skill invoke` / `skill read` 三条主链路命令，帮助里有至少一条（至多两条）以 `skillmaster <该路径>` 开头的例子，且该 argv 走 #2 的同一条**纯解析入口**不报用法错误（**只解析、不执行**：不连服务端、不改状态）；下一步固定表四条各有一行——`skills search` → `skill invoke`、`skill invoke` → `skill read`、`skill submit` → 上线要在浏览器点、`version publish` → `version list` | 解析帮助文本 + 纯解析器跑例子 argv；下一步按一张固定表断言 |
| 21 | **帮助没有副作用**：对本次运行随机端口的服务器地址（`SKILLMASTER_SERVER`）先放一份凭据，再跑 `skillmaster logout --help`——退出 0、stdout 是帮助、**凭据仍能读出来**（今天它会真的退出登录） | Go 测试里对这份 store 播种与回读；**account 用那个随机地址**（service 固定 `skillmaster-cli`，不会与真实条目撞），用 `t.Cleanup` 删掉（`defer` 在 `os.Exit` 下不执行）；**不能只靠 HOME 隔离**——`keychain_other.go` 让 macOS 永远走钥匙串（#9/#10 两个都设，是为了同时覆盖 Linux 的文件 store）。CI 无钥匙串时钥匙串那支跳过、文件那支仍要验 |

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 帮助覆盖率 | 有声明并有帮助的命令路径 ÷ 命令路径总数 | 命令树测试打印两个数 | **100%** |
| 单条帮助体积 | 改后按一条命令 `--help` 的 **stdout** 字节数；**今天 `skill grep --help` 的帮助正文已 1,344 字节（打在 stderr）** | `wc -c` | ≤ 2 KB（2048 字节） |
| 顶层帮助体积 | `skillmaster help` 的字节数；**今天 8,708** | `wc -c` | ≤ 2 KB（2048 字节） |
| 首次找对率 | 20 道「自然语言任务 → 期望命令路径」的固定答案表（`skillmaster-cli/testdata/help-eval.json`，本版新增）：**覆盖六个分组各至少两条、一条多步消费链路、一条错误恢复**（错误恢复那道题以**一段真实的错误输出**为起点，问下一步敲什么，因此全程不需要执行 `--help` 以外的命令）；harness 只允许执行 `--help`（含顶层）。**每道题只有一次作答**：智能体最后给出的命令路径。**两个数字定义**：① **首次找对** = 作答路径 == 期望路径的任务数 ÷ 20（顶层 `--help` 是导航、不是作答，只有叶子命令路径才算作答）；② **调用次数** = 从任务开始到给出作答之间执行的 `--help` 次数（顶层那张也算一次），取 20 道题的中位数；**没在步数上限内作答的题按答错计、调用数记上限值**。同一批 3 次运行同时产出这两个数 | 模型、温度、提示词、步数上限与聚合口径钉在本版的 `test-plan.md`；人工只做复核，不作判定 | 首次找对率 ≥ 80%；调用次数中位数 ≤ 4。**80% 是绝对门槛、不是提升门槛**：改动前不设基线（顶层帮助里根本没有动词级答案） |

#### 「仅防御」错误码清单

验收 #15 要求每个声明的错误码都有一个 fixture 能真实触发；标注「仅防御、不可触发」的必须列在这里并写明理由。起始为空；本版交付时有**一条**，理由如下：

- **仅防御、不可触发**：`login_failed`。登录失败没有 `--json`，也没有稳定的服务端码；声明用它是为了让 `login` 的帮助写出「浏览器里没有同意」这一步，分类器仍按普通失败（1）处理。fixture 见 `cmd/skillmaster/errors_test.go` 的 `defensiveCodes`。


## 依赖与前置

- **只动 CLI**：服务端、控制台、站点、数据库零改动。
- **现有 dispatch 结构不变**：命令树的派生读的是今天的 `switch args[0]`（`main.go:52`、`:244`、`:362`、`:573`、`version.go:61`、`tags.go:211`、`:528`、`:608`），不改调度方式。
- **不新增依赖**：编辑距离与文本都在标准库里（`apikey.IsKey` 也是既有的，key 形状的判据直接用它）。
- **要改的既有行为**：帮助从 stderr 改到 stdout（20 条 + 顶层）；**裸顶层与六个纯分组从 1 改成 2**（没做事就是用法错误，与新契约一致）；**真正的用法错误 1→2**；**页面专用拒绝保持 1**（今天已经是 1，但按新表会被 `usagef` 归成 2，要显式排除）；**认证失败新增 4**（`skill grep` 今天把它们包成 2，其余命令是 1）；**带 `--help` / `-h` 的调用从「报错 / 被当成地址」变成「打印帮助」**（`skill grep a/b --help` 今天退 2 报「不认识的参数」，`skill invoke --help` 今天把 `--help` 当地址；本版不加 `--` 终止符）；**顺带修两处既有缺陷**——`skill grep` 把三类认证错误都包成 2、`auth status` 丢掉服务端原因。
- **`--no-login` 是既有参数**，但只在这些命令上：`skills list` / `search` / `categories` / `public-tags` / `my-tags` 与 `skill show` / `tags`；核心读命令（`skill grep` / `read` / `files` 等）**没有**。验收 #9/#10 用的是 `skills list --no-login`，不需要新增参数。
- **文档那一版是可选的下一步**：本版只在顶层帮助里留一行位置；填地址要等文档那一版落地。
- **一条 ADR 要写**：**退出码是公开契约**（0/1/2/4，4 = 凭据不可用且重试无用——要换一份凭据，下一步可以是登录 / 续期 / 检查环境变量）——它会被脚本、CI 与 agent 分支依赖，属于跨版本的取舍，不进版本文件夹。
