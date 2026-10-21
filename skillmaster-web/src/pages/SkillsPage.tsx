import { useEffect, useRef, useState } from 'react'
import { addressOrPath, go, param, replaceAddress } from '../address'
import { codeMessage } from '../api/errors'
import { listSkills, restoreSkill } from '../api/skills'
import { loginPath } from '../login'
import { formatTime } from '../time'
import type { AuthoredSkillSummary } from '../api/types'
import { useSession } from '../store'
import { versionLabel, versionSuffix } from '../version'
import FormBanner from '../components/FormBanner'
import CommandMenu from '../components/CommandMenu'
import type { CommandMenuHandle, CommandMenuItem } from '../components/CommandMenu'
import ImportDialog from '../components/skill/ImportDialog'
import NewSkillDialog from '../components/skill/NewSkillDialog'

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
 *
 * **What changed in the port.** Vue's `watch`es became React's idioms, and where they had to become an
 * effect it is guarded by a "first run" ref — Vue's `watch` does not fire on mount and React's effect
 * does, so an unguarded one would wipe a `?p=3` the moment the page opened. **The search and sort do
 * not need one**: their reset happens in the input's own handler, in the same update as the value, so
 * no frame pairs a new term with the old page. Every place a state write also writes the address
 * passes its values explicitly: a React setter has not taken effect by the time the address is
 * computed.
 */
const PAGE_SIZE = 10

type Sheet = 'none' | 'import' | 'create'

export default function SkillsPage() {
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [skills, setSkills] = useState<AuthoredSkillSummary[]>([])
  const [banner, setBanner] = useState<string | null>(null)
  const [restoring, setRestoring] = useState<string | null>(null)

  const { account } = useSession()
  /** Which sheet the page's one menu opened; the two entries are one action's two sources. */
  const [sheet, setSheet] = useState<Sheet>('none')
  /** The page's 「新建 skill ▾」 — where focus goes when a sheet it opened closes. */
  const createMenu = useRef<CommandMenuHandle | null>(null)
  const createItems: CommandMenuItem[] = [
    { label: '在线编辑', onSelect: () => setSheet('create') },
    { label: '导入本地 skill', onSelect: () => setSheet('import') },
  ]

  /**
   * Closing either sheet returns focus to 「新建 skill ▾」, the control that opened it (design §我的
   * skill: 「焦点回到打开它的那颗」). The frame lets the sheet leave the DOM first.
   */
  function closeSheet(): void {
    setSheet('none')
    requestAnimationFrame(() => createMenu.current?.focusTrigger())
  }

  /**
   * Which list is on screen. Not state: the two tabs are real links, so the only way this changes is
   * a full navigation — which re-runs the module and reads the address again.
   */
  const show: 'live' | 'deleted' = param('show') === 'deleted' ? 'deleted' : 'live'
  const [query, setQuery] = useState(() => param('q') ?? '')
  const [sort, setSort] = useState<'recent' | 'name'>(() =>
    param('sort') === 'name' ? 'name' : 'recent',
  )
  const [requestedPage, setPage] = useState(() =>
    Math.max(1, Number.parseInt(param('p') ?? '1', 10) || 1),
  )

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const result = await listSkills(show === 'deleted' ? 'deleted' : undefined)
      if (cancelled) {
        return
      }
      if (!result.ok) {
        if (result.code === 'unauthenticated') {
          // This page has no anonymous version — "your skills" is not a question with an answer for
          // nobody — so a session that is missing or has expired ends up at the sign-in form, which
          // knows how to come back here.
          go(loginPath())
          return
        }
        // The design has one sentence for this, written into the page; a banner would say it twice.
        setState('failed')
        return
      }
      setSkills(result.data.skills)
      setState('ready')
    })()
    return () => {
      cancelled = true
    }
  }, [show])

  function hrefOf(skill: AuthoredSkillSummary): string {
    return `/skills/${encodeURIComponent(skill.namespace)}/${encodeURIComponent(skill.name)}`
  }

  /** A skill's live state, in one phrase. Null `current` is a real state, not a missing value. */
  function stateOf(skill: AuthoredSkillSummary): string {
    return skill.current === null ? '未上线' : `已上线 @${versionLabel(versionSuffix(skill.current))}`
  }

  /** The term matches the name and the title, case-insensitively — the design's own rule. */
  const term = query.trim().toLowerCase()
  const matched =
    term === ''
      ? [...skills]
      : skills.filter((skill) => `${skill.name} ${skill.title}`.toLowerCase().includes(term))
  matched.sort((a, b) => {
    if (sort === 'name') {
      return a.name < b.name ? -1 : a.name > b.name ? 1 : 0
    }
    // "最近更新", which is the projected time: the live version's submission, or the newest draft's.
    // A row with neither (every version discarded) sorts last rather than first.
    return (b.updated_at ?? '').localeCompare(a.updated_at ?? '')
  })

  const pages = Math.max(1, Math.ceil(matched.length / PAGE_SIZE))

  // **The page on screen is the requested one, capped at the last page.** Vue clamped in a pre-flush
  // watcher, so no render ever paired a stale page with a shorter list; deriving it here does the
  // same for the render, and the effect below still writes the correction back to the address.
  const page = Math.min(requestedPage, pages)

  const shown = matched.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE)

  const start = (page - 1) * PAGE_SIZE
  const rangeLabel =
    shown.length === 0
      ? `共 ${matched.length} 个`
      : `共 ${matched.length} 个，第 ${start + 1}–${start + shown.length} 个`

  /** The pages worth showing: the ends, the one you are on, and its neighbours — one gap for a run. */
  const pageNumbers: Array<number | null> = []
  for (let number = 1; number <= pages; number += 1) {
    if (number === 1 || number === pages || Math.abs(number - page) <= 1) {
      pageNumbers.push(number)
    } else if (pageNumbers[pageNumbers.length - 1] !== null) {
      pageNumbers.push(null)
    }
  }

  /** The list's own address, so a filtered, sorted, paged list can be linked to. */
  function addressOf(
    pageNumber: number = page,
    /** A value being written in the same tick — the search box and the sort picker. */
    overrides: { query?: string; sort?: string } = {},
  ): string {
    const currentQuery = overrides.query ?? query
    const currentSort = overrides.sort ?? sort
    const next = new URLSearchParams()
    if (currentQuery.trim() !== '') {
      next.set('q', currentQuery.trim())
    }
    if (currentSort !== 'recent') {
      next.set('sort', currentSort)
    }
    if (pageNumber !== 1) {
      next.set('p', String(pageNumber))
    }
    if (show === 'deleted') {
      next.set('show', 'deleted')
    }
    return addressOrPath(next)
  }

  function writeAddress(
    pageNumber: number = page,
    overrides: { query?: string; sort?: string } = {},
  ): void {
    replaceAddress(addressOf(pageNumber, overrides))
  }

  /**
   * A tab's own address, carrying the search and the sort but not the page.
   *
   * The page number is dropped on purpose: switching lists starts at the top of the one you switched
   * to, where a carried-over second page would land on nothing.
   */
  function addressOfRaw(target: 'live' | 'deleted'): string {
    const next = new URLSearchParams()
    if (query.trim() !== '') {
      next.set('q', query.trim())
    }
    if (sort !== 'recent') {
      next.set('sort', sort)
    }
    if (target === 'deleted') {
      next.set('show', 'deleted')
    }
    return addressOrPath(next)
  }

  /**
   * A page number past the end lands on the last page rather than on a blank list (design's own
   * clamp). An effect rather than a computed: the page is also in the address, so the correction has
   * to be written back — the same reason every other control here writes it.
   */
  const firstRun = useRef(true)
  useEffect(() => {
    if (firstRun.current) {
      firstRun.current = false
      return
    }
    if (requestedPage > pages) {
      setPage(pages)
      writeAddress(pages)
    }
    // **`skills` is a dependency, not just `pages`.** Vue watched `[matched, pages]`, and `matched` is
    // a fresh array whenever the list loads — so `?p=4` against a list that still fits one page was
    // normalised back to page 1. `pages` and `requestedPage` are both unchanged in that case (1 and 4
    // before the load, 1 and 4 after), so without the list itself the address kept the stale `p=4`.
    // A failed load leaves `skills` as it was, so it still writes nothing — Vue's did not either.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pages, requestedPage, skills])

  function goToPage(number: number): void {
    setPage(number)
    writeAddress(number)
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
    setRestoring(skill.name)
    const result = await restoreSkill(skill.namespace, skill.name)
    setRestoring(null)
    if (!result.ok) {
      setBanner(codeMessage(result.code, result.message))
      return
    }
    go(address)
  }

  return (
    <>
      <section className="page is-list">
        <header className="page-head">
          <h1>我的 skill</h1>
          {/* **The empty list is its own state in the design** (`empty`, beside `ready`): the count,
              search and sort are not drawn there — "搜索与排序在空态里没有意义" — and 新建 skill ▾ is,
              because it is the only next step. A filtered list that matched nothing is still `ready`:
              the controls stay, so the filter can be cleared. */}
          {state === 'ready' && skills.length > 0 ? (
            <span className="count">
              {`${matched.length} 个${show === 'deleted' ? '已删除' : ''}`}
            </span>
          ) : null}
          {/* **The head's far end is one group** (design `.page-actions`): search and sort, then the
              one action. Wrapping them keeps 新建 skill ▾ at the right edge in the empty state too,
              where the toolbar is not drawn — a button that jumps to the title is a different header.
              Both controls are in the address, like everything else here, so a filtered list can be
              linked to. */}
          <div className="page-actions">
            {state === 'ready' && skills.length > 0 ? (
              <div className="toolbar">
                <label className="search">
                  <span className="visually-hidden">搜索</span>
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
                    placeholder="搜索名字或说明"
                    onChange={(event) => {
                      // **The page falls back to the first in the same update** (design: 筛完落回第一页).
                      // Vue's watcher ran before the render, so no frame ever showed page 3 of a
                      // two-page result; resetting here keeps that true.
                      const value = event.target.value
                      setQuery(value)
                      setPage(1)
                      writeAddress(1, { query: value })
                    }}
                  />
                </label>
                <label className="sort">
                  <span className="visually-hidden">排序</span>
                  <select
                    className="control"
                    value={sort}
                    onChange={(event) => {
                      const value = event.target.value === 'name' ? 'name' : 'recent'
                      setSort(value)
                      setPage(1)
                      writeAddress(1, { sort: value })
                    }}
                  >
                    <option value="recent">最近更新</option>
                    <option value="name">名称</option>
                  </select>
                </label>
              </div>
            ) : null}
            {/* **One action, two sources** (v9): 在线编辑 fills a form and lands a draft; 导入本地 skill
                brings a zip in. Drawn whenever the list is ready — including the empty state, where it
                is the only next step, and both lists, because this is the page header and the design
                gates it on `ready` alone (the deleted list is the same page). */}
            {state === 'ready' ? (
              <CommandMenu
                controlRef={createMenu}
                groups={[createItems]}
                menuLabel="新建 skill"
                resize="reposition"
              >
                {({ open, toggle }) => (
                  <button
                    type="button"
                    className="button menu-button"
                    aria-haspopup="menu"
                    aria-expanded={open ? 'true' : 'false'}
                    onClick={toggle}
                  >
                    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <path
                        d="M12 5v14M5 12h14"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                      />
                    </svg>
                    新建 skill
                    <svg className="button-caret" viewBox="0 0 12 12" aria-hidden="true">
                      <path
                        d="M3 4.8 6 7.8 9 4.8"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.4"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      />
                    </svg>
                  </button>
                )}
              </CommandMenu>
            ) : null}
          </div>
        </header>

        {/* **Two lists, two addresses.** 「已删除」 is not a filter on the list above it: every row in
            it can do exactly one thing — come back — and no row in the other list can do that. */}
        {/* **Not drawn on an empty live list** — the design's `empty` state hides the whole switching
            row, and 「新建 skill ▾」 is the only next step then. Kept while looking at 已删除, because
            hiding it there would leave no way back to 在用; the design does not cover that pairing, so
            the safer of the two readings is taken and recorded in the v9 TD (接口 8). */}
        {state === 'ready' && (skills.length > 0 || show === 'deleted') ? (
          <nav className="skill-tabs list-tabs" aria-label="我的 skill 的两份列表">
            <a
              className={show === 'live' ? 'skill-tab is-current' : 'skill-tab'}
              aria-current={show === 'live' ? 'page' : undefined}
              href={addressOfRaw('live')}
            >
              在用
            </a>
            <a
              className={show === 'deleted' ? 'skill-tab is-current' : 'skill-tab'}
              aria-current={show === 'deleted' ? 'page' : undefined}
              href={addressOfRaw('deleted')}
            >
              已删除
            </a>
          </nav>
        ) : null}

        <FormBanner message={banner} />

        {state === 'loading' ? (
          <p className="loading">加载中…</p>
        ) : state === 'failed' ? (
          <div className="banner">读不到你的 skill 列表，稍后再试一次。</div>
        ) : (
          <div className="list-body">
            {show === 'live' ? (
              <ul className="skills">
                {shown.map((skill) => (
                  <li key={skill.name} className="skill">
                    <div className="skill-line">
                      <a className="skill-name" href={hrefOf(skill)}>
                        {skill.name}
                      </a>
                      <span className={skill.current === null ? 'chip is-plain' : 'chip is-live'}>
                        <i className="dot" />
                        <span className="chip-text">{stateOf(skill)}</span>
                      </span>
                      {/* **The whole skill is offline, not this version.** A second mark, beside the
                          version state, because the two say different things: the skill is listed and
                          manageable, but its address does not serve. */}
                      {skill.offline_at != null ? (
                        <span className="chip is-offline">
                          <i className="dot" />
                          <span className="chip-text">已下线</span>
                        </span>
                      ) : null}
                      <span className="spacer" />
                    </div>
                    <div className="skill-line skill-sub">
                      <p className="skill-title">{skill.title}</p>
                      <span className="spacer" />
                      {skill.updated_at !== null ? (
                        <span className="skill-updated">
                          {`更新于 ${formatTime(skill.updated_at)}`}
                        </span>
                      ) : null}
                    </div>
                  </li>
                ))}
              </ul>
            ) : (
              /* One deleted skill. The name is text, not a link: its address opens nothing now, and a
                 name that looks like a link and goes nowhere is worse than plain text. */
              <ul className="skills">
                {shown.map((skill) => (
                  <li key={skill.name} className="skill">
                    <div className="skill-line">
                      <span className="skill-name">{skill.name}</span>
                      <span className="chip is-plain">
                        <i className="dot" />
                        <span className="chip-text">已删除</span>
                      </span>
                      <span className="spacer" />
                      <button
                        type="button"
                        className="link-button restore"
                        disabled={restoring === skill.name}
                        onClick={() => {
                          void restore(skill)
                        }}
                      >
                        恢复
                      </button>
                    </div>
                    <div className="skill-line skill-sub">
                      <p className="skill-title">{skill.title}</p>
                      <span className="spacer" />
                      {/* When it went is the only date this row has, and it is the one a person is
                          asking about: 「更新于」 on a skill nobody can open would be a fact about a
                          page that no longer exists. */}
                      {skill.deleted_at !== null ? (
                        <span className="skill-updated">
                          {`删除于 ${formatTime(skill.deleted_at)}`}
                        </span>
                      ) : null}
                    </div>
                  </li>
                ))}
              </ul>
            )}

            {show === 'live' && skills.length === 0 ? (
              <p className="empty">
                还没有 skill。新建一个，或运行 <code>skillmaster skill submit</code>。
              </p>
            ) : show === 'live' && matched.length === 0 ? (
              <p className="empty">没有匹配的 skill。换个词试试，或者清空搜索。</p>
            ) : show === 'deleted' && matched.length === 0 ? (
              <p className="empty">没有删掉的 skill。</p>
            ) : null}

            {/* Pages only once there is more than one — a lone 「1」 under a list that fits is a
                control that does nothing, and the count is already in the heading. */}
            {matched.length > PAGE_SIZE ? (
              <nav className="pager" aria-label="分页">
                <span className="pager-count">{rangeLabel}</span>
                {page === 1 ? (
                  <span className="is-off">上一页</span>
                ) : (
                  <a
                    className="pager-step"
                    href={addressOf(page - 1)}
                    onClick={(event) => {
                      event.preventDefault()
                      goToPage(page - 1)
                    }}
                  >
                    上一页
                  </a>
                )}
                {pageNumbers.map((number, index) =>
                  number === null ? (
                    <span key={`gap-${index}`} className="is-gap">
                      …
                    </span>
                  ) : number === page ? (
                    <span key={`page-${number}`} className="is-current" aria-current="page">
                      {number}
                    </span>
                  ) : (
                    <a
                      key={`page-${number}`}
                      className="pager-page"
                      href={addressOf(number)}
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
                    href={addressOf(page + 1)}
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
          </div>
        )}
      </section>

      <ImportDialog
        open={sheet === 'import'}
        mode="skill"
        namespace=""
        name=""
        rung="owner"
        onClose={closeSheet}
        onNavigate={go}
      />
      <NewSkillDialog
        open={sheet === 'create'}
        namespace={account?.namespace ?? ''}
        onClose={closeSheet}
        onNavigate={go}
      />
    </>
  )
}
