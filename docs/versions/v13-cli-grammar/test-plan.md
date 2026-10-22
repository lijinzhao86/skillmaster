# v13-cli-grammar · 测试方案

> **最后更新**：2026-10-22
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 顶层 8 个命令，`help` 全列出 | T1 | 通过 |
| #2 `skills` / `skill` 分组与动词表 | T2、T16 | 通过 |
| #3 旧 `skill list` / `search` 不可用、不转发 | T3 | 通过 |
| #4 `skill files` 正名、`skill ls` 不可用、新输出格式 | T4 | 通过 |
| #5 `version` 正式 7 动作、7 个拒绝型、无 `create` | T5、T7 | 通过 |
| #6 `version list --all` / `--limit` / 无 `--cursor` / 无游标 | T6 | 通过 |
| #7 七个页面动作交接、stderr、非 0 | T7 | 通过 |
| #8 `skill versions` / `version list` 帮助内容与互相指 | T8 | 通过 |
| #9 网关 v9、`setup` 8→9、正文占位符替换 | T9 | 通过 |
| #10 `go test ./...`、`check_docs.py`、ADR、QA 文档 | T10 | 通过 |
| #11 当前文档不再教旧命令；历史/ADR/QA 排除 | T11 | 通过 |
| #12 无服务端代码改动；仅明文例外 | T12 | 通过 |
| #13 输出流、卡片、`--json`、拒绝 `--json`、退出码 | T13 | 通过 |
| #14 分页契约与既有例外 | T14 | 通过 |
| #15 `version discard` 确认；页面动作 | T15、T7 | 通过 |
| #16 凭据、401/403、无浏览器 | T16 | 通过 |
| #17 帮助契约：8 项、公开动词、usage/目的/副作用 | T17、T1、T2、T8 | 通过 |

## 用例

### T1 · 顶层帮助正好 8 个入口

- **前置**：无。
- **步骤**：运行 `skillmaster help`；捕获 stdout / stderr / 退出码。
- **期望**：stderr 列出 `login` / `logout` / `auth` / `setup` / `skills` / `skill` / `version` / `help`；退出 0；stdout 为空。
- **判定**：8 个名字齐全、无多余顶层命令。

### T2 · `skill` / `skills` 分组与动词表

- **前置**：无。
- **步骤**：分别运行 `skillmaster skill help`、`skillmaster skills help`。
- **期望**：`skill help` 的动词表正好是 PRD §1 的 20 个 + `help`，不含 `list` / `search` / `ls`；`skills help` 只有 `list` / `search` / `help`。
- **判定**：逐项对照 PRD §1。

### T3 · 旧命令是未知命令，不转发

- **前置**：临时 home；无服务端请求。
- **步骤**：分别运行 `skillmaster skill list`、`skillmaster skill search x`、`skillmaster skill ls`。
- **期望**：三者都退出非 0；stdout 为空；stderr 是“未知的 skill 子命令” + `skillUsage`；不产生集合列表输出。
- **判定**：子进程捕获 stdout / stderr / 退出码。

### T4 · `skill files` 正名与新输出格式

- **前置**：假服务端返回带 `SKILL.md`、二进制文件的 detail；临时 home。
- **步骤**：运行 `skillmaster skill files dev/x`；再运行 `skillmaster skill files`（无参）；再运行 `skillmaster skill ls dev/x`。
- **期望**：`files` 输出每个文件一行 relpath，正文/二进制提示缩进，文件清单后给 `read` 命令，版本说明行在 stdout 最后；`files` 无参打印 `skill files` 用法；`skill ls` 未知命令。
- **判定**：输出逐行断言；不留 `sha256:` / `/api/v1/` 在文件条目里；`read` 命令与版本说明行可带 pin。

### T5 · `version` 正式动作与拒绝型动词

- **前置**：无。
- **步骤**：运行 `skillmaster version help`、`skillmaster version create`；检查 `version help`。
- **期望**：正式动作只有 `list` / `show` / `diff` / `rename` / `note` / `discard` / `open`；七个页面动作不在动词表；`version create` 未知。
- **判定**：帮助文本 + 负向调用。

### T6 · `version list --all` 与游标收口

- **前置**：假服务端两页，第一页 `next=c2`，第二页 `next=null`；`--status draft` / `--query` 过滤。
- **步骤**：`version list dev/x --all`；`version list dev/x --all --limit 2`；`version list dev/x --cursor c2`；`version list dev/x --json`。
- **期望**：`--all` 取全两页；`--all --limit 2` 每页 2 条；每页都带同一组 `--status` / `--query`；`--cursor` 按未知参数拒绝；人话/JSON 都不出现 `next` / `--cursor`；第二页失败时 stdout 为空。
- **判定**：假服务端记录每页 query；JSON 键断言；stdout 断言。

### T7 · 页面动作只拒绝并交接

- **前置**：无服务端请求。
- **步骤**：逐个运行 `version publish` / `rollback` / `revoke` / `restore` / `offline` / `online` / `delete`。
- **期望**：`version` 级动作给 `version open … --sub actions`；skill 级动作给 `skill open … --tab settings`；交接写 stderr；退出非 0；不执行动作。
- **判定**：逐条断言交接命令与退出码。

### T8 · 帮助内容与互相指

- **前置**：无。
- **步骤**：检查 `skill help` 的 `versions` 行与 `version help` 的 `list` 说明行。
- **期望**：`versions` 行写“列出你可以调用的版本（只含已上线）”，指向 `version list`；`list` 说明行写“作者视角：线上、草稿、历史版本、已下架、已丢弃（按你的档位）”，指向 `skill versions`。
- **判定**：关键名词 + 互相指路。

### T9 · 网关 v9 与 8→9 升级

- **前置**：假网关服务端返回 `platform_api_version: "9"` 的正文；临时 home 里先放 8 版。
- **步骤**：运行 `skillmaster setup`。
- **期望**：打印本地 8→9 的版本变化；安装后的正文与仓库文件除 `https://<host>` 替换外一致；同一 `name` 目录下替换。
- **判定**：文件字节比较 + 输出断言。

### T10 · 文档检查与 QA 探针

- **前置**：TD / test-plan 已存在。
- **步骤**：运行 `python3 .claude/skills/docs-architecture/scripts/check_docs.py`；grep `qa/role-matrix`。
- **期望**：文档检查退出 0；`qa/role-matrix/README.md` 与 `role_matrix/expectations.py` 不含旧的 `skill ls`。
- **判定**：退出码 + grep。

### T11 · 当前文档不教旧命令

- **前置**：无。
- **步骤**：grep `skillmaster-cli/README.md`、`gateway/skillmaster/SKILL.md`、CLI 帮助。
- **期望**：不再把 `skill list` / `skill search` / `skill ls` 当作现行命令；ADR / 历史版本 / QA 不计。
- **判定**：指定来源的 grep + 人看。

### T12 · 没有服务端代码改动

- **前置**：git diff。
- **步骤**：检查本版 diff 路径。
- **期望**：只改 CLI、网关正文、文档、QA 探针；服务端 `src/main` 无改动；既有命令只按 #12 的例外变化。
- **判定**：`git diff --stat` + 逐条例外。

### T13 · 输出与 `--json` 契约

- **前置**：假服务端；临时 home。
- **步骤**：运行 `skills list` / `skills search` / `skill files` / `skill read` / `skill rm` / `skill versions` 的成功与失败；带 `--json` 的负向调用；`cat` / `rm` 多路径部分成功；`grep` 无命中。
- **期望**：卡片两行（描述为空只有第一行）；`--json` 支持命令成功一个对象、失败 stderr 一个 JSON 错误对象；不提供 `--json` 的命令按未知参数拒绝；批量部分成功逐项报错并非 0；`grep` 1/2。
- **判定**：逐命令 stdout / stderr / 退出码矩阵。

### T14 · 分页契约与例外

- **前置**：假服务端两页；`skills search` 带 `next_cursor`；`version diff` 带 `next`；`skill shares` 带页码。
- **步骤**：运行 `skills list --all`、`version list --all`、`skill versions`、`skills search`、`skill shares`、`version diff`。
- **期望**：前两者 `--all` 取全且无游标；`skill versions` 一次给全；`skills search` 单页 + 收窄提示；`skill shares` 页码；`version diff` 的 `next` 作为既有例外保留。
- **判定**：输出与请求参数断言。

### T15 · `version discard` 确认

- **前置**：无服务端请求。
- **步骤**：运行 `version discard dev/x@code`（无 `--yes`），再带 `--json` 运行一次。
- **期望**：人话模式 stdout 有后果与完整命令、stderr 有拒绝说明、退出非 0、不发请求；`--json` 模式 stdout 为空、stderr 一个 JSON 错误对象（含后果/命令）。
- **判定**：stdout / stderr / 退出码。

### T16 · 凭据与 401/403

- **前置**：假服务端返回 401 与 403（`insufficient_scope` / `forbidden`）；无浏览器环境。
- **步骤**：运行缺凭据的命令；检查错误文案；用哨兵 key 检查输出。
- **期望**：不回显凭据；401 与 403 分开；403 区分 scope 与账号/资源权限；无浏览器快速失败；`--json` 进度可出现在 JSON 错误对象前。
- **判定**：文案断言 + 哨兵 grep。

### T17 · 帮助契约

- **前置**：无。
- **步骤**：检查顶层 / 各组帮助。
- **期望**：每组只列公开动词 + `help`；拒绝型动词不列；每个公开命令有 usage + 目的；有副作用的命令写明副作用，读命令不写；旧命令不出现。
- **判定**：帮助文本断言。

## 环境与数据

- **环境**：本机 Go 工具链；`skillmaster-cli/cmd/skillmaster` 的单元测试；假 HTTP 服务端用 `pointAtServer`；临时 home 用 `withTempHome`；不连真实服务端。
- **数据**：测试内构造的 `AuthorVersionList` / `Detail` / `SearchPage` JSON；两页游标、空描述、二进制文件、nameless 版本。
- **清理**：测试用临时目录；不写用户钥匙串；不跑 `qa/`。

## 结果

已执行。`cd skillmaster-cli && go test -count=1 ./...` 的 8 个包全部 `ok`；`python3 .claude/skills/docs-architecture/scripts/check_docs.py` 全部通过（1979 条相对链接有效）。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `TestTopLevelHelpListsEightCommands`（经 `run("help")` 断言 8 项集合、stdout 为空、退出 0） |
| T2 | 通过 | `TestTheSkillAndSkillsGroupsOwnTheirVerbs`、`TestSkillHelpHasTheTwentySingleSkillVerbs` |
| T3 | 通过 | `TestTheSkillAndSkillsGroupsOwnTheirVerbs` 的旧命令负向段（经 `run` + `reportError` 断言 stdout 为空、stderr 为未知命令、状态非 0） |
| T4 | 通过 | `TestFilesListsRelpathsAloneAndSaysWhichVersionItListed`、`TestFilesRoutesADraftToTheAuthorPlane`、`TestFilesWithoutAnAddressPrintsItsOwnUsage`、`TestFilesOnABareAddressKeepsTheResolvedPinInItsFooter`（命令级：footer 带解析出的 pin、版本行在最后） |
| T5 | 通过 | `TestVersionHelpHidesPageOnlyVerbs`、`TestVersionCreateIsUnknown` |
| T6 | 通过 | `TestVersionListAllWalksPagesAndKeepsTheFilters`、`TestVersionListAllStopsOnARepeatedCursor`、`TestVersionListAllStopsOnAnAlternatingCursor`、`TestVersionListAllDoesNotPrintAPartialList`、`TestVersionListRefusesCursor`、`TestVersionListSendsTheFiltersAndPrintsTheAuthorsRows`、`TestVersionListJSONSaysWhenThereIsMore` |
| T7 | 通过 | `TestPageOnlyVerbsRefuseAndNameTheOpenCommand`、`TestPageOnlyRefusalsEndWithTheExactHandoff` |
| T8 | 通过 | `TestVersionHelpAdvertisesAllAndPointsAtTheConsumerList`、`TestSkillHelpHasTheTwentySingleSkillVerbs` |
| T9 | 通过 | `TestSetupInstallsTheGatewayFileTheRepositoryShips`（读仓库网关文件、替换占位符后逐字比较，版本为 9）、`TestSetupReportsAPlatformVersionChange`、`TestSetupInstallsUnderTheNameTheSkillDeclares`。**部署后**的实机验收仍需一份用新网关构建并重启的服务端 |
| T10 | 通过 | `go test -count=1 ./...`（8 个包）、`check_docs.py`（全部通过） |
| T11 | 通过 | CLI 帮助/网关无旧命令；CLI README 只保留一句“旧名不进入正式命令面”的背景说明 |
| T12 | 通过 | `git diff --stat` 只含 CLI / 网关 / QA / 文档，无服务端 `src/main` |
| T13 | 通过 | `TestContentCommandsRefuseJSON`、`TestVersionListSendsTheFiltersAndPrintsTheAuthorsRows`（JSON 形状）、`TestVersionListJSONSaysWhenThereIsMore`（截断信号）、`TestARowIsTheAddressAlone...`、`TestWcBytes...` |
| T14 | 通过 | `TestListWalksEveryPageOnlyWhenAsked`、`TestVersionListAll...`；`skills search` / `skill shares` / `version diff` 保持既有单页/页码/next 行为 |
| T15 | 通过 | `TestVersionDiscardWithoutYesPrintsTheCommandAndSendsNothing`、`TestDiscardWithoutYesKeepsItsConsequenceInTheJSONObject` |
| T16 | 通过 | `TestTheTwoRefusalsThatBothLookLike403`、`TestACommandWithNoCredentialStartsTheLoginAndCarriesOn`、`TestACommandWithNoCredentialAndNoBrowserDoesNotStartOne` |
| T17 | 通过 | 上述帮助测试（集合相等断言：顶层 8 项、`skill` 20 个动词 + `help`、`skills` 只有 `list` / `search` / `help`） |

## 已知问题

本版范围内暂无已知缺陷。实现后若出现未修项，按 `已复现` / `部分复现` / `未验证` / `已修复` 记录在这里。

| 编号 | 缺陷 | 路径 | 状态 |
|---|---|---|---|
| — | — | — | — |
