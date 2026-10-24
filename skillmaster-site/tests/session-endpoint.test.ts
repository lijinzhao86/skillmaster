import { describe, expect, it } from 'vitest'
import { sessionEndpoint } from '../src/session'

/**
 * The two addresses the one read can go to (ADR 0086).
 *
 * Both matter, and only one of them is what the test run itself uses: development asks this app's
 * own origin because `vite.config.ts` proxies it, and production asks the console's origin because
 * that is the only origin the server allows. Getting the production one wrong is invisible in
 * development and fails silently in deployment — the page simply never shows the account.
 */
describe('sessionEndpoint', () => {
  it('asks this app in development, where the dev server proxies /web/', () => {
    expect(sessionEndpoint(true, 'https://console.skillmasterai.com')).toBe('/web/session')
  })

  it("asks the console's origin in production", () => {
    expect(sessionEndpoint(false, 'https://console.skillmasterai.com')).toBe(
      'https://console.skillmasterai.com/web/session',
    )
  })

  it('never doubles the slash it appends after, because the console URL carries none', () => {
    // `CONSOLE_URL` strips trailing slashes; this pins the other half of that agreement.
    expect(sessionEndpoint(false, 'http://localhost:5173')).toBe('http://localhost:5173/web/session')
  })
})
