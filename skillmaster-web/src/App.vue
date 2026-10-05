<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useSession } from './composables/useSession'
import ConsentPage from './pages/ConsentPage.vue'
import HomePage from './pages/HomePage.vue'
import LoginPage from './pages/LoginPage.vue'
import RegisterPage from './pages/RegisterPage.vue'
import ResetPage from './pages/ResetPage.vue'

const { loaded, load } = useSession()

onMounted(() => {
  // Also the CSRF bootstrap: the server writes that cookie while answering a request, so this GET is
  // what makes the first POST possible. See useSession.
  void load()
})

/**
 * The page, chosen from the path.
 *
 * Links are real `<a href>` and navigation is a full load. That is a decision, not an omission —
 * see the ADR: there are three pages, no shared state and no guards, and reloading after signing in
 * is the simplest way to be consistent with the session and CSRF cookies the server just replaced.
 */
const page = computed(() => {
  switch (window.location.pathname) {
    case '/login':
      return LoginPage
    case '/register':
      return RegisterPage
    case '/reset':
      return ResetPage
    case '/consent':
      return ConsentPage
    default:
      return HomePage
  }
})
</script>

<template>
  <main class="shell">
    <h1><a href="/">SkillMaster</a></h1>
    <!-- Nothing renders until the session read comes back. A form that submitted before it would be
         refused, because no response would have carried a CSRF cookie yet. -->
    <p v-if="!loaded" class="muted">加载中…</p>
    <component :is="page" v-else />
  </main>
</template>
