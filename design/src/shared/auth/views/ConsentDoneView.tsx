/**
 * Where the browser lands after answering. Each sentence says the thing its own title has not: the
 * success sentence points at where the flow continues, and the refusal says what the person did and
 * stops — a refusal has no next step, so offering one is the mistake to avoid.
 *
 * The result page is litter once it has been read, so the overlay tries to close itself after three
 * seconds. Nothing on the card mentions it: the attempt can never be granted here, and a promise
 * about a future that does not exist has no place on the card whose whole job is to report what
 * actually happened.
 */

export interface ConsentDoneViewProps {
  active: boolean
  note: string
}

export default function ConsentDoneView({ active, note }: ConsentDoneViewProps) {
  return (
    <div className="dialog-view" id="consentDoneView" hidden={!active}>
      <p id="consentDoneNote">{note}</p>
    </div>
  )
}
