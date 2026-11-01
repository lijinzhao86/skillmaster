# v38-install-delivers-skill · 安装流程改为「先交付 skill，再装 CLI」

> **最后更新**：2026-11-02
> **状态**：进行中（**仓库侧已实现**；平台上的 `skillmaster/skillmaster-startup` 尚未创建，T3 / T7 未执行）
> **含收费**：否

## 目标

让用户装 SkillMaster 时拿到的是一个**技能包**，而不是被改写过的全局记忆；装完 CLI 之后的一切由这个技能自己指引。细节见 [`prd.md`](prd.md)。

## 验收标准

安装完成后：技能文件在 Agent 的技能目录里、SkillMaster 出现在技能清单里、CLI 能跑、**用户的全局记忆一个字节没变**。完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：第 3 条「全局记忆未被修改」不满足就不算交付——本版的目的就是去掉这次改写。

## 这一版明确不做什么

摘要三条；完整范围与每条的理由在 [`prd.md`](prd.md) §范围。

- 不做多 Agent 的技能目录表 —— 每个 Agent 自己推断自己的目录，不维护路径表
- 不做技能的自动更新 —— 本期只有「重新走一遍安装」这一条路
- 不改首页那句**提示**（「复制下面这句，发给你常用的 agent。」）—— 安装指令正文的地址随本版改到下载桶，提示语不变

## 与目标态的关系

[`target/`](../../target/) 描述完整形态。这一版是它的安装子集：保留「安装即交接」，分叉在**交付物从记忆文本变成技能包**；差异清单见 [`target/README.md`](../../target/README.md)。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、控制台与 CLI 对应、验收与指标、依赖 |
| [`technical-design.md`](technical-design.md) | 架构、数据模型、接口、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据、结果 |
| [`../../decisions/0129-onboarding-delivers-a-skill-not-a-memory-line.md`](../../decisions/0129-onboarding-delivers-a-skill-not-a-memory-line.md) | 为什么用技能而不是全局记忆 |

## 影响最大的未决问题

完整列表见 [`technical-design.md`](technical-design.md) §开放问题。下面三条最要紧（顺序与那份表不同）：

1. **Windows 的解包**——`curl.exe` 与 `tar` 在 Windows 10 1803+ 自带，但没在真机上验过。
2. **`skmr prompt` 的去留**——写记忆那一步去掉后，这条命令暂时没有调用方。
3. **平台上的 `skillmaster/skillmaster-startup`**——还没创建；CLI 已经指向它，创建之前 `skmr startup` 会 not_found。
