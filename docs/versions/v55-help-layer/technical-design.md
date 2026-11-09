# v55-help-layer · 技术设计

> **最后更新**：2026-11-10
> **所属版本**：[`README.md`](README.md)

## 架构

### 两层与各自的源

| 层 | 回答什么 | 源 | 渲染 / 发布 |
|---|---|---|---|
| **单命令参考** | 这个命令怎么用、怎么失败、退出码是什么 | `skillmaster-cli/cmd/skillmaster/declarations*.go` 的 `help.Spec` | 渲染链与生成视图见 [`current/cli.md`](../../current/cli.md)（本版不改这条链） |
| **Guide 手册** | 我现在该做哪一步、做完看什么、错了先查哪里 | 新增 `skills/skillmaster-guide/`（从 `skillmaster/skillmaster-guide@1.0.1` 原样搬入） | 本版只进仓库，不发布；下一轮改正文后再由所有者发草稿并在官方账号点上线 |

`help workflow` / `help auth` / `help env` 是 Guide 的短入口，正文仍在 `declarations.go` 的 `commandHelp.topics`。入口 skill `skills/skillmaster/SKILL.md` 只负责把 Agent 送到 Guide；它不是第二份手册。

### 边界

- **命令语法、flag、失败表、退出码、`--json` 字段**只由 `help.Spec` 定义。Guide 可以写完成任务所需的命令行与用途，但不复制完整 flag 表与失败表；需要完整语法时指向 `skmr <命令> --help`。
- **任务顺序、判断、完成信号、出错先查哪里**只由 Guide 定义。`--help` 的 `Next` 可以指向下一条命令，但不解释整条旅程。
- **同一事实改一次只改一处**：语法改 `help.Spec`，旅程改 Guide。超过一处就是重复，必须进检查。

### 数据流

单命令参考现有的渲染与生成链见 [`current/cli.md`](../../current/cli.md)，本版不改它。下面只画本版新增的检查：

```
skills/skillmaster-guide/ + skills/skillmaster/SKILL.md
                    │
                    └──> help_layer_test.go <──commandHelp（命令树）
                              │
                              ├──> Guide 命令 / flag / 退役词 / 能力入口 / 基线
                              ├──> 主题页命令 / flag / 退役词
                              └──> 渲染页列对齐、重复语义、体积

go test ./...（cli.yml）跑全部检查
```

决策记录见 [ADR 0174](../../decisions/0174-two-help-layers.md)。

## 数据模型

本版不涉及数据库。检查器用到四份数据：

| 数据 | 来源 | 形状 |
|---|---|---|
| 命令树 | `commandHelp.Specs()`（Go） | `path → {summary, usage, flags, exits, json_fields}` |
| Guide 页面 | `skills/skillmaster-guide/**/*.md` 与 `skills/skillmaster/SKILL.md` | 文件路径、行号、代码块、正文中的 `skmr …` |
| 基线 | `skillmaster-cli/testdata/help-guide-known-issues.json`（新增） | `{"entries": [{"check", "file", "match", "note"}]}` |
| 评测题 | `skillmaster-cli/testdata/help-eval.json` | `{id, group, prompt, expected}` |

**没有第二份命令表。** Go 测试直接用 `commandHelp`，不解析 Go 源码，也不读生成的 `cli.md`。

**行尾必须固定。** Guide 被逐行解析；Windows 检出若把行尾改成 CRLF，行末的 flag 会带上 `\r`，检查在没人改过的文件上报未知 flag。`.gitattributes` 对 `skills/skillmaster-guide/**` 设 `-text`，与入口 skill 同一做法。

### 基线的判据

基线是**一个精确的集合**，不是上限：

- 当前违规的集合必须**等于**基线。新增一条 → 红（不许长）。
- 基线里有一条当前不再出现 → 红（修掉后必须删；删了又出现也会红）。
- 违规的身份是 `(check, file, match)`，不含行号；行移动不会让基线失效。
- 正常运行时只读基线；`go test ./cmd/skillmaster -run TestGuideKnownIssues -update-guide-baseline` 会重写基线，这是一次显式的、要评审的改动，不是自动放行。

本版基线预计包含：Guide 里的退役词（`@new`、`skill put`、`skill download`）、Guide 索引缺失的新能力入口（`draft` / `secret` / `run` / `notifications`）。**CLI 帮助的五处修复不进基线**——它们在本版修好，检查必须绿。

## 接口

### 新增 `skillmaster-cli/cmd/skillmaster/help_layer_test.go`

| 测试 | 读什么 | 判据 |
|---|---|---|
| `TestGuideCommandsAreDeclared` | Guide 源 + 入口 skill | 每个 `skmr …` 的最长命令路径都在 `commandHelp` 里；路径之后的下一个 token 若是裸词，视为不存在的子命令；每个 `--flag` 都在该命令的 `Flags ∪ Universal` 里 |
| `TestGuideRetiredWords` | 同上 | 收集 `@new` / `skill put` / `skill download` / `skill files` 的出现；与基线比较 |
| `TestGuideIndexMentionsCapabilities` | Guide `SKILL.md` | `draft create` / `skmr secret` / `skmr run` / `notifications` 各至少一处；缺失项与基线比较 |
| `TestGuideKnownIssues` | 上面三组违规 | 当前集合 == 基线集合；`-update-guide-baseline` 重写基线 |
| `TestHelpTopicsAreCurrent` | `help workflow/auth/env` 正文 | 退役词零出现；workflow 含 `draft create` / `secret set` / `notifications` / `run` |
| `TestHelpTopicsUseDeclaredCommands` | `help workflow/auth/env` 正文 | 从已知顶层名开始的命令路径与 `--flag` 都在命令树里；主流程另有固定短语清单 `TestWorkflowTopicNamesTheMainPath` |
| `TestUnauthenticatedFailureHasOneSentence` | 全部声明的凭据类失败 | `unauthenticated` 用统一的 Summary 与 Next；`token_*` 用同一个 Next |
| `TestTopLevelColumnsAlign` | `help.RenderTop(commandHelp)` | `命令：` 块内每行说明的起始列相同 |
| `TestGroupColumnsAlign` | `help.Render(commandHelp, path)` | 每个有子命令的分组页，子命令说明列对齐 |
| `TestNoDuplicateSemantics` | 渲染后的每一页 | `32 KiB` 每页 ≤1 次；`凭据不被接受` 零出现；`凭据不可用` 每页 ≤1 次 |
| `TestReadExamplesPinAVersion` | `skill read` / `cat` / `head` / `tail` 的 `Examples` | 每个例子的地址 token 含 `@`；取第一个非 flag 参数，不数固定位置 |
| `TestEvalTableTargetsLiveCommands` | `testdata/help-eval.json` | 恰好 20 题；每个 `expected` 是 `commandHelp` 的叶子（`PureGroup == false`）；id 与 expected 不重复；题面不含期望答案的命令名；无退役词；覆盖 `draft create` / `secret set` / `run` / `notifications accept` |
| `TestHelpBudgets` | 渲染输出 + Guide 索引 | 阈值见 [`prd.md`](prd.md) A9；测试逐页量字节 / 字符并与阈值比 |

`TestGuideKnownIssues` 的集合比较是**双向**的；实现时把基线写成排序后的数组，失败信息按 `check/file/match` 排序输出，便于对照。

### 现有测试的改动

- `render_conformance_test.go`：不改解析逻辑；`TestRenderedHelpAgreesWithItsDeclaration` 继续作为“渲染 vs 声明”的底。
- `helpfree_test.go`：`TestTheWorkflowTopicNamesTheLiveCommands` 保留，窄断言从只认 `files` 扩成四个退役词。它读的是**渲染后的** `help workflow`，Guide 源检查看不到这一页，所以两者都要留。
- `help_eval_test.go`：不改 harness；题表更新后重跑。它仍默认跳过，`SKILLMASTER_HELP_EVAL=1` 才跑。

### CLI 帮助的五处修复

| 位置 | 改动 |
|---|---|
| `declarations.go` 的 `topics["workflow"]` | 删 `@new` / `put` / `download`；补 `draft create` 与 `secret set` 的位置；`run` 段保留；总长不超 A9 的 workflow 阈值 |
| `declarations_skill.go` 的 `read` / `cat` / `head` / `tail` 例子 | 地址改成 `dev/pdf-tools@1.2.3`，保证复制可执行 |
| `internal/help/render.go` 的 `RenderTop` | 列宽按 `root.Children` 里最长的名字计算，不再写死 8 |
| `declarations_secret.go` 的 `secret set` `Failures` | `usage` 行同时覆盖缺参数与本地超限（本地超限是 usage，退出 2）；`credential_value_too_large` 只说服务端拒绝，不再重复 32 KiB |
| `internal/help/help.go` 的 `universalFailures` 与声明里的 `unauthenticated` | 统一成 `help.UnauthenticatedSummary` / `help.UnauthenticatedNext`（“凭据不可用” / “运行 skmr login，或到控制台换一把 API key”）；删掉“凭据不被接受”的变体 |

### 五处之外的相邻修正

审计在**同一批页面**上发现的近邻错误，一并修掉；它们不进 A5 的五条验收，但同一批测试覆盖：

| 位置 | 改动 |
|---|---|
| `skill check` 的 `Next` | 去掉退役的 `@new` / `--name` / `--from`；按是否已有草稿分两支：线上版本先 `draft create`，草稿直接 `skill edit` |
| `skill read` 的 `Output` | 写明裸地址读 `SKILL.md` 的例外：等于加载整份正文，必须先 `invoke` 或写版本 |
| `skill ls` 的 `Next` | 地址改成 `@<版本码>`，避免下一步读 `SKILL.md` 时撞上同一条拒绝 |
| `secret rotate` 的失败表 | 补本地超限的 `usage`（退出 2）；服务端的 `credential_value_too_large` 不再声称 32 KiB |
| `secret set` 的第二个例子 | 补上管道，复制即可执行 |
| `skill cat` 的例子 | 只留 `SKILL.md`，不再假设 `README.md` 存在 |
| `internal/help/registry.go` | `unauthenticated` 的文案改用统一常量 |

保持 20 题，删五加五：

- 删：`skill-files-draft`、`download-version`（退役）；`auth-status-source`（与 `auth-status-usable` 重复）；`category-clear`（与 `category-set` 成对，保留 set）；`recovery-missing-name`（题面引用退役的 `version rename` 与旧报错）。
- 加：`draft-create` → `draft create`；`secret-set` → `secret set`；`run-script` → `run`；`notifications-accept` → `notifications accept`；`recovery-overwrite-read` → `skill write`（题面引用 `read_proof.go` 的真实拒绝语，不泄露 `--overwrite`）。

题面不出现期望答案的命令名，保持现有写法。

### CI

- **`cli.yml`**：`go test ./...` 已经会跑上面的 Go 测试，不改 workflow。
- **`changed-paths.sh`**：`cli` 的匹配加 `skills/skillmaster-guide/|skills/skillmaster/`，让 Guide 或入口 skill 的变化也触发 CLI 测试。`skills/` 仍归 `site`，两个 workflow 都会跑；Guide 是公开官方 skill，站点打包它没有坏处。
- **评测**：题表有效性由 `TestEvalTableTargetsLiveCommands` 每次 PR 校验；模型运行不进 CI，改帮助的 PR 本地跑 3 轮，结果写进 `test-plan.md`。

## 分期

| 期 | 交付什么 | 验收 |
|---|---|---|
| P1 · 搬源与检查 | `skills/skillmaster-guide/` 的内容文件、`help_layer_test.go`、基线文件、`changed-paths.sh` 一行 | A2、A3、A4、A7；检查能在旧 Guide 上把已知问题收进基线 |
| P2 · CLI 帮助修复 | `workflow` 主题、四个例子、列宽、`secret set`、凭据说法 | A5、A9；CLI 侧的检查全绿，基线不含 CLI 违规 |
| P3 · 题表与收口 | 20 题题表、模型评测一次、文档重出、TODO 删条 | A6、A8、A10；结果写进 test-plan |

**三期内的工作放在同一个 PR 里按 P1→P2→P3 顺序做完**，不拆成三个 PR；每期完成时跑该期的测试，最后一期一起做 C4。本版不发布 CLI 正式版；CLI 改动走 beta 通道（[`cli-beta.yml`](../../../.github/workflows/cli-beta.yml)）。Guide 不发布。

## 开放问题

1. **基线的粒度够不够？** 当前用 `(check, file, match)`，不含行号；`match` 是整行原文加出问题的词，所以行移动不失效，但**同一行被改写一个字**会让旧条目“不再出现”、同时报一条新违规，需要重录。当前接受这个代价：条目只有几十条，且只减不增。如果将来条目变多，再改成按文件 + 出问题的词。
2. **`skills/skillmaster-guide/` 会不会和站点打包冲突？** `changed-paths.sh` 现在把 `skills/` 归到 `site`。当前倾向：保留，Guide 是公开官方 skill；若站点开始校验 skills 结构，再排除。
3. **四个例子的修复会不会牵动 golden？** `help` 的 golden 不录成功响应；例子只进 `TestReadExamplesPinAVersion` 与 `parseOnly`。已确认：`go test ./...` 全绿，golden 未受影响。
4. **模型评测这一版跑不跑？** 用户选了“题表进 CI，模型手动跑”。本版 CLI 只修了五处，跑一次记录基线即可；目标值留给内容轮。已执行：3 轮 18/18/17，首次找对率中位数 90%，调用中位数 5.0；结果见 [`test-plan.md`](test-plan.md) T11，不达标不算本版失败。
5. **Guide 索引的“新能力入口”以什么为准？** 当前倾向：只查 `SKILL.md` 索引里是否出现 `draft create` / `skmr secret` / `skmr run` / `notifications` 这四个串，不查具体页；缺失项进基线，下一轮补页时清零。
6. **检查认得的命令形状够不够？** 现在只解析以 `skmr` 开头的调用；同一行里的多个调用会逐个检查，但省略前缀的裸命令名（表格里的 `` `skill rm` ``）不在检查面内。主题页的命令检查从已知的顶层名开始，所以子命令或 flag 拼错会红，顶层名本身拼错（`secert set`）不会被当成命令；`TestWorkflowTopicNamesTheMainPath` 用固定的短语清单兜住主流程（`run <地址>`、`skill request`、`skill write / edit` 等），简写里的裸动词（`accept / decline` 的 `decline`）仍不单独检查。退役词也只认固定清单，裸 `put` / `download` 这种散文用法不抓。当前倾向：Guide 的代码块都写 `skmr`，清单由 ADR 0136 维护；若将来出现省略前缀的写法，再补一条规则。
