# ADR 0111 · 删掉 `startup --plan`

- **状态**：已采纳
- **日期**：2026-10-28
- **依赖**：[`versions/v27-rule-block-and-prompt-retirement/`](../versions/v27-rule-block-and-prompt-retirement/)；取代 [0104](0104-the-agent-knows-where-its-user-level-file-is.md) 里以 `--file` / `--agent` 为主语的部分，以及 [0105](0105-writing-to-a-user-file-starts-with-a-plan.md) / [0106](0106-the-checklist-is-recomputed-not-recorded.md) / [0108](0108-importing-local-skills-is-optional.md) 里以 `startup --plan` 为主语的部分

## 背景

v26 让 `startup --plan` 成为「这台机器还差什么」的唯一出口（[0106](0106-the-checklist-is-recomputed-not-recorded.md)）：七项清单、只读、每步重算、stderr 末行给下一步指针。它成立的前提是**清单住在 CLI 里**——v26 交付的是「CLI 打印一张清单，agent 照着读步骤文件」。

2026-10-28 的小版本（v26 迭代 0004）把旅程技能改成「欢迎 → 两项多选 → 逐项做」，清单与下一步都写进技能正文，由 agent 说给人听。从那以后 `--plan` 就是 CLI 里的一份副本，而且已经和正文对不上：

- 技能是**两项**多选，CLI 还在算**七项**；
- 它的下一步指向 `steps/0N-*.md`，而技能目录早已改成 `memory/` 与 `import/`；
- 它的一项（「让 Agent 用 SkillMaster 自己的文件夹」）对应的授权对象早就不存在——备份改放在 `~/skillmaster-skills-<日期>.tar.gz`；
- `--agent` / `--file` 两个参数只为它存在（[0104](0104-the-agent-knows-where-its-user-level-file-is.md)）。

2026-10-28 全仓核查（`grep` 覆盖 `.md` / `.go` / `.ts` / `.tsx` / `.html` / `.sh` / `.yml`）：没有任何控制台页面、站点页面、安装说明、CLI 输出或旅程正文引导人跑 `startup --plan`；提到它的只有它自己的帮助条目、README，以及决策与版本的记录。

## 决定

在 v27 里删掉 `startup --plan`、`startup --agent`、`startup --file`：`skillmaster startup` 不接受任何参数，带参数即未知参数、退出 2。`startup` 原有的输出不变——旅程正文在 stdout，「你在哪」与「这台电脑上写过这段说明的 Agent」两行在 stderr。

## 理由

- **副本会漂，而且已经漂了。** 清单的权威在旅程正文；CLI 再算一份，改一次清单要同时改 Go 代码、测试、四份文档，而正文改一次不用发版。
- **没有调用者。** 现在删掉不需要迁移任何人；将来真有需要再写回来，比长期养一个没人跑的命令便宜。
- **否掉「留着当只读检查」**：那等于长期养两份口径。两份不一致时，人不知道该信哪份——本轮审计里就已经出现「七项 / 六项」两种说法。
- **否掉「下一版再删」**：本版正好在收窄命令面（[0110](0110-the-block-is-a-paragraph-the-cli-stops-managing.md) 让 `prompt` 只剩 `print`），一次做完；拖到下一版，它只会更没人知道存在。

## 后果

- **`prompt.json` 只剩一个读者**：`startup` 打「这台电脑上写过这段说明的 Agent」那一行（[0104](0104-the-agent-knows-where-its-user-level-file-is.md) 要回答的「我换了一个 Agent，还要再写吗」）。
- **`--file` 的通用路径检查随它消失**（目录、父目录、主目录、项目级警告）。写文件的是 agent，位置由 agent 自己确定（[0104](0104-the-agent-knows-where-its-user-level-file-is.md)）；CLI 不再替人检查路径，也不再有任何命令读用户文件。
- **ADR 0105 / 0106 / 0108 里以 `startup --plan` 为主语的部分失效**；其余决定（写入前先出计划、清单按事实重算、传技能可选）仍然成立，但承载它们的是旅程正文，不是 CLI。
- **脚本里若还有 `startup --plan`**：得到未知参数与退出码 2，而不是被静默当成 `startup` 跑一遍。
