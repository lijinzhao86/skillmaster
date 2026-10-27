# 各 agent 怎么自动加载指令与 skill

> **核对日期**：2026-10-27（2026-10-27 复核 WorkBuddy 一行）。**用途**：决定 v17 旅程第四步「把三条规则写进系统提示词」到底写到哪个文件，以及这个动作能不能对所有 agent 生效。
> **口径**：以客户端自己的文档与源码为准；第三方整理只作线索。**查不到官方依据的标「未核实」**，不写成结论。客户端迭代很快，这张表会过期——过期就重新核对，不要凭记忆改。

## 三句话结论

1. **不存在一个「所有 agent 都读」的指令文件。** 每个客户端读自己的名字：Claude Code 读 `CLAUDE.md`、Codex 读 `AGENTS.md`、Gemini 读 `GEMINI.md`、Copilot 读 `copilot-instructions.md`、Qwen 读 `QWEN.md`……**第四步只能按 agent 选文件；选不出来就先查自己的配置或上下文，仍不确定就问「你用的是哪个 AI」，再直接写用户级记忆。**
2. **`AGENTS.md` 是覆盖面最广的那个，但不是万能的。** 项目级它大体通行；可 Claude Code 只在**没有任何 `CLAUDE.md`** 时读它，Zed 只在没有 `.rules` / `.cursorrules` 等更高优先文件时读它——**写了不等于被读到**。
3. **skill 的自动触发靠「description 进上下文」，而这件事要求 skill 装在本机。** 我们这一版不装 skill（ADR 0089），所以触发只能靠指令文件；规范里那套「description 即触发」在这里用不上。**应对是「会话开始时先加载库」**——把 `skillmaster skills search` 写成规则的第一条（[ADR 0102](../decisions/0102-the-rule-block-ships-with-the-cli.md)；早期的 ADR 0094 / 0096 已被取代）。它是指令，不是机制保证。

## 一、指令文件：写哪里才会每次会话都被读到

| 客户端 | 用户级（整台机器） | 项目级 | 备注 |
|---|---|---|---|
| Claude Code | `~/.claude/CLAUDE.md`（另可 `~/.claude/rules/*.md`） | `CLAUDE.md` / `.claude/CLAUDE.md` | 拼接 CWD 及其祖先；**子目录文件按需加载**；`AGENTS.md` 仅在没有 CLAUDE.md 时读（v2.1.277+） |
| Codex CLI | `~/.codex/AGENTS.md`（`AGENTS.override.md` 优先；目录可由 `CODEX_HOME` 改） | 仓库根到 CWD 每层 `AGENTS.md` | 每目录只取一个；累计默认 32 KiB 上限 |
| Cursor | 账号级 User Rules，**在应用内 Customize → Rules**，官方未给文件路径 | `.cursor/rules/*.mdc`（必须 `.mdc`）或 `AGENTS.md` | `~/.cursor/rules` **不是**用户级；`.md` 放在 rules 目录会被忽略 |
| Gemini CLI | `~/.gemini/GEMINI.md` | `GEMINI.md`（或把 `AGENTS.md` 加进 `context.fileName`） | 全部拼接；JIT 加载子目录 |
| Copilot CLI | `~/.copilot/copilot-instructions.md`（`COPILOT_HOME` 可改目录） | `.github/copilot-instructions.md` 或 `AGENTS.md` | 兼容读 `CLAUDE.md`、`GEMINI.md` |
| opencode | `~/.config/opencode/AGENTS.md` | `AGENTS.md`（向上找） | 无 AGENTS.md 时回退 `CLAUDE.md` |
| Windsurf / Devin Desktop | `~/.codeium/windsurf/memories/global_rules.md` | `AGENTS.md` 或 `.devin/rules/*.md` | 全局规则 6,000 字符上限 |
| Amp | `~/.config/amp/AGENTS.md`、`~/.config/AGENTS.md` | `AGENTS.md` | 每目录回退 `AGENT.md` → `CLAUDE.md` |
| Zed | `~/.config/zed/AGENTS.md` | **只取第一个命中的**：`.rules` / `.cursorrules` / `.windsurfrules` / `.clinerules` / `copilot-instructions.md` / `AGENT.md` / `AGENTS.md` / `CLAUDE.md` / `GEMINI.md` | 有 `.rules` 时 `AGENTS.md` 静默失效 |
| Kiro | `~/.kiro/steering/AGENTS.md` | `AGENTS.md` 或 `.kiro/steering/*.md`（`inclusion: always`） | steering 支持 `always` / `fileMatch` / `manual` |
| Cline | `~/Documents/Cline/Rules/`（macOS/Linux 的默认目录；另搜 `~/.cline/rules`、`~/Cline/Rules`、跨工具 `~/.agents/AGENTS.md`） | `.cline/rules/*.md`、`.clinerules/`、`AGENTS.md` | workspace 覆盖 global |
| Roo Code | `~/.roo/rules/*.md` | `.roo/rules/*.md`、`AGENTS.md` | 分层读取、按文件名排序 |
| Qwen Code | `~/.qwen/QWEN.md` | `QWEN.md`（+ `.qwen/QWEN.local.md`） | 也读 `AGENTS.md` |
| OpenHands | **没有文档化的用户级位置** | `AGENTS.md`（仓库根） | 也认 `CLAUDE.md`、`GEMINI.md` |
| Factory Droid | `~/.factory/AGENTS.md` | `AGENTS.md`、`.factory/AGENTS.md`、`.agents/AGENTS.md` | 初始 80,000 字符预算；官方只写到目录 `~/.factory/`，`AGENTS.md` 这一层未逐字写明 |
| WorkBuddy | `~/.workbuddy/user-<uid>-<scope>/MEMORY.md`（**源码 + 日志核实 2026-10-27**：`MemoryCollector` 读 `<userDir>/MEMORY.md` 并注入 `<user_memory>`，上限 4,000 字符；`WorkbuddyPathService` 把 `<userDir>` 定为 `设备根/user-<uid>-<scope>`，本机日志 `[MemoryCollector] user MEMORY.md dir=…/user-773f…-personal scoped=yes`） | `CODEBUDDY.md` / `.codebuddy/CODEBUDDY.md` / `AGENTS.md`（**项目级**上下文候选）；项目记忆在 `<仓库>/.workbuddy/memory/MEMORY.md` | **`CODEBUDDY.md` 不是用户级**：WorkBuddy 的 `ProjectContextSection` 从 CWD 找它；`~/.codebuddy/CODEBUDDY.md` 是 CodeBuddy CLI 的路径，WorkBuddy 不读 |
| DeepSeek Harness | **未核实**（官方 README 未写用户级位置） | `AGENTS.md`（仓库根） | 开发预览版，路径可能变 |

**读法**：给一个 agent 写规则，先按它的「用户级」列找文件；那一格写着「没有」或只有第三方来源的，**不要猜**；先查自己的配置或上下文，仍不确定就问「你用的是哪个 AI」，再直接写用户级记忆，不问路径。**还有一类要单独处理：路径按账号分目录**（WorkBuddy 是 `user-<uid>-<scope>`），安装器不能假设一个固定路径；由 agent 从自己的配置或上下文里发现目录，仍不确定就问「你用的是哪个 AI」，再直接写用户级记忆。

## 二、skill 目录与「何时进上下文」

| 客户端 | 读 `~/.agents/skills/` | 读 `~/.claude/skills/` | 启动时进上下文的内容 |
|---|---|---|---|
| Claude Code | **否**（官方未列，社区实测不读） | 是 | name + description |
| Codex CLI | 是（`~/.agents/skills`） | 否 | name + description + 路径（目录预算 8,000 字符） |
| Cursor | 是 | 是（兼容） | 官方未逐字说明启动注入，**部分未核实** |
| Gemini CLI | 是（`.agents` 为该层别名，优先于 `.gemini`） | 否 | name + description，正文要 `activate_skill` 且用户确认 |
| Copilot CLI | 是（个人 `~/.agents/skills`） | 个人级未列（项目级 `.claude/skills` 读） | description 驱动选择，**启动元数据部分未核实** |
| opencode | 是 | 是 | name + description |
| Windsurf / Devin | 是 | 开启 Claude 兼容时读 | name + description |
| Amp | 是 | 是 | name + description |
| Zed | 是（原生位置） | 否 | name + description（catalog 上限 50 KB） |
| Kiro | 否 | 否 | 自家的用户级目录是 `~/.kiro/skills/`（官方文档未提 `.agents` / `.claude`） |
| Cline | **否**（官方只列 `~/.cline/skills/`） | 项目级读 `.claude/skills/` | 元数据约 100 token/skill |
| Roo Code | 是（`~/.roo/skills/` 优先） | 官方未列 | name + description |
| Qwen Code | 是（默认读，无需配置；`~/.qwen/skills/` 优先） | 官方未列 | 概念上渐进披露，**注入点未逐字说明** |
| OpenHands | 是 | 官方未列 | name + description |
| Factory Droid | 是（另有 `~/.agent/skills/`） | 官方未列 | name + description（**启动时机未核实**） |
| WorkBuddy | **未核实** | **未核实** | 自家目录按社区与内置脚本口径是 `~/.workbuddy/skills/`（新版可能改 `.workbuddy-ai`）；官方文档没写 |
| DeepSeek Harness | 未核实 | 官方未列 | name + description（catalog 以 user 消息注入） |

**规范怎么说**：Agent Skills 规范（[agentskills.io/specification](https://agentskills.io/specification)）定义的是「一个 skill 目录里有什么」——`SKILL.md`、frontmatter 的 `name` / `description`、可选的 `scripts/` `references/` `assets/`。**它不规定 skill 装在哪**；`~/.agents/skills/` 是生态约定，不是规范要求。渐进披露是规范的正式条款：启动时约 100 token 的元数据，正文按需，资源再按需。

**发现索引的真相**：`/.well-known/agent-skills/index.json` 出自 Cloudflare 的一份 draft RFC，**不在规范里**；主流客户端**都不读**它，读它的是安装器类工具。这印证了本仓库删掉那两条索引的判断（[ADR 0093](../decisions/0093-no-skill-discovery-index.md)）。

## 三、对我们的直接含义

1. **第四步不能写一句「把规则写进系统提示词」就完事，也不要抄一张固定的「agent → 文件」表。** 这张表是第三方猜测：会过期、会按账号分目录、会有项目级覆盖文件，还会把一个错误地址塞进流程、盖过 agent 自己上下文里的信息（2026-10-27 的 WorkBuddy 现场）。正确做法是让运行在客户端里的 agent 用自己客户端的知识确定用户级文件；不确定时**先查自己的配置或上下文，再问「你用的是哪个 AI」并直接写用户级记忆**，不替他挑一个看起来像的，也不问路径。**本表的用途是供人核对与验收，不是给 agent 的指令。**
2. **用户级才是「这个 agent 以后都走 SkillMaster」的正确落点**；项目级只影响那一个仓库。旅程问的是「这个 agent」，所以默认写用户级。
3. **文件可能被更高优先级的文件盖住**（Zed 的 `.rules`、Claude Code 的 `CLAUDE.md`）。写完要**回读确认**：标记段在文件里、且只有一份；这一条与第五步验证的第 ① 项是同一件事。
4. **不要把希望寄托在 skill 自动加载上。** 本版不装 skill；等哪天重新考虑，这张表的第二列已经说明「装一个位置覆盖不了所有人」。

## 四、未核实清单

- Claude Code 是否**绝对**不读 `.agents/skills`：官方未列，社区实测不读——标「未核实」。
- Cursor、Copilot CLI 启动时是否严格只注入 name + description：官方未逐字说明。
- Gemini CLI 的 skill 发现深度（教程说根或一层，主文档未写）。
- Codex 的 32 KiB 默认上限：在线文档在本环境 403，经官方文档镜像与二进制常量核对，未读到在线原文。
- ~~WorkBuddy 用户级 `~/.workbuddy/*`~~：2026-10-27 源码 + 日志复核——用户级记忆是 `~/.workbuddy/user-<uid>-<scope>/MEMORY.md`（按账号分目录；`CODEBUDDY.md` 是项目级上下文）。WorkBuddy 是否读 skill 目录仍未核实。
- Qwen Code 用户级与项目级 `QWEN.md` 的优先级：官方未规定。
- Factory Droid 启动时注入 skill 元数据的时机：官方未说明。
- DeepSeek Harness 处于 `0.1.x-rc`，路径可能移动。

## 来源

- Agent Skills 规范：https://agentskills.io/specification · 客户端实现指南：https://agentskills.io/client-implementation/adding-skills-support · 描述即触发：https://agentskills.io/skill-creation/optimizing-descriptions
- Anthropic 工程博客：https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills
- Claude Code：https://code.claude.com/docs/en/memory · https://code.claude.com/docs/en/skills
- Codex：https://developers.openai.com/codex/guides/agents-md · https://developers.openai.com/codex/skills · 源码 https://github.com/openai/codex
- Cursor：https://cursor.com/docs/rules · https://cursor.com/docs/skills
- Gemini CLI：https://geminicli.com/docs/cli/gemini-md.md · https://geminicli.com/docs/cli/skills.md
- Copilot CLI：https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-custom-instructions · https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-skills
- opencode：https://opencode.ai/docs/rules/ · https://opencode.ai/docs/skills/
- Windsurf/Devin：https://docs.devin.ai/desktop/cascade/memories · https://docs.devin.ai/desktop/cascade/skills
- Amp：https://ampcode.com/docs/customize/agents-md · https://ampcode.com/docs/customize/skills
- Zed：https://zed.dev/docs/ai/instructions · https://zed.dev/docs/ai/skills
- Kiro：https://kiro.dev/docs/steering/ · https://kiro.dev/docs/skills/
- Cline：https://docs.cline.bot/customization/cline-rules · https://docs.cline.bot/customization/skills
- Roo Code：https://roocodeinc.github.io/Roo-Code/features/custom-instructions · https://roocodeinc.github.io/Roo-Code/features/skills
- Qwen Code：https://qwenlm.github.io/qwen-code-docs/en/users/features/memory/ · https://qwenlm.github.io/qwen-code-docs/en/users/features/skills/
- OpenHands：https://docs.openhands.dev/overview/skills
- Factory：https://docs.factory.com/harness/agents-md · https://docs.factory.com/harness/skills
- WorkBuddy：https://www.codebuddy.cn/docs/workbuddy/ · https://open.workbuddy.cn/docs/skill · 本机安装的 `WorkBuddy.app/Contents/Resources/app.asar`（`MemoryCollector` / `WorkbuddyPathService` / `ProjectContextSection`）与 `~/.workbuddy/logs`（2026-10-27 复核）
- DeepSeek Harness：https://github.com/deepseek-ai/deepseek-harness
- Cloudflare 发现索引 RFC：https://github.com/cloudflare/agent-skills-discovery-rfc
