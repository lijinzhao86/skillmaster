import { useState } from 'react'
import type { ReactNode } from 'react'

/**
 * A navigation item with one level of children.
 *
 * Both behaviours, which is what the reference product does: hovering shows a flyout beside the item
 * for somebody still scanning, and clicking unfolds the children in place for somebody who has
 * decided. It starts open on the page that lives inside it, so the reader can see where they are.
 *
 * **The panel is the item's sibling inside a positioned wrapper**, where the design nests it inside
 * the button: same hover bridge — the wrapper is the hovered thing, so the pointer never leaves it on
 * the way to the panel — but a `<button>` may not contain a link, and the flyout is one.
 */
export interface NavChild {
  href: string
  label: string
  current: boolean
}

export default function NavGroup({
  id,
  label,
  icon,
  children,
}: {
  id: string
  label: string
  icon: ReactNode
  children: NavChild[]
}) {
  const current = children.some((child) => child.current)
  const [open, setOpen] = useState(current)
  /** After a click that closed it, the hover flyout must not open on the spot. */
  const [clicked, setClicked] = useState(false)

  const item = (child: NavChild) => (
    <a
      key={child.href}
      className={'nav-item is-child' + (child.current ? ' is-active' : '')}
      href={child.href}
      aria-current={child.current ? 'page' : undefined}
    >
      <span className="nav-label">{child.label}</span>
    </a>
  )

  return (
    <>
      <div
        className={clicked ? 'nav-disclosure-wrap is-clicked' : 'nav-disclosure-wrap'}
        onMouseLeave={() => setClicked(false)}
      >
        <button
          type="button"
          className={
            current && !open ? 'nav-item nav-disclosure has-current-child' : 'nav-item nav-disclosure'
          }
          id={id}
          aria-controls={id + 'Children'}
          aria-expanded={open ? 'true' : 'false'}
          aria-current={current ? 'true' : undefined}
          onClick={() => {
            setOpen(!open)
            setClicked(true)
          }}
        >
          <span className="nav-icon">{icon}</span>
          <span className="nav-label">{label}</span>
          <span className="nav-chevron">
            <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path
                d="M6 4l4 4-4 4"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </span>
        </button>

        <div className="nav-flyout">
          <div className="nav-flyout-panel">{children.map(item)}</div>
        </div>
      </div>

      <div id={id + 'Children'} className="nav-children" hidden={!open}>
        {children.map(item)}
      </div>
    </>
  )
}
