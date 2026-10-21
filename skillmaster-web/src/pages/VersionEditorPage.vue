<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
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
import ConfirmDialog from '../components/ConfirmDialog.vue'
import { canEditDraft, canWrite } from '../composables/useVersionActions'
import { useSession } from '../composables/useSession'
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
 */
const props = defineProps<{
  namespace: string
  name: string
  mode: 'new' | 'edit'
  version?: string
  file?: string
}>()

const { account } = useSession()

const loading = ref(true)
const missing = ref(false)
const skill = ref<AuthoredSkill | null>(null)
const currentVersion = ref<AuthoredVersion | null>(null)
const isNew = ref(props.mode === 'new')
const baseDigest = ref<string | null>(null)
const baseFrom = ref<string | undefined>(undefined)
const baseLabel = ref('')
const baseCount = ref(1)

const files = ref<AuthoredFile[]>([])
/** Folders with nothing in them live only on this page — the manifest has files only. */
const localFolders = reactive<Set<string>>(new Set())
const buffers = reactive<Record<string, string>>({})
const saved = reactive<Record<string, string>>({})
const current = ref('SKILL.md')
const opened = reactive<Record<string, boolean>>({})
const query = ref('')
const treeCollapsed = ref(false)
const create = ref<{ kind: 'file' | 'dir'; value: string; error: string | null } | null>(null)
const rename = ref<{ path: string; value: string; error: string | null } | null>(null)
const menu = ref<{ path: string; style: Record<string, string> } | null>(null)
const deleteTarget = ref<string | null>(null)
const deleteFolderTarget = ref<string | null>(null)
const leaveTarget = ref<{ href: string; opener: HTMLElement | null } | null>(null)

const draftName = ref('')
const draftNote = ref('')
const savedName = ref('')
const savedNote = ref('')
const labelStatus = ref<{ text: string; danger: boolean } | null>(null)

const saving = ref(false)
const conflict = ref(false)
const duplicate = ref<{ title: string; label: string; href: string } | null>(null)
const errorBanner = ref<string | null>(null)
const errorTitle = ref('保存失败。')
const pendingEdit = ref<DraftEditBody | null>(null)
const scrollTop = ref(0)
const caret = ref({ line: 1, column: 1 })
const textarea = ref<HTMLTextAreaElement | null>(null)
const gutter = ref<HTMLDivElement | null>(null)
const conflictBanner = ref<HTMLElement | null>(null)
const duplicateBanner = ref<HTMLElement | null>(null)
const errorElement = ref<HTMLElement | null>(null)
const workspace = ref<HTMLElement | null>(null)

const MAX_NAME = 40
const MAX_NOTE = 200

const readSuffix = computed(() =>
  currentVersion.value === null ? undefined : versionSuffix(currentVersion.value),
)
/**
 * Where the page's own links go.
 *
 * **A version is a tab pin, not a path suffix** (the product's route grammar): `/skills/ns/name`
 * opens 信息, and `?tab=versions&version=<pin>` is the version page. The editor's `@<pin>/edit` is
 * the one address that carries a suffix, because it is a page of its own.
 */
const skillHref = computed(
  () => `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}`,
)
const versionsHref = computed(() => `${skillHref.value}?tab=versions`)
function versionHref(pin: string): string {
  return `${skillHref.value}?tab=versions&version=${encodeURIComponent(pin)}`
}
const viewHref = computed(() =>
  readSuffix.value === undefined ? versionsHref.value : versionHref(readSuffix.value),
)
const currentFile = computed(() => files.value.find((file) => file.relpath === current.value) ?? null)
const currentText = computed(() => buffers[current.value] ?? '')
const currentBinary = computed(() => currentFile.value?.is_binary === true)
const gutterText = computed(() => {
  const count = currentText.value.split('\n').length
  return Array.from({ length: count }, (_, index) => String(index + 1)).join('\n')
})
const dirtyPaths = computed(() =>
  Object.keys(buffers).filter((path) => buffers[path] !== saved[path]),
)
const currentDirty = computed(() => buffers[current.value] !== saved[current.value])
const canSave = computed(
  () =>
    !saving.value &&
    !currentBinary.value &&
    currentDirty.value &&
    (!isNew.value || draftName.value.trim() !== ''),
)
const facts = computed(() => {
  if (!isNew.value) {
    const code = readSuffix.value ?? ''
    const submitter = currentVersion.value?.submitted_by ?? ''
    return `草稿　·　${code}　·　提交人 ${submitter}　·　${files.value.length} 个文件`
  }
  return `${baseLabel.value}　·　${baseCount.value} 个文件`
})

interface Node {
  name: string
  path: string
  dir: boolean
  children: Node[]
}

const rows = computed(() => {
  const needle = query.value.trim().toLowerCase()
  const searching = needle !== ''
  const out: Array<{
    path: string
    name: string
    depth: number
    dir: boolean
    expanded: boolean
    empty: boolean
  }> = []
  const walk = (nodes: Node[], depth: number): void => {
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
})

function hasFilesUnder(path: string): boolean {
  return files.value.some((file) => file.relpath.startsWith(`${path}/`))
}

function folderExists(path: string): boolean {
  if (localFolders.has(path)) return true
  if (Array.from(localFolders).some((key) => key.startsWith(`${path}/`))) return true
  return hasFilesUnder(path)
}

function fileExists(path: string): boolean {
  return files.value.some((file) => file.relpath === path)
}

/**
 * The recursive tree, built from the flat relpaths. Folders first, then files, each sorted by name —
 * and every folder starts closed; the map only remembers what a person opened or what holds the file
 * being edited.
 */
function buildTree(
  includeLocalFolders = true,
  includeFile: (relpath: string) => boolean = () => true,
): Node[] {
  const root: Node = { name: '', path: '', dir: true, children: [] }
  const byPath: Record<string, Node> = {}
  const ensureDir = (path: string): Node => {
    const parts = path.split('/')
    let parent = root
    for (let index = 0; index < parts.length; index += 1) {
      const dirPath = parts.slice(0, index + 1).join('/')
      let node = byPath[dirPath]
      if (node === undefined) {
        node = { name: parts[index] as string, path: dirPath, dir: true, children: [] }
        byPath[dirPath] = node
        parent.children.push(node)
      }
      parent = node
    }
    return parent
  }
  if (includeLocalFolders) {
    for (const folder of localFolders) ensureDir(folder)
  }
  for (const file of files.value) {
    if (!includeFile(file.relpath)) continue
    const parts = file.relpath.split('/')
    const parent = parts.length > 1 ? ensureDir(parts.slice(0, -1).join('/')) : root
    parent.children.push({
      name: parts[parts.length - 1] as string,
      path: file.relpath,
      dir: false,
      children: [],
    })
  }
  const sort = (nodes: Node[]): void => {
    nodes.sort((a, b) => (a.dir === b.dir ? a.name.localeCompare(b.name) : a.dir ? -1 : 1))
    for (const node of nodes) if (node.dir) sort(node.children)
  }
  sort(root.children)
  return root.children
}

function ancestors(path: string): string[] {
  const parts = path.split('/')
  const out: string[] = []
  for (let index = 1; index < parts.length; index += 1) out.push(parts.slice(0, index).join('/'))
  return out
}

function expandAncestors(path: string): void {
  for (const folder of ancestors(path)) opened[folder] = true
}

function toggleFolder(path: string): void {
  opened[path] = !opened[path]
}

function openFile(path: string): void {
  if (!fileExists(path)) return
  current.value = path
  expandAncestors(path)
  closeMenu()
  caret.value = { line: 1, column: 1 }
  void ensureText(path).then(() => {
    // The read may finish after the reader has moved on; a stale reset would move their caret.
    if (current.value !== path || currentBinary.value) return
    void nextTick(() => {
      if (current.value !== path) return
      const element = textarea.value
      if (element === null) return
      element.setSelectionRange(0, 0)
      onCaret()
    })
  })
}

async function ensureText(path: string): Promise<void> {
  if (buffers[path] !== undefined) return
  const file = files.value.find((candidate) => candidate.relpath === path)
  if (file === undefined || file.is_binary) return
  const result = await getSkillFile(props.namespace, props.name, readSuffix.value, path)
  if (!result.ok) {
    // A read that failed is not a save that failed; the banner says which.
    showFailure(result.code, result.message, '没能打开这个文件。')
    return
  }
  // The textarea is editable while the read is in flight. Text typed into it is the reader's:
  // keep it as the buffer (which marks the file dirty) and record the server's copy as saved.
  saved[path] = result.data
  if (buffers[path] === undefined) buffers[path] = result.data
}

/* ------------------------------------------------------------------ labels */

function clampPoints(value: string, max: number): string {
  return Array.from(value).slice(0, max).join('')
}

function onNameInput(event: Event): void {
  const input = event.target as HTMLInputElement
  const value = clampPoints(input.value, MAX_NAME)
  // The DOM is written too, not only the model: once the model is already at 40, one more
  // character clamps to the same string, the ref assignment is a no-op, and no re-render puts the
  // field back to 40 — the 41st would stay visible (design: 第 41 个字符进不去).
  if (input.value !== value) input.value = value
  draftName.value = value
  labelStatus.value = null
}

function onNoteInput(event: Event): void {
  const input = event.target as HTMLInputElement
  const value = clampPoints(input.value, MAX_NOTE)
  if (input.value !== value) input.value = value
  draftNote.value = value
  labelStatus.value = null
}

async function commitName(): Promise<void> {
  if (isNew.value) return
  const value = clampPoints(draftName.value.trim(), MAX_NAME)
  if (value === '') {
    draftName.value = savedName.value
    labelStatus.value = { text: '版本名称不能为空，已恢复原名称。', danger: true }
    return
  }
  if (value === savedName.value) {
    draftName.value = savedName.value
    return
  }
  const version = currentVersion.value
  if (version === null) return
  const result = await updateDraftName(props.namespace, props.name, versionSuffix(version), value)
  if (result.ok) {
    draftName.value = value
    savedName.value = value
    labelStatus.value = { text: '版本名称已保存。', danger: false }
  } else {
    // The typed value stays: a failed write is not a reason to throw away what somebody wrote.
    // Only an empty name is restored, because the server would refuse a draft without one.
    labelStatus.value = { text: codeMessage(result.code, result.message), danger: true }
  }
}

async function commitNote(): Promise<void> {
  if (isNew.value) return
  const value = clampPoints(draftNote.value.trim(), MAX_NOTE)
  if (value === savedNote.value) {
    draftNote.value = savedNote.value
    return
  }
  const version = currentVersion.value
  if (version === null) return
  const result = await updateDraftNote(props.namespace, props.name, versionSuffix(version), value)
  if (result.ok) {
    draftNote.value = value
    savedNote.value = value
    labelStatus.value = { text: '说明已保存。', danger: false }
  } else {
    // Kept, so losing the network does not lose the sentence; a second blur retries it.
    labelStatus.value = { text: codeMessage(result.code, result.message), danger: true }
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
  if (!isNew.value) return true
  if (draftName.value.trim() !== '') return true
  labelStatus.value = { text: '请先填写版本名称。', danger: true }
  void nextTick(() => (document.getElementById('edName') as HTMLInputElement | null)?.focus())
  return false
}

/* ------------------------------------------------------------------ writes */

async function save(): Promise<void> {
  if (!canSave.value) return
  await send({ op: 'write', path: current.value, text: currentText.value })
}

async function send(edit: DraftEditBody): Promise<boolean> {
  // A second Enter while the first request is in flight would create twice or save against a
  // baseline the first answer is about to replace. One request at a time.
  if (saving.value) return false
  if (!requireNameForDraft()) return false
  saving.value = true
  errorBanner.value = null
  const result = isNew.value
    ? await createDraft(props.namespace, props.name, {
        name: draftName.value.trim(),
        note: draftNote.value.trim(),
        ...(baseFrom.value === undefined ? {} : { from: baseFrom.value }),
        ...(baseDigest.value === null ? {} : { from_digest: baseDigest.value }),
        edit,
      })
    : await editDraft(props.namespace, props.name, readSuffix.value as string, {
        base_digest: baseDigest.value ?? '',
        ...edit,
      })
  saving.value = false
  if (!result.ok) {
    handleFailure(result, edit)
    return false
  }
  adopt(result.data.version, result.data.files, edit)
  return true
}

function adopt(version: AuthoredVersion, nextFiles: AuthoredFile[], edit: DraftEditBody): void {
  const wasNew = isNew.value
  currentVersion.value = version
  isNew.value = false
  baseDigest.value = version.digest
  baseFrom.value = versionSuffix(version)
  // **Saved means what the server holds**, not what the fields hold: a body save carries no
  // labels, so a name typed but not yet blurred must stay dirty and be PATCHed by its blur.
  savedName.value = version.draft_name ?? ''
  savedNote.value = version.note ?? ''
  files.value = nextFiles
  if (edit.op === 'write' && edit.path !== undefined) {
    // What the server has is `sent`; what is in the field may have grown while the request was in
    // flight. Recording `sent` as saved keeps that typing dirty instead of reverting it — and the
    // buffer itself is left exactly as the field has it.
    saved[edit.path] = edit.text ?? ''
  } else if (edit.op === 'delete' && edit.path !== undefined) {
    delete buffers[edit.path]
    delete saved[edit.path]
    if (current.value === edit.path) selectFirstFile()
  } else if (edit.op === 'rename' && edit.from !== undefined && edit.to !== undefined) {
    if (buffers[edit.from] !== undefined) {
      buffers[edit.to] = buffers[edit.from] as string
      delete buffers[edit.from]
    }
    if (saved[edit.from] !== undefined) {
      saved[edit.to] = saved[edit.from] as string
      delete saved[edit.from]
    }
    if (current.value === edit.from) current.value = edit.to
  }
  conflict.value = false
  pendingEdit.value = null
  duplicate.value = null
  errorBanner.value = null
  replaceAddress()
  if (wasNew) {
    // Until the row existed, both label writes returned early (`isNew`), so a name or note typed
    // while the create request was in flight was never sent. Send it now that there is a row.
    if (draftName.value.trim() !== savedName.value) void commitName()
    if (draftNote.value.trim() !== savedNote.value) void commitNote()
  }
}

function handleFailure(
  result: Extract<ApiResult<unknown>, { ok: false }>,
  edit: DraftEditBody,
): void {
  if (result.code === 'digest_mismatch') {
    pendingEdit.value = edit
    conflict.value = true
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
    const detail = (field: string) => result.details.find((item) => item.field === field)?.issue
    const state = detail('state')
    const code = detail('code')
    const pin = detail('pin')
    if (state === 'published' && pin !== undefined && pin !== '') {
      duplicate.value = {
        title: `该内容已属于 @${pin}。`,
        label: `查看 @${pin}`,
        href: versionHref(pin),
      }
    } else if (state === 'draft' && code !== undefined && code !== '') {
      // The design draws this banner for a published version and for a draft. A discarded row also
      // occupies the digest, but the design has no word for it and its page may not be readable —
      // it gets the neutral sentence and the version list rather than being called a draft.
      duplicate.value = {
        title: `该内容已属于草稿 ${code}。`,
        label: `查看 ${code}`,
        href: versionHref(code),
      }
    } else {
      duplicate.value = {
        title: '该内容已属于另一版。',
        label: '查看版本列表',
        href: versionsHref.value,
      }
    }
    return
  }
  showFailure(result.code, result.message)
}

function showFailure(code: string, message: string, title = '保存失败。'): void {
  errorTitle.value = title
  // 「修改后可再次保存。」 is advice about a save; a failed read has nothing to save again.
  const advice = title === '保存失败。' ? '修改后可再次保存。' : ''
  errorBanner.value = `${codeMessage(code, message)}${advice}`
}

/** The draft's address is born with the row: `…@<version code>/edit`, then the file it was on. */
function replaceAddress(): void {
  if (currentVersion.value === null) return
  const url = `${skillHref.value}@${encodeURIComponent(versionSuffix(currentVersion.value))}/edit?file=${encodeURIComponent(current.value)}`
  window.history.replaceState(null, '', url)
}

function selectFirstFile(): void {
  const first = files.value.find((file) => file.relpath === 'SKILL.md') ?? files.value[0]
  if (first !== undefined) {
    current.value = first.relpath
    void ensureText(first.relpath)
  }
}

/* ------------------------------------------------------------------ conflict */

/** Set when this page itself is deliberately leaving, so the native prompt does not ask again. */
let leavingPage = false

/** 重新加载 discards this page's unsaved work — the address already names what to read. */
function reload(): void {
  leavingPage = true
  window.location.reload()
}

/**
 * The same re-read without leaving the page: the manifest and the baseline move, files this page
 * no longer has are dropped, and every other buffer — including unsaved text — stays.
 */
async function refreshDraftInPlace(): Promise<void> {
  const result = await getSkill(props.namespace, props.name, readSuffix.value)
  if (!result.ok) {
    // A failed re-read must not become a reload: reload() sets `leavingPage` and throws away every
    // buffer, which is exactly what this path exists to avoid. Leave the page where it is — the
    // stale baseline meets the next save as an ordinary conflict banner, which offers 重新加载.
    showFailure(result.code, result.message, '没能重新读取这一版。')
    return
  }
  if (result.data.version === null) {
    // Nothing left to edit. Reloading is only safe when there is nothing unsaved to lose.
    if (dirtyPaths.value.length === 0) {
      reload()
      return
    }
    showFailure('', '这一版现在读不到了。', '没能重新读取这一版。')
    return
  }
  currentVersion.value = result.data.version
  baseDigest.value = result.data.version.digest
  files.value = result.data.files
  if (isNew.value) baseCount.value = files.value.length
  for (const path of Object.keys(buffers)) {
    if (!files.value.some((file) => file.relpath === path)) {
      delete buffers[path]
      delete saved[path]
    }
  }
  // The baseline moved, so every clean buffer that is still here is one write behind — the same
  // moment `refreshStaleBuffers` exists for after an overwrite. Unsaved text is left alone.
  await refreshStaleBuffers(undefined)
  if (!files.value.some((file) => file.relpath === current.value)) selectFirstFile()
}

/**
 * A successful overwrite is the one moment another writer's bytes really are in this page's base.
 * A file that was opened but not edited is now stale, and saving it later would use the new digest
 * and quietly revert the other writer. Re-read those; a file with local unsaved text is left alone —
 * it is the user's own content, and dropping it is worse than a visible conflict.
 */
async function refreshStaleBuffers(except: string | undefined): Promise<void> {
  const suffix = readSuffix.value
  for (const path of Object.keys(buffers)) {
    if (path === except) continue
    if (buffers[path] !== saved[path]) continue
    const file = files.value.find((candidate) => candidate.relpath === path)
    if (file === undefined || file.is_binary) continue
    const baseline = saved[path]
    const result = await getSkillFile(props.namespace, props.name, suffix, path)
    // Checked again after the await: text typed, or a save completed, while the re-read was in
    // flight is not what this copy describes, and must not be replaced by it.
    if (buffers[path] !== baseline || saved[path] !== baseline) continue
    if (!files.value.some((candidate) => candidate.relpath === path)) {
      // Removed while the read was in flight; there is nothing here to keep.
      delete buffers[path]
      delete saved[path]
      continue
    }
    if (!result.ok) {
      // A clean stale buffer that cannot be refreshed is dropped, so opening it reads the server
      // rather than keeping bytes that would revert the other writer on the next save.
      delete buffers[path]
      delete saved[path]
      showFailure(result.code, result.message, '没能打开这个文件。')
      if (path === current.value) void ensureText(path)
      continue
    }
    buffers[path] = result.data
    saved[path] = result.data
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
  const edit = pendingEdit.value
  if (edit === null) {
    reload()
    return
  }
  conflict.value = false
  if (baseFrom.value !== undefined) {
    const latest = await getSkill(props.namespace, props.name, baseFrom.value)
    if (latest.ok && latest.data.version !== null) {
      baseDigest.value = latest.data.version.digest
    }
  }
  pendingEdit.value = null
  // 覆盖重写**当前**缓冲区，而不是失败那一刻的文本：冲突之后继续输入的文字必须算在这次写入里
  // (PRD #10: 「覆盖」用本地内容按最新基线再写一次).
  const replayed: DraftEditBody =
    edit.op === 'write' && edit.path !== undefined
      ? { op: 'write', path: edit.path, text: buffers[edit.path] ?? edit.text ?? '' }
      : edit
  const sent = await send(replayed)
  if (sent) {
    await refreshStaleBuffers(replayed.op === 'write' ? replayed.path : undefined)
  }
}

/* ------------------------------------------------------------------ tree actions */

async function toggleMenu(event: MouseEvent, path: string): Promise<void> {
  if (menu.value?.path === path) {
    closeMenu()
    return
  }
  const anchor = event.currentTarget as HTMLElement
  menu.value = { path, style: { left: '0px', top: '0px' } }
  await nextTick()
  const pop = document.querySelector<HTMLElement>('.editor-file-pop')
  if (pop === null) return
  const box = anchor.getBoundingClientRect()
  const width = pop.offsetWidth
  const height = pop.offsetHeight
  const left = Math.round(Math.max(8, Math.min(box.right - width, window.innerWidth - width - 8)))
  const below = box.bottom + 6
  const top = Math.round(below + height > window.innerHeight - 8 ? box.top - 6 - height : below)
  menu.value.style = { left: `${left}px`, top: `${top}px` }
  await nextTick()
  pop.querySelector<HTMLButtonElement>('.menu-item')?.focus()
}

function closeMenu(focusOpener = false): void {
  const path = menu.value?.path
  menu.value = null
  if (focusOpener && path !== undefined) {
    void nextTick(() => menuButton(path)?.focus())
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
  create.value = { kind, value: '', error: null }
  query.value = ''
  void nextTick(() => document.querySelector<HTMLInputElement>('.editor-create-input')?.focus())
}

function cancelCreate(): void {
  const kind = create.value?.kind
  create.value = null
  void nextTick(() =>
    document.getElementById(kind === 'dir' ? 'edNewFolder' : 'edNewFile')?.focus(),
  )
}

function validatePath(path: string): string | null {
  if (path === '') return '路径不能为空。'
  if (path.startsWith('/')) return '路径要从 skill 根目录开始，不能以 / 开头。'
  if (path.endsWith('/')) return '路径不能以 / 结尾。'
  if (path.includes('\\')) return '路径要用 / 分隔。'
  if (path.split('/').some((part) => part === '' || part === '..')) {
    return '路径里不能有空的一段或 ..。'
  }
  return null
}

function createPathError(path: string): string | null {
  const issue = validatePath(path)
  if (issue !== null) return issue
  if (create.value?.kind === 'file') {
    if (fileExists(path)) return '该路径已存在文件。'
    if (folderExists(path)) return '该路径已是文件夹。'
    if (files.value.some((file) => path.startsWith(`${file.relpath}/`))) {
      return '该路径已是文件。'
    }
  } else {
    if (fileExists(path)) return '该路径已是文件。'
    if (files.value.some((file) => path.startsWith(`${file.relpath}/`))) {
      return '该路径已是文件。'
    }
    if (folderExists(path)) return '该文件夹已存在。'
  }
  return null
}

async function createItem(): Promise<void> {
  const creating = create.value
  if (creating === null) return
  const path = creating.value.trim()
  const issue = createPathError(path)
  if (issue !== null) {
    creating.error = issue
    return
  }
  if (creating.kind === 'dir') {
    localFolders.add(path)
    expandAncestors(path)
    create.value = null
    void nextTick(() => {
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
  create.value = null
  openFile(path)
  void nextTick(() => textarea.value?.focus())
}

function beginRename(path: string): void {
  closeMenu()
  expandAncestors(path)
  rename.value = { path, value: path, error: null }
  void nextTick(() => {
    const input = document.querySelector<HTMLInputElement>('.editor-rename-input')
    input?.focus()
    input?.setSelectionRange(input.value.length, input.value.length)
  })
}

function cancelRename(): void {
  const path = rename.value?.path
  rename.value = null
  if (path !== undefined) void nextTick(() => menuButton(path)?.focus())
}

async function commitRename(): Promise<void> {
  const renaming = rename.value
  if (renaming === null) return
  const from = renaming.path
  const to = renaming.value.trim()
  if (to === from) {
    rename.value = null
    return
  }
  const issue =
    validatePath(to) ??
    (fileExists(to)
      ? '该路径已存在文件。'
      : folderExists(to)
        ? '该路径已是文件夹。'
        : files.value.some((file) => to.startsWith(`${file.relpath}/`))
          ? '该路径已是文件。'
          : null)
  if (issue !== null) {
    renaming.error = issue
    return
  }
  if (!requireNameForDraft()) return
  const ok = await send({ op: 'rename', from, to })
  if (ok) {
    // The file is now somewhere else; a collapsed destination would hide the row the editor just
    // switched to.
    expandAncestors(to)
    rename.value = null
  }
}

/** The button the sheet was opened from — where 取消 puts the focus back (design: returnFocus). */
let deleteOpener: HTMLElement | null = null
let deleteFolderOpener: HTMLElement | null = null

function askDelete(path: string): void {
  const opener = menuButton(path)
  closeMenu()
  if (!requireNameForDraft()) return
  deleteOpener = opener
  deleteTarget.value = path
}

function cancelDelete(): void {
  const opener = deleteOpener
  deleteOpener = null
  deleteTarget.value = null
  void nextTick(() => opener?.focus())
}

async function confirmDelete(): Promise<void> {
  const path = deleteTarget.value
  deleteOpener = null
  deleteTarget.value = null
  if (path === null) return
  await send({ op: 'delete', path })
}

function askDeleteFolder(path: string): void {
  deleteFolderOpener = menuButton(path)
  closeMenu()
  deleteFolderTarget.value = path
}

function cancelDeleteFolder(): void {
  const opener = deleteFolderOpener
  deleteFolderOpener = null
  deleteFolderTarget.value = null
  void nextTick(() => opener?.focus())
}

function confirmDeleteFolder(): void {
  const path = deleteFolderTarget.value
  deleteFolderOpener = null
  deleteFolderTarget.value = null
  if (path === null) return
  for (const key of Array.from(localFolders)) {
    if (key === path || key.startsWith(`${path}/`)) localFolders.delete(key)
  }
}

/* ------------------------------------------------------------------ leaving */

function guardLeave(event: MouseEvent, href: string, opener: EventTarget | null): void {
  if (dirtyPaths.value.length === 0) return
  event.preventDefault()
  leaveTarget.value = { href, opener: opener instanceof HTMLElement ? opener : null }
}

function leave(): void {
  const target = leaveTarget.value
  leaveTarget.value = null
  if (target !== null) {
    leavingPage = true
    window.location.assign(target.href)
  }
}

function cancelLeave(): void {
  const opener = leaveTarget.value?.opener ?? null
  leaveTarget.value = null
  void nextTick(() => opener?.focus())
}

function onBeforeUnload(event: BeforeUnloadEvent): void {
  // One question, not two: an action the page itself confirmed (重新加载 / 放弃并离开) has already
  // asked, and the native prompt on top of it can be cancelled, which would abort that action.
  if (leavingPage || dirtyPaths.value.length === 0) return
  event.preventDefault()
  event.returnValue = ''
}

/* ------------------------------------------------------------------ keyboard */

function onKeydown(event: KeyboardEvent): void {
  const dialogOpen =
    deleteTarget.value !== null || deleteFolderTarget.value !== null || leaveTarget.value !== null
  if (dialogOpen) return
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    if (canSave.value) void save()
    return
  }
  if (event.key !== 'Escape') return
  // The menu's dismissal is the document-level one (it is a fixed popup, not a row); rename and
  // create are inputs and own their own Esc, so pressing Esc elsewhere does not tear them down.
  if (menu.value !== null) {
    event.preventDefault()
    closeMenu(true)
  }
}

function onDocumentMouseDown(event: MouseEvent): void {
  if (menu.value === null) return
  const target = event.target as Element | null
  if (target === null) return
  if (target.closest('.editor-file-pop') !== null || target.closest('[data-menu]') !== null) return
  closeMenu()
}

function onTextKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Tab') return
  // Tab is indentation here, not a way out; two spaces, matching the design's own editor.
  event.preventDefault()
  const element = textarea.value
  if (element === null) return
  element.setRangeText('  ', element.selectionStart, element.selectionEnd, 'end')
  buffers[current.value] = element.value
  onCaret()
}

function onTextInput(event: Event): void {
  buffers[current.value] = (event.target as HTMLTextAreaElement).value
  onCaret()
}

function onTextScroll(): void {
  scrollTop.value = textarea.value?.scrollTop ?? 0
  if (gutter.value !== null) gutter.value.scrollTop = scrollTop.value
}

function onCaret(): void {
  const element = textarea.value
  if (element === null) return
  const before = element.value.slice(0, element.selectionStart)
  const lines = before.split('\n')
  caret.value = {
    line: lines.length,
    column: (lines[lines.length - 1] ?? '').length + 1,
  }
}

function onSearchKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape') return
  event.preventDefault()
  query.value = ''
}

function clearSearch(): void {
  query.value = ''
  void nextTick(() => document.getElementById('edFileQuery')?.focus())
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
  if (currentBinary.value) {
    return currentFile.value === null ? '' : byteLabel(currentFile.value.size)
  }
  return `${sizeLabel(currentText.value)} · UTF-8`
}

/**
 * The **server's** file address, not the page's.
 *
 * `下载文件` has to return the bytes, and `/skills/.../files/...` is the SPA's file tab — for a
 * binary it renders a sentence telling the reader to use the CLI. The raw endpoint lives on the
 * session plane, one segment per path.
 */
function fileHref(path: string): string {
  const suffix = readSuffix.value === undefined ? '' : `@${encodeURIComponent(readSuffix.value)}`
  return `/web/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}${suffix}/files/${path.split('/').map(encodeURIComponent).join('/')}`
}

/* ------------------------------------------------------------------ load */

async function load(): Promise<void> {
  loading.value = true
  missing.value = false
  const result =
    props.mode === 'new'
      ? await getSkill(props.namespace, props.name)
      : await getSkill(props.namespace, props.name, props.version)
  if (!result.ok || !canWrite(result.data.rung)) {
    loading.value = false
    missing.value = true
    return
  }
  const version = result.data.version
  if (props.mode === 'edit') {
    if (
      version === null ||
      !canEditDraft(version, result.data.rung, account.value?.username ?? null)
    ) {
      loading.value = false
      missing.value = true
      return
    }
    skill.value = result.data
    adoptLoaded(result.data, version, false)
  } else if (version !== null && (version.state === 'published' || version.state === 'draft')) {
    // Live first, else the newest draft this caller may see — the read's own rule (v8 TD §读).
    skill.value = result.data
    adoptLoaded(result.data, version, true)
  } else {
    // Nothing readable is a base: the skill row with no version selected is the empty base.
    const blank = await getBlankSkill(props.namespace, props.name)
    if (!blank.ok) {
      loading.value = false
      missing.value = true
      return
    }
    skill.value = blank.data
    currentVersion.value = null
    isNew.value = true
    baseDigest.value = null
    baseFrom.value = undefined
    baseLabel.value = '空白底稿'
    baseCount.value = 1
    const text = blankTemplate(blank.data)
    files.value = [
      {
        relpath: 'SKILL.md',
        sha256: '',
        size: new TextEncoder().encode(text).length,
        is_binary: false,
      },
    ]
    buffers['SKILL.md'] = text
    saved['SKILL.md'] = text
    current.value = 'SKILL.md'
  }

  const requested = props.file ?? ''
  const open =
    requested !== '' && fileExists(requested)
      ? requested
      : files.value.some((file) => file.relpath === 'SKILL.md')
        ? 'SKILL.md'
        : (files.value[0]?.relpath ?? 'SKILL.md')
  current.value = open
  await ensureText(open)
  expandAncestors(open)
  loading.value = false
}

function adoptLoaded(data: AuthoredSkill, version: AuthoredVersion, newVersion: boolean): void {
  currentVersion.value = version
  isNew.value = newVersion
  baseDigest.value = version.digest
  baseFrom.value = versionSuffix(version)
  baseCount.value = data.files.length
  if (newVersion) {
    baseLabel.value =
      version.state === 'draft'
        ? `基于草稿 ${versionSuffix(version)}　·　提交人 ${version.submitted_by ?? ''}`
        : `基于线上版本 @${version.name ?? versionSuffix(version)}`
  }
  files.value = data.files
  draftName.value = newVersion ? '' : (version.draft_name ?? '')
  draftNote.value = newVersion ? '' : (version.note ?? '')
  savedName.value = draftName.value
  savedNote.value = draftNote.value
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
  if (workspace.value === null) return
  const open = [conflictBanner.value, duplicateBanner.value, errorElement.value].find(
    (banner) => banner !== null && banner.offsetParent !== null,
  )
  const height =
    open === undefined || open === null
      ? 0
      : open.getBoundingClientRect().height +
        (Number.parseFloat(getComputedStyle(open).marginTop) || 0)
  workspace.value.style.setProperty('--editor-banner', `${height}px`)
}

watch([conflict, duplicate, errorBanner, labelStatus], () => {
  void nextTick(syncBannerHeight)
})

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
  document.addEventListener('mousedown', onDocumentMouseDown, true)
  window.addEventListener('beforeunload', onBeforeUnload)
  window.addEventListener('resize', syncBannerHeight)
  if (window.innerWidth <= 900) treeCollapsed.value = true
  void load().then(() => syncBannerHeight())
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  document.removeEventListener('mousedown', onDocumentMouseDown, true)
  window.removeEventListener('beforeunload', onBeforeUnload)
  window.removeEventListener('resize', syncBannerHeight)
})
</script>

<template>
  <section class="page" id="page-editor">
    <nav class="crumbs" aria-label="面包屑">
      <a
        :href="versionsHref"
        id="edBackCrumb"
        @click="guardLeave($event, versionsHref, $event.currentTarget)"
        >版本</a
      >
      <span class="crumbs-sep">/</span>
      <span class="crumbs-here">{{ isNew ? '新版本' : '编辑' }}</span>
    </nav>

    <header v-show="!loading && !missing" class="vp-head editor-head">
      <h1 class="visually-hidden">编辑版本</h1>
      <div class="editor-head-top">
        <div class="vp-title-row">
          <span class="vp-title-name">
            <input
              class="editor-name-input"
              id="edName"
              type="text"
              placeholder="版本名称"
              aria-label="版本名称（最多 40 个字符）"
              title="最多 40 个字符；中文、英文、数字与符号都可以"
              autocomplete="off"
              spellcheck="false"
              :value="draftName"
              @input="onNameInput"
              @blur="commitName"
              @keydown.enter.prevent="onNameEnter"
            />
          </span>
          <span class="version-states" id="edStates">
            <span v-if="isNew" class="chip is-plain"><i class="dot" /><span class="chip-text">尚未保存</span></span>
            <span v-else class="chip is-draft"><i class="dot" /><span class="chip-text">草稿</span></span>
          </span>
        </div>
        <div class="vp-head-act">
          <a
            v-if="!isNew"
            class="button is-quiet"
            id="edView"
            :href="viewHref"
            @click="guardLeave($event, viewHref, $event.currentTarget)"
            >查看版本详情</a
          >
          <button type="button" class="button" id="edSave" :disabled="!canSave" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
      <div class="editor-label-row">
        <label class="editor-label" for="edNote">说明</label>
        <input
          class="control"
          id="edNote"
          type="text"
          placeholder="说明（可选）"
          aria-label="说明"
          autocomplete="off"
          spellcheck="false"
          :value="draftNote"
          @input="onNoteInput"
          @blur="commitNote"
          @keydown.enter.prevent="onNoteEnter"
        />
      </div>
      <p v-if="labelStatus !== null" class="editor-label-status" :class="{ 'is-danger': labelStatus.danger }" id="edLabelStatus">{{ labelStatus.text }}</p>
      <p class="editor-facts">
        <span class="vp-meta" id="edMeta">{{ facts }}</span>
        <span v-if="isNew" class="editor-hint" id="edHint">首次内容改动时创建版本，随后显示在版本列表中。</span>
      </p>
    </header>

    <div
      v-if="conflict && !loading && !missing"
      ref="conflictBanner"
      class="editor-banner is-danger"
      id="edConflict"
    >
      <div class="editor-banner-text">
        <span class="editor-banner-title">该版本在编辑期间已被修改。</span>
        <span class="editor-banner-note">重新加载将丢弃此页未保存的改动；覆盖将用当前内容重写。</span>
      </div>
      <div class="editor-banner-acts">
        <button type="button" class="button is-quiet" id="edReload" @click="reload">重新加载</button>
        <button type="button" class="button" id="edOverwrite" @click="overwrite">覆盖</button>
      </div>
    </div>
    <div
      v-if="duplicate !== null && !loading && !missing"
      ref="duplicateBanner"
      class="editor-banner"
      id="edDuplicate"
    >
      <div class="editor-banner-text">
        <span class="editor-banner-title" id="edDuplicateTitle">{{ duplicate.title }}</span>
        <span class="editor-banner-note">同一 skill 下不能存在相同内容。请修改后保存，或查看该版本。</span>
      </div>
      <div class="editor-banner-acts">
        <a
          class="button is-quiet"
          id="edDuplicateLink"
          :href="duplicate.href"
          @click="guardLeave($event, duplicate.href, $event.currentTarget)"
          >{{ duplicate.label }}</a
        >
      </div>
    </div>
    <div
      v-if="errorBanner !== null && !loading && !missing"
      ref="errorElement"
      class="editor-banner is-danger"
      id="edError"
    >
      <div class="editor-banner-text">
        <span class="editor-banner-title" id="edErrorTitle">{{ errorTitle }}</span>
        <span class="editor-banner-note" id="edErrorText">{{ errorBanner }}</span>
      </div>
      <div class="editor-banner-acts">
        <button type="button" class="link-button" id="edErrorDismiss" @click="errorBanner = null">
          关闭
        </button>
      </div>
    </div>

    <div v-show="!loading && !missing" ref="workspace" class="editor-workspace" id="edWorkspace">
      <aside class="editor-tree" :class="{ 'is-collapsed': treeCollapsed }" aria-label="文件">
        <button
          type="button"
          class="editor-tree-toggle"
          id="edTreeToggle"
          :aria-expanded="!treeCollapsed"
          aria-controls="edFiles"
          @click="treeCollapsed = !treeCollapsed"
        >
          文件 <span class="count" id="edTreeToggleCount">{{ files.length }}</span>
          <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M4 6l4 4 4-4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
        <div class="editor-tree-head">
          <span class="editor-tree-title">文件 <span class="count" id="edFileCount">{{ files.length }}</span></span>
          <span class="editor-tree-acts">
            <button
              type="button"
              class="editor-icon-button"
              id="edNewFile"
              aria-label="新建文件"
              title="新建文件"
              @click="startCreate('file')"
            >
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M6 3.5h7L17.5 8v5.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M13 3.5V8h4.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M6 3.5V20a1 1 0 0 0 1 1h6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M18 15v6M15 18h6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
              </svg>
            </button>
            <button
              type="button"
              class="editor-icon-button"
              id="edNewFolder"
              aria-label="新建文件夹"
              title="新建文件夹"
              @click="startCreate('dir')"
            >
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M3 7a2 2 0 0 1 2-2h4l2 2h4a2 2 0 0 1 2 2v4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M3 7v10a2 2 0 0 0 2 2h8" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M18 15v6M15 18h6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
              </svg>
            </button>
          </span>
        </div>
        <label class="search editor-search">
          <span class="visually-hidden">搜索文件</span>
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" stroke-width="1.8" />
            <line x1="15.4" y1="15.4" x2="20.6" y2="20.6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          </svg>
          <input
            class="control"
            id="edFileQuery"
            type="search"
            autocomplete="off"
            spellcheck="false"
            aria-label="搜索文件路径"
            placeholder="搜索文件"
            :value="query"
            @input="query = ($event.target as HTMLInputElement).value"
            @keydown="onSearchKeydown"
          />
          <button
            v-show="query !== ''"
            type="button"
            class="search-clear"
            id="edFileClear"
            aria-label="清除搜索"
            @click="clearSearch"
          >
            ×
          </button>
        </label>
        <ul class="editor-files" id="edFiles">
          <li v-if="create !== null" class="editor-file is-creating" :data-kind="create.kind">
            <div class="editor-file-open is-creating" style="padding-left: 6px">
              <span class="editor-file-chevron is-empty" aria-hidden="true" />
              <svg v-if="create.kind === 'dir'" class="editor-file-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
              </svg>
              <svg v-else class="editor-file-icon" viewBox="0 0 24 24" aria-hidden="true"><use href="#fileMark" /></svg>
              <input
                class="editor-create-input"
                type="text"
                autocomplete="off"
                spellcheck="false"
                :value="create.value"
                :placeholder="create.kind === 'dir' ? '文件夹名或路径' : '文件名或路径'"
                :aria-label="create.kind === 'dir' ? '新建文件夹的路径' : '新建文件的路径'"
                @input="create.value = ($event.target as HTMLInputElement).value; create.error = null"
                @keydown.enter.prevent="createItem"
                @keydown.esc.prevent="cancelCreate"
              />
            </div>
            <p class="editor-create-hint">按 Enter 创建，按 Esc 取消</p>
            <p v-if="create.error !== null" class="editor-field-error" role="alert">{{ create.error }}</p>
          </li>
          <li
            v-for="row in rows"
            :key="row.path"
            class="editor-file"
            :class="{ 'is-dir': row.dir, 'is-current': !row.dir && current === row.path, 'is-editing': rename !== null && rename.path === row.path }"
            :data-path="row.path"
            :data-kind="row.dir ? 'dir' : 'file'"
          >
            <template v-if="rename !== null && rename.path === row.path">
              <input
                class="editor-rename-input"
                type="text"
                aria-label="新路径"
                :value="rename.value"
                @input="rename.value = ($event.target as HTMLInputElement).value; rename.error = null"
                @keydown.enter.prevent="commitRename"
                @keydown.esc.prevent="cancelRename"
              />
              <span class="editor-rename-acts">
                <button type="button" class="link-button" @click="commitRename">重命名</button>
                <button type="button" class="link-button" @click="cancelRename">取消</button>
              </span>
              <p v-if="rename.error !== null" class="editor-field-error">{{ rename.error }}</p>
            </template>
            <template v-else>
              <button
                type="button"
                class="editor-file-open"
                :style="{ paddingLeft: `${6 + row.depth * 14}px` }"
                :aria-expanded="row.dir ? row.expanded : undefined"
                :aria-label="row.dir ? `${row.expanded ? '收起' : '展开'} ${row.path}` : `打开 ${row.path}`"
                @click="row.dir ? toggleFolder(row.path) : openFile(row.path)"
              >
                <svg
                  v-if="row.dir"
                  class="editor-file-chevron"
                  viewBox="0 0 16 16"
                  fill="none"
                  aria-hidden="true"
                >
                  <path d="M6 4l4 4-4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <span v-else class="editor-file-chevron is-empty" aria-hidden="true" />
                <svg v-if="row.dir" class="editor-file-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
                </svg>
                <svg v-else class="editor-file-icon" viewBox="0 0 24 24" aria-hidden="true"><use href="#fileMark" /></svg>
                <span class="editor-file-path">{{ row.name }}</span>
                <span
                  v-if="!row.dir && buffers[row.path] !== saved[row.path]"
                  class="editor-file-dot"
                  title="未保存的改动"
                />
              </button>
              <!-- SKILL.md has no menu: both actions are refusals, and a menu whose every item is
                   disabled is a promise broken twice. A folder with files has none either —
                   deleting it would mean deleting content. -->
              <button
                v-if="row.dir ? row.empty : row.path !== 'SKILL.md'"
                type="button"
                class="editor-file-menu"
                :data-menu="row.path"
                :aria-label="`操作：${row.path}`"
                title="操作"
                @click="toggleMenu($event, row.path)"
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <circle cx="12" cy="5" r="1.7" fill="currentColor" />
                  <circle cx="12" cy="12" r="1.7" fill="currentColor" />
                  <circle cx="12" cy="19" r="1.7" fill="currentColor" />
                </svg>
              </button>
              <div
                v-if="menu !== null && menu.path === row.path"
                class="menu-list editor-file-pop"
                role="menu"
                :aria-label="`操作：${row.path}`"
                :style="menu.style"
              >
                <template v-if="row.dir">
                  <button type="button" class="menu-item" role="menuitem" tabindex="-1" @click="askDeleteFolder(row.path)">
                    删除文件夹
                  </button>
                </template>
                <template v-else>
                  <button type="button" class="menu-item" role="menuitem" tabindex="-1" @click="beginRename(row.path)">
                    重命名
                  </button>
                  <button type="button" class="menu-item" role="menuitem" tabindex="-1" @click="askDelete(row.path)">
                    删除
                  </button>
                </template>
              </div>
            </template>
          </li>
        </ul>
        <p v-show="rows.length === 0 && query !== '' && create === null" class="editor-empty" id="edEmpty">
          没有匹配的文件。
          <button type="button" class="link-button" @click="query = ''">清除搜索</button>
        </p>
      </aside>

      <div class="editor-pane">
        <div class="editor-bar">
          <svg class="editor-bar-icon" viewBox="0 0 24 24" aria-hidden="true"><use href="#fileMark" /></svg>
          <span class="editor-path" id="edPath">{{ current }}</span>
          <span v-show="currentDirty" class="editor-unsaved" id="edUnsaved"><i class="dot" />未保存的改动</span>
        </div>
        <div class="editor-body">
          <div v-show="!currentBinary" ref="gutter" class="editor-gutter" id="edGutter" aria-hidden="true">{{ gutterText }}</div>
          <textarea
            v-show="!currentBinary"
            ref="textarea"
            class="editor-text"
            id="edText"
            spellcheck="false"
            aria-label="文件内容"
            :value="currentText"
            @input="onTextInput"
            @scroll="onTextScroll"
            @keydown="onTextKeydown"
            @keyup="onCaret"
            @click="onCaret"
            @select="onCaret"
          />
          <div v-show="currentBinary" class="editor-binary" id="edBinary">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><use href="#fileMark" /></svg>
            <p><strong>二进制文件不能在此编辑。</strong><br />请下载到本地修改，再通过 CLI 写回。</p>
            <a class="button is-quiet" id="edDownload" :href="fileHref(current)">下载文件</a>
          </div>
        </div>
        <div class="editor-foot">
          <span id="edCursor">{{ currentBinary ? '只读' : `第 ${caret.line} 行，第 ${caret.column} 列` }}</span>
          <span id="edInfo">{{ info() }}</span>
        </div>
      </div>
    </div>

    <div v-show="loading" class="editor-loading" id="edLoading">加载中…</div>
    <div v-show="missing" class="editor-missing" id="edMissing">
      <div class="notfound">
        <h2>没有这一版</h2>
        <p class="hint">它可能已经被丢弃，或者不属于你能编辑的版本。</p>
        <p><a :href="versionsHref">回到版本列表</a></p>
      </div>
    </div>

    <ConfirmDialog
      :open="deleteTarget !== null"
      title="删除文件？"
      :body="`${deleteTarget ?? ''} 将从版本中移除。${deleteTarget !== null && buffers[deleteTarget] !== saved[deleteTarget] ? ' 该文件未保存的改动也会丢失。' : ''}`"
      confirm-label="删除文件"
      @confirm="confirmDelete"
      @cancel="cancelDelete"
    />

    <ConfirmDialog
      :open="deleteFolderTarget !== null"
      title="删除文件夹？"
      :body="`${deleteFolderTarget ?? ''} 是空文件夹，删除不会改变版本内容。`"
      confirm-label="删除文件夹"
      :danger="false"
      @confirm="confirmDeleteFolder"
      @cancel="cancelDeleteFolder"
    />

    <ConfirmDialog
      :open="leaveTarget !== null"
      :title="`有 ${dirtyPaths.length} 个文件未保存`"
      body="离开此页将丢失这些改动。"
      confirm-label="放弃并离开"
      cancel-label="继续编辑"
      @confirm="leave"
      @cancel="cancelLeave"
    />

    <svg style="position: absolute; width: 0; height: 0; overflow: hidden" aria-hidden="true">
      <symbol id="fileMark" viewBox="0 0 24 24" fill="none">
        <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
        <path d="M14 3v5h5" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
      </symbol>
    </svg>
  </section>
</template>
