# 0001 · agent 安装链路的沙箱修复

> **日期**：2026-11-12
> **所属版本**：[`../README.md`](../README.md)
> **类型**：小版本迭代——**未改变对外可验收的能力边界**

## 改了什么

- `skillmaster-cli/install/install.sh` 与 `skillmaster-site/public/install/skillmaster.md` 的 `mktemp` 改成显式模板（`${TMPDIR:-/tmp}/...`）。不带模板的 `mktemp` 在沙箱里会去要 macOS 的 `/var/folders` 临时目录并被拒绝，`set -e` 让安装链在第一步就退出。
- `skillmaster.md` 的技能安装改成一条有序回退链：先试 Agent 的文件写入工具；失败或只能用 shell 时用下面的**单文件原地复制**（不 `mv` 覆盖目录、不 `rm -rf` 技能目录——WorkBuddy 允许写内容、拒绝 rename 覆盖已有文件）；shell 也被拒就请求一次「这条命令出沙箱」；用户拒绝且客户端没有导入界面时按第 9 行停下。备份用本次运行独有的名字，只从这次的备份恢复，不会被上一次中断留下的旧备份误导；备份建不出来就不动旧文件；恢复失败时保留备份并告诉用户在哪；没有旧文件时失败会删掉半截的新文件，删不掉就点名这个可能不完整的路径。
- `skillmaster.md` 新增 4.5「沙箱配置（问一次）」：安装器打印的 `doctor --fix` 由 Agent 用选项卡/`AskUserQuestion` 问用户，同意后再运行；拒绝后 `startup` 不绕过这个拒绝再写（[`technical-design.md`](../technical-design.md) §沙箱外执行）。
- `skillmaster.md` 第 5 步先跑 `skmr auth status`，并区分退出码 0 / 1（没凭据）/ 4（凭据被拒或环境变量不是 key）/ 其他；没登录时不在沙箱里启动 `startup`（检测得到沙箱标记的客户端会立即按没凭据退出；检测不到标记的客户端如 WorkBuddy，`open` 可能被拦且浏览器登录会等 5 分钟），改为让用户在终端登录或提供 API key。
- `cmd/skillmaster/session.go`：检测到沙箱且无凭据时，`clientFor` 不再自动发起浏览器登录，直接返回「在沙箱外的终端登录 / 用 API key」；显式 `skmr login` 仍可尝试。
- `cmd/skillmaster/startup.go`：沙箱里的无凭据提示同样指向沙箱外的终端。
- `cmd/skillmaster/startup_test.go`：新增 `TestASandboxWithoutCredentialDoesNotStartABrowserLogin` 与 `TestStartupInASandboxNamesTheTerminalItHasToRunIn`；`cmd/skillmaster/keys_test.go` 移出 `driveTheLogin`，由 `cmd/skillmaster/login_helpers_test.go` 承接并清掉继承来的沙箱标记（同时让超出上限的测试文件缩短）。
- `install/install.sh` / `install/install.ps1`：收尾改绝对路径，先 `auth status`、已登录再 `startup`；无客户端分支也打印绝对路径。`RELEASE.md` §7 同步，并写明 `--dry-run` / `--fix` / `--undo` 三条命令。
- 安装说明补 `skmr update` 在沙箱里改不了名时的兜底、PATH 只是给用户以后用的说明，以及 `建不了临时目录` / `改不了 … 的权限` / `安装被中断` 的报错映射。
- 新增 `T21 · agent 沙箱安装链路`。

## 为什么

在 Agent 沙箱里实测：

- `mktemp -d`（无模板）→ `Operation not permitted`；显式模板成功。
- `~/.claude/skills` 的 Bash 写入 → `operation not permitted`；同一路径的 Agent Write 工具成功。
- `open -a` → 被沙箱拦（`-600`）；`clientFor` 仍认为浏览器可用，`auth.Login` 的 `callbackTimeout` 是 5 分钟，命令会挂住。
- 安装说明没有 `doctor --fix` 这一步，Agent 可能在未放行状态目录/域名时直接交棒给 `startup`。

## 影响

- **影响哪些已交付行为**：v62 的安装链路，不改变已发布 CLI 的命令面与凭据格式。`clientFor` / `startup` 在「可检测沙箱 + 无凭据」时从「等浏览器」变成「立即返回沙箱外登录或 API key 指引」；显式 `skmr login` 不受影响。安装文档的行为分支变了，命令面没变。
- **需要同步改哪些文档**：`skillmaster.md`、`technical-design.md`、`prd.md`、`test-plan.md`、`README.md`、`docs/current/TODO.md`、`skillmaster-cli/RELEASE.md` 已改；平台上的 `startup` skill 正文待发布（TD 的分期 P1），发布前它仍可能按旧描述无条件跑 `doctor --fix`。
- **是否需要新开 ADR**：不需要——沿用 [ADR 0185](../../../decisions/0185-credentials-default-to-a-file.md) / [ADR 0186](../../../decisions/0186-the-cli-writes-a-narrow-sandbox-allowlist.md) 的同一取舍；4.5 的拒绝不被 `startup` 覆盖，是 ADR 0186 §6「显式同意一次」的直接执行，不是新的跨版本决定。
- **测试**：`go test ./...`、`sh -n install/install.sh`、文档校验；`install.ps1` 本机没有 PowerShell，未跑语法或真机检查。真机沙箱复测见 `test-plan.md` T21（覆盖命令、文件写入与 `startup` 的沙箱退出；4.5、第 5 步的 `auth status` 分支与第 2 步的复制 / 恢复失败路径是文档级）。
