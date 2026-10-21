# 0001 · 交接地址带上上线表单的预选

- **日期**：2026-10-21
- **触发它的版本**：[`v11-publish-handoff`](../../v11-publish-handoff/README.md)
- **决策**：[ADR 0074](../../../decisions/0074-a-handoff-may-carry-a-preselection.md)

## 改了什么

- [`technical-design.md`](../technical-design.md) §地址表增加一行：`…&sub=actions&bump=<patch|minor|major>` —— 操作栏的上线表单打开时预选一档。
- 同文件 `version open` 那一行补上 `--sub record|diff|actions` 与 `--bump patch|minor|major`。
- 同文件四处「不新增 / 不做 `--bump`」补上 v11 的注记：CLI 仍不选号、不上线，这个参数只出现在交接地址里。

## 为什么

v6 定的是「选号只在单版页上」——这条仍然成立。v11 要解决的是**决定在交接处丢失**：agent 判断过「这次改动影不影响旧调用」，
CLI 却只能把人送到页面，人还得自己重建那个判断。预选不是执行：页面照旧要人按「上线」并确认。

地址与 `version open` 的权威都在 v6，所以那三处必须回写，否则读起来像 v11 违反 v6 的决定。

## 影响

- 控制台地址多一个可选参数 `bump`；非法值当作没有，页面行为不变。
- 网页的 `parseVersionRoute` / 单版页读它；服务端不读、不校验。
- 「默认选中哪一档」仍以演示件为准（patch），本版不为它新增设计态。
