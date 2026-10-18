# v5-skill-management · 技术设计

> **最后更新**：2026-10-18
> **状态**：实现已完工（服务端 P0、网页 P1 与 CLI P2），验收未执行
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

## 架构

### 元数据与内容是两个模块

这一版改的全部是 **skill 元数据**：谁在什么档位上能做什么、这个 skill 对外可见到什么程度、它被删了能不能找回、谁能申请。**版本与正文一个字都不改**——所以代码与数据按这条线切开：

| | 模块 | 拥有 | 负责 |
|---|---|---|---|
| **M12** | **skill 元数据** | `skill`、`skill_grant`、`skill_access_request` | 地址与身份、权限梯子、授权与申请、可见性、删除 / 恢复、**列表要用的投影字段** |
| **M7** | **版本与内容** | `skill_version`、`version_file`（字节仍在 M6） | 不可变版本、提交 / 上线 / 丢弃、引用计数 GC；**文件清单与 diff 是 M9（分发）读这些版本数据给出的**，不归 M7 |

**本版仍然只有一个库，但两类表不混。** 元数据（`skill` / `skill_grant` / `skill_access_request`）与内容（`skill_version` / `version_file` / `blob*`）各有自己的表与归属；模块边界因此按表来划：**元数据不联版本表，内容不读 `skill` 表**。元数据表按自身需要扩展——加字段、加关联表都不碰内容；本版的梯子、可见性、申请与 V21 的投影就是四次这样的扩展。取舍与否掉的选项见 [ADR 0053](../../decisions/0053-skill-metadata-is-its-own-module.md)。

请求仍按 v1 的三层进来，只是第 ② 层换了归属、并多了一步 ④：

```
请求
 │
 ├─① 凭据的 scope          SecurityConfig     skills:read / skills:write            ← 不动
 │
 ├─② 地址 → 元数据判定      M12 元数据         owns / grant / public → 实际档位       ← 本版核心
 │
 ├─③ 这个操作要哪一档       用例层             atLeast(档位)                          ← 本版核心
 │
 └─④ 要版本或正文时          M7 / M9 内容侧    拿 ② 的判定取数（不再自己查权限）
```

**②的形状不变**：仍然是一条 SQL 从 `skill` 出发、一次给出三个答案——**那一行**、**是不是我的**、**我的实际档位**。本版给它加一个来源（公开交给所有人的那一档），并把答案从「一个可空的被授角色」变成「最高档位」：

```
实际档位 = owns → 所有者（高于一切，不可授）
          否则 max( 我被授的那一档 , 公开交给所有人的那一档 )
          都没有 → 够不到
```

**③从两个布尔换成一个门槛。** 今天多数操作问的是 `mayWrite()` 或 `mayAdminister()`（丢弃问的是 `owns()` 加「提交人是我」）；本版问的是「够不够 `atLeast(某一档)`」。逐条见 §接口。原因不是整齐，是**「加一档」不再需要动判定**。

### 元数据与内容的三条硬规则

1. **一条 SQL 不得同时点两个模块的表。** 元数据的查询不出现 `skill_version`，内容的查询不出现 `skill`。列表要的事实因此落在 `skill` 行上（V21 的投影列）。
2. **内容的权限判定由元数据给出。** 内容模块不再自己读 `skill` / `skill_grant`；用例层先从 M12 拿到 `Access{skillId, owns, rung, visibility, publicRung, currentVersionId, callerId}`，再交给内容模块。内容模块据此决定「给不给文件清单」「给不给草稿」。
3. **跨模块事务仍由用例层划定。** 本版一个库，一次提交 / 上线 / 丢弃仍在同一个事务里；「写版本 + 更新元数据投影」是两处写，但同属一个事务边界。接缝的写入仍然做成**幂等、可重放**——这是接口的好形状，不是为分布式事务提前铺路。

### 元数据接缝

内容模块需要的三件事，由 M12 的公开接缝回答（签名是形状，不是最终代码）：

- `accessFor(namespaceId, name, caller) → Optional<Access>`：`Access` 带 `skillId`、`owns`、`rung`、`visibility`、`publicRung`、`currentVersionId` 与调用者身份 `callerId`（上面那条）。地址解析与「够不够得到」都收在这里；内容模块拿到的已经是判定结果。`currentVersionId` 是不透明 id：内容模块拿它解析 `latest` 与 `isCurrent`，但不读 `skill` 表。
- `findOrCreate(namespaceId, metadata, createdBy, at) → Optional<skillId>`：提交一个新 skill 时建地址行（已存在则不动）。
- `recordSubmission` / `recordDiscard` / `recordPublish`：把列表要用的投影写回 `skill` 行（见 §数据模型 V21）。`recordPublish` 同时写 `current_version_id` 与当前版本的展示投影，并重算草稿面（被上线的那一版离开了草稿集合）。

**反方向没有接缝**：元数据不需要版本内容——列表要的事实都在 `skill` 行上。这正是投影列存在的理由。

### 三条边界的移动，各要一处

| 边界 | 今天 | 本版 | 落点 |
|---|---|---|---|
| 上线 | 只有所有者 | `维护` 档 | M7 `publishVersion` 的门槛 |
| 授权 | 只有所有者 | `管理` 档 | M12 `SkillSharingService` 的门槛 |
| **删除** | 只有所有者 | **仍然只有所有者** | M12 `softDelete` 用 `owns()`，**不是** `atLeast(admin)` |

**第三条是收紧，最容易在实现里被漏掉**：`mayAdminister()` 今天恰好等价于 `owns()`，所以把 `softDelete` 从 `mayAdminister()` 改成 `atLeast(ADMIN)` 看起来是一次纯粹的改名，而它其实放开了一个能力。这条要有自己的测试（见 [`test-plan.md`](test-plan.md) T5）。

### 公开档位与消费面

`public` 的含义是「谁都能搜到它、谁都能调用它」（[ADR 0037](../../decisions/0037-visibility-has-two-states.md) §决定 2），所以本版起**消费面的两个谓词都要认它**：

- M12 的 `permitted()`——单条读的访问谓词
- M12 的列表查询——搜索与 `list`（V21 之后它只查 `skill`，不联版本）

**只改一处会造出一个「搜得到但打不开」的 skill**，那比两处都不改更难发现。这是本版最容易被漏掉的一处实现风险。

## 数据模型

**四张迁移，一张新表，外加一组投影列。** 改动落在 **M12** 新拥有的 `skill` 与 `skill_grant` 上，**外加**一张 `skill_access_request`（申请不是授权，见 [ADR 0052](../../decisions/0052-a-request-is-not-a-grant.md)），再给 `skill` 加一组「列表要的事实」（V21）。`ModuleMap` 把 `skill` / `skill_grant` / `skill_access_request` 划给 M12，把 `skill_version` / `version_file` 留给 M7；`TableOwnershipTest` 自己从 `db/migration` 收表再对账，没有第二处清单要改。

**元数据那一版的两列**（改描述与描述覆盖）仍不在本版（[prd §不做 #8](prd.md)、[ADR 0049](../../decisions/0049-an-edited-description-outranks-the-version.md)）：那一版从 `V22` 起编号。

### V18 · 权限是一条梯子

```sql
-- 词表换了，不是加值：viewer / editor 从此不再出现在 skill_grant 里
--（namespace_member.role 是另一张表，本版不动它）。
ALTER TABLE skill_grant DROP CONSTRAINT skill_grant_role;
UPDATE skill_grant SET role = 'browse' WHERE role = 'viewer';
UPDATE skill_grant SET role = 'write'  WHERE role = 'editor';
ALTER TABLE skill_grant ADD CONSTRAINT skill_grant_role
  CHECK (role IN ('invoke', 'browse', 'write', 'maintain', 'admin'));

-- V12 的表/列注释里还写着 viewer / editor，一并订正（与 V19 对 visibility 的做法相同）。
COMMENT ON TABLE skill_grant IS
  'A skill shared with an account (ADR 0034/0047). The role is one rung of the ladder, and each rung includes everything below it.';
COMMENT ON COLUMN skill_grant.role IS
  'invoke | browse | write | maintain | admin. Five grantable rungs (ADR 0047); the owner is not in this table.';
```

- **`owner` 仍然不是这里的值**，理由与 `V12` 当时写下的一字不差（skill 的所有者是它命名空间的所有者，`skill.namespace_id` 已经说了这件事）。梯子把「所有者」画在最上面，但它**不是五个可授值之一**——它是 `owns`。
- **顺序不存在库里。** 五档的**次序**是代码里的一个常量（[ADR 0047](../../decisions/0047-permissions-are-a-ladder.md) §后果：`GrantRole` 从一对 String 常量变成有序的东西），不是数据。库里存的是档位的名字，与今天存 `viewer` 是同一件事。
- **不加索引。** `idx_skill_grant_grantee` 已经覆盖唯一的查询方向；判定不走「按 role 找行」，走「按 (skill, grantee) 找那一行」。

### V19 · 公开是把某一档交给所有人

```sql
-- 订正两处过期的注释：ADR 0037 之后 unlisted 已经不存在。
COMMENT ON COLUMN skill.visibility     IS 'private | public. See ADR 0037, ADR 0048.';
COMMENT ON COLUMN namespace.visibility IS 'private | public. See ADR 0037, ADR 0048.';

ALTER TABLE skill ADD COLUMN public_rung TEXT;

-- 「公开」与「给哪一档」是**一个状态的两个字段**，不允许各说各的。
ALTER TABLE skill ADD CONSTRAINT skill_public_rung CHECK (
  (visibility = 'private' AND public_rung IS NULL) OR
  (visibility = 'public'  AND public_rung IN ('invoke', 'browse'))
);
```

- **两列而不是三个值**，理由见 [ADR 0048](../../decisions/0048-visibility-hands-out-a-rung.md)。
- **`private` 是今天唯一存在的值**，所以回填是空的——已有行全部满足新约束，不需要数据迁移。
- **`namespace.visibility` 不在本版范围内**（命名空间级的公开没有产品需求），但它的列注释同样写着过期的 `unlisted`。**本次一并订正注释**（V19 里连同 `skill.visibility` 两条 `COMMENT ON COLUMN`），仅此而已——[ADR 0037](../../decisions/0037-visibility-has-two-states.md) §后果 说「更正挂到下一次真正需要动这两张表的迁移上」，就是这一次。
- **没有索引。** `public_rung` 只跟着那一行走，判定永远已经从 `skill` 那一行出发。

### V20 · 申请是一档，不是一条授权

```sql
-- 申请是独立的对象：它的主键就是「谁、在哪个 skill 上」，所以一个人对一个 skill 只能有一条
-- 待处理的申请——重复申请是同一件事的又一次表达，不是第二条记录。
CREATE TABLE skill_access_request (
  skill_id     TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  requester_id TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  wanted_role  TEXT NOT NULL,
  created_at   TEXT NOT NULL,
  PRIMARY KEY (skill_id, requester_id),
  CONSTRAINT skill_access_request_role
    CHECK (wanted_role IN ('invoke', 'browse', 'write', 'maintain', 'admin'))
);
```

- **它不进 `skill_grant`**（[ADR 0052](../../decisions/0052-a-request-is-not-a-grant.md)）。一条申请不授予任何东西，所以「谁能碰这个 skill」的每一条读路径都不需要认识这张表；批准那一刻才写 `skill_grant`，与今天的一次授权是同一条代码路径。
- **没有索引**，与 `skill_grant` 同一条理由的反面：那张表有「授给我的 skill」这条热路径，这张表**没有**「我申请了什么」那一屏。管理员那一向按 `skill_id` 查，主键已经覆盖。
- **`wanted_role` 存的是申请人说的那一档**，批准时可以给另一档——批准前两者都在那一行，才说得清「他申请的」与「批准的」不是一回事。批准之后申请行删除，`wanted_role` 只留在申请的审计里；`skill_grant` 只记批准的档。
- **随 skill 一起走**：`ON DELETE CASCADE`，而软删（`deleted_at`）不碰这张表——恢复的 skill 把申请一并带回来。

### V21 · `skill` 行带上列表要的事实

**为什么有这一组列。** 模块拆开之后，搜索与作者列表只能查 `skill`；它们今天从 `skill_version` 取的三类事实——当前版本的展示字段、当前版本的时间与草稿计数、最新草稿——必须在元数据这一侧有一份。这不是第二份真相：**版本与字节仍是内容的，指针归元数据**（[ADR 0053](../../decisions/0053-skill-metadata-is-its-own-module.md) §决定 3），这里只是「读列表时不必跨过去」的那一份投影，**由内容模块在提交 / 上线 / 丢弃时经接缝写回**。

```sql
ALTER TABLE skill ADD COLUMN when_to_use                  TEXT;
ALTER TABLE skill ADD COLUMN current_version_name         TEXT;
ALTER TABLE skill ADD COLUMN current_digest               TEXT;
ALTER TABLE skill ADD COLUMN current_version_submitted_at TEXT;
ALTER TABLE skill ADD COLUMN draft_count                  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE skill ADD COLUMN newest_draft_submitted_at    TEXT;
ALTER TABLE skill ADD COLUMN newest_draft_version         TEXT;
ALTER TABLE skill ADD COLUMN newest_draft_digest          TEXT;
ALTER TABLE skill ADD COLUMN newest_title                 TEXT;
ALTER TABLE skill ADD COLUMN newest_description           TEXT;

COMMENT ON COLUMN skill.current_version_submitted_at IS
  'RFC3339 UTC. submitted_at of the version current_version_id points to; NULL until something is published. Written by M12 recordPublish.';
COMMENT ON COLUMN skill.newest_draft_submitted_at IS
  'RFC3339 UTC. submitted_at of the newest draft; NULL when there is no draft. Recomputed by M12 recordSubmission / recordDiscard / recordPublish.';

-- Backfill once, from the existing content rows (the only read of skill_version allowed here):
-- the published version's name / digest into current_version_name / current_digest, its title /
-- description into skill.title / skill.description, its when_to_use into skill.when_to_use, and
-- its submitted_at into current_version_submitted_at; the newest draft fills draft_count /
-- newest_draft_* / newest_title / newest_description / newest_draft_submitted_at.
-- when_to_use takes COALESCE(frontmatter::jsonb #>> '{metadata,when_to_use}',
-- frontmatter::jsonb ->> 'when_to_use') so rows written before this migration keep their value.
```

- **`published` 不新增列**：`current_version_id IS NOT NULL` 就是它。搜索那条内连接因此可以去掉，换成显式谓词 `WHERE s.current_version_id IS NOT NULL`——否则一个只有草稿的 skill 会进搜索；「指针指向一个已上线版本」的完整性改由上线路径保证（写入方是同一条接缝），列表不再承担那个检查。
- **`when_to_use` 取 `frontmatter.metadata.when_to_use`**——设计件与 `design/README.md` 的键。今天搜索查询读的是顶层 `when_to_use`，那是错的；V21 的投影按 `metadata` 取，查询跟着改，测试夹具也改到 `metadata` 下。
- **回填是一次性的。** V21 从现有 `skill_version` 回填这些列——迁移里允许这一次读内容表，因为它是把已有数据搬进新列；之后列表的常规读路径不再碰版本表。`when_to_use` 的回填按 `COALESCE(metadata.when_to_use, 顶层 when_to_use)` 取：**已有的顶层值在这一步保住**，此后只写、只读 `metadata` 那一处。
- **维护点就是新的「漏一处就静默出错」**：`recordPublish` 写 `current_version_id / current_version_name / current_digest / current_version_submitted_at / when_to_use` 与 `title / description / frontmatter`，**并重算草稿面** `draft_count / newest_draft_submitted_at / newest_draft_* / newest_title / newest_description`——被上线的那一版离开了草稿集合；回滚到已上线版本时草稿面重算结果不变（`current_version_submitted_at` 随指针回到那一版的时间）。`recordSubmission` / `recordDiscard` 重算 `draft_count / newest_draft_submitted_at / newest_draft_* / newest_title / newest_description`。漏一处，列表就显示旧事实，而响应格式完全正常。
- **两种口径要分开**：`newest_*` / `newest_title` / `newest_description` / `newest_draft_submitted_at` 取最新**草稿**（`state = 'draft'`）；`current_version_submitted_at` 取**当前上线版本**的 `submitted_at`。列表的「更新于」与默认排序用 `COALESCE(current_version_submitted_at, newest_draft_submitted_at)`：有上线版本时是它的时间，纯草稿行退回最新草稿时间，两者都没有（全部丢弃）为 null。
- **响应里的 `updated_at` 是列表卡片的时间**（上面那个 `COALESCE`），**不是** `skill.updated_at`——后者由上线推动、记的是上线时刻，两者不是一回事。
- **列表的「更新于」不再跟着草稿走**：设计件里 pdf-tools 上线 @1.2.3（10/07）、另有一版草稿（10/16），卡片显示的是 **10/07**；所以提交草稿不改变卡片与默认顺序，草稿只在版本表里出现。纯草稿 / 全丢弃两态设计件没有画，口径已在 2026-10-18 补进 [`design/README.md`](../../../design/README.md) 末尾的「列表行的空态」一节：纯草稿写「未上线」+ 最新**草稿**提交时间（`updated_at`），全丢弃那一格留空（**不写**「每一版都被丢弃了」）。
- **上线的顺序**：M7 先把版本标成 published 并把这一版的投影交给 M12，M12 再写指针与投影；**内容不再写 `skill` 表**。两处写在同一用例事务里。
- **作者列表的标题规则照旧**：`current_version_id IS NOT NULL` 时取 `skill.title`（它是当前上线版本的投影）；否则取 `COALESCE(newest_title, skill.title)`。描述同理。

### 删除与恢复

**没有 DDL**：`deleted_at` 从 v1 起就在，本版给它一条写入路径（删除）与一条清除路径（恢复）。见 [ADR 0050](../../decisions/0050-deleting-a-skill-can-be-undone.md)。

## 接口

### 门槛表

**这张表是本版的契约**，也是[验收 #1](prd.md) 逐条要对的东西。「所有者」一列写的是**只有命名空间的所有者**能做到的事（不是 `created_by`；本版没有组织模型，两者今天总是同一个人）。

| 操作 | 端点 | 最低档 |
|---|---|---|
| 搜索 / 列表（只给 L1 三字段） | `GET /api/v1/skills` | `invoke`（公开的按公开档） |
| 读 L1（无清单） | `GET /api/v1/skills/{ns}/{name}/metadata` | `invoke` |
| 读详情（L1 + **完整文件清单**） | `GET /api/v1/skills/{ns}/{name}` | **`browse`** |
| 读正文 L2 | `GET …/body` | `invoke` |
| 读单个文件 L3 | `GET …/files/{relpath}` | `invoke` |
| 列版本 | `GET …/versions` | **`invoke`**（响应里只有 `published`） |
| 作者面：**列表**（我能以作者身份参与哪些 skill） | `GET /web/skills` | `write` |
| 作者面：**「信息」那一栏**（元数据，不含文件） | `GET /web/skills/{ns}/{name}` 的元数据形状 | **`invoke`** |
| 作者面：**「版本」那一栏**（只列 `published`，草稿不给） | `GET /web/skills/{ns}/{name}` 响应里的 `versions`（按档位过滤） | **`invoke`** |
| 作者面：详情 / 正文 / 单个文件 | `GET /web/skills/{ns}/{name}`、`…/body`、`…/files/{relpath}` | **`browse`** |
| 作者面：**草稿**（详情响应里 `draft` 的那些版本、以及草稿的正文与文件） | 同上那条详情的响应里 | `write` |
| 作者面：diff | `GET /web/skills/{ns}/{name}/diff` | `write` |
| 提交新版本 | `POST /api/v1/skills/{ns}/{name}/versions` | `write` |
| 丢弃草稿 | `POST /web/skills/{ns}/{name}/discard` | `maintain` ∨（`write` ∧ 提交人是我） |
| 上线 / 回滚 | `POST /web/skills/{ns}/{name}/publish` | `maintain` |
| 改描述 | — | **不在本版**（元数据那一版，[prd §不做 #8](prd.md)） |
| 改可见性 | `PATCH /web/skills/{ns}/{name}/visibility` | `admin` |
| 授权 / 撤销 / 看名单 | `…/grants`（两面同一套用例） | `admin` |
| **申请一档** | `POST /web/skills/{ns}/{name}/access-requests` | **`invoke`**（看得见它就能申请） |
| **撤回自己的申请** | `DELETE /web/skills/{ns}/{name}/access-requests/me` | 申请人本人 |
| **列待处理的申请** | `GET /web/skills/{ns}/{name}/access-requests` | `admin` |
| **批准 / 拒绝** | `POST …/access-requests/{handle}/approve` · `…/deny` | `admin` |
| **删除** | `POST /web/skills/{ns}/{name}/delete` | **所有者** |
| **恢复** | `POST /web/skills/{ns}/{name}/restore` | **所有者** |

**五条要解释的**：

1. **「调用」档够得到详情以外的一切，唯独够不到清单。** 详情端点因此要 `browse`——一个响应不能一半有权一半无权，而清单与 L1 在同一个响应里。够不到时是 **403**（不是 404）：存在性已经被确认过了，他刚用 `/metadata` 拿到了这一个 skill 的名字与描述（这是 [ADR 0034](../../decisions/0034-skill-level-sharing.md) §理由给 403 定下的成立条件，本版沿用）。**403 不带 `insufficient_scope` 挑战**——他的 scope 是对的，是**这一档**不够，同 [ADR 0034](../../decisions/0034-skill-level-sharing.md) §决定 7。
2. **`invoke` 档在搜索里照常出现。** 「调用」档看不到的是**文件清单**，不是这个 skill 本身；而公开档的 skill 必须被搜到（[ADR 0037](../../decisions/0037-visibility-has-two-states.md) §决定 2）。搜索给的 L1 三字段与清单是两样东西。
3. **改可见性是一条自己的路由，不是「改元数据」那个 `PATCH` 的一个字段。** 它的门槛是 `admin`，而这个 `PATCH` 上没有别的字段——元数据那一整块要等元数据那一版（[prd §不做 #8](prd.md)）。把一个字段的门槛写成「提交里出现它就判它」会让 403 说不清缺的是哪一档。设计件把它放在**设置 › 常规**里的一块（可见性），与协作者名单分开——可见性讲「陌生人拿到哪一档、能看到什么、能做什么」，授权讲「某个人拿到哪一档」。
4. **作者面的读按档位给内容。** 详情 / 正文 / 文件降到 `browse`——**但它必须按档位把草稿滤掉**，否则 `浏览` 档的人会看到还没上线的内容的元数据。这一条是 [ADR 0051](../../decisions/0051-the-author-plane-serves-by-tier.md) 的全部内容，也是**本版第二处「漏一处就静默出错」**：滤掉与没滤掉的响应格式完全一样。
5. **`invoke` 也进得了作者面，拿得到「信息」与「版本」两栏。** 那一栏是**面向使用者**的子集：描述、`metadata.when_to_use`、`compatibility`、版本名（`allowed-tools` 不给——见 `design/README.md` §allowed-tools 不在「信息」页）；完整的 `frontmatter`（含许可）与版本的 `digest` 在**界面上**只给 `admin` 以上的「设置 › 详细信息」。所以按档位给的不只是「有没有」，还有「哪几个字段」。 [ADR 0051](../../decisions/0051-the-author-plane-serves-by-tier.md) §决定 2a（2026-10-17 补）：那一屏对他是**「信息」与「版本」两栏**的页面（版本表只含 `published`），所以同一条详情路由要能按档位给出**不带文件清单、不带草稿**的响应——而不是让他先 403 再靠别的端点拼。这与第 1 条那条 `/metadata` 是**同一层数据**，只是走会话面：「信息」栏是那一层里面向使用者的**子集**（[ADR 0051](../../decisions/0051-the-author-plane-serves-by-tier.md) §决定 2a 的原话），`/metadata` 本身**这一版不改**——它照旧给完整的 `frontmatter`（含 `allowed-tools` 与许可）与 `digest`。两边共有的那几项必须一致，否则界面与 agent 看到的不是同一个 skill。

### 新增与改动的路由

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `PATCH` | `/web/skills/{ns}/{name}/visibility` | 体 `{"visibility": "private"}` 或 `{"visibility": "public", "public_rung": "browse"}` | 会话 + CSRF + `admin` |
| `POST` | `/web/skills/{ns}/{name}/access-requests` | 体 `{"rung": "write"}`；一人一条，重复提交是覆盖；**`rung` 必须高于申请人当前的实际档位**（公开与授权取高；所有者与 `管理` 都没有可申请的档），否则 `400 invalid_request`；**校验失败不覆盖已有申请**（先校验、后写入） | 会话 + CSRF + **`invoke`** |
| `DELETE` | `/web/skills/{ns}/{name}/access-requests/me` | 撤回自己的申请 | 会话 + CSRF + 本人 |
| `GET` | `/web/skills/{ns}/{name}/access-requests` | 待处理的申请（管理员那一屏用） | 会话 + `admin` |
| `POST` | `/web/skills/{ns}/{name}/access-requests/{handle}/approve` | 体 `{"rung": "write"}`；按这一档授权并删掉申请 | 会话 + CSRF + `admin` |
| `POST` | `/web/skills/{ns}/{name}/access-requests/{handle}/deny` | 删掉申请，不授权 | 会话 + CSRF + `admin` |
| `POST` | `/web/skills/{ns}/{name}/delete` | 软删 | 会话 + CSRF + **所有者** |
| `POST` | `/web/skills/{ns}/{name}/restore` | 清 `deleted_at` | 会话 + CSRF + **所有者** |
| `GET` | `/web/skills?state=live\|deleted` | 作者面列表多一个筛选；默认 `live`；`deleted` **只列我拥有的已删的** | 会话 + `write`（`deleted` 时是所有者） |

**成功码与响应体**（上表只写方法与门槛，这里是另一半）：`PATCH …/visibility`、`POST …/delete`、`POST …/restore`、`DELETE …/access-requests/me`、`POST …/{handle}/approve|deny` 一律 **204**（无正文）；`POST …/access-requests` 是 **201**（无正文）；`GET …/access-requests` 是 **200**，体为 `{"requests": [{"handle", "wanted_role", "created_at"}], "total"}`（按申请时间升序）；`GET …/grants?q=&p=` 是 **200**，体为 `{"grants": [{"handle", "role", "created_at"}], "total", "page", "page_size"}`。批准 / 拒绝与撤销授权这三条带路径参数的端点都叫 `{handle}`（用户名），不叫 `{account}`——它就是页面上那一个人读到的那个名字。

**删除与恢复用 `POST …/delete` 而不是 `DELETE …/{ns}/{name}`。** 两个理由：它是**软删且可恢复**的动作，不是一个资源删除；而 `DELETE /api/v1/skills/{ns}/{name}` 正是 [ADR 0046](../../decisions/0046-deleting-a-skill-happens-in-the-browser.md) 撤掉的那条形状，在网页面上用同一个形状会让它看起来像又回来了。

**恢复那条路由自己解析已删的行。** 它是全系统**唯一**一条**按地址**解析已删行、并把 `deleted_at` 写回去的路径（作者面的「已删除」那一份也读已删行，但它只列我拥有的、不走地址解析）——不能走 `permitted()`（那个谓词里有 `AND s.deleted_at IS NULL`，复用会让恢复永远 404），也不能复用得太宽（那会让已删的 skill 从别的路径漏出去）。见 [ADR 0050](../../decisions/0050-deleting-a-skill-can-be-undone.md) §后果。

**「已删除」那一份的每一行带删除时间。** 设计件那一行写「删除于 …」——「更新于」对一个已经不存在的页面不成立（[design/README.md](../../../design/README.md) §我的 skill 有两份列表）。所以 `authorPage` 要选出 `s.deleted_at`，列表响应里跟着返回（`live` 那一份为 null）；恢复清掉 `deleted_at` 之后它自然消失。

**名单是分页的。** `GET …/grants` 收 `q`（按用户名）与 `p`（从 1 起），一页 10 条，响应里带总数。设计件把这两个都写进地址（`&q=` / `&p=`），所以它们必须是这条路由的参数，而不是前端把整份名单拉下来再筛。

**申请那一组不是授权的别名。** 同一条路径上两个端点门槛不同，因为它们做的是两件事：`POST …/access-requests` 是「我想要」，门槛 `invoke`；`…/approve` 是「我给了」，门槛 `admin`。**私有 skill 对申请人先是 404**（`permitted()` 不认他），所以「申请一个看不见的东西」在结构上不存在，不需要额外的判断——这正是 [ADR 0052](../../decisions/0052-a-request-is-not-a-grant.md) §决定 2 要的那个形状。批准那一刻写 `skill_grant`，与今天一次授权是同一条代码路径，所以梯子、审计、按档位滤内容都不认识「申请」这个字。

### 错误码

**不加新的错误码。** 复用已有的四个：

| 情形 | 答案 |
|---|---|
| 够不到这个 skill（不存在 / 不是你的 / 没被授 / 私有的 / 已删的） | **一律 404**，同一个答案——这条从 [ADR 0012](../../decisions/0012-addressing-and-version-pinning.md) 起没变过，删除之后也是它 |
| 够得到，但**这一档不够** | **403**，不带 scope 挑战；消息说清缺哪一档 |
| 未认证 / scope 不够 | 401 / 403（`insufficient_scope`，由 `SecurityConfig` 那两条 authority 规则给） |
| 提交到**已软删**的名字 | `400 invalid_request`（今天已是如此，本版不改） |
| 申请一个**不高于自己当前实际档位**的档（所有者与 `管理` 都没有可申请的档） | `400 invalid_request` |
| 旧 `role` 词（`viewer` / `editor`） | `400 invalid_request`（[§一处要连带改的契约](#一处要连带改的契约)） |
| 批准 / 拒绝一条**已经不存在**的申请 | `404`（与「够不到这个 skill」同一个答案） |
| `DELETE /api/v1/skills/{ns}/{name}` | **405 + `Allow`**（不是 404）：v4 撤掉的是那条 `DELETE`，同一个路径仍以 `GET` 服务，所以框架答 405。这是框架层的答案，不新增业务错误码；`SkillSubmitIT` 钉着它 |

### 一处要连带改的契约

`SkillGrantRequest` 的 `role` 取值从 `viewer` / `editor` 换成五档。**旧的词要被明确拒绝**（`400 invalid_request`），不能静默映射到某一档——一个回「已授权」而实际授了别的档的请求，比报错坏得多。CLI 的 `skill share --role` 同理。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P0 服务端** | **四个**迁移（V18–V21，含投影列）；**拆出 M12 元数据模块**（`skill` / `skill_grant` / `skill_access_request` 连同仓储与服务搬过去，内容模块改用接缝）；列表查询去掉版本联表；`GrantRole` 有序化；`permitted()` 返回「实际档位」；各操作换成 `atLeast(...)`；公开档进两个读谓词；**三条**新路由 + 列表筛选 + **申请那一组五条**；审计七个动作（删除——动作名已有、本版首次有路由；恢复、可见性、申请、撤回、批准、拒绝）。**重复提交申请也算一次**：每次被接受的 `POST …/access-requests` 都写一条（覆盖不吞掉前一条的历史），这与「一人一条申请」不矛盾——表里一行，审计是流水 | [验收 #1–#6、#9、#10](prd.md) 的**服务端一半**（这些验收都还有一个界面或 CLI 的那一半，见下面两期），以及 [#7、#8](prd.md) 的服务端一半 |
| **P1 网页** | **那一屏长成设计件的形状**（见下）；协作者换成梯子（五档可授 + 所有者一行）+ **待处理的申请与批准/拒绝**；可见性那一块（设置 › 常规）接上新接口；删除入口 + 先问后果；「已删除」那一份列表 + 恢复；「不是我的」那一份；**信息页底部的「申请」块与撤回** | [验收 #1、#6–#10](prd.md) 的界面一半；#2 的界面一半（所有者只读行、档位菜单不出所有者）、#4 |
| **P2 CLI** | `invoke` / `read` 换一条取数路径（见下）；`files` 归「浏览」档；`skill share --role` 换词表并拒旧词；`auth status <地址>` 的输出；`--help` 里的词 | [验收 #1](prd.md) 的 CLI 一半；#2 的 CLI 一半（五档与旧词拒绝） |

**顺序是硬约束**：契约先成立，界面才有东西可点。每期做完各走一遍 SOP 阶段 6–7 的对应部分，不把三期的审计堆到最后。

**每期明确不做**：P0 不做任何界面；P1 不做 CLI；P2 不加新能力——它只把**取数路径与词**跟上来。

#### P1 的网页：不是「接两个接口」，是那一屏长成设计件的形状

产品今天那一屏比设计件**薄得多**，所以 P1 的第一件事是把它补成设计件的样子，而不是贴两个新接口上去：

| 设计件有 | 产品今天 | P1 |
|---|---|---|
| 分区：文件 / **版本** / **设置** | 只有「文件」+ 内嵌的一块协作者面板 | 补出分区；**设置**是全页唯一有第二级的一项 |
| 设置 › **常规 / 协作者 / 详细信息** | 协作者是内嵌面板；**可见性在整个产品里没有任何界面能改** | 建成二级菜单；可见性并进「常规」，删除是「常规」页底的危险区域（只有所有者看得见） |
| 协作者是一条**梯子，六行**（五档可授；名单第一行是**所有人的所有者**，只读、没有撤销；档位菜单只列比公开档高的，**另加该行当前那一档**——公开之前授出去的「浏览」仍有选中值；名单里只列单独授给个人的那些） | 两个 `<option>`：只读 / 可以提新版本 | 换成梯子 |
| 协作者页末尾有**待处理的申请**（谁、申请哪一档、批准 / 拒绝；只在有申请时出现），二级菜单右肩挂条数 | 没有——今天开口只能在产品之外，且不留痕迹 | 补出这一块与那个数字；批准 = 按最终那一档授权（[ADR 0052](../../decisions/0052-a-request-is-not-a-grant.md)） |
| 信息页底部有**「申请」块**：上面一句「当前档位为「X」…」说清他现在能做什么、还缺什么；还有比自己高的档就能申请（当前档为调用 / 浏览 / 写入 / 维护之一时画；`管理` 与所有者没有更高的档，不画），发出后换成「等待处理」胶囊 + 谁审批 + 撤回 | 没有 | 补出这一块；「管理」与所有者不画 |
| 版本那一栏是**表格**，行末是**这一行能做的事**（查看差异 / 上线 / 丢弃），另有一行**草稿** | 一个下拉 + 横幅里的上线、丢弃两个按钮 | 换成表格；**三个动作从横幅挪到行末**（动作本身不重做） |
| 列表的**搜索、两种排序（最近更新 / 名称）、分页**（一页 10 条，装得下就不出分页条；`q` / `sort` / `p` 进地址） | 只有前端搜索；没有排序与分页 | 排序与分页都在前端做（一次取回，`authorPage` 上限 500；超过 500 的 skill 这一版看不到——既有上限，另行处理）；**本版不交付调用次数与「调用最多」**（[prd §指标](prd.md)；设计件把这一列标为前瞻字段，等用量信号落地再补）；`sort=` 只认缺省（最近更新）与 `name`，不产生 `calls` |
| 纯草稿 / 全丢弃的行（产品此前写「未上线」与「每一版都被丢弃了」） | 设计件没有这两态 | 口径已补进 `design/README.md` 末尾（2026-10-18）：纯草稿「未上线」+ 最新草稿时间；全丢弃那一格留空 |
| 列表一栏**不数「待上线」**（草稿只在版本表里出现） | 列表行渲染「N 个待上线 →」 | 移除这一条 |
| 我的 skill 有**两份列表**（「在用 / 已删除」），共用一个页头、一个搜索、一个排序；标题旁是「N 个已删除」 | 只有一份；已删除没有入口 | 补出两个 tab 与数量；前端把地址里的 `show=deleted` 映射到 `GET /web/skills?state=deleted`；两份共用搜索 / 排序 |
| 已删除行：名字是**纯文字**（地址已打不开）、「已删除」胶囊、不显示 live 胶囊与调用次数、第二行是「删除于 …」、行末是「恢复」 | 没有 | 按设计件画；**不要复用 live 行的链接与「更新于」** |

**版本表的行末按档位**（设计件「管理动作」）：查看差异只在草稿行（`write`）；上线在除线上外、且不是 `discarded` 的每一行（`maintain`）——**已丢弃的版本服务端会拒绝上线，所以不画**；丢弃只在草稿行（`maintain` ∨（`write` ∧ 提交人是我））；线上那一版行末为空；`浏览` 看不到草稿行，`写入` 看得到草稿但没有上线。**`discarded` 行按 `design/README.md` 末尾那一节画**：`chip is-plain`「已丢弃」，不计入版本数、不进版本下拉、没有动作。**例外**：地址钉住一个草稿或已丢弃版本时，它在下拉里以**禁用项**出现——不是可选项，只是让控件别对屏幕上的内容说谎（同一节）。

**分区与二级菜单的形状设计件已经画好了**——P1 是**实现它**，不是设计它。原先要新画的三处与要补画的那一处**2026-10-17 都已画完**，逐条落在哪见 [§设计稿：已经动过的地方](#设计稿已经动过的地方2026-10-17)。

#### P2 的 CLI：`invoke` 与 `read` 不能再从清单进

**今天 `invoke` 与 `read` 都先打详情接口（L1 + 完整清单），再照抄清单里的 `uri` 去取字节**（`main.go` 的 `invoke`、`read` 两处都是 `Detail` → `Fetch`）。而详情在这一版要 `浏览` 档——于是**「调用」档的人在 CLI 上连 `invoke` 都跑不了**，而设计件写得明白：「`调用`：能调用它，也能按路径点名取单个文件……但**列不出目录**」——CLI 的取字节不能走需要 `browse` 的详情，所以 `invoke` / `read` 要换一条路。CLI 是唯一普适的客户端（[ADR 0041](../../decisions/0041-third-parties-reach-us-through-the-cli.md)），所以这一档会在真实使用里不存在。

**改法**——`invoke` 要的其实只是「解析出是哪一版」，而那件事 `/metadata` 就够（L1 + 版本名与 digest，**不含清单**）：

| 命令 | 今天 | P2 |
|---|---|---|
| `skill invoke <地址>` | 详情（清单）→ 照抄 `resources.body` 的 uri | **`/metadata`** 钉出版本 → 自己拼 `/body` + `X-Skill-Version` 头 |
| `skill read <地址> <路径>` | 详情（清单）→ 在清单里找到那一项 → 照抄它的 uri | **自己拼 `/files/{路径}`** + `X-Skill-Version` 头 |
| `skill files <地址>` | 详情（清单） | **不变**——它就是列目录，归 `浏览` 档 |

**钉仍然由本机携带**（[ADR 0035](../../decisions/0035-the-pin-lives-on-the-client-machine.md)），所以「地址 + 头」两样合起来唯一决定字节，与照抄 uri 等价。两条要跟着改的：**`read` 路径打错时的指路**——今天指 `files`，而对「调用」档的人来说 `files` 是 403，那句话要按档位分开说；以及**这一改动要有自己的测试**（见 [`test-plan.md`](test-plan.md) T1 的 CLI 那一条）。

**`files` 归「浏览」档是对的**，不用改：它列出的就是目录，而梯子明说「`调用`：看不到里面有哪些文件」。

### 设计稿：已经动过的地方（2026-10-17）

这一节原先是「P1 开工前要先动它们」的清单。**2026-10-17 全部动完了**，逐条记下落在哪；**P1 已照它搬**（验收未执行）。原始清单里那些「为什么这一处要画」的理由留在这里，因为它们同时是验收这一处画对了没有的判据。

| # | 当初要动什么 | 现在落在哪 |
|---|---|---|
| 1 | **删除入口 + 先问后果** | 设置 › 常规 › 页底那块「危险操作」（不是导航项，参照 GitHub 的 Danger Zone 与 GitLab 的 Advanced）。确认用的是密钥页、登录设备同一个 `askBefore`（就地替换，不是弹窗），问题说的是后果（「删除后，该名称将无法用于创建新 skill，当前地址立即失效。」），不是「确定吗」。确认之后**跳到「已删除」那一份**（`?page=skills&show=deleted`）——那一步才看得出它真的发生了 |
| 2 | **「已删除」与恢复** | 我的 skill › **已删除**那一份列表，行末是「恢复」。**没有照原计划做成「活跃 / 已撤销」那样的筛选**——理由写在 `design/README.md` 新的一节「我的 skill 有两份列表」：那一份里每一行能做的只有恢复，做成筛选项等于说两份是同一个列表的两种看法 |
| 3 | **「不是我的」那一份** | 演示条最右边的「以谁的身份看」，六档各一份。要收起来的四件逐件核过：设置那一栏、指进可见性的徽章、草稿行、上线 / 丢弃 / 查看差异。**规则是「够不到的东西不画，不是画成灰的」**（GitLab 的 `return unless can?(...)`） |
| 4 | **版本表的草稿态与动作格** | 版本表多一行「草稿」，行末一格放三个动作：查看差异 / 上线 / 丢弃。**历史行上也有「上线」**——把一个旧版本重新上线是同一个动作、同一条路由，所以没有第二个名字（「回滚」只是文档里的说法） |

**四条回写也都做完了：**

- **梯子那两格本版不交付的能力**（管理的「改名」、维护的「改描述」）在 `design/README.md` 的梯子表下面标注了，不是删除那两格——那两格画的仍是完整形态。
- **「没有这个 skill」那一态**的那句话改成了三种情形都成立的说法（「这个地址下没有你能打开的 skill，也可能它已经被删除了」），并**多了一条出路「查看已删除的 skill」**：删掉一个之后，它自己的地址给出**指向那一份列表的**一条出路，恢复动作仍然在列表里做（[ADR 0050](../../decisions/0050-deleting-a-skill-can-be-undone.md) §决定 5）。这是原先那条「要么让链接指向已删除那一份列表，要么单开一态」选了前者。
- **协作者行的「撤回 / 撤销」**：源稿改成「撤销」，与 `design/voice-spec.md` §三 一致（与结束密钥同一个词）。
- **`design/voice-spec.md` §三 补了五行**：档位 / 所有者 / 撤销 / 恢复 / 已删除。

**已经有的、没重画的**：六行（五档可授）的协作者页、可见性的三选一——设计件里本来就画好了，本版是**实现它**。

**另有一处顺带定下的显示规则**：标题下那一行是 `title`，**写了才画**（没写时整行不占高度）。判据是 `title !== name`，不是判空——因为服务端在缺席时把它填成了 `name`（`SkillUploadValidator`）。`title` 本身不属于这一版的范围（元数据那一版处理它的地位），这一版只落它「怎么显示」。

**上线 / 丢弃 / 查看差异这三个动作本身不重新设计**——产品网页上已经在发这些请求（`PendingBanner.vue:80-85`）。这一版改的是**它们在页面上的位置**：从版本表上方那条横幅，挪到版本自己那一行。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 一个人被**取消**授权（或他的档位被降下来）之后，要不要告诉他？ | **不告诉。** 今天撤销共享是删行、不通知（[ADR 0034](../../decisions/0034-skill-level-sharing.md) §决定 9：历史在审计里），本版不改这个形状 | 无通知机制可接（v1 没有任何外发动作）；真要做是一条独立的功能 |
| Q2 | 被授 `管理` 的人**能不能把别人提到 `管理`**？ | **能。** 梯子说 `管理` 档能授权，而「能授哪些档」在设计件里的规则是「只列比公开那一档高的」——`管理` 是最高可授档，所以它能授出 `管理` | 已按此写进 [ADR 0047](../../decisions/0047-permissions-are-a-ladder.md) §决定 5；若用户要收紧，改的是那一句 |
| Q3 | `namespace_member.role` 的词表怎么办？它今天声明 `owner \| editor \| viewer`，而 `skill_grant` 不再用后两个词 | **本版不动。** [ADR 0034](../../decisions/0034-skill-level-sharing.md) §决定 3 借那两个词的理由（「同一个概念两套词，是把将来的合并变成一次迁移」）**随本版失效**——两处词表现在明确分开了。将来做成员体系时要么统一、要么明确是两套 | 留给成员体系那一版；本版只在 [ADR 0047](../../decisions/0047-permissions-are-a-ladder.md) 记下这条代价 |

**不写「待定」**——上面每一条都有倾向与回答方式。

**一条已经关掉的不在这里**：「`public` 是『任何持凭据的账号』还是『任何人，含匿名的』」——2026-10-17 定为**前者**，本版不引入匿名读面，见 [`prd.md`](prd.md) §不做 #7。它当初被留在这里是因为它会改变「公开」这个词在产品上的含义；现在它是范围里的一条否定决定，不是开放问题。

## 附录

### 附录 A · 与现有代码的关系

**行号以本版锁定时的提交 `51481bb` 为准**——实现已经在工作树里推进，行号会随之移动；核对这些断言时认符号，不认偏移。

| 模块 / 类 | 处置 |
|---|---|
| **模块切分** | **新增 M12 `modules/skill`（元数据）**，**M7 `modules/version` 收窄为内容**。`skill` / `skill_grant` 及它们的仓储与服务搬去 M12；`skill_access_request` 及其仓储 / 服务在 M12 新建；`ModuleMap` 与 `TableOwnershipTest` 跟上 |
| `modules/version/GrantRole` | **搬去 M12 并改写**：从一对 String 常量变成有序的五档（[ADR 0047](../../decisions/0047-permissions-are-a-ladder.md) §后果） |
| `internal/SkillRepository.permitted()` | **搬去 M12**：返回的 `Permitted` 里那个可空的**被授角色**字段变成「实际档位」；谓词多一个公开档的分支 |
| `Permitted` 的 `mayWrite()` / `mayAdminister()` | **作废**，换成 `atLeast(档位)` |
| `internal/SkillCatalogRepository` | **搬去 M12**：`page` 与 `authorPage` **只查 `skill`**（V21 投影），不再联 `skill_version`；`page` 认公开档；`authorPage` 认被授 `write` 及以上并加 `state`（`state = deleted` 只列我拥有的），并选出 `s.deleted_at`（已删除那一份要显示删除时间）；`authorPage` 里写死的 `g.role = 'editor'` 改成「被授 `write` 及以上」——换成精确的 `'write'` 会把 `维护` / `管理` 共享来的 skill 静默丢掉 |
| `SkillCatalogService` | **搬去 M12**：仍是 `page` / `authorPage` 两条接缝；`authorPage` 多一个 `state` 参数并选出 `deleted_at`（`state = deleted` 时只列我拥有的——恢复是所有者独有的） |
| `SkillSharingService` | **搬去 M12**：门槛 `atLeast(admin)`；新增 `setVisibility` |
| `SkillAccessRequestRepository` / `SkillAccessService` | **新增**（M12）：申请、撤回、列申请、批准 / 拒绝 |
| `SkillVersionService` | **留 M7**：不再自己查、也不再自己写 `skill` / `skill_grant`——建行由用例先经 M12 的 `findOrCreate`，指针改由 `recordPublish` 写；`softDelete` 的 `skill.deleted_at` 写入搬去 M12，`restore` 新增在 M12；**`BlobGc` 留在 M7**，软删后的 sweep 由用例在同一事务里调 M7 执行（M12 不依赖 M7）；权限判定由用例层从 M12 取来后传入；`publishVersion` 门槛 `atLeast(maintain)`；丢弃合成 `maintain ∨ (write ∧ 提交人是我)`；作者面读按传入的档位滤草稿 |
| `VersionRepository` / `BlobGc` | **留 M7**，不动 |
| `SkillSnapshot` | **改**：不再带 `visibility`——可见性属于元数据，由响应组装时从 `Access` 取 |
| `SkillDistributionService`（M9） | **改**：接收 `Access`，不再经过内容的权限 SQL；详情 / 正文 / 文件的档位判定由用例层先问 M12 |
| 旧词的**运行时字符串**（`ShareSkillUseCase.java:56` 的 `must_be_viewer_or_editor`、`SkillVersionService.java:90` / `:206` / `:305` 与 `SkillSharingService.java:95` 的 403 消息） | **改**：换成梯子的说法。它们会随 400 / 403 响应体一起到客户端，属于「旧词不再出现在那条接口里」；漏了它们，人会在报错里读到已经失效的档位名。改的时候连带改钉住这些字符串的断言 |
| `usecase/SoftDeleteSkillUseCase` | **保留**——它一直在那儿等这条路由（[ADR 0046](../../decisions/0046-deleting-a-skill-happens-in-the-browser.md)），改调 M12 |
| `usecase/RestoreSkillUseCase` | **新增**（M12） |
| `usecase/UpdateSkillVisibilityUseCase` | **新增**（M12；改描述那个属于元数据那一版） |
| `usecase/RequestSkillAccessUseCase` · `WithdrawSkillAccessRequestUseCase` · `ListSkillAccessRequestsUseCase` · `DecideSkillAccessRequestUseCase` | **新增**（M12） |
| `api/WebSkillRoutes` / `WebSkillController` | **改**：三条新路由（可见性、删除、恢复）+ 列表筛选 + 申请那一组五条 |
| `api/AuthoredSkillResponse` | **改**：`version` 与 `resources` 可为 null（`resources` 在 `invoke` 为 null——没有文件与正文地址；`version` 只在 `invoke` / `browse` 且一版未上线时为 null。`write` 及以上没有已上线版本时会回退选中最新未丢弃版本，全被丢弃时回退最新一行）；带上 `visibility` 与 `public_rung`（设置 › 常规的三选一回读）；带上 `rung`（调用者自己的档位：`owner` 或五档之一），页面据此决定画哪些分区——设计稿的规则是「够不到的东西不画」，而不知道自己是哪一档就答不了这个问题；带上 `pending_rung`（调用者自己那条待处理申请要的档位，没有时为 null）——信息页的「等待处理」要从服务端来，页面记住自己点过什么是撑不过一次刷新的；`versions[]` 与 `version` 各带上 `title`（版本自己的标题，版本表那一行叫「说明」）与 `submitted_by`（提交人的**用户名**，服务端批量解析；行上要写「谁交的」，而「写入」只能丢自己的草稿） |
| `api/AuthoredSkillsResponse` | **改**：列表行加 `deleted_at`（已删除那一份显示「删除于 …」；`live` 为 null）；`latest_submitted_at` 换成 `updated_at`（`COALESCE(current_version_submitted_at, newest_draft_submitted_at)`） |
| `SkillCatalogService.AuthorRow` · `usecase/model/AuthoredSkillSummary` | **改**：随 `deleted_at` 一起带到响应层，不要在中间层丢掉 |
| `SkillMetadata` | **不动**（描述覆盖是 [ADR 0049](../../decisions/0049-an-edited-description-outranks-the-version.md)，归元数据那一版） |
| `modules/namespace/**` | **不动**（本版不做成员体系） |
| `security` / `Scopes` / `api_key` | **不动**——scope 词表不变，理由见 [prd §不做 #4](prd.md) |

**网页（`skillmaster-web`）**

| 位置 | 处置 |
|---|---|
| `components/SkillCollaborators.vue` + `components/RungMenu.vue`（**新写**；旧的 `SharingPanel.vue` 不再接路由） | 两档 → 五档可授（名单第一行是所有者，共六行）的梯子（选项、默认值、只列比公开档高的**且另加该行当前那一档**、档位是自绘的档位菜单） |
| `pages/SkillPage.vue` | **改**：补出「版本」「设置」两个分区；设置做二级菜单（常规 / 协作者 / 详细信息）；删除入口是「常规」页底的危险区域 + 先问后果 |
| 可见性那一块（设置 › 常规） | **新增**（产品今天**没有任何界面**能改 `visibility`——它只是 `types.ts` 里的一个只读字段） |
| `pages/SkillsPage.vue` | **改**：「已删除」那一份列表 + 恢复（行内显示「删除于 …」）；列表补出设计件的排序 / 分页（前端做，见 P1 表）；**移除列表行的「N 个待上线」**——设计件明确列表不数草稿 |
| 那一屏的「不是我的」那一份 | **新增**（[ADR 0051](../../decisions/0051-the-author-plane-serves-by-tier.md)） |
| `api/types.ts` · `api/skills.ts` | **改**：`role: 'viewer' \| 'editor'` 换成五档 `Rung`（`Rung = 'invoke'|'browse'|'write'|'maintain'|'admin'`；调用者自己的档位叫 `Standing = Rung \| 'owner'`，因为所有者是关系、不在梯子上）；`AuthoredSkillSummary` 加 `deleted_at: string \| null`，`latest_submitted_at` 换成 `updated_at: string \| null`；详情类型加 `public_rung: 'invoke' \| 'browse' \| null`、`rung: Standing`、`pending_rung: Rung \| null`，`version: AuthoredVersion \| null`，版本行加 `title` 与 `submitted_by: string \| null`；客户端加删除/恢复、可见性、申请列表/申请/撤回/批准/拒绝，`listSkills(state)`、`listGrants(q, p)` |
| `components/SkillHeading.vue` · `pages/SkillsPage.vue` 的 `title` | **不动**——那是元数据那一版的事（[prd §不做 #8](prd.md)） |

**CLI（`skillmaster-cli`）**

| 位置 | 处置 |
|---|---|
| `internal/api/client.go` 的 `RoleViewer` / `RoleEditor` | **改**：五档 |
| `cmd/skillmaster/main.go` 的 `invoke` / `read` | **改**：不再先打详情（见 §分期 P2） |
| `cmd/skillmaster/main.go` 的 `files` | **不动**——它就是列目录，归 `浏览` 档 |
| `cmd/skillmaster/main.go:498` —— **`auth status <地址>`** 的那句输出（`reportSkillAccess`，由 `:452` 调） | **改**：「拿到 editor 共享」那句换词表 |
| `--role viewer\|editor` 的解析与默认值（`cmd/skillmaster/main.go:1172`、`:1198-1199`）、输出用语（`:1219-1229` 的 `roleLine`）、usage（`:116` 与 `parseShare` 自己的 `:1167`、全局 `usage()` 的 `:2375`）与 help（`:2404`） | **改**：五档，并对旧词报错 |
| `internal/api/client.go` 的 `Detail.Title`（`:562`） | **不动**——元数据那一版。**注意搜索卡片（`Card`，`:521`）里没有 `Title`**，所以 `list` / `search` 的输出根本碰不到它 |

### 附录 B · 来源与出处

- **一手**：设计件本身（`design/demos/dir3-hara-console.html` 的 `LADDER` 与可见性三选一，及其规格 `design/README.md:581-596`）、代码（逐处行号见 [ADR 0047](../../decisions/0047-permissions-are-a-ladder.md)、[0048](../../decisions/0048-visibility-hands-out-a-rung.md)、[0050](../../decisions/0050-deleting-a-skill-can-be-undone.md)–[0053](../../decisions/0053-skill-metadata-is-its-own-module.md) 与本文各节）。
- **二手（只作参照词来源，不作依据）**：`design/README.md:596` 记的 GitHub 官方简中 `Write` / `Maintain` / `Admin` 三档，以及 `get` / `list` 在 S3 / K8s RBAC / NTFS 里的同一条切分。**依据仍然是设计件**——这几个词之所以被采用，是因为设计件用了它们，不是因为参照产品用了。

### 附录 C · 已测量的数据

本版**没有需要测量的数字**——它不引入性能敏感的路径。唯一要量的是迁移的规模，而它在实现时才知道：

| 指标 | **口径** | 数值 |
|---|---|---|
| `skill_grant` 的迁移行数 | 本机 dev 库与测试库里 `role IN ('viewer','editor')` 的行数 | **实现时量**，写进提交信息 |
