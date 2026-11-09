/**
 * The marketing site's top bar, in both navigation states.
 *
 * **Moved from `design/src/home/SiteNav.tsx`** when the legal pages arrived (v56): those pages have
 * the same bar, and two copies of it would drift. The demo's console-demo links become the product's
 * `VITE_CONSOLE_URL`; the demo's hard-coded avatar becomes the session's account.
 */

import { useEffect, useRef, useState } from 'react'
import { consoleLink } from './console'

const noop = (event: { preventDefault: () => void }) => event.preventDefault()

export interface SiteNavProps {
  /** The account the session read, or null when signed out. */
  accountName: string | null
  /**
   * Where the wordmark goes. The home page passes nothing — the wordmark there is the current
   * page, and the design's check pins that clicking it does not move. The legal pages pass `/`,
   * because there it is a way back.
   */
  brandHref?: string
  /**
   * A legal page scrolls under the bar, so the bar needs a ground there. The home page keeps it
   * transparent: its hero already supplies one.
   */
  solid?: boolean
}

/** The avatar's letter: the design draws the first character of the username. */
function initialOf(username: string): string {
  return username.trim().slice(0, 1).toLowerCase()
}

export default function SiteNav({ accountName, brandHref, solid = false }: SiteNavProps) {
  const [menuOpen, setMenuOpen] = useState(false)
  const meBtn = useRef<HTMLButtonElement>(null)
  const meMenu = useRef<HTMLDivElement>(null)

  /* **The account menu behaves exactly like the console's**: a click anywhere else closes it, and
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

  return (
    <header className={'nav' + (solid ? ' is-solid' : '')} id="nav">
      <div className="wrap nav-inner">
        <a
          className="brand"
          id={brandHref ? 'brandHome' : undefined}
          href={brandHref ?? '#'}
          {...(brandHref ? {} : { 'data-noop': true, onClick: noop })}
        >
          <svg className="mark" viewBox="0 0 24 24" aria-hidden="true">
            <rect x="3" y="4" width="18" height="4.5" rx="2.25" fill="currentColor" />
            <rect x="3" y="10.5" width="13" height="4.5" rx="2.25" fill="currentColor" opacity="0.6" />
            <rect x="3" y="17" width="8" height="4.5" rx="2.25" fill="currentColor" opacity="0.32" />
          </svg>
          <span className="brand-name">SkillMaster</span>
        </a>

        <div className="nav-right">
          {accountName ? (
            /* **Signed in: the way in becomes the way across.** 「进入控制台」 is what a signed-in
               visitor is here for, and the account is the console's own control — same avatar,
               same menu, same 退出登录 (ADR 0086 decision 2). */
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
                  <span className="avatar" aria-hidden="true">{initialOf(accountName)}</span>
                  <span className="me-name">{accountName}</span>
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
  )
}
