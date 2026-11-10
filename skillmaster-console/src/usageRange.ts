/**
 * 使用记录与 skill「使用」页签的时间范围（v60）。
 *
 * **口径是用户本地自然日、含首尾两天**（`design/README.md` §使用记录的筛选）：今天从本地
 * 00:00 起算，近 7 天是今天与前 6 个自然日。地址里只存「哪一段」——`days=7` 或
 * `from=YYYY-MM-DD&to=YYYY-MM-DD`；真正发给服务端的是两个时刻：开始日本地 00:00 与结束日
 * 次日本地 00:00（不含）。只有浏览器知道用户在哪个时区，所以换算在这里，服务端只认时刻。
 *
 * 手工改坏的区间落回默认 30 天，与页码越界收在末页同一条规矩。
 */

export interface UsageRange {
  /** 预设的天数；自定义时为 null。 */
  days: number | null
  from: string | null
  to: string | null
}

/** 默认看近 30 天：与账单 / 报表 / 采用看板的主流一致（`prd.md` §范围）。 */
export const DEFAULT_RANGE: UsageRange = { days: 30, from: null, to: null }

/** 预设只有这四档；网页不提供任意天数，CLI 的 `--days` 可以。 */
export const PRESET_DAYS = [1, 7, 30, 90] as const

/** The server refuses a longer keyword (code points); the box truncates at the same bound. */
export const MAX_SKILL_LENGTH = 100

/** At most {@link MAX_SKILL_LENGTH} code points, so a paste cannot create an address that 400s. */
export function clampSkill(value: string): string {
  return Array.from(value).slice(0, MAX_SKILL_LENGTH).join('')
}

const DAY = /^\d{4}-\d{2}-\d{2}$/

function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

/** A local calendar day, `YYYY-MM-DD` — never `toISOString()`, which would shift by the offset. */
export function dayOf(date: Date): string {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

export function today(now: Date = new Date()): string {
  return dayOf(now)
}

/** The three numbers of a `YYYY-MM-DD`, checked by the callers before they get here. */
function partsOf(day: string): [number, number, number] {
  const [year, month, date] = day.split('-')
  return [Number(year), Number(month), Number(date)]
}

/**
 * A real calendar day. `2026-09-31` matches the shape but JavaScript's `Date` would silently roll
 * it into 10-01, so the string is written back through `dayOf` and compared.
 */
export function validDay(day: string): boolean {
  if (!DAY.test(day)) return false
  const [year, month, date] = partsOf(day)
  const parsed = new Date(year, month - 1, date, 12)
  return dayOf(parsed) === day
}

export function shiftDay(day: string, delta: number): string {
  const [year, month, date] = partsOf(day)
  return dayOf(new Date(year, month - 1, date + delta, 12))
}

/** The oldest day a range may name: today and the 89 before it. */
export function oldest(todayValue: string): string {
  return shiftDay(todayValue, -89)
}

export function rangeFromParams(params: URLSearchParams, now: Date = new Date()): UsageRange {
  const from = (params.get('from') ?? '').trim()
  const to = (params.get('to') ?? '').trim()
  const current = today(now)
  if (validDay(from) && validDay(to) && from <= to && from >= oldest(current) && to <= current) {
    return { days: null, from, to }
  }
  const days = Number.parseInt(params.get('days') ?? '', 10)
  if ((PRESET_DAYS as readonly number[]).includes(days)) {
    return { days, from: null, to: null }
  }
  return DEFAULT_RANGE
}

/** The two local days a range covers, for the control and the calendar. */
export function rangeBounds(range: UsageRange, now: Date = new Date()): { from: string; to: string } {
  if (range.from !== null && range.to !== null) {
    return { from: range.from, to: range.to }
  }
  const current = today(now)
  return { from: shiftDay(current, -(range.days ?? 30) + 1), to: current }
}

export function rangeLabel(range: UsageRange): string {
  if (range.from !== null && range.to !== null) {
    // **Same year drops the year**: the button already sits next to "近 30 天"; a full `YYYY-MM-DD`
    // on both ends is longer than the popover it opens (design/README.md §使用记录的筛选).
    return range.from.slice(0, 4) === range.to.slice(0, 4)
      ? `${range.from.slice(5)} 至 ${range.to.slice(5)}`
      : `${range.from} 至 ${range.to}`
  }
  const days = range.days ?? 30
  return days === 1 ? '今天' : `近 ${days} 天`
}

/** Writes the range back into a page's parameters, clearing whichever form is not in use. */
export function withRange(params: URLSearchParams, range: UsageRange): URLSearchParams {
  params.delete('days')
  params.delete('from')
  params.delete('to')
  if (range.from !== null && range.to !== null) {
    params.set('from', range.from)
    params.set('to', range.to)
  } else if ((range.days ?? 30) !== 30) {
    params.set('days', String(range.days))
  }
  return params
}

/**
 * The two instants the server reads, as RFC3339 UTC. `until` is the next local midnight, so it can
 * be up to a day in the future; the server tolerates that (it queries `at < until`, and rows in
 * the future cannot exist).
 */
export function rangeInstants(
  range: UsageRange,
  now: Date = new Date(),
): { since: string; until: string } {
  const bounds = rangeBounds(range, now)
  const [fromYear, fromMonth, fromDay] = partsOf(bounds.from)
  const [toYear, toMonth, toDay] = partsOf(bounds.to)
  return {
    since: new Date(fromYear, fromMonth - 1, fromDay, 0, 0, 0, 0).toISOString(),
    until: new Date(toYear, toMonth - 1, toDay + 1, 0, 0, 0, 0).toISOString(),
  }
}

/** Case-insensitive contains, the same rule the server applies to skill addresses. */
export function addressMatches(address: string, keyword: string): boolean {
  return keyword === '' || address.toLowerCase().includes(keyword.toLowerCase())
}
