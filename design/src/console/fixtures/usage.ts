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

/** 统计全部由明细现算，不另存一份数——两处各写一份，第一次改数就会对不上。 */
export function usageStats(rows: UsageInteraction[]): UsageStats {
  const calls: { skill: string; at: string }[] = []
  let searches = 0
  let converted = 0
  for (const row of rows) {
    if (row.kind === 'direct') {
      calls.push({ skill: row.skill, at: row.at })
      continue
    }
    searches += 1
    if (row.invocations.length === 0) continue
    converted += 1
    for (const invocation of row.invocations) calls.push({ skill: invocation.skill, at: invocation.at })
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

/** 所有者看到的协作者统计（第 2 层）：**采用 + 贡献**，不是三种动作的次数
    （2026-11-10 用户定）。读取是调用的影子、文件级写入是噪声，都不指向管理动作；
    换成调用次数（采用度）与提交版本数（贡献度），并用档位解释「提交 0 次」。 */
export const COLLABORATORS = [
  { handle: 'yuhan', role: '维护', invoke: 42, versions: 5, last: '2026-11-09' },
  { handle: 'hexu', role: '写入', invoke: 11, versions: 1, last: '2026-11-06' },
  { handle: 'linzhi', role: '调用', invoke: 4, versions: 0, last: '2026-10-28' },
]

/** 所有者自己的用量（表里第一行是他自己）。调用次数与「我的使用」对得上。 */
export const OWNER_USAGE = { role: '所有者', invoke: 3, versions: 12, last: '2026-11-10' }

/** 非所有者在这个 skill 上的「我的记录」（第 3 层）。 */
export const MY_CALLS_FOR_SKILL = [
  { id: 'm1', skill: 'dev/pdf-tools', version: '1.2.3', at: '2026-11-10 09:41' },
  { id: 'm2', skill: 'dev/pdf-tools', version: '1.2.3', at: '2026-11-09 11:08' },
  { id: 'm3', skill: 'dev/pdf-tools', version: '1.2.2', at: '2026-11-08 10:02' },
]
