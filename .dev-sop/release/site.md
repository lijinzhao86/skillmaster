# 站点（`skillmaster-site/`）

Workflow：`.github/workflows/site.yml`。产品形态：`home.skillmasterai.com` 的营销页（v63 起为主入口），
静态文件；`www.skillmasterai.com` 作为独立页面保留同一份构建（步骤见 `deploy/README.md`）。

## 与控制台完全同形

**一处不同：站点 Deploy 是串行的**（`concurrency: site-deploy`，v63）。两次 dispatch 会共用同一个
软链与部署探针，后一次会覆盖前一次、前一次还可能报出一个已经不在服务的版本。控制台没有这一条。

| 现象 | 判据 |
|---|---|
| PR / push 到 main | `Site changes` 判断是否碰了站点；碰了才跑 `Site check`：装依赖 + 测试 + `npm run build`，并在 push 上上传 `dist/` |
| 人手动 `workflow_dispatch` | `Deploy`：要求同一 commit 的 `Site check` 绿了，下载它的 artifact |

部署：**不自己构建**，下载同一 commit 的 `Site check` 产物 → `rsync` 到
`/srv/skillmaster/site-releases/<sha>/` → 切 `/srv/skillmaster/site` 软链 → **主机上**的探针（同样用
`--resolve …:127.0.0.1` 问本机 nginx）→ 不过就切回 → 保留最近 5 个。`workflow_dispatch` 的 `sha` 可以
填更早的绿色 commit，留空就是 dispatch 的 ref 顶端。**这个 commit 必须在 push run 里被 `Site check`
构建并上传过 artifact**——更早的 commit 没有 `site-dist`，会失败；只填完整 40 位 sha。

**下载桶只跟 `main` 顶端走。** 在 `main` 上部署、且部署的是 `main` 的顶端时，这次构建里的 skill 包与
安装文档会发到 `dl:beta/`；用 `sha` 重发旧站点（或从别的 ref dispatch）时，发布步骤被跳过，桶里的两份
文件保持不动，不会跟着页面一起回退。

两件它自己的事：

- **`VITE_CONSOLE_URL` 来自 `skillmaster-site/.env.production`**，而 `vite.config.ts` 没有它就**拒绝构建**。
  这是故意的：站点上所有「去控制台」的链接都从它拼出来，构建期拿不到就会发一页指向不存在机器的链接。
- 站点与控制台是**两条独立的路**：各自的 `* changes` 判断、各自的 `Run workflow`，部署一个不碰另一个，
  也不碰服务端。
