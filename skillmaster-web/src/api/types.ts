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
