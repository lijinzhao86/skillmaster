/**
 * **危险操作一律弹窗确认（2026-10-19 定）。**
 *
 * It answers one question: what does this step take away. Cancel is the default focus; an action
 * that needs a reason gets the field in the sheet, and confirm stays disabled while it is empty;
 * Escape and Cancel are the same answer.
 *
 * **Waiting, success and failure all happen in this one sheet.** After confirm the button says what
 * it is doing, both answers are disabled and Escape does not close it; success closes it and the
 * page updates in place; failure writes the reason under the body, restores the buttons, and the
 * same sheet retries.
 *
 * **A batch runs inside this sheet too** (v18). The list a person just approved becomes the list they
 * watch: each line lands on its own, the ones already done keep their version number, the one that
 * failed keeps its reason, and the sheet settles on a count instead of closing on a spinner. Nothing
 * is invented — a line only turns green when its result is in.
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { replace } from '../../address'
import { dialogs, useDialogs } from './store'
import type { BatchRun } from './store'

/** 一条的执行状态。`wait` 是还没轮到，`run` 是正在走。 */
type LineState = 'wait' | 'run' | 'ok' | 'failed'

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

/* 一个**真的在转**的圈：留一段缺口，一转就看得出来方向。没有它，"进行中"和"卡住了"长得一样。 */
const SPIN = (
  <svg className="confirm-spin" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="1.8" opacity=".25" />
    <path d="M8 2a6 6 0 0 1 6 6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
  </svg>
)

export default function DangerConfirm() {
  const { danger } = useDialogs()
  const open = danger !== null
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  /** 执行单的三段：确认 → 一条条走 → 结算。普通确认单永远停在第一段。 */
  const [phase, setPhase] = useState<'confirm' | 'running' | 'done'>('confirm')
  /** 已经走完几条。它既是进度，也是"第几条正在走"。 */
  const [at, setAt] = useState(0)
  /** 单子还没滚到底：底边要淡出去一截，否则"下面还有"在 macOS 的浮层滚动条上根本看不出来。 */
  const [more, setMore] = useState(false)
  const input = useRef<HTMLTextAreaElement>(null)
  const accept = useRef<HTMLButtonElement>(null)
  const cancel = useRef<HTMLButtonElement>(null)
  const lines = useRef<HTMLUListElement>(null)

  const field = danger?.field
  const required = !!field && !danger?.optionalField
  const ready = !required || reason.trim() !== ''
  const run: BatchRun | undefined = danger?.run

  /* **Cancel is the default focus, and it is there before the click's task ends.** A `setTimeout(0)`
     here loses a race the old code never had: the press that opened the sheet could be followed by
     an assertion in the same task, and the answer under the reader's fingers is part of the sheet's
     contract (the same rule `askBefore` states). A layout effect runs before the browser paints. */
  useLayoutEffect(() => {
    if (!open) return
    setReason('')
    setError('')
    setPending(false)
    /* **地址里带着快照的，进来就是那一段。** 演示件的每一种样子都要有一个地址（也才拍得进图），
       而一段动画的中间帧不能被"点一下再等 1.8 秒"这种办法拍。 */
    const snap = danger?.run?.snapshot
    setPhase(snap ? (snap.finished ? 'done' : 'running') : 'confirm')
    setAt(snap ? snap.at : 0)
    ;(field ? input.current : cancel.current)?.focus()
  }, [open, danger, field])

  /**
   * **正在走的那一条永远在中间**（2026-10-25 定）。一批十几条时单子比它的框高，不跟着走就会
   * 出现"进度在 9 / 14，眼睛还停在第一条"。所以每落一条，把框滚到让**下一条**落在正中——
   * 看得见刚落定的那条，也看得见接下来那条。快照（`?publish=running`）滚到位就停，不做动画：
   * 一张快照不该在拍它的时候还在动。
   *
   * **结算之后回到顶端**：这一屏的活从"看它跑"变成了"看结果"，而结果要从第一条读起——
   * 跑完停在最后一条上，等于把前面十三条藏在上面。滚不滚得动都是同一个动作，短批次是一次空操作。
   */
  useEffect(() => {
    if (phase === 'confirm') return
    const box = lines.current
    if (!box) return
    const smooth = !run?.snapshot && !window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const behavior = smooth ? 'smooth' : 'auto'
    if (phase === 'done') {
      box.scrollTo({ top: 0, behavior })
      return
    }
    const row = box.querySelector<HTMLElement>('[data-line="' + at + '"]')
    if (!row) return
    const boxBox = box.getBoundingClientRect()
    const rowBox = row.getBoundingClientRect()
    const delta = (rowBox.top - boxBox.top) - (box.clientHeight - rowBox.height) / 2
    box.scrollTo({ top: box.scrollTop + delta, behavior })
  }, [at, phase, run])

  /* 底边淡出只在"下面还有"时出现——到底了还淡，等于骗人往下滚。 */
  useEffect(() => {
    const box = lines.current
    if (!box) return
    const update = () => setMore(box.scrollHeight - box.scrollTop - box.clientHeight > 2)
    update()
    box.addEventListener('scroll', update, { passive: true })
    return () => box.removeEventListener('scroll', update)
  }, [open, phase, at, danger])

  /* **一条一条地走。** 每 `stepMs` 落一条——落的那一条立刻显示它自己的结果（成功带实际号、
     失败带原因），还没轮到的保持预演，正在走的那一条转圈。产品里这里换成"流式返回来一条、
     落一条"，界面一个字不改。 */
  useEffect(() => {
    if (phase !== 'running' || !run || run.snapshot) return
    if (at >= run.outcomes.length) {
      setPhase('done')
      setPending(false)
      return
    }
    const timer = window.setTimeout(() => setAt((done) => done + 1), run.stepMs)
    return () => window.clearTimeout(timer)
  }, [phase, at, run])

  /* **A failed request leaves the reason under the body and the button ready to try again.** The
     focus goes back to it once it is enabled again — see the failure branch. */
  useEffect(() => {
    if (error !== '' && !pending) accept.current?.focus()
  }, [error, pending])

  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      /* 请求已经发出，Esc 不能把结果关在外面；一批正在走时也一样。结算态可以关。 */
      if (event.key !== 'Escape' || pending || phase === 'running') return
      if (phase === 'done') {
        finish()
        return
      }
      dialogs.closeDanger()
      danger?.returnFocus?.focus()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  })

  /** 结算之后离开：这一批已经发生过，页面要按结果更新，所以关单子和回调是同一件事。 */
  const finish = () => {
    dialogs.closeDanger()
    danger?.onConfirm(field ? reason.trim() : '')
    danger?.focusAfter?.focus()
  }

  const confirm = () => {
    if (!danger || pending) return
    const value = field ? reason.trim() : ''
    if (required && value === '') return
    setError('')
    setPending(true)
    /* **批量：这张单子不关，就地开始走。** 走到哪一条、成没成，都在同一张单子上说。 */
    if (run) {
      setAt(0)
      setPhase('running')
      return
    }
    /* 演示件没有服务端：地址带 `fail=1` 时**下一次**请求失败，好让失败那一态能被看见；失败之后
       这一位自己用掉，原地重试就能成功。 */
    const current = new URLSearchParams(window.location.search)
    const failed = current.get('fail') === '1'
    if (failed) {
      current.delete('fail')
      /* Through `replace`, not `history.replaceState`: the page builds its addresses from the
         snapshot `useAddress` keeps, and a write nobody announced would leave that snapshot saying
         `fail=1` for the rest of the visit. */
      replace('?' + current.toString())
    }
    window.setTimeout(() => {
      if (failed) {
        /* The focus lands after the render that re-enables the button: a disabled control cannot
           take focus, and the answer that failed is meant to be under the fingers for a retry. */
        setPending(false)
        setError(danger.failMessage || '没有完成：服务器暂时没有响应，请重试。')
        return
      }
      setPending(false)
      dialogs.closeDanger()
      danger.onConfirm(value)
      danger.focusAfter?.focus()
    }, 600)
  }

  const total = danger?.lines?.length ?? 0
  const done = run ? run.outcomes.filter((outcome) => outcome.ok).length : 0
  const failed = run ? run.outcomes.length - done : 0
  const running = phase === 'running'
  const settled = phase === 'done'

  /** 一条此刻的状态：走完的看它的结果，正走的那一条转圈，其余等轮到。 */
  const lineState = (index: number): LineState => {
    if (!run || phase === 'confirm') return 'wait'
    if (index < at || settled) return run.outcomes[index]?.ok ? 'ok' : 'failed'
    return index === at ? 'run' : 'wait'
  }

  const title = running ? (run?.runningTitle ?? '')
    : settled ? (run?.doneTitle(done, failed) ?? danger?.title ?? '')
    : (danger?.title ?? '')

  return (
    <div className="confirm" id="dangerConfirm" hidden={!open}>
      <div
        className={'confirm-sheet' + (run ? ' has-run' : '')}
        role="dialog"
        aria-modal="true"
        aria-labelledby="dangerConfirmTitle"
        aria-describedby="dangerConfirmBody"
        data-phase={run ? phase : undefined}
      >
        <h2 className="confirm-title" id="dangerConfirmTitle">{title}</h2>
        <p className="confirm-body" id="dangerConfirmBody">
          {settled ? (run?.doneBody(done, failed) ?? '') : running ? '' : (danger?.body ?? '')}
        </p>

        {/* **进度只说真话。** 计数是"已经落地的条数"——不是按时间估的，所以它只会往前走、
           不会卡在 99%；条数就是这张单子上有几行。 */}
        {run && phase !== 'confirm' && (
          <div className="confirm-progress" hidden={settled}>
            <span
              className="confirm-progress-track"
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={total}
              aria-valuenow={at}
              aria-label="已上线的份数"
            >
              <span className="confirm-progress-fill" style={{ width: (total ? (at / total) * 100 : 0) + '%' }}></span>
            </span>
            <span className="confirm-progress-count" id="publishRunCount">{at + ' / ' + total}</span>
          </div>
        )}

        {/* **一张逐条列出的单子，不是一句总数。** 一次放行 N 次上线，人要知道的是哪几条、各变成什么号
            （ADR 0090）。名字走等宽、号靠右对齐，扫一眼就能对上。
            **执行时这张单子不换**：走完的那一条把号换成实际值，没成的把那一格换成原因。 */}
        {danger?.lines && danger.lines.length > 0 && (
          <ul
            className={'confirm-lines' + (more ? ' has-more' : '')}
            id="dangerConfirmLines"
            ref={lines}
            aria-busy={running ? 'true' : undefined}
          >
            {danger.lines.map((line, index) => {
              const state = lineState(index)
              const outcome = run?.outcomes[index]
              const shown = state === 'ok' && outcome?.version ? outcome.version : line.version
              return (
                <li className={'confirm-line is-' + state} key={line.name} data-line={index}>
                  <span className="confirm-line-name">{line.name}</span>
                  {line.note && state !== 'failed' && <span className="confirm-line-note">{line.note}</span>}
                  <span className="spacer"></span>
                  {state !== 'failed' && <span className="confirm-line-version">{shown}</span>}
                  {run && (
                    <span className="confirm-line-state" aria-hidden="true">
                      {state === 'ok' && CHECK}
                      {state === 'failed' && CROSS}
                      {state === 'run' && SPIN}
                    </span>
                  )}
                  {run && (
                    <span className="visually-hidden">
                      {state === 'ok' ? '已上线' : state === 'failed' ? '失败' : state === 'run' ? '正在上线' : '等待中'}
                    </span>
                  )}
                  {/* **原因整行接在下面**（`flex-basis:100%`）：与名字抢宽度就会把 `log-triage ·
                      「重…` 截掉，而"哪一条没成"正是这一行要说的事。 */}
                  {state === 'failed' && (
                    <span className="confirm-line-message">{outcome?.message ?? ''}</span>
                  )}
                </li>
              )
            })}
          </ul>
        )}

        <p className="confirm-error" id="dangerConfirmError" role="alert" hidden={error === ''}>{error}</p>
        <label className="confirm-field" id="dangerConfirmField" hidden={!field}>
          <span className="confirm-label" id="dangerConfirmFieldLabel">{field ?? ''}</span>
          <textarea
            className="confirm-input"
            id="dangerConfirmInput"
            rows={3}
            ref={input}
            value={reason}
            placeholder={danger?.placeholder ?? ''}
            onChange={(event) => setReason(event.target.value)}
          ></textarea>
        </label>
        <div className="confirm-acts" hidden={running}>
          {/* **结算之后没有"取消"**：这一批已经发生过，取消不成立。 */}
          <button
            type="button"
            className="button is-quiet"
            id="dangerConfirmCancel"
            ref={cancel}
            hidden={settled}
            disabled={pending}
            onClick={() => {
              dialogs.closeDanger()
              danger?.returnFocus?.focus()
            }}
          >
            {danger?.cancelLabel ?? '取消'}
          </button>
          <button
            type="button"
            className={danger?.danger === false ? 'button' : 'button is-danger'}
            id="dangerConfirmAccept"
            ref={accept}
            disabled={pending || !ready}
            onClick={settled ? finish : confirm}
          >
            {settled
              ? (run?.doneLabel ?? '完成')
              : pending ? (danger?.pendingLabel ?? '正在处理…') : (danger?.confirmLabel ?? '')}
          </button>
        </div>
        {/* 执行中也要有那一行高度：按钮一消失，弹窗在最后一刻缩一下。 */}
        <div className="confirm-acts" hidden={!running}>
          <button type="button" className="button" id="dangerConfirmRunning" disabled>
            {danger?.pendingLabel ?? '正在处理…'}
          </button>
        </div>
      </div>
    </div>
  )
}
