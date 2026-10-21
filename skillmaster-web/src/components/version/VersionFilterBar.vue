<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AuthoredVersion } from '../../api/types'
import CommandMenu from '../CommandMenu.vue'
import { versionCreateItems } from './createMenu'
import { canWrite } from '../../composables/useVersionActions'
import { filterKeyOf, statusOf, type VersionFilterKey } from '../../version'

/**
 * The version list's toolbar: status chips with counts, the new-version placeholder, and search.
 *
 * **Counts describe what this rung can see, and do not move while searching** — the chips answer
 * 「哪里有多少」. A chip is drawn only for a status that occurs; 「全部」 is always there and is also
 * the way to clear the status filter. Filter and search combine, and both are written to the address
 * by the page, so a filtered list can be refreshed or sent to somebody.
 *
 * 「新建版本」 is a real entry since v8: it goes to the editor, where the base is visible before
 * anything is written. It is drawn for `write` and above only — 调用 and 浏览 cannot submit a
 * version, and a button that can do nothing is exactly that promise.
 */
const props = defineProps<{
  versions: AuthoredVersion[]
  filter: VersionFilterKey
  query: string
  rung: string
  /** The new-version editor's address. */
  newHref: string
}>()

const emit = defineEmits<{
  'update:filter': [value: VersionFilterKey]
  'update:query': [value: string]
  /** 「导入压缩包」: the page owns the sheet, because it also owns where a success lands. */
  'open-import': []
}>()

const CHIPS: Array<{ key: Exclude<VersionFilterKey, 'all'>; label: string }> = [
  { key: 'live', label: '线上' },
  { key: 'draft', label: '草稿' },
  { key: 'published', label: '历史版本' },
  { key: 'revoked', label: '已下架' },
  { key: 'discarded', label: '已丢弃' },
]

const counts = computed<Record<string, number>>(() => {
  const result: Record<string, number> = {}
  for (const version of props.versions) {
    const key = filterKeyOf(statusOf(version))
    result[key] = (result[key] ?? 0) + 1
  }
  return result
})

const createItems = computed(() => versionCreateItems(props.newHref, () => emit('open-import')))

const menu = ref<InstanceType<typeof CommandMenu> | null>(null)

/** Puts focus back on 「新建版本 ▾」 — what the design asks for when the sheet it opened closes. */
defineExpose({ focusCreate: () => menu.value?.focusTrigger() })

const searchPlaceholder = computed(() =>
  props.rung === 'invoke' ? '搜索版本号或说明' : '搜索版本名称、版本号或说明',
)

function pick(key: VersionFilterKey): void {
  emit('update:filter', key)
}

function setQuery(value: string): void {
  emit('update:query', value)
}

function clearQuery(): void {
  emit('update:query', '')
}
</script>

<template>
  <div class="version-toolbar">
    <div class="version-filters" role="group" aria-label="按状态筛选">
      <button
        type="button"
        class="version-filter"
        :class="{ 'is-current': filter === 'all' }"
        :aria-pressed="filter === 'all' ? 'true' : 'false'"
        @click="pick('all')"
      >
        全部 <span class="version-filter-count">{{ versions.length }}</span>
      </button>
      <template v-for="chip in CHIPS" :key="chip.key">
        <button
          v-if="(counts[chip.key] ?? 0) > 0"
          type="button"
          class="version-filter"
          :class="{ 'is-current': filter === chip.key }"
          :aria-pressed="filter === chip.key ? 'true' : 'false'"
          @click="pick(chip.key)"
        >
          {{ chip.label }} <span class="version-filter-count">{{ counts[chip.key] }}</span>
        </button>
      </template>
    </div>

    <!-- **One action, two sources** (v9): 在线编辑 is the editor, 导入压缩包 brings a zip in.
         A menu rather than two buttons, because they produce the same thing (2026-10-21). -->
    <CommandMenu
      v-if="canWrite(rung)"
      ref="menu"
      :groups="[createItems]"
      menu-label="新建版本"
      resize="reposition"
    >
      <template #trigger="{ open, toggle }">
        <button
          type="button"
          class="button version-create menu-button"
          aria-haspopup="menu"
          :aria-expanded="open ? 'true' : 'false'"
          @click="toggle"
        >
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M12 5v14M5 12h14"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
            />
          </svg>
          新建版本
          <svg class="button-caret" viewBox="0 0 12 12" aria-hidden="true">
            <path
              d="M3 4.8 6 7.8 9 4.8"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </button>
      </template>
    </CommandMenu>
  </div>

  <div class="search version-search">
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" stroke-width="1.8" />
      <line
        x1="15.4"
        y1="15.4"
        x2="20.6"
        y2="20.6"
        stroke="currentColor"
        stroke-width="1.8"
        stroke-linecap="round"
      />
    </svg>
    <input
      :value="query"
      class="control"
      type="search"
      autocomplete="off"
      spellcheck="false"
      aria-label="搜索版本"
      :placeholder="searchPlaceholder"
      @input="setQuery(($event.target as HTMLInputElement).value)"
      @keydown.esc.prevent="query !== '' && clearQuery()"
    />
    <button
      v-if="query !== ''"
      type="button"
      class="search-clear"
      aria-label="清除搜索"
      @click="clearQuery"
    >
      ×
    </button>
  </div>
</template>
