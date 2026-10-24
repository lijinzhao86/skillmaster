import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import LogoutPage from '../src/pages/LogoutPage'
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

describe('/logout (ADR 0086 decision 2)', () => {
  it('is a route of its own, with nothing of the console drawn around it', () => {
    const route = routeOf('/logout')
    expect(route.shell).toBe(false)
    // `standalone` is the stronger promise: no brand line around it either.
    expect(route.standalone).toBe(true)
    // And the only route that draws nothing at all while the session read is still out — see App
    // for why that is scoped to this one page rather than to every `standalone` page.
    expect(route.blankWhileLoading).toBe(true)
  })

  it('posts the session away and returns to the site, replacing this page', async () => {
    const { requests } = stubFetch(() => new Response(null, { status: 204 }))
    render(<LogoutPage />)

    await waitFor(() => expect(location.replace).toHaveBeenCalledWith(SITE))
    expect(requests).toHaveLength(1)
    expect(requests[0]?.path).toBe('/web/logout')
    expect(requests[0]?.method).toBe('POST')
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
    render(
      <StrictMode>
        <LogoutPage />
      </StrictMode>,
    )

    await waitFor(() => expect(location.replace).toHaveBeenCalledWith(SITE))
    expect(requests).toHaveLength(1)
  })

  it('gives a way back when the write fails', async () => {
    stubFetch(() => errorBody(500, 'internal_error'))
    const { findByRole } = render(<LogoutPage />)

    const back = await findByRole('link', { name: '回到首页' })
    expect(back.getAttribute('href')).toBe(SITE)
  })
})
