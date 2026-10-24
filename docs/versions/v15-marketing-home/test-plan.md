# v15-marketing-home · 测试方案

> **最后更新**：2026-10-24
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 `/` 渲染营销首页，无控制台外壳 / 登录表单 / 浮层 | T1、T2 | 部分执行 |
| #2 运行时只发起一次会话读，不请求 `/api/v1/*`、`/oauth/*`，不发写 | T3 | 通过 |
| #3 导航的 `登录` / `开始使用` 与页脚「产品」列指向 console 的对应地址 | T4 | 部分执行 |
| #4 与设计稿逐处一致（含 ≤480px 只留头像） | T5、T10 | 部分执行 |
| #5 占位链接可渲染、点击不导航 | T6 | 通过 |
| #6 深色系统下保持浅色 | T7 | 未执行 |
| #7 有已登录态，两种态只差导航；会话失败按未登录 | T2、T14 | 部分执行 |
| #8 控制台零回归 | T8 | 通过 |
| #9 `skillmaster-site` 产物纯静态 | T9 | 通过 |
| #10 设计交付件与源一致、检查通过、图重出 | T10 | 部分执行 |
| #11 页面标题是设计稿那一句 | T1 | 部分执行 |
| #12 无新增 CLI 命令（`/logout` 为已确认的豁免） | T11 | 通过 |
| #13 prompt 可复制、内容一致、有反馈 | T12 | 通过 |
| #14 首屏是 Hero + 三张卡片；下滑后页脚入场、顶栏固定不动 | T13 | 部分执行 |
| #15 已登录端到端可用（cookie `Domain`、只读 CORS、`XSRF-TOKEN` 仍主机级） | T15 | 部分执行 |
| #16 站点 CI 覆盖构建与检查（做 #10 的仓库内那一半） | T16 | 部分执行 |

## 用例

### T1 · 首页渲染

- **前置**：`skillmaster-site` 已构建；无后端。
- **步骤**：打开 `/`；读取导航、Hero（含 prompt 与三张卡片）、页脚；读 `<title>`；读 `#agentPrompt` 的文本；量 `.hero` 高度、`.cards-grid` 与 `#foot` 的位置；数 `.card` 与 `.foot-col`；读每张卡片的标题 / 正文。
- **期望**：三段齐全，且没有 `.horizon` / `.term` / `.split` / `.points` / `.steps`；prompt 是「请帮我下载并安装 SkillMaster CLI。」；Hero 至少一屏高、页脚在首屏之下；三张卡片整块位于首屏内，卡片上方没有 `#whyTitle` / `.cards-head`，卡片内没有 `.card-eyebrow`；`.card` 为 3 张，标题依次是「云端加载，随处调用」「技能共享，知识沉淀」「遇到问题，总有办法」，正文依次是「把 skill 保存到云端，换哪个 agent 都能用。」「把 skill 分享给朋友和同事，他们随时都能用上最新的知识。」「1000+ 个精选 skill，总有一个能解决问题。」；`.foot-col` 为三列；文案与设计稿逐字一致；标题为「SkillMaster — 把你的 skill 托管起来，任何 agent 一行加载。」。
- **判定**：通过 / 失败（记录缺失或不同的段落）。

> 「1000+」与「精选」是**上线口径**（见 `design/README.md`）：发布前要用真实数量与筛选动作核对，达不到就按设计说明回退成「公开的 skill，总有一个能解决问题」。回退时这条期望与 `home-check` 的断言要一起改——不要把一句可能变的话钉死在两处。

### T2 · 没有控制台外壳，导航随会话变

- **前置**：`skillmaster-site` 与控制台都已构建，服务端在跑；先在控制台登录，得到一份带会话的浏览器上下文（cookie 见 T15）。
- **步骤**：检查 DOM 是否有 `.app`、`.sidenav`、`.dialog`、登录 `<form>`；对比两次访问的导航与首屏其余内容。
- **期望**：两次都无外壳、无表单、无浮层；**未登录**导航只有 `登录` / `开始使用`，**已登录**导航是 `进入控制台` + 账号（头像、用户名），没有 `登录` / `开始使用`；两次的首屏标题、prompt、三张卡片、页脚完全相同。
- **判定**：通过 / 失败（记录两侧导航与首屏差异）。

### T3 · 只读一次会话

- **前置**：两条独立路径，各跑一次——①测试环境 stub `fetch`，读调用清单；②不 stub，用浏览器 Network 面板看真实请求（stub 之后 Network 里不会出现那条请求，两者不能混用）。
- **步骤**：加载首页到 `load`；记录所有请求（含方法）；再在已登录上下文重复一次。
- **期望**：**每次页面加载**发往后端的请求恰好一条：`GET ${CONSOLE_URL}/web/session`，带 credentials；没有 `/api/v1/*`、没有 `/oauth/*`、没有任何非 GET 请求；其余全是静态资源。本版不做「重新可见时重查」（TD Q9），所以重复切标签页不产生第二条。
- **判定**：通过 / 失败（列出请求清单与方法；多一条或少一条都算失败）。

### T4 · 入口

- **前置**：分别用开发配置与生产构建。
- **步骤**：读导航的 `登录` / `开始使用`，以及页脚「产品」列的 `控制台` / `开始使用` / `登录` 的 `href`。
- **期望**：开发为 `http://localhost:5173/login` / `register` / `/`；生产为 `https://console.skillmasterai.com/login` / `register` / `/`。`VITE_CONSOLE_URL` 本身不带结尾斜杠；页脚「控制台」那一条按 `CONSOLE_URL + '/'` 拼出，以 `/` 结尾是对的。
- **判定**：通过 / 失败。

### T5 · 响应式与设计对照

- **前置**：首页可打开；设计稿可打开。
- **步骤**：在 1440 / 1000 / 720 三档并排看；已登录态再收窄到 480px 以下看账号控件（只留头像、字不换行）；核对首屏满高、导航固定、Hero 字号、prompt 在 720px 下换行不溢出、页脚三列与纵向堆叠。
- **期望**：与设计稿一致；无横向滚动、无溢出、无孤字行。
- **判定**：通过 / 失败；**像不像由人判定**，不由断言单独代替。

### T6 · 占位链接

- **前置**：首页可打开。
- **步骤**：依次点击页脚的文档 / CLI / 协议 / 关于 / 更新日志 / 隐私 / 条款。
- **期望**：地址不变、页面不跳转、不出现错误；这些链接在 PRD 与设计稿里都标为占位。**品牌链接也是占位**（`href="#"`），同样不导航。
- **判定**：通过 / 失败。

> **占位链接共八条**：页脚七条 + 品牌一条。本轮审计发现 PRD 里写的是「七条」，漏了品牌那一条；PRD 做 #9 已改为「七条 + 品牌 = 八条」。

### T7 · 深色系统

- **前置**：浏览器模拟 `prefers-color-scheme: dark`。
- **步骤**：打开首页，读背景、文字、强调色的计算样式。
- **期望**：与浅色一致（本版有意不分深色）。
- **判定**：通过 / 失败（记录不同的颜色）。

### T8 · 控制台回归

- **前置**：`skillmaster-console` 依赖已装。
- **步骤**：`npm run type-check && npm run test && npm run build`。
- **期望**：全绿；测试数 **不少于 538**。
- **判定**：通过 / 失败（附输出）。

### T9 · 站点构建

- **前置**：`skillmaster-site` 已建。
- **步骤**：`npm run type-check && npm run test && npm run build`；检查 `dist`。
- **期望**：全绿；产物只有 HTML/CSS/JS/图片；无服务端代码、无会话代码；**`VITE_CONSOLE_URL` 为空时构建失败**（不留一个指向 localhost 的产物）。
- **判定**：通过 / 失败。

### T10 · 设计交付件

- **前置**：设计源码已改。
- **步骤**：`build-demo.mjs` 重建首页与控制台两个交付件；`diff` 与产物；跑 `home-check`、`interaction-check` 与 `console-states`；重出 `design/shots/`。
- **期望**：交付件与源逐字节一致；三套检查全绿；首页三部分（导航、Hero（含 prompt 与三张卡片）、页脚）且 prompt 复制可用；首屏满高（**在设计件上量时扣掉底部演示条的 44px**）、页脚入场、导航固定；首页不再有浮层 / 授权入口；**两种导航态都被钉住**：未登录态在 §1 / §2，已登录态在 §8（由地址 `?signed-in` 直接打开，演示条是给人用的入口）；授权屏在控制台演示里可见；图已更新（含 `00-home-signed-in.png`）。
- **判定**：通过 / 失败（附命令输出与图）。

### T11 · CLI 对应

- **前置**：PRD 的「控制台操作与 CLI 对应」表。
- **步骤**：逐行核对；确认本版新增的只有一处（`/logout`），其余是链接。
- **期望**：表里对 `/logout` 写明「刻意不做 CLI 对应」及其理由（CLI 的 `logout` 是另一件事），且该豁免由产品负责人确认；首页的登录 / 开始使用 / 进入控制台都是链接，不是控制台操作。
- **注意**：ADR 0081 要求「每处控制台操作都要有 CLI 命令」，例外只有高风险与产品负责人逐条确认的两类。`/logout` 属于第二类，**2026-10-24 已由产品负责人逐条确认不做**（写进 prd 不做 #12）；它不是高风险——退出登录可恢复，影响面只在本机浏览器。
- **判定**：通过 / 失败。

### T12 · prompt 复制

- **前置**：首页可打开；测试里 stub `navigator.clipboard.writeText`。
- **步骤**：读框外安装提示与「安装指令」标签；读 `#agentPrompt` 的文本；点 `#copyPromptBtn`；读 stub 收到的内容与按钮文案；等反馈复位。
- **期望**：提示是「复制下面这句安装指令，发给你常用的 agent，开启你的 skill 之旅。」；标签是「安装指令」；`#agentPrompt` 是「请帮我下载并安装 SkillMaster CLI。」；复制内容与它逐字一致；按钮显示「已复制」，随后回到「复制」。
- **失败分支**：让 `navigator.clipboard.writeText` 抛错、且 `document.execCommand('copy')` 返回 `false`（两条复制路径都失败），按钮应显示「复制失败」，2 秒后回到「复制」；页面其余部分不报错。
- **判定**：通过 / 失败（成功与失败两条都要有证据）。

> 「复制失败」的红边依赖 `--danger-line`。本轮审计发现首页 `:root` 从没定义过它，是靠在计算值里回落成 `currentColor` 才看起来是红的；现在已补上 `--danger-line:#f0b3ac`（与控制台同一个值），这一条才有东西可钉。

### T13 · 首屏、两屏切换与固定导航

- **前置**：首页可打开；`home-check` 可运行。
- **步骤**：1440 下打开 `/`；量 `.hero` 高度、`.cards-grid` 的上下沿与 `#foot` 的 top；截图 `00-home.png`；记录 `#nav` 的 rect、`position`、背景与圆角；触发一次滚轮（或 ArrowDown），等页脚 top 为 0；对比 `#nav` 的同一组值；等 `#foot` 的 `is-visible` 与 `opacity`；量 Hero top；截图 `00-home-scrolled.png`；按 Home 回到首屏。
- **期望**：Hero 高度 ≥ 视口；页脚 top ≥ 视口；三张卡片整块位于首屏内；切到第二屏后 `.deck` 有 `is-second`、页脚 top 0、Hero top = -视口（没有中间位置）；`#nav` 的 rect、`position`、背景与圆角与切换前一致；页脚有 `is-visible` 且 opacity 1；两张图分别是「首屏（Hero + 卡片）」与「页脚整屏」。
- **口径**：以上「视口」指**产品构建**的视口。在**设计演示件**上量时，底部演示条占 44px（`--demo-bar`），所以 Hero 是「视口 − 44」、页脚 top 是「视口 − 44」、切到第二屏后 Hero top 是「−(视口 − 44)」；`home-check` 就是这么量的。产品没有演示条（`--demo-bar: 0`），数值回到不带减号的版本。**两者不要混用。**
- **判定**：通过 / 失败。

### T14 · 已登录态

- **前置**：`skillmaster-site` 已构建；一个带 console 会话的浏览器上下文；再准备一份让 `/web/session` 失败（500 / 断网 / 超时）的上下文。
- **步骤**：已登录上下文打开 `/`；读导航右侧；点账号按钮，按 Esc（菜单关闭、焦点回按钮）；再点开菜单，点「退出登录」；再在失败上下文打开 `/`。
- **期望**：导航右侧是 `进入控制台` + 账号（头像由用户名首字母生成、用户名），没有 `登录` / `开始使用`；点账号打开菜单，菜单里是「退出登录」；Esc 关闭菜单并把焦点还给账号按钮；「退出登录」是一条指向 `${CONSOLE_URL}/logout` 的**普通链接**（不是 POST、不带 CSRF），浏览器跳到控制台，**跳转途中不出现任何控制台内容（不闪「加载中…」、不画品牌行）**，会话结束，再回首页是未登录态（演示件里是去掉 `?signed-in`）；**失败上下文里页面完整可用**，导航是 `登录` / `开始使用`，首屏文案、prompt、三张卡片、页脚都在。
- **判定**：通过 / 失败（两种上下文各一份证据）。

### T15 · 服务端两处配置与一条不变项

- **前置**：服务端在跑；控制台已登录；能读响应头。
- **步骤**：读登录后返回的 `Set-Cookie`；从 `www` 源（或同源代理）请求 `GET /web/session` 与一个不存在的路径；读 `XSRF-TOKEN` 的作用域。
- **期望**：`SKILLMASTER_SESSION` 带 `Domain=skillmasterai.com`（**不带前导点**——Tomcat 会拒绝带点的写法）、`HttpOnly`、`SameSite=Lax`；`GET /web/session` 的响应带 `Access-Control-Allow-Origin: https://www.skillmasterai.com` 与 `Access-Control-Allow-Credentials: true`；**其他路径不返回 CORS 头**；`XSRF-TOKEN` **不带 `Domain`**（仍是主机级）。
- **判定**：通过 / 失败（三条各一份响应头证据）。

### T16 · 站点的 CI

- **前置**：`.github/workflows/site.yml` 存在且与 `console.yml` 同形状。
- **步骤**：读 workflow 的触发条件与步骤；在本机依次跑 `npm ci`、`npm run type-check`、`npm test`、`npm run build`；推送后到 Actions 看这次运行。
- **期望**：只在 `skillmaster-site/**` 或该 workflow 改动时触发；四步的顺序与 console 相同；本机四步全绿；GitHub 上的运行通过。
- **判定**：通过 / 失败。**GitHub 上的实跑只有推送后才有证据**，在那之前只能写「部分执行」。

## 环境与数据

- **环境怎么造**：从干净仓库开始；`skillmaster-site` 用 Vite 本地开发或 `preview`；控制台用现有 `skillmaster-console` 的 dev server（仅在回归时）；设计检查用 `design/scripts/` 下的脚本。
- **数据**：首页没有数据；设计稿的演示数据仍来自 `design/src/console/fixtures/`，本版不改夹具内容。
- **清理**：构建产物 `dist/` 已被忽略；设计重出的图在 `design/shots/` 内，按设计流程处理。

## 结果

**2026-10-24 实现后填写。证据都是本机实跑的命令输出；没有跑过的写「未执行」或「部分执行」，不写「应该没问题」。**

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 部分执行 | `skillmaster-site` 的测试断言了主标题、prompt、三张卡片、三列页脚与已登录/未登录两态（**20 条全过**）；页标题写在 `index.html`，测试不读它；浏览器里的整页渲染没单独跑 |
| T2 | 部分执行 | 站点测试覆盖「未登录 → 已登录」的导航切换，并断言首屏其余内容（`.hero` 文本）切换前后完全相同；真实控制台会话下的浏览器对照未跑 |
| T3 | 通过 | `skillmaster-site/tests/session.test.tsx`：一次 `GET /web/session`、`credentials: include`、无写请求、无 `/api/v1` |
| T4 | 部分执行 | 开发值有断言：`#loginBtn` / `#startBtnNav` / `#toConsoleBtn` / `#signOutBtn`，以及页脚 `#footConsole` / `#footStart` / `#footLogin`；**生产值**由两个 `.env.production` 提供，没有测试去读构建产物里的字符串 |
| T5 | 未执行 | 视觉对照由人做；本轮只在设计件上跑了两套检查，产品没有起 dev server 逐档看 |
| T6 | 通过 | `home-page.test.tsx`：8 条 `href="#"`，点「更新日志」地址不变 |
| T7 | 未执行 | 站点没有单独跑深色系统下的计算样式 |
| T8 | 通过 | `skillmaster-console`：`tsc --noEmit` 干净；`npm test` **544 通过**（≥538）；`npm run build` 成功（只有 chunk 体积警告） |
| T9 | 通过 | `skillmaster-site`：`tsc --noEmit` 干净；`npm test` **20 通过**；`npm run build` 产出 `index.html` + 一份 CSS + 一份 JS；`VITE_CONSOLE_URL= npm run build` 按预期失败（构建期闸），控制台同理（`VITE_SITE_URL`） |
| T10 | 部分执行 | `home-check` 全绿（新增已登录态与页脚断言）、`interaction-check` 全绿、两个演示件与源逐字节一致、图重出；`console-states` **整套没跑**（自带审批闸，且本版没改控制台的设计面），按组跑了 `--only auth`：0 failed / 0 problems。视觉「像不像」由人看（用户 2026-10-24 已确认设计稿） |
| T11 | 通过 | PRD 的映射表：唯一新增操作是 `/logout`，标为 ADR 0081 的豁免，2026-10-24 由产品负责人确认 |
| T12 | 通过 | `home-page.test.tsx`：复制成功、回落路径、两条都失败时「复制失败」、2 秒后回到「复制」——四段都有断言 |
| T13 | 部分执行 | 设计件上的 `home-check` 覆盖首屏、两屏切换与固定导航；产品站点的滚轮/键盘切换没有单独的浏览器用例 |
| T14 | 部分执行 | 站点测试覆盖已登录导航、菜单 Esc 关闭并把焦点还给按钮、会话失败时的完整页面；真实会话与「退出登录」跳转没有在浏览器里走一遍 |
| T15 | 部分执行 | `SiteSessionIT`（3 条）：会话 cookie 带 `Domain=skillmasterai.com` 与 `HttpOnly`、`XSRF-TOKEN` 不带 Domain；`/web/session` 的预检放行 www 源，`/web/keys` 不放行；带 Origin 的读返回账号与 ACAO。`SiteConfigTest`（6 条）钉住规范化与启动即失败：结尾斜杠/空白、`*` 与逗号列表、cookie 域的前导点与非法形状。**`SameSite=Lax` 没有断言**（由 `application.yml` 的 `same-site: lax` 提供；现在是 Boot 的序列化器从这些属性读，`SiteConfig` 只加 `Domain`）。**第三条用例不能当浏览器 cookie 路由的证据**：JDK 的 `CookieManager` 会把带 `Domain=skillmasterai.com` 的 cookie 也交给 `http://localhost`（已用一段独立探针确认），所以它证明的是响应头与 CORS，不是浏览器真的把这个 cookie 带到了 www |
| T16 | 部分执行 | `.github/workflows/site.yml` 与 `console.yml` 同形状（paths 限定 `skillmaster-site/**` 与本文件；Node 版本读 `.nvmrc`：存在，内容 `24`；install → type-check → test → build）。`type-check` / `test` / `build` 三步见 T8 / T9；**`npm ci` 这一步没有单独记录**；GitHub 上还没实跑——分支尚未推送 |

## 已知问题

本版已实现，以下三条是范围内**未修**的：

1. **`/logout` 的失败态没有设计稿。** 这一页只在请求失败时画一行字与一条「回到首页」的链接（`LogoutPage.tsx`），设计稿从没画过它。要不要补一屏，待设计侧确认（TD §实现落点 第 3 条）。
2. **375px 宽的手机上首屏比屏幕高。** 设计稿的断点只做到 720px；第三张卡片在矮屏上会被裁掉。既有边界，本版没修。
3. **生产域名未核实。** TD Q1 仍开着；`skillmaster.site.origin` 与 `cookie-domain` 要按真实域名配置，配错时首页会**安静地**永远显示未登录（ADR 0086 §后果）。

