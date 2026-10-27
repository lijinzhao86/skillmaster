# v26-startup-guidance-and-agent-paths · 测试方案

> **最后更新**：2026-10-27
> **状态**：执行中（单元与文档检查已跑；真机、人工走查与 Windows 未做，见「结果」）
> **依据**：[PRD](prd.md) 验收 1–20 与 [技术设计](technical-design.md)

## 验收映射

| PRD 验收 | 用例 | 怎么判定 |
|---|---|---|
| 1 旅程没有路径表 | T01 | `skillmaster skill read skillmaster/startup@1.3.1 steps/03-prompt.md` 逐条看：没有「agent → 文件」表；有四句（五条原则）+ 五句话 |
| 2 WorkBuddy 写对 | T02 | 真机 WorkBuddy：写入 `~/.workbuddy/user-<uid>-<scope>/MEMORY.md`；新任务里先查库 |
| 3 通用路径检查 | T03 | 目录 / 父目录不存在 / 主目录外 / 项目目录四组夹具的退出码与输出 |
| 4 计划先行（成立条件） | T04 | 不给 `--yes`：文件字节与 mtime 不变，退出 1；已有同样块：只更新记录，退出 0 |
| 5 多文件记录与更新（成立条件） | T05 | 两个文件记录、逐个 status、update 全部、旧摘要提示一次、v1 记录可读 |
| 6 `--agent` 只是标签 | T06 | 随意名字能写、能记录、status 原样显示 |
| 7 撤销 | T07 | uninstall 只去标记段，段外字节不变；文件仍在；记录去掉 |
| 8 `startup` 记录行 | T08 | 两个标签 → 一行；没有记录 → 没有这一行；记录行由本地生成，无新增服务端请求（计数待补） |
| 9 旅程验证与换 agent | T09 | `steps/04` 用 `prompt status`；`steps/05-written` 只告知，不安排重启；明说换 AI 要重做第三步 |
| 10 五句话请示 | T10 | 人工走查传技能、加说明、删电脑上原来的那份；每次五句话 + 具体问法 |
| 11 清单：第一次 / 小工具旧了 | T11 | 新 HOME + 有本机 skill + 未登录；旧二进制 + 假发布源 |
| 12 清单：Claude 完成 → Codex（成立条件） | T12 | 只剩 1 件；记录旧摘要 → 「要更新」；另一个文件被说明 |
| 13 清单：本机没有 skill | T13 | 传技能 / 正式上线进「跳过」；让 AI 用 SkillMaster 自己的文件夹 / 电脑上原来的那份进「已经好了」 |
| 14 清单只读且诚实（成立条件） | T14 | 前后哈希不变；无旅程正文 / pin；未登录「登录后检查」；断网「暂时读不到」；无假百分比 |
| 15 每步重算与报进度 | T15 | 传技能后重跑，下一项变正式上线；必做项都好了以后问可选清理；多对象「第 3 个，共 33 个」；收尾报告 |
| 16 安装说明页第 0 步 | T16 | 没装 / 旧了 / 最新三种情况；说明页写清 `--plan` |
| 17 清单的产品语言 | T17 | 人工读输出：无 CLI / agent / 系统提示词 / 路径 / 命令 / 标记段 / 摘要 |
| 18 措辞按范本 | T18 | 人工走查逐条对照 PRD 的情境表与完整范本；技术词 0；不出现分数 |
| 19 契约与跨平台 | T19 | 帮助八项 / ≤2 KB / 无 ANSI；prompt print 逐字节；退出码；check_docs；Windows |
| 20 `.skillmaster` 一次授权 | T20 | 人工走查权限设置 / transcript：只给 `~/.skillmaster/`；拒绝时备份与删除都不发生 |
| 22 传技能可选（成立条件） | T22 | 本机有未传 / 部分已传 / 全未传三组夹具；「还差」不含传技能；下一步不指 `steps/01`；一份没传时正式上线已完成；线上技能正文明说可选 |

## 用例

### T01 · 旅程没有路径表（平台内容）

- **前置**：线上 `skillmaster/startup@1.3.1` 可读。
- **步骤**：`skillmaster skill read skillmaster/startup@1.3.1 steps/03-prompt.md` 与 `SKILL.md`；逐条对照 PRD 做 #1。
- **期望**：没有「agent → 文件」的表格；只有「用户级 / 你自己确定 / 不确定先查自己的配置或上下文、再问『你用的是哪个 AI』并直接写用户级记忆 / 覆盖文件 / 应用内粘贴」这几条原则 + 五句话 + `prompt status` / `prompt install --file` / `--yes`；有旧路径迁移说明；`SKILL.md` 有五句话。

### T02 · WorkBuddy 真机（人工，成立条件）

- **前置**：真实 WorkBuddy 会话；本地记忆设置开着；草稿已上线到测试通道（或直接从草稿读）。
- **步骤**：在 WorkBuddy 里走第三步；让 agent 用自己上下文里的路径运行 `prompt install --file <路径> --agent workbuddy --yes`；开新任务；问一个需要查库的问题。
- **期望**：写入 `~/.workbuddy/user-<uid>-<scope>/MEMORY.md`，不是 `~/.codebuddy/CODEBUDDY.md`；新任务里 agent 先跑 `skillmaster skills search`；transcript 留档。

### T03 · 通用路径检查（单元 / 夹具）

- **前置**：临时 HOME；四个目标：目录、父目录不存在、主目录外的文件、当前项目目录里的文件；`--force` 另跑一次。
- **步骤**：`prompt install --file X --yes`；记录退出码与 stdout/stderr；`--force` 只越过主目录外。
- **期望**：目录 / 父目录不存在 → 退出 1，不写；主目录外 → 退出 1，提示 `--force`；`--force` + `--yes` 才写；项目目录 → 计划里有「这个位置只对当前项目生效」，不拒绝；四种情况下目标文件字节不变（除最后一种同意写入）。

### T04 · 计划先行（单元，成立条件）

- **前置**：一个没有标记段的文件；一个已有相同标记段的文件；一个已有不同标记段的文件。
- **步骤**：分别跑 `prompt install --file X`（无 `--yes`）；再跑 `--yes`；`prompt uninstall --file X`（无 / 有 `--yes`）。
- **期望**：无 `--yes` 时输出五句话，退出 1，install 的文件字节与 mtime 不变、uninstall 的文件字节不变；已有相同段 → 只更新记录、退出 0、文件不变；有不同段 → 计划后退出 1，`--yes` 才替换；uninstall 同理，段外字节不变，文件仍在。

### T05 · 多文件记录与更新（单元，成立条件）

- **前置**：两个目标文件；`prompt.json` 为 v1 单条记录；再写第二份；把二进制块摘要改成另一个值（测试夹具）。
- **步骤**：两次 `prompt install --yes`；`prompt status`；`prompt update --yes`；任一普通命令触发 `noteStaleBlock`；`prompt update --yes` 全部更新。
- **期望**：`prompt.json` 是 `{"schema":2,"files":[...]}` 两条；status 两行；全部一致退出 0；任一不一致退出 1；旧摘要提示只出现一次；update 后两份都变成新版；v1 记录不丢文件。

### T06 · `--agent` 只是标签（单元）

- **前置**：任意文件。
- **步骤**：`prompt install --file X --agent 一个随意的名字 --yes`；`prompt status`。
- **期望**：能写、能记录；status 原样显示；不因名字不在名单里拒绝。

### T07 · 撤销（单元）

- **前置**：文件里标记段前后各有一段内容；记录里有该路径；再准备一条 `--all` 的第二份。
- **步骤**：`prompt uninstall --file X --yes`；`prompt uninstall --all --yes`；无 `--yes` 各跑一次。
- **期望**：只去掉 `Begin` 到 `End` 的整段；段外字节不变；文件仍在；记录去掉；无 `--yes` 什么都不动、退出 1。

### T08 · `startup` 记录行（单元）

- **前置**：`prompt.json` 两条记录（`claude`、`workbuddy`）；假服务端；空记录夹具。
- **步骤**：跑 `startup`；对比 stderr；假服务端计请求。
- **期望**：有记录 → stderr 一行「这台电脑上写过这段说明的 AI：claude、workbuddy。如果你不是其中之一，按第三步再写一份。」（标签原样输出）；没有记录 → 没有这一行；记录行由本地 `prompt.json` 生成；stdout 仍是旅程正文逐字。

### T09 · 旅程的验证与换 agent（平台内容）

- **前置**：草稿可读。
- **步骤**：读 `steps/04-verify.md`、`steps/05-written.md`、`steps/06-permission.md`、`steps/07-retire.md`。
- **期望**：04 用 `prompt status` 核对记录的文件；05 只告知「已经写进全局记忆」、不安排重启；明说换 AI 要重做第三步。

### T10 · 五句话请示（人工，成立条件）

- **前置**：一次完整走查；三个动作：传技能、加说明、删电脑上原来的那份。
- **步骤**：记录 agent 在每次写 / 删前的发言。
- **期望**：每次含 我要做什么 / 只会动到什么 / 做完会怎样 / 不想做了怎么办 / 不做会怎样；最后一句是具体动作；没有「确定吗」；不默认同意；不出现路径与命令（除非用户必须自己运行）。

### T11 · 清单：第一次 / 小工具旧了（夹具）

- **前置**：新 HOME、有本机 skill、未登录；另一组「旧版二进制 + 假发布源」（`SKILLMASTER_UPDATE_BASE`）。
- **步骤**：`startup --plan`；更新后重跑。
- **期望**：退出码 0；开头一句话目的；只列还没做的；七项名字正确；未登录项写「登录后检查」；**传技能在「跳过（可选）」里，不出现在「还差」或「还要确认」**；小工具旧了写「小工具有新版本，先更新一下」；结尾「还差 K 件」；stderr 指向 `steps/0N-*.md` 或「先登录 / 先更新小工具 / 稍后再检查一次」。

### T12 · 清单：Claude 完成 → Codex（夹具，成立条件）

- **前置**：Claude 记录与线上数据；`--agent codex --file ~/.codex/AGENTS.md`；把 Claude 记录改成旧摘要。
- **步骤**：`startup --plan --agent codex --file ~/.codex/AGENTS.md`。
- **期望**：小工具 / 登录 / 正式上线 / 电脑上原来的那份在「已经好了」或「跳过」里；**传技能在「跳过（可选）」里（本机技能都已上线时在「已经好了」里）**，不在「还差」里；只有「让 AI 先来 SkillMaster 找技能」在「还差」里；「让 AI 用 SkillMaster 自己的文件夹」在「跳过」里；「还差 1 件」「已经好了（4 件）」；把该 `--file` 对应的 Codex 记录改成旧摘要后写「要更新」；另一个文件被说明。

### T13 · 清单：本机没有 skill（夹具）

- **前置**：空本机、已登录。
- **步骤**：`startup --plan`。
- **期望**：传技能 / 正式上线进「跳过」（传技能写「本机没有可上传的技能」）；让 AI 用 SkillMaster 自己的文件夹 / 电脑上原来的那份进「已经好了」，清单只列名字；不要求读这几步。

### T14 · 清单只读且诚实（夹具 / 断网，成立条件）

- **前置**：`prompt.json`、目标文件、`update.json` 前后哈希；假服务端计请求；未登录夹具；断网夹具；四组路径夹具。
- **步骤**：跑 `--plan`；对比哈希与请求；读输出。
- **期望**：哈希不变；没有旅程正文请求 / pin；退出码 0；未登录写「登录后检查」不是 0；断网写「暂时读不到」；开发构建不检查更新；开始有「正在检查…」；没有假百分比；`--file` 的路径问题只报告不写。

### T15 · 每步重算与报进度（夹具 + 人工，成立条件）

- **前置**：有草稿、有本机 skill。
- **步骤**：完成「传技能」后重跑 `--plan`；走一次多对象步骤；人工走查。
- **期望**：该项进入「已经好了」、下一项变「正式上线」；**只传一部分时该项仍在「跳过（可选）」并写剩下几个，下一步仍是正式上线；一份都没传时正式上线算已完成，下一步是写说明**；多对象报「第 3 个，共 33 个」；人喊停停在下一个对象前；每步后有一段进度；收尾报告指一件真实的事。

### T22 · 传技能可选（夹具 + 线上正文，成立条件）

- **前置**：三组夹具——① 本机有 1 个技能、平台没有；② 本机有 3 个技能，其中 1 个已上线、1 个是草稿、1 个没传；③ 本机有技能、一份都没传。已登录。
- **步骤**：逐组跑 `startup --plan`；再跑 `skillmaster skill read skillmaster/startup@<当前版本> SKILL.md`、`steps/01-import.md`、`voice.md` 读正文。
- **期望**：① 传技能在「跳过（可选）」里，写 1 个没传；「还差」不含它；stderr 下一步不是 `steps/01`。② 传技能在「跳过（可选）」里，写剩下 1 个；正式上线在「还差」里，只针对那 1 份草稿；下一步是 `steps/02`。③ 传技能在「跳过（可选）」里；正式上线算已完成；下一步是写说明。三组都退出 0，都能在没传完的情况下继续；线上正文写清「传技能可选、不传或只传一部分都不挡后面」。
- **结果**：见 §结果。

### T16 · 安装说明页第 0 步（文档）

- **前置**：线上安装说明页。
- **步骤**：读页面；三种情况（没装 / 旧了 / 最新）。
- **期望**：`skillmaster --version` → 安装 / `update` / `--plan`；写清清单由 `--plan` 生成。

### T17 · 清单的产品语言（人工）

- **前置**：T11 / T12 的输出。
- **步骤**：逐行读。
- **期望**：每项按「用户得到什么」说；无 CLI / agent / 系统提示词 / 路径 / 命令 / 标记段 / 摘要 / 凭据；已完成收成「已经好了（M 件）」，待做按编号。

### T18 · 措辞按范本（人工，成立条件）

- **前置**：完整走查 transcript / 截图。
- **步骤**：对照 PRD 的六组情境表与完整范本。
- **期望**：开场 / 清单 / 五句话 / 进度 / 失败 / 跳过 / 收尾结构齐全；用户可见词是「小工具 / 传技能 / 正式上线 / 让 AI 先来 SkillMaster 找技能 / 让 AI 用 SkillMaster 自己的文件夹 / 电脑上原来的那份」；禁用词 0；不说 6/7；每个请示只问一个动作。

### T19 · 契约与跨平台（既有测试 + CI）

- **前置**：现有 `skillmaster-cli` 测试；`windows-latest`。
- **步骤**：`go test ./...`；帮助契约测试；`prompt print` 逐字节；`check_docs.py`；Windows 作业。
- **期望**：全绿；退出码 0/1/2/4；帮助八项、≤2 KB、无 ANSI；`check_docs.py` 退出 0。

### T20 · `.skillmaster` 一次授权（人工）

- **前置**：至少一个真机（Claude Code 或 Codex）；一个只能在弹窗里选的客户端可选。
- **步骤**：写完说明并告知用户后，走到第六步权限、第七步备份与删除；记录 agent 的请示与实际权限设置；再走一次拒绝分支。
- **期望**：备份前按五句话请示；同意后权限设置里只有 `~/.skillmaster/`（读 / 写 / 新建 / 删除），没有整个主目录；拒绝时备份与删除都不发生，本机副本不动；以后读写不再逐次问。

## 环境与数据

- **单元**：`t.TempDir()` + `t.Setenv("HOME", …)` / `XDG_CONFIG_HOME` / `APPDATA` / `LOCALAPPDATA`（v25 已有做法，见 `v25_test.go`）；`captureStdout` / `captureStderr`；`startupServer`（`startup_test.go`）扩展为可返回 metadata/body 的假服务端。
- **路径夹具**：临时 HOME 下建 `a/b.md`、目录 `d/`、主目录外的 `outside.md`、当前项目目录里的 `project/AGENTS.md`；符号链接一份。
- **记录夹具**：v1 单条 JSON、v2 两条 JSON、坏 JSON、空文件。
- **清单夹具**：新 HOME + `~/.claude/skills/{a,b}/SKILL.md`；未登录 / 已登录 / 断网；假 `PublishQueue` 与 `QuerySkills` 响应；`SKILLMASTER_UPDATE_BASE` 指向 httptest 的 `version.txt`。
- **平台内容**：线上 `skillmaster/startup@1.3.1` 是本轮的已发布内容；真机 WorkBuddy 用人的账号走一次，不把凭据写进夹具。
- **清理**：全部临时目录；不碰真实 `~/.claude`、`~/.codex`、`~/.workbuddy`、`~/.skillmaster`。

## 结果

| 用例 | 状态 | 证据 |
|---|---|---|
| T01 旅程没有路径表 | **通过（线上 @1.3.1）** | `skillmaster skill read skillmaster/startup@1.3.1 steps/03-prompt.md` 已无路径表，只有四句（五条原则）；线上 `1.3.1` 已发布 |
| T02 WorkBuddy 真机 | **未执行** | 需要真机与人操作，本地无法代替 |
| T03 通用路径检查 | **通过** | `TestPromptInstallPathChecks`（目录 / 父目录不存在 / 主目录外 / `--force` / 项目级警告） |
| T04 计划先行 | **通过** | `TestPromptInstallWithoutYesPrintsThePlainPlanAndWritesNothing`（字节与 mtime）、`TestPromptUninstallWithoutYesDoesNotWrite`；逐字相同只补记录见 `TestPromptInstallOfAnIdenticalBlockOnlyRecords`；无 TTY 见 `TestPromptUpdateDoesNotWaitForInput` |
| T05 多文件记录与更新 | **部分通过** | 两个文件的记录 / 撤销见 `TestPromptRecordsTwoFilesAndUninstall`，`update` 覆盖两文件见 `TestPromptUpdateTwoFiles`，v1 读取见 `TestAV25RecordIsReadAsOneFile`；**多文件「旧摘要只提示一次」没有单独用例**（单文件有 `TestTheStaleBlockIsAnnouncedOnce`） |
| T06 `--agent` 只是标签 | **通过** | `TestPromptAgentIsOnlyALabel`（写入、记录、`prompt status` 原样显示） |
| T07 撤销 | **通过** | `TestPromptRecordsTwoFilesAndUninstall`；段外字节不变由 `block_test.go` 的 `TestReplaceKeepsEverythingOutsideTheMarkers` / `TestRemoveKeepsEverythingOutsideTheMarkers` / `TestAppendLeavesOneBlankLine` 覆盖 |
| T08 `startup` 记录行 | **部分通过** | `TestStartupPrintsTheRecordedAIsOnStderr`、`TestPromptRecordLineNamesEachConfiguredAI`（去重、v25 无标签记录显示「未命名」、不显示路径）；**没有单独统计「不新增服务端请求」** |
| T09 旅程的验证与换 agent | **部分通过** | 线上 `@1.3.1` 的 `steps/04-verify.md` 用 `prompt status` + `prompt uninstall`；`steps/05-written.md` 只告知、不安排重启；换 AI 要重做第三步 |
| T10 五句话请示 | **未执行** | 人工走查（传技能 / 加说明 / 删本机副本） |
| T11 清单：第一次 / 小工具旧了 | **部分通过** | 新 HOME + 本机 skill + 未登录见 `TestStartupPlanWithoutCredentialIsReadOnlyAndSaysWhatIsUnknown`；该测试现在断言传技能在「跳过（可选；登录后才知道…）」、不在「还差」；**「旧小工具」分支没有清单夹具** |
| T12 清单：Claude 完成 → Codex | **部分通过** | `TestStartupPlanWithCredentialCountsTheSevenItems`（Codex 已写好、必做项全好的夹具）得到「已经好了 5 件 / 跳过 2 件」，stderr 指 `steps/06-permission.md`；`TestStartupPlanContinuesAfterAPartialImport` 覆盖「本机 2 个、1 个已上线 / 1 个草稿 / 1 个没传」时传技能留在「跳过（可选）」、正式上线进「还差」；**Claude→Codex 的「还差 1 件」夹具未建**；**「记录旧摘要 → 要更新」没有单独夹具** |
| T13 清单：本机没有 skill | **未执行** | 没有「本机技能目录为空」的夹具 |
| T14 清单只读且诚实 | **部分通过** | 前后无 `prompt.json` / `update.json`（`TestStartupPlanWithoutCredentialIsReadOnlyAndSaysWhatIsUnknown`）；旧记录不被标记（`TestStartupPlanLeavesAStaleRecordAlone`）；服务端 500 时清单写「还要确认」「现在还不能算完成」、不谎报完成（`TestStartupPlanDoesNotCallAnUnreadChecklistComplete`）；只读形状由 `TestOnlyThePlanFormOfStartupSkipsTheWritingHooks` 证明谓词被跳过，但该测试不统计请求；**断网「暂时读不到」与「无旅程 / pin 请求」没有单独断言** |
| T15 每步重算与报进度 | **部分通过** | 必做项全好后指向可选清理见 `TestStartupPlanWithCredentialCountsTheSevenItems`；只传一部分后仍在「跳过（可选）」、下一步转正式上线见 `TestStartupPlanContinuesAfterAPartialImport`；状态行不再猜「写提示词」、改成「看看还差几步」见 `TestWhereYouAreNamesTheNextStep`；多对象进度与人工走查未执行 |
| T16 安装说明页第 0 步 | **部分（文档已改）** | `skillmaster-site/public/install/skillmaster.md` 加了第 0 步（`--version` → 安装 / `update` / 最新）与第 4 步 `startup --plan`；**站点未发布，线上未核** |
| T17 清单的产品语言 | **部分通过** | 未登录清单有自动断言：stdout 不含 `CLI` / `agent` / `系统提示词` / `标记段` / `摘要` / `凭据` / `--` / 主目录路径（`TestStartupPlanWithoutCredentialIsReadOnlyAndSaysWhatIsUnknown`）；install / uninstall 的五句话同样有断言（`TestPromptInstallWithoutYesPrintsThePlainPlanAndWritesNothing`、`TestPromptUninstallWithoutYesDoesNotWrite`）。完整清单（已登录、七项都有内容）仍要人工读 |
| T18 措辞按范本 | **未执行** | 人工走查，逐条对照 PRD 情境表与完整范本 |
| T19 契约与跨平台 | **通过（本轮只跑本地）** | PR #79 的 CI 全绿（CLI Test and vet、Windows update path、Docs、Site）；2026-10-27 迭代 0001 后重跑 `gofmt -l .`（无输出）、`go vet ./...`、`go test ./...`（全部包 ok）；`check_docs.py` 2647 条链接全部有效；只读目录会打印可重跑的命令（`TestAHostThatCannotWriteIsGivenACommandToRerun`）。**本轮改动尚未推 CI** |
| T20 `.skillmaster` 一次权限 | **未执行** | 需要真机与人看权限设置 |
| T22 传技能可选 | **部分通过** | 一份没传：`TestStartupPlanFinishesWhenThePersonDeclinesToImport`（「跳过（可选）」、「必做项都好了；电脑上还有技能没传…」、stderr「旅程完成」、不出现「还差」/「全部好了」）；只传一部分：`TestStartupPlanContinuesAfterAPartialImport`（传技能仍可选、正式上线进「还差」、下一步 `steps/02` 而不是 `steps/01`）；未登录：`TestStartupPlanWithoutCredentialIsReadOnlyAndSaysWhatIsUnknown`；线上正文：草稿 `skillmaster/startup@TWQPGJWE` 已写入并通过 `skill check`，**待技能主人正式上线后按线上版本复核** |

**本地验过的命令（2026-10-27）**：`cd skillmaster-cli && gofmt -l . && go vet ./... && go test ./...`（全部包 ok）；`python3 .claude/skills/docs-architecture/scripts/check_docs.py`（退出 0，2647 条链接）；`skillmaster skill check skillmaster/startup@TWQPGJWE`（无错误，草稿 5 个文件改动）。

## 已知问题

1. **线上 `skillmaster/startup@1.3.1` 已发布**，9 个文件（含 `voice.md`、`steps/06-permission.md`）、`skill check` 无错误、`steps/03` 已无对照表。T01 / T09 按线上 `@1.3.1` 核对。**迭代 0001 的新正文在草稿 `@TWQPGJWE`（5 个文件改动），待技能主人正式上线；上线前线上仍是 `1.3.1` 的旧行为，T22 的线上正文一半要等上线后复核。**
2. **`.skillmaster` 权限没有自动验证**：CLI 不知道 agent 的权限系统；验收 20 靠人看设置与 transcript。
3. **`prompt.json` 并发写**：v2 仍是一个文件，两个 agent 同时 install 可能最后写入者赢（TD Q7）；安装流程顺序执行时不发生。
4. **Windows 路径判断**：Windows 作业已跑 updater / prompt 子集；大小写、`EvalSymlinks`、`%USERPROFILE%` 的完整路径夹具仍没有在 Windows 上逐条跑。
5. **`--plan` 的 5 秒预算**：平台状态 3 秒、更新检查 2 秒，顺序跑；网络慢时会出现「暂时查不到」，这是诚实退化，不是失败。
6. **几条清单分支还没有夹具**：小工具旧了（T11）、记录旧摘要（T12）、本机没有 skill（T13）、断网（T14）。实现已走到这些分支，但「没跑到」与「跑过且对」不能混为一谈；补夹具之前，这几条按未执行算。
7. **安装说明页只改了仓库里的源**：`skillmaster-site/public/install/skillmaster.md` 的第 0 步与第 4 步已改，站点发布前线上仍是旧版；T16 的线上核对未做。
