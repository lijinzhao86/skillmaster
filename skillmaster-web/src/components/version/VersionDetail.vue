<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { codeMessage } from '../../api/errors'
import { updateDraftName, updateDraftNote } from '../../api/skills'
import type { AuthoredVersion } from '../../api/types'
import {
  canEditDraft,
  canMaintain,
  canWrite,
  defaultBaseline,
  hasVersionActions,
} from '../../composables/useVersionActions'
import type { VersionRoute } from '../../version'
import { answersPin, statusOf, versionSuffix } from '../../version'
import { formatTime } from '../../time'
import FormBanner from '../FormBanner.vue'
import VersionActionPanel from './VersionActionPanel.vue'
import VersionDiffPane from './VersionDiffPane.vue'
import VersionFacts from './VersionFacts.vue'
import VersionHeader from './VersionHeader.vue'
import VersionStatusBar from './VersionStatusBar.vue'

/**
 * One version's own page: identity and state at the top, then 概览 / 改动 / 操作.
 *
 * **This is a record, not a second list.** Selecting a version happens in the list; this page is
 * reached by pinning one. The three panes are the three ways to read it — what it is and said
 * (概览), what it changes (改动), what can be done to it (操作). A pane that has nothing to show is
 * not drawn; when neither of the latter two exists, the tab row itself is not drawn. An address
 * that asks for a pane this rung cannot see falls back to 概览 rather than to an empty shell.
 *
 * **Renaming and editing the note happen in place** (title pencil, note pencil) and are not action
 * blocks: they edit this record, they do not change its lifecycle. Both are draft-only; a published
 * version has no pencil.
 */
const props = defineProps<{
  namespace: string
  name: string
  version: AuthoredVersion
  versions: AuthoredVersion[]
  rung: string
  username: string | null
  live: AuthoredVersion | null
  route: VersionRoute
}>()

const emit = defineEmits<{ 'select-baseline': [pin: string] }>()

const status = computed(() => statusOf(props.version))
const maintain = computed(() => canMaintain(props.rung))
const editable = computed(() => canEditDraft(props.version, props.rung, props.username))
const hasActions = computed(() => hasVersionActions(props.version, props.rung, props.username))

const baseline = computed(() => {
  const from = props.route.from
  const chosen =
    from === undefined
      ? undefined
      : props.versions.find(
          (candidate) => candidate !== props.version && answersPin(candidate, from),
        )
  return (
    chosen ??
    defaultBaseline(props.version, props.versions, props.live, props.rung)
  )
})

const hasDiff = computed(() => props.rung !== 'invoke' && baseline.value !== null)
const pane = computed<'record' | 'diff' | 'actions'>(() => {
  if (props.route.sub === 'diff' && hasDiff.value) {
    return 'diff'
  }
  if (props.route.sub === 'actions' && hasActions.value) {
    return 'actions'
  }
  return 'record'
})

const pin = computed(() => versionSuffix(props.version))
const base = computed(
  () =>
    `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}`,
)

const listHref = computed(() => {
  const query = new URLSearchParams(window.location.search)
  for (const key of ['version', 'sub', 'from', 'to', 'fq', 'file', 'list']) {
    query.delete(key)
  }
  query.set('tab', 'versions')
  return `${base.value}?${query.toString()}`
})

function subHref(sub: 'record' | 'diff' | 'actions'): string {
  const query = new URLSearchParams(window.location.search)
  query.set('tab', 'versions')
  query.set('version', pin.value)
  if (sub === 'record') {
    query.delete('sub')
  } else {
    query.set('sub', sub)
  }
  return `${base.value}?${query.toString()}`
}

const filesHref = computed<string | null>(() => {
  if (props.rung === 'invoke' || status.value === 'discarded') {
    return null
  }
  if (status.value === 'draft' && !canWrite(props.rung)) {
    return null
  }
  return `${base.value}@${encodeURIComponent(pin.value)}?tab=files`
})

const jumpLabel = computed(() => {
  if (!maintain.value) {
    return ''
  }
  switch (status.value) {
    case 'draft':
      return '上线'
    case 'live':
      return '回滚'
    case 'history':
      return '上线'
    case 'revoked':
      return '重新上架'
    default:
      return ''
  }
})

const jumpHref = computed(() => (jumpLabel.value === '' ? null : subHref('actions')))

/**
 * 编辑文件 is the draft's second entry (v8), beside 查看文件: the two questions about a draft are
 * "what is in it" and "I want to change it". `editable` already means 草稿 + 我能改, so published,
 * historical, revoked and discarded rows have no link.
 */
const editHref = computed<string | null>(() =>
  editable.value ? `${base.value}@${encodeURIComponent(pin.value)}/edit` : null,
)

const localBanner = ref<string | null>(null)

async function rename(value: string): Promise<void> {
  const result = await updateDraftName(props.namespace, props.name, pin.value, value)
  if (!result.ok) {
    localBanner.value = codeMessage(result.code, result.message)
    return
  }
  window.location.reload()
}

/* --------------------------------------------------------------- the note */

const editingNote = ref(false)
const noteDraft = ref('')
const noteInput = ref<HTMLTextAreaElement | null>(null)

function beginNote(): void {
  noteDraft.value = props.version.note ?? ''
  editingNote.value = true
  void nextTick(() => noteInput.value?.focus())
}

function cancelNote(): void {
  editingNote.value = false
}

function onNoteInput(): void {
  const chars = Array.from(noteDraft.value)
  if (chars.length > 200) {
    noteDraft.value = chars.slice(0, 200).join('')
  }
}

async function saveNote(): Promise<void> {
  const result = await updateDraftNote(props.namespace, props.name, pin.value, noteDraft.value.trim())
  if (!result.ok) {
    localBanner.value = codeMessage(result.code, result.message)
    return
  }
  window.location.reload()
}

/* --------------------------------------------------------------- the timeline */

const timeline = computed(() => {
  const items: Array<{ label: string; time: string }> = []
  if (props.rung !== 'invoke') {
    items.push({ label: '提交', time: formatTime(props.version.submitted_at) })
  }
  if (props.version.first_published_at !== null) {
    items.push({ label: '首次上线', time: formatTime(props.version.first_published_at) })
  }
  if (props.rung !== 'invoke' && status.value === 'revoked' && props.version.state_at !== null) {
    items.push({ label: '下架', time: formatTime(props.version.state_at) })
  }
  if (props.rung !== 'invoke' && status.value === 'discarded' && props.version.state_at !== null) {
    items.push({ label: '丢弃', time: formatTime(props.version.state_at) })
  }
  return items
})

/* --------------------------------------------------------------- the diff summary */

const summary = ref<{ files: number; added: number | null; removed: number | null } | null>(null)

function onSummary(
  value: { files: number; added: number | null; removed: number | null } | null,
): void {
  summary.value = value
}
</script>

<template>
  <div id="versionDetail">
    <FormBanner :message="localBanner" />

    <VersionHeader
      :namespace="namespace"
      :name="name"
      :version="version"
      :rung="rung"
      :list-href="listHref"
      :files-href="filesHref"
      :edit-href="editHref"
      :jump-href="jumpHref"
      :jump-label="jumpLabel"
      :editable="editable"
      :has-tabs="hasDiff || hasActions"
      @save-name="rename"
    />

    <VersionStatusBar :version="version" :live="live" :rung="rung" />

    <nav v-if="hasDiff || hasActions" class="vp-tabs" aria-label="版本页的三栏">
      <a
        class="vp-tab"
        :class="{ 'is-current': pane === 'record' }"
        :aria-current="pane === 'record' ? 'page' : undefined"
        :href="subHref('record')"
        >概览</a
      >
      <a
        v-if="hasDiff"
        class="vp-tab"
        :class="{ 'is-current': pane === 'diff' }"
        :aria-current="pane === 'diff' ? 'page' : undefined"
        :href="subHref('diff')"
        >改动 <span class="vp-tab-count">{{ summary === null ? '—' : summary.files }}</span></a
      >
      <a
        v-if="hasActions"
        class="vp-tab"
        :class="{ 'is-current': pane === 'actions' }"
        :aria-current="pane === 'actions' ? 'page' : undefined"
        :href="subHref('actions')"
        >操作</a
      >
      <span v-if="hasDiff" class="vp-tabs-stats">
        <template v-if="summary !== null">
          <b class="stat-add">+{{ summary.added }}</b> <b class="stat-del">−{{ summary.removed }}</b>
        </template>
        <template v-else>—</template>
      </span>
    </nav>

    <div v-show="pane === 'record'" class="vp-pane" data-vpane="record">
      <div class="vp-layout">
        <div class="vp-main">
          <section class="vp-module">
            <span class="info-label">
              <svg class="info-icon" aria-hidden="true"><use href="#descMark" /></svg>
              说明
              <button
                v-if="editable"
                type="button"
                class="inline-edit"
                aria-label="编辑说明"
                title="编辑说明"
                @click="beginNote"
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M4 20h4L19 9l-4-4L4 16v4z"
                    stroke="currentColor"
                    stroke-width="1.8"
                    stroke-linejoin="round"
                  />
                  <path
                    d="M13.5 6.5l4 4"
                    stroke="currentColor"
                    stroke-width="1.8"
                    stroke-linecap="round"
                  />
                </svg>
              </button>
            </span>

            <template v-if="!editingNote">
              <p class="vp-note" :class="{ 'is-empty': !version.note }">
                {{ version.note || '还没有说明' }}
              </p>
            </template>
            <template v-else>
              <textarea
                ref="noteInput"
                v-model="noteDraft"
                class="vp-note-input"
                rows="3"
                aria-label="说明"
                placeholder="这个版本改了什么"
                @input="onNoteInput"
                @keydown.esc.prevent="cancelNote"
                @keydown.meta.enter.prevent="saveNote"
                @keydown.ctrl.enter.prevent="saveNote"
              />
              <div class="vp-inline-acts">
                <button type="button" class="button" @click="saveNote">保存</button>
                <button type="button" class="button is-quiet" @click="cancelNote">取消</button>
              </div>
            </template>
          </section>

          <section v-if="timeline.length > 0" class="vp-module">
            <p class="vp-eyebrow">时间线</p>
            <ol class="vp-timeline">
              <li
                v-for="(item, index) in timeline"
                :key="index"
                :class="{ 'is-current': index === timeline.length - 1 }"
              >
                {{ item.label }}
                <span class="time">{{ item.time }}</span>
              </li>
            </ol>
          </section>
        </div>

        <VersionFacts
          :version="version"
          :namespace="namespace"
          :name="name"
          :rung="rung"
        />
      </div>
    </div>

    <!-- Kept mounted while another pane is open, so the tab's count and totals are there when the
         reader comes back; it is hidden, not unmounted. -->
    <VersionDiffPane
      v-if="hasDiff"
      v-show="pane === 'diff'"
      :namespace="namespace"
      :name="name"
      :version="version"
      :versions="versions"
      :rung="rung"
      :from="route.from"
      :fq="route.fq"
      :file="route.file"
      :list="route.list"
      :live="live"
      @summary="onSummary"
      @select-baseline="emit('select-baseline', $event)"
    />

    <div v-show="pane === 'actions'" class="vp-pane vp-actions-pane" data-vpane="actions">
      <VersionActionPanel
        v-if="hasActions"
        :namespace="namespace"
        :name="name"
        :version="version"
        :versions="versions"
        :rung="rung"
        :username="username"
        :live="live"
      />
    </div>
  </div>
</template>
