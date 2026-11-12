/**
 * 通知：**待处理与全部**，以及一条通知该长什么样。
 *
 * **两种东西放在一张单子上，靠「待处理 / 全部」分开。** 邀请与申请要人答；结果通知与公告只是
 * 告知。默认那一份是「待处理」——这一页首先是动作清单，历史在第二个页签里。页签与页码都在
 * 地址里，所以筛过的那一屏发得出去、刷新后还在。
 *
 * **已读不是已处理。** 一枚点只说明「你还没看过」；一条邀请读过了仍然待接受。反过来，结果通知
 * 可能还没读，但事情早就办完了。所以行上的状态章与那枚点画的是两个事实，不要合并。
 *
 * **处理完的通知留档。** 接受 / 拒绝 / 过期 / 撤销之后，这一行不消失，换成结果章，留在「全部」里；
 * 用户回看得到自己过去处理过什么。
 *
 * 行本身在 `components/notifications/NoticeRow.tsx`——铃铛下拉用的是同一份标记，只换密度。
 * ⚠️ 顶栏的未读数与本页读同一份演示状态（`notificationsStore`）：产品里是同一份服务端状态。
 */

import { useSyncExternalStore } from 'react'
import { replace } from '../address'
import { dialogs } from '../components/dialogs/store'
import Pager from '../components/Pager'
import NoticeRow from '../components/notifications/NoticeRow'
import { OFFLINE_NOTICE, badgeText, isPending } from '../fixtures/notifications'
import type { Notice } from '../fixtures/notifications'
import { accept, decline, markAllRead, markRead, snapshot, subscribe, unread } from '../notificationsStore'
import type { DemoPageProps } from './types'

const PER_PAGE = 10

export default function NotificationsPage({ active, state, params }: DemoPageProps) {
  const rows = useSyncExternalStore(subscribe, snapshot, snapshot)
  const unreadTotal = useSyncExternalStore(subscribe, unread, unread)

  const tab = params.get('status') === 'all' ? 'all' : 'pending'
  const askedPage = parseInt(params.get('p') ?? '', 10)

  const settled = state !== 'loading' && state !== 'failed'
  /* **空态由地址定，不由夹具定**：`empty-pending` 与 `empty-all` 是演示条上的两态，
     它们把行滤掉，好让「空」这一屏能被拍到、被链接到。 */
  const forcedEmpty = state === 'empty-pending' ? 'pending' : state === 'empty-all' ? 'all' : null
  const source = params.get('notice') === 'offline'
    ? rows.map((row) => (row.id === 'n-6' ? OFFLINE_NOTICE : row))
    : rows
  const visible = forcedEmpty !== null || !settled
    ? []
    : source.filter((row) => (tab === 'pending' ? isPending(row) : true))

  let page = Number.isNaN(askedPage) || askedPage < 1 ? 1 : askedPage
  const pages = Math.max(1, Math.ceil(visible.length / PER_PAGE))
  if (page > pages) page = pages
  const start = (page - 1) * PER_PAGE
  const shown = visible.slice(start, start + PER_PAGE)

  const writeAddress = (next: { status?: 'pending' | 'all'; page?: number }) => {
    const copy = new URLSearchParams()
    copy.set('page', 'notifications')
    const nextStatus = next.status ?? tab
    if (nextStatus === 'all') copy.set('status', 'all')
    const nextPage = next.page ?? page
    if (nextPage > 1) copy.set('p', String(nextPage))
    /* 这一屏的其它事实跟着走：`state` 是演示条选中的那一态，`long` 是长地址那一态。 */
    const askedState = params.get('state')
    if (askedState !== null) copy.set('state', askedState)
    if (params.get('long') === '1') copy.set('long', '1')
    replace('?' + copy.toString())
  }

  /* 长地址那一态：把第一条待处理的 skill 换成实测过的最长的那一个，
     好让「名字很长时这一行还读得下去」有一处可指的画面。 */
  const longName = 'design-system-team/vendor-screenshot-annotator-with-region-notes-and-tickets'

  return (
    <section className="page" id="page-notifications" hidden={!active}>
      <header className="page-head">
        <h1>通知</h1>
        <span className="count" id="noticeCount" hidden={!settled || unreadTotal === 0}>
          {unreadTotal > 0 ? badgeText(unreadTotal) + ' 条未读' : ''}
        </span>
        <div className="page-actions">
          <button
            type="button"
            className="link-button"
            id="noticeReadAll"
            hidden={!settled || unreadTotal === 0}
            onClick={markAllRead}
          >
            全部已读
          </button>
        </div>
      </header>

      <nav
        className="skill-tabs list-tabs"
        id="noticeTabs"
        aria-label="通知的两份列表"
        hidden={!settled || (forcedEmpty === 'all')}
      >
        <a
          className={'skill-tab' + (tab === 'pending' ? ' is-current' : '')}
          data-show="pending"
          href="?page=notifications"
          aria-current={tab === 'pending' ? 'page' : undefined}
          onClick={(event) => { event.preventDefault(); writeAddress({ status: 'pending', page: 1 }) }}
        >
          待处理
        </a>
        <a
          className={'skill-tab' + (tab === 'all' ? ' is-current' : '')}
          data-show="all"
          href="?page=notifications&status=all"
          aria-current={tab === 'all' ? 'page' : undefined}
          onClick={(event) => { event.preventDefault(); writeAddress({ status: 'all', page: 1 }) }}
        >
          全部
        </a>
      </nav>

      <p className="loading" data-when="loading" hidden={state !== 'loading'}>加载中…</p>

      <div className="banner" data-when="failed" hidden={state !== 'failed'}>
        读不到通知，稍后再试一次。
      </div>

      {/* **两种空**：还没有任何通知，与「待处理清空了」。后者不是坏消息，所以给一条去全部的出路。 */}
      <p className="empty" id="noticeEmptyAll" hidden={!settled || forcedEmpty !== 'all'}>
        还没有通知。有人与你共享 skill、或你申请权限时，通知会出现在这里。
      </p>
      <p className="empty" id="noticeEmptyPending" hidden={!settled || forcedEmpty !== 'pending'}>
        没有待处理的通知。<a href="?page=notifications&status=all">看全部</a>
      </p>

      <ul className="notices" id="noticeList" hidden={!settled || shown.length === 0}>
        {shown.map((row) => {
          const long = params.get('long') === '1' && row.id === 'n-1' && row.skill !== undefined
          const display: Notice = long
            ? { ...row, skill: { namespace: longName.split('/')[0], name: longName.split('/')[1] } }
            : row
          return (
            <NoticeRow
              key={display.id}
              row={display}
              onAccept={accept}
              onDecline={decline}
              onView={() => {
                markRead(display.id)
                replace('?page=skill')
                window.scrollTo(0, 0)
              }}
              onHandle={() => {
                markRead(display.id)
                replace('?page=skill&tab=settings&sub=collaborators')
                window.scrollTo(0, 0)
              }}
              onCopy={() => {
                markRead(display.id)
                dialogs.openCopy({
                  mode: 'open',
                  skillName: display.skill?.name ?? '',
                  versions: [{ value: 'latest', label: '线上版本' }],
                  initialVersion: 'latest',
                  trigger: null,
                })
              }}
            />
          )
        })}
        {shown.length === 0 ? null : (
          <li className="pager-row">
            <Pager
              id="noticePager"
              total={visible.length}
              page={page}
              hrefFor={(target) => {
                const copy = new URLSearchParams()
                copy.set('page', 'notifications')
                if (tab === 'all') copy.set('status', 'all')
                if (target > 1) copy.set('p', String(target))
                const askedState = params.get('state')
                if (askedState !== null) copy.set('state', askedState)
                if (params.get('long') === '1') copy.set('long', '1')
                return '?' + copy.toString()
              }}
              onGo={(target) => writeAddress({ page: target })}
            />
          </li>
        )}
      </ul>

      {/* 全部里一条都没有，但「待处理」页签仍在时，不发第二句空态——上面那一句已经说了。 */}
      <p className="empty" id="noticeNone" hidden={!settled || forcedEmpty !== null || visible.length > 0}>
        {tab === 'pending' ? '没有待处理的通知。' : '还没有通知。'}
      </p>
    </section>
  )
}
