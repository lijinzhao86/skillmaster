# v15-marketing-home · 技术设计

> **最后更新**：2026-10-24
> **状态**：已按本文实现（2026-10-24）；之后的行为改动仍要先回写本文，再改代码
> **2026-10-24 修订**：首页从「零请求」改为「一次只读会话查询」，已登录态进范围（[ADR 0086](../../decisions/0086-the-homepage-reads-the-session.md)）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

<!--
本章之外的硬约束：
- 测出来的数字必须带口径（见下方表格的「口径」列），让读者能自己复现。
- 引用外部事实必须带出处，且区分一手与二手。
- 不复述 ADR 的理由；不负责任何产品决策的论证。
-->

## 架构

**一句话：新增一个只产出静态文件的公开站点项目 `skillmaster-site/`；它只向控制台要一样东西——「我是谁」——入口地址是构建期常量。写会话仍然发生在控制台。**

```
                   GET https://www.skillmasterai.com/
访客浏览器 ─────────────────────────────────────► www
   │                                                │
   │  静态 HTML / CSS / JS / 图片                    │  skillmaster-site 的 dist
   │                                                │
   │  启动时一次只读会话查询 ─── credentials ────────► https://console.skillmasterai.com/web/session
   │  （2xx 且响应体是账号 → 已登录；其余 → 未登录）   │  （已有；本版只加 cookie Domain 与一条 CORS）
   │                                                │
   └── 点「登录」/「开始使用」/「进入控制台」 ─ 绝对链接 ► https://console.skillmasterai.com/{login,register,}
                                                     │
                                                     └─ 控制台（已有：会话 + CSRF + API + OAuth）
```

- **什么在客户端**：整个首页，外加一次会话查询。没有服务端渲染、没有自己的会话、没有存储。
- **什么在服务端**：控制台已有的登录 / 注册 / 找回密码 / 页面，加上两项配置——会话 cookie 的 `Domain` 放宽到 `skillmasterai.com`，以及只对 `GET /web/session` 放行 www 源的 CORS（带 credentials）。没有新接口。
- **为什么这个划分成立**：www 只读不改。它要知道「谁在看」才能画对导航，但任何写（登录 / 退出）仍留在控制台，所以 www 上不需要第二套 CSRF 与跨源错误处理（[ADR 0086](../../decisions/0086-the-homepage-reads-the-session.md)）。
- **渲染顺序**：先按未登录渲染，会话查询回来后替换导航；查询失败按未登录，页面绝不空等（fail-open）。不做骨架屏。
- **参考产品**：`deepseek.com` 的 www 没有登录面，所有 CTA 是跳子域的普通链接（2026-10-23 实地核对）；本版与它不同的地方正是「已登录时导航认人」，这是产品明确要的。

**这一版不引入**：路由库、状态库、API 客户端库、会话模块、SSR、CMS。那次会话查询是手写的一个 `fetch`，不因此引入任何依赖。

## 数据模型

**没有。** 本版不建表、**不写任何 cookie**、不写 `localStorage`、不写 `sessionStorage`。会话只**读**一次（`GET /web/session` 的响应在内存里，用于画导航），不落任何存储。

首页的内容全部在代码与设计稿里；没有需要持久化的用户数据。唯一随环境变化的是控制台地址（构建期变量，见 §接口）。

首页的 prompt 在设计件里是 `HomeApp.tsx` 里的一句字符串常量，在产品里是 `skillmaster-site/src/App.tsx` 的 `AGENT_PROMPT`（同一句话）；复制只写系统剪贴板，不落任何存储。成功 / 失败的反馈、当前在第几屏（`0 / 1`），都是组件内的临时状态，刷新即消失。

## 接口

本版**不新增**服务端 HTTP 接口；它消费一条已有的只读接口，对外契约因此包含**页面地址、链接目标与这一条请求**：

| 项 | 值 | 说明 |
|---|---|---|
| 站点根 | `https://www.skillmasterai.com/` | 只有这一个页面 |
| 登录入口 | `${CONSOLE_URL}/login` | 普通 `<a href>`；不是表单、不是浮层 |
| 开始使用 | `${CONSOLE_URL}/register` | 同上 |
| 控制台 | `${CONSOLE_URL}/` | 页脚「产品」列；**未登录**时导航不放入口，**已登录**时导航的主按钮就是它（`进入控制台`） |
| 退出登录 | `${CONSOLE_URL}/logout` | 普通链接（导航，不是写）；控制台新增这个页面路由：结束当前会话后回到首页。www 不发 CSRF、不发 POST |
| 三张卡片 | 首屏 Hero 内，prompt 下面 | 分别对应托管 / 分享 / 市场，不挂眉标；卡片上方没有单独标题；常显，不参与切换 |
| 页脚 | 三列：产品 / 文档 / 关于 | 产品列三条为控制台真实链接；其余七条为占位；第二屏时 `is-visible` |
| 幻灯片状态 | `.deck.is-second`；页脚 `is-visible` | 前端行为，无 HTTP；一次手势切一整屏（700ms），没有中间位置；顶栏固定不动，页脚淡入上移 |
| 占位链接 | `href="#"` + `preventDefault` | 文档 / CLI / 协议 / 关于 / 更新日志 / 隐私 / 条款，页脚三列共七条；本版不导航 |
| 构建期变量 | `VITE_CONSOLE_URL` | 开发默认 `http://localhost:5173`；生产必须是 `https://console.skillmasterai.com`（不带结尾斜杠）。**`vite build` 少了这个值直接失败**，不留一个指向 localhost 的产物 |
| 会话读 | `GET ${CONSOLE_URL}/web/session` | **唯一一条请求**。`credentials: 'include'`；2xx 用返回的账号画已登录导航，401 / 网络失败 / 超时（3s）→ 未登录。不读 CSRF 令牌、不发写、不落存储。依赖 ADR 0086 的两项服务端配置 |
| 页面标题 | `SkillMaster — 把你的 skill 托管起来，任何 agent 一行加载。` | 设计稿的 `<title>` |
| prompt 复制 | 页面内动作，无 HTTP | 把 `#agentPrompt` 的同一句文本写入系统剪贴板；2 秒内显示「已复制」，两条复制路径都失败时显示「复制失败」 |
| 未知路径 | 待定（见开放问题 Q3） | 静态托管的 404 行为，不在首页内部 |

**鉴权**：只读。首页不持有会话，只用浏览器的会话 cookie 换一次「我是谁」。cookie 是 `HttpOnly`（www 的脚本读不到它），所以这次查询必须走 `fetch(..., {credentials: 'include'})` 由浏览器带上；这也是为什么不能改用一个可被脚本读的提示 cookie（[ADR 0086](../../decisions/0086-the-homepage-reads-the-session.md)）。**开发环境**：`skillmaster-site` 的 dev server 把 `/web/` 代理到**本地服务端**（`localhost:8080`；控制台的 dev server 只是它的前端，代理打的是服务端），同源，不需要 CORS；生产的 CORS 只放行 www 源。

**错误行为**：只有一条可能失败的调用，它**没有错误态**——失败、超时、401 一律退化成「未登录」，与真正未登录的用户看到同一页。这个方向是安全的：最坏是让已登录的人多点一次「登录」，不会把人误认成别人。静态资源加载失败由浏览器默认行为处理。控制台那边的登录 / 注册错误由控制台自己负责，本版不改。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 新项目 `skillmaster-site/`：Vite + React + TypeScript，从设计稿搬首页；构建出纯静态产物 | `type-check` / `test` / `build` 通过；产物不含服务端代码 |
| P1 | 首页三部分（导航、首屏 Hero（含 prompt 与三张卡片）、页脚）、两屏切换与固定导航、响应式、页标题、入口与占位链接；`VITE_CONSOLE_URL` 注入；**已登录导航**（一次只读会话查询 + 账号菜单，退出链接到 `console/logout`）；**服务端两项配置**（cookie `Domain`、只对 `GET /web/session` 的 CORS）；**控制台 `/logout` 路由** | PRD 验收 #1–#9、#11、#13–#15（#10 落在 P2 的设计交付件，#12 由 P4 的 CLI 对照覆盖） |
| P2 | 设计稿回写：首页演示去浮层 / `?client_id`；**保留一个标注过的已登录变体**（`?signed-in` + 演示条）；授权屏搬进控制台演示；旧文案更新（首页不再使用截图）；检查脚本跟着迁移，并钉住两种导航态 | PRD 验收 #10；三套检查全绿、图重出、人看过 |
| P3 | 部署：`www` 静态托管、apex 301、DNS/TLS（由部署侧执行）；新增 `skillmaster-site` 的 CI | 仓库内那一半是 PRD 验收 #16（CI 四步）；托管与 apex 由部署侧验收 |
| P4 | 测试与循环审计；PRD 验收全量走查 | PRD 全部验收有证据 |

**每期明确不做**：

- P0 不做页面内容与部署；
- P1 不做设计稿改动（先按现设计搬，P2 再回写内容）；**P1 的服务端改动只有两项配置**——不加接口、不改会话语义；
- P2 不动控制台产品代码（授权屏的“搬家”只发生在设计演示里）；
- P3 不做文档页与 `docs.` 子域；
- P4 不顺手改控制台既有缺陷。

> 设计稿与实现谁先：SOP 要求设计稿先于代码。本版的 P2 与 P0/P1 在时间上可以并行起草，但**页面内容以 P2 回写后的设计稿为准**；P1 若先搬了旧文案，P2 必须逐处对齐。

## 实现落点

**代码已经按这里落地**（2026-10-24）。路径是权威：改哪一处，看这张表。

| 落点 | 文件 | 说明 |
|---|---|---|
| 站点项目 | `skillmaster-site/` | Vite + React + TS，与 `skillmaster-console/` 同一套配置（Node 版本、类型严格度、vitest + jsdom） |
| 页面 | `skillmaster-site/src/App.tsx` | 从 `design/src/home/HomeApp.tsx` 搬来，只改三处：状态来自会话、没有演示条、链接来自 `VITE_CONSOLE_URL` |
| 会话读 | `skillmaster-site/src/session.ts` | 一次 `GET`，`credentials: 'include'`，3s 截止；任何失败都是未登录 |
| 地址 | `skillmaster-site/src/console.ts` | `VITE_CONSOLE_URL` 去空白、去尾斜杠；`''` 是控制台首页（带 `/`） |
| 样式 | `skillmaster-site/src/styles.css` | 设计件的 `styles.css` 原样搬来，只改 `--demo-bar: 0`（产品没有演示条），并删掉演示条自己的规则。设计件把挂载点那条 `#app { display: contents }` 放在另一张 `runtime.css` 里，因此产品也照搬一张 `runtime.css` 并在 `main.tsx` 里按同样的顺序引入 |
| 开发代理 | `skillmaster-site/vite.config.ts` | dev server 在 5174，`^/web/` 代理到 8080（带前缀的正则，`/webhooks` 之类不会被一起吞掉）——开发环境因此同源，不需要 CORS |
| 服务端配置 | `skillmaster-server/src/main/java/com/skillmasterai/config/SiteConfig.java` | 一条只针对 `/web/session` 的 CORS，与会话 cookie 的 `Domain`（后者是 `DefaultCookieSerializerCustomizer`——**在 Boot 的序列化器上加一处**，不替换它，名字/路径/SameSite/Secure 仍由 `server.servlet.session.cookie.*` 决定） |
| 服务端属性 | `application.yml` → `skillmaster.site.origin` / `skillmaster.site.cookie-domain` | 环境变量 `SKILLMASTER_SITE_ORIGIN` / `SKILLMASTER_COOKIE_DOMAIN`；都为空时：没有 CORS、cookie 主机级 |
| 控制台退出页 | `skillmaster-console/src/pages/LogoutPage.tsx` + `App.tsx` 的 `/logout` | `standalone` + `blankWhileLoading`：不画品牌行，等待会话读的那段时间**什么都不画**（**只有这一条路由这样**——`/cli-sign-in` 等其它 standalone 页仍显示「加载中…」）；发一次 `POST /web/logout`（带 CSRF，**StrictMode 下 effect 跑两次也只发一次**），成功就 `replace` 回站点；失败才画一行与一条回去的链接 |
| 控制台构建变量 | `VITE_SITE_URL`（默认 `http://localhost:5174`） | 退出后回哪；**`vite build` 少了这个值直接失败** |
| CI | `.github/workflows/site.yml` | 与 console 同形状：install → type-check → test → build |

**实现时定下的三件事，都是被事实逼出来的**：

1. **`Domain` 不带前导点。** 第一版写的是 `.skillmasterai.com`，Tomcat 的 cookie 处理器直接拒绝（`Invalid cookie domain`），注册接口因此 500。RFC 6265 本来就把前导点当可忽略，所以 `skillmasterai.com` 等价且能用。
2. **「退出登录」在演示件里也是链接。** 产品只能给一条普通链接（www 不写会话），而演示件原来是按钮；同一个控件两种做法迟早漂，所以把演示件也改成 `<a href="?">`（去掉 `?signed-in`）。
3. **`/logout` 没有设计稿。** 它存在的时间只有一次请求：成功时什么都不画，失败才画一行与一条回去的链接。**这一态设计稿没有画过**，是本次实现新造的；已在设计说明里标出，待设计侧确认要不要补一屏。

**此外，本版的失败全都是安静的**——错了也一样出页面，只是少一个状态或少一次跳转。所以三处要在最早能看见的地方吵出来：

| 哪里 | 怎么吵 |
|---|---|
| `VITE_CONSOLE_URL` / `VITE_SITE_URL` 缺失 | `vite build` 直接失败（`vite.config.ts` 的构建期检查），而不是产出一个指向 localhost 的页面 |
| `skillmaster.site.origin` 写成 `*` 或一串源；结尾带 `/` | 前两种**启动即失败**；结尾斜杠与首尾空白就地规范化（浏览器的 `Origin` 从来没有斜杠，带着它等于一条匹配不上的规则） |
| `skillmaster.site.cookie-domain` 写成 `https://…`、带端口或带前导点 | 前两种启动即失败；前导点就地去掉（Tomcat 拒绝带点的写法） |

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 生产域名与 apex 行为是什么？ | `www.skillmasterai.com` 承载站点，`console.skillmasterai.com` 承载控制台，apex `skillmasterai.com` 301 到 `www` | 部署前用 DNS 与实际证书核对；写进部署说明 |
| Q2 | 占位链接最终指向哪里？ | 本版保留 `#`；以后在同一个 `skillmaster-site` 里做文档 / 更新日志 / 协议页，`CLI` 可指文档里的一节 | 产品负责人给真实地址后逐条替换；不在本版定 |
| Q3 | 静态托管遇到未知路径怎么办？ | 回 `index.html`（单页）或专用 404，二选一由部署形态决定 | 部署时按 nginx/CDN 能力定；若回 index，首页不显示任何“未找到”内容 |
| Q4 | 要不要 `meta description` / Open Graph？ | 本版只设设计稿已有的 `<title>`；不加设计稿没有的元数据 | 有 SEO / 分享需求时，先补设计稿再实现 |
| Q5 | 文档将来是 `/docs` 还是 `docs.` 子域？ | 同一个 `skillmaster-site` 产出；子域是 host 别名还是路径由部署定 | 做文档页时再定，不在本版 |
| Q6 | 授权请求屏搬到控制台演示后，谁钉它？ | 在控制台那一套检查里新增一组授权地址的断言；首页检查只钉“没有授权入口” | P2 实现时补检查，跑通后写进 `design/README` |
| Q7 | 首页是否需要产品截图或终端演示？ | 不需要：内容密度照 DeepSeek，首页只留一句可复制 prompt；`design/assets/console-light.png` 不再被首页引用（旧方向稿仍在用） | 2026-10-23 用户看过后定；若以后要展示产品真身，先补设计稿 |
| Q8 | 首页要不要等字体/图片的加载态？ | 不用；静态页按浏览器默认加载，设计稿没有加载态 | 若以后加大图/自定义字体，再评估 |
| Q9 | 在别的标签页退出后，www 怎么知道？ | **本版不做**：重查会让「每次页面加载恰好一条请求」这条验收变成「每次可见还要再来一条」，口径要先改清楚再实现。下一版再定（倾向：`visibilitychange` + ≥30s 节流，不轮询） | 需要时先改验收 #2 与 T3 的口径，再实现 |
| Q10 | 会话查询挡住首屏吗？ | 不挡：先渲染未登录态，响应回来只换导航；首屏文案与卡片不依赖它 | 实现时把查询放在 `useEffect` 里；验收 #7 有「会话失败页面仍完整」一条 |

**不写"待定"**：以上每条都有当前倾向与回答方式。

## 附录

### 附录 A · 与现有代码的关系

| 现有物 | 本版怎么处理 |
|---|---|
| `skillmaster-console/` | **一处新路由**：`/logout`——结束当前会话后回到 `www` 首页，不改会话语义（做 #11）。注册成功页的文案纠正（「管理」→「我的 skill」）**不在本版做**：它没有产品负责人的批准，且会让「不做 #10」的三处基础设施改动变成四处；等到那一版一起改 |
| `skillmaster-server/` | **两处配置**：会话 cookie 的 `Domain` 放宽到 `skillmasterai.com`，以及只允许 `www` 源、只允许 `GET /web/session` 的 CORS（带 credentials）。没有新接口、不改会话语义（[ADR 0086](../../decisions/0086-the-homepage-reads-the-session.md)） |
| `skillmaster-cli/` | 零改动 |
| `design/src/home/` | 改成纯营销：去浮层、`?client_id`；去地址线 / 终端 / 痛点 / 卖点 / 步骤，只留一句 prompt 与三张解释「随时在场」的卡片；CTA 改为链接；**保留已登录变体**（`?signed-in`）与演示条，它对应产品的已登录态 |
| `design/src/console/` | 接收授权请求屏（原本只在首页演示里） |
| `design/src/shared/auth/` | 保持共享；控制台演示继续用它渲染登录 / 注册 / 找回密码 |
| `design/assets/console-light.png` | 不再被首页引用（`dir1` / `dir2` 历史方向稿仍用）；不重拍 |
| `skillmaster-site/` | **新增** |

### 附录 B · 来源与出处

| 来源 | 类型 | 用途 |
|---|---|---|
| `design/src/home/HomeApp.tsx` + `styles.css` | 一手 | 页面结构、类名、文案、尺寸、断点 |
| `design/README.md` | 一手 | 首页的内容与交互规格（v15 起；`spec.md` 是最初三版输入，历史） |
| `design/voice-spec.md` | 一手 | 名词表：上线 / 提交 / 档位 / 我的 skill |
| `https://www.deepseek.com/`（2026-10-23 核对） | 一手（外部） | 交互与内容密度参考：www 无登录、CTA 跳子域、一个 Hero + 少量入口 + 页脚 |
| [ADR 0085](../../decisions/0085-rename-web-to-console.md) | 一手 | **只有项目改名**；分域与公开站点形态由本版文档决定（0085 自己写明不在它的射程里）。首页读取会话见 [ADR 0086](../../decisions/0086-the-homepage-reads-the-session.md) |

### 附录 C · 已测量的数据

| 指标 | **口径**（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| 控制台测试数 | `skillmaster-console` 的 `npm run test`，2026-10-24 | **28 文件 / 544 用例**（v15 新增 6 条：`/logout` 页 5 条 + 路由表把「空白等待」限定在 `/logout` 的 1 条；实现前的基线是 538） |
| 控制台构建大小 | `npm run build`（含本版 `/logout`），2026-10-24，`gzip -9` | JS **594.78 kB raw / 185.35 kB gzip**；CSS **87.61 kB / 15.16 kB** |
| 首页产物大小 | `skillmaster-site` 的 `npm run build`（含 `runtime.css`），2026-10-24，`gzip -9` | JS **231.72 kB raw / 71.05 kB gzip**；CSS **14.65 kB / 4.11 kB**；HTML **0.53 kB / 0.41 kB** |
| 首页 API 请求数 | 浏览器 Network 与实际代码（`session.ts`），2026-10-24 | **每条页面加载恰好 1 条**：`GET /web/session`；写请求 0 |
