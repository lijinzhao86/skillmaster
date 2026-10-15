# ADR 0007 · 自建 OAuth 2.1 AS，支持三种客户端注册方式

- **状态**：已采纳
- **日期**：2026-09-26
- **依赖**：[ADR 0002](0002-gateway-skill-and-cli.md)、[ADR 0003](0003-api-primary.md)
- **范围被收窄**：[ADR 0011](0011-server-and-cli-stack.md) 把「三种注册方式」分期——**v1 只做预注册**，CIMD 推到 P2，DCR 只作兼容。**本文正文不改**（ADR 不可变）。
- **其中一条被取代**：[ADR 0038](0038-default-credential-is-an-api-key.md) §决定 9 —— §决定 里「无人值守用 Client Credentials」那一条，改成**用户自己签发的 API key**。**「自建 AS」这个决定本身不变**，其余各条照旧。

## 背景

用户需要登录平台鉴权。两个待定：登录与令牌签发自己做还是接现成的（如 SSO）；以及鉴权与传输是否绑定。

## 决定

- **自建登录与令牌签发**（OAuth 2.1 AS，起步与 API 同进程——规范允许两者同托管）。
- **令牌是 bearer**，走 `Authorization` 头，**绝不放在 query string**。
- **AS 必须同时支持三种客户端注册方式**：CIMD、DCR、预注册。
- **CLI 是普适客户端**：交互式用 loopback PKCE；无人值守用 Client Credentials。

## 理由

- **无人值守场景今天只有自研客户端能覆盖。** 调研结论：OAuth 的 **Client Credentials 扩展仍是 Draft**，官方客户端支持矩阵里**只有一家打了勾**；官方 SDK 已实现（TS/Python 都有），所以服务端容易做，但**没有现成 agent 能用**。调研原话是"CI/headless 需要一个基于 SDK 自研的客户端"。**这条不是 MCP 的退路，是唯一的路。**
- **CIMD 支持很薄，不能只做一种。** 已确认会发布 CIMD 的只有 Claude Code、VS Code、ChatGPT；Cursor / Gemini CLI / Codex 未确认（Gemini CLI 文档明确说自己走 DCR）；且 2025-12 时 Auth0、Okta、Cognito、Entra、Google Identity **都还没实现 CIMD**。只做 CIMD 会卡死一批客户端。
- **交互式鉴权覆盖很广**，但连接方式受限：Codex 是 HTTP-only（stdio 不能 OAuth）；Gemini CLI 明说"无浏览器、远程 SSH 无 X11 转发、无浏览器的容器环境都不工作"。所以 CLI 必须能独立完成登录。
- **AS 与资源服务器共用一个令牌体系**，避免用户登录两次、两边权限不一致。
- **身份提供方降级成一行数据**：`identity(provider, external_id)` 表预留，将来接飞书等 SSO 只是加一行，不动用户主键。

## 后果

- 要自己实现：登录页、令牌签发 / 刷新 / 撤销、三种注册方式、以及发现端点（RFC 8414 AS Metadata、OIDC discovery、RFC 9728 Protected Resource Metadata）。
- **"零自研客户端"只对公开 skill 成立。** 私有 / 需鉴权的 skill 必须有凭据持有者（我们的 CLI）。网关 skill 本身**绝不能**携带 token——它是落盘的。
- **令牌只存哈希**（sha256），数据库泄露不等于令牌泄露；refresh token 轮换并保留 `rotated_from` 链，便于检出重放。
- 401 的形状照 MCP 来（`WWW-Authenticate: Bearer resource_metadata="..."`），将来加适配器不用改错误语义。MCP 规范里 `Authorization` 本身是 OPTIONAL，所以不能假设客户端都实现——双路（CLI + 可选 MCP）是必要的。
