# M15 · 使用度量

> **状态**：已实现（v49） · 拥有表与对外发布类型见 [`system.md`](../system.md) 的对应行（源是 `ModuleMap`）

## 职责

记一次「skill 正文被加载」的事实，并按 **scope**（度量的用途）维护两条序列：按「账号 × 天」去重的使用宽度，与每次加载都 +1 的累计次数。对外只给分桶值，不返回精确账号数。

## 边界

- **不知道公开 / 私有**：调用方按自己的策略决定哪些加载要报；M15 只认 `scope`。
- **不算入库数**：库关系是 M12 的事实，由读模型拼装，M15 不读 `skill_library_join`。
- **不判定谁能看**：`scope` 是「为什么度量」，不是「谁能读」；展示层的授权与它分开。
- **不做实时分析**：没有事件流、没有外部投递；写入是同步的，失败由调用方兜住。

## 不变量

1. **同账号、同一天、同 scope 只有一行**：主键 `(skill_id, day, account_id, scope)`；重复加载不增行。
2. **累计次数每次加载都 +1**，包括被上一条去重的那一次（[ADR 0152](../../decisions/0152-cumulative-calls-are-their-own-signal.md)）。
3. **只给分桶下限，不返回精确账号数**——达到阈值也不发（[ADR 0149](../../decisions/0149-use-is-counted-per-account-per-day.md)，由 [ADR 0153](../../decisions/0153-usage-metrics-is-its-own-module.md) 收窄）。
4. **90 天清理只清去重表**，累计次数永不清理；保留期按 scope 定。
5. **写入失败不影响读**：调用方在独立事务里记录，失败只记日志（[ADR 0153](../../decisions/0153-usage-metrics-is-its-own-module.md)）。

## 对外契约

`UsageRecorder`（`record(SkillLoad)`）、`UsageQuery`（`snapshots(ids, scope)`）、`SkillLoad`、`UsageScope`、`UsageSnapshot`。

## 为什么这样切

度量会继续长——作者看板、协作者的使用管理都会复用同一批事实，公域只是第一个消费者。把它和来源放在同一个模块，会让两套规则互相拖拽；把它和公域读路径绑在同一笔事务里，会让记账失败变成读失败。拆开之后，公域只保留「哪些加载算公域信号」这一条策略，M15 对公开 / 私有零知识（[ADR 0153](../../decisions/0153-usage-metrics-is-its-own-module.md)）。
