import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import SkillsPage from '../src/pages/SkillsPage'
import { clearCookies, errorBody, empty, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * The author's list page, since v5: the two lists, and what happens around them.
 *
 * **Split off `skills.test.ts` while porting.** That file is two halves under one name: this one
 * (the list) and `one skill`'s four sections, which belong to `SkillPage` and therefore to P4 —
 * nothing here needs a page that does not exist yet. The assertions are the ones the Vue file made,
 * unchanged; the other half lands with the page it tests.
 *
 * The protocol is what most of these assert — which address, which method, which body — because
 * everything else is a rendering of what the server said. The rest is about what is *not* drawn: an
 * empty list with a toolbar over it, a row that looks like a link and opens nothing.
 */

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

async function showSkills(
  listResponse: () => Response,
  url = '/',
): Promise<ReturnType<typeof render> & { requests: Array<{ path: string; method: string }> }> {
  const [path, query] = url.split('?')
  location = stubLocation(path ?? '/', query === undefined ? '' : `?${query}`)
  const { requests } = stubFetch(listResponse)
  const view = render(<SkillsPage />)
  await flush()
  return { ...view, requests }
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

const text = (element: Element | null): string => (element?.textContent ?? '').trim()

describe('the skills list', () => {
  it('draws a row as a name, its live state, and when it last changed', async () => {
    const { container } = await showSkills(() => json(200, { skills: [row()] }))

    expect(text(container.querySelector('a.skill-name'))).toBe('pdf-tools')
    expect(container.querySelector('a.skill-name')?.getAttribute('href')).toBe(
      '/skills/demo-user/pdf-tools',
    )
    expect(text(container.querySelector('.chip'))).toBe('已上线 @1.0.0')
    expect(text(container.querySelector('.skill-updated'))).toContain('更新于')
    // No 调用次数: the demo draws one because its fixtures carry call counts, and the product has
    // no such fact.
    expect(container.querySelector('.skill-calls')).toBeNull()
  })

  it('says 未上线 for a skill nothing has been published from', async () => {
    const { container } = await showSkills(() => json(200, { skills: [row({ current: null })] }))

    expect(text(container.querySelector('.chip'))).toBe('未上线')
    expect(container.querySelector('.chip')?.classList.contains('is-plain')).toBe(true)
    // The list no longer counts drafts: a draft is a state of its own row in 版本, not of the skill.
    expect(container.textContent).not.toContain('待上线')
  })

  it('searches the name and the title, and counts what is on screen', async () => {
    const { container } = await showSkills(
      () =>
        json(200, {
          skills: [
            row({ name: 'pdf-tools', title: 'PDF 工具' }),
            row({ name: 'other', title: '别的东西' }),
          ],
        }),
      '/?q=PDF',
    )

    expect(container.querySelectorAll('a.skill-name')).toHaveLength(1)
    expect(text(container.querySelector('.page-head .count'))).toBe('1 个')
  })

  it('sorts by name when asked, through the address', async () => {
    const { container } = await showSkills(
      () =>
        json(200, {
          skills: [row({ name: 'zeta' }), row({ name: 'alpha' })],
        }),
      '/?sort=name',
    )

    expect([...container.querySelectorAll('a.skill-name')].map((link) => text(link))).toEqual([
      'alpha',
      'zeta',
    ])
  })

  it('pages at ten a row, and only draws a pager when there is more than one page', async () => {
    const many = Array.from({ length: 12 }, (_, index) => row({ name: `skill-${index}` }))
    const { container } = await showSkills(() => json(200, { skills: many }), '/')

    expect(container.querySelectorAll('li.skill')).toHaveLength(10)
    expect(text(container.querySelector('.pager'))).toContain('共 12 个')

    fireEvent.click(container.querySelector('.pager-page') as HTMLAnchorElement)

    expect(container.querySelectorAll('li.skill')).toHaveLength(2)
  })

  it('lands a page past the end on the last one, and writes the correction back', async () => {
    const replace = vi.spyOn(window.history, 'replaceState')
    const seven = Array.from({ length: 7 }, (_, index) => row({ name: `skill-${index}` }))

    const { container } = await showSkills(() => json(200, { skills: seven }), '/?p=4')
    await flush()

    // The list that fits on one page is what is drawn …
    expect(container.querySelectorAll('li.skill')).toHaveLength(7)
    // … and an address that claimed page four no longer does. `?p=4` against a list that fits one
    // page leaves `pages` and the requested page unchanged (1 and 4 before the load, 1 and 4 after),
    // so the correction has to be triggered by the list arriving — as Vue's `matched` watcher was.
    expect(replace.mock.calls.map((call) => call[2])).toContain('/')
  })

  it('says nothing matched rather than showing an empty page', async () => {
    const { container } = await showSkills(() => json(200, { skills: [row()] }), '/?q=zzz')

    expect(container.textContent).toContain('没有匹配的 skill。换个词试试，或者清空搜索。')
    expect(container.textContent).not.toContain('还没有提交过 skill')
  })

  it('says how the first skill gets here when there are none', async () => {
    const { container } = await showSkills(() => json(200, { skills: [] }))

    // v9: 新建 skill is a first-class way in, so the empty state names it beside the CLI (design).
    expect(container.textContent).toContain('还没有 skill。新建一个，或运行')
    expect(container.textContent).toContain('skillmaster skill submit')
    expect(container.querySelector('.menu-button')).not.toBeNull()
    // The design draws the count, search and sort only once there is something to count or filter —
    // the empty list is its own state, and 新建 skill ▾ is the only thing on the line.
    expect(container.querySelector('.page-head .count')).toBeNull()
    expect(container.querySelector('.toolbar')).toBeNull()
  })

  it('reads the deleted list on its own request, and restores from it', async () => {
    const dead = row({ deleted_at: '2026-10-07T09:00:00Z', updated_at: null })
    const { requests } = stubFetch(
      () => json(200, { skills: [dead] }),
      () => empty(204),
    )
    stubLocation('/', '?show=deleted')
    const { container } = render(<SkillsPage />)
    await flush()

    expect(requests[0]?.path).toBe('/web/skills?state=deleted')
    // The name is text, not a link: its address opens nothing now.
    expect(container.querySelector('a.skill-name')).toBeNull()
    expect(text(container.querySelector('.chip'))).toBe('已删除')
    expect(text(container.querySelector('.skill-updated'))).toContain('删除于')

    fireEvent.click(container.querySelector('.restore') as HTMLButtonElement)
    await flush()

    expect(requests[1]?.path).toBe('/web/skills/demo-user/pdf-tools/restore')
    expect(requests[1]?.method).toBe('POST')
  })

  it('says the deleted list is empty rather than showing the live one', async () => {
    const { container } = await showSkills(() => json(200, { skills: [] }), '/?show=deleted')

    expect(container.textContent).toContain('没有删掉的 skill。')
  })

  it('sends somebody who is not signed in to the login form, and says where to come back to', async () => {
    await showSkills(NOT_SIGNED_IN)

    expect(location.assign).toHaveBeenCalledWith('/login?return_to=%2F')
  })

  it('says what went wrong rather than pretending the list is empty', async () => {
    const { container } = await showSkills(() =>
      json(500, errorBody('internal_error', 'Something went wrong.')),
    )

    expect(container.querySelector('.banner')).not.toBeNull()
    expect(container.textContent).not.toContain('还没有提交过 skill')
  })
})
