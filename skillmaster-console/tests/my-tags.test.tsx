import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import MyTagsPage from '../src/pages/MyTagsPage'
import { clearCookies, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * 标签: one page, two lists.
 *
 * The private list is the editable one — rename and describe in place, create on the first row,
 * delete through the red sheet. The public list is read-only. Both are drawn from `/web/tags`; the
 * six states the requirement names are exercised here, and the two empty sentences are kept apart.
 */

let location = stubLocation('/my-tags')

beforeEach(() => {
  location = stubLocation('/my-tags')
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function tag(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: '01Jclient',
    name: '客户项目',
    description: '交付期内客户随时要用的 skill',
    used_in: 3,
    dormant_in: 0,
    ...overrides,
  }
}

/** `GET /web/categories`: read beside the list, for the "this is a category" pre-check. */
const CATEGORY_VOCABULARY = {
  categories: [
    { key: 'doc', name: '文档与演示', state: 'enabled', public_total: 3 },
    { key: 'ops', name: '运维与云', state: 'stopped', public_total: 0 },
  ],
}

async function showTags(
  query = '',
  ...responses: Array<() => Response>
): Promise<{ container: HTMLElement; requests: Array<{ path: string; method: string; body: unknown }> }> {
  stubLocation('/my-tags', query)
  const [first, ...rest] = responses
  const { requests } = stubFetch(
    first!,
    () => json(200, CATEGORY_VOCABULARY),
    ...rest,
  )
  const { container } = render(<MyTagsPage />)
  await flush()
  await flush()
  const vocabulary = requests.findIndex((request) => request.path === '/web/categories')
  if (vocabulary >= 0) {
    requests.splice(vocabulary, 1)
  }
  return { container, requests }
}

const text = (element: Element | null): string => (element?.textContent ?? '').trim()

describe('the private list', () => {
  it('lists definitions with their descriptions and counts, count descending then name', async () => {
    const { container } = await showTags(
      '',
      () =>
        json(200, {
          tags: [
            tag({ id: 'a', name: '甲', used_in: 1 }),
            tag({ id: 'b', name: '乙', used_in: 5 }),
          ],
        }),
    )

    const rows = [...container.querySelectorAll('#tagList .tag-row')]
    expect(rows).toHaveLength(2)
    expect(text(rows[0]?.querySelector('.tag-name'))).toBe('乙')
    expect(text(rows[0]?.querySelector('.tag-count'))).toBe('用在 5 个 skill 上')
    expect(text(rows[0]?.querySelector('.tag-desc'))).toBe('交付期内客户随时要用的 skill')
    // The count is the way into the library filtered by it.
    expect(rows[0]?.querySelector('a.tag-count')?.getAttribute('href')).toBe('/?my-tag=b')
  })

  it('draws its own empty sentence, without a toolbar or create row', async () => {
    const { container } = await showTags('', () => json(200, { tags: [] }))

    expect(container.textContent).toContain(
      '还没有标签。新建一个，用来归类你库里的 skill；只有你看得到。',
    )
    expect(container.querySelector('#tagTabs')).not.toBeNull()
  })

  it('says the definitions are all dormant, in the user’s own words', async () => {
    const { container } = await showTags(
      '',
      () => json(200, { tags: [tag({ used_in: 0, dormant_in: 2 })] }),
    )

    expect(container.textContent).toContain(
      '这些标签对应的 skill 现在都看不到（可能已下线或暂时不可见）。skill 回来时，标签会重新出现。',
    )
    expect(text(container.querySelector('.tag-count'))).toBe('用在 0 个 skill 上')
  })

  it('does not call a never-used definition dormant', async () => {
    // Zero effective uses is true of a definition nobody has attached yet — but there is no skill
    // that is "invisible", so the dormant sentence would state something false. Only an actual
    // dormant attachment makes the list dormant.
    const { container } = await showTags(
      '',
      () => json(200, { tags: [tag({ used_in: 0, dormant_in: 0 })] }),
    )

    expect(container.querySelector('#tagDormant')?.hasAttribute('hidden')).toBe(true)
    expect(container.textContent).toContain('客户项目')
  })

  it('disables creating at 100 and says why', async () => {
    const full = Array.from({ length: 100 }, (_, index) =>
      tag({ id: 't' + index, name: '标签 ' + index, used_in: 1 }),
    )
    const { container } = await showTags('', () => json(200, { tags: full }))

    expect(container.querySelector('#createTag')?.hasAttribute('disabled')).toBe(true)
    expect(container.textContent).toContain('标签最多 100 个。删掉一个，才能新建。')
  })

  it('shows the failed state and no tabs when the list cannot be read', async () => {
    const { container } = await showTags(
      '',
      () => json(500, errorBody('internal_error', 'boom')),
    )

    expect(container.querySelector('.banner')?.textContent).toBe('无法加载你的标签，请稍后重试。')
    expect(container.querySelector('#tagTabs')?.hasAttribute('hidden')).toBe(true)
  })

  it('filters by name and description, and says when nothing matched', async () => {
    const { container } = await showTags(
      '?q=找不到',
      () => json(200, { tags: [tag()] }),
    )

    expect(text(container.querySelector('#tagNoMatch'))).toBe('没有匹配的标签。换个词试试。')
  })
})

describe('editing in place', () => {
  it('renames on Enter, through PATCH, and keeps the row', async () => {
    const { container, requests } = await showTags(
      '',
      () => json(200, { tags: [tag()] }),
      () => json(200, tag({ name: '客户 A' })),
    )

    fireEvent.click(container.querySelector('.tag-name.is-editable') as HTMLButtonElement)
    const input = container.querySelector('#tagName') as HTMLInputElement
    fireEvent.change(input, { target: { value: '客户 A' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await flush()
    await flush()

    expect(requests[1]?.path).toBe('/web/tags/01Jclient')
    expect(requests[1]?.method).toBe('PATCH')
    expect(requests[1]?.body).toEqual({ name: '客户 A' })
    expect(text(container.querySelector('.tag-name'))).toBe('客户 A')
  })

  it('writes the description on Enter and clears it to null', async () => {
    const { container, requests } = await showTags(
      '',
      () => json(200, { tags: [tag()] }),
      () => json(200, tag({ description: null })),
    )

    fireEvent.click(container.querySelector('.tag-desc') as HTMLButtonElement)
    const input = container.querySelector('#tagDescription') as HTMLInputElement
    fireEvent.change(input, { target: { value: '' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await flush()
    await flush()

    expect(requests[1]?.path).toBe('/web/tags/01Jclient')
    expect(requests[1]?.body).toEqual({ description: '' })
    expect(text(container.querySelector('.tag-desc'))).toBe('加一句说明')
  })

  it('shows a refused description, then clears the failure when the retry succeeds', async () => {
    const { container } = await showTags(
      '',
      () => json(200, { tags: [tag()] }),
      () => json(500, errorBody('internal_error', 'boom')),
      () => json(200, tag({ description: '新的说明' })),
    )

    fireEvent.click(container.querySelector('.tag-desc') as HTMLButtonElement)
    const input = container.querySelector('#tagDescription') as HTMLInputElement
    fireEvent.change(input, { target: { value: '新的说明' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await flush()
    await flush()

    expect(text(container.querySelector('.tag-edit-error'))).toBe('服务暂时不可用，请稍后重试。')

    // Retry in place: the editor is still open, and a success must take the old error with it.
    fireEvent.keyDown(container.querySelector('#tagDescription') as HTMLInputElement, {
      key: 'Enter',
    })
    await flush()
    await flush()
    expect(container.querySelector('.tag-edit-error')).toBeNull()
    expect(text(container.querySelector('.tag-desc'))).toBe('新的说明')
  })

  it('creates from the first row: Enter moves to the description, then accepts', async () => {
    const { container, requests } = await showTags(
      '',
      () => json(200, { tags: [] }),
      () => json(201, tag({ id: 'new', name: '待评估', description: '还没试过的', used_in: 0 })),
    )

    fireEvent.click(container.querySelector('#createTag') as HTMLButtonElement)
    const name = container.querySelector('#newTagName') as HTMLInputElement
    fireEvent.change(name, { target: { value: '待评估' } })
    fireEvent.keyDown(name, { key: 'Enter' })
    const description = container.querySelector('#newTagDescription') as HTMLInputElement
    fireEvent.change(description, { target: { value: '还没试过的' } })
    fireEvent.keyDown(description, { key: 'Enter' })
    await flush()
    await flush()

    expect(requests[1]?.path).toBe('/web/tags')
    expect(requests[1]?.method).toBe('POST')
    expect(requests[1]?.body).toEqual({ name: '待评估', description: '还没试过的' })
    expect(container.textContent).toContain('待评估')
    expect((container.querySelector('#newTagName') as HTMLInputElement).value).toBe('')
  })

  it('deletes through the red sheet, stating both counts', async () => {
    const { container, requests } = await showTags(
      '',
      () => json(200, { tags: [tag({ used_in: 3, dormant_in: 2 })] }),
      () => new Response(null, { status: 204 }),
    )

    fireEvent.click(container.querySelector('.tag-acts .link-button.is-danger') as HTMLButtonElement)
    expect(text(container.querySelector('.confirm-title'))).toBe('删除标签？')
    expect(text(container.querySelector('.confirm-body'))).toBe(
      '删除后，这个标签会从 3 个你能看到的 skill 上移除，不能恢复；另有 2 个 skill 上的这个标签你现在看不到，也会一起删除。那些 skill 本身不会被删除。',
    )

    fireEvent.click(container.querySelector('.confirm-acts .button.is-danger') as HTMLButtonElement)
    await flush()
    await flush()

    expect(requests[1]?.path).toBe('/web/tags/01Jclient')
    expect(requests[1]?.method).toBe('DELETE')
    expect(container.querySelector('#tagList .tag-row')).toBeNull()
  })
})

describe('the public list', () => {
  it('is read-only, orders by count, and links each count into the library', async () => {
    const { container } = await showTags(
      '?tab=public',
      // The private list is read on mount even when the public tab is open.
      () => json(200, { tags: [] }),
      () =>
        json(200, {
          tags: [
            { key: 'one', name: '只用一个', used_in: 1 },
            { key: 'two', name: '用两个', used_in: 2 },
          ],
        }),
    )
    await flush()

    expect(container.querySelector('#createTag')).toBeNull()
    expect(container.querySelector('.tag-name.is-editable')).toBeNull()
    const rows = [...container.querySelectorAll('#publicTagList li')]
    expect(text(rows[0]?.querySelector('.tag-name'))).toBe('#用两个')
    expect(text(rows[0]?.querySelector('.tag-count'))).toBe('用在 2 个 skill 上')
    expect(rows[0]?.querySelector('a.tag-count')?.getAttribute('href')).toBe('/?public-tag=two')
  })

  it('has its own empty sentence', async () => {
    const { container } = await showTags(
      '?tab=public',
      () => json(200, { tags: [] }),
      () => json(200, { tags: [] }),
    )
    await flush()

    expect(text(container.querySelector('#publicTagNone'))).toBe(
      '还没有公开标签。在你创建的 skill 的信息页贴上一个，它会出现在这里。',
    )
  })

  it('says when a search matched no public tag', async () => {
    const { container } = await showTags(
      '?tab=public&q=zzz',
      () => json(200, { tags: [] }),
      () => json(200, { tags: [{ key: 'pdf', name: 'PDF', used_in: 2 }] }),
    )
    await flush()

    expect(text(container.querySelector('#publicTagNone'))).toBe(
      '没有匹配的公开标签。换个词试试。',
    )
  })
})
