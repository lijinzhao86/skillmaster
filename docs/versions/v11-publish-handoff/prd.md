# v11-publish-handoff · 产品需求

> **最后更新**：2026-10-21
> **所属版本**：[`README.md`](README.md)

## 用户与场景

**谁在用**：用 agent 管 skill 的作者（agent 干活，人拍板），以及被交接的那位所有者或维护者。

**在什么场景下用**：agent 在终端里做完一版草稿，对话里说「这是新增能力，发 1.1.0」。它跑
`skillmaster version publish ns/name@<草稿>`——CLI 按 [ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md)
拒绝，并打印 `skillmaster version open ns/name@<草稿>` 让人去网页确认（`version.go:106`）。人复制这条命令：

1. 打开的是那一版的「概览」，不是动作所在的「操作」（地址只有 `?tab=versions&version=<钉>`，`version.go:1405-1410`）；
2. 到了「操作」，版本号是页面按线上版本自己算的「只是修正」建议（线上 1.0.0 → 1.0.1，
   `VersionActionPanel.vue:79-97`），与 agent 刚说的 1.1.0 不是一回事；
3. 人得先想起 agent 的结论，再点「新增能力」。

**现在的替代做法是什么**：人记住 agent 的结论，到页面上手点；或者 agent 干脆不提版本号，让人自己判断。
这不是不能用——问题是**决定在交接处丢了**：agent 已经做过「这次改动影不影响旧调用」的判断，CLI 却没有位置放它。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | `version open` 加 `--sub <record\|diff\|actions>`：把栏位写进地址；`record` 是缺省，不写 | CLI |
| 2 | `version open` 加 `--bump patch\|minor\|major`：把「这次改动影不影响旧调用」写进地址的 `bump` | CLI |
| 3 | 版本级页面专属动词（`publish` / `rollback` / `revoke` / `restore`）的拒绝句，第三行带上 `--sub actions`；`publish` 另外转发 `--bump` | CLI |
| 4 | 单版页读地址里的 `bump`：打开「操作」时预先选中对应那一项，版本号随之变成它对应的建议 | 网页 |
| 5 | 页面专属动词不再静默丢参数：不认识的参数、第二个地址都是本地用法错误；`version open` 的 `--sub` / `--bump` 非法取值同样 | CLI |
| 6 | `bump` 按版本栏参数对待：在概览 / 改动 / 操作之间保留，切到信息 / 文件 / 设置或回到清单时去掉；上线成功后也去掉 | 网页 |
| 7 | `version` 的帮助与 CLI README 同步两个新参数 | CLI 文档 |

**#5 是修一个既有缺陷，不是新增能力**：`pageOnlyVerb` 今天把第一个非 `-` 开头的参数当地址，其余一概不看
（`version.go:89-99`）。所以 `version publish …@<草稿> --bump minor` 会**静默丢掉** `--bump`——而本版的交接
恰恰要靠这个参数，静默丢等于交接说谎。

**#6 不是 #4 的自然结果**：网页有六处地址拼装各自维护一份「版本栏参数清单」，用来在离开版本栏时把它们删掉
（`SkillPage.vue` 的 `tabHref` / 设置子页 / `dirHref` / `writeListAddress` / `backToListHref`，`VersionDetail.vue`
的 `listHref`），另有一处同样的清单在 `useVersionActions.backToList`（写命令后回到清单）。`bump` 不逐一加进去，
就会跟着人跑到信息、文件、设置或版本清单。上线成功后的地址改写走 `useVersionActions.navigateVersion`——它原样
保留查询串，`bump` 要在那里删。单版页内部切栏用的是 `VersionDetail.subHref`，它同样原样保留查询串——**那一处不能动**。

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | `--release <任意版本号>`（把任意 SemVer 原样填进输入框） | 预填必须落在三个建议号之一，否则**一打开**单选那行的分类就是假话（选了「只是修正」，框里却不是下一个 patch）。设计件的输入框确实可以手改，人与框不一致是允许的中途状态；设计没有的是「打开那一刻就不一致」 | 设计稿为「打开时单选与输入框不一致」定义出含义之后 |
| 2 | 让 CLI 执行上线 / 回滚 / 下架 / 重新上架 | 它们改变调用者读到什么（[ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md) §决定 1）。本版只把交接做得更准，不越过这条线 | 不做（结构性） |
| 3 | 服务端 / 作者面 / 消费面接口的任何改动 | 本版全在客户端：CLI 拼地址、网页读地址；上线请求的形状一个字节不动 | 不做（结构性） |
| 4 | `skill open --bump` | 上线是版本级动作，skill 页没有上线表单 | 不做 |
| 5 | 单选项变化时把 `bump` 写回地址 | 地址里的 `bump` 是「打开时的预选」，不是页面状态；写回会让地址多一份与表单同步的权威，刷新时分不清谁对 | 未定 |
| 6 | 改设计件的界面（结构、类名、数值、文案） | 三个单选项本来就是设计件画好的三态，本版只在三态之间选一个，不让**打开时**输入框与选中项不一致：没有新画面、没有新句子、没有新名词 | 不做 |

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | `version open ns/name@<草稿> --sub actions`（或 `--sub diff`）打印的地址带 `sub=actions`（`sub=diff`）；以维护及以上身份打开，`actions` 落在「操作」，`diff` 在有基线时落在「改动」、没有基线时落回「概览」 | CLI 用例断言地址 + 两个值各真机打开一次（`diff` 用一份有基线可比的草稿） |
| 2 | `--sub record` 不写这个参数：地址与今天同形 | CLI 用例断言地址里没有 `sub=` |
| 3 | `version open … --sub actions --bump patch\|minor\|major` 的地址带 `bump=<档>`；以维护及以上身份打开后，三档分别选中「只是修正 / 新增能力 / 旧调用会失效」，版本号是 `1.2.4` / `1.3.0` / `2.0.0`（线上 1.2.3） | CLI 用例断言地址 + 网页组件用例断言三档 |
| 4 | 地址不带 `bump` 时行为与今天完全一致：选中「只是修正」，按钮「上线 @1.2.4」 | 网页组件用例（既有用例强化断言） |
| 5 | `version publish ns/name@<草稿> --bump minor` 的第三行逐字是 `到那一版自己的页面上去做：skillmaster version open ns/name@<草稿> --sub actions --bump minor`；零请求 | CLI 用例逐字断言 + 请求计数为 0 |
| 6 | 不带 `--bump` 时，`publish` / `rollback` / `revoke` / `restore` 的第三行都带 `--sub actions`、不带 `--bump` | CLI 用例逐条断言完整字符串 |
| 7 | `version rollback … --bump minor` 是本地用法错误（`--json` 下 `code=usage`），零请求 | CLI 用例 |
| 8 | 页面专属动词收到不认识的参数、第二个地址，或 `version open` 收到非法取值（`--sub general`、`--bump foo`）→ 本地用法错误，句中指名那个参数或取值；零请求 | CLI 用例 |
| 9 | `bump` 只在「这一版能选号」时有意义：第一次上线（没有线上版本）、v6 之前自带版本号的草稿、已上线的版本都忽略它，页面照常打开 | 网页组件用例：第一种没有单选项、版本号仍是 `1.0.0`；第二种沿用已有版本号、页面上是那行说明；第三种没有发布区块。第二种是产品里的既有状态（`VersionActionPanel.vue:49`），设计件没有这一版；本版不为它新增画面 |
| 10 | 地址里 `bump` 不是 `patch` / `minor` / `major` → 当作没有，页面照常打开 | 网页用例（`parseVersionRoute`） |
| 11 | `bump` 只在版本栏内传递：概览 / 改动 / 操作之间保留；切到信息 / 文件 / 设置、回到清单、上线成功后，地址里不再有它 | 网页用例断言各条链接与上线后的地址 |
| 12 | 两个新参数都不给时，`version open` 的地址与今天逐字相同 | CLI 用例（既有用例原样保留） |
| 13 | `--json` 形状不变：`{url, opened}`，`url` 带上新参数 | CLI 用例 |
| 14 | `version` 的帮助与 CLI README 列出 `--sub record\|diff\|actions`（注明 `record` 是缺省）与 `--bump patch\|minor\|major` | 用例断言帮助文本 + 人工核对 README |

**成立条件**：#1、#3、#5、#6、#12、#13——覆盖「落在动作那一栏」「决定真的到了页面」「交接句可复制」「四个动词都带落点」
「旧地址不变」「机器契约不变」。任何一条不成立，这一版就没有解决它要解决的问题。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 交接落点正确率 | 从拒绝句复制的那条命令，以维护及以上身份打开后，直接看到动作所在那一栏的比例 | 真机走查：`publish` / `rollback` / `revoke` / `restore` 各一次 | 4/4 |
| 交接后需要改选的比例 | 带 `--bump` 的交接，打开后还需要动单选项的比例（`patch` 是默认档，不单列） | 真机走查：`minor` / `major` 各一次 | 0/2 |

## 依赖与前置

- **外部依赖**：无。
- **内部前提**：
  - [ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md)：上线等动作只在浏览器面——本版不碰。
  - [ADR 0072](../../decisions/0072-the-handoff-points-at-the-page-that-owns-the-action.md)：交接落在拥有动作的那一页。
    本版把它从「哪一页」推进到「哪一栏 + 预选」，因此要新增一条 ADR：**交接可以携带「预选」，不能携带「执行」**
    ——它回答的是跨版本的边界（任意版本号为什么不在预选之列、单选项为什么可以）。
  - v6 的 [`technical-design.md`](../v6-version-management/technical-design.md)：§地址表补 `bump`（单版页三栏那三行 `:514-516` 已经有了，
    其中概览一行不带 `sub`）；`version open` 那一行（`:458`）补两个参数；四处「不做 `--bump`」（`:412` / `:577` / `:584` / `:677`）要改写成
    「CLI 不选号、不上线，只把作者的选择交过去」。v6 已交付，按仓库规矩走 `iterations/`。
  - v10 的 [`prd.md`](../v10-cli-lifecycle/prd.md) 范围 #5（`:52`，`version open` 的形状与拒绝句）与验收 #17（`:99`，逐字拒绝句）
    要跟着改；验收 #16 是 `skill open`，不受影响。v10 已交付，同样走 `iterations/`。
  - **设计件不改，但有一条口径要写下来**：设计件的三个单选项各自画了「选中」的样子，默认选中的是「只是修正」
    （[`design/skillmaster-控制台演示.html`](../../../design/skillmaster-控制台演示.html) `:11274` 的 `radio.checked = true` 只给 patch；
    [`design/README.md`](../../../design/README.md) 只写「预填建议号」，没有逐字记录默认选中哪一档）。地址里的 `bump`
    只是把打开时的那一面换成另外两个已经画好的状态，不新增视觉；本版以演示件为准，不改 `design/`。
- **排除了什么前置**：
  - **不需要改设计稿的界面**：三态是现成的，本版不新增画面、数值或文案；设计交付的判据（§二 四件事）因此不重跑。
  - **不需要服务端改动**：没有迁移、没有配置键、没有新路由；`bump` 只活在地址里。
  - **不需要新名词**：用的是设计件已有的「只是修正 / 新增能力 / 旧调用会失效」，`voice-spec` 不动。
