import type { SkillCard } from '../../api/types'
import { STANDING_LABEL } from '../../libraryLabels'
import { formatTime } from '../../time'

/**
 * One library row: two lines, a source mark and a state mark.
 *
 * A source is outlined and a state is tinted — side by side the eye reads the state first and where
 * it came from second. The name is the only link; the author's own controls live on the skill page
 * it opens. A deleted row's name is text, because its address opens nothing now, and the one thing
 * that row can do is 恢复.
 */
export default function LibraryRow({
  skill,
  deleted = false,
  restoring = false,
  selected = false,
  mine = false,
  onRestore,
  onSelect,
}: {
  skill: SkillCard
  /** The deleted list: name as text, 已删除, and 恢复. */
  deleted?: boolean
  restoring?: boolean
  /** Whether its preview is the one open in the pane beside the list (v35). */
  selected?: boolean
  /** Whether this skill lives in the reader's own namespace — then the prefix is left off. */
  mine?: boolean
  onRestore?: () => void
  /** Opening the preview. Absent on a row that cannot be previewed. */
  onSelect?: () => void
}) {
  const href = `/skills/${encodeURIComponent(skill.namespace)}/${encodeURIComponent(skill.name)}`

  const status = (() => {
    if (deleted || skill.state === 'deleted') {
      return (
        <span className="chip is-plain">
          <i className="dot" />
          <span className="chip-text">已删除</span>
        </span>
      )
    }
    if (skill.state === 'offline') {
      return (
        <span className="chip is-offline">
          <i className="dot" />
          <span className="chip-text">已下线</span>
        </span>
      )
    }
    if (skill.state === 'draft') {
      return (
        <span className="chip is-draft">
          <i className="dot" />
          <span className="chip-text">草稿</span>
        </span>
      )
    }
    return (
      <span className="chip is-live">
        <i className="dot" />
        <span className="chip-text">
          {skill.version === null ? '已上线' : `已上线 @${skill.version}`}
        </span>
      </span>
    )
  })()

  return (
    <li
      className={'skill' + (selected ? ' is-selected' : '')}
      data-source={deleted ? undefined : (skill.source ?? undefined)}
      data-state={skill.state}
      /* **The row opens the preview; the name still goes to the skill page.** The name is the one
         link this row has always had, and its destination does not change. `tabIndex` / `role` are
         there so the row is reachable by keyboard at all: a clickable row that only a mouse can
         reach is a gate failure. That the name's link sits inside a `role="button"` is a real
         compromise — written down in design/README.md rather than hidden. */
      tabIndex={onSelect === undefined ? undefined : 0}
      role={onSelect === undefined ? undefined : 'button'}
      aria-expanded={onSelect === undefined ? undefined : selected}
      onClick={(event) => {
        if (onSelect === undefined) return
        if ((event.target as HTMLElement).closest('a, button')) return
        onSelect()
      }}
      onKeyDown={(event) => {
        if (onSelect === undefined) return
        if (event.target !== event.currentTarget) return
        if (event.key !== 'Enter' && event.key !== ' ') return
        event.preventDefault()
        onSelect()
      }}
    >
      <div className="skill-line">
        {/* **The row opens with the address**: the namespace, a step fainter, ahead of the name —
            the same `.ns` the skill page's title and the discovery card use. That is what tells two
            same-named skills apart: `dev / pdf-tools` and `yuhan / pdf-tools` are different things,
            and the source chip cannot say so (a "shared with me" row can live in your own
            namespace). */}
        {deleted ? (
          <span className="skill-name" title={`${skill.namespace} / ${skill.name}`}>
            {mine ? null : (
              <>
                <span className="ns">{skill.namespace} /</span>{' '}
              </>
            )}
            {skill.name}
          </span>
        ) : (
          <a className="skill-name" href={href} title={`${skill.namespace} / ${skill.name}`}>
            {mine ? null : (
              <>
                <span className="ns">{skill.namespace} /</span>{' '}
              </>
            )}
            {skill.name}
          </a>
        )}
        {/* **The chip says the rung, not where it came from (v35, the user's call).** Once the
            namespace is on the row, 「我创建的」 said the same thing as 「no prefix」; the rung says
            something new — what you can actually do. The owner's row draws no chip, for the same
            reason: owning it is the absence of a prefix. */}
        {!deleted && skill.standing !== null && skill.standing !== undefined ? (
          <span className="chip is-source">
            <span className="chip-text">{STANDING_LABEL[skill.standing]}</span>
          </span>
        ) : null}
        {deleted ? status : null}
        <span className="spacer" />
        {deleted ? (
          <button
            type="button"
            className="link-button restore"
            disabled={restoring}
            onClick={onRestore}
          >
            恢复
          </button>
        ) : (
          status
        )}
      </div>
      <div className="skill-line skill-sub">
        {/* 一行截断；完整的那一段在 `title` 里，悬停就出来。主路是点名字进 skill 页的「信息 ›
            描述」，那里画的是全文（v35）。 */}
        <p className="skill-title" title={skill.description}>{skill.description}</p>
        <span className="spacer" />
        {deleted
          ? skill.deleted_at !== null
            ? <span className="skill-updated">{'删除于 ' + formatTime(skill.deleted_at)}</span>
            : null
          : skill.updated_at !== null
            ? <span className="skill-updated">{'更新于 ' + formatTime(skill.updated_at)}</span>
            : null}
      </div>
    </li>
  )
}
