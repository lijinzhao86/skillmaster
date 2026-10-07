<script setup lang="ts">
/**
 * What a field has to say for itself, under the field.
 *
 * The message wins over the verdict when there is one, because a message is always the more specific
 * answer — the server's refusal, the checks' reason — while a verdict is only ever "fine" or "still
 * being asked about". So the three states below are ordered by how much they know, not by when they
 * happen.
 *
 * The icons come from Heroicons (MIT) as their own paths rather than as an approximation of them, for
 * the reason the reveal control does: a shape is worth using because people already recognise it, and
 * a redrawn one is not that shape. Their colour is not the only signal either — a tick and a cross
 * differ in form, and the message is text — so nothing here depends on seeing red.
 */
defineProps<{
  message?: string | null
  /** what the checks concluded, or undefined while the field has not been judged */
  verdict?: 'checking' | 'ok' | 'problem'
}>()
</script>

<template>
  <p v-if="message" class="field-error">
    <svg
      xmlns="http://www.w3.org/2000/svg"
      class="icon"
      fill="none"
      viewBox="0 0 24 24"
      stroke-width="1.5"
      stroke="currentColor"
      aria-hidden="true"
    >
      <path
        stroke-linecap="round"
        stroke-linejoin="round"
        d="m9.75 9.75 4.5 4.5m0-4.5-4.5 4.5M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z"
      />
    </svg>
    {{ message }}
  </p>
  <p v-else-if="verdict === 'ok'" class="field-ok">
    <!-- Named rather than decorative: with no text beside it, the label is the only thing that
         carries "this one is fine" to somebody who cannot see the tick. -->
    <svg
      xmlns="http://www.w3.org/2000/svg"
      class="icon"
      fill="none"
      viewBox="0 0 24 24"
      stroke-width="1.5"
      stroke="currentColor"
      role="img"
      aria-label="通过"
    >
      <path
        stroke-linecap="round"
        stroke-linejoin="round"
        d="M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z"
      />
    </svg>
  </p>
  <p v-else-if="verdict === 'checking'" class="field-checking">检查中…</p>
</template>
