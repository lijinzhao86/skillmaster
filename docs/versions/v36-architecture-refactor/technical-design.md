# v36-architecture-refactor · 技术设计

> **最后更新**：2026-11-01
> **状态**：已实现
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文只链接、不复述理由。

## 架构

### 当前形态

服务端是模块化单体：`modules/<域>` 是 12 个在 `ModuleMap` 里的模块（M1–M10、M12、M13；M11 已退休），外加无表的 `canon` 支持包，`usecase/` 是跨模块编排，`api/` 是 HTTP 适配器，`common/` 是基础约定，`config/` 是装配。模块的仓储放在 `modules/<域>/internal/`，外部不得引用；[`ArchitectureTest`](../../../skillmaster-server/src/test/java/com/skillmasterai/ArchitectureTest.java) 已在 CI 里卡环、分层、事务与内部包；`TableOwnershipTest` 卡表归属。

CLI 是 `cmd/skillmaster` 单包 + `internal/` 按关注点分包；控制台是页面 + 组件 + 纯模块 + hooks；站点很小，不在本次范围。

### 目标形态

```text
服务端
  api/            只做 HTTP：解析、鉴权主体、调用 usecase、映射响应
  usecase/        跨模块编排与事务边界；只依赖模块的发布接口
  modules/<域>/   publishedTypes（对外契约） + internal（仓储 / SQL，不可外引）
  common/         基础约定（错误、时间、ID、分页…）
  config/         装配；不被任何层依赖

CLI
  cmd/skillmaster/   package main：入口 + 调度 + 按命令组拆的文件
  internal/          纯逻辑、API 客户端、凭据、帮助、更新器…

控制台
  pages/<feature>/   页面 = 状态机 + 编排；一个页面文件夹
  components/<域>/   组件 = 只画东西
  hooks/             可复用状态逻辑
  *.ts               框架无关的纯模块（不 import react）
```

**箭头语义**：`api → usecase → modules → common`；`config` 只装配、不被依赖；`modules` 之间只能通过 `publishedTypes` 依赖；`internal` 只能被本模块引用。CLI 的 `cmd → internal`；控制台的 `pages → components → 纯模块 → api`，组件不 import 页面，纯模块不 import react。

**与当前形态的差异**只有三处：模块接口从「所有 public 类」收成 `publishedTypes`；`api` 从「可直接调模块」收成「只调 usecase（共享内核值类型除外）」；长文件按能力拆开。数据流、路由、表结构、界面都不变。

### 模块依赖现状

按 import 语句统计（不含 Javadoc 引用），当前模块间边为：

| 方向 | 边 |
|---|---|
| 账务与令牌 | `token → account`、`token → auth`、`token → audit` |
| 内容与元数据 | `version → skill`、`version → blob`、`skill → retrieval` |
| 读取与分发 | `distribution → blob`、`distribution → ingest`、`distribution → namespace`、`distribution → skill`、`distribution → version` |
| 其他 | `namespace → account`、`account → audit`、`search → skill`、`ingest → canon` |

`distribution` 是读 / 组合模块，扇出 5 个模块是它的职责；但它是本次最需要模块文档说明的一个。`version → skill` 是本次要收口的那条（见 §接口）。

## 数据模型

**本版不改数据模型。** 不新增表、不删表、不改列、不迁移数据；[`model.md`](../../architecture/model.md) 的四层身份（字节 / 文件 / 版本 / skill）与寻址规则不变。唯一的「模型」变化是代码里的值对象边界（见 §接口），不落库。

## 接口

### 对外契约（不变）

以下全部保持不变，作为本版的硬约束：

- HTTP 路径、方法、请求 / 响应 JSON 形状、错误码与状态码；
- CLI 命令名、参数、stdout / stderr 形状、退出码；
- 控制台的 URL 参数、DOM 类名、文案、aria。

### 内部接口（新增或收紧）

| 接口 | 位置 | 作用 | 变化 |
|---|---|---|---|
| `ModuleMap.Module.publishedTypes` | [`common/ModuleMap.java`](../../../skillmaster-server/src/main/java/com/skillmasterai/common/ModuleMap.java) | 每个模块声明可被外部引用的类型 | 新增字段 |
| 模块接口规则 | [`ArchitectureTest`](../../../skillmaster-server/src/test/java/com/skillmasterai/ArchitectureTest.java) | 禁止依赖其他模块未发布的类型 | 新增检查 |
| `VersionProjectionSink` | `modules/version/` | M7 向 M12 写投影的端口，替代直接依赖 `SkillMetadataService` | 新增端口 + 适配器 |
| `SkillVersionService` 门面 | `modules/version/` | 保持现有公开方法签名，内部委托给 6 个协作者 | 签名不变，实现拆分 |
| CLI 文件边界 | `cmd/skillmaster/` | 一个命令组一个文件 | 无接口变化 |
| 控制台 hook 边界 | `pages/skill/`、`pages/version-editor/` | 页面只做状态机 + 编排 | 无对外变化 |

**共享内核**（显式白名单，见 [ADR 0123](../../decisions/0123-modules-publish-an-explicit-interface.md)）：`Access`、`GrantRole`、`VersionPin`、`VersionState`。它们可以被任何模块引用；其他跨模块类型必须走拥有者的 `publishedTypes`，或由 usecase 转成普通值。

**错误形状不变**：`ApiError` / `ErrorCode` / 异常处理器的映射不动；拆分不得改变任何错误码或状态码。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P0 基线与护栏** | 基线测试结果；`docs/architecture/rules.md`；`scripts/check-file-size.mjs` + 基线；`.github/workflows/arch.yml`；ADR 0122–0125 | 不改进产品代码；行数脚本能报出基线；CI 绿；`check_docs.py` 0 |
| **P1 CLI** | `main.go` 拆为入口 + 命令组文件；`version.go`、`files.go`、`internal/api/client.go` 同法拆 | `main.go` < 300；同包测试全过；CLI 输出不变 |
| **P2 控制台** | `SkillPage` 拆为特性文件夹（4 个 tab + 7 个本版新抽的 hook + 5 个纯模块）；`VersionEditorPage` 拆为 9 个 hook + 纯模块 + 工作区组件 | 页面 < 600、组件 < 400；DOM / 文案不变；用户看过（待人看） |
| **P3 服务端** | `SkillVersionService` → 门面 + 6 协作者；`TokenStore` → 门面 + 行存储 + 加载器 + 行映射器；`VersionRepository` → 行 / 文件 / 查询仓储；三个大控制器按资源拆 | 无类 > 500（基线外）；`mvnw verify` 全绿；路由不变 |
| **P4 边界与收口** | 模块 `publishedTypes` + ArchUnit 规则；共享内核白名单；M7→M12 端口；`api` 越界规则（本版未做，见 Q3）；M03–M10、M12、M13 模块文档；最终回归 | 新规则绿；基线只减不增；文档齐；三端测试全绿 |

**每期明确不做**：P0 不改产品代码；P1 不搬包、不改命令；P2 不改 DOM / 文案 / 设计；P3 不改接口 / 表 / 事务边界；P4 不做战术 DDD、不引入新框架。

**实现结果**：P0–P4 已落地。`VersionProjectionSink` 已实现并接入（M7 不再 import `SkillMetadataService`）；`api` 窄规则按 Q3 推迟到下一版。三端测试全绿，控制台视觉验收待人看。

## 实施清单

> 勾选框是交付判据；每条都要有对应的验证命令或 review 记录。**一次只拆一层，每拆一层验一次**（dev-sop §三）。

### P0 · 基线与护栏

- [x] 从 `main` 开分支；确认工作区干净（`git status --short`），记录起始提交号。
- [x] 记录基线并写进 [`test-plan.md`](test-plan.md) §结果：
  - [x] `cd skillmaster-server && ./mvnw -B verify`（需要本地 PostgreSQL；记录通过 / 失败条数）
  - [x] `cd skillmaster-cli && gofmt -l cmd internal && go vet ./... && go test -count=1 ./...`
  - [x] `cd skillmaster-console && npm ci && npm run type-check && npm test && npm run build`
  - [x] `python3 .claude/skills/docs-architecture/scripts/check_docs.py`
- [x] 写 `docs/architecture/rules.md`：
  - [x] 三条依赖规则（表归属、事务边界、无环）＋ 分层方向；
  - [x] 模块发布接口与共享内核白名单；
  - [x] 每条规则的**强制强度**：编译期 / 测试 / lint，并链接 `ArchitectureTest`、`TableOwnershipTest`、行数脚本；
  - [x] 如实写 lint 不是证明。
- [x] 写 `scripts/check-file-size.mjs`：
  - [x] 扫描 `skillmaster-server/src/main/java`、`skillmaster-server/src/test/java`、`skillmaster-cli/cmd`、`skillmaster-cli/internal`、`skillmaster-console/src`、`skillmaster-console/tests`、`skillmaster-site/src`；
  - [x] 阈值：页面 600 / 组件 400 / 通用源码 500 / Java 类 500 / Go 文件 600 / CLI 主入口 300 / 测试 1,000；
  - [x] 跳过 `node_modules`、`dist`、`target`、生成物、`reference-python`；
  - [x] `scripts/file-size-baseline.json` 记录当前超限文件；CI 只拦新增；
  - [x] `--update-baseline` 仅本地可用，CI 不传。
- [x] 新增 `.github/workflows/arch.yml`：PR 上跑 `check-file-size.mjs` 与 `check_docs.py`。
- [x] 写 ADR [0122](../../decisions/0122-architecture-refactor-is-structural.md)、[0123](../../decisions/0123-modules-publish-an-explicit-interface.md)、[0124](../../decisions/0124-file-size-limits-are-a-shrink-only-baseline.md)、[0125](../../decisions/0125-cli-splits-by-command-group-in-one-package.md)，更新 `docs/decisions/README.md` 索引。
- [x] 退出：上述检查全绿；无产品代码变更。

### P1 · CLI 拆分

- [x] `main.go` 只留：包注释、imports、`main`、`run`、`flagValue`、`namedArgument`、`credentialSource`、调度辅助（< 300 行）。
- [x] 按命令组拆文件（同包，函数体原样搬）：
  - [x] `auth.go` / `auth_logout.go`：`login`、拒绝与提示、`authStatus*`、`loginWithBrowser`、`revokeReplaced`、`loginFacts`、`logout*`、`whereToRevoke`。
  - [x] `session.go`：`session`、`sessionMode`、`environmentKeyProblem`、`loginForACommand`、`clientFor`。
  - [x] `search.go` / `search_query.go`：搜索、列表渲染与查询参数解析。
  - [x] `share.go`、`invoke.go`、`read.go`、`versions.go`、`pins.go`、`submit.go`、`startup.go`：各命令组。
  - [x] `dispatch.go`：`skill` / `skills` / `version` 三组分派（从 `run` 里抽出）。
- [x] `version.go`（1,741）按子命令拆：`version list/show/diff/discard/rename/note/type` 等，每个文件 < 600。
- [x] `files.go`（1,591）按文件动词拆：读类（`ls/find/stat/cat/head/tail/wc/grep/check`）与写类（`write/edit/rm/mv/cp/download`）分开，每个文件 < 600。
- [x] `internal/api/client.go`（889）按关注点拆：核心 client / 资源读取 / 错误映射，每个文件 < 600。
- [x] 验证：`gofmt -l cmd internal` 为空；`go vet ./...`；`go test -count=1 ./...`；抽查 `skmr --version`、`skmr help`、golden 测试。
- [x] 退出：`main.go` < 300；无源码文件 > 600（基线外）；测试全过。

### P2 · 控制台拆分

**SkillPage（1,914 → 目标 < 600，逻辑拆到 hooks）**

- [x] 建 `src/pages/skill/` 特性文件夹；移动 `SkillPage.tsx`，更新路由 import。
- [x] 抽 `hooks/useSkillPageRoute.ts`：`?tab=` / `?sub=` / 版本路由 / 地址读写。
- [x] 抽 `hooks/useSkillVersions.ts`：筛选、查询、行匹配、回列表、基线跳转；可见版本与选择器在 `versionSelection.ts`。
- [x] 抽 `hooks/useSkillFiles.ts`：目录枚举、当前目录、打开文件、读取内容、下载与提交链接。
- [x] 抽 `hooks/useSkillSettings.ts`：可见性、下线 / 恢复服务、删除、访问申请计数。
- [x] 另抽 `hooks/useSkillImport.ts`（导入对话框与文件菜单）、`hooks/useSkillMembership.ts`（加入 / 移除库）、`hooks/useSkillAsk.ts`（申请 / 撤回访问）。
- [x] 抽 `pages/skill/InfoTab.tsx`、`FilesTab.tsx`、`VersionsTab.tsx`、`SettingsTab.tsx`；页面只做 tab 切换与编排。
- [x] 纯计算移到 `pages/skill/{skillRoute,skillAddress,versionSelection,fileListing,skillDetails}.ts`（不 import react）。
- [x] 保持 DOM 结构、类名、文案、aria、地址参数不变；不新增 / 删除任何界面元素。
- [x] 自动验证：`npm run type-check`、`npm test`、`npm run build`。
- [ ] 请用户看产品页面截图（待人看）。
- [x] 退出：`SkillPage` < 600；每个 tab 组件 < 400；测试全过。

**VersionEditorPage（1,381 → 目标 < 600，逻辑拆到 9 个 hook）**

- [x] 建 `src/pages/version-editor/` 特性文件夹；移动页面。
- [x] 抽 `hooks/useEditorDocument.ts`：files、buffers、saved、current、dirty、树。
- [x] 抽 `hooks/useEditorSave.ts`：保存、冲突、重复、错误横幅、reload。
- [x] 抽 `hooks/useEditorTree.ts`：文件树的展开、选择与新建 / 重命名 / 删除入口。
- [x] 抽 `hooks/useEditorLabels.ts`：标签与版本钉的读写。
- [x] 抽 `hooks/useEditorLeave.ts`：离开确认。
- [x] 抽 `hooks/useEditorCaret.ts`、`hooks/useEditorBannerHeight.ts`、`hooks/useEditorKeyboard.ts`、`hooks/useEditorLifecycle.ts`：光标、横幅高度、键盘与生命周期。
- [x] 复用现有 `components/version/EditorHeader|Notices|FileTree|Pane`；页面只做编排。
- [x] 纯计算移到 `pages/version-editor/editorModel.ts`。
- [x] 自动验证：同 SkillPage；`version-editor` 相关测试全过。
- [ ] 用户看产品页面截图（待人看）。
- [x] 退出：页面 < 600；组件 < 400；DOM / 文案不变。

### P3 · 服务端 god class 拆分

**`SkillVersionService`（1,265）**

- [x] 抽 package-private `VersionRows`：`summary`、`pinOf`、`visibleTo`、`visibleStates`、`versionFor`、`describe`、`draftProjectionOf`、`notDraft`、`contentConflict`。
- [x] 抽 `VersionDraftEditor`：`openDraft`、`lockDraft`、`filesOf`、`putFile`、`deleteFile`、`renameFile`、`copyFile`、`commitContent`、`updateDraft*`。
- [x] 抽 `VersionSubmitter`：`submit`、`submitVersion`、`submitFromBase`、`record`。
- [x] 抽 `VersionPublisher`：`publishVersion`、`numberFromDeclaredType`。
- [x] 抽 `VersionReader`：`authorVersionIgnoringState`、`authorVersions`、`versionOf`、`liveSnapshot`、`detailedSnapshot`、`authorSnapshot`、`authoredView`、`blankAuthoredView`、`resolveForConsumption`、`toSnapshot`、`versionsOf`、`liveVersionsOf`。
- [x] 抽 `VersionLifecycleService`：发布队列的草稿读取（`draftsOf`）、丢弃 / 下架 / 恢复（`discardVersion` / `revokeVersion` / `restoreVersion`）。
- [x] `SkillVersionService` 保留为门面，公开方法签名不变，方法体改为委托；`underExclusiveWrite` 留在门面（事务 / 锁的顺序不能拆散）。
- [x] 验证：`./mvnw -B verify`；重点看 `SkillVersionIT`、`VersionEditorIT`、`SkillSubmitIT`、`PublishBatchIT`。
- [x] 退出：无类 > 500（门面若因 Javadoc 超限，写进基线并说明）。

**`TokenStore`（853）**

- [x] 抽 `AuthorizationRowStore`：`upsertAuthorization`、`upsertCode`、`upsertAccessToken`、`upsertRefreshToken`、`remove`。
- [x] 抽 `AuthorizationLoader`：`load`、`codeToken`、`accessToken`、`refreshToken`、`selectToken`、`pastAbsoluteCeiling`、`withinReplayGrace`。
- [x] 抽 `TokenRowMapper`：四个 row record 与静态映射 / 解析。
- [x] `TokenStore` 保留 `OAuth2AuthorizationService` 适配器方法（`save`、`remove`、`findById`、`findByToken`）。
- [x] 验证：`AuthorizationServerIT`、`IssuedTokenValidatorIT`、`CliSignInIT`。
- [x] 退出：类 < 500；行为不变。

**`VersionRepository`（866）**

- [x] 抽 `VersionRowRepository`：`skill_version` 的插入 / 查找 / 状态更新。
- [x] 抽 `VersionFileRepository`：`version_file` / manifest 的读写。
- [x] 抽 `VersionQueryRepository`：列表、可见性、发布版本、草稿投影。
- [x] 保留 `VersionRepository` 为薄门面（若调用点太多），或直接更新 `SkillVersionService` 的注入。
- [x] 验证：`mvnw verify` 全绿；SQL 字符串原样搬。
- [x] 退出：仓储类 < 500；表归属不变。

**控制器**

- [x] `WebSkillController`（26 端点）→ `WebSkillController`（详情 / diff / 文件 / 归档）、`WebSkillLifecycleController`（发布 / 丢弃 / 下架 / 恢复 / 下线 / 删除）、`WebSkillAccessController`（分享 / 授权 / 访问申请）。
- [x] `AuthorSkillController`（20 端点）→ `AuthorSkillController`（读）、`AuthorSkillWriteController`（写 / 上传）。
- [x] `SkillsController`（14 端点）→ `SkillsController`（搜 / 读）、`SkillWriteController`（写）。
- [x] **上传字节上限评估后留在控制器**：`AuthorSkillWriteController.bodyOf` 读的是 `HttpServletRequest` 的流，属 HTTP 适配；原计划的 `ReadUploadUseCase` / `UploadGuard` 若搬进 usecase，会把 servlet 类型带进用例层。**这是对清单的一处偏离，记录在此**（行为不变）。
- [x] `VersionState` 等模块枚举的响应映射尽量移到 usecase model 或 API mapper；不在控制器里新增模块服务调用。
- [x] 验证：每个控制器拆完跑 `mvnw verify`；抽查路由表（路径、方法、鉴权）。
- [x] 退出：控制器 < 500；路由不变；IT 全绿。

### P4 · 边界加固与收口

- [x] `ModuleMap.Module` 增加 `publishedTypes`；为每个模块列出当前可被外部引用的类型。
- [x] `ArchitectureTest` 增加规则：模块不得依赖其他模块 `publishedTypes` 之外的类；用 `ModuleMap.PUBLISHED_TYPES` 记录现有越界，CI 只拦新增。
- [x] `rules.md` 写明共享内核白名单：`Access`、`GrantRole`、`VersionPin`、`VersionState`；其他跨模块类型走发布接口或用例层普通值。
- [x] 在 `modules/version/` 定义 `VersionProjectionSink` 端口（`recordSubmission`、`recordPublish`、`recordDiscard`、`currentVersionId`）；在 `config` 写适配器（`SkillVersionProjectionAdapter`）；`SkillVersionService` 不再 import `SkillMetadataService`。
- [ ] 评估并加 `api` 越界规则：先禁止 `api` 直接调用模块**服务**，允许共享内核值类型；现有越界进基线。把三个大控制器的组装逻辑收回 usecase。**本版未做，见 Q3。**
- [x] 补 `docs/architecture/modules/M03-auth.md` … `M13-retrieval.md`（M03–M10、M12、M13；每个模块一页：职责、拥有的表、对外契约、为什么这样切）。
- [x] 更新 `docs/architecture/README.md` 的模块索引与 [`v1-hosting/technical-design.md`](../v1-hosting/technical-design.md) §2.5：规则与模块细节指向 `rules.md` / `modules/`，不再维护第二份副本。
- [x] 最终回归：三端全量测试；`check-file-size.mjs` 无新增；`check_docs.py` 0；更新本版 README 状态与 [`test-plan.md`](test-plan.md) §结果。
- [ ] 用户看控制台截图（待人看）。
- [ ] 退出：PRD #1–#8 满足；#9 视觉验收待人看。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 是否引入 Spring Modulith 替代自研的 `ModuleMap` + ArchUnit？ | 不引入。现有检查已覆盖环、分层、internal 与表归属；Spring Modulith 不解决表归属与 `api` 越界，且要改包约定 | 在 P0 用一页对比记录；只有将来需要事务性事件 / outbox 时重新评估 |
| Q2 | `Access` / `GrantRole` / `VersionPin` / `VersionState` 是搬进独立共享内核包，还是留在原模块靠白名单？ | 先白名单，不搬包。搬包会改 50+ 文件的 import，收益是形式上的；白名单已经能阻止新增泄漏 | P4 结束时看 `ModuleMap.PUBLISHED_TYPES` 的规模；若共享类型继续增长，再开 ADR 搬包 |
| Q3 | `api` 层最终是「只禁模块服务」还是「连模块类型也禁」？ | **本版未加**：三个大控制器已拆分，但 `api` 仍直接使用 `SkillDistributionService.FileLookup` / `StreamLookup`、`VersionPin` 等返回类型 / 值类型。窄规则需要先给这些类型一个 usecase 层的归宿，代价大于本版收益 | 下一版：先把 `FileLookup` / `StreamLookup` 改为 usecase 模型，再加「api 只调 usecase」的 ArchUnit 规则 |
| Q4 | 文件行数阈值是否合适（页面 600 / 组件 400 / 通用 500 / Java 500 / Go 600 / CLI 主入口 300 / 测试 1,000）？ | 先按此执行；P1–P3 的实际拆分结果会给出校准数据 | P3 结束时统计每个拆分文件的行数；若普遍 < 300，下一版收紧 |
| Q5 | `VersionEditorPage` 先拆 hooks 还是先拆组件？ | 先拆 hooks。1,381 行里大部分是状态与保存 / 冲突逻辑，渲染已经由 `components/version/*` 承担 | P2 先动 hooks；若页面仍 > 600，再拆工作区组件 |
| Q6 | 超过 1,000 行的测试文件是否在本版拆？ | 不拆。测试是行为契约，本版只随文件移动改 import；拆测试不解决维护主线 | 列入后续待办；不影响本版验收 |
| Q7 | `SkillVersionService` 的门面长期保留，还是让 usecase 直接注入六个协作者？ | 先保留门面，避免一次改 76 处调用点；门面是模块的对外契约，协作者是内部实现 | P3 结束时评估门面行数与调用点；若门面仍 > 500，再决定是否拆调用点 |

## 附录

### 附录 A · 与现有代码的关系

| 代码 | 处理 |
|---|---|
| `skillmaster-server/src/main/java/**` | 保留模块与分层；只拆类、加端口 / 适配器、加 ArchUnit 规则；不改表与路由 |
| `skillmaster-cli/cmd/skillmaster/**` | 同包拆文件；不改命令、参数、输出、退出码 |
| `skillmaster-cli/internal/**` | 只拆 `api/client.go`；其余包不动 |
| `skillmaster-console/src/pages/{SkillPage,VersionEditorPage}.tsx` | 移入特性文件夹并拆 hooks / 子组件；DOM / 文案不变 |
| `skillmaster-console/src/components/**` | 保留；只在页面拆分需要时调整 import |
| `skillmaster-site/**` | 不动 |
| `docs/architecture/rules.md` | 新增；从 v1 TD §2.5 迁入规则，TD 改指这里 |
| `docs/architecture/modules/M03…M13` | 新增；每模块一篇 |
| 数据库 / 迁移 | 不动 |

### 附录 B · 来源与出处

**一手来源**

- Spring Modulith, *Verifying Application Module Structure*：`ApplicationModules.verify()` 覆盖无环、internal 访问与 allowedDependencies。<https://docs.spring.io/spring-modulith/reference/1.4/verification.html>
- Go, *Organizing a Go module*：更大的程序可把代码拆到多个 `package main` 文件，再把功能移进 `internal/`。<https://go.dev/doc/modules/layout>
- ESLint, *max-lines*：没有客观上限，但建议 100–500 行，默认 300。<https://eslint.org/docs/latest/rules/max-lines>

**二手来源（用于交叉验证，不单独作为依据）**

- Thoughtworks Technology Radar, *Spring Modulith*：把模块边界、进程内事件与 ArchUnit / jMolecules 结合。<https://www.thoughtworks.com/en-us/radar/languages-and-frameworks/spring-modulith>
- Java Code Geeks, *Spring Modulith 2.0: Enforcing Module Boundaries Before Microservices*（2026-07）。<https://www.javacodegeeks.com/2026/07/spring-modulith-2-0-enforcing-module-boundaries-before-microservices.html>
- rtcamp, *React Component Architecture: Best Practices for Scale*：约 200 行考虑拆分、单一职责。<https://rtcamp.com/handbook/react-best-practices/component-architecture/>
- 架构适应度函数与工具对照：ArchUnit / dependency-cruiser / go-arch-lint / import-linter；建议先上 3–5 条高价值规则、用只减不增的基线。<https://curohq.com/blogs/tools-for-automated-software-architecture-enforcement>
- DDD 适用边界（事务脚本 vs 战术 DDD）：简单 / 通用子域用事务脚本，核心子域才值得富领域模型。<https://github.com/pedronauck/skills/blob/9a5254eba0a80d1245214f0ce476622fb23d762c/skills/mine/ddd-master/references/when-not.md>

### 附录 C · 已测量的数据

口径：2026-11-01，`main` 分支（v35 合并后），`wc -l` 统计源码文件；不含测试、生成物、`node_modules`、`dist`、`target`、`references/`。

| 指标 | 数值 |
|---|---:|
| 服务端主代码 | 37,948 行 / 373 文件 |
| 服务端分层 | api 7,630（75 文件）、usecase 8,601（86）、modules 19,409（186）、common 1,059（15）、config 1,225（10） |
| 服务端最大类 | `SkillVersionService` 1,265、`VersionRepository` 866、`TokenStore` 853、`WebSkillController` 681、`AuthorSkillController` 668、`SkillsController` 628 |
| CLI `cmd/skillmaster` | 13,594 行源码 / 22 文件；测试 14,721 行 |
| CLI 最大文件 | `main.go` 3,673、`version.go` 1,741、`files.go` 1,591、`internal/api/client.go` 889 |
| 控制台源码 | 25,172 行 / 101 文件；测试 13,993 行 |
| 控制台最大页面 | `SkillPage.tsx` 1,914、`VersionEditorPage.tsx` 1,381 |
| 控制台最大组件 | `SkillCollaborators.tsx` 673、`VersionActionPanel.tsx` 538 |
| 模块 import 边（按「从该模块导入至少一个类型的文件数」） | `version→skill` 11、`token→audit` 7、`token→account` 3、`distribution→version` 3、`distribution→blob` 3、`token→auth` 2、`namespace→account` 2、`search→skill` 2、`version→blob` 2、`account→audit` 2、`skill→retrieval` 1、`ingest→canon` 1 等 |
| 跨模块共享类型使用 | 共享内核四个类型（`Access` / `GrantRole` / `VersionPin` / `VersionState`）被 56 个文件引用；`SkillMetadata` 另有 19 个文件、`SkillMetadataService` 另有 37 个（含 api、usecase、distribution；口径：import 该类型的文件数，同一文件只计一次） |