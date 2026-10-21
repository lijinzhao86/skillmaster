/**
 * A row's action menu: the same popover for a file and for an empty folder; only the items differ.
 *
 * It is painted into `document.body` rather than into the tree: it is a popover, not part of the
 * row's flow, and it has to escape the tree's own scroll box. Placement is fixed, flipped above
 * when it would leave the window, and Escape and an outside click both close it — with the focus
 * going back to the button that opened it.
 */

import { useEffect, useLayoutEffect, useRef } from 'react'
import { createPortal } from 'react-dom'

export interface EditorMenuItem {
  label: string
  onSelect: () => void
}

export default function EditorFileMenu({
  anchor,
  path,
  items,
  onClose,
}: {
  anchor: HTMLButtonElement
  path: string
  items: EditorMenuItem[]
  onClose: (refocus: boolean) => void
}) {
  const list = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    const panel = list.current
    if (!panel) return
    const box = anchor.getBoundingClientRect()
    const width = panel.getBoundingClientRect().width
    const height = panel.getBoundingClientRect().height
    panel.style.left = Math.round(
      Math.max(8, Math.min(box.right - width, window.innerWidth - width - 8)),
    ) + 'px'
    const below = box.bottom + 6
    panel.style.top = Math.round(
      below + height > window.innerHeight - 8 ? box.top - 6 - height : below,
    ) + 'px'
    panel.querySelector<HTMLButtonElement>('.menu-item')?.focus()
  }, [anchor, path])

  useEffect(() => {
    const outside = (event: MouseEvent) => {
      if (!list.current?.contains(event.target as Node) && !anchor.contains(event.target as Node)) {
        onClose(false)
      }
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      onClose(true)
    }
    document.addEventListener('mousedown', outside, true)
    document.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('mousedown', outside, true)
      document.removeEventListener('keydown', onKey, true)
    }
  }, [anchor, onClose])

  return createPortal(
    <div className="menu-list editor-file-pop" role="menu" aria-label={'操作：' + path} ref={list}>
      {items.map((item) => (
        <button
          key={item.label}
          type="button"
          className="menu-item"
          role="menuitem"
          tabIndex={-1}
          onClick={() => {
            onClose(false)
            item.onSelect()
          }}
        >
          {item.label}
        </button>
      ))}
    </div>,
    document.body,
  )
}
