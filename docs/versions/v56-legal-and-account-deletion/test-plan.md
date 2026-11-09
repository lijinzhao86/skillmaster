# v56-legal-and-account-deletion · 测试方案

> **最后更新**：2026-11-10
> **所属版本**：[`README.md`](README.md)

## 验收映射

| PRD 验收 | 用例 | 谁能验 |
|---|---|---|
| #1 两个法务页匿名可读，含生效日期与版本 | T1 | 机 + 人 |
| #2 页脚两处链接真实，「协议」消失 | T2 | 机 |
| #3 不勾同意不能提交（前后端都拒） | T3 | 机 |
| #4 注册记录同意的文本版本与时间 | T4 | 机 |
| #5 账号页有注销入口与弹窗 | T5 | 机 + 人 |
| #6 凭据不对不注销；取消无变化 | T6、T8 | 机 |
| #7 注销后会话失效、不能再登录、提示「已注销」 | T7、T13 | 机 |
| #8 注销后 API 密钥全部失效 | T7 | 机 |
| #9 注销后个人命名空间内容对任何人不可调用 | T7 | 机 |
| #10 30 天内不能重注册；期满可以 | T9、T10 | 机 |
| #11 期满删除个人数据与个人内容，账号行去标识化 | T10 | 机 |
| #12 三处保留期与实现一致 | T11 | 人 + 机 |
| #13 安全承诺一节四条都在 | T12 | 人 |
| #14 别人命名空间里的贡献保留，作者显示「已注销账号」 | T14 | 机 |
| #15 分享/入库的接收者收到通知 | T15 | 机 |

## 用例

### T1 · 法务页匿名可读

- **前置**：站点构建完成；不带任何会话 cookie。
- **步骤**：访问 `/privacy` 与 `/terms`；读标题、生效日期、版本；点「返回首页」。
- **期望**：两页 200；标题分别是「隐私政策」「服务条款」；各带生效日期与版本 `1.0`；页脚可到；返回首页回到 `/`；`document.title` 随页面变。
- **落点**：`skillmaster-site` 的组件测试 + 真栈手工一次。

### T2 · 页脚

- **步骤**：在首页第二屏看页脚；点「隐私」「条款」。
- **期望**：分别到 `/privacy`、`/terms`；「协议」不再出现；其余四个占位链接点了不跳走（品牌 + 页脚 4 = 5 条 `href="#"`）。
- **落点**：`design/scripts/home-check.mjs`（本次已加断言：占位链接 5 条、两条法务链接的 href、法务页可滚动、底栏运营主体）。

### T3 · 注册同意（前后端）

- **前置**：注册页填好合法用户名、手机号、密码。
- **步骤**：不勾同意点「下一步」；勾上再点。
- **期望**：不勾时停在第一步，`.consent-error` 提示在勾选框下方出现「请先阅读并同意《服务条款》和《隐私政策》」；**一条短信都不发**；勾上提示消失并继续。两条链接指向站点源（`SITE_URL + terms/privacy`），不是控制台自己的源。
- **接口层**：直接 `POST /web/register` 不带 `consent` 或 `consent:false` → `400`，`{field:"consent", issue:"required"}`；验证码**不消耗**，勾上后用同一验证码仍可注册。
- **落点**：控制台组件测试（`register-flow.test.tsx`）+ `AccountDeletionIT.registrationRefusesWithoutConsentAndSpendsNothing`；设计件 `interaction-check` 的同意两态（已加）。

### T4 · 同意记录

- **步骤**：注册一个账号；查 `legal_consent`。
- **期望**：一行，`terms_version` / `privacy_version` 等于服务端配置的当前版本，`consented_at` 是本次时间。
- **落点**：`AccountDeletionIT.registrationRecordsTheVersionItServed`（JDBC 直查）。

### T5 · 注销入口与弹窗

- **步骤**：登录 → 账号页 → 点「注销账号」。
- **期望**：账号页底部有危险区；弹窗标题「注销账号」，正文说明后果；用户名、密码两个框；默认焦点在取消；两项没填满时确认不可用。
- **落点**：控制台组件测试 + 设计件 `interaction-check`（已加）。

### T6 · 凭据不对

- **步骤**：弹窗里填对用户名、错密码；再填错用户名、对密码；各确认一次。
- **期望**：两次都不注销；弹窗不关，正文下出现「没有完成：用户名或密码不对。」；账号仍可登录；次数计入按账号频控。
- **落点**：`AccountDeletionIT.aWrongUsernameOrPasswordDeletesNothing` + 控制台测试。

### T7 · 成功注销后的立即效果

- **前置**：一个账号，至少一条 skill、一把 API 密钥、两个浏览器会话。
- **步骤**：在会话 A 注销；刷新会话 B；用原手机号+密码登录；用注销前的 key 调 `/api/v1`；另一个账号 `invoke` 该命名空间的 skill。
- **期望**：会话 A/B 都失效；登录得到 `account_deleted`（「这个账号已注销。」）；key 得到未授权；另一个账号得到 skill 不存在/不可用。
- **落点**：`AccountDeletionIT`（第二会话用 `secondSignedInBrowser`；key 用注销前签发的真实 key 调 `/api/v1/skills` 断言 401）+ 手工真栈一次。

### T8 · 取消与关闭

- **步骤**：打开弹窗，点取消；再打开，按 Esc。
- **期望**：两种都不改任何状态；账号仍可登录，skill 仍在。
- **落点**：控制台组件测试。

### T9 · 30 天内不能重注册

- **步骤**：注销后立即用原手机号注册；用原用户名注册。
- **期望**：手机号 `already_registered`；用户名 `already_taken`。
- **落点**：`AccountDeletionIT` 直接调 `AccountRegistrar.register`（跳过短信那一层，断言的是审核这道守卫本身；同号同名的 UNIQUE 与保留行是机制）。

### T10 · 期满清理

- **前置**：把保留期配成短值（测试属性，例如 1 秒）；造一个已注销账号，带个人命名空间里的 skill、文件和 blob；另在**别人的命名空间**里留一条他创建的 skill 与一版。
- **步骤**：跑清理任务；查 `app_user`、`namespace`、`skill`、`version_file`、`credential`、`legal_consent`、`phone_verification`、`blob_deletion`；再用原手机号注册。
- **期望**：个人命名空间、skill、版本、凭据、同意记录都不在；别人命名空间里的那条 skill 与版本仍在，作者引用仍指向保留的 id；**别人命名空间里的个人行也不在**（`skill_grant(grantee)`、`skill_library_join`、`skill_invitation(invitee)`、`skill_access_request(requester)`、`my_tag_definition`、`skill_usage_day`）；`app_user` 行**还在**，手机号与 email 为空、handle 是 `deleted-<id>`、显示名「已注销账号」、`purged_at` 已写；`dueForPurge` 不再选中它；未被其他版本引用的 blob 进 `blob_deletion`；原手机号与用户名可重新注册。
- **落点**：`AccountDeletionIT.thePurgeDeletesTheKeptDataAndDeidentifiesTheRow`（短保留期 + 直接调任务）与 `AccountDeletionIT.thePurgeDoesNotSelectTheSameAccountTwice`。

### T11 · 保留期口径与实现一致

- **步骤**：对照三处——隐私政策文本、`SkillmasterProperties` 的保留期配置、生产配置。
- **期望**：主数据 30 天；日志 6 个月（nginx `rotate 180`、journald `MaxRetentionSec=6month` 都要在位）；RDS 自动备份 7 天（`DescribeBackupPolicy`，2026-11-09 核为 7）。
- **落点**：上线检查单 + 服务端配置测试。

### T12 · 安全承诺

- **步骤**：读 `/privacy` 第四节「我们如何保护你的信息」与第五节「我们与谁共享」。
- **期望**：§四四条都在——TLS/BCrypt/HMAC/AES + 凭据 KMS 加密、关键操作留痕与内部访问受控、安全事件告知；§五写明**不出售、不用于广告**，以及五家受托方各自的目的（阿里云短信、OSS、RDS、DashScope、华为云 KMS）。
- **落点**：站点组件测试（文案存在）+ 人读一次。

### T13 · 已注销账号的登录文案

- **步骤**：用已注销账号的正确手机号与密码登录。
- **期望**：不是「手机号或密码不对」，而是「这个账号已注销。」；错误码 `account_deleted`。
- **落点**：新增的注销集成测试 + 控制台测试。

### T14 · 别人命名空间里的贡献保留

- **前置**：**今天的真实路径还走不到这一步**——普通用户不能在别人的命名空间建 skill（待办里的「命名空间成员可写」）。测试用夹具直接造：B 拥有的命名空间、A 是成员，其中一条 `created_by` 指向 A 的 skill 与一版；A 注销。
- **步骤**：A 注销 30 天后跑清理；用 B 读那条 skill 的信息与版本；对 A 调 `handlesOf`。
- **期望**：skill 与版本仍在；`handlesOf` 对 A 返回「已注销账号」，不是 `deleted-<id>`；A 在该命名空间的成员关系已删，B 自己的访问不受影响。
- **落点**：`AccountDeletionIT.aContributionInSomebodyElsesNamespaceSurvivesAndReadsAsADeletedAccount`（夹具造 B 的命名空间、A 的成员关系与 skill；待那条待办落地后补真实路径）。

### T15 · 分享/入库的接收者收到通知

- **前置**：A 把个人命名空间里的 skill 分享给 B，并让 C 入库；A 注销。
- **步骤**：看 B 与 C 的通知列表。
- **期望**：各有一条「作者已注销，skill 不可用」；带 skill 名；状态是 `info` 而不是待处理；30 天后 skill 物理删除时通知随之消失（可接受）。
- **落点**：`AccountDeletionIT.skillsGoInvisibleAndEveryoneUsingThemIsTold`（通知行）+ `NotificationViewAssembler` 的状态映射单测。

## 环境与数据

- **本地库**：Docker Postgres（`docker exec skillmaster-postgres`），本 worktree 用 `skillmaster_v54` / `skillmaster_test_v54`；测试库每次重建 schema。
- **服务端**：`cd skillmaster-server && ./mvnw verify`（单元 + 集成；集成测试用 `SKILLMASTER_TEST_DB_*`）。
- **控制台**：`npm test`、`npm run type-check`。
- **官网**：`npm test`、`npm run type-check`。
- **设计件**：`cd design && npm run build:home && npm run build:console`，再在仓库根目录跑 `node design/scripts/home-check.mjs "design/skillmaster-首页演示.html" artifacts/design/home` 与 `node design/scripts/interaction-check.mjs "design/skillmaster-控制台演示.html" artifacts/design/interactions`。通知页与账号页没有 `console-states` 组，不跑它。
- **真栈手工**：起服务端 + 控制台，造一个账号、一条 skill、一把 key；按 T1、T7、T11 各走一遍。删除后 30 天要等，T10 用短保留期代替。
- **数据清理**：测试库可丢弃；真栈手工账号用同一个注销接口清掉。

## 结果

- **设计件（2026-11-10，已执行）**：`home-check`（`design/skillmaster-首页演示.html`）0 失败——法务页可滚动、页脚运营主体、占位链接 5 条；`interaction-check`（`design/skillmaster-控制台演示.html`）0 失败——注册同意两态、注销成功/失败/取消、**注销弹窗默认焦点在取消**；`tsc --noEmit` 0 错误，home 与 console 两个演示件都重新构建。合并 `main` 后 `home-check` 的一条陈旧断言暴露出来：首页复制按钮静止时写「复制指令」（voice-spec 的 v54 口径），脚本却在等「复制」；已按文案改脚本，不是改产品。**通知页与账号页没有 `console-states` 截图组**（它的组是 auth/keys/list/detail/settings/layout 等），这两屏靠 `interaction-check` 与产品组件测试覆盖。
- **服务端（2026-11-10，已执行）**：`./mvnw verify -Dit.test='AccountDeletionIT,WebRegistrationIT'`（测试库 `skillmaster_test_v54`，Flyway 到 V44）。`AccountDeletionIT` **12 条全过**：注册缺同意 400 且不消耗验证码、同意版本落库、两种凭据错误、注销立即生效且两个会话都失效、注销前的 API key 之后的请求 401、`account_deleted` 只在密码正确时出现、skill 立即下线且接收者各收一条通知、第二次注销不重复发通知、30 天内不能重注册（手机号与用户名两条分支）、30 天清理后个人数据与内容删除/账号行去标识化/别人命名空间里的个人关系行删除/手机号盲索引释放、清理过的账号不再被选中且只写一条审计、别人命名空间里的贡献保留且 `handlesOf` 返回「已注销账号」。`WebRegistrationIT` 17 条全过（原有用例补 `consent: true`）。`TableOwnershipTest` 把 `legal_consent` 记为 M1 的表；surefire **471 条**随 `verify` 全过。
- **控制台（2026-11-10，已执行）**：合并 `main` 后 `vitest run` 43 个文件 **726 条全过**，含注销成功/凭据错误文案、注册同意在第一步且不发短信、法务链接指向站点源、`account_deleted` 文案与注销后 `.form-note` 中性提示、`skill_unavailable` 通知行（句子、图标不是公告的喇叭、actor 清空后的兜底）。
- **站点（2026-11-10，已执行）**：`vitest run` 4 个文件 25 条全过，含 `/privacy`、`/terms` 路由、标题、可滚动类与页脚两个入口；`tsc --noEmit` 0 错误。
- **合并与 CI（2026-11-10，已执行）**：PR #161 合并为 `e839124`。合并前 CI 的全量 `Test` 暴露了第一版修复漏掉的一类调用者：注册请求的 `consent` 原是原始 `boolean`，Jackson 3 对**字段缺失**会先映射 null → 500，`ApiKeyIT`、`WebSessionsIT`、`CliSignInIT` 三个老用例注册第二账号时炸了。改成 `Boolean` + `Boolean.TRUE.equals` 后，缺字段与 `false` 一样返回 `400 {field:"consent"}`；三个老用例补了 `consent: true`，受影响的五个 IT 本地全过，CI 全量 `Test`（702 条 IT）转绿。
- **发布（2026-11-10，已执行）**：PR #161 合并后按 `.dev-sop/release/` 手动 dispatch 三个 Deploy，均成功——服务端 `42659681328`（同一 commit 的 `Build` 产物，Flyway 到 V44，机内健康探针通过）、控制台 `42660776370`、官网 `42659695238`。公网复核：`/privacy` 与 `/terms` 都 200，线上站点 bundle 含两份文本与运营主体；控制台 bundle 含同意提示、注销入口与「账号已注销」；`/web/session` 未登录返回 401 JSON。发布中另修了 deploy 的 run 查询（[PR #162](https://github.com/lijinzhao86/skillmaster/pull/162)）：`status=success` 索引对刚完成的 run 滞后，导致 console 拒绝了一个已存在的绿色构建。
- **仍未覆盖**：T11 的 nginx/journald 轮转（生产仍是 `rotate 14`，本机没有主机 SSH 权限）与 KMS 环境下的凭据清理；真栈手工 T1/T7/T11 也未在本机走一遍。

## 已知问题

| 问题 | 状态 | 说明 |
|---|---|---|
| 演示件 900px 窗口里注册按钮会被底部演示条挡住 | 已复现，仅限演示件 | 加了同意勾选后表单变高；产品浮层会滚动，真实用户不受影响；测试里先把按钮滚到中间再点 |
| 生产访问日志今天只留 14 天 | 已核实，属于本版要改的部署项 | `/etc/logrotate.d/nginx` 的 `rotate 14` 必须改成 `rotate 180`；journald 加 `MaxRetentionSec=6month` |
| 文本版本与同意记录版本的权威分两处 | 开放问题 Q2 | 服务端配置是记录权威，页面常量要同版改；发版清单核对 |
| 注销与进行中的发布并发时，一个新 skill 可能不被立即下架 | 已核实，接受（medium） | 注销先改账号状态、再批量下架；一个在两步之间提交的发布能留下一条可见 skill，直到 30 天清理。窗口极窄，清理会收掉；要彻底关闭需给写路径加账号状态检查，留到「命名空间成员可写」那版一起做 |
| 会话撤销在事务提交之后，进程在两者之间退出会留下一个可用会话 | 已核实，接受（medium） | 与密码重置同一先例；API 面另有 `isActive` 每请求检查，网页会话靠撤销。彻底关闭需给 `/web/**` 加逐请求账号状态检查，影响面大于本版 |
| `audit_event` 不随清理删除，注册审计里留着操作时的 handle | 已核实，接受（low） | 审计是安全留痕，不能为了 30 天删除而抹掉；隐私政策把它归在「关键操作留痕」而非个人资料。若要缩短，需另定审计保留期 |
