# v28 · 测试方案

> **最后更新**：2026-10-29 · **所属版本**：[v28](README.md)

## 验收映射

| prd 验收 | 用例 |
|---|---|
| 1 点品牌回到营销站 | T1 |
| 2 一个值两个用的人 | T2 |
| 3 构建仍然拒收缺值 | T3 |
| 4 守卫仍然拦住品牌 | T4 |
| 5 演示件不变 | T5 |
| 6 视觉没有变化 | T6（人工） |
| 7 品牌有可访问名称 | T7 |

## 用例

| # | 前置 | 步骤 | 期望 |
|---|---|---|---|
| T1 | — | 渲染 `AppShell`，读 `a.brand` 的 `href`；`npm run build` 后在 `dist/` 里 `grep -o 'https://www.skillmasterai.com[^"]*'` | 等于 `SITE_URL`（测试环境是兜底 `http://localhost:5174/`）；产物里出现的是不带斜杠的 origin（斜杠由运行时的 `siteUrl.ts` 补）；链接文字仍是 `SkillMaster` |
| T2 | — | `grep -rn "VITE_SITE_URL" skillmaster-console/src` | 只命中 `src/siteUrl.ts` 一个文件（模块注释与那一行常量）；`AppShell` 与 `LogoutPage` 都只 import `SITE_URL` |
| T3 | 一份没有 `VITE_SITE_URL` 的环境 | `npm run build`；再带上变量跑一次 | 缺值那次构建失败并报 `VITE_SITE_URL must be set`；带上 `.env.production` 的那次成功 |
| T4 | 登录后进 `/account/keys`，新建一把密钥、**不复制** | 点左上角品牌 | 出现「这把密钥还没复制——离开后取不回来，只能撤销后重建。」——**问题块重新出现才是被拦住的证据**（那块刚被答掉过一次）；`location.assign` 未调用只是顺带，jsdom 的锚点跳转本来就不走它 |
| T5 | 干净的 `design/` | `npm run build:console`；再把源重出一份到 `/tmp` 与产物 `diff`；跑 `node design/scripts/console-states.mjs "design/skillmaster-控制台演示.html" design/shots/console --only layout` | 两份逐字节一致；检查 `failed: 0`、`problems: []` |
| T6 | — | 人打开控制台（或演示件），对照 `.brand` / `.brand-name` 的 CSS 看顶栏 | 标记 `19×19`、字号 `15px/600`、字距 `0.04em`、颜色、悬停态与改前一致；只有去向变了 |
| T7 | — | 读 `a.brand` 的 `aria-label`；再看 640px 以下的规则 | 等于「SkillMaster 首页」，且含可见文字「SkillMaster」；窄屏仍然只留标记（视觉不动） |

## 环境与数据

- **控制台**：`cd skillmaster-console && npx tsc --noEmit -p tsconfig.json && npx vitest run`。测试里没有 `VITE_SITE_URL`，所以看到的是兜底值——与 `tests/logout-page.test.tsx` 同一个口径。
- **构建**：`npm run build` 会读 `.env.production`（`VITE_SITE_URL=https://www.skillmasterai.com`）；`vite.config.ts` 在 `build` 时校验它。
- **设计件**：`cd design && npm run build:console`；检查脚本按组跑，本版只跑 `layout`。

## 结果

2026-10-29，同分支实测：

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `tests/brand.test.tsx`（`a.brand` 的 `href` 与文字）；产物里 `grep` 得到 `https://www.skillmasterai.com` |
| T2 | 通过 | `grep` 只命中 `src/siteUrl.ts`；`LogoutPage` 的本地常量已删除 |
| T3 | 通过 | 带 `.env.production`：`npm run build` 成功；在临时副本里去掉该文件：构建失败，报 `Error: VITE_SITE_URL must be set for a build — see README.md.`（2026-10-29 实跑） |
| T4 | 通过 | `tests/app.test.tsx` 的密钥用例新增一段：答掉问题后点 `a.brand`，问题块**重新出现**（`.key-exit .link-button` 不为空）、`location.assign` 未调用 |
| T5 | 通过 | 重出后 `diff` 一致；`console-states --only layout` `failed: 0`、`problems: []` |
| T6 | **待人工** | 没有视觉变化（只改 `href` 与一个属性）；仍需人打开看一眼 |
| T7 | 通过 | `tests/brand.test.tsx` 断言 `aria-label` 与「含可见文字」；`design/src/console/styles.css` 的 ≤640px 规则未改 |

**全量**：`npx vitest run` 31 个文件、585 条全过（本版新增 `brand.test.tsx` 1 条，另在既有密钥用例里加了一段断言）；`npx tsc --noEmit` 无输出；`npm run build` 成功；演示件重出后与源逐字节一致，`console-states --only layout` `failed: 0`。

## 已知问题

| # | 问题 | 状态 |
|---|---|---|
| 1 | 测试环境里 `SITE_URL` 是兜底值 `http://localhost:5174/`，不是生产地址 | 设计如此：生产地址由构建时校验，测试只验「谁读它」与「读到了什么形状」；产物那一半由 T1 的 `grep` 补上 |
| 2 | 营销站在大陆访问时 TLS 会被重置（`www.skillmasterai.com` 与 `dl.` 同因） | **已复现**（2026-10-29 本机实测：HTTPS 连接重置、HTTP 返回 ICP 提示页）。不是本版代码引入的，也不在本版修；记在这里是因为**品牌现在指向那里**，它直接决定这一版对一部分用户有没有效果 |
