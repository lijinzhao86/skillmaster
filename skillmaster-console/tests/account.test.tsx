import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import AccountPage from '../src/pages/AccountPage'
import { loadSession } from '../src/store'
import { clearCookies, empty, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

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

  it('deletes the account only after the handle and the password are both given', async () => {
    const { requests } = stubFetch(
      () => json(200, ACCOUNT),
      () => empty(204),
    )
    await loadSession()
    const { container } = render(<AccountPage />)
    await flush()

    fireEvent.click(container.querySelector('#deleteAccount') as HTMLButtonElement)
    // The sheet's own confirm cannot be pressed before both fields have something in them.
    const sheetButtons = Array.from(container.querySelectorAll('.confirm-acts button')) as HTMLButtonElement[]
    const accept = sheetButtons.find((button) => button.textContent === '注销账号') as HTMLButtonElement
    expect(accept.disabled).toBe(true)

    const inputs = container.querySelectorAll<HTMLInputElement>('.confirm-field input')
    fireEvent.change(inputs[0], { target: { value: 'demo-user' } })
    fireEvent.change(inputs[1], { target: { value: 'a-long-enough-password' } })
    expect(accept.disabled).toBe(false)

    fireEvent.click(accept)
    await flush()

    const sent = requests.find((request) => request.path.endsWith('/web/account/delete'))
    expect(sent?.body).toEqual({ username: 'demo-user', password: 'a-long-enough-password' })
    expect(window.location.assign).toHaveBeenCalledWith('/login?deleted=1')
  })

  it('says the credentials were wrong and keeps the sheet open', async () => {
    stubFetch(
      () => json(200, ACCOUNT),
      () => json(401, errorBody('invalid_credentials', 'The phone number or password is incorrect.')),
    )
    await loadSession()
    const { container } = render(<AccountPage />)
    await flush()

    fireEvent.click(container.querySelector('#deleteAccount') as HTMLButtonElement)
    const inputs = container.querySelectorAll<HTMLInputElement>('.confirm-field input')
    fireEvent.change(inputs[0], { target: { value: 'demo-user' } })
    fireEvent.change(inputs[1], { target: { value: 'wrong-password' } })
    const accept = Array.from(container.querySelectorAll('.confirm-acts button'))
      .find((button) => button.textContent === '注销账号') as HTMLButtonElement
    fireEvent.click(accept)
    await flush()

    expect(container.querySelector('.confirm-error')?.textContent)
      .toContain('没有完成：用户名或密码不对。')
    expect(window.location.assign).not.toHaveBeenCalledWith('/login?deleted=1')
  })
})
