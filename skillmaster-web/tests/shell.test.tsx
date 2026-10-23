import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render } from '@testing-library/react'
import AppShell from '../src/AppShell'
import { loadSession } from '../src/store'
import { clearCookies, empty, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * The frame every signed-in page sits in: the top bar, the navigation beside the page, and the three
 * things in the bar that are interactive.
 *
 * **The shape is the design's** (`design/skillmaster-控制台演示.html`): brand, search and account
 * across the top; `SKILL` and `设置` in the sidebar, with the two pages of `权限` — the keys and the
 * login devices — as children of a disclosure. These are the assertions that keep this file from
 * drifting back to the delivery before it — the shell was one design delivery behind, which is how
 * the two came to disagree on screen — and from being a delivery behind again when one of those two
 * is added, which is what happened when the devices screen arrived.
 *
 * Signing out lives in the account menu rather than on a page because it is reachable from every
 * page, and with it the two ways that request can go. The refusal is the one worth pinning: it means
 * the server still considers the session alive, so the page must not navigate anywhere.
 *
 * **What changed in the port.** `AppShell.vue` was mounted on its own with a slot; here it is a
 * component taking `children`, and the session comes from the app's one store, loaded the way `App`
 * loads it. Rendered into `document.body` by RTL, which is what makes the focus assertions below
 * possible — jsdom only moves focus inside a document.
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
 * Mounts the frame with the session read already answered.
 *
 * The store is the app's single one, so this is the same call `App` makes on every page load; a
 * second answer in the same file simply replaces the first, which is what lets one test be about a
 * visitor with no session.
 */
async function showShell(
  path: string,
  sessionResponse: () => Response = () => json(200, ACCOUNT),
) {
  location = stubLocation(path)
  stubFetch(sessionResponse)
  const view = render(
    <AppShell>
      <p className="page">page</p>
    </AppShell>,
  )
  await act(async () => {
    await loadSession()
  })
  await flush()
  return view
}

const text = (element: Element | null): string => (element?.textContent ?? '').trim()

describe('the signed-in frame', () => {
  it('puts the page it was given under the navigation', async () => {
    const { container } = await showShell('/')

    expect(container.querySelector('.content .page')).not.toBeNull()
  })

  it('puts the brand in the top bar and leaves the sidebar without one', async () => {
    // The design moved both the brand and the account into the bar; the sidebar it leaves behind is
    // navigation and nothing else, which is also why it draws no rule of its own.
    const { container } = await showShell('/')

    expect(text(container.querySelector('.topbar .brand'))).toContain('SkillMaster')
    expect(container.querySelector('.sidenav .brand')).toBeNull()
    expect(container.querySelector('.sidenav .nav-foot')).toBeNull()
  })

  it('groups the navigation under the design’s two headings', async () => {
    const { container } = await showShell('/account')

    // SKILL and 设置 — two headings, not three groups of one: "who you are" and "what may act as you"
    // are both settings questions, and the credential page is a child of one of them rather than a
    // section beside them.
    expect([...container.querySelectorAll('.nav-title')].map((node) => text(node))).toEqual([
      'SKILL',
      '设置',
    ])
    const active = container.querySelector('a.nav-item.is-active')
    expect(active?.getAttribute('href')).toBe('/account')
    expect(text(active)).toBe('账号')
  })

  it('files the credential page as a child of the 权限 disclosure', async () => {
    const { container } = await showShell('/account/keys')

    // A child item, indented and without an icon slot — the reference product's own way of saying
    // "this is inside the thing above" — and the parent says a child of it is current without taking
    // the current styling itself.
    const child = container.querySelector('a.nav-item.is-child.is-active')
    expect(child?.getAttribute('href')).toBe('/account/keys')
    expect(child?.getAttribute('aria-current')).toBe('page')
    // The design opens a disclosure on the page inside it, so the parent is expanded and carries
    // `aria-current` rather than the collapsed `has-current-child` mark.
    expect(container.querySelector('#navAccess')?.getAttribute('aria-current')).toBe('true')
    expect(container.querySelector('#navAccess')?.getAttribute('aria-expanded')).toBe('true')
  })

  it('files the login devices beside the keys, in both shapes of the disclosure', async () => {
    const { container } = await showShell('/account/sessions')

    // **Both lists, because the design has both**: the flyout shown on hover and the children opened
    // in place. They are two renderings of one group, and a page added to one and not the other is
    // reachable by pointer and not by keyboard — or the reverse.
    expect(container.querySelectorAll('a.nav-item.is-child[href="/account/sessions"]').length).toBe(2)
    // The order is the design's: the keys first, the devices second. Scoped to the 权限 group —
    // 管理 has a flyout of its own now, and an unscoped selector would read both.
    const access = container.querySelector('#navAccess')?.parentElement
    expect(
      [...(access?.querySelectorAll('.nav-flyout-panel a.nav-item.is-child') ?? [])].map((a) =>
        text(a),
      ),
    ).toEqual(['API 密钥', '登录设备'])

    // Two, because the group is drawn twice — one anchor per shape — and the one current page is
    // marked in both. A single active anchor would mean one of the two lists had gone stale.
    const active = [...container.querySelectorAll('a.nav-item.is-child.is-active')]
    expect(active.map((a) => a.getAttribute('href'))).toEqual([
      '/account/sessions',
      '/account/sessions',
    ])
    // The parent says a child of it is current, and does not take the current styling itself.
    //
    // **The second half used to be `expect(... '.nav-disclosure.is-active' ...).toBe(false)`, which
    // could not fail**: nothing in this component or in the design ever puts `is-active` on a
    // disclosure, so the assertion was about a class that does not exist. What the design does state
    // there is `aria-current` on the disclosure — 「the only one of the three a screen reader could
    // not hear」 — so that is what is asserted.
    expect(container.querySelector('#navAccess')?.getAttribute('aria-current')).toBe('true')
  })

  it('starts a disclosure closed off its own page, and opens its children in place', async () => {
    // A page outside the group does not unfold it: that would be presuming on the reader, and the
    // breadcrumb is what says where you are. A page inside it starts open (above).
    const { container } = await showShell('/account')

    const toggle = () => container.querySelector('#navAccess') as HTMLButtonElement
    const children = () => container.querySelector('#navAccessChildren')
    expect(toggle().getAttribute('aria-expanded')).toBe('false')
    expect(children()?.hasAttribute('hidden')).toBe(true)

    fireEvent.click(toggle())
    expect(toggle().getAttribute('aria-expanded')).toBe('true')
    expect(children()?.hasAttribute('hidden')).toBe(false)

    fireEvent.click(toggle())
    expect(toggle().getAttribute('aria-expanded')).toBe('false')
  })

  it('puts 发现 in the SKILL group, and 标签 under 管理', async () => {
    // The v14 arrangement: where to look (发现), what I have (我的 skill), how to manage it (管理 ›
    // 标签). The old single 管理 item was the library's name and its group's name at once.
    const discover = await showShell('/discover')
    expect(discover.container.querySelector('a.nav-item.is-active')?.getAttribute('href')).toBe(
      '/discover',
    )
    // Two mounted trees would leave two `#navManageChildren` ids in the document, and a scoped id
    // query would then read the wrong one.
    discover.unmount()

    const tags = await showShell('/my-tags')
    const manage = tags.container.querySelector('#navManage') as HTMLButtonElement
    expect(manage.getAttribute('aria-expanded')).toBe('true')
    expect(manage.getAttribute('aria-current')).toBe('true')
    expect(
      tags.container.querySelector('#navManageChildren a.is-active')?.getAttribute('href'),
    ).toBe('/my-tags')
  })

  it('keeps the skills item marked while the reader is inside a skill', async () => {
    // The section, not the page: a sidebar that unmarked itself as soon as you opened something would
    // stop saying where you are.
    const { container } = await showShell('/skills/demo-user/pdf-tools')

    expect(container.querySelector('a.nav-item.is-active')?.getAttribute('href')).toBe('/')
  })
})

describe('the top bar', () => {
  it('hands what was typed to the skill list, and an empty box to the list unfiltered', async () => {
    const { container } = await showShell('/')

    fireEvent.change(container.querySelector('#topSearch') as HTMLInputElement, {
      target: { value: 'PDF 工具' },
    })
    fireEvent.submit(container.querySelector('.topbar-search') as HTMLFormElement)
    expect(location.assign).toHaveBeenCalledWith('/?q=PDF%20%E5%B7%A5%E5%85%B7')

    // Submitting nothing is a way back to the whole list rather than a search for the empty string.
    fireEvent.change(container.querySelector('#topSearch') as HTMLInputElement, {
      target: { value: '  ' },
    })
    fireEvent.submit(container.querySelector('.topbar-search') as HTMLFormElement)
    expect(location.assign).toHaveBeenLastCalledWith('/')
  })

  it('puts the cursor in the search box when / is pressed, and leaves it alone when it is not', async () => {
    // The hint inside the box promises this. **The guard is the half that matters**: stealing the key
    // while somebody is typing would make a skill name containing a slash impossible to type, so the
    // event is left to the box — and prevented everywhere else, so the browser's own quick-find does
    // not open on top of it.
    const { container } = await showShell('/')

    const elsewhere = new KeyboardEvent('keydown', { key: '/', bubbles: true, cancelable: true })
    document.dispatchEvent(elsewhere)
    expect(elsewhere.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(container.querySelector('#topSearch'))

    const typing = new KeyboardEvent('keydown', { key: '/', bubbles: true, cancelable: true })
    document.activeElement?.dispatchEvent(typing)
    expect(typing.defaultPrevented).toBe(false)
  })

  it('opens the account menu, and closes it on Escape with the focus back on the avatar', async () => {
    const { container } = await showShell('/')
    const avatar = () => container.querySelector('.me') as HTMLButtonElement

    fireEvent.click(avatar())
    expect(avatar().getAttribute('aria-expanded')).toBe('true')
    expect(container.querySelector('.me-menu')?.hasAttribute('hidden')).toBe(false)

    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    })
    await flush()
    expect(avatar().getAttribute('aria-expanded')).toBe('false')
    expect(container.querySelector('.me-menu')?.hasAttribute('hidden')).toBe(true)
    expect(document.activeElement).toBe(avatar())
  })

  it('closes the account menu when the click is somewhere else', async () => {
    const { container } = await showShell('/')

    fireEvent.click(container.querySelector('.me') as HTMLButtonElement)
    await act(async () => {
      document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })
    await flush()

    expect(container.querySelector('.me-menu')?.hasAttribute('hidden')).toBe(true)
  })

  it('goes to the login page once the server has ended the session', async () => {
    const { container } = await showShell('/')
    // The sign-out request itself: the session read already spent the first stub.
    stubFetch(() => empty(204))

    fireEvent.click(container.querySelector('.me') as HTMLButtonElement)
    fireEvent.click(container.querySelector('.me-menu button') as HTMLButtonElement)
    await flush()

    expect(location.assign).toHaveBeenCalledWith('/login')
  })

  it('says why when the server would not end the session', async () => {
    // A 403 here is what a missing or stale CSRF token looks like, and it means the person is still
    // signed in on the server whatever this page does next — so it stays put and says so rather than
    // sending them somewhere that would still be signed in.
    const { container } = await showShell('/')
    stubFetch(() => json(403, errorBody('forbidden', 'The CSRF token was missing or stale.')))

    fireEvent.click(container.querySelector('.me') as HTMLButtonElement)
    fireEvent.click(container.querySelector('.me-menu button') as HTMLButtonElement)
    await flush()

    expect(text(container.querySelector('.banner'))).toBe('请求被拒绝了，请刷新页面后重试。')
    expect(location.assign).not.toHaveBeenCalled()
  })

  it('offers a way in instead of an account menu to somebody who has none', async () => {
    // The one case the design has no picture of: its console is always signed in, while a session
    // that expired under a signed-in page is not.
    const { container } = await showShell('/', () =>
      json(401, errorBody('unauthenticated', 'Authentication is required.')),
    )

    expect(container.querySelector('.me')).toBeNull()
    expect(container.querySelector('.topbar-right a[href="/login"]')).not.toBeNull()
  })
})
