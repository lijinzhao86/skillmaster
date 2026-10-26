# v22-cli-help-and-prompt · 技术设计

> **最后更新**：2026-10-27
> **状态**：设计稿（**待 PRD 锁定后填写**）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

## 架构

引导分两层，两层的读者与变化频率不同：

- **规则段**：平台技能 `skillmaster/startup` 的 `steps/03-prompt.md` 里的那一段，由旅程第三步粘进用户的用户级指令文件。只在「什么时候该用 SkillMaster」变化时改。
- **CLI help**：随二进制发布，回答命令与流程。只在命令面变化时改。

两者之间只有一条单向引用：规则段末尾的「更多命令见 `skillmaster help`」。

（本版只新增一层 help 内容与一条 `@new` 提示，不改变两层的位置。）

## 数据模型

本版不涉及数据模型。

## 接口

| 接口 | 变化 |
|---|---|
| `skillmaster help auth` | 新增 topic（≤ 512 B）：两种凭据、签发地址、`skm_` 前缀、验证方式、在哪撤销/续期、`logout` 只清本机 |
| `skillmaster help workflow` | 新增 topic（≤ 512 B）：消费、本地创作、在线创作三条主流程 |
| 顶层 `help` | 增加一行 `主流程：skillmaster help workflow` |
| 401 类失败的下一步 | 6 条命令自己的 `unauthenticated` 行改成同时给「重新登录」与「API key 到控制台续期或新签」；共享行保持 |
| `clientFor` 的无浏览器分支 | 错误句补 `SKILLMASTER_API_KEY` 与 `help auth` |
| `saveAndReport` 的保存失败 | 错误句补下一步（改用 key / 给配置目录写权限） |
| `skill write/edit/rm/mv` | 用法行加 `@new`；`--from` 改为版本；例子改版本 |
| `skill cp` | 删除 `@new` 例子与 `--from/--name/--note` 三个死 flag |
| `@new` 成功后的 stderr | 增加下一条命令（含草稿码） |
| `read` / `cat` / `invoke` / `files` / `versions` / `search` | 帮助写明实际输出形状（行号、页脚、说明行、二进制）与「何时用哪个」 |
| 版本解析 | 帮助写明裸地址 = 上次 `invoke` 钉住的；两组命令在无 live 时的差异；版本注记的输出流 |
| `submit` / `write` / `edit` / `download` | 互相指路（本地创作 ↔ 在线创作 ↔ 快照往返）；`submit` 说明裸名字按 `SKILLMASTER_SKILLS_DIR` 解析、审批地址落在 skill 总页 |
| `skills pending` | 说明只列可上线的；交接句改成 `version open …@<草稿码> --sub actions` |
| `share` / `request` / `shares` | 五档含义 + 默认 `invoke`；`share` 指到协作者管理页 |
| `skill grep` 的帮助 | 写明状态规则（哪些版本可搜、权限底）、示例用线上地址；`grepRefusal` 里「只搜草稿」的说法同步改 |
| `skill submit` | 首例加 `--name`；说明裸名字按 `SKILLMASTER_SKILLS_DIR` 解析；审批地址落在 skill 总页 |
| `skill write/edit/rm/mv` 的 `--name` | 标为 `@new` 必填 |
| `version publish` | 拒绝说明改成「不是失败，交接用 `version open --sub actions`」 |
| `login` / `startup` / `help env` | 补 key 路径、签发地址、`skm_` 前缀、验证方式；`auth status` 的 key 分支补续期/撤销地址 |
| `invoke/read/cat/files/grep/wc` 的帮助 | 补「无凭据会尝试浏览器登录；无人值守用 `--no-login`（若支持）」 |
| `auth status` / `skills pending` / `version release-type` | 补可选地址；去掉 ADR 编号（含版本页专用动词的运行时文案） |
| `GET /skills/{ns}/{name}/versions/{pin}/grep` | 两道门一起改：控制器 `grepRowIdOf`（`AuthorSkillController.java:441-449`）换成与 `wc` 同一个 `pinOf`；状态门 `openDraftForRead`（`SkillVersionService.java:131-149`）换成 `authorSnapshotAtLeast(..., BROWSE)`（`:1054-1067`，同时给出 `browse`+ 底与 `visibleStates`）。低于 `browse` 仍 403、行级不可见 404。输出、上限、flag、pattern 校验不变。用例改名 `GrepVersionUseCase` |
| `skill check <地址>` | 新增命令（客户端实现），只查相对引用（frontmatter 与根 SKILL.md 由服务端写入时校验，CLI 不解析）。两次请求：`files` 给清单、`isBinary` 与 ETag；**ETag 必须先 `strings.Trim(etag, "\"")` 去掉外层引号、保留 `sha256:` 前缀**再作归档的 pin——`FileStreams.etag` 发的是 `"sha256:<hex>"`（`FileStreams.java:45-47`，`download.go:119` 是现成的剥法），而 `SkillAddress.pinOf` 只认以 `sha256:` 开头的后缀，带引号或裸 hex 都会 404（`SkillAddress.java:36,101-107`）。今天是强 ETag（无 `W/`）；将来若出现弱 ETag，要先剥 `W/` 再剥引号。同一摘要让两次请求落在同一快照；找不到就报「检查期间版本变过，请重试」。内存 `zip.NewReader`，上限复用 `maxArchiveBytes`。**非当前版本必须显式 `@<版本码>`**；帮助页写明「哪些算引用」与「不查什么」；`invoke` 档被拒；已丢弃用 `maintain` 档时 `files` 成功、拒绝来自归档（`invalid_version_state`） |

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | S1–S9：两个 topic、命令级修补、输出契约、规则段预算、grep 状态规则、`skill check`；声明/运行时一致性测试；`skillmaster-cli/README.md` 同步；ADR 0097、ADR 0098 | 验收 1–14 |

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 流程拆成一页还是两页（`help auth` + `help workflow`）？ | 两页：凭据与流程是两类问题；现有 `Topic.Body` 的约定是 ≤ 512 B（`internal/help/types.go:101`）。**已试写并量过**：`help auth` 462 B、`help workflow` 508 B，装得下 | 已定：维持两页；内容再增长就拆第三页或单独评估放宽上限 |
| Q2 | 规则段的版本标记格式？ | **本版不做**（PRD §不做 6）；若以后做，倾向在 `<!-- skillmaster:begin -->` 上带 `v1`，第四步比对时不一致就报「粘的是旧版」 | 留到 help 收口后单独评估 |
| Q3 | 规则段的预算按字节还是按行？ | **已定**：按「三个问题」判（何时用、第一步、去哪查），同时保留 ≤ 3 行 / ≤ 300 字的上限（PRD §指标） | 不再开放 |
| Q4 | 401 的静态下一步怎么与运行时保持一致？ | 声明里写两条路；加一条测试比对声明文本与 `refusalMessage` 的关键词。**注意去重规则**：命令自己声明了 `unauthenticated` 就会顶掉共享行（`internal/help/help.go:34-43`），所以必须改 6 处声明，不能只改共享行 | 写出测试再定具体断言 |
| Q5 | 输出形状与帮助不符时，改帮助还是改输出？ | **改帮助**：`read` 的行号、`files`/`versions`/`search` 的多行、`invoke` 的页脚都是既有契约（v16 的「正常调用不改」）；只在帮助里写清，并在 `read` 页警告不能直接写回 | 若之后要给这些命令加 `--json`，单独开一版 |
| Q6 | `skill check` 里「什么算引用」？ | 三条：markdown 链接 `](path)`、反引号里的路径 `` `path` ``、`skill read/cat <地址> <路径>` 里的路径。只解析相对路径，不解析 URL；`/#` 锚点只校验文件部分 | 先按这三条写夹具，误报多再收窄 |
| Q7 | 已发布版本的 grep 服务端怎么寻址？ | **已定**：沿用作者面路由的 pin。**两道门都要改**——只改 `grepRowIdOf` 会让非草稿的行 id 撞上 `openDraftForRead` 的 `write`+ 与 draft 检查。CLI 侧不用改请求：显式 `@版本`/`@摘要` 已原样进 URL（`internal/api/author.go:500-517`），裸地址也已被 `resolve` 钉成具体 pin。**已丢弃不新造例外**：跟随 `visibleStates`（`maintain`+ 可搜），`download` 的拒绝是导出规则 | 实现后核对：`grep` / `files` / `wc` 三处只剩 `visibleStates` 一条规则；`grepRefusal` 与帮助里的「只搜草稿」已删干净 |
