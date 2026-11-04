/**
 * The file tab's reading of the manifest: which directory is open, what one level of it holds, and
 * which file is being read.
 *
 * Framework-free. The listing is one level at a time (design), the root opens `SKILL.md` by
 * default, and a version that is not on screen opens no files at all (TD §接口 8) — those three
 * rules are here so the listing, the breadcrumb and the open-file box read them once.
 */

import type { AuthoredFile, AuthoredVersion } from '../../api/types'

export type Entry =
  | { kind: 'dir'; name: string; relpath: string }
  | { kind: 'file'; name: string; relpath: string; size: number; isBinary: boolean }

/** Whether some file in the manifest sits under this directory. The root is not answered here. */
export function isDirectoryOf(files: AuthoredFile[], relpath: string): boolean {
  return relpath !== '' && files.some((file) => file.relpath.startsWith(`${relpath}/`))
}

/**
 * The directory being listed.
 *
 * A file's path already carries the directory it lives in, so the listing follows the file rather
 * than being told where to look twice. `?path=` names a directory when no file is open, and a path
 * the manifest does not have falls back to the root rather than to a blank page — the design's rule
 * for a directory in the address, applied to the one a file address implies as well (design README
 * §一个 skill: 「地址里的目录不存在时落回根目录」).
 */
export function currentDirOf(
  relpath: string | undefined,
  askedPath: string | null,
  files: AuthoredFile[],
): string {
  if (relpath !== undefined) {
    const slash = relpath.lastIndexOf('/')
    const dir = slash < 0 ? '' : relpath.slice(0, slash)
    // An address naming a file this version does not have still names a directory — the one the
    // link meant. It is the root only when that directory is not there either.
    return dir === '' || isDirectoryOf(files, dir) ? dir : ''
  }
  // `?path=references/` is the same directory as `references`; the slash is dropped before the
  // segment is used, or every relpath would be matched against `references//`.
  const asked = (askedPath ?? '').replace(/\/+$/, '')
  if (asked === '') {
    return ''
  }
  return isDirectoryOf(files, asked) ? asked : ''
}

/**
 * One level of the tree: the folders and files whose parent is `currentDir`.
 *
 * **One level at a time** (design): a folder goes into its own listing, a file opens below. Folders
 * come first and files after, each sorted by name — the shape a repository's file table has.
 */
export function levelEntriesOf(
  files: AuthoredFile[],
  shownVersion: AuthoredVersion | null,
  currentDir: string,
): Entry[] {
  // **No version on screen, no files.** `skill.files` is the selected version's manifest, and a
  // discarded version is still selected server-side — drawing its rows here would be the same lie
  // the fallback to `liveVersion` told (TD §接口 8).
  if (shownVersion === null) {
    return []
  }
  const dir = currentDir
  const prefix = dir === '' ? '' : `${dir}/`
  const folders = new Map<string, Entry>()
  const entries: Entry[] = []
  for (const file of files) {
    if (!file.relpath.startsWith(prefix)) {
      continue
    }
    const rest = file.relpath.slice(prefix.length)
    const slash = rest.indexOf('/')
    if (slash >= 0) {
      const folder = rest.slice(0, slash)
      if (!folders.has(folder)) {
        folders.set(folder, { kind: 'dir', name: folder, relpath: `${prefix}${folder}` })
      }
    } else if (rest !== '') {
      entries.push({
        kind: 'file',
        name: rest,
        relpath: file.relpath,
        size: file.size,
        isBinary: file.is_binary,
      })
    }
  }
  const byName = (a: Entry, b: Entry): number => a.name.localeCompare(b.name)
  return [...folders.values()].sort(byName).concat(entries.sort(byName))
}

/** The relpath being read: the address's, or the root body when the address names no file. */
export function openRelpathOf(relpath: string | undefined, currentDir: string): string | undefined {
  if (relpath !== undefined) {
    return relpath
  }
  if (currentDir === '') {
    return 'SKILL.md'
  }
  return undefined
}

export function openFileOf(
  files: AuthoredFile[] | null,
  openRelpath: string | undefined,
  shownVersion: AuthoredVersion | null,
): { relpath: string; isBinary: boolean } | null {
  // A file opens under a version, and a version that is not on screen opens none (TD §接口 8).
  if (openRelpath === undefined || files === null || shownVersion === null) {
    return null
  }
  const entry = files.find((file) => file.relpath === openRelpath)
  return entry === undefined ? null : { relpath: entry.relpath, isBinary: entry.is_binary }
}

export function openIsMarkdownOf(openRelpath: string | undefined): boolean {
  return /\.(md|markdown)$/i.test(openRelpath ?? '')
}

/** The path inside the skill, segment by segment, each one back to its own level. */
export function dirPartsOf(currentDir: string): string[] {
  return currentDir === '' ? [] : currentDir.split('/')
}
