<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { codeMessage } from '../api/errors'
import {
  approveAccessRequest,
  denyAccessRequest,
  grantSkill,
  listAccessRequests,
  listGrants,
  revokeGrant,
} from '../api/skills'
import type { AccessRequest, Rung, SkillGrant } from '../api/types'
import { rungChoices, rungLabel, rungsAbove, rungsUpTo } from '../rungs'
import RungMenu from './RungMenu.vue'

/**
 * 设置 › 协作者 — who may do what, and who is waiting to.
 *
 * Three blocks in the design's order, which is "the most frequent thing first": 共享给 (most people
 * opening this page came to add somebody), the名单 (with its search and pager), and 待处理的申请
 * last — its long-term home is a notification surface that does not exist yet, so the second-level
 * menu's count is what points at it today.
 *
 * **The owner is in the list too.** They are not on the ladder and cannot be handed out, but "who
 * can touch this skill" is one column of facts — GitHub's Manage access and GitLab's Members both
 * put the owner first. The owner's cell is a read-only 所有者 with no 撤销.
 *
 * **Search and the page number live in the address**, like the skill list's: a filtered list of
 * collaborators is a thing somebody can send to somebody else. The server cuts the page (design's
 * own `q` / `p`), so this component never slices a list it fetched whole.
 */
const props = defineProps<{
  namespace: string
  name: string
  /** Private is `publicRung === null`; the list's sentences and offers read that one fact. */
  publicRung: Rung | null
}>()

/**
 * The queue's size, told to the page rather than fetched a second time: the settings nav's count and
 * this block are two views of one fact, and two reads would be two chances for them to disagree.
 */
const emit = defineEmits<{ count: [value: number] }>()

const params = new URLSearchParams(window.location.search)
const query = ref((params.get('q') ?? '').trim())
const page = ref(Math.max(1, Number.parseInt(params.get('p') ?? '1', 10) || 1))

/** True between clamping an out-of-range page and the read of the page it landed on. */
const clamping = ref(false)

/** The server's page size, learned from the first response — the pager only reports what came back. */
const pageSize = ref(10)
const PAGE_SIZE = computed(() => pageSize.value)

const grants = ref<SkillGrant[]>([])
const total = ref(0)
const requests = ref<AccessRequest[]>([])
const banner = ref<string | null>(null)
const handle = ref('')
const offered = computed(() => rungsAbove(props.publicRung))
const shareRole = ref<Rung>(offered.value[0]?.name ?? 'invoke')
const busy = ref(false)

/** Requests carry their own chosen rung; a rejection or approval clears the row anyway. */
const wanted = ref<Record<string, Rung>>({})

/** The owner's handle. A namespace's slug is the handle it was created from (design's `dev`). */
const owner = computed(() => props.namespace)

/** A one-line answer under the block heading — different sentences for private/public and empty. */
const hint = computed(() => {
  if (props.publicRung === null) {
    return population.value <= 1
      ? '这个 skill 是私有的，目前只有你可以访问。在「共享给」中填写用户名并选择档位，即可添加协作者。'
      : '这个 skill 是私有的，只有你和下列协作者可以访问。'
  }
  return (
    `公开之后${rungsUpTo(props.publicRung)}对所有人开放。` +
    (population.value <= 1
      ? '目前没有单独授予个人的档位，可以通过「共享给」添加。'
      : '以下为单独授予个人的档位；个人的实际权限取两者中较高的一档。')
  )
})

const ownerMatches = computed(() => {
  const term = query.value.trim().toLowerCase()
  return term === '' || owner.value.toLowerCase().includes(term)
})

/**
 * The number of people this skill has, owner included.
 *
 * **Not the filtered count.** The server counts only the rows that match `q`, so a search with no
 * matches would otherwise make the empty-state sentence claim the skill has no collaborators at all —
 * a statement about the account made from a statement about one query. The unfiltered number is
 * remembered from the last read that asked no question, and the heading shows the search's number
 * the way the design does ("the number of rows on screen").
 */
const knownTotal = ref<number | null>(null)

/** Rows on screen: the owner (first, always) plus this page of grants. */
const rowCount = computed(() => total.value + (ownerMatches.value ? 1 : 0))

/**
 * How many people the skill has, ignoring the search. Used for the sentence under the heading,
 * which is about the skill rather than about the query.
 */
const population = computed(() => (knownTotal.value ?? total.value) + 1)

const pages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE.value)))

/**
 * "By x–y of N" for the rows on screen. The owner is one row on page one and is not a grant the
 * server pages by, so page one's range starts at 1 and includes that extra row; later pages are
 * offset by it.
 */
const range = computed(() => {
  const extra = page.value === 1 && ownerMatches.value ? 1 : 0
  // Page one always starts at the first person. Every later page is offset by one more row only
  // when the owner was part of the match — a search that misses the owner does not reserve his row,
  // so offsetting those pages too made the range start one past the first row on screen and end
  // past the population.
  const start =
    page.value === 1
      ? 1
      : (page.value - 1) * PAGE_SIZE.value + 1 + (ownerMatches.value ? 1 : 0)
  return { start, end: start + grants.value.length + extra - 1 }
})

/** Where the settings sub-page keeps its own state, so 协作者 and 常规 can be walked between. */
function writeAddress(): void {
  const next = new URLSearchParams(window.location.search)
  next.set('tab', 'settings')
  next.set('sub', 'collaborators')
  if (query.value === '') {
    next.delete('q')
  } else {
    next.set('q', query.value)
  }
  if (page.value === 1) {
    next.delete('p')
  } else {
    next.set('p', String(page.value))
  }
  const queryString = next.toString()
  window.history.replaceState(null, '', queryString === '' ? window.location.pathname : `?${queryString}`)
}

async function loadGrants(): Promise<void> {
  const result = await listGrants(props.namespace, props.name, query.value, page.value)
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  grants.value = result.data.grants
  total.value = result.data.total
  pageSize.value = result.data.page_size
  if (query.value === '') {
    knownTotal.value = result.data.total
  }
  // Clamp a page number past the end, the same rule the skill list follows. The watcher on `page`
  // owns the write and the read: doing either here as well sent the same page twice.
  const last = Math.max(1, Math.ceil(result.data.total / result.data.page_size))
  if (page.value > last) {
    // Until the clamped page's own answer lands, the rows on screen are the empty answer for the
    // page that does not exist — a pager drawn then would say 「第 12–11 人」. Hide it for that
    // window rather than print a range that is not about what is on screen.
    clamping.value = true
    page.value = last
    return
  }
  clamping.value = false
  await nextTick()
  sizeMemberColumn()
  reserveHeight()
}

/** Reads the unfiltered population. The previous value stays until this answer arrives. */
async function fetchPopulation(): Promise<void> {
  const result = await listGrants(props.namespace, props.name, undefined, 1)
  if (result.ok) {
    knownTotal.value = result.data.total
  }
}

/**
 * The skill's population, ignoring the search — for the sentence under the heading, which is about
 * the skill and not about one query. Read once when the page was opened on a search: the filtered
 * response's own total cannot answer it.
 */
async function loadPopulation(): Promise<void> {
  if (query.value === '' || knownTotal.value !== null) {
    return
  }
  await fetchPopulation()
}

/**
 * A grant changed, so the population sentence has to be re-read: when the page is filtered the
 * reloaded list says nothing about how many people the skill has. Without this the hint kept saying
 * 「目前只有你可以访问」 after the first person was added.
 *
 * **The old value is not cleared first.** It is a number from a moment ago, not a lie; replacing it
 * with `null` made `population` fall back to the filtered total, and the hint briefly claimed the
 * skill was private to one person — the exact sentence this refresh exists to correct.
 */
async function refreshPopulation(): Promise<void> {
  if (query.value === '') {
    // The reloaded list carries the count itself.
    return
  }
  await fetchPopulation()
}

async function loadRequests(): Promise<void> {
  const result = await listAccessRequests(props.namespace, props.name)
  if (!result.ok) {
    // Not everybody reaching this component may see the queue; the block simply does not draw.
    requests.value = []
    emit('count', 0)
    return
  }
  requests.value = result.data.requests
  emit('count', requests.value.length)
  await nextTick()
  sizeMemberColumn()
  for (const request of requests.value) {
    if (wanted.value[request.handle] === undefined) {
      const offeredNames = offered.value.map((rung) => rung.name)
      wanted.value[request.handle] = offeredNames.includes(request.wanted_role)
        ? request.wanted_role
        : (offeredNames[0] ?? 'invoke')
    }
  }
}

onMounted(async () => {
  await Promise.all([loadGrants(), loadRequests(), loadPopulation()])
  await nextTick()
  sizeMemberColumn()
})

// Typing filters this card and turns back to its first page: a search that stayed on page two would
// look like "there is nothing". Debounced, because the page is cut on the server — a round trip per
// keystroke is what makes a search box feel like a form.
let searchTimer: number | undefined
watch(query, () => {
  page.value = 1
  writeAddress()
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    void loadGrants()
  }, 200)
})

watch(page, () => {
  writeAddress()
  void loadGrants()
})

function goToPage(number: number): void {
  page.value = number
}

/** The pager's own address, carrying the search — a link, like every other page number here. */
function pagerHref(number: number): string {
  const next = new URLSearchParams(window.location.search)
  next.set('tab', 'settings')
  next.set('sub', 'collaborators')
  if (query.value !== '') {
    next.set('q', query.value)
  }
  if (number === 1) {
    next.delete('p')
  } else {
    next.set('p', String(number))
  }
  return `?${next.toString()}`
}

function avatarInitial(person: string): string {
  return person.slice(0, 1)
}

/**
 * The name column's width, measured once from the longest handle in the list.
 *
 * Each row is its own grid, so CSS cannot ask "the widest of all rows" — a hidden probe measures the
 * text itself and the result is written to the pane. The bounds are the design's: 96 at the narrowest
 * (two-character handles still breathe) and 320 at the widest (a 30-character handle measures ~276).
 */
function sizeMemberColumn(): void {
  const pane = document.querySelector<HTMLElement>('.settings-pane[data-sub="collaborators"]')
  if (pane === null) {
    return
  }
  const probe = document.createElement('span')
  probe.className = 'grant-who'
  probe.style.cssText =
    'position:absolute;left:-9999px;top:0;width:auto;display:inline-block;visibility:hidden;white-space:pre;'
  document.body.appendChild(probe)
  let widest = 0
  const names = [owner.value, ...grants.value.map((grant) => grant.handle), ...requests.value.map((request) => request.handle)]
  for (const name of names) {
    probe.textContent = name
    widest = Math.max(widest, probe.offsetWidth)
  }
  probe.remove()
  pane.style.setProperty('--member-name', `${Math.min(Math.max(widest + 2, 96), 320)}px`)
}

/**
 * A page that is not full keeps a full page's height, so the block after it does not jump when the
 * pager moves. The last row of such a page keeps its own hairline too (`is-partial`).
 */
const partial = computed(
  () => total.value > PAGE_SIZE.value && grants.value.length < PAGE_SIZE.value,
)

/**
 * A short page keeps a full page's height, so the block after it does not jump when the pager moves.
 * Measured from the rows themselves, because a row's height depends on the window (a long handle
 * wraps at narrow widths) and a hard-coded number would be wrong at one of them.
 */
const listEl = ref<HTMLElement | null>(null)
const reservedHeight = ref<string | undefined>(undefined)

function reserveHeight(): void {
  if (!partial.value || listEl.value === null) {
    reservedHeight.value = undefined
    return
  }
  let tallest = 0
  for (const row of Array.from(listEl.value.children)) {
    tallest = Math.max(tallest, (row as HTMLElement).getBoundingClientRect().height)
  }
  // The tallest page is page one when the owner is in the match — one row more than the server's
  // page. Reserving that everywhere keeps the block below from moving when the pager moves.
  const slots = PAGE_SIZE.value + (ownerMatches.value ? 1 : 0)
  // `-1` is the full page's last row having no bottom border.
  reservedHeight.value = tallest === 0 ? undefined : `${Math.round(tallest * slots) - 1}px`
}

async function share(): Promise<void> {
  const who = handle.value.trim()
  if (who === '') {
    return
  }
  busy.value = true
  const result = await grantSkill(props.namespace, props.name, who, shareRole.value)
  busy.value = false
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  handle.value = ''
  await Promise.all([loadGrants(), refreshPopulation()])
}

async function changeRole(grant: SkillGrant, role: string): Promise<void> {
  const result = await grantSkill(props.namespace, props.name, grant.handle, role as Rung)
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  // A rung change is not a population change; the list is all that has to be re-read.
  await loadGrants()
}

async function withdraw(grant: SkillGrant): Promise<void> {
  const result = await revokeGrant(props.namespace, props.name, grant.handle)
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  await Promise.all([loadGrants(), refreshPopulation()])
}

async function approve(request: AccessRequest): Promise<void> {
  const rung = wanted.value[request.handle] ?? 'invoke'
  const result = await approveAccessRequest(props.namespace, props.name, request.handle, rung)
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  await Promise.all([loadRequests(), loadGrants(), refreshPopulation()])
}

async function deny(request: AccessRequest): Promise<void> {
  const result = await denyAccessRequest(props.namespace, props.name, request.handle)
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  await loadRequests()
}

function setWanted(person: string, rung: string): void {
  wanted.value = { ...wanted.value, [person]: rung as Rung }
}
</script>

<template>
  <section class="settings-pane" data-sub="collaborators">
    <h2>协作者</h2>

    <p v-if="banner !== null" class="banner">{{ banner }}</p>

    <section class="settings-block">
      <h3>共享给</h3>
      <div class="share-row">
        <input
          v-model="handle"
          class="control"
          type="text"
          autocomplete="off"
          spellcheck="false"
          placeholder="用户名"
          @keydown.enter.prevent="share"
        />
        <RungMenu v-model="shareRole" :options="offered" label="权限" />
        <button type="button" class="button" :disabled="busy" @click="share">共享</button>
      </div>
      <p class="hint">仅所有者与「管理」可以修改他人的档位。</p>
    </section>

    <section class="settings-block">
      <div class="member-head">
        <h3>
          协作者名单 <span class="count">{{ rowCount }}</span>
        </h3>
        <label class="search member-search">
          <span class="visually-hidden">搜索协作者</span>
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" stroke-width="1.8" />
            <line
              x1="15.4"
              y1="15.4"
              x2="20.6"
              y2="20.6"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
            />
          </svg>
          <input
            v-model="query"
            class="control"
            type="search"
            autocomplete="off"
            spellcheck="false"
            placeholder="搜索用户名"
          />
        </label>
      </div>
      <p class="hint">{{ hint }}</p>
      <ul
        v-if="rowCount > 0"
        ref="listEl"
        class="grants"
        :class="{ 'is-partial': partial }"
        :style="reservedHeight === undefined ? undefined : { minHeight: reservedHeight }"
      >
        <!-- The owner: not on the ladder, not grantable, and still the first row. -->
        <li v-if="page === 1 && ownerMatches" class="is-owner">
          <span class="avatar" aria-hidden="true">{{ avatarInitial(owner) }}</span>
          <span class="grant-who">{{ owner }}</span>
          <span class="member-type">个人</span>
          <span class="role-tag">所有者</span>
        </li>
        <li v-for="grant in grants" :key="grant.handle">
          <span class="avatar" aria-hidden="true">{{ avatarInitial(grant.handle) }}</span>
          <span class="grant-who">{{ grant.handle }}</span>
          <span class="member-type">个人</span>
          <RungMenu
            :model-value="grant.role"
            :options="rungChoices(grant.role, publicRung)"
            :label="`${grant.handle} 的档位`"
            @update:model-value="changeRole(grant, $event)"
          />
          <button type="button" class="link-button danger withdraw" @click="withdraw(grant)">
            撤销
          </button>
        </li>
      </ul>
      <p v-if="rowCount === 0" class="hint member-none">没有匹配的协作者。</p>
      <nav v-if="pages > 1 && !clamping" class="pager" aria-label="分页">
        <span class="pager-count">共 {{ rowCount }} 人，第 {{ range.start }}–{{ range.end }} 人</span>
        <span v-if="page === 1" class="is-off">上一页</span>
        <a v-else class="pager-step" :href="pagerHref(page - 1)" @click.prevent="goToPage(page - 1)"
          >上一页</a
        >
        <template v-for="number in pages" :key="number">
          <span v-if="number === page" class="is-current" aria-current="page">{{ number }}</span>
          <a v-else class="pager-page" :href="pagerHref(number)" @click.prevent="goToPage(number)">{{
            number
          }}</a>
        </template>
        <span v-if="page === pages" class="is-off">下一页</span>
        <a v-else class="pager-step" :href="pagerHref(page + 1)" @click.prevent="goToPage(page + 1)"
          >下一页</a
        >
      </nav>
    </section>

    <section v-if="requests.length > 0" class="settings-block">
      <h3>
        待处理的申请 <span class="count">{{ requests.length }}</span>
      </h3>
      <ul class="grants is-requests">
        <li v-for="request in requests" :key="request.handle">
          <span class="avatar" aria-hidden="true">
            {{ avatarInitial(request.handle) }}
          </span>
          <div class="grant-what">
            <span class="grant-who">{{ request.handle }}</span>
            <span class="grant-ask">申请「{{ rungLabel(request.wanted_role) }}」</span>
          </div>
          <span class="member-type">个人</span>
          <RungMenu
            :model-value="wanted[request.handle] ?? 'invoke'"
            :options="offered"
            :label="`批准 ${request.handle} 的档位`"
            @update:model-value="setWanted(request.handle, $event)"
          />
          <div class="row-acts">
            <button type="button" class="button" @click="approve(request)">批准</button>
            <button type="button" class="button is-quiet" @click="deny(request)">拒绝</button>
          </div>
        </li>
      </ul>
    </section>
  </section>
</template>
