<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import type { AuthoredVersion } from '../../api/types'
import { formatTime } from '../../time'
import { statusOf, statusText, versionSuffix } from '../../version'

/**
 * This version's facts, in the overview's sticky rail.
 *
 * **The author and the caller see different facts.** The caller sees the four things the consumption
 * contract carries: version number, status, first publish time and address. The name, submitter,
 * submission time, state-change history, file counts and content digest are author-plane facts; the
 * digest is still returned by the API for pinning, but the page does not draw it for a caller.
 *
 * **Two repeats are deliberately not drawn**: 「最近状态变更」 when it equals 「首次上线」 (it is the
 * same sentence), and the address's version suffix as a row of its own. **「版本码」 replaces the old
 * draft-only 「版本标识」** (v7): every author-plane state draws its row id, third — after 名称 and
 * 版本号 — while the caller keeps four rows and no code. The address already ends in that code for a
 * draft; the row is the id as a single copyable value, not the same string a second time.
 */
const props = defineProps<{
  version: AuthoredVersion
  namespace: string
  name: string
  rung: string
}>()

const status = computed(() => statusOf(props.version))
const caller = computed(() => props.rung === 'invoke')
const address = computed(
  () => `${props.namespace}/${props.name}@${versionSuffix(props.version)}`,
)

function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  const kilobytes = bytes / 1024
  return kilobytes < 1024 ? `${kilobytes.toFixed(1)} KB` : `${(kilobytes / 1024).toFixed(1)} MB`
}

/**
 * The verb before the state-change time, or null when the API cannot name the transition.
 *
 * **A deliberate deviation.** The design distinguishes 「成为历史版本」 (a rollback displaced this
 * row) from 「重新上架于」 (a take-down was undone) with two demo fixtures, `supersededAt` and
 * `restoredAt`. The server stores one `state_at` and does not record which transition moved it
 * (TD §数据模型), so a restored, still-historical row and a displaced one are indistinguishable here.
 * Rather than choose one and say something false for the other half, a historical row gets the row
 * label alone — 「最近状态变更」 with the time — which is true for both. The remaining four statuses
 * name their transition because their state does.
 */
function stateChangeText(): string | null {
  switch (status.value) {
    case 'live':
      return '上线于'
    case 'revoked':
      return '下架于'
    case 'discarded':
      return '丢弃于'
    default:
      return null
  }
}

interface Row {
  label: string
  value: string
  copy?: string
}

const rows = computed<Row[]>(() => {
  const v = props.version
  if (caller.value) {
    return [
      {
        label: '版本号',
        value: v.name === null ? '—' : `@${v.name}`,
        copy: v.name === null ? undefined : `@${v.name}`,
      },
      { label: '状态', value: statusText(status.value) },
      {
        label: '首次上线',
        value: v.first_published_at === null ? '—' : formatTime(v.first_published_at),
      },
      { label: '地址', value: address.value, copy: address.value },
    ]
  }

  const list: Row[] = [
    { label: '名称', value: v.draft_name ?? '—', copy: v.draft_name ?? undefined },
    {
      label: '版本号',
      value: v.name === null ? '—' : `@${v.name}`,
      copy: v.name === null ? undefined : `@${v.name}`,
    },
    {
      label: '版本码',
      value: v.code ?? v.draft_id ?? '—',
      copy: v.code ?? v.draft_id ?? undefined,
    },
    { label: '状态', value: statusText(status.value) },
    { label: '提交', value: `${v.submitted_by ?? '—'} · ${formatTime(v.submitted_at)}` },
    {
      label: '首次上线',
      value: v.first_published_at === null ? '—' : formatTime(v.first_published_at),
    },
  ]
  if (v.state_at !== null && v.state_at !== v.first_published_at) {
    const verb = stateChangeText()
    list.push({
      label: '最近状态变更',
      value: verb === null ? formatTime(v.state_at) : `${verb} ${formatTime(v.state_at)}`,
    })
  }
  list.push({ label: '内容摘要', value: v.digest, copy: v.digest })
  list.push({ label: '文件', value: `${v.file_count} 个文件 · ${formatSize(v.total_bytes)}` })
  list.push({ label: '地址', value: address.value, copy: address.value })
  return list
})

const copied = ref<string | null>(null)
const failed = ref<string | null>(null)
let timer: number | null = null

function done(label: string): void {
  copied.value = label
  if (timer !== null) {
    window.clearTimeout(timer)
  }
  timer = window.setTimeout(() => {
    copied.value = null
  }, 1200)
}

/** Clipboard first (file:// is not always available), then a hidden textarea; never a fake success. */
function copy(text: string, label: string): void {
  const fallback = (): void => {
    try {
      const area = document.createElement('textarea')
      area.value = text
      area.setAttribute('readonly', '')
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      const ok = document.execCommand('copy')
      area.remove()
      if (ok) {
        done(label)
      } else {
        failed.value = label
      }
    } catch {
      failed.value = label
    }
  }
  failed.value = null
  if (navigator.clipboard?.writeText !== undefined) {
    navigator.clipboard.writeText(text).then(() => done(label), fallback)
  } else {
    fallback()
  }
}

onBeforeUnmount(() => {
  if (timer !== null) {
    window.clearTimeout(timer)
  }
})
</script>

<template>
  <aside class="vp-facts" aria-label="版本事实">
    <h2 class="vp-facts-title">版本事实</h2>
    <dl class="vp-facts-list">
      <div v-for="row in rows" :key="row.label">
        <dt>{{ row.label }}</dt>
        <dd>
          <span class="vp-fact-line">
            <span>{{ row.value }}</span>
            <button
              v-if="row.copy !== undefined"
              type="button"
              class="vp-copy"
              :class="{ 'is-done': copied === row.label }"
              :aria-label="copied === row.label ? `已复制${row.label}` : `复制${row.label}`"
              :title="
                failed === row.label
                  ? '复制失败，请手动选择'
                  : copied === row.label
                    ? '已复制'
                    : `复制${row.label}`
              "
              @click="copy(row.copy, row.label)"
            >
              <svg v-if="copied !== row.label" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <rect
                  x="9"
                  y="9"
                  width="11"
                  height="11"
                  rx="2"
                  stroke="currentColor"
                  stroke-width="1.8"
                />
                <path
                  d="M5 15V6a2 2 0 0 1 2-2h9"
                  stroke="currentColor"
                  stroke-width="1.8"
                  stroke-linecap="round"
                />
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M5 12.5 9.5 17 19 7.5"
                  stroke="currentColor"
                  stroke-width="1.8"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </button>
            <span class="visually-hidden" aria-live="polite">{{
              copied === row.label ? '已复制' : ''
            }}</span>
          </span>
        </dd>
      </div>
    </dl>
  </aside>
</template>
