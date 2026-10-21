/**
 * **公开 · 浏览 的确认。** It is not a delete, but handing it over means downloads and copies that
 * cannot be taken back — so the cost is said before the second click.
 *
 * The radio that asked the question is put back while the sheet is up: the answer has not been
 * given yet, and a control that has already moved is a decision taken for the reader.
 */

import { useEffect, useLayoutEffect, useRef } from 'react'
import { dialogs, useDialogs } from './store'

export default function PublicBrowseConfirm() {
  const { publicBrowse } = useDialogs()
  const open = publicBrowse !== null
  const accept = useRef<HTMLButtonElement>(null)

  useLayoutEffect(() => {
    if (open) accept.current?.focus()
  }, [open])

  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      dialogs.closePublicBrowse()
      publicBrowse?.returnFocus?.focus()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, publicBrowse])

  return (
    <div className="confirm" id="publicBrowseConfirm" hidden={!open}>
      <div className="confirm-sheet" role="dialog" aria-modal="true" aria-labelledby="publicBrowseConfirmTitle">
        <h2 className="confirm-title" id="publicBrowseConfirmTitle">改为「公开 · 浏览」</h2>
        <p className="confirm-body">公开之后，任何人都可以查看全部文件、下载并复制这个 skill；已经发生的下载与复制无法收回。</p>
        <div className="confirm-acts">
          <button
            type="button"
            className="button is-quiet"
            id="publicBrowseCancel"
            onClick={() => {
              dialogs.closePublicBrowse()
              publicBrowse?.returnFocus?.focus()
            }}
          >
            取消
          </button>
          <button
            type="button"
            className="button"
            id="publicBrowseAccept"
            ref={accept}
            onClick={() => {
              const confirm = publicBrowse?.onConfirm
              dialogs.closePublicBrowse()
              confirm?.()
            }}
          >
            确认公开
          </button>
        </div>
      </div>
    </div>
  )
}
