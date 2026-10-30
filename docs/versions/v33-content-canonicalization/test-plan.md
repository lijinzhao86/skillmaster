# v33-content-canonicalization · 测试方案

> **最后更新**：2026-10-30
> **所属版本**：[`README.md`](README.md)
> **状态**：已执行（结果见 §结果）

<!--
验收映射覆盖 PRD 的每一条，不多不少。每条用例都有前置 / 步骤 / 期望。
结果一节如实：通过写通过、未执行写未执行。
-->

## 验收映射

| 验收 | 用例 |
|---|---|
| A1 文本归一逐项 | S1、S2、S3、S4、S5 |
| A2 跨平台幂等 | S6 |
| A3 路径拒绝逐条 | S7、S8、S18；C2、C3 |
| A4 碰撞给两条 detail | S9、S10、S19；C4 |
| A5 垃圾丢弃并报告 | S11、S12；C5 |
| A6 审计留痕 | S13、S14 |
| A7 网页两处 | W1、W2、W3、W4 |
| A8 旧版本不变 | S15 |
| A9 拒绝零落库 | S16、S17、S19 |
| A10 文案逐字一致 | C2、C3、C6、C7、C8、C9；W4、W5 |

## 用例

### 服务端（`skillmaster-server`）

**S1 · 换行归一**（A1）
前置：`TextCanon.canonicalize` 可调用。步骤：同一段文字分别以 `CRLF`、单独 `CR`、`LF` 传入。期望：三者输出字节相等且都是 `LF`；`changes` 含 `newline_lf`（`LF` 输入不含）。

**S2 · BOM 与 UTF-16**（A1）
前置：同上。步骤：① UTF-8 BOM + 文本；② UTF-16LE BOM + 同一段文字；③ UTF-16BE BOM。期望：三者输出与纯 `LF` 版本字节相同；① 含 `bom_removed`，②③ 含 `utf16_to_utf8`（BOM 的移除由 `utf16_to_utf8` 一并表达）。

**S3 · NFC**（A1）
前置：同上。步骤：内容里放一段 NFD 文字。期望：输出为 NFC；`changes` 含 `nfc`。

**S4 · 二进制与批处理原样**（A1）
前置：同上。步骤：① 含 `0x00` 的字节；② 非法 UTF-8；③ 文本内容但文件名 `run.bat`、`RUN.CMD`。期望：三者输出与输入逐字节相同，`changes` 为空。

**S5 · 不补末尾换行**（A1）
前置：同上。步骤：内容 `"a\nb"`（无末尾换行）。期望：输出仍是 `"a\nb"`。

**S6 · 同一文字只产生一版**（A2）
前置：`SkillUploadValidator` 可直接调用。步骤：同一份 skill 打成 `CRLF + BOM` 与 `LF` 两个包，各自过校验，按 ADR 0005 的公式算 digest。期望：两个 digest 相同；库级幂等由 `SkillSubmitIT` 的重放用例覆盖。

**S7 · 路径规则逐条**（A3）
前置：`PathCanon.check` 可调用。步骤：依次喂 `报告:v2.md`、`CON.md`、`con.txt`、`notes.md `（结尾空格）、`报告 .md.`、300 字节的段、1030 字节的整条、`notes .md`（中间有空格）。期望：前七个依次得到 `windows_invalid_char`、`windows_reserved_name`、`windows_reserved_name`、`trailing_space_or_dot`、`trailing_space_or_dot`、`path_too_long`、`path_too_long`；最后一个是合法的（Windows 只去段尾）。

**S8 · 上传被拒时带路径与原因**（A3）
前置：造得出 zip（`support.Zips`）。步骤：上传一个含 `报告:v2.md` 的包。期望：400，`code=invalid_upload`，`details` 里有一条 `field="报告:v2.md"`、`issue="windows_invalid_char"`。

**S9 · 大小写碰撞**（A4）
前置：同上。步骤：一个包里同时有 `A.md` 与 `a.md`。期望：400，`details` 恰有两条 `case_collision`，`field` 分别是两个名字。

**S10 · 编码写法碰撞**（A4）
前置：同上。步骤：一个包里同时有 NFC 与 NFD 写法的 `café.md`。期望：400，两条 `unicode_collision`，`field` 分别是两种原文。

**S11 · 垃圾被丢弃**（A5）
前置：同上。步骤：包里带 `__MACOSX/._SKILL.md`、`.DS_Store`、`notes/.DS_Store`、`~$draft.docx`、`.git/config`。期望：上传成功；`dropped` 正好是这五个；清单里一个都没有。

**S12 · 垃圾不影响单一根目录的判定**（A5）
前置：同上。步骤：`pdf-tools/…` 的包外层再加一个 `__MACOSX/`；另造一个根目录名与 frontmatter `name` 不符的包（同样带垃圾）。期望：前者仍剥掉 `pdf-tools/`；后者按 `name_does_not_match_directory` 被拒。

**S13 · 提交的审计**（A6）
前置：测试库；一个带 CRLF 文件与 `.DS_Store` 的包。步骤：提交。期望：201；响应 `dropped` 含 `.DS_Store`；`audit_event` 最新一行的 `detail.normalized` 含 `references/notes.md` 与 `newline_lf`，`detail.dropped` 含 `.DS_Store`。

**S14 · 写的审计**（A6）
前置：测试库；一个草稿。步骤：`PUT` 一个 CRLF 文本文件。期望：200；`audit_event` 的 `detail.normalized` 那条 `path` 是目标路径、`changes` 含 `newline_lf`。

**S15 · 旧版本字节不变**（A8）
前置：库里有一版本版之前原样入库的 CRLF 内容。步骤：读取它。期望：字节与当年一致、digest 不变。**注意**：这样的行只能手工造，本版没有夹具——见 §结果。

**S16 · 上传被拒零落库**（A9）
前置：测试库。步骤：跑 S9，然后列该 skill 的版本与文件。期望：版本数、草稿、清单与提交前完全一样。（拒绝发生在 `SkillUploadValidator` 里，早于任何写。）

**S17 · 写被拒零落库**（A9）
前置：测试库；一个草稿。步骤：对一个草稿 `PUT` 到已被结构规则拒绝的路径（如空段）。期望：400 `invalid_path`；再读这一版，digest 与清单都没变。

**S18 · 解不出的 zip 名字**（A3）
前置：同上。步骤：包里含一个带 `U+FFFD` 的名字。期望：400，`invalid_zip_name`，`field` 为空字符串。

**S19 · 写路径的碰撞**（A4、A9）
前置：测试库；草稿里有 `references/a.md`。步骤：`PUT references/A.md`。期望：400 `invalid_path`，两条 `case_collision`（`field` 各是其中之一）；再列文件，`references/A.md` 不在。

### CLI（`skillmaster-cli`）

**C1 · CLI 不改写字节**（不再映射到 A1）
前置：假服务端记录请求体。步骤：`skmr skill write` 一个 CRLF 文件。期望：请求里的 `text` 是 CRLF 原样（归一发生在服务端，见 S1–S5、S14）。**未执行**：没有单测钉住。

**C2 · 提交被拒：逐条输出**（A3、A10）
前置：假服务端答 400 `invalid_upload`，`details` 为 `报告:v2.md` / `windows_invalid_char`、`CON.md` / `windows_reserved_name`、`A.md` 与 `a.md` / `case_collision`。步骤：`skmr skill submit <目录>`。期望：stderr 逐字等于 PRD §措辞 的形状（首句 4 个文件名、三条问题、末句）；stdout 为空；退出码 1。

**C3 · `write` 被拒：单条输出**（A3、A10）
前置：假服务端答 400 `invalid_path`，`details` 为 `CON.md` / `windows_reserved_name`。步骤：`skmr skill write`。期望：stderr 逐字等于 PRD §措辞 的 write 形状（本地树不可见，因此不给建议）。

**C4 · 碰撞的行配对**（A4）
前置：`details` 两条 `case_collision`。步骤：渲染。期望：一行「A.md 与 a.md：两个名字在 macOS 和 Windows 上会被当成同一个文件。改掉其中一个」，不是两行。

**C5 · 成功时列出丢弃**（A5）
前置：假服务端答 201，`dropped` 两项。步骤：提交。期望：stdout 有一行「忽略了 2 个不是 skill 内容的文件：.DS_Store、__MACOSX/._SKILL.md」。

**C6 · 超过 10 条**（A10）
前置：`details` 造 13 个单条问题。步骤：渲染。期望：只列 10 条，第 11 行是「还有 3 个。」。

**C7 · 建议名**（A10）
前置：临时目录里没有替换后的名字。步骤：① `报告:v2.md` → 建议「改成「报告-v2.md」」；② 目录里已有 `报告-v2.md` → 不给改法。

**C8 · 末行路径照抄**（A10）
前置：同上。步骤：`skmr skill submit ./pdf-tools`。期望：末行是「改完再提交：skmr skill submit ./pdf-tools」。

**C9 · 保留名的建议**（A10）
前置：临时目录。步骤：① 目录里没有 `_CON.md` → 「CON.md：…改成「_CON.md」」；② 已有 `_CON.md` → 退回「换一个名字」。

### 控制台（`skillmaster-console`）

**W1 · 编辑器新建挡设备名**（A7）
前置：草稿页，文件列表默认。步骤：新建文件输入 `CON.md` 回车。期望：不发送请求；红字「CON 是 Windows 保留的设备名。」；输入值仍是 `CON.md`。

**W2 · 编辑器新建挡大小写碰撞**（A7）
前置：草稿里有 `references/split.md`。步骤：新建 `references/SPLIT.md`。期望：不发送请求；红字「与已有的 references/split.md 在 macOS 和 Windows 上会被当成同一个文件。」。

**W3 · 改名同一套**（A7）
前置：同上。步骤：把 `references/notes.md` 改名为 `references/SPLIT.md`。期望：不发送请求；同一行显示碰撞句。

**W4 · 导入失败块**（A7、A10）
前置：假接口返回 5 个文件的 4 个问题。步骤：选包、填名称、点导入。期望：首句「压缩包里有 5 个文件名不能用在所有系统上，没有导入。」，三行「路径：原因。」、「还有 1 个。」、「改好压缩包再选一次。」。

**W5 · 文案与设计件一致**（A10）
前置：`design/README.md`、`design/src/console/fixtures/editor.ts`、`src/nameRules.ts`、`cmd/skillmaster/uploadissues.go` 四处。步骤：逐条对照句子与规则。期望：字符级一致（人工核对，记录在案）。

## 环境与数据

- **服务端单测**：`cd skillmaster-server && SKILLMASTER_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/skillmaster_test SKILLMASTER_TEST_DB_USER=skillmaster SKILLMASTER_TEST_DB_PASSWORD=skillmaster ./mvnw -B verify`（集成测试用同一个库）。
- **CLI**：`cd skillmaster-cli && go test ./...`；假服务端在测试内起。
- **控制台**：`cd skillmaster-console && npx tsc --noEmit && npm test`。
- **清理**：集成测试各自建自己的 skill 名（带随机后缀），`@Sql` 在类前清表，不依赖执行顺序。

## 结果

服务端 `./mvnw -B verify`：单测 **312** 个、集成测试（failsafe）**531** 个，合计 **843 个全部通过**（测试库 `skillmaster_test`）。CLI `go test ./...` 全绿；控制台 `npx tsc --noEmit` 与 `vitest run`（597 个测试）全绿。

| 用例 | 结果 | 证据 |
|---|---|---|
| S1–S5 | 通过 | `TextCanonTest` |
| S6 | 通过 | `UploadCanonicalizationTest.theSameTextInTwoLineEndingsMakesOneDigest` |
| S7 | 通过 | `PathCanonTest.eachRuleHasItsOwnIssue` |
| S8 | 通过 | `UploadCanonicalizationTest.aNameWindowsCannotRestoreIsRefusedWithItsPath` |
| S9、S10 | 通过 | `UploadCanonicalizationTest`（`twoNamesThatOneFilesystemMergesAreBothNamed`、`twoSpellingsOfOneNameAreACollisionAndNotTwoFiles`） |
| S11、S12 | 通过 | `UploadCanonicalizationTest.archiveJunkIsDroppedAndReported`、`junkDoesNotHideTheSingleRootDirectory`（含目录名不一致的拒绝） |
| S13 | 通过 | `SkillSubmitIT.aSubmissionRecordsWhatTheDoorRewroteAndDiscarded` |
| S14 | 通过 | `DraftFileIT.aCanonicalisingWriteLeavesTheChangeOnTheAuditTrail` |
| S15 | **未验证** | 读取路径未改动（`SkillDistributionService` 不在本版 diff 里），但这种脏行没有夹具 |
| S16 | **部分** | 拒绝在 `SkillUploadValidator` 里、任何写之前抛出；没有单独的 IT 断言提交被拒后版本数不变 |
| S17 | 通过 | `DraftFileIT` 既有的「写被拒后清单不变」用例 |
| S18 | 通过 | `UploadCanonicalizationTest.aNameThatCouldNotBeDecodedHasNoPathToPointAt` |
| S19 | 通过 | `DraftFileIT.aWriteThatDiffersOnlyByCaseFromAnExistingNameIsRefused` |
| C1 | **未执行** | 无单测；「CLI 原样发送」是代码结构事实 |
| C2–C9 | 通过 | `cmd/skillmaster/uploadissues_test.go`（逐字断言，含建议名被占用、超过 10 条、碰撞合并、`dropped`、单条 write、保留名 `_` 建议、端到端 400） |
| W1–W3 | 通过 | `tests/version-editor.test.tsx` |
| W4 | 通过 | `tests/skill-transfer.test.tsx` |
| W5 | 通过（人工） | 四处逐条对照，字符一致 |

**两处既有测试按新契约改过**（不是为了让测试变绿）：

- `DraftFileIT.crlfAndAMissingFinalNewlineComeBackByteForByte` → `aWriteIsCanonicalisedAndTheMissingFinalNewlineSurvives`：它断言的是 ADR 0005 的旧承诺，ADR 0117 取代的正是这一条；缺末尾换行仍然保留。
- `SkillSubmitIT.submitsAZipAndReturnsTheCreatedVersion`：响应多了 `dropped`，字段名清单跟着加一条并断言它是数组。

**未执行的都写在这里**：S15、S16 的独立断言、C1。它们不改变「本版交付」的判断，但也不是「已验证」。

## 已知问题

**未发现**（审计发现的缺陷已全部修掉，记在各自的提交里）。
