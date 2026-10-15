<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { logout } from './api/account'
import { codeMessage } from './api/errors'
import { useSession } from './composables/useSession'
import FormBanner from './components/FormBanner.vue'

/**
 * The frame every signed-in page sits in: a top bar across the width, and the navigation beside the
 * page under it.
 *
 * **This file was one delivery behind the design it is built from.** The console deliverable had
 * moved the account out of the sidebar's foot and into a top bar, given that bar the brand and a
 * search box, and regrouped the navigation under `SKILL` and `设置` (`design/skillmaster-控制台演示
 * .html`, and `design/README.md` §外壳). The shell was still built to the previous delivery — brand
 * in the sidebar, no top bar, three groups of one — so the two disagreed on screen. The shape below
 * is the deliverable's, element for element: same regions, same class names, same icons.
 *
 * **Four pages are not in here**, and that has not changed: signing in, registering, resetting and
 * consenting are forms a visitor reaches without an account, and wrapping them in a navigation that
 * leads to pages they cannot open would be showing them a door they cannot use. `App.vue` decides;
 * this component does not know it exists.
 *
 * The active item is read off the path rather than passed in, because the path is where the page is:
 * there is no router (ADR 0015), so a link is a real link and a reload lands on the same item.
 */
const { account } = useSession()

/** The avatar letter: one character, because that is all a circle this size holds legibly. */
const initial = computed(() => account.value?.username.charAt(0) ?? '?')

const banner = ref<string | null>(null)
const signingOut = ref(false)

const path = computed(() => window.location.pathname.replace(/\/+$/, '') || '/')
const onSkills = computed(() => path.value === '/' || path.value.startsWith('/skills'))
const onAccount = computed(() => path.value === '/account')
// The credentials a machine acts with are their own area — the design files them under 设置 as a
// child of 权限, which is the pair the sidebar draws and the breadcrumb names.
const onKeys = computed(() => path.value === '/account/keys')

/* ---------------------------------------------------------------------------------------------
   Three pieces of shell state, none of them the page's.
   --------------------------------------------------------------------------------------------- */

// **None of this goes in the address.** The address is the page's state — which filter, which skill,
// which file — while how far the navigation happens to be unfolded, whether the account menu is open
// and what is sitting in the search box are the frame's business. The design keeps them in local
// state for the same reason (its own comment over `navAccess`).
const menuOpen = ref(false)
const accessOpen = ref(false)
/** Set for as long as the pointer is on the disclosure item after a click, so a click that *closed*
 *  the children is not answered by the hover flyout opening on the spot — see `.nav-flyout`. */
const clickedAccess = ref(false)
const searching = ref('')
const meWrap = ref<HTMLElement | null>(null)

/**
 * The top bar's search hands its question to the skill list, which is the one thing here there is to
 * search. It is an entrance to that page's own filter, not a second filter of its own: the design
 * rejected the reference product's global search modal, and a box that does nothing would be worse
 * than no box at all.
 */
function searchSkills(): void {
  const asked = searching.value.trim()
  window.location.assign(asked === '' ? '/' : `/?q=${encodeURIComponent(asked)}`)
}

/**
 * `/` puts the cursor in the search box, which is what the hint inside it is promising.
 *
 * Ignored while the reader is already typing somewhere — otherwise the character never arrives, and
 * a skill name with a slash in it becomes impossible to type. Modifiers are left alone so that the
 * browser's own `/`-with-a-modifier shortcuts still work.
 */
function onSlash(event: KeyboardEvent): void {
  if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) {
    return
  }
  const active = document.activeElement
  if (
    active !== null &&
    (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || (active as HTMLElement).isContentEditable)
  ) {
    return
  }
  event.preventDefault()
  searchBox.value?.focus()
}

const searchBox = ref<HTMLInputElement | null>(null)

/** Anywhere that is not the menu, which is every menu of this shape. */
function onDocumentClick(event: MouseEvent): void {
  if (!menuOpen.value) {
    return
  }
  if (meWrap.value?.contains(event.target as Node) ?? false) {
    return
  }
  menuOpen.value = false
}

/**
 * Escape closes the account menu and hands the focus back to the avatar.
 *
 * **Only when it is open**, and that is what keeps this from trying to own the key: the keys page
 * binds Escape to "no" on a confirm that is open, and a shell that answered it as well would close a
 * menu nobody had opened while somebody was answering a question.
 */
function onEscape(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || !menuOpen.value) {
    return
  }
  menuOpen.value = false
  menuButton.value?.focus()
}

const menuButton = ref<HTMLButtonElement | null>(null)

onMounted(() => {
  document.addEventListener('keydown', onSlash)
  document.addEventListener('keydown', onEscape)
  document.addEventListener('click', onDocumentClick)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onSlash)
  document.removeEventListener('keydown', onEscape)
  document.removeEventListener('click', onDocumentClick)
})

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
  menuOpen.value = false
}
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div class="topbar-left">
        <a class="brand" href="/">
          <svg class="mark" viewBox="0 0 24 24" aria-hidden="true">
            <rect x="3" y="4" width="18" height="4.5" rx="2.25" fill="currentColor" />
            <rect x="3" y="10.5" width="13" height="4.5" rx="2.25" fill="currentColor" opacity="0.6" />
            <rect x="3" y="17" width="8" height="4.5" rx="2.25" fill="currentColor" opacity="0.32" />
          </svg>
          <span class="brand-name">SkillMaster</span>
        </a>
      </div>

      <form class="topbar-search" role="search" @submit.prevent="searchSkills">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" stroke-width="1.8" />
          <line x1="15.4" y1="15.4" x2="20.6" y2="20.6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
        <label class="visually-hidden" for="topSearch">搜索 skill</label>
        <input
          id="topSearch"
          ref="searchBox"
          v-model="searching"
          type="search"
          placeholder="搜索你的 skill"
          autocomplete="off"
          spellcheck="false"
        />
        <kbd>/</kbd>
      </form>

      <div class="topbar-right">
        <div v-if="account" ref="meWrap" class="me-wrap">
          <button
            ref="menuButton"
            type="button"
            class="me"
            aria-haspopup="true"
            :aria-expanded="menuOpen"
            @click="menuOpen = !menuOpen"
          >
            <span class="avatar" aria-hidden="true">{{ initial }}</span>
            <span class="me-name">{{ account.username }}</span>
          </button>
          <!-- Opened and closed with `hidden` and nothing else: two mechanisms for one menu is how
               they come apart, and the global `[hidden]` rule is the one that hides things here. -->
          <div class="me-menu" :hidden="!menuOpen">
            <!-- `data-sign-out` marks the one control here that navigates without being a link, so
                 the moment screen can ask about it before it does — the same hold it puts on every
                 link, and the design's own list of the exits it covers (README §那一刻). -->
            <button type="button" data-sign-out :disabled="signingOut" @click="signOut">
              {{ signingOut ? '退出中…' : '退出登录' }}
            </button>
          </div>
        </div>
        <!-- **The one case the design has no picture of.** The demo's console is always signed in;
                 a session that expired under a signed-in page is not, and the honest control for
                 that is a way in rather than an avatar with nothing behind it. -->
        <a v-else href="/login">去登录</a>
      </div>
    </header>

    <nav class="sidenav" aria-label="主导航">
      <p class="nav-title">SKILL</p>
      <a class="nav-item" :class="{ 'is-active': onSkills }" href="/" :aria-current="onSkills ? 'page' : undefined">
        <span class="nav-icon">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <rect x="3" y="3" width="7.5" height="7.5" rx="1.8" stroke="currentColor" stroke-width="1.8" />
            <rect x="13.5" y="3" width="7.5" height="7.5" rx="1.8" stroke="currentColor" stroke-width="1.8" />
            <rect x="3" y="13.5" width="7.5" height="7.5" rx="1.8" stroke="currentColor" stroke-width="1.8" />
            <rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.8" stroke="currentColor" stroke-width="1.8" />
          </svg>
        </span>
        <span class="nav-label">管理</span>
      </a>

      <p class="nav-title">设置</p>
      <a
        class="nav-item"
        :class="{ 'is-active': onAccount }"
        href="/account"
        :aria-current="onAccount ? 'page' : undefined"
      >
        <span class="nav-icon">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="12" cy="8.4" r="3.6" stroke="currentColor" stroke-width="1.8" />
            <path
              d="M4.8 20c.9-3.5 3.8-5.5 7.2-5.5s6.3 2 7.2 5.5"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
            />
          </svg>
        </span>
        <span class="nav-label">账号</span>
      </a>

      <!-- **Both behaviours, which is what the reference product does**: hovering shows a flyout
           beside the item for somebody who is still scanning, and clicking opens the children in
           place for somebody who has decided. It starts collapsed on every page rather than opening
           itself because the page you are on is inside it — the breadcrumb says where you are, and a
           navigation that unfolds on its own is presuming on the reader. -->
      <!-- The panel is the item's *sibling* inside a positioned wrapper, where the design nests it
           inside the button. Same hover bridge — the wrapper is the hovered thing, so the pointer
           never leaves it on the way to the panel — but a `<button>` may not contain a link, and the
           flyout is one. -->
      <div
        class="nav-disclosure-wrap"
        :class="{ 'is-clicked': clickedAccess }"
        @mouseleave="clickedAccess = false"
      >
        <button
          type="button"
          class="nav-item nav-disclosure"
          :class="{ 'has-current-child': onKeys }"
          aria-controls="navAccessChildren"
          :aria-expanded="accessOpen"
          @click="
            () => {
              accessOpen = !accessOpen
              clickedAccess = true
            }
          "
        >
          <span class="nav-icon">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="7.6" cy="12" r="4.1" stroke="currentColor" stroke-width="1.8" />
              <line x1="11.7" y1="12" x2="21" y2="12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              <line x1="17.6" y1="12" x2="17.6" y2="16.2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              <line x1="14.2" y1="12" x2="14.2" y2="15" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
          </span>
          <span class="nav-label">权限</span>
          <span class="nav-chevron">
            <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M6 4l4 4-4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </span>
        </button>

        <div class="nav-flyout">
          <div class="nav-flyout-panel">
            <a
              class="nav-item is-child"
              :class="{ 'is-active': onKeys }"
              href="/account/keys"
              :aria-current="onKeys ? 'page' : undefined"
              ><span class="nav-label">API 密钥</span></a
            >
          </div>
        </div>
      </div>

      <div id="navAccessChildren" class="nav-children" :hidden="!accessOpen">
        <a
          class="nav-item is-child"
          :class="{ 'is-active': onKeys }"
          href="/account/keys"
          :aria-current="onKeys ? 'page' : undefined"
          ><span class="nav-label">API 密钥</span></a
        >
      </div>
    </nav>

    <main class="content">
      <FormBanner :message="banner" />
      <slot />
    </main>
  </div>
</template>
