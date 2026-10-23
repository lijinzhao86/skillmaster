import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'

/**
 * The one confirmation sheet (design's `.confirm` / `#dangerConfirm`).
 *
 * Every action that changes a version or the skill's service goes through it: one title saying what
 * the step is, one body saying what it takes away, **取消 as the default focus**, Esc equal to 取消,
 * and the focus returned to whatever opened it (the caller owns that last part). Waiting, success
 * and failure all happen in this one sheet: the confirm button becomes 「正在…」, both buttons go
 * unavailable, and Esc cannot close a request that is already in flight; a failure leaves the reason
 * under the body and puts the buttons back for a retry.
 *
 * The destructive *tone* is not the same as "this needs confirming": discard is outlined and its
 * confirm button is not red (ADR 0060), while 下架 and 下线 are red. `danger` carries that.
 */
export interface Props {
  open: boolean
  title: string
  body: string
  confirmLabel: string
  /** The safe answer's label. 取消 everywhere except the leave sheet, which says 继续编辑. */
  cancelLabel?: string
  pendingLabel?: string
  /** Red confirm button. `false` for rollback, publish and discard (ADR 0060). */
  danger?: boolean
  /** A field's label; when present the sheet asks for text as well as an answer. */
  field?: string
  /** The field may be left empty (a delist reason does not have to be written). */
  optionalField?: boolean
  placeholder?: string
  pending?: boolean
  error?: string | null
  onConfirm: (value: string) => void
  onCancel: () => void
}

export default function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel,
  cancelLabel = '取消',
  pendingLabel = '正在处理…',
  danger = true,
  field,
  optionalField = false,
  placeholder = '',
  pending = false,
  error = null,
  onConfirm,
  onCancel,
}: Props) {
  const cancelButton = useRef<HTMLButtonElement | null>(null)
  const [value, setValue] = useState('')
  const firstRun = useRef(true)

  const acceptDisabled =
    pending || (field !== undefined && !optionalField && value.trim() === '')

  useEffect(() => {
    if (firstRun.current) {
      firstRun.current = false
      return
    }
    if (!open) {
      return
    }
    setValue('')
    // 取消 is the default focus for every one of these, including a sheet that also asks for a
    // reason: the safe answer is the one under the fingers.
    const frame = requestAnimationFrame(() => cancelButton.current?.focus())
    return () => {
      cancelAnimationFrame(frame)
    }
  }, [open])

  function onKeydown(event: ReactKeyboardEvent<HTMLDivElement>): void {
    // A request already sent cannot be closed from under its own result.
    if (event.key !== 'Escape' || pending) {
      return
    }
    event.preventDefault()
    onCancel()
  }

  function confirm(): void {
    if (acceptDisabled) {
      return
    }
    onConfirm(field === undefined ? '' : value.trim())
  }

  if (!open) {
    return null
  }

  return (
    <div className="confirm" onKeyDown={onKeydown}>
      <div
        className="confirm-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirmTitle"
        aria-describedby="confirmBody"
      >
        <h2 id="confirmTitle" className="confirm-title">
          {title}
        </h2>
        <p id="confirmBody" className="confirm-body">
          {body}
        </p>
        {error !== null ? (
          <p className="confirm-error" role="alert">
            {error}
          </p>
        ) : null}
        {field !== undefined ? (
          <label className="confirm-field">
            <span className="confirm-label">{field}</span>
            <textarea
              className="confirm-input"
              rows={3}
              placeholder={placeholder}
              disabled={pending}
              value={value}
              onChange={(event) => setValue(event.target.value)}
            />
          </label>
        ) : null}
        <div className="confirm-acts">
          <button
            ref={cancelButton}
            type="button"
            className="button is-quiet"
            disabled={pending}
            onClick={onCancel}
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            className={danger ? 'button is-danger' : 'button'}
            disabled={acceptDisabled}
            onClick={confirm}
          >
            {pending ? pendingLabel : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
