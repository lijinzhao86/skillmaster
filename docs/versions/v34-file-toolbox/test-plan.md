# v34-file-toolbox · 测试方案

> **最后更新**：2026-10-30
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收 | 用例 |
|---|---|
| A1 `ls` 的一层、`-l`、`-R` 与两种错误 | T1、T2、T3、T4 |
| A2 `files` 退役 | T5 |
| A3 `find` 的四类匹配与无命中 | T6 |
| A4 `stat` 的文件、目录、摘要、清单带摘要 | T7、T14 |
| A5 `head` / `tail` | T8 |
| A6 `read --limit` / `--raw` | T9 |
| A7 `grep -C/-A/-B` | T10 |
| A8 `grep -e/--exclude/--max-count` | T11 |
| A9 `edit --regex` | T12 |
| A10 既有契约不变 | T13 |
| A11 invoke 头部的能力清单 | T15 |

## 用例

### T1 · `ls` 列一层

- **前置**：假服务端的清单含 `SKILL.md`、`references/api.md`、`references/split.md`、`assets/` 下两张图。
- **步骤**：`skill ls <地址>`；再 `skill ls <地址> references`。
- **期望**：根一层打出 `assets/`、`references/`、`SKILL.md`——目录在前、文件在后，各自按名字排；子目录一层只打 `api.md`、`split.md`；版本注记在 stderr。
- **判定**：通过／失败

### T2 · `ls -l` 的类型与大小

- **步骤**：`skill ls <地址> -l`。
- **期望**：三列 `<类型> <字节> <名字>`；`SKILL.md` 的类型是 `body`，图片是 `bin`，目录是 `dir` 且字节为 `-`；字节数与清单里的 `size` 一致。
- **判定**：通过／失败

### T3 · `ls -R`

- **步骤**：`skill ls <地址> -R`。
- **期望**：打出该目录下所有条目，每行一条完整相对路径，目录行带 `/`，按路径排序。
- **判定**：通过／失败

### T4 · `ls` 的两种错误

- **步骤**：①`skill ls <地址> nowhere`；②`skill ls <地址> SKILL.md`。
- **期望**：①退出 1，句子给出「用 skmr skill ls <地址> 看根目录」；②退出 1，句子说它是文件、指向 `read` / `cat`。都不发文件请求（只用清单）。
- **判定**：通过／失败

### T5 · `files` 退役

- **步骤**：①`skill files <地址>`；②`skill --help` 与 `skill files --help`；③敲一个错名字看建议列表；④`skmr help workflow`。
- **期望**：①退出 2，stderr 一句话同时给出 `ls` 与 `find`，且按 ADR 0112 含「已经退役」；②帮助页与子命令列表里没有 `files`；③建议列表里不出现 `files`；④`skmr help workflow` 的主题正文也只提 `ls`。
- **判定**：通过／失败

### T6 · `find`

- **步骤**：①`find <地址> '*.md'`；②`find <地址> 'references/*.md'`；③`find <地址> 'API*' -i`；④`find <地址> '*' --path references/`；⑤`find <地址> 'zzz'`。
- **期望**：①按 basename 命中所有 `.md`（通配是 `path.Match`，也认 `[...]`）；②按完整路径命中 `references/` 下的 `.md`；③忽略大小写命中 `api.md`；④只在 `references/` 下；⑤退出 1 + 一句「没有匹配」。
- **判定**：通过／失败

### T7 · `stat`

- **步骤**：①`stat <地址> SKILL.md`；②`stat <地址> references/`；③`stat <地址> SKILL.md --digest`；④`stat <地址> nope.md`；⑤清单里没有 `sha256` 字段（旧服务端）。
- **期望**：①四列，摘要为前 12 位 hex（清单里的 `sha256:` 前缀不进列）；②`dir` + 文件数 + 总字节；③完整 sha256（64 位十六进制）；④退出 1；⑤清单没有 `sha256` 字段时那一列打 `-`。
- **判定**：通过／失败

### T8 · `head` / `tail`

- **步骤**：①`head <地址> SKILL.md`（默认 10 行）；②`tail <地址> SKILL.md -n 3`；③对只有两行的文件 `head -n 10`；④对二进制文件 `head`。
- **期望**：①②输出原始字节、无行号无页脚；③有多少给多少、退出 0；④退出 1 并指向 `cat` / `download`，stdout 什么都不打。
- **判定**：通过／失败

### T9 · `read --limit` / `--raw`

- **步骤**：①`read <地址> SKILL.md --offset 3 --limit 2`；②`read <地址> SKILL.md --raw` 与 `skill cat` 的 stdout 逐字节比较；③`read --raw --offset 2 --limit 3`；④`read --raw` 一个二进制文件；⑤`read --raw --offset` 越过末尾。
- **期望**：①只打第 3–4 行且带行号；②与 `skill cat` 的 stdout 逐字节相同、无行号无页脚、版本注记在 stderr；③按行切片、原样输出；④退出 1；⑤`--offset` 越过末尾时 stdout 为空、提示与注记都在 stderr。
- **判定**：通过／失败

### T10 · `grep -C/-A/-B`

- **步骤**：①`grep <地址> need -C 1`；②`grep <地址> need -A 2`；③`grep <地址> need -B 1 -n`；④`grep <地址> need -C 1 -A 1`。
- **期望**：①②③ 命中行用 `path:line:text`（带 `-n`）或 `path:text`，上下文行用 `path-line-text` / `path-text`；相邻命中合成一组，组间一行 `--`；同一行不重复；④退出 2（用法错误）。
- **判定**：通过／失败

### T11 · `grep -e/--exclude/--max-count`

- **步骤**：①`grep <地址> -e alpha -e beta`；②`grep <地址> x --exclude '*.md'`；③`grep <地址> x --max-count 2`。
- **期望**：①任一模式命中都算；②被排除的路径不出现在结果里；③每个文件最多 2 条命中。三条都以请求参数送到服务端（服务端替身断言参数与路径过滤在服务端完成）。
- **判定**：通过／失败

### T12 · `edit --regex`

- **步骤**：①`--regex --old 'npm (install)' --new 'npm $1 --save'`；②同一模式在两处出现、不给 `--replace-all`；③非法正则 `--old '('`；④`--old 'zzz'`。
- **期望**：①只改那一处，回显 hunk，`$1` 展开；②退出 1 并列出两处行号；③退出 2，句子带 RE2 错误；④退出 1，句子指向 `skill cat`（不做「最接近的行」）。
- **判定**：通过／失败

### T13 · 既有契约回归

- **步骤**：`edit` 的字面匹配与 hunk、`read` 的默认渲染、`cat`、`wc`、`check`、`version diff`、`write` 的既有用例。
- **期望**：全绿。
- **判定**：通过／失败

### T14 · 清单带摘要（服务端）

- **前置**：一个草稿，清单里至少一个文件。
- **步骤**：`GET …/versions/{version}/files`。
- **期望**：每一项含 `relpath`、`sha256`（`sha256:<hex>`）、`size`、`is_binary`；把该路径的字节取下来算 SHA-256，与清单里的 `sha256` 逐位相同（单文件响应的 `ETag` 是**版本** digest，不能拿来比）。
- **判定**：通过／失败

### T15 · invoke 头部的能力清单

- **前置**：五个档位各一份元数据（invoke / browse / write / maintain / admin）+ 一把没有 `skills:write` 的 key。
- **步骤**：①逐个档位 `skill invoke <地址>`；②用只读 key 再跑一次 write 档；③让假服务端只回旧的 `permissions`（没有 `capabilities`）。
- **期望**：①`permissions:` 下按能力分行，命令与档位一致（invoke 只有 `read files`；browse 多 `list files`；write 多 `write files`；maintain 多 `discard drafts`；admin 多 `share this skill`）；渲染时去掉 `skill ` 前缀，`version discard` 原样保留；②写类三行消失；③回落到旧的一行 `permissions: read, list, write`，不报错。
- **判定**：通过／失败

## 环境与数据

- **栈**：Go 单元测试 + `pointAtServer` / `newFakeServer` 假服务端；服务端 JUnit（服务端 grep 的上下文与参数）。
- **数据**：清单样本要有两层目录、一个 `body`、一个 `bin`、多个 `.md`；内容样本要包含重复模式、CRLF、以 `\n` 结尾与不结尾两种。
- **清理**：临时文件用 `t.TempDir()`。

## 结果

**执行环境**：macOS arm64；`go build` / `go vet` / `gofmt` 干净，`go test ./...` 全绿。服务端 `./mvnw -o -DskipTests test-compile` 通过（含新写的 IT）。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 · `ls` 列一层 | **通过** | `TestLsListsOneLevelAndKeepsDirectoriesFirst`（目录在前、文件在后、目录带 `/`） |
| T2 · `ls -l` | **通过** | `TestLsSubdirectoryAndDetails`（`dir -`、`body 12`、`text 100`、`bin 2048` 四行逐字断言） |
| T3 · `ls -R` | **通过** | `TestLsRecursiveAndItsTwoErrors`（六行完整路径、字节序） |
| T4 · `ls` 的两种错误 | **通过** | 同上（目录不存在 → 指向根目录；路径是文件 → 指向 `read`） |
| T5 · `files` 退役 | **通过** | `TestFilesSaysItBecameLs`（退出 2、含「已经退役」、同时给出 `ls` 与 `find`）+ `tree_test` / 子命令列表用例（帮助与建议里不再有 `files`）+ `help workflow` 主题正文改为 `ls` |
| T6 · `find` | **通过** | `TestFindMatchesNamesAndFolders`（basename / 完整路径 / `-i` / `--path` 按路径段：`references/` 命中其下、`SKILL.md` 命中它本身、`references/a` 不命中 / 无命中退出 1） |
| T7 · `stat` | **通过** | `TestStatPrintsFileAndDirectoryRows`（四列、目录汇总、`--digest`、找不到）、`TestStatPrintsHexDigestAndStripsThePrefix`（`sha256:<hex>` → 列里 hex、`--digest` 无前缀、缺字段打 `-`） |
| T8 · `head` / `tail` | **通过** | `TestHeadAndTailAreRaw`（默认与 `-n`、原始字节、不足 N 行照样退出 0）、`TestHeadTailAndRawReadRefuseBinary`（二进制拒绝、stdout 为空） |
| T9 · `read --limit` / `--raw` | **通过** | `TestReadRawIsByteExactAndLimitCutsTheRange`（与 `skill cat` 逐字节相同、注记走 stderr、区间、带行号版的上限）、`TestReadRawPastTheEndWritesNothingToStdout`（越界提示走 stderr）、附加覆盖（不属步骤①–⑤）：`TestLimitDoesNotRaiseTheByteCap`（`--limit` 只抬行数、不抬 128 KiB）、`TestRawReadHasNoByteCap`（`--raw` 不受 128 KiB 限制）；二进制拒绝见上一行 |
| T10 · `grep -C/-A/-B` | **通过（CLI）／已写未跑（服务端）** | CLI：`TestGrepRendersContextTheWayGrepDoes`（`:` 与 `-` 分隔符、`--` 分组、`-C` 与 `-A` 冲突退出 2）；服务端：`DraftFileIT.grepBringsTheContextItWasAskedForWithoutRepeatingLines` —— **本机没有测试数据库，未执行** |
| T11 · `grep -e/--exclude/--max-count` | **通过（CLI）／已写未跑（服务端）** | CLI：`TestGrepSendsEveryFilterToTheServer`（请求里带 `pattern` 重复、`exclude`、`max`）、`TestGrepAcceptsOnlyDashEPatterns`（步骤① 的 `-e`-only 形式）、`TestGrepSaysWhatToDoWithAPatternRE2Refuses`（附加覆盖，不属步骤①–③：服务端以 `details[].issue=bad_pattern` 拒绝时给出下一步，而不是通用句）；服务端：`DraftFileIT.grepFiltersEveryWayTheCliCanAskFor` —— 同上，未执行 |
| T12 · `edit --regex` | **通过** | `TestEditRegexMatchesAndExpands`（`$1` 展开、非法正则退出 2）、`TestEditRegexRefusesSeveralMatchesWithoutReplaceAll`（两处 → 退出 1、零 PUT、列出行号 1、2） |
| T13 · 既有契约回归 | **通过** | `go test ./...` 全绿；golden 的 `skill files` 两行差异已登记（退出码 1→2、stderr 换成退役句子）；`help workflow` 与 pin 破坏时的两句运行时提示都改指 `ls` |
| T14 · 清单带摘要（服务端） | **已写未跑** | `DraftFileIT` 的两处清单断言（`sha256` 字段存在、`sha256:` 前缀）；服务端 `./mvnw -o -DskipTests test-compile` 通过，**本机没有测试数据库，未执行** |
| T15 · invoke 头部的能力清单 | **通过（CLI 与 server 单元）／已写未跑（IT）** | CLI：`invoke_output_test`（write 与 admin 两档的渲染、`version discard` 保留前缀、旧服务端回落）；五个档位的映射与累积由 `GrantRoleTest` 覆盖，只读 key 的交集由 `CredentialPermissionsTest` 覆盖；`SkillDetailIT` 的 `capabilities` 断言已写，需要测试库 |

**未执行的**：服务端两个 grep 用例、T14 的清单摘要断言、T15 的 `SkillDetailIT` capabilities 断言，都需要 `SKILLMASTER_TEST_DB_*` 指向一个可用数据库；本机这次没有起库，按「未执行」记，交付前要补跑。

## 已知问题

本版范围内暂无记录。
