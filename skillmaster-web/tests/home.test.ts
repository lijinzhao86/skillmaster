import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { clearCookies, empty, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * The signed-in page: what it shows, and what signing out does.
 *
 * It is also the only page that acts on the session it read — everything else only reads it — so the
 * two ways a sign-out can go are pinned here.
 */

const ACCOUNT = { user_id: '01M3HTG7GCCVBGRPAFFSVSF12W', username: 'demo-user', namespace: 'demo-user' }
const NOT_SIGNED_IN = () => json(401, errorBody('unauthenticated', 'Authentication is required.'))

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

/** Mounts the page on a fresh module graph, with the session read already answered. */
async function showHome(sessionResponse: () => Response): Promise<VueWrapper> {
  stubFetch(sessionResponse)
  vi.resetModules()
  const { useSession } = await import('../src/composables/useSession')
  const { default: HomePage } = await import('../src/pages/HomePage.vue')
  await useSession().load()
  const wrapper = mount(HomePage)
  await flush()
  return wrapper
}

describe('the home page', () => {
  it('shows who is signed in', async () => {
    const wrapper = await showHome(() => json(200, ACCOUNT))

    expect(wrapper.text()).toContain('你已登录')
    expect(wrapper.text()).toContain('demo-user')
    expect(wrapper.find('button').text()).toBe('退出登录')
    wrapper.unmount()
  })

  it('offers a way in to somebody who is not signed in, and does not call it an error', async () => {
    const wrapper = await showHome(NOT_SIGNED_IN)

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
    const wrapper = await showHome(() =>
      json(500, errorBody('internal_error', 'Something went wrong.')),
    )

    expect(wrapper.text()).toContain('无法确认登录状态')
    expect(wrapper.text()).not.toContain('还没有登录')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('goes to the login page once the server has ended the session', async () => {
    const wrapper = await showHome(() => json(200, ACCOUNT))
    // The sign-out request itself: the session read already spent the first stub.
    stubFetch(() => empty(204))

    await wrapper.find('button').trigger('click')
    await flush()

    expect(location.assign).toHaveBeenCalledWith('/login')
    wrapper.unmount()
  })

  it('says why when the server would not end the session', async () => {
    // A 403 here is what a missing or stale CSRF token looks like, and it means the person is still
    // signed in on the server whatever this page does next — so it stays put and says so rather than
    // sending them somewhere that would still be signed in.
    const wrapper = await showHome(() => json(200, ACCOUNT))
    stubFetch(() => json(403, errorBody('forbidden', 'The CSRF token was missing or stale.')))

    await wrapper.find('button').trigger('click')
    await flush()

    expect(wrapper.find('.banner').text()).toBe('请求被拒绝了，请刷新页面后重试。')
    expect(location.assign).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
