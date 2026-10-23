import { formatTime } from '../time'
import type { SkillCard } from '../api/types'
import { SOURCE_LABEL } from '../libraryLabels'

/**
 * One public result, drawn as a card.
 *
 * Both places on 发现 use it — the opening 「为你推荐」 stream and the list after a search or a
 * category — and they share it so the "already in your library" case cannot be missed in one of
 * them.
 *
 * The card says six things: who it is, what it does, when to remember it, which public tags its
 * author wrote, what version it is on and when it changed, and which shelf it sits on. No call
 * counts and no ranking: the product has no usage signal, so any such mark would be invented.
 *
 * **The action is one bookmark in the top-right corner.** Outlined when it is not in the library,
 * filled and accent-coloured once it is; pressing a filled one again takes it back out. The icon is
 * the control, which is why the card's foot does not repeat a 「加入 / 移除」 button.
 */
export type JoinState = 'idle' | 'busy' | 'failed'

export default function ResultCard({
  skill,
  joinState,
  joinError,
  onJoin,
  onRemove,
}: {
  skill: SkillCard
  joinState: JoinState
  /** The sentence the server (or the transport) gave; the card never invents a cause. */
  joinError?: string
  onJoin: () => void
  /** Only for the card joined on this screen: pressing the filled bookmark takes that back. */
  onRemove?: () => void
}) {
  const corner =
    skill.source === 'joined' && onRemove ? (
      <button
        type="button"
        className="result-join"
        data-state={joinState === 'busy' ? 'busy' : 'joined'}
        disabled={joinState === 'busy'}
        onClick={onRemove}
        title="移除"
      >
        <svg key="joined" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z"
            fill="currentColor"
          />
        </svg>
        <span className="visually-hidden">移除</span>
      </button>
    ) : skill.source !== null ? (
      <span
        className="result-inlibrary"
        aria-hidden="true"
        title={'已在库 · ' + SOURCE_LABEL[skill.source]}
      >
        <svg viewBox="0 0 24 24" fill="none">
          <path
            d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z"
            fill="currentColor"
          />
        </svg>
      </span>
    ) : joinState === 'busy' ? (
      <button type="button" className="result-join" data-state="busy" disabled title="加入中">
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
        <span className="visually-hidden">加入中…</span>
      </button>
    ) : (
      <button type="button" className="result-join" data-state="idle" onClick={onJoin} title="加入">
        <svg key="idle" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinejoin="round"
          />
        </svg>
        <span className="visually-hidden">加入</span>
      </button>
    )

  const version = skill.version === null ? '' : '@' + skill.version
  const updated = skill.updated_at === null ? '' : '更新于 ' + formatTime(skill.updated_at)
  const meta = [version, updated].filter((part) => part !== '').join('　·　')

  return (
    <li className="result" data-name={skill.name}>
      <div className="result-head">
        <a className="result-name" href={`/skills/${encodeURIComponent(skill.namespace)}/${encodeURIComponent(skill.name)}`}>
          <span className="ns">{skill.namespace} /</span> {skill.name}
        </a>
        {corner}
      </div>
      <p className="result-title">{skill.description}</p>
      {skill.when_to_use !== null && skill.when_to_use !== '' ? (
        <p className="result-when">{skill.when_to_use}</p>
      ) : null}
      {/* Public tags follow the "when to remember it" line: they are the author's words for search,
          which is why this result was found; the category stays at the foot, where the platform's
          one-shelf classification belongs. */}
      {skill.public_tags.length > 0 ? (
        <p className="result-public">
          {skill.public_tags.map((tag) => (
            <span key={tag.key}>{'#' + tag.name}</span>
          ))}
        </p>
      ) : null}
      {meta !== '' ? <p className="result-meta">{meta}</p> : null}
      <div className="result-foot">
        <div className="result-tags">
          {skill.category !== null ? (
            <span className="chip is-plain">{skill.category.name}</span>
          ) : null}
        </div>
        <div className="result-act">
          {skill.source !== null ? (
            joinState === 'failed' && onRemove !== undefined ? (
              // The bookmark went back and the server refused, or the request never arrived.
              // Saying "already in the library" would hide a removal that did not happen.
              <>
                <span className="result-error" role="alert">
                  {joinError ?? '没有移除，请重试。'}
                </span>
                <button type="button" className="button is-quiet" onClick={onRemove}>
                  重试
                </button>
              </>
            ) : (
              <span className="result-in" data-source={skill.source}>
                {'已在库　·　' + SOURCE_LABEL[skill.source]}
              </span>
            )
          ) : joinState === 'failed' ? (
            <>
              <span className="result-error" role="alert">
                {joinError ?? '没有加入，请重试。'}
              </span>
              <button type="button" className="button is-quiet" onClick={onJoin}>
                重试
              </button>
            </>
          ) : null}
        </div>
      </div>
    </li>
  )
}
