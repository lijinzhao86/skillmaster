# v32-online-editing · 测试方案

> **最后更新**：2026-10-30
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收 | 用例 |
|---|---|
| A1 文件输入与互斥、UTF-8、空 old | T1、T2 |
| A2 成功回显 hunk 与版本状态 | T3、T4、T15 |
| A3 行号诊断 | T5 |
| A4 空白诊断 | T6 |
| A5 最接近的行 / 低于阈值 | T7 |
| A6 多处匹配次数与行号 | T8 |
| A7 帮助同步 | T9 |
| A8 既有契约不变 | T10 |
| A9 `read --raw`（二期） | T11（未执行） |
| A10 `write` 拒绝行号（二期） | T12（未执行） |
| A11 `@new` 下一步与钉不变（三期） | T13（未执行） |
| A12 `write --content-file`（二期） | T14（未执行） |

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

## 二期 / 三期用例（未执行）

| 用例 | 内容 | 对应 |
|---|---|---|
| T11 | `read --raw` 与 `cat` 对同一文件的 stdout 逐字节相同；默认 `read` 仍带行号 | A9 |
| T12 | `write` 拒绝行号渲染内容；去掉行号后可以写入 | A10 |
| T13 | `@new` 打印的下一条命令可直接执行；执行前后本机钉不变 | A11 |
| T14 | `write --content-file`：空文件写入空内容、非 UTF-8 拒绝 | A12 |

## 环境与数据

- **栈**：Go 单元测试 + `pointAtServer` / `newFakeServer` 假服务端；不需要真库。
- **数据**：每个用例自造 2–200 行文本，内容里故意包含 `N\t` 形状、重复段落、行尾空白与 CRLF；`--old-file` 的样本按编辑器保存的形态带结尾换行。
- **清理**：临时文件用 `t.TempDir()`。

## 结果

**执行环境**：macOS arm64；`go test ./...` 全绿、`gofmt` / `go vet` 干净。另外在本机用**真二进制**（工作区构建）对本地假服务端（`127.0.0.1:18099`，一份 5 行草稿）跑了两轮：第一轮是初版，第二轮是审计修订后。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 · 从文件取内容 | **通过** | `TestEditTakesItsTextFromFilesAndShowsTheHunk`、`TestEditAcceptsOldTextWithItsOwnLineEnding`（带结尾换行，初版在此 panic）；真二进制 `--old-file`（含换行）成功 |
| T2 · 互斥与坏输入 | **通过** | `TestEditRefusesTwoSourcesForOneSide`、`TestEditRefusesAnEmptyOldFileAsAUsageError`（退出 2、无 PUT）、`TestEditRefusesANonUTF8File`、`TestEditRefusesAMissingOldFile`（退出 1、无 PUT）；T10 用例表新增四条 |
| T3 · 成功回显 | **通过** | `TestEditTakesItsTextFromFilesAndShowsTheHunk` 对 9 行文件逐字节断言 `@@ -2,7 +2,7 @@`（上下各 3 行）；真二进制输出 `@@ -1,5 +1,5 @@` + `-npm install` + `+npm ci` + 「这一版现在是 me/demo@K3M9QX2P」 |
| T4 · 多 hunk 与截断 | **通过** | `TestEditEchoesEveryOccurrenceOfReplaceAll`（三处都画成改动、无假上下文）、`TestEditEchoesTwoMatchesOnOneLine`、`TestEditEchoesADeletedLineAsAnEmptyOne`、`TestEditCapsTheEchoAndPointsAtTheDiff`、`TestEditSaysWhenTheEchoWasCutAtTheBoundary`（恰好 60 行）、`TestEditSaysWhenOnlyLineEndingsChanged` |
| T5 · 行号诊断 | **通过** | `TestEditNamesCopiedLineNumbers`、`TestEditStillMatchesContentThatLooksNumbered`、`TestEditDoesNotBlameAReadForOneNumberedLine`（单行带换行不误判）、`TestEditNamesCopiedLineNumbersWhenTheTextMoved`（多行编号不吻合）、`TestEditNamesCopiedLineNumbersWithAFooter`（带 CLI 注记）；真二进制输出「你给的原文带着 read 的行号…」 |
| T6 · 空白诊断 | **通过** | `TestEditSpotsWhitespaceOnlyDifference`（含无 PUT 断言）、`TestEditPrefersTheLineAlignedWhitespaceMatch`（`npm install2` 边界）；真二进制输出「只差空白：第 4 行…」 |
| T7 · 最接近的行 | **通过** | `TestEditPointsAtTheClosestLine`、`TestEditFallsBackWhenNothingIsClose`（低于阈值走兜底）；真二进制输出「最接近的是第 4 行：`npm ci`」 |
| T8 · 多处匹配 | **通过** | `TestEditListsEveryOccurrence`（第 1、3、5 行；同一行两处只列一次）、`TestEditCountsLinesSeparatelyFromOccurrences`（7 次、7 行、列 6 个）；真二进制输出「3 次，在第 3、5 行」 |
| T9 · 帮助 | **通过** | 真二进制 `skmr skill edit --help` 逐条读过；帮助的长度与覆盖用例全绿（这些用例只保证形状，内容由人工核对） |
| T10 · 既有契约回归 | **通过** | `go test ./...` 全绿；`@new` 一读一写、`TestEditOnNewDraftWithoutANameIsAUsageError`（退出 2、零请求）、`TestEditReportsAStaleBaseline`（PUT 409 → 「被改动过」） |
| T15 · `@new` 的回显 | **通过** | `TestEditOnNewDraftEchoCarriesTheCreatedCode`（stdout 空、顺序「建出草稿 → hunk → 这一版现在是」、`只显示前 N 行` 与 `完整改动：skmr version diff …@K3M9QX2P` 同时出现） |

**指标**：一次修改的调用数在真二进制上为「读原文 + 一次 `edit`」= 2；诊断命中按构造样本 4/4（行号 / 空白 / 最接近 / 多处）。

**行号污染**：一期只覆盖 `edit`；`skill write` 的防线在二期，尚未验证，因此本版不把这一指标记为达成。

**二期、三期**：未执行（对应 A9–A12）。

## 已知问题

本版范围内暂无记录。
