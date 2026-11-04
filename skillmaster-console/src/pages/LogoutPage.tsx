import { useEffect, useRef, useState } from 'react'
import { logout } from '../api/account'
import { SITE_URL } from '../siteUrl'

/**
 * `/logout` — the page the marketing site's 「退出登录」 points at (ADR 0086 decision 2).
 *
 * **It exists so the site never has to write.** The site cannot POST (it has no CSRF token, by
 * design), so ending a session has to happen on this origin; the site's menu is an ordinary link
 * here, and this page does the one write.
 *
 * **Where the person stops is a guide, not the site** (ADR 0141). Ending the browser session does
 * not end the CLI credential on this machine — that is a separate 登录设备 with its own lifetime —
 * and until this screen existed nothing on the way out said so. The three sentences and the copyable
 * sentence for the agent are the design's `LogoutDoneView`, copied here part by part.
 *
 * **The way to end the CLI is a sentence, not another sign-in**: the person already has an agent
 * that can run `skmr logout`, and sending them back through the sign-in form was a detour.
 *
 * `blankWhileLoading` in `App` keeps this page from drawing 「加载中…」 while the session read is
 * still out; the visit is one request and a person on their way out.
 */
export const LOGOUT_INSTRUCTION = '请运行 skmr logout，让这台电脑退出 SkillMaster 的 CLI 登录。'

const COPY_ICON = (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <rect x="9" y="9" width="11" height="11" rx="2" stroke="currentColor" strokeWidth="1.8" />
    <path
      d="M5 15V6a2 2 0 0 1 2-2h9"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
    />
  </svg>
)

const CHECK_ICON = (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path
      d="M5 12.5 9.5 17 19 7.5"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
)

/**
 * The design's `writeClipboard`, kept in step with it: the async API where it exists, the
 * selection-based copy where it does not (`execCommand` still works from inside a click handler),
 * and a visible failure only when both paths fail.
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

export default function LogoutPage() {
  /** `working` draws nothing (see the module comment); the other two are the two answers. */
  const [state, setState] = useState<'working' | 'done' | 'failed'>('working')

  /**
   * The write, started at most once per visit.
   *
   * **Under `StrictMode` React runs an effect twice** (mount, unmount, mount), so an effect that
   * posts directly would send two logouts. A ref outlives that remount while the page does not, and
   * holding the *promise* rather than a flag lets the second run still receive the answer.
   */
  const pending = useRef<ReturnType<typeof logout> | null>(null)

  useEffect(() => {
    let cancelled = false
    pending.current ??= logout()
    void pending.current.then((result) => {
      if (cancelled) return
      setState(result.ok ? 'done' : 'failed')
    })
    return () => {
      cancelled = true
    }
  }, [])

  if (state === 'working') return null

  if (state === 'failed') {
    return (
      <main className="logout-fallback">
        <p className="hint">没能退出登录。可以回去重试，或直接关闭这个页面。</p>
        <a className="link-button" href={SITE_URL}>
          回到首页
        </a>
      </main>
    )
  }

  return <LogoutDone />
}

/** The design's standalone dialog, with its one view: three sentences and the sentence to copy. */
function LogoutDone() {
  const [done, setDone] = useState(false)
  const [failed, setFailed] = useState(false)
  const timer = useRef<number | null>(null)
  const dialogRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    // The design focuses the dialog when a view opens; a keyboard user who pressed 「退出登录」 on
    // another page should land here, not on the body.
    dialogRef.current?.focus()
    return () => {
      if (timer.current !== null) window.clearTimeout(timer.current)
    }
  }, [])

  const title = done ? '已复制' : failed ? '复制失败，请手动选择' : '复制这句话'

  return (
    <div className="overlay is-standalone">
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogTitle"
        tabIndex={-1}
        ref={dialogRef}
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
          已退出登录
        </h2>

        <div className="dialog-view" id="logoutDoneView">
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

          {/* The design draws a button whose host decides where home is; on this origin that is the
              site, and the top bar's brand links to the same value (`../siteUrl.ts`). A button rather
              than an anchor because that is the control the design draws — and `a:hover` underlines,
              which would make this one button look unlike every other. */}
          <button
            type="button"
            className="btn btn-block dialog-submit"
            id="logoutHome"
            onClick={() => window.location.replace(SITE_URL)}
          >
            回到首页
          </button>
        </div>
      </div>
    </div>
  )
}
