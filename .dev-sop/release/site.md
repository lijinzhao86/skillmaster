# 站点（`skillmaster-site/`）

Workflow：`.github/workflows/site.yml`。产品形态：`www.skillmasterai.com` 的营销页，静态文件。

## 与控制台完全同形

| 现象 | 判据 |
|---|---|
| PR / push 且 `skillmaster-site/**` 变了 | `Check` job：装依赖 + `npm run build` |
| 人手动 `workflow_dispatch` | `Deploy` job |

部署：构建 → `rsync` 到 `/srv/skillmaster/site-releases/<sha>/` → 切 `/srv/skillmaster/site` 软链 →
**主机上**的探针（同样用 `--resolve …:127.0.0.1` 问本机 nginx）→ 不过就切回 → 保留最近 5 个。

两件它自己的事：

- **`VITE_CONSOLE_URL` 来自 `skillmaster-site/.env.production`**，而 `vite.config.ts` 没有它就**拒绝构建**。
  这是故意的：站点上所有「去控制台」的链接都从它拼出来，构建期拿不到就会发一页指向不存在机器的链接。
- 站点与控制台是**两条独立的路**：各自的 path 过滤、各自的 `Run workflow`，部署一个不碰另一个，也不碰
  服务端。
