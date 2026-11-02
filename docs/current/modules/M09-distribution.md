# M09 · 分发

> **状态**：已实现 · 拥有表与对外发布类型见 [`system.md`](../system.md) 的对应行（源是 `ModuleMap`）

## 职责

消费面与作者面的读取：详情、文件清单（零内容）、只给 L1 的元数据、正文 L2、单文件 L3、两版差异。它把 M05 / M06 / M07 / M12 / M04 的读取拼成一个回答。

## 边界

- **不做**：写任何东西。
- **不拥有表**：它是读模型 / 组合层，不做数据归属。
- **扇出最大**：`distribution → blob`、`ingest`、`namespace`、`skill`、`version` 五条边。这是它的职责，不是缺陷；但它是模块文档最需要说明的一个。

## 对外契约

`SkillDistributionService` 及其返回的 `SkillDetail` / `SkillDiff` 目前被 `api` 直接 import。v36 P4 已评估：按 [TD Q3](../../versions/v36-architecture-refactor/technical-design.md#开放问题) 推迟到下一版，届时把这些类型收回用例层，让 `api` 只依赖 usecase。

## 为什么这样切

读取的组合与写入的归属是两件事。把「详情页要什么」放进 M07 会让版本模块同时拥有存储与展示；单独的读模型让展示形状可以随界面变化，而不动版本的状态机。
