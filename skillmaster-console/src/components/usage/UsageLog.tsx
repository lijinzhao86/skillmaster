import { Fragment, useEffect } from 'react'
import type { UsageInteraction, UsageSearch } from '../../api/usage'
import { fullStamp, formatTime } from '../../time'
import { addressMatches } from '../../usageRange'

/**
 * 使用记录的一张日志表（v58）。
 *
 * 搜索与直接调用同一种行：搜索行整行是开关，展开的结果面板与表格同宽、把后面的行往下推；
 * 直接调用没有可展开的东西。一次搜索调了几条就列几行——藏进悬停的「+2」没人会注意。
 *
 * 状态在页面里（地址里的 `open`），这一份只画：点行、点折角都交给 `onToggle`，
 * 「点别处收起」与 Esc 也在页面里，因为它要同时管着地址与焦点。
 */
/**
 * The row's identity in the address (`?open=`) and in the DOM.
 *
 * <p>The server's `activity_event.id` is the key: it is unique across pages, so a link keeps
 * pointing at the same row after the list shifts. The positional form is only a fallback for a
 * response that predates the field — two searches in the same second for the same words would
 * otherwise be one row to React and to `aria-controls`.
 */
export function searchKey(row: UsageSearch, index: number): string {
  return row.id === undefined || row.id === null ? `${row.at}|${row.query}|${index}` : `s${row.id}`
}

export function directKey(row: Extract<UsageInteraction, { kind: 'direct' }>, index: number): string {
  return row.id === undefined || row.id === null ? `direct-${row.at}|${row.skill}|${index}` : `d${row.id}`
}

/** HTML ids may not contain whitespace, and `aria-controls` splits on it. */
function domId(key: string): string {
  return encodeURIComponent(key)
}

function stamp(at: string): string {
  const formatted = formatTime(at)
  const [date, time] = formatted.split(' ')
  const parts = (date ?? '').split('/')
  if (parts.length < 3 || time === undefined) {
    return formatted
  }
  return `${parts[1]}-${parts[2]} ${time}`
}

function skillHref(address: string): string {
  const [namespace, name] = address.split('/')
  return `/skills/${encodeURIComponent(namespace ?? '')}/${encodeURIComponent(name ?? '')}`
}

function SearchMark() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="10.5" cy="10.5" r="5.8" stroke="currentColor" strokeWidth="1.8" />
      <path d="m15 15 4.5 4.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  )
}

function TerminalMark() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M5.5 7.5 10 12l-4.5 4.5"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path d="M12.5 16.5h6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  )
}

function Chevron({ open }: { open: boolean }) {
  return (
    <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path
        d={open ? 'M4 6l4 4 4-4' : 'M6 4l4 4-4 4'}
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

function SearchDetail({ row, id }: { row: UsageSearch; id: string }) {
  const invokedRanks = new Set(row.invocations.map((invocation) => invocation.rank))
  const total = row.total ?? row.results.length
  return (
    <div className="usage-detail" id={'usage-detail-' + domId(id)} aria-label="搜索返回的结果">
      <p className="usage-detail-head">
        搜索返回 {total} 个结果
        {row.total !== null && row.results.length < total
          ? `，这里只记录前 ${row.results.length} 个`
          : ''}
        ，按搜索时的顺序排列。
      </p>
      <ol className="usage-detail-list">
        {row.results.map((result) => (
          <li key={result.rank} className={invokedRanks.has(result.rank) ? 'is-invoked' : undefined}>
            <span className="usage-result-rank">#{result.rank}</span>
            <a className="notice-skill" href={skillHref(result.skill)}>
              {result.skill}
            </a>
            {invokedRanks.has(result.rank) && <span className="usage-detail-invoked">已调用</span>}
          </li>
        ))}
      </ol>
    </div>
  )
}

function SearchRow({
  row,
  index,
  open,
  hitOnly,
  onToggle,
}: {
  row: UsageSearch
  index: number
  open: boolean
  /** 筛选的 skill 只在结果里出现过、没有被调用：行上要说明它为什么在这里。 */
  hitOnly: boolean
  onToggle: () => void
}) {
  const key = searchKey(row, index)
  const expandable = row.results.length > 0
  const total = row.total ?? row.results.length
  return (
    <div
      className={
        'usage-log-row' +
        (expandable ? ' is-toggleable' : '') +
        (open ? ' is-open' : '') +
        (row.invocations.length > 1 ? ' is-multi' : '')
      }
      role="row"
      data-kind="search"
      onClick={(event) => {
        if (!expandable) return
        const target = event.target as HTMLElement
        if (target.closest('a, button')) return
        if ((window.getSelection()?.toString() ?? '') !== '') return
        onToggle()
      }}
    >
      <time className="usage-log-time" role="cell" title={fullStamp(row.at)}>
        {stamp(row.at)}
      </time>

      <span className="usage-log-event" role="cell">
        <span className="usage-log-icon" aria-hidden="true">
          <SearchMark />
        </span>
        <span className="usage-log-query" title={row.query}>
          “{row.query}”
        </span>
        <span className="usage-log-count">{total} 个结果</span>
        {hitOnly && (
          <span className="usage-hit" title="搜索结果里有它，随后没有调用">
            搜到过
          </span>
        )}
      </span>

      <span className="usage-log-result" role="cell">
        {row.invocations.length > 0 ? (
          <span className="usage-invocations">
            {row.invocations.map((invocation) => (
              <span className="usage-invocation" key={invocation.skill + '@' + invocation.at}>
                <span className="notice-skill" title={invocation.skill}>
                  {invocation.skill}
                </span>
                <span className="usage-version">@{invocation.version}</span>
                {row.invocations.length > 1 && (
                  <span className="usage-invocation-at" title={fullStamp(invocation.at)}>
                    {stamp(invocation.at).slice(6)}
                  </span>
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
          id={'usage-toggle-' + domId(key)}
          aria-expanded={open}
          aria-controls={'usage-detail-' + domId(key)}
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
      <time className="usage-log-time" role="cell" title={fullStamp(row.at)}>
        {stamp(row.at)}
      </time>

      <span className="usage-log-event" role="cell">
        <span className="usage-log-icon is-direct" title="直接调用" aria-label="直接调用">
          <TerminalMark />
        </span>
      </span>

      <span className="usage-log-result" role="cell">
        <a className="notice-skill usage-log-skill" href={skillHref(row.skill)} title={row.skill}>
          {row.skill}
        </a>
        <span className="usage-version">@{row.version}</span>
      </span>

      <span className="usage-log-toggle-placeholder" aria-hidden="true" />
    </div>
  )
}

export default function UsageLog({
  items,
  skill,
  open,
  onToggle,
}: {
  items: UsageInteraction[]
  /** The address keyword in the address bar; empty when nothing is filtered. */
  skill: string
  open: string | null
  onToggle: (key: string | null) => void
}) {
  /* 点别处、按 Esc 收起，焦点还给折角。面板与行都算「里面」：面板里要能选字、复制、点链接，
     行里也要能拖选——否则每一次 mousedown 都会被当成「点在外面」。 */
  useEffect(() => {
    if (open === null) return
    const close = () => {
      onToggle(null)
      document.getElementById('usage-toggle-' + domId(open))?.focus()
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        close()
      }
    }
    const onDown = (event: MouseEvent) => {
      const target = event.target as HTMLElement | null
      if (target?.closest('.usage-detail') || target?.closest('.usage-log-row')) return
      close()
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onDown)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onDown)
    }
  }, [open, onToggle])

  return (
    <div className="usage-log" id="usageList" role="table" aria-label="使用记录">
      <div className="usage-log-head" role="row">
        <span role="columnheader">时间</span>
        <span role="columnheader">操作</span>
        <span role="columnheader">调用的 skill</span>
        <span aria-hidden="true" />
      </div>

      {items.map((row, index) => {
        const key = row.kind === 'search' ? searchKey(row, index) : directKey(row, index)
        return (
          <Fragment key={key}>
            {row.kind === 'search' ? (
              <SearchRow
                row={row}
                index={index}
                open={open === key}
                hitOnly={skill !== ''
                  && row.invocations.every((invocation) => !addressMatches(invocation.skill, skill))}
                onToggle={() => onToggle(open === key ? null : key)}
              />
            ) : (
              <DirectRow row={row} />
            )}
            {row.kind === 'search' && row.results.length > 0 && open === key && (
              <SearchDetail row={row} id={key} />
            )}
          </Fragment>
        )
      })}
    </div>
  )
}
