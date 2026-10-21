# v10-cli-lifecycle · 产品需求

> **最后更新**：2026-10-21
> **所属版本**：[`README.md`](README.md)

<!--
这是本版需求的权威文档。README 只放摘要，别处不要重复这里的内容。
-->

## 用户与场景

**谁在用**：

- **agent**：手里只有一份凭据（`skillmaster login` 换来的登录态，或一把 API key），被要求把 skill 的生命周期走完。
- **在本机敲 CLI 的人**：写脚本、做批处理，或只是不想为一个动作打开浏览器。

**在什么场景下用**：

- **新建 skill**：agent 从零开始，手上还没有任何文件，想先有一个 skill，再逐个 `write`。今天它必须先在本地造一个目录、写一份 `SKILL.md`、再 `submit`——`skill submit` 确实能建出 skill（`SubmitSkillUseCase.java:104` 的 `findOrCreate`），但它要的是「一整包已经存在的内容」，不是「空白起步」。网页的「在线编辑」有这条路（`CreateSkillUseCase`），CLI 没有。
- **基于某一版改一版**：agent 想改 `SKILL.md` 的一处，然后提交新版本。今天它只能 `skill download` 整包、本地改、再 `submit`——为一个文件改动下载整个 skill。网页编辑器不必落本地副本（`CreateDraftFromVersionUseCase`），CLI 没有对等物。
- **管理共享**：有人问「这个 skill 现在共享给谁了」「把某人的访问收回」。今天 CLI 只有 `skill share`（只能授予），看一眼或收回一次都得打开网页。
- **申请访问**：agent 碰到一个自己只能调用、想写入的 skill（这需要一把带 `skills:write` 的凭据——只读密钥发不出申请，见下）。今天「申请」「撤回申请」只在网页的信息栏里（`SkillPage.vue:1205-1224`），`/api/v1` 上一条路都没有。
- **把一件事交回给人**：agent 被要求上线、回滚、下线、删除。这些只能由人在网页上确认（[ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md)），CLI 的职责是**把人送到正确的那一页**。今天所有拒绝句都指向 `version open`（`version.go:101-103`），而「下线 / 恢复服务 / 删除」在 skill 的设置页——落点错了。

**现在的替代做法是什么**：

- 新建 skill：本地造目录再 `submit`；或打开网页点「新建 skill › 在线编辑」。
- 基于某一版改：`download → 改 → submit` 是完整可用的，代价是全量下载。
- 共享：打开网页的「设置 › 协作者」。
- 申请访问：打开网页的信息栏。
- 交回给人：拒绝句给一个地址，但 skill 级的动作要人自己再找到设置页。

**这一版为什么还要做**：

目标是把「除高风险动作外不打开网页」变成真的。前两条省掉的是一次本地副本与一次整包下载；后两条是今天**完全没有**的 CLI 能力。最后一条不是新能力，是交接落点的错误——它让「交回给人」这一步多绕一次，并且会打开一个**没有那个按钮**的页面。

## 范围

**两条轴，不要混用**（这是本版最容易写错的一处）：

- **内容与生命周期轴**：上线、回滚、下架、重新上架、下线、恢复服务、删除 skill、可见性。它们改变**调用者读到什么**，判据在 [ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md) §决定 1——**只在浏览器面**。可见性本身由 [ADR 0037](../../decisions/0037-visibility-has-two-states.md) 与 [ADR 0048](../../decisions/0048-visibility-hands-out-a-rung.md) 定义，按同一条判据推导，所以也在这一轴。
- **按人授权轴**：共享与撤销共享改变**某一个人**在梯子上的档位，不改变内容本身；判据在 [ADR 0034](../../decisions/0034-skill-level-sharing.md) §决定 9 与 [ADR 0047](../../decisions/0047-permissions-are-a-ladder.md) §决定 5——**CLI 可以授予与撤销**（`管理` 档）。**申请是第三样东西**：它按 [ADR 0052](../../decisions/0052-a-request-is-not-a-grant.md) 不授予任何东西，只是把「我想要」变成一个待处理的请求。三者都不在内容轴上。

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | **新建 skill**：`skillmaster skill create <名字> --description <描述> [--name <版本名称>] [--json]`。一次事务建出 skill 与它的第一份草稿，根 `SKILL.md` 由服务端按名字与描述渲染（与网页「在线编辑」同一个用例）。**名字是一段、不带 `/` 或 `@`**（`@` 是版本分隔符，会在 `@<版本码>` 里造成歧义）——服务端只把 skill 建在调用者自己的个人命名空间（`CreateSkillUseCase.java:79`），命令里给命名空间只会造出一个第二权威。凭据所属的每个账号在注册时都有一个个人命名空间（`NamespaceService.java:45-52`），所以「个人命名空间」总是可确定。`--name` 是这一版的版本名称，省略时为「第一版」（与网页表单的预填一致，`NewSkillDialog.vue:25`），**命令会把它打印出来**——默认值可见，不静默。**线上指针不动**，新 skill 未上线。命令打印 `命名空间/名字@<版本码>`；`--json` 给 `{namespace, name, version_code, version_name, state}`。同名已存在 → 409 `skill_already_exists`；名字已被已删除的 skill 占着 → 409 `skill_deleted`，句子指向「到网页恢复」；描述为空或名字越界 → 400 `invalid_upload`，具体是哪一条在 `details[].issue`；版本名称缺失或越界 → 400 的**顶层码** `draft_name_required` / `draft_name_too_long`（`VersionLabels.java:31-40`；`create` 省略 `--name` 时填「第一版」，**显式传空是本地用法错误**——CLI 不发请求、不替人补默认值；直接调 API 省略或传空才由服务端答 `draft_name_required`）。**任何拒绝都不留下半行**。 | CLI + 作者面一条路由 |
| 2 | **第一次写入带底稿**：四个写命令接受 `@new` 作为写入目标——`skill write <ns>/<name>@new --name <版本名称> [--from <版本>] [--note <说明>] <路径>`、`skill edit …@new`、`skill rm …@new`、`skill mv …@new`（**内容命令不带 `--json`**——这是 v7 起就有的契约，它们回答的是字节与句子，不是文档）。服务端在**同一次请求**里建出草稿并应用这次改动（复用网页编辑器的用例）。**底稿由服务端解析、CLI 只读一次结果**：省略 `--from` 时，规则「线上 → 本档位可见的最新草稿 → 空白」（`CreateDraftFromVersionUseCase.resolveBase`）在服务端有一份（建草稿时用），网页编辑器另有一份自己的读序（v8 既有，`VersionEditorPage.vue:1029-1035`，本版不动）。**CLI 不复制第三份**——它先向作者面读一次被服务端解析出的底稿**引用**（版本码 + digest，**不返回内容**——这是「省掉整包下载」成立的前提），再带着它们建草稿；`--from <版本>` 用那一版，但只能是**调用者本档位看得见**的已上线版本或草稿（历史版本也算；已下架 / 已丢弃按下一句处理）——看不见的行一律 404。服务端要求 `from_digest`（`CreateDraftFromVersionUseCase.requireBaseDigest`）：**有底稿时**缺了 → 428 `if_match_required`，**空白底稿时带 digest 反而是 400**——所以 CLI 有底稿就带上、空白就两个都不带。这条命令是**读一次底稿、写一次草稿**：同一个底稿行的 digest 在两次之间变了 → 409 `digest_mismatch`；空白被竞态填上版本 → 428（服务端重新解析后发现非空却没 digest）；别的 skill 的版本码 → 404；已丢弃 / 已下架的底稿对 `写入` 不可见（`SkillVersionService.java:1066-1075`）→ 404。「同一次请求」指的是**建草稿 + 首次改动**，不是「选底稿 + 建草稿」。`--name` **必填**（[ADR 0055](../../decisions/0055-drafts-have-a-required-name.md)）：草稿的名称是作者给这一版起的短名字，CLI 不给默认值。`--note` 可选，至多 200 码点。**结果与既有版本内容相同 → 409 `content_already_exists`，零写入、不产生半个草稿**（`SkillVersionService.java:264-276`）。**底稿状态**：已下架 / 已丢弃 → `维护` 及以上拿到 400 `invalid_request`，`details[].issue=not_a_base`；`写入` 档**根本看不见这两种状态**（`SkillVersionService.java:1066-1075`），拿到的是 404。**`cp` 不接受 `@new`**：服务端建草稿的入参只有 write / delete / rename 三种改动（`CreateDraftFromVersionUseCase.apply`），`cp` 要等草稿存在。 | CLI + 作者面两条路由（读底稿、建草稿） |
| 3 | **协作者列表与撤销**：`skillmaster skill shares <ns>/<name> [--query <文字>] [--page <n>] [--json]` 列出「谁、什么档位、从什么时候」；`skillmaster skill unshare <ns>/<name> --user <用户名> [--json]` 撤销一次共享。列表按服务端的每页 10 条分页、按用户名过滤。**列表与撤销都要求 `管理` 档**（所有者当然可以；`SkillSharingService.java:113-120` 的 `permittedForAdministration`）——不够档 403，看不见 404。撤销是**幂等**的：原本就没有，也答成功；用户名不存在 → 400 `invalid_request`，`details[].issue` = `no_such_user`。**不新增服务端路由**——`GET` 与 `DELETE` 在 `SkillsController` 的 `grants` / `revoke` 两个方法上。 | CLI |
| 4 | **申请访问**：`skillmaster skill request <ns>/<name> --role <档位> [--json]` 提交申请；`skill request <ns>/<name> --status [--json]` 读自己在这一项上等着的档位；`skill request <ns>/<name> --cancel [--json]` 撤回。申请的前置由服务端判定：能看见这个 skill、档位严格高于自己当前档位、所有者不能申请（`SkillAccessService.request`）。**重复申请只有一行**：再申请一次会覆盖目标档位，不新增（`SkillAccessRequestRepository.java:31-44`）。**撤回幂等**：没有在等的申请也答成功。**申请不授予任何东西**：未审批前，对该 skill 的**内容与版本**读取结果不变（申请状态本身除外）（[ADR 0052](../../decisions/0052-a-request-is-not-a-grant.md) §决定 1）。审批与拒绝**不在本版**：批准是 [ADR 0052](../../decisions/0052-a-request-is-not-a-grant.md) §决定 3（批准即授权），待处理队列按 §决定 4 在「设置 › 协作者」——两者都是人做的判断。三条申请路由落在哪条平面、以及为什么批准留在网页，见 [ADR 0073](../../decisions/0073-asking-for-access-splits-by-plane.md)。**scope 按方法走**（`SecurityConfig.java:221-224`）：申请 POST、撤回 DELETE 要 `skills:write`；读自己的申请 GET 要 `skills:read`。所以**只读密钥申请不了访问**——这条写进帮助与 README，不静默。 | CLI + 消费面三条路由 |
| 5 | **打开网页并落到正确的页**：`skillmaster skill open <ns>/<name> [--tab info\|files\|versions\|settings] [--sub general\|collaborators\|details] [--print] [--json]`，与 `version open` 同形。地址映射：不带参数 → `/skills/{ns}/{name}`；`--tab x` → `?tab=x`；`--sub y` 只在 `--tab settings` 下有效 → `&sub=y`，其它组合是本地用法错误。**同时修正拒绝句的落点**（记在 [ADR 0072](../../decisions/0072-the-handoff-points-at-the-page-that-owns-the-action.md)）：这三个 skill 级动作只有所有者能做（`SkillPage.vue:1521` 整块是 `rung === 'owner'`），交接对象因此是所有者；版本级的 `publish` / `rollback` / `revoke` / `restore` 继续指向 `version open`；skill 级的 `offline` / `online` / `delete` 改为指向 `skill open … --tab settings`。设置页的默认子页就是「常规」（`SkillPage.vue:186-194`），所以 `--tab settings` 本身足以落到危险区。拒绝句**逐字**是：<br><br>版本级：`<verb> 会改变调用者能读到什么，只能在网页上由人确认，CLI 不执行。` / `这些动作按 ADR 0031 / 0046 / 0061 只在浏览器面存在。` / `到那一版自己的页面上去做：skillmaster version open <ns>/<name>@<版本>`<br><br>skill 级：同样的前两句，第三句是 `到这个 skill 的设置页去做：skillmaster skill open <ns>/<name> --tab settings`（三个 skill 级动词的入参都可能带 `@<版本>`，交接时一律脱掉版本）。 | CLI |
| 6 | **帮助与 README**：`skillmaster skill help` 列出新命令；CLI 的 README 补齐五块能力与两条落点规则。文案用 [`design/voice-spec.md`](../../../design/voice-spec.md) 的名词（共享 / 撤销 / 档位 / 新建 skill / 版本名称 / 说明 / 底稿 / 上线）。**「申请」此前不在名词表里**——本版沿用网页那一块已有的「申请」「撤回申请」，并把它补进 voice-spec 的名词表；同时把「新建 skill」定义里「浏览器里」那半句去掉（CLI 从本版起是第二个「从零新建」入口（`skill submit` 是整包提交，也能建出 skill））。**这两处随本版一起交付**（v10 不改网页画法，只补名词表与定义）。 | CLI 文档 |

**两条写死的口径**：

- `@new` 的 `--name` **必填**，`skill create` 的版本名称**可省略、默认「第一版」且打印**——差别不是随意的：前者是作者改一版，只有他知道这次改的是什么（[ADR 0055](../../decisions/0055-drafts-have-a-required-name.md) 的理由）；后者是产品替人起步，网页表单本来就把「第一版」摆在可见、可改的输入框里（`NewSkillDialog.vue:25`；「新建 skill 与第一份草稿是一个事务」见 [ADR 0070](../../decisions/0070-creating-a-skill-creates-its-first-draft.md)），CLI 只是照做并把值说出来。
- **不新增任何 scope，不改梯子**：新路由的 scope 完全由 `/api/v1/**` 既有规则决定（`GET/HEAD/OPTIONS` → `skills:read`，其余 → `skills:write`）。申请要 `skills:write` 是这条规则的直接结果，不是新规则。

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | **上线、回滚、下架、重新上架、下线、恢复服务、删除 skill 的命令行入口** | 它们改变调用者读到什么，判据是 [ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md) §决定 1。API 平面上有**五条字面量的 404 桩**：`publish` / `rollback` / `revoke` / `restore` 与 `delete`（`AuthorSkillController.noSuchAction`）；其中 `delete` 挂在版本路径下，而它其实是一个 skill 级动作。`offline` / `online` 两个**根本没有路由**——三种「做不到」的形状不同，本版都不改，只把拒绝句的落点改对 | 不做（结构性） |
| 2 | **批准 / 拒绝访问申请** | 待处理队列与批准按 [ADR 0052](../../decisions/0052-a-request-is-not-a-grant.md) §决定 3（批准即授权）与 §决定 4（队列在「设置 › 协作者」）里由人做的判断；本版的目标是 agent **自己**的准备动作，审批不是 | 未定（若将来管理动作也交给 CLI，要单独定档位与确认方式） |
| 3 | **待处理申请的列表（所有者视角）** | 同上：它服务的是审批，而审批在网页；补一个只读列表不解决任何 CLI 侧的动作 | 随 #2 |
| 4 | **skill 级 `title` / `description` 的修改** | 服务端今天没有这条 PATCH（v1 遗留），网页也改不了；规则（[ADR 0049](../../decisions/0049-an-edited-description-outranks-the-version.md)）属于「元数据」那一版 | 元数据那一版 |
| 5 | **允许一份内容与既有版本相同的草稿** | 会推翻内容寻址与重复检测（[ADR 0005](../../decisions/0005-content-addressing.md)）；网页编辑器也不这么做——它在第一次内容改动时才建草稿 | 不做 |
| 6 | **本地工作区 / 自动同步 / `@new` 在本地留一份底稿** | 服务端仍是唯一权威（[ADR 0001](../../decisions/0001-server-authoritative.md)）；`@new` 只是「读一次底稿、写一次草稿」，不在本机落任何东西 | 不做 |
| 7 | **`cp` 作为第一次改动** | 服务端建草稿的入参只有 write / delete / rename；为 `cp` 单独扩一条服务端改动类型是另一件事（还要想清楚源文件不在包里的情形） | 未定 |
| 8 | **申请的历史（被拒过没有）** | 服务端只有 pending 行与审计（`V20__a_request_is_not_a_grant.sql`），没有可查询的拒绝记录；新增状态机不是本版要解决的问题 | 未定 |
| 9 | **可见性（私有 / 公开）的命令行入口** | 它改变调用者读到什么，与上线同类（内容与生命周期轴） | 不做（结构性） |
| 10 | **强制空白底稿（`--from none`）** | 服务端没有这个表示法：`from` 为空表示「服务端按默认规则解析」，`?base=none` 只是网页读路由的参数（`WebSkillController.java:164-171`），`SkillAddress.pinOf` 也不接受 `none`。空白底稿本来就只在「线上与草稿都没有」时出现，服务端自己会回落 | 若将来真需要，先给作者面建草稿加一个显式入参——**不要用「省略 `--from`」冒充空白**，那会让「有线上却拿到空模板」变成一个静默的错误。注意：服务端解析出的底稿为空时（线上与草稿都没有），做 #2 与验收 #5 **仍然会建一份空白草稿**；本项只拒绝「人工强制空白」 |

## 验收与指标

### 验收标准

每条都写明**在什么条件下、观察到什么**；映射见 [`test-plan.md`](test-plan.md) §验收映射。

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | **新建 skill 一次成型**：`skill create hello --description "..."` 后，`version list` 里有它的一份草稿，草稿的根 `SKILL.md` 里 `name` / `description` 等于命令给的；stdout 含 `ns/hello@<版本码>` 与打印出来的版本名称；`--json` 的五个字段齐全（`namespace` / `name` / `version_code` / `version_name` / `state`）。**未上线要按消费面的真实形状验**：`skill list` 里**没有**它（消费面只列有线上指针的 skill，`SkillCatalogRepository.java:89-90`），`skill versions` **非零退出**并给出 404（只有草稿的 skill 在消费面上不存在，`ReadSkillUseCase.java:163-170`） | 一条 CLI 端到端用例 + 一条 IT 读三张表 |
| 2 | **新建 skill 的拒绝都具名且零写入**：同名 → 409 `skill_already_exists`；名字被已删除的 skill 占着 → 409 `skill_deleted` 且句子指向「到网页恢复」；**每种拒绝之后 `skill` / `skill_version` / `version_file` 三张表行数不变** | IT 逐个比行数 |
| 3 | **新建 skill 的字段校验**：CLI 缺 `--description` → 本地用法错误、非零退出、**不发请求**；服务端收到空描述 → 400 `invalid_upload`，`details[].issue=required_field_missing`；名字超过 64 码点 → 400 且 issue 是 `name_too_long`；名字里带 `%` 或 `;` → issue 是 `name_contains_unaddressable_char`；版本名称超过 40 码点 → 400 `draft_name_too_long`；名字里带 `/` 或 `@` → CLI 本地用法错误、请求计数 0；**每种 400 之后三张表行数同样不变** | CLI 用例断言请求计数；IT 断言码、issue 与行数 |
| 4 | **`@new` 用线上做底稿**：对一个已有线上版本的 skill 执行 `skill write …@new --name 第二版 <路径>`，服务端建出草稿，内容 = 线上 + 这次写入；命令打印新版本码；随后 `skill write …@<版本码>` 继续写成功 | 一条 IT + 一条 CLI 用例 |
| 5 | **`@new` 的底稿规则是条件式的**：有线上 → 线上；没有线上但有一份可见草稿 → 最新那份；两者都没有 → 空白（服务端渲染模板）；`--from <某个已上线版本>`（含历史版本）→ 那一版；`--from <草稿版本码>` → 那份草稿 | IT 用五份夹具逐个断言；另加一例「别的 skill 的版本码 → 404」（草稿对 `write` 及以上本来就可见，见 [ADR 0051](../../decisions/0051-the-author-plane-serves-by-tier.md) §决定 2，所以「本档位看不见的草稿」在这个入口上不成立） |
| 6 | **底稿状态的两种答案**：`--from <已下架或已丢弃>`，`维护` 与 `管理` → 400 `invalid_request`，`details[].issue=not_a_base`；`写入` 档 → 404（这两种状态对他不可见） | IT 按档位各跑一次（`写入` / `维护` / `管理`） |
| 7 | **内容相同不给草稿**：`@new` 的第一次改动让结果与既有版本 digest 相同 → 409 `content_already_exists`，不产生草稿、不改任何行 | IT 比行数与 digest |
| 8 | **基线过期**：CLI 读到的底稿 digest 与服务端不一致时，命令拿到 409 `digest_mismatch`，句子说清「这一版刚变过，重读再来」；缺 digest 的 428 `if_match_required` 常规路径是直接调 API 且省略 digest；CLI 有底稿必带、空白两个都不带，唯一能碰到它的是「读完是空白、发请求前被别人补了草稿」这个竞态（`writeRefusal` 有这条翻译），不要求专门复现 | IT 用一个过期 digest；CLI 用例断言退出码与句子 |
| 9 | **`@new` 缺版本名称**：CLI 不带 `--name` → **本地用法错误**、非零退出、请求计数 0（与 #3 的 `--description` 同一口径）；绕过 CLI 直接调作者面路由时 → 400 `draft_name_required`、不建草稿、零写入 | CLI 用例 + 一条直接调 API 的 IT |
| 10 | **`--note` 落到草稿**：`@new --note "..."` 后 `version show` 读到的说明等于它；超过 200 码点 → 400 | IT |
| 11 | **`cp` 不接受 `@new`**：命令在本地就拒绝，并说明「先建出版本，再复制文件」，请求计数为 0 | CLI 用例 |
| 12 | **协作者列表**：`skill shares` 每一行有用户名、档位、加入时间；`--query` 过滤、`--page` 翻页与服务端一致（每页 10）；所有者与 `管理` 档能看；公开 skill 上 `调用` / `浏览` / `写入` / `维护` → 403；看不见 → 404 | IT 按档位逐档 + CLI 用例 |
| 13 | **撤销共享**：撤掉一条存在的共享；再撤一次仍成功；公开 skill 上 `调用` / `浏览` / `写入` / `维护` 撤销 → 403；用户名不存在 → 400，`details[].issue=no_such_user` | IT + CLI 用例 |
| 14 | **申请的前置与幂等**：能看见才可申请（陌生人申请私有 skill → 404）；档位必须严格高于当前（等于或更低 → 400 `not_above_current_rung`）；所有者申请 → 400 `owner_has_no_higher_rung`；非五档 → 400 `not_a_rung`；重复申请只有一行，目标档位被覆盖；只读 scope 的凭据申请 → 403 | IT 与 scope 用例 |
| 15 | **申请不改变可读性**：申请之后、批准之前，用同一个凭据读该 skill 的**内容与版本**结果与申请前完全相同（`--status` 是新能力，不在比较范围）；`--status` 能读到在等的档位，`--cancel` 之后读到「没有在等的申请」，反复撤回都成功 | IT 前后对比 + CLI 用例 |
| 16 | **打开网页**：`skill open` 打印 `/skills/{ns}/{name}`；`--tab x` → `?tab=x`；`--tab settings --sub collaborators` → 两个参数都在；`--sub` 不配 `--tab settings` → 本地用法错误；`--print` 不打开浏览器；`--json` 给 `{url, opened}` | CLI 用例逐条断言 |
| 17 | **拒绝句逐字正确**：`publish` / `rollback` / `revoke` / `restore` 的第三句是 `到那一版自己的页面上去做：skillmaster version open <ns>/<name>@<版本>`；`offline` / `online` / `delete` 的第三句是 `到这个 skill 的设置页去做：skillmaster skill open <ns>/<name> --tab settings`（三个 skill 级动词都脱掉版本）；第一句是同一模板（`<verb>` 代入），第二句逐字相同 | CLI 用例逐条断言完整字符串 |
| 18 | **权限与 scope 不变宽**：建 skill / 建草稿 / 撤销共享 / 申请 / 撤回用 `skills:write`；**`@new` 先读底稿（GET → `skills:read`）再写（POST → `skills:write`），是两次 scope 检查，不是两把 scope**——按 [ADR 0039](../../decisions/0039-api-key-defaults-and-their-edges.md) §决定 2「写蕴含读」，一把带 `skills:write` 的凭据在签发时已带上读；列共享 / 读自己的申请用 `skills:read`；**不新增任何 scope**；同一动作在网页与 CLI 的档位要求一致 | IT 按档位与 scope 逐档跑 |
| 19 | **机器可读**：`create` / `shares` / `unshare` / `request` / `open` 的每条形态都支持 `--json`；内容命令（`write` / `edit` / `rm` / `mv`）沿用 v7 的契约、**不接受** `--json`。失败时退出码非零，`--json` 的错误对象走 stderr，含具名码（顶层 `code`，以及 `error.issue`——服务端 `details[].issue` 里的那个名字） | CLI 用例 |
| 20 | **文档同步**：`skillmaster skill help` 列出新命令；CLI README 有五块能力的入口、两条落点规则与「只读密钥申请不了访问」；voice-spec 已更新：名词表含「申请」，「新建 skill」的定义不再限定浏览器 | 人工核对 + 一条帮助文本用例 |

**成立条件**（README 的摘要用这一组）：#1、#7、#12、#13、#14、#15、#17、#18——分别对应「新能力真的建出东西」「不该建的时候零写入」「协作者的读取档位不放松」「撤销的档位不放松」「申请的前置不被绕过」「申请不偷偷改变可读性」「交接落到正确的页」「scope 不变宽」。它们覆盖了本版唯一可能造成实质伤害的三类地方：写出不该写的东西、放宽权限、把人送到错的地方。

**指标**：本版没有需要长期观测的量化指标；验收即判据。

## 依赖与前置

- **内容与生命周期轴**：[ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md)（凭据能对版本做什么）、[ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md)（提交与上线分开）、[ADR 0046](../../decisions/0046-deleting-a-skill-happens-in-the-browser.md)（删除只在浏览器面）、[ADR 0054](../../decisions/0054-lifecycle-actions-happen-on-a-version-page.md)（生命周期动作在单版页）。
- **按人授权轴**：[ADR 0034](../../decisions/0034-skill-level-sharing.md)（共享是 skill 级授权，CLI 有入口）、[ADR 0047](../../decisions/0047-permissions-are-a-ladder.md)（梯子；`管理` 授权与撤销）、[ADR 0052](../../decisions/0052-a-request-is-not-a-grant.md)（申请不是授权；批准按 §决定 3、队列按 §决定 4 在「设置 › 协作者」）、[ADR 0073](../../decisions/0073-asking-for-access-splits-by-plane.md)（本版新增：申请 / 撤回 / 读自己的在消费面 API，批准 / 拒绝与待批列表只在网页）、[ADR 0051](../../decisions/0051-the-author-plane-serves-by-tier.md)（作者面按档位分）。
- **新建与草稿的语义**：[ADR 0070](../../decisions/0070-creating-a-skill-creates-its-first-draft.md)（一个事务建 skill 与第一份草稿）、[ADR 0055](../../decisions/0055-drafts-have-a-required-name.md)（草稿有必填名称）、[ADR 0071](../../decisions/0071-a-deleted-name-answers-with-its-own-code.md)（`skill_deleted`）、[ADR 0005](../../decisions/0005-content-addressing.md)（内容寻址）。
- **交接落点**：[ADR 0072](../../decisions/0072-the-handoff-points-at-the-page-that-owns-the-action.md)（本版新增，修订 0061 §决定 4 的落点）。
- **已有用例**：`CreateSkillUseCase`（v9）、`CreateDraftFromVersionUseCase`（v8）、`ListSkillGrantsUseCase` / `RevokeSkillShareUseCase`（v5）、`RequestSkillAccessUseCase` / `WithdrawSkillAccessRequestUseCase` / `SkillAccessService`（v5）。
- **需要新增的服务端面**：作者面三条路由（建 skill、建草稿、读被解析出的底稿），消费面三条路由（申请、撤回、读自己的申请）。**都不新增 scope、不改梯子**。
- **文案**：[`design/voice-spec.md`](../../../design/voice-spec.md) 的名词表；本版把「申请」补进去，并把「新建 skill」定义里「浏览器里」那半句改成不限定入口（CLI 从本版起是第二个「从零新建」入口（`skill submit` 是整包提交，也能建出 skill））；这两处与代码同批交付，不改变任何界面画法。
