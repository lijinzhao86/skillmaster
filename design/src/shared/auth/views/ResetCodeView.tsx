/**
 * The code step with no button: six digits is the whole answer, so the field goes on by itself the
 * moment it has one. What is left is the way back for a message that never arrived.
 */

import CodeField from '../fields/CodeField'
import type { ResetFlow } from '../flows/useResetFlow'

export interface ResetCodeViewProps {
  active: boolean
  flow: ResetFlow
  onResend: () => void
  onLogin: () => void
}

export default function ResetCodeView({ active, flow, onResend, onLogin }: ResetCodeViewProps) {
  /* No button on this screen — it is judged from its own input, not from a submit. The form is
     still a form so that Enter works, and it still has to be stopped from reloading the page. */
  return (
    <form
      className="dialog-view"
      id="resetCodeForm"
      noValidate
      hidden={!active}
      onSubmit={(event) => event.preventDefault()}
    >
      <CodeField
        id="resetCode"
        boxesId="resetCodeBoxes"
        hintId="resetCodeHint"
        hint={flow.codeHint}
        field={flow.code}
        onValueChange={flow.codeChanged}
      />

      <p className="dialog-foot">
        <button type="button" className="link" id="resetResend" onClick={onResend}>
          重新获取验证码
        </button>
        {'　·　'}
        <a
          href="#"
          id="resetCodeToLogin"
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
