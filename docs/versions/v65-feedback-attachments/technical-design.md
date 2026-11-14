# v65-feedback-attachments · 技术设计

> **最后更新**：2026-11-13
> **状态**：已实现
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR 0191](../../decisions/0191-feedback-attachments-are-private-unmoderated-objects.md)**——本文只链接、不复述理由。

## 架构

**一条提交链路，三段。**

```
用户贴图 / 录视频
  → 宿主（Claude Code / WorkBuddy）
  → agent：skmr feedback attach
       --copy <消息里的路径> | --last-paste（会话缓存 → 剪贴板）| --clipboard
       → $TMPDIR/skmr-feedback/<stem>-<32 hex>.<ext>（0700/0600）
  → agent：skmr feedback send --file <文档> --attach <路径>…
       预览（附件清单）→ 用户确认 → --consent
  → CLI：本地校验（类型/大小/sha256）→ GET /api/v1/feedback/consent
       → POST /api/v1/feedback（multipart：文本字段 + attachment parts）
  → 服务端：校验 → 上传私有 OSS 命名对象 → 一个事务写
       user_feedback + feedback_attachment + 站内通知 + 审计
  → 撤回：DELETE /api/v1/feedback/{回执}
       → 删每个附件对象 + 归档对象 → 删 user_feedback 行（附件行级联）
```

**边界**

| 谁 | 负责什么 | 不负责什么 |
|---|---|---|
| CLI（`cmd/skillmaster/feedback*.go`、`internal/feedback/`） | 取图与落盘、本地类型/大小校验、预览、multipart 编码、临时文件清理 | 不直写 OSS；不相信自己算的 sha256 能代替服务端；不决定谁能看 |
| `api/FeedbackController` | 两条 `POST`（JSON / multipart）、字段反序列化、把每个 part spool 成临时文件、请求结束清理 | 不做业务校验；不碰对象存储 |
| `usecase/SubmitFeedbackUseCase` | 文本与附件的校验顺序、限流、上传对象、事务边界、失败清理 | 不写 SQL；不决定附件行怎么存 |
| M18（`modules/feedback/`） | `user_feedback` 与 `feedback_attachment` 的行、回执、撤回、归档元数据 | 不决定字节放哪；对象键由 M18 生成，字节由 M6 写 |
| M6（`modules/blob/`） | `putObject` / `deleteObject` 的私有 OSS 通道 | 不知道附件是什么 |
| M14 / M10 | 回执通知、`feedback_submit` / `feedback_delete` 审计 | 不读附件字节 |

**上传在数据库事务之外。** 对象存储不能加入数据库事务；`SubmitFeedbackUseCase.submit` 不再整体加 `@Transactional`，而是在校验与限流之后、写库之前上传对象，再用 `TransactionTemplate` 把「反馈行 + 附件行 + 通知 + 审计」放进一个事务。写库失败时按已上传的键逐个删；删不掉就记日志留键。顺序与理由见 [ADR 0191](../../decisions/0191-feedback-attachments-are-private-unmoderated-objects.md) §后果。

**取图的三条路是「线索」而不是承诺。** 消息里的路径（`[Image: source: …]`）是宿主未文档化的行为；会话缓存是 Claude Code 的私有目录；剪贴板受平台与沙箱影响。`attach` 按优先级尝试，拿到什么就打印什么来源；agent 用 `Read` 看一眼候选图片，确认是用户说的那张，再交给 `send`。WorkBuddy 的真机行为在测试方案里单独验；取不到时退到「请用户把文件拖进对话」。

## 数据模型

新迁移 `V51__feedback_attachment.sql`。写法与 `V50__user_feedback.sql` 一致：`TEXT` 主键与时间、`CHECK` 只做兜底。

```sql
CREATE TABLE feedback_attachment (
  id          TEXT PRIMARY KEY,                 -- ULID，服务端生成
  feedback_id TEXT NOT NULL REFERENCES user_feedback(id) ON DELETE CASCADE,
  position    INTEGER NOT NULL,                 -- 0..n-1，按 part 顺序
  filename    TEXT NOT NULL,                    -- 原文件名（basename、去控制与格式字符、≤200 码点），只作展示
  media_type  TEXT NOT NULL,                    -- 服务端按文件头识别，不信客户端
  size_bytes  BIGINT NOT NULL,
  sha256      TEXT NOT NULL,                    -- 服务端对落盘字节计算
  object_key  TEXT NOT NULL UNIQUE,             -- feedback/attachments/YYYY/MM/DD/<ULID>.<ext>
  created_at  TEXT NOT NULL,
  UNIQUE (feedback_id, position),
  CHECK (position >= 0),
  CHECK (size_bytes > 0)
);

CREATE INDEX idx_feedback_attachment_feedback ON feedback_attachment(feedback_id);
```

- **归属**：`ModuleMap` 的 M18 表集合加 `feedback_attachment`；`TableOwnershipTest` 会把它钉住。
- **级联**：撤回删 `user_feedback` 行时附件行随 `ON DELETE CASCADE` 消失；对象不能跟着行走，必须先删对象再删行（[ADR 0191](../../decisions/0191-feedback-attachments-are-private-unmoderated-objects.md) 决定 7）。
- **不硬编码数量上限**：`position` 只做非负兜底；5 个的上限是业务规则，改数值不应该需要迁移。
- **对象键**：`feedback/attachments/YYYY/MM/DD/<ULID>.<ext>`；日期取提交时刻的 UTC，`ext` 由识别出的类型决定（`png` / `jpg` / `gif` / `webp` / `mp4` / `mov` / `webm`）。不拼原文件名，也不拼回执号；原文件名只在数据库里。
- **模型身份**：`docs/current/model.md` 增加 `FeedbackAttachment` 一行——`id`（ULID）是行身份，`object_key` 是字节身份；一条附件只属于一条反馈。

## 接口

### CLI

| 命令 | 作用 |
|---|---|
| `skmr feedback attach --copy <路径> [--json]` | 把消息里已有的路径复制到稳定目录；打印路径、来源、类型、大小、sha256 |
| `skmr feedback attach --last-paste [--max-age <时长>] [--json]` | 当前会话缓存 → 剪贴板；默认 `--max-age 30m` |
| `skmr feedback attach --clipboard [--json]` | 只读剪贴板；v65 仅 macOS |
| `skmr feedback send ... --attach <路径>`（可重复） | 预览逐条列出附件；带 `--consent` 时 multipart 提交 |
| `skmr feedback template` | 模板末尾增加附件说明：`--attach` 怎么用、不自动扫描；上限指向 `skmr help feedback send`，不抄数值 |
| `skmr feedback delete <回执> --yes` | 服务端删除附件对象与行；CLI 文案改为「反馈、附件与归档副本」 |

**`attach` 的输出**（文本模式）：

```
已取到：/var/folders/.../T/skmr-feedback/shot-ab12…png
来源：当前会话的粘贴缓存
类型：image/png
大小：1.2 MiB
sha256：ab12…
下一步：skmr feedback send --file <文档> --attach <上面的路径>
```

`--json` 输出同一组字段：`path`、`source`（`message_path` / `session_cache` / `clipboard`）、`media_type`、`size_bytes`、`original_name`、`sha256`。`attach` 不需要登录，不发网络请求。

**取图来源**

| 来源 | 怎么找 | 限制 |
|---|---|---|
| `--copy <路径>` | 校验是普通文件 → 复制进稳定目录 | 路径不存在/不是普通文件/不可读 → 可读错误 |
| 会话缓存 | `<base>/claude-<uid>/<cwd-slug>/<session-id>/images/`；`<base>` 依次取 `CLAUDE_CODE_TMPDIR`、`os.TempDir()`（macOS 上是每用户 `$TMPDIR`）；兼容 `~/.claude/image-cache/<session-id>/` | 会话 ID 取 `CLAUDE_CODE_SESSION_ID` 或 `CLAUDE_SESSION_ID`；都没有时只在当前项目的会话目录里找 `--max-age` 内最新的允许类型文件 |
| 剪贴板 | macOS `osascript`：`PNGf` / `JPEG` / `GIFf`（GIF）/ TIFF（经 `sips` 转 PNG）/ `furl` 文件 URL | 非 macOS 明确报「当前平台不支持读取剪贴板」；多选文件的 `furl` 会让 AppleScript 失败，按「没有找到」处理，引导用户拖一个文件进对话 |

稳定目录是 `os.TempDir()/skmr-feedback/`（0700，文件 0600）；`attach` 每次启动把 24 小时前的旧文件截断为 0 字节；`send` 成功后也把自己用过的、位于该目录下的文件截断。**不 unlink**：ADR 0101 禁止 CLI 删除文件，凭据文件已经用「截断为空」表达同一个事实；空壳留在目录里，温度目录的系统清理会最终收走它。文件名是 `<原词干>-<32 位随机十六进制>.<扩展名>`：随机段保证唯一，词干只用于让预览看得出是哪一张，不参与任何寻址。`send` 从同一个形状里去掉随机段，服务端记录的原文件名因此仍是 `shot.png`，而不是稳定副本的名字。`send` 判断「是不是自己写的」也看目录名与这个文件名形状，所以 `attach` 与 `send` 的 `$TMPDIR` 不一致时仍会清空；符号链接与非普通文件一律不碰。

**`send` 的本地预检**

1. `--attach` 可重复；数量 ≤ 5。
2. 每个路径：必须是普通文件、可读、非空；按文件头识别类型；图片 ≤ 10 MiB、视频 ≤ 50 MiB。
3. 合计 ≤ 100 MiB；重复的路径按两个附件算（服务端也按两个行存）。
4. 预览：文件名、类型、大小、sha256、合计；固定提示「图片或视频不会被自动扫描；请确认画面里没有密钥、个人信息或他人隐私；图片或视频可能含位置等元数据」。
5. 预览与发送之间文件消失 → 可读错误，不发请求。

### 服务端

**保留 v64 的 JSON 形状**（旧客户端不升级也能提交文本）：

| 方法 | 路径 | 入参 | 出参 / 状态 |
|---|---|---|---|
| GET | `/api/v1/feedback/consent` | — | `200 {version, notice}`；notice 增加附件一句 |
| POST | `/api/v1/feedback` | `application/json`：`{content, skill_address?, diagnostics?, consent_version, allow_sensitive?}` | `201 {receipt, created_at, attachments: []}`；与 v64 行为一致 |
| POST | `/api/v1/feedback` | `multipart/form-data`：文本字段 + 重复的 `attachment` part | `201 {receipt, created_at, attachments: [{filename, media_type, size_bytes, sha256}]}` |
| DELETE | `/api/v1/feedback/{receipt}` | — | `204`；不属于当前账号时 `404` |

**multipart 字段**

| 字段 | 必填 | 说明 |
|---|---|---|
| `content` | 是 | 反馈文档；上限仍是 20,000 码点 |
| `skill_address` | 否 | 关联 skill |
| `diagnostics` | 否 | JSON 字符串；服务端仍按 v64 白名单过滤 |
| `consent_version` | 是 | 当前隐私政策版本；本版是 `1.3` |
| `allow_sensitive` | 否 | `true` 时放行文本里的敏感信息确认 |
| `attachment` | 否 | 0..5 个文件 part；`filename` 只作展示，类型按文件头判 |

**附件类型（按文件头，不信扩展名与客户端声明）**

| 类型 | 识别 | 单文件上限 |
|---|---|---|
| `image/png` | `89 50 4E 47 0D 0A 1A 0A` | 10 MiB |
| `image/jpeg` | `FF D8 FF` | 10 MiB |
| `image/gif` | `GIF87a` / `GIF89a` | 10 MiB |
| `image/webp` | `RIFF` + 第 8..11 字节 `WEBP` | 10 MiB |
| `video/mp4` | 第 4..7 字节 `ftyp` + 品牌在白名单（`isom` / `iso2` / `iso4` / `iso5` / `iso6` / `mp41` / `mp42` / `avc1` / `M4V ` / `MSNV` / `dash`） | 50 MiB |
| `video/quicktime` | `ftyp` + 品牌 `qt  ` | 50 MiB |
| `video/webm` | `1A 45 DF A3` + 前 256 字节含 `webm`（不分大小写；不解析 DocType 位置） | 50 MiB |

拒收：SVG（脚本面）、HEIC/HEIF（兼容性）、TIFF、PDF、ZIP、可执行文件与其它一切未列出的类型。文件头读 256 字节，足够覆盖上面七种。

**校验顺序**（任何一步失败都不落库、不上传）

登录 → 文本非空 → U+0000 → 码点上限 → 同意版本 → 文本敏感扫描 → **附件数量 → 文件名清洗 → 空文件 → 类型 → 单文件大小 → 合计大小** → 限流 → 上传对象 → 事务写库。

文本扫描在限流之前（v64 的顺序）；附件校验也在限流之前——一个类型不对的附件不该先花掉一小时的额度。控制器在 spool 之前还会按数量、单文件 50 MiB、合计 100 MiB 粗检一遍，避免为一个注定被拒的请求再写一份临时文件；空文件、类型与类型相关的 10 MiB 图片上限仍在 use case 里。同时违反文本与附件规则时，粗检可能先回附件错误——两者都是 400，成功路径不受影响。**Servlet 的 520 MB 全局 multipart 上限仍然先于这些检查**（它是 skill 发布用的那个值，本版没有再给反馈路由单独配一套 multipart 上限），所以一个超大请求仍会先被 Tomcat 缓冲；粗检挡住的是第二份 spool 副本。

**错误码**（新增到 `ErrorCode`；`ApiExceptionHandler` 为 `feedback_attachment_unavailable` 加 503）

| 码 | 状态 | 含义 |
|---|---|---|
| `feedback_attachment_too_many` | 400 | 超过 5 个附件 |
| `feedback_attachment_too_large` | 400 | 单个附件超过图片或视频上限；`details` 带文件名、实际字节、上限 |
| `feedback_attachment_total_too_large` | 400 | 合计超过 100 MiB；`details` 带实际值与上限 |
| `feedback_attachment_type` | 400 | 文件头不是允许的七种；`details` 只带文件名与 `type_not_allowed`，允许类型写在 message 里，不回显字节 |
| `feedback_attachment_empty` | 400 | 0 字节 |
| `feedback_attachment_unreadable` | 400 | part 读不出来（截断、临时文件失败） |
| `feedback_attachment_unavailable` | 503 | 部署没有对象存储、OSS 写入失败，或服务端建不出暂存文件；文本反馈不受影响 |

**响应**：`attachments` 每条含 `filename`、`media_type`、`size_bytes`、`sha256`；没有附件时是 `[]`。旧客户端忽略这个字段。

**上传与写库**

1. `FeedbackController` 先做粗检，再把每个 `MultipartFile` spool 成 `0600` 临时文件，交给 `SubmitFeedbackUseCase.Upload(Path, filename)`（大小由 use case 自己 `Files.size` 得出）；请求结束时在 `finally` 删除。临时文件落在 `BlobSettings.tempDirPath()`——和 blob 上传同一个目录，部署把它挪到大盘时这里也跟着走；连临时文件都建不出来是服务端故障，回 503 `feedback_attachment_unavailable`。
2. `SubmitFeedbackUseCase` 调 `FeedbackAttachmentSafety` 校验，生成 `FeedbackAttachmentDraft`（`id`、`position`、`filename`、`mediaType`、`sizeBytes`、`sha256`、`objectKey`）。
3. 逐条 `BlobStore.putObject(key, file, size)`；任何一条失败 → 删已上传的键（best effort）→ 抛 `feedback_attachment_unavailable` 或对应的校验错误。
4. `TransactionTemplate` 内：`FeedbackService.create(...)` 写反馈行与附件行；`NotificationService.emit` 发回执通知（正文写附件个数）；`AuditLog.record` 写 `feedback_submit`（`attachments` 只记个数，不记文件名）。
5. 事务抛异常 → 删已上传的键 → 原异常继续抛。

**撤回**：`PgFeedbackService.deleteByReceipt` 在 `FOR UPDATE` 锁住反馈行后，`repository.findAttachments(id)` 取键 → 逐个 `deleteObject` → 删归档对象 → 删反馈行（附件行级联）。任一对象删失败就抛出，事务回滚，行留着，下一次按键重试。重复删已删的键是幂等的。

**归档**：`FeedbackArchiveJob.document(row, attachments)` 在 NDJSON 里增加 `attachments` 数组（`filename`、`media_type`、`size_bytes`、`sha256`、`object_key`、`position`）；附件字节不复制，OSS 里已经是耐久副本。

**运营读路径**：没有新路由。按回执查库拿键，再去私有桶看：

```sql
SELECT f.receipt, a.position, a.filename, a.media_type, a.size_bytes, a.object_key
FROM user_feedback f
JOIN feedback_attachment a ON a.feedback_id = f.id
WHERE f.receipt = :receipt
ORDER BY a.position;
```

### 隐私与文案

- **隐私政策 1.3**：`design/src/home/legalText.ts` 与 `skillmaster-site/src/legal/legalText.ts` 同步改；服务端 `SkillmasterProperties.Account.privacyVersion` 默认值与 `application.yml` 的 `SKILLMASTER_PRIVACY_VERSION` 一起升到 `1.3`；`ReadFeedbackConsentUseCase.NOTICE` 增加「附件会一并发送；图片或视频不会被自动扫描」。
- **§一（收集什么）** 增加：附件文件、文件名、类型、大小、sha256。
- **§十（你主动提交的反馈）** 增加：附件存私有 OSS、长期保留、撤回删除、不会自动扫描图片或视频内容、图片或视频元数据可能保留。
- **旧账号不强制重新同意**（与 v64 相同）；提交时按当次版本记录，1.2 的客户端会被 `feedback_consent_stale` 推去重新预览。

### skill 文案

- `skills/skillmaster/SKILL.md` 的「卡住了」：先看消息里的图片/文件路径；没有就 `skmr feedback attach --last-paste`；还取不到再请用户拖文件。明确写「不要让用户手敲 CLI」。
- `skills/skillmaster-guide/trouble/feedback.md`：增加「图片或视频」一节，给出 `attach` → `send --attach` 的两步，以及预览必须给用户看。
- 两处都写：图片内容不会被自动扫描，发送前自己确认画面里没有密钥、个人信息或他人隐私。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | `V51`、M18 附件行、`FeedbackAttachmentSafety`、multipart 提交、上传与事务拆分、撤回删对象、归档元数据、错误码、隐私版本 1.3 | [`prd.md`](prd.md) #4–#9、#15、#16 的服务端半；#11 的版本半 |
| P1 | `attach` 三路取图、`send --attach`、本地校验、预览、multipart 客户端、临时文件清理 | #1–#3、#5、#6、#15 的 CLI 半 |
| P2 | 隐私条款两处、skill 两处、`docs/current/` 生成物、Claude Code 与 WorkBuddy 真机 | #10–#14 |

## 开放问题

第 3 关的三条问题都已定，没有新的未决项。

| # | 问题 | 结论 |
|---|---|---|
| Q1 | 对象存储不能加入数据库事务：先传对象还是先写行？失败时谁清理？ | 先传对象，再用 `TransactionTemplate` 写库；写失败按已上传的键逐个删，删不掉记日志留键。进程崩溃留下的孤儿对象先接受，将来用前缀列举与行比对清扫 |
| Q2 | 取图的三条路各自在什么条件下可信？ | 消息路径只当线索；会话缓存按当前会话目录取最新；剪贴板只做 macOS 兜底；WorkBuddy 真机验证，取不到就退到「请用户拖文件」。agent 用 `Read` 确认候选图片 |
| Q3 | 隐私条款写到 1.3 的边界在哪？ | 与 v64 的反馈一节合并，如实写附件、元数据与「不自动扫描」，不写成免责声明；两处 `legalText.ts` 与服务端版本号一起升 |

## 附录 A · 与现有代码的关系

| 文件 | 改动 |
|---|---|
| `skillmaster-server/src/main/resources/db/migration/V51__feedback_attachment.sql` | 新表、索引、级联 |
| `modules/feedback/FeedbackAttachment.java` | 新记录：一行附件 |
| `modules/feedback/FeedbackAttachmentSafety.java` | 类型识别、四个对外上限（5 个 / 10 MiB / 50 MiB / 100 MiB）、文件名清洗（控制与格式字符、200 码点、`.`/`..` 回退为 `attachment`） |
| `modules/feedback/FeedbackService.java` | `create(..., List<FeedbackAttachmentDraft>)`、`attachmentsFor` |
| `modules/feedback/internal/PgFeedbackService.java` | 写附件行、撤回删对象、对象键生成 |
| `modules/feedback/internal/FeedbackRepository.java` | 附件行的插入与按反馈读 |
| `modules/feedback/internal/FeedbackArchiveJob.java` | NDJSON 增加附件元数据 |
| `modules/feedback/FeedbackConfiguration.java` | 无新 bean；常量在 `FeedbackAttachmentSafety` |
| `api/FeedbackController.java` | 增加 multipart 方法、spool 临时文件 |
| `usecase/SubmitFeedbackUseCase.java` | 附件校验、上传、`TransactionTemplate`、失败清理 |
| `usecase/ReadFeedbackConsentUseCase.java` | notice 增加附件一句 |
| `common/ErrorCode.java`、`api/ApiExceptionHandler.java` | 七个错误码与 503 |
| `common/ModuleMap.java` | M18 表加 `feedback_attachment`；发布类型加 `FeedbackAttachment` / `FeedbackAttachmentDraft` / `FeedbackAttachmentSafety` |
| `config/SkillmasterProperties.java`、`application.yml` | 隐私版本 1.3 |
| `skillmaster-cli/cmd/skillmaster/feedback.go`、`feedback_attach.go` | `--attach` 解析、`attach` 子命令 |
| `skillmaster-cli/internal/feedback/media.go`、`paste.go`、`clipboard_darwin.go`、`clipboard_other.go` | 类型识别、三路取图、稳定目录 |
| `skillmaster-cli/internal/api/feedback.go` | multipart 提交、附件与响应类型 |
| `skillmaster-cli/cmd/skillmaster/declarations_feedback.go`、`internal/help/registry.go` | `attach` 的帮助、错误码 |
| `scripts/docs/generate.py` | 生成 `limits.md` 时读 `FeedbackAttachmentSafety` |
| `design/src/home/legalText.ts`、`skillmaster-site/src/legal/legalText.ts` | 隐私政策 1.3 |
| `skills/skillmaster/SKILL.md`、`skills/skillmaster-guide/trouble/feedback.md` | 取图与预览引导 |

## 附录 B · 已测量的数据

本版没有需要提前测量的数字；上限是产品契约（[`prd.md`](prd.md) §范围），不是压测结果。
