# v10-cli-lifecycle · 测试方案

> **最后更新**：2026-10-21
> **所属版本**：[`README.md`](README.md) · **需求**：[`prd.md`](prd.md) · **设计**：[`technical-design.md`](technical-design.md)

<!--
验收在 prd.md，接口在 technical-design.md；这里只写「怎么验」与结果。结果一节在实现后如实填写。
-->

## 验收映射

prd.md 的 20 条验收，逐条落到用例。**一条对多例**是常态。

| 验收 | 用例 |
|---|---|
| #1 新建 skill 一次成型 | S1（IT）、C1（CLI 端到端） |
| #2 新建 skill 的拒绝具名且零写入 | S2–S4 |
| #3 新建 skill 的字段校验 | S5–S8、C2 |
| #4 `@new` 用线上做底稿 | S9、C3 |
| #5 `@new` 的底稿规则 | S10–S13、S14（别的 skill 的版本码） |
| #6 底稿状态的两种答案 | S15、S16 |
| #7 内容相同不给草稿 | S17 |
| #8 基线过期与缺 digest | S18、S19、C4 |
| #9 `@new` 缺版本名称 | C5、S20 |
| #10 `--note` 落到草稿 | S21 |
| #11 `cp` 不接受 `@new` | C6 |
| #12 协作者列表与档位 | S22–S24、C7 |
| #13 撤销共享 | S25、S26、C8 |
| #14 申请的前置与幂等 | S27–S30、C9 |
| #15 申请不改变可读性 | S31、C10 |
| #16 打开网页 | C11 |
| #17 拒绝句逐字正确 | C12 |
| #18 权限与 scope 不变宽 | S32、S33 |
| #19 机器可读 | C13 |
| #20 文档同步 | D1、D2 |

## 用例

### 服务端（IT，`*IT`，真端口 + 真库）

**S1 · 建 skill 一次成型**
前置：注册账号 A（个人命名空间 `a`）。步骤：`POST /api/v1/author/skills`，`{name:"hello", description:"d", version_name:"第一版"}`。期望：201，`state=draft`，返回草稿 id；`skill` / `skill_version` / `version_file` 各 +1 行；草稿根 `SKILL.md` 的 `name`/`description` 等于请求；`skill.current_version_id` 仍为 null。

**S2 · 同名 409** 前置：A 已有 `hello`。期望：409 `skill_already_exists`，三表行数不变。

**S3 · 已删除名字 409** 前置：`hello` 被删除（`deleted_at` 非空）。期望：409 `skill_deleted`，行数不变。

**S4 · 拒绝零写入** S2/S3 之后各比一次三表行数；另用空描述触发一次 400 后同样比对。

**S5 · 描述缺失** 期望：400 `invalid_upload`，`details[].issue=required_field_missing`。

**S6 · 名字越界** 65 个码点的名字 → 400 `invalid_upload`，issue `name_too_long`；含 `/` → issue `name_not_usable_as_a_directory`；含 `@` → issue `name_contains_version_separator`；含 `%` 或 `;` → issue `name_contains_unaddressable_char`。

**S7 · 版本名称越界** 41 个码点 → 400 顶层码 `draft_name_too_long`；空串 → 400 `draft_name_required`。

**S8 · 码点计数** 与 v9 `CreateSkillIT.theNameIsCountedInCodePoints` 同形：64 个码点的中文名字应当通过。

**S9 · `@new` 用线上做底稿** 前置：A 的 skill 有线上 `1.0.0`（内容 `SKILL.md` + `a.md`）。步骤：`GET …/draft-base` → `{base:{pin:"1.0.0",digest}}`；`POST …/versions`，`from=1.0.0`、`from_digest`、`edit{op:"write",path:"a.md",text:"changed"}`、`name:"第二版"`。期望：201；新草稿的 `a.md` 是 changed、`SKILL.md` 与线上相同；返回的 `version.code` 可继续写。

**S10 · 底稿默认：有线上 → 线上** `draft-base` 返回线上 pin 与 digest（另有一份草稿同时存在时仍取线上）。

**S11 · 底稿默认：无线上但有草稿 → 最新草稿** 前置：skill 未上线、有两份草稿。期望：`draft-base` 返回其中一份——**具体哪一份以服务端排序为准**；用例断言「返回的那一份确实是草稿且可读」，不假设创建顺序。

**S12 · 底稿默认：都没有 → 空白** 期望：`{base: null}`；用 `from`/`from_digest` 都不带的请求建草稿成功，根 `SKILL.md` 是模板。

**S13 · 显式 `--from` 用指定版本** `from` 指向历史版本（已上线、非当前）→ 新草稿基于它。

**S14 · 别的 skill 的版本码 → 404** 前置：A 有两个 skill，各有一份草稿。步骤：用 skill 1 的草稿版本码作 skill 2 的 `from`。期望：404（这个 pin 在 skill 2 里解析不到）。**注**：草稿对 `write` 及以上是可见的（ADR 0051 §决定 2），所以「本档位看不见的草稿」在 `@new` 这个入口上不成立。

**S15 · 已下架 / 已丢弃：`维护` / `管理` → 400** 前置：版本被下架/丢弃，调用者有 `maintain`。期望：400 `invalid_request`，`details[].issue=not_a_base`。

**S16 · 已下架 / 已丢弃：`写入` → 404** 同一场景，调用者只有 `write`。期望：404。

**S17 · 内容相同 → 409** `@new` 的第一次改动让结果与既有版本 digest 相同。期望：409 `content_already_exists`；三表行数不变。

**S18 · digest 过期 → 409** 先读底稿 digest，另一请求改动该底稿，再建草稿。期望：409 `digest_mismatch`。

**S19 · 缺 digest / 空白带 digest** 有底稿但不带 `from_digest` → 428 `if_match_required`；空白底稿带 `from_digest` → 400 `invalid_request`。

**S20 · 缺版本名称** 直接在请求里省略 `name` → 400 `draft_name_required`，零写入。

**S21 · `--note` 与上限** 带 `note` 建草稿后，作者面读该版本 `note` 等于它；201 个码点 → 400。

**S22 · 共享列表：所有者与 `admin`** 期望：200，行含 `handle`/`role`/`created_at`。

**S23 · 共享列表：档位不足** 公开 skill 上 `调用`/`浏览`/`写入`/`维护` 各调一次。期望：403。

**S24 · 共享列表：看不见** 私有 skill 上陌生人。期望：404。另测 `q` 过滤与 `p` 翻页（每页 10）。

**S25 · 撤销共享幂等** 撤一条存在的 → 204；再撤 → 204；`skill_grant` 行数按预期变化。

**S26 · 撤销共享的拒绝** 公开 skill 上 `调用`/`浏览`/`写入`/`维护` 各一次 → 403；用户名不存在 → 400 `invalid_request`，issue `no_such_user`。

**S27 · 申请成功与重复** 能看见、当前 `browse`、申请 `write` → 201；再申请 `maintain` → 仍只有一行，`wanted_role` 变成 maintain。

**S28 · 申请的前置拒绝** 等于当前档位 → 400 `not_above_current_rung`；所有者 → 400 `owner_has_no_higher_rung`；非五档 → 400 `not_a_rung`；陌生人申请私有 skill → 404。

**S29 · 撤回幂等** `DELETE …/me` → 204；再删 → 204；`--status` 读到 null。

**S30 · 只读 scope 不能申请** 用只有 `skills:read` 的 key 发 POST → 403（scope 层）。

**S31 · 申请不授予** 申请前后，用同一凭据 `GET …/metadata` 与 `GET …/versions` 的响应完全相同；`skill_grant` 行数不变。

**S32 · scope 矩阵** 新路由：POST/DELETE 用只读 key → 403；GET 用写 key → 200（写蕴含读）。

**S33 · 档位矩阵** 读底稿 / 建草稿要求 `write`+（`browse` → 403）；申请不要求档位（能看见即可）；共享列表与撤销要求 `admin`。

### CLI（`go test ./cmd/skillmaster`，httptest 假服务端）

**C1 · `skill create` 端到端** 断言：POST 路径与体；stdout 含 `ns/hello@<码>`、版本名称；`--json` 五字段（含 `state`）。

**C2 · `skill create` 的本地校验** 缺 `--description`、名字含 `/`、名字含 `@`、显式传空 `--name` → 非零退出、请求计数 0。

**C3 · `@new`（省略 `--from`）** 先 `GET …/draft-base` 拿引用，再 `POST …/versions` 带上 `from`/`from_digest` 与 `edit`；断言两个请求的顺序与体。

**C4 · `@new`（显式 `--from`）** 先 `GET …/versions/{pin}` 取 digest，再建草稿；409 `digest_mismatch` 翻成中文并在 `--json` 里带码。

**C5 · `@new` 缺 `--name`** 本地用法错误、非零退出、请求计数 0。

**C6 · `cp …@new`** 本地拒绝，句子说明「先建出版本，再复制文件」，请求计数 0。

**C7 · `skill shares`** 输出含用户名/档位/时间与页码行；`--query`/`--page` 进查询串；`--json` 透出 `{grants,total,page,page_size}`。

**C8 · `skill unshare`** DELETE 路径含 handle；404 不出现（幂等）；`no_such_user` 翻成中文。

**C9 · `skill request` 三种形态** POST 体 `{rung}`、GET `…/me`、DELETE `…/me`；重复申请照发（服务端覆盖）。

**C10 · `--status`** `{pending_rung:"write"}` → 打印档位；null → 「没有在等的申请」。

**C11 · `skill open`** 默认、`--tab`、`--tab settings --sub collaborators` 三种地址；`--sub` 不配 settings → 本地错误；`--print` 不调用浏览器；`--json` 给 `{url,opened}`。

**C12 · 拒绝句** 七个动词各一次：`publish`/`rollback`/`revoke`/`restore` 的第三句逐字含 `skillmaster version open <ns>/<name>@<版本>`；`offline`/`online`/`delete` 逐字含 `skillmaster skill open <ns>/<name> --tab settings`，且入参带 `@版本` 时交接地址里没有版本。前两句七个动作相同（第一句 `<verb>` 代入）。

**C13 · `--json` 与退出码** 五块能力的每条形态都接受 `--json`；失败走 stderr、退出码非零、含顶层码或 `error.issue`（服务端 `details[].issue` 的名字）。

### 文档

**D1 · 帮助文本** `skillmaster skill help` 列出 create/shares/unshare/request/open；`version` 组的帮助仍不列页面专属动词。

**D2 · voice-spec 与 README** voice-spec 名词表含「申请」，「新建 skill」定义不再限定浏览器；CLI README 有五块入口、两条落点规则、只读密钥 caveat。

## 环境与数据

- **服务端 IT**：照 `CreateSkillIT` / `VersionEditorIT` 的形状——`AbstractIT` 真端口 + 真库，浏览器面用 `AbstractAccountIT.Browser`，API 面用真 Bearer；每个用例 `@Sql("/sql/truncate-business-tables.sql")`；跑法 `./mvnw -o verify -Dit.test=<IT>`（测试库先 `scripts/init-test-db.sh`）。
- **夹具**：账号 A（个人命名空间）、账号 B（被共享者）；一把 `skills:write` key、一把只读 key；一个已上线版本 + 一个历史版本 + 两份草稿 + 一个已下架 + 一个已丢弃。
- **CLI**：`httptest` 假服务端；不碰真网络。真机端到端（真服务端 + 真库 + 真 CLI）在实现后按需补一次，结果写进 §结果。
- **清理**：IT 用既有的 truncate 脚本；CLI 用例不落盘。

## 结果

**2026-10-21 实现完成；自动化与本机真机端到端均已执行，只有「真正打开一次浏览器」没有做。**

| 层 | 命令 | 结果 |
|---|---|---|
| 服务端单测 | `./mvnw -o verify -Dit.test=CliAuthoringIT,AccessRequestApiIT`（真 `.env`） | **238 通过** |
| 服务端 IT | 同上 | **21 通过**：`CliAuthoringIT` 15 / `AccessRequestApiIT` 6 |
| CLI | `go test -count=1 ./...` | **通过**（`lifecycle_test.go` 20 个用例，含五条新命令每条形态的 `--json`、失败对象里的 `issue`、本地用法错误的 `usage` 码、显式 `--from`、`--print` 不打开浏览器、帮助文本） |
| 文档 | `check_docs.py` | **全部通过** |
| 真机端到端 | 本机服务（当前代码 + 开发库）+ 构建出的 CLI | **通过**（见下） |

### 真机端到端（2026-10-21）

本机起一份当前代码的服务（`spring-boot:run`，开发库），用两把 `skills:write` 的 key 与一把只读 key 驱动构建出的 CLI：

- `skill create` 成功、`--json` 五字段；重复名字得到中文的 409。
- `version list` 看得到草稿；`skill list`（消费面）里没有它；`skill versions` 非零退出并给出 404。
- `write @new`（空白底稿，服务端渲染 `SKILL.md`）→ `edit @new`（以最新草稿为底）→ 显式 `--from <第一份草稿>`；第三份草稿确实带着上一份的 `SKILL.md`，`--note` 可在 `version list` 看到。
- 相同内容再建一次 → 409 `content_already_exists` 的中文句子。
- `version discard` 之后 `@new` 跳过被丢弃的那一份、落到剩下最新的草稿（文件清单验证）。
- `skill shares`（空与 JSON）、`unshare` 未知用户（400 `no_such_user` 中文）、撤销一条不存在的共享（成功）。
- 所有者 `request` → 400 `owner_has_no_higher_rung` 中文；`--status` 空、`--cancel` 连续两次成功。
- 第二账号：所有者授予 `浏览`；B 申请 `写入`、`--status` 读到、`--cancel` 撤回；B 在只有 `浏览` 时 `write @new` 被 403 拒绝；撤销共享后 B 再读该 skill 是 404。
- 只读 key：`request --status`（GET）可用，`request --role`（POST）被 403 拒绝并说明需要 `skills:write`。
- `skill open --print` 与 `--json` 给出正确的地址；显式空 `--name` 在本地被拒、不发请求；65 码点名字的 `--json` 失败对象带 `issue=name_too_long`；`--page 0 --json` 是 `code=usage` 的 JSON 失败对象。
- 服务端日志在整轮里没有 ERROR 或异常。

### 已执行

- **S1、S2、S3、S5、S7、S9、S11、S12、S13、S17、S18、S19、S20、S21**：`CliAuthoringIT`。
  - S11 用两份草稿：断言 `draft-base` 答的是其中一份、且按那个 pin 读回来确实是 `draft`（具体哪一份以服务端排序为准，用例不假设创建顺序）。
  - S13 覆盖两种底稿：已上线但非当前的历史版本、以及一份草稿。
  - S16 只覆盖了**已丢弃**底稿在 `写入` 档下的 404；`维护` / `管理` 的 400 `not_a_base` 由同一用例覆盖（见 §部分执行）。
  - PRD 做 #2 的另外两种首次改动（`rm`、`mv`）另有 `renameAndDeleteCanBeTheFirstChange`（TD P0 的补充用例，验证这两种 op 也能完成第一次写入）。
- **S27、S28、S29、S31**：`AccessRequestApiIT`；S31 比较申请前后 `metadata` 与 `versions` 两个响应体逐字相同。
- **C1–C11、C13**：`lifecycle_test.go`。
  - C1 同时断言文本输出与 `--json` 的五个字段；C2 覆盖显式空 `--name` 在本地被拒（不发请求）。
  - C7 断言 `用户名 / 档位 / 加入时间` 三列与页码行；C8 连发两次 DELETE；C9 连发两次 POST（服务端覆盖同一行）；C11 覆盖默认地址、单独 `--tab`、`settings + collaborators`、`--print`、`--json`。
  - C13 覆盖五条命令的每条形态都接受 `--json`，以及失败对象的 `code` 与 `error.issue`（`name_too_long`）、本地用法错误的 `usage` 码。
- **C12**：七个动词的第三句逐字断言；`@版本` 的剥离对 `offline` / `online` / `delete` 各测一次。
- **D1**：`TestTheHelpListsTheNewVerbs` 断言 `skill help` 列出五个新动词。

### 部分执行

- **S6**：含 `@` 的名字测了；65 码点与含 `/` 的名字没有单列（两者走同一个 `SkillDocument` 规则，issue 名由 `issueValues` 一并翻译）。
- **S10**：只测了「有线上 → 线上」；「线上与草稿并存时仍取线上」没有单列。
- **S15 / S16**：只测了已丢弃，**已下架没有单列**（两种状态在 `resolveBase` 里走同一条拒绝）；`维护` / `管理` 的 400 与 `写入` 的 404 都只在这一个状态上验过。
- **S22–S26**：既有 `SkillSharingIT` 覆盖「所有者列表有 `handle` / `role`」「`写入` 档在列表上 403」「撤销两次仍 204」「陌生人对 grants 的 POST / GET / DELETE 是 404」。
  - **没覆盖**：`admin`（被授予者）的列表、`created_at` 列、`调用` / `浏览` / `维护` 三档在列表与撤销上的 403、`q` / `p` 分页、服务端 DELETE 的 `no_such_user`（CLI 侧的那句翻译有单测）。
  - 这些是 v5 既有行为，本版没有改服务端；本版新增的只是 CLI 的两条命令，它们的请求形状与输出由 C7 / C8 覆盖。
- **S32 / S33**：没有按五档逐档单列；`draft-base` 的 `write+` 由 `CliAuthoringIT` 的 `maintain` 用例间接走到。
- **C12**：只逐字断言了第三句；第一句是模板、第二句未逐字断言。
- **D2**：voice-spec 与 CLI README 已更新，但这条靠人工核对，没有测试读 README 文本。

### 未执行

- **S4**（400 之后三表行数）、**S8**（码点计数）、**S14**（别的 skill 的版本码 → 404）。
- **S30** 已在真机端到端里执行（只读 key 的 POST 被 403 拒绝）；`InsufficientScopeIT` 仍覆盖同一条规则。
- 真正打开一次浏览器（本次只用 `--print` 验了地址与参数）；视觉验收仍由人做。

## 已知问题

- **同意屏的 `skills:write` 清单没有跟着加「新建 skill」**（`已复现`）：`design/README.md`、首页演示与 `CliSignInPage.vue` 里那句仍是「提交新版本、管理草稿与共享」。它没有说错，但 v10 之后不再穷举。改动涉及设计件与网页文案，本版没有动；下一版改到那一屏时一并加。
- **两条契约不是缺陷**（`已修复`→此处只是说明）：`cp …@new` 由 CLI 在本地拒绝（服务端的 create 只有 write / delete / rename），内容命令不接受 `--json`（v7 起只回答字节与句子，PRD 验收 #19 已按此收窄）。`@new` 的失败走 `writeRefusal` 的中文句子；v10 新出现的拒绝（`not_a_base`、`invalid_format`、`unexpected`、`note_too_long`、`draft_name_required` / `draft_name_too_long`、`file_already_exists`、两个「不能删」）已翻成中文。
- **只读 scope 的申请被拒（S30）没有单列**（`未验证`）：由 `/api/v1/**` 的既有方法—scope 规则与 `InsufficientScopeIT` 覆盖；本版没有新增 scope，因此没有新规则要验。
- **T-PERF 类指标**（`未验证`）：本版没有性能目标；`skill shares` 的人数增长由服务端分页约束，不在本版测量。
