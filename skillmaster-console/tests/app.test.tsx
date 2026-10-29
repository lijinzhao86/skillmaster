import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import App, { routeOf } from '../src/App'
import CliSignInPage from '../src/pages/CliSignInPage'
import ConsentPage from '../src/pages/ConsentPage'
import LoginPage from '../src/pages/LoginPage'
import RegisterPage from '../src/pages/RegisterPage'
import ResetPage from '../src/pages/ResetPage'
import SkillsPage from '../src/pages/SkillsPage'
import DiscoverPage from '../src/pages/DiscoverPage'
import MyTagsPage from '../src/pages/MyTagsPage'
import { clearCookies, errorBody, flush, json, setCookie, stubFetch, stubLocation, type RecordedRequest } from './support'
import { session } from '../src/store'

/**
 * The address decides the page.
 *
 * **Every value the page needs is in the path or the query**, never in component state — that is
 * what makes a version link a real link, and what lets a reload, a bookmark and the address the CLI
 * opens all land on the same thing.
 *
 * This file starts its life in P0 with the route table alone: every address resolves to a
 * placeholder until the pages land, one phase at a time (v12's TD §分期). It grows into the full
 * routing test — render, shell, standalone — when the last page arrives.
 */
describe('the route table', () => {
  it('sends a signed-out visitor to a page with no shell', () => {
    const route = routeOf('/login')

    expect(route.shell).toBe(false)
    expect(route.page).toBe(LoginPage)
  })

  it('keeps the login-request screen standalone: no site under it', () => {
    // The CLI opened that address, and there is nothing behind it — the design's own distinction
    // between a card over a page and a screen that *is* the page.
    const route = routeOf('/cli-sign-in')

    expect(route.page).toBe(CliSignInPage)
    expect(route.standalone).toBe(true)
    expect(route.shell).toBe(false)
  })

  it('gives each signed-out address its own page', () => {
    expect(routeOf('/register').page).toBe(RegisterPage)
    expect(routeOf('/reset').page).toBe(ResetPage)
    expect(routeOf('/consent').page).toBe(ConsentPage)
    for (const path of ['/register', '/reset', '/consent']) {
      expect(routeOf(path).shell).toBe(false)
    }
  })

  it('puts the signed-in pages inside the shell', () => {
    expect(routeOf('/').shell).toBe(true)
    expect(routeOf('/discover').shell).toBe(true)
    expect(routeOf('/my-tags').shell).toBe(true)
    expect(routeOf('/account').shell).toBe(true)
    expect(routeOf('/account/keys').shell).toBe(true)
    expect(routeOf('/account/sessions').shell).toBe(true)
  })

  it('routes 发现 and 标签 to their own pages', () => {
    expect(routeOf('/discover').page).toBe(DiscoverPage)
    expect(routeOf('/my-tags').page).toBe(MyTagsPage)
  })

  it('reads a skill address as namespace and name', () => {
    const route = routeOf('/skills/acme/pdf-tools')

    expect(route.shell).toBe(true)
    expect(route.props).toMatchObject({ namespace: 'acme', name: 'pdf-tools' })
    expect(route.props?.version).toBeUndefined()
  })

  it('carries a pinned version as the suffix, never as a number', () => {
    expect(routeOf('/skills/acme/pdf-tools@1.2.3').props).toMatchObject({
      namespace: 'acme',
      name: 'pdf-tools',
      version: '1.2.3',
    })
  })

  it('treats a suffix that names no version as part of the name', () => {
    // `@3` is the integer alias ADR 0033 retired; the server answers such an address the same 404
    // it answers a name that does not exist, and this table agrees with it.
    expect(routeOf('/skills/acme/pdf-tools@3').props).toMatchObject({
      name: 'pdf-tools@3',
    })
  })

  it('keeps the whole path after files/', () => {
    expect(routeOf('/skills/acme/pdf-tools/files/references/x.md').props).toMatchObject({
      namespace: 'acme',
      name: 'pdf-tools',
      relpath: 'references/x.md',
    })
  })

  it('opens the editor for /new and for an address pinned to a draft', () => {
    expect(routeOf('/skills/acme/pdf-tools/new').props).toMatchObject({ mode: 'new' })
    expect(routeOf('/skills/acme/pdf-tools@01J8Z3QK/edit').props).toMatchObject({
      mode: 'edit',
      version: '01J8Z3QK',
    })
  })

  it('sends an unknown address to the list, like any other typo', () => {
    const route = routeOf('/nowhere')

    expect(route.shell).toBe(true)
    expect(route.page).toBe(SkillsPage)
  })
})

/**
 * What the address draws, once the pages are real.
 *
 * The table above answers "which page"; this answers "inside what". The frame is the one thing a
 * person notices without being told — a signed-out form with a navigation around it is a door onto
 * pages they cannot open — and it is decided by `shell` / `standalone` on the route, so it is the
 * half of the table that can only be seen by rendering.
 */
const ACCOUNT = { user_id: '01M3HTG7GCCVBGRPAFFSVSF12W', username: 'demo-user', namespace: 'demo-user' }

const NOT_SIGNED_IN = () => json(401, errorBody('unauthenticated', 'Authentication is required.'))

let location = stubLocation('/')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/')
  session.reset()
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** The app at one address, with the session read (and whatever the page then asks) answered. */
async function show(path: string, ...answers: Array<() => Response>) {
  const [pathname, query] = path.split('?')
  location = stubLocation(pathname ?? '/', query === undefined ? '' : `?${query}`)
  const { requests } = stubFetch(...answers)
  const view = render(<App />)
  await flush()
  return { ...view, requests }
}

describe('what the address draws', () => {
  it('puts a page of the signed-in console inside the frame', async () => {
    const { container } = await show('/', () => json(200, ACCOUNT), () => json(200, EMPTY_LIBRARY))

    expect(container.querySelector('.app')).not.toBeNull()
    expect(container.querySelector('.topbar')).not.toBeNull()
    expect(container.querySelector('.sidenav')).not.toBeNull()
    expect(container.querySelector('.content .page')).not.toBeNull()
  })

  it('draws a visitor’s page with no frame around it', async () => {
    const { container } = await show('/login', NOT_SIGNED_IN)

    expect(container.querySelector('.app')).toBeNull()
    expect(container.querySelector('form')).not.toBeNull()
  })

  it('makes the CLI’s screen the whole page, with no site under it', async () => {
    // The design's own distinction: a card over a page versus a screen that *is* the page. The
    // address is the one `skillmaster login` opens, and a brand line above it would be a second one.
    const address =
      '/cli-sign-in?client_id=skillmaster-cli&state=s&scope=skills%3Aread&code_challenge=c'
    const { container } = await show(address, () => json(200, ACCOUNT))

    expect(container.querySelector('.app')).toBeNull()
    expect(container.querySelector('.overlay.is-standalone')).not.toBeNull()
  })
})

/**
 * The shell: which page an address gets, and the one thing that must happen before any of them
 * render.
 *
 * **Ported from `app.test.ts` of the Vue implementation**, assertions unchanged. Both are the kind
 * of decision that is invisible while it works — a page chosen by the wrong branch looks like a
 * page, and a form that renders before the CSRF cookie exists looks like a form until it is
 * submitted.
 *
 * The addresses are asserted rather than the call order, because the order is a consequence of the
 * gate below and the paths are the contract.
 */
const SHELL_ACCOUNT = ACCOUNT

/** `/web/library` with nothing in it — the shape the empty state is read from (v14). */
const EMPTY_LIBRARY = {
  skills: [],
  page: 1,
  page_size: 10,
  total_entries: 0,
  effective_entries: 0,
  filtered_total: 0,
}
const CAPTCHA = { captcha_id: 'cap-1', image: 'iVBORw0KGgo=' }

const LIVE_VERSION = {
  name: '1.0.0',
  digest: 'sha256:aaa',
  draft_name: '第一版',
  draft_id: null,
  title: 'PDF Tools',
  note: '第一版说明',
  status: 'live' as const,
  submitted_at: '2026-10-05T09:00:00Z',
  submitted_by: 'demo-user',
  state: 'published' as const,
  state_at: '2026-10-05T10:00:00Z',
  first_published_at: '2026-10-05T10:00:00Z',
  is_current: true,
  file_count: 1,
  total_bytes: 90,
  revoked_reason: null,
}

const SECOND_VERSION = {
  ...LIVE_VERSION,
  name: '2.0.0',
  digest: 'sha256:bbb',
  draft_name: '第二版',
  note: '第二版说明',
  status: 'history' as const,
  submitted_at: '2026-10-06T09:00:00Z',
  state_at: '2026-10-06T10:00:00Z',
  first_published_at: '2026-10-06T10:00:00Z',
  is_current: false,
}

/** A version whose author declared no name — addressable only by its digest (ADR 0033). */
const NAMELESS_DIGEST = `sha256:${'ab12cd34'.repeat(8)}`

const SKILL = {
  namespace: { slug: 'demo-user' },
  name: 'pdf-tools',
  title: 'PDF Tools',
  description: '',
  visibility: 'private',
  public_rung: null,
  offline_at: null,
  rung: 'owner',
  pending_rung: null,
  frontmatter: { name: 'pdf-tools' },
  version: LIVE_VERSION,
  versions: [LIVE_VERSION, SECOND_VERSION],
  files: [{ relpath: 'SKILL.md', sha256: 'sha256:ccc', size: 90, is_binary: false }],
  resources: { body: '/web/skills/demo-user/pdf-tools@1.0.0/body' },
}

/** The app at one address. The answers are stubbed by the caller, which also holds the record. */
async function showApp(url: string) {
  const [pathname, query] = url.split('?')
  location = stubLocation(pathname ?? '/', query === undefined ? '' : `?${query}`)
  // Back to "nothing read yet": a second app in the same test would otherwise open already loaded,
  // draw its page before the session read, and spend the first stub on that page's own request.
  session.reset()
  const view = render(<App />)
  await settle()
  return view
}

/**
 * Lets the chain settle: the session read, the render that follows it, the page's own reads, and the
 * render after those.
 *
 * One `flush` is not enough here and was the reason half of these failed at first: the store publishes
 * from a promise continuation, so the page mounts a task later, and its reads land a task after that.
 */
async function settle(): Promise<void> {
  for (let round = 0; round < 8; round += 1) {
    await flush()
  }
}

/** The request that went to this address, or undefined when none did. */
function requested(requests: RecordedRequest[], path: string): RecordedRequest | undefined {
  return requests.find((request) => request.path === path)
}

const text = (element: Element | null): string => (element?.textContent ?? '').trim()

describe('the app shell', () => {
  it('renders nothing but a loading line until the session read comes back', async () => {
    // Not a nicety: the server writes the CSRF cookie while answering a request, so a form rendered
    // before this GET returns would be a form whose first submission is refused.
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))

    const { container } = await showApp('/login')

    expect(container.textContent).toContain('加载中')
    expect(container.querySelector('form')).toBeNull()
  })

  /**
   * The blank wait is `/logout`'s alone. The other `standalone` screens — the CLI's login request
   * among them — keep the loading line, so this pins the scoping rather than the mechanism.
   */
  it('draws the loading line on every standalone page except /logout', () => {
    expect(routeOf('/cli-sign-in').blankWhileLoading).toBeFalsy()
    expect(routeOf('/logout').blankWhileLoading).toBe(true)
    expect(routeOf('/logout').standalone).toBe(true)
  })

  it('makes that read before anything else it does', async () => {
    const { requests } = stubFetch(NOT_SIGNED_IN)

    await showApp('/login')

    expect(requests[0]?.path).toBe('/web/session')
  })

  it('serves the login page at /login', async () => {
    stubFetch(NOT_SIGNED_IN)

    const { container } = await showApp('/login')

    expect(text(container.querySelector('h2'))).toBe('登录')
    // No navigation, because there is nothing behind it that a visitor could open.
    expect(container.querySelector('.sidenav')).toBeNull()
  })

  it('serves the register page at /register', async () => {
    stubFetch(NOT_SIGNED_IN, () => json(200, CAPTCHA))

    const { container } = await showApp('/register')

    expect(text(container.querySelector('h2'))).toBe('注册')
  })

  it('serves the reset page at /reset', async () => {
    stubFetch(NOT_SIGNED_IN, () => json(200, CAPTCHA))

    const { container } = await showApp('/reset')

    expect(text(container.querySelector('h2'))).toBe('找回密码')
  })

  it('serves the consent page at /consent', async () => {
    stubFetch(NOT_SIGNED_IN)

    const { container } = await showApp('/consent')

    expect(text(container.querySelector('h2'))).toBe('授权请求')
  })

  it('serves the login request at /cli-sign-in, which the CLI opens, with no site around it', async () => {
    // The address `skillmaster login` hands the browser. **The card is the whole page**: the design's
    // standalone screen, so there is no brand line above it and no navigation beside it — the card
    // carries its own brand, and there is nothing under this address for a site to be.
    const { requests } = stubFetch(() => json(200, SHELL_ACCOUNT))

    const { container } = await showApp(
      '/cli-sign-in?client_id=skillmaster-cli&state=s1&scope=skills%3Aread&code_challenge=abc',
    )

    expect(requested(requests, '/web/session')).toBeDefined()
    expect(text(container.querySelector('h2'))).toBe('授权请求')
    expect(container.querySelector('h1')).toBeNull()
    expect(container.querySelector('.sidenav')).toBeNull()
    expect(container.querySelector('.dialog-brand')).not.toBeNull()
    // And what it says came from the address rather than from anywhere in the app.
    expect(text(container.querySelector('.consent-scopes li'))).toBe('读取你可见的全部 skill，包括未公开的')
  })

  it('serves the author’s own list at /, which is where the CLI’s deep link lands', async () => {
    const { requests } = stubFetch(() => json(200, SHELL_ACCOUNT), () => json(200, EMPTY_LIBRARY))

    const { container } = await showApp('/')

    expect(requested(requests, '/web/library')).toBeDefined()
    expect(text(container.querySelector('h1'))).toBe('我的 skill')
    expect(container.querySelector('.sidenav')).not.toBeNull()
  })

  it('serves 发现 at /discover', async () => {
    stubFetch(() => json(200, SHELL_ACCOUNT), () => json(200, { skills: [], next_cursor: null }))

    const { container } = await showApp('/discover')

    expect(text(container.querySelector('h1'))).toBe('发现')
    expect(container.querySelector('#page-discover')).not.toBeNull()
  })

  it('serves 标签 at /my-tags, under 管理', async () => {
    stubFetch(() => json(200, SHELL_ACCOUNT), () => json(200, { tags: [] }))

    const { container } = await showApp('/my-tags')

    expect(text(container.querySelector('h1'))).toBe('标签')
    expect(container.querySelector('#navManage')).not.toBeNull()
    // **管理 holds two items since v18, and 待上线 comes first** (the user's order, 2026-10-25): the
    // queue is the daily work list, 标签 is the configuration somebody changes occasionally.
    expect(
      [...container.querySelectorAll('#navManageChildren a')].map((item) => item.getAttribute('href')),
    ).toEqual(['/publish', '/my-tags'])
  })

  it('serves 待上线 at /publish, first under 管理', async () => {
    stubFetch(() => json(200, SHELL_ACCOUNT), () => json(200, { skills: [], truncated: false }))

    const { container } = await showApp('/publish')

    expect(text(container.querySelector('h1'))).toBe('待上线')
    expect(container.querySelector('#page-publish')).not.toBeNull()
  })

  it('serves the account page at /account', async () => {
    stubFetch(() => json(200, SHELL_ACCOUNT))

    const { container } = await showApp('/account')

    expect(text(container.querySelector('h2'))).toBe('账号')
  })

  it('serves the credential screen at /account/keys, beside the account page', async () => {
    // Beside it rather than under it: "who you are" and "what may act as you" are two questions, and
    // the page is where a key comes into existence at all (ADR 0038).
    const { requests } = stubFetch(
      () => json(200, SHELL_ACCOUNT),
      () =>
        json(200, {
          keys: [],
          policy: { expires: true, lifetime_days: 90, latest_day: '2027-01-13' },
        }),
    )

    const { container } = await showApp('/account/keys')

    expect(requested(requests, '/web/keys')).toBeDefined()
    expect(text(container.querySelector('h1'))).toBe('API 密钥')
  })

  it('serves the login devices at /account/sessions, the second child of 权限', async () => {
    // Beside the keys, under the same grouping reason: a key and a login session are the two things
    // that may act as this account (ADR 0043, design/README.md §登录设备).
    const { requests } = stubFetch(() => json(200, SHELL_ACCOUNT), () => json(200, { sessions: [] }))

    const { container } = await showApp('/account/sessions')

    expect(requested(requests, '/web/sessions')).toBeDefined()
    expect(text(container.querySelector('h1'))).toBe('登录设备')
  })

  it('asks before the shell carries a key away from the moment screen', async () => {
    // **The whole page, because the exits this is about belong to the shell.** The moment screen
    // holds its own crumb; the navigation, the brand and the sign-out button are drawn by the shell,
    // and the point of the hold is that *their* handlers do not run either — the question is asked in
    // the capture phase for exactly that reason.
    const { requests } = stubFetch(
      () => json(200, SHELL_ACCOUNT),
      () =>
        json(200, {
          keys: [],
          policy: { expires: true, lifetime_days: 90, latest_day: '2027-01-13' },
        }),
      () =>
        json(201, {
          key: {
            id: 'k9',
            name: 'CI',
            note: '',
            scope: 'skills:read',
            expires_at: '2027-01-13T15:59:59Z',
            last_used_at: null,
            revoked_at: null,
            created_at: '2026-10-15T01:00:00Z',
          },
          token: 'skm_x',
        }),
      () => json(200, {}),
    )
    const { container } = await showApp('/account/keys')

    fireEvent.click(container.querySelector('[data-key-new]') as HTMLElement)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: 'CI' },
    })
    fireEvent.submit(container.querySelector('.key-form') as HTMLFormElement)
    await flush()
    expect(container.textContent).toContain('密钥已创建')

    // The navigation's own link: held, and the string is still here.
    fireEvent.click(container.querySelector('.sidenav a[href="/account"]') as HTMLElement)
    expect(text(container.querySelector('.key-exit .hint'))).toBe(
      '这把密钥还没复制——离开后取不回来，只能撤销后重建。',
    )

    // **The brand leaves for the marketing site, and is held like every other exit** (v28): it is an
    // `a[href]` like the navigation, so changing where it points must not create a quiet way out. The
    // question is answered 留在这一屏 first, so the rest of this test starts from no question.
    fireEvent.click(container.querySelectorAll('.key-exit .link-button')[1] as HTMLElement)
    const brand = container.querySelector('a.brand') as HTMLAnchorElement
    expect(brand.getAttribute('href')).toBe('http://localhost:5174/')
    fireEvent.click(brand)
    expect(container.querySelector('.key-exit .link-button')).not.toBeNull()
    expect(location.assign).not.toHaveBeenCalled()
    fireEvent.click(container.querySelectorAll('.key-exit .link-button')[1] as HTMLElement)
    expect(container.querySelector('.key-exit .link-button')).toBeNull()

    // The one control here that navigates without being a link. Opening the menu is not a
    // navigation, so it is not held; pressing 退出登录 is, and **nothing of the shell's own handler
    // may have happened** — no request, no redirect.
    fireEvent.click(container.querySelector('.me') as HTMLElement)
    fireEvent.click(container.querySelector('[data-sign-out]') as HTMLElement)
    await flush()
    expect(container.querySelector('.key-exit .link-button')).not.toBeNull()
    expect(requested(requests, '/web/logout')).toBeUndefined()
    expect(location.assign).not.toHaveBeenCalled()

    // **A second exit while the question stands is held too, and the question moves to it.** Waving it
    // through because one is already open is the same silent departure this screen exists to stop.
    // The top bar's search is a form rather than a link, and the shell submits it with `.prevent` — so
    // a capture listener that only called `preventDefault` would leave the shell's own handler free to
    // navigate. Held, and the address does not move.
    fireEvent.change(container.querySelector('.topbar-search input') as HTMLInputElement, {
      target: { value: 'pdf' },
    })
    fireEvent.submit(container.querySelector('.topbar-search') as HTMLFormElement)
    await flush()
    expect(container.querySelector('.key-exit .link-button')).not.toBeNull()
    expect(location.assign).not.toHaveBeenCalled()

    // Answering 留在这一屏 hands the block back; the next exit asks again, and 仍然离开 re-runs the
    // gesture that was pressed last — the search, whose own handler is what navigates.
    fireEvent.click(container.querySelectorAll('.key-exit .link-button')[1] as HTMLElement)
    expect(container.querySelector('.key-exit .link-button')).toBeNull()
    fireEvent.submit(container.querySelector('.topbar-search') as HTMLFormElement)
    await flush()
    fireEvent.click(container.querySelectorAll('.key-exit .link-button')[0] as HTMLElement)
    await flush()
    expect(location.assign).toHaveBeenCalledWith('/?q=pdf')

    // And the control whose handler is a request rather than a redirect: the sign-out button, whose
    // own handler must run — once, and only after the answer.
    fireEvent.click(container.querySelector('[data-sign-out]') as HTMLElement)
    await flush()
    fireEvent.click(container.querySelectorAll('.key-exit .link-button')[0] as HTMLElement)
    await flush()
    expect(requested(requests, '/web/logout')).toBeDefined()
  })

  it('serves the same list at /skills, which is what the CLI opens', async () => {
    const { requests } = stubFetch(() => json(200, SHELL_ACCOUNT), () => json(200, EMPTY_LIBRARY))

    const { container } = await showApp('/skills')

    expect(requested(requests, '/web/library')).toBeDefined()
    expect(text(container.querySelector('h1'))).toBe('我的 skill')
  })

  it('serves one skill at /skills/<namespace>/<name>, with the version the address named', async () => {
    // The version travels in the path rather than in the page's own state, which is what makes a
    // version link a real link: the address a browser shows is the version being looked at, so a
    // reload, a bookmark and the link the CLI opens all land on the same thing.
    const noSuchSkill = () => json(404, errorBody('skill_not_found', 'No such skill.'))
    const { requests } = stubFetch(() => json(200, SHELL_ACCOUNT), noSuchSkill, noSuchSkill)

    const { container } = await showApp('/skills/demo-user/pdf-tools@2.0.0')

    expect(requested(requests, '/web/skills/demo-user/pdf-tools@2.0.0')).toBeDefined()
    // No `/body` fetch: since v5 the page renders what the detail already carries, and the body is
    // opened from the file list like any other file.
    expect(requested(requests, '/web/skills/demo-user/pdf-tools@2.0.0/body')).toBeUndefined()
    // A 404 here is an address that names nothing, not a failure to report — see SkillPage.
    expect(text(container.querySelector('h2'))).toBe('没有这个 skill')
  })

  it('serves a digest-pinned address, which is how a nameless version is reached', async () => {
    // ADR 0033: a version whose author declared no name has no name at all, so the only way to pin it
    // is `@sha256:<hex>` — a suffix the validator has to accept alongside a semver.
    const noSuchSkill = () => json(404, errorBody('skill_not_found', 'No such skill.'))
    const { requests } = stubFetch(() => json(200, SHELL_ACCOUNT), noSuchSkill, noSuchSkill)

    await showApp(`/skills/demo-user/pdf-tools@${NAMELESS_DIGEST}`)

    expect(requested(requests, `/web/skills/demo-user/pdf-tools@${NAMELESS_DIGEST}`)).toBeDefined()
  })

  it('treats a retired integer suffix as part of the name, not as a version', async () => {
    // ADR 0033 removed `@N`: the server parses `pdf-tools@3` as a skill literally called that and
    // answers its one 404, so the app must not invent a version the server no longer resolves. It
    // falls back exactly the way a missing suffix does — the suffix names nothing, so the segment is
    // the name.
    const noSuchSkill = () => json(404, errorBody('skill_not_found', 'No such skill.'))
    const { requests } = stubFetch(() => json(200, SHELL_ACCOUNT), noSuchSkill, noSuchSkill)

    const { container } = await showApp('/skills/demo-user/pdf-tools@3')

    // The whole segment goes as the name, `@` percent-encoded — a version would have kept it literal.
    expect(requested(requests, '/web/skills/demo-user/pdf-tools%403')).toBeDefined()
    expect(text(container.querySelector('h2'))).toBe('没有这个 skill')
  })

  it('serves a comparison at /skills/<namespace>/<name>/diff, with string versions in the query', async () => {
    // The page reads the skill first (it needs the version list to say what is live), then the
    // comparison it was asked for.
    const { requests } = stubFetch(
      () => json(200, SHELL_ACCOUNT),
      () => json(200, { ...SKILL, rung: 'maintain' }),
      () => json(200, { from: '1.0.0', to: '2.0.0', truncated: false, next: null, files: [] }),
    )

    const { container } = await showApp('/skills/demo-user/pdf-tools/diff?from=1.0.0&to=2.0.0')

    // `to` names the version being compared, so it is the one in the address — and `from` is the
    // baseline, which is a query parameter because a version is not always the answer.
    expect(requested(requests, '/web/skills/demo-user/pdf-tools@2.0.0/diff?from=1.0.0')).toBeDefined()
    // And the detail is read for **that** version, not by following the pointer: the address carries
    // no `@`, so following it would put the live version in the picker while the diff below compared
    // the live version against something else.
    expect(requested(requests, '/web/skills/demo-user/pdf-tools@2.0.0')).toBeDefined()
    // The legacy alias opens the version tab's 改动 pane, not a second screen.
    expect(container.querySelector('[data-vpane="diff"]')).not.toBeNull()
  })

  it('serves one file at /skills/<namespace>/<name>/files/<relpath>', async () => {
    const { requests } = stubFetch(
      () => json(200, SHELL_ACCOUNT),
      () => json(200, SKILL),
      // The word groups and the vocabulary are read on every tab, before the file's own read.
      () =>
        json(200, {
          category: null,
          public_tags: [],
          my_tags: { readable: false, reason: 'not_in_library', tags: [] },
          library: { source: 'created', active: true, joinable: false, can_remove: false },
        }),
      () => json(200, { categories: [] }),
      () => new Response('# PDF Tools\n\n正文\n', { status: 200 }),
      // The settings nav's queue size, read after the file on a page this rung can administer.
      () => json(200, { requests: [], total: 0 }),
    )

    const { container } = await showApp('/skills/demo-user/pdf-tools/files/SKILL.md')

    // The resolved version is written into the file address rather than left to the pointer: a file
    // that moved between versions must be the one belonging to the version on screen.
    expect(requested(requests, '/web/skills/demo-user/pdf-tools@1.0.0/files/SKILL.md')).toBeDefined()
    expect(container.querySelector('.doc')?.textContent).toContain('正文')
  })

  it('takes the pinned version of a comparison from the address, not only from the query', async () => {
    // `?to=` is what this app's own links carry, but `@2.0.0/diff` is an address the server accepts
    // too — and ignoring its `@2.0.0` answered a comparison against the live version while the bar
    // said that version.
    const { requests } = stubFetch(
      () => json(200, SHELL_ACCOUNT),
      () => json(200, { ...SKILL, rung: 'maintain' }),
      () => json(200, { from: '1.0.0', to: '2.0.0', truncated: false, next: null, files: [] }),
    )

    await showApp('/skills/demo-user/pdf-tools@2.0.0/diff')

    // v6's default baseline is the previous published version, so the comparison asks for 1.0.0
    // even when the address named no baseline.
    expect(requested(requests, '/web/skills/demo-user/pdf-tools@2.0.0/diff?from=1.0.0')).toBeDefined()
  })

  it('keeps a relpath with slashes in it as several path segments', async () => {
    // Encoding the whole relpath would turn the separator into `%2F`, which the servlet firewall
    // rejects before routing — so this is the rule that makes a nested reference openable at all.
    const nested = {
      ...SKILL,
      files: [{ relpath: 'references/x.md', sha256: 'sha256:d', size: 10, is_binary: false }],
    }
    const { requests } = stubFetch(
      () => json(200, SHELL_ACCOUNT),
      () => json(200, nested),
      () => new Response('# x\n', { status: 200 }),
      () => json(200, { requests: [], total: 0 }),
    )

    await showApp('/skills/demo-user/pdf-tools/files/references/x.md')

    expect(
      requested(requests, '/web/skills/demo-user/pdf-tools@1.0.0/files/references/x.md'),
    ).toBeDefined()
  })

  it('decodes the address it read from the bar, and encodes it again on the way out', async () => {
    // Skill names may be non-ASCII and may hold a space, so the path the browser shows and the path
    // the server is asked for are not the same string. Both segments go through the decode, and the
    // page encodes each of them back rather than passing the raw value on.
    const noSuchSkill = () => json(404, errorBody('skill_not_found', 'No such skill.'))
    const { requests } = stubFetch(() => json(200, SHELL_ACCOUNT), noSuchSkill, noSuchSkill)

    await showApp('/skills/demo%20user/pdf%20tools')

    expect(requested(requests, '/web/skills/demo%20user/pdf%20tools')).toBeDefined()
  })

  it('falls back to the list for a path it does not know', async () => {
    // A handful of paths and a default, rather than a 404 page: the paths are the SPA's own, and the
    // one that can be reached by a typo is the one that shows where to go next.
    const { requests } = stubFetch(() => json(200, SHELL_ACCOUNT), () => json(200, EMPTY_LIBRARY))

    const { container } = await showApp('/nonsense')

    expect(requested(requests, '/web/library')).toBeDefined()
    expect(text(container.querySelector('h1'))).toBe('我的 skill')
  })

  it('serves the editor at /new and at a draft’s @<pin>/edit', async () => {
    // Two addresses, one page: `/new` holds a base in memory until the first content change, and
    // `@<code>/edit` opens a row that already exists. The suffix is a version pin like any other,
    // so the page is reached the same way every other version-specific view is.
    const draft = {
      ...LIVE_VERSION,
      name: null,
      digest: 'sha256:draft',
      draft_name: '草稿',
      draft_id: 'DRAFT001',
      status: 'draft',
      state: 'draft',
      state_at: null,
      first_published_at: null,
      is_current: false,
    }
    const body = () => new Response('# body\n', { status: 200 })

    const fresh = stubFetch(() => json(200, SHELL_ACCOUNT), () => json(200, SKILL), body)
    const newPage = await showApp('/skills/demo-user/pdf-tools/new')
    expect(requested(fresh.requests, '/web/skills/demo-user/pdf-tools')).toBeDefined()
    expect(newPage.container.querySelector('#page-editor')).not.toBeNull()
    expect(text(newPage.container.querySelector('.crumbs-here'))).toBe('新版本')
    // Gone before the next address is rendered: two Apps at once would have the first one's page
    // reading from the second one's stubs.
    newPage.unmount()

    const edit = stubFetch(
      () => json(200, SHELL_ACCOUNT),
      () => json(200, { ...SKILL, version: draft, versions: [draft] }),
      body,
    )
    const editPage = await showApp('/skills/demo-user/pdf-tools@DRAFT001/edit')
    expect(requested(edit.requests, '/web/skills/demo-user/pdf-tools@DRAFT001')).toBeDefined()
    expect(editPage.container.querySelector('#page-editor')).not.toBeNull()
    expect(text(editPage.container.querySelector('.crumbs-here'))).toBe('编辑')
    editPage.unmount()
  })

  it('treats a suffix it does not recognise as a path it does not know', async () => {
    // `/skills/<ns>/<name>/<anything else>` is not an address: a skill's name cannot contain a slash,
    // so the third segment is either `diff`, `files/…`, or a typo.
    const { requests } = stubFetch(() => json(200, SHELL_ACCOUNT), () => json(200, EMPTY_LIBRARY))

    const { container } = await showApp('/skills/demo-user/pdf-tools/nonsense')

    expect(requested(requests, '/web/library')).toBeDefined()
    expect(text(container.querySelector('h1'))).toBe('我的 skill')
  })
})
