# v25-cli-self-update-and-prompt · 测试

> **状态**：已定稿（2026-10-27）；§结果 在实现完成后填
> **所属版本**：[`README.md`](README.md)

<!--
验收映射必须覆盖 prd.md 的每一条，不多不少。
结果一节在实现完成后填写：通过写通过、没执行写未执行、失败附证据。
-->

## 验收映射

| 验收（prd.md） | 用例 |
|---|---|
| 1 静默 | T01 |
| 2 自动装（mac / Linux） | T02、T02b |
| 3 校验和不符不装 | T03 |
| 4 节流 | T04 |
| 5 关闭开关 | T05 |
| 6 dev 构不参与 | T06 |
| 7 跳过帮助与版本 | T07 |
| 8 写不进去只提示 | T08 |
| 9 显式入口 | T09 |
| 10 Windows 换位 | T10 |
| 11 并发 | T11 |
| 12 规则段随二进制 | T12 |
| 13 过期提示 | T13 |
| 14 审批（不读 stdin） | T14、T14b |
| 15 定位与幂等 | T15 |
| 16 不删文件 | T16 |
| 17 旅程指向 CLI | T17 |
| 18 契约不回退 | T18 |
| 19 官方安装位置之外不动手 | T19 |

## 用例

### T01 · 没有新版时什么都不做

- **前置**：假发布源，`version.txt` 与本机 `version.String()` 相同。
- **步骤**：跑 `skills list --no-login`（一个纯本地命令），再跑一次。
- **期望**：两次 stdout 与基线逐字节相同；stderr 没有新增内容；发布源只收到 1 次 `version.txt` 请求（第一次），第二次没有。

### T02 · 有新版时自动装（macOS / Linux）

- **前置**：假发布源放一个更高的版本与对应产物包；本机二进制是当前构建。
- **步骤**：跑 `skills list --no-login`，记录 stdout / 退出码 / stderr；再跑 `--version`。
- **期望**：第一条命令的 stdout 与基线逐字节相同、退出码相同、stderr 恰好多一行「已更新到 …」；`--version` 显示新版本；新旧两次 `--version` 的差异就是版本串。

### T02b · 下载没完成就不阻塞

- **前置**：假发布源的产物下载被限速到 10 秒以上。
- **步骤**：跑 `skills list --no-login` 并计时。
- **期望**：命令在 ≤ 10 秒 + 命令本身耗时的范围内返回；没有更新；**约一小时后的那次运行**会重新尝试（检查点被前移，不是下一条命令）。

### T03 · 校验和不符不装，并说出来

- **前置**：假发布源把产物改一个字节，`checksums.txt` 保持原值。
- **步骤**：跑一条命令，再看 `--version`。
- **期望**：不替换；stderr **有一行**说明没通过校验（「没有通过校验」）；同一版本再跑不重复这一行；`--version` 仍是旧版，退出码与不更新时一致；下一次检查约在一小时后（不是一天）。

### T04 · 24 小时节流

- **前置**：假发布源无新版；清空状态目录。
- **步骤**：连续跑三条命令（间隔几秒）。
- **期望**：`version.txt` 请求数 = 1；第二条、第三条零请求。

### T05 · 关闭开关

- **前置**：`SKILLMASTER_AUTO_UPDATE=0`。
- **步骤**：跑三条命令。
- **期望**：请求数 = 0；命令输出与不加变量时一致；`skillmaster update --check` **仍然可用**（手动入口不受这个开关影响）。

### T06 · dev 构不参与

- **前置**：不带 `-X` 的本地构建（`Version == "dev"`、`UpdateBase == ""`）。
- **步骤**：跑三条命令，含一个 24 小时后的假状态。
- **期望**：请求数 = 0；`update --check` 明确说「这个构建不参与更新」。

### T07 · 跳过帮助与版本

- **前置**：远端有更新可取。
- **步骤**：分别跑 `--help`、`-h`、`--version`、`help`、`help skill`、裸 `skillmaster`。
- **期望**：六条都零请求、零下载、没有「已更新」行；帮助与版本输出与今天逐字节相同。

### T08 · 写不进去只提示

- **前置**：安装目录 `chmod a-w`（Windows：用只读 ACL 或改为在程序目录放一个同名目录）。
- **步骤**：连跑两次；再清掉节流记录跑第三次。
- **期望**：命令都成功、退出码不变；第一次 stderr 有「装不进去，重跑安装命令」；在节流窗口内的第二次**不发请求**，因此也不会再提示；清掉节流后再检查仍只提示一次（`Notified` 记着这个版本）。

### T09 · 显式入口

- **步骤**：`update --check`（有新版 / 无新版各一次）、`update`（有新版）。
- **期望**：`--check` 只打印结论、不下载（产物请求数 = 0）；`update` 打印新旧版本并完成换位；已是最新时 `update` 打印「已是最新」并退出 0；三者都不受节流影响（连续跑三次都真的访问了远端）。

### T10 · Windows 换位（`windows-latest`）

- **前置**：CI 作业里构建一个「父」二进制与一个假发布源。
- **步骤**：跑一条命令触发更新；同时每 10 ms 轮询目标 `skillmaster.exe` 是否存在；结束后列目录、跑 `--version`。
- **期望**：轮询**从不**发现目标缺失；目录里没有 `.old`、没有 `.new-*`；`--version` 是新版本；父进程退出码与基线一致。

### T11 · 并发

- **前置**：远端有新版；状态目录干净。
- **步骤**：同时起三条命令（shell 后台并行）。
- **期望**：只有一次下载（打包请求数 = 1）；三条命令都正常退出；结束后二进制是新版、目录里没有多余文件、`update.json` 里没有残留 `pending`。

### T12 · 规则段随二进制

- **前置**：断网、清空凭据。
- **步骤**：`skillmaster prompt print`；与 `internal/prompt/block.md` 比对。
- **期望**：退出 0；输出与文件**逐字节**相同（含 `<!-- skillmaster:begin -->` 与 `end`）；全程零网络请求。

### T13 · 过期提示

- **前置**：`prompt.json` 存在且 `digest` 与二进制不同（**文件里放的是旧块**）。
- **步骤**：跑一条无关命令，再跑一条；然后跑 `prompt status`。
- **期望**：第一条 stderr 有一行「规则段是旧版」；第二条没有；`prompt status` 退出 1 并显示文件里的旧块与新块的差异。**文件被删掉**时任何命令都不打这一行，只有 `prompt status` 说文件不见了。**文件已经是最新、只有记录落后**时：任一命令仍会提示一次（提示看的是记录摘要 + 文件在不在，不读内容），而 `prompt status` 退出 0，并顺手把记录修正到当前摘要。

### T14 · 审批：不给 `--yes` 不落笔

- **前置**：夹具文件，标记段前后各有内容。
- **步骤**：`prompt update`（记录指向该文件，标记段是旧版），记录前后 `sha256`；再跑 `--yes`。
- **期望**：第一次退出 1、打印 diff、文件 `sha256` 不变；第二次只替换标记段，段外字节不变（分段比对）；两次都不读 stdin（见 T14b）。

### T14b · 不读 stdin、不挂起

- **步骤**：在 `< /dev/null`、关闭 stdin 的管道、以及一个伪终端三种环境下各跑 `prompt update` 与 `prompt status`，计时。
- **期望**：三种环境都在 1 秒内返回；没有读取任何输入的痕迹（strace / 进程不阻塞）。

### T15 · 定位与幂等

- **前置**：三个夹具：① 已有逐字相同标记段的文件；② 完全没有标记段的文件；③ 标记段是旧版的文件。
- **步骤**：`prompt install --file` 各跑一次。
- **期望**：① 只记录，文件 mtime 与字节都不变；② 末尾追加标记段（前面恰好一个空行），并记录；③ 打印 diff，没 `--yes` 退出 1。之后再跑 `prompt update` 不需要 `--file`。

### T16 · 不删文件

- **步骤**：`go test ./internal/nodelete`；Linux 容器里 `strace -f` 跑 `update`、`prompt status/print/install/update` 与一次完整的自动更新。
- **期望**：测试绿；`unlink` / `unlinkat` / `rmdir` 计数为 0（与 v24 的 T16 同一口径）。

### T17 · 旅程指向 CLI（平台内容）

- **前置**：新版本 `skillmaster/startup` 已上线。
- **步骤**：`skill read skillmaster/startup steps/03-prompt.md`。
- **期望**：文件里没有可粘贴的规则正文；有一条 `skillmaster prompt install --file` 与「先问人」。`prompt print` 输出的那段仍是 v20 批准的三行（逐字）。

### T18 · 契约不回退

- **步骤**：`go test ./...`；对四个新命令逐条核对 v16 的八项与 ≤ 2 KB；`check_docs.py`。
- **期望**：全绿；新命令的帮助没有相对时间词、没有 ANSI；`--help` 在任意位置都有效；命令表里**没有** helper。

### T19 · 官方安装位置之外不动手

- **前置**：把二进制放在临时目录（不在 `~/.local/bin` / `%LOCALAPPDATA%\SkillMaster\bin`），假发布源上有新版。
- **步骤**：跑一条命令；再跑 `update`；再跑 `update --check`。
- **期望**：自动路径**零请求**、文件哈希不变；`update` 退出 1 并说「不在官方安装位置」；`update --check` 仍能报告有没有新版，且不改文件。（夹具是一处非官方位置；官方位置的两条由 T02 与 T09 覆盖。）

## 环境与数据

- **假发布源**：Go 测试里的 `httptest.Server`，按真实形状提供 `version.txt`、`checksums.txt`、`skillmaster_<os>_<arch>.tar.gz|zip`。产物在测试里现造（`archive/tar` + `compress/gzip`、`archive/zip`），内容是一个能打印自身版本的小二进制——用一个带 `-X` 的 `go build` 太重，测试改用「放进包里的任意字节」，只验证落盘与大小；端到端那条用本仓库的二进制。
- **端到端**：测试把 `HOME` / `XDG_CONFIG_HOME` / `APPDATA` / `LOCALAPPDATA` 全指到临时目录，所以「官方安装位置」本身也是临时的；夹具二进制就放在那个临时的 `~/.local/bin`（Windows 是临时的 `%LOCALAPPDATA%\SkillMaster\bin`）里。真实用户的目录一个字节都不碰。
- **Windows**：`windows-latest` 作业跑同一套单元测试 + T10；换位那一步只在那里有意义。
- **清理**：全部落在 `t.TempDir()`；不触碰用户目录。ADR 0101 之后测试也不能删文件——`t.TempDir()` 由 `testing` 包自己删，不在 CLI 代码里。

## 结果

2026-10-27，实现完成后跑第一轮。单元与端到端都在本机（macOS / arm64）跑；需要 Windows、限速、容器或平台内容的用例如实标未执行。

| 用例 | 状态 | 证据 |
|---|---|---|
| T01 | 通过 | `TestTheCheckIsThrottledToOnceADay`（无新版：一条命令一次请求，后面不再请求；两次运行的 stdout / stderr 逐字节相同） |
| T02 | 通过 | `TestANewerBuildIsInstalledAndTheCommandIsUntouched`（stdout 空、退出码不变、stderr 一行、目标文件变成新构建）；「最多多等 10 秒」由 T02b 的夹具单独量，本文未计时 |
| T02b | 未执行 | 需要限速夹具；10 s 预算与「丢弃」由 `Drain` 的定时器实现 |
| T03 | 通过 | `TestATamperedPackageLeavesTheBinaryAlone`（含「没有通过校验」一行）、`TestAVerificationFailureIsReportedAndRetriedSoon`（一小时后重试） |
| T04 | 通过 | `TestTheCheckIsThrottledToOnceADay`（3 条命令 → 1 次请求） |
| T05 | 通过 | `TestTheSwitchTurnsTheAutomaticCheckOff` |
| T06 | 通过 | `TestADevBuildNeverChecksForUpdates`、`TestUpdateSaysSoWhenTheBuildCannotUpdate` |
| T07 | 通过 | `TestHelpAndVersionNeverCheckForUpdates`（6 种调用含 `-h` 零请求）；「输出与今天逐字节相同」由 v16 的既有快照测试覆盖，本条不重复断言 |
| T08 | 部分 | 单元级 `TestPrepareReportsWhenTheMachineWillNotTakeTheUpdate`、`TestAFailedRecoveryIsAnnouncedOnce`（提示能活过节流分支且只出现一次）；「每版一次」由 `TestAReleaseIsAnnouncedOnce`；只读安装目录的端到端未跑 |
| T09 | 通过 | `TestUpdateCheckReportsWithoutDownloading`、`TestUpdateInstallReplacesTheBinary`、`TestUpdateSaysWhenItIsAlreadyCurrent` |
| T10 | 未执行 | 需要 Windows runner；`TestTheWindowsHelperReplacesTheRunningBinary` 已写好并加了 10 ms 目标路径轮询，靠 `SKILLMASTER_E2E_UPDATE=1` 在 CI 跑 |
| T11 | 部分 | `TestALockedMachineIsNotUpdatedTwice`（锁被持有时第二个更新器零请求、不开工）；两条真进程并发的端到端未跑 |
| T12 | 通过 | `TestPromptPrintIsTheBinarysOwnText`（逐字节）、`TestThePromptGroupNeverTouchesTheNetwork`（零请求） |
| T13 | 通过 | `TestTheStaleBlockIsAnnouncedOnce`（第一次有一行、第二次没有）、`TestADeletedBlockFileIsNotAnnounced`（文件不在 → 没有这一行）、`TestPromptStatusHealsARecordThatIsBehindTheFile`（文件最新而记录落后 → 退出 0 并修正记录） |
| T14 | 通过 | `TestPromptInstallThenUpdate`（无 `--yes` 退出 1、文件哈希不变；有 `--yes` 只换段内） |
| T14b | 部分 | `TestPromptUpdateDoesNotWaitForInput`（无人写入的管道，5 s 内返回）；伪终端环境未跑，1 秒上限未计时 |
| T15 | 通过 | `TestPromptInstallThenUpdate`、`TestPromptInstallOfAnIdenticalBlockOnlyRecords`（mtime 与字节都不变） |
| T16 | 部分 | `go test ./internal/nodelete` 绿；Linux 容器 strace 未跑 |
| T17 | 未执行 | 平台内容（`steps/03-prompt.md`）要由技能主人上线，不在本仓库 |
| T18 | 通过 | `go test ./...`、`gofmt -l`、`go vet`、`check_docs.py` 全部退出 0；命令表 64 条、帮助 ≤ 2 KB |
| T19 | 通过 | `TestABinaryOutsideTheOfficialDirectoryIsNotReplaced`（零请求、哈希不变、`update` 拒绝、`--check` 仍报告且不改文件） |

## 已知问题

- **Windows 未签名**（PRD 不做 #1）：自更新替换二进制可能被 360 / Defender / EDR 拦截。状态：未验证（没有 Windows 真机样本）。缓解：换位失败时 `.new` 留在安装目录，`pending` 记录让下一次运行重试；用户随时可重跑安装命令。
- **T10 / T02b / T17 未执行、T08 / T11 / T14b / T16 部分**：分别卡在 Windows runner、限速夹具、平台内容上线、只读安装目录端到端、真进程并发、伪终端与容器 strace。T10 是这一版的成立条件之一，所以 Windows 作业跑绿之前，这一版不算验收完成。
