import { vi } from 'vitest'

/** One request the code under test made. */
export interface RecordedRequest {
  path: string
  method: string
  headers: Record<string, string>
  body: unknown
}

/**
 * A `fetch` that answers in order and remembers what it was asked.
 *
 * Hand-written rather than MSW: what these tests assert is the protocol — which header, which field
 * name, which order — and MSW would add a cookie jar and a service worker to simulate a network
 * none of that depends on.
 *
 * Responses are factories, because a body can only be read once.
 */
export function stubFetch(...responses: Array<() => Response | Promise<Response>>) {
  const requests: RecordedRequest[] = []
  const mock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const headers = (init?.headers ?? {}) as Record<string, string>
    requests.push({
      path: String(input),
      method: init?.method ?? 'GET',
      headers,
      body: init?.body === undefined ? undefined : JSON.parse(String(init.body)),
    })
    const next = responses.shift()
    if (next === undefined) {
      throw new Error(`the test made more requests than it stubbed (${requests.length} so far)`)
    }
    // A thunk may be async: a response that has not arrived yet is how a test reaches the window
    // between a request going out and its answer coming back.
    return next()
  })
  vi.stubGlobal('fetch', mock)
  return { requests, mock }
}

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

export function empty(status: number, headers: Record<string, string> = {}): Response {
  return new Response(null, { status, headers })
}

/** The envelope the server sends for everything that failed. */
export function errorBody(
  code: string,
  message: string,
  details: Array<{ field: string; issue: string }> = [],
): unknown {
  return { error: { code, message, details } }
}

/**
 * Replaces `window.location`, which jsdom refuses to navigate.
 *
 * Only what the app reads is provided. The real object is captured on the first call and put back by
 * `restore`, so a test may call this again with a different path without losing the original.
 */
export function stubLocation(pathname: string, search = '', href?: string) {
  originalLocation ??= window.location
  const assign = vi.fn()
  Object.defineProperty(window, 'location', {
    configurable: true,
    // `href` is what the code resolves a relative `return_to` against, so a test about origins has
    // to be able to say what host the page is on — jsdom's own is `http://localhost:3000`.
    value: { pathname, search, assign, href: href ?? originalLocation.href },
  })
  return {
    assign,
    restore: () => {
      if (originalLocation !== null) {
        Object.defineProperty(window, 'location', { configurable: true, value: originalLocation })
        originalLocation = null
      }
    },
  }
}

let originalLocation: Location | null = null

export function setCookie(name: string, value: string): void {
  document.cookie = `${name}=${value}; path=/`
}

export function clearCookies(): void {
  for (const cookie of document.cookie.split(';')) {
    const name = cookie.split('=')[0]?.trim()
    if (name !== undefined && name !== '') {
      document.cookie = `${name}=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT`
    }
  }
}

/** Lets the pending promises in a component settle. */
export function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}
