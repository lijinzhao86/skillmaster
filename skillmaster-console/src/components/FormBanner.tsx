/** A refusal or a notice that belongs to the whole form rather than to one field. */
export default function FormBanner({ message }: { message: string | null }) {
  // role=alert so a screen reader announces a refusal that appears without a page change.
  if (!message) {
    return null
  }
  return (
    <p className="banner" role="alert">
      {message}
    </p>
  )
}
