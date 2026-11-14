import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { go } from '../address'
import type { SkillCard } from '../api/types'
import { SOURCE_LABEL } from '../libraryLabels'
import { formatDay } from '../time'
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
 * one line of the author's description, and a third line that is **attributes on the left and
 * public tags on the right** (v65). The row's tail carries the call bucket, the reserved favourite,
 * the join bookmark and the library count. License, 更新时间, 适用场景 and the version moved to the
 * information page, so a 1440-wide screen scans seven rows instead of two cards.
 *
 * **The expected result does not go on this row** (v65): the row answers "what is this", and the
 * description does that better than a one-line claim. The outcome is on the information page.
 *
 * **The action is one icon at the row's tail.** Joining is a bookmark: outlined when absent,
 * filled and accent-coloured once joined; pressing a filled one takes it back. A row already in
 * the library draws the same filled bookmark but static — removing your own library entry happens
 * on the skill page, with a confirmation (ADR 0056). The source mark sits at the row's bottom
 * right: it says where the skill came from, never how popular it is.
 */
export type JoinState = 'idle' | 'busy' | 'failed'

/**
 * v65: the attribute group's icon — terminal (language), screen (OS), tool (tools and resources),
 * key (credentials), price tag (pricing). **The colour comes from `data-group` in CSS**; the icon is
 * what tells the groups apart for a reader who does not see colour.
 */
function BlockerIcon({ kind }: { kind: 'key' | 'runtime' | 'os' | 'tool' | 'price' }) {
  if (kind === 'key') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="8.2" cy="8.2" r="3.6" stroke="currentColor" strokeWidth="1.8" />
        <path d="M10.8 10.8 19 19M15.2 15.2l2-2M17.2 17.2l2-2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      </svg>
    )
  }
  if (kind === 'runtime') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M4.8 7.2l4.6 4.8-4.6 4.8M12.4 16.8h6.8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )
  }
  if (kind === 'os') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <rect x="3.5" y="4.5" width="17" height="12" rx="1.6" stroke="currentColor" strokeWidth="1.8" />
        <path d="M8.5 20h7M12 16.5V20" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      </svg>
    )
  }
  if (kind === 'tool') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M14.5 6.2a4 4 0 0 0-5 5L4.8 15.9a1.8 1.8 0 0 0 2.5 2.5l4.7-4.7a4 4 0 0 0 5-5l-2.6 2.6-2.4-2.4 2.5-2.7Z" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )
  }
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M12 3.8 20 8v8l-8 4.2L4 16V8l8-4.2Z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
      <path d="M9.5 9.5h5M12 9.5v6M9.8 12.2h4.4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  )
}

/** The group decides the icon; the colour comes from CSS via `data-group`. */
function iconKind(group: string): 'key' | 'runtime' | 'os' | 'tool' | 'price' {
  if (group === 'credential') return 'key'
  if (group === 'os') return 'os'
  if (group === 'tool') return 'tool'
  if (group === 'pricing') return 'price'
  return 'runtime'
}

/**
 * v65: how many attribute chips the row shows before the rest become `+N`.
 *
 * **Three is the design's number** (PRD A13): a row is read to rule candidates out, and a fourth
 * chip costs a line without changing that decision. The information page shows every one.
 */
const ATTR_CHIPS = 3

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
    ) : skill.state === 'pending' ? (
      /* v61 (ADR 0184): the ramp refuses new dependencies, so the card does not offer the action
         it would have to refuse. The chip says why; the action column keeps its width. */
      null
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

  /* **v65: the third line is attributes on the left, public tags on the right.** The attributes
     answer "what will I need to run it"; the tags answer "why was this found". Each chip filters
     the public results by that one attribute — the same address shape a category chip uses. */
  const attributeChips = (skill.labels ?? []).map((label) => ({
    id: label.key + ':' + label.value,
    href:
      '/discover?label=' + encodeURIComponent(label.key + ':' + label.value),
    label: label.label,
    group: label.group,
    kind: iconKind(label.group),
    /* A display-only word (`filterable: false`) has no link: the server would refuse the filter
       as an unknown label. An older server omits the field, which reads as filterable. */
    filterable: label.filterable !== false,
  }))
  const shownAttrs = attributeChips.slice(0, ATTR_CHIPS)
  const hiddenAttrs = attributeChips.slice(ATTR_CHIPS)

  /* **`+N` opens the rest in a popover** (2026-11-05; v65 adds attributes): the row keeps three
     lines, so the expansion hangs off the body at the button's position; outside click or Esc
     closes it. */
  const [openMore, setOpenMore] = useState<{
    kind: 'tags' | 'attrs'
    left: number
    top: number
  } | null>(null)
  const tagsRef = useRef<HTMLButtonElement>(null)
  const attrsRef = useRef<HTMLButtonElement>(null)
  const toggleMore = (kind: 'tags' | 'attrs', ref: { current: HTMLButtonElement | null }) => {
    if (openMore?.kind === kind) {
      setOpenMore(null)
      return
    }
    const rect = ref.current?.getBoundingClientRect()
    if (rect === undefined) {
      return
    }
    setOpenMore({
      kind,
      left: Math.max(12, Math.min(rect.left, window.innerWidth - 356)),
      top: rect.bottom + 6,
    })
  }
  useEffect(() => {
    if (openMore === null) {
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
      setOpenMore(null)
    }
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpenMore(null)
      }
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [openMore])

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
              title={copied ? '已复制' : '复制地址'}
              aria-label={copied ? '已复制' : '复制地址'}
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
          {/* v61 (ADR 0184): a ramp is still in the public list — it still serves — so the card
              says it is leaving and when, rather than letting it look permanent. */}
          {skill.state === 'pending' ? (
            <span className="chip is-soon" title="下线中：仍可调用，已不接受新入库">
              下线中{skill.offline_pending_until == null
                ? ''
                : ` · ${formatDay(skill.offline_pending_until)}`}
            </span>
          ) : null}
        </div>
        {/* One line of description; the whole paragraph rides in `title`, and the main path is the
            skill page. */}
        <p className="result-title" title={skill.description}>
          {skill.description}
        </p>
        {/* **v65: the third line is attributes on the left, public tags on the right**
            (2026-11-13 用户定). The two answer different questions, so they no longer share one
            `#` run. Source and account count stay on the information page. */}
        <div className="result-bottom">
          <div className="result-attrs" aria-label="属性">
            {shownAttrs.map((chip) =>
              chip.filterable ? (
                <a
                  className="chip is-blocker"
                  key={chip.id}
                  href={chip.href}
                  data-group={chip.group}
                >
                  <BlockerIcon kind={chip.kind} />
                  {chip.label}
                </a>
              ) : (
                <span className="chip is-blocker" key={chip.id} data-group={chip.group}>
                  <BlockerIcon kind={chip.kind} />
                  {chip.label}
                </span>
              ),
            )}
            {hiddenAttrs.length > 0 ? (
              <button
                type="button"
                ref={attrsRef}
                className="result-more"
                aria-expanded={openMore?.kind === 'attrs'}
                title={'显示全部 ' + attributeChips.length + ' 个属性'}
                onClick={() => toggleMore('attrs', attrsRef)}
              >
                {'+' + hiddenAttrs.length}
              </button>
            ) : null}
          </div>
          <div className="result-tags">
            {shownTags.map((tag) => (
              <span className="result-tag" key={tag.key}>
                {'#' + tag.name}
              </span>
            ))}
            {moreTags > 0 ? (
              <button
                type="button"
                ref={tagsRef}
                className="result-more"
                aria-expanded={openMore?.kind === 'tags'}
                title={'显示全部 ' + skill.public_tags.length + ' 个公开标签'}
                onClick={() => toggleMore('tags', tagsRef)}
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
      {openMore === null
        ? null
        : createPortal(
            <div
              className="tags-pop"
              role="dialog"
              aria-label={openMore.kind === 'tags' ? '全部公开标签' : '全部属性'}
              style={{ left: openMore.left, top: openMore.top }}
            >
              {openMore.kind === 'tags' ? (
                <>
                  <p className="tags-pop-head">{'公开标签 · ' + skill.public_tags.length}</p>
                  <div className="tags-pop-list">
                    {skill.public_tags.map((tag) => (
                      <span className="tags-pop-tag" key={tag.key}>
                        {'#' + tag.name}
                      </span>
                    ))}
                  </div>
                </>
              ) : (
                <>
                  <p className="tags-pop-head">{'属性 · ' + attributeChips.length}</p>
                  <div className="tags-pop-list">
                    {attributeChips.map((chip) =>
                      chip.filterable ? (
                        <a
                          className="tags-pop-attr"
                          key={chip.id}
                          href={chip.href}
                          data-group={chip.group}
                        >
                          <BlockerIcon kind={chip.kind} />
                          {chip.label}
                        </a>
                      ) : (
                        <span className="tags-pop-attr" key={chip.id} data-group={chip.group}>
                          <BlockerIcon kind={chip.kind} />
                          {chip.label}
                        </span>
                      ),
                    )}
                  </div>
                </>
              )}
            </div>,
            document.body,
          )}
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
