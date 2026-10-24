/**
 * The strip along the bottom. **Not part of the design** — the demo's own switch, the same strip the
 * console demo carries.
 *
 * The homepage has two states, and this is the only way to reach the second one without typing an
 * address: signed out (the default) and signed in (`?signed-in`). Both are this same file, so each
 * option is a link to it with or without the parameter.
 */

export default function DemoBar({ signedIn }: { signedIn: boolean }) {
  return (
    <div className="demo-bar" id="demoBar">
      <span className="demo-tag">演示</span>
      <span className="demo-opts">
        <a href="?" className={signedIn ? undefined : 'is-on'}>未登录</a>
        <a href="?signed-in" className={signedIn ? 'is-on' : undefined}>已登录</a>
      </span>
    </div>
  )
}
