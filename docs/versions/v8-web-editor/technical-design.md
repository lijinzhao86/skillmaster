# v8-web-editor · 技术设计

> **最后更新**：2026-10-20
> **所属版本**：[`README.md`](README.md)

<!--
这一版是「新增一类用户能做的事」：走全流程。接口与数据模型以本文为准；
代码与本文不一致时，先回写本文，再改代码（dev-sop §一）。
ADR 不复述：理由在 docs/decisions/，本文只链接。
-->

## 架构

**一句话**：浏览器面新增一条「草稿编辑」的路——读仍走现有的 `/web/skills/**`，写走两个新的 `/web` 路由，落到 v7 已有的写用例与规则上；「新版本」在第一次内容改动前只是一张页面里的底稿，第一次内容改动时与建行在同一个事务里完成。

```
VersionEditorPage.vue
  ├─ 读  GET /web/skills/{ns}/{name}@<pin>          （已有，WebSkillController.detail）
  │     GET /web/skills/{ns}/{name}@<pin>/files/{*} （已有，WebSkillController.file）
  └─ 写  POST /web/skills/{ns}/{name}/versions           （新：从底稿建草稿 + 第一次改动）
         POST /web/skills/{ns}/{name}/versions/{v}/edits （新：一次改动）
                    ↓
         WebDraftController（会话 + CSRF）
                    ↓
         CreateDraftFromVersionUseCase（新） / WriteDraftFileUseCase、DeleteDraftFileUseCase、
         MoveDraftFileUseCase（v7 已有）
                    ↓
         DraftFileWrite（共享的路径、基线、上限、审计）
         SkillVersionService（内容、manifest、digest、唯一约束）
```

**为什么写入口落在 `/web` 而不是复用 `/api/v1/author`。** 两条平面的鉴权不同：作者面用带 scope 的 API key，浏览器面用会话 + CSRF（[ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md)、v1 §4.1）。编辑页手里只有会话，没有 key；把 key 塞进浏览器也不是一个选项。两个适配器，同一组用例——这是 v6 起就有的分法，不是这一版新造的。

**为什么第一次内容改动才建行。** 直接复制线上版本会得到与线上相同的 `digest`，撞上 `UNIQUE (skill_id, digest)`，而 [ADR 0064](../../decisions/0064-a-draft-may-change-a-published-version-may-not.md) 明确拒绝「同一 skill 的两行拥有同一份内容」。本版不改这条约束（它同时保证发布幂等与摘要寻址），于是把「新版本」做成一张尚未落库的底稿：

- 页面从底稿版本读到文件清单与 `digest`，在客户端保存「底稿 + 缓冲」；
- 第一次内容改动把 **一次改动** 与 **建行** 放进同一个请求、同一个事务：服务端读底稿的 manifest，在内存里应用改动，校验上限与 frontmatter，算出新 `digest`，确认它不等于任何已有行，再插入 `skill_version` 与 `version_file`；
- 因此结果必然与底稿不同；没有内容改动时不会发出建行请求（保存按钮不可用，空文件夹也不触发建行——见下），也就不会出现「内容与底稿相同」的建行请求。

**读路径只多一个 `?base=none` 参数，其余零改动。** 编辑页读的是 `AuthoredSkillResponse` 已有的 `version`（`digest`）、`files`（`relpath` / `size` / `isBinary`）与 `rung`；文件内容是 `WebSkillController.file`。底稿是线上版本或显式 `from` 指定的版本时，这些都现成；底稿是草稿时，`write` 也已经能读。

**客户端状态机**（页面自己的，不是服务端的状态）：

```
new ──第一次内容改动成功──▶ saved
 │                        │
 │  正文改动              │  正文改动
 ▼                        ▼
dirty ──保存成功──▶ saved（digest 前移）
 │
 ├─ 409 digest_mismatch ──▶ conflict ──重新加载──▶ saved（服务端内容）
 │                              └────覆盖────▶ 保存 → saved / conflict
 ├─ 409 content_already_exists ─▶ duplicate（本地内容保留）
 └─ 4xx/5xx ─▶ error（本地内容保留）
```

**文件操作是立即写，正文是显式保存。** 新建、重命名、删除都是用户明确按下的动作，各自是一次服务端写；它们没有「未保存」态。正文编辑是页面里的缓冲，只有「保存」才写。两条边界的理由：结构操作本身没有可预览的中间态（一个还没建出来的文件无法编辑），而正文的中间态正是用户要看的；把它们都做成待保存会让树与编辑器各有一套未保存状态，离开保护也说不清在保护什么。

**新版本上的第一次结构操作也走 create。** 在还没有行的新版本上，第一次新建文件、重命名或删除与第一次正文保存是同一件事：一个 `POST …/versions`，`edit` 的 `op` 是那一次动作。成功后页面把地址换成 `@<code>/edit`，之后的每一次结构操作走 `…/edits`。这条不是特例，而是「第一次内容改动才落库」的同一句话——落库的触发是**第一次内容改动**，正文与文件结构都算。

**两条边界**。① **空文件夹不落库，也不触发建行**：`version_file` 只有文件行，`op` 表里没有 mkdir；新建文件夹只是页面内的分组，放进第一个文件之后才真正存在。只建空文件夹时地址与版本列表都不动。② **第一次内容改动前名称必填**：`POST …/versions` 的 `name` 是必填的，所以名称为空时新建文件 / 重命名 / 删除不发送请求，页面把焦点放回名称并说明；新建空文件夹不受影响。

## 数据模型

**没有迁移。** 本版不新增表、不新增列、不改约束。用到的既有形状：

| 对象 | 本版的用法 |
|---|---|
| `skill_version` | 第一次内容改动插入一行 `state='draft'`、`version=NULL`、`number=MAX+1`、`draft_name` / `changelog`（wire `note`）来自页头；`digest`/`file_count`/`total_bytes` 是「底稿 + 第一次改动」的结果；`title`/`description`/`frontmatter` 取底稿那一版的值（除非这次改动写了根 `SKILL.md`，那就用新解析出来的值） |
| `version_file` | 建行时按底稿的 manifest 逐行复制；没有改的文件继续引用同一个 `blob_sha256`，不复制字节（内容寻址，[ADR 0005](../../decisions/0005-content-addressing.md)） |
| `blob` | 只有第一次改动写了新字节时才产生新 blob；失败留下的孤儿由 v7 的 sweep 按 grace 回收（[ADR 0065](../../decisions/0065-blob-reclamation-waits-for-a-grace-period.md)） |

**名称与说明落在 `skill_version.draft_name` / `changelog` 两列上**（[ADR 0055](../../decisions/0055-drafts-have-a-required-name.md)）。建行时来自页头；之后由既有的两条 PATCH 原地更新它们。两列都不进 digest、不进地址，也不影响 `version_file`。

**底稿的读取与复制**：

1. 解析 `from`：显式给了 `from` 就按那一版；缺省时**线上指针 → 这一档能看到的最近一版草稿 → 空底稿**。**已下架 / 已丢弃不作底稿**——它们是可见性上的一档，不是「从哪开始改」的答案；否则页头就得为它们多出一种来源，而设计只有三种。调用者必须能读它——`write` 能读已发布版本与草稿。
2. 读到它的 `Manifest`（`SkillVersionService.filesOf`）与行上的 `title`/`description`/`frontmatter`（`VersionRepository.VersionRow` 已有这三列；通过 `SkillVersionService` 暴露一个读法，不把 internal 类型带进 use case）。
3. 在内存里应用这一次改动，得到 `Manifest.of(entries)`；`SKILL.md` 被写时用 `SkillDocument.parse` 重新解析元数据，并 `requireNameMatches`。
4. `requireWithinLimits`（512 / 16 MiB）与 `requirePath` 复用 `DraftFileWrite` 的同一组规则。
5. 在 `SkillVersionService` 上新增一个建行方法（例如 `submitFromBase`），由它在内部 `lockContentOf` + `findBySkillAndDigest`：命中任何**其它**行就是 409 `content_already_exists`（与 v7 的 `commitContent` 同一答案）。**use case 不直接引用 `VersionRepository`**——`internal/` 的仓储只属于模块内部（[`docs/architecture/README.md`](../../architecture/README.md) §模块索引），既有先例是 `SkillVersionService` 里的提交路径。
6. 同一个事务里 `insertIfAbsent(...)` + `insertFiles(...)`；`insertIfAbsent` 的 `ON CONFLICT (skill_id, digest) DO NOTHING` 在这里是第二道保险，返回空即 409，不返回已有行。**整个建行走 `DraftRecorder`**：版本码主键碰撞会中止事务，必须换一个新事务重画一次 id；3 次尝试（首次 + 2 次重试，`DraftRecorder` 的 `ATTEMPTS`）后再碰撞才是 500（ADR 0063 §决定 5 定了「换新事务重来」，次数是实现细节）。
7. 审计一行 `version_file_write`（`op='create'`）。现有 `DraftFileWrite.record` 的 `from` 是 relpath（`move`/`copy` 用），**不要复用它放底稿的钉**；建行这一行用独立键 `base_pin` / `base_digest`，其余字段照 `record` 的形状（`version`、`code`、`digest_after`、`files`）。

**空底稿**（没有可作底稿的版本：没有线上版本与草稿；已下架 / 已丢弃即使可读也不算）：不复制任何文件，先生成一个最小 `SKILL.md`，再应用第一次改动。模板：

```markdown
---
name: <skill 的 name>
description: <skill 行的 description——入库时已由 SkillDocument 要求非空>
---

# <skill 的 title 或 name>
```

它和任何一次上传的 `SKILL.md` 走同一个 `SkillDocument.parse`；`name` 与地址不一致时拒绝。

## 接口

### 两条新的写路由

都挂在 `WebSkillRoutes.BASE` 下（`/web/skills`），由**同一个**包私有控制器 `WebDraftController` 映射（与 `WebSkillController` 同一风格），身份取 `Authentication caller` → `AuthenticatedSubject.ofSession(caller.getName())`（与 `WebSkillController` 一致，不用 `@AuthenticationPrincipal`）。`/web/**` 的安全链已经要求会话与 CSRF（`SecurityConfig`），这里不新增规则。

#### `POST /web/skills/{namespace}/{name}/versions`

从底稿建一份草稿，并应用第一次改动。

```json
{
  "name": "compress 补文档",
  "note": "把 compress 的用法补进正文",
  "from": "1.2.3",
  "from_digest": "sha256:9f2c…",
  "edit": { "op": "write", "path": "SKILL.md", "text": "---\nname: pdf-tools\n…" }
}
```

- `from` 省略 = 线上版本；没有线上时取这一档能看到的**最近一版草稿**；都没有 = 空底稿。**已下架 / 已丢弃不作底稿**（它们不是「从哪开始改」的答案，也不在页头的三种来源里）；`write` 能读别人提交的草稿，复制是读，因此也允许（§开放问题 §已定 8）。
- `from_digest` 必填（空底稿时省略）：它让「我看到的那一版」与「服务端复制的底稿」是同一份。底稿在页面打开后被别人改过（草稿底稿才可能）→ 409 `digest_mismatch`，错误明细 `error.details` 里 `field=current_digest`、`issue=<新 digest>`。
- `edit` 的三种形状（`write` / `delete` / `rename`）与下面单次改动相同；`op` 省略时按 `write`。
- `name` 走 `VersionLabels.name`（必填、长度）；`note` 走 `VersionLabels.note`。两者都来自页头，**不复制底稿那一版的名称与说明**：新版本是另一件事，它的名称与说明从空开始。
- 201 + `{"version": …}`；`version` 与 `AuthoredSkillResponse.Version` 同形（`code`、`digest`、`name`、`state`、`fileCount`、`totalBytes`…），外加本次之后的 `files` 清单。

错误：

| 状态 | code | 何时 |
|---|---|---|
| 400 | `draft_name_required` / `draft_name_too_long` / `note_too_long` / `invalid_request` / `invalid_path` / `invalid_upload` | 名称缺失或过长、说明过长、名称或说明里有控制字符、路径不合规、frontmatter 不合法、超过 512 / 16 MiB |
| 403 | `forbidden` | 低于 `write`；底稿不可见归 404，不在这里泄露存在性 |
| 404 | `skill_not_found` | 地址不存在或调用者看不到 |
| 409 | `digest_mismatch` | `from_digest` 与底稿当前 digest 不同 |
| 409 | `content_already_exists` | 结果已经是同一 skill 的另一版；`error.details` 里有 `state`，命中的行是 `published` 时还有 `pin`（与 v7 的实现一致） |

#### `POST /web/skills/{namespace}/{name}/versions/{version}/edits`

对一份**已有草稿**做一次改动。

```json
{ "base_digest": "sha256:…", "op": "write", "path": "references/split.md", "text": "…" }
```

`op` 三种：

| op | 字段 | 语义 |
|---|---|---|
| `write` | `path`, `text` | 新增或替换；`text` 按 UTF-8 编码，空字符串是合法内容 |
| `delete` | `path` | 删除；`SKILL.md` 与「删到 0 个文件」由服务端拒绝 |
| `rename` | `from`, `to` | 只改 `relpath`，不动 blob；目标已是**文件** 409 `file_already_exists`；目标与已有文件 / 目录同名（`a` 与 `a/b`）400 `invalid_path`；`from` 是 `SKILL.md` 拒绝 |

- `base_digest` 必填。缺 → 428 `if_match_required`（复用作者面的同一个码：meaning「缺基线」，尽管这里是 JSON 字段而不是 `If-Match` 头）；不匹配 → 409 `digest_mismatch`，`error.details` 里带 `current_digest`。
- **`write` 是新增或替换**：v7 的 `VersionRepository.putFile` 是 upsert，已存在的路径不会回 409。**新建这一路的「目标已存在」由页面先拒绝**（「该路径已存在文件。」），不发请求；直接调接口就是替换。
- **同一路径的重命名由页面短路**（源与目标相同即不发请求）；服务端 v7 的 `MoveDraftFileUseCase` 会把它当作目标已存在回 409 `file_already_exists`，本版不改服务端这条既有行为。
- **树不变量**：写 / 重命名之后，manifest 里任意两个路径不能一个是另一个的前缀（`a` 与 `a/b` 不能同时是文件）。违反的请求 400 `invalid_path`（`field` 是那条路径）。这条在新路由上检查——否则网页的树会出现同名文件与目录。v7 的作者面没有这条检查，属于既有面，不在本版改。
- 200 + `{"version": …}`（与建行同一形状）。客户端用响应里的 `digest` 作为下一次保存的基线，用 `files` 更新文件树。
- 权限、状态、上限、frontmatter 全部落到 v7 的 `openDraft` / `lockDraft` / `commitContent`：`write` 只改自己的草稿，`maintain` 改任何草稿。**非草稿按状态与档位分开**：已发布 / 历史版本对 `write` 及以上 409 `version_is_not_draft`；已下架 / 已丢弃对 `write` 不可见（404），`maintain` 及以上 409。
- 正文通过 JSON 字符串传：编辑页只编辑文本；二进制不进这条路（见 §开放问题 §仍开放 4）。16 MiB 是整版上限，单次请求体落在它之内。

#### 名称与说明：复用已有的两条 PATCH

编辑页头部的名称与说明不是新契约，v6 已经给了写路径：

- `PATCH /web/skills/{ns}/{name}/versions/{version}/name`，体 `{ "name": "…" }`（`WebSkillRoutes.VERSION_NAME`）；
- `PATCH /web/skills/{ns}/{name}/versions/{version}/note`，体 `{ "note": "…" }`（`WebSkillRoutes.VERSION_NOTE`）。

两者照旧走 `VersionLabels.name` / `VersionLabels.note` 与草稿的状态、权限规则（`write` 改自己的草稿、`maintain` 改任何草稿）。**能看见、且能改的那一行若已不是草稿，答案是 400 `draft_name_is_frozen` / `note_is_frozen`**（`VersionStateException`，与文件写的 `version_is_not_draft` 不是同一个码）。判断按 `updateDraft` 的既有次序：先按档位可见性过滤（`write` 对已下架 / 已丢弃 → 404），再按提交人（`write` 对别人提交的版本 → 403），最后才看状态（→ 400 frozen）。**新版本**还没有行，名称与说明随 `POST …/versions` 的 `name` / `note` 一起写入；**已有草稿**在字段失焦或回车时各发一条 PATCH，正文的「保存」与它们无关。两条都不改 `digest`、`code` 或 `state`；失败时页面保留输入并显示服务端的 `error.details` / `message`。

- **后写胜出，没有基线比较**：名称与说明不参与内容摘要，两条 PATCH 也不接 `base_digest`——两个人同时改名称，最后到达的那条生效。这与版本页已有的铅笔是同一个语义。
- **失焦写入用 `keepalive: true` 发出**：点返回链接会先让输入框失焦、再导航，普通请求可能随页面一起被中止；一条几百字节的 PATCH 用 `keepalive` 走完。写入失败仍按上面的口径显示在字段下面。
- **两层上限**：名称 / 说明输入在客户端按码点挡在 40 / 200（第 41 / 201 个字符进不去，与版本页的铅笔一致）；服务端仍按同一口径校验，绕过页面的超长回 400 `draft_name_too_long` / `note_too_long`，不静默截断。

### 读

不新增读接口：

- 编辑页打开时 `GET /web/skills/{ns}/{name}@<pin>`（`WebSkillController.detail`）拿 `version.digest`、`files`、`rung`、`version.state`；
- 打开一个文件时 `GET /web/skills/{ns}/{name}@<pin>/files/{*relpath}`（`WebSkillController.file`）；
- 新版本的底稿 pin：线上版本（`isCurrent` 的那一行）→ 没有则这一档能看到的最近一版草稿 → 都没有 = 空底稿；已下架 / 已丢弃不作底稿。
- **空底稿的读法**：`GET /web/skills/{ns}/{name}?base=none`——同一条读接口、多一个查询参数，跳过 `Latest` 的兜底，顶层 `title` / `description` / `frontmatter` 取 **skill 行**（`Access`），`selected=null`、`files=[]`。页面用它拼最小 `SKILL.md`。**页面的判定顺序**：新版本页先读 `Latest`；`selected` 是 `published` / `draft` 就用它，是 `discarded` 或 `null` 就改读 `?base=none`。`maintain` 及以上在「所有版本都已丢弃」的 skill 上会先拿到那一版（`Latest` 的既有兜底），必须换掉——否则模板会带上已丢弃那一版的元数据。

**两种页面形态的判断。** **新版本页**（`/new`）渲染任何可读的底稿——线上版本、这一档能看到的草稿（包括别人提交的，复制是读）、或空底稿；它没有 `version.state` 可判，`write` 以下直接打开时页面按 `rung` 显示与 `state=missing` 同一屏，第一次写 403。**已有草稿页**（`@<pin>/edit`）用响应里的 `version.state`、`rung` 与 `version.submittedBy` 判断：`draft` 且（`maintain` 及以上，或 `write` 且提交人是自己）；否则同一屏「没有这一版」。第一次写由服务端按状态与档位作答：不可见 404、非草稿 409 `version_is_not_draft`、别人的草稿 403 `forbidden`、低于 `write` 403。这不是服务端的整页拒绝——读接口与版本页共用。

### 前端地址

`App.vue` 的 `skillRouteOf` 增加两种形状：

- `/skills/{ns}/{name}/new?file=<relpath>`：尚未保存的新版本；
- `/skills/{ns}/{name}@<pin>/edit?file=<relpath>`：已有草稿。

`file` 放查询参数而不是路径段：它只决定右侧打开哪一份文件，不影响「这是哪一版」；刷新、收藏与从版本页跳进来都带同一个地址。第一次内容改动成功后用 `history.replaceState` 把地址从 `/new` 换成 `@<code>/edit`，不整页重载——页面上其它文件的缓冲还在。

## 分期

| 期 | 交付什么 | 验收什么 |
|---|---|---|
| **P0 服务端** | 两条 `/web` 写路由；`CreateDraftFromVersionUseCase`；复制 manifest、空底稿与 `?base=none` 读法、第一次改动；审计；权限与 409 全部沿用 v7 | prd #2–#6、#7–#14、#21 的服务端一半；`VersionEditorIT` |
| **P1 网页** | `VersionEditorPage.vue` + `FileEditor.vue` + `DraftFileTree.vue`；名称与说明（失焦即写）、新建/重命名/删除、保存、脏标记、冲突、离开保护、搜索、键盘、窄屏 | prd #2–#16、#18–#21 的页面一半（#1、#17 的入口在 P2）；`VersionEditorPage.test.ts` |
| **P2 入口与设计** | 版本列表「新建版本」从占位变成链接；版本页「编辑文件」；列表行菜单「编辑」；`design/` 的编辑页与状态图 | prd #1、#17；`console-states --only editor`、视觉验收由人做 |

**顺序是硬约束**：P0 的写路径成立之后，P1 才有可保存的东西；P2 的入口在 P1 的页面能打开之后才有意义。

## 开放问题

### 已定（2026-10-20）

1. **第一次内容改动才建行，还是立刻建一行？** 已定：第一次内容改动才建行。立刻复制线上会撞 `UNIQUE (skill_id, digest)`；要允许它就得改唯一约束，并回答「摘要钉是否还能唯一定位」「发布幂等怎么办」——那是另一版的结构性取舍。代价是 prd #3 写明的「第一次内容改动前不在列表里」。
2. **冲突时「覆盖」的语义。** 已定：重新读这一版的当前 `digest`，再把本地这份内容按同一位置写一次。它只让**这一次写**胜出，不撤回别人对其它文件的改动；如果别人删掉了这个文件，覆盖会重新创建它。删除操作的「覆盖」在没有这个文件时视为已完成（重新读一遍即可）。
3. **名称与说明在哪里改。** 已定（2026-10-20 用户定）：编辑页页头同时给出**名称与说明**两个输入。新版本第一次内容改动时随建行一起写入；已有草稿失焦或回车即写，复用 v6 的两条 PATCH。名称必填、说明可选，两者都不进 digest、不改地址；版本页原有的铅笔编辑保留，两条路写的是同一对列。
4. **编辑器内核。** 已定：一个等宽 `textarea` + 同步滚动的行号槽；不做语法高亮、不做 Markdown 预览、不引入 CodeMirror / Monaco。行号、Tab 插入两个空格、`⌘/Ctrl+S` 是这一版的全部编辑器能力。
5. **结构操作是否进「未保存」。** 已定：不进。新建、重命名、删除各自是一次立即写；「未保存」只指正文缓冲。离开保护也只保护正文。
6. **空底稿的模板。** 已定：`name` + `description` 取自 skill 行，正文是 `# <title>`；它就是一次普通的 `SKILL.md` 解析，`name` 不符照常拒绝。
7. **多份未保存正文时离开。** 已定：不给「保存全部」。离开弹窗只有「继续编辑」与「放弃并离开」，并写明有几份文件未保存；批量保存会引入一串顺序写与半成功状态，不是这一版的量。
8. **底稿是别人的草稿。** 已定：`write` 能读它但改不动；新建版本只是复制它的内容，复制这个动作是读，因此允许。新草稿的提交者是操作者本人，`write` 之后能改自己的这一份。
9. **旧草稿的版本码 / ULID。** 已定：编辑页地址接受两者——`@<pin>` 走现有的 `SkillAddress` 解析；第一次内容改动返回的是新行的 8 位版本码（ADR 0063）。

### 仍开放（每条是一个能回答的问法）

1. **单次正文请求的上限要不要单独设？** 整版 16 MiB 已经覆盖；但一次 JSON 请求里塞一个 16 MiB 的字符串，内存与延迟都不好看。当前倾向：先沿用整版上限，T-PERF 时量一次再决定是否加单文件上限。
2. **保存后是否要在「版本事实」里显示「最后编辑」？** 版本行没有 `updated_at`，v7 也没加（ADR 0064 后果）；编辑页只在当前会话里知道保存过。当前倾向：不加列、不展示。
3. **编辑器要不要记住上次打开的文件？** 地址里的 `file=` 已经是答案；回版本页再进来时默认 `SKILL.md`。当前倾向：不记浏览器本地状态。
4. **二进制文件将来要不要支持替换？** 这需要一条单独的上传/替换路径与一个二进制预览面，不是文本编辑器的扩展。当前倾向：本版不做，阅读与下载已经够用。
