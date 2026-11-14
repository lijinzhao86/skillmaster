/**
 * The authorization screen: the one no page leads to. The name comes first, the way both forms put
 * theirs — it is what the person has to answer, and everything under it is the explanation of what
 * answering costs.
 */

import type { ConsentEntry } from '../authModel'

export interface ConsentViewProps {
  active: boolean
  entry: ConsentEntry
  /** The account the browser is signed in as — the identity the credential will belong to. */
  account: string
  name: string
  onName: (value: string) => void
  onSwitch: () => void
  onAgree: () => void
  onRefuse: () => void
}

export default function ConsentView({
  active,
  entry,
  account,
  name,
  onName,
  onSwitch,
  onAgree,
  onRefuse,
}: ConsentViewProps) {
  return (
    <div className="dialog-view" id="consentView" hidden={!active}>
      {/* The link is a one-time invitation, and the person may be one tap away from forwarding it.
          Said before the identity card because it is about the address they followed, not about who
          is signed in — and forwarding it is the one mistake this screen can still prevent. */}
      <p className="hint" id="consentLinkOnly">这条链接只能批准一次登录，不要转发给别人。</p>

      {/* Who is about to grant this, first. The page used to show only the client and the scopes, so
          a browser still signed in as the previous account silently issued the credential to it.
          The name is the largest thing in this card on purpose: the permissions below are only worth
          reading once the account above is right. */}
      {account !== '' ? (
        <div className="consent-account">
          <span className="consent-account-mark" aria-hidden="true">
            {account.charAt(0)}
          </span>
          <div className="consent-account-who">
            <strong id="consentAccount">{account}</strong>
            <span className="consent-account-hint">授权将发给这个账号。</span>
            <button type="button" className="link" id="consentSwitch" onClick={onSwitch}>
              不是我，换一个账号
            </button>
          </div>
        </div>
      ) : null}

      <div className="field">
        <label htmlFor="consentName">名称</label>
        <input
          id="consentName"
          name="name"
          type="text"
          maxLength={60}
          autoComplete="off"
          placeholder="我的 MacBook"
          aria-describedby="consentNameHint"
          value={name}
          onChange={(event) => onName(event.target.value)}
        />
        <p className="hint" id="consentNameHint">这台机器的名称。它只出现在你的「登录设备」里。</p>
        {/* **The one fact on this card the CLI cannot make up.** The name above is what the CLI
            reports; this line is where the request came from and how long ago, as the server saw it.
            It is what a person checks against the machine they just logged into — and what a
            forwarded link gives away. Absent when the server could not see the socket. */}
        {entry.from !== '' ? (
          <p className="hint" id="consentOrigin">
            {entry.ageMinutes === null
              ? `请求来自 ${entry.from}。`
              : entry.ageMinutes < 1
                ? `请求来自 ${entry.from}，刚刚发起。`
                : `请求来自 ${entry.from}，${entry.ageMinutes} 分钟前发起。`}
          </p>
        ) : null}
      </div>

      <p className="consent-who">
        <strong id="consentWho">{entry.who}</strong>
        {/* An unrecognised client is shown as itself with the warning: inventing a friendly name for
            a client nobody has heard of would be the lie the warning exists to prevent. */}
        <span className="hint" id="consentUnknownClient" hidden={entry.knownClient}>
          （这个客户端不在已知名单里，请确认它确实是你安装的）
        </span>
        {' 请求以下权限：'}
      </p>

      <ul className="consent-scopes" id="consentScopes">
        {entry.scopes.map((scope, index) => (
          <li key={index}>
            {scope.kind === 'described' ? (
              scope.text
            ) : (
              <>
                <code>{scope.raw}</code>
                {/* Raw, and said to be undescribed: a blank would read as harmless, which is the
                    one thing an unknown scope must not read as. */}
                <span className="hint">（我们不知道这一项的含义，请谨慎同意）</span>
              </>
            )}
          </li>
        ))}
      </ul>

      <p className="hint" id="consentDisclosure">同意后不再询问。7 天或闲置 24 小时后失效。</p>
      <p className="hint" id="consentExit">如需提前结束，请在控制台的「登录设备」中退出。</p>

      <button
        type="button"
        className="btn btn-block dialog-submit"
        id="consentAgree"
        disabled={name.trim() === ''}
        onClick={onAgree}
      >
        同意并继续
      </button>
      <p className="dialog-foot">
        <button type="button" className="link" id="consentRefuse" onClick={onRefuse}>
          拒绝
        </button>
      </p>
    </div>
  )
}
