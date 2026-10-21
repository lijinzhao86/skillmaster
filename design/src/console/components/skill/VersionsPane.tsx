/**
 * 版本: the one list of this skill's versions, and it does not act.
 *
 * A row is a name plus what that version is; every action that changes a version's state happens
 * on that version's own page, reached by its name (ADR 0054). The version pin in the address
 * switches this tab from the list to that one version; without it the list is what the tab is.
 */

import { useRef } from 'react'
import type { KeyboardEvent, ReactNode, RefObject } from 'react'
import type { AtLeast, Version } from '../../fixtures/skill'
import {
  backAddress,
  versionByPin,
  versionDetailAddress,
  versionOptionValue,
  versionStateKey,
  versionTitleOf,
  visibleVersions,
} from '../../fixtures/skill'
import { replace } from '../../address'
import CopyButton from './CopyButton'
import VersionActionMenu from './VersionActionMenu'
import { StateChip } from './parts'

const VERSION_FILTERS = [
  { key: 'live', label: '线上' },
  { key: 'draft', label: '草稿' },
  { key: 'published', label: '历史版本' },
  { key: 'revoked', label: '已下架' },
  { key: 'discarded', label: '已丢弃' },
]

export interface VersionsPaneProps {
  hidden: boolean
  versions: Version[]
  atLeast: AtLeast
  params: URLSearchParams
  menuOpen: boolean
  menuOwner: 'files' | 'versions' | null
  versionCreateRef: RefObject<HTMLButtonElement | null>
  onToggleMenu: (owner: 'files' | 'versions') => void
  children?: ReactNode
}

export default function VersionsPane({
  hidden,
  versions,
  atLeast,
  params,
  menuOpen,
  menuOwner,
  versionCreateRef,
  onToggleMenu,
  children,
}: VersionsPaneProps) {
  const browse = atLeast('browse')
  const shown = visibleVersions(versions, atLeast)
  const askedPin = params.get('version')
  const target = versionByPin(versions, askedPin, atLeast)

  /* **地址是筛选的唯一来源**：刷新、转发、后退都回到同一个视图。 */
  const askFilter = params.get('filter')
  const known = VERSION_FILTERS.some((option) => option.key === askFilter)
  const filter = known ? (askFilter as string) : 'all'
  const wanted = params.get('q') ?? ''
  const versionQueryInput = useRef<HTMLInputElement>(null)

  const counts: Record<string, number> = {}
  shown.forEach((version) => {
    const key = versionStateKey(version)
    counts[key] = (counts[key] ?? 0) + 1
  })

  /* **只搜行上看得见的字。** 作者面是名称、版本号、说明与提交人；调用者那一行只有版本号与说明. */
  const needle = wanted.trim().toLowerCase()
  const matched = shown.filter((version) => {
    if (filter !== 'all' && versionStateKey(version) !== filter) return false
    if (!needle) return true
    const words = browse
      ? [versionTitleOf(version, browse), version.name || '', version.note || '', version.by]
      : [version.name || '', version.note || '']
    return words.join(' ').toLowerCase().indexOf(needle) >= 0
  })

  const setFilter = (key: string) => {
    const query = new URLSearchParams(params)
    if (key === 'all') query.delete('filter')
    else query.set('filter', key)
    replace('?' + query.toString())
  }
  const setQuery = (value: string) => {
    const query = new URLSearchParams(params)
    if (value === '') query.delete('q')
    else query.set('q', value)
    replace('?' + query.toString())
  }
  const clearFilters = () => {
    const query = new URLSearchParams(params)
    query.delete('filter')
    query.delete('q')
    replace('?' + query.toString())
  }

  const queryInput = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Escape' && wanted !== '') {
      event.preventDefault()
      setQuery('')
    }
  }

  return (
    <div className="tab-panel" data-tab="versions" hidden={hidden}>
      <div id="versionListWrap" hidden={!!askedPin}>
        <div className="version-toolbar">
          <div className="version-filters" id="versionFilters" role="group" aria-label="按状态筛选">
            <button
              type="button"
              className={'version-filter' + (filter === 'all' ? ' is-current' : '')}
              aria-pressed={filter === 'all' ? 'true' : 'false'}
              onClick={() => setFilter('all')}
            >
              全部<span className="version-filter-count">{String(shown.length)}</span>
            </button>
            {VERSION_FILTERS.map((option) => {
              if (!counts[option.key]) return null
              return (
                <button
                  key={option.key}
                  type="button"
                  className={'version-filter' + (option.key === filter ? ' is-current' : '')}
                  aria-pressed={option.key === filter ? 'true' : 'false'}
                  onClick={() => setFilter(option.key)}
                >
                  {option.label}<span className="version-filter-count">{String(counts[option.key])}</span>
                </button>
              )
            })}
          </div>
          <button
            type="button"
            className="button menu-button"
            id="versionCreate"
            aria-haspopup="menu"
            aria-expanded={menuOpen && menuOwner === 'versions' ? 'true' : 'false'}
            hidden={!atLeast('write')}
            ref={versionCreateRef}
            onClick={() => onToggleMenu('versions')}
            onKeyDown={(event) => {
              if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault()
                if (!(menuOpen && menuOwner === 'versions')) onToggleMenu('versions')
              }
            }}
          >
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
            新建版本
            <svg className="button-caret" viewBox="0 0 12 12" aria-hidden="true"><path d="M3 4.8 6 7.8 9 4.8" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" /></svg>
          </button>
        </div>
        <div className="search version-search">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
            <line x1="15.4" y1="15.4" x2="20.6" y2="20.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          </svg>
          <input
            id="versionQuery"
            className="control"
            type="search"
            autoComplete="off"
            spellCheck={false}
            aria-label="搜索版本"
            placeholder={browse ? '搜索版本名称、版本号或说明' : '搜索版本号或说明'}
            ref={versionQueryInput}
            value={wanted}
            onInput={(event) => setQuery(event.currentTarget.value)}
            onChange={(event) => setQuery(event.currentTarget.value)}
            onKeyDown={queryInput}
          />
          <button
            type="button"
            className="search-clear"
            id="versionQueryClear"
            aria-label="清除搜索"
            hidden={wanted === ''}
            onClick={() => {
              setQuery('')
              versionQueryInput.current?.focus()
            }}
          >
            ×
          </button>
        </div>
        <div className="file-table version-list-table">
          <ul className="versions" id="versionList">
            {matched.map((version) => {
              const displayName = versionTitleOf(version, browse)
              const mono = !browse || !version.draftName
              return (
                <li key={versionOptionValue(version)}>
                  <div className="version-head">
                    <a
                      className={'version-name' + (mono ? ' is-mono' : '')}
                      href={versionDetailAddress(version, params)}
                      title={version.note ? displayName + '\n' + version.note : displayName}
                      aria-label={'打开 ' + displayName}
                    >
                      {displayName}
                    </a>{' '}
                    <span className={'version-number' + (version.name ? '' : ' is-empty')} hidden={!browse}>
                      {version.name || '—'}
                    </span>{' '}
                    <span className="version-states"><StateChip version={version} /></span>{' '}
                    <VersionActionMenu
                      version={version}
                      versions={versions}
                      atLeast={atLeast}
                      params={params}
                    />{' '}
                  </div>
                  <div className="version-sub">
                    <span className="version-note">{version.note || ''}</span>{' '}
                    {browse && version.code
                      ? (
                        <span className="version-code-slot">
                          <span className="version-code">
                            <span>{version.code}</span>
                            <CopyButton text={version.code} label="版本码" />
                          </span>
                        </span>
                      )
                      : null}{' '}
                    <span className="version-meta">
                      {browse ? version.by + ' · ' + version.at : '上线于 ' + version.at}
                    </span>
                  </div>
                </li>
              )
            })}
          </ul>
          <p className="version-empty" id="versionEmpty" hidden={matched.length > 0}>
            没有匹配的版本。
            {' '}
            <button type="button" className="link-button" id="versionEmptyClear" onClick={clearFilters}>清除筛选</button>
          </p>
        </div>
      </div>

      <div id="versionMissing" hidden={!askedPin || target !== null}>
        <div className="notfound">
          <h2>没有这一版</h2>
          <p className="hint">这个地址下没有你能打开的版本：它可能已经被丢弃，也可能属于一个你看不到的 skill。</p>
          <p><a href={backAddress(params)} id="versionBackList">回到版本列表</a></p>
        </div>
      </div>
      {children}
    </div>
  )
}
