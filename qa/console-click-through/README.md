# 控制台点选验收（console click-through）

**一句话**：让无头 Chrome 用真实账号登录真实控制台，按人手的路径把 v65 改过的界面点一遍——搜索结果页头、分类筛选、属性胶囊与 `+N`、信息页顺序、公开标签行、400px 窄屏——每一步断言并截图。

它和 `qa/role-matrix` 分工不同：那边量的是**授权梯子**（哪一档能做什么），这里量的是**这一版改过的界面**。它不在 CI 里跑：需要一套活着的本地栈、一份演示数据和本机已装的 Chrome。

## 先决条件

- 服务端 `:8080`、控制台 Vite `:5173` 都已经起来（`/web`、`/api/v1` 从 5173 代理进去）。
- 一个已经注册的账号（手机号 + 密码），客户端能登进去。
- 演示数据：`seed.sql` 在 `skillmaster_v65` 这类本地库上跑一次（幂等）。它给 8 个公域 skill 写分类、给 3 个写预期结果、给 5 个写属性（含 `prompt-only` / `free` 两个默认值）。
- 本机装了 Chrome；不下载浏览器。

## 跑

```bash
cd qa/console-click-through
npm ci                     # 只装 puppeteer-core，约 5 MB
cd ../..
psql "$SKILLMASTER_DB_URL" -f qa/console-click-through/seed.sql

cd qa/console-click-through
CLICK_PHONE=13900000000 CLICK_PASSWORD='...' npm start
```

可选环境变量：`CLICK_BASE`（默认 `http://localhost:5173`）、`CHROME`（默认 macOS 的 Chrome 路径）、`CLICK_OUT`（截图目录，默认 `artifacts/qa/console-click-through`，已在 gitignore 里）。

退出码 0 表示 30 项检查全过；有失败时仍打印全部结果并以 1 退出。截图六张：搜索结果、分类筛选、属性浮层、信息页、公开标签、400px。

## 它查什么

| 组 | 断言 |
|---|---|
| 登录 | 用账号密码登录，离开登录页 |
| 发现页 | 落地有推荐；搜 `ab-testing` 后页头是「搜索结果 · 按相关度排序」；结果页与分页条没有「N 个结果 / 共 N 个」，发现页 `.count` 为空 |
| 结果行 | 第二行只有作者描述（预期结果不上列表）；属性三枚 `Python / 在线服务 / 付费`、是可点的链接；没多余的 `+N` |
| 分类筛选 | 点「数据分析」→「筛选结果」+ 小字写明货架 +「清除」；只返回该货架；清除回推荐 |
| `+N` | `dispatching-parallel-agents` 四枚收成三枚 + `+1`，浮层里四枚 |
| 默认值 | `brainstorming` 的 `纯提示词` / `免费` 不上结果行 |
| 信息页 | 预期结果出现；描述在它之前；属性在它之后、三枚且可点；兼容性存在时排在两者之间 |
| 公开标签 | 每行是「查看 skill」、无计数、读屏名字带标签名（`seed.sql` 给账号建了一个带公开标签的 skill；没有自己的 skill 时列表为空也算过） |
| 窄屏 | 400px 下 `/discover?tag=data-analysis` 没有横向滚动 |

## 它不替代什么

「测试全绿」不是视觉验收（仓库红线 2）。这组检查证明的是结构、类名、请求与交互；颜色、间距、一屏六行仍要人看截图或在浏览器里看。`seed.sql` 的演示数据是本地造的，真实公域数据的标签要等 P1 抽取回填。
