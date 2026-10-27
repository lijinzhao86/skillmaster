# v24-cli-file-footprint · 测试方案

> **最后更新**：2026-10-27
> **所属版本**：`docs/versions/v24-cli-file-footprint/`（验收标准见 [`prd.md`](prd.md) §验收与指标）

## 验收映射

| PRD 验收 | 用例 |
|---|---|
| 1 非测试源码里删除原语为 0 | TC-01、TC-02 |
| 2 一组命令的 unlink/rmdir 为 0 | TC-03 |
| 3 两个目标沙箱的审批里没有删除 | TC-04、TC-05 |
| 4 `logout` 后文件为 0 字节且读作未登录 | TC-06 |
| 5 同一版本重复 invoke 不写 pin | TC-07 |
| 6 pin 记录里的地址不一致时拒绝 | TC-08 |
| 7 旧单文件只读回退、不被改动 | TC-09 |
| 8 download 成功与改前一致、无残留 | TC-10 |
| 9 download 失败保留 staging 并点名 | TC-11 |
| 10 只读状态目录下 `startup` 正常且不删 | TC-12 |

**成立条件**：验收 1–3 任一条不过即不算交付（见 PRD）。TC-04 / TC-05 需要人看，其余全部可自动执行。

## 用例

### TC-01 非测试源码零删除原语

- **前置**：工作树干净；`internal/nodelete` 已落地。
- **步骤**：在 `skillmaster-cli/` 跑 `go test -count=1 ./internal/nodelete/`。
- **期望**：通过；扫描到 0 处 `os.Remove` / `os.RemoveAll` / `syscall.Unlink` / `syscall.Rmdir` / `x/sys/unix` 的同类调用。

### TC-02 检查器自测

- **前置**：同 TC-01。
- **步骤**：`TestTheScannerSeesADelete` 对一段合成源码（含 `os.Remove`、别名导入 `o "os"` 后的 `o.RemoveAll`、以及 Windows 的 `syscall.DeleteFile`）跑收集函数。
- **期望**：三处都被抓到。**这一步防的是「检查器永远返回空」**——一个扫描不到东西的检查器看起来和干净的工作树一模一样。

### TC-03 运行期零删除（strace）

- **前置**：Linux（或容器）；`strace` 可用；一个能应答 `invoke` / `read` / `startup` / `download` 的假服务端（或本机 8080 起的完整栈 + 夹具）；`SKILLMASTER_API_KEY` 指一把有效 key（避开浏览器登录）；`SKILLMASTER_SERVER` 指向它。
- **步骤**：
  ```bash
  strace -f -e trace=unlink,unlinkat,rmdir,rmdirat -o /tmp/v24.trace \
    skillmaster skill invoke <地址>            # 同一版本连续两次
  strace -f -e trace=unlink,unlinkat,rmdir,rmdirat -o /tmp/v24-read.trace \
    skillmaster skill read <地址> <相对路径>
  strace -f -e trace=unlink,unlinkat,rmdir,rmdirat -o /tmp/v24-start.trace \
    skillmaster startup
  strace -f -e trace=unlink,unlinkat,rmdir,rmdirat -o /tmp/v24-dl.trace \
    skillmaster skill download <地址> --into /tmp/dl-ok
  strace -f -e trace=unlink,unlinkat,rmdir,rmdirat -o /tmp/v24-dlfail.trace \
    skillmaster skill download <地址> --into /tmp/dl-fail   # 注入写失败
  ```
- **期望**：五个文件里都没有 `unlink` / `unlinkat` / `rmdir` / `rmdirat` 行（`strace` 的进程退出与信号行除外）。`login` / `logout` 不在这一组：前者必须有浏览器，两者的凭据文件路径由 TC-01 与 TC-06 覆盖。

### TC-04 Claude Code 沙箱

- **前置**：Claude Code 开着 sandbox；已登录（或 `SKILLMASTER_API_KEY`）。
- **步骤**：依次跑 TC-03 那五条命令（去掉 strace）。
- **期望**：审批界面不再出现类型为**删除**的条目；出现的写/读条目与本版 PRD 允许的集合一致（pin 一次、staging 若干）。

### TC-05 WorkBuddy 沙箱

- **前置**：WorkBuddy 沙箱；凭据用 `SKILLMASTER_API_KEY`（它的 HOME 只读，见 [v22 PRD](../v22-cli-help-and-prompt/prd.md)）。
- **步骤**：同 TC-04。
- **期望**：同 TC-04。

### TC-06 `logout` 之后文件仍在且为 0 字节

- **前置**：状态目录里已有一个回退凭据文件（钥匙串不可用的环境下登录一次即可；或直接放一份合法 blob）。
- **步骤**：`skillmaster logout` → 看文件 → `skillmaster auth status`。
- **期望**：文件**存在**、大小 0、权限 0600；`auth status` 说未登录；再 `login` 能正常登入。**不存在回退文件的环境**（凭据只在钥匙串里）不要求文件出现。

### TC-07 同一版本重复 invoke 不写 pin

- **前置**：先 `invoke` 一次，记下 pin 文件的 `mtime` 与内容哈希。
- **步骤**：同一地址、不促发新发布，再 `invoke` 一次。
- **期望**：pin 文件的 `mtime` 与内容哈希都不变；`invoke` 的输出与第一次一致。

### TC-08 地址不一致时拒绝

- **前置**：已有 `demo/hello` 的 pin 文件。
- **步骤**：把文件里的 `address` 改成 `demo/other`，再 `read demo/hello <路径>`；另外把文件内容改成非法 JSON，再 `invoke demo/hello`。
- **期望**：`read` 报错（记录不属于这个地址），**不返回任何版本**；非法 JSON 下 `invoke` **成功**并覆写（自愈），之后的 `read` 恢复正常。

### TC-09 旧单文件回退，且不被改动

- **前置**：状态目录里只有旧的 `pins-<server 哈希>` 单文件，里面有 `demo/hello` 的钉子。
- **步骤**：`read demo/hello <路径>` → 记下旧文件的哈希 → `invoke demo/hello` → 再看旧文件。
- **期望**：`read` 按旧表里的版本读；`invoke` 之后旧文件字节不变，新的 per-skill 文件出现。

### TC-10 download 成功与改前一致

- **前置**：同一版本在改动前后各下载一次到不同目录。
- **步骤**：比较两个目录的文件集合与每个文件的字节；再检查工作区（目标父目录）没有 `.staging-*`。
- **期望**：完全一致；没有 `.staging-*`。若目标目录是事先建好的空目录，会多一个 `.replaced-*` 空目录（CLI 不删，见 ADR 0101）——它不算残留，是这一态的既定行为。

### TC-11 download 失败保留 staging 并点名

- **前置**：让写入在中途失败（目标父目录只读，或磁盘配额，或服务端返回一个通过校验但写入时会失败的档案——哪种可控用哪种）。
- **步骤**：`skill download <地址> --into <目标>`。
- **期望**：退出码非 0；目标目录没有被半成品污染（不存在或仍是原来的空目录）；错误句里出现 staging 的**完整路径**与「确认后请自行删除」；staging 确实还在。

### TC-12 只读状态目录下的 `startup`

- **前置**：`SKILLMASTER_HOME`（若本版引入）或配置目录本身 `chmod 500`；已登录。
- **步骤**：`skillmaster startup`。
- **期望**：正文照常输出（沿用 v22 的行为：钉写不进去只警告），退出码 0；没有删除；警告句里带上 pin 记录目录的路径。自动化用例：`TestStartupWarnsAndCarriesOnWhenTheStateDirectoryIsReadOnly`。

## 环境与数据

- **自动用例**：`go test -count=1 ./...`，全部用 `t.TempDir()` 做状态目录；网络用 `httptest` 假服务端（同现有测试的写法）。
- **TC-03**：Linux（本机无 Linux 时用容器；构建产物用 `GOOS=linux go build`）。
- **TC-04 / TC-05**：人跑、人看；结果以截图或录屏为证据。
- **数据**：一个已在平台上存在的 skill（`demo/hello` 或同结构的夹具），至少一版有正文与两个普通文件；一个用于下载失败的注入点。
- **清理**：自动用例的目录由 `t.TempDir()` 处理；TC-11 故意保留的 staging 在记录证据后由人删除（这一条本身也是 ADR 0101 的行为样本）。

## 结果

实现完成于 2026-10-27；下表是这一轮的实测结果。**TC-04 / TC-05 未执行**——它们要在两个目标沙箱里由人跑、人看，本机没有这两个环境。

| 用例 | 结果 | 证据 |
|---|---|---|
| TC-01 | 通过 | `go test -count=1 ./internal/nodelete/`；另外在 `cmd/skillmaster/version.go` 里临时加了一处 `os.Remove`，该测试变红并报出 `version.go:1729`，随后已移除 |
| TC-02 | 通过 | `TestTheScannerSeesADelete`（`os.Remove`、别名 `o.RemoveAll`、`syscall.DeleteFile` 三处都命中） |
| TC-03 | 通过 | Linux 容器（alpine + strace）里跑 `invoke`×2、`read`、`download` 成功、`download` 失败、`startup`：六个 trace 文件里 `unlink`/`unlinkat`/`rmdir` 均为 0 |
| TC-04 | **未执行** | 需要 Claude Code sandbox 里由人跑 |
| TC-05 | **未执行** | 需要 WorkBuddy 沙箱里由人跑 |
| TC-06 | 通过 | 容器里预置 148 字节凭据文件 → `logout` 退出 0 → 文件 0 字节、0600；`auth status` 退出 1 并说未登录 |
| TC-07 | 通过 | 同一版本连续两次 `invoke`：pin 文件 mtime/大小/inode 三项完全不变；另有 `TestRememberingTheSameVersionDoesNotWrite` |
| TC-08 | 通过 | `TestARecordForAnotherAddressIsRefused`；非法 JSON 由 `TestRememberOverwritesACorruptRecord` 覆盖（`invoke` 覆写自愈） |
| TC-09 | 通过 | 容器里只放旧的 `pins-<hash>`：`read` 按它取到 1.2.3；`invoke` 之后旧文件 md5 不变、新的 per-skill 文件出现；另有 `TestTheLegacyTableIsReadAndNeverWritten`、`TestRememberMigratesAnUnchangedLegacyPin` |
| TC-10 | 通过 | 容器里 `download` 目标目录三个文件齐全、无 `.staging-*`；单元测试 `TestDownloadMakesOneRequestAndCountsOnlyContentFiles` |
| TC-11 | 通过 | 容器里下载一个含 `../escape.txt` 的包：目标未创建、`.dl-bad.staging-*` 保留、错误句点名它并说「确认后请自行删除」；`TestAFailedUnpackLeavesItsStagingAndNamesIt` |
| TC-12 | 通过 | `TestStartupWarnsAndCarriesOnWhenTheStateDirectoryIsReadOnly`；容器里 `chmod 500` 状态目录后 `startup` 退出 0 并警告 |

## 已知问题

实现完成之后填写；本版范围内的缺陷记在这里，历史代码里本版不改的记进 `known-issues.md`（本版暂不建）。
