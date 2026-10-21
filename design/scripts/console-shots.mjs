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
  { name: 'skills-ready', query: '?page=skills', page: 'skills', nav: '管理', scrolls: true },
  { name: 'skills-empty', query: '?page=skills&state=empty', page: 'skills', nav: '管理', scrolls: false },
  { name: 'skills-loading', query: '?page=skills&state=loading', page: 'skills', nav: '管理', scrolls: false },
  { name: 'skills-failed', query: '?page=skills&state=failed', page: 'skills', nav: '管理', scrolls: false },
  /* `?page=skill` 落在「信息」——那一屏一屏装得下（内容块在没点文件时不出现），所以它不滚动。 */
  { name: 'skill-ready', query: '?page=skill', page: 'skill', nav: '管理', scrolls: false },
  /* **文件栏**：这一版的文件清单与提交行。提交行右端的版本名是进这一版的链接（2026-10-20 定）。 */
  { name: 'skill-files', query: '?page=skill&tab=files', page: 'skill', nav: '管理', scrolls: true },
  { name: 'skill-versions', query: '?page=skill&tab=versions', page: 'skill', nav: '管理', scrolls: false },
  /* **过滤与搜索什么都没匹配上。** 一句话 + 一个清除筛选，不是一张空白卡片（2026-10-20 定）。 */
  { name: 'skill-versions-empty', query: '?page=skill&tab=versions&filter=live&q=zzz', page: 'skill', nav: '管理', scrolls: false },
  /* **导入（v9）。** 两个入口共用一张表：版本列表给这个 skill 加一版，「我的 skill」页把
     压缩包带进来。没有后端，所以打开态与一条具名拒绝都走地址（`import=open|error`）。 */
  /* 「新建版本」是一颗菜单（v9）：在线编辑与导入压缩包是同一个动作的两种来源。
     **文件栏表头那颗是同一个菜单**（2026-10-21），所以它也要有自己的一帧。 */
  { name: 'versions-new-menu', query: '?page=skill&tab=versions&new=open', page: 'skill', nav: '管理', scrolls: false },
  { name: 'skill-files-new-menu', query: '?page=skill&tab=files&new=open', page: 'skill', nav: '管理', scrolls: true },
  { name: 'versions-import', query: '?page=skill&tab=versions&import=open', page: 'skill', nav: '管理', scrolls: false },
  { name: 'versions-import-error', query: '?page=skill&tab=versions&import=error', page: 'skill', nav: '管理', scrolls: false },
  /* 幂等重放不是失败：同一句话不染红，只说出那一版的身份并给出入口。 */
  { name: 'versions-import-exists', query: '?page=skill&tab=versions&import=exists', page: 'skill', nav: '管理', scrolls: false },
  { name: 'versions-import-exists-draft', query: '?page=skill&tab=versions&import=exists-draft', page: 'skill', nav: '管理', scrolls: false },
  { name: 'versions-import-exists-hidden', query: '?page=skill&tab=versions&import=exists-hidden', page: 'skill', nav: '管理', scrolls: false },
  { name: 'versions-import-mismatch', query: '?page=skill&tab=versions&import=mismatch', page: 'skill', nav: '管理', scrolls: false },
  { name: 'skills-import', query: '?page=skills&import=open', page: 'skills', nav: '管理', scrolls: true },
  { name: 'skills-import-error', query: '?page=skills&import=error', page: 'skills', nav: '管理', scrolls: true },
  { name: 'skills-import-exists', query: '?page=skills&import=exists', page: 'skills', nav: '管理', scrolls: true },
  { name: 'skills-import-exists-draft', query: '?page=skills&import=exists-draft', page: 'skills', nav: '管理', scrolls: true },
  { name: 'skills-import-exists-hidden', query: '?page=skills&import=exists-hidden', page: 'skills', nav: '管理', scrolls: true },
  { name: 'skills-import-deleted', query: '?page=skills&import=deleted', page: 'skills', nav: '管理', scrolls: true },
  /* **新建 skill（v9）**：菜单、在线编辑的对话框、以及名字已被占用那一态。 */
  { name: 'skills-new-menu', query: '?page=skills&new=open', page: 'skills', nav: '管理', scrolls: true },
  { name: 'skills-new-skill', query: '?page=skills&newskill=open', page: 'skills', nav: '管理', scrolls: true },
  { name: 'skills-new-skill-error', query: '?page=skills&newskill=error', page: 'skills', nav: '管理', scrolls: true },
  { name: 'skills-new-skill-deleted', query: '?page=skills&newskill=deleted', page: 'skills', nav: '管理', scrolls: true },
  /* 弹窗在手机上也要放得下：一条 400px 的窄帧，与编辑页那条同一个宽度。 */
  { name: 'versions-import-narrow', query: '?page=skill&tab=versions&import=open', page: 'skill', nav: '管理', width: 400, height: 900, scrolls: true },
  /* **版本 tab 的单版页。** 版本 tab 是唯一清单，而且只导航；地址钉住一版时，它从清单换成
     那一版，所有改变版本状态的动作都在那一版的「操作」栏里（ADR 0054）。图按三栏分开：
     概览（线上）、操作（草稿的发布 + 丢弃、线上回滚、历史版本的上线 / 下架、
     已下架的重新上架）、改动（线上对照上一版、草稿对照线上），以及不存在的版本钉。 */
  { name: 'version-overview', query: '?page=skill&tab=versions&version=1.2.3', page: 'skill', nav: '管理', scrolls: true },
  /* **草稿的概览。** 地址带着它的 8 位短码；右侧事实栏的「地址」是唯一能整串复制进 CLI 的一行。 */
  { name: 'version-draft', query: '?page=skill&tab=versions&version=7K3M9QX2', page: 'skill', nav: '管理', scrolls: true },
  { name: 'version-publish', query: '?page=skill&tab=versions&version=7K3M9QX2&sub=actions', page: 'skill', nav: '管理', scrolls: true },
  { name: 'version-rollback', query: '?page=skill&tab=versions&version=1.2.3&sub=actions', page: 'skill', nav: '管理', scrolls: false },
  { name: 'version-changes', query: '?page=skill&tab=versions&version=1.2.3&sub=diff', page: 'skill', nav: '管理', scrolls: true },
  { name: 'version-changes-draft', query: '?page=skill&tab=versions&version=7K3M9QX2&sub=diff', page: 'skill', nav: '管理', scrolls: true },
  /* **清单上方的筛选**：按路径收窄左边那份清单，右栏不动（2026-10-20 定）。 */
  { name: 'version-changes-filtered', query: '?page=skill&tab=versions&version=7K3M9QX2&sub=diff&fq=split', page: 'skill', nav: '管理', scrolls: true },
  /* **大改动**：清单先到、内容按需、单文件分段（2026-10-20 定）。三张图分别是首屏（正文
     一个都没取）、单文件模式里一个文件加载后（124 个文件 / 3,200 行的长文件）与只看文件列表。 */
  { name: 'version-big', query: '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&load=1', page: 'skill', nav: '管理', scrolls: true },
  { name: 'version-big-pending', query: '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&manual=1', page: 'skill', nav: '管理', scrolls: true },
  { name: 'version-big-list', query: '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&list=1', page: 'skill', nav: '管理', scrolls: true },
  { name: 'version-revoke', query: '?page=skill&tab=versions&version=1.2.2&sub=actions', page: 'skill', nav: '管理', scrolls: false },
  { name: 'version-revoked', query: '?page=skill&tab=versions&version=1.2.1&sub=actions', page: 'skill', nav: '管理', scrolls: false },
  { name: 'version-missing', query: '?page=skill&tab=versions&version=nope', page: 'skill', nav: '管理', scrolls: false },
  /* **编辑版本（v8）。** 入口在版本列表的「新建版本」与草稿的「编辑文件」。这一页有 25 个
     状态，因为它的任务就是处理那几件会出岔子的事：新版本、已有草稿、名称与说明的失焦即写与
     名称清空、未保存、冲突、内容重复、二进制、新建文件/文件夹与路径错误、空文件夹、重命名、
     删除确认、离开确认、保存失败、加载中、没有这一版。最后一张窄屏在 400px 下拍：树收成一条
     可展开的栏，编辑器占满宽度。 */
  { name: 'editor-new', query: '?page=editor', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-blank', query: '?page=editor&state=blank', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-base-draft', query: '?page=editor&state=base-draft', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-draft', query: '?page=editor&state=draft', page: 'editor', nav: '管理', scrolls: false },
  /* 名称与说明是版本的标签，在页头里就地改、失焦即写（2026-10-20 定）。这几张钉住结果：
     写成的那一句、名称写成、以及两种失败（清空恢复 / 写入失败）。 */
  { name: 'editor-note', query: '?page=editor&state=note', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-name', query: '?page=editor&state=name', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-name-error', query: '?page=editor&state=name-error', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-label-error', query: '?page=editor&state=label-error', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-dirty', query: '?page=editor&state=dirty', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-conflict', query: '?page=editor&state=conflict', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-duplicate', query: '?page=editor&state=duplicate', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-duplicate-draft', query: '?page=editor&state=duplicate-draft', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-binary', query: '?page=editor&state=binary', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-new-file', query: '?page=editor&state=new-file', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-new-file-error', query: '?page=editor&state=new-file-error', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-new-folder', query: '?page=editor&state=new-folder', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-empty-folder', query: '?page=editor&state=empty-folder', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-rename', query: '?page=editor&state=rename', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-delete', query: '?page=editor&state=delete', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-leave', query: '?page=editor&state=leave', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-error', query: '?page=editor&state=error', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-loading', query: '?page=editor&state=loading', page: 'editor', nav: '管理', scrolls: false },
  { name: 'editor-missing', query: '?page=editor&state=missing', page: 'editor', nav: '管理', scrolls: false },
  /* 不能编辑：页面显示与「没有这一版」同一屏（读接口仍返回内容，第一次写 403）。 */
  { name: 'editor-denied', query: '?page=editor&state=denied', page: 'editor', nav: '管理', scrolls: false },
  /* The one shot that is not the console's 1440 column: a phone. `width` is read by the loop; see
     the viewport line there. */
  { name: 'editor-narrow', query: '?page=editor&state=draft', page: 'editor', nav: '管理', width: 400, height: 900, scrolls: true },
  /* 只有草稿、从没上线过：页头与信息栏都说未上线。演示件用 `no-live=1` 造出这一态。 */
  { name: 'skill-no-live', query: '?page=skill&tab=files&no-live=1', page: 'skill', nav: '管理', scrolls: true },
  /* **整个 skill 下线。** 版本与指针都不动；页头多一枚「已下线」，设置页危险区第一行
     变成「恢复服务」。 */
  { name: 'skill-offline', query: '?page=skill&tab=settings&sub=general&offline=1', page: 'skill', nav: '管理', scrolls: true },
  { name: 'skill-missing', query: '?page=skill&state=missing', page: 'skill', nav: '管理', scrolls: false },
  { name: 'skill-settings-collaborators', query: '?page=skill&tab=settings&sub=collaborators', page: 'skill', nav: '管理', scrolls: true },
  /* 可见性三张卡 + 危险区在 960 高的窗口里放不下，这一页本来就滚。 */
  { name: 'skill-settings-visibility', query: '?page=skill&tab=settings&sub=general&vis=public-invoke', page: 'skill', nav: '管理', scrolls: true },
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
