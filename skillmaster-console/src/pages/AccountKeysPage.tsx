import { useEffect, useRef, useState } from 'react'
import { addressFor, go, param, replaceAddress } from '../address'
import { codeMessage, fieldErrors } from '../api/errors'
import { listKeys, revokeKey, rotateKey } from '../api/keys'
import { loginPath } from '../login'
import type { ApiKey, ApiKeyPolicy } from '../api/types'
import KeyCreateForm from '../components/key/KeyCreateForm'
import KeyList from '../components/key/KeyList'
import KeyMoment from '../components/key/KeyMoment'
import {
  heldOf,
  stateOf,
  type Filter,
  type Leaving,
  type Moment,
  type Question,
  type State,
} from '../components/key/keyModel'

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
 *
 * **What changed in the port.** `asking`, `moment`, `form` and `submitting` are mirrored into refs,
 * because Vue read their latest values through its refs: `asking` from the keydown listener
 * registered once at mount, `moment` from the two document listeners that guard leaving, `form`
 * from `load` after its `await`, and `submitting` from the crumb's guard. A React closure would keep
 * the values from the render that created it. The refs are written synchronously with the state they
 * mirror, so the two can never disagree. `nextTick` became `requestAnimationFrame`, and Vue's
 * `watch(mode)` became an effect guarded so it does not fire on mount.
 */
function readFilter(): Filter {
  const value = param('filter')
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
  return param('q') ?? ''
}

export default function AccountKeysPage() {
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [keys, setKeys] = useState<ApiKey[]>([])
  const [policy, setPolicy] = useState<ApiKeyPolicy | null>(null)
  const [banner, setBanner] = useState<string | null>(null)

  /**
   * Which of the three screens this page is: the list, the create form, or the moment a string exists.
   *
   * **The moment is memory and nothing else, and that is the whole of "the string does not survive a
   * reload".** It cannot: the server stores a hash and this is the only response that ever carried the
   * whole key, so a load that did not just create one has nothing to show. The demo had to model that
   * (it looked at `performance`'s navigation type to tell arriving from reloading); here it falls out
   * of where the value lives, which is the better place for it.
   */
  const [mode, setMode] = useState<'list' | 'new' | 'moment'>('list')
  const [moment, setMomentState] = useState<Moment | null>(null)
  const momentRef = useRef<Moment | null>(null)

  /** The row whose consequence question is open, and which question it is. One at a time. */
  const [asking, setAskingState] = useState<Question | null>(null)
  const askingRef = useRef<Question | null>(null)
  /** The row an action is in flight for, so only that row disables. */
  const [busy, setBusy] = useState<string | null>(null)

  const [filter, setFilter] = useState<Filter>(() => readFilter())
  const [query, setQuery] = useState(() => readQuery())

  const [now, setNow] = useState(() => Date.now())

  /** The refs are written with the state they mirror, in the same tick. */
  function setMoment(next: Moment | null): void {
    momentRef.current = next
    setMomentState(next)
  }

  function setAsking(next: Question | null): void {
    askingRef.current = next
    setAskingState(next)
  }

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
  const [leaving, setLeaving] = useState<Leaving | null>(null)
  /** The 仍然离开 button, so the answer that loses something is the one under the fingers. */
  const leaveYes = useRef<HTMLButtonElement | null>(null)
  /**
   * Answered 仍然离开: the screen is on its way out. It is what stands the guard down, so that the
   * gesture being replayed below does not arrive back here and ask again.
   */
  const leavingNow = useRef(false)

  useEffect(() => {
    let cancelled = false
    // Vue awaited the list before binding these, and the order matters: the handlers read the page's
    // state, so they are attached once that state is here — and an unmount before the answer does not
    // leave a listener behind.
    void (async () => {
      await load()
      if (cancelled) {
        return
      }
      window.addEventListener('keydown', onKeydown)
      // Leaving the moment is asked about by intercepting the shell's own navigation, so it is bound for
      // as long as this page is on screen — see `guarded` for why every handler checks the state itself.
      document.addEventListener('click', onExitClick, true)
      document.addEventListener('submit', onExitSubmit, true)
    })()
    return () => {
      cancelled = true
      window.removeEventListener('keydown', onKeydown)
      document.removeEventListener('click', onExitClick, true)
      document.removeEventListener('submit', onExitSubmit, true)
    }
    // The listeners read `asking` and `moment` through the refs above, exactly as Vue read its refs;
    // `load` only writes state. Registered once, on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /**
   * Escape answers the question the row is asking — but only while one is open, which is why the
   * listener is bound always and does nothing the rest of the time: a handler added and removed per
   * confirmation is one more thing that can leak, and this costs a comparison per keystroke.
   */
  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape' && askingRef.current !== null) {
      event.preventDefault()
      cancel()
    }
  }

  // A question belongs to the moment it was asked. Anything that takes the row away — a reload, a
  // different screen, a successful answer — closes it, so returning to the list cannot leave one open
  // over a row that has since changed.
  //
  // Vue's `watch` does not fire on mount; React's effect does.
  const modeSettled = useRef(false)
  useEffect(() => {
    if (!modeSettled.current) {
      modeSettled.current = true
      return
    }
    setAsking(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode])

  async function load(): Promise<void> {
    const result = await listKeys()
    if (!result.ok) {
      if (result.code === 'unauthenticated') {
        // No anonymous version of "your keys", so a session that has gone ends up at the sign-in form,
        // which knows how to come back here.
        go(loginPath())
        return
      }
      setState('failed')
      setBanner(codeMessage(result.code, result.message))
      return
    }
    const nextPolicy = result.data.policy ?? null
    setKeys(result.data.keys)
    setPolicy(nextPolicy)
    setNow(Date.now())
    setState('ready')

    // **And the failed request it is replacing stops claiming to be the current state.** A banner set by
    // a row action that failed (the network, a refusal) used to survive the next reload: the list came
    // back correct and the page still said the request had failed — about a screen that had just been
    // read successfully.
    setBanner(null)
  }

  /** The keys whose name matches the search box — what the four counts count. */
  const searched: ApiKey[] = (() => {
    const term = query.trim().toLowerCase()
    if (term === '') {
      return keys
    }
    return keys.filter((key) => key.name.toLowerCase().includes(term))
  })()

  const counts: Record<State, number> = (() => {
    const total: Record<State, number> = {
      active: 0,
      soon: 0,
      revoked: 0,
      expired: 0,
    }
    for (const key of searched) {
      total[stateOf(key, now)] += 1
    }
    return total
  })()

  const shown: ApiKey[] =
    filter === 'all' ? searched : searched.filter((key) => stateOf(key, now) === filter)

  /**
   * What to say when there is nothing to show, and the three answers differ because the reasons do.
   *
   * The third one asks what the *search* matched rather than whether the box has anything in it: an
   * empty list under an active filter while a card above counts keys whose names contain the term is a
   * sentence arguing with a number.
   */
  const emptySentence: string = (() => {
    if (keys.length === 0) {
      return '暂无密钥。CLI 与 agent 通过密钥访问你的 skill，无需在浏览器中登录。'
    }
    if (searched.length === 0) {
      return `没有名称包含「${query.trim()}」的密钥。`
    }
    return '该状态下暂无密钥。'
  })()

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
  function keysAddress(target: Filter, term: string = query): string {
    const params = new URLSearchParams()
    if (target !== 'active') {
      params.set('filter', target)
    }
    const trimmed = term.trim()
    if (trimmed !== '') {
      params.set('q', trimmed)
    }
    return addressFor('/account/keys', params)
  }

  /**
   * The search term, written into the address without a load.
   *
   * `replaceState` rather than a navigation: a page reload per keystroke would be absurd, and the
   * address is worth keeping current because the design's promise is that a filtered list can be sent
   * to somebody. The filter itself is a real link (`filterHref`), which is how every other navigation
   * in this app works.
   */
  function onQuery(value: string): void {
    setQuery(value)
    replaceAddress(keysAddress(filter, value))
  }

  /* ---------------------------------------------------------------------------------------------
     The two things that can be done to a row.
     --------------------------------------------------------------------------------------------- */

  function ask(id: string, action: 'revoke' | 'rotate'): void {
    setAsking({ id, action })
    // Focus lands on the confirm button, so that answering is one keystroke and Escape is the other.
    requestAnimationFrame(() => {
      document.querySelector<HTMLButtonElement>(`[data-key-id="${id}"] [data-key-confirm]`)?.focus()
    })
  }

  function cancel(): void {
    const open = askingRef.current
    setAsking(null)
    if (open === null) {
      return
    }
    // Back to the button that was pressed, rather than to the top of the document: a keyboard or
    // screen-reader user should not have to find their place again after answering "no".
    requestAnimationFrame(() => {
      document
        .querySelector<HTMLButtonElement>(
          `[data-key-id="${open.id}"] [data-key-action="${open.action}"]`,
        )
        ?.focus()
    })
  }

  async function confirmRevoke(id: string): Promise<void> {
    setBusy(id)
    const result = await revokeKey(id)
    setBusy(null)
    setAsking(null)
    if (!result.ok) {
      setBanner(codeMessage(result.code, result.message))
      return
    }
    // Re-read rather than patch the row: the listing's own answer to "when did it stop working" comes
    // from `revoked_at`, and a row edited in the browser would be this page's opinion of it.
    await load()
  }

  async function confirmRotate(id: string): Promise<void> {
    setBusy(id)
    const result = await rotateKey(id)
    setBusy(null)
    setAsking(null)
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
      setBanner(onField.id ?? codeMessage(result.code, result.message))
      return
    }
    showMoment('rotated', result.data.key, result.data.token)
  }

  /* ---------------------------------------------------------------------------------------------
     Creating one, and the moment its string exists.
     --------------------------------------------------------------------------------------------- */

  function startCreate(): void {
    setBanner(null)
    // **A fresh form every time** is the component's own doing: it mounts when this screen opens, so
    // the second visit cannot offer the previous key's name, note, permissions and date.
    setMode('new')
  }

  function showMoment(kind: 'created' | 'rotated', key: ApiKey, token: string): void {
    setMoment({
      kind,
      key,
      token,
      revealed: false,
      copied: false,
      selected: false,
    })
    // A new string to lose starts with no question open and the guard armed again.
    setLeaving(null)
    leavingNow.current = false
    setMode('moment')
  }

  /** Back to the list, re-read: a renewal changed a row, and the list is where that shows. */
  async function finishMoment(): Promise<void> {
    setMoment(null)
    setMode('list')
    setFilter('all')
    // **The one place on this page that lets go of the list's state, and it lets go of both halves of
    // it** (design §访问凭据). The moment just created a fact the reader has to be able to check, and
    // either half can hide it: a filter of 已撤销 hides a new key, and so does a search term that was
    // typed before it existed — the person lands on「0 个密钥」directly after being told it was created.
    // `filter=all` rather than dropping the parameter, because on this page "absent" *is* 活跃.
    setQuery('')
    replaceAddress('/account/keys?filter=all')
    await load()
  }

  /* ---------------------------------------------------------------------------------------------
     Copying the string — a real button, with two fallbacks underneath it.
     --------------------------------------------------------------------------------------------- */

  /**
   * Writes one field of the moment on screen, and only while it is still the same moment.
   *
   * **By the string, not by the object.** Revealing the key replaces the `Moment` with a copy, so an
   * identity check would drop a copy that finished while the reader was toggling the eye: the button
   * would still say 复制密钥 about a string that is already on the clipboard, and the leave question
   * would still treat it as unsaved. The token is what "the same moment" means (Vue mutated the one
   * reactive object, which is how it survived the same sequence).
   */
  function markMoment(current: Moment, patch: Partial<Moment>): void {
    const live = momentRef.current
    if (live === null || live.token !== current.token) {
      return
    }
    setMoment({ ...live, ...patch })
  }

  async function copy(): Promise<void> {
    const current = momentRef.current
    if (current === null) {
      return
    }
    try {
      await navigator.clipboard.writeText(current.token)
      markMoment(current, { copied: true })
      return
    } catch {
      // Falls through: an insecure origin, a permissions policy, or a browser that wants a different
      // gesture. `execCommand` works from inside a click handler even where the async API does not.
    }
    if (execCommandCopy(current.token)) {
      markMoment(current, { copied: true })
      return
    }
    selectSecret()
    markMoment(current, { selected: true })
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

  const held = heldOf(moment)

  const copyLabel: string = (() => {
    if (moment === null) {
      return '复制密钥'
    }
    if (moment.copied) {
      return '已复制'
    }
    return moment.selected ? '已选中，请按你的复制快捷键' : '复制密钥'
  })()

  /* ---------------------------------------------------------------------------------------------
     Leaving the moment — asked in the block that was already the way out.
     --------------------------------------------------------------------------------------------- */

  /**
   * Whether there is something to lose: the string is here, and it is not in the reader's hands yet.
   * The same condition as the exit button's own precondition — see `held`.
   */
  function guarded(): boolean {
    return !leavingNow.current && momentRef.current !== null && !heldOf(momentRef.current)
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
    setLeaving({ go, back })
    requestAnimationFrame(() => {
      leaveYes.current?.focus()
    })
  }

  function stayHere(): void {
    const open = leaving
    setLeaving(null)
    if (open === null) {
      return
    }
    // **Back to where the question interrupted — and it is not the button this block drew.** That
    // button is *disabled* while the string is uncopied, and a disabled control cannot take the focus
    // at all, so handing it back there would leave the reader on `<body>` with no way back to what they
    // were doing. `cancel` above states the same rule for the row's own question.
    requestAnimationFrame(() => {
      open.back?.focus()
    })
  }

  function leaveAnyway(): void {
    const open = leaving
    setLeaving(null)
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
    leavingNow.current = true
    try {
      open.go()
    } finally {
      leavingNow.current = false
    }
  }

  return (
    <>
      {/* The list, the form and the moment are three screens of one page, which is what the design
          asks for: the moment is not a notification about an object, it *is* the object, so it takes
          the screen rather than being inserted into the list. */}
      {mode === 'list' ? (
        /* **The page, in the design's own wrapper**: a centred column of the page's measure rather than
           the whole width of the panel. Every screen of this page is one `.page`. */
        <KeyList
          state={state}
          banner={banner}
          shown={shown}
          counts={counts}
          filter={filter}
          query={query}
          filterHref={filterHref}
          onQuery={onQuery}
          now={now}
          busy={busy}
          asking={asking}
          ask={ask}
          cancel={cancel}
          confirmRevoke={confirmRevoke}
          confirmRotate={confirmRotate}
          startCreate={startCreate}
          emptySentence={emptySentence}
        />
      ) : mode === 'new' ? (
        <KeyCreateForm
          policy={policy}
          listHref={filterHref(filter)}
          banner={banner}
          onBanner={setBanner}
          onCreated={(created, token) => showMoment('created', created, token)}
          onCancel={() => setMode('list')}
        />
      ) : moment !== null ? (
        <KeyMoment
          moment={moment}
          held={held}
          copyLabel={copyLabel}
          copy={copy}
          setMoment={setMoment}
          finishMoment={finishMoment}
          leaving={leaving}
          leaveYes={leaveYes}
          stayHere={stayHere}
          leaveAnyway={leaveAnyway}
        />
      ) : null}
    </>
  )
}
