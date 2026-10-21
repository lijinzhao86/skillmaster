/**
 * The keys page's facts, and the one state rule that belongs to the page rather than to the
 * vocabulary: **a moment with nothing to be about is not that moment.**
 *
 * `?page=keys&state=created` with no key named used to draw 「密钥已创建」 with no string, no facts,
 * and an exit that returned to a list holding exactly what it held before — a screen announcing an
 * event that never happened. A `rotated` naming an id no key has is the same nothing. Both fall
 * back, which is what every other unknown in the demo does.
 */

import { DEFAULT_STATE, stateOf } from '../demo'
import { madeKey, renewedKey } from '../fixtures/keys'

export * from '../fixtures/keys'

export function keysState(params: URLSearchParams): string {
  let state = stateOf('keys', params)
  if (state === 'created' && !madeKey(params)) state = DEFAULT_STATE.keys
  if (state === 'rotated' && !renewedKey(params)) state = DEFAULT_STATE.keys
  return state
}
