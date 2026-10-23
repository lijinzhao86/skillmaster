import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import SkillPage from '../src/pages/SkillPage'
import {
  clearCookies,
  flush,
  json,
  setCookie,
  stubFetch,
  stubLocation,
  type RecordedRequest,
} from './support'

/**
 * One skill's four sections, and the writes.
 *
 * **Ported from the Vue file's `one skill` half.** The list half is `skills-list.test.tsx`; this is
 * what `SkillPage` does — the sections a rung can reach, the information it draws, the files, the
 * versions, and settings. The assertions are the Vue file's, unchanged: which address, which method,
 * which body, and what is *not* drawn. The port only moved them onto Testing Library — `Mount` became
 * `render`, `find` became `querySelector`, and the page is imported directly because there is no
 * module registry to reset.
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
  // The server lifts these two from the frontmatter (TD 附录 A); the page no longer parses it.
  when_to_use: '需要拆分或合并 PDF 时',
  compatibility: '需要 ghostscript',
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

const text = (element: Element | null | undefined): string => (element?.textContent ?? '').trim()

/**
 * Lets a chain of reads settle.
 *
 * Vue's `flush()` was one microtask turn, which was enough for a ref update to reach the DOM. React
 * commits an effect's state change on its own scheduler turn, and this page reads in chains (the
 * detail, then a child component's own list, then the count); one `setTimeout(0)` only covers the
 * first of them. Several turns, each `flush()`'s own, keep the same intent without asserting on how
 * many turns a render takes.
 */
async function settle(): Promise<void> {
  for (let turn = 0; turn < 6; turn += 1) {
    await flush()
  }
}


/**
 * Renders the skill page with the reads it makes on the way in already answered: the detail, then
 * the file when the address names one, then the comparison when it names one.
 */
/**
 * The three word groups the information page reads (v14). An empty, read-only answer is enough for
 * every test that is about the rest of the page; the tag tests below supply their own.
 */
export const EMPTY_TAGS = {
  category: null,
  public_tags: [],
  my_tags: { readable: false, reason: 'not_in_library', tags: [] },
  /* An entry the author owns: active, and neither joinable nor removable. */
  library: { source: 'created', active: true, joinable: false, can_remove: false },
}

/** `GET /web/categories`: the information page's category menu reads it. */
const CATEGORY_VOCABULARY = {
  categories: [
    { key: 'doc', name: '文档与演示', state: 'enabled', public_total: 4 },
    { key: 'data', name: '数据与分析', state: 'enabled', public_total: 7 },
    { key: 'ops', name: '运维与云', state: 'stopped', public_total: 2 },
  ],
}

async function showSkill(
  skill: Record<string, unknown>,
  options: {
    query?: string
    relpath?: string
    file?: () => Response
    diff?: () => Response
    version?: string
    tags?: () => Response
  } = {},
): Promise<{ container: HTMLElement; requests: RecordedRequest[] }> {
  stubLocation('/skills/demo-user/pdf-tools', options.query ?? '')
  const query = options.query ?? ''
  const responders: Array<() => Response> = [() => json(200, skill)]
  /* A comparison is read by a child panel, whose effect runs before the page's own; the file and
     the word groups are the other way round. */
  if (options.diff !== undefined) {
    responders.push(options.diff)
  }
  /* The word groups and the vocabulary are read on every tab (the page head's join/remove action
     lives there), before the files section's own reads. */
  responders.push(options.tags ?? (() => json(200, EMPTY_TAGS)))
  responders.push(() => json(200, CATEGORY_VOCABULARY))
  if (options.relpath !== undefined) {
    responders.push(options.file ?? (() => new Response('# 标题\n\n正文\n', { status: 200 })))
  }
  if (skill.rung === 'owner' || skill.rung === 'admin') {
    // The settings nav's count: "somebody is waiting" has two homes, and this is one.
    responders.push(() => json(200, { requests: [], total: 0 }))
  }
  const stub = stubFetch(...responders)
  const { container } = render(
    <SkillPage
      namespace="demo-user"
      name="pdf-tools"
      version={options.version}
      relpath={options.relpath}
    />,
  )
  await settle()
  return { container, requests: stub.requests }
}

describe('one skill: the sections a rung can reach', () => {
  it('gives the owner all four sections', async () => {
    const { container } = await showSkill(SKILL)

    expect(
      [...container.querySelectorAll('.skill-tab .skill-tab-label')].map((tab) => text(tab)),
    ).toEqual(['信息', '文件', '版本', '设置'])
  })

  it('draws no 设置 for 浏览, and says the skill is private without linking to what they cannot set', async () => {
    const { container } = await showSkill({ ...SKILL, rung: 'browse' })

    expect(
      [...container.querySelectorAll('.skill-tab .skill-tab-label')].map((tab) => text(tab)),
    ).toEqual(['信息', '文件', '版本'])
    // The badge leads to the page that owns the fact, and that page is 管理's — so below it the
    // whole mark is absent, not greyed (design: 够不到的东西不画).
    expect(container.querySelector('.skill-name-row .chip')).toBeNull()
  })

  it('gives 调用 信息 and 版本 only — no file list, no drafts', async () => {
    const { container } = await showSkill({ ...SKILL, rung: 'invoke', files: [], resources: null })

    expect(
      [...container.querySelectorAll('.skill-tab .skill-tab-label')].map((tab) => text(tab)),
    ).toEqual(['信息', '版本'])
  })
})

describe('one skill: 信息', () => {
  it('reads the description, the when-to-use sentence and the environment', async () => {
    const { container } = await showSkill(SKILL)

    const blocks = [...container.querySelectorAll('.info-block')]
    expect(text(blocks[0])).toContain('描述')
    expect(text(blocks[0])).toContain('把 PDF 拆开再拼起来')
    expect(text(blocks[1])).toContain('适用场景')
    expect(text(blocks[1])).toContain('需要拆分或合并 PDF 时')
    expect(text(blocks[2])).toContain('运行环境')
    expect(text(blocks[2])).toContain('需要 ghostscript')
    expect(text(container.querySelector('.info-rail-value'))).toBe('@1.0.0')
  })

  it('leaves out a block the author did not write', async () => {
    const { container } = await showSkill({
      ...SKILL,
      when_to_use: null,
      compatibility: null,
      frontmatter: { name: 'pdf-tools' },
    })

    expect(container.querySelectorAll('.info-block')).toHaveLength(1)
  })

  it('offers 申请 to 浏览, and posts the rung that was asked for', async () => {
    const { container } = await showSkill({ ...SKILL, rung: 'browse' })
    const post = stubFetch(() => json(201, {}))

    expect(container.querySelector('.ask')).not.toBeNull()
    expect(text(container.querySelector('.ask .hint'))).toContain('当前档位为「浏览」')
    expect(text(container.querySelector('.ask button.button'))).toBe('申请成为协作者')
    // The note under the picker says what the chosen rung adds and what it lacks.
    expect(text(container.querySelector('.ask-note'))).toContain('可以提交新版本')

    fireEvent.click(container.querySelector('.ask button.button') as HTMLButtonElement)
    await settle()

    expect(post.requests[0]?.path).toBe('/web/skills/demo-user/pdf-tools/access-requests')
    expect(post.requests[0]?.method).toBe('POST')
    expect(post.requests[0]?.body).toEqual({ rung: 'write' })
  })

  it('draws no 申请 for the owner or for 管理 — there is no rung above them', async () => {
    const owner = await showSkill(SKILL)
    expect(owner.container.querySelector('.ask')).toBeNull()

    const admin = await showSkill({ ...SKILL, rung: 'admin' })
    expect(admin.container.querySelector('.ask')).toBeNull()
  })

  it('shows 等待处理 from the server, and withdraws it', async () => {
    // The server carries the caller's own pending request; a page that remembered its own click
    // would show the form again to somebody already waiting.
    const { container } = await showSkill({ ...SKILL, rung: 'browse', pending_rung: 'write' })
    const post = stubFetch(() => new Response(null, { status: 204 }))

    expect(container.querySelector('.ask-form')).toBeNull()
    expect(text(container.querySelector('.ask-pending'))).toContain('等待处理')
    expect(text(container.querySelector('.ask-pending'))).toContain('已提交「写入」的申请')

    fireEvent.click(container.querySelector('.ask-pending .link-button') as HTMLButtonElement)
    await settle()

    expect(post.requests[0]?.path).toBe('/web/skills/demo-user/pdf-tools/access-requests/me')
    expect(post.requests[0]?.method).toBe('DELETE')
  })
})

describe('one skill: the three word groups (v14)', () => {
  const TAGS = {
    category: { key: 'doc', name: '文档与演示' },
    public_tags: [{ key: 'pdf', name: 'PDF' }],
    my_tags: {
      readable: true,
      reason: null,
      tags: [{ id: 'client', name: '客户项目' }],
    },
    library: { source: 'created', active: true, joinable: false, can_remove: false },
  }

  it('draws category → public tags → my tags, in that order', async () => {
    const { container } = await showSkill(SKILL, { tags: () => json(200, TAGS) })

    const groups = [...container.querySelectorAll('.info-rail .info-group')]
    expect(groups.map((group) => group.id)).toEqual([
      'infoCategories',
      'infoPublicTags',
      'infoMyTags',
    ])
    expect(text(groups[0]?.querySelector('.chip'))).toBe('文档与演示')
    expect(text(groups[1]?.querySelector('.chip'))).toBe('#PDF')
    expect(text(groups[2]?.querySelector('.chip'))).toBe('客户项目')
  })

  it('changes the category from a single-select menu, with stopped words disabled', async () => {
    const { container } = await showSkill(SKILL, { tags: () => json(200, TAGS) })
    const post = stubFetch(
      () => new Response(null, { status: 204 }),
      () => json(200, { ...TAGS, category: { key: 'data', name: '数据与分析' } }),
    )

    fireEvent.click(container.querySelector('#editCategories') as HTMLButtonElement)
    expect(
      (container.querySelector('.menu-item[data-value="ops"]') as HTMLButtonElement).disabled,
    ).toBe(true)
    // The count beside a word is the public skills on that shelf (`public_total`).
    expect(
      text(container.querySelector('.menu-item[data-value="doc"] .menu-number')),
    ).toBe('4')
    // A selected category has a way to no category at all.
    expect(container.querySelector('.menu-clear')).not.toBeNull()

    fireEvent.click(container.querySelector('.menu-item[data-value="data"]') as HTMLButtonElement)
    await settle()

    expect(post.requests[0]?.path).toBe('/web/skills/demo-user/pdf-tools/category')
    expect(post.requests[0]?.method).toBe('PUT')
    expect(post.requests[0]?.body).toEqual({ key: 'data' })
    expect(text(container.querySelector('#infoCategories .chip'))).toBe('数据与分析')
  })

  it('clears the category through the menu', async () => {
    const { container } = await showSkill(SKILL, { tags: () => json(200, TAGS) })
    const post = stubFetch(
      () => new Response(null, { status: 204 }),
      () => json(200, { ...TAGS, category: null }),
    )

    fireEvent.click(container.querySelector('#editCategories') as HTMLButtonElement)
    fireEvent.click(container.querySelector('.menu-clear') as HTMLButtonElement)
    await settle()

    expect(post.requests[0]?.path).toBe('/web/skills/demo-user/pdf-tools/category')
    expect(post.requests[0]?.method).toBe('DELETE')
  })

  it('offers 加入 when the skill is visible but not yet in the library', async () => {
    const outside = {
      category: null,
      public_tags: [],
      my_tags: { readable: false, reason: 'not_in_library', tags: [] },
      library: { source: null, active: false, joinable: true, can_remove: false },
    }
    const joined = {
      ...outside,
      my_tags: { readable: true, reason: null, tags: [] },
      library: { source: 'joined', active: true, joinable: false, can_remove: true },
    }
    const { container } = await showSkill(
      { ...SKILL, rung: 'browse', visibility: 'public', public_rung: 'browse' },
      { tags: () => json(200, outside) },
    )

    expect(text(container.querySelector('#joinSkill'))).toBe('加入')
    expect(container.querySelector('#removeSkill')).toBeNull()

    const post = stubFetch(
      () => json(200, { changed: true }),
      () => json(200, joined),
    )
    fireEvent.click(container.querySelector('#joinSkill') as HTMLButtonElement)
    await settle()

    expect(post.requests[0]?.path).toBe('/web/skills/demo-user/pdf-tools/library')
    expect(post.requests[0]?.method).toBe('POST')
    // The re-read is what turns the button into 移除; the page does not patch its own copy.
    expect(post.requests[1]?.path).toBe('/web/skills/demo-user/pdf-tools/tags')
    expect(text(container.querySelector('#removeSkill'))).toBe('移除')
    expect(container.querySelector('#joinSkill')).toBeNull()
  })

  it('removes a joined skill through an uncoloured sheet', async () => {
    const joined = {
      category: null,
      public_tags: [],
      my_tags: { readable: true, reason: null, tags: [] },
      library: { source: 'joined', active: true, joinable: false, can_remove: true },
    }
    const left = {
      ...joined,
      my_tags: { readable: false, reason: 'not_in_library', tags: [] },
      library: { source: null, active: false, joinable: true, can_remove: false },
    }
    const { container } = await showSkill(
      { ...SKILL, rung: 'browse', visibility: 'public', public_rung: 'browse' },
      { tags: () => json(200, joined) },
    )

    fireEvent.click(container.querySelector('#removeSkill') as HTMLButtonElement)
    expect(text(container.querySelector('.confirm-title'))).toBe('从我的 skill 移除？')
    expect(text(container.querySelector('.confirm-body'))).toBe(
      '标签会保留，重新加入后会回来。这个 skill 仍在公域，仍可直接调用。',
    )
    // Removing a reference takes nothing away: the sheet is not red (ADR 0056).
    expect(container.querySelector('.confirm-acts .button.is-danger')).toBeNull()

    const post = stubFetch(
      () => json(200, { changed: true }),
      () => json(200, left),
    )
    const confirm = [...container.querySelectorAll('.confirm-acts .button')].find(
      (button) => text(button) === '确认移除',
    ) as HTMLButtonElement
    fireEvent.click(confirm)
    await settle()

    expect(post.requests[0]?.path).toBe('/web/skills/demo-user/pdf-tools/library')
    expect(post.requests[0]?.method).toBe('DELETE')
    expect(container.querySelector('.confirm')).toBeNull()
    expect(text(container.querySelector('#joinSkill'))).toBe('加入')
  })

  it('draws neither 加入 nor 移除 for a skill I created or one shared with me', async () => {
    const created = await showSkill(SKILL, { tags: () => json(200, TAGS) })
    expect(created.container.querySelector('#joinSkill')).toBeNull()
    expect(created.container.querySelector('#removeSkill')).toBeNull()

    const shared = {
      ...TAGS,
      library: { source: 'shared', active: true, joinable: false, can_remove: false },
    }
    const sharedView = await showSkill(SKILL, { tags: () => json(200, shared) })
    expect(sharedView.container.querySelector('#joinSkill')).toBeNull()
    expect(sharedView.container.querySelector('#removeSkill')).toBeNull()
  })

  it('says why a visible skill cannot be joined', async () => {
    const blocked = {
      category: null,
      public_tags: [],
      my_tags: { readable: false, reason: 'not_in_library', tags: [] },
      library: { source: null, active: false, joinable: false, can_remove: false },
    }
    const offline = await showSkill(
      { ...SKILL, rung: 'browse', visibility: 'public', public_rung: 'browse', offline_at: '2026-10-20T09:00:00Z' },
      { tags: () => json(200, blocked) },
    )
    expect(offline.container.querySelector('#joinSkill')).toBeNull()
    expect(text(offline.container.querySelector('.info-note'))).toBe('已下线，暂时不能加入。')

    const noLive = await showSkill(
      { ...SKILL, rung: 'browse', visibility: 'public', public_rung: 'browse' },
      { tags: () => json(200, blocked) },
    )
    expect(noLive.container.querySelector('#joinSkill')).toBeNull()
    expect(text(noLive.container.querySelector('.info-note'))).toBe(
      '还没有上线的版本，暂时不能加入。',
    )
  })

  it('adds and removes a public tag in place, with candidates read only when opened', async () => {
    const { container } = await showSkill(SKILL, { tags: () => json(200, TAGS) })
    const withBatch = {
      ...TAGS,
      public_tags: TAGS.public_tags.concat({ key: 'batch', name: '批量处理' }),
    }
    const stub = stubFetch(
      () =>
        json(200, {
          tags: [{ key: 'batch', name: '批量处理', public_total: 2 }],
        }),
      () => json(200, { changed: true }),
      () => json(200, withBatch),
      () => json(200, { changed: true }),
      () => json(200, TAGS),
    )

    fireEvent.click(container.querySelector('#editPublicTags') as HTMLButtonElement)
    await settle()
    expect(stub.requests[0]?.path).toBe('/web/public-tags')

    const input = container.querySelector('#publicTagInput') as HTMLInputElement
    fireEvent.change(input, { target: { value: '批量处理' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await settle()

    expect(stub.requests[1]?.path).toBe('/web/skills/demo-user/pdf-tools/tags/public')
    expect(stub.requests[1]?.body).toEqual({ name: '批量处理' })

    fireEvent.click(
      [...container.querySelectorAll('#infoPublicTagsList .filter-x')][0] as HTMLButtonElement,
    )
    await settle()
    expect(stub.requests[3]?.path).toBe(
      '/web/skills/demo-user/pdf-tools/tags/public?name=PDF',
    )
    expect(stub.requests[3]?.method).toBe('DELETE')
  })

  it('adds a private tag in place and only when the library entry is effective', async () => {
    const { container } = await showSkill(SKILL, { tags: () => json(200, TAGS) })
    const stub = stubFetch(
      () => json(200, { tags: [{ id: 'evaluate', name: '待评估', description: null, used_in: 0 }] }),
      () => json(200, {}),
      () =>
        json(200, {
          ...TAGS,
          my_tags: { ...TAGS.my_tags, tags: TAGS.my_tags.tags.concat({ id: 'evaluate', name: '待评估' }) },
        }),
    )

    fireEvent.click(container.querySelector('#editMyTags') as HTMLButtonElement)
    await settle()
    expect(stub.requests[0]?.path).toBe('/web/tags')

    const input = container.querySelector('#myTagInput') as HTMLInputElement
    fireEvent.change(input, { target: { value: '待评估' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await settle()

    expect(stub.requests[1]?.path).toBe('/web/skills/demo-user/pdf-tools/tags/my')
    expect(stub.requests[1]?.body).toEqual({ name: '待评估' })
  })

  it('draws no my-tags group when the entry is not in the library or is dormant', async () => {
    const outside = {
      category: null,
      public_tags: [],
      my_tags: { readable: false, reason: 'not_in_library', tags: [] },
      library: { source: null, active: false, joinable: true, can_remove: false },
    }
    const absent = await showSkill({ ...SKILL, rung: 'browse' }, { tags: () => json(200, outside) })
    expect(absent.container.querySelector('#infoMyTags')).toBeNull()
    // A read-only view with no words draws no empty block either.
    expect(
      absent.container.querySelector('#infoPublicTags')?.hasAttribute('hidden'),
    ).toBe(true)

    const dormant = await showSkill(SKILL, {
      tags: () =>
        json(200, {
          category: { key: 'doc', name: '文档与演示' },
          public_tags: [],
          my_tags: { readable: false, reason: 'dormant', tags: [] },
          library: { source: 'joined', active: false, joinable: false, can_remove: true },
        }),
    })
    expect(dormant.container.querySelector('#infoMyTags')).toBeNull()
    expect(text(dormant.container.querySelector('.info-note'))).toBe(
      '暂时不可见，恢复后会回到你的库。',
    )
  })
})

describe('one skill: 文件', () => {
  it('offers the readable versions in one menu, counts the versions, and links each file', async () => {
    const { container } = await showSkill(SKILL, { query: '?tab=files' })

    // The picker is the page's own menu now: name, version number and status tag, not `<optgroup>`.
    expect(text(container.querySelector('.version-picker .menu-trigger-text'))).toBe('第一版')
    expect(
      [...container.querySelectorAll('.version-picker .menu-label')].map((node) => text(node)),
    ).toEqual(['第一版', '第二版'])
    expect(
      [...container.querySelectorAll('.version-picker .menu-number')].map((node) => text(node)),
    ).toEqual(['@1.0.0'])
    expect(
      [...container.querySelectorAll('.version-picker .menu-tag')].map((node) => text(node)),
    ).toEqual(['线上', '草稿'])
    // The count is non-draft, non-discarded: the draft is not a version the skill *has*.
    expect(text(container.querySelector('.skill-version-count'))).toBe('1 个版本')
    expect(text(container.querySelector('.skill-address'))).toContain('demo-user/pdf-tools')
    // With no pin in the path, the file tab falls back to the live version.
    expect(container.querySelector('ul.files a')?.getAttribute('href')).toBe(
      '/skills/demo-user/pdf-tools@1.0.0/files/SKILL.md',
    )
  })

  it('lets an explicit tab win over a directory in the address', async () => {
    // A directory is the address's default destination, not an override of a section it also
    // names — the design settles the same order (a valid tab first, then the named files).
    const { container } = await showSkill(SKILL, { query: '?tab=versions&path=references' })

    expect(container.querySelector('[data-tab="versions"]')).not.toBeNull()
    expect(container.querySelector('[data-tab="files"]')).toBeNull()
  })

  it('does not carry a directory to another section', async () => {
    // The tab link keeps the address' facts, but `path` belongs to 文件: leaving it in a 版本 link
    // made the page reload into 文件 again, and no tab could be reached from a directory.
    const { container } = await showSkill(SKILL, { query: '?tab=files&path=references' })

    const versions = [...container.querySelectorAll('.skill-tab')].find(
      (tab) => text(tab.querySelector('.skill-tab-label')) === '版本',
    )
    expect(versions?.getAttribute('href')).toBe('/skills/demo-user/pdf-tools?tab=versions')
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
    const { container } = await showSkill(nested, { query: '?tab=files' })

    const names = [...container.querySelectorAll('.files .file-name')].map((link) => text(link))
    expect(names).toEqual(['references/', 'scripts/', 'SKILL.md'])
    // A folder goes into its own listing, not to a file address.
    expect(
      [...container.querySelectorAll('.files .file-name')][0]?.getAttribute('href'),
    ).toContain('path=references')
    // The root opens SKILL.md by default.
    expect(text(container.querySelector('.file-tab'))).toBe('SKILL.md')
  })

  it('opens a file under the listing, rendered as a document', async () => {
    const { container } = await showSkill(SKILL, {
      query: '?tab=files',
      relpath: 'SKILL.md',
      file: () => new Response('# 标题\n\n正文\n', { status: 200 }),
    })

    expect(text(container.querySelector('.file-tab'))).toBe('SKILL.md')
    expect(text(container.querySelector('.doc h1'))).toBe('标题')
  })
})

describe('one skill: 版本', () => {
  // **Only the files tab addresses a version in its path.** 信息 / 版本 / 设置 are skill-level
  // screens, and an `@1.2.3` on them named a version the screen did not show (TD §前端架构: every
  // face writes one kind of pin; the versions tab's pin is the query).
  it('keeps the path pin on the files tab and drops it on the others', async () => {
    const { container } = await showSkill(SKILL, { query: '?tab=files', version: '1.2.3' })

    const href = (label: string): string | null | undefined =>
      [...container.querySelectorAll('.skill-tab')]
        .find((tab) => text(tab).includes(label))
        ?.getAttribute('href')
    expect(href('文件')).toBe('/skills/demo-user/pdf-tools@1.2.3?tab=files')
    expect(href('版本')).toBe('/skills/demo-user/pdf-tools?tab=versions')
    expect(href('设置')).toBe('/skills/demo-user/pdf-tools?tab=settings')
  })

  it('draws two-segment rows, the derived statuses, and the non-draft count', async () => {
    const { container } = await showSkill(SKILL, { query: '?tab=versions' })

    // The top tab counts what the skill *has*: non-draft, non-discarded.
    expect(text(container.querySelector('.skill-tab-count'))).toBe('1')
    // The filter chips count what this rung can see, draft included; the draft is not a 版本.
    expect(
      [...container.querySelectorAll('.version-filter')].map((chip) => text(chip)),
    ).toEqual(['全部 2', '线上 1', '草稿 1'])

    const rows = [...container.querySelectorAll('.versions li')]
    expect(rows).toHaveLength(2)
    expect(text(rows[0]?.querySelector('.version-name'))).toBe('第二版')
    expect(text(rows[0]?.querySelector('.version-number'))).toBe('—')
    expect(text(rows[0]?.querySelector('.chip-text'))).toBe('草稿')
    expect(text(rows[0]?.querySelector('.version-note'))).toBe('第二版说明')
    expect(text(rows[0]?.querySelector('.version-meta'))).toContain('demo-user')
    expect(text(rows[1]?.querySelector('.version-name'))).toBe('第一版')
    expect(text(rows[1]?.querySelector('.version-number'))).toBe('@1.0.0')
    expect(text(rows[1]?.querySelector('.chip-text'))).toBe('线上')
    // The list only navigates. The draft's menu points at its own page for 上线 / 丢弃.
    expect(
      [...(rows[0]?.querySelectorAll('.version-menu .menu-item') ?? [])].map((item) => text(item)),
    ).toEqual(['查看文件', '编辑', '查看改动', '上线', '丢弃'])
  })

  it('publishes a draft on its own page, and discards it after a confirmation', async () => {
    const { container } = await showSkill(SKILL, {
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
    const publish = [...container.querySelectorAll('.vp-action .button')].find((button) =>
      text(button).startsWith('上线 @'),
    )
    expect(text(publish)).toBe('上线 @1.0.1')
    fireEvent.click(publish as HTMLButtonElement)

    // Every state-changing action opens the one confirmation sheet.
    expect(text(container.querySelector('.confirm'))).toContain('名称与说明在这一刻锁定')
    fireEvent.click(
      container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement,
    )
    await settle()

    expect(post.requests[0]?.path).toBe('/web/skills/demo-user/pdf-tools/publish')
    expect(post.requests[0]?.body).toEqual({ version: 'd1', release_name: '1.0.1' })

    // Discard is in the ordinary action flow, outlined, and its sheet says it cannot be recovered.
    const discard = [...container.querySelectorAll('.vp-action .button')].find(
      (button) => text(button) === '丢弃',
    )
    fireEvent.click(discard as HTMLButtonElement)
    expect(text(container.querySelector('.confirm-body'))).toContain('丢弃后无法找回')
    fireEvent.click(
      container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement,
    )
    await settle()

    expect(post.requests[1]?.path).toBe('/web/skills/demo-user/pdf-tools/discard')
    expect(post.requests[1]?.body).toEqual({ version: 'd1' })
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
    const { container } = await showSkill(
      { ...SKILL, versions: [discarded, DRAFT_VERSION, LIVE_VERSION] },
      { query: '?tab=versions' },
    )

    // Only the live version counts: a discarded one was never a version the skill has.
    expect(text(container.querySelector('.skill-tab-count'))).toBe('1')
    const chips = [...container.querySelectorAll('.versions .chip-text')].map((chip) => text(chip))
    expect(chips).toEqual(['已丢弃', '草稿', '线上'])
    // 已丢弃 is terminal: the row has no 更多操作 menu.
    expect([...container.querySelectorAll('.versions li')][0]?.querySelector('.version-menu-trigger')).toBeNull()
  })

  it('draws no lifecycle action for 浏览', async () => {
    const { container } = await showSkill({ ...SKILL, rung: 'browse' }, { query: '?tab=versions' })

    const items = [...container.querySelectorAll('.version-menu .menu-item')].map((item) => text(item))
    expect(items).toEqual(['查看文件'])
    expect(items).not.toContain('上线')
    expect(items).not.toContain('丢弃')
    // No 新建版本 for a rung that cannot submit.
    expect(container.querySelector('.version-create')).toBeNull()
  })

  it('lets 维护 discard somebody else’s draft, and 写入 not', async () => {
    const versions = [OTHER_DRAFT, LIVE_VERSION]
    const maintain = await showSkill({ ...SKILL, rung: 'maintain', versions }, { query: '?tab=versions' })
    expect(
      [...maintain.container.querySelectorAll('.version-menu .menu-item')].map((button) => text(button)),
    ).toContain('丢弃')

    const write = await showSkill({ ...SKILL, rung: 'write', versions }, { query: '?tab=versions' })
    expect(
      [...write.container.querySelectorAll('.version-menu .menu-item')].map((button) => text(button)),
    ).not.toContain('丢弃')
    // 写入 may submit, so the entry is drawn — v9 makes it a menu (在线编辑 / 导入压缩包), and the
    // editor item is still a link, which executes nothing by itself (v8 E1: opening the page writes
    // no version).
    expect(write.container.querySelector('.version-create')).not.toBeNull()
    const edit = [...write.container.querySelectorAll('.version-toolbar .menu-item')].find(
      (item) => text(item) === '在线编辑',
    )
    expect(edit?.getAttribute('href')).toBe('/skills/demo-user/pdf-tools/new')
  })

  it('opens the legacy /diff alias on the version page’s 改动 pane', async () => {
    stubLocation('/skills/demo-user/pdf-tools', '')
    const stub = stubFetch(
      () => json(200, SKILL),
      () => json(200, DIFF),
      () => json(200, { requests: [], total: 0 }),
    )
    const { container } = render(
      <SkillPage namespace="demo-user" name="pdf-tools" from="1.0.0" to="d1" />,
    )
    await settle()

    expect(stub.requests[1]?.path).toBe('/web/skills/demo-user/pdf-tools@d1/diff?from=1.0.0')
    expect(text(container.querySelector('.vp-tab.is-current'))).toContain('改动')
    expect(container.querySelector('.vp-file')).not.toBeNull()
    // Coloured by the line's own prefix, which is unified diff's.
    expect(text(container.querySelector('.is-add .diff-text'))).toBe('+第二版')
    // 版本 is the way back to the list.
    expect(container.querySelector('.crumbs a')?.getAttribute('href')).toBe(
      '/skills/demo-user/pdf-tools?tab=versions',
    )
  })

  it('keeps the handoff preselection out of the other sections', async () => {
    // The version page's publish preselection is version-tab state, not skill state: every top tab
    // is a different screen and must not carry it (v11 PRD 验收 #11).
    stubLocation('/skills/demo-user/pdf-tools', '?tab=versions&version=d1&sub=actions&bump=minor')
    stubFetch(
      () => json(200, SKILL),
      () => json(200, DIFF),
      () => json(200, { requests: [], total: 0 }),
    )
    const first = render(<SkillPage namespace="demo-user" name="pdf-tools" />)
    await settle()

    const tabs = [...first.container.querySelectorAll('.skill-tab')]
    expect(tabs.length).toBeGreaterThan(0)
    for (const tab of tabs) {
      expect(tab.getAttribute('href') ?? '').not.toContain('bump')
    }
    // The visibility chip is another exit, into 设置 › 常规.
    const chip = [...first.container.querySelectorAll('a.chip')].find(
      (node) => text(node) === '私有',
    )
    expect(chip?.getAttribute('href')).toBe('/skills/demo-user/pdf-tools?tab=settings&sub=general')

    // And so is 「回到版本列表」 on the 「没有这一版」 screen.
    stubLocation('/skills/demo-user/pdf-tools', '?tab=versions&version=nope&bump=minor')
    stubFetch(() => json(200, SKILL))
    const missing = render(<SkillPage namespace="demo-user" name="pdf-tools" />)
    await settle()
    const back = [...missing.container.querySelectorAll('a')].find(
      (node) => text(node) === '回到版本列表',
    )
    expect(back?.getAttribute('href')).toBe('/skills/demo-user/pdf-tools?tab=versions')
  })
})

/* --------------------------------------------------------------------- 设置 */

async function showSettings(
  skill: Record<string, unknown>,
  sub: string,
  responders: Array<() => Response>,
): Promise<{ container: HTMLElement; requests: RecordedRequest[] }> {
  stubLocation('/skills/demo-user/pdf-tools', `?tab=settings&sub=${sub}`)
  const vocabulary = [() => json(200, EMPTY_TAGS), () => json(200, CATEGORY_VOCABULARY)]
  /* The word groups and the category vocabulary are read on every tab. Their effects run before
     the panel on 常规 and after the panel's own reads on 协作者. */
  const ordered =
    sub === 'collaborators'
      ? [...responders.slice(0, 2), ...vocabulary, ...responders.slice(2)]
      : [...vocabulary, ...responders]
  const stub = stubFetch(() => json(200, skill), ...ordered)
  const { container } = render(<SkillPage namespace="demo-user" name="pdf-tools" />)
  await settle()
  /* These tests are about the settings writes; the two vocabulary reads are not part of that. */
  /* These tests are about the settings writes; the two vocabulary reads are not part of that.
     Splice in place so the array keeps recording the requests made after this harness returns. */
  for (let index = stub.requests.length - 1; index >= 0; index -= 1) {
    const path = stub.requests[index]?.path ?? ''
    if (path === '/web/categories' || path.endsWith('/tags')) {
      stub.requests.splice(index, 1)
    }
  }
  return { container, requests: stub.requests }
}

describe('one skill: 设置 › 常规', () => {
  it('reads the three-way visibility back from the server', async () => {
    const { container } = await showSettings(SKILL, 'general', [
      () => json(200, { requests: [], total: 0 }),
    ])

    const checked = [...container.querySelectorAll<HTMLInputElement>('input[name="skill-visibility"]')].find(
      (input) => input.checked,
    )
    expect(checked?.value).toBe('private')
  })

  it('patches both facts of a public choice together', async () => {
    const { container, requests } = await showSettings(SKILL, 'general', [
      () => json(200, { requests: [], total: 0 }),
      () => new Response(null, { status: 204 }),
    ])

    fireEvent.click(
      [...container.querySelectorAll<HTMLInputElement>('input[name="skill-visibility"]')][1]!,
    )
    await settle()

    const patch = requests.at(-1)
    expect(patch?.path).toBe('/web/skills/demo-user/pdf-tools/visibility')
    expect(patch?.method).toBe('PATCH')
    expect(patch?.body).toEqual({ visibility: 'public', public_rung: 'invoke' })
  })

  it('asks before 公开 · 浏览, and the selector does not move before the answer', async () => {
    const { container, requests } = await showSettings(SKILL, 'general', [
      () => json(200, { requests: [], total: 0 }),
      () => new Response(null, { status: 204 }),
    ])

    fireEvent.click(
      [...container.querySelectorAll<HTMLInputElement>('input[name="skill-visibility"]')][2]!,
    )
    await settle()

    expect(text(container.querySelector('.confirm'))).toContain('已经发生的下载与复制无法收回')
    // Nothing written until the answer, and the selector is back on the state that is true.
    expect(requests).toHaveLength(2)
    const radios = [...container.querySelectorAll<HTMLInputElement>('input[name="skill-visibility"]')]
    expect(radios[0]!.checked).toBe(true)
    expect(radios[2]!.checked).toBe(false)

    fireEvent.click(
      container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement,
    )
    await settle()

    expect(requests.at(-1)?.body).toEqual({ visibility: 'public', public_rung: 'browse' })
  })

  it('asks for the consequence before deleting, then leaves for the deleted list', async () => {
    location = stubLocation('/skills/demo-user/pdf-tools', '?tab=settings&sub=general')
    const stub = stubFetch(
      () => json(200, SKILL),
      () => json(200, EMPTY_TAGS),
      () => json(200, CATEGORY_VOCABULARY),
      () => json(200, { requests: [], total: 0 }),
      () => new Response(null, { status: 204 }),
    )
    const { container } = render(<SkillPage namespace="demo-user" name="pdf-tools" />)
    await settle()

    const remove = [...container.querySelectorAll('.danger-acts .button')].find(
      (button) => text(button) === '删除',
    )
    fireEvent.click(remove as HTMLButtonElement)
    expect(text(container.querySelector('.confirm-body'))).toContain('该名称将无法用于创建新 skill')

    fireEvent.click(container.querySelector('.confirm-acts .button.is-danger') as HTMLButtonElement)
    await settle()

    expect(stub.requests.at(-1)?.path).toBe('/web/skills/demo-user/pdf-tools/delete')
    expect(stub.requests.at(-1)?.method).toBe('POST')
    expect(location.assign).toHaveBeenCalledWith('/?show=deleted')
  })

  it('switches the whole skill off and on from the danger zone, through the sheet', async () => {
    location = stubLocation('/skills/demo-user/pdf-tools', '?tab=settings&sub=general')
    const stub = stubFetch(
      () => json(200, SKILL),
      () => json(200, EMPTY_TAGS),
      () => json(200, CATEGORY_VOCABULARY),
      () => json(200, { requests: [], total: 0 }),
      () => new Response(null, { status: 204 }),
    )
    const { container } = render(<SkillPage namespace="demo-user" name="pdf-tools" />)
    await settle()

    const offlineButton = [...container.querySelectorAll('.danger-acts .button')].find(
      (button) => text(button) === '下线',
    )
    fireEvent.click(offlineButton as HTMLButtonElement)
    expect(text(container.querySelector('.confirm-title'))).toBe('下线这个 skill')
    fireEvent.click(
      container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement,
    )
    await settle()

    expect(stub.requests.at(-1)?.path).toBe('/web/skills/demo-user/pdf-tools/offline')
    expect(stub.requests.at(-1)?.method).toBe('POST')
  })

  it('draws no danger zone for 管理 — deletion is the owner’s', async () => {
    const { container } = await showSettings({ ...SKILL, rung: 'admin' }, 'general', [
      () => json(200, { requests: [], total: 0 }),
    ])

    expect(container.querySelector('.settings-block.is-danger')).toBeNull()
  })
})

describe('one skill: 设置 › 协作者', () => {
  const GRANTS = {
    grants: [{ handle: 'yuhan', role: 'browse', created_at: '2026-10-01T09:00:00Z' }],
    total: 1,
    page: 1,
    page_size: 10,
  }
  const REQUESTS = {
    requests: [{ handle: 'kai', wanted_role: 'write', created_at: '2026-10-02T09:00:00Z' }],
    total: 1,
  }

  it('draws the owner first, then the grants, and names each rung', async () => {
    const { container } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
    ])

    const rows = [...container.querySelectorAll('.grants li')]
    expect(text(rows[0])).toContain('demo-user')
    expect(text(rows[0])).toContain('所有者')
    expect(text(rows[1])).toContain('yuhan')
    expect(text(rows[1]?.querySelector('.menu-trigger'))).toBe('浏览')
    expect(text(container.querySelector('.settings-nav .count'))).toBe('1')
  })

  it('shares with the typed handle at the offered rung, then re-reads the list', async () => {
    const { container, requests } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
      () => json(201, { handle: 'newbie', role: 'invoke' }),
      () => json(200, { ...GRANTS, total: 2 }),
    ])

    fireEvent.change(container.querySelector('.share-row input') as HTMLInputElement, {
      target: { value: 'newbie' },
    })
    fireEvent.click(container.querySelector('.share-row .button') as HTMLButtonElement)
    await settle()

    const post = requests.find((request) => request.method === 'POST')
    expect(post?.path).toBe('/web/skills/demo-user/pdf-tools/grants')
    expect(post?.body).toEqual({ handle: 'newbie', role: 'invoke' })
  })

  it('takes a share away at the handle’s own address', async () => {
    const { container, requests } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
      () => new Response(null, { status: 204 }),
      () => json(200, { grants: [], total: 0, page: 1, page_size: 10 }),
    ])

    fireEvent.click(container.querySelector('.grants .withdraw') as HTMLButtonElement)
    await settle()

    const revoke = requests.find((request) => request.method === 'DELETE')
    expect(revoke?.path).toBe('/web/skills/demo-user/pdf-tools/grants/yuhan')
  })

  it('approves a request at the rung it was asked for, and denies another', async () => {
    const { container, requests } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
      () => new Response(null, { status: 204 }),
      () => json(200, { requests: [], total: 0 }),
      () => json(200, { ...GRANTS, total: 2 }),
      () => new Response(null, { status: 204 }),
      () => json(200, { requests: [], total: 0 }),
    ])

    expect(text(container.querySelector('.grants.is-requests'))).toContain('申请「写入」')
    fireEvent.click(
      container.querySelector('.grants.is-requests .row-acts .button') as HTMLButtonElement,
    )
    await settle()

    const approve = requests.find((request) => request.path.endsWith('/approve'))
    expect(approve?.path).toBe('/web/skills/demo-user/pdf-tools/access-requests/kai/approve')
    expect(approve?.body).toEqual({ rung: 'write' })
  })

  it('keeps the population sentence true when the page is opened on a search', async () => {
    // The filtered response's total cannot answer "does anybody else have access" — so a page
    // opened at `?q=zzz` reads the unfiltered count separately.
    location = stubLocation('/skills/demo-user/pdf-tools', '?tab=settings&sub=collaborators&q=zzz')
    stubFetch(
      () => json(200, SKILL),
      () => json(200, { grants: [], total: 0, page: 1, page_size: 10 }),
      () => json(200, { requests: [], total: 0 }),
      () => json(200, { ...GRANTS, total: 3 }),
      () => json(200, EMPTY_TAGS),
      () => json(200, CATEGORY_VOCABULARY),
    )
    const { container } = render(<SkillPage namespace="demo-user" name="pdf-tools" />)
    await settle()

    expect(text([...container.querySelectorAll('.settings-block')][1]?.querySelector('.hint'))).toContain(
      '只有你和下列协作者可以访问',
    )
    expect(container.textContent).toContain('没有匹配的协作者')
  })

  it('numbers the rows from the filtered match, not from the owner, on later pages', async () => {
    // The owner is a row only when the search matches him. A query that misses him must not reserve
    // his slot on later pages either — that made the range start one late and end past the total.
    const grants = Array.from({ length: 13 }, (_, index) => ({
      handle: `zuser${index + 1}`,
      role: 'invoke',
      created_at: '2026-10-01T09:00:00Z',
    }))
    location = stubLocation('/skills/demo-user/pdf-tools', '?tab=settings&sub=collaborators&q=z&p=2')
    stubFetch(
      () => json(200, SKILL),
      () => json(200, { grants: grants.slice(10), total: 13, page: 2, page_size: 10 }),
      () => json(200, { requests: [], total: 0 }),
      () => json(200, EMPTY_TAGS),
      () => json(200, CATEGORY_VOCABULARY),
      () => json(200, { grants: [], total: 13, page: 1, page_size: 10 }),
    )
    const { container } = render(<SkillPage namespace="demo-user" name="pdf-tools" />)
    await settle()

    expect(text(container.querySelector('.pager-count'))).toBe('共 13 人，第 11–13 人')
  })

  it('reads a clamped page once, at the last page', async () => {
    // `?p=99` is clamped to the last page. The watcher on `page` owns that read; doing it in the
    // load as well sent the same page twice.
    location = stubLocation('/skills/demo-user/pdf-tools', '?tab=settings&sub=collaborators&p=99')
    stubFetch(
      () => json(200, SKILL),
      () => json(200, { grants: [], total: 5, page: 99, page_size: 10 }),
      () => json(200, { requests: [], total: 0 }),
      () => json(200, EMPTY_TAGS),
      () => json(200, CATEGORY_VOCABULARY),
      () => json(200, { ...GRANTS, total: 5 }),
    )
    const { container } = render(<SkillPage namespace="demo-user" name="pdf-tools" />)
    await settle()

    expect(container.querySelector('.pager')).toBeNull()
    expect(text(container.querySelector('.grants'))).toContain('yuhan')
  })

  it('re-reads the population after a share while a search is active', async () => {
    // 「这个 skill 是私有的，目前只有你可以访问」 is about the skill, not about the query — so a
    // grant added under a filter has to change it, even though the filtered list stays empty.
    location = stubLocation('/skills/demo-user/pdf-tools', '?tab=settings&sub=collaborators&q=zzz')
    stubFetch(
      () => json(200, SKILL),
      () => json(200, { grants: [], total: 0, page: 1, page_size: 10 }),
      () => json(200, { requests: [], total: 0 }),
      () => json(200, EMPTY_TAGS),
      () => json(200, CATEGORY_VOCABULARY),
      () => json(200, { grants: [], total: 0, page: 1, page_size: 10 }),
      () => json(201, { handle: 'newbie', role: 'invoke' }),
      () => json(200, { grants: [], total: 0, page: 1, page_size: 10 }),
      () => json(200, { ...GRANTS, total: 1 }),
    )
    const { container } = render(<SkillPage namespace="demo-user" name="pdf-tools" />)
    await settle()

    expect(text([...container.querySelectorAll('.settings-block')][1]?.querySelector('.hint'))).toContain(
      '目前只有你可以访问',
    )

    fireEvent.change(container.querySelector('.share-row input') as HTMLInputElement, {
      target: { value: 'newbie' },
    })
    fireEvent.click(container.querySelector('.share-row .button') as HTMLButtonElement)
    await settle()

    expect(text([...container.querySelectorAll('.settings-block')][1]?.querySelector('.hint'))).toContain(
      '只有你和下列协作者可以访问',
    )
  })

  it('says the skill is private and only the owner reaches it when nobody is shared', async () => {
    const { container } = await showSettings(SKILL, 'collaborators', [
      () => json(200, { grants: [], total: 0, page: 1, page_size: 10 }),
      () => json(200, { requests: [], total: 0 }),
    ])

    expect(text([...container.querySelectorAll('.settings-block')][1]?.querySelector('.hint'))).toContain(
      '目前只有你可以访问',
    )
  })
})
