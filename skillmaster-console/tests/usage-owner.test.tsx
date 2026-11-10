import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import type { AuthoredSkill } from '../src/api/types'
import UsagePane from '../src/components/usage/UsagePane'
import { clearCookies, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * The owner's two views (A20, U2) — the half of the usage pane that is not the personal log.
 *
 * The role column is the reason this file exists: the wire carries the rung key (`maintain`), and
 * the table has to say the interface's word (`维护`) beside an owner row that says `所有者`.
 */

const SKILL = { rung: 'owner' } as unknown as AuthoredSkill

const ALL = {
  skill: 'dev/pdf-tools',
  window_days: 90,
  public: false,
  totals: { calls: '12', accounts: '3', joins: 45, last_used: '2026-11-05T08:00:00Z' },
  users: [
    { who: 'dev', owner: true, role: '所有者', invoke: 3, submitted: 12, last: '2026-11-10T01:41:00Z' },
    { who: 'yuhan', owner: false, role: 'maintain', invoke: 42, submitted: 5, last: '2026-11-09T18:20:00Z' },
  ],
}

const MINE = {
  skill: 'dev/pdf-tools',
  window_days: 90,
  calls: 3,
  versions: 1,
  latest: '2026-11-10T01:41:00Z',
  by_version: [
    { version: '1.2.3', state: 'live', calls: 3, last: '2026-11-10T01:41:00Z' },
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

describe('使用页签的所有者视图', () => {
  it('draws the named users table with the interface word for the rung', async () => {
    stubLocation('/skills/dev/pdf-tools', 'tab=usage')
    stubFetch(() => json(200, ALL))

    const { container } = render(<UsagePane skill={SKILL} namespace="dev" name="pdf-tools" />)
    await flush()
    await flush()

    const buttons = Array.from(container.querySelectorAll('.usage-scope button'))
    expect(buttons.map((button) => button.textContent)).toEqual(['全部使用', '我的使用'])

    const cards = Array.from(container.querySelectorAll('.stats .stat .stat-head'))
      .map((head) => head.textContent)
    expect(cards).toEqual(['调用次数', '使用账号数', '累计入库', '最近使用'])
    expect(container.querySelector('#skillUsageUsers')).not.toBeNull()
    // The card says the day in the design's month word and carries the clock beside it.
    const lastCard = Array.from(container.querySelectorAll('.stats .stat')).at(-1)
    expect(lastCard?.textContent).toContain('11 月 5 日')
    expect(lastCard?.textContent).toContain('16:00')
    expect(container.querySelector('h2')?.textContent).toBe('使用者')
    expect(container.textContent).toContain('调用次数看采用，提交版本看贡献')

    const rows = container.querySelectorAll('#skillUsageUsers tbody tr')
    expect(Array.from(rows).map((row) => row.textContent)).toEqual([
      expect.stringContaining('所有者'),
      expect.stringContaining('维护'),
    ])
    expect(container.textContent).not.toContain('maintain')
    // 最近活跃 is the window's date, not the account's lifetime.
    expect(rows[0]?.textContent).toContain('11-10')
  })

  it('writes the public note with the platform-signal pointer', async () => {
    stubLocation('/skills/dev/pdf-tools', 'tab=usage')
    stubFetch(() => json(200, { ...ALL, public: true, users: [], totals: { ...ALL.totals, calls: '10+', accounts: '少于 10 个账号' } }))

    const { container } = render(<UsagePane skill={SKILL} namespace="dev" name="pdf-tools" />)
    await flush()
    await flush()

    const note = container.querySelector('#skillUsagePublic')
    expect(note?.textContent).toContain('近 30 天账号分桶在')
    expect(note?.textContent).toContain('skmr skill show')
    expect(container.querySelector('#skillUsageUsers')).toBeNull()
  })

  it('gives a non-owner the time按钮 without the view switch', async () => {
    stubLocation('/skills/dev/pdf-tools', 'tab=usage&days=7')
    const { requests } = stubFetch(() => json(200, MINE))

    const { container } = render(
      <UsagePane skill={{ rung: 'browse' } as unknown as AuthoredSkill}
        namespace="dev" name="pdf-tools" />,
    )
    await flush()
    await flush()

    expect(container.querySelector('.usage-scope')).toBeNull()
    expect(container.querySelector('#skillUsageRange')).not.toBeNull()
    expect(container.querySelector('#skillUsageMine')).not.toBeNull()
    expect(requests[0]?.path).toMatch(/^\/web\/usage\/skills\/dev\/pdf-tools\/mine\?since=/)
  })

  it('switches to 我的使用 and names the by-version table', async () => {
    stubLocation('/skills/dev/pdf-tools', 'tab=usage')
    const { requests } = stubFetch(() => json(200, ALL), () => json(200, MINE))

    const { container } = render(<UsagePane skill={SKILL} namespace="dev" name="pdf-tools" />)
    await flush()
    await flush()

    fireEvent.click(Array.from(container.querySelectorAll('.usage-scope button'))[1]!)
    await flush()
    await flush()

    expect(container.querySelector('.usage-scope button[aria-pressed="true"]')?.textContent)
      .toBe('我的使用')
    expect(container.querySelector('#skillUsageMine')).not.toBeNull()
    expect(container.textContent).toContain('按版本')
    expect(container.querySelector('#skillUsageMine thead th')?.textContent).toBe('版本号')
    expect(container.querySelector('#skillUsageMine .chip-text')?.textContent).toBe('线上')
    expect(container.textContent).toContain('11-10 09:41')
    expect(requests[1]?.path).toMatch(/^\/web\/usage\/skills\/dev\/pdf-tools\/mine\?since=/)
  })
})
