import { request, SMS_TIMEOUT_MS } from './client'
import type {
  Account,
  ApiResult,
  Captcha,
  CaptchaRequirement,
  UsernameAvailability,
} from './types'

/**
 * The browser plane's endpoints, one function each.
 *
 * Thin on purpose: no validation, no retries, no interpretation of what a status means. Those
 * belong to the page that knows what it was doing.
 */
const BASE = '/web'

export function getCaptcha(): Promise<ApiResult<Captcha>> {
  return request<Captcha>({ method: 'GET', path: `${BASE}/captcha` })
}

export function currentSession(): Promise<ApiResult<Account>> {
  return request<Account>({ method: 'GET', path: `${BASE}/session` })
}

/**
 * Whether the next registration code send from this browser would be asked for a captcha.
 *
 * Asked as the form opens, so that a visitor whose address has already had its free send is shown the
 * captcha straight away instead of discovering it by being refused.
 */
export function registrationCodeCaptcha(): Promise<ApiResult<CaptchaRequirement>> {
  return request<CaptchaRequirement>({ method: 'GET', path: `${BASE}/register/code/captcha-required` })
}

/**
 * Whether a username is free, asked as somebody leaves the field rather than at submit.
 *
 * A GET: no session, no CSRF header, because it runs before there is anything to protect. Encoded
 * rather than interpolated — a username the policy will refuse is exactly the kind a form asks
 * about, so the value reaching here is routinely full of characters a URL path would read as
 * structure.
 */
export function checkUsername(username: string): Promise<ApiResult<UsernameAvailability>> {
  return request<UsernameAvailability>({
    method: 'GET',
    path: `${BASE}/username/availability?username=${encodeURIComponent(username)}`,
  })
}

/**
 * The captcha fields are nullable because the first send from an address needs none: registration
 * sends nothing for them on that one, and the server answers with what it wanted if the allowance is
 * already gone.
 */
export function requestRegistrationCode(body: {
  phone: string
  captcha_id: string | null
  captcha_answer: string | null
}): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${BASE}/register/code`,
    body,
    timeoutMs: SMS_TIMEOUT_MS,
  })
}

export function register(body: {
  phone: string
  code: string
  password: string
  username: string
}): Promise<ApiResult<Account>> {
  return request<Account>({ method: 'POST', path: `${BASE}/register`, body })
}

export function login(body: { phone: string; password: string }): Promise<ApiResult<Account>> {
  return request<Account>({ method: 'POST', path: `${BASE}/login`, body })
}

export function logout(): Promise<ApiResult<void>> {
  return request<void>({ method: 'POST', path: `${BASE}/logout` })
}

/** Nullable for the same reason as registration's, though this flow never actually omits them. */
export function requestResetCode(body: {
  phone: string
  captcha_id: string | null
  captcha_answer: string | null
}): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${BASE}/reset/code`,
    body,
    timeoutMs: SMS_TIMEOUT_MS,
  })
}

/** Does not sign anybody in, and ends every session the account had — see the reset page. */
export function resetPassword(body: {
  phone: string
  code: string
  password: string
}): Promise<ApiResult<void>> {
  return request<void>({ method: 'POST', path: `${BASE}/reset`, body })
}
