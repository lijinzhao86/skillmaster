import { useRef, useState } from 'react'
import { deleteAccount } from '../api/account'
import { failureMessage } from '../api/errors'
import ConfirmDialog from '../components/ConfirmDialog'
import { useSession } from '../store'

/**
 * The account, and the little there is to say about it.
 *
 * It was the home page until the skills pages arrived, and what it holds is what it held then:
 * who you are, and the namespace your skills live under. Signing out moved to the navigation,
 * where it is reachable from every page rather than only from here.
 *
 * **Deliberately not here: changing the password, and the list of authorized machines.** The reset
 * flow is a signed-out one (it sends a code and ends every session), so there is nothing to change
 * in place — and what a CLI holds is a key now, which has a page of its own under 权限 rather than a
 * link from here (ADR 0038). The older authorizations an OAuth login leaves behind still cannot be
 * listed or revoked individually, and that absence is deliberate: a link to them would be a door
 * that opens onto nothing.
 *
 * **v56 adds the way out.** Deleting the account is irreversible, so it lives in a danger zone at
 * the bottom. The sheet asks for the handle and the password; success lands on the login page with
 * 「账号已注销。」 rather than leaving the shell holding a session that no longer exists.
 */
export default function AccountPage() {
  const { account, problem: sessionProblem } = useSession()
  const deleteButton = useRef<HTMLButtonElement>(null)
  const [asking, setAsking] = useState(false)
  const [pending, setPending] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  async function confirmDelete(values?: Record<string, string>): Promise<void> {
    setPending(true)
    setProblem(null)
    const result = await deleteAccount({
      username: values?.username ?? '',
      password: values?.password ?? '',
    })
    setPending(false)
    if (result.ok) {
      // A full load: every session is already gone, including this one.
      window.location.assign('/login?deleted=1')
      return
    }
    // The sheet asks for a username and a password, so the login screen's sentence about a phone
    // number would point at a box that is not here. The design's own copy for this sheet.
    setProblem(result.code === 'invalid_credentials'
      ? '没有完成：用户名或密码不对。'
      : failureMessage(result))
  }

  return (
    <div className="card">
      <h2>账号</h2>

      {account ? (
        <>
          <dl className="facts">
            <dt>用户名</dt>
            <dd>{account.username}</dd>
            <dt>命名空间</dt>
            <dd>{account.namespace}</dd>
          </dl>
          <p className="hint">命名空间是每个 skill 地址的第一段，也是本机 CLI 提交时的落点。</p>

          <section className="settings-block is-danger">
            <h3>危险操作</h3>
            <div className="danger-box">
              <div className="danger-row">
                <div className="danger-what">
                  <p className="danger-title">注销账号</p>
                  <p className="hint">
                    注销后立即生效，不能撤销：账号立即不能登录，你个人空间里的 skill 立即不可用；30
                    天后删除这些内容与你的个人数据，访问日志按法律要求保留 6 个月。
                  </p>
                  <p className="hint">
                    你在别人 skill 里的贡献会保留，作者显示为「已注销账号」；分享给别人的访问会失效，并通知对方。
                  </p>
                  <p className="hint">30 天内不能用同一手机号或用户名重新注册。</p>
                </div>
                <div className="danger-acts">
                  <button
                    type="button"
                    className="button is-danger"
                    id="deleteAccount"
                    ref={deleteButton}
                    onClick={() => {
                      setProblem(null)
                      setAsking(true)
                    }}
                  >
                    注销账号
                  </button>
                </div>
              </div>
            </div>
          </section>

          <ConfirmDialog
            open={asking}
            title="注销账号"
            body="此操作立即生效，不能撤销。输入用户名与密码以确认是你本人。"
            confirmLabel="注销账号"
            pendingLabel="正在注销…"
            danger
            pending={pending}
            error={problem}
            fields={[
              { key: 'username', label: '用户名', type: 'text', autoComplete: 'username' },
              { key: 'password', label: '密码', type: 'password', autoComplete: 'current-password' },
            ]}
            onConfirm={(_, values) => void confirmDelete(values)}
            onCancel={() => {
              setAsking(false)
              deleteButton.current?.focus()
            }}
          />
        </>
      ) : sessionProblem ? (
        <>
          {/* The session read failed for a reason that is not "nobody is signed in". Saying "还没有登录"
              here would be a claim this page cannot make. */}
          <h3>无法确认登录状态</h3>
          <p className="muted">{sessionProblem}</p>
          <p>
            <a href="/login">去登录</a>　·　<a href="/register">注册</a>
          </p>
        </>
      ) : (
        <>
          <h3>还没有登录</h3>
          <p>
            <a href="/login">去登录</a>　·　<a href="/register">注册</a>
          </p>
        </>
      )}
    </div>
  )
}
