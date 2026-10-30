# 0119 · invoke 头部的能力清单带命令名：名字要能落到调用上

> **状态**：已采纳
> **日期**：2026-10-30
> **部分取代**：[0100](0100-invoke-output-is-platform-assembled.md) 决定 4（`permissions` 列动作名、不列命令）

## 背景

0100 决定 4 把 `permissions` 定为**动作名**：`read` / `list` / `write` / `discard` / `share`，并明说 `download` / `grep` / `show` 有命令也不列——理由是「命令可以改名，权限不该跟着动」。

2026-10-30 复核时暴露出三件事：

- **`list` 在 CLI 里没有对应命令**。列一版文件的是 `ls`（v34 之前叫 `files`），而 `skills list` 是另一件事（列我的库）。一个读过头部的 agent 把 `list` 读回 `skills list` 是合理的误读。
- **补上资源前缀也救不回来。** `files.read` 这类写法没有标准词表：OAuth 的 scope 由资源服务器自己定义，IETF 只规定格式与交互，不规定词的集合。agent 读到的是一个要它去查登记表的名字，而登记表它不会读。
- **动作粒度盖不住命令的差异。** 同为「能读文件」，`read` 与 `grep` 的权限要求不同（后者要清单）；头部只给一个 `read` 时，这个差异不可见。

查到的三家惯例指向同一件事——**词要能落到调用上**：

| 来源 | 做法 |
|---|---|
| GitHub API | 响应头 `X-Accepted-GitHub-Permissions` 把「调用这个端点需要什么权限」贴在**每个操作**上，格式 `contents=read` |
| RFC 6750 | 403 用 `WWW-Authenticate: … error="insufficient_scope", scope="…"` **点名缺的那一条** |
| MCP | `tools/list` 本身就是**权限过滤后的工具清单**，每项是 agent 接下来要调用的名字 |

## 决定

1. **头部新增 `capabilities`**：有序列表，每项 `{capability, commands}`。
   - `capability` 是普通语言的短句：`read files` / `list files` / `write files` / `discard drafts` / `share this skill`；
   - `commands` 是这条能力解锁的命令，写成 `skmr` 之后的整段：`skill read`、`version discard`。
2. **CLI 渲染成按能力分行**，头部标签仍是 `permissions`（对 agent 说的是权限，不是命令数组）：

   ```
   permissions:
     read files: invoke, read, cat, head, tail
     list files: ls, find, stat, grep, wc, check
     write files: write, edit, rm, mv, cp
   ```

   渲染时去掉同组的 `skill ` 前缀（跨组的 `version discard` 原样保留），一项一条能力，右边是逗号分隔的命令名。
3. **旧的 `permissions` 字符串数组保留一代**，与 `capabilities` 同时发。新 CLI 优先 `capabilities`，没有时才回落到旧字段按老格式渲染——老服务端仍然可读。
4. **命令词汇表进服务端**：`GrantRole` 同时拥有「档位 → 动作」与「档位 → 能力（含命令）」两张映射；新增或改名命令时两处一起改。
5. **凭据 scope 的交集按能力过滤**：没有 `skills:write` 的 key 不出现 `write files` / `discard drafts` / `share this skill`。
6. **能力清单不追求穷举**：低频命令（`find` / `stat` / `head` / `tail` / `wc` / `check`）仍列在所属能力下；`download` / `submit` / `create` 是整版或技能级动作、不属于这五条能力，头部不列，帮助里仍有。用法一律留给 `skmr skill --help`——头部不写用法。

## 理由

- **名字必须自带语义，或者来自标准词表。** `files.read` 两者都不占；可执行命令两者都占——agent 认识 `grep`、`edit`，敲错了还有帮助与失败句子兜底。
- **否掉「继续用动作名 + 把映射写进 `skmr help permissions`」**：那是要求 agent 先读一份它不知道存在的登记表。登记表解决的是人的问题，不是调用方的问题。
- **否掉「只列命令、不带能力」**：多个命令同属一件事，逐条列既丢语义又更长；能力行把「为什么这几个命令在一起」说出来。
- **否掉「把用法写进头部」**：那是每个 agent 每次 invoke 都付一遍的成本，而用法是用到才需要；渐进披露（MCP 的 `tools/list` 与帮助页分工）已经给出答案。
- **接受服务端知道命令名这件事。** 0100 当年拒绝这种耦合，换来的是 agent 读不懂的词表；两害相权，可理解性优先。改名时服务端与 CLI 一起改，代价写在「后果」里。

## 后果

- **服务端多两张映射**：`GrantRole` 的动作表与能力表都要维护；新增命令时必须同步，`GrantRoleTest` 会钉住「每个档位都有能力、且累积」。
- **改名成为跨端改动**：`skill files` 这类改名（ADR 0112 的退役形态不受影响）今后要同时改 `GrantRole` 的命令字符串。
- **旧字段 `permissions` 至少保留到下一个主版本**：老 CLI 解析新形状会整体失败（类型从字符串数组变成对象数组），所以不是「忽略未知字段」能解决的；两个都发是唯一的平滑路径。
- **头部从 1 行涨到 1–5 行（随档位）**，这是用可理解性换 token 的一次明确取舍；再想压缩时先推翻本条，不要悄悄派生一套缩写。
- **`discard` 的已知缺口照旧**：`write` 档可以丢弃**自己的**草稿，但头部没有「谁的」这一维，所以 `discard drafts` 从 `maintain` 起列——与 0100 的后半句一致，不是本条的退化。
