# 0001 · 拒绝句的第三行带上落点，`publish` 转发预选

- **日期**：2026-10-21
- **触发它的版本**：[`v11-publish-handoff`](../../v11-publish-handoff/README.md)
- **决策**：[ADR 0074](../../../decisions/0074-a-handoff-may-carry-a-preselection.md)

## 改了什么

- [`prd.md`](../prd.md) §范围 #5 的逐字拒绝句：版本级的第三行改为
  `到那一版自己的页面上去做：skillmaster version open <ns>/<name>@<版本> --sub actions`；
  `publish` 另接受 `--bump patch|minor|major` 并转发进这一行。
- 同文件 §验收 #17 跟着改。
- [`technical-design.md`](../technical-design.md) §接口里 `version publish|rollback|revoke|restore`（拒绝）那一行同步。
- [`test-plan.md`](../test-plan.md) 的 C12 同步。
- [`README.md`](../README.md) §本版文档 的 `iterations/` 行改为链接本记录。

## 为什么

v10 把「交回给人」做实了落点（[ADR 0072](../../../decisions/0072-the-handoff-points-at-the-page-that-owns-the-action.md)），
但版本级的四个动作都在单版页的「操作」栏，而地址缺省落在「概览」——人打开后还要再点一下。
v11 让交接句直接带上 `--sub actions`，并允许 `publish` 把作者判断的改动性质带过去。

## 影响

- 已交付的 CLI 拒绝句文本改变：`version publish|rollback|revoke|restore` 的第三行多一个 `--sub actions`。
- 页面专属动词不再静默丢参数：未知参数、第二个地址、非 `publish` 上的 `--bump` 都成为本地用法错误。
- `offline` / `online` / `delete` 的落点（设置页）不受影响。
