<script setup lang="ts">
defineProps<{ message: string | null }>()
</script>

<template>
  <!-- role=alert so a screen reader announces a refusal that appears without a page change. -->
  <p v-if="message" class="banner" role="alert">{{ message }}</p>
</template>
