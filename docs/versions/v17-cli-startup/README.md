# v17-cli-startup

> **状态**：进行中，实现阶段。四份文档与 [ADR 0088](../../decisions/0088-cli-major-version-is-the-api-generation.md) / [0089](../../decisions/0089-no-local-skills.md) / [0092](../../decisions/0092-retire-local-copies-only-after-they-are-live.md) 已锁定；P0 的 CLI 部分（`--version`、代次常量、`help env`）与两个安装脚本已落地，分发流水线与三个旅程命令尚在实现。
>
> **两处已由 [v20](../v20-guidance-on-platform/README.md) 取代**（[ADR 0096](../../decisions/0096-guidance-lives-on-the-platform.md)）：旅程正文不再走 `/gateway/STARTUP.md`，而是平台上的 `skillmaster/startup` 技能；可粘贴的规则段不再由 `help usage` 携带，而是该技能的第三步文件。本文档里凡写「`/gateway/STARTUP.md`」「`help usage` 的快照」「四条规则 + `--no-login`」之处，以 v20 为准。

## 目标

让一个已经在用某个 agent 的人，用**一段可以复制给 agent 的话**完成三件事：装上 CLI、把本机已有的 skill 搬到 SkillMaster、把 agent 的 skill 读写切换成走 SkillMaster。旅程正文托管在服务端，`skillmaster startup` 取来打印，agent 照做——走的是 `/gateway/{file}` 这条匿名文件路由，**本机不装任何 skill**。

细节见 [`prd.md`](prd.md)。

## 这一版明确不做什么

| 不做 | 原因 |
|---|---|
| 自动发现任意 agent 的 skill 目录 | 每个 agent 的约定不同且会变；先覆盖已知位置，其余由用户指定 |
| 迁移后校验每个 skill 的内容是否可解析 | 迁移是搬运，不是评估；原样搬到平台，坏的在平台上也是坏的 |
| 替用户挑选哪些 skill 该搬 | 旅程里列清单、由人决定 |
| 在本机技能目录里安装任何 skill | 四条稳定规则直接写进系统提示词（[ADR 0094](../../decisions/0094-load-the-library-at-session-start.md)） |
| 把本机副本指针化（软链到平台缓存） | 下线只有一种形状：备份后删除。指针化要求平台侧有可离线随取的缓存，是另一个产品形态（[ADR 0092](../../decisions/0092-retire-local-copies-only-after-they-are-live.md)） |
| 批量上线本身 | [v18](../v18-batch-publish/README.md) 已经交付控制台「待上线」页；本版不新增控制台操作，只在旅程里指路 |

## 验收标准

见 [`prd.md`](prd.md) §验收与指标——五步旅程各有一组可判定的验收，外加「旅程之后 · 日常取用」一组，共 29 条。

## 与目标态的关系

`docs/target/` 描述的是「agent 不装 skill 副本、按需拉取」的完整形态。这一版补的是**从现状走到那个形态的桥**：人手上已经有一批本机 skill，不强迁就等于让人放弃存量。

## 本版文档

| 文档 | 说明 |
|---|---|
| [`prd.md`](prd.md) | 用户、场景、范围、三步旅程的验收 |
| [`technical-design.md`](technical-design.md) | 分发、startup 命令、搬运的接口与数据流、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据 |
