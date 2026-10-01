import { onUnmounted, ref } from 'vue'

/**
 * Seconds left before something may be tried again.
 *
 * A courtesy, not a control: the server refuses on its own clock whatever this says. Its job is to
 * stop somebody from walking into a 429 they could have been told about.
 */
export function useCountdown() {
  const remaining = ref(0)
  let timer: ReturnType<typeof setInterval> | null = null

  function start(seconds: number): void {
    stop()
    remaining.value = seconds
    timer = setInterval(() => {
      remaining.value -= 1
      if (remaining.value <= 0) {
        stop()
      }
    }, 1000)
  }

  function stop(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
    remaining.value = 0
  }

  onUnmounted(stop)

  return { remaining, start, stop }
}
