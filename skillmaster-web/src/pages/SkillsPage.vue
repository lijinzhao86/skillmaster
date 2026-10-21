<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { codeMessage } from '../api/errors'
import { listSkills, restoreSkill } from '../api/skills'
import { loginPath } from '../login'
import { formatTime } from '../time'
import type { AuthoredSkillSummary } from '../api/types'
import { versionLabel, versionSuffix } from '../version'
import FormBanner from '../components/FormBanner.vue'
import CommandMenu from '../components/CommandMenu.vue'
import ImportDialog from '../components/skill/ImportDialog.vue'
import NewSkillDialog from '../components/skill/NewSkillDialog.vue'
import { useSession } from '../composables/useSession'

/**
 * The skills this account has — a list of names with one state each, and two lists.
 *
 * **Two lists, two addresses** (design `list-tabs`). 「已删除」 is not a filter over the live one:
 * every row there can do exactly one thing — come back — and no row in the other can, so the tab is
 * a change of what the page *is* rather than a control in the toolbar. It is read from `?show=` and
 * drawn from its own request (`?state=deleted`), because it is ownership's answer alone.
 *
 * **Search, sort and the page number live in the address.** A filtered, sorted, second-page list is
 * a thing somebody can send to somebody else — the same promise every other screen here makes. The
 * controls rewrite the address in place rather than reloading: the whole list is already here.
 *
 * **No 调用次数 column.** The design's demo draws one because its fixtures carry call counts; the
 * product has no such fact to show, and the design's own note says this column is not part of it.
 */
const state = ref<'loading' | 'ready' | 'failed'>('loading')
const skills = ref<AuthoredSkillSummary[]>([])
const banner = ref<string | null>(null)
const restoring = ref<string | null>(null)

const PAGE_SIZE = 10

const { account } = useSession()
/** Which sheet the page's one menu opened; the two entries are one action's two sources. */
const sheet = ref<'none' | 'import' | 'create'>('none')
/** The page's 「新建 skill ▾」 — where focus goes when a sheet it opened closes. */
const createMenu = ref<InstanceType<typeof CommandMenu> | null>(null)
const createItems = [
  { label: '在线编辑', onSelect: () => (sheet.value = 'create') },
  { label: '导入本地 skill', onSelect: () => (sheet.value = 'import') },
]

function go(href: string): void {
  window.location.assign(href)
}

/**
 * Closing either sheet returns focus to 「新建 skill ▾」, the control that opened it (design §我的
 * skill: 「焦点回到打开它的那颗」). The tick lets the sheet leave the DOM first.
 */
function closeSheet(): void {
  sheet.value = 'none'
  void nextTick(() => createMenu.value?.focusTrigger())
}

const params = new URLSearchParams(window.location.search)
const show = ref<'live' | 'deleted'>(params.get('show') === 'deleted' ? 'deleted' : 'live')
const query = ref(params.get('q') ?? '')
const sort = ref<'recent' | 'name'>(params.get('sort') === 'name' ? 'name' : 'recent')
const page = ref(Math.max(1, Number.parseInt(params.get('p') ?? '1', 10) || 1))

onMounted(async () => {
  const result = await listSkills(show.value === 'deleted' ? 'deleted' : undefined)
  if (!result.ok) {
    if (result.code === 'unauthenticated') {
      // This page has no anonymous version — "your skills" is not a question with an answer for
      // nobody — so a session that is missing or has expired ends up at the sign-in form, which
      // knows how to come back here.
      window.location.assign(loginPath())
      return
    }
    // The design has one sentence for this, written into the page; a banner would say it twice.
    state.value = 'failed'
    return
  }
  skills.value = result.data.skills
  state.value = 'ready'
})

function hrefOf(skill: AuthoredSkillSummary): string {
  return `/skills/${encodeURIComponent(skill.namespace)}/${encodeURIComponent(skill.name)}`
}

/** A skill's live state, in one phrase. Null `current` is a real state, not a missing value. */
function stateOf(skill: AuthoredSkillSummary): string {
  return skill.current === null ? '未上线' : `已上线 @${versionLabel(versionSuffix(skill.current))}`
}

/** The term matches the name and the title, case-insensitively — the design's own rule. */
const matched = computed<AuthoredSkillSummary[]>(() => {
  const term = query.value.trim().toLowerCase()
  const rows =
    term === ''
      ? [...skills.value]
      : skills.value.filter((skill) =>
          `${skill.name} ${skill.title}`.toLowerCase().includes(term),
        )
  return rows.sort((a, b) => {
    if (sort.value === 'name') {
      return a.name < b.name ? -1 : a.name > b.name ? 1 : 0
    }
    // "最近更新", which is the projected time: the live version's submission, or the newest draft's.
    // A row with neither (every version discarded) sorts last rather than first.
    return (b.updated_at ?? '').localeCompare(a.updated_at ?? '')
  })
})

const pages = computed(() => Math.max(1, Math.ceil(matched.value.length / PAGE_SIZE)))

/**
 * A page number past the end lands on the last page rather than on a blank list (design's own
 * clamp). `watch` rather than a computed: the page is also in the address, so the correction has to
 * be written back — the same reason every other control here writes it.
 */
watch([matched, pages], ([, total]) => {
  if (page.value > total) {
    page.value = total
    writeAddress()
  }
})

const shown = computed<AuthoredSkillSummary[]>(() => {
  const start = (page.value - 1) * PAGE_SIZE
  return matched.value.slice(start, start + PAGE_SIZE)
})

const rangeLabel = computed(() => {
  const start = (page.value - 1) * PAGE_SIZE
  return shown.value.length === 0
    ? `共 ${matched.value.length} 个`
    : `共 ${matched.value.length} 个，第 ${start + 1}–${start + shown.value.length} 个`
})

/** The pages worth showing: the ends, the one you are on, and its neighbours — one gap for a run. */
const pageNumbers = computed<(number | null)[]>(() => {
  const numbers: (number | null)[] = []
  for (let number = 1; number <= pages.value; number += 1) {
    if (number === 1 || number === pages.value || Math.abs(number - page.value) <= 1) {
      numbers.push(number)
    } else if (numbers[numbers.length - 1] !== null) {
      numbers.push(null)
    }
  }
  return numbers
})

/** The list's own address, so a filtered, sorted, paged list can be linked to. */
function addressOf(pageNumber: number = page.value): string {
  const next = new URLSearchParams()
  if (query.value.trim() !== '') {
    next.set('q', query.value.trim())
  }
  if (sort.value !== 'recent') {
    next.set('sort', sort.value)
  }
  if (pageNumber !== 1) {
    next.set('p', String(pageNumber))
  }
  if (show.value === 'deleted') {
    next.set('show', 'deleted')
  }
  const queryString = next.toString()
  return queryString === '' ? window.location.pathname : `?${queryString}`
}

function writeAddress(): void {
  window.history.replaceState(null, '', addressOf())
}

/**
 * A tab's own address, carrying the search and the sort but not the page.
 *
 * The page number is dropped on purpose: switching lists starts at the top of the one you switched
 * to, where a carried-over second page would land on nothing.
 */
function addressOfRaw(target: 'live' | 'deleted'): string {
  const next = new URLSearchParams()
  if (query.value.trim() !== '') {
    next.set('q', query.value.trim())
  }
  if (sort.value !== 'recent') {
    next.set('sort', sort.value)
  }
  if (target === 'deleted') {
    next.set('show', 'deleted')
  }
  const queryString = next.toString()
  return queryString === '' ? window.location.pathname : `?${queryString}`
}

// Typing re-renders and re-states the address, and never reloads: the list is here, and a round trip
// per keystroke is what makes a search box feel like a form.
watch(query, () => {
  page.value = 1
  writeAddress()
})

watch(sort, () => {
  page.value = 1
  writeAddress()
})

function goToPage(number: number): void {
  page.value = number
  writeAddress()
  window.scrollTo(0, 0)
}

/**
 * Brings a deleted skill back, then shows it where it now is.
 *
 * A full load rather than a local splice: restoring changes both lists and the skill page's own
 * existence, and a page that patched one row out of its copy would be a second answer to that.
 */
async function restore(skill: AuthoredSkillSummary): Promise<void> {
  const address = hrefOf(skill)
  restoring.value = skill.name
  const result = await restoreSkill(skill.namespace, skill.name)
  restoring.value = null
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  window.location.assign(address)
}
</script>

<template>
  <section class="page is-list">
    <header class="page-head">
      <h1>我的 skill</h1>
      <!-- **The empty list is its own state in the design** (`empty`, beside `ready`): the count,
           search and sort are not drawn there — "搜索与排序在空态里没有意义" — and 新建 skill ▾ is,
           because it is the only next step. A filtered list that matched nothing is still `ready`:
           the controls stay, so the filter can be cleared. -->
      <span v-if="state === 'ready' && skills.length > 0" class="count">
        {{ matched.length }} 个{{ show === 'deleted' ? '已删除' : '' }}
      </span>
      <!-- **The head's far end is one group** (design `.page-actions`): search and sort, then the
           one action. Wrapping them keeps 新建 skill ▾ at the right edge in the empty state too,
           where the toolbar is not drawn — a button that jumps to the title is a different header.
           Both controls are in the address, like everything else here, so a filtered list can be
           linked to. -->
      <div class="page-actions">
        <div v-if="state === 'ready' && skills.length > 0" class="toolbar">
          <label class="search">
            <span class="visually-hidden">搜索</span>
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
              placeholder="搜索名字或说明"
            />
          </label>
          <label class="sort">
            <span class="visually-hidden">排序</span>
            <select v-model="sort" class="control">
              <option value="recent">最近更新</option>
              <option value="name">名称</option>
            </select>
          </label>
        </div>
        <!-- **One action, two sources** (v9): 在线编辑 fills a form and lands a draft; 导入本地 skill
             brings a zip in. Drawn whenever the list is ready — including the empty state, where it
             is the only next step, and both lists, because this is the page header and the design
             gates it on `ready` alone (the deleted list is the same page). -->
        <CommandMenu
          v-if="state === 'ready'"
          ref="createMenu"
          :groups="[createItems]"
          menu-label="新建 skill"
          resize="reposition"
        >
          <template #trigger="{ open, toggle }">
            <button
              type="button"
              class="button menu-button"
              aria-haspopup="menu"
              :aria-expanded="open ? 'true' : 'false'"
              @click="toggle"
            >
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              新建 skill
              <svg class="button-caret" viewBox="0 0 12 12" aria-hidden="true">
                <path d="M3 4.8 6 7.8 9 4.8" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
          </template>
        </CommandMenu>
      </div>
    </header>

    <!-- **Two lists, two addresses.** 「已删除」 is not a filter on the list above it: every row in
         it can do exactly one thing — come back — and no row in the other list can do that. -->
    <!-- **Not drawn on an empty live list** — the design's `empty` state hides the whole switching
         row, and 「新建 skill ▾」 is the only next step then. Kept while looking at 已删除, because
         hiding it there would leave no way back to 在用; the design does not cover that pairing, so
         the safer of the two readings is taken and recorded in the v9 TD (接口 8). -->
    <nav
      v-if="state === 'ready' && (skills.length > 0 || show === 'deleted')"
      class="skill-tabs list-tabs"
      aria-label="我的 skill 的两份列表"
    >
      <a
        class="skill-tab"
        :class="{ 'is-current': show === 'live' }"
        :aria-current="show === 'live' ? 'page' : undefined"
        :href="addressOfRaw('live')"
        >在用</a
      >
      <a
        class="skill-tab"
        :class="{ 'is-current': show === 'deleted' }"
        :aria-current="show === 'deleted' ? 'page' : undefined"
        :href="addressOfRaw('deleted')"
        >已删除</a
      >
    </nav>

    <FormBanner :message="banner" />

    <p v-if="state === 'loading'" class="loading">加载中…</p>

    <div v-else-if="state === 'failed'" class="banner">读不到你的 skill 列表，稍后再试一次。</div>

    <div v-else class="list-body">
      <ul v-if="show === 'live'" class="skills">
        <li v-for="skill in shown" :key="skill.name" class="skill">
          <div class="skill-line">
            <a class="skill-name" :href="hrefOf(skill)">{{ skill.name }}</a>
            <span class="chip" :class="skill.current === null ? 'is-plain' : 'is-live'">
              <i class="dot" /><span class="chip-text">{{ stateOf(skill) }}</span>
            </span>
            <!-- **The whole skill is offline, not this version.** A second mark, beside the version
                 state, because the two say different things: the skill is listed and manageable, but
                 its address does not serve. -->
            <span v-if="skill.offline_at != null" class="chip is-offline">
              <i class="dot" /><span class="chip-text">已下线</span>
            </span>
            <span class="spacer" />
          </div>
          <div class="skill-line skill-sub">
            <p class="skill-title">{{ skill.title }}</p>
            <span class="spacer" />
            <span v-if="skill.updated_at !== null" class="skill-updated">
              更新于 {{ formatTime(skill.updated_at) }}
            </span>
          </div>
        </li>
      </ul>

      <!-- One deleted skill. The name is text, not a link: its address opens nothing now, and a
           name that looks like a link and goes nowhere is worse than plain text. -->
      <ul v-else class="skills">
        <li v-for="skill in shown" :key="skill.name" class="skill">
          <div class="skill-line">
            <span class="skill-name">{{ skill.name }}</span>
            <span class="chip is-plain">
              <i class="dot" /><span class="chip-text">已删除</span>
            </span>
            <span class="spacer" />
            <button
              type="button"
              class="link-button restore"
              :disabled="restoring === skill.name"
              @click="restore(skill)"
            >
              恢复
            </button>
          </div>
          <div class="skill-line skill-sub">
            <p class="skill-title">{{ skill.title }}</p>
            <span class="spacer" />
            <!-- When it went is the only date this row has, and it is the one a person is asking
                 about: 「更新于」 on a skill nobody can open would be a fact about a page that no
                 longer exists. -->
            <span v-if="skill.deleted_at !== null" class="skill-updated">
              删除于 {{ formatTime(skill.deleted_at) }}
            </span>
          </div>
        </li>
      </ul>

      <p
        v-if="show === 'live' && skills.length === 0"
        class="empty"
      >
        还没有 skill。新建一个，或运行 <code>skillmaster skill submit</code>。
      </p>
      <p v-else-if="show === 'live' && matched.length === 0" class="empty">
        没有匹配的 skill。换个词试试，或者清空搜索。
      </p>
      <p v-else-if="show === 'deleted' && matched.length === 0" class="empty">没有删掉的 skill。</p>

      <!-- Pages only once there is more than one — a lone 「1」 under a list that fits is a control
           that does nothing, and the count is already in the heading. -->
      <nav v-if="matched.length > PAGE_SIZE" class="pager" aria-label="分页">
        <span class="pager-count">{{ rangeLabel }}</span>
        <span v-if="page === 1" class="is-off">上一页</span>
        <a v-else class="pager-step" :href="addressOf(page - 1)" @click.prevent="goToPage(page - 1)"
          >上一页</a
        >
        <template v-for="(number, index) in pageNumbers" :key="index">
          <span v-if="number === null" class="is-gap">…</span>
          <span v-else-if="number === page" class="is-current" aria-current="page">{{ number }}</span>
          <a
            v-else
            class="pager-page"
            :href="addressOf(number)"
            @click.prevent="goToPage(number)"
            >{{ number }}</a
          >
        </template>
        <span v-if="page === pages" class="is-off">下一页</span>
        <a v-else class="pager-step" :href="addressOf(page + 1)" @click.prevent="goToPage(page + 1)"
          >下一页</a
        >
      </nav>
    </div>
  </section>

  <ImportDialog
    :open="sheet === 'import'"
    mode="skill"
    namespace=""
    name=""
    rung="owner"
    @close="closeSheet"
    @navigate="go"
  />
  <NewSkillDialog
    :open="sheet === 'create'"
    :namespace="account?.namespace ?? ''"
    @close="closeSheet"
    @navigate="go"
  />
</template>
