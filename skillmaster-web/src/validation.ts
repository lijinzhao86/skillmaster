/**
 * The rules the server enforces, mirrored.
 *
 * Why mirror them at all, when the server checks everything anyway: an SMS costs money and a round
 * trip costs the visitor a wait, so the two rules that stand between a typo and a text message are
 * worth checking here. The wording of each refusal comes from `api/errors.ts` — these functions
 * return the server's own issue codes so that a client-side refusal and a server-side one look the
 * same on screen.
 */

export const USERNAME_MIN_LENGTH = 6
export const USERNAME_MAX_LENGTH = 30
export const PASSWORD_MIN_BYTES = 8
export const PASSWORD_MAX_BYTES = 72

const USERNAME_SHAPE = /^[a-z0-9][a-z0-9-]{5,29}$/
const USERNAME_CHARACTERS = /^[a-z0-9-]+$/
const PHONE_SHAPE = /^1[3-9]\d{9}$/

/** @returns the server's issue code, or null when the value would be accepted */
export function phoneIssue(value: string): string | null {
  if (isBlank(value)) {
    return 'required'
  }
  return PHONE_SHAPE.test(value) ? null : 'invalid_format'
}

/**
 * Checked in the server's order, which is the order a person can act on: the alphabet first, then
 * the length. A two-character Chinese username is refused for its alphabet, and answering "too
 * short" would send its author off to type more characters that are refused for the same reason.
 */
export function usernameIssue(value: string): string | null {
  if (isBlank(value)) {
    return 'required'
  }
  if (!USERNAME_CHARACTERS.test(value)) {
    return 'invalid_format'
  }
  if (value.length < USERNAME_MIN_LENGTH || value.length > USERNAME_MAX_LENGTH) {
    return 'invalid_length'
  }
  return USERNAME_SHAPE.test(value) ? null : 'invalid_format'
}

/**
 * The ceiling is counted in **bytes**, not characters, because that is what the server counts and
 * what BCrypt truncates at. A password of 25 Chinese characters is 75 bytes and is refused; counting
 * `.length` would let it through here and only fail at the server.
 *
 * <p><strong>One server rule is missing here on purpose</strong>: the common-password blocklist.
 * It is some thousands of entries, and shipping them to the browser to save one round trip — on a
 * step that spends no text message — is not a trade worth making. So `password` is refused by the
 * server with the same `too_common` code this file can produce for the other two cases.
 */
export function passwordIssue(value: string, username: string, phone: string): string | null {
  // isBlank, not `=== ''`: eight spaces is not a password, and it is exactly what a full-width
  // input method leaves behind.
  if (isBlank(value)) {
    return 'required'
  }
  if (!isPrintableAscii(value)) {
    return 'invalid_format'
  }
  const bytes = new TextEncoder().encode(value).length
  if (bytes < PASSWORD_MIN_BYTES) {
    return 'too_short'
  }
  if (bytes > PASSWORD_MAX_BYTES) {
    return 'too_long'
  }
  if (value === username) {
    return 'same_as_username'
  }
  if (value === phone) {
    return 'same_as_phone'
  }
  if (basedOnHandle(value, username) || containsPhone(value, phone)) {
    return 'too_common'
  }
  return null
}

/**
 * The handle with something stuck on the end: `demo-user123`, `demo.user.2026`.
 *
 * Equality alone misses these and they are what a hurried person types. Deliberately not "contains
 * the handle", which would refuse `demo-user-and-then-some` — the long passphrase the form is
 * trying to encourage.
 *
 * The test is "the handle, then nothing but digits", not "strip the password's trailing digits and
 * compare": those differ when the handle itself ends in a digit (`u3f0a` with `u3f0a123`), because
 * stripping eats the handle's own last digit. The server learned that from an integration test with
 * a random handle; this mirror has to agree with it, not with the intuition behind it.
 */
function basedOnHandle(password: string, username: string): boolean {
  if (isBlank(username)) {
    return false
  }
  const handle = squashed(username)
  const core = squashed(password)
  if (handle === '' || !core.startsWith(handle)) {
    return false
  }
  return /^\d*$/.test(core.slice(handle.length))
}

/**
 * The phone number, with anything around it.
 *
 * Containment is safe for the number in a way it is not for a handle: eleven digits in a row cannot
 * be a coincidence, while a three-letter handle appears inside ordinary words.
 */
function containsPhone(password: string, phone: string): boolean {
  return !isBlank(phone) && squashed(password).includes(squashed(phone))
}

/** What is left of a value once case and punctuation stop distinguishing it. */
function squashed(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]/g, '')
}

/**
 * Everything from the space to the tilde, and nothing else (ADR 0017).
 *
 * A space inside a password is allowed — it is printable ASCII, and a passphrase of words is the
 * shape this policy encourages. Tab, newline, and anything a Chinese input method in full-width mode
 * produces are not, and that last one is the point: `ｐａｓｓｗｏｒｄ` looks like `password` and is not it.
 */
function isPrintableAscii(value: string): boolean {
  return /^[\x20-\x7e]+$/.test(value)
}

const CODE_SHAPE = /^\d{6}$/

/**
 * The six digits are mirrored from the code the server *generates* — `%06d` over the whole range —
 * rather than from a validator, because on the server's side there is no validator: the code is
 * compared, not shaped. Anything that is not six digits therefore cannot be one, and saying so here
 * saves a round trip on the one field whose value is being read off another device.
 *
 * @returns the server's issue code, or null when the value would be accepted
 */
export function codeIssue(value: string): string | null {
  if (isBlank(value)) {
    return 'required'
  }
  return CODE_SHAPE.test(value) ? null : 'invalid_format'
}

/**
 * Whether a value is nothing but space characters — Unicode's White_Space property, written out.
 *
 * <p>Written out rather than left to `trim()`, because JavaScript's whitespace set is not this one.
 * It includes U+FEFF, a zero-width no-break space (a byte order mark), which occupies no space at
 * all, and it leaves out U+0085, which is a line break. The server's `Text.isBlank` states this same
 * set from the same property; a value that reads as blank on one side and as a malformed value on
 * the other is a form that disagrees with itself about whether something was filled in.
 */
// Escaped rather than literal throughout, and that is not only style: two members of this
// set, U+2028 and U+2029, ARE JavaScript line terminators, so writing them literally inside a
// regex literal ends the line and the file stops parsing. Every member is spelled as an escape
// for that reason, and because a range of invisible characters is impossible to review.
const WHITE_SPACE =
  /^[\t\n\u000B\f\r\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]*$/

export function isBlank(value: string): boolean {
  return WHITE_SPACE.test(value)
}
