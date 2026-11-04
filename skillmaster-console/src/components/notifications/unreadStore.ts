import { unreadCount } from '../../api/notifications'

/**
 * The one unread count the bell and the notification page share.
 *
 * Both surfaces can change it (opening a row, accepting an invitation, marking everything read),
 * and both are mounted at once, so each keeping its own `useState` would put two answers for the
 * same fact on one screen. The design's demo keeps one store for exactly this reason; the server is
 * still the authority — this only holds the last answer it gave.
 */
let value = 0
const listeners = new Set<() => void>()

export function getUnread(): number {
  return value
}

export function setUnread(next: number): void {
  if (next === value) return
  value = next
  listeners.forEach((listener) => listener())
}

export function subscribeUnread(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Re-reads the count after an action. A failure leaves the last known number in place. */
export async function refreshUnread(): Promise<void> {
  const result = await unreadCount()
  if (result.ok) setUnread(result.data.unread)
}
