# 0005 · 规则段改成「默认先查」，只有闲聊免查

- **日期**：2026-10-28
- **关联**：[v27 · 规则段默认先查，CLI 不再管理那段说明](../../v27-rule-block-and-prompt-retirement/README.md)、[ADR 0109](../../../decisions/0109-the-rule-block-defaults-to-searching-first.md)、[ADR 0110](../../../decisions/0110-the-block-is-a-paragraph-the-cli-stops-managing.md)、[0004](0004-startup-skill-restructured-for-users.md)

## 改了什么

**这条改动的主体（正文怎么写、`prompt` 组退役、`startup --plan` 删除）由 [v27](../../v27-rule-block-and-prompt-retirement/README.md) 交付**，正文、命令面与验收都在那份版本文档里，这里不复述——同一件事写两份，第一处改动就会漂。

v26 这边跟着动的只有旅程技能里的说法：`memory/` 各步说的是「让 Agent 先来 SkillMaster 找技能的那段说明」，写它的是 agent 自己，不走 CLI（[0004](0004-startup-skill-restructured-for-users.md)）。

## 为什么

产品负责人实测后判断调用率偏低：条件式把「这次要不要查」交给模型判断，而模型在这件事上系统性偏保守——能干的模型越容易直接动手。把「查」变成默认动作、只有闲聊免查，是这次改动的目的；为什么这么定、否掉了哪些做法，写在 [ADR 0109](../../../decisions/0109-the-rule-block-defaults-to-searching-first.md) 与 [ADR 0110](../../../decisions/0110-the-block-is-a-paragraph-the-cli-stops-managing.md)。

## 影响

- **本版 PRD / TD 里以「规则段正文」为主语的条目**改成指向 v27，不在 v26 复述。
- **ADR 0105 / 0106 / 0108 里依赖 `prompt install` / `startup --plan` 的机制**由 ADR 0110 / 0111 取代；这三条 ADR 的正文不改（那是当时的历史）。
