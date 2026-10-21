import { useEffect, useRef, useState } from 'react'
import { go } from '../address'
import { revokeCliSignIn } from '../api/cliSignIns'
import { codeMessage } from '../api/errors'
import { listSessions, revokeOtherSessions, revokeSession } from '../api/sessions'
import { loginPath } from '../login'
import { formatRelative, formatTime } from '../time'
import type { ApiResult, LoginSession } from '../api/types'
import FormBanner from '../components/FormBanner'

/**
 * 登录设备 — every login this account has, and the way to end one (ADR 0043, v4 Q6).
 *
 * **It exists because "I think I left that machine signed in" had no answer.** Shortening a session's
 * life is only half of that: a person who did the right thing on somebody else's computer needs to be
 * able to end *that* login, and until this screen the only ways were clearing every cookie on the
 * browser or resetting the password — neither of which is the thing they want to do. The design of
 * this screen is `design/README.md` §登录设备, and it is the authority for the layout, the wording and
 * the states below.
 *
 * **Since v4 it holds two kinds of login, and one list.** A browser session and a CLI login — a
 * machine that signed in through the login-request screen — have the same two rulers (24 hours idle,
 * 7 days absolute) and the same action to end them, so they are the same list, the same row shape and
 * the same count (v4 Q6, `technical-design.md`). They are told apart by the row's own second segment
 * (「Chrome · macOS」 against 「CLI · 我的 MacBook」) and by nothing else: the design's rule for this
 * screen is that an active row says nothing about itself, and a mark on every row is a mark that
 * carries no information.
 *
 * **`kind` is the one field the screen branches on, and it is never drawn.** It says which plane ends
 * the row — a session is deleted, an authorization is marked — so it decides the address the 退出
 * button posts to. A CLI login is also never the current device: the person reading this page is on a
 * browser, and that row is about a machine somewhere else.
 *
 * **每行的动作与密钥那一页共用同一套「先问后果」**: the consequence takes the row's own line, the
 * two answers take its action cell, Escape answers 「取消」, and cancelling puts the focus back on the
 * button that was pressed. It is deliberately not a dialog — the design's reason is that a page of
 * hairlines with one filled box in it would be the only filled box on the page.
 *
 * **用词是「退出」而不是「踢掉」** (`design/voice-spec.md`): the metaphor has to be translated by the
 * reader before it means anything, and the literal word for what happens — that login ends — is
 * already available.
 *
 * **One screenful, no pages and no cap.** How many rows there can be is bounded by the two rulers
 * rather than by a number somebody chose: a forgotten machine lives at most 24 hours, a browser
 * signed in repeatedly reuses one row, and a CLI login that has run out of time is no longer sent
 * (`CliSignIn.listFor`). The server sends no total either, so the count below is the length of the
 * list it was drawn from — one answer to "how many" rather than two that can disagree.
 *
 * **Both times on a row were absolute, and that was a deviation from the design rather than a
 * decision** — it was left in place and reported because settling it needed a choice the repository's
 * rules put with the user. It has since been settled: the second time is relative, per
 * `design/voice-spec.md` §六, and the key screen's 「最后使用」 was moved with it. `whenOf` below says
 * which column keeps the date and why.
 *
 * **What changed in the port.** `asking` and `busy` are mirrored into refs, because the keydown
 * listener is registered once (Vue's `onMounted`/`onUnmounted`) and Vue read their latest values
 * through its refs; a React closure would keep the values from the render that registered it. The
 * refs are written synchronously with the state they mirror, so the two can never disagree.
 * `nextTick` became `requestAnimationFrame`, and the list's read moved into the mount effect.
 */
export default function AccountSessionsPage() {
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [sessions, setSessions] = useState<LoginSession[]>([])
  const [banner, setBanner] = useState<string | null>(null)

  /**
   * The question that is open, if any: one device's, or every other device's. One at a time, because
   * two rows asking at once is two consequences on screen and one of them would be about a row the
   * reader has stopped looking at.
   *
   * The device's question holds the whole row rather than its id, because answering it needs to know
   * which plane that login is on — see `endLogin`.
   */
  type Question = { kind: 'device'; session: LoginSession } | { kind: 'others' }

  const [asking, setAskingState] = useState<Question | null>(null)
  const askingRef = useRef<Question | null>(null)
  /**
   * True while a write is in flight, so both answers on the open question disable together — and so do
   * the buttons that would *replace* the question.
   *
   * The second half is not symmetry for its own sake: with those live, pressing another row's
   * 「退出该设备」 while the first request was out opened a question whose two answers were disabled and
   * whose Escape was ignored, so for as long as the request took there was a question on screen that
   * could not be answered or dismissed — and then it closed by itself. Escape is gated the same way,
   * for the same reason (`onKeydown`).
   */
  const [busy, setBusyState] = useState(false)
  const busyRef = useRef(false)

  /** The refs are written with the state they mirror, in the same tick. */
  function setAsking(question: Question | null): void {
    askingRef.current = question
    setAskingState(question)
  }

  function setBusy(value: boolean): void {
    busyRef.current = value
    setBusyState(value)
  }

  /**
   * The devices that could be ended — the count the screen uses twice, for the 「N 台设备」 line and for
   * the confirmation's own sentence, so the number in the question is the number of rows offering one.
   */
  const others = sessions.filter((session) => !session.current).length

  /**
   * 计数是名词短语，不是句子 (design/README.md §登录设备): 「仅当前设备」 and 「N 台设备」, the same shape
   * as the key screen's 「5 个密钥」. The list's own empty line then says only the half the count did
   * not — that devices signed in elsewhere would appear here — rather than repeating it.
   */
  const count = others === 0 ? '仅当前设备' : `${sessions.length} 台设备`

  /**
   * The row's second line, and all of it: 什么设备　·　登录于 …　·　最近活动 …
   *
   * **One line, not three** (2026-10-16): the design stacked the three segments until that day, and
   * four devices then took twelve lines. Only the first segment is a main identifier — the address
   * above it is what says whether the machine is yours — so the two secondary ones share a line. The
   * separator is the full-width 「　·　」, which is not the half-width one a compound label uses
   * (`Chrome · macOS` glues two words into one name; this joins three things, `design/voice-spec.md`
   * §六).
   *
   * **The two times answer two different questions, and are said in two forms.** 「什么时候登进来的」 is
   * a fact about the past that does not change, so it is said as a date. 「它还在用吗」 is about now, so
   * it is said the way a person says it: `formatRelative`, whose authority is `design/voice-spec.md` §六
   * and whose shapes are 刚刚 / 今天 09:41 / 昨天 03:12 / 2026/10/11 22:04 — the last being the
   * fixture's own oldest row.
   *
   * **A session with no device to describe draws no middle segment** rather than a word of our own for
   * it: the server sends an empty description when the client sent no User-Agent, and there is nothing
   * to say. It has to be dropped from this line rather than drawn as an empty span, because here it
   * shares a line with the times — an empty segment would leave its separator behind.
   */
  function whenOf(session: LoginSession): string {
    const what = session.device === '' ? [] : [session.device]
    return [
      ...what,
      `登录于 ${formatTime(session.logged_in_at)}`,
      `最近活动 ${formatRelative(session.last_seen_at)}`,
    ].join('　·　')
  }

  /** Whether this row is the one asking. */
  function askingThis(session: LoginSession): boolean {
    return asking?.kind === 'device' && asking.session.device_id === session.device_id
  }

  /**
   * Whether this row can be ended: not the current device, and it has a handle.
   *
   * The current device is *removed* rather than disabled — the design's rule, taken from the reference
   * product, and it is the right one: that control would end the login of the person reading the page.
   *
   * **The second half is a state the design does not draw**, because this version cannot produce it: a
   * session created before this release carries no device id, so it can be seen and cannot be named. A
   * button that addressed `null` would be a broken control; leaving the row without one is the honest
   * shape, and it is reported rather than hidden. A CLI login always has one — it is the authorization
   * the login is, which the row was addressed by in the first place.
   */
  function canEnd(session: LoginSession): boolean {
    return !session.current && session.device_id !== null
  }

  /**
   * The write that ends one row, on the plane that row belongs to.
   *
   * **Two routes rather than one that guesses.** A browser session is deleted from the session store; a
   * CLI login is an authorization, and ending it marks that row (`POST /web/cli-sign-ins/{id}/revoke`).
   * They answer the same way — 204, and one 404 for "not there, already over, or not yours" — but they
   * end different things, which is why the server has two and why the row carries `kind`.
   *
   * Called only from a row that `canEnd`, which is where the id is known to be there.
   */
  function endLogin(session: LoginSession): Promise<ApiResult<void>> {
    const id = session.device_id as string
    return session.kind === 'cli' ? revokeCliSignIn(id) : revokeSession(id)
  }

  async function load(): Promise<void> {
    const result = await listSessions()
    if (!result.ok) {
      if (result.code === 'unauthenticated') {
        // There is no anonymous version of "your devices", so a session that has gone ends up at the
        // sign-in form, which knows how to come back here.
        go(loginPath())
        return
      }
      /* **A list that will not load is told once, in the design's own sentence** — set below, in the
         markup. The banner is for a *write* that failed, where the page still stands and the reader
         needs to know which request did not take; a list that never arrived has one thing to say, and
         `design/README.md` §登录设备 says it: 「读不到时是一句话，而且只关于这个列表」. Setting a banner
         here as well put two sentences on screen for one failure (the generic code message plus this
         one), which is the shape the design's own `sessions-failed.png` does not have.
         The key screen does set both, and that pair is where this was copied from; it is reported
         rather than changed here, because it is a design-approved screen this version does not touch. */
      setState('failed')
      return
    }
    setSessions(result.data.sessions)
    setState('ready')
    /* **And whatever the last failed request was claiming stops claiming it.** A banner set by a write
       that failed used to stand until the tab was reloaded: the list came back correct and the page
       still said a request had not gone through, with no way to dismiss it and nothing on screen that
       was actually stale. The key screen states the same rule in the same place (`load` clears the
       banner), and this is the one screen where it matters more — a session list read after somebody
       signed in elsewhere is supposed to become true, and a standing error is the one thing on it that
       cannot. The two writers below therefore set their message *after* re-reading, so that this line
       cannot erase the answer to a click that just failed. */
    setBanner(null)
  }

  /**
   * Opens the question over one row, and puts the focus on the answer that ends something.
   *
   * The prompt takes the row's line, which throws away the button that was pressed — so without moving
   * the focus the reader is left on `<body>` with no way to learn a question appeared. The answer that
   * destroys is the one that should be under the fingers; Escape is the other.
   */
  function ask(session: LoginSession): void {
    setAsking({ kind: 'device', session })
    requestAnimationFrame(() => {
      document
        .querySelector<HTMLButtonElement>(
          `[data-session="${session.device_id}"] [data-device-confirm]`,
        )
        ?.focus()
    })
  }

  function askOthers(): void {
    if (others === 0) {
      return
    }
    setAsking({ kind: 'others' })
    requestAnimationFrame(() => {
      document.querySelector<HTMLButtonElement>('[data-others-confirm]')?.focus()
    })
  }

  /** 「取消」, and back to the button that was pressed rather than to the top of the document. */
  function cancel(): void {
    const open = askingRef.current
    setAsking(null)
    if (open === null) {
      return
    }
    requestAnimationFrame(() => {
      const selector =
        open.kind === 'others'
          ? '[data-others]'
          : `[data-session="${open.session.device_id}"] [data-act="kick"]`
      document.querySelector<HTMLButtonElement>(selector)?.focus()
    })
  }

  async function confirmRevoke(session: LoginSession): Promise<void> {
    setBusy(true)
    const result = await endLogin(session)
    setBusy(false)
    setAsking(null)
    if (!result.ok) {
      const message = codeMessage(result.code, result.message)
      // A row that was already gone is the page being out of date, not a failure to report as one:
      // re-reading is what makes the list true again either way — and it has to happen **before** the
      // sentence is set, because a load that succeeds clears the banner (`load` says why) and would
      // take this one with it.
      await load()
      setBanner(message)
      return
    }
    await load()
  }

  async function confirmRevokeOthers(): Promise<void> {
    setBusy(true)
    const result = await revokeOtherSessions()
    setBusy(false)
    setAsking(null)
    if (!result.ok) {
      const message = codeMessage(result.code, result.message)
      await load()
      setBanner(message)
      return
    }
    /* **The answer the server sent is not drawn, and that is not an oversight.** §接口 has the write
       answer with how many devices it ended (「要在界面上说结果」), and the design has no sentence for
       it: what the screen does instead is become the truth — the rows are gone, the button that offered
       this disappears with them, and the line above now reads 「仅当前设备」. That is the result, said
       in the vocabulary the design has; a sentence would be copy invented here. Flagged in the delivery
       report so the choice is visible rather than settled by me. */
    await load()
  }

  /**
   * Escape answers 「取消」, and only while a question is open — see `AppShell`'s own listener.
   *
   * **Not while the answer has already been given.** The design's `askBefore` is synchronous, so it has
   * no such moment; this port does, and without the guard the keyboard could do what the mouse cannot:
   * both buttons are `disabled` while a write is in flight, and Escape used to close the question
   * anyway — leaving the reader told that nothing happened while the device was being ended. Escape is
   * 「取消」, and there is nothing left to cancel once the request is out.
   */
  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape' && askingRef.current !== null && !busyRef.current) {
      event.preventDefault()
      cancel()
    }
  }

  useEffect(() => {
    document.addEventListener('keydown', onKeydown)
    void load()
    return () => {
      document.removeEventListener('keydown', onKeydown)
    }
    // The listener reads `asking` and `busy` through the refs above, exactly as Vue read its refs;
    // `load` only writes state. Both are registered and started once, on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <section className="page">
      <nav className="crumbs" aria-label="面包屑">
        <a href="/account">设置</a>
        <span className="crumbs-sep" aria-hidden="true">/</span>
        <span className="crumbs-here" aria-current="page">登录设备</span>
      </nav>

      <header className="page-head">
        <h1>登录设备</h1>
      </header>

      {/* **Not while the list itself is missing.** The failed state owns its one sentence (the design's
          `sessions-failed.png`), and a banner left over from a write that failed *before* the list did
          would be a second sentence about the same outage — the shape this screen was just fixed for.
          The rule is one state, one sentence, so it is said once here rather than in each writer of
          `banner`: a failed write reaches this state only by way of a failed reload, and a failed
          reload has its own sentence below. */}
      {state !== 'failed' ? <FormBanner message={banner} /> : null}

      {/* Read as one sentence about this list and nothing else, in the key screen's own shape and for
          its own reason: what a reader fears here is that their account is in trouble, so the page
          says on the spot that it is not. */}
      {state === 'failed' ? (
        <p className="hint">
          无法加载登录设备列表，请稍后重试。此处仅表示列表加载失败，不代表账号异常。
        </p>
      ) : state === 'ready' ? (
        <>
          <p className="page-note">
            以下是登录过你账号的设备。不认识的设备可以在这里退出；退出后，该设备需要重新登录，当前设备不受影响。
          </p>

          <div className="key-card">
            <div className="key-head">
              {/* 计数是名词短语；它同时是「退出其他所有设备」那句问句里的数字，所以一处算出来。 */}
              <span className={asking?.kind === 'others' ? 'key-count is-warn' : 'key-count'}>
                {asking?.kind === 'others'
                  ? `退出后，其他 ${others} 台设备都需要重新登录；当前设备不受影响。`
                  : count}
              </span>
              <span className="session-head-acts">
                {/* **Both answers live in the cell the button did**, which is the design's shape for
                    every confirmation on this console: the question is asked where the action was, and
                    answering 「取消」 puts the button back because that is where the reader was. */}
                {asking?.kind === 'others' ? (
                  <>
                    <button
                      type="button"
                      className="link-button danger"
                      data-others-confirm=""
                      disabled={busy}
                      onClick={() => {
                        void confirmRevokeOthers()
                      }}
                    >
                      确认退出 {others} 台
                    </button>
                    <button
                      type="button"
                      className="link-button"
                      disabled={busy}
                      onClick={() => cancel()}
                    >
                      取消
                    </button>
                  </>
                ) : others > 0 ? (
                  /* Rendered only when there is something to end: with one device signed in, a button
                     offering to end the others would be a button that does nothing. */
                  <button
                    type="button"
                    className="link-button danger"
                    data-others=""
                    disabled={busy}
                    onClick={() => askOthers()}
                  >
                    退出其他所有设备
                  </button>
                ) : null}
              </span>
            </div>

            <div className="key-body">
              <ul className="sessions">
                {/* `device_id` is unique among this account's sessions; the fallback is for a session
                    that has none, which is the row that draws no button either. */}
                {sessions.map((session) => (
                  <li
                    key={session.device_id ?? session.logged_in_at}
                    className="session"
                    data-session={session.device_id}
                  >
                    <span className="session-who">
                      <span className="session-head">
                        {/* **No empty identifier line.** A session created before this version carries
                            neither of the two attributes the row is built from, so its address is not
                            "blank" — there is nothing to show, which is the same reason the second line
                            omits the device rather than printing a word of our own for it. The design
                            never draws this row (it cannot: its fixture is all post-version), so this is
                            an omission rather than a state with copy. What is left of such a row is the
                            chip, where it is the device reading the page, and the two times. A CLI login
                            always has an address here — the console it was approved from (V16). */}
                        {session.address ? <strong className="session-ip">{session.address}</strong> : null}
                        {/* The mark follows the main identifier rather than taking the action cell: a
                            state word in a cell of buttons would have to line up with them, and it is a
                            13px bare word against a 14px button. A CLI login never wears it: the person
                            reading this page is on a browser, and that row is about another machine. */}
                        {session.current ? (
                          <span className="chip is-plain session-here">当前设备</span>
                        ) : null}
                      </span>
                      {/* 什么设备　·　登录于 …　·　最近活动 … — one line, and the only thing that differs
                          between the two kinds of login is its first segment. With the question open the
                          whole line becomes the consequence, which is where the design puts it: in the
                          row it is about. */}
                      <span className={askingThis(session) ? 'session-meta is-warn' : 'session-meta'}>
                        {askingThis(session)
                          ? '退出后，该设备需要重新登录；当前设备不受影响。'
                          : whenOf(session)}
                      </span>
                    </span>
                    <span className="session-acts">
                      {askingThis(session) ? (
                        <>
                          <button
                            type="button"
                            className="link-button danger"
                            data-device-confirm=""
                            disabled={busy}
                            onClick={() => {
                              void confirmRevoke(session)
                            }}
                          >
                            确认退出
                          </button>
                          <button
                            type="button"
                            className="link-button"
                            disabled={busy}
                            onClick={() => cancel()}
                          >
                            取消
                          </button>
                        </>
                      ) : canEnd(session) ? (
                        /* Removed for the current device, not disabled: see `canEnd`. */
                        <button
                          type="button"
                          className="link-button danger"
                          data-act="kick"
                          disabled={busy}
                          onClick={() => ask(session)}
                        >
                          退出该设备
                        </button>
                      ) : null}
                    </span>
                  </li>
                ))}
              </ul>
              {sessions.length <= 1 ? (
                <p className="key-none">在别处登录过的设备会出现在这里。</p>
              ) : null}
            </div>
          </div>
        </>
      ) : (
        <p className="loading">加载中…</p>
      )}
    </section>
  )
}
