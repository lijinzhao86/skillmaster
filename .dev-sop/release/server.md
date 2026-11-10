# 服务端（`skillmaster-server/`）

Workflow：`.github/workflows/server.yml`。产物是一个 jar，跑在 ECS 的 systemd 下。

## 四段

| # | 段 | 谁在跑 | 判据 |
|---|---|---|---|
| 1 | **开 PR** | `Test` job | `./mvnw -B verify`：编译 + 单元 + 集成（真 PostgreSQL，`pgvector/pgvector:pg16`）+ 架构规则 |
| 2 | **合并** | 分支保护 | main 的必需检查全绿才允许合并，清单在下面「合并闸」 |
| 3 | **构建** | 合并后自动 | `Test` → 绿了才 `Build`：打 jar、断言 jar 里**没有** `gateway/`、构建镜像（不 push）、**上传 jar artifact** |
| 4 | **上线** | **人按下** | `workflow_dispatch` 的 `Deploy`：先验同一 commit 的 `Build` 绿了，再下载它的 artifact |

## 合并闸

main 的分支保护在仓库设置里，不在这个文件里。要配的检查名：

`Guards` · `Test` · `Test and vet` · `The update path on Windows` · `Console check` · `Site check`
· `Every run block is valid bash` · `Validate documents`
· `Server changes` · `CLI changes` · `Console changes` · `Site changes` · `Shell changes` · `Docs changes`

**顺序：先合并这次 workflow 改动，确认一个 PR 上六个 `* changes` 都出现，再开必需检查。** 反过来会把
所有 PR 堵在 Expected。

**六个 `* changes` 也在必需清单里。** 检测 job 失败时，重活 job 会被跳过，而 skipped 在必需检查里算通过
——把检测也设成必需，检测挂了就挡住合并，不会静默放行。

**为什么不能用 `paths:` 过滤。** 一个 workflow 如果在 PR 上按路径过滤，只改文档的 PR 就不会产生那个
检查；把它设成必需检查后，那些 PR 会永远停在 "Expected"，谁都合不进去。六个 workflow **在 PR 和 push
上总是启动**，由第一个 job（`Server changes` 等）读
[`.github/scripts/changed-paths.sh`](../../.github/scripts/changed-paths.sh) 判断本次改动是否碰到本模块；
没碰到的重活 job 直接 skipped。GitHub 的必需检查接受 `successful` / `skipped` / `neutral`，所以
「没碰这个模块」不会挡门。

## 部署做了什么

`Deploy` **不构建**。`release: new` 时，它先用 Actions API 找到同一 commit 的 push run，确认其中的 `Build` 结论是
`success`；没有就失败，**在碰服务器之前**。然后下载 `Build` 上传的 jar（`actions/download-artifact`，
跨 run 下载并校验 digest）→ 渲染 `/etc/skillmaster/server.env`（仓库的 vars/secrets，唯一来源）→
scp 到 `/opt/skillmaster/releases/<sha>/app.jar` → 切 `/opt/skillmaster/current` → `systemctl restart
skillmaster` → **等 `/actuator/health` 回答**；不回答就切回上一个 release → 修剪旧 release。

探针通过前还会确认两件事：进程必须是重启后的新 PID；`/opt/skillmaster/current` 必须指向**这一次**的
release（比较软链的原始目标，不做 `readlink -f`，否则 `/opt/skillmaster` 整体搬过家时每次部署都会误判）。
没有第二条，两个并发的部署会让 A 的探针被 B 的进程满足、报成功而线上是 B。Deploy 自己也带
`concurrency: server-deploy`，同一时刻只有一个在跑；GitHub 每个 group 只保留一个等待者，第三次 dispatch
会替换掉等待中的那个，而不是排在它后面。

**`release: new` 只部署有绿色构建的 commit。** 一个只改了文档的合并提交没有 server 构建，想回到线上
那一版，用 `release: <已有 sha>`（jar 已经在主机上）**并且 `confirm_rollback=rollback`**；不填这个确认，
Deploy 在碰服务器之前就失败。job summary 会写明「部署哪个、当前线上是哪个」。

## 回滚

`Deploy` 探针不过就自己切回上一版，并在 job summary 里写「probe failed; returned to …」。手动回滚是
`release: <已有 sha>` + `confirm_rollback=rollback`。

2026-11-11 发生过一次版本错位：v59（`a9eed7e`）的部署还在探针期间（02:06:18），另一个 dispatch 用
`release: 36d0e62df9635af2b44c4064c6a533b1608e35db` 开始了一次回滚；v59 在 02:06:29 报成功，02:06:50
那次回滚的切换才把生产换成 36d0e62，v59 **没有触发自动回滚**。这次是「后一次 dispatch 覆盖前一次」；
相邻的另一种风险是两个并发的部署让先完成那次的探针被后完成那次的进程满足（这次没有发生），软链校验就是
防它。现在三处各防一件事：`concurrency` 防并发、软链探针防「探针被另一个部署满足」、`confirm_rollback`
防误发的回滚 dispatch。备注：闸门只管 dispatch 里填 sha 的回滚；选一个旧 ref 再 `release=new` 也会部署
那个版本的构建，不走确认。

**但迁移是唯一回不去的东西**：Flyway 前向执行，回滚 jar 不会回滚 schema——跨了一次加迁移的发布做回滚，
先确认旧 jar 能容忍新 schema。这也是第 4 段要人按的原因。

## 为什么不上线自动化

合并 → 测试 → 构建都可以自动；**上线不自动**，因为服务端启动就改数据库，那是不可回滚的。什么时候把库
也改了，必须有人在场。
