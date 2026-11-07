# 0003 · 沙箱里自带的信任库与传输错误提示

## 改了什么

- **信任库**：`cmd/skillmaster/trustroots.go` 从 `golang.org/x/crypto/x509roots/fallback/bundle` 的 `bundle.Roots()` 构造根池；`//go:debug x509usefallbackroots=1` 放在 `trustroots_darwin.go`，只在 macOS 强制走 Go 验证器。私有 CA 通过 `SKILLMASTER_CA_BUNDLE` 指向一个 PEM 文件加入。
- **能力层**：新包 `internal/sandbox`——客户端识别（`Detect` / `Environment.Summary`）、传输错误目录（`Hint` / `HintFor` / `Wrap` / `WrapFor`）、网络与回环片段（`Snippets`）、给 `doctor` 用的 `Probe`（尚未接线）。
- **接线**：`internal/api`、`internal/auth`、`internal/updater` 在**请求阶段**的传输错误多一段提示，提示里的 host 是实际失败的那个（`Client.BaseURL`、更新通道、RFC 8414 的撤销端点）；`auth.Listen` 把沙箱拒绝 bind 与端口被占分开。
- **CI**：Linux runner 上新增一步，`GOOS=darwin` 的构建信息必须带 `x509usefallbackroots=1`、`GOOS=linux` 必须不带；`trustroots_test.go` 读构建信息守同一件事。

## 为什么

在 Claude Code 的 macOS 沙箱里，Go 的证书验证要调 `com.apple.trustd.agent` 的 Mach IPC，沙箱挡住之后所有 HTTPS 都报 `x509: OSStatus -26276`；回环 bind 也被拒。CLI 当时两件事都做不到，而报错只有原文——用户看不出是被沙箱挡的还是服务端的问题。ADR 0167 记了这个决定与实测矩阵。

## 影响

- **macOS 上 CLI 不再查询系统信任库**：公共根是内嵌的 NSS bundle，企业自建 CA 必须通过 `SKILLMASTER_CA_BUNDLE` 加入；平台验证器的撤销 / CT 检查随之不再适用。**Linux / Windows 不变**，仍用系统信任库。
- **`go.mod` 的 `go` 指令升到 1.26**（fallback 模块的要求），CI 用 `go-version-file` 读取；二进制因嵌入公共根增大。
- **`doctor` 的网络片段与失败提示同源**（迭代 0002），同一客户端不会再出现两套域名或两套键名。
- **不改变 v51 的验收条款**：四家客户端的沙箱矩阵（T9）仍未实测，客户端矩阵与 `Probe` 接线记在 `docs/current/TODO.md` 的「沙箱能力层：客户端矩阵与 Probe 接线」。
