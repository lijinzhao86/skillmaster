# v63-home-subdomain · 技术设计

> **最后更新**：2026-11-13
> **状态**：设计已定稿
> **所属版本**：[`README.md`](README.md)
> **架构决策**：[ADR 0187](../../decisions/0187-the-marketing-home-can-run-on-a-subdomain.md) 记「为什么用同域子域」；[ADR 0190](../../decisions/0190-the-site-origin-is-an-explicit-list.md) 记「为什么主入口是 `home`、`www` 独立保留、CORS 来源改成显式列表」。本文只链接，不复述理由。

## 架构

**同一份构建产物，两个前门，一个主入口。** 首页的静态文件只有一个来源 `/srv/skillmaster/site`（`site.yml` 发布到那里），`www` 与 `home` 都指向它。没有新的构建、新的产物或新的运行时。

> **2026-11-13 补记**：交付后 `www` 改为从独立副本 `/srv/skillmaster/site-www` 提供（办证期间冻结，改它不影响 `home`，站点发布也不覆盖它）；`home` 仍用 `/srv/skillmaster/site`。回退方式见 [`deploy/README.md`](../../../deploy/README.md) §首页入口。本节其余内容与两张表描述的是拆分前的形状。

```
                     nginx (host, :80 -> :443)
  console.skillmasterai.com ─┬─ /srv/skillmaster/console
  www.skillmasterai.com     ─┼─ /srv/skillmaster/site      ← 独立页面，内容不改、不被链接
  home.skillmasterai.com    ─┘─ /srv/skillmaster/site      ← 主入口
  skillmasterai.com         ─── 301 → home
  dl.skillmasterai.com      ─── proxy ──► OSS 桶（不变）
```

**三个面要一起切**：

| 面 | 在哪 | P0 之后（现状） | P1（本次改动） |
|---|---|---|---|
| 服务端 CORS 来源 | 仓库变量 `SKILLMASTER_SITE_ORIGIN` → `skillmaster.site.origin` | `https://www.skillmasterai.com` | `https://www.skillmasterai.com,https://home.skillmasterai.com` |
| 控制台的站外地址 | `skillmaster-console/.env.production` 的 `VITE_SITE_URL`（构建期） | `https://www.skillmasterai.com` | `https://home.skillmasterai.com` |
| 首页宿主与裸域 | `deploy/nginx/skillmaster.conf` | `home` 可访问；裸域 301 到 `www` | `home` 是主入口；裸域 301 到 `home`；`www` 继续服务、内容不改 |

- **CORS 只有一条规则**：`GET /web/session`，来源是配置里的**显式列表**（`SiteConfig.corsConfigurationSource`）。P1 时列表里是 `www` 与 `home`；通配、空项与含空白的条目在启动时拒绝。
- **会话 cookie 不变**：`Domain=skillmasterai.com` 已经覆盖 `home`；`XSRF-TOKEN` 继续保持主机级（ADR 0086 的不变项）。
- **控制台必须重出**：`VITE_SITE_URL` 是构建期常量，`vite.config.ts` 在缺失时拒绝构建；改值之后要重新发布控制台，不能只改环境变量。
- **控制台读这个值的有三处**：品牌链接（`AppShell.tsx`）、退出页「回到首页」（`LogoutPage.tsx`）、注册页两处法务链接（`RegisterPage.tsx`）。`design/README.md` 里把这个值写成 `www`，并说明它是 `VITE_SITE_URL` 的原值——P1 改的是部署值，不是界面契约，所以 `VITE_SITE_URL` 这一项不改 `design/`。A7 的法务文本改动会改 `design/`，见改动面表。
- **`www` 页面不变**：它的 nginx 块、静态文件与页面内容都不动；变化只有三点——裸域不再 301 到它、控制台不再链到它、它通过 CORS 列表继续保留登录态。

**改动的面与文件**：

| 面 | 文件 | 改什么 | 期 |
|---|---|---|---|
| 主机 | DNS、证书、`/etc/nginx/conf.d/` | 加 A 记录；重签证书加 SAN；拷新配置并 reload | P0 |
| 部署配置 | `deploy/nginx/skillmaster.conf` | 端口 80 名单加 `home`；新增 `home` 的 443 块；裸域 301 指向 `home` | P0 / P1 |
| 部署文档 | `deploy/README.md` | 拓扑、证书重签命令、P0/P1 步骤、检查命令 | P0 / P1 |
| 运维 | 阿里云云监控 | 给 `home` 加一条 200 探测（权限见 `deploy/README.md` §自愈与监控） | P1 |
| 巡检 | `.github/workflows/ops-check.yml` | 域名解析清单加 `home`；主证书 SAN 断言 | P0 |
| 站点发布 | `.github/workflows/site.yml` | 部署探针改问 `home`、按内容比对、跟随跳转；部署 job 加 `concurrency` 串行化 | P0 |
| 控制台 | `skillmaster-console/.env.production` | `VITE_SITE_URL` 指向 `home` | P1 |
| 服务端 | `skillmaster-server/src/main/java/com/skillmasterai/config/SiteConfig.java`、`SiteConfigTest.java`、`SiteSessionIT.java` | 来源解析从「一个值」改成「显式列表」；通配、空项拒绝 | P1 |
| 服务端 | 仓库变量 `SKILLMASTER_SITE_ORIGIN` | 改成 `www` 与 `home` 两个值 | P1 |
| 法务 | `design/src/home/legalText.ts`、`skillmaster-site/src/legal/legalText.ts`、`application.yml`、`SkillmasterProperties.java` | 去掉适用范围的子域枚举；隐私政策升 `1.2`，四处同版；`design/` 的演示件按源重出，`home-check` 的版本断言改为形状（它原先钉死 `1.0`，已是基线红） | P0 |
| 设计说明 | `design/README.md` | 品牌链接段补「三处消费者」与「`www` 是原值、部署期可换」；界面契约不变 | P0 |
| 站点文档 | `skillmaster-site/README.md` | 提到同一构建也由 `home` 提供；CORS 一句改成指向配置键 | P0 |
| 发布文档 | `.dev-sop/release/site.md` | 产品形态改为主入口；部署串行化一句 | P0 |
| 版本文档 | `docs/versions/v63-home-subdomain/`、`docs/decisions/0190-*` | 本版四份文档与 CORS 列表决策 | P1 |

**为什么不改 `deploy/acme/auth.sh`**：它按 `CERTBOT_DOMAIN` 推导 `_acme-challenge` 记录，任何 `skillmasterai.com` 下的名字都会被它接住；`home` 不需要新钩子。

**为什么探针要按内容比对**：`site.yml` 的部署探针从主机上用 `--resolve` 问 nginx。只判状态码不够——443 的第一个 server 块是 `console`，任何匹配不上的名字（`home` 块缺失、拼错）都会拿到控制台首页的 200。所以它取 `/index.html` 并与 `/srv/skillmaster/site/index.html` 逐字节比。它跟随跳转，并把 `home` 与 `www` 都 `--resolve` 到本机；即使以后 `home` 变成 301，探针仍落在同一份构建上。

## 数据模型

本版不涉及表结构、实体或存储格式变更。法务版本号是配置（`skillmaster.account.privacy-version`），不是表。

## 接口

**公开地址（对外契约）**：

| 地址 | P1 之后 |
|---|---|
| `https://home.skillmasterai.com/…` | 200，首页静态文件；主入口 |
| `https://www.skillmasterai.com/…` | 200，同一份首页；独立页面，不被任何页面链接 |
| `https://skillmasterai.com/…` | 301 到 `https://home.skillmasterai.com$request_uri` |
| `http://<上面任一个>/…` | 301 到同名 https |

**会话读取（路径与凭据不变，来源改成列表）**：

- `GET https://console.skillmasterai.com/web/session`，只允许 `GET`、只允许配置里的显式来源、带凭据。
- P1 的来源列表是 `https://www.skillmasterai.com` 与 `https://home.skillmasterai.com`；浏览器收到的是与自己 `Origin` 相同的那个值。
- 失败语义不变：查不到就是未登录（fail-open）。

**构建期常量**：

- `skillmaster-console` 的 `VITE_SITE_URL`：控制台品牌、退出页与注册页法务链接的唯一出处；缺值拒绝构建。
- `skillmaster-site` 的 `VITE_CONSOLE_URL`：不变，仍指 `console.skillmasterai.com`。

**法务版本（v56 的契约）**：页面常量与服务端配置必须同版；本次两者都到 `1.2`，生效日期 `2026 年 11 月 13 日`。v56 已定文本更新不打断现有用户，所以不需要重新同意。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 准备（已上线） | DNS A 记录、证书加 SAN、`home` server 块、巡检名单与 SAN 断言、按内容比对的探针、隐私政策 `1.2` 与枚举清理、文档；合并后按发布 SOP 把站点与服务端各 Deploy 一次。 | A1、A2、A6（P0 一次）、A7 |
| P1 主入口（本次改动） | `SiteConfig` 支持显式来源列表；`VITE_SITE_URL` 指向 `home`；裸域 301 指向 `home`；`SKILLMASTER_SITE_ORIGIN` 改成两个值；服务端部署两次（先代码、再变量）、控制台一次；云监控补 `home`。**`www` 的页面与 nginx 块不动。** | A3、A4、A5、A6（P1 一次） |

**P1 的部署顺序**：先部署带列表支持的服务端，此时 `SKILLMASTER_SITE_ORIGIN` 仍是单值；再把变量改成两个值并**再部署一次服务端**——变量只在部署时渲染进 `server.env`。变量变成列表后，回滚到 P1 之前的服务端 release 要先把它改回单值（旧 jar 遇到逗号会拒绝启动），否则回滚会失败并自动切回当前 release。控制台与 nginx 随后切。

**每期明确不做**：P0 不切换入口；P1 不改 `www` 的页面内容、不碰 `dl.` 与 `console.` 的域名、不删除 `home` 的解析与证书 SAN。许可证下来后 `www` 与 `home` 的长期关系不在本次改动里（见 §开放问题）。

**P0 的落地顺序**（已执行）：先主机（DNS → 证书 → nginx），再合并仓库改动；合并后按发布 SOP 把站点与服务端各 Deploy 一次。主机先换配置、仓库后合并之间，巡检会因配置哈希不一致红一次，合并后恢复。

## 开放问题

**已定（2026-11-13，所有者）**：

- **`home` 是营销首页的主入口。** 控制台三处站外链接与裸域 301 都指向它。
- **`www` 作为独立页面保留。** 内容不改、可访问，但任何页面都不链到它。
- **CORS 来源是显式列表。** `www` 与 `home` 同时保留登录态；通配与空项仍然拒绝（ADR 0190）。
- **不再按原计划把 `home` 301 回 `www`。** 长期关系由许可证结果决定。
- **站点发布探针跟随跳转、不随阶段改名。** 它按字节比对，并把 `home` 与 `www` 都 `--resolve` 到本机。
- **隐私政策的子域枚举去掉、版本升 `1.2`。** 括号里的三项会被当穷举读，`home` 不在其中；去掉枚举后主句覆盖所有子域。

仍开放：

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 许可证下来后，`www` 继续作为独立页面，还是 301 到 `home`、或反过来？ | 继续独立：所有者要它保持原样，且它已不被任何页面链接。若许可证办理方要求 `www` 承载特定内容或必须退出，再按那个要求改。 | 由许可证办理方对 `www` 的处置要求决定 |

## 附录 A · 与现有代码的关系

产品代码只有一处逻辑改动：`SiteConfig` 的来源解析从单值改成显式列表（含拒绝规则），配套两个测试。其余都只改取值或探测目标：`siteUrl.ts` 读构建变量、`site.yml` 的探针、`ops-check.yml` 的清单；`deploy/nginx/skillmaster.conf` 的 `home` 块与裸域 301；两处 `legalText.ts` 改一句文本与版本号；服务端两个默认值；设计侧一条检查断言与一次产物重出。

## 附录 B · 来源与出处

- `skillmaster-console/src/siteUrl.ts`：`VITE_SITE_URL` 是控制台指向首页的唯一出处。
- `skillmaster-console/vite.config.ts`：缺值拒绝构建。
- `skillmaster-server/src/main/java/com/skillmasterai/config/SiteConfig.java`：来源列表的解析与拒绝规则。
- `deploy/acme/auth.sh`：DNS-01 钩子按注册域自行推导 `_acme-challenge` 名字。
- `deploy/README.md` §Certificates：`skillmasterai.com` 那张证书的重签命令（apex/www/console，本版加 `home`），`dl.` 是另一张。
- `.github/workflows/site.yml`：部署探针的 `--resolve`、内容比对与回滚。
- `.github/workflows/ops-check.yml`：每日巡检的域名清单、证书 SAN 与 nginx 配置哈希比对。
- [ADR 0086](../../decisions/0086-the-homepage-reads-the-session.md)：首页读取会话的契约，本版沿用。
- [ADR 0187](../../decisions/0187-the-marketing-home-can-run-on-a-subdomain.md)：用同域子域做入口（最初按临时入口设计）。
- [ADR 0190](../../decisions/0190-the-site-origin-is-an-explicit-list.md)：`home` 是主入口、`www` 独立保留、CORS 来源是显式列表。
