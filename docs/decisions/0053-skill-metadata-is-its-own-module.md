# 0053 · skill 元数据是它自己的模块，列表要的事实落在 `skill` 行上

- **状态**：已采纳
- **日期**：2026-10-18
- **所属版本**：[`v5-skill-management`](../versions/v5-skill-management/README.md)
- **依赖**：[0031](0031-submitting-and-publishing-are-two-actions.md)（提交与上线分开）、[0047](0047-permissions-are-a-ladder.md)（权限是一条梯子）、[0048](0048-visibility-hands-out-a-rung.md)（公开是把某一档交给所有人）、[0052](0052-a-request-is-not-a-grant.md)（申请不是授权）
- **修订**：[多模块表](../versions/v1-hosting/technical-design.md) §2.5 里 M7 的职责与表清单——M7 收窄为「版本与内容」，`skill` / `skill_grant` 划给新的 M12。该版本的正文不改写；§2.5 的模块表是跨版本的权威，随这次决定更新。

## 背景

v5 要做的四件事——权限梯子、可见性、删除与恢复、申请——**全是 skill 元数据**：谁在什么档位上能做什么、这个 skill 对外可见到什么程度、它被删了能不能找回、谁能申请。**版本与正文一个字都不改。**

但当时的 M7 把两件事放在同一个模块里：`skill`（元数据）与 `skill_version` / `version_file`（内容）一起归 M7，`skill_grant` 也挂在 M7。这条合并本身有历史理由（授权谓词内联进目录查询、外键指向自己的 `skill`），但它带来两个现在的代价：

- **列表跨了边界**：搜索与作者列表都要联 `skill_version`——前者为了「只列有已上线版本的」和取 `when_to_use`，**而且那条内连接同时是 `current_version_id` 的唯一完整性检查**（那一列没有外键）；后者为了草稿数、最近提交时间与最新草稿。
- **内容反向读了元数据**：版本服务直接查 `skill` 行拿可见性与权限，`SkillSnapshot` 也因此带着 `visibility`。

结果是两件不该混的东西在同一套归属里：列表要联内容表，内容要读 `skill` 行，元数据的新字段也容易顺手加进内容的查询。**本版仍然只有一个库**；要解决的是**表与模块的归属**，不是换库。

摆上桌的选项：

| | 做法 | 为什么被否 |
|---|---|---|
| a | 保持合并，以后再拆 | 那时要同时改列表、权限、发布与所有读路径，一次重构的面是整个读平面；而现在这一版的全部改动本来就只在元数据一侧，边界最便宜的时刻就是现在 |
| b | 不新增模块，只在 `modules.version` 里分 `metadata/` 与 `content/` 子包 | 改动最小，但表归属检查不认这条线，边界只靠约定；下一次「顺手联一下」不会有人拦住 |
| c | **拆成两个模块，列表要的内容事实落成 `skill` 的投影列** | 采纳 |

## 决定

1. **新增 M12「skill 元数据」**，拥有 `skill`、`skill_grant`、`skill_access_request`；**M7 收窄为「版本与内容」**，拥有 `skill_version`、`version_file`（字节仍在 M6）。
2. **一条 SQL 不得同时点两个模块的表。** 元数据的查询不出现 `skill_version`；内容的查询不出现 `skill`。内容的权限判定由用例层先从 M12 拿到 `Access{skillId, owns, rung, visibility, currentVersionId}`，再交给内容模块。
3. **当前版本指针归元数据。** `skill.current_version_id` 是元数据列，存的是不透明 id；上线时内容模块先把版本标成 published，再把这一版的投影交给 M12 的 `recordPublish`，由它写指针与展示投影。内容不再写 `skill` 表。
4. **列表要的内容派生事实落成 `skill` 的投影列**（V21）：`when_to_use`、`current_version_name`、`current_digest`、`current_version_submitted_at`、`draft_count`、`newest_draft_submitted_at`、`newest_draft_version/digest`、`newest_title/description`。它们在提交 / 上线 / 丢弃时由内容模块经接缝写回。**`published` 不新增列**：`current_version_id IS NOT NULL` 就是它。
5. **内容与分发模块的其余实现保持现状**：版本、字节、GC 不变，M9 的文件清单与 diff 也不变；这一版只把它们的权限读取换成传入的 `Access`，并去掉 `SkillSnapshot` 里的 `visibility`（由响应组装时从 `Access` 补）。

## 理由

**为什么先拆模块，而不是先优化查询。** 模块边界决定「谁拥有哪张表、哪一处 SQL 可以点到哪些表」；查询快慢是另一件事。这一版的所有需求都在元数据一侧，此刻拆的代价只有搬家与接缝；以后再拆，代价会落在每一条读路径上，而且没有一个小版本能单独验证它。**元数据表还因此可以自由扩展**：新字段、新关联表加在 M12 自己的表上，内容表不跟着动。

**为什么是投影列，而不是查询时向内容模块批量取。** 列表要按「最近提交」排序并分页，还要在一条 SQL 里做所有权过滤与搜索匹配。这些事实如果留在内容模块，元数据要么拿不到排序键（只能把所有候选拉到内存里合并），要么每行问一次内容（每行一次调用，随数据量变慢）。投影把代价从**每次读**挪到**每次写**：提交、上线、丢弃本来就在写，多更新一行是同一事务的一部分。代价是写入点变多、多一份派生数据——所以 V21 把「漏一处就静默出错」写进了 TD。

**为什么指针也归元数据。** 「当前上线的是哪一版」是列表与存在性都要问的产品事实；把它留在内容侧，搜索与「这个 skill 有没有已上线版本」就都得跨边界。指针是 id，不是内容；内容的版本行仍然只有内容模块读写。

**为什么否掉「保持联表，以后再说」。** 联表就是跨模块的 SQL。边界一旦允许「只是联一下」，以后要清点的就是所有 SQL，而不是一份接缝清单；而元数据表与内容表的扩展也会互相牵连——这正是这一版要避免的。

## 后果

- **一次迁移（V21）与一次回填**。回填要从现有 `skill_version` 算一遍投影——迁移里允许这一次读内容表；之后列表的常规读路径不再碰它。
- **新的静默失败点**：提交 / 上线 / 丢弃各要更新对应的投影列；漏一处，列表显示旧事实，而响应格式完全正常。这条要有自己的断言（[test-plan](../versions/v5-skill-management/test-plan.md) T14 验回填，T15 验三个维护点）。
- **上线是两处写**：版本状态由 M7 写，指针与投影由 M12 写。本版一个库，两处同在一个用例事务里。
- **`SkillSnapshot` 不再带 `visibility`**，`AuthoredSkillResponse` 的 `version` 与 `resources` 可为 null（`invoke` 没有文件与正文地址；没有已上线版本时也没有选中版本）；界面与 client 要按这个形状读。
- **搜索的 `when_to_use` 路径被顺带订正**：从顶层 `when_to_use` 改为 `frontmatter.metadata.when_to_use`（设计件与 `design/README.md` 的键）；V21 的投影按新路径取，查询与测试夹具一起改。
- **v1 §2.5 的模块表与 `ModuleMap` 要跟上**：M12 进模块表，M7 的表清单减到两张；`TableOwnershipTest` 会发现漏改。
