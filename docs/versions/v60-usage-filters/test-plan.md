# v60-usage-filters · 测试方案

> **最后更新**：2026-11-11
> **状态**：已实现（自动化用例已执行，结果见 §结果；待合并）
> **所属版本**：[`README.md`](README.md)
> **验收标准**：[`prd.md`](prd.md) §验收与指标
> **接口形状**：[`technical-design.md`](technical-design.md) §接口

## 验收映射

| 验收 | 用例 |
|---|---|
| A1 近 7 天：明细与统计同一区间 | T01、T02 |
| A2 自定义起止与非法输入 | T03、T04、T26 |
| A3 按 skill 关键词过滤明细 | T05、T06、T27 |
| A4 skill「使用」页签按区间重算 | T07、T08、T09、T28 |
| A5 控制台与 CLI 同一结果 | T10 |
| A6 非法参数：CLI / 接口 / 页面 | T04、T11、T12、T26 |
| A7 90 天边界与默认 30 天 | T13、T14 |
| A8 地址可分享 | T15 |
| A9 筛掉后的空态 | T16 |
| A10 两个页签共用同一组筛选 | T17 |
| A11 CLI 是第一前端 | T18、T19 |
| A12 查询性能 | T20、T21 |
| A13 按 skill 过滤后的统计口径 | T22、T23、T27 |
| A14 平台信号不变 | T24 |
| A15 线上两处视觉偏差 | T25 |

## 用例

### 时间窗口

**T01 · 近 7 天只留下 7 个本地自然日（含今天）**
- **前置**：账号 A；事件在本地 `2026-11-04 23:59`、`2026-11-05 00:00`、`2026-11-11 09:59`、`2026-11-12 00:00` 各一条；时钟钉在 `2026-11-11 10:00`（本地）。
- **步骤**：打开 `/usage?days=7`；再请求 `GET /web/usage/interactions?since=2026-11-05T00:00:00+08:00&until=2026-11-12T00:00:00+08:00`。
- **期望**：`11-05 00:00` 与 `11-11 09:59` 在内，`11-04 23:59` 与 `11-12 00:00` 不在；统计的调用、搜索、使用过的 skill、最近使用与按调用次数只数其中两条。

**T02 · 统计与明细读同一批行**
- **前置**：同 T01，另加一条「搜索出现了 X 但没调用」与一条「直接调用 X」。
- **步骤**：同一窗口分别取 `/interactions` 与 `/stats`。
- **期望**：`stats.calls` 等于明细里所有调用行的调用条数；`stats.searches` 等于搜索行数；`stats.converted + stats.missed = stats.searches`；`stats.skills` 等于调用行里不同 skill 地址的个数。

**T03 · 自定义起止含首尾**
- **前置**：本地 `2026-11-01 00:00`、`2026-11-10 23:59`、`2026-11-11 00:00` 各一条事件。
- **步骤**：`/usage?from=2026-11-01&to=2026-11-10`；接口 `since=2026-11-01T00:00:00+08:00&until=2026-11-11T00:00:00+08:00`。
- **期望**：前两条在，第三条不在；`window_days = 10`。

**T04 · 非法区间被拒绝**
- **前置**：无。
- **步骤**：分别请求 `until <= since`、`since` 早于 90 天 + 2 小时前、跨度超过 90 天 + 2 小时、只给 `since`、`since` 晚于当前时刻。
- **期望**：五次都是 400 `invalid_request`，消息分别指出「结束要晚于开始」「只能看最近 90 天」「跨度超过 90 天」「两个时刻要给齐」「since 不能晚于当前时刻」。

### skill 关键词

**T05 · 出现或调用都算命中**
- **前置**：账号 A 的三类行——搜索 `pdf` 返回 `demo/pdf-tools` 但没调用；搜索 `报告` 返回它并调用了它；直接调用它。另有一条与它无关的搜索。
- **步骤**：`/usage?skill=pdf`；接口 `skill=PDF`。
- **期望**：前三条都出现，最后一条不出现；第一条行上有「搜到过」，调用列写「未调用」；大小写不敏感；清空关键词后四条都回来。

**T06 · 第 51 名以后不参与判定**
- **前置**：一次搜索返回 60 条，`demo/only-51st` 只在第 51 名；明细只存前 50。
- **步骤**：`/usage?skill=only-51st`。
- **期望**：该搜索行不出现；统计的搜索次数为 0。这不是失败，是 PRD「不做」第 2 条的口径。

### skill「使用」页签

**T07 · 所有者两个视图都按区间**
- **前置**：skill S 有所有者 O、协作者 C；O 在 `10-01` 与 `11-10` 各调用一次，C 在 `11-10` 调用两次；C 在 `09-01` 与 `11-09` 各提交一版；clock 钉在 `11-11`。
- **步骤**：所有者打开 `skills/S?tab=usage&days=30`；切「我的使用」。
- **期望**：「全部使用」调用次数 3、使用账号数 2、最近使用 `11-10`；使用者表 O 调用 1、C 调用 2、C 提交 1；「累计入库」与区间无关；「我的使用」调用 1。

**T08 · 协作者的今天也算**
- **前置**：C 在今天调用 S 一次（日汇总要到明天才有）。
- **步骤**：所有者看 `days=1` 的「全部使用」。
- **期望**：调用次数包含 C 的这一次；使用账号数 ≥ 1；不出现「今天没有、明天才补上」的滞后。

**T09 · 公开 skill 的区间聚合与分桶**
- **前置**：公开 skill P，区间内 12 次调用、3 个账号；另有 30 天外的一次。
- **步骤**：所有者打开 P 的「使用」页签，选 30 天。
- **期望**：调用写 `10+`、账号写「少于 10 个账号」；30 天外那次不计入；「累计入库」照旧；页面上的平台信号（如果画了）仍是累计值；**没有「使用者」表，也没有逐人的调用 / 提交行**，只有聚合数卡与说明。

**T28 · 非所有者的「我的使用」也有时间筛选**
- **前置**：协作者 / 库内用户打开一个不是自己的 skill。
- **步骤**：看「使用」页签的控件；把区间改成近 7 天。
- **期望**：有时间按钮、没有「全部使用 / 我的使用」开关；数卡与按版本表按 7 天重算。

### 两端一致

**T10 · 同一组条件，控制台与 CLI 一致**
- **前置**：账号 A 有 20 条匹配 T05 的数据；CLI 与页面在同一时区；记运行当天为 `D`。
- **步骤**：页面 `/usage?from=<D-6>&to=<D>&skill=pdf` 记下 `total` 与首页 10 行；CLI `skmr usage --since <D-6> --until <D> --skill pdf --limit 10 --json`。
- **期望**：`total` 相等、首页 `items` 相同；`since` / `until` / `skill` / `window_days` 相同。两端都用显式日期，不依赖「今天是几号」，换一天跑也成立。

### 参数校验

**T11 · CLI 非法参数退出 2**
- **前置**：已登录。
- **步骤**：`--days 0`、`--days 91`、`--since 2026-11-10 --until 2026-11-01`、`--since 2026-08-01 --until 2026-08-10`、只给 `--since`、`--days 7 --since …`、地址加 `--skill`。
- **期望**：每一条都退出 2，stderr 指出是哪一条，不发起请求。

**T12 · 手工写坏的地址静默回退**
- **前置**：地址里手工写坏 `from=2026-13-01`。
- **步骤**：打开页面。
- **期望**：落回默认近 30 天；不出现错误横幅；页头与工具栏正常。这是地址回退规则，不是表单错误态。

**T26 · 表单里的非法日期**
- **前置**：页面已加载，已有 `days=7` 的筛选。
- **步骤**：打开时间浮层，把「开始」改成 `2026-09-31`（不存在的日期）；再改成晚于结束日的日期；再改成今天之前的第 91 天。
- **期望**：字段下方分别说明「这个日期不存在」「开始日期不能晚于结束日期」「只能选 …之后的日期」；「应用」不可点；浮层不关；关掉浮层后原有 7 天筛选仍在。
- **未来日期**：把结束日改成明天 → 「结束日期不能晚于今天」，同样不可应用。

### 边界与默认

**T13 · 默认 30 天（接口 90 天兼容）**
- **前置**：记运行当天为 `D`；`activity_event` 在 `D-100`、`D-40`、`D-1` 各一条。
- **步骤**：服务端以固定 `now` 调 `UsageWindows.parse(null, null, null, now)`（用例 `UsageWindowsTest`）；CLI 以固定 `now` 调 `usageWindowOf("", "", "", "", now)`（用例 `usage_range_test.go`）；页面打开 `/usage` 不带参数，读请求里的 `since` / `until` 并算出跨度（用例 `usage.test.tsx`，不需要钉系统时钟）。
- **期望**：接口不带参数返回 90 天窗口（`window_days=90`），含 `D-40` 与 `D-1`、不含 `D-100`；页面与 CLI 发的是 30 天窗口（`D-29` 本地 00:00 至次日 00:00），只看到 `D-1`。

**T14 · 90 天边界**
- **前置**：时钟固定；事件在 `today-89 00:00`（本地，最早可选）、`today-90 23:59`、`today-91 00:00`。
- **步骤**：页面 / CLI 选近 90 天；再请求 `since = today-89 本地 00:00` 的接口；最后请求 `since = today-91 本地 00:00`、`until = today-90 本地 00:00` 的接口。
- **期望**：预设窗口含 `today-89`、不含 `today-90`；更早的两条无论在哪个筛选下都不出现；服务端拒绝早于 90 天 + 2 小时的 `since`。

### 地址与空态

**T15 · 地址可分享**
- **前置**：有数据。
- **步骤**：打开 `/usage?days=7&skill=pdf&tab=stats&p=2`，刷新；把地址复制到新标签页。
- **期望**：控件显示「近 7 天」与关键词；统计页签选中；页签与页码不丢；两次结果一致。

**T16 · 筛掉后的空态**
- **前置**：账号有记录，但没有一条含 `zzz`。
- **步骤**：`/usage?days=7&skill=zzz`；再用一个 90 天内一条搜索或调用都没有的账号打开同一地址。
- **期望**：有记录但被筛掉 → 「没有匹配的使用记录。清除筛选」，点「清除筛选」回到默认 30 天且关键词清空，不是错误态、页头还在；90 天内一条搜索或调用都没有 → 「暂无使用记录」，即使地址里带着筛选也一样（`has_any` 压过筛选）。

**T17 · 两个页签不丢条件**
- **前置**：有数据。
- **步骤**：在明细设 `days=7&skill=pdf`，切到统计，再切回明细。
- **期望**：两个页签都显示同一组筛选下的数 / 行；尾部链接带着同一组参数。

### CLI

**T18 · 帮助列出四个参数**
- **前置**：无。
- **步骤**：`skmr usage --help`。
- **期望**：列出 `--days`、`--since`、`--until`、`--skill`，含取值说明与一个组合示例。

**T19 · 输出带区间**
- **前置**：有数据；记运行当天为 `D`。
- **步骤**：依次跑 `skmr usage --days 7`、`skmr usage --since <D-9> --until <D>`、`skmr usage --days 7 --skill pdf`、`skmr usage --stats --days 7`、`skmr usage <地址> --days 7`、`skmr usage <地址> --mine --days 7`、`skmr usage <地址> --users --days 7`。
- **期望**：前三条的尾行形如「近 7 天（… 至 …）」「… 至 …」「… · skill 地址含 “pdf”」；具体日期由 `usageWindowOf(…, now)` 的单测用固定 `now` 断言（`TestUsageWindowOfDays`、`TestUsageWindowOfCustomDaysIncludesBothEnds`），命令层断言标签与后缀出现在输出里。后四条的首行同样带区间（`--stats` 是首行，其余是「地址 + 区间」首行）；按 skill 过滤时搜索效果那句换主语。

### 性能

**T20 · 本人明细 p95**
- **前置**：单账号 30 天 10 万条事件，一页 50 条。
- **步骤**：`EXPLAIN (ANALYZE)` 明细与统计各跑 20 次。
- **期望**：p95 < 300 ms；计划里 `activity_event_actor_at` 是范围扫描，不是全表。

**T21 · 所有者区间 p95**
- **前置**：单 skill 30 天 10 万条调用。
- **步骤**：`EXPLAIN (ANALYZE)` 所有者聚合与协作者表各跑 20 次。
- **期望**：p95 < 300 ms；计划里 `activity_event_skill_at` 是范围扫描。

### 统计口径

**T22 · 按 skill 过滤后的统计**
- **前置**：T05 的三类行，另加一条搜索 `pdf` 并调用了别的 skill。
- **步骤**：`/stats?skill=pdf`。
- **期望**：搜索次数 = 出现 `pdf-tools` 的搜索数（含调用了别的 skill 的那次）；转化 = 其中调用了 `pdf-tools` 的搜索数；未调用 = 差；调用次数 = 调用 `pdf-tools` 的次数；最近使用 = 它最近一次被调用；未调用过时为 `—`。

**T23 · 关键词命中多条 skill**
- **前置**：`demo/pdf-tools` 与 `demo/pdf-merge` 都在窗口内被调用过。
- **步骤**：`/stats?skill=pdf`。
- **期望**：调用次数是两条之和；使用过的 skill 为 2；按调用次数列出两行；搜索次数是「结果里出现任意一条」的搜索数。

**T27 · 损坏的 `detail` 不会让 skill 过滤 500**（技术设计 Q1）
- **前置**：账号 A 有一行 `activity_event`，`detail` 是 `{not json` 或 `{"results": 123}`。
- **步骤**：`/interactions?skill=pdf` 与 `/stats?skill=pdf`。
- **期望**：都返回 200；损坏的行不因 skill 命中出现；页面显示正常，不出现失败横幅。

### 平台信号

**T24 · 平台信号不随区间变**
- **前置**：公开 skill P 有累计调用分桶与近 30 天账号分桶。
- **步骤**：结果行与 skill 页头记下累计调用，`skmr skill show` 记下近 30 天账号；在「使用」页签切换 7 / 30 / 90 天。
- **期望**：累计调用与近 30 天账号都不变；「使用」页签的区间数随区间变；公开页签的说明只说页头的累计调用是平台信号，近 30 天账号在 `skmr skill show`。

### 视觉

**T25 · 线上两处偏差**
- **前置**：用线上打包 CSS 复现（TD 附录 C 的测量口径）。
- **步骤**：1440×900 深色模式下打开统计与明细；悬停折角按钮。
- **期望**：数卡与页签分隔线相距 20 px；折角按钮 28×28、`padding:0`；悬停底色是 `--line`，不是主按钮蓝；浅色箭头看得见。**此外需要人看一眼线上或本地的渲染结果**——断言不等于视觉验收。

## 环境与数据

- **服务端**：Docker Postgres（`skillmaster-postgres`），本 worktree 的 `skillmaster_v60` / `skillmaster_test_v60`；`.env` 从主工作区复制。测试用 `./mvnw verify` 里的 `*IT`，每次重建 schema。
- **固定时钟**：服务端用例用显式 `at` 造行；`UsageWindows.parse` 有收 `now` 的重载，默认窗口与边界用它断言，不依赖运行当天。控制台用例用 URL 固定区间 + 断言请求里的 `since` / `until`；默认窗口的用例断言请求的跨度，不钉系统时钟。CLI 的日期换算在 `usageWindowOf(…, now)` 里单测，命令层用 `time.Now()`，需要同一窗口时显式给 `--since/--until`。
- **造数**：T01–T05、T16、T22–T23、T27 用 `activity_event` 的直接 INSERT（`kind` / `at` / `skill_address` / `detail` 与生产同形；T27 另造一行损坏的 `detail`）；T07–T08、T28 另建一条私有 skill、一个 grant 与 `audit_event` 的 `submit` 行；T09 用公开 skill 与窗口内外的调用；T26 不需要服务端数据。
- **清理**：测试库可抛弃，撞号就重建；不修 Flyway 历史。
- **控制台**：`tests/usage.test.tsx` 的 API mock 按参数返回不同夹具；断言地址、行、统计卡与空态。视觉部分用 `design/scripts/console-states.mjs` 分别跑 `--only usage` 与 `--only skill-usage`，截图放 `artifacts/`（不入库）。
- **CLI**：`httptest` 断言请求里带了 `since` / `until` / `skill`；纯函数单测覆盖日期换算、DST、非法组合；help 快照随 declarations 更新。
- **独立验收**：`qa/role-matrix` 本版未重跑——改动只在已登录账号自己的读接口上，没有新增或改动任何权限门；上一轮全量是 102/118，16 条失败在 `main` 上同样复现（`skill cat` 的既有期望偏差），记为基线而非本版回归。

## 结果

| 用例 | 结果 | 证据 |
|---|---|---|
| T01、T02、T03 | 通过 | `UsageHistoryIT`：半开窗口的四个边界、统计与明细同一批行；`UsageWindowsTest`：自定义窗口的整日数 |
| T04 | 通过 | `UsageWindowsTest`（含未来 `since`、跨度、下限、成对、RFC3339、90 天 + 2 小时边界）；`UsageCollectionIT` 的两条 400 |
| T05、T06、T27 | 通过 | `UsageHistoryIT`：出现 / 调用都命中、大小写不敏感、损坏 `detail` 不报错；`ActivityRepositoryIT.aSearchStoresOnlyTheFirstFiftyResults`：只存前 50 条，第 51 名按关键词查不到 |
| T07、T08 | 通过 | `UsageCollectionIT.theOwnerSeesACollaboratorsCallsAndSubmissionsInsideTheWindow`：协作者的行、窗口内的调用 2 次与提交 1 次、窗口外那次不计；`theOwnerViewIsTheWindowNotTheCumulativeSignal` 补窗口外的自有调用 |
| T09 | 通过 | `UsageCollectionIT.aPublicSkillBucketsTheWindowAggregate`（`10+` / `少于 10 个账号` / `users` 为空）+ `ActivityRepositoryIT.collaboratorDaysCountsTheWindowExactly`（窗口外的一次调用不计入 `skillRange` 的调用与账号） |
| T10 | 通过 | `UsageCollectionIT.theWebPlaneReadsTheSameRowsAsTheApiPlane`（两个平面同一窗口的 `items` / `total` / `has_any` 逐项相等）；控制台 `sends the local day bounds and the skill keyword` + CLI `TestUsageSendsTheWindowAndKeyword` 断言两端发送同一组 `since` / `until` / `skill` |
| T11 | 通过 | `usage_range_test.go` 的 8 条拒绝用例 |
| T12 | 通过 | `usage-range.test.tsx`：`from=2026-13-01`、`from=2026-09-31`、`days=14` 都落回默认 |
| T13 | 通过 | `UsageWindowsTest.theDefaultWindowIsReadOffTheGivenClock`（接口 90 天）、`usage_range_test.go` 默认 30 天、控制台默认请求跨 30 天 |
| T14 | 通过 | `UsageWindowsTest.theRetentionBoundaryCarriesTwoHoursOfTimezoneSlack`（接受 90 天 + 2 小时、多一秒拒绝）；`usage-range.test.tsx` 与 `usage_range_test.go` 各有一条「today-89 接受 / today-90 拒绝」 |
| T15、T17 | 通过 | 控制台 `keeps the filters on the tab links`、`reads the tab and the page from the address`、`sends the local day bounds…`；切页签回第 1 页与设计件一致 |
| T16 | 通过 | 控制台 `offers a filtered empty state…`、`says there are no records at all…` |
| T18 | 通过 | `TestUsageHelpListsTheWindowFlags`：`skmr usage --help` 输出含四个参数 |
| T19 | 通过 | `TestUsageWindowOfDays` / `…CustomDays` 的标签；`TestUsageStatsCarriesTheWindowOnItsFirstLine`、`--users` 首行用例 |
| T20、T21 | 未执行 | 未造 10 万行窗口数据；[`technical-design.md`](technical-design.md) 附录 C 的 p95 两行仍待填 |
| T22、T23 | 通过 | `UsageHistoryIT` 的过滤统计用例（命中集合、多命中） |
| T24 | 通过 | `usage-owner.test.tsx` 的公开说明 + `TestUsageUsersOnAPublicSkillSaysThereIsNoTable`；平台信号由既有用例保护 |
| T25 | 通过（自动测量）；视觉验收待人工 | 附录 C：间距 0→20 px、按钮 32×28 → 28×28 / `padding:0` / 悬停 `rgb(48,54,61)` |
| T26 | 通过 | `usage-range.test.tsx`：不存在的日期、起晚于止、结束晚于今天、早于 90 天窗口 |
| T28 | 通过 | `usage-owner.test.tsx` 的非所有者用例：有时间按钮、无视图开关、请求带窗口 |

整套命令（2026-11-11，本 worktree）：服务端 `./mvnw verify` 1323 个用例全过（surefire 552 + failsafe 771）；控制台 `npx vitest run` 47 个文件 / 758 个用例全过；CLI `go test ./...` 全部包 ok；设计件重新构建后 `console-states --only usage` 与 `--only skill-usage` 全过；`check_docs.py` 与 `impact.py --check` 全过。T20 / T21 的性能数字与 T25 的「人看一眼」仍是缺口。

> 结果只写「通过 / 失败 / 未执行」；失败附命令、输出或截图，不写「应该没问题」。

## 已知问题

- **筛选按浏览器本地日、时刻按 `Asia/Shanghai` 展示**（已复现，本版不修）：`rangeInstants` 用浏览器时区的零点换算（PRD「用户本地自然日」），而 `formatTime` / `formatDay` 固定 `Asia/Shanghai`。不在该时区的用户，边界日可能看到日期对不上的行。首批用户在上海，两者一致；统一时区要动展示层，另开版本。
- **Web 端 400 的原因不落在字段下方**（已复现，页面可继续操作）：区间由控件先行校验，skill 关键词在输入处截到 100 个字符；其余 400（例如浏览器时钟超前）走失败横幅，不逐字段说明。PRD A6 的「页面字段下方说明」由控件校验承担。
- **默认 30 天、记录都在 30–90 天前时的空态**（已复现，本版不改；与设计稿一致）：`has_any` 为真，页面写「没有匹配的使用记录。清除筛选」，但此刻没有筛选可清，按钮把人留在原处；服务端的 `has_any` 是滚动的 90×24 小时，而页面最长只能选 90 个本地自然日，两者之间有一条最多约 24 小时的窄缝——落在那里的行会让 `has_any` 为真却选不出来。改句子或让「清除筛选」放宽到近 90 天需要所有者定；本版照设计稿执行。
