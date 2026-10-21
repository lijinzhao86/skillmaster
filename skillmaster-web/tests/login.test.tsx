import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import LoginPage from '../src/pages/LoginPage'
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

async function signIn(container: HTMLElement, phone = PHONE, password = PASSWORD): Promise<void> {
  fireEvent.change(container.querySelector('#phone') as HTMLInputElement, {
    target: { value: phone },
  })
  fireEvent.change(container.querySelector('#password') as HTMLInputElement, {
    target: { value: password },
  })
  fireEvent.submit(container.querySelector('form') as HTMLFormElement)
  await flush()
}

const page = () => render(<LoginPage />)
const text = (element: Element | null): string => (element?.textContent ?? '').trim()

describe('the login page', () => {
  it('never asks for a captcha and never sends a text message', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    await signIn(container)

    expect(requests.map((request) => request.path)).toEqual(['/web/login'])
    expect(requests[0]?.body).toEqual({ phone: PHONE, password: PASSWORD })
  })

  it('says a wrong password in the banner, and marks no field at all', async () => {
    // The server answers the same way for a number nobody registered, a wrong password and a
    // suspended account. Marking the password field would confirm that the number exists.
    stubFetch(() =>
      json(401, errorBody('invalid_credentials', 'the phone number or password is incorrect')),
    )

    const { container } = page()
    await signIn(container, PHONE, 'wrong')

    expect(text(container.querySelector('.banner'))).toBe('手机号或密码不正确。')
    expect(container.querySelectorAll('.field-error')).toHaveLength(0)
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

    const { container } = page()
    await signIn(container, '12800138000')

    expect(text(container.querySelector('.field-error'))).toBe('请填写 11 位的大陆手机号。')
    expect(container.querySelector('.banner')).toBeNull()
  })

  it('does not go anywhere while the sign-in failed', async () => {
    stubFetch(() =>
      json(401, errorBody('invalid_credentials', 'the phone number or password is incorrect')),
    )

    const { container } = page()
    await signIn(container, PHONE, 'wrong')

    expect(location.assign).not.toHaveBeenCalled()
  })

  it('refuses a password that is only spaces without a round trip', async () => {
    // Eight full-width spaces are what a Chinese input method left in full-width mode produces, and
    // every other field on every other form here already treats them as nothing typed. `=== ''` did
    // not, so this went to the server and came back as "phone or password is wrong".
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    await signIn(container, PHONE, '　'.repeat(8))

    expect(requests).toHaveLength(0)
    expect(text(container.querySelector('.field-error'))).toBe('这一项必填。')
  })

  it('answers a phone number that could not be one without a round trip', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    await signIn(container, '12800138000')

    expect(requests).toHaveLength(0)
    expect(text(container.querySelector('.field-error'))).toBe('请填写 11 位的大陆手机号。')
  })

  it('goes where it was asked to go, when that is a path on this site', async () => {
    location = stubLocation('/login', '?return_to=%2Fauthorize%3Fclient_id%3Dabc')
    stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    await signIn(container)

    expect(location.assign).toHaveBeenCalledWith('/authorize?client_id=abc')
  })

  it('refuses to send anyone to another site after signing in', async () => {
    // This page is the one an authorization flow hands a browser to, which is exactly what makes it
    // worth pointing somewhere else.
    location = stubLocation('/login', '?return_to=https%3A%2F%2Fevil.example')
    stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    await signIn(container)

    expect(location.assign).toHaveBeenCalledWith('/')
  })

  it('refuses a return_to that only looks like a path', async () => {
    location = stubLocation('/login', '?return_to=%2F%2Fevil.example')
    stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    await signIn(container)

    expect(location.assign).toHaveBeenCalledWith('/')
  })

  it('refuses a path that a browser reads as another site', async () => {
    // `/\evil.example` starts with one slash, so a prefix test passes it — and every browser treats
    // the backslash as a slash and resolves it to evil.example. Both spellings are checked because
    // they are the same attack.
    location = stubLocation('/login', '?return_to=%2F%5Cevil.example')
    stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    await signIn(container)

    expect(location.assign).toHaveBeenCalledWith('/')
  })

  it('refuses a path whose second segment is another site', async () => {
    location = stubLocation('/login', '?return_to=%2F%5C%2Fevil.example')
    stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    await signIn(container)

    expect(location.assign).toHaveBeenCalledWith('/')
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

      const { container } = page()
      await signIn(container)

      expect(location.assign).toHaveBeenCalledWith('/')
    }
  })

  it('treats a return_to that means nothing as no request at all', async () => {
    // Every one of these resolves to the login page itself, so honouring it would send the visitor
    // back to the form they had just filled in, now signed in, with no sign anything happened.
    for (const value of ['', '%20%20%20', '%23']) {
      location = stubLocation(
        '/login',
        `?return_to=${value}`,
        `https://skillmaster.example/login?return_to=${value}`,
      )
      stubFetch(() => json(200, ACCOUNT))

      const { container } = page()
      await signIn(container)

      expect(location.assign).toHaveBeenCalledWith('/')
    }
  })

  it('keeps the query and fragment of a path it does accept', async () => {
    location = stubLocation('/login', '?return_to=%2Fauthorize%3Fclient_id%3Dabc%23step2')
    stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    await signIn(container)

    expect(location.assign).toHaveBeenCalledWith('/authorize?client_id=abc#step2')
  })
})

describe('the login page, answering as fields are left', () => {
  it('says nothing about a field nobody has left yet', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    fireEvent.change(container.querySelector('#password') as HTMLInputElement, {
      target: { value: '' },
    })
    await flush()

    expect(container.querySelector('.field-error')).toBeNull()
    expect(requests).toHaveLength(0)
  })

  it('answers nothing about the password, empty or short, and leaves that to the submit', async () => {
    // Two decisions meeting on one field. An empty field is answered at submit like every other
    // required one; and the password policy is not applied on this page at all, because an account
    // whose password predates a rule change would be refused at the door for a rule it never broke.
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    const password = container.querySelector('#password') as HTMLInputElement
    fireEvent.blur(password)
    await flush()
    expect(container.querySelector('.field-error')).toBeNull()

    fireEvent.change(password, { target: { value: 'short' } })
    fireEvent.blur(password)
    await flush()
    expect(container.querySelector('.field-error')).toBeNull()

    expect(requests).toHaveLength(0)
  })

  it('answers the empty password once the form is submitted', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    await signIn(container, PHONE, '')

    expect(text(container.querySelector('.field-error'))).toBe('这一项必填。')
    expect(requests).toHaveLength(0)
  })

  it('answers a phone number that could not be one when the field is left', async () => {
    const { requests } = stubFetch(() => json(200, ACCOUNT))

    const { container } = page()
    const phone = container.querySelector('#phone') as HTMLInputElement
    fireEvent.change(phone, { target: { value: '12800138000' } })
    fireEvent.blur(phone)
    await flush()

    expect(text(container.querySelector('.field-error'))).toBe('请填写 11 位的大陆手机号。')
    expect(requests).toHaveLength(0)
  })
})
