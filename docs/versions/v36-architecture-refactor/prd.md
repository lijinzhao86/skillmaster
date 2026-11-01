# v36-architecture-refactor · 产品需求

> **最后更新**：2026-11-01
> **所属版本**：[`README.md`](README.md)

## 用户与场景

**谁在用**：维护这个仓库的人，以及在这个仓库里改代码的 AI agent（Claude Code 等）。

**在什么场景下用**：要改一个命令、一个页面或一个领域服务时，先得在几千行的文件里建立地图；要新增一个模块时，不确定可以依赖谁、哪些类型算对外契约；要 review 一次改动时，diff 里混着结构噪声与行为变更。CLI 的 `main.go` 一个文件 3,673 行，控制台两个页面 1,914 / 1,381 行，服务端三个类 853–1,265 行；这些文件每次改动都要整份读进来，AI 的上下文窗口与人的注意力都花在地图上，而不是改动本身。

**现在的替代做法是什么**：靠 grep 和跳转；靠记忆知道「M7 可以 import M12 的哪些类」；review 时人工判断有没有越界。这些替代做法在文件小的时候够用，在现在的规模上已经不够——`version → skill` 的类型泄漏就是靠人工 review 没拦住的例子。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | P0：记录基线；迁 `docs/architecture/rules.md`；加文件行数检查与 CI；写 ADR 0122–0125 | 文档 + 脚本 + CI，无产品代码变更 |
| 2 | P1：拆 CLI 的 `main.go`、`version.go`、`files.go`、`internal/api/client.go` | 同包多文件，行为不变 |
| 3 | P2：拆控制台 `SkillPage.tsx`、`VersionEditorPage.tsx` | 特性文件夹 + hooks + 子组件，DOM / 文案不变 |
| 4 | P3：拆服务端 `SkillVersionService`、`TokenStore`、`VersionRepository` 与三个大控制器 | 门面 + 协作者 / 仓储拆分，接口与路由不变 |
| 5 | P4：模块发布接口白名单、共享内核显式化、M7→M12 投影端口、`api` 越界规则（**本版未做，见 TD Q3**）、模块文档 | ArchUnit 规则 + 适配器 + 文档 |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 战术 DDD 重写（聚合根、领域事件、CQRS / ES） | 当前业务以事务脚本 + 仓储为主，重写风险远大于收益；见 [ADR 0122](../../decisions/0122-architecture-refactor-is-structural.md) | 未定；只有核心子域出现本质复杂度时再评估 |
| 2 | 改动 HTTP / CLI / 控制台对外契约 | 这是重构，不是产品版本；任何契约变化都会让本版的「行为不变」验收失效 | 不做 |
| 3 | 改表结构或迁移数据 | 结构重构不需要动数据；动数据会把风险从代码扩散到生产 | 不做 |
| 4 | 引入 Spring Modulith / cobra / 新前端状态库 | 现有 ArchUnit + ModuleMap、stdlib flag、React 状态已够用；新框架会带来一次行为重写 | 未定；需要事件发布 / outbox 时再评估 |
| 5 | 重画界面或改文案 | 本版只拆结构；界面改动必须走设计稿流程 | 不做 |
| 6 | 重写测试以适配新结构 | 测试是行为契约；只允许随文件移动做 import 调整，不允许改写断言 | 不做 |
| 7 | 把 CLI 命令搬进 `internal/command/*` 子包 | 同包拆文件已能解决 3,673 行的问题；搬包会先制造共享辅助的导出 / 循环依赖 | 若 `package main` 仍超限，下一版再评估 |

## 控制台操作与 CLI 对应

本版**不新增或改动任何控制台操作**，因此没有需要映射的 CLI 命令。对照 [ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)：

| # | 控制台操作 | CLI 命令 | 备注 |
|---|---|---|---|
| — | 无（本版只改工程结构，不改界面行为） | — | 不适用 |

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | **行为不变**：服务端全部 IT、CLI 全部测试、控制台全部测试通过，且既有测试的断言未被改写 | `./mvnw -B verify`、`go test -count=1 ./...`、`npm test` 全绿；diff 中测试文件只允许 import / 文件移动 |
| 2 | **对外契约不变**：HTTP 路由与请求 / 响应形状、CLI 输出与退出码、控制台 DOM 类名与文案不变 | 既有契约测试全过（CLI 有 golden 测试；控制台由组件测试断言 DOM / 文案）；抽查路由表与 CLI 样例输出 |
| 3 | **CLI 长文件达标**：`cmd/skillmaster/main.go` < 300 行；`cmd/skillmaster` 下无源码文件 > 600 行（基线外） | `scripts/check-file-size.mjs` |
| 4 | **控制台页面达标**：`src/pages/**` 页面 < 600 行，`src/components/**` 组件 < 400 行（基线外） | 同上 |
| 5 | **服务端类达标**：`src/main/java` 下无类 > 500 行（基线外） | 同上 |
| 6 | **模块接口有规则**：`ArchitectureTest` 新增「只依赖发布类型」规则并通过；基线只减不增 | `./mvnw -B verify`；`ModuleMap.PUBLISHED_TYPES` 与 `SHARED_KERNEL` 的并集只减不增（当前 30 个类型，其中 4 个是共享内核） |
| 7 | **共享内核显式**：`rules.md` 列出 `Access` / `GrantRole` / `VersionPin` / `VersionState`，并说明其他跨模块类型的处理方式 | 文档 review |
| 8 | **文档一致**：`check_docs.py` 退出码 0；新增文档链接全部可解析 | `python3 .claude/skills/docs-architecture/scripts/check_docs.py` |
| 9 | **视觉不变**：控制台两个页面拆分后，DOM 与设计件一致；由人看过截图 | 用户看产品页面截图并确认 |

**成立条件**：#1 与 #2 任何一条不满足，本版不算交付；#3–#5 是结构目标，允许基线内的例外，但基线不得扩大。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 超限文件数 | 超过本版阈值的源码文件数（不含测试与基线内文件） | `check-file-size.mjs` | 0（基线内除外） |
| 基线条目数 | `file-size-baseline.json` 中的文件数 | 同上 | 只减不增 |
| 跨模块越界 import 数 | 模块 import 另一个模块 `publishedTypes` 之外的类 | ArchUnit 规则 + `ModuleMap.PUBLISHED_TYPES` | 只减不增 |
| 主文件行数 | `main.go` / `SkillPage.tsx` / `VersionEditorPage.tsx` / 三个最大 Java 类 | `wc -l` | 见验收 #3–#5 |
| 测试通过数 | 三端既有测试的通过条数 | 各测试命令 | 不低于基线 |

## 依赖与前置

- **外部依赖**：无。本版不新增运行时依赖，不依赖外部服务。
- **内部前提**：[ADR 0122](../../decisions/0122-architecture-refactor-is-structural.md)（结构而非战术 DDD）、[ADR 0123](../../decisions/0123-modules-publish-an-explicit-interface.md)（模块发布接口）、[ADR 0124](../../decisions/0124-file-size-limits-are-a-shrink-only-baseline.md)（行数基线）、[ADR 0125](../../decisions/0125-cli-splits-by-command-group-in-one-package.md)（CLI 拆法）。
- **排除了什么前置**：不需要设计稿（不改界面）、不需要数据库迁移（不改表）、不需要新的部署配置（不改运行时）。
