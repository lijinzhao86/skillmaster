# v33-content-canonicalization · 技术设计

> **最后更新**：2026-10-30
> **所属版本**：[`README.md`](README.md)
> **状态**：已完成（实现与审计已收口）

<!--
接口、数据模型、分期的权威在这一份。取舍的理由在 ADR 0117，这里只链接不复述。
每条对代码的断言都指到了真实文件与符号。
-->

## 架构

### 新增一个纯模块：`modules/canon`

`com.skillmasterai.modules.canon`，不拥有任何表，不依赖任何模块（只依赖 JDK）。三个类，全是静态纯函数：

| 类 | 职责 | 对外签名 |
|---|---|---|
| `TextCanon` | 一份**文本**文件的字节归一 | `static Result canonicalize(String relpath, byte[] bytes)`；`record Result(byte[] bytes, List<String> changes)` |
| `PathCanon` | 一段**路径**的归一与跨系统校验 | `static String normalize(String relpath)`、`static Optional<Issue> check(String relpath)`、`static List<Collision> collisions(List<String> paths)`；`record Issue(String field, String issue)`、`record Collision(String a, String b, String issue)` |
| `Junk` | 打包垃圾的判据 | `static boolean isJunk(String relpath)` |

**为什么是一个模块而不是三个工具类散在各处**：归一化必须「只发生一次、发生在写入的时候」（PRD §做，理由在 [ADR 0117](../../decisions/0117-content-is-canonicalized-at-the-door.md)）；它有两个调用方（上传校验与写路径），抄两份就会漂。`modules/canon` 出现在 `ModuleMap` 之外是**有意的**——`ModuleMap` 记的是「谁拥有哪张表」，而 canon 一张表都没有；`ArchitectureTest.modulesAreFreeOfCycles` 把它当一个无依赖的 slice，照样通过。

### 四类写入口的接入点

| 写入口 | 接入文件与符号 | 归一化什么 |
|---|---|---|
| zip 上传（`skill submit` / 网页导入） | `modules/ingest/SkillUploadValidator.validate` | 名字（junk、NFC、路径规则、碰撞）＋**内容字节**（每个内容文件过 `TextCanon`） |
| `skill write`（CLI / API） | `usecase/WriteDraftFileUseCase.write` | 目标路径（`DraftFileWrite.requireTargetPath`）＋与清单的碰撞 ＋内容字节 |
| 网页编辑（`edit`） | `usecase/WebEditDraftFileUseCase.edit` → 上面的 write / `MoveDraftFileUseCase.move` | 写：同上；改名：目标路径规则与碰撞（源路径不校验，见下） |
| 从底稿建草稿 | `usecase/CreateDraftFromVersionUseCase.apply`（路径与字节）＋ `create` 里的 `requireNoCollision`（碰撞） | 与 write 同一条：目标路径 ＋碰撞 ＋内容字节 |

**上传管线的顺序（在 `SkillUploadValidator.validate` 里）**：

1. `ZipReader.read` 读成 `List<IngestedFile>`（唯一新增：名字里带 `U+FFFD` 时拒绝 `invalid_zip_name`）
2. `Junk` 丢掉打包垃圾，留下 `dropped[]`（**在剥掉单一顶层目录之前**——macOS 造的包会把 `__MACOSX/` 放在最外层，先丢它才认得出真正的单一根）
3. 剥单一顶层目录（现有逻辑不动）
4. 每条 `relpath` 过 `PathCanon.normalize`（逐段 NFC）
5. `PathCanon.check` 逐条校验（在 `SkillPaths.check` 的结构规则之外加跨系统那几条）
6. `PathCanon.collisions` 查大小写 / 编码写法
7. 拆出 sidecar（现有逻辑，位置随 NFC 归一之后）
8. 内容文件逐个过 `TextCanon`，得到新字节与 `normalized[]`
9. `SKILL.md` 解析、上限、构造 `SkillUpload`（现有逻辑）

顺序的理由：**碰撞必须在归一之后查**，否则同一个名字的两种编码写法会被当成两个文件；**内容归一只发生在 9 之前**，`SKILL.md` 的 frontmatter 解析因此天然不必再处理 BOM/CRLF。

### 两条不校验的路径

- **删除的源路径、改名的源路径**：不动。旧数据里可能有当年合法、今天不合规的名字，删除与改名是它们唯一的出路（与 `ReservedPaths` 对源路径的既有取舍同一条）。
- **二进制文件**：`TextCanon` 判为二进制时原样返回，`.bat` / `.cmd` 也按二进制对待（PRD §做 #9）。

### `TextCanon` 的判定顺序

1. `relpath` 以 `.bat` / `.cmd` 结尾（不分大小写）→ 原样返回
2. 以 `FE FF` 或 `FF FE` 开头 → 严格解码 UTF-16，转 UTF-8（`utf16_to_utf8`）
3. 严格 UTF-8 解码失败 → 原样返回（二进制）
4. 去掉开头的 `U+FEFF`（`bom_removed`）
5. `CRLF` / 单独的 `CR` → `LF`（`newline_lf`）
6. `NFC`（`nfc`）

第 2 步先于第 3 步，因为 UTF-16 的字节多半解不成合法 UTF-8；第 3 步先于第 4 步，因为「有 BOM 但后面是乱码」的文件既不是文本也不是我们该动的字节。

### 与既有模块的关系

- **不改 `SkillPaths`**：它是结构规则（空段、`..`、反斜杠），两个调用方（`ZipReader` 与 `DraftFileWrite.requirePath`）共用；跨系统那几条放在 `PathCanon`，由两处各自在结构规则之后调用。
- **不改 `Manifest` / `digest`**：归一化发生在 `blobs.put` 之前，manifest 只看见归一后的字节。ADR 0117 取代的是 ADR 0005 §后果里「上传时不得改写内容」一句，算法本身不动。
- **`IngestedFile.isBinary`** 仍然只做「是不是合法 UTF-8」的探测；归一后的文本为 `false`，UTF-16 转来的也是 `false`——这正是它该说的。

## 数据模型

**没有新表、没有新列。**

审计 `audit_event.detail`（TEXT，JSON）多两个键。**提交两条都写**（没发生归一就是 `[]`，读者不必区分「没有」和「没有这个字段」）；**写路径只在真的改了字节时才写 `normalized`**——普通写入每一条都写，会让真正要紧的那一行更难找：

| 动作 | 新增键 | 形状 |
|---|---|---|
| `submit`（上传） | `normalized` | `[{"path": "SKILL.md", "changes": ["newline_lf", "nfc"]}]`；没变就是 `[]` |
| `submit`（上传） | `dropped` | `[".DS_Store", "__MACOSX/._SKILL.md"]` |
| `version_file_write`（op 为 `write` / `create`） | `normalized` | `[{"path": "<目标路径>", "changes": [...]}]`；没变不写这个键 |

`changes` 的取值就是一个封闭集合：`utf16_to_utf8`、`bom_removed`、`newline_lf`、`nfc`。

**提交成功的响应多一个字段**：`dropped: string[]`（PRD A5「结果里说明忽略了哪几个」）。`normalized` 只进审计，不进响应——它在正常的 Windows 提交上是每一条都发生的事，说出来只是噪音。

## 接口

### 错误码与 `details`

错误信封形状不变（`ApiError`：`{code, message, details:[{field, issue}]}`）。

| 路由 | HTTP | `code` | `details` |
|---|---|---|---|
| zip 上传 | 400 | `invalid_upload` | 每个问题一条；路径类问题的 `field` 是那条 `relpath` |
| `write` / `edit` / `create` | 400 | `invalid_path` | `field` 是目标路径（碰撞时为两条，见下） |

新增的 `issue` 取值（与 PRD §措辞 的原因码一一对应）：

| `issue` | 何时 | `field` |
|---|---|---|
| `windows_invalid_char` | 段里有 `< > : " \| ? *` 或控制字符 | 那条路径 |
| `windows_reserved_name` | 段的主干（第一个 `.` 之前，不分大小写）是 `CON` / `PRN` / `AUX` / `NUL` / `COM1–9` / `LPT1–9` | 那条路径 |
| `trailing_space_or_dot` | 段以空格或 `.` 结尾 | 那条路径 |
| `path_too_long` | 单段 UTF-8 超过 255 字节，或整条超过 1024 字节 | 那条路径 |
| `case_collision` | 两条路径只差大小写 | **两条 detail**，`field` 各是其中一条 |
| `unicode_collision` | 两条路径 NFC 之后相同、原文不同 | **两条 detail**，同上 |
| `invalid_zip_name` | zip 里的名字解出来带 `U+FFFD` | 空字符串（原名已经丢了） |

**碰撞为什么返回两条**：句子要同时点出两个名字（「A.md 与 a.md：…」），而 `Detail` 只有 `field` 与 `issue`。两条相邻、同一 `issue`，客户端按下标两两配对成一行。**服务端不截断**：组内两个两个地配，每条路径最多出现一次，所以结果随文件数线性增长——512 个文件的上限下最多 256 对、512 条 detail。截断会让客户端数不出「还有 N 个」，而这一版的承诺是一次说全（PRD §措辞）。

**写路径上的碰撞**：同样两条（目标路径 + 已有那条），`field` 各自是自己，且**服务端把请求里的那条写在前面**——单行渲染只取第一条，不需要客户端反推哪条是刚写的。

### 客户端读什么

- **CLI**：`api.ServerError` 增加**有序**的 `DetailList []IssueDetail`（`Details map[string]string` 保留，别处还在用；`Detail` 这个名字已经是 manifest 的类型）。提交的失败渲染按 `DetailList` 的相邻配对走；`write` / `edit` 的单条渲染取第一条（即请求里那条，见上）。
- **控制台**：编辑器行内校验在**本地**跑同一套规则（`src/nameRules.ts`，由 `editor/files.ts` 的 `validatePath` 调用），服务端返回作为绕过页面时的兜底，停在**保存失败的横幅**里（`showFailure`）而不是行内——行内那一行只由本地校验产生；导入失败的块由 `details` 拼出来（最多 3 行 + 「还有 N 个。」，计数按文件）。

### CLI 输出形状（逐字见 PRD §措辞）

- `skill submit`：首句一眼（问题个数 + 「这次提交没有落库」）、每个问题一行（路径 → 原因 → 怎么改）、末行下一步路径照抄调用方写法；退出 1，stdout 为空。
- `skill write` / `skill edit`：首句一眼、一行原因、末行「改完再写一次。」。
- 成功且丢了垃圾：结果里多一行「忽略了 N 个打包垃圾：…」（按 `dropped` 原样列，最多 10 个，超出「还有 N 个」）。

## 分期

一期交付，三块并行推进、按顺序合入：

| 期 | 内容 | 完成判据 |
|---|---|---|
| P1 服务端 | `modules/canon` ＋ 四类写入口接入 ＋ 拒绝原因 ＋ 审计 ＋ 响应里的 `dropped` | 服务端单测与 `DraftFileIT` / 上传集成测试覆盖 A1–A6、A8、A9 |
| P2 CLI | `DetailList`、提交的逐条渲染、`write` / `edit` 的单条渲染、建议名的本地查重、成功时列出 `dropped` | CLI 单测逐字断言 PRD §措辞 的首句 / 每条 / 末句 |
| P3 控制台 | 编辑器行内规则搬进 `src/editor/files.ts`（从设计件复制）、导入失败块、`errors.ts` 的原因表 | 前端单测覆盖行内拦截与导入块；界面请人看 |

**明确不做**（PRD §不做 逐条对应）：不迁移旧版本；不改名、不改正文链接；不猜非 UTF-16 的编码；不统一末尾换行；不存权限与时间戳；不接受后警告；CLI 不做上传前的本地预检（**只做建议名的重名确认**，那是为了给出正确建议，不是预检拒绝）。

## 开放问题

1. **`.bat` / `.cmd` 按二进制对待，会不会让 Windows 作者觉得「我明明上传了文本」？** 当前倾向：按 PRD 定（cmd.exe 对只有 LF 的批处理可能出错，转坏了比不转更糟）。真出现反馈再加一个「这个文件没有归一」的说明。
2. **控制台编辑器在本地挡住之后，服务端还会不会自己拒绝？** 会，且必须会（ADR 0011：客户端校验永远不是权威）。两条路径的句子是同一份文案的两个来源，测试里逐字对齐（A10）。
3. **`dropped` 是否需要上限？** 当前倾向：响应与审计都原样列；一个包最多 512 个文件，JSON 撑不大。CLI 打印才截断（10 个 + 还有 N 个）。
