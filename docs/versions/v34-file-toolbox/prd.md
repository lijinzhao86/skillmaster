# v34-file-toolbox · 产品需求

> **最后更新**：2026-10-30
> **所属版本**：[`README.md`](README.md)

<!--
这是本版需求的权威文档。README 只放摘要，别处不要重复这里的内容。
-->

## 用户与场景

**谁在用**：手里有 `write` 档的 agent。它只有终端，没有浏览器的文件树。

**在什么场景下用**：agent 被要求「把 `references/api.md` 里所有过期的字段名改掉」「看看这个 skill 的目录结构」「SKILL.md 最后几段写了什么」。它要先**看**（列目录、按名字找、看某个文件多大）、再**搜**（带上下文的命中行）、再**读一段**（原样字节，不整份塞进上下文），最后才改。

**现在的替代做法是什么**：

| 做法 | 差在哪 |
|---|---|
| `skill files` + 本地 `grep` | 清单是平铺的，没有目录、没有大小；按目录看要 agent 自己切字符串 |
| `skill grep` | 只回命中行，没有上下文；要改一处得再 `read --offset` 猜起点 |
| `skill read` | 带行号与页脚，不能写回；只有 `--offset`，没有 `--limit` / `--raw` |
| `skill cat` | 原样但整份传，大文件没有分段 |

**实测依据**：本机与 Claude Code 的 Read / Grep / Glob / LS 逐项对照，见 [`technical-design.md`](technical-design.md) §与本地工具的对照（v34 之后的命令面；仍缺的「多文件一次改」见 v32 的不做 #2（多文件原子操作））。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | `skill ls <地址> [<目录>] [-l] [-R]`：看一层目录（目录在前、文件在后）；`-l` 带类型与大小；`-R` 递归 | CLI |
| 2 | `skill files` 退役：名字还在，回一句指向 `ls` / `find` 并退出 2；帮助页不再列它 | CLI + 帮助 |
| 3 | `skill find <地址> <模式> [-i] [--path <路径>]`：按名字找（basename；含 `/` 时匹配整条路径；`--path` 按路径段：这条路径本身或它下面，不是字符串前缀） | CLI |
| 4 | `skill stat <地址> <路径>... [--digest]`：大小、类型、摘要；目录给汇总 | CLI |
| 5 | `skill head` / `skill tail <地址> <路径> [-n <行数>]`：首/尾 N 行，原始字节 | CLI |
| 6 | `skill read` 增 `--limit <行数>` 与 `--raw`：读区间、拿原样原文（无行号无页脚） | CLI |
| 7 | `skill grep` 增 `-C/-A/-B <n>`：命中前后带上下文，`--` 分组，与 GNU grep 同形 | **服务端** + CLI |
| 8 | `skill grep` 增 `-e <pattern>`（可重复，位置 pattern 可省略、只用 `-e`）、`--exclude <glob>`、`--max-count <n>`：全部在服务端过滤后再返回 | **服务端** + CLI |
| 9 | `skill edit --regex`：`--old` 是 RE2 正则，`--new` 是替换模板（`$1`），其余规则不变 | CLI |
| 10 | 作者面清单补 `sha256`：`stat` 的摘要列与 `--digest` 的数据来源（线上写法 `sha256:<hex>`） | **服务端** |
| 11 | `skill invoke` 头部的能力清单带命令：`capabilities` 按能力给命令（`read files` / `list files` / `write files` / `discard drafts` / `share this skill`），旧 `permissions` 字符串数组保留一代（[ADR 0119](../../decisions/0119-permissions-carry-commands.md)） | **服务端** + CLI |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | `sed` / `awk` 命令 | 是语言不是命令；子集看着像 sed 却失败在不同地方，比没有更糟。`s///` 的增量由 `edit --regex` 给 | 若出现 `edit --regex` 表达不了的批量变换需求 |
| 2 | `patch` / 统一 diff 输入 | 格式合规是一个独立的失败面（v32 PRD §不做 1） | 不做 |
| 3 | 目录：`mkdir`、目录级 `mv/rm/cp`、`-r/-f` | 要动数据模型与三端，单独一版 | 已记 TODO，版本号顺延 |
| 4 | `cp` / `mv` 的 `-f` 覆盖 | 与「覆盖要显式」的现有口径一起做 | 目录那一版 |
| 5 | `chmod` / `ln` / `touch` | 模型里没有权限、链接与 mtime；建空文件用 `write --allow-empty` | 不做 |
| 6 | `sort` / `uniq` / `jq` | 输出走 stdout，agent 本地管道更合适 | 不做 |
| 7 | 把命令**用法**写进 invoke 头部 | 每个 agent 每次 invoke 都要付一遍；用法是用到才需要的知识，留在 `skmr skill --help` 与各命令的帮助里（[ADR 0119](../../decisions/0119-permissions-carry-commands.md) 决定 6） | 不做 |

## 控制台操作与 CLI 对应

本版没有新增控制台操作。控制台编辑页已有的「文件树 / 搜索 / 查看文件」与 `ls` / `find` / `read` / `grep` 对应；`edit --regex` 对应已有的「改一个文件」，不新增界面。本版不改控制台。

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | `ls` 列一层：目录行带 `/`，目录在前、文件在后，各自按名字排；`-l` 给出类型（`dir`/`body`/`text`/`bin`）与字节数；`-R` 递归输出完整相对路径；目录不存在、路径其实是文件时各有一句指向下一步 | 单元用例：根一层、子目录、`-l`、`-R`、两种错误各一例 |
| A2 | `files` 退役：仍可敲，退出 2，stderr 一句话指向 `skill ls` / `skill find`；帮助页与 `skill` 的子命令提示里不再出现 `files` | 单元用例 + 帮助用例 |
| A3 | `find`：不含 `/` 的模式匹配 basename，含 `/` 的匹配完整路径；`*`/`?` 通配；`-i` 忽略大小写；`--path` 按路径段（这条路径本身或它下面，不是字符串前缀）；没有命中退出 1 | 单元用例：四类各一例 + 无命中 |
| A4 | `stat`：文件给出 `路径 字节 text\|bin 摘要前 12 位`（hex，摘要来自清单）；`--digest` 给完整 sha256；目录给出其下文件数与总字节；找不到退出 1 | 单元用例：文件 / 目录 / `--digest` / 找不到 |
| A5 | `head` / `tail`：默认 10 行，`-n` 可改；输出原始字节（无行号、无页脚）；二进制拒绝；文件不足 N 行时有多少给多少、退出 0 | 单元用例：默认、`-n`、不足 N 行、二进制 |
| A6 | `read --limit`：与 `--offset` 配成区间，显式给出时覆盖行数上限；`read --raw`：stdout 与存储字节逐字节相同、无行号无页脚、版本注记走 stderr；`--raw` 可与 `--offset/--limit` 组合；`--raw` 遇二进制拒绝 | 单元用例 + 逐字节断言 |
| A7 | `grep -C/-A/-B`：命中行与上下文行的分隔符与 GNU grep 同形（`path:line:text` 对 `path-line-text`），组间一行 `--`；`-C` 与 `-A`/`-B` 同时给是用法错误（退出 2） | 单元用例 + 服务端用例 |
| A8 | `grep -e/--exclude/--max-count`：多个模式任一命中（含位置 pattern 省略、只用 `-e` 的形式）；排除的路径不出现在结果里；`--max-count` 是**每个文件**的命中上限；三条都作为请求参数送到服务端（客户端不再二次过滤） | 单元用例 + 请求断言 |
| A9 | `edit --regex`：`--old` 按 RE2 编译；全文唯一命中，多处要 `--replace-all`；`--new` 支持 `$1`；非法正则退出 2 并带 RE2 的错误；0 匹配不做「最接近的行」诊断 | 单元用例：唯一 / 多处 / 替换模板 / 非法 / 0 匹配 |
| A10 | 既有契约不变：`edit` 的字面匹配与 hunk 回显、`read` 的默认渲染、`cat` / `wc` / `check` / `version diff` / `write` 的行为 | 既有用例全绿 |
| A11 | invoke 头部的能力清单：`capabilities` 与档位一致（invoke → `read files`；browse ＋ `list files`；write ＋ `write files`；maintain ＋ `discard drafts`；admin ＋ `share this skill`），每项带命令；没有 `skills:write` 的 key 不出现写类能力；老服务端（只有 `permissions`）时 CLI 回落到旧渲染 | 单元用例：五个档位的精确映射（服务端）、渲染（write / admin 两档）、凭据交集、旧服务端回落 |

### 指标

| 指标 | 怎么量 | 目标 |
|---|---|---|
| 一次修改的调用数 | 读原文 + 一次 `edit`（可以 `read --raw` 或 `ls`/`grep` 定位） | 不超过 3 |
| 多文件计算的位置 | `grep` 的请求里带 `before/after/exclude/max`；响应里只有命中与上下文行，没有整份文件 | 0 次把整份内容拉到客户端做搜索 |
| 命令名与 Linux 一致 | 新命令：`ls`、`find`、`stat`、`head`、`tail` | 5/5 |

## 依赖与前置

- v7 的文件接口与 `If-Match`、v32 的 `edit`/`read`/`write` 契约。
- [ADR 0118](../../decisions/0118-computation-follows-the-data.md)：跨文件的计算在服务端做，单文件的读取与切片在客户端做。
- 外部参照：[`docs/research/agent-file-editing-tools.md`](../../research/agent-file-editing-tools.md)。
