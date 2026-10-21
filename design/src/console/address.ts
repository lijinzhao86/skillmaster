/**
 * The address, which is the whole state of this demo.
 *
 * Every screen here can be linked to, reloaded and sent to somebody else, because the address says
 * which one it is — the same property the real app has, and the reason a state is never remembered
 * in a variable when it can be named in a query. Two kinds of change exist and they are different:
 *
 * - `go` is a **visit**: a new page, a new document, a reload. Links use it, and so does anything
 *   that changes which screen is up.
 * - `replace` is a **revision**: the same screen, with one fact changed — a filter typed into a box,
 *   a file opened in the tree. It writes the address without a reload, because a search that reloads
 *   on every keystroke is a search nobody finishes typing.
 *
 * `useAddress` re-renders on the second kind (and on Back/Forward); the first needs nothing, because
 * the document is replaced.
 */

import { useEffect, useState } from 'react'
import { isAuthPage, isConsolePage } from './demo'
import type { PageName } from './demo'

/** The event a `replace` announces itself with; the demo has no router, so this is the one seam. */
const CHANGED = 'demo:address'

export function paramsNow(): URLSearchParams {
  return new URLSearchParams(window.location.search)
}

/** The page the address names. Anything the demo does not know lands on the console, as it always has. */
export function pageOf(params: URLSearchParams): PageName {
  const page = params.get('page')
  if (isConsolePage(page) || isAuthPage(page)) return page
  return 'skills'
}

export function go(href: string): void {
  window.location.assign(href)
}

export function replace(href: string): void {
  window.history.replaceState(null, '', href)
  window.dispatchEvent(new Event(CHANGED))
}

/** The current query, kept in step with `replace` and with Back/Forward. */
export function useAddress(): URLSearchParams {
  const [params, setParams] = useState(paramsNow)
  useEffect(() => {
    const sync = () => setParams(paramsNow())
    window.addEventListener(CHANGED, sync)
    window.addEventListener('popstate', sync)
    return () => {
      window.removeEventListener(CHANGED, sync)
      window.removeEventListener('popstate', sync)
    }
  }, [])
  return params
}

/** `?page=skills&q=…`, the one thing the top bar's search knows how to ask for. */
export function skillsAddress(query: string): string {
  const asked = query.trim()
  return '?page=skills' + (asked ? '&q=' + encodeURIComponent(asked) : '')
}
