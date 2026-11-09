import { request } from './client'
import type {
  CreatedSecret,
  IncomingSecret,
  Secret,
  SecretBinding,
  SecretList,
  SecretShares,
} from './secretTypes'
import type { ApiResult } from './types'

/**
 * The console's half of the credential vault (v52): `/web/secrets`, one function each.
 *
 * **Why this plane and not `/api/v1`.** A session is the credential a browser has (ADR 0040), and
 * the API plane takes a bearer token the console does not hold. Both planes call the same use cases,
 * so the rules are not duplicated — only the way the caller is recognised.
 *
 * **One write exists only here.** Creating a share hands another person a value they can extract, so
 * ADR 0081's first exception puts it on this plane alone: `POST /api/v1/secrets/{id}/shares` answers
 * 405, and there is no other way to make a grant. Revoking one is on both planes, because taking
 * access back is not the risky direction.
 *
 * The CSRF header every write needs is added by `client.ts`; nothing here has to remember it.
 */
const BASE = '/web/secrets'

const id = (value: string) => encodeURIComponent(value)

/**
 * This account's credentials, newest first. No response on this plane carries a value.
 *
 * @param skill an optional `namespace/name`; the filter applies to the bindings, so a credential
 *              bound to two skills answers in both lists — it is still one credential
 */
export function listSecrets(skill?: string): Promise<ApiResult<SecretList>> {
  const query = skill === undefined ? '' : `?${new URLSearchParams({ skill })}`
  return request<SecretList>({ method: 'GET', path: `${BASE}${query}` })
}

/**
 * What this person receives from **other people's** credentials on one skill.
 *
 * Its own address rather than a flag on the listing: the listing is rooted in "my credentials",
 * and folding these rows in would make one list mean two ownerships. The skill pane shows both
 * together, because that is the question it answers — 「这个 skill 用了哪些凭据」.
 */
export function listIncomingSecrets(skill: string): Promise<ApiResult<{ items: IncomingSecret[] }>> {
  const query = new URLSearchParams({ skill })
  return request<{ items: IncomingSecret[] }>({
    method: 'GET',
    path: `${BASE}/incoming?${query}`,
  })
}

/** Creates one and binds it to its first skill in the same call. */
export function createSecret(input: {
  name: string
  value: string
  skill: string
  env: string
}): Promise<ApiResult<CreatedSecret>> {
  return request<CreatedSecret>({ method: 'POST', path: BASE, body: input })
}

/** Replaces the value; every binding and every grant keeps pointing at the same row. */
export function rotateSecret(secretId: string, value: string): Promise<ApiResult<Secret>> {
  return request<Secret>({
    method: 'PUT',
    path: `${BASE}/${id(secretId)}/value`,
    body: { value },
  })
}

/** Hard delete. Bindings and shares go with the row; the backup keeps them for 30 days. */
export function deleteSecret(secretId: string): Promise<ApiResult<void>> {
  return request<void>({ method: 'DELETE', path: `${BASE}/${id(secretId)}` })
}

/** The daily lease ceiling. Raising it is what the row's 调整 panel does. */
export function setSecretQuota(
  secretId: string,
  perDay: number,
): Promise<ApiResult<{ id: string; per_day: number }>> {
  return request<{ id: string; per_day: number }>({
    method: 'PATCH',
    path: `${BASE}/${id(secretId)}`,
    body: { per_day: perDay },
  })
}

/** Exposes the credential to one more skill and variable. */
export function bindSecret(
  secretId: string,
  skill: string,
  env: string,
): Promise<ApiResult<SecretBinding>> {
  return request<SecretBinding>({
    method: 'POST',
    path: `${BASE}/${id(secretId)}/bindings`,
    body: { skill, env },
  })
}

/** Removes one binding; the value and every other binding stay. */
export function unbindSecret(
  secretId: string,
  skill: string,
  env: string,
): Promise<ApiResult<void>> {
  const query = new URLSearchParams({ skill, env })
  return request<void>({
    method: 'DELETE',
    path: `${BASE}/${id(secretId)}/bindings?${query}`,
  })
}

/** Who receives this credential. Read-only; the page shows it before asking for another grant. */
export function listSecretShares(
  secretId: string,
  skill?: string,
): Promise<ApiResult<SecretShares>> {
  const query = skill === undefined ? '' : `?${new URLSearchParams({ skill })}`
  return request<SecretShares>({
    method: 'GET',
    path: `${BASE}/${id(secretId)}/shares${query}`,
  })
}

/**
 * Hands this credential's use on one skill to an accepted collaborator.
 *
 * The risk is stated twice before this is called — the sentence sits on the screen and the
 * confirmation repeats it — so the request itself is the answered question, not the question.
 */
export function shareSecret(
  secretId: string,
  skill: string,
  user: string,
): Promise<ApiResult<SecretShares>> {
  return request<SecretShares>({
    method: 'POST',
    path: `${BASE}/${id(secretId)}/shares`,
    body: { skill, user },
  })
}

/** Withdraws one person's grant on one skill; their next run is already without it. */
export function unshareSecret(
  secretId: string,
  skill: string,
  user: string,
): Promise<ApiResult<void>> {
  const query = new URLSearchParams({ skill, user })
  return request<void>({
    method: 'DELETE',
    path: `${BASE}/${id(secretId)}/shares?${query}`,
  })
}
