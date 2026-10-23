import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { requestResetCode, resetPassword } from '../api/account'
import { codeMessage, fieldErrors as mapFieldErrors, PASSWORD_HINT } from '../api/errors'
import type { Field } from '../api/errors'
import { useCodeRequest } from '../hooks/useCodeRequest'
import { useFieldChecks } from '../hooks/useFieldChecks'
import { codeIssue, passwordIssue, phoneIssue } from '../validation'
import CaptchaField from '../components/CaptchaField'
import CodeField from '../components/CodeField'
import FieldFeedback from '../components/FieldFeedback'
import FormBanner from '../components/FormBanner'
import PasswordField from '../components/PasswordField'

export default function ResetPage() {
  // Recovery's sends always need a captcha, so this flow shows one from the start rather than waiting
  // to be told — registration's first send is the one that goes without, and it is not this one.
  const step = useCodeRequest(requestResetCode, { captchaFromTheStart: true })

  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)
  const [problems, setProblems] = useState<Partial<Record<Field, string>>>({})
  const [banner, setBanner] = useState<string | null>(null)

  const { blurred, refusedByRules, refusedByServer, verdicts } = useFieldChecks(
    {
      phone: { value: step.phone, check: phoneIssue },
      // No username here — a reset cannot change the handle, so there is none to be too close to. The
      // number still counts, so the password's verdict has to follow it.
      password: {
        value: password,
        check: (value) => passwordIssue(value, '', step.phone),
        dependsOn: [step.phone],
      },
      code: { value: step.code, check: codeIssue },
    },
    setProblems,
  )

  /**
   * The step's own messages belong to the value that was submitted, so a change to that value takes
   * them away — the rule the field checks apply to this page's map. See the register page.
   *
   * One effect per box: a change to one must not take away what the other is still saying.
   */
  const { dismiss: dismissStepProblem } = step
  useEffect(() => {
    dismissStepProblem('phone')
  }, [step.phone, dismissStepProblem])
  useEffect(() => {
    dismissStepProblem('captcha')
  }, [step.captchaAnswer, dismissStepProblem])

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault()
    setBanner(null)
    const local: Partial<Record<Field, string>> = {}
    const passwordProblem = passwordIssue(password, '', step.phone)
    if (passwordProblem !== null) {
      local.password = passwordProblem
    }
    const codeProblem = codeIssue(step.code)
    if (codeProblem !== null) {
      local.code = codeProblem
    }
    // Issue codes, handed to the checks — see the register page. Nothing needs clearing first: the
    // checks own what each field shows, and an attempt that stops here can only speak about the
    // fields it actually looked at.
    if (Object.keys(local).length > 0) {
      refusedByRules(local)
      return
    }

    // One object, used both as the body and as the record of what was sent — see the register page.
    const sent = { phone: step.phone, code: step.code, password }
    setSubmitting(true)
    const result = await resetPassword(sent)
    setSubmitting(false)

    if (result.ok) {
      // Deliberately not signed in: every session this account had has just been revoked, including
      // the one that asked for this. Signing in with the new password is also what proves to the
      // person that the password they chose is the one that works.
      setDone(true)
      return
    }

    const serverMessage = codeMessage(result.code, result.message)
    const serverProblems = mapFieldErrors(result.details, serverMessage)
    if (Object.keys(serverProblems).length > 0) {
      if (!refusedByServer(serverProblems, sent)) {
        setBanner(serverMessage)
      }
    } else if (result.code === 'verification_code_invalid') {
      refusedByServer({ code: serverMessage }, sent)
    } else {
      setBanner(serverMessage)
    }
  }

  if (done) {
    return (
      <div className="card">
        <h2>密码已重置</h2>
        <p>请用新密码重新登录。出于安全考虑，这个账号在所有设备上的登录都已经失效。</p>
        <p>
          <a href="/login">去登录</a>
        </p>
      </div>
    )
  }

  return (
    <div className="card">
      <form
        onSubmit={(event) => {
          void submit(event)
        }}
      >
        <h2>找回密码</h2>
        <FormBanner message={banner} />
        <FormBanner message={step.banner} />

        <div className="field">
          <label htmlFor="phone">手机号</label>
          <input
            id="phone"
            value={step.phone}
            inputMode="numeric"
            autoComplete="tel"
            maxLength={11}
            placeholder="必填"
            aria-required="true"
            onChange={(event) => step.setPhone(event.target.value)}
            onBlur={() => blurred('phone')}
          />
          {/* Both steps can refuse the phone number: the code request checks its shape, and
              `no_account` comes from the reset itself. */}
          <FieldFeedback
            message={problems.phone ?? step.problems.phone}
            verdict={verdicts.phone}
          />
        </div>

        <CaptchaField
          value={step.captchaAnswer}
          onChange={step.setCaptchaAnswer}
          image={step.captchaImage}
          error={step.captchaError ?? step.problems.captcha}
          onRefresh={() => {
            void step.refreshCaptcha()
          }}
        />

        <CodeField
          value={step.code}
          onChange={step.setCode}
          error={problems.code}
          verdict={verdicts.code}
          remaining={step.remaining}
          canSend={step.canSend}
          sending={step.sending}
          onSend={() => {
            void step.requestCode()
          }}
          onBlur={() => blurred('code')}
        />

        <PasswordField
          id="password"
          label="新密码"
          value={password}
          onChange={setPassword}
          autoComplete="new-password"
          error={problems.password}
          verdict={verdicts.password}
          hint={PASSWORD_HINT}
          onBlur={() => blurred('password')}
        />

        <button type="submit" disabled={submitting || !step.sent}>
          {submitting ? '提交中…' : '重置密码'}
        </button>

        <p className="muted">
          想起来了？<a href="/login">去登录</a>
        </p>
      </form>
    </div>
  )
}
