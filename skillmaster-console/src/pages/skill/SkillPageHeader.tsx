import type { RefObject } from 'react'
import type { AuthoredSkill, SkillTagGroups as SkillTagGroupsData } from '../../api/types'
import CommandMenu, { type CommandMenuHandle } from '../../components/CommandMenu'
import SkillAvatar from '../../components/SkillAvatar'
import { TAB_ICONS, type Subpage, type Tab } from './skillRoute'

/**
 * The page head: who and what, the 加入 / 移除 matrix, and the four section tabs.
 *
 * Rung-gated exactly as the page is: the visibility badge and 设置 are drawn only where they can be
 * acted on (`够不到的东西不画`), and the 加入 / 移除 pair follows the server's four `library` facts.
 */
export interface SkillPageHeaderProps {
  skill: AuthoredSkill
  crumbs: Array<{ name: string; href: string }>
  canAdmin: boolean
  tabs: Array<{ key: Tab; label: string }>
  tab: Tab
  versionCount: number
  library: SkillTagGroupsData['library'] | null
  joining: boolean
  /** The one line of social evidence, or null when this skill has nothing to show. */
  evidence: string | null
  /** The reserved favourite, drawn only for a public skill the caller can relate to. */
  showFavorite: boolean
  /** v54: whether 「⋯ › 复制」 is drawn — browse or above, not the owner, something to copy. */
  canCopy: boolean
  onCopy: () => void
  /** The 「⋯」 handle: closing the copy sheet puts focus back on the trigger it came from. */
  moreRef: RefObject<CommandMenuHandle | null>
  onJoin: () => Promise<void>
  removeButton: RefObject<HTMLButtonElement | null>
  onAskRemove: () => void
  homeHref: string
  tabHref: (key: Tab) => string
  subHref: (key: Subpage) => string
}

export default function SkillPageHeader({
  skill,
  crumbs,
  canAdmin,
  tabs,
  tab,
  versionCount,
  library,
  joining,
  evidence,
  showFavorite,
  canCopy,
  onCopy,
  moreRef,
  onJoin,
  removeButton,
  onAskRemove,
  homeHref,
  tabHref,
  subHref,
}: SkillPageHeaderProps) {
  return (
    <header className="skill-head">
      {/* Who, and what: the same three things a repository's first line carries — the account
          mark, the name, and whether anybody else can read it. */}
      <div className="skill-ident">
        <SkillAvatar name={skill.name} />
        <div className="skill-who">
          <div className="skill-name-row">
            <h1>
              <span className="ns">{`${skill.namespace.slug} /`}</span>
              <a className="name" href={homeHref}>
                {skill.name}
              </a>
              {crumbs.map((segment) => (
                <span key={segment.href} className="crumb">
                  {' / '}
                  <a href={segment.href}>{segment.name}</a>
                </span>
              ))}
            </h1>
            {/* **Drawn only where it can be acted on.** The badge leads to the page that owns
                the fact, and that page is `admin`'s — so for every rung below it the whole mark
                is absent, not greyed (design: 够不到的东西不画). */}
            {canAdmin ? (
              <a className="chip is-plain" href={subHref('general')}>
                {skill.visibility === 'private' ? '私有' : '公开'}
              </a>
            ) : null}
            {/* **下线是整个 skill 的状态，不是某一版的。** It sits beside the visibility mark
                because both answer 「这个地址现在是什么」; it is not a version mark. */}
            {skill.offline_at != null ? <span className="chip is-offline">已下线</span> : null}
          </div>
          {/* `title` is an optional field: the server fills it with `name` when the author wrote
              none, so "is there a title" is `title !== name` rather than "is it empty". */}
          {skill.title !== skill.name ? <p className="skill-title">{skill.title}</p> : null}
        </div>
        {/* **加入 / 移除 sit where a repository's Star sits.** They are my relationship to the
            skill, not a property of it, which is why the ladder's 申请 stays below the prose.
            The matrix is prd.md #10: not in the library → 加入; mine by joining → 移除;
            created / shared with me → neither; visible but not joinable → neither, with a
            reason in the rail; dormant → neither (the entry is not in the list). */}
        <div className="skill-ident-actions">
          {/* **Evidence → favourite → main button** (2026-11-05). Evidence is the data — public and
              with a live version — so an author's own public skill shows it too; the favourite is
              still reserved, and the main button is the page's only filled one. */}
          {evidence !== null ? (
            <p className="skill-evidence" id="skillEvidence">
              {evidence}
            </p>
          ) : null}
          {showFavorite ? (
            <span className="skill-favorite" title="收藏（功能暂未开放）">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M12 20s-7-4.35-7-10a4 4 0 0 1 7-2.65A4 4 0 0 1 19 10c0 5.65-7 10-7 10Z"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinejoin="round"
                />
              </svg>
              <span className="skill-favorite-count">—</span>
              <span className="visually-hidden">收藏（功能暂未开放）</span>
            </span>
          ) : null}
          {library !== null && library.source === null && library.joinable ? (
            <button
              type="button"
              className="button skill-join"
              id="joinSkill"
              disabled={joining}
              onClick={() => void onJoin()}
              aria-label={joining ? '入库中' : '入库'}
            >
              {joining ? (
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
              ) : (
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z"
                    stroke="currentColor"
                    strokeWidth="1.8"
                    strokeLinejoin="round"
                  />
                </svg>
              )}
              {joining ? '入库中…' : '入库'}
            </button>
          ) : null}
          {library !== null &&
          library.active &&
          library.source === 'joined' &&
          library.can_remove ? (
            <details className="skill-joined">
              <summary className="button skill-join is-quiet is-joined">
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z"
                    fill="currentColor"
                  />
                </svg>
                已入库
                <svg className="skill-joined-caret" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M7 10l5 5 5-5"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              </summary>
              <div className="skill-joined-menu">
                <button
                  type="button"
                  id="removeSkill"
                  ref={removeButton}
                  aria-label="移出库"
                  onClick={onAskRemove}
                >
                  移出库
                </button>
              </div>
            </details>
          ) : null}
          {/* **复制收在「⋯」里**（2026-11-09）: 入库 is the action the platform recommends, so it
              keeps the header's one filled button; copying is secondary and lives behind 更多. */}
          {canCopy ? (
            <CommandMenu
              controlRef={moreRef}
              groups={[[
                {
                  label: '复制',
                  icon: (
                    <svg className="menu-ico" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <rect
                        x="9"
                        y="9"
                        width="10"
                        height="10"
                        rx="2"
                        stroke="currentColor"
                        strokeWidth="1.8"
                      />
                      <path
                        d="M5.5 15H5a1.5 1.5 0 0 1-1.5-1.5v-8A1.5 1.5 0 0 1 5 4h8a1.5 1.5 0 0 1 1.5 1.5V6"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                      />
                    </svg>
                  ),
                  onSelect: onCopy,
                },
              ]]}
              menuLabel="这个 skill 的更多操作"
              resize="reposition"
              variant="actions"
            >
              {({ open, toggle }) => (
                <button
                  type="button"
                  className="skill-more"
                  id="skillMore"
                  aria-label="更多操作"
                  aria-haspopup="menu"
                  aria-expanded={open}
                  title="更多操作"
                  onClick={toggle}
                >
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <circle cx="5.5" cy="12" r="1.4" fill="currentColor" />
                    <circle cx="12" cy="12" r="1.4" fill="currentColor" />
                    <circle cx="18.5" cy="12" r="1.4" fill="currentColor" />
                  </svg>
                </button>
              )}
            </CommandMenu>
          ) : null}
        </div>
      </div>

      <nav className="skill-tabs" aria-label="这个 skill 的分区">
        {tabs.map((entry) => (
          <a
            key={entry.key}
            className={tab === entry.key ? 'skill-tab is-current' : 'skill-tab'}
            aria-current={tab === entry.key ? 'page' : undefined}
            href={tabHref(entry.key)}
          >
            <svg className="skill-tab-icon" aria-hidden="true">
              <use href={`#${TAB_ICONS[entry.key]}`} />
            </svg>
            <span className="skill-tab-label">{entry.label}</span>
            {entry.key === 'versions' && versionCount > 0 ? (
              <span className="skill-tab-count">{versionCount}</span>
            ) : null}
          </a>
        ))}
      </nav>
    </header>
  )
}
