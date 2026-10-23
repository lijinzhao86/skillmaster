import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, type RenderResult } from '@testing-library/react'
import RegisterPage from '../src/pages/RegisterPage'
import { clearCookies, empty, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

const CAPTCHA = { captcha_id: 'cap-1', image: 'iVBORw0KGgo=' }
const SECOND_CAPTCHA = { captcha_id: 'cap-2', image: 'iVBORw0KGgo=' }
const ACCOUNT = { user_id: '01M3HTG7GCCVBGRPAFFSVSF12W', username: 'demo-user', namespace: 'demo-user' }

const PHONE = '13800138000'
const USERNAME = 'demo-user'
const PASSWORD = 'a-long-enough-password'
const ANSWER = 'TEST'

let location = stubLocation('/register')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/register')
  // Refusals are expected in several of these, and the client logs every one of them.
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** What the server answers about a name, for the tests that leave the username field. */
const available = () => json(200, { available: true, issue: null })

/** The one request the page makes on its own, before anything has been typed. */
const CAPTCHA_QUESTION = '/web/register/code/captcha-required'

/** Answers to the one question the page asks as it opens, which is what every sequence here starts with. */
const NO_CAPTCHA_NEEDED = () => json(200, { required: false })
const CAPTCHA_NEEDED = () => json(200, { required: true })

/**
 * The register page as it opens.
 *
 * The first request is always the page asking whether the next code send will need a captcha — the
 * thing that lets a returning visitor see the captcha straight away rather than by being refused — so
 * every sequence starts with that answer and the tests that care about it say so by passing
 * {@link CAPTCHA_NEEDED} instead.
 */
async function showPage(
  ...responses: Array<() => Response | Promise<Response>>
): Promise<RenderResult & { requests: Array<{ path: string; method: string; body: unknown; headers: Record<string, string> }> }> {
  const { requests } = stubFetch(...responses)
  const view = render(<RegisterPage />)
  await flush()
  return { ...view, requests }
}

function type(container: HTMLElement, selector: string, value: string): void {
  fireEvent.change(container.querySelector(selector) as HTMLInputElement, { target: { value } })
}

function blur(container: HTMLElement, selector: string): void {
  fireEvent.blur(container.querySelector(selector) as HTMLInputElement)
}

function fillDetails(container: HTMLElement): void {
  type(container, '#username', USERNAME)
  type(container, '#phone', PHONE)
  type(container, '#password', PASSWORD)
}

/** The first screen's button: everything but the code, then the message. */
async function next(container: HTMLElement): Promise<void> {
  fireEvent.submit(container.querySelector('form') as HTMLFormElement)
  await flush()
}

async function reachTheCodeStep(container: HTMLElement): Promise<void> {
  fillDetails(container)
  await next(container)
}

async function submit(container: HTMLElement, code = '123456'): Promise<void> {
  type(container, '#sms-code', code)
  fireEvent.submit(container.querySelector('form') as HTMLFormElement)
  await flush()
}

const paths = (requests: Array<{ path: string }>) => requests.map((request) => request.path)

/** Whichever screen is showing has exactly one submit button. */
const nextButton = (container: HTMLElement) => container.querySelector('form button[type="submit"]')

/** Every field on the first screen, filled and ticked — what makes its button pressable. */
async function fillValidDetails(container: HTMLElement): Promise<void> {
  type(container, '#username', USERNAME)
  blur(container, '#username')
  await flush()
  type(container, '#phone', PHONE)
  type(container, '#password', PASSWORD)
}

const text = (element: Element | null): string => (element?.textContent ?? '').trim()

describe('the register page', () => {
  it('asks for nothing at all until the first screen is done', async () => {
    // The free send is the point of the split: an ordinary registration reads no image, and this one
    // has not even asked for a code yet. A challenge drawn on arrival would be exactly the cost the
    // whole design is arranged to avoid.
    const { container, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))

    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
    expect(container.querySelector('#captcha')).toBeNull()
    expect(container.querySelector('#sms-code')).toBeNull()
  })

  it('shows the captcha as it opens when the server says the next send will need one', async () => {
    // The second registration from one address. Finding that out by pressing the button and being
    // refused makes the first thing the visitor sees a refusal they did nothing to earn.
    const { container, requests } = await showPage(
      CAPTCHA_NEEDED,
      () => json(200, CAPTCHA),
      available,
    )

    expect(container.querySelector('#captcha')).not.toBeNull()
    expect(container.querySelector('img')?.getAttribute('src')).toBe(
      'data:image/png;base64,iVBORw0KGgo=',
    )
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)

    type(container, '#captcha', ANSWER)
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)

    await fillValidDetails(container)
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(false)
    expect(paths(requests)).toEqual([
      CAPTCHA_QUESTION,
      '/web/captcha',
      '/web/username/availability?username=demo-user',
    ])
  })

  it('draws nothing and stays usable when that question cannot be answered', async () => {
    // A failed lookup costs the click it was meant to save, and nothing more. What it must not do is
    // leave a form that cannot be finished: the refusal path is still behind the button.
    const { container } = await showPage(() =>
      json(500, errorBody('internal_error', 'the server said no')),
    )

    expect(container.querySelector('#captcha')).toBeNull()
  })

  it('sends the phone number, the code and the password under the names the server uses', async () => {
    // The one place the two halves of the wire format meet. A renamed field would be sent happily
    // and refused by the server as missing.
    const { container, requests } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(201, ACCOUNT),
    )
    await reachTheCodeStep(container)
    await submit(container)

    expect(requests[2]?.path).toBe('/web/register')
    expect(requests[2]?.body).toEqual({
      phone: PHONE,
      code: '123456',
      password: PASSWORD,
      username: USERNAME,
    })
    expect(requests[2]?.headers['X-XSRF-TOKEN']).toBe('token-one')
  })

  it('sends nothing for the captcha on that first request', async () => {
    // Null rather than empty strings: the server's rule is about the address and the day, and a
    // request that carries no answer is how the client asks whether this one is free.
    const { container, requests } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(200, CAPTCHA),
    )
    await reachTheCodeStep(container)

    expect(requests[1]?.path).toBe('/web/register/code')
    expect(requests[1]?.body).toEqual({
      phone: PHONE,
      captcha_id: null,
      captcha_answer: null,
    })
  })

  it('moves to the code on a screen of its own, naming where the message went', async () => {
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(200, CAPTCHA),
    )
    await reachTheCodeStep(container)

    expect(container.querySelector('#sms-code')).not.toBeNull()
    expect(container.querySelector('#username')).toBeNull()
    expect(container.textContent).toContain(PHONE)
  })

  it('draws nothing for a captcha on the way out, so none flickers as the screen changes', async () => {
    // Reported from a real registration: the captcha appeared for a frame and then vanished. The
    // cause was the field being made due when the send succeeded — this screen is still mounted for
    // the tick before the step changes, so it was rendered and taken away again. What is pinned here
    // is that cause: no image is asked for, and nothing on the way out makes the field due. A single
    // frame of it is not something a test can watch for.
    const { container, requests } = await showPage(NO_CAPTCHA_NEEDED, () => empty(204))
    await reachTheCodeStep(container)

    expect(container.querySelector('#captcha')).toBeNull()
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION, '/web/register/code'])
  })

  it('shows the captcha on the way back, which is where the next send will need one', async () => {
    const { container, requests } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(200, CAPTCHA),
    )
    await reachTheCodeStep(container)
    fireEvent.click(container.querySelector('button.link') as HTMLButtonElement)
    await flush()

    // A deliberate move to the screen it belongs on, so it appears where somebody is looking rather
    // than as something that arrives on its own.
    expect(container.querySelector('#captcha')).not.toBeNull()
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION, '/web/register/code', '/web/captcha'])
  })

  it('does not send a second message when the first screen is merely revisited', async () => {
    // An address's free send is spent by the first one, so being sent back to correct a username must
    // not cost a message and a captcha — the person already has a code. The walk back is the server's
    // refusal; 「重新获取验证码」 is the other thing entirely, and it does send.
    const { container, requests } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'username', issue: 'already_taken' },
          ]),
        ),
      () => available(),
    )
    await reachTheCodeStep(container)
    await submit(container)
    expect(container.querySelector('#username')).not.toBeNull()

    // The username is refused, so the button waits for it to be corrected — and the label has to say
    // what the press will do. It walks forward from here; it does not send.
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)
    type(container, '#username', 'another-name')
    blur(container, '#username')
    await flush()
    expect(text(nextButton(container))).toBe('下一步')
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(false)

    await next(container)

    expect(paths(requests)).toEqual([
      CAPTCHA_QUESTION,
      '/web/register/code',
      '/web/register',
      '/web/username/availability?username=another-name',
    ])
    expect(container.querySelector('#sms-code')).not.toBeNull()
  })

  it('keeps the person on the first screen when a resend is refused', async () => {
    // 「重新获取验证码」 really sends, so it waits for the captcha like any other send — and a refused
    // send must not open the code step claiming a message went out. `sent` cannot be asked: it stays
    // true from the first send onward, so a refusal has to be reported by the send itself.
    const { container, requests } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(200, CAPTCHA),
    )
    await reachTheCodeStep(container)
    fireEvent.click(container.querySelector('button.link') as HTMLButtonElement)
    await next(container)

    expect(paths(requests)).toEqual([CAPTCHA_QUESTION, '/web/register/code', '/web/captcha'])
    expect(container.querySelector('#sms-code')).toBeNull()
    expect(container.textContent).toContain('这一项必填。')
  })

  it('reveals the captcha when the server says this address has had its free send', async () => {
    const { container, requests } = await showPage(
      NO_CAPTCHA_NEEDED,
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'captcha', issue: 'required' },
          ]),
        ),
      () => json(200, CAPTCHA),
    )
    fillDetails(container)
    await next(container)

    expect(container.querySelector('#captcha')).not.toBeNull()
    expect(container.textContent).toContain('请先完成图形验证码')
    // Still on the first screen: the send is what the captcha stands in front of, and it did not
    // happen.
    expect(container.querySelector('#sms-code')).toBeNull()
    expect(container.querySelector('img')?.getAttribute('src')).toBe(
      'data:image/png;base64,iVBORw0KGgo=',
    )
    expect(requests.map((request) => request.path)).toEqual([
      CAPTCHA_QUESTION,
      '/web/register/code',
      '/web/captcha',
    ])
  })

  it('sends the captcha on the second attempt, once it has been asked for', async () => {
    const { container, requests } = await showPage(
      NO_CAPTCHA_NEEDED,
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'captcha', issue: 'required' },
          ]),
        ),
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )
    fillDetails(container)
    await next(container)
    type(container, '#captcha', ANSWER)
    await next(container)

    expect(requests[3]?.body).toEqual({
      phone: PHONE,
      captcha_id: 'cap-1',
      captcha_answer: ANSWER,
    })
    expect(container.querySelector('#sms-code')).not.toBeNull()
  })

  it('goes back to the first screen when the server refuses a field that lives there', async () => {
    // The narrow race: the check called the name free and somebody took it in between. Left on the
    // second screen the sentence would be about a box nobody can see.
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'username', issue: 'already_taken' },
          ]),
        ),
    )
    await reachTheCodeStep(container)
    await submit(container)

    expect(container.querySelector('#username')).not.toBeNull()
    expect(container.textContent).toContain('这个用户名已经被占用。')
  })

  it('keeps every field the server refused once it is back on the first screen', async () => {
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'phone', issue: 'already_registered' },
            { field: 'username', issue: 'already_taken' },
            { field: 'password', issue: 'too_short' },
          ]),
        ),
    )
    await reachTheCodeStep(container)
    await submit(container)

    const shown = [...container.querySelectorAll('.field-error')].map((node) => text(node))
    expect(shown).toHaveLength(3)
    expect(shown).toContain('这个手机号已经注册过了。')
    expect(shown).toContain('这个用户名已经被占用。')
    expect(container.querySelector('#username')).not.toBeNull()
  })

  it('confirms the account in place, with the namespace it can never change', async () => {
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(201, ACCOUNT),
    )
    await reachTheCodeStep(container)
    await submit(container)

    // Not a redirect: the one moment this is news is the moment it happened, and the facts come from
    // the response that just arrived rather than from a second request.
    expect(location.assign).not.toHaveBeenCalled()
    expect(text(container.querySelector('h2'))).toBe('注册成功')
    expect(container.textContent).toContain(ACCOUNT.username)
    expect(container.textContent).toContain(`${ACCOUNT.namespace}/我的技能`)
    // **The line that pointed at a gap which no longer exists.** It said the browsing and publishing
    // interface had not been built and that the CLI would eventually publish; both halves are false
    // today (`/` is the console, and publishing is a browser action — ADR 0031 §决定 2). What
    // replaces it points at the console rather than repeating that page's own empty state.
    expect(container.textContent).toContain('控制台')
    expect(container.textContent).not.toContain('还没做')
  })

  it('leaves for the site root only when asked to', async () => {
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(201, ACCOUNT),
    )
    await reachTheCodeStep(container)
    await submit(container)

    fireEvent.click(container.querySelector('button') as HTMLButtonElement)

    expect(location.assign).toHaveBeenCalledWith('/')
  })

  it('says which characters a password may use, and never leaves the first screen', async () => {
    // The rule the form states outright (ADR 0017). Full-width is the case worth pinning: it looks
    // exactly like the half-width password it is not, so the person cannot be expected to notice it
    // for themselves — the message has to.
    const { container, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    fillDetails(container)
    type(container, '#password', 'ｐａｓｓｗｏｒｄ')
    await next(container)

    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
    expect(container.textContent).toContain('密码只能使用半角英文字母、数字和符号。')
  })

  it('stops a password that is too long before spending a message', async () => {
    const { container, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    fillDetails(container)
    type(container, '#password', 'a'.repeat(73))
    await next(container)

    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
    expect(container.textContent).toContain('密码最多 72 个字符。')
  })

  it('refuses a code that is not six digits without a round trip', async () => {
    const { container, requests } = await showPage(NO_CAPTCHA_NEEDED, () => empty(204))
    await reachTheCodeStep(container)
    await submit(container, '12345')

    expect(paths(requests)).toEqual([CAPTCHA_QUESTION, '/web/register/code'])
    expect(container.textContent).toContain('请填写 6 位数字验证码。')
  })
})

/**
 * The next button, which is the form's whole statement about whether it is finished.
 *
 * Dark until every box is ticked, so a grey button and an unticked box are the same fact said twice.
 * That is also why the checks run while somebody types: the field they are still in is the one that
 * never gets left, and a button waiting for a blur would stay dark on a form with nothing wrong.
 */
describe('the next button', () => {
  it('says which boxes have to be filled, in the box and to a screen reader', async () => {
    const { container } = await showPage(NO_CAPTCHA_NEEDED)

    expect(container.querySelector('#username')?.getAttribute('placeholder')).toBe('必填')
    expect(container.querySelector('#phone')?.getAttribute('placeholder')).toBe('必填')
    expect(container.querySelector('#password')?.getAttribute('placeholder')).toBe('必填')
    // The placeholder alone carries nothing to assistive technology — support for reading one as a
    // name is inconsistent across screen readers — so the requirement is stated in the markup too.
    expect(container.querySelector('#username')?.getAttribute('aria-required')).toBe('true')
  })

  it('stays dark until every field is ticked, and lights when the last one is', async () => {
    const { container } = await showPage(NO_CAPTCHA_NEEDED, () => available())
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)

    type(container, '#username', USERNAME)
    blur(container, '#username')
    await flush()
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)

    type(container, '#phone', PHONE)
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)

    type(container, '#password', PASSWORD)

    // The last field was never left, and the button lights anyway: that is the whole reason the
    // checks run on a change and not only on a blur.
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(false)
  })

  it('goes dark again when the server reveals a captcha it has not been given', async () => {
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => available(),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'captcha', issue: 'required' },
          ]),
        ),
      () => json(200, CAPTCHA),
    )
    await fillValidDetails(container)
    await next(container)

    expect(container.querySelector('#captcha')).not.toBeNull()
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)

    type(container, '#captcha', ANSWER)
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(false)
  })

  it('keeps the second screen dark until the code is one, on the same rule', async () => {
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => available(),
      () => empty(204),
      () => json(200, CAPTCHA),
    )
    await fillValidDetails(container)
    await next(container)

    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)

    type(container, '#sms-code', '12345')
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)

    type(container, '#sms-code', '123456')
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(false)
  })
})

/**
 * The checks answering as each field is left, rather than only once the button is pressed.
 *
 * The button on the first screen is the one that spends a message, so a field that could never be
 * accepted has to be refused before it — and the username is the one that cannot be judged without
 * the server, which is why that field's answer arrives a moment later than the others'.
 */
describe('the register page, answering as fields are left', () => {
  it('says nothing about a field nobody has left yet', async () => {
    const { container, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    type(container, '#username', 'alice@example.com')

    expect(container.querySelector('.field-error')).toBeNull()
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
  })

  it('ticks a username the server says is free', async () => {
    const { container, requests } = await showPage(NO_CAPTCHA_NEEDED, () => available())
    type(container, '#username', USERNAME)
    blur(container, '#username')
    await flush()

    expect(paths(requests)).toEqual([
      CAPTCHA_QUESTION,
      '/web/username/availability?username=demo-user',
    ])

    expect(container.querySelector('.field-error')).toBeNull()
    expect(container.querySelector('.field-ok')).not.toBeNull()
  })

  it('crosses a username somebody has, in the words the submit would use', async () => {
    const { container } = await showPage(NO_CAPTCHA_NEEDED, () =>
      json(200, { available: false, issue: 'already_taken' }),
    )
    type(container, '#username', USERNAME)
    blur(container, '#username')
    await flush()

    expect(text(container.querySelector('.field-error'))).toBe('这个用户名已经被占用。')
    expect(container.querySelector('.field-ok')).toBeNull()
  })

  it('does not answer for a username it was refused the format of, without asking the server', async () => {
    // A regular expression costs nothing, so a name that could never be one never becomes a request.
    const { container, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    type(container, '#username', 'alice@example.com')
    blur(container, '#username')
    await flush()

    expect(text(container.querySelector('.field-error'))).toBe(
      '只能用 6–30 位小写字母、数字或连字符，且不能以连字符开头。',
    )
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
  })

  it('drops an answer that arrived for a value no longer on screen', async () => {
    // The one that has to be got right for an asynchronous check to be usable at all: a slow reply
    // about a name two edits ago must not land on top of the verdict for the name typed now.
    let release: (() => void) | undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        if (url.includes('username/availability')) {
          // The answer for the name that was left behind is the slow one; the answer for the name
          // on screen now comes back first, which is what makes the slow one a stale answer.
          if (url.includes('taken-name')) {
            await held
            return json(200, { available: false, issue: 'already_taken' })
          }
          return json(200, { available: true, issue: null })
        }
        return json(200, CAPTCHA)
      }),
    )

    const { container } = await showPage(NO_CAPTCHA_NEEDED)
    type(container, '#username', 'taken-name')
    blur(container, '#username')

    // The field is left again with a different name before the first answer comes back.
    type(container, '#username', 'free-name')
    await flush()
    release?.()
    await flush()

    expect(container.querySelector('.field-error')).toBeNull()
  })

  it('leaves an empty field to the next button rather than marking it on the way past', async () => {
    const { container, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    blur(container, '#username')
    blur(container, '#password')

    expect(container.querySelector('.field-error')).toBeNull()
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
  })

  it('answers the empty field once the button is pressed, and sends nothing', async () => {
    const { container, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    type(container, '#phone', PHONE)
    type(container, '#password', PASSWORD)
    await next(container)

    expect(text(container.querySelector('.field-error'))).toBe('这一项必填。')
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
  })

  it('answers a phone number that could not be one where it was typed', async () => {
    const { container, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    type(container, '#phone', '12800138000')
    blur(container, '#phone')

    expect(text(container.querySelector('.field-error'))).toBe('请填写 11 位的大陆手机号。')
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
  })

  it('ticks each field as it is filled in correctly', async () => {
    const { container } = await showPage(NO_CAPTCHA_NEEDED, () => available())
    type(container, '#username', USERNAME)
    blur(container, '#username')
    await flush()
    type(container, '#phone', PHONE)
    blur(container, '#phone')
    type(container, '#password', PASSWORD)
    blur(container, '#password')

    expect(container.querySelectorAll('.field-ok')).toHaveLength(3)
    expect(container.querySelector('.field-error')).toBeNull()
  })

  it('takes a username tick as it is typed, without the field ever being left', async () => {
    // The button on the first screen waits for this tick, and the field somebody is still in never
    // gets left — so an answer that only arrived on a blur would leave a finished form with a button
    // that never lights. The wait is the debounce: one question, not one per letter.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { container } = await showPage(NO_CAPTCHA_NEEDED, () => available())
    type(container, '#username', USERNAME)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500)
    })

    expect(container.querySelector('.field-ok')).not.toBeNull()
    vi.useRealTimers()
  })

  it('keeps what the server said about a value, until that value changes', async () => {
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => json(200, { available: false, issue: 'already_taken' }),
      () => json(200, { available: false, issue: 'already_taken' }),
    )
    type(container, '#username', USERNAME)
    blur(container, '#username')
    await flush()
    expect(container.textContent).toContain('这个用户名已经被占用。')

    // Leaving the field again takes nothing away: the refusal was about the value that was sent, and
    // tabbing past it is not a correction.
    blur(container, '#username')
    await flush()
    expect(container.textContent).toContain('这个用户名已经被占用。')

    // Changing it does, and at once rather than when the next answer arrives — the message was about
    // the name this edit just replaced, so leaving it up would have the form answering about a value
    // that is no longer in the box.
    type(container, '#username', 'another-name')
    expect(container.querySelector('.field-error')).toBeNull()
    expect(container.querySelector('.field-checking')).not.toBeNull()
  })

  it('takes away a tick that was about a value no longer in the box', async () => {
    // A tick is earned while somebody types, and it is earned about the value that was sent. Nothing
    // is said about the value that replaces it — the field was never left, and a cross means "I have
    // looked at this and it is wrong" — but saying nothing is not the same as leaving the last answer
    // standing: `demo-user` was ticked, and `demo` is a name nobody has judged.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { container } = await showPage(NO_CAPTCHA_NEEDED, () => available())
    type(container, '#username', USERNAME)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500)
    })
    expect(container.querySelector('.field-ok')).not.toBeNull()

    type(container, '#username', 'demo')

    expect(container.querySelector('.field-ok')).toBeNull()
    expect(container.querySelector('.field-error')).toBeNull()
    vi.useRealTimers()
  })

  it('takes the could-not-check sentence back when the question can be put again', async () => {
    // The sentence says what to do, and what it asks for is a change — but the cheapest way to try
    // again is to leave the field a second time, and that has to work: otherwise the only way out of
    // a dead end is editing a value that was never wrong.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => json(500, errorBody('internal_error', 'The server failed.')),
      () => available(),
    )
    type(container, '#username', USERNAME)
    blur(container, '#username')
    await flush()
    expect(container.textContent).toContain('没能确认这一项是否可用，改动一下可以重新检查。')
    expect(container.querySelector('.field-ok')).toBeNull()

    blur(container, '#username')
    await flush()

    expect(container.textContent).not.toContain('没能确认这一项是否可用，改动一下可以重新检查。')
    expect(container.querySelector('.field-ok')).not.toBeNull()

    vi.useRealTimers()
  })

  it('says so when a check could not be put, and takes that back on an edit', async () => {
    // A question that failed is not a verdict, so the field keeps neither a tick nor a cross. But the
    // first screen's button waits for an answer, and a dark button with nothing beside it is a dead
    // end: this is the sentence that says what to do, and it stands until the value changes.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { container } = await showPage(NO_CAPTCHA_NEEDED, () =>
      json(500, errorBody('internal_error', 'The server failed.')),
    )
    type(container, '#username', USERNAME)
    blur(container, '#username')
    await flush()

    expect(container.textContent).toContain('没能确认这一项是否可用，改动一下可以重新检查。')
    // No verdict either way: nothing was concluded, so there is no tick, and 「检查中…」 is gone
    // because nothing is being asked any more.
    expect(container.querySelector('.field-ok')).toBeNull()
    expect(container.querySelector('.field-checking')).toBeNull()
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)

    type(container, '#username', 'another-name')
    expect(container.textContent).not.toContain('没能确认这一项是否可用，改动一下可以重新检查。')
    expect(container.querySelector('.field-checking')).not.toBeNull()

    vi.useRealTimers()
  })

  it('really sends on the way back, since that is what the button says', async () => {
    // 「重新获取验证码」 is somebody saying the code did not arrive. Walking back to fix a username must
    // not spend a second message, but that must not be bought by making this button unable to spend
    // one either — with the two treated as the same question there was no way at all to get a second
    // code for a number, whether the first timed out or simply never turned up.
    const { container, requests } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )
    await reachTheCodeStep(container)
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION, '/web/register/code'])

    fireEvent.click(container.querySelector('button.link') as HTMLButtonElement)
    await flush()
    type(container, '#captcha', ANSWER)
    await next(container)

    expect(paths(requests)).toEqual([
      CAPTCHA_QUESTION,
      '/web/register/code',
      '/web/captcha',
      '/web/register/code',
      '/web/captcha',
    ])
    expect(requests[3]?.body).toEqual({
      phone: PHONE,
      captcha_id: 'cap-1',
      captcha_answer: ANSWER,
    })
    expect(container.querySelector('#sms-code')).not.toBeNull()
  })

  it('takes the server message away when the box it was about is emptied', async () => {
    // Same rule as an edit anywhere else: what was said was about the value that was there. An empty
    // box with 「这个手机号已经注册过了」 under it is the form answering about a number nobody can see.
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'phone', issue: 'already_registered' },
          ]),
        ),
    )
    await reachTheCodeStep(container)
    await submit(container)
    expect(container.textContent).toContain('这个手机号已经注册过了。')

    type(container, '#phone', '')

    expect(container.textContent).not.toContain('这个手机号已经注册过了。')
  })

  it('keeps what the server said about a field whose partner was edited', async () => {
    // The password's verdict reads the username, so editing the username re-states it — but that does
    // not mean the password was retyped, so it must not take away the server's answer about it. The
    // blocklist refusal is the one this matters for: the client cannot tell a blocklisted password
    // from any other, so an erased message would leave a tick over a password the submit refuses again.
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'password', issue: 'too_common' },
          ]),
        ),
      () => available(),
    )
    await reachTheCodeStep(container)
    await submit(container)
    const refusal = '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。'
    expect(container.textContent).toContain(refusal)

    type(container, '#username', 'another-name')
    blur(container, '#username')
    await flush()

    // Still the refusal, and still no tick over it: the username and phone are ticked, the password
    // keeps the one message the server sent about it.
    expect(container.textContent).toContain(refusal)
    expect(container.querySelectorAll('.field-error')).toHaveLength(1)
  })

  it('draws nothing and stays usable when the captcha question answers with an empty body', async () => {
    // A 200 whose body is JSON `null` is reachable through a proxy, and reading `.required` off it
    // would throw inside a callback nobody awaits. Saying nothing is the fallback either way, so the
    // thing to pin is that the form is still whole.
    const { container } = await showPage(() => json(200, null))

    expect(container.querySelector('#captcha')).toBeNull()
    expect(container.querySelector('#username')).not.toBeNull()
  })

  it('takes back a rule’s own refusal when the other half of the comparison is fixed', async () => {
    // The password rule reads the phone, so correcting the phone can make a password that was
    // refused perfectly good. What the rule said has to go with it — otherwise the message stays over
    // a value that is now fine, and because a message means "not fine" the button stays dark with no
    // way out but retyping a password that was never the problem.
    const { container } = await showPage(NO_CAPTCHA_NEEDED, () => available())
    type(container, '#username', USERNAME)
    blur(container, '#username')
    await flush()
    type(container, '#phone', PHONE)
    type(container, '#password', PHONE)
    blur(container, '#password')
    await flush()
    expect(container.textContent).toContain('密码不能与手机号相同。')
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)

    type(container, '#phone', '13900139000')

    expect(container.textContent).not.toContain('密码不能与手机号相同。')
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(false)
  })

  it('names the number the message actually went to when the box is edited mid-flight', async () => {
    // The request takes seconds and the phone box stays editable, so the number can change while the
    // button says 「发送中…」. Reading it back after the await would have the page announce the message
    // went to a number it never reached — and, since that is what the walk-forward compares, never
    // send one there either.
    let release: (() => void) | undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    const { container, requests } = await showPage(NO_CAPTCHA_NEEDED, async () => {
      await held
      return empty(204)
    })
    fillDetails(container)

    const pressing = next(container)
    type(container, '#phone', '13900139000')
    release?.()
    await pressing

    expect(requests[1]?.body).toEqual({ phone: PHONE, captcha_id: null, captcha_answer: null })
    expect(container.querySelector('#sms-code')).not.toBeNull()
    expect(container.textContent).toContain(`验证码已发送至 ${PHONE}`)
    expect(container.textContent).not.toContain('13900139000')
  })

  it('keeps a server refusal about the password when the username is edited around it', async () => {
    // The blocklist refusal the client cannot reproduce. Editing the username re-judges the password
    // rule, and that rule's refusal for "built out of your handle" is word for word the sentence the
    // server sends for the blocklist — so a re-judge must not overwrite the server's sentence, or the
    // next edit would take away a refusal that is still true.
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => available(),
      () => empty(204),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'password', issue: 'too_common' },
          ]),
        ),
      () => available(),
      () => available(),
    )
    type(container, '#username', 'other-name')
    blur(container, '#username')
    await flush()
    type(container, '#phone', PHONE)
    type(container, '#password', 'demouser2026')
    await next(container)
    await submit(container)

    const refusal = '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。'
    expect(container.textContent).toContain(refusal)

    // A handle the password is built out of, so the rule refuses in the same words — then back, so it
    // passes again. Neither move touched the password.
    type(container, '#username', 'demo-user')
    blur(container, '#username')
    await flush()
    type(container, '#username', 'other-name')
    blur(container, '#username')
    await flush()

    expect(container.textContent).toContain(refusal)
  })

  it('says a wait rather than a resend when the first attempt was refused', async () => {
    // Nothing has been sent, so 「N 秒后可重发」 promises a message that never went out.
    const { container } = await showPage(NO_CAPTCHA_NEEDED, () =>
      json(429, errorBody('too_many_requests', 'Slow down.')),
    )
    fillDetails(container)
    await next(container)

    expect(text(nextButton(container))).toBe('60 秒后可重试')
    expect(container.textContent).toContain('操作太频繁了')
  })

  it('keeps a server refusal when a re-check could not be put', async () => {
    // Leaving a field asks again even when nothing was typed, and that question can fail. Failing to
    // ask is not an answer, so it must not take the place of one: 「没能确认」 over a name the server
    // has already refused would be a lie about a question that was answered.
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => available(),
      () => empty(204),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'username', issue: 'already_taken' },
          ]),
        ),
      () => json(500, errorBody('internal_error', 'The server failed.')),
    )
    type(container, '#username', USERNAME)
    blur(container, '#username')
    await flush()
    type(container, '#phone', PHONE)
    type(container, '#password', PASSWORD)
    await next(container)
    await submit(container)
    expect(container.textContent).toContain('这个用户名已经被占用。')

    // Into the field and out again, changing nothing.
    blur(container, '#username')
    await flush()

    expect(container.textContent).toContain('这个用户名已经被占用。')
    expect(container.textContent).not.toContain('没能确认这一项是否可用')
  })

  it('files a refused send against the number that was sent', async () => {
    // The same rule as the submitted number on the way back: the box stays editable while the request
    // is in flight, and an answer about the number that was sent must not appear under a later one.
    let release: (() => void) | undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    const { container } = await showPage(NO_CAPTCHA_NEEDED, async () => {
      await held
      return json(
        400,
        errorBody('invalid_request', 'The request was rejected.', [
          { field: 'phone', issue: 'already_registered' },
        ]),
      )
    })
    fillDetails(container)

    const pressing = next(container)
    type(container, '#phone', '13900139000')
    release?.()
    await pressing

    expect(container.querySelector('#sms-code')).toBeNull()
    expect(container.textContent).not.toContain('这个手机号已经注册过了。')
  })

  it('goes dark when the send itself is refused', async () => {
    // A refusal no verdict of the field checks knows about: the phone is well formed, and it is the
    // server that says it already has an account. Lit, the button invites the same refused request.
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => available(),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'phone', issue: 'already_registered' },
          ]),
        ),
    )
    type(container, '#username', USERNAME)
    blur(container, '#username')
    await flush()
    type(container, '#phone', PHONE)
    type(container, '#password', PASSWORD)
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(false)

    await next(container)

    expect(container.textContent).toContain('这个手机号已经注册过了。')
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)
  })

  it('goes dark when the server refuses the code', async () => {
    // The refusal is a sentence on screen, and a sentence on screen means "not fine" — the button is
    // gated on exactly that, so leaving it lit invites the same request again.
    const { container } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(400, errorBody('verification_code_invalid', 'That code is not valid.')),
    )
    await reachTheCodeStep(container)
    type(container, '#sms-code', '123456')
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(false)

    await submit(container)

    expect(container.textContent).toContain('短信验证码不正确或已过期，请重新获取。')
    expect(nextButton(container)?.hasAttribute('disabled')).toBe(true)
  })

  it('leaves for the site root when the account is confirmed with nothing in it', async () => {
    // The account exists and the cookie is already the new session, but there is no username or
    // namespace to show. Falling through would draw the code step again over an account that was just
    // created, which reads as "not registered".
    const { container } = await showPage(NO_CAPTCHA_NEEDED, () => empty(204), () => json(201, null))
    await reachTheCodeStep(container)
    await submit(container)

    expect(location.assign).toHaveBeenCalledWith('/')
  })
})
