import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import RegisterPage from '../src/pages/RegisterPage.vue'
import { clearCookies, empty, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

const CAPTCHA = { captcha_id: 'cap-1', image: 'iVBORw0KGgo=' }
const SECOND_CAPTCHA = { captcha_id: 'cap-2', image: 'iVBORw0KGgo=' }

const PHONE = '13800138000'
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
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function showPage(): Promise<VueWrapper> {
  const wrapper = mount(RegisterPage)
  await flush()
  return wrapper
}

/** Phone number and captcha answer, the two things the first step needs. */
async function fillFirstStep(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('#phone').setValue(PHONE)
  await wrapper.find('#captcha').setValue(ANSWER)
}

async function sendCode(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('.row button').trigger('click')
  await flush()
}

/** Everything after the code arrived: the code itself, a username and a password. */
async function fillSecondStep(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('#sms-code').setValue('123456')
  await wrapper.find('#username').setValue('demo-user')
  await wrapper.find('#password').setValue('a-long-enough-password')
}

async function submit(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('form').trigger('submit')
  await flush()
}

const paths = (requests: Array<{ path: string }>) => requests.map((request) => request.path)

describe('the register page', () => {
  it('shows a challenge as soon as it opens, rather than an empty box', async () => {
    const { requests } = stubFetch(() => json(200, CAPTCHA))

    const wrapper = await showPage()

    expect(paths(requests)).toEqual(['/web/captcha'])
    expect(wrapper.find('img').attributes('src')).toBe('data:image/png;base64,iVBORw0KGgo=')
    wrapper.unmount()
  })

  it('sends the phone number, the challenge id and the answer under the names the server uses', async () => {
    // The one place the two halves of the wire format meet. A renamed field would be sent happily
    // and refused by the server as missing.
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )

    const wrapper = await showPage()
    await fillFirstStep(wrapper)
    await sendCode(wrapper)

    expect(requests[1]?.path).toBe('/web/register/code')
    expect(requests[1]?.body).toEqual({
      phone: PHONE,
      captcha_id: 'cap-1',
      captcha_answer: ANSWER,
    })
    expect(requests[1]?.headers['X-XSRF-TOKEN']).toBe('token-one')
    wrapper.unmount()
  })

  it('gets a new challenge once a code was sent, because one is spent by being sent', async () => {
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )

    const wrapper = await showPage()
    await fillFirstStep(wrapper)
    await sendCode(wrapper)

    expect(paths(requests)).toEqual(['/web/captcha', '/web/register/code', '/web/captcha'])
    expect(wrapper.text()).toContain('秒后可重发')
    wrapper.unmount()
  })

  it('gets a new challenge when the server refused the one on screen, and says why', async () => {
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () =>
        json(
          400,
          errorBody('invalid_request', 'The request was rejected.', [
            { field: 'captcha_answer', issue: 'invalid' },
          ]),
        ),
      () => json(200, SECOND_CAPTCHA),
    )

    const wrapper = await showPage()
    await fillFirstStep(wrapper)
    await sendCode(wrapper)

    expect(paths(requests)).toEqual(['/web/captcha', '/web/register/code', '/web/captcha'])
    expect(wrapper.text()).toContain('图形验证码不正确或已过期')
    expect(wrapper.find('img').attributes('src')).toBe('data:image/png;base64,iVBORw0KGgo=')
    wrapper.unmount()
  })

  it('asks for no code at all until it has a phone number and an answer', async () => {
    const { requests } = stubFetch(() => json(200, CAPTCHA))

    const wrapper = await showPage()
    await sendCode(wrapper)

    expect(paths(requests)).toEqual(['/web/captcha'])
    expect(wrapper.text()).toContain('这一项必填。')
    wrapper.unmount()
  })

  it('shows every field the server refused, all at the same time, keeping what was typed', async () => {
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
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

    const wrapper = await showPage()
    await fillFirstStep(wrapper)
    await sendCode(wrapper)
    await fillSecondStep(wrapper)
    await submit(wrapper)

    expect(requests[3]?.path).toBe('/web/register')
    expect(requests[3]?.body).toEqual({
      phone: PHONE,
      code: '123456',
      password: 'a-long-enough-password',
      username: 'demo-user',
    })

    const shown = wrapper.findAll('.field-error').map((node) => node.text())
    expect(shown).toHaveLength(3)
    expect(shown).toContain('这个手机号已经注册过了。')
    expect(shown).toContain('这个用户名已经被占用。')
    expect(shown).toContain('太短了。')
    expect((wrapper.find('#username').element as HTMLInputElement).value).toBe('demo-user')
    wrapper.unmount()
  })

  it('says which characters a password may use, and sends nothing', async () => {
    // The rule the form states outright (ADR 0017). Full-width is the case worth pinning: it looks
    // exactly like the half-width password it is not, so the person cannot be expected to notice it
    // for themselves — the message has to.
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )

    const wrapper = await showPage()
    await fillFirstStep(wrapper)
    await sendCode(wrapper)
    await fillSecondStep(wrapper)
    await wrapper.find('#password').setValue('ｐａｓｓｗｏｒｄ')
    await submit(wrapper)

    expect(paths(requests)).toEqual(['/web/captcha', '/web/register/code', '/web/captcha'])
    expect(wrapper.text()).toContain('只能用英文字母、数字和符号')
    wrapper.unmount()
  })

  it('goes to the site root after a 201, because registering signs the account in', async () => {
    stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
      () => json(201, { user_id: '01M3HTG7GCCVBGRPAFFSVSF12W', username: 'demo-user', namespace: 'demo-user' }),
    )

    const wrapper = await showPage()
    await fillFirstStep(wrapper)
    await sendCode(wrapper)
    await fillSecondStep(wrapper)
    await submit(wrapper)

    expect(location.assign).toHaveBeenCalledWith('/')
    wrapper.unmount()
  })

  it('stops a password that is too long before the round trip', async () => {
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )

    const wrapper = await showPage()
    await fillFirstStep(wrapper)
    await sendCode(wrapper)
    await fillSecondStep(wrapper)
    await wrapper.find('#password').setValue('a'.repeat(73))
    await submit(wrapper)

    expect(paths(requests)).toEqual(['/web/captcha', '/web/register/code', '/web/captcha'])
    expect(wrapper.text()).toContain('太长了。')
    wrapper.unmount()
  })
})
