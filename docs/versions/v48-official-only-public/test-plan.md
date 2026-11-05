# v48-official-only-public · 测试方案

> **最后更新**：2026-11-05
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| A1 非官方设 `public` 被拒绝，原值不变 | T1、T2、T3 | 通过 |
| A2 官方命名空间仍能公开与收回 | T4、T12 | 通过（T12 是列判定，不是 HTTP 第二命名空间） |
| A3 控制台非官方没有公开选项，官方仍有三档 | T5、T13 | 通过 |
| A4 `can_publish_public` 正确且只读 | T6 | 通过 |
| A5 存量迁移后非官方公开行为零，官方不受影响 | T7 | 通过 |
| A6 迁移后非官方 skill 不再出现在公域 | T8 | **部分执行**：公开搜索与 `/api/v1/skills?scope=public` 已验证；`/web/discover` 页面与公开标签计数未单独驱动 |
| A7 私有、共享、授权等既有行为不变 | T9、T14 | 通过 |
| A8 设计件与源码一致，对应设计检查组全绿 | T10 | 通过 |
| A9 文档检查全绿 | T11 | 通过 |

## 用例

### T1 · 非官方命名空间设「公开 · 调用」被拒绝

- **前置**：一个非官方账号 A，拥有私有 skill `dev/one`，A 在它上面是 `owner`（`admin` 档以上）；该 skill 初始 `visibility=private`、`public_rung=NULL`。
- **步骤**：以 A 的身份 `PATCH /web/skills/dev/one/visibility`，请求体 `{"visibility":"public","public_rung":"invoke"}`。
- **期望**：403，响应错误码 `public_is_official_only`；库里两列仍是 `private` / `NULL`；没有新增 `audit` 的 `visibility` 事件。
- **判定**：通过 / 失败（失败时记录实际状态码、错误码与库里的两列）。

### T2 · 非官方命名空间设「公开 · 浏览」被拒绝

- **前置**：同 T1。
- **步骤**：请求体改成 `{"visibility":"public","public_rung":"browse"}`。
- **期望**：同 T1；两条公开档位都被挡住，不是只挡其中一条。
- **判定**：通过 / 失败。

### T3 · 非官方仍可把历史公开行收回私有

- **前置**：用 SQL 直接造一条非官方命名空间的 `visibility='public'`、`public_rung='invoke'` 的 skill（模拟迁移前的历史行），调用者在它上面是 `owner`。
- **步骤**：`PATCH` 请求体 `{"visibility":"private","public_rung":null}`。
- **期望**：204；库里 `visibility='private'`、`public_rung=NULL`。公开被挡不影响收回私有，历史行也有出路。
- **判定**：通过 / 失败。

### T4 · 官方命名空间仍能公开、改档与收回

- **前置**：`skillmaster` 命名空间下的一个私有 skill，调用者是它的 `owner`（系统账号或测试里构造的官方账号）。
- **步骤**：① 设 `public` + `invoke`；读作者面；② 设 `public` + `browse`；③ 设 `private`。
- **期望**：三步都 204；① 读到 `visibility=public`、`public_rung=invoke`；② 读到 `browse`；③ 读到 `private`、`public_rung` 为空。
- **判定**：通过 / 失败。

### T5 · 控制台按能力渲染两种设置页

- **前置**：控制台测试里分别 mock 两个作者面响应：一个非官方 skill（`can_publish_public=false`），一个官方 skill（`true`）。
- **步骤**：渲染非官方的「设置 › 常规」；再渲染官方的同一页。
- **期望**：非官方只有「私有」一个选项，并出现「公开目前只对官方 skill 开放」；官方有「私有 / 公开 · 调用 / 公开 · 浏览」三个选项。
- **判定**：通过 / 失败（失败时贴渲染出的选项标题与说明）。

### T6 · `can_publish_public` 正确且只读

- **前置**：同 T4 与 T1 的两个 skill。
- **步骤**：① 读两次作者面响应；② 在 `PATCH visibility` 的请求体里伪造 `can_publish_public=true` 再请求。
- **期望**：① 官方为 `true`、非官方为 `false`；② 伪造字段被忽略，非官方仍按 T1 返回 403；服务端判定不来自请求体。
- **判定**：通过 / 失败。

### T7 · 迁移把非官方公开行清零

- **前置**：测试库里造五行：2 条非官方公开（`invoke` / `browse`）、2 条官方公开（`invoke` / `browse`）、1 条非官方私有；每条都有版本行或至少完整的 skill 行。
- **步骤**：从 V34 开始跑 Flyway 到最新（或直接应用 V35 迁移）。
- **期望**：非官方两条变成 `private` / `NULL`；官方两条仍是 `public` 且档位不变；私有那条不变；`skill`、`skill_version`、`skill_library_join` 的行数不减；新约束 `skill_public_rung` 不报错。
- **判定**：通过 / 失败（失败时记录迁移前后的五行状态与行数）。

### T8 · 迁移后非官方 skill 退出公域

- **前置**：T7 的数据；服务端连这个库起来。
- **步骤**：① `GET /api/v1/skills?scope=public&q=<名字>&semantic=false`；② 打开 `/web/discover` 按同一关键词搜；③ `GET /web/public-tags` 看计数；④ `skmr skills search "<名字>" --scope public --semantic=false`。
- **期望**：四条路都不再出现非官方那两条；官方两条仍在；公开标签计数不再包含它们。
- **判定**：通过 / 失败。

### T9 · 既有可见性、共享与授权行为回归

- **前置**：服务端测试库可从空库重建。
- **步骤**：`./mvnw verify`。
- **期望**：全绿，包含可见性、共享邀请、授权档位、公域搜索的既有测试。
- **判定**：通过 / 失败（失败时贴失败的测试类与用例）。

### T10 · 设计件与源码一致，设置页检查组全绿

- **前置**：`design/` 已 `npm ci`。
- **步骤**：`npm run build:home && npm run build:console`；`git status`；`node design/scripts/console-states.mjs "design/skillmaster-控制台演示.html" artifacts/design/console --only settings`；`--only skill-settings`。
- **期望**：两个演示件与源一致（`git status` 对它们没有未预期差异）；两组检查全绿；`npm run type-check` 退出 0。
- **判定**：通过 / 失败（失败时贴检查组名与失败项）。

### T11 · 文档结构、引用与生成视图

- **前置**：代码与文档改动完成。
- **步骤**：`python3 .claude/skills/docs-architecture/scripts/check_docs.py`；`python3 scripts/docs/impact.py --check`；`python3 scripts/docs/generate.py` 后再 `git status`；`node scripts/check-file-size.mjs`。
- **期望**：前两条退出 0；生成后工作区没有未预期差异；文件行数检查通过。
- **判定**：通过 / 失败。

### T12 · 官方性是命名空间上的一列，不是可注册的 slug

- **前置**：测试库里有种子命名空间 `skillmaster`，以及一个随机注册出来的命名空间。
- **步骤**：① 查两行的 `namespace.official`；② 理解注册路径：它不写 `official`，用列默认值 `FALSE`。
- **期望**：`skillmaster` 为 `true`，随机 handle 为 `false`。官方性是迁移/种子写下的一个列值，不是一份可以被抢先注册的 slug 清单。
- **判定**：通过 / 失败。

### T13 · 滚动部署窗口里的防御态

- **前置**：控制台测试 mock 一个作者面响应：`visibility=public`、`can_publish_public=false`（只在旧实例刚写进去、或手工撤掉标记时出现）。
- **步骤**：渲染「设置 › 常规」。
- **期望**：不出现公开两档；出现「这个 skill 目前是公开的，但所在命名空间已不能再设公开」的说明；「私有」仍可选，让作者能收回。
- **判定**：通过 / 失败。

### T14 · 既有的 404、403 与 400 不变

- **前置**：一个非官方 skill；一个看不见它的调用者；一个看得见但只有 `browse` 档的调用者；一个 `admin` 档的调用者。
- **步骤**：① 看不见的调用者 `PATCH visibility`；② `browse` 档的调用者 `PATCH visibility`；③ `admin` 档的调用者用 `{"visibility":"nope"}`；④ `admin` 档的调用者用 `private` + `invoke`。
- **期望**：① 404 `skill_not_found`（不透露存在性）；② 403 `forbidden`（档位不够，不是命名空间策略）；③ 400 `invalid_request`；④ 400 `invalid_request`。四种都不是 `public_is_official_only`。
- **判定**：通过 / 失败。

## 环境与数据

- **服务端测试**：在 `skillmaster-server/` 跑 `./mvnw verify`。测试读 `SKILLMASTER_TEST_DB_URL` / `SKILLMASTER_TEST_DB_USER` / `SKILLMASTER_TEST_DB_PASSWORD`；本工作区的库是 `skillmaster_test_v48`，测试每次重建 schema，撞号直接重建，不改 Flyway 历史。
- **本机端到端**：`source .env`（含 `SKILLMASTER_DB_URL` 指向 `skillmaster_v48`），按 [`.dev-sop/profile.md`](../../../.dev-sop/profile.md) §并行：本地环境 的方式跑服务端；控制台与 CLI 指向它。官方 skill 可用 V2 种下的 `skillmaster` 命名空间与系统账号；非官方账号用注册接口造。
- **控制台**：`cd skillmaster-console && npm ci && npm test && npm run type-check`；T5 用 mock 的作者面响应，不需要真服务端。
- **设计件**：`cd design && npm ci`；T10 的命令如上。截图落在 `artifacts/design/console`（gitignored）。
- **数据**：迁移用例（T7）用直接 SQL 造五行，不依赖生产；端到端用例（T8）用本机库。生产存量在需求阶段已盘点：公域 4 条，2 条官方 + 2 条 `skillmaster-test`。
- **清理**：测试库可抛弃；本机库 `skillmaster_v48` 可重建。生产不做测试，迁移只在部署时跑一次。

## 结果

2026-11-05 在本机跑完。服务端全量 `./mvnw verify`：**585 个用例，0 失败，0 错误，BUILD SUCCESS**（列设计重构后重跑，新增的 `VisibilityOfficialOnlyIT` 与 `PublicVisibilityMigrationIT` 都在其中）。T8 另起本机 v48 服务端做了一次公开搜索的端到端（见结果表）。控制台 **677 个用例全绿**，`npm run type-check` 退出 0。设计件 `--only settings` 与 `--only skill-settings` 全绿，`build:console` 前后演示件哈希不变。文档检查四条全绿。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `VisibilityOfficialOnlyIT.aPersonalNamespaceCannotPublishEitherRung`：两条公开档位都 403，错误码 `public_is_official_only`，库里仍是 `private` / `NULL`，`auditCount` 不变 |
| T2 | 通过 | 同上：`public + browse` 也 403 |
| T3 | 通过 | `VisibilityOfficialOnlyIT.privateIsAlwaysAllowedEvenOutsideTheOfficialNamespace`：先把历史行写成 public，再由非官方 owner 收回私有，204 |
| T4 | 通过 | `VisibilityOfficialOnlyIT.theOfficialNamespaceStillPublishesAndWithdraws`：官方账号 `invoke` / `browse` / `private` 三步都 204，库里的两列逐步正确 |
| T5 | 通过 | `skills.test.tsx` 的「draws only 私有…」（只有「私有」+ 说明）与「draws all three choices when the namespace can publish」（三个选项都在）；设计件的官方三档另由 T10 的 `settings` 检查组钉住 |
| T6 | 通过 | `VisibilityOfficialOnlyIT.theAuthorPageCarriesTheNamespaceCapability`：官方 `true`、非官方 `false`；`theCapabilityIsNotReadFromTheRequestBody`：请求体里伪造 `can_publish_public=true`，仍按 T1 返回 403 |
| T7 | 通过 | `PublicVisibilityMigrationIT.nonOfficialPublicRowsBecomePrivateAndNothingIsDeleted`：五行（2 官方公开 + 2 非官方公开 + 1 非官方私有）；官方两条保留且各自档位仍钉在 `invoke` / `browse`，非官方两条转 `private` / `NULL`，私有不动，`skill` 行数仍是 5；`namespace.official` 对种子的 `skillmaster` 为 `true`、对种子里的 `other` 为 `false` |
| T8 | **部分执行** | 本机起 v48 服务端（`skillmaster_v48` 库）后，把一条非官方 skill 用 SQL 置为 `public` / `invoke`：`skmr skills search "role-matrix-fixture" --scope public --semantic=false` 返回它；执行 V35 的清理 SQL 后，同一条命令返回「没有匹配的 skill」，`GET /api/v1/skills?scope=public&q=role-matrix-fixture&semantic=false` 返回空数组。**`/web/discover` 页面与 `/web/public-tags` 计数没有单独驱动，不能算全部验收** |
| T9 | 通过 | `./mvnw verify`：**585 tests, 0 failures, 0 errors, BUILD SUCCESS**（2026-11-05，列设计重构后） |
| T10 | 通过 | `npm run build:home` 后首页演示件无差异；`npm run build:console` 两次产物哈希一致；`console-states --only settings`（0 failed，含「两个分栏的链接都带着当下选的档与官方身份」）与 `--only skill-settings`（0 failed）；`git status` 里两个演示件只有本次源改动带来的那一处；`npm run type-check` 退出 0 |
| T11 | 通过 | `check_docs.py`、`impact.py --check`、`generate.py` 幂等（前后哈希一致）、`check-file-size.mjs` 退出 0；`check_comments.py` 对本版改动目录 0 处（全仓只报 `design/src` 里两处未改的历史叙述，属警告） |
| T12 | 通过 | `VisibilityOfficialOnlyIT.officialnessIsANamespaceRowNotAHandle`：`skillmaster` 行 `official=true`，随机注册出来的 handle 行 `official=false`。判定读的是列，不是一份可被抢先注册的 slug 清单 |
| T13 | 通过 | `skills.test.tsx` 的「shows the current public state when the namespace has lost the capability」：不画公开两档，整句出现「这个 skill 目前是公开的，但所在命名空间已不能再设公开。改回私有不影响已经发生的下载与复制。」，私有一项仍在 |
| T14 | 通过 | `VisibilityOfficialOnlyIT.aLowerRungStillGetsTheRungRefusalRatherThanThePolicyCode`：看不见 → 404；看得见但只有 `browse` → 403 `forbidden`，不是新错误码；`invalidVisibilityBodiesStillGetValidationErrors`：`visibility=nope` 与 `private` 带档位都 400 `invalid_request` |

## 已知问题

无。本版范围内的未修缺陷在执行后补记；本版范围外的历史缺陷进 `known-issues.md`。
