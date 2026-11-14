# v69-ops-service · 技术设计

> 本版 TD 只写**与 [`docs/current/`](../../current/README.md) 的差异**（v40 起的规则）。决策理由见 ADR，不在这里复述。
> 本版是**平切**：只搬自动导入，行为不变；打标与清库属于下一版。

## 架构

### 系统上下文

```
        第二台 ECS（新）                              第一台 ECS（现状）
┌──────────────────────────────┐            ┌───────────────────────────────┐
│ skillmaster-ops（Docker）     │            │ skillmaster-server（systemd）  │
│  127.0.0.1:8090  ← CI 走 SSH  │  私网      │  127.0.0.1:8080  ← nginx       │
│  调度 / 导入 / 运行记录        │  :8081 ──► │  私网IP:8081     ← 只有 ops    │
│  自己的库 skillmaster_ops     │  /inner    │  在线库（RDS）                 │
└──────────────┬───────────────┘            └───────────────────────────────┘
               │
               └── 公网：抓取 GitHub 等公开来源
```

- 为什么两个进程、两个库、两台机器：[ADR 0199](../../decisions/0199-ops-runs-as-its-own-service-and-database.md)。
- 为什么互通只在 `/inner/v1`：[ADR 0198](../../decisions/0198-api-planes-are-named-by-audience.md)。

**外部依赖有三处**：抓取公开来源（公网）、提交草稿（server 私网）、自己的库（RDS）。第二台机器不连**在线库**，但连同一台 RDS 上的 `skillmaster_ops`。

### 分层与依赖方向

```
api ──► jobs ──► imports（纯领域：来源、扫描、许可证）
 │        │            ▲
 │        └──► ports ◄──┘        ports = 两个接口
 └────────► server / store（适配器，实现 ports）
```

| 层 | 放什么 | 不许做什么 |
|---|---|---|
| `api` | 触发控制器、令牌过滤器、请求/响应 DTO | 不含业务判断 |
| `jobs` | 任务编排：取锁、展开条目、分批、写运行记录 | 不直接发 HTTP、不写 SQL |
| `imports` | 来源抽象、条目展开、扫描规则、许可证判断、组包规则 | **不 import Spring、JDBC**；HTTP 只出现在来源适配器（`GitHubContentReader`） |
| `ports` | `SubmissionPort`、`RunStore` | 只有接口与记录类型（`ContentSource` 是导入自己的端口，在 `imports/`） |
| `server` / `store` | 实现 ports：HTTP 客户端、JdbcClient 仓储 | 不反向依赖 `jobs` |

强制：ops 自己的 `ArchitectureTest` 断言 `imports` 不依赖 `org.springframework.*` 与 SQL，并断言 `jobs` 不直接使用 `java.net.http.*`（抓取在 `imports` 的适配器里）。**强度是 lint**：它能证明 import 方向，不能证明运行时没绕。

`imports` 里的 **`ContentSource` 是端口**：今天只有 `GitHubContentSource`，将来加一个来源只加一个适配器，`ImportJob` 不改。这是本版为「可扩展」付的那一份钱。

### 领域词

| 词 | 含义 | 代码名 |
|---|---|---|
| 来源条目 | 配置里的一行，指向一个来源里的一个目录或名字 | `SyncEntry` |
| 展开 | 一行配置变成多个可处理单元 | `SyncExpansion` |
| 条目 | 一次运行处理的一个 skill 候选 | ops 内部的 `Ready` / `Blocked` / `Failed`；server 侧的 `PreparedEntry` 由 inner 端点重建 |
| 来源绑定 | 来源的种类、定位与子路径；判断「是不是同一个来源」靠它 | `SourceBinding` |
| 修订 | 上游的一次提交标识 | `revision` |
| 平台名 | 收录后在平台上的名字（可能与上游不同） | `platformName` |
| 运行 | 一次导入任务的执行记录 | `job_run` |
| 条目结果 | 一个条目这次处置的结果：写入 / 重放 / 阻断 / 失败 | `import_entry` |
| 草稿 | server 侧写入的未发布版本 | `draft_id` |

### 运行模型（本版的可扩展点）

`job_run` 是**通用**的：`kind` 记 `import`，将来加 `label` / `sample` / `evaluate` 不需要新表、也不需要改导入。互斥按 `kind` 分：同一类任务同时只允许一个，不同类可以并行——将来打标天天跑时不至于被一次长导入挡住。

`params` 与 `summary` 都以文本存 JSON：前者留给将来按条件跑（`trigger` 是单独一列，所以现在写空对象），后者记逐条统计。**不加列**，加字段。

## 数据模型

### 运营库 `skillmaster_ops`（本版三张）

| 表 | 一行是什么 | 关键列与约束 |
|---|---|---|
| `job_run` | 一次导入运行 | `id`、`kind`、`trigger`（`manual`/`schedule`）、`status`（`running`/`ok`/`partial`/`failed`）、`params` TEXT（JSON）、`summary` TEXT（JSON）、`started_at`、`finished_at`、`error`；索引 `(kind, started_at)` |
| `import_entry` | 一个条目的处置结果 | `run_id` FK 级联、`binding` TEXT（JSON）、`revision`、`platform_name`、`skill_id`、`draft_id`、`result`（`created`/`replayed`/`blocked`/`failed`）、`note`、`created_at`；唯一 `(run_id, binding, revision)` |
| `job_lock` | 按任务类的互斥 | `kind` 主键、`holder`、`acquired_at` |

**互斥**：`SELECT … FROM job_lock WHERE kind = ? FOR UPDATE NOWAIT`，拿不到就返回 409。锁在事务里，进程崩溃由连接断开释放，不留下「永远 running」的锁。

**幂等**：`(binding, revision)` 由 server 判定——同一个修订重提交返回同一条草稿。ops 记 `result='replayed'`，不自己判重；`import_entry` 的唯一键只防同一次运行里重复展开。

**隔离级别**：默认 read committed 够用——写运行记录与条目结果没有跨行不变量；唯一的「先查后写」是锁，已经显式加了 `FOR UPDATE`。

### 在线库

**本版不动。** V53（删 `skill.outcome_*`、收 `skill_label`、移走两张运营表）属于下一版。

## 接口

### 1. `POST /inner/v1/sync/submissions`（server 上）

`multipart/form-data`，两段：

- `metadata`（`application/json`）：

```json
{
  "binding": {"kind": "github", "locator": "owner/repo", "path": "skills/foo"},
  "revision": "9f86d081...",
  "platform_name": "foo",
  "license": {"spdx": "MIT", "name": "MIT License", "copyright": null,
              "status": "DECLARED", "text": null, "path": "LICENSE",
              "url": "https://...", "detail": "{}"},
  "signals": "[{...}]"
}
```

- `archive`（`application/zip`）：根目录为 `platform_name`，文件相对路径与上游条目目录一致。

| 码 | 形状 | 什么时候 |
|---|---|---|
| 200 | `{"skill_id": "…", "draft_id": "…", "created": true, "replayed": false}` | 写入成功 |
| 400 | `{"error":{"code":"invalid_submission","message":"…"}}` | zip 结构、名字或平台名校验失败 |
| 401 | `{"error":{"code":"unauthorized"}}` | 令牌缺失或错误 |
| 409 | `{"error":{"code":"origin_conflict"}}` | 同一 skill 已被另一个来源占用 |

server 侧实现：`SyncDraftWriter` 保留且签名不变，`InnerSyncController` 把 inner 请求重建为 `PreparedEntry` 再调用它；`submit.submit(...)`、`AuthenticatedSubject.ofService(...)`、`sources.recordSynced(...)` 与 `discardOlderDrafts(...)` 全部复用。

### 2. ops 的触发接口（ops 自己，只绑回环）

| 路由 | 作用 | 响应 |
|---|---|---|
| `POST /inner/v1/jobs/import/runs` | 触发一次导入，可带 `{"trigger":"manual"}` | `202 {"run_id":"…","status":"running"}`；已在跑 `409 busy` |
| `GET /inner/v1/jobs/runs/{id}` | 查一次运行 | 运行状态 + 逐条结果摘要 |
| `GET /inner/v1/jobs/runs?kind=import&limit=20` | 最近运行 | 列表，`limit` 上限 50 |

路径按 `kind` 分段（`jobs/import/runs`），将来加 `jobs/label/runs` 不改这一段的路由结构；`GET /jobs/runs/{id}` 返回运行行与 `entries` 逐条结果。

### 3. 暴露规则

- server：`/inner/**` 只在 8081 上应答、要求 `X-Inner-Token`（常数时间比较）；8080 上的 `/inner/**` 返回 404，8081 上的非 `/inner` 路径也返回 404。实现用 Tomcat 附加连接器 + 按 `getLocalPort()` 判断的过滤器。
- ops：只绑 `127.0.0.1:8090`，安全组只开 22；没有公网面。
- `SecurityConfig` 改默认拒绝：`anyRequest().authenticated()` → `denyAll()`，公开例外逐条列（[ADR 0198](../../decisions/0198-api-planes-are-named-by-audience.md) §决定 7）。

## 失败与降级

| 出站调用 | 超时 | 重试 | 断流 |
|---|---|---|---|
| 抓取来源（GitHub） | 连接 3s、接口 10s、仓库归档 600s、解包 60s | 不做重试；下一次运行重来 | 不做断路器；失败按条目记 `failed`，整轮没有成功条目时运行标 `failed` |
| 提交草稿（server inner） | 连接 3s、读取 15s | **不重试**（写入非幂等风险由 server 的 revision 幂等兜底；重试交给下一次运行） | 同上 |
| 许可证与扫描 | 纯本地，无网络 | — | — |

- 单个条目失败不中断整批：`status` 记 `partial`，失败条目留原因。
- 触发先写 `job_run` 并回 202，条目结果随后逐条写库；进程在运行中被杀，已写入的条目保留，运行停在 `running`（下一轮触发时锁已释放，可直接重跑）。
- 健康检查：`/actuator/health` 探库；不探 server——ops 起得来与 server 在不在是两件事。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P1 骨架与边界** | `skillmaster-ops` 工程、自有库迁移、`job_run`/`import_entry`/`job_lock`、触发接口与令牌、ops 的 `ArchitectureTest` | A1、A6（互斥部分） |
| **P2 搬导入** | 抓取、扫描、许可证、组包搬进 `imports/`；server 的 `/inner/v1/sync/submissions`；server 删 `/internal` 与 sync 配置 | A2、A3、A5 |
| **P3 切换与部署** | server 私网连接器与默认拒绝；ops 镜像与第二台 ECS；`sync-trigger.yml` 改指向 ops；旧触发下线 | A4、A6、A7、A8 |

三期都合在同一条分支、同一个 PR；分期是为了每期都能单独验证，不是为了拆 PR。

**竖切（先证明边界通）**：P1 结束就用一个假来源跑通「条目 → `SubmissionPort` → 假 server」；P2 一开始把假 server 换成真的 server，一个条目端到端先绿，再批量。

## 开放问题

1. **来源白名单放哪？** 当前倾向：`deploy/ops/sync-sources.yml`，由运营者拷进主机挂载目录（拷贝动作见 [`deploy/ops/README.md`](../../../deploy/ops/README.md)），不进镜像。回答方式：改来源要不要重新构建镜像——不该要。
2. **镜像仓库用阿里云 ACR 还是离线装载？** 当前倾向：ACR 优先（第二台机器同区，拉取快）；ACR 没开通就退回 `docker save` + `scp` + `docker load` 的脚本。回答方式：阿里云账号下有没有可用的 ACR 命名空间。
3. **切换时要不要保留旧路径一版（双跑）？** 当前倾向：不双跑。触发点只有 CI 一处，回滚是「把 workflow 指回旧 server + 旧镜像」；双跑会让两个导入同时写草稿，反而要处理来源冲突。回答方式：切换当天能不能接受一次导入失败后重跑。
4. **`imports` 是否要复用 server 的 `SkillNameTransform`？** 当前倾向：不复用、也不搬——改名留在 server 的 ingest 那一步，ops 只负责把上游名字报上去；`platform_name` 由 server 校验。回答方式：出现第二个来源时，看改名规则是否会分叉。
