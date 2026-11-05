# v47-welcome-and-consent · 测试方案

> **最后更新**：2026-11-05
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| A1 新账号默认库有两条引用 | T1、T3、T14 | 通过 |
| A2 新账号默认搜索命中官方指南 | T11 | 未执行 |
| A3 官方 skill 缺失或不可加入不影响注册 | T2、T15 | 通过 |
| A4 公域未加入的 invoke 被拒并给两条命令 | T5 | 通过 |
| A5 `--yes` 只对本次生效 | T6 | 通过 |
| A6 join 后不再确认 | T7 | 未执行 |
| A7 created / shared / joined 都不确认 | T4、T8 | T4 通过；T8 未执行 |
| A8 官方 skill 不豁免 | T9 | 未执行 |
| A9 读命令不受影响 | T10 | 通过 |
| A10 `invoke_requires_confirmation` 正确 | T4 | 通过 |
| A11 默认加入幂等 | T3 | 通过 |
| A12 平台 skill 正文写清 `--yes` | T12 | 通过 |

## 用例

### T1 · 新账号注册后库里立刻有两条官方引用

- **前置**：测试库里有 `skillmaster/skillmaster-startup` 与 `skillmaster/skillmaster-guide` 两条公开、有当前版本、未下线、未删除的 skill（造在 V2 种下的保留命名空间与系统账号下，形状同 `AbstractOrganizeIT.skill`；`WebRegistrationIT.RESERVED_NAMESPACE_ID` / `SYSTEM_USER_ID`）。
- **步骤**：调用 `POST /web/register` 注册一个随机用户名与手机号；查该用户的 `skill_library_join` 行，并用新账号的凭据请求 `GET /api/v1/skills?scope=library&show=all`。
- **期望**：201；`skill_library_join` 恰有两行；列表里两个名字的 `source` 都是 `joined`。
- **判定**：通过／失败。

### T2 · 官方 skill 不存在时注册照常成功

- **前置**：测试库里**没有**这两个 skill（默认没有）。
- **步骤**：注册一个随机账号。
- **期望**：201；账号与个人命名空间存在；`skill_library_join` 没有该用户的行；没有 5xx。
- **判定**：通过／失败。

### T15 · 官方 skill 存在但不可加入时注册照常成功

- **前置**：配置里的一条官方 skill 存在，但 `current_version_id` 为空（没有可加入的版本）。
- **步骤**：注册一个随机账号，查 `skill_library_join`。
- **期望**：201；该用户没有这条库引用；没有 5xx。
- **判定**：通过／失败。

### T3 · 默认加入幂等

- **前置**：同 T1。
- **步骤**：注册；再对同一账号直接执行一次默认加入（调用用例，或重放同一段 SQL 语义）。
- **期望**：第二次不报错；`skill_library_join` 仍然只有两行。
- **判定**：通过／失败。

### T4 · `invoke_requires_confirmation` 的四种取值

- **前置**：一个自己创建的公开 skill、一个共享给调用者的公开 skill、一个调用者加入的公开 skill、一个只有公开关系的 skill。
- **步骤**：分别请求 `/api/v1/skills/{ns}/{name}/metadata` 与 `/api/v1/skills/{ns}/{name}`。
- **期望**：只有「只有公开关系」那条为 `true`；其余三条为 `false`；两个端点一致；既有字段（`access`、`permissions`、`can_write` 等）不变。
- **判定**：通过／失败。

### T5 · 公开且未加入的 `invoke` 被拒

- **前置**：调用者没有库关系的一条公开 skill；本地凭据可用。
- **步骤**：`skmr skill invoke <地址>`。
- **期望**：退出码 1；stdout 没有正文与 header；stderr 同时含 `skmr skill invoke <地址> --yes` 与 `skmr skill join <裸地址> --yes`，后一条完整写出「收进你的库（引用，不复制），以后不再确认」；地址里带版本时，invoke 那条保留版本、join 那条去掉版本；这次调用没有发出任何库写入请求。
- **判定**：通过／失败。

### T6 · `--yes` 只对本次生效

- **前置**：同 T5。
- **步骤**：`skmr skill invoke <地址> --yes`，再执行一次不带 `--yes` 的同一命令。
- **期望**：第一次正常加载；库引用仍不存在；第二次仍按 T5 拒绝。
- **判定**：通过／失败。

### T7 · 收进库之后不再确认

- **前置**：同 T5。
- **步骤**：`skmr skill join <裸地址> --yes`，再 `skmr skill invoke <地址>`。
- **期望**：join 成功；invoke 直接加载，不再拒绝。
- **判定**：通过／失败。

### T8 · 三种库关系都不确认

- **前置**：同 T4 的前三条。
- **步骤**：各自 `skmr skill invoke`，不带 `--yes`。
- **期望**：三条都直接加载。
- **判定**：通过／失败。

### T9 · 官方 skill 不豁免

- **前置**：新账号（或手工 join 过官方 skill 的账号）把 `skillmaster/skillmaster-guide` `leave` 掉。
- **步骤**：`skmr skill leave skillmaster/skillmaster-guide --yes`，再 `skmr skill invoke skillmaster/skillmaster-guide`，再带 `--yes` 执行一次。
- **期望**：第一次按 T5 拒绝；带 `--yes` 能加载。
- **判定**：通过／失败。

### T10 · 读命令不受影响

- **前置**：一条只有公开关系的 skill；已经通过 `invoke --yes` 钉过一次版本，或在地址里写版本。
- **步骤**：`skmr skill read` / `cat` / `head` / `tail` / `grep` 它里面的一个文件。
- **期望**：与改动前一致（该成功就成功，该报错就报同一类错），没有确认提示。
- **判定**：通过／失败。

### T11 · 新账号默认搜索命中官方指南

- **前置**：同 T1；本地服务端跑在 8080，数据库指向本工作区的库。
- **步骤**：用新账号执行 `skmr skills search "SkillMaster 官方使用指南" --semantic=false`（不加 `--scope`）。
- **期望**：结果含 `skillmaster/skillmaster-guide`。
- **判定**：通过／失败。

### T12 · 平台 skill 正文写清了官方地址可以带 `--yes`

- **前置**：无。
- **步骤**：读 `skills/skillmaster/SKILL.md` 的「对 skill 的操作」一节。
- **期望**：`skmr skill invoke skillmaster/skillmaster-guide` 的示例带 `--yes`，并说明官方地址不用先问用户。
- **判定**：通过／失败（人工）。

### T13 · `skmr startup` 的加载不受确认影响（回归，未映射验收）

- **前置**：一个**没有**把官方 skill 收进库的账号。
- **步骤**：走 `startup` 读取 `skillmaster/skillmaster-startup` 的那条路径（`fetchBody` 传 `confirm=false`）。
- **期望**：不出现确认拒绝；行为与改动前一致。
- **判定**：通过／失败。这条是设计承诺（`startup` 不受影响）的回归用例，不对应某一条验收。

### T16 · 作者面的行 id 不能绕过确认（回归，未映射验收）

- **前置**：一条公开、调用者没有库关系、`public_rung` 至少是 `browse` 的 skill。
- **步骤**：拿到它的版本行 id，`skmr skill invoke <地址>@<行 id>`。
- **期望**：与 T5 相同的拒绝；不读作者面正文。`invoke` 档打不开作者面，这一档下仍给作者面的 403，不进入那两条命令。
- **判定**：通过／失败。

### T17 · 分块续读带着这次同意（回归，未映射验收）

- **前置**：同 T5，且正文超过分块上限。
- **步骤**：`skmr skill invoke <地址> --yes`，读它给出的续读命令。
- **期望**：续读命令带 `--yes`，照它执行不会再被确认拦住。
- **判定**：通过／失败。

### T14 · 默认清单来自配置

- **前置**：把 `skillmaster.welcome.skills` 覆盖成只含一个地址（`demo/only`），且该地址与一条没配置的地址都已造好。
- **步骤**：① 带这个覆盖启动并注册一个随机账号，查库引用；② 换成格式写错的值（例如 `no-slash`），再启动。
- **期望**：① 只加入配置里的那一条，没配的不加入；② 启动失败，异常信息点名那个写错的值。
- **判定**：通过／失败。

## 环境与数据

- **服务端**：`SKILLMASTER_TEST_DB_URL=jdbc:postgresql://localhost:5432/skillmaster_test_v47`（工作区 `.env` 已配）；`cd skillmaster-server && ./mvnw verify`。测试库每次重建 schema，不手工修。
- **官方 skill 的造法**：测试里直接 INSERT `skill` 行，落在 V2 种下的保留命名空间与系统账号里（`WebRegistrationIT` 的两个常量），形状与 `AbstractOrganizeIT.skill` 相同——公开、`current_version_id` 非空、未下线、未删除。需要正文的用例再补版本内容。
- **默认清单**：由 `skillmaster.welcome.skills` 决定；测试里用 `SKILLMASTER_WELCOME_SKILLS` 覆盖，T14 因此能验证「配置一份就加一份」。
- **CLI**：`cd skillmaster-cli && go test ./...`，用测试内的假服务端覆盖 T5、T6、T10、T13、作者面行 id 与分块续读；T7–T9 需要服务端状态，T11 是人工端到端（本地 8080）。
- **清理**：测试库可重建；人工用例用一次性随机账号，不需要手工清库。

## 结果

实现完成后填写。每条都要有证据（命令输出、截图、日志片段），不要只写"通过"。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `WebRegistrationIT.registrationPutsTheConfiguredDefaultSkillsInTheLibrary`（201 + 恰好两行 + 新账号的库列表两项均为 `joined`） |
| T2 | 通过 | `WebRegistrationIT.registrationStillSucceedsWhenTheDefaultSkillsAreMissing`（201 + 没有引用） |
| T3 | 通过 | `WebRegistrationIT.theDefaultJoinIsIdempotent` |
| T15 | 通过 | `WebRegistrationIT.registrationStillSucceedsWhenADefaultSkillIsNotJoinable` |
| T4 | 通过 | `LibraryApiIT.theAccessCarriesTheCallersRelation`（四种关系）· `SkillDetailIT.aPublicSkillOutsideTheLibraryAsksForConfirmation`（两个端点均为 `true`）· `InvokeConfirmationTest`（工厂各取值） |
| T5 | 通过 | `go test ./cmd/skillmaster -run TestInvokeStopsForAPublicSkillAndOffersBothWaysForward`（含「没有库写入」的请求断言） |
| T6 | 通过 | `go test ./cmd/skillmaster -run TestInvokeWithYesLoadsOnceAndWritesNothing`（第二次不带 `--yes` 仍退出 1） |
| T7 | 未执行 | 需要服务端 join 状态的端到端链路；决策路径由 T4（joined=false）与既有 invoke 用例（false 分支直接加载）组合覆盖 |
| T8 | 未执行 | 同上；created / shared / joined 三种 `false` 已由 T4 钉住 |
| T9 | 未执行 | 需要一条已 leave 出库的官方 skill 的端到端环境 |
| T10 | 通过 | `go test ./cmd/skillmaster -run TestReadIsNotGatedByThePublicSkillConfirmation`（read / cat / head / tail 都不读确认字段） |
| T11 | 未执行 | 需要本地起服务端并造官方 skill 的端到端环境 |
| T12 | 通过 | 人工读 `skills/skillmaster/SKILL.md`：官方地址示例已带 `--yes` |
| T13 | 通过 | `go test ./cmd/skillmaster -run TestStartupReadsItsOwnSkillWithoutConfirmation` |
| T16 | 通过 | `go test ./cmd/skillmaster -run 'TestInvokeByRowIdStillAsks|TestInvokeByRowIdAtTheInvokeRungKeepsTheRungError'`（行 id 不能绕过确认；`invoke` 档仍给出作者面的 403） |
| T17 | 通过 | `go test ./cmd/skillmaster -run TestTheOffsetContinuationKeepsTheConsent`（续读命令带 `--yes`） |
| T14 | 通过 | `WelcomeSkillsConfigIT.onlyTheConfiguredAddressIsJoined`（覆盖成一条后只加入那一条）· `SkillmasterPropertiesTest.aMalformedWelcomeAddressIsRefusedAtStartup`（写错与带空白都拒绝） |

**未执行的用例**：T7、T8、T9、T11 都是端到端组合，需要服务端状态或本地起服务；它们的分支分别由 T4（服务端给出 `false` / `true`）与 CLI 用例（`true` 拒绝、`false` 加载）钉住，但没有一次性跑完整链路。

**跑过两轮**：改动前全量 `./mvnw verify` 570 条，只有 `SkillDetailIT` 两处字段清单断言失败（新字段未列入）；补齐后，复审又加了 `WelcomeSkillsConfigIT`、`WebRegistrationIT` 的 T15 与若干断言，受影响的服务端各组重跑通过。CLI 侧 `go test ./...` 除下列三条既有失败外全过。按项目约定没有再次跑全量回归。

**本版之外、已复现的既有失败**：`go test ./...` 的 `cmd/skillmaster` 包有三条 v25 自动更新用例失败（`TestTheCheckIsThrottledToOnceADay`、`TestANewerBuildIsInstalledAndTheCommandIsUntouched`、`TestATamperedPackageLeavesTheBinaryAlone`）。在干净的 `main` 检出（73326d7）上复现同样三条，与本版改动无关。

## 已知问题

本版范围内没有未修缺陷。
