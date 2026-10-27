# v26-startup-guidance-and-agent-paths · 技术设计

> **最后更新**：2026-10-27
> **状态**：实现中（CLI 侧已按本设计实现并有单测；平台技能 `@1.3.1` 已发布；真机与跨平台验收未做）
> **范围**：CLI（`skillmaster-cli/`）与平台技能内容（`skillmaster/startup@1.3.1` 已发布）；不改服务端、数据库与控制台页面。

## 架构

**只改三处，边界清楚：**

| 处 | 改什么 | 不碰什么 |
|---|---|---|
| `skillmaster-cli/internal/prompt/` | `prompt.json` 从单文件记录扩成一组记录；块本身的定位 / 替换 / 追加逻辑不变 | `block.md` 的正文、标记格式、摘要算法 |
| `skillmaster-cli/cmd/skillmaster/` | `startup --plan`、`prompt install/update/status/uninstall` 的计划先行、路径检查、多文件记录、记录行；`run` 对 `--plan` 跳过两个有写副作用的钩子 | 登录、提交、上线、下载、版本、检索的既有命令 |
| 平台技能 `skillmaster/startup` | 删路径表、五句话请示、七项清单、非技术用户语气、`.skillmaster` 一次授权 | 规则段正文（仍是 v20 批准的三行，[ADR 0102](../../decisions/0102-the-rule-block-ships-with-the-cli.md)） |

**数据流**

```text
skillmaster startup --plan [--agent] [--file]
  ├─ 本地：version / credentials / local.Scan / prompt records
  ├─ 服务端（有凭据才问，只读）：Me / PublishQueue / QuerySkills
  └─ stdout: 七项清单；stderr: 「正在检查…」+「下一步：steps/0N」
        │
        ▼
agent 读清单指向的 steps/0N-*.md
        │
        ▼
skillmaster prompt install --file <路径> [--agent <名字>]      ← 不给 --yes 只打印五句话，退出 1
        │  人同意
        ▼
skillmaster prompt install --file <路径> [--agent <名字>] --yes ← 写入并记录
        │
        ▼
重跑 startup --plan → 重算清单 → 报进度 → 下一项
```

**读命令的写边界（v24 的规矩，这一版继续）**

- `startup --plan` 不写 `prompt.json`、不写 `update.json`、不写 pin、不拉旅程正文、不打开浏览器。
- 现状里有两处会在任何命令之前写东西：`run` 的 `noteStaleBlock(args)`（写 `prompt.json` 的 `notified`）与 `autoUpdate(ctx, args)`（可能写 `update.json`，甚至换二进制）。**`startup --plan` 必须在调用它们之前被识别并跳过**（见 [`main.go`](../../../skillmaster-cli/cmd/skillmaster/main.go) 的 `run`，第 83–85 行）。`--plan` 自己的更新检查走 `updater.Check`，只读。
- `prompt install/update/uninstall` 只在 `--yes` 之后写；`prompt print`、`startup --plan` 只读。`prompt status` 会顺手把落后的记录修正到文件当前摘要（v25 的行为），因此它不是严格只读；在只读 `$HOME` 下它会报写失败。

## 数据模型

### `prompt.json` v1（现状）

`internal/prompt/store.go` 的 `Record` 是一个文件：

```json
{"file":"/Users/me/.claude/CLAUDE.md","digest":"sha256:…","version":"1.1.0","notified":"sha256:…"}
```

### `prompt.json` v2（本版）

同一个文件，改成一组。`schema` 是给以后的迁移留的，不参与查找：

```json
{
  "schema": 2,
  "files": [
    {"path":"/Users/me/.claude/CLAUDE.md","agent":"claude","digest":"sha256:…","version":"1.1.0"},
    {"path":"/Users/me/.codex/AGENTS.md","agent":"codex","digest":"sha256:…","version":"1.1.0"}
  ]
}
```

Go 类型（仍在 `internal/prompt/store.go`）：

```go
type Record struct {
    File     string `json:"path"`
    Agent    string `json:"agent,omitempty"`
    Digest   string `json:"digest"`
    Version  string `json:"version,omitempty"`
    Notified string `json:"notified,omitempty"`
}

type Store struct {
    Schema int      `json:"schema"`
    Files  []Record `json:"files"`
}
```

- **`Agent` 是记录标签**：写入与 `status` 都不校验名字；`--plan` 在没有 `--file` 时会用它筛记录，但仍不校验。空标签表示「不知道 / 没给」。同一台机器可以有多个 agent、同一个 agent 也可以有多份（WorkBuddy 换账号后目录变了）。
- **一个路径一条记录**：按 `File` 去重；同一路径再次 `install` 更新 digest / version / agent，不新增一条。
- **v1 读入**：`LoadStore` 读到 `file` 字段（没有 `files`）时，映射成一条 `{File: file, Digest, Version, Notified}`；下一次任何写操作把它保存成 v2。`Save` 的旧单条 API 保留为兼容包装（写一条 v2），现有测试可以逐步迁移。
- **顺序**：`Files` 按第一次写入的顺序保存；输出按这个顺序，不排序（「先写的那份」对人有意义）。
- **写**：仍然用 `os.OpenFile(..., 0o600)` + `MkdirAll(dir, 0o700)`（[ADR 0101](../../decisions/0101-the-cli-never-deletes-files.md)：不引入删除；这里没有临时文件）。**并发写是最后写入者赢**；安装流程是顺序的，这一版不加锁——这是开放问题 Q7。

### 清单里不存任何东西

`startup --plan` 的七项每次重新算；没有「做到第几步」的文件（[PRD](prd.md) 不做 #11）。`prompt.json` 只记「哪个文件写过、写的是哪版」，不记清单进度。

## 接口

### 1. `skillmaster startup --plan [--agent <名字>] [--file <路径>]`

**只读。** 不登录、不拉旅程正文、不钉版本、不写任何文件、不打开浏览器。

**解析**：`--plan` 是开关；`--agent` 与 `--file` 只和 `--plan` 一起用，单独出现是用法错误（退出 2）。`startup` 不带 `--plan` 时保持 v25 的旅程形状（登录 → 状态行 → 记录行 → 旅程正文）。

**预算**：整条命令 5 秒 p95（[PRD](prd.md) 指标）。两段按顺序跑：服务端状态先给 3 秒，更新检查再给 2 秒；任何一段超时只把对应项写成「暂时读不到」/「暂时查不到新版本」，不拖住整张清单。

**七项与状态**（输出用用户看到的名字，内部状态是已完成 / 待做 / 跳过 / 未知）：

| 项 | 怎么算 | 已完成 | 待做 | 跳过 / 未知 |
|---|---|---|---|---|
| 小工具 | `version.String()`；`version.Version == "dev"` 或 `updateBase() == ""` 不检查；否则 `updater.Check` | 最新 / 开发构建 | 有新版本 | 检查失败：「暂时查不到新版本」 |
| 登录 | `openStore(config.Server())` → `store.Load()`；有 token 时 `Me(ctx)` | 服务端认这个账号 | 没有凭据 / 401 | 网络失败：「暂时读不到」 |
| 传技能（可选） | `local.Scan(nil)` 的名字集合，减去 `live` 与 `pending` | N>0 且 0 个没传 | —（**永不待做**） | N=0 → 跳过「本机没有可上传的技能」；M>0 → 跳过「可选；还有 M 个没传，想传的时候再跑一次」；未登录 / 服务端读不到 → 跳过「可选；登录后 / 稍后再看」 |
| 正式上线 | `PublishQueue` 里本人命名空间的草稿数 | 有本机技能且 0 份 | K 份 | 本机没有技能 → 跳过；未登录 / 读不到 → 「登录后检查」 |
| 让 AI 先来 SkillMaster 找技能 | 见下 | 记录里的文件当前且最新 | 要写 / 要更新 / 要补记录 / 要确认用哪一份 | 文件不在了 / 路径不能写 |
| 让 AI 用 SkillMaster 自己的文件夹 | 本机有没有要清理的副本；CLI 读不到 agent 的权限设置 | 没有要清理的 | — | 有本机副本时写「清理电脑上原来那份时才需要」 |
| 电脑上原来的那份 | `whereabouts.online()`（本机名字 ∩ live） | 0 个（没有要清理的） | — | L>0 时写「可选，以后也能做」 |

**「让 AI 先来 SkillMaster 找技能」的四种输入**：

- `--file X`：只查 X。X 有记录且块与二进制一致 → 已完成；有记录但摘要不同 → 待做「这段说明是旧版，要更新」；X 有块但没有记录 → 待做「已经有这段说明，只记下位置」；X 没有块 → 待做「要加一段说明」；X 读不到 / 是目录 / 父目录不存在 / 不在主目录下 / 落在当前项目目录 → 待做，原因照 `prompt install` 的通用检查写出来（**只报告，不写**）。
- 只给 `--agent A`：查所有 `Agent == A` 的记录（`--agent` 仍是记录标签，不校验名字）。一条且当前 → 已完成；多条 → 待做「这个 AI 写过 N 份；告诉我用哪一份」；没有 → 待做「还没写」。
- 两个都给：以 `--file` 为准；在清单里说「记录里还有 N 个别的文件」。
- 都不给：有记录 → 待做「这台电脑上写过 N 份；告诉我这个 AI 用哪一份」；没有记录 → 待做「还没写」。

**输出**：stdout 是清单，stderr 是过程与下一步。

```text
SkillMaster 安装清单
让这台电脑上的技能，在任何 AI 里都能用。

已经好了（5 件）：小工具、登录、传技能、正式上线、让 AI 先来 SkillMaster 找技能。
跳过（2 件）：让 AI 用 SkillMaster 自己的文件夹（清理电脑上原来那份时才需要；只给 SkillMaster 自己的文件夹）、电脑上原来的那份（可选，以后也能做）。

必做项都好了；要不要清理电脑上原来那份？（可选）
```

- 未登录时，登录之外依赖服务端的三项（正式上线、让 AI 用 SkillMaster 自己的文件夹、电脑上原来的那份）写「登录后检查」，不写 0；网络读不到写「暂时读不到」。**传技能不在这三项里**：它是可选的，未登录 / 读不到时只写「跳过（可选；登录后 / 稍后再看）」，不阻塞完成。
- 没有待做项、也没有未知项时：如果还有没传的本机技能，写「必做项都好了；还有技能没传，想传的时候再跑一次。」；如果本机还有旧副本，写「必做项都好了；要不要清理电脑上原来那份？（可选）」；两者都有就按这个顺序并列；都没有才写「全部好了。」。只要还有未知项，写「还有几件要先确认；现在还不能算完成」，不写「全部好了」。
- stderr 先打「正在检查你的电脑和 SkillMaster…」，最后一行是 `下一步：steps/03-prompt.md`（或「先登录」「先更新小工具」「稍后再检查一次」等）。
- 不报百分比；大步骤的数字由步骤文件自己报（「第 3 个，共 33 个」）。
- 退出码：打印出清单就是 0（包括「先登录」、单项读不到，例如 `config.Dir()` 失败）；整张清单算不出来（`local.Scan` 失败）才 1。

**状态收集**：把 `state.go` 的 `whereYouAre` 拆成 `gatherWhereaboutsFrom(ctx, client, skills) (whereabouts, error)` + 保留 `whereYouAre` 作为「拿不到就不打印」的包装；`startup --plan` 用 `gatherWhereaboutsFrom`，把错误映射成「登录后检查 / 暂时读不到」。`whereYouAreLine` 的文案不用于 `--plan`；`--plan` 用上面七项的模板。

**状态行的下一步（v26 迭代 0001）**：传技能可选后，状态行不再让「本机还有没传的副本」压过草稿与说明。优先级改成：有草稿 → 「下一步：上线。」；没有草稿但有本机技能 → 「下一步：写提示词、验证，然后下线。」；没有本机技能、库里有线上 → 「已完成：平台可用，本机没有副本。」；都没有 → 「下一步：写提示词、验证。」。KI-1 的防线从「必须指向搬运」改成「必须显示本机有多少已上线」——`其中 0 个已上线` 仍然让人看见没传，但不再阻止下一步。

**跳过钩子**：`run` 在调用 `noteStaleBlock` / `autoUpdate` 之前判断 `isReadOnlyPlan(args)`（`args[0]=="startup" && args` 含 `--plan`），跳过两者。`--plan` 自己调 `updater.Check`，不写 `update.json`。

### 2. `skillmaster prompt install --file <路径> [--agent <名字>] [--yes] [--force]`

**不给 `--yes` 只打印计划并退出 1**，不写目标文件；文件里已有逐字相同的块时只更新记录、退出 0（这是唯一的例外：没有内容要改）。

**计划固定五句话**（CLI 的输出，agent 可转述；路径与命令都不打印）：

```text
我要做什么：给这个 AI 加一段说明（新建 / 追加 / 替换原有的一段），让它以后先来 SkillMaster 找技能。
只会动到什么：只改这个 AI 每次开始会读的那份说明里的一段；文件其它内容不动。
做完会怎样：这个 AI 以后每次开始都会读到这段说明，先来 SkillMaster 找技能。
不想做了怎么办：可以只去掉这段说明，文件本身保留。
不做会怎样：这个 AI 还是按原来的方式找技能，可能用不上平台上的技能。
```

**通用路径检查**（`--force` 只越过「不在主目录下」这一条；越过之后仍要 `--yes`）：

1. `--file` 不能为空；`~` 展开后转绝对路径。
2. 目标存在且是目录 → 拒绝。
3. 父目录必须存在（不替人建目录）；父目录是符号链接时按真实路径判断。
4. 目标（存在的符号链接按 `EvalSymlinks` 后的真实路径）必须在用户主目录下；不在 → 拒绝，`--force` 可越过。
5. 路径落在当前工作目录下、且当前目录不是主目录 → 计划里加一条「这个位置只对当前项目生效；我们想让它对整台电脑生效」——由人确认，不拒绝。
6. 计划里不打印路径；审批框里已经显示路径时，用户问起再用一句话说明它是哪个文件。

**写入**：复用 `writeBlock`（暂存 `.skillmaster.new` → `Chmod` → `Rename`；写前重读，文件被改过就不写；[ADR 0101](../../decisions/0101-the-cli-never-deletes-files.md)）。没有块 → `prompt.Append`；有块 → `prompt.Replace`；新文件 → `0o644`。写完再 `SaveStore`；记录写失败不回滚文件（与 v25 相同）。

### 3. `skillmaster prompt update [--yes]`

- 遍历所有记录；每份读文件、定位块、与 `prompt.Block()` 比较；一致只更新 digest；不一致且 `--yes` 才替换；缺文件 / 缺块 / 文件被改过 → 记下这一份失败，继续下一份。
- 不带 `--yes`：逐份打印文件路径与差异，退出 1，不写。
- 全部成功 → 0；任一份失败 → 1。没有记录 → 1，提示 `prompt install`。

### 4. `skillmaster prompt status`

- 遍历所有记录，逐份输出：路径、agent 标签、记录摘要、文件里的摘要、一致 / 不一致 / 文件不在 / 没有标记段 / 有多个标记段。
- 全部一致 → 0；任一份不是一致 → 1；没有记录 → 1。
- 不扫描本机 agent（那是表的复活）；只报告记录里的文件。

### 5. `skillmaster prompt uninstall (--file <路径> | --all) [--yes]`

- `--file X`：即使 X 不在记录里也允许——用于「写错位置」的清理；读文件、定位块、去掉 `Begin` 到 `End` 的整段（含标记与换行），文件其它字节不动；有记录就删掉那一条。
- `--all`：只处理记录里的文件；逐个去掉块与记录。
- 不带 `--yes`：按同一套五句话结构说明会去掉哪段说明；`--all` 时再列出记录里的 AI 标签（不打印路径），退出 1，不写。
- 文件不存在 / 没有块：报出来；没有可动的 → 1；至少动了一个且没有失败 → 0；部分失败 → 1。
- **不删文件**（[ADR 0101](../../decisions/0101-the-cli-never-deletes-files.md)）；不删备份。

### 6. `startup` 的记录行

`startup` 在状态行之后、旅程正文之前，向 **stderr** 打一行（没有记录就不打）：

```text
这台电脑上写过这段说明的 AI：Claude Code、WorkBuddy。如果你不是其中之一，按第三步再写一份。
```

- 只读 `prompt.LoadStore`；没有服务端请求。
- agent 标签按记录里的原样输出（不会改写成别的名字）；标签为空时写「未命名」，不显示路径；多个用「、」。
- 在 stderr，保证 stdout 仍是旅程正文逐字（v17 的契约）。

### 7. 帮助声明

- `declarations.go` 的 `startup` 用法改成：
  `skillmaster startup [--plan [--agent <名字>] [--file <路径>]]`
- `prompt` 子命令加 `uninstall`；`install` 的用法加 `[--agent <名字>] [--yes] [--force]`；`update` 保持 `[--yes]`；`status` 保持无参数。
- 八项帮助、≤2 KB、无 ANSI 的既有契约不变（v16）；`startup` 的新旗标要能从 `help startup` 读到。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P0** | `internal/prompt` v2 store（v1 读入、`LoadStore`/`SaveStore`、兼容 `Load`/`Save`）；`prompt install` 计划先行 + 路径检查 + `--agent`；`prompt update` 全部；`prompt status` 逐份；`prompt uninstall`；`noteStaleBlock` 多份 | PRD 验收 3–8 |
| **P1** | `startup --plan`（七项、只读、跳过钩子、stderr 下一步）；`state.go` 抽 `gatherWhereaboutsFrom`；`startup` 记录行；帮助声明 | PRD 验收 11–15、17、22 |
| **P2** | 平台技能 `@1.3.1` 已发布：删表、五句话、`voice.md`、`.skillmaster` 一次授权、七项清单、每步重算 | PRD 验收 1、9、10、18、20 |
| **P3** | 安装说明页第 0 步；CLI README；`target/README.md` §六；ADR 0104 / 0105 / 0106 / 0107；全量测试与 `windows-latest` | PRD 验收 16、19；CI |

**发布顺序**：技能 `@1.3.1` 已上线；CLI 侧按本设计实现后发布。窗口期里老 CLI 的 `prompt install` 首次写不会先出计划（v25 行为），但能写入；新 CLI 发布后计划先行生效。

## 开放问题

1. **`--plan` 没有凭据时退出 0 还是 1？** 当前定 0：清单打印出来了，第一项就是「登录」；只有完全算不出来才 1。与 PRD 一致，但测试要钉住。
2. **`--file` 指向主目录外时，`--plan` 怎么显示？** `--plan` 不接受 `--force`；当前定：该项待做，原因写「这个位置不在你的主目录里；应该放在整台电脑都能用的地方」，不拒绝整张清单。真正写入时再用 `--force`。
3. **`prompt update` 一份失败是否回滚已更新的？** 定：不回滚；逐份报告，任一份失败退出 1。回滚会把已经成功的文件再改一次，风险更大。
4. **`prompt uninstall --file` 不在记录里时，退出码是什么？** 定：有块且去掉成功 → 0（不额外打印「没有记录」）；没有块 → 1。
5. **`noteStaleBlock` 多份时的提示次数**：一份旧 → 一行；多份旧 → 一行，列出数量；把每份旧记录的 `Notified` 都标成当前摘要，避免下一条命令再念。这个写发生在普通命令上，不在 `--plan` 上。
6. **`--plan` 的更新时间与平台状态怎么分预算**：两段顺序跑，服务端状态 3 秒、更新检查 2 秒；任一段超时只影响该项。实测 p95 超过 5 秒时，再缩短更新窗口并接受「暂时查不到」。
7. **`prompt.json` 并发写**：v2 仍是一个文件，两个 agent 同时 `install` 可能最后写入者覆盖前一条。安装流程是顺序的，这一版不加锁；如果真实出现，改成按路径哈希一文件（像 v24 的 pins），那是下一版的迁移。
8. **Windows 的路径判断**：`filepath.EvalSymlinks`、大小写不敏感、`%USERPROFILE%` 与 `os.UserHomeDir()` 的差异要在 Windows 夹具里验；`--plan` 的「不在主目录下」按 `filepath.Rel` 判断，不字符串前缀。
9. **`.skillmaster` 权限**：CLI 不写 agent 的权限设置（不做表）；由 agent 在步骤 06 完成，验收 20 是人工/transcript，不是单元测试。`--plan` 不检查这一项——它属于清理流程的前置，没有本机副本时写成「没有要清理的，不用加」。

## 对代码的断言（写实现前核对）

| 断言 | 位置 |
|---|---|
| 实现前 `startup` 拒绝任何参数；现在接受 `--plan/--agent/--file`，登录由 `clientFor` 驱动 | [`main.go`](../../../skillmaster-cli/cmd/skillmaster/main.go) 的 `startup`（约 3278 行） |
| `run` 在命令前调用 `noteStaleBlock` 与 `autoUpdate` | 同文件 `run`（约 83–85 行） |
| `prompt install` 现在首写不要求 `--yes`；`prompt update` 已计划先行 | [`prompt.go`](../../../skillmaster-cli/cmd/skillmaster/prompt.go) 的 `promptInstall` / `promptUpdate` |
| 记录的读写是单个 `Record`，`Save` 用 `O_TRUNC` | [`internal/prompt/store.go`](../../../skillmaster-cli/internal/prompt/store.go) 的 `Record` / `Load` / `Save` |
| 块定位 / 替换 / 追加与 CRLF 处理可直接复用 | [`internal/prompt/block.go`](../../../skillmaster-cli/internal/prompt/block.go) 的 `Locate` / `Replace` / `Append` / `BlockIn` |
| 状态行由 `whereYouAre` 算三组名字；任何失败就不打印 | [`state.go`](../../../skillmaster-cli/cmd/skillmaster/state.go) 的 `whereYouAre` / `whereabouts` |
| `whereabouts.online()` 就是「本机名字 ∩ live」 | 同文件 `online` |
| 登录状态用 `openStore` + `store.Load()` + `client.Me` | [`main.go`](../../../skillmaster-cli/cmd/skillmaster/main.go) 的 `authStatusWith` |
| 更新检查是 `updater.Check(ctx, base, client)`；开发构建 / `updateBase()` 为空不参与（含 `SKILLMASTER_UPDATE_BASE` 覆盖） | [`update.go`](../../../skillmaster-cli/cmd/skillmaster/update.go) 的 `updateCmd`；[`version.go`](../../../skillmaster-cli/internal/version/version.go) |
| 本机扫描是 `local.Scan(nil)` | [`internal/local/local.go`](../../../skillmaster-cli/internal/local/local.go) |
| 帮助声明在 `declarations.go`，`startup` 目前只写 `skillmaster startup` | [`declarations.go`](../../../skillmaster-cli/cmd/skillmaster/declarations.go) 的 `startup` 规格 |
| 退出码 0/1/2/4 是公开契约 | [ADR 0087](../../decisions/0087-cli-exit-codes-are-a-public-contract.md) |
| CLI 不删文件 | [ADR 0101](../../decisions/0101-the-cli-never-deletes-files.md) |
