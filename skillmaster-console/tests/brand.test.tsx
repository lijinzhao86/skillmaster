import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import AppShell from '../src/AppShell'
import { clearCookies, setCookie, stubLocation } from './support'

/**
 * **The brand is the way out, not the console's own home** (design README §控制台演示 › 顶栏的品牌回首页,
 * 2026-10-29). It links to the marketing site — the same address `/logout` returns people to.
 *
 * The address is `VITE_SITE_URL`. The tests run without it, so they see the development fallback,
 * exactly as `tests/logout-page.test.tsx` does; a build that took that fallback is refused by
 * `vite.config.ts`, which is where the real value is checked.
 */
const SITE = 'http://localhost:5174/'

let location = stubLocation('/')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the top bar', () => {
  it('sends the brand to the marketing site, not to the console root', () => {
    const { container } = render(
      <AppShell>
        <p>page</p>
      </AppShell>,
    )
    const brand = container.querySelector('a.brand')

    expect(brand).not.toBeNull()
    expect(brand?.getAttribute('href')).toBe(SITE)
    // The name is the design's, unchanged: mark + 「SkillMaster」.
    expect(brand?.textContent).toBe('SkillMaster')
    // **And the link has a name of its own.** Below 640px the text is hidden and the mark is
    // decorative, so the accessible name has to come from the anchor — and it must contain the
    // visible word, or the label and the screen disagree (WCAG 2.5.3).
    expect(brand?.getAttribute('aria-label')).toBe('SkillMaster 首页')
    expect(brand?.getAttribute('aria-label')).toContain('SkillMaster')
  })
})
