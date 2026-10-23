import { request } from './client'
import type { ApiResult, CliSignIn, CliSignInRedirect, CliSignInRefusal } from './types'

/**
 * The CLI's login, from the browser's side (v4): the two buttons on the login-request screen, and
 * the write that ends a login from the console.
 *
 * **Two of these three are the page's whole vocabulary.** That screen has one thing to do and one
 * thing to refuse, and both answers are the same shape — a `redirect_to` the browser is handed — so
 * the page never has to know two formats. The address it is given is read from the registered client
 * row on the server, deliberately: the page does not know the CLI's loopback address, and a target
 * the request could choose would be an open redirect with a one-time code on the end of it.
 *
 * **The credential is not here at all.** What this brings into being is a login; the credential is
 * minted when the CLI redeems the code, and it appears in exactly one response, to the process that
 * asked (v4 §接口).
 *
 * The CSRF header every write needs is added by `client.ts`, which reads it off the cookie at send
 * time; nothing here has to remember it.
 */
const BASE = '/web/cli-sign-ins'

/** Agrees to the login: names the machine, and answers where the browser goes next. */
export function startCliSignIn(signIn: CliSignIn): Promise<ApiResult<CliSignInRedirect>> {
  return request<CliSignInRedirect>({ method: 'POST', path: BASE, body: signIn })
}

/**
 * Declines it.
 *
 * It comes back to the server rather than being a navigation the page performs, and that is the only
 * way it can work: the refusal has to end at the CLI's loopback address, and the page has never been
 * told it. Nothing is written on the way — a refusal is a login that did not happen.
 */
export function refuseCliSignIn(refusal: CliSignInRefusal): Promise<ApiResult<CliSignInRedirect>> {
  return request<CliSignInRedirect>({ method: 'POST', path: `${BASE}/refuse`, body: refusal })
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
  return request<void>({ method: 'POST', path: `${BASE}/${encodeURIComponent(id)}/revoke` })
}
