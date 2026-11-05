import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, waitFor } from '@testing-library/react'
import LogoutPage, { LOGOUT_INSTRUCTION } from '../src/pages/LogoutPage'
import { routeOf } from '../src/App'
import { clearCookies, errorBody, setCookie, stubFetch, stubLocation } from './support'

const SITE = 'http://localhost:5174/'

let location = stubLocation('/logout')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/logout')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** Replaces the clipboard for one test; jsdom has no implementation of its own. */
function stubClipboard(): ReturnType<typeof vi.fn> {
  const writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText },
  })
  return writeText
}

describe('/logout (ADR 0086 decision 2, ADR 0141)', () => {
  it('is a route of its own, with nothing of the console drawn around it', () => {
    const route = routeOf('/logout')
    expect(route.shell).toBe(false)
    // `standalone` is the stronger promise: no brand line around it either.
    expect(route.standalone).toBe(true)
    // And the only route that draws nothing at all while the session read is still out — see App
    // for why that is scoped to this one page rather than to every `standalone` page.
    expect(route.blankWhileLoading).toBe(true)
  })

  it('posts the session away and stops on the guide instead of leaving', async () => {
    const { requests } = stubFetch(() => new Response(null, { status: 204 }))
    const { findByText } = render(<LogoutPage />)

    await findByText('这个浏览器上的登录已经结束。')
    // The page used to replace itself with the site. It now has something to say, and the person
    // chooses when to leave.
    expect(location.replace).not.toHaveBeenCalled()
    expect(requests).toHaveLength(1)
    expect(requests[0]?.path).toBe('/web/logout')
    expect(requests[0]?.method).toBe('POST')
  })

  it('says what ended, what did not, and how to end that too', async () => {
    stubFetch(() => new Response(null, { status: 204 }))
    const { findByText, container } = render(<LogoutPage />)

    await findByText('这个浏览器上的登录已经结束。')
    // The middle sentence is conditional on purpose: the browser cannot know whether the CLI on
    // this machine has already signed out.
    expect(container.textContent).toContain(
      '这台电脑上的 CLI 如果还没有退出，就仍然可以正常使用。',
    )
    expect(container.textContent).toContain('要让它在终端也退出，把这句话交给 agent：')
    expect(container.textContent).toContain('skmr logout')

    // The guide is about the CLI login only; the design says so, and the words that would promise
    // more are absent.
    expect(container.textContent).not.toContain('重新登录')
    expect(container.textContent).not.toContain('登录设备')
    expect(container.textContent).not.toContain('API 密钥')
  })

  it('shows nothing while it works — no console chrome for somebody leaving', () => {
    stubFetch(() => new Promise<Response>(() => undefined))
    const { container } = render(<LogoutPage />)

    expect(container.textContent).toBe('')
  })

  /**
   * React's development mode runs an effect twice. Without the page's own guard that is two logouts
   * for one visit — and the second one, sent after the first cleared the session, is answered
   * differently. This renders the real component under `StrictMode`, as development does.
   */
  it('sends one logout even when its effect runs twice', async () => {
    const { requests } = stubFetch(() => new Response(null, { status: 204 }))
    const { findByText } = render(
      <StrictMode>
        <LogoutPage />
      </StrictMode>,
    )

    await findByText('这个浏览器上的登录已经结束。')
    expect(requests).toHaveLength(1)
  })

  it('copies the sentence for the agent, with the icon turning into a check', async () => {
    stubFetch(() => new Response(null, { status: 204 }))
    const writeText = stubClipboard()
    const { findByRole } = render(<LogoutPage />)

    const button = await findByRole('button', { name: '复制这句话' })
    // The guide focuses its card when it opens, the way the design's dialog does for a view with no
    // field of its own.
    expect(document.querySelector('[role="dialog"]')).toBe(document.activeElement)
    fireEvent.click(button)

    expect(writeText).toHaveBeenCalledWith(LOGOUT_INSTRUCTION)
    await waitFor(() => expect(button.getAttribute('aria-label')).toBe('已复制这句话'))
    // Back to the resting state: the check is feedback, not a mode.
    await waitFor(() => expect(button.getAttribute('aria-label')).toBe('复制这句话'), {
      timeout: 3000,
    })
  })

  it('has one way home', async () => {
    stubFetch(() => new Response(null, { status: 204 }))
    const { findByRole } = render(<LogoutPage />)

    fireEvent.click(await findByRole('button', { name: '回到首页' }))
    expect(location.replace).toHaveBeenCalledWith(SITE)
  })

  it('gives a way back when the write fails', async () => {
    stubFetch(() => errorBody(500, 'internal_error'))
    const { findByRole, container } = render(<LogoutPage />)

    const back = await findByRole('link', { name: '回到首页' })
    expect(back.getAttribute('href')).toBe(SITE)
    // The existing failure sentence, unchanged by v46 — and the guide's sentences absent, because
    // they would be a claim about a sign-out that did not happen.
    expect(container.textContent).toContain('没能退出登录。可以回去重试，或直接关闭这个页面。')
    expect(container.textContent).not.toContain('这个浏览器上的登录已经结束。')
  })
})
