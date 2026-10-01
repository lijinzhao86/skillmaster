<script setup lang="ts">
defineProps<{ message: string | null | undefined }>()
</script>

<template>
  <p v-if="message" class="field-error">{{ message }}</p>
</template>
