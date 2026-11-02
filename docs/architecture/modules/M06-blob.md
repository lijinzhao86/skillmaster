# M06 · 内容寻址存储

> **状态**：已实现 · **拥有表**：`blob`、`blob_content`、`blob_deletion` · **对外的发布类型**：`BlobStore`、`BlobSettings`

## 职责

按 sha256 存字节、按 sha256 取字节，天然去重。它只知道「一段字节 + 一个摘要」，不知道这段字节属于哪个 skill、哪个版本。

## 边界

- **不做**：版本、文件清单、引用计数——那是 M07。
- **不做**：回收策略——M07 算出「仍被引用的摘要集合」，M06 只负责删掉不在集合里的 blob（`deleteUnreferenced`）。
- **不做**：内容语义（是不是 SKILL.md、是不是二进制）——调用方知道，M06 不知道。
- **按大小分流**：`RoutingBlobStore` 把大于阈值（默认 1 MiB）的字节放进私有 OSS，其余留在 `blob_content`；判断只看 `size`，不看类型（[ADR 0130](../../decisions/0130-large-blobs-live-in-object-storage.md)）。读路径按 `blob.storage` 路由，对调用方透明；`open` 给流式读取，`get` 只给能整份放进内存的调用方。
- **OSS 删除是最终一致的**：删 `blob` 行的同一个事务写 `blob_deletion`，由 `drainDeletions` 在事务外删对象并清 outbox；失败留待下一次清扫。

## 对外契约

`BlobStore` 是唯一的字节入口：存、取、删未引用。M07 的 `SkillVersionService` 只与 `Manifest`（摘要与大小）打交道，字节始终经 M06。

## 为什么这样切

内容寻址是存储的边界，不是版本的边界。把它并进 M07 会让「取一个文件」也要经过版本状态机，而 blob 的生命周期比版本长（多个版本可以引用同一段字节）。
