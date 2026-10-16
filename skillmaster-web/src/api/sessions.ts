import { request } from './client'
import type { ApiResult, LoginSessions, SessionsRevoked } from './types'

/**
 * The login-devices plane (ADR 0043), one function each.
 *
 * **Three functions and no create.** A login is not something a page performs — it happens when
 * somebody signs in — so unlike the key screen there is nothing here that makes one; this plane
 * only shows what exists and ends it. That asymmetry is the version's whole shape: shortening a
 * session's life without giving anybody a way to end one would be locking a door and keeping the
 * key.
 *
 * The CSRF header every write needs is added by `client.ts`, which reads it off the cookie at send
 * time; nothing here has to remember it.
 */
const BASE = '/web/sessions'

/** Every device this account is signed in on, the one reading the page marked. */
export function listSessions(): Promise<ApiResult<LoginSessions>> {
  return request<LoginSessions>({ method: 'GET', path: BASE })
}

/**
 * Ends one device's login, now.
 *
 * The row is gone before this answers, so that device's next request is anonymous — there is nothing
 * to wait for. A device that is already gone is a 404 rather than a second success: unlike a key,
 * nothing looks at a device after it has ended, so a stale row means the page is out of date and the
 * page should say so.
 */
export function revokeSession(deviceId: string): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${BASE}/${encodeURIComponent(deviceId)}/revoke`,
  })
}

/**
 * Ends every device but this one, and answers how many that was.
 *
 * The count comes back rather than being taken from the list the page is showing: only the server
 * knows how many were still signed in when the call arrived, and a device ended from another tab a
 * minute ago would otherwise be counted twice.
 */
export function revokeOtherSessions(): Promise<ApiResult<SessionsRevoked>> {
  return request<SessionsRevoked>({ method: 'POST', path: `${BASE}/revoke-others` })
}
