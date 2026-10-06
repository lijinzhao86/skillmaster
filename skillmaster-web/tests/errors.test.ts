import { describe, expect, it } from 'vitest'
import { codeMessage, fieldErrors, isBannerOnly, messageFor } from '../src/api/errors'

describe('the server refusing a field', () => {
  it('says it under the field the server named', () => {
    expect(fieldErrors([{ field: 'username', issue: 'already_taken' }], 'fallback')).toEqual({
      username: '这个用户名已经被占用。',
    })
  })

  it('collapses both captcha fields onto the one the form shows', () => {
    // The server sends `captcha_id` when the challenge is unknown and `captcha_answer` when the
    // answer is wrong. The form has one box, so both belong under it.
    expect(fieldErrors([{ field: 'captcha_id', issue: 'invalid' }], 'fallback')).toEqual({
      captcha: '图形验证码不正确或已过期，已为你换了一张。',
    })
    expect(fieldErrors([{ field: 'captcha_answer', issue: 'invalid' }], 'fallback')).toEqual({
      captcha: '图形验证码不正确或已过期，已为你换了一张。',
    })
  })

  it('keeps every detail when the server sends several at once', () => {
    const errors = fieldErrors(
      [
        { field: 'phone', issue: 'already_registered' },
        { field: 'username', issue: 'invalid_length' },
        { field: 'password', issue: 'too_short' },
      ],
      'fallback',
    )

    expect(Object.keys(errors).sort()).toEqual(['password', 'phone', 'username'])
    expect(errors.phone).toBe('这个手机号已经注册过了。')
    expect(errors.username).toBe('长度需要 6–30 个字符。')
    expect(errors.password).toBe('密码至少需要 8 个字符。')
  })

  it('drops a field this form does not have rather than showing it somewhere wrong', () => {
    expect(fieldErrors([{ field: 'namespace', issue: 'required' }], 'fallback')).toEqual({})
  })

  it('falls back to the envelope message for an issue code this version does not know', () => {
    // Not the issue code itself: "expired" is a string for programs, not a sentence for a person.
    expect(fieldErrors([{ field: 'password', issue: 'expired' }], 'the password has expired')).toEqual(
      { password: 'the password has expired' },
    )
  })

  it('has wording for every issue code the server can send', () => {
    // The vocabulary, read off the server's own policies (UsernamePolicy / PasswordPolicy /
    // PhoneNumberPolicy) and AccountService. A code listed here and answered with the fallback is a
    // code with no wording — which reaches the screen as the server's English sentence.
    const FALLBACK = '__untranslated__'
    const codes = [
      'required',
      'invalid_format',
      'invalid_length',
      'too_short',
      'too_long',
      'same_as_username',
      'same_as_phone',
      'already_taken',
      'already_registered',
      'no_account',
      'invalid',
      'too_common',
    ]

    for (const code of codes) {
      expect(messageFor('password', code, FALLBACK)).not.toBe(FALLBACK)
    }
  })
})

describe('an error code', () => {
  it('is worded here rather than read out of the envelope', () => {
    // The server's own message is written for whoever reads a log, so it is a fallback, not the
    // thing on screen.
    expect(codeMessage('invalid_credentials', 'the phone number or password is incorrect')).toBe(
      '手机号或密码不正确。',
    )
  })

  it('still says something when it is a code this version has never seen', () => {
    expect(codeMessage('something_new', 'a brand new failure')).toBe('a brand new failure')
  })

  it('has wording for every code the browser plane can answer with', () => {
    // Read off the server's ErrorCode. These are the ones an endpoint under /web can produce; the
    // last three are made up by the client itself — a request that never arrived, one it gave up
    // on, and a failure that arrived without an envelope.
    const FALLBACK = '__untranslated__'
    const codes = [
      'unauthenticated',
      'invalid_credentials',
      'forbidden',
      'too_many_requests',
      'invalid_request',
      'verification_code_invalid',
      'internal_error',
      // These two look like the consumption plane's, and the handler that raises them is one
      // `@RestControllerAdvice` with no package filter, so `/web` gets them too: every 404 out of
      // this server is `skill_not_found`, and a file a version's manifest does not have is
      // `file_not_found`. A comparison whose `?from=` names nothing is a page somebody can reach.
      'skill_not_found',
      'file_not_found',
      'network_error',
      'timeout',
    ]

    for (const code of codes) {
      expect(codeMessage(code, FALLBACK)).not.toBe(FALLBACK)
    }
  })

  it('leaves the codes only the consumption plane can answer with to the envelope', () => {
    // Both of these need a bearer token and an upload, so no endpoint this client calls can produce
    // them; the server's English sentence is the honest way for a bug in this client to look.
    for (const code of ['insufficient_scope', 'invalid_upload']) {
      expect(codeMessage(code, 'from the server')).toBe('from the server')
    }
  })
})

describe('where an error goes', () => {
  it('is the banner for the two codes that carry no details', () => {
    expect(isBannerOnly('invalid_credentials')).toBe(true)
    expect(isBannerOnly('verification_code_invalid')).toBe(true)
  })

  it('is the field for the codes that do', () => {
    expect(isBannerOnly('invalid_request')).toBe(false)
  })
})
