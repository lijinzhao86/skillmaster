/**
 * The search box: a magnifier inside the frame, filtering as it is typed, no reload.
 *
 * The same control on 我的 skill, 发现, 待上线 and 标签 — same place, same face; the placeholder is
 * written per page, because each one searches something different. Copies drift at the sentence
 * first, which is exactly what the requirement about `when_to_use` made visible.
 *
 * **One box, three moments to submit (2026-11-07).** The box's own behaviour has one copy: it is never
 * unmounted while the reader types, it keeps the focus, it grows a clear cross when it has text,
 * Escape empties it, and `/` can reach it (`data-page-search` — the top bar's own box was removed).
 * *When* a search is actually sent belongs to the page: local data filters as it is typed, 我的 skill
 * filters as it is typed behind a debounce, 发现 searches on Enter (one search is one model call).
 * The rule is `design/README.md` §搜索框：一套框，三档提交.
 */
import { useRef } from 'react'

export default function SearchBox({
  id,
  value,
  placeholder,
  label,
  onChange,
  maxLength,
}: {
  id: string
  value: string
  placeholder: string
  /** The name a screen reader reads; visually there is only the magnifier. */
  label: string
  onChange: (value: string) => void
  /** A server-side bound the box should not let a paste exceed. */
  maxLength?: number
}) {
  const input = useRef<HTMLInputElement>(null)
  const labelId = `${id}-label`

  return (
    /* **Clicking anywhere in the frame puts the caret in the box**, which is what the `<label>` used
       to give. A `<button>` cannot live inside a `<label>` (a label may not contain a labelable
       element other than the one it labels), so the frame is a plain div and the click is explicit. */
    <div
      className="search search-box"
      onClick={(event) => {
        if ((event.target as HTMLElement).closest('.search-clear') === null) {
          input.current?.focus()
        }
      }}
    >
      <span className="visually-hidden" id={labelId}>
        {label}
      </span>
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
        <line
          x1="15.4"
          y1="15.4"
          x2="20.6"
          y2="20.6"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      </svg>
      <input
        id={id}
        ref={input}
        className="control"
        type="search"
        autoComplete="off"
        spellCheck={false}
        aria-labelledby={labelId}
        /* What the shell's `/` looks for: the page's own box, not a global one. */
        data-page-search=""
        placeholder={placeholder}
        maxLength={maxLength}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          /* **Not while an IME is composing.** Escape there dismisses the candidate list; emptying
             the whole search instead would throw away the word being typed. Safari reports
             `isComposing: false` on the keystroke that ends a composition and leaves `229` in its
             place, so both are checked. */
          if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) {
            return
          }
          if (event.key === 'Escape' && value !== '') {
            event.preventDefault()
            onChange('')
          }
        }}
      />
      <button
        type="button"
        className="search-clear"
        aria-label="清除搜索"
        hidden={value === ''}
        onClick={() => {
          onChange('')
          input.current?.focus()
        }}
      >
        ×
      </button>
    </div>
  )
}
