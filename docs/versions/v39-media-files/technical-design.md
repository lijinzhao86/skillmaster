# v39-media-files · 技术设计

> **最后更新**：2026-11-02
> **状态**：设计稿
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

<!--
本章之外的硬约束：
- 测出来的数字必须带口径（见下方表格的「口径」列），让读者能自己复现。
- 引用外部事实必须带出处，且区分一手与二手。
- 不复述 ADR 的理由；不负责任何产品决策的论证。
-->

## 架构

这一版不新增实体，只把「字节从哪来、放哪、怎么出去」这三段补成能承载大文件的样子。

```
浏览器（控制台）                  CLI（skmr skill put/get）           agent
      │  raw bytes + If-Match            │  raw bytes / GET            │
      ▼                                  ▼                            │
┌──────────────────────────────────────────────────────────────┐     │
│ API / Web 层（M9 distribution 的出口，M5 的路径规则）           │     │
│   PUT /…/files/{relpath}   GET /…/files/{relpath}              │     │
└───────────────┬──────────────────────────────────────────────┘     │
                │                                                    │
                ▼                                                    │
┌──────────────────────────────────────────────────────────────┐     │
│ 用例层：WriteDraftFile / WebEditDraftFile / SubmitSkill         │     │
│  · 大文件先落到服务端临时文件，边读边算 sha256 与 UTF-8 探测      │     │
│  · 之后交给 BlobStore；事务与版本变更不变                         │     │
└───────────────┬──────────────────────────────────────────────┘     │
                │                                                    │
                ▼                                                    │
┌──────────────────────────────────────────────────────────────┐     │
│ M6 blob：BlobStore 接缝（ADR 0010 预留）                        │     │
│  RoutingBlobStore ──► PgByteaBlobStore（不超过 1 MiB）           │     │
│                  └──► OssBlobStore（大于 1 MiB）                 │     │
│  blob / blob_content / blob_deletion 三张表由 M6 拥有            │     │
└───────────────┬──────────────────────────────────────────────┘     │
                │                                                    │
                ▼                                                    │
       PostgreSQL（元数据 + 不超过 1 MiB 的小文件）      私有 OSS 桶（大于 1 MiB 的文件）
```

**边界与理由**：

- **版本、权限、寻址一行不改。** 媒体是 `version_file` 的普通一行，`(version_id, relpath)` 是它的身份；`digest`、`@版本` 钉、`latest` 指针全部复用（[ADR 0132](../../decisions/0132-media-files-are-version-files.md)）。
- **存储选择在 M6 内部。** 用例层只看见 `BlobStore`；换后端是配置，不是改发布路径（ADR 0010 的接缝）。
- **路由规则：大于 1 MiB 的 blob 进 OSS，其余留在 PostgreSQL，按大小而不看类型。** 小文件留在库里，`grep -F` 的预筛（`BlobStore.containing`）与 `diff` 的文本路径仍走热路径；大文本若进了 OSS，由 `containing` 流式读取，且 `grep` 另有 16 MiB 上限（[ADR 0130](../../decisions/0130-large-blobs-live-in-object-storage.md)、[ADR 0133](../../decisions/0133-wc-streams-and-is-not-text-capped.md)）。
- **字节永远只经 API 到达。** OSS 桶私有、不发预签名 URL；内容寻址意味着一个 blob 会被不同受众的 skill 共用，ACL 只能在 API 层成立（ADR 0010 的论证仍然有效）。

## 数据模型

### 变更

```sql
-- M6 拥有。现有行全部是 'db'；新行由 BlobStore 的路由规则决定。
ALTER TABLE blob ADD COLUMN storage TEXT NOT NULL DEFAULT 'db';  -- 'db' | 'oss'
ALTER TABLE blob ADD COLUMN location TEXT;                       -- OSS 对象键；'db' 时为 NULL

ALTER TABLE blob
  ADD CONSTRAINT blob_storage_known CHECK (storage IN ('db', 'oss')),
  ADD CONSTRAINT blob_location_matches_storage
    CHECK ((storage = 'db' AND location IS NULL)
        OR (storage = 'oss' AND location IS NOT NULL));

COMMENT ON COLUMN blob.storage IS
  'Bytes live either in blob_content (db) or in the private OSS bucket (oss). The choice is M6''s; no caller may name it.';
COMMENT ON COLUMN blob.location IS
  'The OSS object key, always blobs/<sha256>. Kept in the table rather than recomputed so a future key scheme can change without a migration of the objects.';

-- M6 拥有。OSS 对象的删除不能和数据库事务一起提交，所以先在同一个事务里记下要删的键。
CREATE TABLE blob_deletion (
  sha256     TEXT PRIMARY KEY,
  location   TEXT NOT NULL,
  created_at TEXT NOT NULL
);

COMMENT ON TABLE blob_deletion IS
  'An outbox: rows written in the same transaction that removes a blob row, drained by a sweeper that deletes the OSS object and then this row. At-least-once deletion; a crash leaves the row for the next pass.';
```

### 不动的部分

- `blob_content` 只存 `storage = 'db'` 的字节；OSS 的对象不在库里，**不在数据库备份里**。
- `version_file` 不变：`relpath`、`blob_sha256`、`size`、`is_binary` 已经够描述媒体。
- 文件数上限（512）不变；`IngestLimits` 拆出单文件上限：

```java
// M5 ingest
public static final int  STANDARD_MAX_FILES      = 512;
public static final long STANDARD_MAX_FILE_BYTES = 100L * 1024 * 1024;  // 新
public static final long STANDARD_MAX_BYTES      = 512L * 1024 * 1024;  // 原 16 MiB
```

`STANDARD_MAX_BYTES` 今天被 `AuthorSkillWriteController.bodyOf` 同时当成「单文件上限」和「整包上限」用；拆开后前者改用 `STANDARD_MAX_FILE_BYTES`。

### OSS 对象

| 项 | 值 |
|---|---|
| 桶 | 私有桶，与 CLI 下载桶分开；region 与 RDS 相同 |
| 键 | `blobs/<sha256>`（小写 hex，跨 skill 去重） |
| ACL | private；不发预签名 URL |
| 生命周期 | 正式对象不自动删（由 outbox 控制）；桶里没有 `tmp/` 对象——上传的临时文件在服务端本机，见 Q2 |
| 凭据 | ECS RAM 角色优先；没有角色时用环境变量里的最小权限 AK（不写进仓库） |

## 接口

### 已有接口的改动

| 方法 | 路径 | 改动 | 鉴权 |
|---|---|---|---|
| `PUT` | `/api/v1/author/skills/{ns}/{name}/versions/{version}/files/{*relpath}` | 请求体改为流式读取；不再整份进 `byte[]`；单文件上限 100 MiB | `skills:write` + `write` 档 |
| `POST` | `/api/v1/author/skills`（multipart） | **P3 才改为流式解压**；P1/P2 在 128 MiB 处拒绝两次——压缩包自身（`getBytes()` 之前）与解压后总量（`ZipReader`），单文件 / 单包上限同上 | `skills:write` |
| `POST` | `/web/skills/{ns}/{name}/versions/import`（multipart） | 同上 | 会话 |
| `GET` | `/web/skills/{ns}/{name}/files/{*relpath}` | 由 `byte[]` 改为流式；加内联 / 下载响应头 | 会话 |
| `GET` | `/api/v1/author/…/files/{*relpath}`、`/api/v1/skills/…/files/{*relpath}` | 加内联 / 下载响应头 | 现有 |

### 新增接口

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `PUT` | `/web/skills/{ns}/{name}/versions/{version}/files/{*relpath}` | 控制台上传一个文件；原始字节；`If-Match` 必填 | 会话 + 草稿写权限 |
| `POST` | `/web/skills/{ns}/{name}/versions`（multipart） | 从一个版本建草稿，**并把第一个上传的文件写进去**（一次事务） | 会话 + 草稿写权限 |

**为什么建草稿要带文件**：控制台的文本保存是在第一次改动时懒建草稿的（`createDraft` 带一条 `edit`）。上传大文件时不能先写一个空的文本占位再覆盖——那会留下一个中间态，失败时还会留一个空文件。所以建草稿与首个二进制写必须是同一个请求、同一个事务。

**上传响应**：作者平面（`/api/v1/author`）沿用 `AuthorVersionResponse.Written`；网页平面（`/web`）沿用 `DraftResponse`（版本、清单、摘要），控制台据此更新文件树与 `If-Match` 基线。两条路的形状各自与所在平面既有的一致。

### CLI 命令分工

帮助、技能与规则段必须说同一件事。分工的判据是**内容是不是文本**，不是文件大小：

| 命令 | 用于 | 说明 |
|---|---|---|
| `skill write` | 文档、代码等**文本**，从 stdin 读 | 整份新建或替换；服务端做换行 / BOM / NFC 归一 |
| `skill edit` | 文本的一处替换 | 默认要求唯一命中 |
| `skill put` | 本地文件，尤其**图片 / PDF / Office / 音视频** | 任意字节；只写**已经存在**的草稿；默认拒绝覆盖，`--overwrite` 才覆盖。建草稿用 `write`（文本）或控制台的上传 |
| `skill get` | 把远端**单个文件**落到本地 | 任意类型；`-o` 省略时落当前目录；`-o -` 写 stdout |
| `skill cat` | 远端文件 → stdout | 不加行号；适合管道 |
| `skill read` | 远端**文本** → 带行号与续读提示 | 二进制落临时文件并打印路径 |
| `skill download` | **整版**打包 | 不是单文件；服务端已支持 512 MiB 归档，CLI 客户端在 P3 前仍受 17 MiB 内存上限约束 |

**文本归一化的边界**：`write` 与 `put` 都按 `TextCanon` 处理合法 UTF-8 的文本，**边界是 16 MiB**（`IngestLimits.STANDARD_TEXT_MAX_BYTES`）；超过 16 MiB 的文本按上传字节原样保存。二进制（UTF-8 探测为 false）一律不归一——重写作者的字节能改掉摘要承诺的内容。`put` 的帮助与 SOP 用同一句话；`skill write` 的旧契约（1–16 MiB 文本仍归一）由此恢复。16 MiB 与 `grep` 的文本上限是同一个数；`wc` 流式计数、不受此限（[ADR 0133](../../decisions/0133-wc-streams-and-is-not-text-capped.md)）。

**帮助里要改的三处**：

1. `declarations_skill.go` 的 `skill write`：把「二进制要先进草稿再写」改成「二进制用 `skill put`」；`skill put` / `skill get` 各自新增一条帮助。
2. `declarations.go` 的 `workflow`：主流程里出现 `put` / `get`，并区分 `get`（单个文件）与 `download`（整版）。
3. `internal/prompt/block.md`：第 4 条补一句「文本用 `skill write`，图片 / PDF 等用 `skill put`」。

**技能里要改的**：`skills/skillmaster/SKILL.md` 的索引加一行「传 / 取单个文件（图片、PDF、Office、音视频）」→ 新增 `sop/07-files.md`；`sop/03-edit-draft.md` 的「只有这两件事用 write」补一句二进制的路。

### 响应头

| 文件 | `Content-Type` | `Content-Disposition` | `X-Content-Type-Options` |
|---|---|---|---|
| png / jpg / jpeg / gif / webp | 对应类型 | `inline` | `nosniff` |
| pdf | `application/pdf` | `inline` | `nosniff` |
| 其余（含 svg / html / js / exe / docx / mp4） | `application/octet-stream` | `attachment` | `nosniff` |

`MediaTypes` 不再为任意扩展名猜类型：内联白名单与正文类型是两张表，其余一律 `application/octet-stream`（[ADR 0131](../../decisions/0131-any-file-type-is-accepted-but-only-safe-types-render.md)）。`Content-Disposition` 的文件名按 RFC 5987 编 UTF-8，中文文件名不能丢。

### 错误

| 场景 | 状态 | 错误码 | 细节 |
|---|---|---|---|
| 单文件超过 100 MiB | 413 | `invalid_upload` | `<文件路径>=file_too_large`（单文件路由没有路径时 `field` 是 `file`），带 `actual_bytes` / `limit_bytes` |
| 单版本超过 512 MiB | 413 | `invalid_upload` | `file=package_too_large`，带实际总量与上限 |
| 整包导入超过 128 MiB（压缩包本身或解压后；P3 前的临时上限） | 413 | `invalid_upload` | `file=package_too_large`，`limit_bytes` 是 128 MiB；`actual_bytes` 也带上（`ZipReader` 给解压后总量，控制器那道护栏给的是压缩包自身大小），但这句话不用它——两个客户端按 `limit_bytes <= 128 MiB` 选「逐个文件上传」那句 |
| 超过 512 个文件 | 400 | `invalid_upload` | `file=too_many_files`，带 `limit_bytes` |
| 目标已存在且没有 `--overwrite` | CLI 拒绝，不发**写**请求（它先列一次清单拿基线）；**控制台直接覆盖**（同路径上传即替换；这条规则尚未定，见 K2） | — | CLI 文案 |
| `If-Match` 过期 | 409 | `digest_mismatch` | 带当前摘要 |
| 写线上版本 | 409 | `version_is_not_draft` | 现有 |
| 路径非法 / 保留路径 | 400 | `invalid_path` / `reserved_path` | 现有 |

**文案**：句子在 `design/voice-spec.md`。**单文件超限**与**版本总量超限**说「实际大小 + 上限」，两个客户端从响应的 `details[].actual_bytes` / `limit_bytes` 取数套进去（服务端的 JSON 全局是 snake_case，见 `application.yml` 的 `property-naming-strategy`），服务端的英文 `message` 只给日志与 `curl` 用；**文件数超限**是一句计数（「这一版最多 N 个文件。」），**整包导入的临时上限**是一句固定上限（「整包导入最多 N MiB；…」，两个护栏共用这一句），这两类没有「实际值」可说。`package_too_large` 一个 issue 对两个上限（版本总量 512 MiB、整包导入 128 MiB——压缩包本身与解压后都算），客户端按 `limit_bytes` 选句子，不按操作猜：P3 解除导入上限后，同一个 issue 的句子会自动跟着变。

**客户端自己还记着 100 / 512 / 512 三个数**，因为 A4 要求在发请求前就拦下；那是预检，不是文案的第二个来源——服务端仍然拒绝绕过预检的请求，且它给出的数字才是句子里的数字。

### 审计

上传走 `version_file_write`，`detail` 里带 `op`（`add` 新增 / `overwrite` 覆盖 / `create` 建草稿带首文件）、`path`、`bytes`（**本次写入文件的字节数**）与 `digest_before` / `digest_after`。文本写入保持 v33 的旧形状（`op=write`，`bytes` 仍是写前版本总量），这是 A10「既有契约不变」的一部分。内容与已有文件相同的重复上传不写审计——它没有改变任何状态。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P0 存储底座** | `blob.storage/location`、`blob_deletion`、`OssBlobStore`、`RoutingBlobStore`、`BlobStore.put(InputStream, size)` / `put(Path, size)` / `open`、`IngestLimits` 拆单文件上限、`FileStreams` / Web 文件响应的内联与下载头 | A5、A7、A10（旧数据可读） |
| **P1 单文件上传** | 服务端流式写（API + Web）、建草稿带首文件、控制台文件树上传 / 拖拽 / 进度、CLI `skill put`、帮助与技能里的分工 | A1、A2、A4、A8、A9、A12、A13 |
| **P2 读取与预览** | CLI `skill get`、控制台图片 / PDF 预览、`MediaTypes` 拆成内联白名单与正文类型、Web GET 流式化、`get` 的帮助与 SOP | A3、A6、A8、A13 |
| **P3 整包与回收** | 整包 submit / import 流式解压、CLI `download` 流式落盘（解除 17 MiB 内存上限；ADR 0101 的失败残留说明照旧）、`blob_deletion` 清扫器 | A4（整包）、A11；外加一条 >17 MiB 媒体包的 `download` 用例 |

**每期明确不做**：

- P0 不做控制台与 CLI 的任何界面；只保证存储与响应头正确。
- P1 不做预览；上传成功后文件树里能看到，但点开仍是「下载」。
- P2 不改整包路径；此时 `submit` 仍受 128 MiB 的临时上限约束（压缩包本身与解压后各一道），CLI `download` 仍受 17 MiB 内存上限（大包与整版下载在 P3 才通）。
- P3 不做历史 blob 迁移；旧字节留在数据库，直到被引用清扫。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | OSS 凭据用 ECS RAM 角色还是环境变量 AK？ | RAM 角色；没有角色时才退回最小权限 AK | 在目标 ECS 上挂角色后跑一次真实上传；角色不可用时记录原因 |
| Q2 | 上传临时目录放在哪、预留多大？ | `/var/lib/skillmaster/tmp`，至少 2 GiB；每次请求一个唯一文件，失败即删 | 看 ECS 数据盘剩余空间；P1 上线前实测一次 100 MiB 上传 |
| Q3 | 整包导入要不要在本版就改成流式？ | 要（P3）；否则 512 MiB 的包在 `ZipReader` 里整份进内存。**P1/P2 先加 128 MiB 的显式上限**（`IngestLimits.PACKAGE_IN_MEMORY_MAX_BYTES`）：`ZipReader` 量**解压后**总量，控制器在 `getBytes()` 之前另按**压缩包自身**大小量一次（同一个 128 MiB），两道都拒绝：超过就拒绝并提示逐个文件上传，不能让它变成 OOM | 用 512 MiB 包在本地跑一次，观察堆峰值；超过 32 MiB 即证明必须流式。在 P3 完成前，T24 的 512 MiB 整包导入标「未执行」 |
| Q4 | 控制台 PDF 预览用 `<iframe>` 直连还是 `fetch` 成 blob？ | **已定**：直连同一个会话 URL，`frame-ancestors` 放行同源；浏览器自己流式加载，不占 JS 堆 | P2 在 Chrome / Safari 各验一次，确认会话 cookie 与会话超时行为 |
| Q5 | `get` 默认拒绝覆盖本地已有文件，是否符合预期？ | **已定**（PRD 做 #7 / A3 / T10）：拒绝，`--overwrite` 才覆盖；与远端 `put` 对称 | 不再是开放问题；若要改默认，先改 PRD |
| Q6 | 1 MiB 阈值的依据是否站得住？ | 站得住：小文件留库省一次 OSS 往返；1 MiB 以上的多半是媒体；万一是大文本，`grep` 有 16 MiB 上限、`wc` 流式读取（ADR 0133） | 上线一个月后统计 `blob` 的大小分布，若小图片占满库再调低 |
| Q7 | 单个文件 100 MiB 是压缩前还是解压后？ | 解压后（包内单条目的大小），与 `version_file.size` 一致 | P3 用例里用一个高压缩比的包验证 |
| Q8 | 「新建草稿」要不要成为独立动作？ | **本版不做，进 TODO（产品负责人 2026-11-02 定）**。方向：`draft create <地址> [--from <版本>]`——建一版草稿（默认从线上版本起稿），**不改 skill 内容**；届时 `write` / `edit` 不再以第一次内容改动建草稿（`put` 本来就只写已有草稿）。现状与缺口：控制台用一次 multipart（`POST /web/skills/{ns}/{name}/versions`）一步建草稿并写进第一个文件；CLI 的 `skill put @new` 拒绝，只能是三步——`printf '占位\n' | skill write <地址>@new _placeholder.txt --name <名称>`（占位路径不要用 `SKILL.md`、不要与目标文件同路径、不要用底稿已有路径——那会覆盖底稿里的那份；`write` 从 stdin 读内容）→ 用打印出的 `@<版本码>` 跑 `skill put` → `skill rm` 删掉占位文件。**真正的阻塞是模型**：一版至少要有一个文件，且 `UNIQUE (skill_id, digest)`（ADR 0064 §5）让「与底稿同内容的新草稿」插不进去——所以先要定「新草稿与底稿差在哪」（占位文件，或放宽唯一约束为只约束已发布 / 历史行），这一步要一条新 ADR 取代 ADR 0064 §5 的半条 | 后续版本：新 ADR → 数据模型迁移 → 控制台「第一次内容改动前不落库」那一态与设计稿（`design/README.md:919`）一并改 → 补 `draft create` 与 API 平面路由 |

## 附录

### 附录 A · 与现有代码的关系

**保留**：M5 的路径与 frontmatter 校验、M7 的版本与 `version_file`、M9 的分发与归档、M10 的审计、M12 的权限矩阵、现有的 `If-Match` 协议。

**改造**：

| 文件 | 改什么 |
|---|---|
| `modules/blob/BlobStore.java` | 增加 `put(InputStream, size)`、`put(Path, size)` 与 `open`；`get` 明确只给「能整份放进内存」的调用方 |
| `modules/blob/internal/PgByteaBlobStore.java` | 只服务 `storage='db'`；`put` 走新接口 |
| `modules/version/internal/BlobGc.java` | 不改：清扫调用 `BlobStore.deleteUnreferenced`，由 M6 的 `RoutingBlobStore` 把 OSS 键写进 `blob_deletion` |
| `modules/ingest/IngestLimits.java` | 拆出 `STANDARD_MAX_FILE_BYTES` |
| `api/MediaTypes.java` | 拆成内联白名单与正文类型；不再为任意扩展名猜类型 |
| `api/FileStreams.java` | 加 `Content-Disposition` / `nosniff`；内容一律来自 `BlobStore.open` |
| `api/WebSkillController.java` | 单文件读改为流式 |
| `api/AuthorSkillWriteController.java` | `bodyOf` 改为流式；单文件上限改用新常量 |
| `modules/ingest/internal/ZipReader.java` | 从 `byte[]` 改为从临时文件 / `SeekableByteChannel` 读取；条目流式交给 `BlobStore` |
| `usecase/Uploads.java`、`SubmitSkillUseCase`、`SubmitSkillVersionUseCase` | 配合流式上传 |
| `usecase/GrepVersionUseCase.java` | 文本处理加 16 MiB 上限；超过的文件跳过并在 `skipped` 里说明 |
| `usecase/CountVersionUseCase.java` | 改为流式计数；**不设** 16 MiB 上限（[ADR 0133](../../decisions/0133-wc-streams-and-is-not-text-capped.md)） |
| `modules/blob/BlobStore.containing`（`PgByteaBlobStore` + 路由实现） | OSS 里的文本按流式扫描；候选集已被 16 MiB 上限收窄 |
| `skillmaster-console`：`EditorFileTree`、`FilesTab`、`useEditorTree`、`useEditorSave`、`api/skills.ts` | 上传入口、进度、预览 |
| `skillmaster-cli`：新增 `put.go` / `get.go`，扩展 `dispatch`、帮助与用例 | 新命令 |
| `cmd/skillmaster/declarations_skill.go`、`declarations.go`、`internal/prompt/block.md` | 写清 `write` / `put` / `get` 的分工；主流程加入 put / get |
| `skills/skillmaster/SKILL.md`、新增 `sop/07-files.md`、`sop/03-edit-draft.md` | 技能侧同一套说法；旧技能不自动更新，帮助仍是权威入口 |

**新增**：

| 名称 | 归属 | 作用 |
|---|---|---|
| `OssBlobStore` | M6 `internal` | 私有 OSS 的 put/get/open/exists/delete |
| `RoutingBlobStore` | M6 `internal` | 按 `storage` 列路由；写入时按大小决定后端 |
| `Utf8Probe` | M5 `ingest` | 流式判断字节是否合法 UTF-8，给清单的 `is_binary` 用，替代整份 `IngestedFile.isBinary()`；同时给内存里的小上传用 |
| `blob_deletion` 清扫器 | M6 `internal` | 定时（或下一次清扫时）删 OSS 对象并删 outbox 行 |
| `PUT /web/…/versions/{version}/files/{*relpath}` | API | 控制台上传 |
| `POST /web/…/versions`（multipart） | API | 建草稿 + 首文件 |

**作废**：无。

### 附录 B · 来源与出处

- 上限的来历：v1 TD §1.3「每 skill 512 文件 / 16 MiB」是 Anthropic skill 标准的推荐值；本版把总量改成 512 MiB 是平台自己的选择，不声称与标准一致。
- 存储接缝与「不引入对象存储」的原决定：[ADR 0010](../../decisions/0010-storage-in-postgres.md)；本版的分流见 [ADR 0130](../../decisions/0130-large-blobs-live-in-object-storage.md)。
- 内容寻址与回收事务：[ADR 0005](../../decisions/0005-content-addressing.md)、ADR 0010 决定 4（其「同一事务」的一半被 [ADR 0130](../../decisions/0130-large-blobs-live-in-object-storage.md) 的 outbox 取代）。
- 权限档位：[ADR 0047](../../decisions/0047-permissions-are-a-ladder.md)。
- 控制台与 CLI 对应：[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)。

### 附录 C · 已测量的数据

| 指标 | **口径**（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| RDS ESSD 存储单价 | 阿里云 `DescribePrice`，cn-shanghai，2026-09-27；100 GB 档报 110 元/月，折算 1 元/GB/月；促销价会变，下单前复核 | 1 元 / GB / 月 |
| 现有 blob 规模 | 本机开发库 `SELECT count(*), sum(size) FROM blob` | **未测**——P0 开工时用同一条 SQL 记录基线 |
| 现有单文件最大值 | 同上，`max(size)` | **未测**——P0 开工时记录 |

> 口径不写清的数字等于没测过——它会被人抄到别处，然后丢掉限定条件。标「未测」的项在 P0 开工时补。
