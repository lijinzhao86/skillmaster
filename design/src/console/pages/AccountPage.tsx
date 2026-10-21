import type { DemoPageProps } from './types'

/**
 * 账号: who you are, and the name every skill of yours starts with.
 *
 * The three blocks are the three answers to "is there a session, and could the page read it": ready
 * is the account, `anonymous` is nobody signed in, and `unknown` is the read itself failing. The
 * third is not the second — saying 「还没有登录」 here would be a claim this page cannot make.
 */
export default function AccountPage({ active, state }: DemoPageProps) {
  return (
    <section className="page" id="page-account" hidden={!active}>
      <nav className="crumbs" aria-label="面包屑">
        <span className="crumbs-here" aria-current="page">设置 / 账号</span>
      </nav>

      <header className="page-head">
        <h1>账号</h1>
      </header>

      <section className="block" data-when="ready" hidden={state !== 'ready'}>
        <h2>账号</h2>
        <dl className="facts">
          <dt>用户名</dt>
          <dd>dev</dd>
          <dt>命名空间</dt>
          <dd>dev</dd>
        </dl>
        <p className="hint account-note">命名空间是每个 skill 地址的第一段，也是本机 CLI 提交时的落点。</p>
      </section>

      {/* The session read failed for a reason that is not "nobody is signed in". Saying 「还没有登录」
          here would be a claim this page cannot make. */}
      <section className="block" data-when="unknown" hidden={state !== 'unknown'}>
        <h2>无法确认登录状态</h2>
        <p className="hint">读取登录状态时出错了，这是页面自己的问题，不是你的账号。</p>
        <p><a href="?page=account">重试</a>　·　<a href="?page=login">去登录</a></p>
      </section>

      <section className="block" data-when="anonymous" hidden={state !== 'anonymous'}>
        <h2>还没有登录</h2>
        <p><a href="?page=login">去登录</a>　·　<a href="?page=register">注册</a></p>
      </section>
    </section>
  )
}
