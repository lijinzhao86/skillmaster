import { useRef } from 'react'
import { dialogs } from '../components/dialogs/store'
import type { DemoPageProps } from './types'

/**
 * 账号: who you are, and the name every skill of yours starts with.
 *
 * The three blocks are the three answers to "is there a session, and could the page read it": ready
 * is the account, `anonymous` is nobody signed in, and `unknown` is the read itself failing. The
 * third is not the second — saying 「还没有登录」 here would be a claim this page cannot make.
 *
 * **v56 adds the way out.** Deleting the account is irreversible, so it lives in a danger zone at
 * the bottom with one row, the same shape the skill pages use. The sheet asks for the handle and
 * the password; success lands on the login page with 「账号已注销。」 rather than leaving the shell
 * holding a session that no longer exists.
 */
export default function AccountPage({ active, state }: DemoPageProps) {
  const deleteButton = useRef<HTMLButtonElement>(null)

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

      <section className="settings-block is-danger" data-when="ready" hidden={state !== 'ready'}>
        <h3>危险操作</h3>
        <div className="danger-box">
          <div className="danger-row">
            <div className="danger-what">
              <p className="danger-title">注销账号</p>
              <p className="hint">
                注销后立即生效，不能撤销：账号立即不能登录，你个人空间里的 skill 立即不可用；30 天后删除这些内容与你的个人数据，访问日志按法律要求保留 6 个月。
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
                  dialogs.openDanger({
                    title: '注销账号',
                    body: '此操作立即生效，不能撤销。输入用户名与密码以确认是你本人。',
                    fields: [
                      { key: 'username', label: '用户名', type: 'text', autoComplete: 'username' },
                      { key: 'password', label: '密码', type: 'password', autoComplete: 'current-password' },
                    ],
                    confirmLabel: '注销账号',
                    pendingLabel: '正在注销…',
                    failMessage: '没有完成：用户名或密码不对。',
                    onConfirm: () => {
                      window.location.href = '?page=login&deleted=1'
                    },
                    returnFocus: deleteButton.current,
                  })
                }}
              >
                注销账号
              </button>
            </div>
          </div>
        </div>
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
