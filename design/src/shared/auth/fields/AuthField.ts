/**
 * One box's state machine: the rules, the server's second opinion, and what is owed under the box.
 *
 * The last two are kept apart because an edit takes the server's answer away at once, while the
 * rules re-state their own verdict on the next judgement instead of withdrawing it. `note` is stored
 * rather than derived at render time for the same reason the source published it on events — see
 * `FieldState` in `authModel.ts`.
 */

import { useEffect, useRef, useState } from 'react'
import { messageFor } from '../authModel'
import type { FieldName, FieldState, FieldStatus, Issue } from '../authModel'

/* How long the server is left alone after the last keystroke. */
export const VERIFY_DELAY_MS = 400

export interface FieldSpec {
  name: FieldName
  check: (value: string) => Issue | null
  /** A second opinion only the server can give; absent where nothing but the rules can answer. */
  verify?: (value: string) => Issue | null
  /** Whether a value the rules accept earns a tick. False for a captcha: no client can know a
   *  correct answer, so a tick there would promise something nothing has established. */
  tick?: boolean
}

export interface AuthField {
  state: FieldState
  get: () => string
  set: (value: string) => void
  /** Change the value without judging it — a spent captcha or a cleared code box. */
  setQuiet: (value: string) => void
  blur: () => void
  judge: (byEdit: boolean) => void
  clear: () => void
  reset: () => void
  refuseByRule: () => boolean
  refuseByServer: (message: string) => void
  /** The captcha's two refusals, which only the form can decide. */
  setError: (message: string) => void
}

function emptyField(): FieldState {
  return {
    value: '',
    visited: false,
    localMessage: '',
    serverMessage: '',
    serverValue: '',
    ok: false,
    awaiting: false,
    note: { kind: 'none' },
  }
}

export function useAuthField(spec: FieldSpec): AuthField {
  const specRef = useRef(spec)
  specRef.current = spec
  const stateRef = useRef<FieldState>(emptyField())
  const [state, setState] = useState<FieldState>(stateRef.current)
  const timer = useRef<number | null>(null)

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current)
  }, [])

  function apply(next: FieldState): void {
    stateRef.current = next
    setState(next)
  }

  function noteOf(next: FieldState): FieldStatus {
    const refused = next.serverMessage !== '' && next.serverValue === next.value ? next.serverMessage : ''
    const message = next.localMessage || refused
    if (message !== '') return { kind: 'error', message }
    if (next.awaiting) return { kind: 'checking' }
    return next.ok && specRef.current.tick !== false ? { kind: 'ok' } : { kind: 'none' }
  }

  function publish(next: FieldState): FieldState {
    return { ...next, note: noteOf(next) }
  }

  function stopTimer(): void {
    if (timer.current !== null) {
      window.clearTimeout(timer.current)
      timer.current = null
    }
    if (stateRef.current.awaiting) apply({ ...stateRef.current, awaiting: false })
  }

  /** The question the server answers. Synchronous here, because the only thing that can answer is a
   *  three-name list in this file; on the real client this is a request, and the wait in front of it
   *  is all the person ever sees. */
  function ask(): void {
    const asked = stateRef.current.value
    apply(publish({ ...stateRef.current, awaiting: true, ok: false }))
    const issue = specRef.current.verify ? specRef.current.verify(asked) : null
    /* An answer about a value that is no longer in the box is not an answer to anything. */
    if (stateRef.current.value !== asked) return
    /* Two outcomes and no third: there is no unreachable server here, so the real client's
       「没能确认」 state cannot arise. */
    if (issue === null) {
      apply(publish({ ...stateRef.current, awaiting: false, localMessage: '', ok: true }))
    } else {
      apply(publish({
        ...stateRef.current,
        awaiting: false,
        localMessage: messageFor(specRef.current.name, issue),
        ok: false,
      }))
    }
  }

  /* The rules, applied to the value in the box. `byEdit` is who decided the value is finished:
     somebody leaving a field has, and somebody partway through typing one has not — which is the
     whole difference between saying nothing and saying that a value is wrong. */
  function judge(byEdit: boolean): void {
    stopTimer()
    let next = stateRef.current
    if (byEdit) next = { ...next, serverMessage: '', serverValue: '' }
    const issue = specRef.current.check(next.value)
    if (issue === 'required') {
      /* Emptiness belongs to the submit handlers: a box nobody has reached has not failed. */
      if (byEdit) next = { ...next, localMessage: '' }
      apply(publish({ ...next, ok: false }))
      return
    }
    if (issue !== null) {
      const localMessage = (!byEdit || next.visited) ? messageFor(specRef.current.name, issue) : ''
      apply(publish({ ...next, localMessage, ok: false }))
      return
    }
    next = { ...next, localMessage: '' }
    if (specRef.current.verify === undefined) {
      apply(publish({ ...next, ok: true }))
      return
    }
    if (!byEdit) {
      ask()
      return
    }
    /* The question is not put until the typing stops, and that wait is what 「检查中…」 measures. */
    apply(publish({ ...next, awaiting: true, ok: false }))
    timer.current = window.setTimeout(() => {
      timer.current = null
      ask()
    }, VERIFY_DELAY_MS)
  }

  function set(value: string): void {
    apply({ ...stateRef.current, value })
    judge(true)
  }

  function setQuiet(value: string): void {
    apply({ ...stateRef.current, value })
  }

  function blur(): void {
    apply({ ...stateRef.current, visited: true })
    judge(false)
  }

  function clear(): void {
    stopTimer()
    apply({ ...emptyField(), value: stateRef.current.value })
  }

  function reset(): void {
    if (timer.current !== null) {
      window.clearTimeout(timer.current)
      timer.current = null
    }
    apply(emptyField())
  }

  /* The page's own pre-check, applied at submit time to boxes nobody has left. It is the same rule,
     written where a rule's verdict goes — so the next judgement re-states it. */
  function refuseByRule(): boolean {
    const issue = specRef.current.check(stateRef.current.value)
    if (issue === null) return false
    apply(publish({
      ...stateRef.current,
      visited: true,
      localMessage: messageFor(specRef.current.name, issue),
      ok: false,
    }))
    return true
  }

  function refuseByServer(message: string): void {
    apply(publish({
      ...stateRef.current,
      serverMessage: message,
      serverValue: stateRef.current.value,
    }))
  }

  function setError(message: string): void {
    apply(publish({ ...stateRef.current, localMessage: message }))
  }

  return {
    state,
    get: () => stateRef.current.value,
    set,
    setQuiet,
    blur,
    judge,
    clear,
    reset,
    refuseByRule,
    refuseByServer,
    setError,
  }
}
