# v64-feedback · 测试方案

> **最后更新**：2026-11-13
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 模板 | T1 | 通过 |
| #2 按文件提交、库行与内容一致 | T2 | 通过 |
| #3 标准输入提交 | T3 | 通过 |
| #4 空 / 超长 / 同意版本过期被拒 | T4 | 通过 |
| #5 预览不发、发送记录同意 | T5、T2 | 通过 |
| #6 限流 | T6 | 通过 |
| #7 运营可查 | T7 | 通过 |
| #8 隐私条款 | T8 | 未执行 |
| #9 skill 引导 | T9 | 通过（人工核对） |
| #10 诊断白名单 | T10 | 通过 |
| #11 未登录指引 | T11 | 未执行 |
| #12 长期保留与对象存储副本 | T12 | 通过 |
| #13 敏感信息检测 | T13 | 通过 |
| #14 回执与撤回删除 | T14、T15、T16 | 通过 |

**没有对应用例的验收标准是不合格的验收标准**——本表没有空行。T15、T16 是 #14 的拒绝态。

## 用例

### T1 · 模板

- **前置**：CLI 已构建；不需要登录，不需要服务端。
- **步骤**：运行 `skmr feedback template`。
- **期望**：打印六节（我想做什么 / 发生了什么 / 怎么复现 / 环境 / 已经试过什么 / 不要写），「不要写」一节列出密钥、密码、私有代码、个人信息、对话全文；退出码 0；不发任何网络请求。

### T2 · 按文件提交

- **前置**：测试账号已登录；服务端与测试库在跑；`f.md` 是一份符合模板的文档（含中文与 emoji）。
- **步骤**：`skmr feedback send --file f.md --consent`。
- **期望**：stdout 打印 `FB-YYYYMMDD-XXXXXX` 回执；退出码 0。库里 `user_feedback` 恰有一行：`content` 与文件逐字一致（密钥前缀替换处除外）、`content_sha256` 等于服务端对 `content` 的 SHA-256、`consent_version` 等于当前隐私政策版本、`consent_at` 非空且为 RFC3339 UTC、`user_id` 是当前账号。

### T3 · 标准输入提交

- **前置**：同 T2。
- **步骤**：`cat f.md | skmr feedback send --consent`。
- **期望**：与 T2 相同；stdin 不是终端时不需要 `--file`。

### T4 · 拒绝态

- **前置**：同 T2。
- **步骤**：
  1. `printf '' | skmr feedback send --consent`
  2. 生成 20001 个码点的文档，`skmr feedback send --file big.md --consent`
  3. 把服务端的隐私版本配置改掉后，用改版前拿到的同意说明重试一次
- **期望**：三种都退出码非 0，stderr 分别给出「内容为空」「超过 20000 个字符（实际 20001）」「同意说明已更新，请重新预览」；**库里没有新增行**。

### T5 · 预览不发

- **前置**：同 T2；抓包代理或服务端访问日志可看到请求。
- **步骤**：`skmr feedback send --file f.md`（不带 `--consent`）。
- **期望**：stdout 打印将发送的完整文档、同意说明与将发送的字段清单；退出码非 0；**没有任何 POST `/api/v1/feedback`**（预览会 `GET /api/v1/feedback/consent` 取当前的同意说明，这是允许的）；库里没有新增行。

### T6 · 限流

- **前置**：一个干净账号；把限流窗口调短或直接在同一小时内提交 10 次（测试数据可用循环）。
- **步骤**：第 11 次 `skmr feedback send --file f.md --consent`。
- **期望**：退出码非 0；错误码 `feedback_throttled`，消息带重试时间（RFC3339）；库里仍是 10 行。

### T7 · 运营可查

- **前置**：T2 已提交一条；数据库可连。
- **步骤**：
  1. 按回执号查 `SELECT * FROM user_feedback WHERE receipt = '<回执>'`。
  2. 按时间范围查 `SELECT * FROM user_feedback WHERE created_at >= :from AND created_at < :to`。
- **期望**：两次都能查到该行，`content` 与提交内容一致。按回执查走 `receipt` 唯一索引；时间范围有 `created_at` 索引可用（测试库数据量小，不强制断言执行计划）。

### T8 · 隐私条款

- **前置**：站点与控制台构建成功。
- **步骤**：打开 `/privacy`，找到「你主动提交的反馈」一节；对照 `design/src/home/legalText.ts` 与 `skillmaster-site/src/legal/legalText.ts`。
- **期望**：两处文本一致；含收集范围、用途、保留期（长期保留、至少 6 个月）、不向第三方提供、撤回与删除路径、以及「我们无法核验 agent 是否征求过同意」的边界。

### T9 · skill 引导

- **前置**：仓库工作区。
- **步骤**：检查 `skills/skillmaster/SKILL.md` 与 `skills/skillmaster-guide/` 的对应一节。
- **期望**：正文含「卡住或命令持续失败时写反馈文档并运行 `skmr feedback send`」；guide 有一节讲模板、预览、`--consent`、删除；命令与 `skmr feedback --help` 一致。

### T10 · 诊断白名单

- **前置**：抓包代理可看到请求体。
- **步骤**：
  1. `skmr feedback send --file f.md --consent`（不带 `--attach-diagnostics`）
  2. `skmr feedback send --file f.md --consent --attach-diagnostics --command "skmr skill invoke" --error-code skill_deleted`
- **期望**：第一次请求体没有 `diagnostics` 键；第二次只有 `cli_version`、`os`、`arch`、`at`、`command`（只存第一个词）、`error_code`；额外字段被服务端丢弃。

### T11 · 未登录

- **前置**：清空本机凭据，或 `SKILLMASTER_API_KEY` 无效。
- **步骤**：`skmr feedback send --file f.md --consent`。
- **期望**：退出码非 0；消息给出两条路——`skmr login`，以及登录不可用时发邮件到 `contact@skillmasterai.com`；没有 POST 到服务端。

### T12 · 长期保留与归档

- **前置**：服务端 IT 环境；`BlobStore` 用 mock 捕获 `putObject`（同 `ActivityArchiveJobIT` 的做法）。
- **步骤**：写入一条反馈；运行归档任务。
- **期望**：`archive_key` 形如 `feedback/YYYY/MM/DD/<receipt>.ndjson.gz`、`archived_at` 非空；上传的字节是 gzip NDJSON 且能解出该条 `id`；**数据库行仍在**，再次运行归档不会重复上传。

### T13 · 敏感信息检测

- **前置**：同 T2；准备四份文档：手机号、身份证号、银行卡号，以及只含 `sk-` 前缀密钥的一份。
- **步骤**：逐份 `skmr feedback send --file <文档> --consent`；再对手机号那份加 `--allow-sensitive`。
- **期望**：前三份打印命中类别与次数、退出码非 0、库无新增；加 `--allow-sensitive` 后成功提交。密钥那份**不要求确认**：stderr 报告「已将 1 处密钥前缀替换为 [已隐藏]」，提交成功，`content` 里只有 `[已隐藏]`、没有原值（按 TD §开放问题 3 的倾向）。

### T14 · 回执与撤回删除

- **前置**：T2 已提交一条；该条已归档（`archive_key` 非空）；`BlobStore` mock 记录 `deleteObject`。
- **步骤**：
  1. `GET /api/v1/notifications` 用同一账号读通知。
  2. `skmr feedback delete <回执> --yes`。
- **期望**：第 1 步有一条 `announcement` 形状的通知，`title` 含回执号，`body` 含撤回命令；第 2 步退出码 0；`user_feedback` 行被删、`deleteObject` 被调用一次且键等于 `archive_key`；再次删除同一回执得到 `feedback_not_found`。

### T15 · 删别人的回执

- **前置**：账号 A 提交一条；账号 B 已登录。
- **步骤**：B 运行 `skmr feedback delete <A 的回执> --yes`。
- **期望**：`404 feedback_not_found`；A 的行与归档对象都在。

### T16 · 删除需要确认

- **前置**：同 T2。
- **步骤**：`skmr feedback delete <回执>`（不带 `--yes`）。
- **期望**：stderr 打印将要删除的回执与 `--yes` 提示；退出码非 0；**没有 DELETE 请求**；行还在。

## 环境与数据

- **服务端 IT**：继承 [`AbstractIT`](../../../skillmaster-server/src/test/java/com/skillmasterai/support/AbstractIT.java)；测试库由 `SKILLMASTER_TEST_DB_*` 指向；每个用例前跑 `@Sql("/sql/truncate-business-tables.sql")`。归档用 mock `BlobStore` 捕获上传与删除。
- **CLI**：`skillmaster-cli/` 下 `go build ./...`；端到端时 `SKILLMASTER_SERVER=http://127.0.0.1:8080`，用一个真实测试账号登录。
- **本地服务端**：按 `.dev-sop/profile.md` 的「本地环境」起库与栈；OSS 用测试桶或 mock。
- **数据**：反馈文档用固定三份（正常、中文 + emoji、含敏感信息）；T13 另备四份敏感样本（手机号、身份证号、银行卡号、只含密钥前缀）。回执号随机，不写死。
- **清理**：`truncate-business-tables.sql` 清表；删除测试桶里的 `feedback/` 前缀对象。

## 结果

**每条都要有证据**（命令输出、抓包、库表查询、对象存储键），不要只写「通过」。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `go test ./cmd/skillmaster -run TestFeedbackTemplateIsLocalAndComplete` |
| T2 | 通过 | 服务端 `FeedbackIT.submittingStoresTheDocumentAndNotifiesItsAuthor`（含 `source=cli` 与 `consent_at`）；CLI `TestFeedbackSendWithConsentPostsTheDocument` |
| T3 | 通过 | `TestFeedbackFromStandardInput`（标准输入 → POST 体含原文） |
| T4 | 通过 | `FeedbackIT.emptyTooLongAndStaleConsentAreRefusedWithoutWriting`（三种拒绝，库无新增） |
| T5 | 通过 | `TestFeedbackPreviewSendsNothing`（断言 POST 次数为 0） |
| T6 | 通过 | `FeedbackIT.theEleventhSubmissionWithinAnHourIsRefused`（429 + `feedback_throttled`） |
| T7 | 通过 | `FeedbackIT.submittingStoresTheDocumentAndNotifiesItsAuthor` 里按回执查完整行、按时间范围查到同一 `content` |
| T8 | 未执行 | 需要浏览器打开 `/privacy` 看两处页面；文本已同步到 `design/src/home/legalText.ts` 与 `skillmaster-site/src/legal/legalText.ts` |
| T9 | 通过（人工核对） | `skills/skillmaster/SKILL.md` 新增「卡住了」；`skills/skillmaster-guide/SKILL.md` 与 `trouble/feedback.md` 新增入口与整篇 |
| T10 | 通过 | `FeedbackIT.diagnosticsKeepOnlyTheWhitelistedKeys`（`evil` 被丢弃）；`TestFeedbackAttachDiagnosticsCarriesOnlyTheWhitelistedFields`（请求体只有六个键） |
| T11 | 未执行 | 没有无凭据环境的自动化用例：CLI 在无凭据时会尝试浏览器登录，测试无法稳定停在该分支 |
| T12 | 通过 | `FeedbackArchiveJobIT.writesOneCopyPerRowAndLeavesTheRowInPlace`（gzip NDJSON 含 `id`、`receipt`、`source`，行保留，二次归档为 0）；`markArchivedIsConditionalAndAMissingRowStaysMissing`（写回条件与缺失行）；`FeedbackArchiveRecordIT`（撤回后的对象清理、以及行仍在时不删对象） |
| T13 | 通过 | 三类分类、同区分隔符、换行边界与普通英文词由 `FeedbackSafetyTest` 覆盖；CLI 拒绝路径以手机号为代表（`TestFeedbackSensitiveContentRefusesAndPostsNothing`）；密钥替换由 `TestFeedbackSecretIsRedactedBeforeItTravels` 与 `FeedbackIT.sensitiveContentNeedsAnExplicitConfirmationAndSecretsAreRedacted` |
| T14 | 通过 | 通知：`FeedbackIT.submittingStoresTheDocumentAndNotifiesItsAuthor`（含 `--yes`）；撤回：`FeedbackArchiveJobIT.withdrawingDeletesTheArchiveObjectBeforeTheRow`、`FeedbackIT.deletingIsScopedToItsOwnerAndRemovesTheRow` |
| T15 | 通过 | `FeedbackIT.deletingIsScopedToItsOwnerAndRemovesTheRow`（他人回执 → 404，行仍在） |
| T16 | 通过 | `TestFeedbackDeleteRequiresYesAndSendsNothingWithoutIt`（无请求、退出 1） |

**未执行的两条**：

- **T8（隐私页视觉）**：合规文案要由人看一遍，尤其「至少保留 6 个月」「我们无法核验 agent 是否征求过同意」两句。自动化只能证明文本在源文件里。
- **T11（无凭据指引）**：需要一个人在没有凭据的终端里跑一次。CLI 的分支已写，但没有稳定的自动路径。`--help` 的失败行受 35 字上限，只写通用下一步；邮箱提示出现在运行时消息里。

## 已知问题

无。
