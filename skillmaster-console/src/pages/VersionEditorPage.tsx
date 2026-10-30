import { useEffect, useRef, useState } from 'react'
import {
  ancestorsOf,
  buildFileTree,
  dirtyPathsOf,
  fileExists as treeFileExists,
  folderExists as treeFolderExists,
  hasFilesUnder as treeHasFilesUnder,
  validatePath,
  type FileNode,
} from '../editor/files'
import { go, replaceAddress } from '../address'
import { collisionIssue } from '../nameRules'
import EditorFileTree from '../components/version/EditorFileTree'
import EditorHeader from '../components/version/EditorHeader'
import EditorNotices from '../components/version/EditorNotices'
import EditorPane from '../components/version/EditorPane'
import type { CreateState, MenuState, RenameState, TreeRow } from '../editor/treeState'
import type {
  ChangeEvent,
  KeyboardEvent as ReactKeyboardEvent,
  MouseEvent as ReactMouseEvent,
  MutableRefObject,
} from 'react'
import { codeMessage } from '../api/errors'
import {
  createDraft,
  editDraft,
  getBlankSkill,
  getSkill,
  getSkillFile,
  updateDraftName,
  updateDraftNote,
} from '../api/skills'
import type {
  ApiResult,
  AuthoredFile,
  AuthoredSkill,
  AuthoredVersion,
  DraftEditBody,
} from '../api/types'
import ConfirmDialog from '../components/ConfirmDialog'
import { canEditDraft, canWrite } from '../versionActions'
import { useSession } from '../store'
import { versionSuffix } from '../version'

/**
 * 新建版本 / 编辑草稿 (v8).
 *
 * The page holds a base in memory — a live version, the newest draft, or the skill row's own minimal
 * `SKILL.md` — and creates the draft on the **first content change**, never when the page opens
 * (v8 TD §架构: copying a live version verbatim would carry its digest, and `UNIQUE (skill_id,
 * digest)` refuses two rows with one content).
 *
 * Everything visible here is the design's own markup (design/skillmaster-控制台演示.html
 * `#page-editor`, 2026-10-20/21), copied class by class. What changed on the way into the framework:
 * the demo's `?page=`/`state=` fiction became real addresses and requests, its fixtures became the
 * server's answers, and the local-only bookkeeping (empty folders, unsaved buffers) stayed local.
 *
 * **What changed in the port.** The mutable model (buffers, saved, the opened folders, the draft's
 * labels, the open menu) is read after `await`s and by the document listeners added once at mount,
 * and a React closure keeps the values it was rendered with rather than Vue's live refs — so those
 * values are mirrored into a ref on every write (`useLive`). `nextTick` became
 * `requestAnimationFrame`, which is what the two frames in `toggleMenu`, the focus calls and the
 * banner measurement are waiting for. `skill` was written in the Vue page and read by nothing, so it
 * is not carried over; `scrollTop` was the same, and stays a ref rather than a state that would
 * re-render on every scroll.
 */
export interface VersionEditorPageProps {
  namespace: string
  name: string
  mode: 'new' | 'edit'
  version?: string
  file?: string
}

const MAX_NAME = 40
const MAX_NOTE = 200

interface LeaveTarget {
  href: string
  opener: HTMLElement | null
}

/**
 * A `useState` that is also readable synchronously.
 *
 * Vue's refs were live: a function reading `buffers[path]` after an `await` saw whatever had been
 * typed while the request was in flight, and a listener added once at mount read the current
 * render's values. A React closure keeps the values it was rendered with, so every value that
 * outlives an `await` or a listener is written to a ref at the same time as to the state — the
 * render still reads the `useState` value.
 */
function useLive<T>(initial: T): [T, (next: T) => void, MutableRefObject<T>] {
  const [value, setValue] = useState(initial)
  const ref = useRef(initial)
  function set(next: T): void {
    ref.current = next
    setValue(next)
  }
  return [value, set, ref]
}

/** One frame, for what measures or focuses after the DOM has caught up (Vue's `nextTick`). */
function nextFrame(): Promise<void> {
  return new Promise((resolve) => {
    requestAnimationFrame(() => resolve())
  })
}

export default function VersionEditorPage({
  namespace,
  name,
  version: pathVersion,
  mode,
  file: requestedFile,
}: VersionEditorPageProps) {
  const { account } = useSession()

  const [loading, setLoading] = useState(true)
  const [missing, setMissing] = useState(false)
  const [currentVersion, setCurrentVersion, currentVersionRef] =
    useLive<AuthoredVersion | null>(null)
  const [isNew, setIsNew, isNewRef] = useLive(mode === 'new')
  const [, setBaseDigest, baseDigestRef] = useLive<string | null>(null)
  const [, setBaseFrom, baseFromRef] = useLive<string | undefined>(undefined)
  const [baseLabel, setBaseLabel] = useState('')
  const [baseCount, setBaseCount] = useState(1)

  const [files, setFiles, filesRef] = useLive<AuthoredFile[]>([])
  /** Folders with nothing in them live only on this page — the manifest has files only. */
  const [localFolders, setLocalFolders, localFoldersRef] = useLive<string[]>([])
  const [buffers, setBuffers, buffersRef] = useLive<Record<string, string>>({})
  const [saved, setSaved, savedRef] = useLive<Record<string, string>>({})
  const [current, setCurrent, currentRef] = useLive('SKILL.md')
  const [opened, setOpened, openedRef] = useLive<Record<string, boolean>>({})
  const [query, setQuery] = useState('')
  const [create, setCreate] = useState<CreateState | null>(null)
  const [rename, setRename] = useState<RenameState | null>(null)
  const [menu, setMenu, menuRef] = useLive<MenuState | null>(null)
  /** Narrow windows start with the tree folded away; the tree itself draws and reports it. */
  const [treeCollapsed, setTreeCollapsed] = useState(false)
  const [deleteTarget, setDeleteTarget, deleteTargetRef] = useLive<string | null>(null)
  const [deleteFolderTarget, setDeleteFolderTarget, deleteFolderTargetRef] =
    useLive<string | null>(null)
  const [leaveTarget, setLeaveTarget, leaveTargetRef] = useLive<LeaveTarget | null>(null)

  const [draftName, setDraftName, draftNameRef] = useLive('')
  const [draftNote, setDraftNote, draftNoteRef] = useLive('')
  const [, setSavedName, savedNameRef] = useLive('')
  const [, setSavedNote, savedNoteRef] = useLive('')
  const [labelStatus, setLabelStatus] = useState<{ text: string; danger: boolean } | null>(null)

  const [saving, setSaving, savingRef] = useLive(false)
  const [conflict, setConflict] = useState(false)
  const [duplicate, setDuplicate] = useState<{ title: string; label: string; href: string } | null>(
    null,
  )
  const [errorBanner, setErrorBanner] = useState<string | null>(null)
  const [errorTitle, setErrorTitle] = useState('保存失败。')
  const [pendingEdit, setPendingEdit] = useState<DraftEditBody | null>(null)
  const scrollTop = useRef(0)
  const [caret, setCaret] = useState({ line: 1, column: 1 })
  const textarea = useRef<HTMLTextAreaElement | null>(null)
  const gutter = useRef<HTMLDivElement | null>(null)
  const conflictBanner = useRef<HTMLDivElement | null>(null)
  const duplicateBanner = useRef<HTMLDivElement | null>(null)
  const errorElement = useRef<HTMLDivElement | null>(null)
  const workspace = useRef<HTMLDivElement | null>(null)

  const readSuffix = currentVersion === null ? undefined : versionSuffix(currentVersion)
  /**
   * Where the page's own links go.
   *
   * **A version is a tab pin, not a path suffix** (the product's route grammar): `/skills/ns/name`
   * opens 信息, and `?tab=versions&version=<pin>` is the version page. The editor's `@<pin>/edit` is
   * the one address that carries a suffix, because it is a page of its own.
   */
  const skillHref = `/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`
  const versionsHref = `${skillHref}?tab=versions`
  function versionHref(pin: string): string {
    return `${skillHref}?tab=versions&version=${encodeURIComponent(pin)}`
  }
  const viewHref = readSuffix === undefined ? versionsHref : versionHref(readSuffix)
  const currentFile = files.find((file) => file.relpath === current) ?? null
  const currentText = buffers[current] ?? ''
  const currentBinary = currentFile?.is_binary === true
  const gutterText = Array.from(
    { length: currentText.split('\n').length },
    (_, index) => String(index + 1),
  ).join('\n')
  const dirtyPaths = dirtyPathsOf(buffers, saved)
  const currentDirty = buffers[current] !== saved[current]
  const canSave =
    !saving &&
    !currentBinary &&
    currentDirty &&
    (!isNew || draftName.trim() !== '')
  /** The keyboard listener is added once; it has to see what this render computed. */
  const canSaveRef = useRef(canSave)
  canSaveRef.current = canSave
  const facts = (() => {
    if (!isNew) {
      const code = readSuffix ?? ''
      const submitter = currentVersion?.submitted_by ?? ''
      return `草稿　·　${code}　·　提交人 ${submitter}　·　${files.length} 个文件`
    }
    return `${baseLabel}　·　${baseCount} 个文件`
  })()

  const rows = (() => {
    const needle = query.trim().toLowerCase()
    const searching = needle !== ''
    const out: TreeRow[] = []
    const walk = (nodes: FileNode[], depth: number): void => {
      for (const node of nodes) {
        const expanded = searching ? true : opened[node.path] === true
        out.push({
          path: node.path,
          name: node.name,
          depth,
          dir: node.dir,
          expanded,
          empty: node.dir && !hasFilesUnder(node.path),
        })
        if (node.dir && expanded) walk(node.children, depth + 1)
      }
    }
    // **A search matches files, never folders** (design: `files.filter(…); buildTree(matched, {})`).
    // Building the tree from the matches also means an empty folder cannot appear while searching,
    // and every ancestor that does appear is one a matched file lives in.
    walk(
      buildTree(!searching, (relpath) => !searching || relpath.toLowerCase().includes(needle)),
      0,
    )
    return out
  })()

  function hasFilesUnder(path: string): boolean {
    return treeHasFilesUnder(files, path)
  }

  function folderExists(path: string): boolean {
    return treeFolderExists(files, localFolders, path)
  }

  function fileExists(path: string): boolean {
    return treeFileExists(filesRef.current, path)
  }

  /** The recursive tree, built from the flat relpaths (see `src/editor/files.ts`). */
  function buildTree(
    includeLocalFolders = true,
    includeFile: (relpath: string) => boolean = () => true,
  ): FileNode[] {
    return buildFileTree(files, localFolders, includeLocalFolders, includeFile)
  }

  function ancestors(path: string): string[] {
    return ancestorsOf(path)
  }

  function expandAncestors(path: string): void {
    const next = { ...openedRef.current }
    for (const folder of ancestors(path)) next[folder] = true
    setOpened(next)
  }

  function toggleFolder(path: string): void {
    setOpened({ ...openedRef.current, [path]: !openedRef.current[path] })
  }

  function openFile(path: string): void {
    if (!fileExists(path)) return
    setCurrent(path)
    expandAncestors(path)
    closeMenu()
    setCaret({ line: 1, column: 1 })
    void ensureText(path).then(() => {
      // The read may finish after the reader has moved on; a stale reset would move their caret.
      if (currentRef.current !== path || currentBinaryNow()) return
      void nextFrame().then(() => {
        if (currentRef.current !== path) return
        const element = textarea.current
        if (element === null) return
        element.setSelectionRange(0, 0)
        onCaret()
      })
    })
  }

  /** The suffix the address would name right now — read after an `await`, so not off this render. */
  function readSuffixNow(): string | undefined {
    const version = currentVersionRef.current
    return version === null ? undefined : versionSuffix(version)
  }

  function currentBinaryNow(): boolean {
    const file = filesRef.current.find((candidate) => candidate.relpath === currentRef.current)
    return file?.is_binary === true
  }

  async function ensureText(path: string): Promise<void> {
    if (buffersRef.current[path] !== undefined) return
    const file = filesRef.current.find((candidate) => candidate.relpath === path)
    if (file === undefined || file.is_binary) return
    const result = await getSkillFile(namespace, name, readSuffixNow(), path)
    if (!result.ok) {
      // A read that failed is not a save that failed; the banner says which.
      showFailure(result.code, result.message, '没能打开这个文件。')
      return
    }
    // The textarea is editable while the read is in flight. Text typed into it is the reader's:
    // keep it as the buffer (which marks the file dirty) and record the server's copy as saved.
    writeSaved(path, result.data)
    if (buffersRef.current[path] === undefined) writeBuffer(path, result.data)
  }

  function writeBuffer(path: string, text: string): void {
    setBuffers({ ...buffersRef.current, [path]: text })
  }

  function removeBuffer(path: string): void {
    const next = { ...buffersRef.current }
    delete next[path]
    setBuffers(next)
  }

  function writeSaved(path: string, text: string): void {
    setSaved({ ...savedRef.current, [path]: text })
  }

  function removeSaved(path: string): void {
    const next = { ...savedRef.current }
    delete next[path]
    setSaved(next)
  }

  function addLocalFolder(path: string): void {
    if (localFoldersRef.current.includes(path)) return
    setLocalFolders([...localFoldersRef.current, path])
  }

  /* ------------------------------------------------------------------ labels */

  function clampPoints(value: string, max: number): string {
    return Array.from(value).slice(0, max).join('')
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

  /* ------------------------------------------------------------------ writes */

  async function save(): Promise<void> {
    if (!canSaveRef.current) return
    const path = currentRef.current
    await send({ op: 'write', path, text: buffersRef.current[path] ?? '' })
  }

  async function send(edit: DraftEditBody): Promise<boolean> {
    // A second Enter while the first request is in flight would create twice or save against a
    // baseline the first answer is about to replace. One request at a time.
    if (savingRef.current) return false
    if (!requireNameForDraft()) return false
    setSaving(true)
    setErrorBanner(null)
    const from = baseFromRef.current
    const digest = baseDigestRef.current
    const result = isNewRef.current
      ? await createDraft(namespace, name, {
          name: draftNameRef.current.trim(),
          note: draftNoteRef.current.trim(),
          ...(from === undefined ? {} : { from }),
          ...(digest === null ? {} : { from_digest: digest }),
          edit,
        })
      : await editDraft(namespace, name, readSuffixNow() as string, {
          base_digest: digest ?? '',
          ...edit,
        })
    setSaving(false)
    if (!result.ok) {
      handleFailure(result, edit)
      return false
    }
    adopt(result.data.version, result.data.files, edit)
    return true
  }

  function adopt(version: AuthoredVersion, nextFiles: AuthoredFile[], edit: DraftEditBody): void {
    const wasNew = isNewRef.current
    setCurrentVersion(version)
    setIsNew(false)
    setBaseDigest(version.digest)
    setBaseFrom(versionSuffix(version))
    // **Saved means what the server holds**, not what the fields hold: a body save carries no
    // labels, so a name typed but not yet blurred must stay dirty and be PATCHed by its blur.
    setSavedName(version.draft_name ?? '')
    setSavedNote(version.note ?? '')
    setFiles(nextFiles)
    if (edit.op === 'write' && edit.path !== undefined) {
      // What the server has is `sent`; what is in the field may have grown while the request was in
      // flight. Recording `sent` as saved keeps that typing dirty instead of reverting it — and the
      // buffer itself is left exactly as the field has it.
      writeSaved(edit.path, edit.text ?? '')
    } else if (edit.op === 'delete' && edit.path !== undefined) {
      removeBuffer(edit.path)
      removeSaved(edit.path)
      if (currentRef.current === edit.path) selectFirstFile()
    } else if (edit.op === 'rename' && edit.from !== undefined && edit.to !== undefined) {
      const from = edit.from
      const to = edit.to
      if (buffersRef.current[from] !== undefined) {
        writeBuffer(to, buffersRef.current[from] as string)
        removeBuffer(from)
      }
      if (savedRef.current[from] !== undefined) {
        writeSaved(to, savedRef.current[from] as string)
        removeSaved(from)
      }
      if (currentRef.current === from) setCurrent(to)
    }
    setConflict(false)
    setPendingEdit(null)
    setDuplicate(null)
    setErrorBanner(null)
    writeDraftAddress()
    if (wasNew) {
      // Until the row existed, both label writes returned early (`isNew`), so a name or note typed
      // while the create request was in flight was never sent. Send it now that there is a row.
      if (draftNameRef.current.trim() !== savedNameRef.current) void commitName()
      if (draftNoteRef.current.trim() !== savedNoteRef.current) void commitNote()
    }
  }

  function handleFailure(
    result: Extract<ApiResult<unknown>, { ok: false }>,
    edit: DraftEditBody,
  ): void {
    if (result.code === 'digest_mismatch') {
      setPendingEdit(edit)
      setConflict(true)
      return
    }
    if (edit.op === 'delete' && result.code === 'file_not_found') {
      // The file this page was about to delete is already gone — the other writer removed it. The
      // delete is done; this tree is what is stale. Re-read in place: a location reload would also
      // throw away unsaved text in every other file (v8 test plan E10).
      void refreshDraftInPlace()
      return
    }
    if (result.code === 'content_already_exists') {
      const detail = (field: string) =>
        result.details.find((item) => item.field === field)?.issue
      const state = detail('state')
      const code = detail('code')
      const pin = detail('pin')
      if (state === 'published' && pin !== undefined && pin !== '') {
        setDuplicate({
          title: `该内容已属于 @${pin}。`,
          label: `查看 @${pin}`,
          href: versionHref(pin),
        })
      } else if (state === 'draft' && code !== undefined && code !== '') {
        // The design draws this banner for a published version and for a draft. A discarded row also
        // occupies the digest, but the design has no word for it and its page may not be readable —
        // it gets the neutral sentence and the version list rather than being called a draft.
        setDuplicate({
          title: `该内容已属于草稿 ${code}。`,
          label: `查看 ${code}`,
          href: versionHref(code),
        })
      } else {
        setDuplicate({
          title: '该内容已属于另一版。',
          label: '查看版本列表',
          href: versionsHref,
        })
      }
      return
    }
    showFailure(result.code, result.message)
  }

  function showFailure(code: string, message: string, title = '保存失败。'): void {
    setErrorTitle(title)
    // 「修改后可再次保存。」 is advice about a save; a failed read has nothing to save again.
    const advice = title === '保存失败。' ? '修改后可再次保存。' : ''
    setErrorBanner(`${codeMessage(code, message)}${advice}`)
  }

  /** The draft's address is born with the row: `…@<version code>/edit`, then the file it was on. */
  function writeDraftAddress(): void {
    const version = currentVersionRef.current
    if (version === null) return
    const url = `${skillHref}@${encodeURIComponent(versionSuffix(version))}/edit?file=${encodeURIComponent(currentRef.current)}`
    replaceAddress(url)
  }

  function selectFirstFile(): void {
    const first =
      filesRef.current.find((file) => file.relpath === 'SKILL.md') ?? filesRef.current[0]
    if (first !== undefined) {
      setCurrent(first.relpath)
      void ensureText(first.relpath)
    }
  }

  /* ------------------------------------------------------------------ conflict */

  /** Set when this page itself is deliberately leaving, so the native prompt does not ask again. */
  const leavingPage = useRef(false)

  /** 重新加载 discards this page's unsaved work — the address already names what to read. */
  function reload(): void {
    leavingPage.current = true
    window.location.reload()
  }

  /**
   * The same re-read without leaving the page: the manifest and the baseline move, files this page
   * no longer has are dropped, and every other buffer — including unsaved text — stays.
   */
  async function refreshDraftInPlace(): Promise<void> {
    const result = await getSkill(namespace, name, readSuffixNow())
    if (!result.ok) {
      // A failed re-read must not become a reload: reload() sets `leavingPage` and throws away every
      // buffer, which is exactly what this path exists to avoid. Leave the page where it is — the
      // stale baseline meets the next save as an ordinary conflict banner, which offers 重新加载.
      showFailure(result.code, result.message, '没能重新读取这一版。')
      return
    }
    if (result.data.version === null) {
      // Nothing left to edit. Reloading is only safe when there is nothing unsaved to lose.
      if (dirtyPathsOf(buffersRef.current, savedRef.current).length === 0) {
        reload()
        return
      }
      showFailure('', '这一版现在读不到了。', '没能重新读取这一版。')
      return
    }
    setCurrentVersion(result.data.version)
    setBaseDigest(result.data.version.digest)
    setFiles(result.data.files)
    if (isNewRef.current) setBaseCount(result.data.files.length)
    for (const path of Object.keys(buffersRef.current)) {
      if (!result.data.files.some((file) => file.relpath === path)) {
        removeBuffer(path)
        removeSaved(path)
      }
    }
    // The baseline moved, so every clean buffer that is still here is one write behind — the same
    // moment `refreshStaleBuffers` exists for after an overwrite. Unsaved text is left alone.
    await refreshStaleBuffers(undefined)
    if (!result.data.files.some((file) => file.relpath === currentRef.current)) selectFirstFile()
  }

  /**
   * A successful overwrite is the one moment another writer's bytes really are in this page's base.
   * A file that was opened but not edited is now stale, and saving it later would use the new digest
   * and quietly revert the other writer. Re-read those; a file with local unsaved text is left alone —
   * it is the user's own content, and dropping it is worse than a visible conflict.
   */
  async function refreshStaleBuffers(except: string | undefined): Promise<void> {
    const suffix = readSuffixNow()
    for (const path of Object.keys(buffersRef.current)) {
      if (path === except) continue
      if (buffersRef.current[path] !== savedRef.current[path]) continue
      const file = filesRef.current.find((candidate) => candidate.relpath === path)
      if (file === undefined || file.is_binary) continue
      const baseline = savedRef.current[path]
      const result = await getSkillFile(namespace, name, suffix, path)
      // Checked again after the await: text typed, or a save completed, while the re-read was in
      // flight is not what this copy describes, and must not be replaced by it.
      if (buffersRef.current[path] !== baseline || savedRef.current[path] !== baseline) continue
      if (!filesRef.current.some((candidate) => candidate.relpath === path)) {
        // Removed while the read was in flight; there is nothing here to keep.
        removeBuffer(path)
        removeSaved(path)
        continue
      }
      if (!result.ok) {
        // A clean stale buffer that cannot be refreshed is dropped, so opening it reads the server
        // rather than keeping bytes that would revert the other writer on the next save.
        removeBuffer(path)
        removeSaved(path)
        showFailure(result.code, result.message, '没能打开这个文件。')
        if (path === currentRef.current) void ensureText(path)
        continue
      }
      writeBuffer(path, result.data)
      writeSaved(path, result.data)
    }
  }

  /**
   * 覆盖: send the same edit again against the digest the server reports now.
   *
   * On a new version the draft row has not been created yet, so the baseline is re-read and the create
   * is retried; on an existing draft only the baseline moves, and the server applies the edit to the
   * row as the other writer left it.
   */
  async function overwrite(): Promise<void> {
    const edit = pendingEdit
    if (edit === null) {
      reload()
      return
    }
    setConflict(false)
    const from = baseFromRef.current
    if (from !== undefined) {
      const latest = await getSkill(namespace, name, from)
      if (latest.ok && latest.data.version !== null) {
        setBaseDigest(latest.data.version.digest)
      }
    }
    setPendingEdit(null)
    // 覆盖重写**当前**缓冲区，而不是失败那一刻的文本：冲突之后继续输入的文字必须算在这次写入里
    // (PRD #10: 「覆盖」用本地内容按最新基线再写一次).
    const replayed: DraftEditBody =
      edit.op === 'write' && edit.path !== undefined
        ? { op: 'write', path: edit.path, text: buffersRef.current[edit.path] ?? edit.text ?? '' }
        : edit
    const sent = await send(replayed)
    if (sent) {
      await refreshStaleBuffers(replayed.op === 'write' ? replayed.path : undefined)
    }
  }

  /* ------------------------------------------------------------------ tree actions */

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
    setQuery('')
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

  /** Every path the version already answers to — files, their folders, and the empty ones. */
  function knownPaths(): string[] {
    const paths: string[] = [...localFolders]
    for (const file of files) {
      paths.push(...ancestorsOf(file.relpath), file.relpath)
    }
    return paths
  }

  function createPathError(path: string): string | null {
    const issue = validatePath(path)
    if (issue !== null) return issue
    if (create?.kind === 'file') {
      if (fileExists(path)) return '该路径已存在文件。'
      if (folderExists(path)) return '该路径已是文件夹。'
      if (files.some((file) => path.startsWith(`${file.relpath}/`))) {
        return '该路径已是文件。'
      }
    } else {
      if (fileExists(path)) return '该路径已是文件。'
      if (files.some((file) => path.startsWith(`${file.relpath}/`))) {
        return '该路径已是文件。'
      }
      if (folderExists(path)) return '该文件夹已存在。'
    }
    return collisionIssue(path, knownPaths())
  }

  async function createItem(): Promise<void> {
    const creating = create
    if (creating === null) return
    // NFC first: the same name typed with combining marks is the same name on macOS, and both
    // sides of every comparison below have to say so before anything is created (v33).
    const path = creating.value.trim().normalize('NFC')
    const issue = createPathError(path)
    if (issue !== null) {
      setCreate({ ...creating, error: issue })
      return
    }
    if (creating.kind === 'dir') {
      addLocalFolder(path)
      expandAncestors(path)
      setCreate(null)
      void nextFrame().then(() => {
        document
          .querySelector<HTMLElement>(`[data-path="${cssEscape(path)}"] .editor-file-open`)
          ?.focus()
      })
      return
    }
    // The draft row is born with the first content change — a file, even an empty one, is one.
    if (!requireNameForDraft()) return
    const ok = await send({ op: 'write', path, text: '' })
    if (!ok) return
    setCreate(null)
    openFile(path)
    void nextFrame().then(() => textarea.current?.focus())
  }

  function beginRename(path: string): void {
    closeMenu()
    expandAncestors(path)
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
    const issue =
      validatePath(to) ??
      (fileExists(to)
        ? '该路径已存在文件。'
        : folderExists(to)
          ? '该路径已是文件夹。'
          : files.some((file) => to.startsWith(`${file.relpath}/`))
            ? '该路径已是文件。'
            // A rename does not collide with the row it is moving away from.
            : collisionIssue(to, knownPaths(), from))
    if (issue !== null) {
      setRename({ ...renaming, error: issue })
      return
    }
    if (!requireNameForDraft()) return
    const ok = await send({ op: 'rename', from, to })
    if (ok) {
      // The file is now somewhere else; a collapsed destination would hide the row the editor just
      // switched to.
      expandAncestors(to)
      setRename(null)
    }
  }

  /** The button the sheet was opened from — where 取消 puts the focus back (design: returnFocus). */
  const deleteOpener = useRef<HTMLElement | null>(null)
  const deleteFolderOpener = useRef<HTMLElement | null>(null)

  function askDelete(path: string): void {
    const opener = menuButton(path)
    closeMenu()
    if (!requireNameForDraft()) return
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
    await send({ op: 'delete', path })
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
    setLocalFolders(
      localFoldersRef.current.filter((key) => !(key === path || key.startsWith(`${path}/`))),
    )
  }

  /* ------------------------------------------------------------------ leaving */

  function guardLeave(
    event: ReactMouseEvent<HTMLAnchorElement>,
    href: string,
    opener: EventTarget | null,
  ): void {
    if (dirtyPaths.length === 0) return
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
    // One question, not two: an action the page itself confirmed (重新加载 / 放弃并离开) has already
    // asked, and the native prompt on top of it can be cancelled, which would abort that action.
    if (leavingPage.current || dirtyPathsOf(buffersRef.current, savedRef.current).length === 0) {
      return
    }
    event.preventDefault()
    event.returnValue = ''
  }

  /* ------------------------------------------------------------------ keyboard */

  function onKeydown(event: KeyboardEvent): void {
    const dialogOpen =
      deleteTargetRef.current !== null ||
      deleteFolderTargetRef.current !== null ||
      leaveTargetRef.current !== null
    if (dialogOpen) return
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
      event.preventDefault()
      if (canSaveRef.current) void save()
      return
    }
    if (event.key !== 'Escape') return
    // The menu's dismissal is the document-level one (it is a fixed popup, not a row); rename and
    // create are inputs and own their own Esc, so pressing Esc elsewhere does not tear them down.
    if (menuRef.current !== null) {
      event.preventDefault()
      closeMenu(true)
    }
  }

  function onDocumentMouseDown(event: MouseEvent): void {
    if (menuRef.current === null) return
    const target = event.target as Element | null
    if (target === null) return
    if (target.closest('.editor-file-pop') !== null || target.closest('[data-menu]') !== null) {
      return
    }
    closeMenu()
  }

  function onTextKeydown(event: ReactKeyboardEvent<HTMLTextAreaElement>): void {
    if (event.key !== 'Tab') return
    // Tab is indentation here, not a way out; two spaces, matching the design's own editor.
    event.preventDefault()
    const element = textarea.current
    if (element === null) return
    element.setRangeText('  ', element.selectionStart, element.selectionEnd, 'end')
    writeBuffer(currentRef.current, element.value)
    onCaret()
  }

  function onTextInput(event: ChangeEvent<HTMLTextAreaElement>): void {
    writeBuffer(currentRef.current, event.target.value)
    onCaret()
  }

  function onTextScroll(): void {
    scrollTop.current = textarea.current?.scrollTop ?? 0
    if (gutter.current !== null) gutter.current.scrollTop = scrollTop.current
  }

  function onCaret(): void {
    const element = textarea.current
    if (element === null) return
    const before = element.value.slice(0, element.selectionStart)
    const lines = before.split('\n')
    setCaret({
      line: lines.length,
      column: (lines[lines.length - 1] ?? '').length + 1,
    })
  }

  function onSearchKeydown(event: ReactKeyboardEvent<HTMLInputElement>): void {
    if (event.key !== 'Escape') return
    event.preventDefault()
    setQuery('')
  }

  function clearSearch(): void {
    setQuery('')
    void nextFrame().then(() => document.getElementById('edFileQuery')?.focus())
  }

  /* ------------------------------------------------------------------ values */

  function sizeLabel(text: string): string {
    return byteLabel(new TextEncoder().encode(text).length)
  }

  function byteLabel(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`
    return `${(bytes / 1024).toFixed(1)} KB`
  }

  function info(): string {
    if (currentBinary) {
      return currentFile === null ? '' : byteLabel(currentFile.size)
    }
    return `${sizeLabel(currentText)} · UTF-8`
  }

  /**
   * The **server's** file address, not the page's.
   *
   * `下载文件` has to return the bytes, and `/skills/.../files/...` is the SPA's file tab — for a
   * binary it renders a sentence telling the reader to use the CLI. The raw endpoint lives on the
   * session plane, one segment per path.
   */
  function fileHref(path: string): string {
    const suffix = readSuffix === undefined ? '' : `@${encodeURIComponent(readSuffix)}`
    return `/web/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}${suffix}/files/${path.split('/').map(encodeURIComponent).join('/')}`
  }

  /* ------------------------------------------------------------------ load */

  async function load(): Promise<void> {
    setLoading(true)
    setMissing(false)
    const result =
      mode === 'new'
        ? await getSkill(namespace, name)
        : await getSkill(namespace, name, pathVersion)
    if (!result.ok || !canWrite(result.data.rung)) {
      setLoading(false)
      setMissing(true)
      return
    }
    const version = result.data.version
    let nextFiles: AuthoredFile[]
    if (mode === 'edit') {
      if (
        version === null ||
        !canEditDraft(version, result.data.rung, account?.username ?? null)
      ) {
        setLoading(false)
        setMissing(true)
        return
      }
      adoptLoaded(result.data, version, false)
      nextFiles = result.data.files
    } else if (version !== null && (version.state === 'published' || version.state === 'draft')) {
      // Live first, else the newest draft this caller may see — the read's own rule (v8 TD §读).
      adoptLoaded(result.data, version, true)
      nextFiles = result.data.files
    } else {
      // Nothing readable is a base: the skill row with no version selected is the empty base.
      const blank = await getBlankSkill(namespace, name)
      if (!blank.ok) {
        setLoading(false)
        setMissing(true)
        return
      }
      setCurrentVersion(null)
      setIsNew(true)
      setBaseDigest(null)
      setBaseFrom(undefined)
      setBaseLabel('空白底稿')
      setBaseCount(1)
      const text = blankTemplate(blank.data)
      nextFiles = [
        {
          relpath: 'SKILL.md',
          sha256: '',
          size: new TextEncoder().encode(text).length,
          is_binary: false,
        },
      ]
      setFiles(nextFiles)
      setBuffers({ 'SKILL.md': text })
      setSaved({ 'SKILL.md': text })
      setCurrent('SKILL.md')
    }

    const requested = requestedFile ?? ''
    const open =
      requested !== '' && nextFiles.some((file) => file.relpath === requested)
        ? requested
        : nextFiles.some((file) => file.relpath === 'SKILL.md')
          ? 'SKILL.md'
          : (nextFiles[0]?.relpath ?? 'SKILL.md')
    setCurrent(open)
    await ensureText(open)
    expandAncestors(open)
    setLoading(false)
  }

  function adoptLoaded(data: AuthoredSkill, version: AuthoredVersion, newVersion: boolean): void {
    setCurrentVersion(version)
    setIsNew(newVersion)
    setBaseDigest(version.digest)
    setBaseFrom(versionSuffix(version))
    setBaseCount(data.files.length)
    if (newVersion) {
      setBaseLabel(
        version.state === 'draft'
          ? `基于草稿 ${versionSuffix(version)}　·　提交人 ${version.submitted_by ?? ''}`
          : `基于线上版本 @${version.name ?? versionSuffix(version)}`,
      )
    }
    setFiles(data.files)
    const nextName = newVersion ? '' : (version.draft_name ?? '')
    const nextNote = newVersion ? '' : (version.note ?? '')
    setDraftName(nextName)
    setDraftNote(nextNote)
    setSavedName(nextName)
    setSavedNote(nextNote)
  }

  /** The smallest valid body: the template v8 TD §数据模型 defines for an empty base. */
  function blankTemplate(data: AuthoredSkill): string {
    const description = (data.description ?? '').replace(/\\/g, '\\\\').replace(/"/g, '\\"')
    const title = data.title.trim() === '' ? data.name : data.title
    return `---\nname: ${data.name}\ndescription: "${description}"\n---\n\n# ${title}\n`
  }

  /* ------------------------------------------------------------------ effects */

  /**
   * 横幅出现时，工作区让出它的高度. The workspace's height is computed from the viewport, and the
   * banners sit between it and the head; measuring the open one rather than writing a fixed number is
   * what keeps the page from growing a scrollbar when a sentence wraps on a narrow screen.
   */
  function syncBannerHeight(): void {
    if (workspace.current === null) return
    const open = [conflictBanner.current, duplicateBanner.current, errorElement.current].find(
      (banner) => banner !== null && banner.offsetParent !== null,
    )
    const height =
      open === undefined || open === null
        ? 0
        : open.getBoundingClientRect().height +
          (Number.parseFloat(getComputedStyle(open).marginTop) || 0)
    workspace.current.style.setProperty('--editor-banner', `${height}px`)
  }

  // Vue's `watch` does not fire on mount; React's effect does.
  const bannersSettled = useRef(false)
  useEffect(() => {
    if (!bannersSettled.current) {
      bannersSettled.current = true
      return
    }
    const frame = requestAnimationFrame(syncBannerHeight)
    return () => {
      cancelAnimationFrame(frame)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conflict, duplicate, errorBanner, labelStatus])

  useEffect(() => {
    document.addEventListener('keydown', onKeydown)
    document.addEventListener('mousedown', onDocumentMouseDown, true)
    window.addEventListener('beforeunload', onBeforeUnload)
    window.addEventListener('resize', syncBannerHeight)
    if (window.innerWidth <= 900) setTreeCollapsed(true)
    void load().then(() => syncBannerHeight())
    return () => {
      document.removeEventListener('keydown', onKeydown)
      document.removeEventListener('mousedown', onDocumentMouseDown, true)
      window.removeEventListener('beforeunload', onBeforeUnload)
      window.removeEventListener('resize', syncBannerHeight)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <section className="page" id="page-editor">
      <nav className="crumbs" aria-label="面包屑">
        <a
          href={versionsHref}
          id="edBackCrumb"
          onClick={(event) => guardLeave(event, versionsHref, event.currentTarget)}
        >
          版本
        </a>
        <span className="crumbs-sep">/</span>
        <span className="crumbs-here">{isNew ? '新版本' : '编辑'}</span>
      </nav>

      <EditorHeader
        hidden={loading || missing}
        isNew={isNew}
        draftName={draftName}
        draftNote={draftNote}
        onNameInput={onNameInput}
        onNoteInput={onNoteInput}
        commitName={commitName}
        commitNote={commitNote}
        onNameEnter={onNameEnter}
        onNoteEnter={onNoteEnter}
        facts={facts}
        labelStatus={labelStatus}
        canSave={canSave}
        saving={saving}
        save={save}
        viewHref={viewHref}
        guardLeave={guardLeave}
      />

      <EditorNotices
        hidden={loading || missing}
        conflict={conflict}
        duplicate={duplicate}
        errorBanner={errorBanner}
        errorTitle={errorTitle}
        conflictBanner={conflictBanner}
        duplicateBanner={duplicateBanner}
        errorElement={errorElement}
        reload={reload}
        overwrite={overwrite}
        dismissError={() => setErrorBanner(null)}
        guardLeave={guardLeave}
      />


      <div ref={workspace} className="editor-workspace" id="edWorkspace" style={{ display: (loading || missing) ? 'none' : undefined }}>
        <EditorFileTree
          rows={rows}
          fileCount={files.length}
          treeCollapsed={treeCollapsed}
          setTreeCollapsed={setTreeCollapsed}
          current={current}
          query={query}
          setQuery={setQuery}
          onSearchKeydown={onSearchKeydown}
          clearSearch={clearSearch}
          create={create}
          setCreate={setCreate}
          createItem={createItem}
          cancelCreate={cancelCreate}
          startCreate={startCreate}
          rename={rename}
          setRename={setRename}
          commitRename={commitRename}
          cancelRename={cancelRename}
          beginRename={beginRename}
          menu={menu}
          toggleMenu={toggleMenu}
          askDelete={askDelete}
          askDeleteFolder={askDeleteFolder}
          buffers={buffers}
          saved={saved}
          openFile={openFile}
          toggleFolder={toggleFolder}
        />

        <EditorPane
          current={current}
          currentDirty={currentDirty}
          currentBinary={currentBinary}
          currentText={currentText}
          gutterText={gutterText}
          infoText={info()}
          caret={caret}
          fileHref={fileHref(current)}
          gutter={gutter}
          textarea={textarea}
          onTextInput={onTextInput}
          onTextScroll={onTextScroll}
          onTextKeydown={onTextKeydown}
          onCaret={onCaret}
        />
      </div>

      <div className="editor-loading" id="edLoading" style={{ display: (!loading) ? 'none' : undefined }}>
        加载中…
      </div>
      <div className="editor-missing" id="edMissing" style={{ display: (!missing) ? 'none' : undefined }}>
        <div className="notfound">
          <h2>没有这一版</h2>
          <p className="hint">它可能已经被丢弃，或者不属于你能编辑的版本。</p>
          <p>
            <a href={versionsHref}>回到版本列表</a>
          </p>
        </div>
      </div>

      <ConfirmDialog
        open={deleteTarget !== null}
        title="删除文件？"
        body={`${deleteTarget ?? ''} 将从版本中移除。${deleteTarget !== null && buffers[deleteTarget] !== saved[deleteTarget] ? ' 该文件未保存的改动也会丢失。' : ''}`}
        confirmLabel="删除文件"
        onConfirm={confirmDelete}
        onCancel={cancelDelete}
      />

      <ConfirmDialog
        open={deleteFolderTarget !== null}
        title="删除文件夹？"
        body={`${deleteFolderTarget ?? ''} 是空文件夹，删除不会改变版本内容。`}
        confirmLabel="删除文件夹"
        danger={false}
        onConfirm={confirmDeleteFolder}
        onCancel={cancelDeleteFolder}
      />

      <ConfirmDialog
        open={leaveTarget !== null}
        title={`有 ${dirtyPaths.length} 个文件未保存`}
        body="离开此页将丢失这些改动。"
        confirmLabel="放弃并离开"
        cancelLabel="继续编辑"
        onConfirm={leave}
        onCancel={cancelLeave}
      />

      <svg style={{ position: 'absolute', width: 0, height: 0, overflow: 'hidden' }} aria-hidden="true">
        <symbol id="fileMark" viewBox="0 0 24 24" fill="none">
          <path
            d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinejoin="round"
          />
          <path d="M14 3v5h5" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
        </symbol>
      </svg>
    </section>
  )
}
