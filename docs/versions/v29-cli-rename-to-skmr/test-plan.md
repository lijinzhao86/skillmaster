# v29 · 测试方案

> **最后更新**：2026-10-29 · **所属版本**：[v29](README.md)

## 验收映射

| PRD 验收 | 用例 |
|---|---|
| 1 装完就能用 | T1、T9（Windows） |
| 2 `--version` 只报新名 | T2 |
| 3 面向人的输出只写 `skmr` | T3 |
| 4 记忆块用新名 | T4 |
| 5 站点 / 服务端 / 设计件 / 控制台文案 | T6a–T6c |
| 6 没有漏网 | T8 |
| 7 平台 skill 正文重发 | T7 |
| 8 安装脚本的错误分支 | T10–T13 |

## 用例

**T1 · 安装**
- 前置：本地假通道，布局与 OSS 一致（`checksums.txt` + `skillmaster_<os>_<arch>.tar.gz`，包内条目叫 `skmr`）；一个空的 `HOME`。
- 步骤：跑 `install.sh`；`ls` 安装目录；`skmr --version`。
- 期望：`$INSTALL_DIR/skmr` 存在；`--version` 退出 0；输出里没有旧名。

**T2 · `--version`**
- 步骤：用发布流水线同样的 ldflag（`-X …/version.Version=1.2.0`）构建，跑 `--version`。
- 期望：`skmr 1.2.0 (API v1)`，以 `skmr ` 开头。

**T3 · 所有 help 的输出不含旧名**
- 步骤：沿用 `render_conformance_test.go` 的遍历，对 61 个命令路径各取一次帮助正文。
- 期望：没有任何一行以 `skillmaster ` 开头；`Usage` 行统一是 `skmr …`。

**T4 · 记忆块**
- 步骤：读 `prompt.Block()`。
- 期望：正文含 `skmr skills search`、`skmr help`；整段不含 `skillmaster `；内嵌与 `block.md` 一致。

**T5 · 自更新按新名工作**
- 前置：装一个 `1.2.0` 构建到官方目录；假通道里放一个**临时编的假版本** `9.9.9` 的包（包内条目 `skmr`）——更新器只认「远端比本地新」，比 `1.2.0` 大的任意版本号都能把它跑起来。
- 步骤：`SKILLMASTER_UPDATE_BASE=<假通道> skmr update`；再跑 `--version`。
- 期望：下载、校验、替换都成功；`--version` 变成 `9.9.9`。

**T6a · 设计件与控制台**
- 步骤：改设计件 → 重出演示件并逐字节比对 → `console-states --only auth` / `--only list` → 控制台构建与测试。
- 期望：命令文案是 `skmr`；`failed: 0`；演示件与源一致；`vitest` 与 `tsc` 绿。

**T6b · 站点安装说明**
- 步骤：读 `skillmaster-site/public/install/skillmaster.md`；跑站点测试。
- 期望：正文里的命令都是 `skmr`；文件路径仍是 `install/skillmaster.md`；附录 B 与包内文件名一致。

**T6c · 服务端提示**
- 步骤：读 `Organize.java` 拼出的那句话。
- 期望：命令是 `skmr skill join …`。

**T7 · 平台 skill 正文（人工）**
- 步骤：由人在网页上把 `skillmaster/startup` 等正文里的命令改成 `skmr` 并发布。
- 期望：发布后正文里没有 `skillmaster `。**在完成之前，这条用例算未执行，不是通过。**

**T8 · 守卫**
- 步骤：① 跑守卫测试（`go/ast` 扫 `skillmaster-cli/` 下非测试 Go 的字符串字面量）；② 在仓库根跑：

  ```
  rg -n --hidden '\bskillmaster [a-z-]+' \
    --glob '!**/node_modules/**' --glob '!.git/**' --glob '!design/shots/**' --glob '!*-演示.html' \
    --glob '!docs/versions/**' --glob '!docs/decisions/**' \
    --glob '!skillmaster-cli/cmd/skillmaster/testdata/golden/**' \
    --glob '!design/demos/dir1-swiss.html' --glob '!design/demos/dir2-resend.html' \
    --glob '!**/*_test.go' .
  ```

  **`--hidden` 不能省**：`rg` 默认跳过隐藏目录，`.github/` 会被整目录漏掉。
- 期望：① 无命中；② 只剩 5 行、3 个文件——前 4 行不是命令示例（数据库角色名、systemd 服务名），第 5 行是**不可修改的历史迁移注释里的命令名**（见结果）。

**T9 · Windows**
- 前置：Windows runner。
- 步骤：`install.ps1` 装；`skmr.exe --version`；跑一次自更新。
- 期望：`SkillMaster\bin\skmr.exe` 存在且能跑；更新后仍是新版本。

**T10 · 下载失败**
- 前置：`SKILLMASTER_DL_URL` 指到一个不存在的路径。
- 期望：打印「下载失败：…（网络，或这个平台暂时没有产物）」，退出码 1。

**T11 · 认不出的架构**
- 前置：假 `uname`，`-m` 回 `riscv64`、`-s` 回 Darwin。
- 期望：打印「认不出架构「riscv64」，…」，退出码 1。

**T12 · 不支持的系统**
- 前置：假 `uname -s` 回 `FreeBSD`。
- 期望：打印「不支持的系统：FreeBSD…」，退出码 1。

**T13 · 校验和不符**
- 前置：假通道里把 `checksums.txt` 的哈希写成一串 0。
- 期望：打印「校验和不符：…——已停止，没有安装」，退出码 1，安装目录里没有新文件。

## 环境与数据

- **本机**：macOS（arm64），Go 工具链与仓库自带的 `go test ./...`。
- **假通道**：一个本地目录，布局与 OSS 一致（`version.txt`、`checksums.txt`、`skillmaster_<os>_<arch>.tar.gz`），用 `python3 -m http.server` 起一个最小静态服务，`SKILLMASTER_UPDATE_BASE` / `SKILLMASTER_DL_URL` 指过去。
- **CI**：`cli.yml`（ubuntu 跑 `go test`）、`cli-release.yml` 的冒烟段；Windows 只在发布流水线里量。
- **清理**：所有用例只写临时目录（`t.TempDir()` / `mktemp -d`），不碰真实的 `~/.local/bin` 与 `~/.config/skillmaster`。

## 结果

实测（2026-10-29，本机 macOS arm64；`feat/cli-rename-skmr`）。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | **通过** | 假通道 + 真跑 `install.sh`：`~/.local/bin/skmr` 存在，`skmr --version` 退出 0。另跑了一遍「改名前的 HOME」（默认目录里只有旧名文件）：重装后 `skmr` 出现、旧文件原样保留——安装页第 ② 步的分支条件据此写成「目录里有没有 `skmr`」，不是「有没有文件」 |
| T2 | **通过** | 按流水线的 ldflag 构建：`skmr 1.2.0 (API v1)` |
| T3 | **通过** | `go test ./...` 全绿，含 `render_conformance_test.go` 对 61 条命令路径的遍历 |
| T4 | **通过** | block 测试：正文含 `skmr skills search`、`skmr help`，不含旧名 |
| T5 | **通过** | 本地装一个 `1.2.0` 构建，假通道上放一个**临时编的假版本** `9.9.9`（只为让更新器认为有新版，不是发布版本）：下载、校验、替换成功；`skmr --version` 变成 `9.9.9` |
| T6a | **通过** | 演示件重出后与源逐字节一致；`console-states --only auth` / `--only list` 均 `failed: 0`；`interaction-check` `failed: 0`；控制台 31 文件 / 585 条通过、`tsc` 无输出 |
| T6b | **通过** | 安装说明正文改为 `skmr`，路径不变；附录 B 与包内文件名（`skmr`）一致；站点 20 条通过 |
| T6c | **部分** | `Organize.java` 已改；`MyTagApiIT` 断言的是 `skill join` 子串，仍然成立。**Java 集成测试未执行**（要数据库） |
| T7 | **未执行** | 依赖人在网页上重发平台 skill 正文 |
| T8 | **通过** | 守卫测试无命中；grep 剩 **5 行 / 3 个文件**：`compose.yaml:38` 与 `.github/workflows/server.yml:76` 是数据库角色名，`server.yml:298`/`:303` 是 systemd 服务名；`V9__…sql:17` **是命令名 `skillmaster logout`**，留在不可改的历史迁移注释里 |
| T9 | **未执行** | 没有 Windows 机器（与 RELEASE.md §10 的既有结论一致） |
| T10 | **通过** | 指错通道：「下载失败：…（网络，或这个平台暂时没有产物）」，退出码 1 |
| T11 | **通过** | 假 `uname` 回 `riscv64`：「认不出架构「riscv64」，…」，退出码 1 |
| T12 | **通过** | 假 `uname -s` 回 `FreeBSD`：「不支持的系统：FreeBSD…」，退出码 1 |
| T13 | **通过** | 校验和写成一串 0：「校验和不符：…——已停止，没有安装」，退出码 1 |

## 已知问题

| # | 现象 | 状态 |
|---|---|---|
| KI-1 | 平台 skill 正文（验收 7 / T7）只能由人在网页上改；在那之前它们仍写着旧名，靠 CLI 代码里那一行兼容还能跑 | 未执行（依赖人的动作） |
| KI-2 | Windows 上旧名是同一个文件的第二个名字（硬链接）。自更新替换的是**被运行的那一份**：用 `skmr` 跑就更新 `skmr`，用旧名跑就更新旧名，另一份停在上一个版本 | 设计内已知；旧名只是兼容入口，正常使用（`skmr`）时 `skmr` 是最新的 |
| KI-3 | 控制台的「二进制文件」提示让人跑 `skmr skill get`，而这个命令不存在 | 见 [`known-issues.md`](known-issues.md) 条目 1（本版只换名字，不修命令） |
