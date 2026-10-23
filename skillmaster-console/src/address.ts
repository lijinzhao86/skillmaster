/**
 * The browser's address, in one place.
 *
 * **Every screen here is an address** (design README: 「它是地址里的一态，不是本地开关」), so page
 * state — a filter, a tab, the file being read — is read from the query string and written back to
 * it. Eleven files used to spell that out themselves, each with its own
 * `new URLSearchParams(window.location.search)` and its own `history.replaceState`, which meant the
 * app had as many answers to "which parameters are there" as it had pages.
 *
 * Two rules this module keeps, and both were learned from a page that got them wrong:
 *
 * - **An empty query is written as the bare path**, never as a dangling `?`. `/account/keys?` and
 *   `/account/keys` are one screen with two spellings; the address bar only gets the first one if
 *   nobody decides.
 * - **A rewrite keeps the page; a navigation loads it.** `replaceAddress` never leaves the page —
 *   that is what a search box or a pager needs — and `go` is a whole load, which is this app's only
 *   kind of navigation (no router holds state; ADR 0015, ADR 0075).
 */

/** The current page's parameters, as a copy: callers edit it and hand it back to `addressFor`. */
export function query(): URLSearchParams {
  return new URLSearchParams(window.location.search)
}

/** One parameter, which is most of what the pages read. */
export function param(name: string): string | null {
  return query().get(name)
}

/** `path` with `params` on it; an empty query gives the bare path. Links between pages use this. */
export function addressFor(path: string, params: URLSearchParams): string {
  const search = params.toString()
  return search === '' ? path : `${path}?${search}`
}

/**
 * A **relative** address — `?tab=versions&version=1.2.4` — which keeps whatever path the page is
 * on. The version screens navigate and link this way, and they say so with the relative form rather
 * than by restating the path.
 *
 * An empty query is `?`, not `''`: `location.assign('')` reloads the page it is already on, which
 * is not what "this screen, with nothing else selected" means.
 */
export function relativeAddress(params: URLSearchParams): string {
  return `?${params.toString()}`
}

/**
 * A relative address, or the current path when nothing is left — the list pages' convention, where
 * an unfiltered list is its own plain address rather than the same one with a dangling `?`.
 */
export function addressOrPath(params: URLSearchParams): string {
  const search = params.toString()
  return search === '' ? window.location.pathname : `?${search}`
}

/** Rewrites the current history entry — no load, and Back stays where it was. */
export function replaceAddress(url: string): void {
  window.history.replaceState(null, '', url)
}

/** A whole-page load. The only navigation this app has: links are real `<a href>`, not pushState. */
export function go(url: string): void {
  window.location.assign(url)
}
