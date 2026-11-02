# ADR 0123 · 模块只对外发布显式接口

- **状态**：已采纳
- **日期**：2026-11-01
- **依赖**：[ADR 0122](0122-architecture-refactor-is-structural.md)；现有 [`ArchitectureTest`](../../skillmaster-server/src/test/java/com/skillmasterai/ArchitectureTest.java) 与 [`ModuleMap`](../../skillmaster-server/src/main/java/com/skillmasterai/common/ModuleMap.java)

## 背景

`ModuleMap` 现在回答「每个模块拥有哪些表」，ArchUnit 回答「模块间无环、分层向下、事务只在 usecase、`internal` 不被外部引用」。但它不回答「模块 A 可以 import 模块 B 的哪些类」——今天任何 public 类都算接口。

后果已经出现：M7（version）的 `SkillVersionService`、`VersionRepository`、`VersionConfiguration` import 了 M12（skill）的 `Access`、`GrantRole`、`SkillMetadata`、`SkillMetadataService`、`DraftProjection`、`VersionProjection`；`api` 层可以直接 import 模块服务与模块枚举。`Access` / `GrantRole` / `VersionPin` / `VersionState` 事实上是跨模块共享语言，但没有任何一处写明「这是共享内核」。

## 决定

1. `ModuleMap.Module` 增加 `publishedTypes`：每个模块显式列出它可以被外部引用的类型。
2. `ArchitectureTest` 增加一条规则：任何模块不得依赖另一个模块 `publishedTypes` 之外的类。
3. **共享内核显式化**：`Access`、`GrantRole`、`VersionPin`、`VersionState` 列入共享内核白名单，允许被任何模块引用；其余跨模块类型必须走拥有者的发布接口，或者由用例层转成普通值。
4. 规则**只对新代码生效**：先把当前的全部跨模块 import 生成一份基线（`ModuleMap.PUBLISHED_TYPES`），CI 只拦「基线之外的新增」；基线只减不增。

## 理由

**否掉 Spring Modulith。** 它提供的 `ApplicationModules.verify()` 覆盖「无环、不访问 internal、allowedDependencies」，与现有 ArchUnit + ModuleMap 重叠；引入它要加框架依赖、改包约定，而它不解决表归属，也不解决 `api` 层越界。等将来需要事务性事件发布 / outbox 时再评估。

**否掉「每个模块加一个 `api` 子包、其余全进 `internal`」。** 这是更彻底的形状，但要移动几十个类、改大量 import，且与现有 `internal` 只放仓储的约定冲突；收益可以在白名单规则下逐步拿到。

**为什么保留共享内核而不是消灭它。** `Access` 是 M12 算出的「一个人对一个 skill 能走多远」，M7 / M9 必须接收它；`GrantRole` 是权限梯子的唯一定义；`VersionPin` / `VersionState` 是寻址与状态词汇。把它们复制成每个模块自己的一套，会让「一档权限包含下面所有档」出现第二个实现——比共享内核更危险。共享内核的问题是它会慢慢变大，所以用白名单把它钉住。

**为什么用「只减不增的基线」而不是一次性全绿。** 当前跨模块 import 有几十处，一次性改完等于把重构和边界加固绑在一起，风险与 review 成本都过高。基线让 CI 从第一天起阻止新增，同时把已有债务变成一份可见、可逐条减少的清单。

## 后果

- 新增跨模块 import 会红；要么把它加进拥有者的 `publishedTypes`（需要 review），要么改成普通值 / 用例层转换。
- 基线是一份需要维护的清单；每次减少都要更新它，且不能为了让 CI 绿而扩大它。
- `api` 层是否允许 import 模块类型，v36 P4 评估后推迟到下一版（见 v36 TD Q3）；本条只先管「模块 ↔ 模块」。
- 模块文档（`docs/current/modules/`）要逐步补齐：一个模块的 `publishedTypes` 是它的对外契约，契约应该有文字说明。
