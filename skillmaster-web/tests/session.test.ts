import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCookies, errorBody, json, setCookie, stubFetch } from './support'

/**
 * Who is signed in — and the app's bootstrap, which is the same request.
 *
 * The state lives at module level on purpose (there is one such fact, and it is refetched on every
 * page load), so each test imports a fresh copy of the module. Without that, a test would inherit
 * whatever the previous one left loaded, and the first-load behaviour could not be asserted at all.
 */

async function freshSession() {
  vi.resetModules()
  const module = await import('../src/composables/useSession')
  return module.useSession()
}

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
  it('starts out knowing nothing, and having loaded nothing', async () => {
    const session = await freshSession()

    expect(session.loaded.value).toBe(false)
    expect(session.account.value).toBeNull()
  })

  it('answers who is signed in', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT))
    const session = await freshSession()

    await session.load()

    expect(requests[0]?.path).toBe('/web/session')
    expect(requests[0]?.method).toBe('GET')
    expect(session.loaded.value).toBe(true)
    expect(session.account.value).toEqual(ACCOUNT)
  })

  it('treats a 401 as nobody being signed in, rather than as a failure', async () => {
    // The ordinary answer for a visitor, and the reason this is not a banner: a signed-out visitor
    // is not a problem to report, they are a person who needs to be given a way to sign in.
    stubFetch(() => json(401, errorBody('unauthenticated', 'Authentication is required.')))
    const session = await freshSession()

    await session.load()

    expect(session.account.value).toBeNull()
    expect(session.loaded.value).toBe(true)
  })

  it('finishes loading even when the request never arrived', async () => {
    // If this left `loaded` false, the app would show 加载中… for ever — a network failure would look
    // like a page that is still trying, which is the one thing it is not.
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))))
    const session = await freshSession()

    await session.load()

    expect(session.loaded.value).toBe(true)
    expect(session.account.value).toBeNull()
  })

  it('is the same fact for every page, not a copy per caller', async () => {
    // Module-level rather than a store: pages read the same value, and there is nowhere for two
    // copies of it to disagree. Both callers come from one module graph on purpose — calling the
    // fresh-module helper twice would produce two graphs and prove the opposite of this.
    stubFetch(() => json(200, ACCOUNT))
    vi.resetModules()
    const { useSession } = await import('../src/composables/useSession')
    const first = useSession()
    const second = useSession()

    await first.load()

    expect(second.account.value).toEqual(ACCOUNT)
    expect(second.loaded.value).toBe(true)
  })

  it('cannot be overwritten from outside the module', async () => {
    stubFetch(() => json(200, ACCOUNT))
    // Vue's readonly() refuses the write and says so in the console rather than throwing, so the
    // warning is silenced here and the claim is about the value.
    vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const session = await freshSession()
    await session.load()

    // Nothing in the app assigns to these, and a page that could would be able to claim a session
    // the server does not know about.
    ;(session.account as { value: unknown }).value = null

    expect(session.account.value).toEqual(ACCOUNT)
  })
})
