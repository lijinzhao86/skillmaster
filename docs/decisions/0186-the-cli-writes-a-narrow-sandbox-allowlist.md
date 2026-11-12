# 0186 · CLI 在安装时改写客户端的沙箱配置，范围限定为 SkillMaster 自己的目录与域名

- **状态**：已采纳
- **日期**：2026-11-11
- **修订**：[ADR 0167](0167-sandbox-compatibility-is-capability-based.md) §5「只打印、不改配置」（旧文正文不改）
- **所属版本**：[`v62-cli-sandbox`](../versions/v62-cli-sandbox/README.md)

## 背景

[ADR 0167](0167-sandbox-compatibility-is-capability-based.md) §5 决定「CLI 只打印配置，不改用户的设置」，理由是用户可能被公司策略管理、而且「谁改了我的机器」难解释。

2026-11-09 用户报告（原 `docs/current/TODO.md` 第 23 条「沙箱内自动放行 SkillMaster 目录的读写」，已并入本版）：在 agent 沙箱里，登录、写 pin、连平台**每一步都要人工审批**。2026-11-11 调研 [`docs/research/agent-sandboxes.md`](../research/agent-sandboxes.md) 确认了三件事：

- **没有一家允许子进程自己弹权限框。** 权限面在宿主手里：要么是用户级配置，要么是宿主自己的审批。
- **多数宿主把「自己的配置文件」也设成写保护。** Claude Code 的 `~/.claude`、Cursor 的 `.cursor/*.json` 等在沙箱内不可写；自动授权必须在**沙箱外**做。
- **只有 WorkBuddy 实测可以让沙箱内的 agent 写自己的配置**（一次审批）。

可选做法：

- **A 维持只打印。** 用户要自己改 JSON/TOML。
- **B CLI 在安装/首次运行时，经用户一次同意，改写客户端的白名单。**
- **C 让 agent 直接改客户端配置，不经 CLI。**

## 决定

**选 B，并把范围限死。**

1. `skmr doctor --fix` 默认在**沙箱外**跑（WorkBuddy 是实测例外：沙箱内可写，写时一次审批），写入的内容只有三类：**状态目录（读 + 写）**、**平台与更新域名**、以及**为放行所需的开关**——Claude Code 的 `sandbox.network.allowLocalBinding`（登录回环）、Codex 在默认 `read-only` 时的 `sandbox_mode="workspace-write"`（**仅当 `config.toml` 已存在且非空**；不存在或为空时不新建，见 §后果。**这会让 Codex 的工作区本身可写**，`writable_roots` 只是把状态目录加进去，代价见 §后果）。**例外是只有网络总开关的客户端**：Codex 的旧式 `[sandbox_workspace_write]` 与 Gemini 的 `tools.sandbox` 没有域名白名单，只能写 `network_access` / `networkAccess = true`，代价是放开该客户端的全部出站网络（写进 §后果）。别的都不动。
2. 覆盖有权限键的客户端：WorkBuddy、Claude Code、Codex、Gemini、Cursor、Devin Desktop、Zed、Copilot CLI（Codex 的 `config.toml` 不存在或为空时只报告，不新建）。没有可写权限键的（Cline、Roo Code、Aider、Amp、旧 Windsurf）与没有完整配置面的（OpenHands、DSH、GrokBot）只打印指引。
3. 幂等；`--undo` 移除**与 SkillMaster 写入值完全一致的条目**（WorkBuddy 的域名规则另按 `id` 以 `skillmaster-` 开头识别；纯字符串条目无法区分是谁加的，这一条如实写在文档与 `--undo` 输出里）；改前备份只建第一份、之后不再覆盖（**零字节的失败备份例外**，下次重写）；配置解析失败就拒绝并打印片段。
4. **不主动申请删除权限**；`skmr` 从不删文件（[ADR 0101](0101-the-cli-never-deletes-files.md)）。
5. 安装脚本与 `startup` skill 只对**管理命令**请求「单条出沙箱」（`doctor --fix` / `login` / `logout` / `update`）；**`skmr run` 永不出沙箱**——它执行 skill 代码。
6. 用户必须显式同意一次：agent 在跑安装时先用 `AskUserQuestion` 问，同意后才运行 `doctor --fix`；安装器只打印改动与命令，**不读终端**（`/dev/tty` 在 agent 里答不了，失败也不能当成「是」）。人在终端运行 `doctor --fix` 即视为同意。企业策略禁止出沙箱时，退回「用户在自己的终端跑一次」。

## 理由

- **A 被否：它对技术用户成立，对非技术用户不成立。** 让用户去改 JSON/TOML、理解每个客户端的键，本身就是门槛；而产品的主要用户正是在 agent 里用的人。
- **C 被否：没有统一入口、不可撤销、不可审计。** 而且 Claude Code / Cursor 等把配置文件设为写保护，agent 在沙箱里根本改不了。
- **B 被采纳：一个显式命令、一次同意、可重复、可撤销。** 安装器与 skill 只是它的调用者，不是另一套逻辑。
- **为什么范围这么窄：** 放行写权限等于让 agent 能改承载凭据的目录。只放 SkillMaster 自己的目录与平台/更新域名，不碰工作区外的其他路径，不碰删除，不碰 `skmr run`——这是把「一次确认」的代价压到最小。**Codex 旧式是唯一的例外**：它的 `workspace-write` 没有「只放某个目录」的写法，代价与撤销边界见 §后果。

## 后果

- **CLI 会写别的应用的配置文件，这是新的信任面。** 写错会破坏用户的配置。缓解：改前备份、幂等、`--undo`、解析失败拒绝、只增不删。
- **有些客户端的「写」键可能把删除一并放开。** Claude Code 的 `allowWrite` 是否包含 unlink **未实测**；如果包含，代价记录在这里：状态目录下的文件可以被沙箱内命令删除。`skmr` 自己从不删。
- **Codex 旧式与 Gemini 的网络侧只有总开关，没有域名白名单。** 两者都只能写 `network_access` / `networkAccess = true`，等于放开该客户端的全部出站网络。这不是「只放平台与更新域名」；`doctor --fix` 的输出与 `--dry-run` 如实说这一点。
- **Codex 旧式的 `sandbox_mode="workspace-write"` 让整个工作区可写。** `writable_roots` 只是把状态目录追加进去，它不是白名单，也不会把工作区缩小。`--undo` 只移除数组条目，**不还原 `sandbox_mode` 与 `network_access`**；安装器的同意文本与 `doctor --fix` 的输出都明说这一点（否则人会以为「撤回」把工作区也关回去了）。
- **不新建会改变沙箱开关的配置。** Gemini 的 `tools.sandbox` 不存在时不写入（新建它会把整进程沙箱打开）；Codex 旧式在 `config.toml` 不存在或为空时也不新建（旧式要 `workspace-write` 才能让 `writable_roots` 生效，新建或填一个空文件等于打开工作区可写）；Codex 已经在用新式 `[permissions.<profile>]` 时也不混写旧式键。三者都只报告「未改动」。其余客户端会创建缺失的父对象（例如 Claude Code 的 `sandbox`），但创建父对象本身不打开沙箱。
- **Gemini 的 `allowedPaths` 在 Linux 容器模式是只读挂载。** 本版照写，但写进配置不等于目录可写；要真正可写需要 `SANDBOX_MOUNTS=...:rw`，本版不写这个环境变量，`doctor` 的指引说明这一点。
- **Codex 的 TOML 写回可能重排、丢注释。** 用备份兜底，`--dry-run` 明说这一点。
- **客户端配置与凭据都是原地覆盖写的，不是「写邻居再改名」。** WorkBuddy 5.8.6 实测：沙箱允许写文件内容，但拒绝 rename 覆盖已有文件。代价是写入的「截断→写入」窗口里，并发读可能读到空文件或半截文件；中断也可能留下半截配置。`.skillmaster.bak` 只在原文件已存在时创建（首次新建的文件没有备份）：有备份的从备份恢复；首次新建且写坏的，手工修好或删除该文件后重跑（CLI 不删文件）；凭据可以重新 `login`。
- **企业托管策略可能禁止单条出沙箱。** 那时退回「用户在自己的终端跑一次」；CLI 不硬来。
- **客户端升级可能改键名或语义。** 本版不承诺跨版本自动适配；失败信息必须给出下一步（打开 `doctor` 的片段或文档）。
- **两个 `--fix` 同时跑可能互相覆盖。** 这是读-改-写，没有锁；安装器与 `startup` skill 通常是先后关系，文档明说不要并行。
- **`--undo` 不还原布尔开关。** `allowLocalBinding` / `networkAccess` / `sandbox_mode` 这类被打开的值无法与用户原本的设置区分；`--undo` 只移除条目、不还原开关，本次新建的空父对象会一并清掉，可能留下空数组。这一点写在 `--undo` 的输出与文档里，不假装还原了全部。
- **`--undo` 也会移除用户自己写过的同值条目。** 纯字符串数组没有来源标记，无法把「SkillMaster 加的」与「用户先写了一模一样的」分开；备份只保留第一份改动前的文件。
- **ADR 0167 §5 被本 ADR 修订。** 它的其余部分——能力探测、错误目录、客户端片段——不变。
