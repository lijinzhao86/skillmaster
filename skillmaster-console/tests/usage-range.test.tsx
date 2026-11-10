import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import UsageRange from '../src/components/usage/UsageRange'
import { clampSkill, DEFAULT_RANGE, rangeFromParams, shiftDay, today, validDay } from '../src/usageRange'

/**
 * The range form's own rules (T26): a date that does not exist, a start after the end, an end
 * after today. Each sentence sits under its field and the apply button stays disabled.
 */

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  vi.restoreAllMocks()
})

function open(range = { days: 7, from: null, to: null }) {
  const { container } = render(
    <UsageRange id="r" label="时间范围" range={range} onChange={() => undefined} />,
  )
  fireEvent.click(container.querySelector('#r') as HTMLElement)
  return container
}

function errorOf(container: HTMLElement): string {
  return container.querySelector('#rError')?.textContent ?? ''
}

describe('时间范围的表单', () => {
  it('refuses a day that does not exist on the calendar', () => {
    const container = open()
    fireEvent.change(container.querySelector('#rFrom') as HTMLInputElement, {
      target: { value: '2026-09-31' },
    })

    expect(errorOf(container)).toContain('这个日期不存在')
    expect((container.querySelector('#rApply') as HTMLButtonElement).disabled).toBe(true)
  })

  it('refuses a start after the end', () => {
    const container = open()
    const day = today()
    const later = new Date(day + 'T12:00:00Z')
    later.setUTCDate(later.getUTCDate() + 1)
    const tomorrow = later.toISOString().slice(0, 10)

    fireEvent.change(container.querySelector('#rFrom') as HTMLInputElement, {
      target: { value: tomorrow },
    })
    fireEvent.change(container.querySelector('#rTo') as HTMLInputElement, {
      target: { value: day },
    })

    expect(errorOf(container)).toContain('开始日期不能晚于结束日期')
    expect((container.querySelector('#rApply') as HTMLButtonElement).disabled).toBe(true)
  })

  it('refuses an end after today, and says which day is the oldest', () => {
    const container = open()
    const day = today()
    const later = new Date(day + 'T12:00:00Z')
    later.setUTCDate(later.getUTCDate() + 1)
    fireEvent.change(container.querySelector('#rTo') as HTMLInputElement, {
      target: { value: later.toISOString().slice(0, 10) },
    })
    expect(errorOf(container)).toContain('结束日期不能晚于今天')

    fireEvent.change(container.querySelector('#rTo') as HTMLInputElement, {
      target: { value: day },
    })
    fireEvent.change(container.querySelector('#rFrom') as HTMLInputElement, {
      target: { value: '2000-01-01' },
    })
    expect(errorOf(container)).toContain('只能选')
    expect((container.querySelector('#rApply') as HTMLButtonElement).disabled).toBe(true)
  })
})

describe('日期与关键词的校验函数', () => {
  it('validDay rejects the shapes JavaScript would roll forward', () => {
    expect(validDay('2026-02-28')).toBe(true)
    expect(validDay('2026-09-31')).toBe(false)
    expect(validDay('2026-13-01')).toBe(false)
  })

  it('clampSkill stops at the server limit in code points', () => {
    expect(clampSkill('x'.repeat(120))).toHaveLength(100)
    expect(clampSkill('😀'.repeat(120))).toHaveLength(200) // 100 astral code points, 200 UTF-16 units
    expect(clampSkill('pdf')).toBe('pdf')
  })

  it('defaults to thirty days', () => {
    expect(DEFAULT_RANGE.days).toBe(30)
  })

  it('accepts the first of the ninety local days and refuses one day earlier', () => {
    const day = today()
    const oldest = shiftDay(day, -89)
    expect(rangeFromParams(new URLSearchParams(`from=${oldest}&to=${day}`)))
      .toEqual({ days: null, from: oldest, to: day })
    expect(rangeFromParams(new URLSearchParams(`from=${shiftDay(day, -90)}&to=${day}`)))
      .toEqual(DEFAULT_RANGE)
  })

  it('falls back to the default when the address carries a broken range', () => {
    expect(rangeFromParams(new URLSearchParams('from=2026-13-01&to=2026-11-10'))).toEqual(DEFAULT_RANGE)
    expect(rangeFromParams(new URLSearchParams('from=2026-09-31&to=2026-10-02'))).toEqual(DEFAULT_RANGE)
    expect(rangeFromParams(new URLSearchParams('days=14'))).toEqual(DEFAULT_RANGE)
  })
})
