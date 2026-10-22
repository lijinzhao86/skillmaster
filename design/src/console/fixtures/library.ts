/**
 * 库与两套标签：演示件的夹具，以及读它们的那几条规则。
 *
 * ⚠️ **这些是夹具，不是数据。** 演示件后面没有服务端，库里有什么、公域里搜得到什么、一个标签
 * 用在几个 skill 上，都是这里写下的。**不编的是形状**：来源只有三种，我的标签只有本人看得见，
 * 公域只含公开的 skill——每一格都对得上 v14 PRD 的口径。
 *
 * 规则放在这里而不是页面里，因为不止一个页面读它：「我的 skill」读库、发现页读公域、信息页读
 * 两套标签、统计读计数。计数**从行算出来**，不写死——写死的计数会在改夹具时悄悄变错。
 *
 * **地址里写 id，界面上写名字**（PRD §展示与计数口径）：`tag=doc` 是筛选地址，界面上是「文档与演示」。
 * 这样改名之后旧链接仍然可用，而 CLI 那边取的是名字——两者不是一回事。
 */

/**
 * 公开分类：平台词表，**平级 10 个词，每个 skill 最多贴一个**（2026-10-23 用户定）。
 *
 * 分类是一个「货架」：粗、稳定、可选一个；召回交给公开标签。调研过的平台都在往这个方向收——
 * B 站 2025 年取消了二级分区、抖音的分类在用户端不可见、小红书与 TikTok 由平台自动归类；保留
 * 分类的（B 站一级分区、YouTube、ClawHub）也**都是单选**。所以这里**不预留层级**：以后真要加，
 * 那是重新决定的一件事，不是今天留的坑。
 *
 * `stopped` 是停用：不能新增，已经贴着的仍算数；停用的词在菜单里置灰。
 */
export const MAX_CATEGORIES_PER_SKILL = 1

export interface Category {
  id: string
  name: string
  stopped?: boolean
}

export const CATEGORIES: Category[] = [
  { id: 'doc', name: '文档与演示' },
  { id: 'data', name: '数据与分析' },
  { id: 'dev', name: '研发与测试' },
  { id: 'design', name: '设计与界面' },
  { id: 'writing', name: '写作与翻译' },
  { id: 'research', name: '研究与检索' },
  { id: 'office', name: '办公与协作' },
  { id: 'ops', name: '运维与云', stopped: true },
  { id: 'media', name: '图像与音视频' },
  { id: 'security', name: '安全与合规' },
]

export function categoryById(id: string): Category | undefined {
  return CATEGORIES.find((category) => category.id === id)
}

/**
 * 公开标签：作者给 skill 贴的自由词，**谁都能看见**，搜索靠它召回（PRD §公开标签；本版推荐还是「最近更新」的固定规则）。
 *
 * 它和我的标签是两层，不是同一个东西的两种叫法：我的标签只有本人看得见、用来整理自己的库；
 * 公开标签是作者写给所有人的入口词。**本版做最小闭环**（2026-10-23 用户定，PRD #17）：
 * 贴、摘、搜索召回；同义词合并、改名/合并与审核仍不做（PRD 不做 #25）。
 *
 * **显示成 `#名字`**：分类与公开标签都是胶囊，但分类是平台收拢的窄词表，公开标签是作者的自由词；
 * 一枚井号让人一眼看出这一颗是「谁都能搜到的那个词」。
 */
export interface PublicTag {
  id: string
  name: string
}

export const MAX_PUBLIC_TAGS_PER_SKILL = 10
/** 名字的规矩与我的标签同一条：1–24 个字符。 */
export const MAX_PUBLIC_TAG_NAME = 24

/**
 * **这不是产品里的一张定义表。** PRD 的公开标签没有定义行——身份是 `(skill_id, tag_key)`；
 * 这份候选词表是产品里 `SELECT DISTINCT tag_key, tag_name`（作者输入框的候选）的替身，
 * `id` 也只是演示件的替身 id（与我的标签的夹具 id 同一种用法）。
 */
export const PUBLIC_TAGS: PublicTag[] = [
  { id: 'pdf', name: 'PDF' },
  { id: 'batch', name: '批量处理' },
  { id: 'doc-gen', name: '文档生成' },
  { id: 'writing', name: '写作' },
  { id: 'review', name: '代码审查' },
  { id: 'data', name: '数据' },
  { id: 'test', name: '测试' },
  { id: 'ops', name: '运维' },
  { id: 'logs', name: '日志' },
  { id: 'efficiency', name: '效率' },
  { id: 'translation', name: '翻译' },
  { id: 'frontend', name: '前端' },
  { id: 'performance', name: '性能' },
  { id: 'docs', name: '文档' },
  { id: 'replay', name: '复盘' },
  { id: 'design-system', name: '设计系统' },
  { id: 'presentation', name: '演示' },
  { id: 'table', name: '表格' },
  { id: 'report', name: '报表' },
  { id: 'screenshot', name: '截图' },
  { id: 'design', name: '设计' },
  { id: 'interface', name: '接口' },
  { id: 'conversion', name: '转换' },
  { id: 'database', name: '数据库' },
  { id: 'meeting', name: '会议' },
  { id: 'monitoring', name: '监控' },
  { id: 'accessibility', name: '无障碍' },
  { id: 'security', name: '安全' },
  { id: 'privacy', name: '隐私' },
  { id: 'research', name: '研究' },
  { id: 'audio', name: '音频' },
]

export function publicTagName(id: string): string {
  return PUBLIC_TAGS.find((tag) => tag.id === id)?.name ?? '已删除的公开标签'
}

/**
 * 「用在 N 个 skill 上」只算**我创建的、生效中的** skill：公开标签是作者的词，别人 skill 上的
 * 那一份不归我管（PRD §公开标签）。
 */
export function publicTagUsage(id: string): number {
  return activeLibrary()
    .filter((skill) => skill.source === 'created' && skill.publicTags.includes(id)).length
}

/** 公开标签那一页的清单：我在自己 skill 上用过的词，计数降序、同数按名字。 */
export function sortedPublicTags(): Array<PublicTag & { count: number }> {
  return PUBLIC_TAGS
    .map((tag) => ({ ...tag, count: publicTagUsage(tag.id) }))
    .filter((tag) => tag.count > 0)
    .sort((a, b) => {
      if (b.count !== a.count) return b.count - a.count
      return a.name < b.name ? -1 : a.name > b.name ? 1 : 0
    })
}

/** 我的标签：一个账号最多 100 个定义，一个 skill 最多 20 个生效中的贴法。 */
export const MAX_TAGS_PER_ACCOUNT = 100
export const MAX_TAGS_PER_SKILL = 20
export const MAX_TAG_NAME = 24
/** 一个标签的说明：一句判断依据，人和 agent 一起读。选填。 */
export const MAX_TAG_DESCRIPTION = 200

export interface MyTag {
  id: string
  name: string
  /** 什么时候该贴这个标签。选填；空串与缺席都算没有。 */
  description?: string
}

export const MY_TAGS: MyTag[] = [
  { id: 'client', name: '客户项目', description: '交付期内客户随时要用的 skill' },
  { id: 'this-week', name: '这周要用', description: '本周排期里会反复调用的那些' },
  { id: 'evaluate', name: '待评估', description: '还没试过、想先跑一遍再决定的' },
  { id: 'team', name: '团队规范', description: '团队约定要照着做的写法与检查' },
  { id: 'design-group', name: '设计组', description: '设计组共用、评审前先看的那一类' },
  /* 旧项目故意不写：空说明那一态（「加一句说明」）在正常清单里就看得见。 */
  { id: 'archive', name: '旧项目' },
]

/** 库条目的来源（PRD §库条目）。显示时按「我创建的 > 共享给我的 > 我加入的」取一个。 */
export type Source = 'created' | 'shared' | 'joined'

export const SOURCE_LABEL: Record<Source, string> = {
  created: '我创建的',
  shared: '共享给我的',
  joined: '我加入的',
}

/** 行上的状态：我创建的与 `write` 及以上共享的，下线仍留在「在用」并显示已下线。 */
export type RowState = 'live' | 'draft' | 'offline'

export interface LibrarySkill {
  name: string
  namespace: string
  title: string
  /** `metadata.when_to_use`——PRD 把它纳入库内搜索与公域检索。 */
  whenToUse: string
  source: Source
  /** 共享给我的那一份是哪一档：`write` 及以上下线仍在，`browse` / `invoke` 失去可消费性即休眠。 */
  sharedRole?: 'invoke' | 'browse' | 'write' | 'maintain' | 'admin'
  state: RowState
  /** 当前上线的版本号；草稿为 null（从来没有上线过）。 */
  version: string | null
  updated: string
  deleted?: string
  /** 一个「货架」：平台词表里的一个 id；没归类的是 null。 */
  category: string | null
  /** 作者贴的公开标签：谁都能看见，搜索靠它召回。 */
  publicTags: string[]
  myTags: string[]
}

/**
 * 库里那 24 行。
 *
 * **两行是休眠的**（`flaky-test-hunter` 与 `proto-review`）：前者共享档是 `browse` 且已下线，
 * 后者是我加入的且已下线。它们不进「在用」——正好是 PRD 要求的「休眠不显示、引用还在」，
 * 而那两行仍然算在「库非空」里。这一页因此有 22 行可显示，一页 10 行。
 */
export const LIBRARY: LibrarySkill[] = [
  { name: 'pdf-tools', namespace: 'dev', title: '把 PDF 拆页、合并与压缩。', whenToUse: '手里有 PDF，要拆页、合并或压缩的时候', source: 'created', state: 'live', version: '1.2.3', updated: '2026/10/07 14:32', category: 'doc', publicTags: ['pdf', 'batch'], myTags: ['client'] },
  { name: 'commit-lint', namespace: 'dev', title: '按团队约定检查提交信息，不合规的给出改法。', whenToUse: '提交之前，想确认这一条信息团队读得懂的时候', source: 'created', state: 'live', version: '0.4.1', updated: '2026/09/18 09:06', category: 'dev', publicTags: ['review', 'efficiency'], myTags: ['team'] },
  { name: 'changelog-writer', namespace: 'dev', title: '从提交历史里收拾出一份能给人看的更新日志。', whenToUse: '要发版，需要一份给人读的更新说明的时候', source: 'created', state: 'live', version: '1.0.0', updated: '2026/10/09 08:15', category: 'writing', publicTags: ['doc-gen', 'writing'], myTags: ['client', 'this-week'] },
  { name: 'sql-review', namespace: 'dev', title: '读一遍要上线的 SQL，把会锁表、会走全表扫的那几句挑出来。', whenToUse: '一条 SQL 要上生产之前', source: 'created', state: 'offline', version: '0.1.0', updated: '2026/04/12 10:05', category: 'dev', publicTags: ['review', 'data'], myTags: [] },
  { name: 'api-doc-writer', namespace: 'dev', title: '从路由定义里生成接口文档的草稿。', whenToUse: '接口改完，文档还没跟上的时候', source: 'created', state: 'live', version: '2.0.0', updated: '2026/08/30 17:41', category: 'doc', publicTags: ['doc-gen'], myTags: ['client'] },
  { name: 'test-namer', namespace: 'dev', title: '给测试用例起一个能说明它在测什么的名字。', whenToUse: '写完一组测试，名字还是 test1 / test2 的时候', source: 'created', state: 'live', version: '1.0.2', updated: '2026/09/02 11:20', category: 'dev', publicTags: ['test'], myTags: ['team'] },
  { name: 'log-triage', namespace: 'dev', title: '把一段日志按「值得看」和「噪音」分开。', whenToUse: '线上出了事，日志一次刷出几千行的时候', source: 'created', state: 'live', version: '0.9.0', updated: '2026/10/01 22:05', category: 'ops', publicTags: ['ops', 'logs'], myTags: ['this-week'] },
  { name: 'regex-explainer', namespace: 'dev', title: '把一条正则拆成人话。', whenToUse: '接手一条看不懂的正则的时候', source: 'created', state: 'live', version: '1.1.0', updated: '2026/07/14 10:02', category: 'dev', publicTags: ['efficiency'], myTags: [] },
  { name: 'dockerfile-review', namespace: 'dev', title: '检查 Dockerfile 里会拖慢构建或撑大镜像的写法。', whenToUse: '镜像越构建越慢、越做越大的时候', source: 'created', state: 'live', version: '0.3.0', updated: '2026/06/28 16:55', category: 'ops', publicTags: ['review', 'ops'], myTags: ['client'] },
  { name: 'i18n-extract', namespace: 'dev', title: '从组件里把待翻译的字符串抽出来。', whenToUse: '一个页面要开始支持另一种语言的时候', source: 'created', state: 'live', version: '1.4.0', updated: '2026/09/25 13:12', category: 'writing', publicTags: ['translation'], myTags: [] },
  { name: 'perf-budget', namespace: 'dev', title: '给前端包体定一个不许越过的预算，并在 CI 里守住。', whenToUse: '包体开始悄悄变大的时候', source: 'created', state: 'draft', version: null, updated: '2026/10/05 19:30', category: null, publicTags: ['frontend', 'performance'], myTags: ['this-week'] },
  { name: 'incident-notes', namespace: 'yuhan', title: '把一次故障的排查过程整理成时间线。', whenToUse: '故障处理完，要写复盘的时候', source: 'shared', sharedRole: 'write', state: 'live', version: '0.2.1', updated: '2026/05/19 08:47', category: 'ops', publicTags: ['ops', 'replay'], myTags: ['team'] },
  { name: 'schema-diff', namespace: 'yuhan', title: '比较两份数据库结构，说清差在哪。', whenToUse: '迁移前后要核对结构的时候', source: 'shared', sharedRole: 'write', state: 'offline', version: '1.0.0', updated: '2026/08/11 15:09', category: 'data', publicTags: ['data'], myTags: [] },
  { name: 'css-token-audit', namespace: 'yuhan', title: '找出散落在样式里的颜色和间距，归并成令牌。', whenToUse: '设计系统要收口，样式里却散着一堆颜色的时候', source: 'shared', sharedRole: 'browse', state: 'live', version: '0.7.0', updated: '2026/09/09 12:33', category: 'design', publicTags: ['design-system', 'frontend'], myTags: ['design-group'] },
  { name: 'flaky-test-hunter', namespace: 'yuhan', title: '从失败记录里找出反复摇摆的那些用例。', whenToUse: 'CI 红一阵绿一阵，说不清是哪条用例的时候', source: 'shared', sharedRole: 'browse', state: 'offline', version: '0.2.0', updated: '2026/10/08 21:14', category: 'dev', publicTags: ['test'], myTags: ['evaluate'] },
  { name: 'env-doc', namespace: 'hexu', title: '把 .env 里每一项的用途问出来并写成文档。', whenToUse: '接手一个项目，配置项没人说得清的时候', source: 'joined', state: 'live', version: '1.0.3', updated: '2026/07/02 09:58', category: 'doc', publicTags: ['docs', 'ops'], myTags: ['client'] },
  { name: 'migration-check', namespace: 'hexu', title: '上线前检查这条迁移会不会锁表。', whenToUse: '一条迁移要在业务高峰上线之前', source: 'joined', state: 'live', version: '0.5.0', updated: '2026/08/22 18:26', category: 'data', publicTags: ['data', 'ops'], myTags: [] },
  { name: 'prompt-lint', namespace: 'zhao', title: '检查提示词里的歧义与自相矛盾。', whenToUse: '一段提示词效果不稳，想找原因的时候', source: 'joined', state: 'live', version: '1.2.0', updated: '2026/10/03 07:44', category: 'writing', publicTags: ['writing'], myTags: ['evaluate'] },
  { name: 'sdk-snippet', namespace: 'zhao', title: '从接口定义生成各语言的最小调用示例。', whenToUse: '文档里要放一段能直接跑的示例的时候', source: 'joined', state: 'live', version: '0.1.0', updated: '2026/04/30 14:11', category: 'doc', publicTags: ['doc-gen'], myTags: [] },
  { name: 'dead-code', namespace: 'qian', title: '找出没人再引用的模块和导出。', whenToUse: '一个仓库越背越重、想删点什么的时候', source: 'joined', state: 'live', version: '1.0.0', updated: '2026/06/06 11:37', category: 'dev', publicTags: ['efficiency'], myTags: [] },
  { name: 'release-notes', namespace: 'qian', title: '把一版里的变更写给用户看，不是写给提交者看。', whenToUse: '要对外发一版的时候', source: 'joined', state: 'live', version: '2.1.0', updated: '2026/09/14 16:20', category: 'writing', publicTags: ['writing', 'docs'], myTags: ['client'] },
  { name: 'proto-review', namespace: 'qian', title: '审一遍 .proto 的改动，看有没有破坏兼容性。', whenToUse: '接口定义要改，还在兼容期的时候', source: 'joined', state: 'offline', version: '0.6.0', updated: '2026/07/27 13:05', category: 'dev', publicTags: ['review'], myTags: ['archive'] },
  { name: 'bundle-triage', namespace: 'sun', title: '说清这次包体涨的那 40 KB 是谁带来的。', whenToUse: '包体突然变大，要找出是谁带进来的时候', source: 'joined', state: 'live', version: '0.3.0', updated: '2026/10/06 23:51', category: 'dev', publicTags: ['frontend', 'performance'], myTags: ['this-week'] },
  { name: 'cron-translator', namespace: 'sun', title: '把 cron 表达式翻译成一句人话，或者反过来。', whenToUse: '接手一条 cron，看不出它到底几点跑的时候', source: 'joined', state: 'live', version: '1.0.0', updated: '2026/05/08 20:19', category: 'ops', publicTags: ['ops', 'efficiency'], myTags: [] },
]

/** 「已删除」只含我创建的软删除 skill；共享与加入没有这一态（PRD 做 #9）。 */
export const DELETED: LibrarySkill[] = [
  { name: 'pdf-stamp', namespace: 'dev', title: '在 PDF 的每一页角上盖一个日期。', whenToUse: '需要证明这份 PDF 是什么时候拿到的时候', source: 'created', state: 'live', version: '1.0.4', updated: '2026/08/19 15:40', deleted: '2026/10/11 09:20', category: 'doc', publicTags: ['pdf'], myTags: ['archive'] },
  { name: 'slug-check', namespace: 'dev', title: '提交前检查目录名与 skill 名是不是同一个。', whenToUse: '打包上传前，担心名字对不上时', source: 'created', state: 'live', version: '2.1.0', updated: '2026/05/02 11:08', deleted: '2026/09/30 17:02', category: 'dev', publicTags: ['efficiency'], myTags: [] },
]

/**
 * **库条目生效与否**（PRD §两个谓词）：我创建的与 `write` 及以上共享的，下线仍生效；
 * `browse` / `invoke` 共享的与我加入的，失去可消费性就休眠。休眠的条目**不进「在用」**，
 * 引用还在，恢复可见后回来。
 */
export function entryActive(skill: LibrarySkill): boolean {
  /* 作者面（我创建的、`write` 及以上共享）看得见草稿与下线，一直生效。 */
  if (skill.source === 'created') return true
  const authorTier = skill.source === 'shared' && (skill.sharedRole === 'write' ||
    skill.sharedRole === 'maintain' || skill.sharedRole === 'admin')
  if (authorTier) return true
  /* 消费面（我加入的、`browse` / `invoke` 共享）只认**可消费**：下线或没有当前版本就休眠。
     演示夹具里两条休眠都是「下线」造成的；「没有当前版本」这一成因由本谓词覆盖，不单独出图，
     由 TD / 产品侧用例验证。 */
  return skill.state !== 'offline' && skill.version !== null
}

/** 在用的库（含草稿、含下线、不含休眠）。控制台这一页是完整库。 */
export function activeLibrary(): LibrarySkill[] {
  return LIBRARY.filter(entryActive)
}

/** 休眠的条目：库里有，但这一页不显示。空态要能说出「它们在，只是暂时看不到」。 */
export function dormantLibrary(): LibrarySkill[] {
  return LIBRARY.filter((skill) => !entryActive(skill))
}

export function categoryName(id: string): string {
  return categoryById(id)?.name ?? '已删除的分类'
}

export function myTagName(id: string): string {
  return MY_TAGS.find((tag) => tag.id === id)?.name ?? '已删除的标签'
}

/**
 * 「用在 N 个 skill 上」的 N **只算生效中的贴法**（PRD §展示与计数口径）。
 * 所以它读的是 `activeLibrary()`，不是 `LIBRARY`——休眠的贴法不计数，但定义还在。
 */
export function myTagUsage(id: string): number {
  return activeLibrary().filter((skill) => skill.myTags.includes(id)).length
}

/**
 * 只算**休眠贴法**的个数。删除确认里的 M 就是它——「另有 M 个 skill 上的这个标签当前不显示」。
 * 它与 N 不是两个列表，是同一个标签的两种贴法：N 现在看得见，M 现在看不见。
 */
export function myTagDormantUsage(id: string): number {
  return dormantLibrary().filter((skill) => skill.myTags.includes(id)).length
}

/** 名字的规矩：去首尾空格，内部连续空格压成一个（PRD 做 #6）。 */
export function normalizeTagName(value: string): string {
  return value.trim().replace(/\s+/g, ' ')
}

/** 名字排序、计数降序、同数按名字——我的标签页的固定顺序。 */
export function sortedMyTags(tags: MyTag[]): MyTag[] {
  return [...tags].sort((a, b) => {
    const byCount = myTagUsage(b.id) - myTagUsage(a.id)
    if (byCount !== 0) return byCount
    return a.name < b.name ? -1 : a.name > b.name ? 1 : 0
  })
}

/** 只沾休眠贴法的定义：计数为 0，但定义还在——「只有休眠」那一态说的就是它们。 */
export function sleepingTags(tags: MyTag[]): MyTag[] {
  return tags.filter((tag) => myTagUsage(tag.id) === 0)
}

/** `？state=full` 造出第 100 个定义的那一态：新建必须禁用并说明。 */
export function fullTagSet(): MyTag[] {
  const rows = [...MY_TAGS]
  for (let index = rows.length + 1; index <= MAX_TAGS_PER_ACCOUNT; index += 1) {
    rows.push({ id: 'demo-' + index, name: '演示标签 ' + index, description: '演示用的标签' })
  }
  return rows
}

/* --------------------------------------------------------------- 公域发现 */

export interface DiscoverSkill {
  name: string
  namespace: string
  title: string
  whenToUse: string
  /** 当前上线的版本号与它上线的时间——公开检索只收录有当前版本的 skill，所以两者都非空。 */
  version: string
  updated: string
  /** 一个「货架」：平台词表里的一个 id；没归类的是 null。 */
  category: string | null
  /** 公域里的卡片也带着作者的公开标签——它们是这一条为什么会出现在结果里的理由。 */
  publicTags: string[]
  /** 已在库里时，这一行只读显示来源；不在库里时是 null。 */
  inLibrary: Source | null
}

/**
 * 公域里的公开 skill：**只有公开、有当前版本、未下线、未删除的**才在这里。
 * 夹具里刻意混着四种在库状态，好让「未加入 / 已在库（我创建的 / 共享给我的 / 我加入的）」
 * 四种卡片在同一屏上都能被看见。
 */
export const DISCOVER: DiscoverSkill[] = [
  { name: 'pdf-tools', namespace: 'dev', title: '把 PDF 拆页、合并与压缩。加载后可用 split 与 merge 两个动作。', whenToUse: '手里有 PDF，要拆页、合并或压缩的时候', version: '@1.2.3', updated: '2026/10/07', category: 'doc', publicTags: ['pdf', 'batch'], inLibrary: 'created' },
  { name: 'pdf-splitter', namespace: 'yuhan', title: '按页、按书签或按分隔符把一份 PDF 切开。', whenToUse: '一份几百页的 PDF 只需要其中几段的时候', version: '@0.4.0', updated: '2026/09/21', category: 'doc', publicTags: ['pdf', 'batch'], inLibrary: null },
  { name: 'slide-maker', namespace: 'hexu', title: '把一段 Markdown 排成 16:9 的演示文稿。', whenToUse: '有一份写好的文档，要拿去讲的时候', version: '@2.1.0', updated: '2026/10/02', category: 'doc', publicTags: ['presentation', 'writing'], inLibrary: 'joined' },
  { name: 'doc-translate', namespace: 'zhao', title: '把一份技术文档翻成另一种语言，术语表优先。', whenToUse: '文档要给另一种语言的读者看的时候', version: '@1.0.2', updated: '2026/08/15', category: 'writing', publicTags: ['translation', 'docs'], inLibrary: null },
  { name: 'csv-cleaner', namespace: 'qian', title: '找出 CSV 里的空列、重复行与格式不一致的日期。', whenToUse: '拿到一份来路不明的表格，先要看它干不干净的时候', version: '@0.9.1', updated: '2026/07/30', category: 'data', publicTags: ['data', 'table'], inLibrary: 'shared' },
  { name: 'report-builder', namespace: 'sun', title: '把一组查询结果排成一份周报。', whenToUse: '每周要交一份数字的时候', version: '@1.3.0', updated: '2026/09/05', category: 'data', publicTags: ['data', 'report'], inLibrary: null },
  { name: 'screenshot-annotator', namespace: 'li', title: '在截图上圈出要说的那几处，并配上说明。', whenToUse: '提 bug 或写说明，需要指给人看的时候', version: '@0.2.0', updated: '2026/06/18', category: 'media', publicTags: ['screenshot', 'design'], inLibrary: null },
  { name: 'api-compat-check', namespace: 'zhou', title: '比较两版接口定义，标出会破坏调用方的改动。', whenToUse: '接口要改，想知道谁会被打断的时候', version: '@1.1.1', updated: '2026/10/11', category: 'dev', publicTags: ['review', 'interface'], inLibrary: null },
  /* 下面十六条让「为你推荐」真的滚得下去：一批六张，四批到底。它们的日期都排在 2026/07–10。 */
  { name: 'md2html', namespace: 'yuhan', title: '把一段 Markdown 转成干净的 HTML 片段。', whenToUse: '要把文档贴进一个只认 HTML 的地方的时候', version: '@1.2.0', updated: '2026/10/12', category: 'doc', publicTags: ['docs', 'conversion'], inLibrary: null },
  { name: 'sql-explain', namespace: 'qian', title: '把 EXPLAIN 的输出翻成人话，指出哪一步最贵。', whenToUse: '一条查询突然变慢，想先看它在哪一步卡住的时候', version: '@0.8.0', updated: '2026/10/08', category: 'data', publicTags: ['data', 'database'], inLibrary: null },
  { name: 'test-gap', namespace: 'hexu', title: '对着行为清单找出没有测试覆盖的路径。', whenToUse: '准备发版，想先看哪块没测过的时候', version: '@0.3.2', updated: '2026/09/29', category: 'dev', publicTags: ['test'], inLibrary: null },
  { name: 'color-audit', namespace: 'li', title: '把界面里用到的颜色列出来，标出重复与偏色。', whenToUse: '设计系统要收口之前', version: '@1.0.1', updated: '2026/09/26', category: 'design', publicTags: ['design-system'], inLibrary: null },
  { name: 'meeting-notes', namespace: 'zhao', title: '把会议记录整理成决定、待办与负责人。', whenToUse: '会开完了，没人说得清决定了什么的时候', version: '@2.0.0', updated: '2026/09/19', category: 'office', publicTags: ['meeting', 'efficiency'], inLibrary: null },
  { name: 'log-to-metrics', namespace: 'sun', title: '从日志里抽出可以画成图的指标。', whenToUse: '只有日志、没有监控的时候', version: '@0.5.0', updated: '2026/09/12', category: 'ops', publicTags: ['ops', 'monitoring'], inLibrary: null },
  { name: 'image-alt', namespace: 'zhou', title: '给一批图片写替代文字，先说图里有什么。', whenToUse: '页面要做无障碍检查的时候', version: '@0.4.1', updated: '2026/09/08', category: 'media', publicTags: ['accessibility'], inLibrary: null },
  { name: 'threat-model', namespace: 'wu', title: '对着数据流画一遍威胁模型，列出要补的边界。', whenToUse: '新功能上线前，想先过一遍风险的时候', version: '@1.1.0', updated: '2026/09/01', category: 'security', publicTags: ['security'], inLibrary: null },
  { name: 'survey-coder', namespace: 'qian', title: '把开放式问卷的回答归成几类，并给出例子。', whenToUse: '收回来一堆文字答案，要看出规律的时候', version: '@0.6.3', updated: '2026/08/28', category: 'research', publicTags: ['research', 'data'], inLibrary: null },
  { name: 'commit-split', namespace: 'hexu', title: '把一坨改动拆成几条说得清的提交。', whenToUse: '一个分支上堆了太多改动，要整理成提交的时候', version: '@1.3.0', updated: '2026/08/20', category: 'dev', publicTags: ['efficiency', 'review'], inLibrary: null },
  { name: 'chart-picker', namespace: 'li', title: '按数据的样子推荐一种图表，并说明为什么。', whenToUse: '有数要展示，却不知道该画成什么的时候', version: '@0.2.0', updated: '2026/08/12', category: 'data', publicTags: ['design', 'data'], inLibrary: null },
  { name: 'doc-lint', namespace: 'zhao', title: '检查文档里的断链、过期示例与不一致的术语。', whenToUse: '文档很久没动，要发出去之前', version: '@1.0.0', updated: '2026/08/06', category: 'doc', publicTags: ['docs', 'writing'], inLibrary: null },
  { name: 'k8s-manifest-review', namespace: 'sun', title: '审一遍 Kubernetes 清单，指出会拖垮集群的写法。', whenToUse: '清单要 apply 到生产之前', version: '@0.7.1', updated: '2026/07/28', category: 'ops', publicTags: ['ops', 'security'], inLibrary: null },
  { name: 'transcript-cut', namespace: 'yuhan', title: '把一段录音转写里的口头语和重复句去掉。', whenToUse: '访谈转写要整理成能读的文字的时候', version: '@1.2.2', updated: '2026/07/20', category: 'media', publicTags: ['writing', 'audio'], inLibrary: null },
  { name: 'privacy-check', namespace: 'wu', title: '扫一遍字段，指出哪些是个人信息、该不该收。', whenToUse: '一张新表或一个新表单要上线之前', version: '@0.9.0', updated: '2026/07/11', category: 'security', publicTags: ['security', 'privacy'], inLibrary: null },
  { name: 'release-brief', namespace: 'qian', title: '把一次发版整理成一页能给非技术读者看的说明。', whenToUse: '要对外解释这次改了什么的时候', version: '@1.4.0', updated: '2026/07/02', category: 'writing', publicTags: ['writing', 'docs'], inLibrary: null },
]

/** 公域只搜不列：有关键词或分类才返回结果，两个都没有时是空数组（PRD #8）。 */
export function searchPublic(query: string, tags: string[]): DiscoverSkill[] {
  if (query.trim() === '' && tags.length === 0) return []
  const asked = query.trim().toLowerCase()
  return DISCOVER.filter((skill) => {
    /* 筛选可以同时选几个货架，取「或」：放宽，不是收窄；每个 skill 自己只在一个货架上。 */
    const byTag = tags.length === 0 ||
      (skill.category !== null && tags.includes(skill.category))
    if (!byTag) return false
    if (asked === '') return true
    /* 多词取「且」；匹配 name / title / description / when_to_use，以及**作者的公开标签**——
       公开标签就是召回词（PRD §公开标签），搜它就该搜到。分类词不参与。 */
    const haystack = skill.name + ' ' + skill.title + ' ' + skill.whenToUse + ' ' +
      skill.publicTags.map(publicTagName).join(' ')
    return asked.split(/\s+/).every((word) => haystack.toLowerCase().includes(word))
  })
}

/**
 * 库内搜索：同样是这些字段。多个我的标签、多个公开标签取「且」（收窄）；几个分类取「或」
 * （同一个 skill 只在一个货架上，选两个货架就是「这两个里都行」）。
 *
 * `publicTags` 只由公开标签管理页那条计数链接用（「用在 N 个 skill 上」→ 去库里看这几个）。
 * 库的筛选条上没有公开标签这一项：它是召回词，不是整理自己的库的那把尺子。
 */
export function matchesLibrary(
  skill: LibrarySkill,
  query: string,
  tags: string[],
  myTags: string[],
  publicTags: string[] = [],
): boolean {
  if (tags.length > 0 && (skill.category === null || !tags.includes(skill.category))) return false
  if (!myTags.every((id) => skill.myTags.includes(id))) return false
  if (!publicTags.every((id) => skill.publicTags.includes(id))) return false
  const asked = query.trim().toLowerCase()
  if (asked === '') return true
  return asked.split(/\s+/).every((word) =>
    (skill.name + ' ' + skill.title + ' ' + skill.whenToUse).toLowerCase().includes(word))
}

/** 信息页那一屏的库归属：PRD 要求六种（不在库 / 我加入的 / 共享给我的 / 我创建的 / 不可加入 / 休眠）。 */
export type Membership = 'created' | 'shared' | 'joined' | 'visible' | 'blocked' | 'dormant'

const MEMBERSHIPS: readonly string[] = ['created', 'shared', 'joined', 'visible', 'blocked', 'dormant']

/** 地址里的 `?in=` 读成归属；不认得或不在的，就是我创建的（这一屏的默认）。 */
export function membershipOf(params: URLSearchParams): Membership {
  const asked = params.get('in')
  return asked !== null && MEMBERSHIPS.includes(asked) ? (asked as Membership) : 'created'
}

/**
 * 没有指定 `as` 时按归属推一档：自己的按所有者，共享的按浏览，公域来的按调用。
 * **这条规则只有一处**——演示条要标出「现在是哪一档」，它复制一份就会和页面说的不一样。
 */
export function tierForMembership(membership: Membership): string {
  return membership === 'created' ? 'owner' : membership === 'shared' ? 'browse' : 'invoke'
}

export const MEMBER_LABEL: Record<Membership, string> = {
  created: '我创建的',
  shared: '共享给我的',
  joined: '我加入的',
  visible: '不在你的库里',
  blocked: '不在你的库里',
  dormant: '暂时不可见',
}
