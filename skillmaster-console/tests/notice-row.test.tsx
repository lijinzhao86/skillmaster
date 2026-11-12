import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import NoticeRow from '../src/components/notifications/NoticeRow'
import type { Notification } from '../src/api/types'

const accepted: Notification = {
  id: 'n-1',
  kind: 'share_invitation',
  state: 'accepted',
  actor: 'yuhan',
  skill: { namespace: 'yuhan', name: 'api-client' },
  role: 'write',
  title: null,
  body: null,
  created_at: '2026-11-01T00:00:00Z',
  resolved_at: '2026-11-03T00:00:00Z',
  expires_at: '2026-12-01T00:00:00Z',
  read: true,
  viewable: true,
}

function draw(row: Notification) {
  return render(
    <NoticeRow
      row={row}
      onAccept={vi.fn()}
      onDecline={vi.fn()}
      onView={vi.fn()}
      onHandle={vi.fn()}
    />,
  )
}

describe('NoticeRow', () => {
  it('does not offer 查看 when the skill can no longer be opened', () => {
    const { container, queryByText } = draw({ ...accepted, viewable: false })
    expect(queryByText('查看')).toBeNull()
    expect(container.querySelector('.notice')?.getAttribute('role')).toBeNull()
    expect(container.querySelector('.notice')?.className).not.toContain('is-clickable')
  })

  it('offers 查看 and makes the whole row clickable when it can', () => {
    const { container, queryByText } = draw(accepted)
    expect(queryByText('查看')).not.toBeNull()
    expect(container.querySelector('.notice')?.getAttribute('role')).toBe('button')
    expect(container.querySelector('.notice')?.className).toContain('is-clickable')
  })

  it('shows when the invitation was answered, not when it was sent', () => {
    const text = draw(accepted).container.textContent ?? ''
    expect(text).toContain('2026/11/03')
    expect(text).not.toContain('2026/11/01')
  })

  it('uses expires_at when an invitation expired without an answer', () => {
    const text = draw({
      ...accepted,
      state: 'expired',
      resolved_at: null,
      expires_at: '2026-11-05T00:00:00Z',
    }).container.textContent ?? ''
    expect(text).toContain('2026/11/05')
    expect(text).not.toContain('2026/11/01')
  })

  it('says a skill stopped working because its author deleted their account (v56)', () => {
    const { container, queryByText } = draw({
      ...accepted,
      kind: 'skill_unavailable',
      state: 'info',
      role: null,
      title: null,
      body: null,
      read: true,
      viewable: false,
    })
    expect(container.textContent).toContain('注销了账号')
    expect(container.textContent).toContain('api-client')
    // Nothing to answer: the author is gone, so no accept/decline buttons.
    expect(queryByText('接受')).toBeNull()
    expect(queryByText('拒绝')).toBeNull()
  })

  it('does not reuse the announcement speaker for an author who left (v56)', () => {
    const { container } = draw({
      ...accepted,
      kind: 'skill_unavailable',
      state: 'info',
      role: null,
      read: true,
      viewable: false,
    })
    // The cut link, not the broadcast speaker: this is news about a person, not the platform.
    expect(container.querySelector('svg path[d^="m15.5 12.5"]')).not.toBeNull()
    expect(container.querySelector('svg path[d^="M4 10v4"]')).toBeNull()
  })

  it('falls back to 已注销账号 when the purge has cleared the actor handle (v56)', () => {
    const text = draw({
      ...accepted,
      kind: 'skill_unavailable',
      state: 'info',
      actor: null,
      role: null,
      read: true,
      viewable: false,
    }).container.textContent ?? ''
    expect(text).toContain('已注销账号')
    expect(text).toContain('注销了账号')
  })

  it('draws no result chip on an announcement', () => {
    const { queryByText } = draw({
      ...accepted,
      kind: 'announcement',
      state: 'info',
      actor: null,
      skill: null,
      role: null,
      title: '平台维护',
      body: '11 月 9 日维护',
      read: true,
      viewable: false,
    })
    expect(queryByText('通知')).toBeNull()
  })

  // v61 (ADR 0184): a 下线中 skill still serves, and the row's job is the way out — a copy.
  it('says when a ramping skill goes offline, and offers the copy (v61)', () => {
    const onCopy = vi.fn()
    const row: Notification = {
      ...accepted,
      kind: 'skill_offline_scheduled',
      state: 'info',
      role: null,
      read: false,
      expires_at: '2026-12-11T00:00:00Z',
    }
    const { container, queryByText } = render(
      <NoticeRow
        row={row}
        onAccept={vi.fn()}
        onDecline={vi.fn()}
        onView={vi.fn()}
        onHandle={vi.fn()}
        onCopy={onCopy}
      />,
    )
    const text = container.textContent ?? ''
    expect(text).toContain('2026/12/11')
    expect(text).toContain('下线')
    expect(queryByText('查看')).toBeNull()
    const copy = queryByText('复制到我的库') as HTMLButtonElement | null
    expect(copy).not.toBeNull()
    copy?.click()
    expect(onCopy).toHaveBeenCalledWith(row)
  })
})