# v44-cli-read-write-contract · 测试方案

> **最后更新**：2026-11-04
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| A1 `draft create` 从线上版本建出内容不变的草稿，并给出可复制的下一步命令 | T1、T2 | 未执行 |
| A2 `--from` 指定底稿；连建两份同内容草稿 | T2 | 未执行 |
| A3 `write` / `edit` / `rm` / `mv` 不再接受 `@new`，help 里没有 `@new` | T3 | 未执行 |
| A4 `write --from` 写文本与二进制；多个来源落进目录 | T4 | 未执行 |
| A5 `put` / `download` 退场并给迁移提示 | T5 | 未执行 |
| A6 `get` 单文件不变；`get --all` 写整版；非空目录拒绝 | T6 | 未执行 |
| A7 覆盖保护：未读 / 读过未变 / 读后变过 / 部分读 | T7 | 未执行 |
| A8 `edit` 的 stdin、`--stdin-json`、批量原子 | T8 | 未执行 |
| A9 `SKILL.md` 裸读在四个视图命令上拒绝；写版本或已 invoke 放行 | T9 | 未执行 |
| A10 生成物一致；用户文档无旧指引 | T10 | 未执行 |
| A11 服务端与 CLI 测试全绿；行数检查通过 | T11 | 未执行 |
| A12 版本码不存在时列出草稿 | T12 | 未执行 |
| A13 已有草稿时拦截 `draft create` 与 `submit`；`--new` 隐藏；协作者不拦 | T13 | 未执行 |

## 用例

### T1 · `draft create` 从线上版本原样复制

- **前置**：测试库里有一份已上线版本 `v44-e2e/owned@1.0.0`（fixture 直接走发布用例；不碰真实账号的 skill）。CLI 已登录同一账号。
- **步骤**：`skmr draft create v44-e2e/owned --name 第二版`；`skmr version list v44-e2e/owned --status draft`；`skmr skill ls v44-e2e/owned@<新码>`；对两个文件各跑一次 `skill get` 与线上版本比较。
- **期望**：退出 0；输出有版本码与一条带该码的下一步写命令；草稿列表出现该码；文件清单与字节和 `1.0.0` 一致。

### T2 · `--from` 指定底稿；两份同内容草稿并存

- **前置**：同 T1，另有草稿 `7V5N319A`。
- **步骤**：`skmr draft create v44-e2e/owned --from 1.0.0 --name A`；再跑一次 `--name B`；对两份各跑 `skmr version diff v44-e2e/owned@<码> --from 1.0.0`。
- **期望**：两次都退出 0，版本码不同；两次 diff 都为空；第二份不因与第一份同内容被拒。

### T3 · `@new` 退场

- **前置**：CLI 已构建。
- **步骤**：分别跑 `skmr skill write v44-e2e/owned@new x.md`、`skill edit …@new …`、`skill rm …@new x.md`、`skill mv …@new a.md b.md`；再跑四条命令的 `--help`。
- **期望**：四条命令都退出非零，提示改用 `skmr draft create`；四条 `--help` 的文本里 grep 不到 `@new`。

### T4 · `write --from` 写文本、二进制与多文件

- **前置**：一份草稿码。
- **步骤**：准备带 CRLF + BOM 的 `a.md` 与一份 PNG；跑 `skmr skill write addr@<码> a.md --from ./a.md --overwrite`（文件已存在时先 `read`）、`... logo.png --from ./logo.png`、`... refs/ --from ./x.txt --from ./y.txt`；各跑 `skill get` 取回比较。
- **期望**：文本按归一后的字节保存（CRLF→LF、BOM 去掉、NFC）；PNG 与本地逐字节相同；`refs/` 下出现 `x.txt` 与 `y.txt`。

### T5 · 旧动词退场

- **步骤**：`skmr skill put v44-e2e/owned@<码> ./a.md refs/a.md`；`skmr skill download v44-e2e/owned@1.0.0 --into ./tmp`。
- **期望**：两条都退出非零；`put` 的提示含 `write --from`，`download` 的提示含 `get --all`；命令在发请求前就拒绝（离线也能看到提示）。

### T6 · `get` 单文件与 `--all`

- **前置**：一份草稿码，另有一个非空目录 `./nonempty`。
- **步骤**：`skmr skill get addr@<码> SKILL.md -o ./one.md`；`skmr skill get addr@<码> --all --into ./whole`；`skmr skill get addr@<码> --all --into ./nonempty`。
- **期望**：第一条退出 0，字节与远端一致；第二条写出整版与 `.skillmaster/metadata.json`；第三条退出非零并说明目录非空。

### T7 · 覆盖保护

- **前置**：草稿里有 `SKILL.md`，本机没有它的读记录。
- **步骤与期望**：
  1. `write addr@<码> SKILL.md --overwrite --from ./new.md` → 退出 1，提示先 `read`。
  2. `skill read addr@<码> SKILL.md` → 成功；立刻重跑第 1 步 → 退出 0，stderr 有 `将改动 N 行`。
  3. 第 2 步之后，用另一条命令改同版另一个文件（换掉版本 ETag），再跑第 1 步 → 退出 1，提示读后变过。
  4. 只跑 `skill head addr@<码> SKILL.md -n 5`，再跑第 1 步 → 退出 1。
  5. `skill read addr@<码> SKILL.md --limit 5`，再跑第 1 步 → 退出 1。
  6. 第 2 步成功后，另起进程在读写之间改同文件 → 写回得到 409，不覆盖。

### T8 · `edit` 的输入通道

- **前置**：一份草稿码，`SKILL.md` 里有三处可唯一匹配的文本。
- **步骤**：
  1. `printf '旧' | skmr skill edit addr@<码> SKILL.md --old-file - --new 新`。
  2. `printf '{"old":"旧","new":"新"}' | skmr skill edit … --stdin-json`。
  3. 批量：三处都存在的 `{"edits":[…]}`；再跑一份第二处故意写错的。
  4. `--stdin-json` 同时给 `--old`。
- **期望**：1、2 各产生一次改动；批量成功一次落一个版本、三处都改到；第二处失败时退出非零、错误带 `第 2 处`、`version list` 没有新版本；4 是用法错误（退出 2）。

### T9 · `SKILL.md` 裸读拦截

- **前置**：本机没有 invoke 过 `v44-e2e/owned`；另有一份已 invoke 过的草稿码。
- **步骤**：裸地址分别跑 `read` / `cat` / `head` / `tail` 读 `SKILL.md`；裸地址读 `README.md`；`@1.0.0` 读 `SKILL.md`；先 `invoke` 再裸读；`cat` 同时给 `README.md` 与 `SKILL.md`。
- **期望**：前四条退出 2，提示 `skmr skill invoke`；读 `README.md` 成功；`@1.0.0` 与 invoke 后成功；`cat` 混合路径整条拒绝，stdout 为空。

### T10 · 生成物与用户文档

- **步骤**：`python3 scripts/docs/generate.py --check`；`python3 .claude/skills/docs-architecture/scripts/check_docs.py`；在 `docs/current/`、`skills/`、根 `README.md` 与安装文档里 `grep -n 'skill put\|skill download\|@new'`；读 `skills/skillmaster/SKILL.md` 的 read/invoke 段。
- **期望**：两个检查退出 0；grep 只命中迁移提示与历史版本；技能正文写明 `SKILL.md` 裸读会被拒绝。

### T11 · 全量测试与行数

- **步骤**：`cd skillmaster-server && ./mvnw verify`；`cd skillmaster-cli && go test ./...`；在仓库根 `node scripts/check-file-size.mjs`。
- **期望**：三条都退出 0；没有新增超限文件（baseline 只允许缩小）。

### T12 · 版本码不存在时列出草稿

- **前置**：该 skill 下有至少一份草稿。
- **步骤**：`skmr skill write v44-e2e/owned@NOSUCH SKILL.md --from ./x.md`。
- **期望**：退出非零；输出列出当前草稿的版本码（或指向 `version list --status draft`），不是只有一句 `not_found`。

### T13 · 已有草稿时的拦截

- **前置**：当前账号在 `v44-e2e/owned` 下有一份草稿。
- **步骤**：
  1. `skmr draft create v44-e2e/owned --name 另一版` → 期望退出 1，输出草稿码与「先问用户」，`version list` 没多出新行。
  2. `skmr skill submit ./dir --name 另一版` → 同上。
  3. `skmr draft create --help` / `skmr skill submit --help` → grep 不到 `--new`。
  4. 非交互 shell 里带 `--new` 重跑 → 期望新建成功。
  5. 用另一个账号在该 skill 下留一份草稿，再以当前账号跑第 1 步 → 不被协作者的草稿拦。

## 环境与数据

- **数据库**：Docker 里的 `skillmaster-postgres`（`compose.yaml`）。本 worktree 专用两个库：
  ```bash
  docker exec skillmaster-postgres psql -U skillmaster -d postgres \
    -c 'CREATE DATABASE skillmaster_v44;' \
    -c 'CREATE DATABASE skillmaster_test_v44;'
  ```
- **`.env`**：从主工作区复制 `cp /Users/lijinzhao/Developer/skillmaster/.env .env`，并加：
  ```
  SKILLMASTER_DB_URL=jdbc:postgresql://localhost:5432/skillmaster_v44
  SKILLMASTER_TEST_DB_URL=jdbc:postgresql://localhost:5432/skillmaster_test_v44
  ```
  测试库读 `SKILLMASTER_TEST_DB_URL` / `SKILLMASTER_TEST_DB_USER` / `SKILLMASTER_TEST_DB_PASSWORD`（`skillmaster-server/src/test/resources/application-test.yml:6`）。
- **服务端测试数据**：`./mvnw verify` 每次重建 schema；T1/T2 的「已上线版本」由 IT fixture 走发布用例造出，不依赖网页。
- **CLI 端到端**：本地起服务端（`8080`），登录测试账号；发布动作仍只在 IT fixture 里造，不在 CLI 里调发布接口。测试 skill 统一用 `v44-e2e/owned`，跑完在测试库里丢弃草稿；不碰真实账号的 skill。
- **清理**：测试库可整库重建；CLI 侧只丢弃草稿，不删文件；本机状态目录里的读记录随测试账号独立，不手工清。

## 结果

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 · `draft create` 原样复制 | **未执行（端到端）**；服务端侧通过 | `CliAuthoringIT.identicalContentIsAllowedInADraftAndRefusedAtPublish`、`VersionEditorIT.aCreateWithoutAnEditCopiesTheBase`（201，读回正文与底稿一致）。CLI 端到端仍待本地 8080 + 测试库按 §环境与数据 跑一次。 |
| T2 · `--from` 与两份同内容草稿 | **未执行（端到端）**；服务端侧通过 | `VersionEditorIT.copiedContentMayCoexistWithAnotherDraft`（201，两份 digest 相同）、`DraftFileIT.aDraftMayHoldContentThatAlreadyExistsAndPublishingItIsRefused`。CLI 单测覆盖 `--from` 透传：`TestDraftCreateWithFromPassesTheVersionThrough`。 |
| T3 · `@new` 退场 | 通过 | `TestTheFileVerbsRefuseANewDraftBeforeAnyRequest`、`TestEditRefusesANewDraft`、`TestWriteIsCreateByDefault/@new is refused`；四条命令的 `--help` 均 grep 不到 `@new`。 |
| T4 · `write --from` | 通过（CLI 侧） | `TestWriteFromLocalFiles`：新文件、多文件进目录与基线刷新、文本与二进制原字节、覆盖需 `--overwrite`、读证明与 `将改动 N 行`。文本归一由服务端 v33 规则负责，本版不重复测。 |
| T5 · 旧动词退场 | 通过 | `TestRetiredVerbsPointAtTheirReplacement`：两条都退 2、提示含 `write --from` / `--all`，且不发请求。 |
| T6 · `get` 单文件与 `--all` | 通过（CLI 侧） | `TestGetAllWritesTheWholeVersion`：整版与旁车写入、计数不含旁车、非空目录拒绝且不发请求。单文件形态由既有 `get` 测试覆盖。 |
| T7 · 覆盖保护 | 通过（CLI 侧） | `TestWriteFromLocalFiles`（未读 / 已读放行 / 读后变过 / `head -n 1` 不算读过）、`TestWriteNeedsAllowEmpty`（空内容仍要显式）。读写之间的并发 409 由服务端 `If-Match` 保证，端到端未跑。 |
| T8 · `edit` 输入通道 | 通过 | `TestEditStdinChannels`：`--old-file -`、`--stdin-json` 单条、批量一次 PUT、第二处失败整批不落盘并报 `第 2 处`、`--stdin-json` 与 `--old` 互斥（退 2）。 |
| T9 · `SKILL.md` 裸读拦截 | 通过 | `TestBareSkillMdReadIsRefused`：`read` / `cat` / `head` / `tail`（含 `--offset` / `--limit`）退 2；`README.md`、`@版本`、已 invoke 放行；`cat` 混合路径整条拒绝、stdout 为空、不发请求。 |
| T10 · 生成物与用户文档 | 通过 | `generate.py --check`、`impact.py --check`、`check_docs.py` 均退 0；`skills/skillmaster/SKILL.md` 已写明裸读 `SKILL.md` 会被拒绝；`docs/current/` 与 `skills/` 里旧动词只剩 TODO #1 的历史描述（已改为 `get --all`）。 |
| T11 · 全量测试与行数 | 通过 | `skillmaster-cli`: `go test ./...` 全绿；`skillmaster-server`: `./mvnw verify` — 549 个集成测试，0 失败（另有 310 个单元测试）；`node scripts/check-file-size.mjs` 退 0。 |
| T12 · 版本码不存在时列出草稿 | 通过 | `TestWriteWithAMissingCodeListsDrafts`：404 后列出草稿码与名称，没有草稿时给出 `draft create`。 |
| T13 · 已有草稿时的拦截 | 通过 | `TestDraftCreateRefusesWhenTheCallerHasADraft`：自己的草稿列出并写「先问用户」、`--new` 放行、协作者草稿不拦；`TestSubmitRefusesWhenTheCallerHasADraft`：上传前拒绝，`--new` 后上传一次；两处 `--help` 都没有 `--new`。 |

## 已知问题

（实现过程中发现、本版不修的问题记在这里，每条标 `已复现` / `部分复现` / `未验证` / `已修复`。）
