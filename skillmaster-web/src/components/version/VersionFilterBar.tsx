import { useImperativeHandle, useRef } from 'react'
import type { RefObject } from 'react'
import type { AuthoredVersion } from '../../api/types'
import CommandMenu from '../CommandMenu'
import type { CommandMenuHandle, CommandMenuItem } from '../CommandMenu'
import { versionCreateItems } from './createMenu'
import { canWrite } from '../../versionActions'
import { filterKeyOf, statusOf, type VersionFilterKey } from '../../version'

/**
 * The version list's toolbar: status chips with counts, the new-version placeholder, and search.
 *
 * **Counts describe what this rung can see, and do not move while searching** — the chips answer
 * 「哪里有多少」. A chip is drawn only for a status that occurs; 「全部」 is always there and is also
 * the way to clear the status filter. Filter and search combine, and both are written to the address
 * by the page, so a filtered list can be refreshed or sent to somebody.
 *
 * 「新建版本」 is a real entry since v8: it goes to the editor, where the base is visible before
 * anything is written. It is drawn for `write` and above only — 调用 and 浏览 cannot submit a
 * version, and a button that can do nothing is exactly that promise.
 */
export interface VersionFilterBarProps {
  versions: AuthoredVersion[]
  filter: VersionFilterKey
  query: string
  rung: string
  /** The new-version editor's address. */
  newHref: string
  onUpdateFilter: (value: VersionFilterKey) => void
  onUpdateQuery: (value: string) => void
  /** 「导入压缩包」: the page owns the sheet, because it also owns where a success lands. */
  onOpenImport: () => void
  /** `defineExpose({ focusCreate })` in the Vue implementation. */
  controlRef?: RefObject<VersionFilterBarHandle | null>
}

export interface VersionFilterBarHandle {
  /** Puts focus back on 「新建版本 ▾」. */
  focusCreate: () => void
}

const CHIPS: Array<{ key: Exclude<VersionFilterKey, 'all'>; label: string }> = [
  { key: 'live', label: '线上' },
  { key: 'draft', label: '草稿' },
  { key: 'published', label: '历史版本' },
  { key: 'revoked', label: '已下架' },
  { key: 'discarded', label: '已丢弃' },
]

export default function VersionFilterBar({
  versions,
  filter,
  query,
  rung,
  newHref,
  onUpdateFilter,
  onUpdateQuery,
  onOpenImport,
  controlRef,
}: VersionFilterBarProps) {
  const counts: Record<string, number> = {}
  for (const version of versions) {
    const key = filterKeyOf(statusOf(version))
    counts[key] = (counts[key] ?? 0) + 1
  }

  const createItems: CommandMenuItem[] = versionCreateItems(newHref, onOpenImport)

  const menu = useRef<CommandMenuHandle | null>(null)

  /** Puts focus back on 「新建版本 ▾」 — what the design asks for when the sheet it opened closes. */
  useImperativeHandle(controlRef, () => ({ focusCreate: () => menu.current?.focusTrigger() }), [])

  const searchPlaceholder =
    rung === 'invoke' ? '搜索版本号或说明' : '搜索版本名称、版本号或说明'

  function pick(key: VersionFilterKey): void {
    onUpdateFilter(key)
  }

  function setQuery(value: string): void {
    onUpdateQuery(value)
  }

  function clearQuery(): void {
    onUpdateQuery('')
  }

  return (
    <>
      <div className="version-toolbar">
        <div className="version-filters" role="group" aria-label="按状态筛选">
          <button
            type="button"
            className={filter === 'all' ? 'version-filter is-current' : 'version-filter'}
            aria-pressed={filter === 'all' ? 'true' : 'false'}
            onClick={() => pick('all')}
          >
            全部 <span className="version-filter-count">{versions.length}</span>
          </button>
          {CHIPS.map((chip) => (counts[chip.key] ?? 0) > 0 ? (
            <button
              key={chip.key}
              type="button"
              className={filter === chip.key ? 'version-filter is-current' : 'version-filter'}
              aria-pressed={filter === chip.key ? 'true' : 'false'}
              onClick={() => pick(chip.key)}
            >
              {chip.label} <span className="version-filter-count">{counts[chip.key]}</span>
            </button>
          ) : null)}
        </div>

        {/* **One action, two sources** (v9): 在线编辑 is the editor, 导入压缩包 brings a zip in.
            A menu rather than two buttons, because they produce the same thing (2026-10-21). */}
        {canWrite(rung) ? (
          <CommandMenu
            controlRef={menu}
            groups={[createItems]}
            menuLabel="新建版本"
            resize="reposition"
          >
            {({ open, toggle }) => (
              <button
                type="button"
                className="button version-create menu-button"
                aria-haspopup="menu"
                aria-expanded={open ? 'true' : 'false'}
                onClick={toggle}
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M12 5v14M5 12h14"
                    stroke="currentColor"
                    strokeWidth="1.8"
                    strokeLinecap="round"
                  />
                </svg>
                新建版本
                <svg className="button-caret" viewBox="0 0 12 12" aria-hidden="true">
                  <path
                    d="M3 4.8 6 7.8 9 4.8"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.4"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              </button>
            )}
          </CommandMenu>
        ) : null}
      </div>

      <div className="search version-search">
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
          aria-label="搜索版本"
          placeholder={searchPlaceholder}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.preventDefault()
              if (query !== '') {
                clearQuery()
              }
            }
          }}
        />
        {query !== '' ? (
          <button
            type="button"
            className="search-clear"
            aria-label="清除搜索"
            onClick={clearQuery}
          >
            ×
          </button>
        ) : null}
      </div>
    </>
  )
}
