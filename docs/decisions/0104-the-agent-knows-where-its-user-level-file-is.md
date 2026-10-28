# ADR 0104 · 用户级说明文件在哪，由运行它的 agent 决定

- **状态**：部分被取代（`--file` / `--agent` 与其路径检查由 [0111](0111-startup-takes-no-flags.md) 取代；「位置由 agent 自己确定」继续有效）
- **日期**：2026-10-27
- **依赖**：[`versions/v26-startup-guidance-and-agent-paths/`](../versions/v26-startup-guidance-and-agent-paths/)

## 背景

v20–v25 的旅程 `skillmaster/startup` 里带着一张「agent → 用户级说明文件」的对照表：Claude Code 写 `~/.claude/CLAUDE.md`，Codex 写 `~/.codex/AGENTS.md`，WorkBuddy 写 `~/.codebuddy/CODEBUDDY.md`。CLI 也按「已知路径」判断一个 `--file` 像不像某个 agent 的地址。

这张表已经错过一次：WorkBuddy 真正加载的是 `~/.workbuddy/user-<uid>-<scope>/MEMORY.md`，`~/.codebuddy/CODEBUDDY.md` 写了也不读。而且表是**内容**，会被抄进旅程、随 agent 版本变化而过期；一个 agent 越新，它自己上下文里关于「我读哪个文件」的信息越比表准。

当时的选项：

1. 维持这张表，人工更新；
2. 把表搬进 CLI，由 CLI 判断；
3. 不做表，让每个 agent 自己确定位置；
4. 只支持表里列出的 agent，其它拒绝。

## 决定

**不做表，CLI 与旅程里都不放「agent → 文件」的对照。** 写进哪个文件由运行在客户端里的 agent 自己确定：

- 它写的是**这个 agent 的用户级说明文件**——整台机器、所有项目都会读到的那一份；
- 位置从它自己的配置或上下文里确定；**仍不确定就问「你用的是哪个 AI」，再直接写用户级记忆，不问路径**；
- 有更高优先的覆盖文件时，写**实际会被读**的那一份；
- 只能在应用内设置、没有文件的 agent，由 `prompt print` 打印正文、请人粘贴，再用 `prompt status` 核对。

CLI 只接受一个显式的 `--file`：做通用检查（不是目录、父目录存在、在主目录下，项目级路径警告），记录、更新、撤销。`--agent` 是记录与展示用的标签，写入与 `status` **不校验**；`startup --plan` 在没有 `--file` 时会用它筛记录，但仍不校验名字。

## 理由

- **表会过期，agent 不会。** 表是某一时刻的截图；agent 自己的配置才是它现在真的读什么。把判断交给离事实最近的一方，是唯一不需要持续维护的做法。
- **错误的表比没有表更糟。** 表把错误地址塞进流程，还盖过 agent 自己知道的信息——WorkBuddy 那次就是这样：agent 可能知道 `MEMORY.md`，但按表写了 `CODEBUDDY.md`。
- **CLI 判不了。** 把表搬进 CLI 只是把同一张会过期的表换了个位置，还多了一条「CLI 版本要跟着 agent 更新」的耦合。
- **被否的第 4 条（只支持表里的 agent）**：等于用一张会过期的表决定「谁能用」，新 agent 一出现就被挡住，而挡住的原因和用户要解决的问题无关。

## 后果

- **CLI 不再有「已知路径」这个判据**，也就拦不住一个「看起来像、其实不对」的路径。代价由两件事兜：写前五句话请示（人知道要动哪个 AI 的哪个设置），写后如实告知（规则是否真的被新任务加载，由以后的新任务回答）。
- **旅程必须要求 agent 先确定位置；仍不确定就问「你用的是哪个 AI」并直接写用户级记忆**，这成了对 agent 自身能力的一条要求；只能在应用内设置的 agent 走「打印正文、人工粘贴」那条路。
- **WorkBuddy 的真机验收不能靠推断**：必须人在 WorkBuddy 里走一次「写进 `~/.workbuddy/user-<uid>-<scope>/MEMORY.md`，再开新任务，规则生效」。
- **旧的错误地址要留一条迁移说明**：按旧旅程写过的 `~/.codebuddy/CODEBUDDY.md` 不会被读，可用 `prompt uninstall --file` 去掉标记段（CLI 不删文件，[ADR 0101](0101-the-cli-never-deletes-files.md)）。
