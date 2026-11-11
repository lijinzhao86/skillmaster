import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import UsagePage from '../src/pages/UsagePage'
import { clearCookies, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * 使用记录（v58）：明细的一行一次交互、行内展开、与统计的四张卡。
 *
 * The page reads two endpoints, one per tab; these assert which one was asked for, what was drawn,
 * and the interaction the design fixes: the whole row toggles, the panel's own mousedown does not
 * close it (so text can be selected), and Escape closes it.
 */

const DETAIL = {
  items: [
    {
      kind: 'search',
      id: 42,
      at: '2026-11-10T01:38:00Z',
      query: 'pdf 合并',
      total: 12,
      returned: 2,
      results: [
        { skill: 'yuhan/pdf-splitter', rank: 1 },
        { skill: 'dev/pdf-tools', rank: 2 },
      ],
      invocations: [
        { skill: 'dev/pdf-tools', version: '1.2.3', at: '2026-11-10T01:41:00Z', rank: 2 },
      ],
    },
    {
      kind: 'direct',
      id: 41,
      at: '2026-11-09T00:55:00Z',
      skill: 'lijinzhao/code-dev-sop',
      version: '1.1.2',
    },
  ],
  total: 2,
  page: 1,
  page_size: 10,
  window_days: 90,
  has_any: true,
}

const STATS = {
  window_days: 90,
  calls: 12,
  searches: 8,
  converted: 6,
  missed: 2,
  skills: 7,
  latest: '2026-11-10T01:41:00Z',
  top: [
    { skill: 'dev/pdf-tools', calls: 3, last: '2026-11-10T01:41:00Z' },
    { skill: 'lijinzhao/code-dev-sop', calls: 2, last: '2026-11-09T10:00:00Z' },
  ],
}

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function showUsage(query = ''): Promise<{
  container: HTMLElement
  requests: Array<{ path: string; method: string }>
}> {
  stubLocation('/usage', query)
  const { requests } = stubFetch(
    query.includes('tab=stats') ? () => json(200, STATS) : () => json(200, DETAIL),
  )
  const { container } = render(<UsagePage />)
  await flush()
  await flush()
  return { container, requests: requests.map(({ path, method }) => ({ path, method })) }
}

describe('使用记录', () => {
  it('reads the detail page and draws a search row beside a direct call', async () => {
    const { container, requests } = await showUsage()

    const path = requests[0]?.path ?? ''
    expect(path.startsWith('/web/usage/interactions?')).toBe(true)
    const params = new URLSearchParams(path.split('?')[1])
    expect(params.get('limit')).toBe('10')
    expect(params.get('since')).not.toBeNull()
    expect(params.get('until')).not.toBeNull()
    // The page's own default is 30 local days (the API's no-parameter default stays 90).
    const span = (Date.parse(params.get('until')!) - Date.parse(params.get('since')!)) / 86_400_000
    expect(span).toBe(30)
    expect(container.textContent).toContain('数据保留近 90 天')
    const rows = container.querySelectorAll('.usage-log-row')
    expect(rows).toHaveLength(2)
    expect(rows[0]?.getAttribute('data-kind')).toBe('search')
    expect(rows[0]?.textContent).toContain('pdf 合并')
    expect(rows[0]?.textContent).toContain('dev/pdf-tools')
    expect(rows[1]?.getAttribute('data-kind')).toBe('direct')
    expect(rows[1]?.textContent).toContain('lijinzhao/code-dev-sop')
  })

  it('opens the results in a panel below the row, and the panel keeps its own clicks', async () => {
    const { container } = await showUsage()
    const row = container.querySelector('[data-kind="search"]') as HTMLElement

    fireEvent.click(row)
    const panel = container.querySelector('.usage-detail') as HTMLElement
    expect(panel).not.toBeNull()
    expect(panel.textContent).toContain('搜索返回 12 个结果')
    expect(panel.textContent).toContain('yuhan/pdf-splitter')
    expect(panel.textContent).toContain('已调用')

    fireEvent.mouseDown(panel)
    fireEvent.click(panel)
    expect(container.querySelector('.usage-detail')).not.toBeNull()

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(container.querySelector('.usage-detail')).toBeNull()
  })

  it('closes the panel when the row is clicked again', async () => {
    const { container } = await showUsage()
    const row = container.querySelector('[data-kind="search"]') as HTMLElement

    fireEvent.click(row)
    expect(container.querySelector('.usage-detail')).not.toBeNull()
    fireEvent.click(row)
    expect(container.querySelector('.usage-detail')).toBeNull()
  })

  it('shows the four stats and the search outcome', async () => {
    const { container, requests } = await showUsage('tab=stats')

    const path = requests[0]?.path ?? ''
    expect(path.startsWith('/web/usage/stats?')).toBe(true)
    expect(new URLSearchParams(path.split('?')[1]).get('since')).not.toBeNull()
    expect(container.querySelectorAll('#usageStats .stat')).toHaveLength(4)
    expect(container.textContent).toContain('调用次数')
    expect(container.textContent).toContain('12')
    expect(container.textContent).toContain('8 次搜索中，6 次最终调用了结果，2 次未调用。')
    expect(container.querySelectorAll('.usage-top tbody tr')).toHaveLength(2)
  })

  it('shows the empty sentence only when the list really is empty', async () => {
    stubLocation('/usage', '')
    stubFetch(() => json(200, { ...DETAIL, items: [], total: 0, has_any: false }))
    const { container } = render(<UsagePage />)
    await flush()
    await flush()

    expect((container.querySelector('#usageEmpty') as HTMLElement).hidden).toBe(false)
    expect((container.querySelector('#usageFilterEmpty') as HTMLElement).hidden).toBe(true)
    expect(container.querySelector('.usage-log')).toBeNull()
  })

  it('shows the failure sentence, not an empty list', async () => {
    stubLocation('/usage', '')
    stubFetch(() => json(500, { error: { code: 'internal_error', message: 'x', details: [] } }))
    const { container } = render(<UsagePage />)
    await flush()
    await flush()

    const banner = container.querySelector('.banner') as HTMLElement
    expect(banner.hasAttribute('hidden')).toBe(false)
    expect(banner.textContent).toContain('使用记录暂时无法加载')
  })
})
describe('使用记录的筛选（v60）', () => {
  it('sends the local day bounds and the skill keyword', async () => {
    stubLocation('/usage', '?days=7&skill=pdf')
    const { requests } = stubFetch(() => json(200, { ...DETAIL, items: [], total: 0 }))
    render(<UsagePage />)
    await flush()
    await flush()

    const params = new URLSearchParams((requests[0]?.path ?? '').split('?')[1])
    expect(params.get('skill')).toBe('pdf')
    expect(params.get('days')).toBeNull()
    const since = new Date(params.get('since') ?? '')
    const until = new Date(params.get('until') ?? '')
    expect(since.getHours()).toBe(0)
    expect(until.getHours()).toBe(0)
    const span = until.getTime() - since.getTime()
    expect(span).toBeGreaterThanOrEqual(7 * 86_400_000 - 3_600_000)
    expect(span).toBeLessThanOrEqual(7 * 86_400_000 + 3_600_000)
  })

  it('says why a search row is here when only its results matched', async () => {
    stubLocation('/usage', '?skill=pdf-tools')
    stubFetch(() => json(200, {
      ...DETAIL,
      items: [{
        kind: 'search',
        id: 42,
        at: '2026-11-10T01:38:00Z',
        query: 'pdf',
        total: 2,
        returned: 2,
        results: [{ skill: 'dev/pdf-tools', rank: 1 }, { skill: 'other/tool', rank: 2 }],
        invocations: [{ skill: 'other/tool', version: '1.0.0', at: '2026-11-10T01:41:00Z', rank: 2 }],
      }],
      total: 1,
    }))
    const { container } = render(<UsagePage />)
    await flush()
    await flush()

    expect(container.querySelector('.usage-hit')?.textContent).toBe('搜到过')
  })

  it('offers a filtered empty state, not the no-records one', async () => {
    stubLocation('/usage', '?days=7&skill=zzz')
    stubFetch(() => json(200, { ...DETAIL, items: [], total: 0 }))
    const { container } = render(<UsagePage />)
    await flush()
    await flush()

    const filtered = container.querySelector('#usageFilterEmpty') as HTMLElement
    expect(filtered.hidden).toBe(false)
    expect(filtered.textContent).toContain('没有匹配的使用记录')
    expect(filtered.textContent).toContain('清除筛选')
    expect((container.querySelector('#usageEmpty') as HTMLElement).hidden).toBe(true)
  })

  it('says there are no records at all when the account has none', async () => {
    stubLocation('/usage', '?days=7&skill=zzz')
    stubFetch(() => json(200, { ...DETAIL, items: [], total: 0, has_any: false }))
    const { container } = render(<UsagePage />)
    await flush()
    await flush()

    expect((container.querySelector('#usageEmpty') as HTMLElement).hidden).toBe(false)
    expect((container.querySelector('#usageFilterEmpty') as HTMLElement).hidden).toBe(true)
  })

  it('explains the numbers with the filtered skill wording', async () => {
    stubLocation('/usage', '?tab=stats&skill=pdf')
    stubFetch(() => json(200, { ...STATS, skill: 'pdf' }))
    const { container } = render(<UsagePage />)
    await flush()
    await flush()

    expect(container.textContent).toContain('搜索结果里出现了这条 skill')
    expect(container.textContent).toContain('最终调用了它')
  })
})
describe('使用记录的筛选交互（v60）', () => {
  it('refetches as the skill keyword is typed, without losing the box', async () => {
    stubLocation('/usage', '')
    const { requests } = stubFetch(
      () => json(200, DETAIL),
      () => json(200, { ...DETAIL, items: [], total: 0 }),
    )
    const { container } = render(<UsagePage />)
    await flush()
    await flush()

    const box = container.querySelector('#usageSkill') as HTMLInputElement
    fireEvent.change(box, { target: { value: 'pdf' } })
    // The box is a library-tier search: 250 ms of quiet before the read.
    await new Promise((resolve) => setTimeout(resolve, 300))
    await flush()
    await flush()

    expect(container.querySelector('#usageSkill')).toBe(box)
    expect((container.querySelector('#usageSkill') as HTMLInputElement).value).toBe('pdf')
    const second = new URLSearchParams((requests[1]?.path ?? '').split('?')[1])
    expect(second.get('skill')).toBe('pdf')
  })

  it('keeps the filters on the tab links', async () => {
    stubLocation('/usage', '?days=7&skill=pdf')
    stubFetch(() => json(200, { ...DETAIL, total: 2 }))
    const { container } = render(<UsagePage />)
    await flush()
    await flush()

    const stats = Array.from(container.querySelectorAll('#usageTabs a'))
      .find((link) => link.textContent === '统计') as HTMLAnchorElement
    expect(stats.getAttribute('href')).toContain('days=7')
    expect(stats.getAttribute('href')).toContain('skill=pdf')
    expect(stats.getAttribute('href')).toContain('tab=stats')
  })

  it('reads the tab and the page from the address', async () => {
    stubLocation('/usage', '?days=7&skill=pdf&p=2')
    const { requests } = stubFetch(() => json(200, { ...DETAIL, total: 25 }))
    const { container } = render(<UsagePage />)
    await flush()
    await flush()

    expect(requests[0]?.path).toContain('page=2')
    const stats = Array.from(container.querySelectorAll('#usageTabs a'))
      .find((link) => link.textContent === '统计') as HTMLAnchorElement
    // Switching tabs drops the page (each list has its own length) but keeps the filters.
    expect(stats.getAttribute('href')).not.toContain('p=2')
    expect(stats.getAttribute('href')).toContain('days=7')
  })

  it('keeps a range picked while the keyword is still waiting for its 250 ms', async () => {
    stubLocation('/usage', '')
    const { requests } = stubFetch(
      () => json(200, DETAIL),
      () => json(200, { ...DETAIL, items: [], total: 0 }),
      () => json(200, { ...DETAIL, items: [], total: 0 }),
    )
    const { container } = render(<UsagePage />)
    await flush()
    await flush()

    fireEvent.change(container.querySelector('#usageSkill') as HTMLInputElement, {
      target: { value: 'pdf' },
    })
    fireEvent.click(container.querySelector('#usageRange') as HTMLElement)
    fireEvent.click(container.querySelector('#usageRangePreset7') as HTMLElement)

    await new Promise((resolve) => setTimeout(resolve, 300))
    await flush()
    await flush()

    const last = new URLSearchParams((requests.at(-1)?.path ?? '').split('?')[1])
    expect(last.get('skill')).toBe('pdf')
    const span = (Date.parse(last.get('until')!) - Date.parse(last.get('since')!)) / 86_400_000
    expect(span).toBe(7)
  })

  it('refetches when a time preset is picked', async () => {
    stubLocation('/usage', '')
    const { requests } = stubFetch(
      () => json(200, DETAIL),
      () => json(200, DETAIL),
    )
    const { container } = render(<UsagePage />)
    await flush()
    await flush()

    fireEvent.click(container.querySelector('#usageRange') as HTMLElement)
    await flush()
    fireEvent.click(container.querySelector('#usageRangePreset7') as HTMLElement)
    await flush()
    await flush()

    expect(container.querySelector('#usageRange')?.textContent).toBe('近 7 天')
    const second = new URLSearchParams((requests[1]?.path ?? '').split('?')[1])
    const span = Date.parse(second.get('until') ?? '') - Date.parse(second.get('since') ?? '')
    expect(span).toBeGreaterThanOrEqual(7 * 86_400_000 - 3_600_000)
    expect(span).toBeLessThanOrEqual(7 * 86_400_000 + 3_600_000)
  })
})
