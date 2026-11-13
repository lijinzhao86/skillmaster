# v63-home-subdomain · 测试方案

> **最后更新**：2026-11-13
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 期 | 对应用例 | 状态 |
|---|---|---|---|
| A1 `home` 返回与同一构建一致的首页 | P0 | T1、T2 | 通过 |
| A2 证书覆盖 `home` 且能续期 | P0 | T3 | 通过 |
| A3 控制台三处链接与裸域 301 指向 `home` | P1 | T4、T7 | 未执行 |
| A4 `home` 与 `www` 都显示登录态 | P1 | T5、T6 | 未执行 |
| A5 `www` 可访问、内容不变、不被任何页面链接 | P1 | T7 | 未执行 |
| A6 巡检与探针在各期都有效 | P0 / P1 | T8、T9 | P0 通过；P1 待执行 |
| A7 隐私政策 `1.2` 且页面与服务端同版 | P0 | T10 | 通过 |

## 用例

### T1 · `home` 解析到部署主机

- **前置**：DNS A 记录已添加。
- **步骤**：`getent ahostsv4 home.skillmasterai.com`。
- **期望**：解析出部署主机的地址（与仓库变量 `DEPLOY_HOST` 一致）。

### T2 · `home` 提供与 `www` 同一份构建

- **前置**：nginx 配置已拷到主机并 `nginx -t` 通过。
- **步骤**：在主机上分别取两个名字的 `/index.html`，再与磁盘上的构建比：

  ```bash
  curl -fsS --resolve home.skillmasterai.com:443:127.0.0.1 -o /tmp/home.html https://home.skillmasterai.com/index.html
  curl -fsS --resolve www.skillmasterai.com:443:127.0.0.1  -o /tmp/www.html  https://www.skillmasterai.com/index.html
  cmp /tmp/home.html /srv/skillmaster/site/index.html
  cmp /tmp/www.html  /srv/skillmaster/site/index.html
  cmp /tmp/home.html /tmp/www.html
  ```

- **期望**：三条 `cmp` 都无输出。**只回 200 不算**：443 的默认 server 是 `console`，`home` 块缺失时未知 Host 会拿到控制台首页的 200。

### T3 · 证书含 `home` 且续期可用

- **前置**：证书已重签。
- **步骤**：`openssl x509 -in /etc/skillmaster/tls/fullchain.pem -noout -ext subjectAltName`；`certbot renew --cert-name skillmasterai.com --dry-run --no-random-sleep-on-renew`。
- **期望**：SAN 含 `DNS:home.skillmasterai.com`；dry-run 成功。

### T4 · 控制台三处链接指向 `home`（P1）

- **前置**：控制台以 `VITE_SITE_URL=https://home.skillmasterai.com` 重新构建。
- **步骤**：在 `skillmaster-console/dist` 里查站外地址；或浏览器打开控制台，看品牌链接、退出页「回到首页」、注册页两处法务链接。
- **期望**：三处都是 `https://home.skillmasterai.com/`；`grep -rc "www.skillmasterai.com" skillmaster-console/dist` 的计数为 0（`-r` 不能省：对目录用 `-c` 会报 `Is a directory` 而不是打印 0）。

### T5 · CORS 同时放行两个来源（P1）

- **前置**：带列表支持的服务端已部署，`SKILLMASTER_SITE_ORIGIN` 已是两个值。
- **步骤**：对两个来源各发一次预检：

  ```bash
  for o in https://www.skillmasterai.com https://home.skillmasterai.com; do
    curl -sS -D- -o /dev/null -X OPTIONS -H "Origin: $o" \
      -H 'Access-Control-Request-Method: GET' https://console.skillmasterai.com/web/session
  done
  ```

- **期望**：两次响应都带 `access-control-allow-origin: <请求的 Origin>` 与 `access-control-allow-credentials: true`；`/web/keys` 仍然没有放行头。

### T6 · 两个宿主都显示已登录态（P1）

- **前置**：在 `console.skillmasterai.com` 登录，会话 cookie 的 `Domain=skillmasterai.com`。
- **步骤**：浏览器分别打开 `https://home.skillmasterai.com/` 与 `https://www.skillmasterai.com/`。
- **期望**：两处导航都显示「进入控制台」与账号，而不是「登录 / 开始使用」。

### T7 · `www` 是独立页面（P1）

- **前置**：P1 已部署。
- **步骤**：
  1. 在主机上取 `www` 的 `/index.html`，与 `/srv/skillmaster/site/index.html` 比；
  2. `grep -rc "www.skillmasterai.com" skillmaster-console/dist skillmaster-site/dist`；
  3. `curl -sSI https://skillmasterai.com/` 看裸域去向。
- **期望**：`www` 的内容与构建逐字节一致；两个 `dist` 里指向 `www` 的链接计数为 0；裸域 301 到 `home`。

### T8 · 每日巡检（P0 / P1 各一次）

- **前置**：仓库改动已合并，主机配置与仓库一致。
- **步骤**：手动跑一次 `ops-check` 工作流。
- **期望**：nginx 配置哈希一致；五个名字都解析到部署主机；主证书 SAN 含四个主机名；两张证书剩余天数均大于阈值。

### T9 · 站点发布探针（P0 / P1 各一次）

- **前置**：`site.yml` 的探针是当前版本（按内容比对、跟随跳转、两个名字都 `--resolve` 到本机）。
- **步骤**：手动 `workflow_dispatch` 一次站点发布。
- **期望**：探针取到的 `/index.html` 与 `/srv/skillmaster/site/index.html` 逐字节相同，发布成功；回滚逻辑未被触发。

### T10 · 隐私政策版本同版（P0）

- **前置**：法务文本与服务端配置已改，且站点与服务端都已按 P0 第 5 步部署（合并不会发布）。
- **步骤**：`grep` 两处 `legalText.ts` 的 `PRIVACY.version` 与 `effective`、`application.yml` 的 `privacy-version` 与 `SkillmasterProperties` 的 `@DefaultValue`；重出设计件并跑 `home-check`；跑 `skillmaster-site` 的 `npm test`；用一个真实注册走一遍（或读 `AccountDeletionIT` 的同类断言）。
- **期望**：两处 `PRIVACY` 常量是 `1.2` / `2026 年 11 月 13 日`；`application.yml` 与 `@DefaultValue` 都是 `1.2`；演示件与源重建逐字节一致且 `home-check` 全绿；页面显示「版本 1.2」；生产上注册记录的 `privacy_version` 也是 `1.2`（它取新 jar 的默认值，`server.yml` 不渲染这个变量）。

## 环境与数据

- **环境怎么造**：生产 ECS 与阿里云 DNS。P0 的仓库改动先不合并，主机侧先加解析、证书与 nginx；配置与探针改完再合并，`home` 才不会因为解析不了而红——但主机换配置与合并之间，巡检会因配置哈希不一致红一次，合并后恢复。合并后再按 P0 第 5 步部署站点与服务端。
- **P1 的顺序**：先部署带列表支持的服务端（变量仍是单值）；再把 `SKILLMASTER_SITE_ORIGIN` 改成两个值并**再部署一次服务端**（变量只在部署时渲染进 `server.env`）；然后重出控制台、改裸域 301。变量变成列表后，回滚到 P1 之前的服务端 release 要先把它改回单值。
- **数据**：无业务数据；登录态用例需要一个真实账号（内测账号即可）。
- **清理**：用例不写业务数据；`--resolve` 探针只读。

## 结果

| 用例 | 期 | 结果 | 证据 |
|---|---|---|---|
| T1 | P0 | 通过 | `dig +short home.skillmasterai.com` → `47.117.36.14` |
| T2 | P0 | 通过 | 主机上从 `home` 与 `www` 各取 `/index.html`，均与 `/srv/skillmaster/site/index.html` 逐字节相同（`cmp` 无输出） |
| T3 | P0 | 通过 | 主证书 SAN 含 `DNS:home.skillmasterai.com`；`certbot renew --dry-run` → all simulated renewals succeeded |
| T4 | P1 | 未执行 | — |
| T5 | P1 | 未执行 | — |
| T6 | P1 | 未执行 | — |
| T7 | P1 | 未执行 | — |
| T8 | P0/P1 | P0 通过 | Ops check run `43282973137` success：五个名字解析、主证书 SAN、配置哈希与证书天数 |
| T9 | P0/P1 | P0 通过 | Site Deploy run `43282740310` success；探针按内容比对通过，未触发回滚 |
| T10 | P0 | 通过 | 站点构建含「各子域与」与「版本 1.2」；部署 jar 的 `application.yml` `privacy-version` 为 `1.2`；主机 `server.env` 不含 `SKILLMASTER_PRIVACY_VERSION`，运行值即 jar 默认值；设计件按源重出、`home-check` 全绿 |

第一次 Site Deploy（run `43281709582`）在主机侧 P0 之前被触发：`home` 还没有解析、证书也没有它的 SAN，探针按设计失败并回滚到上一版。补齐 DNS、证书与 nginx 之后重发成功；这次先后顺序反过来是操作问题，不是代码问题，运行手册已写明顺序。

## 已知问题

本版范围内暂无知悉缺陷。
