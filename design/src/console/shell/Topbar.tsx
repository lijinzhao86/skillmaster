/**
 * The top bar: what this is, where you can go, and who you are — left, centre, right.
 *
 * **The columns are `1fr auto 1fr`**, which is the reference product's own grid: the search sits in
 * the `auto` column, so it is centred on the page rather than only centred in whatever the two sides
 * left over.
 *
 * Everything here is the frame's, not a page's: the search box, the account menu and the navigation
 * are the same on every screen, which is why they take no props beyond the page the address names.
 */

import { useEffect, useRef, useState } from 'react'
import { go, skillsAddress } from '../address'

export default function Topbar() {
  const [menuOpen, setMenuOpen] = useState(false)
  const meBtn = useRef<HTMLButtonElement>(null)
  const meMenu = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)

  /* `/` puts the cursor in the box, which is what the hint on the right of it is promising. Ignored
     while the reader is already typing somewhere — otherwise the character never arrives. */
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) return
      const active = document.activeElement as HTMLElement | null
      if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable)) {
        return
      }
      if (!searchBox.current) return
      event.preventDefault()
      searchBox.current.focus()
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

      {/* A real search: it goes to the skill list's own query, because that is the one thing this
          product has to search. A box that does nothing is worse than no box at all. */}
      <form
        className="topbar-search"
        id="topSearchForm"
        role="search"
        onSubmit={(event) => {
          event.preventDefault()
          go(skillsAddress(searchBox.current?.value ?? ''))
        }}
      >
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
          <line x1="15.4" y1="15.4" x2="20.6" y2="20.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
        <label className="visually-hidden" htmlFor="topSearch">搜索 skill</label>
        <input
          id="topSearch"
          ref={searchBox}
          type="search"
          placeholder="搜索你的 skill"
          autoComplete="off"
          spellCheck={false}
        />
        <kbd>/</kbd>
      </form>

      <div className="topbar-right">
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
