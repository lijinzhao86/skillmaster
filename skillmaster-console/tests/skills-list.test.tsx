import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import SkillsPage from '../src/pages/SkillsPage'
import { loadSession, session } from '../src/store'
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
  /* The session is a module singleton: a test that signs somebody in must not leak into the next. */
  session.reset()
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

/**
 * **A verdict is several ticks away.** Reading the list, then the lookup for an address this page
 * cannot answer, then the decision — each is a promise, so a fixed number of `flush()`es is a test
 * that passes alone and fails in the suite. This waits for the condition instead, with a ceiling.
 */
async function flushUntil(done: () => boolean, ticks = 20): Promise<void> {
  for (let tick = 0; tick < ticks && !done(); tick += 1) {
    await flush()
  }
}

describe('the library', () => {
  it('draws your rung on a row you do not own, and no chip on your own rows', async () => {
    // **The chip says what you can do, not where it came from** (v35). 「我创建的」 and 「no prefix」
    // are the same fact once the address is on the row; the rung is the one that adds something.
    const { container, requests } = await showLibrary(() =>
      json(
        200,
        pageOf([
          card({ name: 'pdf-tools' }),
          card({
            id: 'other',
            name: 'css-token-audit',
            namespace: 'yuhan',
            source: 'shared',
            standing: 'browse',
          }),
        ]),
      ),
    )

    const rows = [...container.querySelectorAll('li.skill')]
    expect(rows[0]?.querySelector('.chip.is-source')).toBeNull()
    expect(text(rows[1]?.querySelector('.chip.is-source'))).toBe('浏览')
  })

  it('leaves the prefix off your own namespace and keeps it for everyone else', async () => {
    // **The rule reads 「no prefix = yours」** (2026-10-31, the user's call). Most rows on this page
    // live in your namespace, so writing it on each of them is one repeated word per row; the ones
    // from somebody else's namespace — a team's included — keep theirs.
    stubFetch(() => json(200, { user_id: '01M3HTG7GCCVBGRPAFFSVSF12W', username: 'demo-user', namespace: 'demo-user' }))
    await loadSession()
    const { container } = await showLibrary(() =>
      json(
        200,
        pageOf([
          card({ name: 'pdf-tools' }),
          card({ id: 'other', name: 'css-token-audit', namespace: 'yuhan' }),
        ]),
      ),
    )

    const rows = [...container.querySelectorAll('a.skill-name')].map((element) => ({
      ns: element.querySelector('.ns')?.textContent ?? null,
      text: element.textContent?.trim(),
    }))
    expect(rows[0]).toEqual({ ns: null, text: 'pdf-tools' })
    expect(rows[1]).toEqual({ ns: 'yuhan /', text: 'yuhan / css-token-audit' })
  })

  it('draws a row as an address, its state, its rung and when it last changed', async () => {
    const { container } = await showLibrary(() => json(200, pageOf([card()])))

    /* **The row opens with the address.** Two same-named skills in different namespaces are told
       apart by this, and by nothing else on the row — the source chip says how you got it, not
       whose namespace it lives in. (No session here, so nothing is known to be "mine" and the
       prefix is drawn; the own-namespace rule is its own test below.) */
    expect(text(container.querySelector('a.skill-name .ns'))).toBe('demo-user /')
    expect(text(container.querySelector('a.skill-name'))).toBe('demo-user / pdf-tools')
    expect(container.querySelector('a.skill-name')?.getAttribute('href')).toBe(
      '/skills/demo-user/pdf-tools',
    )
    // The owner's row draws no rung chip: owning it is already said by the unprefixed name.
    expect(container.querySelector('.chip.is-source')).toBeNull()
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

  it('holds only the controls that can act: one page has the count and the size, no numbers or arrows', async () => {
    // **2026-11-01, the user's call.** The foot stays while the size control can still change
    // something (12 rows at 20 per page is one page), but the numbers and the two arrows would all
    // be dead there — a lone 「1」 and two disabled steps. Same list at the default 10 gives two
    // pages, and then all three come back.
    at('/?per=20')
    const single = await showLibrary(() =>
      json(200, pageOf([card()], { page_size: 20, total_entries: 12, filtered_total: 12 })),
    )

    expect(text(single.container.querySelector('.pager-count'))).toContain('共 12 个')
    expect(single.container.querySelector('.pager-size')).not.toBeNull()
    // Scoped to the pager: `.is-current` is also the 「在用」 tab's class.
    expect(
      single.container.querySelectorAll('#skillPager .pager-page, #skillPager .is-current, #skillPager .is-off, #skillPager .pager-step'),
    ).toHaveLength(0)

    session.reset()
    clearCookies()
    setCookie('XSRF-TOKEN', 'token-one')
    location = stubLocation('/')
    const two = await showLibrary(() =>
      json(200, pageOf([card()], { total_entries: 12, filtered_total: 12 })),
    )

    expect(two.container.querySelectorAll('#skillPager .pager-page, #skillPager .is-current')).toHaveLength(2)
    expect(two.container.querySelectorAll('#skillPager .pager-step, #skillPager .is-off')).toHaveLength(2)
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

/**
 * v35: the preview beside the list.
 *
 * The pane is a shortcut to the same four facts the skill page's 信息 tab carries — so what these
 * assert is that it exists, that it reads the row it belongs to, and that the row's own link is
 * untouched. The layout (side by side, then floating over the list) is the design file's business,
 * checked there against a real browser; jsdom has no layout.
 */
describe('the preview beside the list', () => {
  it('opens from a row, and the name still goes to the skill page', async () => {
    const replace = vi.spyOn(window.history, 'replaceState')
    const { container } = await showLibrary(() => json(200, pageOf([card()])))

    fireEvent.click(container.querySelector('.skill-title') as Element)
    await flush()

    expect(container.querySelector('#skillPreview')).not.toBeNull()
    expect(text(container.querySelector('#skillPreview .preview-name'))).toBe(
      'demo-user / pdf-tools',
    )
    expect(container.querySelector('a.skill-name')?.getAttribute('href')).toBe(
      '/skills/demo-user/pdf-tools',
    )
    const written = replace.mock.calls.map((call) => String(call[2])).join(' ')
    expect(written).toContain('sel=')
    expect(written).toContain('pdf-tools')
  })

  it('does not open when the name itself is clicked — that is the link out', async () => {
    const { container } = await showLibrary(() => json(200, pageOf([card()])))

    fireEvent.click(container.querySelector('a.skill-name') as Element)
    await flush()

    expect(container.querySelector('#skillPreview')).toBeNull()
  })

  it('draws the description it was opened on, and both ways into the skill page sit on the title', async () => {
    at('/?sel=demo-user/pdf-tools')
    const { container } = await showLibrary(() =>
      json(200, pageOf([card({ description: '把 PDF 拆页、合并与压缩。' })])),
    )

    expect(text(container.querySelector('.preview-text'))).toBe('把 PDF 拆页、合并与压缩。')
    // The head carries both doors: the name (a link) and the arrow-out button beside it.
    expect(container.querySelector('a.preview-name')?.getAttribute('href')).toBe(
      '/skills/demo-user/pdf-tools',
    )
    expect(container.querySelector('a.preview-open-icon')?.getAttribute('href')).toBe(
      '/skills/demo-user/pdf-tools',
    )
    // The footer row is gone — it was pushing the only exit to the very bottom of the pane.
    expect(container.querySelector('a.preview-open')).toBeNull()
    // The row it belongs to is marked, so the pane is never about an anonymous row.
    expect(container.querySelectorAll('li.skill.is-selected')).toHaveLength(1)
  })

  it('gives the category a row of its own, my tags another, and no longer repeats the state or the source', async () => {
    at('/?sel=demo-user/pdf-tools')
    const { container } = await showLibrary(() =>
      json(
        200,
        pageOf([
          card({
            state: 'live',
            version: '1.2.3',
            source: 'created',
            category: { key: 'doc', name: '文档与演示' },
            my_tags: [
              { id: 't1', name: '客户项目' },
              { id: 't2', name: '这周要用' },
            ],
          }),
        ]),
      ),
    )

    const pane = container.querySelector('#skillPreview') as Element
    // Both sit on the row to the left, which stays highlighted beside the pane.
    expect(pane.querySelector('.chip.is-live, .chip.is-source')).toBeNull()
    // The category is the shelf (exactly one); my tags are words (a run). Two rows, so the two
    // never read as one pile of pills.
    expect(text(pane.querySelector('#previewCategory .chip.is-plain'))).toBe('文档与演示')
    expect([...pane.querySelectorAll('.preview-row .chip.is-mine')].map(text)).toEqual([
      '客户项目',
      '这周要用',
    ])
  })

  it('explains nothing when a skill has neither a category nor tags', async () => {
    at('/?sel=demo-user/pdf-tools')
    const { container } = await showLibrary(() => json(200, pageOf([card()])))

    expect(container.querySelector('#skillPreview .preview-row')).toBeNull()
  })

  it('does not link a deleted skill\'s title — that address opens nothing now', async () => {
    // The row already draws a deleted name as text for this reason; the pane follows it (audit,
    // 2026-10-31). The arrow is the same way in, so it goes too.
    at('/?show=deleted&sel=demo-user/pdf-tools')
    const { container } = await showLibrary(() =>
      json(
        200,
        pageOf([
          card({ state: 'deleted', deleted_at: '2026-10-07T09:00:00Z', updated_at: null }),
        ]),
      ),
    )

    const pane = container.querySelector('#skillPreview') as Element
    expect(pane.querySelector('.preview-name')?.tagName).toBe('SPAN')
    expect(pane.querySelector('.preview-open-icon')).toBeNull()
  })

  it('closes from its own button and takes `sel` out of the address', async () => {
    at('/?sel=demo-user/pdf-tools')
    const replace = vi.spyOn(window.history, 'replaceState')
    const { container } = await showLibrary(() => json(200, pageOf([card()])))

    fireEvent.click(container.querySelector('.preview-close') as Element)
    await flush()

    expect(container.querySelector('#skillPreview')).toBeNull()
    const written = replace.mock.calls.map((call) => String(call[2])).join(' ')
    expect(written).not.toContain('sel=')
  })

  it('opens from the keyboard too — Enter on the row', async () => {
    const { container } = await showLibrary(() => json(200, pageOf([card()])))

    fireEvent.keyDown(container.querySelector('li.skill') as Element, { key: 'Enter' })
    await flush()

    expect(container.querySelector('#skillPreview')).not.toBeNull()
  })

  it('keeps the preview open when the page changes', async () => {
    // **Paging does not close the pane** (2026-11-01, the user's call): it previews this skill, not
    // this page. The card is remembered under the filters it was opened with, so a page that no
    // longer contains the row still shows it — while a filter that drops the row still closes it.
    at('/?sel=demo-user/pdf-tools')
    const { container } = await showLibrary(
      () => json(200, pageOf([card()], { filtered_total: 23 })),
      () => json(200, {}),
      () => json(200, {}),
      () => json(200, {}),
      () => json(200, pageOf([card({ name: 'other-skill', id: '01Jother' })], { page: 2, filtered_total: 23 })),
    )
    await flush()

    const next = [...container.querySelectorAll('.pager a')].find((link) => text(link) === '下一页')
    fireEvent.click(next as Element)
    await flush()
    await flush()

    expect(container.querySelector('#skillPreview')).not.toBeNull()
    expect(text(container.querySelector('#skillPreview .preview-name'))).toContain('pdf-tools')
  })

  it('drops a selection that is not in the list rather than showing an empty pane', async () => {
    at('/?sel=demo-user/no-such-skill')
    const replace = vi.spyOn(window.history, 'replaceState')
    const { container } = await showLibrary(
      () => json(200, pageOf([card()])),
      // **The lookup, before the verdict**: an address this page cannot answer is asked about first
      // (2026-11-01) — the list is read narrowed by that name, and an empty answer is what makes
      // "no such skill" different from "on another page".
      () => json(200, pageOf([], { total_entries: 23, filtered_total: 23 })),
    )
    await flushUntil(() => container.querySelector('#skillPreview') === null)

    expect(container.querySelector('#skillPreview')).toBeNull()
    const written = replace.mock.calls.map((call) => String(call[2])).join(' ')
    expect(written).not.toContain('sel=')
  })

  it('opens an address that names a skill on another page', async () => {
    // **`sel` is a promise about the filtered list, not about the page** (2026-11-01, the user's
    // call). `?p=2&sel=<a skill on page 1>` is what a person gets by paging with the pane open and
    // then reloading, or by being sent the address — and it used to close the pane.
    at('/?p=2&sel=demo-user/pdf-tools')
    const replace = vi.spyOn(window.history, 'replaceState')
    const { container, requests } = await showLibrary(
      () => json(200, pageOf([card({ name: 'other-skill', id: '01Jother' })], { page: 2, filtered_total: 23 })),
      () => json(200, {}),
      () => json(200, {}),
      () => json(200, {}),
      () => json(200, pageOf([card()], { total_entries: 23, filtered_total: 23 })),
    )
    await flush()
    await flush()

    expect(text(container.querySelector('#skillPreview .preview-name'))).toContain('pdf-tools')
    // The lookup asks the same list for that one name, never for a page number.
    expect(requests.some((request) => request.path.includes('q=pdf-tools'))).toBe(true)
    expect(replace.mock.calls.map((call) => String(call[2])).join(' ')).not.toContain('sel=')
  })

  it('keeps the preview while a page past the end is corrected', async () => {
    // **An empty out-of-range answer is not evidence** (2026-11-01): the correction to the last page
    // is one request away, and dropping on the empty answer lost a preview that was still true.
    at('/?p=9&sel=demo-user/pdf-tools')
    const { container } = await showLibrary(
      () => json(200, pageOf([], { page: 9, total_entries: 23, filtered_total: 23 })),
      () => json(200, pageOf([card()], { page: 3, total_entries: 23, filtered_total: 23 })),
      () => json(200, {}),
      () => json(200, {}),
      () => json(200, {}),
    )
    await flushUntil(() => container.querySelector('#skillPreview .preview-name') !== null)

    expect(text(container.querySelector('#skillPreview .preview-name'))).toContain('pdf-tools')
  })

  it('closes the preview when a filter drops the row it was opened on', async () => {
    // **The filter wins, and it has to win against a stale frame** (2026-11-01). Between the
    // keystroke and the answer the old rows are still on screen; reading them as if they answered
    // the new filters kept the pane — and the `sel` in the address — alive after the row was gone.
    at('/?sel=demo-user/pdf-tools')
    const replace = vi.spyOn(window.history, 'replaceState')
    const { container, requests } = await showLibrary(
      () => json(200, pageOf([card()], { filtered_total: 23 })),
      () => json(200, {}),
      () => json(200, {}),
      () => json(200, {}),
      () => json(200, pageOf([card({ name: 'other-skill', id: '01Jother' })], { filtered_total: 23 })),
      () => json(200, pageOf([], { total_entries: 23, filtered_total: 23 })),
    )
    await flush()
    expect(container.querySelector('#skillPreview')).not.toBeNull()

    fireEvent.change(container.querySelector('#skillQuery') as Element, { target: { value: 'zzz' } })
    // **Wait for the verdict, not for the pane.** The list unmounts while a page is read, so "the
    // pane is gone" is true for a moment in the middle of every filter change; the write is the
    // thing that only happens when the answer says the row is not in the list.
    await flushUntil(() => replace.mock.calls.length >= 2)

    expect(container.querySelector('#skillPreview')).toBeNull()
    // The typing write keeps `sel` (the pane is still open at that moment); the write that follows
    // the answer is the one that must have dropped it.
    expect(String(replace.mock.calls.at(-1)?.[2] ?? '')).not.toContain('sel=')
  })
})
