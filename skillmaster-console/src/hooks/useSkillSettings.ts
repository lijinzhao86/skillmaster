import { useRef, useState } from 'react'
import { go } from '../address'
import { failureMessage } from '../api/errors'
import { deleteSkill, setSkillOffline, setVisibility } from '../api/skills'
import type { AuthoredSkill } from '../api/types'

/**
 * The settings sub-page's three writes and the questions asked before them: visibility, 下线 /
 * 恢复服务, and deletion.
 *
 * Each question is its own sheet with its own pending/error state, and cancelling is the default
 * focus. The server's reload after a successful write is deliberate — it is the only thing that
 * knows what the pointer, the statuses and the derived counts became.
 */
export interface SkillSettingsProps {
  namespace: string
  name: string
  skill: AuthoredSkill | null
  setBanner: (message: string | null) => void
}

export function useSkillSettings({ namespace, name, skill, setBanner }: SkillSettingsProps) {
  const visibilityChoice = (() => {
    if (skill === null || skill.visibility === 'private') {
      return 'private'
    }
    return skill.public_rung === 'browse' ? 'public-browse' : 'public-invoke'
  })()

  const visibilityPane = useRef<HTMLElement | null>(null)

  /* 公开 · 浏览 的确认. */
  const [publicBrowseOpen, setPublicBrowseOpen] = useState(false)
  const [publicBrowsePending, setPublicBrowsePending] = useState(false)
  const [publicBrowseError, setPublicBrowseError] = useState<string | null>(null)

  /** 取消 and Esc are the same answer, and it leaves the focus where the question came from. */
  function visibilityRadio(value: string): HTMLInputElement | null {
    return (
      visibilityPane.current?.querySelector<HTMLInputElement>(
        `input[name="skill-visibility"][value="${value}"]`,
      ) ?? null
    )
  }

  function cancelPublicBrowse(): void {
    setPublicBrowseOpen(false)
    setPublicBrowseError(null)
    requestAnimationFrame(() => visibilityRadio(visibilityChoice)?.focus())
  }

  async function acceptPublicBrowse(): Promise<void> {
    setPublicBrowsePending(true)
    setPublicBrowseError(null)
    const result = await setVisibility(namespace, name, 'public', 'browse')
    setPublicBrowsePending(false)
    if (!result.ok) {
      setPublicBrowseError(failureMessage(result))
      return
    }
    window.location.reload()
  }

  async function changeVisibility(choice: string): Promise<void> {
    if (choice === 'public-browse') {
      // **The choice does not move before the answer.** The browser has already checked the radio;
      // putting it back is what makes 取消 mean "nothing happened" — a controlled `checked` whose
      // value did not change is one React will not patch back on its own either (design's
      // `requestPublicBrowse`).
      const current = visibilityRadio(visibilityChoice)
      if (current !== null) {
        current.checked = true
      }
      setPublicBrowseOpen(true)
      return
    }
    const visibility = choice === 'private' ? 'private' : 'public'
    setBanner(null)
    const publicRung: 'invoke' | 'browse' | null =
      choice === 'private' ? null : choice === 'public-invoke' ? 'invoke' : 'browse'
    const result = await setVisibility(namespace, name, visibility, publicRung)
    if (!result.ok) {
      setBanner(failureMessage(result))
      return
    }
    window.location.reload()
  }

  /* 设置 › 危险操作. */

  const offline = skill != null && skill.offline_at != null

  const [offlineOpen, setOfflineOpen] = useState(false)
  const [offlinePending, setOfflinePending] = useState(false)
  const [offlineError, setOfflineError] = useState<string | null>(null)
  const offlineButton = useRef<HTMLButtonElement | null>(null)

  function askOffline(): void {
    setOfflineError(null)
    setOfflineOpen(true)
  }

  async function confirmOffline(): Promise<void> {
    setOfflinePending(true)
    setOfflineError(null)
    const result = await setSkillOffline(namespace, name, !offline)
    setOfflinePending(false)
    if (!result.ok) {
      setOfflineError(failureMessage(result))
      return
    }
    window.location.reload()
  }

  function cancelOffline(): void {
    setOfflineOpen(false)
    setOfflineError(null)
    requestAnimationFrame(() => offlineButton.current?.focus())
  }

  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deletePending, setDeletePending] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const deleteButton = useRef<HTMLButtonElement | null>(null)

  function askDelete(): void {
    setDeleteError(null)
    setDeleteOpen(true)
  }

  async function confirmDelete(): Promise<void> {
    setDeletePending(true)
    setDeleteError(null)
    const result = await deleteSkill(namespace, name)
    setDeletePending(false)
    if (!result.ok) {
      setDeleteError(failureMessage(result))
      return
    }
    go('/?show=deleted')
  }

  function cancelDelete(): void {
    setDeleteOpen(false)
    setDeleteError(null)
    requestAnimationFrame(() => deleteButton.current?.focus())
  }

  /* The second-level menu's count: "somebody is waiting" has exactly two homes without notifications. */
  const [requestCount, setRequestCount] = useState(0)

  return {
    visibilityChoice,
    visibilityPane,
    changeVisibility,
    publicBrowseOpen,
    publicBrowsePending,
    publicBrowseError,
    acceptPublicBrowse,
    cancelPublicBrowse,
    offline,
    offlineOpen,
    offlinePending,
    offlineError,
    offlineButton,
    askOffline,
    confirmOffline,
    cancelOffline,
    deleteOpen,
    deletePending,
    deleteError,
    deleteButton,
    askDelete,
    confirmDelete,
    cancelDelete,
    requestCount,
    setRequestCount,
  }
}
