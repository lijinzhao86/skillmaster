import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import SkillsPage from '../src/pages/SkillsPage'
import { clearCookies, errorBody, empty, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * 我的 skill since v14: the complete **library**, read from `/web/library`.
 *
 * The filtering, sorting and paging are the server's (`q` / `sort` / `p` / the three tag
 * parameters), so these assert the address that was asked for and the rows that came back — not a
 * local re-implementation. The empty states are the three counts the response carries.
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

/** One row of `/web/library`, in the card shape the two scopes share (technical-design.md §响应形状). */
function card(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const name = (overrides.name as string | undefined) ?? 'pdf-tools'
  return {
    id: '01J' + name,
    namespace: 'demo-user',
    name,
    description: '把 PDF 拆页、合并与压缩。',
    when_to_use: '手里有 PDF 的时候',
    source: 'created',
    state: 'live',
    version: '1.0.0',
    updated_at: '2026-10-06T09:00:00Z',
    deleted_at: null,
    category: null,
    public_tags: [],
    my_tags: [],
    ...overrides,
  }
}

function pageOf(skills: Array<Record<string, unknown>>, overrides: Record<string, unknown> = {}) {
  return {
    skills,
    page: 1,
    page_size: 10,
    total_entries: skills.length,
    effective_entries: skills.length,
    filtered_total: skills.length,
    ...overrides,
  }
}

async function showLibrary(
  ...responses: Array<() => Response>
): Promise<ReturnType<typeof render> & { requests: Array<{ path: string; method: string }> }> {
  const { requests } = stubFetch(...responses)
  const view = render(<SkillsPage />)
  await flush()
  await flush()
  return { ...view, requests }
}

function at(url: string): void {
  const [path, query] = url.split('?')
  location = stubLocation(path ?? '/', query === undefined ? '' : `?${query}`)
}

const text = (element: Element | null): string => (element?.textContent ?? '').trim()

describe('the library', () => {
  it('draws a row as a name, its source, its state, and when it last changed', async () => {
    const { container } = await showLibrary(() => json(200, pageOf([card()])))

    expect(text(container.querySelector('a.skill-name'))).toBe('pdf-tools')
    expect(container.querySelector('a.skill-name')?.getAttribute('href')).toBe(
      '/skills/demo-user/pdf-tools',
    )
    expect(text(container.querySelector('.chip.is-source'))).toBe('我创建的')
    expect(text(container.querySelector('.chip.is-live'))).toBe('已上线 @1.0.0')
    expect(text(container.querySelector('.skill-updated'))).toContain('更新于')
    // No call count: the product has no usage signal, and v14 removed the column.
    expect(container.querySelector('.skill-calls')).toBeNull()
  })

  it('shows 草稿 for a skill nothing has been published from, and 已下线 for the whole skill', async () => {
    const draft = await showLibrary(() =>
      json(200, pageOf([card({ state: 'draft', version: null })])),
    )
    expect(text(draft.container.querySelector('.chip.is-draft'))).toBe('草稿')

    at('/')
    const offline = await showLibrary(() => json(200, pageOf([card({ state: 'offline' })])))
    expect(text(offline.container.querySelector('.chip.is-offline'))).toBe('已下线')
  })

  it('reads the search term, the sort and the tag filters from the address', async () => {
    at('/?q=pdf&sort=name&tag=doc&my-tag=11&public-tag=pdf')
    const { container, requests } = await showLibrary(
      () => json(200, pageOf([card()])),
      () => json(200, { tags: [{ id: '11', name: '客户项目', description: null, used_in: 2 }] }),
      () => json(200, { tags: [{ key: 'pdf', name: 'PDF', used_in: 3 }] }),
      () =>
        json(200, {
          categories: [{ key: 'doc', name: '文档与演示', state: 'enabled', public_total: 4 }],
        }),
    )
    await flush()

    const path = requests[0]?.path ?? ''
    expect(path).toContain('/web/library?')
    expect(path).toContain('q=pdf')
    expect(path).toContain('sort=name')
    expect(path).toContain('tag=doc')
    expect(path).toContain('my-tag=11')
    expect(path).toContain('public-tag=pdf')
    // **The address carries ids and keys; the chips carry the names.**
    const chips = [...container.querySelectorAll('.filters .filter-chip')].map((chip) => text(chip))
    expect(chips[0]).toContain('文档与演示')
    expect(chips[1]).toContain('客户项目')
    expect(chips[2]).toContain('#PDF')
  })

  it('takes the filters off the address when 已删除 is the list', async () => {
    const replace = vi.spyOn(window.history, 'replaceState')
    at('/?show=deleted&tag=doc&my-tag=11&public-tag=pdf')
    const { requests } = await showLibrary(() =>
      json(200, pageOf([card({ state: 'deleted', deleted_at: '2026-10-07T09:00:00Z' })])),
    )

    // Nothing about tags is asked for, and the address no longer names them.
    expect(requests[0]?.path).toBe('/web/library?show=deleted')
    expect(replace.mock.calls.map((call) => call[2])).toContain('/?show=deleted')
  })
})

describe('the three empty states', () => {
  it('draws the library-empty state with both next steps and no tabs', async () => {
    const { container } = await showLibrary(() => json(200, pageOf([])))

    expect(container.textContent).toContain('还没有 skill。新建一个，或者')
    expect(container.querySelector('#skillEmpty a')?.getAttribute('href')).toBe('/discover')
    expect(container.textContent).toContain('skmr skill submit')
    // No tabs: the library is not empty-yet — it is empty.
    expect(container.querySelector('#listTabs')?.hasAttribute('hidden')).toBe(true)
    // No count, toolbar or filters, but the author's one next step stays.
    expect(container.querySelector('.page-head .count')?.hasAttribute('hidden')).toBe(true)
    expect(container.querySelector('.toolbar')).toBeNull()
    expect(container.querySelector('.menu-button')).not.toBeNull()
  })

  it('says the entries are dormant, not that the library is empty', async () => {
    const { container } = await showLibrary(() =>
      json(200, pageOf([], { total_entries: 5, effective_entries: 0, filtered_total: 0 })),
    )

    expect(container.textContent).toContain(
      '这里暂时没有能显示的 skill。它们可能已下线或暂时不可见，恢复后会回来。',
    )
    // The tabs stay: there is a library, and the deleted list may still be reachable.
    expect(container.querySelector('#listTabs')).not.toBeNull()
    expect(container.querySelector('.toolbar')).toBeNull()
  })

  it('says nothing matched rather than calling a filtered list empty', async () => {
    at('/?q=zzz')
    const { container } = await showLibrary(() =>
      json(200, pageOf([], { total_entries: 5, effective_entries: 5, filtered_total: 0 })),
    )

    expect(container.textContent).toContain('没有匹配的 skill。换个词试试，或者清空筛选。')
    expect(container.querySelector('#skillEmpty')).toBeNull()
  })
})

describe('paging', () => {
  it('asks for the requested page and leaves the address saying which page is shown', async () => {
    at('/?p=2')
    const { container, requests } = await showLibrary(() =>
      json(200, pageOf([card()], { page: 2, total_entries: 12, filtered_total: 12 })),
    )

    expect(requests[0]?.path).toContain('p=2')
    expect(container.querySelectorAll('li.skill')).toHaveLength(1)
    expect(text(container.querySelector('.pager'))).toContain('共 12 个')
  })

  it('lands a page past the end on the last one, and writes the correction back', async () => {
    const replace = vi.spyOn(window.history, 'replaceState')
    at('/?p=4')

    const { container } = await showLibrary(
      () => json(200, pageOf([], { page: 4, total_entries: 7, filtered_total: 7 })),
      // The correction reads page one; the last page of seven entries is page one.
      () => json(200, pageOf([card()], { page: 1, total_entries: 7, filtered_total: 7 })),
    )
    await flush()

    expect(container.querySelectorAll('li.skill')).toHaveLength(1)
    expect(replace.mock.calls.map((call) => call[2])).toContain('/')
  })
})

describe('the deleted list', () => {
  it('reads its own list, draws the name as text, and restores from there', async () => {
    at('/?show=deleted')
    const { requests, container } = await showLibrary(
      () =>
        json(
          200,
          pageOf([
            card({ state: 'deleted', deleted_at: '2026-10-07T09:00:00Z', updated_at: null }),
          ]),
        ),
      () => empty(204),
    )

    expect(requests[0]?.path).toBe('/web/library?show=deleted')
    expect(container.querySelector('a.skill-name')).toBeNull()
    expect(text(container.querySelector('.chip'))).toBe('已删除')
    expect(text(container.querySelector('.skill-updated'))).toContain('删除于')

    fireEvent.click(container.querySelector('.restore') as HTMLButtonElement)
    await flush()

    expect(requests[1]?.path).toBe('/web/skills/demo-user/pdf-tools/restore')
    expect(requests[1]?.method).toBe('POST')
  })

  it('says the deleted list is empty without calling the library empty', async () => {
    at('/?show=deleted')
    const { container } = await showLibrary(() =>
      json(200, pageOf([], { total_entries: 5, effective_entries: 5, filtered_total: 0 })),
    )

    expect(container.textContent).toContain('没有删掉的 skill。')
    expect(container.querySelector('#listTabs')).not.toBeNull()
  })
})

describe('failures', () => {
  it('sends somebody who is not signed in to the login form, and says where to come back to', async () => {
    await showLibrary(NOT_SIGNED_IN)

    expect(location.assign).toHaveBeenCalledWith('/login?return_to=%2F')
  })

  it('says what went wrong rather than pretending the list is empty', async () => {
    const { container } = await showLibrary(() =>
      json(500, errorBody('internal_error', 'Something went wrong.')),
    )

    expect(container.querySelector('.banner')).not.toBeNull()
    expect(container.textContent).not.toContain('还没有 skill')
  })
})
