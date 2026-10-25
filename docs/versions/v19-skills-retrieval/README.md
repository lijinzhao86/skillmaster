# v19-skills-retrieval

> **状态**：实现完成，**未上线**。服务端、CLI、控制台发现页都已实现；评测已跑（`Recall@10` 语义式 0.909（n=66）、名字式 1.000（n=33）），阈值按分数分布定为 0.40；[ADR 0095](../../decisions/0095-semantic-retrieval-is-accepted.md) 已取代 0084 对向量检索的否决。上线前还差三件运维确认：生产 RDS 的 `CREATE EXTENSION` 权限、pgvector 版本、以及 CI 里的一个 repository variable（`SKILLMASTER_RETRIEVAL_ENABLED`）加一个 secret（`DASHSCOPE_API_KEY`）。

## 目标

让「库里有没有一个 skill 能做这件事」不再靠逐个读描述来回答：私域与公域用**同一套语义检索**，范围只由可见性决定。

细节（范围、验收、不做）见 [`prd.md`](prd.md)；数据模型与检索链见 [`technical-design.md`](technical-design.md)；用例与结果见 [`test-plan.md`](test-plan.md)。

## 这一版明确不做什么

见 [`prd.md`](prd.md) §范围 › 做 / 不做——那里是唯一的权威，这里不复述。

## 检索模块的边界（独立迭代的前提）

- **一个模块**：`modules/retrieval`，拥有向量表与 embedding 调用；对外只有 `nearest(q, limit)` 与 `nearestWithin(q, ids, limit)`。
  带 `scope` 的 `search(scope, q, filters, limit)` 在 `SemanticSearchUseCase`——可见性归 M12，本模块不碰。
- **不判断可见性**：≤ 20000 条时候选集由 M12 枚举成 id 清单，模块只在这个集合里做近邻查询；
  超过时模块取回 `limit × 20` 名，再由 M12 过滤（近似，见 TD §检索链）。两种走法都不让本模块知道「谁看得见」。
- **不拥有描述文本**：经 `EmbeddingTextSource` 从 M12 的投影读。
- **可替换**：供应商与模型名封在模块内部，模型名进向量表的主键——换模型是「新模型名算一遍、切读、删旧行」，详见 [`technical-design.md`](technical-design.md) §数据模型。

## 验收标准

见 [`prd.md`](prd.md) §验收与指标。

## 与目标态的关系

`docs/target/` 描述的是「agent 按需取用、不关心库有多大」的形态；本版补的是它的**入口那一半**——找到，而不是取用。取用（invoke / files / read）已经能跑，缺的是「我怎么知道有它」。本版不改变目标态，只把「检索」这条路从关键词扩成语义。
