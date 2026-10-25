# v18-batch-publish · 技术设计

## 架构

四段：**读是新的一条、写是在既有路径上多加一列与一条算号规则**，批量那条路由是新的壳、里面调的仍是既有的发布用例：

```
① 类型（写）      草稿的 release_type ∈ {major, minor, patch}，可空
                  提交时给 / 草稿期改 / 发布后冻结
                        │
② 算号（发布时）  草稿自带的号 > 显式号 > 按类型与基线算（首次 1.0.0；基线不可用则要求显式号）
                  基线是「发布那一刻的当前线上版本」
                        │
③ 队列（读）      GET /web/publish-queue ──► 每个 skill + 它自己的草稿行
                  每份草稿：码（pin）、名称、提交时间、版本类型
                  每个 skill：当前线上版本（**只在够得着上线的 skill 上，服务端筛**）
                        │
④ 批量发布（写）  POST /web/skills/publish-batch ──► 逐条调用既有的发布用例
                  会话 + CSRF；每条自己一个事务；返回逐条结果
```

**号在发布那一刻算**——理由在 [ADR 0091](../../decisions/0091-release-type-decides-the-version-number.md) §背景，这里不重复。

**类型不占号**：它是声明，不产生任何数字——它与 [ADR 0055](../../decisions/0055-drafts-have-a-required-name.md) 否掉的"预分配"怎么分界，见 [ADR 0091](../../decisions/0091-release-type-decides-the-version-number.md) §理由。

**为什么要一条新读**：这一页的问题是「哪些草稿在等我」，它的答案是一张**一行一份草稿**的表。今天没有任何一条读回答得了（逐项见 §接口 `GET /web/publish-queue`）。

## 数据模型

**一列，没有新表。**

```sql
-- V29
ALTER TABLE skill_version ADD COLUMN release_type TEXT;
ALTER TABLE skill_version ADD CONSTRAINT skill_version_release_type
  CHECK (release_type IS NULL OR release_type IN ('major', 'minor', 'patch'));
COMMENT ON COLUMN skill_version.release_type IS
  'How big this change is (major/minor/patch), declared by the author. It is not a
   version number: the number is computed at publish time from this and the current
   live version (ADR 0091). NULL means undeclared, and nothing is inherited.';
```

- **可空**：已有行、以及"我要显式给号"的草稿，都没有类型。
- **不进 digest、不改地址**：与 `draft_name` / `changelog` 同属作者面元数据。
- **不加索引**：没有查询按它筛。
- **不新增模块归属**：`skill_version` 已经属于版本模块，`ModuleMap` 不动（新增**表**才要动它）。

**为什么不存算出来的号**：存了就变成"草稿占号"，而那正是 ADR 0055 否掉的形状。号只在发布那一刻产生，落进既有的 `skill_version.version` 列。

## 接口

### 版本号怎么算

```
草稿自己已经有版本号?  ── 有 ──► 沿用它（请求另外给了不同的号 → 400 version_name_is_assigned）
      │ 没有
      ▼
explicit?           ── 有 ──► 用它（校验 semver + 该 skill 内唯一）
      │ 没有
      ▼
base = 当前线上版本（skill.current_version_id 指向的那一版）
      │
      ├─ 没有线上版本（current_version_id 为空，从未发布） ► "1.0.0"（**类型不参与，也不要求有类型**）
      ├─ 草稿没有 release_type ──────────────────────► 400 release_name_required（既有错误码，语义不变）
      ├─ 有线上版本、但那一版没有号（version 为空或不是合法 semver） ► 400 release_type_needs_explicit_name
      ├─ base 带预发布标记（-rc.1） ─────────────────► 400 release_type_needs_explicit_name
      └─ 否则 ──────────────────────────────────────► bump(base, release_type)
```

- **第一档是既有的，不是新加的**：有一类草稿**在提交那一刻就带着版本号**（`skill_version.version` 非空）。这类草稿发布时**沿用它**——`SkillVersionService` 今天的原话是「a legacy draft that already declared one keeps it and must not be renamed here」，请求另给一个不同的号就是 400 `version_name_is_assigned`（`ErrorCode` 既有）。**算号只在草稿没有号的时候发生**；漏了这一档，实现会算出一个号却写不进去（落库那一步是 `COALESCE(version, …)`），或者撞上 `version_name_is_assigned`。
  **这一档是防御性的，队列读不为它加字段**（2026-10-25 核）：`skill_version.version` 全仓只在一处被写——`markPublished` 的 `COALESCE(version, :releaseName)`，而它只作用在 `state = 'draft'` 的行上；插入路径（`insertIfAbsent`）对草稿一律写 `NULL`。所以「草稿带着自己的号」这类行**今天的代码产生不出来**，只可能是历史数据或直接改库。页面上因此不呈现它（验收 25 用改库造数据来验），`GET /web/publish-queue` 的 `drafts[]` 也就不带它——多一个永远为空的字段，只会让人以为它有用。
- **上面那两档（基线不可用）的判据是同一个**：`ReleaseType.applyTo` 算不出号就报——空串、不是合法 semver、带预发布标记，三种都算。两处都说「请直接给一个版本号」，所以共用 `release_type_needs_explicit_name` 一个码（人要做的事完全一样，分成两个码只会让调用方写两个分支）。**那不是「从未发布」**：一个上线过、但那一版没有号的 skill，按 `1.0.0` 发出去就是把它说成了第一版。
- `major` → `X+1.0.0`；`minor` → `X.Y+1.0`；`patch` → `X.Y.Z+1`。
- **构建元数据**（`1.2.3+sha`）不算预发布，可以递增；递增时丢弃它。
- **算出来的号撞上已有版本 → 400 `version_already_exists`**，不顺延。号是契约，不是"尽力而为"。
- **新增一个错误码** `release_type_needs_explicit_name`：草稿已经声明了类型、但基线不可用（**没有号**、**不是合法 semver**、或**带预发布标记**）时说清"请直接给一个版本号"。**草稿连类型都没有时先报的是 `release_name_required`**——树里的顺序就是代码的顺序（`numberFromDeclaredType` 先看 `releaseType == null`）。它与 `release_name_required`（根本没有类型可用）是两种处境，合成一个会让人不知道该做什么。

### 提交（既有接口，加一个可选字段）

**要给类型的是 CLI 打的那两条**——建 skill 与给已有 skill 提交**不是同一个路由**，只改前者，`skill submit --to --release` 就没有地方带：

| 路由 | 谁在用 |
|---|---|
| `POST /api/v1/skills` | `skill submit`（`internal/api/submit.go` 的 `Submit`） |
| `POST /api/v1/skills/{ns}/{name}/versions` | `skill submit --to`（同文件的 `SubmitVersion`） |

两条都是 `consumes = MULTIPART_FORM_DATA_VALUE`，所以 `release_type` 是**表单的另一个 part**，不是 JSON 字段。`Submit` 与 `SubmitVersion` 又是同一个 `upload()` 的两个路径，`--release` 要同时进这两条。

**网页面与作者面的这几条「提交」路由本版不接，一条都不加**（2026-10-25 定；它们与 §改草稿 里新增的 `release-type` 路由不是一回事）：`POST /web/skills`、`POST /web/skills/import`、`POST /web/skills/{ns}/{name}/versions`（v8 从基线建草稿）、`POST /web/skills/{ns}/{name}/versions/import`、`POST /api/v1/author/skills`、`POST /api/v1/author/skills/{ns}/{name}/versions`（v10）——这些路由的界面上**没有版本类型控件**（PRD「不做」：v18 只把类型放进队列这一处），加了参数也没有人传，只是多一处要维护、还要被验收的契约。从这几条路进来的草稿，类型一律为空，之后在队列里选，或 `version release-type` 补。

**不做继承**：不会去读这个 skill 上一次提交的类型，也不读上一份草稿的。每次提交的类型只来自这一次请求——类型是对这一次改动的判断，上一次的判断不构成这一次的依据（[ADR 0091](../../decisions/0091-release-type-decides-the-version-number.md) 决定 9）。**幂等重放是唯一"不动"的情况**：内容没变、服务端返回已有的那一行，那一行上的类型保持原样。

### 改草稿（新增一条，照搬既有形状）

```
PATCH /web/skills/{ns}/{name}/versions/{version}/release-type
{ "release_type": "minor" }        // null 表示清空
```

**权限是 `write`，不是 `maintain`**：能提交草稿的人就该能声明这次改动多大。`maintain` 管的是发布、回滚、丢弃别人的草稿——那些会改变对外可见的东西，类型不会。

这与既有的 `…/{version}/name`、`…/{version}/note` **不同档**：那两条是「`write` ∧ 提交人是我，或 `maintain` 以上」，因为它改的是别人写下的标识。类型只声明量级，发布那一刻才变成号（[ADR 0091](../../decisions/0091-release-type-decides-the-version-number.md)）。

其余与它们一致：「只有草稿能改」的状态闸；**不清空成空串**，`null` 是唯一表示"没有类型"的值。

**两个面都要有它**：控制台走 `/web/…`（会话 + CSRF），CLI 走 **author 面** `PATCH /api/v1/author/skills/{ns}/{name}/versions/{version}/release-type`（Bearer）。既有 `…/name`、`…/note` 就是两面各一条，这条照它们的形状来——**只做 web 面的话，CLI 那条命令没有服务端可打**。

### 发布（既有接口，版本号从必填变可选）

`POST /web/skills/{ns}/{name}/publish` 的 `VersionChoice`：

- 给了 `releaseName` → 照旧，显式号优先。
- 没给 → 走上面的算号。**今天**的 400 `release_name_required` 只看一件事：草稿没有版本号、请求里也没给号（`SkillVersionService`），不看类型也不看基线。**本版之后**它只剩一个触发条件——没给号、**也没类型**、且当前线上版本存在；从未发布的那一档直接算 `1.0.0`（见上表）。**类型先判、基线后判**：草稿有类型而基线不可用时，报的是 `release_type_needs_explicit_name`（见上表）；草稿连类型都没有时，报的永远是 `release_name_required`，哪怕基线本身也不可用。

**向后兼容**：老调用方照旧给号，行为一字不变。

### `GET /web/publish-queue`（新增）

```
GET /web/publish-queue                    （会话；只读，不改变任何状态）

200 { "skills": [
        { "namespace": "dev", "name": "log-triage",
          "current": { "version": "0.9.0", "submitted_at": "2026-08-30T09:20:00Z" },
          "drafts": [
            { "code": "7K3M9QX2", "name": "重写分类", "release_type": "major",
              "submitted_at": "2026-10-25T09:12:00Z" },
            { "code": "4P8N2C7V", "name": "补样例", "release_type": null,
              "submitted_at": "2026-10-24T20:40:00Z" } ] } ],
      "truncated": false }
```

- **`code` 原样就是 pin**：批量请求里的 `version` 就写队列给的这一串，**不加任何前缀**。短码**必须裸传**——`draft:` 只在旧 ULID 的过渡输入里还活着（`draft:<ULID>`），而 `draft:<短码>` 是 `SkillAddress` 注释里点名的「一句谁都没写过的拼法」，故意不接受（[ADR 0063](../../decisions/0063-a-draft-is-addressed-by-a-short-code.md) 也写「地址里不写状态」）。同一个东西别在两处起第三个名字。
- **版本号那一格叫 `version`，不叫 `number`**：`number` 在仓里是提交序的内部整数，ADR 0033 已把它从**所有**线上记录里撤出（既有的 `AuthorVersionResponse` / `SubmitResponse` 都用 `version`）。叫错这个名字，实现时要么暴露错的那一列，要么凭空造出第二个名字。
- **形状就是页面那张表的形状**：一个 skill 一段，段里是它的草稿行。顺序由服务端给（skill 之间按各自最新一份草稿的时间倒序，skill 内新的在前），页面不重排。**从未上线过的 skill，`current` 是 `null`**——页面那一格写「从未上线」。
- **只返回有草稿的 skill**：没有草稿的 skill 不进这一页（PRD 验收 8 的 C）。
- **两个历史性的 null 也要有归宿**：`current.version` 可能为空（早期已发布、但没版本号的行），`drafts[].name` 也可能为空（v6 之前没有 `draft_name` 的行）。前者状态那一格写 `—`（**不是**「从未上线」——它上线过，只是没有号），后者写「未命名」——不拿空串占位，否则页面分不清"没有"和"没取到"。
- **时间一律 RFC3339**（与 `updated_at` 同一条约定），示例里就是响应原样；`2026/08/30 09:20` 是页面把它格式化之后的样子，不出现在响应里。
- **为什么不是 `GET /web/skills`**（2026-10-25 审计改）：那条读每个 skill 只给 `drafts`（份数）与 `draft`（**最新那一份**的 digest），而这一页要的三样它一样都没有——
  - **草稿的名称**：草稿的版本号是 `NULL`，而那条读的 `Version.name` 取的就是版本号，所以草稿那一格永远是空的；`draft_name` 不在它的投影里。
  - **草稿的稳定标识**：发布请求必须带 pin，而地址只认**行码/短码**（[ADR 0063](../../decisions/0063-a-draft-is-addressed-by-a-short-code.md)）。digest 会随草稿内容变化，拿它当 pin 会在编辑之后指向别的东西。
  - **每一份草稿的提交时间与版本类型**：`updated_at` 是 `COALESCE(current_version_submitted_at, newest_draft_submitted_at, created_at)`，**已上线又有新草稿时给的是线上那一版的提交时间**；`release_type` 更是没有任何一条读带它。
  退一步"逐 skill 调详情"是 500 次请求。
- **已删除的 skill 不进队列**：`WHERE s.deleted_at IS NULL`。它的草稿还在，但发不出去——摆一行只会让人点一次没有结果的确认。
- **只返回我够得着上线的 skill**（`owner` / `maintain` / `admin`），**页面不再按档位筛**（[ADR 0090](../../decisions/0090-batch-publish-confirms-once.md) 决定 7）。自己建的 skill 在既有代码里写作 `owner`（`ReadAuthoredSkillUseCase`），不是梯子上的五档之一——所以这个判断要在这条读里一次做对。**响应里不带档位字段**：带了只会诱使页面再筛一遍。
- **`truncated`**：这条读取 **501 条**再截成 500——只按 500 去取的话，"正好 500"与"被截断"都是 500 行，页面分不出来。多取回来的那一条只用来置这一位，不出现在响应里。上限取 500，但**这条读有自己的常量**（`ListAuthoredSkillsUseCase.LIMIT` 是 `private`，借不到；两个 500 将来要一起改）。
- **不给筛选、搜索、全选加参数**：它们都是对已取回的那一页数据的处理，页面自己做。**发布仍在服务端逐条把关**，所以页面过期也发不出去。
- **它要进 `/web/**` 的鉴权链**：`SecurityConfig` 今天逐条列出受保护的路径，**没列的会落到 `permitAll`**（那个文件自己的注释就是这么写的）。这条读必须像 `/web/skills/**` 那样被显式列进去，否则它是**匿名可读**的。

### `GET /api/v1/author/publish-queue`（新增，作者面）

```
GET /api/v1/author/publish-queue        （凭据 + skills:read）
200   与 GET /web/publish-queue 同一份响应：{ "skills": [ … ], "truncated": false }
```

- **CLI 的入口**（[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)）：`skillmaster skills pending` 读的就是它。两条路由共用同一个用例与同一个响应 record——形状不是"相似"，是同一份。
- **在作者面，身份来自凭据**（`@AuthenticationPrincipal AuthenticatedSubject`），不是 `/web/**` 的会话；`skills:read` 够用，与它读到的信息一致。
- **筛选、排序、截断与浏览器那条完全相同**：只列够得着上线的 skill、已删除的 skill 不进、skill 之间按各自最新草稿倒序、取 501 截成 500。
- **没有分页参数**：与浏览器那条同一条规矩——它们都是"把这一页要的东西一次取回来"。

### `POST /web/skills/publish-batch`（新增）

```
POST /web/skills/publish-batch          （会话 + CSRF，/web 链已覆盖）
{ "targets": [ { "namespace": "dev", "name": "pdf-tools", "version": "9F2T4HXK", "predicted": "1.3.0" },
               { "namespace": "dev", "name": "lark",      "version": "6Q1W8ZPM" },
               { "namespace": "dev", "name": "notes",     "version": "3B7X1QKD", "predicted": "0.4.1" },
               { "namespace": "dev", "name": "slides",    "version": "8M2V5TRJ", "predicted": "2.0.0" } ] }
               ← 下面只画了两行 `row`；另外两行与这两行同形

200 application/x-ndjson               ← 一行一个 JSON，来一条写一条
{"type":"start","total":4}
{"type":"row","ok":true, "namespace":"dev","name":"pdf-tools","version":"1.3.0",
 "draft_name":"补文档","predicted":"1.3.0"}
{"type":"row","ok":false,"namespace":"dev","name":"lark","code":"version_already_exists","message":"…"}
                                       ← `predicted` 可选：客户端给了什么就原样回什么，成功失败都一样
{"type":"done","ok":3,"failed":1}
```

- **逐条流式返回（2026-10-25 定）**。这一条只为一件事：界面上**一条一条地落定**（见 §界面）。
  一次返回也能做，但那样界面只剩一个转圈，走完之后四条同时变色——"哪一条正在走、哪一条卡住"
  就只能在结果里回头找。**批量越大，差别越大**（PRD 做 #9）。
- **请求级的问题仍在首行之前**：未登录、没有 CSRF、`duplicate_target`、`targets` 为空——这些
  先于任何一条发布，按普通 4xx 返回，不是一个"流到一半才说"的错误。
- **客户端断了就停**：已经写出去的行各自在自己的事务里提交过了，不回滚；没写的不会再写。
  这正是"逐条成功或失败"的同一条规矩，只是这一次它对**连接**也成立。
- **每条自己一个事务**，逐条成功或失败，不回滚已经成功的。
- **请求必须带版本 pin**，服务端不替调用方猜"最新草稿"——猜错就是把没人选过的东西放出去了。
- **不带 `releaseName`**：批量里不谈号，号走 §版本号怎么算——**草稿自带的号优先**（T22 就是这么发出去的），否则按类型与基线算。
- **`predicted` 是客户端算并传上来的预演**（可选字段，**放在每个 target 上**，见上面的请求示例）。**服务端只做两件事：回实际的号，并把 `predicted` 原样带回去**——不一致由页面比出来并写成那句话。**"预演不是承诺"保证的是实际号本身**：客户端无论如何都只能拿到服务端算出来的那个数，而不必相信自己的预演。（2026-10-25 核实后改口：原写的是"服务端一比、不一致就如实回"，而约定里没有那个字段。）
- **复用既有的发布用例**：指针前移、审计、元数据投影、幂等仍然只有一份实现；批量只负责循环与汇总。
- **权限按条判定**：每条仍走既有的发布用例（`SkillVersionService`，`maintain` 以下抛 `NotPermittedException`）——**批量端点必须逐条接住它、翻成这一条的结果**，否则第一条没权限就会让整批 403。够不着的条目在结果里失败，不是整批 403。**队列不列发不了的行**（`GET /web/publish-queue` 在服务端就筛掉——响应里没有档位字段，页面也筛不了），所以这一条平时不会命中——它守的是页面打开之后权限被撤掉的那一窗口。
- **同一批里同一个 skill 只出现一次**：重复则**整批 400 `duplicate_target`**，先于任何一条发布。这是请求级校验而不是逐条失败——一份 skill 在一批里发两次，"哪一份算数"本来就没有答案，逐条判只会让第二条悄悄盖掉第一条。页面上第二份勾不上（PRD 做 #6），所以撞上它的是调用方或一个过期的页面。

## CLI

| 命令 | 形态 |
|---|---|
| `skillmaster skill submit … --release <major\|minor\|patch>` | 新旗标，可选 |
| `skillmaster version release-type <ns>/<name>@<版本码> <major\|minor\|patch\|none>` | 新命令，`none` 清空；**权限 `write`**（比改草稿名宽一档，见上） |
| `skillmaster skills pending [--json]` | 新命令（ADR 0081 的对应）：打 `GET /api/v1/author/publish-queue`，形状与队列那条读**同一份**（`skills` + `truncated`）。`--json` 原样输出服务端答案 |
| 读 | 草稿详情多一个字段（`release_type`，`version list --json` 与 `version show` 都带上）；`skills list --show draft` 不新增列（列表够挤了），详情里给 |

**帮助里要写清一句**：类型不是版本号，号在发布时才算。否则 agent 会以为 `--release minor` 会得到一个确定的数字。

## 界面

**这一屏的每一块都要先进设计稿**（2026-10-25 已补：`design/` 的待上线页与确认/执行/结算三态）。下面是设计稿必须表达的东西，逐行对。

| 区域 | 必须表达 |
|---|---|
| 页头 | 命名空间**单选**（没有"全部"，每项带自己的待发份数）。**选项只来自有草稿的命名空间，默认是其中按名字排序的第一个**，且默认那个不写进地址（`/publish` 就是它，`?ns=yuhan` 才是另一个）——这样同一个状态只有一个地址；**搜索**（skill 名或版本名称）；**按草稿状态筛选**（全部 / 缺少版本类型 / 有多份草稿）；**全选**（每个能算号的 skill 取最新一份，不覆盖已选） |
| 队列行 | **一行一份草稿**（一行 = 一个预演号）。skill 名与草稿的版本名称在同一格：只有一份时一行说完；攒了多份时上面多一行表头（skill 名 ＋「N 份草稿」＋ 当前线上版本），底下每份草稿各占一行。每份草稿那一行给：版本名称、**这一份的版本号预演**、提交时间 |
| 需要类型的行 | 行上选一档；**选之前勾不上**；选完立刻写回服务端（不是客户端状态，否则 CLI 与控制台各说一套）。**已经选过类型的那一行，号本身就是那颗菜单的触发器**：点开换一档、号立刻重算（PRD 做 #4 / 验收 9） |
| 两种「勾不上」的区别 | 「缺类型」是选一档就完事：**选之前勾不上，选了就能勾**，行上给选择器；「基线不可用」（预发布 / 线上没有号或不是一个版本）换一档也没用，**根本勾不上**，行上只给一句话、不给选择器。区别就在那颗选择器在不在——合起来写成"三种都勾不上"会把缺类型那一行说成没救的 |
| 从未上线的行 | 预演固定 `1.0.0`，不提供选择 |
| **基线是预发布的行** | 号算不出来（ADR 0091 决定 5）。设计件的夹具**故意不给它类型**，用来证明「基线不可用」排在「缺类型」之前：选了类型也照样算不出号。行上写「基线为预发布版本，需指定版本号」，**不提供类型选择**——它缺的是一个号，换一档解决不了；**也勾不上** |
| **同批已选过这个 skill 的行** | 勾不上，行上写「同批已选择该 skill 的另一份草稿」（一批里同一个 skill 只能发一份，PRD 验收 24） |
| **两个历史性的空值** | 上线过但没有版本号的历史行：状态那一格**空串写 `—`**（**不是**「从未上线」），**线上那一版不是合法 semver 的照原样显示**（`已上线 @1.0`）；两种都写「线上版本没有号，需指定版本号」、**都不提供类型选择器**（换一档也算不出号）；v6 之前的草稿没有名字，写「未命名」 |
| 选择 | 勾选态；动作条报「已选 N 份」（**没选时不画这条动作条**，不是画成禁用）。**全选在页头**，不在动作条里（见上一行） |
| 动作条 | 「上线所选」；没选时整条不画。**全选之后还要说清它做了什么**：「· N 个 skill 取最新草稿、M 份未指定版本类型」——它是那几行被选中的理由（PRD 做 #6 / 验收 11） |
| 空态 | 一句「没有待上线的草稿。提交新版本后，尚未上线的草稿会出现在这里。」——**不是空表格，页头也不挂计数**（验收 10）。筛到 0 行是另一句：「没有符合条件的草稿。请调整搜索词或筛选条件。」 |
| 加载中 / 读失败 | 「加载中…」与「无法加载待上线的草稿，请稍后重试。」两句；失败那一句穿危险色，且不与上面「号变了」那条说明混用同一个颜色 |
| 截断提示 | `truncated` 为真时，页头计数旁边一句「只列出了最近 500 个 skill」（PRD 验收 19） |
| 确认单 | **逐行**「skill · 版本名称 → 版本号」；ADR 0090 的可接受性条件。它一打开就是 480px 宽（四列放不进 420px），从确认态起就比普通确认单宽一档 |
| **执行中** | 确认之后**同一张单子不关**：逐条落定（成的写实际号、败的写原因），标题下一行是「已落地 / 总数」的进度；**进度只数已经落地的条数**，不按时间估 |
| **结算** | 走完停在单子上：一句话说完成什么样（`3 份已上线，1 份失败`），只有一颗「完成」；关掉之后成功的离开队列、失败的留下 |
| **执行中关不掉** | Esc 无效、没有关闭入口——按钮换成一颗不可点的「正在上线…」。结算态不是这样：那里 Esc 等于「完成」（验收 20） |
| **流断了** | 连接断在 `done` 之前：已经落地的行照旧有结果，剩下的**没有结果**（既不是成功也不是失败，那几行回到"还没轮到"的样子）；标题**三段各说各的**「N 份已上线，F 份失败，M 份没有结果」，为零的那段不写（已经写着失败原因的行不能被并进"没有结果"）；正文**先交代号变了那一句（如果有：断之前落地的那一行，号与预演不同照样要说，验收 16）**，再接一句「连接中断了：已上线的不会撤回，其余的请重新上线。」。**判据是"有没有行没回来"，不是"请求有没有报错"**：只丢了 `done` 那一行的，每一行都说过话了，按普通结算说（2026-10-25 定，设计件的 `&publish=broken`） |
| **长批次** | 单子自己滚（`max-height:44vh`）：执行中**正在走的那一条居中**，结算**回到第一条**；下面还有时底边淡出，到底了就撤掉 |
| 结果 | 成功的离开队列；失败的留下带原因；算出来的号与预演不同时**要说出来**——那句横幅还要交代**留在单子上的同组草稿**（它们的基线跟着变了，预演也要跟着重算） |

**两处最需要设计**：

1. **部分失败的呈现**——一条成功一条失败时，屏幕不能只弹一句「部分失败」。
2. **预演与结果不一致**——确认单上写 `1.3.0`，实际发了 `1.4.0`。这是少见但会吓人的一刻，需要一句人话解释"你确认之后有人发了更高版本，所以这次落在 1.4.0"。

**执行单的形状由接口决定**（2026-10-25 定）：逐条流式的返回，界面才有一条一条落定的东西可说。若服务端一次返回，界面只能退化成"整体转圈 → 一起变色"——那种实现**不必另做一版设计**，把每一条的落定时刻改成同一刻即可；但它是备选，不是目标。

## 分期

| 期 | 交付 | 验收（这一期自己能验的那一半） |
|---|---|---|
| P0 服务端（类型 + 队列读） | V29 迁移、**五条路由**接 `release_type`（CLI 的两条提交、改草稿的**两个面**、发布这一条）、算号规则与新错误码、`GET /web/publish-queue`（含作者面那条给 CLI 的 `GET /api/v1/author/publish-queue`） | 1–7（服务端）、8（读的形状）、25 |
| P1 服务端（批量） | `POST /web/skills/publish-batch`（逐条流式返回） | 14（服务端）、17、18②、20（服务端）、24（服务端） |
| P2 CLI | `skill submit --release`、`skillmaster version release-type`（两个面）、`skills pending` | 1、2、3、8（CLI 那一半） |
| P3 设计稿 | 队列页 + 行上选类型 + 多选 + 动作条 + 确认单 + **执行单 + 结算** + 部分失败 + 预演不一致 | — |
| P4 控制台 | 队列页与批量动作，从设计件搬 | 8（页面）、9–13、14–16（页面）、18①、19、20（页面）、21–23、24（页面）、26 |

P0 必须先于 P1 与 P2（批量算号与 CLI 打的是同一批接口）。P1 与 P3 无依赖，可以并行。**P4 等 P3，也等 P0 与 P1**——它吃队列那条读与批量那条写。

## 开放问题

> 1、2、4 三条已经定了（留在原处是因为它们定义了这一页的形状）；仍待回答的只有 3。

1. **队列放在导航的哪里？** **已定（2026-10-25）**：挂在「管理」下面、排在「标签」前面，标签写**「待上线」**——它是日常的工作队列，不是库的一种筛选（筛选是"同一批东西换个看法"，队列是"要去处理的事"），但它与「标签」同属"管我这一摊"，所以不与「我的 skill」并列、也不占一级项。界面词的取舍见 [`design/voice-spec.md`](../../../design/voice-spec.md) §三。
2. **队列要不要显示别人提交的草稿？** **已定（2026-10-25）**：显示，只要我够得着发布它——`maintain` 的人要知道自己有一份等着处理，藏起来会让他不知道自己欠着什么。但**我只到 `write` 的 skill 整条不进来**（[ADR 0090](../../decisions/0090-batch-publish-confirms-once.md) 决定 7）。来源标记本版不做：队列里每一份都是要我做决定的，署名是下一层的细节。
3. **一次最多能选多少条？** 当前倾向不设上限，靠 500 条自然收敛。若 IT 发现单批耗时不可接受，再引入分批——但那会带来"第 2 批失败时第 1 批已经发了"的呈现问题。
4. **草稿列表要不要显示类型？** **已定（2026-10-25）**：列表不加列，详情里给（`version show` / `version list --json` 带 `release_type`）。**队列那一页例外**——那里一行就是一份草稿，"它能不能发"正是这一页的问题，所以类型在行上。
