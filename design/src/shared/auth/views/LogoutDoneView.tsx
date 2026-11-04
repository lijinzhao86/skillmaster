/**
 * Where the browser stops after signing out.
 *
 * **The one page that can say what a sign-out did not do.** It ends the browser session and nothing
 * else: the CLI credential on this machine is a separate 登录设备 with its own lifetime, and until
 * this page existed nothing on the way out mentioned it.
 *
 * **The way to end it is a sentence for the agent, not another sign-in.** Sending the person back
 * through the sign-in form to reach 「登录设备」 was a detour: the thing that has to stop is the CLI
 * on this machine, and the person already has an agent that can run it. So the page hands them one
 * sentence to copy.
 *
 * **The control is the console's own copy button** — the one that sits beside a value and draws only
 * an icon (`.vp-copy`): a 24px box, a 16px drawing, the check for 1.2 seconds after a successful
 * copy. The label is not repeated in the box; the box's top-right corner is where this system
 * already puts that action.
 *
 * No close button: the page is the whole screen, and there is nothing behind it to go back to.
 */

import { useEffect, useRef, useState } from 'react'

/** The sentence the person copies. It names the command so an agent can act on it and a person can
 *  run it themselves. */
export const LOGOUT_INSTRUCTION = '请运行 skmr logout，让这台电脑退出 SkillMaster 的 CLI 登录。'

const COPY_ICON = (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <rect x="9" y="9" width="11" height="11" rx="2" stroke="currentColor" strokeWidth="1.8" />
    <path d="M5 15V6a2 2 0 0 1 2-2h9" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
  </svg>
)

const CHECK_ICON = (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path d="M5 12.5 9.5 17 19 7.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)

/**
 * **`navigator.clipboard` is not always there, and on `file://` it is not always allowed.** The
 * demo is a file people double-click, so a refusal must not leave the button saying nothing
 * happened: fall back to the selection-based copy, and report a failure only when both paths fail.
 * This is the console's own helper, kept in step with it.
 */
function writeClipboard(text: string, done: () => void, fail: () => void): void {
  const fallback = () => {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    let ok = false
    try {
      ok = document.execCommand('copy')
    } catch {
      ok = false
    }
    document.body.removeChild(area)
    if (ok) done()
    else fail()
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done, fallback)
  } else {
    fallback()
  }
}

export interface LogoutDoneViewProps {
  active: boolean
  onHome: () => void
}

export default function LogoutDoneView({ active, onHome }: LogoutDoneViewProps) {
  const [done, setDone] = useState(false)
  const [failed, setFailed] = useState(false)
  const timer = useRef<number | null>(null)

  useEffect(
    () => () => {
      if (timer.current !== null) window.clearTimeout(timer.current)
    },
    [],
  )

  const title = done ? '已复制' : failed ? '复制失败，请手动选择' : '复制这句话'

  return (
    <div className="dialog-view" id="logoutDoneView" hidden={!active}>
      <div className="logout-copy">
        <p>这个浏览器上的登录已经结束。</p>
        <p>这台电脑上的 CLI 如果还没有退出，就仍然可以正常使用。</p>
        <p>要让它在终端也退出，把这句话交给 agent：</p>
      </div>

      <div className="logout-instruction">
        <p id="logoutInstruction">
          请运行 <code>skmr logout</code>，让这台电脑退出 SkillMaster 的 CLI 登录。
        </p>
        <button
          type="button"
          className={'logout-copy-btn' + (done ? ' is-done' : '')}
          id="logoutCopy"
          aria-label={done ? '已复制这句话' : failed ? '复制失败，请手动选择' : '复制这句话'}
          title={title}
          onClick={() => {
            writeClipboard(
              LOGOUT_INSTRUCTION,
              () => {
                setDone(true)
                setFailed(false)
                if (timer.current !== null) window.clearTimeout(timer.current)
                timer.current = window.setTimeout(() => setDone(false), 1200)
              },
              () => setFailed(true),
            )
          }}
        >
          {done ? CHECK_ICON : COPY_ICON}
          <span className="logout-sr" aria-live="polite">
            {done ? '已复制' : ''}
          </span>
        </button>
      </div>

      <button type="button" className="btn btn-block dialog-submit" id="logoutHome" onClick={onHome}>
        回到首页
      </button>
    </div>
  )
}
