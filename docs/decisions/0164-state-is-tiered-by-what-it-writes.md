# 0164 · 状态按「要不要写」分三层，凭证永远不落项目

- **状态**：已采纳
- **日期**：2026-11-08
- **所属版本**：[`v51-skill-runtime`](../versions/v51-skill-runtime/README.md)
- **依赖**：[ADR 0101](0101-the-cli-never-deletes-files.md)（CLI 不删文件）、[ADR 0038](0038-default-credential-is-an-api-key.md)（凭据来源）

## 背景

CLI 现在往四处写：状态目录（`os.UserConfigDir()`，macOS 是 `~/Library/Application Support/skillmaster`，放凭据的兜底文件与 pin）、系统钥匙串、用户指定的下载目录、以及自己的二进制。

这些写入在 agent 的沙箱里会被拦住，而四家客户端的规则不一样：

- **Claude Code**：写只给工作目录与 `$TMPDIR`（外加 `--add-dir`）；`~/.claude` 下大部分内容是**受保护路径**，配了 `allowWrite` 也写不进；钥匙串读得到、写不进。
- **Codex**：默认 `workspace-write`，写工作区与 `/tmp`；网络默认关闭。
- **Cursor**：默认 `workspace_readwrite`；网络默认 deny。
- **WorkBuddy**：profile 里 `(deny file-write* (subpath "/"))` 再逐个放行；`~/Library/Application Support` 不在白名单，`~/Library/Keychains` 是硬拒绝；**不给关**。

实测证据：WorkBuddy 的审计日志里直接记着 `.../Application Support/skillmaster/pins-...` 的删除被要求审批，以及 `Library/Keychains/login.keychain-db` 的一次 `rejected`。

按「数据活多久」切分（长期 / 中期 / 短期）看似自然，但四家的规则真正的分界是**读宽写窄**：读几乎全开，写只给少数位置。所以分层必须按「这一层要不要写」来切。

## 决定

1. **三层按写权切分：**
   - **长期**（CLI 本体、agent 目录里的 skill 文件）：**项目外**，只有用户在自己的终端里安装 / 更新时写。沙箱内一律只读。
   - **中期**（凭据）：**全局一份**，钥匙串优先，写不进立刻降级到文件；文件位置可被 `SKILLMASTER_HOME` 覆盖。**永远不写进项目目录。**
   - **短期**（下载下来要执行的 skill 代码）：默认落 `${TMPDIR:-/tmp}/skmr/cache/<digest 前 16 位>/`，可用 `--cache-in` 指定别处。
2. **状态目录逐级探测**：`SKILLMASTER_HOME` → 当前实现的位置 → `${TMPDIR:-/tmp}/skillmaster/state`。第一个写得进去的即为本次**写**的家，并把位置说出来；**读**另走一条路——已有的状态在哪个候选目录里就从哪里读，否则沙箱内会把已有凭据与 pin 读成「不存在」。
3. **钥匙串先探测再写**：写失败（含 `Operation not permitted`）立刻降级到文件，不弹窗、不重试。
4. **缓存的未命中不是错误**：目录不在就重新拉；被系统清理掉也照常工作。

## 理由

- **为什么凭证不落项目。** 项目目录会被 `git add`，一次 `.gitignore` 漏写就是一次凭据泄漏；同一个用户在 N 个项目里会有 N 份凭据，换账号时无法判断哪份是活的；而且 WorkBuddy 的 profile 专门 deny 了 `~/.ssh`、`.npmrc`、`.git-credentials`、`.netrc` —— 把 token 挪进项目等于绕开它设的防。
- **为什么短期缓存落 `$TMPDIR`。** 四家唯一共同保证「可读可写可删」的位置就是它；而且不污染用户仓库（`./.skillmaster/` 会出现在 `git status` 里）。代价是可能被系统清理，这与「短期」这一层的定义一致。
- **为什么用探测而不是「识别是哪个沙箱」。** 只有 Codex 与 Cursor 提供环境变量标记，Claude Code 与 WorkBuddy 没有；任何基于「认出客户端」的分支都会在这些地方失效。探测写权是唯一对所有客户端都成立的做法。
- **被否掉的：**
  - **全部放工作区**：凭证会进仓库，见上。
  - **全部放全局**：沙箱里直接不可写，等于 CLI 不能用。
  - **把「让用户配 `allowWrite` / `extraAllowWrite`」作为默认**：那么每个新用户必须先改配置才能用，而「默认能跑」是这一版的目标。配置片段只作为 `doctor` 给出的可选增强。
  - **识别客户端再分支**：见上，两家没有标记。

## 后果

- 短期缓存在系统清理后要重新下载；**首次拉取需要网络**，这一点写进了 PRD 的依赖与前置。
- 同一台机器上，缓存按 digest 去重，跨项目共享；用户看不到「这个 skill 装在哪」时，`doctor` 要能立刻说清。
- `~/.claude/skills` 这类 agent 目录在沙箱内写不了（Claude Code 的受保护路径），所以「装 skill 到 agent 目录」是一个**必须在沙箱外做一次**的动作，产品上要明说。
- Windows 没有 `+x`，且 `%TEMP%` 的清理策略与 unix 不同；这一层的行为差异记在 TD 的开放问题里。
- **`SKILLMASTER_HOME` 指到项目目录里，CLI 不拦。** 「凭据不落项目」是默认路径的性质，不是对所有显式覆盖的保证：一个人把状态目录指进仓库，是他自己的选择，CLI 唯一能做的是不替他做这个选择。默认路径永远不指向项目——那条才是这条规则守的东西。
