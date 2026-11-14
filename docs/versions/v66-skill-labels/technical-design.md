# v66-skill-labels · 技术设计

> **最后更新**：2026-11-14
> **状态**：设计完成（第 3 关）
> **所属版本**：[`README.md`](README.md)
> **决策**：[ADR 0192](../../decisions/0192-public-categories-are-thirteen-shelves.md) · [ADR 0193](../../decisions/0193-public-labels-are-extensible-claims.md) · [ADR 0194](../../decisions/0194-public-labels-are-agent-produced-and-sampling-audited.md) · [ADR 0195](../../decisions/0195-labels-p0-narrows-two-adrs.md)（对前三者的 P0 收窄）
> **这一版只写与 [`current/`](../../current/README.md) 的差异。** 模块边界、表现状与命令语法见 [`system.md`](../../current/system.md)、[`schema.md`](../../current/schema.md)、[`cli.md`](../../current/cli.md)；界面规格见 [`design/README.md`](../../../design/README.md)。ADR 的理由不复述，只链接。

## 架构

**一句话**：M12 长出「属性标签 + 预期结果」的词表与读模型；M8 / M13 的检索接入同一组字段；`usecase/label` 负责抽取、抽样与运营覆盖；控制台与 CLI 只读同一份读模型。

### 组件与职责

| 组件 | 改动 | 边界 |
|---|---|---|
| **M12 skill** | 新表 `label_definition`、`skill_label`、`label_extraction_run`、`label_audit_sample`、`skill_category_mapping`、`skill_category_assignment_snapshot`；`skill` 加 `outcome` 与来源列；`LabelService`（词表 `vocabulary()` / `definitionIdOf()`）；`SkillCategoryService` 扩到 13 类 | 所有新表归 M12；读模型与词表同模块；不改 M7 的版本表 |
| **M8 search** | `SearchRequest` / `SearchFilters` 加 `labelDefinitionIds`；`SkillCatalogService.page` / `admissibleIds` 加标签 `EXISTS`；`SemanticSearchUseCase` 加标签 | 过滤在 M12 的 id 集里完成；M13 只拿 id 集，不跨模块 SQL |
| **M13 retrieval** | `EmbeddingTextSource` 加 `outcome` 与标签短名/完整语义；文本 hash 变化重嵌 | 向量只回答「哪些近」，不回答「能不能看」 |
| **usecase/label** | `LabelExtractionUseCase`、`LabelExtractionJob`、`LabelAuditUseCase`、`LabelOverrideReader`、`LabelGoldSetRunner` | 读 M7 的快照 / 文件清单、M12 的来源快照；写 M12；模型客户端在 config |
| **api** | `SearchResponse.Card` 与 `SkillMetaResponse` 加 `outcome` / `labels`；`/api/v1/skills`、`/web/discover` 加 `label` | 旧字段不变；新字段缺省省略，旧客户端不受影响 |
| **console** | 把 [`design/`](../../../design/README.md) 的结果行 / 信息页搬进 `skillmaster-console` 的 `DiscoverPage`、`ResultCard`、`InfoTab` | 设计权威是 `design/`；实现不另起一套 |
| **CLI** | `skills search --label`；`skill show` 输出 `outcome` / `labels`；`skills categories` 13；`skills labels` 词表 | 所有命令面向用户；与网页同一读模型；不做自己的判断；不提供运营专用命令 |

### 数据流

1. **入队**：M7 的版本投影完成（发布 / 同步 / 导入）后，`LabelExtractionJob` 只对「公域 + 已上线」的 skill 入队；私有 skill 跳过。同一 `(skill_id, evaluated_version_id)` 幂等；新版本把旧 agent 标签标 `stale`，运营确认的保留。
2. **抽取**：`LabelExtractionUseCase` 读 `SKILL.md`、文件清单、`skill_source_snapshot`；模型返回 `{outcome, category, labels:[{key,value,confidence,evidence}]}`；应用 `deploy/label-overrides.yml`；按高 / 中 / 低路由；写 `skill.outcome`、`skill.category_key`、`skill_label`、`label_extraction_run`。
3. **读**：`SkillCatalogService` 分页取 skill 行；`LabelRepository.labelsOfSkills(ids)` 批量取标签；`SkillCardView` / `SearchResponse.Card` 组装；`label` 过滤在 `admissibleIds` / `page` 里；语义检索先用同一 id 集收敛，再交给 M13。
4. **抽样**：每周 job 从高置信自动通过的标签抽 30–50 条写 `label_audit_sample`；CI 报告读样本 + 运营 `deploy/label-audit.yml` 判定；异常队列进 CI 报告。**不提供运营 CLI**——所有命令都面向用户。
5. **覆盖**：`deploy/label-overrides.yml` 在模型之后应用；`source=operator`、`confidence=1.0`；`review/audit` 只读。

### 边界与不变量

- **所有新表归 M12**；M8 / M13 不直接写标签表；语义检索沿用 v19 的 category / tag 路径（M12 `admissibleIds` → M13 `nearestWithin`）。
- **只抽公域**；私有 skill 的标签为空，分类仍由作者选；原 `description`、`when_to_use`、frontmatter 一个字不改。
- **`outcome` 不是标签**，不塞进 `skill_label`；它是 `skill` 读模型的一列 + 来源列，不参与多选。
- **低置信留空**，不猜；中置信写 `review_state='pending'` 但不展示；只有 `auto` / `confirmed` 进读模型。
- **旧客户端**：新字段缺省省略；CLI 老版本不解析新字段。

## 数据模型

### 新表

**`label_definition`** —— 词表：一行是一个维度的某个值。

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | TEXT PK | 稳定 id；`skill_label` 引用它 |
| `dimension_key` | TEXT NOT NULL | `runtime` / `pricing_type`；新维度就是新 key |
| `dimension_name` | TEXT NOT NULL | 「运行环境」/「定价」；显示用 |
| `value` | TEXT NOT NULL | 值 slug：`python`、`api-key`、`paid` |
| `label_short` | TEXT NOT NULL | 胶囊上的短名：`Python`、`API key`、`付费` |
| `label_full` | TEXT NOT NULL | 完整语义：`需要 Python`、`仅 macOS`、`需要连接数据库服务`；抽取证据 / CLI / 运营，**前端不展示** |
| `group_key` | TEXT NOT NULL | `language` / `os` / `tool` / `credential` / `pricing`；颜色与图标 |
| `filterable` | BOOLEAN NOT NULL DEFAULT true | 新维度可先进「只展示、不进筛选」 |
| `state` | TEXT NOT NULL DEFAULT 'enabled' | `enabled` / `stopped` / `retired` |
| `sort_order` | INTEGER NOT NULL | 维度内顺序；结果行前三枚按它取 |
| `created_at` | TEXT NOT NULL | RFC3339 UTC |

约束：`UNIQUE (dimension_key, value)`、`UNIQUE (dimension_key, sort_order)`（新值插行时不能撞序号）；`CHECK (state IN ('enabled','stopped','retired'))`；索引 `(dimension_key, state, sort_order) WHERE filterable`。`group_key` 只影响颜色 / 图标，不影响筛选。

**`skill_label`** —— 一条 skill 的一个值；多选就是多行。

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | TEXT PK | |
| `skill_id` | TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE | |
| `definition_id` | TEXT NOT NULL REFERENCES label_definition(id) | |
| `run_id` | TEXT REFERENCES label_extraction_run(id) ON DELETE SET NULL | 哪一次抽取写的；运营覆盖为 NULL |
| `source` | TEXT NOT NULL | `agent` / `operator` / `author` / `upstream` |
| `confidence` | NUMERIC(3,2) NOT NULL | 0.00–1.00；运营覆盖为 1.00 |
| `evidence` | JSONB NOT NULL | `{file,line,quote}` 或 `{url,quote}`；运营覆盖为 `{override:"<file>"}` |
| `model` / `model_version` | TEXT | 抽取模型与版本 |
| `vocabulary_version` | TEXT NOT NULL | 词表版本；词表变了能知道哪些要重算 |
| `review_state` | TEXT NOT NULL DEFAULT 'auto' | `auto` / `pending` / `confirmed` / `rejected` / `stale` |
| `evaluated_version_id` | TEXT REFERENCES skill_version(id) | 评的是哪一版 |
| `created_at` / `updated_at` | TEXT NOT NULL | |

约束：`UNIQUE (skill_id, definition_id)`；索引 `(definition_id, skill_id)`、`(skill_id)`、`(review_state)`。**读模型只用 `source IN ('agent','operator')`、`review_state IN ('auto','confirmed')`、且定义 `state <> 'retired'` 的行：agent 行只在 `evaluated_version_id = skill.current_version_id` 时展示，`source='operator'` 的 `confirmed` 行跨版本保留。** 读模型随标签带出 `filterable`：展示与筛选是两件事——`filterable=false` 的词画成不可点的胶囊，词表接口与 `--label` 也不接受它。

**`label_extraction_run`** —— 一次抽取的运行记录，运营排障用。

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | TEXT PK | |
| `skill_id` / `version_id` | TEXT NOT NULL | 评的是哪个 skill 的哪一版 |
| `trigger` | TEXT NOT NULL | `publish` / `sync` / `backfill` / `manual` |
| `status` | TEXT NOT NULL | `queued` / `running` / `ok` / `partial` / `failed` |
| `model` / `model_version` / `vocabulary_version` | TEXT | |
| `started_at` / `finished_at` | TEXT | |
| `labels_written` | INTEGER | |
| `error` | TEXT | 失败原因；`partial` 也要写 |

索引 `(skill_id, started_at)`、`(status)`。

**`label_audit_sample`** —— 每周抽样的样本；判定由运营文件提供，**表里不存判定**。

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | TEXT PK | |
| `week` | TEXT NOT NULL | `2026-W47` |
| `skill_id` / `definition_id` | TEXT NOT NULL | 抽到哪条标签 |
| `sampled_at` | TEXT NOT NULL | |

约束：`UNIQUE (week, skill_id, definition_id)`；索引 `(week)`。周 job 只插样本；CI 报告读样本 + `deploy/label-audit.yml` 的判定，在内存里合并。判定只有一份权威，就是那个文件；不在表里抄第二份。

### `skill` 表

在现有 `skill` 读模型上加：

| 列 | 类型 | 说明 |
|---|---|---|
| `outcome` | TEXT | 一句话预期结果；最多 200 个 Unicode 码点（[ADR 0181](../../decisions/0181-text-limits-use-unicode-code-points.md)）；没有为 NULL |
| `outcome_source` | TEXT | `agent` / `operator` |
| `outcome_confidence` | NUMERIC(3,2) | |
| `outcome_evidence` | JSONB | 与标签同样的证据形状 |
| `outcome_model` / `outcome_model_version` | TEXT | |
| `outcome_evaluated_version_id` | TEXT REFERENCES skill_version(id) | |
| `outcome_updated_at` | TEXT | |

**为什么放 `skill` 而不单开表**：信息页、CLI 与检索文本都要读它，读模型已经在这里；单开表会让信息页与搜索多一次 join。列表本身只显示 `description`，但 `outcome` 仍随读模型返回，供信息页与 CLI 使用。来源列保留「谁说的、凭什么、评的哪一版」，与 `skill_label` 同构。新版本发布时把 agent 的 outcome 标 stale（读模型按 `outcome_evaluated_version_id = current_version_id` 判断），运营确认的跨版本保留。

### 词表 seed

**词表值的唯一权威是 [PRD §范围](prd.md#运行环境词表21-个非默认值--默认)**；迁移按那份表插行，不在这里抄第二份。首批：

- `runtime`：21 个非默认值 + `prompt-only` 默认值；分组 `language` / `os` / `tool` / `credential`。
- `pricing_type`：`free`（不展示）/ `paid`（付费）。

`output_type` 不做（ADR 0193 #3）：预期结果已经说了「得到什么」，再挂一个粗粒度产出类目是重复。后续若加，按「插词表行」的同一路径，不改表结构。

`prompt-only` 与 `free` 的 `skill_label` 行要写（筛选要用），但读模型不返回、界面不画。

### 分类迁移

`skill_category` 从 10 行扩到 13 行；旧 10 个 key 先按映射表改 `skill.category_key`，再退休。

| 旧 key | 旧名 | 新 key | 新名 |
|---|---|---|---|
| `doc` | 文档与演示 | `office-efficiency` | 办公效率 |
| `office` | 办公与协作 | `office-efficiency` | 办公效率 |
| `data` | 数据与分析 | `data-analysis` | 数据分析 |
| `dev` | 研发与测试 | `dev-programming` | 开发编程 |
| `design` | 设计与界面 | `design-media` | 设计多媒体 |
| `media` | 图像与音视频 | `design-media` | 设计多媒体 |
| `writing` | 写作与翻译 | `content-creation` | 内容创作 |
| `research` | 研究与检索 | `knowledge-management` | 知识管理 |
| `ops` | 运维与云 | `it-ops-security` | IT 运维与安全 |
| `security` | 安全与合规 | `it-ops-security` | IT 运维与安全 |

13 个新 key 的完整清单（key / 名称 / 顺序）——**这是唯一一份**，控制台与 CLI 都以它为准：

```
office-efficiency   办公效率       1
content-creation    内容创作       2
dev-programming     开发编程       3
data-analysis       数据分析       4
design-media        设计多媒体     5
ai-agent            AI Agent       6
knowledge-management 知识管理      7
business-ops        商业运营       8
education           教育学习       9
professional        行业专业       10
it-ops-security     IT 运维与安全  11
life-service        生活服务       12
ai-collaboration    AI 协作        13
```

迁移前建两张表（都归 M12）：

- **`skill_category_mapping`**：`old_key`、`new_key`、`old_state`、`old_sort_order`、`note`，一行一个旧词；它随迁移保留，回滚用它恢复词表。
- **`skill_category_assignment_snapshot`**：`skill_id`、`old_key`、`migrated_at`，**一行一个 skill**。映射是多对一（`doc` 与 `office` 都到 `office-efficiency`），key 级映射无法回答某个 skill 原来在哪；回滚必须按快照逐 skill 恢复。

迁移步骤：

1. 建两张表；写映射，写快照（`SELECT id, category_key FROM skill WHERE category_key IS NOT NULL`）。
2. 把旧 10 行改成 `state='retired'`、`sort_order = sort_order + 100`（`UNIQUE(sort_order)` 让新 13 行能用 1–13）。
3. 插入 13 个新行，`sort_order` 1–13、`state='enabled'`。
4. 按映射表更新 `skill.category_key`：`UPDATE skill SET category_key = m.new_key FROM skill_category_mapping m WHERE skill.category_key = m.old_key`。
5. 回滚（迁移脚本注释里是同一条 SQL）：按快照逐 skill 恢复；**清空全部 13 个新货架**——快照恢复之后还指着新 key 的，都是迁移窗口内新建或改类的 skill，它们没有旧词可回，不清就会让下一步 `DELETE` 撞外键（改名货架上的 skill 也没有快照行）；然后删 13 行、按 `old_state` / `old_sort_order` 恢复旧 10 行、删两张迁移表。迁移窗口之后若有人改过分类，回滚会把它清掉——这是回滚的已知代价。

**Dry-run**：`skillmaster-server/scripts/dry-run-v66-category-migration.sh <库名>` 在一个事务里执行 V52、打印映射 / 13 个新词 / **13 个新货架各会移入多少 skill**（从快照统计，纯新货架也占一行、值为 0），然后 `ROLLBACK`；PostgreSQL 的 DDL 是事务性的，回滚后不留表、行或 Flyway 历史（A10）。

旧 `ops` 在 V28 是 `stopped`；回滚要恢复 `stopped`，不是 `enabled`，所以映射表带 `old_state`。真实 13 类都 `enabled`；设计件里那个 stopped 的 `ops` 只用于演示禁用态。

### 索引与预算

- `skill_label` 的 `(definition_id, skill_id)` 是筛选索引；一个 `label` = 一个 `EXISTS`，多个取 AND。
- 卡片批量取标签：`WHERE skill_id = ANY(:ids)` 一次查完，按 `sort_order` 排序；一页 6 / 20 条，不会 N+1。
- `skill.outcome` 与标签短名 / 完整语义进检索文本；`skill_embedding.text_hash` 变化时重嵌。
- 新表都不含大文本；`evidence` 是短 JSON；`outcome` 200 码点。分页查询的增量是一次批量标签查询，不加 join 到主查询。

## 接口

### 读：卡片（列表 / 搜索 / 推荐）

`GET /api/v1/skills` 与 `GET /web/discover` 都接受重复的 `label`：

```
GET /api/v1/skills?scope=public&q=sql&label=runtime:python&label=pricing_type:paid
GET /web/discover?q=sql&label=runtime:python
```

- `label` 的值是 `<dimension_key>:<value>`；多个取 **AND**；未知 key / value 返回 400（`InvalidSearchRequestException`，消息点名那个 label），不静默丢掉——用户点的是词表里的胶囊，拼错就该说。**三条路径同一口径**：公开、库内、语义检索都在进入检索前解析，未知值即 400；分类与公开标签保持 v19 的「空结果 + 提示」规则，那是断链而不是拼错。
- 词表解析在 M12（`LabelService`）：`filterable=true` 且 `state ∈ ('enabled','stopped')` 才可筛；`retired` 不可筛。解析出的 `definition_id` 进 `SearchFilters` / `SearchRequest`。
- 语义检索也接受 `label`：`SemanticSearchUseCase` 先按同一组 filter 从 M12 取 `admissibleIds`，再交给 M13 的 `nearestWithin`；不把 `skill_label` 拼进 M13 的 SQL（[`rules.md`](../../current/rules.md)）。
- `SearchResponse.Card` 新增：

```json
{
  "outcome": "一段能直接照着改的 SQL 优化建议，以及每一处改动的理由。",
  "labels": [
    { "key": "runtime", "value": "python", "label": "Python", "full": "需要 Python", "group": "language", "filterable": true },
    { "key": "runtime", "value": "api-key", "label": "API key", "full": "需要 API key", "group": "credential", "filterable": true }
  ]
}
```

  - `outcome` 为 NULL 时省略；`labels` 无标签时省略（不写空数组）。旧客户端看到的字段不变。
  - `filterable=false` 的词照常返回、照常出现在信息页，但**胶囊不可点**；词表接口与 `--label` 都不接受它（点了就是 400）。
  - **两个端点的 `key` 不是一回事**：卡片里是维度（`runtime`），词表里是定义 id（`runtime:python`，可直接交给 `label=`）；前端拼筛选地址时用 `key + ':' + value`。
  - `labels` 只返回可展示的 `auto` / `confirmed` 行；`prompt-only` 与 `free` 不返回（筛选仍可用）。
  - `SkillCardView` 加 `outcome`，由 `SkillCardAssembler` 用 `LabelService.outcomesOf(ids)` 批量填充；`CatalogRow` 不变。`LabelRepository.labelsOfSkills` 批量组装 `labels`。M8 的 `Card` 只做映射，不做判断。

### 读：元数据（`skill show` / 信息页）

`GET /api/v1/skills/{namespace}/{name}/metadata`（现有 `SkillMetaResponse`）新增同样形状的 `outcome` 与 `labels`。控制台信息页读的是作者面 `GET /web/skills/{namespace}/{name}`（`AuthoredSkillResponse`），本版给它加了同一组字段；两个端点的形状一致，只有权限与附带字段不同。`description`、`whenToUse`、`compatibility` 不动；信息页仍保留作者描述，**控制台列表也只显示描述**——`outcome` 不上列表，只在信息页的适用场景之后出现。**控制台只画标签短名**（`Python` / `macOS` / `API key`）；`label_full` 留在数据里，不进胶囊、tooltip 或读屏。**CLI 的列表仍按现有契约显示 `描述 - 适用场景`**，`outcome` 只在 `skill show`；`skill show` 打印同一组字段，属性显示全部短名，不打印默认值。

### 读：属性词表（`skills labels`）

`GET /api/v1/labels` 只读词表，供 `skmr skills labels` 与将来的控制台词表页使用：

```json
[
  { "key": "runtime:python", "dimension_key": "runtime", "dimension_name": "运行环境",
    "value": "python", "short": "Python", "full": "需要 Python", "group": "language",
    "filterable": true, "state": "enabled", "sort_order": 1 }
]
```

- 只返回 `filterable=true` 且 `state ∈ ('enabled','stopped')` 的行；`retired` 不列，避免 `--label` 指向不可筛的值。
- **不返回公域计数**（2026-11-13 用户定）：公域规模是内部信号，不通过 CLI 词表直接暴露。**控制台同口径**：信息页分类菜单、公开标签管理清单的行内、搜索结果页头与分页条都不显示公域计数，只保留筛选与入口；结果页另加「搜索结果 / 筛选结果 + 筛了什么 + 清除」的头，与推荐页区分。
- 词表源是 M12 的 `label_definition`；`skills categories` 同样不再打印分类的公域计数。

### CLI

| 命令 | 行为 |
|---|---|
| `skmr skills search [<问题>] [--label <key:value>]...` | 给了 `--label` 时可以没有关键词；`--label` 可重复，AND；与 `--category` / `--public-tag` / `--my-tag` 也可组合；参数原样交给服务器，不在 CLI 解析词表。输出仍是「地址 + 描述 - 适用场景」，再跟一行「属性：短名」，最多 3 枚、第四枚起收成 `+N`。 |
| `skmr skill show <地址>` | 顺序对齐信息页：描述、适用场景、我的适用场景、预期结果、兼容性、属性、版本/状态/分类/公开标签/（可选）复制自/来源/许可证/使用/我的标签。**把作者的 `compatibility` 从旧文案「运行环境」改叫「兼容性」**；新平台属性叫「属性」，显示全部短名，默认值不打印。完整语义只在 `skills labels` / 数据里；没有 `--json`。 |
| `skmr skills categories` | 13 个一级词；旧的 10 个 retired 不列出；**不打印公域计数**。 |
| `skmr skills labels` | 只读词表：维度、键（`runtime:python` 这种可筛形状）、短名、完整语义；**不打印公域计数**。 |
| `skmr skills list` | 不变：库内库存是地址清单。要按属性筛库内 skill，用 `skills search --scope library --label ...`。 |
| `skmr skill invoke` | 不变：头部仍只印作者的 `compatibility:`；平台属性不进 invoke 头。 |

CLI 不自己判断标签对错；**所有命令都面向用户，不提供运营专用的 `skill label review/audit` 或写命令**。运营的校准路径是覆盖文件 + CI 报告（见下）。

### 抽取输出与阈值

模型返回结构化 JSON：

```json
{
  "outcome": "一句话；没有证据就 null",
  "category": "office-efficiency",
  "labels": [
    { "key": "runtime", "value": "python", "confidence": 0.94, "evidence": { "file": "SKILL.md", "line": 18, "quote": "python scripts/split.py" } }
  ]
}
```

- **高**：`confidence >= 0.85` → `review_state='auto'`，展示。
- **中**：`0.50 <= confidence < 0.85` → `pending`，不展示，进异常队列 / CI 报告。
- **低**：`< 0.50` → 不落库、不展示；没有证据同样不落库。
- `category` 单选；高置信直接写 `skill.category_key`，中置信进 review 的「分类」簇，低置信留空。
- `outcome` 最多 200 码点；超长重试一次，再超长截断并记 `partial`。
- **运行环境只按「运行时真的缺什么」抽取**（ADR 0193 #9）。证据必须是命令、前置条件或安装步骤；简介里出现关键词不算：
  - `gpu`：本机/本地集群 NVIDIA GPU（`torchrun`、`nvidia-smi`、CUDA、DGX Spark）；云 GPU / serverless GPU 归 `online-service` + `api-key`。
  - `docker`：skill 自己的步骤运行 `docker` / 容器；只写 Dockerfile、生成 compose / K8s 清单不算。
  - `database`：步骤连接或执行到数据库（`EXPLAIN`、迁移、复制、监控）；教 SQL、设计 schema、查公开数据库 API 不算；`database-lookup` 是 `online-service` + `api-key`。
  - 正反例进金标集：`orchestra-training-llms-megatron`（Docker + GPU）、`jeffallan-sql-pro`（数据库）、`jeffallan-devops-engineer`（不标 Docker）、`database-lookup`（不标数据库）、`orchestra-modal-serverless-gpu`（不标 GPU）。

阈值是**初始值**，由 100–200 条金标集校准（[ADR 0194](../../decisions/0194-public-labels-are-agent-produced-and-sampling-audited.md)）；校准后写回配置，不改代码。

### 运营覆盖文件

`deploy/label-overrides.yml`（进 git，抽取任务读取）：

```yaml
skills:
  "dev/pdf-tools":
    outcome: "一份拆好的 PDF，以及每页对应的原文页码。"
    labels:
      runtime:python: force
      runtime:api-key: remove
```

- `force` / `remove` 只对已存在的 definition；新值要先走词表迁移。
- 覆盖写 `source='operator'`、`confidence=1.0`、`evidence={"override":"deploy/label-overrides.yml"}`、`review_state='confirmed'`；抽取任务每次运行都重新应用，运营优先。
- `deploy/label-audit.yml` 存抽样判定：`week` + `namespace/name` + `definition` + `correct|incorrect` + 备注；CI 报告读它计算正确率。
- 金标集 `deploy/label-gold.yml`：100–200 条 `namespace/name` + `definition` + `expected`；`LabelGoldSetRunner` 在 P2 做回归。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P0 · 读模型与词表** | 迁移（新表、`skill.outcome`、13 分类 + 映射 + 回滚快照）、词表 seed、`SearchResponse.Card` / `SkillMetaResponse` 新字段、`skill show` / `--label` / `categories` / `labels`、控制台把 [`design/`](../../../design/README.md) 的结果行与信息页搬进来（此时标签 / 预期结果为空） | A1、A2、A4、A5（空值）、A6（空集）、A9、A10、A11、A12、A13（空值路径）；旧客户端兼容 |
| **P1 · 抽取与检索** | `LabelExtractionJob` / `LabelExtractionUseCase`、三档路由、公域回填、`outcome` 与标签进关键词 / 向量文本、控制台 / CLI 显示真实数据 | A3、A4、A5、A8、A13；抽取覆盖率与成本实测 |
| **P2 · 抽样与运营** | 周抽样 job、`label_audit_sample`、CI 报告、覆盖文件、金标集回归 | A7；95% 准确率；运营周成本 ≤ 2h |

**P0 可独立合并**：它先把「字段在、界面在、旧客户端不坏」交付；P1 之前所有标签 / outcome 为空，列表始终显示描述，符合 A4。P1 不回滚 P0 的契约，只填数据。

## 开放问题

1. **抽取模型与成本**：用哪个模型 / 供应商、每次的 token 上限、批量与并发？当前倾向：复用现有 embedding provider 的 key，单 skill 一次结构化输出；P1 先用 100–200 条金标集实测单条成本，超过预算再换小模型。
2. **阈值**：高 / 中的 0.85 / 0.50 是否合适？当前倾向：先用这两个值（与 ADR 0194 一致），金标集校准；95% 是 precision 目标，recall 只影响覆盖率。
3. **`outcome` 上限**：200 码点是否足够？当前倾向：200（与 `when_to_use` 同一条 ADR 0181 上限），prompt 要求一句话；超长重试 / 截断。
4. **分类映射抽查**：旧 10 的映射是否每一组都按「主要产出」？当前倾向：按 ADR 0192 §决定 5 执行，dry-run 后运营抽查 100 条；发现错分先改映射表，再重跑迁移。
5. **分类也走三档吗**：分类是单选，是否需要 `pending`？当前倾向：高置信直接写，中置信进异常队列 / CI 报告的分类簇，低置信留空；不阻塞标签。
6. **语义检索的 id 集**：`admissibleIds` 在公域规模下会不会太大？当前倾向：公域规模未核实，先用 id 集（与 v19 的 category / tag 同路径）；超过实测阈值再在 M13 的 SQL 里加 `EXISTS`，那要先改模块规则 / ADR。
7. **embedding 重算**：`outcome` / 标签变化是否每次都重嵌？当前倾向：只在 `EmbeddingTextSource` 的文本 hash 变化时重嵌；标签是结构化过滤，短名 / 完整语义进文本是为了「搜 Python 能找到」。
8. **版本与旧标签**：新版本发布后，旧 agent 标签是否展示？当前倾向：agent 行只在 `evaluated_version_id = current_version_id` 时展示，运营 `confirmed` 跨版本保留；抽取完成前信息页不画预期结果块。
9. **私有 skill**：私有 skill 是否也抽标签？当前倾向：不抽；只跑公域；私有分类仍由作者选；我的标签（私人标签）完全不动。
10. **维度名 / 分组放哪**：`label_definition` 每行重复 `dimension_name` / `group_key`，还是单开 `label_dimension` 表？当前倾向：先按 ADR 0193 的两表，维度只有两个；改名走词表迁移；维度继续长再加表（需 ADR）。
11. **真实词表要不要 stopped 词**：设计件的 `ops` 是为了演示禁用态；真实 13 类都 enabled。当前倾向：不保留 stopped；禁用态只在设计件测试。
12. **排序权重**：`outcome` 与 `description` 同权还是略高？当前倾向：先同权，不改排序算法；有搜索日志再加权。
13. **覆盖冲突的留痕**：运营覆盖与 Agent 结果冲突时，`skill_label` 被 `UNIQUE (skill_id, definition_id)` 覆盖，旧值不留；是加 `actor` 列（`updated_at` 已在），还是单开历史表？当前倾向：P2 随运营覆盖一起落，P0 只保证覆盖行是 `source=operator` / `confirmed`；先记在这里，P2 落地前补 DDL 与 ADR 头注。
