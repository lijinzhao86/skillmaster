/**
 * **行右上角的「更多操作」。** It **only navigates**: every item is a link to that version's own
 * page (files / changes / actions). Pressing 上线 here does not publish — publishing still happens
 * on that page, after seeing what it does (ADR 0054).
 *
 * The same appearance and open/close rules as `.menu-select` (fixed position, Escape closes, a
 * click outside closes); the entries are links rather than options, so there is no check and no
 * `aria-selected`.
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { AtLeast, Version } from '../../fixtures/skill'
import {
  canEditDraft,
  diffBaseline,
  editorAddress,
  versionFilesAddress,
  versionSubAddress,
  versionTitleOf,
} from '../../fixtures/skill'

interface Item {
  label: string
  href: string
  view?: boolean
}

export default function VersionActionMenu({
  version,
  versions,
  atLeast,
  params,
}: {
  version: Version
  versions: Version[]
  atLeast: AtLeast
  params: URLSearchParams
}) {
  const [open, setOpen] = useState(false)
  const wrap = useRef<HTMLSpanElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const list = useRef<HTMLDivElement>(null)
  const links = useRef<(HTMLAnchorElement | null)[]>([])

  const browse = atLeast('browse')
  const displayName = versionTitleOf(version, browse)
  const items: Item[] = []
  const canReadFiles = browse && (!version.draft || atLeast('write'))
  if (canReadFiles) items.push({ label: '查看文件', href: versionFilesAddress(version, params), view: true })
  /* **编辑也是一个入口，不是动作。** 它把读者送到编辑页；保存与上线仍在那页上按下。 */
  if (version.draft && canEditDraft(version, atLeast)) {
    items.push({ label: '编辑', href: editorAddress(version, params), view: true })
  }
  if (browse && diffBaseline(versions, version, null, atLeast)) {
    items.push({ label: '查看改动', href: versionSubAddress(version, 'diff', params), view: true })
  }
  if (version.draft) {
    if (atLeast('maintain')) items.push({ label: '上线', href: versionSubAddress(version, 'actions', params) })
    if (canEditDraft(version, atLeast)) items.push({ label: '丢弃', href: versionSubAddress(version, 'actions', params) })
  } else if (version.state === 'revoked') {
    if (atLeast('maintain')) items.push({ label: '重新上架', href: versionSubAddress(version, 'actions', params) })
  } else if (version.live) {
    if (atLeast('maintain')) items.push({ label: '回滚', href: versionSubAddress(version, 'actions', params) })
  } else if (atLeast('maintain')) {
    items.push({ label: '上线', href: versionSubAddress(version, 'actions', params) })
    items.push({ label: '下架', href: versionSubAddress(version, 'actions', params) })
  }
  const close = (refocus: boolean) => {
    setOpen(false)
    if (refocus) trigger.current?.focus()
  }

  const position = () => {
    const button = trigger.current
    const panel = list.current
    if (!button || !panel) return
    const box = button.getBoundingClientRect()
    const width = panel.getBoundingClientRect().width
    panel.style.left = Math.round(
      Math.min(Math.max(8, box.right - width), Math.max(8, window.innerWidth - width - 8)),
    ) + 'px'
    const height = panel.getBoundingClientRect().height
    const below = box.bottom + 6
    panel.style.top = Math.round(below + height > window.innerHeight - 8 ? box.top - 6 - height : below) + 'px'
  }

  useLayoutEffect(() => {
    if (!open) return
    position()
    links.current[0]?.focus()
  }, [open])

  useEffect(() => {
    if (!open) return
    const outside = (event: MouseEvent) => {
      if (!wrap.current?.contains(event.target as Node) && !list.current?.contains(event.target as Node)) {
        close(false)
      }
    }
    /* **A long list scrolls inside its own panel.** `scroll` does not bubble, but the capture phase
       still reaches `window`, so a wheel over `.menu-list` would close the menu on its first pixel —
       and everything below the fold would be unreachable by pointer. Scrolling the page under an
       open menu is the thing to close for; scrolling the menu is not. */
    const viewport = (event: Event) => {
      if (event.target instanceof Node && list.current?.contains(event.target)) return
      close(false)
    }
    document.addEventListener('mousedown', outside, true)
    window.addEventListener('scroll', viewport, true)
    const onResize = () => close(false)
    window.addEventListener('resize', onResize)
    return () => {
      document.removeEventListener('mousedown', outside, true)
      window.removeEventListener('scroll', viewport, true)
      window.removeEventListener('resize', onResize)
    }
  }, [open])

  /* **已丢弃没有入口菜单**（PRD：没有入口菜单，按钉打得到只读单版页）。整行仍是入口。 */
  if (version.state === 'discarded' || items.length === 0) return <span className="version-menu"></span>

  const groups = [items.filter((item) => item.view), items.filter((item) => !item.view)]

  return (
    <span className="version-menu" ref={wrap}>
      <button
        type="button"
        className="version-menu-trigger"
        ref={trigger}
        aria-haspopup="menu"
        aria-expanded={open ? 'true' : 'false'}
        aria-label={'更多操作：' + displayName}
        title="更多操作"
        onClick={() => {
          if (open) close(true)
          else setOpen(true)
        }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault()
            setOpen(true)
          }
        }}
      >
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="12" cy="5" r="1.7" fill="currentColor" />
          <circle cx="12" cy="12" r="1.7" fill="currentColor" />
          <circle cx="12" cy="19" r="1.7" fill="currentColor" />
        </svg>
      </button>

      <div
        className="menu-list"
        role="menu"
        aria-label={'更多操作：' + displayName}
        ref={list}
        hidden={!open}
        onKeyDown={(event) => {
          const index = links.current.findIndex((link) => link === document.activeElement)
          if (event.key === 'ArrowDown') {
            event.preventDefault()
            links.current[Math.min(index + 1, links.current.length - 1)]?.focus()
          } else if (event.key === 'ArrowUp') {
            event.preventDefault()
            if (index <= 0) close(true)
            else links.current[index - 1]?.focus()
          } else if (event.key === 'Home') {
            event.preventDefault()
            links.current[0]?.focus()
          } else if (event.key === 'End') {
            event.preventDefault()
            links.current[links.current.length - 1]?.focus()
          } else if (event.key === 'Escape') {
            event.preventDefault()
            close(true)
          } else if (event.key === 'Tab') {
            close(false)
          }
        }}
      >
        {groups.map((group, groupIndex) => {
          if (group.length === 0) return null
          return (
            <div className="menu-group" role="group" key={groupIndex}>
              {group.map((item) => (
                <a
                  key={item.label}
                  className="menu-item"
                  role="menuitem"
                  tabIndex={-1}
                  href={item.href}
                  ref={(node) => {
                    links.current[items.indexOf(item)] = node
                  }}
                >
                  {item.label}
                </a>
              ))}
            </div>
          )
        })}
      </div>
    </span>
  )
}
