<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '../markdown'

/**
 * A skill's `SKILL.md`, read as a document rather than as source.
 *
 * `v-html`, which is only acceptable because of what `renderMarkdown` guarantees: raw HTML in the
 * document is escaped and dangerous URL schemes are refused. Both are pinned by tests — see
 * `src/markdown.ts`, which is where the one rule that matters lives: **turning raw HTML on there
 * means adding a sanitiser here in the same commit.**
 */
const props = defineProps<{ source: string }>()

const html = computed(() => renderMarkdown(props.source))
</script>

<template>
  <div class="markdown-body" v-html="html" />
</template>
