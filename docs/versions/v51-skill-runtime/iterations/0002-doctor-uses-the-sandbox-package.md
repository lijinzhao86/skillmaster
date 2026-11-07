# 0002 · `doctor` 改用能力层的检测与网络片段

## 改了什么

- **`cmd/skillmaster/doctor.go`**：删掉自己的 `detectSandbox`，改用 `internal/sandbox.Detect()` 与 `Environment.Summary()`（标记按固定顺序打印）；状态目录那条兜底推断（`config.DirNote()`）留在 doctor，因为它刚探过那个目录。
- **配置片段分成两半**：写路径四行仍由 doctor 打印（它知道状态与缓存目录）；网络与回环改用 `internal/sandbox.Snippets()`。未识别客户端时打印四家已知片段加一段通用片段；Claude Code 的 `excludedCommands` 说明保留。
- **域名与键名随之换了来源**：不再写死 `skillmasterai.com` / `*.skillmasterai.com` / `skillmaster-dl.oss-cn-hangzhou.aliyuncs.com`，改为 `config.Server()` 的实际 host 与 `version.Channel()` 的更新域名；补上 Claude 的 `allowLocalBinding`、Codex 的 `allow_local_binding` 与 `profiles.*.network.domains`、Cursor 的回环地址。
- **WorkBuddy 那一行不再要求放行网络**：它没有 marker、归 `Unknown`，片段按 5.8.3 的实测说默认已放行；写路径仍由 doctor 给出。

## 为什么

`internal/sandbox`（ADR 0167）与 v51 的 `doctor` 是先后写成的两套客户端知识。两边对同一个客户端给出的东西不一样：

- Claude Code：错误提示要求 `allowLocalBinding`，doctor 的片段里没有——照 doctor 改完，`skmr login` 仍然 bind 失败；
- 更新域名：doctor 放行的是 OSS 桶，`skmr update` 走的是 `dl.skillmasterai.com`，更新照旧被挡；
- 平台域名：doctor 写死生产域名，自建/镜像部署下完全不对；
- WorkBuddy：doctor 说「另需在网络规则里放行」，能力层说它默认已放行。

同一个命令与它给出的提示互相矛盾时，用户无法判断哪一份是对的。

## 影响

- **`skmr doctor` 的输出变了**：沙箱行对 Claude Code 现在报 `SANDBOX_RUNTIME=…`（有标记就点名）；片段多了一节写路径、网络部分按客户端给全。退出码与「只打印、不改配置」不变。
- **T9 的期望已改写**（`test-plan.md`）：Claude Code 不再只能说「多半在沙箱里」；WorkBuddy 走状态目录回退或报 ok。四家客户端的沙箱矩阵本身仍未实测。
- **`Probe` 仍没有调用者**：能力层里那组四项探测随客户端矩阵一起接，不在这次收口里。
