import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

/**
 * Unmount what a test rendered, after it.
 *
 * `@testing-library/react` only registers this itself when a global `afterEach` exists, which on
 * Vitest means `globals: true` — and this project does not set it. Same reason as the console's
 * setup file: an unmounted tree keeps its timers and its effects running into the next test.
 */
afterEach(() => {
  cleanup()
})
