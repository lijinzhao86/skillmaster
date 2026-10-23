import type { MyTagUsage } from '../../api/types'
import { MAX_TAG_DESCRIPTION, MAX_TAG_NAME, slicePoints } from '../../tagRules'

/**
 * One definition: name, the sentence beside it, the count, and delete.
 *
 * The name and the description are edited in place — a click turns the word into an input at its
 * own position, Enter saves, Escape or leaving cancels, and a refusal lands under that row. The
 * count is a link into the library filtered by this tag, so it says where it leads.
 */
export default function TagDefinitionRow({
  tag,
  editing,
  editName,
  editError,
  describing,
  busy,
  descriptionDraft,
  dormantOnly,
  onBeginRename,
  onEndRename,
  onRename,
  onCommitRename,
  onBeginDescription,
  onEndDescription,
  onDescription,
  onCommitDescription,
  onDelete,
}: {
  tag: MyTagUsage
  editing: boolean
  editName: string
  editError: string
  describing: boolean
  /** This row's PATCH is in flight: the input takes no further edits until it lands. */
  busy: boolean
  descriptionDraft: string
  dormantOnly: boolean
  onBeginRename: () => void
  onEndRename: () => void
  onRename: (value: string) => void
  onCommitRename: () => void
  onBeginDescription: () => void
  onEndDescription: () => void
  onDescription: (value: string) => void
  onCommitDescription: () => void
  onDelete: () => void
}) {
  const description = tag.description ?? ''
  return (
    <li className="skill tag-row" data-tag={tag.id}>
      <div className="skill-line">
        {editing ? (
          <input
            id="tagName"
            className="tag-name-input"
            type="text"
            autoFocus
            autoComplete="off"
            spellCheck={false}
            aria-label="标签的名字"
            value={editName}
            readOnly={busy}
            aria-busy={busy}
            onChange={(event) => onRename(slicePoints(event.target.value, MAX_TAG_NAME))}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                onCommitRename()
              } else if (event.key === 'Escape') {
                event.preventDefault()
                onEndRename()
              }
            }}
            onBlur={onEndRename}
          />
        ) : (
          <button type="button" className="tag-name is-editable" title="点一下改名" onClick={onBeginRename}>
            {tag.name}
          </button>
        )}

        {editing ? null : describing ? (
          <input
            id="tagDescription"
            className="tag-desc-input"
            type="text"
            autoFocus
            autoComplete="off"
            spellCheck={false}
            placeholder="加一句说明"
            aria-label={'标签的说明（选填，最多 ' + MAX_TAG_DESCRIPTION + ' 个字符）'}
            value={descriptionDraft}
            readOnly={busy}
            aria-busy={busy}
            onChange={(event) => onDescription(slicePoints(event.target.value, MAX_TAG_DESCRIPTION))}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                onCommitDescription()
              } else if (event.key === 'Escape') {
                event.preventDefault()
                onEndDescription()
              }
            }}
            onBlur={onEndDescription}
          />
        ) : (
          <button
            type="button"
            className={'tag-desc' + (description === '' ? ' is-empty' : '')}
            title="点一下写说明"
            onClick={onBeginDescription}
          >
            {description === '' ? '加一句说明' : description}
          </button>
        )}

        <a className="tag-count" href={`/?my-tag=${encodeURIComponent(tag.id)}`}>
          {'用在 ' + (dormantOnly ? 0 : tag.used_in) + ' 个 skill 上'}
        </a>
        <span className="tag-acts">
          <button type="button" className="link-button is-danger" onClick={onDelete}>
            删除
          </button>
        </span>
      </div>

      {editError !== '' ? (
        <p className="tag-edit-error" role="alert">
          {editError}
        </p>
      ) : null}
    </li>
  )
}
