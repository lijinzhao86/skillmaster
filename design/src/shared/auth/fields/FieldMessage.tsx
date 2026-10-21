/**
 * What a box owes under it. The element keeps its id in every state, because the input's
 * `aria-describedby` points at it whether or not it is saying anything.
 */

import type { FieldStatus } from '../authModel'
import { ErrorIcon, OkIcon } from './icons'

export default function FieldMessage({ id, status }: { id: string; status: FieldStatus }) {
  if (status.kind === 'none') return <p className="field-error" id={id} hidden></p>
  if (status.kind === 'ok') {
    return (
      <p className="field-ok" id={id}>
        <OkIcon />
      </p>
    )
  }
  if (status.kind === 'checking') {
    return (
      <p className="field-checking" id={id}>
        检查中…
      </p>
    )
  }
  return (
    <p className="field-error" id={id}>
      <ErrorIcon />
      {status.message}
    </p>
  )
}
