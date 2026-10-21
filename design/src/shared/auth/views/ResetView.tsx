/** Recovery's first step. The picture is on screen from the start rather than waited to be told:
 *  recovery is the flow that can take an account away from whoever holds it, so it pays every
 *  time. */

import CaptchaField from '../fields/CaptchaField'
import TextField from '../fields/TextField'
import type { ResetFlow } from '../flows/useResetFlow'

export interface ResetViewProps {
  active: boolean
  flow: ResetFlow
  onLogin: () => void
}

export default function ResetView({ active, flow, onLogin }: ResetViewProps) {
  return (
    <form className="dialog-view" id="resetForm" noValidate hidden={!active} onSubmit={flow.next}>
      <TextField
        id="resetPhone"
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

      <CaptchaField
        id="resetCaptcha"
        imageId="resetCaptchaImg"
        refreshId="resetCaptchaRefresh"
        field={flow.captcha}
        src={flow.captchaSrc}
        onRefresh={flow.refreshCaptcha}
      />

      <button type="submit" className="btn btn-block" id="resetNextBtn" disabled={flow.nextDisabled}>
        {flow.nextLabel}
      </button>

      <p className="dialog-foot">
        想起来了？
        <a
          href="#"
          id="resetToLogin"
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
