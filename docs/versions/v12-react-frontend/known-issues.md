# v12-react-frontend · 本版不修的历史缺陷

本文件记**本版范围外**、本版决定不改的缺陷。本版范围内的未修问题记在
[`test-plan.md`](test-plan.md) §已知问题。

## 001 · 低档位打开 `?tab=settings`（或 `?tab=files`）不会被纠正回「信息」

**状态**：未验证（机制已由读码与 Vue 反应式探针确认；未在真机上用调用档打开该地址复现）

**是什么。** `SkillPage` 把「这一档够不到的 tab」纠正回 `信息` 的规则，是**在 tab 集合变化时**触发的：

- Vue：`watch(tabs, available => { if (!available.some(e => e.key === tab.value)) tab.value = 'info' })`；
- React：内容键 `tabsKey` 上的 effect（首轮跳过，与 Vue 的 `watch` 不随挂载触发一致）。

两者都只在**集合本身变了**的时候纠正。而地址在 skill 还没加载时就已经读进来了，那一刻 `rung` 是
`invoke`、集合是 `[信息, 版本]`；skill 加载后如果这一档仍然是 `invoke`，集合还是 `[信息, 版本]` ——
集合没变，纠正不发生。

**影响。** 打开 `/skills/<ns>/<name>?tab=settings&sub=collaborators` 且调用者只有「调用」那一档时，
页面渲染的是设置面板（可见性单选、协作者名单），而后端会逐个拒绝——没有数据被改，但屏幕上出现了
这一档不该看到的一屏。`?tab=files` 同理。

**为什么本版不修。** v12 的验收是「一切不变」，而这个行为在迁移前的 Vue 里**一模一样**（探针：
`invoke → invoke` 时 watcher 触发 0 次，`invoke → admin` 触发 1 次）。在迁移版里顺手修掉它，等于
在「换引擎」的版本里夹带一次行为变化，失败将无法归因。

**建议修法（另开一版）。** 渲染期派生有效 tab——`const currentTab = tabs.some(e => e.key === tab) ? tab : 'info'`，
渲染与地址都用它；`setTab` 仍只由用户的 tab 点击写入。两套实现要一起改，并补一条六档用例。
