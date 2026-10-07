<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { codeMessage } from '../api/errors'
import { getSkillFile } from '../api/skills'
import { useSkillDetail } from '../composables/useSkillDetail'
import { loginPath } from '../login'
import type { AuthoredVersion } from '../api/types'
import { versionLabel, versionSuffix } from '../version'
import FormBanner from '../components/FormBanner.vue'
import MarkdownView from '../components/MarkdownView.vue'
import SkillHeading from '../components/SkillHeading.vue'

/**
 * One file of one version — what the file list opens.
 *
 * **Markdown is rendered; everything else is shown as its own bytes.** That is the rule a repository
 * host already taught everybody: a README reads as a document and a `schema.json` does not pretend
 * to be one. It is also why this page exists at all rather than opening a dialog: the reader is
 * looking at a file, which is a thing with an address.
 *
 * Binary files are never fetched. The manifest says which ones they are — the page has it from the
 * detail — and decoding arbitrary bytes as text to then print replacement characters would be a
 * worse answer than saying what they are.
 */
const props = defineProps<{ namespace: string; name: string; version?: string; relpath: string }>()

const { skill, state, banner, load } = useSkillDetail()

const content = ref<string | null>(null)
/** Whether the file read is in flight — the difference between 「加载中」 and 「读不到」. */
const reading = ref(false)
const localBanner = ref<string | null>(null)

/**
 * The version loaded, and it does not have this file.
 *
 * **A state of this page's own, not `missing`.** `missing` is the composable's and means "there is no
 * such skill for you", which is a different claim about a different thing — and the two rendered
 * through one branch said 「这个版本里没有 SKILL.md」 about a version the page had never read, with a
 * link to `/…/name@undefined`.
 */
const notFoundFile = ref(false)

onMounted(async () => {
  await load(props.namespace, props.name, props.version)
  if (state.value !== 'ready') {
    return
  }

  const entry = skill.value?.files.find((file) => file.relpath === props.relpath) ?? null
  if (entry === null) {
    notFoundFile.value = true
    return
  }
  if (entry.is_binary) {
    // Not an error and not a fetch: the manifest already said what this is.
    return
  }

  reading.value = true
  const version = skill.value === null ? undefined : versionSuffix(skill.value.version)
  const result = await getSkillFile(props.namespace, props.name, version, props.relpath)
  reading.value = false
  if (result.ok) {
    content.value = result.data
    return
  }
  if (result.code === 'unauthenticated') {
    // This is the only read in the app that can come back 401 *after* a read that succeeded — the
    // session lapsed between the two. Everywhere else a 401 is the load's business; here it has to
    // be handled, or the page sits on 「加载中」 for ever with no banner and no way out.
    window.location.assign(loginPath())
    return
  }
  localBanner.value = codeMessage(result.code, result.message)
})

const selected = computed<AuthoredVersion | null>(() => skill.value?.version ?? null)
const isBinary = computed(
  () => skill.value?.files.find((file) => file.relpath === props.relpath)?.is_binary === true,
)
/** Both extensions the server serves as `text/markdown` — see its `MediaTypes.BY_EXTENSION`. */
const isMarkdown = computed(() => /\.(md|markdown)$/i.test(props.relpath))

function selectVersion(version: string): void {
  window.location.assign(
    `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}@${version}/files/${props.relpath
      .split('/')
      .map(encodeURIComponent)
      .join('/')}`,
  )
}

/** Back to the version this file belongs to. */
const backHref = computed(() => {
  const version = selected.value
  return version === null
    ? '/'
    : `/skills/${encodeURIComponent(props.namespace)}/${encodeURIComponent(props.name)}@${versionSuffix(version)}`
})
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

    <template v-else-if="notFoundFile">
      <h2>没有这个文件</h2>
      <p class="hint">这个版本里没有 {{ relpath }}。</p>
      <p><a :href="backHref">回到这个 skill</a></p>
    </template>

    <template v-else-if="skill && selected">
      <SkillHeading :skill="skill" :selected="selected" @select="selectVersion" />

      <p class="breadcrumb">
        <a :href="backHref">{{ skill.name }}@{{ versionLabel(versionSuffix(selected)) }}</a> / <code>{{ relpath }}</code>
      </p>

      <p v-if="isBinary" class="hint">
        这是二进制文件，不在这里展示。本机用 <code>skillmaster skill get</code> 取它。
      </p>
      <MarkdownView v-else-if="content !== null && isMarkdown" :source="content" />
      <pre v-else-if="content !== null" class="markdown">{{ content }}</pre>
      <p v-else-if="reading" class="muted">加载中…</p>
    </template>
  </div>
</template>
