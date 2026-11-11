# v60-usage-filters · 技术设计

> **最后更新**：2026-11-11
> **状态**：已实现（循环审计收口；待合并）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文只链接、不复述理由。
> **界面规格**：[`design/README.md`](../../../design/README.md) §使用记录、§使用记录的筛选、§详情的「使用」页签。
> **CLI 输出格式**：[`prd.md`](prd.md) §CLI 的格式——本文只引用，不重抄。

## 架构

这一版**不新建模块、不新建表**：M17 的读边多一个「窗口」，所有查询从「最近 90 天」变成「一个窗口」，两端只是把本地日期换成窗口的两个时刻再发请求。

```
控制台 /usage ─────┐                          ┌─ 浏览器：本地日期 → since / until ─┐
CLI skmr usage ────┼─► /web/usage、/api/v1/usage ─► api.UsageWindows.parse ───────┤
skill「使用」页签 ─┘                          └─ 校验、补默认、算 window_days ─────┘
                                                        │
                                                        ▼
                                              M17 UsageHistory（读边）
                                        ┌───────────────┼────────────────┐
                                  interactions / stats   mine        ownerUsage
                                  （本人明细与统计）   （本人 × skill）  （所有者）
                                                                        │
                                              ┌─────────────────────────┴────────┐
                                              ▼                                  ▼
                                     私有：activity_event（调用）        公开：activity_event 聚合
                                           audit_event（提交）                 → floorOf 分桶
```

- **窗口在服务端只是一对时刻。** 只有客户端知道用户所在的时区，所以「本地自然日、含首尾」在控制台 / CLI 里换算：`since` = 开始日本地 00:00，`until` = 结束日次日本地 00:00（不含）。M17 不解析日期、不认得「近 7 天」这种预设名。
- **明细与统计仍由服务端算。** 控制台不再从明细在本地推统计——`/web/usage/stats` 与 `/web/usage/interactions` 收到同一组参数，服务端用同一段谓词。设计件里那张「同一份明细现算」的承诺，落地成「同一个窗口 + 同一个 skill 谓词」。
- **skill 过滤是地址关键词，不是 skill 身份。** 关键词可能命中多条地址；明细留下命中集合参与的交互，统计数的是命中集合自己的账（见 §接口「多命中的口径」）。一次搜索仍是一行，调用列照常列出它调用的全部 skill。
- **所有者页签从日汇总改读明细。** 区间视图要按本地自然日切、并且要包含今天，而 `skill_collaborator_day` 以 UTC 天为单位、只到昨天为止，两条都与区间视图冲突。所以协作者的调用与提交版本改读 `activity_event` 与 `audit_event` 的原始行（都带 `(skill, at)` 索引）。日汇总与 `ActivitySummaryJob` **保留**——它是 [ADR 0179](../../decisions/0179-personal-usage-is-visible-only-to-its-owner.md) 决定 4 的 1 年汇总量，继续供内部趋势使用，只是不再是这个页面的读路径。
- **平台信号不动。** 结果行与 skill 页头的累计调用、`skmr skill show` 的近 30 天账号仍走 M15 的 `UsageQuery.snapshots`（[ADR 0152](../../decisions/0152-cumulative-calls-are-their-own-signal.md) / [ADR 0149](../../decisions/0149-use-is-counted-per-account-per-day.md)）；使用页签里的公开 skill 聚合才是新的区间数，两者各有名字（[ADR 0182](../../decisions/0182-owner-usage-is-a-period-view.md)）。

## 数据模型

**没有新表。** 只加一条部分索引：所有者的「提交版本」查询由 skill 起头，现有 `audit_event` 只有 `at` 索引。它用**普通 `CREATE INDEX`**，与既有迁移同一做法；Flyway 在启动时执行，`audit_event` 行数大时这一次部署会持 SHARE 锁，上线前按当时的行数评估窗口（改用 `CONCURRENTLY` 需要一条非事务迁移，本版不引入）。

```sql
-- V48：一个 skill 在一段时间里的提交。不加的话，每次打开所有者的「使用」页签都要扫
-- 这段时间内全平台的提交，再按 skill 过滤。
CREATE INDEX audit_event_submit_skill_at
  ON audit_event (target_id, at DESC)
  WHERE action = 'submit' AND target_type = 'skill';
```

**已有的读形状**（表与列的全貌见 [`docs/current/schema.md`](../../current/schema.md)）：

| 查询 | 用的索引 | 说明 |
|---|---|---|
| 本人明细与统计 | `activity_event_actor_at` | `(actor_user_id, at DESC, id DESC) INCLUDE (kind, skill_id)`，窗口两端都进入 `at` 的比较 |
| skill 关键词检查搜索结果 | 同上 | `detail` 是 TEXT JSON；只在这一账号的窗口内展开，行数由账号自己的日志决定 |
| 本人 × 一条 skill | `activity_event_skill_at` | `(skill_id, at DESC, id DESC)` |
| 所有者看到的一次调用 | 同上 | 私有与公开都从这里聚合 |
| 所有者的提交版本 | 新的 `audit_event_submit_skill_at` | — |
| 日汇总（保留，不再由页面读） | `skill_collaborator_day_skill` | [ADR 0179](../../decisions/0179-personal-usage-is-visible-only-to-its-owner.md) 决定 4 的 1 年汇总量 |

**时间仍是 RFC3339 UTC 字符串**（`Timestamps.format`，截到秒）。窗口用半开区间 `at >= since AND at < until`——闭区间会让「结束日是今天」在次日的同一秒出现两条边界，半开只出现一次。

## 接口

控制台与 CLI 走同一组参数；两个平面（`/api/v1/usage` 与 `/web/usage`）形状相同，鉴权规则不变。

| 方法 | 路径 | 新增入参 | 作用 |
|---|---|---|---|
| `GET` | `/api/v1/usage/interactions`、`/web/usage/interactions` | `since`、`until`、`skill` | 本人明细一页 |
| `GET` | `/api/v1/usage/stats`、`/web/usage/stats` | `since`、`until`、`skill` | 本人统计 |
| `GET` | `/api/v1/usage/skills/{ns}/{name}` | `since`、`until` | 所有者聚合 + 使用者表 |
| `GET` | `/api/v1/usage/skills/{ns}/{name}/users` | `since`、`until` | 只要使用者表 |
| `GET` | `/api/v1/usage/skills/{ns}/{name}/mine` | `since`、`until` | 本人 × 这条 skill |

**入参语义**：

- `since` / `until`：RFC3339 时刻，**必须成对**；`until` 不含。都不给 → 沿用 v58 的最近 90 天（旧版 CLI 不改也能用）；只给一个 → 400。
- `skill`：地址关键词，大小写不敏感、包含匹配；空串等于没给；最多 100 个 code point，超出 400。只在 `interactions` 与 `stats` 上读取，其它端点给了也忽略。控制台在输入处截到同一上限，不会把这个 400 发出去。
- `until` 最多可以比当前时刻晚 26 小时：客户端把「今天」的结束日换算成本地次日 00:00，这个时刻本来就在未来；再远就 400。查询用 `at < until`，未来的行不可能存在。
- 拒绝：`since` 晚于当前时刻、`until <= since`、跨度超过 90 天 + 2 小时、`since` 早于 90 天 + 2 小时前（时区余量），一律 400 `invalid_request`，消息指出是哪一条。

**出参新增**（旧客户端忽略未知字段即可）：

| 字段 | 出现在 | 值 |
|---|---|---|
| `window_days` | 全部五个接口 | 本次实际覆盖的自然日数：`max(1, round((until − since) / 1 天))`；默认窗口仍是 90 |
| `since` / `until` | 全部五个接口 | 服务端实际使用的两个时刻（`until` 已校验不晚于当前时刻 26 小时） |
| `skill` | 只有 `interactions` / `stats` | 本次的过滤词；没过滤时 `null` |
| `public` | 只有 `/users` | 这条 skill 是否公开；公开时只回聚合，`users` 为空数组 |
| `has_any` | 只有 `interactions` | 这个账号在保留窗口内有没有任意一条**搜索或调用**（登录与文件读取不算；不看本次的时间与 skill 筛选）；空态据此区分「没有记录」与「筛掉了」 |

`/users` 的 `public` 与 `skills/{ns}/{name}` 里已有的同名字段同义。公开 skill 的 `totals.last_used` 与私有视图同形，是区间内最近一次调用的 RFC3339 时刻；日期由客户端按展示时区渲染——服务端截 UTC 日期会让本地凌晨的调用显示成前一天。

`skills/{ns}/{name}` 本来就有 `skill` 字段（skill 地址），不重复放过滤词；`skill` 过滤词只在 `interactions` / `stats` 上读取，其它端点给了也忽略（不报错，因为它们不接受这个参数）。

**多命中的口径**：关键词命中多条地址时，统计按**命中集合**算——调用数是这些 skill 被调用的次数，搜索数是结果里出现过其中任意一条的搜索数，转化是其中调用了任意一条的搜索数，未调用是两者之差，「使用过的 skill」是被调用过的命中 skill 数，「最近使用」是命中集合里最近一次调用，「按调用次数」逐行列命中集合里的 skill。不把关键词当身份，也不取「最长匹配」那一条——那会让同一个关键词在不同时刻数到不同的 skill。

**公开 skill 的所有者**：`totals.calls` / `totals.accounts` 是区间内的聚合，离开 M17 之前按 `UsageSnapshot.floorOf` 分桶；`totals.joins` 仍是累计入库；`totals.last_used` 是区间内最近一次调用。平台信号不在这几个字段里。

**错误形状**沿用现有 API：400 `invalid_request`（参数不合法）、404（所有者的路径对非所有者与不存在的 skill 一样）、401（没有凭据）。`skill` 过滤不改变可见性——它只缩小本人已经能看到的行，不泄露别人的记录。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 服务端 | `UsageWindows` 解析与校验；`UsageHistory` 的方法改收窗口；明细 / 统计 / `mine` 的窗口谓词与 skill 谓词；调用列表也按窗口过滤；所有者的协作者改读原始事件；公开区间聚合与分桶；`audit_event` 新索引 | A1 / A2 / A6 的接口一半、A3、A7、A12、A13、A14 的接口一半 |
| P1 控制台 | 页头工具栏（skill 搜索 + 时间按钮）；地址参数；明细筛选与「搜到过」；统计吃同一组参数；skill「使用」页签的视图开关 + 时间按钮与区间重算；空态两种；`styles-usage.css` 搬设计稿 | A1、A2 / A6 的页面一半、A3、A4、A8、A9、A10、A13 的页面一半、A15 |
| P2 CLI | `--days` / `--since` / `--until` / `--skill`；四个路径与 `--json` 都带参数；输出区间、帮助与报错 | A2 / A6 的 CLI 一半、A5、A11 |
| P3 视觉修正（已提前落地） | 线上统计卡距页签 20px；明细折角按钮的 padding 与悬停色 | A15 |

**这一版明确不做**：不做多选 skill、不做保存的筛选、不做 90 天以前的区间、不改采集点与平台信号、不把日汇总改成按本地日（时区不可知）；P2 不加新命令，也不加确认手势——这几个都是查询参数，不是危险操作。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | `detail` 里出现损坏的 JSON 时，带 skill 过滤的明细会不会 500？ | 用 `detail IS JSON` 守住，再在 `CASE` 里展开；损坏的行只是不命中，不会报错 | 造一行非法 JSON，跑明细与统计，断言 200 且该行不因 skill 命中出现 |
| Q2 | 所有者改读原始事件后，一条高使用量的私有 skill 还能不能守住 p95 < 300 ms？ | 能：`(skill_id, at)` 索引把扫描限制在这条 skill 的窗口内，默认 30 天 | `EXPLAIN (ANALYZE)` 一条 10 万行窗口，记录真实耗时；超了就再评估 |
| Q3 | 公开 skill 的区间聚合（`count(*)` + `count(DISTINCT actor)`）在热门 skill 上会不会太慢？ | 先按原始事件做；公开面本来就只给分桶，结果不受精确值影响 | 同一张 10 万行窗口的 `EXPLAIN`；超 300 ms 时改用日汇总（代价是 UTC 天粒度，分桶后看不出来） |
| Q4 | 搜索结果里的地址匹配要不要改成「先按关键词查出 skill id，再比 `detail.skill_ids`」？ | 不要：现在只在本人窗口内展开，行数很小；先比地址少一次 skill 表查询 | 用 Q1 的同一条查询计时；行数过万再改 |

## 附录

### 附录 A · 与现有代码的关系

| 位置 | 动作 |
|---|---|
| `modules/activity/UsageHistory.java` | 方法改收 `Window(since, until, skill)`；`WINDOW_DAYS` 仍是默认值，服务端上限是 `WINDOW_DAYS + 2 小时`（时区余量） |
| `modules/activity/internal/ActivityRepository.java` | 加窗口与 skill 谓词；`userDays` 换成精确的 `collaboratorDays`；加 `skillRange`（公开聚合）；日汇总的读写方法保留（任务用） |
| `usecase/ReadSkillUsageUseCase.java` | 收窗口；私有协作者改读原始事件与 `AuditCounts.submissionsOfSkill`；公开聚合改读 `skillRange` 再分桶 |
| `modules/audit/AuditCounts.java` + `PgAuditLog` | 加 `submissionsOfSkill(skillId, since, until)` |
| `api/UsageWindows.java`（新） | 两端共用的参数解析与校验；只做 Web 层的事，不放进 M17 |
| `api/UsageController`、`api/WebUsageController` | 五个端点收 `since/until`，其中 `interactions` / `stats` 另收 `skill`；出参都有 `window_days` / `since` / `until`，`skill` 过滤词只在 `interactions` / `stats` |
| `skillmaster-console/src/api/usage.ts` | 每个方法带窗口与关键词；类型补回显字段 |
| `skillmaster-console/src/pages/UsagePage.tsx` | 工具栏、地址参数、筛选与空态；统计不再本地算 |
| `skillmaster-console/src/components/usage/UsageRange.tsx`（新） | 从 `design/src/console/components/UsageRange.tsx` 搬；只改「挪进框架」那一层 |
| `skillmaster-console/src/components/usage/UsagePane.tsx` | 视图开关与时间按钮同一行；数卡、使用者表、按版本表按区间重算 |
| `skillmaster-console/src/styles-usage.css` | 搬 `.usage-tools` / `.usage-range*` / `.usage-hit`；保留 v60 已提前落地的 20px 间距与折角修正 |
| `skillmaster-cli/cmd/skillmaster/usage.go`、`internal/api/usage.go`、`declarations_usage.go` | 按 [`prd.md`](prd.md) §CLI 的格式实现 |
| `skill_collaborator_day`、`ActivitySummaryJob` | **保留**，不再是页面的读路径（原因见 §架构，保留依据是 [ADR 0179](../../decisions/0179-personal-usage-is-visible-only-to-its-owner.md) 决定 4） |

### 附录 B · 来源与出处

- 一手：`design/README.md` §使用记录、§使用记录的筛选、§详情的「使用」页签；`design/src/console/components/UsageRange.tsx`；`design/src/console/fixtures/usage.ts`。
- 一手：服务端 `UsageHistory` / `ActivityRepository` / `ReadSkillUsageUseCase` / `UsageController` / `WebUsageController` 的当前源码；迁移 `V45__activity_event.sql`。
- 决策：[ADR 0182](../../decisions/0182-owner-usage-is-a-period-view.md)、[ADR 0179](../../decisions/0179-personal-usage-is-visible-only-to-its-owner.md)、[ADR 0149](../../decisions/0149-use-is-counted-per-account-per-day.md)、[ADR 0152](../../decisions/0152-cumulative-calls-are-their-own-signal.md)、[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)。
- 二手（只作参照、不参与实现）：浏览器原生日期控件的交互限制来自 `design/README.md` 记录的实测；默认 30 天与「按区间看采用」的判断，见 [`prd.md`](prd.md) §范围与 §用户与场景。

### 附录 C · 已测量的数据

| 指标 | **口径**（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| 线上统计卡与页签的间距 | 线上打包 CSS + 1440×900 无头渲染，量 `#usageStats.top − .list-tabs.bottom` | 修前 **0 px**，修后 **20 px** |
| 线上折角按钮 | 同上，深色模式悬停后量按钮矩形与底色 | 修前 **32×28**、`padding:9.6px 16px`、悬停 `rgb(139,186,255)`；修后 **28×28**、`padding:0`、悬停 `rgb(48,54,61)` |
| 所有者区间查询 p95 | 单 skill、30 天窗口、10 万条调用；`EXPLAIN (ANALYZE)` | **未测**（本机未造该规模数据；测试方案 T21） |
| 本人明细 p95 | 单账号、30 天、一页 50 条；`EXPLAIN (ANALYZE)` | **未测**（同上；T20） |
