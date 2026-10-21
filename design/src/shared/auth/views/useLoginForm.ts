/**
 * The login sheet's own state. It does not use the rules machine: an empty box is refused on submit
 * rather than while typing, and there is no tick — the only answers this form has are 「必填」,
 * 「格式」 and the pair's single refusal.
 */

import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { DEMO_LOGIN_PASSWORD, DEMO_REGISTERED_PHONE, DEMO_ROUND_TRIP_MS, PHONE_RE } from '../authModel'

export interface LoginForm {
  phone: string
  password: string
  phoneError: string
  passwordError: string
  formError: string
  pending: boolean
  revealToken: number
  onPhone: (value: string) => void
  onPassword: (value: string) => void
  submit: (event: FormEvent<HTMLFormElement>) => void
  resetErrors: () => void
  resetReveal: () => void
  cancelPending: () => void
  resetAll: () => void
}

interface LoginOptions {
  onSignedIn: () => void
  onCloseDone: () => void
}

function focusById(id: string): void {
  const node = document.getElementById(id)
  if (node !== null) node.focus()
}

export function useLoginForm({ onSignedIn, onCloseDone }: LoginOptions): LoginForm {
  const signedIn = useRef(onSignedIn)
  signedIn.current = onSignedIn
  const closeDone = useRef(onCloseDone)
  closeDone.current = onCloseDone

  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState('')
  const [phoneError, setPhoneError] = useState('')
  const [passwordError, setPasswordError] = useState('')
  const [formError, setFormError] = useState('')
  const [pending, setPending] = useState(false)
  const [revealToken, setRevealToken] = useState(0)

  const pendingRef = useRef(false)
  const timer = useRef<number | null>(null)

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current)
  }, [])

  function resetErrors(): void {
    setPhoneError('')
    setPasswordError('')
    setFormError('')
  }

  function resetReveal(): void {
    setRevealToken((token) => token + 1)
  }

  function cancelPending(): void {
    if (timer.current !== null) {
      window.clearTimeout(timer.current)
      timer.current = null
    }
    pendingRef.current = false
    setPending(false)
  }

  function resetAll(): void {
    cancelPending()
    resetErrors()
    setPhone('')
    setPassword('')
    setRevealToken((token) => token + 1)
  }

  /* Typing clears what the last submit concluded about that field, and the banner with it — the
     banner is about the pair, so it cannot outlive an edit to either half. */
  function onPhone(value: string): void {
    setPhone(value)
    if (phoneError !== '') setPhoneError('')
    if (formError !== '') setFormError('')
  }

  function onPassword(value: string): void {
    setPassword(value)
    if (passwordError !== '') setPasswordError('')
    if (formError !== '') setFormError('')
  }

  function submit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    if (pendingRef.current) return

    resetErrors()

    const phoneValue = phone.trim()
    let firstBad: 'phone' | 'password' | null = null

    if (!phoneValue) {
      setPhoneError('这一项必填。')
      firstBad = 'phone'
    } else if (!PHONE_RE.test(phoneValue)) {
      setPhoneError('请填写 11 位的大陆手机号。')
      firstBad = 'phone'
    }

    if (!password) {
      setPasswordError('这一项必填。')
      if (firstBad === null) firstBad = 'password'
    }

    if (firstBad !== null) {
      focusById(firstBad)
      return
    }

    pendingRef.current = true
    setPending(true)

    timer.current = window.setTimeout(() => {
      timer.current = null
      pendingRef.current = false
      setPending(false)

      if (phoneValue === DEMO_REGISTERED_PHONE && password === DEMO_LOGIN_PASSWORD) {
        signedIn.current()
        closeDone.current()
        return
      }

      /* One sentence for every way the pair can be wrong. Hanging it on the password field would
         tell an attacker which numbers exist. */
      setFormError('手机号或密码不正确。')
      focusById('phone')
    }, DEMO_ROUND_TRIP_MS)
  }

  return {
    phone,
    password,
    phoneError,
    passwordError,
    formError,
    pending,
    revealToken,
    onPhone,
    onPassword,
    submit,
    resetErrors,
    resetReveal,
    cancelPending,
    resetAll,
  }
}
