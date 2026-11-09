# v55-help-layer · 产品需求

> **最后更新**：2026-11-10
> **所属版本**：[`README.md`](README.md)

## 用户与场景

**谁在用**：把 `skmr` 当第一前端的 Agent，以及通过 CLI 创作、共享 skill 的作者。人不直接读 `--help`；Guide 是手册，读者主要是 Agent，人也可以读。本版不另维护一份人向手册。

**在什么场景下用**：

1. **第一次接触**：Agent 拿到 `skmr`，要从顶层帮助判断“我现在能做什么、下一步敲什么”。
2. **消费一个 skill**：搜索 → 加载 → 照正文做。
3. **创作或修改**：`skill create` / `draft create` → 先读 → `write` / `edit` → `diff` / `check` → 网页上线。
4. **协作与凭据**：共享、接受共享、申请与审批；登录、API key、服务凭据。
5. **出错恢复**：看到一条错误，能直接知道下一条命令。

**现在的替代做法是什么**：Agent 读源码、猜命令，或者回头问人。Guide 线上版停在 v44 之前，照它做会撞上退役命令；CLI 帮助的失败项写得不错，但成功之后经常没有下一步。Guide 只在 registry 里，CLI 改动时 review 看不到它。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | **定两层与唯一权威**：单命令参考（`--help`）与 Guide 手册（主要读者是 Agent，人也可以读；`help workflow/auth/env` 是手册的短入口）。同一事实只在一个层里写，其余只链接 | 新 ADR 0174 |
| 2 | **Guide 源进仓库**：把 `skillmaster/skillmaster-guide@1.0.1` 的内容文件原样搬进 `skills/skillmaster-guide/`，**不改正文** | 仓库内源目录 |
| 3 | **全 Go 检查**：退役词、Guide 命令路径与 flag、顶层与分组列对齐、同页重复语义、Token 预算、评测题表有效性、Guide 索引的新能力入口。全部写成 `skillmaster-cli/cmd/skillmaster` 的 Go 测试 | 新测试文件 |
| 4 | **只减不增的基线**：Guide 里当前已知的违规（退役命令、缺失的新能力入口）记进 `testdata/help-guide-known-issues.json`；新增违规让测试红；修掉一条必须从基线删，删了又出现也红 | 基线 JSON + 测试 |
| 5 | **修 CLI 帮助的五处**：`help workflow` 去掉 `@new` / `put` / `download` 并补 `secret`；`read` / `cat` / `head` / `tail` 四个例子改成可执行；顶层列宽按最长名字算；`secret set` 的 32 KiB 只留一处并拆开“缺 flag”；凭据失败统一成一句话 | `declarations*.go`、`internal/help/*.go` |
| 6 | **评测题表重新有效**：保持 20 题；去掉 `skill files` / `skill download` 两道退役题与两道重复题，替换一道引用旧报错的恢复题；补 `draft create`、`secret set`、`run`、`notifications accept` 四道 | `testdata/help-eval.json` |
| 7 | **CI 触发**：`skills/skillmaster-guide/` 也算 CLI 改动，Guide 变了就触发 `go test ./...` | `.github/scripts/changed-paths.sh` |
| 8 | **收口文档**：`docs/current/TODO.md` 删掉已开版的“CLI 帮助文档全局梳理”；生成视图重新生成 | 文档与生成视图 |

### 两层的重复边界

- **`--help`**：完整语法、flag 表、失败表、退出码、`--json` 字段。这一层是命令的权威。
- **Guide**：完成一个任务所需的命令行、它的用途、完成信号、出错先查哪里。完整 flag 与失败表不在 Guide 里重复，指向 `--help`。
- **判断重复的标准**：同一个语法改一次，要改几个地方？超过一个就是重复，必须进检查；能只改一处就不写第二份。

### 不做

| 不做 | 理由 | 什么时候可能做 |
|---|---|---|
| 改 Guide 正文 | 这一版只搬源、建检查、立基线；正文修复需要逐页对着真栈走，另开一轮 | 下一轮 |
| 发布 Guide | 正文没改，发布没有意义；线上仍是 1.0.1；发布由所有者发草稿并在官方账号点上线，留给内容轮 | 下一轮 |
| 单独维护一份人向手册 | Guide 就是手册，读者以 Agent 为主；人向教程、截图与人向排版以后若需要，从 Guide 派生 | 内容轮结束后再判断是否值得单列 |
| 改控制台与官网文案 | 它们不在 Agent 的 CLI 路径上 | 有对应需求时 |
| 新增 CLI 命令 | 本版只修帮助与检查，不改能力边界 | 各自开版 |
| 重做 Guide 的信息架构 | 现有结构是好的；本版只搬不改 | 结构真的不够用时 |
| 把整份 Guide 从声明自动生成 | 语法可以校验，旅程判断不能自动生成 | 技术设计若证明可行，再收窄 |
| 改退出码或 CLI 行为契约 | 退出码是公开契约（[ADR 0087](../../decisions/0087-cli-exit-codes-are-a-public-contract.md)）；本版只改帮助文字与检查 | 另开 ADR |
| 补“每一步都有下一步”的覆盖 | 它需要逐命令判断哪些是决策点，和 Guide 正文一起做更合适 | 下一轮 |
| 跑设计件检查、改 GUI | 本版没有界面改动，第 2 关不存在 | — |

## 拥有面

| 要动的文件 / 面 | 是不是共享面 | 谁在用 |
|---|---|---|
| `skills/skillmaster-guide/`（新增，31 个文件） | **是** | 官方 Guide 的源 |
| `skillmaster-cli/cmd/skillmaster/help_layer_test.go`（新增） | **是** | CLI 帮助测试 |
| `skillmaster-cli/testdata/help-guide-known-issues.json`（新增） | **是** | Guide 基线 |
| `skillmaster-cli/testdata/help-eval.json` | **是** | 帮助评测 |
| `skillmaster-cli/cmd/skillmaster/declarations*.go` | **是** | 全部命令的帮助与 `docs/current/cli.md` |
| `skillmaster-cli/internal/help/` | **是** | 全部帮助页 |
| `.github/scripts/changed-paths.sh` | **是** | 全部 CI 的路径分类 |
| `.gitattributes` | **是** | 全部检出的行尾；Guide 被逐行解析，不能带 `\r` |
| `docs/current/TODO.md` | **是** | 跨版本待办 |
| `docs/current/cli.md` | 生成 | 命令语法视图 |
| `docs/versions/v55-help-layer/`、`docs/decisions/0174-two-help-layers.md` | 否 | 本版 |

## 控制台操作与 CLI 对应

本版**不新增或改动控制台操作**；现有页面动作继续按 [ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 的例外处理。

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | 两层与唯一权威写进 ADR 0174；每类事实只有一个权威位置 | 读 ADR 与 §两层的重复边界，逐类指出权威 |
| A2 | Guide 的内容文件原样进仓库，正文零改动 | 与 `skillmaster/skillmaster-guide@1.0.1` 逐文件 `diff` |
| A3 | 全 Go 检查存在并随 `go test ./...` 跑：退役词、命令路径与 flag、列对齐、重复语义、Token 预算、题表有效性、Guide 能力入口 | `go test ./cmd/skillmaster` 全绿（不按名字筛，避免新检查被排除在外） |
| A4 | 基线只减不增：当前违规全部在基线里；新增违规让测试红；修掉一条而基线没删也红 | 注入一条新违规 → 红；从基线删一条未修复项 → 红 |
| A5 | CLI 帮助五处修好：`help workflow` 退役词 0 且含 `secret`；四个例子可执行；顶层列对齐；32 KiB 只出现一次；凭据失败只有一句话 | 渲染检查 + 逐条解析四个例子，确认不再被本地规则拒绝 |
| A6 | 评测题表 20 题、零退役题、覆盖四道新能力 | 跑题表检查；`len(tasks) == 20` |
| A7 | Guide 变化会触发 CLI 测试 | 只改一个 Guide 文件，`changed-paths.sh cli` 输出 `cli=true`；CI 上 `go test` 跑 |
| A8 | 仓库检查全绿：CLI `go test ./...`、`generate.py --check`、`check_docs.py`、`impact.py --check`；已开版的 TODO 条目已从 `docs/current/TODO.md` 删除 | 跑命令，并用 `grep` 确认条目不在 |
| A9 | Token 预算：顶层帮助 ≤2,048 字节；单页 ≤2,048 字节；`help workflow` ≤1,024 字节；Guide 索引 ≤3,000 字符；顶层 + workflow + Guide 索引 ≤5,000 字符 | 测试与量字符数 |
| A10 | CLI 帮助的五处修复有人读过渲染结果；Guide 只做结构搬移审查，正文审查留给下一轮 | 请人复验，记录结论 |

**成立条件**：A1、A2、A4 **不满足就不算交付**。A1 是结构本身；A2 是搬移的完整性；A4 是防漂移的闸。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| Guide 基线条数 | `help-guide-known-issues.json` 里的条目数 | 读 JSON | 记录当前值；只减不增 |
| CLI 退役词数 | `help workflow` 与四个例子里的退役命令出现次数 | 渲染检查 | 0 |
| 命令解析率 | Guide 与入口 skill 中 `skmr …` 命令路径能在命令树里找到的比例（基线内除外） | Go 测试 | 100%（基线外） |
| 题表退役题数 | `testdata/help-eval.json` 里指向退役命令的题数 | Go 测试 | 0 |
| 题表新能力覆盖 | `draft create` / `secret set` / `run` / `notifications accept` 各至少一道 | Go 测试 | 4/4 |
| 入口字符数 | 顶层帮助 + `help workflow` + Guide 索引的字符数之和 | 量渲染输出 | 同 A9 |
| 首次找对率 | 20 道“任务 → 期望命令”中答对的比例；模型只许跑 `--help`；3 轮各算一次，取 3 轮的中位数登记 | 手动跑 3 轮，按 `test-plan.md` T11 的口径 | 本版记录基线；内容轮目标 ≥80% |
| 调用中位数 | 从任务开始到作答之间执行的 `--help` 次数；每轮取 20 题的中位数，再取 3 轮的中位数登记 | 同上 | 本版记录基线；内容轮目标 ≤4 |

## 依赖与前置

- **外部依赖**：Guide 1.0.1 的源（已取到本机）；A3 需要 Go 工具链；模型评测需要 `claude` 与评测模型。
- **内部前提**：[ADR 0087](../../decisions/0087-cli-exit-codes-are-a-public-contract.md)（退出码）、[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)（控制台与 CLI）、[ADR 0135](../../decisions/0135-a-draft-may-be-copied-from-a-version.md)（建草稿是自己的命令）、[ADR 0136](../../decisions/0136-cli-file-verbs-follow-intent.md)（读写按意图分，覆盖前先读）、v16 帮助契约。本版新增 ADR 0174 记两层与唯一权威。
- **排除了什么前置**：不需要数据库迁移、服务端接口改动、控制台发布、Guide 发布权限；这些都不在本版验收路径上。
