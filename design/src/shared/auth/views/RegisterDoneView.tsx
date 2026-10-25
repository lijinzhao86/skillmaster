/** The one moment it is news: the handle cannot be changed, and it is the first segment of every
 *  address this account will publish. */

export interface RegisterDoneViewProps {
  active: boolean
  username: string
  onStart: () => void
}

export default function RegisterDoneView({ active, username, onStart }: RegisterDoneViewProps) {
  return (
    <div className="dialog-view" id="registerDoneView" hidden={!active}>
      <p>欢迎，<span id="doneUsername">{username}</span>。账号已经建好，也已经登录。</p>
      <dl className="facts">
        <dt>用户名</dt><dd id="doneUsernameFact">{username}</dd>
        <dt>命名空间</dt><dd id="doneNamespace">{username}</dd>
      </dl>
      <p className="hint">用户名不可更改，它会成为你的每个 skill 地址的第一段，例如 <code id="doneExample">{username}/我的技能</code>。</p>
      {/* **What happens next is a place, and it has a page.** This line used to say the browsing and
          publishing interface had not been built; neither is true. The console's own empty state is
          where "how do I get the first skill in" is written, so this points at it rather than saying
          it twice. */}
      <p className="hint">你的 skill 都在控制台的「我的 skill」里——第一个怎么交上来，那一页会说。</p>
      <button type="button" className="btn btn-block dialog-submit" id="doneStartBtn" onClick={onStart}>
        开始使用
      </button>
    </div>
  )
}
