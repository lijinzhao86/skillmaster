# v37-cli-page-actions · 测试方案

> **最后更新**：2026-11-01
> **所属版本**：[`README.md`](README.md)

## 验收映射

| PRD 验收 | 用例 |
|---|---|
| #1 七个带地址的页面动作：够档位→打开、退出 0；不够→不打开、说明、退出 1；判定不了→打开并说明；`skills publish` 直接打开 | T1、T2、T3、T3b |
| #2 `skill delete` 不带 `--yes`：零写请求、打印后果与命令、退出非 0；判定不了只说「没能确认」 | T4 |
| #3 `skill delete --yes`：四种结果（删 / 上线过 409 / 非 owner 403 / 看不到 404） | T5、T6、T6b |
| #4 上线→下架→再删必须被拒 | T6、T7 |
| #5 条件在服务端一条语句内、无先查后删窗口；发布输掉行锁就回滚 | T7 |
| #6 页面动作不写任何东西；有凭据最多两次只读（作者面一次，失败时消费者面一次）；`skills publish` 零请求 | T8 |
| #7 `skill open` / `version open` 同一套预检（按分区 / 按版本可见性）；`skills pending` 不再断言档位 | T9、T9b、T10 |
| #8 旧名移除 | T11 |
| #9 同意屏文案与设计稿一致 | T12 |
| #10 帮助、README、ADR、三份文档一致 | T13 |

## 用例

### T1 · 档位够：打开页面

- **前置**：假服务端返回 `rung=maintain`、`owned=true`；假打开函数成功。
- **步骤**：`version publish dev/x@d1`、`version rollback dev/x@live`、`version revoke dev/x@h1`、`version restore dev/x@r1`、`skill offline dev/x`（offline=false）、`skill online dev/x`（offline=true）、`skill visibility dev/x`（rung=admin）。
- **期望**：七条都打开对应地址、退出 0、stdout 第一行「还没执行：…」。

### T2 · 档位不够：不打开

- **前置**：作者面 `rung=write`（对 maintain 类）、`rung=browse`（对 owner 类）；或作者面 403、消费者面报 `invoke`。
- **步骤**：TD §CLI 命令面表里的七条逐条跑；再加 `skill open --tab settings`（`browse`）。
- **期望**：假打开函数**未被调用**；退出 1；输出写出所需档位与调用者当前档位（「要 X 档，你在这个 skill 上是 Y」）并给出地址。

### T3 · 判定不了：照常打开并说明

- **前置**：三种：无凭据；两个读请求都 500；两个读请求都 404。
- **期望**：都打开页面、退出 0、stderr 含「没能预检」；`--json` 的 `opened` 反映实际结果。

### T3b · `skills publish` 直接打开

- **步骤**：`skills publish --print`。
- **期望**：零请求、地址是 `/publish`、退出 0、首句「还没执行」。

### T4 · `skill delete` 不带 `--yes`

- **步骤**：分别对「从未上线」「上线过」「读不到（无凭据）」「作者面拒绝、消费者面 `write`」四种 skill 跑。
- **期望**：四种都不发写请求（读请求可以有）；第一种打印后果与 `skmr skill delete <地址> --yes`；第二种打印设置页地址；第三种只说「没能确认它是否上线过」并给设置页地址，**不出现「它从未上线」，也不出现 `--yes` 或「确认后执行」**；四种退出 1。`--json` 时「读不到」报 `precheck_unavailable`，非 owner 报 `insufficient_permission`（不是 `confirmation_required`）。**帮助里不出现 `--yes`**（见 T4b）。

### T4b · 确认型命令的帮助（`skill delete`、`version discard`）

- **步骤**：分别跑两个 `--help`。
- **期望**：用法、参数、输出、失败码、例子都不含 `--yes`；例子是只说明后果的那一条。

### T4c · 页面动作的帮助写明在网页上完成

- **步骤**：九个页面动作各跑一次 `--help`。
- **期望**：第一行带网页/打开页面的标记；正文出现「网页」，且写「命令不执行…确认在网页上由人完成」；`skill delete` 写「由人在网页上确认删除」。

### T5 · `skill delete --yes` 成功

- **前置**：服务端 204。
- **期望**：退出 0；输出「已删除」；`--json` 为 `{"action":"delete","performed":true,…}`。

### T6 · `skill delete --yes` 被拒

- **前置**：服务端 409（上线过）/ 403（非 owner）/ 404（看不到）。
- **期望**：三种都不删；409 给出设置页地址；403 说「没有权限」；404 说「看不到」；都退出 1。

### T6b · 非 owner 与看不到（服务端）

- **步骤**：一个对该 skill 无任何关系的已注册用户调 `POST …/delete`。
- **期望**：404 `skill_not_found`，与不存在的名字同一个回答；行未删除。

### T7 · 服务端条件（IT）

- **步骤**：建 skill → 提交草稿 → 上线 → 下架 → 调删除。
- **期望**：409 `skill_published`，`deleted_at` 仍为空。
- **并发**：删除赢下之后，发布方的 `recordPublish` 影响 0 行、`skillDeleted` 为真，`VersionPublisher` 抛 `SkillDeletedException`；在 `TransactionTemplate` 里跑一遍发布，断言异常与回滚（版本仍是草稿、`first_published_at` 仍为空）。另有一条单测钉住批量路径把这条例外当成本行的失败，而不是截断 NDJSON。
- **迁移回填**：重放 `V31` 文件里的 `UPDATE`（从迁移文件读，不抄一份）→ `ever_published_at` 非空。

### T8 · 页面动作的副作用

- **步骤**：T1 的七条各跑一次，假服务端记录请求。
- **期望**：作者面答得出时每个命令恰好一次 GET；没有任何写方法；`skills publish` 零请求。

### T9 · `open` 纳入预检

- **步骤**：`skill open dev/x --tab settings`（作者面 403、消费者面 `browse`）；`skill open dev/x --tab info`（同一凭据）。
- **期望**：前者不打开、说明「设置要管理档」、退出 1；后者打开、退出 0。

### T9b · `version open` 判版本可见性

- **步骤**：作者面 404、消费者面 `browse`；以及两个面都 404。
- **期望**：前者不打开，说明「看不到这一版：草稿要写入档……」，退出 1；后者照常打开并写明没能预检。

### T10 · `skills pending`

- **期望**：结尾提示不再出现「你去点上线」式断言；只说明「上线要在网页上由人确认」。

### T11 · 旧名移除

- **步骤**：`version delete|offline|online`、`version --help`。
- **期望**：前三条退出 2；帮助不列出它们。

### T12 · 同意屏（设计 + 产品）

- **步骤**：`interaction-check`（设计件）、`tests/cli-sign-in.test.tsx`、`tests/consent.test.tsx`。
- **期望**：新增从句「从未上线过的 skill 还可以删除」在设计源、构建产物、`CliSignInPage` 三处逐字一致，`ConsentPage` 在该从句上一致（前半句是既有的、已记录的分叉）；断言从「不许出现删除」改为「『删除』只以该从句出现」。

### T13 · 文档一致

- **步骤**：`check_docs.py`、README 退出码段落、ADR 0127、PRD 映射表逐行对照。
- **期望**：校验通过；三处说法一致。

## 环境与数据

- CLI 单测沿用 `pointAtServer` / `captureStdout` / 假打开函数；不发真请求。
- 服务端 IT 用测试库 `skillmaster_test`；迁移由 Flyway 跑，回填用 IT 里的直接 SQL 造旧数据。
- 同意屏：设计件用 `interaction-check.mjs`；产品用 Vitest。
- **`qa/role-matrix` 的口径随门移动**（2026-11-01）：`version publish` 的探针从「所有档都拒绝」改成「`maintain` 及以上打开操作页并退出 0、以下拒绝并给地址」，并加 `--print` 以免探针打开浏览器；交接测试同步改写。这是尺子跟着能力边界走，不是为了让检查变绿。

## 结果

| 项 | 命令 / 方式 | 结果 |
|---|---|---|
| 服务端全量 | `./mvnw -B verify`（测试库 `skillmaster_test`） | IT `Tests run: 544`、单元 `Tests run: 417`，均为 0 失败 0 错误；BUILD SUCCESS |
| 服务端 · 新增用例 | `AuthorSkillIT` | 16 条全过；其中新增 9 条：「从未上线可删」「上线过 409」「上线→下架 409」「协作者 403」「陌生人 404」「作者页三个字段」「并发投影写」「V31 回填」「发布输掉行锁的回滚」 |
| 服务端 · 批量上线 | `PublishBatchDeletedTest` | 1 条：删除赢下时该行返回 `skill_deleted`，不中断整批 |
| 架构 / 表归属 | `ArchitectureTest`、`TableOwnershipTest` | 11 条全过 |
| CLI 全量 | `go test ./...`、`go vet ./...`、`gofmt -l` | 全部通过；新增 `TestConfirmationHelpDoesNotMentionTheSwitch`（两个确认命令）、`TestPageActionHelpSaysTheActionHappensInTheBrowser`（九个页面动作）、四条 `skill delete` 分支测试（可删 JSON / 非 owner / 消费者面档位 / 判定不了）；`page_actions_test.go` 覆盖 T1（七条打开）、T2（七条拒绝）、T3（无凭据 / 两读都 500 / 两读都 404）、T3b、T4–T6、T8（七条各一次 GET 到作者面、非 GET 直接失败）、T9/T9b 与 `--json` 形状，`pending_test.go` 覆盖 T10；golden 的 13 条登记覆盖实际差异（四条 stderr 的旧 v22 条目已补写 v37 原因） |
| 设计件 | `interaction-check.mjs`（控制台演示件） | 0 失败；同意屏两条新断言（逐字钉整条、只出现一次「删除」）通过；两份演示件重建后与产物逐字节一致 |
| 控制台 | `tsc --noEmit`、`vitest run tests/cli-sign-in.test.tsx tests/consent.test.tsx` | 通过；16 条全过 |
| 行数 | `node scripts/check-file-size.mjs` | 通过（新增文件均在上限内） |
| 文档 | `check_docs.py` | 通过 |
| 视觉 | 人看 | **待做**（`design/shots/states/12-consent.png`） |

## 已知问题

无。
