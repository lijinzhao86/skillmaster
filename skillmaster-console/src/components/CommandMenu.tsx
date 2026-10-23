import { useEffect, useImperativeHandle, useRef, useState } from 'react'
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, ReactNode, RefObject } from 'react'

/**
 * The command menu the design draws in three places (v9): the version row's 「更多操作」, and the
 * two 「新建 ▾」 triggers.
 *
 * **One behaviour, extracted rather than copied.** The row menu already had positioning, keyboard
 * handling and the outside-click/scroll/resize rules; a second and third copy would be three places
 * for the arrow keys to drift. The trigger is a render prop because its face differs — an icon button
 * on a row, a labelled primary button in a toolbar — while everything behind it is the same question.
 *
 * **Positioned fixed, from the trigger's box.** The list can open upward when the viewport's bottom
 * is close (the row menu near the end of a long list), and it never leaves the viewport's sides.
 * Scroll always closes it: a menu that moves under the pointer while the page scrolls is a menu
 * somebody clicks the wrong thing in. Resize follows the design's two rules — the row menu closes,
 * the 「新建 ▾」 menus reposition — which is what the `resize` prop selects.
 *
 * **What changed in the port.** Vue's `defineExpose` becomes a `controlRef` prop: the page that owns
 * the trigger needs one method on it — `focusTrigger`, the way back after a sheet the menu opened has
 * closed. The slot becomes `children` as a function, because the trigger has to be told whether the
 * menu is open (for `aria-expanded`) and given the toggle.
 */
export interface CommandMenuItem {
  label: string
  /** A navigation entry. */
  href?: string
  /** A command entry: opening a dialog, say. Exactly one of `href` / `onSelect` is set. */
  onSelect?: () => void
}

export interface CommandMenuHandle {
  /**
   * Puts focus back on the control this menu hangs from — the way out of an open menu, and the way
   * back from a sheet it opened.
   */
  focusTrigger: () => void
}

export interface CommandMenuProps {
  /** One array per group; groups are drawn as separate blocks, as the row menu's are. */
  groups: CommandMenuItem[][]
  /** Read aloud for the list. Named `menuLabel` so it cannot be mistaken for a plain attribute. */
  menuLabel: string
  /**
   * What a window resize does to an open menu.
   *
   * The design specifies both, for the two kinds of menu it draws. The row menu closes: it belongs
   * to one line of a long list, and the row may have moved. The 「新建 ▾」 menus reposition: their
   * anchor is a fixed button in the page's head, and the design's own comment says so
   * (「Resizing repositions, scrolling closes」).
   */
  resize?: 'close' | 'reposition'
  controlRef?: RefObject<CommandMenuHandle | null>
  children: (state: { open: boolean; toggle: () => void }) => ReactNode
}

export default function CommandMenu({
  groups,
  menuLabel,
  resize,
  controlRef,
  children,
}: CommandMenuProps) {
  const [open, setOpen] = useState(false)
  /** The open flag the listeners read. They are attached once per opening, so they need the value
   *  now rather than the one captured when they were created. */
  const openRef = useRef(false)
  const anchor = useRef<HTMLSpanElement | null>(null)
  const list = useRef<HTMLDivElement | null>(null)
  const [menuStyle, setMenuStyle] = useState<CSSProperties>({})
  const links = useRef<Array<HTMLElement | null>>([])

  function position(): void {
    const box = anchor.current
    const menu = list.current
    if (box === null || menu === null) {
      return
    }
    const rect = box.getBoundingClientRect()
    const width = menu.getBoundingClientRect().width
    const height = menu.getBoundingClientRect().height
    setMenuStyle({
      left: `${Math.round(
        Math.min(Math.max(8, rect.right - width), Math.max(8, window.innerWidth - width - 8)),
      )}px`,
      top: `${Math.round(
        rect.bottom + 6 + height > window.innerHeight - 8 ? rect.top - 6 - height : rect.bottom + 6,
      )}px`,
    })
  }

  function onOutside(event: MouseEvent): void {
    const target = event.target as Node
    if (anchor.current?.contains(target) !== true && list.current?.contains(target) !== true) {
      close(false)
    }
  }

  function close(refocus: boolean): void {
    if (!openRef.current) {
      return
    }
    openRef.current = false
    setOpen(false)
    document.removeEventListener('mousedown', listeners.outside, true)
    window.removeEventListener('scroll', listeners.viewport, true)
    window.removeEventListener('resize', listeners.resize)
    if (refocus) {
      focusTrigger()
    }
  }

  function closeOnViewportChange(): void {
    close(false)
  }

  function onResize(): void {
    if (resize === 'reposition') {
      position()
    } else {
      close(false)
    }
  }


  // **One identity for the three listeners.** They are added when the menu opens and removed when it
  // closes or the component unmounts. A function declared in the body is a different object on every
  // render, so a removal from a later render would not match the addition — the listeners would pile
  // up, one set per open (Vue's setup-scope functions stayed the same object, which is why the
  // original could pair them directly). These wrappers are made once and call the latest handlers.
  const latestHandlers = useRef({ onOutside, closeOnViewportChange, onResize })
  latestHandlers.current = { onOutside, closeOnViewportChange, onResize }
  const listeners = useRef({
    outside: (event: MouseEvent) => latestHandlers.current.onOutside(event),
    viewport: () => latestHandlers.current.closeOnViewportChange(),
    resize: () => latestHandlers.current.onResize(),
  }).current
  function focusTrigger(): void {
    anchor.current?.querySelector<HTMLElement>('button, a')?.focus()
  }

  useImperativeHandle(controlRef, () => ({ focusTrigger }), [])

  function show(): void {
    close(false)
    openRef.current = true
    setOpen(true)
    document.addEventListener('mousedown', listeners.outside, true)
    window.addEventListener('scroll', listeners.viewport, true)
    window.addEventListener('resize', listeners.resize)
    requestAnimationFrame(() => {
      // The menu may already be closed again by the time the frame arrives (an item picked
      // synchronously, or a second press). Focusing then would pull focus into a hidden list —
      // and, for a sheet the item opened, out of the button it came from.
      if (!openRef.current) {
        return
      }
      position()
      links.current[0]?.focus()
    })
  }

  function toggle(): void {
    if (openRef.current) {
      close(true)
    } else {
      show()
    }
  }

  function onTriggerKeydown(event: ReactKeyboardEvent): void {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      show()
    } else if (event.key === 'Escape') {
      close(true)
    }
  }

  function onMenuKeydown(event: ReactKeyboardEvent): void {
    const current = links.current
    const index = current.indexOf(document.activeElement as HTMLElement)
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      current[Math.min(index + 1, current.length - 1)]?.focus()
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      if (index <= 0) {
        close(true)
      } else {
        current[index - 1]?.focus()
      }
    } else if (event.key === 'Home') {
      event.preventDefault()
      current[0]?.focus()
    } else if (event.key === 'End') {
      event.preventDefault()
      current.at(-1)?.focus()
    } else if (event.key === 'Escape') {
      event.preventDefault()
      close(true)
    } else if (event.key === 'Tab') {
      close(false)
    }
  }

  function nextIndex(groupIndex: number, itemIndex: number): number {
    let index = itemIndex
    for (let group = 0; group < groupIndex; group++) {
      index += groups[group]?.length ?? 0
    }
    return index
  }

  function pick(item: CommandMenuItem): void {
    close(false)
    item.onSelect?.()
  }

  useEffect(
    () => () => {
      close(false)
      // `close` reads the open flag, so a menu that is already closed still has to have its
      // listeners taken down — they are only ever attached while it is open.
      document.removeEventListener('mousedown', listeners.outside, true)
      window.removeEventListener('scroll', listeners.viewport, true)
      window.removeEventListener('resize', listeners.resize)
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  )

  return (
    <span ref={anchor} className="menu-anchor" onKeyDown={onTriggerKeydown}>
      {children({ open, toggle })}
      {/* Always in the DOM, hidden while closed — the same rule the row menu kept and `MenuSelect`
          states outright: "what may I choose" is readable to a screen reader (and to the checks)
          rather than existing only while the menu is open.

          **`stopPropagation` on the keydown is load-bearing.** The list is inside the anchor span, so
          without it every arrow key also ran the trigger's handler, which reopened the menu on its
          first item — the second item could not be reached by keyboard at all. */}
      <div
        ref={list}
        className="menu-list"
        role="menu"
        hidden={!open}
        aria-label={menuLabel}
        style={menuStyle}
        onKeyDown={(event) => {
          event.stopPropagation()
          onMenuKeydown(event)
        }}
      >
        {groups.map((group, groupIndex) =>
          // An empty group is not drawn at all — the design's rule, and the reason it is a *rule*:
          // `.menu-group + .menu-group` adds a hairline above the second one, so a group with nothing
          // in it would draw a rule over nothing.
          group.length === 0 ? null : (
          <div key={groupIndex} className="menu-group" role="group">
            {group.map((item) => {
              const index = nextIndex(groupIndex, group.indexOf(item))
              return item.href !== undefined ? (
                <a
                  key={item.label}
                  ref={(element) => {
                    if (element !== null) {
                      links.current[index] = element
                    }
                  }}
                  className="menu-item"
                  role="menuitem"
                  tabIndex={-1}
                  href={item.href}
                >
                  {item.label}
                </a>
              ) : (
                <button
                  key={item.label}
                  ref={(element) => {
                    if (element !== null) {
                      links.current[index] = element
                    }
                  }}
                  type="button"
                  className="menu-item"
                  role="menuitem"
                  tabIndex={-1}
                  onClick={() => pick(item)}
                >
                  {item.label}
                </button>
              )
            })}
          </div>
          ),
        )}
      </div>
    </span>
  )
}
