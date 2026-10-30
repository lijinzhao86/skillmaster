# v32-online-editing · 测试方案

> **最后更新**：2026-10-30
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收 | 用例 |
|---|---|
| A1 `edit` 的文件输入与互斥、UTF-8、空 old | T1、T2 |
| A2 成功回显 hunk 与版本状态 | T3、T4、T15 |
| A3 行号诊断 | T5 |
| A4 空白诊断 | T6 |
| A5 最接近的行 / 低于阈值 | T7 |
| A6 多处匹配次数与行号 | T8 |
| A7 帮助同步 | T9 |
| A8 既有契约不变 | T10 |
| A12 行尾不参与匹配、写回按文件风格、没有改动即拒绝、服务端 no-op | T16、T17、T18、T19 |
| A13 空内容要显式 | T20 |
| A14 `write` 默认只新建（`--overwrite`） | T22、T21 |
| A9 `read --raw`（二期） | T11（未执行） |
| A10 `write` 拒绝行号渲染（二期） | T12 |
| A11 `@new` 下一步与钉不变（三期） | T13（未执行） |

## 一期用例

### T1 · 从文件取内容

- **前置**：假服务端提供草稿 `K3M9QX2P` 的文件 `SKILL.md`，内容 `alpha\nnpm install\nomega\n`。
- **步骤**：把 `npm install\n` 与 `npm ci\n`（**带结尾换行**，这是编辑器保存的默认形态）写进两个临时文件，用 `--old-file` / `--new-file` 调 `edit`。
- **期望**：PUT 的正文是 `alpha\nnpm ci\nomega\n`；成功；回显里没有 panic。
- **判定**：通过／失败

### T2 · 互斥与坏输入

- **步骤**：分别试 `--old` + `--old-file`、`--new ""` + `--new-file`、`--old-file` 缺值、`--old-file` 指向不存在的文件、指向非 UTF-8 文件、指向空文件、两边都不给。
- **期望**：缺值 / 同时给 / 空文本 → 退出 2 且无 PUT；读文件失败与非 UTF-8 → 退出 1 且无 PUT。
- **判定**：通过／失败

### T3 · 成功回显

- **前置**：文件 9 行，第 5 行是 `npm install`。
- **步骤**：`--old npm install --new npm ci`。
- **期望**：stderr 依次是「已修改 SKILL.md（digest）。」、`@@ -2,7 +2,7 @@`（上下各 3 行上下文）、`-npm install`、`+npm ci`、「（这一版现在是 `<地址>@K3M9QX2P`；再读拿到的就是这份内容。）」；stdout 为空。
- **判定**：通过／失败

### T4 · `--replace-all` 的 hunk 与截断

- **步骤**：同一段出现在三行（其中一行出现两次），加 `--replace-all`；再造一个 70 行整体替换触发上限；再造一个「第一段 hunk 恰好 60 行、后面还有一处改动」的输入；再造一个只改行尾（末尾换行）的输入。
- **期望**：每一处都画成 `-`/`+`，不出现把已改行当上下文的行；同一行两处合并成一条 `-`/`+`；超限时出现「只显示前 N 行」（N 是实际打印行数）与 `version diff` 命令；恰好 60 行且后面还有改动时**同样**打截断提示；只改行尾时打「只改了行尾」。
- **判定**：通过／失败

### T5 · 行号诊断

- **步骤**：`--old` 给 `4\tnpm install`（从 `read` 复制来的形状）。
- **期望**：错误说「带着 read 的行号」，给出 `skmr skill cat <地址> <路径>`；**不发 PUT**。
- **边界**：内容里本来就有一行 `3\tfoo`，`--old` 是 `3\tfoo` 且确实存在 → 正常替换，不误报；`--old` 是 `1\talpha\n2\tgamma` 而内容是 `1\talpha\n2\tbeta`（编号连续但去号后不吻合）→ 报行号诊断。
- **判定**：通过／失败

### T6 · 空白诊断

- **步骤**：`--old` 的每一行末尾多一个空格，其它逐字相同。
- **期望**：错误说「只差空白」，给出实际行号范围与实际写法；**不发 PUT**。
- **边界**：下一行是 `npm install2` 时仍判为空白差异（整行对齐优先）。
- **判定**：通过／失败

### T7 · 最接近的行

- **步骤**：`--old` 给 `npm instxl`（一个字符不同）。
- **期望**：错误给出最接近的一行与行号；相似度低于 0.6 时给「没找到」与取原文命令。
- **判定**：通过／失败

### T8 · 多处匹配次数与行号

- **步骤**：同一段出现在三行，不加 `--replace-all`；另一例一行里出现两次；再一例出现在 7 行。
- **期望**：第一例列三个行号；第二例说「2 次，在第 1 行」；第三例列 6 个行号并说「等 7 行」。
- **判定**：通过／失败

### T15 · `@new` 的回显

- **前置**：假服务端提供 80 行的 `SKILL.md` 底稿 `1.0.0`，新建草稿返回 `K3M9QX2P`。
- **步骤**：`skill edit <地址>@new SKILL.md --name 第二版 --old <前 70 行，换成一个词> --new one`。
- **期望**：stdout 为空；stderr 顺序是「已修改并建出草稿」+「下一步：…」→ hunk →「这一版现在是 `<地址>@K3M9QX2P`」；截断那一行是「只显示前 N 行；完整改动：`skmr version diff <地址>@K3M9QX2P`」。
- **判定**：通过／失败

### T9 · 帮助

- **步骤**：`skmr skill edit --help`。
- **期望**：Usage 含两个文件参数；`--old-file` / `--new-file` 有说明；Output 写明「已修改那一行 / hunk / 草稿码」的顺序；失败栏的 `not_found` 指向 `skill cat`；例子可粘贴。
- **判定**：通过／失败

### T10 · 既有契约回归

- **步骤**：精确匹配唯一性、0 匹配拒绝、`If-Match` 409 文案、`@new` 建草稿只读一次底稿、`@new` 缺 `--name` 零请求、`@new` 回显带新建草稿码。
- **期望**：既有用例全绿；用法错误仍是退出 2、stdout 空、stderr ≤ 200 字节、结尾「用法见：…」。
- **判定**：通过／失败

### T16 · 行尾符不参与匹配

- **前置**：假服务端的 `SKILL.md` 内容为 `alpha\r\nnpm install\r\nomega\r\n`（CRLF）。
- **步骤**：`--old` 给 `npm install`（LF，无行尾符差异以外的不同）→ `--new` 给 `npm ci`；再用 LF 内容的文件、CRLF 写法的 `--old` 反过来跑一次；CRLF 文件上再跑一次 `--replace-all`。
- **期望**：三次都成功（含 CRLF 文件上 `--replace-all` 的那次）；PUT 的正文是被改的那一段，且**行尾符与文件原风格一致**（CRLF 文件里不出现单独的 LF）；回显只画 `-npm install` / `+npm ci`。
- **判定**：通过／失败

### T17 · 写回按文件风格（`.bat`）

- **前置**：假服务端提供 `scripts/run.bat`，内容 `@echo off\r\nnpm install\r\n`（v33 对 `.bat` / `.cmd` 不归一化，CRLF 原样存）。
- **步骤**：用 LF 的 `--old` 改中间那一行，`--new` 写成两行的 `npm ci\nrem next`（替换文本里的换行也是 LF）。
- **期望**：PUT 的正文是 `@echo off\r\nnpm ci\r\nrem next\r\n`（`--new` 里的换行也按文件风格写成 CRLF）——**整份仍是 CRLF，没有混合行尾**。
- **判定**：通过／失败

### T18 · 没有改动可做

- **步骤**：①`--old` 与 `--new` 给同一段文本；②`--new` 只把 `--old` 的换行符写法改掉（例如 old 用 CRLF、new 用 LF，文件本身是 CRLF）。
- **期望**：两次都退出 1，stderr 分别是「`--old` 与 `--new` 相同，没有可改的内容；文件没有改动。」与「替换后与原文相同，没有可改的内容；文件没有改动。」；**PUT 计数为 0**。
- **判定**：通过／失败

### T19 · 服务端归一之后没有变化

- **前置**：假服务端在 GET 上带 `ETag`，PUT 回来时版本 digest 与它相同（模拟 v33 的 no-op：本地改动在服务端归一后与已存内容逐字节相同，例如把 NFC 的文字写成 NFD）。
- **步骤**：`--old` 给已存的 NFC 写法，`--new` 给等价的 NFD 写法。
- **期望**：退出 0；发了 1 次 PUT；stderr 只有一行「没有改动：服务端归一之后与已存的内容相同，这一版没有变。」，**没有 hunk、没有「已修改」**。
- **判定**：通过／失败

## 二期 / 三期用例

### T11 · `read --raw` 与 `cat`（未执行）

- **步骤**：对同一个文件的同一版本分别跑 `skill read --raw` 与 `skill cat`，逐字节比较 stdout；再跑默认 `skill read` 确认仍带行号。
- **期望**：两者 stdout 完全相同；默认 `read` 仍以 `N\t` 开头。
- **判定**：通过／失败

### T12 · `write` 拒绝行号渲染

- **前置**：假服务端提供草稿 `K3M9QX2P` 的文件 `SKILL.md`（内容 `alpha\nbeta\n`）。
- **步骤**：①把 `read` 的完整渲染（每个非空行 `N\t`、编号连续、末行是 `（按 … 取。）` 注记）从标准输入喂给 `skill write`；②同一份渲染走 `@new`；③反例：真实内容里本来就有 `3\tfoo` 这一行；④反例：只有行号、没有注记的文本。
- **期望**：①② 都退出 1，句子指向 `skill cat` 与 `skill edit`，**零请求**（含 `@new` 的建草稿请求）；③ 正常写入；④只有行号、没有注记的文本按内容照写。
- **判定**：通过／失败

### T13 · `@new` 的下一条命令（未执行）

- **步骤**：`skill write @new` 成功后执行它打印的下一条命令；执行前后比较本机钉文件。
- **期望**：命令可直接执行；钉不变。
- **判定**：通过／失败

### T14 · `write` 不接受文件输入

- **前置**：假服务端提供草稿的文件清单与 `SKILL.md`。
- **步骤**：`skill write <地址> SKILL.md --content-file <文件>`。
- **期望**：退出 2、零请求，句子以「用法见：skmr skill write --help」收尾；帮助页面的 Flags 里也没有这个参数（`flagattribution` 用例覆盖）。
- **判定**：通过／失败

### T20 · 空内容要显式

- **步骤**：①标准输入为空、不给 `--allow-empty`；②同样情况给 `--allow-empty`；③`@new` 上给空内容与 `--allow-empty`。
- **期望**：① 退出 2、零请求，句子是「要写的内容是空的；真要写空内容就加 --allow-empty。」；②③ 写入空内容（PUT 正文为空串 / `@new` 的 `text` 字段为空串）。
- **判定**：通过／失败

### T21 · 写进已存在版本（补齐既有空洞）

- **前置**：假服务端提供草稿的文件清单、一个已存在的 `SKILL.md`（带 ETag）与一个不存在的 `new.md`。
- **步骤**：①带 `--overwrite` 覆盖已存在的文件；②写一个不存在的路径（创建）；③写二进制；④让服务端回 409；⑤内容与已存相同（服务端回同一个 digest）。
- **期望**：① PUT 的 `If-Match` 是读到的 ETag，stderr 打「已写入 …（digest）。」；② 用清单的 ETag 作基线，文件被创建；③ 二进制原样上传；④ 句子是既有的「被改动过」那一句；⑤ 只回一句「没有改动」。
- **判定**：通过／失败

### T22 · 默认只新建，覆盖要 `--overwrite`

- **前置**：假服务端提供草稿 `K3M9QX2P`，`SKILL.md` 已存在（带 ETag），`new.md` 不存在。
- **步骤**：①对已存在的 `SKILL.md` 直接 `write`（不给开关）；②同一份内容加 `--overwrite`；③对二进制文件加 `--overwrite`；④`@new` 上给 `--overwrite`。
- **期望**：① 退出 1、**零 PUT**，句子同时点名 `skill edit` 与 `--overwrite`；②③ PUT 正文逐字节一致；④ 退出 2、零请求（那条路没有「已有草稿」）。
- **判定**：通过／失败

## 环境与数据

- **栈**：Go 单元测试 + `pointAtServer` / `newFakeServer` 假服务端；不需要真库。
- **数据**：每个用例自造 2–200 行文本，内容里故意包含 `N\t` 形状、重复段落、行尾空白与 CRLF；`--old-file` 的样本按编辑器保存的形态带结尾换行。T16–T18 另需两份 CRLF 样本（一份 `SKILL.md`、一份 `.bat`）与它们的 LF 对照；**T19、T21、T22 需要带 `ETag` 的假服务端**，PUT 回来同一个 digest（`runCaptured` 里用 `pointAtServer` 自建，`newFakeServer` 不带 ETag）。
- **清理**：临时文件用 `t.TempDir()`。

## 结果

**执行环境**：macOS arm64；`go test ./...` 全绿、`gofmt` / `go vet` 干净。另外在本机用**真二进制**（工作区构建）对本地假服务端（`127.0.0.1:18099`，一份 5 行草稿）跑了两轮：第一轮是初版，第二轮是审计修订后。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 · 从文件取内容 | **通过** | `TestEditTakesItsTextFromFilesAndShowsTheHunk`、`TestEditAcceptsOldTextWithItsOwnLineEnding`（带结尾换行，初版在此 panic）；真二进制 `--old-file`（含换行）成功 |
| T2 · 互斥与坏输入 | **通过** | `TestEditRefusesTwoSourcesForOneSide`、`TestEditRefusesAnEmptyOldFileAsAUsageError`（退出 2、无 PUT）、`TestEditRefusesANonUTF8File`、`TestEditRefusesAMissingOldFile`（退出 1、无 PUT）；T2 用例表新增四条 |
| T3 · 成功回显 | **通过** | `TestEditTakesItsTextFromFilesAndShowsTheHunk` 对 9 行文件逐字节断言 `@@ -2,7 +2,7 @@`（上下各 3 行）；真二进制输出 `@@ -1,5 +1,5 @@` + `-npm install` + `+npm ci` + 「这一版现在是 me/demo@K3M9QX2P」 |
| T4 · 多 hunk 与截断 | **通过** | `TestEditEchoesEveryOccurrenceOfReplaceAll`（三处都画成改动、无假上下文）、`TestEditEchoesTwoMatchesOnOneLine`、`TestEditEchoesADeletedLineAsAnEmptyOne`、`TestEditCapsTheEchoAndPointsAtTheDiff`、`TestEditSaysWhenTheEchoWasCutAtTheBoundary`（恰好 60 行）、`TestEditSaysWhenOnlyLineEndingsChanged` |
| T5 · 行号诊断 | **通过** | `TestEditNamesCopiedLineNumbers`、`TestEditStillMatchesContentThatLooksNumbered`、`TestEditDoesNotBlameAReadForOneNumberedLine`（单行带换行不误判）、`TestEditNamesCopiedLineNumbersWhenTheTextMoved`（多行编号不吻合）、`TestEditNamesCopiedLineNumbersWithAFooter`（带 CLI 注记）；真二进制输出「你给的原文带着 read 的行号…」 |
| T6 · 空白诊断 | **通过** | `TestEditSpotsWhitespaceOnlyDifference`（含无 PUT 断言）、`TestEditPrefersTheLineAlignedWhitespaceMatch`（`npm install2` 边界）；真二进制输出「只差空白：第 4 行…」 |
| T7 · 最接近的行 | **通过** | `TestEditPointsAtTheClosestLine`、`TestEditFallsBackWhenNothingIsClose`（低于阈值走兜底）；真二进制输出「最接近的是第 4 行：`npm ci`」 |
| T8 · 多处匹配 | **通过** | `TestEditListsEveryOccurrence`（第 1、3、5 行；同一行两处只列一次）、`TestEditCountsLinesSeparatelyFromOccurrences`（7 次、7 行、列 6 个）；真二进制输出「3 次，在第 3、5 行」 |
| T9 · 帮助 | **通过** | 真二进制 `skmr skill edit --help` 逐条读过；帮助的长度与覆盖用例全绿（这些用例只保证形状，内容由人工核对） |
| T10 · 既有契约回归 | **通过** | `go test ./...` 全绿；`@new` 一读一写、`TestEditOnNewDraftWithoutANameIsAUsageError`（退出 2、零请求）、`TestEditReportsAStaleBaseline`（PUT 409 → 「被改动过」） |
| T15 · `@new` 的回显 | **通过** | `TestEditOnNewDraftEchoCarriesTheCreatedCode`（stdout 空、顺序「建出草稿 → hunk → 这一版现在是」、`只显示前 N 行` 与 `完整改动：skmr version diff …@K3M9QX2P` 同时出现） |
| T16 · 行尾符不参与匹配 | **通过** | `TestEditMatchesAcrossLineEndings`（三个子例：CRLF 内容 + LF `--old`、LF 内容 + CRLF `--old`、CRLF 文件上的 `--replace-all`；PUT 正文逐字节断言，CRLF 文件改完仍全 CRLF） |
| T17 · 写回按文件风格（`.bat`） | **通过** | `TestEditWritesBackInTheFilesOwnLineEndings`（`scripts/run.bat` 的 CRLF 内容 + LF 的 `--old` / `--new`，PUT 正文为 `@echo off\r\nnpm ci\r\nrem next\r\n`——替换文本里的换行也按 CRLF 写） |
| T18 · 没有改动可做 | **通过** | `TestEditRefusesWhenNothingWouldChange`（`--old` 与 `--new` 相同 → 退出 1、**零请求**；只差行尾符 → 退出 1、无 PUT） |
| T19 · 服务端归一之后没有变化 | **通过** | `TestEditSaysWhenTheServerKeptItsVersion`（NFC → NFD，PUT 1 次，stderr 逐字断言为那一行） |
| T12 · `write` 拒绝行号渲染 | **通过** | `TestWriteRefusesACopiedReadRendering`（标准输入与 `@new` 两个入口都退出 1、零请求；两个反例照写：内容里的 `3\tfoo`、只有行号没有注记的文本） |
| T14 · `write` 不接受文件输入 | **通过** | `TestWriteHasNoFileInput`（`--content-file` → 退出 2、零请求；`flagattribution` 用例确认帮助里也没有这个参数） |
| T20 · 空内容要显式 | **通过** | `TestWriteNeedsAllowEmpty`（空标准输入 → 退出 2、零请求；`--allow-empty` → PUT 正文为空串；`@new` 上给 `--name` 但空输入同样退出 2、零请求）；`@new` + `--allow-empty` 真的写进空内容见 `TestTheFirstWriteSendsEmptyTextAsContent` |
| T21 · 写进已存在版本 | **通过** | `TestWriteIntoAnExistingVersion`（覆盖时 `If-Match` 是读到的 ETag；不存在的路径用清单的 ETag 创建；409 →「这一版在读过之后被改动过」；服务端 no-op →「没有改动」）；步骤 ③ 的二进制覆盖在 `TestWriteIsCreateByDefault` 的「binary replacement」子例 |
| T22 · 默认只新建 | **通过** | `TestWriteIsCreateByDefault`（已存在未给开关 → 退出 1、零 PUT、句子两条路都给；`--overwrite` 覆盖与二进制覆盖；`@new` 上给开关退出 2、零请求） |

**指标**：一次修改的调用数在真二进制上为「读原文 + 一次 `edit`」= 2；诊断命中按构造样本 4/4（行号 / 空白 / 最接近 / 多处）。

**行号污染**：一期只覆盖 `edit`；二期补上 `skill write` 的防线，由 T12 验证。

**二期**：`write` 的三条规则、那扇关上的门与写进已存在版本的用例已执行（T12、T14、T20、T21、T22）；**`read --raw`（T11）本版没做，v34 已交付**，并由 v34 T9 用 `skill cat` 做过同一次逐字节比较（通过）；本版不重复执行。

**三期**：未执行（T13 / A11）。

**一个既有用例改了**：`TestTheFirstWriteSendsEmptyTextAsContent` 加了 `--allow-empty`——空内容现在要点名（T20）；它断言的没变（`@new` 的 `text` 字段仍须出现且为空串）。

## 已知问题

本版范围内暂无记录。
