/**
 * The shapes that travel on the wire.
 *
 * snake_case in both directions, because that is what the server speaks: its Jackson naming
 * strategy is SNAKE_CASE, so a Java record component called `captchaId` is `captcha_id` here. These
 * names are the contract — a typo in one of them is a request the server refuses, and the tests
 * below cannot catch it because they stub the network. See the smoke test in the README.
 */

export interface Account {
  user_id: string
  username: string
  namespace: string
}

export interface Captcha {
  captcha_id: string
  /** The PNG as raw base64 — no `data:` prefix. The view adds one to put it in an `<img>`. */
  image: string
}

/**
 * Whether a candidate username can still be taken.
 *
 * An answer to a question rather than a refusal, so it arrives as a 200. `issue` is the code the
 * registration itself would refuse the name with — the same vocabulary as everywhere else, so the
 * sentence shown is the one the same refusal would get at submit. Null when nothing is wrong.
 */
export interface UsernameAvailability {
  available: boolean
  issue: string | null
}

/**
 * Whether a registration code send from this browser would be asked for a captcha.
 *
 * So the form can draw the captcha as it opens rather than after somebody has pressed a button and
 * been refused. Advice, not a promise — the answer can go stale before the send it was about, which
 * is why a refusal naming the captcha field still has to be handled.
 */
export interface CaptchaRequirement {
  required: boolean
}

export interface ErrorDetail {
  field: string
  issue: string
}

/** The envelope every 4xx and 5xx carries. `details` is always present, empty when there are none. */
export interface ErrorEnvelope {
  error: {
    code: string
    message: string
    details: ErrorDetail[]
  }
}

/**
 * What every call returns.
 *
 * A discriminated union rather than a thrown exception, so that a caller has to deal with the
 * failure branch to reach the data and no call site needs try/catch. A request that never reached
 * the server is `ok: false` too, with `status: 0` — `network_error` when it was refused and
 * `timeout` when it was given up on, which are different claims and different sentences for the
 * person reading them.
 */
export type ApiResult<T> =
  | { ok: true; status: number; data: T }
  | {
      ok: false
      status: number
      code: string
      message: string
      details: ErrorDetail[]
      /** Only on a 429. Always a usable number: see `client.ts`. */
      retryAfterSeconds?: number
    }
