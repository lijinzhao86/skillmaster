/**
 * The sheets, and what opened them.
 *
 * A sheet is not a page's: it covers whatever is behind it, and the same sheet is opened from more
 * than one place. So its state lives here rather than inside the page that happens to ask for it,
 * and the pages ask with `openNewSkill` / `openImport` the way they used to set a global.
 *
 * The trigger is kept because closing a sheet has to hand the focus back to the control that opened
 * it — otherwise a keyboard reader lands at the top of the document with no idea where they were.
 */

import { useSyncExternalStore } from 'react'

export type NewSkillMode = 'open' | 'error' | 'deleted'
export type ImportMode = 'list' | 'skill'
export type ImportAsked =
  | 'open' | 'error' | 'exists' | 'exists-draft' | 'exists-hidden' | 'deleted' | 'mismatch'

export interface NewSkillOpen {
  mode: NewSkillMode
  trigger: HTMLElement | null
}

export interface ImportOpen {
  mode: ImportMode
  asked: ImportAsked
  trigger: HTMLElement | null
}

export interface DangerOpen {
  title: string
  body: string
  confirmLabel: string
  /** A reason field: 下架 asks why. Required unless `optionalField`. */
  field?: string
  placeholder?: string
  optionalField?: boolean
  /** 回滚 is confirmed but is not a dangerous action: the answer wears the ordinary accent. */
  danger?: boolean
  /** 离开那一问's other answer is 继续编辑, which says what staying does rather than undoing. */
  cancelLabel?: string
  pendingLabel?: string
  failMessage?: string
  onConfirm: (reason?: string) => void
  returnFocus?: HTMLElement | null
  focusAfter?: HTMLElement | null
}

export interface PublicBrowseOpen {
  onConfirm: () => void
  returnFocus?: HTMLElement | null
}

export interface DialogSnapshot {
  newSkill: NewSkillOpen | null
  importSkill: ImportOpen | null
  danger: DangerOpen | null
  publicBrowse: PublicBrowseOpen | null
}

let snapshot: DialogSnapshot = {
  newSkill: null,
  importSkill: null,
  danger: null,
  publicBrowse: null,
}
const listeners = new Set<() => void>()

function set(next: Partial<DialogSnapshot>) {
  snapshot = { ...snapshot, ...next }
  listeners.forEach((listener) => listener())
}

export const dialogs = {
  openNewSkill(mode: NewSkillMode = 'open', trigger: HTMLElement | null = null) {
    set({ newSkill: { mode, trigger } })
  },
  closeNewSkill() {
    const trigger = snapshot.newSkill?.trigger ?? null
    set({ newSkill: null })
    trigger?.focus()
  },
  openImport(mode: ImportMode, asked: ImportAsked = 'open', trigger: HTMLElement | null = null) {
    set({ importSkill: { mode, asked, trigger } })
  },
  closeImport() {
    const trigger = snapshot.importSkill?.trigger ?? null
    set({ importSkill: null })
    trigger?.focus()
  },
  openDanger(open: DangerOpen) {
    set({ danger: open })
  },
  closeDanger() {
    set({ danger: null })
  },
  openPublicBrowse(open: PublicBrowseOpen) {
    set({ publicBrowse: open })
  },
  closePublicBrowse() {
    set({ publicBrowse: null })
  },
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useDialogs(): DialogSnapshot {
  return useSyncExternalStore(subscribe, () => snapshot, () => snapshot)
}
