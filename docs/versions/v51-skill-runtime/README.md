# v51-skill-runtime · 让带代码的 skill 搬过去还能跑

> **最后更新**：2026-11-08
> **状态**：已实现并合并（PR #149）；含两次后续修正——可执行位改为由路径决定（[`iterations/0001`](iterations/0001-the-executable-bit-follows-the-path.md)），`doctor` 改用能力层（[`iterations/0002`](iterations/0002-doctor-uses-the-sandbox-package.md)）

## 目标

**同一份 skill，在本机能跑，经 SkillMaster 之后，在同一台机器上还能跑。** 细节见 [`prd.md`](prd.md)。

要做到它，这一版交付三件事：可执行位按路径统一（`scripts/` 之下可执行）、一个执行入口（`skmr run`）、一个自检入口（`skmr doctor`）；并把「状态放在哪」按长期 / 中期 / 短期分三层，让它在 agent 的沙箱里也能工作。

## 这一版明确不做什么

摘要三条；完整范围与每条的理由在 [`prd.md`](prd.md) §范围。

- 不做依赖安装与错误归因 —— 本版只保证「不因搬运而失效」，不负责「让它从不能跑变能跑」
- 不做平台沙箱执行 —— [ADR 0008](../../decisions/0008-no-script-execution.md) 的平台侧结论不变（执行形态是它留的岔路），且服务端隔离是独立的重工程
- 不动控制台 —— 本版全部能力在 CLI 面

## 验收标准

一句话：**本地能跑的那条命令，经平台取回后跑出同样的退出码；`scripts/` 下的脚本取回后仍可执行。** 完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：验收 1（`scripts/` 下的脚本落盘后可执行）与验收 2（`skmr run` 与本地退出码一致）不满足就不算交付。

## 与目标态的关系

[`target/`](../../target/README.md) 描述完整形态，其中「沙箱 / 脚本执行」一项在目标态里本来就自相矛盾（blueprint 说永不做，design 说 M3 做），见 [`target/README.md`](../../target/README.md) §三 第 8 条。这一版不解决那个矛盾：它只把执行放在**用户机器、用户看得见的地方**，平台仍然是分发与授权层。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、验收与指标、依赖与前置、拥有面 |
| [`technical-design.md`](technical-design.md) | 架构、数据模型、接口、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据、结果、已知问题 |
| [`iterations/0001`](iterations/0001-the-executable-bit-follows-the-path.md) | 可执行位改为由路径决定 |
| [`iterations/0002`](iterations/0002-doctor-uses-the-sandbox-package.md) | `doctor` 改用 `internal/sandbox` |
| [`iterations/0003`](iterations/0003-the-cli-carries-its-own-trust-store.md) | 沙箱里的信任库与传输错误提示 |
