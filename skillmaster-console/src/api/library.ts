import { request } from './client'
import type {
  ApiResult,
  CategoryVocabulary,
  Changed,
  DiscoverResults,
  LibraryPage,
  MyTagDefinition,
  MyTagUsages,
  PublicTagUsages,
  PublicTagVocabulary,
  RecommendedPage,
  SkillTagGroups,
} from './types'

/**
 * The console's library, public discovery and tag plane (v14 technical-design.md §传输契约).
 *
 * Every call here is a `/web` request, so it rides the session cookie and the CSRF header rather
 * than a bearer token. The server answers with the same card shape in both list scopes, which is why
 * one module owns them: a second adapter for "the other list" would be the first place the two
 * answers drift.
 */

const WEB = '/web'
const SKILLS = `${WEB}/skills`

/** `/web/skills/<namespace>/<name>`, each segment encoded on its own. */
function skillPath(namespace: string, name: string): string {
  return `${SKILLS}/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`
}

function withQuery(path: string, params: URLSearchParams): string {
  const query = params.toString()
  return query === '' ? path : `${path}?${query}`
}

/** The complete library. `show` has two values here: in use, or the author's deleted list. */
export interface LibraryQuery {
  q?: string
  /** Category keys. Repeated values mean "or". */
  tag?: string[]
  /** My-tag ids. Repeated values mean "and". */
  myTag?: string[]
  /** Public-tag keys. Repeated values mean "and"; only the tags page brings these in. */
  publicTag?: string[]
  sort?: 'recent' | 'name'
  show?: 'all' | 'deleted'
  p?: number
  /** Rows per page: 10 / 20 / 50. Omitted means the default below. */
  per?: number
}

/** **The library's default page size (2026-11-01).** The console reads 10/20/50; 10 is the design's
 *  and the server's own default, so asking for it is left off the address — one spelling per state. */
export const LIBRARY_PAGE_DEFAULT = 10

export function listLibrary(query: LibraryQuery = {}): Promise<ApiResult<LibraryPage>> {
  const params = new URLSearchParams()
  if (query.q !== undefined && query.q.trim() !== '') params.set('q', query.q.trim())
  for (const key of query.tag ?? []) params.append('tag', key)
  for (const id of query.myTag ?? []) params.append('my-tag', id)
  for (const key of query.publicTag ?? []) params.append('public-tag', key)
  if (query.sort !== undefined && query.sort !== 'recent') params.set('sort', query.sort)
  if (query.show === 'deleted') params.set('show', 'deleted')
  if (query.p !== undefined && query.p > 1) params.set('p', String(query.p))
  if (query.per !== undefined && query.per !== LIBRARY_PAGE_DEFAULT) {
    params.set('per', String(query.per))
  }
  return request<LibraryPage>({ method: 'GET', path: withQuery(`${WEB}/library`, params) })
}

/** The public results. The interface has one filter — categories, "or" — and pages at six. */
export interface DiscoverQuery {
  q?: string
  tag?: string[]
  p?: number
}

export function listDiscover(query: DiscoverQuery = {}): Promise<ApiResult<DiscoverResults>> {
  const params = new URLSearchParams()
  if (query.q !== undefined && query.q.trim() !== '') params.set('q', query.q.trim())
  for (const key of query.tag ?? []) params.append('tag', key)
  if (query.p !== undefined && query.p > 1) params.set('p', String(query.p))
  return request<DiscoverResults>({ method: 'GET', path: withQuery(`${WEB}/discover`, params) })
}

/**
 * One batch of the recommendation stream. A cursor is opaque and comes back from the previous batch;
 * `limit` is fixed at the design's six.
 */
export function listRecommended(cursor?: string, limit = 6): Promise<ApiResult<RecommendedPage>> {
  const params = new URLSearchParams({ limit: String(limit) })
  if (cursor !== undefined && cursor !== '') params.set('cursor', cursor)
  return request<RecommendedPage>({
    method: 'GET',
    path: withQuery(`${WEB}/discover/recommended`, params),
  })
}

/** The platform's category vocabulary, with the discoverable-skill count per shelf. */
export function listCategories(): Promise<ApiResult<CategoryVocabulary>> {
  return request<CategoryVocabulary>({ method: 'GET', path: `${WEB}/categories` })
}

/** The public-tag vocabulary, for the information page's candidate dropdown. */
export function listPublicTags(q?: string): Promise<ApiResult<PublicTagVocabulary>> {
  const params = new URLSearchParams()
  if (q !== undefined && q.trim() !== '') {
    params.set('q', q.trim())
  }
  return request<PublicTagVocabulary>({
    method: 'GET',
    path: withQuery(`${WEB}/public-tags`, params),
  })
}

/** The private tag definitions. `tab=public` returns the author's public words instead. */
export function listMyTags(): Promise<ApiResult<MyTagUsages>> {
  return request<MyTagUsages>({ method: 'GET', path: `${WEB}/tags` })
}

export function listPublicTagUsages(): Promise<ApiResult<PublicTagUsages>> {
  return request<PublicTagUsages>({ method: 'GET', path: `${WEB}/tags?tab=public` })
}

export function createMyTag(
  name: string,
  description?: string,
): Promise<ApiResult<MyTagDefinition>> {
  const body: { name: string; description?: string } = { name }
  if (description !== undefined && description !== '') {
    body.description = description
  }
  return request<MyTagDefinition>({ method: 'POST', path: `${WEB}/tags`, body })
}

/** Renames a definition in place. Every attachment follows it; no skill is written. */
export function renameMyTag(id: string, name: string): Promise<ApiResult<MyTagDefinition>> {
  return request<MyTagDefinition>({
    method: 'PATCH',
    path: `${WEB}/tags/${encodeURIComponent(id)}`,
    body: { name },
  })
}

/** Writes or clears the one sentence. Clearing sends an empty string, which the server stores as null. */
export function describeMyTag(id: string, description: string): Promise<ApiResult<MyTagDefinition>> {
  return request<MyTagDefinition>({
    method: 'PATCH',
    path: `${WEB}/tags/${encodeURIComponent(id)}`,
    body: { description },
  })
}

/** Removes a definition and every attachment, dormant ones included. The console's red sheet. */
export function deleteMyTag(id: string): Promise<ApiResult<Changed>> {
  return request<Changed>({ method: 'DELETE', path: `${WEB}/tags/${encodeURIComponent(id)}` })
}

/** The three groups on a skill's information page. */
export function getSkillTags(namespace: string, name: string): Promise<ApiResult<SkillTagGroups>> {
  return request<SkillTagGroups>({ method: 'GET', path: `${skillPath(namespace, name)}/tags` })
}

/** Adds a public skill to the caller's library as a reference. Idempotent on the server. */
export function joinSkill(namespace: string, name: string): Promise<ApiResult<Changed>> {
  return request<Changed>({ method: 'POST', path: `${skillPath(namespace, name)}/library`, body: {} })
}

/** Removes only the join reference; the skill stays public and still callable. */
export function leaveSkill(namespace: string, name: string): Promise<ApiResult<Changed>> {
  return request<Changed>({ method: 'DELETE', path: `${skillPath(namespace, name)}/library` })
}

/** One shelf, not an append: a second call replaces the first. */
export function setSkillCategory(
  namespace: string,
  name: string,
  key: string,
): Promise<ApiResult<Changed>> {
  return request<Changed>({
    method: 'PUT',
    path: `${skillPath(namespace, name)}/category`,
    body: { key },
  })
}

export function clearSkillCategory(namespace: string, name: string): Promise<ApiResult<Changed>> {
  return request<Changed>({ method: 'DELETE', path: `${skillPath(namespace, name)}/category` })
}

export function addPublicTag(
  namespace: string,
  name: string,
  tagName: string,
): Promise<ApiResult<Changed>> {
  return request<Changed>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/tags/public`,
    body: { name: tagName },
  })
}

export function removePublicTag(
  namespace: string,
  name: string,
  tagName: string,
): Promise<ApiResult<Changed>> {
  const params = new URLSearchParams({ name: tagName })
  return request<Changed>({
    method: 'DELETE',
    path: withQuery(`${skillPath(namespace, name)}/tags/public`, params),
  })
}

export function addMyTag(
  namespace: string,
  name: string,
  tagName: string,
): Promise<ApiResult<Changed>> {
  return request<Changed>({
    method: 'POST',
    path: `${skillPath(namespace, name)}/tags/my`,
    body: { name: tagName },
  })
}

export function removeMyTag(
  namespace: string,
  name: string,
  tagName: string,
): Promise<ApiResult<Changed>> {
  const params = new URLSearchParams({ name: tagName })
  return request<Changed>({
    method: 'DELETE',
    path: withQuery(`${skillPath(namespace, name)}/tags/my`, params),
  })
}
