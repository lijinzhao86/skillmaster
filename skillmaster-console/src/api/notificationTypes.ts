import type { Rung } from './types'

/**
 * The notification floor's wire types (v45). Split from `types.ts` when the file reached its size
 * limit; `types.ts` re-exports them, so every existing import keeps working.
 */

/**
 * A share waiting to be accepted (v45, ADR 0138). It is not a grant: before acceptance the invitee
 * has exactly what they had before, which is why this is a separate list.
 */
export interface SkillInvitation {
  handle: string
  role: Rung
  created_at: string
  expires_at: string
  /** Computed by the server from `expires_at`; an expired row no longer accepts. */
  state: 'pending' | 'expired'
}

export interface SkillInvitations {
  items: SkillInvitation[]
}

/**
 * The answer to a share. An invitation carries its expiry; an already-granted answer carries the
 * rung the person holds and no expiry, because nothing was written.
 */
export interface InvitationResult {
  handle: string
  role: Rung
  expires_at?: string
  already_granted?: boolean
}

/** The server's open kind vocabulary (v45). */
export type NotificationKind =
  | 'share_invitation'
  | 'access_request'
  | 'access_request_result'
  | 'announcement'
  /* v56: a skill stopped working because its author deleted their account (ADR 0175). */
  | 'skill_unavailable'
  /* v61: a public skill entered its 下线中 ramp; the deadline is in `expires_at` (ADR 0184). */
  | 'skill_offline_scheduled'

/** A notification's displayed state: the result snapshot, or `pending` / `info`. */
export type NotificationState =
  | 'pending'
  | 'accepted'
  | 'declined'
  | 'expired'
  | 'revoked'
  | 'approved'
  | 'rejected'
  | 'withdrawn'
  | 'info'

/** One notification. `state` is a snapshot; the invitation or request row remains the authority. */
export interface Notification {
  id: string
  kind: NotificationKind
  state: NotificationState
  actor: string | null
  skill: { namespace: string; name: string } | null
  role: Rung | null
  title: string | null
  body: string | null
  created_at: string
  /** When the invitation / request was settled; null while it still waits. */
  resolved_at: string | null
  expires_at: string | null
  read: boolean
  viewable: boolean
}

export interface NotificationPage {
  items: Notification[]
  total: number
  unread: number
}
