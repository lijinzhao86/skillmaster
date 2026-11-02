import { useState } from 'react'
import type { MutableRefObject } from 'react'
import { codeMessage } from '../../api/errors'
import {
  createDraft,
  editDraft,
  getSkill,
  getSkillFile,
} from '../../api/skills'
import { createDraftWithFile, uploadDraftFile } from '../../api/upload'
import type {
  ApiResult,
  AuthoredFile,
  AuthoredVersion,
  DraftEditBody,
  ErrorDetail,
} from '../../api/types'
import { replaceAddress } from '../../address'
import { dirtyPathsOf } from '../../editor/files'
import { useLive } from '../../hooks/useLive'
import { versionSuffix } from '../../version'
import { skillHrefOf, versionHrefOf, versionsHrefOf } from './links'
import type { EditorDocument } from './useEditorDocument'
import type { EditorLabels } from './useEditorLabels'
import type { DuplicateNotice } from './types'

/**
 * The save flow and the three notices it can raise: conflict, duplicate, and a failed request.
 *
 * One request at a time, because a second Enter while the first is in flight would create twice or
 * save against a baseline the first answer is about to replace. A successful answer is adopted in
 * place — the row's new digest, the labels the server holds, and the buffer bookkeeping the edit
 * changed — and the address is rewritten to name the row that now exists.
 */
export interface EditorSaveOptions {
  namespace: string
  name: string
  doc: EditorDocument
  labels: EditorLabels
  canSaveRef: MutableRefObject<boolean>
  /** Set when this page is deliberately leaving, so the native prompt does not ask again. */
  leavingPage: MutableRefObject<boolean>
}

export function useEditorSave({
  namespace,
  name,
  doc,
  labels,
  canSaveRef,
  leavingPage,
}: EditorSaveOptions) {
  const [saving, setSaving, savingRef] = useLive(false)
  const [conflict, setConflict] = useState(false)
  const [duplicate, setDuplicate] = useState<DuplicateNotice | null>(null)
  const [errorBanner, setErrorBanner] = useState<string | null>(null)
  const [errorTitle, setErrorTitle] = useState('保存失败。')
  const [pendingEdit, setPendingEdit] = useState<DraftEditBody | null>(null)
  const [uploads, setUploads] = useState<UploadItem[]>([])

  const skillHref = skillHrefOf(namespace, name)
  const versionsHref = versionsHrefOf(skillHref)

  async function save(): Promise<void> {
    if (!canSaveRef.current) return
    const path = doc.currentRef.current
    await send({ op: 'write', path, text: doc.buffersRef.current[path] ?? '' })
  }

  async function send(edit: DraftEditBody): Promise<boolean> {
    // A second Enter while the first request is in flight would create twice or save against a
    // baseline the first answer is about to replace. One request at a time.
    if (savingRef.current) return false
    if (!labels.requireNameForDraft()) return false
    setSaving(true)
    setErrorBanner(null)
    const from = doc.baseFromRef.current
    const digest = doc.baseDigestRef.current
    const result = doc.isNewRef.current
      ? await createDraft(namespace, name, {
          name: labels.draftNameRef.current.trim(),
          note: labels.draftNoteRef.current.trim(),
          ...(from === undefined ? {} : { from }),
          ...(digest === null ? {} : { from_digest: digest }),
          edit,
        })
      : await editDraft(namespace, name, doc.readSuffixNow() as string, {
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

  /**
   * Uploads one file. A binary file has no text buffer, so the pane must show it as a download or a
   * preview rather than as an editable string. A text file does have one — the pane reads it back
   * after the manifest confirms it, because an empty editor that saves would overwrite the upload.
   *
   * <p>{@code clearError} is false for every file after the first in a batch: a failure in the
   * middle of a batch must stay on screen rather than be wiped by the next file starting.
   */
  async function upload(file: File, path: string, clearError = true): Promise<boolean> {
    if (savingRef.current) return false
    if (!labels.requireNameForDraft()) return false
    setSaving(true)
    if (clearError) setErrorBanner(null)
    setUploads((current) => [...current.filter((item) => item.path !== path),
      { path, percent: 0 }])
    const progress = (fraction: number) => {
      setUploads((current) => current.map((item) => item.path === path
        ? { ...item, percent: Math.round(fraction * 100) }
        : item))
    }
    const from = doc.baseFromRef.current
    const digest = doc.baseDigestRef.current
    const result = doc.isNewRef.current
      ? await createDraftWithFile(namespace, name, {
          name: labels.draftNameRef.current.trim(),
          note: labels.draftNoteRef.current.trim(),
          ...(from === undefined ? {} : { from }),
          ...(digest === null ? {} : { from_digest: digest }),
          path,
          file,
        }, progress)
      : await uploadDraftFile(namespace, name, doc.readSuffixNow() as string, path, file,
          digest ?? '', progress)
    setSaving(false)
    setUploads((current) => current.filter((item) => item.path !== path))
    if (!result.ok) {
      showFailure(result.code, result.message, '上传失败。', result.details, `${path}：`)
      return false
    }
    const uploaded = result.data.files.find((entry) => entry.relpath === path)
    adopt(result.data.version, result.data.files, { op: 'write', path, text: '' }, path,
      clearError)
    if (uploaded !== undefined && !uploaded.is_binary) {
      // The manifest says this is text, so the pane must hold the server's bytes. Without this the
      // editor shows an empty file, and the next save writes that emptiness over the upload.
      void doc.ensureText(path)
    }
    return true
  }

  function adopt(version: AuthoredVersion, nextFiles: AuthoredFile[], edit: DraftEditBody,
      binaryPath?: string, clearError = true): void {
    const wasNew = doc.isNewRef.current
    doc.setCurrentVersion(version)
    doc.setIsNew(false)
    doc.setBaseDigest(version.digest)
    doc.setBaseFrom(versionSuffix(version))
    // **Saved means what the server holds**, not what the fields hold: a body save carries no
    // labels, so a name typed but not yet blurred must stay dirty and be PATCHed by its blur.
    labels.setSavedName(version.draft_name ?? '')
    labels.setSavedNote(version.note ?? '')
    doc.setFiles(nextFiles)
    if (binaryPath !== undefined) {
      // A binary file is not a text buffer: nothing here should be marked as saved text, and the
      // pane must open it so the preview or download notice is what the user sees.
      doc.removeBuffer(binaryPath)
      doc.removeSaved(binaryPath)
      doc.setCurrent(binaryPath)
    } else if (edit.op === 'write' && edit.path !== undefined) {
      // What the server has is `sent`; what is in the field may have grown while the request was in
      // flight. Recording `sent` as saved keeps that typing dirty instead of reverting it — and the
      // buffer itself is left exactly as the field has it.
      doc.writeSaved(edit.path, edit.text ?? '')
    } else if (edit.op === 'delete' && edit.path !== undefined) {
      doc.removeBuffer(edit.path)
      doc.removeSaved(edit.path)
      if (doc.currentRef.current === edit.path) doc.selectFirstFile()
    } else if (edit.op === 'rename' && edit.from !== undefined && edit.to !== undefined) {
      const from = edit.from
      const to = edit.to
      if (doc.buffersRef.current[from] !== undefined) {
        doc.writeBuffer(to, doc.buffersRef.current[from] as string)
        doc.removeBuffer(from)
      }
      if (doc.savedRef.current[from] !== undefined) {
        doc.writeSaved(to, doc.savedRef.current[from] as string)
        doc.removeSaved(from)
      }
      if (doc.currentRef.current === from) doc.setCurrent(to)
    }
    setConflict(false)
    setPendingEdit(null)
    setDuplicate(null)
    // A success normally clears the previous refusal. Inside an upload batch it must not: a later
    // file succeeding would erase the earlier file's failure, and that failed row is already gone.
    if (clearError) setErrorBanner(null)
    writeDraftAddress()
    if (wasNew) {
      // Until the row existed, both label writes returned early (`isNew`), so a name or note typed
      // while the create request was in flight was never sent. Send it now that there is a row.
      if (labels.draftNameRef.current.trim() !== labels.savedNameRef.current) void labels.commitName()
      if (labels.draftNoteRef.current.trim() !== labels.savedNoteRef.current) void labels.commitNote()
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
          href: versionHrefOf(skillHref, pin),
        })
      } else if (state === 'draft' && code !== undefined && code !== '') {
        // The design draws this banner for a published version and for a draft. A discarded row also
        // occupies the digest, but the design has no word for it and its page may not be readable —
        // it gets the neutral sentence and the version list rather than being called a draft.
        setDuplicate({
          title: `该内容已属于草稿 ${code}。`,
          label: `查看 ${code}`,
          href: versionHrefOf(skillHref, code),
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
    // The details travel: v39's size refusals carry `actual_bytes` / `limit_bytes`, and dropping
    // them turned 「这一版有 530 MiB，超过 512 MiB 的上限。」 into a sentence with no numbers.
    showFailure(result.code, result.message, '保存失败。', result.details)
  }

  function showFailure(code: string, message: string, title = '保存失败。',
      details: ErrorDetail[] = [], context = ''): void {
    setErrorTitle(title)
    // 「修改后可再次保存。」 is advice about a save; a failed read has nothing to save again.
    const advice = title === '保存失败。' ? '修改后可再次保存。' : ''
    const reason = codeMessage(code, message, details)
    // The path prefixes the reason only when the reason does not already name the file: a size
    // refusal reads 「文件 <路径> 有 …」, and 「<路径>：文件 <路径> 有 …」 says it twice.
    const prefix = context !== '' && !reason.includes(context.slice(0, -1)) ? context : ''
    setErrorBanner(`${prefix}${reason}${advice}`)
  }

  /** The draft's address is born with the row: `…@<version code>/edit`, then the file it was on. */
  function writeDraftAddress(): void {
    const version = doc.currentVersionRef.current
    if (version === null) return
    const url = `${skillHref}@${encodeURIComponent(versionSuffix(version))}/edit?file=${encodeURIComponent(doc.currentRef.current)}`
    replaceAddress(url)
  }

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
    const result = await getSkill(namespace, name, doc.readSuffixNow())
    if (!result.ok) {
      // A failed re-read must not become a reload: reload() sets `leavingPage` and throws away every
      // buffer, which is exactly what this path exists to avoid. Leave the page where it is — the
      // stale baseline meets the next save as an ordinary conflict banner, which offers 重新加载.
      showFailure(result.code, result.message, '没能重新读取这一版。')
      return
    }
    if (result.data.version === null) {
      // Nothing left to edit. Reloading is only safe when there is nothing unsaved to lose.
      if (dirtyPathsOf(doc.buffersRef.current, doc.savedRef.current).length === 0) {
        reload()
        return
      }
      showFailure('', '这一版现在读不到了。', '没能重新读取这一版。')
      return
    }
    doc.setCurrentVersion(result.data.version)
    doc.setBaseDigest(result.data.version.digest)
    doc.setFiles(result.data.files)
    if (doc.isNewRef.current) doc.setBaseCount(result.data.files.length)
    for (const path of Object.keys(doc.buffersRef.current)) {
      if (!result.data.files.some((file) => file.relpath === path)) {
        doc.removeBuffer(path)
        doc.removeSaved(path)
      }
    }
    // The baseline moved, so every clean buffer that is still here is one write behind — the same
    // moment `refreshStaleBuffers` exists for after an overwrite. Unsaved text is left alone.
    await refreshStaleBuffers(undefined)
    if (!result.data.files.some((file) => file.relpath === doc.currentRef.current)) doc.selectFirstFile()
  }

  /**
   * A successful overwrite is the one moment another writer's bytes really are in this page's base.
   * A file that was opened but not edited is now stale, and saving it later would use the new digest
   * and quietly revert the other writer. Re-read those; a file with local unsaved text is left alone —
   * it is the user's own content, and dropping it is worse than a visible conflict.
   */
  async function refreshStaleBuffers(except: string | undefined): Promise<void> {
    const suffix = doc.readSuffixNow()
    for (const path of Object.keys(doc.buffersRef.current)) {
      if (path === except) continue
      if (doc.buffersRef.current[path] !== doc.savedRef.current[path]) continue
      const file = doc.filesRef.current.find((candidate) => candidate.relpath === path)
      if (file === undefined || file.is_binary) continue
      const baseline = doc.savedRef.current[path]
      const result = await getSkillFile(namespace, name, suffix, path)
      // Checked again after the await: text typed, or a save completed, while the re-read was in
      // flight is not what this copy describes, and must not be replaced by it.
      if (doc.buffersRef.current[path] !== baseline || doc.savedRef.current[path] !== baseline) continue
      if (!doc.filesRef.current.some((candidate) => candidate.relpath === path)) {
        // Removed while the read was in flight; there is nothing here to keep.
        doc.removeBuffer(path)
        doc.removeSaved(path)
        continue
      }
      if (!result.ok) {
        // A clean stale buffer that cannot be refreshed is dropped, so opening it reads the server
        // rather than keeping bytes that would revert the other writer on the next save.
        doc.removeBuffer(path)
        doc.removeSaved(path)
        showFailure(result.code, result.message, '没能打开这个文件。')
        if (path === doc.currentRef.current) void doc.ensureText(path)
        continue
      }
      doc.writeBuffer(path, result.data)
      doc.writeSaved(path, result.data)
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
    const from = doc.baseFromRef.current
    if (from !== undefined) {
      const latest = await getSkill(namespace, name, from)
      if (latest.ok && latest.data.version !== null) {
        doc.setBaseDigest(latest.data.version.digest)
      }
    }
    setPendingEdit(null)
    // 覆盖重写**当前**缓冲区，而不是失败那一刻的文本：冲突之后继续输入的文字必须算在这次写入里
    // (PRD #10: 「覆盖」用本地内容按最新基线再写一次).
    const replayed: DraftEditBody =
      edit.op === 'write' && edit.path !== undefined
        ? { op: 'write', path: edit.path, text: doc.buffersRef.current[edit.path] ?? edit.text ?? '' }
        : edit
    const sent = await send(replayed)
    if (sent) {
      await refreshStaleBuffers(replayed.op === 'write' ? replayed.path : undefined)
    }
  }

  return {
    saving,
    conflict,
    duplicate,
    errorBanner,
    setErrorBanner,
    errorTitle,
    pendingEdit,
    save,
    send,
    upload,
    uploads,
    overwrite,
    reload,
    showFailure,
  }
}

/** One file in flight: its target path and how much of it has arrived. */
export interface UploadItem {
  path: string
  percent: number
}

export type EditorSave = ReturnType<typeof useEditorSave>
