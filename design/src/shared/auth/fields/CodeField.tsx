/**
 * Six boxes are what the person reads, but a single real input lies over all of them: transparent
 * ink, transparent caret, no pointer events. The boxes take the clicks, so pointing at one puts the
 * caret in that box — while paste, the caret, selection and what a screen reader announces all come
 * from the one field underneath.
 */

import { useRef, useState } from 'react'
import type { AuthField } from './AuthField'
import FieldMessage from './FieldMessage'

export interface CodeFieldProps {
  id: string
  boxesId: string
  hintId: string
  hint: string
  field: AuthField
  /** The reset step judges a finished code from its own input rather than from a submit. */
  onValueChange?: (value: string) => void
}

export default function CodeField({ id, boxesId, hintId, hint, field, onValueChange }: CodeFieldProps) {
  const [focused, setFocused] = useState(false)
  const [caret, setCaret] = useState(0)
  const input = useRef<HTMLInputElement>(null)
  const value = field.state.value
  const error = field.state.note.kind === 'error'

  function placeCaret(index: number): void {
    const node = input.current
    if (node === null) return
    node.focus()
    node.setSelectionRange(index, index)
    /* The browser clamps the range to the value's length, so pointing past the last digit means
       「carry on from here」. */
    setCaret(node.selectionStart ?? index)
  }

  return (
    <div className={'field' + (error ? ' has-error' : '')}>
      <label htmlFor={id}>短信验证码</label>
      <div className="code-input">
        <input
          id={id}
          name="code"
          maxLength={6}
          inputMode="numeric"
          autoComplete="one-time-code"
          aria-required="true"
          aria-describedby={id + 'Err'}
          aria-invalid={error ? 'true' : undefined}
          ref={input}
          value={value}
          onChange={(event) => {
            const next = event.target.value
            field.set(next)
            setCaret(next.length)
            onValueChange?.(next)
          }}
          onKeyUp={(event) => {
            /* The caret can move without the value changing — arrow keys, home, a selection.
               Read the position out here: a state updater runs later, during render, by which time
               React has cleared `currentTarget` — and reading it there threw and took the page down. */
            const at = event.currentTarget.selectionStart
            setCaret((current) => at ?? current)
          }}
          onFocus={(event) => {
            setFocused(true)
            /* After the last digit, which is where a field arrives when it is reached without a
               click. */
            setCaret(event.currentTarget.selectionStart ?? event.currentTarget.value.length)
          }}
          onBlur={() => {
            field.blur()
            setFocused(false)
          }}
        />
        <div className="code-boxes" id={boxesId} aria-hidden="true">
          {Array.from({ length: 6 }, (_, index) => (
            <span
              key={index}
              className={'code-box' + (focused && index === Math.min(caret, 5) ? ' is-active' : '')}
              onClick={() => placeCaret(index)}
            >
              {value.charAt(index)}
            </span>
          ))}
        </div>
      </div>
      <FieldMessage id={id + 'Err'} status={field.state.note} />
      <p className="hint" id={hintId}>
        {hint}
      </p>
    </div>
  )
}
