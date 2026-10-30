# v34-file-toolbox · 技术设计

> **最后更新**：2026-10-30
> **所属版本**：[`README.md`](README.md)

<!--
接口与数据模型的权威。取舍的理由在 ADR 与调研记录，不在这里复述。
-->

## 架构

**计算跟着数据走**（[ADR 0118](../../decisions/0118-computation-follows-the-data.md)）：单文件的读取、切片、改写留在客户端；跨文件「算一个答案」的事（搜索、统计、链接检查）在服务端做完，只把结果给客户端。

```
skill ls / find / stat      → 一次清单请求（裸地址另有一次版本解析；没有上线版本时按档位再探一到两次），客户端过滤与排版（清单是元数据，不含内容）
skill head / tail / read    → 只取这一个文件（元数据 + 一次字节请求），客户端切片（单文件）
skill edit（含 --regex）    → 客户端读、算、写（单文件，If-Match 不变）
skill grep                  → 服务端扫完再返回（多文件，只回命中与上下文行）
```

视图里**算行的那一层**是纯函数：`entriesUnder` / `globMatch` / `dirSummary` 输入清单（`[]api.File`）或一份内容，输出是行，可单独单测。取清单、取内容的那一层仍然是命令（`listing` / `contentFor` 发请求），两者同放 `cmd/skillmaster/fileviews.go`。

## 数据模型

不变。没有目录实体：目录由清单里的路径前缀推出（空目录要等目录那一版）。

**作者面清单补一个字段**：`GET …/versions/{version}/files` 的每一项从 `relpath / size / is_binary` 变成 `relpath / sha256 / size / is_binary`，`sha256` 用线上统一的 `sha256:<hex>` 写法（`ManifestEntry.blobSha256` 本来就在，只是没发出去）。`stat` 的摘要列与 `--digest` 靠它，ADR 0118 决定 3 说的「清单带摘要」从这一版起成立。除 grep 与 invoke 头部之外，这是本版唯一的服务端改动。

## 接口

### `skill ls`

```
skmr skill ls <命名空间>/<名字>[@版本] [<目录>] [-l] [-R]
```

- 默认列根目录的**一层**：目录行带 `/`，目录在前、文件在后，各自按名字排序（与控制台文件树同一条规则）。
- `-l`：三列 `<类型> <字节> <名字>`，类型是 `dir` / `body`（`SKILL.md`）/ `text` / `bin`；目录的字节是 `-`。
- `-R`：递归，输出该目录下所有条目的完整相对路径（目录行仍带 `/`），按路径排序。
- 版本注记走 stderr。
- 目录不存在 → 退出 1：「这一版没有 X 目录；用 skmr skill ls <地址> 看根目录」。
- 路径其实是文件 → 退出 1：「X 是文件；读它用 skmr skill read / cat」。

### `skill files`（退役）

```
skmr skill files …
→ skmr skill files 已经退役：看一层用 skmr skill ls <地址> [<目录>]；整版清单用 skmr skill ls <地址> -R 或 skmr skill find <地址> '*'。
```
退出 2（改名依据见 [ADR 0120](../../decisions/0120-file-enumeration-is-ls.md)；退役形态见 ADR 0112：名字还在、参数删除、句子指向新去处）。帮助页与 `skill` 的子命令建议里都不再出现它。

### `skill find`

```
skmr skill find <命名空间>/<名字>[@版本] <模式> [-i] [--path <路径>]
```

- 模式不含 `/`：匹配**最后一段**；含 `/`：匹配完整相对路径。
- 通配按 `path.Match`：`*` 不跨 `/`，`?` 一个字符，也认 `[...]` 与 `\` 转义。
- `-i` 两边都转小写再比；`--path` 与 `grep --path` 同名同义：**按路径段**（这条路径本身或它下面，不是字符串前缀），前导与尾随 `/` 都忽略。
- 命中一行一个，按路径排；没有命中 → 退出 1 + 一句。
- 目录不是条目，`find` 只返回文件。

### `skill stat`

```
skmr skill stat <命名空间>/<名字>[@版本] <路径>... [--digest]
```

- 文件：`<路径> <字节> <text|bin> <摘要前 12 位>`；`--digest` 打完整 sha256。摘要来自作者面清单的 `sha256` 字段（线上拼作 `sha256:<hex>`），列里打的是 hex；老服务端没有这个字段时打 `-`。
- 目录（前缀推断）：`<路径>/ <总字节> dir files:<N>`；`--digest` 对目录无效。
- 找不到 → 退出 1，句子指向 `skill ls`。

### `skill head` / `skill tail`

```
skmr skill head <命名空间>/<名字>[@版本] <路径> [-n <行数>]
skmr skill tail <命名空间>/<名字>[@版本] <路径> [-n <行数>]
```

- 默认 10 行；`-n` 必须 ≥ 1，否则用法错误（退出 2）。
- 输出**原始字节**：不补 `\n`、不加行号、不加页脚；版本注记走 stderr。
- 二进制拒绝（退出 1）：「<路径> 是二进制文件；用 skmr skill cat 或 skmr skill download」。判定来源：作者面清单的 `is_binary`；线上那一面没有清单，按内容判（含 NUL 或不是合法 UTF-8）。
- 文件不足 N 行：有多少给多少，退出 0。

### `skill read` 的两个参数

| 参数 | 语义 |
|---|---|
| `--limit <行数>` | 与 `--offset` 配成区间；**显式给出时覆盖行数上限**，字节上限（默认渲染的 128 KiB）仍生效；`<1` 是用法错误 |
| `--raw` | 不打行号、不打页脚；stdout 与存储字节逐字节相同；版本注记走 stderr；可与 `--offset/--limit` 组合；**2000 行与 128 KiB 两项上限都不适用**；遇二进制拒绝（退出 1，判定同 `head`/`tail`） |

`--raw` 与 `--offset` 的行号都按**行**算，与原文件一致（`\n` 切分）。`--raw` 的 stdout 只有字节：偏移越过文件末尾时那一句提示走 stderr，版本注记照常打。默认（不带 `--raw`）的 `read` 一次最多印 2000 行、正文不超过 128 KiB，截断时正文之后另起一行说明怎么续读；遇二进制文件不打印，把内容写到临时文件并把路径打出来。

### `skill grep`

```
skmr skill grep <命名空间>/<名字>[@版本] [<pattern>] [-e <pattern>...]
                 [-F] [-i] [-n] [-l] [-C <n> | -A <n> | -B <n>]
                 [--path <路径>] [--exclude <glob>...] [--max-count <n>]
```

服务端 `GET …/versions/{version}/grep` 的参数与行为：

| 参数 | 语义 |
|---|---|
| `pattern`（可重复） | 多个模式任一命中；`fixed` 时每个都是字面串。位置 pattern 至多一个，`-e` 可单独使用（与 GNU grep 同形） |
| `fixed` / `ignore_case` / `path` | 不变 |
| `limit` | 总量上限（默认 1000、上限 5000），**按命中行计**，上下文行不计入 |
| `before` / `after` | 命中前后各带几行，范围 0–50 |
| `exclude`（可重复） | 路径 glob，命中的文件不扫 |
| `max` | **每个文件**的命中上限（GNU `-m`） |

响应 `GrepResult`：

```json
{"digest":"…","pin":"…","truncated":false,
 "hits":[{"path":"SKILL.md","line":1,"text":"# demo","match":true},
         {"path":"SKILL.md","line":2,"text":"","match":false}]}
```

- `match` 区分命中行与上下文行；上下文按行号顺序、同一文件的同一行只出现一次。
- 服务端仍然**只回命中与上下文行**，不回整份内容（ADR 0118）。

客户端渲染（与 GNU grep 同形）：

- 有 `-n`：命中 `path:line:text`，上下文 `path-line-text`；
- 没有 `-n`：命中 `path:text`，上下文 `path-text`；
- 组与组之间打一行 `--`（仅当有上下文时）；
- `-l` 时只打文件名，忽略上下文；
- `-C` 与 `-A`/`-B` 同时出现 → 退出 2 的用法错误。

### `skill edit --regex`

```
skmr skill edit <地址> <路径> --regex (--old <RE2> | --old-file <文件>)
                 [--new <模板> | --new-file <文件>] [--replace-all]
```

- 匹配在**行尾符归一视图**上做（与字面匹配同一条，v32 §匹配与写回）；命中位置映回原字节只替换那一段。
- 唯一性不变：多处要 `--replace-all`，否则列出行号（与字面多处同一句话）。
- `--new` 是 Go/RE2 的替换模板：支持 `$1`、`${name}`，**不认 `\1`**。
- 非法正则 → 退出 2，句子带 RE2 的第一行错误，附「用法见：…」；在**任何请求之前**判（与 `--old == --new` 同一处），断网时也能先拿到这一句。
- 0 匹配 → 不做「最接近的行」诊断（对正则没有意义），句子指向 `skill cat`。
- `--old-file` 与 `--regex` 可以一起用：文件内容就是模式。

### `skill invoke` 头部：能力清单带命令

`SkillMetaResponse` 与作者面的 `AuthorVersionResponse.Skill` 都多一个字段 `capabilities`，旧的 `permissions` 字符串数组照发（[ADR 0119](../../decisions/0119-permissions-carry-commands.md) 决定 3 与 §后果：老 CLI 解析新形状会整体失败）。

```json
"capabilities":[
  {"capability":"read files","commands":["skill invoke","skill read","skill cat","skill head","skill tail"]},
  {"capability":"list files","commands":["skill ls","skill find","skill stat","skill grep","skill wc","skill check"]},
  {"capability":"write files","commands":["skill write","skill edit","skill rm","skill mv","skill cp"]}
]
```

档位与能力的映射（`GrantRole.capabilitiesOf`，累积）：

| 档位 | 追加的能力 | 命令（下表省略 `skill ` 前缀，`version discard` 除外） |
|---|---|---|
| invoke | `read files` | `skill invoke` / `read` / `cat` / `head` / `tail` |
| browse | `list files` | `skill ls` / `find` / `stat` / `grep` / `wc` / `check` |
| write | `write files` | `skill write` / `edit` / `rm` / `mv` / `cp` |
| maintain | `discard drafts` | `version discard` |
| admin / 所有者 | `share this skill` | `skill share` / `unshare` / `shares` |

- **凭据交集**：`skills:write` 缺席时去掉 `write files` / `discard drafts` / `share this skill` 三项（`CredentialPermissions`）。
- **CLI 渲染**：有 `capabilities` 时按能力分行，标签仍是 `permissions:`；每项渲染时去掉同组的 `skill ` 前缀，跨组的（`version discard`）原样保留。没有 `capabilities` 的旧服务端回落到旧的 `permissions: read, list, write` 一行。
- **不写用法**：命令名之后的一切（地址、参数、选项）留给各命令的 `--help`。
- **命名是契约**：`capability` 的字符串（`read files` 等）与命令字符串都是对外的；改名要同时改 `GrantRole`、帮助与 ADR。


## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| 一期（本版） | `ls` / `find` / `stat` / `head` / `tail` / `read --limit,--raw` / `edit --regex` / `files` 退役 | A1–A6、A9、A10 |
| 一期（本版） | 服务端 `grep` 的上下文与三个筛选参数 + 客户端渲染 | A7、A8 |
| 一期（本版） | 作者面清单补 `sha256`（`stat` 的摘要来源） | A4 |
| 一期（本版） | invoke 头部的能力清单（`capabilities` 带命令，旧 `permissions` 保留一代） | A11 |

一版四笔交付，都在本版内；目录与 `-r/-f` 留给下一版。

## 与本地工具的对照

| 本地（Claude Code） | 本库 | 状态 |
|---|---|---|
| `Read`（`offset` / `limit`，2000 行上限） | `read`（`--offset` / `--limit`，默认 2000 行 / 128 KiB 上限） | 有 |
| `Read` 的原样通道 | `read --raw`（按版本钉、可切片）/ `cat`（可多路径） | 有 |
| `Grep`（正则、`-i`、上下文、`-e`、`-m`、glob） | `grep`（`-F`、`-i`、`-n`、`-l`、`-C/-A/-B`、`-e`、`--exclude`、`--max-count`、`--path`） | 有；`--exclude` 做排除、`--path` 按路径段收窄，没有 include-glob；在服务端执行（ADR 0118） |
| `Glob`（按名字找） | `find` | 有；`path.Match`，basename 与整条路径两种 |
| `LS`（列目录） | `ls`（`-l` / `-R`） | 有；没有目录实体，目录由路径前缀推出 |
| `Edit`（精确替换、唯一性） | `edit`（`--regex` 是增量） | 匹配规则对齐；写回与 BOM 的差异见 v32 TD |
| `Write`（整份写） | `write`（默认只新建） | 有 |
| 一次改多个文件 | — | 没有（v32 不做 #2） |

## 开放问题

1. **`ls -R` 要不要保留目录行？** 当前保留（结构完整）。若脚本抱怨「只想要文件路径」，把 `-R` 改成只列文件、目录行只在 `-lR` 出现——复评条件：出现一次因目录行而写坏管道的实例。
2. **`grep -e` 多模式怎么编译？** 当前逐个编译、任一命中。拼成一个 `(?:a)|(?:b)` 能省一次遍历，但会改变锚点语义（`^a` 与 `b$` 拼起来需要考虑分组）；逐个更稳。复评条件：模式数超过 8 且扫大文件出现可感知的延迟。
3. **`head` / `tail` 与 `read --raw --limit` 长期是否该合并？** 当前并存：前者是 Linux 肌肉记忆，后者是统一入口。复评条件：帮助页需要超过两句话讲清两者的分工。
4. **`stat` 的目录汇总要不要打文件数？** 当前打（`files:N`）。若与 `ls` 重复，可去掉。
