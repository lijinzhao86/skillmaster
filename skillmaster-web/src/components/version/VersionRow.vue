<script setup lang="ts">
import { computed, onBeforeUnmount, ref, type ComponentPublicInstance } from 'vue'
import type { AuthoredVersion } from '../../api/types'
import { versionActions } from '../../composables/useVersionActions'
import { formatTime } from '../../time'
import {
  statusClass,
  statusOf,
  statusText,
  versionSuffix,
  versionTitle,
} from '../../version'

/**
 * One row of the version list: two segments, no header.
 *
 * **The list only navigates.** The name is the row's one real link; a draft's rename, note and
 * discard happen on that version's own page, not here. Its `::after` covers the rest of the row, so
 * the whole row is the link — one tab stop, and Ctrl/middle-click anywhere open the version in a new
 * tab. The 更多操作 button sits above that layer (`position: relative; z-index: 1`) and its items are
 * links too: they take the reader to the version's page, they do not execute anything.
 */
const props = defineProps<{
  version: AuthoredVersion
  namespace: string
  name: string
  rung: string
  username: string | null
  /** `filter=…&q=…`, carried back to the list from the version's own page. */
  listQuery: string
  hasBaseline: boolean
}>()

const status = computed(() => statusOf(props.version))
const author = computed(() => props.rung !== 'invoke')
const title = computed(() => versionTitle(props.version, props.rung))
const number = computed(() => (props.version.name === null ? '—' : `@${props.version.name}`))
const note = computed(() => props.version.note ?? '')
const meta = computed(() =>
  author.value
    ? `${props.version.submitted_by ?? ''} · ${formatTime(props.version.submitted_at)}`
    : `上线于 ${formatTime(props.version.first_published_at ?? props.version.submitted_at)}`,
)

const base = computed(
  () => `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}`,
)

const pin = computed(() => versionSuffix(props.version))
/** The row id as a value of its own — what 「版本码」 shows, distinct from the whole address. */
const code = computed(() => props.version.code ?? props.version.draft_id ?? '')

const detailHref = computed(() => {
  const query = new URLSearchParams()
  query.set('tab', 'versions')
  query.set('version', pin.value)
  if (props.listQuery !== '') {
    const carried = new URLSearchParams(props.listQuery)
    for (const [key, value] of carried) {
      query.set(key, value)
    }
  }
  return `${base.value}?${query.toString()}`
})

const filesHref = computed(
  () => `${base.value}@${encodeURIComponent(pin.value)}?tab=files`,
)
const subHref = (sub: string): string => `${detailHref.value}&sub=${sub}`

interface Item {
  label: string
  href: string
  view: boolean
}

const items = computed<Item[]>(() => {
  if (status.value === 'discarded') {
    return []
  }
  const list: Item[] = []
  for (const action of versionActions(props.version, props.rung, props.username, props.hasBaseline)) {
    switch (action) {
      case 'files':
        list.push({ label: '查看文件', href: filesHref.value, view: true })
        break
      case 'diff':
        list.push({ label: '查看改动', href: subHref('diff'), view: true })
        break
      case 'publish':
        list.push({ label: '上线', href: subHref('actions'), view: false })
        break
      case 'rollback':
        list.push({ label: '回滚', href: subHref('actions'), view: false })
        break
      case 'revoke':
        list.push({ label: '下架', href: subHref('actions'), view: false })
        break
      case 'restore':
        list.push({ label: '重新上架', href: subHref('actions'), view: false })
        break
      case 'discard':
        list.push({ label: '丢弃', href: subHref('actions'), view: false })
        break
    }
  }
  return list
})

/* ------------------------------------------------------------- the menu */
/* ---------------------------------------------------------- the version code */
const copiedCode = ref(false)
const codeCopyFailed = ref(false)
let codeTimer: number | null = null

/** Clipboard first (file:// is not always available), then a hidden textarea; never a fake success. */
function copyCode(): void {
  const text = code.value
  const done = (ok: boolean): void => {
    if (codeTimer !== null) {
      window.clearTimeout(codeTimer)
    }
    if (!ok) {
      // **A failure keeps its sentence.** The design resets only the success state; clearing the
      // failure after 1.2 s would take away the one line that explains why nothing was copied.
      copiedCode.value = false
      codeCopyFailed.value = true
      return
    }
    copiedCode.value = true
    codeCopyFailed.value = false
    codeTimer = window.setTimeout(() => {
      copiedCode.value = false
      codeCopyFailed.value = false
    }, 1200)
  }
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
      done(ok)
    } catch {
      done(false)
    }
  }
  if (navigator.clipboard?.writeText !== undefined) {
    navigator.clipboard.writeText(text).then(() => done(true), fallback)
  } else {
    fallback()
  }
}

const open = ref(false)
const trigger = ref<HTMLButtonElement | null>(null)
const list = ref<HTMLDivElement | null>(null)
const menuStyle = ref<Record<string, string>>({})
const links = ref<HTMLElement[]>([])

function position(): void {
  const button = trigger.value
  const box = list.value
  if (button === null || box === null) {
    return
  }
  const rect = button.getBoundingClientRect()
  const width = box.getBoundingClientRect().width
  const height = box.getBoundingClientRect().height
  menuStyle.value = {
    left: `${Math.round(
      Math.min(Math.max(8, rect.right - width), Math.max(8, window.innerWidth - width - 8)),
    )}px`,
    top: `${Math.round(
      rect.bottom + 6 + height > window.innerHeight - 8 ? rect.top - 6 - height : rect.bottom + 6,
    )}px`,
  }
}

function onOutside(event: MouseEvent): void {
  const target = event.target as Node
  if (trigger.value?.contains(target) !== true && list.value?.contains(target) !== true) {
    close(false)
  }
}

function close(refocus: boolean): void {
  if (!open.value) {
    return
  }
  open.value = false
  document.removeEventListener('mousedown', onOutside, true)
  window.removeEventListener('scroll', closeOnViewportChange, true)
  window.removeEventListener('resize', closeOnViewportChange)
  if (refocus) {
    trigger.value?.focus()
  }
}

function closeOnViewportChange(): void {
  close(false)
}

function show(): void {
  close(false)
  open.value = true
  document.addEventListener('mousedown', onOutside, true)
  window.addEventListener('scroll', closeOnViewportChange, true)
  window.addEventListener('resize', closeOnViewportChange)
  requestAnimationFrame(() => {
    position()
    links.value[0]?.focus()
  })
}

function onTriggerKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    show()
  }
}

function onMenuKeydown(event: KeyboardEvent): void {
  const index = links.value.indexOf(document.activeElement as HTMLElement)
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    links.value[Math.min(index + 1, links.value.length - 1)]?.focus()
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    if (index <= 0) {
      close(true)
    } else {
      links.value[index - 1]?.focus()
    }
  } else if (event.key === 'Home') {
    event.preventDefault()
    links.value[0]?.focus()
  } else if (event.key === 'End') {
    event.preventDefault()
    links.value[links.value.length - 1]?.focus()
  } else if (event.key === 'Escape') {
    event.preventDefault()
    close(true)
  } else if (event.key === 'Tab') {
    close(false)
  }
}

function setLink(
  index: number,
): (el: Element | ComponentPublicInstance | null) => void {
  return (el) => {
    if (el instanceof HTMLElement) {
      links.value[index] = el
    }
  }
}

onBeforeUnmount(() => {
  close(false)
  if (codeTimer !== null) {
    window.clearTimeout(codeTimer)
  }
})
</script>

<template>
  <li>
    <div class="version-head">
      <a
        class="version-name"
        :class="{ 'is-mono': !author }"
        :href="detailHref"
        :title="note === '' ? title : `${title}\n${note}`"
        :aria-label="`打开 ${title}`"
        >{{ title }}</a
      >
      <span v-if="author" class="version-number" :class="{ 'is-empty': version.name === null }">{{
        number
      }}</span>
      <span class="version-states">
        <span class="chip" :class="statusClass(status)">
          <i class="dot" /><span class="chip-text">{{ statusText(status) }}</span>
        </span>
      </span>

      <span v-if="items.length > 0" class="version-menu">
        <button
          ref="trigger"
          type="button"
          class="version-menu-trigger"
          aria-haspopup="menu"
          :aria-expanded="open ? 'true' : 'false'"
          :aria-label="`更多操作：${title}`"
          title="更多操作"
          @click="open ? close(true) : show()"
          @keydown="onTriggerKeydown"
        >
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="12" cy="5" r="1.7" fill="currentColor" />
            <circle cx="12" cy="12" r="1.7" fill="currentColor" />
            <circle cx="12" cy="19" r="1.7" fill="currentColor" />
          </svg>
        </button>
        <div
          :hidden="!open"
          ref="list"
          class="menu-list"
          role="menu"
          :aria-label="`更多操作：${title}`"
          :style="menuStyle"
          @keydown="onMenuKeydown"
        >
          <div class="menu-group" role="group">
            <a
              v-for="(item, index) in items"
              :key="item.label"
              :ref="setLink(index)"
              class="menu-item"
              role="menuitem"
              tabindex="-1"
              :href="item.href"
              >{{ item.label }}</a
            >
          </div>
        </div>
      </span>
    </div>

    <div class="version-sub">
      <span class="version-note">{{ note }}</span>
      <span v-if="author && code !== ''" class="version-code-slot">
        <span class="version-code">
          <span>{{ code }}</span>
          <button
            type="button"
            class="vp-copy"
            :class="{ 'is-done': copiedCode }"
            :aria-label="copiedCode ? '已复制版本码' : '复制版本码'"
            :title="codeCopyFailed ? '复制失败，请手动选择' : copiedCode ? '已复制' : '复制版本码'"
            @click.stop.prevent="copyCode"
          >
            <svg v-if="!copiedCode" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <rect x="9" y="9" width="11" height="11" rx="2" stroke="currentColor" stroke-width="1.8" />
              <path d="M5 15V6a2 2 0 0 1 2-2h9" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M5 12.5 9.5 17 19 7.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
          <span class="visually-hidden" aria-live="polite">{{ copiedCode ? '已复制' : '' }}</span>
        </span>
      </span>
      <span class="version-meta">{{ meta }}</span>
    </div>
  </li>
</template>
