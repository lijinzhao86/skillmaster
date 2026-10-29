import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { go } from './address'
import { SITE_URL } from './siteUrl'
import { logout } from './api/account'
import { codeMessage } from './api/errors'
import { useSession } from './store'
import FormBanner from './components/FormBanner'
import NavGroup from './components/NavGroup'

/**
 * The frame every signed-in page sits in: a top bar across the width, and the navigation beside the
 * page under it.
 *
 * **A port of `AppShell.vue`, element for element.** The console deliverable had moved the account
 * out of the sidebar's foot and into a top bar, given that bar the brand and a search box, and
 * regrouped the navigation under `SKILL` and `设置` (`design/skillmaster-控制台演示.html`, and
 * `design/README.md` §外壳). Same regions, same class names, same icons — the comparison bench is
 * what holds this file to that.
 *
 * **Four pages are not in here**, and that has not changed: signing in, registering, resetting and
 * consenting are forms a visitor reaches without an account, and wrapping them in a navigation that
 * leads to pages they cannot open would be showing them a door they cannot use. `App.tsx` decides;
 * this component does not know it exists.
 *
 * The active item is read off the path rather than passed in, because the path is where the page is:
 * there is no router (ADR 0015), so a link is a real link and a reload lands on the same item.
 */
export default function AppShell({ children }: { children?: ReactNode }) {
  const { account } = useSession()

  /** The avatar letter: one character, because that is all a circle this size holds legibly. */
  const initial = account?.username.charAt(0) ?? '?'

  const [banner, setBanner] = useState<string | null>(null)
  const [signingOut, setSigningOut] = useState(false)

  const path = window.location.pathname.replace(/\/+$/, '') || '/'
  const onDiscover = path === '/discover'
  const onSkills = path === '/' || path.startsWith('/skills')
  const onTags = path === '/my-tags'
  const onPublish = path === '/publish'
  const onAccount = path === '/account'
  // The credentials a machine acts with are their own area — the design files them under 设置 as
  // children of 权限, which is the pair the sidebar draws and the breadcrumb names. Both of them: a
  // key and a login session are the two things that may act as this account, which is why the group
  // is 权限 and why the disclosure is the only navigation item here that is not a link.
  const onKeys = path === '/account/keys'
  const onSessions = path === '/account/sessions'

  /* --------------------------------------------------------------------------------------------
     Three pieces of shell state, none of them the page's.
     -------------------------------------------------------------------------------------------- */

  // **None of this goes in the address.** The address is the page's state — which filter, which skill,
  // which file — while how far the navigation happens to be unfolded, whether the account menu is open
  // and what is sitting in the search box are the frame's business. The design keeps them in local
  // state for the same reason (its own comment over `navAccess`).
  const [menuOpen, setMenuOpen] = useState(false)
  const [searching, setSearching] = useState('')

  /**
   * The menu's open state, mirrored into a ref.
   *
   * The three listeners below are attached to the document once, when the shell mounts, so they read
   * the value they need from a ref written at the same moment as the state — the same reason
   * `useCodeRequest` mirrors its values. A listener that closed over one render's `menuOpen` would
   * answer about whatever was true when it was attached.
   */
  const menuOpenRef = useRef(false)
  const setMenu = (open: boolean): void => {
    menuOpenRef.current = open
    setMenuOpen(open)
  }

  const meWrap = useRef<HTMLDivElement | null>(null)
  const searchBox = useRef<HTMLInputElement | null>(null)
  const menuButton = useRef<HTMLButtonElement | null>(null)

  /**
   * The top bar's search hands its question to the skill list, which is the one thing here there is to
   * search. It is an entrance to that page's own filter, not a second filter of its own: the design
   * rejected the reference product's global search modal, and a box that does nothing would be worse
   * than no box at all.
   */
  function searchSkills(): void {
    const asked = searching.trim()
    go(asked === '' ? '/' : `/?q=${encodeURIComponent(asked)}`)
  }

  useEffect(() => {
    /**
     * `/` puts the cursor in the search box, which is what the hint inside it is promising.
     *
     * Ignored while the reader is already typing somewhere — otherwise the character never arrives,
     * and a skill name with a slash in it becomes impossible to type. Modifiers are left alone so
     * that the browser's own `/`-with-a-modifier shortcuts still work.
     */
    function onSlash(event: KeyboardEvent): void {
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) {
        return
      }
      const active = document.activeElement
      if (
        active !== null &&
        (active.tagName === 'INPUT' ||
          active.tagName === 'TEXTAREA' ||
          (active as HTMLElement).isContentEditable)
      ) {
        return
      }
      event.preventDefault()
      searchBox.current?.focus()
    }

    /** Anywhere that is not the menu, which is every menu of this shape. */
    function onDocumentClick(event: MouseEvent): void {
      if (!menuOpenRef.current) {
        return
      }
      if (meWrap.current?.contains(event.target as Node) ?? false) {
        return
      }
      setMenu(false)
    }

    /**
     * Escape closes the account menu and hands the focus back to the avatar.
     *
     * **Only when it is open**, and that is what keeps this from trying to own the key: the keys page
     * binds Escape to "no" on a confirm that is open, and a shell that answered it as well would close
     * a menu nobody had opened while somebody was answering a question.
     */
    function onEscape(event: KeyboardEvent): void {
      if (event.key !== 'Escape' || !menuOpenRef.current) {
        return
      }
      setMenu(false)
      menuButton.current?.focus()
    }

    document.addEventListener('keydown', onSlash)
    document.addEventListener('keydown', onEscape)
    document.addEventListener('click', onDocumentClick)
    return () => {
      document.removeEventListener('keydown', onSlash)
      document.removeEventListener('keydown', onEscape)
      document.removeEventListener('click', onDocumentClick)
    }
  }, [])

  async function signOut(): Promise<void> {
    setSigningOut(true)
    const result = await logout()
    setSigningOut(false)
    if (result.ok) {
      // A full load, like every other navigation here: the server has just ended the session, and
      // reloading is the simplest way to be consistent with the cookies it replaced.
      go('/login')
      return
    }
    setBanner(codeMessage(result.code, result.message))
    setMenu(false)
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="topbar-left">
          {/* **The brand is the way out, not the console's own home** (design README §控制台演示
              › 顶栏的品牌回首页). It goes to the marketing site — the same address `/logout`
              returns to — so leaving the console is a real exit rather than a link that reloads
              the page you are on. The demo substitutes `?page=skills`; that href is the demo's
              fake routing and is not what the product does. */}
          <a className="brand" href={SITE_URL}>
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
            <span className="brand-name">SkillMaster</span>
          </a>
        </div>

        <form
          className="topbar-search"
          role="search"
          onSubmit={(event) => {
            event.preventDefault()
            searchSkills()
          }}
        >
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
            <line
              x1="15.4"
              y1="15.4"
              x2="20.6"
              y2="20.6"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
            />
          </svg>
          <label className="visually-hidden" htmlFor="topSearch">
            搜索 skill
          </label>
          <input
            id="topSearch"
            ref={searchBox}
            value={searching}
            type="search"
            placeholder="搜索你的 skill"
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => setSearching(event.target.value)}
          />
          <kbd>/</kbd>
        </form>

        <div className="topbar-right">
          {account ? (
            <div ref={meWrap} className="me-wrap">
              <button
                ref={menuButton}
                type="button"
                className="me"
                aria-haspopup="true"
                aria-expanded={menuOpen ? 'true' : 'false'}
                onClick={() => setMenu(!menuOpenRef.current)}
              >
                <span className="avatar" aria-hidden="true">
                  {initial}
                </span>
                <span className="me-name">{account.username}</span>
              </button>
              {/* Opened and closed with `hidden` and nothing else: two mechanisms for one menu is how
                  they come apart, and the global `[hidden]` rule is the one that hides things here. */}
              <div className="me-menu" hidden={!menuOpen}>
                {/* `data-sign-out` marks the one control here that navigates without being a link, so
                    the moment screen can ask about it before it does — the same hold it puts on every
                    link, and the design's own list of the exits it covers (README §那一刻). */}
                <button
                  type="button"
                  data-sign-out=""
                  disabled={signingOut}
                  onClick={() => {
                    void signOut()
                  }}
                >
                  {signingOut ? '退出中…' : '退出登录'}
                </button>
              </div>
            </div>
          ) : (
            /* **The one case the design has no picture of.** The demo's console is always signed in;
               a session that expired under a signed-in page is not, and the honest control for that
               is a way in rather than an avatar with nothing behind it. */
            <a href="/login">去登录</a>
          )}
        </div>
      </header>

      <nav className="sidenav" aria-label="主导航">
        {/* SKILL: where to go (发现), what I have (我的 skill), how to manage it (管理). 「管理」 no
            longer doubles as the name of the library — that was the most confusing part of the old
            grouping. */}
        <p className="nav-title">SKILL</p>
        <a
          className={onDiscover ? 'nav-item is-active' : 'nav-item'}
          href="/discover"
          aria-current={onDiscover ? 'page' : undefined}
        >
          <span className="nav-icon">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="8.4" stroke="currentColor" strokeWidth="1.8" />
              <path
                d="M15.4 8.6 13.6 13.6 8.6 15.4 10.4 10.4Z"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinejoin="round"
              />
            </svg>
          </span>
          <span className="nav-label">发现</span>
        </a>
        <a
          className={onSkills ? 'nav-item is-active' : 'nav-item'}
          href="/"
          aria-current={onSkills ? 'page' : undefined}
        >
          <span className="nav-icon">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <rect x="3" y="3" width="7.5" height="7.5" rx="1.8" stroke="currentColor" strokeWidth="1.8" />
              <rect x="13.5" y="3" width="7.5" height="7.5" rx="1.8" stroke="currentColor" strokeWidth="1.8" />
              <rect x="3" y="13.5" width="7.5" height="7.5" rx="1.8" stroke="currentColor" strokeWidth="1.8" />
              <rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.8" stroke="currentColor" strokeWidth="1.8" />
            </svg>
          </span>
          <span className="nav-label">我的 skill</span>
        </a>
        <NavGroup
          id="navManage"
          label="管理"
          icon={
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <line x1="4" y1="8" x2="6.2" y2="8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              <circle cx="8.4" cy="8" r="2.2" stroke="currentColor" strokeWidth="1.8" />
              <line x1="10.6" y1="8" x2="20" y2="8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              <line x1="4" y1="16" x2="13.6" y2="16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              <circle cx="15.8" cy="16" r="2.2" stroke="currentColor" strokeWidth="1.8" />
              <line x1="18" y1="16" x2="20" y2="16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
          }
          // **待上线排在标签前面**（v18 用户定）：它是每天要清的活单子，标签是偶尔改一次的配置。
          // **名字是状态，不是动作**：「上线」是按钮与预演句里的那个动词，导航项是一块地方。
          children={[
            { href: '/publish', label: '待上线', current: onPublish },
            { href: '/my-tags', label: '标签', current: onTags },
          ]}
        />

        <p className="nav-title">设置</p>
        <a
          className={onAccount ? 'nav-item is-active' : 'nav-item'}
          href="/account"
          aria-current={onAccount ? 'page' : undefined}
        >
          <span className="nav-icon">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="8.4" r="3.6" stroke="currentColor" strokeWidth="1.8" />
              <path
                d="M4.8 20c.9-3.5 3.8-5.5 7.2-5.5s6.3 2 7.2 5.5"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
              />
            </svg>
          </span>
          <span className="nav-label">账号</span>
        </a>

        <NavGroup
          id="navAccess"
          label="权限"
          icon={
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="7.6" cy="12" r="4.1" stroke="currentColor" strokeWidth="1.8" />
              <line x1="11.7" y1="12" x2="21" y2="12" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              <line x1="17.6" y1="12" x2="17.6" y2="16.2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              <line x1="14.2" y1="12" x2="14.2" y2="15" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
          }
          children={[
            { href: '/account/keys', label: 'API 密钥', current: onKeys },
            { href: '/account/sessions', label: '登录设备', current: onSessions },
          ]}
        />
      </nav>

      <main className="content">
        <FormBanner message={banner} />
        {children}
      </main>
    </div>
  )
}
