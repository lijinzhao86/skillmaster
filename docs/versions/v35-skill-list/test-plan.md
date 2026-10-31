# v35 · 测试方案

> **最后更新**：2026-11-01 · **所属版本**：[v35](README.md)

## 验收映射

| prd 验收 | 用例 | 由谁跑 |
|---|---|---|
| 1 一行、有省略 | 用例 1 | `console-states --only list` 的断言 |
| 2 不越界 | 用例 1 | 同上 |
| 3 一屏十条仍然成立、行高固定 | 用例 2 | `console-states --only list` 与 `--only layout` 的断言 |
| 4 省略号看得见 | 用例 3 | 人看 `skills-long` 那一态（`--only list` 现生成） |
| 5 悬停给全文 | 用例 4 | `console-states --only list` 的断言 |
| 6 卡片高度有界 | 用例 5 | `console-states --only discover` 的断言 |
| 7 400px 不横滚 | 用例 6 | 窄屏图 + 既有横滚断言 |
| 8 这一版新增的十个状态都在状态表里 | 用例 7 | `console-states --list` |
| 9 两份 `styles.css` 的截断声明逐项相同 | 用例 8 | `awk` 抽三段、去空白、比对 |
| 10 行宽 ≤640px、字号 13px、与日期留 24px | 用例 9 | `console-states --only list` 的三条断言 |
| 11 并排（1440） | 用例 10 | `console-states --only list` |
| 12 浮起（1200，列表不动） | 用例 10 | 同上 |
| 13 预览与行是同一份文字 | 用例 10 | 同上 |
| 14 开 / 换 / 关 / 清 | 用例 11 | `console-states --only list` ＋ `tests/skills-list.test.tsx` |
| 15 预览里的长文本封顶 8 行、折着时面板不滚 | 用例 12 | `console-states --only list` |
| 16 进 skill 页的门在标题上，底栏那行已拿掉 | 用例 13 | `console-states --only list` ＋ `tests/skills-list.test.tsx` |
| 17 分类／我的标签各一行、各带名字，多的收进「+N」，没有就整行不画 | 用例 14 | `console-states --only list` ＋ `tests/skills-list.test.tsx` |
| 18 列表与预览都写地址；过长时行里截、预览折行 | 用例 15 | `console-states --only list` ＋ `tests/skills-list.test.tsx` |
| 19 行上的章写档位（作者不画） | 用例 16 | `console-states --only list` ＋ `LibraryApiIT` ＋ `tests/skills-list.test.tsx` |
| 20 那一节叫「适用场景」 | 用例 17 | `console-states --only list` ＋ `tests/skills-list.test.tsx` |
| 21 每页 10/20/50、换档回第 1 页、非法值 400 | 用例 18 | `console-states --only list` ＋ `LibraryApiIT` ＋ `tests/skills-list.test.tsx` |
| 22 一页时那条脚只放还能动的控件 | 用例 19 | `console-states --only list` ＋ `tests/skills-list.test.tsx` |

## 用例

**共用前置**（下面每一条都从这里开始，各用例只写与它不同的部分）：`cd design && npm run build:console`
构建出这一版的控制台演示件；用 `node design/scripts/console-states.mjs "<产物>" design/shots/console --only <组>`
跑断言（它自己开浏览器、按每个状态自己的宽度渲染）；需要看图时它会把同名图片写进 `design/shots/console/`——**这个目录不进版本库**（产物，跑一次就有）。

**用例 1 · 长描述不越界**
前置：1440×900。
步骤：打开 `?page=skills&long=1`；量每一行 `.skill-title` 的高度与底边、它所在 `.skill` 的底边。
期望：`.skill-title` 高度等于 **1 个行高**（13px × 1.5 = 19.5px；断言按行数量，不写死像素）；**每一行**的「描述底边 − 行底边」≤ 0（改前是 +22px）；`.skill-title` 的 `scrollWidth > clientWidth`（证明真的截断了）。

**用例 2 · 一屏十条，行高固定，卡片跟着行收**
前置：再开一个 1200px 高的窗口，以及一页只有三条的那一页（`?page=skills&p=3`）。
步骤：读 960px 与 1200px 下第一行的高度、卡片底边（`#skillList` 的底，也就是分页条的底）与演示条顶之间还剩多少；再看三条那一页的行高与卡片高度。
期望：两档窗口下行高**相同**（66–67px）；960px 下卡片底在演示条顶之上、页面不滚；1200px 多出来的高度是**页面背景**——卡片跟着行收、分页条仍是卡片的最后一行；三条那一页的每一行也是 67px，卡片只有三行加一条脚。窗口矮到 **≤949px**（分页条会被演示带压住的高度）时整页退回文档、页底留 64px。

**用例 3 · 省略号看得见**
步骤：跑 `--only list`，看它写出的 `design/shots/console/skills-long.png`。
期望：被截断的那几条，描述末尾有 `…`（不是被切掉一半的字）。

**用例 4 · 悬停给全文**
步骤：`?page=skills&long=1` 里读第一条 `.skill-title` 的 `title` 属性，与夹具里那一条的完整描述比对。
期望：两者逐字相同。

**用例 5 · 卡片有界**
步骤：打开 `?page=discover&long=1`；量每张卡片的 `.result-title` 与 `.result-when` 高度。
期望：`.result-title` ≤ 3 行（14px × 1.6 × 3 ≈ 67px 上限，实测 67px），`.result-when` ≤ 2 行（13px × 1.6 × 2 ≈ 42px 上限）；卡片总高不再随描述长度线性增长。

**用例 6 · 400px 不横滚**
步骤：`?page=skills&long=1` 与 `?page=discover&long=1` 各在 400×900 下打开。
期望：`documentElement.scrollWidth - clientWidth` **≤ 0**（实测 −15px，判据是没有横向滚动）。

**用例 7 · 状态表**
步骤：`node design/scripts/console-states.mjs "<产物>" <目录> --list`。
期望：这一版新增的十个都在——`skills-long`、`skills-per50`、`skills-last-page`、`skills-preview`、`skills-preview-long`、`skills-preview-tags`、`skills-preview-address`、`skills-preview-overlay`、`skills-preview-narrow`、`discover-long`——且都能被 `--only` 前缀选中。

**用例 8 · 两份样式的截断声明一致**
步骤：按 [`technical-design.md`](technical-design.md) §接口 6 的命令，从两个文件里各抽出三条规则的决定性声明（去空白）。
期望：两边逐行相同——`min-width:0` / `overflow:hidden` / `text-overflow:ellipsis` / `white-space:nowrap`；`display:-webkit-box` / `-webkit-line-clamp:3` / `overflow:hidden`；`display:-webkit-box` / `-webkit-line-clamp:2` / `overflow:hidden`。

**用例 9 · 行宽与字号**
步骤：`?page=skills&long=1` 下读每一行 `.skill-title` 的 `max-width`、实际宽度、字号，以及与 `.skill-updated` 左边界之间的距离。
期望：`max-width` 是 `--measure-row`（640px）；实际宽度 ≤ 640px；与日期之间 ≥ **24px**；字号 **13px**，与「更新于」同档。

**用例 10 · 两种形态**
步骤：1440 与 1200 两个视口下打开 `?page=skills&sel=dev/changelog-writer`，读预览的 `position`、宽度、列表宽度、页面横向溢出；再比预览里的描述与那一行的描述。
期望：1440 → **`absolute`**（这一格从不进流）/ 380 / 列表 679（页宽 1083 − 404）/ 溢出 ≤0；1200 → `absolute` / 380 / 列表 843（＝页宽）/ 溢出 ≤0；两处描述逐字相同。

**用例 11 · 开、换、关**
步骤：点第二行的描述（不是名字）→ 看选中行与地址；按 `Esc` → 看面板与地址；用 `?sel=demo-user/no-such-skill` 打开 → 看面板与地址。
期望：点另一行换成那一条且 `sel` 跟着换；`Esc` 关掉且 `sel` 消失；**翻到第 2 页时预览仍开着、`sel` 也还在**（那一页上没有行高亮）；**被搜索筛掉、切到「已删除」时才关掉并清掉 `sel`**。

**用例 12 · 预览里的长描述**
步骤：`?page=skills&sel=dev/pdf-tools`（**默认数据**，不加 `?long=1`；949 字描述 ＋ 341 字适用场景）下量两段的高度 ÷ 行高、从 `-webkit-line-clamp` 读出封顶行数、读出那两颗按钮的名字、记下面板 `scrollHeight` 与 `clientHeight`；点第一颗，再量一遍，同时记列表第一行的高度。
期望：**描述与适用场景各 8 行**（＝封顶行数），**折着的时候面板不滚**；两颗按钮分别是「展开全文：描述」「展开全文：适用场景」；展开后行数 > 封顶行数、那颗变「收起」、**行高不变**、面板这时才自己滚。再打开一条装得下的（`?long=1&sel=dev/commit-lint`，不在 `?long=1` 换掉的前四条里，描述 1 行、适用场景 1 行）：**一颗按钮都没有**。

**用例 13 · 进 skill 页的门在标题上**
步骤：`?page=skills&sel=dev/changelog-writer` 下读预览头里的链接：`a.preview-name` 的文本与 `href`、`.preview-head a` 的个数、`a.preview-open-icon` 在不在、`a.preview-open` 在不在。
期望：名字是 `changelog-writer`；头里**两道门**（名字链接 ＋ 往外开的图标）；`a.preview-open` **不存在**——底栏那一行已经拿掉。

**用例 14 · 分类一行、我的标签一行**
步骤：`?page=skills&sel=dev/changelog-writer` 下读预览里的章：有没有状态 / 档位那几种章、分类是什么、我的标签有哪几颗；再打开 `?page=skills&sel=qian/release-notes`（**六个**我的标签），量那一行的高度是几行、读「+N」的字与 `aria-label`；点一下，再读一遍（按钮是同一颗，焦点留在原地）。
期望：状态与档位**一颗都没有**；分类「写作与翻译」**自己一行**、在我的标签那一行**上面**（两行不重叠）；两行前面的名字依次是「分类」「我的标签」。再打开长地址那一条（它既无分类也无标签）：**两行都不画**。

**用例 15 · 地址与过长的地址**
步骤：`?page=skills` 下逐行读 `.skill-name` 的 `.ns` 与全文、读 `.ns` 的计算样式；再打开最长的那一条（`sel=design-system-team/vendor-screenshot-annotator-with-region-notes-and-tickets`，命名空间 18 字 ＋ 名字 57 字）量：行里名字是否截断、行高、章还在不在；预览标题是否被截（`scrollWidth > clientWidth`）、折成几行、面板是否滚动。
期望：**自己的命名空间（`dev`）一行前缀都没有**、别人的（`design-system-team /`、`qian /` 等）照写；`.ns` 不加粗（400）且比名字淡。前端另有一条用例：会话里 namespace 与卡片一致时不画 `.ns`，不一致时画。长地址：行里**截在名字那边**、行高仍是 67、档位章与状态章都在；预览**字一个不少**（不截）、折成多行、面板不滚。

> **这一条只能在真浏览器里量。** jsdom 不算布局，`scrollHeight` 与 `clientHeight` 都是 0，所以前端用例里那颗按钮永远不出现——封顶这件事由 `console-states` 负责，前端用例只证明「按钮点了会切文字」。

**用例 16 · 行上的档位**
步骤：`?page=skills` 下列出十条的「章」文本，与 `source` 对照；服务端侧读 `/api/v1/skills?scope=library&show=all` 的卡片，看 `standing` 在拥有、授权共享、仅加入三种行上分别是什么。
期望：界面——作者的行**一颗章都没有**，别人的行是「调用／浏览／写入／维护／管理」之一；服务端——拥有 → 字段缺席，共享给我的 → **授权档与公开档取高的那一个**，仅加入 → 该 skill 公开给所有人的那一档（夹具里是 `browse`）。

**用例 17 · 预览里那一节的叫法**
步骤：打开任一条有适用场景的（`sel=dev/changelog-writer`），读预览里段的标题文字与那颗折按钮的 `aria-label`；再与 `design/voice-spec.md` 的名词表、技能页「信息」那一栏对照。
期望：标题是 **「适用场景」**（不是「什么时候该想起它」）；折按钮是「展开全文：适用场景」。三处同一个词。

**用例 18 · 每页几条**
步骤：`--only list` 跑断言；另外用 curl/浏览器读 `/web/library?per=20`、`?per=20&p=2`、`?per=50`、`?per=17`，看 `skills` 的条数与 `page_size`。
期望：默认 10 条；`per=20` 是 20 条、第 2 页是余下的 3 条；`per=50` 把 23 条装进一页；`per=17` 是 400；响应的 `page_size` 等于请求的那一档；在第 3 页换档回到第 1 页并把 `per` 写进地址。

**用例 19 · 一页时那条脚**
步骤：`--only list` 跑断言；再打开 `?page=skills&per=50`（23 条、一页）与 `?page=skills&q=pdf`（不到 10 条）。
期望：一页时只有计数与每页，页码与「上一页 / 下一页」都不画（DOM 里也没有）；不到 10 条时整条脚不画、卡片到最后一行收口、最后一行没有底边框。

## 环境与数据

- **环境**：本机 Chrome（`/Applications/Google Chrome.app`）＋ `puppeteer-core`，与 `console-states.mjs` 同一套。无头模式，用到 1440×952（十条一屏与卡片的脚）、1440×949（矮窗口退回文档）、1440×1200（行高不变）、1440×900、1200×900、400×900。
- **服务端对这两个字段的边界（2026-10-31 读代码核实）**：`name` ≤ 64 字（`SkillDocument.MAX_NAME_LENGTH`）；`description` 必填、**无长度上限**；`when_to_use` **既不必填也无上限**，DB 列是 `TEXT`（`V21` 迁移），唯一的闸是整份 frontmatter 的 **1,000,000 字符**（`FrontmatterParser.MAX_FRONTMATTER_CHARS`）。预览的封顶是按这个事实设计的，不是按现有样本。
- **数据**：演示件的夹具。长描述是两条真实 `SKILL.md` 的原文照抄、不缩写——`aiuxmasters/handoff-spec`（321 字）与 `heygen-com/cut-the-curve`（949 字，55 个样本里最长的一条）。两段长的适用场景取自 `lijinzhao/web-design`（124 字）与 `code-audit`、`coding` 的原句（演示件里拼成 341 字）。
- **清理**：夹具是源码，跑完不留状态；`design/shots/` 里的图是**产物，不进版本库**（`console-states` / `interaction-check` 每次跑都重写；`qa/role-matrix/output` 同一条规矩）。

## 结果

**执行日期**：2026-11-01（第 2 轮复核后重跑） · **基准**：分支 `design/v35-skill-list`（含每页、一页与选中态补读）

| 验收 | 结果 | 证据 |
|---|---|---|
| 1 | **通过** | 「长描述：描述恒为一行」`pass`，detail「行数 1」；「真的截断了」detail「最长 949 字，4 条被截断」 |
| 2 | **通过** | 「长描述：文字不越出行底」detail「最大越界 **−11px**（改前 +22px）」 |
| 3 | **通过** | 「列表：十条一屏放下，卡片的脚（分页条）没被底部演示条压住」`10 行，末行底 823px，卡片底 882px，演示条顶 883px，溢出 0px`（952px 视口，齐平点 951）；「列表：矮窗口退回文档，页底留 64px」`页底内边距 64px / 溢出 10px`（949px）；「列表：窗口高出来的不是行高，分页条是卡片的最后一行」`1200px 窗口：行高 67.0px → 67.0px（不变），卡片 730px、分页条在卡片内`；`--only list`、`--only layout` 汇总 `failed: 0` |
| 4 | **未执行（待人确认）** — 图已出，视觉那一步按仓库约定由人看 | `skills-long` 那一态（`--only list` 现生成）：被截断的四条末尾都有 `…`。按仓库约定，「像不像」由人看，不由检查宣布 |
| 5 | **通过** | 「长描述：悬停提示里是完整的那一段」`pass`（`title` 与元素文本逐字相同） |
| 6 | **通过** | 「说明最多 3 行」12 张、最多 3 行；「适用场景最多 2 行」最多 2 行；「卡片高度不随说明长度线性长」224–290px |
| 7 | **通过** | 「400px：发现、我的 skill（含长描述）、我的标签与公开标签都不横向溢出」`{"发现":0,"我的 skill":0,"我的标签":0,"公开标签":0,"我的 skill（长描述）":0,"发现（长描述）":0}`——**这两个 `long=1` 地址 2026-11-01 才真的进了这条循环**，此前记的通过量的不是它们 |
| 8 | **通过** | `--list` 下这一版新增的十个状态都在（`skills-long`、`skills-per50`、`skills-last-page`、`skills-preview`、`-long`、`-tags`、`-address`、`-overlay`、`-narrow`、`discover-long`）；`--only discover`（以及 `--only skills` 那批状态）产出图，`--only list` 的组检查 `failed: 0` |
| 9 | **通过** | 按 §接口 6 的命令各抽一遍，两边各 10 行、逐行相同 |
| 10 | **通过** | 「行宽不超过 --measure-row」最宽 640px / 上限 640px；「与日期之间总留得下一段空」最窄 237px；「与更新于同一档字号」13 |
| 11 | **通过** | 「1440 下与列表并排」`宽 380px，与列表相隔 24px，position absolute`；「并排时列表确实让出了地方」列表 679 / 页 1083；「页面没有被顶宽」溢出 ≤0 |
| 12 | **通过** | 「窄于 1300 改成浮在列表上」`宽 380px，与列表重叠 380px`；「浮起来时列表宽度一个像素都没动」列表 843 / 页 843，position absolute；溢出 ≤0 |
| 13 | **通过** | 「描述与那一行是同一份」`pass` |
| 14 | **通过** | 「地址里带 sel 就能直接打开」「点另一行换成那一条」「Esc 关掉，地址里的 sel 也清掉」；`tests/skills-list.test.tsx` 里预览与选中相关的 13 条用例全过（其中 3 条是 11-01 补的补读/越界/筛掉） |
| 15 | **通过** | 「949 字的描述与 341 字的适用场景都折在封顶那个行数（8 行）」`封顶 8 行 / 描述 8 行 / 适用场景 8 行`；「折着的时候面板自己不出滚动条」`面板 不滚`；「两段各自挂一颗」`展开全文：描述 \| 展开全文：适用场景`；「展开后全文都在，靠面板自己滚，不把列表顶高」`19 行 / 行高 67→67 / 面板滚动 true`；「两段都装得下时一颗按钮都不多挂」`描述 1 行 / 适用场景 1 行 / 按钮 0 颗` |
| 16 | **通过** | 「预览：标题与列表同一规则——自己的不写前缀，别人的写」`{"name":"changelog-writer","ns":null,"doors":2,"icon":true,"footer":false}`；前端用例「draws the description it was opened on, and both ways into the skill page sit on the title」全过 |
| 17 | **通过** | 「分类与我的标签各一行、各带自己的名字」`两行名字 分类\|我的标签 ／ 分类 写作与翻译 ／ 我的标签 客户项目,这周要用 ／ 两行分开 true`；「没有分类、也没有标签时，那两行整行不画」`名字（无） ／ 分类 0 ／ 标签 0`；「标签放不下时只占一行」`客户项目,团队规范,待评估 ／ +3 ／ 1 行`（3 颗 ＋ 「+3」）；「点开「+N」后全部标签都在，还能收起」`六个标签名 ／ 收起 ／ 3 行` |
| 18 | **通过** | 「别人的命名空间照实写在行首，自己的不写」`design-system-team / …、changelog-writer、pdf-tools`；「自己的命名空间不写前缀，别人的照写」；「命名空间比名字淡、也不加粗」`{"weight":"400","nameWeight":"600"}`；「长地址：行里截在名字那边，档位章与状态章仍在」`rowClipped true / rowHeight 67 / chipsKept 2`；「长地址：预览里折行、一个字都不截」`3 行 / 截断 false / 面板滚动 false`；前端用例「leaves the prefix off your own namespace…」全过 |
| 19 | **通过** | 「作者的那一行不写档位，别人的行写档位」`vendor…：写入、changelog-writer：所有者、bundle-triage：浏览、prompt-lint：调用`；`LibraryApiIT` 断言 `mine = <none> / shared = browse / joined = browse`，另一条专测「授权低于公开档」：`grant=invoke` ＋ `public_rung=browse` → `standing = browse`（`public_rung` 的 CHECK 只接受 `invoke` / `browse`，公开档写不进 `write`）；前端用例「draws your rung on a row you do not own」全过 |
| 20 | **通过** | 折按钮的读屏名字是 `展开全文：适用场景`；`voice-spec.md` §名词表里那一格就是「适用场景」，三处同一个词 |
| 21 | **通过** | 「分页：默认每页 10 条」`10 条 / 10`；「每页 20 条也是地址里的一个状态」`20 条 / 20 / 共 23 个，第 1–20 个…`；「每页 50 条把 23 条装进一页」`23 条 / 50 / 共 23 个，第 1–23 个…`；「换每页条数会回到第 1 页，并写进地址」`?page=skills&per=20 / 20 条`；`LibraryApiIT` 的 `theWebLibraryPagesAtTenByDefaultAndTakesTheThreeSizesTheConsoleOffers`（含 `per=17 → 400` 与 `page_size` 回显）；前端用例「holds only the controls that can act: one page has the count and the size, no numbers or arrows」 |
| 22 | **通过** | 「分页：多页才有页码与上一页/下一页，一页时都不画」`三页：页码 3 / 箭头 2；一页：页码 0 / 箭头 0`；「搜索：装得下就不分页」`pass`（≤10 条时整条脚不画）；「搜索：装得下就不分页，最后一行的底边框让给卡片」` / 末行底边框 0px`；前端用例同上一条的一半 |
| 审计后补 | **通过** | 审计后补上这几条断言：「三节的标题是「描述 / 适用场景 / 更新于」」`描述\|适用场景\|更新于`；「已删除那一条的名字是纯文本，没有往外开的图标」`{"open":true,"tag":"SPAN","icon":false}`；「翻到第 2 页，预览仍开着、地址里的 sel 也还在」`{"open":true,"sel":"dev/changelog-writer","name":"changelog-writer","highlighted":false}`；「被搜索筛掉 / 切到已删除之后，预览关掉、地址里的 sel 也清掉」两态均 `{"open":false,"sel":null}`；前端新增「does not link a deleted skill's title」 |

**另外跑过**：`design` 的 `npm run type-check`；`skillmaster-console` 的 `npx tsc --noEmit`、`npm test`（33 个文件 / **614 个用例全过**（10-31 记录的是 32 / 608；此后新增 6 条：翻页保持预览 1、每页条数 1、`css-tokens` 1、选中态补读 3））、`npm run build`；服务端（连本机测试库）六套 IT 共 **78 条**全过（`LibraryApiIT` 17 / `SkillSearchIT` 27 / `MyTagApiIT` 14 / `WebDiscoverSemanticIT` 8 / `PublicTagCategoryApiIT` 8 / `DiscoverRecommendedIT` 4）——`standing` 改的是共用 SQL，这几套必须一起验。

**没跑**：`check-all.mjs` 与不带 `--only` 的全量走查——按仓库规矩要人批准，这一版不需要。

## 已知问题

- **`--webkit-line-clamp` 不是标准属性名**（标准是 `line-clamp`）。目标浏览器（Chrome / Safari / Firefox 都支持前缀写法，Firefox 支持 `-webkit-` 前缀自 68 起）——本版用前缀写法是兼容面最广的那一个。若将来某处渲染器只认标准属性，会在卡片上表现为"不截断"，**不会**表现为排版炸开。
- **触屏没有 `title` 提示**——见 [`technical-design.md`](technical-design.md) §开放问题 2。
