# v16-cli-help · 技术设计

> **最后更新**：2026-10-24
> **所属版本**：[`README.md`](README.md) ｜ 需求：[`prd.md`](prd.md)
> **实现基准**：分支 `v16-cli-help` @ `7938773`（PRD 锁定那次提交）

## 架构

### 三个新部件与它们的边界

| 部件 | 位置 | 职责 | 不做什么 |
|---|---|---|---|
| 帮助内核 | **新包** `skillmaster-cli/internal/help` | 类型（`Spec` / `Flag` / `Arg` / `Failure` / `Code` / `ExitClass` / `Topic` / `Node` / `Resolution`）、渲染、路径解析、建议、通用 flag 表 | 不认识任何具体命令，不读环境变量，不做 I/O |
| 声明 | **新文件** `cmd/skillmaster/declarations*.go`（包 `main`；`commandHelp` 是合起来的那个变量） | **56 条命令路径**（这个数已含裸 `skills my-tags`，见 PRD 事实表）+ **6 个纯分组** + 顶层，各自一份声明；`help env` 是 topic，单独一张表 | 不渲染、不判断；只有数据 |
| 接管点 | **新文件** `cmd/skillmaster/helpserve.go`（包 `main`） | 在 `run` 的最前面判断这次调用是不是要帮助；是就打印并返回 `nil` | 不改任何命令的正常调用路径 |

**为什么声明放在 `main` 包、而不是 `internal/help` 里**：PRD 做 #7 要求「与命令实现放在同一处」。声明与处理函数同包，改一条命令时两个文件在同一屏里；测试也能直接读未导出的表，不需要为测试开放导出 API。内核之所以单独成包，是因为它要被三处复用（运行时渲染、一致性测试、命令树测试），而且它不该依赖 `main`。

### 先写骨架的那一步（历史，已接线）

实现是从一个**只编译、不接线**的骨架开始的：类型与签名先落地，`run` 与 `reportError` 都还没调用它们。写它是因为散文审计发现不了「字段不存在」「两个同名符号」这类问题（`Failure.Exit` 与 `Topic` 都被真的撞上过），而编译器一秒就能发现。

| 文件 | 现在有什么 |
|---|---|
| `internal/help/types.go` | `Spec` / `Flag` / `Arg` / `Failure` / `Code` / `ExitClass` / `Topic` / `Node` / `Kind` / `Resolution` / `Catalog` |
| `internal/help/{help,render,resolve,suggest,registry}.go` | 12 个导出函数的实现（见 §内部接口） |
| `cmd/skillmaster/declarations*.go` | `declarationsCatalog` 与 `commandHelp`：56 条命令 + 6 个纯分组 + 顶层 + 1 个 topic |
| `cmd/skillmaster/parseonly.go` | `parseOnly(path, args)`：按声明绑定，不执行 |
| `cmd/skillmaster/exitcode.go` | `credentialError`、`isRefusedCredential`、`classifyExit`、`failureExit` |
| `cmd/skillmaster/version.go` | `cliFailure` 多了一个不序列化的 `Exit int` |

**接线状态**：`run` 的第二句就是 `helpRequest`（空参数分支之后、dispatch 之前），`reportError` 先算码再决定怎么打印。

### 一次 `--help` 的数据流

```
os.Args[1:]
   │
   ├─ help.Requested(cat, args) ─┬─ KindCommand（Path 非空）──▶ help.Render(cat, r.Path) ─▶ stdout，退出 0
   │                             ├─ KindCommand（Path 为 ""）─▶ help.RenderTop(cat) ─▶ stdout，退出 0
   │                             ├─ KindTopic ────▶ help.TopicOf(cat, r.Topic).Body ─▶ stdout，退出 0
   │                             ├─ KindUnknownTopic / KindTopicUsage ─▶ 一行，退出 2
   │                             └─ KindNone ─────┬─ 裸纯分组 ─▶ 该组帮助到 stderr，退出 2
   │                                              └─ 其余 ────▶ 照旧派发
   │                                    （只读声明表与 registry，不认证、不发请求、无副作用）
```

`Requested(cat Catalog, args []string) Resolution`，`Resolution` 是

```go
type Resolution struct {
	Kind  Kind   // KindNone / KindCommand / KindTopic / KindUnknownTopic / KindTopicUsage
	Path  string // KindCommand：目标路径
	Topic string // KindTopic / KindUnknownTopic：主题名
}
```

`KindNone` 有两种去向：**裸纯分组**（只给分组、不带动词，如 `skillmaster skill`）由 `helpserve` 把该组帮助打到 stderr、退出 2；其余照旧派发。其余四种由 `helpserve` 打印并返回（`KindCommand` / `KindTopic` → stdout、退出 0；`KindUnknownTopic` / `KindTopicUsage` → 一行、退出 2）——**主题不存在不会落到 `run` 的未知命令分支**，消息由这里给。

`Requested` 的判定（顺序即优先级）：

1. 从根节点开始沿声明树下降：`args[i]` 是当前节点的子节点名就继续，否则停下；停下处的节点就是**目标路径**。
2. 目标路径之后的**任意位置**出现 `--help` 或 `-h` → 目标路径的帮助。**不跳过 flag 的值**：`--help` 与 `-h` 从此不能作为任何值。
3. 若目标节点是**根**且第一个 token 是 `help`——**这一条必须在第 4 条之前**，否则 `help env` 会被当成顶层帮助：
   - 没有后续 token → 顶层帮助；
   - 恰好一个后续 token → **帮助主题**（今天只有 `env`）：`KindTopic`，由 `help.TopicOf(cat, name)` 取出正文；表里没有 → `KindUnknownTopic`，一行**列出现有主题**、退出 2（不猜：主题只有一两个，列出比建议有用）；
   - 多个后续 token → 一行用法错误、退出 2。
4. 否则，若**目标路径之后的第一个 token** 是 `help` **且目标节点有子节点** → 该节点的帮助。
5. 否则不是帮助请求，`run` 照旧派发。

**规则 2 的代价要写清，不能假装没有**：今天确实有值会以 `--help` / `-h` 开头——`flagValue`（`main.go:94`）只覆盖 v14 的自由文本 flag，`--query` / `--path` / `--into` / `--description` 这些直接取下个 token 的 flag 都接受它们（实测 `version list a/b --query --help` 今天会搜 `--help`，`skill submit --help` 今天把它当目录）。本版起这些输入**变成帮助**，这是「任意位置都算」不可避免的代价，已写进 PRD 验收 #17 的变化清单。要恢复「`--` 之后是数据」的写法，必须先引入 `--` 终止符（开放问题 Q2）。

### 一次失败的退出码数据流

```
error ──▶ reportError（version.go:430）先算码，再决定怎么打印
             │
             ├─ *exitError ──────────────────────────────▶ 它的码（grep 的无命中仍是 1）
             ├─ *cliFailure ─────────────────────────────▶ 读 failure.Exit（见下）
             ├─ *api.Refusal（含被 `answered` 包裹的）────▶ 原始码 ∈ 四个 401 码 ? 4 : 1
             ├─ *usageError（version.go:236） ───────────▶ 2   ← 新增映射（今天 1）
             ├─ *credentialError（新增类型） ────────────▶ 4   ← 新增映射
             └─ 其余（含 account_suspended、读不出、未知码）▶ 1   ← 今天就是 1
```

**`*cliFailure` 这一支是这条设计的关键，因为类型在这里会被吃掉。** `versionErr`（`version.go:246`）把 version 组（14 条）与 lifecycle（5 条）共 19 条命令的一切错误压成 `*cliFailure`，而 `cliFailure` 没有 `Unwrap`——`reportError` 再用 `errors.As` 找 `usageError` / `credentialError` / `api.Refusal` 是找不到的。所以给 `cliFailure` 加一个**不序列化的 `Exit int` 字段**，由两处填：`classifyFailure`（`version.go:345`）在压平的那一刻直接置 4（凭据不可用——那里还能看见原始错误，压平之后就看不见了）；`classifyExit`（`cmd/skillmaster/exitcode.go`，由 `reportError` 在打印前调用）置 `Code == "usage"` → 2 与 `ServerCode` 为四个 401 码之一 → 4；其余保持 0。`reportError` 必须经 `failureExit`（`exitcode.go`）读它——**0 表示「没有分类」、按 1 处理**，直接 `return failure.Exit` 会把失败退成成功。`--json` 的载荷是逐字段拼的，多一个结构体字段不会泄漏出去。

**非 version 命令的 401 走另一条路，不能省。** `skills list` 这类命令经 `authenticatedWith`（`main.go:1209-1256`）把已知码包成一个带 `Unwrap` 的 `answered`，所以 `reportError` 仍要保留 `errors.As(*api.Refusal)` 那一支——删掉它，T12 的四个 401 场景会全部退 1。

**判 4 用的是服务端的原始码，不是归一化后的 `Code`。** `classifyFailure` 会把空码归一成 `unauthenticated`（`version.go:359-366`）；若按 `Code` 判，一个「401 没有码」的 version 命令会被判成 4，违反 ADR 0087 的「401 没有码 → 1」。所以 `cliFailure` 这一支按 **`ServerCode`** 判（空值或不在四个里 → 1），原始 `*api.Refusal` 那一支按 `refusal.Code` 判。

退出码的唯一决定点是 `reportError`，而且**先算码、再决定怎么打印**：今天的实现先分支到 JSON 就 `return 1`，那样带 `--json` 的 401 会永远拿不到 4。改法是把「算码」提到分支之前，两条分支返回同一个码。`cliFailure` 的 JSON 形状（含 `code` / `server_code` / `issue`）不变——退出码是另一根轴（[ADR 0087](../../decisions/0087-cli-exit-codes-are-a-public-contract.md)）。

## 数据模型

没有数据库。几组在内存里的表：`Spec`（声明填充）、`Code` / `ExitClass`（内核里写死）、`Topic`（由 `Catalog` 提供）、通用 flag 表（内核里写死）、命令树（声明的投影；父子一致性由 `Validate` 检查）。

### `help.Spec`——一条命令的声明

| 字段 | 类型 | 含义 | 对应 PRD 八项里的哪一项 |
|---|---|---|---|
| `Path` | `string` | 空格分隔的命令路径，如 `skill invoke`；顶层是 `""` | —（键） |
| `Summary` | `string` | 一行说清干什么；**≤ 12 字（rune）**——它会原样成为分组块里那一行的说明。**根节点例外**（`SkillMaster 命令行`，15 字）：它不出现在任何分组块里 | 描述 |
| `Usage` | `string` | 用法行，含 flag 的值写法 | 用法 |
| `Flags` | `[]Flag` | **只登记改变行为的 flag** | flag |
| `Args` | `[]Arg` | 位置参数的名字、是否必填、顺序 | 用法 |
| `Output` | `string` | 成功时打印什么；只拒绝的命令写「拒绝时说什么 + 去处」 | 成功输出 |
| `JSONFields` | `[]string` | 成功对象的字段名，按顺序；**非空即表示这条命令支持 `--json`——它同时是适用性的唯一来源**，不再另有一份路径集合。**按子动作分支的命令（`skill request` 的申请 / `--status` / `--cancel` 是三个形状）写并集，并在 `Output` 里注明「字段按子动作不同」** | 成功输出（有 `--json` 时多一行 `字段：a, b, c`，**顺序即声明顺序**） |
| `Failures` | `[]Failure` | 会以哪几种方式失败、每种的下一步 | 失败类别与下一步 |
| `Examples` | `[]string` | 0–2 条可粘贴的例子 | 例子 |
| `Parent` | `string` | 上级命令路径（渲染成 `skillmaster <parent> --help`）；**顶层节点（三个命令与四个分组）`Parent` 为空串，渲染成 `skillmaster help`** | 上级地址 |
| `Next` | `string` | 可选的一行下一步 | （附加） |
| `Exits` | `[]int` | **本命令可能返回的退出类**（0 / 1 / 2 / 4），渲染成「退出码：」那一行；每个 `Failure.Code` 在 registry 里的 `Exit` 必须落在这里 | 退出码 |
| `Children` | `[]string` | 有子节点时的直接子节点；空表示叶子 | 子命令块 |
| `PureGroup` | `bool` | 只负责转发的分组（六个）；**必须显式标注**——`skills my-tags` 也可执行、也几乎没有条目，从其他字段推断不出来 | 决定四项契约 |

```go
type Flag struct {
	Name    string   // "--print"
	Aliases []string // 短旗标，如 ["-h"]；渲染时合并成一行 "--help, -h"
	Value   string   // "" 表示布尔；"<值>" / "<a|b>" 表示取值
	Sep     string   // "" 或 " " = `--flag <值>`；"=" = `--flag=<值>`
	Summary string   // ≤ 25 rune
}
type Arg struct {
	Name     string // "<命名空间>/<名字>[@版本]" —— 只用于渲染用法行
	Key      string // "address" —— parseOnly 返回的键名，同一命令内唯一
	Required bool
}
type Failure struct {
	Code    string // registry 里的码
	Summary string // ≤ 35 rune
	Next    string // 下一步；≤ 35 rune
}
```

### 一份 registry，四行投影

```go
// Code 是逐条错误的登记：声明的 Failure.Code 必须来自这里。
type Code struct {
	Name string // "not_found"
	Text string // 一行含义
	Exit int    // 这条错误落在哪个退出类：1 / 2 / 4
}
// ExitClass 是顶层帮助与 README 展示的那四行。
type ExitClass struct {
	Code int    // 0 / 1 / 2 / 4
	Text string // "0 成功"
}
```

**为什么是两张。** 逐错误码的表回答「这个失败是什么意思、下一步是什么」（验收 #15 的逐码 fixture 就建在它上面）；四个退出类是**它的投影**——`help.ExitClasses()` 固定四行，`Code.Exit` 的并集必须恰好是 `{1,2,4}`，加上从来不是错误的 `0` 才是 `{0,1,2,4}`（验收 #19）。0 不在 `Code` 里，因为它不是一条错误。

声明用 `Exits []int` 写「本命令可能返回哪几个退出类」，渲染时到 `ExitClasses()` 取文案。处理函数不许自己拼一个没登记的码。

### `help.Topic`——不是命令的一页文档

```go
type Topic struct {
	Name  string // "env"
	Label string // 顶层帮助那一行的开头，如 "环境变量"
	Body  string // 正文，≤ 512 字节
}
```

`TopicOf(cat, name)` 读一条，`TopicNames(cat)` 读清单；今天 catalog 里只有 `env` 一条（四个环境变量的说明）。顶层帮助那一行、未知主题那条错误都从清单来，所以加主题是加一条 map 条目。它**不进命令树、不计入 56、没有八项契约**；只有 `skillmaster help <主题>` 能打开它；主题名不在表里时列出现有主题并退出 2（不猜，因为主题只有一两个，列出比建议更有用）。`skillmaster help env --help` 按规则 2 落到根节点，打印的是顶层帮助——这是「`--help` 任意位置都算」的一致结果，不是特例。

### `Catalog`——内核看到的全部声明

```go
type Catalog interface {
	Specs() []*Spec          // 全部声明
	Topic(name string) (Topic, bool)
	Topics() []Topic         // 主题清单，顶层帮助与「可用的主题」共用这一份
}
```

声明在 `main` 包里实现它（`commandHelp`，`declarations*.go`），**内核不依赖 `main`**：所有需要目录的导出函数都收 `Catalog` 参数，内核里没有全局状态。`main` 侧确实有一个包级变量（`commandHelp`）；`helpRequest(args)` 读它，测试要替换时临时改它。

### 通用 flag 表（按命令标适用性）

| flag | 适用 | 来源 |
|---|---|---|
| `--help` / `-h` | 全部 | 本版新增 |
| `--json` | **`Spec.JSONFields` 非空的命令** | 适用性只有一个来源：`JSONFields` 非空。**不能拿 `rejectJSON` 当来源**：它只标记「明确拒绝 `--json`」的 20 处。今天的证据是：version 组（`list` / `show` / `diff` / `rename` / `note` / `discard` / `open`，字段见 `cmd/skillmaster/version.go:596-615`（list 的组装）与 `internal/api/author.go:64-81`（`AuthorVersionList` / `AuthorVersionEnvelope` / `AuthorWriteResult`），`diff` 的形状在 `version.go:1063-1072`）与 `skill create` / `shares` / `unshare` / `request` / `open`（`lifecycle.go:72/144/199/253/352`）真的产出 JSON；`invoke` / `write` / `edit` / `mv` / `cp` / `submit` / `share` 不产出。**`version list` 支持 `--json`，所以 `--jsno` 的建议能找到它**（验收 #8） |

`help.Universal(cat, path)` 返回该命令适用的那几条；**参数区把它们排在声明里的 flag 之后一并渲染**（验收 #2 的等式就是按这个口径成立的），建议器的候选集合也取自它（PRD 做 #2 / #4）。

### 命令树

`help.Node{Path string; Children []string}` 是声明的投影（`tree` 逐条照抄 `Path` / `Children`）：**没有单独的树文件**，也不存在第二份要维护的清单。父子一致由 `Validate` 检查——一个路径出现在谁的 `Children` 里，谁就必须是它的父；`Parent` 字段只在渲染「上级：…」那一行时使用。

## 接口

### CLI 契约（对人、对 agent）

**帮助的版式**（每条帮助都按这个顺序；标签行是测试的锚点）：

```
<一句话描述>

用法：skillmaster skill read <命名空间>/<名字>[@版本] <相对路径> [--offset <行号>]

参数：
  --offset <行号>     从第几行开始读
  --help, -h          打印这份帮助

输出：把 skill 的正文写到 stdout，诊断信息走 stderr。

失败：
  地址在服务端找不到。下一步：核对命名空间与名字
  凭据不可用。下一步：运行 skillmaster login 或换一把 API key
  连不上服务端。下一步：检查网络与服务端地址

例子：
  skillmaster skill read dev/pdf-tools SKILL.md
  skillmaster skill read dev/pdf-tools SKILL.md --offset 40

上级：skillmaster skill --help
下一步：skillmaster skill files <地址>

退出码：0 · 1 · 2 · 4
```

**失败一栏只写人话，不写机器码**：`not_found` / `content_already_exists` 是 `--json` 与服务端的事，读者在这里要的是「出了什么事、下一步做什么」；一列 20 字符的标识符 × 每行，是把帮助读不下去的主要原因。**「退出码：」那一行只列本命令可能的码**，四个含义写在顶层帮助里一次——原来 56 条命令各重复一遍同一句话。

**有子节点的帮助**紧接「用法」之后追加一段，**以 `子命令：` 开头、到下一个空行结束**——块里每一行的说明来自那个子节点自己的 `Spec.Summary`，所以渲染拿的是**整份目录**而不是单条声明：

```
子命令：
  invoke    把 skill 装进会话
  read      读一个路径
```

块内一行一条，名字后对齐两格、再跟**一句 ≤ 12 字的说明**（名字长度不限）。

**纯分组的帮助只有四项**：描述、用法、子命令块、上级/顶层地址——不带参数、输出、失败、例子、退出码。它的「用法」行固定写成 `用法：skillmaster <路径> <子命令> [参数]`（分组自己不接受别的参数）。

**顶层帮助**不是任何一条命令的正文，它自成一版：一句话 + 四个分组与三个命令的清单（每行 `名字 …说明… （<名字> --help）`）+ **指向 `skillmaster help env` 的一行**（环境变量搬走了，PRD 做 #9 / 验收 #16）+ **四个退出类各占一行** + 末行 `文档：…`。四行来自 `ExitClasses()`，不复用命令那种一行的「退出码：」行（验收 #5 断言的是四行都在）。

**建议行**是 `stderr` 上的**一行**（`stdout` 为空、退出 2）：

```
错误：不认识的子命令 "skil"。你是不是想用 skill？用法见：skillmaster help
```

没有 ≤ 2 距离的候选、或最小距离并列时，去掉「你是不是想用 …」那半句。**上级地址按层给**：顶层未知命令写 `skillmaster help`（验收 #7 断言的就是这个字面量），分组里的未知动词写 `skillmaster <组> --help`（验收 #6）。未知 flag 的前缀是「不认识的参数」，未知命令 / 动词是「不认识的子命令」。

**退出码**见 [ADR 0087](../../decisions/0087-cli-exit-codes-are-a-public-contract.md)；`skillmaster-cli/README.md` 新增一张四行表，与顶层帮助、`ExitClasses()` 同源。

### 内部接口（`internal/help` 的导出面）

| 函数 | 签名 | 谁用 |
|---|---|---|
| `Lookup` | `Lookup(cat Catalog, path string) (*Spec, bool)` | `helpserve`、测试 |
| `Render` | `Render(cat Catalog, path string) string`（经 `cat.Specs()` 找 spec，子命令块的说明从孩子的 `Summary` 取） | `helpserve`、测试 |
| `Requested` | `Requested(cat Catalog, args []string) Resolution` | `run`：空参数分支之后、`switch args[0]` 之前 |
| `Suggest` | `Suggest(word string, candidates []string) (string, bool)` | 各处的未知名字 / 未知 flag 分支 |
| `TopicOf` | `TopicOf(cat Catalog, name string) (Topic, bool)` | `helpserve` 的 `KindTopic` 分支（**类型叫 `Topic`，函数叫 `TopicOf`**——Go 不允许两者同名） |
| `UniversalFailures` | `UniversalFailures(cat Catalog, path string) []Failure` | `Render`：**能退 4 的命令**（`Exits` 含 4）自动多出「凭据不可用 / 连不上服务端」两条，命令声明里不写。声明已登记同名码时不重复（`auth status` 用自己的说法） |
| `TopicNames` | `TopicNames(cat Catalog) []string` | 未知主题那一行「可用的主题：…」。名单来自 `Catalog.Topics()`，**不是写死的字符串**：顶层帮助里那一行（`<Label>：skillmaster help <name>`）与这条报错读同一份，所以加第二个主题只需在 map 里加一条 |
| `Universal` | `Universal(cat Catalog, path string) []Flag` | `Render`、`Suggest`、测试 |
| `Codes` | `Codes() []Code` | 逐错误码的表：声明校验、验收 #15 的逐码 fixture。**通用码**（`usage` / `network` / `server_error` / `insufficient_permission` / `not_found`）由分类器产出、不必逐命令登记；T21 的「孤儿码」只查**非通用码**。另有**三组四个**由声明引用、分类器不产出（或只在特定分支产出）的码：`refused`（只拒绝的命令，退 1）、`login_failed`（登录流程本身失败，退 1）、`bad_pattern` / `too_expensive`（`skill grep` 的 RE2 边界，退 1） |
| `ExitClasses` | `ExitClasses() []ExitClass` | 顶层帮助的四行、`skillmaster-cli/README.md` 的表、验收 #19 的投影 |
| `RenderTop` | `RenderTop(cat Catalog) string` | 顶层帮助（`KindCommand` 且 `Path == ""`），与 `Render` 分开是因为顶层不是任何一条命令的正文 |
| `Validate` | `Validate(cat Catalog, trees ...[]Node) error` | 命令树测试（喂合成树）；不传树时用声明自己投影出来的那棵（包内 `tree`，不导出） |

`cmd/skillmaster` 侧另有一个**纯解析入口**：

```go
// parseOnly 只解析、不执行：不发请求、不改状态、不读 stdin。
func parseOnly(path string, args []string) (map[string]any, error) { ... }
```

**它读声明，不读 handler 的代码**——这一条是实现时定的，改掉了本版设计稿里「每条命令包一层既有 parse helper」的写法。理由：逐个从 handler 里抽出 `parseXxxArgs` 是 56 次同形改动，而这一版的判据是「帮助只有一个来源」；声明本来就是调用者读到的那份，`parseOnly` 照着它绑定，正好测的是「声明里写的都能被接受」这个方向。

它**测不到的反方向**由 AST 扫描补上：`flags_test.go` 扫包内所有以 `-` 开头的字符串字面量，断言每个 flag 都在某条声明里（`--cursor` 这类「点名拒绝」的除外），`tree_test.go` 扫 `switch args[0]` 派生出真实命令树与声明比对。两条合起来，声明与代码互为镜像，没有单点是循环论证。

positional 的绑定规则就是声明的顺序；token 多于声明时**最后一个参数吸收剩余**——`...` 参数保留列表，短语参数（`skills list` 的关键词）保留用空格拼起来的结果。

**键名从哪来**：`map` 的键就是声明里每个 `Arg.Key`（`address` / `path` / `pattern` …），flag 的键是去掉 `--` 的 flag 名。地址这类会被再拆的值，解析器可以额外返回派生键（`address.version`），但 `Arg.Key` 本身必须在——T03 断言的就是它。**多 token 合并成一个位置参数的命令**（`skills list` / `skills search` 的关键词，`main.go:1696` 把 `words` 拼成 query）取合并后的值，T03 的「等于 argv 里那一段」对它们按合并结果定义。

`Requested` 是纯函数：同样的 args 永远给出同样的答案，不读环境、不发请求——这也让验收 #12 的属性式跑法成立。

### 与既有代码的接缝（逐处）

| 位置 | 今天 | 这一版 |
|---|---|---|
| `main.go:46` `run` | 空参数 → `usage()` + 错误；否则 `switch args[0]` | 空参数分支之后调 `helpserve`；空参数走「命令表 + 退出 2」；`switch` 原样保留 |
| 26 处帮助分支 | 21 个 `case "help", "-h", "--help"`（`main.go` 6 / `tags.go` 12 / `lifecycle.go` 2 / `version.go` 1；其中 `main.go:67` 是顶层 `skillmaster help` 自己，所以「接受 `--help` 的命令」是 20 条）；另有 3 处直接判断（`files.go:488`、`files.go:678`、`main.go:435`）与 2 处 `asksForHelp`（`main.go:1547`，调用点 `:1261` / `:1318`） | **全部删除**——帮助只在派发之前处理一次 |
| `version.go:236` `usageError` / `:240` `usagef` | 已有 90 处用 `usagef` | 保留。**迁移按判据，不按行数**：任何一条「在说命令敲错了」的错误都要是 `usageError`。今天是三类——① **39 处**纯 `errors.New(<用法串>)`（AST 计：`main.go` 11 / `tags.go` 15 / `files.go` 7 / `lifecycle.go` 4 / `download.go` 2）；② **38 行** `fmt.Errorf` 引用了用法串（口径：先 grep `fmt\.Errorf` 的行，再在其中过滤 `usage`，大小写不敏感），其中一部分是把用法说明附在运行时失败后面、**不是**用法错误，逐条判定；③ 只写句子的用法错误（如 `main.go:2176` 的 `@` 拒绝） |
| `version.go:430` `reportError` | **今天只认 `exitError` 的码**：`cliFailure`（含 `usageError` 经 `classifyFailure` 得到的那条）与其余一律 1 | **四类映射**：`usageError` → 2、`credentialError` → 4、**`*api.Refusal` 原始码四选一 → 4**、**`cliFailure.Exit`**（两处填：`classifyFailure` 置凭据不可用的 4；`classifyExit` 置 usage 的 2 与 401 原始码的 4）；其余不变。**算码与输出形态解耦**（JSON 分支也返回算出来的码） |
| `files.go:494`–`:514` grep 的 `exitError{2}` | 用法 / 认证 / 服务端拒绝一律 2 | 换成 `usagef` / `credentialError` / 原样错误，由 `reportError` 决定 |
| `version.go:94` `pageOnlyVerb` | 经 `usagef` 归成用法错误（退出今天 1） | 改成一个**非用法**的拒绝错误（新类型或既有 `cliFailure`），退出 1 |
| `tags.go:218` `myTagsCmd` 的 `delete` 分支 | 纯 `errors.New`，退出 1 | 原样保留，只在声明里登记为「只拒绝、不是用法错误」 |
| `main.go:1107` `environmentKeyProblem` | 普通 error → 1 | `credentialError` → 4 |
| `main.go:1146` `clientFor` 的 `--no-login` 分支 | 普通 error → 1 | `credentialError` → 4 |
| `main.go:472` `refusalMessage` | 把 401 包成一句中文 | 码集合不变；`auth status` 的 `:614` 保留原始码（验收 #11） |
| `main.go:614` `authStatusWith` | 已经收 `credentials.EnvStore` | 不改签名——测试用它注入一个读取报错的 store（test-plan T13） |
| `apikey/format.go:68` `IsKey` | 既有 | 直接用作「形状是不是 key」的判据，不新增判断 |
| 所有取 client 的地方（直接 `session(ctx)` 的 8 处 + 经 `authenticated` / `authenticatedWith` 的其余命令，后两者内部调 `sessionMode`，`main.go:1209-1225`） | **地址语法检查散在客户端里**：`versionInAddress`（`main.go:2167`）只拒 `@` 后为空；「`<命名空间>/<名字>`」的完整语法是 `api.SkillPath`（`internal/api/client.go:677-691`），今天只在 `download` 里被提前调用（`download.go:55`，包成 `usageError`） | P3 逐条核对：**地址的纯语法检查（`api.SkillPath` + `versionInAddress`）必须在取 client 之前**。今天没前移的是 `invoke`（只调 `versionInAddress`，`main.go:1926`）、`read`（`pinFor`，`main.go:2123`）、`files`（`main.go:2384`）、`versions`、`share`、`submit`；`cat` / `wc` / `grep` 的 `resolve` 在 `authenticated` 内部（`files.go:54-58` 与 `:277` / `:373` / `:504`）。拆法：`resolve` 分成 `parseAddress`（两者，不碰 client）与 `resolveVersion`（需要 client），前者放到 `authenticated` 之前 |

**实现时补的第二条**：**「凭据不可用」「连不上服务端」是每条联网命令都会遇到的，但 **45 条能退 4 的命令**（56 − 8 条只拒绝 − 3 条本机）里今天只有 4 条写过**。它们由 `UniversalFailures` 从内核的一张共享表渲染，声明里通常不写——这两句话在哪儿都一样，复制 56 遍只会有 56 个漂移机会。命令自己登记了同名码时（`login` / `logout` / `setup` / `skills categories` / `skills public-tags` 各有自己的 `network` 说法）以命令的为准，共享表不重复渲染。判据是 `Exits` 含 4：拒绝类（0/1）与本机类（`login` / `logout` / `setup`）不加。代价实测（最终形态，口径：56 条可执行命令的 `--help` stdout 字节，2026-10-24 在第 3 轮审计补齐声明后重测）：平均 **711**，最长的 `skill submit` **1211**，离 2 KB 上限仍有 800 余字节；六个纯分组与顶层另算。**这个数字会随声明变化**——补一句说明或一条例子就涨几十字节，所以它是量出来的快照，不是承诺；承诺是每条 ≤ 2 KB 那条断言（`TestEveryHelpFitsTheBudget`）。

**实现时补的一条**：**每一条用法错误（退出 2）都以 `用法见：<该命令> --help` 收尾**（`usageAt(path)`，与未知命令 / 未知 flag 用的是同一个地址），旧的那批 `用法：skillmaster …` 常量随之删除——它们与声明是同一句话的两份。

这句话的强度是逐条落地的，不是靠人记得写：**地址在派发处收口**。`skill` → `skill tag` → `skill tag add` 每一层在返回前调 `retargetUsage(err, path)`——消息里没有地址就补上，地址指向上级就改深，已经指向更深的命令就原样放过（否则最外层的 `skill` 会把每一个地址都拉回自己）。因此解析器不必知道是谁调用了它：`--offset` 同时服务 `read` 与 `invoke`，`splitNewDraftFlags` 同时服务五个写命令，它们的句子到派发处才落到具体命令上。**所有本地解析与校验失败**都已改成用法错误（`usageError`）：`checkBareAddress`、`versionInAddress`、`parseOffset`、`splitNewDraftFlags`、`parseShare` 的 `--role` 与地址语法、`auth status` 的地址、`skill submit --to`、`logout` 的多余参数、`skill submit` 的不认识的参数。判据由两条测试守：`TestUsageErrorsEndAtTheHelpAddress`（按类列的用例表）与 `TestEveryCommandRefusesAnUnknownFlagAtItsOwnHelp`（**每一条**命令各跑一次——分组、纯分组、叶子都跑）。八条只拒绝的命令不在此列：它们的答案就是「这个动作在哪儿做」，退出 1，声明里的 `refused` 就是那个标记。**未知主题也不是命令**：`skillmaster help nope` 的收尾是「上级：skillmaster help」，指一个不存在的 `help nope --help` 会把读者送到错的地址。

**只有地址、没有句子的用法错误会被补上该命令的用法行**：`错误：参数不对。用法：<Spec.Usage>。用法见：…`。此前「参数个数不对」这一类只印出一个地址（`错误：用法见：…`），读者知道哪里有问题却读不到问题是什么；用法行本来就是声明里现成的，不必再开一页。守它的是 `TestAUsageErrorSaysWhatWasWrong`。

地址语法检查（`checkAddressSyntax`）在这一版也拒 `@` 后为空，与 `versionInAddress` 的判据一致；它现在带命令路径，因为那条收尾要指名是哪条命令。

**一处勘误**：服务端 `TokenValidation.Rejected` 的白名单是**五个**码（四个 401 + `account_suspended`），ADR 0087 的「后果」写成「四个码的白名单」是笔误；ADR 正文按规矩不改，以这里为准。

### 服务端、控制台、站点

**零改动。** 401 的四个码已经在服务端的拒绝里；这一版只是让 CLI 把它们映射成退出码。

## 分期

每一期结束都能独立验证；验收编号见 [`prd.md`](prd.md) §验收与指标。

| 期 | 交付什么 | 验证什么 |
|---|---|---|
| **P0 内核** | `internal/help` 的类型、`Render`、`Requested`、`Suggest`、registry 骨架 + 单测 | 渲染的版式（#4）、路径解析的五种情形与 `help env`（#12 / #16 的路由部分）、编辑距离的两个「不猜」分支（#6–#8） |
| **P1 声明** | 56 条命令（已含裸 `skills my-tags`）+ 6 个纯分组 + 顶层的声明，以及 `help env` 这个 topic；一个按声明绑定的 `parseOnly` 入口；中文文案照 [`design/voice-spec.md`](../../../design/voice-spec.md) 的名词表 | 声明表能被 `Render` 全部渲染出来、逐条 ≤ 2 KB、字段长度合规（#2 的静态部分、#14 的体积部分）；**退出码与 stdout/stderr 的断言留给 P2** |
| **P2 接管** | `helpserve` 接进 `run`；删掉 26 处旧帮助分支；重写顶层 `usage()`；`help env` | #1 / #4 / #5 / #6 / #7 / #8 / #12 / #13 / #14 / #16 / #18 / #21 |
| **P3 退出码** | `usageError` 迁移（39 处纯构造 + `fmt.Errorf` 里引用用法串的那些 + 只写句子的那些）、`credentialError`、`reportError` 映射、grep 归一、页面专用拒绝显式化、`auth status` 原始原因 | #9 / #10 / #11 / #17 / #19 |
| **P4 强制** | 命令树测试（扫 `switch args[0]` 的 AST）、声明比对测试、fixture 反向验证 | #2 / #3 / #15 / #20 |
| **P5 指标**（**已交付，2026-10-24**） | `testdata/help-eval.json`（20 题）+ `help_eval_test.go`（不默认跑，`SKILLMASTER_HELP_EVAL=1` 才跑；`claude --bare` 在只放了二进制的临时目录里跑 3 遍） | 指标一节里 T25 的那两个数：首次找对率 **95%（达标）**、调用中位数 **5.5（未达标）**；两个数都来自 `deepseek-v4-flash[1m]`，换模型必须重跑（test-plan §环境与数据） |

**P0 与 P1 之间不回退**：P0 的类型一旦定下，P1 只填数据。P3 可以独立于 P2 先做（退出码不依赖帮助），但两者的 golden 快照要一起更新。

## 开放问题

1. **`--json` 的适用性集合怎么维护？**
   当前倾向：`JSONFields` 非空即适用，这是唯一来源。它只驱动**帮助的渲染与建议的候选**，**不改变命令接到 `--json` 时的行为**——今天 `invoke` / `write` / `mv` / `cp` / `submit` / `share` 各自以不同方式拒绝它，本版不去统一，也不新增拒绝。**要回答的是**：`rejectJSON` 的 20 个调用点要不要保留——倾向保留（它给的是「明确拒绝」的即时文案），但**它不再是适用性的来源**，声明与它不一致时测试失败、以声明为准。

2. **将来有 flag 值允许以 `--` 开头怎么办？**
   当前倾向：那时必须先加 `--` 终止符，`Requested` 的全局扫描改为「`--` 之后不再扫描」。**要回答的是**：现在要不要提前把 `--` 终止符实现掉——倾向不实现（PRD 不做），因为没有值需要它，提前实现会让「`--` 之后是数据」成为一条没人验证的规则。

3. ~~**`skills my-tags` 的块放在参数区之前还是之后？**~~ **已决：块在参数区之前**（`render.go:25-47`，`TestRenderExecutableNodeHasBothBlockAndItems` 钉住）。当初要回答的是「块夹在用法与参数之间会不会让人以为 `add` / `rename` 是参数」——答案是不会：块自带 `子命令：` 标签，与参数区的 `参数：` 是两个标题。

4. **`auth status` 的「原始原因」落在哪一句？**
   今天 `authStatusWith`（`main.go:614`）把 401 包成 `服务端：…`。当前倾向：**保留 `refusalMessage` 的中文句**（`account_suspended` 的「账号已被停用」就在它里面，验收 #11 断言这句），只在后面补上原始码，形成「服务端说：token_expired（这份凭据已经到期…）」。**不能改成只用 `classifyFailure` 的 `Message`**——那是服务端的英文 body，会把中文句与 T13 的断言一起丢掉。**要回答的是**：原始码要不要也进 `--json`——`auth status` 今天没有 `--json`，所以只改 stderr。

5. **建议器的候选要不要跨层？**
   当前倾向：只同层（PRD 做 #4）——`skillmaster versoin list` 是同层的拼错，会提示 `version`（距离 2）。**要回答的是**：`skillmaster invoke a/b` 这种「把动词说到顶层」的情形要不要提示 `skill invoke`——倾向不提示：默认的上级地址已经指到 `skillmaster help`，跨层猜会把建议变成猜测，而且同层没有 ≤ 2 的候选时规则已经说「不猜」。

6. **eval harness 进不进 CI？**
   当前倾向：不进。它要调用模型、有成本，而且 20 道题的回答不可能是逐字节的。**要回答的是**：以什么频率手工跑（每次改帮助契约时跑一次？每版交付前跑一次？）——倾向每版交付前一次，结果写进 `test-plan.md` §结果。
