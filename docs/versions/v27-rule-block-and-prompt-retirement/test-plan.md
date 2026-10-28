# v27 · 测试方案

> **最后更新**：2026-10-28 · **所属版本**：[v27](README.md)

## 验收映射

| prd 验收 | 用例 |
|---|---|
| 1 默认是查 | T1 |
| 2 正文没有标记 | T2 |
| 3 四个命令退役 | T3 |
| 4 命令面收窄 | T4 |
| 5 `startup` 不带参数 | T5 |
| 6 `startup` 仍认得旧记录 | T6 |
| 7 审批地址是草稿页 | T7 |
| 8 首页那句话 | T8 |

## 用例

| # | 前置 | 步骤 | 期望 |
|---|---|---|---|
| T1 | — | 读 `skillmaster-cli/internal/prompt/block.md`；`skillmaster prompt print` | 含「除了闲聊」「任何任务都先查 skill」「不判断「拿不拿得准」」「两次都没有才自己做」；不含「拿不准就查」这类条件式说法；文件与二进制里的正文逐字节一致（`TestThePrintedBlockIsTheFileOnDisk`） |
| T2 | — | 断言 `prompt print` 的输出 | 不含 `<!--`；不以空行开头；结尾恰好一个 `\n`（不是两个） |
| T3 | 一个已含正文的用户文件 | 依次跑 `prompt install --file <文件> --yes`、`prompt status`、`prompt update --yes`、`prompt uninstall --file <文件> --yes` | 四条都**恰好**退出 2；stderr 含「已经退役」「skillmaster prompt print」「skillmaster startup」，stdout 为空；文件字节不变；不新建任何文件（含 `prompt.json`） |
| T4 | — | `skillmaster prompt --help`；`cd skillmaster-cli && go test ./cmd/skillmaster -run TestDeclarationsMatchTheCommandTree` | 子命令只有 `print`；可执行路径 61 条（v26 是 65） |
| T5 | — | `startup --plan`、`startup --agent codex`、`startup --file <路径>` | 三条都退出 2，输出点名被拒的那个参数 |
| T6 | 已登录，`prompt.json` 里四条记录（claude、codex、重复的 claude、一条无标签的 v25 记录） | `startup`；单独调 `promptRecordLine()` | stdout 是旅程正文；stderr 出现「这台电脑上写过这段说明的 Agent：claude、codex、未命名」；重复标签只出现一次；不出现任何路径 |
| T7 | 假服务端返回一份草稿 | `skill submit` | 「审批地址」= `{web}/skills/{ns}/{name}?tab=versions&version={草稿码}&sub=actions` |
| T8 | — | `node design/scripts/home-check.mjs "design/skillmaster-首页演示.html" <出图目录>`；`npx vitest run tests/home-page.test.tsx tests/session.test.tsx` | 检查里那条指令句是「根据 https://www.skillmasterai.com/install/skillmaster.md 安装 SkillMaster。」；站点两条测试断言同一句 |

## 环境与数据

- **CLI**：`cd skillmaster-cli && go test ./...`（14 个包；仓库根目录没有 `go.mod`，在根目录跑会报 `directory prefix . does not contain main module`）。许多包测试用本地 `httptest` 假服务端，不出网；会碰配置目录的用例用 `isolateConfig` 把 HOME 与配置目录指到临时目录。
- **夹具**：`prompt.json` 的 v2 记录（含一条无标签的 v25 记录）；一个已含正文的用户文件；首页出图目录随便一个空目录（`home-check` 会把截图写进去）。
- **站点**：`cd skillmaster-site && npx vitest run tests/home-page.test.tsx tests/session.test.tsx`。

## 结果

2026-10-28，同分支实测（`go test ./...` 14 包全过；`home-check` 报告 `failed: 0`、`problems: []`；`check_docs.py` 退出 0）：

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `TestTheBlockIsTheApprovedText`（含「不含『拿不准就查』」）、`TestThePrintedBlockIsTheFileOnDisk`（磁盘上的 `block.md` 与二进制里的一致）；`wc -l internal/prompt/block.md` = 9 |
| T2 | 通过 | `TestTheBlockStartsAndEndsCleanly`（首字节不是 `\n`、末尾不是 `\n\n`）+ `TestTheBlockIsTheApprovedText`（不含 `<!--`） |
| T3 | 通过 | `TestTheMarkerCommandsAreRetired`（四条各断言 `status == 2`、三处文案、目标文件字节前后相同、没建 `AGENTS.md`、也没写 `prompt.json`） |
| T4 | 通过 | `skillmaster prompt --help` 只列 `print`；`TestDeclarationsMatchTheCommandTree`（61 条） |
| T5 | 通过 | `TestStartupTakesNoArguments`（三条参数各断言退出 2 且输出点名该参数） |
| T6 | 通过 | `TestStartupPrintsTheRecordedAIsOnStderr`、`TestPromptRecordLineNamesEachConfiguredAI` |
| T7 | 通过 | `TestTheDraftApprovalLinkNamesTheDraftAndItsActionsTab` |
| T8 | 通过 | `home-check`（`failed: 0`、`problems: []`）；站点 17 条通过 |

**全量回归**：`go test ./...` 14 个包全过；`gofmt -l` 无输出；`go vet ./...` 无输出。

## 已知问题

| # | 问题 | 状态 |
|---|---|---|
| 1 | `prompt` 组退役后，CLI 不能替用户更新或撤回这段说明；要更新得重跑 `startup`，要撤回由用户删那几行 | 设计如此（见 [technical-design.md](technical-design.md) §开放问题 1），不算未修缺陷 |
| 2 | 用户文件里遗留的旧标记段不会被 CLI 自动清理；写说明那一步由 agent 整段替换（`memory/02-write.md` 规定） | 设计如此（[ADR 0110](../../decisions/0110-the-block-is-a-paragraph-the-cli-stops-managing.md)） |
| 3 | `startup --plan` 删除后，`startup` 不再检查用户文件的路径（目录、父目录、主目录、项目级警告） | 设计如此（[ADR 0111](../../decisions/0111-startup-takes-no-flags.md)） |
| 4 | v26 的文档仍以 `prompt install/update/uninstall`、`startup --plan` 为主语（PRD 做 #3/#4/#5/#6/#13/#14、验收 3–7 与 11–14、「控制台操作与 CLI 对应」表第一行；TD 的接口、数据流与 P0/P1；test-plan 的 T03–T09、T11–T17） | **已复现**：这些条目描述的是 v26 交付时的样子；v26 的三份文档顶部各加了一段指向本版的说明；除了「小工具」改称「CLI」与几处指向本版的补句，条目本身不改写。读 v26 文档的人需要知道「那些命令已经退役」 |
