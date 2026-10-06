<script setup lang="ts">
import VersionPicker from './VersionPicker.vue'
import type { AuthoredSkill, AuthoredVersion } from '../api/types'

/**
 * A skill's name, what it is, and which of its versions is on screen.
 *
 * The same three lines on all three pages — the content, a single file, and the comparison —
 * because which version you are looking at is the one thing that has to be true everywhere, and a
 * page that let you forget it would be showing you a document without saying which one.
 *
 * `namespace / name` is the address, spelled the way the CLI spells it and the way `skillmaster show`
 * takes it. The namespace is always the reader's own in v1, which makes it look redundant and is
 * exactly why it is here: an address a person can copy into a terminal is worth more than the two
 * lines of screen it costs.
 */
defineProps<{ skill: AuthoredSkill; selected: AuthoredVersion }>()

defineEmits<{ select: [number] }>()
</script>

<template>
  <header class="skill-head">
    <div class="skill-title">
      <h2>
        <span class="muted">{{ skill.namespace.slug }} /</span> {{ skill.name }}
      </h2>
      <VersionPicker
        :versions="skill.versions"
        :selected="selected.number"
        @select="$emit('select', $event)"
      />
    </div>
    <p class="hint">{{ skill.title }}</p>
    <p v-if="skill.description" class="muted">{{ skill.description }}</p>
  </header>
</template>
