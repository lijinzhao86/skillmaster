# 决策记录（ADR）

这里记录**跨版本存活的决策**——它们不属于某一版，所以不放在 `versions/` 里。

## 为什么单独拿出来

决策与设计文档的寿命不一样。设计文档随版本迭代被重写，但"为什么当初选了服务端权威"这件事，三年后还要能查。如果把决策埋在设计文档的附录里，它就会随文档一起被覆盖、被遗忘，或者被悄悄改掉而没人知道。

所以：**每条决策一个文件，有编号、日期和状态。** 要推翻某条，就新增一条"取代 000N"，而不是改写原文。**唯一允许的就地修改是文件头的状态与引用**（把状态改成"被取代"、标出取代它的编号）——正文的四段一旦写下就不再改动，因为它的价值正在于"当时是这么想的"。

## 索引

| 编号 | 决策 | 状态 | 日期 |
|---|---|---|---|
| [0001](0001-server-authoritative.md) | 服务端权威，本地不落 skill 副本 | 已采纳 | 2026-09-26 |
| [0002](0002-gateway-skill-and-cli.md) | 交付形态：一个通用网关 skill + 自研 CLI | 已采纳 | 2026-09-26 |
| [0003](0003-api-primary.md) | API 为主契约，MCP 为可选适配器 | 已采纳 | 2026-09-26 |
| [0004](0004-opaque-id-primary-key.md) | 身份模型：不透明 `id` 作主键，`name` 为属性 | 已采纳（寻址部分被 0012 修订） | 2026-09-26 |
| [0005](0005-content-addressing.md) | 内容寻址与幂等发布 | 已采纳 | 2026-09-26 |
| [0006](0006-search-server-side.md) | 检索在服务端，且只索引 L1 | 已采纳 | 2026-09-26 |
| [0007](0007-self-built-oauth-as.md) | 自建 OAuth 2.1 AS，支持三种客户端注册方式 | 已采纳 | 2026-09-26 |
| [0008](0008-no-script-execution.md) | P0 不做脚本执行 | 已采纳 | 2026-09-26 |
| [0009](0009-drop-l2n.md) | 砍掉 `l2#n`，付费边界只落在层与层之间 | 已采纳 | 2026-09-26 |
| [0010](0010-storage-in-postgres.md) | 存储全部落在 PostgreSQL（含字节），不引对象存储或文件系统 | 已采纳 | 2026-09-27 |
| [0011](0011-server-and-cli-stack.md) | 技术栈：服务端 Java + Spring，CLI 用 Go；v1 的 AS 只做预注册 | 已采纳 | 2026-09-27 |
| [0012](0012-addressing-and-version-pinning.md) | 寻址：`namespace/name` + 版本钉（`@序号` / `@digest`） | 已采纳 | 2026-09-28 |
| [0013](0013-phone-login-and-username-slug.md) | 登录凭据与公开身份分离：手机号登录，用户名做 slug | 已采纳 | 2026-10-01 |
| [0014](0014-browser-session-via-spring-session.md) | 浏览器会话交给 Spring Session JDBC；M2 走框架契约（`getName()` = userId）而非 M1 的 API | 已采纳 | 2026-10-01 |
| [0015](0015-web-frontend-stack.md) | 浏览器端：Vue 3 + Vite + TS，且刻意不引路由 / 状态库 / UI 库 / i18n | 已采纳 | 2026-10-01 |
| [0016](0016-password-blocklist-not-composition.md) | 密码用黑名单（vendored SecLists），不用字符类组合规则 | 已采纳 | 2026-10-02 |
| [0017](0017-password-printable-ascii-only.md) | 密码只允许 ASCII 可打印字符（放弃中文与全角，换取一整类失败模式消失） | 已采纳 | 2026-10-02 |
| [0018](0018-caller-address-behind-the-proxy.md) | 反代后面的调用方地址：显式认转发头，且**代理必须覆写**它 | 已采纳 | 2026-10-02 |
| [0019](0019-local-database-in-a-container.md) | 本地开发只把数据库放进容器，应用留在宿主机 | 已采纳 | 2026-10-02 |
| [0020](0020-first-code-send-without-a-captcha.md) | 注册的第一个发码请求免图形验证码（每个地址每个窗口一次，仅注册） | 已采纳 | 2026-10-02 |
| [0021](0021-opaque-tokens-not-jwt.md) | 令牌用不透明随机串，不用 JWT（换撤销立即生效，代价是每个请求查一次主键） | 已采纳 | 2026-10-02 |
| [0022](0022-unattended-client-bound-to-a-user.md) | 无人值守的客户端绑到一个用户（`oauth_client` 加一列，`access_token.user_id` 保持不变） | 已采纳 | 2026-10-02 |
| [0023](0023-spring-authorization-server-with-our-own-storage.md) | M2 用 Spring Authorization Server，但存储自带（保住「令牌只存哈希」） | 已采纳 | 2026-10-02 |
| [0024](0024-refresh-replay-grace-window.md) | refresh token 重放：宽限窗口内算竞态，窗口外整链撤销 | 已采纳 | 2026-10-02 |
| [0025](0025-cli-credential-storage.md) | CLI 的凭据存哪：keychain 优先，明文文件兜底并告知 | 已采纳 | 2026-10-02 |
| [0026](0026-overriding-the-framework-for-public-clients.md) | 为公共客户端覆盖框架的两处：发 refresh token，并允许它刷新与撤销 | 已采纳 | 2026-10-02 |
| [0027](0027-attributes-keep-the-frameworks-principal-type.md) | attributes 里的主体存框架自己的类型（换值，而不是放宽多态白名单） | 已采纳 | 2026-10-03 |
| [0028](0028-cli-uses-a-fixed-loopback-port.md) | CLI 的回调用固定 loopback 端口（框架逐字比对，放过随机端口要重写它整套校验） | 已采纳 | 2026-10-03 |
| [0029](0029-an-authorization-revocation-is-a-state.md) | 授权的撤销是一个状态，记在它自己的行上（扩展 [0024](0024-refresh-replay-grace-window.md)） | 已采纳 | 2026-10-05 |
| [0030](0030-the-fallback-credential-is-per-server.md) | CLI 兜底凭据文件按 server 分（扩展 [0025](0025-cli-credential-storage.md)） | 已采纳 | 2026-10-05 |

## 状态取值

| 状态 | 含义 |
|---|---|
| **提议** | 已写出，尚未确认 |
| **已采纳** | 确认执行，后续文档与实现以它为准 |
| **被取代** | 已被更新的某条取代，保留供追溯（在文件头标明取代它的编号） |
| **已废弃** | 不再适用，但没有替代者 |

## 格式

每条 ADR 固定四段：**背景**（当时面对什么问题、有哪些选项）、**决定**（选了什么）、**理由**（为什么，尤其是被否掉的那条为什么被否）、**后果**（代价与后续影响）。

「理由」段是这类记录最有价值的部分——它保存的是**当时知道什么、不知道什么**，而不是结论本身。结论在代码里看得到，取舍的依据看不到。

## 与设计文档的分工

设计文档**只链接、不重复**决策内容。如果发现某份设计文档在正文里复述了某条决策的理由，那是需要清理的重复——理由只有一处权威来源，就是这里。
