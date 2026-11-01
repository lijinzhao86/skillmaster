import type { RefObject } from 'react'
import type { AuthoredSkill, SkillTagGroups as SkillTagGroupsData } from '../../api/types'
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
        <span className="avatar" aria-hidden="true">
          {skill.namespace.slug.slice(0, 1)}
        </span>
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
          {library !== null && library.source === null && library.joinable ? (
            <button
              type="button"
              className="button"
              id="joinSkill"
              disabled={joining}
              onClick={() => void onJoin()}
            >
              {joining ? '加入中…' : '加入'}
            </button>
          ) : null}
          {library !== null &&
          library.active &&
          library.source === 'joined' &&
          library.can_remove ? (
            <button
              type="button"
              className="button is-quiet"
              id="removeSkill"
              ref={removeButton}
              onClick={onAskRemove}
            >
              移除
            </button>
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
