# 0110 · 规则段是用户文件里的一段话，CLI 不再用标记段管理它

- **状态**：已采纳（2026-10-28）
- **关联**：[0102](0102-the-rule-block-ships-with-the-cli.md)、[0101](0101-the-cli-never-deletes-files.md)、[0109](0109-the-rule-block-defaults-to-searching-first.md)、迭代 [0005](../versions/v26-startup-guidance-and-agent-paths/iterations/0005-rule-block-searches-by-default.md)

## 背景

从 v20 起，写进用户文件的那段说明被两行 HTML 注释包着（`<!-- skillmaster:begin -->` / `<!-- skillmaster:end -->`）。这两行不是给人看的，是给 CLI 用的：`prompt status` 靠它们核对文件里的是不是最新，`prompt update` 靠它们找到旧的那段并原地替换，`prompt uninstall` 靠它们只切掉那一段、不碰文件其它内容。

2026-10-28 规则段的正文重做（0109）时，产品负责人提出把这两行也去掉：**这段说明是用户自己文件里的一段话，不该带机器标记**。同时，新版的 `startup` 旅程已经改成由 agent 直接写入、不再调用 `prompt install`，标记段在旅程里也不再产生。

## 决定

- **规则段不再带任何标记**：`block.md` 就是一段普通正文。
- **整组只留 `prompt print`**：打印二进制自带的正文，供人粘贴、供 agent 取用。
- **`prompt install`、`prompt status`、`prompt update`、`prompt uninstall` 一起退役**：命令还在，但只回答三行退役说明（首行「已经退役」，另两行指向 `prompt print` 与 `skillmaster startup`），退出码 2。它们不再出现在帮助页的命令树里。
- **`startup --plan` 的「让 Agent 先来 SkillMaster 找技能」改用正文判断**：文件里有这段正文就算完成——这条判断不看记录，所以 agent 手写的文件也能判对（旧实现只能判 CLI 自己写过的文件）。**这条判据随后随 `--plan` 一起被删掉**（[0111](0111-startup-takes-no-flags.md)）：不是换个写法，是整个命令没了。

## 理由

**被否掉的选项：保留标记、只把 `block.md` 的标记挪到写入时再加。** 那等于说「给人看的正文不带标记，但文件里仍然要有标记」——用户打开文件还是看得见两行注释，问题没解决。

**被否掉的选项：去掉标记，改成按正文指纹模糊匹配。** 用户会改这段说明（删一句、按自己的话重写），模糊匹配要在「像到什么程度算同一段」上做判断，判错就是替换错位置、删错内容。**没有标记就没有可靠的边界**，与其猜，不如不管理。

**代价如实说**：CLI 从此不能替用户更新或撤回这段说明。要更新，重跑 `startup` 写一遍；要撤回，用户自己删掉那几行。这与「这段说明是用户自己的」是一致的，但确实丢掉了一次命令更新全部机器的能力。

## 后果

- 帮助页少了四条命令（65 → 61）。已经从旧版本升级的人，命令还在、会得到一个说明退役原因的答复。
- 旧文件里遗留的标记段不会被自动清理：CLI 没有任何命令读用户文件。要清掉由人自己删，或重跑 `startup` 时在选项里选「换成 SkillMaster 的」——写说明那一步会先把文件里已有的说明读出来问人。
- 之前 `startup --plan` 里「对新写的文件永远显示待做」那个已知问题，最终随 [0111](0111-startup-takes-no-flags.md) 删除 `--plan` 而消失。
- 「规则段是旧版」的一次性提示随 `noteStaleBlock` 一并删除——没有标记就没有可信的「旧版」判断。
