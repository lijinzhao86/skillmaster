# v66-guide-content · 产品需求

> **最后更新**：2026-11-14
> **所属版本**：[`README.md`](README.md)

## 用户与场景

**谁在用**

1. **agent（主要读者）**：加载 `skillmaster/skillmaster-guide`，按用户原话在索引里找一篇，再照那一篇做。
2. **人（次要读者）**：第一次用 `skmr`、或卡在某个报错时，直接读同一份 Guide。
3. **维护 Guide 的人**：CLI 改了之后，要知道 Guide 里哪些页面要跟着改、改完怎么证明它还能用。

**在什么场景下用**

- 用户说「上传不上去」「改了别人看不到」「共享给他了但他没收到」——agent 需要在一屏索引里认出该读哪一篇。
- 正文让 agent 跑脚本、要 API key、接受共享邀请、看使用记录——这些能力在 Guide 里没有入口时，agent 只能猜命令或回头问人。
- CLI 已经改过（`@new` 废除、`put` 并入 `write --from`、`get` 收编 `download`），Guide 仍教旧命令——agent 照做，第一条命令就退出码 2。

**现在的替代做法是什么**

读 `skmr <命令> --help`。它回答「这条命令怎么拼」，不回答「我现在该做哪一步、做完看什么、错了先查哪里」；单命令参考没有任务顺序，也没有按用户原话组织的入口。Guide 是补这一层的唯一位置（[ADR 0174](../../decisions/0174-two-help-layers.md)）。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | 清掉退役教学：`@new`、`skill put`、`skill download`、`skill files` 在 Guide 里的全部出现位置，按 [ADR 0136](../../decisions/0136-cli-file-verbs-follow-intent.md) 换成 `draft create`、`write --from`、`get --all` | Guide 文本 |
| 2 | 修事实错误：共享是**邀请**、对方接受才生效；首次上线版本号写死 `1.0.0`；整份覆盖前必须先完整读一遍 | Guide 文本 |
| 3 | 补 10 篇能力页：跑脚本、服务凭据、通知与邀请、使用记录、看清一个 skill、复制一份、开新草稿、环境自检、让 Agent 用上 CLI、注销账号 | 新增 Guide 页面 |
| 4 | 重写索引：一行一个分支，补上 `draft create` / `skmr secret` / `skmr run` / `notifications` 四个入口；索引保持 ≤3000 字 | `SKILL.md` |
| 5 | 把 `help-guide-known-issues.json` 基线清零；漂移检查只允许「不新增」 | CLI 测试数据 |
| 6 | 新增 Guide 评测集：42 题「用户原话 → 期望篇目 + 必须出现的词」，配 harness 与有效性测试 | CLI 测试数据 + 测试 |
| 7 | 记录实测口径与结果；Guide 线上发布后删 `docs/current/TODO.md` 的「guide 的教学落后 v44 后的 CLI」（发布前删掉会让线上仍旧版这件事失去跟踪） | 文档 |
| 8 | 回归测试机：`scripts/guide-regression.sh` 一条命令跑结构、链接、文档、生成物与 42 题评测；`--fast` 跳过模型 | 脚本 |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 给 Guide 上 RAG | 41 篇、任务可枚举，索引就是一组确定的 context pointer；向量检索会引入 chunk 与 page 两份事实，且召回失败不可解释。本版不评估 RAG | TODO「QA 机器人（加载 guide skill）」落地时，在机器人那一层决定，不改 Guide 形态 |
| 2 | 新增或修改 CLI 命令 | 本版只让文档追上现状，不改能力边界 | 各自开版 |
| 3 | 把 `--help` 的完整 flag 表与失败表抄进 Guide | 违反 [ADR 0174](../../decisions/0174-two-help-layers.md)「同一事实只有一个家」；抄进来就会漂 | 不做 |
| 4 | 重做人向文档站与新人旅程 | 归 TODO「面向人的使用说明书」「新人旅程（人侧引导）」，位置与载体都未定 | 那两条开版时 |
| 5 | 改控制台、官网与 `trouble/feedback.md` | 控制台不在 Agent 的 CLI 路径上；`trouble/feedback.md` 正被 `feat/feedback-attachments` 修改，避免撞车 | 各自开版 |
| 6 | 把账号注销做成 CLI 命令 | [ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 的高风险例外；[ADR 0175](../../decisions/0175-account-deletion-is-immediate-with-a-fixed-retention.md) 已记录：注销只在控制台，由人输入用户名与密码确认 | 不做 |
| 7 | 把 Guide 评测接进 CI 默认路径 | 评测跑真实模型，42 题一次约 38 秒×并发，且模型会抖动；默认只在改帮助的 PR 上手动跑一次 | 评测稳定到可以当必过闸时；见 [技术设计](technical-design.md) §开放问题 |

## 控制台操作与 CLI 对应

本版**不新增或改动控制台操作**。除已记录的例外外，Guide 引用的页面动作都有对应的 CLI 命令或入口：

| 页面 | Guide 里的对应 |
|---|---|
| `/discover` | `skmr skills search --scope public` / `skmr skill join` |
| `/publish` | `skmr skills pending` / `skmr skills publish` |
| `/notifications` | `skmr notifications list` 及其子命令 |
| `/account/secrets` | `skmr secret` 系列 |
| `/usage` | `skmr usage` |
| `/my-tags` | `skmr skills my-tags` 系列（删除定义仍是控制台例外，Guide 明说） |
| `/account/sessions`、`/account/keys` | `skmr auth status`、`skmr logout` 与说明 |
| `/account`（注销） | **无 CLI 命令**；[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 的高风险例外，[ADR 0175](../../decisions/0175-account-deletion-is-immediate-with-a-fixed-retention.md) 已记录 |

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | Guide 里不再出现退役教学词 | `TestGuideRetiredWords` 通过；基线 `entries` 为空 |
| 2 | 索引能找到 `draft create` / `skmr secret` / `skmr run` / `notifications` 四个入口 | `TestGuideIndexMentionsCapabilities` 通过；基线为空 |
| 3 | 每个 `skmr …` 命令路径与 flag 都在命令树里声明 | `TestGuideCommandsAreDeclared` 通过 |
| 4 | 所有相对链接都能解析到文件 | 本地链接脚本输出 0 条断链 |
| 5 | 索引 ≤3000 字、≤150 行；入口总预算 ≤5000 字 | `TestHelpBudgets` 通过 |
| 6 | 42 题评测的路由与作答各 ≥90% | `TestGuideEval` 在 `SKILLMASTER_GUIDE_EVAL=1` 下通过 |
| 7 | 每个 Guide 页面都至少被一道评测题覆盖 | `TestGuideEvalTableIsValid` 通过 |
| 8 | 每篇正文 ≤100 行（索引除外） | 行数检查 |
| 9 | CLI 全量测试与文档检查全绿；一键回归退出 0 | `go test ./...`、`check_docs.py`、`impact.py --check`、`scripts/guide-regression.sh` 退出 0 |

**成立条件**：#1、#2、#6 任一条不满足就不算交付。它们是「Guide 不再把 agent 带错」与「Guide 真的能被按原话找到」的最小证明。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 路由准确率 | 评测中 agent 打开了期望篇目的题数 ÷ 42 | `TestGuideEval` 输出的 `route_rate` | ≥90% |
| 作答准确率 | 最终回答包含该题全部 `must` 词的题数 ÷ 42 | 同上 `answer_rate` | ≥90% |
| 首次读对率 | 跳过 `SKILL.md` 后，第一次读叶子页就落在期望篇目的题数 ÷ 42（诊断用，不作门槛） | 解析 stream 里的第一次叶子 Read | 记录，不设目标 |
| 评测口径 | 模型 `deepseek-v4-flash`；隔离目录只放 `guide/`；只允许 Read；每题最多读 4 个不同文件（重复读同一文件不另计）；每题 1 次；温度用模型默认 | [`test-plan.md`](test-plan.md) §环境与数据 | 换任何一项都必须重跑并重记 |

## 依赖与前置

- **外部依赖**：`claude` 可执行文件（评测用，找不到时整条跳过并标「未验证」）；模型可调用。
- **内部前提**：
  - [ADR 0174](../../decisions/0174-two-help-layers.md)：`--help` 是命令参考，Guide 是任务手册。
  - [ADR 0136](../../decisions/0136-cli-file-verbs-follow-intent.md)：文件动词按意图收口，`draft create` 独立成动作。
  - [ADR 0091](../../decisions/0091-release-type-decides-the-version-number.md)：版本号在发布时算出来；从未发布过的 skill 一律得到 `1.0.0`。
  - `docs/current/cli.md` 是命令语法的生成视图；Guide 的命令行以它和 `skmr <命令> --help` 为准。
- **排除了什么前置**：不需要动服务端、控制台、官网；不需要新增 ADR——本版的取舍（本版不评估 RAG、评测口径）已由本文件和技术设计写清，且不跨版本改写现有决策。
