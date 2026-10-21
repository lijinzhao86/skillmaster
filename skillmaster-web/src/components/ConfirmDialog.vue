<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

/**
 * The one confirmation sheet (design's `.confirm` / `#dangerConfirm`).
 *
 * Every action that changes a version or the skill's service goes through it: one title saying what
 * the step is, one body saying what it takes away, **取消 as the default focus**, Esc equal to 取消,
 * and the focus returned to whatever opened it (the caller owns that last part). Waiting, success
 * and failure all happen in this one sheet: the confirm button becomes 「正在…」, both buttons go
 * unavailable, and Esc cannot close a request that is already in flight; a failure leaves the reason
 * under the body and puts the buttons back for a retry.
 *
 * The destructive *tone* is not the same as "this needs confirming": discard is outlined and its
 * confirm button is not red (ADR 0060), while 下架 and 下线 are red. `danger` carries that.
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    body: string
    confirmLabel: string
    /** The safe answer's label. 取消 everywhere except the leave sheet, which says 继续编辑. */
    cancelLabel?: string
    pendingLabel?: string
    /** Red confirm button. `false` for rollback, publish and discard (ADR 0060). */
    danger?: boolean
    /** A field's label; when present the sheet asks for text as well as an answer. */
    field?: string
    /** The field may be left empty (a delist reason does not have to be written). */
    optionalField?: boolean
    placeholder?: string
    pending?: boolean
    error?: string | null
  }>(),
  {
    cancelLabel: '取消',
    pendingLabel: '正在处理…',
    danger: true,
    field: undefined,
    optionalField: false,
    placeholder: '',
    pending: false,
    error: null,
  },
)

const emit = defineEmits<{ confirm: [value: string]; cancel: [] }>()

const cancelButton = ref<HTMLButtonElement | null>(null)
const value = ref('')

const acceptDisabled = computed(
  () =>
    props.pending ||
    (props.field !== undefined && !props.optionalField && value.value.trim() === ''),
)

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return
    }
    value.value = ''
    // 取消 is the default focus for every one of these, including a sheet that also asks for a
    // reason: the safe answer is the one under the fingers.
    void nextTick(() => cancelButton.value?.focus())
  },
)

function onKeydown(event: KeyboardEvent): void {
  // A request already sent cannot be closed from under its own result.
  if (event.key !== 'Escape' || props.pending) {
    return
  }
  event.preventDefault()
  emit('cancel')
}

function confirm(): void {
  if (acceptDisabled.value) {
    return
  }
  emit('confirm', props.field === undefined ? '' : value.value.trim())
}
</script>

<template>
  <div v-if="open" class="confirm" @keydown="onKeydown">
    <div
      class="confirm-sheet"
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirmTitle"
      aria-describedby="confirmBody"
    >
      <h2 id="confirmTitle" class="confirm-title">{{ title }}</h2>
      <p id="confirmBody" class="confirm-body">{{ body }}</p>
      <p v-if="error !== null" class="confirm-error" role="alert">{{ error }}</p>
      <label v-if="field !== undefined" class="confirm-field">
        <span class="confirm-label">{{ field }}</span>
        <textarea
          v-model="value"
          class="confirm-input"
          rows="3"
          :placeholder="placeholder"
          :disabled="pending"
        />
      </label>
      <div class="confirm-acts">
        <button
          ref="cancelButton"
          type="button"
          class="button is-quiet"
          :disabled="pending"
          @click="emit('cancel')"
        >
          {{ cancelLabel }}
        </button>
        <button
          type="button"
          class="button"
          :class="{ 'is-danger': danger }"
          :disabled="acceptDisabled"
          @click="confirm"
        >
          {{ pending ? pendingLabel : confirmLabel }}
        </button>
      </div>
    </div>
  </div>
</template>
