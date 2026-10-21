/**
 * Recovery is a walk of three, not one screen: the number and the picture buy the message, the
 * message proves the number, and only then is a new password worth asking for. Each screen knows one
 * thing.
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
} from '../authModel'
import { useAuthField } from '../fields/AuthField'
import type { AuthField } from '../fields/AuthField'

export type ResetStep = 'resetCode' | 'resetPassword' | 'resetDone'

export interface ResetFlow {
  phone: AuthField
  captcha: AuthField
  code: AuthField
  password: AuthField
  captchaSrc: string
  nextDisabled: boolean
  nextLabel: string
  submitDisabled: boolean
  submitLabel: string
  codeHint: string
  revealToken: number
  onPhone: (value: string) => void
  next: (event: FormEvent<HTMLFormElement>) => void
  codeChanged: (value: string) => void
  /** The code screen was left without an answer: its judgement must not arrive on another screen. */
  abandonCode: () => void
  submit: (event: FormEvent<HTMLFormElement>) => void
  refreshCaptcha: () => void
  spendCaptcha: () => void
  reset: () => void
}

export function useResetFlow(onAdvance: (step: ResetStep) => void): ResetFlow {
  const advance = useRef(onAdvance)
  advance.current = onAdvance

  const values = useRef({ phone: '' })

  const phone = useAuthField({ name: 'phone', check: phoneIssue })
  const captcha = useAuthField({ name: 'captcha', check: captchaIssue, tick: false })
  const code = useAuthField({ name: 'code', check: codeIssue })
  const password = useAuthField({
    name: 'password',
    /* No handle here — a reset cannot change it, so there is none for the password to be too close
       to. The number still counts. */
    check: (value) => passwordIssue(value, '', values.current.phone),
  })

  const [captchaIndex, setCaptchaIndex] = useState(0)
  const [sending, setSending] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [remaining, setRemaining] = useState(0)
  const [codePhone, setCodePhone] = useState('')
  const [revealToken, setRevealToken] = useState(0)

  const sendTimer = useRef<number | null>(null)
  const submitTimer = useRef<number | null>(null)
  const verifyTimer = useRef<number | null>(null)
  const verifying = useRef(false)
  const cooldown = useRef<number | null>(null)

  function stopCooldown(): void {
    if (cooldown.current !== null) {
      window.clearInterval(cooldown.current)
      cooldown.current = null
    }
  }

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
    if (verifyTimer.current !== null) window.clearTimeout(verifyTimer.current)
    stopCooldown()
  }, [])

  /* A new challenge; the answer goes with the picture it was typed for. */
  function newCaptcha(): void {
    captcha.setQuiet('')
    setCaptchaIndex((index) => (index + 1) % CAPTCHAS.length)
  }

  function spendCaptcha(): void {
    newCaptcha()
    captcha.clear()
  }

  function onPhone(value: string): void {
    values.current.phone = value
    phone.set(value)
    password.judge(false)
  }

  const nextDisabled = sending || remaining > 0
  const nextLabel = sending
    ? '发送中…'
    : (remaining > 0 ? remaining + ' 秒后可重发' : '下一步')

  const submitDisabled = submitting
  const submitLabel = submitting ? '提交中…' : '重置密码'
  const codeHint =
    codePhone === ''
      ? ''
      : '验证码已发送至 ' + codePhone + '。' +
        (remaining > 0 ? remaining + ' 秒后可以重新发送。' : '没收到可以重新获取。')

  const captchaSrc = useMemo(() => captchaSource(CAPTCHAS[captchaIndex]), [captchaIndex])

  /* Buying the message. The picture is asked for here and nowhere else: it stands in front of the
     send, and every send needs a fresh one. */
  function next(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    if (nextDisabled) return

    let refused = false
    if (phone.refuseByRule()) refused = true
    if (isBlank(captcha.get())) {
      captcha.setError(messageFor('captcha', 'required'))
      refused = true
    }
    if (refused) return

    if (!captchaMatches(captchaIndex, captcha.get())) {
      newCaptcha()
      captcha.setError(messageFor('captcha', 'invalid'))
      return
    }

    /* The send never says whether a number has an account — that would be an oracle, and one
       question answered differently on two endpoints is how one is built. Recovery is where that
       answer has to come from, so it comes at the first question rather than at the last. */
    if (phone.get() !== DEMO_REGISTERED_PHONE) {
      phone.refuseByServer(messageFor('phone', 'no_account'))
      return
    }

    setSending(true)
    setCodePhone(phone.get())
    sendTimer.current = window.setTimeout(() => {
      sendTimer.current = null
      setSending(false)
      startCooldown()
      spendCaptcha()
      advance.current('resetCode')
    }, DEMO_ROUND_TRIP_MS)
  }

  /* This screen has no button, so it is judged the moment it holds six digits. A refusal empties the
     boxes: leaving six wrong digits sitting there would be a field that looks answered and has
     nothing left to type into. */
  function codeChanged(value: string): void {
    code.clear()
    if (value.length !== 6 || verifying.current) return

    const tried = value
    verifying.current = true
    verifyTimer.current = window.setTimeout(() => {
      verifyTimer.current = null
      verifying.current = false
      if (tried !== DEMO_SMS_CODE) {
        code.refuseByServer(messageFor('code', 'verification_code_invalid'))
        code.setQuiet('')
        return
      }
      advance.current('resetPassword')
    }, DEMO_ROUND_TRIP_MS)
  }

  /* Leaving this screen cancels the judgement it owed. The timer below outlives a click on
     「重新获取验证码」 otherwise, and would then refuse a field — or advance past a screen — that
     nobody is standing on. */
  function abandonCode(): void {
    if (verifyTimer.current !== null) {
      window.clearTimeout(verifyTimer.current)
      verifyTimer.current = null
    }
    verifying.current = false
  }

  function submit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    if (submitDisabled) return
    if (password.refuseByRule()) return

    setSubmitting(true)
    submitTimer.current = window.setTimeout(() => {
      submitTimer.current = null
      setSubmitting(false)
      /* Deliberately not signed in: signing in with the new password is also what proves to the
         person that the one they chose is the one that works. */
      advance.current('resetDone')
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
    if (verifyTimer.current !== null) {
      window.clearTimeout(verifyTimer.current)
      verifyTimer.current = null
    }
    verifying.current = false
    stopCooldown()
    setRemaining(0)
    setSending(false)
    setSubmitting(false)
    setCodePhone('')
    values.current = { phone: '' }
    phone.reset()
    captcha.reset()
    code.reset()
    password.reset()
    setRevealToken((token) => token + 1)
  }

  return {
    phone,
    captcha,
    code,
    password,
    captchaSrc,
    nextDisabled,
    nextLabel,
    submitDisabled,
    submitLabel,
    codeHint,
    revealToken,
    onPhone,
    next,
    codeChanged,
    abandonCode,
    submit,
    refreshCaptcha: newCaptcha,
    spendCaptcha,
    reset,
  }
}
