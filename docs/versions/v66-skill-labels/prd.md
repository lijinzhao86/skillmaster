# v66-skill-labels · 产品需求

> **最后更新**：2026-11-14
> **所属版本**：[`README.md`](README.md)
> **来源**：产品负责人 2026-11-13 的方向——这一期只治公域，不做安全；核心是给 skill 增加标签维度，让用户搜索时更快拿到信息与预期结果。分类借鉴 SkillHub 的一级类目并扩展；属性标签在技术设计上必须可扩展；审核用 Agent 自动打、抽样验收，不逐条看。

## 用户与场景

**谁在用**：公域 skill 的读者（普通用户，以及替他们搜索与初筛的 agent）、平台运营者。

**在什么场景下用**：

- 用户在「发现」页或 `skmr skills search` 里看到十几条候选，要在几十秒内判断「这条做什么、用了会得到什么、要不要点进去」。
- agent 在没有网页的环境里搜索，只能读到描述；它需要知道「用了会得到什么」与运行环境，才能替用户初筛。
- 运营把开源 skill 接进官方命名空间，要低成本知道「每条落在哪个货架、预期结果是什么」，而不是逐条读正文。

**现在的替代做法是什么**：

- 结果行只有名字、描述、分类胶囊、公开标签、使用信号；描述是作者写的正文摘要，不是「你会得到什么」。
- 分类只有 10 个，缺 AI Agent、商业运营、教育学习、行业专业、生活服务五个域；`grilling` 这类元技能无处可去。
- 公开标签是作者写的自由词；同步进来的 skill 没有作者，没人替它写分类与标签。
- CLI 与网页读的字段不同；agent 看不到网页上的完整信息。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | **13 个一级分类**：SkillHub 的 12 个 + 「AI 协作」；单选、无二级；旧 10 个一次性重映射，保留映射表 | `skill_category` 词表迁移；控制台分类胶囊；`skmr skills categories` |
| 2 | **可扩展属性标签**：`label_definition` + `skill_label` 两层；首批 `runtime`（多值，21 个非默认值见下表）与 `pricing_type` 两个维度；`requires_api_key` 并入 `runtime=api-key`；`output_type` 不做（预期结果已覆盖）、`input_type` 推迟 | 两张表与词表；读接口字段；控制台与 CLI 展示 |
| 3 | **`outcome` 一句话**：写「预期结果」；新增字段，不覆盖原 `description`；低置信留空 | 抽取任务写入；信息页展示；CLI 输出 |
| 4 | **Agent 自动打标**：公域 skill 发布或同步到已上线后触发（草稿不抽）；每个值带 `confidence` 与 `evidence`；没有证据不落标签 | 抽取任务；模型与词表版本入库 |
| 5 | **三档路由与抽样验收**：高置信直接生效、中置信进异常队列、低置信留空；每周抽 30–50 条、目标准确率 95%；100–200 条金标集回归 | 抽取任务；运营覆盖文件；CI 抽样报告（不提供运营 CLI） |
| 6 | **结果行与信息页**：结果行只显示作者描述；信息页新增「预期结果」与属性行；分类词表扩到 13 个；**控制台不显示公域计数**——分类菜单、公开标签清单的每一行、搜索结果页头与分页条都不带数字；**结果页有自己的头**（「搜索结果 / 筛选结果 + 筛了什么 + 清除」），与「为你推荐」可区分；筛选与入口不变 | 控制台 `ResultCard` / `SkillPage` / `InfoPane` / `MyTagsPage` / `DiscoverPage` / `Pager`；`design/` 设计件 |
| 7 | **CLI 同口径**：`skmr skills search --label key:value`、`skmr skill show` 输出 `outcome` 与标签、`skmr skills categories` 列出 13 类、`skmr skills labels` 列出属性词表（不含公域计数） | CLI 读模型与格式化 |
| 8 | **运营校准**：覆盖文件 + CI 抽样报告；**不提供运营专用 CLI**——所有命令都是给用户用的 | 覆盖文件格式；CI 报告 |
| 9 | **对外文案**：「1000+ 个精选 skill」改为不承诺数量与「精选」的说法 | `design/src/home/HomeApp.tsx`、`skillmaster-site/src/App.tsx` |

#### 运行环境词表（21 个非默认值 + 默认）

`runtime` 是一个多值维度：一个 skill 可以同时挂多个值；列表与信息页只画非默认值。

| 组 | 值（key） | 胶囊上（短名） | 完整语义（词表数据；前端不展示） |
|---|---|---|---|
| 语言运行时 | `python` | Python | 需要 Python |
| | `node` | Node.js | 需要 Node.js |
| | `java` | Java | 需要 Java |
| | `c-cpp` | C/C++ | 需要 C/C++ |
| | `go` | Go | 需要 Go |
| | `rust` | Rust | 需要 Rust |
| | `dotnet` | .NET | 需要 .NET |
| | `ruby` | Ruby | 需要 Ruby |
| | `php` | PHP | 需要 PHP |
| | `shell` | Shell | 需要 Shell |
| 操作系统 | `macos` | macOS | 仅 macOS |
| | `windows` | Windows | 仅 Windows |
| | `linux` | Linux | 仅 Linux |
| 工具与资源 | `browser` | 浏览器 | 需要浏览器 |
| | `docker` | Docker | 运行时需要 Docker 引擎（只写 Dockerfile 不算） |
| | `gpu` | GPU | 需要本机/本地集群 NVIDIA GPU（云 GPU 不算） |
| | `database` | 数据库 | 需要连接数据库服务（教 SQL / 查公开数据库 API 不算） |
| | `online-service` | 在线服务 | 需要在线服务 |
| | `system-permission` | 系统权限 | 需要系统权限 |
| 凭据 | `api-key` | API key | 需要 API key |
| | `account` | 账号 | 需要账号 |
| 默认 | `prompt-only` | — | 纯提示词（不展示） |

- **胶囊只写短名**；`需要 / 仅` 这类完整语义只留在词表数据里（抽取证据 / CLI / 运营），**不在前端展示**——不进胶囊、tooltip 或读屏。
- **类别用颜色 + 图标区分**：语言蓝、系统紫、工具青、凭据琥珀、定价灰；颜色不是唯一信号，文字与图标同时在场。
- **表外语言不塞「其他」**：Agent 提议新增一个值，插词表行，不改表结构。
- **框架不单列**：React 归 `node`，Spring 归 `java`；具体版本看 skill 自己的说明。
- **操作系统只在限定时标**：跨平台或没发现限制就不标；只支持 macOS 与 Linux 就挂 `macos` + `linux`，显示「macOS · Linux」。
- **定价**：`free`（不展示）/ `paid`（付费）。

#### 三个高歧义值的抽取口径

`gpu` / `docker` / `database` 这三个值最容易把「skill 在讲什么」误当成「运行时缺什么」。只按 skill 自己的步骤取证，简介里出现关键词不算：

| 值 | 标 | 不标 |
|---|---|---|
| `gpu` | 步骤要求本机或本地集群的 NVIDIA GPU（`torchrun`、CUDA、`nvidia-smi`、DGX Spark） | 云 GPU / serverless GPU（归一为 `online-service` + `api-key`）；只是讲 GPU 优化但可跑 CPU |
| `docker` | skill 的步骤要求运行 `docker` / 容器（`docker build`、`docker run`、`docker compose`） | 只写 Dockerfile、生成 compose / K8s 清单、把 Docker 当教学主题 |
| `database` | skill 的步骤要连接或执行到数据库（`EXPLAIN`、迁移、复制、监控） | 教 SQL、设计 schema、查公开数据库 API（后者是 `online-service` + `api-key`） |

已核实的正例与反例：`orchestra-training-llms-megatron` 的 quickstart 是 `docker run --gpus all`，标 **Docker + GPU**；`jeffallan-devops-engineer` 只产出 Dockerfile 与流水线示例，**不标 Docker**；`jeffallan-sql-pro` 的 Verify 步骤要跑 `EXPLAIN ANALYZE`，标 **数据库**；`database-lookup` 查公开数据库 API，标 **在线服务 + API key**，**不标数据库**；`orchestra-modal-serverless-gpu` 用云 GPU，**不标 GPU**。

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 二级目录（SkillHub 的 96 个二级） | 用户靠搜索、不靠翻目录；96 个筛选项会撑大界面与维护面。二级词转为候选标签词库，仍能做细粒度召回 | 数据证明 13 个一级装不下时，另开 ADR |
| 2 | 安全扫描、凭据治理 | 属于另一条治理线，不阻塞标签与分类 | 见 [`TODO.md`](../../current/TODO.md)「skill 凭据治理」 |
| 3 | 许可冲突校验 | 属于入库流程的另一条线 | 见 [`TODO.md`](../../current/TODO.md)「许可逻辑」 |
| 4 | 举报入口、下架流程 | 平台未上线，公域以开源导入为主 | 见 [`TODO.md`](../../current/TODO.md)「对外举报入口」「公开库的内容治理与标注」 |
| 5 | 效果评测（有/无 skill 对照） | 需要任务集、沙箱与持续模型成本；这一版先做「主张」层 | 见 [`TODO.md`](../../current/TODO.md)「skill 质量评测」 |
| 6 | 用户评价、评分、评论、评测视频 | 它们是「评价」与「证据」层，与 `outcome` 的主张层分开；生命周期、权限、审核规则都要单独定 | 有评价/证据需求时单独开版 |
| 7 | 收藏 | 与入库是两件事，需要独立数据模型与列表入口 | 见 [`TODO.md`](../../current/TODO.md)「收藏（独立于入库）」 |
| 8 | 作者公开 | [ADR 0146](../../decisions/0146-public-is-platform-endorsement.md) 不变：公开仍是平台背书 | 有审核与责任主体时另开 ADR |
| 9 | 排序权重调整、逐卡推荐理由 | 这一版只加标签与预期结果，不改排序；排序需要权重与足够样本 | 见 [`TODO.md`](../../current/TODO.md)「公域排序与策展」 |
| 10 | 多语言词表与界面多语言 | 首批只有中文名与英文 key；多语言是独立的展示层 | 有海外需求时 |
| 11 | 分类树页面 | 发现页是搜索 + 推荐，没有分类树场景；[ADR 0078](../../decisions/0078-a-category-is-one-shelf.md) 的「不建树」继续成立 | 不做 |
| 12 | 运营控制台工作台 | 这一期用覆盖文件 + CI 报告；所有 CLI 命令都面向用户，不提供运营专用命令；工作台需要新的鉴权与路由 | 见 [`TODO.md`](../../current/TODO.md)「运营侧策展工作台」 |
| 13 | 把 96 个二级词做成筛选条 | 只做候选标签，不做第二层筛选 | 与「不做 1」同一触发条件 |
| 14 | 改原 skill 正文、`description`、`when_to_use`、frontmatter | 这一期只增新字段；原有信息一个字不改 | 不做（原信息属于作者） |
| 15 | 输入要求（类型枚举） | 运行环境能从 `requirements.txt` / `package.json` / `pom.xml` / `Dockerfile` 与 SKILL.md 命令取证；输入要求要判断「必需还是可选」，同一 skill 的不同用法答案不同，Agent 容易标错；「预期结果」与原描述已覆盖「要准备什么」 | 有搜索日志证明用户按输入类型找时，先做二值「需要提供材料」 |
| 16 | 产出形态（`output_type`） | 预期结果已经说了「用了会得到什么」，再挂一个「文档与报告 / 数据与表格」这类粗类目是同一件事的重复；还多一个维度要打标、审核与维护 | 有搜索日志证明用户按产物类型过滤、且预期结果不够用时，再补词表 |

## 控制台操作与 CLI 对应

这一版新增或改动的每一处控制台操作，都要有 CLI 命令对应（[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)）。运营校准不是控制台操作，走覆盖文件，因此不在此表。

| # | 控制台操作 | CLI 命令 | 备注 |
|---|---|---|---|
| 1 | 在信息页看到 `outcome`，在结果行看到分类与属性标签 | `skmr skill show <address>` | 输出同一组字段 |
| 2 | 在搜索结果里看到同样的字段 | `skmr skills search` | 默认就有「属性」行；`--verbose` 再加来源等字段 |
| 3 | 点分类胶囊，看该类结果 | `skmr skills search --category <key>` | 既有命令，词表扩到 13 |
| 4 | 按属性标签筛选 | `skmr skills search --label <key:value>` | 可重复，多个取「且」 |
| 5 | 看分类词表 | `skmr skills categories` | 列出 13 个一级词；不打印公域计数 |
| 6 | 看属性词表 | `skmr skills labels` | 列出维度、键、短名与完整语义；不打印公域计数 |

**没有高风险例外。** 运营校准的技术形态是覆盖文件，不是控制台操作；如果将来做控制台工作台，先补 CLI 或按 ADR 0081 记例外。

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | 公域一级分类只能取 13 个词之一；旧 10 个全部有映射；映射表可查、可回滚 | `skmr skills categories` 返回 13 个；迁移测试覆盖 10 个旧值；映射表行数 = 10 |
| A2 | 新增一个属性维度只需往词表插行，不改表结构、不改代码 | 测试迁移插入第 3 个维度；API/CLI 能读到；无代码改动 |
| A3 | 每个 Agent 标签都有来源、置信度与证据；没有证据的值不落库、不展示 | 库表查询 `source=agent` 时 `evidence` 非空；读接口过滤无证据项 |
| A4 | 结果行只显示原 `description`，不出现「预期结果」标签；描述共用第二行，不额外占行；列表按 1440×900 一屏 6 行验收 | `console-states --only discover` 通过；推荐与结果两处 `outcomeLabel` 都为空；`discover-long` 的同一第三行内容下行高差 ≤ 2px |
| A5 | 信息页显示 `outcome` 与属性标签，原 `description` 仍在；`skmr skill show` 输出同一组字段 | 同一 skill 的网页字段与 CLI 输出逐项一致 |
| A6 | 按属性标签筛选在网页与 CLI 结果一致 | 同一组 `--label` 条件返回同一组地址 |
| A7 | 每周抽 30–50 条自动通过的标签；准确率 ≥ 95%；100–200 条金标集回归通过 | `deploy/label-audit.yml` + CI 报告；金标集运行结果 |
| A8 | 低置信标签不展示；中置信标签进异常队列 | API/DB 抽样；CI 报告能看到中置信簇 |
| A9 | 首页与官网不再出现「1000+ 个精选 skill」 | `design/src/home` 与 `skillmaster-site/src` 的界面文案 grep 无该句；`home-check` 通过（官网暂无自动检查） |
| A10 | 旧分类迁移可 dry-run、可回滚 | `skillmaster-server/scripts/dry-run-v66-category-migration.sh` 在事务内执行并回滚；回滚按 `skill_category_assignment_snapshot` 逐 skill 恢复并验证 |
| A11 | 同一个 skill 在列表、搜索、推荐三处的分类与标签一致 | 三个读接口对同一 skill 返回同一组值 |
| A12 | 原有 `description`、`when_to_use`、frontmatter 一个字没改 | V52 只 `UPDATE skill.category_key`，不出现这三列；未做前后 diff 的自动化用例（构造保证） |
| A13 | 运行环境是多选：同时需要 Node.js 与 Python 的 skill 在列表与信息页各显示两枚；列表最多三枚，第四枚起收成 `+N`，点开是全部；纯提示词与免费不显示；胶囊短名按类别着色 | `console-states --only discover` 的 `screenshot-annotator + runtime:node` 用例（三枚 + `+1` + 浮层四枚）；信息页 `runtime:python` 用例 |
| A14 | `gpu` / `docker` / `database` 按「运行时真的缺什么」抽取，不按主题抽取；云 GPU、只写 Dockerfile、教 SQL / 查公开数据库 API 都不误标 | 金标集包含正例 `orchestra-training-llms-megatron`（Docker + GPU）、`jeffallan-sql-pro`（数据库）；反例 `jeffallan-devops-engineer`（不标 Docker）、`database-lookup`（不标数据库）、`orchestra-modal-serverless-gpu`（不标 GPU）；逐条核对证据行 |
| A15 | **控制台不显示公域计数**：分类菜单、公开标签清单的每一行、搜索结果页头与分页条都不带数字（标签清单页头的条数仍保留）；分类筛选、属性筛选与公开标签入口仍可用 | `console-states --only detail` 的分类菜单 `.menu-number` 为 0；`--only my-tags` 的公开标签行没有 `.tag-count`，每行有「查看 skill」链接；`--only discover` 的 `#page-discover .count` 与 `#discoverPager .pager-count` 都为空 |
| A16 | **搜索结果与推荐可区分**：结果页有「搜索结果 / 筛选结果」标题、说明筛了什么的小字与「清除」入口；推荐页没有结果头 | `console-states --only discover`：关键词结果标题「搜索结果」、小字「按相关度排序」；分类结果标题「筛选结果」、小字「办公效率 · 数据分析」；推荐页 `headTitle` / `headNote` / `headClear` 都为空 |

**成立条件**：A1、A3、A4、A7、A13、A14 任一条不满足就不算交付。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 一级分类覆盖率 | 公域已发布且公开的 skill 中，至少有一个一级分类的比例 | 库表统计 | ≥ 95%（首轮回填后按实测校准） |
| 属性标签覆盖率 | 至少有一个属性标签的比例 | 库表统计 | ≥ 80% |
| `outcome` 覆盖率 | 有非空 `outcome` 的比例 | 库表统计 | ≥ 80% |
| 抽样准确率 | 每周抽检样本中，标签与人工判断一致的比例 | `deploy/label-audit.yml` + CI 报告 | ≥ 95% |
| 运营周成本 | 处理异常簇 + 抽检的时间 | 运营记录 | ≤ 2 小时/周 |

> 公域实际条数未核实：没有总数接口，`/web/discover/recommended` 匿名返回 401。首轮回填时先数一遍，再按实际条数校准覆盖率目标。

## 拥有面

| 要动的文件 / 面 | 是不是共享面 | 谁在用 |
|---|---|---|
| `skillmaster-server` 的 M12 skill 模型、标签服务、迁移 | **是**（`skill` 表与迁移） | 所有 skill 读写 |
| `skillmaster-server` 的 M08 检索 / M13 向量 | **是** | 发现、库、CLI 搜索 |
| `skillmaster-server` 的 ingest / sync 流水线 | **是** | 公域导入 |
| `skillmaster-console` 的 `DiscoverPage` / `SkillPage` / `ResultCard` | **是**（`ResultCard` 推荐与搜索共用） | 发现与信息页 |
| `design/src/console/` 的结果行、信息页、分类菜单、夹具 | **是**（`styles.css`、`fixtures/*`、`ResultCard`） | 全控制台 |
| `skillmaster-cli` 的 `declarations*.go`、`skills search`、`skill show` | **是** | 所有 CLI 用户 |
| `skillmaster-site` 首页文案 | 否 | 官网 |
| `docs/research/`、`docs/decisions/0192–0195`、`docs/versions/v66-skill-labels/` | 否 | 文档 |

## 依赖与前置

- **决策**：[ADR 0192](../../decisions/0192-public-categories-are-thirteen-shelves.md)（13 类）、[ADR 0193](../../decisions/0193-public-labels-are-extensible-claims.md)（可扩展标签、运行环境多选与四层分离）、[ADR 0194](../../decisions/0194-public-labels-are-agent-produced-and-sampling-audited.md)（自动打标与抽样验收）。
- **收窄与取代**：ADR 0195 记了 P0 对 0193 / 0194 的四点收窄；ADR 0192 取代 ADR 0078 的「10 个词」与「公域由作者选」两半，以及「`skills categories` 打印公域计数」一句；平级、单选、无父子继续成立。
- **词表来源**：SkillHub 公共 API 的 12 个一级分类（2026-11-13 快照）；一次性抄入我们的迁移，不建实时同步。
- **抽取前置**：需要一个能读 `SKILL.md` 与文件清单的模型；模型、成本与阈值在第 3 关定，并用 100–200 条金标集实测。
- **界面权威**：`design/` 必须先改（第 2 关），实现照设计件搬；设计稿没覆盖的态先问，不自创。
- **迁移前置**：生产公域条数与旧分类分布未核实；先 dry-run，再按映射表执行。
- **鉴权前置**：这一期没有运营角色；校准走覆盖文件，不做控制台写操作。将来做工作台时另开 ADR。
