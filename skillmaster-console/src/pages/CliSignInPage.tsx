import { useEffect, useState } from 'react'
import { go, query } from '../address'
import { refuseCliSignIn, startCliSignIn } from '../api/cliSignIns'
import { codeMessage } from '../api/errors'
import { loginPath } from '../login'
import { useSession } from '../store'

/**
 * 授权请求 — the screen `skmr login` hands the browser to (v4).
 *
 * **A copy of the design's consent screen, not a rewrite of it.**
 * `design/src/shared/auth/` carries the screen part by part — the markup and class names in
 * `views/ConsentView.tsx`, the sentences in `authModel.ts`, the numbers in `auth.css` — and what
 * stands here is that screen with the demo's scaffolding left behind. Those styles are scoped under `.dialog` and stay scoped there
 * (`styles.css`), which is what keeps them from reaching another page's `.field` and `.hint`.
 *
 * **It is a login, not a key.** What the person is agreeing to is that this machine gets to act as
 * them until the login runs out — the same two rulers a browser session has (24 hours idle, 7 days
 * absolute), and the same screen it shows up on.
 *
 * **It is a page, not a dialog.** The CLI opened this address; there is no site under it, so the card
 * is the whole page (the design's `.overlay.is-standalone`), and no control closes it.
 *
 * **Nothing here is a parameter this page invents.** `client_id`, `state`, `scope` and
 * `code_challenge` all came from the address the CLI opened, and the server checks every one of them
 * again. What the person supplies is the name.
 */
const SCOPE_MEANINGS: Record<string, string> = {
  'skills:read': '读取你可见的全部 skill，包括未公开的',
  'skills:write': '以你的身份提交新版本、管理草稿与共享',
}

/**
 * Who is asking.
 *
 * The friendly names live here, and that has a cost: a name typed into this file can rot. So an id
 * this map does not know is shown **as itself**, with a warning.
 */
const CLIENT_NAMES: Record<string, string> = {
  'skillmaster-cli': 'SkillMaster CLI',
}

export default function CliSignInPage() {
  const params = query()
  const clientId = params.get('client_id')
  const state = params.get('state')
  const codeChallenge = params.get('code_challenge')

  /**
   * Every scope the address asked for, one flat list.
   *
   * The redirect puts them in one space-separated parameter, and a repeated parameter is joined
   * rather than dropped.
   */
  const scopes = params
    .getAll('scope')
    .flatMap((value) => value.split(/\s+/))
    .filter((value) => value !== '')
  const scopeParameter = scopes.join(' ')

  const clientName = clientId === null ? null : (CLIENT_NAMES[clientId] ?? null)

  /**
   * Whether this address cannot produce a login at all.
   *
   * All three are required of the address, and the design's sentence below names all three. The
   * challenge is what the code is created with (`cli_sign_in_code.code_challenge` is NOT NULL), so it
   * can be the only thing missing.
   */
  const incomplete =
    clientId === null ||
    clientId === '' ||
    state === null ||
    codeChallenge === null ||
    codeChallenge === ''

  const { loaded, account } = useSession()

  /**
   * A page with no session is a page nobody can answer, and the sign-in form knows how to come back.
   *
   * `return_to` is the parameter that form already honours, and it checks the destination's origin
   * before it uses it (`LoginPage.returnTo`) — which is why this is the mechanism rather than a new
   * one. The address is the whole thing, query included: those parameters are the login, and losing
   * them across the sign-in would land the person on a screen that cannot work.
   *
   * `replace` rather than `assign`, because a failed attempt must not become a page the Back button
   * keeps returning to.
   */
  useEffect(() => {
    if (loaded && account === null) {
      window.location.replace(loginPath())
    }
  }, [loaded, account])

  /**
   * The name this machine will be called on 「登录设备」. Required, and the server checks that too.
   *
   * **It starts from the address, not from an empty box** (2026-10-16). The CLI is the one party that
   * knows which machine it is running on, so it reads that name off the system and puts it in the
   * address (`name=`); this screen has no way to know it. **Filled is not fixed.** It stays an input
   * and it stays required: the suggestion is right most of the time and useless exactly where naming
   * matters most — a VPS called `localhost`, an EC2 box called `ip-10-0-1-23…`.
   */
  const [name, setName] = useState(params.get('name') ?? '')

  /** The write that is out, if any. Both buttons wait on it: a second press would be a second login. */
  const [busy, setBusy] = useState(false)

  /**
   * What to say when the server did not take it.
   *
   * **A state the design does not draw**, and the reason it is the server's own sentence rather than
   * one written here: every sentence on this screen has a source.
   */
  const [failure, setFailure] = useState<string | null>(null)

  /** Whether the button can be pressed: there is a name, and no request is already out. */
  const canAgree = name.trim() !== '' && !busy

  async function answer(pending: ReturnType<typeof startCliSignIn>): Promise<void> {
    setBusy(true)
    setFailure(null)
    const result = await pending
    setBusy(false)
    if (!result.ok) {
      if (result.code === 'unauthenticated') {
        // The session ended between loading this page and pressing the button. There is no anonymous
        // version of this screen, so the person is sent to sign in and brought back.
        window.location.replace(loginPath())
        return
      }
      setFailure(codeMessage(result.code, result.message))
      return
    }
    go(result.data.redirect_to)
  }

  async function agree(): Promise<void> {
    if (!canAgree) {
      return
    }
    await answer(
      startCliSignIn({
        name: name.trim(),
        client_id: clientId as string,
        scope: scopeParameter,
        state: state as string,
        code_challenge: codeChallenge as string,
      }),
    )
  }

  async function refuse(): Promise<void> {
    if (busy) {
      return
    }
    await answer(refuseCliSignIn({ client_id: clientId as string, state: state as string }))
  }

  return (
    // The design's standalone ground: the screen is the card, and there is no site under it to put
    // out of focus.
    <div className="overlay is-standalone">
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogTitle"
        tabIndex={-1}
      >
        <p className="dialog-brand">
          <svg className="mark" viewBox="0 0 24 24" aria-hidden="true">
            <rect x="3" y="4" width="18" height="4.5" rx="2.25" fill="currentColor" />
            <rect
              x="3"
              y="10.5"
              width="13"
              height="4.5"
              rx="2.25"
              fill="currentColor"
              opacity="0.6"
            />
            <rect x="3" y="17" width="8" height="4.5" rx="2.25" fill="currentColor" opacity="0.32" />
          </svg>
          SkillMaster
        </p>
        {/* Same title in both states below, because it is the same request — one that cannot be
            finished is still this screen. */}
        <h2 className="dialog-title" id="dialogTitle">
          授权请求
        </h2>

        {/* A person arrives here by editing the address or following something old, and saying so
            beats a form that silently does nothing. The design's own sentence, unchanged, on one
            line so that what is rendered is that string. */}
        {incomplete ? (
          <div className="dialog-view">
            <p>
              这个链接不完整，缺少 <code>client_id</code>、<code>state</code> 或{' '}
              <code>code_challenge</code>，无法完成授权。请回到终端重新运行{' '}
              <code>skmr login</code>。
            </p>
          </div>
        ) : (
          <div className="dialog-view">
            {failure !== null ? (
              <p className="form-error" role="alert">
                {failure}
              </p>
            ) : null}

            {/* The one input, first, the way both forms the design draws put theirs: it is what the
                person has to answer, and everything under it is the explanation of what answering
                costs. */}
            <div className="field">
              <label htmlFor="consentName">名称</label>
              <input
                id="consentName"
                name="name"
                type="text"
                value={name}
                maxLength={60}
                autoComplete="off"
                placeholder="我的 MacBook"
                aria-describedby="consentNameHint"
                onChange={(event) => setName(event.target.value)}
              />
              <p className="hint" id="consentNameHint">
                这台机器的名称。它只出现在你的「登录设备」里。
              </p>
            </div>

            <p className="consent-who">
              <strong>{clientName ?? clientId}</strong>
              {/* As a block rather than run on inside the sentence, which is the fragment's own
                  rule: inline, the sentence's last line is left holding a lone character. */}
              {clientName === null ? (
                <span className="hint">（这个客户端不在已知名单里，请确认它确实是你安装的）</span>
              ) : null}
              请求以下权限：
            </p>

            <ul className="consent-scopes">
              {scopes.map((scope) => (
                <li key={scope}>
                  {SCOPE_MEANINGS[scope] !== undefined ? (
                    SCOPE_MEANINGS[scope]
                  ) : (
                    <>
                      <code>{scope}</code>
                      <span className="hint">（我们不知道这一项的含义，请谨慎同意）</span>
                    </>
                  )}
                </li>
              ))}
            </ul>

            {/* Two sentences, and the first one used to be false in two ways. It said 「一份长期凭据」
                and 「要结束它，请重置密码」: the credential is not long-lived (7 days, and 24 hours of
                not being used), and since v4 it *is* revocable on its own. */}
            <p className="hint">同意后不再询问。7 天或闲置 24 小时后失效。</p>
            <p className="hint">如需提前结束，请在控制台的「登录设备」中退出。</p>

            <button
              type="button"
              className="btn btn-block dialog-submit"
              disabled={!canAgree}
              onClick={() => {
                void agree()
              }}
            >
              同意并继续
            </button>
            <p className="dialog-foot">
              <button
                type="button"
                className="link"
                disabled={busy}
                onClick={() => {
                  void refuse()
                }}
              >
                拒绝
              </button>
            </p>
          </div>
        )}
      </div>
    </div>
  )
}
