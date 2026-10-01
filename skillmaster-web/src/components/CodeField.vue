<script setup lang="ts">
import FieldError from './FieldError.vue'

defineProps<{
  modelValue: string
  error: string | null | undefined
  remaining: number
  canSend: boolean
  sending: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  send: []
}>()

function onInput(event: Event): void {
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}
</script>

<template>
  <div class="field">
    <label for="sms-code">短信验证码</label>
    <div class="row">
      <input
        id="sms-code"
        :value="modelValue"
        maxlength="6"
        inputmode="numeric"
        autocomplete="one-time-code"
        @input="onInput"
      />
      <button type="button" :disabled="!canSend" @click="emit('send')">
        <template v-if="sending">发送中…</template>
        <template v-else-if="remaining > 0">{{ remaining }} 秒后可重发</template>
        <template v-else>获取验证码</template>
      </button>
    </div>
    <FieldError :message="error" />
  </div>
</template>
