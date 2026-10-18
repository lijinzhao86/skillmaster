<script setup lang="ts">
import { computed } from 'vue'
import type { AuthoredVersion } from '../api/types'
import { versionLabel, versionSuffix } from '../version'

/**
 * Which version the page is showing — the branch box, for versions.
 *
 * **A native `<select>` with `<optgroup>`, not a hand-drawn popover.** The grouping, the keyboard,
 * the wheel on a phone and the label read aloud by a screen reader all come free, and the thing that
 * makes GitHub's branch box a custom control is that it has to hold hundreds of branches and search
 * them. A skill has a handful of versions and never will: each is one submission, and this is
 * somebody's own work.
 *
 * **What is offered is what a reader can read: 线上 and 历史.** 历史 is the published versions the
 * current one superseded — they can be published again, so they are real choices. A draft is not
 * (there is nothing to read of it), and a discarded version never was live. A group with nothing in
 * it is not rendered: an empty heading would read as a bug.
 *
 * **A pinned draft or discarded version still has to appear, disabled.** The address can name one
 * (`/…@2.0.0/files/…`), and a control that silently displayed the live version while the files on
 * screen came from another would be lying about what is being read. So it is drawn in its own group
 * with `disabled` — an answer to "what is on screen", not an offer.
 *
 * **A line says the version, and only the state its group does not already say.** `线上` and `历史`
 * already say their states, so repeating one per row tells the reader nothing. What no heading can
 * express is the pinned discarded row: `已丢弃` is the whole of it, and it is worth the four
 * characters. A timestamp is not in a row at all: a dropdown is scanned rather than read, and the
 * version's name is its identity.
 *
 * **The order is the server's, newest submission first.** Nothing here sorts by version name, because
 * that is a different question: an author may patch an old line after releasing a new one, and it is
 * the submission order that answers "what did I last send" (ADR 0033).
 *
 * **One thing that costs**: a native `<select>` shows only the chosen option's text once it is
 * closed, with no heading beside it. So the control on the page reads `@1.2.3` and does not itself
 * say whether that is the live version. Saying which version a reader is looking at is the *page's*
 * job rather than the picker's — the content page already does it for a draft, in the banner.
 *
 * It emits rather than navigates. Where a version is shown is the page's business — the content page
 * and the diff page both use this and go to different places — and a component that built its own
 * URLs would have to be told which page it is on.
 */
const props = defineProps<{ versions: AuthoredVersion[]; selected: string }>()

const emit = defineEmits<{ select: [string] }>()

/** The version the address pinned, if it names one. */
const pinned = computed(
  () => props.versions.find((version) => versionSuffix(version) === props.selected) ?? null,
)

const groups = computed(() => {
  const current = props.versions.filter((version) => version.is_current)
  // Only published-but-superseded versions are history. A discarded version was never live — it
  // gets a row of its own in the table, and it is not one of the snapshots this picker offers.
  const history = props.versions.filter(
    (version) => !version.is_current && version.state === 'published',
  )
  const groups = [
    { label: '线上', versions: current, disabled: false },
    { label: '历史', versions: history, disabled: false },
  ]
  if (pinned.value !== null && pinned.value.state === 'draft') {
    groups.push({ label: '草稿', versions: [pinned.value], disabled: true })
  }
  if (pinned.value !== null && pinned.value.state === 'discarded') {
    groups.push({ label: '已丢弃', versions: [pinned.value], disabled: true })
  }
  return groups.filter((group) => group.versions.length > 0)
})

/**
 * One line: the version, and the state **only where the heading above it is not already saying it**.
 *
 * `discarded` is the whole of it. The groups list only published versions, so the pinned draft and
 * the pinned discarded version are the two rows a heading does not already name: the draft says
 * nothing extra under `草稿`, while a discarded version says so — it is the one state that can never
 * be published again, which is exactly what a reader of that address needs to know.
 */
function labelOf(version: AuthoredVersion): string {
  const label = `@${versionLabel(versionSuffix(version))}`
  return version.state === 'discarded' ? `${label} · 已丢弃` : label
}

function onChange(event: Event): void {
  emit('select', (event.target as HTMLSelectElement).value)
}
</script>

<template>
  <label class="version-picker">
    <span class="visually-hidden">选择版本</span>
    <select :value="selected" @change="onChange">
      <optgroup v-for="group in groups" :key="group.label" :label="group.label">
        <option
          v-for="version in group.versions"
          :key="versionSuffix(version)"
          :value="versionSuffix(version)"
          :disabled="group.disabled"
        >
          {{ labelOf(version) }}
        </option>
      </optgroup>
    </select>
  </label>
</template>
