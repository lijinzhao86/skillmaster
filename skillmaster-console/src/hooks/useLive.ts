import { useRef, useState } from 'react'
import type { MutableRefObject } from 'react'

/**
 * A `useState` that is also readable synchronously.
 *
 * Vue's refs were live: a function reading `buffers[path]` after an `await` saw whatever had been
 * typed while the request was in flight, and a listener added once at mount read the current
 * render's values. A React closure keeps the values it was rendered with, so every value that
 * outlives an `await` or a listener is written to a ref at the same time as to the state — the
 * render still reads the `useState` value.
 */
export function useLive<T>(initial: T): [T, (next: T) => void, MutableRefObject<T>] {
  const [value, setValue] = useState(initial)
  const ref = useRef(initial)
  function set(next: T): void {
    ref.current = next
    setValue(next)
  }
  return [value, set, ref]
}
