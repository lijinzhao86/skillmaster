/**
 * Versions, as an address spells them and as a page shows them.
 *
 * ADR 0033, ADR 0063 and v6/v7: a version number is assigned at publish time; a draft or discarded
 * row has no version number and is addressed by its row id (v7's eight-character version code, or a
 * v6 26-character ULID); a published version without a number (an old row) is addressed by its
 * content digest. A version also carries an author-facing *name* (`draft_name`) and a note —
 * neither is part of the address.
 *
 * Every page has to answer the same questions about a version: what suffix its address carries, what
 * to call it on screen, and which of the five derived statuses it is. Answered here once, because a
 * second answer would eventually disagree with the first.
 */

/**
 * SemVer 2.0.0's official grammar, unchanged, mirroring the server's own validator.
 *
 * A suffix is a version name, a prefixed digest, or a row id; one that is none of those names
 * nothing, which is why the address parser folds it into "no version" rather than passing it on.
 */
const SEMVER =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$/

/**
 * A row id: eight Crockford-base32 characters (ADR 0063), or the 26 a v6 ULID has. No `draft:`
 * prefix — the console's address grammar is the bare id, and the prefix is an input spelling only
 * the CLI still accepts for old builds.
 */
// Crockford base32. A ULID's first character is part of its 48-bit timestamp and can only be 0-7;
// the 8-character code has no such limit. The server's Ulid.isValid draws the same line.
const ROW_ID = /^[0-9A-HJKMNP-TV-Z]{8}$|^[0-7][0-9A-HJKMNP-TV-Z]{25}$/

/** Whether a suffix after `@` names a version: a semver, a digest, or a row id. */
export function isVersionSuffix(suffix: string): boolean {
  return SEMVER.test(suffix) || /^sha256:[0-9a-f]{64}$/.test(suffix) || ROW_ID.test(suffix)
}

/**
 * A version's address suffix: a draft's row id, a published version's name, or its digest.
 *
 * **The state decides, not the field.** A draft (or a discarded row, which was a draft) is
 * addressed by its stable row id — editing or renaming it must not move its address (TD §草稿的
 * 名字). A published version is addressed by its number, or by its digest when it never got one;
 * its row id is displayed as 「版本码」 but is not an address (ADR 0063: the consumption plane does
 * not resolve it). The API sends `code` on every row, which is why testing for the field alone
 * would turn every published address into a row id.
 */
export function versionSuffix(version: {
  name: string | null
  digest: string
  state?: string
  status?: VersionStatus
  code?: string | null
  draft_id?: string | null
}): string {
  const rowAddressed = version.state === 'draft' || version.state === 'discarded' ||
    version.status === 'draft' || version.status === 'discarded'
  if (rowAddressed) {
    // `draft_id` is the pre-v7 field with the same value; it is read only for a response that
    // predates `code`.
    if (version.code != null && version.code !== '') {
      return version.code
    }
    if (version.draft_id != null && version.draft_id !== '') {
      return version.draft_id
    }
    return version.name ?? version.digest
  }
  return version.name ?? version.digest
}

/**
 * Whether an address suffix names this version.
 *
 * A published version answers to two spellings — its number and its digest — because the server
 * accepts both (TD §地址/钉). A draft or discarded row answers only to its bare row id: its digest
 * is not an address it publishes, and the CLI refuses that spelling locally for the same reason.
 * Matching only {@link versionSuffix} made `@sha256:…` fail for every version that had a number.
 */
export function answersPin(
  version: {
    name: string | null
    digest: string
    state?: string
    status?: VersionStatus
    code?: string | null
    draft_id?: string | null
  },
  pin: string,
): boolean {
  const status = version.status ?? (version.state === 'draft' ? 'draft' : undefined)
  const rowAddressed = version.state === 'draft' || version.state === 'discarded' ||
    status === 'draft' || status === 'discarded'
  if (rowAddressed) {
    // A draft or discarded row answers to its row id only. The version code is displayed, but for
    // these two states it *is* the address, so the two spellings are one.
    return pin === versionSuffix(version)
  }
  // A published row answers to its number and its digest, never to its code: the code is a display
  // identifier, and the consumption plane does not resolve it (ADR 0063).
  return (version.name !== null && pin === version.name) || pin === version.digest
}

/**
 * What to call a version on screen, from its address suffix: its name, or an abbreviated digest.
 *
 * The abbreviation is git's — the first 8 hex characters — and deliberately not one of our own: a
 * person who reads `a1b2c3d4` already reads it as a hash, where `@1` would be the integer alias
 * ADR 0033 retired. Only the digest is abbreviated; a name is the author's own and is shown whole.
 * A draft pin is a handle, not a version number, and is shown as it is.
 */
export function versionLabel(suffix: string): string {
  return suffix.startsWith('sha256:') ? suffix.slice('sha256:'.length, 'sha256:'.length + 8) : suffix
}

/** The five derived statuses of a version (TD §派生状态 `status`). */
export type VersionStatus = 'draft' | 'live' | 'history' | 'revoked' | 'discarded'

/** The state keys the version list's filter writes into the address (`filter=`). */
export type VersionFilterKey = 'all' | 'live' | 'draft' | 'published' | 'revoked' | 'discarded'

/** A version's status, with `state` as the fallback for a response that predates the field. */
export function statusOf(version: {
  status?: VersionStatus
  state: string
  is_current: boolean
}): VersionStatus {
  if (version.status !== undefined) {
    return version.status
  }
  if (version.state === 'draft') {
    return 'draft'
  }
  if (version.state === 'revoked') {
    return 'revoked'
  }
  if (version.state === 'discarded') {
    return 'discarded'
  }
  return version.is_current ? 'live' : 'history'
}

/** The filter key a status belongs to. `history` filters under the address's `published`. */
export function filterKeyOf(status: VersionStatus): VersionFilterKey {
  return status === 'history' ? 'published' : status
}

/** The one word a status is called by, in the list, the picker and the single-version page. */
export function statusText(status: VersionStatus): string {
  switch (status) {
    case 'live':
      return '线上'
    case 'draft':
      return '草稿'
    case 'revoked':
      return '已下架'
    case 'discarded':
      return '已丢弃'
    default:
      return '历史版本'
  }
}

/** The chip class the status wears. History and discarded are both plain. */
export function statusClass(status: VersionStatus): string {
  switch (status) {
    case 'live':
      return 'is-live'
    case 'draft':
      return 'is-draft'
    case 'revoked':
      return 'is-revoked'
    default:
      return 'is-plain'
  }
}

/** The tag tone a version picker option carries; the menu's own vocabulary. */
export function statusTone(status: VersionStatus): string {
  switch (status) {
    case 'live':
      return 'live'
    case 'draft':
      return 'draft'
    case 'revoked':
      return 'revoked'
    default:
      return ''
  }
}

/**
 * The author's name for a version, or the version number when an old row has none.
 *
 * **A caller's identity is the version number** — `draft_name` is author-plane only and the
 * consumption contract never carries it (PRD 范围 #0). So `invoke` gets `@1.2.3`, never a nickname.
 */
export function versionTitle(
  version: {
    name: string | null
    draft_id?: string | null
    draft_name?: string | null
    digest?: string
  },
  rung: string,
): string {
  if (!atLeastBrowse(rung)) {
    if (version.name !== null) {
      return `@${version.name}`
    }
    return version.digest === undefined ? '' : `@${versionLabel(version.digest)}`
  }
  // An old row may have a number but no name: it shows the number. A draft with neither has nothing
  // to show, and the page leaves it blank rather than inventing a label (design `versionTitleOf`,
  // T32 ⑧). A **published** row with neither exists too — the pre-v6 gateway versions — and there
  // the digest is not invented, it is the only address that row has (TD §草稿的身份); a picker with
  // an empty option would be unusable. `draft_id` is how the two are told apart.
  if (version.draft_name || version.name) {
    return version.draft_name || version.name || ''
  }
  if (version.draft_id != null) {
    return ''
  }
  return version.digest === undefined ? '' : `@${versionLabel(version.digest)}`
}

/** `true` for every rung that reaches the author plane (`browse` and above). */
function atLeastBrowse(rung: string): boolean {
  return rung !== 'invoke'
}

/** The display title with its version number, for a sentence that needs both. */
export function versionRef(
  version: {
    name: string | null
    state?: string
    status?: VersionStatus
    code?: string | null
    draft_id?: string | null
    draft_name?: string | null
  },
  rung: string,
): string {
  const title = versionTitle(version, rung)
  if (version.name !== null && version.draft_name != null && title !== '') {
    return `${title}（@${version.name}）`
  }
  return title === '' ? versionSuffix({ ...version, digest: '' }) : title
}

/**
 * A stable in-page anchor for a changed file, safe in a fragment regardless of its path.
 *
 * **The sanitised path alone can collide**: `a-b.md` and `a_b.md` both become `a_b_md`, and a tree
 * click would scroll to whichever block came first. A short hash of the real path keeps the two
 * apart; it is stable across renders, which is all an anchor needs.
 */
export function fileAnchorId(relpath: string): string {
  let hash = 0
  for (const character of relpath) {
    hash = (hash * 31 + (character.codePointAt(0) ?? 0)) | 0
  }
  const suffix = (hash >>> 0).toString(36)
  return `vpFile${relpath.replace(/[^A-Za-z0-9_-]/g, '_')}${suffix}`
}

/**
 * What an empty comparison says, which depends on what it was compared against.
 *
 * One sentence, one owner: the small comparison and the 改动 pane must not disagree about whether
 * "no files changed" means "identical" or "nothing to compare with".
 */
export function noDifferenceText(from: string | null, live: boolean): string {
  if (from === null) {
    // Nothing to compare with. Reachable only for an empty version, which a real skill cannot be, so
    // the sentence says what is true rather than naming a baseline that does not exist.
    return '这一版没有文件。'
  }
  return `这一版和${live ? '线上版本' : `@${versionLabel(from)}`}没有区别。`
}

/* --------------------------------------------------------------- the route */

export interface VersionRoute {
  /** The pin the version tab is showing, when the address names a readable one. */
  version?: string
  /** `sub=diff` / `sub=actions`; absent means 概览. */
  sub?: 'diff' | 'actions'
  /** The comparison baseline, when the address names one. */
  from?: string
  /** The file-list filter inside 改动 (`fq`). */
  fq: string
  /** The single file being read inside 改动 (`file`). */
  file?: string
  /** 「只看文件列表」 (`list=1`). */
  list: boolean
  /** The version list's status filter (`filter`). */
  filter: VersionFilterKey
  /** The version list's search (`q`). */
  q: string
}

const FILTERS: readonly VersionFilterKey[] = [
  'all',
  'live',
  'draft',
  'published',
  'revoked',
  'discarded',
]

/**
 * The version tab's own address parameters, parsed once.
 *
 * **Settings has a `sub` of its own** (`general` / `collaborators` / `details`) and it means
 * something different. They are parsed by different owners on purpose: one function that understood
 * both would treat the other tab's value as a bad input (TD §地址解析按页归口).
 *
 * An unknown `version`/`from` is not a route value; it names no version, and the page falls back to
 * the list / default baseline rather than inventing a state.
 */
export function parseVersionRoute(search: string): VersionRoute {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search)
  const version = params.get('version')
  const from = params.get('from')
  const sub = params.get('sub')
  const file = params.get('file')
  const rawFilter = params.get('filter')
  const filter =
    rawFilter !== null && (FILTERS as readonly string[]).includes(rawFilter)
      ? (rawFilter as VersionFilterKey)
      : 'all'
  return {
    // **Any non-empty `version` is kept**, known or not: an address that names nothing is the
    // single-version page's 「没有这一版」, and dropping it here would land the reader on the list
    // instead — a different screen answering a different question (v7 TD §网页).
    ...(version !== null && version !== '' ? { version } : {}),
    ...(sub === 'diff' || sub === 'actions' ? { sub } : {}),
    ...(from !== null && isVersionSuffix(from) ? { from } : {}),
    fq: params.get('fq') ?? '',
    ...(file !== null && file !== '' ? { file } : {}),
    list: params.get('list') === '1',
    filter,
    q: params.get('q') ?? '',
  }
}
