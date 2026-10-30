# v32-online-editing · 技术设计

> **最后更新**：2026-10-30
> **所属版本**：[`README.md`](README.md)

<!--
接口与数据模型的权威。取舍的理由在 ADR 与调研记录，不在这里复述。
-->

## 架构

只动 CLI；服务端、API、控制台都不动。

```
skill edit
  parseEditArgs（--old/--old-file、--new/--new-file、--replace-all、@new 的三个参数）
    → loadEditFiles（读内容文件，UTF-8 校验）
    → 空 old 拒绝
    ├─ @new 分支
    │    resolveNewDraftBase → AuthorFile（底稿内容）
    │      → replaceExactly（诊断用 <地址>@<底稿码>）
    │      → renderHunks(before, after)
    │      → CreateDraft（一读一写）→ 回显 hunk + 「这一版现在是 <地址>@<新码>」
    └─ 普通分支
         writeTarget（地址 → 草稿码）
           → AuthorFileList（清单 + ETag）/ AuthorFileBytes（内容 + ETag）
           → replaceExactly（精确匹配 + 诊断）
           → renderHunks(before, after)
           → AuthorWriteFile（If-Match = 读到的 ETag）
           → 回显 hunk + 「这一版现在是 <地址>@<码>」
```

新增一个纯函数文件 `cmd/skillmaster/editdiff.go`，放这些事，都不碰网络、不读环境：

| 函数 | 职责 |
|---|---|
| `renderHunks(before, after []byte) ([]string, bool)` | 对旧/新内容做行级 diff，按 git 形状输出 hunk；`bool` 表示是否被截断 |
| `diffMiddle(oldLines, newLines []string) []lineOp` | 变更区间的 LCS；区间过大时退化为「全删再全加」 |
| `diagnoseMiss(content []byte, old, address, relpath string) error` | 0 匹配时挑一种诊断 |
| `stripReadLineNumbers(text string) (string, []int, bool)` | 去掉 `N\t` 前缀与 `read` 的页脚；返回行号用于「编号是否连续」 |
| `multipleMatchesError(content []byte, old string, count int) error` | 多处匹配：出现次数 + 去重后的行号 |

**为什么回显用 diff 而不是重放替换**：第一稿按「每处替换的位置」拼 hunk，三个缺陷都出在这里——`--old` 带结尾换行时切片越界（`--old-file` 的默认形态）；第二处落在上一段上下文里时被画成「未改动」；整行删除被画成少一行。对 before/after 做 diff 不关心改动是怎么来的，这三个失效模式都不存在。

`replaceExactly` 保留在 `files.go`：0 匹配调 `diagnoseMiss`，多处匹配调 `multipleMatchesError`。

## 数据模型

没有变化。改动仍落在草稿行上，`If-Match` 仍是读到的 ETag（版本 digest）。

## 接口

### 命令行形态

```
skmr skill edit <命名空间>/<名字>@<版本码|new> <相对路径>
    (--old <文本> | --old-file <文件>)
    [--new <文本> | --new-file <文件>]
    [--replace-all] [--from <版本>] [--name <版本名>] [--note <说明>]
```

规则：

- `--old` 与 `--old-file` **必须且只能给一个**；`--new` 与 `--new-file` 最多给一个，都不给等于替换成空。**「给了」看参数是否出现，不看值是否为空**：`--new ""` 是明确的「替换成空」，与 `--new-file` 同时出现仍是矛盾。
- 文件按原始字节读；**必须是合法 UTF-8**，否则拒绝（与 `@new` 的文本限制同一道理：JSON 传输会静默替换）。行尾不做归一化。
- 空 `--old`（含空文件）拒绝：没有可匹配的文本。
- 地址、版本码、`@new` 的规则都不变。

### 成功输出（全部 stderr，stdout 仍为空）

```
已修改 SKILL.md（ad6939e4）。
@@ -1,5 +1,5 @@
 # demo
 
 alpha
-npm install
+npm ci
 omega
（这一版现在是 me/demo@K3M9QX2P；再读拿到的就是这份内容。）
```

- 上下文 3 行；两处改动之间不超过 **6 行**未改动文本时合并进同一段 hunk（git 的 2×上下文规则）。
- `@@` 的数字含上下文，`-` 侧是旧文件行号，`+` 侧是新文件行号；计数为 1 时省略 `,1`（git 的写法）。
- 总行数上限 **60**；超出时末尾加一行：
  `…（只显示前 N 行；完整改动：skmr version diff <地址>@<码>。）`，N 是实际打印的行数（≤ 60）。
- 改动只落在行尾（末尾换行、CRLF↔LF）时没有行级 hunk，改打一行：
  `（这次只改了行尾：换行符或末尾的换行，内容行没有变。）`
- 截断不会停在孤零零的 `@@` 头上（正文被切掉的头一并去掉）。
- 行尾没有换行符的那一行不补 `\ No newline at end of file`：hunk 是给人看的摘要，完整与权威的对照是 `version diff`。
- `@new` 路径的首行是既有文案「已修改并建出草稿 `<地址>@<码>`。」加一行「下一步：…」，其后同样打 hunk 与末句，码是刚建出来的草稿码。

### 0 匹配的诊断（按顺序取第一条成立）

| 条件 | 说法 |
|---|---|
| `--old` 的每个非空行都以 `N\t` 开头、**编号连续**，且（去掉后是整行，或本来就是多行） | 「你给的原文带着 `read` 的行号。去掉行号再试；取原文：`skmr skill cat <地址> <路径>`。」 |
| 每行去掉行尾空白（含 `\r`）、整段去掉首尾空行后，在内容里**恰好一处**（多处时取唯一的整行对齐那处） | 「只差空白：第 a–b 行的实际写法是：…」（列出实际行，最多 6 行） |
| 首行（跳过开头的空行）与文件里某一行的相似度 ≥ 0.6（Levenshtein：`1 − 距离/较长者长度`） | 「没找到。最接近的是第 N 行：`…`。」（候选行超过 120 个字符时截断加 `…`） |
| 都不成立 | 「这一版里没有找到要替换的文本」+「先 `skmr skill cat <地址> <路径>` 看一眼」 |

所有诊断都追加「文件没有改动。」。`@new` 路径里的 `<地址>` 是解析出的底稿（`<地址>@<底稿码>`），不是 `@new`。

**最接近的行的成本上限**：跳过长度差超过较长者 40% 的候选（这类候选的相似度上限也达不到 0.6），其余按 `|旧|×|新|` 计费，**累计 2000 万格**；**单个候选超预算就跳过它、继续扫后面的候选**，不停止扫描——顶部一条超长行不该把下面的近匹配藏住。预算内找到的最近行照常报告。

### 多处匹配

```
要替换的文本出现了 3 次，在第 1、3、5 行。加 --replace-all 全部替换，
或带上更多上下文只改一处。文件没有改动。
```

- **次数**是出现次数（`--replace-all` 会替换几次）；**行**是去重后的位置。一行里出现两次时是「2 次，在第 1 行」。
- 超过 6 行时列前 6 个，后接「等 N 行」，N 是**去重后的总行数**。

### 帮助

| 字段 | 改动 |
|---|---|
| `Usage` | 增 `(--old <文本> \| --old-file <文件>)` 与 `[--new <文本> \| --new-file <文件>]` |
| `Flags` | 增 `--old-file`、`--new-file` 两条 |
| `Output` | 写明：成功在 stderr 依次打「已修改那一行 / hunk / 这一版现在的草稿码」；stdout 为空 |
| `Failures` | `not_found` 的 Next 从「先 read」改成「先 `skill cat` 看原文」 |
| `Examples` | 增一条文件输入的例子 |

### 退出码

不新增类别：

- 缺参数、两个来源同时给、空 `--old`（含空文件）、`@new` 缺 `--name` → **2**（用法错误，stdout 空，stderr ≤ 200 字节、结尾「用法见：…」，v16 T10）。
- 文件读不到、不是 UTF-8、匹配失败、写冲突 → **1**。
- 成功 → 0。

用法错误里不嵌文件路径：200 字节的预算要留给下一步，而不是一个调用方已经知道的路径。

## 分期

| 期 | 交付 | 验收 | 这一期明确不做 |
|---|---|---|---|
| 一期 | `--old-file` / `--new-file`、hunk 回显、三种 0 匹配诊断、多处匹配、帮助 | A1–A8 | 不改 `read` / `write` / `cat` 的既有行为；不给 `write` 加防线或回显 |
| 二期 | `read --raw`、`write` 拒绝行号渲染、`write --content-file` | A9–A10、A12 | 不给 `write` 做 hunk 回显（整文件覆盖没有「改了一处」可画）；不改 `read` / `cat` 的默认渲染 |
| 三期 | `@new` 打印下一条完整命令（不改本机钉） | A11 | 不移动本机钉；不把 `submit` 变成草稿更新器 |

一期的错误文案指向已有的 `skill cat`；二期起统一指向 `read --raw`。

## 开放问题

1. **hunk 上限 60 行够不够？** 已定为 60。复评条件：出现一次「hunk 被截断、看不出改了什么」的实际报告，或一次 `version diff` 被用来补看本可由回显给出的改动。
2. **`read --raw` 与 `cat` 长期是否应合并？** 当前倾向不合并：`cat` 接多个路径、`read --raw` 只接一个并与 `read` 共用版本解析。复评条件：二期实现后，帮助页需要超过两句话才能讲清两者的分工。
3. **`--new-file` 的末尾换行要不要提示？** 当前倾向原样使用、不提示——调用方给什么就是什么。复评条件：出现一次因多/少末尾换行而写坏内容的实际实例（行尾只有换行差异时，回显会给「只改了行尾」的说明，不在此列）。
