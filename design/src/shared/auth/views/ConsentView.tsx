/**
 * The authorization screen: the one no page leads to. The name comes first, the way both forms put
 * theirs — it is what the person has to answer, and everything under it is the explanation of what
 * answering costs.
 */

import type { ConsentEntry } from '../authModel'

export interface ConsentViewProps {
  active: boolean
  entry: ConsentEntry
  name: string
  onName: (value: string) => void
  onAgree: () => void
  onRefuse: () => void
}

export default function ConsentView({ active, entry, name, onName, onAgree, onRefuse }: ConsentViewProps) {
  return (
    <div className="dialog-view" id="consentView" hidden={!active}>
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
