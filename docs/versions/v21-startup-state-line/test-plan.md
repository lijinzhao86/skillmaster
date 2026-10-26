# v21-startup-state-line · 测试方案

> **最后更新**：2026-10-26
> **所属版本**：[`README.md`](README.md)

## 验收映射

把 [`prd.md`](prd.md) §验收与指标 的**每一条**映射到具体用例。

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 无交集报「搬运」（KI-1 本身） | T1 | 通过 |
| #2 全部线上报尾部阶段 | T2 | 通过 |
| #3 全部草稿报「上线」 | T3 | 通过 |
| #4 未搬优先于草稿 | T4 | 通过 |
| #5 只算我创建的（线上 + 草稿） | T5、T13、T11 | T5/T13 通过；T11 未执行 |
| #6 本机空、线上非空报「已完成」 | T6 | 通过 |
| #7 「其中 N 个已上线」（截断时「至少」） | T7 | 通过 |
| #8 四个数据源任一失败不打印 | T8 | 通过 |
| #9 截断保守 | T9 | 通过 |
| #10 行内无步号 | T10 | 通过 |
| #11 真机复现 KI-1 得到「搬运」 | T11 | 未执行 |
| #12 不改其它输出 | T12 | 通过（静态证据） |
| #13 两边都空报「搬运」 | T14 | 通过 |

## 用例

判定类用例（T1–T4、T6、T7、T9、T14）直接调 `whereYouAreLine`，喂构造好的名字集合；取数类用例（T5、T8、T13）用 `httptest` 假服务端跑 `whereYouAre`。两类的区别在测试文件里是 `TestWhereYouAreNamesTheNextStep` 与其余四个 `TestWhereYouAre…`（三个主题；失败那条另分三格子测试）。

### T1 · 本机与线上无交集时报「搬运」

- **前置**：本机名字集合 `{lark-a, lark-b}`；我的线上 `{meeting-notes}`；草稿 0。
- **步骤**：调 `whereYouAreLine`。
- **期望**：行内含「下一步：搬运。」、「其中 0 个已上线」、「我的库里有 1 条线上」，且**不含**「写提示词」。
- **判定**：同一输入在 `1.0.0` 的实现上得到「下一步：写提示词、验证，然后下线」——**这一格在旧实现上必须是红的**（已实测，见 §结果）。

### T2 · 本机名字全部在线上时报尾部阶段

- **前置**：本机 `{a, b}`；线上 `{a, b}`；草稿 0。
- **期望**：行内含「下一步：写提示词、验证，然后下线。」与「其中 2 个已上线」。

### T3 · 本机名字全部是草稿时报「上线」

- **前置**：本机 `{a}`；线上 `{}`；草稿队列 `{a: 1 份}`。
- **期望**：行内含「下一步：上线。」与「1 份草稿在等上线」，且**不含**「搬运」。

### T4 · 未搬优先于草稿

- **前置**：本机 `{a, b}`；线上 `{a}`；草稿队列 `{a: 1 份}`（`b` 未搬）。
- **期望**：行内含「下一步：搬运。」与「其中 1 个已上线」，且不含「下一步：上线」。

### T5 · 线上清单只取我创建的

- **前置**：假服务端记录收到的请求，并对 `/api/v1/me` 返回 `user.namespace=dev`、队列返回空页；`HOME` 指向空目录（本机扫描贡献 0 个）。
- **步骤**：用该服务端跑 `whereYouAre(ctx, client)`。
- **期望**：`GET /api/v1/skills` 的查询串含 `show=live` 与 `source=created`（两者并存）。
- **判定**：查询串缺任一参数即失败。这是客户端能观察到的契约——过滤在服务端做（`LibraryRepository.where()` 的 `sources` 分支），真实行为由 T11 复核。

### T6 · 本机没有副本、线上非空时报「已完成」

- **前置**：本机 `{}`；线上 `{a, b}`；草稿 0。
- **期望**：行内含「已完成：平台可用，本机没有副本。」。

### T7 · 「其中 N 个已上线」

- **前置**：本机 `{a, b, c}`；线上 `{a}`；草稿 0。
- **期望**：行内含「其中 1 个已上线」，结论为「下一步：搬运。」。
- **另**：同一夹具让 `liveTruncated=true` 时，写「其中至少 1 个已上线」。

### T8 · 任一数据源失败时整行不打印

- **前置**：四个数据源各失败一次：本机扫描（`~/.claude/skills` 不可读）、`GET /api/v1/me` 500、`GET /api/v1/author/publish-queue` 500、`GET /api/v1/skills` 500。
- **步骤**：调 `whereYouAre`。
- **期望**：每一格都是 `("", false)`。
- **判定**：不出现任何补零文本；`startup` 的 stderr 无「现在的位置」。

### T9 · 截断时不认「全部已上线」，但已能判定的照常

- **正例**：`liveTruncated=true`，线上第一页 `{a, x, y}`，本机 `{a, b}`（`b` 不在第一页）→ 「下一步：搬运。」，线上条数写「50 条以上」，括注写「其中至少 1 个已上线」。
- **反例**：截断但本机名字全部命中（线上 `{a}`、本机 `{a}`）→ 仍是尾部阶段——截断本身不影响已能判定的事实。
- **队列**：`draftsTruncated=true`、500 份 → 写「500 份以上草稿在等上线（更早的没有列进来）」。

### T10 · 行内不出现步号

- **前置**：任取 T1–T6 的一行输出。
- **期望**：不含「第」字；结论是四个固定句子之一，与 [`prd.md`](prd.md) §状态判定规则逐字一致（含标点）。

### T11 · 真机复现 KI-1

- **前置**：生产账号 `skillmaster-test`；本机 18 个候选目录按名字去重后有 33 个（2026-10-26 实测）；线上有 2 条**自己创建的**、与本机不同名的 skill；草稿 0；若账号里还有共享 / 加入的同名 skill，先记下它们的名字与命名空间。
- **步骤**：`SKILLMASTER_SERVER=https://console.skillmasterai.com skillmaster startup`，把 stderr 第一行与三处来源对照：
  - **本机**：`local.Scan` 的同一口径（本机实测与 `ls ~/.claude/skills` 相同，因为 `.agents` 的 28 个名字都在其中）；
  - **线上**：`skillmaster skills list --source created`；
  - **草稿**：`skillmaster skills pending`，只数自己命名空间的行（地址的第一段就是命名空间）。
- **期望**：行内含「下一步：搬运。」；本机数与扫描一致；「其中已上线」= 本机名字 ∩ `--source created` 的线上名字（**两个**集合）；线上条数 = 那份清单的条数；草稿份数 = 自己命名空间那部分的份数。
- **判定**：三处数字与结论互相自洽；共享 / 加入的同名 skill 不计入（账号里有时必查）。账号里没有这类 skill 时，这一格只覆盖 `source=created`，草稿的共享那一半由 T13 覆盖。**这不是自动化用例**——它证明的是真实账号上的服务端行为。

### T12 · 其它输出不变

- **前置**：与 `1.0.0` 的 `skillmaster help` 输出对比。
- **步骤**：`skillmaster help`。
- **期望**：子命令表逐行一致；`startup` 的 stdout（旅程正文）与 stderr 的其它行不变。

### T13 · 草稿只算自己命名空间的

- **前置**：`HOME` 下有一个本机 skill `theirs`；`/api/v1/me` 返回 `user.namespace=dev`；队列返回两行——别的命名空间的 `theirs`（1 份草稿）与自己命名空间的 `mine`（1 份草稿）；线上空。
- **步骤**：跑 `whereYouAre`。
- **期望**：结论「下一步：搬运。」（`theirs` 并未搬过），且草稿句写「1 份草稿在等上线」（别人的草稿不计）。
- **判定**：不筛命名空间时这一格会得到「下一步：上线。」，所以它是筛选的回归闸门。

### T14 · 本机与库里都空时报「搬运」

- **前置**：本机 `{}`；线上 `{}`；草稿 0。
- **期望**：行内含「下一步：搬运。」。

## 环境与数据

- **单测环境**：`cd skillmaster-cli && go vet ./... && go test -count=1 ./...`。无网络、无数据库：HTTP 由 `httptest` 顶替，本机目录由 `t.Setenv("HOME", t.TempDir())` 隔离——需要本机有 skill 的用例在临时 HOME 下建一个 `~/.claude/skills/<名字>/SKILL.md`。
- **数据**：全部用例的名字与数量都是构造的；唯一来自真实的是 T11 的 33 / 2 / 0，口径见 [`technical-design.md`](technical-design.md) 附录 C。T11 的本机对照必须用与 `local.Scan` 相同的口径（本机恰好与 `ls ~/.claude/skills` 相等），换一台机器时两者可能不同。
- **清理**：单测不落盘（临时 HOME 随测试删除；不可读目录的用例会先把权限改回来）；T11 只读——不新增、不删除、不上线任何 skill。

## 结果

实现在分支 `fix/startup-state-line`（2026-10-26）。单测命令：`cd skillmaster-cli && go vet ./... && go test -count=1 ./...`（全部包通过）。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `TestWhereYouAreNamesTheNextStep/local_and_live_share_no_name`。**旧实现上必红已实测**：把 `HEAD` 的 `state.go` 换回来，对同一输入输出「现在的位置：本机候选目录里 2 个 skill，库里 1 条线上、0 份草稿在等上线 —— 下一步：写提示词、验证，然后下线。」 |
| T2 | 通过 | `.../every_local_copy_is_live`（含「其中 2 个已上线」） |
| T3 | 通过 | `.../every_local_copy_is_a_draft`（断言不含「搬运」） |
| T4 | 通过 | `.../an_unmoved_copy_beats_a_waiting_draft`（断言不含「下一步：上线」） |
| T5 | 通过 | `TestWhereYouAreAsksOnlyForTheCallersOwnSkills`：假服务端捕获的查询串含 `show=live` 与 `source=created` |
| T6 | 通过 | `.../done:_nothing_local,_the_library_is_live` |
| T7 | 通过 | `.../only_some_local_copies_are_live`（「其中 1 个已上线」）与 `.../a_cut_listing_does_not_claim_the_import_is_done`（「其中至少 1 个已上线」） |
| T8 | 通过 | `TestWhereYouArePrintsNothingWhenASourceFails`（三个接口各一格）、`TestWhereYouArePrintsNothingWhenTheLocalScanFails`、`TestWhereYouArePrintsNothingWhenTheIdentityHasNoNamespace` |
| T9 | 通过 | `.../a_cut_listing_does_not_claim_the_import_is_done`、`.../a_cut_listing_still_moves_on_when_every_local_name_was_found`、`.../a_cut_queue_keeps_its_wording` |
| T10 | 通过 | 每个用例都断言行内不含「第」 |
| T11 | 未执行 | 本机没有凭据：新构建的 `startup` 输出「本机没有凭据，先登录 http://localhost:8080」。按上面的命令补跑 |
| T12 | 通过（静态证据） | `git diff --stat` 只含 `cmd/skillmaster/{state.go,state_test.go,main.go}`；`main.go` 只改了一处注释，`help` 的生成代码未动 |
| T13 | 通过 | `TestWhereYouAreCountsOnlyTheCallersOwnDrafts` |
| T14 | 通过 | `.../nothing_anywhere` |

**T11 怎么补**：在带 `skillmaster-test` 凭据的终端跑 `SKILLMASTER_SERVER=https://console.skillmasterai.com skillmaster startup`，把 stderr 第一行与 `ls ~/.claude/skills`、`skillmaster skills list`、`skillmaster skills pending` 三处对照。发布前必须补上——它是唯一验证服务端 `source=created` 过滤真实生效的用例。

## 已知问题

本版范围内暂无。KI-1 属 [`known-issues.md`](../v17-cli-startup/known-issues.md)，发布后在那里把状态改为「已修复」并注明版本。
