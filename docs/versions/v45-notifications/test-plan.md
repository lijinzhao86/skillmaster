# v45-notifications · 测试方案

> **最后更新**：2026-11-04
> **所属版本**：[`README.md`](README.md)

## 验收映射

| 验收 | 用例 |
|---|---|
| A1 | T1 |
| A2 | T2 |
| A3 | T3 |
| A4 | T4 |
| A5 | T5 |
| A6 | T6 |
| A7 | T7 |
| A8 | T8 |
| A9 | T9 |
| A10 | T10 |
| A11 | T11 |
| A12 | T12 |
| A13 | T13 |
| A14 | T14 |
| A15 | T15 |
| A16 | T16 |
| A17 | T17 |
| A18 | T18 |
| A19 | T19 |

## 用例

### T1 · 共享不授予权限（A1）

- **前置**：`owner/private-skill` 是私有 skill；`invitee` 没有被授权。
- **步骤**：`owner` 调 `POST /api/v1/skills/owner/private-skill/invitations`，body `{"handle":"invitee","role":"write"}`；`invitee` 用自己的凭据读该 skill、读授权名单；查 `skill_grant` 与 `skill_invitation`。
- **期望**：发出邀请 201；`invitee` 读到 404（私有 skill 对陌生人不存在）；`skill_grant` 没有这一行；`skill_invitation` 有一行，`expires_at` 是发出后 30 天。

### T2 · 控制台接受与拒绝（A2）

- **前置**：两条待接受邀请，分别给 `invitee` 与 `invitee2`。
- **步骤**：`invitee` 在控制台通知中心点「接受」；`invitee2` 点「拒绝」；两人各自看「待处理」与「全部」。
- **期望**：接受后 `skill_grant` 有对应行，通知 `state=accepted`，从「待处理」消失、「全部」显示「已接受」；拒绝后没有 grant，`state=declined`，同样从「待处理」消失、「全部」显示「已拒绝」。

### T3 · CLI 接受、拒绝与分享的链路（A3）

- **前置**：同 T2；另有一条未读的申请结果通知。
- **步骤**：`invitee` 不带参数跑 `skmr notifications`；对有邀请的那一条按输出里的命令跑 `accept <id>`；`invitee2` 跑 `decline <id>`；`owner` 跑 `skmr skill share owner/private-skill --to invitee3 --role browse`，再按回执里的 `skmr skill shares owner/private-skill` 查看。
- **期望**：默认列表同时有「待处理邀请」与「未读结果」，每条下面有可复制的下一步命令；`accept` 回显拿到的档位，`decline` 回显「已拒绝」，退出码 0；`owner` 的 share 回执说「等待对方接受、30 天后过期」并给出 `skmr skill shares`；`shares` 分「已生效 / 待接受」两组，`invitee3` 在待接受组，且**没有接受 / 拒绝**动作；`invitee` 接受后 `owner` 的名单里出现其授权行。

### T4 · 申请通知的收件人（A4）

- **前置**：`owner`；`admin1` 持有 `admin` 档；`maintain1` 持有 `maintain` 档；`invitee` 能看见该 skill（公开 `browse`，或被授了 `browse`）。
- **步骤**：`invitee` 调 `POST /api/v1/skills/owner/private-skill/access-requests`，body `{"rung":"browse"}`；分别读 `owner`、`admin1`、`maintain1` 的通知列表与未读数。
- **期望**：`owner` 1 条 `access_request`、未读 1；`admin1` 1 条、未读 1；`maintain1` 0 条、未读 0。

### T5 · 批准 / 拒绝收口与结果回执（A5）

- **前置**：T4 的一条申请（申请的是 `browse`），另加同一 skill 上第二位申请人的一条。
- **步骤**：`owner` 批准第一条、档位改成 `write`；拒绝第二条；读 `owner`、`admin1`、两位申请人的通知。
- **期望**：`owner` 与 `admin1` 的申请通知变成 `approved` / `rejected` 并留档；被批准的人收到一条 `access_request_result`、`state=approved`、说明最终档位 `write`；被拒绝的人收到 `state=rejected`；四人未读数按各自规则变化。

### T6 · 已读不等于已处理（A6）

- **前置**：一条待接受邀请。
- **步骤**：`invitee` 调 `POST /api/v1/notifications/{id}/read`；查邀请行与通知状态；再接受。
- **期望**：标已读后 `read=true`、未读减一，但通知 `state` 仍是 `pending`、邀请行仍在、「待处理」仍列；接受后 `state=accepted`，邀请行消失。

### T7 · 广播型通知（A7）

- **前置**：测试夹具插入一条 `announcement` 与两个收件人（`readerA`、`readerB`）。
- **步骤**：`readerA` 标已读；两人各读列表与未读数；查数据库行数。
- **期望**：`notification` 只有一行正文；`notification_recipient` 两行；`readerA` 未读 0、`readerB` 未读 1；公告不出现在「待处理」；`readerA` 只在「全部」，`readerB` 因未读出现在默认 `attention`（铃铛下拉）与「全部」；没有动作。

### T8 · 分页、顺序与 `99+`（A8）

- **前置**：为同一收件人造 120 条通知，`created_at` 递增。
- **步骤**：请求第 1 页与第 2 页（`per=10`）；读总量与未读数；看控制台顶栏的数字。
- **期望**：第 1 页 10 条、时间倒序；第 2 页接着第 1 页；`total=120`；接口未读 120；顶栏显示 `99+`。

### T9 · 存量授权不受影响（A9）

- **前置**：在升级前的数据库里造一条 `skill_grant`（模拟旧版本共享过）；再跑 V34 迁移。
- **步骤**：被授权人读该 skill、跑 `skmr skill shares owner/private-skill`。
- **期望**：读得到；名单里仍列出该授权；没有因为改成邀请制而出现「待接受」或失去权限。

### T10 · 未处理通知去重（A10）

- **前置**：同一 skill、同一收件人。
- **步骤**：连续发两次邀请（第二次换档位）；连续提交两次申请。
- **期望**：`skill_invitation` 仍是一行、`role` 与 `expires_at` 被更新；未处理通知仍各一条；申请行仍一行（upsert）；`notification` 不新增未收口的重复行。

### T11 · 失去管理权后的申请通知（A11）

- **前置**：`admin1` 是 `admin`，有一条待批申请的通知。
- **步骤**：`owner` 把 `admin1` 降成 `maintain`；`admin1` 读通知列表；直接 `GET /api/v1/notifications/{id}`。
- **期望**：列表里不再出现该通知；单条 404；响应里没有任何申请人或 skill 的内容。

### T12 · 控制台与 CLI 的已读状态一致（A12）

- **前置**：一条未读通知。
- **步骤**：在控制台打开（查看 / 去处理）它；`skmr notifications list --unread`；反向再走一次（CLI `read`，控制台刷新）。
- **期望**：一边读掉，另一边下一次读取不再出现在未读里；未读数两边相同。

### T13 · 没有实时通道（A13）

- **前置**：控制台已登录。
- **步骤**：打开通知页与顶栏，看网络面板；刷新页面；在 CLI 标一条已读，再刷新控制台。
- **期望**：没有 SSE / WebSocket 连接；刷新后未读数与列表都是最新；不刷新时不会自己变。

### T14 · 处理完的通知留档（A14）

- **前置**：七种结局各造一条：接受、拒绝、过期、撤销、批准、未通过、撤回（可分布在多条通知上）。
- **步骤**：逐一走完；分别看「待处理」与「全部」。
- **期望**：「待处理」里一条都不再出现；「全部」按时间倒序显示各自的 `state`；时间与结果都在。

### T15 · 邀请 30 天过期（A15）

- **前置**：一条待接受邀请。
- **步骤**：把 `skill_invitation.expires_at` 与 `notification.expires_at` 改成 31 天前；`invitee` 尝试接受；`owner` 读 `GET /invitations` 与 `skmr skill shares`；`owner` 重新共享。
- **期望**：接受 409，`reason=expired`；`invitee` 看到「已过期」；`owner` 的邀请列表里该行 `state=expired`，控制台「已过期的共享」与 CLI「已过期」组列出它；重新共享产生一条新邀请与新通知，旧通知仍是 `expired`。

### T16 · 撤回申请与撤销邀请的收口（A16）

- **前置**：一条待批申请、一条待接受邀请。
- **步骤**：申请人 `DELETE /api/v1/skills/.../access-requests/me`；`owner` `DELETE /api/v1/skills/.../invitations/{handle}`。
- **期望**：申请侧的通知变成 `withdrawn`；邀请侧的通知变成 `revoked`；两条业务行都消失；「待处理」不再列它们。

### T17 · 铃铛快速查看（A17）

- **前置**：至少 5 条「待处理或未读」，其中一条地址很长；另有一条已读且已处理的旧通知。
- **步骤**：点击铃铛；Tab 到铃铛按 Enter；按 Esc；点浮层外面；只把鼠标悬停在铃铛上；把窗口缩到 400px；用 `?long=1` 复现长地址。
- **期望**：点击或 Enter 展开最近 5 条（旧通知不在）；Esc 关闭且焦点回到铃铛；点外面关闭；仅悬停不展开；400px 下不展开、铃铛直接进通知页；正文最多两行、不撑破浮层；共享邀请行内可接受 / 拒绝、底部的「查看全部」进通知页。
- **设计件**：`node design/scripts/console-states.mjs "design/skillmaster-控制台演示.html" artifacts/design/console --only notifications` 全绿（含 `notifications-bell`、`notifications-bell-long`、`notifications-bell-narrow`）。

### T18 · 「查看」的去向与整行可点（A18）

- **前置**：已接受、已批准、未通过、已拒绝、已过期、已撤销各一条；一条待处理申请。
- **步骤**：逐个状态点行与行尾动作；用键盘 Tab 到可点行并按 Enter / Space；点行内按钮。
- **期望**：已接受 / 已批准 / 未通过 → skill 详情页；已拒绝 / 已过期 / 已撤销 → 没有「查看」、整行不可点、没有悬停手型；待处理申请 → 所在 skill 的「设置 › 协作者」；有去向的行整行可点，Enter / Space 同效；点行内按钮不触发整行。
- **设计件**：`--only notifications` 与 `--only skill-settings` 全绿；`skill-settings-collaborators` 截图里「待接受的共享」分组可见。

### T19 · CLI 批准与拒绝申请（A19）

- **前置**：`lin1` 对 `owner/private-skill` 申请了 `browse`；`lin2` 申请了 `browse`；`lin3` 申请了 `write`（用于拒绝）。`owner` 收到三条申请通知；密钥是可写密钥，另备一把只读密钥。
- **步骤**：
  1. `owner` 跑 `skmr notifications`，确认三条申请在「待处理」里、下一行是 `approve` / `deny` 命令。
  2. 不带 `--yes` 跑 `skmr notifications approve <id>`；核对退出码与数据库（`skill_access_request`、`skill_grant`、通知）没有变化。
  3. 对 `lin1` 不带 `--role` 跑 `approve <id> --yes`（默认按申请的 `browse`）；对 `lin2` 跑 `approve <id> --role write --yes`（改档）；对 `lin3` 跑 `deny <id> --yes`。
  4. 用只读密钥跑一次 `approve`。
- **期望**：不带 `--yes` 只打印后果与完整命令，不执行；`lin1` 拿到 `browse`（默认档），`lin2` 拿到 `write`（改档），`lin3` 什么也没拿到；三人的申请行都消失；A 侧通知分别 `approved` / `approved` / `rejected`，`lin1`、`lin2` 收到 `state=approved` 且档位与最终授予一致，`lin3` 收到 `state=rejected`；三条审计都记下申请人、申请的档位与最终档位；只读密钥得 403；同样的申请分别用控制台批准 / 拒绝时，最终状态与 CLI 一致。

## 环境与数据

**起环境**：

- `docker compose up -d`，容器 `skillmaster-postgres`（`compose.yaml`）。
- 服务端测试要三件：`SKILLMASTER_TEST_DB_URL`、`SKILLMASTER_TEST_DB_USER`、`SKILLMASTER_TEST_DB_PASSWORD`（`skillmaster-server/src/test/resources/application-test.yml`）。每个工作区一个库；跑完 `./mvnw verify`。
- 控制台：`skillmaster-console/` 跑 `npm test`、`npm run type-check`。
- CLI：`skillmaster-cli/` 跑 `go test ./...`。
- 设计件：`design/` 跑 `npm run build:console`、`npm run type-check`；状态检查只跑改动的那两组（`--only notifications`、`--only skill-settings`），整套要所有者批准。

**造数据**：

- 四个账号：`owner`（所有者）、`admin1`（`admin`）、`maintain1`（`maintain`）、`invitee`（被邀请 / 申请人）。用注册接口或测试夹具造。
- 两个 skill：`owner/private-skill`（私有）、`owner/public-skill`（公开 `browse`）。用 `POST /api/v1/author/skills` + 提交一版，或测试夹具直接落库。
- 授权：`admin1 → admin`、`maintain1 → maintain`，走 `skill_grant` 夹具或批准申请。
- 过期：时间戳是 RFC3339 TEXT，直接 `UPDATE skill_invitation SET expires_at = ...` 与 `UPDATE notification SET expires_at = ...`，不需要改系统时钟。
- 广播：测试夹具直接插一条 `notification(kind='announcement')` 与两行 `notification_recipient`；本版没有发布入口。
- 清理：每个用例用自己的 schema 或跑前清空 `notification`、`notification_recipient`、`skill_invitation`、`skill_access_request`、`skill_grant`；不要复用上一条用例的残留。

**全量回归**：默认只跑改动面；整套回归由所有者指令触发（`.dev-sop/audit.md` §豁免）。

## 结果

**部分执行。** 服务端与前端各自的自动化套件全绿；界面观感与 CLI 的真实端到端仍待人验收。

### 已执行

- **服务端**：`./mvnw verify`（测试库 `skillmaster_test`）——集成用例 564 个（failsafe）与单元用例 448 个（surefire）全部通过。本版的 `NotificationsIT` 覆盖 T1、T4、T5、T6、T7、T10、T11、T14、T15 的接口层，并补了接受旧邀请不降级、接受已删除 skill 的邀请、过期拒绝、owner 不可被邀请、降档直接生效与升档需邀请、超大页号；`SkillSharingIT` 覆盖邀请—接受、撤回与角色变更；五档权限矩阵见 `SkillRungsIT`；`AccessRequestApiIT`、`WebSkillMetadataIT` 等既有共享面用例已按邀请制改写并通过。
- **控制台**：`npm test`——666 个用例通过；`npm run type-check` 通过；`npm run build` 通过。`SkillCollaborators` 的用例覆盖邀请发出；`NoticeRow` 的用例覆盖 `viewable`、结果时间与公告不画结果章；待接受 / 已过期分组、邀请撤回、铃铛与通知页仍靠 T2 / T15 / T17 / T18 验收。
- **CLI**：`go test ./...` 通过（含命令树、声明、退出码与共享面协议用例）。`notifications_test.go` 覆盖每行给出的下一步命令、`approve` 不带 `--yes` 只打印不执行、以及 `accept` 回显档位。`skmr notifications` 的命令树、帮助与 JSON 契约已注册并通过静态检查。
- **设计件**（第 2 关，非本版功能验收）：`--only notifications`、`--only skill-settings` 全绿；`npm run type-check`、`npm run build:console` 通过。

### 未执行

- **T17 / T18 的视觉验收**：需要人看真实控制台——铃铛的点击展开、Esc 焦点、400px 直接进页、长地址两行截断、整行可点与行内按钮不冒泡。自动化只能证明结构与状态，证明不了“看起来对”。
- **T3 / T19 的 CLI 真实端到端**：命令已实现，命令层自动化通过；尚未在真实服务端上按本文件的步骤跑 `accept` / `decline` / `approve --yes` / `deny --yes`、`skill share → skill shares` 链路，以及只读密钥的 403。
- **T8、T9、T12、T13 的跨端一致性**：控制台与 CLI 共用服务端状态这一点由实现保证（同一张表、同一接口），但没有在两端各走一遍的自动化用例。

## 已知问题

无。设计件里的 skill 详情页只有一个 fixture（`dev/pdf-tools`），所以「查看」在演示件里落到 `?page=skill` 而不是通知里那个具体地址——这是演示件的替身，不是缺陷；产品里按 `/skills/<namespace>/<name>` 落。
