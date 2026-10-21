/**
 * The navigation beside the page.
 *
 * The item recipe is the reference product's, read off its `nav_item.scss` and the tokens that
 * stylesheet reads: a row 28px tall, 12px between the icon slot and the label, a radius, a hovered
 * fill, and a selected state that is a fill plus weight.
 *
 * **Whether an item is current is read off the address**, not remembered: there is no router here, a
 * link is a real link, and a reload lands on the same item. A skill is shown under 管理, the group it
 * lives in.
 */

import { useState } from 'react'
import type { ConsolePage } from '../demo'

const ACCESS_CHILDREN = [
  { page: 'keys', label: 'API 密钥' },
  { page: 'sessions', label: '登录设备' },
] as const

export default function Sidenav({ page }: { page: ConsolePage }) {
  const [accessOpen, setAccessOpen] = useState(false)
  /** Set for as long as the pointer is on the disclosure after a click, so a click that *closed* the
   *  children is not answered by the hover flyout opening on the spot — see `.nav-flyout`. */
  const [clickedAccess, setClickedAccess] = useState(false)

  const owning: string = page === 'skill' || page === 'editor' ? 'skills' : page
  const isActive = (name: string) => name === owning
  /* The two credentials pages live under 权限. The disclosure says so without taking the current
     styling itself: its label goes to the page's own ink and it carries `aria-current`. That is also
     the only signal a screen reader can hear — the child link is not drawn until it is opened. */
  const hasCurrentChild = owning === 'keys' || owning === 'sessions'

  const item = (name: 'keys' | 'sessions', label: string) => (
    <a
      className={'nav-item is-child' + (isActive(name) ? ' is-active' : '')}
      data-page={name}
      href={'?page=' + name}
      aria-current={isActive(name) ? 'page' : undefined}
    >
      <span className="nav-label">{label}</span>
    </a>
  )

  return (
    <nav className="sidenav" aria-label="主导航">
      <p className="nav-title">SKILL</p>
      <a
        className={'nav-item' + (isActive('skills') ? ' is-active' : '')}
        data-page="skills"
        href="?page=skills"
        aria-current={isActive('skills') ? 'page' : undefined}
      >
        <span className="nav-icon"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <rect x="3" y="3" width="7.5" height="7.5" rx="1.8" stroke="currentColor" strokeWidth="1.8" />
          <rect x="13.5" y="3" width="7.5" height="7.5" rx="1.8" stroke="currentColor" strokeWidth="1.8" />
          <rect x="3" y="13.5" width="7.5" height="7.5" rx="1.8" stroke="currentColor" strokeWidth="1.8" />
          <rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.8" stroke="currentColor" strokeWidth="1.8" />
        </svg></span>
        <span className="nav-label">管理</span>
      </a>

      <p className="nav-title">设置</p>
      <a
        className={'nav-item' + (isActive('account') ? ' is-active' : '')}
        data-page="account"
        href="?page=account"
        aria-current={isActive('account') ? 'page' : undefined}
      >
        <span className="nav-icon"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="12" cy="8.4" r="3.6" stroke="currentColor" strokeWidth="1.8" />
          <path d="M4.8 20c.9-3.5 3.8-5.5 7.2-5.5s6.3 2 7.2 5.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg></span>
        <span className="nav-label">账号</span>
      </a>

      {/* **Both behaviours, which is what the reference product does**: hovering shows a flyout
          beside the item for somebody who is still scanning, and clicking opens the children in
          place for somebody who has decided. The flyout is a *descendant* of the item on purpose —
          that is the whole hover bridge, with no timer and no invisible triangles.

          The open state is deliberately *not* in the address: the address is the page's state, and
          how far the navigation happens to be unfolded is the shell's business. */}
      <button
        type="button"
        className={'nav-item nav-disclosure' +
          (hasCurrentChild ? ' has-current-child' : '') +
          (clickedAccess ? ' is-clicked' : '')}
        id="navAccess"
        aria-expanded={accessOpen ? 'true' : 'false'}
        aria-controls="navAccessChildren"
        aria-current={hasCurrentChild ? 'true' : undefined}
        onClick={() => {
          setAccessOpen((open) => !open)
          setClickedAccess(true)
        }}
        onMouseLeave={() => setClickedAccess(false)}
      >
        <span className="nav-icon"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="7.6" cy="12" r="4.1" stroke="currentColor" strokeWidth="1.8" />
          <line x1="11.7" y1="12" x2="21" y2="12" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          <line x1="17.6" y1="12" x2="17.6" y2="16.2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          <line x1="14.2" y1="12" x2="14.2" y2="15" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg></span>
        <span className="nav-label">权限</span>
        <span className="nav-chevron"><svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
        </svg></span>

        {/* The flyout panel: rounded, one border, the subtle fill, the medium shadow, its own scroll —
            the same four things the reference product's `flyout_menu.vue` puts on it. The left padding
            is transparent and is the bridge across the sidebar's own gutter, so the pointer never
            leaves the item on its way to the panel. */}
        <div className="nav-flyout"><div className="nav-flyout-panel">
          {ACCESS_CHILDREN.map((child) => item(child.page, child.label))}
        </div></div>
      </button>

      <div className="nav-children" id="navAccessChildren" hidden={!accessOpen}>
        {ACCESS_CHILDREN.map((child) => item(child.page, child.label))}
      </div>
    </nav>
  )
}
