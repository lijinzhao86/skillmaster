// The console demo's state list, in its own module.
//
// `console-states.mjs` walks these to take pictures and assert the things true of every state;
// `dom-compare.mjs` walks the same list to prove a reimplementation draws the same page. One list,
// because "every state" has to mean the same thing to both.
//
// Each state: its address, the page it should land on, the nav item that should be current, and
// whether its content is tall enough to scroll.
//
// `scrolls` is not decoration. The strip along the bottom is fixed, and the obvious way to keep
// content out from under it — padding the body by the strip's height — makes *every* page scroll by
// exactly that much, including the ones with three lines on them. This is the field that catches it.
//
// It is also, in the other direction, the assertion that the list is a *page*: ten rows are meant to
// be one screen, so `skills-ready` is one of the ones that must not scroll.

export const SHOTS = [
  /* `scrolls: true` since the top bar went in: the ten-rows crossing moved to 928px, so at 900 the
     list is a document — see the media query's own comment for the arithmetic. */
  /* **发现（v14）**：公域只搜不列，所以默认那一眼是「输入关键词或选一个分类」。 */
  /* **这一张只拍第一屏。** 整页截图会把视口撑到文档高度，「为你推荐」的观察器于是真的在拍图时
     加载了下一批——图里会出现一个不是首帧的「正在加载」。首屏才是这一态要证明的东西。 */
  /* **首屏就是这一态**：页头一行 + 推荐一直铺到视口底（六张不够时首屏补齐第二批），所以它
     照常滚。`fullPage: false` 见下——整页截图会撑大视口，无限滚动的观察器就会真的去取下一批，
     图里于是多出几屏。 */
  { name: 'discover-first', query: '?page=discover', page: 'discover', nav: '发现', scrolls: true, fullPage: false },
  { name: 'discover-results', query: '?page=discover&q=PDF', page: 'discover', nav: '发现', scrolls: false },
  { name: 'discover-category', query: '?page=discover&tag=doc', page: 'discover', nav: '发现', scrolls: false },
  /* **分页条只在按字面找的那一档出现**（v19）：九个结果、一页六条，这是唯一能看见它的地址。 */
  { name: 'discover-category-paged', query: '?page=discover&tag=doc&tag=data', page: 'discover', nav: '发现', scrolls: false },
  { name: 'discover-no-results', query: '?page=discover&q=zzz', page: 'discover', nav: '发现', scrolls: false },
  /* **语义不可用（v19）**：搜索那一路断了，落回按字面找并把原因写在结果上面。 */
  { name: 'discover-degraded', query: '?page=discover&q=PDF&state=degraded', page: 'discover', nav: '发现', scrolls: false },
  /* 深链带来一个不在词表里的分类：第四句空态。 */
  { name: 'discover-bad-filter', query: '?page=discover&q=PDF&tag=nosuch&state=bad-filter', page: 'discover', nav: '发现', scrolls: false },
  { name: 'discover-joining', query: '?page=discover&q=PDF&join=busy', page: 'discover', nav: '发现', scrolls: false },
  { name: 'discover-join-failed', query: '?page=discover&q=PDF&join=failed', page: 'discover', nav: '发现', scrolls: false },
  { name: 'discover-loading', query: '?page=discover&state=loading', page: 'discover', nav: '发现', scrolls: false },
  { name: 'discover-failed', query: '?page=discover&state=failed', page: 'discover', nav: '发现', scrolls: false },
  { name: 'discover-narrow', query: '?page=discover&q=PDF', page: 'discover', nav: '发现', width: 400, height: 900, scrolls: true },
  /* 前台在手机上：页头折成两行，推荐一列。 */
  { name: 'discover-landing-narrow', query: '?page=discover', page: 'discover', nav: '发现', width: 400, height: 900, scrolls: true },
  /* **我的标签（v14）**：六态，另有搜索、新建、改名、删除四种点出来的样子。 */
  { name: 'my-tags-ready', query: '?page=my-tags', page: 'my-tags', nav: '标签', scrolls: false },
  { name: 'my-tags-search', query: '?page=my-tags&q=客户', page: 'my-tags', nav: '标签', scrolls: false },
  { name: 'my-tags-empty', query: '?page=my-tags&state=empty', page: 'my-tags', nav: '标签', scrolls: false },
  { name: 'my-tags-dormant', query: '?page=my-tags&state=dormant', page: 'my-tags', nav: '标签', scrolls: false },
  { name: 'my-tags-full', query: '?page=my-tags&state=full', page: 'my-tags', nav: '标签', scrolls: true },
  { name: 'my-tags-loading', query: '?page=my-tags&state=loading', page: 'my-tags', nav: '标签', scrolls: false },
  { name: 'my-tags-failed', query: '?page=my-tags&state=failed', page: 'my-tags', nav: '标签', scrolls: false },
  { name: 'my-tags-new', query: '?page=my-tags&new=1', page: 'my-tags', nav: '标签', scrolls: false },
  { name: 'my-tags-rename', query: '?page=my-tags&rename=evaluate', page: 'my-tags', nav: '标签', scrolls: false },
  { name: 'my-tags-describe', query: '?page=my-tags&describe=evaluate', page: 'my-tags', nav: '标签', scrolls: false },
  { name: 'my-tags-delete', query: '?page=my-tags&delete=evaluate', page: 'my-tags', nav: '标签', scrolls: false },
  /* **两份清单各一拍**：公开标签这一份是只读的集中查看，入口在信息页。 */
  { name: 'my-tags-public', query: '?page=my-tags&tab=public', page: 'my-tags', nav: '标签', scrolls: false },
  { name: 'my-tags-public-empty', query: '?page=my-tags&tab=public&public=empty', page: 'my-tags', nav: '标签', scrolls: false },
  /* **窄屏这一页现在是一份文档**（2026-10-23）：页签占了一行，六条带说明的标签加上页头，
     400×900 里比窗口多出 14px。行本身全都看得见（最后一行落在演示带上方 65px），多出来的是
     文档底部的留白——宁可让它滚，也不把说明挤回一行（那一条是 2026-10-22 用户定的）。 */
  { name: 'my-tags-narrow', query: '?page=my-tags', page: 'my-tags', nav: '标签', width: 400, height: 900, scrolls: true },
  { name: 'my-tags-public-narrow', query: '?page=my-tags&tab=public', page: 'my-tags', nav: '标签', width: 400, height: 900, scrolls: true },
  { name: 'skills-ready', query: '?page=skills', page: 'skills', nav: '我的 skill', scrolls: true },
  /* **待上线（v18）**：一张工作队列，**一行一份草稿**，树形表（勾选位 ｜ 名字 ｜ 状态 ｜ 提交于 ｜
     将以…）。默认那一屏是"齐整的队列"：七份草稿都算得出号、都能勾；缺类型与基线为预发布这两态
     在另一个命名空间（`&ns=yuhan`）与长队列里。 */
  { name: 'publish-ready', query: '?page=publish', page: 'publish', nav: '待上线', scrolls: false },
  /* 勾上两条：动作条出现，行整行染一层极浅的强调色。**这一态也不滚**：改成六列表格之后十条草稿
     只占一屏的一半，动作条吸在底上不再把文档顶出去（上一版是两行一行、要滚 23px）。 */
  { name: 'publish-picked', query: '?page=publish&picked=d-chg-2,d-api-1', page: 'publish', nav: '待上线', scrolls: false },
  /* **全选之后**：多份草稿的 skill 勾它最新提交的那一份（这个夹具里是 4 份、两个 skill 取了最新
     的一份），动作条同时说清还差什么。 */
  { name: 'publish-select-all', query: '?page=publish&picked=d-perf-1,d-log-3,d-chg-2,d-api-1', page: 'publish', nav: '待上线', scrolls: false },
  /* **另一个命名空间里的样子**：命名空间是页头的单选，一次只看一个（`yuhan` 那两份都缺版本类型）。 */
  { name: 'publish-other-ns', query: '?page=publish&ns=yuhan&only=step', page: 'publish', nav: '待上线', scrolls: false },
  /* **长队列**：24 个 skill。搬运之后队列就是这个规模，十条以内看不出分组、动作条与表头会不会散架。 */
  { name: 'publish-long', query: '?page=publish&many=1', page: 'publish', nav: '待上线', scrolls: true },
  /* **名字很长**：一个 skill 名 50 多个字符、草稿名一整句。定宽的格子里，长名字是截断还是折行。 */
  { name: 'publish-long-names', query: '?page=publish&long=1', page: 'publish', nav: '待上线', scrolls: false },
  /* **基线是预发布版本**（`&ns=yuhan`）：`incident-notes` 的线上是 `2.0.0-rc.2`，而且**故意没选
     类型**——它证明「基线不可用」排在「缺类型」之前：行上说「基线为预发布版本」，也没有那颗选择器。 */
  { name: 'publish-prerelease', query: '?page=publish&ns=yuhan', page: 'publish', nav: '待上线', scrolls: false },
  /* **被截断的队列**（`&cut=1`）：一次最多取 500 个 skill。这一态真的铺满 500 行，因为那句提示
     说的是这张单子的边界——挂在十条的单子上，它会变成一句自相矛盾的话。`fullPage:false`：整页
     截图会把这个状态拉成一万像素高的长条，而要看的是页头那句话与列表的开头。 */
  { name: 'publish-cut', query: '?page=publish&cut=1', page: 'publish', nav: '待上线', scrolls: true, fullPage: false },
  /* 那句话在 400px 上要跟着折行（页头是 `flex-wrap`），不能把「待上线」这颗标题挤掉。 */
  { name: 'publish-cut-narrow', query: '?page=publish&cut=1', page: 'publish', nav: '待上线', width: 400, height: 900, scrolls: true, fullPage: false },
  /* **两类历史行**（`&legacy=1`）：`runbook-sync` 的线上版本没有号（状态格写 `—`，行上说「线上
     版本没有号，需指定版本号」，不给类型选择器）；`capacity-notes` 的草稿没有名字（写「未命名」）。 */
  { name: 'publish-legacy', query: '?page=publish&legacy=1', page: 'publish', nav: '待上线', scrolls: false },
  /* 改类型：**版本号本身就是那颗菜单的触发器**，点开能换一档，号跟着重算。
     `fullPage: false` 是必须的——整页截图会把视口撑到文档高度，而菜单在 resize 时关闭
     （`MenuSelect` 自己的规矩），图里就只剩一个没打开的页面。 */
  { name: 'publish-type-menu', query: '?page=publish&types=d-api-1', page: 'publish', nav: '待上线', scrolls: false, fullPage: false },
  /* **确认单 → 执行中 → 结算，是同一张单子的三段**（2026-10-25 定）。四份里有一条会撞号，
     所以三段加起来讲的是一个故事：批了 4 份，3 份落定，1 份卡住。
     确认单逐条列名字与版本号——ADR 0090 的可接受性条件，不能简化成一句总数。 */
  { name: 'publish-confirm', query: '?page=publish&picked=d-perf-1,d-log-3,d-chg-2,d-api-1&publish=confirm', page: 'publish', nav: '待上线', scrolls: false },
  /* 执行中：走完两条、第三条在转、第四条还等着。号是**已经落定的实际值**，没轮到的仍是预演（淡一档）。 */
  { name: 'publish-running', query: '?page=publish&picked=d-perf-1,d-log-3,d-chg-2,d-api-1&publish=running', page: 'publish', nav: '待上线', scrolls: false },
  /* **长批次**：17 份。执行中单子比框高，正在走的那一条被送到正中；结算同样要滚着看，从第一条开始。 */
  { name: 'publish-many-running', query: '?page=publish&many=1&publish=running', page: 'publish', nav: '待上线', scrolls: true, fullPage: false },
  { name: 'publish-many-done', query: '?page=publish&many=1&publish=done', page: 'publish', nav: '待上线', scrolls: true, fullPage: false },
  /* 结算：标题一句话说完成什么样，失败的那一行留着原因；失败的那一份还在队列里。 */
  { name: 'publish-done', query: '?page=publish&picked=d-perf-1,d-log-3,d-chg-2,d-api-1&publish=done', page: 'publish', nav: '待上线', scrolls: false },
  /* **流断了**（2026-10-25 定）：前两条有结果、后两条没有。它既不是「全成功」也不是「全失败」，
     所以单子上那两行回到"还没轮到"的样子，标题把没结果的那几条如实算进去。 */
  { name: 'publish-broken', query: '?page=publish&picked=d-perf-1,d-log-3,d-chg-2,d-api-1&publish=broken', page: 'publish', nav: '待上线', scrolls: false },
  /* 发过一次之后：成功的离开队列，失败的留下并带着原因；上面那条横幅说清「号变了」。 */
  { name: 'publish-partial', query: '?page=publish&publish=partial', page: 'publish', nav: '待上线', scrolls: false },
  { name: 'publish-empty', query: '?page=publish&state=empty', page: 'publish', nav: '待上线', scrolls: false },
  { name: 'publish-loading', query: '?page=publish&state=loading', page: 'publish', nav: '待上线', scrolls: false },
  { name: 'publish-failed', query: '?page=publish&state=failed', page: 'publish', nav: '待上线', scrolls: false },
  { name: 'publish-narrow', query: '?page=publish', page: 'publish', nav: '待上线', width: 400, height: 900, scrolls: true },
  /* **执行单在窄屏折成两行**：名字占一整行，号与状态落到第二行右端（2026-10-25 定）。四样并排
     会把草稿名挤成「补…」，而这一行要能认出是哪一份。 */
  { name: 'publish-running-narrow', query: '?page=publish&picked=d-perf-1,d-log-3,d-chg-2,d-api-1&publish=running', page: 'publish', nav: '待上线', width: 400, height: 900, scrolls: true, fullPage: false },
  /* 库列表在窄屏也要折行，不许横向滚（搜索 + 两个筛选 + 排序 + 新建）。 */
  { name: 'skills-narrow', query: '?page=skills', page: 'skills', nav: '我的 skill', width: 400, height: 900, scrolls: true },
  { name: 'skills-empty', query: '?page=skills&state=empty', page: 'skills', nav: '我的 skill', scrolls: false },
  /* 库里有条目、但一条都显示不出来：页签还在，这一份列表空着。 */
  { name: 'skills-dormant', query: '?page=skills&state=dormant', page: 'skills', nav: '我的 skill', scrolls: false },
  { name: 'skills-tags', query: '?page=skills&tag=dev&my-tag=client', page: 'skills', nav: '我的 skill', scrolls: false },
  { name: 'skills-loading', query: '?page=skills&state=loading', page: 'skills', nav: '我的 skill', scrolls: false },
  { name: 'skills-failed', query: '?page=skills&state=failed', page: 'skills', nav: '我的 skill', scrolls: false },
  /* `?page=skill` 落在「信息」——那一屏一屏装得下（内容块在没点文件时不出现），所以它不滚动。 */
  { name: 'skill-ready', query: '?page=skill', page: 'skill', nav: '我的 skill', scrolls: false },
  /* **六种库归属里另外的五种（v14）**：页头的加入 / 移除，与「我的标签」那一区都跟着变。 */
  { name: 'skill-shared', query: '?page=skill&in=shared', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'skill-joined', query: '?page=skill&in=joined', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'skill-join', query: '?page=skill&in=visible', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'skill-join-blocked', query: '?page=skill&in=blocked&offline=1', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'skill-dormant', query: '?page=skill&in=dormant&offline=1', page: 'skill', nav: '我的 skill', scrolls: false },
  /* 多选菜单是 `position:fixed`：整页截图会先把视口撑大，菜单收到 resize 就关了。 */
  { name: 'skill-categories', query: '?page=skill&edit=categories', page: 'skill', nav: '我的 skill', scrolls: false, fullPage: false },
  { name: 'skill-public-tags', query: '?page=skill&edit=public-tags', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'skill-my-tags', query: '?page=skill&edit=my-tags', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'skill-remove-confirm', query: '?page=skill&in=joined&remove=1', page: 'skill', nav: '我的 skill', scrolls: false },
  /* 信息页在 400px：右栏折到正文下面，两组标签跟着走。 */
  { name: 'skill-narrow', query: '?page=skill&in=joined', page: 'skill', nav: '我的 skill', width: 400, height: 900, scrolls: true },
  /* **文件栏**：这一版的文件清单与提交行。提交行右端的版本名是进这一版的链接（2026-10-20 定）。 */
  { name: 'skill-files', query: '?page=skill&tab=files', page: 'skill', nav: '我的 skill', scrolls: true },
  { name: 'skill-versions', query: '?page=skill&tab=versions', page: 'skill', nav: '我的 skill', scrolls: false },
  /* **过滤与搜索什么都没匹配上。** 一句话 + 一个清除筛选，不是一张空白卡片（2026-10-20 定）。 */
  { name: 'skill-versions-empty', query: '?page=skill&tab=versions&filter=live&q=zzz', page: 'skill', nav: '我的 skill', scrolls: false },
  /* **导入（v9）。** 两个入口共用一张表：版本列表给这个 skill 加一版，「我的 skill」页把
     压缩包带进来。没有后端，所以打开态与一条具名拒绝都走地址（`import=open|error`）。 */
  /* 「新建版本」是一颗菜单（v9）：在线编辑与导入压缩包是同一个动作的两种来源。
     **文件栏表头那颗是同一个菜单**（2026-10-21），所以它也要有自己的一帧。 */
  { name: 'versions-new-menu', query: '?page=skill&tab=versions&new=open', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'skill-files-new-menu', query: '?page=skill&tab=files&new=open', page: 'skill', nav: '我的 skill', scrolls: true },
  { name: 'versions-import', query: '?page=skill&tab=versions&import=open', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'versions-import-error', query: '?page=skill&tab=versions&import=error', page: 'skill', nav: '我的 skill', scrolls: false },
  /* 压缩包里的名字在该下载方还原不了（v33）：点名文件、最多 3 个，再多的只数个数。 */
  { name: 'versions-import-bad-names', query: '?page=skill&tab=versions&import=bad-names', page: 'skill', nav: '我的 skill', scrolls: false },
  /* 只有一个文件出问题时是另一句话（v33）：一句话写完，没有列表。 */
  { name: 'versions-import-bad-name-single', query: '?page=skill&tab=versions&import=bad-name-single', page: 'skill', nav: '我的 skill', scrolls: false },
  /* 幂等重放不是失败：同一句话不染红，只说出那一版的身份并给出入口。 */
  { name: 'versions-import-exists', query: '?page=skill&tab=versions&import=exists', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'versions-import-exists-draft', query: '?page=skill&tab=versions&import=exists-draft', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'versions-import-exists-hidden', query: '?page=skill&tab=versions&import=exists-hidden', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'versions-import-mismatch', query: '?page=skill&tab=versions&import=mismatch', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'skills-import', query: '?page=skills&import=open', page: 'skills', nav: '我的 skill', scrolls: true },
  { name: 'skills-import-error', query: '?page=skills&import=error', page: 'skills', nav: '我的 skill', scrolls: true },
  { name: 'skills-import-bad-names', query: '?page=skills&import=bad-names', page: 'skills', nav: '我的 skill', scrolls: true },
  { name: 'skills-import-exists', query: '?page=skills&import=exists', page: 'skills', nav: '我的 skill', scrolls: true },
  { name: 'skills-import-exists-draft', query: '?page=skills&import=exists-draft', page: 'skills', nav: '我的 skill', scrolls: true },
  { name: 'skills-import-exists-hidden', query: '?page=skills&import=exists-hidden', page: 'skills', nav: '我的 skill', scrolls: true },
  { name: 'skills-import-deleted', query: '?page=skills&import=deleted', page: 'skills', nav: '我的 skill', scrolls: true },
  /* **新建 skill（v9）**：菜单、在线编辑的对话框、以及名字已被占用那一态。 */
  { name: 'skills-new-menu', query: '?page=skills&new=open', page: 'skills', nav: '我的 skill', scrolls: true },
  { name: 'skills-new-skill', query: '?page=skills&newskill=open', page: 'skills', nav: '我的 skill', scrolls: true },
  { name: 'skills-new-skill-error', query: '?page=skills&newskill=error', page: 'skills', nav: '我的 skill', scrolls: true },
  { name: 'skills-new-skill-deleted', query: '?page=skills&newskill=deleted', page: 'skills', nav: '我的 skill', scrolls: true },
  /* 弹窗在手机上也要放得下：一条 400px 的窄帧，与编辑页那条同一个宽度。 */
  { name: 'versions-import-narrow', query: '?page=skill&tab=versions&import=open', page: 'skill', nav: '我的 skill', width: 400, height: 900, scrolls: true },
  /* **版本 tab 的单版页。** 版本 tab 是唯一清单，而且只导航；地址钉住一版时，它从清单换成
     那一版，所有改变版本状态的动作都在那一版的「操作」栏里（ADR 0054）。图按三栏分开：
     概览（线上）、操作（草稿的上线 + 丢弃、线上回滚、历史版本的上线 / 下架、
     已下架的重新上架）、改动（线上对照上一版、草稿对照线上），以及不存在的版本钉。 */
  { name: 'version-overview', query: '?page=skill&tab=versions&version=1.2.3', page: 'skill', nav: '我的 skill', scrolls: true },
  /* **草稿的概览。** 地址带着它的 8 位短码；右侧事实栏的「地址」是唯一能整串复制进 CLI 的一行。 */
  { name: 'version-draft', query: '?page=skill&tab=versions&version=7K3M9QX2', page: 'skill', nav: '我的 skill', scrolls: true },
  { name: 'version-publish', query: '?page=skill&tab=versions&version=7K3M9QX2&sub=actions', page: 'skill', nav: '我的 skill', scrolls: true },
  { name: 'version-rollback', query: '?page=skill&tab=versions&version=1.2.3&sub=actions', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'version-changes', query: '?page=skill&tab=versions&version=1.2.3&sub=diff', page: 'skill', nav: '我的 skill', scrolls: true },
  { name: 'version-changes-draft', query: '?page=skill&tab=versions&version=7K3M9QX2&sub=diff', page: 'skill', nav: '我的 skill', scrolls: true },
  /* **清单上方的筛选**：按路径收窄左边那份清单，右栏不动（2026-10-20 定）。 */
  { name: 'version-changes-filtered', query: '?page=skill&tab=versions&version=7K3M9QX2&sub=diff&fq=split', page: 'skill', nav: '我的 skill', scrolls: true },
  /* **大改动**：清单先到、内容按需、单文件分段（2026-10-20 定）。三张图分别是首屏（正文
     一个都没取）、单文件模式里一个文件加载后（124 个文件 / 3,200 行的长文件）与只看文件列表。 */
  { name: 'version-big', query: '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&load=1', page: 'skill', nav: '我的 skill', scrolls: true },
  { name: 'version-big-pending', query: '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&manual=1', page: 'skill', nav: '我的 skill', scrolls: true },
  { name: 'version-big-list', query: '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&list=1', page: 'skill', nav: '我的 skill', scrolls: true },
  { name: 'version-revoke', query: '?page=skill&tab=versions&version=1.2.2&sub=actions', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'version-revoked', query: '?page=skill&tab=versions&version=1.2.1&sub=actions', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'version-missing', query: '?page=skill&tab=versions&version=nope', page: 'skill', nav: '我的 skill', scrolls: false },
  /* **编辑版本（v8）。** 入口在版本列表的「新建版本」与草稿的「编辑文件」。这一页有 25 个
     状态，因为它的任务就是处理那几件会出岔子的事：新版本、已有草稿、名称与说明的失焦即写与
     名称清空、未保存、冲突、内容重复、二进制、新建文件/文件夹与路径错误、空文件夹、重命名、
     删除确认、离开确认、保存失败、加载中、没有这一版。最后一张窄屏在 400px 下拍：树收成一条
     可展开的栏，编辑器占满宽度。 */
  { name: 'editor-new', query: '?page=editor', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-blank', query: '?page=editor&state=blank', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-base-draft', query: '?page=editor&state=base-draft', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-draft', query: '?page=editor&state=draft', page: 'editor', nav: '我的 skill', scrolls: false },
  /* 名称与说明是版本的标签，在页头里就地改、失焦即写（2026-10-20 定）。这几张钉住结果：
     写成的那一句、名称写成、以及两种失败（清空恢复 / 写入失败）。 */
  { name: 'editor-note', query: '?page=editor&state=note', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-name', query: '?page=editor&state=name', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-name-error', query: '?page=editor&state=name-error', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-label-error', query: '?page=editor&state=label-error', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-dirty', query: '?page=editor&state=dirty', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-conflict', query: '?page=editor&state=conflict', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-duplicate', query: '?page=editor&state=duplicate', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-duplicate-draft', query: '?page=editor&state=duplicate-draft', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-binary', query: '?page=editor&state=binary', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-new-file', query: '?page=editor&state=new-file', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-new-file-error', query: '?page=editor&state=new-file-error', page: 'editor', nav: '我的 skill', scrolls: false },
  /* 跨系统的名字（v33）：`CON.md` 在 Windows 上是设备名，行内标红、按 Enter 不创建。 */
  { name: 'editor-new-file-bad-name', query: '?page=editor&state=new-file-bad-name', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-new-folder', query: '?page=editor&state=new-folder', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-empty-folder', query: '?page=editor&state=empty-folder', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-rename', query: '?page=editor&state=rename', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-delete', query: '?page=editor&state=delete', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-leave', query: '?page=editor&state=leave', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-error', query: '?page=editor&state=error', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-loading', query: '?page=editor&state=loading', page: 'editor', nav: '我的 skill', scrolls: false },
  { name: 'editor-missing', query: '?page=editor&state=missing', page: 'editor', nav: '我的 skill', scrolls: false },
  /* 不能编辑：页面显示与「没有这一版」同一屏（读接口仍返回内容，第一次写 403）。 */
  { name: 'editor-denied', query: '?page=editor&state=denied', page: 'editor', nav: '我的 skill', scrolls: false },
  /* The one shot that is not the console's 1440 column: a phone. `width` is read by the loop; see
     the viewport line there. */
  { name: 'editor-narrow', query: '?page=editor&state=draft', page: 'editor', nav: '我的 skill', width: 400, height: 900, scrolls: true },
  /* 只有草稿、从没上线过：页头与信息栏都说未上线。演示件用 `no-live=1` 造出这一态。 */
  { name: 'skill-no-live', query: '?page=skill&tab=files&no-live=1', page: 'skill', nav: '我的 skill', scrolls: true },
  /* **整个 skill 下线。** 版本与指针都不动；页头多一枚「已下线」，设置页危险区第一行
     变成「恢复服务」。 */
  { name: 'skill-offline', query: '?page=skill&tab=settings&sub=general&offline=1', page: 'skill', nav: '我的 skill', scrolls: true },
  { name: 'skill-missing', query: '?page=skill&state=missing', page: 'skill', nav: '我的 skill', scrolls: false },
  { name: 'skill-settings-collaborators', query: '?page=skill&tab=settings&sub=collaborators', page: 'skill', nav: '我的 skill', scrolls: true },
  /* 可见性三张卡 + 危险区在 960 高的窗口里放不下，这一页本来就滚。 */
  { name: 'skill-settings-visibility', query: '?page=skill&tab=settings&sub=general&vis=public-invoke', page: 'skill', nav: '我的 skill', scrolls: true },
  { name: 'account-ready', query: '?page=account', page: 'account', nav: '账号', scrolls: false },
  { name: 'account-anonymous', query: '?page=account&state=anonymous', page: 'account', nav: '账号', scrolls: false },
  { name: 'account-unknown', query: '?page=account&state=unknown', page: 'account', nav: '账号', scrolls: false },
  { name: 'keys-ready', query: '?page=keys', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  { name: 'keys-new', query: '?page=keys&state=new', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  { name: 'keys-empty', query: '?page=keys&state=empty', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  /* These two carry a key, because a moment with nothing to be about falls back to 正常 now — and the
     same pair is what the demo strip's 已创建 / 已续期 options put in the address. */
  { name: 'keys-created', query: '?page=keys&state=created&made-id=k6&made-name=演示密钥&made-scope=read&made-expires=2027-01-13&made-note=' + encodeURIComponent('从演示条打开的，不是真的签出来的'), page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  { name: 'keys-rotated', query: '?page=keys&state=rotated&rotated=k2', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  { name: 'keys-failed', query: '?page=keys&state=failed', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  /* The filter is an address, so both ends of it are worth a state: one slice, and the whole list. */
  { name: 'keys-filter-revoked', query: '?page=keys&filter=revoked', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  /* The fullest state of this page: five rows, the four counts and the filter box. It fitted before
     the top bar and it fits again now — the bar added 48px of chrome and the panel gave 12 of them
     back by dropping a top margin it no longer needed. */
  { name: 'keys-filter-all', query: '?page=keys&filter=all', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  /* 登录设备：与 API 密钥同一个父项（参照产品的 `Access` 组装的也是这两类东西）。 */
  { name: 'sessions-ready', query: '?page=sessions', page: 'sessions', nav: '登录设备', navParent: '权限', scrolls: false },
  { name: 'sessions-empty', query: '?page=sessions&state=empty', page: 'sessions', nav: '登录设备', navParent: '权限', scrolls: false },
  { name: 'sessions-failed', query: '?page=sessions&state=failed', page: 'sessions', nav: '登录设备', navParent: '权限', scrolls: false },
]
