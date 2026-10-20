<script setup lang="ts">
import { computed } from 'vue'
import type { AuthoredVersion } from '../../api/types'
import { canMaintain } from '../../composables/useVersionActions'
import { statusOf } from '../../version'

/**
 * What this version's state means for the people calling it — said before any action.
 *
 * The status chip says *where in its life* the version is; this strip says *what that does*: whether
 * it can be read, and what replaces it. The live state draws no strip at all — the chip already says
 * it is live, and "callers pinning an old version are unaffected" is a consequence of the
 * publish/rollback moment, written in those actions' own explanations.
 */
const props = defineProps<{
  version: AuthoredVersion
  live: AuthoredVersion | null
  rung: string
}>()

const info = computed<{ tone: string; text: string; reason?: string } | null>(() => {
  const version = props.version
  const status = statusOf(version)
  if (status === 'draft') {
    let text = '这个版本还没有上线，调用者读不到它。上线时才会分配版本号，之后可以按 @版本 调用。'
    if (!canMaintain(props.rung)) {
      text += '上线需要「维护」档位。'
    }
    return { tone: 'is-draft', text }
  }
  if (status === 'live') {
    return null
  }
  if (status === 'revoked') {
    return {
      tone: 'is-danger',
      text: '指定这个版本的调用会失败（410）。它仍对你可见，可以重新上架。',
      reason: version.revoked_reason ?? '',
    }
  }
  if (status === 'discarded') {
    return { tone: 'is-plain', text: '这个版本从未上线，也不会再上线；它只在作者面留作记录。' }
  }
  return {
    tone: 'is-plain',
    text: `指定 ${version.name === null ? '这一版' : `@${version.name}`} 的调用仍可读取；不指定版本的调用现在读到 ${
      props.live?.name === null || props.live?.name === undefined ? '另一版' : `@${props.live.name}`
    }。`,
  }
})
</script>

<template>
  <div v-if="info !== null" class="vp-status" :class="info.tone">
    <span class="vp-status-dot" aria-hidden="true" />
    <p>
      {{ info.text }}
      <span v-if="info.reason !== undefined && info.reason !== ''" class="vp-status-reason"
        >原因：{{ info.reason }}</span
      >
    </p>
  </div>
</template>
