# v62-cli-sandbox · 技术设计

> **最后更新**：2026-11-11
> **状态**：已锁定（所有者确认 2026-11-11）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

## 架构

两块互不依赖的改动，共用一个前提：**`skmr` 要能在 agent 沙箱里正常读写自己的东西**。

```
                    ┌──────────────────────────────┐
                    │  agent 沙箱（Claude Code /    │
                    │  WorkBuddy / Codex / …）       │
                    │                              │
   skmr 命令 ──────▶│  internal/credentials         │
                    │    · 默认：文件优先            │
                    │    · 可选：钥匙串（--keyring） │
                    │                              │
                    │  internal/config              │
                    │    · 状态目录候选链            │
                    └──────────────┬───────────────┘
                                   │ 写文件 / 连域名
                                   ▼
                    ┌──────────────────────────────┐
                    │  客户端沙箱配置（在沙箱外写）  │
                    │  ~/.workbuddy/settings.json   │
                    │  ~/.claude/settings.json      │
                    │  …                            │
                    └──────────────────────────────┘
```

- **凭据落点**：`skillmaster-cli/internal/credentials/`。默认文件，钥匙串可选；读路径先文件、再钥匙串（迁移）。
- **沙箱配置写入**：新增 `skillmaster-cli/internal/agentconfig/`，按客户端表读写 JSON/TOML；`cmd/skillmaster/doctor.go` 的 `--fix` 调用它。`install/install.sh` 只打印改动与命令，不读终端；agent 先用 `AskUserQuestion` 问用户，同意后再运行 `--fix`。
- **服务端与控制台不变**：没有新接口、没有表结构、没有控制台操作。

## 数据模型

### 凭据文件

形状不变（`Credentials` JSON，见 `internal/credentials/store.go`）。变的只有**默认落点**：

| 模式 | 写 | 读 |
|---|---|---|
| 默认（文件） | 状态目录的 `credentials-<server 哈希>`，0600 | 文件 → 没有则钥匙串 |
| `--keyring` | 钥匙串 → 成功则把文件截为零字节（不存在则建墓碑）；失败则写文件 | 钥匙串优先，两份并存时按 `SavedAt` 取较新（只影响这一次登录的 store；普通命令仍按默认模式读） |

`Where()` 表示这个 store 实际读/写的那份副本（沙箱里可能是只读的 `older`）；新增 `Source()` 表示「这次实际用的是哪一份」。

**写方式**：凭据文件同样原地覆盖（先 `Chmod`，再 `Truncate`，最后写），不写邻居、不 rename——原因与客户端配置相同（沙箱拒绝改名覆盖）。**顺序是有意的**：先 `O_TRUNC` 会在 chmod 失败时先清掉旧凭据，而报错只说权限；现在 chmod 失败时旧内容还在。`logout` 仍是截断成零字节。

### 客户端配置的写入结果

不建新模型，只记录一次写入的**变更单**：

```go
type Change struct {
    Client  string   // 人类可读名，例如 "WorkBuddy"
    File    string   // 实际写的文件
    Added   []string // 新增的条目（路径或域名）
    Removed []string // --undo 移除的条目
    Kept    []string // 已存在、未改动
    Toggled   []string // 本次打开的开关（allowLocalBinding、network_access 等）
    Processed bool     // 配置读到了、认得：即使本次无改动，也参与总开关提示
    WorkspaceWrite bool // Codex 旧式当前处于 workspace-write（本次打开或本来就是）
    Skip      string   // 没有权限键 / 解析失败 / 不建议自动写
    Err       error    // 读数、备份或写盘失败
}
```

`Changed()` = `Added`、`Removed`、`Toggled` 任一非空。**开关单独记**：一份已经列了路径与域名的配置，仍然可能缺 `allowLocalBinding`，只按数组判断会把它报成「已是最新」而不写盘。`Skip` 非空的客户端不算 `Processed`（读盘/解析失败在这一列，`Err` 也非空）；**没有 `Skip` 的备份/写盘失败**客户端 `Processed` 已为 true、`Err` 非空，消费者要另行检查 `Err`（`doctor` 的提示函数就同时检查两者）。

## 接口

### `skmr login [--keyring]`

- `--keyring`：这一次登录写系统钥匙串；失败按 [ADR 0025](../../decisions/0025-cli-credential-storage.md) 的兜底写文件。
- 不带参数：写文件。
- 声明在 `cmd/skillmaster/declarations.go` 的 `login` 条目；`Usage` 改成 `skmr login [--keyring]`。

### `skmr auth status`

- 「本机：凭据来自 …」改为读 `EnvStore.Source()`，不再读 `Where()`。`Source()` 故意不放进 `Store` 接口，否则每个测试替身都得实现一个它答不上的问题。
- 来源只有三种：文件（给路径）、系统钥匙串、环境变量 `SKILLMASTER_API_KEY`。

### `skmr doctor [--fix [--undo] [--dry-run]]`

| 参数 | 行为 |
|---|---|
| 无 | 现有自检，只打印；`Output` 说明只有 `--fix` 会写配置 |
| `--fix` | 给**检测到的**客户端写入白名单；不写没有权限键的客户端 |

**「检测到」的口径**：客户端的配置目录存在（`~/.workbuddy`、`CLAUDE_CONFIG_DIR` 或 `~/.claude`、`CODEX_HOME` 或 `~/.codex`、`~/.gemini`、`~/.cursor`、`~/.config/devin`、`~/.config/zed`、`COPILOT_HOME` 或 `~/.copilot`），与 `sandbox.Detect()` 的标记无关——WorkBuddy 没有标记，但仍要写。
| `--fix --dry-run` | 只打印变更单，不落盘 |
| `--fix --undo` | 移除与写入值完全一致的条目（同值无法区分来源，也会移除） |
| `--undo` / `--dry-run` 没和 `--fix` 一起给 | 拒绝，`usage` 退出码 |

**写入内容（唯一一份权威）**：

| 项 | 值 |
|---|---|
| 状态目录 | `SKILLMASTER_HOME` 优先；没有它时 `config.OrdinaryDir()`——**不是**沙箱里回退后的目录 |
| 域名 | `sandbox.Hosts()`——实际配置的服务端 host + 更新通道 host |

**客户端表**（键名出处见 [`docs/research/agent-sandboxes.md`](../../research/agent-sandboxes.md)）：

| 客户端 | 文件 | 写路径键 | 域名键 | 备注 |
|---|---|---|---|---|
| WorkBuddy | `~/.workbuddy/settings.json` | `sandbox.extraAllowWrite[]` | `sandbox.orderedRules.network.rules[]`（`{id,source:"user",domain,action:"allow"}`） | 实测 |
| Claude Code | `~/.claude/settings.json` | `sandbox.filesystem.allowWrite[]` | `sandbox.network.allowedDomains[]` + `sandbox.network.allowLocalBinding` | 沙箱外写 |
| Codex | `$CODEX_HOME/config.toml`（默认 `~/.codex`） | `sandbox_mode="workspace-write"` + `[sandbox_workspace_write].writable_roots[]` | `[sandbox_workspace_write].network_access=true`（**总开关，没有域名白名单**） | 需要 TOML 库；已用新式 `[permissions.*]` 时不混写；`config.toml` 不存在或为空时不新建；**`workspace-write` 让工作区本身可写**，不只是 `writable_roots` |
| Gemini | `~/.gemini/settings.json` | `tools.sandbox.allowedPaths[]` | `tools.sandbox.networkAccess=true`（**总开关，没有域名白名单**） | `tools.sandbox` 不存在时不新建（没有开启沙箱）；Linux 容器里 `allowedPaths` 是只读挂载，要可写需 `SANDBOX_MOUNTS=...:rw`（本版不写）；工具级 `sandbox.toml` 本版不处理（P2） |
| Cursor | `~/.cursor/sandbox.json` | `additionalReadwritePaths[]` | `networkPolicy.allow[]` | 回环无窄例外 |
| Devin | `~/.config/devin/config.json` | `permissions.allow[]` 加 `Write(<dir>/**)`（层级未核实，见 research §四） | `sandbox.allowed_domains[]` | 仅 `--sandbox` 时生效 |
| Zed | `~/.config/zed/settings.json` | `agent.sandbox_permissions.write_paths[]` | `agent.sandbox_permissions.network_hosts[]` | 网络不收 IP/localhost |
| Copilot CLI | `~/.copilot/settings.json` | `sandbox.userPolicy.filesystem.readwritePaths[]` | `sandbox.userPolicy.network.allowedHosts[]` | 只收绝对路径 |

**没有可自动写的配置面或没有域名键、只打印指引**：Cline、Roo Code、Aider、Amp、旧 Windsurf 没有可写的权限键；OpenHands 只有挂载、GrokBot 只有企业面板（没有本地配置面）；DSH 只有文件键 `additionalWritableRoots`、没有域名键（本版不写它的配置）。

**写入规则**：

1. 只增不删；已存在的不重复加。同域名已有 `deny` 规则时**跳过并报告**，不把它当「已存在」。
2. 每个**已存在**的配置文件改前复制一份 `<文件名>.skillmaster.bak`（不删，[ADR 0101](../../decisions/0101-the-cli-never-deletes-files.md)）；**只建第一份**，后续 `--fix` 与 `--undo` 不覆盖它——它是 SkillMaster 动手前的原件。首次新建的文件没有备份；写入失败留下的零字节备份下次会被重新写入。
3. JSON：解析为通用结构（数字保留原样，`UseNumber`）→ 只动目标键 → 序列化写回；解析失败就拒绝，打印片段。**目标键不是预期类型（对象/数组）时跳过并说明，不覆盖**；**JSON `null` 例外——它按「键不存在」处理**（`readList` 与 `ensureObject` 同一语义），会创建目标对象。
4. **写盘原地覆盖（打开后 `Truncate`，不 `O_TRUNC`），不写邻居、不 rename。** 沙箱可以允许写文件内容、却拒绝 rename 覆盖已有文件（WorkBuddy 5.8.6，2026-11-11 实测），而 WorkBuddy 的 `extraAllowWrite` 不含删除。代价是「截断→写入」窗口里并发读可能看到空文件或半截文件，中断也可能留下半截；**改前备份只在原文件已存在时存在**。符号链接由 `OpenFile` 跟随，链接保留（dotfiles 用户的 `stow`/`chezmoi` 不会被替换成普通文件）。
5. TOML（Codex）：用 `go-toml/v2` 解析 → 只动目标键 → 写回；注释可能不保留，写进 ADR 的后果与 `--dry-run` 的输出提示。
6. 路径按客户端要求转换：WorkBuddy/Claude Code 可用 `~/`，Codex/Cursor/Copilot 用绝对路径，Devin 用 glob。Zed 的 `network_hosts` 跳过 IP 与 localhost。
7. `--undo` 的匹配规则按条目类型分两套：**纯字符串数组按值移除**（用户自己写过的同值条目也会被移除，无法区分来源）；**WorkBuddy 的规则对象**要求 `domain` 匹配**且** `id` 以 `skillmaster-` 开头，用户同域但不同 id 的规则保留。本次 `--undo` 新建的空**对象**删掉，空**数组**与布尔/枚举开关可能留下（ADR 0186）。
8. 有写盘/备份/读盘失败的客户端计入失败：`--fix` 退出码非 0，并提示「在你自己的终端里跑一次」；对 Codex 旧式与 Gemini 单独打印「没有域名白名单，等于放开全部出站网络」；Codex 旧式只要当前处于 `workspace-write`（本次打开或本来就是；本次写入失败的客户端除外）就单独打印「让工作区本身可写，`--undo` 不还原」。
9. **不要同时跑两个 `--fix`**：这是读-改-写，没有锁；安装器与 `startup` skill 是先后关系。

### 沙箱外执行

- **不是 CLI 能强制的**：`doctor --fix` 只能在做不到时给出可执行的下一步。
- `startup` skill 增加一步：先跑 `skmr doctor --fix --dry-run`（只打印、不落盘）看哪些客户端还缺配置；都齐了就跳过。有缺口时用 `AskUserQuestion` 问一次，同意后再跑 `skmr doctor --fix`；需要出沙箱时，按客户端声明「仅这一条命令出沙箱」（Claude Code `dangerouslyDisableSandbox`、Codex `on-request`、Copilot bypass、Zed `unsandboxed`），用户点一次同意。安装 4.5 已拒绝的，`startup` 不绕过这个拒绝再写。
- **排除清单**：`skmr run` 永不声明出沙箱；只允许 `doctor --fix` / `login` / `logout` / `update`。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 凭据文件默认 + `--keyring` + `Source()`；`doctor --fix` 的引擎、客户端配置写入（含 Codex TOML）与安装脚本的确认说明（不读终端）；`--undo` / `--dry-run` / 备份 / 坏文件拒绝；两条 ADR | PRD 第 1–8、10–14、16–18 条，加 #9 的客户端配置副本部分 |
| P1 | `startup` skill 的沙箱配置步骤（只读 `--dry-run` 探测 → 必要时提问 → 同意后 `--fix`，不覆盖安装拒绝；平台发布，不在本仓库）；WorkBuddy/Claude Code 与其余客户端逐家真机复测 | PRD 第 9 条的真机部分、第 15 条 |
| P2 | `sandbox.toml`（Gemini 工具级）等源码能力 | 本版不验收；满足条件后另开版本 |

**P0 明确不做**：Gemini 工具级 `sandbox.toml`、任何客户端的删除权限。

## 开放问题

| # | 问题 | 结论 / 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 钥匙串的可选入口用 flag、环境变量还是配置键？ | **已定**：`skmr login --keyring`；`help env` 页不能再加变量（权威是 `TestHelpEnvPage`），flag 在 `login --help` 里看得到 | 已由 ADR 0185 决定 |
| Q2 | 读路径要不要总是碰钥匙串？ | **已定**：默认 store 文件先读，文件没有再读钥匙串 | 已由 ADR 0185 决定 |
| Q3 | `doctor --fix` 由谁调起？ | **已定**：`install.sh` 只打印命令；agent 先用 `AskUserQuestion` 问用户再运行，人在终端运行即同意；`startup` skill 先跑只读 `doctor --fix --dry-run`，未配置且用户同意才跑 `--fix`，不覆盖安装 4.5 的拒绝；沙箱内写不动时给出终端命令 | 已由 ADR 0186 决定；真机复核见 Q4/Q5 |
| Q4 | WorkBuddy 运行中改 `settings.json` 会不会被覆盖？ | 先改文件、要求重启后生效，避免与正在运行的进程抢写 | 改一次、重启、再读 |
| Q5 | Claude Code 的 `allowWrite` 对 `~/Library/Application Support/skillmaster` 是否生效？ | 文档说受保护的是 `.claude` 等路径，状态目录不在其中，倾向认为生效 | 在沙箱里写一次 `--fix` 后的状态目录 |
| Q6 | 写客户端配置时如何保证不破坏原有格式？ | JSON 解析后只增键；TOML 用 `go-toml/v2`；先备份；解析失败就拒绝 | 用真实配置副本做测试，比对未触碰的键 |
| Q7 | 「单条管理命令出沙箱」怎么落到 instruction 里？ | 只对 `doctor --fix` / `login` / `logout` / `update` 显式声明；`skmr run` 永不声明 | 在 Claude Code 与 Codex 各实测一次，检查审计里 `skmr run` 仍在沙箱内 |
| Q8 | Codex 的 TOML 写入会不会丢注释？ | 会。用备份兜底，并在 `--dry-run` 里明说「会重排 TOML、可能丢注释」 | 用一份带注释的 `config.toml` 副本验证 |

## 附录

### 附录 A · 与现有代码的关系

| 文件 | 动作 |
|---|---|
| `internal/credentials/store.go` | `Open` 改为文件优先；新增 `OpenKeyring`（`--keyring` 用）；`Source()` 不放进 `Store` 接口 |
| `internal/credentials/filefirst.go` | 新增 `fileFirstStore`（文件优先、钥匙串兜底） |
| `internal/credentials/file.go` | `Clear` 同时清写入路径与只读旧副本；新增 `FileDefaultNotice` |
| `internal/credentials/responsive.go` | 保留；`--keyring` 仍走它 |
| `internal/credentials/env.go` | `Source()` 改为返回环境变量或兜底来源 |
| `internal/config/config.go` | 导出 `OrdinaryDir()`（现有 `ordinaryStateDir` 是私有的） |
| `internal/sandbox/sandbox.go` | 导出 `Hosts()`（现有 `networkHosts(serverHost())` 是私有的） |
| `internal/agentconfig/`（新） | 客户端表、JSON/TOML 读写、幂等与撤销 |
| `cmd/skillmaster/doctor.go` | 解析 `--fix` / `--undo` / `--dry-run`，调用 `agentconfig` |
| `cmd/skillmaster/auth.go` | `login --keyring`；`auth status` 用 `Source()` |
| `cmd/skillmaster/declarations.go` | `doctor` 与 `login` 的 `Usage` / `Output` / `Flags` |
| `install/install.sh` | 第 8 步（RELEASE.md §7）：检测客户端 → 只打印改动、绝对路径命令（`--dry-run` / `--fix` / `--undo`）与 `auth status` 收尾，不读终端、不写配置；agent 先用 `AskUserQuestion` 问用户 |
| `go.mod` | 加 `github.com/pelletier/go-toml/v2`（Codex） |

### 附录 B · 来源与出处

- 客户端键名与沙箱行为：一手来源逐条列在 [`docs/research/agent-sandboxes.md`](../../research/agent-sandboxes.md)，本文不重复。
- 凭据存储的现成先例：Codex `cli_auth_credentials_store` 默认 `file`（源码核实，见调研文档）。

### 附录 C · 已测量的数据

| 指标 | 口径（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| WorkBuddy 沙箱内 `login` 成功率 | 2026-11-11，WorkBuddy 5.8.6，状态目录已放行，1 次 | 1/1 |
| 登录时钥匙串**写**审批次数 | 同一次，审计日志里指向 `Library/Keychains/` 的 `needs-approval` | 1（改后目标 0；读授权另计） |
| 状态目录写入审批次数 | 放行后同一次 `login` | 0 |
