# v46-account-identity · 技术设计

> **最后更新**：2026-11-05
> **状态**：已实现（设计已落地；验证结果见 [`test-plan.md`](test-plan.md) §结果）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 ADR**：[ADR 0141](../../decisions/0141-a-logout-stops-on-a-guide-screen.md)、[ADR 0142](../../decisions/0142-cli-logout-ends-its-own-server-login.md)。本文只链接，不复述理由。

## 架构

v40 起本文只写相对 [`docs/current/`](../../current/) 的差异；模块表与依赖规则不在这里重抄。

```
浏览器（控制台）                         服务端                          CLI
─────────────────────────────────────────────────────────────────────────────
授权页 ── 读 useSession().account.username
   │
   ├─「换一个账号」── POST /web/logout（现有）──> 浏览器会话结束
   │                    └─ /login?return_to=<授权页>（现有）──> 登成 B ──> 回到授权页
   │
退出页 ── POST /web/logout（现有）──> 浏览器会话结束 ──> 静态引导 + 复制一句话

终端 login ── POST /cli-sign-ins/exchange（现有）──> 凭据 + username ──> 终端打印
终端 logout ── POST /api/v1/logout（新增）──> CliSignIn.revokeSelf
                                             └─ TokenStore.revokeAuthorization（现有）
```

**划分**：

- **浏览器侧只改界面**：授权页的身份卡片与换账号走现有的 `/web/logout` + `/login?return_to`；退出页在挂载时发一次现有的 `POST /web/logout`，成功后是静态引导（复制本身不发请求）。没有新的浏览器接口。
- **API 侧新增一个端点** `POST /api/v1/logout`：用出示的凭据自身认证，结束**它所属的那次**登录；不接受 id 参数。鉴权走现有的 API 链，并为这条路由单独加一条 `authenticated()`（在任何 scope 规则之前）——结束自己的登录与资源权限无关，不需要 scope。
- **Token 模块**：`AuthenticatedSubject` 增加 `authorizationId`（OAuth 令牌时有值；会话与 API 密钥为 null）；`IssuedTokenValidator` 从 `access_token.authorization_id` 读出它。`CliSignIn` 增加 `revokeSelf`，复用 `TokenStore.revokeAuthorization`，trigger 记为 `cli`。
- **CLI**：`login` 从交换响应多读一个 `username` 并打印；`logout` 只在「这是一次 CLI 登录」时调用新端点，API 密钥与旧版遗留凭据保持原有行为。

## 数据模型

**不改表、不改列。** 这次用到的都是已有结构：

| 已有结构 | 本版的用法 |
|---|---|
| `oauth_authorization.revoked_at` | 结束一次 CLI 登录的现有标记；`revokeSelf` 复用 `TokenStore.revokeAuthorization`，trigger 写 `cli` |
| `access_token.authorization_id` | V5 加列、V6 建父表与 FK；`IssuedTokenValidator` 把它读进 subject 的 `authorizationId`，让端点知道「这次登录」是哪一行 |
| `oauth_authorization.principal_name` | 交换时已有；`CliSignIn.Credential` 增加 `userId`，供控制器解析账号名 |

**Java 侧的记录变化**（不是表）：

- `AuthenticatedSubject` 从 `(userId, scopes, apiKeyId)` 变成 `(userId, scopes, apiKeyId, authorizationId)`；工厂 `ofSession` / `ofApiKey` / `ofOAuthToken` 都保持语义，`ofOAuthToken` 开始要求 authorization id。
- `CliSignIn.Credential` 增加 `userId`；`CliSignInCredentialResponse` 增加 `username`（只读字段，不落库）。

**约束**：`authorizationId` 对 OAuth 令牌必填、对会话与 API 密钥必须为 null——与 `apiKeyId` 的「必填 / 必空」同一写法；实现里用工厂方法守住，不靠调用方自觉。

## 接口

### 新增：结束这次 CLI 登录

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `POST` | `/api/v1/logout` | 结束出示的凭据所属的那次 CLI 登录 | Bearer 令牌（CLI 登录凭据），不需要 scope |

- **入参**：无。**没有 id**——凭据自己回答「我是哪次登录」。
- **出参**：`204 No Content`。
- **错误**：
  - `401`：令牌无效、已结束、过期、闲置或账号停用——现有 `TokenValidation` 的五个理由，原样返回。**已经结束的凭据再调一次得到 `token_revoked`，不是 204**：验证器在控制器之前就拒绝了它。CLI 把 `token_revoked` / `token_expired` / `token_idle` 都当成「已经结束」，所以命令这一层仍是幂等的。
  - `400 invalid_request`：出示的是 API 密钥（`authorizationId` 为 null）。句子说明「这条路由结束一次 CLI 登录；API 密钥没有可结束的登录」。CLI 不会走这里（A12）。**浏览器会话到不了这个控制器**：API 链只认 Bearer 令牌，会话 cookie 在这一层先得到 401。
- **归属**：`authorizationId` 来自令牌行，不来自请求体；端点无法结束别人的登录。

### 交换响应加一个只读字段

| 位置 | 字段 | 形状 | 兼容 |
|---|---|---|---|
| `CliSignInCredentialResponse` | `username` | 账号 handle（与 `/me` 的 `user.username` 同一个值） | 旧服务端没有该字段；新 CLI 缺它就省略「已登录为」那一句（A6） |

**为什么是响应字段，而不是登录后再调 `/me`**：交换是唯一一次「凭据与账号同时在一处」的机会；再调一次多一个请求和一个失败态，而失败时终端已经登录成功、却没有账号名可打。

### 不改的接口

- `POST /web/logout`、`POST /web/cli-sign-ins/{id}/revoke`、`CliSignIn.revoke`（控制台用）都不变。
- 授权页换账号复用 `/web/logout` + `/login?return_to=<授权页完整地址>`；登录页的 `returnTo()` 已经做同源校验。
- 退出页不新增接口；复制按钮只写剪贴板。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 设计件（已完成）+ 控制台授权页身份卡片/换账号 + 退出引导页 | A1、A2、A4、A5、A7–A10 |
| P1 | 交换响应 `username` + CLI `login` 打印账号 | A3、A6 |
| P2 | `POST /api/v1/logout` + `CliSignIn` 新增的 `revokeSelf` + CLI `logout` 调用 | A11–A13 |

**每期明确不做**：P0 不改 CLI 与服务端；P1 不改 `logout`；P2 不再改授权页与退出页。三期在同一个版本、同一个 PR 里交付；分期只是实现顺序，不是发布单位。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | `authorizationId` 放进 `AuthenticatedSubject`，还是由安全过滤器放成 request attribute？ | 放进 subject：它是这份凭据的身份，验证器已经读到了那一列；与 `apiKeyId` 同一写法 | 实现后跑 `CliSignInIT` 与 `MeController` 的既有测试；若 subject 的构造扩散过大，退回 request attribute |
| Q2 | 服务端撤销失败时 `skmr logout` 的退出码？ | `0` + 警告：本机清除成功，命令的合同是「这台机器不再持有凭据」；`login` 的 `revokeReplaced` 对失败的撤销也只警告 | CLI 单元测试断言退出码与句子；A13 |
| Q3 | 令牌已经结束（`token_revoked` / `token_expired` / `token_idle`）时，`logout` 算成功还是失败？ | 算「已经结束」：目标状态已达到，不打印控制台地址 | CLI 测试用分别返回这三种 401 的假服务端 |
| Q4 | 本机存的是登录凭据、但环境里有一把 API key 覆盖它时，撤不撤本机那份登录？ | 撤：被清除的是本机那份登录；环境里的 key 不受影响，仍按现有句子提示 | CLI 测试矩阵：env key + stored login |
| Q5 | `username` 放交换响应，还是登录后调 `/me`？ | 放响应：一次请求、原子；A6 覆盖旧服务端 | 实现后跑 A6 的兼容测试 |

**实现后按倾向全部落地**，没有一条需要改方向：身份随 subject 走（Q1）、撤销失败退出码 0（Q2）、已结束按已结束处理（Q3）、环境 key 不影响撤销本机登录（Q4）、账号名随交换响应到达（Q5）。证据在 [`test-plan.md`](test-plan.md) §结果 T4、T5、T8、T13、T14–T18。

## 附录 A · 与现有代码的关系

**服务端**

| 文件 / 符号 | 改什么 |
|---|---|
| `AuthenticatedSubject` | record 增加 `authorizationId`；三个工厂保持语义，`ofOAuthToken` 要求非空 id |
| `IssuedTokenValidator`（`validateIssuedToken`、`AccessTokenRow`、`readAccessToken`） | SELECT 增加 `t.authorization_id`；构造 subject 时传入 |
| `CliSignIn` | 增加 `revokeSelf(userId, authorizationId)`；`Credential` 增加 `userId` |
| `PgCliSignIns` | `exchange` 的 `Credential` 带 `userId`；`revokeSelf` 复用 `TokenStore.revokeAuthorization`，trigger 为 `cli`；现有 `revoke` 保持 `console` |
| 新增 `LogoutController`（`api`） | `POST /api/v1/logout`；`authorizationId` 为 null 时 400，否则调 `revokeSelf`，答 204 |
| `CliSignInCredentialResponse`、`CliSignInExchangeController` | 响应加 `username`；控制器用 `AccountDirectory.handleOf(userId)` 解析，解析失败时省略该字段（凭据已经铸造，不能因此变成 500），并记一条 warn |
| `SecurityConfig` | API 链里为 `POST /api/v1/logout` 单独加一条 `authenticated()`（在写规则之前）；`TokenSecurityConfig` 不改 |

**CLI**

| 文件 / 符号 | 改什么 |
|---|---|
| `internal/api` | 新增 `Client.Logout(ctx)`：`POST /api/v1/logout`，`postJSON` 已接受 204 |
| `internal/auth/login.go`（`Credential`、交换响应结构） | 增加 `Username`，缺字段时为空 |
| `cmd/skillmaster/auth.go`（`saveAndReport`） | `Username` 非空时打印「已登录为 <handle>」 |
| `cmd/skillmaster/auth_logout.go` | 只对 v4 CLI 登录凭据调用 `Logout`（5s 超时）；成功说「服务端那份也已结束」，失败说清楚并保留控制台地址；API key / 遗留凭据不改 |
| `cmd/skillmaster/declarations.go`、`README.md` | `logout` 的帮助与 README 说清「登录凭据结束服务端那次登录，key 只清本机」；`docs/current/cli.md` 重新生成 |

**控制台**

| 文件 | 改什么 |
|---|---|
| `CliSignInPage.tsx` | 账号卡片（头像 + handle + 「授权将发给这个账号。」）+ 「不是我，换一个账号」（logout + `loginPath()`） |
| `LogoutPage.tsx`、`App.tsx` | 退出引导页；`/logout` 从「无屏重定向」变成「一屏 + `blankWhileLoading`」；两个出口 |
| `tests/cli-sign-in.test.tsx`、`tests/logout-page.test.tsx` | 新文案、换账号流程、复制按钮与两个出口、失败态 |

**设计件**：`design/` 已在第 2 关改完；`design/README.md` 的授权请求与退出引导两节按约定最后补写。

## 附录 B · 来源与出处

- [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §决定 7（API 密钥仍只清本机）
- [ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md)（登录凭据是一台登录设备）
- [ADR 0141](../../decisions/0141-a-logout-stops-on-a-guide-screen.md)（退出后停一页引导）
- [ADR 0142](../../decisions/0142-cli-logout-ends-its-own-server-login.md)（`skmr logout` 结束服务端这次登录）

## 附录 C · 已测量的数据

无。本版没有需要量化的性能或规模指标。
