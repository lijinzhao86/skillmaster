/**
 * 通知中心：一屏通知的夹具，以及从它们算出来的两个数。
 *
 * ⚠️ **这些是夹具，演示件没有服务端。** 形状按产品将来真的会有的那份写：
 *
 * - **请求型通知是业务对象的指针。** `invitation` 与 `request` 的「还能不能处理」由邀请行、
 *   申请行决定；这条通知只记「谁对什么做了什么」。所以一条通知的 `state` 是**结果快照**，
 *   不是业务状态本身——它由业务对象在收口时写下，通知不反过来改业务。
 * - **已读与已处理是两件事。** `unread` 只影响未读数与那一枚点；一条已读的邀请仍然是
 *   `pending`，一条未读的结果通知仍然是 `approved`。两者正交。
 * - **留档。** 处理完的通知不删，`state` 换成结果（已接受 / 已拒绝 / 已过期 / 已撤销 /
 *   已批准 / 未通过 / 已撤回），在「全部」里按时间倒序可见。
 *
 * **公告这一行是底层能力的样张，不是本版的发布入口。** v45 不提供发布面；它在这里是为了让
 * 「消息型通知长什么样」在设计件里有一处可指的地方。产品里它只会由测试夹具造出来。
 */

export type NoticeKind = 'invitation' | 'request' | 'result' | 'announcement' | 'skill_unavailable'

/** 一条邀请或一条申请的结果。`pending` 是唯一还在等人处理的那一个。 */
export type NoticeState =
  | 'pending'
  | 'accepted'
  | 'declined'
  | 'expired'
  | 'revoked'
  | 'approved'
  | 'rejected'
  | 'withdrawn'
  /* v56: information, not work. Same value the server sends for the announcement-shaped kinds. */
  | 'info'

export interface Notice {
  id: string
  kind: NoticeKind
  /** 触发这件事的人。公告没有。 */
  actor?: string
  /** 业务对象：一个 skill 的地址。公告没有。 */
  skill?: { namespace: string; name: string }
  /** 邀请给出的档位，或申请要的档位。 */
  role?: string
  state: NoticeState
  /** 演示件的固定时间；产品里是服务端的 `created_at`。 */
  at: string
  /** 产品里的 `resolved_at`：收口时刻。没有它时，过期行用 `expiresAt`。 */
  resolvedAt?: string
  /** 产品里的 `expires_at`：只有邀请有。 */
  expiresAt?: string
  unread: boolean
  /** 公告的标题与正文；其余三类的正文由 `kind + actor + skill + role` 拼出来。 */
  title?: string
  body?: string
}

/* **「我」是 dev**，与设计件其余各屏同一个账号。时刻按新到旧排。 */
export const NOTICES: Notice[] = [
  {
    id: 'n-1',
    kind: 'invitation',
    actor: 'yuhan',
    skill: { namespace: 'yuhan', name: 'api-client' },
    role: 'write',
    state: 'pending',
    at: '2026/11/04 09:12',
    unread: true,
  },
  {
    id: 'n-2',
    kind: 'request',
    actor: 'lin',
    skill: { namespace: 'dev', name: 'pdf-tools' },
    role: 'write',
    state: 'pending',
    at: '2026/11/03 18:40',
    unread: true,
  },
  {
    id: 'n-3',
    kind: 'result',
    skill: { namespace: 'yuhan', name: 'api-client' },
    role: 'write',
    state: 'approved',
    at: '2026/11/02 10:05',
    resolvedAt: '2026/11/03 10:05',
    unread: true,
  },
  {
    id: 'n-4',
    kind: 'invitation',
    actor: 'wang',
    skill: { namespace: 'dev', name: 'report-writer' },
    role: 'browse',
    state: 'accepted',
    at: '2026/10/27 14:20',
    resolvedAt: '2026/10/28 14:20',
    unread: false,
  },
  {
    id: 'n-5',
    kind: 'invitation',
    actor: 'zhao',
    skill: { namespace: 'dev', name: 'deck-builder' },
    role: 'invoke',
    state: 'expired',
    at: '2026/09/01 11:00',
    expiresAt: '2026/10/01 11:00',
    unread: false,
  },
  {
    // v56: the author deleted their account, so what they shared stopped working. Nothing to
    // accept or decline — the row says what happened and points at the skill that is no longer
    // usable.
    id: 'n-6a',
    kind: 'skill_unavailable',
    // A different author and skill from n-1 on purpose: one row cannot say a person left while
    // another still shows a pending invitation from them.
    actor: 'sun',
    skill: { namespace: 'sun', name: 'design-notes' },
    state: 'info',
    at: '2026/11/04 10:20',
    unread: true,
  },
  {
    id: 'n-6',
    kind: 'announcement',
    state: 'pending',
    title: '平台维护',
    body: '11 月 9 日 02:00–03:00 维护，期间控制台与 CLI 可能短暂不可用。',
    at: '2026/10/30 10:00',
    unread: false,
  },
  {
    id: 'n-7',
    kind: 'result',
    skill: { namespace: 'qian', name: 'release-notes' },
    role: 'maintain',
    state: 'rejected',
    at: '2026/10/19 16:30',
    resolvedAt: '2026/10/20 16:30',
    unread: false,
  },
]

/** 未读数：一条通知的 `unread` 为真就算，不管它是否已经处理完。 */
export function unreadCount(rows: Notice[] = NOTICES): number {
  return rows.filter((row) => row.unread).length
}

/** 待处理：只有还在等人的邀请与申请。结果通知与公告不在这里。 */
export function isPending(row: Notice): boolean {
  return row.state === 'pending' && (row.kind === 'invitation' || row.kind === 'request')
}

/** 顶栏那枚数字的写法：99 以上封顶。 */
export function badgeText(count: number): string {
  return count > 99 ? '99+' : String(count)
}
