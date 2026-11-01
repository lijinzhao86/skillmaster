# v36-architecture-refactor · 测试方案

> **最后更新**：2026-11-01
> **所属版本**：[`README.md`](README.md)

## 验收映射

把 [`prd.md`](prd.md) §验收与指标 的**每一条**映射到具体用例。允许一条对多例。

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 行为不变 | T1、T2、T4、T6、T10 | 通过 |
| #2 对外契约不变 | T3、T4、T10 | 通过 |
| #3 CLI 长文件达标 | T7 | 通过 |
| #4 控制台页面达标 | T7 | 通过 |
| #5 服务端类达标 | T7 | 通过 |
| #6 模块接口有规则 | T8 | 通过 |
| #7 共享内核显式 | T8 | 通过 |
| #8 文档一致 | T9 | 通过 |
| #9 视觉不变 | T5 | 待人看 |

## 用例

### T1 · 三端基线测试

- **前置**：干净的工作区，依赖已安装；服务端需要 PostgreSQL（本地或 `compose.yaml`）。
- **步骤**：
  1. `cd skillmaster-server && ./mvnw -B verify`
  2. `cd skillmaster-cli && gofmt -l cmd internal && go vet ./... && go test -count=1 ./...`
  3. `cd skillmaster-console && npm ci && npm run type-check && npm test && npm run build`
- **期望**：全部退出码 0；记录通过条数，作为后续对比基线。
- **判定**：通过 / 失败（失败时记录第一条错误）。

### T2 · CLI 拆分后行为不变

- **前置**：P1 完成；CLI 可构建。
- **步骤**：跑 T1 的 CLI 部分；额外跑 help / 退出码 golden 测试；抽查 `skmr --version`、`skmr help`、`skmr skills list --help`。
- **期望**：测试全过；输出与拆分前逐字节一致（golden 测试已覆盖）；退出码不变。
- **判定**：通过 / 失败。

### T3 · CLI 对外契约抽查

- **前置**：P1 完成。
- **步骤**：对比拆分前后 `skmr help`、`skmr skill --help`、`skmr version --help` 的输出；检查退出码 0 / 2 / 1 的代表命令。
- **期望**：命令名、参数、输出、退出码不变。
- **判定**：通过 / 失败。

### T4 · 控制台页面拆分后行为不变

- **前置**：P2 完成。
- **步骤**：`npm run type-check && npm test && npm run build`；跑 `skills`、`skill-transfer`、`version-editor` 相关测试文件。
- **期望**：测试全过；DOM 类名、文案、aria 不变（组件测试断言）。
- **判定**：通过 / 失败。

### T5 · 控制台视觉检查

- **前置**：P2 完成；本地起控制台。
- **步骤**：本地起控制台，打开 skill 页四个 tab 与版本编辑器，对照设计稿逐屏看；出截图。`console-states` 跑的是设计演示件，不是产品构件，不能作为这一条的机械证据。
- **期望**：用户确认「看不出差别」。
- **判定**：通过 / 失败（用户签字）。

### T6 · 服务端拆分后行为不变

- **前置**：P3 完成；PostgreSQL 可用。
- **步骤**：`./mvnw -B verify`；重点看 `SkillVersionIT`、`VersionEditorIT`、`SkillSubmitIT`、`PublishBatchIT`、`AuthorizationServerIT`、`CliSignInIT`。
- **期望**：全部 IT 通过；路由表不变；事务 / 锁行为不变。
- **判定**：通过 / 失败。

### T7 · 行数检查

- **前置**：P0 完成；`scripts/check-file-size.mjs` 可用。
- **步骤**：跑脚本；检查 `main.go`、`SkillPage.tsx`、`VersionEditorPage.tsx` 与三个最大 Java 类的行数；确认基线只减不增。
- **期望**：无新增超限；目标文件达到验收阈值。
- **判定**：通过 / 失败。

### T8 · 模块接口与共享内核规则

- **前置**：P4 完成。
- **步骤**：`./mvnw -B verify`（含 `ArchitectureTest`）；检查 `ModuleMap.PUBLISHED_TYPES` 与 `SHARED_KERNEL` 的并集（当前 30 个类型，其中 4 个同时属于共享内核）；检查 `rules.md` 的共享内核白名单。只减不增由 review 保证，没有单独的基线文件。
- **期望**：新规则通过；基线不增；白名单只有四个类型。
- **判定**：通过 / 失败。

### T9 · 文档校验

- **前置**：所有文档写完。
- **步骤**：`python3 .claude/skills/docs-architecture/scripts/check_docs.py`。
- **期望**：退出码 0；相对链接全部有效。
- **判定**：通过 / 失败。

### T10 · 对外契约差异检查

- **前置**：P1–P3 完成。
- **步骤**：对比拆分前后的路由表（路径、方法、鉴权）、CLI golden 输出、控制台页面 URL 参数与 DOM 类名；抽查错误码。
- **期望**：无差异。
- **判定**：通过 / 失败。

## 环境与数据

- **环境怎么造**：从 `main` 的干净 clone 开始；服务端按 `compose.yaml` 或本地 PostgreSQL 起库；CLI 用 `go test`（不需要服务端）；控制台 `npm ci`。
- **数据**：服务端 IT 使用测试数据库与迁移；CLI 使用测试夹具；控制台测试 stub `fetch`。不需要生产数据。
- **清理**：测试数据库由测试自己清理；不写生产环境；不提交 `target/`、`dist/`、`node_modules/`。

## 结果

**基线（改动前）**：CLI 全部包通过；控制台 33 文件 / 614 条通过；服务端 `Tests run: 535, Failures: 0, Errors: 0`（集成测试；另有 401 条单元测试）。

实现完成后填写。每条都要有证据（命令输出、截图、日志片段），不要只写「通过」。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | CLI 全部包通过；控制台 33 文件 / 614 条通过；服务端 `Tests run: 535, Failures: 0, Errors: 0` |
| T2 | 通过 | 拆分后 `go test -count=1 ./...` 全绿；`gofmt -l` 为空、`go vet` 干净 |
| T3 | 通过 | CLI help / 退出码 golden 测试全过；命令名、参数、输出未改 |
| T4 | 通过 | `npm run type-check`、`npm test`（614）、`npm run build` 全过；测试断言未改 |
| T5 | 待人看 | 需用户在浏览器里对照两个页面；Claude 看不到渲染结果 |
| T6 | 通过 | `./mvnw -B verify`：535 条，0 失败 |
| T7 | 通过 | `node scripts/check-file-size.mjs` 退出 0；`main.go` 147、`SkillPage` 415、`VersionEditorPage` 267、`SkillVersionService` 196、`TokenStore` 221、`VersionRepository` 250 |
| T8 | 通过 | `ArchitectureTest` 6 条全绿，含「只依赖发布类型」；`PUBLISHED_TYPES` 与共享内核的并集 30 个类型（其中 4 个是共享内核；review 保证只减） |
| T9 | 通过 | `check_docs.py` 退出码 0；相对链接全部有效 |
| T10 | 通过 | 既有 HTTP IT、CLI golden、控制台 DOM 测试全过；未改路由或契约 |

## 已知问题

### KI-1 · `api` 层窄规则未加

`docs/versions/v36-architecture-refactor/technical-design.md` Q3 · **已记录**

本版拆了三个大控制器，但没有加「`api` 只调 usecase」的 ArchUnit 规则；`api` 仍直接使用 `SkillDistributionService.FileLookup` / `StreamLookup`、`VersionPin` 等返回类型 / 值类型。原因与下一版的处理方式见 TD Q3。

本版范围内暂无其他未修缺陷。实现过程中发现的未修缺陷按模板登记；本版范围外的历史缺陷写 `known-issues.md`（本版未创建）。