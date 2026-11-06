# v49-public-signals · 已知问题

> 本版范围内不修、且不属于本版交付的历史缺陷。版本内应该修的缺陷记在
> [`test-plan.md`](test-plan.md) §已知问题。

| # | 问题 | 状态 | 影响 |
|---|---|---|---|
| 1 | CLI 三个 updater 用例失败：`TestTheCheckIsThrottledToOnceADay`、`TestANewerBuildIsInstalledAndTheCommandIsUntouched`、`TestATamperedPackageLeavesTheBinaryAlone` | 已复现，既有 | 未改动 updater；v48 会话记录其中两条在 main 上同样失败，属本机环境问题。v49 相关的 CLI 用例全绿。 |
