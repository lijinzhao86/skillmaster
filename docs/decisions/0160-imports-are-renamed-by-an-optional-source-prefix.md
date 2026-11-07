# 0160 · 导入的开源 skill 按可选来源前缀重命名

- **状态**：已采纳
- **日期**：2026-11-07
- **所属版本**：[`v50-open-source-sync`](../versions/v50-open-source-sync/README.md)
- **依赖**：[ADR 0156](0156-sync-writes-as-the-official-namespace.md)（官方命名空间写入）、[ADR 0157](0157-sync-is-a-source-neutral-pipeline.md)（来源无关底座）

## 背景

v50 同步把上游 `SKILL.md` 的 `name` 当作平台地址；同一轮或跨来源出现同名时，后到的记为 `blocked`。批量导入会遇到「同名、不同内容」的真实情况：2026-11-07 抽查的若干热门 skill 仓库里，[`obra/superpowers`](https://github.com/obra/superpowers/tree/main/skills/test-driven-development) 与 [`addyosmani/agent-skills`](https://github.com/addyosmani/agent-skills/tree/main/skills/test-driven-development) 都有 `test-driven-development`，内容不同。只阻断等于让先到者垄断名字。

可选的做法有：A 导入时按来源前缀重命名；B 平台地址与内容名分离；C 保持阻断、由人处理；D 冲突时自动加 `-2` 或随机后缀；E 用作者名做命名空间（`mattpocock/<skill>`）。

2026-11-07 产品负责人选了 A，并要求 `prefix` 可选：不配就保持上游裸名；需要让两个同名来源共存时，再给其中一个配前缀。作者命名空间留到以后，先要有命名空间认领/验证与写入权限。

## 决定

1. **清单可选配 `prefix`，单 skill 条目也可用 `name`**。配了 `prefix`，平台名 = `<prefix>-<上游 SKILL.md 的 name>`；配了 `name` 就直接用它；都没配就保持上游 `name`。`prefix` 与 `name` 互斥，`select` 展开的条目只能用 `prefix`。
2. **只在平台名与上游 `name` 不同时改写内容**：只改 `SKILL.md` 顶层的 `name` 与 zip 根，正文和其它文件逐字节不动；相同时完全不改。
3. **名称必须符合 Agent Skills 的 name 规则**（1–64 字符、小写字母/数字/连字符、不以连字符开头或结尾、无连续连字符）；`skillmaster` 本身或以 `skillmaster-` 开头保留。首次分配时，组合后超长、含非法字符、落到保留名或被另一个来源的 origin 占用时该条 `blocked` 并记录原因，不截断、不加哈希后缀。
4. **地址在第一次成功导入时分配**，之后按 `(kind, locator, path)` 固定。上游后来改 `SKILL.md` 的 `name` 不新建 skill；改 `prefix` 不静默重命名已有地址，运营要改就另做迁移。
5. **不自动加后缀**。没有配前缀的两个同名来源仍只能进一个，后来者必须加 `prefix`（或单 skill `name`）才能共存。
6. **不改前端与 CLI**。地址变化是数据；控制台与 CLI 仍按 `namespace/name` 通用读取。

## 理由

- **可选前缀保留常见情况的上游名字。** 不需要给每个来源都起前缀；只有真的撞名时才付出改写的代价。前缀是运营在清单里做的显式选择，不是算法自动改名。
- **A 比 B 彻底。** B 只在平台侧起别名，下载到本机后 `SKILL.md` 仍是上游 `name`；两个来源的同名 skill 装到同一个 skills 目录仍会互相遮挡。A 在交付内容里就把名字改掉，本机也唯一。
- **C 不适合批量导入。** 每撞一次就人工处理，来源一多就卡住；A 让运营用一行配置解决。
- **D 的地址不可预测。** 自动加后缀让同一份内容在不同来源下得到不同地址，用户记不住、检索也难对上；一个来源改名还会再换一次。
- **E 是更后的形态。** 作者命名空间地址更干净，但需要命名空间认领/验证、作者授权、同步写入权限与「平台收录不是作者发布」的背书语义；这些不属于这次重命名。前缀是它在官方命名空间内的轻量替代。
- **只改 `name` 是最小改写。** `name` 是标识符，不是正文；平台侧仍保留 `skill_source.locator` / `path` 与快照 `revision`，溯源不依赖改写后的字节。上游原名不单独落库，只写进草稿说明；要按原名检索得另加字段。

## 后果

- 配了前缀的 skill，其 `SKILL.md` 与上游不再逐字节相同；平台存的是改写后的版本。溯源靠 `skill_source` 的 locator/path/revision 与草稿说明，运营审阅时要按这个口径看。
- 未配前缀的条目保持裸名；两个裸名同名的来源仍只能进一个，后来者必须加前缀。
- 已导入的裸名条目以后加 `prefix` 不会自动改名；需要一次显式迁移（本版不做）。
- 搜索只读平台名与描述等平台字段，不读来源，所以按上游原名搜不到带前缀的条目（除非描述里写了原名）。按原名检索与来源卡一起留到二期，见 [`TODO.md`](../current/TODO.md)「导入来源的署名与介绍」。
- 来源卡、作者命名空间与独立来源页不在本决定内，见 [`TODO.md`](../current/TODO.md)。
