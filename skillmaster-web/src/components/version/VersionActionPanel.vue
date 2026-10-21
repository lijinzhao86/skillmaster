<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import type { AuthoredVersion } from '../../api/types'
import {
  canEditDraft,
  canMaintain,
  canRevoke,
  useVersionActions,
} from '../../composables/useVersionActions'
import { statusOf, versionRef, versionSuffix, versionTitle, type BumpKind } from '../../version'
import ConfirmDialog from '../ConfirmDialog.vue'
import VersionPicker from '../VersionPicker.vue'

/**
 * The 操作 tab: every state-changing action this version and this rung allow.
 *
 * **One action at a time, each with its consequence above it.** Ordinary actions come first;
 * 下架 — the one that takes something away from callers — is alone in the danger block at the foot.
 * A draft's 丢弃 is *not* dangerous (ADR 0060): it ends only the author's own version, the pointer
 * does not move and no consumer can read a draft. Its button is outlined, its confirm button is not
 * red, and the sheet's first sentence says the version cannot be recovered.
 *
 * **The sheet covers publish, rollback, discard and 下架** — 取消 as the default focus, Esc as
 * cancel, and the focus handed back to the button that opened it. Publish/rollback/discard use a
 * normal confirm button; 下架 uses the danger tone. **History 上线 and 重新上架 are the design's
 * two-step in place** (「确认后立即生效。」 / 取消 / 确认…): the first press only expands them.
 */
const props = defineProps<{
  namespace: string
  name: string
  version: AuthoredVersion
  versions: AuthoredVersion[]
  rung: string
  username: string | null
  live: AuthoredVersion | null
  /**
   * The address's preselection (`?bump=`), from a CLI handoff. Absent means the design's default,
   * 只是修正. It only seeds the form: the radios stay the author's to change, and nothing writes it
   * back to the address (v11 ADR 0074).
   */
  initialBump?: BumpKind
}>()

const status = computed(() => statusOf(props.version))
const maintain = computed(() => canMaintain(props.rung))
const editableDraft = computed(() => canEditDraft(props.version, props.rung, props.username))
const isDraft = computed(() => status.value === 'draft')
/**
 * A draft from before v6 that already declared a number in `SKILL.md`.
 *
 * Such a row keeps its number; the publish request must omit `release_name`, because assigning a
 * second one is refused (TD §版本号的时机). The form would otherwise ask for a number the server
 * will not take.
 */
const legacyNamedDraft = computed(() => isDraft.value && props.version.name !== null)
const isLive = computed(() => status.value === 'live')
const isHistory = computed(() => status.value === 'history')
const isRevoked = computed(() => status.value === 'revoked')

const candidates = computed(() =>
  props.versions
    .filter((v) => statusOf(v) === 'history')
    .sort((a, b) => (b.first_published_at ?? '').localeCompare(a.first_published_at ?? '')),
)

const showPublish = computed(() => isDraft.value && maintain.value)
const showDiscard = computed(() => isDraft.value && editableDraft.value)
const showRollback = computed(() => isLive.value && maintain.value)
const showRestore = computed(() => isRevoked.value && maintain.value)
const showRepublish = computed(() => isHistory.value && maintain.value)
const showRevoke = computed(() => canRevoke(props.version, props.rung))
const hasActions = computed(
  () =>
    showPublish.value ||
    showDiscard.value ||
    showRollback.value ||
    showRestore.value ||
    showRepublish.value,
)

/* --------------------------------------------------------------- publish form */

const rollbackChoice = ref(candidates.value[0] === undefined ? '' : versionSuffix(candidates.value[0]))

function suggestion(): Record<'patch' | 'minor' | 'major', string> {
  const liveName = props.live?.name ?? null
  if (liveName === null) {
    return { patch: '1.0.0', minor: '1.0.0', major: '1.0.0' }
  }
  const base = liveName.split('.').map((part) => Number.parseInt(part, 10) || 0)
  const [major = 0, minor = 0, patch = 0] = base
  return {
    patch: `${major}.${minor}.${patch + 1}`,
    minor: `${major}.${minor + 1}.0`,
    major: `${major + 1}.0.0`,
  }
}

type Bump = BumpKind

const suggested = suggestion()
const bump = ref<Bump>(props.initialBump ?? 'patch')
const releaseName = ref(props.live === null ? '1.0.0' : suggested[bump.value])
const releaseInvalid = ref(false)

/**
 * The live version as the publish block names it: the number first, the author's name in brackets.
 *
 * That is the design's own order in this one sentence (「当前线上是 @1.2.3（split 范围语法）」),
 * the reverse of {@link versionRef}'s "name first" used where the sentence is about the name.
 */
const liveRef = computed(() => {
  const live = props.live
  if (live === null) {
    return ''
  }
  return live.name === null
    ? versionRef(live, props.rung)
    : `@${live.name}（${versionTitle(live, props.rung)}）`
})

const BUMP_CHOICES: Array<[Bump, string, string]> = [
  ['patch', '只是修正', '修好了问题，调用方式不变'],
  ['minor', '新增能力', '多了新东西，旧调用照旧能用'],
  ['major', '旧调用会失效', '旧的调用要改，才能继续用'],
]

function chooseBump(kind: Bump): void {
  bump.value = kind
  releaseName.value = suggested[kind]
  releaseInvalid.value = false
}

/* ----------------------------------------------------------- confirmation state */

type ActionKind = 'publish' | 'rollback' | 'discard' | 'revoke'

interface Ask {
  kind: ActionKind
  title: string
  body: string
  confirmLabel: string
  pendingLabel: string
  danger: boolean
  field?: string
  optionalField?: boolean
  placeholder?: string
  run: (value: string) => Promise<boolean>
}

/**
 * The inline two-step, for the two actions the design keeps out of the sheet: 历史版本「上线」 and
 * 已下架「重新上架」. The sheet is for 回滚, 上线草稿, 丢弃 and the dangerous actions; these two
 * expand 「确认后立即生效。取消 / 确认…」 in place and only the second press submits.
 */
const inline = ref<'republish' | 'restore' | null>(null)

const ask = ref<Ask | null>(null)
const open = ref(false)
let opener: HTMLElement | null = null

const { pending, error, publish, discard, revoke, restore } = useVersionActions(
  props.namespace,
  props.name,
)

function openAsk(
  kind: ActionKind,
  options: Omit<Ask, 'kind' | 'run'>,
  run: (value: string) => Promise<boolean>,
): void {
  opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
  error.value = null
  ask.value = { kind, ...options, run }
  open.value = true
}

function close(): void {
  open.value = false
  ask.value = null
  error.value = null
  void nextTick(() => opener?.focus())
}

async function confirm(value: string): Promise<void> {
  if (ask.value === null) {
    return
  }
  await ask.value.run(value)
  // A successful run reloads; a failed one stays open with the reason under the body.
}

function askPublish(): void {
  const title = versionTitle(props.version, props.rung)
  if (legacyNamedDraft.value) {
    // A pre-v6 draft keeps the number it declared; sending one would be refused as a second name.
    openAsk(
      'publish',
      {
        title: `上线 ${title}？`,
        body: `上线后，这一版沿用版本号 @${props.version.name}，成为当前版本；不指定版本的调用会立刻读到它。名称与说明在这一刻锁定。`,
        confirmLabel: '确认上线',
        pendingLabel: '正在上线…',
        danger: false,
      },
      () => publish(versionSuffix(props.version)),
    )
    return
  }
  const name = releaseName.value.trim()
  // The shape is checked here once; the server owns SemVer 2.0.0 and the collision rules. The
  // pattern is the server's (`common/SemVer.java`), build metadata included: `1.2.3+build.5` is a
  // valid name, and rejecting it here refused a number the server accepts.
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/.test(name)) {
    releaseInvalid.value = true
    return
  }
  openAsk(
    'publish',
    {
      title: `上线 ${title}？`,
      body: `上线后，这一版会拿到版本号 @${name}，成为当前版本；不指定版本的调用会立刻读到它。名称与说明在这一刻锁定——调用者会读到它们，上线后不能再改。`,
      confirmLabel: '确认上线',
      pendingLabel: '正在上线…',
      danger: false,
    },
    () => publish(versionSuffix(props.version), name),
  )
}

function askRollback(): void {
  const target = candidates.value.find((v) => versionSuffix(v) === rollbackChoice.value)
  if (target === undefined) {
    return
  }
  openAsk(
    'rollback',
    {
      title: `回滚到 ${versionTitle(target, props.rung)}？`,
      body: `${versionRef(target, props.rung)} 会重新成为当前版本，${versionRef(props.version, props.rung)} 变成历史版本。不会新建版本，版本号与内容不变；指定 ${props.version.name === null ? '这一版' : `@${props.version.name}`} 的调用仍可读。`,
      confirmLabel: '确认回滚',
      pendingLabel: '正在回滚…',
      danger: false,
    },
    // A rollback moves the pointer, not this page: the design keeps this version's page open and
    // re-renders it as history, so the composable reloads in place instead of navigating to the
    // target's number.
    () => publish(versionSuffix(target), undefined, true),
  )
}

/** Expanding the two-step clears a failure left by the previous attempt. */
function openInline(kind: 'republish' | 'restore'): void {
  error.value = null
  inline.value = kind
}

async function confirmInline(kind: 'republish' | 'restore'): Promise<void> {
  error.value = null
  // A success reloads the page (publish navigates to the version's number; restore reloads), so
  // only the failure path returns here — with the sentence under the buttons.
  if (kind === 'republish') {
    await publish(versionSuffix(props.version))
  } else {
    await restore(versionSuffix(props.version))
  }
}

function askDiscard(): void {
  openAsk(
    'discard',
    {
      title: '丢弃这个版本？',
      body: '丢弃后无法找回：它不会再上线；维护及以上仍能在版本表里看到这条记录，没有恢复的入口。',
      confirmLabel: '确认丢弃',
      pendingLabel: '正在丢弃…',
      danger: false,
    },
    () => discard(versionSuffix(props.version)),
  )
}

function askRevoke(): void {
  openAsk(
    'revoke',
    {
      title: `下架 ${versionRef(props.version, props.rung)}？`,
      body: '下架后，使用这个版本的调用会立刻失败，直到你重新上架为止。',
      confirmLabel: '确认下架',
      pendingLabel: '正在下架…',
      danger: true,
      field: '原因（可选，只有维护者看得到）',
      optionalField: true,
      placeholder: '例如：这个版本有安全问题，需要让调用立刻失败',
    },
    (reason) => revoke(versionSuffix(props.version), reason === '' ? undefined : reason),
  )
}
</script>

<template>
  <div class="vp-actions-main">
    <section v-if="hasActions" class="vp-module" id="vpActions">
      <!-- 上线 / 回滚 / 重新上架 / 丢弃, one block each. -->
      <template v-if="showPublish">
        <div class="vp-action">
          <h3>上线</h3>
          <p v-if="live !== null">
            当前线上是 {{ liveRef }}。上线后，不指定版本的调用会立刻读到这一版；指定旧版本的调用不受影响。
          </p>
          <p v-else>还没有线上版本。这是这个 skill 第一次上线，版本号从 1.0.0 起步。</p>

          <p v-if="legacyNamedDraft" class="vp-note">
            这一版在提交时已经带了版本号 @{{ version.name }}，上线时沿用，不再指定。
          </p>

          <template v-else>
            <template v-if="live !== null">
              <span class="confirm-label">这次改动会影响旧的调用吗</span>
              <div class="choices">
                <label v-for="choice in BUMP_CHOICES" :key="choice[0]" class="choice">
                  <input
                    type="radio"
                    name="vp-bump"
                    :value="choice[0]"
                    :checked="bump === choice[0]"
                    @change="chooseBump(choice[0])"
                  />
                  <span class="choice-body">
                    <span class="choice-title">{{ choice[1] }}</span>
                    <span class="choice-note">{{ choice[2] }} · {{ suggested[choice[0]] }}</span>
                  </span>
                </label>
              </div>
            </template>

            <label class="confirm-field">
              <span class="confirm-label">版本号</span>
              <input
                v-model="releaseName"
                class="confirm-input is-mono"
                :class="{ 'is-invalid': releaseInvalid }"
                type="text"
                autocomplete="off"
                spellcheck="false"
                @input="releaseInvalid = false"
              />
            </label>
          </template>

          <div class="vp-action-acts">
            <button type="button" class="button" @click="askPublish">
              {{ legacyNamedDraft || releaseName.trim() === '' ? '上线' : `上线 @${releaseName.trim()}` }}
            </button>
          </div>
        </div>
      </template>

      <template v-if="showRollback">
        <div class="vp-action">
          <h3>回滚</h3>
          <p v-if="candidates.length > 0">
            所选版本重新上线，{{ versionTitle(version, rung) }} 变成历史版本。不会新建版本，版本号与内容不变。
          </p>
          <p v-else>没有可以回滚的版本——这个 skill 只有当前线上版本。</p>
          <div v-if="candidates.length > 0" class="vp-action-acts">
            <VersionPicker
              :versions="candidates"
              :selected="rollbackChoice"
              aria-label="回滚到哪一版"
              @select="rollbackChoice = $event"
            />
            <button type="button" class="button" @click="askRollback">回滚</button>
          </div>
        </div>
      </template>

      <template v-if="showRepublish">
        <div class="vp-action">
          <h3>上线</h3>
          <p>上线后，这个版本成为当前版本，原来的线上版本变成历史版本；说明与版本号都不再改。</p>
          <div v-if="inline !== 'republish'" class="vp-action-acts">
            <button type="button" class="button" @click="openInline('republish')">
              上线 {{ versionTitle(version, rung) }}
            </button>
          </div>
          <div v-else class="vp-action-acts">
            <span class="is-warn">确认后立即生效。</span>
            <button type="button" class="button is-quiet" :disabled="pending" @click="inline = null">
              取消
            </button>
            <button type="button" class="button" :disabled="pending" @click="confirmInline('republish')">
              {{ pending ? '正在上线…' : '确认上线' }}
            </button>
          </div>
          <p v-if="inline !== null && error !== null" class="confirm-error" role="alert">{{ error }}</p>
        </div>
      </template>

      <template v-if="showRestore">
        <div class="vp-action">
          <h3>重新上架</h3>
          <p>重新上架后，它回到历史版本，调用者又能读它了；要让它重新对外，还需要再上线一次。</p>
          <div v-if="inline !== 'restore'" class="vp-action-acts">
            <button type="button" class="button" @click="openInline('restore')">重新上架</button>
          </div>
          <div v-else class="vp-action-acts">
            <span class="is-warn">确认后立即生效。</span>
            <button type="button" class="button is-quiet" :disabled="pending" @click="inline = null">
              取消
            </button>
            <button type="button" class="button" :disabled="pending" @click="confirmInline('restore')">
              {{ pending ? '正在重新上架…' : '确认重新上架' }}
            </button>
          </div>
          <p v-if="inline !== null && error !== null" class="confirm-error" role="alert">{{ error }}</p>
        </div>
      </template>

      <template v-if="showDiscard">
        <div class="vp-action">
          <h3>丢弃</h3>
          <p>丢弃后无法找回：它不会再上线；维护及以上仍能在版本表里看到这条记录，没有恢复的入口。</p>
          <div class="vp-action-acts">
            <button type="button" class="button is-quiet" @click="askDiscard">丢弃</button>
          </div>
        </div>
      </template>
    </section>

    <section v-if="showRevoke" class="vp-module is-danger" id="vpDanger">
      <p class="vp-eyebrow is-danger">危险操作</p>
      <div class="danger-box">
        <div class="danger-row">
          <div class="danger-what">
            <p class="danger-title">下架</p>
            <p class="hint">下架后，使用这个版本的调用会立刻失败，直到你重新上架为止。</p>
          </div>
          <div class="danger-acts">
            <button type="button" class="button is-danger" @click="askRevoke">下架</button>
          </div>
        </div>
      </div>
    </section>

    <ConfirmDialog
      :open="open"
      :title="ask?.title ?? ''"
      :body="ask?.body ?? ''"
      :confirm-label="ask?.confirmLabel ?? ''"
      :pending-label="ask?.pendingLabel"
      :danger="ask?.danger ?? true"
      :field="ask?.field"
      :optional-field="ask?.optionalField ?? false"
      :placeholder="ask?.placeholder ?? ''"
      :pending="pending"
      :error="error"
      @confirm="confirm"
      @cancel="close"
    />
  </div>
</template>
