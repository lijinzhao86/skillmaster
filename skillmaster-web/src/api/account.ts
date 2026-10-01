import { request, SMS_TIMEOUT_MS } from './client'
import type { Account, ApiResult, Captcha } from './types'

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

export function requestRegistrationCode(body: {
  phone: string
  captcha_id: string
  captcha_answer: string
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

export function requestResetCode(body: {
  phone: string
  captcha_id: string
  captcha_answer: string
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
