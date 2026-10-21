/**
 * The masked box and the eye at its trailing edge — where every form the person using this has met
 * before puts it.
 *
 * The reveal state lives here rather than in the flow: it is a fact about this control, not about
 * the form. `resetToken` is the flow's way of saying the visit is over, which puts the eye back to
 * its first icon without remounting the input.
 */

import { useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { flushSync } from 'react-dom'
import type { FieldStatus } from '../authModel'
import FieldMessage from './FieldMessage'
import { EyeHideIcon, EyeShowIcon } from './icons'

export interface PasswordInputProps {
  id: string
  name: string
  label: string
  value: string
  onValue: (value: string) => void
  onBlur?: () => void
  status: FieldStatus
  autoComplete: string
  placeholder?: string
  revealId: string
  errorId?: string
  resetToken: number
  children?: ReactNode
}

export default function PasswordInput({
  id,
  name,
  label,
  value,
  onValue,
  onBlur,
  status,
  autoComplete,
  placeholder,
  revealId,
  errorId,
  resetToken,
  children,
}: PasswordInputProps) {
  const [shown, setShown] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const describedBy = errorId ?? id + 'Err'
  const error = status.kind === 'error'

  useLayoutEffect(() => {
    setShown(false)
  }, [resetToken])

  return (
    <div className={'field' + (error ? ' has-error' : '')}>
      <label htmlFor={id}>{label}</label>
      <div className="password">
        <input
          id={id}
          name={name}
          type={shown ? 'text' : 'password'}
          autoComplete={autoComplete}
          placeholder={placeholder}
          aria-required="true"
          aria-describedby={describedBy}
          aria-invalid={error ? 'true' : undefined}
          ref={input}
          value={value}
          onChange={(event) => onValue(event.target.value)}
          onBlur={onBlur}
        />
        <button
          type="button"
          className="reveal"
          id={revealId}
          aria-label={shown ? '隐藏密码' : '显示密码'}
          aria-pressed={shown ? 'true' : 'false'}
          onClick={() => {
            /* The source toggled the type, the pressed state and both icons inside the click, and a
               check reads all three before the click returns. `flushSync` keeps that contract for a
               concurrent root. */
            flushSync(() => setShown((current) => !current))
            input.current?.focus()
          }}
        >
          <EyeShowIcon hidden={shown} />
          <EyeHideIcon hidden={!shown} />
        </button>
      </div>
      <FieldMessage id={describedBy} status={status} />
      {children}
    </div>
  )
}
