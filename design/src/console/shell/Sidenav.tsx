/**
 * The navigation beside the page.
 *
 * The item recipe is the reference product's, read off its `nav_item.scss` and the tokens that
 * stylesheet reads: a row 28px tall, 12px between the icon slot and the label, a radius, a hovered
 * fill, and a selected state that is a fill plus weight.
 *
 * **Whether an item is current is read off the address**, not remembered: there is no router here, a
 * link is a real link, and a reload lands on the same item. A skill is shown under 我的 skill.
 *
 * **两组都一样：一条一级项 + 一个二级项。** 「管理」装着管理类的页面（今天是「标签」，以后都放这里），
 * 与「权限」装着 API 密钥与登录设备是同一种形状：悬停出侧边的浮层、点击在原位展开；**当前页在
 * 里面时它默认是展开的**，这样人一眼能看见自己在哪一格，而不是只看见一个被点亮的父项。
 */

import { useState } from 'react'
import type { ReactNode } from 'react'
import type { ConsolePage } from '../demo'

type NavChild = { page: ConsolePage; label: string }

const ACCESS_CHILDREN: NavChild[] = [
  { page: 'keys', label: 'API 密钥' },
  { page: 'sessions', label: '登录设备' },
]

const MANAGE_CHILDREN: NavChild[] = [
  { page: 'my-tags', label: '标签' },
]

/** 一条可以展开的导航项：图标 + 名字 + 折角，悬停出浮层、点击原位展开。 */
function NavGroup({
  id,
  label,
  icon,
  children,
  owning,
}: {
  id: string
  label: string
  icon: ReactNode
  children: NavChild[]
  /** 当前页的标识：在这一组的哪个子项上。 */
  owning: string
}) {
  const current = children.some((child) => child.page === owning)
  const [open, setOpen] = useState(current)
  /** 点过之后、指针还在这一条上时，hover 浮层不要再开——那一关是点击关的。 */
  const [clicked, setClicked] = useState(false)

  const item = (child: NavChild) => (
    <a
      className={'nav-item is-child' + (child.page === owning ? ' is-active' : '')}
      data-page={child.page}
      href={'?page=' + child.page}
      aria-current={child.page === owning ? 'page' : undefined}
    >
      <span className="nav-label">{child.label}</span>
    </a>
  )

  return (
    <>
      <button
        type="button"
        className={'nav-item nav-disclosure' +
          (current && !open ? ' has-current-child' : '') +
          (clicked ? ' is-clicked' : '')}
        id={id}
        aria-expanded={open ? 'true' : 'false'}
        aria-controls={id + 'Children'}
        aria-current={current ? 'true' : undefined}
        onClick={() => {
          setOpen((value) => !value)
          setClicked(true)
        }}
        onMouseLeave={() => setClicked(false)}
      >
        <span className="nav-icon">{icon}</span>
        <span className="nav-label">{label}</span>
        <span className="nav-chevron"><svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
        </svg></span>

        {/* The flyout panel: rounded, one border, the subtle fill, the medium shadow, its own scroll —
            the same four things the reference product's `flyout_menu.vue` puts on it. The left padding
            is transparent and is the bridge across the sidebar's own gutter, so the pointer never
            leaves the item on its way to the panel. */}
        <div className="nav-flyout"><div className="nav-flyout-panel">
          {children.map(item)}
        </div></div>
      </button>

      <div className="nav-children" id={id + 'Children'} hidden={!open}>
        {children.map(item)}
      </div>
    </>
  )
}

export default function Sidenav({ page }: { page: ConsolePage }) {
  const owning: string = page === 'skill' || page === 'editor' ? 'skills' : page
  const isActive = (name: string) => name === owning

  return (
    <nav className="sidenav" aria-label="主导航">
      {/* **SKILL 组：去哪儿找（发现）、我这边有什么（我的 skill）、怎么管（管理）。**
          「管理」不再兼任「我的 skill」的名字——那正是这一组原来最容易混的一处。 */}
      <p className="nav-title">SKILL</p>
      <a
        className={'nav-item' + (isActive('discover') ? ' is-active' : '')}
        data-page="discover"
        href="?page=discover"
        aria-current={isActive('discover') ? 'page' : undefined}
      >
        <span className="nav-icon"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="12" cy="12" r="8.4" stroke="currentColor" strokeWidth="1.8" />
          <path d="M15.4 8.6 13.6 13.6 8.6 15.4 10.4 10.4Z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
        </svg></span>
        <span className="nav-label">发现</span>
      </a>
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
        <span className="nav-label">我的 skill</span>
      </a>
      <NavGroup
        id="navManage"
        label="管理"
        icon={<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <line x1="4" y1="8" x2="6.2" y2="8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          <circle cx="8.4" cy="8" r="2.2" stroke="currentColor" strokeWidth="1.8" />
          <line x1="10.6" y1="8" x2="20" y2="8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          <line x1="4" y1="16" x2="13.6" y2="16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          <circle cx="15.8" cy="16" r="2.2" stroke="currentColor" strokeWidth="1.8" />
          <line x1="18" y1="16" x2="20" y2="16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>}
        children={MANAGE_CHILDREN}
        owning={owning}
      />

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
      <NavGroup
        id="navAccess"
        label="权限"
        icon={<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="7.6" cy="12" r="4.1" stroke="currentColor" strokeWidth="1.8" />
          <line x1="11.7" y1="12" x2="21" y2="12" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          <line x1="17.6" y1="12" x2="17.6" y2="16.2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          <line x1="14.2" y1="12" x2="14.2" y2="15" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>}
        children={ACCESS_CHILDREN}
        owning={owning}
      />
    </nav>
  )
}
