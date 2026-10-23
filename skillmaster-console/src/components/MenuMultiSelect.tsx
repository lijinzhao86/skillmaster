import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'

/**
 * A **multi-select**: a button, and the list the page paints with a check on every chosen row.
 *
 * The single select (`MenuSelect`) is "choose one, close". This one does not close on a click:
 * toggling is the whole gesture, and closing after each pick would mean reopening for the next one.
 * It is still a listbox rather than a column of checkboxes — `aria-multiselectable="true"` with
 * `aria-selected` on every row is what a screen reader should hear.
 *
 * **The trigger shows a count, not the chosen names.** The names sit in the filter chips below the
 * head, so one fact is not said twice in one screen.
 */
export interface MultiOption {
  value: string
  label: string
  /** How many skills use it; not drawn when absent. */
  count?: number
  disabled?: boolean
}

export interface MultiConfig {
  /** What the group is called: 「分类」 / 「我的标签」. */
  placeholder: string
  ariaLabel?: string
  onToggle?: (value: string) => void
  onClear?: () => void
  /** An icon trigger (the info page's two tag groups); the default is a worded button. */
  tool?: 'add'
  /** The panel's floor: a 24px button cannot hold a readable list. */
  menuMinWidth?: number
}

const CHECK = (
  <svg className="menu-check" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path
      d="M5 12.4l4.4 4.4L19 7.6"
      stroke="currentColor"
      strokeWidth="2.4"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
)

export default function MenuMultiSelect({
  options,
  values,
  config,
  initialOpen = false,
  id,
}: {
  options: MultiOption[]
  values: string[]
  config: MultiConfig
  /** `?edit=` can open the control as the page arrives. */
  initialOpen?: boolean
  id?: string
}) {
  const [open, setOpen] = useState(initialOpen)
  const wrap = useRef<HTMLSpanElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const list = useRef<HTMLDivElement>(null)
  const itemRefs = useRef<Map<string, HTMLButtonElement>>(new Map())

  const chosen = (value: string) => values.includes(value)
  const text = values.length === 0 ? config.placeholder : config.placeholder + ' · ' + values.length

  const close = (refocus: boolean) => {
    setOpen(false)
    if (refocus) {
      trigger.current?.focus()
    }
  }

  /* Position once the list is in the document: below the trigger, or above it when there is no room.
     The width is the trigger's own — the entries belong to the control. */
  useLayoutEffect(() => {
    if (!open) {
      return
    }
    const button = trigger.current
    const panel = list.current
    if (!button || !panel) {
      return
    }
    const box = button.getBoundingClientRect()
    panel.style.fontSize = getComputedStyle(button).fontSize
    panel.style.minWidth = Math.round(Math.max(box.width, config.menuMinWidth ?? 0)) + 'px'
    const width = panel.getBoundingClientRect().width
    panel.style.left =
      Math.round(Math.min(Math.max(8, box.left), Math.max(8, window.innerWidth - width - 8))) + 'px'
    const height = panel.getBoundingClientRect().height
    const below = box.bottom + 6
    panel.style.top =
      Math.round(below + height > window.innerHeight - 8 ? box.top - 6 - height : below) + 'px'
    ;(itemRefs.current.get(values[0] ?? '') ?? itemRefs.current.get(options[0]?.value ?? ''))?.focus()
  }, [open])

  useEffect(() => {
    if (!open) {
      return
    }
    const outside = (event: MouseEvent) => {
      if (!wrap.current?.contains(event.target as Node) && !list.current?.contains(event.target as Node)) {
        close(false)
      }
    }
    /* Same two rules as the single select: a wheel over the list is the list scrolling, and scrolling
       the page under an open menu is the thing to close for. */
    const viewport = (event: Event) => {
      if (event.target instanceof Node && list.current?.contains(event.target)) {
        return
      }
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

  const enabled = options.filter((option) => !option.disabled)

  const focusAt = (next: number) => {
    const target = enabled[Math.min(Math.max(next, 0), enabled.length - 1)]
    if (target) {
      itemRefs.current.get(target.value)?.focus()
    }
  }

  const onListKey = (event: KeyboardEvent) => {
    const index = enabled.findIndex(
      (option) => itemRefs.current.get(option.value) === document.activeElement,
    )
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      focusAt(Math.min(index + 1, enabled.length - 1))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      focusAt(Math.max(index - 1, 0))
    } else if (event.key === 'Home') {
      event.preventDefault()
      focusAt(0)
    } else if (event.key === 'End') {
      event.preventDefault()
      focusAt(enabled.length - 1)
    } else if (event.key === 'Escape') {
      event.preventDefault()
      close(true)
    } else if (event.key === 'Tab') {
      close(false)
    }
  }

  return (
    <span className="menu" ref={wrap}>
      <button
        type="button"
        className={config.tool === 'add' ? 'icon-add' : 'control menu-trigger'}
        id={id}
        ref={trigger}
        aria-haspopup="listbox"
        aria-expanded={open ? 'true' : 'false'}
        aria-label={config.ariaLabel ?? config.placeholder}
        data-value={values.join(',')}
        title={text}
        onClick={() => {
          if (open) {
            close(true)
          } else {
            setOpen(true)
          }
        }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault()
            setOpen(true)
          }
        }}
      >
        {config.tool === 'add' ? (
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 6v12M6 12h12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
        ) : (
          <span className="menu-trigger-text">{text}</span>
        )}
      </button>

      <div
        className="menu-list"
        ref={list}
        hidden={!open}
        role="listbox"
        aria-multiselectable="true"
        aria-label={config.ariaLabel ?? config.placeholder}
        onKeyDown={onListKey}
      >
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            className="menu-item"
            role="option"
            data-value={option.value}
            aria-selected={chosen(option.value) ? 'true' : 'false'}
            disabled={option.disabled}
            aria-disabled={option.disabled ? 'true' : undefined}
            title={option.label}
            ref={(node) => {
              if (node) {
                itemRefs.current.set(option.value, node)
              } else {
                itemRefs.current.delete(option.value)
              }
            }}
            onClick={() => {
              config.onToggle?.(option.value)
            }}
          >
            {CHECK}
            <span className="menu-label">{option.label}</span>
            {option.count !== undefined ? <span className="menu-number">{option.count}</span> : null}
          </button>
        ))}
        {values.length > 0 ? (
          <>
            <div className="menu-rule" />
            <button
              type="button"
              className="menu-item menu-clear"
              data-value=""
              onClick={() => config.onClear?.()}
            >
              <span className="menu-label">清除</span>
            </button>
          </>
        ) : null}
      </div>
    </span>
  )
}
