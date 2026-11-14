/**
 * The shapes that travel on the wire.
 *
 * snake_case in both directions, because that is what the server speaks: its Jackson naming
 * strategy is SNAKE_CASE, so a Java record component called `captchaId` is `captcha_id` here. These
 * names are the contract — a typo in one of them is a request the server refuses, and the tests
 * below cannot catch it because they stub the network. See the smoke test in the README.
 */

import type { SignalLicense, SignalOrigin, SignalUsage } from './signals'

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
 * A 200 answer to a question rather than a refusal; `issue` is the code registration would refuse
 * the name with, so the sentence is the one that refusal would get. Null when nothing is wrong.
 */
export interface UsernameAvailability {
  available: boolean
  issue: string | null
}

/** Whether a code send from this browser would be asked for a captcha — advice, not a promise. */
export interface CaptchaRequirement {
  required: boolean
}

export interface ErrorDetail {
  field: string
  issue: string
  /** v39 size refusals; snake_case is the server's one global wire convention. */
  actual_bytes?: number
  limit_bytes?: number
  /** v59 text refusals count Unicode code points, not bytes or UTF-16 units. */
  actual_code_points?: number
  limit_code_points?: number
}

/** The envelope every 4xx and 5xx carries; `details` is always present. */
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
  /** v61: the 下线中 ramp's due date while it is ahead, or null. */
  offline_pending_until: string | null
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

// The notification floor's wire types live in their own file to keep this one inside its limit.
export type {
  InvitationResult,
  Notification,
  NotificationKind,
  NotificationPage,
  NotificationState,
  SkillInvitation,
  SkillInvitations,
} from './notificationTypes'

export type { AccessRequest, AccessRequests } from './accessRequestTypes'

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

/**
 * What a copy answers with (v54): the new skill's address, its first draft, and the source.
 *
 * `version` is null because a draft has no version number yet (ADR 0055); `draft` is the short
 * code the editor or the version page addresses it by.
 */
export interface CopySkillResponse {
  skill: { namespace: string; name: string }
  draft: string
  draft_name: string
  version: string | null
  source: { address: string; version: string }
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
  /** Whether the namespace may be public (ADR 0146). Absent from an older server means yes. */
  can_publish_public?: boolean
  /** When the whole skill stopped serving (`skill.offline_at`), or null while it is online. */
  offline_at: string | null
  /**
   * v61: the 下线中 ramp's due date while the skill still serves; absent when it is not in a ramp
   * (an older server never sends it). The page derives its three-way service state from this and
   * `offline_at`.
   */
  offline_pending_until?: string | null
  /** v61: how many people outside the owner hold a grant; absent on an older server. */
  grantees?: number
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
  /** v49: where the skill came from; absent on an older server or when none is recorded. */
  origin?: SignalOrigin | null
  /** v49: the license that applies to the selected version; absent when there is no row. */
  license?: SignalLicense | null
  /** v49: the aggregate use signal; absent when nobody has used or joined it. */
  usage?: SignalUsage | null
  /**
   * v54: where this skill was copied from, or absent when it was written rather than copied.
   * A snapshot of the source address and version at copy time (ADR 0173).
   */
  copied_from?: { address: string; version: string } | null
  /** v66: the platform's one-line expected result; absent when there is none. */
  outcome?: string | null
  /** v66: the displayed attributes, short names only; absent when there are none. */
  labels?: SkillLabel[]
}

/**
 * One displayed attribute (v66): **the dimension (`key`) and the value** — `key + ':' + value` is
 * the filter key `?label=` takes (`runtime:python`) — the short name the console draws, and the
 * full sentence the extractor recorded.
 *
 * **Only `label` reaches the interface** — chips, tooltips and screen readers all use it. `full`
 * stays in the data for `skills labels` and the sampling report (2026-11-13 用户定).
 * `filterable: false` means the word is displayed but cannot be filtered on; the chip must not be
 * a link then, or pressing it would ask the server for an unknown label. The field is optional so
 * an older server (one that sends `labels` without it) reads as filterable rather than the reverse.
 */
export interface SkillLabel {
  key: string
  value: string
  label: string
  full: string
  group: string
  filterable?: boolean
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
 * What this deployment does about expiry (ADR 0171).
 *
 * **The maximum and the default are product constants; only 「不过期」 is a deployment decision.**
 * `default_days` seeds the preselect (90); `lifetime_days` is the server's published maximum (365).
 * The six choices themselves are product constants in the form — the console does not hide a preset
 * because a deployment asked it to. `latest_day` is the last day the server will issue, as
 * `YYYY-MM-DD` in the zone this form writes dates in.
 *
 * **Read from the server rather than recomputed here.** The bound is a fact about the product, and
 * two clocks do not agree: a form that worked out "365 days from today" locally would refuse its
 * own last legal date for however far the browser is ahead of the server, in the seconds around a
 * day boundary. The 365 preset therefore sends `latest_day` itself rather than a locally computed
 * day (see `KeyCreateForm.expiresAt`).
 */
export interface ApiKeyPolicy {
  allow_no_expiry: boolean
  default_days: number
  lifetime_days: number
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

/**
 * What the create form sends.
 *
 * `expires_at: null` asks for the product default (90 days); `never_expires: true` is the only way
 * to ask for 「不过期」, because the two are different requests and one `null` cannot mean both
 * (ADR 0171 §决定 4).
 */
export interface NewKey {
  name: string
  note: string | null
  scopes: string[]
  expires_at: string | null
  never_expires: boolean
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
 * One CLI question, as its page sees it (v68).
 *
 * **Snake case, because this is the wire and not the screen** — `created_at`, `expires_at` are the
 * server's spelling, and the page formats them the way it formats every other timestamp.
 *
 * `from` is where the request came from, as the server saw it, or null when the socket did not expose
 * one. It is the one fact on the page the CLI cannot make up, and the page omits the line rather than
 * guessing when it is absent.
 */
export interface CliDeviceSignInView {
  status: 'pending' | 'approved' | 'denied' | 'expired' | 'used'
  name: string
  client: string
  scopes: string[]
  from: string | null
  created_at: string
  expires_at: string
  /**
   * How long ago the question was asked, counted by the server rather than by this browser.
   * Null when a server older than the field answers; the line then leaves the age out.
   */
  age_minutes: number | null
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

/**
 * The state a row shows: a live version, a draft, a skill inside its 下线中 ramp (still serving),
 * an offline skill, or a soft-deleted one (v61).
 */
export type SkillDisplayState = 'live' | 'draft' | 'pending' | 'offline' | 'deleted'

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
  /**
   * v59: the caller's own sentence, when the library entry is effective. It outranks the author's
   * in the preview and in search; absent otherwise.
   */
  my_when_to_use?: string | null
  /** v59: whether this search result was promoted by that sentence. */
  my_when_to_use_matched?: boolean
  source: SkillSource | null
  /**
   * The caller's own rung on this skill, for the row's chip (v35); null or absent when they own it
   * (owning it is already said by the namespace) and when the caller has no relationship to the
   * skill at all (a discovery card — "your rung" is a library fact). A joined skill's value is the
   * rung it hands to everybody.
   */
  standing?: Rung | null
  state: SkillDisplayState
  /** v61: the 下线中 ramp's due date, present only while `state` is `pending`. */
  offline_pending_until?: string | null
  version: string | null
  updated_at: string | null
  deleted_at: string | null
  category: CategoryRef | null
  public_tags: PublicTagRef[]
  my_tags: MyTagRef[]
  /**
   * v66: the platform's one-line expected result and the displayed attributes. **The outcome does
   * not go on a result row** — the row answers "what is this", and the information page answers
   * "what do I get". The attributes are drawn on the row's third line, left of the public tags.
   */
  outcome?: string | null
  labels?: SkillLabel[]
  /** v49: the compact selection signals; absent on an older server. */
  origin_label?: string | null
  origin_kind?: string | null
  /** The repository or platform address shown beside the label, and in the hover title. */
  origin_locator?: string | null
  /** The page the origin label opens when clicked. */
  origin_url?: string | null
  license_spdx?: string | null
  license_status?: string | null
  usage_bucket?: string | null
  calls_bucket?: string | null
  last_used?: string | null
  joins?: number | null
}

/** `GET /web/skills/{ns}/{name}/my-when-to-use` (v59). */
export interface MyWhenToUse {
  when_to_use: string | null
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

/**
 * How big a change a draft declares (v18, ADR 0091).
 *
 * **Not a version number.** It names which digit the next publish should move, and the number itself
 * is computed at that moment from whatever is live then — which is why the queue page previews a
 * number and never promises one.
 */
export type ReleaseType = 'major' | 'minor' | 'patch'

/** One waiting draft of `GET /web/publish-queue`. */
export interface QueueDraft {
  /**
   * The version's short code, and the pin a publish takes **bare** — no `draft:` prefix (ADR 0063).
   * It is the same string the batch request's `version` carries.
   */
  code: string
  /** The author-facing name; empty on rows from before v6 had one (the page says 未命名). */
  name: string | null
  release_type: ReleaseType | null
  submitted_at: string
}

/**
 * A skill on the publish queue, with every draft of it that is still waiting.
 *
 * `current` is `null` when the skill has never been published. When the skill *has* been published
 * but that version carries no number (a pre-v11 row), `current.version` is empty — a different fact,
 * and the page says `—` rather than 「从未上线」.
 */
export interface QueueSkill {
  namespace: string
  name: string
  current: { version: string | null; submitted_at: string } | null
  /** Newest first. The page draws them in this order — it does not sort again. */
  drafts: QueueDraft[]
}

/**
 * `GET /web/publish-queue`: every draft waiting on a skill this caller can publish from.
 *
 * `truncated` is true when there were more skills than the read returns (500); the page says so
 * beside its count rather than pretending the list is whole.
 */
export interface PublishQueue {
  skills: QueueSkill[]
  truncated: boolean
}

/** One target of `POST /web/skills/publish-batch`. */
export interface PublishTarget {
  namespace: string
  name: string
  /** The draft's bare code, exactly as the queue handed it out. */
  version: string
  /** What the page showed this row would become — echoed back so the answer can contradict it. */
  predicted?: string
}

/** One line of the batch stream: `type: 'row'`. */
export interface PublishRowResult {
  type: 'row'
  ok: boolean
  namespace: string
  name: string
  /** The number actually assigned — never the prediction. Absent on a failure. */
  version?: string | null
  draft_name?: string | null
  predicted?: string | null
  /** A failure's code, the same one the single publish route would have returned. */
  code?: string
  message?: string
}

/** The stream's last line: `type: 'done'`. */
export interface PublishDone {
  type: 'done'
  ok: number
  failed: number
}
