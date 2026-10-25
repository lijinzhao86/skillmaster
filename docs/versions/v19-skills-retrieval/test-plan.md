# v19-skills-retrieval · 测试方案

## 验收映射

| prd 验收 | 用例 |
|---|---|
| 1 标注集 `Recall@10` ≥ 0.90 | T01 |
| 2 低于阈值不返回；整批为空时明说 | T02 |
| 3 私域与公域同一策略，只换范围 | T03 |
| 4 `--limit` 生效、上限 50、`--cursor` 不成其为参数（API 语义档对 `cursor` 回 400） | T04 |
| 5 权限：别人的私有 skill 不出现 | T05 |
| 6 发布即索引（发布 / 回滚 / 下线 / 删除） | T06 |
| 7 检索侧退化为关键词、发布侧 503 | T07 |
| 8 隐私开关关闭后检索走关键词、且能启动 | T08 |
| 9 成本与耗时可测（日志不含用户文本） | T09 |
| 10 CLI 与控制台同一结果与排序 | T10 |
| 11 离线评测可复跑 | T11 |
| 12 不拖慢既有路径（p95 增幅 < 10%） | T12 |
| 13 默认就是私域 + 语义 | T13 |
| 14 发布失败不留下半个状态 | T14 |
| 15 发布等待态有反馈 | T15 |

**评测集的唯一权威**：`skillmaster-server/src/test/resources/retrieval/`——

| 文件 | 是什么 |
|---|---|
| `skills.json` | 33 个 skill 的 `name` + `description`（与 `labeled.tsv` 的目标名一一对应） |
| `labeled.tsv` | **99 行：66 条语义式 + 33 条名字式**，列是 `query<TAB>skill_name` |
| `unmatched.tsv` | **38 条**市场里没有对应能力的请求（负样本，判据是"应当沉默"） |

`labeled.tsv` 由 2026-10-25 的 spike 生成（33 个本机 skill，问题按描述由模型生成后人工过一遍；名字式是每个 skill 一条、直接用它的名字）。

⚠️ **spike 的数字不能直接当基线**：spike 当时跑的是 106 行（40 条名字式，含 7 条名字的同义变体），那些变体没有随产物保存，所以随仓库走的是 99 行。T01 的基线必须是这 99 行测出来的 0.909，不是 spike 在 106 行上的合计 0.95——两者不是同一个集合，同义变体让名字式那组更难或更容易，也无法复现。

## 用例

### T01 · Recall@10 达标
- **前置**：`src/test/resources/retrieval/{skills.json,labeled.tsv}`（33 个 skill、99 条标注问题）
- **步骤**：`DASHSCOPE_API_KEY=… python3 scripts/retrieval-eval.py`——按 `SkillEmbeddingTextSource.compose` 的规则组文本、
  逐条算余弦、统计目标 skill 是否在前 10
- **期望**：**两组各自 ≥ 0.90** 才算过（合计只作参考——合并成一个数正是口径要避免的）；低于即失败，不是警告
- **结果**：**通过**——语义式 **0.909**（60/66）、名字式 **1.000**（33/33）、合计 **0.939**（93/99）。
  离 0.90 只有 0.009，两组里语义式是决定成败的那一组
- **口径**：脚本是离线复算，不经过 HTTP 与数据库（组文本 → 同一模型 → 余弦），它量的是**召回**；
  接口与库那一层由 T02 / T06 / T13 覆盖。脚本里的 `compose` 是 Java 那份的手抄副本，
  2026-10-25 用「把 Java 的 `compose` 结果与脚本的逐字对比」核过一次：33 个 fixture **完全一致**

### T02 · 阈值与「没有匹配」
- **前置**：`src/test/resources/retrieval/unmatched.tsv`（**38 条**库里确实没有答案的请求）
- **步骤（a，定阈值）**：脚本对每条负样本取「33 个 skill 能给到的最高分」，与标注集里正确答案的分数分布一起扫一遍候选阈值
- **步骤（b，接口层）**：一条匹配不上的查询走真实接口
- **期望**：负样本的最高分低于阈值（一条都漏不过）；阈值不得把正确答案成批挡掉；整批为空时响应带 `note: 没有足够相关的 skill。`，不是 10 条凑数
- **结果（a）**：**通过**——负样本最高 **0.397**（「把语气改得更客气」那条），**进过前 10 的**正确答案 5 分位 **0.415**；**阈值取 0.40**。
  代价：被挡掉的命中行 3/99、整批为空 3/99、负样本漏过 **0/38**。两侧只隔 0.018，余量薄，改评测集要重量
- **结果（b）**：**通过**——`q=红烧肉怎么做才不腻` 走真实接口返回 `{"skills":[],"note":"没有足够相关的 skill。"}`；
  同一句加 `semantic=false` 返回 `{"skills":[],"note":null}`（字面路径没有这句说明，也说明它不该有）

### T03 · 两个范围同一策略
- **前置**：一个公域可见 skill + 一个只有自己可见的 skill
- **步骤**：同一问题分别以 `scope=library` 与 `scope=public` 检索
- **期望**：排序机制相同（都按分数降序、都受同一阈值约束）；候选集都在 20000 以内时，结果集合只差可见性谓词（超过时走近似路径，见 TD §检索链）

### T04 · 条数与游标
- **步骤**：`--limit 3`；`--limit 100`；带 `--cursor`
- **期望**：3 条；截到 50 并在输出里说明；`--cursor` 是用法错误，退出码 2（`skills search` 从不接受它，与语义档无关）

### T05 · 权限
- **前置**：另一个账号的私有 skill，名字与问题高度相关
- **期望**：`scope=library|public` 都不出现；公域只返回公开且 live 的

### T06 · 发布即索引
- **步骤**：发布新版本（描述不同）→ 一拿到 200 就检索 → 回滚到上一个版本 → 再检索 → 下线 → 再检索 → 删除
- **期望**：**没有等待窗口**——200 之后立刻能按新文本命中；回滚后立刻按旧文本命中（旧向量被覆盖，不是并存）；
  下线后不再出现；删除后**向量行仍在那张表里**、但任何检索都不再返回它（软删除由 M12 的谓词滤掉，设计里没有硬删）。
  草稿不产生任何向量

### T07 · embedding 故障降级（两侧）
- **前置**：模拟 embedding 超时或 5xx
- **检索侧**：接口退化到关键词并在响应里说明「语义检索暂不可用」；不返回 500、不阻塞请求。
  **字面也没找到、或 `scope=all` 没有字面形式时，空结果是正确答案**——判据是有没有那句说明，不是有没有行
- **发布侧**：发布返回 `503 semantic_unavailable`；错误里不含被 embedding 的文本
- **期望**：同一次故障下，读一直可用，写明确失败而不是"成功了但没索引"

### T08 · 隐私开关
- **前置**：配置关闭向量化（`skillmaster.retrieval.enabled=false`），且不起 embedding 客户端
- **期望**：服务能正常启动；检索走关键词；发布照常成功且不产生向量。**注意**：迁移里的 `CREATE EXTENSION` 仍然要执行——扩展缺失是部署错误，必须响亮地失败，不属于本用例的"能启动"

### T09 · 成本与耗时
- **期望**：**不含用户查询文本**，三行各记各的——`DashScopeEmbedder` 每批一行 token 数与条数、`RetrievalService` 每次查询一行耗时与条数、`EmbeddingIndex` 每次发布索引一行耗时；费用 = token 数 × 单价。换模型的全量重算不在本版，不在这里量

### T10 · CLI 与控制台一致
- **步骤**：同一句问题分别用 `skills search --scope public --verbose "…"`（默认语义、默认 10 条；`--verbose` 才打印 `相似度`）与控制台发现页搜索框（回车，分数看响应里的 `score`）
- **期望**：同一批结果、同一排序、同一分数（两边走同一个 `SemanticSearchUseCase`、同一条阈值）。
  **分数比的是两个响应里的 `score`**——控制台那一栏不显示分数，界面上看不到它
- **口径**：只比"有 q"这一种；发现页只选分类时是字面检索、六条一页，不在这一条里
- **结果**：**服务端已验**（`WebDiscoverSemanticIT`：语义形状、分数随卡返回、阈值挡下时是空结果且**不带** `note`——那句空态归页面，词表未命中才出 `note`）；
  **两边并排比**未做——需要线上或本地有若干条带向量的公开 skill

### T11 · 离线复跑
- **步骤**：`cd skillmaster-server && DASHSCOPE_API_KEY=… python3 scripts/retrieval-eval.py`
- **期望**：不连生产、不连数据库，读随仓库的 fixtures，复跑出 T01 的数字；第二次起不花钱
- **结果**：**通过**。向量按「被嵌入文本的 sha256」缓存在 `.retrieval-vectors.json`（`--cache` 可改路径），
  重跑只读缓存、零调用；改了一条 fixture 只失效那一条。首跑 33 + 99 + 38 条文本约 17 次调用（每批 10 条），
  耗时分把钟，**费用以分为计**

### T12 · 不拖慢既有路径
- **步骤**：`semantic=false` 与今天的 `skills search` 各跑 100 次
- **期望**：p95 增幅 < 10%

### T13 · 默认值
- **步骤**：不带任何旗标跑 `skills search "怎么把 PDF 拆成单页"`
- **期望**：只返回自己库里的 skill、按语义排序、前 10；加 `--semantic=false` 变字面；加 `--scope public` 变公域

### T14 · 发布失败不留下半个状态
- **前置**：一个已经发布过 v1 的 skill；embedding 设为失败
- **步骤**：把 v2 发布上去 → 读该 skill 的详情与版本列表 → 恢复 embedding 后重发 v2
- **期望**：第一次返回 503，且指针仍指 v1、审计没有新增行、`skill_embedding` 仍是 v1 的文本哈希；
  恢复后重发成功，指针到 v2，向量换成 v2 的。**这是"事务做完才算发布成功"的可判定形式**

### T15 · 发布等待期间有反馈
- **前置**：控制台任意一屏的上线确认弹窗；embedding 桩设成每次调用慢 2 秒
- **步骤**：点"确认上线"，观察等待期间与结束后的弹窗
- **期望**：等待期间弹窗保持打开、按钮显示「正在上线…」且不可再次点击（这是设计件与其实现已有的态，
  `VersionActions.tsx` 的 `pendingLabel` / `ConfirmDialog.tsx`）；结束后按结果关闭。
  **视觉验收由人做**

## 环境与数据

- 服务端：本地 PostgreSQL 需装 pgvector（`CREATE EXTENSION vector`）；生产实例 `pgm-uf64hdx1njd59aih` 已确认可用（2026-10-25，阿里云 CLI 查 `DescribePostgresExtensions`）；
  本机测试库 `skillmaster_test` 上的 `extversion` 是 **0.8.7**（2026-10-25 查），生产上的版本仍未查
- embedding：百炼 `text-embedding-v4`，key 从环境变量读；**评测跑一次要花几分钱**，可用缓存向量避免重复计费
- 样本：33 个 skill 用 `Zips` 造，文本与 `labeled.tsv` 对应；不读本机 `~/.claude/skills`
- 清理：每个用例结束截断业务表（沿用 `truncate-business-tables.sql`），向量表随外键级联
- **测试里的语义开关是钉死的**：`AbstractIT` 用 `@SpringBootTest(properties = "skillmaster.retrieval.enabled=false")`
  把它关掉，这一条比环境变量优先——否则谁 source 过 `.env`，整套 IT 就会真的去打百炼，而且发现页的答案会在
  它脚下换形状。要打开的那份用例自己声明属性并注入桩 embedder（`WebDiscoverSemanticIT`）

## 结果

服务端、CLI、控制台发现页都已实现（2026-10-25）；评测与阈值已跑。

**下表每行的「证据」列就是那条用例的全部依据，这里不再另作总结**——总结过的数字会漂，那一列不会。
读法只有一条：**「通过」不等于「有自动化用例」**。证据里点名了某个测试（`mvn verify` / `go test` / 设计检查脚本）的，才是被守住的那一部分；写「手工通过」的是跑过一次并留下记录；写「未执行」「未做」的是没跑。要问一条用例由谁守着，看那一列有没有测试名。

| 用例 | 结果 | 证据 |
|---|---|---|
| T01 | **通过** | 语义式 0.909 / 名字式 1.000 / 合计 0.939（n=99），`scripts/retrieval-eval.py` |
| T02 | **通过** | 负样本最高 0.397、**进过前 10 的**正确答案 p5 0.415 → 阈值 0.40，漏过 0/38；接口层的空结果文案见本表下方 |
| T03 | 部分 | 两个 scope 走同一套谓词与同一语句（代码层），**未**在真实数据上跑对照 |
| T04 | 通过 | `search_v19_test.go` 的 `TestSearchSaysWhenItClampedTheLimit`（100 截到 50 并打印一行）；`--cursor` 的运行期拒绝由 `main_test.go` 的 `parseQueryArgs` 表钉住，`flagattribution_test` 是 AST 检查（只保证 help 里不列它）；API 语义档带 `cursor` 回 400 由 `WebDiscoverSemanticIT.aSemanticSearchRefusesACursor` 钉住 |
| T05 | 部分 | 可见性谓词由 `SkillPredicates` 承担，`SkillSearchIT.anotherUsersSkillNeverAppears` 覆盖「别人的私有 skill 不出现」；**语义档**下同一情形未见端到端跑 |
| T06 | **手工通过** | 发布成功后 `skill_embedding.text_hash` 与该 skill 当前文本重算的哈希一致；失败发布不落向量。没有自动化用例 |
| T07 | **通过（检索侧自动化 / 发布侧手工）** | 检索侧由 `WebDiscoverSemanticIT` 的降级两例覆盖；发布侧手工跑过：503 + 整笔回滚 |
| T08 | **手工通过** | 关开关后 `skills search` 静默走关键词、无 `note`、无 `相似度`；自动化那一半只有「整套 IT 在这个开关下能跑」（`AbstractIT` 钉死它） |
| T09 | **通过** | `DashScopeEmbedder` 每批一行 token 数与条数、`RetrievalService` 每次查询一行耗时与条数、`EmbeddingIndex` 每次发布索引一行耗时；三处都不含文本 |
| T10 | 部分 | 服务端两种形状、降级、分类过滤、API 面语义由 `WebDiscoverSemanticIT`（8 例，桩 embedder）覆盖；控制台侧由 `discover.test.tsx`（19 例）覆盖；**两边并排比**未做 |
| T11 | **通过** | 向量缓存命中，重跑零调用 |
| T12 | 未执行 | 需要一次 100 次的对照测量，未做 |
| T13 | 通过 | `skills search` 默认语义 + 私域；`--semantic=false` 回字面 |
| T14 | **手工通过** | 真实失败发布：审计 1 条、版本 1 条、指针不动、无向量。没有自动化用例 |
| T15 | 未执行 | 需要慢 2 秒的 embedding 桩 + 浏览器观察；等待态本身是既有组件，未改 |

`WebDiscoverSemanticIT`（8 例）：`/web/discover` 有 `q` 时是语义形状（`score` 在卡上、没有 `p`/`total`）、
相似度不足时是空结果且**不带** `note`（那句空态归页面）、分类按 key 收窄、词表里没有的名字出 `note`、
provider 不可用时回落字面检索（有结果 / 无结果两句不同的说明），以及 API 面的语义、降级与「带 `cursor` 回 400」。
桩 embedder 让余弦恰好是 1.0 或 0.0，所以阈值那条断言不会碰巧通过，也**不调用付费接口**。

`EmbeddingRepositoryIT`（4 例）在真实 PostgreSQL + pgvector 0.8.7 上通过——V30 的列类型、`(skill_id, model)` 的覆盖语义、`hasVectorFor`、硬删级联都在其中。

## 已知问题

实现后剩下这几条，都是**未做到或未验证**，不是「以后再说」：

- **生产实例的 pgvector 版本未查**（本机测试库是 0.8.7）：低于 0.8 时改用不过滤的精确扫描。上线前跑一次 `SELECT extversion FROM pg_extension WHERE extname='vector';`
- **生产 RDS 的 `CREATE EXTENSION` 权限未验证**：迁移账号可能不是 `rds_superuser` 一档。
- **T12 未测**：`semantic=false` 相对今天的关键词路径的 p95 增幅没有量过。
- **T15 未测**：发布等待态需要慢桩与浏览器观察。
- **评测文本只有两个字段**：fixture 的 33 个 skill 没有 `title` / `when_to_use`，所以 0.909 与阈值 0.40 描述的是「只有名字与描述」的 skill（口径见 TD §检索链）。
- **实例规格**：现在是 1 核 1 GB，一万条以内舒适（口径见 TD §检索链 › 规模与规格）；十万与百万的容量靠升规格，不是代码改动。
