# v13-cli-grammar · 产品需求

> **最后更新**：2026-10-22
> **所属版本**：[`README.md`](README.md)
> **开工基线**：`main@d4cc703`（本版描述“现在”时，均指这个提交）

<!--
这是本版需求的权威文档。README 只放摘要，别处不要重复这里的内容。
-->

## 用户与场景

**谁在用**：

- **agent（经网关 skill）**：在会话开始或任务需要时列出可用的 skill，然后加载正文、列文件、读文件。它读的是网关正文，不是完整帮助；命令名必须一眼能判断“这是对一个 skill，还是对多个”。
- **读 skill 的人**：同一个入口，可能只读某个 skill，也可能先浏览目录。
- **作者**：提交草稿之后，要看版本列表、比较两版、改草稿名称/说明、丢弃草稿、打开网页去上线。
- **维护者与脚本**：看帮助、写 CI，在上线前按正式命令面一次接好，不把临时旧命令带进契约。

**在什么场景下用**：

- agent 拿到一个模糊的任务，先 `skills list` 看有什么，再 `skill invoke` 加载，`skill files` 看文件，`skill read` 读一个文件。
- 作者提交一版之后，`version list` 看草稿，`version diff` 看改了什么，`version discard` 丢掉，`version open` 打开网页让人上线。
- 一个人第一次跑 `skillmaster help`，想知道“一个 skill 的事”和“多个 skill 的事”分别在哪。

**本版开工前（`main@d4cc703`）的替代做法是什么**：

- `skill list` 列**多个** skill，`skill ls` / `files` 列**一个版本里的文件**，只差一个字母，两个都合法；帮助里还把 `ls` 当主命令、说 `files` 是别名。2026-10-22 产品负责人把 `skill list` 读成“列一个 skill 里的文件”——这不是记错，是命名在误导。
- `version` 是顶层组，`skill versions` 是消费面列表；两个组的 `help` 没有把分工说清。顶层 `skillmaster help` 已有一句解释，但本版要把它落到 `skill help` / `version help`。
- 帮助的两份文本自己不一致：`skill` 组帮助把 `ls` 当主命令，顶层帮助写的是 `files`；代码里的主命令其实是 `files`。

## 范围

本版**只整理 CLI 的命令面与共同行为**，不改变任何产品或服务端能力。既有命令中已经有偏差的地方，本版不顺手修；它们集中列在 §CLI 命令行为规范 §10「既有例外」，并由验收 #12 保护。这样“命令面收口”和“既有行为不变”才不会互相打架。

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | **`skills` 组**：`list`、`search`；不带一个 skill 的地址 | CLI |
| 2 | **`skill` 组只留一个 skill 的动作**：`invoke`、`files`、`read`、`cat`、`wc`、`grep`、`write`、`edit`、`rm`、`mv`、`cp`、`download`、`versions`、`submit`、`share`、`shares`、`unshare`、`request`、`create`、`open` | CLI |
| 3 | **`version` 留在顶层**，形状照 `gh release`：正式动作是 `list`、`show`、`diff`、`rename`、`note`、`discard`、`open`；七个页面动作作为拒绝型动词留在 `version` 组，不进帮助 | CLI |
| 4 | **文件枚举的正名是 `skill files`**；`skill ls` 不进入正式命令面。`skill files` 的输出是**每个文件一行可复制的 relpath**，正文/二进制提示缩进，文件清单之后给出 `read` 命令、版本说明行在 stdout 最后；每个文件条目不打印 per-file URI / 摘要，结尾 `read` 命令与版本说明行保留调用者地址与 pin（可能含 `sha256:`） | CLI + 帮助 |
| 5 | **不兼容未上线旧命令**：`skill list` / `skill search` / `skill ls` 都按未知命令处理；帮助与文档只教新名字 | CLI |
| 6 | **帮助**：顶层 8 个命令；`skill help` 与 `skills help` 分开；`skill versions` 与 `version list` 各写一句“这是谁的问题”，并互相指 | CLI + 文档 |
| 7 | **`version list` 不把游标交给调用者**：用 `--all` 一次翻到底，保留 `--limit`；`--cursor` 删除；`version` 不新增 `create` | CLI |
| 8 | **网关 skill 跟着改**：第 1 步用 `skills list`，检索用 `skills search`；第 1 步的说明改成“通常两行；描述为空时只有地址一行，取地址只依赖第一行”；`metadata.platform_api_version` 从 8 加到 9 | `gateway/skillmaster/SKILL.md` |
| 9 | **记录决策**：ADR 0077；CLI README 更新；QA 探针里的旧命令随实现更新；历史版本文档不改写 | 文档 + QA 探针 |
| 10 | **把共同行为写成契约**：适用对象、分组与命名、地址、输出流与退出码、分页、确认与页面动作、凭据与浏览器、帮助、破坏性变更、新增命令检查清单、既有例外 | `prd.md` §CLI 命令行为规范 |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 不把 `version` 收进 `skill` | 先把 `skill versions`（消费面）与 `version list`（作者面）合成一个按权限给结果的命令，才谈嵌套；直接塞进去会造出 `skill versions` vs `skill version list` 这种更糟的混淆 | 版本列表统一之后，作为另一条改动 |
| 2 | 不做顶层 `versions` 组（`versions list` + `version show`） | `gh release list`、`docker image ls`、`npm dist-tag ls` 都是单数名词组 + `list` 动词；`version` 没有 `version ls` 这种碰撞 | 如果以后发现 `version list` 真的被读成“一个版本”，再评估 |
| 3 | 不做 `version create` | 新建版本是 `skill submit`（落草稿），上线是浏览器里由人确认；`gh release create` 那一步在我们这里被拆成了两个动作 | 不做 |
| 4 | 不在 CLI 执行 `publish` / `rollback` / `revoke` / `restore` / `offline` / `online` / `delete` | 这些动作改变调用者能读到什么，按 ADR 0031 / 0046 / 0061 只在浏览器面存在 | 不做 |
| 5 | 不改版本的语义、权限、状态机 | 这一版只管命令面；版本机制是 v5 / v6 的契约 | 另一条改动 |
| 6 | 不改写历史版本文档里的旧命令 | `docs/versions/v*` 是记录；它们写的是当时的 CLI | 不做 |
| 7 | 不做任何界面 / 设计稿改动 | 这是 CLI 与帮助文本的整理，不涉及控制台 | 不做 |
| 8 | 不给 `skills search` 加 `--all` / `--json`，不改 `skill shares` 的 `--page`，不改 `version diff` 的 `next` | 检索的“一页 + 收窄关键词”、分享的页码分页、diff 的文件续取都是既有设计；本版只收命令面，不扩分页能力 | 标签版或检索/分享改版时 |
| 9 | 不给既有 `skill rm` / `write` / `unshare` 补 `--yes` | 本版不改变既有写入与分享命令的行为；它们的破坏性边界在各自的 TD 里 | 另一条改动 |

## CLI 命令行为规范

这一节是**所有新增命令**必须遵守的契约，也是本版**新增/改动命令**的验收依据。它不替代每个命令自己的用法，而是回答“所有命令共同长什么样”。偏离它要一条 ADR，不在实现里临时决定——否则每加一个命令，就多一套输出、错误和分页的方言。

### 0. 适用对象与既有例外

**本版 in-scope 命令**（验收 #13–#17 只对这批命令做契约矩阵）：

- 新增/改动的命令：`skills list`、`skills search`、`skill files`（输出格式）、`version list`（`--all` / 游标收口）、顶层 `help`、`skill help`、`skills help`、`version help`；
- 负向用例：`skill list`、`skill search`、`skill ls`（都不可用）；
- 作为回归的既有命令：`version discard`（`--yes` 行为）、七个页面动作（交接行为）、`skill cat` / `read` / `wc` / `grep` / `download` / `rm`（拒绝 `--json`、退出码、批量部分成功）、`skill versions`（一次给全）。

**本版 in-scope 的 `--json` 支持面**：`version list`、`version discard`、七个页面动作（只拒绝，没有成功 JSON；拒绝时按 `--json` 形状输出错误对象）；`skills list` / `skills search` / `skill versions` 与内容命令不提供（传入按未知参数拒绝）；`help` / 负向旧命令不适用。

**既有例外与本版对齐**（详见 §10；标为“对齐”的是参数处理变化，不是功能变化）：`skills search` 只取一页；`skill shares` 用 `--page`；`version diff` 的 `--json` 带 `next`；`skills list` / `skills search` / `skill versions` 不提供 `--json`；`skill rm` / `write` / `unshare` 不要求 `--yes`；`setup` 的输出格式不变。

**其它既有命令**不受本契约追溯。标为“新增命令”的条款只约束未来新增命令；本版没有新增这一类命令时，条款仍然成立，但不产生本版验收对象。既有命令的现状由验收 #12 保护：除明文例外，输出与行为不变。

### 1. 分组与命名

| 组 | 对象 | 动词 |
|---|---|---|
| `skills` | 多个 skill | `list`、`search` |
| `skill` | 一个 skill | `invoke`、`files`、`read`、`cat`、`wc`、`grep`、`write`、`edit`、`rm`、`mv`、`cp`、`download`、`versions`、`submit`、`share`、`shares`、`unshare`、`request`、`create`、`open` |
| `version` | 一个 skill 的版本（作者面） | 正式动作：`list`、`show`、`diff`、`rename`、`note`、`discard`、`open`；拒绝型动词：`publish`、`rollback`、`revoke`、`restore`、`offline`、`online`、`delete` |
| 顶层机器命令 | 这台机器 | `login`、`logout`、`auth`、`setup` |
| `help` | — | — |

- `skill` 组只放**作用于一个 skill** 的动词；`skills` 组只放**作用于多个 skill** 的动词；`version` 管一个 skill 的版本（作者面）。
- 新命令先归组；需要新的顶层名词时，先写 ADR / PRD，不在实现里临时加。
- 组内复用现有动词；不造同义词（`view` / `get` / `describe` 不作为新正名）。
- 命令名用小写英文；参数用 `--kebab-case`；不加任意缩写。`grep` / `wc` 的 Unix 传统短选项（`-F` / `-i` / `-n` / `-l` / `-w` / `-c`）是既有契约的一部分，不在此规则内；新增长选项仍用 `--kebab-case`。
- 面向人的文字可以是中文；`--json` 的 `code` / `server_code` 是稳定的英文，不翻译。
- 未上线的旧命令不保留别名；上线后的兼容别名只能来自明确的破坏性变更方案，且不宣传。
- 页面动作是**拒绝型动词**：`version publish` 等七个动词留在 `version` 组，但**不进帮助、不计入正式动作**；它们只打印交接命令并以非 0 退出，不执行。
- 文件枚举的正名是 `files`；`ls` 不进入正式命令面。
- `skill create` 与裸 `skill submit` 是“一个 skill”的例外：它们创建/提交一个 skill，不接收一个**已存在**的 skill 地址；`create` 收新名字，`submit` 收本机名字/目录，`--to` 才是地址。

### 2. 地址

- 凡作用于一个**已存在** skill 的命令，共用 `<ns>/<name>` 的地址语法；**读取 skill 内容**的读命令（`read` / `files` / `cat` / `wc` / `grep`）可带 `[@<pin>]`；skill 级命令（`share` / `unshare` / `shares` / `request` / `versions` / `skill open`）不接受版本钉；`skills` 组与机器命令不带 skill 地址；`auth status` 可选接一个 skill 地址，是机器命令的例外；`skill create` / 裸 `skill submit` 的例外见 §1。
- 名字不含空白、`/`、`\`、`@`、`%`、`;`，且不能是 `.` 或 `..`；最多 64 个码点。服务端是最终判据；**新增/改动的校验逻辑**不得比服务端更宽；复用既有 `SkillPath` 的命令（含 `skill files` / `version list`）现状由 #12 保护。
- `<pin>` 的合法形状：
  - 消费面：版本号（SemVer）或 `sha256:<摘要>`；
  - 作者面读命令（`version show` / `diff` / `open`）：版本号、`sha256:<摘要>`、版本码；`draft:<ULID>` 是旧拼法，仍然可读；
  - 作者面写命令（`version rename` / `note` / `discard`）：接受版本码，含旧拼法 `draft:<ULID>`；草稿的地址是 `@<版本码>`，不是版本号或内容摘要。`draft:<short>` 不是合法版本码，由服务端拒绝（404）；本版不额外收紧本地校验；
  - 首次写入：`@new`，只对 `write` / `edit` / `rm` / `mv` 的首次改动有效，且要带 `--name`。
- 按命令分类（地址与钉）：
  - 读一个版本：`read` / `files` / `cat` / `wc` / `grep`；钉可选，解析顺序见下；
  - `invoke`：钉可选；无钉时重新解析并更新本机钉（见下）；
  - 作者面版本命令：`show` / `diff` / `open` 必须带 `@pin`；`rename` / `note` / `discard` 必须带 `@<版本码>`；`version list` 只接 bare skill 地址；
  - skill 级 / 无版本钉：`share` / `unshare` / `shares` / `request` / `versions` / `skill open` / `submit`（`--to` 地址）；`download` 只认地址钉；七个页面动作按 §5 交接、不解析本机钉；
  - 无地址（新建）：`create` 收新名字，裸 `submit` 收本机名字/目录；它们不接收已存在 skill 地址；
  - 内容写：`write` / `edit` / `rm` / `mv` / `cp` 的非 `@new` 形式接受 `@<版本码>`（含旧拼法 `draft:<ULID>`），不接受版本号/摘要；`draft:<short>` 不是合法版本码，服务端 404（与 `version` 写命令同规则）。
- **读取一个版本**的命令（`read` / `files` / `cat` / `wc` / `grep`）解析顺序：地址里写了 `@pin` 就用它；否则用本机 `invoke` 记住的钉；两者都没有时：
  - `read` 取**当前线上版本**；
  - `cat` 也先取当前线上；没有 `live` 且调用者是 `write`+ 时，会回落到最近可见版本并在 stderr 说明（既有行为，§10）；
  - `files` / `wc` / `grep`（作者面）取当前 `live` 行，没有 `live` 时取最近可见的版本，并在输出里说明。
  **所以“裸地址＝当前线上”只对读正文的命令成立，不是 `files` / `cat` / `wc` / `grep` 的默认。** 这些差异是既有行为，验收 #12 保护它们。
- **`invoke` 是例外**：不带 `@pin` 时先解析当前线上；没有 `live` 且调用者是 `write`+ 时，按既有行为回落到最近可见版本并钉住（与 `cat` 的 404 / 无 live 回落同类；消费面 503 不回落，直接报错，§10）；版本变了要在输出里说明。
- `share` / `unshare` / `shares` / `request` / `versions` / `skill open` 是 skill 级，不解析版本钉。`download` 只认地址里的 `@pin`（pin 形状与作者面读命令相同）；不写就取当前 `live`（作者面 `Latest`），不读本机钉（既有行为，§10）。
- `skill write` / `edit` / `rm` / `mv` / `cp` 的 pin 是版本码；首次写入 `@new`；不带 `@` 时只有本机钉是作者面钉才会用它，否则拒绝——既有行为，§10。
- CLI 不猜版本、不静默换版本；换版本要明说。

### 3. stdout / stderr / 退出码

- **stdout 是命令的答案；stderr 是提示、进度、错误。** `files` 的版本说明行是答案的一部分（说明这份清单是哪一版），留在 stdout；`wc` / `grep` 的解析提示按既有行为走 stderr。
- **skill 卡片**（`skills list` / `skills search`）：每个 skill 一张卡，第一行是**完整可复制的地址**（不带版本），缩进的第二行是描述与“什么时候该用它”；**第二行只在描述/适用场景非空时出现，为空时只有第一行**；卡片之间不混其它内容。**这是网关第 1 步依赖的稳定格式**，属于契约，不是“人话美化”。
- 其它列表的行形状由命令自己定：`version list` 是版本 + 状态 + 名称 + 时间；`skill versions` 是版本地址 + 当前/日期标记。不要把它们套成 skill 卡片。
- 内容命令：`cat` 原始字节；`files` 每个文件一行可复制的 relpath，正文/二进制提示缩进，文件清单之后给出 `read` 命令，版本说明行在 stdout 最后；`files` 的每个文件条目不打印 per-file URI / 摘要，结尾的 `read` 命令保留调用者的地址与 pin，所以 nameless 版本或 digest 钉下可能含 `sha256:`；版本说明行如果带 pin，也同样允许；`read` 带行号；`grep` 只回命中行；`wc` 计数。`cat` / `files` / `wc` / `grep` / `download` / `read` / `rm` / `skills list` / `skills search` / `skill versions` **不提供 `--json`**（`rm` 是写命令，也不接受；`skills list` 已经是这个行为，`skills search` / `skill versions` 本版对齐）：收到 `--json` 按未知参数拒绝，stderr 一句人话 + 下一步，非 0，不承诺 JSON 形状；不得把它当作查询词、地址或路径。
- `--json`（只适用于**声明支持 `--json`** 的命令）：成功时 stdout 恰好一个对象；失败时 stdout 为空，stderr 里恰好一个 `{ok:false,error:{...}}` **JSON 对象**。登录/进度提示可以出现在它之前（脚本只解析 JSON 行），但 stdout 不混文本。键用 snake_case；时间用 RFC 3339 UTC，不用本地时区或相对时间。
- 批量命令：`cat` / `rm` 的多路径形式在部分成功时必须逐项报错并以非 0 退出；不能把“有一部分失败”报成成功。新增批量命令同样。
- 退出码：0 成功；非 0 失败；`grep` 按 Unix 约定 1＝无命中、2＝用法/拒绝；`--json` 失败仍非 0。
- **完整的人话文本不是脚本契约；脚本用 `--json`（该命令提供时）。** 但上面的 **skill 卡片格式是例外**：agent 的网关路径读它，所以它的第一行地址与（非空时的）第二行描述是稳定契约。其它人话文本（进度、空态、错误措辞）不是。
- 错误：一句人话 + 下一步；不回显凭据；不打印栈；不把半份列表当答案。
- 截断/“还有更多”是**答案的一部分**，留在 stdout；stderr 只放进度、警告和错误。

### 4. 分页、截断与 `--all`

本节只适用于**分页列表命令**：`skills list`、`version list`。`skill versions` 一次给全；`skills search`、`skill shares`、`version diff` 是既有例外（§10）。

- 列表要么一次给全，要么提供 `--all` 由 CLI 翻到底；**不把游标交给调用者**（模型无法可靠复述长 base64）。这条同时约束人话输出与 `--json` 输出：`--json` 可以保留 `has_more` / `truncated` 这类布尔信号，但不能把服务端游标原样暴露成字段。
- 页大小由命令自己决定：
  - `skills list` 固定请求 100 条/页（服务端上限），提供 `--all`，不提供 `--limit`；
  - `version list` 的 `--limit` 是**每页大小**，默认 50，范围 1–200；不带 `--all` 时只取一页；带 `--all` 时用这个页大小循环取到底。`--all --limit 2` 合法：每页 2 条，直到取完；`--all` 的每一页都带同一组 `--status` / `--query`，不能丢过滤条件翻页。
- **`--cursor` 从本版起删除**：传了按未知参数处理（stderr 一句人话，非 0），不静默接受。人话输出与 `--json` 都不出现游标或 `--cursor`。
- 截断必须明说“还有更多”和收窄方式：
  - `skills list`：说还有更多，建议 `--all`；
  - `version list`：说还有更多，建议 `--all`，或用 `--status` / `--query` 收窄；
  - `skills search`：说还有更多，建议缩小关键词（本版不提供 `--all`）。
- `--all` 失败不打印半份；服务端重复游标 → 报错停止，不循环。
- `skill versions` 一次给全，不需要 `--all`；这是既有行为，作为回归保护。

### 5. 确认与页面动作

- **新增的**破坏性/连坐命令必须显式 `--yes`；没有 `--yes` 时只打印后果和完整命令，不执行。本版既有的 `version discard` 已经如此；`skill rm` / `write` / `unshare` 不在本版范围（§10）。
- 不读 stdin 等确认；CLI 不为 agent 制造交互阻塞。
- 页面动作：`publish` / `rollback` / `revoke` / `restore` → `version open … --sub actions`；`offline` / `online` / `delete` → `skill open … --tab settings`。七个动词都挂在 `version` 组下（`version publish` 等），只拒绝并交接，不执行。
- 成功要说清改了什么；不能只说“完成”。
- 每个有副作用的命令在 help 里写明副作用（`invoke` 钉版、`submit` 打开审批页、`share` 改变别人读得到什么……）；读命令不写。
- 新增的、会打开浏览器的命令必须提供 `--print`（或等价的“只打印地址”）路径，agent 可以不打开窗口就拿到地址；已有的 `skill open` / `version open` 已有，`submit` 的打开行为不变（不补 `--print`）。

### 6. 凭据与浏览器

- `login` 是唯一**以登录为目的**的命令；需要凭据的业务命令在缺凭据时会自动发起同一次登录（v4），没有浏览器时快速失败并指向环境变量或 `login`。`auth` / `logout` / `setup` / `help` 不等登录。`open` / `submit` 打开的是页面，不是登录页。
- **新增的、被设计为启动 / 后台 / 无人值守运行（不期待人操作）的命令，必须提供不触发登录的路径**（例如 `--no-login` 或专用命令），不能依赖“这台机器恰好没有浏览器”。新增的、以脚本/无人值守为目标的 `--json` 命令同样适用。
- 任何输出、错误、交接命令都不回显凭据。
- 401（凭据不可用）与 403（凭据可用但权限不够）分开说。403 的文案要区分 **scope 不足**（换一把带该 scope 的 key）与 **账号对这个资源的权限不足**（换凭据也一样）；不盲目重试。
- `--json` 错误带 `server_code` / `issue`。

### 7. 帮助

- 顶层 8 项，一行一个；每组 `help` 只列本组的**公开动词 + help 自身**；拒绝型动词不列。
- `skill help` 与 `skills help` 分开；`skill versions` 与 `version list` 互相指。
- 帮助到 stderr，`help` 退出 0；不宣传旧命令。
- 每个**公开**命令有一行 usage + 一句目的；有副作用的命令写明副作用，读命令不写。

### 8. 破坏性变更、弃用与网关

- 未上线的旧命令不需要兼容；本版没有迁移期别名。正式契约只包含本版帮助、README 与网关正文教给用户和 agent 的命令。
- 已上线命令的删除 / 改名是破坏性变更，要在 PRD / ADR 里写清迁移期、别名、提示流与删除版本。
- 迁移期别名只用于兼容，且不宣传；提示写 stderr，stdout 仍是命令答案。本版没有迁移期别名。
- `--json` 的字段是公开契约（从上线起算）：加字段可以，删字段 / 改名 / 改类型是破坏性变更，要有版本说明。
- **未上线例外**：产品尚未上线。本版对既有命令的参数/字段调整——`version list` 删除 `--cursor`、`--json` 删除游标字段、`skill files` 按 §3/#4 的新格式输出（relpath 行 + 缩进提示 + 结尾 `read` 命令 + stdout 版本说明行）、`read` / `rm` / `skills search` / `skill versions` 对 `--json` 按未知参数拒绝——属于命令面收口，不适用“迁移期 / 版本说明”的要求；这些是**本版有意的输出变更**，列在验收 #12 的例外里。破坏性变更规则从命令上线起算。
- 网关 `platform_api_version` 随协议变更 +1；`setup` 报告版本变化。网关正文是服务端构建产物的一部分（构建时打包、启动时发布）；**验收 #9 需要一份用新网关构建并重启过的服务端**，这是本版唯一的部署前置。
- 当前文档与网关同步改；`docs/versions/v*` 历史记录不改。
- 新增/改命令如果出现在 agent 的主路径上，必须同一次改网关正文；作者面命令可以只在 CLI README / help 里。

### 9. 新增命令检查清单

- [ ] 属于哪个组？需要新的顶层名词吗？需要 → 先写 ADR。
- [ ] 它作用于一个已存在 skill、多个 skill，还是创建一个 skill？地址/例外写清了吗？
- [ ] 权限、平面（消费面 / 作者面）写清了吗？
- [ ] stdout / stderr / `--json` / 退出码符合第 3 节吗？内容命令拒绝 `--json` 吗？
- [ ] 分页列表有 `--all`、且不暴露游标吗？页大小写清了吗？如果它属于 §10 的既有例外，写清了吗？
- [ ] 破坏性动作有 `--yes`，且打印后果吗？
- [ ] 页面动作是拒绝 + 交接，而不是执行吗？
- [ ] 无凭据时的行为写清了吗？启动 / 后台命令有不触发登录的路径吗？
- [ ] 有副作用吗？help 写了吗？会打开浏览器吗？有 `--print` 吗？
- [ ] 帮助、网关、CLI README、测试都改了吗？
- [ ] 有没有改变已上线命令的输出 / 退出码？有 → 这是破坏性变更，要有迁移方案与版本说明。
- [ ] 如果它在 agent 主路径上，网关正文同一次改了吗？

### 10. 既有例外与本版对齐

| 命令 / 行为 | 现状 | 本版 | 本版动作 |
|---|---|---|---|
| `skills search` | 只取一页；命中 `next_cursor` 时打印“还有更多，缩小关键词”；没有 `--all` / `--json` | 不改；**不是** §4 的违规，是检索的既有设计 | 不改 |
| `skill shares` | `--page` 页码分页，每页 10 人 | 不改；不纳入 §4 的分页契约 | 不改 |
| `version diff` | `--json` 带 `next`；人话输出提示“还有更多文件没取” | 不改；`next` 是文件续取游标，不是列表游标；新命令不得新增同类字段 | 不改 |
| `skill download` 的无钉默认 | 只认地址里的 `@pin`；不写取当前 `live`，不读本机钉 | 不改；§2 已写明 | 不改 |
| `skill cat` 的无钉默认 | 先取当前线上；没有 `live` 且 `write`+ 时回落最近可见版本并在 stderr 说明（含消费面 503 的「skill 下线中」回落） | 不改；§2 已写明 | 不改 |
| `skill invoke` 的无钉默认 | 先解析当前线上；没有 `live` 且 `write`+ 时回落最近可见版本并钉住；消费面 503 不回落，直接报错 | 不改；§2 已写明 | 不改 |
| `read` / `files` / `wc` / `grep` 的无钉默认 | `read` 取当前线上；`files` / `wc` / `grep` 取 live，没有 live 时取最近可见版本 | 不改；§2 已写明 | 不改 |
| `skill write` / `edit` / `rm` / `mv` / `cp` 的无钉默认 | 只有本机钉是作者面钉时才用，否则拒绝 | 不改；§2 已写明 | 不改 |
| `skills list` / `skills search` / `skill versions` 的 `--json` | 不提供；`skills list` 已经是未知参数拒绝 | `skills search` / `skill versions` 对齐为未知参数拒绝；网关读 skill 卡片格式（§3） | `skills search` / `skill versions`：对齐（#12 ⑥）；`skills list`：不改 |
| `skill rm` / `write` / `unshare` 的 `--yes` | 不要求 | 本版不加；它们的破坏性边界在各自 TD | 不改 |
| `setup` 的输出格式 | 已比较本地与本次的 `platform_api_version` 并打印变化 | 不改输出格式；本版只让它安装 9 版正文 | 不改 |
| `version` 的七个页面动作 | 挂在 `version` 组下，只拒绝并交接，不在帮助里 | 不改行为；本版把“拒绝型动词”写进 §1，避免被误算成正式动作 | 不改 |

## 验收与指标

### 验收标准

本版 in-scope 命令见 §CLI 命令行为规范 §0；#13–#17 只对这批命令做矩阵。既有命令的回归由 #12 保护。

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | 顶层正好 8 个命令：`login`、`logout`、`auth`、`setup`、`skills`、`skill`、`version`、`help`；`skillmaster help` 把它们都列出来（包括 `help` 自己） | 命令表 + `help` 输出断言 |
| 2 | `skills list` / `skills search` 是集合命令；`skill help` 的**动词表**不把 `list` / `search` 列为 `skill` 的动词（允许用 `skills list` 指路）；`skills help` 的动词表只有 `list` / `search` 与 `help` 自身；`skill help` 的动词表正好是 §1 的 20 个 + `help` | 帮助文本断言 + CLI 用例 |
| 3 | 旧的 `skill list` / `skill search` 不可用；不会转发到 `skills`，也不会产生集合列表输出 | CLI 子进程负向用例；分别捕获 stdout / stderr；stdout 为空，stderr 给出未知命令，退出非 0 |
| 4 | `skill files` 是文件枚举的正名；`skill ls` 不可用；`skill files` 不带地址时打印的是 `skill files` 的用法；输出是每个文件一行可复制的 relpath，正文/二进制提示缩进，文件清单之后给出 `read` 命令、版本说明行在 stdout 最后；每个文件条目不打印 per-file URI / 摘要，结尾 `read` 命令与版本说明行保留调用者地址与 pin（nameless 版本可能含 `sha256:`） | 帮助断言 + 无参调用断言 + `skill ls` 负向用例 + 输出格式断言 |
| 5 | `version` 仍在顶层，**正式动作只有** `list` / `show` / `diff` / `rename` / `note` / `discard` / `open` 七个；七个页面动作是拒绝型动词，不出现在 `version help` 的动词表（说明句可以指路，但不列命令名）；`version create` 不存在 | 命令表 + 帮助断言 + 负向用例 |
| 6 | `version list` 支持 `--all` 与 `--limit`；`--limit` 是每页大小（默认 50）；`--all --limit 2` 每页 2 条取到底；`version help` 的 `list` **用法行**宣传 `--all` 与 `--limit`、不出现 `--cursor`；`--cursor` 被当作未知参数拒绝；`--all` 的每一页都带同一组 `--status` / `--query`；人话输出与 `--json` 都不含游标字段 | CLI 用例 + `--all` 在多于页大小的数据上翻到底 + `--all --limit 2` + `--all --status draft` + 人话/JSON 字段断言 + `--cursor` 负向用例 |
| 7 | 七个页面动作仍被拒绝，并给出正确交接：`publish` / `rollback` / `revoke` / `restore` → `version open … --sub actions`；`offline` / `online` / `delete` → `skill open … --tab settings`；交接写 stderr，退出非 0 | 七个动词逐条断言交接命令，不执行；捕获 stdout / stderr 与退出码 |
| 8 | `skill help` 里 `versions` 那一行写“列出你可以调用的版本（只含已上线）”；`version help` 里 `list` **说明行**写“作者视角：线上、草稿、历史版本、已下架、已丢弃（按你的档位）”；两者互相指。`skill versions` 没有独立的 `--help`，这句话落在 `skill help` 的 `versions` 行 | 帮助文本断言；断言关键名词（调用 / 版本 / 线上 / 草稿 / 历史版本 / 已下架 / 已丢弃 / 作者视角）与两者互相指，不逐字锁口语 |
| 9 | 网关 skill 第 1 步是 `skills list`，第 1 步的说明与 §3 的卡片格式一致（描述为空时只有地址一行），检索是 `skills search`；`platform_api_version` 是 9；`setup` 看到已安装的 8 版网关时报告版本变化并安装 9 版；安装后的正文与仓库 `gateway/skillmaster/SKILL.md` **除 `https://<host>` 被替换为部署地址外**逐字一致；同一 `name` 目录下旧正文被替换、不并存（旧 `skillmaster-gateway` 目录不在本版清理范围） | 网关文件断言（按部署地址替换占位符后逐字比较）+ 当前网关安装用例 + 8→9 升级用例；需要一份用新网关构建并重启的服务端 |
| 10 | CLI 全部测试通过；`go test ./...` 退出码 0；`python3 .claude/skills/docs-architecture/scripts/check_docs.py` 退出码 0（定义阶段允许因 `technical-design.md` / `test-plan.md` 未写而暂不通过，交付前必须 0）；ADR 0077 四段齐全、已在 `docs/decisions/README.md` 登记；ADR 0077 在**首次提交前必须**修正：决定 1 要给出 `create` / 裸 `submit` 例外，理由段最后一条要删除/改写（若 ADR 已经提交，则新增一条取代 ADR、在 `docs/decisions/README.md` 登记，并把 ADR 0077 的文件头改为「被取代」、标出取代编号）；采用清单为决定 2 / 3 / 4 / 5 / 6；本 PRD §1 与「依赖与前置」的 ADR 条目已声明；`qa/role-matrix/README.md` 与 `qa/role-matrix/role_matrix/expectations.py` 里的旧命令已改到新命令（只改文档/探针，不跑 `qa/`） | `go test ./...`；`check_docs.py`；ADR 与 QA 文档/探针的 `grep` 断言 |
| 11 | 当前文档（CLI README、网关正文、CLI 帮助的命令表/协议段）不再把 `skill list` / `skill search` / `skill ls` 当作现行命令；`docs/decisions/` 的 ADR（含 0077）、`docs/versions/` 历史记录、本版“为什么改”的背景说明、`qa/` 开发态探针不计入本指标（QA 文件仍按 #10 更新） | 指定来源的 `grep` 断言 + 人看 |
| 12 | **没有服务端代码改动**；除下列明文例外，既有正式命令的输出与行为不变：① 旧命令移除；② 帮助 / 提示 / 网关正文；③ `version list` 的 `--all`、`--cursor` 删除、`--json` 游标字段删除；④ `skill files` 按 §3/#4 的新格式输出（relpath 行 + 缩进提示 + 结尾 `read` 命令 + stdout 版本说明行）；⑤ `setup` 安装 9 版网关正文（输出格式不变）；⑥ `read` / `rm` / `skills search` / `skill versions` 对 `--json` 按未知参数拒绝（`skills list` 已经是这个行为，不在本例外里）；⑦ `qa/role-matrix/` 的探针随旧命令更新（只改文档/命令名，不跑 QA） | `git diff` 只看 CLI / 网关 / 文档 / `qa/role-matrix/`；逐条例外的回归用例 |
| 13 | **输出与退出码契约**：stdout 是答案、stderr 是提示/错误；`skills list` / `skills search` 的卡片格式是第一行地址、缩进第二行描述（描述为空时只有第一行）；**§0 的 `--json` 支持命令**成功时 stdout 恰好一个对象，失败时 stderr 恰好一个 JSON 错误对象（之前可以有登录/进度提示），键 snake_case、时间 RFC 3339 UTC；不提供 `--json` 的命令（`cat` / `files` / `wc` / `grep` / `download` / `read` / `rm` / `skills list` / `skills search` / `skill versions`）收到 `--json` 按未知参数拒绝（非 0，不得当作查询词、地址或路径）；`cat` / `rm` 多路径部分成功逐项报错并非 0；退出码 0/非 0，`grep` 为 1 无命中、2 用法/拒绝；截断/“还有更多”留在 stdout | 逐命令的契约矩阵；分别捕获 stdout / stderr；内容命令与 `read` / `rm` / `skills list` / `skills search` / `skill versions` 的 `--json` 负向用例；部分成功用例；卡片格式断言 |
| 14 | **列表分页契约**：`skills list`（页大小 100）与 `version list`（`--limit` 每页大小，默认 50）提供 `--all`，不向调用者暴露游标；`--all --limit 2` 每页 2 条取到底；`--all` 每页都带同一组 `--status` / `--query`；截断时明确说“还有更多”；`--all` 失败不打印半份；服务端重复游标报错停止；`skill versions` 一次给全，不需要 `--all`；`skills search` / `skill shares` / `version diff` 按 §10 的既有例外处理 | 分页列表的 `--all` 用例；假服务端造两页与重复游标；截断文案断言；`skill versions` 不带 `--all` 也能列全；例外命令不作为违规 |
| 15 | **确认与页面动作**：`version discard` 没有 `--yes` 时只打印后果与完整命令、不执行（**人话模式下**后果与完整命令写 stdout，拒绝说明写 stderr；**`--json` 模式下按 §3/#13**：stdout 为空，后果/命令在 stderr 的 `{ok:false,error:{message}}` 里；两种模式都退出非 0）；七个页面动作拒绝并给正确交接（版本级 / skill 级）；`skill rm` / `write` / `unshare` 的 `--yes` 缺口按 §10 不计入本版 | 逐条 CLI 用例；不执行、只断言交接命令与退出码；明确排除 §10 的既有例外 |
| 16 | **凭据与浏览器**：输出、错误、交接命令都不回显凭据；401（凭据不可用）与 403（权限不够，区分 scope 与账号/资源权限）分开说；缺凭据的业务命令在无浏览器环境下快速失败、不尝试打开浏览器；既有 `--json` 命令的登录/进度提示可以出现在 JSON 错误对象之前 | 凭据泄漏矩阵；401/403（含 `insufficient_scope`）文案断言；无凭据 + 无浏览器的子进程用例；`--json` 失败时 stderr 的 JSON 行可解析 |
| 17 | **帮助契约**：顶层 8 项；每组 `help` 覆盖本组公开动词 + `help` 自身；拒绝型动词不列；不宣传旧命令；`skill versions` 与 `version list` 互相指；本版 in-scope 的每个**公开**命令有一行 usage + 一句目的；有副作用的命令写明副作用，读命令不写 | 帮助文本断言；旧命令不出现在帮助里；usage / 目的 / 副作用断言 |

**成立条件**：**#3、#4、#5、#7 与 #13 是「不满足就不算交付」的五条。** #3/#4 是命令面收口：还没上线的旧命令不能带进正式契约；#5 是产品判断：`version` 是作者工作流，不是 `skill` 的子属性；#7 是产品边界：改变调用者能读到什么的动作只在浏览器里发生；#13 是**这套契约能不能约束未来命令**的判据——输出、错误和退出码一旦各写各的，下一批命令就会各漂各的。任何一条不成立，这一版就只是把命令换了个名字，没有把边界说清。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 帮助覆盖率 | 每个组的帮助是否列出该组全部**公开**动词（拒绝型动词不列，`help` 自身计入） | 逐组对照 §1 命令表 | 100% |
| 旧命令残留率 | `skill list` / `skill search` / `skill ls` 是否还能作为成功命令运行 | CLI 子进程矩阵 | 0 |
| 当前文档里的旧命令 | 只统计 `skillmaster-cli/README.md`、`gateway/skillmaster/SKILL.md` 与 CLI 帮助（顶层 / `skill` / `skills` / `version`）中把旧命令当现行命令的出现次数；ADR（含 0077）、`docs/versions/` 历史记录、本版背景说明、`qa/` 不计 | 指定来源的 `grep` + 人看 | 0 |
| 版本列表的翻页 | `version list --all` 在超过一页的数据上是否取全、且不要求调用者携带游标 | 假服务端造两页 | 全部取到，0 个游标外露 |
| 契约测试覆盖 | §0 的**公开** in-scope 命令是否按其声明的输出模式至少有一条成功、一条失败；**帮助命令**（顶层 `help`、`skill help`、`skills help`、`version help`）只要求成功用例（打印帮助、退出 0），不要求失败；七个页面动作只要求拒绝/交接用例，不要求成功；负向旧命令只要求不可用用例，不要求成功；支持 `--json` 的另加一条 JSON 失败；不提供 `--json` 的另加一条 `--json` 拒绝；`help` 与负向旧命令的 `--json` 不适用，不要求用例 | 逐命令对照测试矩阵 | 100% |
| 输出里的凭据 | 命令输出、错误、交接命令中出现的凭据次数 | 注入一把测试用哨兵 key（如 `skm_TEST…`），在 stdout / stderr / 交接命令里 `grep` 该哨兵，并 `grep` 常见凭据前缀 | 0 |
| 列表命令的游标外露 | `skills list`、`skills search`、`skill versions`、`version list` 的输出里是否出现游标 | 逐命令捕获 stdout / `--json` | 0；`version diff` 的 `next` 与 `skill shares` 的页码按 §10 例外处理 |

## 依赖与前置

- **外部依赖**：无。只改 CLI、网关正文与文档；服务端代码不动。
- **部署前置**：网关正文在服务端构建时打包、启动时发布。验收 #9 需要一份用新网关构建并重启过的服务端；这不是服务端代码改动。
- **内部前提**：
  - [ADR 0077](../../decisions/0077-the-cli-grammar-is-singular-and-plural.md)：单数 `skill` / 复数 `skills` 的分组，以及 `version` 留顶层的决定。**注意**：本版采用 ADR 0077 的决定 2 / 3 / 4 / 5 / 6；决定 4 是“旧命令不进入正式命令面”，本版执行；决定 1 的“每个动词都带一个 skill 地址”由 §1 的 `create` / 裸 `submit` 例外取代；理由段最后一条“否掉直接改名、不留别名”与决定 4 冲突。这两处已在 ADR **首次提交前**修正（草稿修正，不是改写已采纳的决定），ADR 现在随本版文档提交在 `aaae2ac`。本版不保留别名。
  - [ADR 0002](../../decisions/0002-gateway-skill-and-cli.md)：网关 skill + CLI 是 agent 的入口；第 1 步的改动依赖它。
  - [ADR 0011](../../decisions/0011-server-and-cli-stack.md)：CLI 用 Go。
  - [ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md) / [0046](../../decisions/0046-deleting-a-skill-happens-in-the-browser.md) / [0061](../../decisions/0061-what-a-credential-may-do-to-versions.md)：页面动作的边界。
- **版本编号**：本版记为 v13；原 `v13-skill-tags` 的 PRD 不在本分支，恢复时顺延为 `v14-skill-tags`，并把其中的 CLI 命令改成 `skills …`；本版**不含**标签命令。ADR 0077 写于顺延之前，其中“v13 的标签命令”按本版编号应读作 v14。版本编号以 `docs/versions/` 的目录名为准；仓库暂无独立版本索引。
- **排除了什么前置**：
  - **不需要服务端代码改动**：命令名是 CLI 层的事，API 与权限都不动；网关正文随服务端构建/重启发布，见“部署前置”。
  - **不需要设计稿**：没有界面变化。
  - **不需要合并两个版本列表**：那是把 `version` 收进 `skill` 的前提，不在本版。
  - **不需要给既有命令补契约缺口**：`skills search` 的分页、`skill shares` 的页码、`version diff` 的 `next`、`skill rm` / `write` / `unshare` 的 `--yes` 都按 §10 既有例外处理，本版不改。
