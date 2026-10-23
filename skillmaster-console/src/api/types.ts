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
  /** When the whole skill stopped serving (`skill.offline_at`), or null while it is online. */
  offline_at: string | null
  current: VersionRef | null
  /** Versions waiting to be published or discarded — the one thing no other listing says. */
  drafts: number
  /** The most recently submitted one of those, or null when there are none — what the count links to. */
  draft: VersionRef | null
  /**
   * The card's "更新于": the current published version's submission time, or the newest draft's for
   * a skill nothing has been published from. **Null when every version has been discarded** — the
   * row still exists and the page says so. This is what the list is ordered by.
   */
  updated_at: string | null
  /**
   * When the skill was soft-deleted, or null on the live list. The deleted list shows
   * "删除于 …" from this instead of "更新于 …".
   */
  deleted_at: string | null
}

export interface AuthoredSkills {
  skills: AuthoredSkillSummary[]
}

/**
 * The five rungs a skill can be shared at, low to high (ADR 0047). Every rung includes the ones
 * below it. `owner` is not one of them: owning the namespace is a relationship, not a grant.
 */
export type Rung = 'invoke' | 'browse' | 'write' | 'maintain' | 'admin'

/** The caller's own standing, as the author detail reports it. */
export type Standing = Rung | 'owner'

/**
 * Who a skill is shared with, and how much (ADR 0034).
 *
 * `handle` is the username rather than a user id: this is a list a person reads while deciding
 * whether to take somebody's access away, and a list of ULIDs is one they cannot act on.
 */
export interface SkillGrant {
  handle: string
  role: Rung
  /** When access was first given. Changing the role does not move it. */
  created_at: string
}

/** One page of the collaborator list, plus what a pager needs (the design's own `q` / `p`). */
export interface SkillGrants {
  grants: SkillGrant[]
  total: number
  page: number
  page_size: number
}

/** One account waiting for a rung (ADR 0052). A request grants nothing until it is approved. */
export interface AccessRequest {
  handle: string
  wanted_role: Rung
  created_at: string
}

export interface AccessRequests {
  requests: AccessRequest[]
  total: number
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
export type VersionStatus = 'draft' | 'live' | 'history' | 'revoked' | 'discarded'

export interface AuthoredVersion {
  /** The version number. Null for a draft and for a v6-before row that never got one. */
  name: string | null
  digest: string
  /**
   * The author's name for this version (`draft_name`), required on submission and frozen once
   * published. Null only on rows submitted before v6.
   */
  draft_name: string | null
  /**
   * The row's own id (ADR 0063): an eight-character version code, or a v6 26-character ULID. It is
   * 「版本码」 in the console and the address suffix for a draft or discarded row.
   */
  code?: string | null
  /**
   * The same value, under v6's name. Kept because the field is still on the wire for drafts; read
   * only when `code` is absent (a response from a server that predates v7).
   */
  draft_id: string | null
  /** The version's own title from `SKILL.md` — content, no longer the row's note. */
  title: string
  /** What changed in this version (`changelog`); null when the author wrote none. */
  note: string | null
  submitted_at: string
  /**
   * The handle that submitted it, or null when the account no longer resolves. Carried for two
   * reasons: the row says who, and 「写入」 may discard only their own draft.
   */
  submitted_by: string | null
  state: 'draft' | 'published' | 'discarded' | 'revoked'
  /** The derived five-value status (TD §派生状态 `status`). Never stored. */
  status: VersionStatus
  state_at: string | null
  /** The first time this version went live; null while it is a draft or was discarded. */
  first_published_at: string | null
  is_current: boolean
  file_count: number
  total_bytes: number
  /** Why a `revoked` version was delisted, when the maintainer wrote one. */
  revoked_reason: string | null
}

export interface AuthoredFile {
  relpath: string
  sha256: string
  size: number
  is_binary: boolean
}

/** One edit the editor sends: write a file, delete one, or rename one (v8 TD §接口). */
export interface DraftEditBody {
  op: 'write' | 'delete' | 'rename'
  path?: string
  text?: string
  from?: string
  to?: string
}

/** What a create or an edit answers with: the row as it now reads, and its manifest. */
export interface DraftResponse {
  version: AuthoredVersion
  files: AuthoredFile[]
}

/** One skill of the author's own, with every version it has and the one the address named. */
export interface AuthoredSkill {
  namespace: { slug: string }
  name: string
  title: string
  description: string
  /**
   * Lifted from the frontmatter by the server (metadata.when_to_use first, top-level as fallback),
   * the same rule `/metadata` and `skill show` use. The page no longer parses the document itself.
   */
  when_to_use: string | null
  /** The author's compatibility line, lifted for the same reason; null when absent. */
  compatibility: string | null
  visibility: 'private' | 'public'
  /** The rung a stranger gets when the skill is public; null when private (ADR 0048). */
  public_rung: Rung | null
  /** When the whole skill stopped serving (`skill.offline_at`), or null while it is online. */
  offline_at: string | null
  /**
   * The caller's own standing — `owner`, or one of the five rungs. The page draws only the sections
   * this can reach (ADR 0051), which is why the server says it rather than leaving the page to probe.
   */
  rung: Standing
  /**
   * The rung this caller has asked for and is waiting on, or null. Without it the page's
   * 「等待处理」 could not survive a reload — its own memory of the click is not a fact.
   */
  pending_rung: Rung | null
  frontmatter: Record<string, unknown>
  /** Null below `browse` (no manifest) and when nothing the caller may see resolved (ADR 0051). */
  version: AuthoredVersion | null
  versions: AuthoredVersion[]
  files: AuthoredFile[]
  resources: { body: string } | null
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
  /** The file's size when the server knows it; null only when it does not. */
  size: number | null
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
  /** The cursor for the next batch of file contents, or null when this was the last one. */
  next: string | null
  files: DiffFile[]
}

/** What publishing or discarding a version did. `changed` is false when nothing was written. */
export interface VersionAction {
  /** The suffix the write named: the version's name, its draft pin, or its digest. */
  version: string
  state: string
  live_at: string | null
  changed: boolean
  /** The version number assigned by a publish, when the target did not have one yet. */
  release_name?: string | null
  /** The author's name for the version, readable after publish too. */
  draft_name?: string | null
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
 * One device this account is signed in on (ADR 0043), and since v4 one CLI login as well.
 *
 * **Two kinds of login share this record, and the screen draws one row for both** (`technical-design.md`
 * v4 Q6): the two have the same rulers and the same way out, so they are the same list and the same
 * count. `kind` is what the screen branches on, and the only thing it branches on — not to draw a
 * mark (the design's rule for that screen is that an active row says nothing about itself) but to
 * pick the address the 退出 button posts to.
 *
 * **`device_id` is not a session id, and that is the whole reason it exists.** A session id is worth
 * as much as the cookie carrying it, so the server never sends one: a row is addressed by a ULID it
 * minted for the purpose, and a CLI login by the id of the authorization it is. Everything else here
 * is what the login left behind — the address it came from, how the device reads, and the two times.
 */
export interface LoginSession {
  /** Which plane ends this row. `browser` posts to `/web/sessions/{id}/revoke`; `cli` does not. */
  kind: 'browser' | 'cli'
  /**
   * The handle this row is revoked by. **Null for a session created before this version**, which
   * carried no stamp: such a row can be seen and cannot be ended, and the screen is what decides
   * that a row with no handle draws no button rather than a broken one. Never null on a `cli` row.
   */
  device_id: string | null
  /**
   * What the device is, as far as the server could tell — `Chrome · macOS`, or the User-Agent
   * verbatim when it recognised none of it, or `CLI · <the name the person typed>`. **Empty when the
   * client sent no User-Agent at all**, and the screen draws no second segment for that rather than
   * a word of its own.
   */
  device: string
  /**
   * Where the login came from. The most effective answer to "is this machine mine?" — and for a CLI
   * login, where it was approved from, which is the console visitor's address rather than the
   * machine's (it has none).
   */
  address: string | null
  logged_in_at: string
  /**
   * When it was last active. For a CLI login approved but not used since, this is the approval
   * itself; the server never sends nothing here, because a row with an empty 最近活动 is not a state
   * the design draws.
   */
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

/**
 * The four parameters the login-request screen read off its own address, which it sends back unread
 * (`technical-design.md` v4 §接口).
 *
 * **Snake case, and the names are the address's own** — `client_id`, `code_challenge` — because the
 * screen neither invents nor interprets any of them: the CLI put them on the address it opened, and
 * the server decides what each may mean. `name` is the one thing the person supplies, and the one
 * thing this request is really about.
 */
export interface CliSignIn {
  name: string
  client_id: string
  scope: string
  state: string
  code_challenge: string
}

/** A refusal, which carries no name because a refusal creates no row for one to belong to. */
export interface CliSignInRefusal {
  client_id: string
  state: string
}

/**
 * Where the browser goes next.
 *
 * The CLI's own loopback address, built by the server from the registered client row and never from
 * the request: a redirect target the caller could choose would be an open redirect with a one-time
 * code on the end of it (ADR 0028). Both buttons answer with this one shape, so the page never has to
 * know two — the difference between agreeing and declining is entirely in the address.
 */
export interface CliSignInRedirect {
  redirect_to: string
}

/**
 * What a submission answers (v9): the existing `SubmitResponse` shape, narrowed to the fields the
 * import and create dialogs read.
 *
 * `draft_id` is present only for a draft — the version number retired from the wire in v6, and the
 * row id is what a draft is addressed by (ADR 0063). A replay answers with whatever row already
 * holds that digest, so `state` can be anything three states.
 */
export interface SubmitResponse {
  namespace: string
  name: string
  created: boolean
  skill_created: boolean
  version: {
    name: string | null
    draft_name: string | null
    note: string | null
    draft_id: string | null
    digest: string
    file_count: number
    total_bytes: number
    submitted_at: string
    state: string
  }
}

/*
 * The v14 library and tag layer (technical-design.md §响应形状). A card is the same shape in both
 * scopes and in `/web/library`; `source` is null in the public scope unless the caller already has
 * it, and `my_tags` is empty unless the library entry is effective.
 */

/** Where a library entry came from, in the display's own precedence. Null means "not in my library". */
export type SkillSource = 'created' | 'shared' | 'joined'

/** The state a row shows: a live version, a draft, an offline skill, or a soft-deleted one. */
export type SkillDisplayState = 'live' | 'draft' | 'offline' | 'deleted'

/** The one shelf a skill sits on. */
export interface CategoryRef {
  key: string
  name: string
}

/**
 * One word of `GET /web/categories` (the platform vocabulary).
 *
 * `state` decides whether a word can be newly assigned; `public_total` is how many discoverable
 * skills are on that shelf. The console reads this list rather than carrying its own copy.
 */
export interface CategoryRow {
  key: string
  name: string
  state: 'enabled' | 'stopped' | 'retired'
  public_total: number
}

export interface CategoryVocabulary {
  categories: CategoryRow[]
}

/** One word of `GET /web/public-tags`, with the number of discoverable skills carrying it. */
export interface PublicTagVocabularyRow {
  key: string
  name: string
  public_total: number
}

export interface PublicTagVocabulary {
  tags: PublicTagVocabularyRow[]
}

/** A public tag as a skill carries it; `key` is the normalized name and `name` the author's spelling. */
export interface PublicTagRef {
  key: string
  name: string
}

/** One of the caller's own tag definitions, as attached to a skill. */
export interface MyTagRef {
  id: string
  name: string
}

/**
 * One row of a library or discovery list.
 *
 * `updated_at` is the projected "updated" expression (TD §谓词): the live version's submission, or
 * the newest draft's, or the skill's creation. It is null only when every version was discarded.
 */
export interface SkillCard {
  id: string
  namespace: string
  name: string
  description: string
  when_to_use: string | null
  source: SkillSource | null
  state: SkillDisplayState
  version: string | null
  updated_at: string | null
  deleted_at: string | null
  category: CategoryRef | null
  public_tags: PublicTagRef[]
  my_tags: MyTagRef[]
}

/** `GET /web/library`: the card plus the three counts the page's empty states are read from. */
export interface LibraryPage {
  skills: SkillCard[]
  page: number
  page_size: number
  total_entries: number
  effective_entries: number
  filtered_total: number
}

/**
 * `GET /web/discover`: the public results, paged at 6.
 *
 * **This one carries a total**, unlike the keyset listings: the console draws the design's numbered
 * pager, which needs to know how many pages there are (TD §响应形状).
 */
export interface DiscoverResults {
  skills: SkillCard[]
  p: number
  page_size: number
  total: number
}

/** `GET /web/discover/recommended`: one batch, and the cursor for the next one (null at the end). */
export interface RecommendedPage {
  skills: SkillCard[]
  next_cursor: string | null
}

/** One row of `GET /web/tags` (the private tab). */
export interface MyTagUsage {
  id: string
  name: string
  description: string | null
  /** Effective attachments. */
  used_in: number
  /** Attachments that are dormant now; the delete sheet's second sentence is about them. */
  dormant_in: number
}

export interface MyTagUsages {
  tags: MyTagUsage[]
}

/** What a definition write echoes. A new definition's `used_in` is zero. */
export interface MyTagDefinition {
  id: string
  name: string
  description: string | null
  used_in?: number
}

/** Every organization write answers "did anything change" rather than 204 (TD §响应形状). */
export interface Changed {
  changed: boolean
}

/** One row of `GET /web/tags?tab=public`: the author's own public words. */
export interface PublicTagUsage {
  key: string
  name: string
  used_in: number
}

export interface PublicTagUsages {
  tags: PublicTagUsage[]
}

/**
 * `GET /web/skills/{ns}/{name}/tags`.
 *
 * `my_tags.reason` is `not_in_library` or `dormant`; the section is drawn only when `readable`.
 */
export interface SkillTagGroups {
  category: CategoryRef | null
  public_tags: PublicTagRef[]
  my_tags: {
    readable: boolean
    reason: 'not_in_library' | 'dormant' | null
    tags: MyTagRef[]
  }
  /**
   * The four facts the information page's join/remove matrix is read from, all computed by the
   * same predicates the server uses: where the entry comes from, whether it is effective, whether
   * it can be joined now, and whether its join reference can be removed.
   */
  library: {
    source: SkillSource | null
    active: boolean
    joinable: boolean
    can_remove: boolean
  }
}
