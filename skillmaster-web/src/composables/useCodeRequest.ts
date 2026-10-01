import { computed, ref } from 'vue'
import type { Field } from '../api/errors'
import { codeMessage, fieldErrors as mapFieldErrors, messageFor } from '../api/errors'
import type { ApiResult } from '../api/types'
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

/** The first half of both flows that send a text message: a phone number, a captcha, and a code. */
export function useCodeRequest(
  sendCode: (body: {
    phone: string
    captcha_id: string
    captcha_answer: string
  }) => Promise<ApiResult<void>>,
) {
  const captcha = useCaptcha()
  const phone = ref('')
  const captchaAnswer = ref('')
  const code = ref('')
  const sent = ref(false)
  const sending = ref(false)
  const problems = ref<Partial<Record<Field, string>>>({})
  const banner = ref<string | null>(null)
  const { remaining, start } = useCountdown()

  const canSend = computed(() => !sending.value && remaining.value === 0)

  function clearMessages(): void {
    problems.value = {}
    banner.value = null
  }

  /**
   * Asks for a code.
   *
   * The captcha is replaced on every path that could have spent or invalidated it: after a code was
   * actually sent (single use), after a refusal naming the captcha field, and after a throttled
   * request, where the server may have counted the attempt before refusing it.
   */
  async function requestCode(): Promise<void> {
    clearMessages()
    const local: Partial<Record<Field, string>> = {}
    const phoneProblem = phoneIssue(phone.value)
    if (phoneProblem !== null) {
      local.phone = messageFor('phone', phoneProblem, '')
    }
    if (isBlank(captchaAnswer.value)) {
      local.captcha = messageFor('captcha', 'required', '')
    }
    if (Object.keys(local).length > 0) {
      problems.value = local
      return
    }

    sending.value = true
    const result = await sendCode({
      phone: phone.value,
      captcha_id: captcha.id.value,
      captcha_answer: captchaAnswer.value,
    })
    sending.value = false

    if (result.ok) {
      sent.value = true
      captchaAnswer.value = ''
      start(SMS_COOLDOWN_SECONDS)
      await captcha.refresh()
      return
    }

    const serverMessage = codeMessage(result.code, result.message)
    problems.value = mapFieldErrors(result.details, serverMessage)
    const throttled = result.code === 'too_many_requests'
    const unknownOutcome = result.code === 'timeout' || result.code === 'network_error'
    if (throttled) {
      // The sentence alone: how long to wait is what the countdown beside the button is for, and
      // saying it twice read as "请稍后再试。请 30 秒后再试。"
      banner.value = serverMessage
      start(result.retryAfterSeconds ?? SMS_COOLDOWN_SECONDS)
    } else if (unknownOutcome) {
      // Given up on rather than answered, and on these two endpoints that is not the same as
      // "nothing happened": the server spends the captcha and only then waits on the SMS provider,
      // so the message may be on its way. The code step opens anyway — somebody holding a code that
      // did arrive has no other way to use it — and the captcha is replaced so that a resend is not
      // refused for the wrong reason. Getting this wrong costs one message, and the server's own
      // minute-long cooldown is what bounds it.
      sent.value = true
      // Cleared with the image: the answer on screen was for the picture that is now gone.
      captchaAnswer.value = ''
      banner.value = '没能确认短信是否发出。如果稍后收到了，直接填在下面就好。'
    } else if (Object.keys(problems.value).length === 0) {
      banner.value = serverMessage
    }
    if (problems.value.captcha !== undefined || throttled || unknownOutcome) {
      await captcha.refresh()
    }
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
    captchaAnswer,
    captchaImage: captcha.image,
    captchaError: captcha.error,
    refreshCaptcha: captcha.refresh,
    requestCode,
  }
}
