# v61-skill-deletion · 测试方案

> **最后更新**：2026-11-11
> **所属版本**：[`README.md`](README.md)

## 验收映射

| 验收 | 用例 |
|---|---|
| A1 删除后同名立即重建 | T1 |
| A2 恢复冲突自动改名 | T2 |
| A3 删除确认显示影响面 | T3 |
| A4 删除后通知受影响的人 | T4 |
| A5 公开 410 / 私有 404 | T5 |
| A6 公开、有线上版本且有入库者进入下线中 30 天 | T6 |
| A7 下线中调用成功且带警告 | T7 |
| A8 下线开始通知入库者与授权者并给 copy 指引 | T8 |
| A9 到期转已下线、503、通知 | T9 |
| A10 取消下线中 | T10 |
| A11 私有 / 无入库者即时下线与删除 | T11 |
| A12 创建 / 提交不再返回 `skill_deleted` | T12 |
| A13 控制台操作都有 CLI 对应 | T13（人工逐行核对） |
| A14 下线中拒绝入库 | T14 |

## 用例

### 服务端（集成测试，`./mvnw verify`）

**T1 · 删除后同名立即重建**
- 前置：`dev/t1` 已发布一版。
- 步骤：删除 `dev/t1` → 立即创建 `dev/t1`。
- 期望：创建成功；「已删除」列表里旧条目仍在；旧版本与文件仍可读（通过恢复或作者面已删除查询）。

**T2 · 恢复冲突自动改名**
- 前置：删除 `dev/t2`，再创建同名新 skill。
- 步骤：恢复旧条目。
- 期望：恢复成功，返回地址 `dev/t2-restored-<id>`；旧版本、授权与可见性完整；新 skill 不受影响。

**T3 · 删除影响面**
- 前置：公开 skill，2 个入库者、1 个授权者。
- 步骤：读作者面的那一次详情（同一个响应里带 `usage.joins` 与 `grantees`，不另开协作者名单的请求）。
- 期望：`usage.joins = 2`、`grantees = 1`；控制台确认框能显示 2、1 与「公开」。

**T4 · 删除通知**
- 前置：同 T3，且已走完下线。
- 步骤：删除。
- 期望：2 个入库者与 1 个授权者各收到一条 `announcement`，标题「skill 已删除」；所有者不收到。

**T5 · 地址语义**
- 前置：一个已发布的公开 skill、一个公开但从未发布的 skill、一个私有 skill。
- 步骤：分别删除后请求 `GET /api/v1/skills/{ns}/{name}`。
- 期望：公开且曾发布 → 410 `skill_gone`；公开但从未发布 → 404；私有 → 404；同名下已有新的活 skill 时也是 404。

**T6 · 下线中**
- 前置：公开 skill，有 1 个入库者、1 个授权者，有线上版本。
- 步骤：`POST /web/skills/{ns}/{name}/offline`。
- 期望：`offline=false`，`pending_until = 操作时刻 + 30 天（UTC，容差 1 分钟）`；`offline_at` 为空；`show=pending` 列得出、`show=offline` 列不出；删除返回 409 `offline_required`。

**T7 · 下线中调用**
- 前置：同 T6。
- 步骤：`skmr skill invoke <地址>`；`skmr run <地址>`。
- 期望：都成功；stdout 内容不变；stderr 含日期与 `skmr skill copy` 指引。

**T8 · 下线开始通知**
- 前置：公开 skill，1 个入库者、1 个授权者。
- 步骤：开始下线。
- 期望：两人各收到 `skill_offline_scheduled`（收件人共 2 行），带日期（`expires_at`）与 skill 地址；所有者不收到。

**T9 · 到期完成（读时判定 + 任务物化）**
- 前置：一个 `offline_due_at` 在过去的 pending skill（1 个入库者、1 个授权者）。
- 步骤一：**先不跑任务**，读消费面、列表与删除判定。
- 期望一：消费面已经返回 503 `skill_offline`；列表显示已下线；删除已经允许。
- 步骤二：调用 `CompleteDueOfflinesUseCase`（测试直接调用，不跑定时任务）。
- 期望二：`offline_at` 被写入、两列清空；入库者与授权者各收到一条完成通知（共 2 行）；重复调用返回 false，不重复通知。

**T10 · 取消下线中**
- 前置：同 T6。
- 步骤：`POST /online`。
- 期望：两列清空；状态回到在线；调用不再带警告；收件人收到取消通知。

**T11 · 即时下线与删除**
- 前置：一个私有共享 skill（有授权者）；一个公开、有线上版本且无入库者的 skill。
- 步骤：分别下线、删除。
- 期望：都即时完成（`offline=true`、无 `pending_until`）；删除后入库者与授权者收到通知；公开地址 410，私有地址 404。

**T12 · `skill_deleted` 退役**
- 步骤：删除一个 skill 后创建 / 提交同名内容；搜索代码与错误码表。
- 期望：创建与提交都成功；代码、帮助与错误表里不再出现 `skill_deleted`。

**T13 · 控制台操作都有 CLI 对应（ADR 0081）**
- 步骤：逐行核对 `prd.md` §控制台操作与 CLI 对应；每一处标为「只在控制台」的操作，确认也在 PRD 的不做清单里写了理由。
- 期望：没有一处控制台操作缺少命令或例外记录。

**T14 · 下线中拒绝入库**
- 前置：同 T6。
- 步骤：`skmr skill join <地址> --yes`；控制台打开该 skill。
- 期望：CLI 返回 409 `skill_offline_pending`，句子里有日期与 copy 指引；控制台不显示入库按钮，说明「下线中，暂时不能入库」。

### CLI（`go test ./...`）

- `skills list --show pending` 只列下线中；`--show offline` 只列已下线；`--verbose` 与 `skill show` 的状态带日期。
- `delete` 回执不再说「名字不会释放」；`restore` 冲突时输出新地址。
- `join` 的 `skill_offline_pending` 映射；`invoke` / `run` 的 stderr 警告在 `--json` 下也不丢。

### 控制台（`npm test`、`npm run type-check`）

- 设置页下线中：取消下线的确认壳与按钮（`skills.test.tsx` 的 `keeps the delete blocked during the ramp and says why`）。
- 设置页公开且有入库：删除禁用与「先走完下线」提示（同一文件）。
- 删除确认显示影响面与「名字会立即释放」。
- 入库确认弹窗「引用，不复制」。
- 通知页下线中通知带「复制到我的库」，点击打开复制弹窗。

### 设计件

- `node design/scripts/console-states.mjs … --only settings,list,detail,notifications` 全绿（改到哪一组跑哪一组；`--only` 用逗号，不是竖线）。
- 人工看 `artifacts/design/console/skill-settings-pending.png`、`skills-pending.png`、`notifications-offline.png`。

## 环境与数据

- 数据库：`skillmaster_test_v61`（每个 worktree 一份）；测试每次重建 schema。
- 集成测试需要的三个 `SKILLMASTER_TEST_DB_*` 从 `.env` / `compose.yaml` 取；不把值写进文档。
- 时间：测试直接写 `offline_due_at` 到过去，再调用 `CompleteDueOfflinesUseCase`，不等真实 30 天。
- 造数据：用现有测试夹具创建 skill、发布、`skill_grant` 与 `skill_library_join`；通知断言读 `notification` / `notification_recipient`。
- 设计件：`design/` 下 `npm ci` 后构建；通知样张用 `?page=notifications&status=all&notice=offline`。
- **既有失败**：`home-check` 的「隐私政策：有生效日期与版本」在 `main` 上就失败，与本版无关；不把它算作本版失败。

## 结果

执行时间：2026-11-11。命令都从仓库根目录或文档写明的子目录跑。

**服务端**（`skillmaster-server/`）：
`SKILLMASTER_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/skillmaster_test_v61 SKILLMASTER_TEST_DB_USER=… SKILLMASTER_TEST_DB_PASSWORD=… ./mvnw -o verify`
→ **单测 506、集成 782，0 失败 0 错误，`BUILD SUCCESS`**。

- T1：`CreateSkillIT#aDeletedNameIsFreeAgain`、`CliAuthoringIT#anExistingNameIsANamedConflictAndADeletedOneIsFree` **通过**。
- T2、T5、T6–T10：`WebSkillMetadataIT#aDeletedSkillIsTheSameNothingEverywhereAndComesBackIntact`（改名恢复）、`LibraryApiIT#restoringATombstoneByIdRenamesTheOneAskedFor`（按 id 恢复 + 改名）、`#aDeletedPublicSkillAnswers410AndAPrivateOneStays404`、`#aPublicSkillThatNeverPublishedStays404AfterDeletion`（公开但从未发布仍是 404）、`#aLiveSkillUnderTheOldNameTakesBackTheOrdinary404`（同名活 skill 存在时回到 404）、`#aPublicSkillWithJoinersLeavesThroughTheRamp`（下线中、到期 +30 天、列表 `show=pending`、拒绝入库、删除被挡、读时判定、任务物化与通知（2 个入库者 + 1 个授权者共 3 人）、删除通知、通知历史里不残留过期预告）、`#thePassedDeadlineAllowsTheDeleteBeforeTheJobRuns`（到期未跑任务也可删）、`#cancellingTheRampRestoresServiceAndWithdrawsTheAdvanceNotice`（取消后通知与取消公告、历史 feed 不再出现过期预告）**通过**。
- T3、T4：`WebSkillMetadataIT#theAuthorDetailCarriesTheDeleteImpact`（一次详情读出 `usage.joins = 2` 与 `grantees = 1`）、`LibraryApiIT#deletingANeverPublishedSkillTellsThePeopleItWasSharedWith` 与上面的公开路径（2 个入库者 + 1 个授权者各一条删除通知）**通过**。
- T9 的任务路径直接调用 `CompleteDueOfflinesUseCase`，不跑定时任务；同一用例断言重复调用不产生第二条完成通知（认领是条件更新）。
- T4、T11：`LibraryApiIT#deletingANeverPublishedSkillTellsThePeopleItWasSharedWith`（从未上线的窄删除也通知授权者，所有者不收到）、`WebSkillMetadataIT#aPublicSkillNobodyJoinedGoesOfflineAtOnceAndMayBeDeleted`（公开无入库者一步下线、随即删除）与上面的公开路径 **通过**。
- T12：`SkillSubmitIT#submittingOverADeletedNameCreatesANewSkillAndLeavesTheTombstoneAlone`、`SkillCopyIT#aDeletedNameIsFreeAgain`、`SkillTransferIT#aDeletedNameIsFreeAgain` **通过**；代码与错误表里已无 `skill_deleted`。
- T14：服务端 `JoinSkillLibraryUseCase` 返回 409 `skill_offline_pending` 且 `details[0].issue` 是日期，由 T6 用例断言（用库外账号发起）。
- T13：逐行核对 `prd.md` §控制台操作与 CLI 对应，「只在控制台」的行都在 §不做里写了理由；本版新增的每一处界面动作都有命令或例外记录。**通过**。
- 同步侧行为随 ADR 0183 改变：`SyncPipelineIT#aSoftDeletedSkillIsCreatedAgainByTheNextSync` 记录「下一次同步会重新创建」，不再跳过。

**CLI**（`skillmaster-cli/`）：`go build ./...` 与 `go test ./...` **全部通过**。`--show pending` 发到线上（`TestListSendsThePendingShowValue`）、`--verbose` 与 `skill show` 的状态带日期（`main_test.go` 的表与 `TestSkillShowNamesTheRampAndItsDate`）、`invoke` 在下线中仍成功并警告（`TestInvokeOnARampLoadsAndWarns`；`run` 复用同一个 `offlinePendingNote`）、`join` 的 `skill_offline_pending` 映射带可执行地址（`TestJoinAndLeaveRefusalsAreNamedByCode` 与错误夹具）、`online` 在下线中打开页面而 `offline` 拒绝（`TestARampIsOpenedByOnlineAndRefusedByOffline`）、`restore` 的新地址回执与 `skill delete` 的成功回执（「名字已释放」与可执行的 `restore` 命令）都有单测。`skill_gone` 的句子在 `ServerError` 层，`show` / `invoke` / `run` 都拿到中文。

**控制台**（`skillmaster-console/`）：`npm run type-check` 通过；`npm test` **47 个文件、761 个用例全过**。设置页下线中（取消下线 + 删除禁用 + 原因）、删除确认的三件影响面、入库确认弹窗、发现页下线中卡片不画入库、通知「复制到我的库」都有组件用例；名字释放的回执与恢复改名的横幅也有用例。**视觉验收仍需人看**（CLAUDE.md 红线 2）。

**设计件**：`node design/scripts/console-states.mjs "design/skillmaster-控制台演示.html" artifacts/design/console --only settings,list,detail,notifications` **全部通过**（exit 0）。

**文档**：`generate.py --check`、`impact.py --check`（1260 个引用）、`check_docs.py`（4603 条链接）**全部通过**。

## 已知问题

无本版范围内未修缺陷。

- `home-check` 的「隐私政策：有生效日期与版本」在 `main` 上就失败，与本版无关，不计入。
- 同步侧在官方命名空间重新创建已删除的 skill 是 ADR 0183 的直接后果，已在 `SyncPipelineIT` 中固定；若产品以后希望「删除即永久」需要另开决策。
