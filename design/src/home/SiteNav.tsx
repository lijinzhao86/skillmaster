/**
 * The marketing site's top bar, in both navigation states.
 *
 * It moved out of `HomeApp` when the legal pages arrived: those pages have the same bar, and two
 * copies of it would drift. The menu's open/closed state and its outside-click/Escape handling
 * live here with the markup that needs them.
 *
 * The demo links at the console demo file; the product builds the same two addresses from
 * `VITE_CONSOLE_URL` (`skillmaster-site/src/App.tsx`).
 */

import { useEffect, useRef, useState } from 'react'

/** In the product this is `VITE_CONSOLE_URL`; in the single-file demo it is the console demo. */
const CONSOLE_DEMO = 'skillmaster-控制台演示.html'

const noop = (event: { preventDefault: () => void }) => event.preventDefault()

export interface SiteNavProps {
  /** The demo spells this in the address (`?signed-in`); the product reads the session. */
  signedIn: boolean
  /**
   * Where the wordmark goes. The home page passes nothing — the wordmark there is the current
   * page, and the demo's own check pins that clicking it does not move. The legal pages pass the
   * home address, because there it is a way back.
   */
  brandHref?: string
  /**
   * A legal page scrolls under the bar, so the bar needs a ground there. The home page keeps it
   * transparent: its hero already supplies one.
   */
  solid?: boolean
}

export default function SiteNav({ signedIn, brandHref, solid = false }: SiteNavProps) {
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
          {signedIn ? (
            /* **Signed in: the way in becomes the way across.** 「进入控制台」 is the one thing a
               signed-in visitor is here for, and the account is the console's own control —
               same avatar, same menu, same 退出登录. */
            <>
              <a className="btn btn-sm" id="toConsoleBtn" href={CONSOLE_DEMO}>进入控制台</a>
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
                  <span className="avatar" aria-hidden="true">d</span>
                  <span className="me-name">dev</span>
                </button>
                <div className="me-menu" id="meMenu" ref={meMenu} hidden={!menuOpen}>
                  {/* **A link in both places.** The product's goes to the console's `/logout`
                      (ADR 0086 decision 2); the demo's `?` is the signed-out page itself, which is
                      the same navigation with nothing to write. */}
                  <a className="nav-signout" id="signOutBtn" href="?">退出登录</a>
                </div>
              </div>
            </>
          ) : (
            <div className="nav-guest" id="navGuest">
              <a className="link-btn" id="loginBtn" href={CONSOLE_DEMO + '?page=login'}>登录</a>
              <a className="btn btn-sm" id="startBtnNav" href={CONSOLE_DEMO + '?page=register'}>开始使用</a>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}
