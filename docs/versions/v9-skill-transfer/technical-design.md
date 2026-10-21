# v9-skill-transfer · 技术设计

> **最后更新**：2026-10-21
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

<!--
接口与数据模型以本文为准；代码与本文不一致时，先回写本文，再改代码（dev-sop §一）。
本版不新增表、不新增列；所有变化都在既有管线的边界上。
-->

## 架构

**一句话**：整包导出是 M9 对**一份 manifest** 的序列化（服务端一次生成、两个平面各一条薄路由）；导入是既有两个提交用例的**新适配器**（网页 multipart → 同一组用例），校验前多一步 sidecar 的剥离与解析；网页新建 skill 是一条新的写用例，把「skill 行 + 第一份草稿 + 它的 `SKILL.md`」放进同一个事务。

```
CLI download ─ GET /api/v1/author/skills/{ns}/{name}[/versions/{pin}]/archive ─┐
Web 下载直链 ─ GET /web/skills/{ns}/{name}@<pin>/archive ───────────────────────┤
                                                                               ▼
                                              ArchiveSkillVersionUseCase（新，usecase）
                                                → ReadAuthorVersionUseCase.show（既有：browse + 按档可见）
                                                → SkillVersionService.filesOf（既有：一份 manifest）
                                                → SkillSidecar.render（新，M5；清单已有该路径时不渲染）
                                                → SkillDistributionService.archive（新，M9）
                                                     → BlobStore.open（既有，M6，流式）
                                                     → 临时 zip 文件（错误边界 + Content-Length）

CLI submit ─ POST /api/v1/skills[...]（既有 multipart）──────────────────┐
Web 导入到已有 skill ─ POST /web/skills/{ns}/{name}/versions/import ──────┤→ SubmitSkillVersionUseCase（既有）
Web 导入到我的 skill ─ POST /web/skills/import ───────────────────────────┘→ SubmitSkillUseCase（既有）
                                                                          → SkillUploadValidator（改）
                                                                               → ZipReader（只放宽 reader 余量）
                                                                               → stripSingleRootDirectory（既有）
                                                                               → SkillSidecar.parse（新：剥离保留路径）
                                                                               → 内容上限（validator 成为语义权威）
                                                                          → Uploads.store（既有，M6）+ SkillVersionService（既有，M7）

Web 新建 skill ─ POST /web/skills（JSON）→ CreateSkillUseCase（新，usecase）
                                            → SkillMdTemplate（从 v8 抽出）+ SkillDocument.parse（既有，M5）
                                            → Uploads.store（既有，M6）
                                            → SkillMetadataService.createOwned（新，M12：只建不取）
                                            → SkillVersionService.submit（既有，M7：本来就不检查 Access）
```

**两个平面，同一组用例。** 作者面（Bearer + `skills:read`）与浏览器面（会话 + CSRF）的鉴权不同，适配器因此各有一条；它们调的是同一个 `ArchiveSkillVersionUseCase` 与同一组提交用例。**导入不新写第三条提交管线**：`SubmitSkillUseCase` 与 `SubmitSkillVersionUseCase` 已经分别覆盖「个人命名空间」与「地址式加版本」，网页只是把 multipart 解析成它们的入参（[`SubmitSkillUseCase.java:78-111`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/SubmitSkillUseCase.java)、[`SubmitSkillVersionUseCase.java:71-117`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/SubmitSkillVersionUseCase.java)）。

**导出的快照取自一处。** 用例先按钉解析版本（`ReadAuthorVersionUseCase.show`，它已经带 `browse` 底线与按档可见），再读**一次** `filesOf` 得到完整的 `Manifest`；blob 不可变，所以之后逐条流出的字节都属于这一份清单。用例跑在 `REPEATABLE READ` 的只读事务里（`@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`），版本行与文件行因此是同一个快照。**用例调用用例在这条链上是新的组合**（既有代码里已有先例——`WebEditDraftFileUseCase` 持有写 / 删 / 移三个用例，`PublishGatewaySkillUseCase` 持有 `SubmitSkillUseCase`；新的是 Archive → Show 这一对）：`show` 自己的注解是 `readOnly = true` / 默认传播，调用进来时**加入**外层事务而不是新开一个，所以隔离性由外层这一个注解决定，这一点要在实现里按注释钉住，别把 `show` 改成 `REQUIRES_NEW`。

**这一个快照保护的是行，不是已经被 GC 回收的字节。** blob 不可变，但有人在导出一份**草稿**的同时删掉清单里的一个文件时，`underExclusiveWrite` 的清扫会把那个已无引用的 blob 删掉；我们的 manifest 来自更早的快照，再去 `BlobStore.open` 就会失败——在发字节之前，所以客户端拿到 500，而不是半个 zip。这是既有单文件读取的同一性质（冻结的已发布版本不受影响），不是本版引入的；**不拿 GC 锁去堵它**（那会让一次下载挡住所有写入）。T10 的并发编辑是**新增**文件，不触发这条。要写进已知问题的是「并发删除 + 下载草稿」这一个窗口，不是「快照不一致」。digest 用**这份 manifest 自己算出来的**（`Manifest.digest()`），不用行上那一列——即使草稿在两次读之间被改名，包的身份仍与包的内容一致（验收 #10）。

**包在临时文件里成形，不在内存里成形。** 16 MiB 的内容上限意味着单次导出最多约 16 MiB；把它整个拼在内存里在单请求下没问题，但并发下载会成倍放大堆占用，而且**服务端错误只能在第一个字节发出前变成错误响应**。所以 `SkillDistributionService.archive` 先写一个临时 zip，成功后返回「路径 + 文件名 + ETag + 字节数」，控制器再以 `StreamingResponseBody` 流出；失败发生在发字节之前，客户端拿到的是正常的错误信封，而不是一个 200 的半个 zip。代价是一个临时文件的生命周期：`ArchiveArtifact` 是 `AutoCloseable`，body 的 `finally` 删除它；响应没有开始流出就被丢弃的那份由操作系统的临时目录策略兜底（`ArchiveStreams` 的注释这么写）。**`archive` 自己写到一半失败时还没有 artifact 可以交给控制器**，所以服务内部用 `try { … } catch (IOException | RuntimeException | Error e) { delete; … }` 兜住（`IOException` 包成 `UncheckedIOException` 再抛，临时文件同样删掉）。进程被强杀时残留的临时文件由操作系统的临时目录策略兜底（见 §开放问题 Q1）。

**保留路径上的那个条目只在清单没有它时才写。** 判断在 `ArchiveSkillVersionUseCase`：它拿到 `manifest` 后看有没有 `ReservedPaths.SIDECAR`——有就把 `sidecar` 参数传成 `null`、**原样流那个文件**、不再渲染（否则 zip 里会出现两个同名 entry）；没有才 `SkillSidecar.render` 交给 `SkillDistributionService.archive` 作为最后一个条目写进去（PRD 做 #9 的那条例外）。

**保留路径的规则只有一处，但不在 `ZipReader` 里。** `ZipReader` 是解压炸弹的硬边界，它不该知道平台元数据叫什么。本版把语义上限移到 `SkillUploadValidator`：reader 只多放**一个文件、64 KiB** 的余量（`IngestLimits.withRoomForSidecar()`），validator 剥掉 sidecar 后对内容重新执行 512 / 16 MiB。这样 512 个内容文件 + sidecar 能过、513 个内容文件被拒，而 `ZipReader` 仍然是一个与路径名无关的通用读取器。sidecar 的判别式也在 validator 一层，而且必须能一句话判死，否则同一个文件会得到两个答案：**顶层是 JSON 对象且带 `schema_version` 键 → 按 sidecar 处理**（随后 schema 的每一条都能给出具名的 `metadata_*` issue）；**其余一律 `reserved_path`**（这个路径留给平台元数据，而它不是元数据）。判据只看顶层 `schema_version` 这一个键的存在，不看它的值是否合法——合法性属于 `metadata_schema_unsupported` / `metadata_field_invalid`，那两句文案在设计稿里已经存在。

**新建 skill 是一个事务，不是两步，而且锁的顺序与提交逐字相同。** `CreateSkillUseCase` 在 `@Transactional`（默认 READ COMMITTED）里分两段，与 [`SubmitSkillUseCase.java:92-98`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/SubmitSkillUseCase.java) 完全同形：
1. **GC 锁之外**：生成最小 `SKILL.md` 并解析（名字、描述、frontmatter 全部走既有 `SkillDocument`），`Uploads.store` 存 blob——blob 的插入与等待必须发生在 GC 锁之前，否则它与一个「已经存过同一 blob、正在等 GC 锁」的并发提交互相等死；
2. **`versions.underExclusiveWrite(...)` 之内**：建 `skill` 行（M12 的 create-only 入口），再写第一份草稿。**GC 表锁先于 `skill` 行锁**这条顺序不能反，与提交同一条（[`SkillVersionService.java:55-71`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/version/SkillVersionService.java)）。

写第一份草稿用既有的 `SkillVersionService.submit(skillId, skill, manifest, …)`，**不新增 `submitInitial`**：那个方法本来就不检查 `Access`、只要求调用方在 `underExclusiveWrite` 里传进 `skillId`（[`SkillVersionService.java:300-313`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/version/SkillVersionService.java)），正是「M12 刚建出它」这一半；再写一个同义方法就是第二条会漂的路径。任一步失败，三张表一起回滚。

**create-only 的二次读要求 READ COMMITTED。** `ON CONFLICT DO NOTHING` 在并发事务已提交时会返回空，随后的「读含已删除的那一行」必须在同一个快照里看得见那一行；`REPEATABLE READ` 下快照始于事务的第一条语句，这条读会返回空并落进一条不存在的分支。所以这个用例**不设隔离级别**（默认 READ COMMITTED），`REPEATABLE READ` 只属于导出那一条。**重试边界在控制器**：`DraftRecorder.record(() -> createSkill.create(...))` 包住整个用例（见 §接口 3）；用例内部不重试——那会跑在一个已标记 rollback 的事务上（[`DraftRecorder.java:14-32`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/DraftRecorder.java)）。

## 数据模型

**没有迁移。** 不新增表、不新增列、不新增索引、不改约束。用到的既有形状：

| 对象 | 本版的用法 |
|---|---|
| `skill` | 新建 skill 时插入一行：`id=Ulid`、`namespace_id=调用者的个人命名空间`、`name=表单名字`、`title/description/frontmatter` 来自生成并解析过的 `SKILL.md`、`visibility='private'`、`created_by=调用者`、`created_at=updated_at=now`、`current_version_id/deleted_at=NULL`（[`V1__baseline.sql:327-343`](../../../skillmaster-server/src/main/resources/db/migration/V1__baseline.sql)、[`SkillRepository.java:50-71`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/skill/internal/SkillRepository.java)） |
| `skill_version` | 第一份草稿：`id=<8 位短码>`、`number=1`、`version=NULL`、`draft_name=表单版本名称`、`changelog=''`（列是 `NOT NULL DEFAULT ''`；读回为 null）、`digest/file_count/total_bytes` 来自只含 `SKILL.md` 的 manifest、`state='draft'`、`submitted_by=调用者`、`submitted_at=now`、`source='web'`（沿用 v8 的取值，[`CreateDraftFromVersionUseCase.java:56`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/CreateDraftFromVersionUseCase.java)） |
| `version_file` | 一行：`SKILL.md → blob_sha256`；主键 `(version_id, relpath)`（[`V1__baseline.sql:421-431`](../../../skillmaster-server/src/main/resources/db/migration/V1__baseline.sql)） |
| `blob` / `blob_content` | 生成的那份 `SKILL.md` 的字节；按内容寻址，重复内容复用同一行（[`BlobStore.java:16-46`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/blob/BlobStore.java)） |

**同名冲突由唯一的 `UNIQUE (namespace_id, name)` 裁决，它包含已软删除的行。** 因此「新建」不能用 `findOrCreate`（那个方法对 live 行返回既有 id，正是提交想要的语义，不是创建想要的）。M12 新增一个 create-only 方法：`INSERT … ON CONFLICT (namespace_id, name) DO NOTHING RETURNING id`；返回空时再读**含已删除**的那一行——`deleted_at IS NULL` → 409 `skill_already_exists`，非空 → 409 `skill_deleted`。两步在同一事务里，唯一约束是仲裁者（[`SkillRepository.java:50-71`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/skill/internal/SkillRepository.java)）。

**保留路径不是表，是一条规则。** 常量 `".skillmaster/metadata.json"`（`ReservedPaths.SIDECAR`，M5）。它：

- 在**上传**里：被 validator 从内容集合中剥出、按 sidecar 解析；不计入 512 / 16 MiB，不进 manifest、不进 digest；
- 在**写路径**（`write` / `mv` / `cp` 的目标）里：拒绝，400 `reserved_path`；**删除**与**重命名的来源**不拦——既有数据要能被清理。检查**不放进** `SkillPaths.check`（那条被 `ZipReader` 与删除、改名的来源共用，放进去会让 sidecar 永远传不上来、也让旧数据删不掉），而是新增一个只给目标用的判定（`ReservedPaths.requireNotReserved`），在 `DraftFileWrite` 的写 / `mv` 的 `to` / `cp` 的 `to` 上调用；
- 在**从底稿新建草稿**（`CreateDraftFromVersionUseCase` 的复制）里：**照原样带过去，不拦也不丢**。那条路不经过 validator，而这个路径在既有版本里是普通内容（v9 之前它本来就没有特殊含义）；拒绝会让一个含它的旧 live 版本**永远不能从编辑器开新草稿**（只能从它建草稿，而建草稿又拒绝——死锁），静默丢弃则等于改了用户看不见的内容。两者都比「保留」更糟。新版本若再被导入，仍按上传规则具名拒绝——**除非那条历史文件本身就是 sidecar 的形状**（顶层带 `schema_version` 的 JSON 对象），那时判别式把它当元数据剥掉、导入成功：这是同一个判别式在两条规则之间唯一可能的取舍，反过来会让平台自己导出的包永远不能再导入（PRD 做 #9 的例外）；
- 在**解压到本地**时：照常写出（它是包的一部分），由 CLI 当普通文件落盘。

**sidecar 的 schema**（这也是「解析成合法元数据」的判据）：

```json
{
  "schema_version": 1,
  "version": { "name": "补一份参考", "note": "把 split 的范围语法写成文档。" }
}
```

- 顶层只允许 `schema_version` 与 `version`；`schema_version` 必须是整数 `1`；`version` 必须是对象，只允许 `name` / `note`，两者都是字符串；
- `name` / `note` 的长度与空白规则不在解析器里做，而是交给既有的 `VersionLabels.name` / `note`——那是全系统唯一一份口径（1–40 / 0–200 码点）；
- 未知字段、不认识的 `schema_version`、类型不对 → `IngestException` + `invalid_upload`，details 里带具名 issue（见 §接口 §6）；
- **请求里的非空值优先**；空字符串与没给同义；两者都没有 `version.name` → 400 `draft_name_required`；`note` 同一条规则，空 → 无说明；
- 内容相同时幂等返回既有版本：**格式校验照常先跑**（坏 sidecar 仍具名拒绝），但名称与说明**不再应用、也不覆盖**既有版本（PRD 做 #9）。

**错误信封的形状不变。** `ApiError.Detail` 仍然是 `(field, issue)`；两个名字用既有的「值放在 issue、field 说明它是哪个值」约定：`Detail("address_name", "pdf-tools")`、`Detail("content_name", "split")`，外加 `Detail("files[0].relpath", "name_mismatch")`。先例是 `Detail("state", row.state())` 与 `Detail("current_digest", …)`（[`SkillVersionService.java:256-273`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/version/SkillVersionService.java)）。

## 接口

### 1. 整包导出

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `GET` | `/api/v1/author/skills/{namespace}/{name}/archive` | 线上版本（`Latest`）整包 | Bearer + `skills:read`；`browse`+ |
| `GET` | `/api/v1/author/skills/{namespace}/{name}/versions/{version}/archive` | 指定版本整包；`{version}` 接受版本号、digest、8 位版本码 / 26 位 ULID | 同上 |
| `GET` | `/web/skills/{namespace}/{name}/archive` | 网页直链；`{name}` 可带 `@<pin>`，与网页其它路由同一套地址形状 | 会话（GET 不需要 CSRF） |

- **状态口径与「查看文件」一致**：线上/历史 `browse`+；草稿 `write`+；已下架 `maintain`+。**已丢弃即使 `maintain` 看得见也拒绝下载**（400 `invalid_version_state`）——产品里那一态不展示文件，CLI 与网页同一口径。
- **响应**：`200 application/zip`；`Content-Length` 是临时文件的字节数；`ETag: "sha256:<digest>"`（强 ETag，digest 来自本次打包的 manifest）；`Content-Disposition: attachment; filename="<ascii fallback>"; filename*=UTF-8''<percent-encoded>`；`Cache-Control: private, no-store`。不处理 `If-None-Match`（没有缓存层要服务，见 §开放问题 Q4）。
- **包的形状**：内容条目直接以 manifest 的 `relpath` 命名，**没有外层目录**；标准 `ZipOutputStream`、无密码、deflate；sidecar 最后写；**manifest 里已经有这个路径时，不渲染 sidecar**，那个条目按普通内容流出（PRD 做 #9）。
- **文件名**：`<name>@<版本号>.zip`（已发布**含已下架**、有版本号时）、`<name>@<版本码>.zip`（草稿，以及没有版本号的已发布行）。**文件名与上屏文案里用「版本码」这个词，不用「行 id / 短码」**——[`design/voice-spec.md`](../../../design/voice-spec.md) 把「行 id」列为禁用的叫法（这条只约束面向人的字；文档内部讲实现时仍会用「短码」「row id」，那是指同一个东西）。没有版本号的已发布行也用版本码而不是 `sha256:<digest>`：文件名里不该出现冒号（Windows 不接受），而地址里的 digest 钉是另一件事（见 Q5）。**`<ascii fallback>` 用 `ContentDisposition.attachment().filename(...)` 生成**——名字允许 `"`（`requireUsableName` 只禁空白、`/`、`\`、`@`、`%`、`;`），手拼会写出一个坏掉的头；`filename*` 那份按 UTF-8 百分号编码。服务端接受既有的全部 pin 语法（版本号 / digest / 版本码）；CLI 的帮助只列版本号与版本码（PRD 的地址语法），digest 钉属于既有的服务端能力，不在本版新增。
- **错误**：404 `skill_not_found`（不存在/不可见，一个答案——**低于该状态的门槛也是这个答案**：`authorVersions` 按档过滤，看不见的行与不存在的行不可区分）；403 `forbidden`（连 `browse` 都没有，`ReadAuthorVersionUseCase.accessOf` 抛的那一条）；**400 `invalid_version_state`**（已丢弃；沿用既有的 `VersionStateException` 映射，不新造 409 路径）；500 `internal_error`（manifest 里的 blob 读不出来——在发字节之前发生，客户端拿到的是信封）。

### 2. 网页导入（两个入口）

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `POST` | `/web/skills/{namespace}/{name}/versions/import` | 给已有 skill 加一版 | 会话 + CSRF；`write`+ |
| `POST` | `/web/skills/import` | 带进个人命名空间；没有就建 skill | 会话 + CSRF；任何登录账号 |

- **请求**：`multipart/form-data`，part 与 CLI 相同——`file`（zip）、`name`（版本名称）、`note`（可选）。不是 `@RequestParam`，用 `@RequestPart`，与 [`SkillsController.java:306-371`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/SkillsController.java) 同形。
- **网页这条路名称必填**（PRD 做 #4/#5、设计稿「网页不解析压缩包」）：两条 `/web` 导入路由在控制器里要求 `name` 非空，缺/空 → 400 `draft_name_required`；**sidecar 的名称兜底只属于 CLI / API 的提交用例**，网页不把包里的 `version.name` 当名称来源。`note` 留空仍可由 sidecar 提供（设计稿明写「说明留空 = 没给」）。
- **实现**：控制器把 `MultipartFile.getBytes()` 交给既有的 `SubmitSkillVersionUseCase.submitVersion(...)`（返回 `Optional<SubmittedSkill>`，空即 404）/ `SubmitSkillUseCase.submit(...)`（返回 `SubmittedSkill`），再用 `SubmitResponse.of(...)` 序列化。**两处都必须像 [`SkillsController.java:322`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/SkillsController.java) 那样包一层 `DraftRecorder.record(...)`**——短码主键碰撞时换事务重试；直接调用会让一次碰撞变成 500。**没有第三条提交管线。**
- **响应**：201（本次插入了版本）/ 200（幂等重放），体是 `SubmitResponse`。线上字段是 snake_case（`application.yml:33` 的 `property-naming-strategy: SNAKE_CASE`）：`created`、`skill_created`、`namespace`、`name`、`version.draft_id`（草稿的版本码）、**`version.name`（这里是发布后分配的版本号，不是 sidecar 里的版本名称；后者在 `version.draft_name`）**、`version.state`、`version.digest` 等——**没有 `version.code`**（那是 `AuthorVersionResponse` 的字段）。页面靠 `created` 决定是否跳转，靠 `state` 决定「内容已存在」那一行给什么链接（PRD 做 #4）。
- **错误**：与 CLI 共享同一个信封；`name_mismatch` 额外带 `address_name` / `content_name`；`skill_deleted` 见 §6。
- **`skill_deleted` 的入口差异**：个人命名空间导入会命中它（名字仍被已删除行占着）；版本列表导入面对已删除 skill 时在 `accessFor` 就返回空 → 404 `skill_not_found`（[`SkillRepository.java:285`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/skill/internal/SkillRepository.java) 的 `deleted_at IS NULL`）。

### 3. 网页新建 skill

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `POST` | `/web/skills` | 一次事务建出 skill 与第一份草稿 | 会话 + CSRF；任何登录账号（个人命名空间） |

```json
{ "name": "pdf-tools", "description": "把 PDF 拆页、合并与压缩。", "version_name": "第一版" }
```

- **成功** 201 + `SubmitResponse`：`namespace` 是调用者的个人命名空间 slug，`name` 是表单名字，`version.draft_id` 是这条草稿的 row id；页面据此跳到 `/skills/{namespace}/{name}@<draft_id>/edit`。
- **重试边界在控制器**：`WebImportController` 用 `DraftRecorder.record(() -> createSkill.create(...))` 包住整个用例（与 [`SkillsController.java:322`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/SkillsController.java) 同形）；短码碰撞时整段事务回滚并重跑，`skill` 行与草稿一起重来。不要把它写成用例内部的重试——那会跑在一个已标记 rollback 的事务上。
- **第一份草稿的内容**：一份最小的根 `SKILL.md`，frontmatter 的 `name` / `description` 就是表单填的两个值，正文是 `# <title>`（title 取名字）。模板从 v8 的空底稿生成器抽出（[`CreateDraftFromVersionUseCase`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/CreateDraftFromVersionUseCase.java) §数据模型「空底稿」），两份代码共用一个 `SkillMdTemplate`，YAML 双引号转义也在那一处。解析之后组装成一个**只有一个 `SKILL.md` 的 `SkillUpload`**，再走与上传同一个 `Uploads.store` / `Uploads.metadataOf`（`SkillUpload` 是普通 record，直接构造；这样「存字节的顺序」与「元数据来自版本」两条不变量不出现第二份实现）。
- **名字校验**：生成模板后走 `SkillDocument.parse`，所以 `name_too_long` / `name_not_usable_as_a_directory` / `name_contains_version_separator` / `name_contains_unaddressable_char` 与提交同一条实现；`description` 仍是必填（`required_field_missing`）；`version_name` 走既有的 `VersionLabels.name`（1–40 码点、`draft_name_required` / `draft_name_too_long`）。
- **错误**：**名字与描述**走 `SkillDocument`，因此与上传同形——400 `invalid_upload` + `details[].issue`（`name_too_long` / `name_not_usable_as_a_directory` / `name_contains_version_separator` / `name_contains_unaddressable_char` / `required_field_missing`）；**`version_name`** 走 `VersionLabels`，是**顶层码** 400 `draft_name_required` / `draft_name_too_long`（[`VersionLabels.java:34-41`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/VersionLabels.java)）；另有 409 `skill_already_exists`（同名 live）、409 `skill_deleted`（同名已删除，附 `去恢复`）；不泄露「别人有没有这个名字」——创建只发生在自己的命名空间。
- **事务**：见 §架构。审计写一条 `submit`（target `skill`，与 [`SubmitSkillUseCase.java:109-111`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/SubmitSkillUseCase.java) 同形，`created=true`），与变更同一事务（[`AuditLog.java:5-11`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/audit/AuditLog.java)）。

### 4. CLI

**`skill download <命名空间>/<名字>[@版本 | @版本码] [--into <目录>]`**

- 地址按第一个 `@` 切开；前半用既有的 `api.SkillPath` 校验，后半只要求非空（版本号、digest、短码、ULID 都由服务端解析）。
- **下载不预解析**：不复用 `resolve` / `fileTarget` 那条「没有钉就先问 `AuthorVersions` 再问 `AuthorVersion`」的路径——那条路为的是拿到一个具体版本再取单文件，而整包路由自己就能解析「不带钉 = 线上」。所以一次下载**恰好一次** `GET …/archive`（不带钉时不带 `/versions/{pin}`），验收 #11 量的就是这个。
- **目标**：省略 `--into` → `./<名字>`；已存在且非空 → 拒绝、非零退出、零写入；已存在的空目录 → 先解到 `<目标>.partial`，成功后 `rename` 到它（POSIX 可直接替换空目录）；平台不支持覆盖目录时（Windows），先在父目录下造一个 `.skillmaster-replaced-<rand>`，把空目录改名进去，`rename` 成功后再删备份，任何一步失败都把备份改回来——**用户原有的空目录在成功之前不被删除**。`.`、`..`、根目录一律拒绝（不拿用户的工作目录做替换目标）。
- **落盘**：响应先写临时文件（`os.CreateTemp`，上限 `16 MiB + 1 MiB` 的 `io.LimitReader`），再用 `archive/zip` 读；`path.Clean` 后拒绝绝对路径与 `..`；目录项跳过；文件 `0644`、目录 `0755`；成功后整体 `rename`，失败时删掉临时目录与临时 zip。
- **输出**：`下载 <ns>/<name>@<pin>  <digest>`、`N 个文件  N 字节`、`已写到 <path>`。digest 取自响应头 `ETag`（去引号）。**`N 个文件` 是内容文件的个数**——`<pin>` 省略时 CLI 拿不到版本名，所以这一行与 `skill ls` 的「文件数」用同一口径（不含 sidecar），T1 就是拿这两处比的。**判别式与上传同一条**（`.skillmaster/metadata.json` 上是 ≤64 KiB、带顶层 `schema_version` 的 JSON 对象 → 元数据）：历史版本若把**这个形状**的文件当内容存着，CLI 与服务端无从区分，这一角报 N−1 而 `skill ls` 列 N；普通文本的历史文件仍算内容，计数一致。
- **失败**：`statusError` 的既有映射（401/403/404），加上 `invalid_version_state` 的中文句；退出码 1。

**`skill submit` 的变化**

- `--name` 变为可选：给了非空值就作为 multipart 的 `name`；给了空串等于没给；没给就不发这个 part。服务端在两者都没有时答 `draft_name_required`（既有中文映射）。
- 新增 `skill_deleted` 的中文句与退出码 1；`--to` 的地址式提交遇到已删除 skill 仍是 404（与版本列表导入同一答案）。
- 帮助文本与 README 同步。

### 5. sidecar 的解析与渲染

- **解析**（M5）：`SkillSidecar.parse(byte[])`。手工读 JSON 树（Jackson `readTree`），按 §数据模型 的 schema 逐键检查；不做长度校验（交给 `VersionLabels`）。任何不合格 → `IngestException`，details 里带下面的 issue。
- **渲染**（M5）：`SkillSidecar.render(String name, String note)` → UTF-8 JSON 字节；`note` 为空时省略该键；**`draft_name` 为 null 时省略 `name` 键**（v6 之前的老行没有名称，再提交时 `--name` 仍要给，PRD 验收 #2 的「包里带着」对这类行不成立）。
- **大小**：64 KiB 是 reader 给保留路径的**内部余量**，不是产品上限，也不产生一个用户可见的错误码。合法的 sidecar 被 `VersionLabels` 的 1–40 / 0–200 码点压得很小；写大了的名称 / 说明由 `VersionLabels` 给出既有的 `draft_name_too_long` / `note_too_long`。整个包超过 reader 的余量时是既有的 `too_large`（包确实过大）；**没有 `metadata_too_large` 这个码**——那会是一个设计稿里没有文案、实际也几乎到不了的分支。
- **在 validator 里的顺序**：`ZipReader.read`（余量）→ `stripSingleRootDirectory` → 找保留路径并移除 → `SkillSidecar.parse` → 找根 `SKILL.md` → `SkillDocument.parse` → 目录名检查 → **内容**的 512 / 16 MiB 上限。sidecar 格式错误先于「缺 `SKILL.md`」报出——包自己的声明坏了，先修它。
- **返回值 `ValidatedUpload`**（M5）：`(SkillUpload upload, SkillSidecar sidecar)`——`upload` 是剥掉保留路径之后、通过了 512 / 16 MiB 的内容集合（`skill_version.file_count` / `total_bytes` 与 manifest 都从它算），`sidecar` 可以为 `null`（包里没有保留路径）。这个名字也是两个提交用例与 `Uploads.store` 之间新的传递类型。

### 6. 错误码与 issue

| 状态 | code | 何时 |
|---|---|---|
| 409 | `skill_deleted`（新） | 名字被已删除的 skill 占着；**替换**今天 `SkillDeletedException` 的 400 `invalid_request`（[`ApiExceptionHandler.java:157-161`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/ApiExceptionHandler.java)） |
| 409 | `skill_already_exists`（新） | 新建 skill 时同名 live skill 已存在 |
| 400 | `reserved_path`（新） | 写 / 移动 / 复制的目标落在 `.skillmaster/metadata.json` |
| 400 | `invalid_version_state`（既有） | 下载已丢弃的版本（`VersionStateException` 既有映射就是 400，[`ApiExceptionHandler.java:259-263`](../../../skillmaster-server/src/main/java/com/skillmasterai/api/ApiExceptionHandler.java)） |

**issue（`details[].issue`）**：`name_mismatch`（+ `address_name` / `content_name`）、`metadata_unknown_field`、`metadata_schema_unsupported`、`metadata_field_invalid`、`body_too_large`；`ZipReader` / validator 既有的 `not_a_zip`、`empty_archive`、`missing_skill_md`、`name_does_not_match_directory`、`duplicate_relpath`、`symlink_not_allowed`、`too_many_files`、`too_large`、`unreadable`；路径类既有码 `empty_name`、`absolute_path`、`backslash_in_path`、`path_traversal`、`empty_path_segment`。

**保留路径的两层**：**上传侧**是 `invalid_upload` + issue `reserved_path`（设计稿的元数据文案走这条）；**写路径**（`write` / `mv` / `cp` 目标）是顶层 400 `reserved_path`。`metadata_unknown_field` / `metadata_field_invalid` 另带 `Detail("metadata_field", <字段名>)`，网页据此拼设计稿里那两句：「.skillmaster/metadata.json 有无法识别的字段：<字段>。」与「.skillmaster/metadata.json 的 <字段> 类型不对。」。

### 7. 权限与状态矩阵

| 版本状态 | 看得见的人 | 能下载的人 |
|---|---|---|
| 线上 / 历史（published） | `browse`+ | `browse`+ |
| 草稿（draft） | `write`+ | `write`+ |
| 已下架（revoked） | `maintain`+ | `maintain`+ |
| 已丢弃（discarded） | `maintain`+ | **没有人**（400 `invalid_version_state`） |
| `invoke` | 只有线上 L1/L2/L3 | 不能下载整包 |

### 8. 网页适配器

- **请求层**：`request()` 增加 `FormData` 分支——不设 `Content-Type`（浏览器自己带 boundary），仍从 cookie 读 CSRF 并写 `X-XSRF-TOKEN`；上传的 `timeoutMs` 单独放宽到 120s（[`client.ts:91-165`](../../../skillmaster-web/src/api/client.ts)）。`stubFetch` 同步加一个 `FormData` 记录分支。
- **API 函数**：`createSkill(name, description, versionName)` → `POST /web/skills`；导入是**两个**函数而不是一个带可选参数的：`importVersion(namespace, name, file, versionName, note)` → `POST /web/skills/{ns}/{name}/versions/import`，`importSkill(file, versionName, note)` → `POST /web/skills/import`。两者都发 multipart（`file` / `name` / `note`，说明为空时不发这个字段），都返回 `SubmitResponse`。
- **错误上屏**：导入的失败走 `details[].issue` 到中文的独立映射（`invalid_upload` 的通用句会先命中，不能经过 `codeMessage`）；`name_mismatch` 从 `details` 读 `address_name` / `content_name` 拼句子；`skill_deleted` 给 `去恢复`（`/skills?show=deleted`），`skill_already_exists` 给 `查看 <名字>`（`/skills/{namespace}/{name}`）。**新建 skill 的字段错误与导入一样是 `details[].issue`**（`SkillDocument` 抛 `IngestException`，`ApiExceptionHandler` 落成 `invalid_upload` + details，见 [`SkillDocument.java:109-131`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/ingest/SkillDocument.java)），**不是顶层 `error.code`**。文案逐字来自 [`design/README.md:658`](../../../design/README.md)：`name_too_long` →「名字不能超过 64 个字符。」、`name_not_usable_as_a_directory` →「名字里不能有空格或路径分隔符。」、`name_contains_version_separator` →「名字里不能有 @。」、`name_contains_unaddressable_char` →「名字里不能有 % 或 ;。」、`required_field_missing` →「名字和描述都要填。」——它们和导入的句子一起放进 `errors.ts` 的同一张 `TRANSFER_ISSUES`；**`required_field_missing` 是新建表独有的**：包里的 `SKILL.md` 漏了 `name` 也会报这个 issue，但导入表没有那两个字段，所以导入这一路跳过它、回落服务端的原句。网络那两句也**按表分**：导入写「导入失败：服务器没有响应，文件没有导入。重试一次。」，新建表没有文件，用 `CODE_MESSAGES` 里既有的网络句。
- **动作菜单**：把行菜单里的那一套（`menu-button` 触发 + `menu-list[role=menu]`，上下键 / Esc / 外点 / 滚动关闭）抽成 `components/CommandMenu.vue`，两颗「新建 ▾」共用；Home / End 是现有组件的既有行为，留着不算新增。**resize 的两种行为都保留**：行菜单关闭，两颗「新建 ▾」重定位（设计件自己的注释：「Resizing repositions, scrolling closes」）。抽出来后**列表的 keydown 要 `stop`**——列表在触发器的包裹元素里，不拦冒泡时方向键会同时跑触发器的处理函数、把菜单重新打开并回到第一项（键盘根本到不了第二项）。分组为空时不画那个 `.menu-group`（设计件里有同样的跳过），否则行菜单末尾会多出一条分隔线。
- **两张表**：`ImportDialog.vue`（**压缩包**、版本名称、说明、错误 / 结果；标题是「导入 skill」或「导入新版本」，引子与字段说明逐字照设计件）与 `NewSkillDialog.vue`（名字、描述、版本名称；标题「新建 skill」）；`.confirm` 壳与 CSS 从设计件搬，`ConfirmDialog` 的单 textarea 装不下它们。**两张表关闭时把焦点还给打开它的那颗「新建 ▾」**（设计：Esc 与取消同答案、焦点回到原处）——它们只 `emit('close')`，由页面通过 `CommandMenu` 暴露的 `focusTrigger` 收回焦点。
- **列表空态**：`SkillsPage.vue` 的空态句子与「新建 skill ▾」的显隐按设计稿改（今天是「还没有提交过 skill。在本机运行 `skillmaster skill submit`…」，设计稿要求「新建一个，或运行 `skillmaster skill submit`」，且空态里也有菜单）——这是本版改动清单里的一行，不是顺手。**空态里计数、搜索、排序与两份列表的标签都不画**（设计那句「搜索与排序在空态里没有意义，所以它们仍然不画」，以及它的 `empty` 状态里整行 `list-tabs` 也不在）：条件是 `state === 'ready' && skills.length > 0`，所以「有 skill 但筛不到」仍是 `ready`，控件留着好让人清掉筛选。**例外是一处设计稿没画到的组合**——在「已删除」列表里且已删除也为空时，标签仍然画：那是回到「在用」的唯一入口，藏掉就把人困住了；设计件把 `empty` 当整页状态是演示开关，产品里它是两份列表各自的结果。实现条件是 `skills.length > 0 || show === 'deleted'`。
- **落地**：**导入**在 `created=true` 时去**版本页** `/skills/{namespace}/{name}?tab=versions&version=<版本码>`——**不是** `/skills/{ns}/{name}@<pin>`：路径里的 `@` 只是把这一版钉给页面用，默认落在「信息」；版本自己的那一页（标题、状态、操作）是查询参数 `?tab=versions&version=`（`VersionEditorPage` 与之同理）；**新建 skill** 在 `created=true` 时去**编辑页** `/skills/{namespace}/{name}@<draft_id>/edit`。`created=false` → 弹窗留着，按 `version.state` 给链接：`ImportDialog.vue` 的 `suffixOf` 用与前端既有 `versionSuffix()` 同一条规矩——published 有版本号写 `@<version.name>`、没有写 `@sha256:<digest>`；draft 写**裸版本码**（设计件的 `查看 7K3M9QX2`，不带 `@`）；**读得到的已下架**同样给链接（版本号或 digest）；读不到的不给。**「读得到」由导入方所在页面的档位回答**——响应里没有可读性这个事实：版本页把 `skill.rung` 传给弹窗，「我的 skill」传 `owner`（包只会落进自己的命名空间）；已丢弃一律不给，与档位无关（设计把「已丢弃」直接算作读不到）。
- **下载**：`VersionDetail.vue` 加 `downloadHref`（与 `filesHref` 同条件），`VersionHeader.vue` 按「查看文件 → 下载 → 编辑文件」插入；链接是 `/web/skills/{ns}/{name}@<pin>/archive`，`<a>`，不经 `request()`。
- **文件栏表头的两颗动作（2026-10-21 补）**：右端加「下载」与「新建版本」（后者只有文字、居中，不带加号与箭头；`aria-haspopup` 仍在），与版本页的「下载」、版本列表的「新建版本」**同条件、同一条归档路由、同一张菜单**（pin 的写法可能不同：文件栏用版本的规范后缀，版本页用它地址里的钉）——没有新的服务端路由，也没有第二套菜单实现。下载取的是 `shownVersion`（屏幕上的那一版），`shownVersion === null` 时不画；新建版本沿用 `canWrite`，导入表记录是哪一个触发器打开的，关闭后焦点回到它。设计口径见 [`design/README.md`](../../../design/README.md) §一个 skill。
- **文件栏**：落实设计的那一条——「已丢弃不展示文件，不列；地址钉住一版读不到的版本时，它作为禁用项出现」（[`design/README.md:1218`](../../../design/README.md)）。三处一起改：
  1. `pinnedFileVersion` 不再把已丢弃算作可读（今天它会在 `visibleVersions` 里命中）；
  2. `pickerVersions` 仍把地址钉住的那一行塞回选项，但 `disabledPickerValues` 要把已丢弃算作禁用（今天 `readableBy(discarded, maintain)` 为真，它是个正常选项）；
  3. `shownVersion` **对钉住的已丢弃版本不回落**到线上版本——今天钉住的版本读不到时它会退到 `liveVersion`，于是「已丢弃版本的地址」下显示着线上版本的文件，选择器说一套、文件说另一套。v9 让这一屏的文件区为空、**不画像任何一版的文件**；头部仍用钉住那一行的身份（名称 / 说明 / 版本码），这样选择器与文件区说的是同一版。**这一条只管已丢弃**：钉住一版读不到的**草稿 / 已下架**时仍回落到线上（与既有行为一致），下载跟着屏幕上那一版走。

  **不给下载**：「这一态不展示文件」包括不为它提供下载入口。这一屏文件区为空时的那句文案，设计稿没有写（它只说「作为禁用项出现」），属于下一版前要补的一处设计（见 §开放问题 Q8；本版没有补，文件区留空），实现里**不自拟**。

## 分期

| 期 | 交付什么 | 验收什么 |
|---|---|---|
| **P0 服务端** | sidecar 解析/渲染与 validator 的剥离、reader 余量、保留路径写入拒绝、名字码点；`SkillDeletedException` → 409 `skill_deleted`，另加 `skill_already_exists` / `reserved_path` 两个新码；M12 create-only、`CreateSkillUseCase`（M7 用既有的 `submit`）；`ArchiveSkillVersionUseCase` + M9 `archive`；作者面两条与网页面一条导出路由、网页两条导入路由、`POST /web/skills`；IT | prd #1–#3（服务端一半）、#5–#10、#11（服务端一次出包）、#12、#16–#19；`SkillTransferIT` / `ArchiveIT` / `CreateSkillIT` |
| **P1 CLI** | `skill download`（不预解析）、`--name` 可选、`submit.go` 的 409 分支与 `skill_deleted` 文案、帮助与 README；CLI 测试 | prd #1–#3（CLI）、#11（CLI 一次请求）、#17；`go test ./...` |
| **P2 网页** | 动作菜单组件、导入与新建 skill 两张表、`FormData` 支持、下载直链、文件栏表头的下载与新建版本（2026-10-21 补）、文件栏不再展示已丢弃、错误映射、CSS 移植、组件测试 | prd #4（覆盖 做 #3 的文件栏那一句）、#5–#8（页面）、#11（直链一次 GET）、#13–#15、#18–#20（页面）；`npm test` |
| **P3 文档与收口** | 四条 ADR（0068–0071）落盘后对 0050 / 0055 / v7 TD 的文件头修订注记、全量回归、人看设计件 | prd #15；`check_docs.py`、设计检查、视觉验收由人做 |

**P0 之前（文档阶段）**：prd 依赖一节列的四条 ADR（0068–0071）先落盘——它们定义的是**跨版本**的取舍（一次性快照取代 v7 TD 的绝对禁令、sidecar 不是内容、建 skill 与第一份草稿是一个动作），P0 的实现以它们为前提；P3 只剩对既有 ADR 文件头的引用注记。

**设计稿的回写原分两批，本版两批都没有补**——实现已在没有它们的情况下完成，按「缺句子不自拟」回落（Q6 编辑器的写路径拒绝回服务端原句，Q7 那几类导入拒绝回服务端原句），所以它们从「写代码的前置」转成**下一版之前的设计债**：

- **Q6**：补 `editor-reserved-path` 一态并重出图——写路径的拒绝已经生效，编辑器那一态现在只有回落句。
- **Q7 / Q8**：补一部分导入拒绝的上屏句子，以及文件栏「钉住已丢弃」时文件区的写法。

补的时候都只加态 / 加句子，不改已锁的其余部分。

**每期明确不做**：P0 不碰发布 / 回滚 / 下架 / 丢弃的状态机，也不改消费面；P1 不做本地工作区、不做覆盖已存在目录；P2 不做拖拽上传、不做文件夹上传、不做进度条；P3 不重写 v7 TD 正文（只在文件头加引用）。

## 开放问题

### 已定（实现时按此，实测确认）

| # | 问题 | 决定 | 怎么确认 |
|---|---|---|---|
| Q1 | 整包在服务端用临时文件还是内存？并发下载要不要设上限？ | 临时文件：错误边界干净、并发不放大堆；代价是清理。**不设并发上限**：单次最多约 16 MiB，导出是登录后的显式动作，频率受会话与既有限流约束；把它写成明确接受的风险，而不是假装没有。 | 用 16 MiB 夹具量两种实现的峰值与并发 5 个下载时的堆；临时文件胜出则保留，清理用 `AutoCloseable` + body `finally` + 服务内的失败清理。**重新考虑的信号**：日志里出现同一账号短时间内多次导出，或临时目录占用超过磁盘告警阈值——那时再加信号量或队列，并把超限答成 429。 |
| Q2 | reader 的余量放多少、语义上限放哪？ | reader 多放 1 个文件 / 64 KiB，`SkillUploadValidator` 对内容重新执行 512 / 16 MiB。 | 边界用例：512 内容 + sidecar 接受；**513 内容不带 sidecar（513 个条目）由 validator 报 `too_many_files`**；带 sidecar 的 513 内容（514 个条目）由 reader 先报——两种都是具名拒绝；16 MiB 内容 + sidecar 接受、超 1 字节拒绝。 |
| Q3 | 新建 skill 的建行入口：给 `findOrCreate` 加参数，还是新增只建不取的方法？ | 新增 M12 `createOwned`：`ON CONFLICT DO NOTHING` + 读含已删除行，语义显式。 | 并发两个同名创建，断言恰好一个 201、一个 409；再断言拒绝后 `skill` 行数不变。 |
| Q4 | 导出响应要不要支持 `If-None-Match` / 304？ | 不做：没有缓存层，ETag 只用于往返核对。 | T1 会确认 ETag 等于 `"sha256:" + manifest digest`（实现后）；是否加 304 留给将来有缓存需求时再定。 |
| Q5 | 没有版本号的已发布版本，文件名用什么？ | 这个版本的**版本码**，与草稿同一口径；不用 `sha256:`——文件名里不要冒号（Windows），而地址里的 digest 钉照旧可用。 | 造一个无版本号的已发布版本，下载看 `Content-Disposition` 与 CLI 输出。 |

### 仍开放（每条是一个能回答的问法）

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q6 | 编辑器里新建 / 重命名到保留路径，上屏文案放在哪？ | 新增顶层 `reserved_path`（400），并**新增一个编辑器状态 `editor-reserved-path`**（不复用 `editor-new-file-error`——那个状态说的是「路径占用」，语义不同）。**句子本身由设计稿定**：导入那条已有的「压缩包里有 .skillmaster/metadata.json；这个路径留给平台元数据，不能作为 skill 的内容。」是同一件事的现成口径，编辑器用它的哪一半、要不要改主语，是这次回写的内容。 | **本版没有补**：编辑器这一态先回落服务端原句（`CODE_MESSAGES` 里故意不给 `reserved_path`），不自拟。补态是这一版之后的设计债：补进设计稿、加 SHOT 并重出图，再以 `console-states --only editor` 钉住。 |
| Q7 | 一部分导入拒绝（非 zip、空包、顶层目录名与 `name` 不符、超过 512 文件、解压后超过 16 MiB、重复路径、符号链接，以及反斜杠这一类路径）的**上屏句子**写什么？ | 设计稿只写了「每条具名原因都要有上屏文案」并给了其中几类的句子，这几类没有句子（[`design/README.md:644`](../../../design/README.md)）。**实现里不自拟**：照设计稿的口气补进 `design/README.md` 的导入一节与演示件的 `import=error` 变体。 | **本版没有补**：这几类先回落服务端原句（`importFailure` 在 `TRANSFER_ISSUES` 查不到时给原句），不自拟；T9 只验「有具名 issue」，T15 不验这几句。补态是设计债：补进设计稿并重出图，再以 `console-states --only versions-import,skills-import` 钉住。 |
| Q8 | 文件栏钉住一版已丢弃的版本时，文件区（表头以下）写什么？ | 设计只说「它作为禁用项出现」，没写文件区。倾向：文件区为空，头部保留那一版的名称 / 说明 / 版本码；空区的句式需要设计稿给。 | **本版没有补**：文件区为空，头部保留那一版的名称 / 说明 / 版本码（不多写一句话）。补态是设计债：与 Q7 同一轮回写。 |

## 附录

### 附录 A · 与现有代码的关系

**保留不动**：`Manifest` 的 digest 公式、`ZipReader` 的路径与上限检查、`SkillUploadValidator` 的根目录剥离、`Uploads` 的存储顺序、`SkillVersionService` 的提交 / 建行 / 发布状态机、`ReadAuthorVersionUseCase` 的按档解析、`SkillDistributionService` 的单文件流、两条 `/web` 安全链。

**新增**：
- `modules/ingest/SkillSidecar`、`modules/ingest/ReservedPaths`、`ValidatedUpload`、`IngestLimits.withRoomForSidecar()`（M5，见 §架构与 §接口 5）；
- `SkillDistributionService.archive` 与 `ArchiveArtifact`（M9）——**不新开 `ArchiveWriter`**：整包与单文件流同属 M9 对 blob 的读出，放进既有的服务里，不新增一个只被一条用例调用的类；
- `modules/skill/SkillMetadataService.createOwned` + `SkillRepository.insertNew` / `findAnyByName`（M12）——返回新行的 id，冲突时按行是否已删除给出两个 409；**M7 不加方法**（既有 `submit` 就是这条）；
- `usecase/CreateSkillUseCase`、`usecase/ArchiveSkillVersionUseCase`（`@Transactional` 只出现在 `usecase`，[`ArchitectureTest.java:86-97`](../../../skillmaster-server/src/test/java/com/skillmasterai/ArchitectureTest.java)）；
- `api/WebSkillController` 加一条 `GET …/archive`（复用它的 `addressOf`，不新开控制器）、`api/WebImportController`（两条导入）与 `POST /web/skills`（新建），作者面 `AuthorSkillController` 两条导出路由；
- `SkillMdTemplate`（从 `CreateDraftFromVersionUseCase` 抽出，v8 与 v9 共用）。

**修改**：
- `SkillUploadValidator` 的返回值与顺序（内容上限移到它这一层）；
- `SubmitSkillUseCase` / `SubmitSkillVersionUseCase` 的名称解析（请求 → sidecar → 缺省），以及 `name_mismatch` 的 details；
- `SkillDocument.requireUsableName` 改按码点；
- `ApiExceptionHandler` 的 `SkillDeletedException` 映射；
- `ErrorCode` 三个新值；
- `DraftFileWrite` 增加一个只给目标用的保留路径判定（`ReservedPaths.requireNotReserved`），在写与 `mv` / `cp` 的 `to` 上调用；**不改 `SkillPaths.check`**（`ZipReader` 与删除 / 改名的来源共用它）；`CreateDraftFromVersionUseCase` 的底稿复制**不动**（见 §数据模型）；
- CLI 的 `skillVerb`、`splitSubmitArgs`、`Submit` 的包，新增 download；**`internal/api/submit.go` 加 409 分支**（今天 409 只落进一句 `服务端答 409`，`classifyFailure` 拿不到 `ServerError`，`skill_deleted` 的中文句就永远命中不了）；
- 网页的 `client.ts`（FormData + 上传专用超时）、`errors.ts`、`VersionFilterBar.vue`、`SkillsPage.vue`、`VersionDetail.vue` / `VersionHeader.vue`，新增动作菜单与两张表；
- 网页文件栏三处一起改（[`SkillPage.vue:344-411`](../../../skillmaster-web/src/pages/SkillPage.vue) 与 [`useVersionActions.ts:95-104`](../../../skillmaster-web/src/composables/useVersionActions.ts)）：`pinnedFileVersion` 不再把已丢弃算作可读、`disabledPickerValues` 把它算作禁用、`shownVersion` 不再回落到线上版本（详见 §接口 8）——这是 PRD 做 #3 那句「这一态不展示文件」的实现，不是顺手：今天按版本码直接打开已丢弃版本时，`maintain` 仍会看到文件。

**已知热点（改动时对照）**：`SkillVersionService` 是 1166 行的入口类，新增能力走它的 `filesOf` / `authorSnapshot`，不要在 controller 里重解析状态；`underExclusiveWrite` 的 GC 锁必须先于 `skill` 行锁（[`SkillVersionService.java:66-71`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/version/SkillVersionService.java)）；`AuthorSkillRoutes.FILES` 的 `{*relpath}` 会吞掉 `/files/` 之后的路径，`/archive` 必须挂在 `/files/` 之外；`SubmitResponse.created` 与 `skill_created` 已经区分「新版本 / 新 skill / 重放」，不要另造字段。

### 附录 B · 来源与出处

- **512 文件 / 16 MiB**：Agent Skills 扩展（§1.3）的服务器建议上限，见 [`IngestLimits.java:4`](../../../skillmaster-server/src/main/java/com/skillmasterai/modules/ingest/IngestLimits.java) 的类注释；不是本版新测的数字。
- **32 MB**：Spring multipart 的缓冲上限，**不是** skill 上限（[`application.yml:20-28`](../../../skillmaster-server/src/main/resources/application.yml)）。
- 版本 / 地址 / 钉的语义见 [`docs/architecture/model.md`](../../architecture/model.md)；决策见 ADR 0005、0031、0034、0055、0063、0064。

### 附录 C · 已测量的数据

**实现已完成，但性能测量没有做**——这里仍然没有新的数字。要补的是带口径的实测（峰值内存、16 MiB 导出的墙钟、并发 5 个下载时的堆），并注明样本与是否计入零值；功能侧的实测结果在 [`test-plan.md`](test-plan.md) §结果。
