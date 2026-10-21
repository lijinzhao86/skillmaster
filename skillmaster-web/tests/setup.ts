import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

/**
 * Unmount what a test rendered, after it.
 *
 * `@testing-library/react` only registers this itself when a global `afterEach` exists, which on
 * Vitest means `globals: true` — and this project does not set it. Without the call, every tree a
 * test rendered stays mounted for the rest of the file: its debounced checks keep running and fire
 * requests into the *next* test's stub, which then answers a question it was not asked. Found by a
 * register-page test that failed only when run after the others.
 */
afterEach(() => {
  cleanup()
})
