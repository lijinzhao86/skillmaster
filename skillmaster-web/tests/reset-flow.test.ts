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
  await wrapper.find('.row button').trigger('click')
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
    expect(wrapper.text()).toContain('太长了。')
    wrapper.unmount()
  })
})
