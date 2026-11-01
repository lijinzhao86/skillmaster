# M13 · 语义检索

> **状态**：已实现 · **拥有表**：`skill_embedding` · **对外的发布类型**：`EmbeddingTextSource`

## 职责

把 skill 的文本向量化（百炼 `text-embedding-v4`，1024 维），在给定候选集里做近邻查询，并在发布事务内写向量。

## 边界

- **不判可见性**：候选集由 M12 给；M13 不读 `skill` / `skill_grant`。
- **不拥有文本**：文本经 `EmbeddingTextSource` 从 M12 的投影读。
- **不做**：字面搜索与排序——那是 M08。
- **不做**：向量缺失时的降级——由用例层决定是否退回字面搜索。

## 对外契约

`EmbeddingTextSource` 由 M13 声明（`modules/retrieval`）、M12 实现（`internal/SkillEmbeddingTextSource`）；M13 的其余类型是向量化与查询的内部实现。

## 为什么这样切

向量是「另一种索引」，不是另一份状态。它只拥有 `skill_embedding`，不复制 skill 的可见性或生命周期；这样 skill 上下线、改私有、删掉时，不需要在两张表之间保持一致。
