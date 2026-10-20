<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DiffFile } from '../api/types'

/**
 * One file of a comparison: its header (path + `+n −m`) and its body (hunk lines, line numbers).
 *
 * The only line renderer since v6: it replaced `DiffView.vue` outright after the small comparison,
 * the version page's 改动 pane and the file tab's block were found to be drawing the same lines
 * three ways. One renderer is one place for the add/remove colours to stay right.
 *
 * **The header and the body are one component**, so the version page's lazy loading can pass its
 * own pending / failed / too-big body through the default slot while keeping the same header.
 *
 * Null counts are said as `—`, never as `+0 −0`: a binary file or one past the server's cap has no
 * countable lines, and zero would be a claim the server did not make (PRD #25).
 */
const props = withDefaults(
  defineProps<{
    file: DiffFile
    expanded?: boolean
    /** The reason a file with no hunks was not rendered; the design's own sentence. */
    unrendered?: string
    /** First-screen lines in the version page's 改动 pane; Infinity for the small comparison. */
    initialLimit?: number
  }>(),
  { expanded: true, unrendered: undefined, initialLimit: Number.POSITIVE_INFINITY },
)

const emit = defineEmits<{ toggle: [] }>()

const isOpen = ref(props.expanded)

const added = computed(() => props.file.added)
const removed = computed(() => props.file.removed)
const countable = computed(() => added.value !== null && removed.value !== null)

function formatSize(bytes: number | null): string {
  if (bytes === null) {
    return ''
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  const kilobytes = bytes / 1024
  return kilobytes < 1024 ? `${kilobytes.toFixed(1)} KB` : `${(kilobytes / 1024).toFixed(1)} MB`
}

const unrenderedText = computed(() => {
  if (props.unrendered !== undefined) {
    return props.unrendered
  }
  return props.file.binary
    ? '二进制文件，不显示内容。'
    : '这个文件的内容没有渲染（太大，或者这次比较的额度用完了）。'
})

const tooBig = computed(
  () => props.file.hunks === null && !props.file.binary && props.file.size !== null,
)

function lineClass(line: string): string {
  if (line.startsWith('+')) {
    return 'is-add'
  }
  return line.startsWith('-') ? 'is-del' : ''
}

/**
 * Hunks flattened for rendering — body lines only, numbered per file from 1.
 *
 * The design's large-diff block draws one `<pre>` of numbered lines and no `@@` rows; the numbering
 * is per file from 1 rather than the two sides' real line numbers. Hunk headers stay in the model
 * (the small comparison uses them) and are not painted here.
 */
const rows = computed(() => {
  const list: Array<{ num: number; text: string }> = []
  let num = 0
  for (const hunk of props.file.hunks ?? []) {
    for (const line of hunk.lines) {
      num += 1
      list.push({ num, text: line })
    }
  }
  return list
})

/** 「单文件首屏 400 行，之后每次 500」 — the version page's 改动 pane passes 400. */
const STEP = 500
const shown = ref(props.initialLimit)

watch(
  () => props.file.hunks,
  () => {
    shown.value = props.initialLimit
  },
)

/**
 * Hunk headers do not count toward the line budget: 「单文件首屏 400 行」 is about lines of the
 * diff, and a header is a label rather than one of them.
 */
const totalLines = computed(() => rows.value.length)

const visibleRows = computed(() => rows.value.slice(0, shown.value))
const remaining = computed(() => Math.max(0, totalLines.value - shown.value))

function showMore(): void {
  shown.value += STEP
}

function toggle(): void {
  isOpen.value = !isOpen.value
  emit('toggle')
}
</script>

<template>
  <section class="vp-file">
    <div class="diff-head vp-file-head">
      <button
        type="button"
        class="vp-file-toggle"
        :aria-expanded="isOpen ? 'true' : 'false'"
        @click="toggle"
      >
        <span class="vp-file-path">{{ file.relpath }}</span>
      </button>
      <span v-if="countable" class="diff-stats">
        <b class="stat-add">+{{ added }}</b> <b class="stat-del">−{{ removed }}</b>
      </span>
      <span v-else class="diff-stats is-pending">—</span>
    </div>

    <div v-if="isOpen" class="vp-file-body">
      <template v-if="file.hunks === null">
        <slot name="pending">
          <p class="diff-pending">
            {{ tooBig && file.size !== null ? `这个文件太大，没有展开（${formatSize(file.size)}）。` : unrenderedText }}
          </p>
        </slot>
      </template>
      <template v-else-if="file.hunks.length > 0">
        <!-- One `<pre>` per file, every line a row inside it: the design's contiguous block. A
             `<pre>` per line would give each line the UA's margin and its own scroll container. -->
        <pre class="file-source diff-body diff-lines"><span
          v-for="row in visibleRows"
          :key="row.num"
          :class="lineClass(row.text)"
        ><b class="diff-num">{{ row.num }}</b><span class="diff-text"
          ><template v-if="row.text.startsWith('+') || row.text.startsWith('-')"
            ><b class="diff-sign">{{ row.text.charAt(0) }}</b>{{ row.text.slice(1) }}</template
          ><template v-else>{{ row.text }}</template></span
        ></span></pre>
        <div v-if="remaining > 0" class="diff-more">
          <span>还有 {{ remaining }} 行未显示</span>
          <button type="button" class="link-button" @click="showMore">显示更多（500 行）</button>
        </div>
      </template>
    </div>
  </section>
</template>
