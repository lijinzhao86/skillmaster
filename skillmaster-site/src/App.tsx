/**
 * The marketing page: what the product is, and the ways into the console.
 *
 * **Moved from `design/src/home/HomeApp.tsx`** with three changes, and nothing else:
 *
 * 1. the demo's `?signed-in` flag is gone — the state comes from the one session read in
 *    `session.ts` (ADR 0086);
 * 2. the demo strip (`DemoBar`) is not here; the product has no strip, and `--demo-bar` is 0;
 * 3. every outbound address is built from `VITE_CONSOLE_URL` instead of pointing at the demo file.
 *
 * Structure, class names, copy and spacing are the design's, character for character. The two
 * screens are a deck, not a scroll: one gesture moves the whole screen and locks until it settles;
 * there is no position in between to rest on.
 */

import { useEffect, useRef, useState } from 'react'
import { consoleLink } from './console'
import { useSession } from './session'

const noop = (event: { preventDefault: () => void }) => event.preventDefault()

/** What a person copies into their agent. Rendered and copied from this one string. */
const AGENT_PROMPT = '请帮我下载并安装 SkillMaster CLI。'

type CopyState = 'idle' | 'copied' | 'failed'

/**
 * **`navigator.clipboard` is not everywhere, and on an insecure origin it is refused.** Fall back to
 * the selection-based copy, and let the caller say 「复制失败」 only when both paths fail.
 */
async function writeClipboard(text: string) {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return
    } catch {
      // The fallback below is the one that still works when the modern API is refused.
    }
  }

  const active = document.activeElement as HTMLElement | null
  const field = document.createElement('textarea')
  field.value = text
  field.setAttribute('readonly', '')
  field.style.position = 'fixed'
  field.style.top = '-1000px'
  document.body.appendChild(field)
  field.select()
  const copied = document.execCommand('copy')
  document.body.removeChild(field)
  active?.focus()
  if (!copied) throw new Error('copy failed')
}

/** The avatar's letter: the design draws the first character of the username. */
function initialOf(username: string): string {
  return username.trim().slice(0, 1).toLowerCase()
}

export default function App() {
  const { account } = useSession()
  const [slide, setSlide] = useState(0)
  const [copyState, setCopyState] = useState<CopyState>('idle')
  const [menuOpen, setMenuOpen] = useState(false)
  const slideRef = useRef(0)
  const lockRef = useRef(false)
  const meBtn = useRef<HTMLButtonElement>(null)
  const meMenu = useRef<HTMLDivElement>(null)

  /* One gesture, one screen. The lock lasts as long as the 700ms transition. */
  const goTo = (next: number) => {
    const target = Math.max(0, Math.min(1, next))
    if (target === slideRef.current || lockRef.current) return
    lockRef.current = true
    slideRef.current = target
    setSlide(target)
    window.setTimeout(() => {
      lockRef.current = false
    }, 760)
  }

  useEffect(() => {
    const onWheel = (event: WheelEvent) => {
      if (Math.abs(event.deltaY) < 8) return
      event.preventDefault()
      goTo(event.deltaY > 0 ? slideRef.current + 1 : slideRef.current - 1)
    }
    const onKey = (event: KeyboardEvent) => {
      if (['ArrowDown', 'PageDown', ' '].includes(event.key)) {
        event.preventDefault()
        goTo(slideRef.current + 1)
      } else if (['ArrowUp', 'PageUp'].includes(event.key)) {
        event.preventDefault()
        goTo(slideRef.current - 1)
      } else if (event.key === 'Home') {
        event.preventDefault()
        goTo(0)
      } else if (event.key === 'End') {
        event.preventDefault()
        goTo(1)
      }
    }
    let touchStart = 0
    const onTouchStart = (event: TouchEvent) => {
      touchStart = event.touches[0]?.clientY ?? 0
    }
    const onTouchEnd = (event: TouchEvent) => {
      const touchEnd = event.changedTouches[0]?.clientY ?? touchStart
      const delta = touchStart - touchEnd
      if (Math.abs(delta) < 40) return
      goTo(slideRef.current + (delta > 0 ? 1 : -1))
    }
    window.addEventListener('wheel', onWheel, { passive: false })
    window.addEventListener('keydown', onKey)
    window.addEventListener('touchstart', onTouchStart, { passive: true })
    window.addEventListener('touchend', onTouchEnd, { passive: true })
    return () => {
      window.removeEventListener('wheel', onWheel)
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('touchstart', onTouchStart)
      window.removeEventListener('touchend', onTouchEnd)
    }
  }, [])

  useEffect(() => {
    if (copyState === 'idle') return
    const timer = window.setTimeout(() => setCopyState('idle'), 2000)
    return () => window.clearTimeout(timer)
  }, [copyState])

  /* The account menu behaves exactly like the console's: a click anywhere else closes it, and
     Escape closes it and hands the focus back to the button. */
  useEffect(() => {
    if (!menuOpen) return
    const onDown = (event: MouseEvent) => {
      const target = event.target as Node
      if (meBtn.current?.contains(target) || meMenu.current?.contains(target)) return
      setMenuOpen(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setMenuOpen(false)
      meBtn.current?.focus()
    }
    document.addEventListener('click', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('click', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [menuOpen])

  const copyPrompt = async () => {
    try {
      await writeClipboard(AGENT_PROMPT)
      setCopyState('copied')
    } catch {
      setCopyState('failed')
    }
  }

  return (
    <>
      <header className="nav" id="nav">
        <div className="wrap nav-inner">
          <a className="brand" href="#" data-noop onClick={noop}>
            <svg className="mark" viewBox="0 0 24 24" aria-hidden="true">
              <rect x="3" y="4" width="18" height="4.5" rx="2.25" fill="currentColor" />
              <rect x="3" y="10.5" width="13" height="4.5" rx="2.25" fill="currentColor" opacity="0.6" />
              <rect x="3" y="17" width="8" height="4.5" rx="2.25" fill="currentColor" opacity="0.32" />
            </svg>
            <span className="brand-name">SkillMaster</span>
          </a>

          <div className="nav-right">
            {account ? (
              /* **Signed in: the way in becomes the way across.** 「进入控制台」 is what a
                 signed-in visitor is here for, and the account is the console's own control. Its
                 menu holds one link — 退出登录 — which is a navigation to the console, not a write
                 from here (ADR 0086 decision 2). */
              <>
                <a className="btn btn-sm" id="toConsoleBtn" href={consoleLink('')}>进入控制台</a>
                <div className="me-wrap">
                  <button
                    type="button"
                    className="me"
                    id="meBtn"
                    ref={meBtn}
                    aria-expanded={menuOpen ? 'true' : 'false'}
                    aria-haspopup="true"
                    onClick={() => setMenuOpen((open) => !open)}
                  >
                    <span className="avatar" aria-hidden="true">{initialOf(account.username)}</span>
                    <span className="me-name">{account.username}</span>
                  </button>
                  <div className="me-menu" id="meMenu" ref={meMenu} hidden={!menuOpen}>
                    <a className="nav-signout" id="signOutBtn" href={consoleLink('/logout')}>退出登录</a>
                  </div>
                </div>
              </>
            ) : (
              <div className="nav-guest" id="navGuest">
                <a className="link-btn" id="loginBtn" href={consoleLink('/login')}>登录</a>
                <a className="btn btn-sm" id="startBtnNav" href={consoleLink('/register')}>开始使用</a>
              </div>
            )}
          </div>
        </div>
      </header>

      <div className={'deck' + (slide === 1 ? ' is-second' : '')}>
        <main>
          <section className="hero" onFocus={() => goTo(0)}>
            <div className="wrap">
              <h1>SkillMaster 随时在场</h1>

              <p className="prompt-hint">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M12 2.5l1.9 5.6 5.6 1.9-5.6 1.9L12 17.5l-1.9-5.6L4.5 10l5.6-1.9L12 2.5z" fill="currentColor" />
                </svg>
                <span>复制下面这句安装指令，发给你常用的 agent，开启你的 skill 之旅。</span>
              </p>

              <div className="prompt-wrap">
                <span className="prompt-glow" aria-hidden="true" />
                <div className="prompt">
                  <div className="prompt-head">
                    <span className="prompt-label">
                      <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path d="m4 17 6-6-6-6M12 19h8" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                      安装指令
                    </span>
                    <button
                      type="button"
                      className={
                        'prompt-copy' +
                        (copyState === 'copied' ? ' is-copied' : copyState === 'failed' ? ' is-failed' : '')
                      }
                      id="copyPromptBtn"
                      onClick={copyPrompt}
                    >
                      {copyState === 'copied' ? (
                        <svg viewBox="0 0 24 24" aria-hidden="true">
                          <path d="M20 6 9 17l-5-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                        </svg>
                      ) : copyState === 'failed' ? (
                        <svg viewBox="0 0 24 24" aria-hidden="true">
                          <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="1.8" />
                          <path d="M12 8v5M12 16h.01" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                        </svg>
                      ) : (
                        <svg viewBox="0 0 24 24" aria-hidden="true">
                          <rect x="9" y="9" width="11" height="11" rx="2" fill="none" stroke="currentColor" strokeWidth="1.8" />
                          <path d="M5 15V5a2 2 0 0 1 2-2h10" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
                        </svg>
                      )}
                      <span>{copyState === 'copied' ? '已复制' : copyState === 'failed' ? '复制失败' : '复制'}</span>
                    </button>
                    <span className="sr-only" role="status">
                      {copyState === 'copied'
                        ? '已复制到剪贴板。'
                        : copyState === 'failed'
                          ? '复制失败，请手动选择这段文字。'
                          : ''}
                    </span>
                  </div>
                  <p className="prompt-body" id="agentPrompt">{AGENT_PROMPT}</p>
                </div>
              </div>

              <div className="cards-grid">
                <article className="card">
                  <div className="card-head">
                    <span className="card-icon" aria-hidden="true">
                      {/* Lucide `cloud-download` (ISC). */}
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M12 13v8l-4-4" />
                        <path d="m12 21 4-4" />
                        <path d="M4.393 15.269A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.436 8.284" />
                      </svg>
                    </span>
                    <h3>云端加载，随处调用</h3>
                  </div>
                  <p className="card-body">把 skill 保存到云端，换哪个 agent 都能用。</p>
                </article>

                <article className="card">
                  <div className="card-head">
                    <span className="card-icon" aria-hidden="true">
                      {/* Lucide `share-2` (ISC). */}
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                        <circle cx="18" cy="5" r="3" />
                        <circle cx="6" cy="12" r="3" />
                        <circle cx="18" cy="19" r="3" />
                        <line x1="8.59" x2="15.42" y1="13.51" y2="17.49" />
                        <line x1="15.41" x2="8.59" y1="6.51" y2="10.49" />
                      </svg>
                    </span>
                    <h3>技能共享，知识沉淀</h3>
                  </div>
                  <p className="card-body">把 skill 分享给朋友和同事，<br className="card-br" />他们随时都能用上最新的知识。</p>
                </article>

                <article className="card">
                  <div className="card-head">
                    <span className="card-icon" aria-hidden="true">
                      {/* Lucide `layout-grid` (ISC): a catalogue of many skills. */}
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                        <rect width="7" height="7" x="3" y="3" rx="1" />
                        <rect width="7" height="7" x="14" y="3" rx="1" />
                        <rect width="7" height="7" x="14" y="14" rx="1" />
                        <rect width="7" height="7" x="3" y="14" rx="1" />
                      </svg>
                    </span>
                    <h3>遇到问题，总有办法</h3>
                  </div>
                  <p className="card-body"><strong className="card-num">1000+</strong> 个精选 skill，总有一个能解决问题。</p>
                </article>
              </div>
            </div>

            <button
              type="button"
              className="scroll-hint"
              onClick={() => goTo(1)}
              aria-label="下滑看产品与文档"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M6 9l6 6 6-6" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
          </section>
        </main>

        <footer
          className={'foot' + (slide === 1 ? ' is-visible' : '')}
          id="foot"
          onFocus={() => goTo(1)}
        >
          <div className="wrap">
            <div className="foot-top">
              <div className="foot-brand">
                <div className="foot-name">
                  <svg className="mark" viewBox="0 0 24 24" aria-hidden="true">
                    <rect x="3" y="4" width="18" height="4.5" rx="2.25" fill="currentColor" />
                    <rect x="3" y="10.5" width="13" height="4.5" rx="2.25" fill="currentColor" opacity="0.6" />
                    <rect x="3" y="17" width="8" height="4.5" rx="2.25" fill="currentColor" opacity="0.32" />
                  </svg>
                  <span>SkillMaster</span>
                </div>
                <p className="foot-tag">把你的 skill 托管起来，任何 agent 一行加载。</p>
              </div>

              <nav className="foot-cols" aria-label="页脚">
                <div className="foot-col">
                  <h3>产品</h3>
                  <ul>
                    <li><a id="footConsole" href={consoleLink('')}>控制台</a></li>
                    <li><a id="footStart" href={consoleLink('/register')}>开始使用</a></li>
                    <li><a id="footLogin" href={consoleLink('/login')}>登录</a></li>
                  </ul>
                </div>
                <div className="foot-col">
                  <h3>文档</h3>
                  <ul>
                    <li><a href="#" data-noop onClick={noop}>文档</a></li>
                    <li><a href="#" data-noop onClick={noop}>CLI</a></li>
                    <li><a href="#" data-noop onClick={noop}>协议</a></li>
                  </ul>
                </div>
                <div className="foot-col">
                  <h3>关于</h3>
                  <ul>
                    <li><a href="#" data-noop onClick={noop}>关于</a></li>
                    <li><a href="#" data-noop onClick={noop}>更新日志</a></li>
                    <li><a href="#" data-noop onClick={noop}>隐私</a></li>
                    <li><a href="#" data-noop onClick={noop}>条款</a></li>
                  </ul>
                </div>
              </nav>
            </div>

            <div className="foot-bottom">
              <span>© 2026 SkillMaster</span>
            </div>
          </div>
        </footer>
      </div>
    </>
  )
}
