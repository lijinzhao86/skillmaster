<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import type { AuthoredVersion } from '../../api/types'
import { formatTime } from '../../time'
import { statusClass, statusOf, statusText, versionTitle } from '../../version'

/**
 * A version's identity: breadcrumb, title, version number, status, meta, and its two ways onward.
 *
 * **The title is always this version's name** — before and after publishing. Publishing assigns a
 * version number; it does not take the name away. An old row without one falls back to the number,
 * which is then the only identity it has; a caller's identity is the number, and that is the one
 * thing the consumption contract carries.
 *
 * A draft's rename happens on the title itself (a pencil → input → 保存 / 取消, Enter saves, Esc
 * cancels). It does not open an action block in 操作: renaming is editing this record, not changing
 * its lifecycle (TD §改动, 2026-10-20).
 */
const props = defineProps<{
  namespace: string
  name: string
  version: AuthoredVersion
  rung: string
  listHref: string
  filesHref: string | null
  /** The whole version as one zip; same condition as 查看文件 (v9, ADR 0068). */
  downloadHref: string | null
  /** The draft's editor address; null for every version whose content is frozen (v8). */
  editHref: string | null
  jumpHref: string | null
  jumpLabel: string
  editable: boolean
  /** When the three-pane tab row is drawn, its own rule replaces the head's. */
  hasTabs: boolean
}>()

const emit = defineEmits<{ 'save-name': [value: string] }>()

const status = computed(() => statusOf(props.version))
const author = computed(() => props.rung !== 'invoke')
const title = computed(() => versionTitle(props.version, props.rung))
// The design: the title is the author's name, and a row without one falls back to its number —
// **that**, and only that, is monospaced. The old condition excluded drafts, so a pre-v6 draft that
// declares a number drew that number in the proportional face.
const mono = computed(() => !author.value || props.version.draft_name === null)
const number = computed(
  () =>
    author.value &&
    status.value !== 'draft' &&
    props.version.name !== null &&
    props.version.draft_name !== null,
)
const meta = computed(() => {
  const v = props.version
  const when = v.first_published_at ?? v.submitted_at
  if (!author.value) {
    return `上线于 ${formatTime(when)}`
  }
  return v.first_published_at !== null
    ? `上线于 ${formatTime(v.first_published_at)} · ${v.submitted_by ?? ''}`
    : `提交于 ${formatTime(v.submitted_at)} · ${v.submitted_by ?? ''}`
})

const editing = ref(false)
const draftName = ref('')
const input = ref<HTMLInputElement | null>(null)

function beginEdit(): void {
  draftName.value = props.version.draft_name ?? ''
  editing.value = true
  void nextTick(() => {
    input.value?.focus()
    input.value?.select()
  })
}

function cancel(): void {
  editing.value = false
}

function save(): void {
  const value = Array.from(draftName.value.trim()).slice(0, 40).join('')
  if (value === '') {
    return
  }
  emit('save-name', value)
}

function onInput(): void {
  const chars = Array.from(draftName.value)
  if (chars.length > 40) {
    draftName.value = chars.slice(0, 40).join('')
  }
}
</script>

<template>
  <nav class="crumbs" aria-label="面包屑">
    <a :href="listHref">版本</a>
    <span class="crumbs-sep" aria-hidden="true">/</span>
    <span class="crumbs-here">{{ title }}</span>
  </nav>

  <header class="vp-head" :class="{ 'has-tabs': hasTabs }">
    <div class="vp-head-main">
      <div class="vp-title-row">
        <span class="vp-title-name">
          <h1 v-if="!editing" class="vp-title" :class="{ 'is-mono': mono }" tabindex="-1">
            {{ title }}
          </h1>
          <span v-else class="vp-title-edit">
            <input
              ref="input"
              v-model="draftName"
              class="vp-title-input"
              type="text"
              aria-label="版本名称（最多 40 个字符）"
              title="最多 40 个字符；中文、英文、数字与符号都可以"
              @input="onInput"
              @keydown.enter.prevent="save"
              @keydown.esc.prevent="cancel"
            />
            <span class="vp-inline-acts" style="margin-top: 0">
              <button type="button" class="button" :disabled="draftName.trim() === ''" @click="save">
                保存
              </button>
              <button type="button" class="button is-quiet" @click="cancel">取消</button>
            </span>
          </span>
          <button
            v-if="editable && !editing"
            type="button"
            class="inline-edit"
            aria-label="改名"
            title="改名"
            @click="beginEdit"
          >
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path
                d="M4 20h4L19 9l-4-4L4 16v4z"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linejoin="round"
              />
              <path d="M13.5 6.5l4 4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
          </button>
        </span>

        <span v-if="number" class="vp-number">@{{ version.name }}</span>
        <span class="version-states">
          <span class="chip" :class="statusClass(status)">
            <i class="dot" /><span class="chip-text">{{ statusText(status) }}</span>
          </span>
        </span>
      </div>
      <p class="vp-meta">{{ meta }}</p>
    </div>

    <div class="vp-head-act">
      <a v-if="filesHref !== null" class="button is-quiet vp-files-link" :href="filesHref">
        <svg class="vp-files-icon" aria-hidden="true"><use href="#fileMark" /></svg>
        查看文件
      </a>
      <!-- **取整包（v9）。** Same condition as 查看文件; a plain link, so the browser downloads it
           and the page draws neither progress nor result. Order is 看 → 取 → 改. -->
      <a v-if="downloadHref !== null" class="button is-quiet vp-files-link" :href="downloadHref">
        <svg class="vp-files-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M12 4v10M8 10l4 4 4-4"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
          <path d="M5 19h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
        下载
      </a>
      <a v-if="editHref !== null" class="button is-quiet vp-files-link" :href="editHref">
        <svg class="vp-files-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M4 20h4L19 9l-4-4L4 16v4z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
          <path d="M13.5 6.5l4 4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
        编辑文件
      </a>
      <a v-if="jumpHref !== null && jumpLabel !== ''" class="button" :href="jumpHref">{{ jumpLabel }}</a>
    </div>
  </header>
</template>
