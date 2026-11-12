# 0185 · CLI 凭据默认存文件，钥匙串改为可选

- **状态**：已采纳
- **日期**：2026-11-11
- **修订**：[ADR 0025](0025-cli-credential-storage.md)（旧文正文不改）
- **所属版本**：[`v62-cli-sandbox`](../versions/v62-cli-sandbox/README.md)

## 背景

[ADR 0025](0025-cli-credential-storage.md) 决定「钥匙串优先、明文文件兜底」。今天的前提变了：

- **沙箱里写钥匙串不是可靠路径。** 2026-11-11 在 WorkBuddy 5.8.6 实测：沙箱把钥匙串的写重定向到 `login.keychain-db.sb-*` 副本并要审批；点允许写进副本、会话结束就没了，点拒绝 CLI 才改用文件。**两个结果最后都用文件，但用户被迫做一个看不懂的选择。**
- 调研 [`docs/research/agent-sandboxes.md`](../research/agent-sandboxes.md) 显示：Zed 沙箱内取不到系统钥匙串（源码/profile 推断），Cursor 的 macOS keychain 有已知失败，Codex 的 keychain 要网络策略生效才放行 `SecurityServer`（后两条来自论坛与源码，均未实测）。**没有一家能把「沙箱内写系统钥匙串」当作可靠路径。**
- Codex CLI 自己的 `cli_auth_credentials_store` **默认就是 `file`**。

可选做法：

- **A 维持钥匙串优先，只在沙箱里改用文件**：要可靠地知道自己在沙箱里。WorkBuddy 5.8.6 不发任何标记；按客户端猜是 [ADR 0167](0167-sandbox-compatibility-is-capability-based.md) 已否掉的路。
- **B 默认文件，钥匙串变成显式可选**。
- **C 永远文件，不留钥匙串**。

## 决定

**选 B。**

1. 新登录默认写状态目录的 `credentials-<server 哈希>`（0600）。
2. `skmr login --keyring` 让这一次写钥匙串；**写成功后把文件截为零字节**，否则默认读路径仍会先读到旧文件；写失败按 ADR 0025 的兜底写文件。
3. **默认 store** 的读路径先文件；文件没有，再读钥匙串——迁移不需要用户操作。`--keyring` 的那一次登录仍按 ADR 0025 的钥匙串优先读（写进去的那份就是它）。
4. `auth status` 报**实际在用的那份**（`EnvStore.Source()`），不再报存储位置。
5. `logout` 两处都清。

## 理由

- **A 被否：没有可靠的沙箱检测。** WorkBuddy 不发标记；「按客户端识别」在 ADR 0167 里已经被否，因为它随版本漂移、且误判会让每个用户看到错误的提示。
- **C 被否：拿安全换简单，而且没为谁换来什么。** 终端用户（远程 SSH、容器、无浏览器的机器）本来能用钥匙串，一刀切放弃是白白降级。
- **B 被采纳：默认值该跟着现实走。** 沙箱里钥匙串写不了，那就别把它当默认；想加固的人仍然有 `--keyring`；迁移对已有用户透明。

## 后果

- **所有机器的默认凭据变成 0600 明文文件。** 以同一 OS 用户运行的程序——包括 agent 自己与 skill 脚本——都能读。ADR 0025 的告知义务保留：登录结束必须说清位置与是否明文。
- **钥匙串不再是默认。** `--keyring` 的登录在沙箱里可能被直接拒绝（毫秒级、不弹窗），也可能要求一次钥匙串审批（写的是副本）；写不进就落回明文文件并说明，这是选它的代价，写进 `login --help` 的说明。
- **读路径在文件缺失时会碰钥匙串。** macOS 上可能弹一次授权，所以只在文件没有时才碰；`responsiveKeychain` 的 5 秒上限保留。
- **两份并存时以文件为准（默认 store）。** 迁移期的极端情况（旧二进制后写钥匙串）不再用「较新一份」裁决；`--keyring` 的 store 仍是钥匙串优先、并用写入时间裁决两份。`logout` 两处都清，所以不会留下一个仍在生效的旧副本。
- **迁移时不主动删旧钥匙串条目**（[ADR 0101](0101-the-cli-never-deletes-files.md)）；`logout` 是例外，它清当前服务器的那一条，否则「退出」只清了一半。
- **凭据文件是原地覆盖写的，不是「写邻居再改名」。** WorkBuddy 5.8.6 实测（2026-11-11）：沙箱允许写文件内容，但拒绝 `rename` 覆盖已存在的文件（`operation not permitted`）——换名要删旧目录项，而 WorkBuddy 的 `extraAllowWrite` 不含删除（其它客户端的 `allowWrite` 是否含 unlink 见 [ADR 0186](0186-the-cli-writes-a-narrow-sandbox-allowlist.md) §后果）。代价是写入中断可能留下截断的凭据，重新 `login` 即可；「截断→写入」窗口里并发读也可能看到空文件或半截文件（空文件按未登录处理），所以**不要在 `login`/`logout` 的同时并行读凭据**。`previousCredential` 容忍读不出的旧副本，不会挡住下一次登录。
