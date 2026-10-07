<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { codeMessage } from '../api/errors'
import { discardVersion, getSkillBody, getSkillDiff, publishVersion } from '../api/skills'
import { useSession } from '../composables/useSession'
import { useSkillDetail } from '../composables/useSkillDetail'
import type { AuthoredVersion, SkillDiff } from '../api/types'
import { versionSuffix } from '../version'
import FormBanner from '../components/FormBanner.vue'
import MarkdownView from '../components/MarkdownView.vue'
import PendingBanner from '../components/PendingBanner.vue'
import SharingPanel from '../components/SharingPanel.vue'
import SkillHeading from '../components/SkillHeading.vue'

/**
 * One skill: what it says, which files it has, and whether a version is waiting.
 *
 * This is the page the CLI's deep link opens, and it is deliberately the *content* page rather than
 * a version table. What a person needs after submitting is the answer to "what is live, and what
 * would publishing change" — the first is the document below, the second is the banner above it.
 *
 * The version is in the address (`@1.2.3`), so the picker is a link and a reload lands on the same
 * version. With no version the address means "whatever is live", which for a skill nothing has been
 * published from is its newest draft — the one case where the page has to say out loud that there is
 * no live version, because otherwise the document shown would be one nobody approved.
 */
const props = defineProps<{ namespace: string; name: string; version?: string }>()

const { skill, state, banner, load } = useSkillDetail()
const { account } = useSession()

const body = ref<string | null>(null)
/** Whether the body read is still in flight — the difference between 「加载中」 and 「读不到」. */
const readingBody = ref(true)
const diff = ref<SkillDiff | null>(null)
const compared = ref(false)
const acting = ref<string | null>(null)
const localBanner = ref<string | null>(null)

onMounted(async () => {
  const [found, text] = await Promise.all([
    load(props.namespace, props.name, props.version),
    getSkillBody(props.namespace, props.name, props.version),
  ])
  readingBody.value = false
  void found
  if (state.value !== 'ready') {
    return
  }
  if (text.ok) {
    body.value = text.data
  } else if (text.code !== 'unauthenticated') {
    // The file list and the versions are still worth showing, so this is a note rather than a
    // page-level refusal.
    localBanner.value = codeMessage(text.code, text.message)
  }

  const about = bannerAbout.value
  if (about !== null) {
    const changes = await getSkillDiff(props.namespace, props.name, versionSuffix(about))
    compared.value = true
    if (changes.ok) {
      diff.value = changes.data
    }
  }
})

const selected = computed<AuthoredVersion | null>(() => skill.value?.version ?? null)
const newestDraft = computed(
  () => skill.value?.versions.find((version) => version.state === 'draft') ?? null,
)

/**
 * Which waiting version the banner is about, if any.
 *
 * The one on screen when it is a draft — that is what the reader opened and what they are about to
 * act on. Otherwise the newest draft, but **only while the page is showing what is live**: somebody
 * reading a superseded version on purpose is not asking to be told about something else.
 */
const bannerAbout = computed<AuthoredVersion | null>(() => {
  const version = selected.value
  if (version === null) {
    return null
  }
  if (version.state === 'draft') {
    return version
  }
  return version.is_current ? newestDraft.value : null
})

const compareHref = computed(() =>
  bannerAbout.value === null
    ? null
    : `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}/diff?to=${encodeURIComponent(versionSuffix(bannerAbout.value))}`,
)

/**
 * Whether the comparison behind the banner is against the live version.
 *
 * It always is *here* — this page asks for a comparison with no `from`, and the server defaults to
 * what is current — but it is read from the version list rather than asserted, because the sentence
 * says the word 线上 and a page should not say that about a comparison it did not check.
 */
const fromIsLive = computed(
  () =>
    skill.value?.versions.find((version) => versionSuffix(version) === diff.value?.from)?.is_current ===
    true,
)

/**
 * Whether this skill is the caller's own, which is the one thing sharing is gated on.
 *
 * Asked of the session rather than of the server: the address's first segment *is* the owner's
 * namespace — that is what a personal namespace is (§3.2) — so the comparison is exact, and a page
 * that asked the server would be paying a round trip to be told what it already has. An editor
 * grantee fails this test and so sees no panel, which is right: sharing is the owner's.
 *
 * `account` is loaded before any page renders (see `App.vue`), so a null here means "not signed in"
 * rather than "not loaded yet".
 */
const mine = computed(
  () => account.value !== null && account.value.namespace === skill.value?.namespace.slug,
)

function versionHref(version: string): string {
  return `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}@${version}`
}

/** A full load, like every other navigation here (ADR 0015): the address is where the version is. */
function selectVersion(version: string): void {
  window.location.assign(versionHref(version))
}

/**
 * One file of the version on screen.
 *
 * The relpath is encoded per segment rather than whole: `references/x.md` is two path segments, and
 * an encoded slash never survives the servlet firewall. The same rule the server applies when it
 * advertises an address, which is what makes this the inverse of it.
 */
function fileHref(relpath: string): string {
  const current = skill.value
  const version = selected.value === null ? undefined : versionSuffix(selected.value)
  if (current === null || version === undefined) {
    return '/'
  }
  const encoded = relpath.split('/').map(encodeURIComponent).join('/')
  return `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(current.name)}@${version}/files/${encoded}`
}

async function act(action: 'publish' | 'discard', version: string): Promise<void> {
  acting.value = version
  localBanner.value = null
  const result =
    action === 'publish'
      ? await publishVersion(props.namespace, props.name, version)
      : await discardVersion(props.namespace, props.name, version)
  acting.value = null

  if (!result.ok) {
    // The server's reasons are the interesting ones here — a version discarded while it was being
    // published, one that is not a draft — and it says them in the message.
    localBanner.value = codeMessage(result.code, result.message)
    return
  }
  // A reload rather than patching state: the version list, the pointer and the comparison all just
  // changed, and re-reading them is how the page is guaranteed to agree with the server.
  window.location.reload()
}
</script>

<template>
  <div class="card">
    <FormBanner :message="banner ?? localBanner" />
    <p v-if="state === 'loading'" class="muted">加载中…</p>

    <template v-else-if="state === 'missing'">
      <h2>没有这个 skill</h2>
      <p class="hint">这个地址在你名下找不到对应的 skill，可能已经删除了。</p>
      <p><a href="/">回到我的 skill</a></p>
    </template>

    <template v-else-if="skill && selected">
      <SkillHeading :skill="skill" :selected="selected" @select="selectVersion" />

      <PendingBanner
        v-if="bannerAbout"
        :draft="bannerAbout"
        :diff="diff"
        :compared="compared"
        :from-is-live="fromIsLive"
        :compare-href="compareHref"
        :acting="acting !== null"
        @publish="(n) => act('publish', n)"
        @discard="(n) => act('discard', n)"
      />

      <h3>文件（{{ skill.files.length }}）</h3>
      <ul class="files">
        <li v-for="file in skill.files" :key="file.relpath">
          <a :href="fileHref(file.relpath)">{{ file.relpath }}</a>
          <span class="muted">{{ (file.size / 1024).toFixed(1) }} KB</span>
        </li>
      </ul>

      <h3>SKILL.md</h3>
      <MarkdownView v-if="body !== null" :source="body" />
      <!-- Only while it is true. A read that failed leaves `body` null too, and claiming to still be
           loading next to the banner that says it failed is the page contradicting itself. -->
      <p v-else-if="readingBody" class="muted">加载中…</p>

      <!-- Last, and deliberately: this page is the document, and who else may read the skill is the
           least frequent thing anybody opens it for. Putting it above the body pushes the content
           down for every reader to serve a setting most of them are not changing. -->
      <SharingPanel v-if="mine" :namespace="props.namespace" :name="props.name" />
    </template>
  </div>
</template>
