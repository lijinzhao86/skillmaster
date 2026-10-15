# 已知问题

> 日期：2026-10-15 · 对应 `8c1741e`（v3 的文档刚定稿，实现尚未开始）；**003 于 2026-10-16 补入**，那一天本机跑着服务端逐条 curl 过
> 方法：本版讨论过程中读代码与设计件时发现，**逐条把两侧都读过**（承诺在哪、实现是什么），但**端到端没有跑过**（003 例外——它是实测的）
> 处置：**本版不修**——两条都落在本版范围**之外**（一条属于 CLI 那条路的凭据，一条属于 OAuth 授权的可见性），而本版只碰浏览器会话（见 [`prd.md`](prd.md) §不做）
>
> **与 [`design/README.md`](../../../design/README.md) §已知的取舍 的分工**：那边记的是**设计与实现的口径差异**，这边记的是**产品里的缺陷**。两条都涉及同一块界面时不重复描述，只互相指向。

## 状态标注

| 标注 | 含义 |
|---|---|
| **已复现** | 实际跑出来过 |
| **部分复现** | 只在特定条件下触发；**也用于「读代码确认、未实测」的情形** |
| **未验证** | 有人提出，但没有独立复现 |
| **已修复** | 已被后续改动解决（注明日期与改动） |

**分布（3 条，权威口径）**：**1 已复现 + 2 部分复现** = 3。

> 本文件是这组计数的**唯一权威来源**；[`README.md`](README.md) 与 [`test-plan.md`](test-plan.md) 只链接，不复述。

---

## medium

### 001 · 授权屏承诺「随时可以撤销」，而这句话从 v2 起不再成立

`skillmaster-web/src/pages/ConsentPage.vue` · `design/partials/auth-overlay.html:571` · **部分复现**（读代码确认，未实测）

**现象**：那一屏写着「凭据可以随时用 `skillmaster logout` 撤销」。而 v2 起 `logout` **只删本机那一份、不再撤销服务端**（[ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §决定 7）。CLI 自己已经把这件事说对了——`whereToRevoke` 会告诉人：

> 服务端那份是浏览器登录换来的授权：**控制台没有它的入口**；重置密码会一并撤销它。

也就是说，**产品唯一的披露时刻**上，承诺了一条做不到的事。

**复现步骤（未执行）**：`skillmaster login` 走完浏览器授权 → 把凭据拷到另一处 → `skillmaster logout` → 在**另一处**用那份拷来的凭据发一个请求。若仍然成功，即复现。

**影响面**：读的人会以为登出等于撤销，于是带着一个**还活着的授权**离开。服务端那份今天最长活到 **180 天**（`refresh-token-absolute`），或者连续 30 天不用才失效。

**为什么不在本版修**：它属于 CLI 那条路的凭据，而本版的范围是浏览器会话（[`prd.md`](prd.md) §不做 #1）。改它要**同时改设计件与产品页面**——那是另一次交付，而且要先把「撤销这件事该在哪儿做」定下来。

## low

### 002 · CLI 那条路签发的凭据，控制台上没有入口

`skillmaster-server` 的 `/web/keys` 之外 · [ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md) §后果 · **部分复现**（两处代码互相印证，未实测）

**现象**：浏览器登录给 CLI 换来的是一份 **OAuth 授权**，它**不在**密钥列表里——控制台既看不到它，也撤销不掉。两处代码互相印证：密钥列表读的是 `api_key` 表（`WebKeyController` 只列自己签发的东西），而 CLI 自己那条提示语写着「控制台没有它的入口」。

**复现步骤（未执行）**：`skillmaster login` 走完浏览器授权 → 打开控制台 `/account/keys` → 列表里没有它。

**影响面**：过渡期内，「我给出去过什么」这个问题在唯一该回答它的地方没有答案。

**为什么不在本版修**：本版的设备列表管的是**会话**，不是 OAuth 授权。把这份凭据变成列表里的一行，正是 [ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md) §决定 3（那条路将来铸的是**一把 key**）要做的事——key 会在列表里。

## medium

### 003 · 挑战头把客户端指向一个 404：RFC 9728 的资源元数据从未实现

`skillmaster-server` · `BearerAuthenticationEntryPoint` · `InsufficientScopeAccessDeniedHandler` · **已复现**（2026-10-16，本机服务端 curl）

**现象**：本服务对未认证请求回的挑战头是

```
WWW-Authenticate: Bearer, resource_metadata="http://localhost:8080/.well-known/oauth-protected-resource"
```

而那个地址**答 404**。它没有任何处理器：`/.well-known/**` 在 `TokenSecurityConfig` 的链里只被 `permitAll`，服务它的是框架自己的 AS 元数据（`/.well-known/oauth-authorization-server`，答 200），资源元数据这一份从来没有写过。`git log -S "oauth-protected-resource" -- skillmaster-server/src/main` 只有一次提交，加的就是挑战头里那个字符串。

**承诺在哪**：v1 TD §174 写的是「server **MUST** 实现 RFC 9728 Protected Resource Metadata；client **MUST** 用它做 AS 发现」，§接口 的端点表里也列着 `GET /.well-known/oauth-protected-resource`。MCP 授权规范对它是必做项。

**复现步骤**：启动服务端 → `curl -i http://localhost:8080/api/v1/skills`（不带凭据）→ 看 `WWW-Authenticate` 头 → curl 它给的地址，得 404。

**影响面**：**这条路上本来该有的自动发现断了**。一个 MCP 客户端拿着挑战头去读资源元数据，学到的是「这里什么都没有」——它拿不到 AS 的地址，只能靠预先配置。今天我们自己的一方才没受影响：CLI 的地址是编译进去的。第三方客户端接进来的那天，这就是第一道墙。

**状态行在别处**：它是 v1 的一条要求在实现上的缺口，所以「仍未实现」那一行的权威是 [`v1-hosting/README.md`](../v1-hosting/README.md) §状态——**本文是复现与影响，不复述状态**。

**为什么不在本版修**：它属于 M2 的发现面，而本版只碰浏览器会话。**而且它是「从未实现」，不是「本版删掉的」**——修它要写一个新端点和它的正文（`resource`、`authorization_servers`、`scopes_supported` 各写什么是一个小设计决定），配一条 IT，并把 TD 那一行从「列着」改成记账。那是新增能力，该有自己的验收，不该藏在本版的 diff 里（2026-10-16 的取舍）。
