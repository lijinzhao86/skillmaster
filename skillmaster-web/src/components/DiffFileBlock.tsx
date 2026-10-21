import { useEffect, useRef, useState } from 'react'
import { formatSize } from '../format'
import type { ReactNode } from 'react'
import type { DiffFile } from '../api/types'

/**
 * One file of a comparison: its header (path + `+n −m`) and its body (hunk lines, line numbers).
 *
 * The only line renderer since v6: it replaced `DiffView.vue` outright after the small comparison,
 * the version page's 改动 pane and the file tab's block were found to be drawing the same lines
 * three ways. One renderer is one place for the add/remove colours to stay right.
 *
 * **The header and the body are one component**, so the version page's lazy loading can pass its
 * own pending / failed / too-big body through the default slot while keeping the same header.
 *
 * Null counts are said as `—`, never as `+0 −0`: a binary file or one past the server's cap has no
 * countable lines, and zero would be a claim the server did not make (PRD #25).
 */
export interface Props {
  /**
   * The element's id, from the caller.
   *
   * A prop here rather than an attribute that falls through: Vue lets an undeclared attribute land on
   * the root element, and the comparison pane uses that to give each file an anchor. React drops an
   * unknown prop silently, so the one place it is used declares it.
   */
  id?: string
  file: DiffFile
  expanded?: boolean
  /** The reason a file with no hunks was not rendered; the design's own sentence. */
  unrendered?: string
  /** First-screen lines in the version page's 改动 pane; Infinity for the small comparison. */
  initialLimit?: number
  /** The `pending` slot's content; absent draws the component's own sentence. */
  pending?: ReactNode
  onToggle?: () => void
}

/** 「单文件首屏 400 行，之后每次 500」 — the version page's 改动 pane passes 400. */
const STEP = 500

export default function DiffFileBlock({
  id,
  file,
  expanded = true,
  unrendered,
  initialLimit = Number.POSITIVE_INFINITY,
  pending,
  onToggle,
}: Props) {
  const [isOpen, setIsOpen] = useState(expanded)
  const [shown, setShown] = useState(initialLimit)
  const firstRun = useRef(true)

  const added = file.added
  const removed = file.removed
  const countable = added !== null && removed !== null

  const unrenderedText =
    unrendered !== undefined
      ? unrendered
      : file.binary
        ? '二进制文件，不显示内容。'
        : '这个文件的内容没有渲染（太大，或者这次比较的额度用完了）。'

  const tooBig = file.hunks === null && !file.binary && file.size !== null

  function lineClass(line: string): string {
    if (line.startsWith('+')) {
      return 'is-add'
    }
    return line.startsWith('-') ? 'is-del' : ''
  }

  /**
   * Hunks flattened for rendering — body lines only, numbered per file from 1.
   *
   * The design's large-diff block draws one `<pre>` of numbered lines and no `@@` rows; the numbering
   * is per file from 1 rather than the two sides' real line numbers. Hunk headers stay in the model
   * (the small comparison uses them) and are not painted here.
   */
  const rows: Array<{ num: number; text: string }> = []
  let num = 0
  for (const hunk of file.hunks ?? []) {
    for (const line of hunk.lines) {
      num += 1
      rows.push({ num, text: line })
    }
  }

  useEffect(() => {
    if (firstRun.current) {
      firstRun.current = false
      return
    }
    setShown(initialLimit)
    // The budget is reset when the file's hunks are replaced, not when the limit prop changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [file.hunks])

  /**
   * Hunk headers do not count toward the line budget: 「单文件首屏 400 行」 is about lines of the
   * diff, and a header is a label rather than one of them.
   */
  const totalLines = rows.length

  const visibleRows = rows.slice(0, shown)
  const remaining = Math.max(0, totalLines - shown)

  function showMore(): void {
    setShown((value) => value + STEP)
  }

  function toggle(): void {
    setIsOpen(!isOpen)
    onToggle?.()
  }

  return (
    <section id={id} className="vp-file">
      <div className="diff-head vp-file-head">
        <button
          type="button"
          className="vp-file-toggle"
          aria-expanded={isOpen ? 'true' : 'false'}
          onClick={toggle}
        >
          <span className="vp-file-path">{file.relpath}</span>
        </button>
        {countable ? (
          <span className="diff-stats">
            <b className="stat-add">+{added}</b> <b className="stat-del">−{removed}</b>
          </span>
        ) : (
          <span className="diff-stats is-pending">—</span>
        )}
      </div>

      {isOpen ? (
        <div className="vp-file-body">
          {file.hunks === null ? (
            pending !== undefined ? (
              pending
            ) : (
              <p className="diff-pending">
                {tooBig && file.size !== null
                  ? `这个文件太大，没有展开（${formatSize(file.size)}）。`
                  : unrenderedText}
              </p>
            )
          ) : file.hunks.length > 0 ? (
            <>
              {/* One `<pre>` per file, every line a row inside it: the design's contiguous block. A
                  `<pre>` per line would give each line the UA's margin and its own scroll container. */}
              <pre className="file-source diff-body diff-lines">
                {visibleRows.map((row) => (
                  <span key={row.num} className={lineClass(row.text) || undefined}>
                    <b className="diff-num">{row.num}</b>
                    <span className="diff-text">
                      {row.text.startsWith('+') || row.text.startsWith('-') ? (
                        <>
                          <b className="diff-sign">{row.text.charAt(0)}</b>
                          {row.text.slice(1)}
                        </>
                      ) : (
                        row.text
                      )}
                    </span>
                  </span>
                ))}
              </pre>
              {remaining > 0 ? (
                <div className="diff-more">
                  <span>还有 {remaining} 行未显示</span>
                  <button type="button" className="link-button" onClick={showMore}>
                    显示更多（500 行）
                  </button>
                </div>
              ) : null}
            </>
          ) : null}
        </div>
      ) : null}
    </section>
  )
}
