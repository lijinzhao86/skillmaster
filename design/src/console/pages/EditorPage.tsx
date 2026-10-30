/**
 * 编辑版本: one version's files, its name and note, and the three ways a save can fail.
 *
 * **Two shapes, one page.** A **new version** has no row yet — the header's name field, the chip
 * saying 尚未保存, and the hint about the first content change. A **saved draft** has the same
 * fields holding its name and note, the chip saying 草稿, and the short code in the facts line.
 * The workspace is identical; only the chip, the facts line and the first-save hint differ.
 *
 * **The first content change turns a new version into a draft (2026-10-21 audit).** Saving the
 * body and any structural change both count; creating an empty folder does not — it does not reach
 * the server. Before that first change the name is required: creating a row needs a name.
 *
 * **The labels write on blur (2026-10-20).** The name is required, and clearing it restores the
 * previous one; a draft with no name would be refused by the server anyway (ADR 0055).
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { FormEvent, MouseEvent as ReactMouseEvent } from 'react'
import { go, replace } from '../address'
import { dialogs } from '../components/dialogs/store'
import EditorTree from '../components/editor/EditorTree'
import EditorFileMenu from '../components/editor/EditorFileMenu'
import type { EditorMenuItem } from '../components/editor/EditorFileMenu'
import type { DemoPageProps } from './types'
import {
  BLANK_TEXT,
  DRAFT,
  ED_TEXT,
  OTHER_WRITER,
  ancestorsOf,
  clampPoints,
  folderExists,
  lineNumbers,
  sizeLabel,
  validateCreatePath,
  validateNameCollision,
  validatePath,
} from '../fixtures/editor'
import type { CreateKind, EditorFile, FolderMap } from '../fixtures/editor'

const VERSION_CODE = '7K3M9QX2'

interface MenuState {
  kind: CreateKind
  path: string
  anchor: HTMLButtonElement
}

interface InitialEditor {
  isMissing: boolean
  isLoading: boolean
  blankBase: boolean
  baseDraft: boolean
  files: EditorFile[]
  saved: Record<string, string>
  buffer: Record<string, string>
  dirty: Record<string, boolean>
  current: string
  expanded: FolderMap
  emptyFolders: FolderMap
  creating: { kind: CreateKind } | null
  createPreset: string
  createError: string
  editing: string | null
  renameValue: string
  renameError: string
  isNew: boolean
  baseCount: number
  name: string
  note: string
  label: { text: string; danger: boolean }
  conflictOpen: boolean
  duplicateOpen: boolean
  duplicateDraft: boolean
  errorOpen: boolean
  deleteOnMount: boolean
  leaveOnMount: boolean
  caretToEnd: boolean
}

function moveKey<T>(record: Record<string, T>, from: string, to: string): Record<string, T> {
  const next = { ...record }
  if (from in next) {
    next[to] = next[from]
    delete next[from]
  }
  return next
}

function removeKey<T>(record: Record<string, T>, key: string): Record<string, T> {
  const next = { ...record }
  delete next[key]
  return next
}

function initialEditor(state: string, params: URLSearchParams): InitialEditor {
  const isMissing = state === 'missing' || state === 'denied'
  const isLoading = state === 'loading'
  const blankBase = state === 'blank'
  const baseDraft = state === 'base-draft'
  const isNew = state === 'new' || blankBase || baseDraft
  const binaryOpen = state === 'binary'

  let files: EditorFile[] = [
    { path: 'SKILL.md', size: '1.8 KB', text: ED_TEXT['SKILL.md'] },
    { path: 'references/split.md', size: '3.2 KB', text: ED_TEXT['references/split.md'] },
    { path: 'scripts/compress.sh', size: '0.6 KB', text: ED_TEXT['scripts/compress.sh'] },
  ]
  /* 空白底稿: no version at all, so the editor carries one minimal SKILL.md. It is still a
     normal parse, and a name that disagrees with the address is refused as usual. */
  if (blankBase) {
    files = [{ path: 'SKILL.md', size: '0.3 KB', text: BLANK_TEXT }]
  }
  if (binaryOpen) {
    files = [...files, { path: 'assets/manual.pdf', size: '412 KB', binary: true }]
  }

  /* The base's file count: for a new version the header's N means this, not the workspace's
     temporary edits. */
  const baseCount = files.length

  const saved: Record<string, string> = {}
  const buffer: Record<string, string> = {}
  const dirty: Record<string, boolean> = {}
  files.forEach((file) => {
    saved[file.path] = file.text || ''
    buffer[file.path] = file.text || ''
    dirty[file.path] = false
  })

  const asked = params.get('file')
  let current = asked !== null && Object.prototype.hasOwnProperty.call(buffer, asked)
    ? asked
    : (binaryOpen ? 'assets/manual.pdf' : 'SKILL.md')

  const expanded: FolderMap = {}
  if (state === 'empty-folder') expanded.guides = true
  if (state === 'rename' || state === 'delete') expanded.references = true
  ancestorsOf(current).forEach((path) => { expanded[path] = true })

  /* **空文件夹只活在这一页。** The manifest has files only, so a folder with nothing in it is a
     grouping this page remembers for as long as it is open. Creating the first file inside it is
     what makes it real. */
  const emptyFolders: FolderMap = state === 'empty-folder' ? { 'guides/advanced': true } : {}

  let creating: { kind: CreateKind } | null = null
  let createPreset = ''
  let createError = ''
  if (state === 'new-file' || state === 'new-file-error' || state === 'new-file-bad-name') {
    creating = { kind: 'file' }
    if (state === 'new-file-error') {
      createPreset = 'references/split.md'
      createError = '该路径已存在文件。'
    } else if (state === 'new-file-bad-name') {
      /* 跨系统的名字（v33）：`CON.md` 在 Windows 上是设备名。句子与产品、CLI 共用一套。 */
      createPreset = 'CON.md'
      createError = 'CON 是 Windows 保留的设备名。'
    } else {
      createPreset = 'references/api.md'
    }
  } else if (state === 'new-folder') {
    creating = { kind: 'dir' }
    createPreset = 'guides/advanced'
  }

  let editing: string | null = null
  let renameValue = ''
  let renameError = ''
  if (state === 'rename') {
    editing = 'references/split.md'
    renameValue = 'scripts/compress.sh'
    renameError = '该路径已存在文件。'
  }

  let label: { text: string; danger: boolean } = { text: '', danger: false }
  if (state === 'note') label = { text: '说明已保存。', danger: false }
  if (state === 'name') label = { text: '版本名称已保存。', danger: false }
  if (state === 'name-error') label = { text: '版本名称不能为空，已恢复原名称。', danger: true }
  if (state === 'label-error') label = { text: '说明没有保存：无法连接服务端，请重试。', danger: true }

  const conflictOpen = state === 'conflict'
  const duplicateOpen = state === 'duplicate' || state === 'duplicate-draft'
  const duplicateDraft = state === 'duplicate-draft'
  const errorOpen = state === 'error'

  if (['dirty', 'conflict', 'duplicate', 'duplicate-draft', 'error'].indexOf(state) >= 0) {
    buffer['SKILL.md'] = ED_TEXT['SKILL.md'] + '\n- split --pages 1-3 — 取前三页\n'
    dirty['SKILL.md'] = true
    current = 'SKILL.md'
  }
  if (state === 'leave') {
    buffer['SKILL.md'] = ED_TEXT['SKILL.md'] + '\n- 再加一行还没保存的说明。\n'
    dirty['SKILL.md'] = true
  }

  const draftName = DRAFT.draftName
  const draftNote = DRAFT.note
  const name = isNew ? '' : draftName
  const note = isNew ? '' : draftNote

  return {
    isMissing,
    isLoading,
    blankBase,
    baseDraft,
    files,
    saved,
    buffer,
    dirty,
    current,
    expanded,
    emptyFolders,
    creating,
    createPreset,
    createError,
    editing,
    renameValue,
    renameError,
    isNew,
    baseCount,
    name,
    note,
    label,
    conflictOpen,
    duplicateOpen,
    duplicateDraft,
    errorOpen,
    deleteOnMount: state === 'delete',
    leaveOnMount: state === 'leave',
    /* The states below append a line to the buffer and redraw without resetting the caret. Assigning
       a textarea's `value` puts the caret at the end, so the old screen said 第 16 行第 1 列 — the
       empty line after the line it had just added. The caret is part of that state. */
    caretToEnd: state === 'leave' ||
      ['dirty', 'conflict', 'duplicate', 'duplicate-draft', 'error'].indexOf(state) >= 0,
  }
}

export default function EditorPage({ active, state, params }: DemoPageProps) {
  const [initial] = useState(() => initialEditor(state, params))

  const [files, setFiles] = useState<EditorFile[]>(initial.files)
  const [saved, setSaved] = useState<Record<string, string>>(initial.saved)
  const [buffer, setBuffer] = useState<Record<string, string>>(initial.buffer)
  const [dirty, setDirty] = useState<Record<string, boolean>>(initial.dirty)
  const [current, setCurrent] = useState(initial.current)
  const [expanded, setExpanded] = useState<FolderMap>(initial.expanded)
  const [emptyFolders, setEmptyFolders] = useState<FolderMap>(initial.emptyFolders)
  const [creating, setCreating] = useState<{ kind: CreateKind } | null>(initial.creating)
  const [createPreset, setCreatePreset] = useState(initial.createPreset)
  const [createError, setCreateError] = useState(initial.createError)
  const [createToken, setCreateToken] = useState(0)
  const [editing, setEditing] = useState<string | null>(initial.editing)
  const [renameValue, setRenameValue] = useState(initial.renameValue)
  const [renameError, setRenameError] = useState(initial.renameError)
  const [renameToken, setRenameToken] = useState(0)
  const [isNew, setIsNew] = useState(initial.isNew)
  const [draftName, setDraftName] = useState(DRAFT.draftName)
  const [draftNote, setDraftNote] = useState(DRAFT.note)
  const [submitter, setSubmitter] = useState(DRAFT.by)
  const [nameText, setNameText] = useState(initial.name)
  const [noteText, setNoteText] = useState(initial.note)
  const [query, setQuery] = useState('')
  const [saving, setSaving] = useState(false)
  const [label, setLabel] = useState(initial.label)
  const [conflictOpen, setConflictOpen] = useState(initial.conflictOpen)
  const [duplicateOpen] = useState(initial.duplicateOpen)
  const [errorOpen, setErrorOpen] = useState(initial.errorOpen)
  const [menu, setMenu] = useState<MenuState | null>(null)
  const [treeOpen, setTreeOpen] = useState(true)
  const [cursor, setCursor] = useState('第 1 行，第 1 列')

  const nameRef = useRef<HTMLInputElement>(null)
  const noteRef = useRef<HTMLInputElement>(null)
  const textRef = useRef<HTMLTextAreaElement>(null)
  const gutterRef = useRef<HTMLDivElement>(null)
  const queryRef = useRef<HTMLInputElement>(null)
  const workspaceRef = useRef<HTMLDivElement>(null)
  const conflictRef = useRef<HTMLDivElement>(null)
  const duplicateRef = useRef<HTMLDivElement>(null)
  const errorRef = useRef<HTMLDivElement>(null)
  /* The page opens at the top of the file, the way `renderOpen(true)` did on first draw. */
  const resetCaretRef = useRef(true)
  const caretToEndRef = useRef(initial.caretToEnd)
  const focusTextRef = useRef(false)
  /* Esc puts the focus back on the header icon that opened the row. The initial `new-folder`
     state did not come through a click, so — as in the old page — its default is the file icon. */
  const createOpenerRef = useRef<'edNewFile' | 'edNewFolder'>('edNewFile')

  /* Values the 350 ms save timer and the beforeunload guard read after the render that started
     them. The demo has no server, so "saving" is the one delay in the page. */
  const bufferRef = useRef(buffer)
  bufferRef.current = buffer
  const currentRef = useRef(current)
  currentRef.current = current
  const isNewRef = useRef(isNew)
  isNewRef.current = isNew
  const draftNameRef = useRef(draftName)
  draftNameRef.current = draftName
  const draftNoteRef = useRef(draftNote)
  draftNoteRef.current = draftNote

  const file = files.find((candidate) => candidate.path === current)
  const binary = file?.binary === true
  const lineText = buffer[current] ?? ''
  const info = binary
    ? (file?.size || '412 KB')
    : sizeLabel(lineText) + ' · UTF-8'
  const dirtyCount = Object.keys(dirty).filter((path) => dirty[path]).length
  const anyDirty = dirtyCount > 0
  const nameOk = !isNew || nameText.trim() !== ''
  const saveDisabled = saving || !dirty[current] || binary || !nameOk

  const anyDirtyRef = useRef(anyDirty)
  anyDirtyRef.current = anyDirty
  const saveDisabledRef = useRef(saveDisabled)
  saveDisabledRef.current = saveDisabled
  /* All six pages are mounted at once. The old editor's global shortcuts and unload guard existed
     only on the editor page, so the hidden copies must not answer for it. */
  const activeRef = useRef(active)
  activeRef.current = active

  const showLabelStatus = (text: string, danger: boolean) => {
    setLabel({ text, danger })
  }

  const expandAncestors = (path: string) => {
    setExpanded((previous) => {
      const next = { ...previous }
      ancestorsOf(path).forEach((folder) => { next[folder] = true })
      return next
    })
  }

  const requireNameForDraft = () => {
    if (!isNewRef.current) return true
    if ((nameRef.current?.value ?? '').trim() !== '') return true
    showLabelStatus('请先填写版本名称。', true)
    nameRef.current?.focus()
    return false
  }

  /**
   * **The first content change turns a new version into a draft.** Saving the body and any
   * structural change count; the address follows, because the draft now exists.
   */
  const becomeDraft = (at: string) => {
    if (!isNewRef.current) return
    isNewRef.current = false
    setIsNew(false)
    const nextName = clampPoints((nameRef.current?.value ?? '').trim(), 40) || draftNameRef.current
    const nextNote = clampPoints((noteRef.current?.value ?? '').trim(), 200)
    draftNameRef.current = nextName
    draftNoteRef.current = nextNote
    setDraftName(nextName)
    setDraftNote(nextNote)
    setSubmitter('dev')
    const next = new URLSearchParams(window.location.search)
    next.set('page', 'editor')
    next.set('state', 'draft')
    next.set('version', VERSION_CODE)
    next.set('file', at)
    replace('?' + next.toString())
  }

  const rowAt = (path: string) => Array.from(
    document.querySelectorAll<HTMLElement>('#edFiles .editor-file'),
  ).find((row) => row.getAttribute('data-path') === path)

  const focusRowOpen = (path: string) => {
    rowAt(path)?.querySelector<HTMLElement>('.editor-file-open')?.focus()
  }

  const focusRowMenu = (path: string) => {
    rowAt(path)?.querySelector<HTMLElement>('.editor-file-menu')?.focus()
  }

  const commitName = () => {
    if (isNewRef.current) return
    const element = nameRef.current
    if (!element) return
    const value = clampPoints(element.value.trim(), 40)
    if (value === '') {
      element.value = draftNameRef.current
      setNameText(draftNameRef.current)
      showLabelStatus('版本名称不能为空，已恢复原名称。', true)
      return
    }
    if (value === draftNameRef.current) {
      element.value = draftNameRef.current
      setNameText(draftNameRef.current)
      return
    }
    draftNameRef.current = value
    setDraftName(value)
    element.value = value
    setNameText(value)
    showLabelStatus('版本名称已保存。', false)
  }

  const commitNote = () => {
    if (isNewRef.current) return
    const element = noteRef.current
    if (!element) return
    const value = clampPoints(element.value.trim(), 200)
    if (value === draftNoteRef.current) {
      element.value = draftNoteRef.current
      setNoteText(draftNoteRef.current)
      return
    }
    draftNoteRef.current = value
    setDraftNote(value)
    element.value = value
    setNoteText(value)
    showLabelStatus('说明已保存。', false)
  }

  const select = (path: string) => {
    setCurrent(path)
    expandAncestors(path)
    resetCaretRef.current = true
  }

  const toggleDir = (path: string) => {
    setExpanded((previous) => {
      const isOpen = query.trim() !== '' || previous[path] === true
      return { ...previous, [path]: !isOpen }
    })
  }

  const startCreate = (kind: CreateKind) => {
    createOpenerRef.current = kind === 'dir' ? 'edNewFolder' : 'edNewFile'
    setMenu(null)
    setCreating({ kind })
    setCreatePreset('')
    setCreateError('')
    setQuery('')
    setCreateToken((token) => token + 1)
  }

  const cancelCreate = () => {
    setCreating(null)
    setCreatePreset('')
    setCreateError('')
    document.getElementById(createOpenerRef.current)?.focus()
  }

  const createItem = (value: string) => {
    if (!creating) return
    const kind = creating.kind
    /* NFC first: the same name typed with combining marks is the same name on macOS, and both
       sides of the comparison below have to say so before anything is created. */
    const path = value.trim().normalize('NFC')
    const issue = validateCreatePath(files, emptyFolders, path, kind)
    if (issue) {
      setCreatePreset(path)
      setCreateError(issue)
      setCreateToken((token) => token + 1)
      return
    }
    if (kind === 'file' && !requireNameForDraft()) return
    setCreating(null)
    setCreatePreset('')
    setCreateError('')
    if (kind === 'dir') {
      setEmptyFolders((previous) => ({ ...previous, [path]: true }))
      expandAncestors(path)
      window.setTimeout(() => focusRowOpen(path), 0)
      return
    }
    setFiles((previous) => [...previous, { path, size: '0 B', text: '' }])
    setSaved((previous) => ({ ...previous, [path]: '' }))
    setBuffer((previous) => ({ ...previous, [path]: '' }))
    setDirty((previous) => ({ ...previous, [path]: false }))
    setCurrent(path)
    expandAncestors(path)
    becomeDraft(path)
    resetCaretRef.current = true
    focusTextRef.current = true
  }

  const beginRename = (path: string) => {
    setEditing(path)
    setRenameValue(path)
    setRenameError('')
    expandAncestors(path)
    setRenameToken((token) => token + 1)
  }

  const commitRename = (value: string) => {
    if (editing === null) return
    const from = editing
    const path = value.trim().normalize('NFC')
    const issue = validatePath(path)
    if (issue) {
      setRenameError(issue)
      return
    }
    /* Enter on the same path is a no-op, not "the target already exists". */
    if (path === from) {
      setEditing(null)
      return
    }
    if (!requireNameForDraft()) return
    if (files.some((candidate) => candidate.path === path)) {
      setRenameError('该路径已存在文件。')
      return
    }
    if (folderExists(files, emptyFolders, path)) {
      setRenameError('该路径已是文件夹。')
      return
    }
    if (files.some((candidate) => path.indexOf(candidate.path + '/') === 0)) {
      setRenameError('该路径已是文件。')
      return
    }
    /* 改名不该跟自己撞：`from` 那一行不参与碰撞。 */
    const collision = validateNameCollision(files, emptyFolders, path, from)
    if (collision) {
      setRenameError(collision)
      return
    }
    if (!files.some((candidate) => candidate.path === from)) return

    setFiles((previous) => previous.map(
      (candidate) => (candidate.path === from ? { ...candidate, path } : candidate),
    ))
    setSaved((previous) => moveKey(previous, from, path))
    setBuffer((previous) => moveKey(previous, from, path))
    setDirty((previous) => moveKey(previous, from, path))
    const nextCurrent = current === from ? path : current
    setCurrent(nextCurrent)
    setEditing(null)
    expandAncestors(path)
    becomeDraft(nextCurrent)
  }

  const cancelRename = () => {
    const from = editing
    setEditing(null)
    setRenameError('')
    if (from) window.setTimeout(() => focusRowMenu(from), 0)
  }

  const closeMenu = (refocus: boolean) => {
    const anchor = menu?.anchor
    setMenu(null)
    if (refocus) anchor?.focus()
  }

  const askDelete = (path: string, anchor: HTMLElement | null) => {
    if (!requireNameForDraft()) return
    const warn = dirty[path] ? ' 该文件未保存的改动也会丢失。' : ''
    dialogs.openDanger({
      title: '删除文件？',
      body: path + ' 将从版本中移除。' + warn,
      confirmLabel: '删除文件',
      danger: true,
      returnFocus: anchor,
      onConfirm: () => {
        const nextFiles = files.filter((candidate) => candidate.path !== path)
        setFiles(nextFiles)
        setSaved((previous) => removeKey(previous, path))
        setBuffer((previous) => removeKey(previous, path))
        setDirty((previous) => removeKey(previous, path))
        setEditing(null)
        const nextCurrent = current === path ? (nextFiles[0]?.path ?? 'SKILL.md') : current
        setCurrent(nextCurrent)
        becomeDraft(nextCurrent)
      },
    })
  }

  const askDeleteFolder = (path: string, anchor: HTMLElement | null) => {
    dialogs.openDanger({
      title: '删除文件夹？',
      body: path + ' 是空文件夹，删除不会改变版本内容。',
      confirmLabel: '删除文件夹',
      /* It takes nothing away — an empty folder never entered the version — so the answer is not
         painted red. */
      danger: false,
      returnFocus: anchor,
      onConfirm: () => {
        setEmptyFolders((previous) => {
          const next = { ...previous }
          Object.keys(next).forEach((key) => {
            if (key === path || key.indexOf(path + '/') === 0) delete next[key]
          })
          return next
        })
      },
    })
  }

  const askLeave = (href: string | null, opener: HTMLElement | null) => {
    const count = Object.keys(dirty).filter((path) => dirty[path]).length
    dialogs.openDanger({
      title: '有 ' + count + ' 个文件未保存',
      body: '离开此页将丢失这些改动。',
      confirmLabel: '放弃并离开',
      cancelLabel: '继续编辑',
      danger: true,
      returnFocus: opener,
      onConfirm: () => {
        if (href) go(href)
      },
    })
  }

  /* **Every in-page way out asks once**: the breadcrumb, 查看版本详情, and the duplicate banner's
     version link. Guarding only the breadcrumb would leave two ways around the unsaved work. */
  const guardLeave = (event: ReactMouseEvent<HTMLAnchorElement>) => {
    if (!anyDirty) return
    event.preventDefault()
    askLeave(event.currentTarget.getAttribute('href'), event.currentTarget)
  }

  const save = () => {
    if (saveDisabledRef.current) return
    setSaving(true)
    window.setTimeout(() => {
      const at = currentRef.current
      if (isNewRef.current) becomeDraft(at)
      setSaved((previous) => ({ ...previous, [at]: bufferRef.current[at] ?? '' }))
      setDirty((previous) => ({ ...previous, [at]: false }))
      setSaving(false)
    }, 350)
  }
  const saveRef = useRef(save)
  saveRef.current = save

  const changeText = (value: string, caret?: number) => {
    setBuffer((previous) => ({ ...previous, [current]: value }))
    setDirty((previous) => ({ ...previous, [current]: value !== (saved[current] ?? '') }))
    if (caret !== undefined) textRef.current?.setSelectionRange(caret, caret)
  }

  const updateCursor = (element?: HTMLTextAreaElement) => {
    const node = element ?? textRef.current
    if (!node) return
    const position = node.selectionStart ?? 0
    const before = (bufferRef.current[currentRef.current] ?? '').slice(0, position)
    const line = before.split('\n').length
    const column = position - before.lastIndexOf('\n')
    setCursor('第 ' + line + ' 行，第 ' + column + ' 列')
  }

  const onTextInput = (event: FormEvent<HTMLTextAreaElement>) => {
    changeText(event.currentTarget.value)
    updateCursor(event.currentTarget)
  }

  const onNameInput = (event: FormEvent<HTMLInputElement>) => {
    const element = event.currentTarget
    const clamped = clampPoints(element.value, 40)
    if (clamped !== element.value) element.value = clamped
    setNameText(clamped)
    showLabelStatus('', false)
  }

  const onNoteInput = (event: FormEvent<HTMLInputElement>) => {
    const element = event.currentTarget
    const clamped = clampPoints(element.value, 200)
    if (clamped !== element.value) element.value = clamped
    setNoteText(clamped)
    showLabelStatus('', false)
  }

  const clearQuery = (focusInput: boolean) => {
    setQuery('')
    if (focusInput) queryRef.current?.focus()
  }

  const onQueryInput = (event: FormEvent<HTMLInputElement>) => {
    setQuery(event.currentTarget.value)
  }

  /* Cursor follows the selection; a file switch updates it once the textarea holds the new text. */
  useEffect(() => {
    if (binary) return
    updateCursor()
  }, [buffer, current, binary])

  useEffect(() => {
    if (binary || !resetCaretRef.current) return
    resetCaretRef.current = false
    const node = textRef.current
    if (node) {
      const at = caretToEndRef.current ? node.value.length : 0
      node.setSelectionRange(at, at)
    }
    /* The end-of-buffer caret belongs to the initial state that appended a line; every later
       reset (opening another file, creating one) starts at the top. */
    caretToEndRef.current = false
    updateCursor()
  }, [current, binary])

  useEffect(() => {
    if (!focusTextRef.current) return
    focusTextRef.current = false
    textRef.current?.focus()
  }, [current])

  /* The narrow layout folds the tree; the address names its initial fold so the state can be
     linked to. */
  useEffect(() => {
    if (window.innerWidth <= 900) setTreeOpen(params.get('tree') === 'open')
  }, [])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!activeRef.current) return
      if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== 's') return
      event.preventDefault()
      if (!saveDisabledRef.current) saveRef.current()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  useEffect(() => {
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!activeRef.current || !anyDirtyRef.current) return
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [])

  /* The two states whose answer is a sheet: both open it once, after the page is on screen. */
  useEffect(() => {
    if (!active) return
    if (initial.deleteOnMount) {
      /* The row is the opener, so cancelling returns the reader there — the same place the old
         page put them. Read after the tree is on screen; the row may not exist until then. */
      const timer = window.setTimeout(() => {
        const row = rowAt('references/split.md')?.querySelector<HTMLElement>('.editor-file-menu')
        askDelete('references/split.md', row ?? null)
      }, 0)
      return () => window.clearTimeout(timer)
    }
    if (initial.leaveOnMount) {
      const timer = window.setTimeout(() => askLeave(null, null), 0)
      return () => window.clearTimeout(timer)
    }
    return undefined
  }, [])

  /* **When a banner appears, the workspace gives up its height.** The workspace's height comes
     from the viewport and the banner sits between the head and it; measuring the banner rather
     than hard-coding a number keeps a sentence that wraps on a narrow screen from adding a scroll
     bar. */
  useLayoutEffect(() => {
    const space = workspaceRef.current
    if (!space) return
    const banners = [conflictRef.current, duplicateRef.current, errorRef.current]
    const sync = () => {
      const open = banners.find((banner) => banner !== null && !banner.hidden)
      const height = open
        ? open.getBoundingClientRect().height + (parseFloat(getComputedStyle(open).marginTop) || 0)
        : 0
      space.style.setProperty('--editor-banner', height + 'px')
    }
    const observer = new ResizeObserver(sync)
    banners.forEach((banner) => { if (banner) observer.observe(banner) })
    sync()
    return () => observer.disconnect()
  }, [active, conflictOpen, duplicateOpen, errorOpen])

  const meta = isNew
    ? (initial.blankBase
      ? '空白底稿'
      : initial.baseDraft
        ? '基于草稿 ' + DRAFT.pin + '　·　提交人 ' + DRAFT.by
        : '基于线上版本 @1.2.3') + '　·　' + initial.baseCount + ' 个文件'
    : '草稿　·　' + VERSION_CODE + '　·　提交人 ' + submitter + '　·　' + files.length + ' 个文件'

  const viewHref = isNew ? '#' : '?page=skill&tab=versions&version=' + VERSION_CODE
  const duplicateTitle = initial.duplicateDraft
    ? '该内容已属于草稿 ' + DRAFT.pin + '。'
    : '该内容已属于 @1.2.3。'
  const duplicateLinkText = initial.duplicateDraft ? '查看 ' + DRAFT.pin : '查看 @1.2.3'
  const duplicateHref = initial.duplicateDraft
    ? '?page=skill&tab=versions&version=' + DRAFT.pin
    : '?page=skill&tab=versions&version=1.2.3'

  const menuItems: EditorMenuItem[] = menu === null
    ? []
    : menu.kind === 'dir'
      ? [{ label: '删除文件夹', onSelect: () => askDeleteFolder(menu.path, menu.anchor) }]
      : [
        { label: '重命名', onSelect: () => beginRename(menu.path) },
        { label: '删除', onSelect: () => askDelete(menu.path, menu.anchor) },
      ]

  const headHidden = initial.isMissing || initial.isLoading

  return (
    <section className="page" id="page-editor" hidden={!active}>
      <nav className="crumbs" aria-label="面包屑">
        <a
          href="?page=skill&tab=versions"
          id="edBackCrumb"
          onClick={guardLeave}
        >
          版本
        </a>
        <span className="crumbs-sep">/</span>
        <span className="crumbs-here" id="edCrumb">{isNew ? '新版本' : '编辑'}</span>
      </nav>

      <header className="vp-head editor-head" hidden={headHidden}>
        <h1 className="visually-hidden">编辑版本</h1>
        <div className="editor-head-top">
          <div className="vp-title-row">
            <span className="vp-title-name">
              <input
                className="editor-name-input"
                id="edName"
                type="text"
                placeholder="版本名称"
                aria-label="版本名称（最多 40 个字符）"
                title="最多 40 个字符；中文、英文、数字与符号都可以"
                autoComplete="off"
                spellCheck={false}
                ref={nameRef}
                value={nameText}
                onInput={onNameInput}
                onChange={onNameInput}
                onBlur={commitName}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault()
                    commitName()
                    event.currentTarget.blur()
                  }
                }}
              />
            </span>
            <span className="version-states" id="edStates">
              <span className={'chip ' + (isNew ? 'is-plain' : 'is-draft')}>
                <i className="dot"></i>
                <span className="chip-text">{isNew ? '尚未保存' : '草稿'}</span>
              </span>
            </span>
          </div>
          <div className="vp-head-act">
            <a className="button is-quiet" id="edView" href={viewHref} hidden={isNew} onClick={guardLeave}>
              查看版本详情
            </a>
            <button type="button" className="button" id="edSave" disabled={saveDisabled} onClick={save}>
              {saving ? '保存中…' : '保存'}
            </button>
          </div>
        </div>
        <div className="editor-label-row">
          <label className="editor-label" htmlFor="edNote">说明</label>
          <input
            className="control"
            id="edNote"
            type="text"
            placeholder="说明（可选）"
            aria-label="说明"
            autoComplete="off"
            spellCheck={false}
            ref={noteRef}
            value={noteText}
            onInput={onNoteInput}
            onChange={onNoteInput}
            onBlur={commitNote}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                commitNote()
                event.currentTarget.blur()
              }
            }}
          />
        </div>
        <p
          className={'editor-label-status' + (label.danger ? ' is-danger' : '')}
          id="edLabelStatus"
          hidden={label.text === ''}
        >
          {label.text}
        </p>
        <p className="editor-facts">
          <span className="vp-meta" id="edMeta">{meta}</span>
          <span className="editor-hint" id="edHint" hidden={!isNew}>
            首次内容改动时创建版本，随后显示在版本列表中。
          </span>
        </p>
      </header>

      <div className="editor-banner is-danger" id="edConflict" hidden={headHidden || !conflictOpen} ref={conflictRef}>
        <div className="editor-banner-text">
          <span className="editor-banner-title">该版本在编辑期间已被修改。</span>
          <span className="editor-banner-note">重新加载将丢弃此页未保存的改动；覆盖将用当前内容重写。</span>
        </div>
        <div className="editor-banner-acts">
          <button
            type="button"
            className="button is-quiet"
            id="edReload"
            onClick={() => {
              const at = currentRef.current
              setSaved((previous) => ({ ...previous, [at]: OTHER_WRITER }))
              setBuffer((previous) => ({ ...previous, [at]: OTHER_WRITER }))
              setDirty((previous) => ({ ...previous, [at]: false }))
              setConflictOpen(false)
            }}
          >
            重新加载
          </button>
          <button
            type="button"
            className="button"
            id="edOverwrite"
            onClick={() => {
              const at = currentRef.current
              setSaved((previous) => ({ ...previous, [at]: bufferRef.current[at] ?? '' }))
              setDirty((previous) => ({ ...previous, [at]: false }))
              setConflictOpen(false)
            }}
          >
            覆盖
          </button>
        </div>
      </div>
      <div className="editor-banner" id="edDuplicate" hidden={headHidden || !duplicateOpen} ref={duplicateRef}>
        <div className="editor-banner-text">
          <span className="editor-banner-title" id="edDuplicateTitle">{duplicateTitle}</span>
          <span className="editor-banner-note">同一 skill 下不能存在相同内容。请修改后保存，或查看该版本。</span>
        </div>
        <div className="editor-banner-acts">
          <a className="button is-quiet" id="edDuplicateLink" href={duplicateHref} onClick={guardLeave}>
            {duplicateLinkText}
          </a>
        </div>
      </div>
      <div className="editor-banner is-danger" id="edError" hidden={headHidden || !errorOpen} ref={errorRef}>
        <div className="editor-banner-text">
          <span className="editor-banner-title" id="edErrorTitle">保存失败。</span>
          <span className="editor-banner-note" id="edErrorText">
            SKILL.md 的 name 与地址不一致：文件为 pdf-tools，地址为 pdf-tool。修改后可再次保存。
          </span>
        </div>
        <div className="editor-banner-acts">
          <button type="button" className="link-button" id="edErrorDismiss" onClick={() => setErrorOpen(false)}>
            关闭
          </button>
        </div>
      </div>

      <div className="editor-workspace" id="edWorkspace" ref={workspaceRef} hidden={headHidden}>
        <aside className={'editor-tree' + (treeOpen ? '' : ' is-collapsed')} aria-label="文件">
          {/* The narrow layout's one bar: the whole strip opens and closes; the wide layout does
              not paint it. */}
          <button
            type="button"
            className="editor-tree-toggle"
            id="edTreeToggle"
            aria-expanded={treeOpen ? 'true' : 'false'}
            aria-controls="edFiles"
            onClick={() => setTreeOpen((open) => !open)}
          >
            文件 <span className="count" id="edTreeToggleCount">{files.length}</span>
            <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M4 6l4 4 4-4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          <div className="editor-tree-head">
            <span className="editor-tree-title">
              文件 <span className="count" id="edFileCount">{files.length}</span>
            </span>
            {/* Two icons, like VS Code's EXPLORER header: new file and new folder beside the
                title, each with its own tooltip and aria-label. Pressing one does not open a
                form; it grows a row at the top of the tree. */}
            <span className="editor-tree-acts">
              <button
                type="button"
                className="editor-icon-button"
                id="edNewFile"
                aria-label="新建文件"
                title="新建文件"
                onClick={() => startCreate('file')}
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M6 3.5h7L17.5 8v5.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
                  <path d="M13 3.5V8h4.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
                  <path d="M6 3.5V20a1 1 0 0 0 1 1h6" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
                  <path d="M18 15v6M15 18h6" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
                </svg>
              </button>
              <button
                type="button"
                className="editor-icon-button"
                id="edNewFolder"
                aria-label="新建文件夹"
                title="新建文件夹"
                onClick={() => startCreate('dir')}
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M3 7a2 2 0 0 1 2-2h4l2 2h4a2 2 0 0 1 2 2v4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
                  <path d="M3 7v10a2 2 0 0 0 2 2h8" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
                  <path d="M18 15v6M15 18h6" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
                </svg>
              </button>
            </span>
          </div>
          <label className="search editor-search">
            <span className="visually-hidden">搜索文件</span>
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
              <line x1="15.4" y1="15.4" x2="20.6" y2="20.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            <input
              id="edFileQuery"
              className="control"
              type="search"
              autoComplete="off"
              spellCheck={false}
              aria-label="搜索文件路径"
              placeholder="搜索文件"
              ref={queryRef}
              value={query}
              onInput={onQueryInput}
              onChange={onQueryInput}
              onKeyDown={(event) => {
                if (event.key === 'Escape') {
                  event.preventDefault()
                  setQuery('')
                }
              }}
            />
            <button
              type="button"
              className="search-clear"
              id="edFileClear"
              aria-label="清除搜索"
              hidden={query === ''}
              onClick={() => clearQuery(true)}
            >
              ×
            </button>
          </label>
          <EditorTree
            files={files}
            current={current}
            dirty={dirty}
            query={query}
            expanded={expanded}
            emptyFolders={emptyFolders}
            creating={creating}
            createPreset={createPreset}
            createError={createError}
            createToken={createToken}
            editing={editing}
            renameValue={renameValue}
            renameError={renameError}
            renameToken={renameToken}
            onSelect={select}
            onToggleDir={toggleDir}
            onMenu={(kind, path, anchor) => setMenu({ kind, path, anchor })}
            onCreateChange={(value) => {
              setCreatePreset(value)
              setCreateError('')
            }}
            onCreateCommit={createItem}
            onCreateCancel={cancelCreate}
            onRenameChange={setRenameValue}
            onRenameCommit={commitRename}
            onRenameCancel={cancelRename}
            onClearSearch={() => clearQuery(false)}
          />
        </aside>

        <div className="editor-pane">
          <div className="editor-bar">
            {/* The same file icon as the tree: this bar says which of the tree's rows you are
                editing. */}
            <svg className="editor-bar-icon" viewBox="0 0 24 24" aria-hidden="true"><use href="#fileMark" /></svg>
            <span className="editor-path" id="edPath">{current}</span>
            <span className="editor-unsaved" id="edUnsaved" hidden={!dirty[current]}>
              <i className="dot"></i>未保存的改动
            </span>
          </div>
          <div className="editor-body">
            <div className="editor-gutter" id="edGutter" aria-hidden="true" hidden={binary} ref={gutterRef}>
              {binary ? '' : lineNumbers(lineText)}
            </div>
            <textarea
              className="editor-text"
              id="edText"
              spellCheck={false}
              aria-label="文件内容"
              hidden={binary}
              ref={textRef}
              value={binary ? '' : lineText}
              onInput={onTextInput}
              onChange={onTextInput}
              onKeyDown={(event) => {
                /* Tab indents in an editor; it does not leave it. Two spaces, matching this
                   version's decision not to offer indentation settings. */
                if (event.key !== 'Tab') return
                event.preventDefault()
                const element = event.currentTarget
                const start = element.selectionStart ?? 0
                const end = element.selectionEnd ?? 0
                const value = element.value.slice(0, start) + '  ' + element.value.slice(end)
                const caret = start + 2
                element.value = value
                element.setSelectionRange(caret, caret)
                changeText(value, caret)
                updateCursor(element)
              }}
              onClick={(event) => updateCursor(event.currentTarget)}
              onKeyUp={(event) => updateCursor(event.currentTarget)}
              onSelect={(event) => updateCursor(event.currentTarget)}
              onScroll={(event) => {
                if (gutterRef.current) gutterRef.current.scrollTop = event.currentTarget.scrollTop
              }}
            ></textarea>
            <div className="editor-binary" id="edBinary" hidden={!binary}>
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><use href="#fileMark" /></svg>
              <p><strong>二进制文件不能在此编辑。</strong><br />请下载到本地修改，再通过 CLI 写回。</p>
              <a className="button is-quiet" id="edDownload" href="#">下载文件</a>
            </div>
          </div>
          <div className="editor-foot">
            <span id="edCursor">{binary ? '只读' : cursor}</span>
            <span id="edInfo">{info}</span>
          </div>
        </div>
      </div>

      <div className="editor-loading" id="edLoading" hidden={!initial.isLoading}>加载中…</div>
      <div className="editor-missing" id="edMissing" hidden={!initial.isMissing}>
        <div className="notfound">
          <h2>没有这一版</h2>
          <p className="hint">它可能已经被丢弃，或者不属于你能编辑的版本。</p>
          <p><a href="?page=skill&tab=versions">回到版本列表</a></p>
        </div>
      </div>

      {menu !== null && (
        <EditorFileMenu
          anchor={menu.anchor}
          path={menu.path}
          items={menuItems}
          onClose={closeMenu}
        />
      )}
    </section>
  )
}
