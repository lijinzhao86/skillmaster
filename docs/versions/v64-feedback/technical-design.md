# v64-feedback · 技术设计

> **最后更新**：2026-11-13
> **所属版本**：[`README.md`](README.md)

本文只写与 [`docs/current/`](../../current/) 的差异；模块边界、表结构的现状不在这里复述。范围与验收见 [`prd.md`](prd.md)。

## 架构

**三段，一条链路。**

```
CLI（cmd/skillmaster/feedback.go）
  ├─ 本地：模板、完整内容预览、敏感信息扫描
  ├─ GET  /api/v1/feedback/consent        ← 取当前同意说明与版本
  ├─ POST /api/v1/feedback                → 落库 + 通知 + 投递
  └─ DELETE /api/v1/feedback/{回执}       → 撤回并删除（含归档对象）

服务端
  ├─ api/FeedbackController.java          ← 薄控制器，鉴权用现有 AuthenticatedSubject
  ├─ usecase/SubmitFeedbackUseCase.java   ← 校验、限流、脱敏、写库、发通知
  ├─ usecase/DeleteFeedbackUseCase.java   ← 删行、删归档对象、写审计
  ├─ modules/feedback/                    ← 新模块 M18：表的所有者、仓库与归档
  ├─ modules/notification/                ← 复用 M14：给提交者发 ANNOUNCEMENT 形状的通知
  └─ modules/blob/                        ← 复用 M6：归档对象写 OSS
```

**为什么新开 M18 而不是塞进 M17（activity）。** `user_feedback` 是用户提交的内容，有自己的生命周期、归档与删除路径；活动日志是行为记录，两者的保留与访问口径不同。模块表在 [`ModuleMap.java`](../../../skillmaster-server/src/main/java/com/skillmasterai/common/ModuleMap.java)，新增一条 M18 后 `TableOwnershipTest` 会把表的归属钉住。

**为什么复用 `ANNOUNCEMENT` 而不新增通知 kind。** 控制台的 [`NoticeRow.tsx`](../../../skillmaster-console/src/components/notifications/NoticeRow.tsx) 对未知 kind 只渲染 `title`，不渲染 `body`；新增 kind 要改控制台（本版无 GUI）。`ANNOUNCEMENT` 的分支渲染 `title + body`，且 [`SkillLifecycleNotices.java`](../../../skillmaster-server/src/main/java/com/skillmasterai/usecase/SkillLifecycleNotices.java) 已经用它对个人发过通知，属于既有模式。回执正文把回执号与撤回命令写进 `body`。

**运营读库，不做推送。** 仓库里没有邮件设施；本版不引入。运营按回执号或时间范围直接查 `user_feedback`——`receipt` 唯一索引服务按回执查人，`created_at` 索引服务全平台的时间范围；`(user_id, created_at)` 服务按账号查。要主动提醒运营时再开推送（见 [`prd.md`](prd.md) 不做 10）。

## 数据模型

新迁移 `V50__user_feedback.sql`。表结构与约束按 [`V46__my_skill_when_to_use.sql`](../../../skillmaster-server/src/main/resources/db/migration/V46__my_skill_when_to_use.sql) 的写法：`TEXT` 主键与时间、`CHECK` 只做兜底、注释写口径。

```sql
CREATE TABLE user_feedback (
  id             TEXT PRIMARY KEY,
  receipt        TEXT NOT NULL UNIQUE,          -- FB-YYYYMMDD-XXXXXX
  user_id        TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  skill_address  TEXT,                          -- 可选上下文，如 ns/name@1.2.3
  content        TEXT NOT NULL,                 -- 提交的文档，密钥前缀已脱敏
  content_sha256 TEXT NOT NULL,                 -- 服务端计算，不信任客户端
  consent_version TEXT NOT NULL,                -- 当前隐私政策版本
  consent_at     TEXT NOT NULL,                 -- RFC3339 UTC
  source         TEXT NOT NULL,                 -- 提交渠道；v64 只有 cli
  diagnostics    JSONB,                         -- 白名单字段，见 §接口
  archive_key    TEXT,                          -- OSS 对象键；未归档为 null
  archived_at    TEXT,
  created_at     TEXT NOT NULL
);

CREATE INDEX idx_user_feedback_user_time ON user_feedback(user_id, created_at);
CREATE INDEX idx_user_feedback_created ON user_feedback(created_at);
CREATE INDEX idx_user_feedback_pending_archive ON user_feedback(archived_at) WHERE archived_at IS NULL;
```

- **限流**：提交前 `SELECT count(*) FROM user_feedback WHERE user_id = :id AND created_at > :oneHourAgo`，到 `SubmitFeedbackUseCase.MAX_PER_HOUR` 条拒绝（值见 [`limits.md`](../../current/limits.md)）。并发下可能多放一条，本版接受；错误码里写清窗口。撤回会删掉自己被计数的行，所以「发送后立刻撤回」能重置这份计数——限流防的是失控的 agent，不防蓄意脚本，后者由审计事件留痕。
- **归档**：`FeedbackArchiveJob` 每天把 `archived_at IS NULL` 的行逐条写成 gzip NDJSON，上传到 `feedback/YYYY/MM/DD/<receipt>.ndjson.gz`，再以 `WHERE id = :id AND archived_at IS NULL` 写回 `archive_key` / `archived_at`；一次运行按 500 条分批，最多 40 批，剩余留给下一次。**逐条一个对象**，撤回时按对象键精确删除；不按天打包，避免撤回要重写整天的文件。写回影响 0 行时：行还在＝另一实例已记同一确定性键，保留；行已不在＝撤回先提交，删掉刚上传的对象。暂存目录做符号链接拒绝与 0700，超过 1 小时的遗留暂存文件会被清理。
- **删除**：`DeleteFeedbackUseCase` 在事务里用 `SELECT … FOR UPDATE` 锁住这一行（否则归档任务可能在上传后、写回前被删除穿过）→ 有 `archive_key` 先删对象 → 删行 → 写 `audit_event`。归档对象删除复用 `OssBlobStore.delete(key)`；`BlobStore` 增加 `deleteObject(String)` 默认方法，`RoutingBlobStore` 转发。对象删失败则整笔回滚，行留着，下次还能按键重试。
- **账号注销**：v56 的清理把 `app_user` 行去标识化而不是删除，所以 `ON DELETE CASCADE` 不会触发；`user_id` 继续指向 `deleted-<id>` 的账号，反馈内容按提交内容保留（密钥前缀已脱敏，ADR 0189 决定 4）。注销后无法再运行删除命令，隐私政策写明可发邮件申请删除。

## 接口

### 服务端

| 方法 | 路径 | 入参 | 出参 / 状态 |
|---|---|---|---|
| GET | `/api/v1/feedback/consent` | — | `200 {version, notice}`；`version` 取 `LegalTextVersions.privacy()` |
| POST | `/api/v1/feedback` | `{content, skill_address?, diagnostics?, consent_version, allow_sensitive?}` | `201 {receipt, created_at}` |
| DELETE | `/api/v1/feedback/{receipt}` | — | `204`；不属于当前账号时 `404` |

**错误码**（新增到 [`ErrorCode.java`](../../../skillmaster-server/src/main/java/com/skillmasterai/common/ErrorCode.java)）：

| 码 | 状态 | 含义 |
|---|---|---|
| `feedback_empty` | 400 | 内容为空 |
| `feedback_too_long` | 400 | 超过 `FeedbackSafety.MAX_CODE_POINTS` 个码点，带实际值与上限 |
| `feedback_consent_stale` | 400 | `consent_version` 不是当前版本，客户端要重新取同意说明 |
| `feedback_sensitive` | 400 | 命中敏感信息且未带 `allow_sensitive`；只回类别与次数，不回原值 |
| `feedback_throttled` | 429 | 1 小时内超过 `SubmitFeedbackUseCase.MAX_PER_HOUR` 条，带重试时间 |
| `feedback_not_found` | 404 | 回执不存在或不属于当前账号 |

**诊断白名单**：`cli_version`、`os`、`arch`、`at`、`command`、`error_code`。未知键直接丢弃，不报错；`command` 只存命令名（第一个词），不存参数。

**校验顺序**：登录 → 非空 → 空字符（U+0000）→ 码点上限 → 同意版本 → 敏感扫描（原文）→ 限流 → 脱敏 → 写库。任何一步失败都不落库。

**落库后的两件事**（与反馈行同一事务）：
1. `NotificationService.emit(new NotificationDraft(ANNOUNCEMENT, null, null, null, null, null, title, body, null, List.of(userId)), at)`，`body` 含回执号与 `skmr feedback delete <回执> --yes`。
2. 写 `audit_event`（`feedback_submit`）。

**为什么不再放到事务外**：本版没有邮件，通知与审计都是同一数据库里的插入；最慢的一步就是事务本身。要么三件一起成功，要么 CLI 收到错误并在重试后拿到同一条新回执——不会出现「反馈已收下但用户没收到回执通知」。

### CLI

新命令组 `feedback`，与 `secret` / `notifications` 同形（[`main.go`](../../../skillmaster-cli/cmd/skillmaster/main.go) 的 switch、[`declarations.go`](../../../skillmaster-cli/cmd/skillmaster/declarations.go) 的 `topSpecs` 都要加）：

| 命令 | 作用 |
|---|---|
| `skmr feedback template` | 本地打印反馈文档模板；不需要登录、不发网络请求 |
| `skmr feedback send [--file <路径>] [--skill <地址>] [--attach-diagnostics] [--command <命令>] [--error-code <码>] [--consent] [--allow-sensitive] [--json]` | 无 `--consent` 只预览；有才提交 |
| `skmr feedback delete <回执> [--yes] [--json]` | 撤回并删除；不带 `--yes` 只打印将要删除的回执 |

- 标准输入：`--file` 缺省且标准输入不是终端时读标准输入；两者都没有时报用法错误。
- 预览内容：将发送的完整文档（密钥已替换）、同意说明（来自 `GET /consent`）、将发送的字段清单。预览会取一次同意说明，但没有任何 POST。
- 敏感扫描在客户端与服务端各做一次：客户端负责提示，服务端是闸。**密钥前缀先替换再扫描**：CLI 把命中的密钥替换为 `[已隐藏]` 并告诉用户替换了几处，剩下的三类（`id_number`、`bank_card`、`phone`）命中且未带 `--allow-sensitive` 时打印类别与次数并退出 1。服务端对原文再扫一次，`secret_prefix` 在服务端同样被替换。
- `--attach-diagnostics` 只带白名单字段；`--command` / `--error-code` 是可选补充。默认不带任何诊断。
- 网络层新增 `internal/api/feedback.go`，沿用 `APIPrefix` 与 `Client` 的既有形状（参照 [`secrets.go`](../../../skillmaster-cli/internal/api/secrets.go)）。

### 隐私条款

两处 `legalText.ts`（`design/src/home/` 与 `skillmaster-site/src/legal/`）各加一节，内容一致：

- 收集什么：你提交的反馈文档、账号标识、提交时间、可选的关联 skill 地址与诊断字段。
- 用途：受理、联系、修复；不用于训练，不向第三方提供。
- 保留：至少 6 个月、不设自动删除期限；另存副本到对象存储，数据库行不删；你可以随时撤回并删除。账号注销后反馈按长期保留处理，不适用 30 天清理，可发邮件申请删除。
- 边界：我们无法核验你的 agent 是否在提交前征求过你的同意；你指示 agent 提交，视为你提交（《服务条款》同条写清）。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 服务端：`V50`、M18、同意接口、提交/删除、限流、敏感闸、通知、归档 | `prd.md` 验收 #2（服务端半）、#5（服务端半）、#6、#7、#10、#12、#13（服务端半）、#14（服务端半） |
| P1 | CLI：`feedback` 组、模板、预览、提交、删除、敏感扫描、诊断白名单 | #1、#2（CLI 半）、#3、#4、#5（CLI 半）、#10、#11（CLI 行为）、#13（CLI 半）、#14（CLI 半） |
| P2 | 隐私条款两处、`skillmaster` 与 `skillmaster-guide` 的引导 | #8、#9、#11（邮箱兜底文案） |

## 开放问题

第 3 关的四个问题都已定，下面是对既有结论的指针；没有新的未决项。

| # | 问题 | 结论 |
|---|---|---|
| 1 | 同意说明的版本跟谁走？ | 用 `LegalTextVersions.privacy()`，不另立版本；隐私政策改版时 `feedback_consent_stale` 把客户端推到新说明。本版把隐私政策升到 1.2（新增反馈一节） |
| 2 | 归档对象与数据库谁是主？ | 数据库是主，OSS 是耐久副本；归档后不删行。撤回两边都删（ADR 0189） |
| 3 | 手机号 / 身份证号 / 银行卡号要不要脱敏？ | 密钥前缀硬脱敏；其余三类只提示、保留原值（它们是报告的一部分），运营查看需单独的访问限制 |
| 4 | 账号注销时反馈怎么处理？ | 账号行去标识化、反馈行保留、`user_id` 不变（ADR 0189 决定 4）；隐私政策写清与 30 天清理的边界 |

**已知残余**：预览与提交是两次独立运行，提交前 CLI 总会重新取当前同意说明并记录它的版本。若两次之间政策改版，用户看到的是上一版说明、记录的是提交时的版本——本版接受（撤销唯一发生在政策部署的瞬间）；要严格禁止，需要把预览时的版本带进提交（一次性令牌）。
