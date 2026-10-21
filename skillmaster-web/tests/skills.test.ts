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
 * The author's pages, since v5: the two lists, one skill's four sections, and the writes.
 *
 * The protocol is what most of these assert — which address, which method, which body — because
 * everything else is a rendering of what the server said. The rest is about what is *not* drawn: a
 * section a rung cannot reach, an action a rung may not take, a 「等待处理」 that only the server can
 * say. All of those would look plausible and be wrong.
 */

const LIVE_VERSION = {
  name: '1.0.0',
  digest: 'sha256:aaa',
  draft_name: '第一版',
  draft_id: null,
  title: '第一版',
  note: '第一版说明',
  submitted_at: '2026-10-05T09:00:00Z',
  submitted_by: 'demo-user',
  state: 'published' as const,
  status: 'live' as const,
  state_at: '2026-10-05T10:00:00Z',
  first_published_at: '2026-10-05T10:00:00Z',
  is_current: true,
  file_count: 1,
  total_bytes: 90,
  revoked_reason: null,
}

const DRAFT_VERSION = {
  name: null,
  digest: 'sha256:bbb',
  draft_name: '第二版',
  draft_id: 'd1',
  title: '第二版',
  note: '第二版说明',
  submitted_at: '2026-10-06T09:00:00Z',
  submitted_by: 'demo-user',
  state: 'draft' as const,
  status: 'draft' as const,
  state_at: null,
  first_published_at: null,
  is_current: false,
  file_count: 1,
  total_bytes: 90,
  revoked_reason: null,
}

/** A version a collaborator submitted — 「維護」 may discard it, 「寫入」 may not. */
const OTHER_DRAFT = {
  ...DRAFT_VERSION,
  digest: 'sha256:ccc',
  draft_name: '第三版',
  draft_id: 'd2',
  submitted_by: 'yuhan',
}

const SKILL = {
  namespace: { slug: 'demo-user' },
  name: 'pdf-tools',
  title: 'PDF Tools',
  description: '把 PDF 拆开再拼起来',
  visibility: 'private',
  public_rung: null,
  offline_at: null,
  rung: 'owner',
  pending_rung: null,
  frontmatter: {
    name: 'pdf-tools',
    compatibility: '需要 ghostscript',
    metadata: { when_to_use: '需要拆分或合并 PDF 时' },
  },
  version: DRAFT_VERSION,
  versions: [DRAFT_VERSION, LIVE_VERSION],
  files: [{ relpath: 'SKILL.md', sha256: 'sha256:ddd', size: 90, is_binary: false }],
  resources: { body: '/web/skills/demo-user/pdf-tools@2.0.0/body' },
}

const DIFF = {
  from: '1.0.0',
  to: 'd1',
  truncated: false,
  next: null,
  files: [
    {
      relpath: 'SKILL.md',
      status: 'modified',
      binary: false,
      size: 90,
      added: 1,
      removed: 1,
      hunks: [{ header: '@@ -1,2 +1,2 @@', lines: [' # PDF Tools', '-第一版', '+第二版'] }],
    },
  ],
}

const NOT_SIGNED_IN = () => json(401, errorBody('unauthenticated', 'Authentication is required.'))

let location = stubLocation('/')

beforeEach(() => {
  location = stubLocation('/')
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/* --------------------------------------------------------------------- the list */

async function showSkills(listResponse: () => Response, url = '/'): Promise<VueWrapper> {
  const [path, query] = url.split('?')
  location = stubLocation(path ?? '/', query === undefined ? '' : `?${query}`)
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
    current: { name: '1.0.0', digest: 'sha256:aaa' },
    drafts: 0,
    draft: null,
    updated_at: '2026-10-06T09:00:00Z',
    deleted_at: null,
    ...overrides,
  }
}

describe('the skills list', () => {
  it('draws a row as a name, its live state, and when it last changed', async () => {
    const wrapper = await showSkills(() => json(200, { skills: [row()] }))

    expect(wrapper.find('a.skill-name').text()).toBe('pdf-tools')
    expect(wrapper.find('a.skill-name').attributes('href')).toBe('/skills/demo-user/pdf-tools')
    expect(wrapper.find('.chip').text()).toBe('已上线 @1.0.0')
    expect(wrapper.find('.skill-updated').text()).toContain('更新于')
    // No 调用次数: the demo draws one because its fixtures carry call counts, and the product has
    // no such fact.
    expect(wrapper.find('.skill-calls').exists()).toBe(false)
    wrapper.unmount()
  })

  it('says 未上线 for a skill nothing has been published from', async () => {
    const wrapper = await showSkills(() => json(200, { skills: [row({ current: null })] }))

    expect(wrapper.find('.chip').text()).toBe('未上线')
    expect(wrapper.find('.chip').classes()).toContain('is-plain')
    // The list no longer counts drafts: a draft is a state of its own row in 版本, not of the skill.
    expect(wrapper.text()).not.toContain('待上线')
    wrapper.unmount()
  })

  it('searches the name and the title, and counts what is on screen', async () => {
    const wrapper = await showSkills(
      () =>
        json(200, {
          skills: [
            row({ name: 'pdf-tools', title: 'PDF 工具' }),
            row({ name: 'other', title: '别的东西' }),
          ],
        }),
      '/?q=PDF',
    )

    expect(wrapper.findAll('a.skill-name')).toHaveLength(1)
    expect(wrapper.find('.page-head .count').text()).toBe('1 个')
    wrapper.unmount()
  })

  it('sorts by name when asked, through the address', async () => {
    const wrapper = await showSkills(
      () =>
        json(200, {
          skills: [row({ name: 'zeta' }), row({ name: 'alpha' })],
        }),
      '/?sort=name',
    )

    expect(wrapper.findAll('a.skill-name').map((link) => link.text())).toEqual(['alpha', 'zeta'])
    wrapper.unmount()
  })

  it('pages at ten a row, and only draws a pager when there is more than one page', async () => {
    const many = Array.from({ length: 12 }, (_, index) => row({ name: `skill-${index}` }))
    const wrapper = await showSkills(() => json(200, { skills: many }), '/')

    expect(wrapper.findAll('li.skill')).toHaveLength(10)
    expect(wrapper.find('.pager').text()).toContain('共 12 个')

    await wrapper.find('.pager-page').trigger('click')

    expect(wrapper.findAll('li.skill')).toHaveLength(2)
    wrapper.unmount()
  })

  it('says nothing matched rather than showing an empty page', async () => {
    const wrapper = await showSkills(() => json(200, { skills: [row()] }), '/?q=zzz')

    expect(wrapper.text()).toContain('没有匹配的 skill。换个词试试，或者清空搜索。')
    expect(wrapper.text()).not.toContain('还没有提交过 skill')
    wrapper.unmount()
  })

  it('says how the first skill gets here when there are none', async () => {
    const wrapper = await showSkills(() => json(200, { skills: [] }))

    expect(wrapper.text()).toContain('还没有提交过 skill')
    expect(wrapper.text()).toContain('skillmaster skill submit')
    wrapper.unmount()
  })

  it('reads the deleted list on its own request, and restores from it', async () => {
    const dead = row({ deleted_at: '2026-10-07T09:00:00Z', updated_at: null })
    const stub = stubFetch(
      () => json(200, { skills: [dead] }),
      () => new Response(null, { status: 204 }),
    )
    stubLocation('/', '?show=deleted')
    vi.resetModules()
    const { default: SkillsPage } = await import('../src/pages/SkillsPage.vue')
    const wrapper = mount(SkillsPage)
    await flush()

    expect(stub.requests[0]?.path).toBe('/web/skills?state=deleted')
    // The name is text, not a link: its address opens nothing now.
    expect(wrapper.find('a.skill-name').exists()).toBe(false)
    expect(wrapper.find('.chip').text()).toBe('已删除')
    expect(wrapper.find('.skill-updated').text()).toContain('删除于')

    await wrapper.find('.restore').trigger('click')
    await flush()

    expect(stub.requests[1]?.path).toBe('/web/skills/demo-user/pdf-tools/restore')
    expect(stub.requests[1]?.method).toBe('POST')
    wrapper.unmount()
  })

  it('says the deleted list is empty rather than showing the live one', async () => {
    const wrapper = await showSkills(() => json(200, { skills: [] }), '/?show=deleted')

    expect(wrapper.text()).toContain('没有删掉的 skill。')
    wrapper.unmount()
  })

  it('sends somebody who is not signed in to the login form, and says where to come back to', async () => {
    const wrapper = await showSkills(NOT_SIGNED_IN)

    expect(location.assign).toHaveBeenCalledWith('/login?return_to=%2F')
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

/* --------------------------------------------------------------------- one skill */

/**
 * Mounts the skill page with the reads it makes on the way in already answered: the detail, then the
 * file when the address names one, then the comparison when it names one.
 */
async function showSkill(
  skill: Record<string, unknown>,
  options: {
    query?: string
    relpath?: string
    file?: () => Response
    diff?: () => Response
    version?: string
  } = {},
): Promise<{ wrapper: VueWrapper; requests: RecordedRequest[] }> {
  stubLocation('/skills/demo-user/pdf-tools', options.query ?? '')
  const responders: Array<() => Response> = [() => json(200, skill)]
  if (options.relpath !== undefined) {
    responders.push(options.file ?? (() => new Response('# 标题\n\n正文\n', { status: 200 })))
  }
  if (options.diff !== undefined) {
    responders.push(options.diff)
  }
  if (skill.rung === 'owner' || skill.rung === 'admin') {
    // The settings nav's count: "somebody is waiting" has two homes, and this is one.
    responders.push(() => json(200, { requests: [], total: 0 }))
  }
  const stub = stubFetch(...responders)
  vi.resetModules()
  const { default: SkillPage } = await import('../src/pages/SkillPage.vue')
  const wrapper = mount(SkillPage, {
    props: {
      namespace: 'demo-user',
      name: 'pdf-tools',
      ...(options.version === undefined ? {} : { version: options.version }),
      ...(options.relpath === undefined ? {} : { relpath: options.relpath }),
    },
  })
  await flush()
  return { wrapper, requests: stub.requests }
}

describe('one skill: the sections a rung can reach', () => {
  it('gives the owner all four sections', async () => {
    const { wrapper } = await showSkill(SKILL)

    expect(wrapper.findAll('.skill-tab .skill-tab-label').map((tab) => tab.text())).toEqual([
      '信息',
      '文件',
      '版本',
      '设置',
    ])
    wrapper.unmount()
  })

  it('draws no 设置 for 浏览, and says the skill is private without linking to what they cannot set', async () => {
    const { wrapper } = await showSkill({ ...SKILL, rung: 'browse' })

    expect(wrapper.findAll('.skill-tab .skill-tab-label').map((tab) => tab.text())).toEqual(['信息', '文件', '版本'])
    // The badge leads to the page that owns the fact, and that page is 管理's — so below it the
    // whole mark is absent, not greyed (design: 够不到的东西不画).
    expect(wrapper.find('.skill-name-row .chip').exists()).toBe(false)
    wrapper.unmount()
  })

  it('gives 调用 信息 and 版本 only — no file list, no drafts', async () => {
    const { wrapper } = await showSkill({ ...SKILL, rung: 'invoke', files: [], resources: null })

    expect(wrapper.findAll('.skill-tab .skill-tab-label').map((tab) => tab.text())).toEqual(['信息', '版本'])
    wrapper.unmount()
  })
})

describe('one skill: 信息', () => {
  it('reads the description, the when-to-use sentence and the environment', async () => {
    const { wrapper } = await showSkill(SKILL)

    const blocks = wrapper.findAll('.info-block')
    expect(blocks[0]?.text()).toContain('描述')
    expect(blocks[0]?.text()).toContain('把 PDF 拆开再拼起来')
    expect(blocks[1]?.text()).toContain('适用场景')
    expect(blocks[1]?.text()).toContain('需要拆分或合并 PDF 时')
    expect(blocks[2]?.text()).toContain('运行环境')
    expect(blocks[2]?.text()).toContain('需要 ghostscript')
    expect(wrapper.find('.info-rail-value').text()).toBe('@1.0.0')
    wrapper.unmount()
  })

  it('leaves out a block the author did not write', async () => {
    const { wrapper } = await showSkill({ ...SKILL, frontmatter: { name: 'pdf-tools' } })

    expect(wrapper.findAll('.info-block')).toHaveLength(1)
    wrapper.unmount()
  })

  it('offers 申请 to 浏览, and posts the rung that was asked for', async () => {
    const { wrapper } = await showSkill({ ...SKILL, rung: 'browse' })
    const post = stubFetch(() => json(201, {}))

    expect(wrapper.find('.ask').exists()).toBe(true)
    expect(wrapper.find('.ask .hint').text()).toContain('当前档位为「浏览」')
    expect(wrapper.find('.ask button.button').text()).toBe('申请成为协作者')
    // The note under the picker says what the chosen rung adds and what it lacks.
    expect(wrapper.find('.ask-note').text()).toContain('可以提交新版本')

    await wrapper.find('.ask button.button').trigger('click')
    await flush()

    expect(post.requests[0]?.path).toBe('/web/skills/demo-user/pdf-tools/access-requests')
    expect(post.requests[0]?.method).toBe('POST')
    expect(post.requests[0]?.body).toEqual({ rung: 'write' })
    wrapper.unmount()
  })

  it('draws no 申请 for the owner or for 管理 — there is no rung above them', async () => {
    const owner = await showSkill(SKILL)
    expect(owner.wrapper.find('.ask').exists()).toBe(false)
    owner.wrapper.unmount()

    const admin = await showSkill({ ...SKILL, rung: 'admin' })
    expect(admin.wrapper.find('.ask').exists()).toBe(false)
    admin.wrapper.unmount()
  })

  it('shows 等待处理 from the server, and withdraws it', async () => {
    // The server carries the caller's own pending request; a page that remembered its own click
    // would show the form again to somebody already waiting.
    const { wrapper } = await showSkill({ ...SKILL, rung: 'browse', pending_rung: 'write' })
    const post = stubFetch(() => new Response(null, { status: 204 }))

    expect(wrapper.find('.ask-form').exists()).toBe(false)
    expect(wrapper.find('.ask-pending').text()).toContain('等待处理')
    expect(wrapper.find('.ask-pending').text()).toContain('已提交「写入」的申请')

    await wrapper.find('.ask-pending .link-button').trigger('click')
    await flush()

    expect(post.requests[0]?.path).toBe('/web/skills/demo-user/pdf-tools/access-requests/me')
    expect(post.requests[0]?.method).toBe('DELETE')
    wrapper.unmount()
  })
})

describe('one skill: 文件', () => {
  it('offers the readable versions in one menu, counts the versions, and links each file', async () => {
    const { wrapper } = await showSkill(SKILL, { query: '?tab=files' })

    // The picker is the page's own menu now: name, version number and status tag, not `<optgroup>`.
    expect(wrapper.find('.version-picker .menu-trigger-text').text()).toBe('第一版')
    expect(wrapper.findAll('.version-picker .menu-label').map((node) => node.text())).toEqual([
      '第一版',
      '第二版',
    ])
    expect(wrapper.findAll('.version-picker .menu-number').map((node) => node.text())).toEqual([
      '@1.0.0',
    ])
    expect(wrapper.findAll('.version-picker .menu-tag').map((node) => node.text())).toEqual([
      '线上',
      '草稿',
    ])
    // The count is non-draft, non-discarded: the draft is not a version the skill *has*.
    expect(wrapper.find('.skill-version-count').text()).toBe('1 个版本')
    expect(wrapper.find('.skill-address').text()).toContain('demo-user/pdf-tools')
    // With no pin in the path, the file tab falls back to the live version.
    expect(wrapper.find('ul.files a').attributes('href')).toBe(
      '/skills/demo-user/pdf-tools@1.0.0/files/SKILL.md',
    )
    wrapper.unmount()
  })

  it('lets an explicit tab win over a directory in the address', async () => {
    // A directory is the address's default destination, not an override of a section it also
    // names — the design settles the same order (a valid tab first, then the named files).
    const { wrapper } = await showSkill(SKILL, { query: '?tab=versions&path=references' })

    expect(wrapper.find('[data-tab="versions"]').exists()).toBe(true)
    expect(wrapper.find('[data-tab="files"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not carry a directory to another section', async () => {
    // The tab link keeps the address' facts, but `path` belongs to 文件: leaving it in a 版本 link
    // made the page reload into 文件 again, and no tab could be reached from a directory.
    const { wrapper } = await showSkill(SKILL, { query: '?tab=files&path=references' })

    const versions = wrapper.findAll('.skill-tab').find((tab) => tab.find('.skill-tab-label').text() === '版本')
    expect(versions?.attributes('href')).toBe('/skills/demo-user/pdf-tools?tab=versions')
    wrapper.unmount()
  })

  it('lists one level at a time, with folders above files', async () => {
    const nested = {
      ...SKILL,
      files: [
        { relpath: 'SKILL.md', sha256: 'sha256:a', size: 90, is_binary: false },
        { relpath: 'references/split.md', sha256: 'sha256:b', size: 10, is_binary: false },
        { relpath: 'scripts/compress.sh', sha256: 'sha256:c', size: 20, is_binary: false },
      ],
    }
    const { wrapper } = await showSkill(nested, { query: '?tab=files' })

    const names = wrapper.findAll('.files .file-name').map((link) => link.text())
    expect(names).toEqual(['references/', 'scripts/', 'SKILL.md'])
    // A folder goes into its own listing, not to a file address.
    expect(wrapper.findAll('.files .file-name')[0]?.attributes('href')).toContain('path=references')
    // The root opens SKILL.md by default.
    expect(wrapper.find('.file-tab').text()).toBe('SKILL.md')
    wrapper.unmount()
  })

  it('opens a file under the listing, rendered as a document', async () => {
    const { wrapper } = await showSkill(SKILL, {
      query: '?tab=files',
      relpath: 'SKILL.md',
      file: () => new Response('# 标题\n\n正文\n', { status: 200 }),
    })

    expect(wrapper.find('.file-tab').text()).toBe('SKILL.md')
    expect(wrapper.find('.doc h1').text()).toBe('标题')
    wrapper.unmount()
  })
})

describe('one skill: 版本', () => {
  // **Only the files tab addresses a version in its path.** 信息 / 版本 / 设置 are skill-level
  // screens, and an `@1.2.3` on them named a version the screen did not show (TD §前端架构: every
  // face writes one kind of pin; the versions tab's pin is the query).
  it('keeps the path pin on the files tab and drops it on the others', async () => {
    const { wrapper } = await showSkill(SKILL, { query: '?tab=files', version: '1.2.3' })

    const href = (label: string) =>
      wrapper.findAll('.skill-tab').find((tab) => tab.text().includes(label))?.attributes('href')
    expect(href('文件')).toBe('/skills/demo-user/pdf-tools@1.2.3?tab=files')
    expect(href('版本')).toBe('/skills/demo-user/pdf-tools?tab=versions')
    expect(href('设置')).toBe('/skills/demo-user/pdf-tools?tab=settings')
    wrapper.unmount()
  })

  it('draws two-segment rows, the derived statuses, and the non-draft count', async () => {
    const { wrapper } = await showSkill(SKILL, { query: '?tab=versions' })

    // The top tab counts what the skill *has*: non-draft, non-discarded.
    expect(wrapper.find('.skill-tab-count').text()).toBe('1')
    // The filter chips count what this rung can see, draft included; the draft is not a 版本.
    expect(wrapper.findAll('.version-filter').map((chip) => chip.text())).toEqual([
      '全部 2',
      '线上 1',
      '草稿 1',
    ])

    const rows = wrapper.findAll('.versions li')
    expect(rows).toHaveLength(2)
    expect(rows[0]?.find('.version-name').text()).toBe('第二版')
    expect(rows[0]?.find('.version-number').text()).toBe('—')
    expect(rows[0]?.find('.chip-text').text()).toBe('草稿')
    expect(rows[0]?.find('.version-note').text()).toBe('第二版说明')
    expect(rows[0]?.find('.version-meta').text()).toContain('demo-user')
    expect(rows[1]?.find('.version-name').text()).toBe('第一版')
    expect(rows[1]?.find('.version-number').text()).toBe('@1.0.0')
    expect(rows[1]?.find('.chip-text').text()).toBe('线上')
    // The list only navigates. The draft's menu points at its own page for 上线 / 丢弃.
    expect(rows[0]?.findAll('.version-menu .menu-item').map((item) => item.text())).toEqual([
      '查看文件',
      '编辑',
      '查看改动',
      '上线',
      '丢弃',
    ])
    wrapper.unmount()
  })

  it('publishes a draft on its own page, and discards it after a confirmation', async () => {
    const { wrapper } = await showSkill(SKILL, {
      query: '?tab=versions&version=d1',
      diff: () => json(200, DIFF),
    })
    const post = stubFetch(
      () =>
        json(200, {
          version: 'd1',
          state: 'published',
          live_at: '2026-10-20T10:00:00Z',
          changed: true,
          release_name: '1.0.1',
          draft_name: '第二版',
        }),
      () =>
        json(200, {
          version: 'd1',
          state: 'discarded',
          live_at: null,
          changed: true,
        }),
    )

    // The publish form suggests the next patch from the live version (1.0.0 → 1.0.1).
    const publish = wrapper.findAll('.vp-action .button').find((button) => button.text().startsWith('上线 @'))
    expect(publish?.text()).toBe('上线 @1.0.1')
    await publish!.trigger('click')

    // Every state-changing action opens the one confirmation sheet.
    expect(wrapper.find('.confirm').text()).toContain('名称与说明在这一刻锁定')
    await wrapper.find('.confirm-acts .button:not(.is-quiet)').trigger('click')
    await flush()

    expect(post.requests[0]?.path).toBe('/web/skills/demo-user/pdf-tools/publish')
    expect(post.requests[0]?.body).toEqual({ version: 'd1', release_name: '1.0.1' })

    // Discard is in the ordinary action flow, outlined, and its sheet says it cannot be recovered.
    const discard = wrapper.findAll('.vp-action .button').find((button) => button.text() === '丢弃')
    await discard!.trigger('click')
    expect(wrapper.find('.confirm-body').text()).toContain('丢弃后无法找回')
    await wrapper.find('.confirm-acts .button:not(.is-quiet)').trigger('click')
    await flush()

    expect(post.requests[1]?.path).toBe('/web/skills/demo-user/pdf-tools/discard')
    expect(post.requests[1]?.body).toEqual({ version: 'd1' })
    wrapper.unmount()
  })

  it('shows a discarded version as 已丢弃 — not counted, no menu', async () => {
    const discarded = {
      ...DRAFT_VERSION,
      name: null,
      draft_id: 'd0',
      draft_name: '旧草稿',
      digest: 'sha256:ddd',
      status: 'discarded' as const,
      state: 'discarded' as const,
      state_at: '2026-10-06T11:00:00Z',
    }
    const { wrapper } = await showSkill(
      { ...SKILL, versions: [discarded, DRAFT_VERSION, LIVE_VERSION] },
      { query: '?tab=versions' },
    )

    // Only the live version counts: a discarded one was never a version the skill has.
    expect(wrapper.find('.skill-tab-count').text()).toBe('1')
    const chips = wrapper.findAll('.versions .chip-text').map((chip) => chip.text())
    expect(chips).toEqual(['已丢弃', '草稿', '线上'])
    // 已丢弃 is terminal: the row has no 更多操作 menu.
    expect(wrapper.findAll('.versions li')[0]?.find('.version-menu-trigger').exists()).toBe(false)
    wrapper.unmount()
  })

  it('draws no lifecycle action for 浏览', async () => {
    const { wrapper } = await showSkill({ ...SKILL, rung: 'browse' }, { query: '?tab=versions' })

    const items = wrapper.findAll('.version-menu .menu-item').map((item) => item.text())
    expect(items).toEqual(['查看文件'])
    expect(items).not.toContain('上线')
    expect(items).not.toContain('丢弃')
    // No 新建版本 for a rung that cannot submit.
    expect(wrapper.find('.version-create').exists()).toBe(false)
    wrapper.unmount()
  })

  it('lets 维护 discard somebody else’s draft, and 写入 not', async () => {
    const versions = [OTHER_DRAFT, LIVE_VERSION]
    const maintain = await showSkill({ ...SKILL, rung: 'maintain', versions }, { query: '?tab=versions' })
    expect(
      maintain.wrapper.findAll('.version-menu .menu-item').map((button) => button.text()),
    ).toContain('丢弃')
    maintain.wrapper.unmount()

    const write = await showSkill({ ...SKILL, rung: 'write', versions }, { query: '?tab=versions' })
    expect(
      write.wrapper.findAll('.version-menu .menu-item').map((button) => button.text()),
    ).not.toContain('丢弃')
    // 写入 may submit, so the entry is drawn — and it is a link to the editor, which executes
    // nothing by itself (v8 E1: opening the page writes no version).
    const create = write.wrapper.find('.version-create')
    expect(create.exists()).toBe(true)
    expect(create.attributes('href')).toBe('/skills/demo-user/pdf-tools/new')
    write.wrapper.unmount()
  })

  it('opens the legacy /diff alias on the version page’s 改动 pane', async () => {
    stubLocation('/skills/demo-user/pdf-tools', '')
    const stub = stubFetch(
      () => json(200, SKILL),
      () => json(200, DIFF),
      () => json(200, { requests: [], total: 0 }),
    )
    vi.resetModules()
    const { default: SkillPage } = await import('../src/pages/SkillPage.vue')
    const wrapper = mount(SkillPage, {
      props: { namespace: 'demo-user', name: 'pdf-tools', from: '1.0.0', to: 'd1' },
    })
    await flush()

    expect(stub.requests[1]?.path).toBe(
      '/web/skills/demo-user/pdf-tools@d1/diff?from=1.0.0',
    )
    expect(wrapper.find('.vp-tab.is-current').text()).toContain('改动')
    expect(wrapper.find('.vp-file').exists()).toBe(true)
    // Coloured by the line's own prefix, which is unified diff's.
    expect(wrapper.find('.is-add .diff-text').text()).toBe('+第二版')
    // 版本 is the way back to the list.
    expect(wrapper.find('.crumbs a').attributes('href')).toBe(
      '/skills/demo-user/pdf-tools?tab=versions',
    )
    wrapper.unmount()
  })
})

/* --------------------------------------------------------------------- 设置 */

async function showSettings(
  skill: Record<string, unknown>,
  sub: string,
  responders: Array<() => Response>,
): Promise<{ wrapper: VueWrapper; requests: RecordedRequest[] }> {
  stubLocation('/skills/demo-user/pdf-tools', `?tab=settings&sub=${sub}`)
  const stub = stubFetch(() => json(200, skill), ...responders)
  vi.resetModules()
  const { default: SkillPage } = await import('../src/pages/SkillPage.vue')
  const wrapper = mount(SkillPage, { props: { namespace: 'demo-user', name: 'pdf-tools' } })
  await flush()
  return { wrapper, requests: stub.requests }
}

describe('one skill: 设置 › 常规', () => {
  it('reads the three-way visibility back from the server', async () => {
    const { wrapper } = await showSettings(SKILL, 'general', [
      () => json(200, { requests: [], total: 0 }),
    ])

    const checked = wrapper.findAll('input[name="skill-visibility"]').find((input) => (input.element as HTMLInputElement).checked)
    expect((checked?.element as HTMLInputElement).value).toBe('private')
    wrapper.unmount()
  })

  it('patches both facts of a public choice together', async () => {
    const { wrapper, requests } = await showSettings(SKILL, 'general', [
      () => json(200, { requests: [], total: 0 }),
      () => new Response(null, { status: 204 }),
    ])

    await wrapper.findAll('input[name="skill-visibility"]')[1]!.setValue(true)
    await flush()

    const patch = requests.at(-1)
    expect(patch?.path).toBe('/web/skills/demo-user/pdf-tools/visibility')
    expect(patch?.method).toBe('PATCH')
    expect(patch?.body).toEqual({ visibility: 'public', public_rung: 'invoke' })
    wrapper.unmount()
  })

  it('asks before 公开 · 浏览, and the selector does not move before the answer', async () => {
    const { wrapper, requests } = await showSettings(SKILL, 'general', [
      () => json(200, { requests: [], total: 0 }),
      () => new Response(null, { status: 204 }),
    ])

    await wrapper.findAll('input[name="skill-visibility"]')[2]!.setValue(true)
    await flush()

    expect(wrapper.find('.confirm').text()).toContain('已经发生的下载与复制无法收回')
    // Nothing written until the answer, and the selector is back on the state that is true.
    expect(requests).toHaveLength(2)
    const radios = wrapper.findAll('input[name="skill-visibility"]')
    expect((radios[0]!.element as HTMLInputElement).checked).toBe(true)
    expect((radios[2]!.element as HTMLInputElement).checked).toBe(false)

    await wrapper.find('.confirm-acts .button:not(.is-quiet)').trigger('click')
    await flush()

    expect(requests.at(-1)?.body).toEqual({ visibility: 'public', public_rung: 'browse' })
    wrapper.unmount()
  })

  it('asks for the consequence before deleting, then leaves for the deleted list', async () => {
    location = stubLocation('/skills/demo-user/pdf-tools', '?tab=settings&sub=general')
    const stub = stubFetch(
      () => json(200, SKILL),
      () => json(200, { requests: [], total: 0 }),
      () => new Response(null, { status: 204 }),
    )
    vi.resetModules()
    const { default: SkillPage } = await import('../src/pages/SkillPage.vue')
    const wrapper = mount(SkillPage, { props: { namespace: 'demo-user', name: 'pdf-tools' } })
    await flush()

    const remove = wrapper.findAll('.danger-acts .button').find((button) => button.text() === '删除')
    await remove!.trigger('click')
    expect(wrapper.find('.confirm-body').text()).toContain('该名称将无法用于创建新 skill')

    await wrapper.find('.confirm-acts .button.is-danger').trigger('click')
    await flush()

    expect(stub.requests[2]?.path).toBe('/web/skills/demo-user/pdf-tools/delete')
    expect(stub.requests[2]?.method).toBe('POST')
    expect(location.assign).toHaveBeenCalledWith('/?show=deleted')
    wrapper.unmount()
  })

  it('switches the whole skill off and on from the danger zone, through the sheet', async () => {
    location = stubLocation('/skills/demo-user/pdf-tools', '?tab=settings&sub=general')
    const stub = stubFetch(
      () => json(200, SKILL),
      () => json(200, { requests: [], total: 0 }),
      () => new Response(null, { status: 204 }),
    )
    vi.resetModules()
    const { default: SkillPage } = await import('../src/pages/SkillPage.vue')
    const wrapper = mount(SkillPage, { props: { namespace: 'demo-user', name: 'pdf-tools' } })
    await flush()

    const offlineButton = wrapper
      .findAll('.danger-acts .button')
      .find((button) => button.text() === '下线')
    await offlineButton!.trigger('click')
    expect(wrapper.find('.confirm-title').text()).toBe('下线这个 skill')
    await wrapper.find('.confirm-acts .button:not(.is-quiet)').trigger('click')
    await flush()

    expect(stub.requests[2]?.path).toBe('/web/skills/demo-user/pdf-tools/offline')
    expect(stub.requests[2]?.method).toBe('POST')
    wrapper.unmount()
  })

  it('draws no danger zone for 管理 — deletion is the owner’s', async () => {
    const { wrapper } = await showSettings({ ...SKILL, rung: 'admin' }, 'general', [
      () => json(200, { requests: [], total: 0 }),
    ])

    expect(wrapper.find('.settings-block.is-danger').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('one skill: 设置 › 协作者', () => {
  const GRANTS = {
    grants: [{ handle: 'yuhan', role: 'browse', created_at: '2026-10-01T09:00:00Z' }],
    total: 1,
    page: 1,
    page_size: 10,
  }
  const REQUESTS = { requests: [{ handle: 'kai', wanted_role: 'write', created_at: '2026-10-02T09:00:00Z' }], total: 1 }

  it('draws the owner first, then the grants, and names each rung', async () => {
    const { wrapper } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
    ])

    const rows = wrapper.findAll('.grants li')
    expect(rows[0]?.text()).toContain('demo-user')
    expect(rows[0]?.text()).toContain('所有者')
    expect(rows[1]?.text()).toContain('yuhan')
    expect(rows[1]?.find('.menu-trigger').text()).toBe('浏览')
    expect(wrapper.find('.settings-nav .count').text()).toBe('1')
    wrapper.unmount()
  })

  it('shares with the typed handle at the offered rung, then re-reads the list', async () => {
    const { wrapper, requests } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
      () => json(201, { handle: 'newbie', role: 'invoke' }),
      () => json(200, { ...GRANTS, total: 2 }),
    ])

    await wrapper.find('.share-row input').setValue('newbie')
    await wrapper.find('.share-row .button').trigger('click')
    await flush()

    const post = requests.find((request) => request.method === 'POST')
    expect(post?.path).toBe('/web/skills/demo-user/pdf-tools/grants')
    expect(post?.body).toEqual({ handle: 'newbie', role: 'invoke' })
    wrapper.unmount()
  })

  it('takes a share away at the handle’s own address', async () => {
    const { wrapper, requests } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
      () => new Response(null, { status: 204 }),
      () => json(200, { grants: [], total: 0, page: 1, page_size: 10 }),
    ])

    await wrapper.find('.grants .withdraw').trigger('click')
    await flush()

    const revoke = requests.find((request) => request.method === 'DELETE')
    expect(revoke?.path).toBe('/web/skills/demo-user/pdf-tools/grants/yuhan')
    wrapper.unmount()
  })

  it('approves a request at the rung it was asked for, and denies another', async () => {
    const { wrapper, requests } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
      () => new Response(null, { status: 204 }),
      () => json(200, { requests: [], total: 0 }),
      () => json(200, { ...GRANTS, total: 2 }),
      () => new Response(null, { status: 204 }),
      () => json(200, { requests: [], total: 0 }),
    ])

    expect(wrapper.find('.grants.is-requests').text()).toContain('申请「写入」')
    await wrapper.find('.grants.is-requests .row-acts .button').trigger('click')
    await flush()

    const approve = requests.find((request) => request.path.endsWith('/approve'))
    expect(approve?.path).toBe('/web/skills/demo-user/pdf-tools/access-requests/kai/approve')
    expect(approve?.body).toEqual({ rung: 'write' })
    wrapper.unmount()
  })

  it('keeps the population sentence true when the page is opened on a search', async () => {
    // The filtered response's total cannot answer "does anybody else have access" — so a page
    // opened at `?q=zzz` reads the unfiltered count separately.
    location = stubLocation(
      '/skills/demo-user/pdf-tools',
      '?tab=settings&sub=collaborators&q=zzz',
    )
    stubFetch(
      () => json(200, SKILL),
      () => json(200, { grants: [], total: 0, page: 1, page_size: 10 }),
      () => json(200, { requests: [], total: 0 }),
      () => json(200, { ...GRANTS, total: 3 }),
    )
    vi.resetModules()
    const { default: SkillPage } = await import('../src/pages/SkillPage.vue')
    const wrapper = mount(SkillPage, { props: { namespace: 'demo-user', name: 'pdf-tools' } })
    await flush()

    expect(wrapper.findAll('.settings-block')[1]?.find('.hint').text()).toContain(
      '只有你和下列协作者可以访问',
    )
    expect(wrapper.text()).toContain('没有匹配的协作者')
    wrapper.unmount()
  })

  it('numbers the rows from the filtered match, not from the owner, on later pages', async () => {
    // The owner is a row only when the search matches him. A query that misses him must not reserve
    // his slot on later pages either — that made the range start one late and end past the total.
    const grants = Array.from({ length: 13 }, (_, index) => ({
      handle: `zuser${index + 1}`,
      role: 'invoke',
      created_at: '2026-10-01T09:00:00Z',
    }))
    location = stubLocation(
      '/skills/demo-user/pdf-tools',
      '?tab=settings&sub=collaborators&q=z&p=2',
    )
    stubFetch(
      () => json(200, SKILL),
      () => json(200, { grants: grants.slice(10), total: 13, page: 2, page_size: 10 }),
      () => json(200, { requests: [], total: 0 }),
      () => json(200, { grants: [], total: 13, page: 1, page_size: 10 }),
    )
    vi.resetModules()
    const { default: SkillPage } = await import('../src/pages/SkillPage.vue')
    const wrapper = mount(SkillPage, { props: { namespace: 'demo-user', name: 'pdf-tools' } })
    await flush()

    expect(wrapper.find('.pager-count').text()).toBe('共 13 人，第 11–13 人')
    wrapper.unmount()
  })

  it('reads a clamped page once, at the last page', async () => {
    // `?p=99` is clamped to the last page. The watcher on `page` owns that read; doing it in the
    // load as well sent the same page twice.
    location = stubLocation('/skills/demo-user/pdf-tools', '?tab=settings&sub=collaborators&p=99')
    stubFetch(
      () => json(200, SKILL),
      () => json(200, { grants: [], total: 5, page: 99, page_size: 10 }),
      () => json(200, { requests: [], total: 0 }),
      () => json(200, { ...GRANTS, total: 5 }),
    )
    vi.resetModules()
    const { default: SkillPage } = await import('../src/pages/SkillPage.vue')
    const wrapper = mount(SkillPage, { props: { namespace: 'demo-user', name: 'pdf-tools' } })
    await flush()

    expect(wrapper.find('.pager').exists()).toBe(false)
    expect(wrapper.find('.grants').text()).toContain('yuhan')
    wrapper.unmount()
  })

  it('re-reads the population after a share while a search is active', async () => {
    // 「这个 skill 是私有的，目前只有你可以访问」 is about the skill, not about the query — so a
    // grant added under a filter has to change it, even though the filtered list stays empty.
    location = stubLocation(
      '/skills/demo-user/pdf-tools',
      '?tab=settings&sub=collaborators&q=zzz',
    )
    stubFetch(
      () => json(200, SKILL),
      () => json(200, { grants: [], total: 0, page: 1, page_size: 10 }),
      () => json(200, { requests: [], total: 0 }),
      () => json(200, { grants: [], total: 0, page: 1, page_size: 10 }),
      () => json(201, { handle: 'newbie', role: 'invoke' }),
      () => json(200, { grants: [], total: 0, page: 1, page_size: 10 }),
      () => json(200, { ...GRANTS, total: 1 }),
    )
    vi.resetModules()
    const { default: SkillPage } = await import('../src/pages/SkillPage.vue')
    const wrapper = mount(SkillPage, { props: { namespace: 'demo-user', name: 'pdf-tools' } })
    await flush()

    expect(wrapper.findAll('.settings-block')[1]?.find('.hint').text()).toContain(
      '目前只有你可以访问',
    )

    await wrapper.find('.share-row input').setValue('newbie')
    await wrapper.find('.share-row .button').trigger('click')
    await flush()

    expect(wrapper.findAll('.settings-block')[1]?.find('.hint').text()).toContain(
      '只有你和下列协作者可以访问',
    )
    wrapper.unmount()
  })

  it('says the skill is private and only the owner reaches it when nobody is shared', async () => {
    const { wrapper } = await showSettings(SKILL, 'collaborators', [
      () => json(200, { grants: [], total: 0, page: 1, page_size: 10 }),
      () => json(200, { requests: [], total: 0 }),
    ])

    expect(wrapper.findAll('.settings-block')[1]?.find('.hint').text()).toContain(
      '目前只有你可以访问',
    )
    wrapper.unmount()
  })
})
