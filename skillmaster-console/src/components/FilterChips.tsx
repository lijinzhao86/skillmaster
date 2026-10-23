/**
 * **The filters that are on, each a chip that can be taken off.**
 *
 * The triggers only say "how many"; the names live here — the address carries ids, the interface
 * carries names (prd.md §展示与计数口径), so somebody opening a filtered list first sees *what* it
 * is filtered by without opening every menu.
 *
 * A deleted tag's address still opens: its chip says 「已删除的标签」 and the × still removes it —
 * the requirement's "give an empty state and a way to clear it".
 */
export interface FilterChip {
  value: string
  label: string
}

export default function FilterChips({
  chips,
  onRemove,
  onClear,
  id,
}: {
  chips: FilterChip[]
  onRemove: (value: string) => void
  onClear: () => void
  /** One per page: both pages are in the document. */
  id: string
}) {
  if (chips.length === 0) {
    return null
  }
  return (
    <div className="filters" id={id}>
      <span className="filters-label">筛选</span>
      {chips.map((chip) => (
        <span className="filter-chip" key={chip.value}>
          <b>{chip.label}</b>
          <button
            type="button"
            className="filter-x"
            aria-label={'去掉「' + chip.label + '」'}
            onClick={() => onRemove(chip.value)}
          >
            ×
          </button>
        </span>
      ))}
      {chips.length > 1 ? (
        <button type="button" className="link-button filters-clear" onClick={onClear}>
          清除筛选
        </button>
      ) : null}
    </div>
  )
}
