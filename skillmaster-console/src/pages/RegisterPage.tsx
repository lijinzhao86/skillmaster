import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import {
  checkUsername,
  register,
  registrationCodeCaptcha,
  requestRegistrationCode,
} from '../api/account'
import { go } from '../address'
import { codeMessage, fieldErrors as mapFieldErrors, PASSWORD_HINT } from '../api/errors'
import type { Field } from '../api/errors'
import type { Account } from '../api/types'
import { useCodeRequest } from '../hooks/useCodeRequest'
import { useFieldChecks } from '../hooks/useFieldChecks'
import { codeIssue, isBlank, passwordIssue, phoneIssue, usernameIssue } from '../validation'
import CaptchaField from '../components/CaptchaField'
import CodeInput from '../components/CodeInput'
import FieldFeedback from '../components/FieldFeedback'
import FormBanner from '../components/FormBanner'
import PasswordField from '../components/PasswordField'

export default function RegisterPage() {
  const step = useCodeRequest(requestRegistrationCode)

  /*
   * Two screens on one route, and the state above survives the switch because nothing is unmounted —
   * which is the whole reason not to give the second screen its own URL. It has nothing to be reached
   * with: the number the code went to lives in this component, and a visitor arriving at a second
   * address directly would have no code and no phone.
   */
  const [which, setWhich] = useState<1 | 2>(1)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  /** The account the server just created, which is what turns this page into the success screen. */
  const [registered, setRegistered] = useState<Account | null>(null)
  const [problems, setProblems] = useState<Partial<Record<Field, string>>>({})
  const [banner, setBanner] = useState<string | null>(null)

  /** The number a code was last sent to, so going back to fix a username does not send another. */
  const [codePhone, setCodePhone] = useState('')

  const { blurred, refusedByRules, refusedByServer, verdicts } = useFieldChecks(
    {
      username: {
        value: username,
        check: usernameIssue,
        verify: async (candidate) => {
          const result = await checkUsername(candidate)
          // `data == null` is reachable: the client answers a 200 whose body is JSON `null` with
          // `ok: true` and no data. Reading through it would throw inside a call nobody awaits, and
          // the field would sit at 「检查中…」 for the rest of the page's life.
          if (!result.ok || result.data == null) {
            return undefined
          }
          return result.data.issue
        },
      },
      phone: { value: step.phone, check: phoneIssue },
      // Against the other two fields, because that is what the server does: a password built out of
      // the handle or the number is refused whatever else is right about it.
      password: {
        value: password,
        check: (value) => passwordIssue(value, username, step.phone),
        dependsOn: [username, step.phone],
      },
      code: { value: step.code, check: codeIssue },
    },
    setProblems,
  )

  /**
   * The step's own messages are about the value that was submitted, so a change to that value takes
   * them away — the rule the field checks already apply to this page's map. Without this, the phone
   * keeps saying 「这个手机号已经注册过了」 while somebody types a different number into it.
   */
  const { dismiss: dismissStepProblem } = step
  useEffect(() => {
    dismissStepProblem('phone')
  }, [step.phone, dismissStepProblem])
  useEffect(() => {
    dismissStepProblem('captcha')
  }, [step.captchaAnswer, dismissStepProblem])

  /** Whether the person asked for another code from the second screen. */
  const [wantsAnotherCode, setWantsAnotherCode] = useState(false)

  /** The code was sent to the number on screen, so the second step is already paid for. */
  const alreadyPaidFor = !wantsAnotherCode && step.sent && step.phone === codePhone

  /**
   * Whether the first screen is finished, which is what lights the button.
   *
   * Ticked rather than merely non-empty, and that is the reason the checks run while somebody types:
   * the last field they are in is the one that never gets left. The captcha is asked for by whether
   * it is filled rather than by a tick, because nothing local can know a correct answer.
   */
  const ready =
    verdicts.username === 'ok' &&
    verdicts.phone === 'ok' &&
    verdicts.password === 'ok' &&
    (!step.captchaRequired || !isBlank(step.captchaAnswer))

  /**
   * A send waits out the cooldown; walking forward does not.
   *
   * Only the send spends a text message, which is what the countdown exists to space out — so gating
   * the walk forward on it would make somebody who came back to fix a username wait a minute for
   * nothing.
   */
  const firstStepBlocked =
    !ready ||
    // A refusal the send itself came back with, which no verdict of the field checks knows about: the
    // phone is well-formed, and it is the server that says it already has an account.
    step.problems.phone !== undefined ||
    (!alreadyPaidFor && !step.canSend)

  /**
   * What the first screen's button says, which has to be what it does.
   *
   * `alreadyPaidFor` is the one state where the two could part company: the button walks forward
   * without sending, so it must not be labelled as a send.
   */
  const sendLabel = step.sending
    ? '发送中…'
    : alreadyPaidFor
      ? '下一步'
      : step.remaining > 0
        ? // A wait either way, but not the same wait: a first attempt refused for being too soon has
          // sent nothing, and calling that a resend promises a message that never went out.
          step.sent
          ? `${step.remaining} 秒后可重发`
          : `${step.remaining} 秒后可重试`
        : step.sent
          ? '重新发送验证码'
          : '下一步'

  // Asked as the form opens: an address that has already had its one free send is one whose captcha
  // belongs on screen from the start, and finding that out by pressing the button and being refused
  // makes the first thing a returning visitor sees a refusal they did nothing to earn.
  const { requireCaptcha } = step
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const answer = await registrationCodeCaptcha()
      // `data == null` is reachable — a 200 whose body is JSON `null`, or a 204 — and reading through
      // it would throw inside a callback nobody awaits.
      if (!cancelled && answer.ok && answer.data?.required === true) {
        await requireCaptcha()
      }
    })()
    return () => {
      cancelled = true
    }
  }, [requireCaptcha])

  /**
   * The way back to the screen whose button sends, which is also where the captcha belongs.
   *
   * The captcha is revealed here rather than when the send succeeded: on the way out, this screen is
   * still mounted for the tick between the flag changing and the step changing, so a field revealed
   * then is drawn and taken away again — the flicker that is worse than never showing it.
   */
  function backToDetails(): void {
    // A free send is spent by going out, so the next one needs a captcha — and the person is about to
    // ask for exactly that. Skipped when one is already up, whose image the send that succeeded has
    // already replaced.
    if (step.sent && !step.captchaRequired) {
      void requireCaptcha()
    }
    // Said here rather than inferred later: this screen was reached by asking for another code, so the
    // button on it must really ask for one.
    setWantsAnotherCode(true)
    setWhich(1)
  }

  /**
   * The first step: everything that can be got wrong before a text message is spent, then the message.
   *
   * The code is not re-sent when the number has not changed — an address's free send is spent by the
   * first one, so walking back to fix a username and forward again would cost a second message and a
   * captcha, for a code the person already has.
   */
  async function next(event: FormEvent): Promise<void> {
    event.preventDefault()
    setBanner(null)
    // Issue codes, not sentences: they are handed to the checks, which is where the wording for an
    // issue lives and where a rule's verdict belongs — so the next judgement re-states them.
    const local: Partial<Record<Field, string>> = {}
    const usernameProblem = usernameIssue(username)
    if (usernameProblem !== null) {
      local.username = usernameProblem
    }
    const phoneProblem = phoneIssue(step.phone)
    if (phoneProblem !== null) {
      local.phone = phoneProblem
    }
    const passwordProblem = passwordIssue(password, username, step.phone)
    if (passwordProblem !== null) {
      local.password = passwordProblem
    }
    if (Object.keys(local).length > 0) {
      refusedByRules(local)
      return
    }

    if (alreadyPaidFor) {
      setWhich(2)
      return
    }

    // The step opens only when the send says so. `sent` cannot be asked: it stays true from the first
    // send onward, so a refused resend would leave it true and the page would advance claiming a code
    // had gone out.
    //
    // The number is captured before the request rather than read after it, because the request can
    // take seconds and the box stays editable throughout.
    const sentTo = step.phone
    const opensTheCodeStep = await step.requestCode()
    if (opensTheCodeStep) {
      setCodePhone(sentTo)
      setWantsAnotherCode(false)
      setWhich(2)
    }
  }

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault()
    setBanner(null)
    const codeProblem = codeIssue(step.code)
    if (codeProblem !== null) {
      refusedByRules({ code: codeProblem })
      return
    }

    // One object, used both as the body and as the record of what was sent, so the two cannot drift.
    const sent = {
      phone: step.phone,
      code: step.code,
      password,
      username,
    }
    setSubmitting(true)
    const result = await register(sent)
    setSubmitting(false)

    if (result.ok) {
      if (result.data == null) {
        // The account exists and the cookie is already the new session, but there is no username or
        // namespace to show — and falling through would draw the code step again, which reads as "not
        // registered" over an account that was just created.
        goHome()
        return
      }
      setRegistered(result.data)
      return
    }

    const serverMessage = codeMessage(result.code, result.message)
    if (result.code === 'verification_code_invalid') {
      // No details on this one, but it is plainly about the field it sits next to.
      refusedByServer({ code: serverMessage }, sent)
      return
    }
    const serverProblems = mapFieldErrors(result.details, serverMessage)
    if (Object.keys(serverProblems).length > 0) {
      // Nothing to show means every refusal named a field this form does not have, and a refusal
      // nobody can see is worse than one in the wrong place.
      if (!refusedByServer(serverProblems, sent)) {
        setBanner(serverMessage)
        return
      }
      // A refusal about a field on the first screen sends the person back to it. Left here it would
      // be a sentence about a box nobody can see.
      if (wantsFirstStep(serverProblems)) {
        setWhich(1)
      }
      return
    }
    setBanner(serverMessage)
  }

  /** A full load, because the session cookie registration just replaced is what `/` reads. */
  function goHome(): void {
    go('/')
  }

  if (registered) {
    return (
      // The screen that exists for one moment, and the only place that says what the account is now
      // worth knowing: the namespace, and the shape of the addresses it will appear in.
      <div className="card">
        <h2>注册成功</h2>
        <p>欢迎，{registered.username}。账号已经建好，也已经登录。</p>

        <dl className="facts">
          <dt>用户名</dt>
          <dd>{registered.username}</dd>
          <dt>命名空间</dt>
          <dd>{registered.namespace}</dd>
        </dl>

        <p className="hint">
          用户名不可更改，它会成为你发布的每个 skill 地址的第一段，例如
          <code>{registered.namespace}/我的技能</code>。
        </p>
        <p className="hint">你的 skill 都在控制台的「管理」里——第一个怎么交上来，那一页会说。</p>

        <button type="button" onClick={goHome}>
          开始使用
        </button>
      </div>
    )
  }

  if (which === 1) {
    return (
      <form
        className="card"
        onSubmit={(event) => {
          void next(event)
        }}
      >
        <h2>注册</h2>
        <FormBanner message={banner} />
        <FormBanner message={step.banner} />

        <div className="field">
          <label htmlFor="username">用户名</label>
          <input
            id="username"
            value={username}
            autoComplete="username"
            maxLength={30}
            placeholder="必填"
            aria-required="true"
            onChange={(event) => setUsername(event.target.value)}
            onBlur={() => blurred('username')}
          />
          <FieldFeedback message={problems.username} verdict={verdicts.username} />
          {/* Said here rather than on a help page: it becomes the first segment of every address this
              account publishes, and addresses get pinned and copied. */}
          <p className="hint">
            用户名不可更改，它会成为你发布的每个 skill 地址的第一段（<code>用户名/名称</code>）。请不要使用真实姓名或手机号。
          </p>
        </div>

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
          {/* Both steps can refuse the phone number: this one checks its shape, and the send is where
              `already_registered` comes from. */}
          <FieldFeedback
            message={problems.phone ?? step.problems.phone}
            verdict={verdicts.phone}
          />
        </div>

        <PasswordField
          id="password"
          label="密码"
          value={password}
          onChange={setPassword}
          autoComplete="new-password"
          error={problems.password}
          verdict={verdicts.password}
          hint={PASSWORD_HINT}
          onBlur={() => blurred('password')}
        />

        {/* Last, and only when the server asks for it: registration's first send from an address
            needs no captcha, and no client can know in advance which send that is. */}
        {step.captchaRequired ? (
          <CaptchaField
            value={step.captchaAnswer}
            onChange={step.setCaptchaAnswer}
            image={step.captchaImage}
            error={step.captchaError ?? step.problems.captcha}
            onRefresh={() => {
              void step.refreshCaptcha()
            }}
          />
        ) : null}

        <button type="submit" disabled={firstStepBlocked}>
          {sendLabel}
        </button>

        <p className="muted">
          已经有账号？<a href="/login">登录</a>
        </p>
      </form>
    )
  }

  return (
    <form
      className="card"
      onSubmit={(event) => {
        void submit(event)
      }}
    >
      <h2>验证手机号</h2>
      <FormBanner message={banner} />
      <FormBanner message={step.banner} />

      <div className="field">
        <label htmlFor="sms-code">短信验证码</label>
        <CodeInput
          id="sms-code"
          value={step.code}
          onChange={step.setCode}
          onBlur={() => blurred('code')}
        />
        <FieldFeedback message={problems.code} verdict={verdicts.code} />
        {/* The number the message actually went to, not whatever is in the box now: the phone box is
            editable while the request is in flight, so after a correction mid-send the two differ —
            and naming the new one would send somebody to wait for a message that went elsewhere. */}
        <p className="hint">
          验证码已发送至 {codePhone}。
          {step.remaining > 0
            ? `${step.remaining} 秒后可以重新发送。`
            : '没收到可以重新获取。'}
        </p>
      </div>

      {/* The same rule as the first screen's button: ticked before it can be pressed. */}
      <button type="submit" disabled={submitting || verdicts.code !== 'ok'}>
        {submitting ? '注册中…' : '注册'}
      </button>

      <p className="muted">
        {/* Back rather than here: the send is what the captcha stands in front of, and that is the
            first screen's button. */}
        <button type="button" className="link" onClick={backToDetails}>
          重新获取验证码
        </button>
        　·　<a href="/login">去登录</a>
      </p>
    </form>
  )
}

function wantsFirstStep(serverProblems: Partial<Record<Field, string>>): boolean {
  return (
    serverProblems.username !== undefined ||
    serverProblems.phone !== undefined ||
    serverProblems.password !== undefined
  )
}
