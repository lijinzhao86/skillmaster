import { useCallback, useEffect, useState } from 'react'
import { go, param, query, replaceAddress } from '../address'
import { listUsage, getUsageStats } from '../api/usage'
import type { UsagePage as UsagePageData, UsageStats } from '../api/usage'
import Pager from '../components/Pager'
import UsageLog from '../components/usage/UsageLog'
import UsageStatsCards from '../components/usage/UsageStats'

const TABS = [
  { key: 'detail', label: '明细' },
  { key: 'stats', label: '统计' },
] as const

const PER_PAGE = 10

/**
 * 使用记录（v58）：明细 / 统计，只看自己的数据。
 *
 * 状态全在地址里（`?tab=` / `?p=` / `?open=`），刷新、转发都还在；页签是真正的链接。
 * 明细与统计由同一份事件算得，页面不再存第二份数。
 */
export default function UsagePage() {
  const asked = param('tab')
  const tab = TABS.some((item) => item.key === asked) ? (asked as string) : 'detail'
  const askedPage = Number.parseInt(param('p') ?? '', 10)
  const page = Number.isNaN(askedPage) || askedPage < 1 ? 1 : askedPage
  const [open, setOpen] = useState<string | null>(param('open'))

  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [data, setData] = useState<UsagePageData | null>(null)
  const [stats, setStats] = useState<UsageStats | null>(null)

  // The page the data is for, clamped once the total is known. Deriving it in render (rather than
  // navigating after an empty answer) is what keeps a stale `?p=5` from drawing the empty sentence
  // for a frame, and `replaceAddress` keeps the correction out of the Back stack.
  const pages = data === null ? page : Math.max(1, Math.ceil(data.total / PER_PAGE))
  const current = Math.min(page, pages)

  useEffect(() => {
    let live = true
    setState('loading')
    const load =
      tab === 'stats'
        ? getUsageStats().then((result) => {
            if (!live) return
            if (result.ok) {
              setStats(result.data)
              setState('ready')
            } else {
              setState('failed')
            }
          })
        : listUsage(current, PER_PAGE).then((result) => {
            if (!live) return
            if (result.ok) {
              setData(result.data)
              setState('ready')
            } else {
              setState('failed')
            }
          })
    void load
    return () => {
      live = false
    }
  }, [tab, current])

  useEffect(() => {
    if (tab !== 'detail' || data === null || page <= pages) return
    // Only the page number is corrected; `open` (and anything else already in the address) stays,
    // so an expanded row survives the rewrite and a reload still lands on it.
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

  const href = (next: string): string => {
    const params = new URLSearchParams()
    if (next !== 'detail') params.set('tab', next)
    return params.toString() === '' ? '/usage' : `/usage?${params}`
  }

  const pageHref = (target: number): string => {
    const params = new URLSearchParams()
    if (tab !== 'detail') params.set('tab', tab)
    if (target > 1) params.set('p', String(target))
    return params.toString() === '' ? '/usage' : `/usage?${params}`
  }

  const settled = state !== 'loading' && state !== 'failed'
  const shown = tab === 'detail' ? (data?.items ?? []) : []

  return (
    <section className="page" id="page-usage">
      <header className="page-head">
        <h1>使用记录</h1>
        <span className="count">近 90 天</span>
        <span className="count-note">仅记录搜索与调用</span>
      </header>

      <nav className="skill-tabs list-tabs" aria-label="使用记录的两份列表" hidden={!settled}>
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

      {settled && tab === 'stats' && stats !== null && <UsageStatsCards stats={stats} />}

      {settled && tab === 'detail' && (
        <>
          {shown.length > 0 && (
            <p className="usage-intro">
              搜索与随后的调用显示在同一行；点搜索行查看当次搜索结果。
            </p>
          )}

          {shown.length > 0 && <UsageLog items={shown} open={open} onToggle={toggle} />}

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

          <p className="empty" hidden={!settled || shown.length > 0 || (page > pages && data !== null && data.total > 0)}>
            暂无使用记录。搜索或调用 skill 后，记录会显示在这里。
          </p>
        </>
      )}
    </section>
  )
}
