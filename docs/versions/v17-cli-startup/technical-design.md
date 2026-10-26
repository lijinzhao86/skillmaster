# v17-cli-startup · 技术设计

## 架构

七段，彼此只通过既有的契约相连：

```
⓪ 入口           首页「安装指令」卡片里的一句话（v15 已有那一屏，只换文案）
                  「根据 https://www.skillmasterai.com/install/skillmaster.md 开始 SkillMaster 的旅程。」
                        │
① 安装说明        站点静态文件 install/skillmaster.md（匿名，随站点部署）
                  agent 照它：按平台取产物、核对 SHA-256、放进 PATH、--version、startup
                        │
② 分发           阿里云 OSS → https://dl.skillmasterai.com/
                  六个产物 + checksums.txt + 两个安装脚本（tag cli/vX.Y.Z 触发）
                        │
③ 旅程正文       gateway/skillmaster/STARTUP.md ──► GET /gateway/STARTUP.md（匿名）
                 正文讲的是登录之后的三步             ↑ 发布时把 <host> 换成真实地址
                        │
④ 旅程           skillmaster startup ──► 没有凭据就先开登录页 ──► 打印正文
                        │                  （新用户在那页注册）
                        ├── 第 2 步 skillmaster skills import …        ← 新命令
                        ├── 第 3 步 人去控制台批量上线（v18 已有那一页）
                        │        └─ skills pending 为空才算完成（只读核对）
                        ├── 第 4 步 写入四条稳定规则（正文规定，agent 执行）
                        ├── 第 5 步 验证新开关真的能用（list + invoke，只读）
                        └── 第 6 步 skillmaster skills retire …        ← 新命令
                 任何一步被拒绝或没通过 → 停在那里，说清 已完成 / 还剩 / 怎么回来
                        │
⑤ 日常取用       skillmaster help usage ──► 四条规则（唯一来源）
                 旅程第三步把末尾那段快照写进系统提示词；参数与输出仍走 `help <命令>`
                        │
⑥ 服务端         既有：/api/v1（写）、/web（浏览器）、/gateway（匿名引导）
                 本版新增：一条匿名路由 /gateway/STARTUP.md
```

**为什么安装说明在站点、旅程正文在服务端**：安装说明要在**还没有 CLI** 的时候被读到，它属于公开文档，随站点发版最直接；旅程正文是 `startup` 打印给 agent 的、与 CLI 版本对齐的协议文本，随服务端走。两者都不编进二进制——理由和网关 skill 一样：改一次对所有人生效。

**为什么旅程是一份托管文档而不是编进客户端**：它会随着 agent 生态变化（新的客户端、新的目录约定），托管在服务端就能改一次对所有人生效，不需要发新版二进制。

**为什么本机一个 skill 都不装**：产品卖的就是「skill 放云端、按需取用」，再往技能目录放一个引导用的 skill 自相矛盾（[ADR 0089](../../decisions/0089-no-local-skills.md)）。代价是触发从「常驻 frontmatter」退化为系统提示词里的三句话——那三句因此是全项目最需要打磨的一段文字。

**为什么任何一步被拒绝都停下**：五步是一条链，不是菜单。跳过搬运，就没有草稿可上线、没有副本可下线，往系统提示词里写「以后都查 SkillMaster」等于让 agent 去看一个空库——旅程会显示「走完了」，而它的目标正是人刚刚拒绝的那件事。所以每个「否」都通向一个**停**，停的时候说清三件事（已完成到哪、还剩什么与为什么不能往下走、怎么回来），而不是「跳过继续」。这不是把拒绝当失败：停止是合法的结果，只是它不是完成。

**为什么下线排在批量上线之后**：搬上去的全是草稿，`skills list` 默认只列 live——在有人上线之前撤掉本机副本，用户手上就零可用副本。批量上线（v18）把「让平台可用」从几十次点击变成一次操作，所以下线可以回到旅程里，但**位置必须在它之后**（[ADR 0092](../../decisions/0092-retire-local-copies-only-after-they-are-live.md)）。

**为什么先写提示词、验证，最后才下线**：提示词是「以后都从平台取」的开关，下线是拆掉旧副本。**先切换、验证新路径能用，再拆旧的**，是唯一不会把人逼进死角的顺序：验证没通过时本机副本原封不动，提示词还能 `.bak` 退回；反过来先下线，一旦提示词写错或规则跑不通，人手上零副本、agent 也不认识平台，只能从备份包往回捞。所以旅程把**验证**单独做成一道闸（验收 20），而它验的是三件事：标记段只有一份且与 `help usage` 逐字一致、`skills list` 列得到、`skill invoke` 取得到。

**为什么下线要「核对 + 备份」两道闸**：`skills retire` 逐个向服务端确认同名的 skill 是否 live，只删已 live 的；核不上（还是草稿）的一律保留。删除之前先把本次扫描到的全部本机 skill 打成一个 zip 放进 `~/.skillmaster/`——核对或删除中途失败时，包里仍是完整的原始状态。**不做指针化**：那要求平台侧有可离线随取的本地缓存，是另一个产品形态。

**为什么第三步由 agent 执行而不是 CLI**：改的是各客户端的提示词文件（`CLAUDE.md` / `AGENTS.md` / 其他），位置随 agent 而异，而产物只是文本。但**规则不能留给临场发挥**：起止标记、`.bak`、只改人指定的那一个文件，这三条由 `STARTUP.md` 规定死（开放问题 2）。

**正文收尾必须说清三件事**，否则用户会以为旅程走完就能用了：① 搬上去的都是**草稿**，要进控制台的「待上线」页批量上线（给出地址）；② **在你上线之前，agent 从库里看不到它们**；③ 写提示词并验证通过之后，才跑 `skillmaster skills retire` 下线本机副本——它**先打包备份、再只删已 live 的，还有草稿就整批不删**。这三句是旅程最后一步的存在理由，不能省成一句「完成」（PRD 验收 4、15、20 也据此判定）。

## 能力与流程的分界

**CLI 管基础能力，官方文档管整个安装流程。** 判据只有一条：**这件事是需要和人对话，还是确定性计算？** 前者的家是旅程正文（`STARTUP.md`，托管在服务端、agent 直接读，不装本机——[ADR 0089](../../decisions/0089-no-local-skills.md)），后者的家是 CLI（它是可测的、可复现的、错了会红的那一半）。

| 归 CLI（能力） | 归旅程正文（流程） |
|---|---|
| 登录与凭据、`auth status` | 旅程的顺序、每一步先问人 |
| 扫描候选目录、按名字去重、来源标签 | 每一步被拒绝后的「已完成 / 还剩 / 怎么回来」 |
| `import` 的 `--yes` / `--dry-run` / `--skip-existing` / 冲突一次列全 | 提示词怎么写（起止标记、`.bak`、只改人指定的那一个文件） |
| `skills pending`（只读） | 第五步验证：跑哪几条命令、三件都通过才算过、不通过怎么退（**由 agent 执行，不新增命令**） |
| `retire`：核对 live、有草稿整批拒绝、打包、删除、三档汇总 | 完成判定与日常用法 |
| `startup`：确保登录、打印正文与「你现在在哪」 | 「停不是失败，只是不是完成」这句叙事 |

**为什么验证不写成命令**（2026-10-25 产品负责人定）：那三件事里有两件本身就要跑已有命令（`skills list` / `skill invoke`），第三件是在 agent 刚写下的文件里找标记段——加一条只为「核对 agent 自己写的东西」存在的命令，换不来安全，只是多一个面。**但规则要写死**：验哪三件、全过才放行、不通过时本机副本一条不动且用 `.bak` 退回，这三条由正文规定，不留给临场发挥。

**为什么流程不写进 CLI**：流程里每一步都要先问人、要把「为什么现在不能往下走」讲清楚——这是语言工作，不是分支判断。把它编进命令，等于让命令替人做决定（「没上线？那我顺手帮你上线」），而那正是 [ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md) 与 [ADR 0090](../../decisions/0090-batch-publish-confirms-once.md) 划开的边界。

## 数据模型

**没有新表。** 搬运走的是既有的建 skill / 写文件 / 提交草稿三条路径，产物是普通的 skill 与草稿版本；下线只删本机目录，不碰服务端。

**客户端只多一个备份目录。** `~/.skillmaster/` 放 `skills-backup-<时间戳>.zip`（[ADR 0092](../../decisions/0092-retire-local-copies-only-after-they-are-live.md)）：

- 由 `skills retire --yes` 写，**CLI 不读它**——恢复是 `unzip -d / ~/.skillmaster/skills-backup-….zip`，包内路径是本机绝对路径去掉开头的 `/`。
- 除了它，客户端不落任何东西：没有「上次搬到哪」的清单、没有本地状态文件；`import` 与 `retire` 每次重新扫描，续跑靠 `--skip-existing` 与服务端的 live 状态。

## 接口

### 发布产物

**权威在 [`skillmaster-cli/RELEASE.md`](../../../skillmaster-cli/RELEASE.md)**：版本编号、发哪些包与凭什么、构建参数、OSS 坐标与上传顺序、两个安装脚本的行为，全部写在那里。这里只留本版实现时才需要知道的一句：

本版由 `.github/workflows/cli-release.yml` 在 `cli/v*` 正式版 tag 上产出六个包、`checksums.txt` 与 `install.sh` / `install.ps1`，传上 `dl.skillmasterai.com`。**流水线需要一组只能写这个 bucket 的 OSS 凭据**（RAM 用户 + 最小权限策略），存在 GitHub Secrets 里。

### 安装说明页（站点静态文件）

| 文件 | 地址 | 内容 |
|---|---|---|
| `skillmaster-site/public/install/skillmaster.md` | `https://www.skillmasterai.com/install/skillmaster.md` | 六个包的产物名、下载与校验和、放进 PATH、`--version` 自检、最后一步 `skillmaster startup` |

**用 `www.` 直连**：`skillmasterai.com` 的 server block 是一条 301 到 www 的规则，写顶点域名会让 agent 多走一次跳转——有的 fetch 不跟。

**它是纯静态文件，不加服务端路由、不改 nginx**。站点工作流已经 rsync 整个 `dist/`，这个文件跟着走。

### 服务端

| 方法 | 路径 | 鉴权 | 作用 |
|---|---|---|---|
| ~~`GET`~~ | ~~`/gateway/STARTUP.md`~~ | ~~匿名~~ | **已由 [v20](../v20-guidance-on-platform/technical-design.md) 退休**（ADR 0096）：旅程正文是平台上的 `skillmaster/startup` 技能，由 `startup` 加载 |

这条路由与既有的 `/gateway/SKILL.md` 同一通道、同一替换规则。正文源文件 `gateway/skillmaster/STARTUP.md` 打进镜像的 classpath；发布时把 `<host>` 占位替换成 `SKILLMASTER_PUBLIC_BASE_URL`。**不加新表、不加新配置键。**

`SKILL.md` 与 `setup` 本版**删除**（ADR 0089）；它的正文压缩成**四条稳定规则**，写进用户的系统提示词——只写行为，参数只留 `--no-login` 一个（ADR 0094）。

**删除的范围**（[ADR 0093](../../decisions/0093-no-skill-discovery-index.md)）：`/gateway/SKILL.md`、两条 `/.well-known` 发现索引、三个 `/.well-known/{gateway,skills,agent-skills}/skillmaster/*` 别名，以及发布它们的那套机制（`GatewayService`、`GatewayIndex`、`PublishGatewaySkillUseCase`、启动自举与 `skillmaster.gateway` 配置块）。`/.well-known/oauth-authorization-server` **不动**——它属于授权服务器，走另一条链。保留 `skillmaster` 保留命名空间与种子迁移。

**`invoke` 的响应多两个头**：`GET …/body` 加 `X-SkillMaster-Can-Browse` 与 `X-SkillMaster-Can-Write`（`true` / `false`），由服务端按同一个 `Access#atLeast` 谓词算。**为什么是响应头而不是响应体字段**：`/body` 返回的是 `ResponseEntity<byte[]>`——作者写下的原样字节，`SkillsController` 的注释写明重新编码会破坏 ADR 0005 的 digest。往那个响应里塞 JSON 字段要么改坏字节，要么要求调用方重新解析；响应头不碰字节。**为什么要服务端算**：客户端只知道凭据的 scope（`skills:read` / `skills:write`），不知道账户在这个 skill 上的档位（`invoke` / `browse` / `write` …）——两边各算一次，迟早会出现"它说我能"和"它让我"不一致。detail 里已有的 `canWrite` 是这个先例：同一个谓词、同一个「由服务端算」的理由；只是载体从 JSON 字段换成响应头，因为这条路不能带字段。

**顺带补一处帮助**：`skill files --help` 渲染出来有三条失败（`not_found` + 通用的「凭据不可用」「连不上服务端」），**没有「权限不够」**——拿到 403 的 agent 会以为自己敲错了。补一条，指向 `invoke` 打出的那行权限。

### CLI

| 命令 | 形态 | 说明 |
|---|---|---|
| `skillmaster startup` | **先确保登录**（没有凭据就驱动 `login`），再打印一行「你现在在哪」+ 加载 `skillmaster/startup` 的正文（**v20 取代**：原先是取 `/gateway/STARTUP.md`） | 那一行由本机扫描（候选目录里的 skill 数）与两次只读查询（`skills pending` 的草稿数、`skills list` 的线上数）拼成，并据此给出下一步（搬运 / 上线 / 写提示词并验证后下线 / 已完成）；**状态取不到时这一行不打印**——猜一个状态比不打印更糟；**v20 起这一行不再写步号**（编号只在技能里），而且**正文也不再是匿名的**：断网或凭据不被接受时正文同样取不到，`startup` 整体失败（ADR 0096） |
| ~~`skillmaster setup`~~ | **本版删除** | 连同网关 skill（ADR 0089） |
| `skillmaster skill invoke` | 正文仍走 stdout；权限一行走 **stderr**（如「这一版：可调用、可读文件；不能列目录、不能写」），读的是响应头；`--json` 里带两个布尔 | v16 的「正文 stdout、诊断 stderr」约定 |
| ~~`skillmaster help usage`~~ | **已由 [v20](../v20-guidance-on-platform/technical-design.md) 退休**（ADR 0096） | 规则段写在 `skillmaster/startup` 的 `steps/03-prompt.md` 里；`help` 只讲命令 |
| 旅程第四步的追问 | 改提示词之前问「这个 agent 只使用哪些标签的 skill」，**默认不限定**（整行不写） | 答案写成提示词里的可选一行「只使用带 X、Y 标签的 skill。」；不限定就整行不写。**CLI 不改**，`skills list --my-tag` 已够 |
| 旅程第四步的落点 | **按 agent 选用户级指令文件**；表在 [`docs/research/agent-context-loading.md`](../../../docs/research/agent-context-loading.md)，表里没有就问人 | 没有全 agent 共用的文件名（2026-10-25 调研）；`AGENTS.md` 覆盖面最广但会被 `CLAUDE.md` / `.rules` 盖掉，所以「写完回读」是验收 18 的一部分 |
| `skillmaster skills import [<目录>…]` | 列清单 → 不带 `--yes` 就退出 → 带 `--yes` 才逐个创建、写文件、提交草稿 | `--dry-run` 只列；`--yes` 是唯一的同意方式（见下）；`--all` 不逐条问，但仍需 `--yes`；`--skip-existing` 跳过同名冲突并计入汇总；`--name <版本名>` 指定草稿版本名（默认 `0.1.0`，**这是版本名，不是 CLI 版本**）；`--tag <a,b>` 覆盖默认的来源标签（默认见下表） |
| `skillmaster skills retire [<目录>…]` | 扫描（候选表或给定目录）→ 逐条问服务端是否 live → 列清单 → 不带 `--yes` 就退出 → 带 `--yes` 才「打包 → 删已 live 的」 | `--dry-run` 只列；`--yes` 是唯一的同意方式；没有 `--all`——这里没有逐条询问，同意只有一次 |

`startup`、`skills import` 两条新命令与 `help usage` 一个主题都进声明（v16 的自述体系），`--help` 与实现由 `render_conformance_test` / `flagattribution_test` 守着。

**同意只能用 `--yes`，不能用 stdin**：agent 读不到终端输入，"在确认处回答否"是一道它跨不过去的门。照仓库既有形状（`version discard`、`skill join` 同款，`declarations_other.go:247` 原话是「没有 --yes 时只打印后果与完整命令；带 --yes 才执行」）：不带 `--yes` → 打印将被创建的清单 + 它自己会跑的那条完整命令 + 退出码 0（这是答案，不是拒绝）；agent 转述给人，人同意后补 `--yes` 重跑。

**整批只开一次口子**：`submit` 每成功一次就 `auth.OpenBrowser(page)`（`main.go:2719`）。`import` 不调用它，整批结束后只打印**一行**：控制台「待上线」页的地址（v18 的批量上线，[ADR 0090](../../decisions/0090-batch-publish-confirms-once.md)）。一条都没成功时不打印。这与 `submit` 对草稿的处理同一个理由——「为一次没有结果的提交打开标签页，是没人要求的副作用」。

**`retire` 的判定与结果**（[ADR 0092](../../decisions/0092-retire-local-copies-only-after-they-are-live.md)）：

- **判据是「我的、且 live」**。一次 `skills list --show all --all` 拿到调用者的全量卡片（`Card.State` 是 live / draft / offline / deleted，`Card.Source` 是 created / shared / joined），只把 `Source == "created"` 且 `State == "live"` 的名字当成「可以删」。别人共享给我的同名 skill 不算。
- **删**：同名 skill 命中了判据，删的是本机目录树 `<候选目录>/<名字>`。
- **整批拒绝**：只要有一份本机 skill 在平台上**存在但不是 live**（草稿、已下线），`retire` 一条都不删，退出码非 0，看清单就知道缺哪几条。这是「停在下线之前」在命令里的落点——不依赖 agent 记得先查。
- **留**：平台上根本没有同名 skill 的本地副本不算阻塞，它们留着并在汇总里单列（从来不在这次迁移里）。
- **失败**：删除时出错（权限、占用）。不中止整批，逐条报告，退出码非 0。
- **包**：`~/.skillmaster/skills-backup-<YYYYMMDD-HHMMSS>.zip`（本机时区），含本次扫描到的**全部** skill，不只将被删的；包内路径是本机绝对路径去根。汇总里打印包的路径与大小。
- **断网或凭据不可用 → 拒绝执行**，不猜状态、不先打包。
- **扫描规则与 `import` 共用一套**（一层、跳过 `.` 开头、候选表或给定目录），避免两条命令对「本机有哪些 skill」给出两种答案。

**候选目录**（按顺序探测，存在的才纳入；PRD §各 agent 的技能目录 是这张表的依据）。表里第二列就是搬运时自动打上的标签：

```
~/.agents/skills              agent:common    通用位置（Codex 主位置、Zed、Warp、Amp、Gemini 别名…）
~/.claude/skills              agent:claude    Claude Code
~/.codex/skills               agent:codex     Codex 旧位置（已弃用但仍被读取）
~/.cursor/skills              agent:cursor
~/.gemini/skills              agent:gemini
~/.copilot/skills             agent:copilot
~/.config/opencode/skills     agent:opencode
~/.codeium/windsurf/skills    agent:windsurf
~/.config/amp/skills          agent:amp
~/.config/agents/skills       agent:amp       Amp 的更高优先级位置
~/.kiro/skills                agent:kiro
~/.cline/skills               agent:cline
~/.roo/skills                 agent:roo
~/.qwen/skills                agent:qwen
~/.openhands/skills           agent:openhands
~/.factory/skills             agent:factory
~/.workbuddy/skills           agent:workbuddy
~/.dsh/skills                 agent:dsh
```

不给参数、也不给 `--all` 时，`import` 只用这张表；给了目录就只认给的那个。`~` 在 Windows 上解析为 `%USERPROFILE%`，代码里走 `os.UserHomeDir()`，不做平台分支。

**列表截断时的提示要改**（`main.go:1273` 打印计数行、`:1274` 打印收窄行）：现在第二行是「要收窄就用关键词或 `--category` / `--my-tag` / `--public-tag`；要全部就用 `skillmaster skills list --all`」——给了菜单，没给第一步。改成先说怎么找出来，并保留「要全部就用 `--all`」那半句：

```
（要收窄：先 skillmaster skills my-tags 看有哪些标签，再用 --my-tag / --category 筛；要全部就用 --all）
```

理由：一个从没看过这个库的 agent 不知道有哪些标签可填，`--my-tag` 对它是个空旗标。**"先看有哪些标签，再按标签召回"这条路径要由 CLI 在截断的当下带出来，而不是写进系统提示词**——它是条件性的（只有库大到列不完才用），而提示词里那四条是无条件的行为准则。默认 50 条一页（`ListPageSize`），不到 50 的库永远看不到这句。

**扫描规则**：一个 skill = 某个**直接子目录**里有 `SKILL.md`，即 `<dir>/<name>/SKILL.md`。

- **不递归**。`~/.claude/skills/coding` 内部还有 10 个子 `SKILL.md`，那是它自己的子结构；递归会把本机的 33 个变成 44 个。
- **跳过 `.` 开头的条目**。`~/.codex/skills/.system/` 里是客户端自带的 5 个 skill，不是用户的。
- 目录存在但没有符合条件的子目录时，记为 0 条，不报错——这是正常状态（本机 18 个候选目录只有 3 个存在）。

**标签的三条语义**：

- **由目录决定，不由客户端决定**。`~/.agents/skills` 里的东西一律是 `agent:common`，即使它同时被 Codex 和 Zed 读取——标签记来源，不记兼容性。
- **表外目录不打标签**。用户显式给一个表里没有的路径时，那些 skill 没有 `agent:*`。表就是名词表；造一个 `agent:misc` 等于在名词表之外加词。

- **去重合并取并集**。同一份内容出现在两个候选目录时，合并成一条并带上两个标签。`--tag` 一旦给出就完全覆盖自动标签（不给则自动加），这样「这批是我手工整理的」能盖掉来源。

标签名的合法性由服务端的 `TagNames.parse` 决定：NFKC 归一、去首尾空白、内部连续空白折成一个空格，身份是**小写**（`agent:Claude` 与 `agent:claude` 同一个），展示保留原拼写；上限 **24 个码点**，只禁 `#`，**`:` 合法**。最长的一条是 15 个字符（`agent:openhands` 与 `agent:workbuddy` 并列），全表都在限内。

**服务端不支持一次写入**（v14 的既有契约，已核实）：创建 skill 的接口只接 `name` / `description` / `version_name`，标签是**另一条调用**——`POST /api/v1/skills/{ns}/{name}/tags/my`，body 只有 `{ "name": "agent:claude" }`。所以每个 skill 是"建 → 打标"两次往返，`import` 按这个顺序走，**不新开接口**。

由此有三件必须处理的事：

- **打标失败会留下"建了但没标签"的条目**。不做回滚（删除是高风险的、按 ADR 0046 只在浏览器面），而是**如实报告**：失败项写进同一个汇总（验收 9 的形状），并直接给出补救命令 `skillmaster skill tag add <ns>/<name> agent:claude`。重跑 `import` 不行——那会撞上同名冲突（验收 10）。
- **标签定义按账号计配额**：每账号 100 个定义、每 skill 20 个标签。本版最多用掉 17 个定义（候选表去重后的 `agent:*`：common、claude、codex、cursor、gemini、copilot、opencode、windsurf、amp、kiro、cline、roo、qwen、openhands、factory、workbuddy、dsh）。配额满时打标失败，走同一条报告路径。
- **标签名不能是公开分类名**（`category_name_taken`）。`agent:*` 与现有分类不会撞，但错误照原样透出，不改写成自己的说法。

**本机去重**：同名 skill 出现在多个候选目录时，按 `name`（规范要求与目录名一致）合并成**一条**。判据是内容指纹相同——相同则静默合并并记一行「已在 X 处见过」；不同则**这一条不搬**，连同两处路径一起写进汇总的「需人工决定」一栏，其余照常。

**不让人在批中间回答**：内容不同的同名项若"停下来问用哪一份"，问的又是一道 agent 读不到 stdin 的门——整批就卡在那里。所以它和冲突走同一条路：**报出来、不猜、其余继续**。静默选一份会替人决定丢哪份；跳过不是选择，是如实报告。

**服务端冲突**：写入之前先做一次存在性检查，把**全部**同名冲突一次列全（不是撞第一个就停），然后退出、`skills list` 的对象数不变。要续跑就带 `--skip-existing`，冲突的跳过并计入汇总，新增的照常搬。检查放在写入前，是因为撞到一半才停下会留下半批——而半批和整批看起来一模一样。

**默认不覆盖**：同名代表很可能是同一份东西。静默覆盖会丢人东西，静默跳过会让人以为搬了——所以两者都不是默认，默认是**停下并且说全**。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 环境与分发 | 版本变量 + `--version`、`internal/api` 代次前缀常量（收编非测试代码里那 18 处 `"/api/v1…"` 字面量）、发布构建注入服务端地址、**修 `help env` 的两处文本**、OSS bucket 与 `dl.` 域名和证书、`cli-release.yml`（六个目标）、两个安装脚本、首次 `cli/v1.0.0`、安装说明页 | 1、2、3 |
| P1 旅程正文与四条规则 | `STARTUP.md`（一条链 + 每步一个停）+ `/gateway/STARTUP.md` 路由 + `skillmaster startup`（无凭据先驱动登录，打印前加一行现场状态）；`help usage` 主题（末尾带可粘贴快照）；换掉首页卡片里那两句文案（**先改设计稿**） | 4、5、16–20、27、29 |
| P2 搬运 | `skills import`（一层扫描、来源标签、`--yes`、`--skip-existing`、收尾一行控制台地址） | 6–14 |
| P3 上线引导与下线 | `skillmaster skills retire`（核对 live、整批拒绝、打包备份、逐条报告）；`import` 与正文里的「去待上线页」收尾；`skills pending` 为空才放行 | 15、21–25、28 |
| P4 删除网关与 `invoke` 报权限 | 删除网关 skill 与 `setup`；`GET …/body` 加两个权限响应头；`skill files --help` 补一条「权限不够」 | 25 |

**P1 一次交完正文、规则与卡片**，因为验收 23 要求提示词里那段与 `help usage` 逐字一致——先交正文后交 `usage`，中间那一版根本过不了 23。**P2 与 P1 无依赖**，可以并行。**P3 依赖 P2**（retire 与 import 共用扫描规则；15 的两处收尾也都在 P2 之后才齐）。**P4 可以最后做**：删网关与加响应头都不影响前三期的可测性。

**P0 里那两处 `help env` 的文本**：① `SKILLMASTER_SERVER` 现在写着「默认 https://skillmasterai.com」，与代码（`http://localhost:8080`）相反——注入生产地址之后改成「默认随构建而定」；② `SKILLMASTER_API_KEY` 一条加一句「**不要把它的值打印出来**（`echo` / `env` / 写进日志）——那会进 agent 的上下文」。CLI 自身从不回显凭据（已核：`auth status` 只印名字、权限、有效期，全仓无一处打印 `AccessToken` 或 `Authorization`），但 agent 敲什么命令不受 CLI 控制。

## 开放问题

**已结的一条**：Codex 读哪个目录。原疑点是 `~/.codex/skills/` 与 `~/.agents/skills/` 之争；2026-10 的 loader 与文档给出的结论是 **`~/.agents/skills/` 为主、`~/.codex/skills/` 是仍被读取的旧位置**（本机 `~/.codex/skills` 只剩 `.system`，与此一致）。两者本来都在候选表里，所以**行为不变**，只是不再是一个悬着的问题。

**已结的第二条**：标签能不能在创建时一并写入。答案是不能，已按「先建后打标 + 失败如实报告」写进上面的接口一节，因此不再是一个开放问题。

1. **草稿版本名用什么？** ADR 0033 要求版本名是 semver，`submit --name` 必填（首次写入）。当前倾向默认 `0.1.0`，允许 `--name` 覆盖。这是**skill 的版本名**，与 CLI 自己的 `1.0.0` 无关（ADR 0088）。若用户希望从 `SKILL.md` 的 `version:` 读，需要先改 ADR 0033 的口径（v9 已明确那个字段是内容、不是版本名）。
2. **改系统提示词这一步，CLI 要不要提供命令？** 当前倾向不提供：不同 agent 的文件不同（`CLAUDE.md` / `AGENTS.md` / 其他），且这一步的产物是文本，正适合 agent 做。但**幂等标记与回退**的规则必须由正文规定死（起止标记 + `.bak`）。
