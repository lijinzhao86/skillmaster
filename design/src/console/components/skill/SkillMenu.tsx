/**
 * A select, drawn by the page — the same control as `components/MenuSelect`, used by every picker
 * on the skill page.
 *
 * **One difference is deliberate: the search box listens to `input`, not `change`.** React's
 * tracked `change` event ignores a value assigned by the page's own tooling (the demo's state
 * checks set `input.value` directly), while the native `input` event is the event that actually
 * happened. A person typing gets the same behaviour either way.
 *
 * `ariaLabelledBy` exists because 申请档位's visible label is attached to the control with
 * `aria-labelledby`: `<label for>` only knows form controls, and the trigger is a button.
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { flushSync } from 'react-dom'

export interface SkillMenuOption {
  value: string
  label: string
  number?: string
  tag?: string
  tagTone?: string
  meta?: string
  disabled?: boolean
}

export interface SkillMenuGroup {
  label?: string
  options: SkillMenuOption[]
}

export interface SkillMenuConfig {
  ariaLabel?: string
  ariaLabelledBy?: string
  triggerText?: string
  triggerClass?: string
  disabled?: boolean
  search?: boolean
  searchPlaceholder?: string
  searchLabel?: string
  emptyText?: string
  onPick?: (value: string) => void
}

const CHECK = (
  <svg className="menu-check" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path d="M5 12.4l4.4 4.4L19 7.6" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)

export default function SkillMenu({
  groups,
  value,
  config = {},
}: {
  groups: SkillMenuGroup[]
  value: string
  config?: SkillMenuConfig
}) {
  const [current, setCurrent] = useState(value)
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const wrap = useRef<HTMLSpanElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const list = useRef<HTMLDivElement>(null)
  const search = useRef<HTMLInputElement>(null)
  const itemRefs = useRef<Map<string, HTMLButtonElement>>(new Map())

  const options = groups.flatMap((group) => group.options)
  const searchable = !!config.search
  const labelFor = (wanted: string) => options.find((option) => option.value === wanted)?.label ?? wanted
  const triggerText = config.triggerText ?? labelFor(current)

  useEffect(() => setCurrent(value), [value])

  const shown = (option: SkillMenuOption) =>
    query.trim() === '' ||
    (option.label + ' ' + (option.number || '')).toLowerCase().includes(query.trim().toLowerCase())
  const visibleItems = () => options.filter((option) => shown(option) && !option.disabled)

  const close = (refocus: boolean) => {
    setOpen(false)
    if (refocus) trigger.current?.focus()
  }

  const choose = (next: string) => {
    close(true)
    setCurrent(next)
    config.onPick?.(next)
  }

  /* Position once the list is in the document: below the trigger, or above it when there is no room.
     The width is the trigger's own for a plain list — the entries belong to the control — and the
     searchable dialog keeps the width the stylesheet gives it, clamped to the viewport. */
  useLayoutEffect(() => {
    if (!open) return
    const button = trigger.current
    const panel = list.current
    if (!button || !panel) return
    const box = button.getBoundingClientRect()
    panel.style.fontSize = getComputedStyle(button).fontSize
    panel.style.minWidth = Math.round(searchable ? 0 : box.width) + 'px'
    const width = panel.getBoundingClientRect().width
    panel.style.left = Math.round(
      Math.min(Math.max(8, box.left), Math.max(8, window.innerWidth - width - 8)),
    ) + 'px'
    const height = panel.getBoundingClientRect().height
    const below = box.bottom + 6
    panel.style.top = Math.round(below + height > window.innerHeight - 8 ? box.top - 6 - height : below) + 'px'

    if (searchable) search.current?.focus()
    else (itemRefs.current.get(current) ?? itemRefs.current.get(visibleItems()[0]?.value ?? ''))?.focus()
  }, [open, current, searchable])

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

  const focusAt = (next: number) => {
    const items = visibleItems()
    const target = items[Math.min(Math.max(next, 0), items.length - 1)]
    if (target) itemRefs.current.get(target.value)?.focus()
  }

  const onListKey = (event: KeyboardEvent) => {
    const items = visibleItems()
    const index = items.findIndex((option) => itemRefs.current.get(option.value) === document.activeElement)
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      focusAt(Math.min(index + 1, items.length - 1))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      if (index === 0 && searchable) search.current?.focus()
      else focusAt(Math.max(index - 1, 0))
    } else if (event.key === 'Home') {
      event.preventDefault()
      focusAt(0)
    } else if (event.key === 'End') {
      event.preventDefault()
      focusAt(items.length - 1)
    } else if (event.key === 'Escape') {
      event.preventDefault()
      close(true)
    } else if (event.key === 'Tab') {
      close(false)
    }
  }

  const entry = (option: SkillMenuOption) => (
    <button
      key={option.value}
      type="button"
      className="menu-item"
      role="option"
      data-value={option.value}
      data-name={(option.label + ' ' + (option.number || '')).toLowerCase()}
      aria-selected={option.value === current ? 'true' : 'false'}
      disabled={option.disabled}
      aria-disabled={option.disabled ? 'true' : undefined}
      hidden={!shown(option)}
      title={option.label + (option.number ? ' ' + option.number : '')}
      ref={(node) => {
        if (node) itemRefs.current.set(option.value, node)
        else itemRefs.current.delete(option.value)
      }}
      onClick={() => choose(option.value)}
    >
      {CHECK}
      <span className="menu-label">{option.label}</span>
      {option.number && <>{' '}<span className="menu-number">{option.number}</span></>}
      {(option.tag || option.meta) && ' '}
      {option.tag && <span className={'menu-tag' + (option.tagTone ? ' is-' + option.tagTone : '')}>{option.tag}</span>}
      {option.meta && <>{option.tag && ' '}<span className="menu-tag is-meta">{option.meta}</span></>}
    </button>
  )

  const groupNodes = () => groups.map((group, index) => (
    <div
      className="menu-group"
      key={index}
      role={group.label ? 'group' : 'presentation'}
      aria-label={group.label}
      hidden={group.options.every((option) => !shown(option))}
    >
      {group.label && <div className="menu-group-label">{group.label}</div>}
      {group.options.map(entry)}
    </div>
  ))

  return (
    <span className="menu" ref={wrap}>
      <button
        type="button"
        className={'control menu-trigger' + (config.triggerClass ? ' ' + config.triggerClass : '')}
        ref={trigger}
        aria-haspopup={searchable ? 'dialog' : 'listbox'}
        aria-expanded={open ? 'true' : 'false'}
        aria-label={config.ariaLabel}
        aria-labelledby={config.ariaLabelledBy}
        data-value={current}
        title={triggerText}
        disabled={config.disabled}
        onClick={() => {
          if (open) close(true)
          else {
            setQuery('')
            setOpen(true)
          }
        }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault()
            setQuery('')
            setOpen(true)
          }
        }}
      >
        <span className="menu-trigger-text">{triggerText}</span>
      </button>

      <div
        className={'menu-list' + (searchable ? ' has-search' : '')}
        ref={list}
        hidden={!open}
        role={searchable ? 'dialog' : 'listbox'}
        aria-label={searchable ? config.ariaLabel || '筛选' : config.ariaLabel}
        onKeyDown={searchable ? undefined : onListKey}
      >
        {searchable && (
          <div className="menu-search">
            <input
              type="search"
              className="menu-search-input"
              ref={search}
              placeholder={config.searchPlaceholder || '按名称筛选'}
              aria-label={config.searchLabel || '按名称筛选'}
              autoComplete="off"
              value={query}
              onInput={(event) => {
                const value = event.currentTarget.value
                /* The demo's checks assign `value` directly; commit the filter before the event returns. */
                flushSync(() => setQuery(value))
              }}
              onChange={(event) => setQuery(event.currentTarget.value)}
              onKeyDown={(event) => {
                if (event.key === 'ArrowDown') {
                  event.preventDefault()
                  visibleItems()[0] && itemRefs.current.get(visibleItems()[0].value)?.focus()
                } else if (event.key === 'Enter') {
                  event.preventDefault()
                  const target = visibleItems()[0]
                  if (target) choose(target.value)
                } else if (event.key === 'Escape') {
                  event.preventDefault()
                  close(true)
                } else if (event.key === 'Tab') {
                  close(false)
                }
              }}
            />
          </div>
        )}

        {searchable ? (
          <div className="menu-box" role="listbox" aria-label={config.ariaLabel} onKeyDown={onListKey}>
            {groupNodes()}
            <div className="menu-empty" hidden={visibleItems().length > 0}>
              {config.emptyText || '没有匹配项。'}
            </div>
          </div>
        ) : (
          groupNodes()
        )}
      </div>
    </span>
  )
}
