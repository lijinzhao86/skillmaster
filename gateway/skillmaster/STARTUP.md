# SkillMaster 入门旅程

这是一条链，不是一个菜单：**每一步都是下一步的前提，任何一步被拒绝或没通过就停在那里**。

服务地址：`https://<host>`
正文上面那行是这个账号与本机的现场状态，说明你现在站在哪一步。

每一步之前都先问人（登录已经做完，不再问）。**人拒绝时不要往下走**，也**不要只说「已停止」**——停下来的时候必须说清三件事：

1. **已完成到哪**（例：33 个 skill 已搬成草稿）。
2. **还剩什么、为什么现在不能往下走**（例：提示词还没写，agent 现在仍从本机找 skill；先把开关打开并验证，再删副本）。
3. **怎么回来**（例：重跑 `skillmaster startup` 看当前位置，从第四步接着做）。

停下是这一段旅程的合法结果，只是它不是「完成」。

## 第一步 · 登录（已完成）

`skillmaster startup` 在打印这段文字之前已经确保这台机器有凭据。核对一下这份凭据是谁的：

```bash
skillmaster auth status
```

## 第二步 · 搬运本机的 skill（先问人）

先看清单，这一步不写服务器：

```bash
skillmaster skills import --dry-run
```

清单来自下面这些目录（存在才探测；只认 `<目录>/<名字>/SKILL.md` 这一层，不递归，跳过 `.` 开头的条目）：

| 客户端 | 用户级目录 | 自动标签 |
|---|---|---|
| 通用位置（多数客户端都读） | `~/.agents/skills/` | `agent:common` |
| Claude Code | `~/.claude/skills/` | `agent:claude` |
| Codex CLI（旧位置） | `~/.codex/skills/` | `agent:codex` |
| Cursor | `~/.cursor/skills/` | `agent:cursor` |
| Gemini CLI | `~/.gemini/skills/` | `agent:gemini` |
| GitHub Copilot CLI | `~/.copilot/skills/` | `agent:copilot` |
| opencode | `~/.config/opencode/skills/` | `agent:opencode` |
| Windsurf | `~/.codeium/windsurf/skills/` | `agent:windsurf` |
| Amp | `~/.config/amp/skills/`、`~/.config/agents/skills/` | `agent:amp` |
| Kiro | `~/.kiro/skills/` | `agent:kiro` |
| Cline | `~/.cline/skills/` | `agent:cline` |
| Roo Code | `~/.roo/skills/` | `agent:roo` |
| Qwen Code | `~/.qwen/skills/` | `agent:qwen` |
| OpenHands | `~/.openhands/skills/` | `agent:openhands` |
| Factory Droid | `~/.factory/skills/` | `agent:factory` |
| WorkBuddy | `~/.workbuddy/skills/` | `agent:workbuddy` |
| DeepSeek Harness | `~/.dsh/skills/` | `agent:dsh` |

标签记的是**来源目录**，不是兼容性：`~/.claude/skills` 里的东西，Codex、Cursor、Zed 读的也是同一份 `SKILL.md`。

把清单转述给人，人同意之后（`--yes` 是唯一的同意方式）：

```bash
skillmaster skills import --yes
```

- 表外的目录：`skillmaster skills import <目录> --yes`，搬上去的 skill **不带**自动标签。
- 人想换掉自动标签：加 `--tag work`（多个用逗号分隔，给了就完全覆盖默认）。
- 同一份内容出现在两个目录：合并成一条，标签取并集。同名但内容不同：**不搬**，汇总里列出来，由人决定用哪一份。
- 单个 skill 失败不会中止整批；跑完看汇总里的成功 / 失败两栏——失败的那些仍在原处，如实报给人。

**人拒绝搬运，旅程到这里结束**：平台上没有内容，后面几步都无从做起。告诉人随时回来重跑这一步。

搬上去的是**草稿**——平台上还不能用。

## 第三步 · 上线（只能人做）

**这一步不执行命令，也不能由 agent 代劳。** 上线是控制台操作，要由人点：

打开 <https://console.skillmasterai.com/publish> ，在「待上线」页勾选这些草稿，一次确认上线。

人回来之后，**不要听一句「都上线了」就往下走**，跑一次只读的核对：

```bash
skillmaster skills pending
```

- **队列空了**：进入第四步。
- **还列着草稿**：旅程停在这里。把剩下哪几条报给人，并说明为什么不能往下走——它们还是草稿，库里看不到；这时候往下走只会得到一半可用的平台。

## 第四步 · 写系统提示词（先问人）

先取规则：

```bash
skillmaster help usage
```

它末尾有一段以 `<!-- skillmaster:begin -->` 开始、`<!-- skillmaster:end -->` 结束的规则。把这一段**原样**写进这个 agent 的**用户级**指令文件——那是「这台机器上以后每次都读」的位置，项目级只影响一个仓库。

**没有所有 agent 共用的文件名。** 按这个 agent 查下表（调研与来源见 `docs/research/agent-context-loading.md`）：

| agent | 写这个文件 |
|---|---|
| Claude Code | `~/.claude/CLAUDE.md` |
| Codex CLI | `~/.codex/AGENTS.md` |
| Cursor | `~/.cursor/rules/skillmaster.mdc`（`alwaysApply: true`） |
| Gemini CLI | `~/.gemini/GEMINI.md` |
| GitHub Copilot CLI | `~/.copilot/copilot-instructions.md` |
| opencode | `~/.config/opencode/AGENTS.md` |
| Windsurf / Devin Desktop | `~/.codeium/windsurf/memories/global_rules.md` |
| Amp | `~/.config/amp/AGENTS.md` |
| Zed | `~/.config/zed/AGENTS.md` |
| Kiro | `~/.kiro/steering/AGENTS.md` |
| Cline | `~/.cline/rules/skillmaster.md` |
| Roo Code | `~/.roo/rules/skillmaster.md` |
| Qwen Code | `~/.qwen/QWEN.md` |
| OpenHands | 没有文档化的用户级位置——**问用户** |
| Factory Droid | `~/.factory/AGENTS.md` |
| WorkBuddy | `~/.codebuddy/CODEBUDDY.md` |
| DeepSeek Harness | `~/.dsh/AGENTS.md` |

- **表里没有这个 agent，或者文件不存在、不知道写哪：问用户**，不要挑一个看起来像的。
- **改之前先问人**，并且**只改人指定的那一个文件**。
- 原文件留一份同目录、带时间戳的 `.bak`。
- 连跑两次不会写出两份：标记让写入幂等。
- **写完回读一遍**：标记段在文件里、且只有一份。项目级另有更高优先的文件（Zed 的 `.rules`、Claude Code 的 `CLAUDE.md`）时，确认它没有把这份盖掉。
- 人想限定这个 agent 只看某些标签时，在标记段**前面**加一行（默认不限定，整行不写）：
  `只使用带 agent:claude、agent:common 标签的 skill。`

**人拒绝写提示词，旅程到这里结束**：平台上的 skill 已经可用，但这个 agent 还按原来的方式找 skill。

**写完不要马上去删本机副本**——先做第五步。那是这条旅程里唯一一次「先开新路、再拆旧路」的验证。

## 第五步 · 验证新开关真的能用（只读）

三件事同时成立才算通过：

1. **提示词里那段只有一份**，并且与 `skillmaster help usage` 末尾那段**逐字一致**（重新跑一次 `help usage` 对比）。
2. **列得到**：`skillmaster skills list` 列出刚才上线的那些，条数与上线数一致。
3. **取得回来**：挑其中一条，`skillmaster skill invoke <地址>` 能取到正文。

**任一不成立就停**，把哪一条没通过说清楚。此时：

- **本机副本一条都不要动**——它们是你现在唯一确定能用的东西。
- 提示词退回原样：用第四步留下的 `.bak`（`mv <文件>.bak <文件>`）。
- 修好之后从第四步重来。

## 第六步 · 下线本机副本（先问人）

先看清单，这一步不动任何文件：

```bash
skillmaster skills retire
```

人同意之后：

```bash
skillmaster skills retire --yes
```

它做两件事，顺序不能反：

1. **先把本机现有的 skill 全部打成一个 zip**，放在 `~/.skillmaster/skills-backup-<时间戳>.zip`。包里的路径是本机绝对路径去掉开头的 `/`，`unzip -d /` 就回到原位。
2. **再删掉平台上已经上线的那些同名 skill 的本机副本**。**只要还有一条同名的是草稿，整批一条都不删**；平台上根本没有的本地 skill 留着并单列。

单条失败不会中止整批；跑完看汇总里的删除 / 保留 / 失败三栏。

**人拒绝下线，旅程到这里结束**：平台可用、agent 已切换，但本机仍有副本。告诉人随时回来重跑 `skillmaster skills retire`。

## 只有走到这里才算完成

三件事同时成立时，才可以说旅程完成：

1. 平台上这些 skill **已上线**（`skillmaster skills pending` 为空）。
2. 这个 agent 的系统提示词**已写入并验证通过**（第五步的三条）。
3. 本机对应的副本**已下线**（`skillmaster skills retire` 的清单为空）。

平时怎么用：`skillmaster help usage` 是四条稳定规则的唯一来源，各命令的参数与输出查 `skillmaster help <命令>`。
