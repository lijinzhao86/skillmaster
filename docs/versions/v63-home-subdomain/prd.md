# v63-home-subdomain · 产品需求

> **最后更新**：2026-11-13
> **所属版本**：[`README.md`](README.md)

## 用户与场景

**谁在用**：内测期的访客与已登录用户；他们的入口是营销首页 `www.skillmasterai.com`。

**在什么场景下用**：产品要申请经营性 ICP 许可证，`www.skillmasterai.com` 的页面需要按办证要求改造，改造与审核期间它可能不可用。访客仍然需要一个能打开、能看介绍、能跳到控制台的首页；这个入口必须落在同一注册域内，才能继续共享会话 cookie（`Domain=skillmasterai.com`）。CORS 来源不能共享：服务端只放行一个来源，它要随入口切换。

**现在的替代做法是什么**：直接把 `www` 改掉，或者启用 `skillmasterai.cn`。前者让访客在审核期没有入口；后者是独立顶级域，要重新走新增网站备案（所有者的记录口径：阿里云初审 1–2 个工作日 + 广东管局 8–15 个工作日，以实际受理为准），审核期间该域名还不能对外服务，而且 `.cn` 与 `.com` 之间无法共享登录态。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | 新增 `home.skillmasterai.com`，用现有证书体系提供与 `www` 相同的首页静态文件 | DNS A 记录 + 证书 SAN + nginx server 块 |
| 2 | 窗口期把入口切到 `home`：控制台的品牌/退出/法务链接、服务端 CORS 来源、`www` 与裸域的处理 | 构建变量 + 仓库变量 + nginx |
| 3 | 许可证下来后切回：`home` 301 到 `www`，上面的三处取值一起回退 | nginx + 构建变量 + 仓库变量 |
| 4 | 让每日巡检与站点发布探针在域名切换前后继续有效：域名解析、证书 SAN、按内容校验的发布探针 | `ops-check.yml`、`site.yml` |
| 5 | 更新《隐私政策》：适用范围去掉子域枚举，版本升到 `1.2`，页面常量、服务端配置与 `@DefaultValue` 同版；设计件按源重出 | `design/src/home/legalText.ts`、`skillmaster-site/src/legal/legalText.ts`、`application.yml`、`SkillmasterProperties.java` |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 不用 `skillmasterai.cn` | 要新增网站备案，审核期不能对外服务；跨顶级域也共享不了登录态 | 未定；若将来要独立入口再议 |
| 2 | 不新增 `dl.` / `console.` 的域名与 server 块 | 本版只处理营销首页这一个入口；但主证书会重签以加入 `home` 的 SAN，`console` 仍在覆盖范围内，重签后要复验 | 不做 |
| 3 | 不做 canonical、sitemap 等 SEO 搬家 | 临时宿主，切回时的 301 会自然收口；期间没有 SEO 动作 | 不做 |

**范围里不许出现「后续再看」「视情况而定」。** 与 `www` 窗口期处理有关的未定项在 [`technical-design.md`](technical-design.md) §开放问题，是一个能回答的问法。

## 控制台操作与 CLI 对应

本版不新增或改动任何控制台操作。控制台只换一个构建期常量（`VITE_SITE_URL`），没有新的用户动作，因此不产生 CLI 命令需求。

## 验收与指标

### 验收标准

| # | 期 | 验收标准 | 怎么判定 |
|---|---|---|---|
| A1 | P0 | `https://home.skillmasterai.com/` 通过真实证书返回首页，且内容与同一份构建一致 | 在主机上用 `--resolve` 取 `/index.html`，与 `/srv/skillmaster/site/index.html` 逐字节比；只回 200 不算 |
| A2 | P0 | 证书覆盖 `home` 且续期链路仍然可用 | `openssl` 看到 SAN 含 `home`；`certbot renew --dry-run` 通过 |
| A3 | P1 | 控制台的品牌链接、退出后「回到首页」、注册页《服务条款》《隐私政策》都指向当前入口 | 用构建后的 `dist` 检查地址，或浏览器点到即止 |
| A4 | P1 | `home` 上能看到已登录态：`GET /web/session` 的 CORS 放行 `home` 来源，且会话 cookie 随请求到达 | 带真实会话打开 `home`，导航显示账号而不是「登录 / 开始使用」 |
| A5 | P2 | 切回后 `home` 301 到 `www`，登录态回到 `www`，控制台链接回到 `www`，站点发布探针改问 `www` | `curl -I` 看 301；浏览器复验登录态与链接；探针按内容比对通过 |
| A6 | P0 / P1 / P2 | 每日巡检与站点发布探针在各期都能通过：五个名字解析、主证书 SAN、配置哈希；探针 P0/P1 问 `home`、P2 起问 `www` | 每期手动跑一次 `ops-check` 与一次站点发布 |
| A7 | P0 | 《隐私政策》适用范围不再依赖子域枚举，页面与服务端记录的版本同为 `1.2` | 两处 `legalText.ts` 的 `version` 与 `effective`、`application.yml` 与 `SkillmasterProperties` 的默认值一致；站点测试断言页面显示 `1.2`；设计件按源重出且 `home-check` 全绿；注册记录取服务端配置 |

**成立条件**：本笔交付 P0，A1、A2、A6 的 P0 一次与 A7 不满足就不算交付；A3、A4 在 P1 完成时验收，A5 在 P2 完成时验收。

### 指标

本版不设产品指标；它是一次接入地址的迁移加一次法务文本澄清。

## 依赖与前置

- **外部依赖**：阿里云 DNS 对 `skillmasterai.com` 的管理权限（本机 OAuth profile `domain`，过期需重新登录）；Let's Encrypt 的 DNS-01 续期链路（现有 `deploy/acme/`）。
- **内部前提**：`www` 继续读取会话的模型不变（[ADR 0086](../../decisions/0086-the-homepage-reads-the-session.md)），本版对会话模型只换来源地址；另有 A7 的法务文本与版本变更。临时入口用同域子域这个取舍见 [ADR 0187](../../decisions/0187-the-marketing-home-can-run-on-a-subdomain.md)。
- **P1 的触发**：何时把入口切到 `home` 由所有者决定（2026-11-13：时机另定）；本版先交付 P0。
- **排除了什么前置**：不需要新增备案——`home` 是 `skillmasterai.com` 的子域，现有备案覆盖它；服务端没有 Java 逻辑改动，但 `application.yml` 的法务版本默认值会改，它要随服务端 Deploy 生效（`server.yml` 不渲染这个变量，生产值来自 jar 里的默认值）。
