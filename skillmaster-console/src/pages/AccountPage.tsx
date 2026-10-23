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
 */
export default function AccountPage() {
  const { account, problem: sessionProblem } = useSession()

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
