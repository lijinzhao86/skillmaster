/**
 * The top bar: what this is, and who you are — left and right.
 *
 * **It used to carry a search box in a centred `auto` column, and no longer does (2026-11-07).**
 * That box never searched anything itself: submitting it handed the question to 我的 skill's own
 * `q`. It was a second door to the same room, and it cost the middle column, a `/` badge and a
 * narrow-screen breakpoint. `/` survives, aimed at the page's own search box instead.
 *
 * Everything here is the frame's, not a page's: the account menu and the navigation are the same on
 * every screen, which is why they take no props beyond the page the address names.
 */

import { useEffect, useRef, useState } from 'react'
import { go } from '../address'
import NotificationsBell from './NotificationsBell'

export default function Topbar({ page, params }: { page: string; params: URLSearchParams }) {
  const [menuOpen, setMenuOpen] = useState(false)
  const meBtn = useRef<HTMLButtonElement>(null)
  const meMenu = useRef<HTMLDivElement>(null)

  /* `/` puts the cursor in **this page's own** search box — the one the reader would have clicked.
     Ignored while the reader is already typing somewhere, otherwise the character never arrives; a
     page with no search box ignores the key too (nothing to focus, and an error would be worse). */
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) return
      const active = document.activeElement as HTMLElement | null
      if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable)) {
        return
      }
      /* `data-page-search` is the marker each page puts on its own box. The first one actually on
         screen wins: panels are put away with the `hidden` attribute, and the editor collapses its
         file tree with a class (`display:none`) instead — a `hidden`-only test would focus an
         invisible box and swallow the key. */
      const box = Array.from(document.querySelectorAll<HTMLInputElement>('[data-page-search]')).find(
        (input) =>
          input.closest('[hidden]') === null &&
          (input.offsetParent !== null || input.getClientRects().length > 0),
      )
      if (!box) return
      event.preventDefault()
      box.focus()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  /* The account menu closes on a click anywhere else, and **Escape closes it and hands the focus back
     to the avatar** — without that the only way out was a click somewhere else, which a keyboard
     reader has to go looking for. */
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
    <header className="topbar">
      <div className="topbar-left">
        {/* **A stand-in, not the product's target.** The console's brand goes to the marketing site
            (`VITE_SITE_URL`, the same address `/logout` returns to — design README §顶栏的品牌回首页).
            The demo has no external site, so it points at its own skills page instead; porting this
            href as-is would give the product a link that reloads the page you are already on.

            **The name is on the link, not on the letters.** Below 640px `.brand-name` is hidden and
            the mark is decorative, so an anchor named only by its text would have no accessible name
            there. `aria-label` carries it at every width, and it contains the visible word, so the
            name and the label agree. */}
        <a className="brand" href="?page=skills" aria-label="SkillMaster 首页">
          <svg className="mark" viewBox="0 0 24 24" aria-hidden="true">
            <rect x="3" y="4" width="18" height="4.5" rx="2.25" fill="currentColor" />
            <rect x="3" y="10.5" width="13" height="4.5" rx="2.25" fill="currentColor" opacity="0.6" />
            <rect x="3" y="17" width="8" height="4.5" rx="2.25" fill="currentColor" opacity="0.32" />
          </svg>
          <span className="brand-name">SkillMaster</span>
        </a>
      </div>

      <div className="topbar-right">
        {/* **通知走顶栏，不进侧栏。** 侧栏两组各有自己的主语（去哪找 / 设置），通知不属于任何一组；
            而它是一个随时可以瞥一眼的入口，与账号并列是它本来就在的位置。 */}
        <NotificationsBell page={page} params={params} />

        {/* The account, which used to be the sidebar's foot. It is in the top bar in the reference
            product too, and moving it is what leaves the sidebar with no foot to align with. */}
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
            <button
              type="button"
              className="nav-signout"
              id="signOutBtn"
              onClick={() => go('?page=login')}
            >
              退出登录
            </button>
          </div>
        </div>
      </div>
    </header>
  )
}
