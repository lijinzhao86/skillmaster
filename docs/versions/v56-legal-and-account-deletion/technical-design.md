# v56-legal-and-account-deletion · 技术设计

> **最后更新**：2026-11-10
> **状态**：已实现（服务端/控制台/站点测试通过；待合并与预发）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文只链接、不复述理由。

## 架构

```
www（skillmaster-site）
  /privacy, /terms ──► LegalPage（静态文本，无登录）
  页脚 ──────────────► 同一站点的两条路径
  nginx: try_files $uri /index.html（deploy/nginx/skillmaster.conf）

console（skillmaster-console）
  注册 ──► POST /web/register { …, consent: true }
  账号页 ─► POST /web/account/delete { username, password }
  登录页 ◄─ ?deleted=1 显示「账号已注销。」

server（skillmaster-server）
  WebAccountController
    ├─ RegisterAccountUseCase ──► M1 账号 + M4 个人命名空间 + legal_consent
    ├─ LoginUseCase ────────────► M1 认证（新增 deleted 结果）
    └─ DeleteAccountUseCase ────► M1 状态 + M2 密钥 + 会话 + M12 skill 下架 + M14 通知 + M10 审计
  AccountPurgeJob（每日）──► 到期后物理删除账号及其关联数据，并交 blob 给 M6
```

**边界**

- **服务端是注销的唯一执行者**：客户端只提交用户名与密码，不告诉服务端「删什么」。会话、密钥、skill、blob 的处置都在一个用例里编排；跨模块事务由用例层划定（`docs/current/rules.md` 规则②，先例是 `usecase/RegisterAccountUseCase.java`）。
- **www 的文本是静态内容**：法务页不依赖登录态、不依赖服务端接口；`/privacy` 与 `/terms` 由 SPA 路由，nginx 已有的 `try_files $uri /index.html` 负责把深链交给 `index.html`。
- **控制台只做展示与提交**：`POST /web/account/delete` 返回后，控制台清掉本地会话痕迹并跳到 `/login?deleted=1`；「已注销」这句话由登录页读查询参数显示，不依赖服务端再查一次。
- **清理任务与请求路径分开**：注销请求只做「立即生效」的部分；物理删除由每日任务按保留期执行，不在请求里删大对象。

**与 `docs/current/` 的关系**：本文只写这一版的 delta；账号、令牌、命名空间、skill、blob 的现状契约在 [`current/model.md`](../../current/model.md)、[`current/schema.md`](../../current/schema.md) 与各模块文档里。

## 数据模型

新增迁移 `V43__legal_consent_and_account_deletion.sql`（Flyway 当前最后一个是 `V42__service_credentials.sql`）。

```sql
-- 注销时间：状态仍是 app_user 已有的 status，另加两个时间给清理任务用。
ALTER TABLE app_user ADD COLUMN deleted_at TEXT;
-- 清理跑过的时刻。没有它，dueForPurge 会每天重新选中同一个已清理账号。
ALTER TABLE app_user ADD COLUMN purged_at TEXT;
CREATE INDEX app_user_deletion_due ON app_user (deleted_at) WHERE status = 'deleted';

-- 注册时同意过哪一版文本。一个账号一行，两个版本分开存。
CREATE TABLE legal_consent (
  user_id         TEXT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  terms_version   TEXT NOT NULL,
  privacy_version TEXT NOT NULL,
  consented_at    TEXT NOT NULL
);
```

- **不加新的状态枚举**：`app_user.status` 已经在 `AccountRepository` / `AccountService` 里表达「能不能行动」，`deleted` 只是第三个值；`AccountDirectory.isActive` 已经把它挡在外面，不需要第二个开关。
- **账号行保留、去标识化，不物理删除**：别人命名空间里的内容与授权行指向它；删行会挡住外键或抹掉别人的历史。个人数据（手机号、密码、凭据、同意记录、通知）全部删掉，留下的只有 `id`、`status`、`deleted_at`、`display_name` 与一个 `deleted-<id>` 的占位 handle。这是**去标识化**，不是匿名化，文本要如实写。
- **`legal_consent` 一行一个账号**：两个版本在同一行，因为它们在同一次勾选里被同意；将来条款改版要重新同意时，再加一行会需要 `consent_at` 历史，那是新版本的事（见 Q2）。
- **`namespace` 不加 `deleted_at`**：注销后该命名空间下的 skill 立即置 `skill.deleted_at`，命名空间本身留着到清理任务。不提前释放 `namespace.slug`，因为 `UNIQUE(handle)` 与 `UNIQUE(slug)` 是「30 天内不能重新注册」的实现方式。
- **`phone_verification` 没有外键**：清理任务要按 `phone_hash` 单独删除，不能指望级联。
- **不写唯一索引的例外**：`UNIQUE(phone_hash)` 与 `UNIQUE(handle)` 不动；保留期内的重新注册由它们自然拒绝。

**清理任务的数据顺序**（每一步为什么不能换）：

1. 按 `(status='deleted' AND deleted_at <= now - retention AND purged_at IS NULL)` 找到账号。比较用 `<=`：保留期恰好跑满的那一刻就该清理，`<` 会多等一天。
2. 删 `namespace`（级联 `skill` / `skill_version` / `version_file` / `skill_grant` / `skill_library_join` / `skill_embedding` 等）。个人命名空间里的内容——**包括已分享的**——在这里一起走。
3. 删这个账号的个人行：`credential`、`api_key`、`namespace_member`、`notification_recipient`、`service_credential`、`credential_share(grantee_user_id)`、`legal_consent`、`identity`；再删 `phone_verification WHERE phone_hash = …`（它没有外键）。
   **别人命名空间里的个人行也要删**，命名空间级联够不着：`skill_library_join(user_id)`、`skill_grant(grantee_id)`、`skill_invitation(invitee_id)`、`skill_access_request(requester_id)`、`my_tag_definition(user_id)`、`skill_usage_day(account_id)`。它们是「谁收过什么、谁分享给他、他申请过什么」——隐私政策把它算个人数据，30 天后就不该再以可链接的 id 出现。
4. **把 `app_user` 行去标识化，不删**：`phone_hash` / `phone_enc` / `email` 置空，`handle` 改成 `deleted-<id>`，`display_name` 写「已注销账号」，`purged_at` 写清理时刻，`status` 仍是 `deleted`；留下的列是 `id`、`status`、`created_at`、`deleted_at`、`purged_at`、`display_name` 与占位 handle。别人命名空间里的 `skill.created_by`、`skill_version.submitted_by`、`skill_grant.granted_by`、`skill_invitation.invited_by` 留着指向它——这是别人历史不丢的前提（[ADR 0175](../../decisions/0175-account-deletion-is-immediate-with-a-fixed-retention.md)）。
5. 跑一次 blob 回收：由 M7 给出仍被 `version_file` 引用的 sha 集合，交给 `BlobStore.deleteUnreferenced(referenced, cutoff)`。它已经有「按引用计数删、带十分钟宽限、写 `blob_deletion` 交给 `BlobDeletionDrain`」的整套语义，注销清理只是它的一个调用者，不新开接口。

**显示层**：`AccountRepository.handlesOf` 对 `status='deleted'` 返回常量「已注销账号」，不再返回 handle 或 `deleted-<id>`（30 天保留期内 `display_name` 还是空的，读列值不够）；`handleOf` 仍返回真实 handle 给内部寻址用。这个改动只影响展示，不影响权限。

**注销请求里的立即处置**（事务内，除会话外）：

- **先过一道幂等闸**：`app_user.status` 从 `active` 改成 `deleted` 是一次条件更新，只有第一次成功。第二次请求（双击、重试、两个标签页）拿到「已经注销」就只回成功，不再发通知、不再写审计——否则接收者会收到重复的「作者已注销」。
- `app_user.status='deleted'`、`deleted_at=now`（保留第一次的 `deleted_at`，保留期从第一次算）
- 该账号拥有的 namespace 下所有 `skill.deleted_at=now`（内容立即下架；`skill` 的读取路径已经尊重 `deleted_at`）
- 该账号全部 `api_key.revoked_at=now`（`ApiKeyRegistry` 已有按用户撤销的写法可复用）
- 给这些 skill 上的 `skill_grant.grantee_id` 与 `skill_library_join.user_id` 各发一条通知（M14）：作者已注销，skill 不可用。通知带 `skill_id`；30 天后 skill 物理删除时随外键级联消失
- 写一条 `audit_event`（`account_delete`），actor 是本人，target 是本人
- 事务提交后调用 `WebSessionRegistry.revokeAllFor(userId)`（Spring Session 的写不在同一个事务里，见 `SpringSessionRegistry` 的注释）
- 不在请求里删 blob、不删 skill 行、不改作者引用

## 接口

### 注册：多一个同意位

`POST /web/register`（`WebAccountController` 的 `RegistrationRequest`）

```json
{ "phone": "13900139000", "code": "123456", "password": "…", "username": "newuser01", "consent": true }
```

- `consent` 不是 `true` → `400`，形状与现有字段错误一致：`{ "field": "consent", "issue": "required" }`。
- 通过后，`RegisterAccountUseCase` 在同一事务里写 `legal_consent`，版本取服务端配置里的两个版本号（实现时加进 `SkillmasterProperties` 的账号组），不信任客户端传的版本号。
- **服务端拒绝是必需的**：前端勾选只是提示，绕过前端直接调接口也必须拒绝。

### 注销：只在浏览器面

`POST /web/account/delete`（新增 `WebRoutes.DELETE_ACCOUNT = "/account/delete"`）

```json
{ "username": "newuser01", "password": "…" }
```

- 鉴权：浏览器会话 + CSRF（`/web/**` 既有规则）。
- 用户名必须等于当前会话账号的 handle；密码必须通过 BCrypt 比对。任一不对 → `401 invalid_credentials`（与登录同一个码，不额外泄露）。
- 成功 → `204 No Content`；客户端随后跳 `/login?deleted=1`。
- 已注销的账号不应该还能进来：`AccountDirectory.isActive` 已经在请求路径上挡着；如果会话还在但账号已 `deleted`，同样拒绝。
- **不加 CLI 路由**：ADR 0081 的高风险例外，PRD 的「不做」已记。

### 登录：已注销与密码错误分开

`POST /web/login`

- 只有**密码比对通过、且账号状态是 `deleted`** 时，返回 `401` + `{ "code": "account_deleted" }`；密码不对仍然 `invalid_credentials`。
- 新增 `ErrorCode.ACCOUNT_DELETED("account_deleted")`。它与 `INVALID_CREDENTIALS` 的边界写在错误码注释里：拿到这个回答的人已经知道密码，所以不构成账号存在性预言机。
- 控制台的登录页把 `account_deleted` 显示成「这个账号已注销。」；`?deleted=1` 的中性提示是注销成功后立即看到的。两者都走 `FormBanner`，靠 `note` 区分：中性提示是 `.form-note` + `role="status"`，错误是 `.banner` + `role="alert"`。

### 法务页面：静态路由

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `GET` | `/privacy` | 《隐私政策》 | 无 |
| `GET` | `/terms` | 《服务条款》 | 无 |

- 两条都是 SPA 路由；nginx 已有 fallback，不新增 location。
- 文本住在 `skillmaster-site/src/legal/legalText.ts`（两份文档同一模块；正文与设计件 `design/src/home/legalText.ts` 逐字一致，只有文件头注释各指对方），页面组件只负责排版；两份要一起改。
- 每页 `<title>` 与 `document.title` 各自设置；不引入新的 meta 框架。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 法务两页 + 页脚链接；注册同意（前后端）；注销接口、立即生效、清理任务；日志留存调到 6 个月；`V43` 迁移；ADR 0175 | PRD 验收 #1–#15 |
| P0 明确不做 | 数据导出、冷静期、CLI 注销、英文版、Cookie 横幅、未成年人独立流程、文本改版后的重新同意 | PRD「不做」#1–#12 |
| P1（不在本版） | 人侧使用说明书（待办里的「面向人的使用说明书」）复述安全承诺；举报与反馈入口（待办里的同名条目） | 各自版本 |

**P0 的部署项**（代码之外，必须随上线做）：

- `/etc/logrotate.d/nginx`：`rotate 14` → `rotate 180`（每日轮转，约 6 个月）。
- journald：加一个 drop-in，`MaxRetentionSec=6month` 且 `SystemMaxUse` 足够放下 6 个月（按当前约 3 MB/天估，1 GB 有余量）。
- RDS 自动备份当前 7 天（`DescribeBackupPolicy`，2026-11-09），不需要改；文本写「备份最多 7 天」。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 注销密码尝试的频控阈值定多少？ | **已定并实现：5 次 / 小时，按 user 计数，在验密码之前**（`PgAuthThrottle.Rule.ACCOUNT_DELETE`，2026-11-10 关） | 已实现；第 6 次 `429`，窗口过后恢复 |
| Q2 | 文本版本与同意记录版本由谁做权威？ | 服务端配置（`skillmaster.account.terms-version` / `privacy-version`）是同意记录的权威；页面显示自己的常量 `1.0`。两者在同一版发布时一起改，发版清单加一条核对 | 把「页面常量 = 服务端配置」写进 release 检查；若第二次改版仍靠人记，考虑让页面读 `GET /web/legal/versions` |
| Q3 | 注销账号的 blob 回收范围怎么定？ | 只回收「这次删除后不再被任何 `version_file` 引用」的 blob；内容寻址可能与他人共享，所以按引用计数判断，不按归属 | 在清理任务里跑一次 `SELECT sha256 FROM blob WHERE NOT EXISTS (…)` 的限量批次；先测共享 blob 不被误删 |
| Q4 | 「作者已注销」通知是一条一个 skill，还是合并成一条？ | 一条一个 skill：接收者才知道是哪个地址不能用了；同一作者多个 skill 会产生多条，通知列表允许 | 实现后看通知页的可读性；如果刷屏，再合并成一条带清单 |
| Q5 | 30 天保留期内，运营能不能看这些账号的数据？ | 产品面一律看不到（登录、skill、API 都被挡）；库层面只有清理任务与法律要求时可查，且要留痕 | 写进内部操作口径；不新增后台入口 |
| Q6 | 去标识化的账号行要永久保留吗？ | 是：它只承载别人的作者引用，没有手机号、密码、凭据；这是 [ADR 0175](../../decisions/0175-account-deletion-is-immediate-with-a-fixed-retention.md) 的代价，文本已披露 | 上线后抽查一次：`status='deleted'` 的行里没有个人信息；有泄漏再加清理任务 |
