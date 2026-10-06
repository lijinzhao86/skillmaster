<script setup lang="ts">
import { computed } from 'vue'
import type { AuthoredVersion, SkillDiff } from '../api/types'

/**
 * The version waiting to be published, and what publishing it would do.
 *
 * This is the one place in the product where a skill becomes what consumers get, and it is modelled
 * on the one place every developer has already seen do it: a branch that is ahead of the default
 * branch, with the comparison and the merge next to it. "This branch is 3 commits ahead of main" is
 * the same sentence as "比线上多 3 个文件的变化", and the buttons are in the same order.
 *
 * **The count comes from a comparison, not from a guess.** It says how many files differ and what
 * the line counts are, because approving something means deciding about what it will change — and a
 * banner that said only "there is a version waiting" would send the reader somewhere else to find
 * out whether it matters. When the comparison could not be read, the banner says that instead of
 * showing a number it does not have.
 *
 * The one case with no number at all is a skill nothing has been published from: there is no
 * baseline, so nothing is "more" than it. That is not a missing value — see `from: null` in the
 * comparison — and the sentence changes rather than the layout.
 */
const props = defineProps<{
  /** The version this banner is about: the one on screen if it is a draft, else the newest one. */
  draft: AuthoredVersion
  diff: SkillDiff | null
  compared: boolean
  /**
   * Whether the comparison's baseline is the version consumers get. Usually yes — it is the default —
   * but `from` is part of the address, so a comparison against any version can be linked to, and
   * saying 「比线上多」 about one that is not against live is a sentence that would be false.
   */
  fromIsLive: boolean
  /** Where the comparison lives, or null on the page that already is it. */
  compareHref: string | null
  acting: boolean
}>()

defineEmits<{ publish: [number]; discard: [number] }>()

/** The baseline, named the way the sentence needs it. */
function baseline(): string {
  return props.fromIsLive ? '线上' : `@${props.diff?.from}`
}

const summary = computed(() => {
  if (props.diff === null) {
    return props.compared ? '没能读出这一版的改动，可以自己去比对。' : '这一版还没有上线。'
  }
  const files = props.diff.files.length
  if (props.diff.from === null) {
    return `还没有上线过，这次会把 ${files} 个文件放上线。`
  }
  if (files === 0) {
    return `和${baseline()}没有区别。`
  }
  let added = 0
  let removed = 0
  for (const file of props.diff.files) {
    added += file.added ?? 0
    removed += file.removed ?? 0
  }
  const counts = added + removed === 0 ? '' : `（+${added} −${removed}）`
  return `比${baseline()}多 ${files} 个文件的改动${counts}。`
})
</script>

<template>
  <section class="pending">
    <p class="pending-title">等你上线：@{{ draft.number }}</p>
    <p class="pending-summary">{{ summary }}</p>
    <div class="pending-actions">
      <a v-if="compareHref !== null" class="button-link" :href="compareHref">查看差异</a>
      <button type="button" :disabled="acting" @click="$emit('publish', draft.number)">
        {{ acting ? '上线中…' : '上线' }}
      </button>
      <button type="button" class="link danger" :disabled="acting" @click="$emit('discard', draft.number)">
        丢弃
      </button>
    </div>
  </section>
</template>
