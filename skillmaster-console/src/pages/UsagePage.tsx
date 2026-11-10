import { useCallback, useEffect, useRef, useState } from 'react'
import { go, param, query, replaceAddress } from '../address'
import { listUsage, getUsageStats } from '../api/usage'
import type { UsagePage as UsagePageData, UsageStats } from '../api/usage'
import Pager from '../components/Pager'
import SearchBox from '../components/SearchBox'
import UsageLog from '../components/usage/UsageLog'
import UsageRange from '../components/usage/UsageRange'
import UsageStatsCards from '../components/usage/UsageStats'
import {
  DEFAULT_RANGE,
  MAX_SKILL_LENGTH,
  clampSkill,
  rangeFromParams,
  rangeInstants,
  rangeLabel,
  withRange,
} from '../usageRange'
import type { UsageRange as UsageRangeValue } from '../usageRange'

const TABS = [
  { key: 'detail', label: '明细' },
  { key: 'stats', label: '统计' },
] as const

const PER_PAGE = 10

/**
 * 使用记录（v58；v60 加时间与 skill 筛选）。
 *
 * 明细 / 统计，只看自己的数据。**整页同一组筛选**：时间范围与 skill 关键词都写进地址，
 * 两个页签、页码与展开行共用；统计由服务端按同一组参数算，页面不另存一份。
 *
 * **筛选控件同时有 state 与地址。** `replaceAddress` 只改 `history`，不会触发 React 重渲染；
 * 只写地址的话，输入框会被受控值弹回、点预设也不会重查。所以这两个值先落 state、再写地址，
 * 地址仍负责刷新与转发。
 */
export default function UsagePage() {
  const asked = param('tab')
  const tab = TABS.some((item) => item.key === asked) ? (asked as string) : 'detail'
  const askedPage = Number.parseInt(param('p') ?? '', 10)
  const page = Number.isNaN(askedPage) || askedPage < 1 ? 1 : askedPage
  const [open, setOpen] = useState<string | null>(param('open'))
  const [range, setRange] = useState<UsageRangeValue>(() => rangeFromParams(query()))
  /* **What was asked, not what is being typed.** The box is a library-tier search (every term is
     a server read), so the draft waits 250 ms and the request follows the last applied word —
     the same split as 我的 skill. The address carries the applied word, never half of one. */
  const [skill, setSkill] = useState(() => clampSkill((param('skill') ?? '').trim()))
  const [draft, setDraft] = useState(skill)

  const { since, until } = rangeInstants(range)

  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [data, setData] = useState<UsagePageData | null>(null)
  const [stats, setStats] = useState<UsageStats | null>(null)
  /* **Only the first read blanks the page.** A filter change keeps the previous rows and the
     toolbar mounted, so typing never loses focus to a refetch. */
  const loaded = useRef(false)

  // The page the data is for, clamped once the total is known. Deriving it in render (rather than
  // navigating after an empty answer) is what keeps a stale `?p=5` from drawing the empty sentence
  // for a frame, and `replaceAddress` keeps the correction out of the Back stack.
  const pages = data === null ? page : Math.max(1, Math.ceil(data.total / PER_PAGE))
  const current = Math.min(page, pages)

  useEffect(() => {
    let live = true
    if (!loaded.current) setState('loading')
    const window = { since, until, skill }
    const load =
      tab === 'stats'
        ? getUsageStats(window).then((result) => {
            if (!live) return
            if (result.ok) {
              setStats(result.data)
              loaded.current = true
              setState('ready')
            } else {
              setState('failed')
            }
          })
        : listUsage(current, PER_PAGE, window).then((result) => {
            if (!live) return
            if (result.ok) {
              setData(result.data)
              loaded.current = true
              setState('ready')
            } else {
              setState('failed')
            }
          })
    void load
    return () => {
      live = false
    }
  }, [tab, current, since, until, skill])

  useEffect(() => {
    if (tab !== 'detail' || data === null || page <= pages) return
    // Only the page number is corrected; filters, `open` (and anything else already in the address)
    // stay, so an expanded row survives the rewrite and a reload still lands on it.
    const next = query()
    if (pages > 1) next.set('p', String(pages))
    else next.delete('p')
    replaceAddress(next.toString() === '' ? window.location.pathname : `?${next}`)
  }, [tab, data, page, pages])

  const toggle = useCallback((key: string | null) => {
    setOpen(key)
    const next = query()
    if (key === null) next.delete('open')
    else next.set('open', key)
    replaceAddress(next.toString() === '' ? window.location.pathname : `?${next}`)
  }, [])

  /** Writes the range / skill / tab / page / open state back, keeping the rest of the address. */
  const writeAddress = (next: {
    tab?: string
    page?: number
    open?: string | null
    range?: UsageRangeValue
    skill?: string
  }): void => {
    const nextRange = next.range ?? range
    const nextSkill = clampSkill((next.skill ?? skill).trim())
    const nextOpen = next.open === undefined ? open : next.open
    if (next.range !== undefined) setRange(nextRange)
    if (next.skill !== undefined) {
      setSkill(nextSkill)
      // A clear or an applied word moves the box with it; typing does not come through here.
      setDraft(nextSkill)
    }
    if (next.open !== undefined) setOpen(nextOpen)

    const params = query()
    const nextTab = next.tab ?? tab
    if (nextTab !== 'detail') params.set('tab', nextTab)
    else params.delete('tab')
    const nextPage = next.page ?? current
    if (nextPage > 1) params.set('p', String(nextPage))
    else params.delete('p')
    if (nextOpen === null || nextOpen === '') params.delete('open')
    else params.set('open', nextOpen)
    withRange(params, nextRange)
    if (nextSkill !== '') params.set('skill', nextSkill)
    else params.delete('skill')
    replaceAddress(params.toString() === '' ? window.location.pathname : `?${params}`)
  }

  /* **The timer writes through the latest writer.** It fires 250 ms after the last keystroke, and
     by then the range (or the page, or the open row) may have moved; running the closure captured
     at the keystroke would write that older state back and undo the newer choice. The ref is
     reassigned every render, so the timer sees whatever the address should be now. */
  const writeLatest = useRef(writeAddress)
  writeLatest.current = writeAddress

  /* **A quiet 250 ms, then the word is asked for.** Every keystroke rearms it; a pasted address
     is one read, not sixty. Clearing through the empty state writes the same applied value, so
     the timer collapses to a no-op. */
  useEffect(() => {
    const wanted = clampSkill(draft.trim())
    if (wanted === skill) return
    const timer = window.setTimeout(() => {
      writeLatest.current({ skill: wanted, page: 1, open: null })
    }, 250)
    return () => window.clearTimeout(timer)
  }, [draft, skill])

  const href = (next: string): string => {
    const params = query()
    if (next !== 'detail') params.set('tab', next)
    else params.delete('tab')
    params.delete('p')
    params.delete('open')
    return params.toString() === '' ? '/usage' : `/usage?${params}`
  }

  const pageHref = (target: number): string => {
    const params = query()
    if (tab !== 'detail') params.set('tab', tab)
    if (target > 1) params.set('p', String(target))
    else params.delete('p')
    params.delete('open')
    return params.toString() === '' ? '/usage' : `/usage?${params}`
  }

  const settled = state === 'ready'
  const shown = tab === 'detail' ? (data?.items ?? []) : []
  /* The two empty sentences are different questions: "you have nothing yet" comes from the
     server's unfiltered existence flag; "the filters kept nothing" is everything else. */
  const noneAtAll = data !== null && !data.has_any
  const filteredOut = data !== null && data.has_any && shown.length === 0

  return (
    <section className="page" id="page-usage">
      <header className="page-head">
        <h1>使用记录</h1>
        <span className="count-note">仅记录搜索与调用 · 数据保留近 90 天</span>
        {settled && (
          <div className="toolbar">
            <SearchBox
              id="usageSkill"
              value={draft}
              placeholder="搜索 skill 地址"
              label="按 skill 筛选"
              maxLength={MAX_SKILL_LENGTH}
              onChange={setDraft}
            />
            <span className="usage-range">
              <UsageRange
                id="usageRange"
                label="时间范围"
                range={range}
                onChange={(next) => writeAddress({ range: next, page: 1, open: null })}
              />
            </span>
          </div>
        )}
      </header>

      <nav
        className="skill-tabs list-tabs"
        id="usageTabs"
        aria-label="使用记录的两份列表"
        hidden={!settled}
      >
        {TABS.map((item) => (
          <a
            key={item.key}
            className={'skill-tab' + (tab === item.key ? ' is-current' : '')}
            href={href(item.key)}
            aria-current={tab === item.key ? 'page' : undefined}
          >
            {item.label}
          </a>
        ))}
      </nav>

      <p className="loading" hidden={state !== 'loading'}>
        加载中…
      </p>
      <div className="banner" hidden={state !== 'failed'}>
        使用记录暂时无法加载，请稍后重试。
      </div>

      {settled && tab === 'stats' && stats !== null && (
        <UsageStatsCards stats={stats} skill={stats.skill ?? ''} />
      )}

      {settled && tab === 'detail' && (
        <>
          {shown.length > 0 && (
            <p className="usage-intro">
              {skill === ''
                ? '搜索与随后的调用显示在同一行；点搜索行查看当次搜索结果。'
                : `只看${rangeLabel(range)}内与「${skill}」有关的交互；搜索返回过它、随后没调用的行标「搜到过」。`}
            </p>
          )}

          {shown.length > 0 && <UsageLog items={shown} skill={skill} open={open} onToggle={toggle} />}

          {shown.length > 0 && data !== null && (
            <div className="pager-row">
              <Pager
                id="usagePager"
                total={data.total}
                page={current}
                hrefFor={pageHref}
                onGo={(target) => go(pageHref(target))}
              />
            </div>
          )}

          <p
            className="empty"
            id="usageEmpty"
            hidden={!settled || shown.length > 0 || !noneAtAll
              || (page > pages && data !== null && data.total > 0)}
          >
            暂无使用记录。搜索或调用 skill 后，记录会显示在这里。
          </p>
          <p
            className="empty"
            id="usageFilterEmpty"
            hidden={!settled || shown.length > 0 || !filteredOut
              || (page > pages && data !== null && data.total > 0)}
          >
            没有匹配的使用记录。
            <button
              type="button"
              className="link-button"
              id="usageFilterClear"
              onClick={() => writeAddress({ skill: '', range: DEFAULT_RANGE, page: 1, open: null })}
            >
              清除筛选
            </button>
          </p>
        </>
      )}
    </section>
  )
}
