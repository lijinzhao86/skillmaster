# 搜索排序与个人适用场景：调研与设计底稿

> **状态**：设计底稿，2026-11-10。**本文不下决定**——跨版本的取舍进 `docs/decisions/`，某一版做到哪进 `docs/versions/`。
> **用途**：为「用户给自己的库里的 skill 写一段适用场景，让搜索更准」这件事提供算法底稿；同时登记未来 LTR 训练需要的样本口径。
> **口径**：外部算法结论标注来源；本仓库的现状以代码为准，本文只写指针，不复述可派生的数值。

## 结论先行

1. **排序应当 intent-first，不是 relevance-first**：先判断「这条 skill 是不是用户此刻要的」，再判断「它和 query 有多像」。证据分级（用户明确写过 > 名字/标题 > 作者内容 > 通用语义）比一个大加法更稳。
2. **个人说明只在自己的库里生效**：用户把公开 skill 入库时才能写；不进公域检索，不进共享向量。这样个人化成本是 `O(M)`（库大小），不是 `O(N)`（公域规模）。
3. **用户只写自然语言，不维护触发短语**：精确命中用「查询与说明的最长公共子串」，语义命中用「说明按句切、向量取最大」。同义词表不做，换说法交给语义。
4. **规则版先落，样本先攒**：v1 只做规则排序与事件记录，不训练；未来 LTR 用 Agent 的采纳行为构造 0/1 与 pairwise 样本。全 0 的 query（库里没有相关 skill）不进 ranker 训练，单独进覆盖率分析。

---

## 一、现状：四条检索路径

符号：`N` 公域可见行数，`M` 我的库行数，`d = 1024` 向量维，`Q` 查询词数，`L` 每行文本长度，`k` 返回条数。

| 路径 | 召回（候选集） | 排序 | 复杂度 |
|---|---|---|---|
| 公域 + 关键词 | 可见公开行 `R`，逐词 `ILIKE` 过 name/title/description/`when_to_use`/公开标签 | `relevance = name + title + description` 的字段权重；**整句子串**命中才计分 | `O(R·Q·L + R log k)` |
| 公域 + 语义 | 向量表全表 exact top-k | cosine | `O(N·d + N log k)` |
| 库内 + 关键词 | 我的库 `M`，逐词 `ILIKE` 同上 | **没有 relevance**，按 `updated_at` / 名字排 | `O(M·Q·L + M log M)` |
| 库内 + 语义 | 我的库 id 集内 exact top-k | cosine | `O(M·d + M log k)` |

四处不对称，是这次设计要处理的起点：

1. **公开适用场景只当门票**：`SqlFilters.text` 把它放进过滤条件，但 `SkillCatalogRepository.RELEVANCE` 只给 name/title/description 打分。只在 `when_to_use` 命中的 skill 是 0 分。
2. **打分是整句子串**：`PATTERN` 用整条 `queryText`，多词查询（「合同 审查」）几乎总是 0 分，最后按时间排。
3. **库内关键词没有相关性排序**：`LibraryRepository.page` 只过滤，然后按 `updated_at` 或名字排。
4. **语义是一条共用向量**：`SkillEmbeddingTextSource` 把 name/title/description/`when_to_use` 拼成一条向量；个人文字装不进去。`EmbeddingRepository.nearest` 是 exact scan，没有 ANN 索引；语义阈值默认值见 `application.yml` 的 `SKILLMASTER_RETRIEVAL_THRESHOLD`。

另外：`skmr skills search` 默认 `scope=library`、`semantic=true`，所以日常走的是「库内 + 语义」。

---

## 二、目标：把「用户想要的」排在最前

排序要回答三个问题，顺序不能乱：

1. **用户自己有没有明确说过「这种情况用它」？**（个人触发标准）
2. 如果没有，它的**名字 / 标题**是不是就是 query 本身？（身份）
3. 如果还不是，它的**内容**和 query 有多相关？（作者描述 + 语义）

### 证据分级

| 级别 | 证据 | 谁给的 | 含义 |
|---|---|---|---|
| **S0** | 名字 / 标题与 query 一致 | 作者 | 就是它 |
| **S1** | 个人触发短语精确命中 | 用户 | 用户明确说过「说这个就用它」 |
| **S2** | 个人触发短语语义高置信 | 用户 | 换了个说法，但意思就是它 |
| **S3** | 作者内容相关 | 作者 | 描述、公开适用场景、作者语义 |
| **S4** | 通用语义 | 模型 | 兜底 |

**最终排名 = 从上到下；同一级里，才比分数。** 跨级不比分数：不管 S3 的语义分多高，都越不过 S1「用户明确说过」和 S0「名字/标题一致」。

### 为什么不做一个大加法

加法需要调权重，而且会出现「内容很像、但用户根本没打算用」的 skill 靠高语义分压过用户明确写过的那一条。分级是结构性的：**权重只能决定同级顺序，不能决定跨级顺序。**

### 级内怎么比

- **S0**：完全一致 > 包含 > 部分重叠。
- **S1**：最长公共子串越长、越具体，越靠前；多条命中取最长。
- **S2**：query 向量与说明的句子向量取最大余弦；必须过阈值，且领先第二名一个 margin。
- **S3**：字段加权 BM25F + 作者向量，用 RRF 合并名次。
- **S4**：余弦相似度兜底。

---

## 三、个人适用场景：自然语言，不维护短语

### 生成时机

用户把公开 skill 入库时才能写；自己创建的、别人共享的 skill 在库里也可以写。**个人说明只在库里生效**，不进公域检索，不进共享向量。

### 存什么

- 一段自然语言；
- 按标点切成的 1–5 句，每句一个向量；
- 不存「触发短语表」，也不要求用户确认短语。

### 查询时怎么用

| 证据 | 做法 |
|---|---|
| **S1 精确** | 查询与说明的**最长公共子串** ≥ 3 个汉字（或 ≥ 5 个拉丁字符）→ 命中；长度越长分越高 |
| **S2 语义** | query 向量与说明的每句向量算余弦，取最大；过阈值 + 领先 margin |

例子：

> 说明：当我说「循环审计」的时候，用这个功能。
> 查询：帮我用循环审计跑一遍。
> 最长公共子串 =「循环审计」，长度 4 → S1 命中。

这覆盖了用户说的「只要说了这个词就必须命中」，又不需要他维护短语表。换说法（「反复审查」）由 S2 的语义兜底。**同义词表不做**——换说法交给语义，精确说法交给 S1。

### 阈值与防误触

- S2 用高阈值 + `top1 - top2 ≥ margin`；S3/S4 用现有语义阈值；S0/S1 精确不设阈值。
- 阈值必须在标注集上按 precision/recall 扫，不用 0.85 这类常数。短文本的近义干扰会让分数反转，`0.85` 的默认阈值的可靠性有限。
- 低置信不提升 tier，只加有界 boost。

---

## 四、复杂度

| 操作 | 复杂度 | 量级（`M=1000`、写过说明 `A=50`、每段 `P=3` 句、`d=1024`） |
|---|---|---|
| S0/S1 匹配（最长公共子串） | `O(A·Q·L)` | ~10 万次字符比较，微秒级 |
| S2 个人语义 | `O(A·P·d)` | ~15 万次乘加，亚毫秒 |
| S3 作者 BM25F | `O(M·Q·L)` | ~100 万次字符比较，几毫秒 |
| S3/S4 作者向量 | `O(M·d)` | ~100 万次乘加，1–3 ms |
| 排序 | `O(M log k)` | 忽略 |
| embedding API | 1 次网络调用 | 30–150 ms，最大固定成本 |

**结论**：个人层只扫「写过说明的 skill」，通常几十条；库内作者层 `M` 小，几毫秒。真正的成本是一次 embedding 调用和公域原有的全表扫。公域 ANN 索引的触发条件见 `TODO.md` 第 41 条「公域向量索引（HNSW）」。

---

## 五、为未来 LTR 收集样本（Agent 链路）

### 链路

```
用户说一句话
  → Agent 把这句话变成搜索词/意图句
  → Agent 调 skmr skills search
  → Agent 看结果，决定 show / read / invoke 哪一条
  → Agent 用这条 skill 完成任务
```

因此：

- **样本的 query = Agent 生成的搜索词**，不是用户的原始话语。检索服务在推理时看到的就是这个 query。
- **样本的 label = Agent 有没有采纳**（show / read / invoke），不是用户点击。
- 用户的原始话语是 Agent 的上游输入，属于「Agent 把话说成搜索词」的质量问题，不是检索模型的训练输入。

### 事件与串联

| 事件 | 字段 |
|---|---|
| `search` | `search_id`、`session_id`、`user_id`、时间、`query`、scope、结果列表 `[{skill_id, rank, tier, score}]`、是否零结果 |
| `adopt` | `search_id`（可空）、`skill_id`、动作（show / read / invoke）、`outcome`、时间 |
| `exclude` | `search_id`、被排除的 `skill_id` 列表、时间；另记 `excluded_skill_was_adopted_before` 与 `adopted_after` |
| `feedback` | `session_id`、`skill_id`、成功 / 失败、时间 |

串联办法：`skills search` 的响应带 `search_id`；CLI 把它写进会话状态；`skill show/read/invoke` 的请求头带 `X-Search-Id`；服务端把采纳和那次结果对上。**不需要另建一套埋点管道**，服务端本来就同时看到 search 和 invoke。

### 0/1 标签

| 信号 | 标签 | 强度 |
|---|---|---|
| adopt（invoke 成功） | 1 | 强正 |
| show / read | 1 或 0.5 | 弱正 |
| `exclude` 且之前没采纳过、随后选了别的 | 0 | 强负 |
| 改词重搜，前一次结果没用 | 0 | 中负 |
| 展示了但没选 | 0 | **弱负，降权** |
| 没展示 | 不打标签 | — |
| invoke 报错 / 超时 | 不打标签 | 可用性问题，不是相关性 |

**`exclude` 的歧义**：CLI 的 `--exclude` 本义是去重（已看过、不要再给），不是「差评」。只有它没有被采纳过、并且随后采纳了另一条时，才升级为强负；之前 invoke 过的排除是去重，是正/中性。

### 全 0 的 query 不进 ranker 训练

「库里确实没有相关 skill」的 query 不能把展示结果全打成负样本：

- 假负例：被展示但没被选的，可能其实相关；
- 语料属性被当成语义信号：模型学到的是「我们库里没有」，不是「语义不相关」；
- 类别不平衡：全 0 query 会把整体分数往下拉。

处理：**只有至少一个正样本的 query 进 LTR 训练**；全 0 query 单独进「覆盖率 / 无答案」分析，用于补库与「什么时候该返回空」，不训练排序。

### 偏差

| 偏差 | 表现 | 处理 |
|---|---|---|
| 位置偏差 | Agent 通常读前几条 | 记 rank；pairwise「adopted vs. shown-above」 |
| 停止偏差 | 选到第一条 plausible 就停 | 同上；不把「只看过前几条」当硬负 |
| query 质量偏差 | Agent 的搜索词本身不好 | 单独评估 query 改写，不进 ranker 训练 |
| 多 skill 链路 | 一个任务用多条 skill | 每条都记正样本；按 session 归因 |
| 不同 Agent 行为不同 | Claude Code / Codex / Cursor 停在不同位置 | client 类型作为特征或分层 |

探索（exploration）先不做：让 Agent 看到随机结果会干扰它干活；先用 pairwise 与显式排除校正。

### 隐私

- Query 原文已在另一个工作流收集；这里只存 `query_id`，不重复落原文。
- 个人说明原文不落日志；`note` 事件只记「谁在哪个 query 后给哪条 skill 写了说明」，不记文字。要拿说明文本训练，是另一个授权。
- 用户标识 HMAC；不做个人画像；保留期与账号注销删除规则单独定。
- 这些数据只用于评估与训练排序，不用于展示；隐私政策要单独写一条「用我的搜索记录改进排序」。

---

## 六、未决问题

| # | 问题 | 当前倾向 |
|---|---|---|
| Q1 | S2 的高阈值与 margin 取多少？ | 在 20–50 条人工标注（正例 + 近义负例）上扫 precision/recall，不拍常数 |
| Q2 | 个人说明的句子向量要不要发给 embedding 服务？ | 要，否则换说法无法命中；隐私政策需补一条，或改用本地模型 |
| Q3 | 个人说明的文本是否允许进全局训练？ | 默认不允许；只用 `note` 事件做标签，不训练文本 |
| Q4 | `search_id` 由服务端生成还是 CLI 生成？ | 服务端生成、响应带回；CLI 只负责在会话状态里保存并回传 |
| Q5 | 事件落盘在服务端还是 CLI 本地？ | 优先服务端（search 与 invoke 都经过它）；CLI 本地只做会话状态 |
| Q6 | 公域 ANN 索引何时加？ | 见 `TODO.md` 第 41 条；用「条数 → P95」实测拐点定，不拍数字 |
| Q7 | 个人说明的 UI 叫什么、放在哪？ | 产品方案阶段定；候选「我的适用场景」，沿用「适用场景 + 我的」的分层命名 |

---

## 七、来源

**外部算法**

- [BM25F 字段权重与调参](https://arxiv.org/html/2410.20056)
- [中文检索：字符 n-gram 与分词](https://aclanthology.org/W02-1804.pdf)
- [Chinese information retrieval: using characters or words?](https://dl.acm.org/doi/pdf/10.1145/355214.355235)
- [Hybrid retrieval / RRF](https://zenodo.org/records/20138823)
- [Query expansion techniques](https://milvus.io/ai-quick-reference/what-are-query-expansion-techniques)
- [语义分数校准与阈值](https://mixpeek.com/guides/calibrating-similarity-scores)
- [短文本近义干扰与阈值反转（预印本）](https://zenodo.org/search?q=metadata.creators.person_or_org.name%3A%22Green%2C%20Guerin%20Lee%22&l=list&p=1&s=10&sort=bestmatch)
- [ColBERT / 多向量 late interaction](https://www.sbert.net/docs/multi_vector_encoder/usage/usage.html)
- [混合检索中的精确匹配 boost 与有界加权](https://github.com/pramod0s007/architect-notes/blob/main/volume-13-ai-system-design-retrieval/paper-69-hybrid-search/README.md)

**本仓库**

- [`docs/research/agent-skill-triggering.md`](agent-skill-triggering.md)：触发率的事实与杠杆
- [`docs/research/search-evaluation-baseline.md`](search-evaluation-baseline.md)：v59 的 33 skill / 123 查询评测基线，后续优化的对照
- [`docs/current/modules/M08-search.md`](../current/modules/M08-search.md)、[`M13-retrieval.md`](../current/modules/M13-retrieval.md)：现有检索与语义模块
- [`docs/current/TODO.md`](../current/TODO.md)：公域 HNSW、会话内检索/调用痕迹
