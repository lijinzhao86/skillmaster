# v45-notifications · 技术设计

> **最后更新**：2026-11-04
> **所属版本**：[`README.md`](README.md)
> **决策**：[ADR 0138](../../decisions/0138-sharing-is-an-invitation.md)（共享是邀请）、[ADR 0139](../../decisions/0139-a-notification-points-at-a-business-object.md)（通知是业务对象的指针）、[ADR 0140](../../decisions/0140-cli-can-approve-access-requests.md)（CLI 可批准 / 拒绝申请）

## 架构

### 与 `docs/current/` 的差异

这一版新增一个模块与三张表，其他现状（模块切分、依赖规则、资源身份）不变。

| 新增 | 位置 | 拥有什么 |
|---|---|---|
| **M14 `notification`** | `com.skillmasterai.modules.notification` | `notification`、`notification_recipient` |
| M12 的新表 | `com.skillmasterai.modules.skill` | `skill_invitation` |
| 顶层用例 | `com.skillmasterai.usecase` | 邀请的接受 / 拒绝 / 撤销；通知的列表 / 单条 / 已读；已生效授权的降档；改造共享、申请、批准、撤回四条既有用例 |

M12 的表集合与 M14 的登记都改 [`ModuleMap`](../../../skillmaster-server/src/main/java/com/skillmasterai/common/ModuleMap.java)：M12 的 `Set.of(...)` 加 `skill_invitation`，`MODULES` 加 M14 一行，`PUBLISHED_TYPES` 加两张表对应的公开类型。`TableOwnershipTest` 从迁移里收表对账，没有第二处清单。

### 模块边界

**M12 与 M14 互不 import。** 编排在 `usecase/`：

- **M12（skill）** 回答「这个邀请 / 申请还能不能处理」「谁能处理」「这个人现在能不能读这个 skill」。新增 `SkillInvitationService`（公开 seam），并在 `SkillAccessService` / `SkillMetadataService` 上补三个批量读：`adminUserIds(skillId, ownerId)`、`administeredSkillIds(Caller)`、`readableAmong(Caller, skillIds)`。**所有者 id 由用例从 M4 取**——`namespace.owner_user_id` 是 M4 的表，M12 不能读；M12 只负责把「所有者 + 每个 `admin` 档」合成一个收件人集合。`Caller` 里已经带着调用者的个人命名空间 id，所以后两个读不需要用例再传一次。
- **M14（notification）** 只读写自己的两张表：写入一条通知 + 若干收件人行、翻页、算未读、标记已读、按 `subject_key` 落结果。它**不读任何业务表**，也不知道邀请、申请、skill 的业务含义——收件人 id、skill id、档位都由用例传进来。**但 feed 的两种请求型 kind（`share_invitation` / `access_request`）目前写在查询里**（「待处理」的 kind 列表与「申请通知只对管理档可见」的过滤）；新增请求型 kind 要同步改这一处，消息型与新的展示面则不必。
- **`ShareSkillUseCase` / `RequestSkillAccessUseCase` / `DecideSkillAccessRequestUseCase` / `WithdrawSkillAccessRequestUseCase`** 同时依赖 M12 与 M14：先让 M12 写下业务对象，再让 M14 发通知；业务对象收口时，同一个事务里让 M14 落结果。

这条边界与 [ADR 0139](../../decisions/0139-a-notification-points-at-a-business-object.md) §后果 是同一句话：通知不反向读业务表，所以没有环；读的时候要的当前权限由用例向 M12 要一次，再传给 M14 的查询。

### 为什么它是底层能力，而不是「共享的一个功能」

**M14 只认一份 draft，不认业务词。** 公开 seam 是通用的 `emit(NotificationDraft)` 与 `resolve(subjectKey, kind, outcome)`：

```
NotificationDraft(kind, subjectKey?, actorId?, actorHandle?, skillId?,
                  role?, title?, body?, expiresAt?, recipients)
```

`emitInvitation` / `emitAccessRequest` / `emitResult` 是**用例里的私有 helper**（构造 draft），不是 M14 的契约。任何模块要发通知，只在自己的用例里构造一个 draft；M14 一行不改。

**`kind` 是 TEXT，不是闭集枚举。** M14 不校验 kind；已知的四个值列在 `NotificationKinds` 的常量里，客户端按 kind 给句子与动作。**消息型**新 kind 不加迁移、不改 M14——加的是那个业务用例和一个客户端的渲染分支；**请求型**新 kind 还要把它的「算待处理 / 按管理权过滤」规则加进 feed 查询（见上一条边界说明）。

**扩展点与它们各自的落点**：

| 要扩什么 | 怎么扩 | 本版有没有 |
|---|---|---|
| 新通知类型（如组织邀请、账单提醒） | 构造新的 draft + 客户端一句文案；`kind` 直接用新字符串（请求型还要把过滤规则加进 feed 查询） | 数据形状已支持；本版只有四个 kind |
| 新的「要人答」流程 | 业务对象自己持有状态；通知只存 `subject_key` 与结果快照 | 已支持（邀请、申请是前两个） |
| 邮件 / 推送 / 企业微信 | 在同一个事务里再写一行 outbox，由通道 worker 投递；`notification` 表不动 | **不做**；加通道时才建 outbox |
| 大受众广播 | 收件人从「发布时物化」改成「读时懒建已读行」；feed 查询变一处 | 本版物化（Q4） |
| 新展示面（移动端 / webhook） | 读同一个 feed API；不复制表 | 本版只有控制台与 CLI |
| 按类型 / 通道的偏好 | 新表 `notification_preference(user_id, kind, channel)`，feed 与投递读它 | **不做**（PRD 不做 #4） |
| 保留期 / 清理 | 按 kind 加 `retention` 配置，清理任务读它 | **不做**（PRD 不做 #5） |

**它独立，但不假装所有扩展都已存在。** 外部通道、偏好、清理是三块明确的未建能力；它们各自的位置已在这里写死，不需要回头改 `notification` 的语义。**「独立」指 M14 不依赖任何业务模块、任何业务模块也不依赖 M14；「可扩展」指新类型与新通道加在边缘，不加在表结构上。**

M14 公布的公开类型以 [`docs/current/system.md`](../../current/system.md) 为准——那张表由 `ModuleMap` 生成，不在这里另抄一份。

### 数据流

| 动作 | 谁写业务对象 | 谁写通知 | 事务边界 |
|---|---|---|---|
| A 共享给 B | `SkillInvitationService.invite` 写 `skill_invitation`（对方已有同级或更高授权时只回显，不写） | `emit(邀请 draft)` 写一条 `share_invitation` 给 B | 同一个 `@Transactional` 用例 |
| B 接受 | `SkillInvitationService.accept` 先删邀请行；档位取 `max(现有授权, 邀请档位)`，现有更高时不写 `skill_grant`；过期 / skill 已删除时不写 | `resolve(..., accepted / expired / revoked)`；三种都由接受用例收口 | 同上 |
| B 拒绝 / A 撤销 | `SkillInvitationService.decline` / `revoke` 删邀请行 | `resolve(..., declined / revoked)`；拒绝时邀请已过期则归档 `expired`，仍回 204 | 同上 |
| B 申请 | `SkillAccessService.request` upsert 申请行 | `emit(申请 draft)` 给所有者 + 每个 `admin`，收件人由 `SkillAccessService.adminUserIds(skillId, ownerId)` 给出（`ownerId` 来自 M4） | 同上 |
| A 批准 / 拒绝 | `SkillAccessService.decide` 写授权（或只删申请行） | `resolve(..., approved / rejected)` 给所有收件人；再 `emit(结果 draft)` 给 B | 同上 |
| B 撤回申请 | `SkillAccessService.withdraw` 删申请行 | `resolve(..., withdrawn)` | 同上 |
| A 给已生效协作者降档 | `SkillSharingService.changeRole` 直接写 `skill_grant`；只允许降到当前档以下 | 不发通知（没有新访问，也不需要对方答）；审计记 from/to | 同上 |
| 邀请过期 | 不改行；读取时按 `expires_at` 判定 | 读取时返回 `state=expired`；接受时用例 `resolve(..., expired)` 并删邀请行 | 接受那条是写事务 |
| 平台公告 | 无 | 一条 `notification` + 每个收件人一行 `notification_recipient`；本版没有发布入口，由测试夹具造 | — |

**没有 outbox、没有队列、没有外部投递。** 通知是同一数据库里的一行，与业务写同事务；[ADR 0139](../../decisions/0139-a-notification-points-at-a-business-object.md) §理由 已说明这一版为什么不预先造一套没有失败模式的机制。加邮件 / 推送时，那条通道才需要 outbox。

### 过期的判定

`skill_invitation.expires_at` 与通知上的 `expires_at` 在发出时一起写死（`created_at + 30 天`）。

- **读取**：`state` 为 `pending` 且 `expires_at <= now` 时，返回 `state=expired`；「待处理」的查询把这类行排除。**读取时过期的通知不改行，`resolved_at` 仍为 NULL**；展示层（控制台与 CLI）用 `expires_at` 作为结果时间，这样 PRD A14 的「结果与时间」对懒过期也成立。
- **接受**：`SkillInvitationService.accept` 先看 `expires_at`；过期则返回 `EXPIRED`，用例把通知落成 `expired`、删邀请行，接口回 409。
- **重新共享**：`invite` 发现旧行已过期时，先把旧通知落成 `expired`、删旧邀请行，再插新行与新通知（同一个事务）。
- **没有后台任务。** [PRD A15](prd.md) 的「读取时判定」就是这里的三条。

## 数据模型

### `notification`（M14）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | TEXT PK | `Ulid.generate()`，与 `my_tag_definition` 同一套 |
| `kind` | TEXT NOT NULL | `share_invitation` / `access_request` / `access_request_result` / `announcement` |
| `subject_key` | TEXT | `invitation:<skillId>:<inviteeId>` 或 `access_request:<skillId>:<requesterId>`；公告为 NULL |
| `actor_id` | TEXT REFERENCES app_user(id) ON DELETE SET NULL | 发起人；公告为 NULL |
| `actor_handle` | TEXT | 发起人当时的 handle，历史快照；公告为 NULL |
| `skill_id` | TEXT REFERENCES skill(id) ON DELETE CASCADE | 公告为 NULL |
| `role` | TEXT | 邀请给出的档位 / 申请要的档位 / 结果里的最终档位 |
| `outcome` | TEXT | NULL 或 `accepted` / `declined` / `expired` / `revoked` / `approved` / `rejected` / `withdrawn` |
| `expires_at` | TEXT | 仅 `share_invitation`；其余为 NULL |
| `title` / `body` | TEXT | 仅 `announcement` |
| `created_at` | TEXT NOT NULL | RFC3339 UTC |
| `resolved_at` | TEXT | 结果落下的时刻；NULL 表示还没收口 |

约束与索引：

- 部分唯一索引 `(subject_key, kind) WHERE resolved_at IS NULL AND subject_key IS NOT NULL`——同一业务对象同一类型最多一条未收口通知；重复共享 / 重复申请是 upsert，不是第二条。
- `idx_notification_created`：`(created_at DESC, id DESC)`，列表的时间倒序。
- `idx_notification_skill`：`(skill_id)`，按 skill 查历史（管理与排障）。

### `notification_recipient`（M14）

| 列 | 类型 | 说明 |
|---|---|---|
| `notification_id` | TEXT NOT NULL REFERENCES notification(id) ON DELETE CASCADE | |
| `user_id` | TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE | |
| `read_at` | TEXT | NULL = 未读 |
| `created_at` | TEXT NOT NULL | |

- 主键 `(notification_id, user_id)`；索引 `(user_id, read_at)` 服务未读数与「未读」筛选，索引 `(user_id)` 服务列表。
- **广播型公告**：一条 `notification` 行 + 每个收件人一行 `notification_recipient`；正文只存一份。本版没有发布面，测试夹具造两个收件人。

### `skill_invitation`（M12）

| 列 | 类型 | 说明 |
|---|---|---|
| `skill_id` | TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE | |
| `invitee_id` | TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE | |
| `role` | TEXT NOT NULL | 五档之一 |
| `invited_by` | TEXT NOT NULL REFERENCES app_user(id) | |
| `created_at` | TEXT NOT NULL | |
| `expires_at` | TEXT NOT NULL | `created_at + 30 天` |

- 主键 `(skill_id, invitee_id)`：一个人在一个 skill 上最多一条待接受邀请；再次共享是 upsert。
- 索引 `(invitee_id)`：`GET /invitations` 与「我收到的邀请」的将来路径。
- **没有 `accepted_at` / `status`**：行在 = 待接受；接受 / 拒绝 / 撤销删行；过期看 `expires_at`。与 [ADR 0052](../../decisions/0052-a-request-is-not-a-grant.md) 的 `skill_access_request` 同一形状。

### 迁移

`V34__notifications_and_invitations.sql`（`V33` 已被活动分支 `feat/cli-read-write-contract` 占用，合并前再核一次）：建三张表、索引、注释；`ModuleMap` 同步。`skill_access_request` 与 `skill_grant` 不动。

## 接口

### 契约形状

一条通知的响应：

```json
{
  "id": "01J...",
  "kind": "share_invitation",
  "state": "pending",
  "actor": "yuhan",
  "skill": { "namespace": "yuhan", "name": "api-client" },
  "role": "write",
  "title": null,
  "body": null,
  "created_at": "2026-11-04T09:12:00Z",
  "resolved_at": null,
  "expires_at": "2026-12-04T09:12:00Z",
  "read": false,
  "viewable": true
}
```

- `state`：`pending` / `accepted` / `declined` / `expired` / `revoked` / `approved` / `rejected` / `withdrawn` / `info`（公告）。
- `skill` 与 `actor` 在公告上为 `null`。
- `viewable`：调用者**此刻**能不能读那个 skill。邀请 / 结果行用它决定「查看」出不出现；申请通知只在调用者仍是 `admin` 时出现（查询时已过滤），所以为 `true`；公告为 `false`。
- 列表：`{ "items": [...], "total": 23, "unread": 3 }`；`p` 从 1 起，`per` 默认 10、最大 50（与「我的 skill」同一套分页）。
- `status=attention`（默认）：铃铛下拉与 CLI 的默认，返回「待处理或未读」按时间倒序的前 `per` 条；`unread` 仍按全量算。`status=pending` 只看要处理的，`status=all` 含历史。
- 一条邀请：`{ "handle": "yuhan", "role": "write", "created_at": "...", "expires_at": "...", "state": "pending" }`；`state` 由 `expires_at` 在读取时算出，取值 `pending` / `expired`。对方已有同级或更高授权时 `POST` 回 `200` 与 `{ "handle", "role", "already_granted": true }`，不产生邀请行。邀请列表回 `{ "items": [ ... ] }`，**含已过期行**。

### API 平面（CLI）

| 方法 | 路径 | 用途 | 状态码 |
|---|---|---|---|
| GET | `/api/v1/notifications?status=attention\|pending\|all&p=&per=` | 列表 | 200 |
| GET | `/api/v1/notifications/{id}` | 单条 | 200 / 404 |
| POST | `/api/v1/notifications/{id}/read` | 标为已读 | 204 / 404 |
| POST | `/api/v1/notifications/read-all` | 全部已读 | 200 `{ "marked": n }` |
| POST | `/api/v1/notifications/{id}/accept` | 接受共享邀请 | 200 `{ "skill": {...}, "role": "write" }` / 404 / 409 |
| POST | `/api/v1/notifications/{id}/decline` | 拒绝共享邀请 | 204 / 404 / 409 |
| POST | `/api/v1/notifications/{id}/approve` | 批准一条权限申请（body 可带 `role`，默认申请档位） | 200 `{ "skill": {...}, "role": "write" }` / 404 / 409 / 400 |
| POST | `/api/v1/notifications/{id}/deny` | 拒绝一条权限申请 | 204 / 404 / 409 |
| POST | `/api/v1/skills/{ns}/{name}/invitations` | 发出邀请 | 201 `{ "handle", "role", "expires_at" }` / 200 `{ "handle", "role", "already_granted": true }` |
| GET | `/api/v1/skills/{ns}/{name}/invitations` | 列出邀请（含已过期） | 200 |
| DELETE | `/api/v1/skills/{ns}/{name}/invitations/{handle}` | 撤销邀请 | 204 |
| PUT | `/api/v1/skills/{ns}/{name}/grants/{handle}` | 改已生效授权的档位（只降不升） | 200 `{ "handle", "role", "changed" }` / 400 `raise_needs_invitation` / 404 |

- **两条「决定」路由按 kind 分开**：`accept` / `decline` 只对 `share_invitation`（别的 kind 回 `reason=not_an_invitation`），`approve` / `deny` 只对 `access_request`（别的 kind 回 `reason=not_an_access_request`），错误码都是 `409 INVALID_NOTIFICATION_STATE`。批准 / 拒绝调用 `DecideSkillAccessRequestUseCase` 的同一条路径；因为通知只带 `skill_id`、不带地址，M12 需要一个按 id 的决定 seam（`SkillAccessService` 的 `decideById`），网页原有的地址路由继续用同一条用例。
- **`POST /api/v1/skills/{ns}/{name}/grants` 移除**：它今天是「即时授权」，与新语义冲突。`GET /grants` 与 `DELETE /grants/{handle}` 保留，前者列已生效授权，后者撤销已生效授权。CLI 的 `skill unshare` 先调邀请撤销、再调授权撤销，两条都幂等。
- **scope**：`GET` 要 `skills:read`，`POST` / `DELETE` 要 `skills:write`（`SecurityConfig` 的按方法规则，见 [ADR 0073](../../decisions/0073-asking-for-access-splits-by-plane.md) §决定 3）。因此**只读密钥能列、能看，不能标已读、不能接受 / 拒绝**；CLI 帮助里先写清楚（与「申请是一笔写」同一种说明）。
- **错误**：`404 NOTIFICATION_NOT_FOUND`（不存在，或不是你的）；`409 INVALID_NOTIFICATION_STATE`，detail 里给 `reason`：`expired` / `already_resolved` / `not_an_invitation` / `not_an_access_request` / `revoked`（接受一条指向已删除 skill 的邀请）。改档路由升档回 `400 INVALID_REQUEST`，`issue=raise_needs_invitation`。两个通知错误码加进 `ErrorCode` 与生成的 `docs/current/errors.md`。
- **共享的老错误不变**：handle 不存在、分享给自己、档位不在五档内仍走 `AccountRequestException`。邀请目标若是该 skill 的所有者，回 `400 issue=invitee_is_owner`（与「分享给自己」区分：调用者是 admin 协作者时，对方是 owner，不是他自己）。改档路由无已生效授权回 `400 issue=not_shared`；升档回 `400 issue=raise_needs_invitation`。

### 网页平面（控制台）

与 API 平面同形，前缀 `/web`，会话 + CSRF：

| 方法 | 路径 |
|---|---|
| GET | `/web/notifications?status=attention\|pending\|all&p=&per=` |
| GET | `/web/notifications/unread-count` |
| GET | `/web/notifications/{id}` |
| POST | `/web/notifications/{id}/read` |
| POST | `/web/notifications/read-all` |
| POST | `/web/notifications/{id}/accept` |
| POST | `/web/notifications/{id}/decline` |
| POST | `/web/skills/{ns}/{name}/invitations` |
| GET | `/web/skills/{ns}/{name}/invitations` |
| DELETE | `/web/skills/{ns}/{name}/invitations/{handle}` |
| POST | `/web/skills/{ns}/{name}/grants/{handle}/role` |

- 控制台顶栏挂载时取一次 `unread-count`；每次通知动作之后刷新列表与计数；铃铛打开时取 `status=attention&per=5`。
- **网页平面没有 `approve` / `deny` 的通知路由**：批准 / 拒绝仍走协作者页原有的 `POST /web/skills/{ns}/{name}/access-requests/{handle}/approve|deny`，两边调用同一条用例（[ADR 0140](../../decisions/0140-cli-can-approve-access-requests.md) §决定 3）。
- 共享动作的返回从 `ShareResponse` 扩成 `InvitationResponse(handle, role, expiresAt, alreadyGranted)`；「共享给」那一句改成「邀请已发出，等待对方接受」。
- 协作者页同时读 `GET /grants` 与 `GET /invitations`，分三组渲染（已生效 / 待接受 / 已过期）；邀请与授权各自撤销（邀请行的「撤销」只删邀请，授权行的「撤销」只删授权，已过期行没有动作）。CLI 的 `skill unshare` 是组合动作：先删邀请、再删授权，两条都幂等。

### CLI

新增命令组 `notifications`（`declarations_notifications.go` + 分发）：

```bash
skmr notifications [list] [--status attention|pending|all] [--unread] [--page <n>] [--json]
skmr notifications show <id> [--json]
skmr notifications read <id> | --all
skmr notifications accept <id>
skmr notifications decline <id>
skmr notifications approve <id> [--role <档>] --yes
skmr notifications deny <id> --yes
```

- **默认 `--status attention`**（待处理 + 未读，与铃铛下拉同一份）；`pending` 只看要处理的；`all` 含历史。`--unread` 只在 `attention` / `all` 上进一步筛。
- **人读输出每条给一句 + 可复制的下一步命令**：

  | 通知 | 下一行 |
  |---|---|
  | 待接受共享邀请 | `skmr notifications accept <id>` / `decline <id>` |
  | 待处理申请 | `skmr notifications approve <id> --role <档> --yes` / `deny <id> --yes`；也可 `skmr skill open <地址> --tab settings --sub collaborators` 去网页 |
  | 申请已批准 / 邀请已接受 | `skmr skill invoke <地址>`，再给 `skmr skill open <地址>`（`viewable` 为 false 时不给） |
  | 未通过 | `skmr skill open <地址>`（与控制台「查看」一致；`viewable` 为 false 时不给） |
  | 已过期 / 已拒绝 / 已撤销 | 无动作，只留一行历史 |
  | 公告 | 本版无动作；将来有链接就打印 |

- **`approve` / `deny` 的 `--yes` 必填**：不带时只打印后果与带 `--yes` 的完整命令，不执行（与 `skmr skill delete` 同一个形状，[ADR 0056](../../decisions/0056-dangerous-actions-are-confirmed-in-a-modal.md)、[ADR 0140](../../decisions/0140-cli-can-approve-access-requests.md)）。`approve` 的 `--role` 默认是申请的那一档，可改。
- `--json` 在**有响应体**的命令里返回接口原样字段；`read` / `decline` / `deny` 的服务端是 204，CLI 回 `{id, …}` 的最小回执（字段见 `docs/current/cli.md`）。都不拼命令，agent 自己拼。
- `show` 只读，不自动标已读；显式标记走 `read <id>`。控制台的「查看 / 去处理」会自动标已读——这是界面行为，不是 CLI 的默认。
- **不做登录 / 启动提示**：`login` 与 `startup` 不报未读数；通知只在 `skmr notifications` 出现。2026-11-04 定：`login` 之后是去做任务，不是浏览。

改造既有命令：

- `skmr skill share <地址> --to <handle> [--role <档>]`：回执从「已共享」变成「已邀请，等待对方接受（30 天后过期）」，**并给出下一步 `skmr skill shares <地址>`**——A 的同一件事在待接受名单里，链路不断。目标改为 `POST .../invitations`。
- `skmr skill shares <地址>`：输出三组——「已生效」「待接受」「已过期」；JSON 形状加一个 `invitations` 数组（每项带 `state`）。**这里是 A 的管理视图（要 `admin`），没有接受 / 拒绝**；接受 / 拒绝是收件人 B 在 `notifications` 里的动作。
- `skmr skill role <地址> --user <handle> --role <档>`：**只降不升**。降档直接写 `skill_grant` 并记审计；升档服务端回 `raise_needs_invitation`，要用 `skill share` 发邀请。这是控制台协作者页改档位那一列的 CLI 对应物（ADR 0081）。
- `skmr skill unshare <地址> --user <handle>`：先 `DELETE .../invitations/{handle}`，再 `DELETE .../grants/{handle}`；回执说明删掉的是哪一种（与 `SkillSharingService.Revocation` 的 `removedRole` 同一种区分）。

## 分期

### P0 · 服务端（验收 A1、A4–A7、A9–A11、A14–A16、A18、A19 的服务端部分）

1. `V34` 迁移 + `ModuleMap`（M12 加表，M14 登记）。
2. M12：`skill_invitation` 的仓储与 `SkillInvitationService`；`SkillAccessService` 的 `adminUserIds(skillId, ownerId)`、`administeredSkillIds(Caller)` 与按 id 的 `decideById`；`SkillMetadataService` 的 `readableAmong(Caller, skillIds)`、`addressesOf(skillIds)` 与公开的 `SkillAddress`。
3. M14：`NotificationService`（emit / resolve / findFor / page / unread / markRead / markAll；`attention` 由 `NotificationQuery.Status.ATTENTION` 表达）+ 两张表的仓储。
4. 用例改造：`ShareSkillUseCase`（发邀请）、`RequestSkillAccessUseCase`（发申请通知）、`DecideSkillAccessRequestUseCase`（收口 + 结果回执）、`WithdrawSkillAccessRequestUseCase`（收口）；新增接受 / 拒绝 / 撤销邀请、通知列表 / 单条 / 已读 / 全部已读 / 未读计数。
5. API 平面与网页平面的路由、响应、错误码；`POST /grants` 下线；API 平面的 `approve` / `deny`（网页仍用原有的地址路由）。
6. 服务端测试：`./mvnw verify`。

### P1 · 网页（验收 A2、A8、A12、A13、A17、A18 的界面部分）

1. `design/src/console` 的组件搬进 `skillmaster-console/src`（`design/README.md` 的通知与协作者两节，不重写）：顶栏铃铛 + 下拉、通知页、`NoticeRow`、协作者页的待接受与已过期两组。
2. `src/api/notifications.ts`、`components/notifications/NotificationsBell.tsx`（含共享的未读数 store）、`pages/NotificationsPage.tsx`；`AppShell` 顶栏接入。
3. 共享对话框的文案与动作改成邀请；`SkillCollaborators` 分两组。
4. `npm test`、`npm run type-check`。

### P2 · CLI（验收 A3、A19 的 CLI 部分）

1. `skmr notifications` 命令组（含 `approve` / `deny` 与 `--yes`）与 API 客户端。
2. `skill share` / `skill shares` / `skill unshare` 的新语义与输出分组。
3. `go test ./...`。

## 开放问题

1. **Q1：`administeredSkillIds` 作为 `IN` / `ANY` 列表传给 M14，规模到多大要换？**
   当前倾向：先直接传（页面 10 条，通常管理的 skill 远少于 1000）；超过 1000 时改成「M14 存一份 `requires_admin` + 用例先算出可见 id 集」，或让 M14 读一个 M12 发布的视图。判据是单次查询的 `EXPLAIN` 与列表 P95，不是猜。
2. **Q2：只读密钥不能标已读、不能接受 / 拒绝、不能批准 / 拒绝申请，可以吗？**
   当前倾向：可以，按 `SecurityConfig` 的按方法规则，`POST` 一律要 `skills:write`；CLI 帮助与 README 先写清楚。若产品要求只读密钥也能标已读，那是一条新的 scope 规则，要另开 ADR，不在本版偷偷开按路由的例外。
3. **Q3：`actor_handle` 存快照还是读取时解析？**
   当前倾向：快照。handle 是账号在发出那一刻的称呼，历史行显示当时的称呼；`actor_id` 仍然保留，将来 handle 若可改，再决定是否按 id 解析当前值。
4. **Q4：公告的收件人行在用户量变大后怎么扇出？**
   当前倾向：发布时物化（一人一行）。本版没有发布面，测试夹具两个收件人；用户量到十万级时改成「正文一行 + 读时懒建已读行」，那是另一条 ADR。
5. **Q5：升级前已经待批的申请，要不要补通知？**
   当前倾向：不补。协作者页的数字标仍然指向它们，A 打开那个 skill 就能处理；补通知要回填「当时的 owner + admin」名单，而那份名单今天已经无法准确还原（授权可能已经变过）。若所有者要求补，只对「当前 owner」补一条，并在 PRD 里记。
6. **Q6：skill 硬删之后通知跟着级联删，历史就没了，可以吗？**
   当前倾向：可以。硬删是极少数；历史在 `audit_event`。软删不删通知（只写 `skill.deleted_at`），恢复后通知仍在。
7. **Q7：`viewable` 每页多一次批量读，值得吗？**
   当前倾向：值得。一页 10 个 skill，一次 `readableAmong`；它换掉的是「点进去 404」的体验。若 P95 明显变差，再把 `viewable` 降级成只在单条读时算。
