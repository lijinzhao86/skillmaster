import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { login } from '../src/api/account'
import { CSRF_COOKIE, CSRF_HEADER, request } from '../src/api/client'
import { clearCookies, empty, errorBody, json, setCookie, stubFetch } from './support'

describe('the api client', () => {
  beforeEach(() => clearCookies())
  afterEach(() => vi.unstubAllGlobals())

  it('sends the CSRF token from the cookie on a POST', async () => {
    setCookie(CSRF_COOKIE, 'token-one')
    const { requests } = stubFetch(() => json(200, account()))

    await login({ phone: '13800138000', password: 'correct-horse' })

    expect(requests[0]?.headers[CSRF_HEADER]).toBe('token-one')
  })

  it('reads the token at send time, so a rotated one is picked up', async () => {
    // The trap this exists for: the server replaces the token when a session is established, so a
    // value captured once and remembered is exactly the one that will be stale.
    setCookie(CSRF_COOKIE, 'before-login')
    const { requests } = stubFetch(
      () => json(200, account()),
      () => empty(204),
    )

    await login({ phone: '13800138000', password: 'correct-horse' })
    setCookie(CSRF_COOKIE, 'after-login')
    await request({ method: 'POST', path: '/web/logout' })

    expect(requests[0]?.headers[CSRF_HEADER]).toBe('before-login')
    expect(requests[1]?.headers[CSRF_HEADER]).toBe('after-login')
  })

  it('does not put the token on a GET', async () => {
    setCookie(CSRF_COOKIE, 'token-one')
    const { requests } = stubFetch(() => json(401, errorBody('unauthenticated', 'Authentication is required.')))

    await request({ method: 'GET', path: '/web/session' })

    expect(requests[0]?.headers[CSRF_HEADER]).toBeUndefined()
  })

  it('asks for same-origin credentials, not include', async () => {
    const { requests, mock } = stubFetch(() => json(200, account()))
    setCookie(CSRF_COOKIE, 'token-one')

    await login({ phone: '13800138000', password: 'correct-horse' })

    expect(requests).toHaveLength(1)
    expect(mock.mock.calls[0]?.[1]?.credentials).toBe('same-origin')
  })

  it('decodes a 401 instead of throwing', async () => {
    stubFetch(() => json(401, errorBody('invalid_credentials', 'the phone number or password is incorrect')))

    const result = await login({ phone: '13800138000', password: 'wrong' })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.status).toBe(401)
      expect(result.code).toBe('invalid_credentials')
      expect(result.details).toEqual([])
    }
  })

  it('reports how long to wait on a 429', async () => {
    stubFetch(() => empty(429, { 'Retry-After': '37' }))

    const result = await login({ phone: '13800138000', password: 'wrong' })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.retryAfterSeconds).toBe(37)
    }
  })

  it('falls back to a number when a 429 arrives without Retry-After', async () => {
    // A countdown cannot survive NaN, and the header can be eaten by anything in between.
    stubFetch(() => empty(429))

    const result = await login({ phone: '13800138000', password: 'wrong' })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.retryAfterSeconds).toBe(60)
    }
  })

  it('turns a response that is not our envelope into something showable', async () => {
    // What a reverse proxy sends when the backend is down.
    stubFetch(() => new Response('<html>502 Bad Gateway</html>', { status: 502 }))

    const result = await request({ method: 'GET', path: '/web/session' })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.status).toBe(502)
      expect(result.code).toBe('internal_error')
      expect(result.message).not.toBe('')
      expect(result.details).toEqual([])
    }
  })

  it('reports a network failure rather than throwing', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))))

    const result = await request({ method: 'GET', path: '/web/session' })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.status).toBe(0)
      expect(result.code).toBe('network_error')
    }
  })

  it('gives back nothing for a 204', async () => {
    setCookie(CSRF_COOKIE, 'token-one')
    stubFetch(() => empty(204))

    const result = await request({ method: 'POST', path: '/web/logout' })

    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.status).toBe(204)
      expect(result.data).toBeUndefined()
    }
  })

  it('refuses to call a 2xx with a non-JSON body a success', async () => {
    // Passed on as `data`, this `undefined` would be dereferenced by the caller and the form would
    // show nothing at all — no captcha and no message.
    stubFetch(() => new Response('<html>captive portal</html>', { status: 200 }))

    const result = await request({ method: 'GET', path: '/web/captcha' })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.code).toBe('internal_error')
      expect(result.message).not.toBe('')
    }
  })

  it('gives up on a request that never answers, and says the server was slow', async () => {
    // `fetch` has no timeout of its own, so without a signal the caller waits forever and the
    // button stays disabled. The stub keeps the promise pending until the signal really does abort,
    // so the timeout itself is exercised rather than simulated — a test that only asserted the
    // signal existed would stay green with any budget at all, including one that never fires.
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_input: RequestInfo | URL, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () =>
              reject(new DOMException('The operation timed out.', 'TimeoutError')),
            )
          }),
      ),
    )

    const result = await request({ method: 'GET', path: '/web/session', timeoutMs: 10 })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.status).toBe(0)
      // Not `network_error`: the network was fine and the server was slow, and telling somebody to
      // check their connection when the wait is the problem sends them to the wrong place.
      expect(result.code).toBe('timeout')
    }
  })

  it('recognises a timeout that did not come from this realm', async () => {
    // The abort reason can be an object from another realm — undici under jsdom, a worker, a frame —
    // where `instanceof DOMException` is false. Reporting a slow server as a broken network sends
    // somebody to check a router that was never the problem.
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject({ name: 'TimeoutError', message: 'The operation timed out.' })),
    )

    const result = await request({ method: 'GET', path: '/web/session' })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.code).toBe('timeout')
    }
  })

  it('reports a body that fails to arrive as a failure, not as a successful response', async () => {
    // The headers can arrive and the body then not: a proxy that closes the connection, or the
    // timeout above firing during the read. Read outside the guard, this rejects `request()` itself
    // and the caller's `await` with it — which is how a page ends up on "加载中…" for ever.
    stubFetch(() => {
      const body = new ReadableStream({
        start(controller) {
          controller.error(new TypeError('terminated'))
        },
      })
      return new Response(body, { status: 200, headers: { 'Content-Type': 'application/json' } })
    })

    const result = await request({ method: 'GET', path: '/web/session' })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.code).toBe('network_error')
    }
  })

  it('refuses an empty body on a status that is not 204', async () => {
    // A proxy or captive portal answering 200 with nothing in it. Handed on as `data`, the
    // `undefined` becomes a `TypeError` inside a discarded promise: no captcha and no message.
    stubFetch(() => new Response('', { status: 200 }))

    const result = await request({ method: 'GET', path: '/web/captcha' })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.code).toBe('internal_error')
    }
  })
})

function account() {
  return { user_id: '01M3HTG7GCCVBGRPAFFSVSF12W', username: 'demo', namespace: 'demo' }
}
