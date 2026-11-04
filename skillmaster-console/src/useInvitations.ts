import { useState } from 'react'
import { failureMessage } from './api/errors'
import { listInvitations } from './api/invitations'
import type { SkillInvitation } from './api/types'

/**
 * The invitations on one skill, pending and expired. A failed read is not "nobody is waiting": it
 * reports through the caller's banner, because hiding the block would hide a row the owner may
 * still need to revoke.
 */
export function useInvitations(
  namespace: string,
  name: string,
  banner: (message: string | null) => void,
): { invitations: SkillInvitation[]; reload: () => Promise<void> } {
  const [invitations, setInvitations] = useState<SkillInvitation[]>([])

  async function reload(): Promise<void> {
    const result = await listInvitations(namespace, name)
    if (!result.ok) {
      setInvitations([])
      banner(failureMessage(result))
      return
    }
    setInvitations(result.data.items)
  }

  return { invitations, reload }
}
