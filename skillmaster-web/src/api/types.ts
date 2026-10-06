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

/** One row of the author's listing. `current` is null for a skill nothing has been published from. */
export interface AuthoredSkillSummary {
  /** On every row, so a link to it can be built without having read the session first. */
  namespace: string
  name: string
  title: string
  description: string
  visibility: string
  current: { number: number; digest: string } | null
  /** Versions waiting to be published or discarded — the one thing no other listing says. */
  drafts: number
  /** The highest-numbered one of those, or null when there are none — what the count links to. */
  draft_number: number | null
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
 * One version, and what the author has decided about it.
 *
 * `is_current` is not the same question as `state === 'published'`: a version that was superseded is
 * still published, and its own number still resolves. `state_at` is when it left draft, which is
 * null while it is one.
 */
export interface AuthoredVersion {
  number: number
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
 * every file is an addition. `truncated` means the server cut something — a page that renders it
 * must say so rather than present a partial difference as the whole one.
 */
export interface SkillDiff {
  from: number | null
  to: number
  truncated: boolean
  files: DiffFile[]
}

/** What publishing or discarding a version did. `changed` is false when nothing was written. */
export interface VersionAction {
  number: number
  state: string
  live_at: string | null
  changed: boolean
}
