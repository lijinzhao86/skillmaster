import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import SkillPage from '../src/pages/SkillPage'
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

let location = stubLocation('/')

beforeEach(() => {
  location = stubLocation('/')
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

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


const text = (element: Element | null | undefined): string => (element?.textContent ?? '').trim()

/** The collaborators block's own banner, not the page's. */
const collaboratorBanner = (container: HTMLElement): Element | null =>
  container.querySelector('.settings-pane[data-sub="collaborators"] .banner')

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

async function showSettings(
  skill: Record<string, unknown>,
  sub: string,
  responders: Array<() => Response | Promise<Response>>,
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


describe('one skill: 加入与失败横幅', () => {
  it('takes a failed join off the page banner when a later join succeeds', async () => {
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

    stubFetch(
      () => json(400, errorBody('skill_not_joinable', 'refused')),
      () => json(200, { changed: true }),
      () => json(200, joined),
    )
    fireEvent.click(container.querySelector('#joinSkill') as HTMLButtonElement)
    await settle()
    expect(text(container.querySelector('.page > .banner'))).toContain('加入不了')

    fireEvent.click(container.querySelector('#joinSkill') as HTMLButtonElement)
    await settle()
    expect(container.querySelector('.page > .banner')).toBeNull()
    expect(text(container.querySelector('#removeSkill'))).toBe('移除')
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

  it('says which username is missing when a share is refused', async () => {
    const { container } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
      () =>
        json(
          400,
          errorBody('invalid_request', 'refused', [
            { field: 'handle', issue: 'no_such_user' },
          ]),
        ),
    ])

    fireEvent.change(container.querySelector('.share-row input') as HTMLInputElement, {
      target: { value: 'nobody' },
    })
    fireEvent.click(container.querySelector('.share-row .button') as HTMLButtonElement)
    await settle()

    expect(text(collaboratorBanner(container))).toBe('没有这个用户名，检查一下拼写。')
  })

  it('says that sharing with yourself is unnecessary', async () => {
    const { container } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
      () =>
        json(
          400,
          errorBody('invalid_request', 'refused', [
            { field: 'handle', issue: 'already_yours' },
          ]),
        ),
    ])

    fireEvent.change(container.querySelector('.share-row input') as HTMLInputElement, {
      target: { value: 'demo-user' },
    })
    fireEvent.click(container.querySelector('.share-row .button') as HTMLButtonElement)
    await settle()

    expect(text(collaboratorBanner(container))).toBe(
      '不用共享给自己——这个 skill 本来就在你名下。',
    )
  })

  it('takes a failed share off the banner when a later share succeeds', async () => {
    const { container } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
      () =>
        json(
          400,
          errorBody('invalid_request', 'refused', [
            { field: 'handle', issue: 'no_such_user' },
          ]),
        ),
      () => json(201, { handle: 'newbie', role: 'invoke' }),
      () => json(200, { ...GRANTS, total: 2 }),
    ])

    const input = container.querySelector('.share-row input') as HTMLInputElement
    const button = container.querySelector('.share-row .button') as HTMLButtonElement

    fireEvent.change(input, { target: { value: 'nobody' } })
    fireEvent.click(button)
    await settle()
    expect(text(collaboratorBanner(container))).toBe('没有这个用户名，检查一下拼写。')

    fireEvent.change(input, { target: { value: 'newbie' } })
    fireEvent.click(button)
    await settle()
    expect(collaboratorBanner(container)).toBeNull()
  })

  it('clears the old banner as soon as the next share starts', async () => {
    let release: (() => void) | undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    const { container } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
      () =>
        json(
          400,
          errorBody('invalid_request', 'refused', [
            { field: 'handle', issue: 'no_such_user' },
          ]),
        ),
      async () => {
        await held
        return json(201, { handle: 'newbie', role: 'invoke' })
      },
      () => json(200, { ...GRANTS, total: 2 }),
    ])

    const input = container.querySelector('.share-row input') as HTMLInputElement
    const button = container.querySelector('.share-row .button') as HTMLButtonElement

    fireEvent.change(input, { target: { value: 'nobody' } })
    fireEvent.click(button)
    await settle()
    expect(collaboratorBanner(container)).not.toBeNull()

    fireEvent.change(input, { target: { value: 'newbie' } })
    fireEvent.click(button)
    await flush()
    // The request is still in flight; the old failure is already gone.
    expect(button.disabled).toBe(true)
    expect(collaboratorBanner(container)).toBeNull()

    release?.()
    await settle()
    expect(collaboratorBanner(container)).toBeNull()
  })

  it('clears a failed read when the search re-reads the list', async () => {
    const { container } = await showSettings(SKILL, 'collaborators', [
      () => json(500, errorBody('internal_error', 'boom')),
      () => json(200, REQUESTS),
      () => json(200, GRANTS),
    ])

    expect(collaboratorBanner(container)).not.toBeNull()

    fireEvent.change(container.querySelector('.member-search input') as HTMLInputElement, {
      target: { value: 'yu' },
    })
    await new Promise((resolve) => setTimeout(resolve, 250))
    await settle()

    expect(collaboratorBanner(container)).toBeNull()
  })

  it('keeps a failed share when an unrelated read succeeds', async () => {
    const { container } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
      () =>
        json(
          400,
          errorBody('invalid_request', 'refused', [
            { field: 'handle', issue: 'no_such_user' },
          ]),
        ),
      () => json(200, GRANTS),
    ])

    fireEvent.change(container.querySelector('.share-row input') as HTMLInputElement, {
      target: { value: 'nobody' },
    })
    fireEvent.click(container.querySelector('.share-row .button') as HTMLButtonElement)
    await settle()
    expect(text(collaboratorBanner(container))).toBe('没有这个用户名，检查一下拼写。')

    // The search box re-reads the list independently of the share. Its success says nothing about
    // the share that failed, so it may not take that sentence away.
    fireEvent.change(container.querySelector('.member-search input') as HTMLInputElement, {
      target: { value: 'yu' },
    })
    await new Promise((resolve) => setTimeout(resolve, 250))
    await settle()

    expect(text(collaboratorBanner(container))).toBe('没有这个用户名，检查一下拼写。')
  })

  it('keeps a failed share when a read failed while it was in flight', async () => {
    let releaseRead: (() => void) | undefined
    let releaseShare: (() => void) | undefined
    const readHeld = new Promise<void>((resolve) => {
      releaseRead = resolve
    })
    const shareHeld = new Promise<void>((resolve) => {
      releaseShare = resolve
    })
    const { container } = await showSettings(SKILL, 'collaborators', [
      async () => {
        await readHeld
        return json(500, errorBody('internal_error', 'boom'))
      },
      () => json(200, REQUESTS),
      async () => {
        await shareHeld
        return json(
          400,
          errorBody('invalid_request', 'refused', [
            { field: 'handle', issue: 'no_such_user' },
          ]),
        )
      },
      () => json(200, GRANTS),
    ])

    const input = container.querySelector('.share-row input') as HTMLInputElement
    const button = container.querySelector('.share-row .button') as HTMLButtonElement
    fireEvent.change(input, { target: { value: 'nobody' } })
    fireEvent.click(button)
    await flush()

    // The list read lands after the share started. Its failure must not make the share's failure
    // clearable when a later read succeeds.
    releaseRead?.()
    await flush()
    releaseShare?.()
    await settle()
    expect(text(collaboratorBanner(container))).toBe('没有这个用户名，检查一下拼写。')

    fireEvent.change(container.querySelector('.member-search input') as HTMLInputElement, {
      target: { value: 'yu' },
    })
    await new Promise((resolve) => setTimeout(resolve, 250))
    await settle()

    expect(text(collaboratorBanner(container))).toBe('没有这个用户名，检查一下拼写。')
  })

  it('does not put a handle detail under a name the person has since changed', async () => {
    let release: (() => void) | undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    const { container } = await showSettings(SKILL, 'collaborators', [
      () => json(200, GRANTS),
      () => json(200, REQUESTS),
      async () => {
        await held
        return json(
          400,
          errorBody('invalid_request', 'refused', [
            { field: 'handle', issue: 'no_such_user' },
          ]),
        )
      },
    ])

    const input = container.querySelector('.share-row input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'nobody' } })
    fireEvent.click(container.querySelector('.share-row .button') as HTMLButtonElement)
    await flush()
    fireEvent.change(input, { target: { value: 'someone-else' } })
    release?.()
    await settle()

    expect(text(collaboratorBanner(container))).toBe('提交的内容有问题，请检查后重试。')
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
