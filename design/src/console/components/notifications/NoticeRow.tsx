/**
 * 一条通知，两种密度：通知页的整行，与铃铛下拉里的紧凑行。
 *
 * **一份标记，两处用。** 首页与下拉要是各写一遍，句子、结果章与动作就会漂——而这两处说的
 * 是同一个事实。密度只差 `compact` 一个类：内边距、字号与动作换行由 CSS 决定，结构不动。
 *
 * 这是「props 进、标记出」的组件：接受 / 拒绝由调用方给，不自己读 store。
 */

import type { KeyboardEvent as ReactKeyboardEvent, MouseEvent as ReactMouseEvent, ReactNode } from 'react'
import { rungLabel } from '../../fixtures/skill'
import type { Notice } from '../../fixtures/notifications'

const RESULT_LABEL: Record<Notice['state'], string> = {
  pending: '待处理',
  accepted: '已接受',
  declined: '已拒绝',
  expired: '已过期',
  revoked: '已撤销',
  approved: '已批准',
  rejected: '未通过',
  withdrawn: '已撤回',
  info: '',
}

/** 已收口的行，状态章用哪一档底色。颜色只是第三层信号，字先说明白。 */
function resultClass(state: Notice['state']): string {
  if (state === 'accepted' || state === 'approved') return 'chip is-live'
  if (state === 'declined' || state === 'rejected' || state === 'revoked' || state === 'withdrawn') return 'chip is-revoked'
  if (state === 'expired') return 'chip is-expired'
  return 'chip is-plain'
}

function KindIcon({ kind }: { kind: Notice['kind'] }) {
  if (kind === 'invitation') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="9.5" cy="8" r="3.2" stroke="currentColor" strokeWidth="1.8" />
        <path d="M3.8 19c.7-3 3.1-4.7 5.7-4.7s5 1.7 5.7 4.7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        <path d="M18.5 7v6M15.5 10h6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      </svg>
    )
  }
  if (kind === 'request') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M12 19V6M7.5 10.5 12 6l4.5 4.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M5 20h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      </svg>
    )
  }
  if (kind === 'result') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.8" />
        <path d="m8.4 12.2 2.4 2.4 4.8-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )
  }
  /* v56: a person and a cut link — **not** the announcement's speaker. This kind is about an author
     who left, and reusing the broadcast icon would read as platform news. */
  if (kind === 'skill_unavailable') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="9.5" cy="8" r="3.2" stroke="currentColor" strokeWidth="1.8" />
        <path d="M3.8 19c.7-3 3.1-4.7 5.7-4.7 1.5 0 2.8.5 3.8 1.4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        <path d="m15.5 12.5 4 4M19.5 12.5l-4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      </svg>
    )
  }
  /* v61: a clock — it is a scheduled departure, not a person leaving and not a broadcast. */
  if (kind === 'skill_offline_scheduled') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.8" />
        <path d="M12 7.5V12l3 2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )
  }
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M4 10v4a1 1 0 0 0 1 1h2l5 3V6L7 9H5a1 1 0 0 0-1 1Z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
      <path d="M16 9.5a3.5 3.5 0 0 1 0 5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M18.6 7a7 7 0 0 1 0 10" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  )
}

/** 地址里的一行：`dev / pdf-tools` 与其余各屏同一个写法，命名空间淡一档。 */
function SkillName({ skill }: { skill: NonNullable<Notice['skill']> }) {
  return (
    <span className="notice-skill" title={skill.namespace + ' / ' + skill.name}>
      <span className="ns">{skill.namespace} /</span> {skill.name}
    </span>
  )
}

function sentence(row: Notice): ReactNode {
  const role = row.role === undefined ? '' : rungLabel(row.role)
  if (row.kind === 'announcement') {
    return (
      <>
        <span className="notice-title">{row.title}</span>
        <span className="notice-announce">{row.body}</span>
      </>
    )
  }
  if (row.kind === 'skill_unavailable') {
    return <><b>{row.actor}</b> 注销了账号，<SkillName skill={row.skill!} /> 已不可用</>
  }
  if (row.kind === 'skill_offline_scheduled') {
    return (
      <>
        <b>{row.actor}</b> 将于 {row.expiresAt?.split(' ')[0]} 下线 <SkillName skill={row.skill!} />。
        要保留它，复制到你的库。
      </>
    )
  }
  if (row.kind === 'result') {
    return row.state === 'approved'
      ? <>你对 <SkillName skill={row.skill!} /> 的「{role}」申请已批准</>
      : <>你对 <SkillName skill={row.skill!} /> 的「{role}」申请未通过</>
  }
  if (row.kind === 'request') {
    return row.state === 'withdrawn'
      ? <><b>{row.actor}</b> 撤回了对 <SkillName skill={row.skill!} /> 的「{role}」申请</>
      : <><b>{row.actor}</b> 申请 <SkillName skill={row.skill!} /> 的「{role}」档位</>
  }
  if (row.state === 'pending') {
    return <><b>{row.actor}</b> 与你共享 <SkillName skill={row.skill!} />，档位「{role}」</>
  }
  if (row.state === 'accepted') {
    return <>你已接受 <b>{row.actor}</b> 共享的 <SkillName skill={row.skill!} /></>
  }
  if (row.state === 'declined') {
    return <>你拒绝了 <b>{row.actor}</b> 共享的 <SkillName skill={row.skill!} /></>
  }
  if (row.state === 'revoked') {
    return <><b>{row.actor}</b> 撤销了共享 <SkillName skill={row.skill!} /> 的邀请</>
  }
  return <><b>{row.actor}</b> 共享 <SkillName skill={row.skill!} /> 的邀请已过期</>
}

export default function NoticeRow({
  row,
  compact = false,
  onAccept,
  onDecline,
  onView,
  onHandle,
  onCopy,
}: {
  row: Notice
  /** 铃铛下拉里的那一份：两行网格，正文最多两行，动作落在元信息行右侧。 */
  compact?: boolean
  onAccept: (id: string) => void
  onDecline: (id: string) => void
  /** 打开这条通知指向的 **skill 详情页**。**用动作，不用 `<a href>`**：浮层会在点击后关闭，
     相对链接的默认跳转在某些浏览器里会随着节点被卸载而丢掉——这是它「点了没反应」的根因。 */
  onView: () => void
  /** 待处理申请的去处：那个 skill 的「设置 › 协作者」。 */
  onHandle: () => void
  /** v61：下线预告里的迁移出路——把这个 skill 复制到自己的库。 */
  onCopy?: (row: Notice) => void
}) {
  const pendingInvitation = row.kind === 'invitation' && row.state === 'pending'
  const pendingRequest = row.kind === 'request' && row.state === 'pending'
  /* **不是每条已处理的通知都还有东西可看。** 已接受与已批准意味着「你现在能进去了」；
     未通过的人原本就看得见那个 skill（申请的前提），所以也留「查看」。
     已拒绝 / 已过期 / 已撤销则不一定——私有 skill 根本打不开，放一个会 404 的「查看」
     比没有这个动作更糟。公告本版没有发布面，也就没有链接。 */
  /* `viewable === false` 与服务端一致：已被删除的那个地址打开是 404，不给「查看」。 */
  const canView = !pendingInvitation && !pendingRequest &&
    row.kind !== 'announcement' && row.kind !== 'skill_unavailable' &&
    row.kind !== 'skill_offline_scheduled' && row.viewable !== false &&
    (row.state === 'accepted' || row.state === 'approved' || row.state === 'rejected')
  const canCopy = row.kind === 'skill_offline_scheduled' && onCopy !== undefined
  const destination = pendingRequest ? onHandle : canView ? onView : null
  const hasAction = pendingInvitation || pendingRequest || canView || canCopy

  const actions = (
    <>
      {pendingInvitation && (
        <>
          <button type="button" className="button" onClick={() => onAccept(row.id)}>接受</button>
          <button type="button" className="button is-quiet" onClick={() => onDecline(row.id)}>拒绝</button>
        </>
      )}
      {pendingRequest && (
        <button type="button" className="button is-quiet" onClick={onHandle}>去处理</button>
      )}
      {canCopy && (
        <button type="button" className="button is-quiet" onClick={() => onCopy(row)}>复制到我的库</button>
      )}
      {canView && (
        <button type="button" className="link-button" onClick={onView}>查看</button>
      )}
    </>
  )
  const meta = (
    <>
      <time>{row.resolvedAt ?? (row.state === 'expired' ? row.expiresAt : undefined) ?? row.at}</time>
      {row.state === 'pending' || row.state === 'info'
        ? null
        : <span className={resultClass(row.state)} data-result={row.state}>{RESULT_LABEL[row.state]}</span>}
      {row.unread && (
        <span className="notice-unread"><span className="visually-hidden">未读</span></span>
      )}
    </>
  )

  /* **有去向的行整行可点**，「查看 / 去处理」只是行尾的可见提示：24px 宽的一段文字，
     点偏一点就没反应。行里的按钮自己处理点击，行不抢——与「我的 skill」那一行的做法一致。 */
  const rowProps = {
    'data-kind': row.kind,
    'data-state': row.state,
    className: 'notice' +
      (compact ? ' is-compact' : '') +
      (destination !== null ? ' is-clickable' : '') +
      (row.unread ? ' is-unread' : ''),
    role: destination !== null ? 'button' : undefined,
    tabIndex: destination !== null ? 0 : undefined,
    onClick: destination !== null
      ? (event: ReactMouseEvent<HTMLLIElement>) => {
        if ((event.target as HTMLElement).closest('button, a')) return
        destination()
      }
      : undefined,
    onKeyDown: destination !== null
      ? (event: ReactKeyboardEvent<HTMLLIElement>) => {
        if (event.target !== event.currentTarget) return
        if (event.key !== 'Enter' && event.key !== ' ') return
        event.preventDefault()
        destination()
      }
      : undefined,
  }

  /* **紧凑的那一份不是「把整行缩小」，而是换一种排法。** 下拉只有 360px：正文与两颗按钮并排，
     句子会被压成一条缝；动作整行落到下面，每条又高出一截。所以正文独占一行（最多两行），
     时间、结果章与动作挤在第二行——动作靠右，与时间同一条基线。 */
  if (compact) {
    return (
      <li {...rowProps}>
        <span className="notice-icon"><KindIcon kind={row.kind} /></span>
        <div className="notice-body">
          <p className="notice-text is-clamped">{sentence(row)}</p>
          <div className="notice-meta">
            {meta}
            {hasAction && <span className="notice-acts">{actions}</span>}
          </div>
        </div>
      </li>
    )
  }

  return (
    <li {...rowProps}>
      <span className="notice-icon"><KindIcon kind={row.kind} /></span>
      <div className="notice-body">
        <p className="notice-text">{sentence(row)}</p>
        <p className="notice-meta">{meta}</p>
      </div>
      <div className="notice-acts">{actions}</div>
    </li>
  )
}
