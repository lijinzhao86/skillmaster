import type { MutableRefObject } from 'react'
import type { EditorLeave } from './useEditorLeave'
import type { EditorSave } from './useEditorSave'
import type { EditorTree } from './useEditorTree'

/**
 * The page's document-level keys and clicks: ⌘/Ctrl+S saves, Esc dismisses the row menu, and a
 * click outside the menu does the same.
 *
 * The listener is added once at mount, so every value it reads is either a ref or a function that
 * itself reads refs. `canSaveRef` is the one computed fact, mirrored by the page each render.
 */
export interface EditorKeyboardOptions {
  canSaveRef: MutableRefObject<boolean>
  save: EditorSave
  tree: EditorTree
  leave: EditorLeave
}

export function useEditorKeyboard({ canSaveRef, save, tree, leave }: EditorKeyboardOptions) {
  function onKeydown(event: KeyboardEvent): void {
    const dialogOpen =
      tree.deleteTargetRef.current !== null ||
      tree.deleteFolderTargetRef.current !== null ||
      leave.leaveTargetRef.current !== null
    if (dialogOpen) return
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
      event.preventDefault()
      if (canSaveRef.current) void save.save()
      return
    }
    if (event.key !== 'Escape') return
    // The menu's dismissal is the document-level one (it is a fixed popup, not a row); rename and
    // create are inputs and own their own Esc, so pressing Esc elsewhere does not tear them down.
    if (tree.menuRef.current !== null) {
      event.preventDefault()
      tree.closeMenu(true)
    }
  }

  function onDocumentMouseDown(event: MouseEvent): void {
    if (tree.menuRef.current === null) return
    const target = event.target as Element | null
    if (target === null) return
    if (target.closest('.editor-file-pop') !== null || target.closest('[data-menu]') !== null) {
      return
    }
    tree.closeMenu()
  }

  return { onKeydown, onDocumentMouseDown }
}

export type EditorKeyboard = ReturnType<typeof useEditorKeyboard>
