/**
 * The pager: how many, which slice, and the page numbers.
 *
 * Not drawn when everything fits — a lone 「1」 under a list that fits is a control that does
 * nothing, and the count already sits beside the title.
 *
 * Only the two ends, the current page and its neighbours are numbered; a skipped run is one 「…」.
 * The address is the caller's: the two pages carry different other parameters (filters, search, tab),
 * which is why `hrefFor` is a prop rather than a fixed spelling.
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
  /** 1-based. An out-of-range page is clamped by the caller. */
  page: number
  hrefFor: (page: number) => string
  onGo: (page: number) => void
  pageSize?: number
  /** One per page: both pages are in the document, and an id must name one of them. */
  id?: string
}) {
  if (total <= pageSize) {
    return null
  }
  const pages = Math.max(1, Math.ceil(total / pageSize))
  const start = (page - 1) * pageSize
  const shown = Math.min(pageSize, total - start)

  const numbers: Array<number | null> = []
  for (let number = 1; number <= pages; number += 1) {
    if (number === 1 || number === pages || Math.abs(number - page) <= 1) {
      numbers.push(number)
    } else if (numbers[numbers.length - 1] !== null) {
      numbers.push(null)
    }
  }

  const step = (label: string, target: number, off: boolean) =>
    off ? (
      <span className="is-off">{label}</span>
    ) : (
      <a
        className="pager-step"
        href={hrefFor(target)}
        onClick={(event) => {
          event.preventDefault()
          onGo(target)
        }}
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
        if (number === null) {
          return (
            <span className="is-gap" key={'gap' + index}>
              …
            </span>
          )
        }
        if (number === page) {
          return (
            <span className="is-current" aria-current="page" key={number}>
              {String(number)}
            </span>
          )
        }
        return (
          <a
            className="pager-page"
            key={number}
            href={hrefFor(number)}
            onClick={(event) => {
              event.preventDefault()
              onGo(number)
            }}
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
