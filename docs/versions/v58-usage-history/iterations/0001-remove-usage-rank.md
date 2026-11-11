# 0001 · 调用行不再显示「第 K 个结果」

> **日期**：2026-11-11
> **所属版本**：[`../README.md`](../README.md)
> **类型**：小版本迭代——**未改变对外可验收的能力边界**

## 改了什么

- 控制台 `skillmaster-console/src/components/usage/UsageLog.tsx` 与设计件 `design/src/console/pages/UsagePage.tsx`：一次搜索后的调用行由「`地址 @版本` + `第 K 个结果`（窄屏 `#K`）」改为只显示 `地址 @版本`；多调用时行尾仍附调用时刻，窄屏也显示。
- 样式：删除 `design/src/console/styles.css` 与 `skillmaster-console/src/styles-usage.css` 里的 `.usage-rank`、窄屏 `.usage-rank-full` / `.usage-rank-short` 规则；删除窄屏隐藏 `.usage-invocation-at` 的规则（原先是为给名次腾位置）。
- 规格：`design/README.md` 的「调用的 skill 列」与「长文本」同步改为 `地址 @版本`；本文件同目录的 `technical-design.md` §展示侧、`test-plan.md` R13 同步。
- 未改：服务端 `rank` / `search_rank` 字段与接口形状；行内展开面板仍按 `#K` 排名次、被调用项仍标「已调用」；CLI 单条调用仍输出「（第 K 个结果）」，`usage_test.go` 的断言不变。

## 为什么

2026-11-11 产品判断：「调用的这个工具排在第几位」用户看不懂，行内不需要。名次仍有价值的地方是行内展开的结果面板——对照「当时返回了什么、哪条被用了」——那里按名次排序并标「已调用」，保持不变。

顺带修正：窄屏原先为了给 `#K` 腾位置而隐藏每次调用的时刻；名次删除后空间已腾出，时刻恢复显示（`title` 在 `display:none` 上本来也摸不到）。

## 影响

- **影响哪些已交付行为**：只影响控制台「使用记录」明细的调用行显示；搜索、筛选、统计、展开面板、CLI 输出与数据形状都不变。现有测试没有依赖这段文字。
- **需要同步改哪些文档**：`design/README.md`、`technical-design.md` §展示侧、`test-plan.md` R13 已就地修订；`README.md` 的「本版文档」表登记本条。
- **是否需要新开 ADR**：不需要——这是界面文案与显示取舍，没有改变对外契约或跨版本能力边界。
