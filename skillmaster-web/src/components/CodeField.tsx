import CodeInput from './CodeInput'
import FieldFeedback from './FieldFeedback'

export default function CodeField({
  value,
  error,
  verdict,
  remaining,
  canSend,
  sending,
  onChange,
  onSend,
  onBlur,
}: {
  value: string
  error: string | null | undefined
  /** what the checks concluded about the value, when anything has judged it */
  verdict?: 'checking' | 'ok' | 'problem'
  remaining: number
  canSend: boolean
  sending: boolean
  onChange: (value: string) => void
  onSend: () => void
  /** The input was left — declared because `blur` does not bubble out of the boxes' wrapper. */
  onBlur?: () => void
}) {
  return (
    <div className="field">
      <label htmlFor="sms-code">短信验证码</label>
      <CodeInput id="sms-code" value={value} onChange={onChange} onBlur={onBlur} />
      <FieldFeedback message={error} verdict={verdict} />
      {/* Below the boxes rather than beside them, which is where the flow that has no room for a
          second column puts it — six boxes and a button do not share a phone's width. It reads as a
          link because that is what it is: the code has usually already gone out once, and this asks
          for another. */}
      <p className="hint">
        <button type="button" className="link resend" disabled={!canSend} onClick={onSend}>
          {sending ? '发送中…' : remaining > 0 ? `${remaining} 秒后可重发` : '获取验证码'}
        </button>
      </p>
    </div>
  )
}
