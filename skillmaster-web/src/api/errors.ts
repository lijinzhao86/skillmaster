import type { ErrorDetail } from './types'

/** The fields these forms have. The server names them the same way in `details[].field`. */
export type Field = 'phone' | 'username' | 'password' | 'code' | 'captcha'

/**
 * What to say for each issue code the server can send.
 *
 * The codes come from the server's own policy classes, and they are written here rather than shown
 * to anybody: an issue code is a stable string for programs, and "invalid_length" is not a sentence.
 */
const ISSUE_MESSAGES: Record<string, string> = {
  required: '这一项必填。',
  invalid_format: '格式不正确。',
  invalid_length: '长度不符合要求。',
  too_short: '太短了。',
  too_long: '太长了。',
  same_as_username: '不能与用户名相同。',
  same_as_phone: '不能与手机号相同。',
  already_taken: '这个用户名已经被占用。',
  already_registered: '这个手机号已经注册过了。',
  no_account: '这个手机号还没有注册。',
  invalid: '不正确，请重新输入。',
  // One sentence for two causes — a password from the common list, and one built out of the
  // handle or the number — because both are answered the same way and neither is worth a second
  // round of copy.
  too_common: '这个密码太常见了，或者与你的用户名、手机号太接近，请换一个。',
}

/**
 * What to say for each error code the envelope can carry.
 *
 * The envelope's own `message` is written for whoever is reading a log — the server's strings are
 * English and phrased for an operator — so it is a fallback, never the thing on screen. A code this
 * version does not know still gets said out loud rather than swallowed.
 */
const CODE_MESSAGES: Record<string, string> = {
  too_many_requests: '操作太频繁了，请稍后再试。',
  invalid_credentials: '手机号或密码不正确。',
  verification_code_invalid: '短信验证码不正确或已过期，请重新获取。',
  forbidden: '请求被拒绝了，请刷新页面后重试。',
  unauthenticated: '登录状态已失效，请重新登录。',
  invalid_request: '提交的内容有问题，请检查后重试。',
  network_error: '网络请求失败，请检查网络后重试。',
  timeout: '服务响应太慢，请稍后重试。',
  internal_error: '服务暂时不可用，请稍后重试。',
}

/** @param fallback the envelope's own message, used when this version has no wording for the code */
export function codeMessage(code: string, fallback: string): string {
  return CODE_MESSAGES[code] ?? fallback
}

/** Where the generic wording would not be enough to act on. */
const FIELD_SPECIFIC: Partial<Record<Field, Record<string, string>>> = {
  username: {
    invalid_format: '只能用 3–30 位小写字母、数字或连字符，且不能以连字符开头。',
    invalid_length: '长度需要 3–30 个字符。',
  },
  phone: {
    invalid_format: '请填写 11 位的大陆手机号。',
  },
  captcha: {
    invalid: '图形验证码不正确或已过期，已为你换了一张。',
  },
  password: {
    // The one rule here a person cannot guess, so it has to say what is allowed rather than that
    // something is wrong.
    invalid_format: '密码只能用英文字母、数字和符号（键盘上看得见的那些），不能用中文或全角字符。',
  },
}

/**
 * The server's `details` as a map keyed by the field the form shows.
 *
 * Both captcha fields (`captcha_id` and `captcha_answer`) collapse onto `captcha`, which is the
 * field name the server itself uses when it refuses one.
 *
 * @param fallback what to say for an issue code this version does not know — the envelope's own
 *        message, so a server that learns a new word for something still says something true
 */
export function fieldErrors(
  details: ErrorDetail[],
  fallback: string,
): Partial<Record<Field, string>> {
  const errors: Partial<Record<Field, string>> = {}
  for (const detail of details) {
    const field = fieldOf(detail.field)
    if (field === null) {
      continue
    }
    errors[field] = messageFor(field, detail.issue, fallback)
  }
  return errors
}

/**
 * One field's wording for one issue code.
 *
 * Shared with the client-side checks in `validation.ts`, which produce the same issue codes as the
 * server: a value refused before it is sent has to read exactly like one refused after.
 */
export function messageFor(field: Field, issue: string, fallback: string): string {
  return FIELD_SPECIFIC[field]?.[issue] ?? ISSUE_MESSAGES[issue] ?? fallback
}

function fieldOf(wireField: string): Field | null {
  switch (wireField) {
    case 'captcha_id':
    case 'captcha_answer':
      return 'captcha'
    case 'phone':
    case 'username':
    case 'password':
    case 'code':
      return wireField
    default:
      // A field this form does not have. Dropping it is right: showing it under a field that is not
      // on screen would be worse than the banner that already carries the message.
      return null
  }
}

/**
 * Whether an error belongs on a field or in the banner at the top.
 *
 * `invalid_credentials` and `verification_code_invalid` carry no details at all, deliberately: the
 * first says the same thing for three different failures, and hanging it on the password field would
 * quietly tell the visitor that the phone number exists.
 */
export function isBannerOnly(code: string): boolean {
  return code === 'invalid_credentials' || code === 'verification_code_invalid'
}
