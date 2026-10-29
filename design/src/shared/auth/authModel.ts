/**
 * The sign-in overlay's rules, wording and consent model — everything the screens decide that is
 * not presentation.
 *
 * The validators are mirrored from the web client's `validation.ts`, down to the order each value is
 * judged in and the issue code each refusal is reported under — so a value refused here reads
 * exactly like one the server refused. There is no account server behind this demo, so everything
 * one would decide is decided here.
 */

export type AuthView =
  | 'login'
  | 'register'
  | 'registerCode'
  | 'registerDone'
  | 'reset'
  | 'resetCode'
  | 'resetPassword'
  | 'resetDone'
  | 'consent'
  | 'consentIncomplete'
  | 'consentDone'

export type FieldName = 'username' | 'phone' | 'password' | 'captcha' | 'code'

export type Issue =
  | 'required'
  | 'invalid_format'
  | 'invalid_length'
  | 'too_short'
  | 'too_long'
  | 'same_as_username'
  | 'same_as_phone'
  | 'already_taken'
  | 'already_registered'
  | 'no_account'
  | 'too_common'
  | 'verification_code_invalid'
  | 'invalid'

/** What a box owes under it: the source's `note()` kinds, made explicit. */
export type FieldStatus =
  | { kind: 'none' }
  | { kind: 'ok' }
  | { kind: 'checking' }
  | { kind: 'error'; message: string }

/**
 * One field's facts. `note` is stored rather than derived at render time because the source
 * publishes it on events: clearing an input's value after a refusal (the reset step's six wrong
 * digits) leaves the refusal standing until the next judgement, and deriving it would withdraw it
 * early.
 */
export interface FieldState {
  value: string
  visited: boolean
  localMessage: string
  serverMessage: string
  serverValue: string
  ok: boolean
  awaiting: boolean
  note: FieldStatus
}

/* ------------------------------------------------------------ the demo's facts
   There is no account server behind this page. The one code that is accepted, the one number that
   already has an account, and the handles that are taken. */

export const PHONE_RE = /^1[3-9]\d{9}$/
export const DEMO_SMS_CODE = '123456'
export const DEMO_REGISTERED_PHONE = '13800138000'
export const DEMO_LOGIN_PASSWORD = 'skillmaster'

/* Every one of these has to clear the length rule to be reachable: a taken name shorter than six
   characters is refused for its length before anybody asks whether it is free, which is the real
   order. A short entry here would simply never be seen. */
export const DEMO_TAKEN_USERNAMES = ['skillmaster', 'adminuser', 'demo-user']

/* The real product leaves a minute between two messages to one number. Ten seconds here — a demo
   that makes somebody sit out the real clock is one nobody finishes. */
export const DEMO_COOLDOWN_SECONDS = 10

/* The round trip, slowed enough that 「发送中…」 and 「注册中…」 are states rather than a frame. */
export const DEMO_ROUND_TRIP_MS = 420

/* -------------------------------------------------------------------- rules */

const USERNAME_MIN_LENGTH = 6
const USERNAME_MAX_LENGTH = 30
const PASSWORD_MIN_BYTES = 8
const PASSWORD_MAX_BYTES = 72

const USERNAME_SHAPE = /^[a-z0-9][a-z0-9-]{5,29}$/
const USERNAME_CHARACTERS = /^[a-z0-9-]+$/
const CODE_SHAPE = /^\d{6}$/
const PRINTABLE_ASCII = /^[\x20-\x7e]+$/
/* Unicode's White_Space property, written out. Escaped throughout because two of its members,
   U+2028 and U+2029, are line terminators and cannot stand literally inside a regex literal. */
const WHITE_SPACE =
  /^[\t\n\u000B\f\r\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]*$/

export function isBlank(value: string): boolean {
  return WHITE_SPACE.test(value)
}

export function phoneIssue(value: string): Issue | null {
  if (isBlank(value)) return 'required'
  return PHONE_RE.test(value) ? null : 'invalid_format'
}

/* The alphabet first and the length second, which is the order a person can act on: a short Chinese
   handle is refused for its characters, and answering 「太短」 would send its author off to type
   more of what is already refused. */
export function usernameIssue(value: string): Issue | null {
  if (isBlank(value)) return 'required'
  if (!USERNAME_CHARACTERS.test(value)) return 'invalid_format'
  if (value.length < USERNAME_MIN_LENGTH || value.length > USERNAME_MAX_LENGTH) return 'invalid_length'
  return USERNAME_SHAPE.test(value) ? null : 'invalid_format'
}

/* The ceiling counted in bytes, which is what the server counts and what BCrypt truncates at: a
   password of 25 Chinese characters is 75 bytes and is refused, while `.length` would let it
   through here and only fail at the server. */
export function passwordIssue(value: string, username: string, phone: string): Issue | null {
  if (isBlank(value)) return 'required'
  if (!PRINTABLE_ASCII.test(value)) return 'invalid_format'
  const bytes = new TextEncoder().encode(value).length
  if (bytes < PASSWORD_MIN_BYTES) return 'too_short'
  if (bytes > PASSWORD_MAX_BYTES) return 'too_long'
  if (value === username) return 'same_as_username'
  if (value === phone) return 'same_as_phone'
  if (basedOnHandle(value, username) || containsPhone(value, phone)) return 'too_common'
  return null
}

export function codeIssue(value: string): Issue | null {
  if (isBlank(value)) return 'required'
  return CODE_SHAPE.test(value) ? null : 'invalid_format'
}

export function captchaIssue(value: string): Issue | null {
  return isBlank(value) ? 'required' : null
}

function squashed(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]/g, '')
}

/* The handle with nothing but digits on the end. Deliberately not "contains the handle": that would
   refuse the long passphrase the policy is trying to encourage. */
function basedOnHandle(password: string, username: string): boolean {
  if (isBlank(username)) return false
  const handle = squashed(username)
  const core = squashed(password)
  if (handle === '' || core.indexOf(handle) !== 0) return false
  return /^\d*$/.test(core.slice(handle.length))
}

/* Containment is safe for a number in a way it is not for a handle: eleven digits in a row cannot
   be a coincidence. */
function containsPhone(password: string, phone: string): boolean {
  return !isBlank(phone) && squashed(password).indexOf(squashed(phone)) !== -1
}

/** The second opinion only the demo's "server" can give. */
export function verifyUsername(value: string): Issue | null {
  return DEMO_TAKEN_USERNAMES.indexOf(value) === -1 ? null : 'already_taken'
}

/* ----------------------------------------------------------------- wording
   Every sentence a refusal can be shown as, word for word from `api/errors.ts`. */

const MESSAGES: Partial<Record<Issue, string>> = {
  required: '这一项必填。',
  invalid_format: '填写的内容格式不正确，请检查后重试。',
  invalid_length: '长度不符合要求，请检查后重试。',
  too_short: '长度不足，请检查后重试。',
  too_long: '长度超出限制，请检查后重试。',
  same_as_username: '密码不能与用户名相同。',
  same_as_phone: '密码不能与手机号相同。',
  already_taken: '这个用户名已经被占用。',
  already_registered: '这个手机号已经注册过了。',
  no_account: '这个手机号还没有注册。',
  too_common: '这个密码太常见，或与你的用户名、手机号过于接近，请换一个。',
  verification_code_invalid: '短信验证码不正确或已过期，请重新获取。',
}

/* Where the generic wording would not be enough to act on. */
const FIELD_SPECIFIC: Partial<Record<FieldName, Partial<Record<Issue, string>>>> = {
  username: {
    invalid_format: '只能用 6–30 位小写字母、数字或连字符，且不能以连字符开头。',
    invalid_length: '长度需要 6–30 个字符。',
  },
  phone: { invalid_format: '请填写 11 位的大陆手机号。' },
  captcha: { invalid: '图形验证码不正确或已过期，已为你换了一张。' },
  code: { invalid_format: '请填写 6 位数字验证码。' },
  password: {
    invalid_format: '密码只能使用半角英文字母、数字和符号。',
    too_short: '密码至少需要 8 个字符。',
    too_long: '密码最多 72 个字符。',
  },
}

export function messageFor(field: FieldName, issue: Issue): string {
  return FIELD_SPECIFIC[field]?.[issue] ?? MESSAGES[issue] ?? '填写的内容不正确，请重新输入。'
}

/* ------------------------------------------------------------------ captcha
   Three fixed challenges, drawn as an SVG so the answer is a picture rather than text. The real one
   arrives as a PNG from the server; this one has to be drawn here, because there is no server. */

export const CAPTCHAS = ['K7M2', '9X4P', 'R3F8']

/* Fixed in its letters and moving in its specks: the answer is what has to stay readable, and noise
   that shifts between two views of the same challenge is what makes it a picture. */
export function captchaSource(answer: string): string {
  const width = 112
  const height = 38
  /* Every colour is one already in the sheet: --paper for the ground, --stroke and --line for the
     measures, --ink and --muted for the letters. They are literals because an image cannot read a
     CSS variable. */
  const parts = ['<rect width="' + width + '" height="' + height + '" fill="#fdfcfa"/>']
  const strokes = [
    'M-3 11 C 18 2, 38 22, 58 10 S 92 4, 115 15',
    'M-3 29 C 16 37, 34 15, 56 29 S 90 35, 115 23',
    'M-3 21 C 22 25, 42 5, 68 21 S 96 29, 115 11',
  ]
  strokes.forEach((stroke) => {
    parts.push('<path d="' + stroke + '" fill="none" stroke="#b7c0c9" stroke-width="1.1"/>')
  })
  answer.split('').forEach((letter, index) => {
    const x = 17 + index * 26
    const y = 27 + (index % 2 === 0 ? 3 : -3)
    const tilt = index % 2 === 0 ? -12 : 13
    parts.push(
      '<text x="' + x + '" y="' + y + '" text-anchor="middle" font-family="monospace" font-size="23"' +
        ' font-weight="700" fill="' + (index % 2 === 0 ? '#1f2328' : '#59636e') + '"' +
        ' transform="rotate(' + tilt + ' ' + x + ' ' + y + ')">' + letter + '</text>',
    )
  })
  for (let dot = 0; dot < 36; dot += 1) {
    parts.push(
      '<circle cx="' + (Math.random() * width).toFixed(1) + '" cy="' + (Math.random() * height).toFixed(1) +
        '" r="0.7" fill="#d8dee4"/>',
    )
  }
  return (
    'data:image/svg+xml;charset=utf-8,' +
    encodeURIComponent(
      '<svg xmlns="http://www.w3.org/2000/svg" width="' + width + '" height="' + height + '">' +
        parts.join('') + '</svg>',
    )
  )
}

/* Case-forgiving: the box capitalises as it is typed into on a phone, and refusing a right answer
   for its case is the kind of rule that reads as a trick. */
export function captchaMatches(index: number, value: string): boolean {
  return value.trim().toUpperCase() === CAPTCHAS[index]
}

/* ------------------------------------------------------------------ consent
   The one screen no page leads to. The CLI hands the browser a URL carrying client_id, state and
   scope, so that address is the entry — and there being no such address is the homepage. */

export const CONSENT_CLIENTS: Record<string, string> = { 'skillmaster-cli': 'SkillMaster CLI' }

/* Written as consequences rather than as scope names. This screen is the product's only moment of
   disclosure: somebody who typed `skmr login` has not thereby learned what `skills:write`
   means.

   **This entry used to say 「发布」, and that word was wrong here.** Putting a version live exists
   only on the browser plane — the API plane has no such route (ADR 0031 §决定 2), so a credential on
   this plane can never publish anything. 「删除」 came out of the same list on 2026-10-16, for the
   same reason: until the route goes, this line must not be shortened. */
export const CONSENT_SCOPES: Record<string, string> = {
  'skills:read': '读取你可见的全部 skill，包括未公开的',
  'skills:write': '以你的身份提交新版本、管理草稿与共享',
}

/** A scope is either one this file can describe, or the raw one plus a warning that it cannot. */
export type ConsentScope =
  | { kind: 'described'; text: string }
  | { kind: 'unknown'; raw: string }

export interface ConsentEntry {
  complete: boolean
  knownClient: boolean
  who: string
  name: string
  scopes: ConsentScope[]
}

export function readConsent(params: URLSearchParams | undefined): ConsentEntry {
  const query = params ?? new URLSearchParams()
  const clientId = query.get('client_id')
  const state = query.get('state')
  const knownClient = clientId !== null && Object.prototype.hasOwnProperty.call(CONSENT_CLIENTS, clientId)

  const scopes: ConsentScope[] = []
  query.getAll('scope').forEach((value) => {
    value.split(/\s+/).forEach((scope) => {
      if (scope === '') return
      if (Object.prototype.hasOwnProperty.call(CONSENT_SCOPES, scope)) {
        scopes.push({ kind: 'described', text: CONSENT_SCOPES[scope] })
      } else {
        /* Raw, and said to be undescribed: a blank would read as harmless, which is the one thing an
           unknown scope must not read as. */
        scopes.push({ kind: 'unknown', raw: scope })
      }
    })
  })

  /* Either half missing is a link that cannot work. A link carrying neither is not a consent entry
     at all in the source; a host that asks for this view anyway gets the incomplete card rather
     than an empty request. */
  return {
    complete: clientId !== null && clientId !== '' && state !== null,
    knownClient,
    who: knownClient && clientId !== null ? CONSENT_CLIENTS[clientId] : (clientId ?? ''),
    name: query.get('name') || '',
    scopes,
  }
}

/* ------------------------------------------------------------------- views */

export const VIEW_TITLE: Record<AuthView, string> = {
  login: '登录',
  register: '注册',
  registerCode: '验证手机号',
  registerDone: '注册成功',
  reset: '找回密码',
  resetCode: '验证手机号',
  resetPassword: '设置新密码',
  resetDone: '密码已重置',
  consent: '授权请求',
  consentIncomplete: '授权请求',
  consentDone: '授权成功',
}

/**
 * The value a host with addresses hears about. Register's and reset's code and result steps have
 * no address of their own — a visitor arriving at them directly would have no code and no number —
 * and consent's three states are one entry.
 */
export function addressViewOf(view: AuthView): AuthView {
  if (view === 'register' || view === 'registerCode' || view === 'registerDone') return 'register'
  if (view === 'reset' || view === 'resetCode' || view === 'resetPassword' || view === 'resetDone') return 'reset'
  if (view === 'consent' || view === 'consentIncomplete' || view === 'consentDone') return 'consent'
  return 'login'
}
