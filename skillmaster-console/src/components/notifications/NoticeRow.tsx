import type { KeyboardEvent as ReactKeyboardEvent, MouseEvent as ReactMouseEvent, ReactNode } from 'react'
import type { Notification, NotificationState } from '../../api/types'
import { rungLabel } from '../../rungs'
import { formatDay, formatTime } from '../../time'

const RESULT_LABEL: Record<NotificationState, string> = {
  pending: '待处理',
  accepted: '已接受',
  declined: '已拒绝',
  expired: '已过期',
  revoked: '已撤销',
  approved: '已批准',
  rejected: '未通过',
  withdrawn: '已撤回',
  info: '通知',
}

function resultClass(state: NotificationState): string {
  if (state === 'accepted' || state === 'approved') return 'chip is-live'
  if (state === 'declined' || state === 'rejected' || state === 'revoked'
      || state === 'withdrawn') return 'chip is-revoked'
  if (state === 'expired') return 'chip is-expired'
  return 'chip is-plain'
}

function KindIcon({ kind }: { kind: Notification['kind'] }) {
  if (kind === 'share_invitation') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="9.5" cy="8" r="3.2" stroke="currentColor" strokeWidth="1.8" />
        <path d="M3.8 19c.7-3 3.1-4.7 5.7-4.7s5 1.7 5.7 4.7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        <path d="M18.5 7v6M15.5 10h6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      </svg>
    )
  }
  if (kind === 'access_request') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M12 19V6M7.5 10.5 12 6l4.5 4.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M5 20h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      </svg>
    )
  }
  if (kind === 'access_request_result') {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.8" />
        <path d="m8.4 12.2 2.4 2.4 4.8-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )
  }
  /* v56: the author left and the link to their skill is cut. **Not the announcement's speaker** —
     reusing it would read as platform news (design `NoticeRow.tsx`). */
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

function SkillName({ skill }: { skill: NonNullable<Notification['skill']> }) {
  return (
    <span className="notice-skill" title={skill.namespace + ' / ' + skill.name}>
      <span className="ns">{skill.namespace} /</span> {skill.name}
    </span>
  )
}

function sentence(row: Notification): ReactNode {
  const role = row.role === null ? '' : rungLabel(row.role)
  // The purge clears the stored handle; the row itself stays for the other recipient, so the
  // subject falls back to what the account now reads as rather than to an empty name.
  const actor = row.actor ?? '已注销账号'
  if (row.kind === 'announcement') {
    return (
      <>
        <span className="notice-title">{row.title}</span>
        <span className="notice-announce">{row.body}</span>
      </>
    )
  }
  if (row.skill === null) {
    return row.title ?? ''
  }
  // v56: the author left, and what they shared stopped working. No accept/decline — there is
  // nothing left to answer (ADR 0175).
  if (row.kind === 'skill_unavailable') {
    return (
      <>
        <b>{actor}</b> 注销了账号，<SkillName skill={row.skill} /> 已不可用
      </>
    )
  }
  /* v61: still serving, and still time to keep a copy. The deadline is the notification's
     `expires_at` — the server sets it to the ramp's due date, so the two cannot disagree. */
  if (row.kind === 'skill_offline_scheduled') {
    const deadline = formatDay(row.expires_at)
    return (
      <>
        <b>{actor}</b> 将于 {deadline} 下线 <SkillName skill={row.skill} />。
        要保留它，复制到你的库。
      </>
    )
  }
  if (row.kind === 'access_request_result') {
    return row.state === 'approved'
      ? <>你对 <SkillName skill={row.skill} /> 的「{role}」申请已批准</>
      : <>你对 <SkillName skill={row.skill} /> 的「{role}」申请未通过</>
  }
  if (row.kind === 'access_request') {
    return row.state === 'withdrawn'
      ? <><b>{actor}</b> 撤回了对 <SkillName skill={row.skill} /> 的「{role}」申请</>
      : <><b>{actor}</b> 申请 <SkillName skill={row.skill} /> 的「{role}」档位</>
  }
  if (row.state === 'pending') {
    return <><b>{actor}</b> 与你共享 <SkillName skill={row.skill} />，档位「{role}」</>
  }
  if (row.state === 'accepted') {
    return <>你已接受 <b>{actor}</b> 共享的 <SkillName skill={row.skill} /></>
  }
  if (row.state === 'declined') {
    return <>你拒绝了 <b>{actor}</b> 共享的 <SkillName skill={row.skill} /></>
  }
  if (row.state === 'revoked') {
    return <><b>{actor}</b> 撤销了共享 <SkillName skill={row.skill} /> 的邀请</>
  }
  return <><b>{actor}</b> 共享 <SkillName skill={row.skill} /> 的邀请已过期</>
}

export interface NoticeRowProps {
  row: Notification
  compact?: boolean
  onAccept: (id: string) => void
  onDecline: (id: string) => void
  /** Opens the skill's own page. */
  onView: (row: Notification) => void
  /** Opens the skill's settings › collaborators. */
  onHandle: (row: Notification) => void
  /** v61: the ramp's way out — takes the reader to that skill's copy sheet. */
  onCopy?: (row: Notification) => void
}

export default function NoticeRow({
  row,
  compact = false,
  onAccept,
  onDecline,
  onView,
  onHandle,
  onCopy,
}: NoticeRowProps) {
  const pendingInvitation = row.kind === 'share_invitation' && row.state === 'pending'
  const pendingRequest = row.kind === 'access_request' && row.state === 'pending'
  /* Not every handled row still has somewhere to go. Accepted and approved mean "you can enter
     now"; rejected means the person could already see the skill when they asked, so the page is
     still theirs. Declined / expired / revoked may point at a private skill, and a 「查看」 that
     404s is worse than none. An announcement has no publisher in this version. */
  const canView =
    !pendingInvitation &&
    !pendingRequest &&
    row.kind !== 'announcement' &&
    row.kind !== 'skill_offline_scheduled' &&
    row.viewable &&
    (row.state === 'accepted' || row.state === 'approved' || row.state === 'rejected')
  /* v61: the ramp's only action is the way out — a snapshot of your own. The row is not a link:
     the point is to take a copy before the service goes, not to browse it now. */
  const canCopy = row.kind === 'skill_offline_scheduled' && onCopy !== undefined
  const destination = pendingRequest ? () => onHandle(row) : canView ? () => onView(row) : null
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
        <button type="button" className="button is-quiet" onClick={() => onHandle(row)}>去处理</button>
      )}
      {canView && (
        <button type="button" className="link-button" onClick={() => onView(row)}>查看</button>
      )}
      {canCopy && (
        <button type="button" className="button is-quiet" onClick={() => onCopy?.(row)}>
          复制到我的库
        </button>
      )}
    </>
  )
  const meta = (
    <>
      {/* A settled row shows when it was settled; an invitation that expired without an answer has
          no resolved_at, so its own expiry is the moment that answers "when". */}
      <time>
        {formatTime(
          row.resolved_at ??
            (row.state === 'expired' ? row.expires_at : null) ??
            row.created_at,
        )}
      </time>
      {row.state === 'pending' || row.state === 'info' ? null : (
        <span className={resultClass(row.state)} data-result={row.state}>
          {RESULT_LABEL[row.state]}
        </span>
      )}
      {!row.read && (
        <span className="notice-unread"><span className="visually-hidden">未读</span></span>
      )}
    </>
  )

  const rowProps = {
    'data-kind': row.kind,
    'data-state': row.state,
    className:
      'notice' +
      (compact ? ' is-compact' : '') +
      (destination !== null ? ' is-clickable' : '') +
      (!row.read ? ' is-unread' : ''),
    role: destination !== null ? 'button' : undefined,
    tabIndex: destination !== null ? 0 : undefined,
    onClick:
      destination !== null
        ? (event: ReactMouseEvent<HTMLLIElement>) => {
            if ((event.target as HTMLElement).closest('button, a')) return
            destination()
          }
        : undefined,
    onKeyDown:
      destination !== null
        ? (event: ReactKeyboardEvent<HTMLLIElement>) => {
            if (event.target !== event.currentTarget) return
            if (event.key !== 'Enter' && event.key !== ' ') return
            event.preventDefault()
            destination()
          }
        : undefined,
  }

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
