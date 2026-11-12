# 0167 · 沙箱兼容按能力探测，不按 agent 适配

- **状态**：已采纳
- **日期**：2026-11-08
- **依赖**：[ADR 0025](0025-cli-credential-storage.md)（CLI 凭据存储）、[ADR 0101](0101-the-cli-never-deletes-files.md)（CLI 不删文件）
- **被修订**：[ADR 0186](0186-the-cli-writes-a-narrow-sandbox-allowlist.md)（§5「只打印、不改配置」改为按范围自动写）

## 背景

2026-11-08 在 macOS 上实测了两端：

| 环境 | 沙箱 | TCP 回环 | UDS | fd 继承 | 平台 HTTPS | 钥匙串 |
|---|---|---|---|---|---|---|
| Claude Code | 关 | 通 | 通 | 通 | 通 | 通 |
| Claude Code | 开，无 `allowLocalBinding` | `bind` EPERM | 未跑 | 未跑 | 未跑 | 未跑 |
| Claude Code | 开 + `allowLocalBinding` + `allowedDomains` | 通 | 三个路径全 EPERM | 通 | 证书验证失败（`x509: OSStatus -26276`）；加白名单后 curl 401/200 | 可读 |
| WorkBuddy 5.8.3 | 默认开 | 通 | 通 | 通 | 通 | 通 |

两类失败的原因不同：

- **TLS**：macOS 上 Go 通过 Security.framework 验证证书，要调 `com.apple.trustd.agent` 的 Mach IPC；沙箱挡住这条 IPC，所有 Go 程序的 HTTPS 都报 `x509: OSStatus -26276`。curl/Python 直接读 `/private/etc/ssl/cert.pem`，所以不受影响。这是 Claude Code 的已知问题（[#23416](https://github.com/anthropics/claude-code/issues/23416)、[#34876](https://github.com/anthropics/claude-code/issues/34876)），不是本仓库的特例。
- **回环 / UDS**：各客户端在策略层允许或拒绝 bind/connect；Claude Code 的 Linux 沙箱给每个命令私有 loopback，macOS 需要 `allowLocalBinding`；UDS 即使开了 `allowLocalBinding` 仍被拒。Codex / Cursor / Gemini 的默认值与配置键本轮未实测。

**沙箱标记实测**：同一台机器上，Claude Code 的沙箱命令里 `SANDBOX_RUNTIME=1`，没有 `CODEX_SANDBOX` 这类客户端标记，而 `CLAUDECODE` 在非沙箱会话里也设。所以 `Sandboxed` 只由 `SANDBOX_RUNTIME` 这一类标记决定，`CLAUDECODE` 只用来选配置片段。

**blank import 不够。** `golang.org/x/crypto/x509roots/fallback` 只调用 `x509.SetFallbackRoots`；Go 只在系统根不可用时采用它。macOS 的 `loadSystemRoots` 返回 `&CertPool{systemPool: true}` 且不报错，`initSystemRoots` 因此保留系统池，`Verify` 继续走 `systemVerify` —— 仍然撞 trustd。必须有 `//go:debug x509usefallbackroots=1`（或给 transport 显式设 `RootCAs`）才会走纯 Go 验证。

可选做法：

- **A 每个 agent 一套适配**：CLI 识别客户端，各写一条分支与一组测试。客户端越多，分支与矩阵越大；而且客户端的名字不是能力，同一客户端的不同版本又会漂。
- **B 要求用户关沙箱**：能绕过所有问题，但把 skill 代码和 agent 的所有命令一起暴露；与「让 skill 在沙箱里安全运行」的目标相反。
- **C 能力探测 + 统一错误目录 + 客户端配置片段**：CLI 只探测当前进程能做什么，按同样的降级处理；客户端差异只落在一段可粘贴的配置里。
- **D 平台代转**：把凭据请求移到平台，客户端只需要一个出站域名。它能绕开本地回环，但成本与信任面是另一条决定；本 ADR 不覆盖。

## 决定

1. **CLI 按能力探测，不按客户端分支。** 探测 TLS / 网络、回环、状态目录、凭据存储；能用就用，不能用就按统一目录给出可操作提示。执行路径里没有 `if client == ...`。
2. **macOS 上 CLI 自带信任库。** 主包从 `golang.org/x/crypto/x509roots/fallback/bundle` 的 `bundle.Roots()` 构造根池；`//go:debug x509usefallbackroots=1` 放在 `trustroots_darwin.go`，只在 macOS 强制走它。**Linux / Windows 不强制替换**：那里系统信任库可用，也正好是用户装企业 CA 的地方，强制替换等于吞掉受管机器自己的根，而问题只出在 macOS 那条被沙箱挡住的 Mach IPC 上。根池在**所有平台**都登记为 fallback，但在 Linux / Windows 上只有系统根加载不到时才会被采用——所以最小容器里 `SKILLMASTER_CA_BUNDLE` 同样有效，正常安装的系统库照旧生效。私有 CA（企业 MITM、自建部署）在 macOS 上通过 `SKILLMASTER_CA_BUNDLE` 指向一个 PEM 文件加入。**这不是「跳过验证」**：同一份公共根，没有 `--insecure`；私有 CA 是显式加入的。**但代价要说准**：macOS 上平台验证器（Security.framework）不再运行，它独有的撤销与 CT 检查也随之不再适用，剩下的是 `crypto/x509` 的链验证与主机名检查。
3. **`internal/sandbox` 是唯一的能力、错误与网络/回环片段层。** 客户端识别、探测、`Hint(err)`、`Wrap(err)`、网络与回环的配置片段都在这里。本单元把它接到 `internal/api`、`internal/auth`、`internal/updater` 的传输错误，以及 `auth.Listen` 的 bind 失败；`Probe` 是给 `doctor` 用的。**写路径（文件系统白名单）片段不在这里**：它要的是状态目录与缓存目录的实际路径，只有 `doctor` 知道，硬塞进来会让一次网络失败的提示顺带打印一堆无关的写权限。**`doctor` 已随 v51 合入 `main`，并在本单元改为复用本包**（v51 迭代 0002）：客户端与沙箱判定用 `sandbox.Detect()`、网络与回环片段用 `sandbox.Snippets()`，自己只留写路径与 Claude 的 `excludedCommands` 那两段（外加状态目录回退的推断）；不统一时同一个客户端会出现两套域名与两套键名。`Snippets` 同时列出平台域名与**更新下载域名**（`skmr update` 走另一个 host，见 [ADR 0103](0103-the-cli-updates-itself.md)）；只列平台域名会让沙箱里的更新照旧被挡，而更新是刷新内嵌根、避免它过期的路径（它自己也会用到这些根，见后果一节）。
4. **客户端差异只进配置片段，不进执行路径。** 可识别 Claude Code / Codex / Cursor / Gemini；`CLAUDECODE` 只用来选片段，`Sandboxed` 由沙箱标记决定；WorkBuddy 没有标记，归 Unknown（本轮实测它默认放行）。
5. **CLI 只打印配置，不改用户的设置。** 沙箱配置是用户的机器，CLI 给出可复制的片段，改不改由人决定。
6. **登录在回环被挡时走终端兜底。** `auth.Listen` 把**沙箱拒绝 bind（`EPERM`）**与其余 bind 失败分开：后者按「端口被占」说（固定端口，[ADR 0028](0028-cli-uses-a-fixed-loopback-port.md)），前者给回环提示并建议在自己的终端跑一次 `skmr login`。device flow 是另一条独立决定，不在本 ADR 里。

## 理由

- **A 被否：风险随客户端数量增长，而且永远收不了口。** 每来一个新 agent 就要识别、适配、测试；识别还可能错——本轮环境里就有 `CODEX_MODEL` 这类模型变量，误判成 Codex 会让每个用户都看到错误的提示。能力探测把矩阵从「N 个客户端 × M 种能力」压成「M 种能力」。
- **B 被否：它解决的是 CLI 的问题，代价是用户的安全。** 沙箱保护的正是 skill 代码与 agent 的命令；为了登录或取一次数据关掉它，与产品要建立的安全边界相反。而且它把「CLI 能在沙箱里工作」变成「CLI 要求你离开沙箱」。
- **C 被采纳：失败点各不相同，但降级方式可以相同。** TLS 失败用内置信任库；网络失败打印域名白名单；回环失败打印 `allowLocalBinding` 或改为终端登录；状态失败指向可写目录。每种失败都有自己的出路，判断方式却是同一套。
- **强制 fallback 而不是 `enableWeakerNetworkIsolation`：** 后者要放行 trustd 的 IPC，把沙箱的边界扩大给所有调用 trustd 的进程；前者只让 CLI 这个进程不再调用平台验证器。两者都付代价，只是付的地方不同：前者放弃平台验证器独有的撤销 / CT 检查，换来不依赖那条被挡的 IPC；后者不放弃检查，但削弱沙箱。选了前者，并且把放弃的东西写在后果里，而不是用「强度不变」一句带过。而且一旦 CLI 不再调用系统验证器，`enableWeakerNetworkIsolation` 对它也不再生效。
- **只在 macOS 强制：** 换验证器的根是为了绕开 trustd，而 trustd 只有 macOS 有。在所有平台强制，会把一台 Linux 受管机器上的企业根一起换掉；这不是本单元要解决的问题，却是本单元会造成的故障。
- **错误目录只覆盖 `internal/api`/auth/updater 真正会看到的传输错误。** 不把任意 `operation not permitted` 翻译成「被 X 拦了」——v51 PRD 不做 8 的理由成立：errno 文本不稳定。回环/状态由 `Probe` 的检查项直接给改法，它知道自己在探哪一项，不需要从字符串猜。
- **只打印不改配置：** 用户可能被公司策略管理着 settings，CLI 直接改会静默覆盖；而且「谁改了我的机器」比一条复制不了的提示更难解释。
- **D 暂不并入：** 平台代转是凭据交付的决定，不是沙箱探测的决定。流量、隐私、可用性与服务适配留给凭据那一版单独决定。

## 后果

- **内嵌公共根是一份快照，必须随 CLI 发布刷新。** 根来自 `golang.org/x/crypto/x509roots/fallback` 的 pin 版本（本单元是 `v0.0.0-20261105095718-a1058eb15227`），那个模块自己就要求保持更新（安全与兼容）。macOS 上它是**唯一**的信任源，所以根轮换而 CLI 没跟上时，所有命令一起 TLS 失败，连 `skmr update` 自己也不例外。义务因此写在这里：每次发版把该模块 bump 到当时最新，并用内嵌根对生产端点做一次冒烟；用户侧的恢复路径是重跑安装说明里那条命令（`certificateHint` 已经这么写）。发版清单在 [`.dev-sop/release/`](../../.dev-sop/release/README.md)；本单元不改它，要不要把「刷新内嵌根」加进去，记在 `TODO.md` 的「沙箱能力层：客户端矩阵与 Probe 接线」里，由产品负责人定。
- **macOS 上 CLI 不再查询系统信任库**：公共根是内嵌的 NSS bundle，企业自建 CA 必须通过 `SKILLMASTER_CA_BUNDLE` 加入；`enableWeakerNetworkIsolation` 不再有帮助；平台验证器不再运行，撤销 / CT 检查随之不再适用。这个变量**不登在 `help env`**：那一页按设计只有一屏，上限由 `TestHelpEnvPage` 守着，已经没有余量；它的发现路径就是失败时那段提示的原文，里面写了变量名和「指向 PEM 文件」。**Linux / Windows 不强制替换**：系统信任库照旧生效，企业 CA 照旧装进系统库；只有在系统根加载不到时才会用到内嵌池（那时 `SKILLMASTER_CA_BUNDLE` 也会生效）。这个指令有没有被带上，由测试与 CI 两头守着：`trustroots_test.go` 读构建信息，CI 的 Linux runner 再分别列一次 `GOOS=darwin` 与 `GOOS=linux` 的 `DefaultGODEBUG`——否则删掉那条指令时，Linux CI 会保持全绿。`skillmaster-cli/go.mod` 的 `go` 指令升到 **1.26**（fallback 模块的要求），CI 用 `go-version-file` 读取它，二进制因嵌入公共根增大。
- `internal/api`、`internal/auth`、`internal/updater` 在**请求阶段**的传输错误会多出一段提示（读响应体中途被切断不在目录内）；原始错误仍被 `%w` 包装，`errors.Is` 与拒绝码处理不变。`auth.Listen` 不再把沙箱的 bind 失败说成端口冲突。
- **`doctor` 已收口**（边界见决定 3：检测与网络 / 回环片段走本包，写路径片段留在 doctor）：`detectSandbox` 与它自己的网络片段表已删，同一客户端不会再出现两套域名与两套键名。v51 的 T9 期望随之改写（Claude Code 有标记就点名；WorkBuddy 没有标记，走状态目录回退或报 ok），见 v51 迭代 0002；四家客户端的矩阵仍未实测，记在 `TODO.md` 的「沙箱能力层：客户端矩阵与 Probe 接线」。
- `Probe` 在本单元没有命令调用者；它仍未接线，计划随客户端矩阵一起接到 `doctor`。本单元接线的只有 `Hint`/`Wrap` 与登录的 bind 分类。
- `Probe` 的状态检查会在状态目录留下一个零字节的 `.sandbox-probe` 作为写入测试（`doctor` 自己的写入探针是 `.write-probe`，两者不同）；CLI 不删文件（[ADR 0101](0101-the-cli-never-deletes-files.md)），所以它会留下。凭据检查只报告位置，不读钥匙串，避免在 agent 面前弹权限框。
- **编号**：0164 / 0165（v51）与 0166（权限位修复）已合入 `main`；本 ADR 与它们同期起草，故用 0167。
- **Codex / Cursor / Gemini / DSH / GrokBot 仍未实测**；Codex 的片段标注「键名以你安装的版本为准」，Cursor 与 Gemini 的标注「本轮未实测」。测试矩阵按能力行扩展，不按客户端加代码；未接入的客户端按 Unknown 走通用片段。
- 登录的回环问题没有消失：用户在自己的终端跑一次 `skmr login` 是这一版的答案；device flow 需要 server 端点与同意页，另开一版。
- 本 ADR 不决定凭据托管与交付；那是 v52（尚未开版）的事。它只保证「CLI 能在沙箱里连上平台并说清哪里被挡」。
