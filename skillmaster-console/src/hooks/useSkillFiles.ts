import { useState } from 'react'
import { go } from '../address'
import { codeMessage } from '../api/errors'
import { getSkillFile } from '../api/skills'
import type { AuthoredSkill, AuthoredVersion } from '../api/types'
import { loginPath } from '../login'
import { versionSuffix, versionTitle } from '../version'
import {
  commitVersionHrefFor,
  dirHrefFor,
  fileDownloadHrefFor,
  fileHrefFor,
  baseAddressFor,
  type SkillAddressParts,
} from '../pages/skill/skillAddress'
import {
  currentDirOf,
  dirPartsOf,
  levelEntriesOf,
  openFileOf,
  openIsMarkdownOf,
  openRelpathOf,
  type Entry,
} from '../pages/skill/fileListing'
import type { Tab } from '../pages/skill/skillRoute'

/**
 * The file tab: which level is listed, what is open, and the one read that fetches its bytes.
 *
 * The listing itself is {@link currentDirOf} / {@link levelEntriesOf} — pure readings of the
 * manifest. What lives here is the byte read, which is the only part that has state of its own.
 */
export interface SkillFilesProps {
  namespace: string
  name: string
  skill: AuthoredSkill | null
  relpath: string | undefined
  params: URLSearchParams
  parts: SkillAddressParts
  tab: Tab
  rung: string
  shownVersion: AuthoredVersion | null
  fileTabVersion: AuthoredVersion | null
}

export interface SkillFilesResult {
  currentDir: string
  levelEntries: Entry[]
  openRelpath: string | undefined
  openFile: { relpath: string; isBinary: boolean } | null
  openIsMarkdown: boolean
  crumbs: Array<{ name: string; href: string }>
  fileContent: string | null
  readingFile: boolean
  fileProblem: string | null
  fileHref: (file: string) => string
  dirHref: (dir: string) => string
  commitVersionHref: string
  commitVersionLabel: string
  fileDownloadHref: string | null
  onPickVersion: (suffix: string) => void
  loadFile: () => Promise<void>
}

export function useSkillFiles({
  namespace,
  name,
  skill,
  relpath,
  params,
  parts,
  tab,
  rung,
  shownVersion,
  fileTabVersion,
}: SkillFilesProps): SkillFilesResult {
  const currentDir = currentDirOf(relpath, params.get('path'), skill?.files ?? [])

  /**
   * One level of the tree: the folders and files whose parent is {@link currentDir}.
   *
   * **No version on screen, no files.** `skill.files` is the selected version's manifest, and a
   * discarded version is still selected server-side — drawing its rows here would be the same lie
   * the fallback to `liveVersion` told (TD §接口 8).
   */
  const levelEntries = levelEntriesOf(skill?.files ?? [], shownVersion, currentDir)

  /** The relpath being read: the address's, or the root body when the address names no file. */
  const openRelpath = openRelpathOf(relpath, currentDir)

  const openFile = openFileOf(skill?.files ?? null, openRelpath, shownVersion)

  const openIsMarkdown = openIsMarkdownOf(openRelpath)

  const versionSuffixOf = (): string | undefined =>
    shownVersion === null ? undefined : versionSuffix(shownVersion)

  function fileHref(file: string): string {
    return fileHrefFor(parts, file, versionSuffixOf())
  }

  /** A directory's own level, as an address: the listing is one level at a time. */
  function dirHref(dir: string): string {
    return dirHrefFor(parts, params, dir, versionSuffixOf())
  }

  /** The path inside the skill, segment by segment, each one back to its own level. */
  const crumbs = dirPartsOf(currentDir).map((segment, index, partsList) => ({
    name: segment,
    href: dirHref(partsList.slice(0, index + 1).join('/')),
  }))

  const commitVersionHref = commitVersionHrefFor(parts, fileTabVersion)

  const commitVersionLabel = (() => {
    if (fileTabVersion === null) {
      return ''
    }
    const title = versionTitle(fileTabVersion, rung)
    return fileTabVersion.name === null ? title : `${title} @${fileTabVersion.name}`
  })()

  const fileDownloadHref = fileDownloadHrefFor(parts, shownVersion)

  function onPickVersion(suffix: string): void {
    go(`${baseAddressFor(parts, suffix)}?tab=files`)
  }

  /* One file of the shown version. The root's SKILL.md is what the listing opens by default. */
  const [fileContent, setFileContent] = useState<string | null>(null)
  const [readingFile, setReadingFile] = useState(false)
  const [fileProblem, setFileProblem] = useState<string | null>(null)

  async function loadFile(): Promise<void> {
    setFileContent(null)
    setFileProblem(null)
    // Only the files section reads bytes. Every other tab is a full navigation away, so this is
    // decided once, at mount.
    if (tab !== 'files') {
      return
    }
    if (openRelpath === undefined || openFile === null || openFile.isBinary) {
      return
    }
    setReadingFile(true)
    const result = await getSkillFile(namespace, name, versionSuffixOf(), openRelpath)
    setReadingFile(false)
    if (result.ok) {
      setFileContent(result.data)
      return
    }
    if (result.code === 'unauthenticated') {
      // The one read in the app that can come back 401 *after* a read that succeeded — the session
      // lapsed between the two. Everywhere else a 401 is the load's business.
      go(loginPath())
      return
    }
    setFileProblem(codeMessage(result.code, result.message))
  }

  return {
    currentDir,
    levelEntries,
    openRelpath,
    openFile,
    openIsMarkdown,
    crumbs,
    fileContent,
    readingFile,
    fileProblem,
    fileHref,
    dirHref,
    commitVersionHref,
    commitVersionLabel,
    fileDownloadHref,
    onPickVersion,
    loadFile,
  }
}
