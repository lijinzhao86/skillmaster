# 0004 · 浏览器端：注册 / 登录 / 找回密码的页面

> **日期**：2026-10-01
> **所属版本**：[`../README.md`](../README.md)
> **类型**：小版本迭代——**未改变对外可验收的能力边界**

## 改了什么

### 1. 新子项目 `skillmaster-web/`

Vue 3 + Vite + TypeScript，独立构建，产物是静态文件（[ADR 0015](../../../decisions/0015-web-frontend-stack.md)）。接入的是**已经存在**的八个 `/web/*` 端点，一个都没新增：

| 页面 | 走的路 |
|---|---|
| `/login` | `POST /web/login` → 整页跳 `/`（或 `?return_to=` 指的同站路径） |
| `/register` | `GET /web/captcha` → `POST /web/register/code` → `POST /web/register` |
| `/reset` | `GET /web/captcha` → `POST /web/reset/code` → `POST /web/reset` |
| `/`（已登录） | `GET /web/session`，另加一个 `POST /web/logout` |

`src/api/client.ts` 把所有请求收在一个函数里，它负责四件会互相伪装成别的问题的事：`credentials: 'same-origin'`（不是 `'include'`）、**发送那一刻**才从 cookie 读 CSRF token（不做模块级缓存——服务端登录时会轮换它）、每个 POST 都带 `X-XSRF-TOKEN`、以及把非 2xx **解码而不是抛出**（连反代返回的 HTML 502 也合成一个可显示的 `internal_error`）。

### 2. 一处必须先做的文档纠正

TD §4.4 把登录页写成 `GET /web/login`——**服务端从来没有这条路由**（`WebAccountController` 只映射 `/web/{session,captcha}` 与五个 POST）。页面归前端，地址是 SPA 自己的 `/login`。这不只是笔误：按原文部署，反代让 `/web/**` 整个转给服务端，那个「登录页」永远不会出现。

### 3. 测试里发现并修掉的一个真缺陷

`RegisterPage` 的手机号输入框只渲染第一步（发码）的错误，于是 `POST /web/register` 对 `phone` 的拒绝——**正是 `already_registered` 这一条**——被静默丢掉，页面上什么都不显示。测试先失败后暴露的，已修。

## 为什么

[ADR 0007](../../../decisions/0007-self-built-oauth-as.md) 当初就把「登录页」列成自建 AS 必须自己实现的东西：M2 的 `/oauth/authorize` 在用户未登录时得有一个**能填的页面**可去，那是 CLI loopback PKCE 流程的一环。M01 把服务端那半边做完了，浏览器打开却只能看到一段 JSON。

### 为什么是迭代而不是新开大版本

判据是「是否改变对外可验收的能力边界」。这一轮**没有新增任何端点、没有改任何契约、没有动 `prd.md` 的验收标准**：它是一个已有能力的客户端。CLI 将来接 `/oauth/authorize` 时才是新能力。

### 几个刻意不做的（理由在 ADR 0015）

不引路由、状态库、UI 库、CSS 框架、`axios`、i18n、ESLint。每条都是取舍不是遗漏——尤其**不引路由**：登录成功后整页重载恰好是最干净的收尾，服务端刚换了 session id 与 CSRF token，重载天然拿到一致状态，用客户端路由反而要额外写代码去追平。M2 加授权同意页时重新评估。

## 影响

**已交付行为**：服务端一行未改；`/api/v1/**` 与 `/web/**` 的形状全部未变。新增的是一份静态产物和它自己的 CI（`.github/workflows/web.yml`，不需要数据库与服务端，可在服务端 CI 停着的时候独立跑）。

**测试覆盖到哪里，要看清**：前端测试全是 stub `fetch` 的单测（48 条），它们钉住的是**前端自己的**协议理解——把 `captcha_id` 打成 `captchaId`，测试会跟着一起错。所以另做了一次**经 Vite 代理打真服务端**的走查：`curl` 走完注册 → 登出 → 登录 → 重置换码，把字段名、状态码、错误码、`Retry-After`、以及四条镜像规则的 issue 码逐个对了一遍，**全对**（证据表见 [`test-plan.md`](../test-plan.md) §结果第三轮）。**没有浏览器**，所以剩的是浏览器那一层；且这一遍**没有自动化**，回归一次要人再走一遍——要补自动化得在 CI 里起真服务端加真库，那是另一个决定。

**同样未验证的还有生产部署**：反代的 nginx 片段写在 ADR 0015 里并标注未跑过（没有地方可部署）。其中有一处**依赖服务端**：两个 cookie 的 `Secure` 由 Spring 依 `request.isSecure()` 决定，要认 `X-Forwarded-Proto` 得有 `server.forward-headers-strategy`——本轮不改服务端，所以生产上 cookie 会不会带 `Secure` 目前未核实。这是一条待办。

**文档同步**：

| 文档 | 改了什么 |
|---|---|
| 新增 [`decisions/0015-web-frontend-stack.md`](../../../decisions/0015-web-frontend-stack.md) | 栈与「不引什么」的理由、同源与那条 nginx 片段（标注未验证） |
| [`decisions/README.md`](../../../decisions/README.md) | 索引加 0015 |
| [`technical-design.md`](../technical-design.md) §4.4 | `GET /web/login` → `/login`，由 web 子项目提供 |
| 同 §2.4 | 代码仓库布局加 `skillmaster-web/` |
| [`test-plan.md`](../test-plan.md) | §结果 记手工走查；§已知问题 记「线路格式没有自动化验证」 |
| 根 `README.md` | `## Layout` 加一条 |
| 新增 `skillmaster-web/README.md` | 状态、工具链、怎么对着本地服务端跑 |
