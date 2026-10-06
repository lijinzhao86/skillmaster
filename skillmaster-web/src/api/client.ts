import type { ApiResult, ErrorDetail, ErrorEnvelope } from './types'

/** The CSRF pair the server uses. Named in one place so both halves move together. */
export const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'

/**
 * What to assume when a 429 arrives without a usable `Retry-After`.
 *
 * The server always sends one, so its absence means something in between ate it. A countdown needs
 * a number; assuming a minute is better than showing `NaN` or no wait at all.
 */
const FALLBACK_RETRY_AFTER_SECONDS = 60

/**
 * How long to wait for an answer before giving up.
 *
 * `fetch` has no default timeout: a server that accepts the connection and then never answers — a
 * hung proxy, a half-open connection — leaves the promise pending forever, and every caller here
 * awaits it. The form would sit on "登录中…" with no error and no way out but a reload. Generous,
 * because one of these calls makes an SMS provider wait on the other end.
 */
const TIMEOUT_MS = 15_000

/**
 * What the two code-sending endpoints are given instead.
 *
 * They block on an SMS provider, so the budget has to cover somebody else's worst case rather than
 * this service's. Timing out on one of them is worse than waiting: the message may have been sent
 * anyway, and the person is looking at a failure that says otherwise.
 */
export const SMS_TIMEOUT_MS = 45_000

interface RequestOptions {
  method: 'GET' | 'POST'
  path: string
  body?: unknown
  /**
   * Overrides {@link TIMEOUT_MS} for a request that waits on something slower than this service.
   *
   * The two code-sending endpoints block on an SMS provider, so a budget that is generous for a
   * database read is not generous for them — and a request that is given up on is not necessarily a
   * request the server abandoned.
   */
  timeoutMs?: number
  /**
   * How to read a body that came back with a 2xx. JSON unless the endpoint says otherwise.
   *
   * One endpoint does: a skill's raw `SKILL.md` is served as `text/markdown`, because it is the
   * author's own bytes and §4.2 promises them unaltered. Running it through `JSON.parse` would turn
   * a Markdown document into `internal_error` — a message about our own reading, presented as a
   * failure of the server's.
   */
  responseType?: 'json' | 'text'
}

/**
 * Every call to the server goes through here.
 *
 * Four things live in this function because getting any of them wrong is a bug that looks like
 * something else:
 *
 * 1. `credentials: 'same-origin'` — not `'include'`. The app is served from the same origin as the
 *    API in development (through the proxy) and in production (behind the reverse proxy).
 *    `'include'` would go on working the day somebody moves the frontend to a CDN, but only if a
 *    pile of CORS headers nobody has written happens to be right. This way that day breaks loudly.
 * 2. The CSRF token is read from the cookie **at send time, never cached**. The server replaces it
 *    whenever a session is established, so a token captured earlier is exactly the one that will be
 *    stale — and a stale token is a 403 whose message blames the client.
 * 3. `X-XSRF-TOKEN` goes on every POST, not only the ones that feel state-changing. On this plane
 *    they all are.
 * 4. Failures are decoded, never thrown: the caller gets the envelope's code and details, or a
 *    synthesised `internal_error` when the response was not our envelope at all (a proxy's HTML
 *    error page, say). The UI can then always render something true.
 */
export async function request<T>(options: RequestOptions): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (options.method !== 'GET') {
    const token = csrfToken()
    if (token !== null) {
      headers[CSRF_HEADER] = token
    }
  }

  let response: Response
  let text: string
  try {
    response = await fetch(options.path, {
      method: options.method,
      headers,
      credentials: 'same-origin',
      signal: AbortSignal.timeout(options.timeoutMs ?? TIMEOUT_MS),
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
    // Read inside the same guard, and that is not tidiness: the body arrives as a second stream, so
    // a connection that closes after the headers, or the abort above firing while the body is being
    // read, rejects here. Outside this `catch` that would reject `request()` itself and every
    // caller's `await` with it — leaving `App.vue` on "加载中…" for ever and the sign-in button
    // disabled with no message, which is the failure the timeout was added to remove.
    text = await response.text()
  } catch (cause) {
    // A timeout reaches here as a `TimeoutError` — a `DOMException`, not the `AbortError` that
    // `AbortController.abort()` produces — and it is worth telling apart from a refused connection:
    // "the network failed" is false when the network was fine and the server was slow.
    console.error('request to %s failed', options.path, cause)
    // Read structurally rather than with `instanceof`: the abort reason can come from another realm
    // — undici under jsdom, a worker, a frame — and an `instanceof` that is false there would report
    // a slow server as a broken network, sending somebody to check their router.
    const name = (cause as { name?: unknown } | null)?.name
    return name === 'TimeoutError' ? timedOut() : networkFailure()
  }

  if (response.ok) {
    // An empty body is only a success for 204. For anything else it means a response was cut short,
    // and handing `undefined` to a caller that expects captcha fields is a `TypeError` in a
    // discarded promise: no captcha, no message, nothing on screen.
    if (response.status === 204) {
      return { ok: true, status: response.status, data: undefined as T }
    }
    if (options.responseType === 'text') {
      // Not parsed, and not trimmed: the bytes are the answer, and a view that shows them has to
      // show what was served rather than a normalised version of it.
      return { ok: true, status: response.status, data: text as T }
    }
    try {
      return { ok: true, status: response.status, data: JSON.parse(text) as T }
    } catch {
      // A 2xx whose body is not our JSON — a proxy falling back to an HTML page, a captive portal.
      console.error('a successful response was not JSON: %s', text)
      return unreadable(response.status)
    }
  }

  const envelope = parseEnvelope(text)
  if (envelope === null) {
    console.error('a %s from %s was not our error envelope: %s', response.status, options.path, text)
  }
  return {
    ok: false,
    status: response.status,
    code: envelope?.error.code ?? 'internal_error',
    message: envelope?.error.message ?? '服务暂时不可用，请稍后重试。',
    details: detailsOf(envelope),
    retryAfterSeconds: response.status === 429 ? retryAfterSeconds(response) : undefined,
  }
}

function networkFailure(): ApiResult<never> {
  return {
    ok: false,
    status: 0,
    code: 'network_error',
    message: '网络请求失败，请检查网络后重试。',
    details: [],
  }
}

/**
 * The request was given up on rather than refused.
 *
 * Its own code because the two are different claims and the message has to be true: a slow server
 * reported as a broken network sends somebody to check their router. On the two endpoints that wait
 * on an SMS provider it is also not proof the send did not happen.
 */
function timedOut(): ApiResult<never> {
  return {
    ok: false,
    status: 0,
    code: 'timeout',
    message: '服务响应太慢，请稍后重试。',
    details: [],
  }
}

/** A 2xx whose body was not this service's JSON, or was not there at all. */
function unreadable(status: number): ApiResult<never> {
  return {
    ok: false,
    status,
    code: 'internal_error',
    message: '服务返回了无法识别的内容，请稍后重试。',
    details: [],
  }
}

/**
 * The CSRF token as it stands right now.
 *
 * Not decoded: the server generates it with `UUID.randomUUID()`, so it is hex and dashes, and
 * `decodeURIComponent` on a value that happened to contain a `%` would throw inside a request.
 *
 * Exported as well as used here, because the consent page submits a **plain HTML form** rather than
 * going through {@link request}: the answer to that POST is a redirect to the caller's loopback port,
 * and `fetch` would have to follow it, read a cross-origin body and then do the navigation itself.
 * A form lets the browser do what it is for. The token then has to be rendered into the form by the
 * page, and it is the same value this reads for every other write.
 */
export function csrfToken(): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${CSRF_COOKIE}=([^;]*)`))
  const value = match?.[1]
  return value === undefined || value === '' ? null : value
}

function parseEnvelope(text: string): ErrorEnvelope | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null || !('error' in parsed)) {
    return null
  }
  const error = (parsed as { error: unknown }).error
  if (typeof error !== 'object' || error === null || typeof (error as { code?: unknown }).code !== 'string') {
    return null
  }
  return parsed as ErrorEnvelope
}

function detailsOf(envelope: ErrorEnvelope | null): ErrorDetail[] {
  const details: unknown = envelope?.error.details
  return Array.isArray(details) ? (details as ErrorDetail[]) : []
}

/** Seconds to wait, or the fallback. Guarded against `NaN`, which a countdown cannot survive. */
function retryAfterSeconds(response: Response): number {
  const header = response.headers.get('Retry-After')
  if (header === null) {
    return FALLBACK_RETRY_AFTER_SECONDS
  }
  const seconds = Number.parseInt(header, 10)
  return Number.isFinite(seconds) && seconds > 0 ? seconds : FALLBACK_RETRY_AFTER_SECONDS
}
