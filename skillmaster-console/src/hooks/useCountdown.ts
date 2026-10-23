import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Seconds left before something may be tried again.
 *
 * A courtesy, not a control: the server refuses on its own clock whatever this says. Its job is to
 * stop somebody from walking into a 429 they could have been told about.
 */
export function useCountdown() {
  const [remaining, setRemaining] = useState(0)
  const timer = useRef<ReturnType<typeof setInterval> | null>(null)

  const stop = useCallback(() => {
    if (timer.current !== null) {
      clearInterval(timer.current)
      timer.current = null
    }
    setRemaining(0)
  }, [])

  const start = useCallback(
    (seconds: number) => {
      stop()
      setRemaining(seconds)
      timer.current = setInterval(() => {
        setRemaining((left) => {
          if (left <= 1) {
            if (timer.current !== null) {
              clearInterval(timer.current)
              timer.current = null
            }
            return 0
          }
          return left - 1
        })
      }, 1000)
    },
    [stop],
  )

  // The interval outlives a component that went away otherwise, and keeps calling `setRemaining` on
  // an unmounted tree — a leak that shows up as a React warning and does nothing useful.
  useEffect(() => stop, [stop])

  return { remaining, start, stop }
}
