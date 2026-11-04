# 服务端（`skillmaster-server/`）

Workflow：`.github/workflows/server.yml`。产物是一个 jar，跑在 ECS 的 systemd 下。

## 四段

| # | 段 | 谁在跑 | 判据 |
|---|---|---|---|
| 1 | **开 PR** | `Test` job | `./mvnw -B verify`：编译 + 单元 + 集成（真 PostgreSQL，`pgvector/pgvector:pg16`）+ 架构规则 |
| 2 | **合并** | 分支保护 | `Test` 绿了才允许合并（main 的 required status check） |
| 3 | **构建** | 合并后自动 | 又是 `Test`（push 上再跑一遍）→ 绿了才 `Build`：打 jar、断言 jar 里**没有** `gateway/`、构建镜像（不 push） |
| 4 | **上线** | **人按下** | `workflow_dispatch` 的 `Deploy` |

## 部署做了什么

`Deploy` 在 runner 上打 jar → 渲染 `/etc/skillmaster/server.env`（仓库的 vars/secrets，唯一来源）→
scp 到 `/opt/skillmaster/releases/<sha>/app.jar` → 切 `/opt/skillmaster/current` → `systemctl restart
skillmaster` → **等 `/actuator/health` 回答**；不回答就切回上一个 release → 修剪旧 release。

## 现状 vs 要做到（2026-10-26 梳理）

| 段 | 今天 | 要做到 |
|---|---|---|
| 1–2 | `Test` 在 PR 上会跑，但 **main 的保护里一条 required status check 都没有**——红了也能合 | 把 job 名 `Test` 配成必需检查；同时要求走 PR |
| 3 | 已经是 `push` → `Test` → `needs: test` 的 `Build` | 多一步：**上传 jar artifact** |
| 4 | `Deploy` **跳过 Test/Build**，**自己重新打 jar**，对同一 sha 的 CI 结论一无所知 | 下载第 3 段的 artifact；没有同 sha 的绿色 `Build` 就直接失败 |

**在配好之前，人肉纪律是：合并后等 `Build` 绿了，再 dispatch。** 2026-10-26 那次就是因为合并 13 秒后
直接 dispatch，生产在测试还没跑完时就被更新了（结果没出事，但顺序是反的）。

## 一个必须先解决的坑：`paths` 过滤 × 必需检查

`server.yml` 只在 `skillmaster-server/**` 等路径变了才跑。一旦把 `Test` 配成必需检查，**只改文档的 PR
不会触发它**，检查会永远停在 "Expected"，谁都合不进去。

解法二选一：加一个**总会跑**的占位 job（不满足条件就直接 success、同名上报），或改用 ruleset 的
required workflow。**先解决它，再开必需检查**，顺序反了会把人堵在门外。

## 回滚

`Deploy` 探针不过就自己切回上一版。**但迁移是唯一回不去的东西**：Flyway 前向执行，回滚 jar 不会回滚
schema——跨了一次加迁移的发布做回滚，先确认旧 jar 能容忍新 schema。这也是第 4 段要人按的原因。

## 为什么不上线自动化

合并 → 测试 → 构建都可以自动；**上线不自动**，因为服务端启动就改数据库，那是不可回滚的。什么时候把库
也改了，必须有人在场。
