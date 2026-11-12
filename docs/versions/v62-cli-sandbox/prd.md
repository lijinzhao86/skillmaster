# v62-cli-sandbox · 产品需求

> **最后更新**：2026-11-11
> **所属版本**：[`README.md`](README.md)
> **状态**：需求已锁定（所有者确认 2026-11-11）

## 用户与场景

**谁在用**：

1. **在 agent 沙箱里用 `skmr` 的人**——Claude Code、WorkBuddy、Codex、Cursor、Zed 等的沙箱里跑命令。这是本版的主要对象。
2. **在自己终端里用 `skmr` 的人**——开发机、远程 SSH、容器；功能上不降级（凭据仍然长期可用），但默认安全面变了：凭据默认是明文文件，想保留钥匙串的人用 `--keyring`。
3. **排障的人**——看 `auth status` 与 `doctor` 的输出，判断凭据在哪、沙箱拦了哪一步。

**在什么场景下用**：

- 在 WorkBuddy 里第一次登录：弹「允许写入钥匙串」。点允许，写进去的是沙箱副本（`login.keychain-db.sb-…`），会话结束就没了；点拒绝，CLI 才改用文件。**两个结果最后都用文件，但用户不知道该点哪个。**（2026-11-11 实测，WorkBuddy 5.8.6 审计日志）
- 日常写 pin、写缓存、连平台域名：在 WorkBuddy 里各弹一次审批。用户不知道这些路径是干什么的。
- 登录完跑 `skmr auth status`：它说「凭据来自 系统钥匙串」，可这次登录实际写的是文件——用户以为凭据在更安全的地方。
- 换成 Claude Code / Codex / Cursor，同样的问题换个形状：配置键不同、能不能在沙箱里写也不同。

**现在的替代做法是什么**：

| 替代做法 | 代价 |
|---|---|
| 维持「钥匙串优先、文件兜底」 | 沙箱里每次登录都要过一个看不懂的审批；点允许反而写进副本 |
| 用户自己手动改 agent 配置 | 要求用户会改 JSON/TOML、知道每个客户端的键；改错会破坏配置 |
| 用 `SKILLMASTER_API_KEY` | 不落盘，但要用户会去控制台签 key、会设环境变量；不是默认路径 |

## 调研依据

[`docs/research/agent-sandboxes.md`](../../research/agent-sandboxes.md) 核对了主流 agent 的沙箱机制与配置键（2026-11-11）。三条直接影响本版：

1. **没有一家允许子进程自己弹权限框**；权限面在宿主手里，只能写宿主配置或打印片段。
2. **多数宿主把「自己的配置文件」也设成写保护**；自动授权必须**在沙箱外**跑，只有 WorkBuddy 实测可以让沙箱内的 agent 写（一次审批）。
3. **钥匙串在沙箱里普遍不可用**；凭据落文件是这批客户端的共同形状。

**由此得到本版的主路径**：`skmr doctor --fix` **在沙箱外跑一次**——用户在终端跑，或 agent 请求「仅这一条命令出沙箱」；一次能把本机所有**有可写配置面且不触发跳过条件**的客户端配好，其余在输出里说明原因（没有权限键、没有配置面、或该客户端本身没有可改的配置）。不需要进每家沙箱各写一次。

**「一次确认」的范围**：本版的一次确认指**沙箱权限**（目录可写 + 域名可访问），配好之后日常读凭据、写 pin、连平台都不再弹。它**不包括登录本身**——凭据有闲置与绝对上限，过期后仍要重新 `skmr login`（浏览器里点一次授权，不再弹沙箱权限）；也不包括 `skmr update` 替换二进制（通常一次审批）。想彻底不登录，走 `SKILLMASTER_API_KEY`，那是另一条路。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | **凭据默认写入状态目录的 0600 文件**（`credentials-<server 哈希>`），不再默认写钥匙串。`SKILLMASTER_HOME` 仍然优先决定状态目录。 | CLI |
| 2 | **钥匙串改为显式可选**：`skmr login --keyring` 让这一次登录写钥匙串。不给参数就是文件。 | CLI |
| 3 | **老凭据继续可用**：读路径先看文件；文件里没有，再读钥匙串。 | CLI |
| 4 | **`auth status` 报「实际在用的那份」**：文件还是钥匙串，按真正提供凭据的副本说。 | CLI 输出 |
| 5 | **`doctor` 的沙箱行不装知道**：客户端没有自报标记时（WorkBuddy）说「客户端没有自报」，不说成「没有检测到」；有标记的照旧点名。 | CLI 输出 |
| 6 | **登录输出说清落点与是否明文**，去掉重复词。 | CLI 输出 |
| 7 | **`skmr doctor --fix`：把沙箱白名单写进客户端配置**。它必须在**沙箱外**跑：用户在终端跑一次，或 agent 按第 9 条请求「仅这一条命令出沙箱」。一次配好本机所有有权限键的客户端：**WorkBuddy、Claude Code、Codex、Gemini、Cursor、Devin Desktop、Zed、Copilot CLI**；每家的键与文件路径以调研文档为准。**WorkBuddy 是例外**：沙箱内也能跑（写时一次审批）。**Codex 旧式与 Gemini 没有域名键**：只能打开网络总开关，代价是放开该客户端的全部出站网络；**Codex 旧式还会打开工作区可写**（`workspace-write` 管的是工作区，`writable_roots` 只是把状态目录加进去），`--undo` 不还原这个开关；这些都在输出里如实说。**不新建会改变沙箱开关的配置**（Gemini 的 `tools.sandbox` 不存在时跳过；Codex 的 `config.toml` 不存在或为空时也跳过；Codex 已用新式 `[permissions.*]` 时不混写）；其余客户端允许创建不打开沙箱的父对象。 | CLI |
| 8 | **幂等、可撤销、不硬写**：已存在的不重复加；`--undo` 移除与 SkillMaster 写入值完全一致的条目（同值无法区分是谁加的，如实写在输出里）；配置文件不是合法 JSON/TOML 时拒绝写入并打印片段，原文件字节不变；改前留一份备份（只建第一份，之后不覆盖；**原文件已存在时才有备份**，首次新建的文件没有；**零字节的失败备份例外**，下次重写）。 | CLI |
| 9 | **安装/首次运行时的授权路径**：<br>· **安装器**（`install.sh`）：检测到客户端后，只打印会改什么与两条命令（`--dry-run` / `--fix`），**不读终端、不写配置**；agent 在跑安装时先用 `AskUserQuestion` 问用户是否现在配置，同意后再运行 `doctor --fix`；人在终端运行这条命令即视为同意。检测到的客户端都会被告知结果：能写的写，跳过的说明原因。<br>· **Agent 在沙箱里**：`startup` skill 让 agent 跑 `skmr doctor --fix`，并按客户端的「单条命令出沙箱」机制请求一次：Claude Code 用 Bash 的 `dangerouslyDisableSandbox`、Codex 用 `on-request` 升级、Copilot 用 bypass、Zed 用 `unsandboxed`；用户点一次同意即可。WorkBuddy 不需要出沙箱（沙箱内可写，一次审批）。<br>· **兜底**：客户端不支持单条出沙箱或被企业策略禁用时，打印一条可粘贴到终端的命令。 | 安装脚本 + CLI；skill 正文随平台发布（P1） |
| 9a | **出沙箱只用于管理命令**：`doctor --fix`、`login`、`logout`、`update` 这类。**`skmr run` 永远不出沙箱**——它执行 skill 代码。 | CLI + skill 约定 |
| 10 | **对没有配置面的客户端只打印指引**，分两组：Cline / Roo Code / Aider / Amp / 旧 Windsurf **没有可写的权限键**，只能靠命令级审批；OpenHands（只有挂载）、GrokBot（只有企业面板）**没有本地配置面**，DSH **只有文件键 `additionalWritableRoots`、没有域名键**——分别给挂载卷、可写根或企业面板的替代做法。 | CLI 输出 |
| 11 | **新增两条 ADR**：0185 修订 [ADR 0025](../../decisions/0025-cli-credential-storage.md) 的「钥匙串优先」；0186 修订 [ADR 0167](../../decisions/0167-sandbox-compatibility-is-capability-based.md) §5 的「只打印、不改配置」。交付物，由 `check_docs.py` 判定，不设运行时验收。 | 决策记录 |
| 12 | **把调研落成仓库事实**：[`docs/research/agent-sandboxes.md`](../../research/agent-sandboxes.md)。交付物，不设运行时验收。 | 调研文档 |
| 13 | **`skmr logout` 把两处都清掉**：写入路径与只读旧副本、文件与钥匙串；哪一处清不掉就报告，不谎称已退出。它清的是**当前凭据**；更早版本遗留、与当前无关的条目不在本版主动清理（[ADR 0101](../../decisions/0101-the-cli-never-deletes-files.md)）。 | CLI |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | **默认整体关闭沙箱，或把 `skmr run` 放出去** | 安全边界会一起消失；`skmr run` 执行的是 skill 代码，必须留在沙箱里。本版只允许**单条管理命令**出沙箱（第 9、9a 条），不是关沙箱 | 不做 |
| 2 | **自动打开终端并注入按键** | 需要 macOS 辅助功能授权，且「谁在替用户按回车」不好解释；一条可粘贴的命令已经够 | 不做 |
| 3 | **解决回环被挡导致的登录失败** | Cursor / Zed / Claude Code Linux 默认封回环，且没有窄例外；这与凭据落点无关 | 另开一版：device flow 或 API key 为主路径 |
| 4 | 改控制台、服务端、设计稿 | 本版只动客户端 | 未排期 |
| 5 | 换掉 `zalando/go-keyring` | 钥匙串从默认降为可选，现有库够用 | 如果 `--keyring` 实测出问题 |
| 6 | 改 `SKILLMASTER_API_KEY` 的优先级与语义 | 环境变量优先于本机存储是 [ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md) §决定 2 的独立决定 | 不做 |
| 7 | 改凭据的过期、刷新、轮换；主动删旧钥匙串条目 | 本版只改「存在哪」与「放行哪些路径」 | 不做 |
| 8 | 申请任何客户端的删除权限 | 放行范围只有状态目录与域名；`skmr` 自己从不删（[ADR 0101](../../decisions/0101-the-cli-never-deletes-files.md)） | 不做 |

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | 没有 `SKILLMASTER_API_KEY`、钥匙串里也没有凭据的机器，首次 `skmr login` 成功后，凭据写在状态目录的 `credentials-<哈希>`，权限 0600；钥匙串没有新增条目 | `ls -l` 看文件与权限；`security find-generic-password -s skillmaster-cli` 查不到新条目 |
| 2 | 在 WorkBuddy 沙箱里跑 `skmr login`，**不出现钥匙串的写审批**，登录成功 | 审计日志没有 `file-safety.needs-approval` 指向 `Library/Keychains/`；退出码 0 |
| 3 | 钥匙串里有旧凭据、文件没有的机器，升级后任意读命令仍能认证；`auth status` 说凭据来自钥匙串 | 造一份只有钥匙串的凭据，跑 `auth status` 与一条读命令 |
| 4 | 文件与钥匙串两份并存（**包括钥匙串那份写得较晚**）：默认 store 仍以文件为准，`auth status` 说文件，实际请求用文件里的 token | 两份都造（两种新旧各一次），比对输出与请求头 |
| 5 | 带 `--keyring` 登录后，凭据写入钥匙串，`auth status` 说来自钥匙串；钥匙串写不进去时落文件，并在输出里说明 | 跑 `skmr login --keyring`，再跑 `auth status`；在沙箱里各试一次 |
| 6 | `skmr logout` 后文件被清空（零字节）、钥匙串条目被清（能清到的话），`auth status` 报未登录 | 跑 logout 后再查两处 |
| 7 | `skmr doctor` 在 WorkBuddy 里说「客户端没有自报」，在 Claude Code（`SANDBOX_RUNTIME=1`）里仍点名 | 两个客户端各跑一次，比对沙箱行 |
| 8 | 登录输出没有「系统钥匙串钥匙串」这类重复词，且写明落点与是否明文 | 跑一次 login 读输出 |
| 9 | **在终端跑一次 `doctor --fix`**，WorkBuddy 与 Claude Code 的真机配置被正确写入（对象与键都在）；其余客户端（Codex、Gemini、Cursor、Devin、Zed、Copilot）用**配置副本**验证合并正确：SkillMaster 的键在、其他键的值不变（JSON 重新序列化可能改变缩进与键序） | WorkBuddy/Claude Code 真机各一次；其余客户端用临时目录内联构造的配置副本跑（`agentconfig_test.go`），比对未触碰的键 |
| 10 | 第二次 `doctor --fix` 无改动（幂等）；`--undo` 后与 SkillMaster 写入值完全一致的条目消失，**其他不同值的条目不变；用户自己写过的同值条目也会被移除（纯字符串无法区分来源）**。它可能留下空数组与为放行打开的 `allowLocalBinding`/`networkAccess` 这类开关（无法与用户原本的值区分），这一点在 `--undo` 的输出与 ADR 里写明 | 连跑 `--fix`/`--fix`/`--undo`，每次比对备份；另造一份「用户预置同值条目」的配置验证它会消失 |
| 11 | 配置文件不是合法 JSON/TOML 时，`--fix` 拒绝写入并打印片段，原文件字节不变 | 造坏文件，比对校验和 |
| 12 | 对 Cline / Aider 这类没有配置面的客户端，`--fix` 只打印指引，不写任何文件 | 临时 HOME 里只放 `.aider.conf.yml`，跑 `--fix`，确认文件未变 |
| 13 | 在 Claude Code 沙箱里跑 `--fix` 失败并给出可粘贴的终端命令；在终端跑成功 | 沙箱内、终端各跑一次 |
| 14 | **安装器不提问**：跑 `install.sh`，检测到客户端时只打印改动与命令，不读终端、不写配置；提示里不出现 JSON/TOML 键名；随后运行 `doctor --fix` 才写入。agent 场景由 agent 先用 `AskUserQuestion` 问一次。 | 在装有 Claude Code/WorkBuddy 的机器上跑一次安装脚本，确认配置未变；再运行 `doctor --fix`，确认写入 |
| 15 | **单条命令出沙箱**：在 Claude Code 里，agent 执行 `doctor --fix` 时能请求「仅这一条命令出沙箱」，用户同意一次后配置写入；在 Codex 里走 `on-request` 升级。两者重启后不再弹。 | 两个客户端各一次，看审批与写入 |
| 16 | **出沙箱被禁用时退回兜底**：企业策略禁止 bypass 时，CLI 不硬来，打印终端命令。 | 用禁用 bypass 的配置模拟 |
| 17 | **`skmr run` 不出沙箱**：skill 的代码仍在沙箱内执行，不因为第 9 条被带出去。 | 在 Claude Code 里跑一次 `skmr run`，看审计里仍是沙箱内执行 |
| 18 | `go test ./...` 全绿；`go vet ./...` 无输出 | 在 `skillmaster-cli/` 跑 |

**成立条件**：第 1、2、3、9 条任一不满足就不算交付——它们分别是本版存在的理由、要解决的原始问题、不破坏已有用户、以及「装完不再点确定」的核心。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 登录时的钥匙串**写**审批次数 | 一次默认 `skmr login` 触发的、指向 `Library/Keychains/` 的写审批事件数（读授权另计） | WorkBuddy 审计日志按事件类型计数 | 0 |
| WorkBuddy 首次使用的审批次数 | 从 `doctor --fix` 跑完到第一次成功 `skill invoke`，用户点击「允许」的次数（`--fix` 本身的那一次另计） | 审计日志 `needs-approval`/`ask-matched` 计数 | 0 |

## 依赖与前置

- **外部依赖**：无。不依赖服务端改动。
- **内部前提**：
  - [ADR 0185](../../decisions/0185-credentials-default-to-a-file.md)（凭据）与 [ADR 0186](../../decisions/0186-the-cli-writes-a-narrow-sandbox-allowlist.md)（自动写配置）已采纳（2026-11-11）。
  - 钥匙串入口是 `skmr login --keyring`，命令声明里加 flag；`help env` 页已接近字节上限（权威是 `TestHelpEnvPage`），**不放新环境变量**。
  - 客户端配置的键名与形状以 [`docs/research/agent-sandboxes.md`](../../research/agent-sandboxes.md) 为准。
  - `skillmaster/skillmaster-startup` 的正文在平台上，不在本仓库；本版交付 CLI、安装脚本与 `doctor --fix`，skill 里「先跑 `doctor --fix`」那一步随下次 skill 发布（剩余项记在 [`docs/current/TODO.md`](../../current/TODO.md) §版本里的技术欠账的 v62 行）。
- **排除了什么前置**：
  - 不需要「能检测到自己在沙箱里」。凭据默认文件，读写都不依赖这个判断。
  - 不需要服务端配合：凭据格式、过期、轮换都不变。
- **未验证、实现时必须先测**：
  - Claude Code 的 `sandbox.filesystem.allowWrite` 对 `~/Library/Application Support/skillmaster` 是否真的生效（`~/.claude` 本身受保护，但状态目录不在其中）。
  - WorkBuddy 运行中改 `settings.json` 是否会被覆盖、要不要重启才生效。
  - 其余客户端的配置合并主要在副本上验证；Codex 与 Copilot 在 T15 的沙箱实测中写过真实配置（测试后已恢复/删除），但客户端是否认这些键仍未实测；实现完成后按客户端逐家补测。
