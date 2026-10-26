# v17-cli-startup · 测试方案

> **已被 [v20](../v20-guidance-on-platform/README.md) 部分取代**（[ADR 0096](../../decisions/0096-guidance-lives-on-the-platform.md)）：本文里「旅程正文走匿名 `/gateway/{file}` 路由」「规则是四条、由 `help usage` 携带」这些说法都是**当时**的形状。今天旅程是平台上的 `skillmaster/startup` 技能、规则是它 `steps/03-prompt.md` 里的三段，两者都要凭据。本文件保留为 v17 的需求记录，不再逐句改写。

## 验收映射

| prd 验收 | 用例 |
|---|---|
| 1 干净机器可安装，缺凭据时 `startup` 不假装成功 | T01 |
| 2 校验和一致 | T02 |
| 3 安装脚本选对包、认不出来时拒绝安装 | T03 |
| 4 有浏览器的机器上走完登录并打印旅程，全程零副本 | T04 |
| 5 登录后 `auth status` 说得清身份 | T05 |
| 6 不带 `--yes` 只列清单与命令、不写服务器 | T06（作废） |
| 7 `--dry-run` 只列不搬 | T07（作废） |
| 8 `--all` 一次搬完 | T08（作废） |
| 9 单条失败不中止、逐条报告 | T09（作废） |
| 10 同名冲突一次报全、`--skip-existing` 能续跑 | T10（作废） |
| 11 候选目录里的每条都带来源标签 | T11（作废） |
| 12 去重合并的条目带全部来源标签 | T12、T12b（作废） |
| 13 表外目录不打标签、`--tag` 覆盖默认 | T13（作废） |
| 14 只认一层、跳过点条目 | T14（作废） |
| 15 指路、核对，缺一条就停 | T15 |
| 16 改提示词前必问、拒绝则文件不变 | T16 |
| 17 只改被指定的文件 | T17 |
| 18 幂等、且是 `steps/03-prompt.md` 那段的快照（v20 取代 `help usage`） | T18 |
| 19 可回退（`.bak` 且带起止标记） | T19 |
| 20 验证三件事都成立，任一不成立就停 | T20 |
| 21 不带 `--yes` 只列清单、本机文件不动 | T21（作废） |
| 22 先打包、包在 `~/.skillmaster`、可解压恢复 | T22（作废） |
| 23 有草稿就整批不删 | T23（作废） |
| 24 逐条报告、单条失败不中止 | T24（作废） |
| 25 删除之后技能目录里只剩该留的 | T25（作废） |
| 26 `invoke` 报出的权限与实测一致（P4，未交付） | T26（未交付，不可执行） |
| 27 标签范围默认不限定，选了才写 | T27 |
| 28 任何一步被拒绝或没通过都停，且说清三件事 | T28 |
| 29 `startup` 打印「你现在在哪」 | T29 |

**有一条要说在前面**：验收 16–19 约束的是 **agent 的行为**（先问再改、只改一个文件），而 agent 是模型。这类断言无法用单元测试证明，只能靠（a）正文里的强制措辞 +（b）一次真实 agent 的走查记录。它们标为**手工走查**，不假装有自动化覆盖——v16 的 T25 是「未验证」，两者的区别是**这一条真的会有人跑一遍并留下记录**，而不是等一个还没写的 eval。

**每条用例里「服务端对象数」怎么数**：`skillmaster skills list --show all --all`。**不能用默认的 `skills list`**——它只列 live，而 import 提交的全是草稿，拿它数出来的永远是 0，断言会恒真（T06、T10 都会被它骗过去）。

## 用例

### T01 · 干净机器安装，缺凭据时不假装成功
- **前置**：一个没有 skillmaster 的容器（`ubuntu:24.04` + curl），不带任何凭据、没有浏览器
- **步骤**：`curl` 打开指令里那个地址，照说明页走完安装；然后跑 `skillmaster --version` 与 `skillmaster startup`
- **期望**：说明页 200、匿名可读，且列出六个包的产物名，并说明用 `checksums.txt` 核对（页面本身不嵌校验和：它是静态页，嵌进去必然过期）；`--version` 输出 `skillmaster 1.0.0 (API v1)`，退出码 0；`startup` **退出码非 0**，输出说清两条路（有浏览器就去那一页登录或注册；已有账号可设 `SKILLMASTER_API_KEY`），并且**没有**打印任何旅程正文；技能目录里没有多出任何条目

### T02 · 校验和与清单完整
- **步骤**：从 `https://dl.skillmasterai.com/` 取六个包与 `checksums.txt`，逐个 `shasum -a 256`
- **期望**：六个包都在，`checksums.txt` 里六行齐全且逐个一致；两个安装脚本也在，且不在校验和文件里（脚本是取用入口，不是被校验的对象）

### T03 · 安装脚本选对包
- **步骤**：在 macOS/arm64 与 Linux/x86_64 上分别跑 `install.sh`；再用一个假的 `uname` 让架构不可识别跑一次
- **期望**：前两次分别装到 `darwin_arm64` 与 `linux_amd64`，各自核对过 SHA-256 且 `--version` 可执行；第三次**不下载、不写文件**，退出码非 0，说明里给出产物清单的地址
- **未覆盖**：Windows 的 `install.ps1` 两种架构**没有实测环境**，见 §已知问题

### T04 · 有浏览器的机器上走完登录，跑完本机零副本
- **前置**：一台有图形浏览器的机器；`HOME=$TMP/home`，Linux 上再加 `XDG_CONFIG_HOME=$TMP/home/.config`（`config.Dir` 取 `os.UserConfigDir()`，它在 Linux 上先看 `XDG_CONFIG_HOME`；`local.Scan` 扫 `$HOME` 下的候选表。**没有** `SKILLMASTER_CONFIG` 这个变量）
- **步骤**：`skillmaster startup` → 在打开的页面注册一个新账号 → 回到终端；随后跑 `skillmaster skill read skillmaster/startup steps/03-prompt.md`（要联网）；最后断网跑 `skillmaster help`（本地命令，断网可用）
- **期望**：注册并登录后 `startup` **先打印一行现场状态**（空库 + 空技能目录 →「下一步：搬运」，**v20 起不带步号**），再打印 `skillmaster/startup` 的正文；正文按现场状态指到第一步（`steps/01-import.md`），每一步自己做完了才指下一步，收尾说清三件事：搬上去的是草稿、去控制台批量上线、写提示词并验证通过之后再用 `skills retire` 下线本机副本（先备份）。`steps/03-prompt.md` 里带 `<!-- skillmaster:begin -->` 的可粘贴块（v20 取代 `help usage`）；`help` 的命令表里**没有 `setup`**（也没有 `usage` 主题）；跑完**技能目录仍然为空**

### T05 · 登录后身份可读
- **步骤**：`skillmaster login` 后 `skillmaster auth status`
- **期望**：输出含 handle，退出码 0

### T06 · 不带 `--yes` 只列清单 — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **前置**：本机 3 个 skill 目录；服务端账号为空库
- **步骤**：`skills import <dir>`
- **期望**：退出码 0（这是答案，不是拒绝）；stdout 列出将被创建的 3 条，并给出一条带 `--yes` 的完整命令；`skills list --show all --all` 条数为 **0**

### T07 · `--dry-run` — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **步骤**：`skills import <dir> --dry-run`
- **期望**：打印将被创建的名单；`skills list --show all --all` 条数不变

### T08 · `--all` — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **前置**：33 个目录（真实样本）
- **步骤**：`skills import ~/.claude/skills --all --yes`
- **期望**：退出码 0；`skills list --show all --all` 条数为 **33**；总耗时打印出来；收尾打印**一行**控制台「待上线」页地址

### T09 · 单条失败不中止 — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **前置**：两个正常目录 + 一个 `SKILL.md` 权限为 `000` 的目录
- **步骤**：`skills import <dir> --all --yes`
- **期望**：两个成功、一个失败；末尾汇总「2 成功 / 1 失败」并列出失败目录与原因；退出码非 0
- **注**：**没有 `SKILL.md` 的目录不算失败**，它根本不构成一个 skill（见 T14），不进汇总

### T10 · 同名冲突 — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **前置**：两批目录，其中两条与平台上已有的 skill 同名
- **步骤**：先 `skills import <dir> --yes` 搬一批；再对整批跑一次（不带 `--skip-existing`）；最后带 `--skip-existing` 跑
- **期望**：第二次**一次列出全部**冲突的名字（不是撞第一个就停），退出码非 0，`skills list --show all --all` 条数不变；第三次冲突的跳过并计入汇总，其它照常搬完

### T11 · 来源标签 — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **前置**：一个只存在于候选目录里的 skill（如临时 `HOME` 下的 `~/.claude/skills/demo-a`）
- **步骤**：`skills import --all --yes` 后读回该 skill
- **期望**：标签里含 `agent:claude`，且没有别的 `agent:*`

### T12 · 合并取并集 — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **前置**：同一份内容（同名、内容指纹相同）同时放在 `$TMP/home/.agents/skills/x` 与 `$TMP/home/.claude/skills/x`
- **步骤**：`skills import --all --yes`
- **期望**：只产生一条 skill；标签同时含 `agent:common` 与 `agent:claude`

### T12b · 同名不同内容不搬、如实报告
- **前置**：`x` 在两个候选目录里同名但内容不同
- **步骤**：`skills import --all --yes`
- **期望**：`x` 不产生任何对象；汇总里有一条「需人工决定」，带上两处路径；其余条目照常搬完

### T13 · 表外目录与 `--tag` — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **步骤**：分别跑 `skills import /tmp/outside --yes` 与 `skills import /tmp/outside --tag work --yes`
- **期望**：前者搬上去的 skill 没有任何 `agent:*` 标签；后者只有 `work`

### T14 · 只认一层、跳过点目录 — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **前置**：一个临时 `HOME`，其中 `.claude/skills/coding/` 内置 10 个子 `SKILL.md`（从真实样本复制），`.codex/skills/.system/skill-creator/` 存在
- **步骤**：`skills import --dry-run --all`
- **期望**：清单里 `coding` 只出现一次（不是 11 次）；`.system` 下的 skill 一条都不出现

### T15 · 指路、核对、缺一条就停
- **前置**：已登录、库为空；本机 3 个 skill 目录
- **步骤**：逐个 `skill submit <dir>` 搬 3 条（`skills import` 已砍）；随后 `skillmaster startup`；然后在控制台只上线其中 2 条，跑 `skills pending`
- **期望**：`startup` 正文打印控制台「待上线」页地址，并说明「这些还是草稿，上线之后才可用」；只上线 2 条之后 `skills pending` 仍列出剩下那 1 条，旅程停在这一步，后面的步骤一条都不做

### T16 · 拒绝时文件不变（**手工走查**）
- **前置**：一个带 `CLAUDE.md` 的测试工程
- **步骤**：让 agent 按 `skillmaster/startup` 的 `steps/03-prompt.md` 走，在询问处回答「不改」
- **期望**：文件 `shasum` 前后一致，且旅程停下

### T17 · 只改被指定的文件（**手工走查**）
- **步骤**：工程里同时放 `CLAUDE.md`、`AGENTS.md`、`README.md`，只指定 `CLAUDE.md`
- **期望**：只有 `CLAUDE.md` 变了，另两个 `shasum` 不变

### T18 · 幂等，且是 `steps/03-prompt.md` 那段的快照（**手工走查**）
- **步骤**：同意一次；再走一遍同意一次
- **期望**：写入的那段与 `skillmaster/startup` 的 `steps/03-prompt.md` 里那段**逐字一致**（v20 取代 `help usage`）；除 `--scope public` 外一个参数名都没有；标记段仍只有一份

### T19 · 可回退（**手工走查**）
- **步骤**：查看同目录下的备份与新写入的内容
- **期望**：原文件留了一份 `.bak`（带时间戳），**并且**写入内容自带 `<!-- skillmaster:begin/end -->`

### T20 · 验证新开关真的能用（只读）
- **前置**：T15 的核对通过（`skills pending` 为空）；提示词已按 T16–T19 写入
- **步骤**：先看提示词里标记段的份数与内容；再跑 `skillmaster skills list`；再用 `skillmaster skill invoke <其中一个地址>`
- **期望**：三件事同时成立——标记段只有一份且与 `steps/03-prompt.md` 里那段逐字一致（v20 起不再是 `help usage` 末段）；`skills list` 列出已上线的那些、条数与上线数一致；`invoke` 取到正文。**任一不成立就停**：本机副本一条不动，并给出 `mv` 回 `.bak` 的退回办法

### T21 · `retire` 不带 `--yes` — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **前置**：一批已导入的 skill，其中至少一条在平台 live
- **步骤**：`skills retire <dir>`
- **期望**：退出码 0；stdout 列出将被删的清单与带 `--yes` 的完整命令；技能目录里所有文件的 `shasum` 与文件数不变；`~/.skillmaster/` 没有新增文件

### T22 · 备份包 — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **前置**：同 T21，删除前先记下每个文件的 `shasum`
- **步骤**：`skills retire <dir> --yes`；检查 `~/.skillmaster/`
- **期望**：出现 `skills-backup-<时间戳>.zip`；`unzip -l` 里每一份扫描到的 skill 都在，包内路径是本机绝对路径去根（如 `Users/<名>/.claude/skills/coding/SKILL.md`）；`unzip -d <tmp>` 后逐文件 `shasum` 与删除前一致；汇总打印了包的路径与大小

### T23 · 有草稿就整批不删 — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **前置**：一批里一半已在平台 live、一半仍是草稿；另加一个平台上根本没有的同名本地 skill
- **步骤**：`skills retire <dir> --yes`
- **期望**：**一条都不删**，退出码非 0；汇总里列出还没上线的那几条，并说明「全部上线之后再跑一次」；平台上没有的那条**不算阻塞**，单独列出（它留着）；备份 zip 照样生成

### T24 · 逐条报告、单条失败不中止 — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **前置**：两个 live 的 skill + 一个删不掉的目录（权限 `000`）
- **步骤**：`skills retire <dir> --yes`
- **期望**：两个删掉、一个失败；汇总里成功 / 保留 / 失败三档齐全，失败那条带原因；退出码非 0

### T25 · 删除后目录干净 — **作废**

- **本用例作废**（2026-10-26 产品负责人定）：`skills import` / `skills retire` 都不做，见 [`prd.md`](prd.md) 的「不做」表。用例文本留在这里，将来真做时不必重新推导。
- **前置**：候选目录里有未上线的 skill、不是 skill 的文件（如 `README.md`）、`.` 开头的条目（如 `.system`）
- **步骤**：`skills retire <dir> --yes`
- **期望**：只删了 live 的 skill 目录；其余条目一个不少；被删的条目不残留空壳

### T26 · `invoke` 报出权限（**P4，未交付**）
- **状态**：`invoke` 的权限行与 `--json` 属于 P4，尚未实现（PRD 做 #8 标着「P4，尚未交付」）。本用例在 P4 交付前跑不出期望，照着跑会误报缺陷。
- **前置**：两个账号看同一个 skill——一个只有 `invoke` 档，一个有 `browse`
- **步骤**：两边各跑 `skill invoke`，看 stderr；再各跑 `skill files`
- **期望**：`invoke` 档那行说「可调用、可读文件；不能列目录、不能写」，`files` 回 403；`browse` 那行改口说「可列目录」，`files` 成功。两种情况 `skill read` 正文点名的文件都成功

### T27 · 标签范围
- **前置**：库里至少有一个带 `agent:claude`、一个只带 `agent:common`、一个带 `work` 的 skill
- **步骤**：先走一遍写提示词那一步（`steps/03-prompt.md`）、在那一问上跳过；再看提示词；然后重跑并选「限定」，挑 `agent:claude` 与 `agent:common`，看提示词与 `skills list --my-tag`
- **期望**：第一次提示词里**没有任何**「只使用带…标签」的行，`skills list` 列出全部三条；第二次那一行出现，那条只带 `work` 的 skill 不再出现在列表里

### T28 · 每一步被拒绝或没通过都停下
- **前置**：一个已登录的账号与本机样本；每一步都准备好人可以回答「不」
- **步骤**：分别在搬运、上线、写提示词、验证、下线五处各拒绝/失败一次
- **期望**：每一次都不执行后面的步骤；停止处都写明三件事——已完成到哪、还剩什么与为什么不能往下走、怎么回来（具体命令）。只说「已停止」或直接报错都不算。**验证失败那次尤其要验**：本机副本一条不动，且给出 `mv` 回 `.bak` 的退回办法

### T29 · 「你现在在哪」
- **前置**：依次造出四种状态：空库空目录、搬完未上线、全部上线且本机仍有副本、全部上线且本机已无副本
- **步骤**：每种状态跑一次 `skillmaster startup`
- **期望**：四种状态分别打印「下一步：搬运。」「下一步：上线。」「下一步：写提示词、验证，然后下线。」「已完成：平台可用，本机没有副本。」（**v20 起不带步号**）；**空库空目录与「本机与库里都空」都算「下一步：搬运」**——库里没有 live 的 skill 就不算完成（prd §验收 29）；数字与服务端、本机实际一致；断网时这一行**不打印**，而且**正文也取不到**（正文不再是匿名的，v20 / ADR 0096），`startup` 整体失败

## 环境与数据

- **服务端**：本地 PostgreSQL + `skillmaster-server/mvnw spring-boot:run`（服务端是 Java/Spring；`go run ./cmd/skillmaster` 跑的是**客户端**），或直接用生产（读操作用生产，写操作用本地）
- **账号**：本地服务端注册一个测试账号；`HOME=$TMP/home`（Linux 加 `XDG_CONFIG_HOME=$TMP/home/.config`）隔开真实凭据与本机 skill，避免污染真实环境
- **样本**：复制 `~/.claude/skills` 的一个子集到临时目录（至少含一个多文件 skill，如 `coding` 22 个文件），外加人为造的两个样本：一个 `SKILL.md` 权限 `000`（T09 用），一个缺 `SKILL.md` 的目录（T14 用，它**不该**算一条）
- **计数**：一律用 `skills list --show all --all`——默认 `skills list` 只列 live，量不出草稿
- **本机备份**：下线用例把 `HOME` 指到临时目录（`HOME=$TMP/home`），`~/.skillmaster/` 因此也落在临时目录里，不碰真实的 home
- **live 与草稿的混合**（T23、T24 需要）：导入之后在控制台把其中一部分点上线（或走浏览器面的 `/web/skills/{ns}/{name}/publish`）；CLI 的 `version publish` 不执行发布（ADR 0031），不能拿来造数据
- **清理**：每个用例结束删临时目录；服务端的测试账号用一次性账号，不删数据

## 结果

尚未执行——本版仍处于定义阶段。

| 用例 | 结果 | 证据 |
|---|---|---|
| T01–T29 | 未执行 | — |
| T16–T19（手工走查） | 未执行 | — |

## 已知问题

（实现后填写。设计阶段已知的两条：）

- **T16–T19 无法自动化**，只能靠正文的强制措辞加一次真实 agent 的走查记录（见上面的说明）。
- **Windows 一个都没验过。** `windows/amd64` 与 `windows/arm64` 能交叉编译出来（2026-10-25 实测，12 MB / 11 MB），但 `install.ps1` 的 PATH 写入、`.zip` 解包、以及在 Windows 上的首次登录，**都没有在真实 Windows 上跑过**。在没有 Windows 机器之前，这一条标「未验证」，不写成「支持」。
