# skillmaster-site

`www.skillmasterai.com` 上的营销首页。静态构建，不需要服务端。

```bash
npm ci
npm run dev        # http://localhost:5174
npm run type-check
npm test
npm run build      # dist/：一份 HTML + 一份 CSS + 一份 JS
```

## 它做什么

一屏品牌主句、一句可复制给 agent 的安装指令、三张解释「随时在场」的卡片；下滑一屏是产品 / 文档 / 关于页脚。界面以 `design/skillmaster-首页演示.html` 为权威，`src/App.tsx` 是从 `design/src/home/HomeApp.tsx` 搬来的，只改了三处：状态来自会话、没有演示条、链接来自构建变量。

## 它读什么

**一次，只读**（[ADR 0086](../docs/decisions/0086-the-homepage-reads-the-session.md)）：启动时 `GET /web/session`。2xx 画已登录导航（`进入控制台` + 账号），401、网络失败或 3 秒超时一律画未登录导航。没有写、没有表单、不存任何东西。

- 生产：请求 `VITE_CONSOLE_URL` 的源，服务端对 www 放行一条只读 CORS，会话 cookie 带 `Domain`。
- 开发：`vite.config.ts` 把 `^/web/` 代理到 `localhost:8080`，请求因此同源，不需要 CORS。
- `VITE_CONSOLE_URL`（开发默认 `http://localhost:5173`）：**`npm run build` 少了它直接失败**。生产值在 `.env.production` 里，随仓库提交。

## 测试

`tests/` 覆盖会话读的五种结局（账号、401、断网、响应不是账号、超时）、两种导航态、复制的三条路径与占位链接不导航。真实的跨源链路在服务端的 `SiteSessionIT` 里跑。
