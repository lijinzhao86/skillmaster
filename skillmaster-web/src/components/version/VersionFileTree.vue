<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DiffFile } from '../../api/types'
import { fileAnchorId } from '../../version'

/**
 * The 改动 pane's left rail: every changed file, its `+n −m`, and a path filter above it.
 *
 * **The filter narrows this list only.** The diff bodies already loaded do not disappear while the
 * reader types; a matched row still scrolls to (or opens) its block. Its value is the address's `fq`
 * (TD §改动), so a filtered tree can be sent to somebody else.
 *
 * Binary and over-limit files have no countable lines and show `—`, never `+0 −0` (PRD #25).
 */
const props = defineProps<{
  files: DiffFile[]
  filter: string
  current: string | null
  /** In single-file or list-only mode a row is a real address; otherwise it is an anchor. */
  addressMode: boolean
  hrefFor: (relpath: string) => string
}>()

const emit = defineEmits<{ 'update:filter': [value: string]; select: [relpath: string] }>()

const query = ref(props.filter)

watch(
  () => props.filter,
  (value) => {
    query.value = value
  },
)

const matched = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return needle === ''
    ? props.files
    : props.files.filter((file) => file.relpath.toLowerCase().includes(needle))
})

function setFilter(value: string): void {
  query.value = value
  emit('update:filter', value)
}

function clear(): void {
  setFilter('')
}

function hasStats(file: DiffFile): boolean {
  return file.added !== null && file.removed !== null
}
</script>

<template>
  <aside class="vp-tree" aria-label="改动的文件">
    <div class="search vp-tree-search">
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
        aria-label="搜索改动的文件"
        placeholder="搜索文件路径"
        @input="setFilter(($event.target as HTMLInputElement).value)"
        @keydown.esc.prevent="query !== '' && clear()"
      />
      <button
        v-if="query !== ''"
        type="button"
        class="search-clear"
        aria-label="清除搜索"
        @click="clear"
      >
        ×
      </button>
    </div>

    <ul class="vp-tree-list">
      <li v-for="file in matched" :key="file.relpath">
        <a
          class="vp-tree-item"
          :class="{ 'is-current': file.relpath === current }"
          :title="file.relpath"
          :href="addressMode ? hrefFor(file.relpath) : `#${fileAnchorId(file.relpath)}`"
          @click="addressMode ? undefined : emit('select', file.relpath)"
        >
          <span class="vp-tree-path">{{ file.relpath }}</span>
          <span v-if="hasStats(file)" class="diff-stats">
            <b class="stat-add">+{{ file.added }}</b> <b class="stat-del">−{{ file.removed }}</b>
          </span>
          <span v-else class="diff-stats is-pending">—</span>
        </a>
      </li>
    </ul>
    <p v-if="matched.length === 0 && files.length > 0" class="vp-tree-empty">
      没有匹配的文件。 <button type="button" class="link-button" @click="clear">清除筛选</button>
    </p>
  </aside>
</template>
