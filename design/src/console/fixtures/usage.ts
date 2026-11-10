/**
 * 交互明细的样张（v58）。
 *
 * **一次交互一行**：搜索词、总结果数、返回的名次，以及随后调用了哪条、原名次是几；
 * 没有匹配上的调用单独成一条「直接调用」。两张数据形状不同，所以用判别联合，不硬塞进一行。
 *
 * **统计与明细同源**：调用次数、使用过的 skill、最近使用、搜索后调到了几次，全部由这张表
 * 现算（`usageStats()`），不另列一份数——两处各写一份，第一次改数就会对不上。
 */

export interface UsageResult {
  skill: string
  rank: number
}

export interface UsageInvoke {
  skill: string
  version: string
  at: string
  /** 这一条在搜索返回里的原名次。 */
  rank: number
}

export interface SearchInteraction {
  kind: 'search'
  id: string
  query: string
  at: string
  /** 搜索返回的总数；API 搜索响应没有总数，所以可为空——空时显示本次返回的条数。 */
  total: number | null
  results: UsageResult[]
  /** **一次搜索可以被调用多次**（2026-11-10 定）：按调用时间升序；空数组 = 未调用。
      前端与 CLI 都显示第一次，其余挂在同一条搜索上（明细里折成一行，统计按全部算）。 */
  invocations: UsageInvoke[]
}

export interface DirectInteraction {
  kind: 'direct'
  id: string
  skill: string
  version: string
  at: string
}

export type UsageInteraction = SearchInteraction | DirectInteraction

/* ---------------------------------------------------------------- the range (v60) */

/** 演示件把「今天」钉在夹具最新的一天：7 / 30 / 90 天的区间才可复现。
    真实产品用用户本地的今天；口径见 PRD「时间口径」。 */
export const USAGE_TODAY = '2026-11-10'

export interface UsageRange {
  /** 预设的自然日数；自定义时是 null。`今天` 是 1。 */
  days: number | null
  /** 自定义起止，`YYYY-MM-DD`，含首尾两天；预设时是 null。 */
  from: string | null
  to: string | null
}

/** 默认近 30 天（v60，2026-11-11 用户定）：与账单 / 报表 / 采用看板的主流一致；
    7 天会把「上周三」藏起来，90 天首屏偏满、筛选感弱。90 天一键可到。 */
export const DEFAULT_RANGE = { days: 30, from: null, to: null } satisfies UsageRange
export const PRESET_DAYS = [1, 7, 30, 90] as const

export function shiftDay(day: string, offset: number): string {
  const date = new Date(`${day}T12:00:00Z`)
  date.setUTCDate(date.getUTCDate() + offset)
  return date.toISOString().slice(0, 10)
}

export function rangeBounds(range: UsageRange): { from: string; to: string } {
  if (range.from !== null && range.to !== null) return { from: range.from, to: range.to }
  const days = range.days ?? DEFAULT_RANGE.days
  return { from: shiftDay(USAGE_TODAY, -(days - 1)), to: USAGE_TODAY }
}

/** 行上的时刻是 `YYYY-MM-DD HH:MM`；区间按本地自然日、含首尾两天，只比日期部分。 */
export function inRange(at: string, range: UsageRange): boolean {
  const day = at.slice(0, 10)
  const bounds = rangeBounds(range)
  return day >= bounds.from && day <= bounds.to
}

export function rangeLabel(range: UsageRange): string {
  if (range.from !== null && range.to !== null) {
    return range.from.slice(0, 4) === range.to.slice(0, 4)
      ? `${range.from.slice(5)} 至 ${range.to.slice(5)}`
      : `${range.from} 至 ${range.to}`
  }
  switch (range.days) {
    case 1: return '今天'
    case 7: return '近 7 天'
    case 90: return '近 90 天'
    default: return '近 30 天'
  }
}

const DAY = /^\d{4}-\d{2}-\d{2}$/

/** 真实存在的日历日。`2026-09-31` 形状对，但 `Date` 会把它顺延成 10-01；
    写回去再比一次，顺延过的字符串就对不上了。 */
export function validDay(day: string): boolean {
  if (!DAY.test(day)) return false
  const date = new Date(`${day}T12:00:00Z`)
  return Number.isNaN(date.getTime()) ? false : date.toISOString().slice(0, 10) === day
}

/** 地址里的区间。非法值一律落回默认 30 天——与页码越界收在末页同一条规矩：
    手工改坏的地址不该把整页卡在错误态。 */
export function rangeFromParams(params: URLSearchParams): UsageRange {
  const from = params.get('from')
  const to = params.get('to')
  if (from !== null && to !== null && validDay(from) && validDay(to) && from <= to) {
    const oldest = shiftDay(USAGE_TODAY, -89)
    if (from >= oldest && to <= USAGE_TODAY) return { days: null, from, to }
  }
  const asked = Number.parseInt(params.get('days') ?? '', 10)
  if ((PRESET_DAYS as readonly number[]).includes(asked)) {
    return { days: asked, from: null, to: null }
  }
  return DEFAULT_RANGE
}

/* ------------------------------------------------------------- the skill filter (v60) */

/** 只按地址做大小写不敏感的包含匹配；空词表示不筛。 */
export function matchesSkill(address: string, query: string): boolean {
  const wanted = query.trim().toLowerCase()
  return wanted === '' || address.toLowerCase().includes(wanted)
}

/** 一次搜索算命中，当结果里出现过它，或随后调用了它。 */
export function rowMatchesSkill(row: UsageInteraction, query: string): boolean {
  if (query.trim() === '') return true
  if (row.kind === 'direct') return matchesSkill(row.skill, query)
  return row.results.some((result) => matchesSkill(result.skill, query)) ||
    row.invocations.some((invocation) => matchesSkill(invocation.skill, query))
}

/** 命中的原因只是「结果里出现过」：随后没有调用它。行上因此要写明，否则读的人不知道它为什么在这里。 */
export function searchHitOnly(row: SearchInteraction, query: string): boolean {
  return query.trim() !== '' &&
    !row.invocations.some((invocation) => matchesSkill(invocation.skill, query)) &&
    row.results.some((result) => matchesSkill(result.skill, query))
}

export const USAGE_INTERACTIONS: UsageInteraction[] = [
  {
    kind: 'search',
    id: 'e1',
    query: 'pdf 合并',
    at: '2026-11-10 09:38',
    total: 12,
    results: [
      { skill: 'yuhan/pdf-splitter', rank: 1 },
      { skill: 'qian/pdf-merge-pro', rank: 2 },
      { skill: 'hexu/pdf-compress', rank: 3 },
      { skill: 'linzhi/doc-convert', rank: 4 },
      { skill: 'dev/pdf-tools', rank: 5 },
      { skill: 'yuhan/file-batch', rank: 6 },
      { skill: 'linzhi/pdf-annotate', rank: 7 },
      { skill: 'qian/ocr-scan', rank: 8 },
      { skill: 'hexu/form-fill', rank: 9 },
      { skill: 'linzhi/sign-stamp', rank: 10 },
      { skill: 'yuhan/watermark', rank: 11 },
      { skill: 'dev/merge-check', rank: 12 },
    ],
    invocations: [{ skill: 'dev/pdf-tools', version: '1.2.3', at: '2026-11-10 09:41', rank: 5 }],
  },
  {
    kind: 'direct',
    id: 'e2',
    skill: 'lijinzhao/code-dev-sop',
    version: '1.1.2',
    at: '2026-11-10 08:55',
  },
  {
    kind: 'search',
    id: 'e3',
    query: '季度报表',
    at: '2026-11-10 09:10',
    total: 4,
    results: [
      { skill: 'dev/quarterly-report-assembler', rank: 1 },
      { skill: 'qian/finance-rollup', rank: 2 },
      { skill: 'hexu/table-align', rank: 3 },
      { skill: 'qian/tax-export', rank: 4 },
    ],
    invocations: [{ skill: 'dev/quarterly-report-assembler', version: '1.0.0', at: '2026-11-10 09:12', rank: 1 }],
  },
  {
    kind: 'search',
    id: 'e4',
    query: '演示文稿',
    at: '2026-11-09 17:22',
    total: 7,
    results: [
      { skill: 'hexu/slide-maker', rank: 1 },
      { skill: 'qian/deck-theme', rank: 2 },
      { skill: 'linzhi/markdown-to-ppt', rank: 3 },
      { skill: 'hexu/deck-review', rank: 4 },
      { skill: 'qian/slide-outline', rank: 5 },
      { skill: 'linzhi/ppt-notes', rank: 6 },
      { skill: 'yuhan/slide-export', rank: 7 },
    ],
    invocations: [],
  },
  {
    kind: 'direct',
    id: 'e5',
    skill: 'official/startup',
    version: '1.0.0',
    at: '2026-11-07 09:30',
  },
  {
    kind: 'search',
    id: 'e6',
    query: '审计 skill',
    at: '2026-11-08 20:10',
    total: 2,
    results: [
      { skill: 'lijinzhao/code-audit', rank: 1 },
      { skill: 'yuhan/skill-lint', rank: 2 },
    ],
    invocations: [{ skill: 'lijinzhao/code-audit', version: '0.9.1', at: '2026-11-08 20:14', rank: 1 }],
  },
  {
    kind: 'direct',
    id: 'e7',
    skill: 'dev/pdf-tools',
    version: '1.2.3',
    at: '2026-11-05 14:20',
  },
  {
    kind: 'search',
    id: 'e8',
    query: '把 PPT 转成 PDF',
    at: '2026-10-29 16:40',
    total: 5,
    results: [
      { skill: 'hexu/ppt-to-pdf', rank: 1 },
      { skill: 'linzhi/doc-convert', rank: 2 },
      { skill: 'yuhan/pdf-splitter', rank: 3 },
      { skill: 'dev/pdf-tools', rank: 4 },
      { skill: 'qian/pdf-merge-pro', rank: 5 },
    ],
    invocations: [{ skill: 'hexu/ppt-to-pdf', version: '2.1.0', at: '2026-10-29 16:44', rank: 1 }],
  },
  {
    kind: 'direct',
    id: 'e9',
    skill: 'dev/pdf-tools',
    version: '1.2.3',
    at: '2026-10-21 10:05',
  },
  {
    kind: 'search',
    id: 'e10',
    query: 'slides 美化',
    at: '2026-10-16 15:10',
    total: 3,
    results: [
      { skill: 'hexu/slide-maker', rank: 1 },
      { skill: 'qian/deck-theme', rank: 2 },
      { skill: 'linzhi/markdown-to-ppt', rank: 3 },
    ],
    /* **一次搜索、三次调用**：这一条是「多调用挂一次搜索」的样张。 */
    invocations: [
      { skill: 'hexu/slide-maker', version: '0.8.0', at: '2026-10-16 15:13', rank: 1 },
      { skill: 'qian/deck-theme', version: '1.4.0', at: '2026-10-16 15:16', rank: 2 },
      { skill: 'linzhi/markdown-to-ppt', version: '0.6.2', at: '2026-10-16 15:19', rank: 3 },
    ],
  },
  {
    kind: 'direct',
    id: 'e11',
    skill: 'lijinzhao/code-dev-sop',
    version: '1.1.1',
    at: '2026-10-09 09:12',
  },
  {
    kind: 'search',
    id: 'e12',
    query: '月度汇报',
    at: '2026-09-28 11:20',
    total: 6,
    results: [
      { skill: 'qian/finance-rollup', rank: 1 },
      { skill: 'dev/quarterly-report-assembler', rank: 2 },
      { skill: 'hexu/table-align', rank: 3 },
      { skill: 'linzhi/doc-convert', rank: 4 },
      { skill: 'yuhan/file-batch', rank: 5 },
      { skill: 'hexu/slide-maker', rank: 6 },
    ],
    invocations: [{ skill: 'dev/quarterly-report-assembler', version: '0.9.4', at: '2026-09-28 11:26', rank: 2 }],
  },
  {
    kind: 'direct',
    id: 'e13',
    skill: 'official/startup',
    version: '1.0.0',
    at: '2026-09-20 08:50',
  },
  {
    kind: 'search',
    id: 'e14',
    query: 'yaml 校验',
    at: '2026-09-11 14:05',
    total: 2,
    results: [
      { skill: 'yuhan/skill-lint', rank: 1 },
      { skill: 'lijinzhao/code-audit', rank: 2 },
    ],
    invocations: [],
  },
]

export const USAGE_SEARCHES = USAGE_INTERACTIONS.filter((row) => row.kind === 'search')
export const USAGE_CALLS = USAGE_INTERACTIONS.filter(
  (row) => (row.kind === 'search' && row.invocations.length > 0) || row.kind === 'direct',
)

export interface UsageStats {
  /** 调用次数：搜索之后调到的 + 直接调用的。 */
  calls: number
  searches: number
  /** 搜索之后调到了结果的那几次。 */
  converted: number
  missed: number
  skills: number
  /** 最近一次调用，`YYYY-MM-DD HH:MM`；一次都没有调用时为 null。 */
  latest: string | null
  /** 按调用次数排，同次数的最近用过的在前。 */
  top: { skill: string; calls: number; last: string }[]
}

/** 统计全部由明细现算，不另存一份数——两处各写一份，第一次改数就会对不上。
 *
 *  **按 skill 过滤时数的是这条 skill 自己的单位**（v60）：搜索次数数「结果里出现过它」的搜索，
 *  转化数「其中调用了它」的搜索；调用只收它自己的调用。一次搜索同时还调用了别的 skill 时，
 *  明细行仍列全部调用——行是整次交互，统计是这条 skill 的账。两种数读同一批行，单位不同。
 */
export function usageStats(rows: UsageInteraction[], skillQuery = ''): UsageStats {
  const wanted = skillQuery.trim()
  const calls: { skill: string; at: string }[] = []
  let searches = 0
  let converted = 0
  for (const row of rows) {
    if (row.kind === 'direct') {
      if (wanted !== '' && !matchesSkill(row.skill, wanted)) continue
      calls.push({ skill: row.skill, at: row.at })
      continue
    }
    if (wanted === '') {
      searches += 1
      if (row.invocations.length === 0) continue
      converted += 1
      for (const invocation of row.invocations) calls.push({ skill: invocation.skill, at: invocation.at })
      continue
    }
    const appeared = row.results.some((result) => matchesSkill(result.skill, wanted))
    const invoked = row.invocations.filter((invocation) => matchesSkill(invocation.skill, wanted))
    if (!appeared && invoked.length === 0) continue
    searches += 1
    if (invoked.length > 0) converted += 1
    for (const invocation of invoked) calls.push({ skill: invocation.skill, at: invocation.at })
  }

  const bySkill = new Map<string, { calls: number; last: string }>()
  for (const call of calls) {
    const count = bySkill.get(call.skill)
    if (count === undefined) bySkill.set(call.skill, { calls: 1, last: call.at })
    else {
      count.calls += 1
      if (call.at > count.last) count.last = call.at
    }
  }
  const top = [...bySkill.entries()]
    .map(([skill, count]) => ({ skill, calls: count.calls, last: count.last }))
    .sort((a, b) => (b.calls - a.calls) || (a.last < b.last ? 1 : a.last > b.last ? -1 : 0))
  const latest = calls.reduce<string | null>((max, call) => (max === null || call.at > max ? call.at : max), null)

  return { calls: calls.length, searches, converted, missed: searches - converted, skills: bySkill.size, latest, top }
}

/* ------------------------------------------------------- one skill's usage (v60) */

/** 档位是**当前值**，不随区间变；表里只按区间算调用、提交版本与最近活跃。 */
export const SKILL_GRANTS = [
  { handle: 'yuhan', role: '维护' },
  { handle: 'hexu', role: '写入' },
  { handle: 'linzhi', role: '调用' },
] as const

/** 这条 skill 的调用事件（所有者 + 协作者），「全部使用」与「我的使用」都按区间现算。
    `who` 是使用者；地址都是 `dev/pdf-tools`。 */
export const SKILL_CALLS: { who: string; version: string; at: string }[] = [
  { who: 'dev', version: '1.2.3', at: '2026-11-10 09:41' },
  { who: 'dev', version: '1.2.3', at: '2026-11-08 14:03' },
  { who: 'dev', version: '1.2.2', at: '2026-11-02 10:15' },
  { who: 'dev', version: '1.2.2', at: '2026-10-21 10:05' },
  { who: 'dev', version: '1.2.1', at: '2026-10-05 09:20' },
  { who: 'dev', version: '1.2.1', at: '2026-09-18 16:40' },
  { who: 'dev', version: '1.2.0', at: '2026-08-20 11:11' },
  { who: 'yuhan', version: '1.2.3', at: '2026-11-09 11:08' },
  { who: 'yuhan', version: '1.2.3', at: '2026-11-07 09:15' },
  { who: 'yuhan', version: '1.2.3', at: '2026-11-04 18:22' },
  { who: 'yuhan', version: '1.2.2', at: '2026-10-28 14:30' },
  { who: 'yuhan', version: '1.2.2', at: '2026-10-14 09:40' },
  { who: 'yuhan', version: '1.2.1', at: '2026-09-30 15:10' },
  { who: 'yuhan', version: '1.2.1', at: '2026-09-02 10:00' },
  { who: 'yuhan', version: '1.2.0', at: '2026-08-18 13:05' },
  { who: 'hexu', version: '1.2.3', at: '2026-11-06 16:44' },
  { who: 'hexu', version: '1.2.2', at: '2026-10-25 11:20' },
  { who: 'hexu', version: '1.2.1', at: '2026-09-12 14:00' },
  { who: 'linzhi', version: '1.2.2', at: '2026-10-28 17:10' },
  { who: 'linzhi', version: '1.2.0', at: '2026-08-25 09:30' },
]

/** 公域读者：只在公开 skill 的「全部使用」里计数，不进使用者表——那张表只列协作者。 */
export const PUBLIC_CALLS: { who: string; version: string; at: string }[] = [
  { who: 'reader-42', version: '1.2.3', at: '2026-11-10 07:20' },
  { who: 'study-group', version: '1.2.3', at: '2026-11-03 12:00' },
  { who: 'reader-42', version: '1.2.2', at: '2026-10-20 08:45' },
  { who: 'notebook-user', version: '1.2.1', at: '2026-09-25 19:30' },
  { who: 'intern-3', version: '1.2.1', at: '2026-09-10 10:05' },
  { who: 'analyst-7', version: '1.2.0', at: '2026-08-28 14:20' },
  { who: 'reviewer-9', version: '1.2.0', at: '2026-08-18 09:10' },
  { who: 'reader-42', version: '1.2.0', at: '2026-08-15 16:50' },
]

/** 提交版本的事件：「提交版本」按区间数，档位仍是当前值。 */
export const SKILL_SUBMISSIONS: { who: string; at: string }[] = [
  { who: 'dev', at: '2026-11-08' },
  { who: 'dev', at: '2026-11-05' },
  { who: 'dev', at: '2026-11-01' },
  { who: 'dev', at: '2026-10-28' },
  { who: 'dev', at: '2026-10-20' },
  { who: 'dev', at: '2026-10-12' },
  { who: 'dev', at: '2026-10-02' },
  { who: 'dev', at: '2026-09-28' },
  { who: 'dev', at: '2026-09-20' },
  { who: 'dev', at: '2026-09-12' },
  { who: 'dev', at: '2026-09-05' },
  { who: 'dev', at: '2026-09-01' },
  { who: 'yuhan', at: '2026-11-06' },
  { who: 'yuhan', at: '2026-10-29' },
  { who: 'yuhan', at: '2026-10-15' },
  { who: 'yuhan', at: '2026-10-01' },
  { who: 'yuhan', at: '2026-09-10' },
  { who: 'hexu', at: '2026-11-02' },
]

/** 累计入库：不随区间变；卡片标签写「累计入库」把口径说清。 */
export const SKILL_JOINS = 45

/** 非所有者在这个 skill 上的「我的记录」（第 3 层）；按区间筛。 */
export const MY_CALLS_FOR_SKILL = [
  { id: 'm1', skill: 'dev/pdf-tools', version: '1.2.3', at: '2026-11-10 09:41' },
  { id: 'm2', skill: 'dev/pdf-tools', version: '1.2.3', at: '2026-11-09 11:08' },
  { id: 'm3', skill: 'dev/pdf-tools', version: '1.2.2', at: '2026-11-08 10:02' },
  { id: 'm4', skill: 'dev/pdf-tools', version: '1.2.2', at: '2026-10-20 15:30' },
  { id: 'm5', skill: 'dev/pdf-tools', version: '1.2.1', at: '2026-09-12 09:10' },
]

/** 公开面的分桶口径（ADR 0149 / 0152）：分桶值本身是一句话，不再另挂单位；0 落在「少于 10」。 */
export function accountsLabel(count: number): string {
  if (count < 10) return '少于 10 个账号'
  if (count < 100) return '10+'
  if (count < 1000) return '100+'
  return '1k+'
}

export function callsLabel(count: number): string {
  if (count < 10) return '少于 10 次调用'
  if (count < 100) return '10+'
  if (count < 1000) return '100+'
  if (count < 10000) return '1k+'
  if (count < 1000000) return '1万+'
  return '100万+'
}
