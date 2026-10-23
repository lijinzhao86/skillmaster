import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, type RenderResult } from '@testing-library/react'
import ResetPage from '../src/pages/ResetPage'
import { clearCookies, empty, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

const CAPTCHA = { captcha_id: 'cap-1', image: 'iVBORw0KGgo=' }
const SECOND_CAPTCHA = { captcha_id: 'cap-2', image: 'iVBORw0KGgo=' }

const PHONE = '13800138000'
const NEW_PASSWORD = 'a-different-long-password'

let location = stubLocation('/reset')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/reset')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function showPage(): Promise<RenderResult> {
  const view = render(<ResetPage />)
  await flush()
  return view
}

function type(container: HTMLElement, selector: string, value: string): void {
  fireEvent.change(container.querySelector(selector) as HTMLInputElement, { target: { value } })
}

async function requestCode(container: HTMLElement): Promise<void> {
  type(container, '#phone', PHONE)
  type(container, '#captcha', 'TEST')
  // The resend link sits under the boxes rather than beside them — six boxes and a button do not fit
  // one phone's width — so this is where the action lives now.
  fireEvent.click(container.querySelector('.resend') as HTMLButtonElement)
  await flush()
}

async function submit(container: HTMLElement): Promise<void> {
  type(container, '#sms-code', '123456')
  type(container, '#password', NEW_PASSWORD)
  fireEvent.submit(container.querySelector('form') as HTMLFormElement)
  await flush()
}

const paths = (requests: Array<{ path: string }>) => requests.map((request) => request.path)

const submitButton = (container: HTMLElement) => container.querySelector('button[type="submit"]')

describe('the reset page', () => {
  it('shows a challenge as soon as it opens, and asks for nothing else', async () => {
    const { requests } = stubFetch(() => json(200, CAPTCHA))

    const { container } = await showPage()

    expect(paths(requests)).toEqual(['/web/captcha'])
    expect(container.querySelector('img')?.getAttribute('src')).toBe(
      'data:image/png;base64,iVBORw0KGgo=',
    )
  })

  it('keeps the reset button shut until a code has gone out, and opens it once one has', async () => {
    // The button is gated on `sent` alone, so every field the page draws can be filled and the
    // button still be dead — which is exactly what it is for, and exactly what looks like a bug
    // from the other side of the screen. This is the assertion that pins the gate: nothing here
    // is submitted, and the only thing that moves the button is a send that came back.
    stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )

    const { container } = await showPage()

    type(container, '#phone', PHONE)
    type(container, '#captcha', 'TEST')
    type(container, '#sms-code', '123456')
    type(container, '#password', NEW_PASSWORD)
    expect(submitButton(container)?.hasAttribute('disabled')).toBe(true)

    fireEvent.click(container.querySelector('.resend') as HTMLButtonElement)
    await flush()

    expect(submitButton(container)?.hasAttribute('disabled')).toBe(false)
  })

  it('leaves the reset button shut when the send was refused', async () => {
    // The other half of the gate, and the half a person actually meets: a wrong captcha answer is
    // refused by the server, nothing was sent, and the button has to stay shut — otherwise the
    // page offers a reset the person has no code for.
    stubFetch(
      () => json(200, CAPTCHA),
      () =>
        json(
          400,
          errorBody('invalid_request', '验证码不正确。', [{ field: 'captcha', issue: 'invalid' }]),
        ),
      () => json(200, SECOND_CAPTCHA),
    )

    const { container } = await showPage()
    await requestCode(container)

    expect(submitButton(container)?.hasAttribute('disabled')).toBe(true)
    // The page's own wording rather than the server's: a refusal naming the captcha is answered
    // with what to do — the challenge has already been replaced under it.
    expect(container.textContent).toContain('图形验证码不正确或已过期')
  })

  it('asks for the code with the phone number, the challenge and the answer', async () => {
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )

    const { container } = await showPage()
    await requestCode(container)

    expect(paths(requests)).toEqual(['/web/captcha', '/web/reset/code', '/web/captcha'])
    expect(requests[1]?.body).toEqual({
      phone: PHONE,
      captcha_id: 'cap-1',
      captcha_answer: 'TEST',
    })
  })

  it('sends the new password and the code, and no username at all', async () => {
    // The one field this flow has that registration does not, and the one it lacks that
    // registration has: a reset cannot change the handle, so sending one would be a request the
    // server has no field for.
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
      () => empty(204),
    )

    const { container } = await showPage()
    await requestCode(container)
    await submit(container)

    expect(requests[3]?.path).toBe('/web/reset')
    expect(requests[3]?.body).toEqual({
      phone: PHONE,
      code: '123456',
      password: NEW_PASSWORD,
    })
  })

  it('says the password was changed and where to go, instead of signing anyone in', async () => {
    // Not signed in afterwards, on purpose. Every session the account had has just been revoked —
    // including the one that asked — and signing in with the new password is what proves to the
    // person that the password they chose is the one that works.
    stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
      () => empty(204),
    )

    const { container } = await showPage()
    await requestCode(container)
    await submit(container)

    expect(container.textContent).toContain('密码已重置')
    expect(container.querySelector('a[href="/login"]')).not.toBeNull()
    expect(container.querySelector('form')).toBeNull()
    expect(location.assign).not.toHaveBeenCalled()
  })

  it('puts a number nobody registered under the phone field', async () => {
    // `no_account` is the one refusal this flow has that registration does not: the code was real,
    // the number has no account. Rendered on the field it belongs to, and not swallowed.
    stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was refused.', [
            { field: 'phone', issue: 'no_account' },
          ]),
        ),
    )

    const { container } = await showPage()
    await requestCode(container)
    await submit(container)

    expect(container.textContent).toContain('这个手机号还没有注册。')
    expect(container.querySelector('form')).not.toBeNull()
  })

  it('puts a code the server would not accept under the code field', async () => {
    stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
      () => json(400, errorBody('verification_code_invalid', 'The code is not valid.')),
    )

    const { container } = await showPage()
    await requestCode(container)
    await submit(container)

    expect(container.textContent).toContain('短信验证码不正确或已过期，请重新获取。')
  })

  it('refuses a password that is too long before it sends anything', async () => {
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )

    const { container } = await showPage()
    await requestCode(container)
    type(container, '#sms-code', '123456')
    type(container, '#password', 'a'.repeat(73))
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    expect(paths(requests)).toEqual(['/web/captcha', '/web/reset/code', '/web/captcha'])
    expect(container.textContent).toContain('密码最多 72 个字符。')
  })
})

describe('the reset page, answering as fields are left', () => {
  it('says nothing about a password nobody has left, and answers it when they do', async () => {
    stubFetch(() => json(200, CAPTCHA))

    const { container } = await showPage()
    type(container, '#password', 'a'.repeat(73))

    expect(container.querySelector('.field-error')).toBeNull()

    fireEvent.blur(container.querySelector('#password') as HTMLInputElement)

    expect((container.querySelector('.field-error')?.textContent ?? '').trim()).toBe(
      '密码最多 72 个字符。',
    )
  })

  it('answers the phone number where it was typed', async () => {
    const { requests } = stubFetch(() => json(200, CAPTCHA))

    const { container } = await showPage()
    type(container, '#phone', '12800138000')
    fireEvent.blur(container.querySelector('#phone') as HTMLInputElement)

    expect((container.querySelector('.field-error')?.textContent ?? '').trim()).toBe(
      '请填写 11 位的大陆手机号。',
    )
    expect(paths(requests)).toEqual(['/web/captcha'])
  })

  it('takes back its own pre-check refusal when the phone is corrected', async () => {
    // This page's submit button is not gated on the verdicts, so its own pre-check is what refuses a
    // password that is not yet right — and that refusal has to be the checks' to take back, because it
    // is word for word what their rule produces. Written by this page instead, correcting the phone
    // would leave 「密码不能与手机号相同」 under a password that is now perfectly good.
    //
    // Reached by pressing the button with the password box still focused, which is the case the
    // pre-check exists for: a click would blur first and let the rule answer on its own.
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )

    const { container } = await showPage()
    await requestCode(container)
    type(container, '#sms-code', '123456')
    type(container, '#password', PHONE)
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()
    expect(container.textContent).toContain('密码不能与手机号相同。')

    type(container, '#phone', '13900139000')

    expect(container.textContent).not.toContain('密码不能与手机号相同。')
    expect(paths(requests)).toHaveLength(3)
  })

  it('keeps what the server said about a field this attempt never asks about', async () => {
    // An attempt the pre-check refuses never reaches the server, so it is in no position to take away
    // what the server said last time about some *other* field. Clearing the code box and pressing the
    // button again must not put a tick over the password the server had just refused.
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'password', issue: 'too_common' },
          ]),
        ),
    )

    const { container } = await showPage()
    await requestCode(container)
    await submit(container)
    const refusal = '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。'
    expect(container.textContent).toContain(refusal)

    type(container, '#sms-code', '')
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    expect(container.textContent).toContain('这一项必填。')
    expect(container.textContent).toContain(refusal)
    expect(paths(requests)).toHaveLength(4)
  })

  it('files a refusal against the number that was sent, not the one typed meanwhile', async () => {
    // The boxes stay editable while the request is in flight, so the answer can arrive after the value
    // it is about has been replaced. Shown then, it would sit under a number the server never looked
    // at — and be lost for the one it did.
    let release: (() => void) | undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
      async () => {
        await held
        return json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'phone', issue: 'no_account' },
          ]),
        )
      },
    )

    const { container } = await showPage()
    await requestCode(container)
    type(container, '#sms-code', '123456')
    type(container, '#password', NEW_PASSWORD)

    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    type(container, '#phone', '13900139000')
    release?.()
    await flush()

    expect(requests[3]?.body).toEqual({ phone: PHONE, code: '123456', password: NEW_PASSWORD })
    expect(container.textContent).not.toContain('这个手机号还没有注册。')
  })

  it('keeps a refusal the server never re-examined', async () => {
    // The server's use cases stop at the first failure, so an answer about the code can be an answer
    // that never got as far as the password — and the code is checked *before* it. Losing the
    // password's refusal there would put a tick over a password the next submit refuses again, which is
    // the one thing a server's refusal exists to prevent.
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'password', issue: 'too_common' },
          ]),
        ),
      () => json(400, errorBody('verification_code_invalid', 'That code is not valid.')),
    )

    const { container } = await showPage()
    await requestCode(container)
    await submit(container)
    const refusal = '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。'
    expect(container.textContent).toContain(refusal)

    // A different code, and the server refuses that instead — the password is untouched and was never
    // looked at this time.
    type(container, '#sms-code', '654321')
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    expect(container.textContent).toContain('短信验证码不正确或已过期，请重新获取。')
    expect(container.textContent).toContain(refusal)
    expect(paths(requests)).toHaveLength(5)
  })

  it('keeps what the server said about the password when the phone is corrected', async () => {
    // This page's submit is not gated on the verdicts (only on a code having been sent), and it clears
    // the whole problem map as it starts. So a message the server put there has to be recognised by its
    // wording rather than by anything remembered about the field: a note that outlives the value it was
    // about would let the next change to the phone take the server's sentence away — and the password
    // rule reads the phone, so that change re-judges it.
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'password', issue: 'too_common' },
          ]),
        ),
    )

    const { container } = await showPage()
    await requestCode(container)
    await submit(container)
    const refusal = '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。'
    expect(container.textContent).toContain(refusal)

    type(container, '#phone', '13900139000')

    expect(container.textContent).toContain(refusal)
    expect(paths(requests)).toHaveLength(4)
  })
})
