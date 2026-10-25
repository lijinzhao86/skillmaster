import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'

/**
 * The one confirmation sheet (design's `.confirm` / `#dangerConfirm`).
 *
 * Every action that changes a version or the skill's service goes through it: one title saying what
 * the step is, one body saying what it takes away, **取消 as the default focus**, Esc equal to 取消,
 * and the focus returned to whatever opened it (the caller owns that last part). Waiting, success
 * and failure all happen in this one sheet: the confirm button becomes 「正在…」, both buttons go
 * unavailable, and Esc cannot close a request that is already in flight; a failure leaves the reason
 * under the body and puts the buttons back for a retry.
 *
 * The destructive *tone* is not the same as "this needs confirming": discard is outlined and its
 * confirm button is not red (ADR 0060), while 下架 and 下线 are red. `danger` carries that.
 *
 * **A batch runs inside this sheet too** (v18, ADR 0090). `lines` lists what is about to happen,
 * one line per draft, because 「确定上线 8 个吗」 hides the only informative part: which eight, and
 * what each becomes. `batch` then carries the same sheet through the two phases after 确认 —
 * **running**, where each line lands on its own as its result arrives, and **settled**, where it
 * stops on a count with one 完成 button. Nothing is invented: a line turns green when its result is
 * in, and the number it shows is the one the server assigned, never the prediction.
 */

/** One line of a batch sheet: **which draft, and what it becomes**. */
export interface SheetLine {
  name: string
  note: string
  version: string
}

/** One line's answer; `null` in the outcomes list means "has not reported yet". */
export interface SheetOutcome {
  ok: boolean
  /** The number actually assigned — shown in place of the prediction once known. */
  version?: string | null
  /** Why it failed, drawn under the line rather than fighting the name for width. */
  message?: string | null
}

/** The two phases after 确认. Absent on an ordinary one-step confirmation. */
export interface BatchState {
  phase: 'running' | 'done'
  /** Aligned with `lines`, in the same order. */
  outcomes: (SheetOutcome | null)[]
  runningTitle: string
  doneTitle: string
  doneBody: string
  doneLabel?: string
}

export interface Props {
  open: boolean
  title: string
  body: string
  confirmLabel: string
  /** The safe answer's label. 取消 everywhere except the leave sheet, which says 继续编辑. */
  cancelLabel?: string
  pendingLabel?: string
  /** Red confirm button. `false` for rollback, publish and discard (ADR 0060). */
  danger?: boolean
  /** A field's label; when present the sheet asks for text as well as an answer. */
  field?: string
  /** The field may be left empty (a delist reason does not have to be written). */
  optionalField?: boolean
  placeholder?: string
  pending?: boolean
  error?: string | null
  /** The list a batch is about to act on (ADR 0090). Absent on an ordinary confirmation. */
  lines?: SheetLine[]
  /** The batch's progress and settlement. Absent until 确认, and absent on an ordinary sheet. */
  batch?: BatchState
  onConfirm: (value: string) => void
  onCancel: () => void
  /** 完成, on a settled batch: the sheet closes and the page catches up with what happened. */
  onDone?: () => void
}

/** The tick, the cross and the one spinner. 16px, drawn where the line's state goes. */
const CHECK = (
  <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <path d="M3.4 8.4l3 3 6.2-6.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)

const CROSS = (
  <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <path d="M4.2 4.2l7.6 7.6M11.8 4.2l-7.6 7.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
  </svg>
)

/** **A circle that is really turning** — the gap is what makes the direction readable. Without it,
 * "in progress" and "stuck" look the same. */
const SPIN = (
  <svg className="confirm-spin" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="1.8" opacity=".25" />
    <path d="M8 2a6 6 0 0 1 6 6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
  </svg>
)

type LineState = 'wait' | 'run' | 'ok' | 'failed'

export default function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel,
  cancelLabel = '取消',
  pendingLabel = '正在处理…',
  danger = true,
  field,
  optionalField = false,
  placeholder = '',
  pending = false,
  error = null,
  lines,
  batch,
  onConfirm,
  onCancel,
  onDone,
}: Props) {
  const cancelButton = useRef<HTMLButtonElement | null>(null)
  const acceptButton = useRef<HTMLButtonElement | null>(null)
  const doneButton = useRef<HTMLButtonElement | null>(null)
  const list = useRef<HTMLUListElement | null>(null)
  const [value, setValue] = useState('')
  const firstRun = useRef(true)
  /** Whether the list still has unrevealed lines below it: the bottom edge fades only then. */
  const [more, setMore] = useState(false)

  const acceptDisabled =
    pending || (field !== undefined && !optionalField && value.trim() === '')

  const running = batch?.phase === 'running'
  const settled = batch?.phase === 'done'
  /** How many lines have reported. It is the progress bar's whole value — never an estimate. */
  const landed = batch === undefined ? 0 : batch.outcomes.filter((outcome) => outcome !== null).length
  const total = lines?.length ?? 0

  useLayoutEffect(() => {
    if (firstRun.current) {
      firstRun.current = false
      return
    }
    if (!open) {
      return
    }
    setValue('')
    // 取消 is the default focus for every one of these, including a sheet that also asks for a
    // reason: the safe answer is the one under the fingers. A batch has no reason field, and its
    // default is 取消 too — 确认上线 is never the thing under the fingers on arrival.
    const frame = requestAnimationFrame(() => cancelButton.current?.focus())
    return () => {
      cancelAnimationFrame(frame)
    }
  }, [open])

  /* **A settled batch hands the focus to 完成**: it is the only thing left to press, and 取消 has
     gone. The focus lands after the render that put the button there. */
  useEffect(() => {
    if (settled) {
      doneButton.current?.focus()
    }
  }, [settled])

  /**
   * **The line being worked on stays in the middle** (2026-10-25). A batch of fifteen is taller than
   * its box, so without this the progress says 9 / 14 while the eye is still on the first line.
   *
   * **Settling scrolls back to the top**: the job has changed from watching it run to reading the
   * result, and the result is read from the first line. A short batch is a no-op either way.
   */
  useEffect(() => {
    const box = list.current
    if (box === null || batch === undefined) {
      return
    }
    // `matchMedia` is not in every environment this runs in (jsdom has no layout, so no media
    // queries): reading it through an optional call keeps the scroll working and the preference
    // honoured wherever the browser does provide it.
    const smooth = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches !== true
    const behavior: ScrollBehavior = smooth ? 'smooth' : 'auto'
    // `scrollTo` is not in every environment the tests run in either (jsdom has no layout);
    // scrolling is a nicety of this sheet, and its absence must not break the batch.
    if (batch.phase === 'done') {
      box.scrollTo?.({ top: 0, behavior })
      return
    }
    const row = box.querySelector<HTMLElement>(`[data-line="${landed}"]`)
    if (row === null) {
      return
    }
    const boxBox = box.getBoundingClientRect()
    const rowBox = row.getBoundingClientRect()
    const delta = rowBox.top - boxBox.top - (box.clientHeight - rowBox.height) / 2
    box.scrollTo?.({ top: box.scrollTop + delta, behavior })
  }, [landed, batch])

  /* 底边淡出只在"下面还有"时出现——到底了还淡，等于骗人往下滚。 */
  useEffect(() => {
    const box = list.current
    if (box === null || !open) {
      return
    }
    const update = (): void => setMore(box.scrollHeight - box.scrollTop - box.clientHeight > 2)
    update()
    box.addEventListener('scroll', update, { passive: true })
    return () => box.removeEventListener('scroll', update)
  }, [open, batch, landed, lines])

  function onKeydown(event: ReactKeyboardEvent<HTMLDivElement>): void {
    // A request already sent cannot be closed from under its own result; neither can a batch that is
    // still running. A settled one can — 完成 is what Escape means there.
    if (event.key !== 'Escape' || pending || running) {
      return
    }
    event.preventDefault()
    if (settled) {
      onDone?.()
      return
    }
    onCancel()
  }

  function confirm(): void {
    if (acceptDisabled) {
      return
    }
    onConfirm(field === undefined ? '' : value.trim())
  }

  if (!open) {
    return null
  }

  /** One line's state: reported ones show their answer, the one being worked on spins, the rest wait. */
  const lineState = (index: number): LineState => {
    if (batch === undefined) {
      return 'wait'
    }
    const outcome = batch.outcomes[index]
    if (outcome !== null && outcome !== undefined) {
      return outcome.ok ? 'ok' : 'failed'
    }
    return batch.phase === 'running' && index === landed ? 'run' : 'wait'
  }

  const shownTitle = running
    ? (batch?.runningTitle ?? '')
    : settled
      ? (batch?.doneTitle ?? title)
      : title
  const shownBody = settled ? (batch?.doneBody ?? '') : running ? '' : body

  return (
    <div className="confirm" hidden={!open} onKeyDown={onKeydown}>
      <div
        // **Wide from the first phase.** The list is four columns before anything runs — a name
        // with a draft name, a kind, a number and a state — and 420px squeezes the draft name to
        // three characters. The design's own comment says "only the run sheet widens", but its code
        // widens every batch sheet; the code is what the drawn screens show, so this follows it.
        className={'confirm-sheet' + (batch === undefined && (lines === undefined || lines.length === 0) ? '' : ' has-run')}
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirmTitle"
        aria-describedby="confirmBody"
        data-phase={batch === undefined ? undefined : batch.phase}
      >
        <h2 id="confirmTitle" className="confirm-title">
          {shownTitle}
        </h2>
        <p id="confirmBody" className="confirm-body">
          {shownBody}
        </p>
        {/* **进度只说真话。** 计数是"已经落地的条数"——不是按时间估的，所以它只会往前走、
            不会卡在 99%；条数就是这张单子上有几行。 */}
        {batch !== undefined && running ? (
          <div className="confirm-progress">
            <span
              className="confirm-progress-track"
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={total}
              aria-valuenow={landed}
              aria-label="已上线的份数"
            >
              <span
                className="confirm-progress-fill"
                style={{ width: (total === 0 ? 0 : (landed / total) * 100) + '%' }}
              ></span>
            </span>
            <span className="confirm-progress-count">{`${landed} / ${total}`}</span>
          </div>
        ) : null}

        {/* **一张逐条列出的单子，不是一句总数**（ADR 0090）。执行时这张单子不换：走完的那一条把
            号换成实际值，没成的把那一格换成原因。 */}
        {lines !== undefined && lines.length > 0 ? (
          <ul
            className={'confirm-lines' + (more ? ' has-more' : '')}
            ref={list}
            // Read aloud while the batch is still going: the list is changing under the reader.
            aria-busy={running ? 'true' : undefined}
          >
            {lines.map((line, index) => {
              const state = lineState(index)
              const outcome = batch?.outcomes[index] ?? null
              const shown = state === 'ok' && outcome?.version ? outcome.version : line.version
              return (
                <li className={'confirm-line is-' + state} key={line.name} data-line={index}>
                  <span className="confirm-line-name">{line.name}</span>
                  {line.note !== '' && state !== 'failed' ? (
                    <span className="confirm-line-note">{line.note}</span>
                  ) : null}
                  <span className="spacer"></span>
                  {state !== 'failed' ? <span className="confirm-line-version">{shown}</span> : null}
                  {batch !== undefined ? (
                    <span className="confirm-line-state" aria-hidden="true">
                      {state === 'ok' ? CHECK : null}
                      {state === 'failed' ? CROSS : null}
                      {state === 'run' ? SPIN : null}
                    </span>
                  ) : null}
                  {batch !== undefined ? (
                    <span className="visually-hidden">
                      {state === 'ok'
                        ? '已上线'
                        : state === 'failed'
                          ? '失败'
                          : state === 'run'
                            ? '正在上线'
                            : '等待中'}
                    </span>
                  ) : null}
                  {/* **原因整行接在下面**：与名字抢宽度就会把 `log-triage · 「重…` 截掉，而"哪一条
                      没成"正是这一行要说的事。 */}
                  {state === 'failed' && outcome?.message ? (
                    <span className="confirm-line-message">{outcome.message}</span>
                  ) : null}
                </li>
              )
            })}
          </ul>
        ) : null}

        {/* **失败的原因写在这张单子下面**（设计的顺序）：它是"这批没有发生"的答案，而清单是"本来
            要做哪些"——先给清单、再给为什么没做，读起来才是先因后果。 */}
        {error !== null ? (
          <p className="confirm-error" role="alert">
            {error}
          </p>
        ) : null}

        {field !== undefined ? (
          <label className="confirm-field">
            <span className="confirm-label">{field}</span>
            <textarea
              className="confirm-input"
              rows={3}
              placeholder={placeholder}
              disabled={pending}
              value={value}
              onChange={(event) => setValue(event.target.value)}
            />
          </label>
        ) : null}

        <div className="confirm-acts" hidden={running}>
          {/* **结算之后没有"取消"**：这一批已经发生过，取消不成立。 */}
          <button
            ref={cancelButton}
            type="button"
            className="button is-quiet"
            hidden={settled}
            disabled={pending}
            onClick={onCancel}
          >
            {cancelLabel}
          </button>
          {settled ? (
            <button ref={doneButton} type="button" className="button" onClick={() => onDone?.()}>
              {batch?.doneLabel ?? '完成'}
            </button>
          ) : (
            <button
              ref={acceptButton}
              type="button"
              className={danger ? 'button is-danger' : 'button'}
              disabled={acceptDisabled}
              onClick={confirm}
            >
              {pending ? pendingLabel : confirmLabel}
            </button>
          )}
        </div>
        {/* 执行中也要有那一行高度：按钮一消失，弹窗在最后一刻缩一下。 */}
        <div className="confirm-acts" hidden={!running}>
          <button type="button" className="button" disabled>
            {pendingLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
