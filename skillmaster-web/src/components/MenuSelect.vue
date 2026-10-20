<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'

/**
 * The page's own dropdown: a button and a list the page draws itself (design's `menuSelect`).
 *
 * **Not a native `<select>`.** Its popup is drawn by the system — dark ground, system highlight — and
 * `<option>` cannot be styled, while this page is white paper layered on white paper. The button
 * wears the same border, arrow and size as `select.control`, and the list is a small white card with
 * a check on the chosen row.
 *
 * **The list is `position: fixed`.** The cards around these controls clip their rows (`overflow:
 * hidden`, for the rounded corners), so an absolutely positioned list would be cut off; fixed
 * positioning is relative to the viewport and escapes that clip. Where it goes is worked out from the
 * trigger's rectangle when it opens, and it flips above the trigger when there is no room below.
 *
 * **The options are always in the DOM**, hidden — so "what may I choose" is readable by a screen
 * reader and by the design's checks, rather than existing only while the menu is open.
 *
 * Extracted from `RungMenu.vue` in v6: the rung picker, the version pickers and the comparison
 * picker are one mechanism. Groups are `{ label?, options }`; an option may carry a mono number, a
 * status tag, a secondary tag and a disabled flag.
 */
export interface MenuOption {
  value: string
  label: string
  /** A mono suffix after the label: the version number in a version picker. */
  number?: string
  /** A status tag at the far right. */
  tag?: string
  /** The tag's tone: `live`, `draft`, `revoked`, or empty for plain. */
  tagTone?: string
  /** A second, quieter tag: 「上一版」 in the comparison picker. */
  meta?: string
  disabled?: boolean
  /** Extra words the search matches, beyond the label and number. */
  searchText?: string
}

export interface MenuGroup {
  label?: string
  options: MenuOption[]
}

const props = withDefaults(
  defineProps<{
    groups: MenuGroup[]
    modelValue: string
    /** Read aloud; the visible label, when there is one, is wired to it by the caller. */
    ariaLabel?: string
    /** A visible label element's id — the design's `aria-labelledby`, used for 申请档位. */
    labelledby?: string
    /** What the closed button says; the selected option's label when omitted. */
    triggerText?: string
    /** A class for the trigger, for the version picker's width rule. */
    triggerClass?: string
    /** Draw a search box above the options and filter them as it is typed. */
    search?: boolean
    searchPlaceholder?: string
    emptyText?: string
    disabled?: boolean
  }>(),
  {
    ariaLabel: undefined,
    labelledby: undefined,
    triggerText: undefined,
    triggerClass: undefined,
    search: false,
    searchPlaceholder: '按名称筛选',
    emptyText: '没有匹配的版本。',
    disabled: false,
  },
)

const emit = defineEmits<{ select: [value: string] }>()

const trigger = ref<HTMLButtonElement | null>(null)
const list = ref<HTMLDivElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const open = ref(false)
const query = ref('')
const listStyle = ref<Record<string, string>>({})

const options = computed(() => props.groups.flatMap((group) => group.options))

const selectedLabel = computed(() => {
  const found = options.value.find((option) => option.value === props.modelValue)
  return found === undefined ? props.modelValue : found.label
})

const triggerLabel = computed(() => props.triggerText ?? selectedLabel.value)

/** A group is hidden when its options all fall out of the search; the popup says so once. */
const shownGroups = computed(() =>
  props.groups.map((group) => ({
    label: group.label,
    options: group.options.map((option) => ({
      option,
      hidden: !matches(option),
    })),
    hidden: group.options.every((option) => !matches(option)),
  })),
)

const noMatches = computed(
  () => props.search && shownGroups.value.every((group) => group.hidden),
)

function matches(option: MenuOption): boolean {
  const needle = query.value.trim().toLowerCase()
  if (needle === '') {
    return true
  }
  const haystack = [
    option.label,
    option.number ?? '',
    option.tag ?? '',
    option.meta ?? '',
    option.searchText ?? '',
  ]
    .join(' ')
    .toLowerCase()
  return haystack.includes(needle)
}

function position(): void {
  const button = trigger.value
  const box = list.value
  if (button === null || box === null) {
    return
  }
  const rect = button.getBoundingClientRect()
  // Measured while hidden, which still gives a height: the element is laid out, only out of sight.
  const height = box.getBoundingClientRect().height
  const width = box.getBoundingClientRect().width
  const below = rect.bottom + 6
  listStyle.value = {
    fontSize: window.getComputedStyle(button).fontSize,
    minWidth: props.search ? '0px' : `${Math.round(rect.width)}px`,
    left: `${Math.round(
      Math.min(Math.max(8, rect.left), Math.max(8, window.innerWidth - width - 8)),
    )}px`,
    top: `${Math.round(below + height > window.innerHeight - 8 ? rect.top - 6 - height : below)}px`,
  }
}

function focusFirst(): void {
  if (props.search) {
    searchInput.value?.focus()
    return
  }
  const current =
    list.value?.querySelector<HTMLElement>('.menu-item[aria-selected="true"]:not(:disabled)') ??
    list.value?.querySelector<HTMLElement>('.menu-item:not(:disabled)')
  current?.focus()
}

function show(): void {
  if (props.disabled) {
    return
  }
  query.value = ''
  open.value = true
  void nextTick(() => {
    position()
    focusFirst()
  })
  document.addEventListener('mousedown', onOutside, true)
  window.addEventListener('scroll', closeOnViewportChange, true)
  window.addEventListener('resize', closeOnViewportChange)
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

function onOutside(event: MouseEvent): void {
  const target = event.target as Node
  if (trigger.value?.contains(target) !== true && list.value?.contains(target) !== true) {
    close(false)
  }
}

function closeOnViewportChange(): void {
  close(false)
}

function choose(value: string): void {
  close(true)
  emit('select', value)
}

function visibleItems(): HTMLElement[] {
  return Array.from(list.value?.querySelectorAll<HTMLElement>('.menu-item') ?? []).filter(
    (item) => item.hidden !== true && !item.hasAttribute('disabled'),
  )
}

function onTriggerKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    if (!open.value) {
      show()
    }
  }
}

function onSearchKeydown(event: KeyboardEvent): void {
  const items = visibleItems()
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    items[0]?.focus()
  } else if (event.key === 'Enter') {
    event.preventDefault()
    const target = items.find((item) => !item.hasAttribute('disabled'))
    if (target !== undefined) {
      choose(target.dataset.value ?? '')
    }
  } else if (event.key === 'Escape') {
    event.preventDefault()
    close(true)
  } else if (event.key === 'Tab') {
    close(false)
  }
}

/** Arrow keys, Home/End and Escape inside the list — the same keys a native menu answers to. */
function onListKeydown(event: KeyboardEvent): void {
  const items = visibleItems()
  const index = items.indexOf(document.activeElement as HTMLElement)
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    items[Math.min(index + 1, items.length - 1)]?.focus()
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    if (index <= 0 && props.search) {
      searchInput.value?.focus()
    } else {
      items[Math.max(index - 1, 0)]?.focus()
    }
  } else if (event.key === 'Home') {
    event.preventDefault()
    items[0]?.focus()
  } else if (event.key === 'End') {
    event.preventDefault()
    items[items.length - 1]?.focus()
  } else if (event.key === 'Escape') {
    event.preventDefault()
    close(true)
  } else if (event.key === 'Tab') {
    close(false)
  }
}

onBeforeUnmount(() => {
  close(false)
})
</script>

<template>
  <span class="menu">
    <button
      ref="trigger"
      type="button"
      class="control menu-trigger"
      :class="triggerClass"
      :aria-haspopup="search ? 'dialog' : 'listbox'"
      :aria-expanded="open ? 'true' : 'false'"
      :aria-label="ariaLabel"
      :aria-labelledby="labelledby"
      :disabled="disabled"
      :data-value="modelValue"
      :title="triggerLabel"
      @click="open ? close(true) : show()"
      @keydown="onTriggerKeydown"
    >
      <span class="menu-trigger-text">{{ triggerLabel }}</span>
    </button>
    <div
      :hidden="!open"
      ref="list"
      class="menu-list"
      :class="{ 'has-search': search }"
      :role="search ? 'dialog' : 'listbox'"
      :aria-label="ariaLabel"
      :style="listStyle"
      @keydown="onListKeydown"
    >
      <div v-if="search" class="menu-search">
        <input
          ref="searchInput"
          v-model="query"
          type="search"
          class="menu-search-input"
          :placeholder="searchPlaceholder"
          :aria-label="searchPlaceholder"
          autocomplete="off"
          @keydown="onSearchKeydown"
        />
      </div>
      <div :class="search ? 'menu-box' : ''" :role="search ? 'listbox' : undefined" :aria-label="search ? ariaLabel : undefined">
        <div
          v-for="(group, groupIndex) in shownGroups"
          :key="groupIndex"
          class="menu-group"
          :hidden="group.hidden"
          :role="group.label ? 'group' : 'presentation'"
          :aria-label="group.label"
        >
          <div v-if="group.label" class="menu-group-label">{{ group.label }}</div>
          <button
            v-for="entry in group.options"
            :key="entry.option.value"
            type="button"
            role="option"
            class="menu-item"
            :hidden="entry.hidden"
            :data-value="entry.option.value"
            :aria-selected="entry.option.value === modelValue ? 'true' : 'false'"
            :aria-disabled="entry.option.disabled ? 'true' : undefined"
            :disabled="entry.option.disabled"
            :title="entry.option.label + (entry.option.number ? ' ' + entry.option.number : '')"
            @click="choose(entry.option.value)"
          >
            <svg class="menu-check" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path
                d="M5 12.4l4.4 4.4L19 7.6"
                stroke="currentColor"
                stroke-width="2.4"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <span class="menu-label">{{ entry.option.label }}</span>
            <span v-if="entry.option.number" class="menu-number">{{ entry.option.number }}</span>
            <span
              v-if="entry.option.tag"
              class="menu-tag"
              :class="entry.option.tagTone ? `is-${entry.option.tagTone}` : ''"
              >{{ entry.option.tag }}</span
            >
            <span v-if="entry.option.meta" class="menu-tag is-meta">{{ entry.option.meta }}</span>
          </button>
        </div>
        <div v-if="noMatches" class="menu-empty">{{ emptyText }}</div>
      </div>
    </div>
  </span>
</template>
