# v24-cli-file-footprint · 产品需求

> **最后更新**：2026-10-27
> **所属版本**：`docs/versions/v24-cli-file-footprint/`（`README.md`、`technical-design.md`、`test-plan.md` 在后续阶段落地，本文件是这一版需求的唯一权威）

## 用户与场景

**谁在用**：在带沙箱的 agent 里跑 SkillMaster CLI 的人。具体是两个角色：把 skill 交给 agent 执行的人，以及替这个 agent 配沙箱权限的人。已经在本仓库里出现的沙箱是 Claude Code 的 sandbox 与 WorkBuddy（[v22 PRD](../v22-cli-help-and-prompt/prd.md) 记过后者只读 HOME 的实测）。

**在什么场景下用**：agent 按 skill 里的指示执行 `skillmaster startup` / `skill invoke` / `skill read` / `skill download`。沙箱对工作区之外的每一个文件动作逐次审批，**删除类动作尤其显眼**——它看起来像是 agent 要拿走东西，审批界面会停下来等人。

**现在的替代做法是什么**：把 `$HOME` 整个放给 agent（沙箱形同虚设）、关掉沙箱、或者每一步手动批准。前两个牺牲的正是沙箱存在的理由；第三个让一个本该自主跑完的任务反复停下来等人。三种都不是产品该让人做的选择。

## 事实：现在到底在哪里删文件

以下全部是**非测试代码**，逐处对过代码。这是本版要清零的清单，也是「删除」为什么会在审批里出现的答案。

| # | 位置 | 什么时候发出删除 | 删的是什么 |
|---|---|---|---|
| 1 | `skillmaster-cli/internal/pins/store.go:211-215` | **每次 `invoke` / `startup` 保存 pin 之后** | 临时名 `.pins-*`。`rename` 成功后它已不存在，这次 `Remove` **必然失败**——pin 没有被删，但 unlink 系统调用已经发出去了 |
| 2 | `skillmaster-cli/internal/credentials/file.go:59-63` | 每次写回退凭据文件之后 | 同上，`.credentials-*` |
| 3 | `skillmaster-cli/internal/credentials/file.go:82-88` | `logout` | 凭据文件（命令的目的就是这个） |
| 4 | `skillmaster-cli/internal/credentials/responsive.go:195` | `login` 成功写进钥匙串之后 | 回退文件里那份旧副本 |
| 5 | `skillmaster-cli/internal/credentials/responsive.go:204,217,230` | `logout` | 回退文件 |
| 6 | `skillmaster-cli/cmd/skillmaster/download.go:87` | `skill download` 结束 | `$TMPDIR` 里的 zip |
| 7 | `skillmaster-cli/cmd/skillmaster/download.go:219` | `skill download` 解包开始 | `<target>.partial`（先删后建） |
| 8 | `skillmaster-cli/cmd/skillmaster/download.go:106,111` | 解包或放置失败 | `<target>.partial` 整棵树 |
| 9 | `skillmaster-cli/cmd/skillmaster/download.go:320,328` | 目标已存在且为空时替换 | 备份目录 |

第 1 条是日常命中的那一条：**一次 `invoke`（或 `startup`），哪怕这次什么都没变，也会亮一次删除**。这是本版要解决的第一个问题。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | **CLI 二进制不再发起任何文件删除**：`unlink` / `rmdir` 类系统调用（`os.Remove`、`os.RemoveAll`、`syscall.Unlink`、`syscall.Rmdir` 等）在非测试代码里清零，**失败路径也不删**。上表九处全部按各自的替代规则改写 | CLI |
| 2 | pin 改为**一个 skill 一个文件**：`<状态目录>/pins/<server 哈希>/<sha256(地址)前 16 位>.json`，文件内含 `address`、`kind`、`name`/`digest`。**原地覆写**，不用临时文件。顺带消灭整表读—改—写的丢更新（两个 agent 并发 invoke **不同** skill 时，后写者会覆盖先写者的记录） | CLI |
| 3 | 文件内的 `address` 与要查的地址不一致时**拒绝**，不把别的 skill 的钉子当作答案 | CLI |
| 4 | 同一版本**重复 invoke 不重写** pin（内容不变就不落盘） | CLI |
| 5 | 凭据：保存改**原地覆写**；`logout` 改为**清空**（截断为 0 字节＝没有凭据），不删文件。钥匙串条目仍然调用系统的删除 API——那不是文件，也不经过文件系统 | CLI |
| 6 | `skill download` 去掉全部删除：解包在内存里完成，写入一个本次独有的 staging 目录，成功后 `rename` 到位；失败时**保留 staging** 并在错误句里点名它的路径，由人来决定删不删 | CLI |
| 7 | 旧的 `pins-<server 哈希>` 单文件保留为**只读回退**（新文件不存在时查它），新写入不再碰它，也永不删除。新旧路径不冲突：旧的是文件 `pins-<哈希>`，新的是目录 `pins/<哈希>/` | CLI |
| 8 | **可执行检查**：一个 `go/ast` 测试，扫描本模块所有非测试 `.go` 文件，断言没有删除原语；随 `go test ./...` 在 CI 里跑 | CLI + CI |

**上表九处删除各自的替代规则**（细节进 `technical-design.md`）：第 1、2 条（临时名）→ 原地覆写，不再有临时文件；第 3、4、5 条（凭据）→ 清空或原地覆写；第 6–9 条（下载）→ 内存解包 + 独有 staging，成功 `rename`，失败保留并点名。

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 读命令零写（pin 完全不落盘） | 产品负责人 2026-10-27 定：写可以接受；要「记住版本」必须落一次 | 不做 |
| 2 | 状态目录可搬迁（`SKILLMASTER_HOME`）与 `skillmaster paths` | 本版只解决**删除**。搬迁解决的是读/写的审批，是另一件事 | 未定，另开一版评估 |
| 3 | macOS 钥匙串的开关（沙箱里跳过钥匙串） | 钥匙串的授权弹窗不是文件删除；且本版不动凭据来源的优先级 | 未定，另开一版评估 |
| 4 | 给已不存在 / 已删除的 skill 清理 pin 文件 | 清理本身就是删除，与本版的目标直接冲突。陈旧文件不会被读到——除非地址被重新使用，而那时版本不存在会给明确的错 | 不做 |
| 5 | 清理旧的 `pins-<server 哈希>` 单文件 | 它是只读回退，删它需要删除 | 不做 |
| 6 | `install/install.sh` 与 `install/install.ps1` 里的 `rm -rf` / `Remove-Item` | 它们不是 CLI 二进制，是一次性安装脚本；本版的不变量只约束运行期的 `skillmaster` | 未定：若 agent 内的安装也开始被逐次审批，再单独收 |

**范围里不许出现「后续再看」「视情况而定」**，上面「未定」的三条都写明了触发条件。

## 控制台操作与 CLI 对应

本版**不新增、不改动任何控制台操作**，因此没有需要映射的行（[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 的判据是「新增一处控制台操作就要有一条 CLI 命令」，这里没有新增）。

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | 非测试源码里删除原语出现次数为 **0** | `go test ./...` 里的 AST 测试通过；再**故意加一处** `os.Remove`，确认它会红 |
| 2 | 一组命令跑完后，进程发出的 `unlink` / `unlinkat` / `rmdir` / `rmdirat` 系统调用数为 **0** | Linux 上 `strace -f -e trace=unlink,unlinkat,rmdir,rmdirat` 跑：`invoke`（同一版本连续两次）、`read`（文本与二进制）、`startup`、`download` 成功一次、`download` 失败一次。**`login` 与 `logout` 不在这一组里**：前者必须有浏览器，脚本跑不完，它们的凭据文件路径由验收 1（AST）与验收 4（行为）覆盖 |
| 3 | 在**目标沙箱**里跑同一组命令，审批里不再出现删除 | Claude Code 的 sandbox 与 WorkBuddy 各跑一次，人看截图/录屏 |
| 4 | `logout` 之后：凭据文件**仍然存在且为 0 字节**；`skillmaster auth status` 说未登录；随后 `login` 正常 | 自动化 + 手工 |
| 5 | 同一版本连续 `invoke` 两次：第二次**不修改** pin 文件（内容与 mtime 都不变） | 自动化 |
| 6 | pin 文件内含 `address`；把 `address` 改成另一个 skill 后，`read` **拒绝**而不是返回那一版 | 自动化 |
| 7 | 升级不丢钉：只存在旧的 `pins-<server 哈希>` 单文件时，`read` 仍按它里面的钉子读；新的 `invoke` 写入后旧文件**未被动过** | 自动化 |
| 8 | `download` 成功：目标目录的文件集合与字节数和改前一致，工作区没有残留 staging | 自动化（对照既有 download 测试） |
| 9 | `download` 失败：目标目录没有被半成品污染；staging 目录保留，且错误信息里点出它的路径与「确认后请自行删除」 | 自动化（注入写失败） |
| 10 | `startup` 在只读状态目录下照常给出正文，且不发起删除 | 自动化（沿用 v22 的只读目录夹具） |

**成立条件**：第 1、2、3 条**有一条不过就不算交付**。它们是本版的存在理由；其余各条是随之而来的行为契约。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 删除系统调用数 | 每组命令（上表第 2 条那组）发出的 unlink/rmdir 类调用总数，口径是 `strace` 的计数 | 见验收 2 | 0 |
| 沙箱审批里的删除条数 | 同一组命令在目标沙箱里触发的、类型为删除的审批条数 | 见验收 3 | 0 |
| 重复 invoke 的写入次数 | 同一版本连续 invoke 两次，pin 文件被写的次数 | 见验收 5 | 1（第一次）→ 0（第二次） |

## 依赖与前置

- **外部依赖**：无。不需要服务端改动，不需要控制台改动，不需要新接口。
- **内部前提**（开工前必须落定）：
  - **新 ADR：CLI 永不删除文件**。这是「三年后还成立吗」答「是」的跨版本取舍，按 [`docs-architecture`](../../../.claude/skills/docs-architecture/SKILL.md) §2.2 必须独立成条；它把 [ADR 0025](../../decisions/0025-cli-credential-storage.md) 里「`logout` 要清两处」的兜底文件那半从「删掉」改为「清空」（钥匙串那半不变），并给 [v9 TD](../v9-skill-transfer/technical-design.md) 的「失败时删掉临时目录与临时 zip」划出替代规则。
  - [ADR 0035](../../decisions/0035-the-pin-lives-on-the-client-machine.md) **不变**：pin 仍在客户端机器上；本版只改它的存储形状（一 skill 一文件）。
  - [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §决定 7 **不变**：`logout` 仍然不调用服务端。
- **排除了什么前置**：不需要为「不删」引入新的存储后端或服务；不需要改 `skill read` / `skill invoke` 的输出形状——本版对调用方可见的变化只有失败时的残留目录与 `logout` 之后文件仍在。
