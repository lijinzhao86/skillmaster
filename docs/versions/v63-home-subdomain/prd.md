# v63-home-subdomain · 产品需求

> **最后更新**：2026-11-13
> **所属版本**：[`README.md`](README.md)

## 用户与场景

**谁在用**：内测期的访客与已登录用户。营销首页的主入口改为 `home.skillmasterai.com`；`www.skillmasterai.com` 作为一个独立页面保留，内容不改、照常可以访问，但任何页面都不链到它。

**在什么场景下用**：产品要申请经营性 ICP 许可证，`www` 需要被独立出来供办证使用；访客仍需要一个能打开、能看介绍、能跳到控制台的主入口。主入口落在同一注册域内，才能继续共享会话 cookie（`Domain=skillmasterai.com`）；`www` 要保留登录态，因此服务端必须同时放行 `www` 与 `home` 两个 CORS 来源。

**现在的替代做法是什么**：只放行一个 CORS 来源——`home` 需要它，`www` 就会永远显示未登录；`www` 需要它，`home` 就会永远显示未登录。另一个替代是启用 `skillmasterai.cn`：独立顶级域，要重新走新增网站备案（所有者的记录口径：阿里云初审 1–2 个工作日 + 广东管局 8–15 个工作日，以实际受理为准），审核期间该域名还不能对外服务，而且 `.cn` 与 `.com` 之间无法共享登录态。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | 新增 `home.skillmasterai.com`，用现有证书体系提供与 `www` 相同的首页静态文件 | DNS A 记录 + 证书 SAN + nginx server 块 |
| 2 | 把主入口指向 `home`：控制台的品牌/退出/法务链接、裸域 `skillmasterai.com` 的 301 | 构建变量 + nginx |
| 3 | 让 `www` 与 `home` 同时保留登录态：服务端 CORS 来源从「一个」改为「显式列表」 | `SiteConfig.java` + 仓库变量 + 服务端部署 |
| 4 | 让 `www` 成为独立页面：内容不改、可访问，但任何页面都不链到它 | 控制台构建产物检查 + 文档 |
| 5 | 让每日巡检与站点发布探针继续有效：域名解析、证书 SAN、按内容校验的发布探针 | `ops-check.yml`、`site.yml` |
| 6 | 更新《隐私政策》：适用范围去掉子域枚举，版本升到 `1.2`，页面常量、服务端配置与 `@DefaultValue` 同版；设计件按源重出 | `design/src/home/legalText.ts`、`skillmaster-site/src/legal/legalText.ts`、`application.yml`、`SkillmasterProperties.java` |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 不用 `skillmasterai.cn` | 要新增网站备案，审核期不能对外服务；跨顶级域也共享不了登录态 | 未定；若将来要独立入口再议 |
| 2 | 不改 `www` 的页面内容 | 所有者要求它先保持原样；它现在只作为独立页面保留 | 许可证办理方提出要求时 |
| 3 | 不新增 `dl.` / `console.` 的域名与 server 块 | 本版只处理营销首页；主证书会重签以加入 `home` 的 SAN，`console` 仍在覆盖范围内，重签后要复验 | 不做 |
| 4 | 不做 canonical、sitemap 等 SEO 搬家 | 两个宿主是过渡状态，且 `www` 不再被链接；期间没有 SEO 动作 | 不做 |

**范围里不许出现「后续再看」「视情况而定」。** 与 `www`/`home` 长期关系有关的未定项在 [`technical-design.md`](technical-design.md) §开放问题，是一个能回答的问法。

## 控制台操作与 CLI 对应

本版不新增或改动任何控制台操作。控制台只换一个构建期常量（`VITE_SITE_URL`），没有新的用户动作，因此不产生 CLI 命令需求。

## 验收与指标

### 验收标准

| # | 期 | 验收标准 | 怎么判定 |
|---|---|---|---|
| A1 | P0 | `https://home.skillmasterai.com/` 通过真实证书返回首页，且内容与同一份构建一致 | 在主机上用 `--resolve` 取 `/index.html`，与 `/srv/skillmaster/site/index.html` 逐字节比；只回 200 不算 |
| A2 | P0 | 证书覆盖 `home` 且续期链路仍然可用 | `openssl` 看到 SAN 含 `home`；`certbot renew --dry-run` 通过 |
| A3 | P1 | 控制台的品牌链接、退出后「回到首页」、注册页《服务条款》《隐私政策》，以及裸域 `skillmasterai.com` 的 301 都指向 `home` | 用构建后的 `dist` 检查控制台三处地址；`curl -sSI https://skillmasterai.com/` 看 `Location` |
| A4 | P1 | `home` 与 `www` 上都能看到已登录态：`GET /web/session` 对两个来源都放行，且会话 cookie 随请求到达 | 带真实会话分别打开两个宿主，导航都显示账号 |
| A5 | P1 | `www` 保持可访问、内容与改动前一致，且**任何页面都不链到它** | `curl` 取 `www` 的 `/index.html` 与磁盘构建逐字节一致；控制台与首页的构建产物里 `grep -rc "www.skillmasterai.com"` 的计数都为 0 |
| A6 | P0 / P1 | 每日巡检与站点发布探针在各期都能通过：五个名字解析、主证书 SAN、配置哈希；探针始终问 `home` 并跟随跳转 | 每期手动跑一次 `ops-check` 与一次站点发布 |
| A7 | P0 | 《隐私政策》适用范围不再依赖子域枚举，页面与服务端记录的版本同为 `1.2` | 两处 `legalText.ts` 的 `version` 与 `effective`、`application.yml` 与 `SkillmasterProperties` 的默认值一致；站点测试断言页面显示 `1.2`；设计件按源重出且 `home-check` 全绿；注册记录取服务端配置 |

**成立条件**：P0 的 A1、A2、A6 的 P0 一次与 A7 不满足就不算交付；A3、A4、A5 在 P1 完成时验收。

### 指标

本版不设产品指标；它是一次接入地址的迁移、一次 CORS 契约的小改动，加一次法务文本澄清。

## 依赖与前置

- **外部依赖**：阿里云 DNS 对 `skillmasterai.com` 的管理权限（本机 OAuth profile `domain`，过期需重新登录）；Let's Encrypt 的 DNS-01 续期链路（现有 `deploy/acme/`）。
- **内部前提**：`www` 继续读取会话的模型不变（[ADR 0086](../../decisions/0086-the-homepage-reads-the-session.md)）；CORS 来源由 [ADR 0190](../../decisions/0190-the-site-origin-is-an-explicit-list.md) 改为显式列表；同域子域见 [ADR 0187](../../decisions/0187-the-marketing-home-can-run-on-a-subdomain.md)。
- **P1 的执行顺序**：先部署带列表支持的服务端（变量仍是单值）；再把 `SKILLMASTER_SITE_ORIGIN` 改成两个值并**再部署一次服务端**——变量只在部署时渲染进 `server.env`，不重新部署就不会生效。变量变成列表后，回滚到 P1 之前的服务端 release 要先把它改回单值，理由见 [`deploy/README.md`](../../../deploy/README.md) §首页入口。
- **排除了什么前置**：不需要新增备案——`home` 是 `skillmasterai.com` 的子域，现有备案覆盖它。
