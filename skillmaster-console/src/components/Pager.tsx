import MenuSelect from './MenuSelect'

/**
 * The pager: how many, which slice, how many per page, and the page numbers.
 *
 * Not drawn when everything fits — a lone 「1」 under a list that fits is a control that does
 * nothing, and the count already sits beside the title.
 *
 * Only the two ends, the current page and its neighbours are numbered; a skipped run is one 「…」.
 * The address is the caller's: the two pages carry different other parameters (filters, search, tab),
 * which is why `hrefFor` is a prop rather than a fixed spelling.
 *
 * **How many per page (2026-11-01, the user's call): 10 / 20 / 50, default 10.** Drawn only when
 * `onPageSize` is given. With it, the pager stops hiding on "this page fits" and hides on "changing
 * the size would do nothing" instead — at most the smallest option (10). Otherwise somebody who
 * picked 50 on a 23-skill library would never get back to 10. Changing it must land on page 1; the
 * caller does that.
 *
 * **With one page, the numbers and the two arrows are not drawn (2026-11-01, the user's call).**
 * The row stays for the size control, but everything else in it would do nothing — the arrows are
 * disabled and there is one lone 「1」. The rule for this foot is one sentence: **it holds only the
 * controls that can act** — which leaves the count on the left and the size on the right.
 */
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
  /** 1-based. An out-of-range page is clamped by the caller. */
  page: number
  hrefFor: (page: number) => string
  onGo: (page: number) => void
  pageSize?: number
  /** The sizes offered. Drawn only when `onPageSize` is given. */
  sizeOptions?: number[]
  onPageSize?: (size: number) => void
  /** One per page: both pages are in the document, and an id must name one of them. */
  id?: string
}) {
  const smallest = Math.min(...sizeOptions)
  if (total <= (onPageSize === undefined ? pageSize : smallest)) {
    return null
  }
  const pages = Math.max(1, Math.ceil(total / pageSize))
  /* Never negative, and never a reversed range: a page past the end reads 「第 41–40 个」
     (audit 2026-11-01). */
  const current = Math.min(Math.max(1, page), pages)
  const start = (current - 1) * pageSize
  const shown = Math.max(0, Math.min(pageSize, total - start))

  const numbers: Array<number | null> = []
  for (let number = 1; number <= pages; number += 1) {
    if (number === 1 || number === pages || Math.abs(number - current) <= 1) {
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
      {/* Copied from the design's `Pager` word for word, `is-with-size` included: no rule reads that
          second class today, but the two files' markup is meant to be the same markup. */}
      <span className={onPageSize === undefined ? 'pager-count' : 'pager-count is-with-size'}>
        {'共 ' + total + ' 个，第 ' + (start + 1) + '–' + (start + shown) + ' 个'}
      </span>
      {onPageSize === undefined ? null : (
        <span className="pager-size">
          <span aria-hidden="true">每页</span>
          <MenuSelect
            groups={[
              { options: sizeOptions.map((size) => ({ value: String(size), label: String(size) })) },
            ]}
            value={String(pageSize)}
            ariaLabel="每页显示多少条"
            triggerText={String(pageSize)}
            triggerClass="pager-size-trigger"
            onSelect={(value: string) => {
              onPageSize(Number(value))
            }}
          />
        </span>
      )}
      {/* One page: everything in this run would be dead — see the note in the header comment. */}
      {pages === 1 ? null : (
        <>
          {step('上一页', current - 1, current === 1)}
          {numbers.map((number, index) => {
            if (number === null) {
              return (
                <span className="is-gap" key={'gap' + index}>
                  …
                </span>
              )
            }
            if (number === current) {
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
          {step('下一页', current + 1, current === pages)}
        </>
      )}
    </nav>
  )
}

export { PAGE_SIZE_DEFAULT }
