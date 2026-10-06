<script setup lang="ts">
import { computed } from 'vue'
import type { AuthoredVersion } from '../api/types'

/**
 * Which version the page is showing — the branch box, for versions.
 *
 * **A native `<select>` with `<optgroup>`, not a hand-drawn popover.** The grouping, the keyboard,
 * the wheel on a phone and the label read aloud by a screen reader all come free, and the thing that
 * makes GitHub's branch box a custom control is that it has to hold hundreds of branches and search
 * them. A skill has a handful of versions and never will: the numbers are allocated one per distinct
 * content, and this is somebody's own work.
 *
 * The groups are the states, which is what a person is actually choosing between — GitHub groups by
 * Branches and Tags for the same reason. A group with nothing in it is not rendered: an empty
 * "待处理" heading would read as a bug.
 *
 * **A line says the number, and only the state its group does not already say.** The groups are the
 * states, so repeating one tells the reader nothing: `已上线` under a `线上` heading and `草稿` under
 * `待处理` are both the heading said twice. What survives that test is the one thing no heading can
 * express — `历史` holds both the versions that were once live and the versions that were thrown
 * away, and the difference is real: the first can be published again (that is a rollback), the second
 * never can. So a discarded row says so and its neighbours stay bare. A timestamp is not in a row at
 * all: a dropdown is scanned rather than read, and the number is the version's identity.
 *
 * **One thing that costs**: a native `<select>` shows only the chosen option's text once it is
 * closed, with no heading beside it. So the control on the page reads `@1` and does not itself say
 * whether that is the live version. Saying which version a reader is looking at is the *page's* job
 * rather than the picker's — the content page already does it for a draft, in the banner.
 *
 * It emits rather than navigates. Where a version is shown is the page's business — the content page
 * and the diff page both use this and go to different places — and a component that built its own
 * URLs would have to be told which page it is on.
 */
const props = defineProps<{ versions: AuthoredVersion[]; selected: number }>()

const emit = defineEmits<{ select: [number] }>()

const groups = computed(() => {
  const order = [...props.versions].sort((left, right) => right.number - left.number)
  const current = order.filter((version) => version.is_current)
  const pending = order.filter((version) => version.state === 'draft')
  const history = order.filter((version) => !version.is_current && version.state !== 'draft')
  return [
    { label: '线上', versions: current },
    { label: '待处理', versions: pending },
    { label: '历史', versions: history },
  ].filter((group) => group.versions.length > 0)
})

/**
 * One line: the number, and the state **only where the heading above it is not already saying it**.
 *
 * `discarded` is the whole of it. A version on the wire is `draft`, `published` or `discarded`, and
 * the groups already separate the first two — `待处理` is the drafts and `线上` is the published one
 * consumers get — so naming them again per row is the heading said twice. `历史` is the group that
 * cannot do that work: it holds the published-but-superseded versions *and* the discarded ones, and
 * the difference is exactly the difference between a version that can be published again (a rollback)
 * and one that can never be. That is worth the four characters.
 */
function labelOf(version: AuthoredVersion): string {
  return version.state === 'discarded' ? `@${version.number} · 已丢弃` : `@${version.number}`
}

function onChange(event: Event): void {
  emit('select', Number((event.target as HTMLSelectElement).value))
}
</script>

<template>
  <label class="version-picker">
    <span class="visually-hidden">选择版本</span>
    <select :value="selected" @change="onChange">
      <optgroup v-for="group in groups" :key="group.label" :label="group.label">
        <option v-for="version in group.versions" :key="version.number" :value="version.number">
          {{ labelOf(version) }}
        </option>
      </optgroup>
    </select>
  </label>
</template>
