import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCaptcha } from '../src/composables/useCaptcha'
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

describe('a captcha challenge', () => {
  it('keeps the id and turns the raw base64 into something an <img> can show', async () => {
    // The server sends bare base64 with no `data:` prefix; that prefix is the difference between an
    // image and a broken icon, and it is added here rather than in the template so there is one
    // place that knows it.
    stubFetch(() => json(200, { captcha_id: 'cap-1', image: 'iVBORw0KGgo=' }))
    const captcha = useCaptcha()

    await captcha.refresh()

    expect(captcha.id.value).toBe('cap-1')
    expect(captcha.image.value).toBe('data:image/png;base64,iVBORw0KGgo=')
    expect(captcha.error.value).toBeNull()
  })

  it('drops the id along with the image when it cannot get one', async () => {
    // Not just the image: an id left behind is an answer to a picture nobody can see.
    stubFetch(() => json(429, errorBody('too_many_requests', 'Too many requests.')))
    const captcha = useCaptcha()

    await captcha.refresh()

    expect(captcha.id.value).toBe('')
    expect(captcha.image.value).toBe('')
    expect(captcha.error.value).not.toBeNull()
  })

  it('drops the id along with the image when the challenge is not in the response', async () => {
    // A 204, or a 200 whose body is the JSON literal `null`. Both are shapes the server does not
    // send and the client can still hand back — and reading a field off either throws inside a
    // discarded promise, which leaves an empty box and no message at all.
    stubFetch(() => empty(204), () => json(200, null))
    const captcha = useCaptcha()

    await captcha.refresh()
    expect(captcha.id.value).toBe('')
    expect(captcha.image.value).toBe('')
    expect(captcha.error.value).not.toBeNull()

    await captcha.refresh()
    expect(captcha.id.value).toBe('')
    expect(captcha.image.value).toBe('')
    expect(captcha.error.value).not.toBeNull()
  })

  it('says why in the language the rest of the page is in', async () => {
    // The envelope's own message is written for whoever reads a log, and the server's strings are
    // English. Every other failure in this app goes through codeMessage for exactly this reason.
    stubFetch(() => json(429, errorBody('too_many_requests', 'Too many requests.')))
    const captcha = useCaptcha()

    await captcha.refresh()

    expect(captcha.error.value).toBe('操作太频繁了，请稍后再试。')
  })

  it('clears a previous failure once a challenge does arrive', async () => {
    stubFetch(
      () => json(429, errorBody('too_many_requests', 'Too many requests.')),
      () => json(200, { captcha_id: 'cap-2', image: 'iVBORw0KGgo=' }),
    )
    const captcha = useCaptcha()
    await captcha.refresh()

    await captcha.refresh()

    expect(captcha.error.value).toBeNull()
    expect(captcha.id.value).toBe('cap-2')
  })
})
