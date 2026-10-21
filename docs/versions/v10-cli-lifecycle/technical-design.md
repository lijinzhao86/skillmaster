# v10-cli-lifecycle · 技术设计

> **最后更新**：2026-10-21
> **所属版本**：[`README.md`](README.md) · **需求**：[`prd.md`](prd.md)

<!--
接口的权威在本文件；需求在 prd.md。两条轴的分界（内容/生命周期 vs 按人授权）见 prd.md §范围。
-->

## 架构

**这一版不新增模块、不新增表、不改梯子、不新增 scope。** 五块能力落在两个既有平面上：

| 平面 | 路径前缀 | 鉴权 | 这一版加什么 |
|---|---|---|---|
| 作者面 | `/api/v1/author/skills` | Bearer + scope；档位在用例里判 | 建 skill、解析底稿、建草稿（第一次改动） |
| 消费面 | `/api/v1/skills` | Bearer + scope；档位在用例里判 | 申请、撤回、读自己的申请 |
| 网页面 | `/web/skills` | 会话 + CSRF | **不动**——批准 / 拒绝仍在这里 |

**复用既有用例，不重写规则。** 建 skill 走 `CreateSkillUseCase`（v9），建草稿走 `CreateDraftFromVersionUseCase`（v8），申请 / 撤回走 `RequestSkillAccessUseCase` / `WithdrawSkillAccessRequestUseCase`（v5），共享列表 / 撤销走 `ListSkillGrantsUseCase` / `RevokeSkillShareUseCase`（v5）。新控制器只做三件事：把 HTTP 形状翻译成用例入参、把 `Optional` 翻成 404、把身份从 `@AuthenticationPrincipal AuthenticatedSubject` 交给用例（作者面与消费面都是这个形状，**不是** `/web` 的 `Authentication`）。

**底稿解析只有一处实现。** `CreateDraftFromVersionUseCase` 的私有 `resolveBase` 现在只服务内部；本版在用例里加了一个公开的 `resolveBaseFor`（内含同一段规则），供新的「读底稿」用例调用，**不复制一份到控制器或 CLI**。CLI 因此是「读解析结果 → 带上 from + digest → 建草稿」，两次请求之间底稿变了由服务端的 digest 校验答 409（[ADR 0005](../../decisions/0005-content-addressing.md) 的内容身份 + v8 的 `requireBaseDigest`）。

**scope 仍按方法走。** `SecurityConfig.java:221-224` 把 `/api/v1/**` 的 `GET/HEAD/OPTIONS` 交给 `skills:read`、其余交给 `skills:write`。因此：建 skill / 建草稿 / 撤销共享 / 申请 / 撤回 = `skills:write`；读底稿 / 列共享 / 读自己的申请 = `skills:read`。按 [ADR 0039](../../decisions/0039-api-key-defaults-and-their-edges.md) §决定 2「写蕴含读」，一把 `skills:write` 的凭据在签发时已经带读，所以 `@new` 的两次检查用一把凭据就够。

**难点的位置在档位，不在 scope。** 建草稿要求 `write`+；读底稿也必须要求 `write`+（否则一个 `browse` 持有人能读到草稿的版本码与 digest）；共享列表与撤销要求 `admin`（所有者天然满足）。这些判定全部留在用例里（[ADR 0047](../../decisions/0047-permissions-are-a-ladder.md)、[ADR 0051](../../decisions/0051-the-author-plane-serves-by-tier.md)），控制器不写第二份。

## 数据模型

**没有迁移。** 这一版不写新表、不改列：

- 建 skill / 建草稿落在既有的 `skill` / `skill_version` / `version_file`；首次草稿的根 `SKILL.md` 由 `SkillMdTemplate` 渲染（`CreateSkillUseCase.java:81`）。
- 访问申请落在既有的 `skill_access_request`（`V20__a_request_is_not_a_grant.sql`）：主键 `(skill_id, requester_id)`，**没有 status 列**；批准 / 拒绝 / 撤回都是删行，历史在 `audit_event`。
- 共享落在既有的 `skill_grant`；撤销按用户名处理：**用户名存在、只是没有那条共享 → 204**；**用户名不存在 → 400 `no_such_user`**（`RevokeSkillShareUseCase` 的口径）。

**唯一动到结构的地方是新增公开的 `resolveBaseFor`**：它复用私有的 `resolveBase`（不改那条规则的语义与排序：线上指针 → 本档位可见的最新草稿 → 空白），把「解析出的底稿引用」交给控制器。

## 接口

### 服务端 · 作者面

#### 1. 建 skill

```
POST /api/v1/author/skills
```

| 项 | 内容 |
|---|---|
| 请求体 | `{"name": "...", "description": "...", "version_name": "..."}`。`version_name` **必填**：直接调 API 省略或给空串 → 400 `draft_name_required`；CLI 的「省略 `--name`」是由 CLI 先填 `第一版` 再发，服务端不替任何人补默认值 |
| 成功 | 201 `{"namespace": "...", "name": "...", "version_code": "<草稿 id>", "version_name": "...", "state": "draft"}` |
| 档位 | 不要求 grant 档位；任何已登录账号都能在自己的个人命名空间里建（`CreateSkillUseCase.java:79`） |
| scope | `skills:write` |
| 错误 | 409 `skill_already_exists` / `skill_deleted`；400 `invalid_upload`（`details[].issue`：`required_field_missing`、`name_too_long`、`name_not_usable_as_a_directory`、`name_contains_version_separator`、`name_contains_unaddressable_char`）；400 `draft_name_required` / `draft_name_too_long`；403 scope |

**命名空间不在路径里**：这个用例只写调用者的个人命名空间。这是作者面路径族**唯一一条没有 `{namespace}/{name}` 的写路由**——动作发生时那个名字还不存在（[ADR 0073](../../decisions/0073-asking-for-access-splits-by-plane.md) §决定 4）。命令面因此只收一段名字（`skill create hello`），带 `ns/` 的是用法错误。

#### 2. 读被解析出的底稿

```
GET /api/v1/author/skills/{namespace}/{name}/draft-base
```

| 项 | 内容 |
|---|---|
| 成功 | 200 `{"base": null}`（空白底稿）或 `{"base": {"pin": "1.0.0" \| "<版本码>", "digest": "sha256:..."}}`——**只给引用，不给内容** |
| 档位 | `write`+（草稿的版本码与 digest 不能给 `browse`） |
| scope | `skills:read` |
| 错误 | 404（这个 skill 对该调用者不存在）；403（看得见但低于 `write`） |
| 语义 | 与建草稿省略 `from` 时**同一个** `resolveBase`：线上指针 → 本档位可见的最新草稿 → 空白；已下架 / 已丢弃永不作为底稿（显式把它当底稿时：`维护`/`管理` → 400 `not_a_base`，`写入` → 404，因为这两种状态对 `写入` 不可见） |

#### 3. 建草稿（第一次改动）

```
POST /api/v1/author/skills/{namespace}/{name}/versions
```

| 项 | 内容 |
|---|---|
| 请求体 | `{"name": "...", "note": "..."（至多 200 码点，超过 → 400 `note_too_long`）, "from": "<版本>", "from_digest": "sha256:...", "edit": {"op": "write\|delete\|rename", "path": "...", "text": "...", "from": "...", "to": "..."}}` |
| 成功 | 201 `{"version": {…AuthoredSkillResponse.Version…}, "files": [{relpath, sha256, size, is_binary}]}`——与网页编辑器同一个响应形状（`WebDraftController.DraftResponse`），CLI 只取 `version.code` |
| 档位 | `write`+ |
| scope | `skills:write` |
| 错误 | 400 `invalid_request`（`from:not_a_base`、缺 `edit`、`from_digest` 与空白底稿同时出现）；400 `invalid_path`；400 `invalid_upload`；404（skill 看不见、指定底稿看不见）；409 `content_already_exists` / `digest_mismatch` / `file_already_exists` / `skill_md_cannot_be_deleted` / `last_file_cannot_be_deleted`；428 `if_match_required`（有底稿必带 digest、空白两个都不带；CLI 只在「读完 `draft-base` 是空白、发请求前被别人补了一份草稿」这个竞态里碰到它——`writeRefusal` 有这条翻译）；403 档位或 scope |

**空白底稿的约定**：`from` 与 `from_digest` **都不带**；带上 digest 是 400（`CreateDraftFromVersionUseCase.requireBaseDigest`）。CLI 在 `draft-base` 答 `base: null` 时照此发。

**`cp` 没有这条路**：`edit.op` 只有 write / delete / rename（`CreateDraftFromVersionUseCase.apply`）。CLI 的 `cp …@new` 在本地拒绝，不发请求。

### 服务端 · 消费面

#### 4–6. 申请 / 撤回 / 读自己的申请

```
POST   /api/v1/skills/{namespace}/{name}/access-requests        {"rung": "write"}
GET    /api/v1/skills/{namespace}/{name}/access-requests/me
DELETE /api/v1/skills/{namespace}/{name}/access-requests/me
```

| | POST | GET | DELETE |
|---|---|---|---|
| 成功 | 201 `{"rung": "..."}`（被接受的目标档位） | 200 `{"pending_rung": "write" \| null}` | 204 |
| scope | `skills:write` | `skills:read` | `skills:write` |
| 档位 | 能看见即可（不要求 grant）；目标档位必须严格高于当前，所有者不能申请 | 同左 | 同左 |
| 错误 | 404（看不见）；400 `invalid_request`（`details[].issue`：`not_a_rung`、`owner_has_no_higher_rung`、`not_above_current_rung`） | 404 | 404 |

**幂等**：重复 POST 覆盖同一行的目标档位（`SkillAccessRequestRepository.upsert`，主键 `(skill_id, requester_id)`）；重复 DELETE 一律 204。

**批准 / 拒绝与待批列表不在这一版**：三条 `/web` 路由不动。五条动作按平面切开的理由在 [ADR 0073](../../decisions/0073-asking-for-access-splits-by-plane.md)。

### CLI

五块能力的命令面。所有新命令都支持 `--json`；失败时退出码非零，`--json` 的错误对象走 stderr（沿用 `cliFailure` 的 `Code` / `Message` / `Hint` / `ServerCode` / `Issue`；`Issue` 只在 `invalid_request` / `invalid_upload` 上出现，值是服务端 `details[].issue` 的名字）。

| 命令 | 行为 | 服务端调用 |
|---|---|---|
| `skill create <名字> --description <描述> [--name <版本名称>] [--json]` | 建 skill + 第一份草稿，打印 `ns/名字@<版本码>` 与版本名称 | `POST /api/v1/author/skills` |
| `skill write\|edit\|rm\|mv <ns>/<name>@new --name <版本名称> [--from <版本>] [--note <说明>] …` | 先解析底稿（省略 `--from` 时 `GET …/draft-base`；给了 `--from` 时 `GET …/versions/{pin}` 取 digest），再建草稿并应用这次改动 | `GET …/draft-base` 或 `GET …/versions/{pin}`，然后 `POST …/versions` |
| `skill shares <ns>/<name> [--query <文字>] [--page <n>] [--json]` | 列出 `用户名 / 档位 / 加入时间`；输出尾部注明 `第 p 页，共 n 人` | `GET /api/v1/skills/{ns}/{name}/grants?q=&p=` |
| `skill unshare <ns>/<name> --user <用户名> [--json]` | 撤销一次共享；幂等 | `DELETE /api/v1/skills/{ns}/{name}/grants/{handle}` |
| `skill request <ns>/<name> --role invoke|browse|write|maintain|admin [--json]` | 提交申请；重复提交覆盖目标档位 | `POST …/access-requests` |
| `skill request <ns>/<name> --status [--json]` | 打印在等的档位，或「没有在等的申请」 | `GET …/access-requests/me` |
| `skill request <ns>/<name> --cancel [--json]` | 撤回；幂等 | `DELETE …/access-requests/me` |
| `skill open <ns>/<name> [--tab info\|files\|versions\|settings] [--sub general\|collaborators\|details] [--print] [--json]` | 打印并尝试打开 `/skills/{ns}/{name}?tab=&sub=`；`--sub` 只允许配 `--tab settings`，三个值之外的取值是本地错误 | 不发请求 |
| `version publish\|rollback\|revoke\|restore`（拒绝） | 第三句给 `skillmaster version open <ns>/<name>@<版本> --sub actions`（**v11 起**，见 [ADR 0074](../../decisions/0074-a-handoff-may-carry-a-preselection.md)；本版交付时第三句到 `<版本>` 为止，`publish` 从 v11 起另接受 `--bump` 并转发） | 不发请求 |
| `version offline\|online\|delete`（拒绝） | 第三句给 `skillmaster skill open <ns>/<name> --tab settings`（入参带 `@版本` 时脱掉） | 不发请求 |

**CLI 的本地校验**（与「服务端是权威」不矛盾——这些是**用法**错误，不是权限判断）：`skill create` 的名字含 `/` 或 `@`、缺 `--description`；`@new` 缺 `--name`；`cp …@new`。它们不发请求、退出码非零。

**内容命令不带 `--json`**：`write` / `edit` / `rm` / `mv` 从 v7 起就只回答字节与句子；`@new` 不改变这条契约。`--json` 只在五块新命令上。

**输出约定**：`skill shares --json` 直接透出服务端的 `{grants, total, page, page_size}`；`skill request --status --json` 给 `{"pending_rung": "..." \| null}`；`skill open --json` 给 `{"url", "opened"}`；`skill create --json` 给 `{"namespace","name","version_code","version_name","state"}`。

**拒绝句**：前两句与今天完全一致（`version.go` 的 `pageOnlyVerb` 里那两行），只有第三句按动作分两套（[ADR 0072](../../decisions/0072-the-handoff-points-at-the-page-that-owns-the-action.md)）。逐字文本见 [`prd.md`](prd.md) §做 #5，测试逐字钉住。

## 分期

| 期 | 内容 | 完成的判据 |
|---|---|---|
| P0 服务端 | 三条新路由 + 在 `CreateDraftFromVersionUseCase` 上加一个公开的 `resolveBaseFor`（内部复用私有的 `resolveBase`，规则只此一份）；控制器照 `AuthorSkillController` / `SkillsController` 的形状（包私有 `@RestController`、请求体是内部 record、响应是顶层 record + `of(...)`） | 新 IT 全绿：建 skill（成功 / 两种 409 / 字段 400 / 零写入）、读底稿（线上 / 草稿 / 空白 / 看不见）、建草稿（三种 op / 五种错误 / 空白约定）、申请三条（前置 / 幂等 / 不授权） |
| P1 CLI | 五块命令、帮助、README、拒绝句两套落点 | CLI 单测按 prd 验收 #1–#19；`go test ./...` 通过 |
| P2 收尾 | voice-spec 两处、CLI README、帮助（voice-spec **随本版同批交付**，见 PRD §依赖与前置） | `check_docs.py` 退出 0；`skill help` 列出新命令 |

**P0 先于 P1**：契约先成立，CLI 才有东西可调。voice-spec 的名词表与「新建 skill」定义**不阻塞本版代码**：本版不改网页画法，那两处只是把既有口径补进权威表，因此随本版同批交付（PRD §依赖与前置）。

## 开放问题

1. **`skill shares` 要不要默认取完所有页？** 当前倾向：**不**——服务端是按页分页（每页 10），CLI 给 `--page`，默认第一页并在尾部注明总人数；一次拉完会让「看一眼」变成一个人数越多越慢的动作。要全部时用 `--page` 自己翻。（可回答：`--all` 是否值得加。）
2. **`skill request --status` 读到的是「我自己的 pending」，够不够？** 当前倾向：**够**——被拒后行就没了，服务端没有可查询的拒绝历史（[ADR 0052](../../decisions/0052-a-request-is-not-a-grant.md) §后果）；要「被拒过」需要新状态机，不在本版。
3. **`@new` 在 `--from` 指向别的 skill 的版本码时，CLI 先 `GET …/versions/{pin}` 就会 404，还是等建草稿时 404？** 当前倾向：**先读时 404**——CLI 的读请求会先失败，错误句子与服务端一致；建草稿那一步根本不会发生。（可回答：是否需要在 CLI 侧把 404 改写成更具体的一句。）
4. **`skill open` 在没有图形界面 / 打开失败时说什么？** 当前倾向：与 `version open` 完全一致——地址照打印，警告进 stderr（`version.go` 的 `versionOpen`），退出码不受影响。（可回答：是否要把「没打开」变成非零退出。）
