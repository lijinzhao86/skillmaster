import { afterEach, beforeEach, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import AccountKeysPage from '../src/pages/AccountKeysPage'
import {
  clearCookies,
  flush,
  setCookie,
  stubFetch,
  stubLocation,
  type RecordedRequest,
} from './support'

/**
 * The credential page's fixtures and the two files that render it share them.
 *
 * It lives here rather than in one test file because `keys.test.tsx` reached the repository's
 * file-size baseline: adding the create form's tests to it would have grown a file the rule says
 * may only shrink. Splitting by subject — the list and its moments here, the form's expiry choice
 * in `keys-expiry.test.tsx` — is the smaller unit, not a way around the limit.
 */

// `latest_day` comes from the server (ADR 0171 §决定 2): it is the product's last day, and the form
// takes it from here rather than working it out from this browser's clock. `default_days` is the
// form's preselect (90); `lifetime_days` is the select's last preset (365).
export const POLICY = {
  allow_no_expiry: true,
  default_days: 90,
  lifetime_days: 365,
  latest_day: '2027-10-15',
}

export function key(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'k1',
    name: '笔记本',
    note: '我自己的这台',
    scope: 'skills:read',
    expires_at: '2027-01-13T15:59:59Z',
    last_used_at: null,
    revoked_at: null,
    created_at: '2026-10-15T01:00:00Z',
    ...overrides,
  }
}

export const LISTING = { keys: [key()], policy: POLICY }

let location = stubLocation('/account/keys')

/** The hooks every file that renders this page needs: a CSRF cookie, a stub location, and a clean
 *  jsdom (clipboard, env, fetch) for the next test. */
export function installKeysTestHooks(): void {
  beforeEach(() => {
    setCookie('XSRF-TOKEN', 'token-one')
    location = stubLocation('/account/keys')
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    location.restore()
    // jsdom has no clipboard; the one test that provides it has to take it away again, or the next
    // test would silently exercise the other path.
    Reflect.deleteProperty(navigator, 'clipboard')
    clearCookies()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })
}

export async function showPage(
  url = '/account/keys',
  // Async thunks included, as `stubFetch` allows: a response that has not arrived yet is how a test
  // reaches the window between a request going out and its answer coming back.
  ...responses: Array<() => Response | Promise<Response>>
): Promise<{ container: HTMLElement; requests: RecordedRequest[]; mock: unknown }> {
  const [path, query] = url.split('?')
  location = stubLocation(path ?? '/account/keys', query === undefined ? '' : `?${query}`)
  const stub = stubFetch(...responses)
  const { container } = render(<AccountKeysPage />)
  await flush()
  return { container, ...stub }
}

export const text = (element: Element | null): string => (element?.textContent ?? '').trim()

/** The expiry control is the page's own `MenuSelect`, not a native `<select>`: a trigger plus a
 *  list of options that stay in the DOM while closed. */
export function expiryOptions(container: HTMLElement): string[] {
  const trigger = container.querySelector('#key-expires')
  const menu = trigger?.closest('.menu')
  return [...(menu?.querySelectorAll('.menu-item') ?? [])].map((item) => text(item))
}

export function chooseExpiry(container: HTMLElement, value: string): void {
  const trigger = container.querySelector('#key-expires')
  const menu = trigger?.closest('.menu')
  fireEvent.click(trigger as Element)
  fireEvent.click(menu?.querySelector(`.menu-item[data-value="${value}"]`) as Element)
}

/** Focus lands inside `requestAnimationFrame`, which jsdom runs on its own timer. */
export function nextFrame(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 20))
}

export function requested(
  requests: RecordedRequest[],
  path: string,
  method = 'GET',
): RecordedRequest | undefined {
  return requests.find((request) => request.path === path && request.method === method)
}

export function posted(requests: RecordedRequest[], path: string): RecordedRequest[] {
  return requests.filter((request) => request.path === path && request.method === 'POST')
}
