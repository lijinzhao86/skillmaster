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
})
