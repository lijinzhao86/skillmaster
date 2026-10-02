import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import LoginPage from '../src/pages/LoginPage.vue'
import { clearCookies, errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'

const PHONE = '13800138000'
const PASSWORD = 'correct-horse'
const ACCOUNT = { user_id: '01M3HTG7GCCVBGRPAFFSVSF12W', username: 'demo-user', namespace: 'demo-user' }

let location = stubLocation('/login')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/login')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function signIn(wrapper: VueWrapper, phone = PHONE, password = PASSWORD): Promise<void> {
  await wrapper.find('#phone').setValue(phone)
  await wrapper.find('#password').setValue(password)
  await wrapper.find('form').trigger('submit')
  await flush()
}

function page(): VueWrapper {
  return mount(LoginPage)
}

describe('the login page', () => {
  it('never asks for a captcha and never sends a text message', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await signIn(wrapper)

    expect(requests.map((request) => request.path)).toEqual(['/web/login'])
    expect(requests[0]?.body).toEqual({ phone: PHONE, password: PASSWORD })
    wrapper.unmount()
  })

  it('says a wrong password in the banner, and marks no field at all', async () => {
    // The server answers the same way for a number nobody registered, a wrong password and a
    // suspended account. Marking the password field would confirm that the number exists.
    stubFetch(() => json(401, errorBody('invalid_credentials', 'the phone number or password is incorrect')))

    const wrapper = page()
    await signIn(wrapper, PHONE, 'wrong')

    expect(wrapper.find('.banner').text()).toBe('手机号或密码不正确。')
    expect(wrapper.findAll('.field-error')).toHaveLength(0)
    wrapper.unmount()
  })

  it('keeps a refusal that names a field on that field', async () => {
    stubFetch(() =>
      json(
        400,
        errorBody('invalid_request', 'The request was rejected.', [
          { field: 'phone', issue: 'invalid_format' },
        ]),
      ),
    )

    const wrapper = page()
    await signIn(wrapper, '12800138000')

    expect(wrapper.find('.field-error').text()).toBe('请填写 11 位的大陆手机号。')
    expect(wrapper.find('.banner').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not go anywhere while the sign-in failed', async () => {
    stubFetch(() => json(401, errorBody('invalid_credentials', 'the phone number or password is incorrect')))

    const wrapper = page()
    await signIn(wrapper, PHONE, 'wrong')

    expect(location.assign).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('refuses a password that is only spaces without a round trip', async () => {
    // Eight full-width spaces are what a Chinese input method left in full-width mode produces, and
    // every other field on every other form here already treats them as nothing typed. `=== ''` did
    // not, so this went to the server and came back as "phone or password is wrong".
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await signIn(wrapper, PHONE, '　'.repeat(8))

    expect(requests).toHaveLength(0)
    expect(wrapper.find('.field-error').text()).toBe('这一项必填。')
    wrapper.unmount()
  })

  it('answers a phone number that could not be one without a round trip', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await signIn(wrapper, '12800138000')

    expect(requests).toHaveLength(0)
    expect(wrapper.find('.field-error').text()).toBe('请填写 11 位的大陆手机号。')
    wrapper.unmount()
  })

  it('goes where it was asked to go, when that is a path on this site', async () => {
    location = stubLocation('/login', '?return_to=%2Fauthorize%3Fclient_id%3Dabc')
    stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await signIn(wrapper)

    expect(location.assign).toHaveBeenCalledWith('/authorize?client_id=abc')
    wrapper.unmount()
  })

  it('refuses to send anyone to another site after signing in', async () => {
    // This page is the one an authorization flow hands a browser to, which is exactly what makes it
    // worth pointing somewhere else.
    location = stubLocation('/login', '?return_to=https%3A%2F%2Fevil.example')
    stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await signIn(wrapper)

    expect(location.assign).toHaveBeenCalledWith('/')
    wrapper.unmount()
  })

  it('refuses a return_to that only looks like a path', async () => {
    location = stubLocation('/login', '?return_to=%2F%2Fevil.example')
    stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await signIn(wrapper)

    expect(location.assign).toHaveBeenCalledWith('/')
    wrapper.unmount()
  })

  it('refuses a path that a browser reads as another site', async () => {
    // `/\evil.example` starts with one slash, so a prefix test passes it — and every browser treats
    // the backslash as a slash and resolves it to evil.example. Both spellings are checked because
    // they are the same attack.
    location = stubLocation('/login', '?return_to=%2F%5Cevil.example')
    stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await signIn(wrapper)

    expect(location.assign).toHaveBeenCalledWith('/')
    wrapper.unmount()
  })

  it('refuses a path whose second segment is another site', async () => {
    location = stubLocation('/login', '?return_to=%2F%5C%2Fevil.example')
    stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await signIn(wrapper)

    expect(location.assign).toHaveBeenCalledWith('/')
    wrapper.unmount()
  })

  it('refuses a same-site link whose path would be read as another site', async () => {
    // The one a first fix missed. Both of these are same-origin *as URLs* — the origin is this site
    // — and both come out as `//evil.example`, which `location.assign` parses a second time as a
    // new authority. An origin check on the way in is not enough; the string on the way out has to
    // be checked too.
    for (const returnTo of [
      'https%3A%2F%2Fskillmaster.example%2F%2Fevil.example%2F',
      '%2F%2Fskillmaster.example%2F%2Fevil.example%2F',
    ]) {
      location = stubLocation('/login', `?return_to=${returnTo}`, 'https://skillmaster.example/login')
      stubFetch(() => json(200, ACCOUNT))

      const wrapper = page()
      await signIn(wrapper)

      expect(location.assign).toHaveBeenCalledWith('/')
      wrapper.unmount()
    }
  })

  it('treats a return_to that means nothing as no request at all', async () => {
    // Every one of these resolves to the login page itself, so honouring it would send the visitor
    // back to the form they had just filled in, now signed in, with no sign anything happened.
    // The `href` is given rather than defaulted: jsdom's root would resolve `''` to `/`, which the
    // function returns anyway, so the test would pass with or without the fix.
    for (const value of ['', '%20%20%20', '%23']) {
      location = stubLocation(
        '/login',
        `?return_to=${value}`,
        `https://skillmaster.example/login?return_to=${value}`,
      )
      stubFetch(() => json(200, ACCOUNT))

      const wrapper = page()
      await signIn(wrapper)

      expect(location.assign).toHaveBeenCalledWith('/')
      wrapper.unmount()
    }
  })

  it('keeps the query and fragment of a path it does accept', async () => {
    location = stubLocation('/login', '?return_to=%2Fauthorize%3Fclient_id%3Dabc%23step2')
    stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await signIn(wrapper)

    expect(location.assign).toHaveBeenCalledWith('/authorize?client_id=abc#step2')
    wrapper.unmount()
  })
})

describe('the login page, answering as fields are left', () => {
  it('says nothing about a field nobody has left yet', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await wrapper.find('#password').setValue('')

    expect(wrapper.find('.field-error').exists()).toBe(false)
    expect(requests).toHaveLength(0)
    wrapper.unmount()
  })

  it('answers nothing about the password, empty or short, and leaves that to the submit', async () => {
    // Two decisions meeting on one field. An empty field is answered at submit like every other
    // required one, rather than the moment the cursor passes through it; and the password policy is
    // not applied on this page at all, because an account whose password predates a rule change would
    // be refused at the door for a rule it never broke.
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await wrapper.find('#password').trigger('blur')
    expect(wrapper.find('.field-error').exists()).toBe(false)

    await wrapper.find('#password').setValue('short')
    await wrapper.find('#password').trigger('blur')
    expect(wrapper.find('.field-error').exists()).toBe(false)

    expect(requests).toHaveLength(0)
    wrapper.unmount()
  })

  it('answers the empty password once the form is submitted', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await signIn(wrapper, PHONE, '')

    expect(wrapper.find('.field-error').text()).toBe('这一项必填。')
    expect(requests).toHaveLength(0)
    wrapper.unmount()
  })

  it('answers a phone number that could not be one when the field is left', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const wrapper = page()
    await wrapper.find('#phone').setValue('12800138000')
    await wrapper.find('#phone').trigger('blur')

    expect(wrapper.find('.field-error').text()).toBe('请填写 11 位的大陆手机号。')
    expect(requests).toHaveLength(0)
    wrapper.unmount()
  })
})
