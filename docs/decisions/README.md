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
| [0012](0012-addressing-and-version-pinning.md) | 寻址：`namespace/name` + 版本钉（`@版本名` / `@digest`） | 已采纳（别名形状被 0033 修订） | 2026-09-28 |
| [0013](0013-phone-login-and-username-slug.md) | 登录凭据与公开身份分离：手机号登录，用户名做 slug | 已采纳 | 2026-10-01 |
| [0014](0014-browser-session-via-spring-session.md) | 浏览器会话交给 Spring Session JDBC；M2 走框架契约（`getName()` = userId）而非 M1 的 API | 已采纳 | 2026-10-01 |
| [0015](0015-web-frontend-stack.md) | 浏览器端：Vue 3 + Vite + TS，且刻意不引路由 / 状态库 / UI 库 / i18n | 已采纳 | 2026-10-01 |
| [0016](0016-password-blocklist-not-composition.md) | 密码用黑名单（vendored SecLists），不用字符类组合规则 | 已采纳 | 2026-10-02 |
| [0017](0017-password-printable-ascii-only.md) | 密码只允许 ASCII 可打印字符（放弃中文与全角，换取一整类失败模式消失） | 已采纳 | 2026-10-02 |
| [0018](0018-caller-address-behind-the-proxy.md) | 反代后面的调用方地址：显式认转发头，且**代理必须覆写**它 | 已采纳 | 2026-10-02 |
| [0019](0019-local-database-in-a-container.md) | 本地开发只把数据库放进容器，应用留在宿主机 | 已采纳 | 2026-10-02 |
| [0020](0020-first-code-send-without-a-captcha.md) | 注册的第一个发码请求免图形验证码（每个地址每个窗口一次，仅注册） | 已采纳 | 2026-10-02 |
| [0021](0021-opaque-tokens-not-jwt.md) | 令牌用不透明随机串，不用 JWT（换撤销立即生效，代价是每个请求查一次主键） | 已采纳 | 2026-10-02 |
| [0022](0022-unattended-client-bound-to-a-user.md) | 无人值守的客户端绑到一个用户（`oauth_client` 加一列，`access_token.user_id` 保持不变） | **被取代**（机制那半：[0038](0038-default-credential-is-an-api-key.md) §决定 9 改用 API key） | 2026-10-02 |
| [0023](0023-spring-authorization-server-with-our-own-storage.md) | M2 用 Spring Authorization Server，但存储自带（保住「令牌只存哈希」） | 已采纳 | 2026-10-02 |
| [0024](0024-refresh-replay-grace-window.md) | refresh token 重放：宽限窗口内算竞态，窗口外整链撤销 | 已采纳 | 2026-10-02 |
| [0025](0025-cli-credential-storage.md) | CLI 的凭据存哪：keychain 优先，明文文件兜底并告知 | 已采纳 | 2026-10-02 |
| [0026](0026-overriding-the-framework-for-public-clients.md) | 为公共客户端覆盖框架的两处：发 refresh token，并允许它刷新与撤销 | 已采纳 | 2026-10-02 |
| [0027](0027-attributes-keep-the-frameworks-principal-type.md) | attributes 里的主体存框架自己的类型（换值，而不是放宽多态白名单） | 已采纳 | 2026-10-03 |
| [0028](0028-cli-uses-a-fixed-loopback-port.md) | CLI 的回调用固定 loopback 端口（框架逐字比对，放过随机端口要重写它整套校验） | 已采纳 | 2026-10-03 |
| [0029](0029-an-authorization-revocation-is-a-state.md) | 授权的撤销是一个状态，记在它自己的行上（扩展 [0024](0024-refresh-replay-grace-window.md)） | 已采纳 | 2026-10-05 |
| [0030](0030-the-fallback-credential-is-per-server.md) | CLI 兜底凭据文件按 server 分（扩展 [0025](0025-cli-credential-storage.md)） | 已采纳 | 2026-10-05 |
| [0031](0031-submitting-and-publishing-are-two-actions.md) | 提交与上线是两个动作（修订 [0012](0012-addressing-and-version-pinning.md) §决定 5「v1 不做草稿」） | 已采纳 | 2026-10-06 |
| [0032](0032-ui-primitives-on-demand.md) | 组件按需引无样式原语，不引整套组件库（扩展 [0015](0015-web-frontend-stack.md) 的「不引 UI 组件库」） | 已采纳 | 2026-10-06 |
| [0033](0033-version-names-are-semver.md) | 版本名是作者声明的 semver，身份仍是 digest（修订 [0012](0012-addressing-and-version-pinning.md) §决定 3 的整数别名） | 已采纳 | 2026-10-07 |
| [0034](0034-skill-level-sharing.md) | 共享是 skill 级的授权（`viewer` / `editor`，挂在 `skill.id` 上），不是命名空间成员；引出第一个 403 | 已采纳 | 2026-10-07 |
| [0035](0035-the-pin-lives-on-the-client-machine.md) | 版本钉由 CLI 记在本机（`invoke` 写、`read` 用 `X-Skill-Version` 发），地址不带版本（修订 [0012](0012-addressing-and-version-pinning.md) §理由 里「让客户端记住版本号」那条） | 已采纳 | 2026-10-07 |
| [0036](0036-enumeration-is-a-read.md) | 枚举一个版本的文件是读取的一部分，由 CLI 的 `files` 给、挂同一个读授权（修订 [0034](0034-skill-level-sharing.md) 那一轮撤掉 `show` 时「枚举不是 skill 自己的动词」那句） | 已采纳 | 2026-10-07 |
| [0037](0037-visibility-has-two-states.md) | 可见性只有 `private` / `public` 两档，公开即被搜到；`unlisted` 被放弃而不是推迟，两张表都是（修订 [0034](0034-skill-level-sharing.md) §理由 里「另外两个值是留给公共发现的」那句） | 已采纳 | 2026-10-10 |
| [0039](0039-api-key-defaults-and-their-edges.md) | API key 的缺省值与它的边界：有效期**界面预选 90 天**（不是参照产品的 365 天——那个数有提醒托着，我们没有邮件通道）、**写蕴含读**、**部署开关只约束新建**、续期**重新定日期**且已撤销的不能续期、补**两条审计信号**。部分取代 [0038](0038-default-credential-is-an-api-key.md) 的决定 3、4 与后果第二条 | 已采纳 | 2026-10-15 |
| [0038](0038-default-credential-is-an-api-key.md) | 第一方的默认凭据是用户自己签发的 API key（**缺省 90 天**、部署可配、前缀 `skm_`、默认只读；这一行原先写 365 天，[0039](0039-api-key-defaults-and-their-edges.md) 已取代那个数），OAuth 降级为第三方专用（修订 [0022](0022-unattended-client-bound-to-a-user.md) 的机制、收窄 0011/0023/0024/0026/0028 那组到第三方那条路）| 已采纳，**部分被 0039 取代** | 2026-10-15 |
| [0040](0040-api-key-in-the-cli-now-the-browser-path-later.md) | CLI 的凭据面：本版**签发只在控制台**（能造凭据的必须是浏览器会话）、CLI 只从 **stdin / 环境变量**收 key、CLI **不签发不列出不撤销不续期**；「点一下授权」那条路**推迟但要做**，而且它将来铸的是**一把 key 而不是 OAuth 令牌** | 已采纳 | 2026-10-15 |
| [0041](0041-third-parties-reach-us-through-the-cli.md) | 第三方 agent **走我们的 CLI** 进来（装 CLI + 一把 key），本版**不做 MCP 适配器也不做 CIMD**；OAuth 那条路留着但本版没有第三方宿主在用（唯一走它的是过渡期的 `login`）。修订 [prd.md](../versions/v2-credentials/prd.md) 里「第三方接入本身仍是 P2」那句错话 | 已采纳 | 2026-10-15 |
| [0042](0042-the-expiry-setting-is-a-cap.md) | 部署的有效期配置**同时是默认值和上限**：带日期的签发请求超过「`lifetime` 天之后那一天的最后一秒」（一天的结束，不是 `now + lifetime` 这个瞬间——两者差着当天已经过去的那些秒）就拒绝（`400 invalid_expiry` / `beyond_maximum`），这一天由服务端算好、随 `policy.latest_day` 公布，`expire: false` 则没有上界；配置项改名 `default-lifetime` → `lifetime`，并且**不到一整天的 `lifetime` 启动即拒**（`toDays()` 会截断成 0，上限落到今天，续期出来的 key 出生即过期）。钉死 [0039](0039-api-key-defaults-and-their-edges.md) §决定 1 里被读成两样的那一句，不改它的结论 | 已采纳 | 2026-10-15 |
| [0043](0043-the-browser-session-is-bounded-and-revocable.md) | 浏览器会话有**两条尺子**：空闲 **24 小时**、绝对 **7 天**（今天只有一条滑动的空闲 7 天，对天天在用的人等价于永久），绝对那条**从登录时刻算、不滑动**，且**启动时拒绝「绝对 < 空闲」**；同时**登录设备可见可撤销**（能踢一台、能踢其他所有，当场生效）——两者是同一个决定的半边：**有了能踢，寿命才可以放宽到 7 天；撤不掉的话，1 天还是 7 天都只是给一扇关不上的门调门缝**。列表里不出现会话 id（它等价于凭据）。本版**不改权限模型**（会话仍是「账号全权、不带 scope」） | 已采纳 | 2026-10-15 |
| [0044](0044-the-cli-serves-desktops-and-servers.md) | 终端范围划清：**CLI 的浏览器登录只面向桌面**（服务器走 `login --with-key` / `SKILLMASTER_API_KEY`，没有浏览器不是缺陷是范围）；**「跨系统」的待办是验证不是重写**（三处 OS 差异已在接口后面，但 CI 里没有 CLI 的 job、Mac 之外从未执行）；**不做移动端 CLI**，将来若需要由一个独立注册的 App 承担，凭据存哪由它自决；**那个 App 动工前必须先定主体是人还是 agent**（连的平面与规范都不同）。否掉了「把 CLI 做到移动端」与「在移动端加设备码绕开回跳」（手机的病是收不到回跳，不是没有浏览器）。后果里写明服务器那份凭据是 API 密钥、不进登录设备列表 | 已采纳 | 2026-10-16 |

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
