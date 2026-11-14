# v69-ops-service · 需求

## 用户与场景

**平台运营者是主要用户。** 他每天要做的事里，第一件是把公开来源里值得收录的 skill 导进来。今天这件事由 CI 通过 SSH 打服务端的 `/internal/sync/run` 触发，抓取、扫描、判许可证、组装草稿全在线上服务进程里跑。

现在的代价：

- 抓取与扫描是网络与 CPU 活，和用户请求抢同一份 CPU 与数据库连接。
- 一次导入出问题，和线上故障混在同一个日志、同一个进程里，排查时先要分清是谁。
- 线上服务发版要连带重启导入；导入调参也要重启线上服务。

**终端用户不是本版的用户。** 他们看到的 skill、筛选与信息页在这一版前后没有任何变化——这是一次**平切**：行为不变，只换进程。

**打标不是本版的事。** 现有的 Skill / Skill Label 自动标注放到下一版；它需要先在 ops 里有稳定的骨架与运行记录，本版把骨架立起来。

## 范围

### 做

1. **新服务 `skillmaster-ops`**：独立 Spring Boot 进程、独立库 `skillmaster_ops`、自己的 Flyway；无前端、无用户登录，只有服务间令牌与自己的定时任务。它不编译 `skillmaster-server` 的任何类。
2. **自动导入搬进 ops**：抓取公开来源、安全扫描、许可证判断、组包在 ops 完成。
3. **一条 inner 写通路**：ops 把组好的提交交给 `POST /inner/v1/sync/submissions`，server 仍用现在那套提交代码写草稿与来源记录——状态机只有一份。
4. **server 侧退役**：`/internal/sync`、`SyncTriggerController`、`SyncTriggerGuard`、`SyncConfig`、`SyncSourcesLoader` 与旧的同步配置一起删除；`sync-trigger.yml` 改为驱动 ops。
5. **暴露规则落地**：`/inner` 只在私网连接器上应答、令牌校验、server 安全链默认拒绝（[ADR 0198](../../decisions/0198-api-planes-are-named-by-audience.md)）。
6. **可扩展的运行骨架**：ops 的库与代码按「任务」组织——`job_run` 记一次运行，导入是 `kind=import`；打标、抽样、评测将来只是新的 kind 与新目录，不改导入。
7. **部署**：ops 以 Docker 镜像部署在第二台 ECS；镜像由 CI 交付（ACR 或离线装载）；ops 只绑回环地址，CI 走 SSH 触发。
8. **文档**：本版四件套、ADR 0198 与 0199、`docs/current/` 的现状更新（0196/0197 属下一版）。

### 不做

| 不做 | 理由 | 什么时候可能做 |
|---|---|---|
| 自动打标与标签候选表 | 用户要求先完成平切；打标需要骨架稳定后再做，否则边界一边建一边改 | 下一版（打标版） |
| 在线库 V53（删 outcome_* 七列、收 `skill_label`、移走两张运营表） | 它是打标工作的收尾，不是导入的；现在动会让平切变大 | 下一版，与打标一起做 |
| 金标闸、周抽样、提升 | 都属于打标 | 下一版 |
| 运营界面（管理页） | 本版没有前端；`/admin` 继续拒绝 | `/admin` 有真实运营需求时 |
| skill 评测 | 先把骨架与导入立稳 | 骨架稳定后 |
| 把账号、会话、用户数据搬进 ops | ops 是内容运营，不持有用户 | 不做 |
| 多实例 / 高可用 | 单实例足够；多实例要先解决任务互斥 | 任务量单机跑不完时 |
| 改用户可见的输出形状 | 平切，不改产品行为 | 单开一版 |
| 改标签词表与 13 分类 | 与导入无关 | 词表本身要改时 |

## 拥有面

| 要动的文件 / 面 | 是不是共享面 | 谁在用 |
|---|---|---|
| `skillmaster-ops/**`（新目录：服务、迁移、Dockerfile） | 否 | 本分支新建 |
| `skillmaster-server/.../usecase/sync/**`（抓取与扫描搬走，`SyncDraftWriter` 留下） | 否 | 同步流水线 |
| `skillmaster-server/.../api/SyncTriggerController.java`、`SyncTriggerGuard`、`config/SyncConfig`、`SyncSourcesLoader` | 否 | `/internal/sync` |
| `skillmaster-server/.../config/SecurityConfig.java` | **是** | 全部 HTTP 面；云设备登录分支同时在改 |
| `skillmaster-server/.../config/SkillmasterProperties.java` | **是** | 配置绑定；删 `sync` 会影响启动 |
| `skillmaster-server/.../api/inner/`（新） | 否 | 只有 ops 调 |
| `deploy/**`（新 `deploy/ops/`、server 的私网连接器、nginx） | **是** | 发布脚本 |
| `.github/workflows/sync-trigger.yml`、`server.yml`，新增 `ops.yml` | **是** | CI 与部署 |
| `docs/versions/v69-ops-service/**`、`docs/decisions/0196–0199`、`docs/current/` | **是**（同一份文档） | 所有分支 |

## 多面对应

本版**不新增控制台操作，也不新增用户可见的 CLI 命令**。导入的触发走 `/inner`，调用方是 CI 与 ops 自己的定时任务，不是用户操作——[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 要求的是「控制台操作必有 CLI」，这里没有控制台操作可对应。

## 验收与指标

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | ops 能在 server 停着的情况下启动并连上自己的库 | 只起 ops，`/actuator/health` 为 `UP`；迁移跑完，`job_run` 与 `import_entry` 在 |
| A2 | 一次导入只在 ops 抓取与扫描，server 只收到提交 | 导入跑完，导入不再由 server 抓取（v49 的来源信号刷新仍会调用 GitHub，那是另一件事）；ops 记到 `job_run` 与 `import_entry`；产生的草稿带来源、名字与指纹，与搬迁前同一份来源的结果一致 |
| A3 | server 上不再有 `/internal` 路由 | `POST /internal/sync/run` 返回 404；相关类已删；`sync-trigger.yml` 不再访问 server |
| A4 | `/inner` 从公网不可达 | console 域名的 `/inner/**` 返回 404；8080 上访问 `/inner/**` 也是 404；8081 上无令牌或错令牌返回 401 |
| A5 | 同一来源同一次修订不重复写 | 同一个 `(binding, revision)` 提交两次，返回同一条草稿且 `replayed=true`；server 不新增版本 |
| A6 | 运行可查、互斥可控 | `GET /inner/v1/jobs/runs/{id}` 能取到状态与逐条结果；同类任务正在跑时再触发返回 409 |
| A7 | ops 可在第二台 ECS 独立部署与回滚 | CI 产出镜像在第二台机器上起来、健康检查通过；回滚到上一镜像仍通过；全程不需要连 Docker Hub |
| A8 | 用户可见行为不变 | `/web` 与 `/api/v1` 的输出字段与取值与本版之前一致；控制台与 CLI 的既有测试全绿 |

指标（不设目标值，用来判断搬迁是否健康）：

- **导入成功率**：一次运行里 `ok` / 条目总数。
- **单次运行时长**：从触发到 `finished_at`。
- **重复率**：`replayed=true` 的条目占比——高说明来源没变，属正常。

## 依赖与前置

- [ADR 0198](../../decisions/0198-api-planes-are-named-by-audience.md)（五个 API 面）、[ADR 0199](../../decisions/0199-ops-runs-as-its-own-service-and-database.md)（运营独立服务与独立库）。
- [ADR 0196](../../decisions/0196-label-extraction-reads-public-text-only.md) 与 [ADR 0197](../../decisions/0197-production-tables-hold-only-final-claims.md) 仍然成立，但它们的落地属于**下一版（打标版）**；本版不建候选表、不动在线库标签表。
- 第二台 ECS，且两台机器私网可达：ops 能访问 server 的私网连接器；RDS 白名单放行 ops 的私网地址（本版 ops 只连自己的库）。
- 镜像交付：大陆机器连不上 Docker Hub，走阿里云 ACR 或离线装载。
- 来源白名单在 `deploy/ops/sync-sources.yml`，随 ops 部署进容器挂载目录。
- 云设备登录那条分支同时改 `SecurityConfig.java` 与登录入口；合并时后合的一方手工对一次安全链。
