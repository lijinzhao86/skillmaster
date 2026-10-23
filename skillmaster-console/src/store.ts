import { useSyncExternalStore } from 'react'
import { currentSession } from './api/account'
import { codeMessage } from './api/errors'
import type { Account } from './api/types'

/**
 * Who is signed in.
 *
 * Module-level state rather than a store library: there is one fact shared across pages, and it is
 * refetched on every page load anyway — a full reload is what happens after signing in, which is
 * also what picks up the session and CSRF cookies the server has just replaced.
 *
 * It doubles as the app's bootstrap, and that is not a side effect to be tidied away: the server
 * only writes the CSRF cookie while answering a request, so *some* GET has to go through before the
 * first POST can carry a token. `App` waits for this before rendering anything.
 *
 * `useSyncExternalStore` is the React spelling of what the Vue version got from a module-level
 * `ref`: one value read by every component, with the module as its only writer. Nothing here is
 * client-authoritative — the server's session table is.
 */
export interface SessionState {
  readonly account: Account | null
  readonly loaded: boolean
  readonly problem: string | null
}

export interface Session {
  load: () => Promise<void>
  subscribe: (listener: () => void) => () => void
  snapshot: () => SessionState
  /**
   * Back to "nothing has been read yet".
   *
   * The app never calls this — a full reload is how a real page starts over. It exists because the
   * store is a module singleton and a test that renders `App` again cannot reload the module: without
   * a reset, the second test would open with `loaded` already true and never exercise the bootstrap.
   */
  reset: () => void
}

const EMPTY: SessionState = { account: null, loaded: false, problem: null }

/**
 * A session store, made as a function rather than as module-level `let`s for one reason: a test can
 * then hold its own instance instead of resetting the module registry, which in React would hand it
 * a second copy of `react` and break every hook in the process.
 */
export function createSession(): Session {
  let state: SessionState = EMPTY
  const listeners = new Set<() => void>()

  function publish(next: SessionState): void {
    // Frozen because a snapshot is handed to every reader: without this, one page could write to the
    // object it was given and every other page would see the change. `readonly` is a type, and this
    // is the runtime half of it.
    state = Object.freeze(next)
    for (const listener of listeners) {
      listener()
    }
  }

  async function load(): Promise<void> {
    const result = await currentSession()
    // The value is checked, not just `ok`: a success with no data is a shape the client can hand
    // back, and reading the account off `undefined` would set `account` to it while leaving
    // `problem` null — falling through to "还没有登录", which is the claim this module exists not
    // to make. `!= null` rather than `!== undefined`: a 200 whose body is the JSON literal `null`
    // produces `null`, which `!== undefined` lets through, with the same effect.
    const found: Account | null | undefined = result.ok ? result.data : undefined
    if (found != null) {
      publish({ account: found, loaded: true, problem: null })
      return
    }
    // A 401 is the ordinary answer for a visitor who is not signed in, not a failure to report.
    // Anything else — a 500, a network failure — is a question this page could not ask, and the
    // answer to that is not "还没有登录": saying so would be a claim about the session that was
    // never made.
    publish({
      account: null,
      loaded: true,
      problem: result.ok
        ? '服务返回了无法识别的内容，请刷新页面重试。'
        : result.code === 'unauthenticated'
          ? null
          : codeMessage(result.code, result.message),
    })
  }

  return {
    load,
    subscribe: (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    snapshot: () => state,
    reset: () => {
      publish(EMPTY)
    },
  }
}

/** The one the app uses. Pages read it; only this module writes it. */
export const session = createSession()

/** The app's bootstrap: ask the server who is signed in, and therefore also fetch a CSRF token. */
export function loadSession(): Promise<void> {
  return session.load()
}

/** The one fact, and a re-render when it changes. */
export function useSession(): SessionState {
  return useSyncExternalStore(session.subscribe, session.snapshot, session.snapshot)
}
