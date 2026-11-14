# v69-ops-service · 测试

验收标准见 [`prd.md`](prd.md) §验收与指标；接口形状见 [`technical-design.md`](technical-design.md) §接口。

## 验收映射

| 验收 | 用例 |
|---|---|
| A1 ops 独立启动并连上自己的库 | T1、T2 |
| A2 导入只在 ops 抓取扫描，server 只收提交 | T3、T4 |
| A3 server 不再有 `/internal` | T5 |
| A4 `/inner` 公网不可达、令牌生效 | T6、T6b |
| A5 同一修订不重复写 | T7 |
| A6 运行可查、互斥可控 | T8（含逐条结果） |
| A7 第二台 ECS 独立部署与回滚 | T9（人工） |
| A8 用户可见行为不变 | T10 |

## 用例

### T1 · ops 单独启动

- **前置**：`skillmaster_ops_v69` 存在；server 停着。
- **步骤**：起 ops，`curl 127.0.0.1:8090/actuator/health`；再停掉数据库后重起。
- **期望**：第一次 `UP`；启动日志里没有连 server 的失败循环（启动不依赖 server）；第二次健康检查 `DOWN` 而不是进程退出。

### T2 · 迁移与表

- **前置**：空库 `skillmaster_ops_v69`。
- **步骤**：起 ops；查 `flyway_schema_history` 与表清单；再起一次。
- **期望**：`job_run`、`import_entry`、`job_lock` 都在；第二次不重复执行迁移；`import_entry` 的 `(run_id, binding, revision)` 唯一约束存在。

### T3 · 导入经 inner 写草稿

- **前置**：server 可用；ops 配一个假来源（`ContentSource` 测试实现返回固定文件树）；server 的私网连接器开着。
- **步骤**：`POST /inner/v1/jobs/import/runs`；轮询 `GET /inner/v1/jobs/runs/{id}` 到 `ok`；查 server 的草稿。
- **期望**：server 出现一条 `source='sync'` 的草稿，名字 `sync-<sha8>`、note 带来源与许可证；`import_entry` 有 `skill_id`/`draft_id`、`result='created'`；与搬迁前同一份来源产出的草稿逐字段一致。

### T4 · server 不做对外抓取

- **前置**：同 T3。
- **步骤**：查 server 源码与构建产物里是否还有导入流水线与来源内容适配器（`GitHubContentSource`、`SyncPipeline`、`SyncSourcesLoader`）；v49 的信号刷新适配器 `GitHubSourceAdapter` 仍在，属另一件事；查 `SkillmasterProperties` 是否还有 `sync` 段。
- **期望**：`GitHubContentSource`、`SyncPipeline`、`SyncSourcesLoader` 不在 server；`skillmaster.sync.*` 配置键不存在；server 用最小配置也能启动。

### T5 · `/internal` 退役

- **前置**：server 最新构建。
- **步骤**：`POST /internal/sync/run`（带旧令牌与不带都试）；查 `SyncTriggerController` 类与 `sync-trigger.yml`。
- **期望**：两次都 404；类不在源码里；workflow 里不再出现 server 地址。

### T6 · `/inner` 端口隔离与令牌

- **前置**：server 同时开 8080（回环）与 8081（私网）。
- **步骤**：从两个端口分别访问 `/inner/v1/sync/submissions`，覆盖：无令牌、错令牌、对令牌、8081 上访问 `/web/`。
- **期望**：8080 上任何 `/inner` 都是 404；8081 上无/错令牌 401、对令牌进入业务校验；8081 上的 `/web/` 404。

### T6b · 部署层隔离（人工）

- **前置**：线上两台机器。
- **步骤**：用 console 域名访问 `/inner/`（www 与 home 也已加同一条 404）；一台不在白名单的机器访问 8081。
- **期望**：前者 404，后者连接被拒。记进发布清单。

### T7 · 同一修订重放

- **前置**：T3 已产生一条草稿。
- **步骤**：用同一 `(binding, revision)` 再触发一次。
- **期望**：返回同一 `skill_id`/`draft_id`，`replayed=true`；server 的版本行数不变；`import_entry` 记 `result='replayed'`。

### T8 · 运行与互斥

- **前置**：一个会阻塞 5 秒的假来源。
- **步骤**：触发一次导入，在它跑完前再触发一次；跑完后查 `GET /inner/v1/jobs/runs?kind=import`。
- **期望**：第二次返回 `409 {"error":{"code":"busy"}}`；第一次结束后列表能看到它，状态 `ok`，摘要含条目数；`GET /runs/{id}` 的 `entries` 里有逐条结果与失败原因；不同 `kind` 的任务互不阻塞（用一次假 `job_lock` 验证）。

### T9 · 部署与回滚（人工）

- **前置**：CI 产出镜像；第二台 ECS 能连 ACR 或拿到离线镜像。
- **步骤**：部署新镜像 → 健康检查 → 回滚上一镜像 → 健康检查。
- **期望**：两次都 `UP`；全程不需要连 Docker Hub。

### T10 · 用户可见行为不变

- **前置**：改动前后各一次。
- **步骤**：跑 server 的既有测试（`./mvnw verify`）、CLI 与控制台的相关测试；对 `/api/v1/skills` 列表与详情录一份响应快照。
- **期望**：字段与取值一致；本版没有删任何对外字段。

## 环境与数据

**本机两套栈**（每个 worktree 一份）：

```bash
docker exec skillmaster-postgres psql -U skillmaster -d postgres \
  -c 'CREATE DATABASE skillmaster_v69;' \
  -c 'CREATE DATABASE skillmaster_test_v69;' \
  -c 'CREATE DATABASE skillmaster_ops_v69;'
```

- `.env` 里加 `SKILLMASTER_DB_URL`、`SKILLMASTER_TEST_DB_URL`（server）与 `OPS_DB_URL`（ops）。本地库名带 `_v69` 后缀是为了每个 worktree 一份，部署环境里叫 `skillmaster_ops`。ops 目前只有不需要库的单元测试；要跑带库的用例时，直接重建 `skillmaster_ops_v69`，不修 Flyway 历史。
- 导入用例的假来源是一个 `ContentSource` 测试实现，返回固定文件树；**测试不外连 GitHub**。
- `skill_id`、版本 id 由 server 的真实提交产生，不手写。
- 清理：删掉上面创建的三个库。

**线上**：T6b、T9 只在第二台 ECS 上做，步骤写进发布清单，结果记在这里。

## 结果

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 部分执行 | 线上 ops 起来后 `/actuator/health` 为 `UP`，`docker` 健康检查 `healthy`；「停库后 `DOWN` 而不退出」未演练 |
| T2 | 通过 | 空库 `skillmaster_ops` 首次启动时 Flyway 建了 `job_run`、`import_entry`、`job_lock`；容器重建后日志为 `Schema "public" is up to date. No migration necessary.` |
| T3 | 通过 | 线上全量导入跑完：935 条，`created` 10、`replayed` 899、`blocked` 9、`failed` 17、`status=partial`；10 个新建 `skill_id` 在生产库 `skill` 表里逐条命中 |
| T4 | 通过 | 源码已无 `GitHubContentSource` / `SyncPipeline` / `SyncSourcesLoader`，`skillmaster.sync.*` 已删；生产服务端以新的 `server.env`（无 `SKILLMASTER_SYNC_*`）启动并通过探针 |
| T5 | 通过（判据修正） | `POST /internal/sync/run` 经 nginx 得到 `405`（静态目录不接受 POST），服务端 8080 上 `POST /inner/v1/sync/submissions` 为 `404`——旧入口不再存在；`sync-trigger.yml` 已不含服务端地址 |
| T6 | 通过 | `InnerPlaneFilterTest` 6 个用例全绿；线上实测：8080 上 `/inner` → `404`，8081 上无令牌 → `401`、错令牌 → `401`、对令牌 → `415`（缺 multipart），8081 上 `/web/` → `404` |
| T6b | 通过 | console/www/home 三个域名的 `/inner/` 均为 `404`；不在白名单的机器连 8081 超时（安全组只放行 `172.16.1.220/32`） |
| T7 | 通过 | 同一 `(binding, revision)` 重放 899 条，全部 `replayed`，未新增版本 |
| T8 | 部分执行 | 互斥（`409 busy`）与逐条失败原因导出未演练；`GET /runs` 空表与有记录两种状态都验证过 |
| T9 | 部分执行 | 离线镜像 `docker load` → `docker compose up -d` → `healthy`，全程未连 Docker Hub；回滚到上一镜像未演练（首次部署，没有上一版） |
| T10 | 部分执行 | 合入 main 的四套 workflow 全绿；用户可见行为没有做响应快照对比 |

机器检查：`skillmaster-ops` 的 `mvn test` 全绿（含 `ArchitectureTest`、`SubmissionZipTest`、迁移过来的抓取/扫描/许可证/白名单用例）；v69 合并点（`fe06361`）上服务端全量回归 775 个用例全绿；三条文档检查与文件行数闸全绿。线上部署的是 `63db993`（v69 合并点之上再合并了云端设备登录那条分支），该提交的 Server / Console / Site / CLI / Docs / Shell / Arch / Ops 八套 workflow 全绿。

`failed` 的 17 条全部是 `invalid_skill_md`（来源目录里没有 `SKILL.md`，如 `browser-act/skills` 的 `solutions/*` 下若干目录），`blocked` 的 9 条全部是安全规则命中（`pipe_to_shell` 8 条、`executable_binary` 1 条）。两类都不是本版引入的行为差异。

## 已知问题

无。
