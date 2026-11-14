import { request } from './client'
import type { ApiResult, CliDeviceSignInView } from './types'

/**
 * The CLI's login, from the browser's side (v68): read the question, answer it, and end a login from
 * the console.
 *
 * **The page does not poll.** The machine that asked does, at its own interval; this page reads the
 * question once, answers it once, and is done. A page that polled would keep a tab busy to learn
 * something it no longer has any use for.
 *
 * **The ticket is the only thing the address carries.** It is what the question is addressed by, and
 * it is worth nothing without the device secret and the PKCE verifier — both of which stayed on the
 * machine that asked.
 *
 * The CSRF header every write needs is added by `client.ts`, which reads it off the cookie at send
 * time; nothing here has to remember it.
 */
const READ = '/cli-device-sign-ins'
const ANSWER = '/web/cli-device-sign-ins'

/** What the page needs to draw the question. A read: it changes nothing, so a link preview is safe. */
export function getCliDeviceSignIn(ticket: string): Promise<ApiResult<CliDeviceSignInView>> {
  return request<CliDeviceSignInView>({
    method: 'GET',
    path: `${READ}/${encodeURIComponent(ticket)}`,
  })
}

/**
 * Agrees to the question: names the machine, and writes the authorization row.
 *
 * No content comes back, and that is the design: the credential is collected by the CLI that asked,
 * not by the page that answered. A question that is gone answers 409, which the page draws as the
 * expired state.
 */
export function approveCliDeviceSignIn(ticket: string, name: string): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${ANSWER}/${encodeURIComponent(ticket)}/approve`,
    body: { name },
  })
}

/** Declines it. Nothing is written: a refusal is a login that did not happen. */
export function refuseCliDeviceSignIn(ticket: string): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${ANSWER}/${encodeURIComponent(ticket)}/refuse`,
  })
}

/**
 * Ends one login, from the row the console is looking at.
 *
 * 204, and every failure is one answer — a login that is not there, one that is already over, and one
 * that belongs to somebody else all answer 404 `session_not_found`, because telling them apart would
 * confirm that a login with that id exists. It is a separate route from the browser session's revoke
 * because it ends a different thing: a session is deleted, an authorization is marked.
 */
export function revokeCliSignIn(id: string): Promise<ApiResult<void>> {
  return request<void>({ method: 'POST', path: `/web/cli-sign-ins/${encodeURIComponent(id)}/revoke` })
}
