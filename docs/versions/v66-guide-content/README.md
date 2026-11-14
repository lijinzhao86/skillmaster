# v66-guide-content · 让 Guide 重新覆盖当前 CLI

> **最后更新**：2026-11-14
> **状态**：进行中（实现与验证已完成；Guide 的线上版本待发布）
> **含收费**：否

## 目标

把 `skillmaster/skillmaster-guide` 修到与当前 CLI 一致，并补齐 `run` / `secret` / `notifications` / `usage` / `draft create` / `copy` 等能力的入口；同时新增一套独立于 `--help` 评测的 Guide 评测口径。细节见 [`prd.md`](prd.md)。

## 验收标准

Guide 的漂移检查与本地链接检查全绿，43 题 Guide 评测达到路由、作答、通过各 ≥90%。完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：验收 #1、#2、#6 任一条不满足就不算交付（退役命令清零、四个必需入口、评测达标）。

## 这一版明确不做什么

摘要三条最要紧的；完整范围与每条的理由在 [`prd.md`](prd.md) §范围。

- 不上 RAG —— 索引本身就是一组可枚举的 context pointer；向量检索会造出第二份事实，只留给未来的问答机器人
- 不新增或改 CLI 命令 —— 本版只修文档与验收，不改能力边界
- 不重做人向文档站与新人旅程 —— 归 TODO「面向人的使用说明书」「新人旅程（人侧引导）」

## 与目标态的关系

[`target/`](../../target/) 不含本版涉及的面。Guide 的定位由 [ADR 0174](../../decisions/0174-two-help-layers.md) 固定为「两层帮助里的手册层」；本版只让这一层追上现状。

- 保留的部分：`--help` 是命令参考，Guide 是任务手册，同一事实只有一个家。
- 已经分叉的部分：无。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、CLI 对应、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 架构、页面模型、索引预算、评测口径、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据、结果、已知问题 |
| [`known-issues.md`](known-issues.md) | 本版范围外、留给下一版的 CLI 缺陷（帮助文案两处） |

## 开放问题

完整列表见 [`technical-design.md`](technical-design.md) §开放问题。本版没有阻塞交付的未决项；四个非阻塞问题（评测跑几次、是否接入 CI、读上限是否合适、索引余量）在那一节给了当前倾向。
