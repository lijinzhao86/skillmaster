import { request } from './client'
import { skillPath } from './skills'
import type { ApiResult, Rung } from './types'
import type { InvitationResult, SkillInvitations } from './notificationTypes'

/**
 * Sharing after v45: a share is an invitation the other person accepts, and an existing grant's
 * role can move down directly. Split from `skills.ts` to keep that file inside its size limit.
 */

export function grantSkill(
  namespace: string,
  name: string,
  handle: string,
  role: Rung,
): Promise<ApiResult<InvitationResult>> {
  return request<InvitationResult>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/invitations`,
    body: { handle, role },
  })
}

/**
 * Moves an existing grant down. Raising is refused by the server with `raise_needs_invitation`;
 * the console sends only a lower rung here.
 */
export function changeGrantRole(
  namespace: string,
  name: string,
  handle: string,
  role: Rung,
): Promise<ApiResult<{ handle: string; role: Rung; changed: boolean }>> {
  return request({
    method: 'POST',
    path: `${skillPath(namespace, name)}/grants/${encodeURIComponent(handle)}/role`,
    body: { role },
  })
}

/** Every invitation on this skill — pending and expired (v45). */
export function listInvitations(
  namespace: string,
  name: string,
): Promise<ApiResult<SkillInvitations>> {
  return request<SkillInvitations>({
    method: 'GET',
    path: `${skillPath(namespace, name)}/invitations`,
  })
}

/** Withdraws a waiting invitation. Idempotent on the server. */
export function revokeInvitation(
  namespace: string,
  name: string,
  handle: string,
): Promise<ApiResult<void>> {
  return request<void>({
    method: 'DELETE',
    path: `${skillPath(namespace, name)}/invitations/${encodeURIComponent(handle)}`,
  })
}
