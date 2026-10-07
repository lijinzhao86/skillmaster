# ADR 0025 · CLI 的凭据存哪：keychain 优先，明文文件兜底并告知

- **状态**：已采纳
- **日期**：2026-10-02
- **依赖**：[ADR 0002](0002-gateway-skill-and-cli.md)（令牌不进模型上下文、不进命令历史）、[ADR 0011](0011-server-and-cli-stack.md)（Go，keychain 可用且无 CGO）、[ADR 0024](0024-refresh-replay-grace-window.md)（每次刷新都回写）
- **被扩展**：[ADR 0030](0030-the-fallback-credential-is-per-server.md)（正文不变；兜底文件从 `credentials` 改成 `credentials-<server 的哈希>`，与本来就是按 server 分键的钥匙串对齐）

## 背景

CLI 登录后拿到一张 refresh token（长命）与一张 access token（短命），它们必须存在本机某处。

[ADR 0002](0002-gateway-skill-and-cli.md) 已经先定了一条约束：**令牌不能进模型上下文、不能进命令历史**。所以它不能当命令行参数传、不能放环境变量——只剩「一个 CLI 自己读的存储」这一个形状。

三个选项：

1. **只允许 keychain**，拿不到就拒绝登录；
2. **统一用一个 0600 的明文文件**（像 AWS CLI 的 `~/.aws/credentials`）；
3. **keychain 优先，拿不到就回落到明文文件**。

关键事实：`zalando/go-keyring` 的后端是 macOS Keychain、Windows 凭据管理器、以及 **Linux 的 Secret Service（走 D-Bus）**。Linux 那一条需要一个跑着的 Secret Service 提供方**和一个 session D-Bus**——**远程 SSH 的开发机、容器、精简的服务器上都没有**。而那正是 [ADR 0007](0007-self-built-oauth-as.md) 明确说要支持的场景（「无浏览器、远程 SSH 无 X11 转发、无浏览器的容器环境」）。

## 决定

**选 3。** keychain 优先；拿不到就落到 `~/.config/skillmaster/credentials`（权限 `0600`）——**并在登录结束时明确告诉用户存在哪、是不是明文**。

## 理由

**为什么不是 1（只允许 keychain）。** 它恰好在最需要的地方失效：远程 SSH 与容器。那类机器上的用户会被彻底挡在外面，而「在远程开发机上用 CLI」是真实需求，不是边角情况。

**为什么不是 2（统一明文文件）。** 那是白白放弃 macOS 与 Windows 上更好的一层。钥匙串不只是「加密了」——**macOS 还能限制「只有那个签名过的 CLI 能读」**，明文文件没有这层。文件权限只挡得住同一台机器上的其他用户，**挡不住以你的身份运行的程序**——包括 agent 顺手一条 `cat`。有更好的地方就该用更好的地方。

**为什么「告知」是决定的一部分，而不是礼貌。** 回落时盘上确实有一份明文密钥，用户有权知道。**静默降级是这里最容易犯的错**——用户以为在钥匙串里，其实在盘上。参考做法：CircleCI CLI 专门有一个类型就是为「让调用方能打印准确的位置」；tokenstash 把明文那一档明确标注「仅 CI」。

**代价说清楚**：回落路径上，任何能读你 home 目录的进程都能拿到那张 refresh token。

## 后果

- **登录结束的输出必须给出具体位置**：「凭据已存入系统钥匙串」或「凭据已写入 `<路径>`（明文，权限 0600）」。不能是一句含糊的成功提示。
- **这个存储是被频繁改写的**（[ADR 0024](0024-refresh-replay-grace-window.md) 的轮换意味着每次刷新都回写），所以写入必须**原子**，并且要有那把锁——不能写到一半被另一个进程读到。
- **`skillmaster logout` 要清两处**（钥匙串与兜底文件），不能只清这次用的那个：用户完全可能在两种环境下都用过。
- **CI 不走这条路。** 它没有 keychain 也没有人——密钥来自 CI 自己的 secret store（环境变量注入）。那是运维的事，不是这个存储的事。
- **参考的形态**：Claude Code（OS keychain / `~/.claude`）、Codex（`~/.codex/auth.json` / keyring）、gcloud、Stripe、Terraform、Vercel 都是「keychain 优先、明文兜底」。反面例子是 Cline（VS Code 扩展）：默认明文 JSON，且跟着 VS Code Settings Sync 上云——那是要避免的形态。
