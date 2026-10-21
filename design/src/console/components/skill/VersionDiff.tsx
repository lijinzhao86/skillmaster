/**
 * 改动: the baseline, the file tree, and one block of diff per file.
 *
 * **清单先到、内容按需、单文件分段.** A very large change should not compute, send and draw the
 * whole diff the moment the page opens: the tree and the file headers come first, the body is
 * fetched when it enters the viewport or is opened, a file draws 400 lines first and 500 more per
 * press, and above 100 files only one is shown at a time. `?big=1` is that state; `&load=1` loads
 * the current file immediately, `&list=1` shows only the list, `&all=1` forces every block,
 * `&manual=1` turns off the viewport observer, `&difffail=1` fails the first load.
 */

import { useEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'
import SkillMenu from './SkillMenu'
import type { AtLeast, Version, DiffFile } from '../../fixtures/skill'
import {
  BIG_FIRST,
  BIG_SINGLE_OVER,
  BIG_STEP,
  bigHref,
  changeSummary,
  comparableVersions,
  diffStatsOf,
  previousPublished,
  versionMenuOption,
  versionTitleOf,
} from '../../fixtures/skill'
import { go, replace } from '../../address'
import { Stats } from './parts'

export interface DiffPaneProps {
  active: boolean
  target: Version
  baseline: Version | null
  versions: Version[]
  atLeast: AtLeast
  params: URLSearchParams
}

export default function DiffPane({
  active,
  target,
  baseline,
  versions,
  atLeast,
  params,
}: DiffPaneProps) {
  const big = params.get('big') === '1'
  const listOnly = params.get('list') === '1'
  const manual = params.get('manual') === '1'
  const summary = changeSummary(target, big)
  const files = summary.files
  const single = big && !listOnly && params.get('all') !== '1' && files.length > BIG_SINGLE_OVER
  const current = (() => {
    const asked = parseInt(params.get('file') ?? '0', 10)
    if (Number.isNaN(asked)) return 0
    return Math.max(0, Math.min(files.length - 1, asked))
  })()

  const fq = params.get('fq') ?? ''
  const [applyQuery, setApplyQuery] = useState(active && !single)
  const tree = useRef<HTMLUListElement>(null)
  const queryInput = useRef<HTMLInputElement>(null)
  /* One failure per page load, shared by every block — the second attempt succeeds in place. */
  const failedOnce = useRef(false)

  const query = applyQuery ? fq.trim().toLowerCase() : ''
  const matches = (path: string) => query === '' || path.toLowerCase().indexOf(query) >= 0
  const matched = active ? files.filter((file) => matches(file.path)).length : 0

  const setFileQuery = (value: string) => {
    setApplyQuery(true)
    const next = new URLSearchParams(params)
    if (value.trim() === '') next.delete('fq')
    else next.set('fq', value)
    replace('?' + next.toString())
  }
  const clearFileQuery = () => {
    setApplyQuery(true)
    const next = new URLSearchParams(params)
    next.delete('fq')
    replace('?' + next.toString())
  }

  /* 单文件模式：把当前文件滚进树的可视区——124 个文件里第 122 个不该让人自己找。 */
  useEffect(() => {
    if (!single || !active) return
    const items = tree.current?.querySelectorAll('.vp-tree-item')
    const item = items?.[current]
    if (item) item.scrollIntoView({ block: 'nearest' })
  }, [single, active, current])

  const previous = baseline ? previousPublished(versions, target) : null
  const compareVersions = comparableVersions(versions, target, atLeast)
  const ordered = baseline
    ? ([] as Version[])
      .concat(compareVersions.filter((version) => version.live))
      .concat(compareVersions.filter((version) => !version.draft && !version.live && version.state !== 'revoked'))
      .concat(compareVersions.filter((version) => version.draft))
      .concat(compareVersions.filter((version) => version.state === 'revoked'))
    : []

  const toggleHref = bigHref(params, null, listOnly ? { unlist: true } : { list: true })

  return (
    <div className="vp-pane" id="vpDiffPane" data-vpane="diff" hidden={!active}>
      <div className="vp-compare">
        <span className="vp-compare-caption">对比</span>
        <label className="version-picker">
          <span className="visually-hidden">对比的版本</span>
          <span id="vpCompareSlot">
            {baseline && (
              <SkillMenu
                groups={[{
                  options: ordered.map((version) => {
                    const option = versionMenuOption(version, atLeast('browse'), false)
                    if (version === previous) option.meta = '上一版'
                    return option
                  }),
                }]}
                value={baseline.name || baseline.pin || baseline.code}
                config={{
                  ariaLabel: '对比的版本',
                  search: true,
                  searchPlaceholder: '按名称或版本号筛选',
                  emptyText: '没有匹配的版本。',
                  triggerText: versionTitleOf(baseline, atLeast('browse')) +
                    (baseline === previous ? '（上一版）' : ''),
                  onPick: (picked) => {
                    const next = new URLSearchParams(params)
                    next.set('sub', 'diff')
                    next.set('from', picked)
                    go('?' + next.toString())
                  },
                }}
              />
            )}
          </span>
        </label>
        <span className="vp-file-count" id="vpFileCount">{active ? files.length + ' 个文件' : ''}</span>
        {active && big && (
          <button
            type="button"
            id="vpListOnly"
            className="link-button"
            onClick={() => go(toggleHref)}
          >
            {listOnly ? '逐个查看' : '只看文件列表'}
          </button>
        )}
      </div>

      <p className="hint vp-big-note" id="vpBigNote" hidden={!(active && big)}>
        {listOnly
          ? '只看文件列表：' + files.length + ' 个文件有变化。'
          : '这次改动有 ' + files.length + ' 个文件。文件内容按需加载。'}
      </p>

      <div className="vp-changes">
        <aside className="vp-tree" aria-label="改动的文件">
          <div className="search vp-tree-search">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
              <line x1="15.4" y1="15.4" x2="20.6" y2="20.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            <input
              id="vpFileQuery"
              className="control"
              type="search"
              autoComplete="off"
              spellCheck={false}
              aria-label="搜索改动的文件"
              placeholder="搜索文件路径"
              ref={queryInput}
              value={applyQuery ? fq : ''}
              onInput={(event) => setFileQuery(event.currentTarget.value)}
              onChange={(event) => setFileQuery(event.currentTarget.value)}
              onKeyDown={(event) => {
                if (event.key === 'Escape' && (applyQuery ? fq : '') !== '') {
                  event.preventDefault()
                  clearFileQuery()
                  queryInput.current?.focus()
                }
              }}
            />
            <button
              type="button"
              className="search-clear"
              id="vpFileQueryClear"
              aria-label="清除搜索"
              hidden={!(applyQuery && fq !== '')}
              onClick={() => {
                clearFileQuery()
                queryInput.current?.focus()
              }}
            >
              ×
            </button>
          </div>
          <ul className="vp-tree-list" id="vpFileTree" ref={tree}>
            {active && files.map((file, index) => {
              const stats = diffStatsOf(file)
              const pendingStats = big && (file.binary || file.tooBig)
              const href = single || listOnly
                ? bigHref(params, index, { unlist: true })
                : '#' + 'vpFile' + index
              return (
                <li key={file.path} hidden={applyQuery && !matches(file.path)}>
                  <a
                    className={'vp-tree-item' + (single && index === current ? ' is-current' : '')}
                    href={href}
                    title={file.path}
                  >
                    <span className="vp-tree-path">{file.path}</span>
                    {' '}
                    <span
                      className={'diff-stats' + (pendingStats ? ' is-pending' : '')}
                      data-stats-index={String(index)}
                    >
                      {pendingStats ? '—' : <Stats added={stats.added} removed={stats.removed} />}
                    </span>
                  </a>
                </li>
              )
            })}
          </ul>
          <p className="vp-tree-empty" id="vpFileEmpty" hidden={!(active && applyQuery && files.length > 0 && matched === 0)}>
            没有匹配的文件。
            {' '}
            <button
              type="button"
              className="link-button"
              id="vpFileEmptyClear"
              onClick={() => {
                clearFileQuery()
                queryInput.current?.focus()
              }}
            >
              清除筛选
            </button>
          </p>
        </aside>

        <div className="vp-files" id="vpFileList">
          {active && !big && files.map((file, index) => (
            <NormalFileBlock file={file} index={index} key={file.path} />
          ))}
          {active && big && listOnly && (
            <p className="hint vp-file-list-note">只看文件列表。点左边的文件，或「逐个查看」，打开内容。</p>
          )}
          {active && big && !listOnly && single && (
            <>
              <div className="vp-file-mode">
                <span className="hint">一次显示一个文件</span>
                <div className="vp-file-pager">
                  <a
                    className={'link-button' + (current === 0 ? ' is-disabled' : '')}
                    href={bigHref(params, current === 0 ? current : current - 1)}
                  >
                    上一个
                  </a>
                  <span>{(current + 1) + ' / ' + files.length}</span>
                  <a
                    className={'link-button' + (current === files.length - 1 ? ' is-disabled' : '')}
                    href={bigHref(params, current === files.length - 1 ? current : current + 1)}
                  >
                    下一个
                  </a>
                </div>
              </div>
              <BigFileBlock
                file={files[current]}
                index={current}
                auto={!manual}
                params={params}
                failedOnce={failedOnce}
                key={files[current].path}
              />
            </>
          )}
          {active && big && !listOnly && !single && files.map((file, index) => (
            <BigFileBlock
              file={file}
              index={index}
              auto={params.get('load') === '1' && index === 0}
              params={params}
              failedOnce={failedOnce}
              key={file.path}
            />
          ))}
        </div>
      </div>
    </div>
  )
}

function DiffLine({ line, index }: { line: string; index: number }) {
  const mark = line.charAt(0)
  const changed = mark === '+' || mark === '-'
  const className = mark === '+' ? 'is-add' : mark === '-' ? 'is-del' : undefined
  return (
    <span className={className}>
      <b className="diff-num">{String(index + 1)}</b>
      <span className="diff-text">
        {changed
          ? <><b className="diff-sign">{mark}</b>{line.slice(1)}</>
          : line}
      </span>
    </span>
  )
}

function NormalFileBlock({ file, index }: { file: DiffFile; index: number }) {
  const [collapsed, setCollapsed] = useState(false)
  const stats = diffStatsOf(file)
  return (
    <section className="vp-file" id={'vpFile' + index}>
      <div className="diff-head vp-file-head">
        <button
          type="button"
          className="vp-file-toggle"
          aria-expanded={collapsed ? 'false' : 'true'}
          onClick={() => setCollapsed((was) => !was)}
        >
          <span className="vp-file-path">{file.path}</span>
        </button>
        <span className="diff-stats"><Stats added={stats.added} removed={stats.removed} /></span>
      </div>
      <pre className="file-source diff-body" hidden={collapsed}>
        {file.lines.map((line, lineIndex) => (
          <DiffLine line={line} index={lineIndex} key={lineIndex} />
        ))}
      </pre>
    </section>
  )
}

type LoadState = 'idle' | 'loading' | 'loaded' | 'failed'

function BigFileBlock({
  file,
  index,
  auto,
  params,
  failedOnce,
}: {
  file: DiffFile
  index: number
  auto: boolean
  params: URLSearchParams
  failedOnce: RefObject<boolean>
}) {
  const [expanded, setExpanded] = useState(true)
  const [state, setState] = useState<LoadState>('idle')
  const [shown, setShown] = useState(BIG_FIRST)
  const pending = useRef<HTMLDivElement>(null)

  const load = () => {
    if (file.binary || file.tooBig) return
    if (state === 'loading' || state === 'loaded') return
    setState('loading')
    const willFail = params.get('difffail') === '1' && !failedOnce.current
    if (willFail) failedOnce.current = true
    const finish = () => {
      if (willFail) {
        setState('failed')
        return
      }
      setState('loaded')
      setShown(BIG_FIRST)
    }
    if (params.get('load') === '1') finish()
    else window.setTimeout(finish, 260)
  }

  useEffect(() => {
    if (auto) load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (state !== 'idle' || params.get('manual') === '1') return
    if (file.binary || file.tooBig) return
    if (!('IntersectionObserver' in window)) return
    const node = pending.current
    if (!node) return
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return
        observer.unobserve(entry.target)
        load()
      })
    }, { rootMargin: '240px' })
    observer.observe(node)
    return () => observer.disconnect()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state, index])

  const stats = diffStatsOf(file)
  const pendingStats = file.binary || file.tooBig

  const body = () => {
    if (file.binary) return <p className="diff-pending">二进制文件，不显示内容。</p>
    if (file.tooBig) return <p className="diff-pending">这个文件太大，没有展开（{file.size}）。</p>
    if (state === 'loaded') {
      const count = Math.min(shown, file.lines.length)
      return (
        <>
          <pre className="file-source diff-body diff-lines">
            {file.lines.slice(0, count).map((line, lineIndex) => (
              <DiffLine line={line} index={lineIndex} key={lineIndex} />
            ))}
          </pre>
          {file.lines.length > count && (
            <div className="diff-more">
              <span>{'还有 ' + (file.lines.length - count) + ' 行未显示'}</span>
              <button
                type="button"
                className="link-button"
                onClick={() => setShown(Math.min(file.lines.length, count + BIG_STEP))}
              >
                {'显示更多（' + BIG_STEP + ' 行）'}
              </button>
            </div>
          )}
        </>
      )
    }
    if (state === 'failed') {
      return (
        <div className="diff-pending diff-failed">
          <span>加载失败：服务器暂时没有响应。</span>
          <button type="button" className="link-button" onClick={load}>重试</button>
        </div>
      )
    }
    if (state === 'loading') return <p className="diff-pending">正在加载改动…</p>
    return (
      <div className="diff-pending" ref={pending}>
        <span>进入视野后加载</span>
        <button type="button" className="link-button" onClick={load}>显示改动</button>
      </div>
    )
  }

  return (
    <section className="vp-file" id={'vpFile' + index}>
      <div className="diff-head vp-file-head">
        <button
          type="button"
          className="vp-file-toggle"
          aria-expanded={expanded ? 'true' : 'false'}
          onClick={() => {
            const next = !expanded
            setExpanded(next)
            if (next && state !== 'loaded') load()
          }}
        >
          <span className="vp-file-path">{file.path}</span>
        </button>
        <span className={'diff-stats' + (pendingStats ? ' is-pending' : '')}>
          {pendingStats ? '—' : <Stats added={stats.added} removed={stats.removed} />}
        </span>
      </div>
      <div className="vp-file-body" hidden={!expanded}>
        {body()}
      </div>
    </section>
  )
}
