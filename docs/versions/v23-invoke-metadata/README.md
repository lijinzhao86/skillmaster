# v23-invoke-metadata · invoke 的头部改由服务端组装

> **最后更新**：2026-10-27
> **状态**：进行中（**服务端已上线**，release `7109acd`，2026-10-27；CLI 的 beta 已发同一提交，`1.1.0-beta (7109acd)`。两轮循环审计 0 blocker / 0 high，六档矩阵与 `qa/role-matrix` 已过；只剩平台技能 `startup` 的规则段由人上线（验收 7）
> **含收费**：否

## 目标

`skill invoke` 返回的头部不再透传作者的 frontmatter，改由服务端组装成平台事实（`address` / `version` / `access` / `permissions`，作者声明了才有 `compatibility`），并删掉 CLI 追加的页脚。细节见 [`prd.md`](prd.md)。

## 验收标准

头部只有 `address` / `version` / `access` / `permissions` 与作者声明了的 `compatibility`（草稿没有 `version`）、`access` 与 `permissions` 在六档 × 五种版本状态的矩阵里逐格正确、`cat` 仍是原始字节。完整验收见 [`prd.md`](prd.md) §验收与指标。

## 这一版明确不做什么

- 不透传 `description` / `when_to_use`——`skills search` 已经给过，重复即浪费。
- 不在头部列具体命令——命令形状与 skill 无关，规则段写一次就够。
- 不动 `read` / `files` 的不对称（invoke 档能按路径读、不能列目录）——那是权限模型的现状，本版只负责如实告知。

## 与目标态的关系

- 保留的部分：`invoke` 仍是「加载一版正文」这一个动作，渐进披露的三层不变。
- 已经分叉的部分：无新增分叉。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 架构、数据模型、接口与映射表、输出形状、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据、结果、已知问题 |

## 决策

输出契约的跨版本取舍写在 [ADR 0100](../../decisions/0100-invoke-output-is-platform-assembled.md)：`invoke` 返回的是平台组装过的一版，不再是原始字节。其余开放问题见 [`technical-design.md`](technical-design.md) §开放问题。
