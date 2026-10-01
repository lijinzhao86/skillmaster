# ADR 0015 · 浏览器端用 Vue 3 + Vite，且刻意不引路由 / 状态库 / UI 库 / i18n

- **状态**：已采纳
- **日期**：2026-10-01
- **依赖**：[ADR 0007](0007-self-built-oauth-as.md)、[ADR 0011](0011-server-and-cli-stack.md)、[ADR 0013](0013-phone-login-and-username-slug.md)、[ADR 0014](0014-browser-session-via-spring-session.md)
- **正文中被取代的一处**：下面那段 nginx 里的 `X-Forwarded-For $proxy_add_x_forwarded_for` 是**错的**（追加会让调用方自选限流桶），已由 [ADR 0018](0018-caller-address-behind-the-proxy.md) 取代为覆写写法；段末那条「cookie 的 `Secure` 未核实」也随之部分关闭。除这两处外正文不变。

## 背景

M01 的三个流程（注册、登录、找回密码）服务端已经做完，但只有 JSON。ADR 0007 当初就把「登录页」列成自建 AS 必须自己实现的东西：M2 的 `/oauth/authorize` 在用户未登录时，需要一个**能填的页面**可去，否则 CLI 的 loopback PKCE 流程无处落地。

于是要新增一个前端子项目。这属于**跨版本决定**（与 ADR 0011 同类）：它是与版本无关的交付物，且选型一旦定下，后面每一版都在这上面加页面。要定的是三件事——技术栈、**引入哪些依赖**、以及生产环境前端与服务端怎么拼在一起。

## 决定

**栈：Vue 3 + Vite + TypeScript**，独立子项目 `skillmaster-web/`，产物是纯静态文件。依赖只有 `vue` 一个运行时依赖；其余全是构建与测试工具。**版本逐个钉死**（不是 `^`），并在 CI 上用 `npm ci`。

**刻意不引入**：路由（vue-router）、状态库（pinia）、UI 组件库、CSS 框架、`axios`、i18n 框架、ESLint/Prettier。`App.vue` 直接读 `window.location.pathname` 选页面，链接是真正的 `<a href>`，跳转是整页加载。

**前端与服务端同源**，生产由反向代理拼合；`request()` 固定用 `credentials: 'same-origin'`，不用 `'include'`。

## 理由

**选 Vue 而不是别的：它是唯一一个「本仓库已经需要为它写文档」的选项。** 三个表单不值得为框架写一篇选型论文，而 Vue 的 SFC 让「一个组件的模板、逻辑、样式」在一个文件里，正好匹配 §2.3「一个事实只有一个权威位置」——不用在 `.ts` 与 `.html` 两个文件之间来回找同一个字段的绑定。React 需要对 JSX 与 hooks 的额外约定，Svelte 的编译期语义对读代码的人多一层。这是**在三个都很好的选项里选最不费解释的一个**，不是技术优越性判断。

**不引路由，是因为三个页面之间没有共享状态、也没有守卫。** 用 vue-router 要付的租金是：一个额外的依赖、一套路由配置、以及**登录成功后必须手工让会话与 CSRF cookie 重新一致**——服务端在登录那一刻换了 session id 与 CSRF token，整页重载天然拿到一致的状态，用客户端路由反而要额外写代码去追平。深链（`/login?return_to=…`）用 `<a href>` 一样能用，因为路径就是真路径。**M2 加授权同意页时重新评估**：一旦页与页之间要传状态、或需要路由守卫，这条决定就该被推翻。

**不引状态库，是因为唯一的跨页状态是「我是谁」，而它每次加载重取一次就够。** `GET /web/session` 本来就是 CSRF 引导必须发的那个请求（见下），顺带把身份拿到。引 pinia 意味着再加一层「谁是权威」的问题，而这块状态**没有任何客户端权威可言**——它的权威在服务端的会话表里。

**不引 UI 组件库与 CSS 框架，是因为一共八个控件。** 组件库的代价不是体积，是**主题**：它带来一整套设计语言，而本仓库目前没有设计语言要遵守。约一百行自定义属性的 CSS 反而更小、更好读。

**不引 axios，是因为要的那部分 `fetch` 只有约 80 行，而其中一半是 `axios` 不提供的。** 真正的工作量在解码错误信封、按需读 cookie、`Retry-After` 兜底上——这些不管用哪个 HTTP 客户端都得自己写。引入 axios 只会多一套要理解的错误模型。

**不引 i18n 框架，是因为没有第二种语言。** 文案中文硬编码。为一个不存在的需求引框架，是替未来的自己付租金。

**不引 ESLint/Prettier，是因为 `vue-tsc` 加 vitest 已经盖住这三张表单的真实风险。** 类型错误与协议错误是这里会真出的两类问题，格式化工具对两者都不发表意见。这一条**最可能在将来被推翻**（代码长起来之后一致性会开始值钱），推翻它的成本也很低。

**用 `same-origin` 而不是 `include`：让「前端被挪走」这件事响亮地失败。** 两者在当下都能工作（生产同源、开发期经 Vite 代理同源）。差别在将来某天有人把前端放到 CDN 上：`same-origin` 会立刻报错，`include` 会继续「工作」，只是依赖一堆并不存在、也没人写的 CORS 头——而它一旦真的开始工作，靠的是 `Access-Control-Allow-Origin: *` 加凭据这种更糟的配置。**宁可让它坏，也不要让它悄悄地坏成更危险的样子。**

**生产和开发用同一种拼接方式（同源），不是巧合而是条件。** 会话 cookie 是 `SameSite=Lax`，跨站 POST 根本不带；CSRF token 是双提交 cookie，也要靠同源才读得到。开发期浏览器看到的是 Vite 的源，代理把 `/web` 与 `/api/v1` 转给 8080，两边同源；生产期反代做同一件事。**开发期不需要 `cookieDomainRewrite`**，因为两个 cookie 都不带 `Domain`。

## 后果

- **前端路径是 SPA 的，服务端不会有 `/web/login`。** 页面地址是 `/login` / `/register` / `/reset`，`/web/*` 永远是 API。**文档里原先写的 `GET /web/login` 是错的**（服务端从来没有这条路由），本轮改掉。反代必须**不要**让 `/web/**` 吞掉这三个路径——它们不冲突，但这一点必须写在部署说明里，否则将来有人想当然地在反代里给 `/web` 加一条 rewrite 就会踩到。
- **`dist/` 是构建产物，不提交。** 仓库根的 `.gitignore` 已经忽略它，`vite.config.ts` 里把 `outDir` 写成默认值就是因为有东西依赖这个名字。
- **TypeScript 停在 5.9.3**（最新是 7.x 的 Go 移植版）。`vue-tsc` 通过改 TypeScript 内部实现工作，它声明的 `typescript: >=5.0.0` 是没对着 7 测过的宽松范围。升级是一行，`npm run type-check` 立刻能验。
- **线路格式没有自动化验证。** 前端测试全是 stub `fetch` 的单测，把 `captcha_id` 打成 `captchaId`，测试会跟着一起错。已用 `curl` 经 Vite 代理对着真服务端把三个流程与全部失败形态走了一遍（证据在 `test-plan.md` §结果），**线路那一半因此有了证据；没有的是浏览器那一层，以及「回归时不用人再走一遍」这件事**。要补自动化就得在 CI 里起真服务端加真库，那是另一个决定。
- **生产部署缺一段没验证过的 nginx 配置**（下），和 `AliyunSmsSender` 一样如实标注：没有地方可部署，所以从没跑过。

```nginx
server {
    listen 443 ssl;
    server_name skillmaster.example.com;

    # The API plane and the browser plane both belong to the server. Every path the
    # client calls has a segment after /web/, so this prefix covers all of them.
    location /web/    { proxy_pass http://127.0.0.1:8080; }
    location /api/v1/ { proxy_pass http://127.0.0.1:8080; }

    # Everything else is the SPA. The fallback is what makes a refresh on /register work:
    # without it nginx looks for a file named "register" and answers 404.
    location / {
        root /srv/skillmaster-web;
        try_files $uri /index.html;
    }

    proxy_set_header Host              $host;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

- **上面这段里有一处依赖服务端，本轮没有动它。** 两个 cookie 的 `Secure` 属性由 Spring 依据 `request.isSecure()` 决定，而它要认 `X-Forwarded-Proto`，得有 `server.forward-headers-strategy`。本轮不改服务端，所以**生产上 cookie 会不会带上 `Secure`，目前是未核实**——这是一条待办，不是已完成的加固。
