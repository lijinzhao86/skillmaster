# M14 · 通知

> **状态**：已实现（v45） · 拥有表与对外发布类型见 [`system.md`](../system.md) 的对应行（源是 `ModuleMap`）

## 职责

一条事件 + 每个收件人的已读状态：写入定向或广播通知、翻页、算未读、标记已读、按 `subject_key` 落结果快照。请求型通知只是业务对象的指针；消息型通知只有标题、正文与已读。

## 边界

- **不读任何业务表**：邀请、申请、skill 的状态由 M12 回答；收件人 id、skill id、档位由用例传进来。
- **不解释 kind 的业务含义**：`kind` 是 TEXT，已知四个值在 `NotificationKinds`；feed 的「哪些 kind 算待处理 / 受管理权过滤」目前写在查询里，新增请求型 kind 要同步改这一处（[v45 TD](../../versions/v45-notifications/technical-design.md) §架构）。
- **不做外部投递**：没有 outbox、队列、SSE / WebSocket；写入与业务写在同一个事务里。邮件 / 推送到来时才在表旁加 outbox。

## 不变量

1. **已读与已处理正交**：`read_at` 只影响未读数；业务对象是否还能处理由邀请行、申请行回答。
2. **同一业务对象同一 kind 最多一条未收口通知**：部分唯一索引 `(subject_key, kind) WHERE resolved_at IS NULL AND subject_key IS NOT NULL`；重复共享 / 申请是 upsert。
3. **收口时写结果快照**：`outcome` 与 `resolved_at` 记的是已经发生的事，不是第二份当前状态（[ADR 0139](../../decisions/0139-a-notification-points-at-a-business-object.md)）。
4. **广播只存一份正文**：一条 `notification` + 每个收件人一行 `notification_recipient`。

## 对外契约

`NotificationService`（`emit` / `resolve` / `findFor` / `page` / `unread` / `markRead` / `markAll`）、`NotificationDraft`、`NotificationQuery`、`NotificationPage`、`NotificationOutcome`、`NotificationKinds`、`NotificationNotFound` / `NotificationStateException`。

## 为什么这样切

通知是底层能力，不是共享的一个功能：任何业务流只在自己的用例里构造一个 draft，M14 一行不改（[ADR 0139](../../decisions/0139-a-notification-points-at-a-business-object.md)）。M12 与 M14 互不 import，编排在 `usecase/`；这样邀请、申请与将来的组织邀请、账单提醒可以共用同一张表，而通知不会长出业务规则。
