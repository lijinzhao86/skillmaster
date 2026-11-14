# v72-fixed-labeling · 产品需求

> **最后更新**：2026-11-14
> **所属版本**：[`README.md`](README.md)
> **来源**：产品负责人 2026-11-14 的方向——先给 OPS 装一个**固定程序**的打标函数，导入时自动调用、后台也能整体刷新；Agent 抽取与评测留给后续版本。

## 用户与场景

**谁在用**：公域 skill 的读者（普通用户，以及替他们搜索与初筛的 agent）、平台运营者。

**在什么场景下用**：

- 用户在「发现」页或 `skmr skills search` 里看到一条 skill，要先问「我这台机器得装什么才能跑」；今天这一行是空的，只能点进信息页自己读正文。
- agent 在没有网页的环境里搜索，只能读到描述；它需要知道「跑起来缺什么」才能替用户初筛。
- 运营把开源 skill 接进官方命名空间，想知道「这一批里哪些要 Docker、哪些要 API key」，而不是逐条读正文。

**现在的替代做法是什么**：标签词表与读模型在 v66 P0 已经上线，但没有任何生产者，公域标签实际为空；读者只能读作者写的描述。`runtime` 是**能从仓库文件确定性判断**的一组事实（有没有 `requirements.txt`、命令里有没有 `docker run`），不需要模型，也不该等 Agent 那一期。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | **固定的规则函数**：输入是 `SKILL.md` 文本与文件清单，输出带证据的 `runtime` 标签；21 个非默认值与默认值 `prompt-only` 各有一条规则。函数是纯函数——同一输入永远同一输出，不读网、不读库 | `skillmaster-ops` 的 `labels` 包；规则表与单测 |
| 2 | **导入时自动打标**：每次自动导入成功后，对刚导入的这一版调用同一个函数；结果落 OPS 库（证据、规则版本），并推给 server 的最终表。skill 上线后标签立即可见。**已有上线版本的新草稿不写最终表**——那会改掉线上版本的标签；server 回 `applied=false`，OPS 记 `deferred`，等该版本上线后的刷新再写 | 导入钩子；OPS 库的新表；server 的 `/inner/v1` 写接口 |
| 3 | **后台刷新**：一个 OPS 内接口，按当前**已发布的版本内容**重算全部公域 skill 的规则标签，整组替换：命中不到的值删掉，新命中的值加上。用户自己公开的 skill 也在范围内 | `POST /inner/v1/jobs/label/runs`；运行记录与逐条结果 |
| 4 | **生产表只存最终结果**：`skill_label` 收成 `(skill_id, definition_id)` 两列；证据、规则版本、运行记录留在 OPS 库（[ADR 0197](../../decisions/0197-production-tables-hold-only-final-claims.md)） | `skillmaster-server` 的迁移 V55 |
| 5 | **server 的内接口**：列公域已上线 skill、读某一版的文件清单与文本、替换一条 skill 的规则标签；只有 `/inner/v1`，不对外 | `skillmaster-server` 的 `api/inner` |
| 6 | **读模型不变**：网页、CLI、筛选、信息页的字段与形状沿用 v66 P0；这一版只让标签从空变成有值 | 不改界面、不改 CLI 输出形状 |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | Agent / 模型抽取 | 这一期要的是可复算的固定逻辑；模型抽取有自己的外发边界、抽样验收与成本（[ADR 0196](../../decisions/0196-label-extraction-reads-public-text-only.md)） | v66 P1 |
| 2 | `outcome`（预期结果） | 一句话「用了会得到什么」只能从正文语义生成，规则写不出来 | v66 P1 |
| 3 | 分类（13 个一级货架） | 分类是语义判断；关键词匹配会把 skill 放进错的货架，而货架直接出现在公域结果行 | v66 P1 |
| 4 | `pricing_type`（免费 / 付费） | 价格几乎不写在仓库里；规则只能大量留空或误标 | 有可靠来源时另定 |
| 5 | 抽样验收与金标集 | 规则可复算：同一输入重跑结果必须逐字一致，用单测与固定夹具覆盖；抽样的对象是模型的不可复算输出 | Agent 期 |
| 6 | 私有 skill 与草稿的标签 | 只处理公域已发布内容（[ADR 0196](../../decisions/0196-label-extraction-reads-public-text-only.md)）；导入产生的草稿会在提交时就带上标签，但**只在 skill 公开上线后才被读到** | 不做 |
| 7 | 每次发布实时回调 OPS | 发布是浏览器面的高风向动作，不该在事务里等一个内部服务；刷新是拉模式，由运营触发 | 有明确的实时性理由时另定 |
| 8 | 运营覆盖文件（force / remove） | 这一期没有运营干预路径：规则错了改规则、重跑刷新；覆盖文件需要定义格式、优先级与审核留痕 | v66 P1 |
| 9 | 运营专用 CLI | 没有新增控制台操作；刷新是运维动作，走 `/inner/v1`，不是产品命令（[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)） | 做运营工作台时另定 |
| 10 | 规则可配置 / 可热更新 | 规则是代码：进 git、过测试、随服务发版。放进配置文件会让「哪条规则产出了这个标签」无法从版本追溯 | 规则数量多到一份代码装不下时 |

## 控制台操作与 CLI 对应

这一版**不新增控制台操作**：标签出现在 v66 P0 已有的结果行、信息页与筛选里，字段形状不变。

| # | 控制台操作 | CLI 命令 | 备注 |
|---|---|---|---|
| 1 | 在结果行 / 信息页看到标签（既有展示） | `skmr skills search`、`skmr skill show`、`skmr skills labels` | 命令与输出形状不变；这一版只填数据 |
| 2 | 按 `--label runtime:python` 筛选（既有能力） | `skmr skills search --label <key:value>` | 不变 |

刷新不是控制台操作，是 OPS 的后台任务，按 [ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 不要求 CLI。

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | 规则覆盖 `runtime` 的全部 21 个非默认值，每个值至少一条正例；没有任何非默认值命中时写 `prompt-only` | 规则单测逐值断言；词表新增值而没加规则时，一条「词表与规则对齐」的测试失败 |
| A2 | 每条写出的标签都带证据 `{file, line, quote}`；证据必须能在被评估版本的文件清单里找到（行号 > 0 时 quote 等于该行，行号 = 0 时 quote 是命中的 relpath）；找不到证据的值不写 | 单测：正例、改一个字符、指向不存在的文件三种；写库前有校验 |
| A3 | `gpu` / `docker` / `database` 按 v66 PRD 的三口径取证：只在命令 / 前置条件里命中，简介里出现关键词不算；云 GPU、只写 Dockerfile、教 SQL / 查公开数据库 API 都不误标 | 规则单测包含 v66 PRD 点名的五组正反例（`orchestra-training-llms-megatron`、`jeffallan-devops-engineer`、`jeffallan-sql-pro`、`database-lookup`、`orchestra-modal-serverless-gpu`）的等价夹具，逐条断言集合 |
| A4 | 首次导入一个还没有上线版本的新 skill：OPS 库有这一版的标签记录，server 最终表有同一组 `(skill_id, definition_id)`；该版本上线后读接口返回这组标签。**已有上线版本的新草稿**：server 回 `applied=false`，最终表保持线上版本的值，等该版本上线后的刷新再写 | 导入端到端测试：请求 → 两张表 → 读接口；再测一个「已有上线版本 + 新草稿」的夹具，断言最终表没被改 |
| A5 | 后台刷新一次，全部公域已上线 skill 的规则标签与该 skill **当前版本**的内容一致；旧内容命中的值被删除，新命中的值被加上 | 刷新端到端测试：改内容 → 刷新 → 最终表整组替换 |
| A6 | 同一内容重跑，标签集合与证据逐字一致，不产生重复行；重复导入同一 revision 是 no-op | 幂等测试：同输入跑两遍，比较两表 |
| A7 | 生产库的 `skill_label` 只有 `skill_id` / `definition_id` 两列；`skill` 不再有 `outcome_*` 来源列；`label_extraction_run` / `label_audit_sample` 不在在线库 | 迁移后的表结构断言；`schema.md` 生成视图 |
| A8 | V55 可 dry-run、可回滚；生产库当前没有标签行，迁移前后读接口对空标签的行为不变 | 事务内 dry-run 脚本 + 迁移测试；部署前在生产库跑一次 dry-run |
| A9 | 打标或推送失败不改写导入结果：草稿照样创建，运行记录里这一条是 `labeled=false` 或 `push_failed`，下一次导入 / 刷新会补 | 故障注入测试：server 写接口 500、规则抛异常两种 |
| A10 | 对外契约不变：搜索结果、信息页、`skmr skill show` / `skills labels` 的字段与形状与 v66 P0 相同；旧客户端不受影响 | 快照测试 / 契约测试；与 v66 P0 的输出逐字段比较 |
| A11 | 刷新只处理公域已上线 skill；私有、草稿、已删除的 skill 不出现标签行，也不被扫描 | 刷新测试的夹具含三种反例 |
| A12 | 金标集（100–200 条真实 skill）上，有 ≥10 个正样本的值 precision ≥ 95%；语言值 recall ≥ 90%；工具与凭据 recall ≥ 80%；OS precision ≥ 98% | T13 结果表逐值列出；未达标的值先改规则再交付 |

**成立条件**：**A2、A3、A4、A5、A7、A12 任一不满足就不算交付**——它们分别守住「有证据」「歧义值不误标」「导入链路通」「刷新能纠错」「生产表干净」「实测准确率达标」。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 规则覆盖率 | 公域已上线 skill 中，至少命中一个非默认值的比例（`prompt-only` 单独统计） | 刷新运行记录按结果汇总 | 首轮刷新后实测，不拍数字 |
| 证据完整率 | 写出的标签里，证据能在版本清单里找到的比例 | OPS 库校验结果 | 100% |
| 刷新耗时 | 一次全量刷新的墙钟时间，口径：单实例、默认并发 | 运行记录 `duration_ms` | 首轮实测，不拍数字 |
| 刷新失败率 | 逐条结果里 `failed` 的比例 | 运行记录 | 首轮实测 |

## 依赖与前置

- **决策**：[ADR 0193](../../decisions/0193-public-labels-are-extensible-claims.md)（标签是主张、可扩展词表）、[ADR 0196](../../decisions/0196-label-extraction-reads-public-text-only.md)（只读公域已发布文本；本版**不调用模型**，因此没有外发面）、[ADR 0197](../../decisions/0197-production-tables-hold-only-final-claims.md)（生产表只存最终结果）、[ADR 0198](../../decisions/0198-api-planes-are-named-by-audience.md)（`/inner/v1` 是服务间面）、[ADR 0208](../../decisions/0208-fixed-rules-are-a-label-producer.md)（规则是独立产出者）。
- **内部前提**：v66 P0 的词表、读模型、筛选已上线；v69 的 OPS 服务与 `job_run` / `job_lock` 已在第二台 ECS 上运行；导入主链路（v50）不变。
- **数据前提**：公域实际条数未核实（没有总数接口）。刷新按分页遍历，先在第一轮运行里数一遍，再评估耗时。
- **排除了什么前置**：不需要模型与 API key；不需要抽样验收流程；不需要改 `design/`（界面与 v66 P0 一致）。
