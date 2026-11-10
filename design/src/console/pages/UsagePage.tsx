/**
 * 使用记录：**明细 / 统计**，只给本人看自己的数据（v58）。
 *
 * **明细是一张日志表**：固定时间列、事件一句话、结果与状态分开；最新在上。
 * 一次交互一行，搜索与直接调用共用同一套列——直接调用用终端图标区分，不写四个字。
 *
 * **搜索结果放在下拉里**，不推开表格：点行尾箭头，浮层按当时的排名列出返回结果。这样一行行
 * 扫历史时，行高不变；要看「当时还返回了什么」也只是一次点击。
 */

import { Fragment, useEffect } from 'react'
import { replace } from '../address'
import Pager from '../components/Pager'
import { USAGE_INTERACTIONS, usageStats } from '../fixtures/usage'
import type { SearchInteraction, UsageInteraction } from '../fixtures/usage'
import type { DemoPageProps } from './types'

const PER_PAGE = 10

/** `2026-11-10 09:41` → 标题里那两截：`11 月 10 日` 与 `09:41`。 */
function when(at: string) {
  const [date, time] = at.split(' ')
  const [, month, day] = date.split('-')
  return { day: `${Number(month)} 月 ${Number(day)} 日`, time }
}

const TABS = [
  { key: 'detail', label: '明细', href: '?page=usage' },
  { key: 'stats', label: '统计', href: '?page=usage&tab=stats' },
] as const

function SearchMark() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="10.5" cy="10.5" r="5.8" stroke="currentColor" strokeWidth="1.8" />
      <path d="m15 15 4.5 4.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  )
}

/** 直接调用：一个终端提示符，不是「直接调用」四个字。 */
function TerminalMark() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M5.5 7.5 10 12l-4.5 4.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M12.5 16.5h6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  )
}

function Chevron({ open }: { open: boolean }) {
  return (
    <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path
        d={open ? 'M4 6l4 4 4-4' : 'M6 4l4 4-4 4'}
        stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"
      />
    </svg>
  )
}

/* **行内展开，不是浮层**（2026-11-10 用户定，参照审计日志与 Datadog Log Explorer 的展开行）：
   面板与表格同宽、把后面的行往下推。浮层会盖住别的行、左右也接不上表格的边，
   而「这张表」本来就是一个可以一段一段展开的文档。 */
function SearchDetail({ row }: { row: SearchInteraction }) {
  /* 一次搜索可能调用了多条结果：**被调用的那几条都加粗并写明「已调用」**，不是只标一条。 */
  const invokedRanks = new Set(row.invocations.map((invocation) => invocation.rank))
  const total = row.total ?? row.results.length
  return (
    <div className="usage-detail" id={'usage-detail-' + row.id} aria-label="搜索返回的结果">
      {/* **只存前 50 个时要说出来**：不说，读者会把「只记了这么多」当成「当时只有这么多」。 */}
      <p className="usage-detail-head">
        搜索返回 {total} 个结果{row.total !== null && row.results.length < total ? `，这里只记录前 ${row.results.length} 个` : ''}，按搜索时的顺序排列。
      </p>
      <ol className="usage-detail-list">
        {row.results.map((result) => (
          <li key={result.rank} className={invokedRanks.has(result.rank) ? 'is-invoked' : undefined}>
            <span className="usage-result-rank">#{result.rank}</span>
            <a className="notice-skill" href="?page=skill">{result.skill}</a>
            {invokedRanks.has(result.rank) && <span className="usage-detail-invoked">已调用</span>}
          </li>
        ))}
      </ol>
    </div>
  )
}

function SearchRow({ row, open, onToggle }: {
  row: SearchInteraction
  open: boolean
  onToggle: () => void
}) {
  /* **0 条结果没有可展开的东西**：不画折角、整行不可点。总数可空（API 搜索不给总数），
     空的时候用本次返回的条数说「N 个结果」。 */
  const expandable = row.results.length > 0
  const total = row.total ?? row.results.length
  return (
    <div
      className={'usage-log-row' + (expandable ? ' is-toggleable' : '') + (open ? ' is-open' : '') + (row.invocations.length > 1 ? ' is-multi' : '')}
      role="row"
      data-kind="search"
      /* **整行可点**（2026-11-10 用户定）：折角太小、也没人去找它。行里的链接和按钮各做各的；
         文字被选中时不动——想复制的时候，点击不应该先把面板收起来。 */
      onClick={(event) => {
        if (!expandable) return
        const target = event.target as HTMLElement
        if (target.closest('a, button')) return
        if ((window.getSelection()?.toString() ?? '') !== '') return
        onToggle()
      }}
    >
      <time className="usage-log-time" role="cell" title={row.at}>{row.at.slice(5)}</time>

      <span className="usage-log-event" role="cell">
        <span className="usage-log-icon" aria-hidden="true"><SearchMark /></span>
        <span className="usage-log-query" title={row.query}>“{row.query}”</span>
        <span className="usage-log-count">{total} 个结果</span>
      </span>

      <span className="usage-log-result" role="cell">
        {row.invocations.length > 0 ? (
          /* **调了几个就列几行**（2026-11-10 用户定）：一次搜索调了三条，就把三条都写出来——
             藏进悬停的「+2」没人会注意。多行时整行改顶部对齐，时间与折角仍对第一行。 */
          <span className="usage-invocations">
            {row.invocations.map((invocation) => (
              <span className="usage-invocation" key={invocation.skill + '@' + invocation.at}>
                {/* **行里不放链接**：整行是开关，点哪里都展开。要看那条 skill，展开后在结果列表里点。 */}
                <span className="notice-skill" title={invocation.skill}>{invocation.skill}</span>
                <span className="usage-version">@{invocation.version}</span>
                <span className="usage-rank usage-rank-full">第 {invocation.rank} 个结果</span>
                <span className="usage-rank usage-rank-short" title={'第 ' + invocation.rank + ' 个结果'}>
                  #{invocation.rank}
                </span>
                {row.invocations.length > 1 && (
                  <span className="usage-invocation-at" title={invocation.at}>{invocation.at.slice(11)}</span>
                )}
              </span>
            ))}
          </span>
        ) : (
          <span className="usage-log-none">未调用</span>
        )}
      </span>

      {expandable ? (
        <button
          type="button"
          className="usage-log-toggle"
          id={'usage-toggle-' + row.id}
          aria-expanded={open}
          aria-controls={'usage-detail-' + row.id}
          aria-label={open ? '收起搜索结果' : '展开搜索结果'}
          onClick={onToggle}
        >
          <Chevron open={open} />
        </button>
      ) : (
        <span className="usage-log-toggle-placeholder" aria-hidden="true" />
      )}
    </div>
  )
}

function DirectRow({ row }: { row: Extract<UsageInteraction, { kind: 'direct' }> }) {
  return (
    <div className="usage-log-row" role="row" data-kind="direct">
      <time className="usage-log-time" role="cell" title={row.at}>{row.at.slice(5)}</time>

      <span className="usage-log-event" role="cell">
        <span className="usage-log-icon is-direct" title="直接调用" aria-label="直接调用">
          <TerminalMark />
        </span>
      </span>

      <span className="usage-log-result" role="cell">
        <a className="notice-skill usage-log-skill" href="?page=skill" title={row.skill}>{row.skill}</a>
        <span className="usage-version">@{row.version}</span>
      </span>

      <span className="usage-log-toggle-placeholder" aria-hidden="true" />
    </div>
  )
}

export default function UsagePage({ active, state, params }: DemoPageProps) {
  const asked = params.get('tab')
  const tab = TABS.some((item) => item.key === asked) ? (asked as string) : 'detail'
  const settled = state !== 'loading' && state !== 'failed'
  /* 空态仍然画页签：它是「这一页现在没有记录」，不是「这一页打不开」。 */
  const nothing = state === 'empty'
  const openId = params.get('open')
  const long = params.get('long') === '1'
  const askedPage = parseInt(params.get('p') ?? '', 10)

  /* **倒序是日志表的第一条读法**：最近发生的在最上面。 */
  const rows = (nothing ? [] : USAGE_INTERACTIONS)
    .slice()
    .sort((a, b) => (a.at < b.at ? 1 : a.at > b.at ? -1 : 0))
    .map((row) => {
      if (!long || row.id !== 'e1' || row.kind !== 'search') return row
      return {
        ...row,
        query: '把几条业务线的季度数据合并成一份对外报告，统一币种与口径并重排章节',
        invocations: row.invocations.map((invocation) => ({
          ...invocation,
          skill: 'design-system-team/vendor-screenshot-annotator-with-region-notes-and-tickets',
        })),
      }
    })

  /* 统计用样张本身算，不用被 `long` 改写过的 rows——那一张只改显示，不该改到数。 */
  const stats = usageStats(nothing ? [] : USAGE_INTERACTIONS)
  let page = Number.isNaN(askedPage) || askedPage < 1 ? 1 : askedPage
  const pages = Math.max(1, Math.ceil(rows.length / PER_PAGE))
  if (page > pages) page = pages
  const shown = rows.slice((page - 1) * PER_PAGE, (page - 1) * PER_PAGE + PER_PAGE)

  const writeAddress = (next: { tab?: string; page?: number; open?: string | null }) => {
    const copy = new URLSearchParams()
    copy.set('page', 'usage')
    const nextTab = next.tab ?? tab
    if (nextTab !== 'detail') copy.set('tab', nextTab)
    const nextPage = next.page ?? page
    if (nextPage > 1) copy.set('p', String(nextPage))
    const nextOpen = next.open === undefined ? openId : next.open
    if (nextOpen !== null && nextOpen !== '') copy.set('open', nextOpen)
    const askedState = params.get('state')
    if (askedState !== null) copy.set('state', askedState)
    if (long) copy.set('long', '1')
    replace('?' + copy.toString())
  }

  /* **展开要能关，但面板里的点击不能关。** 点别处、按 Esc 收起，焦点还给折角。
     **`.usage-detail` 必须在白名单里**：面板里要能选字、复制、点链接；漏掉它（或沿用旧的
     `.usage-popover` 类名）会让每一次 mousedown 都被当成「点在外面」，面板一碰就收。 */
  useEffect(() => {
    if (openId === null) return
    const close = () => {
      writeAddress({ open: null })
      document.getElementById('usage-toggle-' + openId)?.focus()
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        close()
      }
    }
    const onDown = (event: MouseEvent) => {
      const target = event.target as HTMLElement | null
      /* 面板与行都算「里面」：面板里要能选字复制；行自己管开关（整行可点）。 */
      if (target?.closest('.usage-detail') || target?.closest('.usage-log-row')) return
      close()
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onDown)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onDown)
    }
  }, [openId])

  return (
    <section className="page" id="page-usage" hidden={!active}>
      <header className="page-head">
        <h1>使用记录</h1>
        <span className="count">近 90 天</span>
        {/* **先交代这一页的范围**：名字是「使用记录」，内容却只有搜索与调用。不说一句，
            人会去找自己的读写与分享——找不到，就会以为记录丢了。挨着「近 90 天」，
            与「待上线」那句「只列出了最近 500 个」同一条规矩。 */}
        <span className="count-note">仅记录搜索与调用</span>
      </header>

      <nav className="skill-tabs list-tabs" id="usageTabs" aria-label="使用记录的两份列表" hidden={!settled}>
        {TABS.map((item) => (
          <a
            key={item.key}
            className={'skill-tab' + (tab === item.key ? ' is-current' : '')}
            data-show={item.key}
            href={item.href}
            aria-current={tab === item.key ? 'page' : undefined}
            onClick={(event) => { event.preventDefault(); writeAddress({ tab: item.key, page: 1, open: null }) }}
          >
            {item.label}
          </a>
        ))}
      </nav>

      <p className="loading" data-when="loading" hidden={state !== 'loading'}>加载中…</p>
      <div className="banner" data-when="failed" hidden={state !== 'failed'}>
        使用记录暂时无法加载，请稍后重试。
      </div>

      {settled && tab === 'stats' && (
        <>
          <div className="stats" id="usageStats" style={{ marginTop: 20 }}>
            {/* **单位写进标签，数字下面不再挂「次 / 个」**：标签已经说了是「次数」「人数」，
                数字下面再来一个单位是同一件事说两遍；四个数同高，读起来也是一列数。 */}
            <div className="stat">
              <span className="stat-head">调用次数</span>
              <span className="stat-body"><span className="stat-number">{stats.calls}</span></span>
            </div>
            <div className="stat">
              <span className="stat-head">搜索次数</span>
              <span className="stat-body"><span className="stat-number">{stats.searches}</span></span>
            </div>
            <div className="stat">
              <span className="stat-head">使用过的 skill</span>
              <span className="stat-body"><span className="stat-number">{stats.skills}</span></span>
            </div>
            <div className="stat">
              <span className="stat-head">最近使用</span>
              <span className="stat-body">
                <span className="stat-number" style={{ fontSize: 18 }}>
                  {stats.latest === null ? '—' : when(stats.latest).day}
                </span>
                {stats.latest !== null && <span className="stat-unit">{when(stats.latest).time}</span>}
              </span>
            </div>
          </div>

          {/* **统计与明细同一份事实**：这里只说「搜索有没有把人带到 skill」，别的数交给上面四张卡。 */}
          {stats.searches > 0 && (
            <p className="usage-note" id="usageSearchOutcome">
              {stats.searches} 次搜索中，{stats.converted} 次最终调用了结果，{stats.missed} 次未调用。
            </p>
          )}

          {stats.top.length > 0 && (
            <div className="block" style={{ marginTop: 8 }}>
              <h2>按调用次数</h2>
              <p className="usage-note">仅统计调用；搜索后未调用的不计入。</p>
              <table className="usage-table usage-top" id="usageTop">
                <thead>
                  <tr>
                    <th>名字</th>
                    <th>调用</th>
                    {/* 条是按最大值的相对长度，不是占全部的百分比——所以这一格不写「占比」，
                        只留给看的人一个形状；数在左边那一列。 */}
                    <th className="is-bar"><span className="visually-hidden">相对使用量</span></th>
                    <th>最近使用</th>
                  </tr>
                </thead>
                <tbody>
                  {stats.top.map((item) => (
                    <tr key={item.skill}>
                      <td><a className="notice-skill" href="?page=skill">{item.skill}</a></td>
                      <td>{item.calls} 次</td>
                      <td className="is-bar">
                        <span
                          className="usage-bar-track"
                          title={`${item.calls} 次，最多的一个是 ${stats.top[0].calls} 次`}
                        >
                          <span
                            className="usage-bar-fill"
                            style={{ width: `${Math.round((item.calls / stats.top[0].calls) * 100)}%` }}
                          />
                        </span>
                      </td>
                      <td className="usage-last">{item.last.slice(5)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {settled && tab === 'detail' && (
        <>
          {shown.length > 0 && (
            <p className="usage-intro">搜索与随后的调用显示在同一行；点搜索行查看当次搜索结果。</p>
          )}
          <div className="usage-log" id="usageList" role="table" aria-label="使用记录" hidden={shown.length === 0}>
            <div className="usage-log-head" role="row">
              <span role="columnheader">时间</span>
              <span role="columnheader">操作</span>
              <span role="columnheader">调用的 skill</span>
              <span aria-hidden="true" />
            </div>

            {shown.map((row) => (
              <Fragment key={row.id}>
                {row.kind === 'search' ? (
                  <SearchRow
                    row={row}
                    open={openId === row.id}
                    onToggle={() => writeAddress({ open: openId === row.id ? null : row.id })}
                  />
                ) : (
                  <DirectRow row={row} />
                )}
                {row.kind === 'search' && row.results.length > 0 && openId === row.id && <SearchDetail row={row} />}
              </Fragment>
            ))}
          </div>

          {shown.length > 0 && (
            <div className="pager-row">
              <Pager
                id="usagePager"
                total={rows.length}
                page={page}
                hrefFor={(target) => {
                  const copy = new URLSearchParams()
                  copy.set('page', 'usage')
                  if (target > 1) copy.set('p', String(target))
                  const askedState = params.get('state')
                  if (askedState !== null) copy.set('state', askedState)
                  if (long) copy.set('long', '1')
                  return '?' + copy.toString()
                }}
                onGo={(target) => writeAddress({ page: target, open: null })}
              />
            </div>
          )}

          <p className="empty" id="usageEmpty" hidden={!settled || shown.length > 0}>
            暂无使用记录。搜索或调用 skill 后，记录会显示在这里。
          </p>
        </>
      )}
    </section>
  )
}
