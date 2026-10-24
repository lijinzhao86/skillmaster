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
                        ├── 第 2 步 skillmaster skills import …   ← 新命令
                        └── 第 3 步 写入三条稳定规则（正文规定，agent 执行）
                        │
⑤ 日常取用       skillmaster help usage ──► 三条规则（唯一来源）
                 旅程第三步把末尾那段快照写进系统提示词；参数与输出仍走 `help <命令>`
                        │
⑥ 服务端         既有：/api/v1（写）、/web（浏览器）、/gateway（匿名引导）
                 本版新增：一条匿名路由 /gateway/STARTUP.md
```

**为什么安装说明在站点、旅程正文在服务端**：安装说明要在**还没有 CLI** 的时候被读到，它属于公开文档，随站点发版最直接；旅程正文是 `startup` 打印给 agent 的、与 CLI 版本对齐的协议文本，随服务端走。两者都不编进二进制——理由和网关 skill 一样：改一次对所有人生效。

**为什么旅程是一份托管文档而不是编进客户端**：它会随着 agent 生态变化（新的客户端、新的目录约定），托管在服务端就能改一次对所有人生效，不需要发新版二进制。

**为什么本机一个 skill 都不装**：产品卖的就是「skill 放云端、按需取用」，再往技能目录放一个引导用的 skill 自相矛盾（[ADR 0089](../../decisions/0089-no-local-skills.md)）。代价是触发从「常驻 frontmatter」退化为系统提示词里的三句话——那三句因此是全项目最需要打磨的一段文字。

**为什么本版没有"处置本机副本"这一步**：搬上去的全是草稿，`skills list` 默认只列 live——此时撤掉本机副本，用户手上就零可用副本。所以**处置与批量上线一起推迟到下一版**（2026-10-25 用户定，PRD §不做）。`skills retire` 因此既没有命令也没有模式，不是留下没实现的开关。

**为什么第三步由 agent 执行而不是 CLI**：改的是各客户端的提示词文件（`CLAUDE.md` / `AGENTS.md` / 其他），位置随 agent 而异，而产物只是文本。但**规则不能留给临场发挥**：起止标记、`.bak`、只改人指定的那一个文件，这三条由 `STARTUP.md` 规定死（开放问题 2）。

**正文收尾必须说清三件事**，否则用户会以为旅程走完就能用了：① 搬上去的都是**草稿**，要进控制台逐个点上线（本版没有批量）；② **在你逐个上线之前，agent 从库里看不到它们**——第三步写下的「先查我的库」此时查到的还是空的；③ **本机副本先留着**，别自己删——处置是下一版的事。这三句是旅程最后一步的存在理由，不能省成一句「完成」（PRD 验收 4 也据此判定）。

## 数据模型

**没有新表。** 搬运走的是既有的建 skill / 写文件 / 提交草稿三条路径，产物是普通的 skill 与草稿版本。

**客户端也不落任何新东西。** 本版不删本机文件，因此没有备份目录、没有清单文件；`import` 一次跑完即结束，重跑靠 `--skip-existing` 而不是靠记住上次。

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
| `GET` | `/gateway/STARTUP.md` | 匿名 | 一次性旅程的正文 |

这条路由与既有的 `/gateway/SKILL.md` 同一通道、同一替换规则。正文源文件 `gateway/skillmaster/STARTUP.md` 打进镜像的 classpath；发布时把 `<host>` 占位替换成 `SKILLMASTER_PUBLIC_BASE_URL`。**不加新表、不加新配置键。**

`SKILL.md` 与 `setup` 本版**删除**（ADR 0089）；它的正文压缩成**三条稳定规则**，写进用户的系统提示词——只写行为，不写参数。

**`invoke` 的响应多两个头**：`GET …/body` 加 `X-SkillMaster-Can-Browse` 与 `X-SkillMaster-Can-Write`（`true` / `false`），由服务端按同一个 `Access#atLeast` 谓词算。**为什么是响应头而不是响应体字段**：`/body` 返回的是 `ResponseEntity<byte[]>`——作者写下的原样字节，`SkillsController` 的注释写明重新编码会破坏 ADR 0005 的 digest。往那个响应里塞 JSON 字段要么改坏字节，要么要求调用方重新解析；响应头不碰字节。**为什么要服务端算**：客户端只知道凭据的 scope（`skills:read` / `skills:write`），不知道账户在这个 skill 上的档位（`invoke` / `browse` / `write` …）——两边各算一次，迟早会出现"它说我能"和"它让我"不一致。detail 里已有的 `canWrite` 是这个先例：同一个谓词、同一个「由服务端算」的理由；只是载体从 JSON 字段换成响应头，因为这条路不能带字段。

**顺带补一处帮助**：`skill files --help` 渲染出来有三条失败（`not_found` + 通用的「凭据不可用」「连不上服务端」），**没有「权限不够」**——拿到 403 的 agent 会以为自己敲错了。补一条，指向 `invoke` 打出的那行权限。

### CLI

| 命令 | 形态 | 说明 |
|---|---|---|
| `skillmaster startup` | 取回 `/gateway/STARTUP.md` 并打印 | **先确保登录**：本机没有凭据就驱动 `login`，登录完成才打印。端点本身仍是匿名的——「先登录」是命令的顺序，不是端点的权限 |
| ~~`skillmaster setup`~~ | **本版删除** | 连同网关 skill（ADR 0089） |
| `skillmaster skill invoke` | 正文仍走 stdout；权限一行走 **stderr**（如「这一版：可调用、可读文件；不能列目录、不能写」），读的是响应头；`--json` 里带两个布尔 | v16 的「正文 stdout、诊断 stderr」约定 |
| `skillmaster help usage` | `help` 的一个主题，编进二进制 | 前半是三条规则；末尾是带起止标记、可直接粘贴的快照。**不新增命令**（用户否掉了 `skillmaster prompt`）；**也不复述收窄路径**——那由列表截断时的提示当场带出来 |
| 旅程第三步的追问 | 改提示词之前问「这个 agent 只使用哪些标签的 skill」，**默认不限定**（整行不写） | 答案写成提示词里的可选一行「只使用带 X、Y 标签的 skill。」；不限定就整行不写。**CLI 不改**，`skills list --my-tag` 已够 |
| `skillmaster skills import [<目录>…]` | 列清单 → 不带 `--yes` 就退出 → 带 `--yes` 才逐个创建、写文件、提交草稿 | `--dry-run` 只列；`--yes` 是唯一的同意方式（见下）；`--all` 不逐条问，但仍需 `--yes`；`--skip-existing` 跳过同名冲突并计入汇总；`--name <版本名>` 指定草稿版本名（默认 `0.1.0`，**这是版本名，不是 CLI 版本**）；`--tag <a,b>` 覆盖默认的来源标签（默认见下表） |
| ~~`skillmaster skills retire`~~ | **本版不做** | 与批量上线同一版（PRD §不做）。没有命令、没有 `--mode`，不留未实现的开关 |

`startup`、`skills import` 两条新命令与 `help usage` 一个主题都进声明（v16 的自述体系），`--help` 与实现由 `render_conformance_test` / `flagattribution_test` 守着。

**同意只能用 `--yes`，不能用 stdin**：agent 读不到终端输入，"在确认处回答否"是一道它跨不过去的门。照仓库既有形状（`version discard`、`skill join` 同款，`declarations_other.go:218` 原话是「没有 --yes 时只打印后果与完整命令；带 --yes 才执行」）：不带 `--yes` → 打印将被创建的清单 + 它自己会跑的那条完整命令 + 退出码 0（这是答案，不是拒绝）；agent 转述给人，人同意后补 `--yes` 重跑。

**整批只开一次口子**：`submit` 每成功一次就 `auth.OpenBrowser(page)`（`main.go:2714`）。`import` 不调用它，而是把每条的审批地址收集起来，整批结束后**打印一份合并清单**；一条都没成功时不打印。这与 `submit` 对草稿的处理同一个理由——「为一次没有结果的提交打开标签页，是没人要求的副作用」。

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

**列表截断时的提示要改**（`main.go:1266` 打印计数行、`:1269` 打印收窄行）：现在第二行是「要收窄就用关键词或 `--category` / `--my-tag` / `--public-tag`；要全部就用 `skillmaster skills list --all`」——给了菜单，没给第一步。改成先说怎么找出来，并保留「要全部就用 `--all`」那半句：

```
（要收窄：先 skillmaster skills my-tags 看有哪些标签，再用 --my-tag / --category 筛；要全部就用 --all）
```

理由：一个从没看过这个库的 agent 不知道有哪些标签可填，`--my-tag` 对它是个空旗标。**"先看有哪些标签，再按标签召回"这条路径要由 CLI 在截断的当下带出来，而不是写进系统提示词**——它是条件性的（只有库大到列不完才用），而提示词里那三条是无条件的行为准则。默认 50 条一页（`ListPageSize`），不到 50 的库永远看不到这句。

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
| P0 环境与分发 | 版本变量 + `--version`、`internal/api` 代次前缀常量（收编非测试代码里那 17 处 `"/api/v1…"` 字面量）、发布构建注入服务端地址、**修 `help env` 的两处文本**、OSS bucket 与 `dl.` 域名和证书、`cli-release.yml`（六个目标）、两个安装脚本、首次 `cli/v1.0.0`、安装说明页 | 1、2、3 |
| P1 旅程正文与三条规则 | `STARTUP.md` + `/gateway/STARTUP.md` 路由 + `skillmaster startup`（无凭据先驱动登录）；`help usage` 主题（末尾带可粘贴快照）；换掉首页卡片里那两句文案（**先改设计稿**） | 4、5、15、16、17、18、20 |
| P2 搬运 | `skills import`（一层扫描、来源标签、`--yes`、`--skip-existing`、合并审批地址） | 6–14 |
| P3 删除网关与 `invoke` 报权限 | 删除网关 skill 与 `setup`；`GET …/body` 加两个权限响应头；`skill files --help` 补一条「权限不够」 | 19 |

**P1 一次交完正文、规则与卡片**，因为验收 17 要求提示词里那段与 `help usage` 逐字一致——先交正文后交 `usage`，中间那一版根本过不了 17。**P2 与 P1 无依赖**，可以并行。**P3 可以最后做**：删网关与加响应头都不影响前两期的可测性。

**P0 里那两处 `help env` 的文本**：① `SKILLMASTER_SERVER` 现在写着「默认 https://skillmasterai.com」，与代码（`http://localhost:8080`）相反——注入生产地址之后改成「默认随构建而定」；② `SKILLMASTER_API_KEY` 一条加一句「**不要把它的值打印出来**（`echo` / `env` / 写进日志）——那会进 agent 的上下文」。CLI 自身从不回显凭据（已核：`auth status` 只印名字、权限、有效期，全仓无一处打印 `AccessToken` 或 `Authorization`），但 agent 敲什么命令不受 CLI 控制。

## 开放问题

**已结的一条**：Codex 读哪个目录。原疑点是 `~/.codex/skills/` 与 `~/.agents/skills/` 之争；2026-10 的 loader 与文档给出的结论是 **`~/.agents/skills/` 为主、`~/.codex/skills/` 是仍被读取的旧位置**（本机 `~/.codex/skills` 只剩 `.system`，与此一致）。两者本来都在候选表里，所以**行为不变**，只是不再是一个悬着的问题。

**已结的第二条**：标签能不能在创建时一并写入。答案是不能，已按「先建后打标 + 失败如实报告」写进上面的接口一节，因此不再是一个开放问题。

1. **草稿版本名用什么？** ADR 0033 要求版本名是 semver，`submit --name` 必填（首次写入）。当前倾向默认 `0.1.0`，允许 `--name` 覆盖。这是**skill 的版本名**，与 CLI 自己的 `1.0.0` 无关（ADR 0088）。若用户希望从 `SKILL.md` 的 `version:` 读，需要先改 ADR 0033 的口径（v9 已明确那个字段是内容、不是版本名）。
2. **改系统提示词这一步，CLI 要不要提供命令？** 当前倾向不提供：不同 agent 的文件不同（`CLAUDE.md` / `AGENTS.md` / 其他），且这一步的产物是文本，正适合 agent 做。但**幂等标记与回退**的规则必须由正文规定死（起止标记 + `.bak`）。
