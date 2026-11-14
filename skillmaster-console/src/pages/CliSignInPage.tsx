import { useEffect, useState } from 'react'
import { query } from '../address'
import { logout } from '../api/account'
import {
  approveCliDeviceSignIn,
  getCliDeviceSignIn,
  refuseCliDeviceSignIn,
} from '../api/cliSignIns'
import { failureMessage } from '../api/errors'
import type { CliDeviceSignInView } from '../api/types'
import { loginPath } from '../login'
import { useSession } from '../store'

/**
 * 授权请求 — the screen the CLI's link opens (v68).
 *
 * **A copy of the design's consent screen, not a rewrite of it.**
 * `design/src/shared/auth/` carries the screen part by part — the markup and class names in
 * `views/ConsentView.tsx`, the sentences in `authModel.ts`, the numbers in `auth.css` — and what
 * stands here is that screen with the demo's scaffolding left behind.
 *
 * **The page does not poll.** The machine that asked does, at its own interval. This page reads the
 * question once, answers it once, and is done — a tab that polled would stay busy to learn something
 * it has no further use for.
 *
 * **It is a login, not a key.** What the person is agreeing to is that this machine gets to act as
 * them until the login runs out — the same two rulers a browser session has (24 hours idle, 7 days
 * absolute), and the same screen it shows up on.
 *
 * **The ticket is the whole address.** It is what the question is addressed by and worth nothing on
 * its own: the device secret and the PKCE verifier stayed on the machine that asked.
 */
const SCOPE_MEANINGS: Record<string, string> = {
  'skills:read': '读取你可见的全部 skill，包括未公开的',
  'skills:write': '以你的身份提交新版本、管理草稿与共享；从未上线过的 skill 还可以删除',
}

/**
 * The order the design lists them in — read, then write.
 *
 * **The server's order cannot stand in for it.** The ticket carries a scope **set**, so the array
 * arrives in whatever order that set serialised to, and it does not keep the order the CLI asked
 * in: the consent screen rendered 「以你的身份提交新版本…」 above 「读取你可见的全部 skill」 while
 * the design puts the narrower promise first. Which line a person reads first is part of the
 * screen, so the page fixes it rather than inheriting it; a scope this list does not know keeps
 * its place from the server, after the known ones (2026-11-14, found by putting the product's
 * screenshot next to the design's).
 */
const SCOPE_ORDER: string[] = ['skills:read', 'skills:write']

function inDesignOrder(granted: string[]): string[] {
  const known = SCOPE_ORDER.filter((scope) => granted.includes(scope))
  const unknown = granted.filter((scope) => !SCOPE_ORDER.includes(scope))
  return [...known, ...unknown]
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

/** The sentence the design uses when the answer can no longer be given. */
const GONE = '这条链接已经用过或过期了，不能再批准。请回到那台设备，重新运行 skmr login。'

export default function CliSignInPage() {
  const params = query()
  const ticket = params.get('ticket')

  const { loaded, account } = useSession()

  /**
   * A page with no session is a page nobody can answer, and the sign-in form knows how to come back.
   * `return_to` keeps the address, query included — losing the ticket across the sign-in would land
   * the person on a screen that cannot work.
   */
  useEffect(() => {
    if (loaded && account === null) {
      window.location.replace(loginPath())
    }
  }, [loaded, account])

  const [view, setView] = useState<CliDeviceSignInView | null>(null)
  const [missing, setMissing] = useState(false)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  /** What this page did, once it has answered — the server's answer and the page's are the same fact. */
  const [answered, setAnswered] = useState<'approved' | 'refused' | null>(null)

  useEffect(() => {
    if (ticket === null || ticket === '') {
      return
    }
    let cancelled = false
    void getCliDeviceSignIn(ticket).then((result) => {
      if (cancelled) {
        return
      }
      if (!result.ok) {
        if (result.status === 404) {
          setMissing(true)
          return
        }
        setFailure(failureMessage(result))
        return
      }
      setView(result.data)
      setName(result.data.name)
    })
    return () => {
      cancelled = true
    }
  }, [ticket])

  const canAgree = name.trim() !== '' && !busy

  async function agree(): Promise<void> {
    if (!canAgree || ticket === null) {
      return
    }
    setBusy(true)
    setFailure(null)
    const result = await approveCliDeviceSignIn(ticket, name.trim())
    setBusy(false)
    if (!result.ok) {
      if (result.code === 'unauthenticated') {
        window.location.replace(loginPath())
        return
      }
      if (result.status === 409) {
        setMissing(true)
        return
      }
      setFailure(failureMessage(result))
      return
    }
    setAnswered('approved')
  }

  async function refuse(): Promise<void> {
    if (busy || ticket === null) {
      return
    }
    setBusy(true)
    setFailure(null)
    const result = await refuseCliDeviceSignIn(ticket)
    setBusy(false)
    if (!result.ok) {
      if (result.code === 'unauthenticated') {
        window.location.replace(loginPath())
        return
      }
      if (result.status === 409) {
        setMissing(true)
        return
      }
      setFailure(failureMessage(result))
      return
    }
    setAnswered('refused')
  }

  /**
   * The account on screen is the one the credential will belong to, and this is the way out when it
   * is the wrong one. It ends the browser session rather than offering a chooser: the server issues
   * to whoever the session says, so the only honest way to answer as somebody else is to sign in as
   * somebody else.
   */
  async function switchAccount(): Promise<void> {
    if (busy) {
      return
    }
    setBusy(true)
    setFailure(null)
    const result = await logout()
    if (!result.ok) {
      setBusy(false)
      setFailure(failureMessage(result))
      return
    }
    window.location.replace(loginPath())
  }

  /* **One silent attempt, and nothing on the card mentions it.** The design has this: the tab was
     opened from a chat, and a window opened by a script may close itself. A browser that refuses is
     the common case and costs nothing — the sentence above already tells the person to close it. */
  useEffect(() => {
    if (answered === null) {
      return
    }
    const timer = window.setTimeout(() => window.close(), 3000)
    return () => window.clearTimeout(timer)
  }, [answered])

  const clientName =
    view !== null && Object.prototype.hasOwnProperty.call(CLIENT_NAMES, view.client)
      ? CLIENT_NAMES[view.client]!
      : null

  const scopes = inDesignOrder(view?.scopes ?? [])
  /* **The title is the design's, and it changes when the answer lands.** The standalone card is the
     whole page, so its title is the only thing telling a person their click was received; leaving it
     at 「授权请求」 reads as "nothing happened". Refused gets its own title for the same reason. */
  const approved = answered === 'approved' || (answered === null && view?.status === 'approved')
  const refused = answered === 'refused'
  const gone =
    missing ||
    view?.status === 'expired' ||
    view?.status === 'used' ||
    view?.status === 'denied'
  const title = approved ? '授权成功' : refused ? '授权未完成' : '授权请求'

  return (
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
        <h2 className="dialog-title" id="dialogTitle">
          {title}
        </h2>

        {ticket === null || ticket === '' ? (
          <div className="dialog-view" id="consentIncompleteView">
            <p>
              这个链接不完整，无法完成授权。请回到那台设备，重新运行 <code>skmr login</code>。
            </p>
          </div>
        ) : failure !== null && view === null ? (
          /* A read that failed is not a link that is over: the question may be perfectly good, so the
             card says what happened and what to do, and promises nothing about the ticket. */
          <div className="dialog-view" id="consentErrorView">
            <p className="form-error" role="alert">
              {failure}
            </p>
            <p>刷新这个页面即可。</p>
          </div>
        ) : gone ? (
          <div className="dialog-view" id="consentExpiredView">
            <p>{GONE}</p>
          </div>
        ) : approved ? (
          <div className="dialog-view" id="consentDoneView">
            <p id="consentDoneNote">请关闭此页面，那台设备会继续。</p>
          </div>
        ) : refused ? (
          <div className="dialog-view" id="consentDoneView">
            <p id="consentDoneNote">你拒绝了这次授权请求。</p>
          </div>
        ) : view === null ? null : (
          <div className="dialog-view" id="consentView">
            {/* The link is a one-time invitation, and the person may be one tap away from forwarding
                it. Said before the identity card because it is about the address they followed. */}
            <p className="hint" id="consentLinkOnly">
              这条链接只能批准一次登录，不要转发给别人。
            </p>

            {account !== null && account.username !== '' ? (
              <div className="consent-account">
                <span className="consent-account-mark" aria-hidden="true">
                  {account.username.charAt(0)}
                </span>
                <div className="consent-account-who">
                  <strong id="consentAccount">{account.username}</strong>
                  <span className="consent-account-hint">授权将发给这个账号。</span>
                  <button
                    type="button"
                    className="link"
                    id="consentSwitch"
                    disabled={busy}
                    onClick={() => {
                      void switchAccount()
                    }}
                  >
                    不是我，换一个账号
                  </button>
                </div>
              </div>
            ) : null}

            {failure !== null ? (
              <p className="form-error" role="alert">
                {failure}
              </p>
            ) : null}

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
              {view.from !== null && view.from !== '' ? (
                <p className="hint" id="consentOrigin">
                  {originLine(view)}
                </p>
              ) : null}
            </div>

            <p className="consent-who">
              <strong id="consentWho">{clientName ?? view.client}</strong>
              <span className="hint" id="consentUnknownClient" hidden={clientName !== null}>
                （这个客户端不在已知名单里，请确认它确实是你安装的）
              </span>
              {' 请求以下权限：'}
            </p>

            <ul className="consent-scopes" id="consentScopes">
              {scopes.map((scope) => (
                <li key={scope}>
                  {Object.prototype.hasOwnProperty.call(SCOPE_MEANINGS, scope) ? (
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

            <p className="hint" id="consentDisclosure">同意后不再询问。7 天或闲置 24 小时后失效。</p>
            <p className="hint" id="consentExit">如需提前结束，请在控制台的「登录设备」中退出。</p>

            <button
              type="button"
              className="btn btn-block dialog-submit"
              id="consentAgree"
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
                id="consentRefuse"
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

/**
 * Where the request came from and how long ago, as the server saw it.
 *
 * The name above is what the CLI reports; this line is the one fact it cannot make up. **The age is
 * the server's count, not this browser's clock** — a browser running hours ahead would otherwise
 * turn a just-asked question into 「120 分钟前发起」 on a link that lives ten. Minutes because the
 * question lives ten of them; anything younger than a minute reads "刚刚".
 */
function originLine(view: CliDeviceSignInView): string {
  // **No count means no age.** A server from before the field cannot say how old the question is,
  // and inventing 「刚刚发起」 would put a wrong time on the line whose whole job is to be right.
  if (view.age_minutes === null || view.age_minutes === undefined) {
    return `请求来自 ${view.from ?? ''}。`
  }
  const when = view.age_minutes < 1 ? '刚刚发起' : `${view.age_minutes} 分钟前发起`
  return `请求来自 ${view.from ?? ''}，${when}。`
}
