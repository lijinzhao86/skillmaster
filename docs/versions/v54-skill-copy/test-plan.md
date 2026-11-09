# v54-skill-copy · 测试方案

> **状态**：已实现，[`§结果`](#结果) 已填（2026-11-09）；D2 人工视觉验收已由所有者确认，F4 角色矩阵待所有者决定。

## 验收映射

| 验收 | 用例 |
|---|---|
| A1 入口与权限（浏览及以上、非所有者、有可读已发布版本；调用档 / 无关系 / 自己拥有 / 已下线 / 无版本 / 休眠条目没有；发现页行没有） | S2、S6、S7、S8、S20、S21、U1、U7、D1 |
| A2 默认线上，可选已发布历史版本；草稿 / 已丢弃 / 已下架不可选 | S1、S3、S4、S5、U2、C3 |
| A3 名字必填；控制台预填；CLI 缺 `--name` 报错；改名后地址与 `SKILL.md` 一致；不可改名与保留前缀拒绝 | S11、S12、S13、S25、C2、U3 |
| A4 同名活 / 已删除拒绝，控制台置灰直到改名 | S9、S10、S18、C4、U3 |
| A5 出处：源地址、源版本、复制时间；信息页与 CLI 可读 | S16、S24、C1、C6、U5 |
| A6 许可随行；未声明照记；不因许可拒绝；attribution 写来源 | S14 |
| A7 不携带标签 / 统计 / 协作者 / 服务凭据 / 可见性；新 skill 私有 | S15 |
| A8 只有调用档的账号直接调接口被拒 | S6 |
| A9 CLI 与网页同一件事；网页成功后停在弹窗，两个去向 | S26、C1、U4 |
| A10 失败原子性（含 `skill_copy` / 许可 / 审计不留行） | S17、S18、S19 |
| A11 上限 | S17 |
| A12 审计 | S23 |
| A13 文案：动作叫「复制」；剪贴板写清对象 | U6、D1 |
| A14 文档、测试、设计检查（无新增失败） | F1–F4、D1、D2 |

## 用例

### 服务端（`./mvnw verify`）

**S1 · 公开 · 浏览的线上版本复制成功**
- 前置：`dev/source` 为公开 · 浏览，有一条线上版本 `@1.2.3`；调用者 `you` 的库里没有同名 skill。
- 步骤：`POST /api/v1/author/skills/dev/source/copy` body `{"name":"source-copy"}`（作者面入口；网页入口见 S26）。
- 期望：201；`you/source-copy` 存在、`visibility=private`；一条草稿，其 manifest 与 `dev/source@1.2.3` 相同——改名复制时除 `SKILL.md` 外 relpath / digest / size 逐项相同，`SKILL.md` 的 `name` 已改为新地址（不改名时 `SKILL.md` 也逐字节相同，见 S13）。

**S2 · 共享给我的私有 skill 可复制**
- 前置：`dev/private` 为私有，给 `you` 一条 `browse` 授权；有线上版本。
- 步骤：同 S1（目标名 `private-copy`）。
- 期望：201；新 skill 私有，内容与源线上版本一致。

**S3 · 复制已发布历史版本**
- 前置：`dev/source` 有线上 `@1.2.3` 与历史 `@1.2.2`，内容不同。
- 步骤：body `{"name":"source-old","version":"1.2.2"}`。
- 期望：201；新草稿与 `dev/source@1.2.2` 逐项一致，不是线上。

**S4 · 草稿 / 已丢弃版本不能复制**
- 前置：`dev/source` 有一条草稿（短码）与一条已丢弃（短码）。
- 步骤：分别用两个短码作为 `version`。
- 期望：都 404（与所有读路径同一个 `skill_not_found`；草稿与已丢弃在消费面上解析不出）；不产生新 skill。

**S5 · 已下架版本不能复制**
- 前置：`dev/source` 的 `@1.2.1` 已下架。
- 步骤：`version="1.2.1"`。
- 期望：410 `version_revoked`（与现有 `VersionRevokedException` 同一个映射）；不产生新 skill。

**S6 · 只有调用档被拒**
- 前置：`dev/source` 公开 · 调用；`you` 只有 `invoke`。
- 步骤：调 copy。
- 期望：403 `forbidden`，detail `browse_required`。

**S7 · 所有者不能复制自己的 skill**
- 前置：`dev/source` 由 `you` 拥有。
- 步骤：调 copy。
- 期望：403 `forbidden`，detail `owner`。

**S8 · 不可见的源是 404**
- 前置：`dev/private` 私有且未共享给 `you`。
- 步骤：调 copy。
- 期望：404（与所有读路径同一个 404）。

**S9 · 同名活 skill 拒绝**
- 前置：`you/source` 已存在且未删除。
- 步骤：复制 `dev/source` 到 `source`。
- 期望：409 `skill_already_exists`；`you/source` 不变，源不产生新版本。

**S10 · 同名已删除 skill 拒绝**
- 前置：`you/source` 软删除。
- 步骤：同上。
- 期望：409 `skill_deleted`。

**S11 · 非法 / 保留名字**
- 步骤：目标名分别用 `Bad_Name`、`a--b`、`skillmaster-x`、长度上限 +1 的字符串（上限见 `SkillNameTransform.MAX_LENGTH`）。
- 期望：422 `invalid_name`，detail 分别是 `invalid_name` / `invalid_name` / `name_reserved` / `name_too_long`；不产生新 skill。

**S12 · 改名只改 SKILL.md 的 name**
- 前置：`dev/source` 的 `SKILL.md` 顶层 `name: source`，另有 `references/a.md`。
- 步骤：目标名 `source-copy`。
- 期望：新版本 `SKILL.md` 的 `name` 为 `source-copy`；`references/a.md` 逐字节相同；源版本不变。

**S13 · 原名复制不改内容**
- 步骤：目标名 `source`（目标库没有同名）。
- 期望：新版本 manifest 与源完全一致；`SKILL.md` 的 digest 不变。

**S14 · 许可随行与导出归属**
- 前置：源版本有 `skill_version_license`（MIT）；另一源版本 `status=missing`。
- 步骤：各复制一次；再对副本导出 zip。
- 期望：新版本的许可行存在且除 `version_id` 外与源一致；未声明的那次也是 `missing`，复制未被拒；`attribution.md` 正文第一项是「- 复制自：`<源地址>` @`<版本>`」，许可一行在 MIT 副本写「许可：MIT」、未声明副本写「许可：未声明」（不是 SPDX 的 `NOASSERTION`）

**S15 · 不携带私有事实**
- 前置：源有分类、收藏与调用量、协作者、可见性公开。
- 步骤：复制。
- 期望：新 skill 没有分类、协作者；`skill_usage_total` 与 `skill_usage_day` 里没有新 skill 的行；`visibility=private`。**说明**：公开 / 我的标签与服务凭据由 `createOwned` + `submit` 只写 `skill` / `skill_version` / `version_file` 保证，本用例没有为它们单独造夹具——这是一条结构保证，不是逐项断言。

**S16 · 出处可读**
- 步骤：复制后读 `GET /web/skills/{you}/source-copy` 的 `copied_from`，并查 `skill_copy` 行。
- 期望：`copied_from = {address:"dev/source", version:"1.2.3"}`；`source_skill_id` / `source_version_id` 分别等于源 skill 与源版本的行 id；`copied_at` 非空。**说明**：副本只有草稿时，消费面的 `/metadata` 与详情都按它一贯的规则不可见（消费面只服务已发布版本），所以出处从作者面读，不在消费面上开一个只为草稿的例外。

**S17 · 上限**
- 前置：源版本在普通新建 skill 的文件数 / 单文件 / 总量上限以内（数值见 [`limits.md`](../../current/limits.md)）。
- 步骤：复制；再跑 `DraftFileWriteLimitsTest` 的边界样本（上限 +1）。
- 期望：正常源成功；边界样本抛出与上传相同的 `IngestException`。**说明**：复制与上传调同一个 `DraftFileWrite.requireWithinLimits`，判定本身由既有单测覆盖；源版本本身受同一条上限约束，所以复制路径的拒绝只在「上限被调低后复制旧版本」时出现。复制先过上限、再拿写锁，因此超限时 `skill` / `skill_version` / `skill_copy` 一行都没写。

**S18 · 名字冲突不留半成品**
- 步骤：S9 之后查 `skill` / `skill_version` / `skill_copy`。
- 期望：没有新 skill、没有新版本、没有 `skill_copy` 行、没有审计行。

**S19 · 提交失败整体回滚**
- 前置：测试里让 `SkillVersionService.submit` 抛异常（注入/替身）。
- 步骤：调 copy。
- 期望：`skill` 行不存在，`skill_copy` 与许可行都不存在；审计也不存在。

**S20 · 没有已发布版本**
- 前置：`dev/draft-only` 只有草稿，没有线上版本。
- 步骤：调 copy。
- 期望：404（`resolveForConsumption` 空）；控制台入口不出现（U1）。

**S21 · 已下线**
- 前置：`dev/source` 已下线。
- 步骤：调 copy。
- 期望：503 `skill_offline`（与消费面所有读同一条规则：可见、但作者已停止服务）；控制台入口不出现。

**S22 · 同名源与目标不同命名空间**
- 前置：`dev/source` 公开；`you` 没有 `source`。
- 步骤：原样复制，不改名。
- 期望：201；`you/source` 与 `dev/source` 同时存在，内容一致。

**S23 · 审计**
- 步骤：复制一次，查 `audit_event`。
- 期望：一条 `action=copy`、`subject=skill`、`subject_id=新 skill`，detail 含源地址、源版本、目标名字、草稿 id 与 `created=true`。

**S24 · 无版本名称的已发布版本**
- 前置：源线上版本没有版本名称（按 digest 寻址）。
- 步骤：复制并读出处。
- 期望：`source_version_label` 是该版本的 digest（`SkillSnapshot.addressSuffix()`）；信息页把 digest 截成前 8 位加省略号（U5 的渲染用例覆盖）。

**S25 · 源的 `SKILL.md` 不能安全改名**
- 前置：源的 frontmatter 是 flow mapping（`{name: flow, description: …}`），合法但不是逐行写成的。
- 步骤：改名复制一次；再用原名复制一次。
- 期望：改名一次 409 `name_not_rewritable`，不产生新 skill；原名一次 201（不需要改写字节）。

**S26 · 网页入口与 API 入口同一件事**
- 步骤：用浏览器会话 `POST /web/skills/dev/source/copy` body `{"name":"web-copy"}`。
- 期望：201；新 skill 落在调用者命名空间；与 S1 的 API 结果同一条用例、同一份内容。

### CLI（`go test ./...` + 手工 e2e）

**C1 · 复制成功**
- 步骤：`skmr skill copy dev/source --name source-copy`。
- 期望：退出 0；stdout 有新地址 `you/source-copy` 与草稿 pin；服务端状态与 S1 一致。

**C2 · 缺 `--name`**
- 步骤：`skmr skill copy dev/source`。
- 期望：退出 2；打印 usage；不发请求。

**C3 · 版本钉**
- 步骤：`skmr skill copy dev/source@1.2.2 --name source-old`。
- 期望：复制的是 `1.2.2`；`@draftshort` 被地址解析拒绝（用法错误）。

**C4 · 服务器拒绝的下一句**
- 步骤：目标名已被占用；再试已删除名；再试一个不能改名的源；再试一个不存在或不可见的源；再对非法名字复制一次。
- 期望：分别显示 `skill_already_exists` / `skill_deleted` / `name_not_rewritable` 的话术与下一步；源不可见时打印专用的 404 句（不是「在你的 skill 里」），`--json` 的 code 是 `not_found`；非法名字打印复制自己的规则，不是标签的「1–24 个字」。

**C5 · 帮助**
- 步骤：`skmr help skill copy`。
- 期望：列出用法、`--name` 必填、失败码与一个例子；golden 测试通过。本版**不测** help 的分组改造（记在 [`TODO.md`](../../current/TODO.md)「CLI help 的分组与收口」那条）；`skill --help` 仍是平铺，`copy` 与 `cp` 的两句摘要不同。

**C6 · `skill show` 打印复制自**
- 步骤：副本上线后 `skmr skill show <新地址>`。
- 期望：在来源之前打印「复制自：`<源地址>` @`<版本>`」；草稿阶段的副本没有消费面读路径（见 A5）。

### 控制台（`npm test` / `npm run type-check`）

**U1 · 入口按档位**
- 步骤：分别以所有者、共享给我（浏览）、公开（浏览）、公开（调用）、无关系、已下线、无可读版本、休眠库条目打开详情页。
- 期望：只有「共享给我（浏览）」与「公开（浏览）」出现「⋯」，菜单里只有「复制」；调用档、无关系、自己拥有、已下线、无版本、休眠条目都没有「⋯」。发现页的结果行见 U7。

**U2 · 版本下拉**
- 步骤：打开复制弹窗。
- 期望：默认「线上 · <版本号>」；只有可读的已发布版本（线上 + 历史）；草稿、已丢弃、已下架不在列表。

**U3 · 名字与置灰**
- 步骤：清空名字、输入非法名、提交一个已被占用的名字、再改名。
- 期望：空 / 非法 / 冲突时「复制」disabled；冲突有红字；改名后恢复可点。

**U4 · 成功态**
- 步骤：复制成功。
- 期望：弹窗标题变「复制成功」；主按钮「查看新 skill」进新 skill 信息页；次按钮「留在当前页」关闭弹窗；不发生自动跳转。

**U5 · 出处**
- 步骤：进入新 skill 的信息页，展开来源与许可。
- 期望：「复制自 `<源地址>` · @`<版本>`」；地址是回源 skill 的链接。

**U6 · 剪贴板文案**
- 步骤：走查结果行、版本事实、信息页。
- 期望：结果行是「复制地址」，版本事实是「复制版本码」；没有单用「复制」指剪贴板，也没有把库到库的「复制」与剪贴板混在同一个名字上。

**U7 · 发现页行与休眠条目**
- 步骤：打开发现页的结果列表；再打开一个库条目休眠的详情页。
- 期望：结果行没有「⋯」或「复制」动作，只有剪贴板动作「复制地址」；休眠条目也没有「⋯」。

### 设计件（`design/`）

**D1 · 改动组检查**
- 步骤：`node design/scripts/console-states.mjs "design/skillmaster-控制台演示.html" artifacts/design/console --only skill-copy,skill-copied`。
- 期望：全绿；`?more=1`、`?copy=open|taken|invalid|done`、`?copied=1` 六个状态可渲染（`--only detail` 不含这些前缀，别用它代替）。

**D2 · 人看**
- 步骤：所有者打开页头「⋯」、弹窗、冲突置灰、成功态、信息页。
- 期望：书面确认（不是「测试全绿」代替）。

### 文档与收口

**F1** `python3 .claude/skills/docs-architecture/scripts/check_docs.py`、`python3 scripts/docs/impact.py --check`、`python3 scripts/docs/generate.py` 后工作区干净。
**F2** `node scripts/check-file-size.mjs` 退出 0。
**F3** `./mvnw verify`、控制台 `npm test` + `npm run type-check`、CLI `go test ./...`、设计 `npm run type-check` 全绿。
**F4** `qa/role-matrix` 在代码收口后由所有者决定是否跑（本版至少跑复制相关的 CLI + 浏览器探针）。

## 环境与数据

- **数据库**：`skillmaster-postgres` 容器；本工作区用 `skillmaster_v54` 与 `skillmaster_test_v54`（`.env` 里 `SKILLMASTER_DB_URL` / `SKILLMASTER_TEST_DB_URL`）。测试库可抛弃，迁移由测试重建。
- **服务端测试数据**：用现有 IT 的注册 + 发布助手造两个账号 `dev` 与 `you`；`dev` 在公开 · 浏览、公开 · 调用、私有共享（browse）、已下线、只有草稿各造一个 skill；`you` 造一个同名活 skill 与一个同名已删除 skill。版本用已发布 + 历史 + 草稿 + 已丢弃各一条，其中一个已发布版本改成就绪后被下架（S5）。
- **CLI**：Go 单测用假 API；手工 e2e 用本机 8081 的独立栈（真服务端 + 真 CLI，见项目记忆里的 `reference_local_e2e_stack`），或直接用测试库起服务端。`--name` 与版本钉各跑一次。
- **控制台**：`npm ci` 后在 `skillmaster-console/` 跑 `npm test`、`npm run type-check`；浏览器验证一次只开一套，避免 cookie 串。
- **设计件**：`cd design && npm run build:console` 后跑 `console-states --only skill-copy,skill-copied`；截图落 `artifacts/design/console/skill-copy*.png`（不入库）。
- **清理**：测试事务回滚；手工 e2e 结束删 `skillmaster_v54` / `skillmaster_test_v54` 时先问所有者（工作区收口时一起）。

## 结果

实现完成，2026-11-09 本机执行；第 1 轮循环审计的修复已回灌到下面这轮结果。服务端用 `SkillCopyIT`（23 条，全绿），CLI 用 `copy_test.go` 与 `errors_test.go` 的错误码夹具，控制台用 `skill-copy.test.tsx`（12 条）与 `discover.test.tsx` 的一条边界用例。

| 用例 | 结果 | 证据 |
|---|---|---|
| S1、S2、S3 | 通过 | `copiesTheLiveVersionIntoAPrivateSkillWithOneDraft`、`aSharedPrivateSkillCanBeCopied`、`copiesAPublishedHistoryVersionWhenTheAddressPinsOne` |
| S4、S5 | 通过 | `aDraftOrDiscardedVersionCannotBeCopied`（404 `skill_not_found`）、`aRevokedVersionCannotBeCopied`（410 `version_revoked`） |
| S6、S7、S8 | 通过 | `invokeOnlyCannotCopy`、`theOwnerCannotCopyTheirOwnSkill`、`anInvisibleSourceIsANotFound` |
| S9、S10、S11、S25 | 通过 | `anExistingNameIsRefusedWithZeroWrites`、`aDeletedNameIsANamedConflict`、`anInvalidOrReservedTargetNameIsUnprocessable`、`aSourceWhoseNameCannotBeRewrittenIsRefused`（409 `name_not_rewritable`；原名复制 201） |
| S12、S13 | 通过 | `renamingRewritesOnlyTheSkillMdName`、`theSameNameCopiesTheBytesUnchanged` |
| S14、S16 | 通过 | `provenanceAndLicenseTravel`（导出 zip 的 `attribution.md` 先写「- 复制自：…」；`skill_copy` 的两列 id 与源相同）、`aSourceThatDeclaredNoLicenseIsCopiedAsUndeclared`（导出写「许可：未声明」，不写 `NOASSERTION`） |
| S15 | 通过（部分断言） | `privateFactsDoNotTravel`：可见性、分类、授权、累计调用与当日调用都不随行；标签与服务凭据由同一条建行路径不触碰，未逐项造夹具 |
| S17 | 通过 | 每次成功复制都走 `DraftFileWrite.requireWithinLimits`；上限 +1 的边界样本由既有 `DraftFileWriteLimitsTest` 覆盖，复制与上传同一个判定 |
| S18、S20、S21、S22 | 通过 | `anExistingNameIsRefusedWithZeroWrites`（skill / version / version_file / `skill_copy` / 审计都不增）、`aSkillWithNothingPublishedCannotBeCopied`、`anOfflineSourceIsNotServed`（503 `skill_offline`）、`theSameNameInAnotherNamespaceIsAllowed` |
| S23、S24 | 通过 | `theCopyIsAudited`（detail 含草稿 id 与 `created=true`）、`aVersionWithoutANameIsLabelledByItsDigest`；信息页的 digest 截断由 U5 的渲染用例覆盖 |
| S19 | 未执行 | 需要在提交处注入异常；事务边界与 `CreateSkillUseCase` 完全相同（同一个 `underExclusiveWrite`），没有单独的替身接缝 |
| A9 跨平面（S26） | 通过 | `theWebRouteCopiesTheSameWay`：网页入口与 API 入口调同一个用例，结果一致 |
| C1、C2、C3 | 通过 | `copy_test.go`：请求形状、无版本时省略 `version`、缺 `--name` 与草稿钉在本地就是用法错误（0 请求）、成功后 stdout 的下一步。**真服务端 + 真 CLI**（8082）跑过：线上版本、历史版本 `@0.0.3`、`--json` 形状、缺 `--name`、草稿钉、重名、非法名、404 |
| C4–C6、A5 的 CLI 侧 | 通过 | `skill show` 打印「复制自 `<地址>` @`<版本>`」；`skill_already_exists` / `skill_deleted` / `name_not_rewritable` 的下一句；复制自己的 404 句与 `not_found` 码；非法名字不再被标签规则误答（本轮新修）；声明与命令树一致、help 预算、注册码夹具（`testdata/error-fixtures.json`，由 `errors_test.go` 读取） |
| C5 | 通过 | `skill --help` 仍是平铺，分组是 TODO「CLI help 的分组与收口」那条 |
| U1、U7、U2、U3、U4 | 通过 | `skill-copy.test.tsx`（12 条）：入口按档位（所有者 / 调用 / 离线 / 无已发布版本 / 休眠条目都没有）、⋯ 菜单是动作样式、弹窗预填与页面级版本选项、成功后的两个去向、冲突与已删除名置灰、保留前缀本地拒绝；`discover.test.tsx` 断言结果行没有复制动作、只有「复制地址」 |
| U5 | 通过 | `skill-copy.test.tsx` 的信息页用例：`copied_from` 显示地址、`@sha256:…` 截断与回源链接 |
| U6 | 部分 | 剪贴板文案走查：结果行「复制地址」、版本事实「复制版本码」、首页安装按钮改为「复制指令」；站点 `tests/home-page.test.tsx`（20 条）覆盖按钮文案与反馈，控制台其余按钮没有逐项自动化断言 |
| D1 | 通过 | `node design/scripts/console-states.mjs … --only skill-copy,skill-copied` 全绿（2026-11-09）；`skill-copy-invalid` 状态可渲染 |
| D2 | 通过 | 2026-11-09 所有者在本地栈（8082 + 5175）走查页头「⋯」、弹窗、冲突、成功态、信息页，确认「整体没有问题」 |
| F1 | 通过 | `generate.py`、`impact.py --check`、`check_docs.py` 全部退出 0 |
| F2 | 通过 | `node scripts/check-file-size.mjs` |
| F3 | 通过 | `./mvnw -B clean verify`（单测 469 + 集成 690 = 1159 条，全绿）、控制台 `npm test`（717 条）与 `npm run type-check`、CLI `go test ./...` 除三条既有更新器夹具外全绿；设计 `npm run type-check` 通过（`design/src` 下 v54 相关改动）；站点 `npm run type-check` 与 `npm test`（20 条）通过 |
| F4 | 未执行 | `qa/role-matrix` 由所有者决定 |

**CLI 的三条既有失败**：`TestTheCheckIsThrottledToOnceADay`、`TestANewerBuildIsInstalledAndTheCommandIsUntouched`、`TestATamperedPackageLeavesTheBinaryAlone` 在干净 `HEAD`（`f123178`）上同样失败，原因是本机更新器的官方安装目录夹具；与本版无关。

## 已知问题

- 无。
