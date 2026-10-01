import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, type Ref } from 'vue'
import { useCountdown } from '../src/composables/useCountdown'

/**
 * The countdown, on a clock the test owns.
 *
 * The real one ticks once a second for up to a minute, which is exactly the kind of test that makes
 * a suite slow enough that people stop running it — so time is faked here and none of these wait.
 */

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

interface Harness {
  remaining: Ref<number>
  start: (seconds: number) => void
  stop: () => void
  unmount: () => void
}

/**
 * Runs the composable inside a component, because it registers an unmount hook — and reads the ref
 * from a render function, so what is under test is the composable as a component actually uses it.
 */
function showCountdown(): Harness {
  let countdown!: ReturnType<typeof useCountdown>
  const Host = defineComponent({
    setup() {
      countdown = useCountdown()
      return () => h('span', String(countdown.remaining.value))
    },
  })
  const wrapper = mount(Host)
  return {
    remaining: countdown.remaining,
    start: countdown.start,
    stop: countdown.stop,
    unmount: () => wrapper.unmount(),
  }
}

describe('the countdown', () => {
  it('counts down a second at a time', () => {
    const countdown = showCountdown()

    countdown.start(60)
    expect(countdown.remaining.value).toBe(60)

    vi.advanceTimersByTime(1000)
    expect(countdown.remaining.value).toBe(59)

    vi.advanceTimersByTime(2000)
    expect(countdown.remaining.value).toBe(57)

    countdown.unmount()
  })

  it('stops at zero rather than counting into negative seconds', () => {
    // A countdown that keeps going shows "−3 秒后可重发", and keeps a timer alive for as long as the
    // page is open.
    const countdown = showCountdown()

    countdown.start(2)
    vi.advanceTimersByTime(2000)
    expect(countdown.remaining.value).toBe(0)

    vi.advanceTimersByTime(10_000)
    expect(countdown.remaining.value).toBe(0)
    expect(vi.getTimerCount()).toBe(0)

    countdown.unmount()
  })

  it('drops the previous timer when it is started again', () => {
    // The leak this exists to catch: if the first interval were still running, the second second
    // would take the new countdown from 10 down to 8, and the button would say the wrong thing for
    // the whole minute.
    const countdown = showCountdown()

    countdown.start(60)
    vi.advanceTimersByTime(1000)
    expect(countdown.remaining.value).toBe(59)

    countdown.start(10)
    vi.advanceTimersByTime(1000)
    expect(countdown.remaining.value).toBe(9)
    expect(vi.getTimerCount()).toBe(1)

    countdown.unmount()
  })

  it('goes back to zero when it is stopped', () => {
    const countdown = showCountdown()

    countdown.start(60)
    countdown.stop()

    expect(countdown.remaining.value).toBe(0)
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
