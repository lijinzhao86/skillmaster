# 0004 · `skillmaster/startup` 重做成给用户走的 SOP：两项清单、写记忆不再调 CLI

- **日期**：2026-10-28
- **关联**：[`design/voice-spec.md`](../../../../design/voice-spec.md) §八、[ADR 0107](../../../decisions/0107-startup-speaks-to-non-technical-users.md)、[ADR 0108](../../../decisions/0108-importing-local-skills-is-optional.md)、[0003](0003-first-contact-says-what-it-is-doing.md)、平台技能 `skillmaster/startup`（1.3.2 → 1.3.3）

## 改了什么

- **结构**：删掉 `voice.md`（话术直接写进每个步骤的「对他说」）；`steps/` 重排为 `memory/`（01-locate → 02-write → 03-tell）与 `import/`（01-find → 02-upload → 03-publish → 04-cleanup）两个模块，每个文件固定四段（做什么 / 什么时候做 / 做完的标志 / 步骤 + 出问题怎么办 + 下一步）；删掉 `06-permission`、`07-retire`。
- **流程**：打招呼（已登录）→ 多选清单（写记忆 / 传技能，两项都标「推荐」）→ 一件一件配 → 收尾。两项都选时**先做写记忆，再做传技能**（原先没定顺序，`04-cleanup` 末句会把 agent 送回 `02-write`，形成重复搬运）。
- **写记忆不再由 CLI 写**：Agent 先读文件，判断有没有别的 skill 说明；有就把原文用一句人话说清、把推荐正文整段贴出、用选项组件问「换成 SkillMaster 的 / 保留原来的」，只有选前者才写。写入由 Agent 自己完成，**不写 `<!-- skillmaster:begin/end -->` 标记段，也不再调 `prompt install` 登记**；写完把文件读回来跟 `prompt print` 逐字比对。
- **`startup --plan` 退出这条旅程**：进度改用各步自己的判据（`submit` 结果、`skills pending`、读回比对）。
- **传技能**：`skillmaster skill submit <目录> --name <名字>`——**`--name` 必须带**（服务端对没有 sidecar 的包一律回 `draft_name_required`，而扫出来的本机技能目录没有 sidecar）；进度由 Agent 自己逐个报「第 3 个，共 12 个」，CLI 不打印这句。
- **上线**：网页按钮按控制台实际文案写「上线所选」；核对用 `skills pending` + 抽一条 `skill invoke`；上线后给一个能照着试的例子（配了记忆与没配两种说法分开）。
- **新增备份与清理本机副本**（可选）：上线成功后先 `tar -czf` 打包、`tar -tzf` 核对，再弹框问「清掉 / 先留着」；只有他选清掉才删，且只删包里那些。删本地文件的唯一入口就是这一步。
- **文案按词表改**：用户级记忆 → 全局记忆；会话 → 每次开始一个新任务；搬运 → 传；提示词 → 那段说明；「平台」→ SkillMaster。**所有给用户的选项都必须带推荐和理由**（新立的规矩，写进 `SKILL.md` 的「说话方式」）。

## 为什么

2026-10-28 产品负责人按「面向不懂技术的用户走一遍」重做了这段旅程，逐条提出：第一句要先欢迎、说清要做什么；不要逐步播报过程；不要每一步再问一次「要不要做」；写记忆前必须先检查已有的 skill 说明并让用户选择；写入由 agent 自己做、不要标记段；上线完成后给试用例子；搬运完要备份再问是否清理本机副本。随后三路独立审计（内部一致性 / 与 CLI 源码对账 / 与 voice-spec 对账）查出 39 条问题，其中三条会让流程直接失败：**`submit` 不带 `--name` 整批被拒**、删除命令缺 `--yes` 不执行、两项先后没定导致重复搬运。

## 影响

- **本版 PRD 两段规则改写**：删掉「清单确认一次，不代替每一步的同意」与「每步做完重新跑一次 `--plan`」，改成「清单选过就是同意了，只有三类事要问」与「进度由每一步自己的判据给出」。
- **`design/voice-spec.md` §八 同步**：原来那句「`startup` 旅程里写文件、传技能、上线、删副本仍按五句话请示」删掉，改成同一条「挑了就是同意 + 三处带推荐的提问」。
- **CLI 的 `prompt install` / `status` / `update` 不再被这条旅程使用**（命令本身保留）：因此 `startup --plan` 里「让 Agent 先来 SkillMaster 找技能」这一项对新写的记忆文件**永远显示待做**。
- **`0003` 影响一节里那条 TODO 由这次兑现**：`voice.md` 与步骤文件的称呼已随本次改写处理，随 `1.3.3` 发布。
- **v26 PRD 就地回写**（2026-10-28，产品负责人定）：整体流程的 mermaid、一次典型的旅程、「让 Agent 先来 SkillMaster 找技能」的展开、**把「旅程的五句话」整节换成「什么时候问用户」**（三类提问 + 必须带推荐）、决定 5 改成「备份放在哪」（`~/skillmaster-skills-<日期>.tar.gz`，不再要 `.skillmaster` 一次性授权）、语气与消息类型表、决策 1/2/12/16、验收 10/15/16/17/18/20/21/22 与指标行一并改到新流程。
- **仍未解决（新发现）**：CLI 的 `startup --plan` 在 stderr 末行仍指向 `steps/0N-*.md`（`startup_plan.go:126` 与 `startup_plan_test.go` 里的断言），而技能已经把目录改成 `memory/` 与 `import/`。这条旅程不再调用 `--plan`，但用户或别的 agent 手动跑它时会拿到一个不存在的文件名；要改就得动 CLI 代码与测试，未在本迭代内做。
