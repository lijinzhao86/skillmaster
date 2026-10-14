<script setup lang="ts">
import { computed, ref } from 'vue'
import { logout } from './api/account'
import { codeMessage } from './api/errors'
import { useSession } from './composables/useSession'
import FormBanner from './components/FormBanner.vue'

/**
 * The frame every signed-in page sits in: the navigation on the left, the page on the right.
 *
 * **The four pages that are not signed-in pages are not in here** — signing in, registering,
 * resetting and consenting are forms a visitor reaches without an account, and wrapping them in a
 * navigation that leads to pages they cannot open would be showing them a door they cannot use.
 * `App.vue` decides; this component does not know it exists.
 *
 * The active item is read off the path rather than passed in, because the path is where the page is:
 * there is no router (ADR 0015), so a link is a real link and a reload lands on the same item.
 *
 * The groups are GitHub's shape — a small heading over the items that belong to it. Today each group
 * has one item, which is honest about how much there is to navigate; what the headings do is say
 * where the next items go, and `SKILL` is the group the machine authorizations will join.
 */
const { account } = useSession()

/** The avatar letter: one character, because that is all a circle this size holds legibly. */
const initial = computed(() => account.value?.username.charAt(0) ?? '?')

const banner = ref<string | null>(null)
const signingOut = ref(false)

const path = computed(() => window.location.pathname.replace(/\/+$/, '') || '/')
const onSkills = computed(() => path.value === '/' || path.value.startsWith('/skills'))
const onAccount = computed(() => path.value === '/account')

async function signOut(): Promise<void> {
  signingOut.value = true
  const result = await logout()
  signingOut.value = false
  if (result.ok) {
    // A full load, like every other navigation here: the server has just ended the session, and
    // reloading is the simplest way to be consistent with the cookies it replaced.
    window.location.assign('/login')
    return
  }
  banner.value = codeMessage(result.code, result.message)
}
</script>

<template>
  <div class="app">
    <nav class="sidenav" aria-label="主导航">
      <a class="brand" href="/">
        <svg class="brand-mark" viewBox="0 0 24 24" aria-hidden="true">
          <rect x="3" y="4" width="18" height="4.5" rx="2.25" fill="currentColor" />
          <rect x="3" y="10.5" width="13" height="4.5" rx="2.25" fill="currentColor" opacity="0.6" />
          <rect x="3" y="17" width="8" height="4.5" rx="2.25" fill="currentColor" opacity="0.32" />
        </svg>
        SkillMaster
      </a>

      <p class="nav-group">SKILL</p>
      <a class="nav-item" :class="{ 'is-active': onSkills }" href="/">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <rect x="3" y="3" width="7.5" height="7.5" rx="1.8" stroke="currentColor" stroke-width="1.8" />
          <rect x="13.5" y="3" width="7.5" height="7.5" rx="1.8" stroke="currentColor" stroke-width="1.8" />
          <rect x="3" y="13.5" width="7.5" height="7.5" rx="1.8" stroke="currentColor" stroke-width="1.8" />
          <rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.8" stroke="currentColor" stroke-width="1.8" />
        </svg>
        管理
      </a>

      <p class="nav-group">账号</p>
      <a class="nav-item" :class="{ 'is-active': onAccount }" href="/account">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <line x1="3" y1="7" x2="6.4" y2="7" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          <circle cx="9.4" cy="7" r="2.6" stroke="currentColor" stroke-width="1.8" />
          <line x1="12.4" y1="7" x2="21" y2="7" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          <line x1="3" y1="12" x2="12.4" y2="12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          <circle cx="15.4" cy="12" r="2.6" stroke="currentColor" stroke-width="1.8" />
          <line x1="18.4" y1="12" x2="21" y2="12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          <line x1="3" y1="17" x2="5.4" y2="17" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          <circle cx="8.4" cy="17" r="2.6" stroke="currentColor" stroke-width="1.8" />
          <line x1="11.4" y1="17" x2="21" y2="17" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
        设置
      </a>

      <div class="nav-foot">
        <template v-if="account">
          <span class="avatar" aria-hidden="true">{{ initial }}</span>
          <div class="nav-me">
            <p class="nav-who">{{ account.username }}</p>
            <button type="button" class="link" :disabled="signingOut" @click="signOut">
              {{ signingOut ? '退出中…' : '退出登录' }}
            </button>
          </div>
        </template>
        <a v-else href="/login">去登录</a>
      </div>
    </nav>

    <main class="content">
      <FormBanner :message="banner" />
      <slot />
    </main>
  </div>
</template>
