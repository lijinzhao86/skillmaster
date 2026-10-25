# v19-skills-retrieval

> **状态**：进行中，四份文档已锁定，服务端与 CLI 已实现，评测与阈值未跑；取代 ADR 0084 对向量检索否决的新 ADR 尚未落笔。

## 目标

让「库里有没有一个 skill 能做这件事」不再靠逐个读描述来回答：私域与公域用**同一套语义检索**，范围只由可见性决定。

细节（范围、验收、不做）见 [`prd.md`](prd.md)；数据模型与检索链见 [`technical-design.md`](technical-design.md)；用例与结果见 [`test-plan.md`](test-plan.md)。

## 这一版明确不做什么

见 [`prd.md`](prd.md) §范围 › 做 / 不做——那里是唯一的权威，这里不复述。

## 检索模块的边界（独立迭代的前提）

- **一个模块**：`modules/retrieval`，拥有向量表与 embedding 调用；对外只有 `search(scope, query, filters, topN)`。
- **不判断可见性**：候选集由 M12 按 scope 与过滤条件给出，模块只在这个集合里做近邻查询。
- **不拥有描述文本**：经 `EmbeddingTextSource` 从 M12 的投影读。
- **可替换**：供应商与模型名封在模块内部，模型名进向量表的主键——换模型是「新模型名算一遍、切读、删旧行」，详见 [`technical-design.md`](technical-design.md) §数据模型。

## 验收标准

见 [`prd.md`](prd.md) §验收与指标。

## 与目标态的关系

`docs/target/` 描述的是「agent 按需取用、不关心库有多大」的形态；本版补的是它的**入口那一半**——找到，而不是取用。取用（invoke / files / read）已经能跑，缺的是「我怎么知道有它」。本版不改变目标态，只把「检索」这条路从关键词扩成语义。
