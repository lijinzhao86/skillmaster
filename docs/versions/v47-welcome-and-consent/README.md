# v47-welcome-and-consent · 新账号默认带上官方 skill，公域 skill 调用先确认

> **最后更新**：2026-11-05
> **状态**：进行中（已实现，测试见 [`test-plan.md`](test-plan.md) §结果；待 PR 与端到端验收）
> **含收费**：否

## 目标

新账号注册后库里直接有 `skillmaster/skillmaster-startup` 与 `skillmaster/skillmaster-guide` 两条引用，搜得到、用得上；公域且不在库的 skill 在 `skmr skill invoke` 前先停下来要一次确认，用户可以选择只这一次（`--yes`）或者收进库（以后不再问）。细节见 [`prd.md`](prd.md)。

## 验收标准

十二条验收（A1–A12），摘要其中五条：新账号注册后库里立刻有两条官方引用，`skills list` 列得出、`skills search` 默认搜得到；注册时官方 skill 不存在或不可加入不影响注册；公域未加入的 skill `invoke` 不加载、退出码 1、同时给出 `--yes` 与 `skill join --yes` 两条命令；`--yes` 不写库，下次仍然要确认。完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：A4（拒绝时两条命令都在）、A5（`--yes` 只对本次生效）不满足就不算交付——这一版要解决的就是「用户能在中间被问一次，而且问的方式不偷偷改变库」。

## 这一版明确不做什么

- **存量账号不补官方 skill** —— 不静默改动已有用户的库；他们仍可自己 `skill join`（产品负责人 2026-11-05 定）。
- **不拦 `read` / `cat` / `grep` 等读命令** —— 它们是显式读文件，不是「加载来用」（产品负责人 2026-11-05 定）。
- **`--yes` 不顺带入库** —— 只这一次与收进库是两个选项，由用户选（产品负责人 2026-11-05 定）。

完整范围与每条的理由在 [`prd.md`](prd.md) §范围。

## 与目标态的关系

[`target/`](../../target/) 没有描述「库」这一层；它是 [ADR 0082](../../decisions/0082-public-and-private-with-my-library.md) 的显式扩展，已登记在 [`target/README.md`](../../target/README.md) §五。本版不改变这个形态，只给新账号的库一个非空的初始状态，并给 CLI 的加载加一道确认轨。

- 保留的部分：公开即可调用（[ADR 0048](../../decisions/0048-visibility-hands-out-a-rung.md)）；库是引用；公域只搜不列。
- 已经分叉的部分：CLI 的 `invoke` 不再对公域未加入的 skill 直接加载，这是 [ADR 0143](../../decisions/0143-public-skills-need-confirmation-in-the-cli.md) 对 [ADR 0082](../../decisions/0082-public-and-private-with-my-library.md) §决定 2 的部分取代。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、控制台与 CLI 对应、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 架构、数据模型、接口、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据、结果、已知问题 |

## 影响最大的未决问题

完整列表见 [`technical-design.md`](technical-design.md) §开放问题。按重要性排前三条：

1. **存量账号什么时候补？** 当前倾向：本版不补；若之后有证据表明老用户确实找不到官方指南，再做一次明确的一次性迁移。
2. **默认加入失败只有日志，够不够？** 当前倾向：够——它不影响注册，用户仍可自己 `join`；等出现「注册了却没进库」的实际反馈再加可观测性。
3. **`invoke` 之外的读命令将来要不要也拦？** 当前倾向：不拦；若出现绕开 `invoke` 误用公域内容的证据，再按同一条 ADR 的形状扩。
