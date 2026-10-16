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

/*
 * The author's own plane (ADR 0031): the shapes behind `/web/skills`. They are not the consumption
 * plane's, and the difference is the point — a `state`, a version list, and a `current` that is null
 * because nothing has been published yet.
 */

/**
 * A version as a listing names it.
 *
 * Both halves are here because they answer different questions: the name is the short thing a link
 * carries, and the digest is the identity — and, for a version whose author declared no name, the
 * only thing it can be addressed by at all (ADR 0033).
 */
export interface VersionRef {
  name: string | null
  digest: string
}

/** One row of the author's listing. `current` is null for a skill nothing has been published from. */
export interface AuthoredSkillSummary {
  /** On every row, so a link to it can be built without having read the session first. */
  namespace: string
  name: string
  title: string
  description: string
  visibility: string
  current: VersionRef | null
  /** Versions waiting to be published or discarded — the one thing no other listing says. */
  drafts: number
  /** The most recently submitted one of those, or null when there are none — what the count links to. */
  draft: VersionRef | null
  /**
   * When the most recent version that is *not* discarded was submitted — which is what this list is
   * ordered by. **Null when every version has been discarded**: the query is
   * `max(submitted_at) WHERE state <> 'discarded'`, so a skill whose work was all thrown away has no
   * submission time to report. Not an error and not a missing field.
   */
  latest_submitted_at: string | null
}

export interface AuthoredSkills {
  skills: AuthoredSkillSummary[]
}

/**
 * Who a skill is shared with, and how much (ADR 0034).
 *
 * `handle` is the username rather than a user id: this is a list a person reads while deciding
 * whether to take somebody's access away, and a list of ULIDs is one they cannot act on.
 *
 * `role` is `viewer` or `editor`, and `owner` is not one that can appear: it is what owning the
 * namespace gives you, not something that can be handed out.
 */
export interface SkillGrant {
  handle: string
  role: 'viewer' | 'editor'
  /** When access was first given. Changing the role does not move it. */
  created_at: string
}

export interface SkillGrants {
  grants: SkillGrant[]
}

/**
 * One version, and what the author has decided about it.
 *
 * `is_current` is not the same question as `state === 'published'`: a version that was superseded is
 * still published, and its own name still resolves. `state_at` is when it left draft, which is null
 * while it is one.
 *
 * `name` is the semver the author declared, and null when they declared none — such a version is
 * addressed by its digest alone (ADR 0033). The server's integer submission key is deliberately not
 * on the wire: nothing here orders by version name, and the list arrives newest submission first.
 */
export interface AuthoredVersion {
  name: string | null
  digest: string
  submitted_at: string
  state: 'draft' | 'published' | 'discarded'
  state_at: string | null
  is_current: boolean
  file_count: number
  total_bytes: number
}

export interface AuthoredFile {
  relpath: string
  sha256: string
  size: number
  is_binary: boolean
}

/** One skill of the author's own, with every version it has and the one the address named. */
export interface AuthoredSkill {
  namespace: { slug: string }
  name: string
  title: string
  description: string
  visibility: string
  frontmatter: Record<string, unknown>
  version: AuthoredVersion
  versions: AuthoredVersion[]
  files: AuthoredFile[]
  resources: { body: string }
}

/**
 * A hunk's lines, each prefixed by unified diff's own `' '`, `'-'` or `'+'`.
 *
 * The prefix is read rather than the position: that is what makes the same lines render correctly as
 * plain text, and it is why the view does not have to guess which side a line belongs to.
 */
export interface DiffHunk {
  header: string
  lines: string[]
}

/**
 * One changed file.
 *
 * `hunks` null is "not rendered" — a binary file, one past the server's size cap, or one the
 * response's budget ran out before — and it is not the same as `[]`, which means rendered and
 * changed in no line. `added` and `removed` are null exactly when `hunks` is: they are counted from
 * the hunks, so a file that was not diffed has no count rather than a count of zero.
 */
export interface DiffFile {
  relpath: string
  status: 'added' | 'removed' | 'modified'
  binary: boolean
  added: number | null
  removed: number | null
  hunks: DiffHunk[] | null
}

/**
 * Two versions compared.
 *
 * `from` is null for a skill nothing has been published from: the comparison is against nothing, so
 * every file is an addition. `from` and `to` are address suffixes — a version name, or `sha256:…`
 * for a version whose author declared none — which is what a page turns back into a label and a
 * link. `truncated` means the server cut something — a page that renders it must say so rather than
 * present a partial difference as the whole one.
 */
export interface SkillDiff {
  from: string | null
  to: string
  truncated: boolean
  files: DiffFile[]
}

/** What publishing or discarding a version did. `changed` is false when nothing was written. */
export interface VersionAction {
  /** The suffix the write named: the version's name, or its digest when it has none. */
  version: string
  state: string
  live_at: string | null
  changed: boolean
}

/** One API key, as the console lists it (ADR 0038). **Never the secret** — see {@link IssuedKey}. */
export interface ApiKey {
  id: string
  name: string
  /** What the person wrote about why it exists. Null is an answer: nothing was written. */
  note: string | null
  /** Space-separated, the same vocabulary as a token's `scope`: `skills:read skills:write`. */
  scope: string
  expires_at: string | null
  /** Throttled, so it can trail the truth by up to the server's window. */
  last_used_at: string | null
  revoked_at: string | null
  created_at: string
}

/**
 * What this deployment does about expiry.
 *
 * Read from the listing rather than assumed, because the create form cannot guess it: the design
 * preselects 90 days, but a deployment that turned expiry off issues keys that never expire.
 *
 * **`lifetime_days` is both the default and the ceiling** (ADR 0042): it is the date the form
 * preselects and the last date the server will issue, so the picker offers it as its `max` — and the
 * preselect is exactly the boundary rather than inside it, because the server's ceiling is the end of
 * that *day* (`23:59:59`, in the zone this form writes dates in) and not "now plus the duration",
 * which would refuse the form's own default by however many seconds of today have gone by. It was
 * called `default_lifetime_days` while it was only the first of those.
 */
export interface ApiKeyPolicy {
  expires: boolean
  lifetime_days: number
  /**
   * The last day this deployment issues, as `YYYY-MM-DD` in the zone this form writes dates in, or
   * null when expiry is off.
   *
   * **Read from the server rather than recomputed here.** The bound is a fact about the deployment,
   * and two clocks do not agree: a form that worked out "90 days from today" locally would refuse
   * its own preselected date for however far the browser is ahead of the server, in the seconds
   * around a day boundary. This is also why the preselected day is this one — it is exactly the
   * boundary, so nothing the picker offers can be refused.
   */
  latest_day: string | null
}

export interface ApiKeys {
  keys: ApiKey[]
  policy: ApiKeyPolicy
}

/** A key that was just created: the one response that carries the secret. Shown once, then gone. */
export interface IssuedKey {
  key: ApiKey
  token: string
}

/** What the create form sends. `expires_at` null asks the server for this deployment's default. */
export interface NewKey {
  name: string
  note: string | null
  scopes: string[]
  expires_at: string | null
}

/**
 * One device this account is signed in on (ADR 0043).
 *
 * **`device_id` is not a session id, and that is the whole reason it exists.** A session id is worth
 * as much as the cookie carrying it, so the server never sends one: a row is addressed by a ULID it
 * minted for the purpose. Everything else here is what the login left behind — the address it came
 * from, the User-Agent it arrived with, and the two times the framework already keeps.
 */
export interface LoginSession {
  /**
   * The handle this row is revoked by. **Null for a session created before this version**, which
   * carried no stamp: such a row can be seen and cannot be ended, and the screen is what decides
   * that a row with no handle draws no button rather than a broken one.
   */
  device_id: string | null
  /**
   * The device, as far as the server could tell — `Chrome · macOS`, or the User-Agent verbatim when
   * it recognised none of it. **Empty when the client sent no User-Agent at all**, and the screen
   * draws no second line for that rather than a word of its own.
   */
  device: string
  /** Where the login came from. The most effective answer to "is this machine mine?". */
  address: string | null
  logged_in_at: string
  last_seen_at: string
  /** Whether this is the session reading the page — a boolean, because the id may not be sent. */
  current: boolean
}

export interface LoginSessions {
  sessions: LoginSession[]
}

/** What "end every other device" ended — the number, so the page can say what happened. */
export interface SessionsRevoked {
  revoked: number
}
