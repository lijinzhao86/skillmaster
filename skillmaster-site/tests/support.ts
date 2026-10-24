/** The little that every test here needs: stub `fetch`, and wait for a settled render. */

export interface Call {
  url: string
  init: RequestInit | undefined
}

/** Stubs `fetch` with the given answer and records every call it receives. */
export function stubFetch(answer: Response | (() => Promise<Response>)): Call[] {
  const calls: Call[] = []
  const respond = typeof answer === 'function' ? answer : () => Promise.resolve(answer)
  globalThis.fetch = ((url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init })
    return respond()
  }) as typeof fetch
  return calls
}

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** Lets pending promise callbacks run before the assertion looks at the DOM. */
export const flush = () => new Promise((done) => setTimeout(done, 0))
