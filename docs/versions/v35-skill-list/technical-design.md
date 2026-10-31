# v35 · 技术设计

> **最后更新**：2026-11-01 · **所属版本**：[v35](README.md)

## 架构

**这一版以显示层为主，接口改动有两处**：卡片多一个 `standing`（§接口 0），只加不改；`/web/library` 多一个 `per`（§接口 0.5）。除此之外改动落在三处：

```
design/src/console/fixtures/library.ts      夹具：字段名对齐产品，并给出一份「真实长度」
design/src/console/styles.css               规则：三条截断
design/src/console/{pages/SkillsPage,components/ResultCard}.tsx   读字段、给悬停全文
        │
        └── 照搬 ──> skillmaster-console/src/{api/types.ts（只有卡片加一个字段，见 §接口 0）,
                                        styles.css, components/library/LibraryRow.tsx,
                                        components/ResultCard.tsx}
```

主从预览是这一版的后半程，它多出四个文件：

```
design/src/console/components/library/SkillPreview.tsx     预览这一格
design/src/console/styles.css                              .list-split / .skill-preview / 断点
skillmaster-console/src/components/library/LibraryRow.tsx  行多了一个开预览的契约
        │
        └── 照搬 ──> skillmaster-console/src/{components/library/SkillPreview.tsx, styles.css, pages/SkillsPage.tsx}
```

**一个组件、两种形态，两种都是绝对定位。** `.list-split` 是行容器；预览**始终** `position:absolute` ——宽的时候站进容器右侧留出来的那一列（`.list-split.is-split{padding-right:404px}`），窄的时候盖在列表上。**它从不进流**：这一版早期试过让它当第二个流内子元素，展开全文会把十条行从 67px 顶到 75px，整页跳一次（见 `design/src/console/styles.css` 的注释）。组件不知道自己在哪一种里——断点与取值都在
CSS，组件只管画内容。这是这一版唯一的结构性新增。

**设计件先改，产品照搬**（[`dev-sop`](../../../.claude/skills/dev-sop/SKILL.md) §三）。产品的 `styles.css` 与设计件那份不是同一个文件——排版风格不同（产品带空格、设计件压缩过，产品还多一条 `margin:0` 的重置）——所以判据不是"逐字相同"，而是**决定截断的那几项声明逐项相同**（见 §接口 6）。

**为什么问题在显示层而不是数据层**：`description` 是 `SKILL.md` 的正文，检索（`SkillCatalogService` 的 `RankingWeights`）按它打分，CLI 的 `invoke` 头部也带着它。给它加长度上限会同时削掉检索与 CLI 的信息量——而那两处没有"放不下"的问题。列表与卡片是唯一放不下的地方，就在那里截。

## 数据模型

**服务端模型不动。** 演示件的两条夹具类型改名字，对齐产品已经在用的字段：

| 夹具 | 改前 | 改后 | 产品那边 |
|---|---|---|---|
| `LibrarySkill` | `title: string` | **`description: string`** | `SkillCard.description`（`api/types.ts:594`） |
| `DiscoverSkill` | `title: string` | **`description: string`** | `SkillCard.description` |

> **为什么这个改名是必需的，不是顺手。** 产品的 `Skill.title` 是**版本自己的 title**（`AuthoredSkillsResponse.Skill.title`，来自 `SKILL.md` 的 `title`），而绝大多数 `SKILL.md` 没有这个字段——导入时回落到 `name`（`importer.py:220` `str(fm.get("title") or name)`）。所以产品在列表与卡片上画的是 **`description`**；夹具画的是 `title`。名字对不上，夹具就永远喂着 21 字的短句，**这一处毛病在演示件上不可能被看见**。

**真实长度的夹具**：新增一份写死的常量，取实测的两档

| 夹具 | 长度 | 出处 |
|---|---|---|
| `LONG_DESCRIPTIONS[0]` | 321 字 | `aiuxmasters/handoff-spec` 的 `description` 原文（英文） |
| `LONG_DESCRIPTIONS[1]` | 949 字 | `heygen-com/cut-the-curve` 的 `description` 原文（英文） |
| `LONG_WHEN` / `LONG_WHEN_STRESS` | 124 / 341 字 | `lijinzhao/web-design`、`code-audit`、`coding` 的 `metadata.when_to_use` 原句（后两条在演示件里拼接） |

`?long=1` 时把列表前 4 条（描述与适用场景都换）、发现前 3 张换成这两档（交替），其余不动——**这样一屏里既有长到溢出的，也有短的**，能看出行高并没有因为长条目变形。

## 接口

### 0 · 卡片多一个字段：`standing`（2026-10-31）

行上那枚章从「来源」改成「档位」，而来源三个词服务端本来就有，档位没有——补上，只加不改。

| 层 | 改什么 |
|---|---|
| SQL | `SkillPredicates.STANDING` 多选两列：`s.grant_role, s.public_rung`。`grant_role` 是这个 CTE 本来就算好的（`g.role AS grant_role`），`public_rung` 是 skill 自己的列（ADR 0048）——**SQL 里不做 CASE、也不排高低** |
| `CatalogRow` | 加两个 `String`（`grantRole`、`publicRung`），4 处构造：`LibraryRepository` 两处、`SkillCatalogRepository` 两处 |
| `SkillCardView` / `SkillCardAssembler` | 折成**一个** `standing`：`source == null \|\| "created".equals(source) ? null : GrantRole.higher(grantRole, publicRung)`。**服务端折档位的次序**只写在 `GrantRole` 一处（`GrantRole.java:12` 的类 javadoc：「a rule belongs in one place the compiler can check」）——SQL 里再写一次 `CASE` 就是第二个权威。（客户端另有一份有序的 `LADDER`（`rungs.ts`）与 CLI 的 `api.Roles`，它们只是各自的词表，不参与这次折档位。） |
| `SearchResponse.Card` | 加 `@JsonInclude(NON_NULL) String standing`：**null 时不发**，客户端据此不画章 |
| 控制台 | `SkillCard.standing?: Rung \| null`；`libraryLabels.ts` 的 `STANDING_LABEL` 用既定五个词 |

> **踩过一次**：第一版把 `CASE … COALESCE(grant_role, public_rung)` 写进了 SQL，但那不是「有效档位」。会算错的是**共享给我的**那一行：授权 `invoke`、而 skill 公开给所有人 `browse` 时，`COALESCE` 给的是 `invoke`，正确答案是 `browse`（加入的行走不到这个坑——它的 `grant_role` 恒为 null，`COALESCE` 本来就会落到 `public_rung`）。改成两个输入往上带、在 `SkillCardAssembler` 里折 `GrantRole.higher`，判据才与系统其余各处一致。

**三档怎么来的**：拥有它 → `null`（「没有前缀」已经说了同一件事，画「所有者」是重复）；共享给我的 → **授权档与公开档取高的那一个**（`GrantRole.higher(grant_role, public_rung)`）；加入的 → 这个 skill 公开给所有人的那一档（`public_rung`，ADR 0048），公开档为空（已转私有）时同样是 `null`，不画章。所以 shared / joined 行显示什么，取决于作者设的公开档位，不是一个固定词。

**推荐流不带它**：那一批的行 `source` 为 null（你没有这条关系），`standing` 也发 null——那一格问的是「**你的**档位」，把公开档塞进去是两个不同的说法共用一个字段。**发现页的搜索结果不在此列**：它的谓词不过滤 `source`，一条你已经加入的公开 skill 在结果里就是 `source='joined'`，`standing` 会是它公开给所有人的那一档（2026-11-01 审计更正——原文写成「发现页也不带」，与谓词不符）。发现与推荐卡片上的这个字段今天没有客户端读它（控制台只在库列表的行上画档位章），这条是留给以后那个读的人。

**不改的东西**：`source` 字段照旧在响应里——分页、筛选（`source=` 参数）、休眠谓词都靠它；变的只是**界面上那颗章画什么**。CLI 不读 `standing`，`skills ls` 的输出不变。


### 0.5 · 一个请求参数：`per`（2026-10-31，回写于 2026-11-01）

`GET /web/library?per=10|20|50`（缺省 10）。**三档之外是 400**（`InvalidSearchRequestException`，
`per must be one of 10, 20, 50, was …`）——不静默回落到 10，因为一个客户端没要的页大小就是一张
行数不对的表。

| 位置 | 干什么 |
|---|---|
| `WebLibraryController.list` | 收 `per`，原样交给用例 |
| `LibraryListUseCase.web` | `pageSize = per == null ? WEB_PAGE_SIZE : per`，校验在 `WEB_PAGE_SIZES` 里；offset = `(page − 1) × pageSize`（先用 `long` 算，防 `(2^31−1) × 50` 溢出成负数 `OFFSET`） |
| `OrganizationResponses.WebLibrary.of` | **回显请求的这一档**，不是常量：`?per=20` 曾经发出 20 行、却报 `"page_size": 10`，按它算末页的客户端会算出一倍（2026-11-01 审计） |
| 控制台 `api/library.ts` | `per` 只在**不等于默认 10** 时写进地址与请求——`?per=10` 与不写 `per` 是同一屏，不留第二种拼法 |

控制台这一侧的地址、默认值与「换档回第 1 页」在 `pages/SkillsPage.tsx`；分页组件不画页码与箭头的那一档
（只有一页）见 `components/Pager.tsx` 的头注释。

### 1 · 三条 CSS 规则

| 选择器 | 规则 | 为什么是这个值 |
|---|---|---|
| `.skill .skill-title`（列表行） | `white-space:nowrap; overflow:hidden; text-overflow:ellipsis; min-width:0; max-width:var(--measure-row); font-size:13px` | 一行用原生省略号就够，不必拉 `-webkit-box`；`min-width:0` 是让它在 flex 行里能让位（否则 `auto` 的最小宽度是 min-content，永远截不到）。**行宽与字号是第二轮加的**：截断之后实测那一行仍有 884px、约 118 个西文字符，读起来还是一堵墙；收到 640px（≈85 西文 / 45 中文）并把字号降到 13px（与日期同档），名字才重新成为全页唯一的大字 |
| `.result-title` | `display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:3; overflow:hidden` | 卡片并排看，高度必须有界；3 行在 349px 宽下约 72 个中文字 |
| `.result-when` | 同上，`-webkit-line-clamp:2` | 适用场景在卡片上是"为什么这条会被搜到"的一句补充，2 行封顶 |

**`--measure-row` 是本版新增的 token**（`design/src/console/styles.css` 的 `:root`）。按系统库那条判据，一个新值只有两条出路：补成 token 并说清为什么需要它，或者它是错的。640px 是前者——行宽是排版量，和颜色、间距一样要有名字，否则下次改会散成一个裸 `max-width`。

`-webkit-line-clamp` 的出处：`nextlevelbuilder/ui-ux-pro-max/data/ux-guidelines.csv:85`（*Truncate with ellipsis and expand option*，失败态写作 **Overflow or broken layout**——正是这一版要修的那个）；`wshobson/responsive-design/references/details.md:120` 在卡片里用的就是 `line-clamp-3`。

**规则只作用于列表那一格**：`.skill .skill-title`——skill 页的版本标题也用这个类名，2026-10-31 审计发现它被静默套上了 13px / 640px / 单行截断。收窄的同时**保留了它原来的基础规则**（`.skill-title{color:var(--muted);font-size:14px;margin:0}`）：只把选择器改窄，等于把那一屏的样式一起拿走——产品那边没有全局的段落重置，版本标题会掉成 15px 墨色还多出 UA 的下边距。

**行高改成定值（2026-10-31 用户定）**：`.skill` 去掉 `flex:1` 与 `max-height`，`--row-max` 这个 token 随之删掉。截断之后行高就是内容高度 **66–67px**（名字行 22.5 ＋ 间距 4 ＋ 描述 19.5 ＋ 上下内边距 20，实测；长地址那一行也是 67），窗口多高都一样。**卡片跟着行收**（`.skills` 不再 `flex:1`，2026-10-31 用户定）：一页只有三条时框就是三个行高加一条脚，多出来的是页面背景。**分页条是卡片的最后一行**（`li.pager-row` 放在 `.skills` 里），它装不下时那一格是空的、`:empty` 收掉，最后一条 skill 用 `:has(+ .pager-row:empty)` 把底边框让回来——不然卡片底边与那条线的发丝线会叠成两道。行高不变，变的只是「多出来的高度归谁」。不给 `.skill` 加 `overflow:hidden`——它会切掉 `.skill-name` 的焦点环。

### 2 · 悬停给全文

`.skill-title` 与 `.result-title` 各加一个 `title={...}` 属性，值是完整描述。

**它不是唯一的路，也不是主路**：主路是点名字进 skill 页的「信息 › 描述」（`SkillPage.tsx:1314` 画的 `skill.description` 全文）。把这条写清楚，是因为"截断不给全文"是评审里最常被点名的一条（`secondsky/design-review/SKILL.md:214`）。

### 3 · 地址参数 `?long=1`

| 地址 | 画什么 |
|---|---|
| `?page=skills&long=1` | 「我的 skill」，前 4 条换成真实长度 |
| `?page=discover&long=1` | 「发现」，前 3 张卡片换成真实长度 |

与「待上线」的 `&long=1` 同一个做法（`console-shots.mjs` 的 `publish-long-names`）：它是**演示件的替身**，不是产品参数，复制到前端时删掉。

### 4 · 地址参数 `?sel=<命名空间>/<名字>`

| 值 | 画什么 |
|---|---|
| `sel=dev/changelog-writer` | 那一行高亮，右侧出它自己的预览 |
| 没有 | 不画预览 |

**用 `ns/name` 而不是名字**：名字只在命名空间内唯一，这是产品真的寻址 skill 的方式。选中态还能被
转发出去、刷新后还在——同一条规矩，一屏的状态都在地址里。

**翻页不清 `sel`，筛掉与切页签才清**（2026-10-31 用户定）。`selected` 先在这一页的行里找（那里有
`?long=1` 换过的长描述），找不到再在**整份筛选后的列表**里找：翻到第 2 页时预览仍开着，它预览的是
「这一条」，不是「这一页」；只是那一页上没有行高亮。被搜索筛掉、切到「已删除」时那份列表里根本没有它，
才清掉——理由与「已删除那一份列表没有标签可筛」同一条：不留一个不生效的条件。

**「整份列表」在产品里是一次补读，不是手上的一份全量**（2026-11-01 补）。演示件把整批行放在内存里
（`shown.find ?? matched.find`），产品每页只有服务端给的那十条，所以「翻页时预览还在」靠**记住那一张卡片**
（按筛选键登记：筛选一变，记住的那张就不再生效）。地址里带 `sel` 而那条**不在当前页**时（刷新、或者
别人转发过来的 `?p=2&sel=…`），控制台按地址补读一次：`/web/library` 带**同一组筛选**，再按 `namespace/name` 精确命中——命中即「它还在
这个列表里」，于是开预览；问到「确实没有」才清掉 `sel`。没有搜索词时用那条的名字把范围收窄（名字命中自己，
一次请求就够）；有搜索词时 `q` 原样带上、逐页走，**最多五页（每页 50 行）**——超过就按「说不准」处理，
关面板、不清地址。

三处不能省的前提，都是审计里踩出来的：
- **判断只在「这一份数据确实属于当前筛选」时才成立**。筛选变化到新数据到达之间，`rows` 还是上一页的；
  拿它当新筛选的答案，会把旧卡片登记到新筛选键下，于是「被筛掉的那条」的预览永远不关、地址里留着一条
  不生效的 `sel`（2026-11-01 审计）。`dataKey === listKey` 是这道闸。
- **越界页的空答案不是证据**。`?p=9` 在只有三页的库上返回空页，随后页码被夹紧到末页；在夹紧之前就判定
  「它不在列表里」会把预览白关一次（2026-11-01 审计）。
- **补读按人原样的筛选走**：没有搜索词时用那一条的**名字**把范围收窄（名字一定命中自己，所以「找到」
  ⟺「还在这个列表里」，一次请求就够）；有搜索词时 `q` 原样带上、逐页走（最多五页）——把 `q` 换成名字是
  在回答另一个问题。
- **答不上来 ≠ 不在列表**：读失败（5xx / 超时）、或者五页之后还没见到，一律返回「说不准」：不登记
  「问过」，也不清 `sel`——为一次超时丢掉地址里的状态，比留着一个关着面板的 `sel` 更糟（2026-11-01 审计）。

### 5 · 组件契约

```tsx
<SkillPreview skill={row} deleted={show === 'deleted'} mine={owned(row)} onClose={closePreview} />
```

- **它只读 `row` 这一个对象**，不自己发请求、不自己找数据——所以预览里的文字与那一行永远是同一份。
- `deleted` 决定日期写「更新于」还是「删除于」，以及名字是不是链接（删掉的那一条地址打不开了）。
- `mine` 决定标题写不写命名空间前缀——与行同一条规则（`skill.namespace === account.namespace`）。
- 内容顺序固定：**地址（`命名空间 / 名字`，链接）＋ 一枚「往外开」图标** ／ `分类` ＋ 一颗胶囊 ／ `我的标签` ＋ 若干胶囊 ／ 描述 ／ `适用场景` ／ 时间。**两行胶囊各带名字**（`.preview-row-k`，12px 淡墨、定宽 4em 成列）；没有分类或没有标签时那一行整行不画。**出口在标题上，两道**；底栏那一行已经拿掉。
- **状态与档位不画**（2026-10-31 用户定）：它们在行上，那一行就亮在预览旁边。留下的两样**各占一行**：分类（`chip is-plain`，只有一个）在上，我的标签（`chip is-mine`，一串）在下——同一行里它们长得一样，分不出。标题行因此只留地址与两个按钮。
- **前缀只写别人的**：`skill.namespace === account.namespace` 时省掉 `.ns`（产品从 `useSession()` 拿 `Account.namespace`，经 `mine` 传给行与预览）；判断不依赖档位章。团队命名空间里的照写。
- **地址过长不截**：`.preview-name` 折行（`overflow-wrap:anywhere`），标题行只在真长时长高；按钮 `flex:none` 不被压扁。列表行相反——`.skill .skill-name` 省略号 ＋ `min-width:0`，章 `flex:none`，行高不变。
- **标签默认一行**：放得下几个由**量宽度**决定（标签名最长 24 字，按个数猜在窄处一定错），其余收进「+N」；点开换成换行、按钮变「收起」。量不出来（没有布局）时不裁。
- **它不改这一行是什么**：名字仍是进 skill 页的链接；那枚图标是同一件事的图标版。

### 6 · 验收那条「两边逐字相同」怎么量

```bash
for f in design/src/console/styles.css skillmaster-console/src/styles.css; do
  awk '/^\.skill \.skill-title/,/^}/' "$f" | tr -d ' ' | grep -E '^(min-width|overflow|text-overflow|white-space):'
  awk '/^\.result-title/,/^}/' "$f"       | tr -d ' ' | grep -E '^(display|-webkit-line-clamp|overflow):'
  awk '/^\.result-when/,/^}/' "$f"        | tr -d ' ' | grep -E '^(display|-webkit-line-clamp|overflow):'
done
```

**选择器必须写成实现里那一个**（`.skill .skill-title`，带空格）：规则收窄之后仍用 `/^\.skill-title/` 抽，两边各抽到 0 行，`diff` 空对空也「相同」——2026-10-31 审计就是拿这条报的。**每份文件应当抽到 10 行**（列表行 4 ＋ 卡片两段 3 ＋ 3）。

**比的是声明，不是文件。** 两个文件排版风格不同（产品带空格、设计件压缩过，产品还多一条 `margin:0` 的重置），所以去掉空白之后比这几行；两边应当逐行相同。

## 分期

**一期：截断与行宽**（做 1–6），**二期：主从预览**（做 7–10）。两者同属这一版，但二期的门槛是
一期已经绿了——预览里画的就是那行被截断的文本，截断没定下来之前，预览的排版也要跟着改两次。

| 阶段 | 做什么 | 完成判据 |
|---|---|---|
| 1 | 夹具改名 ＋ 长常量 ＋ `?long=1` | 类型检查通过；DOM 文本逐字不变 |
| 2 | 三条截断规则 ＋ `--measure-row` | `?long=1` 下不越界、行宽 ≤640 |
| 3 | `.list-split` / `.skill-preview` / 断点 | 1440 并排、1200 浮起，页面不横滚 |
| 4 | `sel` 进地址 ＋ 开/换/关 ＋ 键盘 | `console-states --only list` 的相关断言与 `tests/skills-list.test.tsx` 的预览用例 |
| 5 | 产品照搬 | 控制台测试全过 |

## 开放问题

1. **卡片要不要跟列表一样收到 1 行？**
   当前倾向：**不**。列表是"逐行比较的清单"，卡片是"各看各的东西"（`design/README.md` 对这两处的定位）；卡片的一行等于什么都不说。3 行是本版取的数，量法是 349px 宽、14px 字号下一行约 24 个中文字。

2. **触屏上 `title` 提示出不来，要不要换成可点按的展开？**
   当前倾向：**本版不做**。触屏仍有一条路——点名字进 skill 页，那里是全文。真要加，是"卡片上多一颗控件"，得先定它长什么样，那是另一版的事。

3. **预览把「信息」那一栏的内容提前，会不会长出第二套说法？**
   当前倾向：**不会，只要两边读同一组字段。** 已经做到的：文案逐字相同、数据来自同一个 `row`。
   还没做到的：**没有一条检查在比对预览与 skill 页「信息」那一栏这两边**（与列表那一行的比对有，是验收 13）——要等预览有了「改这边没改那边」的历史再说。

4. **描述为空时，第二行画什么？**
   当前倾向：**留空行，行高不变**。产品里 `description` 是 `SKILL.md` 的必填字段，空值只可能来自旧数据或接口异常；为空时把行收成一行，会让那一条在整个列表里矮一截，比留白更糟。

5. **`role="button"` 的行里嵌着一条链接，这个折衷可接受吗？**
   当前倾向：**暂时接受，记在案上。** 不做的话，键盘用户根本打不开预览；做了，读屏会念成「按钮」
   里还有一个链接。彻底的做法是把名字链接换成「开预览」，导航交给预览标题上的名字与那枚图标——
   那是把这一页的一次导航改成两次，需要单独决定，不在这一版。
