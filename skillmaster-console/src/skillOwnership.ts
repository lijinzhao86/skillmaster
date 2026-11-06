import { useSyncExternalStore } from 'react'

/**
 * Which navigation item a skill page belongs under.
 *
 * **The page follows its ownership** (2026-11-05): a skill in the caller's library lights 「我的
 * skill」, a public skill they have not joined lights 「发现」. The fact lives in the page's own
 * read (library entry or not), so the shell cannot work it out from the path alone — this tiny
 * store carries it up. `null` means "not known yet"; the shell then lights neither, rather than
 * claiming a relationship that may not exist.
 */
export type SkillOwner = 'discover' | 'skills' | null

let owner: SkillOwner = null
const listeners = new Set<() => void>()

export function setSkillOwner(next: SkillOwner): void {
  if (owner === next) {
    return
  }
  owner = next
  for (const listener of listeners) {
    listener()
  }
}

export function useSkillOwner(): SkillOwner {
  return useSyncExternalStore(
    (onChange) => {
      listeners.add(onChange)
      return () => listeners.delete(onChange)
    },
    () => owner,
  )
}
