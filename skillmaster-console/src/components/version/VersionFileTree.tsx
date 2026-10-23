import { useEffect, useRef, useState } from 'react'
import type { DiffFile } from '../../api/types'
import { fileAnchorId } from '../../version'

/**
 * The 改动 pane's left rail: every changed file, its `+n −m`, and a path filter above it.
 *
 * **The filter narrows this list only.** The diff bodies already loaded do not disappear while the
 * reader types; a matched row still scrolls to (or opens) its block. Its value is the address's `fq`
 * (TD §改动), so a filtered tree can be sent to somebody else.
 *
 * Binary and over-limit files have no countable lines and show `—`, never `+0 −0` (PRD #25).
 */
export interface Props {
  files: DiffFile[]
  filter: string
  current: string | null
  /** In single-file or list-only mode a row is a real address; otherwise it is an anchor. */
  addressMode: boolean
  hrefFor: (relpath: string) => string
  onChange: (value: string) => void
  onSelect: (relpath: string) => void
}

export default function VersionFileTree({
  files,
  filter,
  current,
  addressMode,
  hrefFor,
  onChange,
  onSelect,
}: Props) {
  const [query, setQuery] = useState(filter)
  const firstRun = useRef(true)

  useEffect(() => {
    if (firstRun.current) {
      firstRun.current = false
      return
    }
    setQuery(filter)
  }, [filter])

  const needle = query.trim().toLowerCase()
  const matched =
    needle === '' ? files : files.filter((file) => file.relpath.toLowerCase().includes(needle))

  function setFilter(value: string): void {
    setQuery(value)
    onChange(value)
  }

  function clear(): void {
    setFilter('')
  }

  function hasStats(file: DiffFile): boolean {
    return file.added !== null && file.removed !== null
  }

  return (
    <aside className="vp-tree" aria-label="改动的文件">
      <div className="search vp-tree-search">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
          <line
            x1="15.4"
            y1="15.4"
            x2="20.6"
            y2="20.6"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
          />
        </svg>
        <input
          value={query}
          className="control"
          type="search"
          autoComplete="off"
          spellCheck={false}
          aria-label="搜索改动的文件"
          placeholder="搜索文件路径"
          onChange={(event) => setFilter(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.preventDefault()
              if (query !== '') {
                clear()
              }
            }
          }}
        />
        {query !== '' ? (
          <button type="button" className="search-clear" aria-label="清除搜索" onClick={clear}>
            ×
          </button>
        ) : null}
      </div>

      <ul className="vp-tree-list">
        {matched.map((file) => (
          <li key={file.relpath}>
            <a
              className={
                file.relpath === current ? 'vp-tree-item is-current' : 'vp-tree-item'
              }
              title={file.relpath}
              href={addressMode ? hrefFor(file.relpath) : `#${fileAnchorId(file.relpath)}`}
              onClick={addressMode ? undefined : () => onSelect(file.relpath)}
            >
              <span className="vp-tree-path">{file.relpath}</span>
              {hasStats(file) ? (
                <span className="diff-stats">
                  <b className="stat-add">+{file.added}</b>{' '}
                  <b className="stat-del">−{file.removed}</b>
                </span>
              ) : (
                <span className="diff-stats is-pending">—</span>
              )}
            </a>
          </li>
        ))}
      </ul>
      {matched.length === 0 && files.length > 0 ? (
        <p className="vp-tree-empty">
          没有匹配的文件。{' '}
          <button type="button" className="link-button" onClick={clear}>
            清除筛选
          </button>
        </p>
      ) : null}
    </aside>
  )
}
