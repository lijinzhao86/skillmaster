# ADR 0023 · M2 用 Spring Authorization Server，但存储自带

- **状态**：已采纳
- **日期**：2026-10-02
- **依赖**：[ADR 0007](0007-self-built-oauth-as.md)（自建 AS）、[ADR 0011](0011-server-and-cli-stack.md)（Java + Spring 栈）、[ADR 0021](0021-opaque-tokens-not-jwt.md)（不透明令牌）
- **细化而非取代 0007**：0007 决定的「不接现成的独立 SSO、把 AS 做进我们自己的进程」今天仍然成立。变的是**「自建」的粒度**——协议层用框架的，存储与身份绑定自己写。

## 背景

M2 要把一个 OAuth 2.1 AS 做出来。有两条路：手写协议，或者站在成熟实现上。

**2025-09-11，Spring 把 Spring Authorization Server 迁进了 Spring Security 7.0**，独立仓库归档进 `spring-attic`。我们用的正是 **Spring Security 7.1.1——同一条版本线**，坐标 `org.springframework.security:spring-security-oauth2-authorization-server`，类名与包名不变。它免费给的：PKCE、授权码流程、刷新轮换、撤销（RFC 7009）、内省、发现端点（RFC 8414 + OIDC）、以及同意记录的持久化。

但它**默认的存储**在两处与我们已经定下的东西冲突：

1. **令牌明文入库。** `oauth2_authorization` 里存的是 `access_token_value` / `refresh_token_value` 原值，哈希是尚未实现的需求（[spring-security#19698](https://github.com/spring-projects/spring-security/issues/19698)）。原因很硬：它的 provider 用字符串 `.equals()` 比对令牌。这直接推翻 [ADR 0007](0007-self-built-oauth-as.md) 那句「数据库泄露不等于令牌泄露」。
2. **授权码、access、refresh 挤在同一张宽表** `oauth2_authorization` 里，靠 `authorization_grant_type` 区分。而我们四张表的分法，理由恰恰是这三样**寿命差着几个数量级**。

另外两条路：

- **Keycloak**：唯一**内置**重放检测的成熟实现。但它是**独立服务**——要单独部署、监控、备份、升级，多一个公网暴露面；而 [ADR 0007](0007-self-built-oauth-as.md) 选的是「AS 与 API 同进程」。
- **全手写**：PKCE 校验、授权码核销的原子性、发现端点的元数据形状、撤销语义——错一次都很难发现，而成熟组件存在的意义正是让你不写它们。

## 决定

**协议层用 Spring Authorization Server，存储与客户端查找自带。**

- `RegisteredClientRepository` **自己实现**，落在 `oauth_client` 上。
- `OAuth2AuthorizationService` **自己实现**，落在 `auth_code` / `access_token` / `refresh_token` 上，**值只存 sha256**。
- **同意记录的存储直接用它的**（`OAuth2AuthorizationConsentService` 与它的表）。理由见下。
- 端点路径用 `AuthorizationServerSettings` 配成 TD §4.4 的形状（`/oauth/authorize` 等），**不跟随它的 `/oauth2/*` 默认值**。
- 同意页仍由 `skillmaster-web/` 提供（[ADR 0015](0015-web-frontend-stack.md)），通过 `consentPage` 配置指过去，不用它默认的 Thymeleaf 页。

**判据（这条比决定本身有用）：只在冲突处自己写。** 同意记录那一块不与任何已有决定冲突——它不涉及令牌哈希，也不涉及寿命分层——所以直接用它，不为了「一致」而重写一遍。

## 理由

**为什么不在存储上也全用它。** 唯一的分歧点恰好落在**安全属性**上，而不是实现细节上：放弃哈希意味着**一次数据库泄露，所有活跃令牌直接可用**——攻击者不需要再猜任何东西。那条性质是我们刻意选过的（[ADR 0007](0007-self-built-oauth-as.md)），不该因为一个组件的默认实现而放弃。内部表的形状倒是可以让（代价小），但既然哈希这一条非改不可，两张表也就一起带上了。

**为什么不是 Keycloak。** 它的重放检测是内置的，确实诱人——但那是**一个独立服务**：多一套部署、监控、备份、升级，以及一个额外的公网暴露面，而这与 [ADR 0007](0007-self-built-oauth-as.md) 的「同进程」是正面冲突。而且它的默认重放行为比我们要的松（[ADR 0024](0024-refresh-replay-grace-window.md)）。

**为什么不是全手写。** 挑着用不是妥协，而是**把自研集中到只有我们才知道答案的那一处**——存储里怎么放令牌、以及无人值守的客户端代表谁（[ADR 0022](0022-unattended-client-bound-to-a-user.md)）。协议部分是别人比我们更清楚的部分。

**顺带一个旁证。** 它把授权码、access、refresh 塞进一行，恰好说明我们的三张表不是多余的——那三样的寿命差着几个数量级，放一起意味着刷新一次就要去动授权码那些行。

## 后果

- **要绕过它的明文比对。** 方向是覆写 `findByToken()`：拿到的明文先哈希、按哈希查到行、**再把行里的令牌值换成传进来的明文**返回，这样 provider 的 `.equals()` 能过。**这是 issue 里记的 workaround，不是一等公民——动手前先做一个验证（spike）把它跑通**。验不通的退路是顶掉它的 `AuthenticationProvider`，或者退回 [ADR 0021](0021-opaque-tokens-not-jwt.md) 的另一侧。
- **升级时要盯。** 这类 workaround 对上游重构敏感；升级 Spring Security 时，M2 的存储适配器要跟着看一遍。
- **`ModuleMap` 与迁移必须同时改。** [`TableOwnershipTest`](../../skillmaster-server/src/test/java/com/skillmasterai/TableOwnershipTest.java) 是**双向**断言（迁移声明的表 ↔ `ModuleMap` 的所有者，`containsExactlyInAnyOrderElementsOf`），只加一边会红。同意表因此也要进 `ModuleMap` 的 M2 条目，并有一条建它的迁移。
- **多一个依赖**：`spring-security-oauth2-authorization-server`，版本随 Spring Security 走。这不改变 [ADR 0011](0011-server-and-cli-stack.md) 的技术栈决定（仍是 Java + Spring），是它的细化。
- **同意页是 SPA 页面 + `consentPage` 配置**，不是它默认的 Thymeleaf 页。
- **净额**：少写整套协议，多写两个存储适配器。
