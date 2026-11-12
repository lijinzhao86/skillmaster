import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import App from '../src/App'
import { json, stubFetch } from './support'

beforeEach(() => {
  stubFetch(json(401, { error: 'unauthenticated' }))
  window.history.pushState({}, '', '/privacy')
})

afterEach(() => {
  window.history.pushState({}, '', '/')
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  document.documentElement.classList.remove('legal-open')
  document.body.classList.remove('legal-open')
})

describe('the legal pages', () => {
  it('draws the privacy policy at /privacy, with its date and version', async () => {
    render(<App />)

    expect(await screen.findByRole('heading', { level: 1, name: '隐私政策' })).toBeTruthy()
    expect(document.querySelector('.legal-meta')?.textContent).toContain('版本 1.2')
    // The deck is not here: this is a document, not the two-screen home page.
    expect(document.querySelector('.deck')).toBeNull()
    await waitFor(() => expect(document.title).toBe('隐私政策 · SkillMaster'))
  })

  it('lets the document scroll, because the home sheet locks it', () => {
    render(<App />)

    expect(document.documentElement.classList.contains('legal-open')).toBe(true)
    expect(document.body.classList.contains('legal-open')).toBe(true)
  })

  it('draws the terms at /terms', async () => {
    window.history.pushState({}, '', '/terms')
    render(<App />)

    expect(await screen.findByRole('heading', { level: 1, name: '服务条款' })).toBeTruthy()
    expect(screen.getByText('一、定义')).toBeTruthy()
  })

  it('goes back to the home page from the wordmark and the back link', () => {
    render(<App />)

    expect(document.querySelector('#brandHome')?.getAttribute('href')).toBe('/')
    expect(document.querySelector('#legalBack')?.getAttribute('href')).toBe('/')
  })

  it('links the footer to both documents', () => {
    render(<App />)

    expect(document.querySelector('#footPrivacy')?.getAttribute('href')).toBe('/privacy')
    expect(document.querySelector('#footTerms')?.getAttribute('href')).toBe('/terms')
  })
})
