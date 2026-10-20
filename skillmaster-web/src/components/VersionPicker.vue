<script setup lang="ts">
import { computed } from 'vue'
import MenuSelect, { type MenuGroup, type MenuOption } from './MenuSelect.vue'
import type { AuthoredVersion } from '../api/types'
import { statusOf, statusText, statusTone, versionSuffix, versionTitle } from '../version'

/**
 * Which version the page is showing — the branch box, for versions.
 *
 * **A `MenuSelect`, not a native `<select>`** (v6): `@Version` alone cannot say whether it is live
 * or history, and the design wants the option to be 「名称 + 版本号 + 状态 tag」 and to be searchable.
 * `MenuSelect` is the page's one dropdown mechanism, so this component only maps versions into it.
 *
 * **What is offered is what the caller passed.** The file tab lists every version this rung can
 * read (live, drafts for `write`, history and revoked for `maintain`); the comparison baseline adds
 * drafts; rollback passes only history. A discarded version has no file snapshot and is never
 * offered. Ordering is the caller's too — a picker that re-sorted would make the comparison default
 * disagree with the rollback default.
 *
 * **A pinned version this rung cannot read is a disabled option**, so the control does not lie about
 * what is on screen. It appears only if the caller passes it in `versions` (the page does when the
 * address pins one); `disabledValues` says which values are display-only.
 *
 * The component emits rather than navigates: where a version is shown is the page's business, and a
 * component that built its own URLs would have to be told which page it is on.
 */
const props = withDefaults(
  defineProps<{
    versions: AuthoredVersion[]
    selected: string
    ariaLabel?: string
    /** Values that are on screen but not choices — the disabled row the control has to show. */
    disabledValues?: string[]
    /** The closed button's text, when the page needs more than the version's own title. */
    triggerText?: string
    /** The previous published version's pin — that option carries the 「上一版」 tag. */
    previousPin?: string
  }>(),
  {
    ariaLabel: '选择版本',
    disabledValues: () => [],
    triggerText: undefined,
    previousPin: undefined,
  },
)

const emit = defineEmits<{ select: [value: string] }>()

const options = computed<MenuOption[]>(() => {
  // A rung that can read no version at all gets 「未上线」, not an empty control that looks broken
  // (design 文件栏, only-draft skill read by `browse`).
  if (props.versions.length === 0) {
    return [{ value: '', label: '未上线', disabled: true }]
  }
  return props.versions.map((version) => {
    const status = statusOf(version)
    const pin = versionSuffix(version)
    return {
      value: pin,
      label: versionTitle(version, 'browse'),
      ...(version.name !== null && version.draft_name != null
        ? { number: `@${version.name}` }
        : {}),
      tag: statusText(status),
      tagTone: statusTone(status),
      ...(pin === props.previousPin ? { meta: '上一版' } : {}),
      disabled: props.disabledValues.includes(pin),
      searchText: statusText(status),
    }
  })
})

const groups = computed<MenuGroup[]>(() => [{ options: options.value }])

const disabled = computed(() => props.versions.length === 0)

const triggerText = computed(() => {
  if (props.versions.length === 0) {
    return '未上线'
  }
  if (props.triggerText !== undefined) {
    return props.triggerText
  }
  const found = options.value.find((option) => option.value === props.selected)
  return found === undefined ? props.selected : found.label
})
</script>

<template>
  <label class="version-picker">
    <span class="visually-hidden">{{ ariaLabel }}</span>
    <MenuSelect
      :groups="groups"
      :model-value="selected"
      :aria-label="ariaLabel"
      :trigger-text="triggerText"
      :trigger-class="'version-picker-trigger'"
      :disabled="disabled"
      search
      search-placeholder="按名称或版本号筛选"
      empty-text="没有匹配的版本。"
      @select="emit('select', $event)"
    />
  </label>
</template>
