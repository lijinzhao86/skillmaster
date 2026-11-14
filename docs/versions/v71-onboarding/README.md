# v71-onboarding · 安装后走一遍 10 分钟新人引导

> **最后更新**：2026-11-14
> **状态**：进行中（2026-11-14 平台已上线 `skillmaster/skillmaster-startup@2.0.0` 与 `skillmaster-guide@1.1.1`；安装文档与技能包待随仓库合并部署；T1–T16 未全量走查）
> **含收费**：否

<!--
README 是「版本记录主页」，不是详述。每个问题只写一句话 + 链接。
细节一律写在 prd.md / technical-design.md / test-plan.md 里。
-->

## 目标

安装结束后问一次「要不要做新人引导」；选「现在开始」的，Agent 带着走 3 块——① 找公开库、加进库，用自己的话搜一次、加载一次；② 做一条自己的（从头写或从本机导入）并发布上线；③ 本机有技能的话，可选批量搬进来——主线目标 10 分钟内走完（第 3 块另算），全程用带「（推荐）」的选项卡点着走，不用打字；卡片的问句要说清「你在点什么」，走完教他用 `/skillmaster` 直接叫出 SkillMaster（这个 Agent 里没有这个技能就照安装说明补装）。细节见 [`prd.md`](prd.md)。

## 验收标准

一句话摘要：选「以后再说」不加载引导、CLI 可用；选「现在开始」走完 3 块，主线第 2 块只上线 1 次（第 3 块迁移如有上线，另算），中位时长 ≤10 分钟。完整验收见 [`prd.md`](prd.md) §验收与指标。

**发布顺序**：先让平台上 `skillmaster/skillmaster-startup@2.0.0` 上线，再发 `skillmaster-guide` 的 patch，最后部署安装文档与主 skill 包；反过来做，用户会在安装后加载到旧的 1.0.0 正文。

**成立条件**：安装后确实问了一次、引导正文按 3 块排列、第 2 块恰好一次上线——这三条不满足不算交付。

## 这一版明确不做什么

摘要四条；**完整范围与每条的理由在 [`prd.md`](prd.md) §范围**。

- 不新增 CLI 命令、不改控制台 —— 现有命令已覆盖，本版只改内容与安装话术
- 不把公开库的默认使用方式改成「复制」 —— 默认 join；copy 只在写自己的第一条时作为起点
- 不做第二次上线、不记录进度 —— 一次闭环足够，进度按实际状态重算
- 不覆盖云端 / 远程 Agent 的安装 —— 沿用 v26 / v38 的边界

## 与目标态的关系

[`target/`](../../target/README.md) 没有单独定义「新人引导」。这一版保留 v38 的「安装即交接、不写全局记忆」，分叉在交接的正文：`startup` 从「迁移本机技能」换成「3 块引导」，迁移降为可选第 3 块。差异登记在 [`target/README.md`](../../target/README.md) §十一。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、引导的内容要求、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 引导正文的结构、安装文档的改动点、skill 源与发布、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、人工走查用例、环境与数据、结果、已知问题 |
| [`../../decisions/0206-startup-is-the-onboarding-and-joining-is-the-default.md`](../../decisions/0206-startup-is-the-onboarding-and-joining-is-the-default.md) | 为什么把 startup 改成引导、为什么公开库默认 join、为什么只上线一次 |

## 影响最大的未决问题

完整列表见 [`technical-design.md`](technical-design.md) §开放问题。按重要性排前三条：

1. **沙箱里打不开上线确认页怎么办**：第 2 块是唯一一次网页确认，某些 Agent 的沙箱会拦 `open`。倾向用 `skmr version publish --print` 打印链接，请用户在任何浏览器里确认；v68 的云端登录已经有同类做法。
2. **用户的需求搜不到时用哪条兜底**：倾向用「做 PPT」搜到的 `skillmaster/baoyu-slide-deck`（已在线、公开可浏览）；它只需要 join，不需要改。
3. **10 分钟中位时长还没有样本**：测试方案里用 5 位首次使用者走查，记录起止时间与是否完成。
