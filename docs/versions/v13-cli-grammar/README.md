# v13-cli-grammar · 把 CLI 的命令面收干净：单数 skill、复数 skills、作者面 version

> **最后更新**：2026-10-22
> **状态**：进行中（定义阶段。`prd.md` 已写；`technical-design.md` 与 `test-plan.md` 待写。CLI 的 `skills` / `skill` 分组、`files` 正名、网关正文已在 `cli/skills-grammar` 分支实现；旧命令实现分支待删除，`version list --all` 与两处 help 分工**待实现 / 待调整**）
> **含收费**：否

<!--
README 是「版本记录主页」，不是详述。每个问题只写一句话 + 链接。
细节一律写在 prd.md / technical-design.md / test-plan.md 里。
这不是风格偏好——复述必然漂移。
-->

## 目标

把 CLI 的命令面收成一条可记住的规则：**`skill` 管一个 skill，`skills` 管多个，`version` 管一个 skill 的版本（作者面）**；旧命令尚未上线，所以不进入正式契约，帮助与网关正文只教新命令。同时把**所有命令的共同行为**（输出流、退出码、分页、确认、凭据、帮助）写成契约，以后新增命令照它做，不再各写各的方言。细节见 [`prd.md`](prd.md)。

## 验收标准

一句话：**顶层只有 8 个命令；`skill list` / `skill search` / `skill ls` 不进入正式命令面；`version` 留在顶层、形状照 `gh release`，页面动作仍交接到浏览器。**

完整 17 条与判定方式见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：**#3、#4、#5、#7 与 #13 是「不满足就不算交付」的五条**——#3/#4 是命令面收口（未上线的旧命令不能带进正式契约），#5 是产品判断（`version` 是作者工作流，不是 `skill` 的子属性），#7 是产品边界（改变调用者能读到什么的动作只在浏览器里发生），#13 是**这套契约能不能约束未来命令**的判据（输出、错误、退出码一旦各写各的，下一批命令就会各漂各的）。

## 这一版明确不做什么

摘要四条最要紧的；**完整范围与每条的理由在 [`prd.md`](prd.md) §范围**。

- 不把 `version` 收进 `skill` —— 先合并 `skill versions` 与 `version list`，再谈嵌套；直接塞进去会造出更糟的混淆
- 不做顶层 `versions` 组 —— `gh release list` / `docker image ls` 都是单数名词组 + `list`，`version` 没有碰撞
- 不做 `version create` —— 新建版本是 `skill submit`，上线在浏览器里由人确认
- 不在 CLI 执行 `publish` / `rollback` / `revoke` / `restore` / `offline` / `online` / `delete` —— 按 ADR 0031 / 0046 / 0061 只在浏览器面存在

## 与目标态的关系

[`target/`](../../target/) 描述的是完整形态。这一版**不改任何产品能力**，只改 CLI 的命令面与帮助：consumer 路径（`skills list` → `skill invoke` → `skill files` → `skill read`）与作者路径（`version …` → 网页上线）都不变。没有需要登记到 [`target/README.md`](../../target/README.md) 差异清单的新能力。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、CLI 命令行为规范、验收与指标、依赖与前置 |
| `technical-design.md` | 命令分派、旧命令负向用例、帮助文本、`version list --all`、测试矩阵（**待写**） |
| `test-plan.md` | 验收映射、用例、环境与数据、结果、已知问题（**待写**） |
| `iterations/` | 小版本迭代记录（只增不改）。**本版尚无迭代记录，目录未建** |

## 影响最大的未决问题

完整列表会落在 `technical-design.md` §开放问题（**待写**）。按重要性排前四条：

1. **`version list --all` 的实现** —— PRD 已定语义：`--limit` 是每页大小（默认 50）、`--all` 翻到底、失败不打印半份、`--cursor` 删除；TD 只剩实现与用例。
2. **旧命令的实现分支怎么清掉** —— 当前分支已经有兼容别名代码，需要在 TD 里列成删除项，避免测试继续把它当能力保护。
3. **`skill help` 与 `version help` 怎么互相指** —— PRD 已定“互相指”；TD 只剩落在哪一行：`skill help` 的 `versions` 行，还是 `version help` 的 `list` 说明行。
4. **`skills list` / `search` 的 `--json`** —— PRD 已定本版不加；网关读两行卡片格式（描述为空时只有地址一行）；标签版再评估。
