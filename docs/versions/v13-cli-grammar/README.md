# v13-cli-grammar · 把 CLI 的命令面收干净：单数 skill、复数 skills、作者面 version

> **最后更新**：2026-10-22
> **状态**：进行中（三份文档已写；实现已在 `cli/skills-grammar` 分支完成，`go test -count=1 ./...` 8 个包全绿、`check_docs.py` 通过；**实现改动尚未提交**）
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
| [`technical-design.md`](technical-design.md) | 命令分派、旧命令负向用例、帮助文本、`version list --all`、测试矩阵 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据、结果（T1–T17 通过）、已知问题 |
| `iterations/` | 小版本迭代记录（只增不改）。**本版尚无迭代记录，目录未建** |

## 实现落在哪

- `skillmaster-cli/cmd/skillmaster/`：命令分派与帮助（`main.go`）、`version list --all`（`version.go`）、`files` 的新输出（`main.go` 的 `files()` / `printFiles`，`files.go` 删掉了旧的 `ls`）
- `skillmaster-cli/internal/api/author.go`：`next` 字段在为空时不再出现在 JSON 里
- `gateway/skillmaster/SKILL.md`：正文按“描述为空只有地址一行”修一处
- `qa/role-matrix/`：探针里的 `skill ls` 改成 `skill files`

取舍与用例见 [`technical-design.md`](technical-design.md) 与 [`test-plan.md`](test-plan.md)。
