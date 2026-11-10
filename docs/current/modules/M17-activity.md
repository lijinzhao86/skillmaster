# M17 · 行为明细

> **状态**：已实现（v58） · 拥有表与对外发布类型见 [`system.md`](../system.md) 的对应行（源是 `ModuleMap`）

## 职责

记已登录用户的**行为明细**——搜索（带返回列表与总命中数）、调用、读取、下载、登录——并按「skill × 人 × 天」汇总所有者要的两个量：调用次数与提交版本数。它回答「我自己做过什么」与「这条 skill 被谁用了多少」，不回答「这个 skill 是什么」。**v60 起所有者页签的区间数改读 `activity_event` / `audit_event` 原始行**（本地自然日、含今天）；`skill_collaborator_day` 与每日任务保留，作为 1 年内部趋势，不再是该页的读路径。

## 边界

- **不判定可见性**：谁能看哪一层由 `usecase/ReadSkillUsageUseCase` 按 namespace 所有者与档位判定；M17 只按 `actor_user_id` 或 `skill_id` 返回值。
- **不拥有 skill / version**：`skill_collaborator_day` 的 `skill_id` 外键指向 M12，只留计数（`invokes` / `versions` / `last_at`）；`activity_event` 行里的 `skill_address` / `version_label` 是写入时的快照，改名后历史仍读得懂。
- **不重复 M15**：公开面的累计调用与账号分桶仍由 M15 提供；M17 的聚合只为所有者与本人明细。
- **写入是 best-effort**：业务请求结束时在独立事务里写，失败只丢一行、不改变响应；失败会打一条限流的 WARN（不是完全静默）。
- **写入类事件不进来**：新建、提交、发布、共享、凭据等由 `audit_event` 记录；M17 只收检索、使用与会话。

## 不变量

1. **明细只追加**：`activity_event` 没有主键，唯一的 UPDATE 是翻页并入（把第 2 页结果追加到同一条搜索的 `detail`）。
2. **按周分区、90 天在线**：`at` 是定长 RFC3339 UTC 字符串，按周一 00:00Z 切周；启动与每日任务保证本周 + 未来两周存在。
3. **关联只向前看**：同账号、只找 `search`、搜索时间在 `[调用 − 30 分钟, 调用]`、该 skill 在并入后的结果集里，取最近一条；否则是直接调用。
4. **一次查询一行**：第 2 页及以后并入首条搜索（同账号、同查询词、同筛选、30 分钟内；两个平面都比对筛选，API 游标请求要重发筛选），结果去重追加、最多 50 条。
5. **本人只读自己的行**：所有读接口都从凭据取账号；所有者聚合对非所有者 404。
6. **保留期分层**：明细 90 天，90 天前的整周分区导出 NDJSON.gz、核对行数后 `DROP`；日汇总保留 1 年。
7. **注销不删日志**：账号注销只删/去标识化账号资料与个人命名空间内容；`activity_event` 与 `skill_collaborator_day` 作为内部安全、审计与分析记录按保留期保存（2026-11-10 用户定），其中的账号 id 指向一行去标识化的账号记录。本人命名空间里的 skill 被删除时，指向它的日汇总行随 M12 的外键级联删除；明细没有外键，全留。

## 对外契约

`ActivityRecorder`（写入）、`UsageHistory`（本人明细 / 统计 / 所有者 / 按版本），以及事件记录 `ActivityEvent` / `SearchActivity` / `InvokeActivity` / `SearchHit` / `ActivityKinds` 与 `ArchiveSettings`。

## 为什么这样切

行为明细是高频、可丢失的消费记录，`audit_event` 是低频、必须与业务同事务的写入记录——把两者塞进一张表会同时破坏「失败不影响读」与「写入必留痕」这两个性质（[ADR 0179](../../decisions/0179-personal-usage-is-visible-only-to-its-owner.md)）。把它们从公域度量里独立出来，是因为 M15 只回答「有多少人用」，不认识「谁在何时做了什么」；可见性又是产品规则，不属于存储模块（[ADR 0153](../../decisions/0153-usage-metrics-is-its-own-module.md)）。
