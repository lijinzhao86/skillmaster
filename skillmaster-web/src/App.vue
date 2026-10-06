<script setup lang="ts">
import { computed, onMounted } from 'vue'
import type { Component } from 'vue'
import AppShell from './AppShell.vue'
import { useSession } from './composables/useSession'
import AccountPage from './pages/AccountPage.vue'
import ConsentPage from './pages/ConsentPage.vue'
import LoginPage from './pages/LoginPage.vue'
import RegisterPage from './pages/RegisterPage.vue'
import ResetPage from './pages/ResetPage.vue'
import SkillDiffPage from './pages/SkillDiffPage.vue'
import SkillFilePage from './pages/SkillFilePage.vue'
import SkillPage from './pages/SkillPage.vue'
import SkillsPage from './pages/SkillsPage.vue'

const { loaded, load } = useSession()

onMounted(() => {
  // Also the CSRF bootstrap: the server writes that cookie while answering a request, so this GET is
  // what makes the first POST possible. See useSession.
  void load()
})

interface Route {
  page: Component
  props?: Record<string, unknown>
  /**
   * Whether this page sits in the signed-in navigation.
   *
   * The four pages that do not are the ones a visitor reaches without an account — signing in,
   * registering, resetting and consenting. Wrapping those in a sidebar of pages they cannot open
   * would be showing them a door they cannot use, and it is why this is decided here rather than
   * inside the shell.
   */
  shell: boolean
}

/**
 * The page, chosen from the path.
 *
 * Links are real `<a href>` and navigation is a full load. That is a decision, not an omission —
 * see the ADR: there are a handful of pages, no shared state and no guards, and reloading after
 * signing in is the simplest way to be consistent with the session and CSRF cookies the server just
 * replaced.
 *
 * **Every value the page needs is in the path or the query, never in component state.** That is what
 * makes a version link a real link: the address a browser shows is the version being looked at, so a
 * reload, a bookmark and the link the CLI opens all land on the same thing.
 */
const route = computed<Route>(() => {
  const path = normalisedPath()
  switch (path) {
    case '/login':
      return { page: LoginPage, shell: false }
    case '/register':
      return { page: RegisterPage, shell: false }
    case '/reset':
      return { page: ResetPage, shell: false }
    case '/consent':
      return { page: ConsentPage, shell: false }
    case '/':
      return { page: SkillsPage, shell: true }
    case '/account':
      return { page: AccountPage, shell: true }
  }

  const skill = skillRouteOf(path)
  return skill ?? { page: SkillsPage, shell: true }
})

function normalisedPath(): string {
  const path = window.location.pathname.replace(/\/+$/, '')
  return path === '' ? '/' : path
}

/**
 * `/skills/<namespace>/<name>[@<version>][/files/<relpath>|/diff]`, or null when the path is not one
 * of those.
 *
 * The version travels inside the second segment — `pdf-tools@3` — which is the same spelling the API
 * uses, and the reason the segments after it can be told apart from a name at all: a skill's name
 * cannot contain a slash (M5), so the third segment is always one of the two known sections.
 *
 * **The file address carries a path, not a segment.** `/files/references/x.md` is two segments after
 * `files`, because an encoded slash is refused by the servlet firewall before routing ever sees it —
 * the same rule the server applies when it advertises a file's address, so this is the inverse of it
 * rather than a second convention. That is why the length is not bounded here: only `diff` is a fixed
 * shape, and everything after `files/` belongs to the file.
 *
 * Decoded per segment, because `location.pathname` is percent-encoded and skill names may be
 * non-ASCII. A malformed escape is not an address, and failing to decode is not a reason to fail the
 * page — it is a typo, and the skills list is the answer to a typo like any other unknown path.
 */
function skillRouteOf(path: string): Route | null {
  const segments = path.split('/').filter((segment) => segment !== '')
  if (segments[0] !== 'skills' || segments.length < 3) {
    return null
  }
  try {
    const namespace = decodeURIComponent(segments[1] as string)
    const address = decodeURIComponent(segments[2] as string)
    const at = address.indexOf('@')
    const suffix = at < 0 ? '' : address.slice(at + 1)
    const pinned = at > 0 && /^[1-9][0-9]*$/.test(suffix)
    const name = pinned ? address.slice(0, at) : address
    const version = pinned ? Number(suffix) : undefined
    const props: Record<string, unknown> = { namespace, name }
    if (version !== undefined) {
      props.version = version
    }

    if (segments.length === 3) {
      return { page: SkillPage, props, shell: true }
    }

    const section = segments[3] as string
    if (segments.length === 4 && section === 'diff') {
      const query = new URLSearchParams(window.location.search)
      const from = versionOf(query.get('from'))
      const to = versionOf(query.get('to'))
      if (from !== undefined) {
        props.from = from
      }
      if (to !== undefined) {
        props.to = to
      }
      return { page: SkillDiffPage, props, shell: true }
    }
    if (section === 'files' && segments.length > 4) {
      const relpath = segments.slice(4).map(decodeURIComponent).join('/')
      return { page: SkillFilePage, props: { ...props, relpath }, shell: true }
    }
    return null
  } catch {
    return null
  }
}

/** A version number from a query parameter, or undefined when it is absent or not one. */
function versionOf(value: string | null): number | undefined {
  return value !== null && /^[1-9][0-9]*$/.test(value) ? Number(value) : undefined
}
</script>

<template>
  <p v-if="!loaded" class="shell muted">加载中…</p>

  <!-- Nothing renders until the session read comes back. A form that submitted before it would be
       refused, because no response would have carried a CSRF cookie yet. -->
  <AppShell v-else-if="route.shell">
    <component :is="route.page" v-bind="route.props" />
  </AppShell>

  <main v-else class="shell">
    <h1><a href="/">SkillMaster</a></h1>
    <component :is="route.page" />
  </main>
</template>
