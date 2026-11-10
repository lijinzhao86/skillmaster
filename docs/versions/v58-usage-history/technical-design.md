# v58-usage-history · 技术设计

> **最后更新**：2026-11-10
> **状态**：已实现（结果见 [`test-plan.md`](test-plan.md)）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR 0179](../../decisions/0179-personal-usage-is-visible-only-to-its-owner.md)**——本文只链接，不复述理由。

## 架构

### 新增模块与边界

**新模块 `M17 activity`**，在 [`ModuleMap.java`](../../../skillmaster-server/src/main/java/com/skillmasterai/common/ModuleMap.java) 的 `MODULES` 里加一项，拥有两张表：`activity_event`（明细，按周分区）与 `skill_collaborator_day`（日汇总）。对外发布行为接口与它们的事件记录类型：

- `ActivityRecorder`：写入。控制器不直接调它，而是经由新增的 `usecase/RecordActivityUseCase`——与现有 [`RecordSkillUsageUseCase`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/RecordSkillUsageUseCase.java) 同一位置、同一套 `REQUIRES_NEW` 写法。事件类型 `ActivityEvent` / `SearchActivity` / `InvokeActivity` / `SearchHit` / `ActivityKinds` 随之发布。
- `UsageHistory`：读。本人明细 / 本人统计 / 所有者视角三个查询。
- `ArchiveSettings`：归档目录的配置绑定（`SkillmasterProperties` 读它）。

业务 SQL 落在 `modules/activity/internal/`；三个运维任务（分区、汇总、归档）的 DDL/读取 SQL 是例外，它们不在 `internal/` 里，也没有经过仓储接口。`@Transactional` 只在 `usecase/`；`TableOwnershipTest` 检查的是**表归属 M17**，不检查 SQL 的包位置。

```
写路径（读请求完成之后，best-effort）：
  api 控制器 ──(解析结果)──► RecordActivityUseCase ──┬─► 新事件：INSERT ─────────► activity_event（同步，REQUIRES_NEW）
                                                     └─► 翻页：并入最近一条同词搜索（FOR UPDATE + UPDATE）

读路径：
  api / web 控制器 ──► UsageHistory ──► activity_event（本人明细 / 统计）
                                   └──► skill_collaborator_day（所有者视角）

汇总：
  ActivitySummaryJob（每日）──► activity_event（invoke）
                          └──► M10 发布的只读计数接口（submit 次数）──► skill_collaborator_day

保留：
  ActivityPartitionJob（启动 + 每日）：建未来两周分区
  ActivityArchiveJob（每日）：整周早于 90 天的分区导出到 OSS 后 DROP
```

### 采集点（每一行都对应现有代码）

| 事件 | 采集位置 | 现有符号 |
|---|---|---|
| `search` / `browse` | 网页发现结果与 API 搜索结果组装完之后 | [`WebDiscoverController`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/WebDiscoverController.java)（`/web/discover`，响应里有 `total`）；[`SkillsController`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/SkillsController.java) 的搜索分支（[`SearchResponse`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/SearchResponse.java) 本身**没有** `total`，见该文件注释） |
| `skill_view` / `version_view` | web 与 api 的 skill 详情、按版本查看 | [`WebSkillController`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/WebSkillController.java)、`SkillsController` 的详情与版本分支 |
| `version_list` / `file_list` | **只在 API / 作者面产生**：`SkillsController` 的 `/versions` 与 `AuthorSkillController` 的文件列表。控制台的版本页签读的是详情（`skill_view` / `version_view`），文件页签直接开单个文件（`file_read`），没有单独的列表请求 | `SkillsController`、`AuthorSkillController` |
| `invoke` | `/api/v1/.../body` 解析成功之后 | [`SkillsController`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/SkillsController.java) `body` 方法；解析在 [`ReadSkillUseCase.streamFile`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/ReadSkillUseCase.java)。**网页的 `body` 是控制台预览，记 `file_read` 不记 `invoke`**——预览不等于使用 |
| `file_read` | `/files/{relpath}` 与网页预览解析成功之后 | `SkillsController.file`；`WebSkillController` 的 `file` 与 `body`；解析在 `ReadSkillUseCase.streamFile` 与 [`ReadAuthoredSkillUseCase.streamFile`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/ReadAuthoredSkillUseCase.java) |
| `download` | 整版归档（`skmr run` / `skmr get`） | [`AuthorSkillController`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/AuthorSkillController.java) 的两个 archive 方法；`WebSkillController` 的 archive 方法——**今天一条记录都没有** |
| `login` / `logout` | 网页登录成功、两种登出 | [`WebAccountController`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/WebAccountController.java) `login` / `logout`；[`LogoutController`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/LogoutController.java) |

`ReadSkillUseCase.streamFile` 今天只给「公开、非所有者、SKILL.md」这一种情形带 `SkillUseCandidate`（见 `StreamedFile` 与 `ReadSkillUseCase.streamFile` 里的 `counts`）。`counts` 是 M15 的口径，**不改**。给它并列加一个 `Consumed(skillId, ownerUserId, versionId, versionLabel)`，只要 `StreamLookup.Found` 就带上，控制器据此记录；M15 的候选仍按原口径产出。同一个 `Consumed` 也加在 `ReadAuthoredSkillUseCase.streamFile` 上（网页预览走它）。两处都需要版本快照：M9 的 `streamFileOf` / `authorStreamFileOf` 从「只返回 `StreamLookup`」改成「查找结果 + `SkillSnapshot`」，所有者取自 M4 的 `Namespace.ownerUserId`——不在读取路径上再查一次。

### 写入方式

- **同步、best-effort**：与 [`RecordSkillUsageUseCase`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/RecordSkillUsageUseCase.java) 同一条路——业务事务结束之后 `REQUIRES_NEW` 写一行；失败只记日志，不重试、不影响读。写完立刻可查（PRD A1）。
- **顺序**：搜索行先写、调用行后写；关联在调用行插入前完成（下文），因此不需要事后 UPDATE。**唯一的 UPDATE 是翻页并入**（第 2 页及以后把结果追加到同一条搜索上，见下），它只改非索引列 `detail`，通常是 HOT update。
- `at` **由调用方传入**：控制器用 [`Timestamps.now()`](../../../skillmaster-server/src/main/java/com/skillmasterai/common/Timestamps.java) 的同一口径（RFC3339 UTC，整秒），`RecordActivityUseCase` 不自己取时钟。窗口与翻页的边界用例因此可以把 `at` 钉死，直接驱动用例层。

### 搜索与调用的关联

**一次调用只查一次，在插入调用行之前完成；查不到就是「直接调用」。** 算法只有四条判定：

1. 同账号：`actor_user_id` 相等。
2. 只找搜索：`kind = 'search'`。
3. 窗口：搜索的 `at ∈ [调用.at − 30 分钟, 调用.at]`。
4. 命中：这次调用的 `skill_id` 在那次搜索**返回给调用方的那一页**结果里。

候选按 `(at DESC, id DESC)` 取**第一条**命中就停——同一秒的两条按 `id` 分先后，后写入的算更近。

**落库**：`invoke.search_event_id = 命中的搜索 id`，`invoke.search_rank = 该 skill 在结果里的名次`。两条直接写在插入时，没有后续 UPDATE。

**数据结构上的一条优化**：搜索行的 `detail` 同时存两份——`results`（带地址的对象，给展示用）和 `skill_ids`（按名次排的扁平数组，给关联用）。匹配只做一次数组查找，名次就是下标 + 1，不在调用路径上解析嵌套 JSON。

**翻页并入同一次搜索**（2026-11-10 用户定）

「搜索次数」数的是查询，不是请求：同一查询翻到第 2 页，不该变成两次搜索，也不该在明细里多出一行。

- **第 1 页**（网页 `p` 缺省或为 1；API 不带 `cursor`）→ INSERT 一条新的 `search`，`detail.pages = 1`。
- **第 2 页及以后**（网页 `p > 1`；API 带 `cursor`）→ 找**同账号、同 `target`、同 `filters`、首条搜索在 30 分钟内**的最新一条 `search`，`SELECT … FOR UPDATE`（候选上限 20 条，通常只有 1 条）后在 `detail` 上并入（**两个平面都比对 `filters`**；API 的游标请求要重发筛选，CLI 已经这样做）：
  - 结果按响应顺序追加，按 `skill_id` 去重；名次接着已有的 `returned` 往下排（网页 = 偏移 + 下标 + 1，API 游标不解析偏移，按已存条数续排）；
  - `returned` 累加、`pages` 加一；`total` 取第一条非空的值（首条为空、后面某页带了总数时补上）；
  - 存储上限仍是 50：满了就不再追加，`total` 不变。
- **找不到可并入的搜索**（首条已超过 30 分钟、或查询词/筛选不同）→ 按第 1 页处理，另插一条。宁可多一行，也不把两次查询混成一次。
- 30 分钟与下面的关联窗口是**同一把尺子**：首条搜索出了窗口，翻页就另起一条，新的那条自己参与关联。
- 并发：同一用户两个翻页请求同时到达时，`FOR UPDATE` 让后一个等前一个提交后再读改写，不会丢结果。

调用写入时的关联如下：

```text
recordInvoke(actor, skill, version, at):
  candidates = SELECT id, detail FROM activity_event
               WHERE actor_user_id = :actor AND kind = 'search'
                 AND at >= :at - 30min AND at <= :at
               ORDER BY at DESC, id DESC
               LIMIT 50
  for s in candidates:
      rank = indexOf(s.skill_ids, skill)          -- -1 表示没出现
      if rank >= 0:
          INSERT invoke(..., search_event_id = s.id, search_rank = rank + 1)
          return
  INSERT invoke(..., search_event_id = NULL)       -- 直接调用
```

走 `(actor_user_id, at DESC) WHERE kind = 'search'` 部分索引；30 分钟窗口最多跨两个周分区，分区裁剪后候选通常是个位数。`LIMIT 50` 是保险：真到 50 条还找不到，按「没关联上」处理，不做全窗口回扫。

**边界规则（写进测试）**

| 情形 | 结果 |
|---|---|
| 搜索结果第 2 页才有这个 skill | 翻到第 2 页时并入同一次搜索，照常关联；**从没请求过那一页**则不关联 |
| 先调用、后搜索 | 不关联（只向前看） |
| 网页搜索、CLI 调用，同账号且 10 分钟内 | 关联（意图连续，与入口无关） |
| 搜索后 31 分钟才调用 | 不关联 |
| 同一查询翻到第 2 页 | 并入同一次搜索：明细仍是一行，搜索次数不变，结果按翻页顺序追加（去重，最多 50） |
| 首条搜索 31 分钟后才翻到第 2 页 | 出窗口，另起一条；它是另一段查询会话 |
| 一次搜索之后调用了 3 个结果里的不同 skill | 三次都挂同一次搜索；明细行把三条**分行列出**（不折叠），行内展开的结果面板里被调用的 3 条都加粗并标「已调用」；统计按三次算，`invocations` 是长度 3 的数组 |
| 搜索行写入失败（best-effort） | 这次调用成为直接调用——这是 best-effort 的已知代价 |
| skill 改过名 | 仍关联：用 `skill_id`，不用地址 |

**展示侧怎么用这两个字段**

- 明细：`kind='search'` 的行 + `kind='invoke'` 且**没有挂进窗口内搜索**的行（`search_event_id IS NULL`，或挂的搜索刚掉出 90 天窗口——后者显示为直接调用）；再按 `search_event_id IN (…)` 一次取回**全部**被挂的调用，按 `(at, id)` 升序装进 `invocations`。
  - **调用了几条就列几行**（2026-11-10 用户定）：每行是 `地址 @版本 第 K 个结果`，多行时行尾附上调用的时刻（窄屏隐藏，`title` 里有）。多行时整行顶部对齐，时间和折角对齐第一行。不用悬停披露——藏起来的计数没人会注意。
  - **行内展开，不是浮层**：面板与表格同宽、把后面的行往下推。面板底色 `--wash`，两列按名次排；**被调用的那几条加粗并写「已调用」**（按 `rank` 集合判断）。最多 50 条，面板最高 300px 内部滚动。
  - **整行是开关**：搜索行上不放链接（点哪里都展开），要看那条 skill 在展开列表里点。面板与行内的 mousedown 都不触发「点外面收起」——否则选字复制会被打断；文字被选中时点行也不切换。
  - `invocations` 为空 → 「未调用」。
- CLI：一次调用时与网页同一行（`→ A@1.2.3（第 5 个结果）`）；多条调用时**分行列出**（`→ 调用了 3 个：#1 hexu/slide-maker@0.8.0（15:13）`、`#2 …`），默认就看得见，不需要 `--expand`；`--expand` 的返回列表里被调用的几条行尾再标 `← 已调用（09:41）`；`--json` 直接输出 `invocations` 数组。
- 统计：调用次数按 `invocations` 全部计；「搜索后调用了结果」= 90 天内、且所挂搜索仍在窗口内的 `count(distinct search_event_id)`（挂到刚出窗口搜索的调用算直接调用，不进这个数）；「未调用」= 搜索数 − 这个数。一次搜索挂 3 次时，三个数分别是「搜索 1 次、调到 1 次、调用 3 次」。**翻页并入不增加搜索数**——一行就是一次查询。
- 内部第 4 层：直接按 `search_event_id` join，能看到一次搜索之后的全部调用，不受折叠影响。

### 边界条件（写入、关联、展示）

| # | 情形 | 处理 |
|---|---|---|
| B1 | 未登录 / 匿名访问 | **不记**。所有事件都以 `actor_user_id` 为前提；ADR 0179 与 PRD 不做 #6 |
| B2 | 搜索请求失败或降级到「语义不可用」 | 只在**成功返回结果页**时记一条；失败没有结果可展示，也不该参与关联 |
| B3 | `q` 为空但有筛选（浏览发现页） | 记 `browse`，**不记 `search`**，不参与任何关联 |
| B4 | 搜索返回 0 条 | 记 `search`，`results=[]`；明细行没有可展开的内容，**不画折角、整行不可点** |
| B5 | 结果超过 50 条 | 只存前 50（`results` / `skill_ids` 同步截断），`total` 仍是完整数；面板头与 CLI 都说明「只记录前 50 个」；关联只认这 50 条 |
| B6 | API 搜索响应没有总数 | `total` 记空；界面用 `returned` 显示「N 个结果」，工具提示写「本次响应返回 N 个」 |
| B7 | API `/body` 解析成功、客户端中途断开 | **记为调用**：blob 不可变，解析成功即内容已确定；与 M15 的「加载正文就是使用」同口径。网页的 `/web/.../body` 是预览，走 B8 的读取口径 |
| B8 | `/files/SKILL.md` | 记为 `file_read`，**不是** `invoke`（ADR 0179 决定 5） |
| B9 | 整版归档（`run` / `get`） | 记 `download`，不进调用统计、不出现在「调用的 skill」里 |
| B10 | 任一事件写失败（best-effort） | 该事件缺失，读路径照常；**公域三张卡来自 M15，使用者表来自日汇总（由明细聚合），两者可能不完全相等**，这是 best-effort 的已知代价。失败会打一条限流的 WARN，不会完全静默 |
| B11 | skill 改名 / 删除 | 行里用 `skill_address` 快照；面板里的结果链接按名字走，删除或改名后可能 404——历史记录仍读得懂 |
| B12 | 账号注销 | 明细与日汇总**不删除**（ADR 0179 决定 7，2026-11-10 修订），按保留期保存，只余去标识化账号 id；指向本人 skill 的汇总行随 skill 外键级联删除；`audit_event` 按 M10 原语义（保留、30 天后清空 detail） |
| B13 | 时间与时区 | 线上传 RFC3339 UTC；网页走 [`skillmaster-console/src/time.ts`](../../../skillmaster-console/src/time.ts) 的 `formatTime`（`Asia/Shanghai`，与全站一致），CLI 按本机时区显示。窗口比较全用服务端 UTC |
| B14 | 90 天边界 | 查询硬过滤 `at >= now − 90 天`；分区按整周 `DROP`，物理上最多多留 7 天，但页面与 CLI 看不到它 |
| B15 | 一条搜索被多次调用 | 全部挂同一次搜索；行内分行列出全部，`invocations` 按时间升序 |
| B16 | 搜索结果只有翻页才出现、而用户**没翻到那一页** | 不关联（没请求过的结果不在任何事件里），显示为直接调用；翻到了就并入同一次搜索并关联 |
| B17 | 同一秒的两条搜索 | 按 `id DESC` 取后写入的那条。已知边界：同一秒内「先调用、后搜索」在时间戳上无法与「先搜索、后调用」区分，仍会关联——调用行没有 id 可与搜索比较，这是秒级时间戳的代价 |
| B18 | 公开 skill 的所有者请求使用者表 | 返回空数组；界面一句「公开 skill 仅提供聚合，不按协作者统计」 |
| B19 | 非所有者请求聚合 / 使用者表 | 404（不泄露存在性）；网页与 CLI 同一契约 |
| B20 | 用户翻到第 2 页 | **并入同一次搜索**（2026-11-10 用户定）：同账号、同查询词与筛选、首条搜索在 30 分钟内；结果按翻页顺序追加、按 `skill_id` 去重、最多 50，`pages` 加一。「搜索次数」仍是 1，明细仍是一行。**两个平面都比对筛选**；API 的游标请求要重发筛选（CLI 已这样做）。超过 30 分钟的翻页另起一条——与关联窗口同一把尺子 |
| B21 | 分区还没建好就来了写入 | `ActivityPartitionJob` 在启动时与每日任务里保证「本周 + 未来两周」；插入失败会被 best-effort 吞掉，所以这一条靠 job 保证，并盯住「no partition」类错误日志 |

## 数据模型

### `activity_event`（M17）

```sql
CREATE TABLE activity_event (
  id              BIGINT GENERATED ALWAYS AS IDENTITY,
  actor_user_id   TEXT NOT NULL,
  owner_user_id   TEXT,
  kind            TEXT NOT NULL,
  skill_id        TEXT,
  skill_address   TEXT,            -- 当时的 ns/name 快照
  version_id      TEXT,
  version_label   TEXT,            -- @1.2.3 或版本码
  target          TEXT,            -- 搜索词或文件路径，应用层截断到 300
  detail          TEXT NOT NULL DEFAULT '{}',
  search_event_id BIGINT,          -- 仅 invoke：挂到哪一次搜索
  search_rank     INT,             -- 仅 invoke：原名次
  at              TEXT NOT NULL
) PARTITION BY RANGE (at);
```

**约束与取舍**

- **不设主键**：这是只追加的日志，没有按 id 的读写，也没有外键指向它；分区表的主键必须带分区键，而多出来的那条唯一索引要在每个分区上多维护一份。`id` 由 identity 保证不重复，排序的并列用 id 断——不需要约束来证明。
- 列宽：`target` 应用层截断 300 字符；`detail` 只放该事件需要的 JSON。
- `actor_user_id` 不设外键：账号注销时活动明细**不删除**（ADR 0179 决定 7，2026-11-10 修订），只余去标识化账号 id；没有引用要处理，也不靠级联。
- `skill_id` / `owner_user_id` 不设外键：skill 删除后历史仍要读得懂，地址用 `skill_address` 快照兜底；所有者不可让渡，`owner_user_id` 写时定下不会漂。
- `kind` 是开放字符串，不建枚举表：新增事件类型不需要迁移；取值集合写在这里：`search` / `browse` / `skill_view` / `version_list` / `version_view` / `file_list` / `file_read` / `invoke` / `download` / `login` / `logout`。
- `detail` 按事件放必要字段，不塞整条请求：
  - `search`：`{"results":[{"skill_id":"…","address":"ns/name","rank":1}],"skill_ids":["…","…"],"total":12,"returned":12,"pages":1,"filters":{"scope":"public","category":["doc"],"my_tag":[],"public_tag":[],"sort":"relevance"}}`（`total` 可空——API 搜索响应没有总数；`returned` 是已存条数、翻页并入时累加；`pages` 是并入的响应数；`results` / `skill_ids` 最多 50 条、顺序一致，`skill_ids` 只给关联用）
  - `browse`：`{"returned":20,"filters":{…}}`
  - `file_read`：`{"path":"references/split.md"}`
  - `download`：`{"pinned":true}`
  - `login`：`{"method":"password"}`

**索引（只这四条）**

| # | 索引 | 谁在用 |
|---|---|---|
| 1 | `(actor_user_id, at DESC, id DESC) INCLUDE (kind, skill_id) WHERE kind IN ('search','invoke')` | 本人明细与统计（同一份索引：明细「没挂进窗口内搜索」在堆上过滤，刚出窗口的搜索 id 由索引 2 另查；统计的 `kind='invoke'` 与按 skill 分组走 INCLUDE 列，尽量 index-only） |
| 2 | `(actor_user_id, at DESC) WHERE kind='search'` | 调用关联 |
| 3 | `(skill_id, at DESC, id DESC)` | 所有者排查、内部按 skill 还原 |
| 4 | `(search_event_id) WHERE kind='invoke' AND search_event_id IS NOT NULL` | 明细折叠（按搜索一次取回挂上的调用） |

**为什么是四条，而不是五条或更多**

- **没有主键索引**：见上，日志不需要唯一约束。
- **没有 `(owner_user_id, skill_id, at)`**：`owner_user_id` 是 skill 的属性，按 skill 查已经是同一个集合；所有者视角的常用查询走日汇总表，不扫明细。`owner_user_id` 列保留给内部分析与账号级排查，不建索引。
- **明细与统计共用索引 1**：明细只要「搜索 + 没挂进窗口内搜索的调用」，统计要全部调用；把索引谓词放宽到 `kind IN ('search','invoke')`，明细查询在堆上再过滤那两种调用。多扫的行就是被挂上的调用，通常与搜索数同量级；比在每个分区上多养一条索引便宜。**「刚出窗口」的搜索 id 用索引 2 在 `[since − 30 分钟, since)` 里取一次**，不按 `id` 反查（那会扫 90 天全量）。
- **部分索引都不包含其他 kind**：浏览、读文件、下载、登录的量最大，却从不出现在这两条查询里——它们不该进这两条索引。
- 不做 BRIN：周分区下每个分区只覆盖 7 天，BRIN 省不了多少，多一套可见性维护。

**大数据量下每条查询的走法**

| 查询 | 索引 | 复杂度 |
|---|---|---|
| 本人明细一页 | 1 + 2 | 索引 1 取本人的 90 天行（`LIMIT 50` 命中即停，OFFSET 深页最多几千行）；索引 2 取一次刚出窗口的搜索 id |
| 本人统计 | 1 + 2 | 索引 1 一次范围扫描 + 聚合，行数 = 本人的调用与搜索（不是全表）；索引 2 同上取一次 stale 搜索 id |
| 调用关联 | 2 | 30 分钟窗口，通常 0–3 行索引项，逐个比对 `skill_ids` |
| 翻页并入 | 2 | 30 分钟窗口内同词搜索通常 0–1 条（上限 20）；`SELECT … LIMIT 20 FOR UPDATE` 会锁住这些候选行，改的是非索引列 |
| 明细折叠 | 4 | 每页最多 50 次按 id 回查，每次返回该搜索挂上的几行 |
| 所有者使用者表 | 日汇总 | 90 天 × 协作者数，千行级 |
| 公域三张卡 | M15 | 既有接口，不扫本表 |

**分区裁剪**：所有在线查询都带 `at >= now − 90 天`，13 个周分区里最多扫 13 个（实际只会命中范围内的）。`at` 是定长 UTC 字符串，字典序就是时间序。

**写入放大**：每条事件 4 条索引 + 1 次分区表插入；50 万事件/天约 6 行/秒。**该估算假定 2 核实例**——PRD 把扩存储列为必做、2C4G 列为可选项；停在 1 核 1G 时以实测延迟为准，必要时把升配一起做。量再上一个数量级（>100 万/天）时按研究文档的触发条件拆实例或改批量队列，不改这里的查询形状。

**分区与保留**

- 按周 `RANGE (at)`；启动时 `ActivityPartitionJob` 与每日任务保证「本周 + 未来两周」存在。PG 没有默认分区，插入前必须已建。
- **冷层「最多 3 年」靠桶的生命周期规则，不靠代码**：对象键以 `activity/` 开头；上线前置是在私有桶上为这个前缀配置 1095 天的过期规则（它和普通 blob 同桶，不能整桶过期）。仓内目前**没有**这条配置或删除任务（2026-11-11 核实），部署时按 test-plan L7 检查。
- 90 天前：导出 **NDJSON.gz** 到 OSS 并核对行数后才 `DROP`（`ActivityArchiveJob`）。顺序是写文件 → 数行 → 上传 → `DROP`；任何一步失败都保留分区。不引入 Parquet 写入依赖：一个每日归档任务不值得整套 Hadoop/Parquet 栈；NDJSON 可流式写、行数可逐行核对，DuckDB 的 `read_json_auto` 一样能读。
- 迁移只建父表，不建分区——分区是运行时产物，`TableOwnershipTest` 只解析迁移里的 `CREATE TABLE`，父表归属 M17 即可。

### `skill_collaborator_day`（M17）

```sql
CREATE TABLE skill_collaborator_day (
  skill_id      TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  actor_user_id TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  owner_user_id TEXT,                   -- 可空：只由提交产生的行没有活动行可抄 owner
  day           TEXT NOT NULL,          -- YYYY-MM-DD（UTC）
  invokes       INT NOT NULL DEFAULT 0,
  versions      INT NOT NULL DEFAULT 0, -- 当天提交的版本数
  last_at       TEXT NOT NULL,
  PRIMARY KEY (skill_id, actor_user_id, day)
);
/* 所有者那张表按「一条 skill × 最近 90 天」读，所以按天排、把三个数带在索引里；
   不建 (owner, skill, day)：查单条 skill 时 owner 已知，用不到它。 */
CREATE INDEX skill_collaborator_day_skill
  ON skill_collaborator_day (skill_id, day DESC)
  INCLUDE (actor_user_id, invokes, versions, last_at);
```

- 只放所有者那张表需要的两个量：**调用次数**与**提交版本数**（2026-11-10 用户定：采用 + 贡献）。读取与文件级写入不进汇总，它们在明细里。
- 外键与 M15 的表同一写法（`ON DELETE CASCADE`）。账号注销不删这两张表（见 §账号注销）：账号行是去标识而不是删除，账号一侧的级联不会触发；但本人命名空间里的 skill 被删除时，指向它的汇总行随 `skill_id` 外键级联删除。明细没有外键，全留。
- `versions` 的来源是 `audit_event` 的 `submit` 动作（`CreateSkillUseCase`、`SubmitSkillUseCase`、`SubmitSkillVersionUseCase` 都记 `submit`）。M10 发布一个只读计数接口 `AuditCounts`（方法 `submissions`，入参 from / until），按 skill × 人 × 天返回分组计数，SQL 留在 M10——别的模块不能写 `audit_event` 的表名。
- 每日任务按水位线（`max(day)`）补到昨天；首次运行时表为空，回填最近 90 天。**所有者自己那一行读实时明细，协作者行走日汇总**——A11 要求「调用次数与我的使用一致」，而汇总只到昨天；为此 `ReadSkillUsageUseCase` 对所有者行单独调用 `history.mine`。
- 保留 1 年：每日任务顺带删除 `day < now − 365 天` 的行。

### 账号注销

`PurgeAccountsUseCase` 的既有语义是「保留审计行、30 天后清空 detail」。活动明细不同：**ADR 0179 决定 7（2026-11-10 修订）要求行为日志不随注销删除**——它们是内部安全、审计与产品分析记录，按各自保留期保存（明细 90 天、汇总 1 年、冷归档 1–3 年）。因此 `PurgeAccountsUseCase` 不改：账号资料与个人命名空间内容照旧在 30 天后删除/去标识化，`activity_event` 与 `skill_collaborator_day` 原样保留（本人命名空间里的 skill 被删除时，指向它的汇总行随外键级联删除）；它们里的 `actor_user_id` 指向的账号行只剩占位 handle 与去标识化字段。`audit_event` 保持 M10 现有语义不变。

## 接口

主契约（API，Bearer；网页 `/web/usage/**` 是同形状的会话版薄封装）：

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `GET` | `/api/v1/usage/interactions?page=1&limit=50` | 本人的交互明细（搜索行 + 未关联的调用行） | 任何已登录账号；只返回本人 |
| `GET` | `/api/v1/usage/stats` | 本人的统计（近 90 天） | 同上 |
| `GET` | `/api/v1/usage/skills/{ns}/{name}` | 全部使用：聚合 + 使用者表 | **仅所有者**；其余 404 |
| `GET` | `/api/v1/usage/skills/{ns}/{name}/users` | 只要使用者表 | 仅所有者；其余 404 |
| `GET` | `/api/v1/usage/skills/{ns}/{name}/mine` | 我在这条 skill 上的使用 | skill 可见者；不可见 404 |

**响应形状**

```jsonc
// interactions
{ "items": [
    { "kind": "search", "id": 1842, "at": "2026-11-10T01:38:00Z", "query": "pdf 合并", "total": 12, "returned": 12,
      "results": [{ "skill": "yuhan/pdf-splitter", "rank": 1 }, …],
      "invocations": [
        { "skill": "dev/pdf-tools", "version": "1.2.3", "at": "2026-11-10T01:41:00Z", "rank": 5 } ] },
    { "kind": "direct", "id": 1841, "at": "2026-11-10T00:55:00Z", "skill": "lijinzhao/code-dev-sop", "version": "1.1.2" } ],
  "total": 14, "page": 1, "page_size": 50, "window_days": 90 }

// stats
{ "window_days": 90, "calls": 12, "searches": 8, "converted": 6, "missed": 2, "skills": 7,
  "latest": "2026-11-10T01:41:00Z",
  "top": [{ "skill": "dev/pdf-tools", "calls": 3, "last": "2026-11-10T01:41:00Z" }] }

// skills/{ns}/{name} —— 私有 skill：totals 是精确数（字符串形式，与公开面的分桶共用类型）
{ "skill": "dev/pdf-tools", "window_days": 90, "public": false,
  "totals": { "calls": "12", "accounts": "3", "joins": 45, "last_used": "2026-11-05T08:00:00Z" },
  "users": [
    { "who": "dev", "owner": true, "role": "所有者", "invoke": 3, "submitted": 12, "last": "2026-11-10T01:41:00Z" },
    { "who": "yuhan", "owner": false, "role": "maintain", "invoke": 42, "submitted": 5, "last": "2026-11-09T18:20:00Z" } ] }

// 公开 skill 的同一条路径：totals 是 M15 的分桶短语，users 为空
{ "skill": "dev/pdf-tools", "window_days": 90, "public": true,
  "totals": { "calls": "1k+", "accounts": "100+", "joins": 45, "last_used": "2026-11-05T08:00:00Z" },
  "users": [] }

// skills/{ns}/{name}/mine —— state 是 VersionStatus 的键，界面负责翻译
{ "skill": "dev/pdf-tools", "window_days": 90, "calls": 3, "versions": 2,
  "latest": "2026-11-10T01:41:00Z",
  "by_version": [{ "version": "1.2.3", "state": "live", "calls": 2, "last": "2026-11-10T01:41:00Z" }] }
```

- **聚合的三个数来自 M15**（`skill_usage_total.calls` 分桶、`skill_usage_day` 的 30 天账号分桶、入库数），不在本模块重复实现。**公开 skill** 的 `totals.calls` / `totals.accounts` 因此是分桶字符串；**私有 skill** 给精确计数（字符串形式）——所有者已经能逐人看到调用次数，总量再分桶就自相矛盾。
- `role` 是 wire 档位键（`invoke` / `browse` / `write` / `maintain` / `admin`，所有者行固定 `所有者`）；控制台用 `rungs.ts` 的 `rungLabel`、CLI 用 `rungLabel` 翻成界面词。`state` 同理是 `VersionStatus` 的键。
- `users` 的顺序：所有者第一，其余按 `invoke` 降序、`last` 降序；`by_version` 按 `last` 降序、`calls` 降序。
- 明细每一行带 `id`（`activity_event.id`）：控制台用它做行键与 `?open=` 的值，CLI 原样输出；它不是能单独取一行的地址。
- 公开 skill 的聚合接口 `users` 为空数组，`public: true`；网页据此显示「公开 skill 仅提供聚合，不按协作者统计」。
- **时间**：线上 `at` 一律 RFC3339 UTC（`2026-11-10T01:41:00Z`）。网页经 `skillmaster-console/src/time.ts` 的 `formatTime` 显示（`Asia/Shanghai`，与全站一致）；CLI 按本机时区显示。窗口与关联比较全在服务端用 UTC，不经过客户端。
- **`total` 可空**：API 搜索响应没有总数时，界面用 `returned` 显示「N 个结果」，工具提示补一句「本次响应返回 N 个」；不编一个假的总数。
- **体积**：`limit` 默认 50、最大 100；一条搜索的 `detail` 最多约 3KB（50 条结果），一页最坏约 150KB。网页每页 10 条；CLI 默认 50 条。`--expand` 不额外请求，直接用响应里的 `results`。
- 错误：未登录 401 `unauthenticated`；非所有者请求聚合/使用者表 404 `skill_not_found`（不泄露存在性，与 A12/A19 一致）；`page < 1`、`limit < 1` 或 `limit > 100` 400 `invalid_request`（与 `ErrorCode.INVALID_REQUEST` 一致）。
- **CLI 映射**：`skmr usage` → interactions；`--expand` 用同一响应里的 `results`，不另开接口；`--stats` → stats；`skmr usage <地址>` → skills；`--users` → `/users`；`--mine` → `/mine`。`--json` 直接输出上述形状。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P1 | M17 模块、两张表与分区、全部采集点（search / browse / view 三类 / file_read / invoke / download / login-logout）、关联写入口 | 每类事件各写出一行；翻页并入同一条搜索（结果追加、搜索数不增）；`invoke.search_event_id` 在 30 分钟窗口内挂对搜索；M15 的公开计数不变 |
| P2 | 本人读路径：interactions + stats 接口、网页与 CLI 对应 | A1、A2、A3、A16、A23、A9、A18、A21（CLI 覆盖） |
| P3 | 所有者读路径：日汇总任务、M10 计数接口、聚合 / 使用者表 / mine 接口 | A10、A11、A12、A19、A20 |
| P4 | 90 天前分区导出 NDJSON.gz 到 OSS、核对后 `DROP` | A4、A15 |

**每期明确不做**：P1 不提供任何查询接口；P2 不含所有者视角；P3 不含归档；P4 不改查询路径。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| OQ1 | 明细分页用 OFFSET 还是 keyset？ | 先 OFFSET（单人 90 天量级小）；p95 超 300ms 时换 keyset，地址不变 | 集成测试计时，按 PRD 指标判定 |
| ~~OQ2~~ | ~~日汇总上线时是否回填历史 90 天？~~ | **已定：回填一次**（表为空时按天循环到昨天） | 部署后在测试库跑一次，核对总数与明细一致 |
| ~~OQ3~~ | ~~账号注销时活动明细的删除走索引 `DELETE` 还是按账号批量分批？~~ | **已定：不删除**（ADR 0179 决定 7，2026-11-10 修订）——账号注销只清空/去标识账号资料与个人命名空间内容，行为日志按保留期留着 | — |
| OQ4 | 30 分钟内搜索很多次时，关联只扫最近 50 条候选会不会漏？ | 漏的只退化成「直接调用」，不影响主流程；若线上退化比例高，先调大 `LIMIT`，再考虑给搜索结果建 `activity_search_result` 子表 | 用压测账号在一个窗口内造 60 次搜索再调用最早一次的结果，看是否关联上 |
| OQ5 | 超大账号（一个人 90 天几十万行）的统计超过 300ms 时，先加覆盖索引还是先做「人 × 天」汇总？ | 先量：覆盖索引 `INCLUDE (skill_id)` 不动查询形状；仍不够再加一张 `activity_actor_day` | 造一个 30 万行的账号跑统计，`EXPLAIN (ANALYZE)` 看时间与扫描行数 |

## 附录 A · 与现有代码的关系

- [M15 `metrics`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/metrics/) **不改口径**：公开 skill 的调用分桶、账号分桶、入库数仍由它提供；`RecordSkillUsageUseCase` 保持现状。
- [M10 `audit`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/audit/) 只新增一个只读计数发布接口，事件写入不变。
- [`ReadSkillUseCase.streamFile`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/ReadSkillUseCase.java) 增加 `Consumed` 事实，不改 M15 候选的判定。
- 网页与 API 两个平面都要挂采集；归档路径（`AuthorSkillController` / `WebSkillController` 的 archive）今天完全没有记录，是本版新增。
