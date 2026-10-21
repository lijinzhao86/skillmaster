/** Register's second step: the code the message just carried. The hint names the number the message
 *  actually went to, not whatever is in the box now. */

import CodeField from '../fields/CodeField'
import type { RegisterFlow } from '../flows/useRegisterFlow'

export interface RegisterCodeViewProps {
  active: boolean
  flow: RegisterFlow
  onResend: () => void
  onLogin: () => void
}

export default function RegisterCodeView({ active, flow, onResend, onLogin }: RegisterCodeViewProps) {
  return (
    <form
      className="dialog-view"
      id="registerCodeForm"
      noValidate
      hidden={!active}
      onSubmit={flow.submitCode}
    >
      <CodeField
        id="regCode"
        boxesId="regCodeBoxes"
        hintId="regCodeHint"
        hint={flow.codeHint}
        field={flow.code}
      />

      <button type="submit" className="btn btn-block" id="regSubmitBtn" disabled={flow.submitDisabled}>
        {flow.submitLabel}
      </button>

      <p className="dialog-foot">
        <button type="button" className="link" id="regResend" onClick={onResend}>
          重新获取验证码
        </button>
        {'　·　'}
        <a
          href="#"
          id="codeToLogin"
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
