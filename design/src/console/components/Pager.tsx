/**
 * 分页条：一共多少、第几到第几，以及页码。结果装得下就不画——只有一个「1」的控件没有作用。
 *
 * 页码只画两端、当前页与它的邻居；中间跳过的一段用一枚「…」表示，跳过的页数不改变那枚省略号的数量。
 * 地址由调用方给：两页的其它参数不同（筛选、搜索、页签），所以 `hrefFor` 是 props，不是写死的。
 */

const PAGE_SIZE_DEFAULT = 10

export default function Pager({
  total,
  page,
  hrefFor,
  onGo,
  pageSize = PAGE_SIZE_DEFAULT,
  id,
}: {
  total: number
  /** 1 起的页码。越界由调用方收在末页。 */
  page: number
  hrefFor: (page: number) => string
  onGo: (page: number) => void
  pageSize?: number
  /** 一页一份，检查脚本按它读这一页的分页条。 */
  id?: string
}) {
  if (total <= pageSize) return null
  const pages = Math.max(1, Math.ceil(total / pageSize))
  const start = (page - 1) * pageSize
  const shown = Math.min(pageSize, total - start)

  const numbers: (number | null)[] = []
  for (let number = 1; number <= pages; number += 1) {
    if (number === 1 || number === pages || Math.abs(number - page) <= 1) numbers.push(number)
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
      <span className="pager-count">
        {'共 ' + total + ' 个，第 ' + (start + 1) + '–' + (start + shown) + ' 个'}
      </span>
      {step('上一页', page - 1, page === 1)}
      {numbers.map((number, index) => {
        if (number === null) return <span className="is-gap" key={'gap' + index}>…</span>
        if (number === page) return <span className="is-current" aria-current="page" key={number}>{String(number)}</span>
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
      {step('下一页', page + 1, page === pages)}
    </nav>
  )
}

export { PAGE_SIZE_DEFAULT }
