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
 * The author's pages: the list, one skill, the comparison, one file, and the two writes.
 *
 * The protocol is what these assert — which address, which method, which body — because everything
 * else about them is a rendering of what the server said. Several assertions are about what is *not*
 * rendered: a count for a file that was never diffed, a banner on a comparison between two versions
 * of the past, a fetch for a binary. All three would look plausible and be wrong.
 */

const DRAFT_VERSION = {
  name: '2.0.0',
  digest: 'sha256:bbb',
  submitted_at: '2026-10-06T09:00:00Z',
  state: 'draft' as const,
  state_at: null,
  is_current: false,
  file_count: 2,
  total_bytes: 120,
}

const LIVE_VERSION = {
  name: '1.0.0',
  digest: 'sha256:aaa',
  submitted_at: '2026-10-05T09:00:00Z',
  state: 'published' as const,
  state_at: '2026-10-05T10:00:00Z',
  is_current: true,
  file_count: 2,
  total_bytes: 110,
}

/** A version whose author declared no name — addressable only by its digest (ADR 0033). */
const NAMELESS_DIGEST = `sha256:${'ab12cd34'.repeat(8)}`

/** The version on screen when the address names nothing: a draft, which is what the banner is for. */
const SKILL = {
  namespace: { slug: 'demo-user' },
  name: 'pdf-tools',
  title: 'PDF Tools',
  description: '把 PDF 拆开再拼起来',
  visibility: 'private',
  frontmatter: { name: 'pdf-tools' },
  version: DRAFT_VERSION,
  versions: [DRAFT_VERSION, LIVE_VERSION],
  files: [{ relpath: 'SKILL.md', sha256: 'sha256:ccc', size: 90, is_binary: false }],
  resources: { body: '/web/skills/demo-user/pdf-tools@2.0.0/body' },
}

const DIFF = {
  from: '1.0.0',
  to: '2.0.0',
  truncated: false,
  files: [
    {
      relpath: 'SKILL.md',
      status: 'modified',
      binary: false,
      added: 1,
      removed: 1,
      hunks: [{ header: '@@ -1,2 +1,2 @@', lines: [' # PDF Tools', '-第一版', '+第二版'] }],
    },
    {
      relpath: 'icon.png',
      status: 'modified',
      binary: true,
      added: null,
      removed: null,
      hunks: null,
    },
  ],
}

const NOT_SIGNED_IN = () => json(401, errorBody('unauthenticated', 'Authentication is required.'))

let location = stubLocation('/skills')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/skills')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function showSkills(listResponse: () => Response): Promise<VueWrapper> {
  stubFetch(listResponse)
  vi.resetModules()
  const { default: SkillsPage } = await import('../src/pages/SkillsPage.vue')
  const wrapper = mount(SkillsPage)
  await flush()
  return wrapper
}

/** One row of the listing, as the server sends it. */
function row(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    namespace: 'demo-user',
    name: 'pdf-tools',
    title: 'PDF Tools',
    description: '',
    visibility: 'private',
    current: null,
    drafts: 0,
    draft: null,
    latest_submitted_at: '2026-10-06T09:00:00Z',
    ...overrides,
  }
}

/** Mounts the content page with the three reads it makes on the way in already answered. */
async function showSkill(
  skillResponse: () => Response,
  options: { body?: () => Response; diff?: () => Response; version?: string } = {},
): Promise<VueWrapper> {
  stubFetch(
    skillResponse,
    options.body ?? (() => new Response('# PDF Tools\n\n第二版\n', { status: 200 })),
    options.diff ?? (() => json(200, DIFF)),
  )
  vi.resetModules()
  const { default: SkillPage } = await import('../src/pages/SkillPage.vue')
  const wrapper = mount(SkillPage, {
    props: {
      namespace: 'demo-user',
      name: 'pdf-tools',
      ...(options.version === undefined ? {} : { version: options.version }),
    },
  })
  await flush()
  return wrapper
}

/** The comparison is read first, and the detail for whatever version it turns out to be about. */
async function showDiff(
  skillResponse: () => Response,
  diffResponse: () => Response,
  props: { version?: string; from?: string; to?: string } = { to: '2.0.0' },
): Promise<VueWrapper> {
  stubFetch(diffResponse, skillResponse)
  vi.resetModules()
  const { default: SkillDiffPage } = await import('../src/pages/SkillDiffPage.vue')
  const wrapper = mount(SkillDiffPage, { props: { namespace: 'demo-user', name: 'pdf-tools', ...props } })
  await flush()
  return wrapper
}

async function showFile(
  skillResponse: () => Response,
  fileResponse: () => Response,
  relpath = 'SKILL.md',
): Promise<{ wrapper: VueWrapper; requests: RecordedRequest[] }> {
  const stub = stubFetch(skillResponse, fileResponse)
  vi.resetModules()
  const { default: SkillFilePage } = await import('../src/pages/SkillFilePage.vue')
  const wrapper = mount(SkillFilePage, { props: { namespace: 'demo-user', name: 'pdf-tools', relpath } })
  await flush()
  return { wrapper, requests: stub.requests }
}

describe('the skills list', () => {
  it('shows a skill nothing has been published from, and links to what is waiting', async () => {
    const wrapper = await showSkills(() =>
      json(200, { skills: [row({ drafts: 2, draft: { name: '3.0.0', digest: 'sha256:ddd' } })] }),
    )

    expect(wrapper.text()).toContain('pdf-tools')
    expect(wrapper.text()).toContain('未上线')
    expect(wrapper.find('a.skill-name').attributes('href')).toBe('/skills/demo-user/pdf-tools')
    // The count goes straight to the version that is waiting rather than to the skill: the person
    // clicking it has already decided what they want to look at.
    expect(wrapper.find('a.pending-link').attributes('href')).toBe('/skills/demo-user/pdf-tools@3.0.0')
    expect(wrapper.find('a.pending-link').text()).toContain('2 个待上线')
    wrapper.unmount()
  })

  it('names the live version, and offers nothing when nothing is waiting', async () => {
    const wrapper = await showSkills(() =>
      json(200, { skills: [row({ current: { name: '4.0.0', digest: 'sha256:aaa' } })] }),
    )

    expect(wrapper.find('.chip').text()).toContain('已上线 @4.0.0')
    expect(wrapper.find('.chip').classes()).toContain('is-live')
    // Null rather than a link to nothing: there is no version to go to.
    expect(wrapper.find('a.pending-link').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not trail off when every version has been discarded', async () => {
    // `latest_submitted_at` is `max(submitted_at) WHERE state <> 'discarded'`, so a skill whose work
    // was all thrown away has no submission time — and "更新于" with nothing after it is the page
    // trailing off rather than saying what happened.
    const wrapper = await showSkills(() =>
      json(200, { skills: [row({ latest_submitted_at: null })] }),
    )

    expect(wrapper.text()).toContain('每一版都被丢弃了')
    expect(wrapper.text()).not.toContain('更新于')
    wrapper.unmount()
  })

  it('says how the first skill gets here when there are none', async () => {
    const wrapper = await showSkills(() => json(200, { skills: [] }))

    expect(wrapper.text()).toContain('还没有提交过 skill')
    expect(wrapper.text()).toContain('skillmaster skill submit')
    wrapper.unmount()
  })

  it('sends somebody who is not signed in to the login form, and says where to come back to', async () => {
    const wrapper = await showSkills(NOT_SIGNED_IN)

    expect(location.assign).toHaveBeenCalledWith('/login?return_to=%2Fskills')
    wrapper.unmount()
  })

  it('says what went wrong rather than pretending the list is empty', async () => {
    const wrapper = await showSkills(() =>
      json(500, errorBody('internal_error', 'Something went wrong.')),
    )

    expect(wrapper.find('.banner').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('还没有提交过 skill')
    wrapper.unmount()
  })
})

describe('one skill', () => {
  it('offers every version in the picker, grouped by what was decided about each', async () => {
    const superseded = { ...LIVE_VERSION, name: '3.0.0', is_current: false }
    const discarded = { ...DRAFT_VERSION, name: '4.0.0', state: 'discarded' as const, state_at: '2026-10-06T11:00:00Z' }
    const wrapper = await showSkill(() =>
      json(200, { ...SKILL, versions: [discarded, superseded, DRAFT_VERSION, LIVE_VERSION] }),
    )

    const groups = wrapper.findAll('optgroup')
    expect(groups.map((group) => group.attributes('label'))).toEqual(['线上', '待处理', '历史'])
    // A line is the version alone where the heading above it already says the state, and exact strings
    // are what pin that there is nothing else on it — no state repeated, no timestamp.
    expect(groups[0]?.findAll('option').map((option) => option.text())).toEqual(['@1.0.0'])
    expect(groups[1]?.findAll('option').map((option) => option.text())).toEqual(['@2.0.0'])
    // `历史` is the heading that cannot say it: a superseded version can be published again and a
    // discarded one never can, so that one word stays. Within it the order is the server's, newest
    // submission first — nothing here sorts by version name, which is a different question (ADR 0033).
    expect(groups[2]?.findAll('option').map((option) => option.text())).toEqual(['@4.0.0 · 已丢弃', '@3.0.0'])
    wrapper.unmount()
  })

  it('shows a version with no name as the short form of its digest', async () => {
    // ADR 0033: a version whose author declared no name has no name at all. It is still addressable,
    // and the address carries the full digest while the label carries git's 8-character abbreviation.
    const nameless = { ...DRAFT_VERSION, name: null, digest: NAMELESS_DIGEST }
    const wrapper = await showSkill(() =>
      json(200, { ...SKILL, version: nameless, versions: [nameless, LIVE_VERSION] }),
    )

    expect(wrapper.find('.pending-title').text()).toContain('@ab12cd34')
    // Encoded, so the `:` of `sha256:` survives the round trip — and so that a version carrying
    // build metadata would too, since a bare `+` in a query string means a space.
    expect(wrapper.find('a.button-link').attributes('href')).toBe(
      `/skills/demo-user/pdf-tools/diff?to=${encodeURIComponent(NAMELESS_DIGEST)}`,
    )
    wrapper.unmount()
  })

  it('leaves out a group with nothing in it', async () => {
    // An empty "待处理" heading would read as a bug rather than as "nothing is waiting".
    const wrapper = await showSkill(() => json(200, { ...SKILL, version: LIVE_VERSION, versions: [LIVE_VERSION] }))

    expect(wrapper.findAll('optgroup').map((group) => group.attributes('label'))).toEqual(['线上'])
    wrapper.unmount()
  })

  it('goes to the version that was picked, as a real address', async () => {
    // The version lives in the path, so a reload, a bookmark and a shared link all land on the same
    // version — which is the whole reason the picker navigates rather than switching in place.
    const wrapper = await showSkill(() => json(200, SKILL))

    await wrapper.find('select').setValue('1.0.0')
    await flush()

    expect(location.assign).toHaveBeenCalledWith('/skills/demo-user/pdf-tools@1.0.0')
    wrapper.unmount()
  })

  it('says what publishing the version on screen would change', async () => {
    const wrapper = await showSkill(() => json(200, SKILL))

    const banner = wrapper.find('.pending')
    expect(banner.text()).toContain('等你上线：@2.0.0')
    expect(banner.text()).toContain('比线上多 2 个文件的改动（+1 −1）')
    // The comparison is a page of its own, so the banner links to it rather than reproducing it.
    expect(banner.find('a.button-link').attributes('href')).toBe('/skills/demo-user/pdf-tools/diff?to=2.0.0')
    expect(banner.find('button').text()).toBe('上线')
    wrapper.unmount()
  })

  it('publishes the version the banner is about, and re-reads the page', async () => {
    const wrapper = await showSkill(() => json(200, SKILL))
    const { requests } = stubFetch(() =>
      json(200, { version: '2.0.0', state: 'published', live_at: '2026-10-06T12:00:00Z', changed: true }),
    )

    await wrapper.find('.pending button').trigger('click')
    await flush()

    expect(requests[0]?.path).toBe('/web/skills/demo-user/pdf-tools/publish')
    expect(requests[0]?.method).toBe('POST')
    // The body names the version by its address suffix — the name, or the digest when it has none.
    expect(requests[0]?.body).toEqual({ version: '2.0.0' })
    expect(requests[0]?.headers['X-XSRF-TOKEN']).toBe('token-one')
    expect(location.reload).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('throws the draft away instead, and does not publish it', async () => {
    const wrapper = await showSkill(() => json(200, SKILL))
    const { requests } = stubFetch(() =>
      json(200, { version: '2.0.0', state: 'discarded', live_at: null, changed: true }),
    )

    await wrapper.find('.pending button.danger').trigger('click')
    await flush()

    expect(requests[0]?.path).toBe('/web/skills/demo-user/pdf-tools/discard')
    expect(requests[0]?.body).toEqual({ version: '2.0.0' })
    wrapper.unmount()
  })

  it('stays put and says why when the server refuses the action', async () => {
    const wrapper = await showSkill(() => json(200, SKILL))
    stubFetch(() => json(400, errorBody('invalid_request', 'version 2.0.0 of pdf-tools was discarded')))

    await wrapper.find('.pending button').trigger('click')
    await flush()

    expect(wrapper.find('.banner').exists()).toBe(true)
    expect(location.reload).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('lists the version’s files, each at the address of the version on screen', async () => {
    const wrapper = await showSkill(() => json(200, SKILL))

    const link = wrapper.find('ul.files a')
    expect(link.text()).toBe('SKILL.md')
    expect(link.attributes('href')).toBe('/skills/demo-user/pdf-tools@2.0.0/files/SKILL.md')
    wrapper.unmount()
  })

  it('renders the SKILL.md as a document rather than as source', async () => {
    const wrapper = await showSkill(() => json(200, SKILL))

    expect(wrapper.find('.markdown-body h1').text()).toBe('PDF Tools')
    wrapper.unmount()
  })

  it('answers an address that names nothing without calling it a failure', async () => {
    const wrapper = await showSkill(() => json(404, errorBody('skill_not_found', 'No such skill.')))

    expect(wrapper.text()).toContain('没有这个 skill')
    expect(wrapper.find('.banner').exists()).toBe(false)
    wrapper.unmount()
  })

  it('sends somebody who is not signed in to the login form with the address to return to', async () => {
    location = stubLocation('/skills/demo-user/pdf-tools')

    const wrapper = await showSkill(NOT_SIGNED_IN, { body: NOT_SIGNED_IN })

    expect(location.assign).toHaveBeenCalledWith(
      '/login?return_to=%2Fskills%2Fdemo-user%2Fpdf-tools',
    )
    wrapper.unmount()
  })
})

describe('the comparison', () => {
  it('renders the hunks from the line prefixes, and keeps a context line’s leading space', async () => {
    const wrapper = await showDiff(() => json(200, SKILL), () => json(200, DIFF))

    const lines = wrapper.findAll('.hunk .line')
    // `element.textContent`, not `text()`: the latter trims, and the leading space on a context line
    // is exactly what this is checking — it is unified diff's prefix, and a view that dropped it
    // would render every context line as an addition.
    expect(lines.map((line) => line.element.textContent)).toEqual([' # PDF Tools', '-第一版', '+第二版'])
    expect(lines[1]?.classes()).toContain('is-removed')
    expect(lines[2]?.classes()).toContain('is-added')
    wrapper.unmount()
  })

  it('does not count a file that was never diffed', async () => {
    const wrapper = await showDiff(() => json(200, SKILL), () => json(200, DIFF))

    const binary = wrapper.findAll('.diff-file')[1]
    expect(binary?.text()).toContain('二进制文件')
    // The server sent null for both counts, and a view that printed 0 would be saying the file
    // changed by nothing — which is not what "we did not look" means.
    expect(binary?.text()).not.toContain('+0')
    expect(binary?.text()).not.toContain('-0')
    wrapper.unmount()
  })

  it('says so when the server cut the comparison short', async () => {
    const wrapper = await showDiff(() => json(200, SKILL), () =>
      json(200, { ...DIFF, truncated: true, files: [{ ...DIFF.files[1], hunks: null }] }),
    )

    expect(wrapper.find('.diff .banner').text()).toContain('只展开了其中一部分')
    wrapper.unmount()
  })

  it('offers the two decisions when the version being compared is the one waiting', async () => {
    const wrapper = await showDiff(() => json(200, SKILL), () => json(200, DIFF))

    const banner = wrapper.find('.pending')
    expect(banner.text()).toContain('等你上线：@2.0.0')
    // No link to the comparison: this page is the comparison.
    expect(banner.find('a.button-link').exists()).toBe(false)
    expect(banner.findAll('button').map((button) => button.text())).toEqual(['上线', '丢弃'])
    wrapper.unmount()
  })

  it('does not offer to publish the past', async () => {
    // A comparison between two versions of the past is not a decision. The banner belongs to the
    // version that would be published, which is a different page.
    const superseded = { ...LIVE_VERSION, name: '3.0.0', is_current: false }
    const wrapper = await showDiff(
      () => json(200, { ...SKILL, versions: [superseded, LIVE_VERSION] }),
      () => json(200, { from: '1.0.0', to: '3.0.0', truncated: false, files: [] }),
      { to: '3.0.0' },
    )

    expect(wrapper.find('.pending').exists()).toBe(false)
    wrapper.unmount()
  })

  it('compares against nothing, and says so, before anything has been published', async () => {
    const first = { ...LIVE_VERSION, state: 'draft' as const, state_at: null, is_current: false }
    const wrapper = await showDiff(
      () => json(200, { ...SKILL, version: first, versions: [first] }),
      () => json(200, { from: null, to: '1.0.0', truncated: false, files: [] }),
      { to: '1.0.0' },
    )

    expect(wrapper.text()).toContain('还没有上线过（下面的文件都算新增）')
    expect(wrapper.text()).toContain('这一版没有文件')
    wrapper.unmount()
  })

  it('says which version a bad address names, without calling it a failure', async () => {
    // The server's one 404 for "that address names nothing" covers a `?from=` or `?to=` that names no
    // version — and the skill itself resolves, so a banner would contradict the page right under it.
    const wrapper = await showDiff(
      () => json(200, SKILL),
      () => json(404, errorBody('skill_not_found', 'No such skill.')),
      { from: '9.9.9', to: '2.0.0' },
    )

    expect(wrapper.find('.banner').exists()).toBe(false)
    expect(wrapper.text()).toContain('地址里那个版本不存在')
    wrapper.unmount()
  })

  it('calls the baseline live when it is, and not when it is not', async () => {
    // The baseline is an address parameter, so a comparison can be linked to against a draft or an
    // old version — and "线上版本 @3.0.0" about something that is not live is a sentence that would be
    // false. `@3.0.0` is not in this fixture's version list, so it cannot be the live one.
    const live = await showDiff(() => json(200, SKILL), () => json(200, DIFF))
    expect(live.find('.diff').text()).toContain('线上版本 @1.0.0')
    live.unmount()

    const other = await showDiff(
      () => json(200, SKILL),
      () => json(200, { ...DIFF, from: '3.0.0' }),
      { from: '3.0.0', to: '2.0.0' },
    )
    expect(other.find('.diff').text()).toContain('对比基准 @3.0.0')
    expect(other.find('.diff').text()).not.toContain('线上版本 @3.0.0')
    other.unmount()
  })

  it('keeps the baseline when another version is picked, as a string in the query', async () => {
    // Dropping `from` would answer a different question: somebody comparing `@1 → @3` who picks `@2`
    // means `@1 → @2`, not `@2` against whatever happens to be live. Both query parameters carry the
    // version's address suffix, so a nameless version's digest can travel in them too.
    const wrapper = await showDiff(
      () => json(200, SKILL),
      () => json(200, { ...DIFF, from: '1.0.0', to: '3.0.0' }),
      { from: '1.0.0', to: '3.0.0' },
    )

    await wrapper.find('select').setValue('2.0.0')
    await flush()

    expect(location.assign).toHaveBeenCalledWith('/skills/demo-user/pdf-tools/diff?to=2.0.0&from=1.0.0')
    wrapper.unmount()
  })
})

describe('one file', () => {
  it('renders a Markdown file as a document', async () => {
    const { wrapper } = await showFile(
      () => json(200, SKILL),
      () => new Response('# 标题\n\n正文\n', { status: 200 }),
    )

    expect(wrapper.find('.markdown-body h1').text()).toBe('标题')
    wrapper.unmount()
  })

  it('shows any other file as its own bytes, not as a document', async () => {
    const skill = { ...SKILL, files: [{ relpath: 'schema.json', sha256: 'sha256:e', size: 20, is_binary: false }] }
    const { wrapper } = await showFile(
      () => json(200, skill),
      () => new Response('{"a":1}\n', { status: 200 }),
      'schema.json',
    )

    expect(wrapper.find('pre.markdown').text()).toContain('"a":1')
    expect(wrapper.find('.markdown-body').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not fetch a binary file at all', async () => {
    // The manifest already said what it is, and decoding arbitrary bytes as text to then print
    // replacement characters is not something to find out about after the fact.
    const skill = { ...SKILL, files: [{ relpath: 'icon.png', sha256: 'sha256:f', size: 40, is_binary: true }] }
    const { wrapper, requests } = await showFile(
      () => json(200, skill),
      () => new Response('', { status: 200 }),
      'icon.png',
    )

    expect(wrapper.text()).toContain('二进制文件')
    expect(requests).toHaveLength(1)
    wrapper.unmount()
  })

  it('says a version does not have the file that was asked for', async () => {
    const { wrapper } = await showFile(
      () => json(200, SKILL),
      () => new Response('', { status: 200 }),
      'nope.md',
    )

    expect(wrapper.text()).toContain('没有这个文件')
    expect(wrapper.text()).toContain('这个版本里没有 nope.md')
    // The version did load, so the way back is a real address rather than `@undefined`.
    expect(wrapper.find('a[href^="/skills/demo-user/pdf-tools@"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('does not report a missing skill as a missing file', async () => {
    // The two are different claims about different things, and one branch for both said this version
    // had no SKILL.md — about a version the page had never read — with a link to `/…/name@undefined`.
    const { wrapper } = await showFile(
      () => json(404, errorBody('skill_not_found', 'No such skill.')),
      () => json(404, errorBody('skill_not_found', 'No such skill.')),
    )

    expect(wrapper.text()).toContain('没有这个 skill')
    expect(wrapper.text()).not.toContain('这个版本里没有')
    expect(wrapper.find('a[href="/"]').exists()).toBe(true)
    expect(wrapper.find('a[href*="@undefined"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

/**
 * Sharing a skill with somebody (ADR 0034).
 *
 * The panel is gated on the skill being the caller's own, and that gate is asked of the session
 * rather than of the server — the address's first segment *is* the owner's namespace. So these mount
 * the page on a graph where the session has been read, which is what `App.vue` does before any page
 * renders.
 */
describe('sharing', () => {
  const GRANT = { handle: 'teammate', role: 'viewer', created_at: '2026-10-06T09:00:00Z' }

  /**
   * Mounts the content page as a signed-in account, with the session read answered first.
   *
   * `namespace` is the account's own; passing a different one is how the "not mine" case is set up.
   */
  async function showOwned(
    namespace: string,
    responses: Array<() => Response>,
  ): Promise<{ wrapper: VueWrapper; requests: RecordedRequest[] }> {
    const stub = stubFetch(
      () => json(200, { user_id: 'u1', username: 'demo-user', namespace } as never),
      ...responses,
    )
    vi.resetModules()
    const { useSession } = await import('../src/composables/useSession')
    const { default: SkillPage } = await import('../src/pages/SkillPage.vue')
    await useSession().load()
    const wrapper = mount(SkillPage, { props: { namespace: 'demo-user', name: 'pdf-tools' } })
    // Twice, and the second is not padding: the panel is inside `v-if="mine"`, which only becomes
    // true once the skill read has resolved — so its own `onMounted` read goes out a tick after the
    // page's three, and one flush would catch the page rendered and the panel still empty.
    await flush()
    await flush()
    return { wrapper, requests: stub.requests }
  }

  /**
   * The reads the page makes on its way in, in the order it actually makes them.
   *
   * The grants read sits **before** the comparison, which looks wrong and is not: the panel mounts
   * the instant `mine` flips — that is, the moment the skill read resolves — while the comparison is
   * asked for after that same `await` settles, so the panel's request leaves the browser first.
   * `stubFetch` answers strictly in order, so getting this wrong hands the comparison response to
   * the panel and the panel's to the comparison, which fails as a rendering error rather than as a
   * wrong address.
   */
  function pageReads(grants: (() => Response) | null = null): Array<() => Response> {
    const reads = [() => json(200, SKILL), () => new Response('# PDF Tools\n\n第二版\n', { status: 200 })]
    return grants === null ? [...reads, () => json(200, DIFF)] : [...reads, grants, () => json(200, DIFF)]
  }

  /**
   * A request on the grants route, found by its address rather than by its position in the list.
   *
   * `includes` and not `endsWith`: a withdrawal is a segment deeper — `/grants/<handle>` — and the
   * two are the same route.
   */
  function grantsRequest(requests: RecordedRequest[], method: string): RecordedRequest | undefined {
    return requests.find((r) => r.method === method && r.path.includes('/grants'))
  }

  it('lists who it is shared with, and says what each role lets them do', async () => {
    const { wrapper } = await showOwned('demo-user', [
      ...pageReads(() =>
        json(200, {
          grants: [GRANT, { ...GRANT, handle: 'helper', role: 'editor' }],
        }),
      ),
    ])

    const rows = wrapper.findAll('.grants li')
    expect(rows).toHaveLength(2)
    expect(rows[0]?.text()).toContain('teammate')
    expect(rows[0]?.text()).toContain('只读')
    // Not the wire's word: somebody reading the list is asking what the other person can do, and
    // `editor` alone does not say that they still cannot publish.
    expect(rows[1]?.text()).toContain('可以提新版本')
    expect(rows[1]?.text()).not.toContain('editor')
    wrapper.unmount()
  })

  it('reads the grants at the skill they belong to', async () => {
    const { requests } = await showOwned('demo-user', pageReads(() => json(200, { grants: [] })))

    expect(grantsRequest(requests, 'GET')?.path).toBe('/web/skills/demo-user/pdf-tools/grants')
  })

  it('draws nothing, and asks nothing, for a skill that is not the caller’s', async () => {
    // An editor grantee reaches this page — the author's listing holds the skills shared with them —
    // and sharing is not theirs to do. Asking anyway would be a 403 rendered as an error banner on a
    // page that is otherwise fine, which is worse than not drawing the panel.
    const { wrapper, requests } = await showOwned('somebody-else', pageReads())

    expect(wrapper.find('.sharing').exists()).toBe(false)
    expect(requests.map((r) => r.path)).not.toContain('/web/skills/demo-user/pdf-tools/grants')
    wrapper.unmount()
  })

  it('shares with the handle that was typed, at the role that was picked, and re-reads the list', async () => {
    const { wrapper, requests } = await showOwned('demo-user', [
      ...pageReads(() => json(200, { grants: [] })),
      () => json(201, GRANT),
      () => json(200, { grants: [GRANT] }),
    ])

    await wrapper.find('#sharing-handle').setValue('  teammate  ')
    await wrapper.find('select').setValue('viewer')
    await wrapper.find('.sharing button:not(.link)').trigger('click')
    await flush()

    const granted = grantsRequest(requests, 'POST')
    expect(granted?.path).toBe('/web/skills/demo-user/pdf-tools/grants')
    // Trimmed, because a value pasted from a chat window carries whitespace and a username with a
    // space in it is a username nobody has.
    expect(granted?.body).toEqual({ handle: 'teammate', role: 'viewer' })
    // The list is re-read rather than patched: the same call changes a role, so the answer is not
    // always a new row, and the server is the one that knows which happened.
    expect(requests.filter((r) => r.path.includes('/grants'))).toHaveLength(3)
    expect(wrapper.findAll('.grants li')).toHaveLength(1)
    // And the input is cleared, so the next share does not start from the last one.
    expect((wrapper.find('#sharing-handle').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })

  it('offers the editor role, and says what it still cannot do', async () => {
    const { wrapper } = await showOwned('demo-user', pageReads(() => json(200, { grants: [] })))

    const options = wrapper.findAll('.sharing select option').map((option) => option.text())
    expect(options).toEqual(['只读', '可以提新版本，不能上线'])
    wrapper.unmount()
  })

  it('takes a share away at the handle’s own address, and drops the row', async () => {
    const { wrapper, requests } = await showOwned('demo-user', [
      ...pageReads(() => json(200, { grants: [GRANT] })),
      () => new Response(null, { status: 204 }),
    ])

    await wrapper.find('.grants button').trigger('click')
    await flush()

    expect(grantsRequest(requests, 'DELETE')?.path)
      .toBe('/web/skills/demo-user/pdf-tools/grants/teammate')
    // Removed from the list rather than re-read: a 204 says nothing about what the list now is, and
    // the only row it could have removed is the one that was just withdrawn.
    expect(wrapper.findAll('.grants li')).toHaveLength(0)
    expect(wrapper.text()).toContain('还没有共享给任何人')
    wrapper.unmount()
  })

  it('says a username nobody has under the input, not in the banner', async () => {
    const { wrapper } = await showOwned('demo-user', [
      ...pageReads(() => json(200, { grants: [] })),
      () =>
        json(
          400,
          errorBody('invalid_request', 'no such handle', [
            { field: 'handle', issue: 'no_such_user' },
          ]),
        ),
    ])

    await wrapper.find('#sharing-handle').setValue('nobody')
    await wrapper.find('.sharing button:not(.link)').trigger('click')
    await flush()

    expect(wrapper.find('.field-error').text()).toContain('没有这个用户名')
    expect(wrapper.find('.banner').exists()).toBe(false)
    wrapper.unmount()
  })

  it('says so rather than leaving a blank space when nothing is shared', async () => {
    const { wrapper } = await showOwned('demo-user', pageReads(() => json(200, { grants: [] })))

    expect(wrapper.text()).toContain('还没有共享给任何人')
    wrapper.unmount()
  })
})
