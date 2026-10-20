<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { AuthoredVersion } from '../../api/types'
import { useVersionDiff } from '../../composables/useVersionDiff'
import { previousPublished, readableBy } from '../../composables/useVersionActions'
import {
  answersPin,
  fileAnchorId,
  noDifferenceText,
  statusOf,
  versionSuffix,
  versionTitle,
} from '../../version'
import DiffFileBlock from '../DiffFileBlock.vue'
import VersionFileTree from './VersionFileTree.vue'
import VersionPicker from '../VersionPicker.vue'

/**
 * The 改动 tab: the comparison baseline, the file tree, and each file's diff.
 *
 * **清单先行、内容按需、单文件分段** (TD §改动). The list and every file's numstat arrive first;
 * a file's body is fetched when it enters the viewport or when 显示改动 is pressed; each body draws
 * its first 400 lines and 500 more per press. More than 100 files puts the pane into single-file
 * mode, where the tree still lists everything but only the current file is drawn. 「只看文件列表」
 * is an address state (`list=1`). The filter above the tree narrows only the tree (`fq`).
 *
 * The baseline picker lists every version this rung can see, including drafts (`write`) and revoked
 * or discarded versions (`maintain`). The default is the previous published version; a draft, a
 * first version, or a previous version this rung cannot see falls back to the live version, and with
 * neither there is no 改动 tab at all (the parent does not draw it).
 */
const props = defineProps<{
  namespace: string
  name: string
  version: AuthoredVersion
  versions: AuthoredVersion[]
  rung: string
  from?: string
  fq: string
  file?: string
  list: boolean
  live: AuthoredVersion | null
}>()

const emit = defineEmits<{
  'select-baseline': [pin: string]
  summary: [value: { files: number; added: number | null; removed: number | null } | null]
}>()

const comparable = computed(() =>
  props.versions.filter((v) => v !== props.version && readableBy(v, props.rung)),
)

/** The previous published version, by first-publish time — the same order rollback defaults to. */
const previous = computed<AuthoredVersion | null>(() =>
  previousPublished(props.version, comparable.value),
)

/** The baseline: the address's `from`, else the previous version, else the live one. */
const baseline = computed<AuthoredVersion | null>(() => {
  const chosen =
    props.from === undefined ? undefined : comparable.value.find((v) => answersPin(v, props.from!))
  if (chosen !== undefined) {
    return chosen
  }
  if (previous.value !== null) {
    return previous.value
  }
  return props.live !== null && props.live !== props.version && comparable.value.includes(props.live)
    ? props.live
    : null
})

/** The picker's order: live, then history/discarded, then drafts, then revoked (design). */
const pickerVersions = computed(() => {
  const list = comparable.value
  const of = (predicate: (v: AuthoredVersion) => boolean) => list.filter(predicate)
  return [
    ...of((v) => statusOf(v) === 'live'),
    ...of((v) => statusOf(v) === 'history' || statusOf(v) === 'discarded'),
    ...of((v) => statusOf(v) === 'draft'),
    ...of((v) => statusOf(v) === 'revoked'),
  ]
})

const baselinePin = computed(() => (baseline.value === null ? '' : versionSuffix(baseline.value)))

const compareTrigger = computed(() => {
  if (baseline.value === null) {
    return ''
  }
  const title = versionTitle(baseline.value, props.rung)
  return baseline.value === previous.value ? `${title}（上一版）` : title
})

const diff = useVersionDiff(props.namespace, props.name)

const listOnly = ref(props.list)
const filter = ref(props.fq)
const current = ref(props.file ?? '')

const singleMode = computed(
  () => !listOnly.value && diff.files.value.length > 100,
)

const currentFile = computed(() => {
  const files = diff.files.value
  return files.find((file) => file.relpath === current.value) ?? files[0]
})

/** Stable inside the template, where Vue's narrowing of a computed does not survive into calls. */
const currentPath = computed(() => currentFile.value?.relpath ?? '')

// **A failed load is not "no changes".** With the list empty, the old shape reported 0 files and
// `+0 −0` in the tab row — the header claiming the opposite of the pane's own failure sentence.
const summary = computed(() =>
  // Null while the list is still on its way, too: an empty list is not a fact yet, and the tab
  // would claim 「改动 0」 / `+0 −0` for the length of the request.
  diff.failed.value || !diff.finished.value
    ? null
    : {
        files: diff.files.value.length,
        added: diff.totalAdded.value,
        removed: diff.totalRemoved.value,
      },
)

watch(
  summary,
  (value) => emit('summary', value),
  { immediate: true },
)

function updateAddress(mutate: (query: URLSearchParams) => void): void {
  const query = new URLSearchParams(window.location.search)
  mutate(query)
  window.history.replaceState(null, '', `?${query.toString()}`)
}

function setFilter(value: string): void {
  filter.value = value
  updateAddress((query) => {
    if (value.trim() === '') {
      query.delete('fq')
    } else {
      query.set('fq', value)
    }
  })
}

function toggleList(): void {
  // **An address state, not a local switch** (design README: 「它是地址里的一态，不是本地开关」).
  // Flipping it in place left single-file mode with no load: the file list and the current file had
  // not changed, so neither watcher that fetches a body fired, and the pane sat on 「正在加载改动…」
  // with no button to leave. The design navigates to the other state; so does this.
  const query = new URLSearchParams(window.location.search)
  query.delete('file')
  if (listOnly.value) {
    query.delete('list')
  } else {
    query.set('list', '1')
  }
  window.location.assign(`?${query.toString()}`)
}

function hrefFor(relpath: string): string {
  const query = new URLSearchParams(window.location.search)
  query.set('file', relpath)
  query.delete('list')
  return `?${query.toString()}`
}

/**
 * In the many-file layout every block is already drawn, so a tree row scrolls to it. Writing `file=`
 * here would open the single-file layout, which is the other mode's job; the address's `file=` is
 * only for it.
 */
function scrollToFile(relpath: string): void {
  document.getElementById(fileAnchorId(relpath))?.scrollIntoView({ block: 'start' })
}

/** What the pane says when the comparison is real but empty. */
const noDifference = computed(() =>
  noDifferenceText(baselinePin.value === '' ? null : baselinePin.value, baseline.value === props.live),
)

function selectBaseline(pin: string): void {
  emit('select-baseline', pin)
}

/**
 * Bodies are fetched when they enter the viewport (or when the button is pressed). jsdom has no
 * `IntersectionObserver`, and that is fine: the button is the same road.
 */
let observer: IntersectionObserver | null = null

function observePending(element: Element | null, relpath: string): void {
  if (element === null || observer === null) {
    return
  }
  observer.observe(element)
  void relpath
}

onMounted(async () => {
  if ('IntersectionObserver' in window) {
    observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) {
            continue
          }
          observer?.unobserve(entry.target)
          const relpath = entry.target.getAttribute('data-relpath')
          if (relpath !== null) {
            void diff.loadFile(relpath)
          }
        }
      },
      { rootMargin: '240px' },
    )
  }
  const pin = baselinePin.value
  if (pin !== '') {
    await diff.load(versionSuffix(props.version), pin)
  }
})

watch(
  () => diff.files.value.length,
  () => {
    // The list arrives after the address's `file=` was read; an empty list must not throw that away.
    if (diff.files.value.length === 0) {
      return
    }
    if (current.value === '' || !diff.files.value.some((file) => file.relpath === current.value)) {
      current.value = diff.files.value[0]?.relpath ?? ''
    }
    if (singleMode.value && currentFile.value !== undefined) {
      // Single-file mode's address names the open file (design README 1157): entering it without
      // `file=` writes the first one, so the page can be sent to somebody and opens the same file.
      updateAddress((query) => query.set('file', currentFile.value!.relpath))
      void diff.loadFile(currentFile.value.relpath)
    } else if (props.file !== undefined) {
      // Multi-file mode reached through `?file=`: the tree row and the block exist, so bring the
      // block into view instead of leaving the reader at the top of the list.
      const addressed = props.file
      void nextTick(() =>
        document.getElementById(fileAnchorId(addressed))?.scrollIntoView({ block: 'start' }),
      )
    }
  },
  { immediate: true },
)

watch(current, () => {
  if (singleMode.value && currentFile.value !== undefined) {
    void diff.loadFile(currentFile.value.relpath)
  }
})

onBeforeUnmount(() => {
  observer?.disconnect()
})

const currentIndex = computed(() =>
  diff.files.value.findIndex((file) => file.relpath === currentFile.value?.relpath),
)
/** The design marks the pager's ends as a link that cannot go further (demo's `is-disabled`). */
const atFirstFile = computed(() => currentIndex.value <= 0)
const atLastFile = computed(() => currentIndex.value === diff.files.value.length - 1)

function prevHref(): string {
  return hrefFor(diff.files.value[Math.max(0, currentIndex.value - 1)]?.relpath ?? '')
}

function nextHref(): string {
  return hrefFor(
    diff.files.value[
      Math.min(diff.files.value.length - 1, currentIndex.value + 1)
    ]?.relpath ?? '',
  )
}

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
</script>

<template>
  <div class="vp-pane" data-vpane="diff">
    <div class="vp-compare">
      <span class="vp-compare-caption">对比</span>
      <VersionPicker
        :versions="pickerVersions"
        :selected="baselinePin"
        aria-label="对比的版本"
        :trigger-text="compareTrigger"
        :previous-pin="previous === null ? undefined : versionSuffix(previous)"
        @select="selectBaseline"
      />
      <span class="vp-file-count">{{ diff.files.value.length }} 个文件</span>
      <button type="button" class="link-button" @click="toggleList">
        {{ listOnly ? '逐个查看' : '只看文件列表' }}
      </button>
    </div>

    <p v-if="diff.files.value.length > 100 || listOnly" class="hint vp-big-note">
      {{
        listOnly
          ? `只看文件列表：${diff.files.value.length} 个文件有变化。`
          : `这次改动有 ${diff.files.value.length} 个文件。文件内容按需加载。`
      }}
    </p>

    <p v-if="diff.failed.value" class="diff-pending diff-failed">
      <span>加载失败：服务器暂时没有响应。</span>
      <button type="button" class="link-button" @click="diff.load(versionSuffix(version), baselinePin)">重试</button>
    </p>
    <p v-else-if="diff.loading.value && diff.files.value.length === 0" class="diff-pending">
      正在加载改动…
    </p>
    <p v-else-if="diff.finished.value && diff.files.value.length === 0" class="hint">
      {{ noDifference }}
    </p>

    <div v-else class="vp-changes">
      <VersionFileTree
        :files="diff.files.value"
        :filter="filter"
        :current="singleMode ? (currentFile?.relpath ?? null) : null"
        :address-mode="singleMode || listOnly"
        :href-for="hrefFor"
        @update:filter="setFilter"
        @select="scrollToFile"
      />

      <div class="vp-files">
        <p v-if="listOnly" class="hint vp-file-list-note">
          只看文件列表。点左边的文件，或「逐个查看」，打开内容。
        </p>

        <template v-else-if="singleMode && currentFile !== undefined">
          <div class="vp-file-mode">
            <span class="hint">一次显示一个文件</span>
            <div class="vp-file-pager">
              <a
                class="link-button"
                :class="{ 'is-disabled': atFirstFile }"
                :href="prevHref()"
                @click="atFirstFile && $event.preventDefault()"
                >上一个</a
              >
              <span>{{ currentIndex + 1 }} / {{ diff.files.value.length }}</span>
              <a
                class="link-button"
                :class="{ 'is-disabled': atLastFile }"
                :href="nextHref()"
                @click="atLastFile && $event.preventDefault()"
                >下一个</a
              >
            </div>
          </div>
          <DiffFileBlock :file="currentFile" :initial-limit="400">
            <template #pending>
              <p v-if="currentFile.binary" class="diff-pending">二进制文件，不显示内容。</p>
              <div v-else-if="diff.errorOf(currentPath) !== ''" class="diff-pending diff-failed">
                <span>加载失败：服务器暂时没有响应。</span>
                <button type="button" class="link-button" @click="diff.loadFile(currentPath)">重试</button>
              </div>
              <p v-else-if="diff.isLoading(currentPath) || !diff.isLoaded(currentPath)" class="diff-pending">
                正在加载改动…
              </p>
              <p v-else-if="diff.unrenderable(currentFile)" class="diff-pending">
                这个文件太大，没有展开（{{ formatSize(currentFile.size) }}）。
              </p>
            </template>
          </DiffFileBlock>
        </template>

        <template v-else>
          <DiffFileBlock
            v-for="file in diff.files.value"
            :key="file.relpath"
            :id="fileAnchorId(file.relpath)"
            :file="file"
            :initial-limit="400"
          >
            <template #pending>
              <p v-if="file.binary" class="diff-pending">二进制文件，不显示内容。</p>
              <div v-else-if="diff.errorOf(file.relpath) !== ''" class="diff-pending diff-failed">
                <span>加载失败：服务器暂时没有响应。</span>
                <button type="button" class="link-button" @click="diff.loadFile(file.relpath)">重试</button>
              </div>
              <p v-else-if="diff.isLoading(file.relpath)" class="diff-pending">正在加载改动…</p>
              <p
                v-else-if="diff.unrenderable(file)"
                class="diff-pending"
              >
                这个文件太大，没有展开（{{ formatSize(file.size) }}）。
              </p>
              <div
                v-else-if="!diff.isLoaded(file.relpath)"
                :ref="(el) => observePending(el as Element | null, file.relpath)"
                class="diff-pending"
                :data-relpath="file.relpath"
              >
                <span>进入视野后加载</span>
                <button type="button" class="link-button" @click="diff.loadFile(file.relpath)">显示改动</button>
              </div>
            </template>
          </DiffFileBlock>
        </template>
      </div>
    </div>
  </div>
</template>
