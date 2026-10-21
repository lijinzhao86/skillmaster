/** Register's first step: the handle, the number, the password, and — once a message has already
 *  gone out — the picture. Last, because an address's first send needs no captcha and every send
 *  after it needs one. */

import CaptchaField from '../fields/CaptchaField'
import PasswordInput from '../fields/PasswordInput'
import TextField from '../fields/TextField'
import type { RegisterFlow } from '../flows/useRegisterFlow'

export interface RegisterViewProps {
  active: boolean
  flow: RegisterFlow
  onLogin: () => void
}

export default function RegisterView({ active, flow, onLogin }: RegisterViewProps) {
  return (
    <form className="dialog-view" id="registerForm" noValidate hidden={!active} onSubmit={flow.next}>
      <TextField
        id="regUsername"
        name="username"
        label="用户名"
        autoComplete="username"
        maxLength={30}
        placeholder="必填"
        value={flow.username.state.value}
        onValue={flow.onUsername}
        onBlur={flow.username.blur}
        status={flow.username.state.note}
      >
        <p className="hint">
          用户名不可更改，它会成为你发布的每个 skill 地址的第一段（<code>用户名/名称</code>）。请不要使用真实姓名或手机号。
        </p>
      </TextField>

      {/* Both steps can refuse this number: this one checks its shape, and the send is where
          「这个手机号已经注册过了」 comes from. */}
      <TextField
        id="regPhone"
        name="phone"
        label="手机号"
        type="tel"
        inputMode="numeric"
        autoComplete="tel"
        maxLength={11}
        placeholder="必填"
        value={flow.phone.state.value}
        onValue={flow.onPhone}
        onBlur={flow.phone.blur}
        status={flow.phone.state.note}
      />

      <PasswordInput
        id="regPassword"
        name="password"
        label="密码"
        autoComplete="new-password"
        placeholder="必填"
        revealId="regPasswordReveal"
        errorId="regPasswordErr"
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

      <CaptchaField
        id="regCaptcha"
        imageId="regCaptchaImg"
        refreshId="regCaptchaRefresh"
        field={flow.captcha}
        src={flow.captchaSrc}
        onRefresh={flow.refreshCaptcha}
        wrapperId="regCaptchaWrap"
        wrapperHidden={!flow.captchaRequired}
      />

      <button type="submit" className="btn btn-block" id="regNextBtn" disabled={flow.nextDisabled}>
        {flow.nextLabel}
      </button>

      <p className="dialog-foot">
        已经有账号？
        <a
          href="#"
          id="regToLogin"
          onClick={(event) => {
            event.preventDefault()
            onLogin()
          }}
        >
          登录
        </a>
      </p>
    </form>
  )
}
