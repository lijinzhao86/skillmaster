import { useEffect, useRef, useState } from 'react'
import { formatSize } from '../format'
import { addressFor, go, query as pageParams, relativeAddress, replaceAddress } from '../address'
import { codeMessage } from '../api/errors'
import {
  deleteSkill,
  getSkillFile,
  listAccessRequests,
  requestAccess,
  setSkillOffline,
  setVisibility,
  withdrawAccessRequest,
} from '../api/skills'
import { loginPath } from '../login'
import { useSession } from '../store'
import { useSkillDetail } from '../hooks/useSkillDetail'
import { defaultBaseline, readableBy } from '../versionActions'
import type { AuthoredVersion, Rung } from '../api/types'
import { formatTime } from '../time'
import {
  answersPin,
  filterKeyOf,
  parseVersionRoute,
  versionLabel,
  versionSuffix,
  statusOf,
  versionTitle,
  type VersionFilterKey,
  type VersionRoute,
} from '../version'
import { atLeast, rungLabel, rungsAbove, RUNG_NOTES } from '../rungs'
import CommandMenu from '../components/CommandMenu'
import type { CommandMenuHandle } from '../components/CommandMenu'
import { versionCreateItems } from '../components/version/createMenu'
import ConfirmDialog from '../components/ConfirmDialog'
import FormBanner from '../components/FormBanner'
import MarkdownView from '../components/MarkdownView'
import RungMenu from '../components/RungMenu'
import SkillCollaborators from '../components/SkillCollaborators'
import VersionDetail from '../components/version/VersionDetail'
import VersionFilterBar from '../components/version/VersionFilterBar'
import type { VersionFilterBarHandle } from '../components/version/VersionFilterBar'
import VersionPicker from '../components/VersionPicker'
import ImportDialog from '../components/skill/ImportDialog'
import VersionRow from '../components/version/VersionRow'

/**
 * One skill, in the four sections the design gives it: 信息, 文件, 版本, 设置.
 *
 * **The page is drawn to the caller's rung** (ADR 0051). 信息 is open to everybody, 文件 needs
 * `browse`, drafts in 版本 need `write`, and 设置 is `admin`; anything a rung cannot reach is not
 * drawn at all rather than drawn greyed — the design's own rule ("够不到的东西不画"). The server
 * says which rung this is, so the page never has to probe a write route for a 403.
 *
 * **Every section is an address.** `?tab=` chooses the section, `?sub=` the settings sub-page, and
 * the file being read is a path under the skill (`/files/…`), which is the same spelling the server
 * advertises. A listing, a version's files, or one file is therefore something somebody can send to
 * somebody else.
 *
 * **There is no banner and no second screen for a draft.** What may be done to a version is said on
 * that version's row, and the row is where the action is. A banner's problem is that it disappears
 * when there is no draft — so 上线 only exists when you happen to be waiting on one.
 *
 * **What changed in the port.** The address is read once, in `useState` initialisers, exactly where
 * Vue's `setup` read it: `?tab=`, the version route and the settings sub-page are the page's opening
 * state, and the only writes afterwards are `replaceState` for the version list's filter. The two
 * `watch`es that corrected a stale choice (`tabs`, `above`) became effects guarded so they do not
 * fire on mount, which is what Vue's `watch` does.
 */
export interface SkillPageProps {
  namespace: string
  name: string
  version?: string
  /** A file being read, from `/skills/…/files/…`. Opens the files section with it in view. */
  relpath?: string
  /** A comparison, from `/skills/…/diff?from=&to=`. Opens the versions section with it above the table. */
  from?: string
  to?: string
}

type Tab = 'info' | 'files' | 'versions' | 'settings'

const TABS = ['info', 'files', 'versions', 'settings'] as const

/** Each top-level tab's mark, drawn once in the page's own symbol sheet (design 2026-10-20). */
const TAB_ICONS: Record<Tab, string> = {
  info: 'infoMark',
  files: 'fileMark',
  versions: 'versionMark',
  settings: 'settingsMark',
}

const SUBPAGES = ['general', 'collaborators', 'details'] as const
type Subpage = (typeof SUBPAGES)[number]

const STANDING: Partial<Record<string, string>> = {
  invoke: '当前档位为「调用」：可以调用，但无法查看文件清单。',
  browse: '当前档位为「浏览」：可以读取，但无法提交版本。',
  write: '当前档位为「写入」：可以提交版本，但无法上线。',
  maintain: '当前档位为「维护」：可以上线，但无法修改可见性与协作者。',
}

type Entry =
  | { kind: 'dir'; name: string; relpath: string }
  | { kind: 'file'; name: string; relpath: string; size: number; isBinary: boolean }

/** The version list's own parameters, read once — `replaceState` does not change what is on screen. */
function initialRoute(): VersionRoute {
  return parseVersionRoute(window.location.search)
}

export default function SkillPage({
  namespace,
  name,
  version: pathVersion,
  relpath,
  from,
  to,
}: SkillPageProps) {
  const params = useRef(pageParams()).current
  const versionRoute = useRef(initialRoute()).current
  /** `/skills/…/diff?from=&to=` is the legacy alias for `?tab=versions&version=<to>&sub=diff&from=`. */
  const legacyDiff = from !== undefined || to !== undefined

  const detail = useSkillDetail()
  const { skill, state } = detail
  const { account } = useSession()

  const rung = skill === null ? 'invoke' : skill.rung
  const canBrowse = skill !== null && atLeast(rung, 'browse')
  const canWrite = skill !== null && atLeast(rung, 'write')
  const canMaintain = skill !== null && atLeast(rung, 'maintain')
  const canAdmin = skill !== null && atLeast(rung, 'admin')

  /** The sections this rung can reach. 设置 is last, which is also the least frequent reason to come. */
  const tabs: Array<{ key: Tab; label: string }> = [{ key: 'info', label: '信息' }]
  if (canBrowse) {
    tabs.push({ key: 'files', label: '文件' })
  }
  tabs.push({ key: 'versions', label: '版本' })
  if (canAdmin) {
    tabs.push({ key: 'settings', label: '设置' })
  }

  const requested = params.get('tab')

  /**
   * Which section is open: what the address asked for when it names one of the four, otherwise the
   * file or the comparison when the address names those — and 信息 when it names neither.
   *
   * **An explicit `tab` wins.** The design settles the same order (`openTabs.indexOf(askTab) >= 0 ?
   * askTab : (namedFiles ? 'files' : openTabs[0])`): a directory or a file is the address's *default*
   * destination, not an override of a section it also names. Inverting that trapped the reader — a
   * tab link kept `path` in the query, the page reloaded, and every tab landed back on 文件.
   */
  const [tab, setTab] = useState<Tab>(
    requested !== null && (TABS as readonly string[]).includes(requested)
      ? (requested as Tab)
      : relpath !== undefined || params.get('path') !== null
        ? 'files'
        : legacyDiff
          ? 'versions'
          : 'info',
  )

  // A rung that cannot reach the section the address names lands on 信息 instead of on nothing.
  const tabsKey = tabs.map((entry) => entry.key).join(',')
  const tabsSettled = useRef(false)
  useEffect(() => {
    if (!tabsSettled.current) {
      tabsSettled.current = true
      return
    }
    if (!tabs.some((entry) => entry.key === tab)) {
      setTab('info')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabsKey])

  /**
   * A section's own address.
   *
   * The version tab's `version` / `sub` / `from` / `fq` / `file` / `list` belong to that tab; carrying
   * them to 信息 or 设置 would be an address naming two screens. The list's `filter` / `q` are kept
   * when going back to 版本, because returning from a version page should land on the same list.
   */
  function tabHref(key: Tab): string {
    const next = new URLSearchParams(params)
    next.delete('p')
    for (const name of ['version', 'sub', 'bump', 'from', 'to', 'fq', 'file', 'list']) {
      next.delete(name)
    }
    if (key !== 'versions') {
      next.delete('filter')
      next.delete('q')
    }
    // A directory belongs to the files section. Carrying it to another tab would be an address that
    // names two screens, and the reader would have to know which one wins.
    if (key !== 'files') {
      next.delete('path')
    }
    if (key === 'info') {
      next.delete('tab')
    } else {
      next.set('tab', key)
    }
    if (key !== 'settings') {
      next.delete('sub')
    }
    const queryString = next.toString()
    // **Only the files tab carries a version in the path**; 信息 / 版本 / 设置 are skill-level screens,
    // and an `@1.2.3` on them would name a version the screen does not show (TD §前端架构: one face,
    // one kind of pin).
    const base = key === 'files' ? baseAddress() : baseAddress(null)
    return `${base}${queryString === '' ? '' : `?${queryString}`}`
  }

  /**
   * The settings sub-page, read once from the address. Not state: its three items are real links, and
   * the only other way this changes is a full navigation — which re-reads the address.
   */
  const requestedSub = params.get('sub')
  const sub: Subpage =
    tab === 'settings' && requestedSub !== null && (SUBPAGES as readonly string[]).includes(requestedSub)
      ? (requestedSub as Subpage)
      : 'general'

  function subHref(key: Subpage): string {
    const next = new URLSearchParams(params)
    for (const name of ['version', 'bump', 'from', 'fq', 'file', 'list']) {
      next.delete(name)
    }
    next.set('tab', 'settings')
    next.set('sub', key)
    next.delete('path')
    const queryString = next.toString()
    return `${baseAddress(null)}?${queryString}`
  }

  // `null` means "no version pin at all". The versions tab carries its pin in the query (`version=`),
  // and TD §前端架构 says one face writes one kind of pin — an address carrying `@1.2.3` *and*
  // `?version=1.2.3` states it twice, and a legacy `/diff?to=` page can make the two disagree.
  function baseAddress(pin?: string | null): string {
    const suffix =
      pin === null ? '' : pin === undefined ? (pathVersion === undefined ? '' : `@${pathVersion}`) : `@${pin}`
    return `/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}${suffix}`
  }

  /* --------------------------------------------------------------- the loaded skill */

  const selected: AuthoredVersion | null = skill?.version ?? null

  /** The version consumers get. Null is an ordinary state: a skill nobody has published from yet. */
  const liveVersion: AuthoredVersion | null =
    skill?.versions.find(
      (candidate) =>
        statusOf(candidate) === 'live' ||
        (candidate.is_current && candidate.state === 'published'),
    ) ?? null

  /** Every version the server sent this rung. The server already applies the draft/revoked ladder. */
  const allVersions: AuthoredVersion[] = skill?.versions ?? []

  /**
   * The list's rows: drafts need `write`; revoked and discarded need `maintain`. Published versions
   * are readable to anybody who reaches the author plane. One filter, read by the rows, the chips and
   * the tab's count, so they cannot disagree.
   */
  const visibleVersions = allVersions.filter((candidate) => {
    const status = statusOf(candidate)
    if (status === 'draft') {
      return canWrite
    }
    if (status === 'revoked' || status === 'discarded') {
      return canMaintain
    }
    return true
  })

  /**
   * The count the tab wears and the file tab repeats: every visible version that is not a draft and
   * was not discarded — live + history + revoked (TD §计数与列表同源).
   */
  const countedVersions = visibleVersions.filter((candidate) => {
    const status = statusOf(candidate)
    return status !== 'draft' && status !== 'discarded'
  })

  /**
   * The version tab's pin: the query's `version`, or the legacy `/diff` alias's `to`, falling back to
   * the live version when the alias named none.
   */
  const detailRoute: VersionRoute = (() => {
    if (!legacyDiff) {
      return versionRoute
    }
    const target = to ?? pathVersion ?? liveVersion?.name ?? undefined
    return {
      ...versionRoute,
      ...(target === undefined ? {} : { version: target }),
      sub: 'diff',
      ...(from === undefined ? {} : { from }),
    }
  })()

  /** The version the single-version page shows, or null when the pin names none this rung can see. */
  const detailVersion: AuthoredVersion | null = (() => {
    const pin = detailRoute.version
    if (pin === undefined) {
      return null
    }
    return visibleVersions.find((candidate) => answersPin(candidate, pin)) ?? null
  })()

  const frontmatter: Record<string, unknown> = skill?.frontmatter ?? {}

  /** `when_to_use` is read from `metadata` first, with the top-level key as the fallback V21 kept. */
  const whenToUse: string = (() => {
    const metadata = frontmatter.metadata
    if (typeof metadata === 'object' && metadata !== null) {
      const value = (metadata as Record<string, unknown>).when_to_use
      if (typeof value === 'string' && value !== '') {
        return value
      }
    }
    const top = frontmatter.when_to_use
    return typeof top === 'string' ? top : ''
  })()

  const compatibility =
    typeof frontmatter.compatibility === 'string' ? frontmatter.compatibility : ''

  /** The version the information page names in its rail — the one consumers get, or none yet. */
  const liveLabel =
    liveVersion === null ? '未上线' : `@${versionLabel(versionSuffix(liveVersion))}`

  /* --------------------------------------------------------------------- the ask block */

  const above = rungsAbove(rung)
  const showAsk = skill !== null && rung !== 'owner' && above.length > 0

  const askWhy = STANDING[rung] ?? ''
  const pendingRung = skill?.pending_rung ?? null
  const [askedRung, setAskedRung] = useState<Rung>(above[0]?.name ?? 'browse')
  const aboveKey = above.map((option) => option.name).join(',')
  const aboveSettled = useRef(false)
  useEffect(() => {
    if (!aboveSettled.current) {
      aboveSettled.current = true
      return
    }
    if (!above.some((option) => option.name === askedRung)) {
      setAskedRung(above[0]?.name ?? 'browse')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [aboveKey])
  const askNote = RUNG_NOTES[askedRung] ?? ''
  const askLabel = atLeast(rung, 'write') ? '申请' : '申请成为协作者'

  async function submitAsk(): Promise<void> {
    const result = await requestAccess(namespace, name, askedRung)
    if (!result.ok) {
      detail.setBanner(codeMessage(result.code, result.message))
      return
    }
    window.location.reload()
  }

  async function withdrawAsk(): Promise<void> {
    const result = await withdrawAccessRequest(namespace, name)
    if (!result.ok) {
      detail.setBanner(codeMessage(result.code, result.message))
      return
    }
    window.location.reload()
  }

  /* ------------------------------------------------------------------- the files section */

  /** The row the address pinned in the path (`/name@pin?tab=files`), whether or not it is readable. */
  const pinnedRow: AuthoredVersion | null = (() => {
    if (pathVersion === undefined) {
      return null
    }
    return allVersions.find((candidate) => answersPin(candidate, pathVersion)) ?? null
  })()

  /** Whether the address pins a discarded version — the one pin whose files are never drawn. */
  const pinnedIsDiscarded = pinnedRow !== null && statusOf(pinnedRow) === 'discarded'

  /**
   * The version the address pinned in the path (`/name@pin?tab=files`), when this rung can read it.
   *
   * **A discarded version is not readable here** even for `maintain`, who can see the row and whose
   * bytes still exist (ADR 0031 keeps them): the design draws no files for a discarded version, so
   * the file tab offers none (design §文件栏; TD §接口 8).
   */
  const pinnedFileVersion: AuthoredVersion | null = (() => {
    if (pathVersion === undefined) {
      return null
    }
    const found = visibleVersions.find((candidate) => answersPin(candidate, pathVersion))
    return found !== undefined && statusOf(found) !== 'discarded' ? found : null
  })()

  /**
   * The version the file tab is showing: the path's pin when readable, else the live version, else
   * the newest readable version that is neither discarded nor revoked.
   *
   * **A pinned discarded version does not fall back.** Falling back to the live version drew the live
   * files under a discarded address, so the picker said one version and the files said another; v9
   * leaves the file area empty instead, and the head still names the pinned row (TD §接口 8).
   *
   * **The picker and the commit row read this same snapshot**, so the selector can never say one
   * version while the files below belong to another.
   */
  const shownVersion: AuthoredVersion | null = (() => {
    if (pinnedFileVersion !== null) {
      return pinnedFileVersion
    }
    if (pinnedIsDiscarded) {
      return null
    }
    if (liveVersion !== null) {
      return liveVersion
    }
    return (
      visibleVersions
        .filter(
          (candidate) =>
            statusOf(candidate) !== 'discarded' && statusOf(candidate) !== 'revoked',
        )
        .sort((a, b) => b.submitted_at.localeCompare(a.submitted_at))[0] ?? null
    )
  })()

  /**
   * The row the file tab's head speaks for: the version on screen, or the pinned discarded row when
   * there are no files to draw. The picker and the commit row both read it, so an empty file area
   * still says which version it is empty of.
   */
  const fileTabVersion: AuthoredVersion | null = shownVersion ?? (pinnedIsDiscarded ? pinnedRow : null)

  /**
   * What the file tab's picker offers, in the design's order: live, drafts (`write`+), then the
   * remaining published versions (history, and revoked for `maintain`+). A discarded version has no
   * file snapshot and is never offered; a pinned version this rung cannot read is a disabled row, so
   * the control does not lie about what is on screen.
   */
  const pickerVersions: AuthoredVersion[] = (() => {
    const readable = visibleVersions.filter((candidate) => statusOf(candidate) !== 'discarded')
    const byTime = (a: AuthoredVersion, b: AuthoredVersion): number =>
      b.submitted_at.localeCompare(a.submitted_at)
    const ordered: AuthoredVersion[] = []
    if (liveVersion !== null) {
      ordered.push(liveVersion)
    }
    readable
      .filter((candidate) => statusOf(candidate) === 'draft')
      .sort(byTime)
      .forEach((candidate) => ordered.push(candidate))
    readable
      .filter((candidate) => statusOf(candidate) !== 'draft' && candidate !== liveVersion)
      .sort(byTime)
      .forEach((candidate) => ordered.push(candidate))
    // The address may pin a version this rung cannot read (the server usually 404s first); if the
    // object is here, show it disabled rather than silently switching the screen to another version.
    const pinned = allVersions.find(
      (candidate) => pathVersion !== undefined && answersPin(candidate, pathVersion),
    )
    if (pinned !== undefined && !ordered.includes(pinned)) {
      ordered.push(pinned)
    }
    return ordered
  })()

  const disabledPickerValues = pickerVersions
    .filter((candidate) => statusOf(candidate) === 'discarded' || !readableBy(candidate, rung))
    .map((candidate) => versionSuffix(candidate))

  /** Which version the files on screen belong to, as the link back to that version's own page. */
  const commitVersionHref =
    fileTabVersion === null
      ? baseAddress(null)
      : `${baseAddress(null)}?tab=versions&version=${encodeURIComponent(versionSuffix(fileTabVersion))}`

  const commitVersionLabel = (() => {
    if (fileTabVersion === null) {
      return ''
    }
    const title = versionTitle(fileTabVersion, rung)
    return fileTabVersion.name === null ? title : `${title} @${fileTabVersion.name}`
  })()

  /**
   * 「下载」 in the file table's head (2026-10-21, design §一个 skill).
   *
   * The same archive route and the same condition as the version page's 下载: whoever can read the
   * files on screen can take that whole version. `shownVersion` is what decides — when it is null
   * (a pinned discarded version, or nothing readable at all) there are no files, so there is nothing
   * to download; the pinned row in the header is an identity, not a snapshot.
   */
  const fileDownloadHref =
    shownVersion === null
      ? null
      : `/web/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}@${encodeURIComponent(versionSuffix(shownVersion))}/archive`

  /* The import sheet: one file, a name, a note; success lands on the new draft's version page. */
  const [importOpen, setImportOpen] = useState(false)
  /** The version list's 「新建版本 ▾」 — where focus goes when the sheet it opened closes. */
  const filterBar = useRef<VersionFilterBarHandle | null>(null)
  /** The file table's own 「新建版本」 (2026-10-21): the same menu, in the other tab. */
  const filesMenu = useRef<CommandMenuHandle | null>(null)
  /** Which 「新建版本」 opened the sheet, so focus goes back to that one. */
  const importOrigin = useRef<'filter' | 'files'>('filter')

  /** The file table's 「新建版本」 items: built by the same function as the version list's menu. */
  const filesCreateItems = versionCreateItems(`${baseAddress(null)}/new`, openImportFromFiles)

  function onPickVersion(suffix: string): void {
    go(`${baseAddress(suffix)}?tab=files`)
  }

  /** A sheet emits an address; the page is what navigates (the sheets never touch `location`). */
  function goTo(href: string): void {
    go(href)
  }

  /** The version list's 「新建版本 ▾」: its 导入压缩包 item opens the sheet. */
  function openImportFromFilter(): void {
    importOrigin.current = 'filter'
    setImportOpen(true)
  }

  /** The file table's 「新建版本」: the same menu, opening the same sheet (2026-10-21). */
  function openImportFromFiles(): void {
    importOrigin.current = 'files'
    setImportOpen(true)
  }

  /**
   * Closing the import sheet returns focus to the menu that opened it (design §导入与下载: 「焦点回到
   * 打开它的那颗」). Wait a frame so the sheet is gone before the button takes focus back — and the
   * button is whichever of the two 「新建版本」s it came from.
   */
  function closeImport(): void {
    setImportOpen(false)
    requestAnimationFrame(() => {
      if (importOrigin.current === 'files') {
        filesMenu.current?.focusTrigger()
      } else {
        filterBar.current?.focusCreate()
      }
    })
  }

  const versionSuffixOf = (): string | undefined =>
    shownVersion === null ? undefined : versionSuffix(shownVersion)

  function fileHref(file: string): string {
    const encoded = file.split('/').map(encodeURIComponent).join('/')
    return `${baseAddress(versionSuffixOf())}/files/${encoded}`
  }

  /** A directory's own level, as an address: the listing is one level at a time. */
  function dirHref(dir: string): string {
    const query = new URLSearchParams(params)
    query.set('tab', 'files')
    for (const name of ['p', 'q', 'version', 'sub', 'bump', 'from', 'fq', 'file', 'list']) {
      query.delete(name)
    }
    if (dir === '') {
      query.delete('path')
    } else {
      query.set('path', dir)
    }
    return `${baseAddress(versionSuffixOf())}?${query.toString()}`
  }

  /**
   * The directory being listed.
   *
   * A file's path already carries the directory it lives in, so the listing follows the file rather
   * than being told where to look twice. `?path=` names a directory when no file is open, and a path
   * the manifest does not have falls back to the root rather than to a blank page.
   */
  const currentDir: string = (() => {
    if (relpath !== undefined) {
      const slash = relpath.lastIndexOf('/')
      return slash < 0 ? '' : relpath.slice(0, slash)
    }
    // `?path=references/` is the same directory as `references`; the slash is dropped before the
    // segment is used, or every relpath would be matched against `references//`.
    const asked = (params.get('path') ?? '').replace(/\/+$/, '')
    if (asked === '') {
      return ''
    }
    return skill?.files.some((file) => file.relpath.startsWith(`${asked}/`)) === true ? asked : ''
  })()

  /**
   * One level of the tree: the folders and files whose parent is {@link currentDir}.
   *
   * **One level at a time** (design): a folder goes into its own listing, a file opens below. Folders
   * come first and files after, each sorted by name — the shape a repository's file table has.
   */
  const levelEntries: Entry[] = (() => {
    // **No version on screen, no files.** `skill.files` is the selected version's manifest, and a
    // discarded version is still selected server-side — drawing its rows here would be the same lie
    // the fallback to `liveVersion` told (TD §接口 8).
    if (shownVersion === null) {
      return []
    }
    const dir = currentDir
    const prefix = dir === '' ? '' : `${dir}/`
    const folders = new Map<string, Entry>()
    const files: Entry[] = []
    for (const file of skill?.files ?? []) {
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
        files.push({
          kind: 'file',
          name: rest,
          relpath: file.relpath,
          size: file.size,
          isBinary: file.is_binary,
        })
      }
    }
    const byName = (a: Entry, b: Entry): number => a.name.localeCompare(b.name)
    return [...folders.values()].sort(byName).concat(files.sort(byName))
  })()

  /* One file of the shown version. The root's SKILL.md is what the listing opens by default. */
  const [fileContent, setFileContent] = useState<string | null>(null)
  const [readingFile, setReadingFile] = useState(false)
  const [fileProblem, setFileProblem] = useState<string | null>(null)

  /** The relpath being read: the address's, or the root body when the address names no file. */
  const openRelpath: string | undefined = (() => {
    if (relpath !== undefined) {
      return relpath
    }
    if (currentDir === '') {
      return 'SKILL.md'
    }
    return undefined
  })()

  const openFile: { relpath: string; isBinary: boolean } | null = (() => {
    // A file opens under a version, and a version that is not on screen opens none (TD §接口 8).
    if (openRelpath === undefined || skill === null || shownVersion === null) {
      return null
    }
    const entry = skill.files.find((file) => file.relpath === openRelpath)
    return entry === undefined ? null : { relpath: entry.relpath, isBinary: entry.is_binary }
  })()

  const openIsMarkdown = /\.(md|markdown)$/i.test(openRelpath ?? '')

  /** The path inside the skill, segment by segment, each one back to its own level. */
  const crumbs: Array<{ name: string; href: string }> = (() => {
    if (currentDir === '') {
      return []
    }
    const parts = currentDir.split('/')
    return parts.map((segment, index) => ({
      name: segment,
      href: dirHref(parts.slice(0, index + 1).join('/')),
    }))
  })()

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

  /* ------------------------------------------------------------------ the versions section */

  const [filter, setFilterState] = useState<VersionFilterKey>(versionRoute.filter)
  const [query, setQueryState] = useState(versionRoute.q)

  /**
   * The list's address. Filter and search are written with `replaceState`, not by navigating: the
   * list is already here, and a round trip per keystroke is what makes a search box feel like a form.
   * Both survive refresh, a copied link and the way back from a version page.
   */
  function writeListAddress(nextFilter: VersionFilterKey, nextQuery: string): void {
    const next = pageParams()
    next.set('tab', 'versions')
    for (const name of ['version', 'sub', 'bump', 'from', 'to', 'fq', 'file', 'list']) {
      next.delete(name)
    }
    if (nextFilter === 'all') {
      next.delete('filter')
    } else {
      next.set('filter', nextFilter)
    }
    if (nextQuery.trim() === '') {
      next.delete('q')
    } else {
      next.set('q', nextQuery)
    }
    replaceAddress(relativeAddress(next))
  }

  function setFilter(value: VersionFilterKey): void {
    setFilterState(value)
    writeListAddress(value, query)
  }

  function setQuery(value: string): void {
    setQueryState(value)
    writeListAddress(filter, value)
  }

  function clearFilters(): void {
    setFilterState('all')
    setQueryState('')
    writeListAddress('all', '')
  }

  /** The list state the rows carry into their own page, and back. */
  const listQueryString = (() => {
    const next = new URLSearchParams()
    if (filter !== 'all') {
      next.set('filter', filter)
    }
    if (query.trim() !== '') {
      next.set('q', query)
    }
    return next.toString()
  })()

  /**
   * The rows on screen: status filter first, then search. What the search reads is what the row shows
   * — the name, number, note and submitter on the author plane; only the number and note for a
   * caller, whose row never carries the other two.
   */
  const matchedVersions = visibleVersions.filter((candidate) => {
    if (filter !== 'all' && filterKeyOf(statusOf(candidate)) !== filter) {
      return false
    }
    const needle = query.trim().toLowerCase()
    if (needle === '') {
      return true
    }
    const words =
      rung === 'invoke'
        ? [candidate.name ?? '', candidate.note ?? '']
        : [
            versionTitle(candidate, rung),
            candidate.name ?? '',
            candidate.note ?? '',
            candidate.submitted_by ?? '',
          ]
    return words.join(' ').toLowerCase().includes(needle)
  })

  /** Whether a version has a baseline at all — what draws its 「查看改动」 menu item. */
  function hasBaselineFor(candidate: AuthoredVersion): boolean {
    return defaultBaseline(candidate, visibleVersions, liveVersion, rung) !== null
  }

  const versionCount = countedVersions.length

  const backToListHref = (() => {
    const next = pageParams()
    for (const name of ['version', 'sub', 'bump', 'from', 'to', 'fq', 'file', 'list']) {
      next.delete(name)
    }
    next.set('tab', 'versions')
    return addressFor(baseAddress(null), next)
  })()

  /** A new comparison baseline is a new screen; the address is the state, so it is a real navigation. */
  function selectBaseline(pin: string): void {
    const next = pageParams()
    next.set('tab', 'versions')
    next.set('sub', 'diff')
    next.set('from', pin)
    // The legacy `/diff` alias's `to=` names the target the old address carried; the canonical form
    // carries it in `version`, so the two are not both kept.
    next.delete('to')
    if (detailRoute.version !== undefined) {
      next.set('version', detailRoute.version)
    }
    go(addressFor(baseAddress(null), next))
  }

  /* ----------------------------------------------------------------- settings · general */

  const visibilityChoice = (() => {
    if (skill === null || skill.visibility === 'private') {
      return 'private'
    }
    return skill.public_rung === 'browse' ? 'public-browse' : 'public-invoke'
  })()

  const visibilityPane = useRef<HTMLElement | null>(null)

  /** The radio for one choice, for the moments the DOM has to be put back by hand. */
  function visibilityRadio(value: string): HTMLInputElement | null {
    return (
      visibilityPane.current?.querySelector<HTMLInputElement>(
        `input[name="skill-visibility"][value="${value}"]`,
      ) ?? null
    )
  }

  async function changeVisibility(choice: string): Promise<void> {
    if (choice === 'public-browse') {
      // **The choice does not move before the answer.** The browser has already checked the radio;
      // putting it back is what makes 取消 mean "nothing happened" — a controlled `checked` whose
      // value did not change is one React will not patch back on its own either (design's
      // `requestPublicBrowse`).
      const current = visibilityRadio(visibilityChoice)
      if (current !== null) {
        current.checked = true
      }
      setPublicBrowseOpen(true)
      return
    }
    const visibility = choice === 'private' ? 'private' : 'public'
    const publicRung: 'invoke' | 'browse' | null =
      choice === 'private' ? null : choice === 'public-invoke' ? 'invoke' : 'browse'
    const result = await setVisibility(namespace, name, visibility, publicRung)
    if (!result.ok) {
      detail.setBanner(codeMessage(result.code, result.message))
      return
    }
    window.location.reload()
  }

  /* 公开 · 浏览 的确认. */
  const [publicBrowseOpen, setPublicBrowseOpen] = useState(false)
  const [publicBrowsePending, setPublicBrowsePending] = useState(false)
  const [publicBrowseError, setPublicBrowseError] = useState<string | null>(null)

  async function acceptPublicBrowse(): Promise<void> {
    setPublicBrowsePending(true)
    setPublicBrowseError(null)
    const result = await setVisibility(namespace, name, 'public', 'browse')
    setPublicBrowsePending(false)
    if (!result.ok) {
      setPublicBrowseError(codeMessage(result.code, result.message))
      return
    }
    window.location.reload()
  }

  /** 取消 and Esc are the same answer, and it leaves the focus where the question came from. */
  function cancelPublicBrowse(): void {
    setPublicBrowseOpen(false)
    setPublicBrowseError(null)
    requestAnimationFrame(() => visibilityRadio(visibilityChoice)?.focus())
  }

  /* 设置 › 危险操作. */

  const offline = skill != null && skill.offline_at != null

  const [offlineOpen, setOfflineOpen] = useState(false)
  const [offlinePending, setOfflinePending] = useState(false)
  const [offlineError, setOfflineError] = useState<string | null>(null)
  const offlineButton = useRef<HTMLButtonElement | null>(null)

  function askOffline(): void {
    setOfflineError(null)
    setOfflineOpen(true)
  }

  async function confirmOffline(): Promise<void> {
    setOfflinePending(true)
    setOfflineError(null)
    const result = await setSkillOffline(namespace, name, !offline)
    setOfflinePending(false)
    if (!result.ok) {
      setOfflineError(codeMessage(result.code, result.message))
      return
    }
    window.location.reload()
  }

  function cancelOffline(): void {
    setOfflineOpen(false)
    setOfflineError(null)
    requestAnimationFrame(() => offlineButton.current?.focus())
  }

  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deletePending, setDeletePending] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const deleteButton = useRef<HTMLButtonElement | null>(null)

  function askDelete(): void {
    setDeleteError(null)
    setDeleteOpen(true)
  }

  async function confirmDelete(): Promise<void> {
    setDeletePending(true)
    setDeleteError(null)
    const result = await deleteSkill(namespace, name)
    setDeletePending(false)
    if (!result.ok) {
      setDeleteError(codeMessage(result.code, result.message))
      return
    }
    go('/?show=deleted')
  }

  function cancelDelete(): void {
    setDeleteOpen(false)
    setDeleteError(null)
    requestAnimationFrame(() => deleteButton.current?.focus())
  }

  /* The details sub-page: the fields SKILL.md declares, for the author to check. */
  const metadataRest = (() => {
    const metadata = frontmatter.metadata
    if (typeof metadata !== 'object' || metadata === null) {
      return ''
    }
    return Object.entries(metadata as Record<string, unknown>)
      .filter(([key]) => key !== 'when_to_use')
      .map(([key, value]) => `${key}: ${String(value)}`)
      .join('\n')
  })()

  const detailRows = (() => {
    const version = liveVersion ?? selected
    return [
      { label: '名字', value: skill?.name ?? '', mono: true },
      {
        label: '版本',
        value:
          version === null
            ? ''
            : `@${versionLabel(versionSuffix(version))} · ${version.digest}`,
        mono: true,
      },
      {
        label: '许可',
        value: typeof frontmatter.license === 'string' ? (frontmatter.license as string) : '',
        mono: true,
      },
      {
        label: 'allowed-tools',
        value:
          typeof frontmatter['allowed-tools'] === 'string'
            ? (frontmatter['allowed-tools'] as string)
            : '',
        mono: true,
      },
      { label: 'metadata', value: metadataRest, mono: true },
    ].filter((row) => row.value !== '')
  })()

  /* The second-level menu's count: "somebody is waiting" has exactly two homes without notifications. */
  const [requestCount, setRequestCount] = useState(0)

  useEffect(() => {
    // The file tab reads the snapshot its path pins; the version tab reads the whole list so a bad
    // pin can be answered with 「没有这一版」 rather than 「没有这个 skill」. The legacy `/diff` alias
    // still pins its target (`?to=` or the path's `@`), because its comparison is about that version.
    const loadPin =
      tab === 'versions' ? (legacyDiff ? (to ?? pathVersion) : undefined) : pathVersion
    void detail.load(namespace, name, loadPin)
    // The address is fixed for the life of the page: every other navigation is a full load.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /**
    * The file's bytes are read once the skill that names them is here.
    *
    * **Not from the mount effect.** `loadFile` decides from `openFile` and `shownVersion`, and at
    * mount the skill is still null — the effect would close over that first render's nulls and read
    * nothing. Vue read the same values through its refs and never met the problem.
    */
  useEffect(() => {
    if (state !== 'ready') {
      return
    }
    let cancelled = false
    void (async () => {
      await loadFile()
      if (cancelled) {
        return
      }
      // The 协作者 sub-page hands its own queue size up instead — one read, two views.
      if (skill !== null && atLeast(skill.rung, 'admin') && !(tab === 'settings' && sub === 'collaborators')) {
        const result = await listAccessRequests(namespace, name)
        if (!cancelled && result.ok) {
          setRequestCount(result.data.total)
        }
      }
    })()
    return () => {
      cancelled = true
    }
    // `state` is the trigger; the rest are the values Vue read through refs at the same moment.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state])

  return (
    <section className="page">
      <FormBanner message={detail.banner} />

      {state === 'missing' ? (
        <div className="notfound">
          <h2>没有这个 skill</h2>
          {/* **Same screen, several reasons.** The address may name nothing, name a skill that was
              deleted, or name one this person cannot reach. The 404 is one answer on purpose, so the
              sentence says only what is true of all of them. */}
          <p className="hint">这个地址下没有你能打开的 skill，也可能它已经被删除了。</p>
          <p>
            <a href="/">回到我的 skill</a>　·　<a href="/?show=deleted">查看已删除的 skill</a>
          </p>
        </div>
      ) : state === 'loading' ? (
        <p className="loading">加载中…</p>
      ) : skill !== null ? (
        <>
          <header className="skill-head">
            {/* Who, and what: the same three things a repository's first line carries — the account
                mark, the name, and whether anybody else can read it. */}
            <div className="skill-ident">
              <span className="avatar" aria-hidden="true">
                {skill.namespace.slug.slice(0, 1)}
              </span>
              <div className="skill-who">
                <div className="skill-name-row">
                  <h1>
                    <span className="ns">{`${skill.namespace.slug} /`}</span>
                    <a className="name" href={baseAddress(null)}>
                      {skill.name}
                    </a>
                    {crumbs.map((segment) => (
                      <span key={segment.href} className="crumb">
                        {' / '}
                        <a href={segment.href}>{segment.name}</a>
                      </span>
                    ))}
                  </h1>
                  {/* **Drawn only where it can be acted on.** The badge leads to the page that owns
                      the fact, and that page is `admin`'s — so for every rung below it the whole mark
                      is absent, not greyed (design: 够不到的东西不画). */}
                  {canAdmin ? (
                    <a className="chip is-plain" href={subHref('general')}>
                      {skill.visibility === 'private' ? '私有' : '公开'}
                    </a>
                  ) : null}
                  {/* **下线是整个 skill 的状态，不是某一版的。** It sits beside the visibility mark
                      because both answer 「这个地址现在是什么」; it is not a version mark. */}
                  {skill.offline_at != null ? <span className="chip is-offline">已下线</span> : null}
                </div>
                {/* `title` is an optional field: the server fills it with `name` when the author wrote
                    none, so "is there a title" is `title !== name` rather than "is it empty". */}
                {skill.title !== skill.name ? <p className="skill-title">{skill.title}</p> : null}
              </div>
            </div>

            <nav className="skill-tabs" aria-label="这个 skill 的分区">
              {tabs.map((entry) => (
                <a
                  key={entry.key}
                  className={tab === entry.key ? 'skill-tab is-current' : 'skill-tab'}
                  aria-current={tab === entry.key ? 'page' : undefined}
                  href={tabHref(entry.key)}
                >
                  <svg className="skill-tab-icon" aria-hidden="true">
                    <use href={`#${TAB_ICONS[entry.key]}`} />
                  </svg>
                  <span className="skill-tab-label">{entry.label}</span>
                  {entry.key === 'versions' && versionCount > 0 ? (
                    <span className="skill-tab-count">{versionCount}</span>
                  ) : null}
                </a>
              ))}
            </nav>
          </header>

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
            <symbol id="descMark" viewBox="0 0 24 24" fill="none">
              <path
                d="M5 6h14M5 10.5h14M5 15h9"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
              />
            </symbol>
            <symbol id="clockMark" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.8" />
              <path
                d="M12 7.4V12l3.2 1.9"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </symbol>
            <symbol id="versionMark" viewBox="0 0 24 24" fill="none">
              <path
                d="M20.1 12.7l-7.4 7.4a1.8 1.8 0 0 1-2.5 0L4 14.1V4h10.1l6 6a1.8 1.8 0 0 1 .0 2.7z"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinejoin="round"
              />
              <circle cx="8.4" cy="8.4" r="1.25" fill="currentColor" />
            </symbol>
            <symbol id="envMark" viewBox="0 0 24 24" fill="none">
              <path
                d="M4.8 7.2l4.6 4.8-4.6 4.8"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
              <path d="M12.4 16.8h6.8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </symbol>
            <symbol id="infoMark" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.8" />
              <path
                d="M12 11v5M12 8.1v.2"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
              />
            </symbol>
            <symbol id="settingsMark" viewBox="0 0 24 24" fill="none">
              <path
                d="M4 7h9M18 7h2M4 12h4M13 12h7M4 17h9M18 17h2"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
              />
              <circle cx="15.5" cy="7" r="2" stroke="currentColor" strokeWidth="1.8" />
              <circle cx="10.5" cy="12" r="2" stroke="currentColor" strokeWidth="1.8" />
              <circle cx="15.5" cy="17" r="2" stroke="currentColor" strokeWidth="1.8" />
            </symbol>
            <symbol id="folderMark" viewBox="0 0 24 24" fill="none">
              <path
                d="M3.5 18.5V6.5a1 1 0 0 1 1-1h4.2l2 2.5h8.8a1 1 0 0 1 1 1v9.5a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1z"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinejoin="round"
              />
            </symbol>
          </svg>

          {/* 信息 = the `/metadata` layer. 描述 first as prose, then 适用场景 lifted out (it is the one
              sentence that says when to remember this skill), then 运行环境 — the three things a reader
              weighs together. The version is a value, so it sits in the rail. */}
          {tab === 'info' ? (
            <div className="tab-panel" data-tab="info">
              <section className="info-pane">
                <div className="info-main">
                  <section className="info-block">
                    <span className="info-label">
                      <svg className="info-icon" aria-hidden="true">
                        <use href="#descMark" />
                      </svg>
                      描述
                    </span>
                    <p className="info-text">{skill.description}</p>
                  </section>
                  {whenToUse !== '' ? (
                    <section className="info-block">
                      <span className="info-label">
                        <svg className="info-icon" aria-hidden="true">
                          <use href="#clockMark" />
                        </svg>
                        适用场景
                      </span>
                      <p className="info-text">{whenToUse}</p>
                    </section>
                  ) : null}
                  {compatibility !== '' ? (
                    <section className="info-block">
                      <span className="info-label">
                        <svg className="info-icon" aria-hidden="true">
                          <use href="#envMark" />
                        </svg>
                        运行环境
                      </span>
                      <p className="info-text">{compatibility}</p>
                    </section>
                  ) : null}
                </div>

                <aside className="info-rail">
                  <section className="info-rail-item">
                    <svg className="info-rail-icon" aria-hidden="true">
                      <use href="#versionMark" />
                    </svg>
                    <span className="info-rail-label">版本</span>
                    <span className="info-rail-value">{liveLabel}</span>
                  </section>
                  {/* 标签是可选的扩展属性，这一版不显示；位置留着，设计件里它也在。 */}
                  <div className="info-tags" hidden />
                </aside>
              </section>

              {/* 申请: the page's only action, drawn while the ladder still has a rung above this one. */}
              {showAsk ? (
                <section className="ask">
                  <h3>申请</h3>
                  <p className="hint">
                    {askWhy}
                    {pendingRung === null ? '如需更高的档位，请在下方申请。' : null}
                  </p>
                  {pendingRung === null ? (
                    <>
                      <div className="ask-form">
                        <span id="askRoleLabel" className="ask-label">
                          申请档位
                        </span>
                        <RungMenu
                          options={above}
                          value={askedRung}
                          label="申请档位"
                          labelledby="askRoleLabel"
                          // The menu's options are `above`, which is a list of rungs — so the value it
                          // reports is one of them.
                          onChange={(value) => setAskedRung(value as Rung)}
                        />
                        <button
                          type="button"
                          className="button"
                          onClick={() => {
                            void submitAsk()
                          }}
                        >
                          {askLabel}
                        </button>
                      </div>
                      <p className="ask-note">{askNote}</p>
                    </>
                  ) : (
                    <div className="ask-pending">
                      <span className="chip is-plain">
                        <span className="dot" />
                        <span className="chip-text">等待处理</span>
                      </span>
                      <span className="ask-pending-text">
                        已提交「{rungLabel(pendingRung)}」的申请，由 {skill.namespace.slug}{' '}
                        或「管理」审批。
                      </span>
                      <button
                        type="button"
                        className="link-button"
                        onClick={() => {
                          void withdrawAsk()
                        }}
                      >
                        撤回申请
                      </button>
                    </div>
                  )}
                </section>
              ) : null}
            </div>
          ) : tab === 'files' ? (
            <div className="tab-panel" data-tab="files">
              <section>
                <h2 className="visually-hidden">{`文件（${levelEntries.length}）`}</h2>
                <div className="file-table">
                  {/* The version row belongs to this table, not to the page: it chooses which snapshot
                      these files are, a question that only means something while the files are on
                      screen. */}
                  <div className="skill-version-row">
                    <label className="version-picker">
                      <span className="visually-hidden">选择版本</span>
                      <VersionPicker
                        versions={pickerVersions}
                        selected={fileTabVersion === null ? '' : versionSuffix(fileTabVersion)}
                        disabledValues={disabledPickerValues}
                        onSelect={onPickVersion}
                      />
                    </label>
                    <span className="skill-version-count">{`${countedVersions.length} 个版本`}</span>
                    <div className="skill-version-tail">
                      <span className="skill-address">
                        <span className="visually-hidden">地址</span>
                        {`${skill.namespace.slug}/${skill.name}`}
                      </span>
                      {/* **The row's right end: 下载 then 新建版本 (2026-10-21, design §一个 skill).**
                          GitHub puts its file actions there; the download is the same archive route and
                          the same condition as the version page's, and 新建版本 is the same menu as the
                          version list's — one way to produce a version, wherever it is opened from. */}
                      <div className="skill-version-actions">
                        {fileDownloadHref !== null ? (
                          <a className="button is-quiet" href={fileDownloadHref}>
                            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                              <path
                                d="M12 4v10M8 10l4 4 4-4"
                                stroke="currentColor"
                                strokeWidth="1.8"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                              />
                              <path
                                d="M5 19h14"
                                stroke="currentColor"
                                strokeWidth="1.8"
                                strokeLinecap="round"
                              />
                            </svg>
                            下载
                          </a>
                        ) : null}
                        {canWrite ? (
                          <CommandMenu
                            controlRef={filesMenu}
                            groups={[filesCreateItems]}
                            menuLabel="新建版本"
                            resize="reposition"
                          >
                            {({ open, toggle }) => (
                              <button
                                type="button"
                                className="button menu-button is-compact"
                                aria-haspopup="menu"
                                aria-expanded={open ? 'true' : 'false'}
                                onClick={toggle}
                              >
                                新建版本
                              </button>
                            )}
                          </CommandMenu>
                        ) : null}
                      </div>
                    </div>
                  </div>

                  {/* The row is always drawn: with nothing readable it says 「未上线」 (design's own
                      sentence), so the file column does not lose its head. */}
                  <div className="file-commit">
                    <span className="avatar" aria-hidden="true">
                      {fileTabVersion === null ? '' : (fileTabVersion.submitted_by ?? '?').slice(0, 1)}
                    </span>
                    <span className="commit-who">
                      {fileTabVersion === null ? '' : (fileTabVersion.submitted_by ?? '')}
                    </span>
                    <span className="commit-msg">
                      {fileTabVersion === null ? '未上线' : (fileTabVersion.note ?? '')}
                    </span>
                    {/* **The right end is this version's identity, so it is the way back to it.** The
                        mirror of the version page's 「查看文件」; the time stays plain text. */}
                    {fileTabVersion !== null ? (
                      <span className="commit-meta">
                        <a className="commit-version" href={commitVersionHref}>
                          {commitVersionLabel}
                        </a>
                        {fileTabVersion.name !== null ? (
                          <>{`· ${formatTime(fileTabVersion.first_published_at ?? fileTabVersion.submitted_at)}`}</>
                        ) : null}
                      </span>
                    ) : null}
                  </div>

                  {/* **One level at a time.** A folder goes into its own listing; a file opens below.
                      The middle column — "the version that last changed this file, and its date" — is
                      left empty: the product has no per-file history to say it with, and the design
                      records that fact as the demo's own fixture (design/README §已知的取舍). */}
                  <ul className="files">
                    {levelEntries.map((entry) => (
                      <li key={entry.relpath}>
                        <svg className="file-icon" aria-hidden="true">
                          <use href={entry.kind === 'dir' ? '#folderMark' : '#fileMark'} />
                        </svg>
                        <a
                          className={
                            entry.kind === 'file' && entry.relpath === openRelpath
                              ? 'file-name is-current'
                              : 'file-name'
                          }
                          href={entry.kind === 'dir' ? dirHref(entry.relpath) : fileHref(entry.relpath)}
                        >
                          {entry.kind === 'dir' ? `${entry.name}/` : entry.name}
                        </a>
                        <span className="file-changed" />
                        {entry.kind === 'file' ? (
                          <span className="size">{formatSize(entry.size)}</span>
                        ) : (
                          <span className="size" />
                        )}
                      </li>
                    ))}
                  </ul>
                </div>
              </section>

              {/* The file being read, under the listing the way a repository renders its README under
                  its files — and in the same kind of box, with the file's own name where the version
                  was. The root opens `SKILL.md` by default; a folder opens nothing until a file is
                  chosen. */}
              {openFile !== null ? (
                <section className="block file-open">
                  <h2 className="visually-hidden">文件内容</h2>
                  <div className="file-table">
                    <div className="file-tab">{openFile.relpath}</div>
                    <div className="file-body">
                      {openFile.isBinary ? (
                        <p className="hint">
                          这是二进制文件，不在这里展示。本机用 <code>skillmaster skill get</code> 取它。
                        </p>
                      ) : readingFile ? (
                        <p className="loading">加载中…</p>
                      ) : fileProblem !== null ? (
                        <p className="banner">{fileProblem}</p>
                      ) : fileContent !== null && openIsMarkdown ? (
                        <MarkdownView source={fileContent} />
                      ) : fileContent !== null ? (
                        <pre className="file-source">{fileContent}</pre>
                      ) : (
                        <p className="hint">这个版本里没有 {openFile.relpath}。</p>
                      )}
                    </div>
                  </div>
                </section>
              ) : null}
            </div>
          ) : tab === 'versions' ? (
            <div className="tab-panel" data-tab="versions">
              {/* **One route, two states.** A version pin in the query switches the tab from the list
                  to that version's own page; without one the list is what the tab is. A pin naming
                  nothing this rung can see gets one answer, with a way back and no leak. */}
              {detailRoute.version !== undefined && detailVersion !== null ? (
                <VersionDetail
                  namespace={namespace}
                  name={name}
                  version={detailVersion}
                  versions={visibleVersions}
                  rung={rung}
                  username={account?.username ?? null}
                  live={liveVersion}
                  route={detailRoute}
                  onSelectBaseline={selectBaseline}
                />
              ) : detailRoute.version !== undefined ? (
                <div id="versionMissing">
                  <div className="notfound">
                    <h2>没有这一版</h2>
                    <p className="hint">
                      这个地址下没有你能打开的版本：它可能已经被丢弃，也可能属于一个你看不到的 skill。
                    </p>
                    <p>
                      <a href={backToListHref}>回到版本列表</a>
                    </p>
                  </div>
                </div>
              ) : (
                <div id="versionListWrap">
                  <VersionFilterBar
                    controlRef={filterBar}
                    versions={visibleVersions}
                    filter={filter}
                    query={query}
                    rung={rung}
                    newHref={`${baseAddress(null)}/new`}
                    onUpdateFilter={setFilter}
                    onUpdateQuery={setQuery}
                    onOpenImport={openImportFromFilter}
                  />
                  <div className="file-table version-list-table">
                    <ul className="versions">
                      {matchedVersions.map((candidate) => (
                        <VersionRow
                          key={versionSuffix(candidate)}
                          version={candidate}
                          namespace={namespace}
                          name={name}
                          rung={rung}
                          username={account?.username ?? null}
                          listQuery={listQueryString}
                          hasBaseline={hasBaselineFor(candidate)}
                        />
                      ))}
                    </ul>
                    {matchedVersions.length === 0 ? (
                      <p className="version-empty">
                        没有匹配的版本。
                        <button type="button" className="link-button" onClick={clearFilters}>
                          清除筛选
                        </button>
                      </p>
                    ) : null}
                  </div>
                </div>
              )}
            </div>
          ) : tab === 'settings' ? (
            <div className="tab-panel" data-tab="settings">
              <div className="settings">
                {/* One column of subjects, the one being set beside it. Nothing separates the two but
                    the indent: a rule there would fence off a list of three. */}
                <nav className="settings-nav" aria-label="设置的分区">
                  <a
                    className={sub === 'general' ? 'settings-item is-current' : 'settings-item'}
                    aria-current={sub === 'general' ? 'page' : undefined}
                    href={subHref('general')}
                  >
                    常规
                  </a>
                  <a
                    className={sub === 'collaborators' ? 'settings-item is-current' : 'settings-item'}
                    aria-current={sub === 'collaborators' ? 'page' : undefined}
                    href={subHref('collaborators')}
                  >
                    协作者
                    {requestCount > 0 ? <span className="count">{requestCount}</span> : null}
                  </a>
                  <a
                    className={sub === 'details' ? 'settings-item is-current' : 'settings-item'}
                    aria-current={sub === 'details' ? 'page' : undefined}
                    href={subHref('details')}
                  >
                    详细信息
                  </a>
                </nav>

                <div className="settings-body">
                  {sub === 'general' ? (
                    <section ref={visibilityPane} className="settings-pane" data-sub="general">
                      <h2>常规</h2>

                      <section className="settings-block">
                        <h3>可见性</h3>
                        <p className="hint">
                          公开的 skill 可以被搜索到；私有的 skill 仅你和协作者可以访问，其他访问者无法得知它的存在。
                        </p>
                        <div className="choices">
                          <label className="choice">
                            <input
                              type="radio"
                              name="skill-visibility"
                              value="private"
                              checked={visibilityChoice === 'private'}
                              onChange={() => {
                                void changeVisibility('private')
                              }}
                            />
                            <span className="choice-body">
                              <span className="choice-title">私有</span>
                              <span className="choice-note">仅你和被授权的协作者可以访问。</span>
                            </span>
                          </label>
                          <label className="choice">
                            <input
                              type="radio"
                              name="skill-visibility"
                              value="public-invoke"
                              checked={visibilityChoice === 'public-invoke'}
                              onChange={() => {
                                void changeVisibility('public-invoke')
                              }}
                            />
                            <span className="choice-body">
                              <span className="choice-title">公开 · 调用</span>
                              <span className="choice-note">
                                任何人都可以调用，也可以按路径读取文件；但无法查看文件清单，只有正文中提到的文件可以按路径获取。
                              </span>
                            </span>
                          </label>
                          <label className="choice">
                            <input
                              type="radio"
                              name="skill-visibility"
                              value="public-browse"
                              checked={visibilityChoice === 'public-browse'}
                              onChange={() => {
                                void changeVisibility('public-browse')
                              }}
                            />
                            <span className="choice-body">
                              <span className="choice-title">公开 · 浏览</span>
                              <span className="choice-note">
                                任何人都可以调用、查看全部文件并下载；这个 skill 也可以被复制。
                              </span>
                              <span className="choice-warn">已经发生的下载与复制无法收回。</span>
                            </span>
                          </label>
                        </div>
                      </section>

                      {/* 危险区域: it stays at the bottom of this page rather than in the navigation —
                          an action that takes the skill away from every reader is not one of three
                          equal options. Only the owner reaches it: the service switch and deletion are
                          off the ladder (ADR 0047, ADR 0050). */}
                      {rung === 'owner' ? (
                        <section className="settings-block is-danger">
                          <h3>危险操作</h3>
                          <div className="danger-box">
                            {/* **下线是整个 skill 的服务开关，不是某一版的状态。** Versions and the
                                pointer do not move; restoring needs no re-publish. Reversible, so it
                                comes before deletion. */}
                            <div className="danger-row">
                              <div className="danger-what">
                                <p className="danger-title">{offline ? '恢复服务' : '下线这个 skill'}</p>
                                {!offline ? (
                                  <p className="hint">
                                    下线后，所有读者按这个 skill 的地址调用都会得到「服务已下线」；它也不再出现在搜索与公开列表里。版本状态与当前版本都不变，恢复后不需要重新上线。
                                  </p>
                                ) : (
                                  <p className="hint">
                                    这个 skill 已下线：调用者得到「服务已下线」。恢复后，当前线上版本直接重新对外。版本状态与当前版本都不变。
                                  </p>
                                )}
                              </div>
                              <div className="danger-acts">
                                <button
                                  ref={offlineButton}
                                  type="button"
                                  className={offline ? 'button' : 'button is-danger'}
                                  onClick={askOffline}
                                >
                                  {offline ? '恢复服务' : '下线'}
                                </button>
                              </div>
                            </div>

                            <div className="danger-row">
                              {/* 后果写在按钮旁边，不是确认框里才第一次出现：确认框只重复最要紧的那一条。 */}
                              <div className="danger-what">
                                <p className="danger-title">删除这个 skill</p>
                                <p className="hint">
                                  删除后，这个 skill 对所有访问者不可见，当前地址立即失效。{' '}
                                  <strong>名称不会被释放</strong>——要用同一个名称创建新 skill，必须先恢复这个 skill。
                                </p>
                                <p className="hint">已删除的 skill 会保留在「我的 skill」的「已删除」中，可以恢复。</p>
                              </div>
                              <div className="danger-acts">
                                <button
                                  ref={deleteButton}
                                  type="button"
                                  className="button is-danger"
                                  onClick={askDelete}
                                >
                                  删除
                                </button>
                              </div>
                            </div>
                          </div>
                        </section>
                      ) : null}
                    </section>
                  ) : sub === 'collaborators' ? (
                    <SkillCollaborators
                      namespace={skill.namespace.slug}
                      name={skill.name}
                      publicRung={skill.public_rung}
                      onCount={setRequestCount}
                    />
                  ) : (
                    <section className="settings-pane" data-sub="details">
                      <h2>详细信息</h2>
                      <p className="hint">
                        这些是 SKILL.md 中声明的字段。调用者在「信息」页只能看到描述、适用场景、运行环境与版本。
                      </p>
                      <dl className="info-rows">
                        {detailRows.map((row) => (
                          <div key={row.label}>
                            <dt>{row.label}</dt>
                            <dd className={row.mono ? 'is-mono' : undefined}>{row.value}</dd>
                          </div>
                        ))}
                      </dl>
                    </section>
                  )}
                </div>
              </div>
            </div>
          ) : null}
        </>
      ) : null}

      {/* 公开 · 浏览 的确认: the selector does not change before this is answered, so cancelling
          leaves the page exactly as it was. */}
      <ConfirmDialog
        open={publicBrowseOpen}
        title="改为「公开 · 浏览」"
        body="公开之后，任何人都可以查看全部文件、下载并复制这个 skill；已经发生的下载与复制无法收回。"
        confirmLabel="确认公开"
        pendingLabel="正在公开…"
        danger={false}
        pending={publicBrowsePending}
        error={publicBrowseError}
        onConfirm={acceptPublicBrowse}
        onCancel={cancelPublicBrowse}
      />

      {/* 下线 / 恢复服务，与删除：同一个确认壳，取消是默认焦点，Esc 等于取消。 */}
      <ConfirmDialog
        open={offlineOpen}
        title={offline ? '恢复服务' : '下线这个 skill'}
        body={
          offline
            ? '恢复后，当前线上版本直接重新对外，不需要重新上线。'
            : '下线后，所有调用都会失败；版本状态与当前版本都不变。'
        }
        confirmLabel={offline ? '确认恢复服务' : '确认下线'}
        pendingLabel={offline ? '正在恢复服务…' : '正在下线…'}
        pending={offlinePending}
        error={offlineError}
        onConfirm={confirmOffline}
        onCancel={cancelOffline}
      />

      <ConfirmDialog
        open={deleteOpen}
        title="删除这个 skill？"
        body="删除后，该名称将无法用于创建新 skill，当前地址立即失效。"
        confirmLabel="确认删除"
        pendingLabel="正在删除…"
        pending={deletePending}
        error={deleteError}
        onConfirm={confirmDelete}
        onCancel={cancelDelete}
      />

      <ImportDialog
        open={importOpen}
        mode="version"
        namespace={namespace}
        name={name}
        rung={skill?.rung ?? 'browse'}
        onClose={closeImport}
        onNavigate={goTo}
      />
    </section>
  )
}
