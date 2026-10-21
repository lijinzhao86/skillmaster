# v6-version-management · 技术设计

> **最后更新**：2026-10-20
> **状态**：实现与验收状态见 [`README.md`](README.md) §状态（此处不复述）；未执行项见 [`test-plan.md`](test-plan.md) §结果。
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

<!--
本章之外的硬约束：
- 测出来的数字必须带口径（见下方表格的「口径」列），让读者能自己复现。
- 引用外部事实必须带出处，且区分一手与二手。
- 不复述 ADR 的理由；不负责任何产品决策的论证。
-->

## 架构

这一版只动**版本对象自己的表达**，不动权限、不动寻址、不动网关。

```
CLI skill submit --note
        │  multipart: file + note
        ▼
POST /api/v1/.../versions                      ── M7 ──► INSERT 写 draft_name 与 note；version 留 NULL
        │
网页草稿单版页：改名 / 补填说明（可多次）
        │  PATCH {name} / PATCH {note}
        ▼
PATCH /web/.../versions/{version}/name|note    ── M7 ──► 仅 draft 行可写
        │
网页版本 tab 的单版页：选版本号（说明在概览里改，发布不再写一遍；上线动作在页面上执行）
        │  JSON: {version, release_name?}
        ▼
POST /web/.../publish                          ── M7 ──► 分配 release_name、冻结说明（发布请求不接收 `note`）、前移指针
        │
        ├── 作者面 GET /web/skills/{ns}/{name}         → versions[].note
        └── 消费面 GET /api/v1/.../versions            → versions[].note
```

**边界说明**

- **版本号不在提交时产生。** `skill_version.version` 在草稿行是 NULL；只有 `POST …/publish` 在分配 `release_name` 的同时写它。`SKILL.md` 的 `version:` 不再被读作版本号——它留在 `frontmatter` 里原样透传。
- **写入口有两个阶段、三条路**：提交（INSERT）、草稿期修改（只对 `draft`）、发布（`draft → published`）。`published` 之后没有**成功的**写路由——改名 / 改说明的入口仍在，但对非 `draft` 行一律回答冻结（`draft_name_is_frozen` / `note_is_frozen`）：所以「上线后不能改」不是靠权限挡，是靠**每条写路由的状态守卫**。
- **读出口有两个面**：作者面与消费面都从 M7 的同一行读；消费面只读 `published` 行（[ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md) §决定 8 的过滤不变），所以草稿的说明天然不会泄露。
- **说明与版本名称都不参与 M12**：它们是版本的，不是 skill 的；`skill` 行的列表投影不包含它们，搜索也不索引它们。这是 [ADR 0053](../../decisions/0053-skill-metadata-is-its-own-module.md) 的边界在起作用——版本说明不必为了「列表读得到」而搬进元数据。**生命周期不再清空指针**：当前版本不能直接下架；上线 / 回滚通过 M12 的 `recordPublish` 移动指针，下架不动指针。M7 仍不直接写 `skill` 表。
- **网页上所有改变版本状态的动作发生在版本 tab 的单版页上**（[ADR 0054](../../decisions/0054-lifecycle-actions-happen-on-a-version-page.md)；**本版修订其第 4 条**：草稿的改名、改说明与丢弃也移进单版页）：版本 tab 是唯一清单；地址钉住一版时，清单换成那一版的单版页（`/skills/{ns}/{name}?tab=versions&version=<pin>`），改名、改说明、丢弃、上线、下架、重新上架都在那一页上执行；**改名与改说明不占「操作」的动作块**：草稿的标题与概览里的说明各自就地编辑（铅笔 → 输入框 → 保存 / 取消，Esc 取消；2026-10-20 定）；**单版页内部分「概览」「改动」与「操作」三栏**（同一路由的 `sub=diff` / `sub=actions`，缺省是概览）：说明与时间线在概览（阅读；草稿的说明可就地编辑），逐文件的差异在改动，状态变更动作在操作（普通动作在上、危险操作在页底）；没有基线不画改动，没有动作不画操作；**地址直接点名一个这一档看不见的 `sub`（改动 / 操作）时落回概览**，不画一栏空壳；改动的基线默认是上一版，`from` 可由用户选成任何看得见的版本（含草稿）；改动的文件树上方有一颗按路径收窄这份清单的筛选框（值写进地址 `fq`，只筛清单，右栏已经展开的差异不动）。**文件栏的提交行右端写着这一版的名称与版本号，它是回这一版页面的链接**（与单版页页头的「查看文件」互为镜像；草稿落到 `draft:<id>`；时间仍是纯文本）。清单只保留导航（行内不画下载/复制/查看差异；只读入口在「更多操作」菜单里）；**每一行整行可点**——名称是唯一的真链接，`::after`（`position:absolute;inset:0`，`li` 是定位锚点）把它铺满整行，悬停整行压 `--wash`；不给 `li` 加 `role="button"`、也不把 `li` 包进 `<a>`（语义保持一个导航链接）。**每行右上角是「更多操作」菜单**：`role="menu"` + `menuitem` 链接（查看文件 / 查看改动 / 该状态下可做的动作），**只导航、不执行**——上线、下架、重新上架、回滚、丢弃仍去那一版的「操作」栏按下并确认（ADR 0054）；按状态与档位给项，一项都没有（调用档、已丢弃）就不画；按钮 `position:relative;z-index:1` 压在整行链接之上（只给按钮，外壳不建层叠上下文，否则固定定位的菜单会被关在行里）。**清单上面是状态过滤（带计数）、搜索与一个占位的「新建版本」**：过滤与搜索读同一份按档位过滤后的列表（计数不随搜索变），两者叠加，选择写进地址（`filter` / `q`），从单版页返回时原样带回；「新建版本」在本版不跳转（创建与编辑版本在下一个版本做，§不做 #10），且**只在 `write` 及以上画**。**六档各看一面**：行数、计数章、过滤胶囊、行菜单与该按钮的显隐全由档位决定——调用与浏览没有「新建版本」，调用没有行菜单，写入只在自己提交的草稿上多一项「丢弃」，维护及以上含已下架与全部入口。**调用者的身份是版本号**：列表行、单版页标题与面包屑取 `@版本号`（不是 `draft_name`），行尾只写上线时间，**页面上**的版本事实只有 版本号 / 状态 / 首次上线 / 地址（不画内容摘要），时间线只有「首次上线」——`draft_name`、提交人、提交时间、状态变更史与文件数都**不进消费面 API 的响应**（`SkillVersionsResponse` 里没有这些）；网页给调用档的版本投影仍来自作者面资源（`AuthoredSkillResponse`），**页面只画四项、不画这些字段**——两件事分开说，避免把「响应里没有」与「界面上不画」混成一句。**`digest` 仍留在响应里**（旧字段一个不变，钉住一版要它），只是界面上不画。两屏都读作者面已有的版本数据，不新增资源；改动的分批不新增路由，在现有 diff 路由上补 `next` / `cursor`——今天的响应只有 `truncated`、没有续取（见 §改动：清单先行、内容按批）。**回滚**不新增路由：它就是以已上线版本为目标的 `POST …/publish`，入口放在当前版本页的「操作」——回滚不拿走任何东西（每一版都还在，只是指针移动），所以不进危险区、按钮不染红，但仍用确认弹窗（取消为默认焦点）。**上线草稿同样先弹窗确认**：弹窗说清名称与说明在这一刻锁定、调用者会读到它们（确认钮不染红）；确认之后才发 `POST …/publish`，等待 / 成功 / 失败都在同一张弹窗里（2026-10-20 定）。**丢弃草稿也不进危险区**：消费面读不到草稿、指针不动，它只结束作者自己这一版；但在「操作」栏里用描边按钮，弹窗确认的正文第一句说清这一版**无法找回**（[ADR 0060](../../decisions/0060-discarding-a-draft-is-not-dangerous.md)）。
- **skill 级下线是 M12 的服务开关，不是版本状态。** `skill.offline_at` 非空即下线；消费面在解析 skill 后、解析版本前检查它（可见但已下线 → 503 `skill_offline`；不可见 → 仍然 404，不泄露存在性），作者面与「我的 skill」不检查。搜索与公开列表过滤掉已下线。版本状态与指针都不动。

### 模块与依赖（DDD）

本版**不新开模块**：生命周期是 M7 版本模块的领域行为，作者 API 是 `api` 层的另一个入口。新代码落在家门口，不为了「v6 的代码好找」另起一个 `versionmanagement` 包——那会把一个聚合切成两半，两半各写一半状态迁移。

| 模块 | 角色 | 拥有 | 对外契约 | 可以依赖 |
|---|---|---|---|---|
| `modules/version`（M7） | 核心域 | `skill_version` 的读写、四态状态机、钉的语法、`draft:<id>` | `SkillVersionService`（提交、发布、丢弃、下架 / 重新上架、作者快照、版本集合）、`VersionPin`、`VersionState`、`VersionStatus` | namespace / access 的公开接口、audit、blob、common |
| `modules/skill`（M12） | 支撑域 + 列表投影 | `skill` 行与 V21 的投影列、`offline_at` | `SkillMetadataService.recordPublish` / `recordSubmission` / `recordDiscard`、`setOffline`、列表查询 | **方向是 M7 → M12**：`Access` / `SkillMetadata` / `VersionProjection` / `DraftProjection` 都在 `modules/skill`，版本模块引用它们；M12 不引 `modules.version`，也不读 `skill_version` |
| `modules/distribution` | 读模型 | 作者 / 消费快照、文件、diff 计算 | `SkillDistributionService`、`DiffService` | version 模块的公开服务、blob |
| `modules/ingest` | 防腐层 | 上传解析与校验 | `SkillUpload` / `SkillUploadValidator` | common |
| `usecase` | 应用层 | 一个用户动作的编排、事务、审计 | 各 `*UseCase` | 上述模块的公开服务 |
| `api` | 交付层 | 两个平面的控制器与 DTO | Controller | usecase、common；**不碰任何 `internal` 包** |
| `common` | 基础 | 错误码、ID、SemVer | — | 不依赖任何模块 |

**四条规则（已有测试强制；新增代码照它们落）**

1. **依赖只向内**：`api → usecase → modules.* → common`；模块之间只走公开服务，`internal` 包外面不可见（`ArchitectureTest.nothingOutsideAModuleMayReferenceAnotherModulesInternals`）。
2. **一条 SQL 只命名一个模块的表**（`TableOwnershipTest`）。作者 API 的版本列表由 M7 的查询给；`skill` 行的写仍由 M12 的 `record*` 完成，两者在同一事务里由 use case 串起来，不互相读表。
3. **交付层不判业务**：档位、状态、冻结都判在 use case / 领域服务里；控制器只解析钉、取身份、映射错误。
4. **写动作带期望状态**：所有状态迁移 SQL 都带 `WHERE state = <期望值>`，0 行不是成功（§版本状态机）。

**扩展点（本版只加一个值，不重写）**

- `VersionPin` 是 sealed：加 `Draft(id)` 让所有 switch 在编译期报出漏掉的分支；消费面的 `X-Skill-Version` 显式拒绝 Draft（附录 A）。
- `status` 是从 `state + isCurrent` 派生的纯函数（`VersionStatus.of`），不落库；网页、作者 API、CLI 读同一个函数的结果。
- diff 的「清单先行、内容按批」加在现有 `DiffService` 上（`next` / `cursor`、LRU、ETag），不新开端点；作者 API 复用同一份响应形状。
- 作者 API 的 DTO 与 `/web` 的 DTO 分开：前者是机器契约（snake_case、不带资源地址），后者服务网页；共用 `VersionSummary` 这一层读模型，不共用 JSON 形状。

**新增类的家**

| 类 / 文件 | 模块 | 职责 | 依赖 |
|---|---|---|---|
| `VersionStatus`（新） | version | 五值派生：`state + isCurrent → live\|history\|…` | `VersionState` |
| `VersionPin.Draft`（新） | version | `draft:<id>` 的语法与查找键 | — |
| `VersionRepository` 的查询（改） | version.internal | 集合分页 / 筛选 / 计数、按 draft id 查找、两条状态迁移 | — |
| `SkillVersionService` 的方法（改） | version | 改名 / 改说明 / 下架 / 重新上架 / 作者集合读；补 `revoked` 的档位过滤 | `VersionRepository`、blob、audit |
| `UpdateDraftNameUseCase`（新） | usecase | 一次改名：权限、状态、审计 | `SkillVersionService`、`Callers` |
| `UpdateDraftNoteUseCase`（新） | usecase | 一次改说明 | 同上 |
| `RevokeVersionUseCase`（新） | usecase | 下架 / 重新上架；拒绝当前版本 | 同上 |
| `ListAuthorVersionsUseCase`（新） | usecase | 作者 API 的集合读：档位过滤 + 分页 + 计数 | `SkillVersionService` |
| `ReadAuthorVersionUseCase`（新） | usecase | 作者 API 的单版读（不回 `/web` 资源地址） | `SkillVersionService` |
| `SetSkillOfflineUseCase`（新） | usecase | skill 下线 / 恢复服务；仅所有者；审计 | `SkillMetadataService` |
| `Access`（改） | skill | 增加 `offlineAt`（`skill.offline_at` 的读模型事实） | — |
| `ReadSkillUseCase`（改） | usecase | 五个消费面读共用一个 `consumptionAccess(...)`：`accessFor` 之后检查 `offlineAt`，非空 → `SkillOfflineException`（503）；作者面读不经过它 | `SkillMetadataService`、`SkillOfflineException` |
| `SkillOfflineException`（新） | common | 离线守卫抛出的领域异常；**放 `common`**，`usecase` 才能引用（`api` 不能反向被 use case 依赖）；`ApiExceptionHandler`（api）只负责映射 503 与文案 | `ErrorCode` |
| `PublishGatewaySkillUseCase`（改） | usecase | 系统提交带 `draftName("gateway")`；**只有 `SubmittedSkill.state == 'draft'` 且 `version` 为空时**发布才带 `release_name = "0.0." + SubmittedSkill.number`（短、唯一、每次启动幂等）；已发布的旧行即使 `version` 为空也**不带**（保持无名、按 digest 寻址）；`VersionNameTakenException` 的 catch 从提交挪到发布。**`number` 要一路带出来**：`SubmitOutcome` 加它，`SubmittedSkill` 从它复制（今天两者都没有这分量，`VersionRow` 才有）。**这个号会落成对外版本名**（`0.0.<n>` 出现在 `/versions` 与 `skill versions` 里）——`0.0.` 段在网关这一处就是「按提交顺序编号」，号一旦分配永不复用（与 0033 同一承诺）；换编号方案只影响之后的网关发布，不改已发出的名字 | `SubmitSkillUseCase`、`SkillVersionService` |
| `Uploads.metadataOf` / `SkillMetadata`（改） | usecase / skill | 不再从 `SkillUpload.version` 取版本名；版本号只由发布写入 | — |
| `AuthorSkillController`（新） | api | `/api/v1/author/...` 的读与草稿写；入口要求 `browse`+ | 上述 use case、`SkillAddress` |
| `AuthorVersionListResponse` / `AuthorVersionResponse`（新） | api | 作者 API 的响应形状（snake_case） | `VersionSummary` |
| `SkillDiffResponse` / `DiffService`（改） | api / distribution | `next`、`cursor`、numstat 随清单、LRU、ETag | — |

### 版本状态机

这一版把 `VersionState` 从三态扩成四态（`draft` / `published` / `revoked` / `discarded`）。**状态是版本的，指针是 skill 的**；两者不能混。「线上 / 历史版本」不是存储状态，而是 `published` 加指针派生出来的名字。

| 当前 | 动作 | 下一状态 | 谁能做 | 指针 |
|---|---|---|---|---|
| `draft` | 发布 | `published` | `maintain` | 前移 |
| `draft` | 丢弃 | `discarded` | `maintain` ∨（`write` ∧ 提交人是我） | 不动 |
| `published`（非当前） | 下架 | `revoked` | `maintain` | 不动 |
| `revoked` | 重新上架 | `published` | `maintain` | 不动 |
| `discarded` | 任何 | — | — | 终态；只能由作者面读，永不上线 |

**当前版本不能直接下架。** 要下架它，先回滚到另一版，让它变成历史版本；指针不会被下架清空。**回滚不新增一行迁移。** 它就是把一个 `published`（历史）目标重新发布成当前版本：版本号不变，不新建版本；默认目标按 `first_published_at` 倒序取当前版本下面那一个**历史版本**（`published` 且非当前；跳过已下架与已丢弃，与 PRD #21 的候选集合一致）。**skill 级下线不改这张表**——它不改变任何版本的状态，见 §skill 级服务开关。

**四条不变量**

1. **`current_version_id` 只能指向 `published`。** 所以「下架当前版本」不是合法动作——先发布/回滚替代版本、指针前移，旧的变成历史版本，再下架它。`findCurrent` 的 `state = 'published'` 因此保持不变。
2. **指针只由上线 / 回滚移动，不会被下架清空。** 一个有已上线版本的 skill 始终有一个当前版本。只有从未上线过（只有草稿）时 `current_version_id` 才是 NULL；作者面的默认屏幕落回最新一个未被丢弃、未被下架的版本。
3. **版本号永不复用。** `UNIQUE(skill_id, version)` 不变；下架不是删除，行与字节都留着，重新上架后还是同一行、同一个号。
4. **所有已发布的版本永久可读。** 下架是版本级的唯一例外（410）；skill 级下线返回 503、skill 删除后返回 404，是另外两件事。

**消费面读过滤不变**：`state = 'published'`。`revoked` 与 `discarded` 不在消费面。本版不加弃用状态（代码里从未有过 `deprecated`），`VersionRepository` 的三处过滤（`findBySkillAndDigest:144`、`findBySkillAndVersion:170`、版本列表 `:336`）保持原样；`findCurrent:204` 也不改。

**作者面**按档位读：`draft` 要 `write`，`revoked` 与 `discarded` 要 `maintain` 及以上（保留在版本表里，可读内容与原因，用于审计），`published` 按作者面原有的读档位。**下架只关消费面，不关作者面**；但它不是对所有作者面档位都敞开——`browse` / `write` 看不到已下架的行，按设计稿「够不到的东西不画」。

**回退选择跳过 `revoked`，对所有档位。** 没有当前版本时，默认屏幕落回最新一个**未被丢弃、未被下架**的版本。已下架的版本即使对 `maintain` 可见，也不该成为默认——作者刚刚把它下架了；要看它，按钉打开（`maintain`+）。`browse` / `write` 按钉读一个 `revoked` 版本仍然是 404（与看不见同一个答案）。

**计数与列表同源，但两个数说的是两件事。** 版本表上的**状态胶囊**数的是这一档看得见的全部行（含草稿、已下架、已丢弃）；顶栏与文件栏的**「版本 N」**数的是**非草稿、非已丢弃**的版本（线上 + 历史 + 已下架，即 `state IN ('published','revoked')`），**不含草稿与已丢弃**——草稿是「可能会成为的版本」，已丢弃从未上线。两个数都从同一份按档位过滤后的 `versions[]` 现算，不另查一次库；`browse` / `write` 看不到已下架，数里也就不含它。作者 API 的 `counts` 给的是**胶囊那一套**（`all` 含草稿与已丢弃）。

### 派生状态 `status`

读模型在 `state + isCurrent` 之上派生一个五值 `status`，**不落库、不新增列、不迁移**：

| `state` | `isCurrent` | `status` |
|---|---|---|
| `draft` | — | `draft` |
| `published` | `true` | `live` |
| `published` | `false` | `history` |
| `revoked` | `false` | `revoked` |
| `discarded` | — | `discarded` |

- 作者面与消费面都返回 `status`；消费面只列 `live` / `history` 两行，作者面五种都可能有。`state` 是四个存储值，`isCurrent` 保留（向后兼容）。
- `status` 只是读模型的一层翻译：分析按它分组，不必自己 join 指针；存储仍然只有一份事实。
- `status` 不参与写路径、不进审计、不参与版本身份。

**skill 级服务开关（一句话）。**

`skill.offline_at TEXT`：NULL = 在线；非空 = 已下线（RFC3339 UTC）。归 M12 所有，不新增表。消费面 503 `skill_offline`、作者面照常、两条 `/web` 路由、审计与 `V24` 迁移的细节见 §接口 › skill 级服务开关；它不改版本状态，也不动指针。

## 数据模型

**`draft_name` 是版本名称，不是草稿专属。** 提交时必填；发布后它仍是这一版在作者面的名字（单版页标题、版本表每行上方的名称、版本下拉的主信息），不会因为发布而消失或改名。**版本号**是 `skill_version.version`：草稿为 `NULL`，发布那一刻才分配，调用者用它寻址。两者分开存储、分开展示——名称为人认这一版，版本号给调用者用。

**说明复用 `skill_version.changelog`；版本名称新增一列。**

`V1__baseline.sql:387` 已经有这一列：

```sql
changelog    TEXT NOT NULL DEFAULT '',
```

`:410` 的注释写着它是「Free text from the author」，并且「v1 has no endpoint that sets it」。这一版就是那条接口。复用而不是新增 `note` 列的理由：

- **同一个概念不该有两列。** 新增一列会留下一个永远空的 `changelog`，下一个人得先判断该写哪一列。
- **它本来就被留给这件事。** 注释是 v1 写的，不是事后补的。
- **列本身零 DDL。** 类型、默认值、可空性都不用动；迁移只需要改注释与（可选的）长度约束。

**约束**

| 项 | 决定 | 理由 |
|---|---|---|
| 可空性 | 保持 `NOT NULL DEFAULT ''` | 空说明是合法状态；API 把空串映射成 `null`，不把「没有说明」与「说明是空串」分成两件事 |
| 长度 | 200 个 Unicode 码点，超出 400 `note_too_long` | 它是一句话，不是 changelog；200 足够一屏一行，也让 UI 不必截断。**不静默截断** |
| 空白 | 提交时 trim；内部换行与连续空白折成一个空格 | 它是单行；折行会在表格里撑开高度，而「一句话」不需要换行 |
| 字符 | 纯文本；拒绝控制字符（`\u0000`–`\u001F` 除 `\t` 外） | 说明会出现在网页与终端两处；控制字符会破坏其中一处 |
| 摘要 | **不进 digest** | digest 只对清单哈希（[ADR 0005](../../decisions/0005-content-addressing.md)）；`changelog` 是行元数据，不是清单的一部分 |
| 索引 | 不建 | 没有任何按说明查询的路径；搜索也不索引它 |
| 外键 | 无新增 | 列在 `skill_version` 上，归属不变 |

**写入规则（这是本版的一处「漏一处就静默出错」）**

| 时点 | 谁写 | 规则 |
|---|---|---|
| 提交（INSERT） | `SubmitSkillUseCase` / `SubmitSkillVersionUseCase` | 写请求里的 `note`；没有就是 `''`。**同一 digest 的重复提交不覆盖已存在的说明**——幂等提交返回已有版本，说明保持第一次写入的那句 |
| 草稿期修改（前端） | `UpdateDraftNoteUseCase`（新） | 只对 `draft` 行；`write` ∧ 提交人是我，或 `maintain` 及以上；写请求里的 `note` 直接覆盖。已上线 / 已下架 / 已丢弃的行 → 400 `note_is_frozen`；看不见的行 → 404 |
| 发布（`draft → published`） | `PublishSkillVersionUseCase` | 只写 `release_name` 与指针；**说明保持草稿期的最后一句，随发布冻结**，发布请求带 `note` → 400 `invalid_request`（不静默忽略）。**同一事务**，与指针前移一起提交 |
| 回滚（`published → published`） | 无 | 目标已是上线版本：说明冻结。请求若带 `note`，400 `invalid_request`（同发布，不静默忽略） |
| 丢弃 | 无 | 草稿的说明留在行上，随行一起不再可见 |

**迁移**：`V22__draft_name_and_note.sql` 做三件事：新增 `draft_name TEXT`（可空、无默认、无回填），更新 `skill_version.changelog` 的列注释（写明 v6 起它有写入口、上线后冻结），以及如有需要补一条 `CHECK (char_length(changelog) <= 200)`。**不改 `changelog` 的类型与默认值、不回填**——这一列今天全库都是空串。

### 草稿的名字

`draft_name TEXT`：草稿期的**作者面标签**；发布后保留并冻结，作者面与 `@版本` 分开展示。它不是版本号，不进地址、不进 digest、消费面不返回。

| 项 | 决定 | 理由 |
|---|---|---|
| 可空性 | **可空**；新提交必填，`NULL` 只出现在 v6 之前提交的旧行 | 旧行没有名字，不能为了约束伪造一个；新提交由应用层拒绝空值 |
| 长度 | 1–40 个 Unicode 码点；trim 后为空 → 400 `draft_name_required`；超长 → 400 `draft_name_too_long` | 它是短标签，不是第二句说明；40 字足够区分，也不至于在表格里截断 |
| 空白 | 同说明：trim；内部换行与连续空白折成一个空格 | 它出现在版本表的一行里 |
| 字符 | 纯文本；拒绝控制字符（同说明） | 网页与终端两处都会显示 |
| 唯一性 | **不要求唯一** | 名字是标签；`draft:<id>` 才是身份。重名靠说明、提交人、时间区分 |
| 摘要 | **不进 digest** | 改名不能让内容变成另一个版本，也不能让地址变 |
| 发布后 | **冻结**；作者面保留，与版本号分开展示；消费面不返回 | 它是草稿期的作者面标签，不是发布契约 |
| 索引 | 不建 | 没有按名字查询的路径 |

**写入规则**

| 时点 | 谁写 | 规则 |
|---|---|---|
| 提交（INSERT） | `SubmitSkillUseCase` / `SubmitSkillVersionUseCase` | 写请求里的 `name`（必填）。**同一 digest 的重复提交不覆盖已存在的名字**——幂等提交返回已有版本，名字保持第一次写入的那个 |
| 草稿期改名（前端） | `UpdateDraftNameUseCase`（新） | 只对 `draft` 行；`write` ∧ 提交人是我，或 `maintain` 及以上；写请求里的 `name` 直接覆盖。已上线行 → 400 `draft_name_is_frozen`；看不见的行 → 404 |
| 发布 / 回滚 / 丢弃 | 无 | 名字随行留在原地；发布后冻结；丢弃后随行不再可见 |

**旧数据**：v6 之前的行 `draft_name` 为 `NULL`。作者面版本表这一格退回版本号（它才是那一版仅有的身份），不编造名字；新提交必须带名字。

### 版本号的时机

版本号与名称分开，落成一条规则：**提交不写 `version`，发布才写。**

- `skill_version.version` 已经是可空列；草稿行保持 NULL。`UNIQUE(skill_id, version)` 在 PostgreSQL 里允许多个 NULL（默认 `NULLS DISTINCT`），所以**不需要改约束**。
- 提交路径（`SubmitSkillUseCase` / `SubmitSkillVersionUseCase`）不再把 `SKILL.md` 的 `version:` 写进这一列；`SkillUpload` 的 `version` 字段与 `SkillUploadValidator` 的提取一并删掉。那个字段仍然留在 `frontmatter` 里，原样透传。
- 发布路径（`PublishSkillVersionUseCase`）接收 `release_name`：目标是草稿且 `version IS NULL` 时必填；按 SemVer 2.0.0 校验；`UNIQUE(skill_id, version)` 冲突 → 400 `version_already_exists`；写 `version` 与 `first_published_at`，同一事务里与指针前移一起提交。
- **名字分配一次，之后不可改。** 已发布版本再次发布（回滚）时，`release_name` 必须省略；带了且不同 → 400 `version_name_is_assigned`。
- **旧数据过渡**：库里已有的草稿可能带着 `SKILL.md` 写下的 `version`。它们按「已经分配」处理——发布时保留原名，不再要求 `release_name`；本版不回填、不清空。
- **丢弃不烧号**：新草稿行 `version` 为 NULL，丢弃后不占用任何版本号；这正是 [ADR 0033](../../decisions/0033-version-names-are-semver.md) 今天做不到的那件事。
- **`SKILL.md` 的 `version:` 被忽略**：CLI 在提交时若检测到它，打印一句「这个字段不参与发布命名；发布时再定」，不阻塞提交。

### 草稿的身份

草稿的钉是 **`draft:<skill_version.id>`**，不是内容摘要。`digest` 仍是内容摘要，是行上的一个字段；草稿的地址由行的稳定 id 决定。

- 本版内容提交后不再变，所以 `digest` 在草稿期是固定的；未来的内容编辑只更新 `digest` 与内容，草稿的 `draft:<id>` 不变。这是为内容编辑预留的那一格，本版不开放写入口。
- 同一 `digest` 的重复提交仍返回已有版本，不新增行；那一行的 `draft:<id>` 就是它的地址。
- 作者面的版本行要带一个能拼出 `draft:<id>` 的稳定 id（草稿行非空；已上线行不需要）。已上线版本的钉仍是 `@版本` / `@sha256:`。
- 发布时把当时的 `digest` 冻成版本身份；发布之后 `digest` 不再随草稿变化——草稿行已经变成上线行。

### 版本的下架

`skill_version.state` 增加一个值：`revoked`（界面叫「已下架」）。列的类型不变（TEXT），`V10` 的 `state_at` 配对约束仍然成立（非 `draft` 一律非空）。新增两列与三条约束：

```sql
ALTER TABLE skill_version ADD COLUMN revoked_reason TEXT;
ALTER TABLE skill_version ADD COLUMN first_published_at TEXT;

-- 先回填再加约束：V10 之后 published 行的 state_at 就是它离开 draft 的时间。
UPDATE skill_version SET first_published_at = state_at WHERE state = 'published';

ALTER TABLE skill_version ADD CONSTRAINT skill_version_state_known
  CHECK (state IN ('draft','published','discarded','revoked'));
ALTER TABLE skill_version ADD CONSTRAINT skill_version_revoked_reason_paired
  CHECK (revoked_reason IS NULL OR state = 'revoked');
ALTER TABLE skill_version ADD CONSTRAINT skill_version_first_published_paired
  CHECK ((state IN ('published','revoked')) = (first_published_at IS NOT NULL));
```

- **`revoked_reason`**：下架时**选填**（0–200 码点；[ADR 0059](../../decisions/0059-delist-reason-is-optional.md)），重新上架时清空。约束只保证「原因只出现在已下架的行上」，不要求每行都有。作者面显示（有才显示）；**消费面不回显**——错误只说「这一版已下架」，不把原因泄露给调用者。
- **`first_published_at`**：首次上线的时间，`COALESCE` 写入，之后不随回滚、下架、重新上架变。消费面的 `publishedAt` 读它——否则下架一下，调用者看到的「发布于」就变成了下架日期。
- **`state_at`** 记**最后一次**状态迁移的时间（下架、重新上架都会更新它）；作者面的状态行显示「下架于 …」，首次上线时间仍从 `first_published_at` 读。
- **回滚也更新 `state_at`，两行都写**：目标版（历史 → 线上）与原来线上那一版（线上 → 历史）都在同一事务里写 `state_at = now`；`first_published_at` 都不动。`markPublished` 的 `COALESCE` 只服务 `draft → published` 的首次上线；`published → published` 的回滚用一条带 `state = 'published'` 守卫的更新，**影响行数必须是 2**（目标与原线上各一行；只命中 1 行说明并发下状态已变，整条事务失败，不能带着半次回滚成功）。不写这两行，设计件要求的「最近状态变更（回滚之后才出现）」永远不会出现。
- **不新增外键**：两列都不指向别的表。
- **迁移 `V23__version_lifecycle.sql`** 加列、回填 `first_published_at`、加约束；**状态值不回填**——已有行仍是三态之一。

**生命周期动作的写规则**

| 动作 | SQL 意图 | 前置 | 同一事务里还要做什么 |
|---|---|---|---|
| 下架 | `state='revoked', state_at=now, revoked_reason=:reason`（`:reason` 可为 NULL） | 目标 `state='published'`，且**不是** `current_version_id` | 无；**指针不动** |
| 重新上架 | `state='published', revoked_reason=NULL`, `state_at` 更新 | 目标 `state='revoked'` | 无；**不动指针** |

每条写入都带 `WHERE state = <期望的当前状态>`，用受影响行数判断是否真的迁移了；0 行就是并发下状态已变，返回 409 或按用例的错误码处理，不静默成功。

## 接口

### 提交：写名字与说明

`POST /api/v1/skills/{namespace}/{name}/versions`（以及首次提交的 `POST /api/v1/skills`）

- 现在就是 `multipart/form-data`，`file` 一个部分；**新增必填的 `name` 部分**（版本名称）与**可选的 `note` 部分**（说明）。
- **不接收也不分配 `release_name`**；提交响应里的 `version.name`（版本号）对草稿为 `null`；`version` 这个对象本身仍在（它有 digest、`state` 等字段）。`SKILL.md` 的 `version:` 不参与。
- 鉴权不变：`write` 及以上。
- 响应 `SubmitResponse` 增加 `draftName`、`note` 与 `draftId`（草稿行非空，其余行为 `null`；验收 #1 的「同一行、同一 `draftId`」读的就是它）；`note` 与 `draftName` 可为 `null`。
- 错误：
  - 缺少名字或 trim 后为空 → 400 `draft_name_required`（新码）。
  - 名字超长 → 400 `draft_name_too_long`（新码）。
  - 说明超长 → 400 `note_too_long`（新码）。
  - 名字或说明含控制字符 → 400 `invalid_request`。
  - 其余提交错误不变。
- **幂等提交不覆盖**：同一 digest 已有行时返回 200，`draftName` 与 `note` 都保持第一次写入的值。

**为什么用 multipart 的一个部分，而不是 JSON 包装**：这个端点的既有契约就是「一个 zip 一个部分」；让客户端把 zip 塞进 JSON 会改变整个请求形状，且 zip 是二进制。新增一个同级部分是向后兼容的最小改动。

### 草稿说明：人补填或修改

`PATCH /web/skills/{namespace}/{name}/versions/{version}/note`（会话 + CSRF）

- 请求体：`{ "note": "…" }`；`note` 是完整替换，不是追加。
- 鉴权：`write` 且提交人是自己，或 `maintain` 及以上。与「丢弃」同一条规则——它也是对别人草稿的一次写。
- 只对 `draft` 行：已上线、已下架或已丢弃 → 400 `note_is_frozen`；看不见这一版 → 404（与「不存在」同一个答案）。
- 响应：`{ "note": "…" }`；空说明返回 `null`。草稿没有版本号，响应不回显 `version`。
- 路径段用 pin 的字符串形式：已命名版本是 `1.2.3`，新草稿是 `draft:<id>`（必要时 URL 编码）。
- 长度与字符校验与提交接口同一套（200 码点、trim、折空白、拒控制字符）。

### 版本名称：人改名（只在草稿上）

`PATCH /web/skills/{namespace}/{name}/versions/{version}/name`（会话 + CSRF）

- 请求体：`{ "name": "…" }`；`name` 是完整替换，不是追加。
- 鉴权：`write` 且提交人是自己，或 `maintain` 及以上。与改说明、丢弃同一条规则。
- 只对 `draft` 行：已上线、已下架或已丢弃 → 400 `draft_name_is_frozen`；看不见这一版 → 404（与「不存在」同一个答案）。
- 响应：`{ "name": "…" }`；名字必填，不会返回空。
- 路径段用 pin 的字符串形式：`draft:<id>`（必要时 URL 编码）。
- 长度与字符校验与提交接口同一套（1–40 码点、trim、折空白、拒控制字符）。

### 发布：分配版本号并冻结说明

`POST /web/skills/{namespace}/{name}/publish`（会话 + CSRF）

请求体：

```json
{ "version": "<target pin>", "release_name": "1.3.0" }
```

- `version` 是**目标版本**的钉（草稿用 `draft:<id>`；已上线版本用名字或 digest），不是新名字。
- `release_name`：目标是草稿且它还没有版本号时**必填**；合法 SemVer；未占用；写一次。目标已有版本号时**必须省略**；带了且不同 → 400 `version_name_is_assigned`。
- **发布请求不接收 `note`**：说明只在草稿的概览里改；带了 `note` → 400 `invalid_request`，不静默忽略。目标已经是 `published`（回滚/重复上线）时同理。
- **版本名称不参与发布**：它已经在行上，冻结后随行保留；发布接口不接收也不修改它。
- 鉴权不变：`maintain` 及以上。
- 响应 `VersionActionResponse` 增加 `release_name`（分配后的版本号）与 `draft_name`（这一版草稿期的名字，发布后仍可读）。
- 错误：`invalid_release_name`（形状不对）、`release_name_required`（草稿没有名字且没给）、`version_already_exists`（同号不同内容）、`version_name_is_assigned`（想给已命名的版本改名）。

### 生命周期：下架与重新上架

两条浏览器面路由（会话 + CSRF），都按版本钉指目标：

| 方法 | 路径 | 谁能做 | 行为 |
|---|---|---|---|
| `POST` | `/web/skills/{ns}/{name}/versions/{version}/revoke` | `maintain` | body `{reason?}`（可选，0–200 码点）；`published`（**非当前**）`→ revoked`；目标是当前版本 → 400 `invalid_version_state`；指针不动 |
| `POST` | `…/restore` | `maintain` | `revoked → published`；不动指针 |

- 目标版本用 `@版本` / `@sha256:` 的同一套钉语法，不是行 id。
- 404 与「看不见这一版」同一个答案；看得见但档位不够 → 403（与梯子的其余部分一致）。
- 下架的 reason 写了就进审计，作者面能读到，消费面不回显；不写也能下架。
- 两条路由都不在 `/api/v1` 平面：它们改变调用者能读到什么，按 [ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md) §理由的规矩，归浏览器面。
- 审计动作名用 `version_revoke` / `version_restore`，`target_type='skill'`，detail 带版本钉与下架原因（有就带）——`restore` 已经是 skill 恢复的动作名，不加前缀会撞。

### skill 级服务开关

`skill.offline_at TEXT`：NULL = 在线；非空 = 已下线（RFC3339 UTC）。归 M12 所有，不新增表。

- **消费面**：解析 skill 时先判断可见性；可见且 `offline_at IS NOT NULL` → **503 `skill_offline`**；不可见 → 404（不泄露存在性）。**落点是 `Access.offlineAt` + `ReadSkillUseCase` 的一个私有守卫**（五个消费面读共用），不是五个入口各写一遍；作者面直接用 `accessFor`，不经过它。所有消费面读（metadata / 正文 / 文件 / 版本列表 / 单版）都一样，包括按 `@版本` / `@sha256:` 钉住的读。
- **作者面**：不按 `offline_at` 过滤；`AuthoredSkillResponse` 带 `offline_at`（或 `offline` 布尔），页头与「我的 skill」显示「已下线」。
- **搜索 / 公开列表**：查询加 `offline_at IS NULL`；M12 的投影同步这个条件。
- **路由**：`POST /web/skills/{ns}/{name}/offline` 与 `/online`（会话 + CSRF，**仅所有者**）；下线/恢复各写一条审计（`skill_offline` / `skill_online`）。重复调用幂等，不重复写审计。
- **恢复服务不动指针**：当前版本继续服务；若当前版本为 NULL，恢复后仍然没有线上版本（裸地址 404），作者要再上线一版。
- **错误码**：`ErrorCode` 加 `skill_offline`（503）；消费面文案「这个 skill 已下线，暂时不提供服务。」
- **迁移**：`V24__skill_offline.sql` 加列（可空、无回填、无外键）。

### 消费面：读到说明与状态

`GET /api/v1/skills/{namespace}/{name}/versions`（`invoke` 及以上）

`SkillVersionsResponse.Version` 增加三个字段：

```json
{
  "name": "1.2.3",
  "digest": "sha256:…",
  "published_at": "2026-10-18T08:23:32Z",
  "is_current": false,
  "note": "把 split 的范围语法写成文档",
  "state": "published",
  "status": "history"
}
```

- 列表含 `published`；不含 `draft` / `discarded` / `revoked`。历史版本按 `isCurrent=false` 列出——调用者钉住的那一版必须留在列表里。
- `state` 只有一个值：`published`。
- `status` 是派生的五值状态（§派生状态 `status`）；消费面只会出现 `live` / `history`。
- **没有 `replacement` 字段，也不返回 `Deprecation` 头**：弃用不是本版的能力（[ADR 0058](../../decisions/0058-a-version-is-readable-or-not.md)）。
- `note` 可空；空说明返回 `null`。
- 旧字段与顺序不变；新增字段在末尾，旧客户端忽略即可。

**下架的响应**：消费面读一个 `revoked` 版本 → **410 `version_revoked`**。这条答案不能靠今天那两条 `liveOnly = true` 的查询——它们把 `revoked` 与「不存在」一起返回空，服务层就分不出 410 与 404。实现要有一条**先不看状态**的查找（同一个 skill 内按版本号 / digest 找行），读到行之后：`published` 正常返回，`revoked` → 410，`draft` / `discarded` → 404（与不存在同答案）。这是对「解析不出就一律 404」的一次有意例外：调用者手里有钉，410 说的是「有过、被下架了」，404 会把它混成「地址打错了」。枚举风险可接受——能走到这一版的调用者本来就有这个 skill 的读权，而版本号不是敏感事实。

### 作者面：版本表读它

`GET /web/skills/{namespace}/{name}`（按档位）

**消费面 `/metadata` 的 `publishedAt` 也读 `firstPublishedAt`。** 它今天从 `SkillSnapshot.stateAt` 取时间（`SkillDistributionService` 组装 `SkillDetailResponse`），而 `state_at` 在重新上架后会变——不改的话，一个重新上架的版本会在 `/metadata` 里报「刚发布」。`SkillSnapshot` 增加 `firstPublishedAt`，`SkillDetailResponse` 与版本列表读同一个字段。

`AuthoredSkillResponse.Version` 增加 `draftName`（可空；值为 `NULL` 只出现在 v6 之前的旧行）、`note`（可空）、`revokedReason`（可空，只有 `revoked` 行有值；Java 字段用驼峰，与同 DTO 的 `submittedBy` / `stateAt` 一致）、`draftId`（草稿行非空，稳定 id；网页用它拼 `draft:<id>`；已上线行为 `null`）与 `status`（派生五值，见 §派生状态 `status`）。`name`（版本号）对草稿为 `null`。网页版本表每行上方是**版本名称**（这一行的入口）、**版本号**（草稿为 `—`）与状态；下方左边是说明（从 `title` 改为读 `note`）、右边是谁 · 时间；没有列头。`title` 仍然是内容字段，留在响应里，但不再占那一栏。名称为空（v6 之前的旧行）时标题与版本表那一格都退回版本号，**不编造名字**。`revoked` 行只出现在 `maintain` 及以上的响应里；`browse` / `write` 的 `versions[]` 不含它。

### 改动：清单先行、内容按批（2026-10-20 定）

改动页不再一次取完整份差异。**档位按每一侧自己的可见性解析，请求取两侧里最高的那一档**：已发布 `browse`、草稿 `write`、已下架与已丢弃 `maintain`。所以两个已发布版本之间 `browse` 就能比（本版把 v5 的 `write` 门槛降到 `browse`，与六档视图一致），涉及草稿要 `write`，涉及已下架要 `maintain`；`write` 拿一个已下架的钉当一侧 → 404（不泄露状态）。现有路由是 `GET /web/skills/{ns}/{name}/diff?from=<pin>&to=<pin>`，本版再加 `file=<relpath>`（只取一个文件的 hunks）（比较的目标版本跟着 `name` 的后缀 `@<pin>` 走，或者由 `to=` 给；两个参数都要解析出 pin，见 `WebSkillController.diff`）。**今天的响应里没有续取**——`SkillDiffResponse` 只有 `from` / `to` / `truncated` / `files`，`truncated` 只告诉你被截断了，没法接着取。本版给它加 `next` / `cursor` 与 `file=`：在比较超过预算时只返回**文件清单**：`from` / `to` / `truncated` / `next` / `files[]`，每个文件带 `relpath`、`status`、`binary`、`size`（已知就有；二进制也有大小）与 **`added` / `removed`（numstat，2026-10-20 改；今天的 `File` 在带 `hunks` 时也有这两个值，不带 hunks 时为 `null`）**，只有 `hunks` 是 `null`——**行数不按需，正文按需**。numstat 比 hunk 便宜（不切上下文、不生成行），而「这一版改了多少行」正是人打开改动页先问的那一句；二进制文件没有可数的行，两个值都是 `null`（客户端画 `—`，不画 `+0 −0`）。带 `cursor=<next>` 再请求下一批内容（每批最多 20 个文件 / 5,000 行），`next` 为 `null` 时结束。**`file=<relpath>` 只取那一个文件的 `hunks`**（不占批、不返回别的文件；路径不在这次比较里 → 404）——单文件模式下切到第 124 个文件不能先取前 19 批。`cursor` 与 `file` 不同时给：`file` 赢，别的文件回 `hunks: null`。小比较仍然一次返回全部内容，形状不变。

**numstat 那一趟不受 `max-files` 限制。** `max-files` 管的是「这次返回几个文件的 `hunks`」，不是「清单多长」：所有非二进制文件的 `added` / `removed` 都在文件清单那一趟算出来（今天的实现把超过上限的文件直接写成 `null`，那是要改的地方——否则第 21 个文件起就没有 `+n −m`，与验收 #25 冲突）。二进制与超过单文件上限的 `added` / `removed` 留 `null`（`size` 仍在），页面才能写「这个文件太大，没有展开（1.4 MB）」。

单文件的上限从 1 MiB 降到 **256 KB**，单文件的 hunk 行数超过 **5,000** 时不进 diff（GitLab 的同一条线）；响应级的 `max-lines` 从 20,000 降到 **5,000**、`max-files` 从 100 降到 **20**（每批）。这些值仍是 `DiffLimits` 的配置项，不是安全边界。服务端以 `(from digest, to digest)` 为键做有界 LRU（版本不可变，重复打开同一对版本不重算），响应带 `ETag`；同一对版本的第二次请求可以 304。

文件清单的 `added` / `removed` **随清单一起到**：页签右边的总行数、文件树与每个文件头的 `+/−` 一进来就有；只有二进制与超限文件没有可数的行（`null`），客户端画 `—`。浏览器侧：文件超过 100 个时默认单文件模式（当前文件写 `file=`），否则文件头先画、正文在进入视野或点开时取；单文件首屏 400 行，之后每次 500；工具栏常驻「只看文件列表」（`list=1`）。验收见 [prd #25](../v6-version-management/prd.md)。

### 版本号：在发布时分配

版本号不再是 `SKILL.md` 的声明，而是发布动作的一部分。服务端仍只校验形状（SemVer 2.0.0）、不判断含义——[ADR 0033](../../decisions/0033-version-names-are-semver.md) 的这条原则不变；变的是**谁在什么时候声明**：从作者在文件里声明，变成发布人在版本 tab 的单版页上选择。这需要一条新 ADR 修订 0033（见 [`README.md`](README.md) §本版文档）。

帮助发生在网页的版本 tab 的单版页：

- 以**当前线上版本**为基准，问一句「这次改动会让旧的用法失效吗？」，按答案算出建议号（MAJOR / MINOR / PATCH），发布人可以改。
- 没有线上版本、也没有任何已上线版本时，建议 `1.0.0`；发布人仍可改成 `0.x`（仍是合法版本号）。
- **本版不在界面上标「试用」（2026-10-20 定）**：`0.y.z` 在 SemVer 里的含义不变，但界面不加章、API 不加字段；调用者读到的就是版本号本身（[prd](../v6-version-management/prd.md) §不做 #15）。
- CLI 不选号、不改 `SKILL.md`；不新增 `--bump`。提交时检测到 `version:` 只提示一句，不阻塞。

### 作者 API 与 CLI 的版本命令（2026-10-20 定）

**边界先于命令。** 一个动作**改不改变调用者读到什么**，决定它有没有 API 路由（[ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md)）：

| 动作 | 改变调用者？ | API 平面 | 网页 |
|---|---|---|---|
| 读作者面的版本、单版、改动、草稿文件 | 否 | 有（`/api/v1/author/…`） | 有 |
| 提交草稿（已有） | 否 | 有 | —（提交只从 CLI / API） |
| 草稿改名 / 改说明 | 否（发布前） | 有 | 有 |
| 丢弃草稿 | 否 | 有 | 有 |
| 上线（含回滚） | **是** | **没有路由** | 有（`POST /web/…/publish`） |
| 下架 / 重新上架 | **是** | **没有路由** | 有 |
| 整 skill 下线 / 恢复、删除 | **是** | **没有路由** | 有 |

**作者 API 的路由**（Bearer；`/api/v1/**` 已有的链与 scope 闸：GET 要 `skills:read`，写要 `skills:write`；档位在用例里判。**列表与单版这两条在入口显式要求 `browse`+，不复用网页那条对 `invoke` 也给投影的路径**——CLI 的 `version` 组是作者面的命令，调用者用 `skill versions`）：

| 方法 | 路径 | 档位 | 说明 |
|---|---|---|---|
| `GET` | `/api/v1/author/skills/{ns}/{name}/versions?status=&q=&limit=&cursor=` | `browse`+ | 作者面的版本列表：`{skill, versions[], counts, next}`；`status` 是派生的五值（`live\|draft\|history\|revoked\|discarded`），不传就是这一档看得见的全部；`counts` 是**未过滤**的可见集合（与网页过滤栏的胶囊同一条口径：`all` 含草稿、已下架、已丢弃；不是顶栏「版本 N」）；草稿只对 `write`+ 出现，已下架与已丢弃只对 `maintain`+ |
| `GET` | `/api/v1/author/skills/{ns}/{name}/versions/{pin}` | 已发布 `browse`+；草稿 `write`+；已下架与已丢弃 `maintain`+ | 一版的事实（API 自己的作者面版本记录，不复用 `AuthoredSkillResponse` 的 `/web` 资源地址）；看不见 → 404；`invoke` → 403 |
| `GET` | `/api/v1/author/skills/{ns}/{name}/versions/{pin}/diff?from=&cursor=&file=` | 按两侧可见性取最高：已发布 `browse` / 草稿 `write` / 已下架与已丢弃 `maintain` | 与网页改动同一份响应（清单先行、numstat 随清单、`hunks` 按批） |
| `PATCH` | `/api/v1/author/skills/{ns}/{name}/versions/{pin}/name` | `write` ∧ 提交人是我，或 `maintain`+ | 只对 `draft`；`{ "name": "…" }` 完整替换；非草稿 → 400 `draft_name_is_frozen`；看得见但不是提交人且非 `maintain`+ → 403 |
| `PATCH` | `/api/v1/author/skills/{ns}/{name}/versions/{pin}/note` | 同上 | 只对 `draft`；`{ "note": "…" }` 完整替换；非草稿 → 400 `note_is_frozen`；403 同上 |
| `POST` | `/api/v1/author/skills/{ns}/{name}/versions/discard` | 同上 | 请求体 `{ "version": "<pin>" }`（写动作的版本钉在 body 里，照 `VersionChoice` 的既有约定）；只对 `draft`；已丢弃 → 200 幂等（返回现状）；已上线 / 已下架 → 400 `invalid_version_state`；403 同上 |
| `GET` | `/api/v1/author/skills/{ns}/{name}/versions/{pin}/files` | 按版本可见性（同单版） | 文件清单 |
| `GET` | `/api/v1/author/skills/{ns}/{name}/versions/{pin}/files/{*relpath}` | 同上 | 一个文件的字节 |
| `POST` | `/api/v1/author/skills` | 无（只落个人命名空间） | **v10 加**：建 skill 与第一份草稿；请求体 `{name, description, version_name}`。见 [v10 TD](../v10-cli-lifecycle/technical-design.md) §接口 |
| `GET` | `/api/v1/author/skills/{ns}/{name}/draft-base` | `write`+ | **v10 加**：被解析出的底稿**引用**（不返回内容）；响应形状见 [v10 TD](../v10-cli-lifecycle/technical-design.md) §接口 |
| `POST` | `/api/v1/author/skills/{ns}/{name}/versions` | `write`+ | **v10 加**：建草稿并应用第一次改动（write / delete / rename）。见 [v10 TD](../v10-cli-lifecycle/technical-design.md) §接口 |

**PATCH 的路径钉是有意例外。** `VersionChoice` 那条「写动作的版本钉放 body，路径钉会让写看起来像读」针对的是 **POST 动作**（publish / discard）；PATCH 的方法本身已经说明是写，钉放在被改的那个子资源上（`/versions/{pin}/name`）读起来更直接。两处按这个方法分开，不混用。

**页面专属的动作连路由都没有**：`/api/v1/author/…/publish|revoke|restore|rollback`，以及 skill 级的 `offline|online|delete`，一律 404（带一把能进 API 平面的凭据时才是 404；没凭据先是 401）。这不是漏写，是[ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md) 与 [0046](../../decisions/0046-deleting-a-skill-happens-in-the-browser.md) 的形状——新加一条作者 API 不改变「由人在网页上确认」的那半边。

**CLI 的命令面**（`skillmaster version <动词>`；提交仍是 `skill submit`）：

| 命令 | 参数 | 作用 | 最小档位 |
|---|---|---|---|
| `version list <ns>/<name>` | `--status <live\|draft\|history\|revoked\|discarded>`（可重复）、`--query <文字>`（名称 / 版本号 / 说明 / 提交人，按档位）、`--limit <n>`（默认 50，最多 200）、`--cursor <token>`、`--json` | 作者面的版本列表（含计数与下一页游标）；`--status` 过滤的是派生的 `status`，不是存储的 `state`；`counts` 不随过滤变 | `browse` |
| `version show <ns>/<name>@<pin>` | `--json` | 一版的事实：名称、版本号 / `draft:<id>`、状态、提交人 / 时间、首次上线、最近状态变更、说明、文件数与大小、摘要、地址；已下架版另带原因 | 已发布 `browse`+；草稿 `write`+；已下架与已丢弃 `maintain`+ |
| `version diff <ns>/<name>@<pin>` | `--from <pin>`（默认上一版；**这是 v6 的新默认**，不是今天网页的行为）、`--patch`、`--file <path>`、`--max-lines <n>`（默认 2000）、`--json` | 默认与 `git diff --stat` 同形（`+n −m` 与文件清单）；`--patch` 给统一 diff，`--file` 只看一个文件；`--patch` 用 `file=<relpath>` 一次取所选文件的 `hunks`（不跟 `next` 走整批）；`--max-lines` 是客户端上限，截断时打印还有多少行 | 按两侧可见性取最高：已发布 `browse` / 草稿 `write` / 已下架与已丢弃 `maintain` |
| `version rename <ns>/<name>@draft:<id>` | `--name <名称>`、`--json` | 改草稿名称（1–40 码点，trim 后非空） | `write` ∧ 提交人是我，或 `maintain`+ |
| `version note <ns>/<name>@draft:<id>` | `--note <文字>` / `--note-file <文件>` / `--clear`（三选一，`-` 读 stdin）、`--json` | 改草稿说明（≤200 码点，完整替换） | 同上 |
| `version discard <ns>/<name>@draft:<id>` | `--yes`、`--json` | 丢弃草稿；没有 `--yes` 时只打印后果与完整命令、**不执行**、退出码 1 | 同上 |
| `version open <ns>/<name>@<pin>` | `--print`、`--json` | 不调服务端：打印那一版的网页地址（**`/skills/{ns}/{name}?tab=versions&version=<pin>`**，与单版页同一条路由）并尝试打开浏览器（失败只是警告）；上线 / 回滚 / 下架 / 重新上架在那里由人确认 | 无（只发地址） |

**`skill versions` 与 `version list` 是两个问题，不合并。** 前者的读者是调用者（消费面：我能 invoke 哪些，只有已上线）；后者的读者是作者（作者面：这个 skill 有哪些版本，含草稿、已下架、已丢弃，按档位过滤）。同一个 skill 两个命令给出不同行数是正常的——它们读的不是同一份契约。

**写命令必须带钉，`@draft:<id>` 是可写的目标。** 草稿的稳定身份是 id，不是摘要；草稿的摘要钉在本地被拒、请改用 `draft:<id>`。**已发布 / 已下架的钉照常发给服务端**，由它回答冻结（改名 / 改说明：`draft_name_is_frozen` / `note_is_frozen`；丢弃：`invalid_version_state`）或 404——CLI 不在本地替服务端判状态，否则同一条命令在不同档位下会得到不同的理由。**不默认「当前那一版」**：默认值会让一次手滑改到另一版。`version publish|rollback|revoke|restore|offline|online|delete` 不在帮助里，按下去只得到一句拒绝：说明这个动作由人在网页上确认，并给出 `version open` 的完整命令。

**`skill files` / `skill read` 按钉所在的面路由**：`@1.2.3` / `@sha256:…` 走消费面（已有）；`@draft:<id>` 与（`maintain`+ 的）已下架钉走作者面。`skill invoke` 不接受 `@draft:<id>`——草稿不是可以 invoke 的版本，消息指向 `version show` / `version diff`。

**`--json` 是机器契约**：每个命令（含 `version open`）输出一个 JSON 对象，字段 snake_case，键稳定；错误在 `--json` 下也输出对象（stderr）并以非零退出。版本对象：`address` / `pin` / `name` / `version`（草稿为 `null`）/ `status`（五值）/ `note` / `submitted_by` / `submitted_at` / `first_published_at` / `state_at` / `revoked_reason` / `digest` / `files` / `bytes`。列表另带 `skill` / `counts` / `next`；改动带 `from` / `to` / `added` / `removed`（CLI 由逐文件 numstat 求和）/ `files[]`（`path` 由服务端的 `relpath` 映射 / `status` / `binary` / `added` / `removed`）/ `truncated` / `next` / `patch`；写命令带 `ok` 与 `version`；`open` 带 `url` 与 `opened`。错误对象：`{"ok":false,"error":{"code":"…","message":"…","hint":"…","server_code":"…"}}`（`server_code` 可选），`code` 取服务端的错误码（`draft_name_is_frozen`、`note_is_frozen`、`draft_name_required`、`draft_name_too_long`、`note_too_long`、`invalid_version_state`、`version_revoked`、`skill_offline`）或 CLI 自己的 `insufficient_permission` / `not_found` / `usage` / `network`。**服务端对档位不足仍回 `forbidden`（`insufficient_scope` 只针对 scope）；CLI 把这两个都翻译成自己的 `insufficient_permission`**，并保留服务端原码在 `error.server_code` 里。

**作者 API 的响应形状**（snake_case；`/web` 也是 snake_case——`application.yml` 全局就是 `SNAKE_CASE`。两边不同的是**身份来源与 DTO 形状**，不是大小写）：

```json
// GET …/versions
{
  "skill": { "namespace": "dev", "name": "pdf-tools", "address": "dev/pdf-tools" },
  "versions": [
    {
      "address": "dev/pdf-tools",
      "pin": "draft:01J…",
      "name": "compress 补文档",
      "version": null,
      "status": "draft",
      "note": "把 compress 的用法补进正文",
      "submitted_by": "yuhan",
      "submitted_at": "2026-10-16T10:12:00Z",
      "first_published_at": null,
      "state_at": null,
      "revoked_reason": null,
      "digest": "sha256:…",
      "files": 6,
      "bytes": 18342
    }
  ],
  "counts": { "all": 6, "live": 1, "draft": 1, "history": 2, "revoked": 1, "discarded": 1 },
  "next": null
}
```

- `counts` 是**未过滤**的可见集合（同网页的口径），`status` / `q` 只过滤 `versions`；`all` 是这一档看得见的全部。
- `q` 是对名称、版本号、说明、提交人的**大小写不敏感子串**匹配；空串等于不筛。
- `cursor` 是**不透明**的（服务端可以把 `number` / `id` 编进去，客户端不解析）；`next` 为 `null` 即最后一页；`limit` 默认 50、上限 200。
- 单版：`{ "skill": {…}, "version": {…} }`。写命令（改名 / 改说明 / 丢弃）：`{ "ok": true, "version": {…} }`，`version` 是写后那一版——CLI 的 `--json` 直接复用这一份形状。
- **不返回** `draft_id` 这个单独的键：`pin` 已经是 `draft:<id>`，再给一个 id 会让客户端猜哪个才是身份。

**同意屏跟着改**：`skills:write` 那一行从「以你的身份提交新版本、管理共享」改为「以你的身份提交新版本、管理草稿与共享」；清单仍然不出现「发布」「删除」——凭据确实做不到。`design/partials/auth-overlay.html` 是权威（`voice-spec.md` 的 §三 名词表登记「管理草稿」= 改名、改说明、丢弃，不含内容编辑），**`CliSignInPage.vue`（第一方）照它搬**；`ConsentPage.vue` 是第三方那条路，本版不动（它与设计件的分叉见 `design/README.md` §授权请求）。

## 前端架构与复用

**一条路由，三种栏位；一份东西只画一次。** 版本 tab 的清单与单版页是同一条路由的两种状态，不是两个页面——地址钉住一版时清单收起，`version=<pin>` 是唯一的开关（ADR 0054）。

**产品沿用现有的地址习惯：路径管 skill 与版本钉，查询管栏位。** 网页**没有** `/skills/:ns/:name/versions` 这条路径——`SkillPage.vue` 是唯一的 skill 页宿主，四个栏位是 `?tab=`；只有 `/diff` 与 `/files/...` 是路径段（`App.vue` 的 `skillRouteOf`）。设计稿的 `?page=skill&tab=versions` 是演示件的假路由，不是产品地址。

| 地址 | 状态 | 谁拥有 |
|---|---|---|
| `/skills/:ns/:name?tab=versions` | 版本清单 | `SkillPage.vue`（版本栏） |
| `…?tab=versions&version=<pin>` | 单版页 · 概览（缺省） | `VersionDetail.vue` |
| `…?tab=versions&version=<pin>&sub=diff` | 单版页 · 改动 | 同上，`VersionDiffPane.vue` |
| `…?tab=versions&version=<pin>&sub=actions` | 单版页 · 操作 | 同上，`VersionActionPanel.vue` |
| `…&sub=diff&from=<pin>` | 选定的对比基线 | `VersionDiffPane.vue` |
| `…&sub=diff&fq=<文字>` | 改动栏的文件筛选 | 同上 |
| `…&sub=diff&file=<path>` | 单文件模式 | 同上 |
| `…&sub=diff&list=1` | 只看文件列表 | 同上 |
| `…?tab=versions&filter=<state>&q=<文字>` | 清单的过滤与搜索 | `SkillPage.vue`（版本栏） |
| `/skills/:ns/:name@<pin>?tab=files` | 这一版的文件栏（单版页页头的「查看文件」） | `SkillPage.vue`（文件栏） |

**地址解析按页归口，不是一个函数包打天下。** `version.ts` 增加 `parseVersionRoute(search)`，只解析**版本栏**的 `version` / `sub` / `from` / `fq` / `file` / `list` / `filter` / `q`；设置栏的 `sub`（`general` / `collaborators` / `details`）仍由 `SkillPage.vue` 自己解析——两者同名不同义，合在一个函数里就会互相把值当坏输入。`App.vue` 继续负责路径（`@pin`、`/diff`、`/files/...`）。`isVersionSuffix` 与 `versionSuffix` **都要**学会 `draft:<id>`：前者决定 `/name@draft:<id>` 是否被当作钉，后者决定选择器里这一版叫什么。

### 组件与复用

| 组件 | 状态 | 职责 | 用在哪 |
|---|---|---|---|
| `components/MenuSelect.vue` | 新（从 `RungMenu.vue` 抽出机制） | 页面自己的下拉：固定定位、点外面 / 滚出视口关闭、焦点与 Esc、选项常驻 DOM；可选分组、搜索框、富标签、禁用项 | `RungMenu`、`VersionPicker` |
| `components/RungMenu.vue` | 改（变成薄壳） | 档位选择：把选项映射给 `MenuSelect` | 协作者与申请档位 |
| `components/VersionPicker.vue` | **改内部，不改契约；接进 `SkillPage`** | 版本选择：名称 + 版本号 + 状态 tag、搜索、按档位给选项、钉住但读不到的版本作禁用项 | 文件栏（`SkillPage`，替换它现在那份 `<select>`）、改动栏的对比基线、回滚目标 |
| `components/DiffFileBlock.vue` | 新（取代 `DiffView.vue`） | 一个文件的头（路径 + `+n −m`）与正文（hunk 行、行号、三种空状态）；v6 起是**唯一**的行渲染器，小比较也由它渲染 | `VersionDiffPane`、`SkillPage` 现有的差异块 |
| `components/version/VersionDetail.vue` | 新 | 单版页的壳：版本头 + 三栏切换 | 版本栏（`version=` 时） |
| `components/version/VersionDiffPane.vue` | 新 | 改动栏：工具栏、文件树、按需加载、单文件模式、`list=1`、`fq`、页签计数与总行数 | 单版页 |
| `components/version/VersionFileTree.vue` | 新 | 左栏：路径树、每个文件的 `+n −m`、当前项 | `VersionDiffPane` |
| `components/version/VersionHeader.vue` | 新 | 面包屑、标题（名称 / 调用者 `@版本号`）、版本号标识、状态章、元信息行、「查看文件」 | 单版页 |
| `components/version/VersionStatusBar.vue` | 新 | 状态条：每种状态一句后果；已下架的原因单独一行；非维护档的上线说明 | 单版页 |
| `components/version/VersionFacts.vue` | 新 | 右栏事实（作者面 / 调用者两套字段） | 概览栏 |
| `components/version/VersionActionPanel.vue` | 新 | 操作栏：发布区块、回滚、下架 / 重新上架、丢弃、危险区 | 操作栏 |
| `components/version/VersionRow.vue` | 新 | 清单的一行：两段式、整行链接、状态章、更多操作菜单 | 版本清单 |
| `components/version/VersionFilterBar.vue` | 新 | 状态胶囊（带计数）+ 搜索 + 「新建版本」占位 | 版本清单 |
| `composables/useVersionDiff.ts` | 新 | 清单先到、按 `file=` 取单个文件的正文、`next` 续取、`fq` / `file` / `list` 的视图状态；请求可取消 | 改动栏 |
| `composables/useVersionActions.ts` | 新 | 六个动作的确认弹窗、等待 / 成功 / 失败状态、成功后刷新 | 操作栏 |
| `api/skills.ts` / `types.ts` | 改 | `/web` 版本写与读；`AuthoredVersion` 加 `draft_name` / `draft_id` / `note` / `revoked_reason` / `status`（`name` 仍是版本号，可空）；`Skill` 加 `offline_at` | 页面 |

**先删死代码，再接新线。** `pages/SkillDiffPage.vue`、`pages/SkillFilePage.vue`、`components/SkillHeading.vue` 今天没有任何路由或组件引用（`App.vue` 只路由到 `SkillPage`）——P1 第一步删掉它们，否则「单版页放在哪」会有两份答案。它们里面唯一还有价值的是 `DiffView` 的渲染，先拆成 `DiffFileBlock` 再删外壳；`DiffView` 本身随即删除（小比较也走 `DiffFileBlock`）。

**每个面只写一种钉。** 文件栏的钉在路径里（`/name@<pin>?tab=files`），**不再带 `version=`**；版本栏的钉在查询里（`?tab=versions&version=<pin>`）。`/skills/:ns/:name@<pin>/diff` 保留为旧链接的**兼容别名**：`App.vue` 仍解析它的 `from` / `to` 并打开版本栏的改动视图；新页面只生成版本栏的 `sub=diff` 地址，不再生成 `/diff`。

**`VersionPicker` 换的是内部，不是契约；它今天没被接上。** 文件栏现在用的是 `SkillPage.vue` 里自己写的一份 `<select>` + 分组逻辑，`VersionPicker` 只有死掉的 `SkillHeading` 在引用——这是「三处选择器」的真实状态。先把它接进 `SkillPage`（替换那份 `<select>`），再换内部：原生 `<select>` 换 `MenuSelect`（设计要求名称 + 版本号 + 状态 tag 与按名称 / 版本号搜索，ADR 0057）。**`props` 仍是 `versions` / `selected`，事件仍是 `select`**，三个调用点只换数据。文件栏的选项**不列已丢弃**（它没有文件快照，`design/README.md` §文件栏）；只有地址钉住而这一档读不到的版本作为禁用项。

**`DiffView` 拆而不是重写，拆完即删。** 它今天把每个文件画全，且只被死掉的 `SkillDiffPage` 引用；把「一个文件的块」拆成 `DiffFileBlock`，`SkillPage` 现有的差异块（直接画 `<pre>` 行）与新的改动栏都改用它，`DiffView` 随后删除——这是「hunk 行只有一份画法」的落点。

**`/api/v1/author/…` 只服务 CLI，网页不调它。** 网页是会话 + CSRF 的 `/web` 平面，作者 API 是 Bearer 的 `/api/v1` 平面；两边共用 `VersionSummary` 与 `SkillVersionService`，但**请求形状、错误码与身份来源都不同**。网页为了省一条函数去调作者 API，会把两套鉴权混在一页里。

**前端从设计件搬，不从文档重画。** 结构、类名、CSS 数值、文案照 `design/demos/dir3-hara-console.html` 与 `design/README.md`；只允许改「挪进框架」那一层（组件、props、真接口、路由）。演示件的替身机制（`?page=` 假路由、`VERSIONS` 夹具、进度条）不搬。设计件没覆盖的态（窄屏、加载、失败）不自己发明——见 CLAUDE.md 的那条规矩。

**组件测试**：`VersionPicker`（选项按档位、搜索、禁用项、Esc / 焦点）、`DiffFileBlock`（hunk 三种空状态、二进制、超限与它的 `size`）、`VersionDiffPane`（`list=1`、`file=`、`fq` 只筛树）、`VersionActionPanel`（档位与状态给动作、确认弹窗、发布区块的当前线上与建议号）、`VersionRow`（整行链接、菜单项）、`VersionStatusBar`（四种状态文案与已下架原因）。页面级测试只验路由解析与切换，不重复组件已经钉住的东西。

### 前端实现顺序

1. 删 `SkillDiffPage.vue` / `SkillFilePage.vue` / `SkillHeading.vue`（先确认无引用）。
2. `types.ts` 的字段与 `version.ts` 的 `parseVersionRoute` / `draft:<id>`。
3. `MenuSelect` 从 `RungMenu` 抽出，`RungMenu` 改成薄壳；`VersionPicker` 接进 `SkillPage` 的文件栏 + 组件测试。
4. `DiffFileBlock` 从 `DiffView` 拆出（拆完删掉 `DiffView`）；`SkillPage` 现有的差异块改用它；跑现有测试。
5. `VersionRow` / `VersionFilterBar`：版本清单（两段式行、整行链接、菜单、过滤 / 搜索）。
6. `VersionDetail` 与五个子组件（头、状态条、事实、改动、操作）；`VersionDiffPane` 的 `file=` / `list=` / `fq`。
7. 路由与 `sub` 接线；动作调用；组件测试与设计件逐屏对照。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P0 服务端** | `V22` 版本名称与说明迁移 + `V23` 生命周期迁移 + `V24` skill 下线迁移；**gateway 的系统提交带名字、发布带由 digest 推出的 `release_name`（启动不再被必填字段挡住）**；提交接口接受必填 `name` 与可选 `note`，且**不再写 `version`**；草稿改名与改说明写接口；发布接口接受 `release_name` 并分配；发布接口不接收 `note`（说明随发布原样冻结）；两条生命周期路由；**skill 下线/恢复两条路由**；消费面读过滤与版本列表；**已下线 503 `skill_offline`**；`410 version_revoked`；**下架拒绝当前版本**；**搜索与公开列表过滤已下线**；审计；两个响应加字段；`draft_name_required` / `draft_name_too_long` / `draft_name_is_frozen` / `note_too_long` / `note_is_frozen` / `invalid_version_state` / `invalid_release_name` / `release_name_required` / `version_name_is_assigned` / `skill_offline`；单测 + IT | [范围 #0，验收 #1、#2、#4、#5、#7、#8、#9、#10、#11、#12、#13、#14、#15、#16、#17、#18、#19、#21、#22、#23、#24、#25](prd.md) 的服务端一半 |
| **P1 网页** | 版本表改成两段式行：上方名称 / 版本号 / 状态，下方说明 / 谁 · 时间，没有列头；说明改读 `note`；**状态标签读 `status`（五值）**；草稿行上方是名称与 `—`；**草稿行的改名/改说明/丢弃改为导航入口，动作在草稿单版页**；**版本 tab 内的单版页**（状态、名字、说明、与线上 / 上一版的差异（线上版本对照上一版；**「概览 / 改动 / 操作」三栏，改动里是文件树 + 逐文件块，操作里是动作与页底危险区**）、上线（选版本号 + 大白话 bump）、下架（后果 + 可选原因）、重新上架；**草稿页上的改名、编辑说明与丢弃**），地址钉住一版时清单收起；**发布后标题仍是名称、版本号跟在旁边**；**「操作」栏里的回滚选择器**（弹窗确认、不染红）与**页底危险区**（下架）；**改动页按文件分批**（清单先到、正文按需、单文件 400 行 / 每次 500、超过 100 个文件进单文件模式、「只看文件列表」、文件清单上方的按路径筛选）；**设置页危险区**（下线 skill / 恢复服务）；**「已下线」badge 与 503 文案**；版本表只保留导航（行内只读动作是将来扩展）；**清单上面的状态过滤（带计数）/ 搜索（名称、版本号、说明、提交人）/ 新建版本占位（仅 `write` 及以上）**，过滤与搜索写进地址（`filter` / `q`）、从单版页返回时带回；**六档各看一面**（行、计数、胶囊、行菜单按档位过滤）；**每行右上角的「更多操作」菜单只给链接**（查看文件 / 查看改动 / 该状态下的动作，都去那一版自己的页面；`role="menu"`，Esc 关、焦点还给按钮）；版本选择器对已下架的处理（名称 + 版本号 + 状态 tag、按名称与版本号筛选）；规范摘要；组件测试 | [范围 #0，验收 #3、#4、#6、#11、#12、#13、#14、#15、#18、#19、#20、#21、#22、#23、#24、#25、#26、#27](prd.md) 的界面一半 |
| **P2 CLI** | `skill submit --name`（必填）与 `--note`；**不新增 `--bump`**；检测到 `SKILL.md` 的 `version:` 时提示它不参与发布命名；消费面版本列表的 `note` / `state` / `status`（`draftName` **不进消费面**）；读已下架版本时把 410 说清楚；**读已下线 skill 时把 503 `skill_offline` 说清楚**；**`version` 组七条命令**（`list` / `show` / `diff` / `rename` / `note` / `discard` / `open`）与 `--json`；**作者 API `/api/v1/author/…`**（列表、单版、改动、改名、改说明、丢弃、文件）；`skill files` / `skill read` 接受草稿与已下架的钉；页面专属动词的拒绝消息；同意屏那一句；**CLI README 同步** | [范围 #0，验收 #2、#6、#12、#13、#18、#19、#22、#24、#28](prd.md) 的 CLI 一半 |
| **P3 文档与 ADR** | `architecture/model.md` 的版本号规范与生命周期承诺；**本版的新 ADR**（见 README §本版文档；版本名称 [0055](../../decisions/0055-drafts-have-a-required-name.md)、说明、版本号含义、下线两态、发布时命名并修订 0033（**含网关的系统发布名 `0.0.<提交序号>`：号会落成对外版本名，重复使用由 0033 的「永不复用」兜住**）、[0054](../../decisions/0054-lifecycle-actions-happen-on-a-version-page.md) 已落定；另含草稿可编辑/稳定身份、skill 级下线、回滚、当前版本不能直接下架、版本模型与下架命名（[0058](../../decisions/0058-a-version-is-readable-or-not.md)），以及本版对 0054 第 4 条的修订）；设计件按 P1 的改动更新 | [范围 #0，验收 #6、#9、#12、#13、#19、#20、#23、#24](prd.md) 的权威那一半 |

**每期明确不做**

- **P0 不做**：不做说明的全文搜索、不做版本说明的历史版本（改一次就是一次覆盖，且只允许在发布那一次）、不做服务端推荐版本号、不做版本号重命名、不做版本删除、不做自动/定时下线。
- **P1 不做**：不做**审计式的完整迁移历史**（`state_at` 只记最后一次；时间线只画行上已有的三个事实：提交、首次上线、当前状态的最近一次变更（下架 / 丢弃；**重新上架不另起一行**——它只在版本事实的「最近状态变更」里）），不做批量下线、不做消费面 diff。
- **P2 不做**：不做 `version publish|rollback|revoke|restore|offline|online|delete`，作者 API 也没有这些路由——这些动作改变调用者能读到什么，按 [ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md) §决定 2、[0046](../../decisions/0046-deleting-a-skill-happens-in-the-browser.md) 与 [0061](../../decisions/0061-what-a-credential-may-do-to-versions.md) 只在浏览器面由人确认；CLI 只留一句带页面地址的拒绝。**不做 `--bump`**，选号只在版本 tab 的单版页上。
- **P3 不做**：不改 [ADR 0033](../../decisions/0033-version-names-are-semver.md) 的正文；新 ADR 在抬头写明它修订了 0033 的哪些决定，加上「说明」「版本号含义」「下线两态」「单版页」等（完整列表见 README §本版文档）。

### 实现顺序（照依赖走，不按页面走）

**P0 服务端**

1. `V22` / `V23` / `V24` 三条迁移（只加列、约束与注释；**没有新表**，`ModuleMap` 与 `truncate-business-tables.sql` 不用动）。V23 顺带 `COMMENT ON COLUMN` 更新 `state` / `state_at`（V10 的注释还写着「只记第一次」与三态）。先跑 `./mvnw -o test -Dtest=ArchitectureTest,TableOwnershipTest`（`-Dtest=` 走 surefire；`-Dit.test=` 只选 `*IT`，这两个名字一个都不会跑）。
2. `VersionState` 加 `REVOKED`；`VersionPin` 加 `Draft`，**同一步**改完 `suffix` / `SkillVersionService.versionFor` / `describe` / `versionOf` 的 switch（否则 step 2 编译不过）；新增 `VersionStatus.of`。**钉的语法带一个 `allowDraft` 开关**：`SkillAddress.parse`（路径）与 `SkillsController.addressOf`（请求头）用 false，作者面路由用 true——消费面的 `@draft:…` 因此仍是不存在（404），请求头仍是 400。
3. `VersionRepository`：按 draft id 查找、作者集合查询（`status` / `q` / `limit` / `cursor` + `counts`）、两条状态迁移、`markPublished` 的 `COALESCE first_published_at`、低档位跳过 `revoked`、**回滚时的两行 `state_at` 更新（一条语句改两行，`executeUpdate` 必须等于 2；只到 1 行就是并发下状态已变，整条事务失败）**；**新增一次不看状态的按钉查找**（仓库只回答「这一版是什么状态」）；三态解析放在 `SkillVersionService`（下一步），仓库不抛业务异常。
4. `SkillVersionService`：**新增 `resolveForConsumption(Access, VersionPin)`**（`published` 返回快照、`revoked` 抛 `VersionRevokedException`、其余为空 → 404；`Latest` 仍走 `findCurrent`，不进三态查找；**它只管 `invoke` 下限**，`browse` 那道闸留在 `liveSnapshotAtLeast`——两条调用方要求不同的档位，一个 `Access` 分不出来，把 `browse` 塞进去会让 `invoke` 的 metadata/正文/文件读变 403），**`liveSnapshot` 与 `detailedSnapshot` 都改调它**；`publishVersion` 接 `releaseName`（可空）；提交写 `draft_name` / `changelog`；改名、改说明、下架 / 重新上架；回滚时两行 `state_at`；`authoredView` / `authorSnapshotAtLeast` / `authorSnapshot` 补 `revoked` 的 `maintain` 过滤。
5. `ingest`：`SkillUpload` 加 `draftName` / `note`，移除 `version`；`SkillUploadValidator` 校验名称与说明、不再提取 `version`；**同一步**改 `Uploads.metadataOf` 与 `SkillMetadata`（`SkillMetadata` 移除 `version` 分量）；版本级事实归 M7：`SkillVersionService.submit` / `submitVersion` / `record` 与 `VersionRepository.insertIfAbsent` 都接 `draftName` / `note`，`version` 写 NULL；`PublishGatewaySkillUseCase` 按附录 A 接上系统路径。
6. use case：`SubmitSkillUseCase.submit` 与 `SubmitSkillVersionUseCase.submitVersion` 都接上 `draftName` / `note`（**两条提交路都要改**，只改一条会让「给已有 skill 加版本」丢名字）；`UpdateDraftNameUseCase`、`UpdateDraftNoteUseCase`、`RevokeVersionUseCase`、`ListAuthorVersionsUseCase`、`ReadAuthorVersionUseCase`、`SetSkillOfflineUseCase`；`PublishSkillVersionUseCase` 接 `release_name`；`DiscardSkillVersionUseCase` 接 `draft:<id>`。
7. M12：`offline_at` 的读与写（`Access.offlineAt`）、`recordPublish` 不变；搜索 / 公开列表过滤已下线；`SkillOfflineException` 放 `common`，`ReadSkillUseCase` 的守卫与 503 映射在下一步接上。
8. 交付层：`ErrorCode` 新码；`SkillsController` 的 multipart；`WebSkillController` 的名称 / 说明 / 下架 / 重新上架 / 下线 / 恢复路由；`AuthorSkillController` 与两个作者响应；`SubmitResponse` / `AuthoredSkillResponse` / `SkillVersionsResponse` 的字段。
9. diff：`SkillDiffResponse` 加 `next`；`DiffService` 的 numstat、分批、LRU、ETag；`DiffAuthoredSkillUseCase` 的默认基线与两侧档位。
10. 测试：每个 use case 的单测；**新增** `AuthorSkillIT`（作者 API）、`GatewayIT` 加一条启动发布用例；扩 `SkillSubmitIT`、`SkillVersionServiceIT`、`SkillVersionPinIT`、`WebSkillsIT`、`SkillContentIT`（410 / 503）；diff 的 numstat / `file=` / batch 单测。最后 `./mvnw -o verify`。

**P1 网页**（顺序见 §前端实现顺序）

**P2 CLI**

1. `internal/api/author.go`：作者 API 的客户端与 `--json` 结构（`version` 对象的字段照 §作者 API 的 JSON 契约）。
2. `cmd/skillmaster/version.go`：七条命令、参数校验、页面专属动词的拒绝、`open` 的地址。
3. `main.go`：顶层分发、`usage()` / `skillUsage`、`submit` 的 `--name` / `--note`、`version:` 提示。
4. `client.go`：消费面字段、410 / 503 文案、`files` / `read` 的面路由。
5. `README.md` 同步；`go test ./...`；`CGO_ENABLED=0 go build -trimpath -o skillmaster ./cmd/skillmaster`。

**每一期的出口条件**：这一期涉及的验收编号在 [test-plan](test-plan.md) 里有对应用例并通过；`check_docs.py` 仍为 0；没有实现任何文档里没有的东西——发现了就先回写文档。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 旧草稿带着 `SKILL.md` 的 `version`，发布时怎么处理？ | 按「已分配」保留原名，不要求 `release_name`；不回填、不清空 | 用一条旧草稿发布一次验 |
| Q2 | 说明的长度与空白规则是否合适？ | 200 码点；trim；换行折成空格；拒绝控制字符 | P1 用真实的一句中文与一句英文各验一次排版；不够再调，不在实现里静默截断 |
| Q3 | CLI 提交时检测到 `version:`，提示到什么程度？ | 打印一句「不参与发布命名；发布时再定」，不阻塞、不报错——它仍是合法内容 | P2 用一个带 `version:` 的 skill 验一次 |
| Q4 | 发布 / 回滚时请求带了 `note` 怎么办？ | 一律 400 `invalid_request`——发布不接收说明，说明只在草稿的概览里改。网页在发布与回滚时都不发这个字段 | 已定（2026-10-20）：发布表单不再有说明输入 |
| Q5 | 说明要不要进版本选择器？ | **不进**。选择器回答「读哪一版」，说明回答「改了什么」；选项是**名称 + 版本号 + 状态 tag**，不是一句说明（2026-10-20 定：下拉带搜索，名称与版本号都能筛） | P1 保持 `VersionPicker` 只显示名称、版本号与状态 |
| Q6 | 界面上要不要标「试用」？ | **不标（2026-10-20 定）**：`0.y.z` 的语义保留在版本号里，没有对应的动作或权限；加一枚章只会多一个要解释的词（[prd](../v6-version-management/prd.md) §不做 #15）。服务端不推断、API 不加字段 | 无（已定） |
| Q7 | 草稿说明谁能改？ | 与「丢弃」同一条规则：`write` ∧ 提交人是我，或 `maintain` 及以上。理由是它与丢弃一样，是对别人草稿的一次写；已上线行没有路 | 用五档凭据各验一次（[验收 #11](prd.md)） |
| Q8 | 下架一个版本，消费面回 410 还是 404？ | **已定：410 `version_revoked`**——它曾经可读，调用者也持有钉；410 说的是「有过、被下架了」，404 会混成「地址打错了」。这是对「一律 404」的有意例外 | 按验收 #13 核对；若改 404，改一行错误映射，语义变成「与不存在同答案」 |
| Q9 | 作者面能不能读已下架的版本？ | **能**，按档位（`maintain` 及以上），用于审计；下架只关消费面 | P1 用 `maintain` 与 `browse` 各读一次，确认前者看得到、后者看不到 |
| Q10 | `state_at` 在下架时覆盖上线时间，可以接受吗？ | 不再需要取舍：新增 `first_published_at` 保留首次上线，`state_at` 只记最后一次迁移。消费面的 `publishedAt` 读前者 | 读一个已下架版本的 `publishedAt`，必须是首次上线时间，不是下架日期 |
| Q11 | `admin` 能不能下架？ | **能**，`maintain` 及以上。下架改变消费面，但 `maintain` 已经在管上线；把版本级下架抬到所有者没有增加保护，只是让人多找一次所有者。**只有下线整个 skill 是所有者** | 用 `maintain` / `admin` / 所有者各试一次（[验收 #15](prd.md)） |

**不写「待定」**——要写「当前倾向 X，因为 Y；验证方式是 Z」。

## 附录

### 附录 A · 与现有代码的关系

| 位置 | 本版怎么动 |
|---|---|
| `V1__baseline.sql:387` / `:410` | 列本身不动；`V22` 改注释（与可选的 `CHECK`）；`V23` 加生命周期列与约束；`V24` 加 `skill.offline_at` |
| `modules/version/VersionState.java` | 只加 `REVOKED`（`DRAFT` / `PUBLISHED` / `DISCARDED` 保留，**不加 `DEPRECATED`**）；改类 javadoc（转移不再是单向三条，共四态） |
| `modules/version/internal/VersionRepository.java` | 三处消费面过滤保持 `published`；**另加一次不看状态的按钉查找**（同一 skill 内按版本号 / digest 找行，只为消费面回答 410：`revoked` → 410，`draft` / `discarded` → 404）；新增两条状态迁移；`markPublished` 用 `COALESCE` 写 `first_published_at`；`findNewestNotDiscarded` 对低档位跳过 `revoked`；`findCurrent` 不变；**新增按 draft id 的查找**（`VersionPin.Draft` 的解析）；**新增作者集合查询**（`status` / `q` / `limit` / `cursor` 与 `counts`）——现有 `versionsOf` 没有分页、筛选与计数 |
| `usecase/RevokeVersionUseCase.java` | 新增：下架 / 重新上架；权限与状态规则按 §版本状态机；**下架拒绝当前版本** |
| `modules/skill/…`（M12 接缝） | 上线/回滚仍走 `recordPublish(skillId, version, drafts, at)` 移动指针；**下架不再清空指针**，不需要新增 `recordCurrentCleared` |
| `usecase/PublishGatewaySkillUseCase.java` | 系统提交带 `draftName("gateway")`；**只有提交返回的是 `draft` 且 `version` 为空时**，发布带 `release_name = "0.0." + SubmittedSkill.number`（短、唯一；`number` 是提交序号）；已发布的旧行（含 v6 之前 `version` 为 NULL 的网关行）**不带** `release_name`，它保持无名、按 digest 寻址；`VersionNameTakenException` 的 catch 从提交挪到发布 |
| `usecase/SubmitSkillUseCase.java` | 改成 `submit(zip, draftName, note, subject)`，**删除旧签名**；两个调用点（API 控制器与 gateway）各自传名字（gateway 传 `"gateway"`）；`SubmitOutcome` / `SubmittedSkill` 都加 `number`（提交序号，短；只在网关的 `release_name` 推导里用，**不进任何 JSON**）**与 `id` / `draftName` / `note`**（从库里读回的值——幂等重放要回显**已存的**名字与说明，不能回显这次请求的），`SubmitOutcome` → `SubmittedSkill` 是唯一搬运点 |
| `usecase/SubmitSkillVersionUseCase.java` | **同样改成 `submitVersion(namespaceSlug, name, zip, draftName, note, subject)`**——`POST /api/v1/skills/{ns}/{name}/versions` 的 multipart `name` / `note` 从这里进来；不改这条，「给已有 skill 加版本」的草稿名与说明会丢，T4/T32 读回是空 |
| `modules/skill/SkillMetadata.java` | 移除 `version` 分量（版本号只由发布写；`Uploads.metadataOf` 不再从 frontmatter 取它）。草稿名与说明是版本级事实，**不进这个 record**，由 `SkillVersionService` 的参数承载 |
| `modules/version/internal/VersionRepository.java`（`insertIfAbsent`） | 不再读 `skill.version()`：接 `draftName` / `note` 写入 `draft_name` 与 `changelog`，`version` 列留 NULL；幂等命中时不覆盖两者 |
| `usecase/Uploads.java` / `modules/skill/SkillMetadata.java` | 不再从 `SkillUpload.version` 取版本名（那一列只由发布写）；`SkillMetadata` 去掉 `version` 分量 |
| `modules/version/SkillVersionService.java`（提交两路） | `submit(skillId, skill, manifest, draftName, note, submittedBy, source, at)` 与 `submitVersion(access, skill, manifest, draftName, note, submittedBy, source, at)`，`record(...)` 同参——**版本级的两个事实从这里的参数进来，不从 `SkillMetadata` 借路** |
| `modules/skill/SkillSnapshot.java` / `api/SkillDetailResponse.java` | 加 `firstPublishedAt`；`/metadata` 的 `publishedAt` 读它，不读 `stateAt` |
| `api/AuthoredSkillsResponse.java` | 列表的 `Skill` 加 `offlineAt`（「我的 skill」显示「已下线」） |
| `modules/ingest/SkillUpload.java` | **新增 `draftName` 分量**（版本名称，必填；multipart 里新增的那一部分叫 `name`）与 `note` 字段（都是提交请求的一部分，不是 frontmatter）。**不能复用现有的 `name`**——它已经是 skill 自己的名字（从 frontmatter 取），记录里也不能有两个同名分量；**移除 `version` 字段** |
| `modules/ingest/SkillUploadValidator.java` | 校验版本名称（1–40 个 Unicode 码点；中文、英文、数字、符号都可以，只拒绝空白与控制字符）与说明；不再从 frontmatter 提取 `version` 写进上传模型；那个字段仍留在 `frontmatter` 里透传 |
| `modules/version/SkillVersionService.java` | **`publishVersion(Access, VersionPin, String releaseName)`**（`releaseName` 可空；草稿且无版本号时必填，已发布目标必须为空）；INSERT 写 `draft_name` 与 `changelog`，`version` 留 NULL；幂等提交不覆盖；新增改名方法；新增下架 / 重新上架两个方法（`publishVersion` / `discardVersion` 今天已有，且已经按 `state = 'draft'` 守卫——新方法照同一种写法），每次写入都带期望状态；**新增 `resolveForConsumption(Access, VersionPin)`，并把 `liveSnapshot` / `detailedSnapshot` 改调它**（三态）；**给现有的作者读路径补 `revoked` 要 `maintain` 的过滤**——`authoredView`（网页版本表）与 `authorSnapshotAtLeast`（单版）在 `SkillVersionService` 里；`authorBodyOf` / `authorFileOf` 在 `SkillDistributionService`（`SkillDistributionService.java:112,137`，它们委托 `SkillVersionService.authorSnapshot`）——今天它们在 `write` 下会把非 published 的状态一起带出来；不改，T35/T36 的「write 看不到已下架」就会漏；**回滚时目标与原线上两行都写 `state_at = now`**（首次上线的 `COALESCE` 不动） |
| `usecase/UpdateDraftNameUseCase.java` | 新增：版本名称的写入口（字段 `draft_name`），按上面的权限与状态规则 |
| `usecase/PublishSkillVersionUseCase.java` | `publish(...)` 多 `release_name`（不接收 `note`）；在 `draft → published` 那一次分配 `version`（旧草稿已有名则保留）、写 `first_published_at`；说明保持草稿期的值；版本名称不动 |
| `api/SkillsController.java:304` / `:348` | multipart 增加 `name`（必填）与 `note` 部分 |
| `api/WebSkillController.java:247` | 新增 `PATCH …/versions/{version}/name` 与 `PATCH …/versions/{version}/note`；`VersionChoice` 增加 `release_name`（可空）与 `note`（可空）；回滚带 note → 400 |
| `api/SubmitResponse.java` | `version.name`（版本号）对草稿为 `null`；增加 `draftName`、`note` 与 `draftId`（草稿行非空，其余为 `null`） |
| `usecase/UpdateDraftNoteUseCase.java` | 新增：草稿说明的写入口，按上面的权限与状态规则 |
| `api/AuthoredSkillResponse.java` | `Version` 增加 `draftName` / `draftId` / `note` / `revokedReason` / `status`；顶层增加 `offlineAt` |
| `api/SkillVersionsResponse.java:46` | `Version` 增加 `note`、`state`、`status`；`publishedAt` 改读 `firstPublishedAt` |
| `modules/version/VersionSummary.java` | 加 `id`（`draft:<id>` 的来源）、`firstPublishedAt`、`draftName`、`note`（`changelog`）、`revokedReason`；`state` 的词表只扩一个值 `REVOKED`。**`VersionRepository` 的查询必须把这些列一起选出来**，两个响应都从这里读 |
| `modules/version/VersionNameTakenException.java` | 触发点从提交移到发布；消息仍要说清「这个名字已经被用掉了」 |
| `modules/distribution/SkillDiff.java` / `DiffService.java` / `api/SkillDiffResponse.java` | `File` 加 `size`（已知就有）；文件清单一次算 numstat（今天的 `File` 带 `hunks` 时已有 `added` / `removed`，不带时为 `null`）；新增 `next` / `cursor` 续取——今天的响应只有 `truncated`，截断后没法接着取；`(from digest, to digest)` 的有界 LRU 与 `ETag` 也落在 `DiffService` 这一层 |
| `modules/version/VersionRevokedException.java`（新） | version | 消费面按钉读到一个 `revoked` 行时抛出；`SkillVersionService` 的新方法 `resolveForConsumption(Access, VersionPin)` 先按 `invoke` 下限解析、再不看状态找行，`published` 返回快照、`revoked` 抛它、其余返回空（→ 404）。**具名调用点：`SkillVersionService.liveSnapshot` 与 `detailedSnapshot` 都改调它**——`SkillDistributionService.readableIn` / `detailedIn`（`SkillDistributionService.java:157,162`）分别委托这两条，**四个按钉的消费面读**由它们兜底（版本列表 `liveVersionsOf` 不走这里：未钉的列表没有 410，不要给它加 `revoked` 分支）；只加方法不改这两条解析，410 永远到不了，`detailOf` 还会与 metadata/正文给出两个不同的答案 | `VersionRepository` |
| `usecase/ReadSkillUseCase.java` / `common/ErrorCode.java` | 消费面五个读共用一个离线守卫（`Access.offlineAt` 非空 → 503）；`ErrorCode` 加 `skill_offline` 与 `version_revoked` 的异常映射 |
| `common/ErrorCode.java` | 新增 `draft_name_required`、`draft_name_too_long`、`draft_name_is_frozen`、`note_too_long`、`note_is_frozen`、`version_revoked`、`invalid_version_state`、`invalid_release_name`、`release_name_required`、`version_name_is_assigned`、`skill_offline` |
| `skillmaster-web/src/api/types.ts` | `AuthoredVersion` 的 `name`（版本号）已经是 `string \| null`，不改名；加 `draft_name` / `draft_id` / `note` / `revoked_reason` / `status`（`state` 已有）。**`AuthoredSkillSummary`**（列表的 TS 接口）加 `offline_at`；`DiffFile` 加 `size`（可空）。 |
| `skillmaster-web/src/pages/SkillPage.vue` | 版本表改成两段式行：上方名称 / 版本号 / 状态，下方说明 / 谁 · 时间，没有列头；说明改读 `note`；草稿行的版本号是 `—`；**草稿行的改名/改说明/丢弃改为导航入口**；补「已下架」状态；**行末的所有改变状态的动作改为链接**，点进去只导航到版本 tab 的单版页；**列表只保留导航**（下载/复制/查看差异这类行内只读动作是将来扩展，今天不画）；**清单上面加状态过滤（带计数）/ 搜索 / 新建版本占位（仅 `write` 及以上）**，过滤与搜索进地址（`filter` / `q`）；**六档各看一面**（行、计数、胶囊、行菜单按档位过滤；**调用者的行身份是 `@版本号`、行尾只写上线时间**）；**每行整行可点**（名称的真链接用 `::after` 铺满整行，悬停整行压 `--wash`）；**每行右上角的「更多操作」菜单只给链接**（查看文件 / 查看改动 / 该状态下的动作，都去那一版自己的页面；`role="menu"`，Esc 关、焦点还给按钮）；单版页由地址里的版本钉切换（没有版本钉时是清单）；**文件栏的提交行右端写着这一版的名称与版本号，它是回这一版页面的链接**（与单版页的「查看文件」互为镜像，时间不跟着可点）；**页头与「我的 skill」显示「已下线」；设置 › 危险操作增加「下线 skill / 恢复服务」** |
| `skillmaster-web/src/pages/SkillPage.vue` | 版本栏改成清单 / 单版页两种状态（`version=`）；**「版本 N」的计数改成非草稿、非已丢弃（`state IN ('published','revoked')`，含已下架）**（今天只数 `state === 'published'`，维护档会少一）；清单两段式行、整行链接、过滤 / 搜索、更多操作菜单；文件栏替换成共享 `VersionPicker` 并用 `DiffFileBlock` 画差异；提交行右端加版本名链接；页头与「我的 skill」显示「已下线」；设置 › 危险操作加「下线 skill / 恢复服务」 |
| `skillmaster-web/src/pages/SkillDiffPage.vue` / `SkillFilePage.vue` / `components/SkillHeading.vue` | **删除**：没有任何路由引用它们（`App.vue` 只到 `SkillPage`）；单版页在 `SkillPage` 的版本栏里实现 |
| `skillmaster-web/src/components/VersionPicker.vue` / `RungMenu.vue` | `MenuSelect` 抽出下拉机制；`RungMenu` 变薄壳；`VersionPicker` 换成菜单并接进 `SkillPage` 的文件栏 |
| `skillmaster-web/src/components/DiffView.vue` | 拆出 `DiffFileBlock` 后**删除**；小比较也由 `DiffFileBlock` 渲染（v6 只有一种行画法） |
| `skillmaster-web/src/App.vue` / `pages/SkillPage.vue` / `version.ts` | 单版页沿用 `/skills/{ns}/{name}?tab=versions&version=<pin>`（不新增路径段）；`version.ts` 的 `isVersionSuffix` 接受 `draft:<id>`——没有这两处，`version open` 打不开草稿页，网页的草稿单版页也解析不了钉 |
| `skillmaster-web/src/components/VersionPicker.vue` | 文件栏的选项是**当前档位读得到的全部版本**：线上、草稿（`write` 及以上）、历史、已下架（`maintain` 及以上），各自带状态 tag；**已丢弃没有文件快照，不列**；只有地址钉住而这一档读不到的版本作为禁用项。对比基线与回滚目标各自传入自己的集合（前者含草稿，后者只含历史版本）。草稿项显示名称，发布过的选项是「名称 + 版本号 + 状态 tag」；选项多时在列表内滚动，搜索框固定 |
| `skillmaster-cli/internal/api/submit.go` | multipart 增加 `name`（必填）与 `note`；**不新增 `--bump`，也不做改 `SKILL.md` 的路径** |
| `skillmaster-cli/internal/api/client.go` | 版本列表解析 `note` / `state` / `status`（消费面没有 `draftName`）；410 `version_revoked` 单独解释；503 `skill_offline` 单独解释；**`files` / `read` 的面路由**：命名 / 摘要钉先走消费面，`404` / `410` 且调用者有作者权限时再走作者面；`draft:<id>` 直接走作者面——客户端拿不到 pin 的状态，不能靠「按钉所在的面」猜 |
| `skillmaster-cli/internal/api/author.go` | 新增：作者 API 的客户端（版本列表 / 单版 / 改动 / 改名 / 改说明 / 丢弃 / 草稿与已下架版本的文件），以及 `--json` 的稳定结构 |
| `skillmaster-cli/cmd/skillmaster/version.go` | 新增：`version` 组的七条命令与它们各自的参数校验；页面专属动词的拒绝消息；`version open` 复用 `config.WebURL()` |
| `skillmaster-cli/cmd/skillmaster/main.go` | 顶层分发加 `version`；`skillUsage` 与全局 `usage()` 加这一组；`skill files` / `skill read` 按钉所在的面路由；`submit` 增加必填 `--name` 与可选 `--note`（**只用参数，不新增交互提示**）；检测到 `version:` 时提示它不参与发布命名；README 同步 |
| `api/AuthorSkillController.java` | 新增：`/api/v1/author/skills/{ns}/{name}` 下的读与草稿写（列表、单版、改动、改名、改说明、丢弃、文件）；列表与单版在入口显式要求 `browse`+（**不复用网页那条对 `invoke` 也给投影的路径**）；返回 API 自己的作者面版本记录（列表是新响应 `{skill, versions, counts, next}`），**不把 `/web` 的资源地址交给 API 调用方**；**没有 publish / revoke / restore / rollback / offline / online / delete 映射** |
| `api/SkillAddress.java` / `modules/version/VersionPin.java` | 增加 `draft:<id>` 这种钉（`VersionPin.Draft(id)`）与按 draft id 的查找；没有它，`{pin}` 路由、`skill files/read @draft:<id>` 与网页的草稿地址都无法解析。**消费面的 `X-Skill-Version` 仍拒绝 `draft:<id>`（400）**——不改变消费契约，`draft:<id>` 只在作者面解析。**钉的解析带一个 `allowDraft` 开关**：`SkillAddress.parse`（路径）与 `SkillsController.addressOf`（请求头）用 `false`——消费面的 `@draft:…` 因此仍是「不存在」（路径 404、请求头 400，与今天同答案）；只有作者面路由用 `true`。不能只给共用的 `pinOf` 加 Draft 而不加开关 |
| `api/VersionChoice.java` | 注释补一句：PATCH 的路径钉是有意例外（方法本身已说明是写），POST 动作（publish / discard）继续把版本钉放 body |
| `usecase/DiffAuthoredSkillUseCase.java` | 默认基线从「当前线上」改为「上一版」（按 `first_published_at` 排序的前一版；草稿、第一版，**或上一版是这一档看不见的（已下架 / 已丢弃）**时退回当前线上；线上也看不见就不画改动）——这是 v6 的新默认，不是现有行为；**门槛从 `write` 改为按每一侧自己的可见性解析、取两侧最高**（已发布 `browse`、草稿 `write`、已下架与已丢弃 `maintain`；与六档视图一致；`write` 拿已下架或已丢弃的钉当一侧 → 404） |
| `design/partials/auth-overlay.html` 与 `skillmaster-web/src/pages/CliSignInPage.vue` | 同意屏 `skills:write` 那一行改为「以你的身份提交新版本、管理草稿与共享」；清单仍不含「发布」「删除」。`ConsentPage.vue`（第三方那条路）不动 |
| `docs/architecture/model.md` | 增加「版本号对调用者的含义」与「生命周期的两条承诺」两节（常驻权威） |
| `docs/decisions/` | 新增一条 ADR 修订 [0033](../../decisions/0033-version-names-are-semver.md)：版本号在发布时分配，不再读 `SKILL.md` 的 `version:`；另新增 [ADR 0054](../../decisions/0054-lifecycle-actions-happen-on-a-version-page.md)：生命周期动作发生在版本 tab 的单版页上，**本版修订其第 4 条：所有改变状态的动作（含草稿的改名/改说明/丢弃）都在单版页，列表只保留导航（行内只读动作是将来扩展）**；**本版还需 ADR**：草稿有必填名字、与说明及版本号分开（[0055](../../decisions/0055-drafts-have-a-required-name.md)）；草稿可编辑、发布后不可变、草稿用稳定身份 `draft:<id>`；skill 级下线与版本下架是两层动作；回滚是当前版本页的入口、底层仍是上线；**当前版本不能直接下架，必须先回滚成历史版本**；版本只有可读 / 不可读两轴（[0058](../../decisions/0058-a-version-is-readable-or-not.md)）；**丢弃草稿不是危险动作**（不影响线上，弹窗确认但说清无法找回；[0060](../../decisions/0060-discarding-a-draft-is-not-dangerous.md)） |

### 附录 B · 来源与出处

- **一手**：本仓库的 `V1__baseline.sql:387/:410`（`changelog` 列与注释）、`SkillVersionsResponse.java:46`（消费面现在的字段）、`WebSkillController.java:247`（发布请求体）、`SkillVersionsResponse.java:15-18`（消费面只列已上线）。
- **一手**：[SemVer 2.0.0 规范](https://semver.org/lang/zh-CN/) 的 MAJOR / MINOR / PATCH 定义；本版只是把它翻译成 skill 的调用契约，不改规范本身。
- **二手**：宿主 `plugin.json` 对 `version` 的要求，转引自 [ADR 0033](../../decisions/0033-version-names-are-semver.md) §背景——那里已经核对过一手来源，本 TD 不重复核对。

### 附录 C · 已测量的数据

| 指标 | **口径**（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| `skill_version.changelog` 的现有写入点 | 全仓 `src/main/java` 搜 `changelog` 的写入（SELECT 不算），2026-10-20 复核 | **1 处**：`VersionRepository.insertIfAbsent` 写入字面量 `''`（`V1__baseline.sql:387` 的默认值），没有任何接口设置它——所以本版不需要回填，但「0 处」这个旧数字是错的 |
| 消费面版本列表的现有字段 | `SkillVersionsResponse.Version` 的组件，2026-10-18 | 4 个：`name` / `digest` / `publishedAt` / `isCurrent`；本版加 `note` / `state` / `status` |
| 作者面版本行的现有字段 | `AuthoredSkillResponse.Version` 的组件，2026-10-18 | 含 `title`（说明列现在读它）；本版加 `draftName` / `draftId` / `note` / `revokedReason` / `status` |
