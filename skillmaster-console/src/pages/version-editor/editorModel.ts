/**
 * The editor's questions that can be answered from its state alone.
 *
 * No React here: the tree's flattened rows, the header's facts line, a path's refusal, and the
 * editor's own value formatting are pure functions. The hooks hold the state these answers are
 * computed from; this file is what they call.
 */

import type { AuthoredFile, AuthoredSkill } from '../../api/types'
import {
  ancestorsOf,
  buildFileTree,
  fileExists,
  folderExists,
  hasFilesUnder,
  validatePath,
  type FileNode,
} from '../../editor/files'
import { formatMib } from '../../format'
import { collisionIssue } from '../../nameRules'

/** One drawn line: a folder or a file, already flattened to the depth it renders at. */
export interface EditorTreeRow {
  path: string
  name: string
  depth: number
  dir: boolean
  expanded: boolean
  /** A folder with nothing under it — drawn but not expandable. */
  empty: boolean
}

/**
 * The tree the page draws: the recursive tree from `files.ts`, flattened to rows with the expansion
 * and search facts the component reads.
 *
 * **A search matches files, never folders** (design: `files.filter(…); buildTree(matched, {})`).
 * Building the tree from the matches also means an empty folder cannot appear while searching, and
 * every ancestor that does appear is one a matched file lives in.
 */
export function buildTreeRows(
  files: ReadonlyArray<{ relpath: string }>,
  localFolders: readonly string[],
  opened: Record<string, boolean>,
  query: string,
): EditorTreeRow[] {
  const needle = query.trim().toLowerCase()
  const searching = needle !== ''
  const out: EditorTreeRow[] = []
  const walk = (nodes: FileNode[], depth: number): void => {
    for (const node of nodes) {
      const expanded = searching ? true : opened[node.path] === true
      out.push({
        path: node.path,
        name: node.name,
        depth,
        dir: node.dir,
        expanded,
        empty: node.dir && !hasFilesUnder(files, node.path),
      })
      if (node.dir && expanded) walk(node.children, depth + 1)
    }
  }
  walk(
    buildFileTree(files, localFolders, !searching, (relpath) => !searching || relpath.toLowerCase().includes(needle)),
    0,
  )
  return out
}

/** Labels are counted in code points, so an emoji is one character (v8: 第 41 个字符进不去). */
export function clampPoints(value: string, max: number): string {
  return Array.from(value).slice(0, max).join('')
}

export function byteLabel(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024).toFixed(1)} KB`
}

export function sizeLabel(text: string): string {
  return byteLabel(new TextEncoder().encode(text).length)
}

/** The pane's footer: a binary shows its manifest size, text shows its encoded size and charset. */
export function infoText(
  currentBinary: boolean,
  currentFile: AuthoredFile | null,
  currentText: string,
): string {
  if (currentBinary) {
    return currentFile === null ? '' : byteLabel(currentFile.size)
  }
  return `${sizeLabel(currentText)} · UTF-8`
}

/** 草稿　·　<code>　·　提交人 <who>　·　<n> 个文件 — the header's one facts line, already worded. */
export function factsText(
  isNew: boolean,
  readSuffix: string | undefined,
  submittedBy: string,
  fileCount: number,
  baseLabel: string,
  baseCount: number,
): string {
  if (!isNew) {
    const code = readSuffix ?? ''
    return `草稿　·　${code}　·　提交人 ${submittedBy}　·　${fileCount} 个文件`
  }
  return `${baseLabel}　·　${baseCount} 个文件`
}

/** The smallest valid body: the template v8 TD §数据模型 defines for an empty base. */
export function blankTemplate(data: AuthoredSkill): string {
  const description = (data.description ?? '').replace(/\\/g, '\\\\').replace(/"/g, '\\"')
  const title = data.title.trim() === '' ? data.name : data.title
  return `---\nname: ${data.name}\ndescription: "${description}"\n---\n\n# ${title}\n`
}

/** Every path the version already answers to — files, their folders, and the empty ones. */
export function knownPathsOf(
  files: ReadonlyArray<{ relpath: string }>,
  localFolders: readonly string[],
): string[] {
  const paths: string[] = [...localFolders]
  for (const file of files) {
    paths.push(...ancestorsOf(file.relpath), file.relpath)
  }
  return paths
}

/** Whether a typed new entry may be created, as a sentence or `null`. */
export function createPathError(
  path: string,
  kind: 'file' | 'dir',
  files: ReadonlyArray<{ relpath: string }>,
  localFolders: readonly string[],
): string | null {
  const issue = validatePath(path)
  if (issue !== null) return issue
  if (kind === 'file') {
    if (fileExists(files, path)) return '该路径已存在文件。'
    if (folderExists(files, localFolders, path)) return '该路径已是文件夹。'
    if (files.some((file) => path.startsWith(`${file.relpath}/`))) {
      return '该路径已是文件。'
    }
  } else {
    if (fileExists(files, path)) return '该路径已是文件。'
    if (files.some((file) => path.startsWith(`${file.relpath}/`))) {
      return '该路径已是文件。'
    }
    if (folderExists(files, localFolders, path)) return '该文件夹已存在。'
  }
  return collisionIssue(path, knownPathsOf(files, localFolders))
}

/** Whether a rename may land on `to`, as a sentence or `null`. A rename never collides with its own row. */
export function renamePathError(
  to: string,
  from: string,
  files: ReadonlyArray<{ relpath: string }>,
  localFolders: readonly string[],
): string | null {
  return (
    validatePath(to) ??
    (fileExists(files, to)
      ? '该路径已存在文件。'
      : folderExists(files, localFolders, to)
        ? '该路径已是文件夹。'
        : files.some((file) => to.startsWith(`${file.relpath}/`))
          ? '该路径已是文件。'
          : collisionIssue(to, knownPathsOf(files, localFolders), from))
  )
}

/**
 * Whether the open file can be shown in place instead of edited (v39).
 *
 * The server's inline allowlist is the same set: raster images and PDF. SVG is deliberately absent —
 * it is XML and can carry script, so it is a download like everything else.
 */
export function previewKindOf(path: string): 'image' | 'pdf' | null {
  const dot = path.lastIndexOf('.')
  if (dot < 0) return null
  const extension = path.slice(dot + 1).toLowerCase()
  if (extension === 'png' || extension === 'jpg' || extension === 'jpeg'
    || extension === 'gif' || extension === 'webp') {
    return 'image'
  }
  if (extension === 'pdf') return 'pdf'
  return null
}

/** The server's ceilings, in the client's words; they are not configurable (v39). */
export const MAX_FILE_BYTES = 100 * 1024 * 1024
export const MAX_SKILL_BYTES = 512 * 1024 * 1024
export const MAX_SKILL_FILES = 512


/**
 * Before the request, not after it: a 150 MiB file should never cross the network to be refused
 * (PRD A4 / T14). Returns the sentence to show, or null when the batch fits.
 */
export function uploadRefusal(
  incoming: { name: string; size: number }[],
  existing: AuthoredFile[],
): string | null {
  for (const file of incoming) {
    if (file.size > MAX_FILE_BYTES) {
      return `文件 ${file.name} 有 ${formatMib(file.size)}，超过单文件上限 ${formatMib(MAX_FILE_BYTES)}。`
    }
  }
  if (existing.length + incoming.length > MAX_SKILL_FILES) {
    return `这一版最多 ${MAX_SKILL_FILES} 个文件。`
  }
  const total = existing.reduce((sum, file) => sum + file.size, 0)
    + incoming.reduce((sum, file) => sum + file.size, 0)
  if (total > MAX_SKILL_BYTES) {
    return `这一版加上这次上传会超过 ${formatMib(MAX_SKILL_BYTES)} 的上限。`
  }
  return null
}
