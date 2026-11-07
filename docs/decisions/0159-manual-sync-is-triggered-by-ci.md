# 0159 · 手动触发同步只开 CI + 本机回环端点，不做 CLI 与控制台

- **状态**：已采纳
- **日期**：2026-11-06
- **所属版本**：[`v50-open-source-sync`](../versions/v50-open-source-sync/README.md)
- **依赖**：[ADR 0158](0158-sync-runs-inside-the-server.md)（同步在 server 进程内）、[ADR 0081](0081-every-console-operation-has-a-cli-command.md)（控制台操作要有 CLI）

## 背景

同步按周期自动跑；运营有时需要在两次之间「现在就跑一次」。可选的做法有：加一条 CLI 命令并配公开 API、在控制台加按钮、只允许登录服务器执行、用 CI/CD 触发。

2026-11-06 产品负责人明确不要 CLI，希望用轻量的 CI/CD 触发，且不发布服务、只触发。平台现有的部署链路已经用 GitHub Actions 通过 SSH 操作服务器，CI 触发可以复用这条链路，不必再开一个公开鉴权面。

## 决定

1. **手动触发只由 GitHub Actions 的 `workflow_dispatch` 发起。** 不加 CLI 命令，不加控制台按钮。
2. **工作流经现有部署 SSH 登录服务器，调本机回环端点** `POST /internal/sync/run`，带专用请求头 `X-Sync-Trigger`。
3. **端点不对公网开放。** nginx 对 `/internal/` 返回 404；token 为空时没有 guard，`POST` 返回 404（控制器仍随同步启用而注册）。token 与部署密钥分开，单独轮换。
4. **同一时刻只允许一次运行。** 已有一个在跑时，第二次返回 409 `sync_already_running`。
5. **触发只跑同一条 pipeline。** 不发布、不重启、不改变可见性；手动触发与定时任务的行为完全一致。

## 理由

- **CI 已经有服务器的入口与审计。** 部署工作流用 `DEPLOY_HOST` / `DEPLOY_USER` / `DEPLOY_KEY` SSH 到 ECS；手动触发沿用同一套，不新增公开面。
- **回环端点比公开 API 小。** 公开 API 需要一个长期账号 key（能发布、能删除的权限），比一个只触发同步的 token 危险得多。
- **比「登录服务器敲命令」可复核。** CI 的运行记录自带触发人、时间与输出，不需要另写审计。
- **比触发文件直观。** 文件触发没有状态码与摘要；HTTP 端点能返回逐条结果，失败也能在 CI 里看到。

被否掉的：**CLI 命令 + 公开 API**（产品负责人不要，且需要长期账号 key）；**控制台按钮**（要设计稿，产品负责人也不要）；**只允许登录服务器执行**（运营要维护 SSH，且没有运行记录）；**触发文件**（延迟与可观测性都差）；**重启服务来触发**（会打断线上，且不是「只触发」）。

## 后果

- 部署流程要多渲染一个 `SKILLMASTER_SYNC_TRIGGER_TOKEN`，并在 nginx 配置里加一条 `/internal/` 拒绝规则。
- GitHub Actions 多一个 `sync-trigger.yml` 工作流；它不构建、不发布、不重启，只发一个 HTTP 请求。
- token 只存在 GitHub secret；轮换时改 secret 并重新部署，`server.env` 由部署流程渲染，主机上手改的不保留，旧值随部署失效。
- CI 请求超时（如 20 分钟）不代表任务停止；任务仍在进程内跑，结果看服务器日志。
- 将来若要给运营做界面，手动触发可以随同步管理页一起升级为控制台操作 + CLI，那时需要新 ADR 推翻本决定。
