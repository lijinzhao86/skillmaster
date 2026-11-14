# 0207 · 网关配置随服务端部署一起下发

> **状态**：已采纳（2026-11-14）

## 背景

生产机的 nginx 配置（`deploy/nginx/skillmaster.conf` 与 `nginx.service.d/restart.conf`）一直是
**人工拷上去**的：仓库里有源文件，`ops-check.yml` 每天比对一次主机与仓库是否一致，但没有任何部署
流程负责把它送上去。

2026-11-14 这个缺口真的咬了人。v68 给 nginx 加了一个反代前缀 `cli-device-sign-ins`（CLI 设备批准
登录的页面）；服务端按 Server 部署上线了，nginx 配置没有跟着上，第一次 `skmr login` 直接拿到
nginx 的 `405` 而不是服务端的页面。它的表现是**一个错误答案而不是一个报错**：配置文件缺前缀时，
请求落到静态站点的 `try_files`，页面把 `index.html` 当作 ticket 读取，于是提示「链接已经用过或
过期了」。只有真走一遍登录流程才发现。

当时有三个选项：

1. **保持人工拷贝**，靠发布清单和 `ops-check.yml` 兜底。
2. **单开一个 Gateway workflow**，发布时多按一次。
3. **并进服务端部署**：`Deploy` 在切 jar 之前先下发并校验 nginx 配置。

## 决定

采用第 3 条。`.github/scripts/install-gateway.sh` 在两个 nginx 文件上与主机比对，不同才替换；
替换后跑 `systemctl daemon-reload && nginx -t`，**不过就还原并让这次部署失败**；通过才
`systemctl reload nginx`。这一步在切 `/opt/skillmaster/current` **之前**执行。

两条细节是刻意的：

- **内容相同也照样校验并 reload。** 上一次可能装上了却没 reload（reload 失败、或脚本在装完与
  校验之间断掉）。把「内容相同」当成「已经生效」，就会在 nginx 还跑着旧配置时报告成功——那正是
  这个脚本要防的事。
- **部署自己输入检查在网关之前。** `release` 格式、`confirm_rollback` 这些判据从 `Deploy` 步
  提前成独立一步：一次会被拒绝的 dispatch 不该先把生产 nginx 改掉。

`deploy/nginx/` 因此归入 server 模块：改它的 PR 会跑服务端的检查（`changed-paths.sh`），
`deploy/sync-sources.yml` 不再算服务端的改动（v69 已把它移交给 ops）。

## 理由

- **第 1 条已经被证伪。** 人记得住的步骤，在「这次没改 nginx」的部署里最容易被跳过；而
  `ops-check.yml` 是每天一次的事后探测，不是发布闸。发现得晚，代价是用户侧一个说不清的失败。
- **第 2 条只是把「忘拷」换成「忘按」。** 多一个必须人记得按的按钮，和原来犯的是同一个错。
- **第 3 条不增加发布时的人为步骤**：发布本来就要按 Server 的 `Deploy`，nginx 是那次部署的一部
  分。顺序放在切 jar 之前，是因为网关坏掉时线上还在跑旧 release——部署失败而生产不受影响，比
  「新 jar + 旧网关」的半截状态好判断。
- **内容相同也照跑校验与 reload，只是不重写文件。** 把「内容相同」当成「已经生效」，会在上一次
  reload 失败时把旧配置继续当作新配置报告成功；reload 本身是平滑的，代价远小于这个错误答案。
  job 日志区分 `gateway unchanged` 与 `gateway installed`，所以「这次没改网关」仍然一眼可见。

## 后果

- `Deploy` 多一个 `Gateway` 步；`release: <sha>` 的回滚同样会先下发当前仓库的 nginx 配置（回滚
  jar 不回滚网关，这一点与 Flyway 迁移同类）。
- 仓库里的 nginx 配置写坏了，会**在切 jar 之前**让部署失败。这是有意的：语法错误应该在发布时被
  拦住，而不是等某次 reload 把站点打挂。
- `ops-check.yml` 的每日比对保留，角色从「唯一的发现手段」变成「兜底漂移检测」。
- 手工拷贝仍然保留在 `deploy/README.md` 的「P0 · home 上线」第 3 步与 §Certificates，用于改主机名、
  Actions 不可用等场景。
- **指名 release 是否在主机上，仍由 `Deploy` 步检查**（它要连主机），所以「release 已被修剪」这种
  失败发生在网关就位之后；此时网关就是仓库当前那份、已通过 `nginx -t`，不影响线上。
- **主机上 nginx 停着时，部署会失败**：`systemctl reload` 返回非零，脚本按失败处理并还原。这是有意的
  ——让一个本来就坏掉的网关以「部署失败」暴露，而不是让部署绿着、下次重启才发现。
