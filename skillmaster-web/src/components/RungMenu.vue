<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'

/**
 * The rung picker: a button and a list the page draws itself (design's `rungMenu`).
 *
 * **Not a native `<select>`.** Its popup is drawn by the system — dark ground, system highlight — and
 * `<option>` cannot be styled, while this page is white paper layered on white paper. The button
 * wears the same border, arrow and size as `select.control`, and the list is a small white card with
 * a check on the chosen row.
 *
 * **The list is `position: fixed`.** The collaborator card clips its rows (`overflow: hidden`, for
 * the rounded corners), so an absolutely positioned list would be cut off; fixed positioning is
 * relative to the viewport and escapes that clip. Where it goes is worked out from the trigger's
 * rectangle when it opens, and it flips above the trigger when there is no room below.
 *
 * **The options are always in the DOM**, hidden — so "what may I choose" is readable by a screen
 * reader and by the design's checks, rather than existing only while the menu is open.
 */
export interface RungOption {
  name: string
  label: string
}

const props = defineProps<{
  options: RungOption[]
  /** The chosen rung's `name`. */
  modelValue: string
  /** Read aloud; the visible label, when there is one, is wired to it by the caller. */
  label?: string
  /** The id of a visible label element — the design's `aria-labelledby`, used for 申请档位. */
  labelledby?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const trigger = ref<HTMLButtonElement | null>(null)
const list = ref<HTMLDivElement | null>(null)
const open = ref(false)
const listStyle = ref<Record<string, string>>({})

function labelFor(name: string): string {
  return props.options.find((option) => option.name === name)?.label ?? name
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
  const below = rect.bottom + 6
  listStyle.value = {
    fontSize: window.getComputedStyle(button).fontSize,
    minWidth: `${Math.round(rect.width)}px`,
    left: `${Math.round(rect.left)}px`,
    top: `${Math.round(below + height > window.innerHeight - 8 ? rect.top - 6 - height : below)}px`,
  }
}

function show(): void {
  open.value = true
  void nextTick(() => {
    position()
    const current =
      list.value?.querySelector<HTMLElement>('[aria-selected="true"]') ?? list.value?.firstElementChild
    ;(current as HTMLElement | null)?.focus()
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

function choose(name: string): void {
  close(true)
  emit('update:modelValue', name)
}

function onTriggerKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    if (!open.value) {
      show()
    }
  }
}

/** Arrow keys, Home/End and Escape inside the list — the same keys a native menu answers to. */
function onListKeydown(event: KeyboardEvent): void {
  const items = Array.from(list.value?.querySelectorAll<HTMLElement>('.menu-item') ?? [])
  const index = items.indexOf(document.activeElement as HTMLElement)
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    items[Math.min(index + 1, items.length - 1)]?.focus()
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    items[Math.max(index - 1, 0)]?.focus()
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
      aria-haspopup="listbox"
      :aria-expanded="open ? 'true' : 'false'"
      :aria-label="label"
      :aria-labelledby="labelledby"
      @click="open ? close(true) : show()"
      @keydown="onTriggerKeydown"
    >
      {{ labelFor(modelValue) }}
    </button>
    <div
      v-show="open"
      ref="list"
      class="menu-list"
      role="listbox"
      :aria-label="label"
      :style="listStyle"
      @keydown="onListKeydown"
    >
      <button
        v-for="option in options"
        :key="option.name"
        type="button"
        role="option"
        class="menu-item"
        :aria-selected="option.name === modelValue ? 'true' : 'false'"
        @click="choose(option.name)"
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
        {{ option.label }}
      </button>
    </div>
  </span>
</template>
