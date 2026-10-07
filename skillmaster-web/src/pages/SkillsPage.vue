<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { codeMessage } from '../api/errors'
import { listSkills } from '../api/skills'
import { loginPath } from '../login'
import { formatTime } from '../time'
import type { AuthoredSkillSummary } from '../api/types'
import { versionLabel, versionSuffix } from '../version'
import FormBanner from '../components/FormBanner.vue'

/**
 * The skills this account has, which is the page the CLI's deep link lands on.
 *
 * A list of names, with one state each — a repository list, not a dashboard. What it is for is
 * answering two questions at a glance: **which of these is live**, and **is anything waiting for
 * me**. The second is the only thing on the page that is clickable beyond the name itself, and it
 * goes straight to the version that is waiting.
 *
 * The state is a dot and a word rather than a colour on its own: colour alone is unreadable to
 * somebody who cannot see it, and the word is what carries the meaning here.
 */
const state = ref<'loading' | 'ready' | 'failed'>('loading')
const skills = ref<AuthoredSkillSummary[]>([])
const banner = ref<string | null>(null)

onMounted(async () => {
  const result = await listSkills()
  if (!result.ok) {
    if (result.code === 'unauthenticated') {
      // This page has no anonymous version — "your skills" is not a question with an answer for
      // nobody — so a session that is missing or has expired ends up at the sign-in form, which
      // knows how to come back here.
      window.location.assign(loginPath())
      return
    }
    state.value = 'failed'
    banner.value = codeMessage(result.code, result.message)
    return
  }
  skills.value = result.data.skills
  state.value = 'ready'
})

function hrefOf(skill: AuthoredSkillSummary): string {
  return `/skills/${encodeURIComponent(skill.namespace)}/${encodeURIComponent(skill.name)}`
}

/**
 * The address of the newest version waiting, which is what the count points at.
 *
 * It goes to that version rather than to the skill because the person clicking it has already
 * decided what they want to look at; sending them to the skill and leaving them to find it is the
 * click this page exists to save. `draft` is null exactly when `drafts` is 0, so the two are read
 * together — see the listing response.
 */
function pendingHref(skill: AuthoredSkillSummary): string {
  return skill.draft === null ? hrefOf(skill) : `${hrefOf(skill)}@${versionSuffix(skill.draft)}`
}

/** A skill's live state, in one phrase. Null `current` is a real state, not a missing value. */
function stateOf(skill: AuthoredSkillSummary): string {
  return skill.current === null ? '未上线' : `已上线 @${versionLabel(versionSuffix(skill.current))}`
}
</script>

<template>
  <div class="card">
    <header class="page-head">
      <h2>我的 skill</h2>
      <span v-if="state === 'ready' && skills.length > 0" class="muted">{{ skills.length }} 个</span>
    </header>

    <FormBanner :message="banner" />

    <p v-if="state === 'loading'" class="muted">加载中…</p>

    <p v-else-if="state === 'ready' && skills.length === 0" class="hint">
      还没有提交过 skill。在本机运行 <code>skillmaster skill submit</code>，提交完会自动打开这一页。
    </p>

    <ul v-else-if="state === 'ready'" class="skills">
      <li v-for="skill in skills" :key="skill.name">
        <div class="skill-row">
          <a class="skill-name" :href="hrefOf(skill)">{{ skill.name }}</a>
          <span class="chip" :class="skill.current === null ? 'is-idle' : 'is-live'">
            <i class="dot" />{{ stateOf(skill) }}
          </span>
        </div>
        <p class="hint">{{ skill.title }}</p>
        <div class="skill-row">
          <!-- Null when every version has been discarded, which is a real state and not a missing
               date: "更新于" with nothing after it would be the page trailing off. -->
          <span v-if="skill.latest_submitted_at !== null" class="muted">
            更新于 {{ formatTime(skill.latest_submitted_at) }}
          </span>
          <span v-else class="muted">每一版都被丢弃了</span>
          <a v-if="skill.drafts > 0" class="pending-link" :href="pendingHref(skill)">
            {{ skill.drafts }} 个待上线 →
          </a>
        </div>
      </li>
    </ul>
  </div>
</template>
