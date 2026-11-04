/**
 * 通知的演示状态：页面与顶栏读同一份，动作之后两边一起变。
 *
 * ⚠️ **这是演示件的替身，不是产品形状。** 产品里这份状态在服务端，顶栏与列表各拉一次；
 * 这里没有服务端，而顶栏的未读数与列表的已读状态又是同一个事实，所以让它们订阅同一份内存。
 * 不这样做的话，「全部已读」之后顶栏那枚数字会停在原处——一屏里同一个数有两个答案。
 *
 * 只放演示需要的三个动作：全部已读、接受、拒绝。产品里它们各自是一条写请求。
 */

import { NOTICES, unreadCount } from './fixtures/notifications'
import type { Notice } from './fixtures/notifications'

let rows: Notice[] = NOTICES.map((row) => ({ ...row }))
const listeners = new Set<() => void>()

function emit() {
  listeners.forEach((listener) => listener())
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

export function snapshot(): Notice[] {
  return rows
}

export function unread(): number {
  return unreadCount(rows)
}

export function markAllRead() {
  rows = rows.map((row) => (row.unread ? { ...row, unread: false } : row))
  emit()
}

/** 打开一条通知（查看 / 去处理）时顺手标掉它自己——不新增按钮，未读点才收得掉。 */
export function markRead(id: string) {
  rows = rows.map((row) => (row.id === id && row.unread ? { ...row, unread: false } : row))
  emit()
}

export function accept(id: string) {
  rows = rows.map((row) => (
    row.id === id ? { ...row, state: 'accepted', unread: false } : row
  ))
  emit()
}

export function decline(id: string) {
  rows = rows.map((row) => (
    row.id === id ? { ...row, state: 'declined', unread: false } : row
  ))
  emit()
}
