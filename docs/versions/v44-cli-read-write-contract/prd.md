# v44-cli-read-write-contract · 产品需求

> **最后更新**：2026-11-04
> **所属版本**：[`README.md`](README.md)
> **来源待办**：[`docs/current/TODO.md`](../../current/TODO.md) 的「`draft create` 新建草稿」「skill 加载与读取的区分」「写侧动词按输入来源分」「`edit` 的输入通道」四条

## 用户与场景

**谁在用**：用 `skmr` 改 skill、读 skill 正文、把整版取下来看的人，以及替他们执行这些动作的 agent。

**在什么场景下用**：

- 给一个已有 skill 开一份新草稿，然后分几次改。
- 更新一份已有 skill：先找未上线的草稿，再一处一处改，或者整份文件替换。
- 加载一个 skill 来用：`invoke` 拿到正文与钉住的版本，之后 `read` 读这一版里的文件。
- 只想看一眼：读 `README.md`、读某个文件、看整版目录。

**现在的替代做法是什么**：

- **建草稿不是一个动作，而是第一次写的副作用**：`write` / `edit` / `rm` / `mv` 用 `@new` 顺手把草稿建出来，`put` 则完全不能建草稿。想先建一份草稿、再分几次写，做不到；想从线上版本起一份草稿但暂时不改内容，也做不到。
- **写侧有两个入口，按“内容从哪来”分**：`write` 从 stdin 读文本，`put` 从本地路径读、按字节存。文件是不是二进制决定用哪个，而人真正要选的是“局部改、整文件改、整版重排”。
- **取侧同样有两个入口**：`get` 取一个文件，`download` 取整版；两者只差单个与整版。
- **`edit` 没有 stdin 入口**：改多行文本只能先写 `--old-file` / `--new-file` 临时文件，一次改三处就要写六个文件。
- **裸地址读 `SKILL.md` 只给一句 stderr 提示**：CLI 照样按当前线上版本读，agent 很容易忽略，于是“读正文”绕过了 `invoke` 的钉版本。

**为什么现在做**：这些不是假设。2026-11-03 改 `lijinzhao/code-dev-sop` 时，agent 刚读过“别搬回本地”，仍选择 `download` 整版、`put --overwrite` 回写 **31 次**；2026-11-04 改同一份 skill 用了 **14 次 `edit`**，每次都是“写 old 文件 + 写 new 文件 + 跑命令”。工具形状在把人推向更费事的路径。而“每轮 `submit` 都新建草稿”的根子，也在建草稿与第一次写绑在一起。

## 现在的问题、诱因与改法

### 问题 1：建草稿和第一次写内容绑在一起

**现在的命令**：

```bash
# 建草稿只能顺手做：@new 表示“这一版还不存在，从这一版起稿”
skmr skill write <地址>@new <路径> --name <名称> [--from <版本>] < 内容
skmr skill edit  <地址>@new <路径> --name <名称> [--from <版本>] --old ... --new ...
skmr skill rm    <地址>@new <路径> --name <名称> [--from <版本>]
skmr skill mv    <地址>@new <from> <to> --name <名称> [--from <版本>]

# put 不能建草稿：它只写已经存在的草稿
skmr skill put <地址>@<版本码> <本地路径>... <远端路径|目录/>
```

**问题**：建一版草稿不是一个独立动作。想先建草稿、再分几次写，做不到；想从线上版本起一份草稿但暂时不改内容，也做不到。反过来，每一次“第一次写”都可能顺手开一份新草稿——这正是“不重复造草稿”那条待办的根子。

**为什么会这样**：服务端模型限制。`CreateDraftFromVersionUseCase` 的注释写得很直接：**草稿行是在第一次内容改动时和文件一起插入的**——原样复制一版会带着同一个 digest，`UNIQUE (skill_id, digest)` 拒绝两行同内容，所以页面与 CLI 都只能把“建草稿”挂在第一次写上。

**怎么改能解决**：新增一个独立命令：

```bash
skmr draft create <地址> [--from <版本|版本码>] --name <名称> [--note <说明>]
```

默认从线上版本起稿（整版复制，内容不变）；`--from` 指定别的底稿。命令输出版本码，并直接给一条可复制的下一步写命令。服务端放宽“草稿行也受 `UNIQUE (skill_id, digest)` 约束”这半条——同一 skill 下允许存在与底稿内容相同的草稿，内容寻址的唯一性继续约束非草稿行。需要一条新 ADR 取代 [ADR 0064](../../decisions/0064-a-draft-may-change-a-published-version-may-not.md) §5 的半条。随后 `write` / `edit` / `rm` / `mv` 不再接受 `@new`，`--from <版本>` / `--name` / `--note` 这些只为起稿存在的 flag 一并退场（`rm` / `mv` 今天也支持 `@new`，别只改 `write` / `edit`）。

### 问题 2：写文件有两个入口，选哪个由“内容从哪来”决定

**现在的命令**：

```bash
# 内容从标准输入读；只收 UTF-8 文本
skmr skill write <地址>@<版本码> <路径> [--overwrite]

# 内容从本地路径读；只写已有草稿；二进制按原字节保存
skmr skill put <地址>@<版本码> <本地路径>... <远端路径|目录/> [--overwrite]
```

**问题**：改一个 PNG 只能用 `put`；改一个文本但内容已经在本地的文件里，人得先判断“这是文本还是二进制”，再决定走 `write` 还是 `put`。词表里真正该有的三个意图——局部改（`edit`）、整文件改、整版重排（`submit`）——没有一一对应。

**为什么会这样**：两个命令是按**数据来源**长出来的：`write` 从 stdin 读，`put` 从本地路径读；`put` 只服务已有草稿，`write` 能 `@new` 起稿。能力有交叉但不重合，帮助文本只能用“文本 / 二进制”解释差别，而 agent 要选的是“我想怎么改”。

**怎么改能解决**：`write` 增加一个“从本地文件读内容”的入口，按内容自动识别文本与二进制，`put` 删除并给迁移提示。问题 1 收走 `@new` 之后，`write` 的 `--from <版本>` 也随之消失，这个 flag 空了出来，正好按 TODO 的原写法用：

```bash
skmr skill write <地址>@<版本码> <远端路径|目录/> --from <本地文件>... [--overwrite]
```

多个 `--from` 对应 `put` 原来的多文件上传；远端以 `/` 结尾时按 basename 放进那个目录。

### 问题 3：取文件也有两个入口，只差单文件与整版

**现在的命令**：

```bash
skmr skill get <地址>@<版本> <路径> [-o <本地路径>] [--overwrite]
skmr skill download <地址>[@版本|@版本码] [--into <目录>]
```

**问题**：同一个动作（把远端内容取到本地）有两个名字，选哪个只取决于“一个文件还是整版”。`download` 还把整包读进内存（约 17 MiB），更大的版本只能退回 `get` 逐个取。

**为什么会这样**：同问题 2，按数据形态分而不是按意图分；`get` 要一个路径，`download` 不要，于是只能并存。

**怎么改能解决**：`get` 增加 `--all`（配 `--into <目录>`），沿用 `download` 的“目标目录必须为空”；`download` 删除并提示改用 `get --all`。整包是否同时改成流式落盘，留到技术设计回答。

### 问题 4：覆盖没有“你知道自己在覆盖什么”的约束

**现在的命令**：

```bash
skmr skill write <地址>@<版本码> <路径> --overwrite
skmr skill put   <地址>@<版本码> <本地路径> <远端路径> --overwrite
```

**问题**：`--overwrite` 只是一个布尔开关，表达“允许覆盖”，不表达“覆盖的是哪一版、会改掉多少”。默认拒绝只挡住了“新建 vs 覆盖”，挡不住“覆盖错内容”。2026-11-03 那次 31 次 `put --overwrite` 就是在这个形状下发生的。

**为什么会这样**：CLI 不知道调用方有没有看过被覆盖的那份内容，于是只能把判断全交给调用方；而调用方（agent）在长任务里不会每一步都回去读。

**怎么改能解决**：覆盖已存在文件时，必须**先完整读过这一版里的这个文件**（`read` / `cat` / `get` 算，`head` / `tail` 只看几行不算）。没读过 → 拒绝，提示先 `read`；读过了但远端内容在你读之后变过 → 同样拒绝，要求重读。**不设 `--force`**——读过就是许可证，和 Claude Code 覆盖本地文件要先 `Read` 是同一个模型。放行时先打印将被改动的行数，再执行。读过的记录落在版本钉存储（钉在本机：[ADR 0035](../../decisions/0035-the-pin-lives-on-the-client-machine.md)；一格一个记录：[ADR 0101](../../decisions/0101-the-cli-never-deletes-files.md)），文件内容一变就作废——这样“读过”对应的是**你读过的那一份**，不是“某个时间点之前读过”。

### 问题 5：`edit` 没有 stdin，一次也只能改一处

**现在的命令**：

```bash
skmr skill edit <地址>@<版本码> <路径> \
  (--old <文本> | --old-file <文件>) [--new <文本> | --new-file <文件>] \
  [--replace-all] [--regex]
```

**问题**：改多行中文时，命令行里塞不下，只能先写临时文件；一次改三处要跑三次命令、写六个临时文件。2026-11-04 改 `lijinzhao/code-dev-sop` 用了 14 次 `edit`，每次都是“写 old 文件 + 写 new 文件 + 跑命令”。

**为什么会这样**：输入通道只有“命令行内联”和“本地文件”两种，没有 stdin；服务端一次 `edit` 也只处理一处替换。

**怎么改能解决**：① `--old-file -` / `--new-file -` 表示 stdin（两端都写 `-` 时拒绝）；② `--stdin-json` 读 `{"old","new","replace_all"?}`，解决两端都长、一个 stdin 装不下的情况；③ 同一格式再收 `{"edits":[...]}`，一次请求落一个版本，任一处匹配不上则整批不落盘并指出第几处。用 JSON 而不是自定义分隔符：分隔符可能出现在正文里，JSON 有标准转义、格式错能当场拒绝。

### 问题 6：裸地址读 `SKILL.md` 只提示、不拦

**现在的命令**：

```bash
skmr skill read <地址> <路径>      # 裸地址、未 invoke 时：stderr 提示「还没 invoke 过，按当前线上版本取」，然后照读
skmr skill cat  <地址> <路径>...
skmr skill head <地址> <路径>
skmr skill tail <地址> <路径>
```

**问题**：`SKILL.md` 是技能正文，读它等于“要加载这个 skill”；但 CLI 把它和读 `README.md` 当成同一件事，只给一句 stderr 提示。agent 很容易忽略这行提示，于是“读正文”绕过了 `invoke` 的钉版本——下次文件变了，读到的是另一版，而且没有任何提示。

**为什么会这样**：`read` 的设计允许“只想看一眼”，这对普通文件是对的；但 `SKILL.md` 是唯一的“正文级”文件，读它的语义接近 `invoke`。CLI 没有区分这两个意图。

**怎么改能解决**：只对 `SKILL.md` 拦：裸地址 + 本机未 `invoke` + 路径是 `SKILL.md` → `read` / `cat` / `head` / `tail` 退出 2，提示先 `skmr skill invoke <地址>`。地址里写了 `@版本`，或本机已 `invoke`，照常读。服务端不做禁令（2026-11-02 已定）。

### 问题 7：CLI 会自己反复新建草稿

**现在的命令**：

```bash
skmr draft create ...                    # 本版新增：建草稿
skmr skill submit <目录> --name <名称>    # 已有命令：也会建出一份新草稿
```

**问题**：agent 在长任务里每一轮都 `submit` 或 `draft create`，同一个 skill 下会留下多份内容相近的草稿；人不知道哪一份是当前要改的，agent 下一轮也可能接着改错。

**为什么会这样**：服务端允许多份草稿（协作需要），CLI 没有任何“你已经有草稿了”的拦截。指南里写了“先看有没有开着的草稿”，但那是散文，agent 会跳过。

**怎么改能解决**：`draft create` 与 `skill submit` 在“**我自己**在这个 skill 下还有未上线草稿”时默认拒绝，列出草稿码、名称与下一步；加 `--new` 才新建。只算 `submitted_by = 我` 的草稿，协作者的不算；控制台不受这条限制——人在网页上点“新建版本”是明确的动作，不是 agent 的循环。

`--new` 是**隐藏 flag**：不写进 `--help`，只在报错里出现。CLI 不做 TTY 交互确认——agent 环境可能拿到 PTY，弹 `[y/N]` 会挂住。默认报错长这样（`skill submit` 只换最后一条命令）：

```text
你在这个 skill 下已有未上线的草稿：
  7V5N319A  v2 · 3 小时前
接着改这一份：
  skmr skill write dev/my-skill@7V5N319A <路径>
确实要另开一份：先问用户；用户确认后加 --new 再跑一次
  skmr draft create dev/my-skill --name <名称> --new
```

报错文案里明确要求“先问用户”，agent 不能把它当成自动重试的提示。代价如实写明：agent 仍然可以自己补 `--new` 绕过去。这条拦截挡的是“顺手就建”，不是“执意要建”；后者没有交互终端就没有可靠的人工闸门。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | **建草稿成为独立动作**：新增 `skmr draft create <地址> [--from <版本|版本码>] --name <名称> [--note <说明>]`，默认从线上版本整版复制、内容不变；命令输出版本码与一条可直接复制的下一步写命令；服务端放宽草稿行的 `UNIQUE (skill_id, digest)` 约束，并新增对应 API；`write` / `edit` / `rm` / `mv` 不再接受 `@new`，只为起稿存在的 `--from <版本>` / `--name` / `--note` 一并退场 | 服务端 + CLI + 迁移 |
| 2 | **写侧按意图收口**：`skill write` 增加 `--from <本地文件>...`（可重复），文本照现有规则归一，二进制按原字节保存；`skill put` 删除，旧名字只保留一句迁移提示并以非零退出 | CLI 命令与 help |
| 3 | **取侧收口**：`skill get` 增加 `--all`（整版写成一个目录，沿用 `download` 的“目标目录必须为空”）；`skill download` 删除，旧名字只保留一句迁移提示并以非零退出 | CLI 命令与 help |
| 4 | **覆盖保护**：覆盖已存在文件默认拒绝；`--overwrite` 只在“本机完整读过这一版、且读后内容未变”时放行；没读过或读后变过都拒绝并要求先 `read`；没有 `--force`。放行时先回显将被改动的行数，再执行 | CLI 命令与 help |
| 5 | **`edit` 的输入通道**：① `--old-file -` / `--new-file -` 表示 stdin（两端都写 `-` 时拒绝）；② `--stdin-json` 一次读 `{"old","new","replace_all"?}`；③ 同一格式再收 `{"edits":[...]}`，一次请求落一个版本，任一处匹配不上则整批不落盘并指出第几处 | CLI 命令与 help |
| 6 | **裸读 `SKILL.md` 拦截**：裸地址、未 `invoke`、路径是 `SKILL.md` 时，`read` / `cat` / `head` / `tail` 一律拒绝（退出 2），提示先 `skmr skill invoke <地址>`；地址里写了版本或本机已 `invoke` 时照常读 | CLI 行为与 help |
| 7 | **建草稿前先看已有草稿**：`draft create` 与 `skill submit` 在“我自己在这个 skill 下还有未上线草稿”时默认拒绝，列出草稿码、名称与下一步；`--new` 才新建，且 `--new` 不写进 help、只在报错里出现。只算自己的草稿，协作者的不算；控制台不受此限 | CLI 行为与 help |
| 8 | **文档与生成物同步**：`docs/current/cli.md`、`docs/current/schema.md` 重出；`skills/skillmaster/SKILL.md` 的 `read` / `invoke` 分工写明“SKILL.md 裸读会被拒绝”；旧动词的迁移提示写进 help | 生成物 + 仓库内技能正文 |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 不改控制台界面与设计件 | 控制台的「新建版本」仍按“第一次改动落草稿”工作，本版不改它的交互；要让控制台也先建空草稿，得先出设计稿 | 产品负责人提出时 |
| 2 | 不保留 `put` / `download` 的别名 | 2026-11-04 定“硬删 + 迁移提示”：保留别名会让“两个入口都行”的混淆多活一版，而这正是本版要消除的 | 不适用 |
| 3 | 不拦 `SKILL.md` 以外的文件裸读 | 读 `README.md`、读某个素材不构成“加载这个 skill”；拦了会误伤只想看看的人 | 出现新的“正文级”文件名时 |
| 4 | 不让 `draft create` 自动复用已有草稿 | 本版的选择是“拒绝 + 列出 + `--new`”，由人决定接着改还是新开；自动复用会把“我想新开”也压掉 | 「不重复造草稿」定案时 |
| 5 | 不改 `submit` 的语义与已上线版本的内容寻址 | 草稿可以有相同内容，但提交/上线时的冲突检查与幂等规则不变 | 另开版本 |
| 6 | 不更新远程官方指南 `skillmaster/skillmaster-guide` | 指南是线上技能，不在本仓库 PR 的交付面里；CLI 发布后单独改 | 本版 CLI 上线后 |
| 7 | 不做交互式编辑器或 TUI | 只加输入通道，不改变“命令 + 参数”的形态 | 不适用 |
| 8 | 不硬拦“整版取回后再整份写回” | 整份重写是合法路径；要硬拦只能禁止覆盖已有文件，或拍一个 diff 阈值，代价都更大。降低动机靠 `edit` 的 stdin / 批量与删除 `download` | 出现大量“整版取回回写”的真实事故时 |
| 9 | 不让写命令省略 `@<版本码>` | 版本码是草稿的身份；同一 skill 多份草稿是常态，省略后只能靠“钉住的”或“最新的”去猜，两条都会静默写错。token 成本可忽略（一条命令多 4–8 个 token） | 不适用 |
| 10 | 不在控制台加同样的“已有草稿”拦截 | 网页上点“新建版本”是人的明确动作，不是 agent 的循环；加拦截会改变已交付的界面行为，得先出设计稿 | 产品负责人提出时 |

## 控制台操作与 CLI 对应

本版**没有新增或改动控制台操作**。CLI 新增的 `draft create` 是控制台没有的独立动作（控制台仍走“第一次改动落草稿”），这不违反 [ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)——它约束的是“控制台有的，CLI 也要有”，不是反过来。映射表为空，不申请例外。

## 拥有面

| 要动的文件 / 面 | 是不是共享面 | 谁在用 |
|---|---|---|
| `skillmaster-server/.../usecase/CreateDraftFromVersionUseCase.java`、`api/WebDraftController.java`、`modules/version/SkillVersionService.java`、Flyway 迁移 | 是（服务端草稿创建路径） | 控制台编辑器、CLI `@new`（本版后只剩 `draft create`） |
| `skillmaster-cli/cmd/skillmaster/declarations*.go`、`put.go`、`get.go`、`download.go`、`write`/`edit` 解析与 `files_*.go`、`read.go`、`fileviews.go` 及测试 | 是（CLI 命令表与 help） | 本仓库所有 CLI 路径 |
| `docs/current/cli.md`、`docs/current/schema.md` | 是（生成物） | 所有人按它读命令与表结构 |
| `skills/skillmaster/SKILL.md` | 是（平台技能源） | 安装包与所有走 `startup` 的用户 |
| `docs/versions/v44-cli-read-write-contract/`、`docs/decisions/0135–0137` | 否 | 本版 |

## 验收与指标

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | `skmr draft create <地址> --name <名称>` 在已有线上版本的 skill 上建出一份新草稿：返回版本码与一条可直接复制的下一步写命令，`version list --status draft` 能看到它，内容与线上版本逐文件一致 | 建完后 `skill ls` 与线上版本比较文件清单，`skill get` 抽查两个文件逐字节比较；输出里存在带该版本码的写命令 |
| A2 | `--from <版本|版本码>` 能从指定底稿起稿；同一 skill 连建两份内容相同的草稿，两份都存在且版本码不同 | 连跑两次，`version list --status draft` 出现两行；两次的 `version diff` 都为空 |
| A3 | `write` / `edit` / `rm` / `mv` 不再接受 `@new`，退出非零并提示改用 `skmr draft create`；help 里没有 `@new` | 四条命令各跑一次负例；`skmr skill write --help` 等文本里 grep 不到 `@new` |
| A4 | `skmr skill write <地址>@<版本码> <路径> --from <本地文件>` 能写文本与二进制：文本做换行 / BOM / NFC 归一，二进制按原字节保存；多个 `--from` 按 basename 落进目录 | 用一份含 CRLF + BOM 的文本与一份 PNG 各跑一次，`skill get` 取回后与写入期望逐字节比较；多文件一次上传再核对清单 |
| A5 | `skmr skill put` 与 `skmr skill download` 不再执行，输出一句迁移提示（`put` → `write --from`，`download` → `get --all`），退出码非零 | 直接跑两条旧命令，断言退出码与提示里的新命令 |
| A6 | `skmr skill get <地址>@<版本码> <路径>` 行为不变；`skmr skill get <地址>@<版本码> --all --into <空目录>` 写出整版；目标目录非空时拒绝 | 单文件取回、整版取回各一次；非空目录负例 |
| A7 | 覆盖已存在文件：默认拒绝；`--overwrite` 只在“本机完整读过这一版且内容未变”时放行；没读过或读后变过都拒绝并提示先 `read`；没有 `--force`；放行时打印将改动的行数 | 四个用例：未读、读过未变、读过之后远端变过、只 `head` 过不算读过 |
| A8 | `edit` 支持 stdin 两端、`--stdin-json` 单条与 `{"edits":[...]}` 批量；批量中任一处匹配不上时，整批不落盘并报出第几处 | 逐种输入各跑一次；批量里第二处故意写错，`version list` 确认没有新版本 |
| A9 | 裸地址 + 未 `invoke` + `SKILL.md`：`read` / `cat` / `head` / `tail` 全部退出 2，提示先 `invoke`；写了 `@版本` 或先 `invoke` 后照常读 | 四个命令各跑负例；`@版本` 与 `invoke` 后各跑正例 |
| A10 | `docs/current/cli.md` 与声明的命令表一致、`schema.md` 与迁移一致；`skills/skillmaster/SKILL.md` 写明 `SKILL.md` 裸读被拒绝；全仓没有残留的 `skill put` / `skill download` / `@new` 用户指引 | `generate.py --check` 干净；`grep` 用户可见文档（`docs/current/`、`skills/`、`README`、安装文档）里的旧命令，命中只允许出现在迁移提示与历史版本里 |
| A11 | 服务端与 CLI 既有测试全绿；文件行数检查通过 | `skillmaster-server`: `./mvnw verify`；`skillmaster-cli`: `go test ./...`；`node scripts/check-file-size.mjs` |
| A12 | 写命令的版本码不存在时，报错列出该 skill 当前开着的草稿，而不是只给 `not_found` | 用一个不存在的码跑一次，断言错误里有草稿清单或 `version list --status draft` 的指引 |
| A13 | `draft create` 与 `skill submit` 在“我自己在这个 skill 下已有未上线草稿”时退出非零、列出草稿码与下一步，且不新建；报错里写明“先问用户”；`--new` 不在 `--help` 里；非交互终端下加 `--new` 仍能新建；只有协作者的草稿时不拦 | 三个用例：自己有草稿时两条命令各跑一次（断言退出码、输出里的草稿码与“先问用户”、`version list` 没有多出新行）；`--help` grep 不到 `--new`；非 TTY 下 `--new` 能新建；只有协作者草稿时能正常新建 |

**成立条件**：A1（建草稿独立成动作）、A5（旧动词退场）、A8（`edit` 三种输入通道）与 A13（重复草稿拦截）不满足就不算交付——这一版的目的就是换掉词表、让人不再写临时文件，并让 CLI 不再自己攒草稿。

## 依赖与前置

- **决策前置**：ADR 0135 定草稿模型与“已有草稿”拦截（取代 ADR 0064 §5 的半条）；ADR 0136 定写侧词表与覆盖保护；ADR 0137 定 `edit` 的输入格式。三条都先于实现。
- **已有机制**：版本钉存储（ADR 0035 / 0101）是“本机读过”记录的落点；具体记录形状在第 3 关定。
- **数据库**：本版要改 `skill_version` 的唯一约束，需要一条 Flyway 迁移；每个 worktree 一个测试库（`.dev-sop/profile.md` §并行）。
- **外部依赖**：CLI 走 beta 通道；服务端与 CLI 同版发布，不需要控制台同步改动。
- **排除了什么前置**：不让 `draft create` 依赖“不重复造草稿”的结论；也不依赖账号、命名空间相关的能力。
