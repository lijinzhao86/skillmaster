import { request } from './client'
import type {
  AccessRequests,
  ApiResult,
  AuthoredSkill,
  AuthoredSkills,
  Rung,
  SkillDiff,
  SkillGrant,
  SkillGrants,
  VersionAction,
} from './types'

/**
 * The author's own plane (ADR 0031), one function each.
 *
 * Everything here is the caller's own work: the list shows drafts no consumer can see, and the two
 * writes are the only way in the system for a version to become live. That is why they are not in
 * `account.ts` and not on the API plane — a bearer token cannot reach any of them.
 *
 * **The parts of an address are taken separately rather than as one string.** A skill's address is
 * its namespace, its name and optionally a version, and each of them is a value that has to be
 * encoded on its own: the `@` between name and version is the grammar's own character and must stay
 * literal, so that `encodeURIComponent` is applied to the two values and never to the joined result.
 * The encoding itself is `encodeURIComponent` and not the server's own rule, because a name may be
 * non-ASCII: percent-encoding is what a path segment is allowed to carry, and the server decodes it
 * back before routing.
 */
const BASE = '/web/skills'

/**
 * Everything the caller has submitted, drafts included, newest submission first — or the deleted
 * list (ADR 0050).
 *
 * `state` chooses between the two lists rather than filtering one: every row on the deleted list can
 * only be restored, and restoring is the owner's, so that list is ownership's answer alone. Omitted
 * means the live list; an unknown value is a 400 naming it, which is why this takes the two values
 * rather than a bare string.
 */
export function listSkills(state?: 'live' | 'deleted'): Promise<ApiResult<AuthoredSkills>> {
  const query = state === undefined ? '' : `?state=${state}`
  return request<AuthoredSkills>({ method: 'GET', path: `${BASE}${query}` })
}

/** One skill, with every version it has. Omitting the version follows the pointer. */
export function getSkill(
  namespace: string,
  name: string,
  version?: string,
): Promise<ApiResult<AuthoredSkill>> {
  return request<AuthoredSkill>({ method: 'GET', path: skillPath(namespace, name, version) })
}

/**
 * The version's original `SKILL.md`, byte for byte.
 *
 * `responseType: 'text'` because the server serves it as `text/markdown`: it is the author's own
 * document, and parsing it as JSON would report our reading of it as the server's failure.
 */
export function getSkillBody(
  namespace: string,
  name: string,
  version?: string,
): Promise<ApiResult<string>> {
  return request<string>({
    method: 'GET',
    path: `${skillPath(namespace, name, version)}/body`,
    responseType: 'text',
  })
}

/**
 * One file of the version, byte for byte.
 *
 * `responseType: 'text'` for the same reason as the body: the server serves the file's own type, and
 * a `references/schema.json` run through `JSON.parse` on the way in would be reported as the
 * server's failure rather than as our reading of it.
 *
 * Only ever called for a file the manifest did not mark binary — the page knows which those are
 * from the detail it already has, and decoding arbitrary bytes as text is not something to find out
 * about after the fact.
 */
export function getSkillFile(
  namespace: string,
  name: string,
  version: string | undefined,
  relpath: string,
): Promise<ApiResult<string>> {
  return request<string>({
    method: 'GET',
    path: `${skillPath(namespace, name, version)}/files/${encodeRelpath(relpath)}`,
    responseType: 'text',
  })
}

/**
 * A relpath, encoded as a path rather than as one segment: `references/x.md` has to stay two
 * segments, because an encoded slash is rejected by the servlet firewall before routing ever sees
 * it. The server applies the same rule when it advertises one, which is what makes this the
 * inverse of that rather than a second convention.
 */
function encodeRelpath(relpath: string): string {
  return relpath.split('/').map(encodeURIComponent).join('/')
}

/**
 * What changed between two of the skill's versions.
 *
 * `version` and `from` are address suffixes — a version name, or `sha256:…` for a version whose
 * author declared none — because both must be able to name a version that has no name at all
 * (ADR 0033). `from` omitted compares against what is live, which is the question the page is
 * asking. The server answers `from: null` when there is nothing live to compare with, and then every
 * file is an addition — a first submission, shown as what publishing it would add.
 */
export function getSkillDiff(
  namespace: string,
  name: string,
  version?: string,
  from?: string,
  options: { file?: string; cursor?: string } = {},
): Promise<ApiResult<SkillDiff>> {
  // Encoded, and not for tidiness: a version may carry SemVer build metadata, whose `+` means a
  // *space* in a query string — `?from=1.0.0+build.1` would arrive as `1.0.0 build.1` and resolve to
  // nothing. `sha256:…` and `draft:…` encode too, and decode back to themselves on the server.
  const query = new URLSearchParams()
  if (from !== undefined) query.set('from', from)
  if (options.file !== undefined) query.set('file', options.file)
  if (options.cursor !== undefined) query.set('cursor', options.cursor)
  const suffix = query.size === 0 ? '' : `?${query.toString()}`
  return request<SkillDiff>({
    method: 'GET',
    path: `${skillPath(namespace, name, version)}/diff${suffix}`,
  })
}

/**
 * Makes one version the one consumers get. Any non-discarded version, including an older one —
 * which is a rollback, and the server answers it like any other publish.
 *
 * A POST, so it carries the CSRF header; a session cookie alone would make this reachable from
 * another site, and this is the request in the system that most needs a person behind it.
 */
export function publishVersion(
  namespace: string,
  name: string,
  version: string,
  releaseName?: string,
): Promise<ApiResult<VersionAction>> {
  // `release_name` is sent only when a draft is being named. A historical version already has its
  // number, and the server refuses a second one (`version_name_is_assigned`) rather than ignoring it.
  return request<VersionAction>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/publish`,
    body: releaseName === undefined ? { version } : { version, release_name: releaseName },
  })
}

/** Throws a draft away. One-way, and the server refuses anything that is not a draft. */
export function discardVersion(
  namespace: string,
  name: string,
  version: string,
): Promise<ApiResult<VersionAction>> {
  return request<VersionAction>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/discard`,
    body: { version },
  })
}

/** How a version pin travels in a path segment. `draft:` and semver build metadata both encode. */
function versionPath(version: string): string {
  return encodeURIComponent(version)
}

/** A draft's name: complete replacement, only while it is a draft. */
export function updateDraftName(
  namespace: string,
  name: string,
  version: string,
  draftName: string,
): Promise<ApiResult<{ name: string }>> {
  return request<{ name: string }>({
    method: 'PATCH',
    path: `${skillPath(namespace, name)}/versions/${versionPath(version)}/name`,
    body: { name: draftName },
  })
}

/** A draft's note: complete replacement, only while it is a draft. */
export function updateDraftNote(
  namespace: string,
  name: string,
  version: string,
  note: string,
): Promise<ApiResult<{ note: string | null }>> {
  return request<{ note: string | null }>({
    method: 'PATCH',
    path: `${skillPath(namespace, name)}/versions/${versionPath(version)}/note`,
    body: { note },
  })
}

/** Takes one non-current published version out of the consumption plane. */
export function revokeVersion(
  namespace: string,
  name: string,
  version: string,
  reason?: string,
): Promise<ApiResult<VersionAction>> {
  return request<VersionAction>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/versions/${versionPath(version)}/revoke`,
    body: reason === undefined || reason === '' ? {} : { reason },
  })
}

/** Puts a revoked version back among the readable ones. It is not live until published again. */
export function restoreVersion(
  namespace: string,
  name: string,
  version: string,
): Promise<ApiResult<VersionAction>> {
  return request<VersionAction>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/versions/${versionPath(version)}/restore`,
    body: {},
  })
}

/** The whole skill's service switch. Owner only; the version rows and the pointer do not move. */
export function setSkillOffline(
  namespace: string,
  name: string,
  offline: boolean,
): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/${offline ? 'offline' : 'online'}`,
    body: {},
  })
}

/**
 * Who this skill is shared with (ADR 0034, ADR 0047). `/admin` rung or the owner.
 *
 * `q` and `p` are the design's own address parameters: the collaborator list is cut on the server
 * because a client that pulled everything to slice it would be inventing a page the server never
 * agreed to. `q` matches a handle; `p` is 1-based.
 */
export function listGrants(
  namespace: string,
  name: string,
  q?: string,
  p?: number,
): Promise<ApiResult<SkillGrants>> {
  const query = new URLSearchParams()
  if (q !== undefined && q !== '') query.set('q', q)
  if (p !== undefined && p > 1) query.set('p', String(p))
  const suffix = query.size === 0 ? '' : `?${query}`
  return request<SkillGrants>({
    method: 'GET',
    path: `${skillPath(namespace, name)}/grants${suffix}`,
  })
}

/**
 * Shares the skill, or changes the role it is shared with.
 *
 * **One call for both**, because the two are the same write: the row is keyed by the pair, so
 * assigning to it is how a role changes. There is nothing to ask first — and asking would race.
 *
 * The role is a rung of the five-step ladder (ADR 0047), and every rung includes the ones below it:
 * a `browse` reads, a `write` also submits drafts, a `maintain` also publishes and discards, an
 * `admin` also shares and changes visibility. A `viewer` or an `editor` is refused by name.
 */
export function grantSkill(
  namespace: string,
  name: string,
  handle: string,
  role: Rung,
): Promise<ApiResult<SkillGrant>> {
  return request<SkillGrant>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/grants`,
    body: { handle, role },
  })
}

/**
 * Takes the share away.
 *
 * Answers 204 whether or not there was one: the request asks for a state — this person must not have
 * access — and that state holds either way. So there is no "it was already gone" to report, and the
 * caller reloads the list rather than trying to patch a row out of its own copy.
 *
 * The handle is the address's own escaping rule again, one segment this time. A handle is lower-case
 * ASCII by policy, so this will never change what is sent — the encoding is the same call as
 * everywhere else rather than a reason to make an exception.
 */
export function revokeGrant(
  namespace: string,
  name: string,
  handle: string,
): Promise<ApiResult<void>> {
  return request<void>({
    method: 'DELETE',
    path: `${skillPath(namespace, name)}/grants/${encodeURIComponent(handle)}`,
  })
}

/**
 * Soft-deletes the skill (ADR 0050). The owner's only, and reachable only from this plane.
 *
 * A `POST` to an action-shaped route rather than a `DELETE`: the action is reversible and the
 * resource is still there. A caller who can see the skill and does not own it gets a 403 rather than
 * a 404 — its existence is already in their listing, so pretending otherwise would be false.
 */
export function deleteSkill(namespace: string, name: string): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/delete`,
    body: {},
  })
}

/** Clears a soft delete. The same action the deleted list's 恢复 calls; ownership is checked. */
export function restoreSkill(namespace: string, name: string): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/restore`,
    body: {},
  })
}

/**
 * Private, or public at one of the two lowest rungs (ADR 0048).
 *
 * The pair travels together because V19's CHECK makes it one state: `publicRung` is `invoke` or
 * `browse` when the skill is public, and null when it is private.
 */
export function setVisibility(
  namespace: string,
  name: string,
  visibility: 'private' | 'public',
  publicRung: 'invoke' | 'browse' | null,
): Promise<ApiResult<void>> {
  return request<void>({
    method: 'PATCH',
    path: `${skillPath(namespace, name)}/visibility`,
    body: { visibility, public_rung: publicRung },
  })
}

/**
 * Asks for a rung (ADR 0052). Everyone who can see the skill reaches this; the rung must be
 * strictly above the caller's own, which the server refuses with a 400 naming the field.
 */
export function requestAccess(
  namespace: string,
  name: string,
  rung: Rung,
): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/access-requests`,
    body: { rung },
  })
}

/** Withdraws the caller's own pending request. Idempotent. */
export function withdrawAccessRequest(namespace: string, name: string): Promise<ApiResult<void>> {
  return request<void>({
    method: 'DELETE',
    path: `${skillPath(namespace, name)}/access-requests/me`,
  })
}

/** The pending requests on this skill — the `admin` rung's queue, oldest first. */
export function listAccessRequests(
  namespace: string,
  name: string,
): Promise<ApiResult<AccessRequests>> {
  return request<AccessRequests>({
    method: 'GET',
    path: `${skillPath(namespace, name)}/access-requests`,
  })
}

/**
 * Approves one request at a rung the approver chooses, which may differ from the one asked for
 * (ADR 0052 §决定 3). 204: the request is gone and the grant is the answer.
 */
export function approveAccessRequest(
  namespace: string,
  name: string,
  handle: string,
  rung: Rung,
): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/access-requests/${encodeURIComponent(handle)}/approve`,
    body: { rung },
  })
}

/** Denies one request: it disappears and nothing is granted. */
export function denyAccessRequest(
  namespace: string,
  name: string,
  handle: string,
): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/access-requests/${encodeURIComponent(handle)}/deny`,
    body: {},
  })
}

/** `/web/skills/<namespace>/<name>[@<version>]`, where `<version>` is an address suffix. */
export function skillPath(namespace: string, name: string, version?: string): string {
  const suffix = version === undefined ? '' : `@${version}`
  return `${BASE}/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}${suffix}`
}
