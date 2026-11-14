import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SKILL, showSkill, text } from './skill-page-harness'
import { clearCookies, setCookie } from './support'

/**
 * v66's information page: the platform's expected result and its attribute chips.
 *
 * Kept out of `skills.test.tsx` because that file is on the size baseline (may only shrink) and
 * these cases are new rather than moved. The expected-result section's position in the block order
 * is asserted there, beside the fields it sits between.
 */
beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  clearCookies()
  vi.restoreAllMocks()
})

describe('one skill: the platform labels', () => {
  it('draws the attributes as filter chips, short names only', async () => {
    const { container } = await showSkill(SKILL)

    const chips = [...container.querySelectorAll('.info-attributes-row a.chip.is-attr')]
    expect(chips.map((chip) => text(chip))).toEqual(['Python', 'API key'])
    expect(chips[0]?.getAttribute('href')).toBe('/discover?label=runtime%3Apython')
    expect(chips[0]?.getAttribute('data-group')).toBe('language')
    // The full sentence stays in the data; no chip, tooltip or screen reader carries it.
    expect(chips[0]?.getAttribute('title') ?? '').not.toContain('需要 Python')
  })
})
