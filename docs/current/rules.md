# 工程架构规则

> **最后更新**：2026-11-01
> **这一轴是什么**：不随版本变的工程架构——系统怎么切、模块之间的契约与依赖规则、以及这些规则靠什么强制。
> **不是什么**：不是产品目标（[`target/`](../target/README.md)），不是取舍记录（[`decisions/`](../decisions/README.md)），也不是某一版的接口与表结构（[`versions/`](../versions/)）。

## 一、模块与分层

服务端是**模块化单体**。模块清单与每个模块拥有的表以代码侧的 [`ModuleMap`](../../skillmaster-server/src/main/java/com/skillmasterai/common/ModuleMap.java) 为权威；[`v1-hosting/technical-design.md`](../versions/v1-hosting/technical-design.md) §2.5 的表格是说明性摘要，`TableOwnershipTest` 只对齐 `ModuleMap` 与迁移，不读那张表。

```
Config  >  Api  >  UseCase  >  Modules  >  Common
```

- `config` 是 Spring 装配，只向下装配 bean；**不被任何层依赖**。
- `api` 是 HTTP 适配器：解析、鉴权主体、调用 usecase、映射响应。
- `usecase` 是跨模块编排层；**跨模块事务边界只在这里划定**。
- `modules/<域>` 是业务模块；仓储与 SQL 放在 `modules/<域>/internal/`。
- `common` 是基础约定（错误、时间、ID、分页等）；**不依赖任何其他层**。

## 二、三条依赖规则

### 规则 ① · 一个模块只能读写自己拥有的表

跨模块取数必须经对方接口。**判据是一句话：一条 SQL 语句不得同时点两个模块的表。**

- **强制**：[`TableOwnershipTest`](../../skillmaster-server/src/test/java/com/skillmasterai/TableOwnershipTest.java) 做源码 lint——它读 SQL 字符串里的表名，并与 `ModuleMap` 的归属集合比对。
- **强度**：**lint 不是证明**。动态拼接、视图、函数体内的 SQL 都可能绕过它；review 仍要看 SQL。
- **落法**：表的所有者执行，另一个模块只出普通值（`Set` / `int` / `String` / 记录），接口里不出现对方模块的类型。

### 规则 ② · 跨模块事务边界只能由用例层划定

模块内部的不变量也由 `usecase` 开的事务覆盖；`@Transactional` 只能出现在 `usecase`，模块自己不声明事务。

- **强制**：[`ArchitectureTest.transactionsAreDeclaredOnlyInUseCases`](../../skillmaster-server/src/test/java/com/skillmasterai/ArchitectureTest.java)。
- **强度**：测试；能证明注解位置，不能证明事务内没有跨模块调用。

### 规则 ③ · 禁止循环依赖

模块之间不得成环。

- **强制**：[`ArchitectureTest.modulesAreFreeOfCycles`](../../skillmaster-server/src/test/java/com/skillmasterai/ArchitectureTest.java)。
- **强度**：测试（ArchUnit）。

## 三、模块发布接口与共享内核

**每个模块只对外发布显式接口。** 一个模块的 public 类不等于它的契约；只有 `ModuleMap.Module.publishedTypes` 列出的类型可以被其他模块引用。

- **强制**：[`ArchitectureTest.modulesDependOnlyOnPublishedTypes`](../../skillmaster-server/src/test/java/com/skillmasterai/ArchitectureTest.java)（v36 P4 已加入）。
- **基线**：`ModuleMap.PUBLISHED_TYPES` 与 `ModuleMap.SHARED_KERNEL` 的**并集**；只减不增靠 review，没有单独的基线文件。**类型清单与个数以 `ModuleMap` 为准，本文不复述**——抄一份就会漂。
- **强度**：测试；基线是过渡手段，不是最终形态。
- **例外**：`modules/canon` 是无表的支持包，不在 `ModuleMap` 里；`ingest → canon` 这条边由 review 看，发布类型规则不覆盖它。

### 共享内核（显式白名单）

**成员名单的唯一权威是 `ModuleMap.SHARED_KERNEL`**，这里不复述——加一个内核类型是一次 ADR（[ADR 0123](../decisions/0123-modules-publish-an-explicit-interface.md)），不是一次改表。

判据是「跨模块的领域词，不是某个模块的实现细节」：授权档位、版本钉、版本状态、访问结论都属于这一类；复制一份会让它们各自长出一个实现。

**其他跨模块类型必须走拥有者的 `publishedTypes`，或由 usecase 转成普通值。** 新增共享内核类型要开 ADR，不能靠一次 import 悄悄扩大。

## 四、可执行的检查与它们的强度

| 检查 | 位置 | 强制强度 | 能证明什么 | 不能证明什么 |
|---|---|---|---|---|
| 表归属 | `TableOwnershipTest` | 源码 lint | SQL 字符串里点到的表属于本模块 | 动态 SQL、视图、函数体 |
| 分层 | `ArchitectureTest.layersOnlyDependDownwards` | 测试 | 包之间的依赖方向 | 运行时的调用路径 |
| 无环 | `ArchitectureTest.modulesAreFreeOfCycles` | 测试 | 模块切片之间没有环 | 逻辑上的循环调用 |
| 事务位置 | `ArchitectureTest.transactionsAreDeclaredOnlyInUseCases` | 测试 | `@Transactional` 只出现在 usecase | 事务内没有跨模块调用 |
| 内部包 | `ArchitectureTest.nothingOutsideAModuleMayReferenceAnotherModulesInternals` | 测试 | `internal` 不被外部引用 | 通过反射或字符串的绕过 |
| common 不依赖其他层 | `ArchitectureTest.commonDependsOnNoOtherLayer` | 测试 | `common` 不依赖 modules / usecase / api | 运行时通过反射的绕过 |
| 发布类型 | `ArchitectureTest.modulesDependOnlyOnPublishedTypes`（v36 P4 已加入） | 测试 | 只依赖 `publishedTypes` 与共享内核 | 类型内部的耦合；`canon` 不在覆盖范围 |
| 文件行数 | `scripts/check-file-size.mjs` | 脚本 + CI | 文件不超过阈值 | 文件内部是否「一件事」 |

**没有一条检查是 correctness 证明。** 它们的作用是把「已经讨论过的约定」变成会红的信号；review 仍然要看代码。

`arch.yml` 跑行数与文档校验；Java 架构检查（6 条 `ArchitectureTest` + `TableOwnershipTest`）随 `server.yml` 的 `./mvnw -B verify` 执行（该工作流按 `skillmaster-server/**` 路径触发）。

## 五、文件行数

**数值的唯一权威是 [`scripts/check-file-size.mjs`](../../scripts/check-file-size.mjs) 的 `LIMITS`**——本文不抄那张表。分类判据在 [`scripts/check-file-size.mjs`](../../scripts/check-file-size.mjs) 的 `kindOf()`；前端那两条（页面约 600、组件约 400）另写在 `.code-dev-sop/profile.md` 的前端约定里。「只减不增」这条的取舍见 [ADR 0124](../decisions/0124-file-size-limits-are-a-shrink-only-baseline.md)。

现有超限文件进入 `scripts/file-size-baseline.json`；**基线只减不增**，新增超限一律失败。阈值是启发式，不是 correctness 证明。

## 六、前端与 CLI 的结构规则

- **前端**：页面只做状态机 + 编排；画东西的交给组件，算东西的交给纯模块。依赖方向 `pages → components → 纯模块 → api`；组件不 import 页面，纯模块不 import react。详见 [`.code-dev-sop/profile.md`](../../.code-dev-sop/profile.md) 的前端约定。
- **CLI**：`main.go` 只做入口与调度；命令按组放在同包文件里；纯逻辑进 `internal/`。详见 [ADR 0125](../decisions/0125-cli-splits-by-command-group-in-one-package.md)。
