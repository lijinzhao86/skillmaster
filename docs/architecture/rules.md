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
- **基线**：`ModuleMap.PUBLISHED_TYPES` 与 `ModuleMap.SHARED_KERNEL` 的**并集**（当前 30 个类型，其中 4 个是共享内核，不重复计数）；只减不增靠 review，没有单独的基线文件。
- **强度**：测试；基线是过渡手段，不是最终形态。
- **例外**：`modules/canon` 是无表的支持包，不在 `ModuleMap` 里；`ingest → canon` 这条边由 review 看，发布类型规则不覆盖它。

### 共享内核（显式白名单）

以下类型是跨模块共享语言，允许被任何模块引用：

| 类型 | 所属模块 | 为什么共享 |
|---|---|---|
| `Access` | M12 skill | M12 算出的「一个人对一个 skill 能走多远」；M7 / M9 必须接收它 |
| `GrantRole` | M12 skill | 权限梯子的唯一定义；复制一份会让「一档包含下面所有档」出现第二个实现 |
| `VersionPin` | M7 version | 寻址词汇（版本号 / 摘要 / 行 id / latest） |
| `VersionState` | M7 version | 版本状态词汇（draft / published / revoked / discarded） |

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

| 种类 | 上限 | 依据 |
|---|---:|---|
| 页面（`skillmaster-console/src/pages/**`） | 600 | 仓库既有约定（dev-sop §三） |
| 组件（`skillmaster-console/src/components/**`） | 400 | 同上 |
| 通用源码（页面 / 组件之外的 TS / TSX，以及其余源码） | 500 | 业界常见区间 100–500（ESLint `max-lines` 默认 300） |
| Java 类 | 500 | 同上 |
| Go 文件 | 600 | Go 官方布局允许同包多文件；`funlen` 默认函数 60 行 |
| CLI 主入口（`skillmaster-cli/cmd/skillmaster/main.go`） | 300 | 入口只做调度；具体值与理由见 ADR 0124 |
| 测试文件 | 1,000 | 测试按场景组织，不按行数硬拆 |

现有超限文件进入 `scripts/file-size-baseline.json`；**基线只减不增**，新增超限一律失败。阈值是启发式，不是 correctness 证明。

## 六、前端与 CLI 的结构规则

- **前端**：页面只做状态机 + 编排；画东西的交给组件，算东西的交给纯模块。依赖方向 `pages → components → 纯模块 → api`；组件不 import 页面，纯模块不 import react。详见 [dev-sop](../../.claude/skills/dev-sop/SKILL.md) §三。
- **CLI**：`main.go` 只做入口与调度；命令按组放在同包文件里；纯逻辑进 `internal/`。详见 [ADR 0125](../decisions/0125-cli-splits-by-command-group-in-one-package.md)。
