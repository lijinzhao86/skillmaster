import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import RegisterPage from '../src/pages/RegisterPage.vue'
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
async function showPage(...responses: Array<() => Response | Promise<Response>>) {
  const { requests } = stubFetch(...responses)
  const wrapper = mount(RegisterPage)
  await flush()
  return { wrapper, requests }
}

async function fillDetails(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('#username').setValue(USERNAME)
  await wrapper.find('#phone').setValue(PHONE)
  await wrapper.find('#password').setValue(PASSWORD)
}

/** The first screen's button: everything but the code, then the message. */
async function next(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('form').trigger('submit')
  await flush()
}

async function reachTheCodeStep(wrapper: VueWrapper): Promise<void> {
  await fillDetails(wrapper)
  await next(wrapper)
}

async function submit(wrapper: VueWrapper, code = '123456'): Promise<void> {
  await wrapper.find('#sms-code').setValue(code)
  await wrapper.find('form').trigger('submit')
  await flush()
}

const paths = (requests: Array<{ path: string }>) => requests.map((request) => request.path)

/** Whichever screen is showing has exactly one submit button. */
const nextButton = (wrapper: VueWrapper) => wrapper.find('button[type="submit"]')

/** Every field on the first screen, filled and ticked — what makes its button pressable. */
async function fillValidDetails(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('#username').setValue(USERNAME)
  await wrapper.find('#username').trigger('blur')
  await flush()
  await wrapper.find('#phone').setValue(PHONE)
  await wrapper.find('#password').setValue(PASSWORD)
}

describe('the register page', () => {
  it('asks for nothing at all until the first screen is done', async () => {
    // The free send is the point of the split: an ordinary registration reads no image, and this one
    // has not even asked for a code yet. A challenge drawn on arrival would be exactly the cost the
    // whole design is arranged to avoid.
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))

    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
    expect(wrapper.find('#captcha').exists()).toBe(false)
    expect(wrapper.find('#sms-code').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows the captcha as it opens when the server says the next send will need one', async () => {
    // The second registration from one address. Finding that out by pressing the button and being
    // refused makes the first thing the visitor sees a refusal they did nothing to earn.
    const { wrapper, requests } = await showPage(
      CAPTCHA_NEEDED,
      () => json(200, CAPTCHA),
      available,
    )

    expect(wrapper.find('#captcha').exists()).toBe(true)
    expect(wrapper.find('img').attributes('src')).toBe('data:image/png;base64,iVBORw0KGgo=')
    expect(nextButton(wrapper).attributes('disabled')).toBeDefined()

    await wrapper.find('#captcha').setValue(ANSWER)
    expect(nextButton(wrapper).attributes('disabled')).toBeDefined()

    await fillValidDetails(wrapper)
    expect(nextButton(wrapper).attributes('disabled')).toBeUndefined()
    expect(paths(requests)).toEqual([
      CAPTCHA_QUESTION,
      '/web/captcha',
      '/web/username/availability?username=demo-user',
    ])
    wrapper.unmount()
  })

  it('draws nothing and stays usable when that question cannot be answered', async () => {
    // A failed lookup costs the click it was meant to save, and nothing more. What it must not do is
    // leave a form that cannot be finished: the refusal path is still behind the button.
    const { wrapper } = await showPage(() =>
      json(500, errorBody('internal_error', 'the server said no')),
    )

    expect(wrapper.find('#captcha').exists()).toBe(false)
    wrapper.unmount()
  })

  it('sends the phone number, the code and the password under the names the server uses', async () => {
    // The one place the two halves of the wire format meet. A renamed field would be sent happily
    // and refused by the server as missing.
    const { wrapper, requests } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(201, ACCOUNT),
    )
    await reachTheCodeStep(wrapper)
    await submit(wrapper)

    expect(requests[2]?.path).toBe('/web/register')
    expect(requests[2]?.body).toEqual({
      phone: PHONE,
      code: '123456',
      password: PASSWORD,
      username: USERNAME,
    })
    expect(requests[2]?.headers['X-XSRF-TOKEN']).toBe('token-one')
    wrapper.unmount()
  })

  it('sends nothing for the captcha on that first request', async () => {
    // Null rather than empty strings: the server's rule is about the address and the day, and a
    // request that carries no answer is how the client asks whether this one is free.
    const { wrapper, requests } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(200, CAPTCHA),
    )
    await reachTheCodeStep(wrapper)

    expect(requests[1]?.path).toBe('/web/register/code')
    expect(requests[1]?.body).toEqual({
      phone: PHONE,
      captcha_id: null,
      captcha_answer: null,
    })
    wrapper.unmount()
  })

  it('moves to the code on a screen of its own, naming where the message went', async () => {
    const { wrapper } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(200, CAPTCHA),
    )
    await reachTheCodeStep(wrapper)

    expect(wrapper.find('#sms-code').exists()).toBe(true)
    expect(wrapper.find('#username').exists()).toBe(false)
    expect(wrapper.text()).toContain(PHONE)
    wrapper.unmount()
  })

  it('draws nothing for a captcha on the way out, so none flickers as the screen changes', async () => {
    // Reported from a real registration: the captcha appeared for a frame and then vanished. The
    // cause was the field being made due when the send succeeded — this screen is still mounted for
    // the tick before the step changes, so it was rendered and taken away again. What is pinned here
    // is that cause: no image is asked for, and nothing on the way out makes the field due. A single
    // frame of it is not something a test can watch for.
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, () => empty(204))
    await reachTheCodeStep(wrapper)

    expect(wrapper.find('#captcha').exists()).toBe(false)
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION, '/web/register/code'])
    wrapper.unmount()
  })

  it('shows the captcha on the way back, which is where the next send will need one', async () => {
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, () => empty(204), () => json(200, CAPTCHA))
    await reachTheCodeStep(wrapper)
    await wrapper.find('button.link').trigger('click')
    await flush()

    // A deliberate move to the screen it belongs on, so it appears where somebody is looking rather
    // than as something that arrives on its own.
    expect(wrapper.find('#captcha').exists()).toBe(true)
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION, '/web/register/code', '/web/captcha'])
    wrapper.unmount()
  })

  it('does not send a second message when the first screen is merely revisited', async () => {
    // An address's free send is spent by the first one, so being sent back to correct a username must
    // not cost a message and a captcha — the person already has a code. The walk back is the server's
    // refusal; 「重新获取验证码」 is the other thing entirely, and it does send.
    const { wrapper, requests } = await showPage(
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
    await reachTheCodeStep(wrapper)
    await submit(wrapper)
    expect(wrapper.find('#username').exists()).toBe(true)

    // The username is refused, so the button waits for it to be corrected — and the label has to say
    // what the press will do. It walks forward from here; it does not send.
    expect(nextButton(wrapper).attributes('disabled')).toBeDefined()
    await wrapper.find('#username').setValue('another-name')
    await wrapper.find('#username').trigger('blur')
    await flush()
    expect(nextButton(wrapper).text()).toBe('下一步')
    expect(nextButton(wrapper).attributes('disabled')).toBeUndefined()

    await next(wrapper)

    expect(paths(requests)).toEqual([
      CAPTCHA_QUESTION,
      '/web/register/code',
      '/web/register',
      '/web/username/availability?username=another-name',
    ])
    expect(wrapper.find('#sms-code').exists()).toBe(true)
    wrapper.unmount()
  })

  it('keeps the person on the first screen when a resend is refused', async () => {
    // 「重新获取验证码」 really sends, so it waits for the captcha like any other send — and a refused
    // send must not open the code step claiming a message went out. `sent` cannot be asked: it stays
    // true from the first send onward, so a refusal has to be reported by the send itself.
    const { wrapper, requests } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(200, CAPTCHA),
    )
    await reachTheCodeStep(wrapper)
    await wrapper.find('button.link').trigger('click')
    await next(wrapper)

    expect(paths(requests)).toEqual([CAPTCHA_QUESTION, '/web/register/code', '/web/captcha'])
    expect(wrapper.find('#sms-code').exists()).toBe(false)
    expect(wrapper.text()).toContain('这一项必填。')
    wrapper.unmount()
  })

  it('reveals the captcha when the server says this address has had its free send', async () => {
    const { wrapper, requests } = await showPage(
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
    await fillDetails(wrapper)
    await next(wrapper)

    expect(wrapper.find('#captcha').exists()).toBe(true)
    expect(wrapper.text()).toContain('请先完成图形验证码')
    // Still on the first screen: the send is what the captcha stands in front of, and it did not
    // happen.
    expect(wrapper.find('#sms-code').exists()).toBe(false)
    expect(wrapper.find('img').attributes('src')).toBe('data:image/png;base64,iVBORw0KGgo=')
    expect(requests.map((request) => request.path)).toEqual([
      CAPTCHA_QUESTION,
      '/web/register/code',
      '/web/captcha',
    ])
    wrapper.unmount()
  })

  it('sends the captcha on the second attempt, once it has been asked for', async () => {
    const { wrapper, requests } = await showPage(
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
    await fillDetails(wrapper)
    await next(wrapper)
    await wrapper.find('#captcha').setValue(ANSWER)
    await next(wrapper)

    expect(requests[3]?.body).toEqual({
      phone: PHONE,
      captcha_id: 'cap-1',
      captcha_answer: ANSWER,
    })
    expect(wrapper.find('#sms-code').exists()).toBe(true)
    wrapper.unmount()
  })

  it('goes back to the first screen when the server refuses a field that lives there', async () => {
    // The narrow race: the check called the name free and somebody took it in between. Left on the
    // second screen the sentence would be about a box nobody can see.
    const { wrapper } = await showPage(
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
    await reachTheCodeStep(wrapper)
    await submit(wrapper)

    expect(wrapper.find('#username').exists()).toBe(true)
    expect(wrapper.text()).toContain('这个用户名已经被占用。')
    wrapper.unmount()
  })

  it('keeps every field the server refused once it is back on the first screen', async () => {
    const { wrapper } = await showPage(
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
    await reachTheCodeStep(wrapper)
    await submit(wrapper)

    const shown = wrapper.findAll('.field-error').map((node) => node.text())
    expect(shown).toHaveLength(3)
    expect(shown).toContain('这个手机号已经注册过了。')
    expect(shown).toContain('这个用户名已经被占用。')
    expect(wrapper.find('#username').exists()).toBe(true)
    wrapper.unmount()
  })

  it('confirms the account in place, with the namespace it can never change', async () => {
    const { wrapper } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(201, ACCOUNT),
    )
    await reachTheCodeStep(wrapper)
    await submit(wrapper)

    // Not a redirect: the one moment this is news is the moment it happened, and the facts come from
    // the response that just arrived rather than from a second request.
    expect(location.assign).not.toHaveBeenCalled()
    expect(wrapper.find('h2').text()).toBe('注册成功')
    expect(wrapper.text()).toContain(ACCOUNT.username)
    expect(wrapper.text()).toContain(`${ACCOUNT.namespace}/我的技能`)
    wrapper.unmount()
  })

  it('leaves for the site root only when asked to', async () => {
    const { wrapper } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(201, ACCOUNT),
    )
    await reachTheCodeStep(wrapper)
    await submit(wrapper)

    await wrapper.find('button').trigger('click')

    expect(location.assign).toHaveBeenCalledWith('/')
    wrapper.unmount()
  })

  it('says which characters a password may use, and never leaves the first screen', async () => {
    // The rule the form states outright (ADR 0017). Full-width is the case worth pinning: it looks
    // exactly like the half-width password it is not, so the person cannot be expected to notice it
    // for themselves — the message has to.
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    await fillDetails(wrapper)
    await wrapper.find('#password').setValue('ｐａｓｓｗｏｒｄ')
    await next(wrapper)

    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
    expect(wrapper.text()).toContain('密码只能使用半角英文字母、数字和符号。')
    wrapper.unmount()
  })

  it('stops a password that is too long before spending a message', async () => {
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    await fillDetails(wrapper)
    await wrapper.find('#password').setValue('a'.repeat(73))
    await next(wrapper)

    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
    expect(wrapper.text()).toContain('密码最多 72 个字符。')
    wrapper.unmount()
  })

  it('refuses a code that is not six digits without a round trip', async () => {
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, () => empty(204))
    await reachTheCodeStep(wrapper)
    await submit(wrapper, '12345')

    expect(paths(requests)).toEqual([CAPTCHA_QUESTION, '/web/register/code'])
    expect(wrapper.text()).toContain('请填写 6 位数字验证码。')
    wrapper.unmount()
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
    const { wrapper } = await showPage(NO_CAPTCHA_NEEDED)

    expect(wrapper.find('#username').attributes('placeholder')).toBe('必填')
    expect(wrapper.find('#phone').attributes('placeholder')).toBe('必填')
    expect(wrapper.find('#password').attributes('placeholder')).toBe('必填')
    // The placeholder alone carries nothing to assistive technology — support for reading one as a
    // name is inconsistent across screen readers — so the requirement is stated in the markup too.
    expect(wrapper.find('#username').attributes('aria-required')).toBe('true')
    wrapper.unmount()
  })

  it('stays dark until every field is ticked, and lights when the last one is', async () => {
    const { wrapper } = await showPage(NO_CAPTCHA_NEEDED, () => available())
    expect(nextButton(wrapper).attributes('disabled')).toBeDefined()

    await wrapper.find('#username').setValue(USERNAME)
    await wrapper.find('#username').trigger('blur')
    await flush()
    expect(nextButton(wrapper).attributes('disabled')).toBeDefined()

    await wrapper.find('#phone').setValue(PHONE)
    expect(nextButton(wrapper).attributes('disabled')).toBeDefined()

    await wrapper.find('#password').setValue(PASSWORD)

    // The last field was never left, and the button lights anyway: that is the whole reason the
    // checks run on a change and not only on a blur.
    expect(nextButton(wrapper).attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('goes dark again when the server reveals a captcha it has not been given', async () => {
    const { wrapper } = await showPage(
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
    await fillValidDetails(wrapper)
    await next(wrapper)

    expect(wrapper.find('#captcha').exists()).toBe(true)
    expect(nextButton(wrapper).attributes('disabled')).toBeDefined()

    await wrapper.find('#captcha').setValue(ANSWER)
    expect(nextButton(wrapper).attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('keeps the second screen dark until the code is one, on the same rule', async () => {
    const { wrapper } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => available(),
      () => empty(204),
      () => json(200, CAPTCHA),
    )
    await fillValidDetails(wrapper)
    await next(wrapper)

    const submit = wrapper.find('form button[type="submit"]')
    expect(submit.attributes('disabled')).toBeDefined()

    await wrapper.find('#sms-code').setValue('12345')
    expect(submit.attributes('disabled')).toBeDefined()

    await wrapper.find('#sms-code').setValue('123456')
    expect(submit.attributes('disabled')).toBeUndefined()
    wrapper.unmount()
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
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    await wrapper.find('#username').setValue('alice@example.com')

    expect(wrapper.find('.field-error').exists()).toBe(false)
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
    wrapper.unmount()
  })

  it('ticks a username the server says is free', async () => {
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, () => available())
    await wrapper.find('#username').setValue(USERNAME)
    await wrapper.find('#username').trigger('blur')
    await flush()

    expect(paths(requests)).toEqual([
      CAPTCHA_QUESTION,
      '/web/username/availability?username=demo-user',
    ])

    expect(wrapper.find('.field-error').exists()).toBe(false)
    expect(wrapper.find('.field-ok').exists()).toBe(true)
    wrapper.unmount()
  })

  it('crosses a username somebody has, in the words the submit would use', async () => {
    const { wrapper } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, { available: false, issue: 'already_taken' }))
    await wrapper.find('#username').setValue(USERNAME)
    await wrapper.find('#username').trigger('blur')
    await flush()

    expect(wrapper.find('.field-error').text()).toBe('这个用户名已经被占用。')
    expect(wrapper.find('.field-ok').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not answer for a username it was refused the format of, without asking the server', async () => {
    // A regular expression costs nothing, so a name that could never be one never becomes a request.
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    await wrapper.find('#username').setValue('alice@example.com')
    await wrapper.find('#username').trigger('blur')
    await flush()

    expect(wrapper.find('.field-error').text()).toBe(
      '只能用 6–30 位小写字母、数字或连字符，且不能以连字符开头。',
    )
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
    wrapper.unmount()
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

    const { wrapper } = await showPage(NO_CAPTCHA_NEEDED)
    await wrapper.find('#username').setValue('taken-name')
    await wrapper.find('#username').trigger('blur')

    // The field is left again with a different name before the first answer comes back.
    await wrapper.find('#username').setValue('free-name')
    await flush()
    release?.()
    await flush()

    expect(wrapper.find('.field-error').exists()).toBe(false)
    wrapper.unmount()
  })

  it('leaves an empty field to the next button rather than marking it on the way past', async () => {
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    await wrapper.find('#username').trigger('blur')
    await wrapper.find('#password').trigger('blur')

    expect(wrapper.find('.field-error').exists()).toBe(false)
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
    wrapper.unmount()
  })

  it('answers the empty field once the button is pressed, and sends nothing', async () => {
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    await wrapper.find('#phone').setValue(PHONE)
    await wrapper.find('#password').setValue(PASSWORD)
    await next(wrapper)

    expect(wrapper.find('.field-error').text()).toBe('这一项必填。')
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
    wrapper.unmount()
  })

  it('answers a phone number that could not be one where it was typed', async () => {
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, () => json(200, CAPTCHA))
    await wrapper.find('#phone').setValue('12800138000')
    await wrapper.find('#phone').trigger('blur')

    expect(wrapper.find('.field-error').text()).toBe('请填写 11 位的大陆手机号。')
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION])
    wrapper.unmount()
  })

  it('ticks each field as it is filled in correctly', async () => {
    const { wrapper } = await showPage(NO_CAPTCHA_NEEDED, () => available())
    await wrapper.find('#username').setValue(USERNAME)
    await wrapper.find('#username').trigger('blur')
    await flush()
    await wrapper.find('#phone').setValue(PHONE)
    await wrapper.find('#phone').trigger('blur')
    await wrapper.find('#password').setValue(PASSWORD)
    await wrapper.find('#password').trigger('blur')

    expect(wrapper.findAll('.field-ok')).toHaveLength(3)
    expect(wrapper.find('.field-error').exists()).toBe(false)
    wrapper.unmount()
  })

  it('takes a username tick as it is typed, without the field ever being left', async () => {
    // The button on the first screen waits for this tick, and the field somebody is still in never
    // gets left — so an answer that only arrived on a blur would leave a finished form with a button
    // that never lights. The wait is the debounce: one question, not one per letter.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { wrapper } = await showPage(NO_CAPTCHA_NEEDED, () => available())
    await wrapper.find('#username').setValue(USERNAME)
    await vi.advanceTimersByTimeAsync(500)

    expect(wrapper.find('.field-ok').exists()).toBe(true)
    vi.useRealTimers()
    wrapper.unmount()
  })

  it('keeps what the server said about a value, until that value changes', async () => {
    const { wrapper } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => json(200, { available: false, issue: 'already_taken' }),
      () => json(200, { available: false, issue: 'already_taken' }),
    )
    await wrapper.find('#username').setValue(USERNAME)
    await wrapper.find('#username').trigger('blur')
    await flush()
    expect(wrapper.text()).toContain('这个用户名已经被占用。')

    // Leaving the field again takes nothing away: the refusal was about the value that was sent, and
    // tabbing past it is not a correction.
    await wrapper.find('#username').trigger('blur')
    await flush()
    expect(wrapper.text()).toContain('这个用户名已经被占用。')

    // Changing it does, and at once rather than when the next answer arrives — the message was about
    // the name this edit just replaced, so leaving it up would have the form answering about a value
    // that is no longer in the box.
    await wrapper.find('#username').setValue('another-name')
    expect(wrapper.find('.field-error').exists()).toBe(false)
    expect(wrapper.find('.field-checking').exists()).toBe(true)
    wrapper.unmount()
  })

  it('takes away a tick that was about a value no longer in the box', async () => {
    // A tick is earned while somebody types, and it is earned about the value that was sent. Nothing
    // is said about the value that replaces it — the field was never left, and a cross means "I have
    // looked at this and it is wrong" — but saying nothing is not the same as leaving the last answer
    // standing: `demo-user` was ticked, and `demo` is a name nobody has judged.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { wrapper } = await showPage(NO_CAPTCHA_NEEDED, () => available())
    await wrapper.find('#username').setValue(USERNAME)
    await vi.advanceTimersByTimeAsync(500)
    expect(wrapper.find('.field-ok').exists()).toBe(true)

    await wrapper.find('#username').setValue('demo')

    expect(wrapper.find('.field-ok').exists()).toBe(false)
    expect(wrapper.find('.field-error').exists()).toBe(false)
    wrapper.unmount()
    vi.useRealTimers()
  })

  it('takes the could-not-check sentence back when the question can be put again', async () => {
    // The sentence says what to do, and what it asks for is a change — but the cheapest way to try
    // again is to leave the field a second time, and that has to work: otherwise the only way out of
    // a dead end is editing a value that was never wrong.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { wrapper } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => json(500, errorBody('internal_error', 'The server failed.')),
      () => available(),
    )
    await wrapper.find('#username').setValue(USERNAME)
    await wrapper.find('#username').trigger('blur')
    await flush()
    expect(wrapper.text()).toContain('没能确认这一项是否可用，改动一下可以重新检查。')
    expect(wrapper.find('.field-ok').exists()).toBe(false)

    await wrapper.find('#username').trigger('blur')
    await flush()

    expect(wrapper.text()).not.toContain('没能确认这一项是否可用，改动一下可以重新检查。')
    expect(wrapper.find('.field-ok').exists()).toBe(true)

    wrapper.unmount()
    vi.useRealTimers()
  })

  it('says so when a check could not be put, and takes that back on an edit', async () => {
    // A question that failed is not a verdict, so the field keeps neither a tick nor a cross. But the
    // first screen's button waits for an answer, and a dark button with nothing beside it is a dead
    // end: this is the sentence that says what to do, and it stands until the value changes.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { wrapper } = await showPage(NO_CAPTCHA_NEEDED, () =>
      json(500, errorBody('internal_error', 'The server failed.')),
    )
    await wrapper.find('#username').setValue(USERNAME)
    await wrapper.find('#username').trigger('blur')
    await flush()

    expect(wrapper.text()).toContain('没能确认这一项是否可用，改动一下可以重新检查。')
    // No verdict either way: nothing was concluded, so there is no tick, and 「检查中…」 is gone
    // because nothing is being asked any more.
    expect(wrapper.find('.field-ok').exists()).toBe(false)
    expect(wrapper.find('.field-checking').exists()).toBe(false)
    expect(nextButton(wrapper).attributes('disabled')).toBeDefined()

    await wrapper.find('#username').setValue('another-name')
    expect(wrapper.text()).not.toContain('没能确认这一项是否可用，改动一下可以重新检查。')
    expect(wrapper.find('.field-checking').exists()).toBe(true)

    wrapper.unmount()
    vi.useRealTimers()
  })

  it('really sends on the way back, since that is what the button says', async () => {
    // 「重新获取验证码」 is somebody saying the code did not arrive. Walking back to fix a username must
    // not spend a second message, but that must not be bought by making this button unable to spend
    // one either — with the two treated as the same question there was no way at all to get a second
    // code for a number, whether the first timed out or simply never turned up.
    const { wrapper, requests } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )
    await reachTheCodeStep(wrapper)
    expect(paths(requests)).toEqual([CAPTCHA_QUESTION, '/web/register/code'])

    await wrapper.find('button.link').trigger('click')
    await flush()
    await wrapper.find('#captcha').setValue(ANSWER)
    await next(wrapper)

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
    expect(wrapper.find('#sms-code').exists()).toBe(true)
    wrapper.unmount()
  })

  it('takes the server message away when the box it was about is emptied', async () => {
    // Same rule as an edit anywhere else: what was said was about the value that was there. An empty
    // box with 「这个手机号已经注册过了」 under it is the form answering about a number nobody can see.
    const { wrapper } = await showPage(
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
    await reachTheCodeStep(wrapper)
    await submit(wrapper)
    expect(wrapper.text()).toContain('这个手机号已经注册过了。')

    await wrapper.find('#phone').setValue('')

    expect(wrapper.text()).not.toContain('这个手机号已经注册过了。')
    wrapper.unmount()
  })

  it('keeps what the server said about a field whose partner was edited', async () => {
    // The password's verdict reads the username, so editing the username re-states it — but that does
    // not mean the password was retyped, so it must not take away the server's answer about it. The
    // blocklist refusal is the one this matters for: the client cannot tell a blocklisted password
    // from any other, so an erased message would leave a tick over a password the submit refuses again.
    const { wrapper } = await showPage(
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
    await reachTheCodeStep(wrapper)
    await submit(wrapper)
    const refusal = '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。'
    expect(wrapper.text()).toContain(refusal)

    await wrapper.find('#username').setValue('another-name')
    await wrapper.find('#username').trigger('blur')
    await flush()

    // Still the refusal, and still no tick over it: the username and phone are ticked, the password
    // keeps the one message the server sent about it.
    expect(wrapper.text()).toContain(refusal)
    expect(wrapper.findAll('.field-error')).toHaveLength(1)
    wrapper.unmount()
  })

  it('draws nothing and stays usable when the captcha question answers with an empty body', async () => {
    // A 200 whose body is JSON `null` is reachable through a proxy, and reading `.required` off it
    // would throw inside a callback nobody awaits. Saying nothing is the fallback either way, so the
    // thing to pin is that the form is still whole.
    const { wrapper } = await showPage(() => json(200, null))

    expect(wrapper.find('#captcha').exists()).toBe(false)
    expect(wrapper.find('#username').exists()).toBe(true)
    wrapper.unmount()
  })

  it('takes back a rule’s own refusal when the other half of the comparison is fixed', async () => {
    // The password rule reads the phone, so correcting the phone can make a password that was
    // refused perfectly good. What the rule said has to go with it — otherwise the message stays over
    // a value that is now fine, and because a message means "not fine" the button stays dark with no
    // way out but retyping a password that was never the problem.
    const { wrapper } = await showPage(NO_CAPTCHA_NEEDED, () => available())
    await wrapper.find('#username').setValue(USERNAME)
    await wrapper.find('#username').trigger('blur')
    await flush()
    await wrapper.find('#phone').setValue(PHONE)
    await wrapper.find('#password').setValue(PHONE)
    await wrapper.find('#password').trigger('blur')
    await flush()
    expect(wrapper.text()).toContain('密码不能与手机号相同。')
    expect(nextButton(wrapper).attributes('disabled')).toBeDefined()

    await wrapper.find('#phone').setValue('13900139000')

    expect(wrapper.text()).not.toContain('密码不能与手机号相同。')
    expect(nextButton(wrapper).attributes('disabled')).toBeUndefined()
    wrapper.unmount()
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
    const { wrapper, requests } = await showPage(NO_CAPTCHA_NEEDED, async () => {
      await held
      return empty(204)
    })
    await fillDetails(wrapper)

    const pressing = next(wrapper)
    await wrapper.find('#phone').setValue('13900139000')
    release?.()
    await pressing

    expect(requests[1]?.body).toEqual({ phone: PHONE, captcha_id: null, captcha_answer: null })
    expect(wrapper.find('#sms-code').exists()).toBe(true)
    expect(wrapper.text()).toContain(`验证码已发送至 ${PHONE}`)
    expect(wrapper.text()).not.toContain('13900139000')
    wrapper.unmount()
  })

  it('keeps a server refusal about the password when the username is edited around it', async () => {
    // The blocklist refusal the client cannot reproduce. Editing the username re-judges the password
    // rule, and that rule's refusal for "built out of your handle" is word for word the sentence the
    // server sends for the blocklist — so a re-judge must not overwrite the server's sentence, or the
    // next edit would take away a refusal that is still true.
    const { wrapper } = await showPage(
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
    await wrapper.find('#username').setValue('other-name')
    await wrapper.find('#username').trigger('blur')
    await flush()
    await wrapper.find('#phone').setValue(PHONE)
    await wrapper.find('#password').setValue('demouser2026')
    await next(wrapper)
    await submit(wrapper)

    const refusal = '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。'
    expect(wrapper.text()).toContain(refusal)

    // A handle the password is built out of, so the rule refuses in the same words — then back, so it
    // passes again. Neither move touched the password.
    await wrapper.find('#username').setValue('demo-user')
    await wrapper.find('#username').trigger('blur')
    await flush()
    await wrapper.find('#username').setValue('other-name')
    await wrapper.find('#username').trigger('blur')
    await flush()

    expect(wrapper.text()).toContain(refusal)
    wrapper.unmount()
  })

  it('says a wait rather than a resend when the first attempt was refused', async () => {
    // Nothing has been sent, so 「N 秒后可重发」 promises a message that never went out.
    const { wrapper } = await showPage(NO_CAPTCHA_NEEDED, () =>
      json(429, errorBody('too_many_requests', 'Slow down.')),
    )
    await fillDetails(wrapper)
    await next(wrapper)

    expect(nextButton(wrapper).text()).toBe('60 秒后可重试')
    expect(wrapper.text()).toContain('操作太频繁了')
    wrapper.unmount()
  })

  it('keeps a server refusal when a re-check could not be put', async () => {
    // Leaving a field asks again even when nothing was typed, and that question can fail. Failing to
    // ask is not an answer, so it must not take the place of one: 「没能确认」 over a name the server
    // has already refused would be a lie about a question that was answered.
    const { wrapper } = await showPage(
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
    await wrapper.find('#username').setValue(USERNAME)
    await wrapper.find('#username').trigger('blur')
    await flush()
    await wrapper.find('#phone').setValue(PHONE)
    await wrapper.find('#password').setValue(PASSWORD)
    await next(wrapper)
    await submit(wrapper)
    expect(wrapper.text()).toContain('这个用户名已经被占用。')

    // Into the field and out again, changing nothing.
    await wrapper.find('#username').trigger('blur')
    await flush()

    expect(wrapper.text()).toContain('这个用户名已经被占用。')
    expect(wrapper.text()).not.toContain('没能确认这一项是否可用')
    wrapper.unmount()
  })

  it('files a refused send against the number that was sent', async () => {
    // The same rule as the submitted number on the way back: the box stays editable while the request
    // is in flight, and an answer about the number that was sent must not appear under a later one.
    let release: (() => void) | undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    const { wrapper } = await showPage(NO_CAPTCHA_NEEDED, async () => {
      await held
      return json(
        400,
        errorBody('invalid_request', 'The request was rejected.', [
          { field: 'phone', issue: 'already_registered' },
        ]),
      )
    })
    await fillDetails(wrapper)

    const pressing = next(wrapper)
    await wrapper.find('#phone').setValue('13900139000')
    release?.()
    await pressing

    expect(wrapper.find('#sms-code').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('这个手机号已经注册过了。')
    wrapper.unmount()
  })

  it('goes dark when the send itself is refused', async () => {
    // A refusal no verdict of the field checks knows about: the phone is well formed, and it is the
    // server that says it already has an account. Lit, the button invites the same refused request.
    const { wrapper } = await showPage(
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
    await wrapper.find('#username').setValue(USERNAME)
    await wrapper.find('#username').trigger('blur')
    await flush()
    await wrapper.find('#phone').setValue(PHONE)
    await wrapper.find('#password').setValue(PASSWORD)
    expect(nextButton(wrapper).attributes('disabled')).toBeUndefined()

    await next(wrapper)

    expect(wrapper.text()).toContain('这个手机号已经注册过了。')
    expect(nextButton(wrapper).attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('goes dark when the server refuses the code', async () => {
    // The refusal is a sentence on screen, and a sentence on screen means "not fine" — the button is
    // gated on exactly that, so leaving it lit invites the same request again.
    const { wrapper } = await showPage(
      NO_CAPTCHA_NEEDED,
      () => empty(204),
      () => json(400, errorBody('verification_code_invalid', 'That code is not valid.')),
    )
    await reachTheCodeStep(wrapper)
    await wrapper.find('#sms-code').setValue('123456')
    expect(nextButton(wrapper).attributes('disabled')).toBeUndefined()

    await submit(wrapper)

    expect(wrapper.text()).toContain('短信验证码不正确或已过期，请重新获取。')
    expect(nextButton(wrapper).attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('leaves for the site root when the account is confirmed with nothing in it', async () => {
    // The account exists and the cookie is already the new session, but there is no username or
    // namespace to show. Falling through would draw the code step again over an account that was just
    // created, which reads as "not registered".
    const { wrapper } = await showPage(NO_CAPTCHA_NEEDED, () => empty(204), () => json(201, null))
    await reachTheCodeStep(wrapper)
    await submit(wrapper)

    expect(location.assign).toHaveBeenCalledWith('/')
    wrapper.unmount()
  })
})
