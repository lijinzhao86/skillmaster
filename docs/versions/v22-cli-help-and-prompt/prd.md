# v22-cli-help-and-prompt · 产品需求

> **最后更新**：2026-10-27
> **所属版本**：[`README.md`](README.md)

<!--
这是本版需求的权威文档。README 只放摘要，别处不要重复这里的内容。
-->

## 用户与场景

**谁在用**

| 角色 | 在这一版里做什么 |
|---|---|
| **agent（主读者）** | 手上只有两层引导：系统提示词里那段被粘进去的规则，和 CLI 的 `--help`。它要据这两样判断「这件事该不该用 SkillMaster」以及「具体怎么做」。 |
| **配置 agent 的人** | 按旅程把规则段粘进指令文件；以后规则段更新时，他要能知道该重粘。 |
| **产品维护者** | 改一句引导文案时，要能判断它属于哪一层、改了之后谁需要重粘。 |

**在什么场景下用**

- agent 接到「把某个 skill 的说明改一句」「给这个 skill 加一个文件」「把它上线」「共享给同事」这类任务。规则段告诉它「该用 SkillMaster」，但「按什么顺序做」没有地方写。
- 维护者要把一条多步流程加进引导，第一个问题是：加在规则段，还是加在 help？

**现在的替代做法是什么**

规则段（[ADR 0096](../../decisions/0096-guidance-lives-on-the-platform.md) 定的三行）负责触发与第一次检索；CLI help（v16）负责每条命令自述。**两者之间没有约定**，于是 agent 只能逐条试命令；试错的具体代价见下一节。

## 调研方法与口径

**口径**：2026-10-27，用已发布的 `skillmaster 1.0.2`（API v1）实跑，源码对照工作区 HEAD。共抓取 **34 个帮助面**：顶层与 `help env`（2）；`auth` / `login` / `logout` / `startup`（4）；`skill` / `skills` / `version` 三个组（3）；`skills list/search/categories/pending`（4）；`skill invoke/read/files/cat/write/edit/submit/download/share/shares/unshare/request/open/show/versions`（15）；`version list/diff/open/publish`（4）；`skill create` 与 `skills my-tags`（2）。

**三轮独立**：

1. 作者实测：逐条抓帮助、对运行时文案与声明；
2. 审计一路：逐条核实初稿的 24 条，判 CONFIRMED / PARTIAL / FALSE；
3. 审计二路：**不看初稿**，只从 CLI 本体出发独立找 blocker。

第二轮推翻了初稿的 5 处（`cp` 不接受 `@new`、版本解析分两组、无浏览器报错不指 key、`auth status` 的 key 分支不给续期地址、`pending` 交接地址不可照抄），并新增了「输出形状与帮助不符」这一整组。此后做了 **12 轮循环审计**（文档内部一致性、文档对代码的逐条断言复核、严重度判据复查），其中第 7 轮起并入 D9（`grep` 的范围）与 D10（没有一致性检查工具），并把原 B7 并进 S8；本稿是第 12 轮修订版，两路均返回 0 blocker / 0 high。

**读者假设**：一个只读过 `skillmaster help` 和对应 `--help`、没有读过本仓库文档的 agent。判据是「它能不能只凭这些决定下一步」。

## 严重度

| 级别 | 判据（按顺序判，命中即停） |
|---|---|
| **BLOCKER** | ① **静默做错**：不报错、或报错指向错误原因，agent 会拿错版本 / 给错权限 / 写坏内容 / 提交错目录；② 或这条旅程**根本走不完**。 |
| **HIGH** | ① 正常路径上**失败一次，且报错能自己解释下一步**；② 或拿到需要二次核对的内容（为核对而多出的请求或确认）。 |
| **MEDIUM** | 发现性或歧义问题，**不导致任何执行失败**。 |
| **LOW** | 无运行时影响。 |

**判据的应用**：`--from` 的例子报「版本不存在」→ 误导，是 BLOCKER；`cp @new` 报「先建草稿再 cp」→ 自解释，是 HIGH；「合法档位只能靠报错学」→ 报错会列出全部档位，自解释，是 HIGH；`invoke > body.md` 多出一行说明 → 不报错但写坏内容，是 BLOCKER。

**本版只做 BLOCKER 与 HIGH**；MEDIUM 只在与它们同页、同段顺手改时带上（逐条列在 §范围）。

## 旅程与问题清单

五条旅程（A–E）共 30 条；F 是贯穿它们的顶层导航与契约，5 条。合计 35 条（12 BLOCKER / 16 HIGH / 6 MEDIUM / 1 LOW）。

### A · 获取凭据

| # | 严重度 | 现状（证据） | 影响 | 解决 |
|---|---|---|---|---|
| A1 | **BLOCKER** | `login` / `startup` 都不提无浏览器、容器、CI、sandbox 的凭据路径；`help env` 只写「`SKILLMASTER_API_KEY`…别把值打印出来」（`declarations.go:57-58`），**没有**控制台 `/account/keys`、没有 `skm_` 前缀、没有「用 `auth status` 验证」。这些只在运行时文案里（`main.go:720-724`、`internal/apikey/format.go:23`）。 | 只看帮助的 agent 以为凭据只能靠浏览器；它会跳过凭据获取，或去跑一条自己已知走不通的 login。 | S1、S5 |
| A2 | HIGH | `clientFor` 的自动登录路径在**无浏览器且无凭据**时返回裸错误「本机没有可用的凭据：先运行 skillmaster login」（`main.go:1081-1092`、`internal/auth/session.go:192`），不提 key。**注意**：`--no-login` 分支（`main.go:1078-1079`）和 `login` 命令本身（`main.go:718-724`）都有正确的 key 指引；缺的只是自动路径。 | 无头 Linux 上，agent 被指引去跑 `login`；跑一次后能拿到正确指引，多绕一轮。 | S4 |
| A3 | **BLOCKER** | 凭据保存失败只返回裸错误「保存凭据：<原始错误>」（`main.go:838-839`），没有下一步。 | 沙箱/只读 HOME 下 `login` 必然死在这里（WorkBuddy 实测），用户拿不到「改用 key」或「给配置目录写权限」的指引。 | S4 |
| A4 | **BLOCKER** | 无凭据时，`clientFor` 在 `browserAvailable()` 为真时会自动打开浏览器并等最多 5 分钟（`main.go:1081`、`login.go:46`）；核心读命令 `invoke/read/cat/files/grep/wc` **没有 `--no-login`**（只有 7 条元数据命令有），help 也不说「不设它就会去登录」。**边界**：纯无头 Linux（无 `DISPLAY`）是快速失败；macOS / Windows / 有 DISPLAY 的 Linux 才会等满 5 分钟。 | 在 macOS 沙箱或带 DISPLAY 的容器里，一条 `skill invoke` 会挂满 5 分钟。 | S5 |
| A5 | HIGH | 6 条命令的 401 下一步写死「运行 skillmaster login」（`declarations.go:145-148`、`declarations_other.go:25,98`、`declarations_skill.go:16,94,358`），对 API key 是错的；共享句本来给了两条路（`internal/help/help.go:22-25`），但被这 6 条自己的 `unauthenticated` 顶掉（`help.go:29-45`）。 | 持 key 被拒的 agent 去重跑 login；而环境变量优先，login 也救不回来。 | S3 |
| A6 | HIGH | `auth status` 的 key 分支只打印名字、权限、到期、最后使用（`main.go:590-601`），**不打印续期/撤销地址**；登录分支才调 `whereToRevoke`（`main.go:587`）。 | key 用户想续期或撤销时，CLI 里找不到控制台入口。 | S1、S5 |
| A7 | MEDIUM | `auth status --help` 的用法行漏了可选地址 `[<地址>]`（`declarations.go:142`；代码接受它，`main.go:512-516`）。 | 少一个诊断能力。 | S5（同页顺手） |

### B · 消费（找、加载、读）

| # | 严重度 | 现状（证据） | 影响 | 解决 |
|---|---|---|---|---|
| B1 | **BLOCKER** | `skill read` 的 stdout 是**带行号的**（`1\t…`），末尾还追加一行版本页脚（实测；`main.go:2259-2262`、行号前缀在 `content.go:32-35`）；帮助只说「文件内容写到 stdout」。`cat` 才是原始字节。 | `read` 的输出写回（`read \| write`）会把行号和页脚一起提交成正文——静默的内容污染。 | S6 |
| B2 | HIGH | 帮助说「一行一个」，实际：`files` 每个路径一行，但正文/二进制条目后有一条缩进说明，末尾还有两条全列表页脚（`main.go:2462-2475`）；`versions` 每版是地址行 + 0–2 行缩进（`main.go:2542-2554`）；`search` 每条是地址 + 描述 2–3 行（`main.go:1329-1341`）。三者都没有 `--json`。 | 用 `for p in $(skill files …)` 会把说明当路径。 | S6 |
| B3 | **BLOCKER** | `invoke` 往 **stdout** 追加「（已按 … 加载。…）」（`main.go:2029-2030`），帮助说「把正文写到 stdout」；`cat` 对二进制直接 `io.Copy`（`files.go:287`），没有保护；`read` 反而有保护（`printBinary` 写临时文件并打印路径，`main.go:2489-2508`）。 | `invoke > body.md` 得到的不是纯正文——把说明写回就是内容污染；`cat` 多个文件时可能把二进制塞进上下文。 | S6 |
| B4 | **BLOCKER** | 裸地址用的是**本机上次 `invoke` 钉住的版本**，不是当前线上。实测：线上 `@1.2.0`，`skill files skillmaster/startup` 末尾写「按 @1.1.0 操作」；帮助从不提（`main.go:2140-2158`、`files.go:69-76`）。版本注记的输出流还分两组：`read`/`files` 走 **stdout**（会被写进文件），`cat`/`wc`/`grep` 走 stderr。 | agent 以为省略版本就是「看最新的」，静默读到旧版；用 `read` 写回时旧版页脚也被写进去。 | S6 |
| B5 | **BLOCKER** | 版本解析分两组：`read` 是「显式 `@版本` → 上次 pin → 当前线上」，没有线上时直接 404；`invoke/cat/files/wc/grep` 在无 live 时为 `write`+ 回退到最近可见版本（可能是草稿）（`files.go:38-85,120-206`）。帮助没有一句。 | 按 `read` 的规则理解 `invoke/cat`，会静默落到草稿版。 | S6 |
| B6 | MEDIUM | 六个读命令各说各做什么，没有「什么时候用哪个」（`declarations_skill.go:12,25,40,54,70,91`）。 | 选错最多多一次请求。 | S6（同页顺手） |

### C · 创作（本地目录）

| # | 严重度 | 现状（证据） | 影响 | 解决 |
|---|---|---|---|---|
| C1 | **BLOCKER** | `submit <名字>` 按 `SKILLMASTER_SKILLS_DIR`（默认 `~/.claude/skills`）解析，**不是当前目录**（`main.go:3030-3034`、`internal/config/config.go:151-159`）；只有含 `/`、前导 `.`/`~` 才当路径（`main.go:3102-3107`）。`help env` 提了，`submit --help` 没提。 | 在项目里 `submit my-skill` 会静默提交全局同名 skill——内容错了也不报错。 | S5 |
| C2 | HIGH | `submit --help` 的首例是 `skillmaster skill submit .`（`declarations_skill.go:251`），但本地目录没有 sidecar，省略 `--name` 会被拒（服务端只在包里有 sidecar 时回退取名，`SubmitSkillUseCase.java:86-88`）。 | 照首例执行失败一次。 | S5 |
| C3 | MEDIUM | `submit` 打印的「审批地址」是 skill 总页（`main.go:2918-2920`），不是该草稿的操作/上线页。 | 打开后还要自己找草稿。 | S5（同页顺手） |
| C4 | HIGH | `submit` 的 `Next` 只有「在浏览器里点上线」；`write`/`edit` 没有 `Next`，也不提 `submit`（`declarations_skill.go:102-144,252`）。 | 两条创作路径互不可见；实测导致「下载 → 改 → submit」，四轮迭代四份草稿。 | S2、S5 |
| C5 | MEDIUM | `download` 只写「整版取成一个目录」，不说它是一次性快照、往返摘要相同，也不是编辑路径（`declarations_skill.go:208-218`）。 | agent 把下载当工作区。 | S5（同页顺手） |

### D · 创作（在线改）

`grep` 与 `skill check` 都是读，但它们服务的是「在线改」——改之前先在某个版本里找，改完在同一版上校验——所以归在这一组。

| # | 严重度 | 现状（证据） | 影响 | 解决 |
|---|---|---|---|---|
| D1 | **BLOCKER** | `@new` 从未被定义：用法行只写 `@<版本码>`，`@new` 只散落在部分示例里；只有 `write`/`edit`/`rm`/`mv` 接受（`files.go:617,654,912,969`），**`cp` 明确拒绝**（`files.go:969-973`）；第二次 `@new` 内容不同时会再建一份草稿（内容完全相同则被 `content_already_exists` 拒绝，`SkillVersionService.java:469-474`）。 | agent 可能每个文件都写 `@new`，建出多份草稿，最后只上线其中一份。 | S2、S5 |
| D2 | HIGH | `cp --help` 教了 `@new` 的用法，还配了 `--from/--name/--note` 三个永远用不上的 flag（`declarations_skill.go:187-204`），照抄必失败。 | 失败一次；报错会自己解释「第一次改动不能是复制，先建草稿」。 | S5 |
| D3 | **BLOCKER** | `--from` 五处都写 `<相对路径>`「第一次写入时从哪个文件起稿」，实际取的是**版本**（`declarations_skill.go:105,129,149,169,190`；读版本在 `files.go:1157`）；`write` 的例子 `--from SKILL.md` 照抄 404（`declarations_skill.go:119`）。 | 照帮助做会失败，且错因看起来像「版本不存在」。 | S5 |
| D4 | HIGH | `write`/`edit` 的成功信息与**草稿码只打在 stderr**（`files.go:801,1149`），stdout 为空；帮助的「输出」不说。`version list --help` 其实写了 `--status draft`（`declarations_other.go:152-162`），但 `write`/`edit` 页不指过去。 | 只捕获 stdout 的 agent 拿不到草稿码，后面 `version diff`/`open` 都缺地址。 | S5 |
| D5 | HIGH | `@new` 成功后没有下一步（`files.go:1149` 只打印「已建出草稿 base@code」）。 | 拿到码不知道接下一条命令。 | S5 |
| D6 | HIGH | 没有一页讲「读原文 → `@new` → 同一草稿上改 → `version diff` → `version open --sub actions`」；`write`/`edit` 不指 `diff`/`open`（`declarations_skill.go:102-144`）。 | 最常见的多步流程只能自己拼。 | S2、S5 |
| D7 | HIGH | `--name` 对 `@new` 是必填（`write` 在 `files.go:617-621`，`edit/rm/mv` 经 `createDraftWithEdit` 在 `files.go:1115-1118`），帮助只写「第一次写入时这一版的名字」。 | 第一次尝试即失败；报错会说明要加 `--name`，自解释。 | S5 |
| D8 | HIGH | `version publish` 是拒绝命令、退出 1，容易被 agent 当成失败而中止交接（`declarations_other.go:280-289`）。 | 至多断一次；报错会说明「只在网页做」并给出交接命令，读到即可继续。 | S5 |
| D9 | HIGH | **`grep` 只搜草稿**，服务端是两道门：控制器只接受版本行 id，版本号 / 摘要直接 `grep_requires_draft`（`AuthorSkillController.java:441-449`）；状态门又要求 `write`+ 且 state 必须是 draft（`GrepDraftUseCase.java:138` → `SkillVersionService.java:131-149`）。只改前一道，非草稿的行 id 仍会被后一道拒。ADR 0062 把范围限定在草稿。已发布版本只能 `cat <地址> <文件>… \| grep`，一次把整版字节过用户网络——这正是 ADR 0062 自己否掉的「做法 2」；而且多条 `cat` 拼成一条流没有分隔，本机的 `grep` 答不出「命中的是哪个文件」。 | 改线上版本前要先在本地过滤；大 skill 每次搜都要拉整版，且拿不到服务端的行号/截断语义。 | S8 |
| D10 | HIGH | **没有任何命令能检查一版内部的相对引用是否都成立**：命令表里没有 validate / lint / check；服务端只在校验根 `SKILL.md` 与 frontmatter（`SkillUploadValidator.java:35,77-86`），**不检查相对引用**。改完一版 `SKILL.md` 里的 `](其他文件.md)` 或反引号路径后，断链只能靠人翻，或先把整版 `download` 到本地再用外部工具。 | 在线编辑这条路少一个收尾步骤：agent 改完无法自证一致性，交付前必须多一次人工核对。 | S9 |

### E · 共享与权限

| # | 严重度 | 现状（证据） | 影响 | 解决 |
|---|---|---|---|---|
| E1 | **BLOCKER** | 五个档位（`invoke`/`browse`/`write`/`maintain`/`admin`）的含义在任何帮助页都没有；`share` 的 `--role` 省略时默认 `invoke`（`main.go:1795`），帮助不写；语义只在成功后的 `roleLine` 里（`main.go:1851-1867`）。 | 用户说「让他管理」，agent 省略 `--role` 会静默只给调用档；选 `maintain` 又改不了协作者。 | S5 |
| E2 | HIGH | `request` 的 `--role <档位>` 不列取值，`shares` 也不列；只有 `share` 列了五个名字（`declarations_skill.go:256-259,317-322`）。 | 合法值只能靠报错学；报错会列出全部档位，自解释。 | S5 |

### F · 顶层导航与契约

| # | 严重度 | 现状（证据） | 影响 | 解决 |
|---|---|---|---|---|
| F1 | **BLOCKER** | 没有 `help auth`、也没有 `help workflow`（实跑：未知主题，退出 2）；顶层只有命令组与「环境变量」一行（`internal/help/render.go:149-177`）。 | 从零开始的 agent 没有「先读哪一页」的起点，跨命令的知识没有家。 | S1、S2 |
| F2 | MEDIUM | 顶层列表里 `startup` 只显示「开始入门旅程」（`internal/help/render.go:149-177`）；`startup --help` 自身已经写明加载官方技能与凭据。 | 发现性差。 | S5（同页顺手） |
| F3 | MEDIUM | 两处帮助引用 ADR 编号（`skills pending` 的 ADR 0031、`version release-type` 的 ADR 0091）；版本页专用动词的运行时文案也带 ADR（`version.go:139-140`）。 | 只有本仓库读者认识。 | S5（同页顺手） |
| F4 | HIGH | `skills pending` 只列「我够得着上线」的（`maintain`+），但空态说「提交后，尚未上线的草稿会出现在这里」——对 `write` 档是假的；「每个 skill 一行」也不准（实际是地址行 + 缩进的 live 状态行）；交接句的地址是 skill 行（可照抄），但落在 `--tab versions`，定位不到具体草稿（`pending.go:49,56-57,63`）。 | 编辑者会以为草稿丢了；交接要多绕一步。 | S5 |
| F5 | LOW | `skills pending --help` 写「最后一行是总数」，实际最后一行是交接句（`declarations_other.go:56`；`pending.go:63-75`）。 | 小。 | 记录 |

## 解决方案

### S1 · `help auth`（≤ 512 B）

两种凭据、在哪管：

- 有浏览器：`skillmaster login`；没有浏览器（容器 / CI / sandbox）：到控制台 `/account/keys` 签一把 `skm_…`，放进 `SKILLMASTER_API_KEY`，用 `skillmaster auth status` 验证；别把值打印出来。
- 撤销 / 续期：登录 → 控制台「登录设备」；key → 「API 密钥」。`logout` 只清本机。
- 本机存储写不进去时：改用 key（S4 的错误句指过来）。末尾一行指到 `help env`。

### S2 · `help workflow`（≤ 512 B）

三条主流程：

1. **消费**：`skills search` → `skill invoke`（钉版本）→ `skill read`/`files`；裸地址用的是上次 invoke 钉住的版本，要看当前就写 `@版本`。
2. **本地创作**：`skill submit ./<目录> --name <版本名>` → `skills pending` → `version open <地址>@<草稿码> --sub actions`。
3. **在线创作**：`skill read` → `skill write <地址>@new <路径> --name <版本名> --from <版本>`（内容从 stdin 读；`@new` 只用于第一次，之后用返回的草稿码；`cp` 要先有草稿）→ 同一草稿上 `write`/`edit` → `version diff` → `version open --sub actions`。

顶层 `help` 在「环境变量」那行旁边加一行：`主流程：skillmaster help workflow`。

### S3 · 401 的下一步按凭据形态给全

**统一改 6 条命令自己的 `unauthenticated` 行**（`auth status`、`skills list`、`skills my-tags`、`skill invoke`、`skill grep`、`skill show`），同时给「重新登录」与「API key 到控制台续期或新签（`help auth`）」。**不改去重规则**。加一条测试比对声明文本与 `refusalMessage` 不矛盾。

### S4 · 凭据失败路径

- `clientFor` 的无浏览器分支：把裸错误改成与 `loginWithBrowser` 同一句（含 `SKILLMASTER_API_KEY` 与 `help auth`）。
- `saveAndReport` 的保存失败：附下一步——「改用 `SKILLMASTER_API_KEY`，或给配置目录写权限后重试」。

### S5 · 命令级修补

| 针对 | 改什么 |
|---|---|
| A1/A6/F2 | `login` / `startup` / `help env` 补 key 路径、签发地址、`skm_` 前缀、验证方式；`auth status` 的 key 分支补续期/撤销地址 |
| A4 | `invoke/read/cat/files/grep/wc` 的帮助补一句「无凭据时会尝试浏览器登录；无人值守用 `--no-login`（若该命令支持）」；不支持的命令明确说「会等你登录」 |
| A7 | `auth status` 用法加 `[<地址>]`，例子给一条 |
| C1/C2/C3/C5 | `submit` 写明裸名字按 `SKILLMASTER_SKILLS_DIR` 解析、首例加 `--name`、审批地址说明它落在 skill 总页；`download` 注明一次性快照 |
| D1/D2/D3/D4/D5/D7/D8 | `@new` 进用法行（只 write/edit/rm/mv）；`cp` 删除 `@new` 例子与三个死 flag；`--from` 改版本；写命令的成功输出说明在 stderr；`@new` 后打印下一条命令；`--name` 标必填；`version publish` 的拒绝说明它不是失败 |
| **C4/D6** | `write`/`edit` 的 `Next` 指到 `submit`、`version diff`、`version open --sub actions`；`submit` 指到在线改与 `version open` |
| E1/E2 | `share` / `request` / `shares` 加五档含义与默认值 `invoke`；`share` 指到协作者管理页 |
| F3/F4 | 去掉两处 ADR 编号与运行时 ADR 引用；`skills pending` 说明只列可上线的、交接用 `version open …@<草稿码> --sub actions` |

### S6 · 输出契约写进帮助

- `read`：带行号与版本页脚（页脚在 stdout），**输出不能直接写回**；整文件读取用 `cat`。
- `cat`：原始字节，可能含二进制（没有 `read` 的二进制保护）；多文件时小心。
- `invoke`：正文无行号，末尾追加一行版本说明（stdout）。
- `files`/`versions`/`search`：写明实际形状（哪些行是数据、哪些是说明），并说明没有 `--json`。
- **何时用哪个读命令**：要正文用 `invoke`；要原始文件用 `cat`；要定位路径用 `files`；要元数据用 `show`；要看可调用版本用 `versions`。
- **版本解析**：裸地址 = 上次 `invoke` 钉住的；没有 pin 才是当前线上；`read` 与 `invoke/cat/files/wc/grep` 在「没有线上版本」时的行为不同（后者对 `write`+ 可能落到最近可见版本）；版本注记在 `read`/`files` 走 stdout、`cat`/`wc`/`grep` 走 stderr。

### S7 · 规则段只指路

规则段不复制流程，保留「更多命令见 `skillmaster help`」，并加一行指到 `help workflow` / `help auth`。**参数禁令**：规则段里不出现命令参数名；现有 `--scope public` 是唯一例外（它改变的是「问的是哪一类东西」）。**预算**见 §验收与指标（≤ 300 字、≤ 3 行）。这一项是版本级目标（§做 7），入口是 F1 的两个 topic。

### S8 · `grep` 对任意可读版本生效

**两道门一起改，只改一道等于没改**：

1. 控制器：`grepRowIdOf` 只认行 id，版本号 / 摘要直接拒绝（`AuthorSkillController.java:441-449`）→ 改成与 `wc` 同一个 `pinOf`，把 pin 交给用例。
2. 状态门：`openDraftForRead` 要求 `write`+ 且 state 必须是 draft（`SkillVersionService.java:131-149`）→ 换成 `authorSnapshotAtLeast(..., BROWSE)`（`SkillVersionService.java:1054-1067`）。**一处换掉两层条件**：它先 `atLeast(BROWSE)`，再按 `visibleStates`（`SkillVersionService.java:1180-1189`）过滤状态。只把状态集换掉、留着 `atLeast(WRITE)`，`browse` 的调用者仍会被 403。

**已丢弃不新造例外**：`visibleStates` 对 `maintain`+ 含 `discarded`，所以 `grep` 与 `files` / `wc` / `cat` 一样能搜（裸地址不会主动选已丢弃，要搜就带 `@<版本码>`——与 `cat` 一致）。`download` 拒绝它是**导出**规则（`ArchiveSkillVersionUseCase.java:66-69`），不是读取规则。grep 是读，跟着读的那条走——否则同一个版本「能 `cat` 不能 `grep`」是一条没人能预期的新规则。

**拒绝的形状与作者面其他读一致**：低于 `browse` 的档位是 403（`NotPermittedException`，`authorSnapshotAtLeast` 的第一句），行级不可见（例如 `write` 问已下架）是 404（`findVisible` 空集）。不是一律 404。

输出、上限、`-F/-i/-n/-l/--path`、pattern 校验、RE2 / KMP 都不变；只改「哪一版可以搜」。用例从 `GrepDraftUseCase` 改名为 `GrepVersionUseCase`（它扫的是版本，不是草稿），方法名与变量同步。**用户可见文案一起改**：`grep --help` 的 Summary / Output / 示例仍写着「在草稿里搜文本」，`grepRefusal`（`files.go:542-546`）把服务端错误翻成「grep 只在草稿上搜索」——S8 之后这两处都会变成假话。需要一条新 ADR 取代 ADR 0062 里「仅草稿」那一条范围（扫描方式与不建索引的结论保留）。

### S9 · 新增 `skill check <地址>`

客户端实现，检查**一版内部的相对引用是否都成立**——这是服务端唯一不做的那一类检查。

**为什么不查 frontmatter 与根 `SKILL.md`**：这两项在写入路径上已由服务端用真正的 YAML 解析器校验（`SkillUploadValidator.java:35,77-86`：缺根 `missing_skill_md`、缺 `name` / `description`、目录名与 `name` 不一致都会拒），而本 CLI 刻意不解析 frontmatter（`internal/api/client.go:467-469`）。在客户端复述它们，要么塞进一个 YAML 依赖，要么再发一次 skill 级请求，换来的只是重复校验。

- **两次请求，落在同一版**：`files <地址>` 给出清单（relpath + `isBinary`）**与 manifest 摘要**（`AuthorFileList.ETag`，`internal/api/author.go:341-384`）；归档用**那个摘要作 pin**——ETag 是带引号的 `"sha256:<hex>"`，先去掉外层引号、保留 `sha256:` 前缀（`SkillAddress.pinOf` 只认这个形状）。两次请求之间版本被发布或改写时，摘要 pin 要么仍解析到同一份内容（内容寻址），要么找不到——找不到就报「检查期间版本变过，请重试」。**绝不会出现清单与字节分属两个摘要。**
- **清单只借 `files`**：归档里没有 `isBinary`（服务端 `ManifestEntry.java:9-11` 不写进 zip），也没有「哪些条目算内容」的判别——历史版本可能在 `.skillmaster/metadata.json` 放真实文件。以 `files` 的清单为准、只取清单内的路径，sidecar 与二进制一起排除。
- **在内存里解**：`zip.NewReader` + 现有 `maxArchiveBytes` 上限（`download.go:27-30`）。这是新写的读法——现有解包是落盘的（`download.go:82-98,218-257` 用 `zip.OpenReader` 走临时文件），不能直接复用。
- **能跑的范围** = 归档能导出的版本：线上 / 历史 `browse`+、草稿 `write`+、已下架 `maintain`+；**已丢弃拒绝**——`files` 会成功，拒绝来自归档（`ArchiveSkillVersionUseCase.java:66-69`），所以报的是归档的 `invalid_version_state`，不要翻成 `files` 的 404。与 grep 的不对称是有意的：check 要整版的字节（导出），grep 只读内容。调用档（`invoke`）跑不了。
- **裸地址与其他读命令同一条规则**：解析出的是上次 `invoke` 钉住的版本，没有钉住才是作者面默认；要别的版本就写 `@<版本码>`。检查的是哪一版，注记打在 stderr（与 `cat` / `wc` 同档）——成功时 stdout 为空，不写这一行就会让人以为查的是当前线上。
- 检查项：文本文件里的相对引用都能在本版内解析——markdown 链接 `](path)`、反引号路径 `` `path` ``、`skill read/cat <地址> <路径>` 里的路径。只解析相对路径；不检查外部 URL；不检查语义。
- 输出 `path:line: 问题`；有问题退出 1、干净退出 0；帮助页写明「哪些算引用」与「不查什么」（frontmatter 由服务端在写入时校验）。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | `help auth` 主题（S1） | CLI |
| 2 | `help workflow` 主题（S2） | CLI |
| 3 | 401 下一步与运行时对齐（S3），含声明/运行时一致性测试 | CLI |
| 4 | 凭据失败路径的下一步（S4） | CLI |
| 5 | 命令级修补（S5） | CLI |
| 6 | 输出契约写进帮助（S6） | CLI |
| 7 | 规则段只指路 + 预算与参数禁令（S7） | 平台技能内容 |
| 8 | **`grep` 对任意可读版本生效**（S8） | 服务端 + CLI |
| 9 | **新增 `skill check <地址>`**（S9） | CLI |
| 10 | 同步 `skillmaster-cli/README.md` 的帮助一节 | 仓库 |
| 11 | 落一条 ADR：**系统提示词 = 触发与路由，CLI help = 参考与流程**（建议编号 0097） | ADR |
| 12 | 落一条 ADR：**grep 对任意可读版本生效**，取代 ADR 0062 的「仅草稿」范围（建议编号 0098） | ADR |

**顺带做（与上面同页、同段的 MEDIUM）**：A7、B6、C3、C5、F2、F3。

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 新增官方技能（如 `skillmaster/authoring`） | 引导只有两层就够；多一个技能就多一个入口（用户 2026-10-27 明确） | 若某类流程长到 help 一页放不下 |
| 2 | 把多步流程写进规则段 | 规则段是每次会话的上下文税；写进去必然与 help 漂，而且改了不传播 | 不做 |
| 3 | 自动改写用户的指令文件 | 那是用户的文件；CLI 只在旅程里、经人同意才改 | 不做 |
| 4 | 改命令的正常调用路径与输出形状 | **例外是 S8/S9**：`grep` 的状态规则放宽到任意可读版本，新增 `skill check`；其余命令的正常路径与输出形状不改——它们仅有的改动是帮助文本、两处运行时提示与 `@new` 的一行提示 | 其他输出形状若要改（如给 `files` 加 `--json`），单独开一版 |
| 4a | 全库内容搜索 / 「谁引用了这个 skill」（`skills references`） | 要服务端扫调用者可读的所有版本，权限、成本、分页都要另设计 | 下一版，单独评估 |
| 5 | 让 agent 上线 / 回滚 / 改可见性 | 高风险，只在控制台（[ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md)） | 不做 |
| 6 | 规则段的版本标记与自动检测 | 本版先解决帮助的可发现性与正确性；标记只解决「知道旧了」，不解决「怎么更新」 | help 收口之后单独评估 |
| 7 | F5 等 LOW 级措辞 | 不影响旅程 | 下一次顺手 |

## 控制台操作与 CLI 对应

本版**不新增**控制台操作。在线编辑的终点是草稿，上线仍由人在网页点；CLI 的对应是既有的：

| # | 控制台操作 | CLI 命令 | 备注 |
|---|---|---|---|
| 1 | 上线一版草稿 | `skillmaster version open <地址>@<草稿码> --sub actions` | 高风险，只在网页执行（ADR 0061） |

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | **凭据两条路可发现**：`login`、`startup`、`help env`、`help auth` 四页都能看到「无浏览器时到控制台签 `skm_…`、放进 `SKILLMASTER_API_KEY`、用 `auth status` 验证」；并用一把真实 key 跑 `auth status`，输出含续期/撤销地址 | 实跑五处，逐字检查关键词；T1 |
| 2 | **无头环境快速失败**：在无 `DISPLAY`、无凭据、无 key 的环境跑 `skill files`，输出含「login」与 `SKILLMASTER_API_KEY`，不启动 5 分钟等待；六条核心读命令的帮助写明「无凭据会尝试登录 / 哪些支持 `--no-login`」 | 实跑 + 六页帮助；T2 |
| 3 | **凭据保存失败给下一步**：`saveAndReport` 的失败句含「改用 `SKILLMASTER_API_KEY`」或「配置目录写权限」 | 用一个只读配置目录跑 `login`；T3 |
| 4 | **401 下一步按凭据形态**：6 条命令的帮助同时给「重新登录」与「API key 到控制台续期或新签」；测试比对声明与 `refusalMessage` | 读帮助 + 跑测试；T4 |
| 5 | **在线编辑可发现并走通**：`help workflow` 存在且 ≤ 512 B；只读 help 的 agent 走完 read → write @new → edit → diff → open，录下全部命令与退出码，序列不含 `download` + `submit`；`write`/`edit` 的成功信息与草稿码说明在 stderr，`@new` 后打印下一条命令 | 实跑两页 + 录序列；T5 |
| 6 | **`@new` 与 `--from` 正确**：`write/edit/rm/mv` 的用法行含 `@new`，并把 `--name` 标为 `@new` 必填；`cp` 不再教 `@new`；`--from` 显示为版本、例子传版本；实跑 `--from <版本>` 成功、`<路径>` 报错 | 逐页 + 实跑；T6 |
| 7 | **输出契约与帮助一致**：`read` 写明带行号与页脚（stdout）、不能直接写回；`cat` 写明原始字节与二进制；`invoke` 写明追加说明；`files/versions/search` 写明实际形状；写明「何时用哪个读命令」 | 实跑输出 vs 帮助；T7 |
| 8 | **版本规则写清**：`read` / `invoke` / `cat` / `files` 各有一句「裸地址 = 上次 invoke 钉住的；显式 `@版本` 才按它」；两组命令的差异写明；版本注记的输出流写明 | 读帮助；T8 |
| 9 | **创作两条路径互指**：`submit` 指到在线改与 `version open`，说明裸名字的目录，首例带 `--name`；`write`/`edit` 指到 `submit`、`version diff`、`version open`；`download` 注明快照；`skills pending` 说明只列可上线的，交接句为 `version open …@<草稿码> --sub actions`；`version publish --help` 的拒绝说明写明退出 1 不是失败、上线用 `version open --sub actions` | 读帮助；T9 |
| 10 | **共享档位可判**：`share` / `request` / `shares` 都能看到五档含义与默认 `invoke`；「让他管理」能映射到 `admin` | 读帮助；T10 |
| 11 | **主题预算与单一权威**：`help auth` 与 `help workflow` 各 ≤ 512 B；在线编辑流程只在 `help workflow` 出现一次（grep 全部帮助页，含新增两页，与规则段）；规则段不含流程 | `wc -c` + 全量 grep；T11 |
| 12 | **v16 契约不回退 + 文档自检**：每条帮助仍满足八项、≤ 2 KB、无 ANSI、退出码契约不变；`check_docs.py` 退出 0 | 既有测试 + 校验器；T12 |
| 13 | **`grep` 对任意可读版本生效**：线上 / 历史可搜（`browse`+）、草稿可搜（`write`+）、已下架与已丢弃可搜（`maintain`+，与 `files` / `wc` 同一条规则）；输出、上限、flag 与今天一致；低于 `browse` 的档位 403、行级不可见 404（与作者面其他读一致）；`grep --help` 与 `grepRefusal` 写明这条状态规则、示例用线上地址 | 三种可读档位（browse / write / maintain）× 五种版本（线上走裸地址；历史 / 草稿 / 已下架 / 已丢弃带 `@<版本码>`），另加一条 `invoke` 403；T13 |
| 14 | **`skill check` 抓断链，并守住范围与快照**：`skill check --help` 写明「哪些算引用」与「不查什么」；断链夹具（markdown 链接 / 反引号路径 / `skill read…` / `skill cat…` 各一条）报 `path:line: 问题` 并退出 1；干净夹具退出 0；外部 URL 与二进制不误报；`invoke` 档被拒；已丢弃用 `maintain` 档 + `@<版本码>` 指向——`files` 成功、归档报 `invalid_version_state`（不是 404）；检查期间版本变过：对**一个草稿**（线上 / 历史 / 已下架 / 已丢弃都不可改写，不参与这条）先 `files` 取摘要，写一次**内容不同、且不与同 skill 其他版本撞摘要**的改动，再用旧摘要跑——旧摘要已没有版本行指向，归档 404，报「版本变过，请重试」，不退回去用新内容 | 两份内容夹具（干净 / 断链）× 四种版本状态（线上 / 历史 = `browse`，草稿 = `write`，已下架 = `maintain`；后三者带 `@<版本码>`），另加 help 页、`invoke` 403、已丢弃、过期摘要四条；T14 |

**成立条件**：验收 1、2、3、6、13、14 是「不满足就不算交付」——1 对应 A1，2 对应 A2/A4，3 对应 A3，6 对应 D1/D3，13 对应 D9，14 对应 D10。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| `help auth` 长度 | 输出字节数 | `skillmaster help auth \| wc -c` | ≤ 512 B |
| `help workflow` 长度 | 输出字节数 | `skillmaster help workflow \| wc -c` | ≤ 512 B |
| 规则段长度 | 正文字符数，不含起止标记 | 取原文计数 | ≤ 300 字、≤ 3 行 |

## 依赖与前置

- **外部依赖**：无第三方服务；规则段改动需要上线 `skillmaster/startup` 的新版本（控制台，技能主人点）。
- **内部前提**：
  - 需新开一条 ADR：**系统提示词 = 触发与路由，CLI help = 参考与流程**（建议 0097）；
  - 需新开一条 ADR：**grep 对任意可读版本生效**，取代 [ADR 0062](../../decisions/0062-grep-is-a-scoped-scan-not-an-index.md) 里「仅草稿」那一条范围（扫描方式与不建索引的结论保留，建议 0098）；
  - [ADR 0096](../../decisions/0096-guidance-lives-on-the-platform.md)（规则段是平台技能内容）；
  - [ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md)（上线只在控制台）；
  - v16 的帮助契约（八项、≤ 2 KB、退出码）。
- **需要什么前置**：服务端改 `grep` 的状态规则（S8，无数据库迁移）；`skill check` 是纯客户端（S9）。
- **排除了什么前置**：不需要新技能；不需要数据库迁移；不需要新的外部服务。
