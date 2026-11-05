# M04 · 命名空间与权限归属

> **状态**：已实现 · 拥有表与对外发布类型见 [`system.md`](../system.md) 的对应行（源是 `ModuleMap`）

## 职责

个人命名空间的生命周期、保留 slug、成员关系，以及「这个命名空间归谁」这一事实。skill 的地址由 `namespace/name` 组成，M04 回答的是前半段。

## 边界

- **不做**：skill 的内容、版本、权限梯子——那是 M07 / M12。
- **读 M01 的一个事实**：`AccountDirectory.handleOf(userId)`，因为命名空间 slug 来自用户的 handle；这是只读接缝，不反向写 M01。

## 对外契约

`Namespace` 是地址解析与归属判定的结果。用例层用它判定归属，再把普通值（namespace id）交给 M12；M09 需要它来拼地址。

`Namespace` 同时带一个 `official`：这个命名空间能否设 `public`（见 [ADR 0146](../../decisions/0146-public-is-platform-endorsement.md)）。它是命名空间上的一列，由迁移/种子写，注册路径不碰它；写路径与读路径都读这一个值。

## 为什么这样切

命名空间是身份与寻址的边界，不是 skill 的属性。把它并进 M12 会让「创建一个用户」与「创建一个 skill」共用一套生命周期，而它们的所有权规则不同（命名空间属于账号，skill 属于命名空间）。
