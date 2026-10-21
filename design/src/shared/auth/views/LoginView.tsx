/** The login form: two boxes, one pair-wide refusal, and the two links out of it. */

import type { FieldStatus } from '../authModel'
import PasswordInput from '../fields/PasswordInput'
import TextField from '../fields/TextField'
import type { LoginForm } from './useLoginForm'

export interface LoginViewProps {
  active: boolean
  form: LoginForm
  onRegister: () => void
  onReset: () => void
}

function errorStatus(message: string): FieldStatus {
  return message === '' ? { kind: 'none' } : { kind: 'error', message }
}

export default function LoginView({ active, form, onRegister, onReset }: LoginViewProps) {
  return (
    <form
      className="login-form"
      id="loginForm"
      noValidate
      hidden={!active}
      aria-busy={form.pending ? 'true' : undefined}
      onSubmit={form.submit}
    >
      <p className="form-error" id="formError" role="alert" hidden={form.formError === ''}>
        {form.formError}
      </p>

      <TextField
        id="phone"
        name="phone"
        label="手机号"
        type="tel"
        inputMode="numeric"
        autoComplete="tel"
        maxLength={11}
        errorId="phoneErr"
        value={form.phone}
        onValue={form.onPhone}
        status={errorStatus(form.phoneError)}
      />

      <PasswordInput
        id="password"
        name="password"
        label="密码"
        autoComplete="current-password"
        revealId="revealBtn"
        errorId="pwErr"
        resetToken={form.revealToken}
        value={form.password}
        onValue={form.onPassword}
        status={errorStatus(form.passwordError)}
      />

      <button type="submit" className="btn btn-block" id="submitBtn" disabled={form.pending}>
        登录
      </button>

      <p className="dialog-foot">
        还没有账号？
        <a
          href="#"
          id="toRegister"
          onClick={(event) => {
            event.preventDefault()
            onRegister()
          }}
        >
          注册
        </a>
        {'　·　'}
        <a
          href="#"
          id="toReset"
          onClick={(event) => {
            event.preventDefault()
            onReset()
          }}
        >
          忘记密码
        </a>
      </p>
    </form>
  )
}
