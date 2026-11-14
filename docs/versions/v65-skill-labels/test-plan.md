# v65-skill-labels · 测试方案

> **最后更新**：2026-11-14
> **所属版本**：[`README.md`](README.md)
> **状态**：P0 用例已执行；P1 / P2 用例随分期交付，尚未执行。

## 验收映射

| 验收 | 用例 |
|---|---|
| A1 · 13 个一级分类与旧 10 个映射 | T1、T10、T18 |
| A2 · 新增维度只插词表行 | T2、T6 |
| A3 · 每个 Agent 标签有来源/置信度/证据，无证据不落库 | T3、T11、T13 |
| A4 · 结果行只显示描述，预期结果不上列表 | T3、T17 |
| A5 · 信息页与 `skill show` 同一组字段 | T4、T9、T15 |
| A6 · 按属性筛选在网页与 CLI 一致 | T5、T8、T16 |
| A7 · 每周 30–50 条抽样、95% 准确率、金标集 | T12、T13 |
| A8 · 低置信不展示、中置信进异常队列 | T4、T11 |
| A9 · 首页与官网不再出现「1000+ 个精选」 | 既有 `home-check`（首页）+ 两个前端源码的文案 grep；不在本测试计划新增用例 |
| A10 · 分类迁移可 dry-run、可回滚 | T1、T18 |
| A11 · 列表/搜索/推荐三处字段一致 | T3 |
| A12 · 原 `description` / `when_to_use` / frontmatter 不改 | T1、T3、T14 |
| A13 · 运行环境多选、最多三枚 + `+N`、短名着色 | T3、T8、T9、T17 |
| A14 · `gpu` / `docker` / `database` 严格口径 | T11、T13 |
| A15 · 控制台不显示公域计数 | T6、T7、T10、T17 |
| A16 · 搜索结果与推荐可区分 | T7、T17 |

**覆盖说明**：A9 由既有 `home-check` 与文案评审覆盖，本版不新增重复用例；A7 的抽样与金标集属于 P2，P0 只把表和报告入口建好。

## 用例

### T1 · 分类迁移与回滚（A1、A10、A12）

- 前置：空测试库；Flyway 迁移到 V50；库里有一条 `skill.category_key = 'doc'` 的记录，一条 `'ops'`（旧 stopped）记录。
- 步骤：
  1. **先在停在 V50 的库上**跑 `skillmaster-server/scripts/dry-run-v65-category-migration.sh <库名>`：打印映射、13 个新词、每个新货架按快照统计的移入数，随后 `ROLLBACK`。
  2. 执行 V51 迁移。
  3. 查 `skill_category`：13 个新 key 为 `enabled`，旧 10 个为 `retired`。
  4. 查 `skill_category_mapping`：10 行，`doc → office-efficiency`、`ops → it-ops-security` 等全部可查；`skill_category_assignment_snapshot` 里两条 skill 各记着自己的旧 key。
  5. 查两条 skill 的 `category_key`：分别变为 `office-efficiency` 与 `it-ops-security`。
  6. 回滚：按迁移脚本注释先执行前两条 SQL（快照恢复 → **13 个新货架全部置空**），确认没有 skill 再指向新 key；再执行删除 13 行、恢复旧 10 行 `state` / `sort_order`、删两张辅助表。删除与词表状态恢复会改静态词表，**不在 IT 里跑**，由人在 V50 临时库上整段执行一次。
  7. 再查 `skill.category_key` 与旧词状态。
- 期望：dry-run 不写任何表（结束查不到 `skill_category_mapping`）；迁移后 13 + 10 行、映射 10 行、两条 skill 落到新货架；回滚按**每个 skill 自己的旧 key** 恢复（`doc` 与 `office` 合并后仍各回各的），迁移后新建的 skill 被置空而不是让 DELETE 撞外键，旧 state（含 `ops = stopped`）恢复；`description` / `when_to_use` / `frontmatter` 不由本迁移写入，前后面貌相同（由「V51 只更新 `category_key`」构造保证，未单独自动化）。
- 证据：`SkillLabelMigrationIT`（3 条：词表与映射、无旧指针、按快照恢复 + 13 键置空不留外键引用）；dry-run 与整段回滚在 V50 临时库实测（2026-11-14，见 T18 的数字）。

### T2 · 词表 seed 与「新增维度只插行」（A2）

- 前置：迁移完成。
- 步骤：
  1. 查 `label_definition`：`runtime` 22 行（21 非默认 + `prompt-only`）、`pricing_type` 2 行。
  2. 插入一行新维度 `input_type:file`（只插词表，不改表结构）。
  3. 调 `/api/v1/labels`。
- 期望：新行出现在读模型；无需新增 Flyway、实体列或代码分支；`prompt-only` / `free` 可筛但不展示。
- 证据：`SkillLabelApiIT`（P0）。

### T3 · 卡片读模型：outcome、labels、描述不变（A3、A4、A11、A12、A13）

- 前置：一条公域、已上线、`current_version_id` 指向 v1 的 skill；词表 seed；插入 `skill_label` 三行（Python auto、API key confirmed、prompt-only auto）与 `skill.outcome`。
- 步骤：
  1. 调 `GET /api/v1/skills?scope=public&q=<名字>`。
  2. 读 `skills[0].outcome` 与 `skills[0].labels`。
  3. 同一条再走 `/web/discover` 与推荐接口。
- 期望：`outcome` 原样出现；`labels` 只含 Python 与 API key，不含 `prompt-only` / `free`；`description` 与作者原文一致；三处字段一致；无标签时字段省略而不是空数组。
- 证据：`SkillLabelApiIT`（P0）。

### T4 · 元数据与低置信隐藏（A5、A8）

- 前置：同 T3，另有一条 `review_state='pending'` 的标签与一条 `stale` 的标签。
- 步骤：
  1. 调 `GET /api/v1/skills/{ns}/{name}/metadata`。
  2. 对照卡片响应。
- 期望：metadata 返回同一 `outcome` 与同一组可展示标签；`pending` / `stale` 不出现；`description` / `whenToUse` / `compatibility` 不变。
- 证据：`SkillLabelApiIT`（P0）。

### T5 · `label` 筛选与未知值（A6）

- 前置：两条公域 skill，一条 `runtime:python`，一条 `runtime:python` + `pricing_type:paid`。
- 步骤：
  1. `GET /api/v1/skills?scope=public&label=runtime:python`。
  2. `...&label=runtime:python&label=pricing_type:paid`。
  3. `...&label=runtime:nope`（未知值；`runtime:go` 是词表里的合法值，不用于这一条）。
  4. 只给 `label`、不给 `q`。
- 期望：1 返回两条；2 只返回同时满足两条的那一条；3 返回 400 且消息点名 `runtime:nope`；4 合法并返回结果，不要求关键词。
- 证据：`SkillLabelApiIT`（P0）。

### T6 · 属性词表（A2、A15）

- 前置：词表 seed。
- 步骤：`GET /api/v1/labels`。
- 期望：只含 `filterable=true` 且 `enabled/stopped` 的行；含 `key=dimension:value` 形状、短名、完整语义、分组、排序；不含任何公域计数；`retired` 不出现。
- 证据：`SkillLabelApiIT`（P0）。

### T7 · 控制台不显示公域计数、结果页有头（A15、A16）

- 前置：设计件已构建；演示数据含关键词结果与分类结果。
- 步骤：
  1. `console-states --only detail`：打开信息页分类菜单。
  2. `console-states --only my-tags`：打开公开标签页。
  3. `console-states --only discover`：关键词搜索、分类筛选、无结果、推荐页。
- 期望：分类菜单 `.menu-number` 为 0；公开标签行无 `.tag-count`、有「查看 skill」；搜索页头无 `N 个结果`、分页条无「共 N 个」；关键词结果标题「搜索结果」、小字「按相关度排序」；分类结果标题「筛选结果」、小字「办公效率 · 数据分析」；推荐页没有结果头；结果页六行仍不产生额外滚动条。
- 证据：`design/scripts/console-states.mjs`（P0 已执行）。

### T8 · CLI `skills search --label`（A6、A13）

- 前置：本地测试服务；两条带标签的公域 skill；登录令牌。
- 步骤：
  1. `skmr skills search --label runtime:python`。
  2. `skmr skills search --label runtime:python --label pricing_type:paid`。
  3. 搜索一条有三种以上运行环境的 skill。
  4. 未知 `--label runtime:nope`。
- 期望：1/2 与网页同结果；每条结果下有一行「属性：短名」，最多三枚、第四枚起 `+N`；3 显示三枚 + `+N`；4 返回 400 且消息点名未知值；标签-only 搜索可以没有关键词。
- 证据：`skillmaster-cli/cmd/skillmaster/labels_test.go`（P0，证明 CLI 发出 `--label` 并格式化返回）；**服务端过滤本身**由 T5 `SkillLabelApiIT` 与 T16 `WebDiscoverSemanticIT` 覆盖——CLI 的 fake server 不经过 `SqlFilters.labels`，不能替代那两条。2026-11-14 另在本地真服务端 + 真 CLI 手工跑过 1–4（含 `runtime:nope` 的 400 与四枚属性的 `+1`）。

### T9 · CLI `skill show` 顺序与兼容性命名（A5、A13）

- 前置：同 T3 的 skill。
- 步骤：`skmr skill show dev/pdf-tools`。
- 期望：字段顺序为 描述 → 适用场景 → 我的适用场景 → 预期结果 → 兼容性 → 属性 → 版本/状态/分类/公开标签/（可选）复制自/来源/许可证/使用/我的标签；作者 `compatibility` 的标题是「兼容性」；属性显示全部短名、默认值不打印；没有 `--json`。
- 证据：`skillmaster-cli/cmd/skillmaster/skill_show_test.go`（字段顺序、「兼容性」、属性）；`复制自 / 来源 / 许可证 / 使用` 这一组的相对顺序由 `versions.go` 的打印顺序保证，fixture 未覆盖它们（它们只在这一版之前就有，且可选）。「没有 `--json`」沿用 `skill show` 既有的 `rejectJSON`，本版不改。

### T10 · CLI 词表命令（A1、A15）

- 前置：迁移完成。
- 步骤：`skmr skills categories`、`skmr skills labels`。
- 期望：categories 只列 13 个一级词；labels 列维度/键/短名/完整语义；两者都不打印公域计数；旧 10 个 retired 不出现。
- 证据：`skillmaster-cli/cmd/skillmaster/labels_test.go`（`skills labels` 输出与无计数）、`v14_test.go`（`skills categories` 只有一列、无公域计数）。

### T11 · 抽取三档与严格口径（A3、A8、A14；P1）

- 前置：抽取任务可用；金标样本含 `orchestra-training-llms-megatron`、`jeffallan-sql-pro`、`jeffallan-devops-engineer`、`database-lookup`、`orchestra-modal-serverless-gpu`。
- 步骤：跑金标集；检查每个值的 `confidence` / `evidence` / `review_state`。
- 期望：高置信写 `auto`；中置信 `pending` 不展示；低置信或无证据不落库；GPU/Docker/数据库按 ADR 0192 的正反例；`outcome` 超 200 码点重试后截断并记 `partial`。
- 状态：**未执行（P1）**。

### T12 · 抽样与金标集（A7；P2）

- 前置：P1 已产生高置信标签；`deploy/label-audit.yml` 与 `deploy/label-gold.yml`。
- 步骤：跑周 job；读 CI 报告的样本数、准确率与异常簇；跑金标集回归。
- 期望：每周 30–50 条；准确率 ≥ 95%；金标集 100–200 条通过；异常按来源/类目成簇；没有运营专用 CLI。
- 状态：**未执行（P2）**。

### T13 · 运营覆盖文件（A3、A7、A14；P2）

- 前置：`deploy/label-overrides.yml` 含 `force` 与 `remove` 各一条。
- 步骤：跑抽取；查 `skill_label` 与读模型。
- 期望：覆盖行 `source=operator`、`confidence=1.0`、`confirmed`；被 `remove` 的值不展示；抽取每次重跑都重新应用；冲突留痕（谁、何时、旧值、新值）——**存储机制 P2 定**（TD 开放问题 13；P0 不落 `actor` 列）。
- 状态：**未执行（P2）**。

### T14 · 旧客户端兼容（A12）

- 前置：P0 已完成。
- 步骤：用旧版 CLI 解析 P0 的 `/api/v1/skills` 与 `/metadata` 响应；不带 `label` 的旧请求照常返回。
- 期望：旧字段不变；新字段缺省省略；不带 `label` 的请求与 P0 前同形。
- 证据：`SkillLabelApiIT` + CLI 现有 `search_v19_test.go`（P0）。

### T15 · `outcome` 上限与空值（A5）

- 前置：一条无 `outcome`、一条 201 码点、一条 200 码点的 skill。
- 步骤：读 API 与 `skill show`。
- 期望：无值省略/不画；200 码点保留；201 码点在写入路径被拒绝或按 TD 重试/截断；读模型不返回超长值。
- 状态：写入路径属于 P1；P0 只验证空值不画与读模型省略。

### T16 · 语义检索带 `label`（A6）

- 前置：语义开关打开 + 固定 stub embedder；两条公域 skill 标签不同。
- 步骤：`GET /api/v1/skills?scope=public&q=<词>&semantic=true&label=runtime:python`。
- 期望：先按标签收敛 id 集再排序；只返回带该标签的 skill；**未知标签是 400 并点名**（三条路径同一口径；分类 / 公开标签仍走「空结果 + 提示」）。合法的标签但没有匹配时，才给「没有足够相关的 skill」。
- 证据：`WebDiscoverSemanticIT.anAttributeNarrowsTheSemanticAnswerBeforeRanking` 与 `...anUnknownAttributeOnTheSemanticPathIsAFourHundred`；字面路径的 400 由 `SkillLabelApiIT.aLabelFilterIsAndAndAnUnknownWordIsFourHundred` 覆盖，库内路径由同类的 `theLibraryListingTakesTheSameLabelFilter` 覆盖。

### T17 · 生产控制台搬设计件（A4、A13、A15、A16）

- 前置：`skillmaster-console` 接通 P0 API。
- 步骤：打开发现页搜关键词、点分类、看信息页；窄屏 400px。
- 期望：结果行第二行只写描述；第三行属性最多三枚 + `+N`；信息页顺序与设计件一致；分类菜单与公开标签清单无公域计数；结果页有「搜索结果 / 筛选结果 + 筛了什么 + 清除」；窄屏不横向滚。
- 状态：**已实现；下列 30 项已由浏览器点选脚本自动验过，渲染观感仍待人工。** `tsc --noEmit` 通过；`vitest run` 48 个文件 / 766 条全绿，其中 `discover.test.tsx` 覆盖「无结果数 / 结果页头 / 清除 / 属性三枚 + `+N` / `label` 请求参数」，`skills.test.tsx` 覆盖信息页顺序与兼容性改名、分类菜单无计数，`skill-labels.test.tsx` 覆盖属性胶囊，`my-tags.test.tsx` 覆盖公开标签行改「查看 skill」。2026-11-14 另在真实服务端 + 真实控制台上跑 `qa/console-click-through`（无头 Chrome，按人手路径点）：登录、落地推荐、结果页头与分页条没有「N 个结果 / 共 N 个」且发现页 `.count` 为空、分类筛选与清除、三枚 + `+N` 浮层、默认值不画、信息页（描述在预期结果之前、属性在预期结果之后；兼容性存在时排在两者之间）、公开标签一行「查看 skill」（`seed.sql` 给账号建了一个带公开标签的 skill）、400px 无横向滚动，**30 项检查全过**，六张截图在 `artifacts/qa/console-click-through/`。**这仍是结构与交互证据，不等于视觉验收**（仓库红线 2）：颜色、间距与一屏六行要人看截图或在浏览器里看。

### T18 · 分类迁移 dry-run（A1、A10）

- 前置：V50 测试库。
- 步骤：在停在 V50 的库上跑 `skillmaster-server/scripts/dry-run-v65-category-migration.sh <库名>`。
- 期望：dry-run 只读、打印 10 条映射、13 个新词与 **13 个新货架各一行**的移入数（按快照统计，纯新货架也为 0 而不是缺行）；事务回滚后不写 `skill_category_mapping`、`skill_category_assignment_snapshot`，也不改 `skill.category_key`；正式执行只写一次。
- 证据：脚本（2026-11-14 在 V50 临时库实测；库里有 `doc`、`office`、`ops` 三条已归类 skill 与一条无分类 skill：dry-run 打印映射 10 行、13 个新词、13 行移入数（`office-efficiency` 2、`it-ops-security` 1、其余 0）、无分类 1；结束后 `skill_category` 仍 10 行，`to_regclass('public.skill_category_mapping')` 为空）；同一库上按注释跑完整回滚：`category_key` 恢复为 `doc` / `office` / `ops`，旧 10 词的 `state` / `sort_order` 复原（`ops = stopped`），两张辅助表删除；`SkillLabelMigrationIT` 覆盖正式迁移后的词表与快照回滚。

## 环境与数据

- **数据库**：Docker `skillmaster-postgres`；本分支专用 `skillmaster_test_v65`（见 `.dev-sop/profile.md` 的一库一栈规则）。测试库可抛弃，Flyway 每次重建。
- **服务端数据**：`V2` seed 的 `demo` 用户与命名空间；测试用 `publicSkill(...)` / `privateSkill(...)` 造公域与私有 skill；标签用 SQL 直接插入 `label_definition` / `skill_label`，不依赖抽取任务。
- **CLI 数据**：本地测试服务 + 静态测试令牌；`--label` 用例不依赖真实模型。
- **控制台数据**：设计件夹具；`console-states` 使用 `design/skillmaster-控制台演示.html`。
- **清理**：`@Sql("/sql/truncate-business-tables.sql")` 在测试方法前清业务表；测试库整体可重建；不清理开发库。

## 结果

- **P0 服务端**：T1–T6、T14、T15（空值）、T16、T18 通过。证据：`SkillLabelApiIT`（7 条）、`SkillLabelMigrationIT`（3 条）、`WebDiscoverSemanticIT`（11 条，含语义标签与未知标签 400）；受影响的旧测试（`LibraryApiIT` / `PublicTagCategoryApiIT` / `V14SmokeIT` / `MyTagApiIT` / `WebOrganizeIT` / `SkillSearchIT` / `SkillCopyIT` / `DiscoverRecommendedIT`）在 `skillmaster_test_v65` 上全绿；全量 `./mvnw verify`（含 failsafe 的 IT）170 个测试类 / 1323 条、0 失败。`TagSchemaMigrationIT` 的词表断言随 V51 改为「13 个 enabled + 10 个 retired」；顺带修了两个与本版无关的测试对日期/分区的依赖（`UsageHistoryIT` 自建它要写的每个周一分区，`ActivityArchiveJobIT` 的旧分区改按周一对齐）。
- **P0 CLI**：T8、T9、T10、T14 通过。证据：`cmd/skillmaster/labels_test.go`（7 条）、`skill_show_test.go`（字段顺序与「兼容性」）、`v14_test.go`（categories 无公域计数、`skills list` 拒绝 `--label`）、`search_v19_test.go`（旧请求形状不变）；`go test ./...` 全绿，`gofmt -l .` 为空，`scripts/check-file-size.mjs` 通过。
- **P0 设计件**：T7 通过（`console-states.mjs` 的计数与结果页头断言）；A9 的 `home-check` 通过，且 `design/src/home` 与 `skillmaster-site/src` 的源码 grep 均无「1000+」这一承诺。
- **P0 生产控制台**：T17 已实现、测试全绿（48 文件 / 766 条），**视觉验收未做**（需要人在浏览器里看）。属性非空的展示由组件测试的 mock 覆盖，真实公域数据在 P1 回填后才有。
- **P1**（T11）与 **P2**（T12、T13）：**未执行**。T15 的写入路径（201 码点）属于 P1，未执行。
- **dry-run 证据**：`skillmaster-server/scripts/dry-run-v65-category-migration.sh` 在 V50 临时库实测通过（2026-11-14）。

## 已知问题

- P1 / P2 的模型调用、抽样 job 与控制台工作台不在 P0 范围；它们未实现，不写成通过。
- T17 的折行、间距与一屏六行密度只能由人看；无横向滚动已由点击脚本覆盖，脚本证明的是结构、类名与请求形状，不是渲染观感。
