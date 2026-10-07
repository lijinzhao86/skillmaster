import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { clearCookies, empty, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * The frame every signed-in page sits in: the navigation, and the one action it owns.
 *
 * Signing out lives here rather than on the account page because it is reachable from every page, and
 * with it the two ways that request can go. The second is the one worth pinning: a refusal means the
 * server still considers the session alive, so the page must not navigate anywhere.
 */

const ACCOUNT = { user_id: '01M3HTG7GCCVBGRPAFFSVSF12W', username: 'demo-user', namespace: 'demo-user' }

let location = stubLocation('/')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** Mounts the frame on a fresh module graph, with the session read already answered. */
async function showShell(
  path: string,
  sessionResponse: () => Response = () => json(200, ACCOUNT),
): Promise<VueWrapper> {
  location = stubLocation(path)
  stubFetch(sessionResponse)
  vi.resetModules()
  const { useSession } = await import('../src/composables/useSession')
  const { default: AppShell } = await import('../src/AppShell.vue')
  await useSession().load()
  const wrapper = mount(AppShell, { slots: { default: '<p class="page">page</p>' } })
  await flush()
  return wrapper
}

describe('the signed-in frame', () => {
  it('puts the page it was given under the navigation', async () => {
    const wrapper = await showShell('/')

    expect(wrapper.find('.content .page').exists()).toBe(true)
    wrapper.unmount()
  })

  it('groups the navigation, and marks the group the address belongs to', async () => {
    const wrapper = await showShell('/account')

    expect(wrapper.findAll('.nav-group')).toHaveLength(2)
    expect(wrapper.find('a.nav-item.is-active').attributes('href')).toBe('/account')
    wrapper.unmount()
  })

  it('keeps the skills item marked while the reader is inside a skill', async () => {
    // The section, not the page: a sidebar that unmarked itself as soon as you opened something would
    // stop saying where you are.
    const wrapper = await showShell('/skills/demo-user/pdf-tools')

    expect(wrapper.find('a.nav-item.is-active').attributes('href')).toBe('/')
    wrapper.unmount()
  })

  it('goes to the login page once the server has ended the session', async () => {
    const wrapper = await showShell('/')
    // The sign-out request itself: the session read already spent the first stub.
    stubFetch(() => empty(204))

    await wrapper.find('.nav-foot button').trigger('click')
    await flush()

    expect(location.assign).toHaveBeenCalledWith('/login')
    wrapper.unmount()
  })

  it('says why when the server would not end the session', async () => {
    // A 403 here is what a missing or stale CSRF token looks like, and it means the person is still
    // signed in on the server whatever this page does next — so it stays put and says so rather than
    // sending them somewhere that would still be signed in.
    const wrapper = await showShell('/')
    stubFetch(() => json(403, errorBody('forbidden', 'The CSRF token was missing or stale.')))

    await wrapper.find('.nav-foot button').trigger('click')
    await flush()

    expect(wrapper.find('.banner').text()).toBe('请求被拒绝了，请刷新页面后重试。')
    expect(location.assign).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('offers a way in instead of a sign-out button to somebody who has none', async () => {
    const wrapper = await showShell('/', () =>
      json(401, errorBody('unauthenticated', 'Authentication is required.')),
    )

    expect(wrapper.find('.nav-foot button').exists()).toBe(false)
    expect(wrapper.find('.nav-foot a[href="/login"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
