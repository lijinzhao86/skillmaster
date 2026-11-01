import { useState } from 'react'
import type { ChangeEvent, MutableRefObject } from 'react'
import { codeMessage } from '../../api/errors'
import { updateDraftName, updateDraftNote } from '../../api/skills'
import type { AuthoredVersion } from '../../api/types'
import { useLive } from '../../hooks/useLive'
import { versionSuffix } from '../../version'
import { clampPoints } from './editorModel'
import { nextFrame } from './nextFrame'
import type { EditorLabelStatus } from './types'

const MAX_NAME = 40
const MAX_NOTE = 200

/**
 * The draft's two labels — its name and its note — and the one write each makes on blur.
 *
 * The fields are dirtied locally and PATCHed by blur, never by the body save: a create or edit
 * carries content only, so what the server holds for the labels is tracked separately
 * (`savedName` / `savedNote`) and `adopt` keeps them in step.
 */
export interface EditorLabelsOptions {
  namespace: string
  name: string
  currentVersionRef: MutableRefObject<AuthoredVersion | null>
  isNewRef: MutableRefObject<boolean>
}

export function useEditorLabels({
  namespace,
  name,
  currentVersionRef,
  isNewRef,
}: EditorLabelsOptions) {
  const [draftName, setDraftName, draftNameRef] = useLive('')
  const [draftNote, setDraftNote, draftNoteRef] = useLive('')
  const [, setSavedName, savedNameRef] = useLive('')
  const [, setSavedNote, savedNoteRef] = useLive('')
  const [labelStatus, setLabelStatus] = useState<EditorLabelStatus | null>(null)

  /** The load hands over the names the version already has — all four fields at once. */
  function init(nextName: string, nextNote: string): void {
    setDraftName(nextName)
    setDraftNote(nextNote)
    setSavedName(nextName)
    setSavedNote(nextNote)
  }

  function onNameInput(event: ChangeEvent<HTMLInputElement>): void {
    const input = event.target
    const value = clampPoints(input.value, MAX_NAME)
    // The DOM is written too, not only the model: once the model is already at 40, one more
    // character clamps to the same string, the ref assignment is a no-op, and no re-render puts the
    // field back to 40 — the 41st would stay visible (design: 第 41 个字符进不去).
    if (input.value !== value) input.value = value
    setDraftName(value)
    setLabelStatus(null)
  }

  function onNoteInput(event: ChangeEvent<HTMLInputElement>): void {
    const input = event.target
    const value = clampPoints(input.value, MAX_NOTE)
    if (input.value !== value) input.value = value
    setDraftNote(value)
    setLabelStatus(null)
  }

  async function commitName(): Promise<void> {
    if (isNewRef.current) return
    const value = clampPoints(draftNameRef.current.trim(), MAX_NAME)
    if (value === '') {
      setDraftName(savedNameRef.current)
      setLabelStatus({ text: '版本名称不能为空，已恢复原名称。', danger: true })
      return
    }
    if (value === savedNameRef.current) {
      setDraftName(savedNameRef.current)
      return
    }
    const version = currentVersionRef.current
    if (version === null) return
    const result = await updateDraftName(namespace, name, versionSuffix(version), value)
    if (result.ok) {
      setDraftName(value)
      setSavedName(value)
      setLabelStatus({ text: '版本名称已保存。', danger: false })
    } else {
      // The typed value stays: a failed write is not a reason to throw away what somebody wrote.
      // Only an empty name is restored, because the server would refuse a draft without one.
      setLabelStatus({ text: codeMessage(result.code, result.message), danger: true })
    }
  }

  async function commitNote(): Promise<void> {
    if (isNewRef.current) return
    const value = clampPoints(draftNoteRef.current.trim(), MAX_NOTE)
    if (value === savedNoteRef.current) {
      setDraftNote(savedNoteRef.current)
      return
    }
    const version = currentVersionRef.current
    if (version === null) return
    const result = await updateDraftNote(namespace, name, versionSuffix(version), value)
    if (result.ok) {
      setDraftNote(value)
      setSavedNote(value)
      setLabelStatus({ text: '说明已保存。', danger: false })
    } else {
      // Kept, so losing the network does not lose the sentence; a second blur retries it.
      setLabelStatus({ text: codeMessage(result.code, result.message), danger: true })
    }
  }

  // Enter is the keyboard's blur: the one write is the blur handler's, so the two paths cannot send
  // the same PATCH twice.
  function onNameEnter(): void {
    ;(document.getElementById('edName') as HTMLInputElement | null)?.blur()
  }

  function onNoteEnter(): void {
    ;(document.getElementById('edNote') as HTMLInputElement | null)?.blur()
  }

  /** The draft needs a name before anything can create it (ADR 0055). */
  function requireNameForDraft(): boolean {
    if (!isNewRef.current) return true
    if (draftNameRef.current.trim() !== '') return true
    setLabelStatus({ text: '请先填写版本名称。', danger: true })
    void nextFrame().then(
      () => (document.getElementById('edName') as HTMLInputElement | null)?.focus(),
    )
    return false
  }

  return {
    draftName,
    draftNote,
    draftNameRef,
    draftNoteRef,
    savedNameRef,
    savedNoteRef,
    setSavedName,
    setSavedNote,
    labelStatus,
    setLabelStatus,
    init,
    onNameInput,
    onNoteInput,
    commitName,
    commitNote,
    onNameEnter,
    onNoteEnter,
    requireNameForDraft,
  }
}

export type EditorLabels = ReturnType<typeof useEditorLabels>
