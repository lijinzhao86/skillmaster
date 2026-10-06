import { request } from './client'
import type {
  ApiResult,
  AuthoredSkill,
  AuthoredSkills,
  SkillDiff,
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

/** Everything the caller has submitted, drafts included, newest submission first. */
export function listSkills(): Promise<ApiResult<AuthoredSkills>> {
  return request<AuthoredSkills>({ method: 'GET', path: BASE })
}

/** One skill, with every version it has. Omitting the version follows the pointer. */
export function getSkill(
  namespace: string,
  name: string,
  version?: number,
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
  version?: number,
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
  version: number | undefined,
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
 * `from` omitted compares against what is live, which is the question the page is asking. The server
 * answers `from: null` when there is nothing live to compare with, and then every file is an
 * addition — a first submission, shown as what publishing it would add.
 */
export function getSkillDiff(
  namespace: string,
  name: string,
  version?: number,
  from?: number,
): Promise<ApiResult<SkillDiff>> {
  const query = from === undefined ? '' : `?from=${from}`
  return request<SkillDiff>({
    method: 'GET',
    path: `${skillPath(namespace, name, version)}/diff${query}`,
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
  number: number,
): Promise<ApiResult<VersionAction>> {
  return request<VersionAction>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/publish`,
    body: { number },
  })
}

/** Throws a draft away. One-way, and the server refuses anything that is not a draft. */
export function discardVersion(
  namespace: string,
  name: string,
  number: number,
): Promise<ApiResult<VersionAction>> {
  return request<VersionAction>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/discard`,
    body: { number },
  })
}

/** `/web/skills/<namespace>/<name>[@<version>]`. */
export function skillPath(namespace: string, name: string, version?: number): string {
  const suffix = version === undefined ? '' : `@${version}`
  return `${BASE}/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}${suffix}`
}
