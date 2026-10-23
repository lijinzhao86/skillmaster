import type { SkillCard } from '../../api/types'
import { SOURCE_LABEL } from '../../libraryLabels'
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
  onRestore,
}: {
  skill: SkillCard
  /** The deleted list: name as text, 已删除, and 恢复. */
  deleted?: boolean
  restoring?: boolean
  onRestore?: () => void
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
      className="skill"
      data-source={deleted ? undefined : (skill.source ?? undefined)}
      data-state={skill.state}
    >
      <div className="skill-line">
        {deleted ? (
          <span className="skill-name">{skill.name}</span>
        ) : (
          <a className="skill-name" href={href}>
            {skill.name}
          </a>
        )}
        {!deleted && skill.source !== null ? (
          <span className="chip is-source">
            <span className="chip-text">{SOURCE_LABEL[skill.source]}</span>
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
        <p className="skill-title">{skill.description}</p>
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
