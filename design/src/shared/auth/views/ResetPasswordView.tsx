/** The third step: only now is a new password worth asking for. */

import PasswordInput from '../fields/PasswordInput'
import type { ResetFlow } from '../flows/useResetFlow'

export interface ResetPasswordViewProps {
  active: boolean
  flow: ResetFlow
  onLogin: () => void
}

export default function ResetPasswordView({ active, flow, onLogin }: ResetPasswordViewProps) {
  return (
    <form
      className="dialog-view"
      id="resetPasswordForm"
      noValidate
      hidden={!active}
      onSubmit={flow.submit}
    >
      <PasswordInput
        id="resetPassword"
        name="password"
        label="新密码"
        autoComplete="new-password"
        placeholder="必填"
        revealId="resetPasswordReveal"
        errorId="resetPasswordErr"
        resetToken={flow.revealToken}
        value={flow.password.state.value}
        onValue={flow.password.set}
        onBlur={flow.password.blur}
        status={flow.password.state.note}
      >
        <p className="hint">
          至少 8 个字符，只能使用半角英文字母、数字和符号（不能用中文或全角字符）。用一句长口令比堆特殊符号更安全。
        </p>
      </PasswordInput>

      <button type="submit" className="btn btn-block" id="resetSubmitBtn" disabled={flow.submitDisabled}>
        {flow.submitLabel}
      </button>

      <p className="dialog-foot">
        想起来了？
        <a
          href="#"
          id="resetPasswordToLogin"
          onClick={(event) => {
            event.preventDefault()
            onLogin()
          }}
        >
          去登录
        </a>
      </p>
    </form>
  )
}
