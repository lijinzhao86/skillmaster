/**
 * 分页条：一共多少、第几到第几、每页几条，以及页码。结果装得下就不画——只有一个「1」的控件没有作用。
 *
 * 页码只画两端、当前页与它的邻居；中间跳过的一段用一枚「…」表示，跳过的页数不改变那枚省略号的数量。
 * 地址由调用方给：两页的其它参数不同（筛选、搜索、页签），所以 `hrefFor` 是 props，不是写死的。
 *
 * **每页条数（2026-10-31 用户定）**：10 / 20 / 50，默认 10。给了 `onPageSize` 才画。
 * 画了它，这条分页条就不再按「这一页装得下」隐藏，而是按「换一档还有没有用」——
 * 一共不超过最小那一档（10）时藏起来，否则一直露着：否则有人选了 50、库里 23 条只占一页，
 * 他就再也回不到 10 了。换档必须回到第 1 页（由调用方处理，页码由它重算）。
 *
 * **只有一页时，页码与「上一页 / 下一页」都不画（2026-11-01 用户定）。** 上一档把分页条
 * 留着是为了「换档」那一个动作，可一页就装下时那一排页码与两个箭头全都动不了——
 * 上一页是禁用态、页码只有一个「1」。这条脚的判据因此是一句话：**只放还能动的控件**，
 * 所以那一态剩下左边的计数和右边的每页条数。
 */

import MenuSelect from './MenuSelect'

const PAGE_SIZE_DEFAULT = 10
const SIZE_OPTIONS_DEFAULT = [10, 20, 50]

export default function Pager({
  total,
  page,
  hrefFor,
  onGo,
  pageSize = PAGE_SIZE_DEFAULT,
  sizeOptions = SIZE_OPTIONS_DEFAULT,
  onPageSize,
  id,
}: {
  total: number
  /** 1 起的页码。越界由调用方收在末页。 */
  page: number
  hrefFor: (page: number) => string
  onGo: (page: number) => void
  pageSize?: number
  /** 每页几条可选的那几档。只有给了 `onPageSize` 时才画这个控件。 */
  sizeOptions?: number[]
  onPageSize?: (size: number) => void
  /** 一页一份，检查脚本按它读这一页的分页条。 */
  id?: string
}) {
  const smallest = Math.min(...sizeOptions)
  /* 有换档的控件时，藏起来的判据是「换了也没用」；没有它，就是原来那条「这一页装得下」。 */
  if (total <= (onPageSize === undefined ? pageSize : smallest)) return null
  const pages = Math.max(1, Math.ceil(total / pageSize))
  /* 页码越界时不画成倒序区间（审计 2026-11-01）。 */
  const current = Math.min(Math.max(1, page), pages)
  const start = (current - 1) * pageSize
  const shown = Math.max(0, Math.min(pageSize, total - start))

  const numbers: (number | null)[] = []
  for (let number = 1; number <= pages; number += 1) {
    if (number === 1 || number === pages || Math.abs(number - current) <= 1) numbers.push(number)
    else if (numbers[numbers.length - 1] !== null) numbers.push(null)
  }

  const step = (label: string, target: number, off: boolean) =>
    off ? (
      <span className="is-off">{label}</span>
    ) : (
      <a
        className="pager-step"
        href={hrefFor(target)}
        onClick={(event) => { event.preventDefault(); onGo(target) }}
      >
        {label}
      </a>
    )

  return (
    <nav className="pager" id={id} aria-label="分页">
      <span className={onPageSize === undefined ? 'pager-count' : 'pager-count is-with-size'}>
        {'共 ' + total + ' 个，第 ' + (start + 1) + '–' + (start + shown) + ' 个'}
      </span>
      {onPageSize === undefined ? null : (
        <span className="pager-size">
          <span aria-hidden="true">每页</span>
          <MenuSelect
            groups={[{ options: sizeOptions.map((size) => ({ value: String(size), label: String(size) })) }]}
            value={String(pageSize)}
            config={{
              ariaLabel: '每页显示多少条',
              triggerClass: 'pager-size-trigger',
              onPick: (value) => onPageSize(Number(value)),
            }}
          />
        </span>
      )}
      {/* 一页时这一排全是死控件——见文件头那一条。 */}
      {pages === 1 ? null : (
        <>
          {step('上一页', current - 1, current === 1)}
          {numbers.map((number, index) => {
            if (number === null) return <span className="is-gap" key={'gap' + index}>…</span>
            if (number === current) return <span className="is-current" aria-current="page" key={number}>{String(number)}</span>
            return (
              <a
                className="pager-page"
                key={number}
                href={hrefFor(number)}
                onClick={(event) => { event.preventDefault(); onGo(number) }}
              >
                {String(number)}
              </a>
            )
          })}
          {step('下一页', current + 1, current === pages)}
        </>
      )}
    </nav>
  )
}

export { PAGE_SIZE_DEFAULT }
