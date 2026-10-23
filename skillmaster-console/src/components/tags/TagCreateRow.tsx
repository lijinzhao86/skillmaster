import type { RefObject } from 'react'
import { MAX_TAG_DESCRIPTION, MAX_TAG_NAME, normalizeTagName, slicePoints } from '../../tagRules'

/**
 * The create row: one small form on the list's first line — name, description, cancel, create.
 *
 * Enter still walks the whole line: in the name it moves to the description, in the description it
 * accepts. Focus leaving the block closes it; moving between the controls does not.
 */
export default function TagCreateRow({
  name,
  description,
  error,
  nameInput,
  descriptionInput,
  onName,
  onDescription,
  onCancel,
  onAccept,
  onEnd,
}: {
  name: string
  description: string
  error: string
  nameInput: RefObject<HTMLInputElement | null>
  descriptionInput: RefObject<HTMLInputElement | null>
  onName: (value: string) => void
  onDescription: (value: string) => void
  onCancel: () => void
  onAccept: () => void
  /** Focus has left the whole block. */
  onEnd: () => void
}) {
  return (
    <li
      className="skill tag-row is-new"
      data-tag="new"
      onBlur={(event) => {
        if (event.currentTarget.contains(event.relatedTarget as Node | null)) {
          return
        }
        onEnd()
      }}
    >
      <div className="tag-create">
        <input
          id="newTagName"
          className="control tag-create-name"
          type="text"
          autoFocus
          autoComplete="off"
          spellCheck={false}
          placeholder="新标签的名字"
          aria-label="新标签的名字"
          ref={nameInput}
          value={name}
          onChange={(event) => onName(slicePoints(event.target.value, MAX_TAG_NAME))}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              if (normalizeTagName(name) === '') {
                onAccept()
              } else {
                descriptionInput.current?.focus()
              }
            } else if (event.key === 'Escape') {
              event.preventDefault()
              onCancel()
            }
          }}
        />
        <input
          id="newTagDescription"
          className="control tag-create-desc"
          type="text"
          autoComplete="off"
          spellCheck={false}
          placeholder="给 agent 看的一句：什么时候该贴它（选填）"
          aria-label={'新标签的说明（选填，最多 ' + MAX_TAG_DESCRIPTION + ' 个字符）'}
          ref={descriptionInput}
          value={description}
          onChange={(event) => onDescription(slicePoints(event.target.value, MAX_TAG_DESCRIPTION))}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              onAccept()
            } else if (event.key === 'Escape') {
              event.preventDefault()
              onCancel()
            }
          }}
        />
        <span className="tag-create-acts">
          <button type="button" className="button is-quiet" id="tagCreateCancel" onClick={onCancel}>
            取消
          </button>
          <button
            type="button"
            className="button"
            id="tagCreateAccept"
            disabled={normalizeTagName(name) === ''}
            onClick={onAccept}
          >
            新建
          </button>
        </span>
      </div>
      {error !== '' ? (
        <p className="tag-edit-error" role="alert">
          {error}
        </p>
      ) : null}
    </li>
  )
}
