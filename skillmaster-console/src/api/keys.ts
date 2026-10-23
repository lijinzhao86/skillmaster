import { request } from './client'
import type { ApiKeys, ApiResult, IssuedKey, NewKey } from './types'

/**
 * The credential screen's plane (ADR 0038), one function each.
 *
 * **These four are the only way a credential comes into existence**, and none of them is on the API
 * plane: what may create a key is a browser session — a person doing something right now — so a
 * request path that could mint keys from a key does not exist to be misused (ADR 0040 §决定 1). The
 * CLI gets its key by being handed one a person copied from this screen.
 *
 * The CSRF header every write needs is added by `client.ts`, which reads it off the cookie at send
 * time; nothing here has to remember it.
 */
const BASE = '/web/keys'

/** This account's keys — revoked ones included and marked — and the deployment's expiry policy. */
export function listKeys(): Promise<ApiResult<ApiKeys>> {
  return request<ApiKeys>({ method: 'GET', path: BASE })
}

/** Creates a key and returns it with its secret. The only response that ever carries one. */
export function createKey(input: NewKey): Promise<ApiResult<IssuedKey>> {
  return request<IssuedKey>({ method: 'POST', path: BASE, body: input })
}

/**
 * Stops a key working, now.
 *
 * 204 and idempotent: a second click is somebody making sure, not an error, and the server keeps the
 * first timestamp — "when did it stop working" must not move because a button was pressed twice.
 */
export function revokeKey(id: string): Promise<ApiResult<void>> {
  return request<void>({ method: 'POST', path: `${BASE}/${encodeURIComponent(id)}/revoke` })
}

/**
 * Replaces a key with a fresh one: same name, note and scopes, a newly computed date.
 *
 * Returns a secret, unlike revoke — the caller has nothing until it has the new string, and the old
 * one is dead the moment this succeeds.
 */
export function rotateKey(id: string): Promise<ApiResult<IssuedKey>> {
  return request<IssuedKey>({ method: 'POST', path: `${BASE}/${encodeURIComponent(id)}/rotate` })
}
