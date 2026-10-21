/**
 * The credentials the demo shows, and the dates that decide their state.
 *
 * ⚠️ **These are fixtures.** `api_key` is designed (ADR 0038, v2-credentials) and the app has the
 * four endpoints; what is *not* invented is the shape: the string never appears again, revoking is a
 * state rather than a deletion, and an expired key is one whose date has passed rather than one that
 * was removed.
 *
 * The dates are relative to the list's own "today" (`KEYS_AS_OF`), so the demo says the same thing
 * tomorrow as it says today.
 */

export const KEYS_AS_OF = '2026/10/15'

/** One number, and the page and the row both read it. */
export const SOON_DAYS = 14

/** **The issuance default, in days, written once**: the form pre-selects it and a renewal re-dates
 *  to it. Two copies of "90" is how a screen ends up offering a different default from the policy
 *  it claims to follow. */
export const EXPIRY_DAYS = 90

export interface ApiKey {
  id: string
  name: string
  note: string
  scope: string
  expires: string
  used?: string
  revokedAt?: string
}

/** Every key carries an id, and the address names a key by it rather than by its name: two keys may
 *  share a name, and the reference product's own revoke/rotate routes take the id. */
export const KEYS: ApiKey[] = [
  { id: 'k1', name: '工作本', note: '我自己的这台，平时读 skill 用。', scope: '可读可写', expires: '2027/01/13', used: '今天 09:41' },
  { id: 'k2', name: '家里的机器', note: '', scope: '可读', expires: '2026/10/26', used: '昨天 03:12' },
  { id: 'k3', name: 'CI', note: '流水线里读 pdf-tools，只读。', scope: '可读', expires: '2026/12/01', used: '2026/10/11 22:04' },
  { id: 'k4', name: '备用', note: '', scope: '可读', expires: '2026/09/30', used: '从未使用' },
  {
    id: 'k5', name: '旧笔记本', note: '换机前那把，已经交出去了。', scope: '可读可写', expires: '2027/01/13',
    revokedAt: '2026/10/02', used: '2026/10/02 18:20',
  },
]

export const KEY_FILTERS = ['active', 'soon', 'revoked', 'expired'] as const
export const KEY_LABEL: Record<string, string> = {
  active: '活跃',
  soon: '两周内到期',
  revoked: '已撤销',
  expired: '已过期',
}

export function dayOf(text: string): number {
  return Date.parse(text.replace(/\//g, '-') + 'T00:00:00Z')
}

export const KEYS_NOW = dayOf(KEYS_AS_OF)

/** The list's own date format, in one place, so a computed date looks like a fixture's. */
export function dateLabel(ms: number): string {
  const day = new Date(ms)
  return day.getUTCFullYear() + '/' + ('0' + (day.getUTCMonth() + 1)).slice(-2) + '/' +
    ('0' + day.getUTCDate()).slice(-2)
}

/** A renewal replaces the date rather than inheriting it: what comes out of 续期 is a newly issued
 *  credential, so it gets the issuance default. */
export const RENEWED_EXPIRES = dateLabel(KEYS_NOW + EXPIRY_DAYS * 86400000)

export type KeyStatus = 'revoked' | 'expired' | 'soon' | 'active'

/** Derived, never stored — "expired" is what a date means once it is behind you. */
export function statusOf(key: ApiKey): KeyStatus {
  if (key.revokedAt) return 'revoked'
  if (dayOf(key.expires) < KEYS_NOW) return 'expired'
  if (dayOf(key.expires) - KEYS_NOW <= SOON_DAYS * 86400000) return 'soon'
  return 'active'
}

/**
 * **The key that was just created is a real row, or this page tells two stories.** The form hands its
 * facts over in the address — which is what this page does with state — so the list can draw it, a
 * refresh keeps it, and the link somebody is sent shows the same account.
 *
 * They are a family of their own (`made-*`) rather than the moment's `name`: `name` means "the key
 * this moment is about" (a rotation uses it), and a page can hold both. All three are required: the
 * id is what the row is addressed by from here on.
 */
export function madeKey(params: URLSearchParams): ApiKey | null {
  const id = params.get('made-id')
  const name = params.get('made-name')
  const expires = params.get('made-expires')
  if (!id || !name || !expires) return null
  return {
    id,
    name,
    note: params.get('made-note') || '',
    scope: params.get('made-scope') === 'write' ? '可读可写' : '可读',
    expires: expires.replace(/-/g, '/'),
    used: '从未使用',
  }
}

/** Which key an id names. The created key counts even before the list has adopted it, because the
 *  moment it is created is the moment its id starts being used. */
export function keyById(params: URLSearchParams, id: string | null): ApiKey | null {
  if (!id) return null
  const made = madeKey(params)
  if (made && made.id === id) return made
  return KEYS.find((candidate) => candidate.id === id) ?? null
}

/**
 * The renewal a moment is about is the last one in the address: `rotated` repeats (an account renews
 * more than one key), and moments are entered by appending.
 *
 * **A revoked key cannot be renewed** — its row does not offer it — so an address claiming one names
 * a transition that cannot happen and falls back like every other unknown here.
 */
export function renewedKey(params: URLSearchParams): ApiKey | null {
  const ids = params.getAll('rotated')
  const key = ids.length ? keyById(params, ids[ids.length - 1]) : null
  if (!key) return null
  const revoked = params.getAll('revoked').includes(key.id)
  return revoked || key.revokedAt ? null : key
}
