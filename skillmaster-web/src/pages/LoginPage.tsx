import { useState } from 'react'
import type { FormEvent } from 'react'
import { go, param } from '../address'
import { login } from '../api/account'
import { codeMessage, fieldErrors as mapFieldErrors, isBannerOnly, messageFor } from '../api/errors'
import type { Field } from '../api/errors'
import { useFieldChecks } from '../hooks/useFieldChecks'
import { isBlank, phoneIssue } from '../validation'
import FieldFeedback from '../components/FieldFeedback'
import FormBanner from '../components/FormBanner'
import PasswordField from '../components/PasswordField'

export default function LoginPage() {
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [problems, setProblems] = useState<Partial<Record<Field, string>>>({})
  const [banner, setBanner] = useState<string | null>(null)

  // Only the phone number. The password has nothing this level can answer: the policy is deliberately
  // not applied on this page — an account whose password predates a rule change would be refused at
  // the door for a rule it never broke — and emptiness is answered at submit like every other
  // required field. So a rule for it would be one that can never fire.
  const { blurred, verdicts } = useFieldChecks(
    { phone: { value: phone, check: phoneIssue } },
    setProblems,
  )

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault()
    setProblems({})
    setBanner(null)
    const local: Partial<Record<Field, string>> = {}
    const phoneProblem = phoneIssue(phone)
    if (phoneProblem !== null) {
      local.phone = messageFor('phone', phoneProblem, '')
    }
    // `isBlank`, not `=== ''`, because eight spaces is not a password anybody typed on purpose — it
    // is what a full-width input method leaves behind, and every other field on this form says so.
    if (isBlank(password)) {
      local.password = messageFor('password', 'required', '')
    }
    if (Object.keys(local).length > 0) {
      setProblems(local)
      return
    }

    setSubmitting(true)
    const result = await login({ phone, password })
    setSubmitting(false)

    if (result.ok) {
      // A full load rather than a client-side page change: the server has just replaced the session
      // id and the CSRF token, and reloading is the simplest way to be consistent with both.
      go(returnTo())
      return
    }

    const serverMessage = codeMessage(result.code, result.message)
    const serverProblems = mapFieldErrors(result.details, serverMessage)
    if (isBannerOnly(result.code)) {
      // One banner, and nothing on a field. The server answers identically for a number nobody
      // registered, a wrong password and a suspended account; hanging this on the password field
      // would quietly confirm that the number exists, which is the whole thing that endpoint avoids.
      setBanner(serverMessage)
    } else if (Object.keys(serverProblems).length > 0) {
      setProblems(serverProblems)
    } else {
      setBanner(serverMessage)
    }
  }

  return (
    <form
      className="card"
      onSubmit={(event) => {
        void submit(event)
      }}
    >
      <h2>登录</h2>
      <FormBanner message={banner} />

      <div className="field">
        <label htmlFor="phone">手机号</label>
        <input
          id="phone"
          value={phone}
          inputMode="numeric"
          autoComplete="tel"
          maxLength={11}
          placeholder="必填"
          aria-required="true"
          onChange={(event) => setPhone(event.target.value)}
          onBlur={() => blurred('phone')}
        />
        <FieldFeedback message={problems.phone} verdict={verdicts.phone} />
      </div>

      <PasswordField
        id="password"
        label="密码"
        value={password}
        onChange={setPassword}
        autoComplete="current-password"
        error={problems.password}
      />

      <button type="submit" disabled={submitting}>
        {submitting ? '登录中…' : '登录'}
      </button>

      <p className="muted">
        还没有账号？<a href="/register">注册</a>　·　<a href="/reset">忘记密码</a>
      </p>
    </form>
  )
}

/** `//login//` and `/login` are the same page, and the check below is about the page. */
function withoutTrailingSlashes(pathname: string): string {
  return pathname.replace(/\/+$/, '')
}

/**
 * Where to go once signed in.
 *
 * M2's authorization endpoint sends a browser here with `return_to` when it needs somebody to sign
 * in first. Only same-site destinations are honoured — a link that could send a visitor to another
 * site after signing in is an open redirect, and this page is exactly the kind that gets used as one.
 *
 * Resolved as a URL and compared by **origin**, not by looking at the first characters. A browser
 * reads `\` as `/` in a URL, so `/\evil.example` begins with a single slash, passes a
 * `startsWith('/') && !startsWith('//')` test, and still lands on another site. Comparing origins is
 * the only test that holds for every spelling of the same destination.
 */
function returnTo(): string {
  const requested = param('return_to')
  if (requested === null) {
    return '/'
  }
  const here = new URL(window.location.href)
  let path: string
  try {
    const target = new URL(requested, here)
    if (target.origin !== here.origin) {
      return '/'
    }
    path = target.pathname + target.search + target.hash
    // This page is never a place to land after signing in, and a value that resolves to it is one
    // that meant nothing: `''` and whitespace resolve to `here` itself, and a bare `?` or `#` to
    // this path without them.
    if (withoutTrailingSlashes(target.pathname) === withoutTrailingSlashes(here.pathname)) {
      return '/'
    }
    // Checked twice, on purpose: once as the URL that came in, and once as the string going out.
    // `location.assign` parses its argument again, and a path that begins with `//` is read by that
    // second parse as a new authority.
    if (new URL(path, here).origin !== here.origin) {
      return '/'
    }
  } catch {
    return '/'
  }
  return path
}
