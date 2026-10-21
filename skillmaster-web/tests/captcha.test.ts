import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useCaptcha } from '../src/hooks/useCaptcha'
import { clearCookies, empty, errorBody, json, setCookie, stubFetch } from './support'

/**
 * One challenge, and the rules for getting another.
 *
 * Every challenge is single use and short-lived, so the interesting behaviour here is all in what
 * happens when one comes back: an id whose picture the page no longer has is worse than no id at
 * all, because it lets somebody answer something they cannot see.
 */

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** Ask for a challenge, and let React flush what the answer did. */
async function refresh(captcha: { refresh: () => Promise<void> }): Promise<void> {
  await act(async () => {
    await captcha.refresh()
  })
}

describe('a captcha challenge', () => {
  it('keeps the id and turns the raw base64 into something an <img> can show', async () => {
    // The server sends bare base64 with no `data:` prefix; that prefix is the difference between an
    // image and a broken icon, and it is added here rather than in the template so there is one
    // place that knows it.
    stubFetch(() => json(200, { captcha_id: 'cap-1', image: 'iVBORw0KGgo=' }))
    const { result } = renderHook(() => useCaptcha())

    await refresh(result.current)

    expect(result.current.id).toBe('cap-1')
    expect(result.current.image).toBe('data:image/png;base64,iVBORw0KGgo=')
    expect(result.current.error).toBeNull()
  })

  it('drops the id along with the image when it cannot get one', async () => {
    // Not just the image: an id left behind is an answer to a picture nobody can see.
    stubFetch(() => json(429, errorBody('too_many_requests', 'Too many requests.')))
    const { result } = renderHook(() => useCaptcha())

    await refresh(result.current)

    expect(result.current.id).toBe('')
    expect(result.current.image).toBe('')
    expect(result.current.error).not.toBeNull()
  })

  it('drops the id along with the image when the challenge is not in the response', async () => {
    // A 204, or a 200 whose body is the JSON literal `null`. Both are shapes the server does not
    // send and the client can still hand back — and reading a field off either throws inside a
    // discarded promise, which leaves an empty box and no message at all.
    stubFetch(
      () => empty(204),
      () => json(200, null),
    )
    const { result } = renderHook(() => useCaptcha())

    await refresh(result.current)
    expect(result.current.id).toBe('')
    expect(result.current.image).toBe('')
    expect(result.current.error).not.toBeNull()

    await refresh(result.current)
    expect(result.current.id).toBe('')
    expect(result.current.image).toBe('')
    expect(result.current.error).not.toBeNull()
  })

  it('says why in the language the rest of the page is in', async () => {
    // The envelope's own message is written for whoever reads a log, and the server's strings are
    // English. Every other failure in this app goes through codeMessage for exactly this reason.
    stubFetch(() => json(429, errorBody('too_many_requests', 'Too many requests.')))
    const { result } = renderHook(() => useCaptcha())

    await refresh(result.current)

    expect(result.current.error).toBe('操作太频繁了，请稍后再试。')
  })

  it('clears a previous failure once a challenge does arrive', async () => {
    stubFetch(
      () => json(429, errorBody('too_many_requests', 'Too many requests.')),
      () => json(200, { captcha_id: 'cap-2', image: 'iVBORw0KGgo=' }),
    )
    const { result } = renderHook(() => useCaptcha())
    await refresh(result.current)

    await refresh(result.current)

    expect(result.current.error).toBeNull()
    expect(result.current.id).toBe('cap-2')
  })
})
