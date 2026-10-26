# v22-cli-help-and-prompt · 技术设计

> **最后更新**：2026-10-27
> **状态**：设计稿（PRD 已锁定 2026-10-27）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

## 架构

引导分两层，两层的读者与变化频率不同：

- **规则段**：平台技能 `skillmaster/startup` 的 `steps/03-prompt.md` 里的那一段，由旅程第三步粘进用户的用户级指令文件。只在「什么时候该用 SkillMaster」变化时改。
- **CLI help**：随二进制发布，回答命令与流程。只在命令面变化时改。

两者之间只有一条单向引用：规则段末尾的「更多命令见 `skillmaster help`」。

本版触及三个组件：

| 组件 | 改什么 | 有无行为变更 |
|---|---|---|
| `skillmaster-cli` | 声明（help）、运行时文案、新增 `skill check`、`grep` 的文案 | 有：新增一条命令 |
| `skillmaster-server` | `grep` 的状态规则（两道门） | 有：可搜的版本变多 |
| 平台技能 `skillmaster/startup` | `steps/03-prompt.md` 的规则段 | 无（内容变短） |

数据流不变：agent → CLI → 作者面 / 消费面 API。本版没有新服务、没有新存储。

## 数据模型

**无表结构变化，无迁移。**

沿用而不改的既有实体与规则：

- `skill_version(id, skill_id, state, version, digest, …)`——行 id（8 位短码或 26 位 ULID）、版本名、内容摘要三个身份都在这里（[ADR 0033](../../decisions/0033-version-names-are-semver.md)、[ADR 0063](../../decisions/0063-a-draft-is-addressed-by-a-short-code.md)、[ADR 0005](../../decisions/0005-content-addressing.md)）。
- 作者的可见状态集 `visibleStates(access)`（`SkillVersionService.java:1180-1189`）：`maintain+` → draft / published / revoked / discarded；`write+` → draft / published；其余 → published。
- 归档（zip）的内容与形状不变（[ADR 0068](../../decisions/0068-export-is-a-one-shot-snapshot.md)）。

`grep` 改的是「哪一行可读」，不是「行里有什么」，所以没有字段要加。

## 接口

### 写作约束：help 是上下文税

每一条帮助文本都会进入用户的 agent 上下文，**多一个字都是每次会话都要付的钱**。本版所有 help 改动按这五条写：

1. **一句一个事实。** 一句话说明两件事就拆开，或者删掉较弱的那件。
2. **示例能表达的，正文不再说。** 正文只补示例看不出、又必须知道的事（默认值、副作用、落在哪个流）。
3. **不写框架词。** 「你可以」「请注意」「以下」「总之」一律删；能省的主语全省。
4. **失败给动作，不给解释。** 「运行 `skillmaster login`」而不是「你需要先完成身份验证才能继续」。
5. **不复述参数名。** 用法行已经列了参数，正文不再逐个解释它叫什么。

判断标准是**删掉之后会不会做错**：不会做错就删。新增的两个 topic 各 ≤ 512 B（`internal/help/types.go:101` 的既有约定），其余帮助页仍受 v16 的「八项 + ≤ 2 KB」约束。

### 服务端：`grep` 的状态规则

路由不变：`GET /skills/{ns}/{name}/versions/{pin}/grep`（`AuthorSkillRoutes.ONE_VERSION + "/grep"`，`AuthorSkillController.java:392`）。

| 项 | 今天 | 本版 |
|---|---|---|
| pin 形状 | 只接受版本行 id；版本名 / 摘要直接 `grep_requires_draft`（`grepRowIdOf`，`:441-449`） | 与 `wc` 同一个 `pinOf`（`:595-598`）：版本名、摘要、行 id 都接受 |
| 权限底 | `write`+（`openDraftForRead`，`SkillVersionService.java:131`） | `browse`+（`authorSnapshotAtLeast(..., BROWSE)`，`:1054-1067`） |
| 状态集 | 必须 `draft`，否则 409 `grep_requires_draft`（`:144-149`） | `visibleStates(access)`：草稿 `write`+、线上 / 历史 `browse`+、已下架 / 已丢弃 `maintain`+ |
| 低于 `browse` | 403 | 403（不变，与作者面其他读一致） |
| 行级不可见 | 404 | 404（不变） |
| 其余 | pattern 校验、`-F/-i/-n/-l/--path`、`limit` / `MAX_LIMIT`、RE2 与 KMP、二进制过滤 | 全部不变 |

实现落点：

- `AuthorSkillController.grep`（`:405-412`）把 `grepRowIdOf(version)` 换成 `pinOf(version)`，传给用例的 `String rowId` 改成 `VersionPin`。
- `GrepDraftUseCase.grep`（`:105-110`）改名为 `GrepVersionUseCase`（它扫的是版本，不是草稿）；`versions.openDraftForRead(access, rowId)`（`:138`）换成 `authorSnapshotAtLeast(access, pin, BROWSE)`；`Manifest manifest = versions.filesOf(draft)` 的变量名同步。
- `openDraftForRead` 若别无调用者则删除；还有调用者就保留原样（**实现时先查引用，再决定删不删**）。
- `ErrorCode.GREP_REQUIRES_DRAFT` 保留常量（线上错误码不复用），但 grep 不再产生它。

**CLI 侧不改请求**：显式 `@版本` / `@摘要` 已原样进 URL（`internal/api/author.go:500-517`），裸地址也已被 `resolve` 钉成具体 pin（`internal/api` 的 `authorVersionPath`，`author.go:624-632`）。要改的只有用户可见文案：`grep --help` 的 Summary / Output / 示例，以及 `grepRefusal`（`cmd/skillmaster/files.go:542-546`）。**保留 `grep_requires_draft` 的分支**（新 CLI 可能短暂连到旧服务端），但句子改成描述服务端规则，不再说「只在草稿里搜」。

### CLI：新增 `skill check`

```
skillmaster skill check <地址>          # 退出 0 = 全部成立；1 = 有断链；2 = 用法/凭据错误
```

| 项 | 约定 |
|---|---|
| 目标 | 归档能导出的一个版本：线上 / 历史 `browse`+、草稿 `write`+、已下架 `maintain`+；已丢弃拒（`invalid_version_state`）；`invoke` 档 403 |
| 寻址 | 与其他读命令同一条规则：裸地址 = 上次 `invoke` 钉住的版本，没有钉住才是作者面默认；要别的版本写 `@<版本码>`。检查的是哪一版，注记打 stderr（`printNote`），因为成功时 stdout 为空 |
| 请求 1 | `AuthorFileList`（`internal/api/author.go:364-384`）：relpath、`isBinary`、ETag |
| 请求 2 | 归档（`StreamArchive`，`internal/api/archive.go:33`），pin = 请求 1 的 ETag **剥外层引号后**（`strings.Trim(etag, "\"")`，保留 `sha256:` 前缀；`FileStreams.java:45-47` 是生成方，`download.go:119` 是现成的剥法，`SkillAddress.java:36,101-107` 是服务端认的形状）。今天是强 ETag；将来出现 `W/` 先剥它 |
| 快照 | 两个请求由同一摘要绑定。第二次 404 → 报「检查期间版本变过，请重试」，不退回用新内容 |
| 解包 | 内存 `zip.NewReader`（`bytes.Reader` 满足 `ReaderAt`），上限复用 `maxArchiveBytes`（`download.go:27-30`）；**不落盘**——现有 `unpack` 是落盘的（`download.go:82-98,218-257`），不直接复用 |
| 清单口径 | 以请求 1 的清单为准，归档里只取清单内的路径（排除 `.skillmaster/metadata.json` sidecar 与二进制） |
| 检查项 | 三类引用：markdown 链接（目标可带标题，也可写成 `<带 空格>` 或含平衡括号，手写扫描而非正则）、反引号里的路径、`skill read/cat <地址> <路径>…` 的**全部**路径。`#锚点` 只校验文件部分；尾部标点先剥；markdown 链接按所在文件目录解析，命令形式按版本根（两处都试）；指向目录的链接按「该目录下有文件」判定 |
| 不把什么当引用 | **命令只在文档说明它是命令的地方解析**：围栏内，或 `$ ` 提示符行；行内引用则是代码跨。裸行以命令开头不算——散文里常出现这种写法。围栏按 CommonMark 闭合（同字符、不短于开启），围栏与代码跨里的 markdown 语法一律是示例；含 `[` `]` 的候选不是路径；命令行在 `-` flag 与 shell 元字符（`| & ; < > #`、`&&`、`||`）处终止，`cat … \| grep` 不会把管道当路径 |
| 代码跨的锚点 | **首段必须是本版已有的条目**才当引用：`references/gone.md` 里 `references/` 存在才查；`AGENTS.override.md`、`.bak`、`Users/me/…`、`skillmaster/startup` 这类散文里关于别处的写法一律跳过。markdown 链接与 `skill read/cat` 的参数不受这条限制——它们本身就声明「这是一个文件」。（2026-10-27 线上实测 `skillmaster/startup@1.2.0`：改前 5 处报告全是这类误报） |
| 不查 | frontmatter、根 `SKILL.md` 的存在性——写入路径已由 `SkillUploadValidator.java:35,77-86` 用真 YAML 解析器校验；CLI 刻意不解析 frontmatter（`internal/api/client.go:467-469`） |
| 输出 | `path:line: 问题`，一行一条，stdout；无问题不打印 |
| 帮助页 | 八项齐全；Output 段写输出形状与退出码；写明「哪些算引用」与「不查什么」 |

**这一条命令要新增的代码**（不新增依赖）：`cmd/skillmaster/check.go`（命令与检查）、`internal/api/archive.go` 增加一个只在内存里读归档的函数、`internal/api/author.go` 已有 `AuthorFileList` 直接复用。

### CLI：其余命令面

| 接口 | 变化 |
|---|---|
| `skillmaster help auth` | 新增 topic（≤ 512 B）：两种凭据、签发地址 `/account/keys`、`skm_` 前缀、用 `auth status` 验证、在哪撤销/续期、`logout` 只清本机 |
| `skillmaster help workflow` | 新增 topic（≤ 512 B）：消费 / 本地创作 / 在线创作三条主流程，每条只写「第一条命令 → 关键分叉 → 收尾命令」 |
| 顶层 `help` | 增加一行 `主流程：skillmaster help workflow` |
| 401 类失败的下一步 | 6 条命令自己的 `unauthenticated` 行同时给「重新登录」与「API key 到控制台续期或新签」；共享行保持。**去重规则决定必须改这 6 处**：命令自己声明了 `unauthenticated` 就会顶掉共享行（`internal/help/help.go:34-43`） |
| `clientFor` 的无浏览器分支 | 错误句补 `SKILLMASTER_API_KEY` 与 `help auth`（`main.go:1081-1092`） |
| `saveAndReport` 的保存失败 | 补下一步：改用 key，或给配置目录写权限（`main.go:838-839`） |
| `skill write/edit/rm/mv` | 用法行加 `@new`；`--from` 改为版本；`--name` 标为 `@new` 必填；例子改版本 |
| `skill cp` | 删除 `@new` 例子与 `--from/--name/--note` 三个死 flag |
| `@new` 成功后的 stderr | 增加下一条命令（含草稿码）：`version diff` / `version open --sub actions` |
| `read` / `cat` / `invoke` / `files` / `versions` / `search` | 写明实际输出形状（行号、页脚、说明行、二进制）与「何时用哪个」 |
| 版本解析 | 写明裸地址 = 上次 `invoke` 钉住的；两组命令在无 live 时的差异；版本注记的输出流（`read`/`files` 走 stdout，`cat`/`wc`/`grep` 走 stderr） |
| `submit` / `write` / `edit` / `download` | 互相指路；`submit` 说明裸名字按 `SKILLMASTER_SKILLS_DIR` 解析、审批地址落在 skill 总页 |
| `skills pending` | 说明只列可上线的；交接句 `version open …@<草稿码> --sub actions` |
| `share` / `request` / `shares` | `share` 写全五档含义与默认 `invoke` 并指到协作者管理页；`request` / `shares` 写「档位含义同 share」（一个事实一处写全，另两处指路） |
| `version publish` | 拒绝说明改成「不是失败，交接用 `version open --sub actions`」 |
| `login` / `startup` / `help env` | 补 key 路径、签发地址、`skm_` 前缀、验证方式；`auth status` 的 key 分支补续期/撤销地址 |
| `invoke/read/cat/files/grep/wc` 的帮助 | 补「无凭据会尝试浏览器登录；支持的命令用 `--no-login`，不支持的会等你登录」 |
| `auth status` / `skills pending` / `version release-type` | 补可选地址；去掉 ADR 编号（含版本页专用动词的运行时文案） |

### 平台技能：规则段

- 位置：`skillmaster/startup` 的 `steps/03-prompt.md`，`<!-- skillmaster:begin -->` 与 `end` 之间。
- 预算：≤ 3 行 / ≤ 300 字，按「三个问题」判——**何时用**、**第一步**、**去哪查**；判据见 PRD §指标。
- 参数禁令：不出现命令参数名；`--scope public` 是唯一例外（它改变的是「问的是哪一类东西」）。
- 末尾指路：`skillmaster help workflow`（流程）与 `skillmaster help auth`（凭据），再一句 `skillmaster help`。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 帮助与运行时文案（S1–S7）；声明/运行时一致性测试；`skillmaster-cli/README.md` 同步；ADR 0097 | 1–12 |
| P1 | `grep` 状态规则（S8）：服务端两道门 + CLI 文案；ADR 0098 | 13 |
| P2 | `skill check`（S9） | 14 |

顺序理由：P0 不动行为、可先合；P1 只改服务端判断；P2 是纯客户端新增，与前两期互不依赖。三期各自可独立回滚。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 流程拆成一页还是两页（`help auth` + `help workflow`）？ | **已定**：两页。**已试写并量过**：`help auth` 462 B、`help workflow` 508 B，都装得下 512 B（`internal/help/types.go:101`） | 内容再增长就拆第三页，或单独评估放宽上限 |
| Q2 | 规则段的版本标记格式？ | **本版不做**（PRD §不做 6）；若以后做，倾向在 `<!-- skillmaster:begin -->` 上带 `v1`，第四步比对时不一致就报「粘的是旧版」 | 留到 help 收口后单独评估 |
| Q3 | 规则段的预算按字节还是按行？ | **已定**：按「三个问题」判，同时保留 ≤ 3 行 / ≤ 300 字（PRD §指标） | 不再开放 |
| Q4 | 401 的静态下一步怎么与运行时保持一致？ | **已定**：声明写两条路；加一条测试比对声明文本与 `refusalMessage` 的关键词。必须改 6 处声明而不是共享行（`internal/help/help.go:34-43` 的去重） | 测试写出后再定具体断言词 |
| Q5 | 输出形状与帮助不符时，改帮助还是改输出？ | **已定**：改帮助（v16 的「正常调用不改」） | 要加 `--json` 时单独开一版 |
| Q6 | `skill check` 里「什么算引用」？ | **已定**：markdown 链接、反引号路径、`skill read/cat` 的路径；只解析相对路径；`/#` 锚点只校验文件部分 | 误报多再收窄 |
| Q7 | 已发布版本的 grep 服务端怎么寻址？ | **已定**：沿用作者面 pin，两道门一起改；已丢弃跟随 `visibleStates`（`maintain`+ 可搜），`download` 的拒绝是导出规则 | 实现后核对：`grep` / `files` / `wc` 三处只剩 `visibleStates` 一条规则 |
