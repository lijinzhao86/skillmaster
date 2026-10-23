/**
 * **正在生效的筛选，各是一枚可去掉的胶囊。**
 *
 * 控件的触发按钮只说「选了几个」，名字落在这里——地址里写 id、界面上写名字（PRD §展示与计数口径），
 * 所以一张筛过的列表发出去之后，读的人先看见的是「筛的是什么」，不必把每个下拉都点一遍。
 *
 * 一个标签被删掉之后，地址仍然打得开：那枚胶囊写「已删除的标签」，× 照样能去掉它——
 * 这是 PRD 要求「给出空态与清除入口」的那一处。
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
  /** 一页一份：两页都在文档里，同一个 id 读出来的是两份东西。 */
  id: string
}) {
  if (chips.length === 0) return null
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
      {chips.length > 1 && (
        <button type="button" className="link-button filters-clear" onClick={onClear}>清除筛选</button>
      )}
    </div>
  )
}
