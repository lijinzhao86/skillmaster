# v63-home-subdomain · 技术设计

> **最后更新**：2026-11-13
> **状态**：设计已定稿
> **所属版本**：[`README.md`](README.md)
> **架构决策**：[ADR 0187](../../decisions/0187-the-marketing-home-can-run-on-a-subdomain.md) 记「为什么用同域子域、为什么只留一个 CORS 来源」；本文只链接，不复述理由。

## 架构

**同一份构建产物，多一个前门。** 首页的静态文件只有一个来源 `/srv/skillmaster/site`（`site.yml` 发布到那里），`www` 现在指向它；本版让 `home` 也指向它。没有新的构建、新的产物或新的运行时。

```
                     nginx (host, :80 -> :443)
  console.skillmasterai.com ─┬─ /srv/skillmaster/console
  www.skillmasterai.com     ─┼─ /srv/skillmaster/site      ← 现有，窗口期按 Q1 处理
  home.skillmasterai.com    ─┘─ /srv/skillmaster/site      ← 本版新增
  skillmasterai.com         ─── 301（目标随窗口期变化）
  dl.skillmasterai.com      ─── proxy ──► OSS 桶（不变）
```

**三个地址决定「谁是当前入口」，它们必须一起切**：

| 面 | 在哪 | 现在 | 窗口期（P1） | 切回（P2） |
|---|---|---|---|---|
| 服务端 CORS 来源 | 仓库变量 `SKILLMASTER_SITE_ORIGIN` → `skillmaster.site.origin` | `https://www.skillmasterai.com` | `https://home.skillmasterai.com` | 回到 `www` |
| 控制台的站外地址 | `skillmaster-console/.env.production` 的 `VITE_SITE_URL`（构建期） | `https://www.skillmasterai.com` | `home` | 回到 `www` |
| 首页宿主 | `deploy/nginx/skillmaster.conf` | `www` | `home`（`www` 按 [Q1](#开放问题) 处理） | `home` 301 到 `www` |

- **CORS 只有一条规则**：`GET /web/session`，来源是配置的那一个值（`SiteConfig.corsConfigurationSource`）。窗口期只有 `home` 需要它；`www` 在窗口期不承担会话展示（见 Q1）。
- **会话 cookie 不变**：`Domain=skillmasterai.com` 已经覆盖 `home`；`XSRF-TOKEN` 继续保持主机级（ADR 0086 的不变项）。
- **控制台必须重出**：`VITE_SITE_URL` 是构建期常量，`vite.config.ts` 在缺失时拒绝构建；改值之后要重新发布控制台，不能只改环境变量。
- **控制台读这个值的有三处**：品牌链接（`AppShell.tsx`）、退出页「回到首页」（`LogoutPage.tsx`）、注册页两处法务链接（`RegisterPage.tsx`）。`design/README.md` 里把这个值写成 `www`，并说明它是 `VITE_SITE_URL` 的原值——P1 改的是部署值，不是界面契约，所以 `VITE_SITE_URL` 这一项不改 `design/`。A7 的法务文本改动会改 `design/`，见改动面表。

**改动的面与文件**：

| 面 | 文件 | 改什么 | 期 |
|---|---|---|---|
| 主机 | DNS、证书、`/etc/nginx/conf.d/` | 加 A 记录；重签证书加 SAN；拷新配置并 reload | P0 |
| 部署配置 | `deploy/nginx/skillmaster.conf` | 端口 80 名单加 `home`；新增 `home` 的 443 块 | P0 |
| 部署配置 | 同上 | 裸域 301 目标与 `www` 块按 Q1 调整 | P1 |
| 部署文档 | `deploy/README.md` | 拓扑、证书重签命令、切换与切回步骤、检查命令 | P0 / P1 / P2 |
| 运维 | 阿里云云监控 | 给 `home` 加一条 200 探测（权限见 `deploy/README.md` §自愈与监控） | P1 |
| 巡检 | `.github/workflows/ops-check.yml` | 域名解析清单加 `home`；主证书 SAN 断言 | P0 |
| 站点发布 | `.github/workflows/site.yml` | 部署探针改问 `home`、按内容比对、跟随跳转；部署 job 加 `concurrency` 串行化 | P0 |
| 控制台 | `skillmaster-console/.env.production` | `VITE_SITE_URL` 指向当前入口 | P1 / P2 |
| 服务端 | 仓库变量 `SKILLMASTER_SITE_ORIGIN` | 指向当前入口（无代码改动） | P1 / P2 |
| 法务 | `design/src/home/legalText.ts`、`skillmaster-site/src/legal/legalText.ts`、`skillmaster-server/src/main/resources/application.yml`、`skillmaster-server/src/main/java/com/skillmasterai/config/SkillmasterProperties.java` | 去掉适用范围的子域枚举；隐私政策升 `1.2`，两处文本、`application.yml` 与 `@DefaultValue` 同版；`design/` 的演示件按源重出，`home-check` 的版本断言改为形状（它原先钉死 `1.0`，已是基线红） | P0 |
| 设计说明 | `design/README.md` | 品牌链接段补「三处消费者」与「`www` 是原值、部署期可换」；界面契约不变 | P0 |
| 站点文档 | `skillmaster-site/README.md` | 提到同一构建也由 `home` 提供；CORS 一句改成指向配置键 | P0 |
| 发布文档 | `.dev-sop/release/site.md` | 产品形态补一句临时入口 | P0 |
| 版本文档 | `docs/versions/v63-home-subdomain/` | 本版四份文档 | P0 |

**为什么不改 `deploy/acme/auth.sh`**：它按 `CERTBOT_DOMAIN` 推导 `_acme-challenge` 记录，任何 `skillmasterai.com` 下的名字都会被它接住；`home` 不需要新钩子。

**为什么探针要按内容比对**：`site.yml` 的部署探针从主机上用 `--resolve` 问 nginx。只判状态码不够——443 的第一个 server 块是 `console`，任何匹配不上的名字（`home` 块缺失、拼错）都会拿到控制台首页的 200。所以它取 `/index.html` 并与 `/srv/skillmaster/site/index.html` 逐字节比。它跟随跳转，并把 `home` 与 `www` 都 `--resolve` 到本机：P2 把 `home` 变成 301 后，探针仍落在同一份构建上，不需要改名字。

## 数据模型

本版不涉及表结构、实体或存储格式变更。法务版本号是配置（`skillmaster.account.privacy-version`），不是表。

## 接口

**公开地址（对外契约）**：

| 地址 | 窗口期（P1） | 切回（P2） |
|---|---|---|
| `https://home.skillmasterai.com/…` | 200，首页静态文件 | 301 到 `https://www.skillmasterai.com$request_uri` |
| `https://www.skillmasterai.com/…` | 按 Q1：301 到 `home`，或一张静态说明页 | 200，首页静态文件 |
| `https://skillmasterai.com/…` | 301 到当前入口 | 301 到 `www` |
| `http://<上面任一个>/…` | 301 到同名 https | 同左 |

**会话读取（不变，只换来源）**：

- `GET https://console.skillmasterai.com/web/session`，只允许 `GET`、只允许一个已配置的来源、带凭据。
- 窗口期来源是 `https://home.skillmasterai.com`；切回后是 `https://www.skillmasterai.com`。
- 失败语义不变：查不到就是未登录（fail-open）。

**构建期常量**：

- `skillmaster-console` 的 `VITE_SITE_URL`：控制台品牌、退出页与注册页法务链接的唯一出处；缺值拒绝构建。
- `skillmaster-site` 的 `VITE_CONSOLE_URL`：不变，仍指 `console.skillmasterai.com`。

**法务版本（v56 的契约）**：页面常量与服务端配置必须同版；本次两者都到 `1.2`，生效日期 `2026 年 11 月 13 日`。v56 已定文本更新不打断现有用户，所以不需要重新同意。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 准备 | DNS A 记录、证书加 SAN、`home` server 块、巡检名单与 SAN 断言、按内容比对的探针、隐私政策 `1.2` 与枚举清理、文档；合并后按发布 SOP 把站点与服务端各 Deploy 一次。**`www` 不动，`SITE_ORIGIN` 与 `VITE_SITE_URL` 不动。** | A1、A2、A6（P0 一次）、A7 |
| P1 切换（所有者发话，**时机另定**） | `www` 按 Q1 处理；裸域 301 指向 `home`；`SITE_ORIGIN` 与 `VITE_SITE_URL` 切到 `home` 并重出控制台；云监控补 `home` | A3、A4、A6（P1 一次） |
| P2 切回（许可证下来） | `home` 301 到 `www`；裸域 301 指回 `www`；`SITE_ORIGIN` 与 `VITE_SITE_URL` 回退并重出控制台 | A5、A6（P2 一次） |

**每期明确不做**：P0 不切换入口（`home` 可以打开，但没人被指过去）；P0 唯一的用户可见变化是隐私政策文本与版本号（A7），部署后要人看一眼。P1 不碰 `dl.` 与 `console.` 的域名；P2 不删除 `home` 的解析与证书 SAN（见 §开放问题「切回后 `home` 长期保留」）。

**P0 的落地顺序**：先主机（DNS → 证书 → nginx），再合并仓库改动；否则每日巡检会在 `home` 还解析不了时先红。两步放在同一个维护窗口里并避开巡检时刻——主机先换配置、仓库后合并之间，巡检会因配置哈希不一致红一次，合并后恢复。合并后还要按发布 SOP 把站点与服务端各 Deploy 一次：合并只跑构建与测试，A7 的 `1.2` 要等这两次发布才生效（先站点、再服务端）。

## 开放问题

**已定（2026-11-13，所有者）**：

- **P1 的时机另定。** 本版这一笔只交付 P0；`www` 现在不动，`SITE_ORIGIN` 与 `VITE_SITE_URL` 也不动。`home` 在 P0 就可用，但在被指过去之前没有人会用它。
- **CORS 只放行一个来源。** 窗口期只有 `home` 承担首页；不改 `SiteConfig` 的来源形状，也不动 ADR 0086 的契约与测试。
- **切回后 `home` 长期保留。** 只把 nginx 改成 301；DNS 记录与证书 SAN 都不删，以后 `www` 维护时还能复用。
- **站点发布探针跟随跳转，不随 P2 改名。** 它按字节比对，并把 `home` 与 `www` 都 `--resolve` 到本机；P2 的 301 仍落在同一份构建上。
- **隐私政策的子域枚举去掉、版本升 `1.2`。** 括号里的三项会被当穷举读，`home` 不在其中；去掉枚举后主句覆盖所有子域，以后加子域不用再改文本。

仍开放：

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | P1 切换时，`www.skillmasterai.com` 是 301 到 `home`，还是换一张静态说明页？ | 301 到 `home`：旧链接不断，且 `www` 不再提供内容。若管局明确要求 `www` 不得指向本站内容，改为静态说明页——那张页必须先有 `design/` 的设计稿与一小版实现，不能在 P1 里现造。 | 由许可证办理的明确要求决定，切换前所有者确认 |

## 附录 A · 与现有代码的关系

不改产品代码的逻辑。`SiteConfig`、`siteUrl.ts`、`site.yml`、`ops-check.yml` 都只改取值或探测目标；`deploy/nginx/skillmaster.conf` 新增一个与 `www` 同形的 server 块；两处 `legalText.ts` 改一句文本与版本号；服务端只改两个默认值（`application.yml` 与 `@DefaultValue`）；设计侧改一条检查断言（`home-check`）并按源重出演示件。

## 附录 B · 来源与出处

- `skillmaster-console/src/siteUrl.ts`：`VITE_SITE_URL` 是控制台指向首页的唯一出处。
- `skillmaster-console/vite.config.ts`：缺值拒绝构建。
- `deploy/acme/auth.sh`：DNS-01 钩子按注册域自行推导 `_acme-challenge` 名字。
- `deploy/README.md` §Certificates：`skillmasterai.com` 那张证书的重签命令（apex/www/console，本版加 `home`），`dl.` 是另一张。
- `.github/workflows/site.yml`：部署探针的 `--resolve`、内容比对与回滚。
- `.github/workflows/ops-check.yml`：每日巡检的域名清单、证书 SAN 与 nginx 配置哈希比对。
- [ADR 0086](../../decisions/0086-the-homepage-reads-the-session.md)：首页读取会话的契约，本版沿用。
- [ADR 0187](../../decisions/0187-the-marketing-home-can-run-on-a-subdomain.md)：用同域子域做临时入口、只留一个 CORS 来源。
