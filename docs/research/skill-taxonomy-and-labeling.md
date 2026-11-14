# Skill 的分类与标签：跨平台调研

> **状态**：调研记录，2026-11-13。数字是时点快照，引用时带日期与口径。
> **问题**：公域 skill 多起来之后，用户与 agent 凭什么在搜索结果里快速判断「是不是我要的、用了会得到什么」？分类怎么重做、标签从哪来、审核怎么做到低成本？
> **范围**：腾讯 SkillHub、ClawHub（OpenClaw 官方）、Glama、Smithery、Anthropic 生态、讯飞 SkillHub 开源设计；以及自动打标与抽样审核的行业做法。
> **口径**：平台事实以官方 API / 官方文档为准；SkillHub 的数字是 2026-11-13 调公共 API 的实测；二手来源逐条标注。本文不下决定——决定在 [`docs/decisions/`](../decisions/)。

## 一、先看事实：各平台怎么分类与打标

| 平台 | 一级分类 | 二级 | 每个 skill 几个 | 可筛的属性标签 | 谁打标 |
|---|---|---|---|---|---|
| **腾讯 SkillHub** | **12 个**（另有 `pay-skill`） | **96 个**（每类 8 个） | 一级 **1 个**；二级 **多个**（实测最多 3 个） | `requires_api_key`、`pricing_type` | 发布页 AI 辅助生成；运营可改 |
| **ClawHub（OpenClaw 官方）** | **14 个**（含 `other`） | 无 | **最多 3 个** | 无独立属性层 | 作者发布时选；plugin 可 LLM 自动分类 |
| **Glama（MCP）** | **86 个**策展类目 | 无 | 未明确 | 有 | 平台策展 |
| **Smithery** | 标签式，无严格树 | 无 | 多个 | 安装量、star、更新时间 | 作者 + 平台信号 |
| **Anthropic 生态** | 没有官方统一分类 | — | — | — | 各社区目录各搞一套 |

**四条可复用的结论：**

1. **两级是上限，不是起点。** 没人做三级树；SkillHub 12×8、ClawHub 平铺 14、Glama 平铺 86。
2. **一级单选、二级多选是主流。** ClawHub 允许 3 个一级分类，但 SkillHub 一级严格单选、二级多选。
3. **真正可筛选的属性标签很少。** SkillHub 跑了大半年也只有两个 key。
4. **自动分类已经在生产环境跑。** ClawHub 的 plugin 省略分类时用 LLM 自动归类（默认 GPT-5.6 Luna，失败回退 `other`）；SkillHub 发布页也有 AI 辅助生成分类/标签（后者来自二手报道，未独立核实）。

## 二、SkillHub 的实测（2026-11-13）

### 2.1 词表

公共接口 `https://api.skillhub.cn/api/v1/categories` 返回 **13 个一级分类**，其中 `pay-skill` 是付费技能，其余 12 个是内容分类：

| key | 中文名 | 二级数 |
|---|---|---|
| `office-efficiency` | 办公效率 | 8 |
| `content-creation` | 内容创作 | 8 |
| `dev-programming` | 开发编程 | 8 |
| `data-analysis` | 数据分析 | 8 |
| `design-media` | 设计多媒体 | 8 |
| `ai-agent` | AI Agent | 8 |
| `knowledge-management` | 知识管理 | 8 |
| `business-ops` | 商业运营 | 8 |
| `education` | 教育学习 | 8 |
| `professional` | 行业专业 | 8 |
| `it-ops-security` | IT 运维与安全 | 8 |
| `life-service` | 生活服务 | 8 |

二级共 **96 个**，每类 8 个，`subcategories?parent=<key>` 可取。一个 skill 可以带多个二级类目。

### 2.2 抽样（500 条，公共 `api/skills` 接口）

| 口径 | 数值 |
|---|---|
| 抽样方式 | 5 页 × 100 条，按接口默认顺序 |
| 一级分类为空 | **6 / 500（1.2%）** |
| `requires_api_key` 有值 | **494 / 500（98.8%）** |
| `pricing_type` 有值 | **66 / 500（13.2%）** |
| 出现过的二级类目 | **89 / 96** |
| 查询时公域总数 | 22,897（接口 `total`，时点快照） |

一级分类分布（同一次抽样）：`professional` 73、`business-ops` 61、`data-analysis` 52、`ai-agent` 52、`office-efficiency` 50、`content-creation` 49、`education` 34、`design-media` 32、`dev-programming` 30、`knowledge-management` 24、`it-ops-security` 20、`life-service` 17。

**读法**：一级分类的覆盖率已经很高；属性标签只用了两个 key。这说明「分类 + 少量属性标签 + 自由标签」是能跑起来的组合，不需要一上来做十个维度。

### 2.3 Grilling 的落点：词表缺一格

`grilling`（mattpocock/skills 里的「逐分支拷问计划/决策」技能）在 SkillHub 上的实际归类：

| 条目 | 一级 | 二级 |
|---|---|---|
| `mp-grilling` | **ai-agent** | 任务自动化 · 提示词优化 |
| `grill-me`（追问我） | **ai-agent** | 提示词优化 · 上下文管理 |
| `nox-grill-me` | **knowledge-management** | 资料整理 · 信息检索 |
| `emotion-decision-grill` | **life-service** | 个人计划 |
| `mattpocock-skills-zh`（合集） | **dev-programming** | 代码审查 · Bug 修复 · 测试生成 |

同一族技能散在四个一级分类里。抽 200 条 `ai-agent` 的二级分布是：工具调用 82、任务自动化 81、工作流 76、上下文 52、框架 45、记忆 42、多 Agent 28、提示词 28——**这是一张「造/跑 Agent」的货架，不是「用 Agent 和你一起工作」的货架**。`grilling` 不造 Agent，它改变的是 agent 追问你、和你对齐的方式；分类器只能把它塞进 `ai-agent`，再用两个并不描述它的二级类目硬凑。

**结论**：缺的不是正确的词，而是一整类「不产出领域交付物、只改变协作方式」的技能。

## 三、ClawHub 的 14 类（官方文档）

`docs.openclaw.ai/clawhub/publishing` 列出的 category slug 共 14 个：

| slug | 官方描述（摘） |
|---|---|
| `integrations` | Connect services, fetch data, reconcile records, and operate APIs. |
| `automation` | Build repeatable processes, scheduled jobs, pipelines, and orchestration. |
| `research` | Search, browse, scrape, summarize, monitor, and extract web information. |
| `development` | Inspect, edit, test, build, debug, and operate codebases. |
| `productivity` | Manage tasks, calendars, email, meetings, projects, and business work. |
| `communication` | Message, publish, and operate social or communication services. |
| `creative` | Create and edit images, video, audio, music, design, and writing. |
| `knowledge` | Work with documents, notes, knowledge bases, teaching, and learning. |
| `agents` | **Change how an agent plans, reflects, learns, remembers, or collaborates.** |
| `operations` | Inspect, monitor, deploy, and operate local systems or infrastructure. |
| `security` | Audit, scan, authenticate, and protect systems or data. |
| `finance` | Work with payments, budgets, banking, shopping, markets, and commerce. |
| `lifestyle` | Travel, health, fitness, cooking, sports, home, and daily-life utilities. |
| `other` | Skills that do not yet fit another browse category. |

指派方式：发布时 `--categories` 指定，逗号分隔，最多 3 个；首次发布不传存为 `other`；plugin 省略分类时由配置的模型自动生成，失败回退 `other`。

**关键对照**：ClawHub 的 `agents` 描述正是 `grilling` 的位置——「改变 agent 如何计划、反思、学习、记忆或协作」。这不是我们发明的类目，是行业里已经存在的货架。

## 四、其他平台

- **Glama**：官方介绍称 86 个策展类目（二手来源，未逐项核）；目录页按「做什么」分组。
- **Smithery**：标签式分类，没有严格层级；靠安装量、star、更新时间等信号。
- **Anthropic 生态**：没有单一官方市场或统一分类；社区目录常用「开发 / 文档办公 / 创意设计 / 数据分析 / 科研 / 写作 / 媒体 / 协作 / 学习 / 企业」等分组（二手来源，仅作对照）。
- **讯飞 SkillHub 开源设计**：`label_definition`（slug、type、visible_in_filter、sort_order）+ `label_translation`（locale、display_name）+ `skill_label`（skill_id、label_id）；类型分 `RECOMMENDED`（可筛）与 `PRIVILEGED`（官方推荐/认证）；单 skill 最多 10 个；标签翻译写入全文索引，筛选走 `skill_label` JOIN 而不是全文搜索。**这是「定义 + 挂载」两层模型的现成参考。**

## 五、自动打标与低成本审核

行业里没有单一标准，但共识做法集中在五件事：

1. **置信度分档路由。** 高置信直接生效并进抽样池；中置信进快速确认队列；低置信不生效（留空）或转人工。
2. **抽样验收代替全量审核。** 从自动通过的结果里随机抽一小部分，算准确率；达标继续自动，不达标收紧阈值或改提示词。
3. **异常聚合。** 运营处理的是「同一来源批量低置信」「同一类目大面积空缺」「标签互相矛盾」这类簇，不是逐条看。
4. **金标集回归。** 人工标 100–200 条作为回归集；改提示词、换模型、调词表前先跑它。
5. **审计与版本化。** 记录模型、提示词版本、词表版本、置信度、证据、人工改过的值；否则无法回答「为什么这条标错了」。

## 六、对 SkillMaster 的约束

> **这是调研时的综合，不是决定。** 定稿以 [ADR 0191](../decisions/0191-public-categories-are-thirteen-shelves.md)、[0192](../decisions/0192-public-labels-are-extensible-claims.md)、[0193](../decisions/0193-public-labels-are-agent-produced-and-sampling-audited.md)、[0194](../decisions/0194-labels-p0-narrows-two-adrs.md) 为准；与本文不一致时按 ADR。

- **分类**：采用 SkillHub 12 个一级分类 + 我们自己的「AI 协作」，共 13 个；单选、无二级。96 个二级词不进模型，转为候选标签词库。
- **标签**：必须是「定义 + 挂载」两层，新增维度只插词表行，不改表结构；首批 2 个维度（运行环境、定价），运行环境是多值并含 API key；产出形态不做（预期结果已覆盖）、输入要求推迟。
- **`outcome`**：一句话「你会得到什么」，是**主张**；与证据（评测/视频）、评价（评论/评分）、信号（调用量）四层分开，不合并。
- **打标**：Agent 在线跑，每个值带置信度与证据；三档路由 + 每周抽样 + 异常簇 + 金标集；不做逐条审核。
- **展示**：结果行显示作者描述；`outcome` 只放信息页（它回答「用了得到什么」，不一定讲得清「这个 skill 是什么」）；信息页与 CLI 读同一组字段。

## 七、未核实项

| # | 事项 | 现状 | 怎么核 |
|---|---|---|---|
| 1 | SkillHub 的分类与二级类目是人工标还是模型标 | 未核实；只能看到结果 | 问 SkillHub 运营或看其发布文档 |
| 2 | SkillHub 发布页「AI 辅助生成分类/标签」 | 二手报道，未独立核实 | 用测试账号走一次发布流程 |
| 3 | ClawHub 的 14 类是当前完整清单 | 来自官方文档，但社区资料另有「11 类」说法 | 以官方文档为准；变更时重核 |
| 4 | 我们公域已发布 skill 的实际条数与分类分布 | 未核实；无总数接口 | 用运营账号或库表统计 |
| 5 | 抽取模型的成本与准确率 | 未实测 | 先用 100–200 条金标集跑一轮 |

## 八、来源与置信度

| 事实 | 出处 | 档位 |
|---|---|---|
| SkillHub 12 + 96 类目、`requires_api_key` / `pricing_type` | `api.skillhub.cn` 公共接口，2026-11-13 实测 | **实测** |
| 500 条抽样中的覆盖率与分布 | 同上，5 页 × 100 条 | **实测** |
| Grilling 的归类 | 同上，`keyword=grilling` 与 `category=ai-agent` 实测 | **实测** |
| ClawHub 14 类、最多 3 个、plugin 自动分类 | [docs.openclaw.ai/clawhub/publishing](https://docs.openclaw.ai/clawhub/publishing) | **官方文档** |
| Glama 86 类 | Glama 介绍与第三方对比文章 | **二手** |
| Smithery 标签式 | 第三方对比文章 | **二手** |
| Anthropic 无统一分类 | 多篇目录盘点 | **二手** |
| 讯飞 SkillHub 标签模型 | [iflytek/skillhub 设计文档](https://github.com/iflytek/skillhub/blob/255a05a870cce9eb47f8ac2d924fa78a232f0016/docs/2026-03-20-skill-label-system-design.md) | **开源设计** |
| 置信度分档、抽样审计、金标集 | [Metadata Automation Governance](https://digital-nirvana.com/blog/metadata-automation-governance-human-review/) · [Auto-Tag, Auto-Summarize, Auto-Route](https://wpaiwriter.com/auto-tag-auto-summarize-auto-route/) · [企业标签治理](https://ones.com/blog/how-to-apply-ai-to-enterprise-knowledge-taxonomy-tagging-while-keeping-human-review-decisions-traceable-in-2026/) | **二手（实践总结）** |

> 引用本文的数字时，必须连口径一起写：**「2026-11-13 调 SkillHub 公共 API，抽 500 条」**，不能写成「实测」。
