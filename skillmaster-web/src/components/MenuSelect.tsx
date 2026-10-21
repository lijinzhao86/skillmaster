import { useEffect, useRef, useState } from 'react'
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent } from 'react'

/**
 * The page's own dropdown: a button and a list the page draws itself (design's `menuSelect`).
 *
 * **Not a native `<select>`.** Its popup is drawn by the system — dark ground, system highlight — and
 * `<option>` cannot be styled, while this page is white paper layered on white paper. The button
 * wears the same border, arrow and size as `select.control`, and the list is a small white card with
 * a check on the chosen row.
 *
 * **The list is `position: fixed`.** The cards around these controls clip their rows (`overflow:
 * hidden`, for the rounded corners), so an absolutely positioned list would be cut off; fixed
 * positioning is relative to the viewport and escapes that clip. Where it goes is worked out from the
 * trigger's rectangle when it opens, and it flips above the trigger when there is no room below.
 *
 * **The options are always in the DOM**, hidden — so "what may I choose" is readable by a screen
 * reader and by the design's checks, rather than existing only while the menu is open.
 *
 * Extracted from the rung picker in v6: the rung picker, the version pickers and the comparison
 * picker are one mechanism. Groups are `{ label?, options }`; an option may carry a mono number, a
 * status tag, a secondary tag and a disabled flag.
 *
 * **What changed in the port.** `v-model` is `value` + `onSelect` (this control never edits a value
 * in place, it reports the one that was chosen), and Vue's `nextTick` before measuring became a
 * `requestAnimationFrame`, which is the tick after React has put the opened list in the DOM.
 */
export interface MenuOption {
  value: string
  label: string
  /** A mono suffix after the label: the version number in a version picker. */
  number?: string
  /** A status tag at the far right. */
  tag?: string
  /** The tag's tone: `live`, `draft`, `revoked`, or empty for plain. */
  tagTone?: string
  /** A second, quieter tag: 「上一版」 in the comparison picker. */
  meta?: string
  disabled?: boolean
  /** Extra words the search matches, beyond the label and number. */
  searchText?: string
}

export interface MenuGroup {
  label?: string
  options: MenuOption[]
}

export interface MenuSelectProps {
  groups: MenuGroup[]
  value: string
  /** Read aloud; the visible label, when there is one, is wired to it by the caller. */
  ariaLabel?: string
  /** A visible label element's id — the design's `aria-labelledby`, used for 申请档位. */
  labelledby?: string
  /** What the closed button says; the selected option's label when omitted. */
  triggerText?: string
  /** A class for the trigger, for the version picker's width rule. */
  triggerClass?: string
  /** Draw a search box above the options and filter them as it is typed. */
  search?: boolean
  searchPlaceholder?: string
  emptyText?: string
  disabled?: boolean
  onSelect: (value: string) => void
}

export default function MenuSelect({
  groups,
  value,
  ariaLabel,
  labelledby,
  triggerText,
  triggerClass,
  search = false,
  searchPlaceholder = '按名称筛选',
  emptyText = '没有匹配的版本。',
  disabled = false,
  onSelect,
}: MenuSelectProps) {
  const trigger = useRef<HTMLButtonElement | null>(null)
  const list = useRef<HTMLDivElement | null>(null)
  const searchInput = useRef<HTMLInputElement | null>(null)
  const [open, setOpen] = useState(false)
  /** The flag the document listeners read: they are attached once per opening. */
  const openRef = useRef(false)
  const [query, setQuery] = useState('')
  const [listStyle, setListStyle] = useState<CSSProperties>({})

  const options = groups.flatMap((group) => group.options)

  const found = options.find((option) => option.value === value)
  const selectedLabel = found === undefined ? value : found.label
  const triggerLabel = triggerText ?? selectedLabel

  /** A group is hidden when its options all fall out of the search; the popup says so once. */
  function matches(option: MenuOption): boolean {
    const needle = query.trim().toLowerCase()
    if (needle === '') {
      return true
    }
    const haystack = [
      option.label,
      option.number ?? '',
      option.tag ?? '',
      option.meta ?? '',
      option.searchText ?? '',
    ]
      .join(' ')
      .toLowerCase()
    return haystack.includes(needle)
  }

  const shownGroups = groups.map((group) => ({
    label: group.label,
    options: group.options.map((option) => ({ option, hidden: !matches(option) })),
    hidden: group.options.every((option) => !matches(option)),
  }))

  const noMatches = search && shownGroups.every((group) => group.hidden)

  function position(): void {
    const button = trigger.current
    const box = list.current
    if (button === null || box === null) {
      return
    }
    const rect = button.getBoundingClientRect()
    // Measured while hidden, which still gives a height: the element is laid out, only out of sight.
    const height = box.getBoundingClientRect().height
    const width = box.getBoundingClientRect().width
    const below = rect.bottom + 6
    setListStyle({
      fontSize: window.getComputedStyle(button).fontSize,
      minWidth: search ? '0px' : `${Math.round(rect.width)}px`,
      left: `${Math.round(
        Math.min(Math.max(8, rect.left), Math.max(8, window.innerWidth - width - 8)),
      )}px`,
      top: `${Math.round(below + height > window.innerHeight - 8 ? rect.top - 6 - height : below)}px`,
    })
  }

  function focusFirst(): void {
    if (search) {
      searchInput.current?.focus()
      return
    }
    const current =
      list.current?.querySelector<HTMLElement>('.menu-item[aria-selected="true"]:not(:disabled)') ??
      list.current?.querySelector<HTMLElement>('.menu-item:not(:disabled)')
    current?.focus()
  }

  // **One identity for the document listeners.** They are added when the list opens and removed when
  // it closes or the component unmounts; a function declared in the body is a different object every
  // render, so a later render's removal would not match the opening render's addition and the
  // listeners would pile up (Vue's setup-scope functions stayed the same object). The wrappers are
  // made once and call the latest handlers.
  const latestHandlers = useRef({ onOutside, closeOnViewportChange })
  latestHandlers.current = { onOutside, closeOnViewportChange }
  const listeners = useRef({
    outside: (event: MouseEvent) => latestHandlers.current.onOutside(event),
    viewport: () => latestHandlers.current.closeOnViewportChange(),
  }).current

  function close(refocus: boolean): void {
    if (!openRef.current) {
      return
    }
    openRef.current = false
    setOpen(false)
    document.removeEventListener('mousedown', listeners.outside, true)
    window.removeEventListener('scroll', listeners.viewport, true)
    window.removeEventListener('resize', listeners.viewport)
    if (refocus) {
      trigger.current?.focus()
    }
  }

  function show(): void {
    if (disabled) {
      return
    }
    setQuery('')
    openRef.current = true
    setOpen(true)
    requestAnimationFrame(() => {
      if (!openRef.current) {
        return
      }
      position()
      focusFirst()
    })
    document.addEventListener('mousedown', listeners.outside, true)
    window.addEventListener('scroll', listeners.viewport, true)
    window.addEventListener('resize', listeners.viewport)
  }

  function onOutside(event: MouseEvent): void {
    const target = event.target as Node
    if (trigger.current?.contains(target) !== true && list.current?.contains(target) !== true) {
      close(false)
    }
  }

  function closeOnViewportChange(): void {
    close(false)
  }

  function choose(chosen: string): void {
    close(true)
    onSelect(chosen)
  }

  function visibleItems(): HTMLElement[] {
    return Array.from(list.current?.querySelectorAll<HTMLElement>('.menu-item') ?? []).filter(
      (item) => item.hidden !== true && !item.hasAttribute('disabled'),
    )
  }

  function onTriggerKeydown(event: ReactKeyboardEvent): void {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      if (!openRef.current) {
        show()
      }
    }
  }

  function onSearchKeydown(event: ReactKeyboardEvent): void {
    const items = visibleItems()
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      items[0]?.focus()
    } else if (event.key === 'Enter') {
      event.preventDefault()
      const target = items.find((item) => !item.hasAttribute('disabled'))
      if (target !== undefined) {
        choose(target.dataset.value ?? '')
      }
    } else if (event.key === 'Escape') {
      event.preventDefault()
      close(true)
    } else if (event.key === 'Tab') {
      close(false)
    }
  }

  /** Arrow keys, Home/End and Escape inside the list — the same keys a native menu answers to. */
  function onListKeydown(event: ReactKeyboardEvent): void {
    const items = visibleItems()
    const index = items.indexOf(document.activeElement as HTMLElement)
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      items[Math.min(index + 1, items.length - 1)]?.focus()
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      if (index <= 0 && search) {
        searchInput.current?.focus()
      } else {
        items[Math.max(index - 1, 0)]?.focus()
      }
    } else if (event.key === 'Home') {
      event.preventDefault()
      items[0]?.focus()
    } else if (event.key === 'End') {
      event.preventDefault()
      items[items.length - 1]?.focus()
    } else if (event.key === 'Escape') {
      event.preventDefault()
      close(true)
    } else if (event.key === 'Tab') {
      close(false)
    }
  }

  useEffect(
    () => () => {
      openRef.current = false
      document.removeEventListener('mousedown', listeners.outside, true)
      window.removeEventListener('scroll', listeners.viewport, true)
      window.removeEventListener('resize', listeners.viewport)
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  )

  return (
    <span className="menu">
      <button
        ref={trigger}
        type="button"
        className={triggerClass ? `control menu-trigger ${triggerClass}` : 'control menu-trigger'}
        aria-haspopup={search ? 'dialog' : 'listbox'}
        aria-expanded={open ? 'true' : 'false'}
        aria-label={ariaLabel}
        aria-labelledby={labelledby}
        disabled={disabled}
        data-value={value}
        title={triggerLabel}
        onClick={() => {
          if (openRef.current) {
            close(true)
          } else {
            show()
          }
        }}
        onKeyDown={onTriggerKeydown}
      >
        <span className="menu-trigger-text">{triggerLabel}</span>
      </button>
      <div
        ref={list}
        hidden={!open}
        className={search ? 'menu-list has-search' : 'menu-list'}
        role={search ? 'dialog' : 'listbox'}
        aria-label={ariaLabel}
        style={listStyle}
        onKeyDown={onListKeydown}
      >
        {search ? (
          <div className="menu-search">
            <input
              ref={searchInput}
              value={query}
              type="search"
              className="menu-search-input"
              placeholder={searchPlaceholder}
              aria-label={searchPlaceholder}
              autoComplete="off"
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={onSearchKeydown}
            />
          </div>
        ) : null}
        <div
          className={search ? 'menu-box' : ''}
          role={search ? 'listbox' : undefined}
          aria-label={search ? ariaLabel : undefined}
        >
          {shownGroups.map((group, groupIndex) => (
            <div
              key={groupIndex}
              className="menu-group"
              hidden={group.hidden}
              role={group.label ? 'group' : 'presentation'}
              aria-label={group.label}
            >
              {group.label ? <div className="menu-group-label">{group.label}</div> : null}
              {group.options.map((entry) => (
                <button
                  key={entry.option.value}
                  type="button"
                  role="option"
                  className="menu-item"
                  hidden={entry.hidden}
                  data-value={entry.option.value}
                  aria-selected={entry.option.value === value ? 'true' : 'false'}
                  aria-disabled={entry.option.disabled ? 'true' : undefined}
                  disabled={entry.option.disabled}
                  title={
                    entry.option.label +
                    (entry.option.number ? ' ' + entry.option.number : '')
                  }
                  onClick={() => choose(entry.option.value)}
                >
                  <svg className="menu-check" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path
                      d="M5 12.4l4.4 4.4L19 7.6"
                      stroke="currentColor"
                      strokeWidth="2.4"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                  <span className="menu-label">{entry.option.label}</span>
                  {entry.option.number ? (
                    <span className="menu-number">{entry.option.number}</span>
                  ) : null}
                  {entry.option.tag ? (
                    <span
                      className={
                        entry.option.tagTone ? `menu-tag is-${entry.option.tagTone}` : 'menu-tag'
                      }
                    >
                      {entry.option.tag}
                    </span>
                  ) : null}
                  {entry.option.meta ? (
                    <span className="menu-tag is-meta">{entry.option.meta}</span>
                  ) : null}
                </button>
              ))}
            </div>
          ))}
          {noMatches ? <div className="menu-empty">{emptyText}</div> : null}
        </div>
      </div>
    </span>
  )
}
