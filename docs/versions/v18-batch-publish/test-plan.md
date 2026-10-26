# v18-batch-publish · 测试方案

## 验收映射

| prd 验收 | 用例 |
|---|---|
| 1 类型可给、可改、发布后冻结 | T01 |
| 2 改类型的门槛是 `write` | T02 |
| 3 已发布的 skill 按类型与当前线上版本算号 | T03 |
| 4 从未发布过的一律 1.0.0，且不可选类型 | T04 |
| 5 显式版本号优先 | T05 |
| 6 基线带预发布标记时类型不可用 | T06 |
| 7 算出来撞号只失败不换号 | T07 |
| 8 队列列出所有**我够得着上线的**、有草稿的 skill（按命名空间分开看） | T08、T13①、T23（CLI 那一半） |
| 9 没有类型的行要选一档才能勾 | T09 |
| 10 没有草稿时是空态 | T10 |
| 11 多选、全选、已选数可见 | T11 |
| 12 一次确认、确认单逐条列出 | T12 |
| 13 取消什么都不发生 | T12 |
| 14 逐条成功或失败 | T13、T20 |
| 15 成功的离开队列，失败的留下可重试 | T14、T20 |
| 16 结果里的号是实际值 | T15 |
| 17 发布之后 agent 立刻能取到 | T16 |
| 18 发不了的不进队列；过期页面那一条失败 | T13 |
| 19 超过 500 条时如实说明 | T17 |
| 20 执行过程一条一条地看得见，只数真的落地的 | T19 |
| 21 一批比弹窗高时，跟着走、算完结也看得全 | T19 |
| 22 结算不自己关；关掉之后队列是结果的样子 | T20 |
| 23 搜索与筛选在队列上真的能筛 | T21 |
| 24 一批里同一个 skill 出现两次 → 整批拒绝 | T18（接口）、T11④（页面：另一份勾不上、行上写原因） |
| 25 历史草稿自带的版本号沿用 | T22 |
| 26 连接断在中间时，结算只说有结果的那几条 | T24 |

## 用例

### T01 · 类型可给、可改、不继承、发布后冻结
- **步骤**：`skill submit --release minor` → 读回草稿 → `version release-type … patch` → 再读回 → **同一个 skill 再提交一份不同的内容，不带 `--release`** → 读回新草稿 → 发布 → 再试图改
- **期望**：类型依次是 `minor`、`patch`；**新草稿的类型是空**（不是 `patch`，没有继承）；发布之后再改被拒，原因写明"已发布"；`none` 能把草稿的类型清空

### T02 · 改类型的门槛是 `write`
- **前置**：一个 `write` 档的账号——它有这份 skill 的写权限，但不是这份草稿的提交人；另一个只有 `browse` 的账号
- **步骤**：第一个账号改这份草稿的 `release_type`；第二个账号也试一次
- **期望**：第一个成功。第二个拿到的是 **404**——草稿对 `browse` 不可见（`visibleStates` 只给 `published`），而本仓库的规矩是"看不见就是不存在"，既有的 `…/name`、`…/note` 也这么答；403 会告诉一个外人"这里有一份草稿"。随后第一个账号**自己发不了**这份草稿：发布要 `maintain`，那是 403（2026-10-25 实现时核实并改写，原写的是"第二个也被拒、说权限不足"）

### T03 · 按类型算号
- **前置**：**三个各自独立的 skill**，线上当前都是 `1.2.3`（同一个 skill 上连发三次是另一回事：基线会一路往上走）
- **步骤**：三个 skill 各一份草稿，分别 `major` / `minor` / `patch`，各发一次
- **期望**：依次得到 `2.0.0`、`1.3.0`、`1.2.4`

### T04 · 从未发布过的一律 1.0.0
- **前置**：三个从没发布过的 skill
- **步骤**：草稿分别不带类型、`major`、`patch`，各自发布
- **期望**：都是 `1.0.0`；界面上那三行**不提供类型选择**（版本那一格就是一个写死的号）。**用例**：`PublishBatchIT.aFirstPublishIsOneZeroZeroEvenWithNoTypeDeclared`（不带类型那条）

### T05 · 显式号优先
- **前置**：草稿带 `minor`，线上 `1.2.3`
- **步骤**：发布请求里给 `1.9.0`
- **期望**：发布后是 `1.9.0`

### T06 · 基线带预发布标记
- **前置**：线上当前 `1.0.0-rc.1`
- **步骤**：草稿带 `patch` 直接发布；再显式给 `1.0.0` 发布
- **期望**：第一次 400 `release_type_needs_explicit_name`；第二次成功得到 `1.0.0`——唯一性是**按字符串**的（`findBySkillAndVersion` / `UNIQUE(skill_id, version)`），`1.0.0-rc.1` 占不住 `1.0.0`，所以这条不依赖任何未定的规则
- **另造一次（线上那一版没有号）**：把线上那一版的 `version` 改成空串（直接改库），草稿带 `patch` → 同样是 `release_type_needs_explicit_name`；显式给号则成功
- **再另造一次（线上那一版不是合法 semver）**：把线上那一版的 `version` 改成 `1.0`（直接改库），草稿带 `patch` → 同样是 `release_type_needs_explicit_name`；显式给号则成功
- **用例**：`PublishBatchIT.aPrereleaseBaselineAsksForANumberRatherThanGuessingOne`、`aLiveVersionWithNoNumberAsksForOneRatherThanGuessing`、`aLiveVersionThatIsNotAVersionAtAllAsksForOneToo`（第三种形状；`ReleaseTypeTest` 与 `tests/publish-model.test.ts` 从判据那一侧覆盖同一件事）

### T07 · 撞号不顺延
- **前置**：线上 `1.2.3`，且 `1.2.4` 已被某个历史版本占用（直接改库造）
- **步骤**：草稿带 `patch` 发布
- **期望**：失败 `version_already_exists`；库里**没有**产生 `1.2.5`

### T08 · 队列内容与命名空间
- **前置**：同一个账号名下 `dev`：`A` 从未上线（1 份草稿）、`B` 已发布 `@1.2.3` 且有新草稿（**提交时间晚于 A 那一份**）、`C` 已发布但没有任何草稿；`yuhan` 下 `E` 已发布又有新草稿；另有一个**别人共享给我、我只到 `write`** 的 skill `D`（**在默认命名空间里**，否则它不出现只能证明命名空间筛选），上面有一份草稿；再造两行历史数据：一个**上线过但没有版本号**的 skill（直接改库把线上那一版的 `version` 置空），和一份**没有 `draft_name`** 的草稿
- **步骤**：打开待上线队列；把命名空间切到 `yuhan`，再切回 `dev`
- **期望**：默认空间里先是 **B、A 两行，B 在前**（按各自最新一份草稿的提交时间倒序）；A 写「从未上线」；**C 不出现**（没有草稿）；**D 不出现**（我够不着上线——它页面上的那一半由 T13① 验）；切到 `yuhan` 只看见 E；**两个空间的草稿永远不会同时出现在一张单子上**；菜单里每个命名空间后面跟着它自己的份数；改库造的那两行（另计，不改变上面 B、A 的顺序）：没有号的那一行状态格写 `—`（**不是**「从未上线」）、行上写「线上版本没有号，需指定版本号」、**勾不上**（换了类型也算不出号）；没名字的那一份写「未命名」，**照样能勾**
- **另造一次**：一份 `version` 非空的草稿（直接改库）**不在队列里**——队列只收可以算号的行（TD §算号第一档）；一个已删除的 skill 也不在队列里

### T09 · 需要类型的行先选才能勾
- **前置**：B 的草稿没有类型
- **步骤**：① 尝试勾选 B 的行；在行上选「次版本」；再勾。② 另造一条线上 `0.9.0`、类型 `major` 的草稿（号 `1.0.0`），**点行上那个 `1.0.0`** 换成「次版本」
- **期望**：① 选之前勾不上；选完能勾上；服务端读回这份草稿的类型是 `minor`。② 号立刻变成 `0.10.0`，服务端读回类型是 `minor`——**改档是写回服务端的，不是客户端状态**

### T10 · 空态
- **前置**：所有 skill 都没有草稿
- **期望**：一句说明「没有待上线的草稿」；不是空表格，也没有可用的「上线所选」

### T11 · 多选、全选与取消全选
- **前置**：队列里 5 个 skill——3 个各一份、2 个各三份，**那 3 个单份的里有 1 个缺版本类型**
- **步骤**：① 点「全选」；② 先手动把某 skill 勾成第二新的那份，再点「全选」；③ 再点一次（此时按钮写「取消全选」）；④ 只勾同一 skill 里的一份，看另一份
- **期望**：① **每个能算号的 skill 恰好勾上一份，且都是各 skill 里最新提交的那一份**；最新那份缺类型的那个 skill 一份都没进；动作条写「已选 N 份 · 2 个 skill 取最新草稿、1 份未指定版本类型」。② **手动选的那一份没被换掉**（全选补的是"还没有决定"的 skill），同组另一份随即不可勾。③ 清空，动作条整条不画（不是画成禁用）。④ 另一份的勾选框不可用，行上写「同批已选择该 skill 的另一份草稿」（验收 24 的页面一半）

### T12 · 确认单与取消
- **前置**：选中 4 份（其中 1 份的号已被占用）
- **步骤**：点「上线所选」，先取消一次；再点一次并确认
- **期望**：确认单上**这 4 行都在**，每行「skill · 版本名称 → 版本号」；取消之后 4 条仍是草稿、队列不变、服务端无变化

### T13 · 发不了的不进队列；逐条成功或失败
- **前置**：`maintain` 的账号，选中 4 行，其中 1 行的计算号已被占用；另有一个只有 `write` 档的账号，别人的 skill 上有一份它提交的草稿
- **步骤**：① `write` 账号打开队列，并直接调 `POST /web/skills/publish-batch` 带上那条；② `maintain` 账号打开队列后，先被撤掉其中一条的 `maintain`，再确认发布
- **期望**：① 那条**不在 `write` 账号的队列里**；接口那一条失败并写明权限不足，**HTTP 不是 403**；② 4 条里 2 条成功、撞号那条失败 `version_already_exists`、被撤权的那一条失败并写明原因；**结算写「2 份已上线，2 份失败」**

### T14 · 再发一次只处理剩下的
- **前置**：接 T13。**撞号那条**仍在队列；**被撤权那条不再出现**——队列只列我够得着的（T13② 撤的就是 `maintain`）
- **步骤**：选中它，再点一次「上线所选」
- **期望**：只提交这 1 条

### T15 · 结果里的号是实际值
- **前置**：线上 `1.2.3`，草稿带 `minor`，预演是 `1.3.0`
- **步骤**：确认单打开后、提交前，用脚本抢先发布一个 `1.3.0`
- **期望**：结果里写的是 `1.4.0`，并**明确说明与预演不同**；不是静默换了号

### T16 · 发布之后立刻可读
- **步骤**：批量发布之后跑 `skillmaster skills list`（**不加 `--show`**）；再对一条 `skill invoke`
- **期望**：这些 skill 出现在默认列表里；`invoke` 成功

### T17 · 超过 500 条
- **前置**：脚本造 501 个有草稿的 skill
- **期望**：页面有一句「只列出了最近 500 个 skill」；不静默截断

### T18 · 批量接口的逐条语义（IT）
- **步骤**：直接 `POST /web/skills/publish-batch`，targets 混入：正常一条、pin 不存在一条、撞号一条、别人的 skill（权限不足）一条
- **另造一次**：同一个 skill 的两份草稿放进同一批 → **请求级 400 `duplicate_target`**，首行之前就返回，**一条都没有上线**
- - **另造一次**：不带 CSRF 头调批量接口 → **4xx，首行之前**；未登录读 `GET /web/publish-queue` → 401（那条读必须显式列进 `/web/**` 的鉴权链，否则它是匿名可读的）
- **期望**：200 `application/x-ndjson`；首行 `start.total=4`，随后**四条 `row` 按请求顺序**到达（顺序由本用例验；「不是攒到最后一起给」里的**推送**由 `PublishBatchFlushTest` 钉住，**到达时机**两处都量不到、只在 `iterations/0001` 的一次性实测里），末行 `done` 的计数与四条对得上；正常那条已 live；另三条 `ok:false` 带各自的 code；**已成功的那条不因为后三条失败而回滚**。另造两次：`duplicate_target` 与空 `targets` → **在首行之前**就是普通 4xx，客户端读不到任何 `row`

### T19 · 执行过程一条一条地看得见（控制台）
- **前置**：队列里选 4 份，其中 1 份会撞号
- **步骤**：点「上线所选」→「确认上线」，在过程中（约 2 秒内）连续观察
- **期望**：确认单**不关**，就地变成执行单；**执行中 Esc 无效、没有关闭入口**（按钮是一颗不可点的「正在上线…」）；四条依次进入"走完 / 走完 / 正在走 / 还没轮到"；标题下方的计数从 `0 / 4` 走到 `3 / 4`（最后一条落定后进度条撤掉、标题换成结算），**只增不减**；落定的那条写的是**实际号**，失败的那条把原因写在下面。若服务端改为一次性返回，四条会在同一刻一起变——此时计数直接走到结算，**不许出现先于结果的"落地"**
- **再加一次长批次**：发 17 份——执行中正在走的那一条始终落在单子正中（偏不超过 40px），底边淡出；结算时单子回到第一条，能滚着看完 17 条

### T20 · 结算不自己关
- **前置**：接 T19
- **步骤**：等最后一条落定；点「完成」
- **期望**：最后一条落定后弹窗**不消失**，标题是「3 份已上线，1 份失败」，只有一颗「完成」（没有「取消」）；点它之后弹窗关闭，成功的 3 条离开队列、失败那条还在并带着原因

### T21 · 搜索与筛选
- **前置**：默认命名空间里有 7 份草稿、4 个 skill——其中 2 个 skill 各攒了多份（`log-triage` 三份、`changelog-writer` 两份），另 2 个各一份；这一组里没有**需要选类型**的行（`perf-budget` 没有类型，但从没上线，首次上线不用选；真正缺类型的几份在另一个命名空间）
- **步骤**：搜 `triage`；清空搜索；筛「有多份草稿」；搜索与筛选同时用；筛着的同时勾一份；**清掉筛选，在默认命名空间筛一次「缺少版本类型」**（0 行、显示空态）；**再切到另一个命名空间**，筛「缺少版本类型」
- **期望**：① 只剩 `log-triage` 那一组（**别用 `log` 验**：`changelog-writer` 也含这三个字母，子串匹配会命中两组——这是搜索按子串匹配的应有行为，不是 bug）；② 只剩攒了多份的那两个 skill；③ 搜索与筛选同时用是交集；④ **已勾选的不因筛选而丢失**（动作条仍说「已选 1 份」）；⑤ 切到另一个命名空间后筛「缺少版本类型」，只剩那两份没选类型的行（**默认命名空间里筛这一档是 0 行**：那 7 份里唯一没类型的 `perf-budget` 是从未上线，首次上线不用选类型）；⑥ 筛到空时说「没有符合条件的草稿。请调整搜索词或筛选条件。」

### T22 · 历史草稿自带的版本号被沿用
- **前置**：直接改库造一份 `skill_version.version = '0.3.1'`、`state = draft` 的行；再造一份同样的（另一个 skill）
- **步骤**：① 不带号发布第一份；② 第二份带显式号 `0.3.2` 发布
- **期望**：① 发出去的号是 `0.3.1`——**算号规则不参与**（这一档排在"请求有没有显式号"之前）；② 400 `version_name_is_assigned`，那一行仍是草稿，没有产生新版本

### T23 · CLI 的队列读（ADR 0081 的对应）
- **前置**：账号下有一个 skill 已上线 `@1.2.3` 且有新草稿，另有一个从未上线的 skill 有草稿
- **步骤**：`skillmaster skills pending`；再 `skillmaster skills pending --json`
- **期望**：文本模式每个 skill 一段（`dev/log-triage` 一行，「已上线 @1.2.3 · N 份草稿」一行，每份草稿一行：`<版本码>  「名称」 · 主版本/次版本/修订/未指定版本类型 · <提交时间>`），末尾一行总数；从未上线的写「从未上线」；没名字的那一份写「未命名」（不是空串）；`--json` 原样输出服务端的 `{skills, truncated}`，草稿里的 `release_type` 在。**覆盖**：`cmd/skillmaster/pending_test.go`（三例）、`PublishQueueIT.theAuthorPlaneAnswersTheSameQuestionForTheCli`

### T24 · 连接断在中间（控制台）
- **前置**：队列里选 4 份（其中 1 份会撞号）
- **步骤**：让批量接口的响应流在两条 `row` 之后断开——**不送 `done`**（自动化测试用一条自己控制的 `ReadableStream`；手工验可以在中间掐掉代理连接）
- **期望**：标题是「1 份已上线，1 份失败，2 份没有结果」——**成、败、没有结果三段各说各的**，已经把原因写在行上的那一条不会被并进「没有结果」；后两行的状态回到"还没轮到"（不是失败、也不是已上线），正文一句「连接中断了：已上线的不会撤回，其余的请重新上线。」；点「完成」后按各自结果更新队列。**另验一次"只丢了 `done`"**：每一行都回来了、流在 `done` 之前结束 → 按普通结算说（「1 份已上线，1 份失败」，不出现「连接中断了」）——判据是"有没有行没回来"，不是"请求有没有报错"
- **覆盖**：`tests/publish.test.tsx` 的 `breaks the stream mid-batch and counts the rows nobody heard from as 没有结果`、`does not call a batch interrupted when only the done line was lost`；设计件 `&publish=broken` 的检查

## 环境与数据

- **服务端**：本地 PostgreSQL + `skillmaster-server/mvnw spring-boot:run`；IT 用 `AbstractIT` + 真库（`skillmaster-server/scripts/init-test-db.sh`）
- **控制台**：`skillmaster-console` 的 dev server 指着本地服务端
- **样本**：从未发布的 skill、已发布又有新草稿的、已发布无草稿的；T07 的撞号与 T15 的抢先用脚本直接改库/调接口造
- **清理**：每个用例用一次性账号；T17 的 501 条用脚本造，跑完整体删除该账号数据

## 结果

**2026-10-25（实现完成、循环审计第三轮之后）**。服务端与 CLI 的自动化用例已跑；控制台的用例是自动化测试 **+ 人看过设计件上的待上线各态**（2026-10-25，产品负责人验收；检查全绿不等于看过，这一条的边界见 [`dev-sop` §五](../../../.claude/skills/dev-sop/SKILL.md)）。

**2026-10-26 补验**：用户报告批量上线没有逐行动画，查出传输其实没有逐行推送（详见 §已知问题第 1 条与 [`iterations/0001`](iterations/0001-batch-publish-actually-streams.md)）。本次改的是服务端的一行推送，重跑了 `PublishBatchFlushTest`（新增，对着旧实现红）与 `PublishBatchIT` 14 例（全绿），并用本机 headless 浏览器复验了控制台那张单子逐行落地。下表里 T18、T19 两行因此带 2026-10-26 的注。

| 用例 | 结果 | 证据 |
|---|---|---|
| T01 类型可给、可改、不继承、发布后冻结 | 通过（服务端） | `ReleaseTypeIT.aDraftDeclaresItsTypeAndTheNextSubmissionDoesNotInheritIt`、`aTypeCanBeChangedAndClearedWhileTheDraftIsADraft`（其中 `null` 与 `none` 两种写法都断言清空）、`aTypeFreezesAtPublish` |
| T02 门槛是 `write` | 通过（服务端） | `ReleaseTypeIT.theWriteRungIsEnoughAndBrowseIsNot`（browse 拿到 404：草稿对它不可见） |
| T03 按类型算号 | 部分通过 | `minor` 端到端在 `PublishBatchIT.theNumberComesFromTheTypeAndTheLiveVersion`；`major`/`patch` 只有单元算术（`ReleaseTypeTest`），**没有各自独立造一条端到端** |
| T04 从未发布一律 `1.0.0` | 通过（服务端 + 页面） | `PublishBatchIT.aFirstPublishIsOneZeroZeroEvenWithNoTypeDeclared`（不带类型那条，正是审计抓到的 blocker）；页面上不提供类型选择由 `tests/publish.test.tsx` 的 `offers no kind picker to a skill that has never been published` 断言（`.release-pick` 不存在，勾选位可用）与设计检查覆盖 |
| T05 显式号优先 | 通过 | `PublishBatchIT.anExplicitNumberWinsOverTheType`（走单条发布路由——批量不谈号） |
| T06 基线不可用（预发布 / 没有号 / 不是合法 semver） | 通过 | `PublishBatchIT.aPrereleaseBaselineAsksForANumberRatherThanGuessingOne`、`aLiveVersionWithNoNumberAsksForOneRatherThanGuessing`、`aLiveVersionThatIsNotAVersionAtAllAsksForOneToo`——三例都**走完第二步**：被拒之后显式给号，同一份草稿成功上线 |
| T07 撞号不顺延 | 通过 | `PublishBatchIT.aComputedNumberThatIsTakenFailsAndIsNotPushedToTheNextOne` |
| T08 队列内容与命名空间 | 部分通过 | `PublishQueueIT.listsTheSkillsWithWaitingDraftsNewestFirstAndSaysWhatIsLive`、`aDraftThatCarriesItsOwnNumberIsNotOnTheQueueAndNeitherIsADeletedSkill`、`theQueueIsOnlyWhatThisCallerMayPublishFrom`；`—` 与「未命名」两态、同 skill 内多份的排序只在控制台测试与设计检查里覆盖，**没有服务端 IT** |
| T09 缺类型先选才能勾 | 通过（页面） | `tests/publish.test.tsx` 的 `cannot tick a row with no kind…`（①）、`recomputes the number the moment the kind changes on an already-typed row`（②：线上 `0.9.0` + `major` 显示 `1.0.0`，点号换成次版本后变 `0.10.0`，PATCH 写回 `minor`） |
| T10 空态 | 通过（页面） | `tests/publish.test.tsx` 的 `does not repeat the count above the empty state` |
| T11 多选 / 全选 / 取消全选 | 通过（页面，①②④）；③ 未单独断言 | `selects the newest draft of each skill…`（①与④：同组另一份不可勾、行上写明原因）、`keeps a draft somebody ticked by hand when 全选 fills in the rest`（②：手动选的那一份没被换掉，同组另一份转为不可勾） |
| T12 确认单与取消 | 通过（页面） | `lists what it is about to publish…`（逐行名字与号）、`closes on 取消 without sending anything`（取消后单子关掉，且只发生过队列读这一次请求） |
| T13 发不了的不进队列；逐条成败 | 通过（服务端） | `PublishQueueIT.theQueueIsOnlyWhatThisCallerMayPublishFrom`、`PublishBatchIT.aTargetThisCallerCannotPublishFailsAsItsOwnRowAndNotAsTheWholeBatch` |
| T14 再发一次只处理剩下的 | 部分通过（页面） | 结算后重读队列、失败的行带着原因留下由 `tests/publish.test.tsx` 断言；**"再点一次只发剩下的"没有第二次提交的断言**——它靠的是队列只留着失败那一份，而这一条已经验过 |
| T15 结果里的号是实际值 | 通过（页面） | `says the number moved, and what that did to the drafts still waiting` |
| T16 发布之后立刻可读 | **未执行** | 需要一次手工端到端（跑起来的服务端 + CLI），本轮未跑 |
| T17 超过 500 条 | 通过（服务端） | `PublishQueueIT.moreThanFiveHundredSkillsIsSaidOutLoud`；页头那句话由设计检查覆盖 |
| T18 批量接口的逐条语义 | 通过（服务端）；**到达时机那一半 2026-10-26 才成立** | `PublishBatchIT` 全部 14 例，其中 `oneSkillTwiceIsRefusedBeforeTheFirstRow`、`anEmptyBatchIsRefusedTheSameWay`、`aTargetThatIsNullIsTheCallersMistakeNotOurFault`、`theBatchRouteIsCsrfProtected`。⚠️ 这条期望里写着「四条 `row` 按请求顺序到达（**不是攒到最后一起给**）」：**顺序**由这 14 例验过，**到达时机**没有——`batch()` 把 body 整段读完再切行（`BodyHandlers.ofString()`）。那句括号里的承诺在 2026-10-26 之前**不成立**（见 §已知问题第 1 条）；现在服务端**每行推一次**，由 `PublishBatchFlushTest` 两例钉住，到达时机本身仍只在 `iterations/0001` 的一次性实测里 |
| T19 执行过程一条一条地看得见 | 通过（页面测试 + 设计检查 + 人看过 2026-10-25）；**传输那半 2026-10-26 补** | `tests/publish.test.tsx` 的 `shows the batch running, refuses Esc, and counts only the rows that came back`（流按行喂入：执行中 Esc 无效、按钮只有一颗不可点的「正在上线…」、计数 `1 / 2` 来自回来的那一行）；`console-states.mjs --only publish` 的快照断言；**滚动居中只在设计检查里**。⚠️ 这一条 2026-10-26 之前**只验了页面**：流是测试自己按行喂的，所以服务端其实没在逐行推送这件事，它量不到（见 §已知问题第 1 条）。现在由 `PublishBatchFlushTest` 补上「每行一次推送」，本机 headless 复验见 `iterations/0001` |
| T20 结算不自己关 | 通过 | 同上，加 `tests/publish.test.tsx` |
| T21 搜索与筛选 | 部分通过（页面） | 搜索由 `counts the drafts on the page…`、筛选由 `narrows to the skills that have more than one draft` 覆盖；「筛过之后已勾选的不丢」没有专门断言，其余几档（筛到空、切命名空间再筛）只在设计检查里 |
| T22 历史草稿自带的版本号 | 通过（服务端） | `PublishBatchIT.aDraftThatCarriesItsOwnNumberKeepsIt` |
| T23 CLI 的队列读 | 通过（CLI） | `cmd/skillmaster/pending_test.go` 三例、`PublishQueueIT.theAuthorPlaneAnswersTheSameQuestionForTheCli` |
| T24 连接断在中间 | 通过（页面测试 + 设计检查 + 人看过 2026-10-25） | `breaks the stream mid-batch and counts the rows nobody heard from as 没有结果`、`does not call a batch interrupted when only the done line was lost`；设计件 `&publish=broken` 的「1 份已上线，1 份失败，2 份没有结果」断言 |

**没跑的一律没有写成"通过"**：T16 需要一次手工端到端；T03 的 `major`/`patch`、T08 的两个历史空值、T11③ 还缺端到端断言。

## 已知问题

（实现后填写。每条须标 `已复现` / `部分复现` / `未验证` / `已修复`。）

1. **批量上线的行不是逐条到达的（`已修复`，2026-10-26）。** 用户报告：第一次批量上线时第一条 skill
   一直转圈，随后所有行同时变绿。实测确认：整段 NDJSON 停在容器缓冲里，直到 handler 结束才一次发出
   （`curl --trace-time`：请求发出 1.89s 后响应头与 1131 字节 body 同一刻到达）。根因是 Spring
   Framework 7 交给 `StreamingResponseBody` 的流在不设 `spring.http.response.flush.enabled` 时
   `flush()` 是空操作，所以控制器里的 `out.flush()` 一直没起作用。改成 `response.flushBuffer()`
   后本机复验：每条 row 各自一次 `Recv data`，页面逐行重绘。回归用例 `PublishBatchFlushTest`。
   记录见 [`iterations/0001`](iterations/0001-batch-publish-actually-streams.md)。

**其余：本版范围内没有已知未修缺陷。** 下面三条是执行时要注意的，都不是缺陷，记在这里免得被当成缺陷处理：

- **队列不分页**，上限是**队列读自己的常量 `ReadPublishQueueUseCase.LIMIT = 500`**（`ListAuthoredSkillsUseCase.LIMIT` 是 private，借不到；两个 500 将来要一起改）——这是 PRD「不做」里记下的取舍，验收 19 管它怎么说，不是待修的坑。
- ~~T06 有一条外部依赖~~（2026-10-25 核实后删）：唯一性按字符串比，`1.0.0-rc.1` 不占 `1.0.0`，这一条不依赖任何未定的规则。
- **T15 需要一次并发窗口**，只能在本地手工制造。它验证的是「结果回实际值」，不是并发本身。
