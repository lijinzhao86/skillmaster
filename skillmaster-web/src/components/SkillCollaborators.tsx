import { useEffect, useRef, useState } from 'react'
// `query` is this page's search term, so the module's reader is named for what it returns.
import { addressOrPath, param, query as currentParams, relativeAddress, replaceAddress } from '../address'
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
import RungMenu from './RungMenu'

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
 *
 * **What changed in the port.** The two `watch`es became effects guarded by a "first run" ref,
 * because Vue's `watch` does not fire on mount and React's effect does. The two `nextTick`s became
 * `requestAnimationFrame`. The values an `await` has to read again afterwards (the search, the page,
 * the lists being measured) are mirrored into refs, written synchronously with the state they
 * mirror — in React a closure would still be holding the values from the render the request was
 * started in.
 */
export interface SkillCollaboratorsProps {
  namespace: string
  name: string
  /** Private is `publicRung === null`; the list's sentences and offers read that one fact. */
  publicRung: Rung | null
  /**
   * The queue's size, told to the page rather than fetched a second time: the settings nav's count and
   * this block are two views of one fact, and two reads would be two chances for them to disagree.
   */
  onCount: (value: number) => void
}

export default function SkillCollaborators({
  namespace,
  name,
  publicRung,
  onCount,
}: SkillCollaboratorsProps) {
  const [query, setQueryState] = useState(() => (param('q') ?? '').trim())
  const [page, setPageState] = useState(() =>
    Math.max(1, Number.parseInt(param('p') ?? '1', 10) || 1),
  )

  /** True between clamping an out-of-range page and the read of the page it landed on. */
  const [clamping, setClamping] = useState(false)

  /** The server's page size, learned from the first response — the pager only reports what came back. */
  const [pageSize, setPageSize] = useState(10)
  const PAGE_SIZE = pageSize

  const [grants, setGrants] = useState<SkillGrant[]>([])
  const [total, setTotal] = useState(0)
  const [requests, setRequests] = useState<AccessRequest[]>([])
  const [banner, setBanner] = useState<string | null>(null)
  const [handle, setHandle] = useState('')
  const offered = rungsAbove(publicRung)
  const [shareRole, setShareRole] = useState<Rung>(offered[0]?.name ?? 'invoke')
  const [busy, setBusy] = useState(false)

  /** Requests carry their own chosen rung; a rejection or approval clears the row anyway. */
  const [wanted, setWantedState] = useState<Record<string, Rung>>({})

  /** The owner's handle. A namespace's slug is the handle it was created from (design's `dev`). */
  const owner = namespace

  // The values an `await` reads again afterwards, mirrored so the state and the ref cannot disagree.
  const queryRef = useRef(query)
  const pageRef = useRef(page)
  const grantsRef = useRef<SkillGrant[]>([])
  const requestsRef = useRef<AccessRequest[]>([])
  const totalRef = useRef(0)
  const pageSizeRef = useRef(10)

  function setQuery(value: string): void {
    queryRef.current = value
    setQueryState(value)
  }

  function setPage(value: number): void {
    pageRef.current = value
    setPageState(value)
  }

  function matchesOwner(term: string): boolean {
    const needle = term.trim().toLowerCase()
    return needle === '' || owner.toLowerCase().includes(needle)
  }

  const ownerMatches = matchesOwner(query)

  /**
   * The number of people this skill has, owner included.
   *
   * **Not the filtered count.** The server counts only the rows that match `q`, so a search with no
   * matches would otherwise make the empty-state sentence claim the skill has no collaborators at all —
   * a statement about the account made from a statement about one query. The unfiltered number is
   * remembered from the last read that asked no question, and the heading shows the search's number
   * the way the design does ("the number of rows on screen").
   */
  const [knownTotal, setKnownTotal] = useState<number | null>(null)

  /** Rows on screen: the owner (first, always) plus this page of grants. */
  const rowCount = total + (ownerMatches ? 1 : 0)

  /**
   * How many people the skill has, ignoring the search. Used for the sentence under the heading,
   * which is about the skill rather than about the query.
   */
  const population = (knownTotal ?? total) + 1

  /** A one-line answer under the block heading — different sentences for private/public and empty. */
  const hint =
    publicRung === null
      ? population <= 1
        ? '这个 skill 是私有的，目前只有你可以访问。在「共享给」中填写用户名并选择档位，即可添加协作者。'
        : '这个 skill 是私有的，只有你和下列协作者可以访问。'
      : `公开之后${rungsUpTo(publicRung)}对所有人开放。` +
        (population <= 1
          ? '目前没有单独授予个人的档位，可以通过「共享给」添加。'
          : '以下为单独授予个人的档位；个人的实际权限取两者中较高的一档。')

  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  /**
   * "By x–y of N" for the rows on screen. The owner is one row on page one and is not a grant the
   * server pages by, so page one's range starts at 1 and includes that extra row; later pages are
   * offset by it.
   */
  const range = ((): { start: number; end: number } => {
    const extra = page === 1 && ownerMatches ? 1 : 0
    // Page one always starts at the first person. Every later page is offset by one more row only
    // when the owner was part of the match — a search that misses the owner does not reserve his row,
    // so offsetting those pages too made the range start one past the first row on screen and end
    // past the population.
    const start =
      page === 1
        ? 1
        : (page - 1) * PAGE_SIZE + 1 + (ownerMatches ? 1 : 0)
    return { start, end: start + grants.length + extra - 1 }
  })()

  /** Where the settings sub-page keeps its own state, so 协作者 and 常规 can be walked between. */
  function writeAddress(pageNumber: number = page): void {
    const next = currentParams()
    next.set('tab', 'settings')
    next.set('sub', 'collaborators')
    if (query === '') {
      next.delete('q')
    } else {
      next.set('q', query)
    }
    if (pageNumber === 1) {
      next.delete('p')
    } else {
      next.set('p', String(pageNumber))
    }
    replaceAddress(addressOrPath(next))
  }

  async function loadGrants(q: string = queryRef.current, p: number = pageRef.current): Promise<void> {
    const result = await listGrants(namespace, name, q, p)
    if (!result.ok) {
      setBanner(codeMessage(result.code, result.message))
      return
    }
    grantsRef.current = result.data.grants
    setGrants(result.data.grants)
    totalRef.current = result.data.total
    setTotal(result.data.total)
    pageSizeRef.current = result.data.page_size
    setPageSize(result.data.page_size)
    if (q === '') {
      setKnownTotal(result.data.total)
    }
    // Clamp a page number past the end, the same rule the skill list follows. The watcher on `page`
    // owns the write and the read: doing either here as well sent the same page twice.
    const last = Math.max(1, Math.ceil(result.data.total / result.data.page_size))
    if (p > last) {
      // Until the clamped page's own answer lands, the rows on screen are the empty answer for the
      // page that does not exist — a pager drawn then would say 「第 12–11 人」. Hide it for that
      // window rather than print a range that is not about what is on screen.
      setClamping(true)
      setPage(last)
      return
    }
    setClamping(false)
    requestAnimationFrame(() => {
      sizeMemberColumn()
      reserveHeight()
    })
  }

  /** Reads the unfiltered population. The previous value stays until this answer arrives. */
  async function fetchPopulation(): Promise<void> {
    const result = await listGrants(namespace, name, undefined, 1)
    if (result.ok) {
      setKnownTotal(result.data.total)
    }
  }

  /**
   * The skill's population, ignoring the search — for the sentence under the heading, which is about
   * the skill and not about one query. Read once when the page was opened on a search: the filtered
   * response's own total cannot answer it.
   */
  async function loadPopulation(): Promise<void> {
    if (queryRef.current === '' || knownTotal !== null) {
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
    if (queryRef.current === '') {
      // The reloaded list carries the count itself.
      return
    }
    await fetchPopulation()
  }

  async function loadRequests(): Promise<void> {
    const result = await listAccessRequests(namespace, name)
    if (!result.ok) {
      // Not everybody reaching this component may see the queue; the block simply does not draw.
      requestsRef.current = []
      setRequests([])
      onCount(0)
      return
    }
    requestsRef.current = result.data.requests
    setRequests(result.data.requests)
    onCount(result.data.requests.length)
    requestAnimationFrame(() => {
      sizeMemberColumn()
    })
    setWantedState((previous) => {
      const next = { ...previous }
      const offeredNames = offered.map((rung) => rung.name)
      for (const request of result.data.requests) {
        if (next[request.handle] === undefined) {
          next[request.handle] = offeredNames.includes(request.wanted_role)
            ? request.wanted_role
            : (offeredNames[0] ?? 'invoke')
        }
      }
      return next
    })
  }

  useEffect(() => {
    void (async () => {
      await Promise.all([loadGrants(), loadRequests(), loadPopulation()])
      requestAnimationFrame(() => {
        sizeMemberColumn()
      })
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Typing filters this card and turns back to its first page: a search that stayed on page two would
  // look like "there is nothing". Debounced, because the page is cut on the server — a round trip per
  // keystroke is what makes a search box feel like a form.
  const searchTimer = useRef<number | undefined>(undefined)
  const searchFirstRun = useRef(true)
  useEffect(() => {
    if (searchFirstRun.current) {
      searchFirstRun.current = false
      return
    }
    setPage(1)
    writeAddress(1)
    window.clearTimeout(searchTimer.current)
    searchTimer.current = window.setTimeout(() => {
      void loadGrants(queryRef.current, 1)
    }, 200)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query])

  const pageFirstRun = useRef(true)
  useEffect(() => {
    if (pageFirstRun.current) {
      pageFirstRun.current = false
      return
    }
    writeAddress()
    void loadGrants()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page])

  function goToPage(number: number): void {
    setPage(number)
  }

  /** The pager's own address, carrying the search — a link, like every other page number here. */
  function pagerHref(number: number): string {
    const next = currentParams()
    next.set('tab', 'settings')
    next.set('sub', 'collaborators')
    if (query !== '') {
      next.set('q', query)
    }
    if (number === 1) {
      next.delete('p')
    } else {
      next.set('p', String(number))
    }
    return relativeAddress(next)
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
    const names = [
      owner,
      ...grantsRef.current.map((grant) => grant.handle),
      ...requestsRef.current.map((request) => request.handle),
    ]
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
  const partial = total > PAGE_SIZE && grants.length < PAGE_SIZE

  /**
   * A short page keeps a full page's height, so the block after it does not jump when the pager moves.
   * Measured from the rows themselves, because a row's height depends on the window (a long handle
   * wraps at narrow widths) and a hard-coded number would be wrong at one of them.
   */
  const listEl = useRef<HTMLUListElement | null>(null)
  const [reservedHeight, setReservedHeight] = useState<string | undefined>(undefined)

  function reserveHeight(): void {
    const ownerMatch = matchesOwner(queryRef.current)
    const isPartial =
      totalRef.current > pageSizeRef.current && grantsRef.current.length < pageSizeRef.current
    if (!isPartial || listEl.current === null) {
      setReservedHeight(undefined)
      return
    }
    let tallest = 0
    for (const row of Array.from(listEl.current.children)) {
      tallest = Math.max(tallest, (row as HTMLElement).getBoundingClientRect().height)
    }
    // The tallest page is page one when the owner is in the match — one row more than the server's
    // page. Reserving that everywhere keeps the block below from moving when the pager moves.
    const slots = pageSizeRef.current + (ownerMatch ? 1 : 0)
    // `-1` is the full page's last row having no bottom border.
    setReservedHeight(tallest === 0 ? undefined : `${Math.round(tallest * slots) - 1}px`)
  }

  async function share(): Promise<void> {
    const who = handle.trim()
    if (who === '') {
      return
    }
    setBusy(true)
    const result = await grantSkill(namespace, name, who, shareRole)
    setBusy(false)
    if (!result.ok) {
      setBanner(codeMessage(result.code, result.message))
      return
    }
    setHandle('')
    await Promise.all([loadGrants(), refreshPopulation()])
  }

  async function changeRole(grant: SkillGrant, role: string): Promise<void> {
    const result = await grantSkill(namespace, name, grant.handle, role as Rung)
    if (!result.ok) {
      setBanner(codeMessage(result.code, result.message))
      return
    }
    // A rung change is not a population change; the list is all that has to be re-read.
    await loadGrants()
  }

  async function withdraw(grant: SkillGrant): Promise<void> {
    const result = await revokeGrant(namespace, name, grant.handle)
    if (!result.ok) {
      setBanner(codeMessage(result.code, result.message))
      return
    }
    await Promise.all([loadGrants(), refreshPopulation()])
  }

  async function approve(request: AccessRequest): Promise<void> {
    const rung = wanted[request.handle] ?? 'invoke'
    const result = await approveAccessRequest(namespace, name, request.handle, rung)
    if (!result.ok) {
      setBanner(codeMessage(result.code, result.message))
      return
    }
    await Promise.all([loadRequests(), loadGrants(), refreshPopulation()])
  }

  async function deny(request: AccessRequest): Promise<void> {
    const result = await denyAccessRequest(namespace, name, request.handle)
    if (!result.ok) {
      setBanner(codeMessage(result.code, result.message))
      return
    }
    await loadRequests()
  }

  function setWanted(person: string, rung: string): void {
    setWantedState((previous) => ({ ...previous, [person]: rung as Rung }))
  }

  return (
    <section className="settings-pane" data-sub="collaborators">
      <h2>协作者</h2>

      {banner !== null ? <p className="banner">{banner}</p> : null}

      <section className="settings-block">
        <h3>共享给</h3>
        <div className="share-row">
          <input
            className="control"
            type="text"
            value={handle}
            autoComplete="off"
            spellCheck={false}
            placeholder="用户名"
            onChange={(event) => setHandle(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                void share()
              }
            }}
          />
          <RungMenu
            value={shareRole}
            options={offered}
            label="权限"
            onChange={(value) => setShareRole(value as Rung)}
          />
          <button type="button" className="button" disabled={busy} onClick={() => void share()}>
            共享
          </button>
        </div>
        <p className="hint">仅所有者与「管理」可以修改他人的档位。</p>
      </section>

      <section className="settings-block">
        <div className="member-head">
          <h3>
            协作者名单 <span className="count">{rowCount}</span>
          </h3>
          <label className="search member-search">
            <span className="visually-hidden">搜索协作者</span>
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
              <line
                x1="15.4"
                y1="15.4"
                x2="20.6"
                y2="20.6"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
              />
            </svg>
            <input
              className="control"
              type="search"
              value={query}
              autoComplete="off"
              spellCheck={false}
              placeholder="搜索用户名"
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>
        </div>
        <p className="hint">{hint}</p>
        {rowCount > 0 ? (
          <ul
            ref={listEl}
            className={partial ? 'grants is-partial' : 'grants'}
            style={reservedHeight === undefined ? undefined : { minHeight: reservedHeight }}
          >
            {/* The owner: not on the ladder, not grantable, and still the first row. */}
            {page === 1 && ownerMatches ? (
              <li className="is-owner">
                <span className="avatar" aria-hidden="true">
                  {avatarInitial(owner)}
                </span>
                <span className="grant-who">{owner}</span>
                <span className="member-type">个人</span>
                <span className="role-tag">所有者</span>
              </li>
            ) : null}
            {grants.map((grant) => (
              <li key={grant.handle}>
                <span className="avatar" aria-hidden="true">
                  {avatarInitial(grant.handle)}
                </span>
                <span className="grant-who">{grant.handle}</span>
                <span className="member-type">个人</span>
                <RungMenu
                  value={grant.role}
                  options={rungChoices(grant.role, publicRung)}
                  label={`${grant.handle} 的档位`}
                  onChange={(value) => {
                    void changeRole(grant, value)
                  }}
                />
                <button
                  type="button"
                  className="link-button danger withdraw"
                  onClick={() => void withdraw(grant)}
                >
                  撤销
                </button>
              </li>
            ))}
          </ul>
        ) : null}
        {rowCount === 0 ? <p className="hint member-none">没有匹配的协作者。</p> : null}
        {pages > 1 && !clamping ? (
          <nav className="pager" aria-label="分页">
            <span className="pager-count">
              共 {rowCount} 人，第 {range.start}–{range.end} 人
            </span>
            {page === 1 ? (
              <span className="is-off">上一页</span>
            ) : (
              <a
                className="pager-step"
                href={pagerHref(page - 1)}
                onClick={(event) => {
                  event.preventDefault()
                  goToPage(page - 1)
                }}
              >
                上一页
              </a>
            )}
            {Array.from({ length: pages }, (_, index) => index + 1).map((number) =>
              number === page ? (
                <span key={number} className="is-current" aria-current="page">
                  {number}
                </span>
              ) : (
                <a
                  key={number}
                  className="pager-page"
                  href={pagerHref(number)}
                  onClick={(event) => {
                    event.preventDefault()
                    goToPage(number)
                  }}
                >
                  {number}
                </a>
              ),
            )}
            {page === pages ? (
              <span className="is-off">下一页</span>
            ) : (
              <a
                className="pager-step"
                href={pagerHref(page + 1)}
                onClick={(event) => {
                  event.preventDefault()
                  goToPage(page + 1)
                }}
              >
                下一页
              </a>
            )}
          </nav>
        ) : null}
      </section>

      {requests.length > 0 ? (
        <section className="settings-block">
          <h3>
            待处理的申请 <span className="count">{requests.length}</span>
          </h3>
          <ul className="grants is-requests">
            {requests.map((request) => (
              <li key={request.handle}>
                <span className="avatar" aria-hidden="true">
                  {avatarInitial(request.handle)}
                </span>
                <div className="grant-what">
                  <span className="grant-who">{request.handle}</span>
                  <span className="grant-ask">申请「{rungLabel(request.wanted_role)}」</span>
                </div>
                <span className="member-type">个人</span>
                <RungMenu
                  value={wanted[request.handle] ?? 'invoke'}
                  options={offered}
                  label={`批准 ${request.handle} 的档位`}
                  onChange={(value) => setWanted(request.handle, value)}
                />
                <div className="row-acts">
                  <button type="button" className="button" onClick={() => void approve(request)}>
                    批准
                  </button>
                  <button
                    type="button"
                    className="button is-quiet"
                    onClick={() => void deny(request)}
                  >
                    拒绝
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </section>
  )
}
