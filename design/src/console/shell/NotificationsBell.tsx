/**
 * 顶栏的通知入口：**点击开、Esc 关、点外面关**，窄屏直接进通知页。
 *
 * **为什么不是 hover。** 触屏没有 hover（第一次点按常被当成 hover，要再点一次才开）；
 * hover 也不代表意图——鼠标从搜索框移到账号，路过铃铛就会弹出并挡住内容。WCAG 2.2 的
 * 1.4.13 还要求 hover 触发的附加内容可关闭、可移入、可保持。业界（Slack / Linear /
 * GitLab）也都是点击。hover 只留给铃铛自己的高亮。
 *
 * **下拉只放「要你看的」**：待处理或未读，最多 5 条；历史在页面里。底部一条「查看全部」。
 * 共享邀请在行里直接接受 / 拒绝——与通知页同一个 `NoticeRow`，只换密度。
 *
 * **窄屏直接进页。** 400px 放不下 360px 的浮层；手机上不需要一个盖住屏幕的小窗口。
 */

import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { go, replace } from '../address'
import { dialogs } from '../components/dialogs/store'
import NoticeRow from '../components/notifications/NoticeRow'
import { badgeText, isPending } from '../fixtures/notifications'
import { accept, decline, markAllRead, markRead, snapshot, subscribe, unread } from '../notificationsStore'

const NARROW = '(max-width: 640px)'
const RECENT = 5

export default function NotificationsBell({ page, params }: { page: string; params: URLSearchParams }) {
  const rows = useSyncExternalStore(subscribe, snapshot, snapshot)
  const unreadTotal = useSyncExternalStore(subscribe, unread, unread)
  /* `?bell=open` 是演示件里「这一层打开着」的地址——否则它只能点出来，拍不到、发不出去。 */
  const [open, setOpen] = useState(() => params.get('bell') === 'open')
  const wrap = useRef<HTMLDivElement>(null)
  const btn = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (event: MouseEvent) => {
      const target = event.target as Node
      if (wrap.current?.contains(target)) return
      setOpen(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setOpen(false)
      btn.current?.focus()
    }
    document.addEventListener('click', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('click', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  /* 窗口被拖窄时关掉浮层：窄屏下它不出现，留一个看不见的 open 会让再拖宽时它自己冒出来。 */
  useEffect(() => {
    const query = window.matchMedia(NARROW)
    const onChange = () => { if (query.matches) setOpen(false) }
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])

  const onBell = () => {
    if (window.matchMedia(NARROW).matches) {
      go('?page=notifications')
      return
    }
    setOpen((value) => !value)
  }

  /* **「要你看的」= 待处理，或还没读。** 结果与公告读过之后不必再占下拉的位置；
     一条已读的邀请仍然待接受，所以它在。 */
  const longName = 'design-system-team/vendor-screenshot-annotator-with-region-notes-and-tickets'
  const shown = rows
    .filter((row) => isPending(row) || row.unread)
    .slice(0, RECENT)
    /* 与通知页同一个 `?long=1`：把第一条换成实测过的最长地址，好让两行截断有一处可指的画面。 */
    .map((row) => (
      params.get('long') === '1' && row.id === 'n-1' && row.skill !== undefined
        ? { ...row, skill: { namespace: longName.split('/')[0], name: longName.split('/')[1] } }
        : row
    ))

  return (
    <div className="bell-wrap" ref={wrap}>
      <button
        type="button"
        className={'topbar-bell' + (page === 'notifications' ? ' is-active' : '')}
        ref={btn}
        aria-label={unreadTotal > 0 ? '通知，' + unreadTotal + ' 条未读' : '通知'}
        aria-expanded={open ? 'true' : 'false'}
        aria-controls="bellPop"
        aria-current={page === 'notifications' ? 'page' : undefined}
        onClick={onBell}
      >
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6.5 9.5a5.5 5.5 0 0 1 11 0c0 3.3.6 4.7 1.6 6H4.9c1-1.3 1.6-2.7 1.6-6Z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
          <path d="M10 18.6a2.2 2.2 0 0 0 4 0" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
        {unreadTotal > 0 && <span className="bell-badge">{badgeText(unreadTotal)}</span>}
      </button>

      <div className="bell-pop" id="bellPop" aria-label="通知" hidden={!open}>
        <div className="bell-pop-head">
          <span className="bell-pop-title">通知</span>
          {unreadTotal > 0 && <span className="count">{badgeText(unreadTotal)} 条未读</span>}
          {unreadTotal > 0 && (
            <button type="button" className="link-button" onClick={markAllRead}>全部已读</button>
          )}
        </div>
        {shown.length > 0 ? (
          <ul className="bell-pop-list">
            {shown.map((row) => (
              <NoticeRow
                key={row.id}
                row={row}
                compact
                onAccept={accept}
                onDecline={decline}
                onView={() => {
                  markRead(row.id)
                  setOpen(false)
                  replace('?page=skill')
                  window.scrollTo(0, 0)
                }}
                onHandle={() => {
                  markRead(row.id)
                  setOpen(false)
                  replace('?page=skill&tab=settings&sub=collaborators')
                  window.scrollTo(0, 0)
                }}
                onCopy={() => {
                  markRead(row.id)
                  setOpen(false)
                  dialogs.openCopy({
                    mode: 'open',
                    skillName: row.skill?.name ?? '',
                    versions: [{ value: 'latest', label: '线上版本' }],
                    initialVersion: 'latest',
                    trigger: null,
                  })
                }}
              />
            ))}
          </ul>
        ) : (
          <p className="bell-pop-empty">没有待处理的通知。</p>
        )}
        <button
          type="button"
          className="bell-pop-all"
          onClick={() => {
            setOpen(false)
            replace('?page=notifications')
            window.scrollTo(0, 0)
          }}
        >
          查看全部
        </button>
      </div>
    </div>
  )
}
