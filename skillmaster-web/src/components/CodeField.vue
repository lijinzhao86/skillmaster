<script setup lang="ts">
import CodeInput from './CodeInput.vue'
import FieldFeedback from './FieldFeedback.vue'

defineProps<{
  modelValue: string
  error: string | null | undefined
  /** what the checks concluded about the value, when anything has judged it */
  verdict?: 'checking' | 'ok' | 'problem'
  remaining: number
  canSend: boolean
  sending: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  send: []
  /** The input was left — declared because `blur` does not bubble out of the boxes' wrapper. */
  blur: []
}>()

function onInput(value: string): void {
  emit('update:modelValue', value)
}
</script>

<template>
  <div class="field">
    <label for="sms-code">短信验证码</label>
    <CodeInput
      id="sms-code"
      :model-value="modelValue"
      @update:model-value="onInput"
      @blur="emit('blur')"
    />
    <FieldFeedback :message="error" :verdict="verdict" />
    <!-- Below the boxes rather than beside them, which is where the flow that has no room for a
         second column puts it — six boxes and a button do not share a phone's width. It reads as a
         link because that is what it is: the code has usually already gone out once, and this asks
         for another. -->
    <p class="hint">
      <button type="button" class="link resend" :disabled="!canSend" @click="emit('send')">
        <template v-if="sending">发送中…</template>
        <template v-else-if="remaining > 0">{{ remaining }} 秒后可重发</template>
        <template v-else>获取验证码</template>
      </button>
    </p>
  </div>
</template>
