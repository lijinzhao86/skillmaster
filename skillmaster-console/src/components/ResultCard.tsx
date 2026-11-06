import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { go } from '../address'
import type { SkillCard } from '../api/types'
import { SOURCE_LABEL } from '../libraryLabels'
import SkillAvatar from './SkillAvatar'
import SourceMark from './SourceMark'
/**
 * One public result row.
 *
 * **Both places on 发现 use it** — the opening 「为你推荐」 stream and the list after a search or
 * a category — and they share it so the "already in your library" case cannot be missed in one of
 * them.
 *
 * **Three lines, everything else on the skill page** (2026-11-05): the name with its category,
 * one line of description, and the author's public tags. The row's tail carries the call bucket,
 * the reserved favourite, the join bookmark and the library count. License, 更新时间, 适用场景 and
 * the version moved to the information page, so a 1440-wide screen scans seven rows instead of two
 * cards.
 *
 * **The action is one icon at the row's tail.** Joining is a bookmark: outlined when absent,
 * filled and accent-coloured once joined; pressing a filled one takes it back. A row already in
 * the library draws the same filled bookmark but static — removing your own library entry happens
 * on the skill page, with a confirmation (ADR 0056). The source mark sits at the row's bottom
 * right: it says where the skill came from, never how popular it is.
 */
export type JoinState = 'idle' | 'busy' | 'failed'

/** Copy one address. `file://` and some test environments have no Clipboard API; fall back. */
function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    return navigator.clipboard.writeText(text)
  }
  const area = document.createElement('textarea')
  area.value = text
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  document.execCommand('copy')
  area.remove()
  return Promise.resolve()
}

export default function ResultCard({
  skill,
  joinState,
  joinError,
  onJoin,
  onRemove,
}: {
  skill: SkillCard
  joinState: JoinState
  /** The server's (or transport's) sentence; the card never invents a cause. */
  joinError?: string
  onJoin: () => void
  /** Only for the card joined on this screen: pressing the filled bookmark takes that back. */
  onRemove?: () => void
}) {
  /* **Copy the name**: it copies `namespace/name` (the same address the CLI prints), turns into a
     check for 1.6 seconds, then back to the copy icon. */
  const [copied, setCopied] = useState(false)
  const copyTimer = useRef<number | null>(null)
  useEffect(
    () => () => {
      if (copyTimer.current !== null) {
        window.clearTimeout(copyTimer.current)
      }
    },
    [],
  )
  const address = skill.namespace + '/' + skill.name
  const onCopy = () => {
    void copyText(address)
      .then(() => {
        setCopied(true)
        if (copyTimer.current !== null) {
          window.clearTimeout(copyTimer.current)
        }
        copyTimer.current = window.setTimeout(() => setCopied(false), 1600)
      })
      .catch(() => {})
  }

  const bookmark =
    skill.source === 'joined' && onRemove ? (
      /* Joined on this screen: the icon is a switch, pressing it takes the join back. */
      <button type="button" className="result-join" data-state="joined" onClick={onRemove} title="移出库">
        <svg key="joined" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z" fill="currentColor" />
        </svg>
        <span className="visually-hidden">移出库</span>
      </button>
    ) : skill.source !== null ? (
      /* Already in the library: the same filled bookmark, but static — removal is on the skill page. */
      <span className="result-inlibrary" title={'已在库 · ' + SOURCE_LABEL[skill.source]}>
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z" fill="currentColor" />
        </svg>
        <span className="visually-hidden">{'已在库 · ' + SOURCE_LABEL[skill.source]}</span>
      </span>
    ) : joinState === 'busy' ? (
      /* Joining: the same spot becomes a turning arc. */
      <button type="button" className="result-join" data-state="busy" disabled title="入库中">
        <svg className="spin" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle
            cx="12"
            cy="12"
            r="8"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeDasharray="30 20"
          />
        </svg>
        <span className="visually-hidden">入库中…</span>
      </button>
    ) : (
      <button type="button" className="result-join" data-state="idle" onClick={onJoin} title="入库">
        <svg key="idle" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinejoin="round"
          />
        </svg>
        <span className="visually-hidden">入库</span>
      </button>
    )

  const skillHref = `/skills/${encodeURIComponent(skill.namespace)}/${encodeURIComponent(skill.name)}`
  /* The hover name is the platform and its address — `GitHub · owner/repo` — not just the platform. */
  const originTitle = [skill.origin_label, skill.origin_locator]
    .filter((part): part is string => part !== null && part !== undefined && part !== '')
    .join(' · ')
  const shownTags = skill.public_tags.slice(0, 3)
  const moreTags = skill.public_tags.length - shownTags.length

  /* **`+N` opens the full tag list in a popover** (2026-11-05): the row keeps three lines, so the
     expansion hangs off the body at the button's position; outside click or Esc closes it. */
  const [tagsOpen, setTagsOpen] = useState(false)
  const moreRef = useRef<HTMLButtonElement>(null)
  const [popoverPos, setPopoverPos] = useState<{ left: number; top: number } | null>(null)
  const toggleTags = () => {
    if (tagsOpen) {
      setTagsOpen(false)
      return
    }
    const rect = moreRef.current?.getBoundingClientRect()
    if (rect === undefined) {
      return
    }
    setPopoverPos({
      left: Math.max(12, Math.min(rect.left, window.innerWidth - 356)),
      top: rect.bottom + 6,
    })
    setTagsOpen(true)
  }
  useEffect(() => {
    if (!tagsOpen) {
      return
    }
    const onDown = (event: MouseEvent) => {
      const node = event.target
      if (
        node instanceof Element &&
        (node.closest('.tags-pop') !== null || node.closest('.result-more') !== null)
      ) {
        return
      }
      setTagsOpen(false)
    }
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') {
        setTagsOpen(false)
      }
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [tagsOpen])

  /* The library count: the joins already in the library; a row joined on this screen counts one
     more immediately, without waiting for the next read. */
  const libraryCount = (skill.joins ?? 0) + (onRemove === undefined ? 0 : 1)

  return (
    <li
      className="result"
      data-name={skill.name}
      /* **The whole row opens the skill page** (2026-11-05). The row already carries copy, save,
         join, category and source controls, so clicks on those stay with them. */
      onClick={(event) => {
        const target = event.target
        if (
          target instanceof Element &&
          target.closest('a,button,.result-copy,.result-more,.result-category,.result-action.is-reserved,.result-source-badge') !== null
        ) {
          return
        }
        go(skillHref)
      }}
    >
      <SkillAvatar name={skill.name} />
      <div className="result-main">
        <div className="result-head">
          <span className="result-ident">
            {/* No spaces around the slash: `namespace/name` is the CLI's spelling, and copying it
                has to give something that can be pasted straight into a command. */}
            <a className="result-name" href={skillHref} title={address}>
              <span className="ns">{skill.namespace}/</span>
              {skill.name}
            </a>
            <button
              type="button"
              className="result-copy"
              onClick={onCopy}
              title={copied ? '已复制' : '复制名字'}
              aria-label={copied ? '已复制' : '复制名字'}
              data-copied={copied}
            >
              {copied ? (
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M5 12.5 10 17.5 19 7"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              ) : (
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <rect x="9" y="9" width="11" height="11" rx="2" stroke="currentColor" strokeWidth="1.7" />
                  <path
                    d="M15 9V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h3"
                    stroke="currentColor"
                    strokeWidth="1.7"
                  />
                </svg>
              )}
            </button>
          </span>
          {skill.category !== null ? (
            /* **The category on the row is a way to browse** (2026-11-05): pressing it shows the
               same shelf; the header no longer carries a category picker. */
            <a
              className="chip is-plain result-category"
              href={'/discover?tag=' + encodeURIComponent(skill.category.key)}
              title="查看这个分类下的 skill"
            >
              {skill.category.name}
            </a>
          ) : null}
        </div>
        {/* One line of description; the whole paragraph rides in `title`, and the main path is the
            skill page. */}
        <p className="result-title" title={skill.description}>
          {skill.description}
        </p>
        {/* **The third line is only the public tags** (2026-11-05): source and account count moved
            to the information page; this line answers "why was this found". */}
        <div className="result-bottom">
          <div className="result-tags">
            {shownTags.map((tag) => (
              <span className="result-tag" key={tag.key}>
                {'#' + tag.name}
              </span>
            ))}
            {moreTags > 0 ? (
              <button
                type="button"
                ref={moreRef}
                className="result-more"
                aria-expanded={tagsOpen}
                title={'显示全部 ' + skill.public_tags.length + ' 个公开标签'}
                onClick={toggleTags}
              >
                {'+' + moreTags}
              </button>
            ) : null}
          </div>
        </div>
        {joinState === 'failed' ? (
          <p className="result-error" role="alert">
            {joinError ?? '没有入库：服务器没有响应。'}
            <button type="button" className="link-button" onClick={onJoin}>
              重试
            </button>
          </p>
        ) : null}
      </div>
      {tagsOpen && popoverPos !== null
        ? createPortal(
            <div
              className="tags-pop"
              role="dialog"
              aria-label="全部公开标签"
              style={{ left: popoverPos.left, top: popoverPos.top }}
            >
              <p className="tags-pop-head">{'公开标签 · ' + skill.public_tags.length}</p>
              <div className="tags-pop-list">
                {skill.public_tags.map((tag) => (
                  <span className="tags-pop-tag" key={tag.key}>
                    {'#' + tag.name}
                  </span>
                ))}
              </div>
            </div>,
            document.body,
          )
        : null}
      {/* **Three cells at the row's tail, each count under its icon** (2026-11-05): calls → the
          reserved favourite → join. The first two are facts; join is the action, and the source
          mark hangs at the bottom right. */}
      <div className="result-actions">
        <div className="result-action-row">
          <div className="result-action is-static" title="累计调用次数">
            <span className="result-action-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none">
                <path
                  d="M13 2.5 5 14h6l-1 7.5 9-12h-6l0-7Z"
                  stroke="currentColor"
                  strokeWidth="1.6"
                  strokeLinejoin="round"
                />
              </svg>
            </span>
            <span className="result-count">{skill.calls_bucket ?? '—'}</span>
            <span className="visually-hidden">累计调用次数</span>
          </div>
          <div className="result-action is-reserved" title="收藏（功能暂未开放）">
            <span className="result-action-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none">
                <path
                  d="M12 20s-7-4.35-7-10a4 4 0 0 1 7-2.65A4 4 0 0 1 19 10c0 5.65-7 10-7 10Z"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinejoin="round"
                />
              </svg>
            </span>
            <span className="result-count">—</span>
            <span className="visually-hidden">收藏（功能暂未开放）</span>
          </div>
          <div className="result-action">
            {bookmark}
            <span className="result-count">{libraryCount}</span>
          </div>
        </div>
        {/* **The source mark sits at the bottom right** (2026-11-05): it says where this row came
            from, so it does not join the action row. No mark without one; an official skill has no
            source page, so its mark is a label, not a link. */}
        <div className="result-sources">
          {skill.origin_label === null || skill.origin_label === undefined ? null : skill.origin_url ? (
            <a
              className="result-source-badge"
              data-kind={skill.origin_kind ?? 'official'}
              href={skill.origin_url}
              target="_blank"
              rel="noreferrer"
              title={originTitle}
              aria-label={'在新标签打开来源：' + originTitle}
            >
              <SourceMark kind={skill.origin_kind ?? 'official'} />
            </a>
          ) : (
            <span
              className="result-source-badge"
              data-kind={skill.origin_kind ?? 'official'}
              title={originTitle}
            >
              <SourceMark kind={skill.origin_kind ?? 'official'} />
            </span>
          )}
        </div>
      </div>
    </li>
  )
}
