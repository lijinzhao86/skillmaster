<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { importFailure, type ImportFailure } from '../../api/errors'
import { importSkill, importVersion } from '../../api/skills'
import type { SubmitResponse } from '../../api/types'
import { canMaintain, canWrite } from '../../composables/useVersionActions'

/**
 * The import sheet (design §导入与下载): one file, a version name, an optional note.
 *
 * **The same sheet for both entries.** The version list's 「导入压缩包」 adds a version to this
 * skill; 「我的 skill」's 「导入本地 skill」 brings the package into the caller's own namespace and
 * creates the skill if the name is new. What differs is the title, the introduction and the target,
 * not the table.
 *
 * **The page does not read the archive.** The version name is whatever the person typed — the
 * package's own metadata is not consulted here, by design; a name left blank is refused before the
 * request rather than sent to be refused by the server (design §导入与下载).
 *
 * **「内容已存在」 is not a failure.** It is an idempotent replay: the sheet stays open, says which
 * version already holds this content, and offers a link when this person can read that version —
 * a link to a 404 would be worse than none.
 */
const props = defineProps<{
  open: boolean
  mode: 'version' | 'skill'
  /** The skill an import adds to; unused in `skill` mode, where the package names it. */
  namespace: string
  name: string
  /**
   * The caller's standing on the skill an import adds to — `owner` in `skill` mode, where the
   * package lands in the caller's own namespace. Read by exactly one question: whether a replay's
   * version can be linked to (design §导入与下载: a link to a 404 is worse than none).
   */
  rung: string
}>()

const emit = defineEmits<{ close: []; navigate: [href: string] }>()

const file = ref<File | null>(null)
const fileName = ref('未选择文件')
const versionName = ref('')
const note = ref('')
const pending = ref(false)
const failure = ref<ImportFailure | null>(null)
const result = ref<{ text: string; href: string | null; label: string } | null>(null)
const pickButton = ref<HTMLButtonElement | null>(null)
const input = ref<HTMLInputElement | null>(null)

const title = computed(() => (props.mode === 'version' ? '导入新版本' : '导入 skill'))
const intro = computed(() =>
  props.mode === 'version'
    ? `压缩包里的 SKILL.md 必须声明 name: ${props.name}。导入后是这个 skill 的一版草稿（内容已经存在时不会新建），线上不变。`
    : '压缩包里的 SKILL.md 决定 skill 的名字；同名 skill 会收到下一版。内容已经存在时不会新建。导入后是一版草稿，上线仍在它的版本页。',
)
const hint =
  '只接受 .zip。SKILL.md 要在压缩包根部；所有文件都在同一个顶层文件夹里时，文件夹名要与 SKILL.md 的 name 相同。最多 512 个文件、解压后合计 16 MiB。'
const acceptDisabled = computed(
  () => pending.value || file.value === null || versionName.value.trim() === '',
)

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return
    }
    file.value = null
    fileName.value = '未选择文件'
    versionName.value = ''
    note.value = ''
    failure.value = null
    result.value = null
    pending.value = false
    void nextTick(() => pickButton.value?.focus())
  },
)

function pick(): void {
  input.value?.click()
}

function chosen(event: Event): void {
  const picked = (event.target as HTMLInputElement).files?.[0] ?? null
  file.value = picked
  fileName.value = picked === null ? '未选择文件' : picked.name
  failure.value = null
}

function onNameInput(): void {
  const chars = Array.from(versionName.value)
  if (chars.length > 40) {
    versionName.value = chars.slice(0, 40).join('')
  }
}

// The design caps the note in the same place it caps the name (「至多 200 个码点」), so the field
// stops at the limit instead of handing the server a value it will refuse.
function onNoteInput(): void {
  const chars = Array.from(note.value)
  if (chars.length > 200) {
    note.value = chars.slice(0, 200).join('')
  }
}

function cancel(): void {
  if (!pending.value) {
    emit('close')
  }
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    cancel()
  }
}

function suffixOf(version: SubmitResponse['version']): string | null {
  if (version.state === 'draft') {
    return version.draft_id
  }
  // A published version — and a revoked one, which was published once — keeps its address: the
  // number, or the digest when it never got one. A discarded row is not addressed at all here: it is
  // never linked (see `canReadReplay`).
  if (version.state === 'discarded') {
    return null
  }
  return version.name ?? version.digest
}

/** Whether this caller may open the version a replay landed on — the link's only condition. */
function canReadReplay(version: SubmitResponse['version']): boolean {
  switch (version.state) {
    case 'discarded':
      // Never linked, whoever is looking: the sentence itself says it cannot be read (design).
      return false
    case 'revoked':
      return canMaintain(props.rung)
    case 'draft':
      return canWrite(props.rung)
    default:
      return true
  }
}

async function submit(): Promise<void> {
  if (acceptDisabled.value || file.value === null) {
    return
  }
  pending.value = true
  failure.value = null
  result.value = null
  const answer =
    props.mode === 'version'
      ? await importVersion(props.namespace, props.name, file.value, versionName.value.trim(), note.value.trim())
      : await importSkill(file.value, versionName.value.trim(), note.value.trim())
  pending.value = false

  if (!answer.ok) {
    failure.value = importFailure(answer, 'import')
    return
  }

  const version = answer.data.version
  if (answer.data.created) {
    const suffix = suffixOf(version)
    if (suffix !== null) {
      emit('navigate', versionPage(answer.data.namespace, answer.data.name, suffix))
      return
    }
  }

  const suffix = suffixOf(version)
  if (suffix === null || !canReadReplay(version)) {
    result.value = { text: '这个 skill 已经有一版同样的内容；那一版现在读不到。', href: null, label: '' }
    return
  }
  // **The address is the version's own.** A published version is written `@1.2.3`; a draft is the
  // bare version code, because that is the only spelling a draft answers to (design §导入与下载).
  const address = version.state === 'draft' ? suffix : `@${suffix}`
  result.value = {
    text: `这个 skill 已经有一版同样的内容（${address}）；没有新建。`,
    href: versionPage(answer.data.namespace, answer.data.name, suffix),
    label: `查看 ${address}`,
  }
}

/**
 * A **version page**'s address, which is not `/name@pin`.
 *
 * The path form `/skills/{ns}/{name}@{pin}` selects the skill (and the pin's files); the version as
 * a *record* — title, state, actions, and the 「查看文件」 next to it — is `?tab=versions&version=`.
 * Landing on the path form after an import opened 信息, and the replay link labelled 「查看 @1.2.3」
 * did the same (design §导入与下载: 成功后落到新草稿的版本页).
 */
function versionPage(namespace: string, name: string, pin: string): string {
  return `/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}?tab=versions&version=${encodeURIComponent(pin)}`
}
</script>

<template>
  <div v-if="open" class="confirm" @keydown="onKeydown">
    <div
      class="confirm-sheet"
      role="dialog"
      aria-modal="true"
      aria-labelledby="importTitle"
      aria-describedby="importIntro importHint"
    >
      <h2 id="importTitle" class="confirm-title">{{ title }}</h2>
      <p id="importIntro" class="confirm-body">{{ intro }}</p>

      <div class="import-field">
        <span class="confirm-label">压缩包</span>
        <div class="import-file">
          <input
            ref="input"
            type="file"
            accept=".zip,application/zip"
            hidden
            @change="chosen"
          />
          <button ref="pickButton" type="button" class="button is-quiet" @click="pick">
            选择文件
          </button>
          <span class="import-filename" :class="{ 'is-chosen': file !== null }">{{ fileName }}</span>
        </div>
        <p id="importHint" class="import-hint">{{ hint }}</p>
      </div>

      <label class="confirm-field">
        <span class="confirm-label">版本名称</span>
        <input
          v-model="versionName"
          class="confirm-input"
          type="text"
          autocomplete="off"
          spellcheck="false"
          placeholder="版本名称"
          aria-label="版本名称（最多 40 个字符）"
          :disabled="pending"
          @input="onNameInput"
        />
      </label>
      <label class="confirm-field">
        <span class="confirm-label">说明（可选）</span>
        <input
          v-model="note"
          class="confirm-input"
          type="text"
          autocomplete="off"
          spellcheck="false"
          placeholder="说明（可选）"
          aria-label="说明"
          :disabled="pending"
          @input="onNoteInput"
        />
      </label>

      <p v-if="failure !== null" class="confirm-error" role="alert">
        {{ failure.message }}
        <a v-if="failure.action !== undefined" :href="failure.action.href">{{ failure.action.label }}</a>
      </p>
      <p v-else-if="result !== null" class="confirm-result">
        {{ result.text }}
        <a v-if="result.href !== null" :href="result.href">{{ result.label }}</a>
      </p>

      <div class="confirm-acts">
        <button type="button" class="button is-quiet" :disabled="pending" @click="cancel">
          取消
        </button>
        <button type="button" class="button" :disabled="acceptDisabled" @click="submit">
          {{ pending ? '正在导入…' : '导入' }}
        </button>
      </div>
    </div>
  </div>
</template>
