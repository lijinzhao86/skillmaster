import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import {
  clearCookies,
  errorBody,
  flush,
  json,
  setCookie,
  stubFetch,
  stubLocation,
  type RecordedRequest,
} from './support'

/**
 * The shell: which page an address gets, and the one thing that must happen before any of them render.
 *
 * Both are the kind of decision that is invisible while it works — a page chosen by the wrong branch
 * looks like a page, and a form that renders before the CSRF cookie exists looks like a form until
 * it is submitted.
 *
 * The addresses are asserted rather than the call order, because the order is a consequence of the
 * gate below and the paths are the contract.
 */

const ACCOUNT = { user_id: '01M3HTG7GCCVBGRPAFFSVSF12W', username: 'demo-user', namespace: 'demo-user' }
const CAPTCHA = { captcha_id: 'cap-1', image: 'iVBORw0KGgo=' }
const NOT_SIGNED_IN = () => json(401, errorBody('unauthenticated', 'Authentication is required.'))

const LIVE_VERSION = {
  name: '1.0.0',
  digest: 'sha256:aaa',
  submitted_at: '2026-10-05T09:00:00Z',
  state: 'published' as const,
  state_at: '2026-10-05T10:00:00Z',
  is_current: true,
  file_count: 1,
  total_bytes: 90,
}

/** A version whose author declared no name — addressable only by its digest (ADR 0033). */
const NAMELESS_DIGEST = `sha256:${'ab12cd34'.repeat(8)}`

const SKILL = {
  namespace: { slug: 'demo-user' },
  name: 'pdf-tools',
  title: 'PDF Tools',
  description: '',
  visibility: 'private',
  frontmatter: { name: 'pdf-tools' },
  version: LIVE_VERSION,
  versions: [LIVE_VERSION],
  files: [{ relpath: 'SKILL.md', sha256: 'sha256:ccc', size: 90, is_binary: false }],
  resources: { body: '/web/skills/demo-user/pdf-tools@1.0.0/body' },
}

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
 * A fresh App, because the session it reads lives at module level.
 *
 * The address may carry a query, because a comparison keeps its two versions there and the page
 * reads them from `window.location` — a stub that dropped the query would be testing an address the
 * browser can never show.
 */
async function showApp(url: string): Promise<VueWrapper> {
  const [path, query] = url.split('?')
  location = stubLocation(path ?? '/', query === undefined ? '' : `?${query}`)
  vi.resetModules()
  const { default: App } = await import('../src/App.vue')
  const wrapper = mount(App)
  await flush()
  return wrapper
}

/** The request that went to this address, or undefined when none did. */
function requested(requests: RecordedRequest[], path: string): RecordedRequest | undefined {
  return requests.find((request) => request.path === path)
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
    // No navigation, because there is nothing behind it that a visitor could open.
    expect(wrapper.find('.sidenav').exists()).toBe(false)
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

  it('serves the consent page at /consent', async () => {
    stubFetch(NOT_SIGNED_IN)

    const wrapper = await showApp('/consent')

    expect(wrapper.find('h2').text()).toBe('授权请求')
    wrapper.unmount()
  })

  it('serves the author’s own list at /, which is where the CLI’s deep link lands', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT), () => json(200, { skills: [] }))

    const wrapper = await showApp('/')

    expect(requested(requests, '/web/skills')).toBeDefined()
    expect(wrapper.find('h2').text()).toBe('我的 skill')
    expect(wrapper.find('.sidenav').exists()).toBe(true)
    wrapper.unmount()
  })

  it('serves the account page at /account', async () => {
    stubFetch(() => json(200, ACCOUNT))

    const wrapper = await showApp('/account')

    expect(wrapper.find('h2').text()).toBe('账号')
    wrapper.unmount()
  })

  it('serves the same list at /skills, which is what the CLI opens', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT), () => json(200, { skills: [] }))

    const wrapper = await showApp('/skills')

    expect(requested(requests, '/web/skills')).toBeDefined()
    expect(wrapper.find('h2').text()).toBe('我的 skill')
    wrapper.unmount()
  })

  it('serves one skill at /skills/<namespace>/<name>, with the version the address named', async () => {
    // The version travels in the path rather than in the page's own state, which is what makes a
    // version link a real link: the address a browser shows is the version being looked at, so a
    // reload, a bookmark and the link the CLI opens all land on the same thing.
    const noSuchSkill = () => json(404, errorBody('skill_not_found', 'No such skill.'))
    const { requests } = stubFetch(() => json(200, ACCOUNT), noSuchSkill, noSuchSkill)

    const wrapper = await showApp('/skills/demo-user/pdf-tools@2.0.0')

    expect(requested(requests, '/web/skills/demo-user/pdf-tools@2.0.0')).toBeDefined()
    expect(requested(requests, '/web/skills/demo-user/pdf-tools@2.0.0/body')).toBeDefined()
    // A 404 here is an address that names nothing, not a failure to report — see SkillPage.
    expect(wrapper.find('h2').text()).toBe('没有这个 skill')
    wrapper.unmount()
  })

  it('serves a digest-pinned address, which is how a nameless version is reached', async () => {
    // ADR 0033: a version whose author declared no name has no name at all, so the only way to pin it
    // is `@sha256:<hex>` — a suffix the validator has to accept alongside a semver.
    const noSuchSkill = () => json(404, errorBody('skill_not_found', 'No such skill.'))
    const { requests } = stubFetch(() => json(200, ACCOUNT), noSuchSkill, noSuchSkill)

    const wrapper = await showApp(`/skills/demo-user/pdf-tools@${NAMELESS_DIGEST}`)

    expect(requested(requests, `/web/skills/demo-user/pdf-tools@${NAMELESS_DIGEST}`)).toBeDefined()
    wrapper.unmount()
  })

  it('treats a retired integer suffix as part of the name, not as a version', async () => {
    // ADR 0033 removed `@N`: the server parses `pdf-tools@3` as a skill literally called that and
    // answers its one 404, so the app must not invent a version the server no longer resolves. It
    // falls back exactly the way a missing suffix does — the suffix names nothing, so the segment is
    // the name.
    const noSuchSkill = () => json(404, errorBody('skill_not_found', 'No such skill.'))
    const { requests } = stubFetch(() => json(200, ACCOUNT), noSuchSkill, noSuchSkill)

    const wrapper = await showApp('/skills/demo-user/pdf-tools@3')

    // The whole segment goes as the name, `@` percent-encoded — a version would have kept it literal.
    expect(requested(requests, '/web/skills/demo-user/pdf-tools%403')).toBeDefined()
    expect(wrapper.find('h2').text()).toBe('没有这个 skill')
    wrapper.unmount()
  })

  it('serves a comparison at /skills/<namespace>/<name>/diff, with string versions in the query', async () => {
    const { requests } = stubFetch(
      () => json(200, ACCOUNT),
      () => json(200, { from: '1.0.0', to: '2.0.0', truncated: false, files: [] }),
      () => json(200, SKILL),
    )

    const wrapper = await showApp('/skills/demo-user/pdf-tools/diff?from=1.0.0&to=2.0.0')

    // `to` names the version being compared, so it is the one in the address — and `from` is the
    // baseline, which is a query parameter because a version is not always the answer.
    expect(requested(requests, '/web/skills/demo-user/pdf-tools@2.0.0/diff?from=1.0.0')).toBeDefined()
    // And the detail is read for **that** version, not by following the pointer: the address carries
    // no `@`, so following it would put the live version in the picker while the diff below compared
    // the live version against something else.
    expect(requested(requests, '/web/skills/demo-user/pdf-tools@2.0.0')).toBeDefined()
    expect(wrapper.find('.diff').exists()).toBe(true)
    wrapper.unmount()
  })

  it('serves one file at /skills/<namespace>/<name>/files/<relpath>', async () => {
    const { requests } = stubFetch(
      () => json(200, ACCOUNT),
      () => json(200, SKILL),
      () => new Response('# PDF Tools\n\n正文\n', { status: 200 }),
    )

    const wrapper = await showApp('/skills/demo-user/pdf-tools/files/SKILL.md')

    // The resolved version is written into the file address rather than left to the pointer: a file
    // that moved between versions must be the one belonging to the version on screen.
    expect(requested(requests, '/web/skills/demo-user/pdf-tools@1.0.0/files/SKILL.md')).toBeDefined()
    expect(wrapper.find('.markdown-body').text()).toContain('正文')
    wrapper.unmount()
  })

  it('takes the pinned version of a comparison from the address, not only from the query', async () => {
    // `?to=` is what this app's own links carry, but `@2.0.0/diff` is an address the server accepts
    // too — and ignoring its `@2.0.0` answered a comparison against the live version while the bar
    // said that version.
    const { requests } = stubFetch(
      () => json(200, ACCOUNT),
      () => json(200, { from: '1.0.0', to: '2.0.0', truncated: false, files: [] }),
      () => json(200, SKILL),
    )

    const wrapper = await showApp('/skills/demo-user/pdf-tools@2.0.0/diff')

    expect(requested(requests, '/web/skills/demo-user/pdf-tools@2.0.0/diff')).toBeDefined()
    wrapper.unmount()
  })

  it('keeps a relpath with slashes in it as several path segments', async () => {
    // Encoding the whole relpath would turn the separator into `%2F`, which the servlet firewall
    // rejects before routing — so this is the rule that makes a nested reference openable at all.
    const nested = { ...SKILL, files: [{ relpath: 'references/x.md', sha256: 'sha256:d', size: 10, is_binary: false }] }
    const { requests } = stubFetch(
      () => json(200, ACCOUNT),
      () => json(200, nested),
      () => new Response('# x\n', { status: 200 }),
    )

    const wrapper = await showApp('/skills/demo-user/pdf-tools/files/references/x.md')

    expect(requested(requests, '/web/skills/demo-user/pdf-tools@1.0.0/files/references/x.md')).toBeDefined()
    wrapper.unmount()
  })

  it('decodes the address it read from the bar, and encodes it again on the way out', async () => {
    // Skill names may be non-ASCII and may hold a space, so the path the browser shows and the path
    // the server is asked for are not the same string. Both segments go through the decode, and the
    // page encodes each of them back rather than passing the raw value on.
    const noSuchSkill = () => json(404, errorBody('skill_not_found', 'No such skill.'))
    const { requests } = stubFetch(() => json(200, ACCOUNT), noSuchSkill, noSuchSkill)

    const wrapper = await showApp('/skills/demo%20user/pdf%20tools')

    expect(requested(requests, '/web/skills/demo%20user/pdf%20tools')).toBeDefined()
    wrapper.unmount()
  })

  it('falls back to the list for a path it does not know', async () => {
    // A handful of paths and a default, rather than a 404 page: the paths are the SPA's own, and the
    // one that can be reached by a typo is the one that shows where to go next.
    const { requests } = stubFetch(() => json(200, ACCOUNT), () => json(200, { skills: [] }))

    const wrapper = await showApp('/nonsense')

    expect(requested(requests, '/web/skills')).toBeDefined()
    expect(wrapper.find('h2').text()).toBe('我的 skill')
    wrapper.unmount()
  })

  it('treats a suffix it does not recognise as a path it does not know', async () => {
    // `/skills/<ns>/<name>/<anything else>` is not an address: a skill's name cannot contain a slash,
    // so the third segment is either `diff`, `files/…`, or a typo.
    const { requests } = stubFetch(() => json(200, ACCOUNT), () => json(200, { skills: [] }))

    const wrapper = await showApp('/skills/demo-user/pdf-tools/nonsense')

    expect(requested(requests, '/web/skills')).toBeDefined()
    expect(wrapper.find('h2').text()).toBe('我的 skill')
    wrapper.unmount()
  })
})
