import FieldFeedback from './FieldFeedback'

export default function CaptchaField({
  value,
  image,
  error,
  onChange,
  onRefresh,
}: {
  value: string
  image: string
  error: string | null | undefined
  onChange: (value: string) => void
  onRefresh: () => void
}) {
  return (
    <div className="field">
      <label htmlFor="captcha">图形验证码</label>
      <div className="captcha">
        {image ? <img src={image} alt="图形验证码" /> : null}
        <button type="button" className="link" onClick={onRefresh}>
          换一张
        </button>
      </div>
      <input
        id="captcha"
        value={value}
        maxLength={4}
        autoComplete="off"
        autoCapitalize="characters"
        placeholder="必填"
        aria-required="true"
        onChange={(event) => onChange(event.target.value)}
      />
      <FieldFeedback message={error} />
    </div>
  )
}
