# v59-personal-scenario · 技术设计

> **最后更新**：2026-11-10
> **状态**：设计稿（待第 3 关填充）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文只链接、不复述理由。
> **算法底稿**：[`docs/research/search-ranking-and-personal-triggers.md`](../../research/search-ranking-and-personal-triggers.md)。

## 架构

个人说明是 **M12 的数据**（和库、标签同一层），不进入版本或内容；M08 只出检索谓词与排序，M13 只负责句子向量。控制台与 CLI 都走 API 平面，没有第二条写路径。

```
控制台信息页 ─┐
CLI my-when-to-use ─┼─► API：/my-when-to-use（写 / 读 / 清）
Agent 规则块  ─┘            │
                            ▼
                     M12：my_skill_when_to_use（本人 × skill）
                            │
              ┌─────────────┴─────────────┐
              ▼                           ▼
      M08：库内候选与排序            M13：说明按句切、向量
      （S0–S4 证据分级）            （S2 语义命中）
```

- **只在库内生效**：公域搜索不读 `my_skill_when_to_use`；scope=all 只把个人层加到库内那部分。
- **不产生版本**：写说明不改 `skill` 的 `description` / `when_to_use` / `current_version_id`，也不建 `skill_version`。
- **一句话的边界**：M12 拥有说明，M08 决定排序，M13 只把句子变成向量；三者通过现有接缝，不新增跨模块类型。
- **向量永远跟着当前那句话**：说明先提交，provider 调用在事务外；embedding 返回后再读一次说明，文本变了就删掉这组向量而不是写入（写入后再核一次）。向量侧任何失败（provider / 数据库）只记日志并清理，不把已提交的说明写请求变成 500。
- **两段的召回语义**：作者的 `skill.when_to_use` 仍在 S3，不因个人层被删除；我的 `my_skill_when_to_use.when_to_use` 在 S1/S2。两段都命中时按 S1/S2 排；只有作者命中时仍召回。这是「覆盖 = 优先级」，不是删除。库内作者向量本身就是 S3（名字 / 标题 / 描述 / 作者 `when_to_use` 的合成文本）；公域或 scope=all 的通用语义是 S4。
- **只走语义检索路径**：个人层（含不需要 provider 的 S1）只在 `semantic=true` 的库内排序里生效；`semantic=false` 或 embedding 关闭时按原关键词排序，结果不变。CLI 的 `skills search` 默认 `semantic=true`，降级是显式选择或 provider 故障。
- **近似键告警的落点**：写入与同步时进服务端日志（字段名 + 被保留的键），本版响应里不带 warnings；要让作者看见，后续版本在回执里加结构化 warnings。

## 数据模型

```sql
-- 我的适用场景：本人 × skill，复用作者 `when_to_use` 的字段名。
-- 为什么不做在 skill_library_join 上：库是「我创建的 + 共享给我的 + 我入库的」，
-- 那张表只覆盖第三种；这份要覆盖三种来源，所以单独一张表。
CREATE TABLE my_skill_when_to_use (
  user_id    TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  skill_id   TEXT NOT NULL REFERENCES skill(id)    ON DELETE CASCADE,
  when_to_use TEXT NOT NULL,             -- 纯文本，≤ 200 个 Unicode 码点；不存 Markdown
  updated_at TEXT NOT NULL,              -- RFC3339 UTC，与全库时间写法一致
  PRIMARY KEY (user_id, skill_id)
);

-- S2 用：每条说明按句切，最多 5 句，每句一条向量。
-- 不放进 skill_embedding：那条向量是所有人的作者文本，这份是本人私有的。
CREATE TABLE my_skill_when_to_use_embedding (
  user_id    TEXT NOT NULL,
  skill_id   TEXT NOT NULL,
  sentence_no INTEGER NOT NULL,
  model      TEXT NOT NULL,
  embedding  vector(1024) NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (user_id, skill_id, sentence_no, model),
  FOREIGN KEY (user_id, skill_id) REFERENCES my_skill_when_to_use (user_id, skill_id)
    ON DELETE CASCADE
);
```

**约束与不写的东西**：

- 主键 `(user_id, skill_id)`：一人一条，天然去重；删除账号或 skill 时级联。
- **不写「必须在库里」的外键**：库的 membership 是 `IDENTIFIED` 派生出来的（created / shared / joined），没有一张表能当外键；由 use case 在写入前用现有的目录接缝校验。
- 索引：主键已覆盖按人读；S2 查询按 `(user_id, model)` 扫，量小，暂不加额外索引。
- **离开库 / 失去访问时不删（2026-11-11 用户定）**：这张表只依赖 `user × skill`，不依赖库条目；移出库、共享被撤销、skill 下线都不动它，重新入库或恢复共享后还在。用户操作错了能拿回来。若 skill 被硬删除，外键级联；账号删除同样级联。**清理路径**：`DELETE` 不要求库 membership（否则离开后用户清不掉自己的数据）；`GET` / `PUT` 仍要求在库里。

## 接口

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `GET` | `/api/v1/skills/{ns}/{name}/my-when-to-use` | 读本人的适用场景；没写过时 200 + `when_to_use: null` | 本人凭据；skill 必须在本人库里 |
| `PUT` | 同上 | 写 / 覆盖；body `{ "when_to_use": "..." }` | 同上 |
| `DELETE` | 同上 | 清除；**不要求库 membership** | 本人凭据；按地址删本人那一行；没有这一行 / 地址看不见时也 200 `{"changed": false}`，不泄露存在性 |

**错误形状**（沿用现有 API）：

| 情况 | 状态码 / 错误码 |
|---|---|
| `GET` / `PUT` 时 skill 可见但不在本人库里 | 403 `forbidden`，`details[0].issue=not_in_library`，消息里带 `skill join` 的下一步 |
| skill 对本人不可见 | 404（与所有私有读一致） |
| `PUT` 时库条目休眠 | 409 `library_entry_dormant` |
| 文本为空 | 400 `MY_WHEN_TO_USE_EMPTY` |
| 超过 200 个 Unicode 码点 | 400 `MY_WHEN_TO_USE_TOO_LONG`，`details` 带 `actual_code_points` / `limit_code_points` |
| 未认证 | 401（现有） |

`DELETE` 不要求在库，也没有「没有这一行」的错误：返回 `200 {"changed": false}`，与标签层的可空写一致。

**搜索响应**：`SearchResponse.Card` 增加两个只对本人返回的字段：

- `myWhenToUse`：本人写过的适用场景，没写过为 null；
- `myWhenToUseMatched`：本次排序是否因它命中（仅用于 CLI 展示「（命中你写的适用场景）」，不参与算分；控制台本版没有库内语义搜索结果行，不消费它）。

`skill show` 复用 `GET /my-when-to-use`，或直接读 `SkillMetaResponse` 的新字段；二选一在实现时定。作者写的 `when_to_use` 仍在现有字段里，两者分开显示（「适用场景」/「我的适用场景」）。

### CLI 交互

**命令面**（[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)：控制台能做的写 / 清，CLI 都能做；两者都可逆、只影响本人，**不需要 `--yes`**）：

| 命令 | 作用 | 形状 |
|---|---|---|
| `skmr skill show <地址>` | 读；已有命令加一行 | 在「适用场景」之后打印「我的适用场景」，没写就不打印；地址不带版本 |
| `skmr skill my-when-to-use set <地址> --text <文字>` | 写 / 覆盖 | `--text -` 从标准输入读；不给 `--text` 是用法错误——**不自动读 stdin**，避免 agent 调用时挂住 |
| `skmr skill my-when-to-use clear <地址>` | 清除；**不要求在库** | 幂等：本来没写也退出 0 |

'**鉴权**：`set` 要求在库；`clear` 不要求（离开库 / 失去访问后仍能清掉自己的数据，按地址删本人那一行）；`skill show` 里的读仍在库内。`skill leave` 的回执与控制台的移除确认都写明：**我的标签与我的适用场景会保留，重新入库后还在**。

**文本口径**：纯文本；去掉首尾空白，内部连续空白（含换行）折成一个空格；按处理后的 **Unicode 码点**计数，上限 200（一个汉字、一个英文字母、一个标点都算 1）。空串不许 `set`——要清用 `clear`。

**输出**：

| 命令 / 情况 | 输出 |
|---|---|
| `set` 成功 | `已记下 <地址> 的我的适用场景。` |
| `set` 内容没变 | `本来就是这句，没有需要再做的：<地址>。` |
| `clear` 成功 | `已清除 <地址> 的我的适用场景。` |
| `clear` 本来没有 | `本来就没有写，没有需要再做的：<地址>。` |
| `skills search` 库内命中个人层 | 摘要用生效的那段（写过用我的），末尾加 `（命中你写的适用场景）`；公域结果不加 |
| `skills search` 零结果（默认 `scope=library`） | 现有提示之后加一行：`（如果已有目标 skill、只是说法对不上：先问用户，再 skmr skill my-when-to-use set <地址> --text <说法>；只对库里的 skill 有效。）` |

**错误映射**：不在库里 → `skill_not_in_library`，下一步 `skmr skill join <地址>`；空文本 → 用法错误；超过 200 码点 → `my_when_to_use_too_long`（带当前长度与上限，下一步「缩短后再试」）；地址带 `@版本` → 用法错误「我的适用场景是按 skill 的，不带版本」。

**作者那句怎么改**：作者的 `when_to_use` 是 skill 内容，不是个人层；CLI 不为它开 `set`。改它走 `skmr draft create` → `skmr skill edit <地址>@<草稿版本码> SKILL.md`（改 `metadata.when_to_use`）→ `skmr version publish`。`my-when-to-use` 的 summary 与 `Next` 要写明「这条只写你的；作者那句在 SKILL.md，改它走版本」，避免创作者把两条路混在一起。

**明确不做**：`--yes`（不是高风险）；本地搜索状态（CLI 保持无状态）；`search_id` 的跨命令传递——事件关联由 P3 的「个人调用记录」管道定，CLI 本期不引入跨命令状态。

## 字段上限与契约

**三个上限一次收口**（详见 [ADR 0181](../../decisions/0181-text-limits-use-unicode-code-points.md)）：

| 字段 | 位置 | 必填 | 上限 | 空值 |
|---|---|---|---|---|
| `description` | `SKILL.md` frontmatter 顶层 | 是 | **1024 个 Unicode 码点** | 拒绝 |
| `when_to_use`（作者的） | `metadata.when_to_use`，顶层回退 | 否 | **200 个 Unicode 码点** | 不写 = 没有 |
| 我的适用场景 | 服务端私人表（不走 `SKILL.md`） | 否 | **200 个 Unicode 码点** | 空值拒绝；清除走 `DELETE` |

**单位是码点，不是字节也不是 UTF-16 单元**：一个汉字、一个英文字母、一个标点都算 1；UTF-8 字节会把中文按 3 倍压缩，UTF-16 的 `length()` 会把 emoji 算成 2。Java 用 `codePointCount`，Go 用 `utf8.RuneCountInString`，控制台按码点截断而不是 `maxlength`。

**作者元数据只有 `SKILL.md` frontmatter 一个事实源。** `SkillDocument.parse` 是唯一契约函数，覆盖四条写入路径：上传 / `skill submit`、新建 skill（模板生成后）、草稿里写根 `SKILL.md`、同步 / 导入。**发布时再校验一次**：草稿可能写在旧规则下，规则升级后不能让它在发布时绕过；被拒时下一步是回草稿改 `SKILL.md`。

**回执是结构化的，不是一句「格式不对」**：每条带字段路径（如 `frontmatter.metadata.when_to_use`）、问题码（`required_field_missing` / `field_not_text` / `too_long` / `name_too_long`）、当前值与上限、下一步；一次报完所有问题。**未知字段保留**（生态扩展不拒）；**近似拼错告警**（`whenToUse` / `when-to-use`）。现有行不重写，只在新写入与发布时生效；上线前用只读 SQL 核一遍现有最长值。

**同步 / 导入**：不合规的包**拒绝并跳过**，不静默截断作者原文；拒绝原因进日志。把被拒条目汇总发运营邮箱是后续待办（[`TODO.md`](../../current/TODO.md)），本版不做。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P0** | 表 + CRUD 接口 + CLI `skill my-when-to-use` + 控制台信息页显示 / 编辑 / 清除；**同时收口三个字段上限与契约（写入 / 发布 / 同步）**；不参与检索 | PRD 验收 #1–#4、#9、#10、#15–#18 |
| **P1** | S1 精确：最长公共子串 ≥ 3 汉字 / ≥ 5 拉丁字符；个人层定级在 S0 之后、S3 之前 | PRD 验收 #5 |
| **P2** | S2 语义：说明按句切、向量、阈值 + margin；隐私政策补 DashScope 条目 | PRD 验收 #6、#12 |
| **P3** | 事件：`search_id` / adopt / exclude / note；复用 v58 的「个人调用记录」管道；离线评测集与指标 | PRD 验收 #7、#8、#11；§指标（本 PR 未执行） |

**每期明确不做**：P0 不做检索；P1 不做语义；P2 不做训练；P3 不训练，只记录。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 上限什么时候需要放宽？ | v59 已定：描述 1024、作者 `when_to_use` 200、我的 200，全部码点；先按 SKILL.md 规范与标签说明的同档值 | 看首批实际长度分布；英文用户接近上限或超限比例高，再讨论加权/放宽，并先改 ADR |
| Q2 | S2 的阈值与 margin 取多少？ | 高阈值 + `top1 - top2 ≥ margin`；具体值不拍 | 20–50 条人工标注（正例 + 近义负例）扫 precision/recall |
| Q3 | 说明向量在写入时算还是查询时算？ | 写入时算、按句存；查询只做点积 | 写入时算一次，避免查询时按条调 provider |
| Q4 | scope=all 怎么合并库内与公域？ | 库内带个人层，公域不带；两路用 RRF | 用同一评测集看合并后的 Top-1 是否退步 |
| Q5 | 事件管道复用「个人调用记录」还是本版自建？ | 复用 v58「个人调用记录」的 query 原文与 invoke 记录；本 PR 不建第二套 | 后续单独实现时与 v58 的表结构对齐 |
| Q6 | 排序游标的 `orderingId` 要不要改？ | 库内语义路径无游标；关键词路径若加个人分，`orderingId` 必须带上权重 | 实现时按 `RelevanceWeights.orderingId` 的现有规则处理 |
| Q7 | 界面最终叫「我的适用场景」还是别的？ | 「我的适用场景」，字段名复用 `when_to_use`；不新造 `scenario`。CLI 用 `my-when-to-use`，与 `skills my-tags` 同一命名法 | 第 2 关已定；`voice-spec.md` 与 CLI help 同步 |
| Q8 | 离开库后，用户怎么找到并清理看不见的说明？ | 离开不删；CLI `my-when-to-use clear` 不要求在库，按地址可清；控制台暂时只能在库里看到 | 先看 P0 的真实使用；若孤儿多，再加「我的说明」列表页 |

## 附录

### 附录 A · 与现有代码的关系

| 现有 | 关系 |
|---|---|
| `SkillLibraryService` / `LibraryRepository` | 保留；库的 membership 判定复用 `IDENTIFIED` |
| `SkillSearchService` / `SkillCatalogRepository` | 保留；库内排序新增证据分级与个人说明谓词 |
| `SemanticSearchUseCase` / `RetrievalService` | 保留；S2 的说明向量走 M13 的新读取，不改作者向量 |
| `SqlFilters.text` | 保留；个人说明作为库内额外字段加入，不改公域调用方 |
| `SkillEmbeddingTextSource` | **不改**：作者向量不装个人文字 |

### 附录 B · 来源与出处

- 算法与样本口径：[`docs/research/search-ranking-and-personal-triggers.md`](../../research/search-ranking-and-personal-triggers.md)
- 现有检索边界：[`M08`](../../current/modules/M08-search.md)、[`M13`](../../current/modules/M13-retrieval.md)
- 控制台↔CLI：[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)

### 附录 C · 已测量的数据

| 指标 | 口径（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| 库内检索基线 | 未测；第 4 关用评测集量 | — |
| S2 阈值 | 未测；第 4 关扫 | — |
