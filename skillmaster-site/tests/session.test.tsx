import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import App from '../src/App'
import { SESSION_TIMEOUT_MS } from '../src/session'
import { flush, json, stubFetch } from './support'

const ACCOUNT = { user_id: 'u-1', username: 'dev', namespace: 'dev' }

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the one session read (ADR 0086)', () => {
  it('is a single credentialed GET to the console-side path', async () => {
    const calls = stubFetch(json(200, ACCOUNT))
    render(<App />)

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0]?.url).toBe('/web/session')
    expect(calls[0]?.init?.method).toBe('GET')
    expect(calls[0]?.init?.credentials).toBe('include')
  })

  it('draws the signed-out page first and swaps to the account when the answer arrives', async () => {
    let answer: (value: Response) => void = () => undefined
    const pending = new Promise<Response>((resolve) => {
      answer = resolve
    })
    stubFetch(() => pending)
    render(<App />)

    // Before the answer: the page a signed-out visitor sees, complete and usable.
    expect(document.querySelector('#loginBtn')).not.toBeNull()
    expect(document.querySelector('#toConsoleBtn')).toBeNull()

    await act(async () => {
      answer(json(200, ACCOUNT))
      await flush()
    })

    expect(document.querySelector('#loginBtn')).toBeNull()
    const consoleLink = document.querySelector('#toConsoleBtn') as HTMLAnchorElement
    expect(consoleLink.getAttribute('href')).toBe('http://localhost:5173/')
    expect(screen.getByText('dev')).toBeTruthy()
  })

  it('treats 401 as signed out', async () => {
    stubFetch(json(401, { error: 'unauthenticated' }))
    render(<App />)

    await waitFor(() => expect(document.querySelector('#loginBtn')).not.toBeNull())
    expect(document.querySelector('#toConsoleBtn')).toBeNull()
  })

  it('treats a network failure as signed out, and still draws the whole page', async () => {
    stubFetch(() => Promise.reject(new TypeError('Failed to fetch')))
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    render(<App />)

    await waitFor(() => expect(document.querySelector('#loginBtn')).not.toBeNull())
    expect(screen.getByText('SkillMaster 随时在场')).toBeTruthy()
    expect(screen.getByText('根据 https://www.skillmasterai.com/install/skillmaster.md 开始 SkillMaster 的旅程。')).toBeTruthy()
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(3 + 3) // cards + footer columns
  })

  it('treats an answer that is not an account as signed out', async () => {
    stubFetch(json(200, { hello: 'world' }))
    render(<App />)

    await waitFor(() => expect(document.querySelector('#loginBtn')).not.toBeNull())
    expect(document.querySelector('#toConsoleBtn')).toBeNull()
  })

  it('gives up after the deadline and shows the signed-out page', async () => {
    vi.useFakeTimers()
    // Never answers, and rejects when the abort signal fires — as a real fetch does.
    stubFetch(
      () =>
        new Promise<Response>((_resolve, reject) => {
          setTimeout(() => reject(new DOMException('aborted', 'AbortError')), SESSION_TIMEOUT_MS)
        }),
    )
    render(<App />)

    await act(async () => {
      vi.advanceTimersByTime(SESSION_TIMEOUT_MS + 1)
    })

    expect(document.querySelector('#loginBtn')).not.toBeNull()
    expect(document.querySelector('#toConsoleBtn')).toBeNull()
  })

  it('leaves the hero, prompt and cards untouched when the account arrives', async () => {
    let answer: (value: Response) => void = () => undefined
    const pending = new Promise<Response>((resolve) => {
      answer = resolve
    })
    stubFetch(() => pending)
    render(<App />)

    const before = document.querySelector('.hero')?.textContent
    await act(async () => {
      answer(json(200, ACCOUNT))
      await flush()
    })

    expect(document.querySelector('.hero')?.textContent).toBe(before)
  })

  it('closes the account menu on Escape and hands the focus back', async () => {
    stubFetch(json(200, ACCOUNT))
    render(<App />)

    const me = await screen.findByRole('button', { name: /dev/ })
    await act(async () => {
      me.click()
    })
    expect(document.querySelector('#meMenu')?.hasAttribute('hidden')).toBe(false)

    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    })

    expect(document.querySelector('#meMenu')?.hasAttribute('hidden')).toBe(true)
    expect(document.activeElement).toBe(me)
  })

  it('points the account menu at the console, not at a write of its own', async () => {
    stubFetch(json(200, ACCOUNT))
    render(<App />)

    const me = await screen.findByRole('button', { name: /dev/ })
    me.click()
    const signOut = await screen.findByRole('link', { name: '退出登录' })
    expect(signOut.getAttribute('href')).toBe('http://localhost:5173/logout')
  })
})
