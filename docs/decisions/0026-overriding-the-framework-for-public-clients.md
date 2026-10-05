# ADR 0026 · 为公共客户端覆盖框架的两处：发 refresh token，并允许它刷新与撤销

- **状态**：已采纳
- **日期**：2026-10-02
- **依赖**：[ADR 0011](0011-server-and-cli-stack.md)（v1 只做预注册）、[ADR 0023](0023-spring-authorization-server-with-our-own-storage.md)（建在 Spring AS 上）、[ADR 0024](0024-refresh-replay-grace-window.md)（轮换）
- **细化 0023**：0023 说「只在冲突处自研」，当时以为冲突只有一处（存储）。spike 发现是三处。**0023 的决定不变，这条把它实际要覆盖的东西写清楚。**

## 背景

ADR 0023 的前提是「拿框架的协议层、存储自己写」，而那一处已经用 spike 验证成立：真实端点跑通同意 → 授权码 → 换令牌 → 轮换 → 撤销，存储里**始终只有 sha256**。

同一个 spike 挖出框架对我们这种客户端的**两处拒绝**，都在客户端认证那条路上，而且都是**静默或半静的**：

1. **`OAuth2RefreshTokenGenerator.generate()` 对「公共客户端 + authorization_code」直接返回 `null`**（私有方法 `isPublicClientForAuthorizationCodeGrant`）。没有异常、没有日志——CLI 只是**永远收不到 refresh token**。是靠读字节码找到的。
2. **`PublicClientAuthenticationConverter` 只匹配 PKCE 的 token 请求**（`OAuth2EndpointUtils.matchesPkceTokenRequest`）。所以一个只带 `client_id` 的 `grant_type=refresh_token` 请求**根本认证不过**，答 `invalid_client`；**撤销端点同理**——一个根因，两个症状。

而**我们的 CLI 正是公共客户端**（原生应用，没有地方放密钥，[RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.html)），**而整个设计都建立在「它持有 refresh token 并静默刷新」之上**。这两处不解决，M2 的核心承诺（一次同意、此后静默）无从谈起。

## 决定

**自己补这两个扩展点，继续用框架。**

- 提供一个 `OAuth2TokenGenerator`，照框架自己的算法生成 refresh token（同样的 96 字节 base64url 密钥），**只去掉那道公共客户端的判断**。
- 往客户端认证链里加一个转换器与提供者，让公共客户端在**刷新**与**撤销**两个端点上能以 `client_id` 通过认证。

## 理由

**为什么覆盖是对的，而不是将就。** 框架那道判断防的是一个真实的坏形态：**浏览器应用**把长期凭据放在 JavaScript 够得着的地方（浏览器应用安全指引专门讲这件事）。而原生应用是另一回事，标准说得很明确——[RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.html) 给它的是 loopback 重定向，[RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html) §2.2.2 允许公共客户端的 refresh token **正是在它轮换的前提下**。我们轮换（[ADR 0024](0024-refresh-replay-grace-window.md)）。**这道判断是一条面向「我们不是的那种形状」的一刀切规则。**

**为什么刷新与撤销能靠 `client_id` 认证。** 这是 RFC 6749 §6 与 RFC 7009 对公共客户端的本来要求：`client_id` 必填但不做认证，**真正的边界是 refresh token 本身**。而且它并没有因此变宽——框架在下一层仍然校验「这张 refresh token 是签给这个客户端的」，拿别人的 token 配自己的 `client_id` 一样过不去。

**为什么不是把 CLI 做成机密客户端。** 那能让框架原生全支持、**零覆盖**，听起来更干净。但每个安装必须有自己的 `client_id` + 密钥，而 v1 没有让安装自己注册的途径——要为此开 **DCR**（`/oauth/register`），而 [ADR 0011](0011-server-and-cli-stack.md) 明确把 DCR 排除在 v1 之外，开放注册端点也有它自己的滥用面。更要紧的是：那个密钥只能和 refresh token 放在同一个 keychain 里，**多出来的保护接近于零**，因为能读到其中一个的人就能读到另一个。

**为什么不是自建 AS。** 那会把协议实现的责任拿回来——PKCE 校验、授权码核销的原子性、发现端点的元数据形状，都是**错一次很难发现**的代码。当初否掉自建的理由今天一条没变。

## 后果

**M2 一共要有三处自研，而不是一处**，而且风险不在同一档：

| # | 覆盖什么 | 扩展点 | 风险 | 状态 |
|---|---|---|---|---|
| 1 | 存储只存哈希 | `OAuth2AuthorizationService` | **低**——接口本来就是给人实现的 | **已验证** |
| 2 | 给公共客户端发 refresh token | `OAuth2TokenGenerator` | 中——一个组合起来的生成器 | **已验证** |
| 3 | 允许公共客户端刷新与撤销 | `clientAuthentication(...)` 的转换器与提供者 | **高——动的是安全关键代码** | **已验证** |

三处都在 spike 里做成了，公共客户端跑完「同意 → 授权码 → 换令牌 → 轮换 → 撤销 → 撤销后拒绝」全程只存哈希。**实现还没搬进 `modules/token`。**

**一条从验证里学到的、要带进正式实现的规则**：覆盖用的转换器**只能认领「没人处理的那种形状」**。第一版写宽了，把**带密钥的**刷新请求也认领了，于是它因为「这不是公共客户端」而拒掉了机密客户端一条本来能用的路径——**是那条机密客户端的测试当场抓住的**。所以：带 `Authorization` 头或带 `client_secret` 的请求必须原样放回框架。

- **对上游重构敏感。** 这三处都建立在「读懂了框架当前的行为」之上，其中一处（那个返回 `null`）**连文档都没有**。升级 Spring Security 时必须重新看一遍，并靠测试钉住——**每处覆盖至少要有一条测试，在它失效时会红**。
- **第 3 处要让「撤销别人的令牌」仍然不可能**：转换器只回答「这个 `client_id` 是谁」，认出谁之后**是否允许它动这张令牌仍然由框架在下一层判定**——它没有被放宽，只是被认出来了。
- **一条正面收获**：框架宁可**启动失败**也不静默降级（`securityMatcher` 漏了会抛 `UnreachableFilterChainException`）。我们自己的覆盖也要这样——**框架的 provider 一旦不在那个列表里，就当场抛错**，而不是安静地退回原行为。
