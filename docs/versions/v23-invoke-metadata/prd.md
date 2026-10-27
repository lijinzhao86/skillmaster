# v23-invoke-metadata · 产品需求

> **最后更新**：2026-10-27
> **所属版本**：[`README.md`](README.md)

<!--
这是本版需求的权威文档。README 只放摘要，别处不要重复这里的内容。
-->

## 用户与场景

**谁在用**：已经装好 CLI 的 agent，以及替它们配置规则的开发者。

**在什么场景下用**：agent 用 `skills search` 找到一条，`skill invoke` 加载正文之后，**它要决定下一步做什么**——能不能读别的文件、能不能改这个 skill、改完收在哪。今天的 invoke 只给「原文 + 一行页脚」，这三件事一件都没答，agent 只能去翻 `help`，或者直接试命令、撞上 403 再退回来。

**现在的替代做法是什么**：三样都不够好。

| 做法 | 差在哪 |
|---|---|
| 翻 `skillmaster help` | 每条帮助讲的是命令形状，不讲「**你**在**这一份**上能做什么」 |
| 直接试 | `skill files` 在 invoke 档回 403，`skill write` 在 browse 档也回 403；每试一次一轮往返，且错误句不告诉它「那我能做什么」 |
| 规则段里背下来 | 规则段是全局的，答不了「这一份」 |

此外，今天 invoke 的正文开头是**作者写的 frontmatter**，其中 `version:` 是作者随手写的标签（`lijinzhao/life` 写着 `2.0.0`），而平台给这一版分配的版本号是 `1.0.0`——两个数字并排出现，没有一个字解释它们是什么。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | **invoke 返回的头部由服务端组装**，只含平台事实：`address` / `version` / `access` / `permissions`，以及作者声明了的 `compatibility` | 服务端 + CLI |
| 2 | **作者开头那一整块 frontmatter 不再出现在 invoke 的输出里**（`description`、`when_to_use`、作者的 `version`、`name`、`license`、`metadata` 及任何其他字段，无一例外） | CLI（正文端点不变：`/body` 仍返回原始字节，由 invoke 在打印时按分隔符去掉开头那个块；`cat` 不受影响） |
| 3 | **删掉 CLI 追加的那行页脚**（「它引用的文件用 `skillmaster skill read …` 取」）——`address` 已经在头部，agent 自己拼得出来 | CLI |
| 4 | **`permissions` 的取值按档位固定**：`invoke → read`；`browse → read, list`；`write → read, list, write`；`maintain → read, list, write, discard`；`admin → read, list, write, discard, share`；再与**凭据的 scope** 求交（只有 `skills:read` 的 key 只看到 `read, list`） | 服务端 |
| 5 | **`cat` 不变**：仍是这一版的原始字节，含作者 frontmatter | 无改动，但要有验收守住 |
| 6 | **平台技能的内容同步**：`skillmaster/startup` 第三步的规则段不再说「末行说明该版本与可执行的操作」，改为指向头部（本版上线时一起发） | 平台技能（不属于代码仓库的交付物，另走草稿 → 控制台） |

**头部的形状**（就是本版要交付的东西）：

```yaml
---
address: lijinzhao/life
version: 1.0.0
access: write
permissions: read, list, write
compatibility: "需要 python3 与网络"   # 作者声明了才出现；折成一行、加引号
---
# 个人生活
…正文一个字节不改…
```

**从未被分配过版本号的行**（草稿、已丢弃）没有 `version` 行，`address` 带版本码（`lijinzhao/life@AB12CD34`）。

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 透传 `description` / `when_to_use` | `skills search` 的卡片已经给了这两样；同一会话里再说一遍是纯重复。规范自己的分层模型也是「元数据一次、正文一次」 | 若「用户直接给地址、不经搜索」成为主要路径，再议 |
| 2 | 在头部列出具体命令（`skill write …@new`、`version open …`） | 命令形状对所有 skill 都一样，只有地址不同——写进每个 skill 的头部，等于把规则段复制 N 份 | 规则段不再承载命令时 |
| 3 | `pinned` 字段 | 它写的就是 `address` + `version`，零新增信息；「裸地址 = 上次 invoke 钉住的」是一条全局规则，已在 `help workflow` 里 | 不做 |
| 4 | 修 `read` / `files` 的不对称（invoke 档能按路径读、不能列目录） | 这是权限模型现状（`GrantRole` 对 invoke 的定义就是这么写的），本版只负责**如实告知**，不动模型 | 若产品决定让 invoke 也能列目录，另开一版 |
| 5 | 控制台展示 `permissions` | 控制台看的是人的权限，已经用档位表达 | 未定 |
| 6 | 给 `compatibility` 之外的作者字段做「保留原样」的兼容开关 | 做了开关就有两条路径、两套测试；本版的目标是**一种输出** | 不做 |

## 控制台操作与 CLI 对应

**本版没有新增或改动任何控制台操作**——它只改 `skill invoke` 的输出。控制台上的档位、共享、上线全部不变，因此本表为空（[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 要求的是「新增的控制台操作要有 CLI 对应」，本版没有新增）。

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | `skill invoke` 的头部只含 `address` / `version` / `access` / `permissions` 与（作者声明了才有）`compatibility`；从未分配过号的行（草稿、已丢弃）没有 `version` 行、`address` 带版本码 | 消费面与草稿各跑一条，逐行比对；`compatibility` 里含换行与 `permissions: …` 的一条，输出仍是单行、加引号 |
| 2 | 作者开头那一整块 frontmatter 不出现在 invoke 的输出里（含 `description`、`when_to_use`、作者的 `version`、`name`） | 对一条 frontmatter 里带齐这些字段的 skill 跑 invoke，`grep` 作者的**取值**：`2.0.0`、`name: <作者的短名>`、`description:`、`license:`、`when_to_use:` 均无命中（头部自己的 `version:` 行不算） |
| 3 | `access` 与 `permissions` 按档位正确：六档（含所有者）× 五种版本状态（线上 / 历史 / 草稿 / 已下架 / 已丢弃）矩阵逐格核对 | 复用 `qa/role-matrix` 的六档凭据 |
| 4 | `permissions` 与真实命令行为一致——列了 `list` 的档，`skill files` 必须成功；没列的必须失败；同一把 key 若只有 `skills:read`，`write` / `discard` / `share` 不出现 | 同一个矩阵里同时跑 `skill files`，另用一把只读 key 跑一次 invoke |
| 5 | `skill cat` 的输出与存储字节一致（含作者 frontmatter） | `skill cat` 与 `skill download` 里的同名文件逐字节比较 |
| 6 | invoke 不再有页脚那一行；stdout 只有头部与正文，以及取用上限（截断、`--offset` 越界）的说明 | 输出里没有「（已按 … 加载…）」；钉移动的提示走 stderr；超长正文的截断说明仍在正文之后 |
| 7 | 平台技能规则段与新输出一致（不再说「末行说明…」） | 读 `skillmaster/startup` 那一版 |

**成立条件**：验收 1、3、4 是「不满足就不算交付」——3 与 4 一起才证明「写在头部的东西是真的」；1 单独成立只证明形状对。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| invoke 头部的上下文成本 | 头部 frontmatter 的 UTF-8 字节数，不含正文与 `---` 行 | 对一个真实 skill 跑 invoke，取头部字节 | ≤ 200 字节 |
| 对一个 skill 的试错轮次 | agent 从「加载完」到「找到能做的事」之间的失败命令数 | 拿一个没见过的 skill 走一遍「加载 → 决定下一步」，数中间被拒的命令（读头部即可，不必翻 help） | 从 1–2 次降到 0 |

## 依赖与前置

- **外部依赖**：无第三方服务。
- **内部前提**：
  - [ADR 0047](../../decisions/0047-permissions-are-a-ladder.md)（五档梯子）与 `GrantRole` 的定义——`permissions` 的取值从那里来，不从命令名来。
  - [ADR 0048](../../decisions/0048-visibility-hands-out-a-rung.md)（公开把某一档交给所有人）——invoke 档的 `permissions` 只有 `read`，正是它的定义。
  - [ADR 0033](../../decisions/0033-version-names-are-semver.md)、[0091](../../decisions/0091-release-type-decides-the-version-number.md)（版本号在发布时分配）——`version` 字段说的是版本号，不是作者写的名称；从未被分配过号的行（草稿、已丢弃）按版本码寻址，头部因此没有 `version` 行。
  - [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md)（API key 的 scope）——`permissions` 要与凭据的 scope 求交，与 `can_write` 同一算法。
  - **需要一条新 ADR**：把「invoke 返回这一版的正文」改成「invoke 返回平台组装过的这一版」，这是输出契约的跨版本取舍 —— 已写：[ADR 0100](../../decisions/0100-invoke-output-is-platform-assembled.md)。
- **排除了什么前置**：不需要动权限模型（`GrantRole` 与 `visibleStates` 都不改）；不需要动控制台；不需要改 `skills search` 的卡片。
