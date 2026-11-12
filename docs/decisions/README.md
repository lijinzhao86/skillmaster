<!-- 生成物，勿手改。源：docs/decisions/NNNN-*.md 的文件头；重新生成：python3 scripts/docs/generate.py -->

# 决策记录（ADR）

决策跨版本存活。要推翻某条，新增一条「取代 000N」，不改旧文正文。**这份索引是生成物**：状态取自每个文件头，不在这里另写一份。

共 186 条。

| 编号 | 决策 | 状态 | 日期 |
|---|---|---|---|
| [0001](0001-server-authoritative.md) | 服务端权威，本地不落 skill 副本 | 已采纳 | 2026-09-26 |
| [0002](0002-gateway-skill-and-cli.md) | 交付形态：一个通用网关 skill + 自研 CLI | **部分被取代**——决定 1（在本机装一个通用网关 skill）以及决定 2 里「安装与更新网关」那一半，均由 [0089](0089-no-local-skills.md) 取代：本机不装任何 skill，三条规则写进系统提示词，`setup` 命令随之删除，因此「两条分发路径」（§后果）也只剩 CLI 一条。仍然有效的是：CLI 主导（登录、持有凭据、检索与取用）、凭据不进模型上下文、服务端权威 | 2026-09-26 |
| [0003](0003-api-primary.md) | API 为主契约，MCP 为可选适配器 | 已采纳 | 2026-09-26 |
| [0004](0004-opaque-id-primary-key.md) | 身份模型：不透明 id 作主键，name 为属性 | 已采纳（§后果 里的**寻址**部分被 [ADR 0012](0012-addressing-and-version-pinning.md) 修订） | 2026-09-26 |
| [0005](0005-content-addressing.md) | 内容寻址与幂等发布 | 部分被取代（§后果里「上传时不得改写内容」这一句由 [0117](0117-content-is-canonicalized-at-the-door.md) 取代） | 2026-09-26 |
| [0006](0006-search-server-side.md) | 检索在服务端，且只索引 L1 | 已采纳 | 2026-09-26 |
| [0007](0007-self-built-oauth-as.md) | 自建 OAuth 2.1 AS，支持三种客户端注册方式 | 已采纳 | 2026-09-26 |
| [0008](0008-no-script-execution.md) | P0 不做脚本执行 | 已采纳 | 2026-09-26 |
| [0009](0009-drop-l2n.md) | 砍掉 `l2#n`，付费边界只落在层与层之间 | 已采纳 | 2026-09-26 |
| [0010](0010-storage-in-postgres.md) | 存储全部落在 PostgreSQL | 已采纳（**部分被 [ADR 0130](0130-large-blobs-live-in-object-storage.md) 取代**：决定 2 的「不引入对象存储」与决定 4 的「字节回收与版本变更在同一事务里完成」；`BlobStore` 接缝与其余结论仍有效） | 2026-09-27 |
| [0011](0011-server-and-cli-stack.md) | 技术栈：服务端 Java + Spring，CLI 用 Go | 已采纳 | 2026-09-27 |
| [0012](0012-addressing-and-version-pinning.md) | 寻址：`namespace/name` + 版本钉 | 已采纳 | 2026-09-28 |
| [0013](0013-phone-login-and-username-slug.md) | 登录凭据与公开身份分离：手机号登录，用户名做 slug | 已采纳 | 2026-10-01 |
| [0014](0014-browser-session-via-spring-session.md) | 浏览器会话交给 Spring Session JDBC，M2 通过框架契约接入 | 已采纳 | 2026-10-01 |
| [0015](0015-web-frontend-stack.md) | 浏览器端用 Vue 3 + Vite，且刻意不引路由 / 状态库 / UI 库 / i18n | **部分被取代**——「用 Vue 3」这一条由 [ADR 0075](0075-the-browser-client-is-react.md) 取代（2026-10-21，浏览器端改用 React）；**其余七项纪律（路由 / 状态库 / UI 组件库 / CSS 框架 / `axios` / i18n / ESLint·Prettier）与同源那一段仍然有效**，0075 原样继承 | 2026-10-01 |
| [0016](0016-password-blocklist-not-composition.md) | 密码用黑名单，不用组合规则 | 已采纳 | 2026-10-02 |
| [0017](0017-password-printable-ascii-only.md) | 密码只允许 ASCII 可打印字符 | 已采纳 | 2026-10-02 |
| [0018](0018-caller-address-behind-the-proxy.md) | 反代后面的「调用方地址」：显式认转发头，且代理必须覆写它 | 已采纳 | 2026-10-02 |
| [0019](0019-local-database-in-a-container.md) | 本地开发只把数据库放进容器，应用留在宿主机 | 已采纳 | 2026-10-02 |
| [0020](0020-first-code-send-without-a-captcha.md) | 注册的第一个发码请求免图形验证码 | 已采纳 | 2026-10-02 |
| [0021](0021-opaque-tokens-not-jwt.md) | 令牌用不透明随机串，不用 JWT | 已采纳 | 2026-10-02 |
| [0022](0022-unattended-client-bound-to-a-user.md) | 无人值守的客户端绑到一个用户 | **被取代** | 2026-10-02 |
| [0023](0023-spring-authorization-server-with-our-own-storage.md) | M2 用 Spring Authorization Server，但存储自带 | 已采纳 | 2026-10-02 |
| [0024](0024-refresh-replay-grace-window.md) | refresh token 重放：宽限窗口内算竞态，窗口外整链撤销 | 已采纳 | 2026-10-02 |
| [0025](0025-cli-credential-storage.md) | CLI 的凭据存哪：keychain 优先，明文文件兜底并告知 | 已采纳 | 2026-10-02 |
| [0026](0026-overriding-the-framework-for-public-clients.md) | 为公共客户端覆盖框架的两处：发 refresh token，并允许它刷新与撤销 | 已采纳 | 2026-10-02 |
| [0027](0027-attributes-keep-the-frameworks-principal-type.md) | 框架的 attributes 里，principal 存框架自己的类型 | 已采纳 | 2026-10-03 |
| [0028](0028-cli-uses-a-fixed-loopback-port.md) | CLI 的回调用固定 loopback 端口 | 已采纳 | 2026-10-03 |
| [0029](0029-an-authorization-revocation-is-a-state.md) | 授权的撤销是一个状态，记在它自己的行上 | 已采纳 | 2026-10-05 |
| [0030](0030-the-fallback-credential-is-per-server.md) | 兜底凭据文件按 server 分 | 已采纳 | 2026-10-05 |
| [0031](0031-submitting-and-publishing-are-two-actions.md) | 提交与上线是两个动作 | 已采纳 | 2026-10-06 |
| [0032](0032-ui-primitives-on-demand.md) | 组件按需引无样式原语，不引整套组件库 | 已采纳 | 2026-10-06 |
| [0033](0033-version-names-are-semver.md) | 版本名是作者声明的 semver，身份仍是 digest | 已采纳 | 2026-10-07 |
| [0034](0034-skill-level-sharing.md) | 共享是 skill 级的授权，不是命名空间成员 | 已采纳 | 2026-10-07 |
| [0035](0035-the-pin-lives-on-the-client-machine.md) | 版本钉记在客户端本地，地址不带版本 | 已采纳 | 2026-10-07 |
| [0036](0036-enumeration-is-a-read.md) | 枚举一个版本的文件是读取的一部分，而且由 CLI 给 | 已采纳 | 2026-10-07 |
| [0037](0037-visibility-has-two-states.md) | 可见性只有私有与公开两档，公开即被搜到 | 已采纳 | 2026-10-10 |
| [0038](0038-default-credential-is-an-api-key.md) | 第一方的默认凭据是用户自己签发的 API key | 已采纳，**部分被 [ADR 0039](0039-api-key-defaults-and-their-edges.md) 取代**（§决定 3、§决定 4 与 §后果 的第二条）；**§决定 7 被 [ADR 0142](0142-cli-logout-ends-its-own-server-login.md) 收窄到 API 密钥**（2026-11-05） | 2026-10-15 |
| [0039](0039-api-key-defaults-and-their-edges.md) | API key 的缺省值与它的边界 | 已采纳，**§决定 1 的「关掉开关则所有新 key 都不过期」与 §理由 的「为什么 90 而不是 365」被 [ADR 0171](0171-api-key-expiry-is-a-choice.md) 收窄**（90 天预选与「配置只约束新建」保留） | 2026-10-15 |
| [0040](0040-api-key-in-the-cli-now-the-browser-path-later.md) | CLI 现在收 key；「点一下」那条路留到后续，而且它铸的是 key | 已采纳 | 2026-10-15 |
| [0041](0041-third-parties-reach-us-through-the-cli.md) | 第三方走我们的 CLI 进来，本版不做 MCP 适配器 | 已采纳 | 2026-10-15 |
| [0042](0042-the-expiry-setting-is-a-cap.md) | 部署的有效期配置是上限，不只是默认值 | 已采纳，**被 [ADR 0171](0171-api-key-expiry-is-a-choice.md) 取代**（`lifetime` 配置被删除；上限是产品常量 365 天，默认值是产品常量 90 天；「按天、按上海日、由服务端下发」的部分保留） | 2026-10-15 |
| [0043](0043-the-browser-session-is-bounded-and-revocable.md) | 浏览器会话有两条尺子，而且设备可撤销 | 已采纳 | 2026-10-15 |
| [0044](0044-the-cli-serves-desktops-and-servers.md) | 终端的范围：CLI 管桌面与服务器，移动端将来由 App | 已采纳 | 2026-10-16 |
| [0045](0045-the-absolute-ceiling-follows-the-session.md) | 绝对上限从「会话那一行创建的时刻」算起，重新登录不重启它 | 已采纳 | 2026-10-16 |
| [0046](0046-deleting-a-skill-happens-in-the-browser.md) | 把一个 skill 删掉这件事，只在浏览器面存在 | 已采纳 | 2026-10-16 |
| [0047](0047-permissions-are-a-ladder.md) | 对一个 skill 的权限是一条梯子，不是一个开关 | 已采纳 | 2026-10-17 |
| [0048](0048-visibility-hands-out-a-rung.md) | 「公开」是把梯子的某一档交给所有人 | 已采纳 | 2026-10-17 |
| [0049](0049-an-edited-description-outranks-the-version.md) | 作者改过的那句描述压着版本，直到他自己清掉 | 已采纳 | 2026-10-17 |
| [0050](0050-deleting-a-skill-can-be-undone.md) | 删除是可以撤销的，而名字不释放 | 部分被取代（§决定 3 由 0183 取代） | 2026-10-17 |
| [0051](0051-the-author-plane-serves-by-tier.md) | 作者面按档位分，不按面分 | 已采纳 | 2026-10-17 |
| [0052](0052-a-request-is-not-a-grant.md) | 申请不是授权：申请一档，由管理与所有者批准 | 已采纳 | 2026-10-17 |
| [0053](0053-skill-metadata-is-its-own-module.md) | skill 元数据是它自己的模块，列表要的事实落在 `skill` 行上 | 已采纳 | 2026-10-18 |
| [0054](0054-lifecycle-actions-happen-on-a-version-page.md) | 生命周期动作发生在一个版本自己的页面上 | 已采纳 | 2026-10-18 |
| [0055](0055-drafts-have-a-required-name.md) | 草稿有一个必填的名字，与说明和发布名分开 | 已采纳 | 2026-10-19 |
| [0056](0056-dangerous-actions-are-confirmed-in-a-modal.md) | 危险动作在弹窗里确认；回滚不是危险动作，但也确认 | 已采纳（**丢弃的归类被 [ADR 0060](0060-discarding-a-draft-is-not-dangerous.md) 取代**；其余决定不变） | 2026-10-19 |
| [0057](0057-dropdowns-are-the-products-own-menu.md) | 下拉一律是页面自己的菜单，不用原生 `<select>` | 已采纳 | 2026-10-19 |
| [0058](0058-a-version-is-readable-or-not.md) | 版本只有「可读 / 不可读」；弃用不是状态，撤回叫下架 | 已采纳 | 2026-10-19 |
| [0059](0059-delist-reason-is-optional.md) | 下架的原因是选填 | 已采纳 | 2026-10-20 |
| [0060](0060-discarding-a-draft-is-not-dangerous.md) | 丢弃草稿不是危险动作，但它无法找回 | 已采纳 | 2026-10-20 |
| [0061](0061-what-a-credential-may-do-to-versions.md) | 凭据能对版本做什么：不改变调用者所读的都可以，改变的不行 | 已采纳 | 2026-10-20 |
| [0062](0062-grep-is-a-scoped-scan-not-an-index.md) | 作者面的 grep 是有界扫描，不是索引 | 部分被取代（「只搜草稿」那一条范围由 [0098](0098-grep-reads-every-visible-version.md) 取代；扫描方式、不建索引、上限与结果形状继续有效） | 2026-10-20 |
| [0063](0063-a-draft-is-addressed-by-a-short-code.md) | 版本由短码寻址，地址里不写状态 | 已采纳 | 2026-10-20 |
| [0064](0064-a-draft-may-change-a-published-version-may-not.md) | 草稿的内容可以变，已发布版本的内容不能变 | 已采纳（`version_file.mode` 的派生值见 [0166](0166-the-executable-bit-follows-the-path.md)） | 2026-10-20 |
| [0065](0065-blob-reclamation-waits-for-a-grace-period.md) | blob 回收等一个 grace period，文件写不拿表锁 | 已采纳 | 2026-10-20 |
| [0066](0066-grep-uses-a-linear-time-regex-engine.md) | grep 的正则由线性时间引擎执行 | 已采纳；**决定 1 与 6 被 [0067](0067-grep-has-a-cost-ceiling.md) 修正**（`-F` 不再经过引擎，正则另有成本上限） | 2026-10-20 |
| [0067](0067-grep-has-a-cost-ceiling.md) | grep 的执行成本必须有硬上界 | 已采纳 | 2026-10-20 |
| [0068](0068-export-is-a-one-shot-snapshot.md) | 一版 skill 的整包导出是一次性快照，不是工作区 | 已采纳 | 2026-10-21 |
| [0069](0069-metadata-is-a-sidecar-not-content.md) | `.skillmaster/metadata.json` 是传输旁路，不是内容 | 已采纳 | 2026-10-21 |
| [0070](0070-creating-a-skill-creates-its-first-draft.md) | 新建 skill 与它的第一份草稿是一个动作 | 已采纳 | 2026-10-21 |
| [0071](0071-a-deleted-name-answers-with-its-own-code.md) | 提交到已删除的名字答 409 `skill_deleted`，不是泛泛的 400 | 被 0183 取代 | 2026-10-21 |
| [0072](0072-the-handoff-points-at-the-page-that-owns-the-action.md) | 页面专属动作的交接落点指向动作所在的那一页 | 已采纳 | 2026-10-21 |
| [0073](0073-asking-for-access-splits-by-plane.md) | 申请访问按平面分：申请、撤回、读自己的走 API；批准与拒绝留在网页 | 已采纳（§决定 2 被 0140 取代；§决定 1、3 不变） | 2026-10-21 |
| [0074](0074-a-handoff-may-carry-a-preselection.md) | 交接可以携带预选，不能携带执行 | 已采纳 | 2026-10-21 |
| [0075](0075-the-browser-client-is-react.md) | 浏览器端改用 React，其余纪律照 0015 不变 | 已采纳 | 2026-10-21 |
| [0076](0076-the-design-file-is-react-source.md) | 设计稿用 React 写，前端整段搬过去 | 已采纳（决定 #6 被 [ADR 0121](0121-the-design-rules-live-in-the-web-design-skill.md) 取代） | 2026-10-22 |
| [0077](0077-the-cli-grammar-is-singular-and-plural.md) | CLI 的命令按单复数分组 | 部分被取代（`tags` 的归属由 [0083](0083-two-tag-layers.md) 更正：读单个 skill 的标签归 `skill` 组；决定 3「`files` 是正名」由 [0120](0120-file-enumeration-is-ls.md) 取代） | 2026-10-22 |
| [0078](0078-a-category-is-one-shelf.md) | 分类是一个货架：平级、单选；召回交给标签 | 已采纳（写于公开标签进入本版之前；公开标签的落版与两套标签的边界见 [0083](0083-two-tag-layers.md)——分类单选的决定不变） | 2026-10-23 |
| [0079](0079-cli-manages-tags-and-library-references.md) | CLI 管贴法、新建、定义编辑与库引用，不管删除 | 已采纳 | 2026-10-23 |
| [0080](0080-cli-covers-non-high-risk-operations.md) | CLI 覆盖所有非高风险操作 | 已采纳 | 2026-10-23 |
| [0081](0081-every-console-operation-has-a-cli-command.md) | 每处控制台操作都要有 CLI 对应 | 已采纳 | 2026-10-23 |
| [0082](0082-public-and-private-with-my-library.md) | 公域 / 私域：库是引用，公域只搜不列 | 已采纳（§决定 2 的「不挡加载」由 [ADR 0143](0143-public-skills-need-confirmation-in-the-cli.md) 部分取代：服务端语义不变，CLI 的 `invoke` 多一道确认） | 2026-10-23 |
| [0083](0083-two-tag-layers.md) | 两套标签：公开分类、公开标签与我的标签 | 已采纳 | 2026-10-23 |
| [0084](0084-l1-search-extension.md) | 检索的 L1 扩展：`when_to_use`、多词与公开标签 | **部分被取代**——§理由里否决「向量检索」的那一条由 [ADR 0095](0095-semantic-retrieval-is-accepted.md) 取代（2026-10-25）；**其余决定（匹配面、多词取「且」、标签过滤在访问谓词内、排序可解释）仍然有效** | 2026-10-23 |
| [0085](0085-rename-web-to-console.md) | `skillmaster-web` 改名为 `skillmaster-console` | 已采纳 | 2026-10-23 |
| [0086](0086-the-homepage-reads-the-session.md) | 营销首页读取会话，登录后显示已登录态 | 已采纳（§决定 2 的「结束当前会话 → 回到首页」由 [ADR 0141](0141-a-logout-stops-on-a-guide-screen.md) 收窄；其余不变） | 2026-10-24 |
| [0087](0087-cli-exit-codes-are-a-public-contract.md) | CLI 的退出码是公开契约 | 已采纳 | 2026-10-24 |
| [0088](0088-cli-major-version-is-the-api-generation.md) | CLI 的主版本号就是它说的 API 代次 | 部分被取代（决定 2 的「删除一律留到下一主版本」由 [0112](0112-retiring-a-command-within-one-generation.md) 收窄；上线前的命令按 [0077](0077-the-cli-grammar-is-singular-and-plural.md) 决定 4 的「上线前直接把命令收干净」处理——`files` → `ls` 的退役**形态**取自 0112、**依据**是 0077 决定 4（不满足 0112 的三条条件），见 [0120](0120-file-enumeration-is-ls.md)；v32 的 A10 / A12 / A13 / A14 是既有命令的**语义改变**，按同一原则直接改，**不套退役形态**；「弃用提示」不出现，[0113](0113-the-cli-is-called-skmr.md) 决定 3 只在代码里给旧**二进制名**留一处入口） | 2026-10-25 |
| [0089](0089-no-local-skills.md) | 本机不装任何 skill，三条规则写进系统提示词 | 已采纳（取代 [ADR 0002](0002-gateway-skill-and-cli.md) 的决定 1；**§决定 2 的规则文本已由 [ADR 0094](0094-load-the-library-at-session-start.md) 取代**） | 2026-10-25 |
| [0090](0090-batch-publish-confirms-once.md) | 批量发布：一次确认放行多次，但确认单必须逐条列出 | 已采纳 | 2026-10-25 |
| [0091](0091-release-type-decides-the-version-number.md) | 发布类型决定版本号，号仍然在发布时算出来 | 已采纳 | 2026-10-25 |
| [0092](0092-retire-local-copies-only-after-they-are-live.md) | 本机副本在平台已可用、且已备份之后才下线 | 已采纳 | 2026-10-25 |
| [0093](0093-no-skill-discovery-index.md) | 不发布 skill 发现索引 | 部分被取代（「匿名通道只留 `/gateway/STARTUP.md`」那一条由 [0096](0096-guidance-lives-on-the-platform.md) 取代；索引删除的决定仍然有效） | 2026-10-25 |
| [0094](0094-load-the-library-at-session-start.md) | 会话开始时先加载库，而不是等着被问 | 被取代（[ADR 0096](0096-guidance-lives-on-the-platform.md) 取代了规则文本与它的存放位置；本条「这是指令不是保证」的判断仍然成立） | 2026-10-25 |
| [0095](0095-semantic-retrieval-is-accepted.md) | 接受向量检索；索引在发布事务里完成 | 已采纳 | 2026-10-25 |
| [0096](0096-guidance-lives-on-the-platform.md) | 引导内容放在平台上的官方技能里 | **部分被取代**——决定 1 里「规则段是技能内容」那一半由 [ADR 0102](0102-the-rule-block-ships-with-the-cli.md) 取代（规则段改为随 CLI 版本发布）；旅程仍在平台上。其余决定仍然有效。本 ADR 取代 [ADR 0094](0094-load-the-library-at-session-start.md)；并取代 [ADR 0093](0093-no-skill-discovery-index.md) 里「保留 `/gateway/STARTUP.md` 这一条匿名路由」那一条 | 2026-10-26 |
| [0097](0097-the-rule-block-routes-and-help-holds-the-flow.md) | 规则段只触发与指路，流程写在 CLI help 里 | 已采纳 | 2026-10-27 |
| [0098](0098-grep-reads-every-visible-version.md) | grep 的范围是「调用者能读到的版本」 | 已采纳（取代 [0062](0062-grep-is-a-scoped-scan-not-an-index.md) 里「只搜草稿」那一条范围；扫描方式、不建索引、上限与结果形状的结论全部保留——v34 在结果形状上是**向后兼容的扩展**：新增 `match` 字段与可选的上下文行，旧请求的行为不变，见 [`versions/v34-file-toolbox/technical-design.md`](../versions/v34-file-toolbox/technical-design.md) §接口） | 2026-10-27 |
| [0099](0099-the-cli-has-a-beta-channel.md) | CLI 有一条 beta 通道，版本串固定、构建身份用提交号 | 已采纳 | 2026-10-27 |
| [0100](0100-invoke-output-is-platform-assembled.md) | invoke 的输出是平台组装的头部加正文，不是原文透传 | 部分被取代（决定 4 由 [0119](0119-permissions-carry-commands.md) 取代） | 2026-10-27 |
| [0101](0101-the-cli-never-deletes-files.md) | CLI 永不删除文件 | 已采纳 | 2026-10-27 |
| [0102](0102-the-rule-block-ships-with-the-cli.md) | 规则段随 CLI 版本发布 | 已采纳（**部分取代** [ADR 0096](0096-guidance-lives-on-the-platform.md) 决定 1 里「规则段是技能内容」那一半；旅程正文仍留在平台上） | 2026-10-27 |
| [0103](0103-the-cli-updates-itself.md) | CLI 自己更新自己 | 已采纳 | 2026-10-27 |
| [0104](0104-the-agent-knows-where-its-user-level-file-is.md) | 用户级说明文件在哪，由运行它的 agent 决定 | 部分被取代（`--file` / `--agent` 与其路径检查由 [0111](0111-startup-takes-no-flags.md) 取代；「位置由 agent 自己确定」继续有效） | 2026-10-27 |
| [0105](0105-writing-to-a-user-file-starts-with-a-plan.md) | 写进用户文件的动作先出计划，`--yes` 才落笔 | 部分被取代（[0110](0110-the-block-is-a-paragraph-the-cli-stops-managing.md) 与 [0111](0111-startup-takes-no-flags.md) 取代了以 `prompt install/update/uninstall` 与 `startup --plan` 为主语的部分；「改动之前先说明、不经同意不落笔」继续有效，承载它的是旅程正文） | 2026-10-27 |
| [0106](0106-the-checklist-is-recomputed-not-recorded.md) | 安装清单按实际状态重算，不记进度 | 部分被取代（`startup --plan` 由 [0111](0111-startup-takes-no-flags.md) 取代；「按事实重算、不记进度」继续有效，现在由旅程正文承载） | 2026-10-27 |
| [0107](0107-startup-speaks-to-non-technical-users.md) | 启动旅程面向非技术用户，控制台不变 | 已采纳 | 2026-10-27 |
| [0108](0108-importing-local-skills-is-optional.md) | 传技能是可选步骤，不挡后面的旅程 | 部分被取代（`startup --plan` 由 [0111](0111-startup-takes-no-flags.md) 取代；「传技能可选、不挡后面」继续有效，现在由旅程正文承载） | 2026-10-27 |
| [0109](0109-the-rule-block-defaults-to-searching-first.md) | 规则段默认先查：只有闲聊免查 | 已采纳（2026-10-28） | 2026-10-28 |
| [0110](0110-the-block-is-a-paragraph-the-cli-stops-managing.md) | 规则段是用户文件里的一段话，CLI 不再用标记段管理它 | 已采纳（2026-10-28） | 2026-10-28 |
| [0111](0111-startup-takes-no-flags.md) | 删掉 `startup --plan` | 已采纳 | 2026-10-28 |
| [0112](0112-retiring-a-command-within-one-generation.md) | 「平台接管」后，只服务旧写法的命令可以在同一代次内退役 | 已采纳 | 2026-10-29 |
| [0113](0113-the-cli-is-called-skmr.md) | CLI 的命令名改为 `skmr` | 已采纳 | 2026-10-29 |
| [0114](0114-search-exclusion-is-caller-supplied-and-applied-before-ranking.md) | 搜索的排除项由调用方提供，在服务端排序之前施加 | 已采纳 | 2026-10-30 |
| [0115](0115-list-is-an-inventory-and-descriptions-live-in-show.md) | `skills list` 是清单，描述在 `search` 与 `show` | 已采纳 | 2026-10-30 |
| [0116](0116-editing-happens-online.md) | 改 skill 走在线，不走本地往返 | 已采纳（2026-10-30） | 2026-10-30 |
| [0117](0117-content-is-canonicalized-at-the-door.md) | 入库前把跨系统差异统一掉 | 已采纳（2026-10-30；部分取代 [0005](0005-content-addressing.md) §后果「上传时不得改写内容」） | 2026-10-30 |
| [0118](0118-computation-follows-the-data.md) | 计算跟着数据走：单文件的算在客户端，跨文件的算在服务端 | 已采纳 | 2026-10-30 |
| [0119](0119-permissions-carry-commands.md) | invoke 头部的能力清单带命令名：名字要能落到调用上 | 已采纳 | 2026-10-30 |
| [0120](0120-file-enumeration-is-ls.md) | 文件枚举的正名改成 `skill ls`（取代 0077 决定 3） | 已采纳 | 2026-10-30 |
| [0121](0121-the-design-rules-live-in-the-web-design-skill.md) | 设计件那套规则搬进 web-design 的项目页 | 已采纳 | 2026-11-01 |
| [0122](0122-architecture-refactor-is-structural.md) | 架构重构是结构性的，不是战术 DDD 重写 | 已采纳 | 2026-11-01 |
| [0123](0123-modules-publish-an-explicit-interface.md) | 模块只对外发布显式接口 | 已采纳 | 2026-11-01 |
| [0124](0124-file-size-limits-are-a-shrink-only-baseline.md) | 文件行数上限用只减不增的基线强制执行 | 已采纳（§决定 1 的分语言数值由 [ADR 0162](0162-file-size-limit-is-one-threshold.md) 取代为统一 1,000；基线机制与其余各条不变） | 2026-11-01 |
| [0125](0125-cli-splits-by-command-group-in-one-package.md) | CLI 按命令组拆文件，不拆成 internal 子包 | 已采纳 | 2026-11-01 |
| [0126](0126-page-only-actions-open-the-page.md) | 网页专属的动作，在 CLI 里打开那一页 | 已采纳 | 2026-11-01 |
| [0127](0127-never-published-skills-may-be-deleted-by-a-credential.md) | 从未上线的 skill 可以由凭据删除；页面命令先报权限 | 已采纳 | 2026-11-01 |
| [0128](0128-cli-help-does-not-advertise-the-confirmation-switch.md) | CLI 的帮助不宣传确认开关 | 已采纳 | 2026-11-01 |
| [0129](0129-onboarding-delivers-a-skill-not-a-memory-line.md) | 安装交付一个技能，而不是一段全局记忆 | 已采纳 | 2026-11-02 |
| [0130](0130-large-blobs-live-in-object-storage.md) | 大字节进对象存储，小字节留 PostgreSQL | 已采纳（决定 4 中关于 `wc` 的一半被 [ADR 0133](0133-wc-streams-and-is-not-text-capped.md) 取代） | 2026-11-02 |
| [0131](0131-any-file-type-is-accepted-but-only-safe-types-render.md) | 任何类型都收，但只有位图与 PDF 内联 | 已采纳 | 2026-11-02 |
| [0132](0132-media-files-are-version-files.md) | 媒体文件是版本文件，不是独立素材库 | 已采纳 | 2026-11-02 |
| [0133](0133-wc-streams-and-is-not-text-capped.md) | `wc` 流式计数，不受 16 MiB 文本上限 | 已采纳 | 2026-11-02 |
| [0134](0134-an-audit-has-a-scope.md) | 审计有范围：只审活文档与本次新增 | 已采纳 | 2026-11-02 |
| [0135](0135-a-draft-may-be-copied-from-a-version.md) | 草稿可以从一版原样复制出来，内容唯一性不再约束草稿 | 已采纳 | 2026-11-04 |
| [0136](0136-cli-file-verbs-follow-intent.md) | CLI 读写命令按意图分，整份覆盖前先读 | 部分被取代（§决定 4 的放行条件由 [ADR 0177](0177-read-proof-binds-to-file-content.md) 取代；其余决定仍有效） | 2026-11-04 |
| [0137](0137-edit-takes-stdin-and-batches.md) | `edit` 接 stdin 与批量 JSON，一批只写一次 | 已采纳 | 2026-11-04 |
| [0138](0138-sharing-is-an-invitation.md) | 共享是一条邀请，对方接受才生效 | 已采纳 | 2026-11-04 |
| [0139](0139-a-notification-points-at-a-business-object.md) | 通知指向业务对象，不是第二份状态 | 已采纳 | 2026-11-04 |
| [0140](0140-cli-can-approve-access-requests.md) | CLI 也能批准与拒绝权限申请 | 已采纳 | 2026-11-04 |
| [0141](0141-a-logout-stops-on-a-guide-screen.md) | 退出登录后停一页引导 | 已采纳（收窄 [ADR 0086](0086-the-homepage-reads-the-session.md) §决定 2；§理由 里「`logout` 只清本机」现只对 API 密钥成立，CLI 登录凭据由 [ADR 0142](0142-cli-logout-ends-its-own-server-login.md) 收窄） | 2026-11-05 |
| [0142](0142-cli-logout-ends-its-own-server-login.md) | CLI 退出时结束服务端这次登录 | 已采纳（收窄 [ADR 0038](0038-default-credential-is-an-api-key.md) §决定 7 到 API 密钥） | 2026-11-05 |
| [0143](0143-public-skills-need-confirmation-in-the-cli.md) | 公域 skill 的调用先确认：CLI 的一条确认轨，不是权限门禁 | 已采纳 | 2026-11-05 |
| [0144](0144-a-new-account-starts-with-the-official-skills.md) | 新账号注册后带上两个官方 skill 的库引用 | 已采纳 | 2026-11-05 |
| [0145](0145-deploy-runs-the-artifact-ci-built.md) | 上线只部署 CI 构建出的那份产物 | 已采纳 | 2026-11-05 |
| [0146](0146-public-is-platform-endorsement.md) | 公开是平台背书：只有保留命名空间能把 skill 设为公开 | 已采纳 | 2026-11-05 |
| [0147](0147-a-source-is-a-typed-connection-plus-signals.md) | 来源是「有类型的连接 + 适配器解释的通用信号」 | 已采纳 | 2026-11-05 |
| [0148](0148-a-license-belongs-to-the-version-and-not-to-the-file-tree.md) | 许可证属于版本，全文不进 `version_file` | 已采纳（「五种状态」部分由 [ADR 0154](0154-v49-does-not-adjudicate-license-conflicts.md) 取代） | 2026-11-05 |
| [0149](0149-use-is-counted-per-account-per-day.md) | 使用信号按「账号 × 天」去重，只展示分桶 | 已采纳 | 2026-11-05 |
| [0150](0150-requesting-a-higher-rung-needs-library-membership.md) | 申请更高档位要求先入库 | 已采纳 | 2026-11-05 |
| [0151](0151-full-license-text-links-to-its-source.md) | 许可全文链回来源，不做站内许可页 | 已采纳 | 2026-11-06 |
| [0152](0152-cumulative-calls-are-their-own-signal.md) | 累计调用次数是一份独立信号 | 已采纳 | 2026-11-06 |
| [0153](0153-usage-metrics-is-its-own-module.md) | 使用度量是独立模块，按 scope 记录 | 已采纳 | 2026-11-06 |
| [0154](0154-v49-does-not-adjudicate-license-conflicts.md) | v49 不裁决许可冲突，冲突校验放到下一版的入库流程 | 已采纳 | 2026-11-06 |
| [0155](0155-open-source-sync-passes-three-gates.md) | 开源同步只收宽松许可，并必须过许可、安全、人工发布三闸 | 已采纳 | 2026-11-06 |
| [0156](0156-sync-writes-as-the-official-namespace.md) | 同步以服务端身份写入官方命名空间，按上游 commit 幂等 | 已采纳 | 2026-11-06 |
| [0157](0157-sync-is-a-source-neutral-pipeline.md) | 同步是来源无关的底座，来源只提供适配器 | 已采纳 | 2026-11-06 |
| [0158](0158-sync-runs-inside-the-server.md) | 同步任务留在 server 进程内，按可抽出的接口分层 | 已采纳 | 2026-11-06 |
| [0159](0159-manual-sync-is-triggered-by-ci.md) | 手动触发同步只开 CI + 本机回环端点，不做 CLI 与控制台 | 已采纳 | 2026-11-06 |
| [0160](0160-imports-are-renamed-by-an-optional-source-prefix.md) | 导入的开源 skill 按可选来源前缀重命名 | 已采纳 | 2026-11-07 |
| [0161](0161-imported-skill-state-is-fixed-when-the-draft-is-created.md) | 导入 skill 的来源状态在草稿创建时定稿 | 已采纳（取代 v49 技术设计 §刷新时机的「发布后刷新」与「每日全刷」）；正文两处「第 14 条」（背景与理由）所述的批量发布 30 秒异步超时，已于 2026-11-07 由 [`v50` 迭代 0003](../versions/v50-open-source-sync/iterations/0003-批量发布超时与同步上限.md) 处理（`spring.mvc.async.request-timeout` → 5m） | 2026-11-07 |
| [0162](0162-file-size-limit-is-one-threshold.md) | 文件行数上限统一为 1,000 行 | 已采纳（取代 [ADR 0124](0124-file-size-limits-are-a-shrink-only-baseline.md) §决定 1 的分语言数值；基线机制不变） | 2026-11-07 |
| [0163](0163-a-repository-is-downloaded-once-per-run.md) | 一个仓库在一轮同步里只下载一次 | 已采纳（取代 [v50 TD](../versions/v50-open-source-sync/technical-design.md) 里「每个条目按自己的 revision 各拉一次整包」的取数口径；[ADR 0157](0157-sync-is-a-source-neutral-pipeline.md) 的底座与适配器分工不变） | 2026-11-07 |
| [0164](0164-state-is-tiered-by-what-it-writes.md) | 状态按「要不要写」分三层，凭证永远不落项目 | 已采纳 | 2026-11-08 |
| [0165](0165-run-resolves-inside-scripts.md) | `skmr run` 起进程，只改写 `scripts/` 内的相对路径 | 已采纳（§7 的解析路径由 [ADR 0178](0178-run-resolves-drafts-through-the-author-plane.md) 扩展） | 2026-11-08 |
| [0166](0166-the-executable-bit-follows-the-path.md) | 可执行位由路径决定，不由文件携带 | 已采纳 | 2026-11-08 |
| [0167](0167-sandbox-compatibility-is-capability-based.md) | 沙箱兼容按能力探测，不按 agent 适配 | 已采纳 | 2026-11-08 |
| [0168](0168-hosted-credentials-are-api-keys-not-identity-passwords.md) | 托管的是调用第三方服务的 API 凭据，不是身份密码 | 已采纳 | 2026-11-08 |
| [0169](0169-the-first-version-injects-without-proxying.md) | 第一版只做注入，本地反代与平台代转都推迟 | 已采纳 | 2026-11-08 |
| [0170](0170-the-root-key-lives-in-a-cloud-kms.md) | 凭据的根密钥在云 KMS，一个 CMK 覆盖全部凭据 | 被 [0172](0172-the-root-key-is-huawei-cloud-kms.md) 取代 | 2026-11-08 |
| [0171](0171-api-key-expiry-is-a-choice.md) | API key 的有效期是用户的选择，不是部署的一个数 | 已采纳 | 2026-11-09 |
| [0172](0172-the-root-key-is-huawei-cloud-kms.md) | 根密钥改由华为云 KMS 托管 | 已采纳 | 2026-11-09 |
| [0173](0173-a-copy-is-a-snapshot-in-your-own-namespace.md) | 复制是把一版已发布内容放进自己的命名空间 | 已采纳 | 2026-11-09 |
| [0174](0174-two-help-layers.md) | 帮助分两层：单命令参考与 Guide 手册 | 已采纳 | 2026-11-09 |
| [0175](0175-account-deletion-is-immediate-with-a-fixed-retention.md) | 账号注销立即生效；个人内容删除，协作与公开内容按归属保留 | 已采纳 | 2026-11-09 |
| [0176](0176-publishing-transfers-ownership-out-of-the-personal-namespace.md) | 发布到公开市场即转出个人命名空间 | 已采纳（原则；实现留给允许普通用户发布公开的那一版） | 2026-11-09 |
| [0177](0177-read-proof-binds-to-file-content.md) | 读凭证绑文件内容，不绑整版摘要 | 已采纳 | 2026-11-10 |
| [0178](0178-run-resolves-drafts-through-the-author-plane.md) | `run` 认同一套版本引用，草稿可在发布前执行 | 已采纳 | 2026-11-10 |
| [0179](0179-personal-usage-is-visible-only-to-its-owner.md) | 行为明细只给本人，聚合给所有者，保留 90 天 | 已采纳 | 2026-11-10 |
| [0180](0180-personal-scenario-ranks-by-evidence-library-only.md) | 个人适用场景按证据分级排序，只在库内生效，v1 不训练 | 已采纳 | 2026-11-11 |
| [0181](0181-text-limits-use-unicode-code-points.md) | 文本上限统一用 Unicode 码点，写入与发布同契约 | 已采纳 | 2026-11-11 |
| [0182](0182-owner-usage-is-a-period-view.md) | 所有者的「使用」页签是区间视图，平台信号不跟着变 | 已采纳 | 2026-11-11 |
| [0183](0183-deleting-a-skill-releases-its-name.md) | 删除释放名字，数据仍然保留 | 已采纳 | 2026-11-11 |
| [0184](0184-taking-a-public-skill-down-is-a-service-lifecycle.md) | 公开 skill 的退场按服务生命周期 | 已采纳 | 2026-11-11 |
| [0185](0185-credentials-default-to-a-file.md) | CLI 凭据默认存文件，钥匙串改为可选 | 已采纳 | 2026-11-11 |
| [0186](0186-the-cli-writes-a-narrow-sandbox-allowlist.md) | CLI 在安装时改写客户端的沙箱配置，范围限定为 SkillMaster 自己的目录与域名 | 已采纳 | 2026-11-11 |
