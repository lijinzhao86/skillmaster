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

**数值的唯一权威是 [`scripts/check-file-size.mjs`](../../scripts/check-file-size.mjs) 的 `LIMITS`**——本文不抄那张表。分类判据同在脚本的 `kindOf()`；目前所有种类统一 1,000 行，超过才失败（[ADR 0162](../decisions/0162-file-size-limit-is-one-threshold.md)）。「只减不增」这条的取舍见 [ADR 0124](../decisions/0124-file-size-limits-are-a-shrink-only-baseline.md)。

现有超限文件进入 `scripts/file-size-baseline.json`；**基线只减不增**，新增超限一律失败。阈值是启发式，不是 correctness 证明。

## 六、前端与 CLI 的结构规则

- **前端**：页面只做状态机 + 编排；画东西的交给组件，算东西的交给纯模块。依赖方向 `pages → components → 纯模块 → api`；组件不 import 页面，纯模块不 import react。详见 [`.dev-sop/profile.md`](../../.dev-sop/profile.md) 的前端约定。
- **CLI**：`main.go` 只做入口与调度；命令按组放在同包文件里；纯逻辑进 `internal/`。详见 [ADR 0125](../decisions/0125-cli-splits-by-command-group-in-one-package.md)。

## 七、API 面与暴露规则

**一条路径前缀就是一个面；面决定凭据、暴露范围与失败形状。** 决策见 [ADR 0198](../decisions/0198-api-planes-are-named-by-audience.md)。

| 前缀 | 使用方 | 凭据 | 暴露 |
|---|---|---|---|
| `/oauth/**`、`/.well-known/**` | OAuth 协议 | 协议自带 | 公网 |
| `/web/**` | 浏览器控制台 | 会话 cookie + CSRF | 公网 |
| `/api/v1/**` | **CLI 与 agent**（不是 CLI 专用） | Bearer token / API key + scope | 公网 |
| `/inner/v1/**`（server） | **服务与服务**及 CI 等内部触发 | `X-Inner-Token` + 私网连接器 | **不对外** |
| `/admin/v1/**` | 运营 / 管理控制台 | 运营角色（未来） | 公网但角色门禁 |

规则：

1. **一条路由只属于一个面。** 不得让同一路径在两种凭据下都可用；每个面一条 `securityMatcher` 精确匹配的链。
2. **默认拒绝。** 每个面的链最后用 `denyAll()`；公开例外逐条命名（`/actuator/health`、`/error`、`/cli-sign-ins/exchange`、`/gateway/**`、退役后仅为返回 404 的 `/internal/**`）。`anyRequest().authenticated()` 不再作为兜底。
3. **`/inner` 三层隔离缺一不可**：nginx 对 `/inner/` 返回 404；server 只绑 VPC 私网 IP 的独立连接器；安全组只放 ops 私网地址；应用层再校验 `X-Inner-Token`（常数时间比较），不认用户 Bearer。
4. **`/admin` 是 JSON API，不是服务端渲染页面。** 页面归 SPA（[ADR 0015](../decisions/0015-web-frontend-stack.md)）；运营角色落地前 `/admin/**` 显式拒绝，不能落到 `permitAll` 或 `authenticated()`。
5. **`/api/v1` 是 CLI 与 agent 共用面**；写「CLI 专用」会让 agent 接入被误判成越界。
6. **`/internal` 退役**：server 的接收端是 `POST /inner/v1/sync/submissions`，ops 自己的触发端是 `POST /inner/v1/jobs/import/runs`；server 上不留 `/internal`，nginx 规则随之改。
7. **每个面自己的版本**：`/api/v1` 跟 CLI 大版本（[ADR 0088](../decisions/0088-cli-major-version-is-the-api-generation.md)）；`/inner/v1`、`/admin/v1` 各自独立。
8. **同一套 JSON 与错误形状**：所有 JSON 面用 snake_case 与 `{"error":{"code","message","details"}}`；`inner` 不另造一套。
9. **`/admin` 与 skill 权限里的 `admin` 档不是一回事**：前者是平台运营面，后者是 `GrantRole.ADMIN`，某条 skill 的共享权限档位。

**强制与强度**：

- `SecurityConfig` 的链与 matcher 由测试断言；**待补**一条路由面清单测试，断言每个 controller 的前缀只属于一个面（测试能证明前缀，不能证明运行时网络）。
- nginx 的 `/inner/` 404 由 `ops-check.yml` 比对文件哈希；它证明仓库与主机上那份配置一致，不证明线上行为，也不证明端口不可达。
- 私网连接器、安全组与 RDS 白名单是部署事实，靠发布清单核对，**没有代码测试**。
- 默认拒绝由安全链覆盖所有未列出的路由；`/admin` 没有专门的拒绝测试（待补），运营角色落地后改为角色门禁，测试随之改。

**现状（2026-11-14）**：`/oauth`、`/web`、`/api/v1` 已实现；`/inner/v1/sync/submissions` 由运营服务调用，只开在私网连接器上；`/admin` 尚无路由，按本条先占名字与拒绝规则。`/internal` 已随导入搬迁退役。
