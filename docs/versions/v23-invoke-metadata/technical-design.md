# v23-invoke-metadata · 技术设计

> **最后更新**：2026-10-27
> **状态**：已实现（PRD 2026-10-27 定；代码在 `feat/v23-invoke-metadata`）
> **所属版本**：[`README.md`](README.md)

## 架构

**值在服务端，渲染在 CLI。** 这一条是本版全部设计的支点，理由有三：

1. 「谁能做什么」只有一个权威。今天 `can_write` 已经在服务端算（账号档位 ∩ 凭据 scope，`SkillDetailResponse` 的注释写着为什么不能由客户端推），`access` / `permissions` 从同一对输入来：`access` 报档位（账号的），`permissions` 报档位 ∩ scope（这份凭据的）。
2. **正文端点一个字节都不改。** `GET …/body` 是 `cat` 与 `digest` 保真的那条路（「托管要保真」），在服务端把它拼上头部，`cat` 就不再是原始字节。所以头部只能由 CLI 在打印时加。
3. CLI 仍然**不解析 frontmatter**（`internal/api/client.go:467`）：它只是把服务端给的字段按固定顺序打印出来，一个 YAML 解析器都不装。正文那个块是**按分隔符丢掉**的（见 §接口「CLI 的输出形状」），那也不是解析——它不看任何一个键。

数据流：

```
CLI invoke
  ├─ GET …/metadata      → address/version/access/permissions/compatibility   （L1，本版加两个字段）
  ├─ GET …/body          → 原始字节（不变）
  └─ 打印 头部 + 正文（删除页脚）
```

作者面那条路（`skill invoke <地址>@<草稿码>`）走 `GET /author/…/versions/{pin}`，**同一个 `Access` 已经在手边**（`AuthorVersionResponse.of(namespaceSlug, access, summary, handles, compatibility, credentialMayWrite)`），所以两处加的是同一对字段。

## 数据模型

**无表结构变化，无迁移。** 用到的既有事实：

- `Access`（`modules/skill/Access.java`）：`owns`（所有者，在梯子之上）与 `rung`（`invoke`／`browse`／`write`／`maintain`／`admin`，或 null）。`GrantRole.atLeast(rung, …)` 就是本版 `permissions` 的判据（`Access.atLeast` 含所有者，语义不同，用的是前者；所有者在调用侧先折成 `admin`）。
- `SkillDetail`（`modules/distribution/SkillDetail.java`）：`version`（版本号）与 `frontmatterJson`（作者原文，`compatibility` 从里面取——服务端**已经**把它提到 L1 了，见 `SkillMetaResponse.compatibility`）。

`SkillDetail` 增加两个只读字段：`access`（String）与 `permissions`（`List<String>`），在 `SkillDistributionService.toDetail(...)` 里由同一个 `Access` 算出——**与 `writable` 是同一个来源，不是第二次查询**。

## 接口

### 档位 → permissions（唯一映射）

| 档位 | permissions | 依据（`GrantRole` 的注释） |
|---|---|---|
| `invoke` | `read` | 能调用、能读说得出名字的文件，不能列目录 |
| `browse` | `read, list` | 第一个能读文件清单的档 |
| `write` | `read, list, write` | 能提版本、丢弃**自己**的草稿 |
| `maintain` | `read, list, write, discard` | 能上线、丢弃**任何人**的草稿 |
| `admin` | `read, list, write, discard, share` | 能授权/撤销、改可见性 |

- **`publish` / `rollback` / `visibility` 不进 `permissions`**：它们在 CLI 里没有命令，是控制台动作。列进来等于教 agent 去找一条不存在的命令。这也说明这条规则不是「每个有命令的动作都列」——`download` / `grep` / `show` 有命令，但它们不改变能力边界，列出来只会让头部变长。
- **`discard` 从 `maintain` 起列**：`write` 能丢弃的是**自己的**草稿（`SkillVersionService.discardVersion` 的 `atLeast(WRITE) && mine`），而头部没有「谁的」这个维度。列成无条件动作，等于告诉持有 `write` 的 agent 它能丢弃任何人的草稿。
- **再与凭据的 scope 求交**：`permissions` 说的是「**这份凭据**能做什么」，而 `skills:write` 是 API 层的闸（非 GET 一律要它，`Scopes.requiredForMethod`）。`can_write` 从 v6 起就是「账号档位 ∩ 凭据 scope」，`permissions` 是同一件事的清单写法，因此走同一个 `CredentialPermissions.of(permissions, credentialMayWrite)`——只有 `skills:read` 的 key 只看到 `read, list`。`access` 不跟着变：档位是账号的，scope 是 key 的。
- **所有者报 `admin`**：所有者在梯子之上（`Access` 的注释），而 `permissions` 只列 CLI 动作、不含 `delete`，所以对 agent 而言所有者与 admin 能敲的命令是同一组。
- 映射写在 `GrantRole` 旁边（`permissionsOf(String rung)`），与梯子同一个文件：两处隔开一定会漂。

### `GET /api/v1/skills/{ns}/{name}/metadata`（消费面 L1）

新增两个字段（加性，不动既有字段）：

```json
{
  "namespace": {"slug": "lijinzhao", "title": "…"},
  "name": "life",
  "title": "…",
  "description": "…",
  "when_to_use": "…",
  "compatibility": "需要 python3 与网络",
  "visibility": "public",
  "frontmatter": {…},
  "version": {"name": "1.0.0", "digest": "sha256:…"},
  "access": "write",
  "permissions": ["read", "list", "write"]
}
```

`version.name` 就是**版本号**（`skill show` 打出来的那个），不是作者写的名称。写进头部时的规则见下节「CLI 的输出形状」——号为空的行按摘要或版本码处理，不是直接打印。`SkillMetaResponse.Version` 那条「作者给这一版起的名字」的旧 Javadoc 已在本分支改掉（ADR 0055／0091 之后号在发布时分配）。

### `GET /api/v1/author/skills/{ns}/{name}/versions/{pin}`（作者面）

`access` / `permissions` 加在 envelope 的 `skill` 上——与消费面同一对字段、同一个 `Access` 算出来。`compatibility` 加在 envelope 顶层、与 `version` 并列：它是**这一版**的事实，而 `skill` 是 skill 的事实；列表信封（`Page`）因此不带它，免得一页的每一行都背一份别人的 frontmatter。

它的值来自这一版存下来的 frontmatter（`frontmatterJson`，M5 存进去的那份），由 API 层取出——消费面在 `SkillMetaResponse.of` 里、作者面在 `SkillMetaResponse.compatibilityOf` 里，两条都落到同一个 `textual` 规则上；两个面各写一份提取规则就会各漂各的。

### `GET …/body` 与 `GET /author/…/files/SKILL.md`

**不变。** 原始字节，含作者 frontmatter。

### CLI 的输出形状

```
---
address: lijinzhao/life
version: 1.0.0
access: write
permissions: read, list, write
compatibility: "需要 python3 与网络"
---
<正文，作者那一段 frontmatter 已去掉>
```

| 规则 | 为什么 |
|---|---|
| 字段顺序固定：`address` → `version` → `access` → `permissions` → `compatibility` | 头部会被模型逐行读；顺序固定才可预期 |
| `compatibility` 只在非空时出现 | 空值写一行没有任何信息 |
| `compatibility` **折成一行**（连续空白压成一个空格）并**加引号** | 它是头部里唯一由作者写的值：换行会让字段提前结束、值里的下一行混进「平台事实」那一区（伪造一行 `permissions:`、或提前一行 `---`）；不加引号时 `macOS: 需要 X` 是一个映射套映射、`#` 开头是注释，按 YAML 读会失败 |
| 两个面都有 `compatibility` 这一行 | 作者面（草稿 / 历史版）的 `invoke` 与消费面是同一个命令，同一个命令不该因为地址写法不同而少一行 |
| `access` / `permissions` 服务端没给就不打印 | 老服务端不返回这两个字段；打印空行等于宣布「什么都不能做」 |
| **页脚删除** | `address` 已在头部，取文件那句可以由它拼出来 |
| **正文开头的作者 frontmatter 块整块去掉**，正文从块的下一行开始 | 头部已经是一份完整的 frontmatter；再并排一份，读者没有依据判断该信哪一份，而作者那行 `version:` 与平台分配的号冲突 |
| `cat` / `read` 不变 | `cat` 是原始字节；`read` 有自己的行号与页脚，属于另一条命令 |

`version` 行的三种情形（实现：`printLoadedHeader`）。判据是「这一版有没有号、地址有没有自带标识」，不是「走的是哪个面」：

| 这一版 | 地址怎么写的 | 头部 |
|---|---|---|
| 有号（ADR 0091 之后发布的每一版） | 裸地址，或行 id | `address: x/y` + `version: 1.0.0`——**用行 id 读一个已发布版本时照样打号** |
| 没有号（草稿 / 已丢弃，ADR 0063），地址自带行 id | `x/y@AB12CD34` | `address: x/y@AB12CD34`，没有 `version` 行——行 id 已经是它的名字 |
| 没有号（v6 之前的旧行），地址没有自带标识 | `x/y` | `address: x/y` + `version: sha256:…`——摘要就是地址钉它的方式（ADR 0033） |

**去块的做法**：`bodyWithoutFrontmatter`（`cmd/skillmaster/content.go`）按**分隔符**找块——第一行是 `---`，到下一条只有 `---` 的行为止，整段（含收尾换行）丢掉；不读 YAML，不看键名。服务端 `FrontmatterParser` 保证每一份 `SKILL.md` 都以这个块开头，所以分隔符一定在；找不到成对分隔符时原样返回，不猜。**行结束符与 `String.lines()` 同规则**：`\n`、`\r\n`、单独的 `\r` 都算换行，取最早出现的那个——服务端接受的文件，这里就得按同样的方式切，否则会漏掉收尾的分隔符、把作者的块留在正文里。分隔符两侧只容忍 ASCII 空白（`String.trim()` 的口径），全角空格不算。

**`--offset` 的行号从正文第一行算起**（头部照常打印，不参与计数；`read` 对同一个文件的编号因此相差一个块的高度）——两者各自与自己的输出一致，这是它们唯一的共同口径。

**`skill invoke` 的 help 文本同步**（`declarations_skill.go`，用语按 [`design/voice-spec.md`](../../../design/voice-spec.md) 的书面语与名词表）：原文写的「末尾追加一行版本说明」已经不成立，改成先头部后正文、作者 frontmatter 不出现；并补上 ADR 0100 §后果 要求的那句「要原始字节用 `skill cat`」。

**钉移动的那一行改走 stderr**：v23 之后 stdout 是「头部 + 正文」这一份文档，`invoke > body.md` 会把追加在正文之后的注记一起存进去。`startup` 早就这么做（v20 test-plan 写明理由相同），此处与之对齐；行本身仍然打印。**取用上限的说明不在此列**——正文超过 2000 行 / 128 KiB 时那行「继续取」仍打在 stdout，因为它必须跟着内容走：丢一行「还有更多」会被读成「这就是全部」。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 服务端：`GrantRole.permissionsOf`、`SkillDetail` 两个字段、L1 的 `access` / `permissions`、作者面的 `access` / `permissions` / `compatibility`、与凭据 scope 求交、过期 Javadoc | 3 |
| P1 | CLI：`api.Metadata` 两个字段、`AuthorSnapshot` 带过来、invoke 打印头部并删页脚、`--offset` 与 help 同步、golden 更新 | 1、2、4、5、6 |
| P2 | 平台技能 `skillmaster/startup` 的规则段去掉「末行说明…」 | 7 |

P0 与 P1 要一起合（分开合会有一版「服务端给了、CLI 不打印」或反之）；P2 是内容，另走草稿与控制器。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 所有者报 `admin` 会不会误导（所有者还能删） | **已定：报 `admin`**。`permissions` 只列 CLI 动作，删除是控制台动作、没有 CLI 入口，所以对 agent 而言两者是同一组命令 | 若将来删除有了 CLI 命令，再考虑 `owner` 这个取值 |
| Q2 | `permissions` 与 `GrantRole` 注释会不会漂 | 映射写在 `GrantRole` 同文件、加一条测试断言「五个档位都有映射且取值只在本表之内」 | 漂了就是测试红 |
| Q3 | 作者面的 `permissions` 与消费面会不会不一致 | 同一个 `Access`、同一个 `permissionsOf`，两处都从它算 | 矩阵用例同时覆盖两条路径 |
| Q4 | `address` 用不用带 pin | **不带**（`version` 已经单独一行）；草稿例外，因为草稿的版本码只能出现在地址里 | 矩阵用例里各取一条核对 |
| Q5 | 头部字节上限 200，`permissions` 到 admin 时占多少 | admin 的 `permissions` 是 33 字符；实测头部 118 字节，在上限内 | **已结**：数字见 [`test-plan.md`](test-plan.md) §结果 |
