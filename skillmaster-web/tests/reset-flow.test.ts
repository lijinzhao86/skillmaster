import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import ResetPage from '../src/pages/ResetPage.vue'
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

async function showPage(): Promise<VueWrapper> {
  const wrapper = mount(ResetPage)
  await flush()
  return wrapper
}

async function requestCode(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('#phone').setValue(PHONE)
  await wrapper.find('#captcha').setValue('TEST')
  // The resend link sits under the boxes rather than beside them — six boxes and a button do not fit
  // one phone's width — so this is where the action lives now.
  await wrapper.find('.resend').trigger('click')
  await flush()
}

async function submit(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('#sms-code').setValue('123456')
  await wrapper.find('#password').setValue(NEW_PASSWORD)
  await wrapper.find('form').trigger('submit')
  await flush()
}

const paths = (requests: Array<{ path: string }>) => requests.map((request) => request.path)

describe('the reset page', () => {
  it('shows a challenge as soon as it opens, and asks for nothing else', async () => {
    const { requests } = stubFetch(() => json(200, CAPTCHA))

    const wrapper = await showPage()

    expect(paths(requests)).toEqual(['/web/captcha'])
    expect(wrapper.find('img').attributes('src')).toBe('data:image/png;base64,iVBORw0KGgo=')
    wrapper.unmount()
  })

  it('asks for the code with the phone number, the challenge and the answer', async () => {
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )

    const wrapper = await showPage()
    await requestCode(wrapper)

    expect(paths(requests)).toEqual(['/web/captcha', '/web/reset/code', '/web/captcha'])
    expect(requests[1]?.body).toEqual({
      phone: PHONE,
      captcha_id: 'cap-1',
      captcha_answer: 'TEST',
    })
    wrapper.unmount()
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

    const wrapper = await showPage()
    await requestCode(wrapper)
    await submit(wrapper)

    expect(requests[3]?.path).toBe('/web/reset')
    expect(requests[3]?.body).toEqual({
      phone: PHONE,
      code: '123456',
      password: NEW_PASSWORD,
    })
    wrapper.unmount()
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

    const wrapper = await showPage()
    await requestCode(wrapper)
    await submit(wrapper)

    expect(wrapper.text()).toContain('密码已重置')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(true)
    expect(wrapper.find('form').exists()).toBe(false)
    expect(location.assign).not.toHaveBeenCalled()
    wrapper.unmount()
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

    const wrapper = await showPage()
    await requestCode(wrapper)
    await submit(wrapper)

    expect(wrapper.text()).toContain('这个手机号还没有注册。')
    expect(wrapper.find('form').exists()).toBe(true)
    wrapper.unmount()
  })

  it('puts a code the server would not accept under the code field', async () => {
    stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
      () => json(400, errorBody('verification_code_invalid', 'The code is not valid.')),
    )

    const wrapper = await showPage()
    await requestCode(wrapper)
    await submit(wrapper)

    expect(wrapper.text()).toContain('短信验证码不正确或已过期，请重新获取。')
    wrapper.unmount()
  })

  it('refuses a password that is too long before it sends anything', async () => {
    const { requests } = stubFetch(
      () => json(200, CAPTCHA),
      () => empty(204),
      () => json(200, SECOND_CAPTCHA),
    )

    const wrapper = await showPage()
    await requestCode(wrapper)
    await wrapper.find('#sms-code').setValue('123456')
    await wrapper.find('#password').setValue('a'.repeat(73))
    await wrapper.find('form').trigger('submit')
    await flush()

    expect(paths(requests)).toEqual(['/web/captcha', '/web/reset/code', '/web/captcha'])
    expect(wrapper.text()).toContain('密码最多 72 个字符。')
    wrapper.unmount()
  })
})

describe('the reset page, answering as fields are left', () => {
  it('says nothing about a password nobody has left, and answers it when they do', async () => {
    stubFetch(() => json(200, CAPTCHA))

    const wrapper = await showPage()
    await wrapper.find('#password').setValue('a'.repeat(73))

    expect(wrapper.find('.field-error').exists()).toBe(false)

    await wrapper.find('#password').trigger('blur')

    expect(wrapper.find('.field-error').text()).toBe('密码最多 72 个字符。')
    wrapper.unmount()
  })

  it('answers the phone number where it was typed', async () => {
    const { requests } = stubFetch(() => json(200, CAPTCHA))

    const wrapper = await showPage()
    await wrapper.find('#phone').setValue('12800138000')
    await wrapper.find('#phone').trigger('blur')

    expect(wrapper.find('.field-error').text()).toBe('请填写 11 位的大陆手机号。')
    expect(paths(requests)).toEqual(['/web/captcha'])
    wrapper.unmount()
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

    const wrapper = await showPage()
    await requestCode(wrapper)
    await wrapper.find('#sms-code').setValue('123456')
    await wrapper.find('#password').setValue(PHONE)
    await wrapper.find('form').trigger('submit')
    await flush()
    expect(wrapper.text()).toContain('密码不能与手机号相同。')

    await wrapper.find('#phone').setValue('13900139000')

    expect(wrapper.text()).not.toContain('密码不能与手机号相同。')
    expect(paths(requests)).toHaveLength(3)
    wrapper.unmount()
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

    const wrapper = await showPage()
    await requestCode(wrapper)
    await submit(wrapper)
    const refusal = '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。'
    expect(wrapper.text()).toContain(refusal)

    await wrapper.find('#sms-code').setValue('')
    await wrapper.find('form').trigger('submit')
    await flush()

    expect(wrapper.text()).toContain('这一项必填。')
    expect(wrapper.text()).toContain(refusal)
    expect(paths(requests)).toHaveLength(4)
    wrapper.unmount()
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

    const wrapper = await showPage()
    await requestCode(wrapper)
    await wrapper.find('#sms-code').setValue('123456')
    await wrapper.find('#password').setValue(NEW_PASSWORD)

    const pressing = wrapper.find('form').trigger('submit')
    await wrapper.find('#phone').setValue('13900139000')
    release?.()
    await pressing
    await flush()

    expect(requests[3]?.body).toEqual({ phone: PHONE, code: '123456', password: NEW_PASSWORD })
    expect(wrapper.text()).not.toContain('这个手机号还没有注册。')
    wrapper.unmount()
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

    const wrapper = await showPage()
    await requestCode(wrapper)
    await submit(wrapper)
    const refusal = '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。'
    expect(wrapper.text()).toContain(refusal)

    // A different code, and the server refuses that instead — the password is untouched and was never
    // looked at this time.
    await wrapper.find('#sms-code').setValue('654321')
    await wrapper.find('form').trigger('submit')
    await flush()

    expect(wrapper.text()).toContain('短信验证码不正确或已过期，请重新获取。')
    expect(wrapper.text()).toContain(refusal)
    expect(paths(requests)).toHaveLength(5)
    wrapper.unmount()
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

    const wrapper = await showPage()
    await requestCode(wrapper)
    await submit(wrapper)
    const refusal = '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。'
    expect(wrapper.text()).toContain(refusal)

    await wrapper.find('#phone').setValue('13900139000')

    expect(wrapper.text()).toContain(refusal)
    expect(paths(requests)).toHaveLength(4)
    wrapper.unmount()
  })
})
