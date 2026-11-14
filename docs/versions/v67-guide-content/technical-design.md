# v67-guide-content · 技术设计

> **最后更新**：2026-11-14
> **状态**：已实现
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

## 架构

### 两层帮助里，本版只改手册层

命令语法、flag、失败表、退出码的权威是 `skmr <命令> --help`（声明渲染，生成视图见 [`docs/current/cli.md`](../../current/cli.md)）。本版不改这一层，也不把它的内容抄进 Guide（[ADR 0174](../../decisions/0174-two-help-layers.md)）。Guide 只负责：判断 → 步骤 → 完成信号 → 出错先查哪里。

### Guide 的结构：索引即路由，叶子即终点

```
skills/skillmaster-guide/
├── SKILL.md            索引：一组 context pointer，一行一个分支
├── start/              第一次用（装、首个 skill、让 Agent 用上）
├── concepts/           概念：直接给结论
├── use/                消费：找、读、看、跑、配凭据、复制、看记录
├── author/             做与改：建、开草稿、改、上传、传二进制、描述、文件、版本信息
├── ship/               发出去：上线、可见性、共享、通知、下线删除
├── account/            账号与凭据：注册、登录、换账号、设备与 key
├── trouble/            症状入口：登录、上传、加载、上线、环境、反馈
├── limits/             上限
└── tips/               写法
```

三条硬约束：

1. **索引一层深。** 从 `SKILL.md` 直接到叶子；叶子之间可以互相链接，但控制流不外包（[`.skill-authoring/rules.md`](../../../.skill-authoring/rules.md) B1–B3）。
2. **一行一个分支。** 索引行的第一列写用户会说的话（「上传不上去」），第二列写具体文件。同义词归并成一个分支，不按同义词加行。
3. **叶子是任务，不是科目。** 每篇开头两句内给结论；步骤带完成信号；出错表只列「agent 不知道自己踩了」的坑。完整 flag 与失败表指向 `--help`。

### 与已合并版本的交集

本版合入 v65（反馈附件）与 v66-skill-labels 后，接两处：

- **v65 反馈附件**：`trouble/feedback.md` 的附件与撤回内容由 v65 写就。本版接管这一页：开头改成两句内给出动作，失败表收成指向 `skmr feedback send --help` 的指针（[ADR 0174](../../decisions/0174-two-help-layers.md)），并加一道「带图片或视频」的评测题。
- **v66-skill-labels（P0 已交付、P1 抽取未交付）**：`skills labels` 是新命令，`skills search` 新增 `--label` 与结果行的「属性」，`skill show` 新增「预期结果」与「属性」并把「运行环境」改名「兼容性」，分类词表扩到 13 个且不再打印公域计数。Guide 的 `use/search.md`、`use/inspect.md`、`author/describe.md` 已按这组能力改写，并加一道「按运行环境筛选」的评测题。

### 检索方式：不用 RAG

本版选「索引 + 一跳」而不是向量检索。索引的 41 个分支可枚举、措辞可控；向量检索会引入 chunk 与 page 两份事实，召回失败也没有可解释的修法。判断依据与取舍见 [`prd.md`](prd.md) §范围「不做 #1」。

检索质量不靠争论，靠评测：44 题「用户原话 → 期望篇目 + 必须出现的词」，见下。

### 评测：与 `--help` 评测并行的第二把尺

```
guide-eval.json（44 题）
        │
        ▼
TestGuideEvalTableIsValid   ← 默认运行：题表有效、页面全覆盖、命令存在、题面不泄答案
TestGuideEval               ← SKILLMASTER_GUIDE_EVAL=1 才跑：真实模型 44 题
        │
        ├── 把 skills/skillmaster-guide/ 复制到临时目录 guide/
        ├── claude --bare --restricted --tools Read：最多读 4 个不同文件
        ├── 解析 stream：读了哪些文件、先读的是不是索引、最后回答是什么
        └── 记 route / answer / pass / first-read 四个率，三个正指标都设门槛
```

**路由的前提是先读索引。** 每题都要求先读 `guide/SKILL.md`；没读索引就直接打开篇目算「猜中」，不计入路由。否则一个指错篇目的索引仍能拿 100%。

**门槛有三个，不是两个。** route、answer、pass 各 ≥90%；pass 要求路由、作答、无违规同时成立。只卡 route / answer 时，全部题目超读上限仍然退出 0。

它与 `help_eval_test.go` 共用隔离方式与解析习惯，但**题表与指标互不覆盖**：`help-eval.json` 量「能不能找到命令」，`guide-eval.json` 量「能不能按原话找到篇目并答对」。两把尺都改 = 两个数字都失去可比性。

## 数据模型

本版不新增数据库表。两个测试数据文件是唯一的新结构：

**`skillmaster-cli/testdata/guide-eval.json`**

```
{
  "tasks": [
    { "id": "use-run", "group": "use", "prompt": "用户原话",
      "page": "use/run.md", "must": ["skmr run"] }
  ]
}
```

| 字段 | 约束 |
|---|---|
| `id` | 唯一、kebab-case |
| `prompt` | 不含 `page` 的文件名，也不含 `must` 里的任何词 |
| `page` | `skills/skillmaster-guide/` 下真实存在的 `.md`（不含 `SKILL.md`） |
| `must` | 全部命中；以 `skmr ` 开头的必须是命令树里的叶子；出现 flag 时必须已声明 |
| `any` | 可选；非空时至少命中一个，用于同义改写（例如「线上版本 / 线上版 / 冻结」） |

完整性约束由 `TestGuideEvalTableIsValid` 强制：恰好 44 题、每个页面至少被一道题覆盖、id 不重复。

**`skillmaster-cli/testdata/help-guide-known-issues.json`**

保持「只减不增」的棘轮形状，本版把 `entries` 清空。新增违规会让 `TestGuideKnownIssues` 变红。

## 接口

本版不改 HTTP 接口。对外可观察的「接口」有三处：

| 面 | 形状 | 谁用 |
|---|---|---|
| 索引 | `SKILL.md` 的路由表；必须包含 `draft create` / `skmr secret` / `skmr run` / `notifications` 四个词 | 加载 Guide 的 agent；`TestGuideIndexMentionsCapabilities` |
| 评测入口 | `SKILLMASTER_GUIDE_EVAL=1` 运行；`SKILLMASTER_GUIDE_EVAL_LIMIT=<n>` 冒烟；`SKILLMASTER_GUIDE_EVAL_OUT=<文件>` 落结果 | 改 Guide 的人 |
| 发布 | `skmr skill submit skills/skillmaster-guide --to skillmaster/skillmaster-guide` → `skmr skill check` → `skmr version open … --sub actions` → 网页确认 | Guide 维护者 |

**发布是单向的**：仓库是唯一源，线上不会自动同步；忘记发布时，线上仍是旧版（[ADR 0174](../../decisions/0174-two-help-layers.md) §后果）。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 内容 | 清退役教学、修事实错误、补 10 篇、重写索引 | `TestGuideRetiredWords` / `TestGuideCommandsAreDeclared` / `TestGuideIndexMentionsCapabilities` 全绿，基线为空 |
| P1 评测 | `guide-eval.json` + harness + 有效性测试 | `TestGuideEvalTableIsValid` 与 `TestGuideEvalScoring` 全绿 |
| P2 实测 | 一次完整评测、记录口径与结果、回归测试机与文档收口 | `TestGuideEval` 达到 90% / 90%；`scripts/guide-regression.sh` 退出 0；`test-plan.md` §结果 填写 |

每期明确不做：P0 不跑模型评测；P1 不把评测接进 CI；P2 不改 Guide 正文（发现的问题进 `test-plan.md` §已知问题或下一版）。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 评测跑 1 次还是 3 次？ | 1 次：44 题约 40 秒，成本低；跨版本比数字时跑 3 次取中位数 | 在同一版上连跑 3 次，比较 route/answer 的波动 |
| Q2 | 要不要把 Guide 评测接进 CI 默认路径？ | 不接：模型会抖动，且 44 题的成本不适合每次提交 | 累积 10 次运行；若方差 <5 个百分点，提议 nightly 而非必过闸 |
| Q3 | 每题最多读 4 个不同文件是否太紧？ | 先保留：索引 + 1–2 篇是正常路径；有一次合法任务读了 5 个不同文件被判违规 | 看 3 次运行里的违规率；超过 10% 再评估 |
| Q4 | 索引只剩约 2 字余量，下一篇怎么加？ | 新增一篇必须同时压缩现有行；若再增 3 篇以上，考虑按 B1 例外开二级索引 | 下一次新增页面时按预算测试判断 |

## 附录 A · 与现有代码的关系

- 新增 `skillmaster-cli/cmd/skillmaster/guide_eval_test.go`，复用 `help_layer_test.go` 的 `helpCommandTable` / `invocationIssues` / `longestCommand` / `helpRepoRoot`，不复制第二套命令解析。
- 修改 `skillmaster-cli/cmd/skillmaster/help_layer_test.go`：`TestHelpBudgets` 增加索引 ≤150 行的检查。
- 新增 `scripts/guide_links.py` 与 `scripts/guide-regression.sh`：前者做可达性、链接、行数与预算，后者是改 Guide 后的一键回归入口。
- 新增 `skillmaster-cli/testdata/guide-eval.json`。
- 修改 `skillmaster-cli/testdata/help-guide-known-issues.json`（清空）。
- 不改 `skillmaster-cli/cmd/skillmaster/declarations*.go`、不改服务端、不改控制台。

## 附录 B · 来源与出处

- [ADR 0174](../../decisions/0174-two-help-layers.md)：两层帮助与「同一事实只有一个家」。
- [ADR 0136](../../decisions/0136-cli-file-verbs-follow-intent.md)：`draft create` 独立、`write --from` 收编二进制、`get` 收编整版。
- [ADR 0091](../../decisions/0091-release-type-decides-the-version-number.md)：版本号在发布时算出来；从未发布过的 skill 一律得到 `1.0.0`。
- `docs/current/cli.md`（生成视图）与各命令 `--help`：Guide 中每条命令行的权威。
- `docs/current/TODO.md` 的「guide 的教学落后 v44 后的 CLI」：本版的触发项。

## 附录 C · 已测量的数据

| 指标 | **口径**（定义 / 样本 / 是否计入零值） | 数值 |
|---|---|---|
| 改前 Guide 规模 | 仓库 HEAD 的 `skills/skillmaster-guide/`，含 `SKILL.md` | 32 个文件、1356 行 |
| 改前索引 | `SKILL.md` 的 UTF-8 字符数 | 2553 |
| 改前基线 | `help-guide-known-issues.json` 的条目数 | 29 |
| 改后 Guide 规模 | 工作区最终内容 | 42 个文件、1982 行 |
| 改后索引 | `SKILL.md` 的 UTF-8 字符数（上限 3000） | 2998（102 行） |
| 改后基线 | 同上 | 0 |
| 评测路由率 | `deepseek-v4-flash`；1 次运行；每题最多读 4 个不同文件；隔离目录 | 44/44 = 100% |
| 评测作答率 | 同上；回答包含全部 `must` 词、`any` 至少一个 | 44/44 = 100% |
| 评测通过率 | 路由、作答、无违规同时成立 | 44/44 = 100% |
| 首次读对率 | 跳过索引与列目录后，第一次读叶子页即命中期望篇目 | 44/44 = 100%（另一次 43/44） |
| 评测耗时 | 44 题、6 路并发、单机 | 约 35–42 秒 |
| 运行间波动 | 同版多次运行 | 路由 100%；作答 95.1%–100% |

**作答判定的口径**：`must` 全部命中，`any`（可选）至少命中一个；都是子串匹配，否定句不区分。选词优先用页面里出现、模型会照抄的名词或命令——`TestGuideEvalTableIsValid` 因此要求每个 `must` 都出现在目标页，且至少一个不在索引里，否则一页坏掉也能被索引兜住。

> 评测跑一次只够看「有没有系统性缺口」；跨版本比较按 Q1 的 3 次中位数。
