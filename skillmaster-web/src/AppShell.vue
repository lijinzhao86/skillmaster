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
      <a class="brand" href="/">SkillMaster</a>

      <p class="nav-group">SKILL</p>
      <a class="nav-item" :class="{ 'is-active': onSkills }" href="/">管理</a>

      <p class="nav-group">账号</p>
      <a class="nav-item" :class="{ 'is-active': onAccount }" href="/account">设置</a>

      <div class="nav-foot">
        <p v-if="account" class="nav-who">{{ account.username }}</p>
        <button v-if="account" type="button" class="link" :disabled="signingOut" @click="signOut">
          {{ signingOut ? '退出中…' : '退出登录' }}
        </button>
        <a v-else href="/login">去登录</a>
      </div>
    </nav>

    <main class="content">
      <FormBanner :message="banner" />
      <slot />
    </main>
  </div>
</template>
