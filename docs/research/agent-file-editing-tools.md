# agent 的「本地写文件」工具是怎么建的

> **核对日期**：2026-10-30。
> **用途**：决定 skmr 的「改 skill」这条路怎么改——agent 为什么绕过在线写、行号为什么会写进内容、`skill edit` 为什么感觉不如本地 Edit。
> **口径**：Claude Code 的一手依据是本会话里的工具说明；Anthropic 文本编辑器工具的原文档在本次核对时被地区重定向挡住（**未直接读到原文**），标「转引」的部分来自 SDK 实现、迁移说明与文档检索结果；Codex 的匹配行为来自其源码与第三方逐行分析，同样标「转引」。**查不到的就写「未核实」，不当结论用。**

## 三句话结论

1. **位置一律用内容锚点，不用行号。** 四家产品里只有 Anthropic 的 `insert` 收行号，而它表达的是「插在哪」；所有「改哪一段」都靠精确子串或上下文行。行号只活在「读」的渲染里，读的渲染从不被回写。
2. **写失败必须是拒绝，不是猜测。** 0 匹配、多匹配、上下文找不到，一律整次不生效，并把「下一步怎么办」写进错误里。
3. **「用什么形态收改动」本身是可靠性变量。** aider 把「格式合规率」与「答对率」分开统计，同一模型两项能差 30 个百分点——所以这不是语法糖，是要单独设计的部分。

## 一、Claude Code 自己的读写契约（一手：本会话的工具说明）

- **读**：`Read` 给每行加 `N\t` 前缀；`Edit` 的说明里明确要求「匹配前把 Read 的行号前缀去掉」。**这是指令层兜底，不是机制层。**
- **写**：`Write` 覆盖一个已存在的文件，要求本会话先读过它；`Edit` 要求 `old_string` 逐字匹配且**唯一**，否则整次失败。
- **失败关闭**：同一套约定也用在发布类工具上——`edits` 里只要一条对不上，整批不生效，页面保持原样。
- **要点**：Claude Code 能安全，靠的不是「模型不会抄行号」，而是**读的内容和写的内容走两条通道**，并且写失败时什么都不发生。抄行号的失败模式仍然存在（说明里专门写了一句防它），只是它落在「Edit 失败」而不是「内容被污染」。

## 二、Anthropic 的文本编辑器工具

- 工具名是 `str_replace_based_edit_tool`，命令为 `view` / `create` / `str_replace` / `insert`（`text_editor_20250429` 起**移除 `undo_edit`**，`20250728` 另加 `max_characters` 用于截断大文件视图）。转引，未直接读到原文。
- `str_replace` 以 `old_str` 精确替换，**要求它在文件里恰好出现一次**；`view` 是带行号的渲染。转引。
- **`undo_edit` 被移除的理由**：编辑历史留在调用方，就是同一份内容的第二个真相源，迟早漂。这条对我们的意义是：**不要在客户端维护「上一版内容」的副本**，基线必须是服务端的 digest。

## 三、OpenAI Codex 的 `apply_patch`

- 格式是 V4A：`*** Begin Patch` … `*** End Patch`，文件操作用 `*** Add File:` / `*** Delete File:` / `*** Update File:`，改名用 `*** Move to:`，hunk 用 `@@` 开头，行前缀 `+` / `-` / 空格。
- **不用行号定位**：每个改动给 3 行上下文，不够唯一时用 `@@ class Foo` 这类**结构锚**继续收窄；`*** End of File` 把 hunk 钉在文件尾。
- **匹配按严格度递减**（转引：源码与第三方逐行分析）：精确 → 忽略行尾空白 → 忽略首尾空白 → 做 Unicode 破折号/引号/空格归一。前向游标按顺序应用 hunk。
- **错误教下一步**：上下文找不到时报 `Invalid Context`，提示用**更多上下文或更小的 hunk** 重试——错误信息本身是下一次调用的指导。
- **一个工具做完全部文件操作**（增删改移），不是每个端点一个工具。

## 四、aider 的编辑格式

- 支持整文件（`whole`）、search/replace 块（`diff`）、简化 unified diff（`udiff`）。`udiff` 是**为 GPT-4 Turbo 专门选的**：别的格式下它会「偷懒」，只写「… 原代码 …」这类注释。
- 排行榜把 **「Correct edit format」（格式合规率）与「Percent correct」（答对率）单列**。实例：某模型答对 45.3%、合规 64.4%；另一档 88.0% / 91.6%；也有模型两栏都是 100%。
- **推论**：格式合规是独立的失败面。一个格式再「优雅」，如果模型写不对，它的收益为负。

## 五、写之后返回什么（2026-10-30 本会话实测 + 源码）

| 工具 | 成功时返回 | 失败时返回 |
|---|---|---|
| Claude Code `Write` | `File created successfully at: <path> (file state is current in your context — no need to Read it back)` | 文档说覆盖未读文件会失败；**本会话实测没拦住**（用 shell 建的文件直接覆盖成功）。这是观察，不是合同 |
| Claude Code `Edit` | `The file <path> has been updated successfully. (file state is current in your context — no need to Read it back)`——**没有 diff、没有片段**（本会话；有些构建会附 `cat -n` 片段） | 0 处：`String to replace not found in file.` + 原样回显你给的串。多处：`Found 6 matches of the string to replace, but replace_all is false…` + 「补上下文」与「设 replace_all」两条出路。**都不给行号、不给候选** |
| Codex `apply_patch` | `Success. Updated the following files:`，随后每行一个 `A` / `M` / `D` + 路径；hunk 为空时 `No files were modified.` | `apply_patch verification failed: Invalid Context` / `Failed to find context '…' in <file>`；格式错是 `invalid patch: …` / `invalid hunk at line N, …` |

三条观察：

1. **两家的成功返回都不含 diff。** 它们敢这样，是因为调用方自己能在本地跑 `git diff` 或再读一次，而且宿主替它盯着文件——本会话里文件被外部改动时，宿主直接把 diff 注进了对话。CLI 没有宿主，`read` 又是带行号的渲染，所以「回显 hunk」不是照抄，是补上它们免费得到的那个机制。
2. **Claude Code 的返回值在替调用方关掉一个问题**：「file state is current in your context — no need to Read it back」。`skill write` 今天只说「已修改（digest）」，agent 无法判断自己记忆里的原文还成不成立。
3. **两家的失败都不给位置**：Claude Code 只回显你给的那个串，Codex 只说 `Invalid Context`。所以「指出最接近的行、认出这是 `read` 的行号」是我们能做得更好的地方，不是照搬。

## 六、六条共同原则

1. **读的渲染 ≠ 可写内容。** 读可以有行号、页脚、截断提示；写只接受原始字节或调用方新写的文本。
2. **位置用内容锚点。** 精确子串（Claude Code / Anthropic）或上下文行 + 结构锚（Codex）。
3. **精确匹配 + 唯一性 + 失败关闭。** 拿不准就整次不生效，不留半成品。
4. **写前带基线。** ETag / digest / `If-Match`，读过之后变过就拒绝，不静默覆盖。
5. **写完回显 diff。** 调用方要能自己看出「改的是不是我想改的那处」。
6. **错误要教下一步。** 最接近的候选、差在哪、用更多上下文或先重读——而不是一句「没找到」。

## 七、对照 skmr 今天的写路径

| 原则 | skmr 今天 | 差距 |
|---|---|---|
| 读 ≠ 写 | `read` 带 `N\t` 与页脚；`cat` 是原始字节 | 只在帮助里写了「不能直接写回」；`write` 把 stdin 原样上传，**没有任何拦截** |
| 内容锚点 | `skill edit --old` 精确子串 | 有；但 `--old` / `--new` 走 shell 参数，多行与反引号要自己转义 |
| 精确 + 唯一 + 关闭 | `replaceExactly`：0 处拒、多处不给 `--replace-all` 拒 | 有，与本地 Edit 同构 |
| 写前基线 | `If-Match: <digest>`，变了回 409 | 有（版本级） |
| 回显 diff | 只报「已修改 X（digest）」 | **缺**，调用方无法自查 |
| 错误教下一步 | 「这一版里没有找到要替换的文本」 | **缺**：不区分行号混入 / 空白差异 / 内容漂移，不给候选 |
| 多文件一次 | 一文件一条命令 | **缺**；一次交整包只有本地 `submit` |
| 多轮改同一草稿 | `submit` 永远按内容建版本；`download` 是一次性快照（ADR 0068） | **缺**：本地往返落不回同一个草稿 |

## 八、三个现象的根因

1. **agent 倾向本地写好后导入**：不是习惯，是这条路**唯一支持多文件 + 多轮**。在线路径一文件一命令，且每轮 `submit` 都换草稿身份；本地则一次交整包、可用 `Read`/`Write`/`Edit`/`git diff`。两条修法：把本地扶正，或把在线路的反馈补上。**v32 选了后者**——本地往返落不回同一份草稿，而在线缺的是反馈（[ADR 0116](../decisions/0116-editing-happens-online.md)）。所以要做的是让在线编辑自带验证信号，不是再劝 agent 用单文件命令。
2. **行号被写进内容**：`read` 是唯一带上下文的阅读入口，而 `write` 是全量覆盖——它没有「匹配不上」的机会。防线必须加在写入口（拒绝或自动剥掉 `N\t`），不能只靠文档里的一句话。
3. **`edit` 不像「本地 patch」**：机制上与本地 Edit 同构（精确、唯一、失败关闭、`If-Match`），差的是**传输**（shell 引号）、**回显**（没有 diff）、**纠错**（错误不教下一步）。「patch」那部分本地其实是 `git diff` 给的，CLI 并没有对应物。

## 九、留给版本设计的问题

以下每条都能回答「做 / 不做」与「怎么量」，进下一版的 technical-design：

1. 本地目录要不要成为一等公民——`checkout` 取到目录、`commit` 把差异写回**同一个草稿**？
2. `write` 的防线做到哪一档：拒绝「整份都像 read 输出」的内容，还是提供 `--strip-line-numbers` 自动修正？
3. `edit` 要不要接受文件形态（`--old-file` / `--new-file`，或直接吃 `git diff`）？
4. 成功时回显 unified diff，走 stdout 还是 stderr？（v7 的管道契约只约束内容命令，`edit` 不在其中）
5. 多文件改动要不要一个命令做完（批量 `If-Match`，失败逐文件报告）？

## 出处

- Claude Code 的 `Read` / `Write` / `Edit` / Artifact `edits` 契约：本会话的工具说明（一手）。
- Anthropic 文本编辑器工具：[Text editor tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/text-editor-tool)（本次核对被地区重定向，**转引**；`str_replace_based_edit_tool` / 移除 `undo_edit` 一节见 [迁移说明](https://docs.claude.com/en/docs/about-claude/models/migrating-to-claude-4)、[SDK 实现](https://github.com/strands-agents/sdk-typescript/issues/152)）。
- OpenAI Codex `apply_patch`：[codex 源码里的工具说明](https://github.com/openai/codex/blob/35aaa5d9/codex-rs/tools/src/apply_patch_tool.rs)、[GPT-OSS 的 apply_patch 说明](https://raw.githubusercontent.com/openai/gpt-oss/main/gpt_oss/tools/apply_patch.md)；匹配级联为**转引**。
- aider 编辑格式与排行榜：[edit formats](https://aider.chat/docs/more/edit-formats.html)、[leaderboards](https://aider.chat/docs/leaderboards/)。
- Anthropic 工具设计经验：[Writing effective tools for agents](https://www.anthropic.com/engineering/writing-tools-for-agents)（工具按工作流合并、错误要具体可执行、输出要省 token）。
