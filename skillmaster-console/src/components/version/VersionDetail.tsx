import { useRef, useState } from 'react'
import { addressFor, query } from '../../address'
import { codeMessage } from '../../api/errors'
import { updateDraftName, updateDraftNote } from '../../api/skills'
import type { AuthoredVersion } from '../../api/types'
import {
  canEditDraft,
  canMaintain,
  canWrite,
  defaultBaseline,
  hasVersionActions,
} from '../../versionActions'
import type { VersionRoute } from '../../version'
import { answersPin, statusOf, versionSuffix } from '../../version'
import { formatTime } from '../../time'
import FormBanner from '../FormBanner'
import VersionActionPanel from './VersionActionPanel'
import VersionDiffPane from './VersionDiffPane'
import VersionFacts from './VersionFacts'
import VersionHeader from './VersionHeader'
import VersionStatusBar from './VersionStatusBar'

/**
 * One version's own page: identity and state at the top, then 概览 / 改动 / 操作.
 *
 * **This is a record, not a second list.** Selecting a version happens in the list; this page is
 * reached by pinning one. The three panes are the three ways to read it — what it is and said
 * (概览), what it changes (改动), what can be done to it (操作). A pane that has nothing to show is
 * not drawn; when neither of the latter two exists, the tab row itself is not drawn. An address
 * that asks for a pane this rung cannot see falls back to 概览 rather than to an empty shell.
 *
 * **Renaming and editing the note happen in place** (title pencil, note pencil) and are not action
 * blocks: they edit this record, they do not change its lifecycle. Both are draft-only; a published
 * version has no pencil.
 */
export interface VersionDetailProps {
  namespace: string
  name: string
  version: AuthoredVersion
  versions: AuthoredVersion[]
  rung: string
  username: string | null
  live: AuthoredVersion | null
  route: VersionRoute
  onSelectBaseline: (pin: string) => void
}

interface DiffSummary {
  files: number
  added: number | null
  removed: number | null
}

export default function VersionDetail({
  namespace,
  name,
  version,
  versions,
  rung,
  username,
  live,
  route,
  onSelectBaseline,
}: VersionDetailProps) {
  const status = statusOf(version)
  const maintain = canMaintain(rung)
  const editable = canEditDraft(version, rung, username)
  const hasActions = hasVersionActions(version, rung, username)

  const baseline = ((): AuthoredVersion | null => {
    const from = route.from
    const chosen =
      from === undefined
        ? undefined
        : versions.find(
            (candidate) => candidate !== version && answersPin(candidate, from),
          )
    return (
      chosen ??
      defaultBaseline(version, versions, live, rung)
    )
  })()

  const hasDiff = rung !== 'invoke' && baseline !== null
  const pane: 'record' | 'diff' | 'actions' = (() => {
    if (route.sub === 'diff' && hasDiff) {
      return 'diff'
    }
    if (route.sub === 'actions' && hasActions) {
      return 'actions'
    }
    return 'record'
  })()

  const pin = versionSuffix(version)
  const base = `/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`

  const listHref = ((): string => {
    const params = query()
    for (const key of ['version', 'sub', 'bump', 'from', 'to', 'fq', 'file', 'list']) {
      params.delete(key)
    }
    params.set('tab', 'versions')
    return addressFor(base, params)
  })()

  function subHref(sub: 'record' | 'diff' | 'actions'): string {
    const params = query()
    params.set('tab', 'versions')
    params.set('version', pin)
    if (sub === 'record') {
      params.delete('sub')
    } else {
      params.set('sub', sub)
    }
    return addressFor(base, params)
  }

  const filesHref: string | null = (() => {
    if (rung === 'invoke' || status === 'discarded') {
      return null
    }
    if (status === 'draft' && !canWrite(rung)) {
      return null
    }
    return `${base}@${encodeURIComponent(pin)}?tab=files`
  })()

  const jumpLabel = ((): string => {
    if (!maintain) {
      return ''
    }
    switch (status) {
      case 'draft':
        return '上线'
      case 'live':
        return '回滚'
      case 'history':
        return '上线'
      case 'revoked':
        return '重新上架'
      default:
        return ''
    }
  })()

  const jumpHref = jumpLabel === '' ? null : subHref('actions')

  /**
   * The archive is the server's route, not this page's: a plain `<a>` to `/web/skills/.../archive`
   * lets the browser download it. Same condition as 查看文件 — whoever can read the files can take
   * the whole version (ADR 0068) — and discarded never reaches it, because `filesHref` is null there.
   */
  const downloadHref: string | null =
    filesHref === null
      ? null
      : `/web/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}@${encodeURIComponent(pin)}/archive`

  /**
   * 编辑文件 is the draft's second entry (v8), beside 查看文件: the two questions about a draft are
   * "what is in it" and "I want to change it". `editable` already means 草稿 + 我能改, so published,
   * historical, revoked and discarded rows have no link.
   */
  const editHref: string | null = editable ? `${base}@${encodeURIComponent(pin)}/edit` : null

  const [localBanner, setLocalBanner] = useState<string | null>(null)

  async function rename(value: string): Promise<void> {
    const result = await updateDraftName(namespace, name, pin, value)
    if (!result.ok) {
      setLocalBanner(codeMessage(result.code, result.message))
      return
    }
    window.location.reload()
  }

  /* --------------------------------------------------------------- the note */

  const [editingNote, setEditingNote] = useState(false)
  const [noteDraft, setNoteDraft] = useState('')
  const noteInput = useRef<HTMLTextAreaElement | null>(null)

  function beginNote(): void {
    setNoteDraft(version.note ?? '')
    setEditingNote(true)
    requestAnimationFrame(() => noteInput.current?.focus())
  }

  function cancelNote(): void {
    setEditingNote(false)
  }

  function onNoteInput(value: string): void {
    const chars = Array.from(value)
    setNoteDraft(chars.length > 200 ? chars.slice(0, 200).join('') : value)
  }

  async function saveNote(): Promise<void> {
    const result = await updateDraftNote(namespace, name, pin, noteDraft.trim())
    if (!result.ok) {
      setLocalBanner(codeMessage(result.code, result.message))
      return
    }
    window.location.reload()
  }

  /* --------------------------------------------------------------- the timeline */

  const timeline: Array<{ label: string; time: string }> = []
  if (rung !== 'invoke') {
    timeline.push({ label: '提交', time: formatTime(version.submitted_at) })
  }
  if (version.first_published_at !== null) {
    timeline.push({ label: '首次上线', time: formatTime(version.first_published_at) })
  }
  if (rung !== 'invoke' && status === 'revoked' && version.state_at !== null) {
    timeline.push({ label: '下架', time: formatTime(version.state_at) })
  }
  if (rung !== 'invoke' && status === 'discarded' && version.state_at !== null) {
    timeline.push({ label: '丢弃', time: formatTime(version.state_at) })
  }

  /* --------------------------------------------------------------- the diff summary */

  const [summary, setSummary] = useState<DiffSummary | null>(null)

  return (
    <div id="versionDetail">
      <FormBanner message={localBanner} />

      <VersionHeader
        namespace={namespace}
        name={name}
        version={version}
        rung={rung}
        listHref={listHref}
        filesHref={filesHref}
        downloadHref={downloadHref}
        editHref={editHref}
        jumpHref={jumpHref}
        jumpLabel={jumpLabel}
        editable={editable}
        hasTabs={hasDiff || hasActions}
        onSaveName={rename}
      />

      <VersionStatusBar version={version} live={live} rung={rung} />

      {hasDiff || hasActions ? (
        <nav className="vp-tabs" aria-label="版本页的三栏">
          <a
            className={pane === 'record' ? 'vp-tab is-current' : 'vp-tab'}
            aria-current={pane === 'record' ? 'page' : undefined}
            href={subHref('record')}
          >
            概览
          </a>
          {hasDiff ? (
            <a
              className={pane === 'diff' ? 'vp-tab is-current' : 'vp-tab'}
              aria-current={pane === 'diff' ? 'page' : undefined}
              href={subHref('diff')}
            >
              改动 <span className="vp-tab-count">{summary === null ? '—' : summary.files}</span>
            </a>
          ) : null}
          {hasActions ? (
            <a
              className={pane === 'actions' ? 'vp-tab is-current' : 'vp-tab'}
              aria-current={pane === 'actions' ? 'page' : undefined}
              href={subHref('actions')}
            >
              操作
            </a>
          ) : null}
          {hasDiff ? (
            <span className="vp-tabs-stats">
              {summary !== null ? (
                <>
                  <b className="stat-add">+{summary.added}</b>{' '}
                  <b className="stat-del">−{summary.removed}</b>
                </>
              ) : (
                <>—</>
              )}
            </span>
          ) : null}
        </nav>
      ) : null}

      <div style={{ display: (pane !== 'record') ? 'none' : undefined }} className="vp-pane" data-vpane="record">
        <div className="vp-layout">
          <div className="vp-main">
            <section className="vp-module">
              <span className="info-label">
                <svg className="info-icon" aria-hidden="true"><use href="#descMark" /></svg>
                说明
                {editable ? (
                  <button
                    type="button"
                    className="inline-edit"
                    aria-label="编辑说明"
                    title="编辑说明"
                    onClick={beginNote}
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

              {!editingNote ? (
                <p className={version.note ? 'vp-note' : 'vp-note is-empty'}>
                  {version.note || '还没有说明'}
                </p>
              ) : (
                <>
                  <textarea
                    ref={noteInput}
                    value={noteDraft}
                    className="vp-note-input"
                    rows={3}
                    aria-label="说明"
                    placeholder="这个版本改了什么"
                    onChange={(event) => onNoteInput(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === 'Escape') {
                        event.preventDefault()
                        cancelNote()
                      } else if (
                        event.key === 'Enter' &&
                        (event.metaKey || event.ctrlKey)
                      ) {
                        event.preventDefault()
                        void saveNote()
                      }
                    }}
                  />
                  <div className="vp-inline-acts">
                    <button type="button" className="button" onClick={() => void saveNote()}>
                      保存
                    </button>
                    <button type="button" className="button is-quiet" onClick={cancelNote}>
                      取消
                    </button>
                  </div>
                </>
              )}
            </section>

            {timeline.length > 0 ? (
              <section className="vp-module">
                <p className="vp-eyebrow">时间线</p>
                <ol className="vp-timeline">
                  {timeline.map((item, index) => (
                    <li
                      key={index}
                      className={
                        index === timeline.length - 1 ? 'is-current' : undefined
                      }
                    >
                      {item.label}
                      <span className="time">{item.time}</span>
                    </li>
                  ))}
                </ol>
              </section>
            ) : null}
          </div>

          <VersionFacts
            version={version}
            namespace={namespace}
            name={name}
            rung={rung}
          />
        </div>
      </div>

      {/* Kept mounted while another pane is open, so the tab's count and totals are there when the
          reader comes back; it is hidden, not unmounted. */}
      {hasDiff ? (
        <VersionDiffPane
          style={{ display: (pane !== 'diff') ? 'none' : undefined }}
          namespace={namespace}
          name={name}
          version={version}
          versions={versions}
          rung={rung}
          from={route.from}
          fq={route.fq}
          file={route.file}
          list={route.list}
          live={live}
          onSummary={setSummary}
          onSelectBaseline={onSelectBaseline}
        />
      ) : null}

      <div style={{ display: (pane !== 'actions') ? 'none' : undefined }} className="vp-pane vp-actions-pane" data-vpane="actions">
        {hasActions ? (
          <VersionActionPanel
            namespace={namespace}
            name={name}
            version={version}
            versions={versions}
            rung={rung}
            username={username}
            live={live}
            initialBump={route.bump}
          />
        ) : null}
      </div>
    </div>
  )
}
