import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useCountdown } from '../src/hooks/useCountdown'

/**
 * The countdown, on a clock the test owns.
 *
 * The real one ticks once a second for up to a minute, which is exactly the kind of test that makes
 * a suite slow enough that people stop running it — so time is faked here and none of these wait.
 */

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

/**
 * Runs the hook inside a component, because it registers an unmount cleanup — and reads the value
 * from the hook's own result, so what is under test is the hook as a component actually uses it.
 */
function showCountdown() {
  const view = renderHook(() => useCountdown())
  return {
    remaining: () => view.result.current.remaining,
    start: (seconds: number) =>
      act(() => {
        view.result.current.start(seconds)
      }),
    stop: () =>
      act(() => {
        view.result.current.stop()
      }),
    tick: (ms: number) =>
      act(() => {
        vi.advanceTimersByTime(ms)
      }),
    unmount: view.unmount,
  }
}

describe('the countdown', () => {
  it('counts down a second at a time', () => {
    const countdown = showCountdown()

    countdown.start(60)
    expect(countdown.remaining()).toBe(60)

    countdown.tick(1000)
    expect(countdown.remaining()).toBe(59)

    countdown.tick(2000)
    expect(countdown.remaining()).toBe(57)

    countdown.unmount()
  })

  it('stops at zero rather than counting into negative seconds', () => {
    // A countdown that keeps going shows "−3 秒后可重发", and keeps a timer alive for as long as the
    // page is open.
    const countdown = showCountdown()

    countdown.start(2)
    countdown.tick(2000)
    expect(countdown.remaining()).toBe(0)

    countdown.tick(10_000)
    expect(countdown.remaining()).toBe(0)
    expect(vi.getTimerCount()).toBe(0)

    countdown.unmount()
  })

  it('drops the previous timer when it is started again', () => {
    // The leak this exists to catch: if the first interval were still running, the second second
    // would take the new countdown from 10 down to 8, and the button would say the wrong thing for
    // the whole minute.
    const countdown = showCountdown()

    countdown.start(60)
    countdown.tick(1000)
    expect(countdown.remaining()).toBe(59)

    countdown.start(10)
    countdown.tick(1000)
    expect(countdown.remaining()).toBe(9)
    expect(vi.getTimerCount()).toBe(1)

    countdown.unmount()
  })

  it('goes back to zero when it is stopped', () => {
    const countdown = showCountdown()

    countdown.start(60)
    countdown.stop()

    expect(countdown.remaining()).toBe(0)
    expect(vi.getTimerCount()).toBe(0)
    countdown.unmount()
  })

  it('leaves no timer behind when the page it belongs to goes away', () => {
    const countdown = showCountdown()

    countdown.start(60)
    expect(vi.getTimerCount()).toBe(1)

    countdown.unmount()

    expect(vi.getTimerCount()).toBe(0)
  })
})
