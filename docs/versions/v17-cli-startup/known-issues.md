# v17-cli-startup · 已知问题

> 本版范围**之外**的历史代码缺陷放在这里。本版范围内的未完成项写在 `README.md` 的状态行与 `prd.md` 的「不做」里。

---

## KI-1 · `startup` 状态行把「本机与线上的 skill 互不相干」误判成「搬运已完成」

**状态**：已复现（2026-10-26）。修复在 [`v21-startup-state-line`](../v21-startup-state-line/README.md) 里，发布后把这里改成「已修复」并注明版本。

**发现**：2026-10-26，真机走 `skillmaster startup` 时（CLI `1.0.0 (API v1)`，macOS arm64，旅程技能 `skillmaster/startup@1.0.2`，账号 `skillmaster-test`）。

### 现象

一台机器的真实状态：

| 项 | 数量 | 说明 |
|---|---|---|
| 本机候选目录 `~/.claude/skills` | 33 个 | `lark` 与 `lark-*` 共 29 个 + `coding` / `huashu-design` / `byted-web-search` / `skillmaster` |
| 线上 live | 2 条 | `skillmaster-test/meeting-notes`、`skillmaster-test/pdf-table`，**本机没有同名目录** |
| 草稿 | 0 份 | `skills pending` 为空 |

两者**没有任何交集**：本机 33 个一条都没上过平台，线上 2 条也不是从本机搬上去的。

`skillmaster startup` 打印的第一行却是：

```
现在的位置：本机候选目录里 33 个 skill，库里 2 条线上、0 份草稿在等上线 —— 下一步：写提示词、验证，然后下线。
```

按 `skillmaster/startup` 首页的对照表，这句指向 `steps/03-prompt.md`，**跳过 `steps/01-import.md`**。

### 为什么没有报错

旅程后面三步都能通过，因此不会有任何提示：

- 第三步写提示词：能写，且与 `steps/03-prompt.md` 逐字一致；
- 第四步验证：`skills list` 列得出 2 条、`skill invoke` 取得到正文（用线上那两条），三项全过；
- 第五步下线：本机没有 `meeting-notes` / `pdf-table` 的同名目录，清单里「要删」为空。

结果是旅程显示**完成**，而本机 33 个 skill 全程没有上过平台。

### 根因

`skillmaster-cli/cmd/skillmaster/state.go:40-52`（`whereYouAreLine`）只按三个**计数**分支：

```go
switch {
case drafts > 0:                   next = "下一步：上线。"
case localCount > 0 && live > 0:   next = "下一步：写提示词、验证，然后下线。"
case localCount > 0:               next = "下一步：搬运。"
case live > 0:                     next = "已完成：平台可用，本机没有副本。"
default:                           next = "下一步：搬运。"
}
```

`localCount > 0 && live > 0` 分支隐含一个前提：**线上的条目来自本机副本**。首次使用时这个前提不成立——线上可能已有示例 / 别人共享 / 早前提交的 skill，而本机是另一批。

名字其实扫到了：`whereYouAre`（`state.go:91-94`）已经把本机目录收成 `distinct` 名字集合，但只把 `len(distinct)` 传下去，名字在调用处被丢掉。单测 `state_test.go:17-20` 也没覆盖「本机与线上不相交」这一格——那几行全部是计数用例。

### 影响

状态行是旅程的**续跑点**（见 `state.go:11-20` 的注释与 ADR 0096）：它决定 agent 读哪一份 `steps/`。判错一次，第一步就被永久跳过，且旅程末端仍报「完成」——用户会以为自己的 skill 已经在平台上，实际一条都没有。

### 期望行为

「搬运」与「已搬运」按**名字**判断，不按计数：

- 本机存在某个名字，它在 live 里没有、在草稿里也没有 → 这一步还没做完 → 仍报「下一步：搬运」。
- 本机名字全部已在 live（或已作为草稿等上线）→ 才进入「写提示词、验证、然后下线」。（2026-10-26 勘误：本条与下方「同名条目在平台上是草稿」那条冲突。v21 以实现为准：**全部已搬、且有草稿在等上线 → 「下一步：上线」**；全部已上线才进入「写提示词、验证，然后下线」。）
- 计数行可以照旧（它只回答「有多少」），但**分支判定必须有名字交集**；必要时在行内补一个数（例如「33 个 skill，其中 0 个已上线」），不要改成罗列名字。

边界：

- 同名但内容不同：本版不处理（现有设计允许，见 `state.go:86-90` 的注释），仍算「已搬过」。
- 线上有、本机没有：与本机无关，不影响判定。
- 同名条目在平台上是**草稿**：算「已搬过」，此时 `drafts > 0` 分支本来就会先命中「下一步：上线」。

### 修复约束

- `whereYouAreLine` 的签名要能拿到名字集合（或调用处预先算好「已上线的本机名字数」），只改这一个函数不够。
- 保持既有两条既有行为：任一数据源失败 → **整行不打印**，不补零（`state.go:58-65`）；分支文案用**阶段**、不写步骤号（ADR 0096）。
- 不要为此新增 CLI 子命令；这是既有判定的修正。

### 验证

单测（`state_test.go`）至少加一格，且这一格在旧实现上必须失败：

| 输入 | 期望 |
|---|---|
| 本机 33 个名字与 live 2 个名字**无交集**、草稿 0 | 「下一步：搬运」 |
| 本机 33 个名字里 33 个都在 live、草稿 0 | 「下一步：写提示词、验证，然后下线」 |
| 本机 33 个名字里 0 个在 live、草稿 33 | 「下一步：上线」（草稿分支优先） |

真机复现：在 live 里放一条本机没有的 skill、清空本机草稿，`skillmaster startup` 应报「下一步：搬运」；当前实现报「写提示词、验证，然后下线」。

### 相关

- `docs/versions/v17-cli-startup/prd.md`（六步旅程与验收；状态行属「安装与登录 → 打印旅程」一条）
- `docs/decisions/0096-guidance-lives-on-the-platform.md`
- `docs/versions/v20-guidance-on-platform/`（旅程正文改为平台技能后，状态行仍是 CLI 里唯一判断「现在在哪」的地方）
