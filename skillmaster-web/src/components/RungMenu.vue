<script setup lang="ts">
import { computed } from 'vue'
import MenuSelect, { type MenuGroup } from './MenuSelect.vue'

/**
 * The rung picker: a thin shell over `MenuSelect` (v6).
 *
 * It was the original home of the dropdown mechanism; the version pickers needed the same thing, so
 * the mechanism moved to `MenuSelect` and this component now only maps a list of rungs into the
 * generic option shape. Its contract is unchanged: `options` / `modelValue` in, `update:modelValue`
 * out.
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

const groups = computed<MenuGroup[]>(() => [
  {
    options: props.options.map((option) => ({ value: option.name, label: option.label })),
  },
])
</script>

<template>
  <MenuSelect
    :groups="groups"
    :model-value="modelValue"
    :aria-label="label"
    :labelledby="labelledby"
    @select="emit('update:modelValue', $event)"
  />
</template>
