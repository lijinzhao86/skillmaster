# skill 里的代码怎么被跑起来

> **核对日期**：2026-11-07。**用途**：回答 [`current/TODO.md`](../current/TODO.md) 里「让用户加载平台的 skill 后能运行 skill 里的代码」那一条。**它不下决定**：形态选择属于 `docs/decisions/`，某一版做到哪属于 `versions/`。
> **范围声明**：§三「本平台的事实」写的是 **v51 实现之前**的代码。v51 已经补上可执行位那条链（`version_file.mode`、分发 zip 设 mode、CLI 按 mode 落盘），那一节因此是历史快照，不要拿它判断「今天」。§一、§二、§四（行业与客户端事实）不受影响。
> **口径**：规范与官方文档逐条带链接并引原文；本平台的事实以代码为准，给出文件与行号；自测数字带样本与条件。**查不到官方依据的标「未核实」，不写成结论。**

## 三句话结论

1. **渐进披露管的是「进上下文的字节」，不是「落盘的字节」。** skill 包本来就整棵树落到用户磁盘（`skill get <地址> --all --into <目录>`，v44 起 `download` 退休、并进 `--all`）。代码失效的地方不在「文件互相引用没法一层层读」，而在三件别的事：**模型没有入口可调、环境没人保证、可执行位在入库时被丢掉**。
2. **规范里只有一个环境槽位，而且是散文。** `compatibility`（≤500 字）是唯一的「环境要求」字段，规范自己补了一句「Most skills do not need the compatibility field」；`scripts/` 只要求「self-contained or clearly document dependencies」。**没有任何客户端实现提供「执行」原语**——执行就是模型调自己的 bash 工具。
3. **（v51 之前）本平台把代码送到了用户磁盘，但那一份是「纯文本的代码」**：同步侧从 tar 里读到了可执行位（`GitHubTar.java:118`）、拿它做安全扫描（`SecurityScanner.java:60`），然后在写草稿 zip 时不设 mode（`SyncDraftWriter.java:240`）；`version_file` 没有 mode 列；CLI 落盘恒 `0o644`（`download.go` `writeEntry`）。上传路径连读都不读（`IngestedFile(relpath, bytes)`）。**但可执行位不是阻塞点**——Codex 的安装器同样把 `0755` 写成 `0644`，直接执行报 `exit 126`（[openai/codex#38740](https://github.com/openai/codex/issues/38740)），而 Claude Code 官方所有示例都用**显式解释器**调用（`python3 ${CLAUDE_SKILL_DIR}/scripts/visualize.py .`）。真正缺的是**入口清单**与**环境声明**；位只是「照原样交付」的保真问题。

## 一、先把「代码」拆成三件事

同一句「skill 里的代码」，在三种用途下要的东西完全不同。混着说，方案会打架。

| # | 是什么 | 例子 | 模型需要什么 | 渐进披露适用吗 |
|---|---|---|---|---|
| 1 | **说明性代码**：读来照做的参考实现 | 「正确的 retry 长这样」的片段、算法示例 | 把它读进上下文 | **适用**，它本来就是文档 |
| 2 | **工具性代码**：跑一次得出结果 | 抽 PDF 文本、校验 CSV、调用某个 CLI | **入口、参数与退出码**，不是源码 | **不适用**。读源码是浪费，且会诱发模型自己改写一份 |
| 3 | **作品性代码**：代码本身是交付物 | 素材库 + WebGL 场景、要本地打开的页面（taste-skill） | 整包落盘 + 一个打开方式 | 不适用，是分发形态问题 |

**用户说的「执行代码有很多相互的引用」，精确命中的是第 2 种。** 它有一个可判定的特征：**脚本之间有相对 import**。本仓同步目录里的真实样本（见 §四）正是这个形状——`check_response.py` 开头做了

```python
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parent))
from _common import (...)
```

**这个文件的正确性依赖「它的同级目录里有一个 `_common.py`」**。把它单独读进上下文、或者只取一个文件，都是错的；它的正确用法是**在它所在的位置执行它**。

由此得到本问题的第一性判断：**代码的单元不是文件，是「入口 + 它的依赖闭包 + 运行时」。** 渐进披露可以把「入口清单」分成三级给出，但它永远不该把「源码」当成第三级资源派发。

## 二、规范与客户端实际规定了什么

### 2.1 目录：`scripts/` 存在，语义只有两条

[Agent Skills 规范](https://agentskills.io/specification)把 skill 目录定义成 `SKILL.md` + 可选的 `scripts/` `references/` `assets/`。`scripts/` 原文：

> Contains executable code that agents can run. Scripts should:
> * Be self-contained or clearly document dependencies
> * Include helpful error messages
> * Handle edge cases gracefully

「self-contained **or** clearly document dependencies」——**规范把依赖问题交给作者自觉，自己没有声明机制**。

### 2.2 frontmatter：只有一个环境字段，而且要求作者少用

规范定义的六个字段：

| 字段 | 必填 | 约束 |
|---|---|---|
| `name` | 是 | ≤64，小写字母数字与连字符 |
| `description` | 是 | ≤1024 |
| `license` | 否 | 许可名或指向许可文件 |
| `compatibility` | 否 | **≤500 字，表示环境要求**（intended product、system packages、network access 等） |
| `metadata` | 否 | 任意字符串键值对，客户端可存放自己的扩展 |
| `allowed-tools` | 否 | 空格分隔的工具白名单，**实验性，各实现支持不一** |

`compatibility` 的三条官方示例里，两条是环境：

> `compatibility: Requires git, docker, jq, and access to the internet`
> `compatibility: Requires Python 3.14+ and uv`

而规范紧接着写：**「Most skills do not need the compatibility field.」** 它是**给人看、给模型读的散文**：没有 schema、没有版本范围语法、没有任何一方会去校验或安装它。

### 2.3 渐进披露：第三级把代码和文档混在一起

规范的三级、以及[客户端实现指南](https://agentskills.io/client-implementation/adding-skills-support)的同一张表：

| 层 | 加载什么 | 何时 |
|---|---|---|
| 1 目录 | name + description | 会话开始 |
| 2 指令 | `SKILL.md` 正文 | 判定相关时 |
| 3 资源 | **scripts、references、assets** | 指令引用到它们时 |

第三级把「要读的文档」和「要跑的脚本」放在同一格里。这个归并是代码失效的**规范层面**原因：它暗示脚本也是「按需读进来」的，而正确的动作是「按需执行」。

### 2.4 客户端：没有执行原语，只有通用工具

客户端实现指南里，与代码有关的只有三条：

- **激活时列出 `scripts/` 等文件，但明文写着不要预读**：「it can also enumerate supporting files (scripts, references, assets) in the skill directory — but it should **not eagerly read them**.」
- **把 skill 目录加进权限白名单**，否则每次读脚本都会弹一次确认。
- **`compatibility` 可以帮助模型判断怎么执行**：「`compatibility` notes environment requirements that could inform how the model executes the skill's instructions.」

整份指南**没有**「安装依赖」「检查运行时」「沙箱」「执行脚本」任何一环。执行 = 模型用自己本来就有的 bash 工具，把磁盘上那个路径跑起来。**这就是今天生态的真实状态：skill 提供的是「代码在磁盘上的位置」，不是「可执行能力」。**

### 2.5 客户端实测：落地、执行、声明面、沙箱

**落地是整目录。** Claude Code 的四个作用域（enterprise / personal `~/.claude/skills/<name>/` / project `./.claude/skills/<name>/` / plugin）都以目录为单位，个人与项目 skill 就地读，没有「只抽 `SKILL.md`」这回事，条目还可以是符号链接；claude.ai 账号的 skill 同步到 `~/.claude/skills/synced/`。Codex 落在 `$HOME/.agents/skills` 与 `$CODEX_HOME/skills`，内置技能的写入函数注释是「Preserves the embedded directory structure」。Gemini 激活时把**整个目录**加进 agent 的可读路径。

**可执行位：官方基本不讲，Codex 有实证丢失。** Claude Code / Cursor / Gemini 的文档都没有一句讲 chmod 或权限位；Codex 安装后文件是 `0644`（机制未核实——issue 只给了结果），[openai/codex#38740](https://github.com/openai/codex/issues/38740) 实测 `0755 → 0644`、直接执行 `exit 126`。**入口不能押注可执行位，要能用显式解释器调起来。**

**调用方式：模型自己写 shell 命令。** Claude Code 给的标准写法是

```yaml
allowed-tools: Bash(${CLAUDE_SKILL_DIR}/scripts/render.sh *)
```

> "If this skill is installed at `~/.claude/skills/render-chart/`, both occurrences of `${CLAUDE_SKILL_DIR}` expand to that directory. The `allowed-tools` rule then matches the exact command the skill body tells Claude to run, so the script runs without prompting."

`${CLAUDE_SKILL_DIR}` 让「正文里的命令」与「权限规则里的路径」对齐，**安装位置变了也不用改正文**。Claude Code 另有一条独立机制：正文里的 `` !`<command>` `` 会在内容送到模型**之前**执行、输出替换占位符——渲染期执行，不是工具调用，失败会中止整次调用。

**声明面：客户端私有字段远多于规范。** Claude Code 认 `when_to_use`、`argument-hint`、`disable-model-invocation`、`user-invocable`、`allowed-tools`、`disallowed-tools`、`model`、`effort`、`context`、`agent`、`background`、`hooks`、`paths`、`shell`，不认识的字段静默忽略；Codex 只读 `name` 与 `description`。**规范的六个字段里，没有一个能表达「入口 + 参数 + 依赖」。**

**沙箱默认值：没有一家默认安全。** Claude Code「The sandbox is off by default」，且只覆盖 Bash，Read/Write/hooks/MCP 都在外面，沙箱内「读」默认覆盖整台机器（含 `~/.ssh`、`~/.aws/credentials`）；Codex 默认断网 + 只写工作区；Cursor 终端默认禁网；Gemini 要显式开。

**官方仓库怎么处理依赖：两种思路，都不是「零预装」。** `anthropics/skills` 的 docx / pptx / xlsx 写的是「`docx` is preinstalled — do not run `npm install` first… Only if that `require` fails: `npm install docx`」——**赌环境里已经装好**；`openai/skills` 的 pdf / transcribe 写「Dependencies (install if missing)… Prefer `uv`」，装不了就「tell the user which dependency is missing」。两个仓库全仓 grep `# /// script`、`uv run`、`uvx` 均零命中——**PEP 723 这类零预装机制是规范文档的推荐，不是官方技能的做法**。

### 2.6 信任：行业已经收敛到「安装 ≠ 执行」

- **默认「安装就执行」的只剩少数。** npm v12（2026-07 GA）把 `allowScripts` 默认关掉，pnpm 10 更早；PyPI 的 wheel 是解包、不执行安装代码（sdist 才跑 build backend）；Docker 的 pull 与 run 是两件事；VS Code 安装是解包、执行发生在激活时。仍在「安装即执行」的是 apt/dpkg（maintainer scripts，root）、Homebrew formula（2026-06 起**第三方 tap 要显式 `brew trust`**）、GitHub Actions（没有独立安装阶段，引用即执行）。
- **背书不等于审计。** Claude Code 官方写明它对目录里的 connector「does not security-audit or manage any MCP server」；MCP 规范自己写「Tools represent arbitrary code execution」，且「MCP itself cannot enforce these security principles at the protocol level」。
- **与我们直接相关的一条**：Claude Code 的 `allowed-tools` 由**技能自己声明**，而「Workspace trust doesn't gate this field… A skill can grant itself broad tool access」。即一个 skill 可以自带 `allowed-tools: Bash(*)`，在不受工作区信任约束的情况下免确认执行。我们的同步闸里已有一条 `broad_permissions` 警告规则专盯 `allowed-tools: *`（`sync/security-rules.yml`）——在「代码能跑」之后，它会从提示升级为高危信号。

## 三、本平台今天的事实（可复核）

### 3.1 整棵树已经在用户磁盘上

`skill get <地址> --all --into <目录>` 把整个版本解到本地（v44 把 `download` 并进 `--all`，旧名现在是退休名）；v38 的安装流程（[ADR 0129](../decisions/0129-onboarding-delivers-a-skill-not-a-memory-line.md)）更是直接交付一个技能包到 agent 的技能目录。**「不落盘」已经不是今天的现状**（[ADR 0001](../decisions/0001-server-authoritative.md) 的前提，在 onboarding 与整版取回这两条路上已经分叉）。

### 3.2 可执行位：读到了、用过了、然后丢了

| 环节 | 代码 | 可执行位在哪 |
|---|---|---|
| 同步读 GitHub tar | `GitHubTar.java:118` `(entry.getMode() & 0111) != 0` | **在这里**，`Entry(path, content, executable)` |
| 安全扫描 | `SecurityScanner.java:60-61` | 用规则判「可执行文本」「可执行二进制」 |
| 写草稿 zip | `SyncDraftWriter.java`（v51 前）`new ZipEntry(root + "/" + relpath)` | **丢了**——不设 unix mode（v51 后这一行已改成 `setUnixMode`，行号不再有意义） |
| 入库 | `IngestedFile(relpath, bytes)`；`version_file(version_id, relpath, blob_sha256, size, is_binary)` | **没有这一列** |
| 分发 zip | `SkillDistributionService.java`（v51 前）`new ZipEntry(entry.relpath())` | 不设 mode（v51 后已换成 `ZipArchiveOutputStream`） |
| CLI 落盘 | `download.go` `writeEntry`：`os.OpenFile(dest, …, 0o644)` | 恒 `0o644` |

结论：**平台唯一知道「这个文件是可执行的」的时刻，是同步的安全扫描那一瞬间。** 过了那一刻，它和一篇 markdown 没有区别。

**这是一个保真问题，不是阻塞点**：客户端自己也不保证位（§2.5），生态的标准写法是显式解释器。修它的价值在「原样交付」，不在「让它能跑」。

### 3.3 上传路径连读都不读

人工上传走 `IngestedFile(relpath, bytes)`，zip 条目的 external attributes 不被解析。所以同一个 skill，从 GitHub 同步进来和用户手工打成 zip 上传，**可执行位一样丢**——只是前者「曾经知道过」。

### 3.4 frontmatter 原样存，`compatibility` 已经进 invoke 头

`SkillDocument.parse` 把 frontmatter 全量存下（含未知字段）；`SkillMetaResponse.java:119` 单独取 `compatibility`；`invoke` 把它当平台事实印在正文前的头部块里（`invoke.go` `printLoadedHeader`）。**槽位已经通了，今天只是没人消费它。**

### 3.5 `invoke` 只给正文，不给资源清单

`invoke` 的模型是「正文就是索引」——`skill`、`references/` 里有什么由作者在正文里写清楚；平台不派发文件清单。这符合文档型 skill 的用法，但**入口清单因此完全依赖作者散文**，机器无法校验、模型无法直接调。

### 3.6 扫描已有，但只在同步一侧

`sync/security-rules.yml`（[ADR 0155](../decisions/0155-open-source-sync-passes-three-gates.md)）已经分好两级：

- **阻断**：硬编码凭据/私钥、破坏性命令（`rm -rf /`、`mkfs`、`dd of=/dev/`）、下载即执行（`curl | sh`）、**可执行二进制**。
- **警告**：外部链接、宽泛权限（`allowed-tools: *`）、二进制文件、**可执行文本脚本**。

人工上传与手工发布闸**没有接这套规则**（[`TODO.md`](../current/TODO.md) 里「上传与同步的安全扫描口径不一致」那一条记的是同一件事的许可部分）。

### 3.7 ADR 0008 的两条理由，一条已被推翻

[ADR 0008](../decisions/0008-no-script-execution.md) 在 2026-09-26 决定「P0 不做脚本执行」，理由是「服务端沙箱太重」与「客户端执行破坏『不落盘』」。**第二条已经不成立**（§3.1）。它留下的两件前置工作——「上传时的脚本扫描策略」与「沙箱方案」——前者已经做了一半（§3.6），后者是一条仍然开放的产品岔路。

## 四、目录里的真实样本

**口径**：取 `deploy/sync-sources.yml` 里去重、按字典序排序后的前 12 个仓库，用 GitHub tree API 拉 HEAD 全树；「自带 scripts/」判据是「某个含 `SKILL.md` 的目录下存在 `scripts/` 子目录」。样本是字典序前 12 个，**不是随机抽样**，不能外推到全部白名单仓库（规模以 `deploy/sync-sources.yml` 为准）。

| 指标 | 数 |
|---|---|
| 仓库 | 12 |
| skill（`SKILL.md` 个数） | 454 |
| 自带 `scripts/` 的 skill | 162（**35.7%**） |

依赖声明的口径更窄——取其中 5 个仓库、160 个带脚本的 skill，判据是「skill 目录下存在 `requirements.txt` / `pyproject.toml` / `package.json` / `Pipfile` / `environment.yml`」：

| 仓库 | 带脚本的 skill | 带依赖清单 |
|---|---:|---:|
| `JimLiu/baoyu-skills` | 16 | 10 |
| `K-Dense-AI/scientific-agent-skills` | 121 | 2 |
| `amElnagdy/delegate-skills` | 20 | 0 |
| `addyosmani/agent-skills` | 2 | 0 |
| `Leonxlnx/taste-skill` | 1 | 0 |

**160 个里 12 个（7.5%）声明了依赖。** 反过来读：绝大多数脚本要么只用标准库，要么在赌用户机器上恰好装了什么——后者正是「跑不起来时无法归因」的来源。

一个具体形状（`K-Dense-AI/scientific-agent-skills` 的 `analytical-method-validation`）：

- `scripts/` 下 8 个文件，`_common.py` 与 `_catalog.py` 被其余脚本 import；
- 每个脚本有 shebang、模块说明、CLI 用法、**以及退出码约定**（`0` 无发现 / `1` 有发现 / `2` 输入有误）——它本来就是按「工具」设计的。

## 五、四种形态（不下决定，给共创用）

**所有形态共享一条前提：安装与执行分开。** 这不是我们发明的要求——npm v12 / pnpm 10 / PyPI wheel / Docker pull / VS Code 安装都是这个方向（§2.6）。「把包放到磁盘上」永远不等于「跑它」，两者之间必须有一个明确的、用户看得见的动作。

| 形态 | 谁执行 | 解决什么 | 主要代价 |
|---|---|---|---|
| **A 只做约定** | agent 的 bash | 平台保留可执行位、在 invoke 头里给出入口清单与环境要求；执行交给客户端 | 环境仍然没人保证；模型仍可能读源码而不是调入口 |
| **B CLI 当运行时入口** | `skmr run <地址> <入口>` 起子进程 | 一处解决「环境 + 入口 + 版本钉住」，且可在里面加确认与日志 | CLI 要变成小的包管理器（准备 venv / node_modules）；离线与代理要定 |
| **C 平台沙箱执行** | 服务端 | 用户机器零风险，依赖由平台保证 | 与「服务端只存字节」的定位分叉；要建隔离与资源限制；本地文件不可用 |
| **D 包成 MCP server** | 客户端进程 | 复用成熟的分发与调用协议，工具有 schema | 交付形态变了：不再是 skill 包；安装配置成本转移给用户 |

四种可以叠加：A 是任何一条路的地基（位与环境声明必须先存在），B 与 C 是「谁来跑」的分叉，D 是「不跑，改成调用服务」。

## 六、未核实

- **Claude Code / Cursor / Gemini 在安装或读取时是否保留可执行位**：三家文档都没有一句讲权限位；只有 Codex 有实测（不保留）。所以「位会不会丢」在客户端侧只能按「可能丢」设计。
- **Cursor 与 Gemini 是否逐字节保留整包**：两家都只说明「发现目录」，没有说从 Git 安装之后 `scripts/`、`assets/` 是否完整落盘。
- **Gemini CLI 的 frontmatter schema**：文档只提 `name` 与 `description`，是否支持 `allowed-tools` / `compatibility` / `metadata` 未写明。
- **Anthropic 企业文档里「第三方 skill 审查清单」的原文**：本次因区域重定向未读到正文，只拿到搜索摘要。
- `max-repo-bytes`（64 MiB）与 `max-unpacked-bytes`（256 MiB）对媒体型 skill 的实际约束，是 [`TODO.md`](../current/TODO.md) 里「taste-skill 这类媒体型仓库」那一条的事，不在本文范围。

## 七、来源

- Agent Skills 规范：https://agentskills.io/specification
- 客户端实现指南：https://agentskills.io/client-implementation/adding-skills-support
- 脚本用法（规范侧）：https://agentskills.io/skill-creation/using-scripts
- Claude Code：https://code.claude.com/docs/en/skills · https://code.claude.com/docs/en/sandboxing · https://code.claude.com/docs/en/security
- Codex：https://learn.chatgpt.com/docs/build-skills · https://learn.chatgpt.com/codex/agent-approvals-security · https://github.com/openai/codex/issues/38740
- Cursor：https://cursor.com/docs/skills · https://cursor.com/docs/agent/security/run-modes
- Gemini CLI：https://geminicli.com/docs/cli/skills · https://geminicli.com/docs/cli/sandbox
- 官方仓库：https://github.com/anthropics/skills（docx / pptx / xlsx / pdf 的 `SKILL.md`）· https://github.com/openai/skills（pdf / transcribe）
- 本平台代码：`skillmaster-server/src/main/java/com/skillmasterai/modules/skill/internal/GitHubTar.java` · `…/usecase/sync/SecurityScanner.java` · `…/usecase/sync/SyncDraftWriter.java` · `…/modules/ingest/IngestedFile.java` · `…/api/SkillMetaResponse.java` · `skillmaster-cli/cmd/skillmaster/download.go` · `skillmaster-cli/cmd/skillmaster/invoke.go`
- 决策：[ADR 0008](../decisions/0008-no-script-execution.md) · [ADR 0129](../decisions/0129-onboarding-delivers-a-skill-not-a-memory-line.md) · [ADR 0155](../decisions/0155-open-source-sync-passes-three-gates.md)
