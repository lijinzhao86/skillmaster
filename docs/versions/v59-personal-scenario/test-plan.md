# v59-personal-scenario · 测试方案

> **最后更新**：2026-11-11
> **所属版本**：[`README.md`](README.md)
> **状态**：P0/P1/P2 已实现并跑过机械检查；T11（事件）依赖「个人调用记录」，本版未执行。

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 写入后在信息页与 `skill show` 显示同一段文字 | T1、T15、T16 | 通过 |
| #2 只对本人可见 | T2 | 通过 |
| #3 不产生版本 / 草稿，不改作者内容 | T3 | 通过 |
| #4 未入库的公开 skill 不能写 | T4 | 通过 |
| #5 S1 精确命中排在作者内容之前、名字/标题之后 | T5 | 通过 |
| #6 S2 语义命中能召回换说法 | T6 | 通过 |
| #7 公域搜索不受影响 | T7 | 通过 |
| #8 清除后不再按说明匹配 | T8 | 通过 |
| #9 校验：空文本、超长、非法地址 | T9 | 通过 |
| #10 控制台与 CLI 一致 | T10、T16 | 通过 |
| #11 事件可关联 | T11 | 未执行（P3 依赖「个人调用记录」） |
| #12 隐私口径已补 | T12 | 通过 |
| #13 下载→提交后作者字段不变 | T13 | 通过 |
| #14 个人说明不删除作者的那段 | T14 | 通过 |
| #15 `description` 上限 1024 码点 | T17 | 通过 |
| #16 作者 `when_to_use` 上限 200 码点，发布复校验 | T17 | 通过 |
| #17 我的适用场景上限 200 码点 | T9、T17 | 通过 |
| #18 同步 / 导入不合规拒绝并跳过 | T18 | 通过 |
| #19 离开库保留、重新入库恢复 | T19 | 通过 |
| §指标 离线评测 | T20 | 通过（个人组 75%→100%） |

## 用例

### T1 · 写入与显示

- **前置**：账号 A 已把一个公开 skill 入库。
- **步骤**：控制台信息页写「循环审计时用它」并保存；`skmr skill show <地址>`；再读 `GET /my-when-to-use`。
- **期望**：三处读到同一字符串；`skill show` 同时显示「适用场景」（skill 自带）与「我的适用场景」；`updated_at` 为本次时间。
- **判定**：通过 / 失败（记录实际字符串与时间）。

### T2 · 私有

- **前置**：账号 A、B 都能看到同一个公开 skill；A 已入库并写说明。
- **步骤**：B 打开信息页、`skill show`、搜索结果；作者账号也看一遍。
- **期望**：三处都看不到 A 的文字；A 仍能看到。
- **判定**：通过 / 失败。

### T3 · 不产生版本

- **前置**：A 已入库一条 skill，记下 `skill_version` 行数与 `description` / `when_to_use` / `current_version_id`。
- **步骤**：写入说明。
- **期望**：行数与三个字段都不变；没有新草稿。
- **判定**：通过 / 失败（记录 before/after）。

### T4 · 入库闸

- **前置**：A 未入库一个公开 skill。
- **步骤**：`skmr skill my-when-to-use set <地址>`；控制台信息页尝试写。
- **期望**：两处都拒绝，提示先入库；库里已有的 skill 可以写。
- **判定**：通过 / 失败。

### T5 · S1 精确命中的排序位置

- **前置**：A 的库里有两条 skill：`name-hit`（名字含查询词）、`note-hit`（作者内容含查询词：`when_to_use` 或描述向量命中，A 给它写说明）。
- **步骤**：搜索该查询。
- **期望**：`name-hit` 在 `note-hit` 之前；`note-hit` 在只有作者内容命中的第三条之前；`skills search` 里 `note-hit` 的摘要末尾标「（命中你写的适用场景）」。
- **判定**：通过 / 失败（记录三条名次）。

### T6 · S2 语义召回

- **前置**：A 给一条 skill 写「循环审计时用它」；标注集里有查询「反复审查」。
- **步骤**：搜索「反复审查」。
- **期望**：该 skill 进前 5；若未进，记录相似度与阈值。
- **判定**：通过 / 失败。

### T7 · 公域不变

- **前置**：同一批 `scope=public` 查询，分别在写说明前、后跑。
- **步骤**：对比结果列表与名次。
- **期望**：完全一致；个人说明不参与公域。
- **判定**：通过 / 失败（逐条对比）。

### T8 · 清除

- **前置**：A 已写说明，且 T5 的查询能因它排序。
- **步骤**：`skmr skill my-when-to-use clear <地址>`；再搜同一查询。
- **期望**：不再因该说明进入结果或改变名次；`skill show` 不再显示。
- **判定**：通过 / 失败。

### T9 · 校验

- **前置**：A 已入库。
- **步骤**：写空文本；写 201 字；写一个不存在的地址。
- **期望**：空文本 `MY_WHEN_TO_USE_EMPTY`；201 字 `MY_WHEN_TO_USE_TOO_LONG`；不存在地址 404；控制台显示字段级错误。
- **判定**：通过 / 失败。

### T10 · 控制台与 CLI 一致

- **前置**：A 已入库。
- **步骤**：控制台写 → CLI 读；CLI 写 → 控制台读；控制台清 → CLI 读。
- **期望**：同一份数据；错误码与文案同义。
- **判定**：通过 / 失败。

### T11 · 事件可关联

- **前置**：打开事件记录；A 搜索并 invoke 一条。
- **步骤**：查 `search_id` 关联的 search / adopt / note 事件。
- **期望**：能串起来；说明原文不出现在任何日志字段。
- **判定**：通过 / 失败（附事件样例，脱敏）。

### T12 · 隐私口径

- **前置**：隐私政策文本。
- **步骤**：读 §五 的 DashScope 条目。
- **期望**：写明「个人适用场景的文本」；未写明则 S2 不上线。
- **判定**：通过 / 失败（引用原文）。

### T13 · 下载 / 上传作者字段 round-trip

- **前置**：一个作者字段齐全的 skill（name / title / description / when_to_use / compatibility + 一个未知 frontmatter 字段）。
- **步骤**：`skmr skill get --all` 到空目录；原样 `skmr skill submit <目录> --name <名称>`；`skill show` 与读回 frontmatter。
- **期望**：六个字段一字不差；未知 frontmatter 字段仍在；分类 / 公开标签不要求。
- **判定**：通过 / 失败（逐字段对比）。

### T14 · 个人说明不删除作者的那段

- **前置**：A 给一条 skill 写了我的说明；作者 `when_to_use` 与我的说明覆盖不同说法。
- **步骤**：构造一个只命中作者 `when_to_use` 的查询；再构造一个两段都命中的查询。
- **期望**：前者仍返回该 skill（S3）；后者按我的 S1/S2 排，作者那段不额外压过我的层级。
- **判定**：通过 / 失败（记录两条查询的名次与命中原因）。

### T15 · 控制台信息页的四个态

- **前置**：A 已入库一条 skill，未写过我的适用场景。
- **步骤**：打开信息页看空态 → 点「添加」看编辑态 → 点「取消」→ 再打开、输入「循环审计时用它」、保存 → 重新打开、点「清除」。
- **期望**：信息页依次是「适用场景」（skill 自带，一整块）与「我的适用场景」；空态说明写了之后检索怎么用它，并给出「添加」；能改这个 skill 的人（写入及以上）的空态多一句「上面那句随 skill 发布…改了不用出新版本」，只能调用 / 浏览的人没有这句；编辑态是卡片里的 `textarea`（不是弹窗），200 字上限、`0/200` 计数、空着时「保存」禁用；取消不改数据；保存后两块都在、作者那块在上；清除后回到空态、作者那块仍在。
- **判定**：通过 / 失败（附四个态截图）。

### T16 · CLI 命令面

- **前置**：A 已登录；库里有一条 `pdf-tools`；未写过我的适用场景。
- **步骤**：`skill show` 看现状；`my-when-to-use set` 用 `--text` 写入；再 `skill show`；用同一个词搜一次；`my-when-to-use clear`；再 `clear` 一次；再试空文本、201 个码点、未入库地址、带 `@版本` 的地址；`my-when-to-use --help` 确认写明作者那句在 `SKILL.md`、改它走版本。
- **期望**：`show` 在「适用场景」后多一行「我的适用场景」，没写时不打印；`set` 成功报告已记下，内容没变时说「本来就是这句」；`skills search` 的摘要用我的那段、末尾带「（命中你写的适用场景）」；`clear` 成功且第二次幂等退出 0；空文本 / 201 码点 / 未入库 / 带版本分别给出用法错误、`my_when_to_use_too_long`、403 的 `not_in_library`、用法错误；`--help` 不让人把作者的 `when_to_use` 也当成这条命令能改的。
- **判定**：通过 / 失败（附每个命令的实际输出与退出码）。

### T17 · 字段上限与契约回执

- **前置**：账号 A 能写一个 skill；准备 `SKILL.md` 夹具：`description` 恰 1024 / 1025 码点，作者的 `when_to_use` 恰 200 / 201 码点，含 emoji 与中英混排；一个带未知 frontmatter 字段的包；一个把 `when_to_use` 写成 `whenToUse` 的包。
- **步骤**：分别提交上述包；再看控制台与 CLI 的回执；用一条旧草稿（其存储的元数据超过新上限）尝试发布；给一个包补上未知字段与近似拼写键。
- **期望**：1024 / 200 通过，1025 / 201 拒绝；emoji 按 1 个码点计；回执带字段路径、问题码、当前值/上限、下一步，一次报完；旧草稿发布被拒，回执指向字段并给「回草稿改 `SKILL.md`」；未知字段保留且不报错，`whenToUse` 给告警但内容保留。
- **判定**：通过 / 失败（附每个夹具的回执与退出码）。

### T18 · 同步 / 导入拒绝并跳过

- **前置**：一个同步源里放两个包：一个合规，一个 `description` 超过 1024 或 `when_to_use` 超过 200。
- **步骤**：跑同步；查同步结果、日志与库里条目；确认被拒包没有被截断后入库。
- **期望**：合规包正常导入；不合规包被跳过，原文不被截断；日志/回执列出地址、来源、字段、问题；库里没有半截内容。
- **判定**：通过 / 失败（附日志与库条目）。

### T19 · 离开库保留、重新入库恢复

- **前置**：A 已入库一条 skill 并写了我的适用场景；记下原文；再准备一条只被 A 加入的公开 skill。
- **步骤**：`skill leave` 移出库；确认 `skills search` 不再因这句召回它；重新 `skill join`；看信息页/`skill show`；再移出库，用 `my-when-to-use clear <地址>` 清理；重新入库。
- **期望**：移出库时回执写明说明会保留；移出后不在库内检索里出现；重新入库后原文还在；离开库状态下 `clear` 仍成功（幂等）；清理后重新入库是空的；账号删除或 skill 硬删除时行级联消失。
- **判定**：通过 / 失败（附每一步的库状态、检索结果与原文）。

### T20 · 离线评测：个人层写前 / 写后

- **前置**：独立测试库；把 v19 的 33 个 fixture skill 写进账号 A 的库并建立作者向量；真实 DashScope `text-embedding-v4`（1024 维）。查询两套：
  - v19 `labeled.tsv` 的 99 条，用来查库内检索相对历史公域集的回归；
  - v59 `v59-personal.tsv` 的 24 条，分 `personal-exact`（12 条，查询含 ≥3 汉字连续说法）与 `personal-rewrite`（12 条，无 ≥3 汉字 / ≥5 拉丁字符连续重合，应走 S2）。
- **步骤**：先跑两套基线；给 12 条 skill 各写一句私人说法；同一批查询再跑一次；比 v19 的 Recall@10 与个人两组的 Top-1、Recall@5、MRR；再用一条查询确认公域卡片没有 `my_when_to_use`。
- **期望**：个人触发组 Top-1 ≥ 90%；改写组 Recall@5 比基线 +20 个百分点；v19 库内回归集不比基线退步；公域不出现私人句子。
- **判定**：通过 / 失败（附逐条名次与三指标）。数字口径：Top-1 = 期望 skill 排第 1 的比例；Recall@5 = 进前 5 的比例；MRR = 名次倒数的均值（未命中记 0）。

## 环境与数据

- **环境怎么造**：按 `.dev-sop/profile.md` 的本地一库一栈；服务端与 CLI 用当前分支构建；控制台跑 `npm test` 与 `npm run type-check`。
- **数据**：两个账号 A / B；A 的库里至少三条 skill（名字命中、描述命中、说明命中各一条）。离线评测用 v19 的 33 个 fixture skill、99 条回归查询，加 v59 的 24 条个人查询（12 exact / 12 rewrite），数据在 `src/test/resources/retrieval/`。
- **清理**：测试库用后重建；A / B 的说明随账号或库条目级联删除；不写生产。

## 结果

实现完成后填写；每条附证据。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `MyWhenToUseApiIT.writingReadingReplacingAndClearingNeverTouchTheSkill`；`tests/skills.test.tsx`「adds, edits and clears…」 |
| T2 | 通过 | `MyWhenToUseApiIT.anotherAccountCannotSeeTheSentence` |
| T3 | 通过 | `MyWhenToUseApiIT.writingReadingReplacingAndClearingNeverTouchTheSkill`（版本 / 草稿行数不变） |
| T4 | 通过 | `MyWhenToUseApiIT.aVisibleSkillOutsideTheLibraryIsA403WithTheJoinStep` |
| T5 | 通过 | `PersonalScenarioSearchIT.nameThenPersonalExactThenTheAuthorsContent` |
| T6 | 通过 | `PersonalScenarioSearchIT.aRewriteIsS2AndStillBeatsTheAuthorsVector` |
| T7 | 通过 | `PersonalScenarioSearchIT.publicResultsDoNotGainThePrivateSentence`；`WebDiscoverSemanticIT` |
| T8 | 通过 | `PersonalScenarioSearchIT.clearingTheSentenceRemovesItFromRanking` |
| T9 | 通过 | `MyWhenToUseApiIT.theLimitIsTwoHundredUnicodeCodePoints`；`my_when_to_use_test.go` |
| T10 | 通过 | 控制台 `tests/skills.test.tsx`；CLI `my_when_to_use_test.go` |
| T11 | 未执行 | P3 依赖 `TODO.md`「个人调用记录（只看自己）」；本版未建事件管道 |
| T12 | 通过 | `skillmaster-site/tests/legal-page.test.tsx`（版本 1.1）；隐私政策 §五含「我的适用场景」 |
| T13 | 通过 | `read_write_contract_test.go` 的 `TestGetAllWritesTheWholeVersion`；`SkillDocumentTest` |
| T14 | 通过 | `PersonalScenarioSearchIT.aConfidentPersonalRewriteOutranksTheAuthorsExactSentence`、`theAuthorsExactSentenceBeatsGenericSemanticEvidence` |
| T15 | 通过 | `tests/skills.test.tsx`（空态 / 编辑 / 清除 / 字段错误） |
| T16 | 通过 | `my_when_to_use_test.go`（set / clear / 空文本 / 版本 pin / 搜索标记 / 零结果提示） |
| T17 | 通过 | `SkillDocumentTest`；`MyWhenToUseApiIT.theLimitIsTwoHundredUnicodeCodePoints`；`tests/errors.test.ts` |
| T18 | 通过 | `SyncPipelineIT.aSkillMdThatBreaksTheTextFieldContractFailsOnlyItsOwnEntry`；`SkillDocumentTest` |
| T19 | 通过 | `MyWhenToUseApiIT.leavingTheLibraryKeepsTheSentenceAndClearingWorksWithoutIt` |
| T20 | 通过 | 数字与口径见 [搜索评测基线](../../research/search-evaluation-baseline.md) |

### T20 的实测数字

通过。数据集、跑法与「写说明前 / 写说明后」数字见 [搜索评测基线](../../research/search-evaluation-baseline.md)。后续搜索优化以该页「写说明后」一列为对照基线。

## 已知问题

- **T11 未执行**：事件管道（`search_id` / adopt / exclude / note）依赖「个人调用记录」，后者未开版；本版不建第二套管道。状态：未执行（依赖未就绪）。
- **休眠条目的说明在控制台不可见**：`GET /my-when-to-use` 在库条目休眠时仍可读，`skill show` 现在也读；但控制台信息页按「库条目有效」才画这一块，休眠时看不到。这与「控制台只在库里画」的决定一致，三端差异留给后续设计态。状态：按决定保留。
- **近似键告警只进服务端日志**：`whenToUse` / `when-to-use` 只会让作者在日志里看到，响应与 CLI / 控制台都不带 warnings。状态：本版按此实现，回执里的结构化 warnings 留后续。
