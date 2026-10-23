import { useEffect, useRef, useState } from 'react'
import CommandMenu from '../CommandMenu'
import type { AuthoredVersion } from '../../api/types'
import { canEditDraft, versionActions } from '../../versionActions'
import { formatTime } from '../../time'
import {
  statusClass,
  statusOf,
  statusText,
  versionSuffix,
  versionTitle,
} from '../../version'

/**
 * One row of the version list: two segments, no header.
 *
 * **The list only navigates.** The name is the row's one real link; a draft's rename, note and
 * discard happen on that version's own page, not here. Its `::after` covers the rest of the row, so
 * the whole row is the link — one tab stop, and Ctrl/middle-click anywhere open the version in a new
 * tab. The 更多操作 button sits above that layer (`position: relative; z-index: 1`) and its items are
 * links too: they take the reader to the version's page, they do not execute anything.
 */
export interface VersionRowProps {
  version: AuthoredVersion
  namespace: string
  name: string
  rung: string
  username: string | null
  /** `filter=…&q=…`, carried back to the list from the version's own page. */
  listQuery: string
  hasBaseline: boolean
}

interface Item {
  label: string
  href: string
  view: boolean
}

export default function VersionRow({
  version,
  namespace,
  name,
  rung,
  username,
  listQuery,
  hasBaseline,
}: VersionRowProps) {
  const status = statusOf(version)
  const author = rung !== 'invoke'
  const title = versionTitle(version, rung)
  const number = version.name === null ? '—' : `@${version.name}`
  const note = version.note ?? ''
  const meta = author
    ? `${version.submitted_by ?? ''} · ${formatTime(version.submitted_at)}`
    : `上线于 ${formatTime(version.first_published_at ?? version.submitted_at)}`

  const base = `/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`

  const pin = versionSuffix(version)
  /** The row id as a value of its own — what 「版本码」 shows, distinct from the whole address. */
  const code = version.code ?? version.draft_id ?? ''

  const detailHref = ((): string => {
    const query = new URLSearchParams()
    query.set('tab', 'versions')
    query.set('version', pin)
    if (listQuery !== '') {
      const carried = new URLSearchParams(listQuery)
      for (const [key, value] of carried) {
        query.set(key, value)
      }
    }
    return `${base}?${query.toString()}`
  })()

  const filesHref = `${base}@${encodeURIComponent(pin)}?tab=files`
  const subHref = (sub: string): string => `${detailHref}&sub=${sub}`
  /** 编辑 is an entry, not an action: it sends the reader to the editor and executes nothing here. */
  const editHref = `${base}@${encodeURIComponent(pin)}/edit`

  const items: Item[] = (() => {
    if (status === 'discarded') {
      return []
    }
    const list: Item[] = []
    for (const action of versionActions(version, rung, username, hasBaseline)) {
      switch (action) {
        case 'files':
          list.push({ label: '查看文件', href: filesHref, view: true })
          break
        case 'diff':
          list.push({ label: '查看改动', href: subHref('diff'), view: true })
          break
        case 'publish':
          list.push({ label: '上线', href: subHref('actions'), view: false })
          break
        case 'rollback':
          list.push({ label: '回滚', href: subHref('actions'), view: false })
          break
        case 'revoke':
          list.push({ label: '下架', href: subHref('actions'), view: false })
          break
        case 'restore':
          list.push({ label: '重新上架', href: subHref('actions'), view: false })
          break
        case 'discard':
          list.push({ label: '丢弃', href: subHref('actions'), view: false })
          break
      }
    }
    // **编辑 is the second entry a draft has** (v8): beside 查看文件, because the two questions about
    // a draft are "what is in it" and "I want to change it". Published rows have neither — their
    // content is frozen (ADR 0064).
    if (canEditDraft(version, rung, username)) {
      const at = list.findIndex((item) => item.label === '查看文件')
      list.splice(at + 1, 0, { label: '编辑', href: editHref, view: true })
    }
    return list
  })()

  /* ------------------------------------------------------------- the menu */

  /** The design's two groups: navigation first, lifecycle actions after a rule (`.menu-group`). */
  const viewItems = items.filter((item) => item.view)
  const actionItems = items.filter((item) => !item.view)
  /* ---------------------------------------------------------- the version code */
  const [copiedCode, setCopiedCode] = useState(false)
  const [codeCopyFailed, setCodeCopyFailed] = useState(false)
  const codeTimer = useRef<number | null>(null)

  /** Clipboard first (file:// is not always available), then a hidden textarea; never a fake success. */
  function copyCode(): void {
    const text = code
    const done = (ok: boolean): void => {
      if (codeTimer.current !== null) {
        window.clearTimeout(codeTimer.current)
      }
      if (!ok) {
        // **A failure keeps its sentence.** The design resets only the success state; clearing the
        // failure after 1.2 s would take away the one line that explains why nothing was copied.
        setCopiedCode(false)
        setCodeCopyFailed(true)
        return
      }
      setCopiedCode(true)
      setCodeCopyFailed(false)
      codeTimer.current = window.setTimeout(() => {
        setCopiedCode(false)
        setCodeCopyFailed(false)
      }, 1200)
    }
    const fallback = (): void => {
      try {
        const area = document.createElement('textarea')
        area.value = text
        area.setAttribute('readonly', '')
        area.style.position = 'fixed'
        area.style.opacity = '0'
        document.body.appendChild(area)
        area.select()
        const ok = document.execCommand('copy')
        area.remove()
        done(ok)
      } catch {
        done(false)
      }
    }
    if (navigator.clipboard?.writeText !== undefined) {
      navigator.clipboard.writeText(text).then(() => done(true), fallback)
    } else {
      fallback()
    }
  }

  useEffect(
    () => () => {
      if (codeTimer.current !== null) {
        window.clearTimeout(codeTimer.current)
      }
    },
    [],
  )

  return (
    <li>
      <div className="version-head">
        <a
          className={author ? 'version-name' : 'version-name is-mono'}
          href={detailHref}
          title={note === '' ? title : `${title}\n${note}`}
          aria-label={`打开 ${title}`}
        >
          {title}
        </a>
        {author ? (
          <span
            className={version.name === null ? 'version-number is-empty' : 'version-number'}
          >
            {number}
          </span>
        ) : null}
        <span className="version-states">
          <span className={`chip ${statusClass(status)}`}>
            <i className="dot" />
            <span className="chip-text">{statusText(status)}</span>
          </span>
        </span>

        {items.length > 0 ? (
          <span className="version-menu">
            <CommandMenu groups={[viewItems, actionItems]} menuLabel={`更多操作：${title}`}>
              {({ open, toggle }) => (
                <button
                  type="button"
                  className="version-menu-trigger"
                  aria-haspopup="menu"
                  aria-expanded={open ? 'true' : 'false'}
                  aria-label={`更多操作：${title}`}
                  title="更多操作"
                  onClick={toggle}
                >
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <circle cx="12" cy="5" r="1.7" fill="currentColor" />
                    <circle cx="12" cy="12" r="1.7" fill="currentColor" />
                    <circle cx="12" cy="19" r="1.7" fill="currentColor" />
                  </svg>
                </button>
              )}
            </CommandMenu>
          </span>
        ) : null}
      </div>

      <div className="version-sub">
        <span className="version-note">{note}</span>
        {author && code !== '' ? (
          <span className="version-code-slot">
            <span className="version-code">
              <span>{code}</span>
              <button
                type="button"
                className={copiedCode ? 'vp-copy is-done' : 'vp-copy'}
                aria-label={copiedCode ? '已复制版本码' : '复制版本码'}
                title={
                  codeCopyFailed ? '复制失败，请手动选择' : copiedCode ? '已复制' : '复制版本码'
                }
                onClick={(event) => {
                  event.stopPropagation()
                  event.preventDefault()
                  copyCode()
                }}
              >
                {!copiedCode ? (
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <rect
                      x="9"
                      y="9"
                      width="11"
                      height="11"
                      rx="2"
                      stroke="currentColor"
                      strokeWidth="1.8"
                    />
                    <path
                      d="M5 15V6a2 2 0 0 1 2-2h9"
                      stroke="currentColor"
                      strokeWidth="1.8"
                      strokeLinecap="round"
                    />
                  </svg>
                ) : (
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path
                      d="M5 12.5 9.5 17 19 7.5"
                      stroke="currentColor"
                      strokeWidth="1.8"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                )}
              </button>
              <span className="visually-hidden" aria-live="polite">
                {copiedCode ? '已复制' : ''}
              </span>
            </span>
          </span>
        ) : null}
        <span className="version-meta">{meta}</span>
      </div>
    </li>
  )
}
