<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { codeMessage, fieldErrors } from '../api/errors'
import { createKey, listKeys, revokeKey, rotateKey } from '../api/keys'
import { loginPath } from '../login'
import { formatDay, formatRelative } from '../time'
import type { ApiKey, ApiKeyPolicy } from '../api/types'
import FormBanner from '../components/FormBanner.vue'

/**
 * 访问凭据 — 「API 密钥」. The screen where a key comes into existence (ADR 0038).
 *
 * **It exists because the CLI's message needs somewhere to point.** When a key expires the terminal
 * says so and names this page; before this page there was nowhere to renew one, and a key that could
 * not be renewed was a key a person had to remember to replace. The design of this screen — four
 * counts that are each a filter, the consequence-first confirmations, the one-time string — is
 * `design/README.md` §「访问凭据：单独一页」, and it is the authority for everything below that is not
 * this app's own layout.
 *
 * **Why the list is filtered in the browser and not on the server.** A person has as many keys as
 * they have machines; the whole list arrives in one response, and the counts have to follow the
 * search term anyway ("how many are about to expire" is a question about the list you are looking
 * at, not about all of them). A filter endpoint would answer a question nobody asked and put the two
 * halves of one screen's arithmetic in two places.
 */
type State = 'active' | 'soon' | 'revoked' | 'expired'
type Filter = State | 'all'

/**
 * **The one number this screen has.** It decides both the 「两周内到期」 count and the tag on a row,
 * because two thresholds would be one fact written twice — and the reference product does exactly
 * that (a 14-day row badge beside a 15-day count), which is how a page ends up disagreeing with
 * itself in a way nobody notices until a screenshot.
 */
const SOON_DAYS = 14

const LABEL: Record<Filter, string> = {
  active: '活跃',
  soon: '两周内到期',
  revoked: '已撤销',
  expired: '已过期',
  all: '全部',
}

const state = ref<'loading' | 'ready' | 'failed'>('loading')
const keys = ref<ApiKey[]>([])
const policy = ref<ApiKeyPolicy | null>(null)
const banner = ref<string | null>(null)

/**
 * Which of the three screens this page is: the list, the create form, or the moment a string exists.
 *
 * **The moment is memory and nothing else, and that is the whole of "the string does not survive a
 * reload".** It cannot: the server stores a hash and this is the only response that ever carried the
 * whole key, so a load that did not just create one has nothing to show. The demo had to model that
 * (it looked at `performance`'s navigation type to tell arriving from reloading); here it falls out
 * of where the value lives, which is the better place for it.
 */
const mode = ref<'list' | 'new' | 'moment'>('list')
const moment = ref<{
  kind: 'created' | 'rotated'
  key: ApiKey
  token: string
  revealed: boolean
  copied: boolean
  /** The fallback where the clipboard is unreachable: the string is selected, and the button says so. */
  selected: boolean
} | null>(null)

/** The row whose consequence question is open, and which question it is. One at a time. */
const asking = ref<{ id: string; action: 'revoke' | 'rotate' } | null>(null)
/** The row an action is in flight for, so only that row disables. */
const busy = ref<string | null>(null)

const filter = ref<Filter>(readFilter())
const query = ref(readQuery())

const form = reactive({
  name: '',
  note: '',
  scope: 'read' as 'read' | 'write',
  expires: '',
})
const fieldMessage = ref<string | null>(null)
const submitting = ref(false)

const now = ref(Date.now())

onMounted(async () => {
  await load()
  window.addEventListener('keydown', onKeydown)
  // Leaving the moment is asked about by intercepting the shell's own navigation, so it is bound for
  // as long as this page is on screen — see `guarded` for why every handler checks the state itself.
  document.addEventListener('click', onExitClick, true)
  document.addEventListener('submit', onExitSubmit, true)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  document.removeEventListener('click', onExitClick, true)
  document.removeEventListener('submit', onExitSubmit, true)
})

/**
 * Escape answers the question the row is asking — but only while one is open, which is why the
 * listener is bound always and does nothing the rest of the time: a handler added and removed per
 * confirmation is one more thing that can leak, and this costs a comparison per keystroke.
 */
function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && asking.value !== null) {
    event.preventDefault()
    cancel()
  }
}

// A question belongs to the moment it was asked. Anything that takes the row away — a reload, a
// different screen, a successful answer — closes it, so returning to the list cannot leave one open
// over a row that has since changed.
watch(mode, () => {
  asking.value = null
})

async function load(): Promise<void> {
  const result = await listKeys()
  if (!result.ok) {
    if (result.code === 'unauthenticated') {
      // No anonymous version of "your keys", so a session that has gone ends up at the sign-in form,
      // which knows how to come back here.
      window.location.assign(loginPath())
      return
    }
    state.value = 'failed'
    banner.value = codeMessage(result.code, result.message)
    return
  }
  keys.value = result.data.keys
  policy.value = result.data.policy
  now.value = Date.now()
  today.value = todayPlus(0)
  state.value = 'ready'
  // **And the failed request it is replacing stops claiming to be the current state.** A banner set by
  // a row action that failed (the network, a refusal) used to survive the next reload: the list came
  // back correct and the page still said the request had failed — about a screen that had just been
  // read successfully.
  banner.value = null
  if (form.expires === '') {
    // The form preselects the deployment's own default rather than a number this file believes in:
    // a deployment that turned expiry off issues keys that never expire, and one that changed the
    // lifetime would otherwise be argued with by a hardcoded 90.
    //
    // **`?.` on `policy`, and it is load-bearing**: a listing whose response carries no `policy` at
    // all — an older server, or a cached bundle against one — is not a reason to take the page down.
    // The three other reads of it already tolerate that (`expiresAt`, the picker's `v-if`, and this
    // expression before it was changed); this one threw, and threw *after* `state` was set to ready,
    // so the list rendered normally while the rest of `onMounted` — the Escape key among it — never
    // ran, with the exception going nowhere but the console.
    form.expires = policy.value?.expires === true ? (latestDay.value ?? '') : ''
  }
}

/** The state of one key — derived, never stored: the row is the truth and this is a reading of it. */
function stateOf(key: ApiKey): State {
  if (key.revoked_at !== null) {
    return 'revoked'
  }
  if (key.expires_at === null) {
    return 'active'
  }
  const expires = Date.parse(key.expires_at)
  if (expires <= now.value) {
    return 'expired'
  }
  return expires - now.value <= SOON_DAYS * 24 * 60 * 60 * 1000 ? 'soon' : 'active'
}

/** The keys whose name matches the search box — what the four counts count. */
const searched = computed<ApiKey[]>(() => {
  const term = query.value.trim().toLowerCase()
  if (term === '') {
    return keys.value
  }
  return keys.value.filter((key) => key.name.toLowerCase().includes(term))
})

const counts = computed<Record<State, number>>(() => {
  const total: Record<State, number> = {
    active: 0,
    soon: 0,
    revoked: 0,
    expired: 0,
  }
  for (const key of searched.value) {
    total[stateOf(key)] += 1
  }
  return total
})

const shown = computed<ApiKey[]>(() =>
  filter.value === 'all'
    ? searched.value
    : searched.value.filter((key) => stateOf(key) === filter.value),
)

/**
 * What to say when there is nothing to show, and the three answers differ because the reasons do.
 *
 * The third one asks what the *search* matched rather than whether the box has anything in it: an
 * empty list under an active filter while a card above counts keys whose names contain the term is a
 * sentence arguing with a number.
 */
const emptySentence = computed<string>(() => {
  if (keys.value.length === 0) {
    return '暂无密钥。CLI 与 agent 通过密钥访问你的 skill，无需在浏览器中登录。'
  }
  if (searched.value.length === 0) {
    return `没有名称包含「${query.value.trim()}」的密钥。`
  }
  return '该状态下暂无密钥。'
})

/** A card is a link to its own filter, and it carries the search with it — the count is of what the
 *  search found, so arriving at a list that ignores the term would be the count and the list
 *  disagreeing. */
function filterHref(target: Filter): string {
  return keysAddress(target)
}

/**
 * The page's own address, for a filter and the search in force — **with the default left out**.
 *
 * 活跃 is what this page shows with no parameter at all (`readFilter`), and the design's rule is that
 * a default is not written into an address: its 活跃 card links to `?page=keys`, not to
 * `?filter=active`. Writing it made two addresses for one screen — a link somebody was sent and a
 * link they typed would have looked different and been the same place. `all` *is* written, because on
 * this page "absent" means 活跃 and only `all` can say "no filter" (see `finishMoment`).
 *
 * One function for both writers: the cards and the crumb are real links, and the search box replaces
 * the address in place.
 */
function keysAddress(target: Filter): string {
  const params = new URLSearchParams()
  if (target !== 'active') {
    params.set('filter', target)
  }
  const term = query.value.trim()
  if (term !== '') {
    params.set('q', term)
  }
  const search = params.toString()
  return search === '' ? '/account/keys' : `/account/keys?${search}`
}

/**
 * The search term, written into the address without a load.
 *
 * `replaceState` rather than a navigation: a page reload per keystroke would be absurd, and the
 * address is worth keeping current because the design's promise is that a filtered list can be sent
 * to somebody. The filter itself is a real link (`filterHref`), which is how every other navigation
 * in this app works.
 */
function onQuery(): void {
  window.history.replaceState(null, '', keysAddress(filter.value))
}

function readFilter(): Filter {
  const value = new URLSearchParams(window.location.search).get('filter')
  // **Absent means active**, which is why the default is not written into the address: keys are
  // mostly read to answer "what is working right now", and the dead ones belong one click away.
  if (value === null || value === 'active') {
    return 'active'
  }
  return value === 'soon' || value === 'revoked' || value === 'expired' || value === 'all'
    ? value
    : 'active'
}

function readQuery(): string {
  return new URLSearchParams(window.location.search).get('q') ?? ''
}

/**
 * The day a key should stop working, as a date input wants it: `YYYY-MM-DD`, **read in Shanghai**.
 *
 * The value the picker holds means "the end of that day here", and `expiresAt` pins the offset to
 * +08:00 — so the calendar day has to come from the same zone as the offset. Read from the browser's
 * clock instead, it is off by one for anybody west of us: at 20:00 UTC it is already the 16th in
 * Shanghai, and a picker whose `min` said the 15th would offer a day that is over before it starts
 * (the server refuses it: `invalid_expiry` / `not_in_the_future`).
 */
const SHANGHAI_DAY = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  timeZone: 'Asia/Shanghai',
})

function shanghaiDay(): { year: number; month: number; day: number } {
  // `formatToParts` rather than parsing the formatted string: the numbers are read as numbers instead
  // of as a shape one locale's separator happens to have, and a bad parse would produce a `NaN` date
  // that reaches the input and then `toISOString` — which throws, leaving a submit button disabled
  // with nothing on screen to explain it.
  const parts = SHANGHAI_DAY.formatToParts(new Date())
  const value = (type: Intl.DateTimeFormatPartTypes): number =>
    Number(parts.find((part) => part.type === type)?.value ?? Number.NaN)
  return { year: value('year'), month: value('month'), day: value('day') }
}

function todayPlus(days: number): string {
  const { year, month, day } = shanghaiDay()
  // Arithmetic in UTC on a date-only value: no zone is involved in "three days after the 13th", and
  // going through a local `Date` here is what would reintroduce the shift this function exists to
  // avoid.
  const at = new Date(Date.UTC(year, month - 1, day + days))
  const m = String(at.getUTCMonth() + 1).padStart(2, '0')
  const d = String(at.getUTCDate()).padStart(2, '0')
  return `${at.getUTCFullYear()}-${m}-${d}`
}

// The first day the picker may offer: today, in the zone `expiresAt` pins the date to. Refreshed
// where the rest of the page's clock is — on every `load()` and every time the form opens — because a
// page left open across a day boundary would otherwise keep offering yesterday, and the picker's `min`
// is the only thing standing between the reader and a date the server refuses.
const today = ref(todayPlus(0))

/**
 * The last day this deployment issues, or nothing when it does not care about expiry.
 *
 * **The server's day, not this page's arithmetic**, and the same value the form preselects: the
 * deployment's lifetime is both the default and the ceiling (ADR 0042), so the last day it will issue
 * is exactly the day the form opens on. Handing it to the picker as `max` means nobody is offered a
 * date the server would refuse — and taking it from `policy` rather than from `todayPlus` is what
 * keeps that true when this browser's clock is ahead of the server's.
 *
 * **The fallback is for a server that sends a policy without `latest_day`** — a rolled-back
 * deployment, or a bundle from this build against one. It is deliberately the arithmetic the server
 * would have done, used only where the server did not answer, and it can be a day long if the server's
 * clock is behind this one's: the server then refuses that day with `beyond_maximum`, which is the
 * graceful half of the two. The alternative — no preselection at all — is a form whose own hint says
 * it preselected something, which is the sentence this exists to keep true.
 */
const latestDay = computed<string | undefined>(() => {
  const current = policy.value
  if (current?.expires !== true) {
    return undefined
  }
  // `typeof` rather than a truthiness test: a policy from a server older than `latest_day` may also be
  // older than `lifetime_days`, and `todayPlus(undefined)` would build a `NaN` date that reaches the
  // input. Nothing is offered in that case, and the hint says so.
  return (
    current.latest_day ??
    (typeof current.lifetime_days === 'number' ? todayPlus(current.lifetime_days) : undefined)
  )
})

/**
 * What the expiry cell says, in one place.
 *
 * Three of the four states share this one wording (`撤销于 …` is the exception, and it is about a
 * different event), so the phrase is built here rather than at each of the four places it is shown.
 * **A key may have no expiry at all** — the deployment can turn them off — and that is a fact the
 * cell has to say: `formatDay` returns an empty string for null, which rendered as「到期 」with
 * nothing after it, reading like a broken cell rather than like a choice somebody made.
 */
function expiryText(value: string | null): string {
  return value === null ? '永不过期' : `到期 ${formatDay(value)}`
}

/**
 * What the clock line in a row says: what happened to *this* key.
 *
 * **Not always when it expires**, and the difference is the point. A revoked key has a different date
 * entirely, and the day it was revoked is the fact somebody looking at the row needs — its expiry
 * would be true and useless. That date is the thing a product which keeps revocation as a boolean
 * cannot show; this one has the column.
 */
function happenedText(key: ApiKey): string {
  return key.revoked_at === null
    ? expiryText(key.expires_at)
    : `撤销于 ${formatDay(key.revoked_at)}`
}

/** 可读 / 可读可写 — what a person reads, from the vocabulary the token plane already uses. */
function scopeLabel(scope: string): string {
  return scope.includes('skills:write') ? '可读可写' : '可读'
}

/* ---------------------------------------------------------------------------------------------
   The two things that can be done to a row.
   --------------------------------------------------------------------------------------------- */

function ask(id: string, action: 'revoke' | 'rotate'): void {
  asking.value = { id, action }
  // Focus lands on the confirm button, so that answering is one keystroke and Escape is the other.
  void nextTick(() => {
    document.querySelector<HTMLButtonElement>(`[data-key-id="${id}"] [data-key-confirm]`)?.focus()
  })
}

function cancel(): void {
  const open = asking.value
  asking.value = null
  if (open === null) {
    return
  }
  // Back to the button that was pressed, rather than to the top of the document: a keyboard or
  // screen-reader user should not have to find their place again after answering "no".
  void nextTick(() => {
    document
      .querySelector<HTMLButtonElement>(
        `[data-key-id="${open.id}"] [data-key-action="${open.action}"]`,
      )
      ?.focus()
  })
}

async function confirmRevoke(id: string): Promise<void> {
  busy.value = id
  const result = await revokeKey(id)
  busy.value = null
  asking.value = null
  if (!result.ok) {
    banner.value = codeMessage(result.code, result.message)
    return
  }
  // Re-read rather than patch the row: the listing's own answer to "when did it stop working" comes
  // from `revoked_at`, and a row edited in the browser would be this page's opinion of it.
  await load()
}

async function confirmRotate(id: string): Promise<void> {
  busy.value = id
  const result = await rotateKey(id)
  busy.value = null
  asking.value = null
  if (!result.ok) {
    // **A refusal about *this row* is answered from the server's own field.** `invalid_request` with
    // `field: id` is what a renewal that lost a race gets — the key was revoked in another tab, or by
    // somebody else, between this list being read and this button being pressed — and the sentence
    // for it (`revoked`) is more use than the banner's "check what you sent and try again", which
    // invites a retry that can only fail the same way.
    const onField = fieldErrors(result.details, result.message)
    // Then the list is read again, before the message is shown: what the refusal is telling us is
    // that this page is holding a row that no longer exists, and the reload is what takes it off the
    // screen. Doing it afterwards would let a reload failure overwrite the sentence above.
    await load()
    banner.value = onField.id ?? codeMessage(result.code, result.message)
    return
  }
  showMoment('rotated', result.data.key, result.data.token)
}

/* ---------------------------------------------------------------------------------------------
   Creating one, and the moment its string exists.
   --------------------------------------------------------------------------------------------- */

// Leaving the form without creating anything: the list keeps its filter and its search term (they
// were the reader's, not the form's), and the messages about this form go with it.
function leaveForm(): void {
  banner.value = null
  fieldMessage.value = null
  mode.value = 'list'
}

/**
 * The breadcrumb is a way out of this form, so it is closed while the form is submitting.
 *
 * **It is the same rule as 取消's `:disabled`, and it has to be applied to both or the pair argues
 * with itself.** Leaving mid-submission is what makes the answer land on a screen nobody is on — and
 * on this screen the answer is the only appearance the new key's string ever makes, so a click on a
 * link that merely *looks* like navigation (a real `href`, a real page load) destroys it. `preventDefault`
 * rather than a greyed-out span, because this is a link and its whole value is that it can be opened
 * in a new tab: what is refused is leaving *this* form, not the address.
 */
function guardCrumb(event: MouseEvent): void {
  if (submitting.value) {
    event.preventDefault()
  }
}

function startCreate(): void {
  fieldMessage.value = null
  banner.value = null
  // The picker's floor is the day the form opens on, not the day the page did.
  today.value = todayPlus(0)
  // **A fresh form every time.** The design's demo navigates to a page, so its form is new by
  // construction; here the three screens are one component, and without this the second visit offers
  // the previous key's name, note, permissions and date — one click away from a duplicate that looks
  // deliberate.
  form.name = ''
  form.note = ''
  form.scope = 'read'
  form.expires = latestDay.value ?? ''
  mode.value = 'new'
}

async function submit(): Promise<void> {
  if (submitting.value) {
    // **A second submission would throw the first one's string away.** Two keys would be created and
    // `showMoment` would show the second: the first one's only appearance was already spent, and
    // nothing on this page can show it again. The disabled button is the visible half of this rule and
    // it is not the load-bearing half — an implicit form submission (Enter, a script, an extension)
    // does not consult a disabled default button, and this is one line.
    return
  }
  fieldMessage.value = null
  banner.value = null
  submitting.value = true
  const result = await createKey({
    name: form.name.trim(),
    note: form.note.trim() === '' ? null : form.note.trim(),
    // Only the intent is sent: 「读写」 asks for write, and that write implies read is the server's
    // rule, applied when it writes the row (ADR 0039 §决定 2). Sending both here would put the rule
    // in two places, and the one that forgot it would be this one.
    scopes: form.scope === 'write' ? ['skills:write'] : ['skills:read'],
    expires_at: expiresAt(),
  })
  submitting.value = false
  if (!result.ok) {
    const onField = fieldErrors(result.details, result.message)
    // `scope` is the one the server can now name on this form — the permission group is a field like
    // any other, and「至少要选一项权限」was written for it and had no way of reaching the screen.
    const named = onField.name ?? onField.note ?? onField.scope ?? onField.expires_at
    if (named !== undefined) {
      fieldMessage.value = named
      return
    }
    banner.value = codeMessage(result.code, result.message)
    return
  }
  showMoment('created', result.data.key, result.data.token)
}

/**
 * The chosen day, as the instant the server stores.
 *
 * End of that day in this product's own timezone rather than midnight UTC: a key that expires "on
 * 2027/01/13" is one that works through that day, and the listing shows the same date back. Sending
 * midnight would make it stop working at eight in the morning here, which is not what the date
 * picker said.
 */
function expiresAt(): string | null {
  if (!policy.value?.expires || form.expires === '') {
    return null
  }
  const at = new Date(`${form.expires}T23:59:59+08:00`)
  if (Number.isNaN(at.getTime())) {
    // A date the picker would not produce, typed or pasted in. Sent as "no date named" rather than
    // thrown about: `toISOString` on an invalid date raises, and this runs after 提交 has already been
    // disabled, so a bad string would leave a dead button and no explanation.
    return null
  }
  return at.toISOString()
}

function showMoment(kind: 'created' | 'rotated', key: ApiKey, token: string): void {
  moment.value = {
    kind,
    key,
    token,
    revealed: false,
    copied: false,
    selected: false,
  }
  // A new string to lose starts with no question open and the guard armed again.
  leaving.value = null
  leavingNow = false
  mode.value = 'moment'
}

/** Back to the list, re-read: a renewal changed a row, and the list is where that shows. */
async function finishMoment(): Promise<void> {
  moment.value = null
  mode.value = 'list'
  filter.value = 'all'
  // **The one place on this page that lets go of the list's state, and it lets go of both halves of
  // it** (design §访问凭据). The moment just created a fact the reader has to be able to check, and
  // either half can hide it: a filter of 已撤销 hides a new key, and so does a search term that was
  // typed before it existed — the person lands on「0 个密钥」directly after being told it was created.
  // `filter=all` rather than dropping the parameter, because on this page "absent" *is* 活跃.
  query.value = ''
  window.history.replaceState(null, '', '/account/keys?filter=all')
  await load()
}

/* ---------------------------------------------------------------------------------------------
   Copying the string — a real button, with two fallbacks underneath it.
   --------------------------------------------------------------------------------------------- */

async function copy(): Promise<void> {
  const current = moment.value
  if (current === null) {
    return
  }
  try {
    await navigator.clipboard.writeText(current.token)
    current.copied = true
    return
  } catch {
    // Falls through: an insecure origin, a permissions policy, or a browser that wants a different
    // gesture. `execCommand` works from inside a click handler even where the async API does not.
  }
  if (execCommandCopy(current.token)) {
    current.copied = true
    return
  }
  selectSecret()
  current.selected = true
}

/**
 * The middle fallback: `execCommand`, which works from inside a click handler even where the async
 * clipboard API does not.
 *
 * Guarded and wrapped, because "unavailable" is not the same as "returned false" here: the method is
 * deprecated and simply absent in some environments (a jsdom test, an embedded webview), and letting
 * that throw would turn a failed copy into a broken button — the one case where the person still has
 * a way forward.
 */
function execCommandCopy(text: string): boolean {
  if (typeof document.execCommand !== 'function') {
    return false
  }
  const field = document.createElement('textarea')
  field.value = text
  field.setAttribute('readonly', '')
  field.style.position = 'fixed'
  field.style.opacity = '0'
  document.body.appendChild(field)
  try {
    field.select()
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.body.removeChild(field)
  }
}

/** The last resort: put the string in the reader's hands and say so, rather than claim a copy. */
function selectSecret(): void {
  const node = document.getElementById('key-secret')
  if (node === null) {
    return
  }
  const range = document.createRange()
  range.selectNodeContents(node)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

/**
 * Whether the string is in the reader's hands — copied, or the fallback where it is selected and the
 * button says 已选中，请按你的复制快捷键.
 *
 * **One fact with one expression**, and both the way out and the leave question are drawn from it: a
 * gate that opens on one condition while the question asks about another is how a screen ends up
 * nagging a reader who has already done what it asked. (The design's own `held` flag in the demo is
 * this same pair, and it is set in the same two places.)
 */
const held = computed<boolean>(() => {
  const current = moment.value
  return current !== null && (current.copied || current.selected)
})

const copyLabel = computed<string>(() => {
  const current = moment.value
  if (current === null) {
    return '复制密钥'
  }
  if (current.copied) {
    return '已复制'
  }
  return current.selected ? '已选中，请按你的复制快捷键' : '复制密钥'
})

/* ---------------------------------------------------------------------------------------------
   Leaving the moment — asked in the block that was already the way out.
   --------------------------------------------------------------------------------------------- */

/**
 * The question while it is on screen, and what to hand the focus back to when it is answered 留下.
 *
 * **The design asks this, and it asks it here** (`design/README.md` §那一刻: 离开是自由的，只是不再
 * 安静). The way out below the card states its own precondition and always did; what it never covered
 * is the rest of the exits the shell draws — the crumb above it, the navigation, the brand, the top
 * bar's search, the sign-out button. Those stay live on purpose: locking the page is a thing the
 * reference product does not do, and it would block the reader who wants to put the string somewhere
 * else first. So this screen asks instead, and it asks in the block that was already its way out.
 *
 * The question carries the consequence rather than the question, and its two answers say themselves
 * rather than 确认/取消 — here 取消 would be a place to stay rather than the undoing of an action.
 */
const leaving = ref<{ go: () => void; back: HTMLElement | null } | null>(null)
/** The 仍然离开 button, so the answer that loses something is the one under the fingers. */
const leaveYes = ref<HTMLButtonElement | null>(null)
/**
 * Answered 仍然离开: the screen is on its way out. It is what stands the guard down, so that the
 * gesture being replayed below does not arrive back here and ask again.
 */
let leavingNow = false

/**
 * Whether there is something to lose: the string is here, and it is not in the reader's hands yet.
 * The same condition as the exit button's own precondition — see `held`.
 */
function guarded(): boolean {
  return !leavingNow && moment.value !== null && !held.value
}

/**
 * **Everything on this page that navigates, and nothing else**: links (the crumb, the navigation, the
 * brand, the account menu) and the one sign-out button. It listens in the capture phase, because the
 * shell's own handlers are what does the navigating and they must not run before the question is
 * answered — `preventDefault` alone would hold the browser and let the sign-out through.
 */
function onExitClick(event: MouseEvent): void {
  if (!guarded() || event.defaultPrevented) return
  // A modified click opens somewhere else in a new tab and takes nothing with it, so it is not this
  // page's to ask about. The question is only ever about the string leaving *here*.
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  const node = event.target as Element | null
  const control = (node?.closest?.('a[href], [data-sign-out]') as HTMLElement | null) ?? null
  if (control === null) return
  event.preventDefault()
  event.stopPropagation()
  askToLeave(control, () => control.click())
}

/** The top bar's search is the one form the shell navigates with. */
function onExitSubmit(event: Event): void {
  if (!guarded() || event.defaultPrevented) return
  const form = event.target as HTMLFormElement | null
  if (form === null || !form.classList.contains('topbar-search')) return
  event.preventDefault()
  event.stopPropagation()
  // A form cannot take the focus; the box inside it is what the reader was typing in.
  askToLeave(form.querySelector('input'), () => form.requestSubmit())
}

/**
 * **Answering 仍然离开 re-runs the reader's own gesture rather than doing what it would have done.**
 * The click was swallowed while the question was up, so the confirmation presses the very control
 * that was pressed — one writer per fact, and no second copy of the sign-out address or of the search
 * form's own rules in here.
 */
function askToLeave(back: HTMLElement | null, go: () => void): void {
  // **A second exit is held too, and the question moves to it.** Letting it through because a
  // question was already open is the same silent departure this screen exists to stop — the reader
  // presses the navigation while the question stands, and the string is gone with nothing said. So
  // the question stays where it is and the gesture it will replay becomes the latest one pressed,
  // which is also the one the reader meant.
  leaving.value = { go, back }
  void nextTick(() => {
    leaveYes.value?.focus()
  })
}

function stayHere(): void {
  const open = leaving.value
  leaving.value = null
  if (open === null) {
    return
  }
  // **Back to where the question interrupted — and it is not the button this block drew.** That
  // button is *disabled* while the string is uncopied, and a disabled control cannot take the focus
  // at all, so handing it back there would leave the reader on `<body>` with no way back to what they
  // were doing. `cancel` above states the same rule for the row's own question.
  void nextTick(() => {
    open.back?.focus()
  })
}

function leaveAnyway(): void {
  const open = leaving.value
  leaving.value = null
  if (open === null) {
    return
  }
  // **The flag covers the replay and nothing else.** A gesture that does not end up navigating has to
  // put the guard back: the sign-out button is the case, and its own handler can fail — a stale CSRF
  // token answers 403, the shell shows its banner and the reader is still standing here. Left set,
  // `leavingNow` would disarm the question for the rest of this page's life, and every later click on
  // the crumb or the navigation would take a string that exists nowhere else without a word.
  //
  // The synthetic click is dispatched synchronously, so the replayed listener still sees the flag;
  // and a gesture that *did* navigate has already scheduled the load that ends this page.
  leavingNow = true
  try {
    open.go()
  } finally {
    leavingNow = false
  }
}
</script>

<template>
  <!-- The list, the form and the moment are three screens of one page, which is what the design
       asks for: the moment is not a notification about an object, it *is* the object, so it takes
       the screen rather than being inserted into the list. -->
  <template v-if="mode === 'list'">
    <!-- **The page, in the design's own wrapper**: a centred column of the page's measure rather than
         the whole width of the panel. Every screen of this page is one `.page`. -->
    <section class="page">
      <!-- The trail names the area and then the page, which is what the reference product's own does:
         its sidebar says 「Access」 while its breadcrumb says 「User Settings / …」, because the
         sidebar answers "where can I go" and the breadcrumb answers "where am I". **It is also the
         only place that says this page sits under 设置**: the navigation's disclosure starts
         collapsed, on purpose. -->
      <nav class="crumbs" aria-label="面包屑">
        <a href="/account">设置</a>
        <span class="crumbs-sep" aria-hidden="true">/</span>
        <span class="crumbs-here" aria-current="page">API 密钥</span>
      </nav>

      <header class="page-head">
        <h1>API 密钥</h1>
      </header>

      <!-- The page's own sentence. **Not on the moment's screens** — it is about the page, and that
         card already says the one thing that matters while a string is on screen. -->
      <p class="page-note">
        CLI 与 agent 使用这些密钥访问你的
        skill。<strong>密钥仅在创建时显示一次</strong>，之后只保留名称、权限与使用记录。
      </p>

      <FormBanner :message="banner" />

      <template v-if="state === 'ready'">
        <!-- Four counts, and every one of them is a filter. A card here is two surfaces — a grey shell
           with a white body inside it — which is the recipe the listing and the moment use too. -->
        <div class="stats">
          <a
            v-for="one in ['active', 'soon', 'revoked', 'expired'] as State[]"
            :key="one"
            class="stat"
            :class="{ 'is-on': filter === one }"
            :href="filterHref(one)"
          >
            <span class="stat-head">{{ LABEL[one] }}</span>
            <span class="stat-body">
              <span class="stat-number">{{ counts[one] }}</span>
              <span class="stat-go">筛选列表</span>
            </span>
          </a>
        </div>

        <!-- **The filter and the search are one control**: the token that says which filter is on sits
           inside the box, next to the magnifier, and the free-text field follows it. -->
        <div class="key-search">
          <svg class="key-search-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
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
          <span class="filter-chip">
            状态：<b>{{ LABEL[filter] }}</b>
            <a
              v-if="filter !== 'all'"
              class="filter-x"
              :href="filterHref('all')"
              title="清除筛选"
              aria-label="清除筛选"
              >×</a
            >
          </span>
          <label class="visually-hidden" for="key-query">搜索名称</label>
          <input
            id="key-query"
            v-model="query"
            class="key-input"
            type="search"
            autocomplete="off"
            spellcheck="false"
            placeholder="搜索名称"
            @input="onQuery"
          />
        </div>

        <div class="key-card">
          <div class="key-head">
            <span class="key-count">{{ shown.length }} 个密钥</span>
            <!-- In the empty state too, and that is the point of it: a day-one account has no keys,
               and this must not be the one screen where making one has no control at all. -->
            <button type="button" class="button" data-key-new @click="startCreate">新建密钥</button>
          </div>
          <div class="key-body">
            <div v-if="shown.length > 0" class="key-cols" aria-hidden="true">
              <span>名称</span><span>说明</span><span>权限</span><span>状态</span><span>操作</span>
            </div>
            <ul v-if="shown.length > 0" class="keys">
              <li v-for="key in shown" :key="key.id" :data-key-id="key.id">
                <span class="key-name">{{ key.name }}</span>
                <!-- A missing note is the reference product's own answer — a grey sentence saying so —
                   rather than a blank cell that reads like a rendering failure. -->
                <span
                  class="key-note"
                  :class="{ 'is-empty': key.note === null || key.note === '' }"
                  >{{ key.note === null || key.note === '' ? '未填写说明' : key.note }}</span
                >
                <span class="key-scope">{{ scopeLabel(key.scope) }}</span>

                <!-- The state column, or the question that replaces it. Two lines rather than one, each
                   with its own icon: when it expires and when it was last used belong together, and
                   neither deserves a column of its own. **An active key draws no chip at all** — most
                   rows are active, so a mark on every one of them carries no information. -->
                <span v-if="asking?.id === key.id" class="key-state is-warn">
                  {{
                    asking.action === 'revoke'
                      ? '撤销后，使用该密钥的客户端将立即失去访问权限。'
                      : '续期后，这把密钥立即失效，换上一把新的。'
                  }}
                </span>
                <span v-else class="key-state">
                  <span class="key-when">
                    <span
                      v-if="stateOf(key) !== 'active'"
                      class="chip"
                      :class="`is-${stateOf(key)}`"
                      ><i class="dot" /><span class="chip-text">{{
                        LABEL[stateOf(key)]
                      }}</span></span
                    >
                    <span class="key-fact">
                      <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                        <path
                          fill-rule="evenodd"
                          clip-rule="evenodd"
                          d="M8.175.002a8 8 0 1 0 2.309 15.603.75.75 0 0 0-.466-1.426 6.5 6.5 0 1 1 3.996-8.646.75.75 0 0 0 1.388-.569A8 8 0 0 0 8.175.002ZM8.75 3.75a.75.75 0 0 0-1.5 0v3.94L5.216 9.4a.75.75 0 0 0 .768 1.288l2.39-1.425a.75.75 0 0 0 .376-.648V3.75Z"
                        />
                      </svg>
                      <!-- This slot says what happened to *this* key, which is not always when it
                         expires: a revoked one has a different date entirely, and showing its expiry
                         there would be true and useless. -->
                      <span class="key-date">{{ happenedText(key) }}</span>
                    </span>
                  </span>
                  <span class="key-used">
                    <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                      <path
                        fill-rule="evenodd"
                        clip-rule="evenodd"
                        d="M2.75 0a.75.75 0 0 0 0 1.5H3v.593c0 1.26.5 2.468 1.391 3.359L6.94 8l-2.548 2.548A4.75 4.75 0 0 0 3 13.907v.593h-.25a.75.75 0 0 0 0 1.5h10.5a.75.75 0 0 0 0-1.5H13v-.593c0-1.26-.5-2.468-1.391-3.359L9.06 8l2.548-2.548A4.75 4.75 0 0 0 13 2.093V1.5h.25a.75.75 0 0 0 0-1.5H2.75Zm2.75 2.093V1.5h5v.593c0 .729-.29 1.428-.805 1.944L8 5.732 6.305 4.037A2.75 2.75 0 0 1 5.5 2.093ZM8 8.732 4.914 11.82c-.265.265-.414.624-.414.999v1.182h7v-1.182c0-.375-.149-.734-.414-.999L8 8.732Z"
                      />
                    </svg>
                    <span class="key-used-text"
                      >最后使用
                      {{
                        key.last_used_at === null ? '从未使用' : formatRelative(key.last_used_at)
                      }}</span
                    >
                  </span>
                </span>

                <span class="key-acts">
                  <template v-if="asking?.id === key.id">
                    <button
                      type="button"
                      class="link-button danger"
                      data-key-confirm
                      :disabled="busy === key.id"
                      @click="
                        asking.action === 'revoke' ? confirmRevoke(key.id) : confirmRotate(key.id)
                      "
                    >
                      {{ asking.action === 'revoke' ? '确认撤销' : '确认续期' }}
                    </button>
                    <button
                      type="button"
                      class="link-button"
                      :disabled="busy === key.id"
                      @click="cancel"
                    >
                      取消
                    </button>
                  </template>
                  <template v-else>
                    <!-- 续期 first, which is the design's own order: the row's two actions are not
                       equally weighted, and the one that keeps the machine working comes first. -->
                    <button
                      v-if="key.revoked_at === null"
                      type="button"
                      class="link-button"
                      data-key-action="rotate"
                      :disabled="busy !== null"
                      @click="ask(key.id, 'rotate')"
                    >
                      续期
                    </button>
                    <!-- A revoked key cannot be renewed, and the row does not offer it: there is
                       nothing left to continue, and renewing it would undo a decision somebody made
                       on purpose (ADR 0039 §决定 3). -->
                    <button
                      type="button"
                      class="link-button danger"
                      data-key-action="revoke"
                      :disabled="busy !== null"
                      @click="ask(key.id, 'revoke')"
                    >
                      撤销
                    </button>
                  </template>
                </span>
              </li>
            </ul>
            <p v-else class="key-none">{{ emptySentence }}</p>
          </div>
        </div>
      </template>

      <p v-else-if="state === 'failed'" class="hint">
        无法加载密钥列表，请稍后重试。此处仅表示列表加载失败，不代表账号异常。
      </p>
      <p v-else class="loading">加载中…</p>
    </section>
  </template>

  <template v-else-if="mode === 'new'">
    <section class="page">
      <nav class="crumbs" aria-label="面包屑">
        <!-- **The breadcrumb goes where 取消 goes** — design §访问凭据: 「面包屑和这个按钮去同一处」.
           Cancelling keeps the list's state (a filter and a search term were the reader's, not the
           form's), so a breadcrumb that dropped them would leave this screen's two ways out pointing
           at two different lists. And it is closed exactly when 取消 is: see `guardCrumb`.
           Three segments while the form is open: the area, the page — a link *back*, because the
           trail's last segment is the action you are taking — and then the action. -->
        <a href="/account">设置</a>
        <span class="crumbs-sep" aria-hidden="true">/</span>
        <a :href="filterHref(filter)" :class="{ 'is-blocked': submitting }" @click="guardCrumb"
          >API 密钥</a
        >
        <span class="crumbs-sep" aria-hidden="true">/</span>
        <span class="crumbs-here" aria-current="page">新建 API 密钥</span>
      </nav>

      <header class="page-head">
        <h1>新建 API 密钥</h1>
      </header>

      <FormBanner :message="banner" />

      <div class="key-card">
        <div class="key-head"><span class="key-count">基本信息</span></div>
        <div class="key-body">
          <form class="key-form" novalidate @submit.prevent="submit">
            <div class="field">
              <label for="key-name">名称</label>
              <input
                id="key-name"
                v-model="form.name"
                class="control"
                type="text"
                maxlength="60"
                autocomplete="off"
                placeholder="笔记本"
                aria-describedby="key-name-hint"
              />
              <!-- The deliverable's own sentence promises a rename this version cannot do
                   (「之后还能改」); `docs/.../technical-design.md` §接口 has no rename endpoint and
                   「允许重名」 is what the model actually permits, so this is the deliverable's stale
                   side — recorded in `design/README.md` rather than copied. -->
              <p class="hint" id="key-name-hint">用在哪台机器、哪个用途。名字只给你自己看，允许重名。</p>
            </div>

            <div class="field">
              <label for="key-note">说明</label>
              <textarea
                id="key-note"
                v-model="form.note"
                class="control"
                rows="2"
                maxlength="255"
                placeholder="例如：CI 上读 pdf-tools 用的"
              ></textarea>
              <p class="hint">
                会显示在列表里。名称只够认出它是哪一把，说明才是你半年后需要的那句话。
              </p>
            </div>

            <fieldset class="field">
              <legend>权限</legend>
              <label class="key-choice">
                <input v-model="form.scope" type="radio" value="read" />
                <span class="key-choice-body">
                  <span class="key-choice-title">读取</span>
                  <span class="key-choice-note">能读你的 skill 与它们的文件。</span>
                </span>
              </label>
              <label class="key-choice">
                <input v-model="form.scope" type="radio" value="write" />
                <span class="key-choice-body">
                  <span class="key-choice-title">读写</span>
                  <span class="key-choice-note"
                    >上面全部，另加提交新版本——<strong>上线仍然只能在浏览器里由人点</strong>。</span
                  >
                </span>
              </label>
            </fieldset>

            <div v-if="policy?.expires" class="field">
              <label for="key-expires">有效期</label>
              <input
                id="key-expires"
                v-model="form.expires"
                class="control"
                type="date"
                :min="today"
                :max="latestDay"
              />
              <p class="hint">
                <template v-if="latestDay !== undefined"
                  >已按这个部署的上限预选
                  {{ policy.lifetime_days }}
                  天——这里签出的密钥不会超过这个期限。</template
                >
                <template v-else
                  >留空就按这个部署的默认值签——这里签出的密钥不会超过这个期限。</template
                >
                到期后这把密钥就失效了，用它的机器会收到 401；可以在列表里续期。
              </p>
            </div>

            <p v-if="fieldMessage !== null" class="field-error">
              {{ fieldMessage }}
            </p>

            <div class="key-form-foot">
              <!-- `leaveForm` rather than a bare `mode = 'list'`: the banner belongs to the request being
                 abandoned, and left up it sits above the list saying something about a creation nobody
                 is doing any more. -->
              <!-- Disabled while a submission is in flight, the way a row's own actions are: leaving
                 then is what makes the answer arrive on a screen nobody is on. -->
              <button
                type="button"
                class="button is-quiet"
                :disabled="submitting"
                @click="leaveForm"
              >
                取消
              </button>
              <button
                type="submit"
                class="button"
                data-key-submit
                :disabled="submitting || form.name.trim() === ''"
              >
                新建密钥
              </button>
            </div>
          </form>
        </div>
      </div>
    </section>
  </template>

  <template v-else-if="moment !== null">
    <section class="page">
      <nav class="crumbs" aria-label="面包屑">
        <!-- Unfiltered, exactly like 完成: this screen has just created a fact, and a list that hides it
           is a list the reader cannot check (see `finishMoment`). Same three segments as the form. -->
        <a href="/account">设置</a>
        <span class="crumbs-sep" aria-hidden="true">/</span>
        <a href="/account/keys?filter=all">API 密钥</a>
        <span class="crumbs-sep" aria-hidden="true">/</span>
        <span class="crumbs-here" aria-current="page">{{
          moment.kind === 'created' ? '新建 API 密钥' : '续期密钥'
        }}</span>
      </nav>

      <header class="page-head">
        <h1>{{ moment.kind === 'created' ? '密钥已创建' : '密钥已续期' }}</h1>
      </header>

      <div class="key-card">
        <div class="key-head"><span class="key-count">密钥详情</span></div>
        <div class="key-body key-detail">
          <p v-if="moment.kind === 'created'" class="hint">
            <strong>密钥仅在此时显示一次</strong>——服务端只保存其摘要，离开本页后将无法再次查看。
          </p>
          <p v-else class="hint">
            续期完成，<strong>原密钥已立即失效</strong>。新密钥仅在此时显示一次，服务端只保存其摘要。
          </p>

          <!-- The key's own facts, standing under the sentence about the string they belong to — and
               the two the form had just asked for, which is what the card's title promises. **Not the
               name**: the heading above already named this key, and the deliverable's own line is
               「权限 … · 到期 …」 (plus 说明 when one was written). -->
          <p class="hint key-facts">
            权限 {{ scopeLabel(moment.key.scope) }} · {{ expiryText(moment.key.expires_at) }}<template
              v-if="moment.kind === 'created' && moment.key.note !== null && moment.key.note !== ''"
            > · 说明：{{ moment.key.note }}</template>
          </p>

          <div class="secret">
            <code id="key-secret" class="secret-value" :class="{ 'is-masked': !moment.revealed }">{{
              moment.token
            }}</code>
            <button
              type="button"
              class="secret-btn"
              :aria-label="moment.revealed ? '隐藏密钥' : '显示密钥'"
              :aria-pressed="moment.revealed"
              @click="moment.revealed = !moment.revealed"
            >
              <!-- Two icons, one shown, and `v-show` rather than `hidden`: `hidden` is a property of
                 HTML elements, and on an `<svg>` setting it changes nothing — the icon stays where
                 the markup left it. The design demo shipped exactly that bug. -->
              <svg
                v-show="!moment.revealed"
                viewBox="0 0 16 16"
                fill="currentColor"
                aria-hidden="true"
              >
                <path
                  fill-rule="evenodd"
                  clip-rule="evenodd"
                  d="M0 8s3-6 8-6 8 6 8 6-3 6-8 6-8-6-8-6Zm1.81.13A13.593 13.593 0 0 1 1.73 8l.082-.13c.326-.51.806-1.187 1.42-1.856C4.494 4.635 6.12 3.5 8 3.5c1.878 0 3.506 1.135 4.77 2.514A13.705 13.705 0 0 1 14.27 8a14.021 14.021 0 0 1-1.502 1.986C11.506 11.365 9.88 12.5 8 12.5c-1.878 0-3.506-1.135-4.77-2.514A13.703 13.703 0 0 1 1.81 8.13ZM11 8a3 3 0 1 1-2.117-2.868 1.5 1.5 0 1 0 1.985 1.985A3 3 0 0 1 11 8Z"
                />
              </svg>
              <svg
                v-show="moment.revealed"
                viewBox="0 0 16 16"
                fill="currentColor"
                aria-hidden="true"
              >
                <path
                  fill-rule="evenodd"
                  clip-rule="evenodd"
                  d="M14.776 2.284a.75.75 0 0 0-1.06-1.06l-1.953 1.953C10.707 2.499 9.44 2 8 2 5.506 2 3.533 3.49 2.24 4.86A14.615 14.615 0 0 0 .226 7.576a5.39 5.39 0 0 0-.028.052l-.008.014-.003.005v.002L.85 8l-.663-.35L.002 8l.185.35L.85 8l-.663.35v.002l.002.002.004.007.012.023c.01.02.026.047.046.082a14.417 14.417 0 0 0 .82 1.262c.47.647 1.13 1.447 1.96 2.18L1.22 13.72a.75.75 0 1 0 1.06 1.06L14.776 2.284Zm-10.681 8.56 1.32-1.32a3 3 0 0 1 4.109-4.109l1.148-1.147C9.864 3.8 8.969 3.5 8 3.5c-1.88 0-3.483 1.134-4.67 2.39A13.114 13.114 0 0 0 1.716 8c.13.213.32.508.567.846a11.98 11.98 0 0 0 1.811 1.998Zm9.42-5.166a.75.75 0 0 1 1.053.122c.447.564.901 1.2 1.245 1.85l.185.35-.185.35L15.15 8l.663.351-.001.002-.003.005-.008.014a9.81 9.81 0 0 1-.53.865 14.62 14.62 0 0 1-1.51 1.903C12.467 12.51 10.494 14 8 14a6.939 6.939 0 0 1-1.021-.08l-.02-.002-.006-.001h-.002l.12-.741-.12.74a.75.75 0 0 1 .239-1.48h.002l.011.001a6.024 6.024 0 0 0 .235.03c.158.017.362.033.562.033 1.88 0 3.483-1.134 4.67-2.39a13.11 13.11 0 0 0 1.616-2.115 12.33 12.33 0 0 0-.893-1.263.75.75 0 0 1 .121-1.054Z"
                />
              </svg>
            </button>
            <!-- `is-copy`/`is-copied` rather than a selector on the label: the label changes with the
               state, and CSS that hangs off a changing string is a rule that stops matching. The
               design's own class names, for the two states it draws (§那一刻). -->
            <button
              type="button"
              class="secret-btn is-copy"
              data-key-copy
              :class="{ 'is-copied': moment.copied }"
              :aria-label="copyLabel"
              @click="copy"
            >
              <svg
                v-show="!moment.copied"
                viewBox="0 0 16 16"
                fill="currentColor"
                aria-hidden="true"
              >
                <path
                  fill-rule="evenodd"
                  clip-rule="evenodd"
                  d="M4 9.5H2.5v-7h7V4H11V2a1 1 0 0 0-1-1H2a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1h2V9.5Zm9.5 4h-7v-7h7v7ZM5 6a1 1 0 0 1 1-1h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6Z"
                />
              </svg>
              <svg
                v-show="moment.copied"
                viewBox="0 0 16 16"
                fill="currentColor"
                aria-hidden="true"
              >
                <path
                  fill-rule="evenodd"
                  clip-rule="evenodd"
                  d="M12.78 4.62a.75.75 0 0 1 0 1.06l-6.097 6.097a.75.75 0 0 1-1.069-.009L3.211 9.284a.75.75 0 1 1 1.078-1.043l1.873 1.936L11.72 4.62a.75.75 0 0 1 1.06 0Z"
                />
              </svg>
            </button>
          </div>

          <p v-if="moment.selected" class="hint" role="status">已选中，请按你的复制快捷键。</p>
          <p class="hint">如未保存：请撤销该密钥后重新创建，或对原密钥直接续期。</p>
        </div>
      </div>

      <!-- Outside the card, below it, which is where the reference product puts its own way out. It
         is disabled until the string has been copied, and it says its own precondition rather than
         being a greyed-out "完成" that does not explain itself.

         **And it is where leaving is asked about**, because it is already the block that takes you
         out: a reader who clicks the crumb or the navigation while the string is still here gets this
         block's own question in place of its button. Escape answers 留在这一屏, which is what a
         question of this shape does everywhere else on this page. -->
      <div class="key-exit" @keydown.esc="stayHere">
        <button
          v-if="leaving === null"
          type="button"
          class="button"
          :disabled="!held"
          @click="finishMoment"
        >
          {{ held ? '完成' : '复制后方可完成' }}
        </button>
        <template v-else>
          <span class="hint">这把密钥还没复制——离开后取不回来，只能撤销后重建。</span>
          <button
            ref="leaveYes"
            type="button"
            class="link-button danger"
            @click="leaveAnyway"
          >
            仍然离开
          </button>
          <button type="button" class="link-button" @click="stayHere">留在这一屏</button>
        </template>
      </div>
    </section>
  </template>
</template>
