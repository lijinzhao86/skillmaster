import { useRef, useState } from 'react'
import { failureMessage } from '../api/errors'
import { grantSkill } from '../api/skills'
import type { Rung } from '../api/types'
import RungMenu from './RungMenu'
import type { RungOption } from './RungMenu'

/**
 * 设置 › 协作者 › 共享给 — the one-row form above the member list.
 *
 * Owns the input, the rung and the in-flight state. The banner is the parent's: a share is one of
 * several writes that share it, and the parent knows whether the sentence on screen came from a
 * read or a write.
 */
export interface CollaboratorShareRowProps {
  namespace: string
  name: string
  offered: RungOption[]
  /** Clear or set the block's banner when the share starts or fails. */
  onBanner: (message: string | null) => void
  /** Re-read the list and the population after a successful share. */
  onShared: () => Promise<void> | void
}

export default function CollaboratorShareRow({
  namespace,
  name,
  offered,
  onBanner,
  onShared,
}: CollaboratorShareRowProps) {
  const [handle, setHandle] = useState('')
  /** The input's value as the last render left it, for the `await` after the request. */
  const handleRef = useRef('')
  const [shareRole, setShareRole] = useState<Rung>((offered[0]?.name as Rung) ?? 'invoke')
  const [busy, setBusy] = useState(false)

  function updateHandle(value: string): void {
    handleRef.current = value
    setHandle(value)
  }

  async function share(): Promise<void> {
    const who = handle.trim()
    if (who === '') {
      return
    }
    onBanner(null)
    setBusy(true)
    const result = await grantSkill(namespace, name, who, shareRole)
    setBusy(false)
    if (!result.ok) {
      // The input stays editable while the request is in flight. If the typed name changed, a
      // detail about the name that was sent must not be said under the new one.
      const details =
        handleRef.current.trim() === who
          ? result.details
          : result.details.filter((detail) => detail.field !== 'handle')
      onBanner(failureMessage({ code: result.code, message: result.message, details }))
      return
    }
    updateHandle('')
    await onShared()
  }

  return (
    <section className="settings-block">
      <h3>共享给</h3>
      <div className="share-row">
        <input
          className="control"
          type="text"
          value={handle}
          autoComplete="off"
          spellCheck={false}
          placeholder="用户名"
          onChange={(event) => updateHandle(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              void share()
            }
          }}
        />
        <RungMenu
          value={shareRole}
          options={offered}
          label="权限"
          onChange={(value) => setShareRole(value as Rung)}
        />
        <button type="button" className="button" disabled={busy} onClick={() => void share()}>
          共享
        </button>
      </div>
      <p className="hint">仅所有者与「管理」可以修改他人的档位。</p>
    </section>
  )
}
