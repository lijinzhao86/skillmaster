import { useRef, useState } from 'react'
import type { ChangeEvent, KeyboardEvent as ReactKeyboardEvent } from 'react'
import type { AuthoredVersion } from '../../api/types'
import { formatTime } from '../../time'
import { statusClass, statusOf, statusText, versionTitle } from '../../version'

/**
 * A version's identity: breadcrumb, title, version number, status, meta, and its two ways onward.
 *
 * **The title is always this version's name** — before and after publishing. Publishing assigns a
 * version number; it does not take the name away. An old row without one falls back to the number,
 * which is then the only identity it has; a caller's identity is the number, and that is the one
 * thing the consumption contract carries.
 *
 * A draft's rename happens on the title itself (a pencil → input → 保存 / 取消, Enter saves, Esc
 * cancels). It does not open an action block in 操作: renaming is editing this record, not changing
 * its lifecycle (TD §改动, 2026-10-20).
 */
export interface Props {
  namespace: string
  name: string
  version: AuthoredVersion
  rung: string
  listHref: string
  filesHref: string | null
  /** The whole version as one zip; same condition as 查看文件 (v9, ADR 0068). */
  downloadHref: string | null
  /** The draft's editor address; null for every version whose content is frozen (v8). */
  editHref: string | null
  jumpHref: string | null
  jumpLabel: string
  editable: boolean
  /** When the three-pane tab row is drawn, its own rule replaces the head's. */
  hasTabs: boolean
  onSaveName: (value: string) => void
}

export default function VersionHeader({
  version,
  rung,
  listHref,
  filesHref,
  downloadHref,
  editHref,
  jumpHref,
  jumpLabel,
  editable,
  hasTabs,
  onSaveName,
}: Props) {
  const status = statusOf(version)
  const author = rung !== 'invoke'
  const title = versionTitle(version, rung)
  // The design: the title is the author's name, and a row without one falls back to its number —
  // **that**, and only that, is monospaced. The old condition excluded drafts, so a pre-v6 draft that
  // declares a number drew that number in the proportional face.
  const mono = !author || version.draft_name === null
  const number =
    author && status !== 'draft' && version.name !== null && version.draft_name !== null
  const when = version.first_published_at ?? version.submitted_at
  const meta = !author
    ? `上线于 ${formatTime(when)}`
    : version.first_published_at !== null
      ? `上线于 ${formatTime(version.first_published_at)} · ${version.submitted_by ?? ''}`
      : `提交于 ${formatTime(version.submitted_at)} · ${version.submitted_by ?? ''}`

  const [editing, setEditing] = useState(false)
  const [draftName, setDraftName] = useState('')
  const input = useRef<HTMLInputElement | null>(null)

  function beginEdit(): void {
    setDraftName(version.draft_name ?? '')
    setEditing(true)
    requestAnimationFrame(() => {
      input.current?.focus()
      input.current?.select()
    })
  }

  function cancel(): void {
    setEditing(false)
  }

  function save(): void {
    const value = Array.from(draftName.trim()).slice(0, 40).join('')
    if (value === '') {
      return
    }
    onSaveName(value)
  }

  function onInput(event: ChangeEvent<HTMLInputElement>): void {
    const chars = Array.from(event.target.value)
    setDraftName(chars.length > 40 ? chars.slice(0, 40).join('') : event.target.value)
  }

  function onKeydown(event: ReactKeyboardEvent<HTMLInputElement>): void {
    if (event.key === 'Enter') {
      event.preventDefault()
      save()
    } else if (event.key === 'Escape') {
      event.preventDefault()
      cancel()
    }
  }

  return (
    <>
      <nav className="crumbs" aria-label="面包屑">
        <a href={listHref}>版本</a>
        <span className="crumbs-sep" aria-hidden="true">
          /
        </span>
        <span className="crumbs-here">{title}</span>
      </nav>

      <header className={hasTabs ? 'vp-head has-tabs' : 'vp-head'}>
        <div className="vp-head-main">
          <div className="vp-title-row">
            <span className="vp-title-name">
              {!editing ? (
                <h1 className={mono ? 'vp-title is-mono' : 'vp-title'} tabIndex={-1}>
                  {title}
                </h1>
              ) : (
                <span className="vp-title-edit">
                  <input
                    ref={input}
                    value={draftName}
                    className="vp-title-input"
                    type="text"
                    aria-label="版本名称（最多 40 个字符）"
                    title="最多 40 个字符；中文、英文、数字与符号都可以"
                    onChange={onInput}
                    onKeyDown={onKeydown}
                  />
                  <span className="vp-inline-acts" style={{ marginTop: 0 }}>
                    <button
                      type="button"
                      className="button"
                      disabled={draftName.trim() === ''}
                      onClick={save}
                    >
                      保存
                    </button>
                    <button type="button" className="button is-quiet" onClick={cancel}>
                      取消
                    </button>
                  </span>
                </span>
              )}
              {editable && !editing ? (
                <button
                  type="button"
                  className="inline-edit"
                  aria-label="改名"
                  title="改名"
                  onClick={beginEdit}
                >
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path
                      d="M4 20h4L19 9l-4-4L4 16v4z"
                      stroke="currentColor"
                      strokeWidth="1.8"
                      strokeLinejoin="round"
                    />
                    <path
                      d="M13.5 6.5l4 4"
                      stroke="currentColor"
                      strokeWidth="1.8"
                      strokeLinecap="round"
                    />
                  </svg>
                </button>
              ) : null}
            </span>

            {number ? <span className="vp-number">{`@${version.name}`}</span> : null}
            <span className="version-states">
              <span className={`chip ${statusClass(status)}`}>
                <i className="dot" />
                <span className="chip-text">{statusText(status)}</span>
              </span>
            </span>
          </div>
          <p className="vp-meta">{meta}</p>
        </div>

        <div className="vp-head-act">
          {filesHref !== null ? (
            <a className="button is-quiet vp-files-link" href={filesHref}>
              <svg className="vp-files-icon" aria-hidden="true">
                <use href="#fileMark" />
              </svg>
              查看文件
            </a>
          ) : null}
          {/* **取整包（v9）。** Same condition as 查看文件; a plain link, so the browser downloads it
              and the page draws neither progress nor result. Order is 看 → 取 → 改. */}
          {downloadHref !== null ? (
            <a className="button is-quiet vp-files-link" href={downloadHref}>
              <svg className="vp-files-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M12 4v10M8 10l4 4 4-4"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
                <path
                  d="M5 19h14"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                />
              </svg>
              下载
            </a>
          ) : null}
          {editHref !== null ? (
            <a className="button is-quiet vp-files-link" href={editHref}>
              <svg className="vp-files-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M4 20h4L19 9l-4-4L4 16v4z"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinejoin="round"
                />
                <path
                  d="M13.5 6.5l4 4"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                />
              </svg>
              编辑文件
            </a>
          ) : null}
          {jumpHref !== null && jumpLabel !== '' ? (
            <a className="button" href={jumpHref}>
              {jumpLabel}
            </a>
          ) : null}
        </div>
      </header>
    </>
  )
}
