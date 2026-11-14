# 第 1 步 · 找到这台电脑上的技能（只读）

**这一步做什么**：找出这个 Agent 实际加载的技能，把名单报给他看。
**什么时候做**：用户选了第三块「一起搬进来」之后。
**做完的标志**：你手上有一份技能目录清单（数目 ≥ 1），已经报给他看过。

## 步骤

1. **对他说**：

   > 我看看你电脑上现在有哪些技能。

2. **按表找**。下面的表是线索，不是判据——先确认那一行是不是你**实际加载 skill 的目录**（不是项目级，也不是这台机器上另一个 Agent 的）。只认 `<目录>/<名字>/SKILL.md` 这一层，不递归，跳过 `.` 开头的条目：

   | Agent | 目录 |
   |---|---|
   | 通用位置（多数 Agent 都读） | `~/.agents/skills/` |
   | Claude Code | `~/.claude/skills/` |
   | Codex CLI | `~/.agents/skills/`、`~/.codex/skills/`（安装工具常写后者） |
   | Cursor | `~/.cursor/skills/`、`~/.agents/skills/`、`~/.claude/skills/` |
   | Gemini CLI | `~/.gemini/skills/`、`~/.agents/skills/` |
   | GitHub Copilot CLI | `~/.copilot/skills/`、`~/.agents/skills/` |
   | opencode | `~/.config/opencode/skills/`、`~/.claude/skills/`、`~/.agents/skills/` |
   | Windsurf / Devin Desktop | `~/.codeium/windsurf/skills/`、`~/.config/devin/skills/`、`~/.agents/skills/` |
   | Amp | `~/.config/amp/skills/`、`~/.config/agents/skills/`、`~/.agents/skills/`、`~/.claude/skills/` |
   | Zed | `~/.agents/skills/` |
   | Kiro | `~/.kiro/skills/` |
   | Cline | `~/.cline/skills/` |
   | Roo Code | `~/.roo/skills/`、`~/.agents/skills/` |
   | Qwen Code | `~/.qwen/skills/`、`~/.agents/skills/` |
   | OpenHands | `~/.openhands/skills/`、`~/.agents/skills/` |
   | Factory Droid | `~/.factory/skills/`、`~/.agents/skills/`（`~/.agent/skills/`） |
   | WorkBuddy | `~/.workbuddy/skills/`、`~/.workbuddy-ai/skills/`（社区与内置脚本口径，官方没写） |
   | DeepSeek Harness | `~/.dsh/skills/`（CLI 的扫描表里有） |

   表里没有这个 Agent、或那一行不是你加载 skill 的地方：换成你自己知道的目录；说不清就先扫你知道的位置。**不要问用户目录路径。** 看一个目录只要一层：

   ```bash
   ls -d <目录>/*/SKILL.md
   ```

   没有输出、报 `No such file or directory`、或 zsh 报 `no matches found`，都算这个目录里没有技能，不是命令坏了。

   **跳过 `skillmaster` 这个技能**：它是 SkillMaster 自己的技能，只装在本地，不传，名单里也不报它。传上去只会多出一份，改了一份另一份不知道。

3. **把名单给他看**（只报名字，不给路径）：

   > 你电脑上有 12 个技能。我把它们传到 SkillMaster——传上去先是草稿，默认只有你看得见；要不要上线，等你在网页上自己点。

   **如果第三块开头已经报过名单，这里只说「我开始传了」**，不要再报第二遍数字。

4. **同名不同内容的**：用一句大白话说清哪份更新/更全，把两种做法摆给他选；**推荐的那份放在第一个并写「（推荐）」**。用这个 Agent 自己的选项组件；没有就写进一句话，推荐的在前面，例如「传新那份，还是传旧那份？」。**不要让他选文件**。**两份都留在电脑上，只传他选的那一份**；他拿不定主意就跳过这一对。记住你实际上传的是哪一个目录（路径只给你自己用）。

## 出问题怎么办

| 情况 | 对他说 | 然后 |
|---|---|---|
| 一个技能都没找到 | 你电脑上暂时没有可上传的技能，这件跳过，我们收个尾。 | 不要编；回 `SKILL.md` 的「收尾」 |
| 他问传上去别人会不会看到 | 默认看不到：先是草稿，只有你看得见；上线也要你自己在网页上点，要不要公开是另一件你单独决定的事。 | 接着报名单（步骤 3） |
| 他纠正了名单 | — | 按他说的重新报一遍（步骤 3），再回到步骤 4 |
| 他看了名单说先不传 | — | 回 `SKILL.md` 的「收尾」 |

## 下一步

名单报过了、同名那份也定了（没有同名的就直接过）→ 读 `import/02-upload.md`。他说先不传，或者一个都没找到：回 `SKILL.md` 的「收尾」。
