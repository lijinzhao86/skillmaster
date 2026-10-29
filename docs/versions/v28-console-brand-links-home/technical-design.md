# v28 · 技术设计

> **最后更新**：2026-10-29 · **所属版本**：[v28](README.md)

## 架构

改动只在控制台前端与设计稿，服务端、CLI、数据库都不动。

| 模块 | 改了什么 | 没改什么 |
|---|---|---|
| `skillmaster-console/` | 新增 `src/siteUrl.ts`；`AppShell.tsx` 的品牌 `href` 改读它；`pages/LogoutPage.tsx` 改读同一个模块；`tests/brand.test.tsx` 新增；`tests/app.test.tsx` 的密钥用例加一段品牌断言 | 顶栏的 DOM、类名与样式；`/logout` 的行为；构建的校验规则 |
| `design/` | `README.md` 新增「顶栏的品牌回首页」一节；`src/console/shell/Topbar.tsx` 在替身那行加注释 | 演示件的 DOM 与产物（注释不进产物，重出后逐字节一致） |

**为什么不是「在 AppShell 里再写一遍那个表达式」**：`VITE_SITE_URL` 的规范化（去空格、去尾斜杠、补一个 `/`）与兜底值已经在 `LogoutPage` 写过一次。第二个用的人出现时照抄，就是两个「首页」各自演化；抽成一个纯模块，值是同一个值，错也只错一处（[dev-sop](../../../.claude/skills/dev-sop/SKILL.md) §三：同一段逻辑被第二个文件用到就抽成模块）。

## 数据模型

这一版没有数据模型变化：不写库、不存状态。

唯一与数据有关的形状是**值本身**：

| 东西 | 形状 | 来源 |
|---|---|---|
| `SITE_URL` | 字符串，带尾斜杠的目录地址，例如 `https://www.skillmasterai.com/` | `VITE_SITE_URL`（`.env.production`）；测试与 `npm run dev` 用兜底 `http://localhost:5174/` |

兜底值与规范化的理由写在 `skillmaster-console/src/siteUrl.ts` 的模块注释里，这里不重复。

## 接口

没有新接口，也没有改接口。这一版动的是浏览器里一个 `href`：

| 位置 | 改前 | 改后 |
|---|---|---|
| `AppShell` 的 `a.brand` | `href="/"`（控制台根，也就是「我的 skill」列表） | `href={SITE_URL}`（营销站首页） |
| `LogoutPage` 的「回到首页」 | 页面内本地常量 | `import { SITE_URL } from '../siteUrl'`（值不变） |

**演示件里的同一个位置不跟着改**：`design/src/console/shell/Topbar.tsx` 仍是 `?page=skills`——它是替身，理由写在 `design/README.md` 那一节与源码注释里。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | `siteUrl.ts`、品牌改址、`/logout` 改用同一模块、设计稿规则与替身说明、两条测试 | prd §验收 1–6；`npx tsc --noEmit`、`npx vitest run`、`npm run build`、`console-states --only layout` |

一期交付，没有 P1。

## 开放问题

1. **品牌要不要换成别的出口？** 现在它是「回营销站」。如果将来控制台有了自己的总览屏，品牌该回哪一处？**判据**：等真有那一屏时再定——那时品牌回控制台、另设一个「去网站」的入口，还是反过来，取决于那个屏是不是人回来的默认落点；在那之前不做假设。
2. **新窗口打开要不要作为选项？** 现在是同一个 tab。**判据**：第一位用户说「我想边看控制台边看首页」时再考虑；在那之前，`/logout` 那条同义的出口已经给了先例——同一个 tab。
