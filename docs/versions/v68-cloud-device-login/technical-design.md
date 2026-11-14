# v68-cloud-device-login · 技术设计

> **最后更新**：2026-11-14
> **状态**：已实现（服务端、控制台、CLI 与设计件完成；待第 6 关审计与合并）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文只链接、不复述理由。

## 架构

这一版把 CLI 登录的**取回路径**从「浏览器回跳本机」换成「CLI 轮询」。批准仍然是一次浏览器会话里的同意，授权行仍然是 `oauth_authorization`，撤销仍然是「登录设备」；被换掉的只有两端的交接方式。

```
 云设备 / 任意机器                          用户的浏览器（手机、笔记本）              服务端
 ────────────────────────────────────────────────────────────────────────────────────────
 skmr login
   │ ① POST /cli-device-sign-ins
   │    报 client_id、scope、code_challenge、建议名称
   │    ← ticket、device_secret、10 分钟、1 秒
   │ ② 打印 https://console/cli-sign-in?ticket=…
   │    （有启动器就顺手 open，不等待）
   │ ③ 轮询 POST …/{ticket}/token
   │    带 device_secret 与 code_verifier
   │                                              GET /cli-device-sign-ins/{ticket}
   │                                              ← 名称、客户端、权限、来源、发起时间
   │                                              未登录 → 登录/注册，回同一页
   │                                              POST /web/…/{ticket}/approve（会话 + CSRF）
   │                                              写 oauth_authorization（label / address）
   │    ← 200 + access_token（轮询那一刻写入）
   │ ④ 存本机（文件 0600，ADR 0185）
   │ ⑤ 打印账号与撤销路径
```

**四个边界各自为什么这么放**：

- **发起是匿名的。** CLI 还没有身份，这正是它要换的东西；所以发起这一条必须能匿名写，因此**必须自带限流**，并且只能创建一行短命的待批准记录，不能创建凭据。
- **批准走会话面。** 与 v4 同一条规矩：谁同意由浏览器会话决定，不由请求体决定。批准只写授权行，不铸令牌。
- **轮询是匿名的，但只认两样东西。** `device_secret`（只有发起它的 CLI 拿得到）与 PKCE 的 `code_verifier`（同样只在 CLI 手里）。票根（ticket）走 URL，可能进浏览器历史与聊天记录，所以它**单独不足以换到任何东西**。
- **令牌在轮询这一次写。** 授权行已在批准那一下由会话写好；轮询只是把它的令牌交出去。这与已退役的一次性码兑换控制器同形，只是不再经过一次性码与回跳。**这不放松 [ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md) §决定 1**：授权行只可能由会话批准写下；轮询出示的是发起时生成的 `device_secret` 与 PKCE 证明，**凭据换不出凭据**。

**与现状的差异**：回环监听、固定端口、`cli_sign_in_code` 与 `/cli-sign-ins/exchange` 全部退役。`docs/current/modules/M02-token-and-as.md` 已同步改写：CLI 那一侧描述的就是本节的链接 + 轮询，回环与授权码那半只留给未来的第三方客户端；其余（授权行、会话列表、撤销、限速）不变。

**多实例**：没有长连接，也没有跨实例推送；每次轮询都读库。批准与轮询落在哪台实例上都可以。

### 沙箱

这一版把「沙箱里能不能登录」的答案从「不能，去签 key」改成「只要出站 HTTPS 通，就能用链接登录」。三件事分开看：

- **浏览器不再是前提。** 检测到沙箱（`sandbox.Detect().Sandboxed`）就不调用 `OpenBrowser`，也不等它；链接照常打印。**这条只管 `skmr login` 自己**：命令**自动发起**登录时另有一套门禁（沙箱、`CI`、打不开浏览器都不发起，见 [PRD](prd.md) 验收 #10）——那是 v4 的自动登录行为，不是凭据分流，[ADR 0200](../../decisions/0200-cloud-device-login-approves-on-a-web-page.md) §决定 3 说的「不再按浏览器分流」指的是登录流程本身。v4 的回环监听与 v62 记录的「回环被挡、等满五分钟」一起消失。
- **网络是唯一的新前提。** 发起与轮询都出站到服务端；白名单为空时，CLI 用现有的 `sandbox.Snippets(client)` / `networkHint(host)` 给出放行片段，并指向 `skmr doctor --fix`（ADR 0186 会把服务端与更新域名写进去）。WorkBuddy 5.8.3 实测默认放行 TLS、回环与网络；Claude Code、Cursor 可能要放行一次。
- **凭据落状态目录。** 0600 文件（ADR 0185），目录由 `doctor --fix` 放行（ADR 0186）。写不进去时按现有凭据错误报，不新增路径。

安装说明里「不要在沙箱里运行 `startup`，它会打开浏览器等满五分钟」那一句，P3 改成「沙箱放行服务端域名后，用链接登录」。

## 数据模型

新增一张待批准表。票根与设备密钥都只存哈希，沿用 `access_token` / `cli_sign_in_code` 的口径。

```sql
CREATE TABLE cli_device_sign_in (
  ticket_hash        TEXT PRIMARY KEY,   -- sha256(链接里的 ticket)，hex
  device_secret_hash TEXT NOT NULL,      -- sha256(只回给 CLI 一次的 secret)，hex
  client_id          TEXT NOT NULL,      -- 现有 oauth_client 的一行
  scopes             TEXT NOT NULL,      -- 空格分隔，发起时与 client 的 scope 取交集
  code_challenge     TEXT NOT NULL,      -- PKCE S256
  name               TEXT NOT NULL,      -- CLI 报的建议名称（批准时可改）
  address            TEXT,               -- 服务端看到的发起方地址，可空
  created_at         TEXT NOT NULL,
  expires_at         TEXT NOT NULL,      -- created_at + 10 分钟
  last_polled_at     TEXT,                -- 上一次轮询；1 秒间隔的原子闸，多实例共享
  approved_at        TEXT,
  authorization_id   TEXT,               -- 批准写下的授权行；无外键，理由同 cli_sign_in_code
  denied_at          TEXT,
  consumed_at        TEXT                -- 令牌交出去的那一刻；一次性
);

CREATE INDEX cli_device_sign_in_expires_at ON cli_device_sign_in (expires_at);
```

- **状态是推导出来的，不是一列**，顺序是 `consumed_at` → `denied_at` → `approved_at` → `expires_at`：used / denied / approved / expired / pending。**已批准的排在过期之前**：十分钟约束的是人的决定，不是机器的领取；CLI 每秒问一次，反过来会让「最后一秒点同意」永远领不到凭据，而授权行已经写下了。没有 `status` 列，就没有两个真相。
- **没有外键**：票根在自己的过期时间上结束，不因为授权行被删而中途消失——与 `cli_sign_in_code` 同一条理由。
- **不存明文**：ticket 只存在于链接与内存，device_secret 只出现在创建响应里。
- **删除**：`cli_sign_in_code` 在本版删掉（迁移里 `DROP TABLE`），它唯一的使用者 `/cli-sign-ins/exchange` 同时退役。

## 接口

票根与设备密钥都是 32 字节随机数，base64url 无填充（43 字符）。链接形如：

```
https://console.skillmasterai.com/cli-sign-in?ticket=<ticket>
```

| # | 方法 | 路径 | 作用 | 鉴权 | 限流 |
|---|---|---|---|---|---|
| 1 | `POST` | `/cli-device-sign-ins` | 发起；返回 ticket 与 device_secret | 匿名 | 每地址 30 次/10 分钟 |
| 2 | `GET` | `/cli-device-sign-ins/{ticket}` | 读公开视图（名称、客户端、权限、来源、发起时间、状态） | 匿名 | 每地址 120 次/分钟 |
| 3 | `POST` | `/web/cli-device-sign-ins/{ticket}/approve` | 会话里同意；写授权行 | 会话 + CSRF | 无单独限流（会话 + CSRF 是门槛；重复批准只成功一次） |
| 4 | `POST` | `/web/cli-device-sign-ins/{ticket}/refuse` | 会话里拒绝 | 会话 + CSRF | 无单独限流（同上；拒绝幂等） |
| 5 | `POST` | `/cli-device-sign-ins/{ticket}/token` | 轮询；pending/approved/denied/expired/used | 匿名，凭 device_secret + code_verifier | 每地址 120 次/分钟，且每票根 1 次/秒，超出 429 |
| 6 | `POST` | `/cli-device-sign-ins/{ticket}/cancel` | Ctrl-C 取消；待批准标成 denied，已批准未领取的结束掉 | 匿名，凭 device_secret | 无单独限流：错 secret 一律 404，票根状态不变 |

**形状**：

- ① 请求 `{client_id, scope, code_challenge, name}` → `201 {ticket, device_secret, expires_in: 600, interval: 1}`。`scope` 与 `oauth_client` 那一行取交集，越界的一律丢弃（现有规则）。
- ② `200 {status, name, client, scopes, from, created_at, expires_at, age_minutes}`；`age_minutes` 由服务端算（浏览器时钟不可信）；未知票根 `404`。**GET 不改变任何状态**，IM 的链接预览抓一下不会消耗票根。响应头 `Cache-Control: no-store`、`Referrer-Policy: no-referrer`。
- ③ 请求 `{name}` → `204`。名称必填（与 v4 一致，空名称让「登录设备」那一行没有东西可认）。票根不是 pending → `409`。
- ④ 请求空 → `204`。
- ⑤ 请求 `{device_secret, code_verifier}`：
  - pending → `202 {status:"pending"}`；
  - approved → `200 {status:"approved", access_token, expires_at, scope, name, username}`，同时写 `consumed_at`；
  - denied → `410 {status:"denied"}`；expired → `410 {status:"expired"}`；used → `410 {status:"used"}`；
  - secret 或 verifier 不对、票根不存在 → `404`（不区分「不存在」与「不对」，理由同一次性码）；**verifier 在状态分支之前校验**，否则批准前的每次回答都是状态探针；
  - 轮得太快 → `429` + `Retry-After` 头（body 是通用错误信封 `{"error":{"code":"too_many_requests"}}`；CLI 读头）。
- ⑥ 请求 `{device_secret}` → `204`。**为什么要这一条**：验收 #3 要求取消后链接不能再被批准，而票根在 CLI 手里时只有 CLI 知道取消发生了。它和轮询用同一把 `device_secret`——拿到链接的人不能取消别人的票根；已经结束的票根再取消是 `204`，什么都不改（幂等）。**取消与批准同时到达时，先写下的那个赢，但取消会把输掉的那次收尾**：批准先落下、取消随后到达时，取消在**一个事务**里把该行标成 denied（一条 `UPDATE … RETURNING`，`approved_at IS NOT NULL` 时不受过期限制）并撤销那条**已批准但没人领取**的授权——页面因此说链接已失效，票根也不会既「已批准」又永远取不到凭据。

**这六条里有四条在 `/cli-device-sign-ins` 上，而它不在 `/web` 之下。** 反向代理与开发代理都按前缀白名单转发，`/cli-sign-ins`（v4 的回环交换）与 `/cli-device-sign-ins`（本版）是两个前缀，前者匹配不上后者；漏掉后者的后果不是 404，而是页面拿到 SPA 的 `index.html`，把一条完好的链接报成「已经用过或过期」。两处清单都要点名它：`skillmaster-console/vite.config.ts` 与 [`deploy/nginx/skillmaster.conf`](../../../deploy/nginx/skillmaster.conf)（2026-11-14 本机端到端才发现的）。

**令牌本身**走现有的签发与滑动：同一把尺子（闲置 24 小时 / 绝对 7 天），同一张 `oauth_authorization`，同一个 `TokenStore`。撤销仍走「登录设备」，`revokeAllFor` 仍覆盖「退出其他所有设备」。

## 分期

| 期 | 交付 | 验收 | 这一期明确不做 |
|---|---|---|---|
| P1 | 表、五个接口（取消在 P2 随 Ctrl-C 交付）、控制台 `/cli-sign-in?ticket=` 的待批准与失效态 | curl + 手机浏览器走通「发起 → 批准 → 轮询拿到令牌」 | 不改 CLI；回环仍在 |
| P2 | CLI 换成链接 + 轮询；桌面自动弹浏览器；Ctrl-C 取消 | 无显示器容器、桌面、云主机各跑一次 | 不删旧路由与旧表 |
| P3 | 删除回环监听、固定端口、`cli_sign_in_code`、`/cli-sign-ins/exchange`；更新 `current/` 与安装说明 | 全仓无旧路径引用；安装说明有云端分支 | 不做二维码、不做宿主注入 |

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 设备上的凭据活多久？ | 与浏览器会话同一把尺子（闲置 24 小时 / 绝对 7 天），服务器每周重新批准一次 | P2 实测一次日常使用；嫌烦再单开 ADR 放宽 |
| Q2 | 旧路由与 `cli_sign_in_code` 在本版直接删，还是留一版？ | 直接删：回环退役是 [ADR 0200](../../decisions/0200-cloud-device-login-approves-on-a-web-page.md) 的决定，留着就是第二套会漂的路径 | P3 删除后跑全量服务端测试与 `impact.py --check` |
| Q3 | 拒绝后的票根，别人再打开时看到哪句话？ | 复用「已经用过或过期了」——它已经被回答过，不再是一道问题；精确到「被拒绝」会多一个态 | P1 看真实的一次拒绝；文案要改就回第 2 关 |
| Q4 | 登录成功要不要发一条通知？ | 本版不发：控制台「登录设备」已经可见，通知是另一个模块的面 | 不做；有需要再进 TODO |
| Q5 | 限流的数值合适吗？ | 按上表起步：发起 30 次/10 分钟、view 与轮询各 120 次/分钟（都按地址），轮询另加每票根 1 次/秒。**数值写在 `PgAuthThrottle.Rule` 里，不做部署配置**——它们描述的是这套防御的形状，能改的部署就是一个能把自己调松的部署 | P1 压测 10 并发；要调就改代码并跑 `AccountThrottleIT` |
| Q6 | 代理后面怎么取发起方地址？ | 沿用 [ADR 0018](../../decisions/0018-caller-address-behind-the-proxy.md) 的形状：代码只读 `getRemoteAddr()`，容器的 `forward-headers-strategy: framework` 会用反代覆写过的 `X-Forwarded-For` 填它 | 部署时核对 nginx 是覆盖而不是追加该头；读不到就留空，整行不显示 |
| Q7 | Ctrl-C 取消后要通知服务端吗？ | **要**（2026-11-14 定）：验收 #3 要求取消后不能再被批准，只有取消的那一端知道它发生了。端点⑥与轮询共用 `device_secret`，没有它的人取消不了；失败当成善意忽略，票根照旧 10 分钟过期 | 已实现；P2 实测 Ctrl-C |
| Q8 | 桌面还要不要保留「自动回跳」的体验？ | 不要：自动弹浏览器 + 一次批准 + 两秒内终端完成，已经够近 | P2 在 macOS / Windows 各跑一次 |
| Q9 | 轮询的响应在路上丢了怎么办？ | 一次性，丢了就重新发起：`consumed_at` 已写，旧票根不能再换；重试会把一份可能已泄漏的令牌再送一次 | P2 模拟一次断网；确认 CLI 的句子是「重新运行 `skmr login`」而不是重试同一个票根 |
