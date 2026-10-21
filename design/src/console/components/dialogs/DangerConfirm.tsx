/**
 * **危险操作一律弹窗确认（2026-10-19 定）。**
 *
 * It answers one question: what does this step take away. Cancel is the default focus; an action
 * that needs a reason gets the field in the sheet, and confirm stays disabled while it is empty;
 * Escape and Cancel are the same answer.
 *
 * **Waiting, success and failure all happen in this one sheet.** After confirm the button says what
 * it is doing, both answers are disabled and Escape does not close it; success closes it and the
 * page updates in place; failure writes the reason under the body, restores the buttons, and the
 * same sheet retries.
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { replace } from '../../address'
import { dialogs, useDialogs } from './store'

export default function DangerConfirm() {
  const { danger } = useDialogs()
  const open = danger !== null
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const input = useRef<HTMLTextAreaElement>(null)
  const accept = useRef<HTMLButtonElement>(null)
  const cancel = useRef<HTMLButtonElement>(null)

  const field = danger?.field
  const required = !!field && !danger?.optionalField
  const ready = !required || reason.trim() !== ''

  /* **Cancel is the default focus, and it is there before the click's task ends.** A `setTimeout(0)`
     here loses a race the old code never had: the press that opened the sheet could be followed by
     an assertion in the same task, and the answer under the reader's fingers is part of the sheet's
     contract (the same rule `askBefore` states). A layout effect runs before the browser paints. */
  useLayoutEffect(() => {
    if (!open) return
    setReason('')
    setError('')
    setPending(false)
    ;(field ? input.current : cancel.current)?.focus()
  }, [open, danger, field])

  /* **A failed request leaves the reason under the body and the button ready to try again.** The
     focus goes back to it once it is enabled again — see the failure branch. */
  useEffect(() => {
    if (error !== '' && !pending) accept.current?.focus()
  }, [error, pending])

  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      /* 请求已经发出，Esc 不能把结果关在外面。 */
      if (event.key !== 'Escape' || pending) return
      dialogs.closeDanger()
      danger?.returnFocus?.focus()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, pending, danger])

  const confirm = () => {
    if (!danger || pending) return
    const value = field ? reason.trim() : ''
    if (required && value === '') return
    setError('')
    setPending(true)
    /* 演示件没有服务端：地址带 `fail=1` 时**下一次**请求失败，好让失败那一态能被看见；失败之后
       这一位自己用掉，原地重试就能成功。 */
    const current = new URLSearchParams(window.location.search)
    const failed = current.get('fail') === '1'
    if (failed) {
      current.delete('fail')
      /* Through `replace`, not `history.replaceState`: the page builds its addresses from the
         snapshot `useAddress` keeps, and a write nobody announced would leave that snapshot saying
         `fail=1` for the rest of the visit. */
      replace('?' + current.toString())
    }
    window.setTimeout(() => {
      if (failed) {
        /* The focus lands after the render that re-enables the button: a disabled control cannot
           take focus, and the answer that failed is meant to be under the fingers for a retry. */
        setPending(false)
        setError(danger.failMessage || '没有完成：服务器暂时没有响应，请重试。')
        return
      }
      setPending(false)
      dialogs.closeDanger()
      danger.onConfirm(value)
      danger.focusAfter?.focus()
    }, 600)
  }

  return (
    <div className="confirm" id="dangerConfirm" hidden={!open}>
      <div className="confirm-sheet" role="dialog" aria-modal="true" aria-labelledby="dangerConfirmTitle" aria-describedby="dangerConfirmBody">
        <h2 className="confirm-title" id="dangerConfirmTitle">{danger?.title ?? ''}</h2>
        <p className="confirm-body" id="dangerConfirmBody">{danger?.body ?? ''}</p>
        <p className="confirm-error" id="dangerConfirmError" role="alert" hidden={error === ''}>{error}</p>
        <label className="confirm-field" id="dangerConfirmField" hidden={!field}>
          <span className="confirm-label" id="dangerConfirmFieldLabel">{field ?? ''}</span>
          <textarea
            className="confirm-input"
            id="dangerConfirmInput"
            rows={3}
            ref={input}
            value={reason}
            placeholder={danger?.placeholder ?? ''}
            onChange={(event) => setReason(event.target.value)}
          ></textarea>
        </label>
        <div className="confirm-acts">
          <button
            type="button"
            className="button is-quiet"
            id="dangerConfirmCancel"
            ref={cancel}
            disabled={pending}
            onClick={() => {
              dialogs.closeDanger()
              danger?.returnFocus?.focus()
            }}
          >
            {danger?.cancelLabel ?? '取消'}
          </button>
          <button
            type="button"
            className={danger?.danger === false ? 'button' : 'button is-danger'}
            id="dangerConfirmAccept"
            ref={accept}
            disabled={pending || !ready}
            onClick={confirm}
          >
            {pending ? (danger?.pendingLabel ?? '正在处理…') : (danger?.confirmLabel ?? '')}
          </button>
        </div>
      </div>
    </div>
  )
}
