# v27 · 技术设计

> **最后更新**：2026-10-28 · **所属版本**：[v27](README.md)

## 架构

改动落在三处：CLI 的两个包，以及站点与设计稿的一处文案。服务端、控制台、数据库都不动。

| 模块 | 改了什么 | 没改什么 |
|---|---|---|
| `skillmaster-cli/internal/prompt/` | `block.md` 正文改成「默认先查」；`block.go` 只剩 `Block` / `Digest` / `DigestOf` / `Normalise`——标记常量与定位 / 替换 / 追加的辅助全部删除 | `store.go` 只剩读：`Record` / `Store` / `LoadStore`。写入路径（`SaveStore` / `Save` / `Upsert` / `RemoveRecord` / `Find`）与 `notified` 字段一起删除——再没有任何命令写 `prompt.json` |
| `skillmaster-cli/cmd/skillmaster/` | `prompt` 组只剩 `print`，四个动词走退役分支；`startup` 删掉 `--plan` / `--agent` / `--file`，`startup_plan.go`（615 行）与 `startup_plan_test.go`（375 行）整个删除，`promptRecordLine` 挪进 `prompt_record.go`；`submit` 的审批地址 | 登录、提交、上线、下载、检索的既有命令 |
| `skillmaster-site/` + `design/` | 首页那条安装指令句 | 首页结构与样式 |

**为什么不留标记**：见 [ADR 0110](../../decisions/0110-the-block-is-a-paragraph-the-cli-stops-managing.md)。一句话：没有标记就没有可靠边界，与其用模糊匹配猜「哪一段是我们写的」，不如不管理。

**为什么 `--plan` 整个删**：见 [ADR 0111](../../decisions/0111-startup-takes-no-flags.md)。一句话：清单的权威已经搬进旅程正文，CLI 里那份副本已经和正文对不上，而且全仓没有调用者。

## 数据模型

| 东西 | 形状 | 变化 |
|---|---|---|
| 规则段 | `block.md`，`go:embed` 嵌进二进制，读入时把 CRLF 归一化成 LF | 正文重写（9 行）；去掉首尾两行标记 |
| 摘要 | `sha256:` + 正文的哈希（`prompt.Digest()`） | 随正文变化；**不再用于任何对外判断**，只是 `prompt.json` 旧记录里那个字段的形状 |
| 记录 `prompt.json` | `{schema:2, files:[{path, agent, digest, version}]}`；旧文件里可能还有 `notified`，读到即忽略 | **只读**：这一版不再写新记录。唯一读者是 `startup` 的 `promptRecordLine()`——它把记录里的 `agent` 标签去重后说成「这台电脑上写过这段说明的 Agent：…」 |
| 用户文件里的那段说明 | 纯正文，一个人文件里的一段话 | 不再有 `<!-- skillmaster:begin/end -->` |

**没有迁移**：不扫描、不改写用户文件。

**旧标记段怎么办（四份文档共用这一句口径）**：CLI 不清理它——`prompt` 组不写文件，`startup` 也不读用户文件，没有任何命令知道那段标记在哪。要清掉有两条路：人自己删，或者重跑 `skillmaster startup`——写说明那一步会先把文件里已有的说明读出来、用选项问「换成 SkillMaster 的 / 保留原来的」，只有人选前者才整段替换（旅程的规则，见 v26 [迭代 0004](../v26-startup-guidance-and-agent-paths/iterations/0004-startup-skill-restructured-for-users.md)）。

## 接口

命令行面（`skillmaster help` 的契约仍适用：帮助 ≤ 2 KB、无 ANSI、退出码 0/1/2/4）：

| 命令 | 行为 |
|---|---|
| `prompt print` | 打印 `Block()`，逐字节；参数多于零个 → 用法错误（退出 2） |
| `prompt install` / `status` / `update` / `uninstall` | 往 **stderr** 打印**三行**退役说明（首行含「已经退役」，另两行给出 `skillmaster prompt print` 与 `skillmaster startup` 两个去处），退出 **2**；stdout 为空；不读写任何文件，参数一律不看 |
| `startup` | **不接受任何参数**；`--plan` / `--agent` / `--file` 都是未知参数（退出 2）。stdout 是旅程正文；stderr 两行，都 best effort（读不到就不打）——「你在哪」（ADR 0096 的状态行）与「这台电脑上写过这段说明的 Agent」（ADR 0104） |
| `skill submit` | 草稿分支的「审批地址」= `{web}/skills/{ns}/{name}?tab=versions&version={草稿码}&sub=actions`，与 `version open --sub actions` 同一构造；非草稿分支仍是技能页 |

**退役分支的位置**：`retiredPromptVerbs` 在派发 `switch` **之前**判断。这样它在源码里不算 `prompt` 的子命令，帮助树（`declarations.go`）与派发（`switch`）仍然一一对应——`tree_test.go` 检查的正是这件事。

**命令路径计数**：`declarations_test.go` 的 `wantCommandPaths` 是 61（v26 是 65：`prompt` 少四个动词，`startup` 从来只算一条）。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 规则段正文；`prompt` 组退役；`startup` 去掉全部参数；`submit` 地址；首页文案 | prd §验收 1–8；`cd skillmaster-cli && go test ./...`、`home-check`、站点测试 |

一期交付，没有 P1。

## 开放问题

1. **用户想撤回这段说明怎么办？** 现在只能自己删那几行。要不要在旅程里加一个「帮你把那段说明去掉」的选项？**判据**：等第一位用户提出这个需求时再加；在那之前，删掉几行不构成负担，而多一个「删别人的文件」的动作要配一套请示与退路。
2. **`prompt.json` 还要不要保留？** 这一版只有一个读者（`startup` 的「写过这段说明的 Agent」）。**判据**：若下一次动 `startup` 时判定这行提示不再需要（例如旅程已经能自己问出「你用的是哪个 Agent」并且不问也知道要不要重写），就删掉整个文件与它的读取；在那之前保留只读。
