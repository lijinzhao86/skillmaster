/**
 * 时间范围：一个按钮，点开在下方展开一张**贴着按钮的浮层**（v60，从 `design/` 搬来）。
 *
 * **不用弹窗、不用掩膜。** 这一层只决定「看哪一段」，不是一次需要挡住整页的确认；
 * 页面其余部分要一直看得见，改完日期也不用先关一层再回来看结果。
 *
 * **自定义 = 两个可键入的日期 + 一张按月纵向滚动的日历。** 文本输入是快路径（知道哪一天就直接打），
 * 日历是找路径（记得大概哪几天就翻）。日历把 90 天内的月份从上到下排开，滚动就是按年月找。
 *
 * 原生 `type=date` 不用：它只能逐段点选，看起来像不能编辑（`design/README.md` 实测记录）。
 */
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import {
  DEFAULT_RANGE,
  oldest,
  rangeBounds,
  rangeLabel,
  today,
  validDay,
} from '../../usageRange'
import type { UsageRange as UsageRangeValue } from '../../usageRange'

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']
const DAY = /^\d{4}-\d{2}-\d{2}$/

const PRESETS = [
  { days: 1, label: '今天' },
  { days: 7, label: '近 7 天' },
  { days: 30, label: '近 30 天' },
  { days: 90, label: '近 90 天' },
]

function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

function monthOf(day: string): string {
  return day.slice(0, 7)
}

function monthParts(month: string): [number, number] {
  const [year, number] = month.split('-')
  return [Number(year), Number(number)]
}

function addMonths(month: string, delta: number): string {
  const [year, number] = monthParts(month)
  return new Date(Date.UTC(year, number - 1 + delta, 1)).toISOString().slice(0, 7)
}

function monthLabel(month: string): string {
  const [year, number] = monthParts(month)
  return `${year} 年 ${number} 月`
}

/** 90 天窗口里的月份，从旧到新；最多四个月。 */
function monthsBetween(from: string, to: string): string[] {
  const months: string[] = []
  let month = monthOf(from)
  const last = monthOf(to)
  while (month <= last) {
    months.push(month)
    month = addMonths(month, 1)
  }
  return months
}

/** 一个月的日期格：周一开头，前后补空格凑满整周。 */
function dayCells(month: string): (string | null)[] {
  const [year, number] = monthParts(month)
  const first = new Date(Date.UTC(year, number - 1, 1, 12))
  const lead = (first.getUTCDay() + 6) % 7
  const total = new Date(Date.UTC(year, number, 0)).getUTCDate()
  const cells: (string | null)[] = []
  for (let index = 0; index < lead; index += 1) cells.push(null)
  for (let day = 1; day <= total; day += 1) cells.push(`${month}-${pad2(day)}`)
  while (cells.length % 7 !== 0) cells.push(null)
  return cells
}

/** `null` = 可以应用；空值由按钮自己禁用，不提前报错。 */
function problem(from: string, to: string, last: string): string | null {
  if (from === '' || to === '') return null
  if (!DAY.test(from) || !DAY.test(to)) return '日期要写成 YYYY-MM-DD。'
  if (!validDay(from) || !validDay(to)) return '这个日期不存在，请检查年月日。'
  if (from > to) return '开始日期不能晚于结束日期。'
  if (from < oldest(last)) return `只能选 ${oldest(last)} 之后的日期。`
  if (to > last) return '结束日期不能晚于今天。'
  return null
}

export default function UsageRange({
  range,
  onChange,
  id,
  label,
}: {
  range: UsageRangeValue
  onChange: (next: UsageRangeValue) => void
  /** 触发按钮与浮层的稳定 id；同一个页面只出现一次，两个页面各一份。 */
  id: string
  /** 读屏用的名字；视觉上只有触发按钮上的范围本身。 */
  label: string
}) {
  const [open, setOpen] = useState(false)
  const last = today()
  const bounds = rangeBounds(range)
  const [from, setFrom] = useState(bounds.from)
  const [to, setTo] = useState(bounds.to)
  const wrap = useRef<HTMLSpanElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const firstPreset = useRef<HTMLButtonElement>(null)
  const firstField = useRef<HTMLInputElement>(null)
  const monthsBox = useRef<HTMLDivElement>(null)
  const monthRefs = useRef<Map<string, HTMLDivElement>>(new Map())
  const [place, setPlace] = useState<{ left: number; top: number; width: number; maxHeight: number } | null>(null)

  const token = range.from !== null ? 'custom' : String(range.days ?? DEFAULT_RANGE.days)
  const error = problem(from, to, last)
  const ready = from !== '' && to !== '' && error === null
  const startChosen = validDay(from)
  const endChosen = validDay(to) && from <= to
  const selected = startChosen && endChosen
  const months = monthsBetween(oldest(last), last)

  /* **浮层用 fixed 定位**：页头在窄屏会把工具折到左边，绝对定位的浮层会从视口左边溢出去。
     按触发按钮的矩形算，右对齐、夹在视口里；下面放不下就往上翻。 */
  useLayoutEffect(() => {
    if (!open) return
    const button = trigger.current
    if (button === null) return
    const rect = button.getBoundingClientRect()
    const width = Math.min(320, window.innerWidth - 32)
    const left = Math.min(Math.max(8, rect.right - width), window.innerWidth - width - 8)
    const roomBelow = window.innerHeight - rect.bottom - 8
    const roomAbove = rect.top - 8
    const above = roomBelow < 320 && roomAbove > roomBelow
    const maxHeight = Math.max(240, Math.min(560, above ? roomAbove : roomBelow))
    const top = above ? Math.max(8, rect.top - 6 - maxHeight) : rect.bottom + 6
    setPlace({ left, top, width, maxHeight })
  }, [open])

  /* 打开时把字段带回当前区间：预设态用它的起止，自定义态用选中的那两个日期。 */
  const show = () => {
    const next = rangeBounds(range)
    setFrom(next.from)
    setTo(next.to)
    setOpen(true)
  }

  useEffect(() => {
    if (!open) return
    if (range.from !== null) {
      firstField.current?.focus()
      return
    }
    /* 焦点落在**当前那一档**上，不是永远第一档：打开时键盘用户接着按 Tab 就往下一档走。 */
    const current = document.getElementById(`${id}Preset${token}`) as HTMLButtonElement | null
    ;(current ?? firstPreset.current)?.focus()
    // 只在打开的那一下决定焦点；之后用户点了哪里就留在哪里。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  /* 打开日历时滚到「开始」那一月：**等 `place` 落定再算**，隐藏元素的 `offsetTop` 全是 0。 */
  useLayoutEffect(() => {
    if (!open || place === null) return
    const month = monthOf(validDay(from) ? from : oldest(last))
    const block = monthRefs.current.get(month)
    const box = monthsBox.current
    if (block !== undefined && box !== null) {
      box.scrollTop = block.offsetTop - box.offsetTop
    }
    // 只在打开的那一下决定滚动位置；之后用户滚到哪里就留在哪里。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, place])

  /* 点外面、按 Esc、以及 Tab 走出这张浮层都收起。没有掩膜，所以「点外面」是唯一的取消手势。 */
  useEffect(() => {
    if (!open) return
    const outside = (event: MouseEvent) => {
      if (!wrap.current?.contains(event.target as Node)) setOpen(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      setOpen(false)
      trigger.current?.focus()
    }
    /* 页面滚走、窗口变宽变窄时收起：fixed 浮层不会跟着按钮走，留着它就会飘在半空。
       浮层自己的月份列表滚动不算——那正是它要支持的手势。 */
    const onScroll = (event: Event) => {
      if (event.target instanceof Node && wrap.current?.contains(event.target)) return
      setOpen(false)
    }
    const onResize = () => setOpen(false)
    document.addEventListener('mousedown', outside, true)
    document.addEventListener('keydown', onKey)
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', onResize)
    return () => {
      document.removeEventListener('mousedown', outside, true)
      document.removeEventListener('keydown', onKey)
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', onResize)
    }
  }, [open])

  const pick = (days: number) => {
    setOpen(false)
    onChange({ days, from: null, to: null })
    trigger.current?.focus()
  }

  const apply = () => {
    if (!ready) return
    setOpen(false)
    onChange({ days: null, from, to })
    trigger.current?.focus()
  }

  /* 点日历：没有起点、或已经选完一整段，就从这一天重新开始；已有起点就补终点；
     补的终点早于起点时自动对调——不让人先想「我该点哪一头」。 */
  const pickDay = (day: string) => {
    if (!validDay(from) || (selected && from !== '' && to !== '')) {
      setFrom(day)
      setTo('')
      return
    }
    if (day < from) {
      setTo(from)
      setFrom(day)
      return
    }
    setTo(day)
  }

  return (
    <span
      className="usage-range"
      ref={wrap}
      onBlur={(event) => {
        /* 只在焦点**移到外面另一个元素**时收起。`relatedTarget` 为空多半是点了滚动条，
           那种时候收起会把正在滚的日历关掉。 */
        const next = event.relatedTarget as Node | null
        if (next !== null && !wrap.current?.contains(next)) setOpen(false)
      }}
    >
      <button
        type="button"
        className="control menu-trigger"
        id={id}
        ref={trigger}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={id + 'Panel'}
        aria-label={label}
        onClick={() => {
          if (open) setOpen(false)
          else show()
        }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'Enter' || event.key === ' ') {
            if (open) return
            event.preventDefault()
            show()
          }
        }}
      >
        <span className="menu-trigger-text">{rangeLabel(range)}</span>
      </button>

      <div
        className="usage-range-pop"
        id={id + 'Panel'}
        role="dialog"
        aria-label={label}
        hidden={!open || place === null}
        style={place === null ? undefined : {
          left: place.left,
          top: place.top,
          width: place.width,
          maxHeight: place.maxHeight,
        }}
      >
        <div className="usage-range-presets" role="group" aria-label="预设">
          {PRESETS.map((preset, index) => (
            <button
              key={preset.days}
              type="button"
              className={'usage-range-preset' + (token === String(preset.days) ? ' is-current' : '')}
              id={`${id}Preset${preset.days}`}
              aria-pressed={token === String(preset.days)}
              ref={index === 0 ? firstPreset : undefined}
              onClick={() => pick(preset.days)}
            >
              {preset.label}
            </button>
          ))}
        </div>

        <div className="usage-range-custom">
          <span className="usage-range-caption">自定义</span>
          <label className="usage-range-field">
            <span>开始</span>
            <input
              className="control usage-range-date"
              id={id + 'From'}
              ref={firstField}
              type="text"
              inputMode="numeric"
              autoComplete="off"
              spellCheck={false}
              placeholder="YYYY-MM-DD"
              maxLength={10}
              value={from}
              onChange={(event) => setFrom(event.target.value)}
            />
          </label>
          <label className="usage-range-field">
            <span>结束</span>
            <input
              className="control usage-range-date"
              id={id + 'To'}
              type="text"
              inputMode="numeric"
              autoComplete="off"
              spellCheck={false}
              placeholder="YYYY-MM-DD"
              maxLength={10}
              value={to}
              onChange={(event) => setTo(event.target.value)}
            />
          </label>

          <div className="usage-range-cal">
            <div className="usage-range-week" aria-hidden="true">
              {WEEKDAYS.map((weekday) => (
                <span key={weekday}>{weekday}</span>
              ))}
            </div>
            <div className="usage-range-months" id={id + 'Months'} ref={monthsBox}>
              {months.map((month) => (
                <div
                  className="usage-range-month"
                  key={month}
                  ref={(node) => {
                    if (node !== null) monthRefs.current.set(month, node)
                    else monthRefs.current.delete(month)
                  }}
                >
                  <div className="usage-range-month-title">{monthLabel(month)}</div>
                  <div className="usage-range-days">
                    {dayCells(month).map((day, index) => {
                      if (day === null) return <span key={`blank-${index}`} />
                      const disabled = day < oldest(last) || day > last
                      const isStart = startChosen && day === from
                      const isEnd = selected && day === to
                      const isRange = selected && day > from && day < to
                      return (
                        <button
                          key={day}
                          type="button"
                          className={
                            'usage-range-day' +
                            (isStart || isEnd ? ' is-selected' : '') +
                            (isRange ? ' is-in-range' : '') +
                            (disabled ? ' is-disabled' : '')
                          }
                          disabled={disabled}
                          aria-pressed={isStart || isEnd}
                          aria-label={day}
                          onClick={() => pickDay(day)}
                        >
                          {Number(day.slice(8, 10))}
                        </button>
                      )
                    })}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {error !== null && (
            <p className="usage-range-error" id={id + 'Error'} aria-live="polite">
              {error}
            </p>
          )}
          <div className="usage-range-acts">
            <button type="button" className="button" id={id + 'Apply'} disabled={!ready} onClick={apply}>
              应用
            </button>
          </div>
        </div>
      </div>
    </span>
  )
}
