import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
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

/** Mounts the page on a fresh module graph, with the session read already answered. */
async function showAccount(sessionResponse: () => Response): Promise<VueWrapper> {
  stubFetch(sessionResponse)
  vi.resetModules()
  const { useSession } = await import('../src/composables/useSession')
  const { default: AccountPage } = await import('../src/pages/AccountPage.vue')
  await useSession().load()
  const wrapper = mount(AccountPage)
  await flush()
  return wrapper
}

describe('the account page', () => {
  it('names the account, and the namespace its skills live under', async () => {
    const wrapper = await showAccount(() => json(200, ACCOUNT))

    expect(wrapper.text()).toContain('demo-user')
    expect(wrapper.text()).toContain('命名空间')
    wrapper.unmount()
  })

  it('offers a way in to somebody who is not signed in, and does not call it an error', async () => {
    const wrapper = await showAccount(() =>
      json(401, errorBody('unauthenticated', 'Authentication is required.')),
    )

    expect(wrapper.text()).toContain('还没有登录')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/register"]').exists()).toBe(true)
    // No banner: a visitor who has never signed in is not a failure to report.
    expect(wrapper.find('.banner').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not claim nobody is signed in when the question could not be asked', async () => {
    // A 500 on the session read is not the same answer as a 401, and showing "还没有登录" would state
    // something about the session that was never established.
    const wrapper = await showAccount(() =>
      json(500, errorBody('internal_error', 'Something went wrong.')),
    )

    expect(wrapper.text()).toContain('无法确认登录状态')
    expect(wrapper.text()).not.toContain('还没有登录')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
