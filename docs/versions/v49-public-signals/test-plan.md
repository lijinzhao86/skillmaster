# v49-public-signals · 测试方案

> **最后更新**：2026-11-06
> **所属版本**：[`README.md`](README.md)
> **状态**：控制台 P3 已实现（2026-11-06），文件行数闸已过；T7 的人工对照仍未执行

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| A1 公开 skill 有一条来源，页面与 CLI 说清类型、定位、许可证、对应版本 | T1 | 通过（自动；真实 GitHub 手工冒烟未执行） |
| A2 加一种来源不需要加列、改页面分支 | T2 | 通过（通用表与假适配器验证；第二个真实平台按 PRD 不做 #6 留到同步那版） |
| A3 来源指标缺失不拖垮页面 | T3 | 部分通过（CLI 与页面侧已验；超时/429 未单独构造） |
| A4 信息页首屏只答「它是什么」 | T4 | 通过（自动断言；人工未看渲染） |
| A5 使用信号按账号去重，不暴露个人 | T5 | 通过（自动） |
| A6 使用信号只给分桶，不下发精确账号数 | T6 | 通过（自动） |
| A7 CLI 与控制台同值 | T7 | 未执行（同一实例上的人工对照还没做） |
| A8 既有公域行为不变 | T8 | 通过（自动测试） |
| A9 设计件与源码一致 | T9 | 通过（自动） |
| A10 文档检查全绿 | T10 | 通过（自动） |
| A11 许可证四种状态说法正确 | T11 | 通过（自动） |
| A12 下载/安装带 `.skillmaster/` 侧车，正文不污染 | T12 | 部分通过（zip 归档已验证；`skill get` 走同一归档） |
| A13 CLI 不打印相似度 | T13 | 通过（自动测试） |
| A14 默认头像稳定 | T14 | 通过（自动） |
| A16 申请更高档位要先入库 | T16 | 通过（自动测试） |
| A17 度量与公域解耦 | T17 | 通过（自动 + 评审） |

## 用例

### T1 · 来源卡：GitHub 来源在页面与 CLI 都说清

- **前置**：本地库 `skillmaster_v49` 已迁移到最新；把仓库 `coreyhaines31/marketingskills` 的 `skills/ab-testing` 导入官方命名空间，得到 `skillmaster/ab-testing`；SKILL.md 带 `metadata.source.kind=github`、`locator=coreyhaines31/marketingskills`、`path=skills/ab-testing`；GitHub 假服务返回 `stargazers_count=1234`、`forks_count=45`、`pushed_at`、`license.spdx_id=MIT`。
- **步骤**：读控制台信息页与结果卡片；跑 `skmr skill show skillmaster/ab-testing`。
- **期望**：信息页的「来源与许可」展开后只有两项——来源 `GitHub · coreyhaines31/marketingskills`（仓库是到 GitHub 的链接）与许可证（`MIT`、版权行、许可全文链接）；允许与义务只在 `skmr skill license`；commit、指标与最近校验只在 `skill show` 里出现，信息页不画它们。结果卡片带 `origin_label`、`origin_kind`、`origin_locator`、`origin_url`、`license_spdx`、`license_status`，悬停名是 `label · locator`，来源标识的点击目标是 `origin_url`。
- **判定**：通过 / 失败（失败时贴两处输出）。

### T2 · 加一种来源只加适配器

- **前置**：代码里有 `SourceAdapter` 注册表。
- **步骤**：① 代码评审：`skill_source` 只有 `kind/role/locator/path/label/url` 通用列，没有 `github_*` / `skillhub_*` 列；CLI `skill show` 按 `signals` 数组渲染，控制台只显示「平台 · 仓库」。② 加一个测试用 `FakeSourceAdapter`（kind=`fake`），不写迁移、不改页面，发布一个带 `source.kind=fake` 的 skill。
- **期望**：迁移不新增；`skill show` 正常显示 fake 来源与其 signals，控制台只显示「平台 · 仓库」；只有适配器与测试文件变化。
- **判定**：通过 / 失败（失败时贴新增文件清单与 schema diff）。

### T3 · GitHub 失败不拖垮来源卡

- **前置**：T1 的 skill；让假 GitHub 服务分别返回 404、超时、429。
- **步骤**：发布后读来源卡与 `skill show`；再读一次缓存。
- **期望**：页面仍显示平台、仓库与许可证（指标本来就不上这一页，所以不受影响）；`skill show` 省略指标行、不报错；页面不整体报错；不会重试到阻塞读。
- **判定**：通过 / 失败。

### T4 · 信息页首屏只答「它是什么」

- **前置**：一个已入库的 skill；调用者所在的档位上面还有更高一档。
- **步骤**：打开信息页；点开「来源与许可」；对照 `skill show` 的来源与许可两段。
- **期望**：首屏只有描述、适用场景、运行环境；来源与许可默认收起，展开后仓库与许可证与 `skill show` 一致（commit 只在 `skill show`）；申请是一行「需要更高权限？」，展开才是档位与说明；使用量不单独占一块，页头证据行按数据画。
- **判定**：通过 / 失败。

### T5 · 使用信号按账号去重

- **前置**：两个账号 A、B；一个公开 skill 与一个私有 skill；A 同一天读公开 skill 3 次、私有 skill 1 次；B 读公开 skill 1 次；作者自己读公开 skill 2 次。
- **步骤**：分别以 A、B、作者身份读根 `SKILL.md`；数据库层查 `skill_usage_day`（按 `scope='public'`）、`count(DISTINCT account_id)` 与 `skill_usage_total.calls`；再读 API 的 `usage`。
- **期望**：`skill_usage_day` 只有 A、B 两行，且 `scope='public'`；私有 skill 没有行；数据库层不同账号数为 2；`skill_usage_total.calls=4`（A 的 3 次 + B 的 1 次，作者两次不计）；API 的 `bucket` 是「少于 10 个账号」，**没有 `invokers_30d` 或任何精确账号数**；`calls_bucket` 是「少于 10 次调用」；API 不返回账号。
- **判定**：通过 / 失败。

### T6 · 低于阈值不显示精确数

- **前置**：3 个不同账号用过；加入数 2。
- **步骤**：读卡片、`skill show` 与 API 的 `usage`。
- **期望**：卡片的 `usage_bucket` 与 `skill show` 的 `usage.bucket` 都是「少于 10 个账号」；卡片的 `calls_bucket` 与 `skill show` 的 `usage.calls_bucket` 都是「少于 10 次调用」；API 不下发任何精确账号数或精确调用次数；CLI 打印这两个分桶；控制台不展示账号分桶（A5）；加入数照常显示；没有记录时显示「还没有人用过」。
- **判定**：通过 / 失败。

### T7 · CLI 与控制台同值

- **前置**：T1、T5 的数据。
- **步骤**：同一 skill 的控制台信息页、`skmr skill show`、`skmr skills search --verbose` 三处对照。
- **期望**：三处读同一组 API 字段；来源 label/locator、许可证 SPDX/状态、累计调用分桶一致；CLI 可以多打印账号分桶与最近使用（控制台按 A5 不展示），但两处都不出现精确账号数或精确调用次数。
- **判定**：通过 / 失败。

### T8 · 既有公域行为回归

- **前置**：测试库可重建。
- **步骤**：`./mvnw verify`；控制台 `npm test`。
- **期望**：全绿；公域只搜不列、加入、信息页、可见性规则不变。
- **判定**：通过 / 失败（失败时贴测试类）。

### T9 · 设计件与源码一致

- **前置**：`design/` 已 `npm ci`。
- **步骤**：`npm run build:home && npm run build:console`；`console-states --only discover`；`--only skill`；`npm run type-check`。
- **期望**：两个演示件与源一致；两组检查组全绿；type-check 退出 0。
- **判定**：通过 / 失败。

### T10 · 文档检查

- **前置**：代码与文档改动完成。
- **步骤**：`check_docs.py`、`impact.py --check`、`generate.py --check`、`check-file-size.mjs`、改动目录 `check_comments.py`。
- **期望**：全部退出 0；生成视图与源一致。
- **判定**：通过 / 失败。

### T11 · 许可证四种状态

- **前置**：四份 fixture：
  1. `ok`：skill 目录内有 MIT LICENSE + 版权行；
  2. `declared-only`：只有 SKILL.md `license: MIT`，无全文；
  3. `missing`：仓库与 SKILL.md 都没有；
  4. `other`：`LicenseRef-Custom`，有全文链接。
- **步骤**：逐个导入 / 解析；读 `skill show`、`skmr skill license <地址>` 与信息页；检查 `.skillmaster/license.json` 的 `status`。
- **期望**：信息页只写许可名——`ok` 加版权行与站外全文链接，`declared-only` 只写名字，`missing` 留空（摘要只写来源、展开不画许可项），`other` 给「其他许可」+ 全文链接；状态词与允许/义务只在 `skmr skill license`（`declared-only` 才有「仅声明，未附全文」），`skill show` 只给一行「许可证：MIT」；`missing` 不默认 MIT；`skill license` 不打印全文本身。声明与来源文件不一致时**用文件的值**，本版不出现冲突状态。
- **判定**：通过 / 失败。

### T12 · `.skillmaster/` 侧车

- **前置**：T1 的 skill；上游正文没有 LICENSE（根 LICENSE 是 MIT）。
- **步骤**：① 浏览器下载 zip，列包内文件；② `skmr skill get <地址> <目录>`；③ `skmr skill ls`；④ `skmr skill invoke <地址>`，确认没有写任何文件。
- **期望**：zip 与落地的目录里都有 `.skillmaster/license.json` 与 `.skillmaster/attribution.md`；上游没有许可文件时另有 `.skillmaster/LICENSE`；`license.json` 含 `spdx`、`copyright`、`status`、`origin`、`license_file`；`skill ls` 默认不列 `.skillmaster/`；正文文件与上游一致，没有被写入 LICENSE；**`skill invoke` 不落盘、不产生侧车**。
- **判定**：通过 / 失败。

### T13 · CLI 不打印相似度

- **前置**：语义检索开启，且有一组能返回结果的问题。
- **步骤**：`skmr skills search "做PPT"` 与 `skmr skills search "做PPT" --verbose`；同时抓服务端响应。
- **期望**：两处输出都不含「相似度」；服务端响应仍有 `score`，排序不变；`skill show` 也不打印相似度。
- **判定**：通过 / 失败。

### T14 · 默认头像稳定

- **前置**：两条公开 skill，其中两条名字首字母相同（如 `pdf-tools` 与 `pdf-splitter`）；控制台已实现头像（P3）。
- **步骤**：刷新发现页两次，看同一条的首字母与底色；再比较两条首字母相同的 skill。
- **期望**：同一条两次刷新首字母与底色不变；不同名字可区分（首字母相同也尽量不同色）。本版没有官方图标（prd 不做 #12）。
- **判定**：通过 / 失败（附两次截图）。

### T16 · 申请更高档位要先入库（[ADR 0150](../../decisions/0150-requesting-a-higher-rung-needs-library-membership.md)）

- **前置**：一个公开、已上线、但不在调用者库里的 skill；调用者能看见它（公开档为调用或浏览）。
- **步骤**：① 调用者申请「写入」；② 打开详情页；③ 入库（`POST …/library` 或控制台的「入库」）；④ 再申请一次。
- **期望**：① 400 `invalid_request`，`details[].issue` 为 `not_in_library`；② 详情页没有「需要更高权限？」这一块，主按钮是「入库」；③ 入库成功；④ 同一申请 201，信息页出现「需要更高权限？」，按钮写「申请」。
- **判定**：通过 / 失败。

### T17 · 度量与公域解耦

- **前置**：M15 已实现；`ModuleMap` 里 `skill_usage_day` / `skill_usage_total` 归 M15。
- **步骤**：① 代码评审：`grep -rn 'skill_usage_' skillmaster-server/src/main/java`，除 `modules/metrics/**` 与 `ModuleMap` 外没有命中；② 读路径、三个响应类、控制台与 CLI 不出现度量表 SQL；③ 在测试里直接插入一行 `scope='owner'` 的 `skill_usage_day`，再用 `UsageQuery.snapshots(..., PUBLIC)` 查同一条 skill，跑 `./mvnw verify`；④ 确认公域代码零改动，并断言 `UsageScope.fromWire("nosuch")` 抛错。
- **期望**：① 除 M15 与 `ModuleMap` 外无命中；② 公域读路径只调 `UsageRecorder` / `UsageQuery`；③ public 查询看不到 `owner` 行，只改 M15 与其测试即可，`ArchitectureTest` 与 `TableOwnershipTest` 全绿；④ 未知 scope 在 M15 被拒绝，公域响应与页面对其他 scope 无感知。
- **判定**：通过 / 失败（失败时贴 grep 结果与改动文件清单）。

## 环境与数据

- **服务端测试**：在 `skillmaster-server/` 跑 `./mvnw verify`。测试读 `SKILLMASTER_TEST_DB_URL` / `SKILLMASTER_TEST_DB_USER` / `SKILLMASTER_TEST_DB_PASSWORD`；本工作区用 `skillmaster_test_v49`，每次重建 schema。
- **适配器测试**：用本地假 HTTP 服务返回 GitHub JSON；不访问真实 GitHub。真实 GitHub 只做一次手工冒烟（可选 token）。
- **本机端到端**：`skillmaster_v49` 库；服务端 8080；控制台 Vite 5173。导入样本用 `coreyhaines31/marketingskills` 的 `skills/ab-testing`（MIT，© 2025 Corey Haines），导入时保留根 LICENSE 全文与版权行。
- **控制台**：`cd skillmaster-console && npm ci && npm test && npm run type-check`。
- **设计件**：`cd design && npm ci`；命令见 T9。
- **CLI**：`cd skillmaster-cli && go test ./...`；端到端用本机服务端。
- **清理**：测试库可抛弃；本机库可重建；导入样本只落在本地，不碰生产。

## 结果

2026-11-05 的结果覆盖公域信号部分；使用度量（T5 / T6 / T17）在 2026-11-06 按 M15 重构后重跑；控制台 P3（T4 / T14 与 A5、A6、A11 的展示部分）在 2026-11-06 补齐。T7 的人工对照仍未执行，T4 的自动断言已过但没有人看过渲染。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过（自动） | `SkillDetailIT.aPublicSkillCarriesItsSourceLicenseAndUseSignalsOnEveryRead`、`show_signals_test.go`、`skills.test.tsx` 的「来源与许可」展开（仓库链接、许可名与版权行、commit 与指标不上页面）。真实 GitHub 仓库未导入，`fetch` 由 `GitHubSourceAdapterTest` 的本地 HTTP 服务覆盖。 |
| T2 | 通过 | `V36__public_signals.sql` 只有 `kind/role/locator/label/url/path` 通用列；`SourceServiceTest` 用 kind=`github` 的假适配器走完全部合并路径，没有新增迁移。第二个真实平台按 PRD 不做 #6 留到同步那版。 |
| T3 | 部分通过 | `GitHubSourceAdapterTest.aMissingRepositoryIsAnErrorSnapshotNotAThrow`（404）；`SourceServiceTest.aFailedRefreshMarksAWorkingSnapshotStaleInsteadOfErasingIt`（失败保留上次成功值、标 `stale`）；服务端在 `status` 非 `ok` 时不下发 `signals`；`skill show` 因此省略指标行；`skills.test.tsx` 断言指标缺失时信息页仍显示平台与仓库。超时/429 未单独构造。 |
| T4 | 通过（自动断言） | `skills.test.tsx`：首屏只有描述 / 适用场景 / 运行环境；「来源与许可」默认收起（`open` 不存在）；申请是一行未展开的「需要更高权限？」；使用量不单独成块，页头证据行显示分桶。**人工未看渲染。** |
| T5 | 通过（自动） | `UsageRepositoryIT.oneDayRowButEveryLoadCounts`（同日去重、每次加载计数）与 `anotherScopesRowsDoNotLeakIntoThePublicSeries`（scope 隔离）；`SkillContentIT.loadingTheBodyCountsOneUsePerAccountPerDay`（两次正文读一行）与 `aPrivateOrAuthorReadIsNotAPublicUse`（私有 / 作者读不记）；`SkillDetailIT` 断言响应里没有精确账号数；`skills.test.tsx` 断言页头证据行显示的是分桶。 |
| T6 | 通过（自动） | `UsageRepositoryIT.theAccountCountIsWindowed`（窗口内 9 个 + 窗口外第 10 个 → 分桶仍为「少于 10」）；`SkillDetailIT` 断言 1 个账号时 `bucket=少于 10 个账号`；`show_signals_test.go` 的分桶说法；`discover.test.tsx` 的行尾调用分桶与来源标识标题。 |
| T7 | 未执行 | 三处都读同一组 API 字段；同一实例的控制台、`skill show`、`skills search --verbose` 人工对照没做。 |
| T8 | 通过 | `./mvnw verify`：598 个集成测试 + 340 个单元测试，0 failures；`npm test`：685 tests，0 failures。 |
| T9 | 通过 | `console-states --only discover` 84 项 0 failures；`--only skill` 156 项 0 failures；`npm run type-check` 退出 0。 |
| T10 | 通过 | `check_docs.py`、`impact.py --check`、`generate.py --check`、`check-file-size.mjs` 全部退出 0；三个超基线文件已拆小或压缩到基线以下。 |
| T11 | 通过（自动） | `GitHubSourceAdapterTest.theLicenseInTheSkillDirectoryWinsOverTheRepositoryRoot` 与 `aLicenseInAParentDirectoryIsFoundByWalkingUp` 验证「最近的 LICENSE 优先」；`SourceServiceTest` 覆盖 `ok / declared-only / missing / other` 的合并结果，并验证声明与来源文件不一致时用文件的值；`show_signals_test.go` 逐状态验证固定说法（`skill show` 不打印 `NOASSERTION`）；`search_v19_test.go` 验证 `--verbose` 对 `missing` / `other` / `declared-only` 也不泄漏哨兵值；`license_test.go` 验证 `skill license` 从 `/metadata` 取块并输出（含旧服务端没有许可块的情况）；`skills.test.tsx` 断言信息页 `missing` 留空、`declared-only` 只写许可名、两者都不编造允许与义务；`PublicSignalsMigrationIT` 验证 V36 回填的存量官方版本由 V38 补上 `declared-only`（声明来自版本自己的 frontmatter）。 |
| T12 | 部分通过 | `LicenseSidecarTest` 与 `ArchiveIT.anArchiveCarriesTheLicenseSidecarBesideTheContent`：zip 内有 `.skillmaster/license.json`、`LICENSE`、`attribution.md`，内容摘要不变；`skill get` 下载同一归档。浏览器与 `skill get` 的实机下载未执行。 |
| T16 | 通过（自动） | `AccessRequestApiIT.aPublicSkillOutsideTheLibraryCannotBeAskedFor`（400 `invalid_request`，`details[].issue = not_in_library`，入库后 201）；`WebSkillMetadataIT` 的两条申请用例都先入库；`skills.test.tsx` 的库外无申请块；`console-states.mjs` 的 `in=visible` 无申请块检查。 |
| T13 | 通过（自动） | `search_v19_test.go` 在默认与 `--verbose` 下都断言不含「相似度」。 |
| T14 | 通过（自动） | `SkillAvatar` 由整条名字取色；`discover.test.tsx` 断言 `pdf-tools` 与 `pdf-splitter` 首字母都是 P、底色不同。本版没有官方图标（prd 不做 #12），`console-states` 断言每一行都是默认头像。**人工未看渲染。** |
| T17 | 通过（自动 + 评审） | `grep -rn 'skill_usage_' src/main/java` 只命中 `modules/metrics/**` 与 `ModuleMap`；`ArchitectureTest`（6）与 `TableOwnershipTest`（5）全绿；`UsageRepositoryIT.anotherScopesRowsDoNotLeakIntoThePublicSeries` 用直接写入的 `owner` scope 行证明新增 scope 不需要改公域代码。 |

## 已知问题

本版范围内没有未修缺陷；范围外的既有缺陷见 [`known-issues.md`](known-issues.md)。
