import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import AccountPage from '../src/pages/AccountPage'
import { loadSession } from '../src/store'
import { clearCookies, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * The account page: who you are, and the namespace your skills live under.
 *
 * Nothing here acts on the session any more — signing out moved to the navigation, which is reachable
 * from every page rather than only from this one — so what is pinned is what the page claims, and in
 * the two cases where it cannot make a claim, what it says instead.
 */

const ACCOUNT = { user_id: '01M3HTG7GCCVBGRPAFFSVSF12W', username: 'demo-user', namespace: 'demo-user' }

let location = stubLocation('/account')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/account')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** Renders the page with the session read already answered, the way the app's bootstrap does. */
async function showAccount(sessionResponse: () => Response): Promise<HTMLElement> {
  stubFetch(sessionResponse)
  await loadSession()
  const { container } = render(<AccountPage />)
  await flush()
  return container
}

describe('the account page', () => {
  it('names the account, and the namespace its skills live under', async () => {
    const container = await showAccount(() => json(200, ACCOUNT))

    expect(container.textContent).toContain('demo-user')
    expect(container.textContent).toContain('命名空间')
  })

  it('offers a way in to somebody who is not signed in, and does not call it an error', async () => {
    const container = await showAccount(() =>
      json(401, errorBody('unauthenticated', 'Authentication is required.')),
    )

    expect(container.textContent).toContain('还没有登录')
    expect(container.querySelector('a[href="/login"]')).not.toBeNull()
    expect(container.querySelector('a[href="/register"]')).not.toBeNull()
    // No banner: a visitor who has never signed in is not a failure to report.
    expect(container.querySelector('.banner')).toBeNull()
  })

  it('does not claim nobody is signed in when the question could not be asked', async () => {
    // A 500 on the session read is not the same answer as a 401, and showing "还没有登录" would state
    // something about the session that was never established.
    const container = await showAccount(() =>
      json(500, errorBody('internal_error', 'Something went wrong.')),
    )

    expect(container.textContent).toContain('无法确认登录状态')
    expect(container.textContent).not.toContain('还没有登录')
    expect(container.querySelector('a[href="/login"]')).not.toBeNull()
  })
})
