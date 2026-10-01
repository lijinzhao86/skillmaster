import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { clearCookies, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * The shell: which page a path gets, and the one thing that must happen before any of them render.
 *
 * Both are the kind of decision that is invisible while it works — a page chosen by the wrong branch
 * looks like a page, and a form that renders before the CSRF cookie exists looks like a form until
 * it is submitted.
 */

const ACCOUNT = { user_id: '01M3HTG7GCCVBGRPAFFSVSF12W', username: 'demo-user', namespace: 'demo-user' }
const CAPTCHA = { captcha_id: 'cap-1', image: 'iVBORw0KGgo=' }
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

/** A fresh App, because the session it reads lives at module level. */
async function showApp(path: string): Promise<VueWrapper> {
  location = stubLocation(path)
  vi.resetModules()
  const { default: App } = await import('../src/App.vue')
  const wrapper = mount(App)
  await flush()
  return wrapper
}

describe('the app shell', () => {
  it('renders nothing but a loading line until the session read comes back', async () => {
    // Not a nicety: the server writes the CSRF cookie while answering a request, so a form rendered
    // before this GET returns would be a form whose first submission is refused.
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))

    const wrapper = await showApp('/login')

    expect(wrapper.text()).toContain('加载中')
    expect(wrapper.find('form').exists()).toBe(false)
    wrapper.unmount()
  })

  it('makes that read before anything else it does', async () => {
    const { requests } = stubFetch(NOT_SIGNED_IN)

    const wrapper = await showApp('/login')

    expect(requests[0]?.path).toBe('/web/session')
    wrapper.unmount()
  })

  it('serves the login page at /login', async () => {
    stubFetch(NOT_SIGNED_IN)

    const wrapper = await showApp('/login')

    expect(wrapper.find('h2').text()).toBe('登录')
    wrapper.unmount()
  })

  it('serves the register page at /register', async () => {
    stubFetch(NOT_SIGNED_IN, () => json(200, CAPTCHA))

    const wrapper = await showApp('/register')

    expect(wrapper.find('h2').text()).toBe('注册')
    wrapper.unmount()
  })

  it('serves the reset page at /reset', async () => {
    stubFetch(NOT_SIGNED_IN, () => json(200, CAPTCHA))

    const wrapper = await showApp('/reset')

    expect(wrapper.find('h2').text()).toBe('找回密码')
    wrapper.unmount()
  })

  it('serves the signed-out home page to a visitor', async () => {
    stubFetch(NOT_SIGNED_IN)

    const wrapper = await showApp('/')

    expect(wrapper.text()).toContain('还没有登录')
    wrapper.unmount()
  })

  it('serves the signed-in home page to somebody with a session, and to nobody else', async () => {
    stubFetch(() => json(200, ACCOUNT))

    const wrapper = await showApp('/')

    expect(wrapper.text()).toContain('你已登录')
    expect(wrapper.text()).toContain('demo-user')
    wrapper.unmount()
  })

  it('falls back to the home page for a path it does not know', async () => {
    // Three paths and a default, rather than a 404 page: the paths are the SPA's own, and the one
    // that can be reached by a typo is the one that shows where to go next.
    stubFetch(NOT_SIGNED_IN)

    const wrapper = await showApp('/nonsense')

    expect(wrapper.text()).toContain('还没有登录')
    wrapper.unmount()
  })
})
