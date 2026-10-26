# v21-startup-state-line · 技术设计

> **最后更新**：2026-10-26
> **状态**：已实现并发布（合入 `main`，随 CLI `1.0.1` 上线；T11 真机仍待补）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

## 架构

**一处改动，全在 CLI。** 服务端、控制台、`design/` 零改动：状态行要回答的「这台机器上的 skill 有没有上平台」里，**「这台机器上有什么」只有 CLI 知道**（它扫本机目录），服务端拿不到这个事实。所以判定只能在客户端做：服务端提供它的两半——线上清单（按 `source=created` 过滤）与**权限面**的草稿队列；「只算我的草稿」这一半由客户端拿 `/me` 的命名空间收窄。

```
       本机目录（local.Scan）
              │  []local.Skill → 名字集合 local
              ▼
   GET /api/v1/me ─────────────► 个人命名空间 slug（用来筛掉别人的草稿）
              │
   GET /api/v1/author/publish-queue ─► 只留自己命名空间的行 → 名字集合 pending、草稿份数 drafts
              │
   GET /api/v1/skills?show=live&source=created ─► 名字集合 live
              │
              ▼
        whereYouAre  ── 三个集合塞进 whereabouts
              │
              ▼
     whereYouAreLine  ── 从上往下第一条命中 → 一行文本（stderr）
```

**四个数据源必须全部成功**：本机扫描与三个接口，任一失败就返回「不打印」，不补零（v17 验收 29 的既有行为，本版保持）。理由是这一行是**续跑点**而不是装饰——猜一个数比不显示更糟，因为它会把人指到错误的步骤上。

**为什么多一次 `/me`**：草稿队列是**权限面**（自己命名空间 + maintain 及以上的共享，`SkillCatalogRepository.publishQueue`），不是所有权面；不知道自己的命名空间，就分不清「我的草稿」和「同事的草稿」。`Me` 不需要 scope，凭据可用就能回答。

**判定与渲染分开**：`whereYouAre` 只负责取数、失败即退出；`whereYouAreLine` 是纯函数，输入名字集合，输出一行。两层各有单测：判定表测纯函数（KI-1 那一格就是在这里钉住的），取数与失败形状测 `whereYouAre`（查询串、四个数据源各自失败）。旧实现只测计数，正是漏掉 KI-1 的原因。

## 数据模型

**没有表、没有迁移。** 这一版的数据结构只有内存里的三个名字集合与两个计数：

```go
// cmd/skillmaster/state.go
type whereabouts struct {
	local   map[string]struct{} // 本机候选目录里的 skill 名（去重，见下）
	live    map[string]struct{} // show=live & source=created 返回的名字（一页）
	pending map[string]struct{} // 自己命名空间里、有 ≥1 份草稿的名字
	drafts  int                 // 自己命名空间里的草稿份数：把每行 drafts 数组的长度相加

	liveTruncated   bool // 线上清单只拿到一页（NextCursor 非空）
	draftsTruncated bool // 草稿队列只拿到一页（truncated）
}
```

派生量，都是纯函数：

| 名字 | 定义 | 用途 |
|---|---|---|
| `unmoved()` | `local` 里既不在 `live`、也不在 `pending` 的名字数 | 判定第 1 条 |
| `online()` | `local ∩ live` 的名字数 | 句子里的「其中 N 个已上线」 |

**约束与取舍**：

- **键是名字，不是 `namespace/name`。** 本机目录只有目录名，没有命名空间；线上用 `source=created`、草稿用自己的命名空间，两个集合都已经是「我的」，剩下的近似只是「我的两个不同 skill 共用目录名」——按设计那要停下来问人，一行状态行问不了。
- **名字去重**：同一个 skill 常同时存在于 `~/.agents/skills` 与 `~/.claude/skills` 两份副本，按名字去重（`state.go` 既有注释；v17 PRD §各 agent 的技能目录 结论 3 实测 `.agents` 28 个名字都在 `.claude` 的 33 个里）。
- **不变量**：`pending` 里只有「草稿数 ≥ 1」的名字；`drafts` 是份数，可能大于 `len(pending)`（一个 skill 可以有多份草稿）。
- **截断即保守**：`live` 只取一页（`api.ListPageSize` = 50），队列一页 500 个 skill（每个可带多份草稿）。没被这一页命中的本机名字按「未搬」算，于是判定落到「搬运」——两个方向的错代价不对等（[`prd.md`](prd.md) §不做 #5）。队列被截断时同理：可能出现「搬运」与「N 份以上草稿」同句，这是保守方向的已知形状。

## 接口

### 对服务端：三个既有接口，零新增

| 方法 | 路径 | 本版用法 | 鉴权 |
|---|---|---|---|
| `GET` | `/api/v1/me` | 取 `user.namespace`（个人命名空间 slug） | 凭据，**不需要 scope** |
| `GET` | `/api/v1/author/publish-queue` | 只留 `namespace == 自己` 的行；取 `name`、`drafts` 长度、`truncated` | 凭据 + `skills:read` |
| `GET` | `/api/v1/skills` | `show=live`、`source=created`、`limit=50`；只取 `skills[].name` | 既有凭据 |

**草稿队列走作者面那条，不是 `/web/publish-queue`。** 后者是控制台的会话路由；CLI 没有会话（`AuthorPublishQueueController` 的类注释写明了这一点）。两条路由返回同一个响应形状。

`source=created` 由服务端过滤（`LibraryRepository.where()` 的 `sources` 分支），不在客户端再筛一遍——客户端筛会把整页一起丢掉，服务端筛才是「我创建的」这一页。该参数与 `show=live` 同时可用；两者都在 v14/v19 就存在，本版不改服务端。队列的命名空间过滤则只能在客户端做：服务端没有「只给我自己的」这个参数。

### 对用户：状态行的契约

一行，`fmt.Sprintf` 于下列形状，**结论是四个固定句子之一（含标点）**：

```
现在的位置：本机候选目录里 %d 个 skill（%s），我的库里有 %s线上、%s —— %s
```

| 槽位 | 取值 |
|---|---|
| 本机个数 | `len(local)` |
| 其中已上线 | `其中 %d 个已上线`（= `online()`）；线上清单被截断时 `其中至少 %d 个已上线` |
| 线上条数 | `len(live)` 条 / 被截断时 `api.ListPageSize` 条以上 |
| 草稿 | `%d 份草稿在等上线` / 被截断时 `%d 份以上草稿在等上线（更早的没有列进来）` |
| 结论 | 判定表第 1–5 条的固定句子：「下一步：搬运。」「下一步：上线。」「下一步：写提示词、验证，然后下线。」「已完成：平台可用，本机没有副本。」 |

判定，从上往下第一条命中：

```go
switch {
case len(w.local) > 0 && w.unmoved() > 0: next = "下一步：搬运。"
case w.drafts > 0:                        next = "下一步：上线。"
case len(w.local) > 0:                    next = "下一步：写提示词、验证，然后下线。"
case len(w.live) > 0:                     next = "已完成：平台可用，本机没有副本。"
default:                                  next = "下一步：搬运。"
}
```

**错误：没有新的错误码。** 任一数据源出错 → `whereYouAre` 返回 `("", false)` → `startup` 不打印这一行，其余输出不变（`main.go` 的既有分支）。

## 分期

单期，一次交付。

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | `cmd/skillmaster/state.go` 的判定与句子（含 `/me` 的命名空间过滤）；`state_test.go` 重写表驱动用例，另加四条取数用例（三个主题） | 验收 #1–#13 全部（[`prd.md`](prd.md)） |

**这一期明确不做**：

- 不翻页取全量线上清单或草稿队列（[`prd.md`](prd.md) §不做 #5）
- 不比较内容（§不做 #2）
- 不动 `skills pending` / `skills list` / 控制台（§不做 #6）
- 不新增子命令（§不做 #1）

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 一个账号**自己创建**的线上 skill 超过 50 条时，状态行会保守地一直报「搬运」。什么时候为它翻页？ | 现在不翻：首次使用与绝大多数账号远不到 50；翻页让每次 `startup` 多几个请求，而保守报错只让人多看一眼第一步 | 统计生产上 `source=created` 且 `show=live` 的账号条数分布；出现真实账号超过 50 条时，在 `whereYouAre` 里跟 `NextCursor` 翻页，最多 N 页 |
| Q2 | 「提示词写没写、验证跑没跑」没有数据源，尾部三步只能合成一个阶段。要不要让平台记录这两件事？ | 不记录：它们改的是人本机的提示词文件与一次只读验证，服务端无从核实，记了也会失真 | 由旅程技能在每步结束时问人一句、人自己回答；若将来出现「跨机器续跑」的需求，再评估平台侧状态 |
| Q3 | 同名不同内容算「已搬过」，会不会让人以为搬过其实没搬？ | 保持：设计允许同名（`state.go` 既有注释），修复单也接受这一条 | 若导入流程将来做内容级去重，状态行跟随那套判定；在那之前不为此加一次内容比较 |

## 附录

### 附录 A · 与现有代码的关系

| 文件 | 处置 |
|---|---|
| `skillmaster-cli/cmd/skillmaster/state.go` | 改：`whereYouAreLine` 的签名换成 `whereabouts`，判定改按名字；`whereYouAre` 增加 `Me` 调用、队列按命名空间过滤、线上加 `Sources: []string{"created"}` |
| `skillmaster-cli/cmd/skillmaster/state_test.go` | 重写：判定表从「计数」改成「名字集合」并补 KI-1 一格；新增四条取数用例（查询串、队列命名空间、三个接口各自失败、本机扫描失败） |
| `skillmaster-cli/cmd/skillmaster/main.go` | 只改一处注释（状态行现在是四个请求，不是三个），调用点与「失败不打印」分支照旧 |
| `skillmaster-cli/internal/api/{client,library,author,me}.go` | 不动（`Me`、`QueryOptions.Sources`、`PublishQueue` 都在） |
| 服务端 / 控制台 / `design/` | 不动 |

### 附录 B · 来源与出处

- **一手**：[`docs/versions/v17-cli-startup/known-issues.md`](../v17-cli-startup/known-issues.md)（KI-1：真机现象、根因、期望行为、修复约束、验证要求）
- **一手**：`skillmaster-server/src/main/java/com/skillmasterai/modules/skill/internal/LibraryRepository.java` 的 `where()`（`show` 与 `sources` 两个分支）——确认 `show=live&source=created` 可用
- **一手**：`skillmaster-server/.../skill/SearchScope.java` 的类注释——确认 `library` 是 created / shared / joined 三类之和
- **一手**：`skillmaster-server/.../skill/internal/SkillCatalogRepository.java` 的 `publishQueue`（`ownNamespaceId OR maintainAndUp`）与 `.../usecase/ReadPublishQueueUseCase.java`（`namespace` 字段填的是 slug）——确认队列是权限面、以及按命名空间过滤可行
- **一手**：[`docs/versions/v17-cli-startup/prd.md`](../v17-cli-startup/prd.md) 验收 29（`1.0.0` 未满足的那一条）

### 附录 C · 已测量的数据

| 指标 | **口径**（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| 本机 skill 数 | macOS 一台，`local.Scan` 的 18 个候选目录按名字去重后的个数。本机存在的目录只有 `.agents`（28 个）、`.claude`（33 个）与 `.codex`（只有 `.system`，被跳过）；`ls ~/.agents/skills` 的每个名字都能在 `ls ~/.claude/skills` 里找到，所以并集去重仍是 33 | 33 |
| 自己创建的线上 skill | 同一账号 `source=created` 且 `show=live`，2026-10-26 实测 | 2 |
| 与我本机同名的线上 skill | 同上两个集合求交 | 0 |
| 草稿份数 | `skills pending` 的队列（自己的命名空间），同上时刻 | 0 |

## 附录 D · 实施时对照的既有行为（不许改）

1. **失败不补零**：任一数据源失败 → 整行不打印（v17 验收 29）。
2. **阶段词、无步号**（[v20 TD](../v20-guidance-on-platform/technical-design.md) §接口 › CLI 的「状态行不再写步号」段；[ADR 0096](../../decisions/0096-guidance-lives-on-the-platform.md) 是「旅程在平台上」那个更大的决定）。
3. **`startup` 只在 stderr 打印这一行**，stdout 仍是旅程正文（v20）。
