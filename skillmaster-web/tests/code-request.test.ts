import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { useCodeRequest } from '../src/composables/useCodeRequest'
import type { ApiResult } from '../src/api/types'
import { clearCookies, json, setCookie, stubFetch } from './support'

/**
 * The first half of both flows that send a text message.
 *
 * Driven directly, with a sender that records what it was asked, because the register and reset
 * pages already cover the requests they make: what is left is the part they cannot show — when a
 * captcha is put in front of a send, and which refusals replace the one that is there. Every
 * challenge is single use, so the two mistakes are a person retyping a picture the server has
 * forgotten, or typing an answer to a picture they can no longer see.
 *
 * The captcha's presence is the other half. Registration's first send from an address needs none, so
 * a flow that starts without one must send nothing for it and must not demand an answer; a flow that
 * always needs one says so up front. Both are covered, because the difference between them is the
 * whole of this composable's shape.
 *
 * Time is faked, and with `shouldAdvanceTime` so ordinary awaits still resolve: the countdown is a
 * minute, and nothing here waits for it.
 */

interface CodeBody {
  phone: string
  captcha_id: string | null
  captcha_answer: string | null
}

type SendCode = (body: CodeBody) => Promise<ApiResult<void>>

const PHONE = '13800138000'
const ANSWER = 'TEST'
const CAPTCHA = { captcha_id: 'cap-1', image: 'iVBORw0KGgo=' }
const SECOND_CAPTCHA = { captcha_id: 'cap-2', image: 'iVBORw0KGgo=' }

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  vi.useFakeTimers({ shouldAdvanceTime: true })
})

afterEach(() => {
  vi.useRealTimers()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function showCodeRequest(send: SendCode, options?: { captchaFromTheStart?: boolean }) {
  let code!: ReturnType<typeof useCodeRequest>
  const Host = defineComponent({
    setup() {
      code = useCodeRequest(send, options)
      return () => h('span', String(code.remaining.value))
    },
  })
  const wrapper = mount(Host)
  return { code, unmount: () => wrapper.unmount() }
}

/** A sender that answers with what the test wants, and remembers what it was asked. */
function recordingSender(...answers: Array<() => ApiResult<void>>) {
  const asked: CodeBody[] = []
  const send: SendCode = (body) => {
    asked.push(body)
    const next = answers.shift()
    if (next === undefined) {
      throw new Error(`the test sent more codes than it stubbed (${asked.length} so far)`)
    }
    return Promise.resolve(next())
  }
  return { asked, send }
}

const ok = (): ApiResult<void> => ({ ok: true, status: 204, data: undefined })

/** A refusal as the envelope delivers it: field and issue when there is one, details empty when not. */
const refused =
  (status: number, code: string, detail?: { field: string; issue: string }) =>
  (): ApiResult<void> => ({
    ok: false,
    status,
    code,
    message: `the server said: ${code}`,
    details: detail === undefined ? [] : [detail],
    retryAfterSeconds: status === 429 ? 30 : undefined,
  })

const paths = (requests: Array<{ path: string }>) => requests.map((request) => request.path)

describe('a send that has not been asked for a captcha', () => {
  it('carries nothing for it, and fetches nothing either', async () => {
    const { requests } = stubFetch(() => json(200, CAPTCHA))
    const { asked, send } = recordingSender(ok)
    const { code, unmount } = showCodeRequest(send)

    code.phone.value = PHONE
    await code.requestCode()

    expect(asked).toEqual([{ phone: PHONE, captcha_id: null, captcha_answer: null }])
    // Nothing was fetched, before or after: the free send needs no image, and it leaves nothing
    // behind that would need one. Drawing one anyway is what used to put a captcha on screen for the
    // frame between a send succeeding and the screen it belongs to being left.
    expect(requests).toHaveLength(0)
    expect(code.captchaRequired.value).toBe(false)
    unmount()
  })

  it('puts the next send behind one only when the flow asks, and draws the image then', async () => {
    const { requests } = stubFetch(() => json(200, CAPTCHA))
    const { asked, send } = recordingSender(ok, ok)
    const { code, unmount } = showCodeRequest(send)

    code.phone.value = PHONE
    await code.requestCode()
    expect(requests).toHaveLength(0)

    // What arms it is the flow, at the moment its sending screen comes back into view — so the image
    // is drawn for a screen somebody is looking at, rather than for one on its way out.
    await code.requireCaptcha()

    expect(code.captchaRequired.value).toBe(true)
    expect(paths(requests)).toEqual(['/web/captcha'])

    code.captchaAnswer.value = ANSWER
    await code.requestCode()

    expect(asked[1]).toEqual({ phone: PHONE, captcha_id: 'cap-1', captcha_answer: ANSWER })
    unmount()
  })

  it('reveals one when the server says the address has already had its free send', async () => {
    const { requests } = stubFetch(() => json(200, CAPTCHA))
    const { asked, send } = recordingSender(
      refused(400, 'invalid_request', { field: 'captcha', issue: 'required' }),
    )
    const { code, unmount } = showCodeRequest(send)

    code.phone.value = PHONE
    await code.requestCode()

    expect(code.captchaRequired.value).toBe(true)
    // Nothing the person did was wrong — the rule is about the address and the day, and only the
    // server knows where this caller stands in it — so the sentence says what to do about it.
    expect(code.problems.value.captcha).toBe('请先完成图形验证码，然后再继续。')
    expect(code.sent.value).toBe(false)
    expect(paths(requests)).toEqual(['/web/captcha'])
    expect(asked).toEqual([{ phone: PHONE, captcha_id: null, captcha_answer: null }])
    unmount()
  })
})

describe('a send that always needs a captcha', () => {
  function start(options = { captchaFromTheStart: true }) {
    return showCodeRequest(recordingSender(ok, ok).send, options)
  }

  it('shows one from the start and answers nothing without it', async () => {
    const { requests } = stubFetch(() => json(200, CAPTCHA))
    const { code, unmount } = start()

    expect(code.captchaRequired.value).toBe(true)
    expect(paths(requests)).toEqual(['/web/captcha'])

    code.phone.value = PHONE
    await code.requestCode()

    expect(code.problems.value.captcha).toBe('这一项必填。')
    expect(code.sent.value).toBe(false)
    unmount()
  })

  it('asks for nothing until it has a phone number and an answer', async () => {
    stubFetch(() => json(200, CAPTCHA))
    const { asked, send } = recordingSender()
    const { code, unmount } = showCodeRequest(send, { captchaFromTheStart: true })

    await code.requestCode()

    expect(asked).toHaveLength(0)
    expect(code.problems.value.phone).toBe('这一项必填。')
    expect(code.problems.value.captcha).toBe('这一项必填。')
    unmount()
  })

  it('sends the phone number, the challenge and the answer', async () => {
    stubFetch(
      () => json(200, CAPTCHA),
      () => json(200, SECOND_CAPTCHA),
      () => json(200, CAPTCHA),
    )
    const { asked, send } = recordingSender(ok)
    const { code, unmount } = showCodeRequest(send, { captchaFromTheStart: true })
    await code.refreshCaptcha()

    code.phone.value = PHONE
    code.captchaAnswer.value = ANSWER
    await code.requestCode()

    // The second image, which is the one on screen: the first was replaced by the explicit refresh.
    expect(asked).toEqual([{ phone: PHONE, captcha_id: 'cap-2', captcha_answer: ANSWER }])
    unmount()
  })
})

describe('what a code request does to the challenge in play', () => {
  /**
   * A request with a captcha on screen from the start, which is the case these are all about.
   *
   * Two images are spent just by arriving: one for `captchaFromTheStart`, and one for the explicit
   * refresh that stands in for a person asking for another picture. A third is stubbed for whatever
   * the request itself does; the ones a test does not reach are simply never handed out, which is
   * also what makes the count of requests below meaningful.
   */
  async function ready(send: SendCode) {
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => json(200, SECOND_CAPTCHA),
      () => json(200, CAPTCHA),
    )
    const shown = showCodeRequest(send, { captchaFromTheStart: true })
    await shown.code.refreshCaptcha()
    shown.code.phone.value = PHONE
    shown.code.captchaAnswer.value = ANSWER
    return { ...shown, requests }
  }

  it('starts the cooldown, clears the answer, and gets a new challenge', async () => {
    // All three, because a challenge is spent by being answered: keeping it would have the next
    // attempt typed into a picture the server has already forgotten.
    const { send } = recordingSender(ok)
    const { code, unmount, requests } = await ready(send)

    await code.requestCode()

    expect(code.sent.value).toBe(true)
    expect(code.remaining.value).toBe(60)
    expect(code.captchaAnswer.value).toBe('')
    expect(code.captchaImage.value).toBe('data:image/png;base64,iVBORw0KGgo=')
    expect(paths(requests)).toEqual(['/web/captcha', '/web/captcha', '/web/captcha'])
    unmount()
  })

  it('counts the cooldown down and lets the button come back', async () => {
    const { send } = recordingSender(ok)
    const { code, unmount } = await ready(send)
    await code.requestCode()

    expect(code.canSend.value).toBe(false)

    await vi.advanceTimersByTimeAsync(60_000)

    expect(code.remaining.value).toBe(0)
    expect(code.canSend.value).toBe(true)
    unmount()
  })

  it('waits however long the server said when it is throttled', async () => {
    // The server's number, not the client's own minute: it knows when its window started.
    const { send } = recordingSender(refused(429, 'too_many_requests'))
    const { code, unmount } = await ready(send)

    await code.requestCode()

    // The sentence says what happened and the countdown beside the button says how long — the two
    // used to say it between them twice over.
    expect(code.banner.value).toBe('操作太频繁了，请稍后再试。')
    expect(code.remaining.value).toBe(30)
    unmount()
  })

  it('opens the code step when the answer never came, because the message may have gone out', async () => {
    // The server spends the challenge and only then waits on the SMS provider, so a request given up
    // on is not the same as one that never happened. If the text does arrive, this is the only way
    // its recipient can use it — and the challenge is replaced so a resend is not refused for a
    // reason the person cannot see.
    const { send } = recordingSender(refused(0, 'timeout'))
    const { code, unmount } = await ready(send)

    await code.requestCode()

    expect(code.sent.value).toBe(true)
    expect(code.banner.value).toContain('没能确认短信是否发出')
    unmount()
  })

  it('gets a new challenge when the server refused that one', async () => {
    const { send } = recordingSender(
      refused(400, 'invalid_request', { field: 'captcha_answer', issue: 'invalid' }),
    )
    const { code, unmount, requests } = await ready(send)

    await code.requestCode()

    expect(code.problems.value.captcha).toBe('图形验证码不正确或已过期，已为你换了一张。')
    expect(paths(requests)).toEqual(['/web/captcha', '/web/captcha', '/web/captcha'])
    unmount()
  })

  it('leaves the challenge alone when the refusal is about something else', async () => {
    // The other half of the rule, and the one that is easy to over-apply: replacing the picture
    // after every refusal makes somebody re-read one for a reason that had nothing to do with it.
    const { send } = recordingSender(
      refused(400, 'invalid_request', { field: 'phone', issue: 'invalid_format' }),
    )
    const { code, unmount, requests } = await ready(send)

    await code.requestCode()

    expect(code.problems.value.phone).toBe('请填写 11 位的大陆手机号。')
    expect(paths(requests)).toEqual(['/web/captcha', '/web/captcha'])
    unmount()
  })

  it('says a refusal that names no field in the banner', async () => {
    const { send } = recordingSender(() => ({
      ok: false,
      status: 400,
      code: 'verification_code_invalid',
      message: 'the request was refused',
      details: [],
    }))
    const { code, unmount } = await ready(send)

    await code.requestCode()

    expect(code.banner.value).toBe('短信验证码不正确或已过期，请重新获取。')
    expect(code.problems.value).toEqual({})
    unmount()
  })
})
