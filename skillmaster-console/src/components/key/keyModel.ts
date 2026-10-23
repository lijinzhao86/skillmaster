import type { ApiKey } from '../../api/types'
import { formatDay } from '../../time'

/**
 * A key's state, and the words this page uses for it — the three screens' shared vocabulary.
 *
 * It lives here rather than in the page because all three screens read it: the list colours a row
 * and counts the cards, the moment writes 「权限 可读 · 到期 …」, and the page filters by it. The
 * functions are pure and take what they need (`now`, a key), so a screen can call them without
 * reaching back into the page that renders it.
 */

export type State = 'active' | 'soon' | 'revoked' | 'expired'
export type Filter = State | 'all'

/**
 * **The one number this screen has.** It decides both the 「两周内到期」 count and the tag on a row,
 * because two thresholds would be one fact written twice — and the reference product does exactly
 * that (a 14-day row badge beside a 15-day count), which is how a page ends up disagreeing with
 * itself in a way nobody notices until a screenshot.
 */
export const SOON_DAYS = 14

export const LABEL: Record<Filter, string> = {
  active: '活跃',
  soon: '两周内到期',
  revoked: '已撤销',
  expired: '已过期',
  all: '全部',
}

/** The state of one key — derived, never stored: the row is the truth and this is a reading of it. */
export function stateOf(key: ApiKey, now: number): State {
  if (key.revoked_at !== null) {
    return 'revoked'
  }
  if (key.expires_at === null) {
    return 'active'
  }
  const expires = Date.parse(key.expires_at)
  if (expires <= now) {
    return 'expired'
  }
  return expires - now <= SOON_DAYS * 24 * 60 * 60 * 1000 ? 'soon' : 'active'
}

/**
 * What the expiry cell says, in one place.
 *
 * Three of the four states share this one wording (`撤销于 …` is the exception, and it is about a
 * different event), so the phrase is built here rather than at each of the four places it is shown.
 * **A key may have no expiry at all** — the deployment can turn them off — and that is a fact the
 * cell has to say: `formatDay` returns an empty string for null, which rendered as「到期 」with
 * nothing after it, reading like a broken cell rather than like a choice somebody made.
 */
export function expiryText(value: string | null): string {
  return value === null ? '永不过期' : `到期 ${formatDay(value)}`
}

/**
 * What the clock line in a row says: what happened to *this* key.
 *
 * **Not always when it expires**, and the difference is the point. A revoked key has a different date
 * entirely, and the day it was revoked is the fact somebody looking at the row needs — its expiry
 * would be true and useless. That date is the thing a product which keeps revocation as a boolean
 * cannot show; this one has the column.
 */
export function happenedText(key: ApiKey): string {
  return key.revoked_at === null ? expiryText(key.expires_at) : `撤销于 ${formatDay(key.revoked_at)}`
}

/** 可读 / 可读可写 — what a person reads, from the vocabulary the token plane already uses. */
export function scopeLabel(scope: string): string {
  return scope.includes('skills:write') ? '可读可写' : '可读'
}

/** One key whose whole string exists on screen, and how much of it is in the reader's hands. */
export interface Moment {
  kind: 'created' | 'rotated'
  key: ApiKey
  token: string
  /** Revealed by the eye button; masked by default. */
  revealed: boolean
  copied: boolean
  /** The fallback where the clipboard is unreachable: the string is selected, and the button says so. */
  selected: boolean
}

/** The row whose consequence question is open, and which question it is. One at a time. */
export interface Question {
  id: string
  action: 'revoke' | 'rotate'
}

/** A navigation the moment screen is refusing to make quietly, and what to hand the focus back to. */
export interface Leaving {
  go: () => void
  back: HTMLElement | null
}

/** Whether the string is in the reader's hands — copied, or selected by the fallback. */
export function heldOf(current: Moment | null): boolean {
  return current !== null && (current.copied || current.selected)
}
