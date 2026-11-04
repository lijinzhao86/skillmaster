import { failureMessage } from './api/errors'
import { changeGrantRole, grantSkill, revokeInvitation } from './api/invitations'
import type { Rung, SkillGrant, SkillInvitation } from './api/types'
import { rungIndex, rungLabel } from './rungs'

/**
 * The two collaborator writes that need more than one API call or more than one reload (v45).
 *
 * They live outside the pane so the component stays inside its size limit, and so the rule they
 * encode has one home: lowering is immediate, raising is an invitation, withdrawing an invitation
 * only touches the invitation.
 */
export interface CollaboratorHooks {
  banner: (message: string | null) => void
  reloadGrants: () => Promise<void>
  reloadInvitations: () => Promise<void>
  refreshPopulation: () => Promise<void>
}

export async function changeCollaboratorRole(
  namespace: string,
  name: string,
  grant: SkillGrant,
  role: Rung,
  hooks: CollaboratorHooks,
): Promise<void> {
  hooks.banner(null)
  const from = rungLabel(grant.role)
  const to = rungLabel(role)

  // Lowering takes nothing new, so it is immediate; raising is an invitation, because it is new
  // access and the person gets to answer.
  if (rungIndex(role) < rungIndex(grant.role)) {
    const result = await changeGrantRole(namespace, name, grant.handle, role)
    if (!result.ok) {
      hooks.banner(failureMessage(result))
      return
    }
    if (!result.data.changed) {
      hooks.banner(`${grant.handle} 已经是「${to}」。`)
      return
    }
    hooks.banner(`已把 ${grant.handle} 的档位从「${from}」改为「${to}」。`)
    await Promise.all([hooks.reloadGrants(), hooks.refreshPopulation()])
    return
  }
  if (rungIndex(role) === rungIndex(grant.role)) {
    return
  }
  const result = await grantSkill(namespace, name, grant.handle, role)
  if (!result.ok) {
    hooks.banner(failureMessage(result))
    return
  }
  if (result.data.already_granted) {
    hooks.banner(`${grant.handle} 已经有这一档或更高，没有发出共享邀请。`)
    return
  }
  hooks.banner(`已向 ${grant.handle} 发出「${to}」的共享邀请，等待对方接受。`)
  await hooks.reloadInvitations()
}

export async function withdrawCollaboratorInvitation(
  namespace: string,
  name: string,
  invitation: SkillInvitation,
  hooks: Pick<CollaboratorHooks, 'banner' | 'reloadInvitations'>,
): Promise<void> {
  hooks.banner(null)
  const result = await revokeInvitation(namespace, name, invitation.handle)
  if (!result.ok) {
    hooks.banner(failureMessage(result))
    return
  }
  await hooks.reloadInvitations()
}
