import { useRef, useState } from 'react'
import type {
  KeyboardEvent as ReactKeyboardEvent,
  MouseEvent as ReactMouseEvent,
} from 'react'
import type { CreateState, MenuState, RenameState } from '../../editor/treeState'
import { useLive } from '../../hooks/useLive'
import { createPathError, renamePathError, uploadRefusal } from './editorModel'
import { nextFrame } from './nextFrame'
import type { EditorCaret } from './useEditorCaret'
import type { EditorDocument } from './useEditorDocument'
import type { EditorLabels } from './useEditorLabels'
import type { EditorSave } from './useEditorSave'

/**
 * 文件 — the tree's interactions: opening, folding, searching, its row menu, and the create / rename
 * / delete flows that write the draft through the save hook.
 *
 * It also owns the menu's placement and dismissal, because both are measurements of a DOM node that
 * exists only after the first frame — and the page's document listeners need the same menu ref.
 */
export interface EditorTreeOptions {
  doc: EditorDocument
  labels: EditorLabels
  save: EditorSave
  caret: EditorCaret
}

export function useEditorTree({ doc, labels, save, caret }: EditorTreeOptions) {
  const [create, setCreate] = useState<CreateState | null>(null)
  const [rename, setRename] = useState<RenameState | null>(null)
  const [menu, setMenu, menuRef] = useLive<MenuState | null>(null)
  const [deleteTarget, setDeleteTarget, deleteTargetRef] = useLive<string | null>(null)
  const [deleteFolderTarget, setDeleteFolderTarget, deleteFolderTargetRef] =
    useLive<string | null>(null)

  const deleteOpener = useRef<HTMLElement | null>(null)
  const deleteFolderOpener = useRef<HTMLElement | null>(null)

  async function toggleMenu(
    event: ReactMouseEvent<HTMLButtonElement>,
    path: string,
  ): Promise<void> {
    if (menuRef.current?.path === path) {
      closeMenu()
      return
    }
    const anchor = event.currentTarget
    setMenu({ path, style: { left: '0px', top: '0px' } })
    await nextFrame()
    const pop = document.querySelector<HTMLElement>('.editor-file-pop')
    if (pop === null) return
    const box = anchor.getBoundingClientRect()
    const width = pop.offsetWidth
    const height = pop.offsetHeight
    const left = Math.round(Math.max(8, Math.min(box.right - width, window.innerWidth - width - 8)))
    const below = box.bottom + 6
    const top = Math.round(below + height > window.innerHeight - 8 ? box.top - 6 - height : below)
    setMenu({ path, style: { left: `${left}px`, top: `${top}px` } })
    await nextFrame()
    pop.querySelector<HTMLButtonElement>('.menu-item')?.focus()
  }

  function closeMenu(focusOpener = false): void {
    const path = menuRef.current?.path
    setMenu(null)
    if (focusOpener && path !== undefined) {
      void nextFrame().then(() => menuButton(path)?.focus())
    }
  }

  /** `CSS.escape` is not in jsdom's older surface, and a relpath's only quotable characters are these. */
  function cssEscape(value: string): string {
    return value.replace(/["\\]/g, '\\$&')
  }

  function menuButton(path: string): HTMLElement | null {
    return document.querySelector<HTMLElement>(`[data-menu="${cssEscape(path)}"]`)
  }

  function startCreate(kind: 'file' | 'dir'): void {
    closeMenu()
    setCreate({ kind, value: '', error: null })
    doc.setQuery('')
    void nextFrame().then(() =>
      document.querySelector<HTMLInputElement>('.editor-create-input')?.focus(),
    )
  }

  function cancelCreate(): void {
    const kind = create?.kind
    setCreate(null)
    void nextFrame().then(() =>
      document.getElementById(kind === 'dir' ? 'edNewFolder' : 'edNewFile')?.focus(),
    )
  }

  async function createItem(): Promise<void> {
    const creating = create
    if (creating === null) return
    // NFC first: the same name typed with combining marks is the same name on macOS, and both
    // sides of every comparison below have to say so before anything is created (v33).
    const path = creating.value.trim().normalize('NFC')
    const issue = createPathError(path, creating.kind, doc.files, doc.localFolders)
    if (issue !== null) {
      setCreate({ ...creating, error: issue })
      return
    }
    if (creating.kind === 'dir') {
      doc.addLocalFolder(path)
      doc.expandAncestors(path)
      setCreate(null)
      void nextFrame().then(() => {
        document
          .querySelector<HTMLElement>(`[data-path="${cssEscape(path)}"] .editor-file-open`)
          ?.focus()
      })
      return
    }
    // The draft row is born with the first content change — a file, even an empty one, is one.
    if (!labels.requireNameForDraft()) return
    const ok = await save.send({ op: 'write', path, text: '' })
    if (!ok) return
    setCreate(null)
    openFile(path)
    void nextFrame().then(() => caret.textarea.current?.focus())
  }

  function beginRename(path: string): void {
    closeMenu()
    doc.expandAncestors(path)
    setRename({ path, value: path, error: null })
    void nextFrame().then(() => {
      const input = document.querySelector<HTMLInputElement>('.editor-rename-input')
      input?.focus()
      input?.setSelectionRange(input.value.length, input.value.length)
    })
  }

  function cancelRename(): void {
    const path = rename?.path
    setRename(null)
    if (path !== undefined) void nextFrame().then(() => menuButton(path)?.focus())
  }

  async function commitRename(): Promise<void> {
    const renaming = rename
    if (renaming === null) return
    const from = renaming.path
    const to = renaming.value.trim().normalize('NFC')
    if (to === from) {
      setRename(null)
      return
    }
    const issue = renamePathError(to, from, doc.files, doc.localFolders)
    if (issue !== null) {
      setRename({ ...renaming, error: issue })
      return
    }
    if (!labels.requireNameForDraft()) return
    const ok = await save.send({ op: 'rename', from, to })
    if (ok) {
      // The file is now somewhere else; a collapsed destination would hide the row the editor just
      // switched to.
      doc.expandAncestors(to)
      setRename(null)
    }
  }

  /** The button the sheet was opened from — where 取消 puts the focus back (design: returnFocus). */
  function askDelete(path: string): void {
    const opener = menuButton(path)
    closeMenu()
    if (!labels.requireNameForDraft()) return
    deleteOpener.current = opener
    setDeleteTarget(path)
  }

  function cancelDelete(): void {
    const opener = deleteOpener.current
    deleteOpener.current = null
    setDeleteTarget(null)
    void nextFrame().then(() => opener?.focus())
  }

  async function confirmDelete(): Promise<void> {
    const path = deleteTargetRef.current
    deleteOpener.current = null
    setDeleteTarget(null)
    if (path === null) return
    await save.send({ op: 'delete', path })
  }

  function askDeleteFolder(path: string): void {
    deleteFolderOpener.current = menuButton(path)
    closeMenu()
    setDeleteFolderTarget(path)
  }

  function cancelDeleteFolder(): void {
    const opener = deleteFolderOpener.current
    deleteFolderOpener.current = null
    setDeleteFolderTarget(null)
    void nextFrame().then(() => opener?.focus())
  }

  function confirmDeleteFolder(): void {
    const path = deleteFolderTargetRef.current
    deleteFolderOpener.current = null
    setDeleteFolderTarget(null)
    if (path === null) return
    doc.setLocalFolders(
      doc.localFoldersRef.current.filter((key) => !(key === path || key.startsWith(`${path}/`))),
    )
  }

  function openFile(path: string): void {
    if (!doc.fileExists(path)) return
    doc.setCurrent(path)
    doc.expandAncestors(path)
    closeMenu()
    caret.setCaret({ line: 1, column: 1 })
    void doc.ensureText(path).then(() => {
      // The read may finish after the reader has moved on; a stale reset would move their caret.
      if (doc.currentRef.current !== path || doc.currentBinaryNow()) return
      void nextFrame().then(() => {
        if (doc.currentRef.current !== path) return
        const element = caret.textarea.current
        if (element === null) return
        element.setSelectionRange(0, 0)
        caret.onCaret()
      })
    })
  }

  function onSearchKeydown(event: ReactKeyboardEvent<HTMLInputElement>): void {
    if (event.key !== 'Escape') return
    event.preventDefault()
    doc.setQuery('')
  }

  function clearSearch(): void {
    doc.setQuery('')
    void nextFrame().then(() => document.getElementById('edFileQuery')?.focus())
  }

  /**
   * Uploads the chosen files into the open file's directory.
   *
   * The directory is the open file's, not a selected one: the tree has no "selected folder" state,
   * and the file being edited is the only place the user has told us they are. Sequential rather
   * than parallel: each upload moves the draft's baseline, and the next one has to be based on it.
   */
  async function uploadFiles(files: File[]): Promise<void> {
    if (save.saving || files.length === 0) return
    const current = doc.currentRef.current
    const slash = current.lastIndexOf('/')
    const directory = slash < 0 ? '' : current.slice(0, slash + 1)
    // A file replacing one already at the same path is not a new file for the ceilings; count only
    // what the batch actually adds.
    const targetPaths = new Set(files.map((file) => directory + file.name))
    const existing = doc.filesRef.current.filter((file) => !targetPaths.has(file.relpath))
    const refusal = uploadRefusal(files, existing)
    if (refusal !== null) {
      save.showFailure('', refusal, '上传失败。')
      return
    }
    for (const [index, file] of files.entries()) {
      // The first file clears the previous banner; the rest keep it, so a failure in the middle of
      // the batch is still on screen when the loop ends (PRD A4/A9).
      await save.upload(file, directory + file.name, index === 0)
    }
  }

  return {
    create,
    setCreate,
    rename,
    setRename,
    menu,
    menuRef,
    deleteTarget,
    deleteTargetRef,
    deleteFolderTarget,
    deleteFolderTargetRef,
    toggleMenu,
    closeMenu,
    startCreate,
    cancelCreate,
    createItem,
    beginRename,
    cancelRename,
    commitRename,
    askDelete,
    cancelDelete,
    confirmDelete,
    askDeleteFolder,
    cancelDeleteFolder,
    confirmDeleteFolder,
    openFile,
    toggleFolder: doc.toggleFolder,
    onSearchKeydown,
    clearSearch,
    uploadFiles,
  }
}

export type EditorTree = ReturnType<typeof useEditorTree>
