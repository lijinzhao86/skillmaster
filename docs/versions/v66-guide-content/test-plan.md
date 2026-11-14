# v66-guide-content · 测试方案

> **最后更新**：2026-11-14
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 退役教学词清零 | T1 | 通过 |
| #2 四个必需入口 | T2 | 通过 |
| #3 命令与 flag 都声明 | T3 | 通过 |
| #4 相对链接可解析 | T4 | 通过 |
| #5 索引与入口预算 | T5 | 通过 |
| #6 评测路由 / 作答 ≥90% | T6 | 通过 |
| #7 每页都有评测题 | T7 | 通过 |
| #8 单篇 ≤100 行 | T8 | 通过 |
| #9 CLI 与文档检查全绿 | T9、T10 | 通过 |

## 用例

### T1 · 退役教学词清零

- **前置**：`main` 的 Guide 源已搬入工作区。
- **步骤**：`cd skillmaster-cli && go test ./cmd/skillmaster -run 'TestGuideRetiredWords|TestGuideKnownIssues' -count=1`
- **期望**：两条都通过；`testdata/help-guide-known-issues.json` 的 `entries` 为空。
- **判定**：退出码 0。

### T2 · 索引里有四个必需入口

- **前置**：`SKILL.md` 已重写。
- **步骤**：`go test ./cmd/skillmaster -run TestGuideIndexMentionsCapabilities -count=1`
- **期望**：索引文本包含 `draft create`、`skmr secret`、`skmr run`、`notifications`。
- **判定**：退出码 0。

### T3 · 命令路径与 flag 都已声明

- **前置**：同上。
- **步骤**：`go test ./cmd/skillmaster -run 'TestGuideCommandsAreDeclared|TestGuideKnownIssues' -count=1`
- **期望**：Guide 里每个 `skmr …` 都在命令树里，且 flag 都有声明；未知命令与未声明 flag 的集合为空。
- **判定**：退出码 0。

### T4 · 相对链接可解析

- **前置**：新增页面已落盘。
- **步骤**：`python3 scripts/guide_links.py`（可达性、相对链接、行数、索引预算），再跑 `python3 .claude/skills/docs-architecture/scripts/check_docs.py`。
- **期望**：Guide 内 0 条断链；`check_docs.py` 退出 0（含版本四件套齐全）。
- **判定**：两条都为 0。

### T5 · 索引与入口预算

- **前置**：同上。
- **步骤**：`go test ./cmd/skillmaster -run TestHelpBudgets -count=1`
- **期望**：`SKILL.md` ≤3000 字、≤150 行；顶层帮助 + `help workflow` + 索引合计 ≤5000 字。
- **判定**：退出码 0。

### T6 · Guide 评测

- **前置**：`claude` 可执行文件在 PATH；模型可调用；`testdata/guide-eval.json` 通过 T7。
- **步骤**：

  ```bash
  SKILLMASTER_GUIDE_EVAL=1 SKILLMASTER_GUIDE_EVAL_OUT=/tmp/guide-eval.json \
    go test ./cmd/skillmaster -run 'TestGuideEval$' -count=1 -v
  ```

- **期望**：`route_rate` 与 `answer_rate` 都 ≥90%。
- **判定**：退出码 0；结果 JSON 留档。

### T7 · 评测题表有效

- **前置**：`guide-eval.json` 已写。
- **步骤**：`go test ./cmd/skillmaster -run 'TestGuideEvalTableIsValid|TestGuideEvalScoring' -count=1`
- **期望**：42 题；每个 Guide 页面至少被覆盖一次；id 唯一且是 kebab-case；题面不泄露篇目或 `must`/`any` 词；每个 `must` 与 `any` 都出现在目标页，且至少一个不在索引里；`must` 里的命令都已声明、且是叶子。
- **判定**：退出码 0。

### T8 · 单篇行数

- **前置**：内容定稿。
- **步骤**：统计 `skills/skillmaster-guide/**/*.md` 的行数。
- **期望**：除 `SKILL.md`（索引，上限 150）外，每篇 ≤100 行。
- **判定**：无超限文件。

### T9 · CLI 与文档检查

- **前置**：工作区干净。
- **步骤**：

  ```bash
  cd skillmaster-cli && go test ./...
  cd .. && node scripts/check-file-size.mjs
  python3 scripts/docs/impact.py --check
  python3 scripts/docs/generate.py --check
  python3 .claude/skills/docs-architecture/scripts/check_docs.py
  ```

- **期望**：全部退出 0；`generate.py --check` 不报漂移。
- **判定**：全部退出 0。

### T10 · 一键回归

- **前置**：T1–T9 的命令都在本机可用。
- **步骤**：`scripts/guide-regression.sh`（全套）或 `scripts/guide-regression.sh --fast`（跳过模型评测）。
- **期望**：六步全绿；退出 0。以后改 Guide 都跑这一条。
- **判定**：退出码 0。

## 环境与数据

- **环境怎么造**：本机 Go 1.26+；`claude` 在 PATH；网络可调模型。评测在 `t.TempDir()` 里跑，工作目录只有 `guide/`（由 `skills/skillmaster-guide/` 复制），`--bare` 跳过 CLAUDE.md / hooks / skills / memory。
- **数据**：
  - 题表 `skillmaster-cli/testdata/guide-eval.json`，42 题，一题一句用户原话。
  - 模型 `deepseek-v4-flash`；每题最多读 4 个不同文件（重复读同一文件不另计；列目录不算文件，也不占额度）；每题 1 次；只允许 Read（`--restricted --tools Read`）。
  - **先读索引才算路由**：每题都要求先读 `guide/SKILL.md`；直接打开篇目算猜中，不计入路由。
  - 「首次读对」跳过 `SKILL.md`：每次都必须先读索引，索引本身不算一次尝试；列目录（读一个不以 `.md` 结尾的路径）也不算。
  - **三个率都设门槛**：route、answer、pass 各 ≥90%；pass = 路由 + 作答 + 无违规。
  - 评测结果写到 `SKILLMASTER_GUIDE_EVAL_OUT` 指定的文件（本机用 `/tmp/`，不入库）。
- **清理**：`t.TempDir()` 自动删；`/tmp/guide-eval*.json` 是临时产物，不提交。

## 结果

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `TestGuideRetiredWords` / `TestGuideKnownIssues` ok；基线 `{"entries": []}` |
| T2 | 通过 | `TestGuideIndexMentionsCapabilities` ok |
| T3 | 通过 | `TestGuideCommandsAreDeclared` ok |
| T4 | 通过 | 本地扫描 0 条断链；`check_docs.py` 退出 0 |
| T5 | 通过 | `TestHelpBudgets` ok；索引 2995 字、102 行 |
| T6 | 通过 | 42/42 路由 = 100%，42/42 作答 = 100%，42/42 通过 = 100%；首次读对 42/42（模型 `deepseek-v4-flash`，1 次，读上限 4 个不同文件；`--restricted --tools Read` 下重跑） |
| T7 | 通过 | `TestGuideEvalTableIsValid` / `TestGuideEvalScoring` ok |
| T8 | 通过 | 42 个文件、1943 行；正文最长 97 行；`SKILL.md` 102 行（索引上限 150） |
| T9 | 通过 | `go test ./...` 全绿；`impact.py --check` 退出 0；`generate.py --check` 无漂移；`check-file-size.mjs` 通过 |
| T10 | 通过 | `scripts/guide-regression.sh` 六步全绿 |

**T6 的运行间波动**：同一版多次运行里，路由稳定在 100%；作答出现过 95.1%、98% 与 100%，差异来自模型的同义改写。`must` 已按「页面里出现、模型会照抄」校准；单次运行只够看系统性缺口，跨版本比较按技术设计 Q1 跑 3 次取中位数。

## 已知问题

### 1 · 纯 flag 的 `must` 不校验是否已声明

`skillmaster-cli/cmd/skillmaster/guide_eval_test.go` · **接受**

题表里以 `skmr ` 开头的 `must` 会核对命令是否在命令树里、是否是叶子；像 `--role maintain`、`--from` 这样只写 flag 的 `must` 不校验。当前两条都已声明，未造成漏判；以后新增时人工核一眼。

### 2 · 关键词评分不区分否定

`skillmaster-cli/cmd/skillmaster/guide_eval_test.go` · **接受**

作答判定是「必须词是否出现」，回答里写「不要用 `skmr skill mv`」也会命中。要彻底解决得给每道题加否定短语，收益低于维护成本；当前的题面都问「怎么做」，不鼓励否定式回答。

### 3 · 合法任务可能读到第 5 个文件

`skillmaster-cli/cmd/skillmaster/guide_eval_test.go` · **部分复现**

`use-copy` 一题中 agent 先查许可、再查复制、再看可见性，有一次读了 5 个不同文件，超过每题 4 个的上限，被判违规；另几次没有触发。处理：先保留上限 4（索引 + 1–2 篇是正常路径）；按技术设计 Q3 观察 3 次运行的违规率，超过 10% 再评估。
