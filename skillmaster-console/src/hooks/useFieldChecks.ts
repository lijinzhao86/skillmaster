import { useEffect, useRef, useState } from 'react'
import type { Dispatch, SetStateAction } from 'react'
import { CHECK_REFUSED_HINT, COULD_NOT_CHECK_HINT, messageFor } from '../api/errors'
import type { Field } from '../api/errors'

/** What the checks concluded about a field, once anything has looked at it. */
export type Verdict = 'checking' | 'ok' | 'problem'

/** What a rule has to say about a value: it passes, it is refused, or it has nothing to say yet. */
type Conclusion = 'ok' | 'refused' | 'silent'

/**
 * How long the server is left alone after the last keystroke.
 *
 * Only for the check that has to be asked (`verify`), and only when a value changed rather than a
 * field being left. Long enough that typing a name is one question instead of one per letter; short
 * enough that the answer is there by the time somebody has read what they typed.
 */
const VERIFY_DELAY_MS = 400

/** One field's rule: the value to follow, and what that value has to satisfy. */
interface Rule {
  /** the value now in the box, so what is said about the field follows what is in it */
  value: string
  /** the server's issue code for a value, or null when it would be accepted */
  check: (value: string) => string | null
  /**
   * A second opinion only the server can give, asked when `check` passes.
   *
   * The reason this is separate from `check` rather than folded into it: `check` is a rule and costs
   * nothing, while this is a request. So it is debounced while somebody types and asked at once when
   * they leave the field, and it is never asked about a value the cheap rules already refused.
   *
   * `undefined` is the third answer, and it means the question could not be put — the server was
   * unreachable or said something unusable.
   */
  verify?: (value: string) => Promise<string | null | undefined>
  /**
   * Other values this rule reads, whose changes make its verdict a claim about something else.
   *
   * The password rule is the one that needs it: it refuses a password built out of the handle or the
   * number, so it is a statement about three values, and editing either of the other two leaves a tick
   * standing over a combination nobody has judged.
   */
  dependsOn?: readonly string[]
}

/**
 * The checks that answer before the round trip.
 *
 * <p><strong>A tick is earned as soon as a value is right; a cross is only ever earned by leaving.</strong>
 * The asymmetry is the point. Somebody typing a name has not finished it, and a form that says "only
 * 6–30 characters" after the first keystroke is one people learn to type through — so nothing is said
 * about a value the rules refuse until the field is left, or until that field has been left once
 * already. But a value that is right is right now, and waiting for a blur to say so would leave a form
 * whose button never lights up for somebody whose last field is the one they are still in.
 *
 * <p><strong>Three sentences can be owed about one field, and they are kept apart by what produced
 * them.</strong> That is the whole design, and it replaced a single shared slot in which the code had
 * to work out whose sentence it was looking at — which it could not, because two of them can come out
 * word for word the same. The three:
 *
 * <ul>
 *   <li>{@link ruled} — what a rule concluded about the value in the box. Re-stated on every
 *       judgement, so it follows the value, including when the rule is re-judged because another value
 *       it reads changed.
 *   <li>{@link refused} — what the server refused, each held with the inputs it was refused for, so it
 *       is shown while those are still the values in the boxes and drops out of view when one of them
 *       changes.
 *   <li>{@link unasked} — the sentence for a question that could not be put. Nothing was concluded, so
 *       it is shown only when nothing else is owed.
 * </ul>
 *
 * <p>An empty field is not one of the findings. Emptiness is stated by the form itself — a placeholder
 * on every required box — and answered once, by the submit handlers.
 *
 * **What changed in the port.** Vue's `watch` fires synchronously on a value change and hands over the
 * previous value; React has no such thing, so the rules are followed by one effect that remembers the
 * last values it saw and works out which field changed. One effect rather than one per field, because
 * the set of rules is a fresh object on every render and a `useEffect` inside a loop would be a hook
 * whose order the framework cannot rely on.
 */
export function useFieldChecks(
  rules: Partial<Record<Field, Rule>>,
  setProblems: Dispatch<SetStateAction<Partial<Record<Field, string>>>>,
) {
  /** The fields the person has been in and out of. What a cross needs, and a tick does not. */
  const visited = useRef<Partial<Record<Field, true>>>({})
  /** What to show beside each field: a tick, a cross, or that the server is still being asked. */
  const [verdicts, setVerdicts] = useState<Partial<Record<Field, Verdict>>>({})
  /** The debounced questions still in flight, so leaving a field can cancel one and ask at once. */
  const waiting = useRef(new Map<Field, ReturnType<typeof setTimeout>>())
  /** What the rules concluded about the values now in the boxes. Re-stated on every judgement. */
  const ruled = useRef<Partial<Record<Field, string>>>({})
  /** What the server refused, each held with the inputs it was refused for. */
  const refused = useRef<Partial<Record<Field, { message: string; of: string }>>>({})
  /** A question that could not be put. Not a finding, and it never covers one. */
  const unasked = useRef<Partial<Record<Field, string>>>({})
  /** Fields whose answer is still being waited for — the third state a field can be shown in. */
  const awaiting = useRef<Partial<Record<Field, true>>>({})
  const concluded = useRef<Partial<Record<Field, Conclusion>>>({})

  // The rules object is rebuilt every render, so the effect reads it through a ref rather than
  // closing over one render's copy — otherwise a debounced `ask` could judge a value that has since
  // moved on.
  const rulesRef = useRef(rules)
  rulesRef.current = rules

  useEffect(() => {
    const timers = waiting.current
    return () => {
      for (const timer of timers.values()) {
        clearTimeout(timer)
      }
    }
  }, [])

  /** The wording for an issue, and never the empty string. */
  function sentence(field: Field, issue: string): string {
    return messageFor(field, issue, CHECK_REFUSED_HINT)
  }

  function publish(field: Field): void {
    const fromServer = refused.current[field]
    const served =
      fromServer !== undefined && fromServer.of === rulesRef.current[field]?.value
        ? fromServer.message
        : undefined
    const message = ruled.current[field] ?? served ?? unasked.current[field]

    setProblems((previous) => {
      const next = { ...previous }
      if (message === undefined) {
        delete next[field]
      } else {
        next[field] = message
      }
      return next
    })

    // A field something is still being said about is not fine, whatever the rules concluded: until
    // what was said is taken away, a tick beside it would be the form contradicting itself. And a box
    // the rules have nothing to say about is not fine either — empty is not a pass.
    setVerdicts((previous) => {
      const next = { ...previous }
      delete next[field]
      if (message !== undefined) {
        next[field] = 'problem'
      } else if (awaiting.current[field] === true) {
        next[field] = 'checking'
      } else if (concluded.current[field] === 'ok') {
        next[field] = 'ok'
      }
      return next
    })
  }

  function publishAll(): void {
    for (const field of Object.keys(rulesRef.current) as Field[]) {
      publish(field)
    }
  }

  /**
   * The value a refusal from the server was an answer about.
   *
   * This field's own, and only that. The server's rule reads other values too — it is the same rule —
   * but a refusal cannot be taken apart into which of them caused it. So the conservative reading is
   * the one taken: the answer stands while the value it was about is on screen.
   */
  function ofValue(field: Field, sent: Partial<Record<Field, string>>): string {
    return sent[field] ?? rulesRef.current[field]?.value ?? ''
  }

  async function ask(field: Field, rule: Rule): Promise<void> {
    const asked = rule.value
    awaiting.current[field] = true
    publish(field)

    let issue: string | null | undefined
    try {
      issue = await rule.verify!(asked)
    } catch {
      // A `verify` that throws is one that could not answer, which is exactly what `undefined` means
      // to the code below. Caught rather than let out because the call is fire-and-forget: an
      // unhandled rejection here would leave the field saying 「检查中…」 for the rest of the page's
      // life.
      issue = undefined
    }

    // An answer about a value that is no longer on screen is not an answer to anything.
    if (rulesRef.current[field]?.value !== asked) {
      return
    }
    delete awaiting.current[field]

    if (issue === undefined) {
      // Nothing was answered, so nothing is concluded. Said anyway, because the button waits for an
      // answer and a dark button with nothing beside it is a dead end — but kept out of the way of the
      // other two.
      unasked.current[field] = COULD_NOT_CHECK_HINT
      delete ruled.current[field]
      publish(field)
      return
    }
    if (issue === null) {
      delete ruled.current[field]
    } else {
      ruled.current[field] = sentence(field, issue)
    }
    publish(field)
  }

  /**
   * @param byEdit true when this field's own value changed, false when the field was left or when a
   *        value the rule merely reads changed. Who decided the value is finished is the whole
   *        difference: somebody leaving a field has, and somebody partway through typing one has not.
   */
  function judge(field: Field, rule: Rule, byEdit: boolean): void {
    const pending = waiting.current.get(field)
    if (pending !== undefined) {
      clearTimeout(pending)
      waiting.current.delete(field)
    }

    // An edit is what takes the server's answer out of view, and the key does it: an edited value is
    // no longer one of the values that refusal was about.
    delete unasked.current[field]
    delete awaiting.current[field]

    const issue = rule.check(rule.value)
    if (issue === 'required') {
      // Emptiness belongs to the submit handlers and the placeholders. An edit still takes away what
      // was said about the value it replaced — emptying a box is an edit.
      if (byEdit) {
        delete ruled.current[field]
      }
      concluded.current[field] = 'silent'
      publish(field)
      return
    }

    if (issue !== null) {
      // Nothing while a value is only being typed: a cross means "I have looked at this and it is
      // wrong", and the person has not said they are finished until they leave.
      if (!byEdit || visited.current[field] === true) {
        ruled.current[field] = sentence(field, issue)
        concluded.current[field] = 'refused'
      } else {
        delete ruled.current[field]
        concluded.current[field] = 'silent'
      }
      publish(field)
      return
    }
    delete ruled.current[field]
    concluded.current[field] = 'ok'

    if (rule.verify === undefined) {
      publish(field)
      return
    }
    if (!byEdit) {
      void ask(field, rule)
      return
    }
    // An edit takes away what was said about the value it replaced, now rather than when the answer
    // comes back: the form should not spend a round trip complaining about a name that is no longer
    // in the box.
    awaiting.current[field] = true
    publish(field)
    waiting.current.set(
      field,
      setTimeout(() => {
        waiting.current.delete(field)
        const current = rulesRef.current[field]
        if (current !== undefined) {
          void ask(field, current)
        }
      }, VERIFY_DELAY_MS),
    )
  }

  // Follow every rule's value (and whatever else it reads). Only the field's *own* value changing
  // counts as an edit: editing the username re-states what can be said about the password, but it
  // does not mean the password was retyped, so it must not take away what the server said about it.
  const watched = (Object.keys(rules) as Field[]).map((field) => {
    const rule = rules[field]
    return [field, rule?.value ?? '', ...(rule?.dependsOn ?? [])] as const
  })
  const signature = JSON.stringify(watched)
  const lastSeen = useRef<Record<string, readonly string[]>>({})

  useEffect(() => {
    for (const [field, own, ...rest] of watched) {
      const now = [own, ...rest]
      const before = lastSeen.current[field]
      lastSeen.current[field] = now
      const rule = rulesRef.current[field]
      if (rule === undefined) {
        continue
      }
      // No previous values means this is the first render: Vue's `watch` does not fire on mount
      // either, and judging here would put a cross under a field nobody has touched.
      if (before === undefined) {
        continue
      }
      const byEdit = before[0] !== now[0]
      if (byEdit || before.slice(1).some((value, index) => value !== now[index + 1])) {
        judge(field, rule, byEdit)
      }
    }
    // `signature` carries every watched value; `watched` is rebuilt each render and would make this
    // effect run on every render if it were a dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature])

  return {
    /** The field was left. The one moment a value still being typed can be told it is wrong. */
    blurred(field: Field): void {
      visited.current[field] = true
      const rule = rulesRef.current[field]
      if (rule !== undefined) {
        judge(field, rule, false)
      }
    },
    /**
     * Records what this page's own pre-check refused, by issue code.
     *
     * These are the same rules, applied at submit time to boxes nobody has left, so they are written
     * where a rule's verdict goes — and the next judgement re-states them.
     */
    refusedByRules(refusedIssues: Partial<Record<Field, string>>): void {
      for (const field of Object.keys(refusedIssues) as Field[]) {
        const issue = refusedIssues[field]
        if (issue !== undefined) {
          ruled.current[field] = sentence(field, issue)
          concluded.current[field] = 'refused'
        }
      }
      publishAll()
    },
    /**
     * Records what the server answered about a submit.
     *
     * Merged, not replaced: a server's use cases stop at the first failure, so an answer about one
     * field can be an answer that never reached the others.
     */
    refusedByServer(
      refusals: Partial<Record<Field, string>>,
      sent: Partial<Record<Field, string>>,
    ): boolean {
      let recorded = false
      for (const field of Object.keys(refusals) as Field[]) {
        const message = refusals[field]
        // A field no rule follows is one no template reads, so a refusal about it would be stored and
        // never shown — the caller is told that, so it can say it somewhere else rather than lose it.
        if (message === undefined || rulesRef.current[field] === undefined) {
          continue
        }
        refused.current[field] = { message, of: ofValue(field, sent) }
        recorded = true
      }
      publishAll()
      return recorded
    },
    verdicts,
  }
}
