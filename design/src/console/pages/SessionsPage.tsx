/**
 * 登录设备: the machines that have signed in, and the one control that ends them.
 *
 * **Two things sit in this list.** A browser session and the CLI's own sign-in on the same machine
 * are both rows, and the fixture has one of each on the same laptop: the address is identical, and
 * what tells them apart is the second line (`Chrome · macOS` against `CLI · 我的 MacBook`), not
 * another mark.
 *
 * The confirmation is the page's own (`askBefore` in the old source): the question replaces the
 * sentence it is about, the two answers replace the action, Escape answers 取消, and 取消 puts the
 * focus back on the control that asked.
 */

import { useEffect, useRef, useState } from 'react'
import type { DemoPageProps } from './types'

interface Session {
  id: string
  ip: string
  device: string
  signedIn: string
  seen: string
  current?: boolean
}

/** ⚠️ Fixtures, like the credentials: there is no endpoint yet. What is not invented is the shape —
 *  the address is shown in full, the current device has no control, and ending a session takes
 *  effect rather than waiting for somebody to clear a flag. */
const SESSIONS: Session[] = [
  { id: 's1', ip: '192.0.2.88', device: 'Chrome · macOS', signedIn: '2026/10/16 08:40', seen: '刚刚', current: true },
  { id: 's2', ip: '192.0.2.88', device: 'CLI · 我的 MacBook', signedIn: '2026/10/16 08:41', seen: '刚刚' },
  { id: 's3', ip: '203.0.113.24', device: 'Safari · iPhone', signedIn: '2026/10/14 09:12', seen: '今天 05:40' },
  { id: 's4', ip: '198.51.100.7', device: 'Chrome · Windows', signedIn: '2026/10/12 21:04', seen: '昨天 18:20' },
]

type Asking = { kind: 'one'; id: string } | { kind: 'all' } | null

export default function SessionsPage({ active, state }: DemoPageProps) {
  /* 「仅当前设备」这个态的夹具就是「别的机器都没登录过」。 */
  const [sessions, setSessions] = useState<Session[]>(
    state === 'empty' ? SESSIONS.filter((session) => session.current) : SESSIONS,
  )
  const [asking, setAsking] = useState<Asking>(null)
  const [focusBack, setFocusBack] = useState<string | null>(null)
  const yes = useRef<HTMLButtonElement>(null)

  const others = sessions.filter((session) => !session.current).length

  /* The answers take the focus when they appear: nothing here is announced on its own, and the
     control that was pressed has just been removed. */
  useEffect(() => {
    if (asking !== null) yes.current?.focus()
  }, [asking])

  /* 取消 rebuilds the row the question had emptied, which throws the focused node away — so without
     this the reader lands on `<body>`. */
  useEffect(() => {
    if (asking === null && focusBack !== null) {
      const again = focusBack === 'head'
        ? document.getElementById('sessionKickOthers')
        : (document.querySelector(
          '[data-session="' + focusBack + '"] [data-act="kick"]',
        ) as HTMLElement | null)
      again?.focus()
      setFocusBack(null)
    }
  }, [asking, focusBack])

  const cancel = (back: string | null) => {
    setAsking(null)
    setFocusBack(back)
  }
  const drop = (id: string) => {
    setSessions((rows) => rows.filter((session) => session.id !== id))
    setAsking(null)
  }
  const dropOthers = () => {
    setSessions((rows) => rows.filter((session) => session.current))
    setAsking(null)
  }

  const answers = (
    where: 'row' | 'head',
    label: string,
    onConfirm: () => void,
    back: string | null,
  ) => (
    <span
      className={where === 'head' ? 'session-head-acts' : 'session-acts'}
      id={where === 'head' ? 'sessionHeadActs' : undefined}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          cancel(back)
        }
      }}
    >
      <button type="button" className="link-button danger" ref={yes} onClick={onConfirm}>{label}</button>
      <button type="button" className="link-button" onClick={() => cancel(back)}>取消</button>
    </span>
  )

  return (
    <section className="page" id="page-sessions" hidden={!active}>
      <nav className="crumbs" aria-label="面包屑">
        <a href="?page=account">设置</a>
        <span className="crumbs-sep" aria-hidden="true">/</span>
        <span className="crumbs-here" aria-current="page">登录设备</span>
      </nav>

      <header className="page-head">
        <h1>登录设备</h1>
      </header>

      <p className="page-note" data-when="ready empty" hidden={state !== 'ready' && state !== 'empty'}>
        以下是登录过你账号的设备。不认识的设备可以在这里退出；退出后，该设备需要重新登录，当前设备不受影响。
      </p>

      <div className="key-card" data-when="ready empty" hidden={state !== 'ready' && state !== 'empty'}>
        <div className="key-head">
          <span className={'key-count' + (asking?.kind === 'all' ? ' is-warn' : '')} id="sessionCount">
            {asking?.kind === 'all'
              ? '退出后，其他 ' + others + ' 台设备都需要重新登录；当前设备不受影响。'
              : others === 0 ? '仅当前设备' : sessions.length + ' 台设备'}
          </span>
          {/* **这一格的按钮是渲染出来的，不是写在标记里的**：下面那套「先问后果」会把这一格清空来
              放两个答案，而「取消」必须把按钮放回去。一个写者。 */}
          {asking?.kind === 'all' ? (
            answers('head', '确认退出 ' + others + ' 台', dropOthers, 'head')
          ) : (
            <span className="session-head-acts" id="sessionHeadActs">
              {others > 0 && (
                <button
                  type="button"
                  id="sessionKickOthers"
                  className="link-button danger"
                  onClick={() => setAsking({ kind: 'all' })}
                >
                  退出其他所有设备
                </button>
              )}
            </span>
          )}
        </div>
        <div className="key-body">
          <ul className="sessions" id="sessionList">
            {sessions.map((session) => {
              const askingThis = asking?.kind === 'one' && asking.id === session.id
              return (
                <li className="session" data-session={session.id} key={session.id}>
                  <span className="session-who">
                    <span className="session-head">
                      <strong className="session-ip">{session.ip}</strong>
                      {/* **标记跟着主标识，不占动作那一格。** */}
                      <span className="chip is-plain session-here" hidden={!session.current}>当前设备</span>
                    </span>
                    <span className={'session-meta' + (askingThis ? ' is-warn' : '')}>
                      {askingThis
                        ? '退出后，该设备需要重新登录；当前设备不受影响。'
                        : session.device + '　·　登录于 ' + session.signedIn + '　·　最近活动 ' + session.seen}
                    </span>
                  </span>
                  {askingThis ? (
                    answers('row', '确认退出', () => drop(session.id), session.id)
                  ) : (
                    <span className="session-acts">
                      <button
                        type="button"
                        className="link-button danger"
                        data-act="kick"
                        hidden={!!session.current}
                        onClick={() => setAsking({ kind: 'one', id: session.id })}
                      >
                        退出该设备
                      </button>
                    </span>
                  )}
                </li>
              )
            })}
          </ul>
          <p className="key-none" id="sessionNone" hidden={sessions.length > 1}>
            在别处登录过的设备会出现在这里。
          </p>
        </div>
      </div>

      {/* 读不到。**一句话，而且只关于这个列表**——怕的人怕的是账号出事，所以要当场说清不是。 */}
      <p className="hint" data-when="failed" hidden={state !== 'failed'}>
        无法加载登录设备列表，请稍后重试。此处仅表示列表加载失败，不代表账号异常。
      </p>
    </section>
  )
}
