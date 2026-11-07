/**
 * 搜索框：放大镜在框里，输入即筛，不刷新页面。
 *
 * 这是「我的 skill」「发现」「待上线」「标签」四页五处共用那一个控件——同一个位置、同一副外观；
 * 占位按每一处真正要找的东西写，不是一句通用的话。
 *
 * **一套框，三档提交（2026-11-07）。** 框自己的行为只有一份：不卸载、不失焦、有字时浮出清除叉、
 * Esc 清空、`/` 能聚焦到它（顶栏因此把自己的搜索框删了）。「什么时候真的去搜」由页面决定——
 * 本地数据边打边筛，「我的 skill」边打边筛加防抖，「发现」回车才搜（一次搜索问一次模型）。
 * 判据在设计 README 的「搜索框：一套框，三档提交」。
 */
import { useRef } from 'react'

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
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          /* Not while an IME is composing: Escape there dismisses the candidate list, and emptying
             the whole search would throw away the word being typed. Safari reports
             `isComposing: false` on the keystroke that ends a composition and leaves `229` instead. */
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
