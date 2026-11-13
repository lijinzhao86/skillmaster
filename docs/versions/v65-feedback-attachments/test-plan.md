# v65-feedback-attachments · 测试方案

> **最后更新**：2026-11-13
> **状态**：已实现；已执行的自动化部分通过，未执行项见 §结果
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 `attach --copy` 落稳定文件并打印元数据 | T1 | 通过 |
| #2 `attach --last-paste` 会话缓存 → 剪贴板，失败有指引 | T2、T3、T4 | 部分（缓存与错误句子通过；真剪贴板未执行） |
| #3 预览列附件，不带 `--consent` 无 POST | T5 | 通过 |
| #4 带附件提交后库与 OSS 都有 | T6 | 通过 |
| #5 按文件头判类型，七种之外拒收 | T7 | 通过 |
| #6 数量/单文件/合计/空/不可读都拒 | T8 | 通过 |
| #7 不带附件的 JSON 请求与 v64 一致 | T9 | 通过 |
| #8 撤回删附件对象、归档对象与行 | T10 | 通过 |
| #9 归档 NDJSON 带附件元数据 | T11 | 通过 |
| #10 他人不可读/删；没有返回字节的路由 | T12 | 部分（归属删除通过；路由清单未跑） |
| #11 隐私政策 1.3 与 stale 同意 | T13 | 部分（自动化通过；视觉与专门 1.2 用例未执行） |
| #12 skill 引导 agent 自动取图与预览 | T14 | 通过（文本与文档检查） |
| #13 Claude Code 真机取图并提交 | T15 | 未执行 |
| #14 WorkBuddy 真机取图或拖文件后提交 | T16 | 未执行 |
| #15 预览后文件消失，报错且不落半条 | T17 | 通过（CLI 用例） |
| #16 私有桶、无公开 URL、键不可推导 | T18 | 未执行 |

## 用例

### T1 · `attach --copy` 把消息里的路径落成稳定文件

- **前置**：CLI 已构建；`$TMPDIR` 干净；准备 `/tmp/source/2.png`（1×1 PNG，68 字节）。
- **步骤**：`skmr feedback attach --copy /tmp/source/2.png --json`。
- **期望**：退出 0；`path` 在 `$TMPDIR/skmr-feedback/` 下，文件 0600、目录 0700；`source=message_path`、`media_type=image/png`、`size_bytes=68`、`sha256` 等于 `shasum -a 256 /tmp/source/2.png`；源文件仍在。
- **判定**：通过／失败（失败记录实际 JSON 与文件权限）。

### T2 · `attach --last-paste` 从当前会话缓存取最新一张

- **前置**：构造 `<base>/claude-<uid>/<cwd-slug>/<session-id>/images/1.png` 与 `2.png`，`2.png` 更新；设置 `CLAUDE_CODE_SESSION_ID` 指向该会话；不放剪贴板内容。
- **步骤**：`skmr feedback attach --last-paste --json`。
- **期望**：选中 `2.png`；`source=session_cache`；类型/大小/sha256 与实际文件一致；`3.png` 不存在时不会凭空生成。
- **判定**：通过／失败。

### T3 · 会话缓存没有时回退到 macOS 剪贴板

- **前置**：清空会话缓存；macOS；用 AppleScript 把一张 1×1 PNG 放进剪贴板；`attach` 的 `osascript` 走真实路径（测试只替换缓存根）。
- **步骤**：`skmr feedback attach --last-paste --json`。
- **期望**：`source=clipboard`；输出文件是合法 PNG；sha256 与剪贴板字节一致；临时文件 0600。
- **判定**：通过／失败；非 macOS 跳过并记「未执行」。

### T4 · 三路都取不到时给出可执行的下一步

- **前置**：无会话缓存；清空剪贴板。
- **步骤**：`skmr feedback attach --last-paste --json`。
- **期望**：退出非 0；错误说明「把文件拖进对话或给路径」，并给出 `--copy <路径>` 的例子；没有网络请求（用抓包或假 server 断言）。
- **判定**：通过／失败。

### T5 · 预览逐条列附件，不带 `--consent` 不发送

- **前置**：登录态；`feedback.md`；`a.png`、`b.mp4` 都是合法文件头；假 server 记录请求。
- **步骤**：`skmr feedback send --file feedback.md --attach a.png --attach b.mp4 --json`。
- **期望**：退出 1；`--json` 时预览走 stderr；列出两个附件的文件名、类型、大小、sha256 与合计；server 只收到 `GET /feedback/consent`，没有 `POST`；预览含「不会被自动扫描」提示。
- **判定**：通过／失败；同时记录 server 请求日志。

### T6 · multipart 提交后库、OSS、通知与审计都在

- **前置**：服务端测试上下文注入 `@Primary` 的记录型 `BlobStore`；测试库清空；有效 token；`consent_version` 为当前版本；准备 PNG 与 MP4 的合法字节。
- **步骤**：构造 multipart（`content`、`consent_version`、两个 `attachment` part）→ `POST /api/v1/feedback`。
- **期望**：201；响应 `receipt` 以 `FB-` 开头，`attachments` 两条且字段完整；`user_feedback` 一行；`feedback_attachment` 两行、`position` 0/1、`media_type` 正确、`sha256` 等于服务端对落盘字节的摘要；记录型 `BlobStore` 收到两个 `putObject`，键为 `feedback/attachments/YYYY/MM/DD/<ULID>.<ext>`；站内通知与 `feedback_submit` 审计各一条。
- **判定**：通过／失败；证据附 SQL 结果与 fake store 的键。

### T7 · 类型按文件头判，七种之外拒收

- **前置**：服务端同上；准备：`.png` 扩展名但内容是 SVG、`.mp4` 扩展名但内容是文本、SVG、HEIC（`ftypheic`）、TIFF、ELF/MZ 可执行文件；再准备七种合法文件头。
- **步骤**：逐个提交；CLI 侧再用同样的文件跑 `send --attach`。
- **期望**：七种合法类型通过；其余全部 400 `feedback_attachment_type`，消息列允许类型但不回显字节；CLI 在发请求前就以同一类型规则拒绝；库里没有行、fake store 没有对象。
- **判定**：通过／失败；记录每种文件的实际响应。

### T8 · 数量、单文件、合计、空、不可读都拦得住

- **前置**：准备 6 个合法 PNG；一张 10 MiB+1 的 PNG；一个 50 MiB+1 的 MP4；合计 100 MiB+1 的多个文件；0 字节文件；一个读不出的文件（权限 000 或在读取时删除）。
- **步骤**：分别提交；CLI 本地先跑一次，服务端用直接 HTTP 再跑一次绕过本地检查。
- **期望**：第 6 个 → `feedback_attachment_too_many`；10 MiB+1 图片与 50 MiB+1 视频 → `feedback_attachment_too_large`；合计超限 → `feedback_attachment_total_too_large`；0 字节 → `feedback_attachment_empty`；读不出 → `feedback_attachment_unreadable`；全部 400 且不落库、不上传；CLI 本地拒绝时退出 1 并给同一类原因。
- **判定**：通过／失败；记录每个用例的实际码与 CLI 文案。

### T9 · 不带附件的 JSON 请求保持 v64 形状

- **前置**：服务端；旧形状 JSON `{content, consent_version}`。
- **步骤**：`POST /api/v1/feedback`（`application/json`）。
- **期望**：201；响应含 `receipt`、`created_at`、`attachments: []`；`user_feedback` 一行；`feedback_attachment` 零行；通知与审计照旧。
- **判定**：通过／失败。

### T10 · 撤回先删对象、再删行

- **前置**：一条反馈带两个附件；`archive_key` 已写；记录型 `BlobStore` 记录删除。
- **步骤**：`DELETE /api/v1/feedback/{receipt}`；再删一次。
- **期望**：第一次 204；两个附件对象与归档对象都被删除（共 3 次 `deleteObject`）；`user_feedback` 行与 `feedback_attachment` 行都没了；第二次 404 `feedback_not_found`；fake store 为空。
- **判定**：通过／失败；证据附删除顺序与 SQL 计数。

### T11 · 归档 NDJSON 带附件元数据，不复制字节

- **前置**：一条带两个附件的反馈；归档任务用记录型 `BlobStore` 捕获上传。
- **步骤**：运行 `FeedbackArchiveJob.archivePending(10)`；解压上传的对象。
- **期望**：NDJSON 中 `attachments` 数组两条，含 `filename`、`media_type`、`size_bytes`、`sha256`、`object_key`、`position`；没有附件字节；`user_feedback` 行仍在；第二次运行不重复。
- **判定**：通过／失败。

### T12 · 别人的附件不可读、不可删；没有返回字节的路由

- **前置**：两个账号；A 提交一条带附件的反馈；B 登录。
- **步骤**：B 用 A 的回执 `DELETE`；用 A 的回执与对象键尝试任何 GET/下载路径；枚举控制器路由。
- **期望**：B 的删除返回 404 `feedback_not_found`；没有任何路由接受「按回执提交」或返回附件字节；响应里没有公开 URL；对象键不能从回执推导（键用独立 ULID，不含回执）。
- **判定**：通过／失败；附路由清单与响应。

### T13 · 隐私政策 1.3 与 stale 同意

- **前置**：服务端配置 `privacy-version=1.3`；站点与设计演示件已构建。
- **步骤**：`GET /api/v1/feedback/consent`；打开官网隐私页与设计演示件；用 `consent_version=1.2` 提交。
- **期望**：consent 返回 `1.3`，notice 含附件一句；两处隐私页都有「附件」内容（收集、私有存储、长期保留、撤回删除、不自动扫描、元数据可能保留）；1.2 提交返回 400 `feedback_consent_stale`。
- **判定**：通过／失败；视觉部分由人对照，记录截图。

### T14 · skill 引导 agent 自动取图并预览

- **前置**：仓库里的 `skills/skillmaster/SKILL.md` 与 `skills/skillmaster-guide/trouble/feedback.md`。
- **步骤**：读两处文本；跑文档检查。
- **期望**：都提到消息路径 → `attach --last-paste` → 拖文件的顺序；都要求提交前把附件清单给用户看；都明确不要让用户手敲 CLI；都提示图片或视频不会被自动扫描。
- **判定**：通过／失败。

### T15 · Claude Code 真机：贴图、取图、提交

- **前置**：本机服务端（启用 OSS 或测试用对象存储）可访问；Claude Code 已登录 `skmr`；用户准备一张截图。
- **步骤**：用户在 Claude Code 里贴图并描述问题 → agent 写反馈文档 → agent 用消息路径或 `attach --last-paste` 取图 → 预览给用户 → 用户确认 → `send --attach --consent`。
- **期望**：提交 201；库里的附件 `sha256` 与宿主缓存里的原图一致；用户在预览里看到的是同一张图；回执与撤回命令可见。
- **判定**：通过／失败；记录 Claude Code 版本、取图来源（`message_path` / `session_cache` / `clipboard`）。

### T16 · WorkBuddy 真机：贴图、取图或拖文件、提交

- **前置**：WorkBuddy 已安装并连到同一个本机服务端；用户准备一张截图；记录 WorkBuddy 版本与是否在沙箱里。
- **步骤**：用户在 WorkBuddy 里贴图 → agent 先试消息路径/`attach --last-paste`；若剪贴板或缓存不可用，按引导请用户把文件拖进对话或给路径 → 预览 → 提交。
- **期望**：提交 201；附件行与对象都在；记录哪一条取图路可用；如果三条都不可用且用户无法拖文件，本用例失败（属成立条件 #14）。
- **判定**：通过／失败；证据附 WorkBuddy 版本、沙箱状态、取图来源。

### T17 · 预览后附件消失

- **前置**：`attach` 已落一个稳定文件；跑 `send` 预览成功；在发送前删除该文件。
- **步骤**：`skmr feedback send --file feedback.md --attach <已删除的路径> --consent`。
- **期望**：退出非 0，提示文件不存在/不可读；没有 POST；库里没有新行；fake store 没有对象。
- **判定**：通过／失败。

### T18 · 私有桶、无公开 URL、键不可推导

- **前置**：服务端配置；提交一条带附件的反馈。
- **步骤**：检查 `skillmaster.blobs` 配置与响应；尝试用对象键拼公开 URL；检查 `BlobStore` 是否产生预签名链接。
- **期望**：桶是私有的；响应只有 `sha256` 与元数据，没有 URL；`OssBlobStore` 不提供预签名方法；对象键含独立 ULID，不含回执号。
- **判定**：通过／失败；附配置与响应。

## 环境与数据

**服务端**

- 测试库：`SKILLMASTER_TEST_DB_URL=jdbc:postgresql://localhost:5432/skillmaster_test_v65`；测试会重建 schema。
- 对象存储：测试 profile 默认关闭 OSS；`FeedbackIT` 用 `@TestConfiguration` + `@Primary` 注入 Mockito 记录型 `BlobStore`，用 `ArgumentCaptor` 与 `doAnswer` 检查键、写入字节与删除次数/顺序；本身不保存字节，需要字节的用例在测试里自己捕获。
- 媒体样本：一份小 PNG/JPEG/GIF/WebP/MP4/MOV/WebM 文件头；反例 SVG、HEIC、TIFF、MZ。样本放测试代码里以字节数组构造，不依赖外部文件。
- `Multipart` 测试 helper 需要能关掉自动补 `name` 的行为（现有 helper 为 skill 提交自动加 `name`），新增一个显式开关后再用于反馈。

**CLI**

- `go test ./...`；`internal/feedback` 用临时目录构造会话缓存；剪贴板读取用一个假的 `osascript`（写进临时目录并前置到 `PATH`）验证 Go 侧写入逻辑，测试不碰真实剪贴板。
- `cmd/skillmaster/testdata/attachment.png` 作为错误夹具的合法附件；`testdata/feedback.md` 复用 v64。
- 错误夹具：新增七个 `feedback_attachment_*` 码，全部用合法附件 + 假 server 返回对应码，保证 registry 的每个码都真的被触发。

**真机**

- 本机服务端：按 `.dev-sop/profile.md` 的「本地环境」起 `8080`；附件提交要求启用 OSS，若本机没有可用桶，使用测试用的对象存储替身或部署好的测试环境，并在结果里写明用的是哪一个。
- Claude Code 与 WorkBuddy 各一次；记录版本、宿主是否沙箱、取图来源。
- 清理：删除测试反馈与临时目录；恢复剪贴板内容（若测试改过）。

## 结果

已执行的自动化部分通过；T3 的真实剪贴板、T4 的真实「三路都空」、T12 的路由清单、T13 的视觉对照、T15/T16 真机与 T18 的配置断言未执行。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `internal/feedback` 的 `TestAttachCopiesAMessagePathIntoAStableFile`（目录 0700、文件 0600）；`cmd/skillmaster` 的 `TestFeedbackAttachCopiesAMessagePathAndPrintsJSON` |
| T2 | 通过 | `TestAttachPicksTheNewestSessionPaste`（构造当前会话缓存，选 `2.png`） |
| T3 | 未执行（真剪贴板） | Go 侧的 `TestClipboardMediaWritesTheFirstClassItCanRead` 用假 `osascript` 通过；真实剪贴板与 AppleScript 的 PNG/TIFF/furl 只在手工 shell 里验过，没有走完整 CLI |
| T4 | 通过（错误句子） | `TestAttachErrorNamesTheNextStep` 与 `TestFeedbackAttachNeedsExactlyOneSource`；真实的「三路都空」未跑 |
| T5 | 通过 | `TestFeedbackSendPreviewListsAttachmentsAndDoesNotPost`：两个附件（PNG + MP4）列出文件名、类型、大小、sha256 与「合计：92 字节」及「不会被自动扫描」，没有任何 POST |
| T6 | 通过 | `FeedbackIT.submittingAttachmentsStoresRowsAndObjects`：键→字节→sha256 逐条对上、站内通知与 `feedback_submit` 审计（`attachments=2`）各一条；失败路径 `anUnavailableBlobStoreIsA503AndLeavesNoRow`（503、无行、失败键也进清理）与 `aFailedSecondUploadRemovesTheFirstObjectToo`；事务失败清理由 `SubmitFeedbackUseCaseTest` 覆盖；`aChunkedMultipartSubmissionIsAccepted` 覆盖 CLI 真实使用的无长度（chunked）编码；CLI 侧的 `TestFeedbackSendWithConsentPostsMultipart` |
| T7 | 通过 | `FeedbackIT.anSvgNamedPngIsRefusedBeforeStoringAnything`、`FeedbackAttachmentSafetyTest`、CLI 的 `TestFeedbackAttachRefusesAnUnsupportedFile` 与 `TestFeedbackSendRefusesBadAttachmentsBeforeTheNetwork`（`send --attach` 本地拒绝，零请求） |
| T8 | 通过 | `FeedbackIT.sixAttachmentsAreRefused`、`anOversizedImageIsRefused`、`anOversizedVideoIsRefused`、`attachmentsOverTheTotalAreRefused`（3 × 34 MiB 稀疏文件）、`anEmptyAttachmentIsRefused`、`anUnreadableAttachmentIsRefused`；CLI 的 `TestFeedbackSendRefusesBadAttachmentsBeforeTheNetwork` 覆盖第 6 个、超尺寸图与错误类型，均退出 1 且不发请求 |
| T9 | 通过 | `FeedbackIT.aTextOnlySubmissionHasAnEmptyAttachmentList`：201、`attachments: []`、`feedback_submit` 审计的 `attachments` 为 `0` |
| T10 | 通过 | `FeedbackIT.withdrawingDeletesEveryAttachmentObjectAndTheRows`：两个附件对象与归档对象共 3 次 `deleteObject`，归档对象在最后；行级联消失；第二次删除 404 |
| T11 | 通过 | `FeedbackArchiveJobIT.theArchiveCarriesAttachmentMetadataAndNoBytes`：两条附件、六个字段（含 `size_bytes`/`sha256`/`position`）逐条断言、NDJSON 小于 2 KiB（无字节）、第二次 `archivePending` 为 0 |
| T12 | 部分执行 | `FeedbackIT.deletingIsScopedToItsOwnerAndRemovesTheRow` 仍通过；「没有任何路由返回字节」的路由清单断言未跑 |
| T13 | 部分执行 | 站点 `npm test` 25 条通过，隐私页版本 1.3；`FeedbackIT` 的 stale 用例仍通过。真机视觉对照与专门的 1.2 提交未跑 |
| T14 | 通过（文本） | `skills/skillmaster/SKILL.md` 与 `skills/skillmaster-guide/trouble/feedback.md` 已改；`check_docs.py` 通过 |
| T15 | 未执行 | 需要 Claude Code 真机与可用对象存储 |
| T16 | 未执行 | 需要 WorkBuddy 真机；沙箱内的剪贴板/缓存行为待记录 |
| T17 | 通过 | `TestFeedbackSendRefusesAnAttachmentThatDisappearedAfterPreview`：预览成功 → 删除文件 → 带 `--consent` 退出非 0、没有任何 POST |
| T18 | 未执行 | 私有桶与「无公开 URL」的配置断言未单独跑；实现里没有预签名方法 |

**已跑的自动化汇总**

- 服务端 `./mvnw verify`：surefire 521 条、failsafe 811 条，均 0 失败、0 错误（两个插件会重复执行一部分 IT 类，所以不合并成单一数字；含 `FeedbackIT` 23 条、`FeedbackArchiveJobIT` 4 条、`FeedbackAttachmentSafetyTest` 6 条）。
- CLI `go test ./...`：全部包通过；`gofmt -l` 为空；`internal/nodelete` 通过（临时文件用截断，不 unlink）。
- 站点 `npm test`：25 条通过；`npm run type-check` 通过。
- 设计件：`npm run build:home`、`npm run build:console`、`npm run type-check`、`home-check.mjs` 全通过。
- 文档：`generate.py`、`impact.py --check`、`check_docs.py` 全通过；`node scripts/check-file-size.mjs` 通过。

## 已知问题

本版范围内没有已复现但未修的缺陷。标为「未执行」或「部分执行」的项按上表如实记录，不当作通过。
