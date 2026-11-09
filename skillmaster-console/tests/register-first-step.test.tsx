import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, type RenderResult } from '@testing-library/react'
import RegisterPage from '../src/pages/RegisterPage'
import { clearCookies, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

/* What the register page does before anything is sent: the consent box, and the captcha question
   it asks as it opens. Kept out of `register-flow.test.tsx`, which is over the size limit and may
   only shrink (scripts/check-file-size.mjs). */

const PHONE = '13800138000'
const USERNAME = 'demo-user'
const PASSWORD = 'a-long-enough-password'
const NO_CAPTCHA_NEEDED = () => json(200, { required: false })

let location = stubLocation('/register')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/register')
  // The refusal below is expected, and the client logs every one of them.
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function showPage(): Promise<
  RenderResult & { requests: Array<{ path: string }> }
> {
  const { requests } = stubFetch(NO_CAPTCHA_NEEDED)
  const view = render(<RegisterPage />)
  await flush()
  return { ...view, requests }
}

function type(container: HTMLElement, selector: string, value: string): void {
  fireEvent.change(container.querySelector(selector) as HTMLInputElement, { target: { value } })
}

describe('the register page before the first send', () => {
  it('draws nothing and stays usable when the captcha question cannot be answered', async () => {
    // A failed lookup costs the click it was meant to save, and nothing more. What it must not do is
    // leave a form that cannot be finished: the refusal path is still behind the button.
    const { container } = await showPage(() =>
      json(500, errorBody('internal_error', 'the server said no')),
    )

    expect(container.querySelector('#captcha')).toBeNull()
  })

  it('points the consent links at the site, where the legal pages live', async () => {
    const { container } = await showPage()

    // `SITE_URL` is the one door out (ADR 0086): this origin has no /privacy or /terms route, so a
    // relative link would open the console's fallback page instead of the text.
    const links = Array.from(container.querySelectorAll<HTMLAnchorElement>('.consent a'))
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      'http://localhost:5174/terms',
      'http://localhost:5174/privacy',
    ])
  })

  it('stops on the first step, before any message, until the box is ticked', async () => {
    const { container, requests } = await showPage()
    type(container, '#username', USERNAME)
    type(container, '#phone', PHONE)
    type(container, '#password', PASSWORD)

    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    expect(container.textContent).toContain('请先阅读并同意《服务条款》和《隐私政策》')
    // No text message was bought, and the second step never opened.
    expect(requests.map((request) => request.path)).not.toContain('/web/register/code')
    expect(container.querySelector('#sms-code')).toBeNull()
  })
})
