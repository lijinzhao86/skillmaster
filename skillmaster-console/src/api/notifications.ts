import { request } from './client'
import type {
  ApiResult,
  Notification,
  NotificationPage,
  Rung,
} from './types'

/**
 * The notification centre (v45). Every function here is the browser plane's `/web/notifications`,
 * which carries the session and CSRF pair; the API plane has the same routes for the CLI.
 */

const BASE = '/web/notifications'

export function listNotifications(
  status: 'attention' | 'pending' | 'all',
  unread: boolean,
  p?: number,
  per?: number,
): Promise<ApiResult<NotificationPage>> {
  const query = new URLSearchParams()
  query.set('status', status)
  if (unread) query.set('unread', 'true')
  if (p !== undefined && p > 1) query.set('p', String(p))
  if (per !== undefined) query.set('per', String(per))
  return request<NotificationPage>({ method: 'GET', path: `${BASE}?${query}` })
}

export function getNotification(id: string): Promise<ApiResult<Notification>> {
  return request<Notification>({ method: 'GET', path: `${BASE}/${encodeURIComponent(id)}` })
}

export function unreadCount(): Promise<ApiResult<{ unread: number }>> {
  return request<{ unread: number }>({ method: 'GET', path: `${BASE}/unread-count` })
}

export function markRead(id: string): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${BASE}/${encodeURIComponent(id)}/read`,
    body: {},
  })
}

export function markAllRead(): Promise<ApiResult<{ marked: number }>> {
  return request<{ marked: number }>({ method: 'POST', path: `${BASE}/read-all`, body: {} })
}

export function acceptInvitation(
  id: string,
): Promise<ApiResult<{ skill: { namespace: string; name: string }; role: Rung }>> {
  return request({
    method: 'POST',
    path: `${BASE}/${encodeURIComponent(id)}/accept`,
    body: {},
  })
}

export function declineInvitation(id: string): Promise<ApiResult<void>> {
  return request<void>({
    method: 'POST',
    path: `${BASE}/${encodeURIComponent(id)}/decline`,
    body: {},
  })
}

