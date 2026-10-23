import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCookies, errorBody, json, setCookie, stubFetch } from './support'
import { createSession } from '../src/store'

/**
 * Who is signed in — and the app's bootstrap, which is the same request.
 *
 * Each test builds its own store rather than resetting the module registry: in React that would
 * re-import `react` as a second copy, and the app's own instance is a module-level singleton the
 * whole point of which is that two readers share it.
 */

const ACCOUNT = { user_id: '01M3HTG7GCCVBGRPAFFSVSF12W', username: 'demo-user', namespace: 'demo-user' }

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the session', () => {
  it('starts out knowing nothing, and having loaded nothing', () => {
    const session = createSession()

    expect(session.snapshot().loaded).toBe(false)
    expect(session.snapshot().account).toBeNull()
  })

  it('answers who is signed in', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT))
    const session = createSession()

    await session.load()

    expect(requests[0]?.path).toBe('/web/session')
    expect(requests[0]?.method).toBe('GET')
    expect(session.snapshot().loaded).toBe(true)
    expect(session.snapshot().account).toEqual(ACCOUNT)
  })

  it('treats a 401 as nobody being signed in, rather than as a failure', async () => {
    // The ordinary answer for a visitor, and the reason this is not a banner: a signed-out visitor
    // is not a problem to report, they are a person who needs to be given a way to sign in.
    stubFetch(() => json(401, errorBody('unauthenticated', 'Authentication is required.')))
    const session = createSession()

    await session.load()

    expect(session.snapshot().account).toBeNull()
    expect(session.snapshot().problem).toBeNull()
    expect(session.snapshot().loaded).toBe(true)
  })

  it('reports anything that is not a 401 as a problem worth showing', async () => {
    // A 500 is a question this page could not ask; the answer to that is not "还没有登录", which
    // would be a claim about the session that was never made.
    stubFetch(() => json(500, errorBody('internal_error', 'Something went wrong.')))
    const session = createSession()

    await session.load()

    expect(session.snapshot().account).toBeNull()
    expect(session.snapshot().problem).not.toBeNull()
  })

  it('finishes loading even when the request never arrived', async () => {
    // If this left `loaded` false, the app would show 加载中… for ever — a network failure would look
    // like a page that is still trying, which is the one thing it is not.
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))))
    const session = createSession()

    await session.load()

    expect(session.snapshot().loaded).toBe(true)
    expect(session.snapshot().account).toBeNull()
  })

  it('tells every subscriber when the fact changes', async () => {
    stubFetch(() => json(200, ACCOUNT))
    const session = createSession()
    let notified = 0
    const stop = session.subscribe(() => {
      notified += 1
    })

    await session.load()
    stop()

    expect(notified).toBe(1)
  })

  it('cannot be overwritten through a snapshot', async () => {
    stubFetch(() => json(200, ACCOUNT))
    const session = createSession()
    await session.load()

    // Nothing in the app assigns to these, and a page that could would be able to claim a session
    // the server does not know about. The snapshot is frozen, so a write fails loudly rather than
    // quietly changing what every other page reads.
    const snapshot = session.snapshot() as { account: unknown }

    expect(() => {
      snapshot.account = null
    }).toThrow(TypeError)
    expect(session.snapshot().account).toEqual(ACCOUNT)
  })
})
