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

## 缓存与校验

在同一个 `DiffService` 实例内（`DiffLimits` 构造期固定），一次比较的答案由**两份清单**加上 `file` / `cursor` 决定；时间、作者、可见性这些版本行上的其他字段都不进答案，`from` / `to` 只是被回显的地址字符串。

缓存与校验器都按这条建，**不只按版本地址**建 —— 草稿是就地编辑的（[ADR 0064](../../decisions/0064-a-draft-may-change-a-published-version-may-not.md)），它的地址是行 id（[ADR 0063](../../decisions/0063-a-draft-is-addressed-by-a-short-code.md)），改多少次都不变；地址仍要进键，因为答案里带着 `from` / `to` 两个标签。

| 东西 | 归谁 | 按什么失效 |
|---|---|---|
| 重算结果的进程内 LRU | `DiffService`，按条数有界 | 键是每侧的「地址 + 清单 digest」，外加 `file` / `cursor` |
| 响应的强校验器（`ETag`） | `api` 层，对将要发出去的响应字节求哈希 | 答案变了就换一个 —— 包括草稿被就地编辑 |

**同一条对版本地址的请求，内容变了答案就必须重算**。凡是想省掉重算的地方，先问「地址是否代表内容」；对草稿它不代表（ADR 0063），对已发布版本它代表。

## 为什么这样切

读取的组合与写入的归属是两件事。把「详情页要什么」放进 M07 会让版本模块同时拥有存储与展示；单独的读模型让展示形状可以随界面变化，而不动版本的状态机。
