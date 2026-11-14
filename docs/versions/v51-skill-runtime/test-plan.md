# v51-skill-runtime · 测试方案

> **最后更新**：2026-11-08
> **所属版本**：[`README.md`](README.md)

## 验收映射

`prd.md` 的 11 条验收，逐条落到用例，不多不少。

| 验收 | 用例 | 方式 |
|---|---|---|
| 1 可执行位按路径 | T1、T1b、T1c、T14 | 自动 |
| 2 `run` 与本地退出码一致 | T2 | 自动 |
| 3 路径解析与候选清单 | T3、T3b | 自动 |
| 4 默认 cwd 是 `scripts/` | T4 | 自动 |
| 5 环境变量原样继承 | T5 | 自动 |
| 6 缓存命中与重建 | T6 | 自动 |
| 7 状态目录降级 | T7 | 自动 |
| 8 钥匙串降级 | T8 | 自动（CI）+ 手工（macOS） |
| 9 沙箱内 `doctor` | T9a–T9d | **手工**，四家客户端 |
| 10 符号链接给人话 | T10 | 自动（服务端） |
| 11 断网时缓存仍能跑 | T11 | 自动 |

做 #4 的「首次执行前确认」不对应独立验收条，由 T13 覆盖；`--` 分界线的回归由 T15 覆盖。

## 用例

### T1 · 可执行位（上传路径）

- **前置**：fixture skill `v51-fixture` 已发布；其 `scripts/hello.sh` 在源 zip 里是 `0755`
- **步骤**：`skmr skill get <地址> --all --into /tmp/t1` → `test -x /tmp/t1/scripts/hello.sh` → 直接执行它
- **期望**：`test -x` 退出 0；脚本打印 `hello`、退出码 0

### T1b · 可执行位（同步路径）

- **前置**：一个 GitHub fixture 仓库，skill 目录里的脚本在上游是 `0755`
- **步骤**：同步建草稿 → 发布 → `skmr skill get … --all`
- **期望**：同上；且入库后 `version_file.mode` 为 `493`（0755）

### T1c · `scripts/` 之外不可执行

- **前置**：fixture 里根目录有一个 `notes.txt`、`scripts/` 下有一个 `run.sh`
- **步骤**：`skmr skill get <地址> --all --into /tmp/t1c`
- **期望**：`test -x /tmp/t1c/scripts/run.sh` 退出 0；`test -x /tmp/t1c/notes.txt` 退出 1

### T2 · `run` 与本地退出码一致

- **前置**：fixture 里 `scripts/exit_code.py` 接受 `--code N`，以 N 退出
- **步骤**：在 skill 目录里直接 `python3 scripts/exit_code.py --code 7; echo $?`；再 `skmr run <地址> --yes -- python3 exit_code.py --code 7; echo $?`（`--yes` 只跳过**首次**确认，见 T13）
- **期望**：两边都输出 7；`--json` 的 `exit_code` 也是 7

### T3 · 路径解析

- **前置**：fixture 里 `scripts/plan.py` 存在；`scripts/lib/plan.py` 是另一个文件（只写 `plan.py` 时先按相对路径命中根下那个，不会进歧义分支）
- **步骤**：`skmr run <地址> --yes -- python3 plan.py`（按名字唯一命中）→ 再跑一次指向不存在的 `nope.py`
- **期望**：前者执行 `scripts/plan.py`；后者退出码非 0，输出含「`scripts/` 下没有 nope.py，现有：…」并列出清单

### T3b · 歧义不猜

- **前置**：fixture 里同时有 `scripts/a/x.py` 与 `scripts/b/x.py`
- **步骤**：`skmr run <地址> --yes -- python3 x.py`
- **期望**：退出码非 0，输出列出两个候选，不执行任何一个

### T4 · 默认 cwd

- **前置**：fixture 里 `scripts/read_neighbor.py` 打开同目录的 `data.csv`（**`data.csv` 在 `scripts/` 里**）
- **步骤**：`skmr run <地址> --yes -- python3 read_neighbor.py`
- **期望**：打印文件内容，退出码 0；把 `--cwd` 指到 skill 根再跑一次，得到 `FileNotFoundError`——两次的差别证明 cwd 确实是 `scripts/`

### T5 · 环境原样继承

- **前置**：fixture 里 `scripts/env_probe.py` 打印 `PATH`、`PYTHONPATH`、`HTTP_PROXY`、`SKILLMASTER_SKILL_DIR`
- **步骤**：`FOO=bar HTTP_PROXY=http://x:1 skmr run <地址> --yes -- python3 env_probe.py`
- **期望**：`PATH` / `PYTHONPATH` / `HTTP_PROXY` 与调用者逐字相同；`SKILLMASTER_SKILL_DIR` 指向解出来的 skill 根

### T6 · 缓存命中与重建

- **前置**：同一 digest 已联网跑成功过一次（这一步同时写下了「地址 → digest」的记忆）
- **步骤**：把 `SKILLMASTER_SERVER` 指向一个不可达地址，再跑一次；然后删掉缓存目录，用真实地址再跑一次
- **期望**：第二次（服务端不可达）仍成功，stderr 说「用上次在这个地址上解析到的版本」，且**没有档案请求**；删缓存后用真实地址第三次成功重新下载。**「缓存命中」的判据是不重新下载**——服务端可达时仍会发一次元数据请求确认当前版本，这是有意的（见 ADR 0165 §后果）

### T7 · 状态目录降级

- **前置**：准备一个 `chmod 555` 的目录
- **步骤**：`SKILLMASTER_HOME=<只读目录> skmr doctor`
- **期望**：命令成功；状态目录那一行说出**被拒的那个目录**与**实际用的目录**，两者不同（不能是同一句里自相矛盾的一对）。用 `skmr doctor` 而不是 `skills search`：后者只为了取凭据而解析状态目录，不会报告降级，判不了这一条

### T8 · 钥匙串降级

- **前置**：Linux 无 Secret Service 的 CI 环境（自动）；macOS 手工用 `SKILLMASTER_HOME` 指向临时目录并拒绝钥匙串写入
- **步骤**：`skmr login` → `skmr doctor`
- **期望**：`login` 退出码 0；`doctor` 指出凭据落在文件、并说明钥匙串不可用

### T9a–T9d · 沙箱内 `doctor`（手工，四家各一次）

- **前置**：四家客户端各装好，沙箱按默认配置开启
- **步骤**：在 agent 的终端里执行 `skmr doctor`
- **期望**：
  - **Claude Code**：沙箱那一行报 `SANDBOX_RUNTIME=…`（它自己的标记，2026-11-08 实测）；输出含 `filesystem.allowWrite`、`"allowedDomains"`、`allowLocalBinding`、`excludedCommands`
  - **Codex**：报 `CODEX_SANDBOX=…`；输出含 `writable_roots`、`network_access`、`allow_local_binding` 与 `profiles.*.network.domains`
  - **Cursor**：报 `CURSOR_SANDBOX=…`；输出含 `additionalReadwritePaths` 与 `"networkPolicy"` / `"allow"`（含回环地址）
  - **WorkBuddy**：没有标记。**沙箱行报 ok 时没有配置块**（`doctor` 只在探测到沙箱、状态目录回退或某项失败时才打印片段）；状态目录写不进去时那条走「多半在沙箱里（客户端没有自报）」，片段含 `sandbox.extraAllowWrite`，通用网络片段说它 5.8.3 默认已放行
  - 片段里的域名必须是**实际配置的服务端 host 与更新通道**，不是写死的 `skillmasterai.com`（v51 迭代 0002）
  - 四家都**不修改**任何配置文件，也**不在 `$HOME` 根目录或 skills 目录里新增探针文件**（状态目录里的 `.write-probe` 是 ADR 0101 允许的那一个，且沙箱场景下它写不进去）

### T13 · 首次执行前确认

- **前置**：一个新 digest，从未在本机跑过
- **步骤**：`skmr run <地址> -- python3 plan.py`（不带 `--yes`）→ 加 `--yes` 再跑一次 → 第三次不带 `--yes`
- **期望**：第一次退出码非 0，输出含「第一次在本机执行这个版本的代码」与 `--yes`；第二次正常执行并记下；第三次直接执行、不再问。**确认发生在路径解析之后**：把命令写成一个不存在的脚本，第一次应当报「找不到脚本」，且**不**留下确认标记（再跑一个正常命令时仍会问）

### T10 · 符号链接

- **前置**：一个含 `scripts/link -> ../data` 的 fixture
- **步骤**：走同步（或上传）
- **期望**：被拒，错误文本含「快捷方式」与「改成真实文件」，不再出现「不安全压缩包」字样

### T11 · 断网时缓存仍能跑

- **前置**：同一 digest 已缓存
- **步骤**：断开网络（或 `SKILLMASTER_SERVER` 指向不可达）后 `skmr run`
- **期望**：成功；首次拉取在同样条件下失败，且报错含「这一步需要联网」

### T14 · 位由路径决定（原先的 T12 已随重放对账一起删除）

- **前置**：一棵树，其中 `scripts/run.sh` 在磁盘上是 `0644`、根目录 `root.sh` 是 `0755`
- **步骤**：提交并发布，再 `skmr skill get --all`
- **期望**：`scripts/run.sh` 落盘是 `0755`、`root.sh` 是 `0644`；`version_file.mode` 分别是 `493` / `420`。同一份内容再提交一次是重放（200），**不会**出现 `mode_changed_on_replay`

### T15 · 帮助不吞 `--` 之后的旗标（回归，见 [`iterations/0004`](iterations/0004-help-stops-at-the-separator.md)）

不对应独立验收条；它守的是验收 2 / 3 所依赖的那条分界线。

- **前置**：一个带 `scripts/tool.py` 的 skill，脚本自身支持 `--help` / `-h`
- **步骤**：`skmr run <地址> --yes -- python3 tool.py --help`；再 `skmr run <地址> --help`
- **期望**：前者打印**脚本自己的**帮助、退出 0（不是 `skmr run` 的帮助）；后者在分隔符之前，仍打印 `skmr run` 的帮助

## 环境与数据

**服务端**：`skillmaster-server/` 下 `./mvnw verify`——**不是 `test`**，`*IT` 只在 `verify` 阶段跑（`pom.xml` 的 surefire / failsafe 分工）。测试库用 `SKILLMASTER_TEST_DB_*` 三件（见 [`.dev-sop/profile.md`](../../../.dev-sop/profile.md) §本地环境）。T1b、T10、T14 是需要数据库的用例。

**CLI**：`skillmaster-cli/` 下 `go test ./...`。端到端用例要真服务端；单元级的路径解析、cwd、确认记账、状态目录降级不依赖它。

**fixture skill**（`skillmaster-cli/internal/testdata/v51-fixture/`，尚未落地，端到端用例前置）：

```
SKILL.md
scripts/hello.sh            0755
scripts/exit_code.py        --code N
scripts/read_neighbor.py    读同目录 data.csv
scripts/data.csv            T4 用：与 read_neighbor.py 同目录
scripts/env_probe.py        打印四个环境变量
scripts/plan.py             与 scripts/lib/plan.py 同名不同路径
scripts/lib/plan.py
scripts/a/x.py  scripts/b/x.py   歧义用
```

打 zip 时用 `zip -X` 保留 unix mode；同步侧的 fixture 是一个固定 commit 的测试仓库。

**清理**：缓存目录在 `${TMPDIR:-/tmp}/skmr/cache/`，状态回退目录在 `${TMPDIR:-/tmp}/skillmaster/state`；CI 里整个 `$TMPDIR` 随作业回收，本地手工测试用完自行删掉（CLI 自己不删，[ADR 0101](../../../docs/decisions/0101-the-cli-never-deletes-files.md)）。

## 结果

**跑过的命令**（2026-11-08）：

- `skillmaster-server/`：`./mvnw verify` → **433 个单元用例 + 651 个集成用例（`*IT`），0 失败 0 错误**，`BUILD SUCCESS`。集成用例走测试库（`SKILLMASTER_TEST_DB_*`）。**`./mvnw test` 不算数**：surefire 不匹配 `*IT`，集成用例只在 `verify` 阶段跑
- `skillmaster-cli/`：`go test ./...` → 13 个包通过；`cmd/skillmaster` 有 3 个失败，**在 `main` 上同样失败**（更新器相关，见下「已知问题」）
- 仓库根：`node scripts/check-file-size.mjs`、`python3 scripts/docs/generate.py --check`、`python3 scripts/docs/impact.py --check`、`python3 .claude/skills/docs-architecture/scripts/check_docs.py` → 全部通过

**端到端（2026-11-08，真服务端 + 真 skill + 真 CLI）**：v51 分支的 jar 连独立库 `skillmaster_v51`，端口 8081；注册了 `e2e-runner`、签了一把 key；夹具 `v51-e2e` 的脚本互相引用（`main.py` → `lib/helpers.py`、`run_all.sh` → `python3 pipeline.py` + `python3 -m lib.report`、同级 `scripts/data/input.csv`、同名 `a/x.py`/`b/x.py`）。跑通的有：跨文件 import、shell→python、嵌套脚本按名字命中、`./run_all.sh`（可执行位）、同名拒绝、缺脚本列候选、环境继承、`--json`、退出码透传、首跑确认、断网跑缓存、`get --all` 的位。**平台上新建的文件也验过**：`skmr skill write … scripts/newtool.py` 入库 `493`，同一条命令写根目录 `notes2.md` 入库 `420`——正是 ADR 0166 要回答的那件事。**四家客户端的沙箱矩阵（T9）仍未跑**。

| 用例 | 结果 | 证据 / 缺什么 |
|---|---|---|
| T1 可执行位（上传→分发） | 通过（服务端 + 端到端） | `ArchiveIT.aScriptUnderScriptsIsExecutableInTheArchive`、`ArchiveIT.theArchiveDoesNotTrustAStoredMode`（把入库的 `mode` 改成 420，下载仍是 0755）；端到端：`skills/e2e-runner/v51-e2e` 的 `scripts/run_all.sh` 在 `get --all` 后是 `-rwxr-xr-x`，`SKILL.md` 是 `-rw-r--r--` |
| T1b 可执行位（入库，两条入口） | 通过 | 上传：`SkillSubmitIT.theExecutableBitFollowsThePath`（同一次提交里，zip 不说位时 `scripts/` 下仍 493、zip 标了位时 `notes.txt` 仍 420）。同步：`导入端到端用例.aSyncedDraftTakesItsModesFromThePaths` |
| T1c `scripts/` 之外不可执行 | 通过（服务端 + 端到端） | `ArchiveIT.aScriptUnderScriptsIsExecutableInTheArchive`：同一次下载里 `SKILL.md` 没有任何执行位；端到端 `get --all` 后 `SKILL.md` 是 `-rw-r--r--`、`scripts/run_all.sh` 是 `-rwxr-xr-x` |
| T1（CLI 落盘） | 通过（端到端） | 见 T1 那一行；另外用 `--no-cache` 直接 `./run_all.sh` 也跑通 |
| T2 退出码一致 | 通过（端到端） | 同一份 skill、同一台机器：本地 `python3 main.py` 与 `skmr run … -- python3 main.py` 输出逐字一致；`python3 -c "sys.exit(7)"` 的退出码原样返回 7 |
| T3、T3b 路径解析 | 单元通过 | `TestRunResolvesOnlyScriptPaths`；`../x.py` 不出 `scripts/`（即使 `scripts/` 下有同名文件也不回退）：`TestRunDoesNotResolveOutsideScripts` |
| T4 默认 cwd | 通过（单元 + 端到端） | `TestRunWorksInScriptsByDefault`；端到端脚本从 `scripts/data/input.csv` 读到文件 |
| T5 环境继承 | 通过（端到端） | `E2E_TOKEN=secret-123` 原样出现在子进程里；`SKILLMASTER_SKILL_DIR` 指向解出来的 skill 根 |
| T6 缓存命中 | 通过（端到端） | 首次下载后重复 `run` 没有再落盘；`--no-cache` 走临时目录 |
| T7 状态目录降级 | 单元通过 | `TestTheStateDirectoryFallsBackWhenTheOverrideIsNotWritable`：落点可写，且提示里**被拒的目录**与**在用的目录**是两个不同的路径。读写分离：`TestAStateFileIsWrittenWhereItCanBeAndReadWhereItIs` |
| T8 钥匙串降级 | 未执行 | 需要 Linux CI 或手工造钥匙串拒绝；`Save` 的写失败分支已改成降级到文件，尚未在真机上验证 |
| T9a–T9d 沙箱内 doctor | 未执行 | 需要在四家客户端里各跑一次 |
| T10 符号链接 | 服务端通过、端到端未跑 | 拒绝本身有 `SkillSubmitIT` 的 `symlink_not_allowed` 断言；中文那句在 `issueMessages` 里，手工跑一次 `submit` 才算验完 |
| T11 断网缓存 | 通过（单元 + 端到端） | 判据有 `TestRunOnlyTreatsATransportFailureAsOffline`；端到端把 `SKILLMASTER_SERVER` 指到不可达端口，仍在 stderr 说明后跑通缓存里的版本 |
| T14 位由路径决定 | 通过（服务端 + 端到端） | `SkillSubmitIT.theExecutableBitFollowsThePath`、`导入端到端用例.aSyncedDraftTakesItsModesFromThePaths`、`VersionEditorIT.aMoveAcrossTheScriptsBoundaryChangesTheStoredMode`（跨 `scripts/` 边界来回改名，位跟着新路径走）；端到端用一棵「磁盘位与规则相反」的树验证过：`scripts/*` 入库 493、`root.sh` 入库 420 |
| T13 首次确认 | 单元通过 | `TestRunAsksOncePerVersion`；「确认发生在路径解析之后」由 `runSkill` 的调用顺序保证，没有单独用例 |
| T15 帮助不吞 `--` 之后 | 通过（单元 + 生产手测） | `TestRequestedStopsAtTheSeparator`、`TestHelpIsRecognisedAtEveryPosition`（只在分隔符之前插旗标）、`TestTheUpdateCheckSurvivesAScriptsOwnHelp`（`--` 之后不再跳过自动更新检查）；生产：`skmr run skillmaster/matlab --yes -- python3 validate_project_manifest.py --help` 打印脚本自己的 argparse 帮助、退出 0，`-h` 同；`skmr run … --help` 仍打印 `skmr run` 的帮助 |

## 已知问题

| 问题 | 状态 | 说明 |
|---|---|---|
| `TestTheCheckIsThrottledToOnceADay`、`TestANewerBuildIsInstalledAndTheCommandIsUntouched`、`TestATamperedPackageLeavesTheBinaryAlone` | 已复现（本版之外） | 在 `main` 上同样失败，属更新器路径的既有问题，不是本版引入 |
| 四家客户端的沙箱矩阵（T9a–d）、钥匙串降级（T8）、符号链接中文文案（T10） | 未执行 | 需要在真客户端里各跑一次；T10 还差一次手工 `submit` 的文案确认 |
| 存量已发布版本的可执行位 | 已修复 | `V41__mode_follows_the_path.sql` 按路径重算了**全部**行（含已发布），本地实测旧版本 1.0.0 在迁移后 `./run_all.sh` 可执行。这是一次数据迁移，不是命令；[ADR 0064](../../decisions/0064-a-draft-may-change-a-published-version-may-not.md) 的冻结口径在 [ADR 0166](../../decisions/0166-the-executable-bit-follows-the-path.md) 里被显式修订 |
