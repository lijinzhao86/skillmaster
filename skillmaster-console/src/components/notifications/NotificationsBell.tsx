import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { go } from '../../address'
import { failureMessage } from '../../api/errors'
import {
  acceptInvitation,
  declineInvitation,
  listNotifications,
  markAllRead,
  markRead,
} from '../../api/notifications'
import type { Notification } from '../../api/types'
import NoticeRow from './NoticeRow'
import { getUnread, refreshUnread, setUnread, subscribeUnread } from './unreadStore'

/** 99 is where the badge stops counting: whether anything happened matters more than how much. */
function badgeText(count: number): string {
  return count > 99 ? '99+' : String(count)
}

function skillAddress(row: Notification): string | null {
  return row.skill === null ? null : `/skills/${encodeURIComponent(row.skill.namespace)}/${encodeURIComponent(row.skill.name)}`
}

/**
 * 顶栏铃铛与它的快速查看（v45）。
 *
 * **Click, not hover.** The design's research settled this: a hover menu fails touch, fails WCAG's
 * content-on-hover rule, and opens on the way to somewhere else. Escape closes and returns focus;
 * a click outside closes too. At 640px and below the popover has no room, so the bell navigates to
 * the notification page instead.
 */
export default function NotificationsBell() {
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState<Notification[]>([])
  const unread = useSyncExternalStore(subscribeUnread, getUnread, getUnread)
  const [banner, setBanner] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // The notification page has no sidebar item of its own, by design: the bell *is* its entry, and
  // it is in the top bar on every page. Marking it current is what gives that page its orientation
  // without adding a second door to the same room.
  const onNotificationsPage = window.location.pathname.replace(/\/+$/, '') === '/notifications'
  const wrap = useRef<HTMLDivElement | null>(null)
  const button = useRef<HTMLButtonElement | null>(null)

  async function refreshRows(): Promise<void> {
    // The server's `per` is what the design's popover asks for; slicing a 10-row page in the
    // browser fetched twice as much as it drew.
    const result = await listNotifications('attention', false, undefined, 5)
    if (!result.ok) {
      setBanner(failureMessage(result))
      setRows([])
      return
    }
    setBanner(null)
    setRows(result.data.items.slice(0, 5))
    setUnread(result.data.unread)
  }

  useEffect(() => {
    void refreshUnread()
  }, [])

  // A popover left open while the window narrows would be invisible (≤640px) but still open, then
  // reappear on its own when the window widens again. The design closes it on the breakpoint.
  // jsdom has no matchMedia; the guard keeps every test that mounts the shell from needing one.
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const query = window.matchMedia('(max-width: 640px)')
    const onChange = (): void => {
      if (query.matches) setOpen(false)
    }
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])

  useEffect(() => {
    if (!open) return
    void refreshRows()
  }, [open])

  useEffect(() => {
    function onDocumentClick(event: MouseEvent): void {
      if (!open) return
      if (wrap.current?.contains(event.target as Node) ?? false) return
      setOpen(false)
    }
    function onEscape(event: KeyboardEvent): void {
      if (event.key !== 'Escape' || !open) return
      setOpen(false)
      button.current?.focus()
    }
    document.addEventListener('click', onDocumentClick)
    document.addEventListener('keydown', onEscape)
    return () => {
      document.removeEventListener('click', onDocumentClick)
      document.removeEventListener('keydown', onEscape)
    }
  }, [open])

  function toggle(): void {
    if (typeof window.matchMedia === 'function'
        && window.matchMedia('(max-width: 640px)').matches) {
      go('/notifications')
      return
    }
    setOpen((value) => !value)
  }

  async function accept(id: string): Promise<void> {
    setBusy(true)
    const result = await acceptInvitation(id)
    setBusy(false)
    if (!result.ok) {
      setBanner(failureMessage(result))
      return
    }
    await Promise.all([refreshRows(), refreshUnread()])
  }

  async function decline(id: string): Promise<void> {
    setBusy(true)
    const result = await declineInvitation(id)
    setBusy(false)
    if (!result.ok) {
      setBanner(failureMessage(result))
      return
    }
    await Promise.all([refreshRows(), refreshUnread()])
  }

  async function readAll(): Promise<void> {
    setBusy(true)
    const result = await markAllRead()
    setBusy(false)
    if (!result.ok) {
      setBanner(failureMessage(result))
      return
    }
    await Promise.all([refreshRows(), refreshUnread()])
  }

  async function openSkill(row: Notification): Promise<void> {
    await markRead(row.id)
    const address = skillAddress(row)
    if (address !== null) go(address)
  }

  async function openCollaborators(row: Notification): Promise<void> {
    await markRead(row.id)
    const address = skillAddress(row)
    if (address !== null) go(`${address}?tab=settings&sub=collaborators`)
  }

  return (
    <div ref={wrap} className="bell-wrap">
      <button
        ref={button}
        type="button"
        className={open || onNotificationsPage ? 'topbar-bell is-active' : 'topbar-bell'}
        aria-label={unread > 0 ? `通知，${unread} 条未读` : '通知'}
        aria-haspopup="true"
        aria-expanded={open ? 'true' : 'false'}
        aria-current={onNotificationsPage ? 'page' : undefined}
        onClick={toggle}
      >
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6 9a6 6 0 1 1 12 0c0 3.2.8 4.7 1.6 5.6H4.4C5.2 13.7 6 12.2 6 9Z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
          <path d="M10 18.2a2.2 2.2 0 0 0 4 0" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
        {unread > 0 && <span className="bell-badge">{badgeText(unread)}</span>}
      </button>

      {open && (
        <div className="bell-pop" role="dialog" aria-label="最近的通知">
          <div className="bell-pop-head">
            <span className="bell-pop-title">通知</span>
            {unread > 0 && <span className="count">{badgeText(unread)} 条未读</span>}
            {unread > 0 && (
              <button type="button" className="link-button" onClick={() => void readAll()}>
                全部已读
              </button>
            )}
          </div>
          {banner !== null && <p className="bell-pop-empty">{banner}</p>}
          {banner === null && rows.length === 0 && (
            <p className="bell-pop-empty">没有待处理的通知。</p>
          )}
          {rows.length > 0 && (
            <ul className="bell-pop-list">
              {rows.map((row) => (
                <NoticeRow
                  key={row.id}
                  row={row}
                  compact
                  onAccept={(id) => {
                    if (!busy) void accept(id)
                  }}
                  onDecline={(id) => {
                    if (!busy) void decline(id)
                  }}
                  onView={(value) => void openSkill(value)}
                  onHandle={(value) => void openCollaborators(value)}
                />
              ))}
            </ul>
          )}
          <a className="bell-pop-all" href="/notifications">查看全部</a>
        </div>
      )}
    </div>
  )
}
