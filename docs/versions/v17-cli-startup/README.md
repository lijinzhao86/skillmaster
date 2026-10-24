# v17-cli-startup

> **状态**：进行中，定义阶段。四份文档已成形；实现尚未开始（工作区里有三处未提交的前置改动：`--version`、`internal/api` 的代次常量、`config.DefaultServer` 由 `const` 改 `var`）。

## 目标

让一个已经在用某个 agent 的人，用**一段可以复制给 agent 的话**完成三件事：装上 CLI、把本机已有的 skill 搬到 SkillMaster、把 agent 的 skill 读写切换成走 SkillMaster。旅程正文托管在服务端，`skillmaster startup` 取来打印，agent 照做——走的是 `/gateway/{file}` 这条匿名文件路由，**本机不装任何 skill**。

细节见 [`prd.md`](prd.md)。

## 这一版明确不做什么

| 不做 | 原因 |
|---|---|
| 自动发现任意 agent 的 skill 目录 | 每个 agent 的约定不同且会变；先覆盖已知位置，其余由用户指定 |
| 迁移后校验每个 skill 的内容是否可解析 | 迁移是搬运，不是评估；原样搬到平台，坏的在平台上也是坏的 |
| 替用户挑选哪些 skill 该搬 | 旅程里列清单、由人决定 |
| 在本机技能目录里安装任何 skill | 三条稳定规则直接写进系统提示词（[ADR 0089](../../decisions/0089-no-local-skills.md)） |
| 处置本机已有的 skill 副本（指针化 / 删除） | 搬上去的全是草稿、列表里看不到；此时撤副本等于让人零可用副本。**与批量上线同一版做** |
| 批量上线 | 是控制台操作，要带一态新设计稿，且按 ADR 0081 要配 CLI 命令。**下一版** |

## 验收标准

见 [`prd.md`](prd.md) §验收与指标——三步旅程各有一组可判定的验收，外加「旅程之后 · 日常取用」一组，共 20 条。

## 与目标态的关系

`docs/target/` 描述的是「agent 不装 skill 副本、按需拉取」的完整形态。这一版补的是**从现状走到那个形态的桥**：人手上已经有一批本机 skill，不强迁就等于让人放弃存量。

## 本版文档

| 文档 | 说明 |
|---|---|
| [`prd.md`](prd.md) | 用户、场景、范围、三步旅程的验收 |
| [`technical-design.md`](technical-design.md) | 分发、startup 命令、搬运的接口与数据流、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据 |
