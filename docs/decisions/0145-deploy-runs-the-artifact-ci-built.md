# 0145 · 上线只部署 CI 构建出的那份产物

- **状态**：已采纳
- **日期**：2026-11-05
- **相关**：[`.dev-sop/release/server.md`](../../.dev-sop/release/server.md)（四段与回滚）、[`docs/current/TODO.md`](../current/TODO.md)（这条欠账的来源）

## 背景

服务端、控制台、站点三段的上线都是人手动 `workflow_dispatch`。直到这次改动之前，`Deploy` 对同一 commit 的 CI 结论一无所知：它 checkout、自己重新构建，再传到主机。2026-10-26 那次合并在 13 秒后就按下了 dispatch，生产在测试还没跑完时就被更新——结果没出事，但顺序是反的。即使等 CI 绿了再按，部署的仍是**第二次构建**：同一份源码、另一台 runner、另一次依赖解析。CI 验证过的字节和上线的字节不是同一份。

当时有两个方向：

1. **保留自己构建，只加一道「这个 sha 的 CI 绿了吗」的查询。** 改动小，但上线字节仍不是 CI 验证的那份。
2. **让构建只发生一次，Deploy 下载它。** 上线字节与验证字节是同一份，代价是 Deploy 依赖 artifact 的存在与保留期。

## 决定

1. **构建只发生在 `Build` / `Console check` / `Site check` 里，产物上传为 artifact。** server 传 jar，console / site 传 `dist/`；只在 `push` 时上传。
2. **`Deploy` 不构建。** 它先用 Actions API 找到同一 commit 的 push run，要求对应 job（`Build` / `Console check` / `Site check`）的结论是 `success`；没有就直接失败，**在碰服务器之前**。
3. **通过后用 `actions/download-artifact` 跨 run 下载**（`run-id` + `github-token`），下载时校验 digest；拿到的就是 CI 验证过的那份。
4. **回滚语义不变。** server 的 `release: <已有 sha>` 仍表示「主机上已经有这个 release，切回去」；console / site 新增 `sha` 输入，可以重新部署一个更早的、在 push run 里构建并上传过 artifact 的完整 40 位 commit sha——更早的 run 里没有 artifact。
5. **`release: new` 的判据变成「这个 commit 有绿色构建」。** 一个只改了文档的合并提交通常没有 server 构建，不能再拿最新 main 硬发。

## 理由

- **为什么否掉「保留重建 + 只查 CI 状态」**：它只保证了「同一份源码」，没有保证「同一份字节」。JDK 补丁版本、Maven 缓存、依赖解析顺序都可能让两次构建不同；而且每次上线要多花一遍构建时间。既然 CI 已经把 jar 打好了，再打一次没有收益。
- **为什么否掉「Build 绿了就自动上线」**：服务端启动时跑 Flyway，schema 迁移不可回滚。上线必须是一个人在场按下的动作，这条理由写在 [`server.md`](../../.dev-sop/release/server.md)「为什么不上线自动化」。
- **为什么否掉「信任操作者，不再校验」**：2026-10-26 的 13 秒 dispatch 说明「靠纪律」会失败。校验发生在部署脚本里，失败在 ssh 之前，代价是零。
- **为什么 console / site 也一起改**：它们的 `Deploy` 同样自己重建；只改服务端等于留下两处会漂的第二构建。

## 后果

- **`release: new` 只能在有绿色构建的 commit 上工作。** 文档合并、或构建产物已过保留期时，`Deploy` 会失败；回到线上那一版要用 `release: <已有 sha>`（server）或 `sha` 输入（console / site）。
- **三个 workflow 的部署 job 需要 `actions: read`**；构建 job 多一步上传，Deploy 少一次构建。
- **artifact 的保留期成为部署能力的一部分。** 保留期由仓库设置决定；超过保留期后那个 commit 不能再被 `new` 部署。
- **console / site 的 `sha` 只能指向本机制启用之后构建过的 commit。** 更早的 run 没有 artifact；撞上时 `Deploy` 在碰服务器之前失败。
- **site 用 `sha` 重发旧站点时不再发布下载桶里的 skill 包与安装文档**；这两份只由 `main` 顶端的构建写。
- **上线字节与 CI 验证字节一致**，这是本决定真正买到的东西。
