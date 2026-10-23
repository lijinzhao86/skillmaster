import { useRef, useState } from 'react'

/**
 * A one-time code, drawn as one box per digit.
 *
 * <p>Six boxes are what the person reads, but there is only ever <em>one</em> real input, stretched
 * invisibly over them. That is the shape the established components use (Vuetify's `v-otp-input`
 * documents the same anatomy: a single hidden input for keyboard interaction, a field component per
 * character slot), and it is the shape the platform requires. `autocomplete="one-time-code"` is what
 * makes iOS and Safari offer the code the phone just received — and it works on <em>a field</em>, so
 * six real inputs would each be a field with no code to offer. Paste, the caret, selection, and what
 * a screen reader announces all come from the same single field rather than being rebuilt by hand.
 *
 * <p>The digits are therefore drawn twice: once by the input, in transparent ink, and once by the
 * boxes below it, which take the clicks (`pointer-events: none` on the input) so that pointing at a
 * box puts the caret in that box instead of wherever the invisible text happens to sit.
 *
 * <p>Nothing here filters what is typed. The rule about the code — six digits — is stated by
 * `validation.ts` and by the server, and a field that silently swallowed a character would leave the
 * person with a message about a keystroke that never appeared.
 */
const LENGTH = 6

export default function CodeInput({
  id,
  value,
  onChange,
  onBlur,
}: {
  /** the input's id, which is also what the label points at */
  id: string
  value: string
  onChange: (value: string) => void
  /** The input was left. The caller cannot place this on the wrapper: `blur` does not bubble. */
  onBlur?: () => void
}) {
  const field = useRef<HTMLInputElement | null>(null)
  const [focused, setFocused] = useState(false)
  /** Which box the caret is at, so the mark follows the person rather than staying on the last one. */
  const [caret, setCaret] = useState(0)

  const digits = Array.from({ length: LENGTH }, (_, index) => value[index] ?? '')
  /** The box the next digit goes in, or -1 while the field is not being used. */
  const active = focused ? Math.min(caret, LENGTH - 1) : -1

  /**
   * Puts the caret in the box somebody pointed at.
   *
   * The browser clamps the range to the value's length, so pointing past the last digit that was typed
   * means "carry on from here" rather than a caret in a box nothing can reach.
   */
  function placeCaret(index: number): void {
    const input = field.current
    if (input === null) {
      return
    }
    input.focus()
    input.setSelectionRange(index, index)
    setCaret(Math.min(index, value.length))
  }

  return (
    <div className="code-input">
      <input
        id={id}
        ref={field}
        value={value}
        maxLength={LENGTH}
        inputMode="numeric"
        autoComplete="one-time-code"
        aria-required="true"
        onChange={(event) => {
          setCaret(event.target.value.length)
          onChange(event.target.value)
        }}
        onKeyUp={(event) => {
          // The caret can move without the value changing — arrow keys, home, a selection.
          setCaret(event.currentTarget.selectionStart ?? caret)
        }}
        onFocus={(event) => {
          setFocused(true)
          // After the last digit, which is where a field arrives when it is reached without a click
          // and where the next one would go. A click sets it explicitly, and does so after this has
          // run.
          setCaret(event.currentTarget.selectionStart ?? value.length)
        }}
        onBlur={() => {
          setFocused(false)
          onBlur?.()
        }}
      />
      {/* Decorative: the value and the label are the real input's, and six extra text nodes beside
          it would have a screen reader read the code out a second time. */}
      <div className="code-boxes" aria-hidden="true">
        {digits.map((digit, index) => (
          <span
            key={index}
            className={index === active ? 'code-box is-active' : 'code-box'}
            onClick={() => placeCaret(index)}
          >
            {digit}
          </span>
        ))}
      </div>
    </div>
  )
}
