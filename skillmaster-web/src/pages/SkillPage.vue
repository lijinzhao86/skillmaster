<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
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
import { useSession } from '../composables/useSession'
import { useSkillDetail } from '../composables/useSkillDetail'
import { defaultBaseline, readableBy } from '../composables/useVersionActions'
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
import CommandMenu from '../components/CommandMenu.vue'
import { versionCreateItems } from '../components/version/createMenu'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import FormBanner from '../components/FormBanner.vue'
import MarkdownView from '../components/MarkdownView.vue'
import RungMenu from '../components/RungMenu.vue'
import SkillCollaborators from '../components/SkillCollaborators.vue'
import VersionDetail from '../components/version/VersionDetail.vue'
import VersionFilterBar from '../components/version/VersionFilterBar.vue'
import VersionPicker from '../components/VersionPicker.vue'
import ImportDialog from '../components/skill/ImportDialog.vue'
import VersionRow from '../components/version/VersionRow.vue'

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
 */
/** The import sheet: one file, a name, a note; success lands on the new draft's version page. */
const importOpen = ref(false)
/** The version list's 「新建版本 ▾」 — where focus goes when the sheet it opened closes. */
const filterBar = ref<InstanceType<typeof VersionFilterBar> | null>(null)
/** The file table's own 「新建版本」 (2026-10-21): the same menu, in the other tab. */
const filesMenu = ref<InstanceType<typeof CommandMenu> | null>(null)
/** Which 「新建版本」 opened the sheet, so focus goes back to that one. */
const importOrigin = ref<'filter' | 'files'>('filter')

const props = defineProps<{
  namespace: string
  name: string
  version?: string
  /** A file being read, from `/skills/…/files/…`. Opens the files section with it in view. */
  relpath?: string
  /** A comparison, from `/skills/…/diff?from=&to=`. Opens the versions section with it above the table. */
  from?: string
  to?: string
}>()

const { skill, state, banner, load } = useSkillDetail()
const { account } = useSession()

const params = new URLSearchParams(window.location.search)
/** The version tab's own parameters, parsed by its own owner (settings' `sub` is different). */
const versionRoute = parseVersionRoute(window.location.search)
/** `/skills/…/diff?from=&to=` is the legacy alias for `?tab=versions&version=<to>&sub=diff&from=`. */
const legacyDiff = props.from !== undefined || props.to !== undefined

const rung = computed(() => (skill.value === null ? 'invoke' : skill.value.rung))

const canBrowse = computed(() => skill.value !== null && atLeast(rung.value, 'browse'))
const canWrite = computed(() => skill.value !== null && atLeast(rung.value, 'write'))
const canMaintain = computed(() => skill.value !== null && atLeast(rung.value, 'maintain'))
const canAdmin = computed(() => skill.value !== null && atLeast(rung.value, 'admin'))

type Tab = 'info' | 'files' | 'versions' | 'settings'

/** The sections this rung can reach. 设置 is last, which is also the least frequent reason to come. */
const tabs = computed<{ key: Tab; label: string }[]>(() => {
  const list: { key: Tab; label: string }[] = [{ key: 'info', label: '信息' }]
  if (canBrowse.value) {
    list.push({ key: 'files', label: '文件' })
  }
  list.push({ key: 'versions', label: '版本' })
  if (canAdmin.value) {
    list.push({ key: 'settings', label: '设置' })
  }
  return list
})

const TABS = ['info', 'files', 'versions', 'settings'] as const

/** Each top-level tab's mark, drawn once in the page's own symbol sheet (design 2026-10-20). */
const TAB_ICONS: Record<Tab, string> = {
  info: 'infoMark',
  files: 'fileMark',
  versions: 'versionMark',
  settings: 'settingsMark',
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
const tab = ref<Tab>(
  requested !== null && (TABS as readonly string[]).includes(requested)
    ? (requested as Tab)
    : props.relpath !== undefined || params.get('path') !== null
      ? 'files'
      : legacyDiff
        ? 'versions'
        : 'info',
)

watch(tabs, (available) => {
  if (!available.some((entry) => entry.key === tab.value)) {
    tab.value = 'info'
  }
})

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

const SUBPAGES = ['general', 'collaborators', 'details'] as const
type Subpage = (typeof SUBPAGES)[number]
const requestedSub = params.get('sub')
const sub = ref<Subpage>(
  tab.value === 'settings' &&
    requestedSub !== null &&
    (SUBPAGES as readonly string[]).includes(requestedSub)
    ? (requestedSub as Subpage)
    : 'general',
)

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
function baseAddress(version?: string | null): string {
  const suffix =
    version === null ? '' : version === undefined ? (props.version === undefined ? '' : `@${props.version}`) : `@${version}`
  return `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}${suffix}`
}

/* --------------------------------------------------------------- the loaded skill */

const selected = computed<AuthoredVersion | null>(() => skill.value?.version ?? null)

/** The version consumers get. Null is an ordinary state: a skill nobody has published from yet. */
const liveVersion = computed<AuthoredVersion | null>(
  () =>
    skill.value?.versions.find(
      (version) => statusOf(version) === 'live' || (version.is_current && version.state === 'published'),
    ) ?? null,
)

/** Every version the server sent this rung. The server already applies the draft/revoked ladder. */
const allVersions = computed<AuthoredVersion[]>(() => skill.value?.versions ?? [])

/**
 * The list's rows: drafts need `write`; revoked and discarded need `maintain`. Published versions
 * are readable to anybody who reaches the author plane. One filter, read by the rows, the chips and
 * the tab's count, so they cannot disagree.
 */
const visibleVersions = computed(() =>
  allVersions.value.filter((version) => {
    const status = statusOf(version)
    if (status === 'draft') {
      return canWrite.value
    }
    if (status === 'revoked' || status === 'discarded') {
      return canMaintain.value
    }
    return true
  }),
)

/**
 * The count the tab wears and the file tab repeats: every visible version that is not a draft and
 * was not discarded — live + history + revoked (TD §计数与列表同源).
 */
const countedVersions = computed(() =>
  visibleVersions.value.filter((version) => {
    const status = statusOf(version)
    return status !== 'draft' && status !== 'discarded'
  }),
)

/**
 * The version tab's pin: the query's `version`, or the legacy `/diff` alias's `to`, falling back to
 * the live version when the alias named none.
 */
const detailRoute = computed<VersionRoute>(() => {
  if (!legacyDiff) {
    return versionRoute
  }
  const target = props.to ?? props.version ?? liveVersion.value?.name ?? undefined
  return {
    ...versionRoute,
    ...(target === undefined ? {} : { version: target }),
    sub: 'diff',
    ...(props.from === undefined ? {} : { from: props.from }),
  }
})

/** The version the single-version page shows, or null when the pin names none this rung can see. */
const detailVersion = computed<AuthoredVersion | null>(() => {
  const pin = detailRoute.value.version
  if (pin === undefined) {
    return null
  }
  return visibleVersions.value.find((version) => answersPin(version, pin)) ?? null
})

const frontmatter = computed<Record<string, unknown>>(() => skill.value?.frontmatter ?? {})

/** `when_to_use` is read from `metadata` first, with the top-level key as the fallback V21 kept. */
const whenToUse = computed<string>(() => {
  const metadata = frontmatter.value.metadata
  if (typeof metadata === 'object' && metadata !== null) {
    const value = (metadata as Record<string, unknown>).when_to_use
    if (typeof value === 'string' && value !== '') {
      return value
    }
  }
  const top = frontmatter.value.when_to_use
  return typeof top === 'string' ? top : ''
})

const compatibility = computed<string>(() =>
  typeof frontmatter.value.compatibility === 'string' ? frontmatter.value.compatibility : '',
)

/** The version the information page names in its rail — the one consumers get, or none yet. */
const liveLabel = computed(() =>
  liveVersion.value === null ? '未上线' : `@${versionLabel(versionSuffix(liveVersion.value))}`,
)

/* --------------------------------------------------------------------- the ask block */

const above = computed(() => rungsAbove(rung.value))
const showAsk = computed(() => skill.value !== null && rung.value !== 'owner' && above.value.length > 0)

const STANDING: Partial<Record<string, string>> = {
  invoke: '当前档位为「调用」：可以调用，但无法查看文件清单。',
  browse: '当前档位为「浏览」：可以读取，但无法提交版本。',
  write: '当前档位为「写入」：可以提交版本，但无法上线。',
  maintain: '当前档位为「维护」：可以上线，但无法修改可见性与协作者。',
}

const askWhy = computed(() => STANDING[rung.value] ?? '')
const pendingRung = computed(() => skill.value?.pending_rung ?? null)
const askedRung = ref<Rung>(above.value[0]?.name ?? 'browse')
watch(above, (options) => {
  if (!options.some((option) => option.name === askedRung.value)) {
    askedRung.value = options[0]?.name ?? 'browse'
  }
})
const askNote = computed(() => RUNG_NOTES[askedRung.value] ?? '')
const askLabel = computed(() => (atLeast(rung.value, 'write') ? '申请' : '申请成为协作者'))

async function submitAsk(): Promise<void> {
  const result = await requestAccess(props.namespace, props.name, askedRung.value)
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  window.location.reload()
}

async function withdrawAsk(): Promise<void> {
  const result = await withdrawAccessRequest(props.namespace, props.name)
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  window.location.reload()
}

/* ------------------------------------------------------------------- the files section */

/** The row the address pinned in the path (`/name@pin?tab=files`), whether or not it is readable. */
const pinnedRow = computed<AuthoredVersion | null>(() => {
  if (props.version === undefined) {
    return null
  }
  return allVersions.value.find((version) => answersPin(version, props.version!)) ?? null
})

/** Whether the address pins a discarded version — the one pin whose files are never drawn. */
const pinnedIsDiscarded = computed(
  () => pinnedRow.value !== null && statusOf(pinnedRow.value) === 'discarded',
)

/**
 * The version the address pinned in the path (`/name@pin?tab=files`), when this rung can read it.
 *
 * **A discarded version is not readable here** even for `maintain`, who can see the row and whose
 * bytes still exist (ADR 0031 keeps them): the design draws no files for a discarded version, so
 * the file tab offers none (design §文件栏; TD §接口 8).
 */
const pinnedFileVersion = computed<AuthoredVersion | null>(() => {
  if (props.version === undefined) {
    return null
  }
  const version = visibleVersions.value.find((candidate) => answersPin(candidate, props.version!))
  return version !== undefined && statusOf(version) !== 'discarded' ? version : null
})

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
const shownVersion = computed<AuthoredVersion | null>(() => {
  if (pinnedFileVersion.value !== null) {
    return pinnedFileVersion.value
  }
  if (pinnedIsDiscarded.value) {
    return null
  }
  if (liveVersion.value !== null) {
    return liveVersion.value
  }
  return (
    visibleVersions.value
      .filter((version) => statusOf(version) !== 'discarded' && statusOf(version) !== 'revoked')
      .sort((a, b) => b.submitted_at.localeCompare(a.submitted_at))[0] ?? null
  )
})

/**
 * The row the file tab's head speaks for: the version on screen, or the pinned discarded row when
 * there are no files to draw. The picker and the commit row both read it, so an empty file area
 * still says which version it is empty of.
 */
const fileTabVersion = computed<AuthoredVersion | null>(() =>
  shownVersion.value ?? (pinnedIsDiscarded.value ? pinnedRow.value : null),
)

/**
 * What the file tab's picker offers, in the design's order: live, drafts (`write`+), then the
 * remaining published versions (history, and revoked for `maintain`+). A discarded version has no
 * file snapshot and is never offered; a pinned version this rung cannot read is a disabled row, so
 * the control does not lie about what is on screen.
 */
const pickerVersions = computed<AuthoredVersion[]>(() => {
  const readable = visibleVersions.value.filter((version) => statusOf(version) !== 'discarded')
  const byTime = (a: AuthoredVersion, b: AuthoredVersion): number =>
    b.submitted_at.localeCompare(a.submitted_at)
  const ordered: AuthoredVersion[] = []
  if (liveVersion.value !== null) {
    ordered.push(liveVersion.value)
  }
  readable
    .filter((version) => statusOf(version) === 'draft')
    .sort(byTime)
    .forEach((version) => ordered.push(version))
  readable
    .filter((version) => statusOf(version) !== 'draft' && version !== liveVersion.value)
    .sort(byTime)
    .forEach((version) => ordered.push(version))
  // The address may pin a version this rung cannot read (the server usually 404s first); if the
  // object is here, show it disabled rather than silently switching the screen to another version.
  const pinned = allVersions.value.find(
    (version) => props.version !== undefined && answersPin(version, props.version),
  )
  if (pinned !== undefined && !ordered.includes(pinned)) {
    ordered.push(pinned)
  }
  return ordered
})

const disabledPickerValues = computed<string[]>(() =>
  pickerVersions.value
    .filter((version) => statusOf(version) === 'discarded' || !readableBy(version, rung.value))
    .map((version) => versionSuffix(version)),
)

/** Which version the files on screen belong to, as the link back to that version's own page. */
const commitVersionHref = computed(() => {
  if (fileTabVersion.value === null) {
    return baseAddress(null)
  }
  return `${baseAddress(null)}?tab=versions&version=${encodeURIComponent(versionSuffix(fileTabVersion.value))}`
})

const commitVersionLabel = computed(() => {
  const version = fileTabVersion.value
  if (version === null) {
    return ''
  }
  const title = versionTitle(version, rung.value)
  return version.name === null ? title : `${title} @${version.name}`
})

/**
 * 「下载」 in the file table's head (2026-10-21, design §一个 skill).
 *
 * The same archive route and the same condition as the version page's 下载: whoever can read the
 * files on screen can take that whole version. `shownVersion` is what decides — when it is null
 * (a pinned discarded version, or nothing readable at all) there are no files, so there is nothing
 * to download; the pinned row in the header is an identity, not a snapshot.
 */
const fileDownloadHref = computed<string | null>(() => {
  const version = shownVersion.value
  if (version === null) {
    return null
  }
  return `/web/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}@${encodeURIComponent(versionSuffix(version))}/archive`
})

/** The file table's 「新建版本」 items: built by the same function as the version list's menu. */
const filesCreateItems = computed(() =>
  versionCreateItems(`${baseAddress(null)}/new`, openImportFromFiles),
)

function onPickVersion(suffix: string): void {
  window.location.assign(`${baseAddress(suffix)}?tab=files`)
}

/** A sheet emits an address; the page is what navigates (the sheets never touch `location`). */
function goTo(href: string): void {
  window.location.assign(href)
}

/** The version list's 「新建版本 ▾」: its 导入压缩包 item opens the sheet. */
function openImportFromFilter(): void {
  importOrigin.value = 'filter'
  importOpen.value = true
}

/** The file table's 「新建版本」: the same menu, opening the same sheet (2026-10-21). */
function openImportFromFiles(): void {
  importOrigin.value = 'files'
  importOpen.value = true
}

/**
 * Closing the import sheet returns focus to the menu that opened it (design §导入与下载: 「焦点回到
 * 打开它的那颗」). Wait a tick so the sheet is gone before the button takes focus back — and the
 * button is whichever of the two 「新建版本」s it came from.
 */
function closeImport(): void {
  importOpen.value = false
  void nextTick(() =>
    importOrigin.value === 'files'
      ? filesMenu.value?.focusTrigger()
      : filterBar.value?.focusCreate(),
  )
}

const versionSuffixOf = (): string | undefined =>
  shownVersion.value === null ? undefined : versionSuffix(shownVersion.value)

function fileHref(relpath: string): string {
  const encoded = relpath.split('/').map(encodeURIComponent).join('/')
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
const currentDir = computed<string>(() => {
  if (props.relpath !== undefined) {
    const slash = props.relpath.lastIndexOf('/')
    return slash < 0 ? '' : props.relpath.slice(0, slash)
  }
  // `?path=references/` is the same directory as `references`; the slash is dropped before the
  // segment is used, or every relpath would be matched against `references//`.
  const asked = (params.get('path') ?? '').replace(/\/+$/, '')
  if (asked === '') {
    return ''
  }
  return skill.value?.files.some((file) => file.relpath.startsWith(`${asked}/`)) === true
    ? asked
    : ''
})

type Entry =
  | { kind: 'dir'; name: string; relpath: string }
  | { kind: 'file'; name: string; relpath: string; size: number; isBinary: boolean }

/**
 * One level of the tree: the folders and files whose parent is {@link currentDir}.
 *
 * **One level at a time** (design): a folder goes into its own listing, a file opens below. Folders
 * come first and files after, each sorted by name — the shape a repository's file table has.
 */
const levelEntries = computed<Entry[]>(() => {
  // **No version on screen, no files.** `skill.files` is the selected version's manifest, and a
  // discarded version is still selected server-side — drawing its rows here would be the same lie
  // the fallback to `liveVersion` told (TD §接口 8).
  if (shownVersion.value === null) {
    return []
  }
  const dir = currentDir.value
  const prefix = dir === '' ? '' : `${dir}/`
  const folders = new Map<string, Entry>()
  const files: Entry[] = []
  for (const file of skill.value?.files ?? []) {
    if (!file.relpath.startsWith(prefix)) {
      continue
    }
    const rest = file.relpath.slice(prefix.length)
    const slash = rest.indexOf('/')
    if (slash >= 0) {
      const name = rest.slice(0, slash)
      if (!folders.has(name)) {
        folders.set(name, { kind: 'dir', name, relpath: `${prefix}${name}` })
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
})

function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  const kilobytes = bytes / 1024
  return kilobytes < 1024 ? `${kilobytes.toFixed(1)} KB` : `${(kilobytes / 1024).toFixed(1)} MB`
}

/* One file of the shown version. The root's SKILL.md is what the listing opens by default. */
const fileContent = ref<string | null>(null)
const readingFile = ref(false)
const fileProblem = ref<string | null>(null)

/** The relpath being read: the address's, or the root body when the address names no file. */
const openRelpath = computed<string | undefined>(() => {
  if (props.relpath !== undefined) {
    return props.relpath
  }
  if (currentDir.value === '') {
    return 'SKILL.md'
  }
  return undefined
})

const openFile = computed<{ relpath: string; isBinary: boolean } | null>(() => {
  const wanted = openRelpath.value
  // A file opens under a version, and a version that is not on screen opens none (TD §接口 8).
  if (wanted === undefined || skill.value === null || shownVersion.value === null) {
    return null
  }
  const entry = skill.value.files.find((file) => file.relpath === wanted)
  return entry === undefined ? null : { relpath: entry.relpath, isBinary: entry.is_binary }
})

const openIsMarkdown = computed(() => /\.(md|markdown)$/i.test(openRelpath.value ?? ''))

/** The path inside the skill, segment by segment, each one back to its own level. */
const crumbs = computed<{ name: string; href: string }[]>(() => {
  const dir = currentDir.value
  if (dir === '') {
    return []
  }
  const parts = dir.split('/')
  return parts.map((name, index) => ({
    name,
    href: dirHref(parts.slice(0, index + 1).join('/')),
  }))
})

async function loadFile(): Promise<void> {
  fileContent.value = null
  fileProblem.value = null
  // Only the files section reads bytes. Every other tab is a full navigation away, so this is
  // decided once, at mount.
  if (tab.value !== 'files') {
    return
  }
  if (openRelpath.value === undefined || openFile.value === null || openFile.value.isBinary) {
    return
  }
  readingFile.value = true
  const result = await getSkillFile(
    props.namespace,
    props.name,
    versionSuffixOf(),
    openRelpath.value,
  )
  readingFile.value = false
  if (result.ok) {
    fileContent.value = result.data
    return
  }
  if (result.code === 'unauthenticated') {
    // The one read in the app that can come back 401 *after* a read that succeeded — the session
    // lapsed between the two. Everywhere else a 401 is the load's business.
    window.location.assign(loginPath())
    return
  }
  fileProblem.value = codeMessage(result.code, result.message)
}

/* ------------------------------------------------------------------ the versions section */

const filter = ref<VersionFilterKey>(versionRoute.filter)
const query = ref(versionRoute.q)

/**
 * The list's address. Filter and search are written with `replaceState`, not by navigating: the
 * list is already here, and a round trip per keystroke is what makes a search box feel like a form.
 * Both survive refresh, a copied link and the way back from a version page.
 */
function writeListAddress(): void {
  const next = new URLSearchParams(window.location.search)
  next.set('tab', 'versions')
  for (const name of ['version', 'sub', 'bump', 'from', 'to', 'fq', 'file', 'list']) {
    next.delete(name)
  }
  if (filter.value === 'all') {
    next.delete('filter')
  } else {
    next.set('filter', filter.value)
  }
  if (query.value.trim() === '') {
    next.delete('q')
  } else {
    next.set('q', query.value)
  }
  window.history.replaceState(null, '', `?${next.toString()}`)
}

function setFilter(value: VersionFilterKey): void {
  filter.value = value
  writeListAddress()
}

function setQuery(value: string): void {
  query.value = value
  writeListAddress()
}

function clearFilters(): void {
  filter.value = 'all'
  query.value = ''
  writeListAddress()
}

/** The list state the rows carry into their own page, and back. */
const listQueryString = computed(() => {
  const next = new URLSearchParams()
  if (filter.value !== 'all') {
    next.set('filter', filter.value)
  }
  if (query.value.trim() !== '') {
    next.set('q', query.value)
  }
  return next.toString()
})

/**
 * The rows on screen: status filter first, then search. What the search reads is what the row shows
 * — the name, number, note and submitter on the author plane; only the number and note for a
 * caller, whose row never carries the other two.
 */
const matchedVersions = computed(() =>
  visibleVersions.value.filter((version) => {
    if (filter.value !== 'all' && filterKeyOf(statusOf(version)) !== filter.value) {
      return false
    }
    const needle = query.value.trim().toLowerCase()
    if (needle === '') {
      return true
    }
    const words =
      rung.value === 'invoke'
        ? [version.name ?? '', version.note ?? '']
        : [versionTitle(version, rung.value), version.name ?? '', version.note ?? '', version.submitted_by ?? '']
    return words.join(' ').toLowerCase().includes(needle)
  }),
)

/** Whether a version has a baseline at all — what draws its 「查看改动」 menu item. */
function hasBaselineFor(version: AuthoredVersion): boolean {
  return defaultBaseline(version, visibleVersions.value, liveVersion.value, rung.value) !== null
}

const versionCount = computed(() => countedVersions.value.length)

const backToListHref = computed(() => {
  const next = new URLSearchParams(window.location.search)
  for (const name of ['version', 'sub', 'bump', 'from', 'to', 'fq', 'file', 'list']) {
    next.delete(name)
  }
  next.set('tab', 'versions')
  return `${baseAddress(null)}?${next.toString()}`
})

/** A new comparison baseline is a new screen; the address is the state, so it is a real navigation. */
function selectBaseline(pin: string): void {
  const next = new URLSearchParams(window.location.search)
  next.set('tab', 'versions')
  next.set('sub', 'diff')
  next.set('from', pin)
  // The legacy `/diff` alias's `to=` names the target the old address carried; the canonical form
  // carries it in `version`, so the two are not both kept.
  next.delete('to')
  if (detailRoute.value.version !== undefined) {
    next.set('version', detailRoute.value.version)
  }
  window.location.assign(`${baseAddress(null)}?${next.toString()}`)
}

/* ----------------------------------------------------------------- settings · general */

const visibilityChoice = computed(() => {
  if (skill.value === null || skill.value.visibility === 'private') {
    return 'private'
  }
  return skill.value.public_rung === 'browse' ? 'public-browse' : 'public-invoke'
})

const visibilityPane = ref<HTMLElement | null>(null)

/** The radio for one choice, for the moments the DOM has to be put back by hand. */
function visibilityRadio(value: string): HTMLInputElement | null {
  return (
    visibilityPane.value?.querySelector<HTMLInputElement>(
      `input[name="skill-visibility"][value="${value}"]`,
    ) ?? null
  )
}

async function changeVisibility(choice: string): Promise<void> {
  if (choice === 'public-browse') {
    // **The choice does not move before the answer.** The browser has already checked the radio;
    // putting it back is what makes 取消 mean "nothing happened" — Vue will not patch a `checked`
    // binding whose value did not change (design's `requestPublicBrowse`).
    const current = visibilityRadio(visibilityChoice.value)
    if (current !== null) {
      current.checked = true
    }
    publicBrowseOpen.value = true
    return
  }
  const visibility = choice === 'private' ? 'private' : 'public'
  const publicRung: 'invoke' | 'browse' | null =
    choice === 'private' ? null : choice === 'public-invoke' ? 'invoke' : 'browse'
  const result = await setVisibility(props.namespace, props.name, visibility, publicRung)
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  window.location.reload()
}

/* 公开 · 浏览 的确认. */
const publicBrowseOpen = ref(false)
const publicBrowsePending = ref(false)
const publicBrowseError = ref<string | null>(null)

async function acceptPublicBrowse(): Promise<void> {
  publicBrowsePending.value = true
  publicBrowseError.value = null
  const result = await setVisibility(props.namespace, props.name, 'public', 'browse')
  publicBrowsePending.value = false
  if (!result.ok) {
    publicBrowseError.value = codeMessage(result.code, result.message)
    return
  }
  window.location.reload()
}

/** 取消 and Esc are the same answer, and it leaves the focus where the question came from. */
function cancelPublicBrowse(): void {
  publicBrowseOpen.value = false
  publicBrowseError.value = null
  void nextTick(() => visibilityRadio(visibilityChoice.value)?.focus())
}

/* 设置 › 危险操作. */

const offline = computed(() => skill.value != null && skill.value.offline_at != null)

const offlineOpen = ref(false)
const offlinePending = ref(false)
const offlineError = ref<string | null>(null)
const offlineButton = ref<HTMLButtonElement | null>(null)

function askOffline(): void {
  offlineError.value = null
  offlineOpen.value = true
}

async function confirmOffline(): Promise<void> {
  offlinePending.value = true
  offlineError.value = null
  const result = await setSkillOffline(props.namespace, props.name, !offline.value)
  offlinePending.value = false
  if (!result.ok) {
    offlineError.value = codeMessage(result.code, result.message)
    return
  }
  window.location.reload()
}

function cancelOffline(): void {
  offlineOpen.value = false
  offlineError.value = null
  void nextTick(() => offlineButton.value?.focus())
}

const deleteOpen = ref(false)
const deletePending = ref(false)
const deleteError = ref<string | null>(null)
const deleteButton = ref<HTMLButtonElement | null>(null)

function askDelete(): void {
  deleteError.value = null
  deleteOpen.value = true
}

async function confirmDelete(): Promise<void> {
  deletePending.value = true
  deleteError.value = null
  const result = await deleteSkill(props.namespace, props.name)
  deletePending.value = false
  if (!result.ok) {
    deleteError.value = codeMessage(result.code, result.message)
    return
  }
  window.location.assign('/?show=deleted')
}

function cancelDelete(): void {
  deleteOpen.value = false
  deleteError.value = null
  void nextTick(() => deleteButton.value?.focus())
}

/* The details sub-page: the fields SKILL.md declares, for the author to check. */
const metadataRest = computed(() => {
  const metadata = frontmatter.value.metadata
  if (typeof metadata !== 'object' || metadata === null) {
    return ''
  }
  return Object.entries(metadata as Record<string, unknown>)
    .filter(([key]) => key !== 'when_to_use')
    .map(([key, value]) => `${key}: ${String(value)}`)
    .join('\n')
})

const detailRows = computed(() => {
  const version = liveVersion.value ?? selected.value
  return [
    { label: '名字', value: skill.value?.name ?? '', mono: true },
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
      value: typeof frontmatter.value.license === 'string' ? frontmatter.value.license : '',
      mono: true,
    },
    {
      label: 'allowed-tools',
      value:
        typeof frontmatter.value['allowed-tools'] === 'string'
          ? (frontmatter.value['allowed-tools'] as string)
          : '',
      mono: true,
    },
    { label: 'metadata', value: metadataRest.value, mono: true },
  ].filter((row) => row.value !== '')
})

/* The second-level menu's count: "somebody is waiting" has exactly two homes without notifications. */
const requestCount = ref(0)

onMounted(async () => {
  // The file tab reads the snapshot its path pins; the version tab reads the whole list so a bad
  // pin can be answered with 「没有这一版」 rather than 「没有这个 skill」. The legacy `/diff` alias
  // still pins its target (`?to=` or the path's `@`), because its comparison is about that version.
  const loadPin =
    tab.value === 'versions'
      ? legacyDiff
        ? (props.to ?? props.version)
        : undefined
      : props.version
  await load(props.namespace, props.name, loadPin)
  if (state.value !== 'ready') {
    return
  }
  await loadFile()
  // The 协作者 sub-page hands its own queue size up instead — one read, two views.
  if (canAdmin.value && !(tab.value === 'settings' && sub.value === 'collaborators')) {
    const result = await listAccessRequests(props.namespace, props.name)
    if (result.ok) {
      requestCount.value = result.data.total
    }
  }
})

watch(() => props.relpath, () => {
  void loadFile()
})
</script>

<template>
  <section class="page">
    <FormBanner :message="banner" />

    <div v-if="state === 'missing'" class="notfound">
      <h2>没有这个 skill</h2>
      <!-- **Same screen, several reasons.** The address may name nothing, name a skill that was
           deleted, or name one this person cannot reach. The 404 is one answer on purpose, so the
           sentence says only what is true of all of them. -->
      <p class="hint">这个地址下没有你能打开的 skill，也可能它已经被删除了。</p>
      <p>
        <a href="/">回到我的 skill</a>　·　<a href="/?show=deleted">查看已删除的 skill</a>
      </p>
    </div>

    <p v-else-if="state === 'loading'" class="loading">加载中…</p>

    <template v-else-if="skill">
      <header class="skill-head">
        <!-- Who, and what: the same three things a repository's first line carries — the account
             mark, the name, and whether anybody else can read it. -->
        <div class="skill-ident">
          <span class="avatar" aria-hidden="true">{{ skill.namespace.slug.slice(0, 1) }}</span>
          <div class="skill-who">
            <div class="skill-name-row">
              <h1>
                <span class="ns">{{ skill.namespace.slug }} /</span>
                <a class="name" :href="baseAddress(null)">{{ skill.name }}</a>
                <template v-for="segment in crumbs" :key="segment.href">
                  <span class="crumb"> / <a :href="segment.href">{{ segment.name }}</a></span>
                </template>
              </h1>
              <!-- **Drawn only where it can be acted on.** The badge leads to the page that owns
                   the fact, and that page is `admin`'s — so for every rung below it the whole mark
                   is absent, not greyed (design: 够不到的东西不画). -->
              <a
                v-if="canAdmin"
                class="chip is-plain"
                :href="subHref('general')"
              >{{ skill.visibility === 'private' ? '私有' : '公开' }}</a>
              <!-- **下线是整个 skill 的状态，不是某一版的。** It sits beside the visibility mark
                   because both answer 「这个地址现在是什么」; it is not a version mark. -->
              <span v-if="skill.offline_at != null" class="chip is-offline">已下线</span>
            </div>
            <!-- `title` is an optional field: the server fills it with `name` when the author wrote
                 none, so "is there a title" is `title !== name` rather than "is it empty". -->
            <p v-if="skill.title !== skill.name" class="skill-title">{{ skill.title }}</p>
          </div>
        </div>

        <nav class="skill-tabs" aria-label="这个 skill 的分区">
          <a
            v-for="entry in tabs"
            :key="entry.key"
            class="skill-tab"
            :class="{ 'is-current': tab === entry.key }"
            :aria-current="tab === entry.key ? 'page' : undefined"
            :href="tabHref(entry.key)"
          >
            <svg class="skill-tab-icon" aria-hidden="true">
              <use :href="`#${TAB_ICONS[entry.key]}`" />
            </svg>
            <span class="skill-tab-label">{{ entry.label }}</span>
            <span
              v-if="entry.key === 'versions' && versionCount > 0"
              class="skill-tab-count"
              >{{ versionCount }}</span
            >
          </a>
        </nav>
      </header>

      <svg style="position: absolute; width: 0; height: 0; overflow: hidden" aria-hidden="true">
        <symbol id="fileMark" viewBox="0 0 24 24" fill="none">
          <path
            d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linejoin="round"
          />
          <path d="M14 3v5h5" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
        </symbol>
        <symbol id="descMark" viewBox="0 0 24 24" fill="none">
          <path
            d="M5 6h14M5 10.5h14M5 15h9"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
          />
        </symbol>
        <symbol id="clockMark" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="8.2" stroke="currentColor" stroke-width="1.8" />
          <path
            d="M12 7.4V12l3.2 1.9"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </symbol>
        <symbol id="versionMark" viewBox="0 0 24 24" fill="none">
          <path
            d="M20.1 12.7l-7.4 7.4a1.8 1.8 0 0 1-2.5 0L4 14.1V4h10.1l6 6a1.8 1.8 0 0 1 .0 2.7z"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linejoin="round"
          />
          <circle cx="8.4" cy="8.4" r="1.25" fill="currentColor" />
        </symbol>
        <symbol id="envMark" viewBox="0 0 24 24" fill="none">
          <path
            d="M4.8 7.2l4.6 4.8-4.6 4.8"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
          <path d="M12.4 16.8h6.8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </symbol>
        <symbol id="infoMark" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="8.2" stroke="currentColor" stroke-width="1.8" />
          <path
            d="M12 11v5M12 8.1v.2"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
          />
        </symbol>
        <symbol id="settingsMark" viewBox="0 0 24 24" fill="none">
          <path
            d="M4 7h9M18 7h2M4 12h4M13 12h7M4 17h9M18 17h2"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
          />
          <circle cx="15.5" cy="7" r="2" stroke="currentColor" stroke-width="1.8" />
          <circle cx="10.5" cy="12" r="2" stroke="currentColor" stroke-width="1.8" />
          <circle cx="15.5" cy="17" r="2" stroke="currentColor" stroke-width="1.8" />
        </symbol>
        <symbol id="folderMark" viewBox="0 0 24 24" fill="none">
          <path
            d="M3.5 18.5V6.5a1 1 0 0 1 1-1h4.2l2 2.5h8.8a1 1 0 0 1 1 1v9.5a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1z"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linejoin="round"
          />
        </symbol>
      </svg>

      <!-- 信息 = the `/metadata` layer. 描述 first as prose, then 适用场景 lifted out (it is the one
           sentence that says when to remember this skill), then 运行环境 — the three things a reader
           weighs together. The version is a value, so it sits in the rail. -->
      <div v-if="tab === 'info'" class="tab-panel" data-tab="info">
        <section class="info-pane">
          <div class="info-main">
            <section class="info-block">
              <span class="info-label">
                <svg class="info-icon" aria-hidden="true"><use href="#descMark" /></svg>
                描述
              </span>
              <p class="info-text">{{ skill.description }}</p>
            </section>
            <section v-if="whenToUse !== ''" class="info-block">
              <span class="info-label">
                <svg class="info-icon" aria-hidden="true"><use href="#clockMark" /></svg>
                适用场景
              </span>
              <p class="info-text">{{ whenToUse }}</p>
            </section>
            <section v-if="compatibility !== ''" class="info-block">
              <span class="info-label">
                <svg class="info-icon" aria-hidden="true"><use href="#envMark" /></svg>
                运行环境
              </span>
              <p class="info-text">{{ compatibility }}</p>
            </section>
          </div>

          <aside class="info-rail">
            <section class="info-rail-item">
              <svg class="info-rail-icon" aria-hidden="true"><use href="#versionMark" /></svg>
              <span class="info-rail-label">版本</span>
              <span class="info-rail-value">{{ liveLabel }}</span>
            </section>
            <!-- 标签是可选的扩展属性，这一版不显示；位置留着，设计件里它也在。 -->
            <div class="info-tags" hidden />
          </aside>
        </section>

        <!-- 申请: the page's only action, drawn while the ladder still has a rung above this one. -->
        <section v-if="showAsk" class="ask">
          <h3>申请</h3>
          <p class="hint">
            {{ askWhy
            }}<template v-if="pendingRung === null">如需更高的档位，请在下方申请。</template>
          </p>
          <div v-if="pendingRung === null" class="ask-form">
            <span id="askRoleLabel" class="ask-label">申请档位</span>
            <RungMenu v-model="askedRung" :options="above" label="申请档位" labelledby="askRoleLabel" />
            <button type="button" class="button" @click="submitAsk">{{ askLabel }}</button>
          </div>
          <p v-if="pendingRung === null" class="ask-note">{{ askNote }}</p>
          <div v-else class="ask-pending">
            <span class="chip is-plain"><span class="dot" /><span class="chip-text">等待处理</span></span>
            <span class="ask-pending-text">
              已提交「{{ rungLabel(pendingRung) }}」的申请，由 {{ skill.namespace.slug }} 或「管理」审批。
            </span>
            <button type="button" class="link-button" @click="withdrawAsk">撤回申请</button>
          </div>
        </section>
      </div>

      <div v-else-if="tab === 'files'" class="tab-panel" data-tab="files">
        <section>
          <h2 class="visually-hidden">文件（{{ levelEntries.length }}）</h2>
          <div class="file-table">
            <!-- The version row belongs to this table, not to the page: it chooses which snapshot
                 these files are, a question that only means something while the files are on
                 screen. -->
            <div class="skill-version-row">
              <label class="version-picker">
                <span class="visually-hidden">选择版本</span>
                <VersionPicker
                  :versions="pickerVersions"
                  :selected="fileTabVersion === null ? '' : versionSuffix(fileTabVersion)"
                  :disabled-values="disabledPickerValues"
                  @select="onPickVersion"
                />
              </label>
              <span class="skill-version-count">{{ countedVersions.length }} 个版本</span>
              <div class="skill-version-tail">
                <span class="skill-address">
                  <span class="visually-hidden">地址</span>{{ skill.namespace.slug }}/{{ skill.name }}
                </span>
                <!-- **The row's right end: 下载 then 新建版本 (2026-10-21, design §一个 skill).**
                     GitHub puts its file actions there; the download is the same archive route and
                     the same condition as the version page's, and 新建版本 is the same menu as the
                     version list's — one way to produce a version, wherever it is opened from. -->
                <div class="skill-version-actions">
                  <a
                    v-if="fileDownloadHref !== null"
                    class="button is-quiet"
                    :href="fileDownloadHref"
                  >
                    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <path
                        d="M12 4v10M8 10l4 4 4-4"
                        stroke="currentColor"
                        stroke-width="1.8"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                      />
                      <path
                        d="M5 19h14"
                        stroke="currentColor"
                        stroke-width="1.8"
                        stroke-linecap="round"
                      />
                    </svg>
                    下载
                  </a>
                  <CommandMenu
                    v-if="canWrite"
                    ref="filesMenu"
                    :groups="[filesCreateItems]"
                    menu-label="新建版本"
                    resize="reposition"
                  >
                    <template #trigger="{ open, toggle }">
                      <button
                        type="button"
                        class="button menu-button is-compact"
                        aria-haspopup="menu"
                        :aria-expanded="open ? 'true' : 'false'"
                        @click="toggle"
                      >
                        新建版本
                      </button>
                    </template>
                  </CommandMenu>
                </div>
              </div>
            </div>

            <!-- The row is always drawn: with nothing readable it says 「未上线」 (design's own
                 sentence), so the file column does not lose its head. -->
            <div class="file-commit">
              <span class="avatar" aria-hidden="true">
                {{ fileTabVersion === null ? '' : (fileTabVersion.submitted_by ?? '?').slice(0, 1) }}
              </span>
              <span class="commit-who">{{
                fileTabVersion === null ? '' : (fileTabVersion.submitted_by ?? '')
              }}</span>
              <span class="commit-msg">{{
                fileTabVersion === null ? '未上线' : (fileTabVersion.note ?? '')
              }}</span>
              <!-- **The right end is this version's identity, so it is the way back to it.** The
                   mirror of the version page's 「查看文件」; the time stays plain text. -->
              <span v-if="fileTabVersion !== null" class="commit-meta">
                <a class="commit-version" :href="commitVersionHref">{{ commitVersionLabel }}</a>
                <template v-if="fileTabVersion.name !== null">
                  · {{ formatTime(fileTabVersion.first_published_at ?? fileTabVersion.submitted_at) }}
                </template>
              </span>
            </div>

            <!-- **One level at a time.** A folder goes into its own listing; a file opens below.
                 The middle column — "the version that last changed this file, and its date" — is
                 left empty: the product has no per-file history to say it with, and the design
                 records that fact as the demo's own fixture (design/README §已知的取舍). -->
            <ul class="files">
              <li v-for="entry in levelEntries" :key="entry.relpath">
                <svg class="file-icon" aria-hidden="true">
                  <use :href="entry.kind === 'dir' ? '#folderMark' : '#fileMark'" />
                </svg>
                <a
                  class="file-name"
                  :class="{ 'is-current': entry.kind === 'file' && entry.relpath === openRelpath }"
                  :href="entry.kind === 'dir' ? dirHref(entry.relpath) : fileHref(entry.relpath)"
                  >{{ entry.kind === 'dir' ? `${entry.name}/` : entry.name }}</a
                >
                <span class="file-changed" />
                <span v-if="entry.kind === 'file'" class="size">{{ formatSize(entry.size) }}</span>
                <span v-else class="size" />
              </li>
            </ul>
          </div>
        </section>

        <!-- The file being read, under the listing the way a repository renders its README under its
             files — and in the same kind of box, with the file's own name where the version was.
             The root opens `SKILL.md` by default; a folder opens nothing until a file is chosen. -->
        <section v-if="openFile !== null" class="block file-open">
          <h2 class="visually-hidden">文件内容</h2>
          <div class="file-table">
            <div class="file-tab">{{ openFile.relpath }}</div>
            <div class="file-body">
              <p v-if="openFile.isBinary" class="hint">
                这是二进制文件，不在这里展示。本机用 <code>skillmaster skill get</code> 取它。
              </p>
              <p v-else-if="readingFile" class="loading">加载中…</p>
              <p v-else-if="fileProblem !== null" class="banner">{{ fileProblem }}</p>
              <MarkdownView
                v-else-if="fileContent !== null && openIsMarkdown"
                :source="fileContent"
              />
              <pre v-else-if="fileContent !== null" class="file-source">{{ fileContent }}</pre>
              <p v-else class="hint">这个版本里没有 {{ openFile.relpath }}。</p>
            </div>
          </div>
        </section>
      </div>

      <div v-else-if="tab === 'versions'" class="tab-panel" data-tab="versions">
        <!-- **One route, two states.** A version pin in the query switches the tab from the list to
             that version's own page; without one the list is what the tab is. A pin naming nothing
             this rung can see gets one answer, with a way back and no leak. -->
        <VersionDetail
          v-if="detailRoute.version !== undefined && detailVersion !== null"
          :namespace="props.namespace"
          :name="props.name"
          :version="detailVersion"
          :versions="visibleVersions"
          :rung="rung"
          :username="account?.username ?? null"
          :live="liveVersion"
          :route="detailRoute"
          @select-baseline="selectBaseline"
        />

        <div v-else-if="detailRoute.version !== undefined" id="versionMissing">
          <div class="notfound">
            <h2>没有这一版</h2>
            <p class="hint">
              这个地址下没有你能打开的版本：它可能已经被丢弃，也可能属于一个你看不到的 skill。
            </p>
            <p><a :href="backToListHref">回到版本列表</a></p>
          </div>
        </div>

        <div v-else id="versionListWrap">
          <VersionFilterBar
            ref="filterBar"
            :versions="visibleVersions"
            :filter="filter"
            :query="query"
            :rung="rung"
            :new-href="`${baseAddress(null)}/new`"
            @update:filter="setFilter"
            @update:query="setQuery"
            @open-import="openImportFromFilter"
          />
          <div class="file-table version-list-table">
            <ul class="versions">
              <VersionRow
                v-for="version in matchedVersions"
                :key="versionSuffix(version)"
                :version="version"
                :namespace="props.namespace"
                :name="props.name"
                :rung="rung"
                :username="account?.username ?? null"
                :list-query="listQueryString"
                :has-baseline="hasBaselineFor(version)"
              />
            </ul>
            <p v-if="matchedVersions.length === 0" class="version-empty">
              没有匹配的版本。
              <button type="button" class="link-button" @click="clearFilters">清除筛选</button>
            </p>
          </div>
        </div>
      </div>

      <div v-else-if="tab === 'settings'" class="tab-panel" data-tab="settings">
        <div class="settings">
          <!-- One column of subjects, the one being set beside it. Nothing separates the two but the
               indent: a rule there would fence off a list of three. -->
          <nav class="settings-nav" aria-label="设置的分区">
            <a
              class="settings-item"
              :class="{ 'is-current': sub === 'general' }"
              :aria-current="sub === 'general' ? 'page' : undefined"
              :href="subHref('general')"
              >常规</a
            >
            <a
              class="settings-item"
              :class="{ 'is-current': sub === 'collaborators' }"
              :aria-current="sub === 'collaborators' ? 'page' : undefined"
              :href="subHref('collaborators')"
              >协作者<span v-if="requestCount > 0" class="count">{{ requestCount }}</span></a
            >
            <a
              class="settings-item"
              :class="{ 'is-current': sub === 'details' }"
              :aria-current="sub === 'details' ? 'page' : undefined"
              :href="subHref('details')"
              >详细信息</a
            >
          </nav>

          <div class="settings-body">
            <section
              v-if="sub === 'general'"
              ref="visibilityPane"
              class="settings-pane"
              data-sub="general"
            >
              <h2>常规</h2>

              <section class="settings-block">
                <h3>可见性</h3>
                <p class="hint">
                  公开的 skill 可以被搜索到；私有的 skill 仅你和协作者可以访问，其他访问者无法得知它的存在。
                </p>
                <div class="choices">
                  <label class="choice">
                    <input
                      type="radio"
                      name="skill-visibility"
                      value="private"
                      :checked="visibilityChoice === 'private'"
                      @change="changeVisibility('private')"
                    />
                    <span class="choice-body">
                      <span class="choice-title">私有</span>
                      <span class="choice-note">仅你和被授权的协作者可以访问。</span>
                    </span>
                  </label>
                  <label class="choice">
                    <input
                      type="radio"
                      name="skill-visibility"
                      value="public-invoke"
                      :checked="visibilityChoice === 'public-invoke'"
                      @change="changeVisibility('public-invoke')"
                    />
                    <span class="choice-body">
                      <span class="choice-title">公开 · 调用</span>
                      <span class="choice-note">
                        任何人都可以调用，也可以按路径读取文件；但无法查看文件清单，只有正文中提到的文件可以按路径获取。
                      </span>
                    </span>
                  </label>
                  <label class="choice">
                    <input
                      type="radio"
                      name="skill-visibility"
                      value="public-browse"
                      :checked="visibilityChoice === 'public-browse'"
                      @change="changeVisibility('public-browse')"
                    />
                    <span class="choice-body">
                      <span class="choice-title">公开 · 浏览</span>
                      <span class="choice-note">任何人都可以调用、查看全部文件并下载；这个 skill 也可以被复制。</span>
                      <span class="choice-warn">已经发生的下载与复制无法收回。</span>
                    </span>
                  </label>
                </div>
              </section>

              <!-- 危险区域: it stays at the bottom of this page rather than in the navigation — an
                   action that takes the skill away from every reader is not one of three equal
                   options. Only the owner reaches it: the service switch and deletion are off the
                   ladder (ADR 0047, ADR 0050). -->
              <section v-if="rung === 'owner'" class="settings-block is-danger">
                <h3>危险操作</h3>
                <div class="danger-box">
                  <!-- **下线是整个 skill 的服务开关，不是某一版的状态。** Versions and the pointer
                       do not move; restoring needs no re-publish. Reversible, so it comes before
                       deletion. -->
                  <div class="danger-row">
                    <div class="danger-what">
                      <p class="danger-title">{{ offline ? '恢复服务' : '下线这个 skill' }}</p>
                      <p v-if="!offline" class="hint">
                        下线后，所有读者按这个 skill 的地址调用都会得到「服务已下线」；它也不再出现在搜索与公开列表里。版本状态与当前版本都不变，恢复后不需要重新上线。
                      </p>
                      <p v-else class="hint">
                        这个 skill 已下线：调用者得到「服务已下线」。恢复后，当前线上版本直接重新对外。版本状态与当前版本都不变。
                      </p>
                    </div>
                    <div class="danger-acts">
                      <button
                        ref="offlineButton"
                        type="button"
                        class="button"
                        :class="{ 'is-danger': !offline }"
                        @click="askOffline"
                      >
                        {{ offline ? '恢复服务' : '下线' }}
                      </button>
                    </div>
                  </div>

                  <div class="danger-row">
                    <!-- 后果写在按钮旁边，不是确认框里才第一次出现：确认框只重复最要紧的那一条。 -->
                    <div class="danger-what">
                      <p class="danger-title">删除这个 skill</p>
                      <p class="hint">
                        删除后，这个 skill 对所有访问者不可见，当前地址立即失效。
                        <strong>名称不会被释放</strong>——要用同一个名称创建新 skill，必须先恢复这个 skill。
                      </p>
                      <p class="hint">已删除的 skill 会保留在「我的 skill」的「已删除」中，可以恢复。</p>
                    </div>
                    <div class="danger-acts">
                      <button ref="deleteButton" type="button" class="button is-danger" @click="askDelete">
                        删除
                      </button>
                    </div>
                  </div>
                </div>
              </section>
            </section>

            <SkillCollaborators
              v-else-if="sub === 'collaborators'"
              :namespace="skill.namespace.slug"
              :name="skill.name"
              :public-rung="skill.public_rung"
              @count="requestCount = $event"
            />

            <section v-else class="settings-pane" data-sub="details">
              <h2>详细信息</h2>
              <p class="hint">
                这些是 SKILL.md 中声明的字段。调用者在「信息」页只能看到描述、适用场景、运行环境与版本。
              </p>
              <dl class="info-rows">
                <div v-for="row in detailRows" :key="row.label">
                  <dt>{{ row.label }}</dt>
                  <dd :class="{ 'is-mono': row.mono }">{{ row.value }}</dd>
                </div>
              </dl>
            </section>
          </div>
        </div>
      </div>
    </template>

    <!-- 公开 · 浏览 的确认: the selector does not change before this is answered, so cancelling
         leaves the page exactly as it was. -->
    <ConfirmDialog
      :open="publicBrowseOpen"
      title="改为「公开 · 浏览」"
      body="公开之后，任何人都可以查看全部文件、下载并复制这个 skill；已经发生的下载与复制无法收回。"
      confirm-label="确认公开"
      pending-label="正在公开…"
      :danger="false"
      :pending="publicBrowsePending"
      :error="publicBrowseError"
      @confirm="acceptPublicBrowse"
      @cancel="cancelPublicBrowse"
    />

    <!-- 下线 / 恢复服务，与删除：同一个确认壳，取消是默认焦点，Esc 等于取消。 -->
    <ConfirmDialog
      :open="offlineOpen"
      :title="offline ? '恢复服务' : '下线这个 skill'"
      :body="
        offline
          ? '恢复后，当前线上版本直接重新对外，不需要重新上线。'
          : '下线后，所有调用都会失败；版本状态与当前版本都不变。'
      "
      :confirm-label="offline ? '确认恢复服务' : '确认下线'"
      :pending-label="offline ? '正在恢复服务…' : '正在下线…'"
      :pending="offlinePending"
      :error="offlineError"
      @confirm="confirmOffline"
      @cancel="cancelOffline"
    />

    <ConfirmDialog
      :open="deleteOpen"
      title="删除这个 skill？"
      body="删除后，该名称将无法用于创建新 skill，当前地址立即失效。"
      confirm-label="确认删除"
      pending-label="正在删除…"
      :pending="deletePending"
      :error="deleteError"
      @confirm="confirmDelete"
      @cancel="cancelDelete"
    />
  </section>

  <ImportDialog
    :open="importOpen"
    mode="version"
    :namespace="props.namespace"
    :name="props.name"
    :rung="skill?.rung ?? 'browse'"
    @close="closeImport"
    @navigate="goTo"
  />
</template>
