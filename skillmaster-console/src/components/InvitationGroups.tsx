import type { SkillInvitation } from '../api/types'
import { avatarInitial } from '../avatar'
import { rungLabel } from '../rungs'

/**
 * The two invitation groups on the collaborators pane (v45): still waiting, and past 30 days.
 *
 * An expired invitation is history, not work: it gets a chip and no button. Re-sharing replaces
 * the row, which is the only action the product offers for it.
 */
export default function InvitationGroups({
  invitations,
  onWithdraw,
}: {
  invitations: SkillInvitation[]
  onWithdraw: (invitation: SkillInvitation) => void
}) {
  const pending = invitations.filter((invitation) => invitation.state !== 'expired')
  const expired = invitations.filter((invitation) => invitation.state === 'expired')

  return (
    <>
      {pending.length > 0 ? (
        <section className="settings-block">
          <h3>
            待接受的共享 <span className="count">{pending.length}</span>
          </h3>
          <p className="hint">对方接受之前，这里的人还不在协作者名单里。</p>
          <ul className="grants">
            {pending.map((invitation) => (
              <li key={invitation.handle}>
                <span className="avatar" aria-hidden="true">
                  {avatarInitial(invitation.handle)}
                </span>
                <div className="grant-what">
                  <span className="grant-who">{invitation.handle}</span>
                  <span className="grant-ask">等待对方接受</span>
                </div>
                <span className="member-type">个人</span>
                <span className="role-tag">{rungLabel(invitation.role)}</span>
                <button
                  type="button"
                  className="link-button danger withdraw"
                  onClick={() => onWithdraw(invitation)}
                >
                  撤销
                </button>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {expired.length > 0 ? (
        <section className="settings-block">
          <h3>
            已过期的共享 <span className="count">{expired.length}</span>
          </h3>
          <p className="hint">这些邀请已过 30 天，对方不能再接受；重新共享会替换这一行。</p>
          <ul className="grants">
            {expired.map((invitation) => (
              <li key={invitation.handle}>
                <span className="avatar" aria-hidden="true">
                  {avatarInitial(invitation.handle)}
                </span>
                <div className="grant-what">
                  <span className="grant-who">{invitation.handle}</span>
                  <span className="grant-ask">已过 30 天</span>
                </div>
                <span className="member-type">个人</span>
                <span className="role-tag">{rungLabel(invitation.role)}</span>
                <span className="chip is-expired">已过期</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </>
  )
}
