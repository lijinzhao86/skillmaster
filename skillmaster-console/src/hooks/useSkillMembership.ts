import { useEffect, useRef, useState } from 'react'
import type { SkillTagGroups } from '../api/types'

/**
 * The page head's 加入 / 移除 matrix.
 *
 * The four facts are the server's (`skillTags.groups.library`); this hook owns only the action
 * state around them — the pending join, the remove sheet, and the focus the sheet hands back.
 */
export interface SkillMembershipTags {
  groups: SkillTagGroups | null
  join: () => Promise<boolean>
  leave: () => Promise<boolean>
}

export interface SkillMembershipProps {
  skillTags: SkillMembershipTags
  params: URLSearchParams
  /** `skill.offline_at != null`, read by the rail's sentence when joining is not possible. */
  offlineAt: string | null
}

export function useSkillMembership({ skillTags, params, offlineAt }: SkillMembershipProps) {
  const library = skillTags.groups?.library ?? null
  const [joining, setJoining] = useState(false)
  const [removeOpen, setRemoveOpen] = useState(false)
  const [removePending, setRemovePending] = useState(false)
  const removeButton = useRef<HTMLButtonElement | null>(null)

  const joinLibrary = async (): Promise<void> => {
    setJoining(true)
    await skillTags.join()
    setJoining(false)
  }

  function askRemove(): void {
    setRemoveOpen(true)
  }

  /* The design's reproduction address: `?remove=1` opens the question once the server has said
     the join reference can be removed. Without it a link to this state could not be shared, and a
     reload would lose the dialog. */
  const removeAsked = useRef(false)
  useEffect(() => {
    if (removeAsked.current || library === null) {
      return
    }
    if (params.get('remove') === '1' && library.active && library.source === 'joined'
        && library.can_remove) {
      removeAsked.current = true
      setRemoveOpen(true)
    }
  }, [library, params])

  async function confirmRemove(): Promise<void> {
    setRemovePending(true)
    const done = await skillTags.leave()
    setRemovePending(false)
    /* A refusal keeps the dialog's pending state from hanging; the page's banner carries the
       server's sentence, which is the same place every other write failure lands. */
    if (!done) {
      setRemoveOpen(false)
      return
    }
    setRemoveOpen(false)
  }

  function cancelRemove(): void {
    setRemoveOpen(false)
    requestAnimationFrame(() => removeButton.current?.focus())
  }

  /** A visible skill that cannot be joined says why; a dormant entry says why it is not listed. */
  const membershipNote =
    library === null
      ? ''
      : library.source === null
        ? library.joinable
          ? ''
          : offlineAt != null
            ? '已下线，暂时不能入库。'
            : '还没有上线的版本，暂时不能入库。'
        : library.active
          ? ''
          : '暂时不可见，恢复后会回到你的库。'

  return {
    library,
    joining,
    joinLibrary,
    removeOpen,
    removePending,
    removeButton,
    askRemove,
    confirmRemove,
    cancelRemove,
    membershipNote,
  }
}
