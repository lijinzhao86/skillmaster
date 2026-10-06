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
  invalid_format: '填写的内容格式不正确，请检查后重试。',
  invalid_length: '长度不符合要求，请检查后重试。',
  // The fields that can actually earn these two say something more useful in FIELD_SPECIFIC below;
  // what is here is the floor, for a field this version has not met.
  too_short: '长度不足，请检查后重试。',
  too_long: '长度超出限制，请检查后重试。',
  same_as_username: '密码不能与用户名相同。',
  same_as_phone: '密码不能与手机号相同。',
  already_taken: '这个用户名已经被占用。',
  already_registered: '这个手机号已经注册过了。',
  no_account: '这个手机号还没有注册。',
  invalid: '填写的内容不正确，请重新输入。',
  // One sentence for two causes — a password from the common list, and one built out of the
  // handle or the number — because both are answered the same way and neither is worth a second
  // round of copy.
  too_common: '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。',
}

/**
 * What to say for each error code the envelope can carry.
 *
 * The envelope's own `message` is written for whoever is reading a log — the server's strings are
 * English and phrased for an operator — so it is a fallback, never the thing on screen. A code this
 * version does not know still gets said out loud rather than swallowed.
 */
const CODE_MESSAGES: Record<string, string> = {
  // The author plane's two "there is nothing at this address" codes. Without a wording the person
  // gets the envelope's own text, which this file says is English and written for a log — and the
  // skill pages do reach these: a comparison whose `?from=` names nothing, or a file a version no
  // longer has, are both addresses a person can arrive at.
  skill_not_found: '这个地址在你的 skill 里找不到。',
  file_not_found: '这一版里没有这个文件。',
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
    invalid_format: '只能用 6–30 位小写字母、数字或连字符，且不能以连字符开头。',
    invalid_length: '长度需要 6–30 个字符。',
  },
  phone: {
    invalid_format: '请填写 11 位的大陆手机号。',
  },
  captcha: {
    invalid: '图形验证码不正确或已过期，已为你换了一张。',
  },
  code: {
    invalid_format: '请填写 6 位数字验证码。',
  },
  password: {
    // The one rule here a person cannot guess, so it has to say what is allowed rather than that
    // something is wrong.
    invalid_format: '密码只能使用半角英文字母、数字和符号。',
    // Stated as the requirement rather than as the failure: 「太短了。」 says something is wrong
    // without saying what would be right, which leaves the person guessing at the rule.
    too_short: '密码至少需要 8 个字符。',
    too_long: '密码最多 72 个字符。',
  },
}

/**
 * The rule, said once for the two pages that set a password.
 *
 * It is shown rather than hidden because it is a restriction the person has to be told about: the
 * form refuses characters they may well have typed on purpose — a Chinese password, a full-width
 * one — and that has to be visible where they type rather than only as a refusal afterwards. The
 * second sentence is the answer to a rule that reads like an obstacle: length is what buys strength
 * here, not a pile of symbols.
 */
export const PASSWORD_HINT =
  '至少 8 个字符，只能使用半角英文字母、数字和符号（不能用中文或全角字符）。用一句长口令比堆特殊符号更安全。'

/**
 * Shown when the server asks for a captcha the client had not been showing.
 *
 * Registration's first send from an address needs no captcha and every send after it does, so a
 * client cannot know which one it is about to make. The server's answer to a request carrying none
 * is what tells it — and that answer is not a mistake the person made, so it needs a sentence that
 * says what to do rather than one that says something was wrong.
 */
export const CAPTCHA_NEEDED_HINT = '请先完成图形验证码，然后再继续。'

/**
 * Shown when a check could not be put to the server at all — it was unreachable, refused the request,
 * or answered in a shape that says nothing.
 *
 * Said rather than passed over in silence. It is **not** a verdict: nothing was concluded, so the
 * field shows no tick and no 「检查中…」. But the form's button waits for an answer, and a dark button
 * with nothing beside it is a dead end — so this is a sentence, drawn like every other message rather
 * than as a state of its own, and its wording is what says it is not a refusal.
 */
export const COULD_NOT_CHECK_HINT = '没能确认这一项是否可用，改动一下可以重新检查。'

/**
 * Shown when a check came back with something this version has no wording for.
 *
 * Not {@link COULD_NOT_CHECK_HINT}: that one says the question could not be put, and here it was put
 * and answered — with an issue code from a newer server, which is a refusal of some kind. This says
 * only what is true either way, because a sentence about the wrong thing is worse than a vague one.
 */
export const CHECK_REFUSED_HINT = '这一项没能通过检查，改动一下可以重新检查。'

/**
 * The server's `details` as a map keyed by the field the form shows.
 *
 * All three names for a captcha collapse onto `captcha`: the server refuses one under `captcha`, and
 * names the two request fields when it is complaining about which of them was sent.
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
    case 'captcha':
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
