# v27-rule-block-and-prompt-retirement · 规则段默认先查，CLI 不再管理那段说明

> **最后更新**：2026-10-28
> **状态**：已完成（代码与文档同分支 `feat/rule-block-searches-by-default`，未提交）
> **含收费**：否

## 目标

把写进用户文件的那段说明从「有条件才查」改成「除闲聊外一律先查」，并让 CLI 从此只管打印它、不再检测 / 更新 / 撤销它；同时删掉因此失去调用者的 `startup --plan`。细节见 [`prd.md`](prd.md)，决策见 [ADR 0109](../../decisions/0109-the-rule-block-defaults-to-searching-first.md)、[ADR 0110](../../decisions/0110-the-block-is-a-paragraph-the-cli-stops-managing.md)、[ADR 0111](../../decisions/0111-startup-takes-no-flags.md)。

## 验收标准

`prompt print` 打出的正文不含任何标记；敲 `prompt install|status|update|uninstall` 得到三行退役说明并退出 2；`startup` 带任何参数都退出 2，不带参数时旅程正文与两行 stderr 提示不变（唯一去掉的是 v26 那句「规则段是旧版」——它随标记段一起退役）。完整验收（8 条）见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：验收 1（规则段的默认是查）、验收 3（四个命令退役且不写文件）、验收 5（`startup` 不带参数）——这三条不满足，这一版就没有交付。

## 这一版明确不做什么

- **不做规则段的自动更新**——没有标记就没有可靠的边界，要更新重跑 `startup`（理由见 [`prd.md`](prd.md) §范围）。
- **不清理用户文件里遗留的旧标记段**——CLI 没有任何命令读用户文件；要清掉由人自己删，或重跑 `startup` 时在选项里选「换成 SkillMaster 的」（同上）。
- **不改 `startup` 技能的目录与文案**——那在 v26 迭代 0004 里；这一版只碰规则段、CLI 的命令面，以及 v26 那边跟不上的那份清单副本（删掉它，不重写它）。
- **不在 CLI 里补回一份「还差几步」的清单**——权威在旅程正文。

## 与目标态的关系

[`target/`](../../target/) 这一版没有分叉：它收窄 CLI 的命令面，朝着「平台与技能说话、CLI 只管取用」的方向走了一步。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、控制台操作与 CLI 对应、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 规则段的形态、命令面、`startup` 的参数面、迁移与分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据、结果、已知问题 |
