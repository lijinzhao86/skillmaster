# ADR 0173 · 帮助分两层：单命令参考与 Guide 手册

- **状态**：已采纳
- **日期**：2026-11-09
- **依赖**：[ADR 0087](0087-cli-exit-codes-are-a-public-contract.md)（退出码是公开契约）、[ADR 0081](0081-every-console-operation-has-a-cli-command.md)（控制台操作要有 CLI 对应）、[ADR 0136](0136-cli-file-verbs-follow-intent.md)（`put` / `download` / `@new` 退役，覆盖前先读）、[v16 帮助契约](../versions/v16-cli-help/README.md)
- **不改**：[ADR 0087](0087-cli-exit-codes-are-a-public-contract.md) 的退出码集合与含义；[ADR 0136](0136-cli-file-verbs-follow-intent.md) 的命令面

## 背景

CLI 的帮助和 Guide 都在描述同一批命令，但它们各自长出来的方式不同：

- `--help` 是**声明渲染**出来的，与命令一一对应；渲染链与生成视图见 [`docs/current/cli.md`](../current/cli.md)。
- Guide 是**手写的 skill**：线上地址 `skillmaster/skillmaster-guide`，按“判断 → 步骤 → 完成信号 → 出错处理”组织。它把命令、flag 与示例又抄了一遍。

2026-11-04 的 v44 把 `skill put` 并进 `write --from`、`get` 收编 `download`、`@new` 换成 `draft create`，并在覆盖前加了读证明（[ADR 0136](0136-cli-file-verbs-follow-intent.md)）。CLI 改了，Guide 没有改：线上 1.0.1 仍停在 2026-11-03，`author/edit.md` 教 `@new`，`author/media.md` 整篇围绕 `put`，`author/files.md` 用 `download`。`skmr help workflow` 也还在教这三个退役命令。Agent 照文档做，第一步就是退出码 2。

同一类断点还有三处：`secret`（v52）、`run`（v51）、`notifications`（v45）这些新能力没有进入 Guide 的索引；Guide 只在 registry 里，CLI 改动时 review 看不到它；评测题表里有两道题仍指向 `skill files` / `skill download`，完美作答的上限只有 90%。

产品负责人 2026-11-09 定了一个前提：**Guide 就是手册，只是现在基本没有人从头读手册，读者主要是 Agent。** 所以不需要再写一份人向手册；需要的是让这本手册对 Agent 准确、可查、可校验。

## 决定

1. **帮助分两层，不按“人 / Agent”分三份。**
   - **单命令参考**：`skmr <命令> --help`。回答“这个命令怎么拼、参数是什么、输出在哪、怎么失败、退出码是什么”。权威是 `help.Spec`。
   - **Guide 手册**：`skillmaster/skillmaster-guide` 与 `help workflow/auth/env` 的短入口。回答“我现在该做哪一步、做完看什么、错了先查哪里”。读者以 Agent 为主，人也可以读。
   - 本版**不另立人向手册**。以后若需要人向教程、截图与人向排版，从 Guide 派生，不维护第二份事实。
2. **同一事实只有一个家。** 命令语法、flag、失败表、退出码、`--json` 字段只在 `help.Spec`；任务顺序、判断、完成信号、出错先查哪里只在 Guide。Guide 可以写“这一步跑 `skmr draft create …`”这一行命令与它的用途，但不复制完整 flag 表与失败表；需要完整语法时指向 `skmr <命令> --help`。
3. **Guide 的源进仓库。** 新增 `skills/skillmaster-guide/`，从线上 1.0.1 原样取源；仓库是唯一的源，发布是单向的：`skmr skill submit skills/skillmaster-guide --to skillmaster/skillmaster-guide` → `skmr skill check` → `skmr version open … --sub actions` → 网页确认。**本版只搬源，不改正文，不发布**；正文修复与发布在下一轮。
4. **漂移由检查兜住，不靠人记得。** 六类检查：
   - 退役词：`@new` / `skill put` / `skill download` / `skill files` 在 Guide 与入口 skill 中出现的位置；
   - 命令与 flag：Guide 里每个 `skmr …` 的路径与 `--flag` 都在命令树与声明里；
   - Guide 能力入口：索引里能找到 `draft create` / `skmr secret` / `skmr run` / `notifications`；
   - 文字形状：顶层与分组页列对齐，同页不重复同一条限制，凭据失败只有一句话；
   - 评测题表：每道题的期望路径都存在，且覆盖 `draft create` / `secret set` / `run` / `notifications accept`；
   - 体积：顶层帮助、单页、`help workflow`、Guide 索引的字节与字符上限。
   **六类都写在 `skillmaster-cli/cmd/skillmaster/help_layer_test.go` 的 Go 测试里**，随 `go test ./...` 跑；`changed-paths.sh` 把 `skills/skillmaster-guide/` 也算作 CLI 改动，Guide 变了就触发。不新增第二种检查语言。
5. **评测仍是一次测量，不是每次提交的闸。** 题表有效性进 CI；`SKILLMASTER_HELP_EVAL=1` 的模型运行在“改帮助的 PR”上手动跑一次，结果写进 `test-plan.md`，不进 CI，避免把模型抖动变成必过检查。
6. **本版用只减不增的基线搬入未修的 Guide。** `testdata/help-guide-known-issues.json` 记下当前 Guide 的违规；检查要求“当前违规集合 == 基线”，新增一条就红，修掉一条必须从基线删。CLI 帮助的五处修复不在基线里，本版必须绿。

## 理由

**为什么不是“三份文档：参考 / 旅程 / 人向手册”。** 产品负责人的判断是：人基本不会从头读手册，Agent 才是主要读者。再维护一份人向手册，等于把同一批命令、同一套步骤写两遍；两遍必然漂，而这一次的教训正是“写两遍会漂”。Guide 本身就是那本手册，只是排版与措辞要按 Agent 的可读性来组织；人想读也能读。人向教程、截图与排版是**呈现形式**，不是新的事实源，将来需要时从 Guide 派生。

**为什么 Guide 的源要进仓库。** 只留在 registry 时，CLI 退役一个命令，review 看不到 Guide 还在教它，CI 也读不到线上内容；这次落后 6 天就是这个结构的结果。进仓库后，Guide 与 CLI 在同一条 PR 里，Go 测试能直接读源；退役词与命令路径都能在合并前变红。代价是仓库源与线上版本两份，需要一条手动的单向发布；这个代价比“没有 review、没有检查”小。本版先把源搬进来，内容修复与发布另开一轮。

**为什么不是整份 Guide 从声明生成。** 声明能生成“命令怎么用”，生成不了“什么时候该用哪个、做完看什么、错了先查哪里”。整份生成会把 Guide 变成第二份 `--help`，失去它作为手册的价值。可以生成或校验的是**语法形状**；任务判断必须手写。当前选择是“抽取校验”，不是“整份生成”。

**为什么检查全放 Go。** Guide 与命令树的比对只需要读 Markdown 与声明；列对齐与重复语义需要看**渲染后的文字**，只有 Go 的 `help.Render` 能做。两件事放在同一个 `go test` 包里，只有一套测试入口、一套失败输出；代价是 Guide 变化要触发 CLI 测试，`changed-paths.sh` 加一行就能解决。仓库的文档工具虽然已经是 Python，但那是生成与校验文档的工具，不是产品技术栈；这条检查没有再引入第二种语言。

**为什么先搬不先改。** 产品负责人要把“源进仓库、检查能跑”与“逐页改正文”分成两件事：前者是结构，后者需要对着真栈一页页走。搬进来的源会带着已知问题，所以用基线把它们逐条记下；基线是精确集合，新增一条就红，修掉一条必须删。这样“先搬”不会变成“先放行”，也不会把正文修复的评审压力压在这一版。

**为什么评测不进 CI。** 评测要调用真实模型，20 题 × 3 次；它适合回答“这次改动有没有让 Agent 更难找答案”，不适合做每次提交的必过门。题表是否有效是确定性的，进 CI；模型运行是测量，放在改帮助的 PR 上手动做，结果留在版本记录里。

## 后果

- **Guide 成为 Agent 优先的手册**：人向截图、寒暄与纯教程段落会逐步删掉或后置；人仍然能读，但不再以“从头读到尾的人”为默认读者。
- **本版线上 Guide 仍是旧版**：只搬源，不改正文，不发布；Agent 读到的还是 1.0.1 的退役命令与缺失入口。它们的清单在基线文件里，下一轮逐条清零；这是“先搬后改”的直接代价。
- **Guide 的修改多一步发布**：改仓库源不会自动改线上；要跑 `submit`、`check`、网页确认。忘记发布时，线上仍是旧版；下一次发布会补上，但**没有自动同步**。
- **命令语法改动会牵动 Guide 的任务行**：改 `help.Spec` 的用法时，Guide 里那一行命令可能也要改；Go 测试会在路径或 flag 失效时变红，不会静默放过。完整 flag 表不在 Guide 里，所以改动面比现在小。
- **Go 测试成为 CI 的必过检查**：`changed-paths.sh` 把 `skills/skillmaster-guide/` 也算作 CLI 改动，Guide 或声明改动都会触发 `go test ./...`；测试退出 1 时 PR 不能合并。
- **Guide 源放在 `skills/` 会触发站点打包**：`changed-paths.sh` 现在把 `skills/` 归到 `site`；Guide 是公开官方 skill，站点打包它没有坏处。若将来站点开始校验 skills 结构，再决定是否排除。
- **评测仍是人工触发**：主干不会每晚自动跑；指标的可比性依赖 test-plan 记录的口径（模型、运行次数、题表版本）。题表一变，旧数字不再可比。
- **没有新增主题的强制要求**：`secret` 是否需要一个 `help secret` 主题，由技术设计按 1 KB 的预算决定；本 ADR 只要求它有一个能被 Agent 找到的入口。
