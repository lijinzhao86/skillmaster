# 主流 Agent 的沙箱与「第三方 CLI 怎么申请权限」

> **核对日期**：2026-11-11。**用途**：回答「一个第三方 CLI 跑在这些 agent 的沙箱里时，怎么申请文件写入与网络访问」。
> **它不下决定**：产品取舍在 `docs/decisions/`，某一版做到哪在 `docs/versions/`。
> **口径**：官方文档与官方仓库源码优先；每条结论带出处。**查不到一手依据的标「未核实」，不写成结论。**

## 状态图例

| 标记 | 含义 |
|---|---|
| **实测** | 在本机亲手跑出来、有审计日志或命令输出 |
| **文档核实** | 官方文档原文逐条核对 |
| **源码核实** | 官方仓库源码或 JSON Schema 逐条核对 |
| **未核实** | 查过但没有一手依据，只作线索 |

## 三句话结论

1. **「沙箱」实际是两层：审批层与隔离层。** 审批层决定「这条命令让不让跑」；隔离层决定「跑起来以后能碰什么」。Cline、Roo Code、Aider、Amp、旧 Windsurf 只有审批层；Claude Code、Copilot CLI、Codex、Gemini、Cursor、Devin、Zed、OpenHands、DSH、GrokBot 有真正的隔离层。
2. **没有一家允许「子进程自己弹出权限申请」。** 权限面全在宿主手里：要么是用户级配置文件，要么是宿主自己的审批弹窗。第三方 CLI 只能（a）改写宿主配置，或（b）失败时打印一段能直接粘贴的片段。
3. **多数宿主的沙箱把「自己的配置文件」也设成写保护。** 所以「第三方 CLI 在沙箱里给自己授权」普遍不成立；授权必须由**沙箱外的安装器或用户**写。只有 WorkBuddy 实测可以让沙箱内的 agent 写（代价是一次审批）。

## 一句话选型

- **要自动写配置**：WorkBuddy（实测可写，一次审批）、Claude Code/Copilot/Cursor（键已核实，文档写明配置文件在写保护路径，必须**在沙箱外**写）、Devin/Zed（键名有官方出处；Devin 的 `Write(glob)` 层级未逐字核实、Zed 的沙箱内可写性未核实，按沙箱外处理）。Codex 旧式与 Gemini 也能写，但网络侧只有总开关，没有域名白名单；Codex 新式 `[permissions.*]` 有域名白名单。
- **只能打印片段**：Cline、Roo Code、Aider、Amp、旧 Windsurf——它们没有「按路径/域名放行」的键，只有命令级审批。
- **没有完整配置面**：GrokBot（网络白名单仅 Enterprise 面板）、OpenHands（只有挂载，没有域名白名单）、DSH（只有文件键 `additionalWritableRoots`，没有网络键）。

---

## 一、有隔离层、且能配「写路径 / 网络」的客户端

### 1.1 总表

| 客户端 | 隔离机制（默认） | 配置文件 | 文件写键 | 网络键 | 回环 | 沙箱内能改自己的配置吗 |
|---|---|---|---|---|---|---|
| **Claude Code** | macOS Seatbelt / Linux bubblewrap+socat（默认关） | `~/.claude/settings.json` | `sandbox.filesystem.allowWrite` | `sandbox.network.allowedDomains` | `sandbox.network.allowLocalBinding`（macOS 有效，Linux 无效） | **不能**：`.claude` 配置、`.credentials.json` 是写保护路径 |
| **GitHub Copilot CLI** | MXC：Seatbelt / Bubblewrap / ProcessContainer（默认关） | `~/.copilot/settings.json` | `sandbox.userPolicy.filesystem.readwritePaths`（只收绝对路径） | `sandbox.userPolicy.network.allowedHosts`（不能带端口） | `sandbox.userPolicy.network.allowLocalNetwork` | **不能**：sandbox 设置只收用户级，且不在沙箱可写范围 |
| **Codex CLI** | Seatbelt / bubblewrap+seccomp（默认 `read-only`） | `$CODEX_HOME/config.toml` | `sandbox_mode="workspace-write"` + `[sandbox_workspace_write].writable_roots`，或 `[permissions.<profile>.filesystem]` | `[sandbox_workspace_write].network_access`，或 `[permissions.<profile>.network]` | `[permissions.<profile>.network].allow_local_binding`；旧式无此键 | **未核实**；`auth.json` 默认可读不可写，配置目录不建议放开 |
| **Gemini CLI** | Seatbelt / Docker/Podman / bwrap（默认关） | `~/.gemini/settings.json`；工具级 `~/.gemini/policies/sandbox.toml`（源码能力） | `tools.sandbox.allowedPaths`，或 `[commands.<cmd>].allowed_paths` | `tools.sandbox.networkAccess`，或 `allow_network`（无域名白名单） | 整进程 macOS `permissive-open` 默认允许 bind；Linux 容器用 `host.docker.internal` | **较少**：整进程 Docker 不挂载 `~/.gemini` |
| **Cursor** | Seatbelt / Landlock+seccomp（随 Run Mode） | `~/.cursor/sandbox.json` | `additionalReadwritePaths` | `networkPolicy.allow`（默认 deny） | **默认封，且没有窄例外**；只能 `default:"allow"` 宽口子 | **不能**：`.cursor/*.json` 永远不可写 |
| **Devin Desktop**（原 Windsurf） | Seatbelt / bwrap+seccomp（CLI 默认关，要 `--sandbox`） | `~/.config/devin/config.json` | `permissions` 的 `Write(glob)` | `sandbox.allowed_domains`（仅 `--sandbox` 时生效） | 文档未写，**未核实** | **未核实** |
| **Zed** | Seatbelt / Bubblewrap（1.14 起默认开） | `~/.config/zed/settings.json` | `agent.sandbox_permissions.write_paths` | `agent.sandbox_permissions.network_hosts` | **根本不允许**：源码拒绝 IP 与 localhost | **未核实**；沙箱内 keychain 不可用（源码推断） |
| **OpenHands** | Docker 容器（默认） | 环境变量 + Settings UI | 无路径键；靠 `SANDBOX_VOLUMES` 挂载 | **无域名白名单**；只有 `AGENT_SERVER_USE_HOST_NETWORK` | 容器内用 `host.docker.internal` | 不适用 |
| **DSH（DeepSeek Harness）** | macOS Seatbelt / Linux bwrap+Landlock（部署默认 `read-only`） | `$DSH_HOME/cordis.yml`（完整路径未核实） | `additionalWritableRoots`（绝对目录，仅 `workspace-write` 下生效） | **没有网络键**（官方明说网络不在沙箱词汇表内） | 不治理网络，回环不被 DSH 阻止 | 不适用 |

> **WorkBuddy 不在本表**：它没有可文档核实的官方键名，结论来自本机实测，单列在 §3.1。

### 1.2 逐家要点

**Claude Code**
- 沙箱默认关；`sandbox.enabled: true` 才开。只覆盖 Bash/子进程，不覆盖内置 Read/Write/MCP/hooks。
- `allowWrite` 是路径数组；受保护路径即使落在可写目录内也仍然禁写：`.claude` 的 settings/skills/agents/commands/hooks、`.mcp.json`、`~/.claude.json`、`.credentials.json` 等。
- `allowedDomains` 可带端口；`*.example.com` 匹配子域；`*` 匹配所有 host。
- 网络是 HTTP(S) 代理模型：裸 SSH、多数数据库驱动、UDP/QUIC 即使域名在白名单也会失败。
- Linux/WSL2 每条命令有私有 loopback，`allowLocalBinding` 无效；要连宿主 localhost 只能 `sandbox.excludedCommands` 出沙箱。
- 出处：[sandboxing](https://code.claude.com/docs/en/sandboxing)、[settings-reference](https://code.claude.com/docs/en/settings-reference)。键名本次由官方 settings 索引复核。

**GitHub Copilot CLI**
- 沙箱默认关；开启后出站网络默认开、本地网络默认关。
- `readwritePaths` 只收绝对路径，不支持通配符；`allowedHosts` 不能带 scheme/path/端口，`*.example.com` 不含根域。
- 一旦有任何 Allow host 规则，未匹配的 host 就被阻止——要列全 API/CDN/认证域。
- macOS Keychain 默认关，要 `sandbox.userPolicy.seatbelt.keychainAccess: true`，且只能写在用户 `settings.json`。
- 出处：[CLI config dir reference](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-config-dir-reference)、[configuring local sandbox settings](https://docs.github.com/en/copilot/how-tos/cloud-and-local-sandboxes/configuring-local-sandbox-settings)。

**Codex CLI**
- 默认 `read-only`；有 project trust 决策时推导为 `workspace-write`。`network_access` 默认 `false`。
- 两套写法：旧式 `sandbox_mode` + `[sandbox_workspace_write]`；新式 `default_permissions` + `[permissions.<profile>.*]`。**两套不要混写**。
- `writable_roots` 是绝对路径数组；`[permissions.<profile>.network.domains]` 支持 `*.` / `**.`，全局 `*` 会被拒绝。
- `cli_auth_credentials_store` 默认 `file`，另有 `keyring`/`auto`/`ephemeral`——这是「默认写文件」的现成先例。
- **沙箱里的 keychain 不是无条件可用**：`seatbelt_base_policy.sbpl` 没有放行 `com.apple.SecurityServer`，只有 `seatbelt_network_policy.sbpl` 里有；而那份策略只在 `network_access = true` 或 managed proxy 时才附加。源码级结论，未在沙箱内实测 keychain 读写。
- 状态：**源码核实（子代理；本次二次复核超时）**。出处：[config_types.rs](https://github.com/openai/codex/blob/d9a32c178951695f365faa36a39d542e06a96e4f/codex-rs/protocol/src/config_types.rs)、[config.schema.json](https://github.com/openai/codex/blob/main/codex-rs/core/config.schema.json)。

**Gemini CLI**
- 两套独立机制：整进程沙箱（`tools.sandbox`）与工具级沙箱（`security.toolSandboxing` + `~/.gemini/policies/sandbox.toml`），默认都关。
- `sandbox.toml` 是**源码能力，官方 `docs/` 未记录**；一旦存在会整体替代内置默认，三个 mode 都要写全。
- Linux 容器里 `allowedPaths` 是只读挂载；要可写必须 `SANDBOX_MOUNTS=...:rw`。
- 没有域名白名单；只有网络总开关或代理命令。
- 出处：[sandbox docs](https://github.com/google-gemini/gemini-cli/blob/e5d88fb2c036ce7e0c27131469cd963221b9d9e7/docs/cli/sandbox.md)、[configuration](https://github.com/google-gemini/gemini-cli/blob/e5d88fb2c036ce7e0c27131469cd963221b9d9e7/docs/reference/configuration.md)。

**Cursor**
- 沙箱跟随 Run Mode，没有独立全局开关；只作用于 shell 命令。
- `.cursor/*.json`、`.claude/*.json`、`.vscode/**`、`.git/hooks/**` 永远不可写。
- `networkPolicy.default` 默认 `deny`；`allow` 支持域名、`*.`、CIDR。
- 私有地址（含 `127.x`、`::1`）默认被封；文档没说把 `127.0.0.1/32` 放进 allow 能否覆盖默认封禁（**未核实**）。
- 出处：[sandbox.json reference](https://cursor.com/docs/reference/sandbox)、[run modes](https://cursor.com/docs/agent/security/run-modes)。

**Devin Desktop（原 Windsurf）**
- 2026-06-02 改名；Cascade 在 v3.9.19 移除。CLI 默认**不沙箱**，要 `--sandbox`；企业可强制。
- 文件写不是独立数组，来自 `permissions` 的 `Write(glob)`。
- `sandbox.allowed_domains` 只在 `--sandbox` 激活时生效；`*.example.com` 不含 apex，含 apex 用 `**.example.com`。网络过滤官方标为 unstable。
- 出处：[sandbox](https://docs.devin.ai/cli/sandbox)、[permissions](https://docs.devin.ai/cli/reference/permissions)、[config file](https://docs.devin.ai/cli/reference/configuration/config-file)。

**Zed**
- 1.14 起对所有用户默认开；只覆盖 Agent 的 `terminal` 与 `fetch` 工具。
- `network_hosts` 只接受 hostname 或 `*.`，**拒绝 IP literal 和 localhost**；要访问本机服务只能 `unsandboxed`。
- `write_paths` 的元素可以是裸绝对路径，或 `{requested, resolved}` 对象。
- 沙箱内 keychain 不可用：Seatbelt profile 的 Mach allowlist 没有 securityd（**源码推断，官方未逐字写**）。
- 出处：[sandboxing](https://zed.dev/docs/ai/sandboxing)、[agent.rs](https://github.com/zed-industries/zed/blob/main/crates/settings_content/src/agent.rs)、[allowlist.rs](https://github.com/zed-industries/zed/blob/main/crates/http_proxy/src/allowlist.rs)。

**OpenHands / DSH**
- OpenHands：Docker 是默认真隔离；可写范围由挂载决定，**没有路径白名单键、没有域名白名单**。
- DSH：有文件沙箱（`additionalWritableRoots`），**官方明说网络不在 `SandboxMode` 的词汇表内**，所以不提供域名白名单或出站开关。
- 出处：[OpenHands Docker sandbox](https://docs.openhands.dev/openhands/usage/sandboxes/docker)、[DSH Process Sandbox](https://deepseek-harness.github.io/deepseek-harness/en/reference/subsystems/sandbox.md)。

---

## 二、没有可按路径 / 域名放行的配置面的客户端

| 客户端 | 机制 | 文件写 | 网络 | 结论 |
|---|---|---|---|---|
| **Cline** | VS Code 扩展直接执行；CLI 的 `sandbox mode` 只隔离状态目录 | 没有路径白名单，只有 `editFiles`/`editFilesExternally` 布尔 | 没有网络控制 | **没有配置面**，只能靠宿主审批 |
| **Roo Code** | VS Code 扩展；命令前缀 allow/deny | 没有路径白名单（源码键 `alwaysAllowWrite*`） | 没有网络控制 | 仓库已归档（2026-05-15），适配价值存疑 |
| **Aider** | 直接在本机执行 | 无（`--read`/`--aiderignore` 不是安全边界） | 无 | **没有配置面** |
| **Amp** | 本机执行，默认不审批 | 当前 schema 无写白名单 | 无域名白名单 | 旧 `amp.permissions` 是命令级，不是路径级 |
| **旧 Windsurf/Cascade** | 命令 allow/deny，没有文件系统/网络隔离 | 无 | 无 | 第三方审计原话：「there is no sandbox to escape」 |
| **GrokBot（xAI）** | 每用户一台 Firecracker microVM；同一用户的 Bot 共享电脑 | 无路径白名单 | Network Controls **仅 Enterprise**；自助团队默认 allow-all | 没有本地配置文件可写 |

出处：[Cline auto-approve](https://docs.cline.bot/features/auto-approve)、[Cline CLI](https://docs.cline.bot/cli/cli-reference)、[Roo auto-approving](https://roocodeinc.github.io/Roo-Code/features/auto-approving-actions)、[Aider options](https://aider.chat/docs/config/options.html)、[Amp tools](https://ampcode.com/docs/tools)、[Grok Bot security FAQ](https://docs.x.ai/grok-bot/security-faq)。

> **Roo Code 仓库已于 2026-05-15 归档**（GitHub API `archived: true`）。Aider 最新 release 是 v0.86.0（2025-08-09），近期不活跃。两者按「可能仍在用、但不再演进」处理。

---

## 三、对本产品的直接含义

### 3.1 谁能被自动写配置

| 客户端 | 键已核实 | 沙箱内可写自己的配置 | 结论 |
|---|---|---|---|
| WorkBuddy 5.8.x | **实测**：`~/.workbuddy/settings.json` 的 `sandbox.extraAllowWrite` + `sandbox.orderedRules.network.rules`（规则形状 `{id, source:"user", domain, action}`） | **能**（写时一次审批） | 可自动写 |
| Claude Code | 文档核实 | 不能（`.claude` 受保护） | 必须**沙箱外**写 |
| Copilot CLI | 文档核实 | 不能 | 必须沙箱外写 |
| Codex | 源码核实 | 未核实；不建议 | 沙箱外写 |
| Gemini | 文档 + 源码核实 | 部分（容器不挂载） | 沙箱外写 |
| Cursor | 文档核实 | 不能（`.cursor/*.json` 不可写） | 沙箱外写 |
| Devin | 文档有键名，`Write(glob)` 层级未核实 | 未核实 | 沙箱外写 |
| Zed | 文档 + 源码核实 | 未核实 | 沙箱外写 |
| Cline / Roo / Aider / Amp / 旧 Windsurf | 无隔离层 | — | 没有配置面，只能打印指引 |
| OpenHands / DSH | 有隔离但无域名白名单 | — | 只能打印挂载/可写根指引 |
| GrokBot | 仅 Enterprise UI | — | 只能打印指引 |

**一句话**：除了 WorkBuddy，**自动授权都得在沙箱外跑**（安装器或用户自己的终端）。这否定了「让沙箱里的 agent 给所有客户端开门」的想法。

**WorkBuddy 的写权限有边界（2026-11-11 实测，5.8.6）**：`extraAllowWrite` 允许在放行目录里**写文件内容**（创建、截断、写），也允许把新文件改名到一个**不存在**的目标；但 `rename` **覆盖已存在的文件**会返回 `operation not permitted`。所以凭据与配置的写入必须原地覆盖，不能「写邻居再改名」；安装器升级已有二进制也受同一条限制。

### 3.2 回环决定了「登录能不能在沙箱里做」

| 客户端 | 沙箱内能否 bind/connect 127.0.0.1 |
|---|---|
| WorkBuddy | **实测可以**（今天在沙箱里完成了一次浏览器登录） |
| Claude Code | macOS 需 `allowLocalBinding: true`；Linux/WSL2 **不行**，只能 `excludedCommands` |
| Copilot CLI | 需 `allowLocalNetwork: true`；且本地代理活动时只保证「能监听」，不保证「能连自己」 |
| Codex | 旧式 `network_access=true` 有 outbound；bind 未显式放行。新式需 `allow_local_binding=true` |
| Gemini | 整进程 macOS `permissive-open` 默认允许 bind；Linux 容器用 `host.docker.internal` |
| Cursor | **默认封，且没有窄例外**（只能 `default:"allow"` 宽口子） |
| Zed | **根本不允许**，也没有键能做到 |
| OpenHands / DSH | 容器内 `127.0.0.1` 是容器自己；DSH 不治理网络 |

**含义**：`skmr login` 用的是固定回环端口（[ADR 0028](../decisions/0028-cli-uses-a-fixed-loopback-port.md)）。在 Cursor / Zed / Claude Code Linux 里，**即使目录放行，登录也做不了**——登录必须在沙箱外，或走另一条不依赖回环的凭据路径（API key / device flow）。这与凭据落点无关，是独立的一条。

### 3.3 凭据：文件默认是跨客户端的共同形状

本次调研到的凭据行为：

| 客户端 | 凭据存储 | 沙箱内 |
|---|---|---|
| Claude Code | macOS Keychain，回退 `~/.claude/.credentials.json` | `.credentials.json` 写保护；keychain 行为未核实 |
| Copilot CLI | macOS Keychain（默认关，要 `keychainAccess: true`） | 默认不可用 |
| Codex | `auth.json`（默认 `file`），可选 keyring/auto/ephemeral | 可读不可写 |
| Gemini | OS keychain，回退 `~/.gemini/gemini-credentials.json` | Docker 不挂载 |
| Cursor | 本地存储；macOS keychain 有已知失败，论坛方案 `AGENT_CLI_CREDENTIAL_STORE=file` | 官方未表态 |
| Zed | provider key 存系统 keychain（GUI 进程） | 沙箱内不可用（源码推断） |
| WorkBuddy | **实测**：钥匙串写被重定向到 `.sb-` 副本并需审批；CLI 改用文件 | 文件可用 |
| Devin | 明文 `~/.local/share/devin/credentials.toml` | 默认可读 |

**结论**：**没有一家能把「沙箱内写系统钥匙串」当作可靠路径。** 凭据落文件不是 SkillMaster 一家的妥协，是这批客户端的共同现实。

---

## 四、未核实清单（不要当结论用）

| # | 项 | 为什么没核实 |
|---|---|---|
| 1 | Codex macOS 旧式 `network_access` 下 bind 的实际行为 | 只看到源码生成 `network-outbound`，未运行验证 |
| 2 | Codex 二次复核 | 本次 WebFetch 超时；结论来自子代理的源码核实 |
| 3 | 各客户端「沙箱内能否写自己的配置」 | 除 WorkBuddy（实测）与 Claude Code/Copilot/Cursor（文档写明写保护）外，其余只有推断 |
| 4 | Cursor 能否用 `127.0.0.1/32` 覆盖默认封禁 | 官方文档未说明 |
| 5 | Zed / Gemini / Codex 沙箱内 keychain 的实际可用性 | 只有源码/profile 推断，未实测 |
| 6 | Devin Desktop / Zed 的沙箱内配置文件可写性 | 文档未覆盖 |
| 7 | GrokBot 的 loopback 行为 | 官方文档未写 |
| 8 | DSH `cordis.yml` 的完整 profile 路径 | 官方页未逐字给出 |
| 9 | Devin `Write(glob)` 在配置文件里的准确层级 | 官方文档只说「`permissions` 的 `Write(glob)`」；`permissions.allow[]` 与直接在 `permissions` 下列出两种形状都见过，未逐字核实 |

## 五、出处

一手来源（官方文档/源码）已在上文逐条给链接。二手来源只用于「Roo Code 已归档」（GitHub API）与「旧 Windsurf 无沙箱」（第三方安全审计），已在对应行标注。本文件不引用博客作为键名依据。
