/**
 * Who is looking — asked once, answered without blocking the page.
 *
 * www holds no session of its own ([ADR 0086](../../docs/decisions/0086-the-homepage-reads-the-session.md)):
 * it asks the console one read-only question, `GET /web/session`, and draws what came back. The
 * cookie is `HttpOnly`, so the answer has to come from `fetch(..., {credentials: 'include'})` —
 * script cannot read the cookie, which is the point.
 *
 * **Every failure is "signed out".** 401, a network error, a malformed body, or the three-second
 * deadline all end the same way, because they are the same answer to the visitor: this page cannot
 * prove you are signed in, so it shows you the page a signed-out visitor sees. Failing open is the
 * safe direction — the worst case is one extra click on 登录, never a stranger's name in the corner.
 *
 * **The page never waits.** The nav renders signed-out immediately and swaps in place when the
 * answer arrives; no skeleton, no empty gap (design/README.md §已登录变体, v15 TD Q10).
 */

import { useEffect, useState } from 'react'
import { CONSOLE_URL } from './console'

/** What `/web/session` answers: the three fields the console's own bootstrap uses. */
export interface Account {
  user_id: string
  username: string
  namespace: string
}

export interface Session {
  /** The signed-in account, or null — including while the question is still open. */
  account: Account | null
  /** Whether the one question has been answered. The nav does not depend on it; tests do. */
  settled: boolean
}

/** The deadline for the one request. A slow console must not hold the page's only state. */
export const SESSION_TIMEOUT_MS = 3000

/**
 * Where the question goes.
 *
 * Production asks the console's origin, cross-origin, and the server allows exactly that origin
 * (ADR 0086). Development asks this app's own `/web`, which the dev server proxies (see
 * `vite.config.ts`) — so the browser sends the session cookie with no CORS and no laptop-port
 * exception on the server.
 *
 * **The branch is a parameter, not a read of `import.meta.env`**, so both answers can be asserted
 * without a second build. Only the caller knows which one the running bundle is.
 */
export function sessionEndpoint(dev: boolean, consoleUrl: string): string {
  return dev ? '/web/session' : consoleUrl + '/web/session'
}

function isAccount(value: unknown): value is Account {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.user_id === 'string' &&
    typeof candidate.username === 'string' &&
    typeof candidate.namespace === 'string'
  )
}

/** Reads the session once, on mount. Never throws; the answer is always a `Session`. */
export function useSession(): Session {
  const [session, setSession] = useState<Session>({ account: null, settled: false })

  useEffect(() => {
    /* The flag belongs to this run of the effect, not to the component: a reply that lands after
       the effect was cleaned up (or run again) must not write state. A ref shared across runs
       cannot tell "this run was cancelled" from "a newer run is live". */
    let active = true
    const controller = new AbortController()
    const deadline = window.setTimeout(() => controller.abort(), SESSION_TIMEOUT_MS)

    const finish = (account: Account | null) => {
      if (active) setSession({ account, settled: true })
    }

    fetch(sessionEndpoint(import.meta.env.DEV, CONSOLE_URL), {
      method: 'GET',
      credentials: 'include',
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    })
      .then(async (response) => {
        if (!response.ok) return finish(null)
        const body: unknown = await response.json().catch(() => null)
        finish(isAccount(body) ? body : null)
      })
      .catch(() => finish(null))
      .finally(() => window.clearTimeout(deadline))

    return () => {
      active = false
      window.clearTimeout(deadline)
      controller.abort()
    }
  }, [])

  return session
}
