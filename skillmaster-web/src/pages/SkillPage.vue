<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { codeMessage } from '../api/errors'
import {
  deleteSkill,
  discardVersion,
  getSkillDiff,
  getSkillFile,
  listAccessRequests,
  publishVersion,
  requestAccess,
  setVisibility,
  withdrawAccessRequest,
} from '../api/skills'
import { loginPath } from '../login'
import { useSession } from '../composables/useSession'
import { useSkillDetail } from '../composables/useSkillDetail'
import type { AuthoredVersion, Rung, SkillDiff } from '../api/types'
import { formatTime } from '../time'
import { versionLabel, versionSuffix } from '../version'
import { atLeast, rungLabel, rungsAbove, RUNG_NOTES } from '../rungs'
import FormBanner from '../components/FormBanner.vue'
import MarkdownView from '../components/MarkdownView.vue'
import RungMenu from '../components/RungMenu.vue'
import SkillCollaborators from '../components/SkillCollaborators.vue'

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
      : props.from !== undefined || props.to !== undefined
        ? 'versions'
        : 'info',
)

watch(tabs, (available) => {
  if (!available.some((entry) => entry.key === tab.value)) {
    tab.value = 'info'
  }
})

function tabHref(key: Tab): string {
  const next = new URLSearchParams(params)
  next.delete('q')
  next.delete('p')
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
  return `${baseAddress()}${queryString === '' ? '' : `?${queryString}`}`
}

const SUBPAGES = ['general', 'collaborators', 'details'] as const
type Subpage = (typeof SUBPAGES)[number]
const requestedSub = params.get('sub')
const sub = ref<Subpage>(
  requestedSub !== null && (SUBPAGES as readonly string[]).includes(requestedSub)
    ? (requestedSub as Subpage)
    : 'general',
)

function subHref(key: Subpage): string {
  const next = new URLSearchParams(params)
  next.set('tab', 'settings')
  next.set('sub', key)
  next.delete('path')
  const queryString = next.toString()
  return `${baseAddress()}?${queryString}`
}

function baseAddress(version?: string): string {
  const suffix = version === undefined ? (props.version === undefined ? '' : `@${props.version}`) : `@${version}`
  return `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}${suffix}`
}

/* --------------------------------------------------------------- the loaded skill */

const selected = computed<AuthoredVersion | null>(() => skill.value?.version ?? null)

/** The version consumers get. Null is an ordinary state: a skill nobody has published from yet. */
const liveVersion = computed<AuthoredVersion | null>(
  () => skill.value?.versions.find((version) => version.is_current && version.state === 'published') ?? null,
)

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

/**
 * The versions that are, or ever were, live: 线上 and 历史.
 *
 * **A discarded version is neither.** It was never published, and it is not a version the skill
 * "has" — it was refused by its author. Counting it here made the head say more versions than the
 * picker could offer, which is the disagreement this filter exists to prevent.
 */
const publishedVersions = computed(
  () => skill.value?.versions.filter((version) => version.state === 'published') ?? [],
)

/**
 * What the picker lists: 线上, then every other published version under 历史.
 *
 * **Drafts are not choices** — there is nothing a consumer can read of one. A draft is shown as a
 * *disabled* entry only when the address pinned one: the product lets an author open the files of a
 * draft they are working on (`/…@2.0.0/files/…`), and a control that silently displayed the live
 * version while the files on screen came from a draft would be lying about what is being read.
 */
const pickerGroups = computed(() => {
  const groups: { label: string; versions: AuthoredVersion[]; disabled: boolean }[] = []
  const live = liveVersion.value
  if (live !== null) {
    groups.push({ label: '线上', versions: [live], disabled: false })
  }
  const history = publishedVersions.value.filter((version) => version !== live)
  if (history.length > 0) {
    groups.push({ label: '历史', versions: history, disabled: false })
  }
  if (selected.value !== null && selected.value.state === 'draft') {
    groups.push({ label: '草稿', versions: [selected.value], disabled: true })
  }
  if (selected.value !== null && selected.value.state === 'discarded') {
    // The address can name a discarded version (the author's own list may link one). The picker
    // does not offer it, but it has to show what is on screen rather than a different version.
    groups.push({ label: '已丢弃', versions: [selected.value], disabled: true })
  }
  return groups
})

const shownVersion = computed(() => selected.value ?? liveVersion.value)

function onPickVersion(event: Event): void {
  const suffix = (event.target as HTMLSelectElement).value
  window.location.assign(`${baseAddress(suffix)}?tab=files`)
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
  query.delete('p')
  query.delete('q')
  if (dir === '') {
    query.delete('path')
  } else {
    query.set('path', dir)
  }
  return `${baseAddress()}?${query.toString()}`
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
  if (wanted === undefined || skill.value === null) {
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

const visibleVersions = computed(() =>
  (skill.value?.versions ?? []).filter((version) => version.state !== 'draft' || canWrite.value),
)

const versionTabLabel = computed(() => {
  const published = publishedVersions.value.length
  const drafts = skill.value?.versions.filter((version) => version.state === 'draft').length ?? 0
  const notes = canWrite.value && drafts > 0 ? ` · ${drafts} 个草稿` : ''
  return `版本（${published}）${notes}`
})

/**
 * 「维护」 discards anybody's draft; 「写入」 only their own — `maintain ∨ (write ∧ 提交人是我)`.
 * The two halves are why the version row carries its submitter at all.
 */
function mayDiscard(version: AuthoredVersion): boolean {
  const mine = version.submitted_by !== null && version.submitted_by === account.value?.username
  return version.state === 'draft' && (canMaintain.value || (canWrite.value && mine))
}

function chipOf(version: AuthoredVersion): { className: string; text: string } {
  if (version.is_current && version.state === 'published') {
    return { className: 'is-live', text: '线上' }
  }
  if (version.state === 'draft') {
    return { className: 'is-draft', text: '草稿' }
  }
  // **丢弃不是历史.** 历史 is a version that was live and was superseded; a discarded version was
  // never live at all. Saying 历史 about it would also imply it can be published again, which the
  // server refuses.
  return version.state === 'discarded'
    ? { className: 'is-plain', text: '已丢弃' }
    : { className: 'is-plain', text: '历史' }
}

async function publish(version: AuthoredVersion): Promise<void> {
  const result = await publishVersion(props.namespace, props.name, versionSuffix(version))
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  window.location.reload()
}

async function discard(version: AuthoredVersion): Promise<void> {
  const result = await discardVersion(props.namespace, props.name, versionSuffix(version))
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  window.location.reload()
}

/* The comparison, when the address asks for one. */
const diff = ref<SkillDiff | null>(null)
const diffTitle = ref('')
const diffLines = ref<string[]>([])

async function loadDiff(): Promise<void> {
  if (props.from === undefined && props.to === undefined) {
    return
  }
  const result = await getSkillDiff(props.namespace, props.name, props.to, props.from)
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  diff.value = result.data
  const target = props.to ?? liveVersion.value?.name ?? ''
  diffTitle.value =
    result.data.from === null
      ? `首次上线：@${versionLabel(target)}`
      : `与线上 ${liveLabel.value} 的差别 · @${versionLabel(target)}`
  const lines: string[] = []
  for (const file of result.data.files) {
    lines.push(`${file.status} ${file.relpath}`)
    for (const hunk of file.hunks ?? []) {
      lines.push(hunk.header)
      lines.push(...hunk.lines)
    }
    if (file.hunks === null) {
      lines.push(file.binary ? '（二进制文件）' : '（未展开）')
    }
  }
  diffLines.value = lines
}

const diffCloseHref = computed(() => {
  const suffix = props.to ?? (shownVersion.value === null ? undefined : versionSuffix(shownVersion.value))
  return `${baseAddress(suffix)}?tab=versions`
})

/* ----------------------------------------------------------------- settings · general */

const visibilityChoice = computed(() => {
  if (skill.value === null || skill.value.visibility === 'private') {
    return 'private'
  }
  return skill.value.public_rung === 'browse' ? 'public-browse' : 'public-invoke'
})

const confirmingPublicBrowse = ref(false)
const deleting = ref(false)
const visibilityPane = ref<HTMLElement | null>(null)
const confirmAccept = ref<HTMLButtonElement | null>(null)
const deleteConfirm = ref<HTMLButtonElement | null>(null)

/** The radio for one choice, for the two moments the DOM has to be put back by hand. */
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
    confirmingPublicBrowse.value = true
    await nextTick()
    confirmAccept.value?.focus()
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

async function acceptPublicBrowse(): Promise<void> {
  confirmingPublicBrowse.value = false
  await changeVisibilityDirect('public', 'browse')
}

/** 取消 and Esc are the same answer, and it leaves the focus where the question came from. */
function cancelPublicBrowse(): void {
  confirmingPublicBrowse.value = false
  void nextTick(() => visibilityRadio(visibilityChoice.value)?.focus())
}

async function changeVisibilityDirect(
  visibility: 'private' | 'public',
  publicRung: 'invoke' | 'browse' | null,
): Promise<void> {
  const result = await setVisibility(props.namespace, props.name, visibility, publicRung)
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  window.location.reload()
}

async function beginDelete(): Promise<void> {
  deleting.value = true
  await nextTick()
  deleteConfirm.value?.focus()
}

/** Esc is the same answer as 取消 — nothing is deleted. */
function cancelDelete(): void {
  deleting.value = false
}

async function confirmDelete(): Promise<void> {
  const result = await deleteSkill(props.namespace, props.name)
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  window.location.assign('/?show=deleted')
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
  // A comparison's `to` is the version the page is about: the detail is read for that version, not
  // by following the pointer, or the picker would show the live one while the block below compared
  // something else.
  await load(props.namespace, props.name, props.to ?? props.version)
  if (state.value !== 'ready') {
    return
  }
  await loadFile()
  await loadDiff()
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
                <a class="name" :href="baseAddress()">{{ skill.name }}</a>
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
            >{{ entry.label }}</a
          >
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
                <select
                  :value="shownVersion === null ? '' : versionSuffix(shownVersion)"
                  @change="onPickVersion"
                >
                  <optgroup
                    v-for="group in pickerGroups"
                    :key="group.label"
                    :label="group.label"
                  >
                    <option
                      v-for="version in group.versions"
                      :key="version.digest"
                      :value="versionSuffix(version)"
                      :disabled="group.disabled"
                    >
                      @{{ versionLabel(versionSuffix(version)) }}
                    </option>
                  </optgroup>
                </select>
              </label>
              <span class="skill-version-count">{{ publishedVersions.length }} 个版本</span>
              <span class="skill-address">
                <span class="visually-hidden">地址</span>{{ skill.namespace.slug }}/{{ skill.name }}
              </span>
            </div>

            <div v-if="shownVersion !== null" class="file-commit">
              <span class="avatar" aria-hidden="true">
                {{ (shownVersion.submitted_by ?? '?').slice(0, 1) }}
              </span>
              <span class="commit-who">{{ shownVersion.submitted_by ?? '' }}</span>
              <span class="commit-msg">{{ shownVersion.title }}</span>
              <span class="commit-meta">
                @{{ versionLabel(versionSuffix(shownVersion)) }} · {{ formatTime(shownVersion.submitted_at) }}
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
        <section v-if="openFile !== null" class="file-open">
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
        <!-- The difference, when one has been asked for, above the table it was asked from. -->
        <div v-if="diff !== null" class="diff-block">
          <div class="diff-head">
            <span>{{ diffTitle }}</span>
            <a class="link-button" :href="diffCloseHref">收起差异</a>
          </div>
          <pre class="file-source diff-body"><span
            v-for="(line, index) in diffLines"
            :key="index"
            :class="line.startsWith('+') ? 'is-add' : line.startsWith('-') ? 'is-del' : ''"
            >{{ line }}</span
          ></pre>
        </div>
        <div class="file-table">
          <div class="file-tab">{{ versionTabLabel }}</div>
          <ul class="versions">
            <li v-for="version in visibleVersions" :key="version.digest">
              <span class="version-name">@{{ versionLabel(versionSuffix(version)) }}</span>
              <span class="chip" :class="chipOf(version).className">
                <i class="dot" /><span class="chip-text">{{ chipOf(version).text }}</span>
              </span>
              <span class="version-note">{{ version.title }}</span>
              <span class="version-meta">
                {{ version.submitted_by ?? '' }} · {{ formatTime(version.submitted_at) }}
              </span>
              <span class="version-acts">
                <a
                  v-if="version.state === 'draft' && canWrite"
                  class="link-button"
                  :href="`${baseAddress(versionSuffix(version))}/diff`"
                  >查看差异</a
                >
                <!-- A discarded version is refused by the server, so the action is not drawn:
                     够不到的东西不画. -->
                <button
                  v-if="version.state !== 'discarded' && !version.is_current && canMaintain"
                  type="button"
                  class="link-button"
                  @click="publish(version)"
                >
                  上线
                </button>
                <button
                  v-if="mayDiscard(version)"
                  type="button"
                  class="link-button danger"
                  @click="discard(version)"
                >
                  丢弃
                </button>
              </span>
            </li>
          </ul>
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
                   options. Only the owner reaches it: deletion is off the ladder (ADR 0047). -->
              <section v-if="rung === 'owner'" class="settings-block is-danger">
                <h3>危险操作</h3>
                <div class="danger-box">
                  <div class="danger-row" @keydown.esc="deleting && cancelDelete()">
                    <div class="danger-what">
                      <p class="danger-title">删除这个 skill</p>
                      <template v-if="!deleting">
                        <p class="hint">
                          删除后，这个 skill 对所有访问者不可见，当前地址立即失效。
                          <strong>名称不会被释放</strong>——要用同一个名称创建新 skill，必须先恢复这个 skill。
                        </p>
                        <p class="hint">已删除的 skill 会保留在「我的 skill」的「已删除」中，可以恢复。</p>
                      </template>
                      <p v-else class="is-warn">删除后，该名称将无法用于创建新 skill，当前地址立即失效。</p>
                    </div>
                    <div class="danger-acts">
                      <template v-if="!deleting">
                        <button type="button" class="button is-danger" @click="beginDelete">
                          删除
                        </button>
                      </template>
                      <template v-else>
                        <button
                          ref="deleteConfirm"
                          type="button"
                          class="link-button danger"
                          @click="confirmDelete"
                        >
                          确认删除
                        </button>
                        <button type="button" class="link-button" @click="cancelDelete">
                          取消
                        </button>
                      </template>
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
    <div v-if="confirmingPublicBrowse" class="confirm" @keydown.esc="cancelPublicBrowse">
      <div class="confirm-sheet" role="dialog" aria-modal="true" aria-labelledby="publicBrowseConfirmTitle">
        <h2 id="publicBrowseConfirmTitle" class="confirm-title">改为「公开 · 浏览」</h2>
        <p class="confirm-body">
          公开之后，任何人都可以查看全部文件、下载并复制这个 skill；已经发生的下载与复制无法收回。
        </p>
        <div class="confirm-acts">
          <button type="button" class="button is-quiet" @click="cancelPublicBrowse">取消</button>
          <button
            ref="confirmAccept"
            type="button"
            class="button"
            @click="acceptPublicBrowse"
          >
            确认公开
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
