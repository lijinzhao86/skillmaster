/**
 * The challenge, as a picture the answer is drawn into rather than set as text. The refresh control
 * asks for another picture, not for the refusal about the last one to be taken back — which is why
 * it only redraws here and the field's messages are left alone.
 */

import type { AuthField } from './AuthField'
import FieldMessage from './FieldMessage'

export interface CaptchaFieldProps {
  id: string
  imageId: string
  refreshId: string
  field: AuthField
  src: string
  onRefresh: () => void
  wrapperId?: string
  wrapperHidden?: boolean
}

export default function CaptchaField({
  id,
  imageId,
  refreshId,
  field,
  src,
  onRefresh,
  wrapperId,
  wrapperHidden,
}: CaptchaFieldProps) {
  const error = field.state.note.kind === 'error'
  return (
    <div className={'field' + (error ? ' has-error' : '')} id={wrapperId} hidden={wrapperHidden}>
      <label htmlFor={id}>图形验证码</label>
      <div className="captcha">
        <img id={imageId} alt="图形验证码" src={src} />
        <button type="button" className="link" id={refreshId} onClick={onRefresh}>
          换一张
        </button>
      </div>
      <input
        id={id}
        name="captcha"
        maxLength={4}
        autoComplete="off"
        autoCapitalize="characters"
        placeholder="必填"
        aria-required="true"
        aria-describedby={id + 'Err'}
        aria-invalid={error ? 'true' : undefined}
        value={field.state.value}
        onChange={(event) => field.set(event.target.value)}
        onBlur={field.blur}
      />
      <FieldMessage id={id + 'Err'} status={field.state.note} />
    </div>
  )
}
