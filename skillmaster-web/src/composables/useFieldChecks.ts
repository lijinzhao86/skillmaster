import { onUnmounted, ref, watch } from 'vue'
import type { Ref } from 'vue'
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
  /** watched, so what is said about the field follows what is in it */
  value: Ref<string>
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
  dependsOn?: ReadonlyArray<Ref<unknown>>
}

/**
 * The checks that answer before the round trip.
 *
 * <p><strong>A tick is earned as soon as a value is right; a cross is only ever earned by leaving.</strong>
 * The asymmetry is the point. Somebody typing a name has not finished it, and a form that says "only
 * 6–30 characters" after the first keystroke is one people learn to type through — so nothing is said
 * about a value the rules refuse until the field is left, or until that field has been left once
 * already — somebody who has been told what is wrong with a value is told again as they fix it. But a value that is right is right now, and waiting for a blur to say so would leave a form
 * whose button never lights up for somebody whose last field is the one they are still in.
 *
 * <p>Watching the value rather than driving this from the template's `@input`: the value is what the
 * rule is about, so following the value covers every way it can change — typing, pasting, autofill —
 * without depending on whether a listener happens to run before or after the model updates.
 *
 * <p><strong>Three sentences can be owed about one field, and they are kept apart by what produced
 * them.</strong> That is the whole design, and it replaced a single shared slot in which the code had
 * to work out whose sentence it was looking at — which it could not, because two of them can come out
 * word for word the same (`too_common` is one sentence for the server's blocklist and for "built out
 * of your handle"). The three:
 *
 * <ul>
 *   <li>{@link ruled} — what a rule concluded about the value in the box. Re-stated on every
 *       judgement, so it follows the value, including when the rule is re-judged because another value
 *       it reads changed. What a page's own pre-check found goes here too, because it is the same rule
 *       applied at submit time — and for the same reason it is the next judgement that withdraws it.
 *   <li>{@link refused} — what the server refused, each held with the inputs it was refused for, so it
 *       is shown while those are still the values in the boxes and drops out of view when one of them
 *       changes.
 *   <li>{@link unasked} — the sentence for a question that could not be put. Nothing was concluded, so
 *       it is shown only when nothing else is owed.
 * </ul>
 *
 * <p>{@link sentence} exists so that an issue code this version has no words for is still said out
 * loud. `messageFor` would answer such a code with an empty string, and an empty message is worse than
 * no message: it renders as nothing, hides the tick, and leaves a dark button with nothing beside it.
 *
 * <p>An empty field is not one of the findings. Emptiness is stated by the form itself — a placeholder
 * on every required box — and answered once, by the submit handlers. Nothing here can tell "not filled
 * in yet" from "left empty on purpose", and nothing here can say it without also saying it about a
 * field somebody has simply not reached.
 */
export function useFieldChecks(
  problems: Ref<Partial<Record<Field, string>>>,
  rules: Partial<Record<Field, Rule>>,
) {
  /** The fields the person has been in and out of. What a cross needs, and a tick does not. */
  const visited = ref<Partial<Record<Field, true>>>({})

  /** What to show beside each field: a tick, a cross, or that the server is still being asked. */
  const verdicts = ref<Partial<Record<Field, Verdict>>>({})

  /** The debounced questions still in flight, so leaving a field can cancel one and ask at once. */
  const waiting = new Map<Field, ReturnType<typeof setTimeout>>()

  /** What the rules concluded about the values now in the boxes. Re-stated on every judgement. */
  const ruled = ref<Partial<Record<Field, string>>>({})

  /**
   * What the server refused, each held with the inputs it was refused for.
   *
   * The key is what makes this honest rather than a guess. A server's refusal is an answer about the
   * value it was given, so it is shown while that value is still the one in the box and stops being
   * shown once it is not — which is the same lifetime an edit gives it, with one difference: typing a
   * value away and back brings the answer back, because it is the same question. No part of this needs
   * to know who wrote a sentence.
   *
   * It is merged rather than replaced, and that is the other half: a server's use cases stop at the
   * first failure, so an answer about one field can be an answer that never reached the others, and
   * "did not say" is only "did not refuse" when everything was looked at. Nothing is lost by keeping
   * an older refusal, either — a field whose value is unchanged would be refused again.
   */
  const refused = ref<Partial<Record<Field, { message: string; of: string }>>>({})

  /** A question that could not be put. Not a finding, and it never covers one. */
  const unasked = ref<Partial<Record<Field, string>>>({})

  /** Fields whose answer is still being waited for — the third state a field can be shown in. */
  const awaiting = ref<Partial<Record<Field, true>>>({})

  /**
   * What a rule concluded about the value in the box, which the tick hangs on.
   *
   * Three outcomes and not two, because "nothing to say" is one of them: an empty box is not a pass,
   * and neither is a value the rules refuse while somebody is still typing it — that one is only
   * withheld, and the difference between the two is *when* it will be said, not whether it is true.
   */
  const concluded = ref<Partial<Record<Field, Conclusion>>>({})

  onUnmounted(() => {
    for (const timer of waiting.values()) {
      clearTimeout(timer)
    }
  })

  /**
   * Writes what a field shows, and what that means for the button.
   *
   * The first of the three that is owed wins, in the order they are listed here: the rules' verdict is
   * about the value in the box right now, so it is the most specific thing that can be said; the last
   * submit's finding is about a value that may since have been edited away; and a question that could
   * not be put says nothing at all about the value, so it is the last to be shown.
   */
  /** The wording for an issue, and never the empty string: see the class comment. */
  function sentence(field: Field, issue: string): string {
    return messageFor(field, issue, CHECK_REFUSED_HINT)
  }

  function publish(field: Field): void {
    const fromServer = refused.value[field]
    const served =
      fromServer !== undefined && fromServer.of === rules[field]?.value.value
        ? fromServer.message
        : undefined
    const message = ruled.value[field] ?? served ?? unasked.value[field]
    if (message === undefined) {
      delete problems.value[field]
    } else {
      problems.value[field] = message
    }
    // A field something is still being said about is not fine, whatever the rules concluded: until
    // what was said is taken away, a tick beside it would be the form contradicting itself. And a box
    // the rules have nothing to say about is not fine either — empty is not a pass.
    delete verdicts.value[field]
    if (message !== undefined) {
      verdicts.value[field] = 'problem'
    } else if (awaiting.value[field] === true) {
      verdicts.value[field] = 'checking'
    } else if (concluded.value[field] === 'ok') {
      verdicts.value[field] = 'ok'
    }
  }

  /**
   * The value a refusal from the server was an answer about.
   *
   * This field's own, and only that. The server's rule reads other values too — it is the same rule —
   * but a refusal cannot be taken apart into which of them caused it, and the wording usually covers
   * several at once (`too_common` is "too common, or too close to your username or number"). So the
   * conservative reading is the one taken: the answer stands while the value it was about is on screen.
   * A refusal kept past the point where a change to another field would have fixed it is a sentence
   * that is still true of the value — and the submit that fixes it clears the whole form anyway.
   */
  function ofValue(field: Field, sent: Partial<Record<Field, string>>): string {
    return sent[field] ?? rules[field]?.value.value ?? ''
  }

  function publishAll(): void {
    for (const field of Object.keys(rules) as Field[]) {
      publish(field)
    }
  }

  async function ask(field: Field, rule: Rule): Promise<void> {
    const asked = rule.value.value
    awaiting.value[field] = true
    publish(field)

    let issue: string | null | undefined
    try {
      issue = await rule.verify!(asked)
    } catch {
      // A `verify` that throws is one that could not answer, which is exactly what `undefined` means
      // to the code below. Caught rather than let out because the call is fire-and-forget: an
      // unhandled rejection here would leave the field saying 「检查中…」 for the rest of the page's
      // life, and the button waiting for an answer that will never come.
      issue = undefined
    }

    // An answer about a value that is no longer on screen is not an answer to anything. Dropping it
    // is the whole reason the value is captured above: a slow reply for the name somebody typed two
    // edits ago would otherwise land on top of the verdict for the one they have now. Whatever the
    // judgement on the new value did to these three sentences stands.
    if (rule.value.value !== asked) {
      return
    }
    delete awaiting.value[field]

    if (issue === undefined) {
      // Nothing was answered, so nothing is concluded. Said anyway, because the button waits for an
      // answer and a dark button with nothing beside it is a dead end — but kept out of the way of the
      // other two, because failing to put a question must never cover a sentence that answers one: a
      // name the server has already refused is refused whatever this request did.
      unasked.value[field] = COULD_NOT_CHECK_HINT
      delete ruled.value[field]
      publish(field)
      return
    }
    if (issue === null) {
      delete ruled.value[field]
    } else {
      ruled.value[field] = sentence(field, issue)
    }
    publish(field)
  }

  /**
   * @param byEdit true when this field's own value changed, false when the field was left or when a
   *        value the rule merely reads changed. Who decided the value is finished is the whole
   *        difference: somebody leaving a field has, and somebody partway through typing one has not.
   */
  function judge(field: Field, rule: Rule, byEdit: boolean): void {
    const pending = waiting.get(field)
    if (pending !== undefined) {
      clearTimeout(pending)
      waiting.delete(field)
    }

    // An edit is what takes the server's answer out of view, and the key does it: an edited value is
    // no longer one of the values that refusal was about. A question that could not be put goes too —
    // it was about this same value, and something is being concluded about it now.
    delete unasked.value[field]
    delete awaiting.value[field]

    const issue = rule.check(rule.value.value)
    if (issue === 'required') {
      // Emptiness belongs to the submit handlers and the placeholders, and a tick on a box nobody has
      // filled in would say it had been judged and passed. An edit still takes away what was said
      // about the value it replaced — emptying a box is an edit, and 「这个手机号已经注册过了」 under
      // an empty field would be the form answering about a number nobody can see. A blur does not:
      // what was said about an empty box is still true of it.
      if (byEdit) {
        delete ruled.value[field]
      }
      concluded.value[field] = 'silent'
      publish(field)
      return
    }

    if (issue !== null) {
      // Nothing while a value is only being typed: a cross means "I have looked at this and it is
      // wrong", and the person has not said they are finished until they leave.
      if (!byEdit || visited.value[field] === true) {
        ruled.value[field] = sentence(field, issue)
        concluded.value[field] = 'refused'
      } else {
        // Saying nothing is not the same as leaving the last answer standing. What was concluded was
        // about the value this one replaced — a tick earned a keystroke ago, or a spinner waiting for
        // a reply that has already been dropped — and either would be the form describing a value that
        // is not in the box.
        delete ruled.value[field]
        concluded.value[field] = 'silent'
      }
      publish(field)
      return
    }
    delete ruled.value[field]
    concluded.value[field] = 'ok'

    if (rule.verify === undefined) {
      publish(field)
      return
    }
    if (!byEdit) {
      void ask(field, rule)
      return
    }
    // An edit takes away what was said about the value it replaced, now rather than when the answer
    // comes back: the form should not spend a round trip complaining about a name that is no longer in
    // the box. What it says instead is that it is looking — which is also the tick's absence while the
    // button waits, and the reason the button waits.
    awaiting.value[field] = true
    publish(field)
    waiting.set(
      field,
      setTimeout(() => {
        waiting.delete(field)
        void ask(field, rule)
      }, VERIFY_DELAY_MS),
    )
  }

  for (const field of Object.keys(rules) as Field[]) {
    const rule = rules[field]
    if (rule === undefined) {
      continue
    }
    // The value itself, plus anything else the rule reads: a verdict about a combination is not about
    // the field alone, so a change to either side of it re-states what can be said.
    //
    // But only the field's *own* value changing counts as an edit. Editing the username re-states what
    // can be said about the password — it does not mean the password was retyped, so it must not take
    // away what the server said about it. The blocklist refusal is the case that matters: the client
    // cannot tell a blocklisted password from any other, so an erased message would leave a tick over a
    // password the submit is about to refuse again.
    watch([rule.value, ...(rule.dependsOn ?? [])], (values, previous) => {
      judge(field, rule, values[0] !== previous[0])
    })
  }

  return {
    /** The field was left. The one moment a value still being typed can be told it is wrong. */
    blurred(field: Field): void {
      visited.value[field] = true
      const rule = rules[field]
      if (rule !== undefined) {
        judge(field, rule, false)
      }
    },
    /**
     * Records what this page's own pre-check refused, by issue code.
     *
     * These are the same rules, applied at submit time to boxes nobody has left, so they are written
     * where a rule's verdict goes — and the next judgement re-states them. That is what makes a
     * password refused for matching the phone stop being refused the moment the phone is corrected,
     * rather than leaving a sentence the rules can no longer reproduce.
     */
    refusedByRules(refused: Partial<Record<Field, string>>): void {
      for (const field of Object.keys(refused) as Field[]) {
        const issue = refused[field]
        if (issue !== undefined) {
          ruled.value[field] = sentence(field, issue)
          concluded.value[field] = 'refused'
        }
      }
      publishAll()
    },
    /**
     * Records what the server answered about a submit.
     *
     * Merged, not replaced — see {@link refused}. An attempt that stopped before some field has
     * nothing to say about it, whether it stopped in the browser or in the server's own validation,
     * and the two are the same case here on purpose.
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
        if (message === undefined || rules[field] === undefined) {
          continue
        }
        refused.value[field] = { message, of: ofValue(field, sent) }
        recorded = true
      }
      publishAll()
      return recorded
    },
    verdicts,
  }
}
