# 0002 · M01 落地：注册 / 登录 / 登出 / 密码重置走通

> **日期**：2026-10-01
> **所属版本**：[`../README.md`](../README.md)
> **类型**：小版本迭代——**未改变对外可验收的能力边界**

## 改了什么

### 1. 服务端：新增迁移 `V3__account_login.sql`

**没有改 `V1`**，虽然 TD §7 的 P1 原先写的是「直接改 `V1__baseline.sql`（沿用 P0c 的先例）」。那句话是错的，已订正：V1 已合进 `main` 且在本机应用过，Flyway 按校验和拒跑，改它等于要求每台已有库重建一次。P0c 的先例不适用——那次是同一次提交里改的，还没被别人拉过。

| 变更 | 说明 |
|---|---|
| `app_user` 加 `phone_hash` / `phone_enc` | 可空（V2 seed 的三个用户没有手机号、也永远登不进来），`CHECK` 保证两列配对 |
| `COMMENT ON COLUMN app_user.handle` 重写 | 原文是 "Login name"，与 [ADR 0013](../../../decisions/0013-phone-login-and-username-slug.md) 冲突 |
| 新增 `phone_verification` | 短信验证码，注册与重置共表，用 `purpose` 分开 |
| 新增 `auth_throttle` | 频控计数，主键 `(scope, key_hash, window_start)` |
| **删除 `browser_session`** | 改由 Spring Session JDBC 承担，见 [ADR 0014](../../../decisions/0014-browser-session-via-spring-session.md) |
| 新增 `spring_session` / `spring_session_attributes` | 逐字抄自 `spring-session-jdbc` 4.1.1 的 `schema-postgresql.sql`（只小写化） |

`ModuleMap` 的 M1 集合随之更新（去 `browser_session`，加四张新表）。`TableOwnershipTest` 此前只认 `CREATE TABLE`，所以一份「V1 建、V3 删」的迁移会让它误判——**已改成 DROP 敏感**，并加了一条钉住这个行为的正反断言。

### 2. 服务端：M01 与浏览器面

- **模块**（`modules/account`）：`AccountRegistrar` / `PhoneVerification` / `AuthThrottle` / `WebSessionRegistry` / `PhoneCipher` / `PasswordHasher` / `SmsSender` 等公开接口，实现落在 `internal`。手机号是**盲索引 + AES-GCM 密文**两列；密码是 BCrypt（上限 72 字节，见下）。
- **用例层**：`SendVerificationCodeUseCase`、`RegisterAccountUseCase`、`LoginUseCase`、`ResetPasswordUseCase`，各自一个事务边界。
- **API**：七个 `/web/*` 端点（TD §4.4），`WebSession` 负责建立/结束会话。
- **安全接线**：`SecurityConfig` 拆成**两条链**（`/web/**` 与 `/api/v1/**`），各自显式 `@Order`。浏览器面用双提交 cookie 的 CSRF，且 401 **不发 `WWW-Authenticate`**。
- **M4**：加 `NamespaceService.createPersonalNamespace`（注册在同一个事务里建个人命名空间）。

### 3. 测试

新增 9 个集成测试类（`WebSecurityPlaneIT`、`WebCsrfIT`、`WebSessionIT`、`WebRegistrationIT`、`WebLoginIT`、`WebLogoutIT`、`WebPasswordResetIT`、`AccountThrottleIT`、`SessionSchemaOwnershipTest`）与 3 个单测类，以及两个支撑件（`Browser` 带 cookie jar 的客户端、`RecordingSmsSender` 用 `@Primary` 覆盖短信发送）。`truncate-business-tables.sql` 重写（加会话与账号那五张表，但**不加 `app_user` 与 `namespace`**——对它们做 `CASCADE` 会把 V2 的 seed 一起带走，而那正是所有读路径测试的起点）。

`clean verify` → **BUILD SUCCESS，230 个测试通过**（109 单测 + 121 集成）。

## 为什么

[`prd.md`](../prd.md) 的验收第 1 条是「注册 → 登录 → 拿到令牌」，而 M01 的设计在 [ADR 0013](../../../decisions/0013-phone-login-and-username-slug.md) 与 [`architecture/modules/M01-account-login.md`](../../../architecture/modules/M01-account-login.md) 里已经定稿，代码没跟上：`modules/account` 当时只有一个 `AccountDirectory.handleOf`，全仓库没有密码哈希、没有会话、没有 CSRF、没有短信。

### 实现期间发现并修掉的两个缺陷

| 缺陷 | 怎么发现的 |
|---|---|
| **验证码的尝试次数会被回滚掉。** 拒绝猜错是用抛异常表达的，而异常回滚了「记一次猜错」——于是六位数字可以在一分钟内枚举完，**而除了专门钉它的那一条，所有测试都是绿的**。修法是在两个用例上声明 `noRollbackFor = VerificationCodeException`；安全性来自「码的校验排在所有写入之前」，所以那一刻待提交的只有计数器 | `WebRegistrationIT.aCodeStopsBeingUsableAfterTooManyGuesses` 第一次跑就红了（预期 400 得到 201） |
| **登录成功后会清掉 CSRF cookie。** 原实现用 `csrfTokenRepository.saveToken(null, …)` 表达「轮换令牌」，而在 `CookieCsrfTokenRepository` 里 null 的语义是**删除 cookie**（`maxAge=0`）——于是登录后客户端的第一次写请求必被 403。改成保存一个新建的 `DefaultCsrfToken` | 对着 `spring-security-web-7.1.1` 的字节码核实 `saveToken` 之后发现（原先的写法是照某篇文章的「标准做法」写的） |

实现过程中另外核实了几条**只能靠读字节码确认**的框架行为，它们决定了配置的写法（CSRF 处理器必须是朴素的那个且 `setCsrfRequestAttributeName(null)`，否则第一次响应不写 cookie；`CsrfFilter` 自己调 `AccessDeniedHandler`，所以 CSRF 失败永远是 403 而不是 401；`HttpSessionRequestCache` 会为每个匿名 401 建会话，所以关掉）。

### 4. 一条被实测证伪的「验证过了」

`spring.session.jdbc.initialize-schema: never` 是用来关掉 Boot 那个建表器的。第一次去验它时，做法是把它临时改成 `always` 再跑一遍集成测试，**结果全绿**——于是看起来「这个开关无关紧要」。

那个结论是错的，而且错得很有代表性：测试用的 Flyway 会先 `clean()` 再 `migrate()`，**第二个建表器建出来的东西被 clean 掉了，没有留下任何痕迹**。「跑一遍没坏」在这里什么也证明不了。

真正能看见它的是断言这个 bean **在不在**，而不是断言行为：

```java
@Autowired(required = false) JdbcSessionDataSourceScriptDatabaseInitializer bootSessionSchemaInitializer;
assertThat(bootSessionSchemaInitializer).isNull();
```

`SessionSchemaOwnershipTest` 就是这个断言，并且做了突变验证：把属性改成 `always` 它变红（bean 出现了），改回 `never` 它变绿。这是本轮唯一一条靠「先证伪自己的验证方法」才立住的结论。

## 影响

**已交付行为**：`/api/v1/**` 一条都没动，两条链的拆分只是加了一条更窄的、带 `@Order(1)` 的链；`browser_session` 表被删（它此前没有任何代码引用）。**`app_user` / `namespace` 的既有数据不受影响**——V3 只加列、加表、删一张没人用的表。

**文档同步**：

| 文档 | 改了什么 |
|---|---|
| [`technical-design.md`](../technical-design.md) §3.1 | `browser_session` 的 DDL 换成 `auth_throttle` + 指向 V3 的一段说明；订正「`idx_pv_lookup` 兼做频控计数」这句（计数已统一到 `auth_throttle`） |
| 同 §4.1 | 浏览器面的 401/403 约定（不发 bearer 挑战、`forbidden` 只来自 CSRF）、两条链、取第一个 CSRF 令牌的方式；错误码表加四个 |
| 同 §4.4 | `/web/*` 端点表补 `GET /web/session` 与每个端点的失败形态；实施状态写清「六个端点已实现、登录页与 `/oauth/*` 仍是 M2」 |
| 同 §7 P1 | 拆成「已完成的 M01」与「未完成的 M02」；订正「直接改 V1」那句 |
| [`test-plan.md`](../test-plan.md) | P1 账号用例从「未执行」改为已执行并逐条附证据；§结果与§已知问题补本轮 |
| [`architecture/modules/M01-account-login.md`](../../../architecture/modules/M01-account-login.md) | 拥有的表、验证码不变量、对外契约（M2 的接缝由表改成框架契约）、实现状态与已知不精确 |
| 新增 [ADR 0014](../../../decisions/0014-browser-session-via-spring-session.md) | 会话为什么交给 Spring Session、被否掉的两个选项、以及「`getName()` 必须是 userId」这条没有编译期约束的后果 |
