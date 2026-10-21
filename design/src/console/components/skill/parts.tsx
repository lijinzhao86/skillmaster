/**
 * The small pieces a version row, a fact and a member row all draw: a state chip, a pair of diff
 * counts, an account mark. They are components rather than functions returning nodes because each
 * one is a fragment of markup, not a thing that owns state.
 */

import type { ReactNode } from 'react'
import type { Version } from '../../fixtures/skill'
import { stateChipTone, stateText } from '../../fixtures/skill'

export function StateChip({ version }: { version: Version }) {
  return (
    <span className={'chip' + stateChipTone(version)}>
      <i className="dot"></i>
      <span className="chip-text">{stateText(version)}</span>
    </span>
  )
}

export function Stats({ added, removed }: { added: number; removed: number }) {
  return (
    <>
      <b className="stat-add">{'+' + added}</b>{' '}
      <b className="stat-del">{'−' + removed}</b>
    </>
  )
}

export function Avatar({ name }: { name: string }) {
  return <span className="avatar" aria-hidden="true">{name.charAt(0)}</span>
}

export function TypeNode({ kind }: { kind?: string }) {
  return <span className="member-type">{kind === 'org' ? '组织' : '个人'}</span>
}

/** The facts table both 信息 and 详细信息 draw: a term and a value, and the value may be empty. */
export function FactRow({ label, value, mono }: { label: string; value: ReactNode; mono?: boolean }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd className={mono ? 'is-mono' : undefined}>{value}</dd>
    </div>
  )
}
