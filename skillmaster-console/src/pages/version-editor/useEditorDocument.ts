import { useState } from 'react'
import type { MutableRefObject } from 'react'
import { getBlankSkill, getSkill, getSkillFile } from '../../api/skills'
import type { AuthoredFile, AuthoredSkill, AuthoredVersion } from '../../api/types'
import { ancestorsOf, dirtyPathsOf, fileExists as treeFileExists } from '../../editor/files'
import { useLive } from '../../hooks/useLive'
import { canEditDraft, canWrite } from '../../versionActions'
import { versionSuffix } from '../../version'
import { blankTemplate, buildTreeRows, factsText } from './editorModel'
import type { FailureReporter, LabelsInit } from './types'

/**
 * The documents this page holds: which version is its base, the manifest, the buffers, the open
 * file, the tree's expansion and search, and the load that fills them.
 *
 * It owns no banner and no dialog — those are other hooks. What it does own is the one rule the
 * editor is built around: the draft row is created on the **first content change**, never when the
 * page opens (v8 TD §架构: copying a live version verbatim would carry its digest, and
 * `UNIQUE (skill_id, digest)` refuses two rows with one content). Every value that outlives an
 * `await` or a document listener is mirrored into a ref by `useLive`.
 */
export interface EditorDocumentOptions {
  namespace: string
  name: string
  mode: 'new' | 'edit'
  pathVersion: string | undefined
  requestedFile: string | undefined
  username: string | null
  /** A failed file read is reported by the save hook's banner, so the two cannot disagree. */
  reportFailure: MutableRefObject<FailureReporter>
  /** Set by the label hook once it exists; the load is the one caller that fills both fields. */
  initializeLabels: MutableRefObject<LabelsInit | null>
}

export function useEditorDocument({
  namespace,
  name,
  mode,
  pathVersion,
  requestedFile,
  username,
  reportFailure,
  initializeLabels,
}: EditorDocumentOptions) {
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
  /** Narrow windows start with the tree folded away; the tree itself draws and reports it. */
  const [treeCollapsed, setTreeCollapsed] = useState(false)

  const readSuffix = currentVersion === null ? undefined : versionSuffix(currentVersion)
  const currentFile = files.find((file) => file.relpath === current) ?? null
  const currentText = buffers[current] ?? ''
  const currentBinary = currentFile?.is_binary === true
  const gutterText = Array.from(
    { length: currentText.split('\n').length },
    (_, index) => String(index + 1),
  ).join('\n')
  const dirtyPaths = dirtyPathsOf(buffers, saved)
  const currentDirty = buffers[current] !== saved[current]
  const rows = buildTreeRows(files, localFolders, opened, query)
  const facts = factsText(
    isNew,
    readSuffix,
    currentVersion?.submitted_by ?? '',
    files.length,
    baseLabel,
    baseCount,
  )

  function fileExists(path: string): boolean {
    return treeFileExists(filesRef.current, path)
  }

  function expandAncestors(path: string): void {
    const next = { ...openedRef.current }
    for (const folder of ancestorsOf(path)) next[folder] = true
    setOpened(next)
  }

  function toggleFolder(path: string): void {
    setOpened({ ...openedRef.current, [path]: !openedRef.current[path] })
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
      reportFailure.current(result.code, result.message, '没能打开这个文件。')
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

  function selectFirstFile(): void {
    const first =
      filesRef.current.find((file) => file.relpath === 'SKILL.md') ?? filesRef.current[0]
    if (first !== undefined) {
      setCurrent(first.relpath)
      void ensureText(first.relpath)
    }
  }

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
        !canEditDraft(version, result.data.rung, username)
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
    initializeLabels.current?.(nextName, nextNote)
  }

  return {
    loading,
    missing,
    setLoading,
    setMissing,
    currentVersion,
    setCurrentVersion,
    currentVersionRef,
    isNew,
    isNewRef,
    setIsNew,
    setBaseDigest,
    baseDigestRef,
    setBaseFrom,
    baseFromRef,
    setBaseCount,
    files,
    filesRef,
    setFiles,
    localFolders,
    localFoldersRef,
    setLocalFolders,
    buffers,
    buffersRef,
    saved,
    savedRef,
    current,
    currentRef,
    setCurrent,
    query,
    setQuery,
    treeCollapsed,
    setTreeCollapsed,
    readSuffix,
    rows,
    currentFile,
    currentText,
    currentBinary,
    gutterText,
    dirtyPaths,
    currentDirty,
    facts,
    fileExists,
    expandAncestors,
    toggleFolder,
    readSuffixNow,
    currentBinaryNow,
    ensureText,
    selectFirstFile,
    writeBuffer,
    removeBuffer,
    writeSaved,
    removeSaved,
    addLocalFolder,
    load,
  }
}

export type EditorDocument = ReturnType<typeof useEditorDocument>
