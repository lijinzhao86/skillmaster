<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { codeMessage } from '../api/errors'
import { discardVersion, getSkillDiff, publishVersion } from '../api/skills'
import { useSkillDetail } from '../composables/useSkillDetail'
import type { AuthoredVersion, SkillDiff } from '../api/types'
import DiffView from '../components/DiffView.vue'
import FormBanner from '../components/FormBanner.vue'
import PendingBanner from '../components/PendingBanner.vue'
import SkillHeading from '../components/SkillHeading.vue'

/**
 * What publishing one version would change, against what is live.
 *
 * A page of its own rather than a panel under the content, for the reason a repository host splits
 * the file view from the change view: they answer different questions and each needs the width. The
 * link between them is the banner, which is where the decision is.
 *
 * `?to=` names the version being compared and `?from=` the baseline; both are optional and both mean
 * what they say in the API — omitting `from` compares against what is live, and omitting `to`
 * compares the newest version that is not discarded. The URL is the whole state, so a comparison can
 * be linked to, and the version picker works from here as well as from the content page.
 */
const props = defineProps<{
  namespace: string
  name: string
  version?: number
  from?: number
  to?: number
}>()

const { skill, state, banner, load } = useSkillDetail()

const diff = ref<SkillDiff | null>(null)
const localBanner = ref<string | null>(null)
const acting = ref<number | null>(null)

/**
 * The comparison came back 404 — the address names a version that is not there.
 *
 * A state of its own rather than "the comparison is missing": every other failure (a timeout, a 500)
 * also leaves `diff` null, and it already has a banner. Rendering the 404 sentence underneath that
 * banner said the version does not exist *and* that the request failed, about the same read.
 */
const diffMissing = ref(false)

onMounted(async () => {
  // Which version is being compared: the query when there is one, and otherwise the one the address
  // pinned. `?to=` is what this app's own links carry, but `/skills/<ns>/<name>@2/diff` is an address
  // the server accepts as well, and ignoring its `@2` answered a comparison against the live version
  // while the address in the bar said 2.
  const target = props.to ?? props.version

  // Read before the detail, because it is what says which version this page is about. With neither
  // `?to=` nor a pin the server answers with the newest version that is not discarded — so the answer
  // does not exist until the comparison has been read.
  const result = await getSkillDiff(props.namespace, props.name, target, props.from)
  if (result.ok) {
    diff.value = result.data
  } else if (result.status === 404) {
    // Not a failure to announce. This is the server's single 404 for "that address names nothing",
    // and from a caller who named a version in `?from=` or `?to=` the reading is that the version is
    // not there — a banner would also contradict the skill rendered right underneath it, which the
    // detail read below resolves successfully.
    diffMissing.value = true
  } else if (result.code !== 'unauthenticated') {
    localBanner.value = codeMessage(result.code, result.message)
  }

  // Then the detail, for **that** version rather than for the pointer. Reading the pointer put `@1`
  // in the picker and in the "back to this version" link while the diff below compared `@1 → @2`,
  // because `?to=2` carries no `@` for the address to resolve.
  //
  // **But only when the comparison answered.** `target` is unvalidated — it is whatever the address
  // said — so following it after a 404 asked for a version that does not exist and put the whole page
  // into 「没有这个 skill」, denying a skill the author owns over a mistyped number in a query string.
  // A failure falls back to the pin the address carried, or to the pointer, which is where the page
  // was before there was anything to compare.
  await load(props.namespace, props.name, result.ok ? result.data.to : props.version)
})

const selected = computed<AuthoredVersion | null>(() => skill.value?.version ?? null)

/**
 * Whether the comparison's baseline is the version consumers get.
 *
 * Read from the version list rather than assumed from `from` being absent: `from` is part of the
 * address, so a comparison against a draft can be linked to, and the label has to be true either way.
 */
const fromIsLive = computed(
  () =>
    skill.value?.versions.find((version) => version.number === diff.value?.from)?.is_current === true,
)

/**
 * The waiting version this comparison is about, when it is the one being compared.
 *
 * Narrower than the content page's rule, and deliberately: a banner offering to publish belongs on
 * the comparison of the version that would be published, not on a comparison between two versions
 * of the past.
 */
const bannerAbout = computed<AuthoredVersion | null>(() => {
  if (skill.value === null || diff.value === null) {
    return null
  }
  const compared = skill.value.versions.find((version) => version.number === diff.value?.to) ?? null
  return compared !== null && compared.state === 'draft' ? compared : null
})

/**
 * A version picked here replaces the subject of the comparison and keeps its baseline.
 *
 * `from` is part of the address and the address is the whole state, so dropping it would silently
 * answer a different question: somebody comparing `@1 → @3` who picks `@2` means `@1 → @2`, not
 * `@2` against whatever happens to be live.
 */
function selectVersion(number: number): void {
  const from = props.from !== undefined && props.from !== number ? `&from=${props.from}` : ''
  window.location.assign(
    `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}/diff?to=${number}${from}`,
  )
}

const contentHref = computed(
  () =>
    `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}@${selected.value?.number}`,
)

async function act(action: 'publish' | 'discard', number: number): Promise<void> {
  acting.value = number
  localBanner.value = null
  const result =
    action === 'publish'
      ? await publishVersion(props.namespace, props.name, number)
      : await discardVersion(props.namespace, props.name, number)
  acting.value = null

  if (!result.ok) {
    localBanner.value = codeMessage(result.code, result.message)
    return
  }
  window.location.reload()
}
</script>

<template>
  <div class="card">
    <FormBanner :message="banner ?? localBanner" />
    <p v-if="state === 'loading'" class="muted">加载中…</p>

    <template v-else-if="state === 'missing'">
      <h2>没有这个 skill</h2>
      <p><a href="/">回到我的 skill</a></p>
    </template>

    <template v-else-if="skill && selected">
      <SkillHeading :skill="skill" :selected="selected" @select="selectVersion" />

      <PendingBanner
        v-if="bannerAbout"
        :draft="bannerAbout"
        :diff="diff"
        :compared="true"
        :from-is-live="fromIsLive"
        :compare-href="null"
        :acting="acting !== null"
        @publish="(n) => act('publish', n)"
        @discard="(n) => act('discard', n)"
      />

      <p class="breadcrumb"><a :href="contentHref">← 回到这一版的内容</a></p>

      <DiffView v-if="diff" :diff="diff" :from-is-live="fromIsLive" />
      <!-- Only for the 404. Any other failure has a banner above, and saying "that version does not
           exist" underneath it would be a second and contradictory account of the same read. -->
      <p v-else-if="diffMissing" class="hint">地址里那个版本不存在，没有可比较的东西。</p>
    </template>
  </div>
</template>
