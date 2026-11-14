# 0197 · 生产表只存最终标签与预期结果，抽取中间态进候选表

- **状态**：已采纳
- **日期**：2026-11-14
- **所属版本**：后续打标版（未开版；v69 只做导入平切，不含 V53 与候选表）
- **依赖**：[0193](0193-public-labels-are-extensible-claims.md)（标签是主张）、[0194](0194-public-labels-are-agent-produced-and-sampling-audited.md)（Agent 打标与抽样）、[0196](0196-label-extraction-reads-public-text-only.md)（只读公域文本）
- **取代**：0193 §决定 1 的 `skill_label` 字段形状与 §决定 7 的版本留痕方式；0194 §决定 3 的三档结果落点与 §决定 8 的「随版本重算」存储部分。原则不变，只改「存在哪」。
- **实现注记**：0193 / 0194 的文件头标记的是被 0195 取代的部分；本条对它们存储形状的取代自下一版开版时生效。

## 背景

P0 为了先把字段、界面与筛选交付，把两处中间信息放在了生产表上：

- `skill` 行有 `outcome_source` / `outcome_confidence` / `outcome_evidence` / `outcome_model` / `outcome_model_version` / `outcome_evaluated_version_id` / `outcome_updated_at` 七列；
- `skill_label` 每行带 `source` / `confidence` / `evidence` / `model` / `model_version` / `vocabulary_version` / `review_state` / `evaluated_version_id`。

这些列对用户不可见，公开接口会过滤掉未确认的行；但它们**物理上在生产表里**。产品负责人的要求是：生产表只存用户能看到的最终结果，自动抽取与人工审核的中间信息不要放进来。三种做法：

| | 做法 | 问题 |
|---|---|---|
| a | 保持现状，只在接口过滤 | 表不干净；影子期的数据也已经在生产表里 |
| b | **最终结果表 + 候选表两层** | 多两张内部表与一次提升动作；换来干净的生产表与更简单的读模型 |
| c | 连 `outcome` 也搬出 `skill`，单独一张最终表 | 读模型多一次 join；`outcome` 是用户可见的一句话，留在 `skill` 行上并没有弄脏它 |

## 决定

1. **`skill` 只留 `outcome` 一列**。七列来源 / 置信度 / 证据 / 模型 / 评估版本全部搬进 `skill_outcome_candidate`。
2. **`skill_label` 只留「哪条 skill 有哪个标签」**：`(skill_id, definition_id)` 两列，复合主键。`source` / `confidence` / `evidence` / `model` / `review_state` / `evaluated_version_id` 全部搬进 `label_candidate`。
3. **候选表是审计与审核层，放在运营服务的独立库**：每次抽取的每个候选（含被拒的）写 `label_candidate` / `skill_outcome_candidate`，带置信度、证据、模型、提示词与词表版本、决定（`auto` / `pending` / `rejected` / `confirmed` / `removed` / `stale`）与是否已提升。
4. **提升是显式动作**：高置信且证据通过、影子期已过，才由 server 的 `/inner/v1` 接口把候选写入在线最终表；影子期一律不提升。读模型只读最终表，不再按 `review_state` / `source` / 评估版本过滤。
5. **新版本到来**：旧候选标 `stale`；最终表里的 Agent 行与 `outcome` 清掉，运营 `confirmed` 的行保留；抽取任务重跑后再提升。运营覆盖文件是运营行的权威，重跑时重新应用。
6. **被拒的候选留在候选表**（带原因），不提升。它们用于提示词迭代与金标分析，不进生产表。

## 理由

- **为什么生产表要干净。** 生产表是用户结果的权威；中间态混在里面，读模型就要背着一套 `review_state` / `source` / 版本过滤，任何一个新读路径漏掉一条就会把未确认的值发给用户。两层之后，读模型只需要读最终表，过滤规则从「每条查询都要记得」变成「表里本来就没有」。
- **为什么影子期变简单。** 影子期不再需要读接口开关：不提升，生产表就是空的。开闸是提升开关加一次回填提升，不需要改读代码，也不需要重启后「忘了关」。
- **为什么保留候选表而不是直接丢掉。** 审核、证据、模型版本、重跑历史、金标集与周抽样都要读它们；没有候选表，这些信息只能进日志，无法查询，也无法回答「这条为什么被拒」。
- **为什么 `outcome` 留在 `skill`。** 它是用户可见的一句话，不是中间态；单开一张表会让每次信息页与卡片多一次 join，收益只是「更纯」，不值。
- **为什么被拒候选也留。** 拒绝原因（证据找不到、置信度低、词表外）是改提示词的燃料；只留通过的值，等于把最有用的负样本扔了。

被否掉的：**只在接口过滤**（生产表仍含中间态，影子期数据已在表里）；**把 `outcome` 也搬出 `skill`**（多一次 join，换不到用户可见的收益）；**丢掉被拒候选**（失去审核与迭代依据）；**保留 `review_state` 作为最终表的一列**（最终表只放结果，状态属于候选层）。

## 后果

- **运营库新增候选表**：`label_candidate`、`skill_outcome_candidate`、`skill_category_candidate`；由 ops 自己的迁移创建，在线库不建这些表。
- **在线库做一次破坏性迁移**：`skill` 删 7 列、`skill_label` 删 12 列并改为复合主键、`label_extraction_run` / `label_audit_sample` 从在线库移除。P0 生产库没有标签 / 抽样数据，删列安全；P0 的读模型、测试与夹具要同步改。
- **写入走内部 API**：ops 只把最终结果 `PUT /inner/v1/skills/{ns}/{name}/labels` 给 server；server 写在线最终表，ops 不拿在线库写权限。
- **读模型简化**：`LabelRepository` / `SqlFilters` 不再判断 `review_state` / `source` / `evaluated_version_id`；API 与 CLI 的输出形状不变。
- **影子期与开闸**：`shadow=true` 时只写候选、不提升；关影子的动作是一次 `promoteBacklog()`。
- **新版本生命周期**：发布路径清理最终表的 Agent 行与 `outcome`，保留运营确认行；抽取任务重新提升。
- **候选保留期**：候选表随 `skill` / `label_extraction_run` 级联删除；暂不设单独保留期，量级远小于正文。
- **旧 ADR 的状态**：0193 / 0194 的正文不改，文件头标出被本条取代的存储形状部分。
