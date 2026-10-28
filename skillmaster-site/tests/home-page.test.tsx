import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import App from '../src/App'
import { json, stubFetch } from './support'

const ACCOUNT = { user_id: 'u-1', username: 'dev', namespace: 'dev' }

beforeEach(() => {
  // Signed out, which is what most of these are about; the session tests cover the other state.
  stubFetch(json(401, { error: 'unauthenticated' }))
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the marketing page', () => {
  it('is the page the design draws: one line, one prompt, three cards, three footer columns', () => {
    render(<App />)

    expect(screen.getByText('SkillMaster 随时在场')).toBeTruthy()
    expect(screen.getByText('根据 https://www.skillmasterai.com/install/skillmaster.md 安装 SkillMaster。')).toBeTruthy()
    expect(document.querySelectorAll('.card')).toHaveLength(3)
    expect(document.querySelectorAll('.foot-col')).toHaveLength(3)
    expect(document.querySelector('.card-eyebrow')).toBeNull()
  })

  it('sends the two ways in to the console, and keeps the seven placeholders inert', () => {
    render(<App />)

    expect(document.querySelector('#loginBtn')?.getAttribute('href')).toBe('http://localhost:5173/login')
    expect(document.querySelector('#startBtnNav')?.getAttribute('href')).toBe(
      'http://localhost:5173/register',
    )

    const placeholders = document.querySelectorAll('a[href="#"]')
    expect(placeholders).toHaveLength(8) // the brand + seven footer links
    const address = window.location.href
    fireEvent.click(screen.getByText('更新日志'))
    expect(window.location.href).toBe(address)
  })

  it('sends the footer product column to the console too', () => {
    render(<App />)

    expect(document.querySelector('#footConsole')?.getAttribute('href')).toBe('http://localhost:5173/')
    expect(document.querySelector('#footStart')?.getAttribute('href')).toBe('http://localhost:5173/register')
    expect(document.querySelector('#footLogin')?.getAttribute('href')).toBe('http://localhost:5173/login')
  })

  it('copies exactly the sentence on screen and says so', async () => {
    const written: string[] = []
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async (text: string) => { written.push(text) } },
    })
    render(<App />)

    fireEvent.click(screen.getByRole('button', { name: '复制' }))

    await waitFor(() => expect(screen.getByRole('button', { name: '已复制' })).toBeTruthy())
    expect(written).toEqual(['根据 https://www.skillmasterai.com/install/skillmaster.md 安装 SkillMaster。'])
  })

  it('falls back to the selection copy when the modern API refuses', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async () => { throw new Error('denied') } },
    })
    const exec = vi.fn(() => true)
    Object.defineProperty(document, 'execCommand', { configurable: true, value: exec })
    render(<App />)

    fireEvent.click(screen.getByRole('button', { name: '复制' }))

    await waitFor(() => expect(screen.getByRole('button', { name: '已复制' })).toBeTruthy())
    expect(exec).toHaveBeenCalledWith('copy')
  })

  it('returns the button to 「复制」 two seconds after the feedback', async () => {
    vi.useFakeTimers()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async () => undefined },
    })
    render(<App />)

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '复制' }))
      await Promise.resolve()
    })
    expect(screen.getByRole('button', { name: '已复制' })).toBeTruthy()

    await act(async () => {
      vi.advanceTimersByTime(2100)
    })
    expect(screen.getByRole('button', { name: '复制' })).toBeTruthy()
  })

  it('says 「复制失败」 only when both paths fail, and only for two seconds', async () => {
    vi.useFakeTimers()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async () => { throw new Error('denied') } },
    })
    Object.defineProperty(document, 'execCommand', { configurable: true, value: () => false })
    render(<App />)

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '复制' }))
      await Promise.resolve()
    })
    expect(screen.getByRole('button', { name: '复制失败' })).toBeTruthy()

    // The failure is as temporary as the success: the button is a button again, not a label.
    await act(async () => {
      vi.advanceTimersByTime(2100)
    })
    expect(screen.getByRole('button', { name: '复制' })).toBeTruthy()
  })

  it('draws the account with the username initial', async () => {
    stubFetch(json(200, ACCOUNT))
    render(<App />)

    const me = await screen.findByRole('button', { name: /dev/ })
    expect(me.textContent).toContain('d')
  })
})
