# v31-list-addresses-only · skills list 只给地址

> **最后更新**：2026-10-30
> **状态**：进行中（已实现；待审计与合并）
> **含收费**：否

<!--
README 是「版本记录主页」，不是详述。每个问题只写一句话 + 链接。
细节一律写在 prd.md / technical-design.md / test-plan.md 里。
这不是风格偏好——复述必然漂移。
-->

## 目标

`skills list` 默认每条只打地址、默认列全；描述与小字段留在 `--verbose`，详情留在 `skill show`。细节见 [`prd.md`](prd.md)。

## 验收标准

默认每行一个地址、默认列全、`--verbose` 保留描述、`search`/`show` 不变。完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：A1（默认只有地址）、A2（默认列全）、A4（`--verbose` 保留描述）——这三条不满足，本版不算交付。

## 这一版明确不做什么

摘要三条最要紧的；**完整范围与每条的理由在 [`prd.md`](prd.md) §范围**。

- 默认行不加状态/来源/版本/时间 —— 这些维度已由 `--show` / `--source` / `--sort` 表达
- 不把 `--verbose` 的描述去掉 —— 它是「更多」的那一档，批量总览要靠它
- 不动 `search` 与 `show` —— 各自职责不变

## 与目标态的关系

[`target/`](../../target/) 描述的是「agent 按需取用、不关心库有多大」的形态。这一版把「清单」与「发现」分开：清单只给身份，发现与详情各自有命令。

- 保留的部分：服务端权威、地址即句柄（ADR 0035）、搜索在服务端（[0006](../../decisions/0006-search-server-side.md)）。
- 已经分叉的部分：v13 的卡片契约（第一行地址、第二行描述）在 `list` 上被本版取代，见 [ADR 0115](../../decisions/0115-list-is-an-inventory-and-descriptions-live-in-show.md)。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 架构、数据模型、接口、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、结果、已知问题 |

## 影响最大的未决问题

完整列表见 [`technical-design.md`](technical-design.md) §开放问题。按重要性排前三条：

1. **超大库要不要硬上限** —— 当前倾向不加，靠收窄参数。
2. **`--show` 非默认时要不要标记非 live 行** —— 当前倾向不。
3. **空结果的两句话要不要跟着精简** —— 当前倾向不动。
