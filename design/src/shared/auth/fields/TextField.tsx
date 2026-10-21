/**
 * A label, a text box, and what the box owes under it. The same shell serves every non-password
 * field in the register and reset walks; the login form and consent build their own because their
 * boxes do not use the rules machine.
 */

import type { ReactNode } from 'react'
import type { FieldStatus } from '../authModel'
import FieldMessage from './FieldMessage'

export interface TextFieldProps {
  id: string
  name: string
  label: string
  value: string
  onValue: (value: string) => void
  onBlur?: () => void
  status: FieldStatus
  type?: 'text' | 'tel'
  autoComplete?: string
  inputMode?: 'numeric' | 'text'
  maxLength?: number
  placeholder?: string
  autoCapitalize?: string
  errorId?: string
  wrapperId?: string
  wrapperHidden?: boolean
  children?: ReactNode
}

export default function TextField({
  id,
  name,
  label,
  value,
  onValue,
  onBlur,
  status,
  type,
  autoComplete,
  inputMode,
  maxLength,
  placeholder,
  autoCapitalize,
  errorId,
  wrapperId,
  wrapperHidden,
  children,
}: TextFieldProps) {
  const error = status.kind === 'error'
  return (
    <div className={'field' + (error ? ' has-error' : '')} id={wrapperId} hidden={wrapperHidden}>
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        name={name}
        type={type}
        autoComplete={autoComplete}
        inputMode={inputMode}
        maxLength={maxLength}
        placeholder={placeholder}
        autoCapitalize={autoCapitalize}
        aria-required="true"
        aria-describedby={errorId ?? id + 'Err'}
        aria-invalid={error ? 'true' : undefined}
        value={value}
        onChange={(event) => onValue(event.target.value)}
        onBlur={onBlur}
      />
      <FieldMessage id={errorId ?? id + 'Err'} status={status} />
      {children}
    </div>
  )
}
