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

    expect(requests[0]?.path).toBe('/web/usage/interactions?limit=10')
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

    expect(requests[0]?.path).toBe('/web/usage/stats')
    expect(container.querySelectorAll('#usageStats .stat')).toHaveLength(4)
    expect(container.textContent).toContain('调用次数')
    expect(container.textContent).toContain('12')
    expect(container.textContent).toContain('8 次搜索中，6 次最终调用了结果，2 次未调用。')
    expect(container.querySelectorAll('.usage-top tbody tr')).toHaveLength(2)
  })

  it('shows the empty sentence only when the list really is empty', async () => {
    stubLocation('/usage', '')
    stubFetch(() => json(200, { ...DETAIL, items: [], total: 0 }))
    const { container } = render(<UsagePage />)
    await flush()
    await flush()

    expect(container.querySelector('.empty')?.hasAttribute('hidden')).toBe(false)
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
