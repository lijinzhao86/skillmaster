import type { MouseEvent as ReactMouseEvent, MutableRefObject } from 'react'
import { go } from '../../address'
import { dirtyPathsOf } from '../../editor/files'
import { useLive } from '../../hooks/useLive'
import { nextFrame } from './nextFrame'
import type { EditorDocument } from './useEditorDocument'
import type { LeaveTarget } from './types'

/**
 * The leave guard: a link out with unsaved work asks first, and the native prompt is suppressed
 * once the page itself has asked.
 *
 * One question, not two. An action the page itself confirmed (重新加载 / 放弃并离开) has already
 * asked, and the native prompt on top of it can be cancelled, which would abort that action.
 */
export interface EditorLeaveOptions {
  doc: EditorDocument
  /** Set when this page is deliberately leaving, so the native prompt does not ask again. */
  leavingPage: MutableRefObject<boolean>
}

export function useEditorLeave({ doc, leavingPage }: EditorLeaveOptions) {
  const [leaveTarget, setLeaveTarget, leaveTargetRef] = useLive<LeaveTarget | null>(null)

  function guardLeave(
    event: ReactMouseEvent<HTMLAnchorElement>,
    href: string,
    opener: EventTarget | null,
  ): void {
    if (doc.dirtyPaths.length === 0) return
    event.preventDefault()
    setLeaveTarget({ href, opener: opener instanceof HTMLElement ? opener : null })
  }

  function leave(): void {
    const target = leaveTarget
    setLeaveTarget(null)
    if (target !== null) {
      leavingPage.current = true
      go(target.href)
    }
  }

  function cancelLeave(): void {
    const opener = leaveTarget?.opener ?? null
    setLeaveTarget(null)
    void nextFrame().then(() => opener?.focus())
  }

  function onBeforeUnload(event: BeforeUnloadEvent): void {
    if (leavingPage.current || dirtyPathsOf(doc.buffersRef.current, doc.savedRef.current).length === 0) {
      return
    }
    event.preventDefault()
    event.returnValue = ''
  }

  return {
    leaveTarget,
    leaveTargetRef,
    guardLeave,
    leave,
    cancelLeave,
    onBeforeUnload,
  }
}

export type EditorLeave = ReturnType<typeof useEditorLeave>
