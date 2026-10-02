import { computed, ref } from 'vue'
import type { Field } from '../api/errors'
import {
  CAPTCHA_NEEDED_HINT,
  codeMessage,
  fieldErrors as mapFieldErrors,
  messageFor,
} from '../api/errors'
import type { ApiResult, ErrorDetail } from '../api/types'
import { isBlank, phoneIssue } from '../validation'
import { useCaptcha } from './useCaptcha'
import { useCountdown } from './useCountdown'

/**
 * How long to tell a person to wait after sending one text message.
 *
 * The server's rule is one per number per minute, counted in fixed windows, so the true wait is
 * somewhere between a second and a minute. A full minute is the honest client-side answer: it is
 * never shorter than the truth.
 */
const SMS_COOLDOWN_SECONDS = 60

/**
 * The first half of both flows that send a text message: a phone number, a captcha, and a code.
 *
 * <p><strong>The captcha is not always there.</strong> Registration's first send from an address needs
 * none and every send after it needs one, and only the server knows which of those a given caller is
 * about to make — the rule is per address per day. So the client shows no captcha until it is told to.
 * It learns that either from a refusal naming the field with `required` — this address had already
 * spent its free send — or because the flow called {@link requireCaptcha} as its sending screen came
 * back into view. The refusal is not a mistake the person made, which is why it gets a sentence saying
 * what to do rather than what went wrong.
 *
 * <p>A flow whose sends always need one says so up front with {@code captchaFromTheStart}, and then
 * none of that machinery is ever reached. Recovery is that flow: it is the one that can take an
 * account away from whoever holds it, so it pays every time.
 */
export function useCodeRequest(
  sendCode: (body: {
    phone: string
    captcha_id: string | null
    captcha_answer: string | null
  }) => Promise<ApiResult<void>>,
  options: { captchaFromTheStart?: boolean } = {},
) {
  const captcha = useCaptcha()
  const phone = ref('')
  const captchaAnswer = ref('')
  const code = ref('')
  const sent = ref(false)
  const sending = ref(false)
  const problems = ref<Partial<Record<Field, string>>>({})
  const banner = ref<string | null>(null)
  /** Whether a captcha is on screen and has to be answered before a send will be accepted. */
  const captchaRequired = ref(options.captchaFromTheStart === true)
  const { remaining, start } = useCountdown()

  const canSend = computed(() => !sending.value && remaining.value === 0)

  if (captchaRequired.value) {
    void captcha.refresh()
  }

  function clearMessages(): void {
    problems.value = {}
    banner.value = null
  }

  /** Whether the server refused because it wanted a captcha and the request carried none. */
  function demandsACaptcha(details: ErrorDetail[]): boolean {
    return details.some((detail) => detail.field === 'captcha' && detail.issue === 'required')
  }

  /**
   * Puts the next send behind a captcha, with a fresh image.
   *
   * Called by the flow that knows when its sending screen comes back into view, and never by the send
   * itself. Doing it there is what the first screen of registration cannot afford: the flag and the
   * step are changed in different ticks, so a field that appears on the way out is rendered for the
   * frame between them — a captcha that flickers into view and vanishes as the step changes.
   */
  async function requireCaptcha(): Promise<void> {
    captchaRequired.value = true
    // Cleared with the image: an answer on screen was for a picture that is about to be replaced.
    captchaAnswer.value = ''
    await captcha.refresh()
  }

  /**
   * Asks for a code, and says whether the code step should now open.
   *
   * <p><strong>The answer is not "did it succeed".</strong> A send whose outcome could not be
   * determined opens the step too — the message may be on its way — and one refusal that is not the
   * caller's fault (a throttled request) does not. So this returns the one thing the caller actually
   * needs to know, rather than leaving it to infer the answer from {@link sent}, which stays true
   * from the first send onward and therefore cannot tell a second send's failure from its success.
   *
   * The captcha is replaced on every path that could have spent or invalidated it: after a code was
   * actually sent (single use), after a refusal naming the captcha field, and after a throttled
   * request, where the server may have counted the attempt before refusing it.
   */
  async function requestCode(): Promise<boolean> {
    clearMessages()
    const local: Partial<Record<Field, string>> = {}
    const phoneProblem = phoneIssue(phone.value)
    if (phoneProblem !== null) {
      local.phone = messageFor('phone', phoneProblem, '')
    }
    // Only when one is on screen. A request that carries no captcha is exactly what the free send is
    // for, and demanding an answer to a picture nobody was shown would be the form refusing itself.
    if (captchaRequired.value && isBlank(captchaAnswer.value)) {
      local.captcha = messageFor('captcha', 'required', '')
    }
    if (Object.keys(local).length > 0) {
      problems.value = local
      return false
    }

    // The number the request carries, kept so a refusal can be filed against it: the box stays
    // editable while the request is in flight, and an answer about a number that has since been
    // replaced would be shown under one the server never looked at.
    const asked = phone.value
    sending.value = true
    const result = await sendCode({
      phone: asked,
      captcha_id: captchaRequired.value ? captcha.id.value : null,
      captcha_answer: captchaRequired.value ? captchaAnswer.value : null,
    })
    sending.value = false

    if (result.ok) {
      sent.value = true
      start(SMS_COOLDOWN_SECONDS)
      if (captchaRequired.value) {
        // A challenge is spent by being used, so this one is replaced for the next send. Only when
        // there was one: a free send consumed nothing to replace, and drawing an image for it would
        // be a request for nothing.
        await requireCaptcha()
      }
      return true
    }

    const serverMessage = codeMessage(result.code, result.message)
    const refusals = mapFieldErrors(result.details, serverMessage)
    if (phone.value !== asked) {
      // The phone's part of the answer is about a number nobody can see any more. What else it named —
      // the captcha — is still about the field it names, so it stays.
      delete refusals.phone
    }
    problems.value = refusals

    if (demandsACaptcha(result.details)) {
      // The answer to a request that carried none. Nothing the person did was wrong — the rule is
      // about the address and the day, and only the server knows where this caller stands in it — so
      // what is shown says what to do rather than reporting a failure. This one is not on the way
      // out: the send did not happen, so the screen stays and the field belongs on it.
      problems.value.captcha = CAPTCHA_NEEDED_HINT
      await requireCaptcha()
      return false
    }

    const throttled = result.code === 'too_many_requests'
    const unknownOutcome = result.code === 'timeout' || result.code === 'network_error'
    if (throttled) {
      // The sentence alone: how long to wait is what the countdown beside the button is for, and
      // saying it twice read as "请稍后再试。请 30 秒后再试。"
      banner.value = serverMessage
      start(result.retryAfterSeconds ?? SMS_COOLDOWN_SECONDS)
      // Only when one was in play. A free send consumed no challenge, so replacing an image nobody
      // was shown would be a request for nothing.
      if (captchaRequired.value) {
        await captcha.refresh()
      }
      return false
    }
    if (unknownOutcome) {
      // Given up on rather than answered, and on these two endpoints that is not the same as
      // "nothing happened": the server spends the captcha and only then waits on the SMS provider,
      // so the message may be on its way. The code step opens anyway — somebody holding a code that
      // did arrive has no other way to use it — and the captcha is replaced so that a resend is not
      // refused for the wrong reason. Getting this wrong costs one message, and the server's own
      // minute-long cooldown is what bounds it.
      sent.value = true
      captchaAnswer.value = ''
      banner.value = '没能确认短信是否发出。如果稍后收到了，直接填在下面就好。'
      await captcha.refresh()
      return true
    }
    if (problems.value.captcha !== undefined) {
      await captcha.refresh()
    } else if (Object.keys(problems.value).length === 0) {
      banner.value = serverMessage
    }
    return false
  }

  // Flat rather than nested: every one of these ends up bound in a template, and a ref reached
  // through another object is a ref the template will not unwrap.
  return {
    phone,
    code,
    sent,
    sending,
    remaining,
    canSend,
    problems,
    banner,
    captchaRequired,
    captchaAnswer,
    captchaImage: captcha.image,
    captchaError: captcha.error,
    refreshCaptcha: captcha.refresh,
    requireCaptcha,
    requestCode,
  }
}
