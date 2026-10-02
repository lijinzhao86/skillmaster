<script setup lang="ts">
import { ref } from 'vue'
import FieldFeedback from './FieldFeedback.vue'

defineProps<{
  /** the input's id, which is also what the label points at */
  id: string
  label: string
  modelValue: string
  error: string | null | undefined
  /** what the checks concluded about the value, when anything has judged it */
  verdict?: 'checking' | 'ok' | 'problem'
  /** `new-password` where one is being set, `current-password` where one is being checked */
  autocomplete: 'new-password' | 'current-password'
  /** the rule, shown under the field — a restriction the person has to be told about */
  hint?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  /**
   * The input was left. Declared rather than left to attribute fallthrough: this component's root is
   * the wrapping `div`, and `blur` does not bubble, so a listener placed there would never fire.
   */
  blur: []
}>()

/**
 * Masked until asked otherwise, by the eye at the input's trailing edge.
 *
 * That position and that icon are the established ones — every form the person using this has met
 * before puts the reveal there — so this follows them rather than inventing a control of its own.
 * The icon paths are Heroicons' (`eye`, `eye-slash`, MIT), for the same reason.
 *
 * A revealed password is what actually prevents a typo: the alternative is typing it twice and
 * hoping both attempts were wrong in the same way, which measures worse than it looks. NIST
 * 800-63B-4 §3.1.1.2 advises offering this option and mentions confirmation fields not at all.
 */
const shown = ref(false)

function onInput(event: Event): void {
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}

function onBlur(): void {
  emit('blur')
}
</script>

<template>
  <div class="field">
    <label :for="id">{{ label }}</label>
    <div class="password">
      <input
        :id="id"
        :value="modelValue"
        :type="shown ? 'text' : 'password'"
        :autocomplete="autocomplete"
        placeholder="必填"
        aria-required="true"
        @input="onInput"
        @blur="onBlur"
      />
      <!-- A real button with `type="button"`: inside a form the default is `submit`, so without it
           revealing the password would send the form — an empty-password sign-in attempt on the
           login page. The icon is decoration, so the name comes from `aria-label`, and it says
           which way the switch goes. -->
      <button
        type="button"
        :aria-label="shown ? '隐藏密码' : '显示密码'"
        @click="shown = !shown"
      >
        <svg
          v-if="!shown"
          xmlns="http://www.w3.org/2000/svg"
          fill="none"
          viewBox="0 0 24 24"
          stroke-width="1.5"
          stroke="currentColor"
          aria-hidden="true"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            d="M2.036 12.322a1.012 1.012 0 0 1 0-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178Z"
          />
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"
          />
        </svg>
        <svg
          v-else
          xmlns="http://www.w3.org/2000/svg"
          fill="none"
          viewBox="0 0 24 24"
          stroke-width="1.5"
          stroke="currentColor"
          aria-hidden="true"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            d="M3.98 8.223A10.477 10.477 0 0 0 1.934 12C3.226 16.338 7.244 19.5 12 19.5c.993 0 1.953-.138 2.863-.395M6.228 6.228A10.451 10.451 0 0 1 12 4.5c4.756 0 8.773 3.162 10.065 7.498a10.522 10.522 0 0 1-4.293 5.774M6.228 6.228 3 3m3.228 3.228 3.65 3.65m7.894 7.894L21 21m-3.228-3.228-3.65-3.65m0 0a3 3 0 1 0-4.243-4.243m4.242 4.242L9.88 9.88"
          />
        </svg>
      </button>
    </div>
    <FieldFeedback :message="error" :verdict="verdict" />
    <p v-if="hint" class="hint">{{ hint }}</p>
  </div>
</template>
