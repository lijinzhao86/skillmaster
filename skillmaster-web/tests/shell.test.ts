import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { clearCookies, empty, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * The frame every signed-in page sits in: the top bar, the navigation beside the page, and the three
 * things in the bar that are interactive.
 *
 * **The shape is the design's** (`design/skillmaster-控制台演示.html`): brand, search and account
 * across the top; `SKILL` and `设置` in the sidebar, with the credential page as a child of a `权限`
 * disclosure. These are the assertions that keep this file from drifting back to the delivery before
 * it — the shell was one design delivery behind, which is how the two came to disagree on screen.
 *
 * Signing out lives in the account menu rather than on a page because it is reachable from every
 * page, and with it the two ways that request can go. The refusal is the one worth pinning: it means
 * the server still considers the session alive, so the page must not navigate anywhere.
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

/**
 * Mounts the frame on a fresh module graph, with the session read already answered.
 *
 * Attached to the document, because two of the things in here are about focus — `/` moving it into
 * the search box, and Escape handing it back to the avatar — and jsdom only moves focus inside a
 * document.
 */
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
  const wrapper = mount(AppShell, { slots: { default: '<p class="page">page</p>' }, attachTo: document.body })
  await flush()
  return wrapper
}

describe('the signed-in frame', () => {
  it('puts the page it was given under the navigation', async () => {
    const wrapper = await showShell('/')

    expect(wrapper.find('.content .page').exists()).toBe(true)
    wrapper.unmount()
  })

  it('puts the brand in the top bar and leaves the sidebar without one', async () => {
    // The design moved both the brand and the account into the bar; the sidebar it leaves behind is
    // navigation and nothing else, which is also why it draws no rule of its own.
    const wrapper = await showShell('/')

    expect(wrapper.find('.topbar .brand').text()).toContain('SkillMaster')
    expect(wrapper.find('.sidenav .brand').exists()).toBe(false)
    expect(wrapper.find('.sidenav .nav-foot').exists()).toBe(false)
    wrapper.unmount()
  })

  it('groups the navigation under the design\'s two headings', async () => {
    const wrapper = await showShell('/account')

    // SKILL and 设置 — two headings, not three groups of one: "who you are" and "what may act as you"
    // are both settings questions, and the credential page is a child of one of them rather than a
    // section beside them.
    expect(wrapper.findAll('.nav-title').map((node) => node.text())).toEqual(['SKILL', '设置'])
    expect(wrapper.find('a.nav-item.is-active').attributes('href')).toBe('/account')
    expect(wrapper.find('a.nav-item.is-active').text()).toBe('账号')
    wrapper.unmount()
  })

  it('files the credential page as a child of the 权限 disclosure', async () => {
    const wrapper = await showShell('/account/keys')

    // A child item, indented and without an icon slot — the reference product's own way of saying
    // "this is inside the thing above" — and the parent says a child of it is current without taking
    // the current styling itself.
    const child = wrapper.find('a.nav-item.is-child.is-active')
    expect(child.attributes('href')).toBe('/account/keys')
    expect(child.attributes('aria-current')).toBe('page')
    expect(wrapper.find('.nav-disclosure.has-current-child').exists()).toBe(true)
    wrapper.unmount()
  })

  it('starts the disclosure collapsed and opens its children in place', async () => {
    // Collapsed on every page, including this one: a navigation that unfolds itself is presuming on
    // the reader, and the breadcrumb is what says where you are.
    const wrapper = await showShell('/account/keys')

    const children = wrapper.find('#navAccessChildren')
    const toggle = wrapper.find('.nav-disclosure')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(children.attributes('hidden')).toBeDefined()

    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(children.attributes('hidden')).toBeUndefined()

    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    wrapper.unmount()
  })

  it('keeps the skills item marked while the reader is inside a skill', async () => {
    // The section, not the page: a sidebar that unmarked itself as soon as you opened something would
    // stop saying where you are.
    const wrapper = await showShell('/skills/demo-user/pdf-tools')

    expect(wrapper.find('a.nav-item.is-active').attributes('href')).toBe('/')
    wrapper.unmount()
  })
})

describe('the top bar', () => {
  it('hands what was typed to the skill list, and an empty box to the list unfiltered', async () => {
    const wrapper = await showShell('/')

    await wrapper.find('#topSearch').setValue('PDF 工具')
    await wrapper.find('.topbar-search').trigger('submit')
    expect(location.assign).toHaveBeenCalledWith('/?q=PDF%20%E5%B7%A5%E5%85%B7')

    // Submitting nothing is a way back to the whole list rather than a search for the empty string.
    await wrapper.find('#topSearch').setValue('  ')
    await wrapper.find('.topbar-search').trigger('submit')
    expect(location.assign).toHaveBeenLastCalledWith('/')
    wrapper.unmount()
  })

  it('puts the cursor in the search box when / is pressed, and leaves it alone when it is not', async () => {
    // The hint inside the box promises this. **The guard is the half that matters**: stealing the key
    // while somebody is typing would make a skill name containing a slash impossible to type, so the
    // event is left to the box — and prevented everywhere else, so the browser's own quick-find does
    // not open on top of it.
    const wrapper = await showShell('/')

    const elsewhere = new KeyboardEvent('keydown', { key: '/', bubbles: true, cancelable: true })
    document.dispatchEvent(elsewhere)
    expect(elsewhere.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(wrapper.find('#topSearch').element)

    const typing = new KeyboardEvent('keydown', { key: '/', bubbles: true, cancelable: true })
    document.activeElement?.dispatchEvent(typing)
    expect(typing.defaultPrevented).toBe(false)
    wrapper.unmount()
  })

  it('opens the account menu, and closes it on Escape with the focus back on the avatar', async () => {
    const wrapper = await showShell('/')

    const avatar = wrapper.find('.me')
    await avatar.trigger('click')
    expect(avatar.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('.me-menu').attributes('hidden')).toBeUndefined()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flush()
    expect(avatar.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.me-menu').attributes('hidden')).toBeDefined()
    expect(document.activeElement).toBe(avatar.element)
    wrapper.unmount()
  })

  it('closes the account menu when the click is somewhere else', async () => {
    const wrapper = await showShell('/')

    await wrapper.find('.me').trigger('click')
    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flush()

    expect(wrapper.find('.me-menu').attributes('hidden')).toBeDefined()
    wrapper.unmount()
  })

  it('goes to the login page once the server has ended the session', async () => {
    const wrapper = await showShell('/')
    // The sign-out request itself: the session read already spent the first stub.
    stubFetch(() => empty(204))

    await wrapper.find('.me').trigger('click')
    await wrapper.find('.me-menu button').trigger('click')
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

    await wrapper.find('.me').trigger('click')
    await wrapper.find('.me-menu button').trigger('click')
    await flush()

    expect(wrapper.find('.banner').text()).toBe('请求被拒绝了，请刷新页面后重试。')
    expect(location.assign).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('offers a way in instead of an account menu to somebody who has none', async () => {
    // The one case the design has no picture of: its console is always signed in, while a session
    // that expired under a signed-in page is not.
    const wrapper = await showShell('/', () =>
      json(401, errorBody('unauthenticated', 'Authentication is required.')),
    )

    expect(wrapper.find('.me').exists()).toBe(false)
    expect(wrapper.find('.topbar-right a[href="/login"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
