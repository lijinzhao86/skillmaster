/** A refusal or a notice that belongs to the whole form rather than to one field. */
export default function FormBanner({
  message,
  note = false,
}: {
  message: string | null
  /** A neutral statement, not a refusal: 「账号已注销。」 after a deletion. */
  note?: boolean
}) {
  // role=alert so a screen reader announces a refusal that appears without a page change; a note
  // is not a refusal, so it is announced politely and with `status`.
  if (!message) {
    return null
  }
  return note ? (
    <p className="form-note" role="status">
      {message}
    </p>
  ) : (
    <p className="banner" role="alert">
      {message}
    </p>
  )
}
