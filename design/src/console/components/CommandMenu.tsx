/**
 * The menu behind 新建 skill and 新建版本: one action, two sources.
 *
 * 在线编辑 starts from a version the editor knows how to pick; 导入压缩包 starts from a file on
 * disk. They are menu items rather than sibling buttons because two buttons say "two kinds of
 * thing", and these are one kind with two origins.
 *
 * It is painted into `document.body` rather than into the page: it is a popover, not part of the
 * page's flow, and the old demo appended it there for the same reason.
 *
 * **Resizing repositions, scrolling closes.** A menu anchored to a button stays where the button is
 * when the window changes size; when the page scrolls the anchor moves and a fixed menu would float
 * away from it — that is when it closes.
 */

import { useEffect, useLayoutEffect, useRef } from 'react'
import { createPortal } from 'react-dom'

export interface CommandItem {
  text: string
  href?: string
  onSelect?: (trigger: HTMLElement) => void
}

export default function CommandMenu({
  open,
  trigger,
  label,
  items,
  onClose,
}: {
  open: boolean
  trigger: HTMLElement | null
  label: string
  items: CommandItem[]
  onClose: (refocus: boolean) => void
}) {
  const list = useRef<HTMLDivElement>(null)
  const nodes = useRef<HTMLElement[]>([])

  const position = () => {
    const panel = list.current
    if (!panel || !trigger) return
    const box = trigger.getBoundingClientRect()
    const width = panel.getBoundingClientRect().width
    panel.style.left = Math.round(Math.max(8, Math.min(box.right - width, window.innerWidth - width - 8))) + 'px'
    const height = panel.getBoundingClientRect().height
    const below = box.bottom + 6
    panel.style.top = Math.round(below + height > window.innerHeight - 8 ? box.top - 6 - height : below) + 'px'
  }

  useLayoutEffect(() => {
    if (!open) return
    position()
    nodes.current[0]?.focus()
  }, [open, trigger, label])

  useEffect(() => {
    if (!open) return
    const outside = (event: MouseEvent) => {
      const target = event.target as Node
      if (!list.current?.contains(target) && !trigger?.contains(target)) onClose(false)
    }
    const viewport = () => position()
    const scrolled = () => onClose(false)
    document.addEventListener('mousedown', outside, true)
    window.addEventListener('scroll', scrolled, true)
    window.addEventListener('resize', viewport)
    return () => {
      document.removeEventListener('mousedown', outside, true)
      window.removeEventListener('scroll', scrolled, true)
      window.removeEventListener('resize', viewport)
    }
  }, [open, trigger])

  if (!open) return null

  return createPortal(
    <div
      className="menu-list"
      role="menu"
      aria-label={label}
      ref={list}
      onKeyDown={(event) => {
        const index = nodes.current.indexOf(document.activeElement as HTMLElement)
        if (event.key === 'ArrowDown') {
          event.preventDefault()
          nodes.current[Math.min(index + 1, nodes.current.length - 1)]?.focus()
        } else if (event.key === 'ArrowUp') {
          event.preventDefault()
          if (index <= 0) onClose(true)
          else nodes.current[index - 1]?.focus()
        } else if (event.key === 'Escape') {
          event.preventDefault()
          onClose(true)
        } else if (event.key === 'Tab') {
          onClose(false)
        }
      }}
    >
      {items.map((item) => {
        const common = {
          className: 'menu-item',
          role: 'menuitem' as const,
          tabIndex: -1,
          ref: (node: HTMLElement | null) => {
            if (node) nodes.current[items.indexOf(item)] = node
          },
        }
        if (item.href) {
          return <a key={item.text} {...common} href={item.href}>{item.text}</a>
        }
        return (
          <button
            key={item.text}
            {...common}
            type="button"
            onClick={() => {
              onClose(false)
              if (trigger) item.onSelect?.(trigger)
            }}
          >
            {item.text}
          </button>
        )
      })}
    </div>,
    document.body,
  )
}
