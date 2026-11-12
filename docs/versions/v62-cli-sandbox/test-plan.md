# v62-cli-sandbox · 测试方案

> **最后更新**：2026-11-11
> **状态**：进行中（已实现；自动化全绿，真机 WorkBuddy、Claude Code 沙箱失败与终端隔离配置成功、安装器已跑；T17–T19 与部分真机项未跑——见 §结果）
> **所属版本**：[`README.md`](README.md)

## 验收映射

把 [`prd.md`](prd.md) §验收与指标 的 18 条逐条映射到用例。

| prd 验收标准 | 对应用例 |
|---|---|
| #1 新机器首次登录落 0600 文件，钥匙串无新条目 | T1 |
| #2 WorkBuddy 沙箱内登录无钥匙串写审批 | T2 |
| #3 只有钥匙串的旧机器仍能读，`auth status` 说钥匙串 | T3 |
| #4 两份并存（两种新旧）都以文件为准，`auth status` 说文件、请求用文件 | T4 |
| #5 `login --keyring` 写钥匙串；写失败落文件并说明 | T5 |
| #6 `logout` 两处都清 | T6 |
| #7 `doctor` 沙箱行措辞 | T7 |
| #8 登录输出去重复词、写明落点与明文 | T8 |
| #9 一次 `--fix`：WorkBuddy 真机（终端与沙箱）；Claude Code 终端半用隔离配置；其余客户端用副本验证 | T9、T10、T11、T15（仅终端隔离半，不是 T10 的真机写入） |
| #10 幂等与 `--undo` | T12 |
| #11 坏文件拒绝写入、字节不变 | T13 |
| #12 没有权限键的客户端只打印 | T14 |
| #13 Claude Code 沙箱内失败并给终端命令 | T15 |
| #14 安装器不提问，只打印命令 | T16 |
| #15 单条命令出沙箱（Claude Code / Codex） | T17 |
| #16 出沙箱被禁用时退回兜底 | T18 |
| #17 `skmr run` 仍在沙箱内 | T19 |
| #18 `go test` / `go vet` 全绿 | T20 |

每条用例的实际结果见下方 §结果；本表不另记状态，避免两处漂移。

## 用例

### T1 · 新机器默认写文件

- **前置**：临时 `HOME`（`t.TempDir()`），无 `SKILLMASTER_API_KEY`，钥匙串里无 `skillmaster-cli` 条目；用测试替身注入一次成功的登录结果（不经过真实浏览器）。
- **步骤**：调用 `saveAndReport` / `openStore` 的保存路径；`os.Stat` 文件；查钥匙串。
- **期望**：文件在 `<state>/credentials-<hash>`，mode `0600`；钥匙串无新条目；`auth status` 的来源是文件路径。
- **判定**：通过 / 失败（记录实际路径与权限）

### T2 · WorkBuddy 沙箱内登录不碰钥匙串

- **前置**：WorkBuddy 5.8.6；状态目录已由 `--fix` 放行；钥匙串里有旧条目。
- **步骤**：在 WorkBuddy 里跑 `skmr login`，完成浏览器授权；读审计日志。
- **期望**：`~/.workbuddy/audit-log/` 里没有 `file-safety.needs-approval` 指向 `Library/Keychains/`；凭据文件被重写；退出码 0。
- **判定**：通过 / 失败（贴出审计事件）

### T3 · 只有钥匙串的旧机器

- **前置**：用旧版本造一份钥匙串凭据，删掉状态目录里的 `credentials-*`。
- **步骤**：跑 `skmr auth status` 与一条读命令。
- **期望**：读命令成功；`auth status` 说「凭据来自 系统钥匙串」。
- **判定**：通过 / 失败

### T4 · 两份并存、默认用文件

- **前置**：钥匙串与文件各放一份；两种新旧各造一次（文件较新、钥匙串较新）。
- **步骤**：跑 `skmr auth status`；抓一条命令的 `Authorization` 头。
- **期望**：两种情形下 `auth status` 都说文件；请求头里的 token 等于文件里的。ADR 0185 之后默认 store 不再用「较新一份」裁决。
- **判定**：通过 / 失败

### T5 · `--keyring`

- **前置**：macOS；钥匙串可写。失败子用例：在沙箱里或把钥匙串设为只读/让它超时。
- **步骤**：跑 `skmr login --keyring`，完成授权；跑 `auth status`；查钥匙串条目。失败子用例再跑一次。
- **期望**：成功时凭据写入钥匙串，文件被清空（零字节；不存在则建零字节墓碑，避免旧副本遮蔽），`auth status` 说钥匙串；失败时落文件，输出写明是明文文件。
- **判定**：通过 / 失败

### T6 · `logout` 两处都清

- **前置**：文件与钥匙串各有一份。
- **步骤**：跑 `skmr logout`；查文件与钥匙串；跑 `auth status`。
- **期望**：文件零字节；钥匙串条目被清（能清到的话）；`auth status` 报未登录。
- **判定**：通过 / 失败

### T7 · `doctor` 沙箱行

- **前置**：WorkBuddy（无标记）与 Claude Code（`SANDBOX_RUNTIME=1`）。
- **步骤**：两个客户端各跑 `skmr doctor`。
- **期望**：WorkBuddy 那行说「客户端没有自报」，不说「没有检测到」；Claude Code 那行点名 `SANDBOX_RUNTIME`。
- **判定**：通过 / 失败

### T8 · 登录输出措辞

- **前置**：可完成一次登录的机器。
- **步骤**：跑 `skmr login`，读 stdout/stderr。
- **期望**：没有「系统钥匙串钥匙串」这类重复词；写明凭据落到哪、是不是明文。
- **判定**：通过 / 失败

### T9 · WorkBuddy 真机写入

- **前置**：`~/.workbuddy/settings.json` 存在；先复制一份到 `/tmp` 作对照。
- **步骤**：在终端跑 `skmr doctor --fix`。
- **期望**：`sandbox.extraAllowWrite` 出现状态目录；`sandbox.orderedRules.network.rules` 出现 `sandbox.Hosts()` 返回的域名，各一条 `action:"allow"`；其余键的**值**不变（JSON 重新序列化可能改变缩进与键序）；备份文件存在。
- **判定**：通过 / 失败

### T10 · Claude Code 真机写入

- **前置**：`~/.claude/settings.json` 存在；先复制对照。
- **步骤**：在终端跑 `skmr doctor --fix`。
- **期望**：`sandbox.filesystem.allowWrite` 出现状态目录；`sandbox.network.allowedDomains` 出现 `sandbox.Hosts()` 返回的域名；`allowLocalBinding` 为 true；其余键不变。
- **判定**：通过 / 失败

### T11 · 其余客户端用副本验证合并（WorkBuddy/Claude Code 由 T9/T10 真机覆盖）

- **前置**：每个客户端一份最小配置副本，测试里在临时目录内联构造（`agentconfig_test.go` 的 `writeFile`），形状从调研文档来。
- **步骤**：表驱动跑 `Apply`：对 Codex、Gemini、Cursor、Devin、Zed、Copilot 各一次；再跑一次确认幂等；最后以 `undo=true` 再跑一次 `Apply`。
- **期望**：目标键出现且形状正确；未触碰的键的值不变（JSON 可能重排）；第二次 `Apply` 无变更；`undo=true` 的 `Apply` 移除与 SkillMaster 写入值完全一致的条目（同值的用户条目也会被移除）；本次新建的空对象不会留下；布尔开关与空数组可能留下（与 T12 一致，ADR 0186）。
- **判定**：通过 / 失败

### T12 · 幂等与撤销

- **前置**：任意一份真实配置副本；另造一份「用户预置与 SkillMaster 同值条目」的副本。
- **步骤**：`--fix` → 记录变更单 → 再 `--fix` → `--fix --undo`；在同值副本上再跑一次 `--fix`/`--undo`。
- **期望**：第二次 `--fix` 的变更单为空；`--undo` 按值移除，其他不同值的条目不变；**同值条目无法区分来源，会与 SkillMaster 的一起被移除**（ADR 0186）。空数组与 `allowLocalBinding`/`networkAccess`/`sandbox_mode` 这类布尔或枚举开关可能留下（方案里写明的代价）。
- **判定**：通过 / 失败

### T13 · 坏文件拒绝

- **前置**：一份不是合法 JSON/TOML 的配置副本；记录 sha256。
- **步骤**：跑 `--fix`。
- **期望**：拒绝写入，打印可粘贴的片段；文件 sha256 不变。
- **判定**：通过 / 失败

### T14 · 没有权限键的客户端

- **前置**：临时 `HOME` 里只放 `.aider.conf.yml`。
- **步骤**：跑 `--fix`。
- **期望**：打印「没有可写的权限键：Cline、Roo Code、Aider、Amp、旧 Windsurf」与「没有域名键或没有本地配置面：OpenHands、DSH、GrokBot」两组指引；不写任何文件。
- **判定**：通过 / 失败

### T15 · Claude Code 沙箱内失败与终端兜底

- **前置**：Claude Code 开启 `sandbox.enabled`。
- **步骤**：在沙箱里跑 `skmr doctor --fix`；再在终端跑一次。
- **期望**：沙箱内失败，并打印「在你自己的终端里跑一次：`skmr doctor --fix`」；终端里成功。
- **判定**：通过 / 失败

### T16 · 安装器不提问

- **前置**：装有 Claude Code / WorkBuddy 的机器；先备份配置。
- **步骤**：跑 `install.sh`（pty 与无 tty 各一次）。
- **期望**：不出现 `[Y/n]`、不读 `/dev/tty`；打印 `--dry-run` 与 `--fix` 命令；配置文件不变；提示里没有 JSON/TOML 键名。之后运行 `doctor --fix` 才写入。
- **判定**：通过 / 失败

### T17 · 单条命令出沙箱

- **前置**：Claude Code 沙箱开启；Codex 用 `on-request`。
- **步骤**：让 agent 执行 `skmr doctor --fix` 并声明「仅这一条出沙箱」；用户同意；重启客户端再跑一次普通命令。
- **期望**：配置写入；重启后普通命令不再弹权限。
- **判定**：通过 / 失败

### T18 · 出沙箱被禁用

- **前置**：用一个禁止 bypass 的配置（Claude Code 托管设置 / Codex 策略）模拟。
- **步骤**：让 agent 执行 `--fix`。
- **期望**：不硬来；打印终端命令；没有越权写入。
- **判定**：通过 / 失败

### T19 · `skmr run` 不出沙箱

- **前置**：一个带脚本的 skill；Claude Code 沙箱开启。
- **步骤**：跑 `skmr run`；读审计。
- **期望**：脚本仍在沙箱内执行；没有因为第 9 条被带出去。
- **判定**：通过 / 失败

### T20 · 单元测试与静态检查

- **前置**：`skillmaster-cli/` 目录。
- **步骤**：`go test ./...`；`go vet ./...`。
- **期望**：全绿；vet 无输出。
- **判定**：通过 / 失败

## 环境与数据

- **凭据类用例（T1–T6）**：`t.TempDir()` 作 `HOME`；**钥匙串用测试替身注入**（`internal/credentials/env_test.go` 的 `stubStore`；命令层的 `machineStore` 缝在 `cmd/skillmaster/main_test.go`，`machineKeyringStore` 的替身在 `cmd/skillmaster/sandboxfix_test.go`），不碰真实钥匙串；涉及真实钥匙串的 T2/T5/T6 在 macOS 真机上手工跑。
- **配置写入用例（T9–T14）**：测试在临时目录内联构造配置副本（`agentconfig_test.go` 的 `writeFile`），形状来自 [`docs/research/agent-sandboxes.md`](../../research/agent-sandboxes.md)；真机用例先备份原文件，跑完按备份恢复。
- **沙箱出沙箱用例（T15、T17–T19）**：Claude Code 需临时开启 `sandbox.enabled`；Codex 用 `on-request`；WorkBuddy 用 5.8.6。每条跑完恢复原设置；**例外**是本次 WorkBuddy 的 `--fix` 结果保留（它是 T9 的证据）。Claude Code 的真实 `~/.claude/settings.json` 全程未写：T15 的「终端里成功」用隔离的 `CLAUDE_CONFIG_DIR` 验证，T10 仍未跑。T16 是安装器交互，用临时 `HOME` 与本地 artifact 跑。
- **2026-11-11 的两次终端 `--fix`**（T9 的 21:28–21:29 与 T15 的终端半）：`HOME` 用真实值（只有 WorkBuddy 走真实配置），`CLAUDE_CONFIG_DIR` 指向隔离目录，`CODEX_HOME` 与 `COPILOT_HOME` 指向不存在的路径；Gemini/Cursor/Devin/Zed 未安装。运行后真实 `~/.claude/settings.json` 的 sha256 仍是 `5c270e49…`（未变），Codex 与 `config.toml.skillmaster.bak` 逐字节一致、无 sandbox 键，Copilot 的 `settings.json` 仍不存在。
- **清理**：临时 `HOME` 不删除（`t.TempDir` 自动回收）；真机备份保留，恢复后记录备份路径。本次被 T15 沙箱运行顺带写入的 Codex 用 `config.toml.skillmaster.bak` 逐字节恢复、Copilot 删除新建的 `settings.json`；之后两次终端运行都按上一条隔离，没有重新写入它们。

**2026-11-11 沙箱真机实测的一条环境事实**：Claude Code 的沙箱只按它自己的 `filesystem.allowWrite` 与 `permissions.additionalDirectories` 决定放行，测试设置里后者含家目录，所以沙箱内 `--fix` 写 Claude Code 的配置被拒（那次用 `CLAUDE_CONFIG_DIR` 指到隔离目录，被拒的是隔离目录里的 `settings.json`；真实 `~/.claude/settings.json` 没有被尝试）、写 WorkBuddy / Codex / Copilot 的真实配置却成功。这次测试后 Codex 用 `config.toml.skillmaster.bak` 逐字节恢复，Copilot 删除新建的 `settings.json`，WorkBuddy 保留。结论：**`--fix` 是否被拦取决于那个客户端的沙箱策略，不取决于 SkillMaster**；它按客户端逐条报告成败并以非 0 退出。

## 结果

2026-11-11 实现后首轮。自动化部分已跑；需要真机或沙箱但没跑的标「未执行」，跑了其中一半的标「部分」并写清跑了哪一半。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 部分（单元） | 单元：`TestFileFirstReadsTheFileBeforeTheFallback` 覆盖「默认落文件」；真机清空凭据后的 0600 权限与钥匙串无新条目未跑 |
| T2 | 通过（授权动作待补证） | WorkBuddy 5.8.6，2026-11-11 20:56：本地构建的 `skmr`（当时命名为 `skmr-next`）`login` 退出码 0，凭据原地覆盖为 331 B / 0600；审计 spool 13 条（11 条 `sandbox-executed` + 2 条 `network.allow-matched`），零 `needs-approval`，无系统弹窗；`.new` 未被本次登录碰过（现存 0 字节文件 mtime 20:46，是旧逻辑留下的）。另：本次登录约 3 秒完成且无人点击，控制台页面没有自动提交逻辑；`refresh_token` 为空是本版预期（新版登录不写 refresh token），不是异常线索。需服务端审计确认那次授权动作。 |
| T3 | 通过（单元） | `TestFileFirstFallsBackToTheKeychainWhenTheFileIsEmpty` |
| T4 | 通过（单元） | `TestFileFirstReadsTheFileBeforeTheFallback`、`TestFileFirstPrefersTheFileWhenTheKeychainIsNewer`（钥匙串较新仍用文件）；真机 `skmr auth status` 输出「凭据来自 …/credentials-43328c44e10d」 |
| T5 | 部分（单元） | `TestASuccessfulKeychainWriteTombstonesTheWritePath` 覆盖零字节墓碑与后续读取路径；真机钥匙串写入与沙箱内失败未跑 |
| T6 | 通过（单元） | `TestFileFirstClearClearsBoth`；写入路径缺失、旧副本仍在时 `TestFileStoreClearClearsTheOlderCopy`；零字节墓碑由 `TestClearingTruncatesAndKeepsTheFile` 覆盖（只读 `older` 未单独构造） |
| T7 | 通过 | WorkBuddy 真机：沙箱行「客户端没有自报」。Claude Code 真机（`claude -p --settings`，`sandbox.enabled=true`）：沙箱行「注意 SANDBOX_RUNTIME=1 CLAUDECODE=1」，并给出按客户端的放行写法 |
| T8 | 部分 | 登录输出已改为「明文文件，仅本人可读」，`auth status` 无重复词；没有重跑一次真登录 |
| T9 | 通过 | 真机 WorkBuddy，两次：① 2026-11-11 21:00 沙箱内 `--fix`（T15 同一次）写入 `sandbox.extraAllowWrite` 与两条 `action:"allow"` 规则，其余键未变（与备份逐键比对）；② 21:28–21:29 在**终端**（非沙箱，`doctor` 沙箱行「客户端没有自报」）跑 `--fix --undo` → `--fix`：先撤销掉路径与两条规则，再重新写入，退出码 0，最终路径与两条规则都在。终端运行按 §环境与数据 隔离（`CLAUDE_CONFIG_DIR` 隔离、`CODEX_HOME`/`COPILOT_HOME` 指向不存在的路径），真实 HOME 下只有 WorkBuddy 被写。备份：`~/.workbuddy/settings.json.skillmaster.bak` |
| T10 | 部分 | 临时 `HOME` 的 Claude Code 副本：写入、幂等、`--undo` 通过；真机未跑 |
| T11 | 通过（单元） | `TestApplyWorkBuddy` / `ClaudeCode` / `Gemini` / `Cursor` / `Devin` / `Zed` / `Copilot` / `Codex`（每家都断言第二次 apply 无变更与 undo）；`TestSwitchesAloneAreWritten`（含同值 undo）、`TestUndoRemovesNewEmptyObjects`、`TestZedJSONCIsAccepted`、`TestCodexNewSchemeIsNotMixed`、`TestWorkBuddyDenyRuleIsReported`、`TestGeminiSandboxOffIsLeftAlone`、`TestZedHostsSkipLocalhost`、`TestNonArrayKeyIsLeftAlone`、`TestBackupKeepsTheFirstCopy`、`TestSymlinkedConfigStaysASymlink`、`TestEmptyHostIsSkipped` |
| T12 | 通过（单元） | 上述第二次 apply、同值 undo 与空对象断言；临时 `HOME` 实测 |
| T13 | 部分 | `TestBadFileIsLeftAlone`（坏 JSON，sha256 不变）、`TestBadTOMLIsLeftAlone`（坏 TOML）；「打印片段」由 `printFragment` 实现，未单独断言 |
| T14 | 部分 | `TestNothingInstalled`（不写任何文件）+ `TestNoConfigSurfaceLinesNameTheClients`（指引里点名各家）；文案未在真机跑 |
| T15 | 通过（终端半用隔离配置） | 沙箱内（`sandbox.enabled=true`，`SANDBOX_RUNTIME=1`）跑 `doctor --fix`：写 Claude 配置时 `operation not permitted`，打印文件开头与「在你自己的终端里跑一次：skmr doctor --fix」，退出码 1；同一次里 WorkBuddy 写成功（沙箱实际放行了它），并顺带写入 Codex / Copilot 的真实配置（Codex 用 `config.toml.skillmaster.bak` 逐字节恢复，Copilot 删除新建的 `settings.json`）。终端半：非沙箱 shell 里用隔离的 `CLAUDE_CONFIG_DIR`（`CODEX_HOME`/`COPILOT_HOME` 指向不存在的路径）跑 `--fix`，退出码 0，隔离配置写入 `allowWrite`、`allowedDomains` 与 `allowLocalBinding`；真实 `~/.claude/settings.json` 的 sha256 未变（所以它是终端路径验证，不是 T10 的真机写入）。单元侧 `TestFixFailure` / `TestFailedChanges` / `TestDescribeChangeFailure` 覆盖失败聚合与描述 |
| T16 | 通过 | 2026-11-12，本地产物 + 临时 `HOME`：pty 与无 tty 各跑一次 `install.sh`，都不出现 `[Y/n]`、不读 `/dev/tty`，只打印 `--dry-run` 与 `--fix` 命令，配置保持 `{}`；提示只出现客户端名，没有 JSON/TOML 键名。之后手动运行 `doctor --fix` 才写入（写入行为由 T9 覆盖） |
| T17 | 未执行 | 需要 Claude Code / Codex 真机 |
| T18 | 未执行 | 需要禁用 bypass 的模拟配置 |
| T19 | 未执行 | 需要带脚本的 skill 与 Claude Code 沙箱 |
| T20 | 通过 | `go test ./...` 退出 0；`go vet ./...` 无输出 |

**实现侧额外验过的**：

- 生产地址注入后 `doctor --fix --dry-run` 输出 `~/Library/Application Support/skillmaster、console.skillmasterai.com、dl.skillmasterai.com`（两条 `-ldflags`）。
- 临时 `HOME` 的 `--fix` → `--fix` → `--undo` 三步：第二次「已是最新」，`--undo` 只移除数组条目；`allowLocalBinding` 与空数组留下，已在方案里写明。

## 已知问题

本版范围内的未修缺陷。范围外的历史缺陷写 `known-issues.md`（本版尚未创建）。

- **CLI 登录在 3 秒内无人点击完成，授权动作未核实**（未验证）：2026-11-11 20:56 的 WorkBuddy 登录，浏览器授权没有观察到点击，控制台页面也没有自动提交逻辑；`refresh_token` 为空是本版预期（新版登录不写 refresh token），不是异常线索。需要服务端登录设备/审计确认那次授权是谁、以什么动作完成的。它不影响 T2 已验的那一半（凭据落 0600 文件、无钥匙串审批），但在确认前不要把「登录成功」当作完整证据。
