# 凭据在所有终端上存在哪里，有没有通用解法

> 调研日期：2026-10-16
> 来源分三档，正文逐条标注：**[规范]** = RFC / 官方规范；**[官方文档]** = 厂商自己的文档；**[二手]** = 博客、库的 README、搜索摘要
> 用途：为「桌面之外（服务器、平板、手机）的终端要不要支持、怎么支持」提供**事实底稿**。
> **本文不下决定**——跨版本的取舍属于 [`docs/decisions/`](../decisions/)；某一版做到哪属于 `docs/versions/`。文末 §五 只登记事实与候选，供将来开 ADR 时引用。
> **结论先行**：**没有通用解法，但有一条通用的决策树**——而且它只覆盖「授权怎么拿到凭据」这一半。「凭据存在哪」那一半**没有跨平台标准**，只有各家的 OS 存储加上社区库。对 SkillMaster 最要紧的一条来自 §五第 3 条：主流 MCP 宿主是**从云上连**我们的服务器的，所以「手机上跑 agent」今天最现实的样子**不是把 CLI 装到手机上**。

## 一、问题必须拆成两半，否则一定会误判

| 一半 | 问的是什么 | 有多少标准 | 能不能通用 |
|---|---|---|---|
| **授权** | 一个客户端怎么在用户的同意下拿到凭据 | **有**：RFC 8252、RFC 8628、OAuth 2.1 草案、MCP 授权规范 | 能。跨平台、跨语言、可互操作 |
| **存储** | 拿到之后那份凭据放在哪 | **没有**。只有各平台的 OS 存储、一把社区库 | 不能。每一档都有自己的坑 |

**混在一起谈就会出现「通用解法」的误判**：能通用的只有前一半。后一半每次都要按平台重新做，而且**做错了不会报错**（iOS 的钥匙串卸载不消失、Android 的加密库被弃用，都是静默的）。

## 二、授权：按「客户端能不能收到重定向」分档

分档的轴只有一根：**这个客户端能不能在用户点完「同意」之后收到一个回跳**。收得到就分两种（回环 / 认领 https），收不到就只剩设备码或环境变量。

| 客户端情形 | 标准做法 | 依据 |
|---|---|---|
| 桌面程序，能起回环监听 | `http://127.0.0.1:{port}/callback` + PKCE | **[规范]** RFC 8252 §7.3，原文只说 `typically, those on desktop operating systems`；Appendix A 第 3 条写的是「**required to support desktop operating systems**」 |
| 移动 App（能注册自定义 scheme，或能认领一个 https） | **认领 https（Universal Link / App Link）优先**；自定义 scheme 次之，且**必须**配 PKCE | **[规范]** RFC 8252 §7.2 原文 `native apps SHOULD use them over the other options where possible`；§7.1 自定义 scheme 要求反向域名（**不含点号的 scheme SHOULD 被拒**）；§8.1 自定义 scheme 可被**多个 App 抢注**，缓解手段只有 PKCE |
| 无浏览器、输入受限（服务器、容器、TV） | Device Authorization Grant（设备码，用户在另一台设备上打字确认） | **[规范]** RFC 8628 |
| 没有人（CI） | client credentials，或一把 scoped API key | **[二手]** 行业分档文（Logto 那篇）；**[规范]** 我们自己的 [ADR 0025 §后果](../decisions/0025-cli-credential-storage.md) 也这么写：CI 的密钥来自它自己的 secret store |
| 本地 agent（stdio 传输） | **不走 OAuth，从环境变量取凭据** | **[规范]** MCP 授权规范 §Protocol Requirements：`Implementations using an STDIO transport SHOULD NOT follow this specification, and instead retrieve credentials from the environment` |
| 远程 MCP server（HTTP 传输） | OAuth 2.1 + `/.well-known` 发现 + PKCE + `resource` 参数（RFC 8707） | **[规范]** MCP 授权规范（修订版 2025-06-18） |

**三条容易漏的规则**，都会直接影响我们的服务端：

1. **回环要用 IP 字面量，不能用 `localhost`。** **[规范]** RFC 8252 §8.3 原文 `the use of localhost is NOT RECOMMENDED`。我们用的是 `127.0.0.1`，合规。
2. **授权服务器 MUST 接受客户端请求的任意回环端口。** **[规范]** RFC 8252 §7.3/§8.4（回环的端口是「精确匹配」的**唯一**例外），OAuth 2.1 草案同样保留这条例外。**我们是一个例外**：[ADR 0028](../decisions/0028-cli-uses-a-fixed-loopback-port.md) 让 CLI 用**固定端口**并在注册时逐字登记。这对我们是合规的（我们同时是 AS），但**比 BCP 窄**——一旦要接第三方客户端，这条必须放开。
3. **MCP 的硬约束：回调只能是 `localhost` 或 HTTPS。** **[规范]** MCP 授权规范 §Communication Security 原文 `All redirect URIs MUST be either localhost or use HTTPS`。→ **自定义 URL scheme 在 MCP 里不合规**，移动端 App 若自己当 MCP 客户端，只能走认领 https。

**OAuth 2.1 的现状**：**[二手]** 截至 2026 年仍是 IETF 草案（搜到的最新是 2026-03 的 `draft-ietf-oauth-v2-1-15`），**没有 RFC、也没有取代 RFC 8252**（后者仍是 BCP 212，2017-10）。另有更新的 attestation-based client auth 草案，面向钱包/原生 App 的**客户身份证明**，不是通用方案。这一条要在真正依赖之前复核。

## 三、存储：四类终端，四个地方，**没有标准**

| 终端 | 放在哪 | 这一档的坑（都是**静默**的） |
|---|---|---|
| **桌面**（macOS / Windows / Linux） | 系统钥匙串：Keychain / Credential Manager / **Secret Service（D-Bus）** | Linux 那档需要一个跑着的 Secret Service **和一个 session D-Bus**，容器、SSH、精简服务器上都没有。我们的 `internal/credentials/keychain_linux.go` 就是为这件事存在（探测 `DBUS_SESSION_BUS_ADDRESS`），`responsive.go` 再加一个 5 秒上限——因为 macOS 的钥匙串锁着时**会弹窗等人，可以永远等** |
| **移动**（iOS / Android） | iOS **Keychain** / Android **Keystore** | ① **iOS 的钥匙串条目在 App 被卸载后仍然留在设备上**，且官方没有「卸载时清除」的 API → 业界的做法是**首次启动时主动清一次**；`ThisDeviceOnly` 那一族管的是**不进备份/不同步**，**不管卸载**。② Android 的 `EncryptedSharedPreferences`（`androidx.security:security-crypto`）**在 2025 年 1.1.0-alpha07 被整体弃用**，官方建议直接使用 Keystore，社区共识是 DataStore + Tink |
| **浏览器** | cookie（`HttpOnly`） | 这一档**和上面两档是两套东西**：cookie 属于浏览器，用户看不到、拷不走，CLI 也读不到（见 [ADR 0043](../decisions/0043-the-browser-session-is-bounded-and-revocable.md) 与 `docs/architecture/`） |
| **CI** | 环境变量，值来自 CI 自己的 secret store | 这台机器上**什么都不存**——这正是它安全的原因，也是它不能反推「用户已登录」的原因 |

**跨平台标准：没有。** 有的是社区库，每个都只覆盖一部分平台。

## 四、「同一份登录状态跨设备」有没有通用做法

**没有标准做法。** 主流是**每台设备各自授权一次**——这正是「登录设备 / Active Sessions」这一类界面存在的理由（参照产品的 `Access` 组里就摆着密钥与会话两类东西）。真实存在的替代路子与它们的取舍：

| 做法 | 取舍 |
|---|---|
| iOS/macOS 的 iCloud 钥匙串同步 | 对主打安全的那一族条目（`…ThisDeviceOnly`）**恰恰不同步**——为了设备绑定放弃了同步 |
| 把 token 存进密码管理器让人肉同步 | 多一份明文副本，且脱离了应用自己的轮换与撤销 |
| 干脆不要长命凭据：每次短命 token + 频繁交互授权 | 打断，但撤销面最小 |
| DPoP（RFC 9449）这类**发送方受限**令牌 | 它解决的是**重放**，不是跨设备同步；两件事常被混为一谈 |

## 五、对 SkillMaster 的六条事实（登记事实，不下结论）

1. **我们与 BCP 的关系是「合规但窄」**：回环 + PKCE + 精确匹配都在，但固定端口逐字注册是我们自己加的约束（ADR 0028）。要接第三方客户端时，「AS MUST 接受任意请求端口」这条必须放开。
2. **MCP 客户端是硬约束，不是建议**（§二第 3 条）：回调必须是 `localhost` 或 HTTPS。→ 移动端 App 自己当 MCP 客户端时，**只有认领 https 一条路**。
3. **主流 MCP 宿主是「从云上连」**：**[官方文档]** Anthropic 帮助中心原文 —— `Custom connectors connect to your MCP server from Anthropic's cloud, not from your local device`，并明说这对 Claude Desktop 也成立；同一页**没有**把自定义连接器列进移动端（移动端只到 beta）。**推论：今天「手机上用 agent」的现实形态是云端宿主代连，不是把我们的 CLI 装到手机上。**
4. **那条路对服务端的要求是「公网可达 + 认一个回调」**：同一页明说服务器在防火墙后或私网会连不上（办法是给 Anthropic 的 IP 段开白名单）。Anthropic 侧的 OAuth 客户端选项写作「用 Claude 的公开身份（推荐）／自动注册／自带 OAuth 客户端」——**[官方文档]** 该页**没有**出现 DCR / CIMD 的技术名，**也没有**给回调 URL，所以「它到底用哪个机制」**未核实**。
5. **它给非 OAuth 服务器的支持是「固定请求头里的 API key」**：**[官方文档]** 同一页原文 `add fixed credentials such as API keys that Claude sends on every request`。→ 这正是我们**已经有**的那条路（API key + `SKILLMASTER_API_KEY`），不需要新的授权机制。
6. **存储侧我们已经符合业界形态**（keychain 优先、明文兜底并告知位置，ADR 0025），而**移动端那一档今天是空的**：`internal/credentials/keychain_other.go` 的注释自己列的就只有 macOS 与 Windows。
7. **多一个客户端，在我们的实现里是「部署时多一行」，不是改授权流程。** `oauth_client` 表本身就有 `redirect_uris` 列（`ClientRegistry` 的查询列里就有它），而 `ClientRegistry.save()` **直接抛异常**——v1 没有注册入口，靠部署时种行；DCR 从不启用、CIMD 归 P2（[v1 技术设计](../versions/v1-hosting/technical-design.md) §接口）。这个类从第一天就写在框架接口后面，模块文档明说「走到 CIMD 是**加一个实现**，不是重写授权流程」。→ 所以「认一个云宿主的 https 回调」在代码上是加一行数据；**真正的代价在治理**：谁有资格被种进去、按什么条件放行。

**2026-10-16 的落地**：下面这些候选里已经有一条定了——终端的范围写进了 [ADR 0044](../decisions/0044-the-cli-serves-desktops-and-servers.md)（**CLI 只面向桌面与服务器**；服务器走 key 不走浏览器；**不做移动端 CLI**，将来若需要由独立注册的 App 承担，凭据存哪由它自决；那个 App 动工前**先定主体是人还是 agent**）。**本文仍然是事实底稿**：决定与理由在 0044，事实与出处在这里，两边不互相复述。

**剩下的候选方向**（只是候选，不是决定）：

- **A. 什么都不做**：手机/平板只走浏览器会话（网页），agent 走 API key。今天就能用，代价是手机上没有命令行工具。
- **B. 让 AS 认得「云宿主的回调」**：支持 HTTPS 回调 + 精确匹配（已是现状），并评估 DCR 或 CIMD——MCP 规范对这两者都是 `SHOULD`，而 [v1 技术设计](../versions/v1-hosting/technical-design.md) 里 DCR 明确写着「v1 不启用、只作兼容」，CIMD 排在 P2。**代码代价很小**（见 §五第 7 条：一行 `oauth_client`），代价在**治理**：谁能被种进那张表、按什么条件。
- **C. 自己写移动端客户端**：需要认领 https 回调 + 平台 Keystore + 一个新的预注册客户端。工作量与维护面最大，且它要解决的用户痛点（在手机上跑 agent）今天被 B 那条路覆盖。

## 六、来源、口径与置信度

| 事实 | 出处 | 档位 |
|---|---|---|
| 回环只面向桌面；移动端用认领 https 优先、自定义 scheme 次之；`localhost` 不推荐；精确匹配与端口例外；自定义 scheme 的劫持与 PKCE | [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.txt) §7.1/§7.2/§7.3/§8.1/§8.3/§8.4/§8.5/Appendix A | **[规范]** 逐字读过 |
| 设备码流程的适用面与轮询规则 | [RFC 8628](https://www.rfc-editor.org/rfc/rfc8628)（本页只按分档引用，未逐字核对轮询细节） | **[规范]**（分档）/ **[二手]**（实现细节） |
| MCP 授权：OAuth 2.1、metadata 发现、PKCE、`resource` 参数、回调必须是 localhost 或 HTTPS、stdio 从环境变量取凭据 | [MCP 授权规范 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization) | **[规范]** 逐字读过 |
| 自定义连接器**从 Anthropic 云上连**、移动端不在其列、非 OAuth 服务器用固定请求头里的 API key | [Anthropic 帮助中心 · 使用连接器](https://support.claude.com/en/articles/11176164-using-connectors-to-extend-claude-s-capabilities) | **[官方文档]** 逐字读过 |
| iOS 钥匙串条目**卸载后仍在**、`ThisDeviceOnly` 管的是备份与迁移 | OWASP MASTG 与移动安全资料（**未找到 Apple 官方页面直述这一点**） | **[二手]** 复核后再依赖 |
| Android `EncryptedSharedPreferences` 于 2025 年 1.1.0-alpha07 被弃用、建议直接用 Keystore | [androidx.security.crypto API 参考](https://developer.android.com/reference/kotlin/androidx/security/crypto/package-summary) 与社区迁移文 | **[官方文档]**（弃用事实）/ **[二手]**（Tink 是共识做法） |
| OAuth 2.1 仍是草案、未取代 RFC 8252 | IETF datatracker（搜到 2026-03 的 draft-ietf-oauth-v2-1-15） | **[二手]** 用前复核 |
| keychain 优先 + 明文兜底的业界形态 | [ADR 0025](../decisions/0025-cli-credential-storage.md) 已列的参考（Claude Code / Codex / gcloud / Stripe / Terraform / Vercel） | **[二手]** 且是当时的转述 |
