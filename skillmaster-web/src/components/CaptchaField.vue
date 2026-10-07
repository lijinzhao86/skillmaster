<script setup lang="ts">
import FieldFeedback from './FieldFeedback.vue'

defineProps<{ modelValue: string; image: string; error: string | null | undefined }>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  refresh: []
}>()

function onInput(event: Event): void {
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}
</script>

<template>
  <div class="field">
    <label for="captcha">图形验证码</label>
    <div class="captcha">
      <img v-if="image" :src="image" alt="图形验证码" />
      <button type="button" class="link" @click="emit('refresh')">换一张</button>
    </div>
    <input
      id="captcha"
      :value="modelValue"
      maxlength="4"
      autocomplete="off"
      autocapitalize="characters"
      placeholder="必填"
      aria-required="true"
      @input="onInput"
    />
    <FieldFeedback :message="error" />
  </div>
</template>
