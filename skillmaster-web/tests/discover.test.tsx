import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import DiscoverPage from '../src/pages/DiscoverPage'
import { clearCookies, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * 发现: the opening search + recommendation stream, and the results a search or a category asks for.
 *
 * The stream is the server's (`/web/discover/recommended`, six at a time with a cursor); these
 * assert what was asked for, what was drawn, and the three join states. The browser's
 * `IntersectionObserver` is replaced with one a test can fire, which is how the scroll-to-load rule
 * is exercised without a viewport.
 */

type Observer = { callback: IntersectionObserverCallback; disconnected: boolean }
let observers: Observer[] = []

class FakeObserver {
  private entry: Observer
  constructor(callback: IntersectionObserverCallback) {
    this.entry = { callback, disconnected: false }
    observers.push(this.entry)
  }
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {
    this.entry.disconnected = true
  }
  takeRecords(): IntersectionObserverEntry[] {
    return []
  }
}

/** Fire the newest live observer as if the sentinel scrolled into view. */
async function scrollToBottom(): Promise<void> {
  const live = [...observers].reverse().find((entry) => !entry.disconnected)
  live?.callback(
    [{ isIntersecting: true } as IntersectionObserverEntry],
    {} as IntersectionObserver,
  )
  await flush()
  await flush()
}

/** Fire the newest live observer as if the sentinel left the viewport. */
async function scrollAway(): Promise<void> {
  const live = [...observers].reverse().find((entry) => !entry.disconnected)
  live?.callback(
    [{ isIntersecting: false } as IntersectionObserverEntry],
    {} as IntersectionObserver,
  )
  await flush()
}

beforeEach(() => {
  observers = []
  vi.stubGlobal('IntersectionObserver', FakeObserver)
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function card(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const name = (overrides.name as string | undefined) ?? 'pdf-splitter'
  return {
    id: '01J' + name,
    namespace: 'yuhan',
    name,
    description: '按页把一份 PDF 切开。',
    when_to_use: '只需要其中几段的时候',
    source: null,
    state: 'live',
    version: '0.4.0',
    updated_at: '2026-09-21T09:00:00Z',
    deleted_at: null,
    category: { key: 'doc', name: '文档与演示' },
    public_tags: [{ key: 'pdf', name: 'PDF' }],
    my_tags: [],
    ...overrides,
  }
}

/** `GET /web/categories`: every page that draws a category picker reads it. */
const CATEGORY_VOCABULARY = {
  categories: [
    { key: 'doc', name: '文档与演示', state: 'enabled', public_total: 3 },
    { key: 'ops', name: '运维与云', state: 'stopped', public_total: 0 },
  ],
}

async function showDiscover(
  query = '',
  ...responses: Array<() => Response>
): Promise<{ container: HTMLElement; requests: Array<{ path: string; method: string; body: unknown }> }> {
  stubLocation('/discover', query)
  const [first, ...rest] = responses
  /* The vocabulary is read after the first list call (both effects fire on mount), so its response
     sits second. It is spliced out of the record afterwards: these tests are about the list calls. */
  const { requests } = stubFetch(
    first!,
    () => json(200, CATEGORY_VOCABULARY),
    ...rest,
  )
  const { container } = render(<DiscoverPage />)
  await flush()
  await flush()
  const vocabulary = requests.findIndex((request) => request.path === '/web/categories')
  if (vocabulary >= 0) {
    requests.splice(vocabulary, 1)
  }
  return { container, requests }
}

const text = (element: Element | null): string => (element?.textContent ?? '').trim()

describe('the opening screen', () => {
  it('draws the search row, the category picker and a batch of recommendations', async () => {
    const batch = Array.from({ length: 6 }, (_, index) => card({ name: `skill-${index}` }))
    const { container, requests } = await showDiscover(
      '',
      () => json(200, { skills: batch, next_cursor: null }),
    )

    expect(text(container.querySelector('h1'))).toBe('发现')
    expect(container.querySelector('#discoverHeroQuery')).not.toBeNull()
    expect(container.querySelector('.hero-toolbar .filter-select')).not.toBeNull()
    expect(text(container.querySelector('.picks-title'))).toBe('为你推荐')
    expect(container.querySelectorAll('#discoverPicks .result')).toHaveLength(6)
    expect(requests[0]?.path).toBe('/web/discover/recommended?limit=6')
    // The design's ending sentence, not a "load more" button.
    expect(container.textContent).toContain('没有更多了。用上面的搜索或分类继续找。')
    expect(container.querySelector('.picks .button')).toBeNull()
  })

  it('keeps the batches already shown when the next one fails, and retries after the sentinel leaves', async () => {
    const first = Array.from({ length: 6 }, (_, index) => card({ name: `a-${index}` }))
    const next = Array.from({ length: 6 }, (_, index) => card({ name: `b-${index}` }))
    const { container, requests } = await showDiscover(
      '',
      () => json(200, { skills: first, next_cursor: 'c1' }),
      () => json(500, errorBody('internal_error', 'boom')),
      () => json(200, { skills: next, next_cursor: null }),
    )
    const batches = (): number =>
      requests.filter((request) => request.path.startsWith('/web/discover/recommended')).length

    await scrollToBottom()
    expect(container.querySelectorAll('#discoverPicks .result')).toHaveLength(6)
    expect(container.textContent).toContain('无法加载公开的 skill，请稍后重试。')
    expect(batches()).toBe(2)

    // The effect rebuilds its observer after the failed batch; the sentinel is still in view, and
    // that must NOT become another request — otherwise a failure retries forever.
    await scrollToBottom()
    expect(batches()).toBe(2)

    // Leaving the sentinel arms exactly one retry; coming back runs it.
    await scrollAway()
    await scrollToBottom()
    expect(batches()).toBe(3)
    expect(container.querySelectorAll('#discoverPicks .result')).toHaveLength(12)
    expect(container.textContent).not.toContain('无法加载公开的 skill，请稍后重试。')
  })

  it('says there is nothing to recommend rather than leaving the stream blank', async () => {
    const { container } = await showDiscover('', () => json(200, { skills: [], next_cursor: null }))

    expect(text(container.querySelector('#discoverPicksNone'))).toBe(
      '没有可推荐的了。用上面的搜索或分类继续找。',
    )
  })

  it('fills the first screen by asking for the next batch when the sentinel is already visible', async () => {
    const first = Array.from({ length: 6 }, (_, index) => card({ name: `a-${index}` }))
    const next = Array.from({ length: 6 }, (_, index) => card({ name: `b-${index}` }))
    const { container, requests } = await showDiscover(
      '',
      () => json(200, { skills: first, next_cursor: 'c1' }),
      () => json(200, { skills: next, next_cursor: null }),
    )

    await scrollToBottom()

    expect(requests[1]?.path).toBe('/web/discover/recommended?limit=6&cursor=c1')
    expect(container.querySelectorAll('#discoverPicks .result')).toHaveLength(12)
  })

  it('shows an existing library entry read-only, with its source', async () => {
    const { container } = await showDiscover(
      '',
      () =>
        json(200, {
          skills: [card({ name: 'mine', source: 'created' })],
          next_cursor: null,
        }),
    )

    expect(text(container.querySelector('.result-in'))).toBe('已在库　·　我创建的')
    expect(container.querySelector('.result-join')).toBeNull()
    expect(container.querySelector('.result-inlibrary')).not.toBeNull()
  })
})

describe('joining', () => {
  it('posts the join, keeps the card in place, and reads as 我加入的', async () => {
    const { container, requests } = await showDiscover(
      '',
      () => json(200, { skills: [card()], next_cursor: null }),
      () => json(200, {}),
    )

    fireEvent.click(container.querySelector('.result-join') as HTMLButtonElement)
    await flush()
    await flush()

    expect(requests[1]?.path).toBe('/web/skills/yuhan/pdf-splitter/library')
    expect(requests[1]?.method).toBe('POST')
    expect(container.querySelectorAll('#discoverPicks .result')).toHaveLength(1)
    expect(text(container.querySelector('.result-in'))).toBe('已在库　·　我加入的')
    expect(container.querySelector('.result-join')?.getAttribute('data-state')).toBe('joined')
  })

  it('shows a refused removal on the card instead of leaving it looking joined', async () => {
    const { container, requests } = await showDiscover(
      '',
      () => json(200, { skills: [card()], next_cursor: null }),
      () => json(200, { changed: true }),
      () => json(500, errorBody('library_entry_dormant', 'boom')),
    )

    fireEvent.click(container.querySelector('.result-join') as HTMLButtonElement)
    await flush()
    await flush()
    expect(text(container.querySelector('.result-in'))).toBe('已在库　·　我加入的')

    // The filled bookmark takes the join back; this one is refused.
    fireEvent.click(container.querySelector('.result-join') as HTMLButtonElement)
    await flush()
    await flush()
    expect(container.querySelector('.result-error')?.textContent)
      .toBe('这个 skill 的库条目现在不生效（可能已下线或暂时不可见），恢复可见后再试。')
    expect(text(container.querySelector('.result-act .button.is-quiet'))).toBe('重试')
    expect(requests.filter((request) => request.method === 'DELETE')).toHaveLength(1)
  })

  it('shows the failure in place and retries from there', async () => {
    const { container, requests } = await showDiscover(
      '',
      () => json(200, { skills: [card()], next_cursor: null }),
      () => json(500, errorBody('internal_error', 'boom')),
      () => json(200, {}),
    )

    fireEvent.click(container.querySelector('.result-join') as HTMLButtonElement)
    await flush()
    await flush()
    // The card repeats the server's reason rather than claiming the network failed.
    expect(container.querySelector('.result-error')?.textContent).toBe('服务暂时不可用，请稍后重试。')
    expect(text(container.querySelector('.result-act .button.is-quiet'))).toBe('重试')

    fireEvent.click(container.querySelector('.result-act .button.is-quiet') as HTMLButtonElement)
    await flush()
    await flush()
    expect(requests.filter((request) => request.path === '/web/skills/yuhan/pdf-splitter/library')).toHaveLength(2)
    expect(text(container.querySelector('.result-in'))).toBe('已在库　·　我加入的')
  })

  it('takes a join back when the filled bookmark is pressed again, without a sheet', async () => {
    const { container, requests } = await showDiscover(
      '',
      () => json(200, { skills: [card()], next_cursor: null }),
      () => json(200, {}),
      () => new Response(null, { status: 204 }),
    )

    fireEvent.click(container.querySelector('.result-join') as HTMLButtonElement)
    await flush()
    await flush()
    fireEvent.click(container.querySelector('.result-join') as HTMLButtonElement)
    await flush()
    await flush()

    const undo = requests.at(-1)
    expect(undo?.path).toBe('/web/skills/yuhan/pdf-splitter/library')
    expect(undo?.method).toBe('DELETE')
    expect(container.querySelector('.confirm')).toBeNull()
    expect(container.querySelector('.result-join')?.getAttribute('data-state')).toBe('idle')
  })
})

describe('searching', () => {
  it('turns the opening row into results, with the term in the address and on the request', async () => {
    const { container, requests } = await showDiscover(
      '',
      () => json(200, { skills: [card()], next_cursor: null }),
      () => json(200, { skills: [card({ name: 'found' })], p: 1, page_size: 6, total: 1 }),
    )

    fireEvent.change(container.querySelector('#discoverHeroQuery') as HTMLInputElement, {
      target: { value: 'pdf' },
    })
    fireEvent.submit(container.querySelector('.hero-toolbar') as HTMLFormElement)
    await flush()
    await flush()

    expect(requests[1]?.path).toBe('/web/discover?q=pdf')
    expect(container.querySelector('.hero-toolbar')).toBeNull()
    expect(container.querySelector('#discoverQuery')).not.toBeNull()
    expect(container.querySelectorAll('#discoverResults .result')).toHaveLength(1)
  })

  it('lets a category choose the results, and says so on the request', async () => {
    const { container, requests } = await showDiscover(
      '',
      () => json(200, { skills: [card()], next_cursor: null }),
      () => json(200, { skills: [card()], p: 1, page_size: 6, total: 1 }),
    )

    fireEvent.click(container.querySelector('.hero-toolbar .menu-trigger') as HTMLButtonElement)
    // The picker's words come from `/web/categories`. A stopped word still filters: it only
    // prevents new assignments, not finding skills already on that shelf.
    expect(
      (container.querySelector('.menu-item[data-value="ops"]') as HTMLButtonElement).disabled,
    ).toBe(false)
    fireEvent.click(container.querySelector('.menu-item[data-value="doc"]') as HTMLButtonElement)
    await flush()
    await flush()

    expect(requests[1]?.path).toBe('/web/discover?tag=doc')
  })

  it('moves to the next page through p, six at a time', async () => {
    const { container, requests } = await showDiscover(
      '?q=pdf',
      () => json(200, { skills: [card()], p: 1, page_size: 6, total: 8 }),
      () => json(200, { skills: [card({ name: 'second' })], p: 2, page_size: 6, total: 8 }),
    )

    expect(requests[0]?.path).toBe('/web/discover?q=pdf')
    // The total is the server's: the header count and the numbered pager both read it.
    expect(text(container.querySelector('.count'))).toBe('8 个结果')
    expect(text(container.querySelector('.pager-count'))).toBe('共 8 个，第 1–6 个')
    expect(
      [...container.querySelectorAll('.pager-page')].map((link) => text(link)),
    ).toEqual(['2'])
    const next = [...container.querySelectorAll('.pager-step')].find(
      (link) => text(link) === '下一页',
    ) as HTMLAnchorElement | undefined
    fireEvent.click(next as HTMLAnchorElement)
    await flush()
    await flush()

    expect(requests[1]?.path).toBe('/web/discover?q=pdf&p=2')
    expect(text(container.querySelector('a.result-name'))).toContain('second')
  })

  it('says nothing matched rather than showing an empty grid', async () => {
    const { container } = await showDiscover(
      '?q=zzz',
      () => json(200, { skills: [], p: 1, page_size: 6, total: 0 }),
    )

    expect(text(container.querySelector('#discoverNone'))).toBe(
      '没有找到匹配的 skill。换个词，或者换一个分类。',
    )
  })
})

describe('failures', () => {
  it('says the public list could not be read', async () => {
    const { container } = await showDiscover(
      '',
      () => json(500, errorBody('internal_error', 'boom')),
    )

    expect(container.querySelector('.banner')?.textContent).toBe(
      '无法加载公开的 skill，请稍后重试。',
    )
  })
})
