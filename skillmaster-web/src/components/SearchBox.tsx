/**
 * The search box: a magnifier inside the frame, filtering as it is typed, no reload.
 *
 * The same control on 我的 skill and 发现 — same place, same face, same placeholder rule. Two copies
 * drift at the sentence first, which is exactly what the requirement about `when_to_use` made
 * visible.
 */
export default function SearchBox({
  id,
  value,
  placeholder,
  label,
  onChange,
}: {
  id: string
  value: string
  placeholder: string
  /** The name a screen reader reads; visually there is only the magnifier. */
  label: string
  onChange: (value: string) => void
}) {
  return (
    <label className="search">
      <span className="visually-hidden">{label}</span>
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
        className="control"
        type="search"
        autoComplete="off"
        spellCheck={false}
        placeholder={placeholder}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}
