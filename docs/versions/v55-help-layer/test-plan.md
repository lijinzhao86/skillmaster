# v55-help-layer · 测试方案

> **最后更新**：2026-11-10
> **状态**：实现完成；T2–T9、T11 已通过并记录，T1 / T10 等人工复验。

## 验收映射

| 验收 | 用例 | 备注 |
|---|---|---|
| A1 两层与唯一权威 | T1 | 读 ADR 与 §两层的重复边界，逐类指出权威 |
| A2 Guide 源原样进仓库 | T2 | 与线上 1.0.1 逐文件比对 |
| A3 全 Go 检查 | T3 | `go test` 里七组检查 |
| A4 基线只减不增 | T4 | 注入新违规 → 红；删基线未修项 → 红 |
| A5 CLI 帮助五处 | T5 | 渲染检查 + 例子解析 |
| A6 题表 20 题、零退役、覆盖新能力 | T6 | 题表测试 |
| A7 Guide 变化触发 CLI 测试 | T7 | `changed-paths.sh cli` 输出 true |
| A8 仓库检查全绿 | T8 | CLI、文档、生成视图 |
| A9 Token 预算 | T9 | `TestHelpBudgets` + 量字符 |
| A10 有人读过渲染结果 | T10 | CLI 五处 + Guide 源搬移审查 |
| 指标：首次找对率 / 调用中位数 | T11 | 模型手动跑 3 轮，记录基线 |

## 用例

### T1 · 两层与唯一权威

**前置**：ADR 0173、`prd.md` §两层的重复边界已写。
**步骤**：逐类列出“命令语法 / flag / 失败 / 退出码 / 版本规则 / 凭据规则 / 任务顺序 / 完成信号 / 出错处理”，指出它在哪一层、哪个文件；检查另一层是否只链接。
**期望**：每类只有一个权威；Guide 不含完整 flag 表与失败表；`--help` 不含任务顺序与“什么时候用哪个”的整段判断。

### T2 · Guide 源原样进仓库

**前置**：`skills/skillmaster-guide/` 已就位；本机已登录。
**步骤**：
```bash
skmr skill get skillmaster/skillmaster-guide@1.0.1 --all --into /tmp/guide-live
diff -r --exclude=.skillmaster /tmp/guide-live skills/skillmaster-guide
```
**期望**：`diff` 无输出；文件数 31；内容零改动。`--all` 与 `--overwrite` 不能同时给（`get.go` 拒绝这对组合，退出 2）；目录已存在时先换一个空目录，或删掉它再取。若线上在比对前被改过，先记下 `version list` 的时间，再重取。

### T3 · 全 Go 检查

**前置**：`help_layer_test.go` 与基线文件已落。
**步骤**：`cd skillmaster-cli && go test ./cmd/skillmaster -count=1`。
**期望**：全绿。不按测试名筛：筛名字会漏掉新加的检查，而漏掉的那条不会报错。测试名与判据见 [`technical-design.md`](technical-design.md) §接口。

### T4 · 基线只减不增

**前置**：T3 绿。
**步骤**：
1. 在 `skills/skillmaster-guide/author/edit.md` 临时加一行 `skmr skill frobnicate <地址>`，跑 T3；
2. 撤回，改在基线文件里删掉一条仍存在的 `retired_word` 项，跑 T3；
3. 撤回，跑 T3。
**期望**：1 红，报未知命令不在基线；2 红，报基线项在当前源里仍存在；3 绿。基线不是“上限”，是精确集合。

### T5 · CLI 帮助五处

**前置**：`declarations*.go`、`render.go`、`help.go` 的改动已落。
**步骤**：
1. `/tmp/skmr-audit help workflow`（或 `go run ./cmd/skillmaster help workflow`）检查退役词与 `secret`；`help auth` / `help env` 也要看；
2. `skmr skill read --help` / `cat` / `head` / `tail`，看四个例子的地址都带 `@`；
3. `skmr --help` 看 `notifications` 行的说明与其余行对齐；
4. `skmr secret set --help`，数 `32 KiB` 出现次数；
5. 任意一个 `unauthenticated` 页面，看凭据失败只有一句话。
**期望**：三个主题退役词 0、workflow 含 `secret`；四个例子可解析且不再被“先 invoke”的本地规则拒绝；顶层列对齐；`32 KiB` 1 次；`凭据不被接受` 0 次。

### T6 · 题表有效性

**前置**：`testdata/help-eval.json` 已改。
**步骤**：`cd skillmaster-cli && go test -run TestEvalTableTargetsLiveCommands ./cmd/skillmaster -count=1`。
**期望**：恰好 20 题；每个 `expected` 是叶子命令；id 与 expected 都不重复；题面不含期望答案的命令名；无 `skill files` / `skill download`；覆盖 `draft create` / `secret set` / `run` / `notifications accept`。

### T7 · Guide 变化触发 CLI 测试

**前置**：`changed-paths.sh` 已改。
**步骤**：
```bash
printf 'skills/skillmaster-guide/SKILL.md\n' | bash .github/scripts/changed-paths.sh cli
printf 'skills/skillmaster/SKILL.md\n' | bash .github/scripts/changed-paths.sh cli
printf 'docs/README.md\n' | bash .github/scripts/changed-paths.sh cli
```
**期望**：前两条 `cli=true`；第三条 `cli=false`。

### T8 · 仓库检查

**前置**：实现完成。
**步骤**：
```bash
cd skillmaster-cli && go test ./...
cd .. && python3 scripts/docs/generate.py --check
python3 .claude/skills/docs-architecture/scripts/check_docs.py
python3 scripts/docs/impact.py --check
! grep -q 'CLI 帮助文档全局梳理' docs/current/TODO.md
```
**期望**：全部退出 0；`generate.py` 重出后 `git status` 对生成物干净；TODO 条目已删（`grep -q` 没匹配时返回 1，所以取反）。

### T9 · Token 预算

**前置**：帮助与 Guide 源已就位。
**步骤**：`cd skillmaster-cli && go test -run TestHelpBudgets ./cmd/skillmaster -count=1`；另量 `skills/skillmaster-guide/SKILL.md` 的字符数。
**期望**：全部不超 [`prd.md`](prd.md) A9 的五条阈值。

### T10 · 有人读过

**前置**：T8 绿。
**步骤**：
1. 请人看 `skmr help workflow`、四个例子、顶层帮助、`secret set`、一个凭据失败页；
2. 请人看 `skills/skillmaster-guide/` 的目录与 `SKILL.md` 索引，确认“只是搬移、没有改正文”。
**期望**：五处修复读起来是当前命令；Guide 源能对上线上 1.0.1。结论写进 §结果，不写“看起来没问题”。

### T11 · 模型评测（记录基线）

**前置**：T6 绿；本机 `claude` 可用。
**步骤**：
```bash
cd skillmaster-cli
SKILLMASTER_HELP_EVAL=1 SKILLMASTER_HELP_EVAL_OUT=/tmp/v55-help-eval-final.json \
  go test -count=1 -run TestHelpEval ./cmd/skillmaster -v
```
**期望**：跑 3 轮，记录首次找对率与调用中位数；本版**只记录基线**，不把 ≥80% / ≤4 当成本版门槛（内容轮才修 Guide 正文）。模型不可调用时记“未验证”。口径见 §环境与数据。

## 环境与数据

- **Guide 源**：`skmr skill get skillmaster/skillmaster-guide@1.0.1 --all --into <空目录>`；需要登录。比对时排除 `.skillmaster/` 旁车。
- **Go 测试**：`skillmaster-cli/`，Go 工具链；不需要服务端、数据库或凭据。**本机 shell 可能带着 `SKILLMASTER_AUTO_UPDATE=0`**；v25 的三个 updater 测试会因此失败。那不是本版的回归，跑全量时用 `env -u SKILLMASTER_AUTO_UPDATE go test ./...`，或在 CI 里（没有这个变量）跑。
- **文档检查**：仓库根，Python 3（仓库已有的 `scripts/docs` 与校验器）。
- **模型评测**：本机 `claude` + `deepseek-v4-flash`；不需要 SkillMaster 服务端。结果文件留在 `/tmp`，不提交。**口径**：每题只把 `prompt` 交给模型，允许它执行的只有 `skmr … --help`（以及 `skmr help <主题>`；`-h` 也算一次调用），单题上限 8 次，超过上限记 8 次并判错；最终答案必须是一行、以 `skmr ` 开头，答出 `expected` 的完整路径才算对；**首次找对率**是 20 题中答对的比例，每轮算一次，登记 3 轮的中位数；**调用中位数**是每题从开始到作答执行的 `--help` 次数，每轮取 20 题的中位数，再登记 3 轮的中位数。执行了非 `--help` 命令，该题记为错。harness 机制（提示词模板、8 次上限、一行作答、3 轮、判分）沿用 [`v16 test-plan`](../v16-cli-help/test-plan.md) 的 eval harness（T25），命令前缀按 v29 改成 `skmr`；题目表是本版自己的 20 题，v16 的分组数量要求不适用于本版。
- **清理**：临时目录 `/tmp/guide-live`、`/tmp/v55-help-eval-final.json` 不提交；仓库内只留基线、题表、测试与 Guide 源。

## 结果

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 未执行 | 等 C4 所有者读 ADR 与 §两层的重复边界 |
| T2 | 通过 | `diff -r --exclude=.skillmaster` 无输出；31 个文件；线上 1.0.1 |
| T3 | 通过 | `go test ./cmd/skillmaster` 全绿 |
| T4 | 通过 | 注入 `skmr skill frobnicate` → 红（30 vs 29）；删一条基线 → 红（29 vs 28）；恢复 → 绿 |
| T5 | 通过 | 顶层列对齐；三个主题退役词 0、`help workflow` 含 `secret`；`32 KiB` 1 次；四个例子都带 `@1.2.3` |
| T6 | 通过 | 20 题；id 与 expected 无重复；题面不泄露答案命令；零退役题；覆盖 `draft create` / `secret set` / `run` / `notifications accept` |
| T7 | 通过 | `changed-paths.sh cli`：Guide 与入口 skill 为 `true`，`docs/` 为 `false` |
| T8 | 通过 | `env -u SKILLMASTER_AUTO_UPDATE go test ./...` 全绿；`generate.py --check` / `check_docs.py` / `impact.py --check` 均退出 0；TODO 条目 grep 无输出 |
| T9 | 通过 | `TestHelpBudgets` 全绿；顶层实测 1,053 字节，其余四项与 A9 的五条阈值比对均未超 |
| T10 | 未执行 | 等 C4 所有者看渲染与 Guide 源搬移 |
| T11 | 通过（记录基线） | 最终题表与最终帮助文字：3 轮 18/20、18/20、17/20 → 首次找对率取中位数 90%；`--help` 调用中位数 5.0。两者都只作基线：≥80% / ≤4 是内容轮目标，不是本版门槛 |

## 已知问题

- **Guide 正文的机器可检问题见基线文件** `skillmaster-cli/testdata/help-guide-known-issues.json`：退役命令、缺失的新能力入口。本版不修，下一轮清零。
- **基线抓不到的语义问题也留到下一轮**：首次上线缺“先读后写”、协作只写邀请人不写接收人/审批人、Guide 里没有 `run` / `secret` 页。这些不是“检查漏了”，是内容轮的正文工作。
- **CLI 里两处相邻的下一步没在本版修**：`skill create` 的 `Output` 与 `draft create` 的 `Next` 还没写“先 `skill read` 再 `write --overwrite`”；`skill invoke` 的 `Next` 仍是 `skill read`。它们不在用户确认的五处里，留到内容轮。
- 本版没有首次上线的真栈走查；Guide 正文不在本版修复范围，首次上线的修复与验证在下一轮。
