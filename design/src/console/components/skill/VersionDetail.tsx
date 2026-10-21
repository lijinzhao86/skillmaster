/**
 * 一个版本自己的页面: what this version is, what it says, how it differs from another, and what
 * can be done to it.
 *
 * The pin in the address switches the 版本 tab from the list to this page; without it the list is
 * what the tab is. It is a record, not a second list — the same shape as GitLab's Edit Release
 * (ADR 0054). Actions run here, after the state, the note and the diff have been seen.
 */

import { useEffect, useRef, useState } from 'react'
import type { AtLeast, Version } from '../../fixtures/skill'
import {
  DIGEST,
  FILE_SUMMARY,
  SKILL_NAME,
  SKILL_OWNER,
  backAddress,
  canEditDraft,
  changeSummary,
  diffBaseline,
  editorAddress,
  liveVersion,
  stateChangeText,
  stateText,
  versionFilesAddress,
  versionOptionValue,
  versionTitleOf,
} from '../../fixtures/skill'
import CopyButton from './CopyButton'
import DiffPane from './VersionDiff'
import VersionActions from './VersionActions'
import { StateChip, Stats } from './parts'

export interface VersionDetailProps {
  target: Version
  /** The block is always in the document, as it was when this was one flat page; a version this
   *  reader cannot open leaves it hidden rather than absent. */
  hidden?: boolean
  params: URLSearchParams
  versions: Version[]
  atLeast: AtLeast
  onRename: (key: string, name: string) => void
  onSetNote: (key: string, note: string) => void
  onPublish: (key: string, name: string) => void
  onDiscard: (key: string) => void
  onRestore: (key: string) => void
  onRepublish: (key: string) => void
  onRevoke: (key: string, reason: string) => void
}

const VERSION_NAME_MAX = 40

interface StatusInfo {
  tone: string
  text: string
  reason?: string
}

/** **状态条说的是后果，不是名字.** It only says what the chip cannot: whether this version can still
 *  be read, and which version replaces it. */
function statusInfo(target: Version, versions: Version[], atLeast: AtLeast): StatusInfo | null {
  const live = liveVersion(versions)
  if (target.draft) {
    let text = '这个版本还没有上线，调用者读不到它。上线时才会分配版本号，之后可以按 @版本 调用。'
    if (!atLeast('maintain')) text += '上线需要「维护」档位。'
    return { tone: 'is-draft', text }
  }
  if (target.live) return null
  if (target.state === 'revoked') {
    return {
      tone: 'is-danger',
      text: '指定这个版本的调用会失败（410）。它仍对你可见，可以重新上架。',
      reason: target.reason || '',
    }
  }
  if (target.state === 'discarded') {
    return { tone: 'is-plain', text: '这个版本从未上线，也不会再上线；它只在作者面留作记录。' }
  }
  return {
    tone: 'is-plain',
    text: '指定 ' + (target.name ?? '') + ' 的调用仍可读取；不指定版本的调用现在读到 ' + (live ? (live.name ?? '') : '另一版') + '。',
  }
}

export default function VersionDetail({
  target,
  hidden,
  params,
  versions,
  atLeast,
  onRename,
  onSetNote,
  onPublish,
  onDiscard,
  onRestore,
  onRepublish,
  onRevoke,
}: VersionDetailProps) {
  const browse = atLeast('browse')
  const maintain = atLeast('maintain')
  const displayName = versionTitleOf(target, browse)
  const key = versionOptionValue(target)
  const titleRef = useRef<HTMLHeadingElement>(null)
  const titlePencil = useRef<HTMLButtonElement>(null)
  const notePencil = useRef<HTMLButtonElement>(null)
  const titleInput = useRef<HTMLInputElement>(null)
  const noteInput = useRef<HTMLTextAreaElement>(null)

  const editable = !!target.draft && canEditDraft(target, atLeast)
  const [titleOpen, setTitleOpen] = useState(false)
  const [titleValue, setTitleValue] = useState(target.draftName ?? '')
  const [noteOpen, setNoteOpen] = useState(false)
  const [noteValue, setNoteValue] = useState(target.note ?? '')

  useEffect(() => {
    if (titleOpen) {
      titleInput.current?.focus()
      titleInput.current?.select()
    }
  }, [titleOpen])

  useEffect(() => {
    if (noteOpen) {
      noteInput.current?.focus()
      const length = noteInput.current?.value.length ?? 0
      noteInput.current?.setSelectionRange(length, length)
    }
  }, [noteOpen])

  const closeTitle = () => {
    setTitleOpen(false)
    window.requestAnimationFrame(() => titlePencil.current?.focus())
  }
  const commitTitle = () => {
    const value = Array.from(titleValue.trim()).slice(0, VERSION_NAME_MAX).join('')
    if (value === '') return
    setTitleOpen(false)
    onRename(key, value)
    window.requestAnimationFrame(() => titlePencil.current?.focus())
  }
  const closeNote = () => {
    setNoteOpen(false)
    window.requestAnimationFrame(() => notePencil.current?.focus())
  }
  const commitNote = () => {
    setNoteOpen(false)
    onSetNote(key, noteValue.trim())
    window.requestAnimationFrame(() => notePencil.current?.focus())
  }

  const numberHidden = !browse || !!target.draft || !target.name || !target.draftName
  const meta = browse
    ? (target.firstPublishedAt ? '上线于 ' + target.firstPublishedAt : '提交于 ' + target.at) + ' · ' + target.by
    : '上线于 ' + target.at
  const status = statusInfo(target, versions, atLeast)

  const filesReadable = target.state !== 'discarded' &&
    (target.state === 'revoked'
      ? maintain
      : browse && (!target.draft || atLeast('write')))

  let jump = ''
  if (target.live && maintain) jump = '回滚'
  else if (target.draft && maintain) jump = '上线'
  else if (!target.draft && !target.live && maintain && (target.state === 'published' || !target.state)) jump = '上线'
  else if (target.state === 'revoked' && maintain) jump = '重新上架'
  const jumpHref = (() => {
    const query = new URLSearchParams(params)
    query.set('sub', 'actions')
    return '?' + query.toString()
  })()

  const baseline = diffBaseline(versions, target, params.get('from'), atLeast)
  const summary = baseline ? changeSummary(target, params.get('big') === '1') : null
  const askedPane = params.get('sub')

  const draft = !!target.draft
  const hasActions = draft
    ? (maintain || canEditDraft(target, atLeast))
    : target.state === 'discarded' ? false : maintain
  const hasDanger = !draft && !target.live && maintain && (!target.state || target.state === 'published')
  const pane = askedPane === 'diff' && baseline
    ? 'diff'
    : askedPane === 'actions' && (hasActions || hasDanger) ? 'actions' : 'record'
  const tabsHidden = !baseline && !(hasActions || hasDanger)

  const tabHref = (name: string) => {
    if (tabsHidden) return '#'
    const query = new URLSearchParams(params)
    query.set('sub', name)
    return '?' + query.toString()
  }
  const tabClass = (name: string) => 'vp-tab' + (tabsHidden ? '' : name === pane ? ' is-current' : '')

  return (
    <div id="versionDetail" hidden={hidden}>
      <nav className="crumbs" aria-label="面包屑">
        <a href={backAddress(params)} id="vpBack">版本</a>
        <span className="crumbs-sep">/</span>
        <span className="crumbs-here" id="vpCrumb">{displayName}</span>
      </nav>

      <header className={'vp-head' + (tabsHidden ? '' : ' has-tabs')}>
        <div className="vp-head-main">
          <div className="vp-title-row">
            <span className="vp-title-name">
              {titleOpen && (
                <div className="vp-title-edit" id="vpTitleEditor">
                  <input
                    type="text"
                    className="vp-title-input"
                    ref={titleInput}
                    aria-label={'版本名称（最多 ' + VERSION_NAME_MAX + ' 个字符）'}
                    title={'最多 ' + VERSION_NAME_MAX + ' 个字符；中文、英文、数字与符号都可以'}
                    value={titleValue}
                    onInput={(event) => {
                      const capped = Array.from(event.currentTarget.value).slice(0, VERSION_NAME_MAX).join('')
                      if (event.currentTarget.value !== capped) event.currentTarget.value = capped
                      setTitleValue(capped)
                    }}
                    onChange={(event) => {
                      const capped = Array.from(event.currentTarget.value).slice(0, VERSION_NAME_MAX).join('')
                      if (event.currentTarget.value !== capped) event.currentTarget.value = capped
                      setTitleValue(capped)
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') { event.preventDefault(); commitTitle() }
                      if (event.key === 'Escape') { event.preventDefault(); closeTitle() }
                    }}
                  />
                  <span className="vp-inline-acts" style={{ marginTop: '0' }}>
                    <button type="button" className="button" disabled={titleValue.trim() === ''} onClick={commitTitle}>保存</button>
                    <button type="button" className="button is-quiet" onClick={closeTitle}>取消</button>
                  </span>
                </div>
              )}
              <h1
                className={'vp-title' + (!browse || (!target.draftName && !target.draft) ? ' is-mono' : '')}
                id="vpTitle"
                tabIndex={-1}
                hidden={titleOpen}
                ref={titleRef}
              >
                {displayName}
              </h1>
              <button
                type="button"
                className="inline-edit"
                id="vpTitleEdit"
                aria-label="改名"
                title="改名"
                hidden={!editable || titleOpen}
                ref={titlePencil}
                onClick={() => {
                  setTitleValue(target.draftName ?? '')
                  setTitleOpen(true)
                }}
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4 20h4L19 9l-4-4L4 16v4z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" /><path d="M13.5 6.5l4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
              </button>
            </span>
            <span className="vp-number" id="vpNumber" hidden={numberHidden}>{numberHidden ? '' : target.name}</span>
            <span className="version-states" id="vpStates"><StateChip version={target} /></span>
          </div>
          <p className="vp-meta" id="vpMeta">{meta}</p>
        </div>
        <div className="vp-head-act">
          <a
            className="button is-quiet vp-files-link"
            id="vpFiles"
            href={filesReadable ? versionFilesAddress(target, params) : '#'}
            hidden={!filesReadable}
          >
            <svg className="vp-files-icon" aria-hidden="true"><use href="#fileMark" /></svg>
            查看文件
          </a>
          <a className="button is-quiet vp-files-link" id="vpDownload" href="#" hidden={!filesReadable}>
            <svg className="vp-files-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M12 4v10M8 10l4 4 4-4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
              <path d="M5 19h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            下载
          </a>
          <a
            className="button is-quiet vp-files-link"
            id="vpEdit"
            href={editable ? editorAddress(target, params) : '#'}
            hidden={!editable}
          >
            <svg className="vp-files-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4 20h4L19 9l-4-4L4 16v4z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" /><path d="M13.5 6.5l4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
            编辑文件
          </a>
          <a className="button" id="vpJump" href={jumpHref} hidden={jump === ''}>{jump}</a>
        </div>
      </header>

      <div className={status ? 'vp-status ' + status.tone : 'vp-status'} id="vpStatus" hidden={!status}>
        <span className="vp-status-dot" aria-hidden="true"></span>
        <p id="vpStatusText">
          {status ? status.text : ''}
          {status && status.reason ? <span className="vp-status-reason">{'原因：' + status.reason}</span> : null}
        </p>
      </div>

      <nav className="vp-tabs" id="vpTabs" aria-label="版本页的三栏" hidden={tabsHidden}>
        <a
          className={tabClass('record')}
          data-vpane="record"
          href={tabHref('record')}
          aria-current={!tabsHidden && pane === 'record' ? 'page' : undefined}
        >
          概览
        </a>
        <a
          className={tabClass('diff')}
          data-vpane="diff"
          id="vpDiffTab"
          href={tabHref('diff')}
          aria-current={!tabsHidden && pane === 'diff' ? 'page' : undefined}
          hidden={!baseline}
        >
          改动 <span className="vp-tab-count" id="vpChangeCount">{summary ? String(summary.files.length) : ''}</span>
        </a>
        <a
          className={tabClass('actions')}
          data-vpane="actions"
          id="vpActionsTab"
          href={tabHref('actions')}
          aria-current={!tabsHidden && pane === 'actions' ? 'page' : undefined}
          hidden={!hasActions && !hasDanger}
        >
          操作
        </a>
        <span className="vp-tabs-stats" id="vpChangeStats" hidden={!baseline}>
          {summary ? <Stats added={summary.added} removed={summary.removed} /> : null}
        </span>
      </nav>

      <div className="vp-pane" id="vpRecordPane" data-vpane="record" hidden={pane !== 'record'}>
        <div className="vp-layout">
          <div className="vp-main">
            <section className="vp-module" id="vpNoteModule">
              <span className="info-label">
                <svg className="info-icon" aria-hidden="true"><use href="#descMark" /></svg>
                说明
                <button
                  type="button"
                  className="inline-edit"
                  id="vpNoteEdit"
                  aria-label="编辑说明"
                  title="编辑说明"
                  hidden={!editable || noteOpen}
                  ref={notePencil}
                  onClick={() => {
                    setNoteValue(target.note ?? '')
                    setNoteOpen(true)
                  }}
                >
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4 20h4L19 9l-4-4L4 16v4z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" /><path d="M13.5 6.5l4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
                </button>
              </span>
              <p className={'vp-note' + (target.note ? '' : ' is-empty')} id="vpNote" hidden={noteOpen}>
                {target.note || '还没有说明'}
              </p>
              {noteOpen && (
                <>
                  <textarea
                    id="vpNoteEditor"
                    className="vp-note-input"
                    rows={3}
                    placeholder="说明（可选）"
                    aria-label="说明"
                    ref={noteInput}
                    value={noteValue}
                    onInput={(event) => {
                      const capped = Array.from(event.currentTarget.value).slice(0, 200).join('')
                      if (event.currentTarget.value !== capped) event.currentTarget.value = capped
                      setNoteValue(capped)
                    }}
                    onChange={(event) => {
                      const capped = Array.from(event.currentTarget.value).slice(0, 200).join('')
                      if (event.currentTarget.value !== capped) event.currentTarget.value = capped
                      setNoteValue(capped)
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Escape') { event.preventDefault(); closeNote() }
                      if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
                        event.preventDefault()
                        commitNote()
                      }
                    }}
                  ></textarea>
                  <div className="vp-inline-acts">
                    <button type="button" className="button" onClick={commitNote}>保存</button>
                    <button type="button" className="button is-quiet" onClick={closeNote}>取消</button>
                  </div>
                </>
              )}
            </section>

            <section className="vp-module" id="vpHistory">
              <p className="vp-eyebrow">时间线</p>
              <ol className="vp-timeline" id="vpTimeline">
                <Timeline target={target} caller={!browse} />
              </ol>
            </section>
          </div>

          <aside className="vp-facts" id="vpFacts" aria-label="版本事实">
            <h2 className="vp-facts-title">版本事实</h2>
            <dl className="vp-facts-list" id="vpFactsList">
              <VersionFacts target={target} versions={versions} atLeast={atLeast} />
            </dl>
          </aside>
        </div>
      </div>

      <DiffPane
        active={pane === 'diff'}
        target={target}
        baseline={baseline}
        versions={versions}
        atLeast={atLeast}
        params={params}
      />

      <VersionActions
        active={pane === 'actions'}
        target={target}
        versions={versions}
        atLeast={atLeast}
        focusTitle={() => titleRef.current}
        onPublish={onPublish}
        onDiscard={onDiscard}
        onRestore={onRestore}
        onRepublish={onRepublish}
        onRevoke={onRevoke}
      />
    </div>
  )
}

function VersionFacts({
  target,
  versions,
  atLeast,
}: {
  target: Version
  versions: Version[]
  atLeast: AtLeast
}) {
  const browse = atLeast('browse')
  const caller = !browse
  const address = SKILL_OWNER + '/' + SKILL_NAME +
    (target.name || (target.pin ? '@' + target.pin : ''))

  type Row = [string, string, string | null]
  let rows: Row[]
  if (caller) {
    rows = [
      ['版本号', target.name || '—', target.name],
      ['状态', stateText(target), null],
      ['首次上线', target.firstPublishedAt || '—', null],
      ['地址', address, address],
    ]
  } else {
    rows = [
      ['名称', target.draftName || '—', target.draftName ?? null],
      ['版本号', target.name || '—', target.name],
      ['版本码', target.code || '—', target.code],
      ['状态', stateText(target), null],
      ['提交', target.by + ' · ' + (target.submittedAt || target.at), null],
      ['首次上线', target.firstPublishedAt || '—', null],
    ]
    /* **重复的事实不画。** 最近状态变更与首次上线是同一个时间时，它就是同一句话。 */
    const changedAt = target.stateAt
    if (changedAt && changedAt !== target.firstPublishedAt) {
      rows.push(['最近状态变更', stateChangeText(target) + ' ' + changedAt, null])
    }
    rows.push(['内容摘要', DIGEST, DIGEST])
    rows.push(['文件', FILE_SUMMARY, null])
    rows.push(['地址', address, address])
  }

  return (
    <>
      {rows.map((row) => (
        <div key={row[0]}>
          <dt>{row[0]}</dt>
          <dd>
            <div className="vp-fact-line">
              <span>{row[1]}</span>
              {row[2] ? <CopyButton text={row[2]} label={row[0]} /> : null}
            </div>
          </dd>
        </div>
      ))}
    </>
  )
}

function Timeline({ target, caller }: { target: Version; caller: boolean }) {
  const items: Array<{ label: string; time: string }> = []
  /* 提交是作者面的事实；调用者的时间线从上线开始。 */
  if (!caller) items.push({ label: '提交', time: target.submittedAt || target.at })
  if (target.firstPublishedAt) items.push({ label: '首次上线', time: target.firstPublishedAt })
  if (!caller && target.state === 'revoked' && target.stateAt) {
    items.push({ label: '下架', time: target.stateAt })
  }
  if (!caller && target.state === 'discarded' && target.stateAt) {
    items.push({ label: '丢弃', time: target.stateAt })
  }
  return (
    <>
      {items.map((item, index) => (
        <li className={index === items.length - 1 ? 'is-current' : undefined} key={item.label}>
          {item.label + ' '}
          <span className="time">{item.time}</span>
        </li>
      ))}
    </>
  )
}
