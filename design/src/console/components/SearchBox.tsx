/**
 * 搜索框：放大镜在框里，输入即筛，不刷新页面。
 *
 * 这是「我的 skill」与「发现」两页共用的那一个控件——同一个位置、同一副外观、同一句占位。
 * 两页各写一份的代价是那句占位会先漂一处（PRD 要求两处都随 `when_to_use` 更新）。
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
  /** 读屏用的名字。视觉上只有那枚放大镜。 */
  label: string
  onChange: (value: string) => void
}) {
  return (
    <label className="search">
      <span className="visually-hidden">{label}</span>
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
        <line x1="15.4" y1="15.4" x2="20.6" y2="20.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
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
