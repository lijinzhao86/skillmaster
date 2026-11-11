import { useEffect, useState, useSyncExternalStore } from 'react'
import { go, param } from '../address'
import { failureMessage } from '../api/errors'
import {
  acceptInvitation,
  declineInvitation,
  listNotifications,
  markAllRead,
  markRead,
} from '../api/notifications'
import type { Notification } from '../api/types'
import NoticeRow from '../components/notifications/NoticeRow'
import Pager from '../components/Pager'
import { getUnread, setUnread, subscribeUnread } from '../components/notifications/unreadStore'

/**
 * 通知：**待处理与全部**。
 *
 * The first tab is the action list; the second is the archive. Read and handled are two different
 * facts — a read invitation is still waiting, and a result notification may be unread after the
 * thing it reports is long settled — so the row draws both.
 */
export default function NotificationsPage() {
  const tab = param('status') === 'all' ? 'all' : 'pending'
  const askedPage = Math.max(1, Number.parseInt(param('p') ?? '1', 10) || 1)
  const [page, setPage] = useState(askedPage)
  const [rows, setRows] = useState<Notification[]>([])
  const [total, setTotal] = useState(0)
  const unread = useSyncExternalStore(subscribeUnread, getUnread, getUnread)
  const [banner, setBanner] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  const pageSize = 10

  async function load(atPage: number = page): Promise<void> {
    setLoading(true)
    const result = await listNotifications(tab, false, atPage)
    setLoading(false)
    if (!result.ok) {
      setBanner(failureMessage(result))
      setRows([])
      return
    }
    setBanner(null)
    setRows(result.data.items)
    setTotal(result.data.total)
    setUnread(result.data.unread)
    // A page past the end is a stale link; move to the last page the server actually has.
    const last = Math.max(1, Math.ceil(result.data.total / pageSize))
    if (atPage > last && result.data.total > 0) {
      setPage(last)
      go(`/notifications?${tab === 'all' ? 'status=all&' : ''}p=${last}`)
    }
  }

  useEffect(() => {
    setPage(askedPage)
    void load(askedPage)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, askedPage])

  async function readAll(): Promise<void> {
    const result = await markAllRead()
    if (!result.ok) {
      setBanner(failureMessage(result))
      return
    }
    await load()
  }

  async function accept(id: string): Promise<void> {
    const result = await acceptInvitation(id)
    if (!result.ok) {
      // A 409 can mean the server already closed this invitation (expired, answered elsewhere).
      // The row on screen is stale, so re-read it before saying why. The sentence stays on screen
      // because the reload clears the banner on success.
      const message = failureMessage(result)
      if (result.code === 'invalid_notification_state') await load()
      setBanner(message)
      return
    }
    await load()
  }

  async function decline(id: string): Promise<void> {
    const result = await declineInvitation(id)
    if (!result.ok) {
      const message = failureMessage(result)
      if (result.code === 'invalid_notification_state') await load()
      setBanner(message)
      return
    }
    await load()
  }

  async function view(row: Notification): Promise<void> {
    await markRead(row.id)
    if (row.skill !== null) {
      go(`/skills/${encodeURIComponent(row.skill.namespace)}/${encodeURIComponent(row.skill.name)}`)
    }
  }

  async function handle(row: Notification): Promise<void> {
    await markRead(row.id)
    if (row.skill !== null) {
      go(
        `/skills/${encodeURIComponent(row.skill.namespace)}/${encodeURIComponent(row.skill.name)}` +
          '?tab=settings&sub=collaborators',
      )
    }
  }

  const pagerHref = (target: number): string => {
    const query = new URLSearchParams()
    if (tab === 'all') query.set('status', 'all')
    if (target > 1) query.set('p', String(target))
    const text = query.toString()
    return text === '' ? '/notifications' : `/notifications?${text}`
  }

  return (
    <section className="page" id="page-notifications">
      <header className="page-head">
        <h1>通知</h1>
        {unread > 0 && <span className="count">{unread > 99 ? '99+' : unread} 条未读</span>}
        <div className="page-actions">
          {unread > 0 && (
            <button type="button" className="link-button" onClick={() => void readAll()}>
              全部已读
            </button>
          )}
        </div>
      </header>

      <nav className="skill-tabs list-tabs" aria-label="通知的两份列表">
        <a
          className={'skill-tab' + (tab === 'pending' ? ' is-current' : '')}
          href="/notifications"
          aria-current={tab === 'pending' ? 'page' : undefined}
        >
          待处理
        </a>
        <a
          className={'skill-tab' + (tab === 'all' ? ' is-current' : '')}
          href="/notifications?status=all"
          aria-current={tab === 'all' ? 'page' : undefined}
        >
          全部
        </a>
      </nav>

      {banner !== null && <p className="banner">{banner}</p>}
      {loading && rows.length === 0 && <p className="loading">加载中…</p>}

      {!loading && rows.length === 0 && (
        <p className="empty">
          {tab === 'pending' ? (
            <>
              没有待处理的通知。<a href="/notifications?status=all">看全部</a>
            </>
          ) : (
            '还没有通知。有人与你共享 skill、或你申请权限时，通知会出现在这里。'
          )}
        </p>
      )}

      {rows.length > 0 && (
        <ul className="notices">
          {rows.map((row) => (
            <NoticeRow
              key={row.id}
              row={row}
              onAccept={(id) => void accept(id)}
              onDecline={(id) => void decline(id)}
              onView={(value) => void view(value)}
              onHandle={(value) => void handle(value)}
              onCopy={(value) => {
                // v61: the copy sheet lives on the skill's page, with the versions it offers —
                // this row only knows the address. Acting on the notice also reads it, as every
                // other row action here does.
                void markRead(value.id)
                if (value.skill !== null) {
                  go(
                    `/skills/${encodeURIComponent(value.skill.namespace)}/` +
                      `${encodeURIComponent(value.skill.name)}?copy=1`,
                  )
                }
              }}
            />
          ))}
          <li className="pager-row">
            <Pager
              total={total}
              page={page}
              hrefFor={pagerHref}
              onGo={(target) => go(pagerHref(target))}
            />
          </li>
        </ul>
      )}
    </section>
  )
}
