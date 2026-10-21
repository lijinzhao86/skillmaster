/**
 * Register is one view in two steps, and the second has no address of its own — the number the code
 * went to lives in this dialog, so a visitor arriving at a second screen directly would have no code
 * and no number to explain it.
 *
 * Nothing unmounts between the steps, which is why the values typed on the first are still there
 * when a refused send puts somebody back on it.
 */

import { useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  CAPTCHAS,
  DEMO_COOLDOWN_SECONDS,
  DEMO_REGISTERED_PHONE,
  DEMO_ROUND_TRIP_MS,
  DEMO_SMS_CODE,
  captchaIssue,
  captchaMatches,
  captchaSource,
  codeIssue,
  isBlank,
  messageFor,
  passwordIssue,
  phoneIssue,
  usernameIssue,
  verifyUsername,
} from '../authModel'
import { useAuthField } from '../fields/AuthField'
import type { AuthField } from '../fields/AuthField'

export type RegisterStep = 'registerCode' | 'registerDone'

export interface RegisterFlow {
  username: AuthField
  phone: AuthField
  password: AuthField
  captcha: AuthField
  code: AuthField
  captchaSrc: string
  captchaRequired: boolean
  nextDisabled: boolean
  nextLabel: string
  submitDisabled: boolean
  submitLabel: string
  codeHint: string
  revealToken: number
  onUsername: (value: string) => void
  onPhone: (value: string) => void
  next: (event: FormEvent<HTMLFormElement>) => void
  submitCode: (event: FormEvent<HTMLFormElement>) => void
  refreshCaptcha: () => void
  reset: () => void
}

export function useRegisterFlow(onAdvance: (step: RegisterStep) => void): RegisterFlow {
  const advance = useRef(onAdvance)
  advance.current = onAdvance

  /* The password's rule reads two other boxes; the ref is written synchronously so the check sees
     the value that was just typed, the way the source read the DOM. */
  const values = useRef({ username: '', phone: '' })

  const username = useAuthField({ name: 'username', check: usernameIssue, verify: verifyUsername })
  const phone = useAuthField({ name: 'phone', check: phoneIssue })
  const password = useAuthField({
    name: 'password',
    /* Against the other two boxes, because that is what the server does: a password built out of the
       handle or the number is refused whatever else is right about it. */
    check: (value) => passwordIssue(value, values.current.username, values.current.phone),
  })
  const captcha = useAuthField({ name: 'captcha', check: captchaIssue, tick: false })
  const code = useAuthField({ name: 'code', check: codeIssue })

  const [captchaIndex, setCaptchaIndex] = useState(0)
  const [sent, setSent] = useState(false)
  const [sending, setSending] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [remaining, setRemaining] = useState(0)
  const [codePhone, setCodePhone] = useState('')
  const [captchaRequired, setCaptchaRequired] = useState(false)
  const [revealToken, setRevealToken] = useState(0)

  const sendTimer = useRef<number | null>(null)
  const submitTimer = useRef<number | null>(null)
  const cooldown = useRef<number | null>(null)

  function stopCooldown(): void {
    if (cooldown.current !== null) {
      window.clearInterval(cooldown.current)
      cooldown.current = null
    }
  }

  /* A courtesy, not a control: whatever this says, the server refuses on its own clock. */
  function startCooldown(): void {
    stopCooldown()
    setRemaining(DEMO_COOLDOWN_SECONDS)
    cooldown.current = window.setInterval(() => {
      setRemaining((value) => (value > 0 ? value - 1 : 0))
    }, 1000)
  }

  useEffect(() => {
    if (remaining <= 0) stopCooldown()
  }, [remaining])

  useEffect(() => () => {
    if (sendTimer.current !== null) window.clearTimeout(sendTimer.current)
    if (submitTimer.current !== null) window.clearTimeout(submitTimer.current)
    stopCooldown()
  }, [])

  /* A new challenge. The answer on screen was for the picture being replaced, so it goes with it —
     what is left to the caller is any sentence about the old one, which is not this function's to
     write or to withdraw. */
  function newCaptcha(): void {
    captcha.setQuiet('')
    setCaptchaIndex((index) => (index + 1) % CAPTCHAS.length)
  }

  /* A challenge is spent by being used, so a send that went through leaves a fresh one behind — and
     nothing to say about the one it spent. */
  function spendCaptcha(): void {
    newCaptcha()
    captcha.clear()
  }

  function onUsername(value: string): void {
    values.current.username = value
    username.set(value)
    /* The password's rule reads another box, so a change to that box re-states what can be said
       about it. */
    password.judge(false)
  }

  function onPhone(value: string): void {
    values.current.phone = value
    phone.set(value)
    password.judge(false)
  }

  /* Ticked before it can be pressed, and the last box somebody is in is the one they never leave —
     so a button that waited for a blur would stay dark for a form that is complete. The captcha is
     asked for by being filled rather than by a tick, because nothing local can know a correct
     answer. */
  const ready =
    username.state.ok === true &&
    phone.state.ok === true &&
    password.state.ok === true &&
    phone.state.serverMessage === '' &&
    (!captchaRequired || !isBlank(captcha.state.value))

  const nextDisabled = !ready || sending || remaining > 0

  const nextLabel = sending
    ? '发送中…'
    : remaining > 0
      /* A wait either way, but not the same wait: a first attempt refused for being too soon has sent
         nothing, and calling that a resend promises a message that never went out. */
      ? (sent ? remaining + ' 秒后可重发' : remaining + ' 秒后可重试')
      : (sent ? '重新发送验证码' : '下一步')

  const submitDisabled = submitting || code.state.ok !== true
  const submitLabel = submitting ? '注册中…' : '注册'
  const codeHint =
    codePhone === ''
      ? ''
      : '验证码已发送至 ' + codePhone + '。' +
        (remaining > 0 ? remaining + ' 秒后可以重新发送。' : '没收到可以重新获取。')

  const captchaSrc = useMemo(() => captchaSource(CAPTCHAS[captchaIndex]), [captchaIndex])

  function next(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    if (nextDisabled) return

    let refused = false
    if (username.refuseByRule()) refused = true
    if (phone.refuseByRule()) refused = true
    if (password.refuseByRule()) refused = true
    if (refused) return

    /* A challenge's answer is judged here because nothing local can judge it, and a challenge is
       spent whether the answer was right or wrong. */
    if (captchaRequired) {
      if (isBlank(captcha.get())) {
        captcha.setError(messageFor('captcha', 'required'))
        return
      }
      if (!captchaMatches(captchaIndex, captcha.get())) {
        newCaptcha()
        captcha.setError(messageFor('captcha', 'invalid'))
        return
      }
    }

    /* The answer the send itself gives: the number is well-formed, and it is the server that says it
       already has an account. */
    if (phone.get() === DEMO_REGISTERED_PHONE) {
      phone.refuseByServer(messageFor('phone', 'already_registered'))
      return
    }

    setSending(true)
    sendTimer.current = window.setTimeout(() => {
      sendTimer.current = null
      setSending(false)
      setSent(true)
      setCodePhone(phone.get())
      startCooldown()
      /* A free send is spent by going out, so the next one needs a captcha — which is what this send
         has just spent. */
      setCaptchaRequired(true)
      spendCaptcha()
      advance.current('registerCode')
    }, DEMO_ROUND_TRIP_MS)
  }

  function submitCode(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    if (submitDisabled) return
    if (code.refuseByRule()) return

    const sentCode = code.get()
    setSubmitting(true)
    submitTimer.current = window.setTimeout(() => {
      submitTimer.current = null
      setSubmitting(false)
      if (sentCode !== DEMO_SMS_CODE) {
        /* This one carries no details, but it is plainly about the field it sits next to — which is
           where the real client files it too, rather than in the banner. */
        code.refuseByServer(messageFor('code', 'verification_code_invalid'))
        return
      }
      advance.current('registerDone')
    }, DEMO_ROUND_TRIP_MS)
  }

  function reset(): void {
    if (sendTimer.current !== null) {
      window.clearTimeout(sendTimer.current)
      sendTimer.current = null
    }
    if (submitTimer.current !== null) {
      window.clearTimeout(submitTimer.current)
      submitTimer.current = null
    }
    stopCooldown()
    setRemaining(0)
    setSent(false)
    setSending(false)
    setSubmitting(false)
    setCodePhone('')
    setCaptchaRequired(false)
    values.current = { username: '', phone: '' }
    username.reset()
    phone.reset()
    password.reset()
    captcha.reset()
    code.reset()
    setRevealToken((token) => token + 1)
  }

  return {
    username,
    phone,
    password,
    captcha,
    code,
    captchaSrc,
    captchaRequired,
    nextDisabled,
    nextLabel,
    submitDisabled,
    submitLabel,
    codeHint,
    revealToken,
    onUsername,
    onPhone,
    next,
    submitCode,
    refreshCaptcha: newCaptcha,
    reset,
  }
}
