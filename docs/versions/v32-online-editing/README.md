# v32-online-editing · 在线编辑 skill

> **最后更新**：2026-10-30
> **状态**：进行中（第一期 `edit` 已实现，通过三轮三路审计；二期、三期未开始）
> **含收费**：否

<!--
README 是「版本记录主页」，不是详述。每个问题只写一句话 + 链接。
细节一律写在 prd.md / technical-design.md / test-plan.md 里。
-->

## 目标

让 agent 在服务端直接改 skill 成为最短、最可信的一条路：改之前有可逐字引用的原文，改完能看到改了什么，改错能被指出来。细节见 [`prd.md`](prd.md)；为什么不做本地往返见 [ADR 0116](../../decisions/0116-editing-happens-online.md)。

## 验收标准

第一期（`edit`）A1–A8、A13，第二期（读与写）A9–A10、A12，第三期（动线）A11。完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：A2（改完回显实际改动）、A3（行号混入有专门诊断）、A6（多处匹配给行号）——这三条不满足，本版不算交付。

## 这一版明确不做什么

摘要四条最要紧的；**完整范围与每条的理由在 [`prd.md`](prd.md) §范围**。

- 不做 patch 语法 —— 缺的是反馈，不是提交形态；格式合规本身是独立失败面（aider 的数据见[调研记录](../../research/agent-file-editing-tools.md)）
- 不做多文件原子操作 —— 逐文件的独立结果更容易定位是哪一步错了
- 不做 undo / 编辑历史 —— 客户端不存第二份真相（Anthropic 在 Claude 4 删掉了 `undo_edit`）
- 不改「只有 invoke 移动本机钉」（[ADR 0035](../../decisions/0035-the-pin-lives-on-the-client-machine.md)）—— `@new` 只打印下一条完整命令

## 与目标态的关系

[`target/`](../../target/) 里 agent 按需取用、不关心库有多大。本版补的是「取用之后怎么改」：改动仍落在服务端草稿上，不进本地目录。

- 保留的部分：服务端权威、草稿身份（ADR 0063）、写前基线 `If-Match`（v7）。
- 已经分叉的部分：无新增分叉。本地导入仍保留给首次导入（[ADR 0108](../../decisions/0108-importing-local-skills-is-optional.md)），本版不碰。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 架构、接口、诊断规则、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、结果、已知问题 |
| [`../../decisions/0116-editing-happens-online.md`](../../decisions/0116-editing-happens-online.md) | 为什么改 skill 走在线、不走本地往返 |
| [`../../research/agent-file-editing-tools.md`](../../research/agent-file-editing-tools.md) | 外部参照：Claude Code / Anthropic / Codex / aider 怎么写文件 |

## 影响最大的未决问题

完整列表见 [`technical-design.md`](technical-design.md) §开放问题。按重要性排前三条：

1. **hunk 上限 60 行** —— 已定为 60，等实测反馈再调。
2. **`read --raw` 与 `cat` 长期是否合并** —— 当前倾向不合并，二期实现时再确认帮助页能否讲清分工。
3. **`--new-file` 末尾换行要不要提示** —— 当前倾向原样使用、不提示。
