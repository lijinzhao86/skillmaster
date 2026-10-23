# v14-skill-tags · 技术设计

> **最后更新**：2026-10-23
> **状态**：技术设计已完成（**尚未实现**）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

## 架构

**一句话：所有新数据落在 M12（`skill` 所在模块），读与写共用同一套 use case；`/api/v1` 与 `/web` 只是两个传输适配器，CLI、控制台、agent 看到的是同一份判据。**

```
控制台（React） ── /web/**  （会话 + CSRF） ─┐
                                            ├─ controller（只做传输） ─ use case ─ M12 服务 ─ PostgreSQL
CLI / agent ──── /api/v1/**（Bearer）───────┘                              │
                                                                           └─ M10 AuditLog（同事务）
```

- **一个模块**：`skill`、`skill_grant`、`skill_access_request` 今天已在 M12（[`ModuleMap.java:92-93`](../../../skillmaster-server/src/main/java/com/skillmasterai/common/ModuleMap.java)）。库加入引用、公开标签、我的标签、分类词表与分类赋值**全部**进 M12——这样「库列表」「标签过滤」「访问谓词」都能在一条 SQL 里完成，不跨模块。
- **两条平面，同一套用例**：`/api/v1` 给 CLI / agent（Bearer，`SCOPE_skills:read|write`），`/web` 给控制台（会话 + CSRF）。控制器只做参数校验、身份转换与错误映射；权限、上限、审计、事务都在 use case。**不复制业务规则**。
- **新 `/web` 路径必须显式进会话链**：`SecurityConfig.java:103-130` 里只有逐个列出的路径是 `authenticated()`，其余 `/web/**` 落 `permitAll`（`:131`）。本版加 `.requestMatchers("/web/library", "/web/discover", "/web/discover/**", "/web/tags", "/web/tags/**", "/web/categories", "/web/public-tags").authenticated()`。`/web/skills/**` 已覆盖信息页那几组写，不用再列；`/web/tags` 与 `/web/tags/**` 两条都要写，免得只匹配到子路径。
- **三层，各做一件事**（沿用现有形状）：`modules/skill/internal/` 的 repository 只写 SQL（`SkillCatalogRepository` 与新增的 `LibraryRepository` / `TagRepository` / `CategoryRepository`，与 `SkillRepository` / `SkillGrantRepository` 同包）；`modules/skill/` 的服务承载边界与判据（`SkillCatalogService`、`SkillMetadataService`，新增 `SkillLibraryService` / `SkillTagService` / `SkillCategoryService`）；`usecase/` 的用例编排事务、权限与审计。谓词（可见、可发现、条目生效、来源优先级）各只有一处定义，列表、检索、计数、标签读写共用。
- **状态推导，不落列**：库条目的「生效 / 休眠」、我的标签的「生效 / 休眠」都由谓词从既有列推导；没有 `state` / `dormant` 列，也没有状态回填任务。
- **身份稳定**：我的标签定义用不透明 `id`（ULID）；公开标签用规范化 `tag_key`；分类用词表 `key`。改名、改说明、换分类都不改引用。

**M12 的边界**：M12 今天是「skill 本身」——创建、版本、可见性、授权、访问申请。本版给它加上第二件事：**人们怎么认识和组织 skill**（库加入、两套标签、分类）。这两件事共用一个主体（`skill` 行）和同一套可见性谓词，所以不拆——拆成「skill 内容」与「skill 目录」两个模块会让每一次列表查询都跨模块 join，而 [`TableOwnershipTest`](../../../skillmaster-server/src/test/java/com/skillmasterai/TableOwnershipTest.java) 禁止一条 SQL 碰两个模块的表。**拆分触发点**写在这里，将来照它判：出现组织 / 团队库、或标签治理需要独立生命周期（审核、合并、运营）时，那部分数据与判据整体搬出——谓词从 `userId` 换成「主体」，挂载表与身份不变。

**为什么这个划分能扩展**（本版最在意的四条）：

1. **主体（subject）只有一个变量**：谓词都接受 `userId`，将来组织 / 团队加入时把它换成「主体」参数即可，不需要重写列表与权限。
2. **词层可加、不可猜**：分类是独立词表、公开标签是挂载行、我的标签是定义 + 挂载。将来加同义词、审核、多级分类，都是**加表或加状态**，不改现有挂载的身份。
3. **派生不物化**：计数、来源、休眠全部现算。加缓存是一次有测量的改造，不是今天的默认。
4. **不预埋**：没有 `parent_key`、没有 `team_id`、没有 `visibility_scope` 这类没有产品语义的列——扩展点是判据与表边界，不是空字段（[ADR 0078](../../decisions/0078-a-category-is-one-shelf.md) 明确不预留层级）。

## 数据模型

**迁移编号**：现有最大 `V25`（`V25__a_draft_is_mutable.sql`），本版用 **V26–V28**。每张新表同时登记进 `ModuleMap` 的 M12 集合（`ModuleMap.java:92-93`），否则 `TableOwnershipTest.java:93-96` 直接红。

### V26 · 库的加入引用

```sql
CREATE TABLE skill_library_join (
  user_id    TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  skill_id   TEXT NOT NULL REFERENCES skill(id)    ON DELETE CASCADE,
  created_at TEXT NOT NULL,                         -- RFC3339 UTC（Timestamps.now）
  PRIMARY KEY (user_id, skill_id)
);
CREATE INDEX idx_library_join_skill ON skill_library_join(skill_id);
```

- **只存“我加入的”**。我创建的、共享给我的仍然从 `skill` 与 `skill_grant` 推导——不复制一份来源，否则两处会漂。
- **没有 `source` 列**：来源是每次按优先级 `created > shared > joined` 算出来的一个值。
- **没有状态列**：条目生效 / 休眠由谓词推导；移除就是删行，没有墓碑（[ADR 0082](../../decisions/0082-public-and-private-with-my-library.md)）。
- 主键覆盖「我的库有哪些 skill」这条热路径；`skill_id` 索引服务级联删除与按 skill 清理。

### V27 · 两套标签

```sql
CREATE TABLE skill_public_tag (
  skill_id   TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  tag_key    TEXT NOT NULL,                        -- 规范化后的 key（NFKC、去首尾、压缩空格、小写）
  tag_name   TEXT NOT NULL,                        -- 作者写下的显示名
  created_at TEXT NOT NULL,
  PRIMARY KEY (skill_id, tag_key),
  CHECK (char_length(tag_name) BETWEEN 1 AND 24)
);
CREATE INDEX idx_public_tag_key ON skill_public_tag(tag_key);

CREATE TABLE my_tag_definition (
  id          TEXT PRIMARY KEY,                    -- ULID
  user_id     TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  name_key    TEXT NOT NULL,
  description TEXT,                                -- NULL = 没有说明；空串在用例里归一成 NULL
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  UNIQUE (user_id, name_key),
  CHECK (char_length(name) BETWEEN 1 AND 24),
  CHECK (description IS NULL OR char_length(description) <= 200)
);
CREATE INDEX idx_my_tag_definition_user ON my_tag_definition(user_id);

CREATE TABLE my_tag_attachment (
  definition_id TEXT NOT NULL REFERENCES my_tag_definition(id) ON DELETE CASCADE,
  skill_id      TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  created_at    TEXT NOT NULL,
  PRIMARY KEY (definition_id, skill_id)
);
CREATE INDEX idx_my_tag_attachment_skill ON my_tag_attachment(skill_id);
```

- **公开标签没有独立定义表**：词形就是标识（[ADR 0083](../../decisions/0083-two-tag-layers.md)）。计数、筛选、改名 / 合并的将来改造都以 `tag_key` 为单位。
- **我的标签「定义」与「贴法」分开**：改名改定义，所有贴法跟着变；删除定义级联删贴法（只在控制台）。
- **贴法没有状态列**：条目休眠时整组读不到，由谓词决定；重新可见时自然回来。
- **贴法不冗余 `user_id`**：所有者只在定义上存一份。附件是「定义 × skill」的纯关联，多一列就多一处可能与定义漂的值；按用户过滤的查询 join 一次定义，`idx_my_tag_attachment_skill` 让「这个 skill 上所有贴法」仍然走索引，join 的右表是主键点查。
- 上限（每 skill 10 / 20、每账号 100）**不在 CHECK 里**：它们是跨行计数，用事务 + 咨询锁保证（见 §接口「并发与上限」）。

### V28 · 分类词表与赋值

```sql
CREATE TABLE skill_category (
  key        TEXT PRIMARY KEY,                     -- 'doc' / 'data' / …（地址与 CLI 用它）
  name       TEXT NOT NULL,                        -- 界面显示名
  state      TEXT NOT NULL DEFAULT 'enabled',
  sort_order INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  CHECK (state IN ('enabled', 'stopped', 'retired')),
  UNIQUE (sort_order)
);

ALTER TABLE skill ADD COLUMN category_key TEXT REFERENCES skill_category(key);
CREATE INDEX idx_skill_category ON skill(category_key) WHERE deleted_at IS NULL;

INSERT INTO skill_category (key, name, state, sort_order, created_at) VALUES
  ('doc',      '文档与演示',   'enabled', 1, ...),
  ('data',     '数据与分析',   'enabled', 2, ...),
  ('dev',      '研发与测试',   'enabled', 3, ...),
  ('design',   '设计与界面',   'enabled', 4, ...),
  ('writing',  '写作与翻译',   'enabled', 5, ...),
  ('research', '研究与检索',   'enabled', 6, ...),
  ('office',   '办公与协作',   'enabled', 7, ...),
  ('ops',      '运维与云',     'stopped', 8, ...),
  ('media',    '图像与音视频', 'enabled', 9, ...),
  ('security', '安全与合规',   'enabled', 10, ...);
```

- **单选由列保证**：一个 `category_key` 就是一格，不需要关联表与唯一约束。
- **词表是平台数据**：`enabled` / `stopped` / `retired` 三态；停用不阻止已贴的保留，退役不出现在词表与选择器（[ADR 0078](../../decisions/0078-a-category-is-one-shelf.md)）。
- **不预留层级**。将来真要加层，是新 ADR + 新迁移，而不是今天塞一个 `parent_key`。

### 谓词（唯一权威）

**形状是“一个 CTE 产出身份列，其他查询只读这些列”，不是四段可以随手拼的字符串。** 谓词只写在 `modules/skill/internal/SkillPredicates.java` 里，由列表、检索、计数、标签读写共用：

```sql
WITH identified AS (
  SELECT s.*,
         g.role AS grant_role,
         CASE
           WHEN s.namespace_id = :ownNamespaceId THEN 'created'
           WHEN g.role IS NOT NULL THEN 'shared'
           WHEN j.skill_id IS NOT NULL THEN 'joined'
         END AS source
  FROM skill s
  LEFT JOIN skill_grant g
    ON g.skill_id = s.id AND g.grantee_id = :userId
  LEFT JOIN skill_library_join j
    ON j.skill_id = s.id AND j.user_id = :userId
)
```

- **参数只有两个**（`ownNamespaceId` / `userId`）；多选过滤一律用数组绑定（`= ANY(:keys)`），不拼字符串。
- **“写档及以上”从 `GrantRole` 生成**（`GrantRole.atLeast(role, GrantRole.WRITE)` → 数组参数；现有签名是两参），不在 SQL 里再写一遍 `'write','maintain','admin'`。
- 现有三处副本（`SkillRepository.permitted`、`SkillCatalogRepository.page`、`authorPage`）都迁到这个类。
- **计数与来源筛选也读这个 CTE，不另写一条**：`/categories`、`/public-tags`、`/me/tags` 的计数是在 `identified` 的结果上再过滤，`source=` 筛选读的是 `identified.source`。任何一处新写「可见 / 可发现 / 生效 / 来源」判断都算缺陷；测试用同一组夹具分别打 CTE、列表、检索、计数四个入口，结果必须一致。

```sql
-- 1. 公域可发现
s.deleted_at IS NULL AND s.current_version_id IS NOT NULL
AND s.offline_at IS NULL AND s.visibility = 'public'

-- 2. 对调用者可见（沿用今天的三分支，且必须未删除）
s.deleted_at IS NULL
AND (s.namespace_id = :ownNamespaceId
     OR g.role IS NOT NULL
     OR s.visibility = 'public')

-- 3. 库来源（没有匹配就为 NULL，条目不进库）
identified.source

-- 4. 库条目生效
CASE
  WHEN source = 'created' THEN deleted_at IS NULL
  WHEN source = 'shared' AND grant_role = ANY(:writeAndUp) THEN deleted_at IS NULL
  ELSE deleted_at IS NULL
       AND current_version_id IS NOT NULL
       AND offline_at IS NULL
       /* 我加入的：公开私有化且没有授权时必须休眠（PRD §库与标签的生命周期） */
       AND (visibility = 'public' OR grant_role IS NOT NULL)
END
```

- `live / draft / offline / deleted / all` 是谓词上的**状态筛选**：`live` = 生效 ∧ 有当前版本 ∧ 未下线；`draft` = 生效 ∧ 没有当前版本；`offline` = 生效 ∧ 下线；`all` = 三者之并；`deleted` = 我创建的 ∧ 已软删（[`prd.md`](prd.md) §CLI 命令面）。
- **列表读的 `updated_at` 是一个表达式**：`COALESCE(current_version_submitted_at, newest_draft_submitted_at, created_at)`。第三个兜底是必要的——所有版本都被丢弃时前两项都是 `NULL`，而现有 keyset 与 `CatalogRow.sortKey` 都会把 `NULL` 当值处理（`List.of` 直接 NPE、`<` 比较静默丢行）。排序读的是上面那个表达式；「删除于」读的是 `deleted_at` 列，不参与排序。`skill.updated_at` 是另一条真实列，由内容、可见性与生命周期写路径更新，列表排序**不读它**。
- **三种排序各有一个 ordering id 与一套 keyset**：`recent` = `(updated_key DESC, id ASC)`；`name` = `(name ASC, id ASC)`；`relevance` 保持今天的 `(relevance DESC, updated_key DESC, id ASC)`。`--sort name` 用独立的 ordering id，旧游标在新排序下会被拒绝，不会静默错页。

### 规范化与计数

- 名字规范化只在服务端一处实现（建议 `modules/skill/TagNames.java`）：NFKC → 去首尾空白 → 内部连续空白压成一个 → `Locale.ROOT` 小写；长度按 Unicode 码点 1–24。规范化后**含 `#` 的名字拒绝**（`invalid_name`）：井号是公开标签在界面上的写法，不是名字的一部分（voice-spec §公开标签写成 `#名字`）。CLI 的 `--my-tag` / `--public-tag` 传名字，服务端规范化后匹配；**客户端不保存第二套规则**。
- **同一个 `tag_key` 的显示名**：取**同一可见域里最早一条贴法**的 `tag_name`（按 `created_at, skill_id` 排序），保证同一份词表在不同请求里显示一致。两个域不同：`skills public-tags` / `/web/public-tags` 的域是**公域可发现**的 skill（私有 / 下线 / 已删除的贴法不参与，否则词形会从私域泄露）；管理页公开标签页签的域是**调用者自己创建的、生效中的** skill（那一页本来就只数这些）。单个 skill 上仍显示作者自己写的那一份。
- **整理动作不改「最近更新」**：贴 / 摘标签、设置分类、加入 / 移除库都**不更新** `skill.updated_at`，也不写 `current_version_submitted_at` / `newest_draft_submitted_at`；列表排序读的表达式因此不动（「删除于」是 `deleted_at`，整理动作本来也不碰它）。否则一次整理会把条目顶到「最近更新」的最前面，排序就不再是可解释的。
- 计数全部现算，**三个数各有各的口径，不复用**：
  - 公开标签 / 分类的 `公域`（CLI `skills public-tags`、`skills categories`）= 满足「公域可发现」且带该词的 skill 数；
  - 管理页公开标签页签的 `用在`（`/web/tags?tab=public`）= **我创建的、生效中的** skill 上该词的贴法数（[`prd.md`](prd.md) §展示与计数口径）——它不是 `公域`，接口字段也不同（`used_in` vs `public_total`），避免一次误用；
  - 我的标签的 `用在` = 该定义下、且条目对本人**生效**的贴法数。
- 不给计数建表、不做触发器；有测量证据再谈缓存（§开放问题 Q3）。

## 接口

### 主契约（`/api/v1`，Bearer）

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `GET` | `/api/v1/skills?scope=library\|public&…` | 库列表 / 公域检索（同一路由，`scope` 分流；缺省 `library`） | `skills:read` |
| `GET` | `/api/v1/skills/{ns}/{name}/metadata` | 详情元数据（既有；**本版加 `when_to_use` 与 `compatibility` 两个顶层字段**，见附录 A） | `skills:read` |
| `GET` | `/api/v1/skills/{ns}/{name}/tags` | 三组词（分类 / 公开标签 / 我的标签） | `skills:read`；我的标签另受库条目谓词 |
| `POST` | `/api/v1/skills/{ns}/{name}/library` | 加入（引用） | `skills:write` |
| `DELETE` | `/api/v1/skills/{ns}/{name}/library` | 移除加入引用 | `skills:write` |
| `PUT` | `/api/v1/skills/{ns}/{name}/category` | 设置 / 替换公开分类；body `{"key":"doc"}` | `skills:write`，所有者 / `admin` |
| `DELETE` | `/api/v1/skills/{ns}/{name}/category` | 清除分类 | 同上 |
| `POST` | `/api/v1/skills/{ns}/{name}/tags/public` | 贴公开标签；body `{"name":"PDF"}` | 同上 |
| `DELETE` | `/api/v1/skills/{ns}/{name}/tags/public?name=PDF` | 摘公开标签 | 同上 |
| `POST` | `/api/v1/skills/{ns}/{name}/tags/my` | 贴我的标签；body `{"name":"客户项目"}`，没有定义就新建 | `skills:write`；不在库 → 403，条目休眠 → 409（§错误形状） |
| `DELETE` | `/api/v1/skills/{ns}/{name}/tags/my?name=客户项目` | 摘我的标签（定义保留） | 同上 |
| `GET` | `/api/v1/me/tags?q=&name=` | 我的标签定义 + 计数 + 说明（≤100 一次给全）。`name` 按服务端规范化（NFKC / 去首尾空白 / 压缩空格 / 小写）解析**一个**定义，供 CLI 的 `rename` / `describe` 把旧名换成 id；给了 `name` 就忽略 `q`，没有匹配给空列表 | `skills:read` |
| `POST` | `/api/v1/me/tags` | 新建定义；body `{"name","description"?}` | `skills:write`，本人 |
| `PATCH` | `/api/v1/me/tags/{id}` | 改名 / 改说明；body 任选 `{"name"?,"description"?}` | 同上 |
| `GET` | `/api/v1/categories` | 公开分类词表 + 公域计数（启用 + 停用的种子词一次给全，退役不列） | `skills:read` |
| `GET` | `/api/v1/public-tags?q=&limit=&cursor=` | 公开标签词 + 公域计数（默认 50，`all` 由 CLI 循环） | `skills:read` |
| `POST` | `/api/v1/skills/{ns}/{name}/restore` | 恢复软删除的 skill | `skills:write`，所有者 |

`GET /api/v1/skills` 的筛选：`q`、`category`（可重复，取「或」）、`my_tag` / `public_tag`（可重复，取「且」）、`source`（可重复，取「或」）、`sort=recent|name`、`show=live|draft|offline|deleted|all`（缺省 `live`）、`limit`、`cursor`。**三个标签类筛选收名字**（分类名字、公开标签名字、我的标签名字），服务端用 `TagNames` 规范化后解析；不存在的名字给空结果，不是 400。只有 `PUT …/category` 的 body 用分类 **key**。`scope=public` 时 **`q`、分类、公开标签至少一个，否则 400**，且**排序只有相关度**（`sort` / `show` / `source` / `my_tag` 一律拒绝，公域没有「我的」这回事）；`show=deleted` 只接受 `source=created` 或省略，并拒绝三类标签过滤。

**`scope=library` 与 `scope=public` 走两条 use case**：库列表是新增的 `LibraryListUseCase`（与其余用例同在 `com.skillmasterai.usecase` 包；读 M12 的表，自带结果集、来源优先级、生效谓词、`show` 状态）；公域检索是今天那条检索路的扩展——**策略在 M8 的 `SkillSearchService`，SQL 在 M12 的 `SkillCatalogService` / `SkillCatalogRepository`**（`SkillCatalogService` 的类注释写明这个分工），本版把分类与公开标签的过滤加进这条 SQL，相关度公式与权重不动。控制器按 `scope` 分流，两条路**在控制器层映射到同一个响应 record（今天的 `SearchResponse.Card`，在 `api/` 里）与同一种 `next_cursor`**，但**不共用查询**——库列表没有相关度、不按 `q` 排序、默认只看可消费。共享形状放在传输层而不是复用 M8 的 `SkillCard`：`SkillCard` 在 `modules/search` 里，M12 引它就是 M12 → M8 的环（`SkillCatalogService` 的类注释原话：两者互相依赖就再也无法分开推理）；把两者塞进一个 `if` 会让检索签名长出库语义。`/web/library` 调的是同一个 `LibraryListUseCase`。契约测试：同一份 skill 在两个 scope 下返回的字段集合与 `source` / `state` 判定一致。

**定义删除没有 API 路由**：它只在 `/web/tags/{id}`，且是控制台的红色弹窗（[ADR 0079](../../decisions/0079-cli-manages-tags-and-library-references.md)）。

### 传输契约（`/web`，会话 + CSRF）

| 方法 | 路径 | 作用 |
|---|---|---|
| `GET` | `/web/library?q=&tag=&my-tag=&public-tag=&sort=recent\|name&show=all\|deleted&p=` | 「我的 skill」完整库；`show` 只有两个值（界面只有「在用 / 已删除」两个页签，缺省 `all`）；`tag` 是分类 id；来源是行内标签，**不提供来源筛选** |
| `GET` | `/web/discover?q=&tag=&p=` | 公域结果（界面只有分类筛选；`tag` 是分类 id；`p` 的固定页大小是 **6**，与设计件一致——不是库列表的 10） |
| `GET` | `/web/discover/recommended?cursor=&limit=6` | 为你推荐的一批（keyset；「不在库」= `identified.source IS NULL`——在公开 ∧ 在线这个域里，它与「条目生效」等价） |
| `GET` | `/web/tags?tab=public&q=` | 标签页两份清单（`tab=public` 开公开那份；缺省是我的标签，没有 `tab=private`） |
| `GET` | `/web/categories` | 公开分类词表 + 公域计数（与 `/api/v1/categories` 同形；控制台不能硬编码词表） |
| `GET` | `/web/public-tags?q=` | 公开标签词 + 公域计数（信息页候选下拉） |
| `POST` | `/web/tags` | 新建我的标签定义 |
| `PATCH` | `/web/tags/{id}` | 改名 / 改说明 |
| `DELETE` | `/web/tags/{id}` | **删除定义**（红色弹窗；连同休眠贴法） |
| `GET` | `/web/skills/{ns}/{name}/tags` | 信息页三组词 |
| `POST` / `DELETE` | `/web/skills/{ns}/{name}/library` | 加入 / 移除（移除走弹窗） |
| `PUT` / `DELETE` | `/web/skills/{ns}/{name}/category` | 设置 / 清除分类 |
| `POST` / `DELETE` | `/web/skills/{ns}/{name}/tags/public` | 贴 / 摘公开标签 |
| `POST` / `DELETE` | `/web/skills/{ns}/{name}/tags/my` | 贴 / 摘我的标签 |

`/web/library` 的 `show=deleted` 只含我创建的软删除 skill；该模式下 `tag` / `my-tag` / `public-tag` **被忽略**（前端会把它们从地址清掉，服务端也不据此过滤——两处都不许留一个「地址上还有、结果却不按它」的筛选）。

`/web/skills` 的**既有一半**仍是作者面的读（版本、草稿）；本版在它下面新加的是三组词与三种整理写（库 / 分类 / 两种标签），它们按 skill 寻址、按该 skill 的谓词授权。**库列表不复用它**——两者的结果集与权限不同，混在一个路由里会让「默认只看可消费」这条口径无处安放。

### 响应形状

列表卡片（`GET /api/v1/skills` 的 `skills[]`，在今天的 `api/SearchResponse.Card` 上加字段；两个 scope 共用）：

```json
{
  "id": "01J…",
  "namespace": "dev",
  "name": "pdf-tools",
  "description": "把 PDF 拆页、合并与压缩。",
  "when_to_use": "需要拆分、合并或压缩 PDF 时。",
  "source": "joined",
  "state": "live",
  "version": "1.2.3",
  "updated_at": "2026-10-20T09:12:00Z",
  "deleted_at": null,
  "category": {"key": "doc", "name": "文档与演示"},
  "public_tags": [{"key": "pdf", "name": "PDF"}],
  "my_tags": [{"id": "01J…", "name": "客户项目"}]
}
```

- `source` 在公域检索里只在该结果**已在库**时出现；`my_tags` 只在库条目生效时出现。
- `state` 是显示状态（`live / draft / offline / deleted`），由谓词算；不是新列。
- `next_cursor` 保持今天的 keyset 语义；CLI 不把它交给用户。

三组词的响应：

```json
{
  "category": {"key": "doc", "name": "文档与演示"},
  "public_tags": [{"key": "pdf", "name": "PDF"}],
  "my_tags": {
    "readable": true,
    "reason": null,
    "tags": [{"id": "01J…", "name": "客户项目", "description": "交付期内客户随时要用的 skill"}]
  },
  "library": {"source": "joined", "active": true, "joinable": false, "can_remove": true}
}
```

`library` 是信息页画「加入 / 移除 / 不画并说明原因」所需的四个事实：`source` 是显示来源（可为 null），`active` 是条目是否生效，`joinable` 是现在能不能加入，`can_remove` 是能不能移除这条引用。四个都由同一组谓词算，界面不自己推导。

`my_tags.reason` 取 `null` / `not_in_library` / `dormant`；skill 不可见时整个请求按 404 处理，不泄露存在性。

`GET /web/library` 的响应（`skills` 里的每一行就是上面的卡片）：

```json
{
  "skills": [],
  "page": 1,
  "page_size": 10,
  "total_entries": 37,
  "effective_entries": 32,
  "filtered_total": 12
}
```

- `page_size` 固定 **10**，沿用「我的 skill」今天的页大小（`SkillsPage.tsx:39`），不是新选一个数。
- `total_entries` = 库里全部条目（我创建的 + 共享给我的 + 我加入的，含休眠、含我软删的），**不受 `q` / 标签 / `show` 影响**；`effective_entries` = 其中生效的（`live + draft + offline`），同样不受筛选影响；`filtered_total` = 当前筛选后的条数，分页用它。
- **PRD 的三种空态**由此判得开：库空（`total_entries = 0`）、全部休眠（`effective_entries = 0` 且 `total_entries > 0`）、无已删除（`show=deleted` 且 `filtered_total = 0`）。`filtered_total` 另让列表能说设计件那一句「没有匹配的 skill。」，而**不把它混进上面三种**——只用一个 `total` 会把三者说成同一句话。
- **「只剩我软删的 skill」落「全部休眠」这一态**，不另开一种：它们在「已删除」页签里，恢复后回到在用；PRD 那句「可能已下线或暂时不可见，恢复后会回来」对它们依然成立。但**「已删除」页签必须同时有内容**，否则「恢复」没有入口——页签的显隐只看 `total_entries`，不看 `effective_entries`。

`GET /web/discover` 的响应（分页要能画页码，所以带总数）：

```json
{"skills": [], "p": 1, "page_size": 6, "total": 123}
```

`GET /web/tags` 的两个页签各有一份行形状：

```json
// 缺省（我的标签）
{"tags": [{"id": "01J…", "name": "客户项目", "description": "…", "used_in": 3, "dormant_in": 2}]}
// tab=public
{"tags": [{"key": "pdf", "name": "PDF", "used_in": 2}]}
```

`dormant_in` 是「条目当前不生效」的贴法数：删除弹窗要写清「N 个可见 + M 个你现在看不到的」，只给 `used_in` 就说不出后半句。

`tab=public` 的 `used_in` 只算我创建的、生效中的 skill（见 §规范化与计数），与 `skills public-tags` 的 `公域` 不是同一个数。

**写操作回答「有没有变」**，不用 204：加入 / 移除库、贴 / 摘两种标签、设置 / 清除分类都返回 `200 {"changed": true|false}`。`changed=false` 是 no-op 的合法结果——重复加入（含休眠引用）、重复贴同一个词、同一个分类再设一次、清一个本来没有的分类——CLI 据此说「已经在你的库里。」/「已经贴过」/「已经是这个分类」，而不是把一次没变的事说成成功。摘标签没有 no-op（没贴就是 404），但仍回同一形状的 `{"changed":true}`，让调用方只需要读一种响应。定义的新建 / 改名 / 改说明返回 `{"id","name","description"}`（`used_in` 只在列表行里给），删除返回 `{"changed": true}`。

三条词表读的每一行（CLI 与 `/web` 共用同一个字段名，避免两套说法）：

```json
// GET /api/v1/me/tags              → {"tags": [{"id","name","description","used_in"}]}
// GET /api/v1/categories           → {"categories": [{"key","name","state","public_total"}]}
// GET /api/v1/public-tags?q=&limit= → {"tags": [{"key","name","public_total"}], "next_cursor": "…"}
```

`used_in` 是「这一行的标签在**某个范围**内的生效贴法数」：私有定义算我库里的生效贴法，公开词的管理页算我创建的、生效中的 skill 上的贴法（差别见上一节）；`public_total` 一律是「公域可发现且带该词的 skill 数」。两个名字不互换。

### 错误形状

沿用现有 `{"error":{"code","message","details":[…]}}`（`common/ApiError.java:22-44`）。**码用小写**，与既有的 `skill_not_found` / `invalid_upload` 同一写法。本版新增五个码：

| code | HTTP | 何时 |
|---|---|---|
| `skill_not_joinable` | 409 | **可见**，且来源已是创建 / 共享（私有的也算，它已在我的库里），或公开但当前不可加入（下线 / 无当前版本） |
| `library_reference_missing` | 409 | `DELETE …/library` 时**没有 `skill_library_join` 行**（来源是创建，或本来就没加入，或共享但从未加入）。判据只有「这一行在不在」，**不看显示来源** |
| `library_entry_dormant` | 409 | 条目休眠时写我的标签（来源是加入或共享都一样） |
| `tag_not_attached` | 404 | 要摘的标签不在这个 skill 上 |
| `tag_definition_not_found` | 404 | 定义 id 不存在，或不是本人的（一个答案，不确认存在性） |
| `skill_not_deleted` | 409 | `POST …/restore` 指向一个没有软删的 skill——它看得见，只是不需要恢复，所以不能报 404 |

其余沿用：不可见 / 非公开 / 已删除 → 404 `skill_not_found`（**不泄露存在性**）；可见但不是所有者 / `admin` → 403 `forbidden`；名字格式 / 重名 / 撞分类 / 上限 / 词表外 → 400 `invalid_request`（`details[].field` 指出字段，`issue` 区分 `name_taken` / `category_name_taken` / `tag_limit_reached`）。**不新增“标签不存在”与“无权限”混用的码**。

**我的标签写入的拒绝按「能不能加入」分两支**（读面用 `my_tags.reason`，写面必须给出路）。错误体沿用 `details[]{field,issue}`，所以「为什么」写在 `{"field":"reason","issue":"not_in_library"}` 这一项里：

- **可见但不在库**（只可能是公开、且没有给我的授权）→ 403 `forbidden`，`details` 含 `{"field":"reason","issue":"not_in_library"}`，message 两支：
  - 可加入（有当前版本、未下线）→ 「先把 <地址> 加入你的库：`skillmaster skill join <地址> --yes`」；
  - 当前不可加入（下线 / 无当前版本）→ 「这个 skill 现在加不进库；上线后可以再加入并贴标签」。
- **条目休眠**（来源是我加入的：软删 / 私有化且无授权；来源是共享的：下线 / 无当前版本 / 软删）→ 409 `library_entry_dormant`。共享只读 **不是** 403 —— 它在库里，只是条目现在不生效。

两种都和 404 分开：404 只留「看不见」，否则会把「存在但你没有权限」变成一次探测。判定复用谓词（`source` / `active` / 可加入条件），控制器只做映射。

### 并发与上限

- **我的标签定义 ≤100**：`POST /me/tags`、改名、以及**贴标签时的「没有定义就新建」**，全部走同一个「确保定义存在」用例（建议 `usecase/EnsureMyTagDefinitionUseCase`），在事务里先取 `pg_advisory_xact_lock(hashtextextended('my-tags:' || user_id, 0))`，再计数与插入。自动新建绕开这层，就等于给 100 开了一条旁路。
- **我的标签每 skill ≤20**、**公开标签每 skill ≤10**：事务里先取 `pg_advisory_xact_lock(hashtextextended('skill-tags:' || skill_id, 0))`，再数生效贴法 / 公开标签行。**不用 `SELECT … FOR UPDATE` 锁 skill 行**：那会把发布、下线、授权变更一起堵在标签写入后面，而它们与标签上限没有关系；一把按 skill 命名的咨询锁只串行标签写。
- **锁序固定：先用户、后 skill**。一个事务里既自动建定义又贴标签时（存在），按这个顺序取，避免两条写路径反向加锁。
- 两把锁都是 `xact` 级，事务结束自动释放；失败与超限走 400，不重试。
- **加入 / 移除**：`INSERT … ON CONFLICT DO NOTHING` / `DELETE`；来源判定在同一事务里，重复加入是 no-op；移除时没有这条引用 → 409 `library_reference_missing`（不静默成功，否则用户会以为删掉了什么）。
- **上限的判定写在 use case**，不在 CHECK 里——跨行计数不适合约束；测试覆盖并发（§[`test-plan.md`](test-plan.md) T12 / T48）。

### 审计

- 动作（`AuditEvent.action`）：`library_join` / `library_leave`、`category_set` / `category_clear`、`tag_public_add` / `tag_public_remove`、`tag_my_attach` / `tag_my_detach`、`tag_definition_create` / `tag_definition_rename` / `tag_definition_describe` / `tag_definition_delete`；恢复沿用既有的 `restore`。
- 每条在**同一事务**里写，带 `actor` / `target_type`（`skill` 或 `my_tag`）/ `target_id`；`detail` 记录 before / after 里可安全外泄的部分（分类 key、公开标签 key、名称长度 / id）。
- **我的标签的 detail 不存名字**，只存定义 id 与 skill id；公开标签与分类的名字可以进审计。

### CLI 映射

| 命令 | 请求 |
|---|---|
| `skills list …` | `GET /api/v1/skills?scope=library&…`（默认 `limit=50`） |
| `skills search …` | `GET /api/v1/skills?scope=public&…` |
| `skills categories` | `GET /api/v1/categories` |
| `skills public-tags [q] [--all]` | `GET /api/v1/public-tags`（`--all` 在 CLI 内循环 cursor） |
| `skills my-tags [q]` | `GET /api/v1/me/tags` |
| `skills my-tags add / rename / describe` | `POST /api/v1/me/tags` / `PATCH /api/v1/me/tags/{id}` |
| `skill tags` | `GET /api/v1/skills/{ns}/{name}/tags` |
| `skill tag add / remove` | `POST` / `DELETE …/tags/{public\|my}` |
| `skill category set / clear` | `PUT` / `DELETE …/category` |
| `skill join / leave` | `POST` / `DELETE …/library`（本地 `--yes` 闸） |
| `skill show` | `GET …/metadata` + `GET …/tags`（两个读；CLI 合成一张卡） |
| `skill restore` | `POST /api/v1/skills/{ns}/{name}/restore` |

- `--no-login` 在 CLI 层实现：**不触发自动登录**；没有凭据立即失败并指向 `skillmaster login`。请求本身仍带凭据，不改变服务端的鉴权。
- `skills list --all` 与 `skills public-tags --all` 在 **CLI 内循环** `next_cursor`；游标不出现在 stdout、帮助或错误里。
- `skills my-tags rename` / `describe` 的旧名 / 名字先在 `GET /me/tags?name=` 里解析成 id（规范化由服务端做，CLI 不比较显示名）；找不到时按空结果 + 指向 `skills my-tags` 的可读提示，不猜。
- `--json` 继续拒绝；`--yes` 只用于 `join` / `leave`，与 `version discard` 同一套本地闸（`version.go:1311-1331`）。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P0 数据与谓词** | V26–V28；`ModuleMap` 与清库清单；`TagNames`；`SkillPredicates`；仓储骨架；迁移 / 谓词单测 | #6、#7、#23 的谓词部分 |
| **P1 读面** | `/api/v1/skills` 的 `scope` 与筛选；`/tags`、`/categories`、`/public-tags`、`/me/tags` 读；`/metadata` 加 `when_to_use` / `compatibility`；`/web/library`、`/web/discover`、`/web/discover/recommended`、`/web/tags` 读；检索扩展（`when_to_use` + 公开标签 + 多词） | #1、#8、#31、#37 的**服务端部分**；#14 / #16 的读 |
| **P2 写面** | 加入 / 移除、分类、公开标签、我的标签贴 / 摘、定义新建 / 改名 / 改说明、`skill restore` 的 API 路由；审计；上限与并发；跨用户隐私与「加入不挡加载」的负向用例 | #2–#7、#12、#13、#15、#17–#24、#32、#34、#36 的**服务端部分** |
| **P3 控制台** | 发现页、标签页（含定义删除的红色弹窗与 `DELETE /web/tags/{id}`）、库列表重塑、信息页三组词；复用设计稿结构 | #9–#11、#20 的界面部分、#25、#26、#33、#35、#38 |
| **P4 CLI 与网关** | 命令面、`--no-login`、`--yes`、帮助；`platform_api_version` 9→10 与网关 `SKILL.md` 同一提交 | #27–#30、#36 / #37 的 CLI 部分、#39–#53 |
| **P5 收口** | 性能测量（5000 公域 / 200 库，计数与标签过滤各一条 `EXPLAIN (ANALYZE)`）、完整回归、文档与 QA 探针 | §指标 |

**验收列只写这一期能自己证明的部分**。一条验收横跨多期时（#36 先在 P2 做服务端、再在 P4 做 CLI），各期证明自己那一层，**整条在最后触到它的那一期验**；所以 P1 / P2 里没有 CLI 验收，P3 里没有服务端接口验收。每条的最终判定见 [`test-plan.md`](test-plan.md) §验收映射。

**每期明确不做**：

- P0 不开任何 HTTP 路由；不写控制台。
- P1 不做写操作、不做推荐算法；检索只加字段与多词，不改相关度公式。
- P2 不做定义删除的 API；不做团队 / 组织库。
- P3 不重写设计稿，只搬；不改外壳。开工前对照一次设计稿与本次提交（`adf5b14` 之后的 `design/`）：若设计稿在实现前又动过，先重跑改动组检查并重新出图，**不按旧截图实现**。
- P4 不动 v13 的七个页面动作（`version restore` 仍是页面交接，与本版的 `skill restore` 不是一件事）。
- P5 不跑全量设计检查，除非用户批准。

**`platform_api_version` 为什么在 P4 而不是 P1**：它描述的是**网关 `SKILL.md` 给 agent 的那份协议**（CLI 命令的形状），不是服务端路由表。P1 / P2 先有接口但没有命令，提前把它写成 10 就等于让 agent 读到尚不存在的命令；到 P4 与命令面、网关文档同一提交里 9 → 10，读到的版本与能执行的命令永远同批。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 控制台的 `p` 页码怎么做分页？ | **`/web` 用 `p` + 固定页大小（`LIMIT/OFFSET`）**，因为产品要求页码；`/api/v1` 继续 keyset 游标。`/grants` 已有 offset 先例（`ListSkillGrantsUseCase.java:38,74-77`）。 | 5000 公域 + 200 库的夹具上量 `p95`；若深页超过预算，改成地址里带 `c`（cursor）且 `p` 只做显示 |
| Q2 | 检索要不要换索引？ | 本版**不引扩展**：`V1__baseline.sql:11-13` 写明 P0 不需要任何扩展（`pg_bigm` 故意缺席）；`pg_trgm` 的 3-gram 对 1–2 个汉字基本失效（[ADR 0062](../../decisions/0062-grep-is-a-scoped-scan-not-an-index.md) §理由）。先保持 `ILIKE`，只在 `when_to_use` 与公开标签上扩匹配面。 | 在 5000 条夹具上 `EXPLAIN (ANALYZE)`；p95 超预算再单独开 ADR 评估 pg_bigm / 物化检索行 |
| Q3 | 计数现算还是缓存？ | **现算**。计数只出现在词表与管理页，规模是「10 个分类 + 用户自己的词」。 | 词表 p95 超过 200ms 时，先加覆盖索引，再考虑物化 |
| Q4 | 推荐流用独立路由还是检索的一个 scope？ | **独立路由** `GET /web/discover/recommended?cursor=`：它没有 `q`，也不参与相关度排序；塞进检索会多一个「没有输入」的例外。 | 用 `discover` 组的首屏 / 滚动断言验证；批次 6 与游标由 TD 固定 |
| Q5 | 上限的并发怎么保证？ | 定义数用**用户级咨询锁**（自动新建定义也走同一用例），贴法数用**按 skill 命名的咨询锁**，不用行锁；锁序先用户后 skill，都在事务内，不做「先数再插」的裸检查。 | 并发测试（T48）起 10 个请求，断言最终不超限、失败可读，且同一 skill 的发布不被标签写阻塞 |
| Q6 | `description` 的空值怎么表示？ | 数据库 `NULL` = 没有说明；`--text ""` 与界面清空都写 `NULL`。API 返回 `null`。 | 新建 / 改名 / 清空三条用例断言 |
| Q7 | 公开标签将来改名 / 合并怎么办？ | 本版以 `tag_key` 为单位；将来合并是**一次数据迁移**（重写 key 与显示名），不是新增一层定义表。真要引入定义表时，形状是「新表 + 一批 `INSERT … SELECT DISTINCT tag_key` + 挂载表加 `definition_id` 并与旧 `tag_key` 双写一版 + 回填 + 摘列」，代价是**一次双写期与一次回填**（表规模 = 贴法行数），不是原地改字段；本版不预付这张表，因为今天没有任何产品语义需要用 id 指代一个公开词。 | 本版不做；ADR 0083 已把治理排除 |
| Q8 | `/web` 与 `/api/v1` 的写接口会不会漂？ | 两套控制器都只调**同一个 use case**，请求 / 响应 record 各自定义；差异只在身份与 CSRF。 | 同一用例的 API 与 web 集成测试各一条，断言副作用一致 |

## 附录 A · 与现有代码的关系

| 文件 / 符号 | 本版动作 |
|---|---|
| `V1__baseline.sql:327-341` 的 `skill` | 加 `category_key`（V28）；`V1` 之后由后续迁移加上的列都不动（`V19:10` `public_rung`、`V21` 的 `when_to_use` 与九条列表投影列、`V24:9` `offline_at`） |
| `ModuleMap.java:92-93` | M12 增加 `skill_library_join`、`skill_public_tag`、`my_tag_definition`、`my_tag_attachment`、`skill_category` |
| `SecurityConfig.java:103-130` | 新增 `/web/library`、`/web/discover`、`/web/discover/**`、`/web/tags`、`/web/tags/**`、`/web/categories`、`/web/public-tags` 的 `authenticated()`；不加就落 `:131` 的 `permitAll` |
| `truncate-business-tables.sql:40-61` | 加四张**业务**表（`skill_library_join` / `skill_public_tag` / `my_tag_definition` / `my_tag_attachment`）；**`skill_category` 是平台种子，不加入清库清单**——理由不是「多清一张表」，而是后续每个 IT 都要有词表；`skill.category_key` 指向它，`TRUNCATE … CASCADE` 会连 `skill` 一起清（`skill` 本来就在清单里，所以这不是额外的危害） |
| `SkillCatalogRepository.java:53-58` | **只扩 `TEXT_FILTER`** 到 `when_to_use` 与公开标签，多词 AND；`:36-40` 的 `RELEVANCE` 与权重、ordering id 不动——标签与适用场景只增加命中，不改变排序分数 |
| `SkillCatalogRepository.java:66-151` | 保留消费列表的 keyset；新增库 / 公域两个 scope 与筛选 |
| `SkillCatalogRepository.authorPage` | 不删；作者面读仍由它服务 |
| `SkillRepository.permitted` | 保留；库与标签的可见性复用它（或抽成 `SkillPredicates`） |
| `RestoreSkillUseCase.java` | 复用；只在 `/api/v1` 加一条路由 |
| `WebSkillController.java:567` | 保留网页 restore；库列表不复用 `/web/skills` |
| `AuthoredSkillResponse.java`（`/web/skills` 详情） | 同样加两个顶层字段 `when_to_use` / `compatibility`，与 `SkillMetaResponse` 共用同一抽取方法。信息页读的是这个响应而不是 `/metadata`；不加这两个字段，「客户端不再各自解析 frontmatter」就不成立 |
| `SkillMetaResponse.java:30-37` | 加两个顶层字段：`when_to_use`（**与 `SkillRepository.java:169-171` 的投影同一抽取规则**：`metadata.when_to_use` 优先、顶层兜底）与 `compatibility`（frontmatter 顶层，没有则 `null`）。`skill show` 与信息页都读这两个字段，**客户端不再各自解析 frontmatter**（实现前 `SkillPage.tsx` 自己解析，是「同一规则两处」的现成例子；v14 已删掉那段） |
| `SkillPage.tsx:1240-1241` | 信息页标签占位替换成三组词组件（从设计稿搬） |
| `SkillsPage.tsx` | 改读 `/web/library`；保留作者管理入口 |
| `main.go:157-165` / `:111-154` | `skills` / `skill` 帮助加入新命令 |
| `lifecycle.go` | `join` / `leave` / `restore` 落在这里（与现有生命周期动作为一组） |
| 新增 `cmd/skillmaster/tags.go` | 标签与分类命令；`skills my-tags` 也在这里 |
| `client.go:251-256` | `ListPageSize` 100 → 50（v14 契约）；注释写明它只约束 `skills list` 的默认页，`public-tags` 用 50 是同一常量、`my-tags` 与 `categories` 一次给全不走它；新增 scope / filter 参数 |
| `client.go:557-592` | 补具名映射：409 `skill_not_joinable` / `library_reference_missing` / `library_entry_dormant`，404 `tag_not_attached` |
| `gateway/skillmaster/SKILL.md:5-6` | `platform_api_version` 9 → 10 |

## 附录 B · 来源与出处

- 需求：[`prd.md`](prd.md) 的 53 条验收与 §CLI 命令面。
- 决策：[ADR 0078](../../decisions/0078-a-category-is-one-shelf.md)、[0079](../../decisions/0079-cli-manages-tags-and-library-references.md)、[0080](../../decisions/0080-cli-covers-non-high-risk-operations.md)、[0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)、[0082](../../decisions/0082-public-and-private-with-my-library.md)、[0083](../../decisions/0083-two-tag-layers.md)、[0084](../../decisions/0084-l1-search-extension.md)。
- 现有代码的定位见 §附录 A；行号对应的提交是 `adf5b14` 之后的工作树。
- 表归属规则：[`ModuleMap`](../../../skillmaster-server/src/main/java/com/skillmasterai/common/ModuleMap.java) 与 [`TableOwnershipTest`](../../../skillmaster-server/src/test/java/com/skillmasterai/TableOwnershipTest.java)。
- 时间与身份：`Timestamps`（RFC3339 UTC、截到秒）与 `Ulid`（不透明、可按创建时间排序）。

## 附录 C · 已测量的数据

本版尚未实现，没有新测数字。要引用的预算都来自 [`prd.md`](prd.md) §指标（库列表 ≤50 行、公域 p95 不超过私域 2 倍、标签过滤 p95 不超过 1.5 倍）；实现后按 [`test-plan.md`](test-plan.md) §环境与数据 的口径补测。
