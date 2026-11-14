import { useEffect, useRef, useState } from 'react'
import { addressFor, go, query as pageQuery, replaceAddress } from '../address'
import { loginPath } from '../login'
import { failureMessage } from '../api/errors'
import { listCategories, listDiscover, listRecommended, joinSkill, leaveSkill } from '../api/library'
import type { SkillCard } from '../api/types'
import Pager from '../components/Pager'
import ResultCard from '../components/ResultCard'
import SearchBox from '../components/SearchBox'

/**
 * 发现: **a search row and a bounded 「为你推荐」 stream**, never the whole public catalogue.
 *
 * The opening screen is the search box, the category picker and one stream of public skills that are
 * live, not deleted, and not already in the caller's library — newest first. It is a rule that can be
 * explained, not a recommendation algorithm: the product has no usage or rating signal today.
 *
 * The stream is batched at six and asks for the next batch when the sentinel scrolls into view.
 * **There is no "load more" button**; on a screen too tall to fill, the sentinel is in view at once,
 * which is exactly the "fill the first screen" rule (prd.md §为你推荐).
 *
 * Once a search or a category is chosen, the header keeps its row and only the right-hand group
 * changes. **A keyword is a search by meaning** (v19): at most ten rows, no pager, Enter to submit.
 * **A category alone is the literal search**: six a page with the numbered pager. The recommendation
 * stream is not redrawn either way.
 */
const PAGE_SIZE = 6
/**
 * How many rows a search by meaning may show. **The same 10 the server and the CLI use** — the
 * three are separate constants (`SemanticSearchUseCase.DEFAULT_LIMIT`, `searchPageSize` in the
 * CLI, `SEMANTIC_LIMIT` in the design), so this one keeps the page from drawing more rows than
 * the design does if the server's own default ever moves.
 */
const SEMANTIC_LIMIT = 10
/** One batch of the recommendation stream. */
const PICK_BATCH = 6

type LoadState = 'loading' | 'ready' | 'failed'

/** A comma-joined key for a dependency: the arrays are equal when their selections are. */
const keyOf = (values: string[]) => values.join('\u0000')

export default function DiscoverPage() {
  const initial = useRef(pageQuery()).current
  const [query, setQuery] = useState(() => initial.get('q') ?? '')
  /* **The address's category, read-only**: the only way in is a result row's category chip. */
  const tags = useRef(initial.getAll('tag')).current
  /* v66: attributes are the second filter — `dimension:value`, repeated, ANDed by the server. They
     arrive the same way a category does: an address written by a result row's attribute chip. */
  const labels = useRef(initial.getAll('label')).current
  const [page, setPage] = useState(() => Math.max(1, Number.parseInt(initial.get('p') ?? '1', 10) || 1))
  /**
   * **The results box searches on Enter, not on every keystroke** (v19, 2026-10-25). A search is now
   * a call to the embedding provider: filtering as it is typed would buy one paid call per character
   * and let the answers overtake each other. The box holds a draft; the address holds the term that
   * was actually searched, so the two never disagree about what is on screen.
   */
  const [draftQuery, setDraftQuery] = useState(query)
  useEffect(() => {
    setDraftQuery(query)
  }, [query])

  const landing = query.trim() === '' && tags.length === 0 && labels.length === 0
  const hasQuery = !landing
  /**
   * **A keyword is a search by meaning; a category alone is a browse** (v19). The two have different
   * shapes on the wire — ten rows and no pages, or six rows a page with a total — so this flag
   * decides which is asked for, and the response decides whether a pager can be drawn.
   */
  const semantic = query.trim() !== ''

  /* ------------------------------------------------------------------ results */

  const [results, setResults] = useState<SkillCard[]>([])
  /** Present only on the paged shape; its absence is what says "this answer has no page 2". */
  const [total, setTotal] = useState<number | null>(null)
  /** Why this is not the answer that was asked for; null on an ordinary answer. */
  const [note, setNote] = useState<string | null>(null)
  const [resultState, setResultState] = useState<LoadState>('loading')
  /** `/web/categories`, for the result head's shelf names; null until the read lands. */
  const [categoryNames, setCategoryNames] = useState<Record<string, string> | null>(null)

  const tagsKey = keyOf(tags)
  const labelsKey = keyOf(labels)
  /* The result head names a shelf from the vocabulary, once, and only when a shelf filter is in
     play. A failed read keeps the key visible rather than inventing a name. */
  useEffect(() => {
    if (landing || tags.length === 0 || categoryNames !== null) {
      return
    }
    let cancelled = false
    void (async () => {
      const result = await listCategories()
      if (cancelled || !result.ok) {
        return
      }
      const names: Record<string, string> = {}
      for (const row of result.data.categories ?? []) {
        names[row.key] = row.name
      }
      setCategoryNames(names)
    })()
    return () => {
      cancelled = true
    }
    // `tagsKey` stands in for the array; `categoryNames` stops the loop after one read.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [landing, tagsKey, categoryNames])
  useEffect(() => {
    if (landing) {
      return
    }
    let cancelled = false
    setResultState('loading')
    void (async () => {
      /* `p` goes out whenever the address names a page, and the server ignores it on the semantic
         path: a keyword answer can be the second page of a degraded search, and a deep link has to
         keep meaning what it says before any response has come back. */
      const result = await listDiscover({ q: query, tag: tags, label: labels, p: page })
      if (cancelled) {
        return
      }
      if (!result.ok) {
        if (result.code === 'unauthenticated') {
          go(loginPath())
          return
        }
        setResultState('failed')
        return
      }
      setResults((result.data.skills ?? []).slice(0, SEMANTIC_LIMIT))
      setTotal(result.data.total ?? null)
      setNote(result.data.note ?? null)
      setResultState('ready')
      /* The transport echoes the page it was asked for and cuts by offset, so a page past the end
         is empty. The design lands on the last page: correct the address and read that one. A
         semantic answer has no pages at all, so there is nothing to correct. */
      if (result.data.total !== undefined) {
        const pages = Math.max(1, Math.ceil(result.data.total / PAGE_SIZE))
        if (page > pages) {
          setPage(pages)
          writeAddress({ page: pages })
        }
      }
    })()
    return () => {
      cancelled = true
    }
    // `tagsKey` / `labelsKey` stand in for the arrays: the same selections are the same question.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [landing, query, tagsKey, labelsKey, page, semantic])

  /* -------------------------------------------------------- 为你推荐 (the stream) */

  const [picks, setPicks] = useState<SkillCard[]>([])
  const [picksState, setPicksState] = useState<LoadState>('loading')
  const [cursor, setCursor] = useState<string | null>(null)
  const [moreLoading, setMoreLoading] = useState(false)
  const [moreFailed, setMoreFailed] = useState(false)
  const askedRef = useRef(false)
  // **The observer's own memory, not state.** After a failure the effect re-runs (an in-flight
  // flag changed), and a fresh observer delivers an initial "intersecting" callback immediately.
  // Without this ref that callback would retry at once, and every failure would start another
  // request — a storm. The ref says "one failure stands until the sentinel leaves the viewport".
  const moreFailedRef = useRef(false)
  const moreInFlight = useRef(false)

  const loadMore = async (from: string | null) => {
    if (from === null || moreInFlight.current) {
      return
    }
    moreInFlight.current = true
    setMoreLoading(true)
    const result = await listRecommended(from, PICK_BATCH)
    moreInFlight.current = false
    setMoreLoading(false)
    if (!result.ok) {
      if (result.code === 'unauthenticated') {
        go(loginPath())
        return
      }
      // A later batch failing must not take the batches already on screen with it: the cards a
      // person is reading stay, and the sentinel says why nothing more arrived. Scrolling the
      // sentinel out of view and back arms one retry (see the observer).
      moreFailedRef.current = true
      setMoreFailed(true)
      return
    }
    moreFailedRef.current = false
    setMoreFailed(false)
    setPicks((list) => list.concat(result.data.skills ?? []))
    setCursor(result.data.next_cursor ?? null)
  }

  useEffect(() => {
    if (!landing || askedRef.current) {
      return
    }
    askedRef.current = true
    let cancelled = false
    void (async () => {
      const result = await listRecommended(undefined, PICK_BATCH)
      if (cancelled) {
        return
      }
      if (!result.ok) {
        if (result.code === 'unauthenticated') {
          go(loginPath())
          return
        }
        setPicksState('failed')
        return
      }
      setPicks(result.data.skills ?? [])
      setCursor(result.data.next_cursor ?? null)
      setPicksState('ready')
    })()
    return () => {
      cancelled = true
    }
    // Once per page: the stream is not re-read when a search is cleared.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [landing])

  const moreRef = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    if (!landing || picksState !== 'ready' || cursor === null || moreLoading) {
      return
    }
    const node = moreRef.current
    if (node === null || typeof IntersectionObserver === 'undefined') {
      return
    }
    /* The sentinel is the "more" row itself. It fires at once when the first six do not fill the
       screen — that is the fill rule, with no artificial delay. */
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            if (!moreFailedRef.current) {
              void loadMore(cursor)
            }
          } else if (moreFailedRef.current) {
            // Leaving the sentinel is the retry gesture: the next entry into view tries once more.
            moreFailedRef.current = false
            setMoreFailed(false)
          }
        }
      },
      { rootMargin: '120px' },
    )
    observer.observe(node)
    return () => observer.disconnect()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [landing, picksState, cursor, moreLoading, picks.length])

  /* ------------------------------------------------------------------- address */

  const writeAddress = (next: {
    query?: string
    tags?: string[]
    labels?: string[]
    page?: number
  }) => {
    const q = next.query ?? query
    const chosen = next.tags ?? tags
    const chosenLabels = next.labels ?? labels
    const p = next.page ?? page
    const params = new URLSearchParams()
    if (q.trim() !== '') {
      params.set('q', q)
    }
    chosen.forEach((tag) => params.append('tag', tag))
    chosenLabels.forEach((label) => params.append('label', label))
    if (p > 1) {
      params.set('p', String(p))
    }
    replaceAddress(addressFor('/discover', params))
  }

  const search = (next: string, requestedPage = 1) => {
    setQuery(next)
    setPage(requestedPage)
    writeAddress({ query: next, page: requestedPage })
  }

  /** The address for one result page: the same spelling `writeAddress` uses. */
  const hrefParams = (target: number): URLSearchParams => {
    const params = new URLSearchParams()
    if (query.trim() !== '') {
      params.set('q', query)
    }
    tags.forEach((tag) => params.append('tag', tag))
    labels.forEach((label) => params.append('label', label))
    if (target > 1) {
      params.set('p', String(target))
    }
    return params
  }

  const goToPage = (number: number) => {
    setPage(number)
    writeAddress({ page: number })
    window.scrollTo(0, 0)
  }

  /* -------------------------------------------------------------- the join states */

  const [busy, setBusy] = useState<string | null>(null)
  /** Keyed by address and carrying the reason: the card shows the sentence, not a generic one. */
  const [failed, setFailed] = useState<{ key: string; message: string } | null>(null)
  /** The cards joined on this screen: pressing the filled bookmark again takes that join back. */
  const [joined, setJoined] = useState<string[]>([])

  /** A skill is identified by its address, not by its name alone: names are unique per namespace. */
  const addressKey = (skill: SkillCard): string => `${skill.namespace}/${skill.name}`

  const join = async (skill: SkillCard): Promise<void> => {
    const key = addressKey(skill)
    setFailed(null)
    setBusy(key)
    const result = await joinSkill(skill.namespace, skill.name)
    setBusy(null)
    if (!result.ok) {
      setFailed({
        key,
        message: failureMessage(result),
      })
      return
    }
    setJoined((list) => (list.includes(key) ? list : list.concat(key)))
  }

  const unjoin = async (skill: SkillCard): Promise<void> => {
    const key = addressKey(skill)
    setFailed(null)
    setBusy(key)
    const result = await leaveSkill(skill.namespace, skill.name)
    setBusy(null)
    if (!result.ok) {
      // The bookmark must not look like it worked: the card's own failed state says it did not,
      // whether the cause was the network, an expired session or a reference already gone.
      setFailed({
        key,
        message: failureMessage(result),
      })
      return
    }
    setJoined((list) => list.filter((item) => item !== key))
  }

  /** A joined card keeps its place and reads as 「已在库 · 我加入的」; it is not taken out of the stream. */
  const withSource = (skill: SkillCard): SkillCard =>
    joined.includes(addressKey(skill)) ? { ...skill, source: 'joined' } : skill
  const joinState = (skill: SkillCard) => {
    const key = addressKey(skill)
    return busy === key ? 'busy' : failed?.key === key ? 'failed' : 'idle'
  }
  const removable = (skill: SkillCard) =>
    joined.includes(addressKey(skill)) ? () => void unjoin(skill) : undefined

  const card = (skill: SkillCard) => {
    const shown = withSource(skill)
    return (
      <ResultCard
        key={addressKey(skill)}
        skill={shown}
        joinState={joinState(skill)}
        joinError={failed?.key === addressKey(skill) ? failed.message : undefined}
        onJoin={() => void join(skill)}
        onRemove={removable(skill)}
      />
    )
  }

  /* **The results screen gets its own head** (v66): without it, a searched list reads like
     为你推荐 and nobody knows why these rows are here. The title says which question this is, the
     note says what was filtered, and neither reports a count — the public corpus size is internal
     (PRD A15). A filter word with no result is still printed, so a broken address stays readable. */
  const filterName = (key: string): string => {
    /* **The vocabulary, not the current page** (v66): a filter that matched nothing would print the
       internal key, and a shelf's name must not change with which rows happened to come back. Until
       the read lands, the key is shown as itself rather than as a wrong name. */
    if (categoryNames === null) return key
    return categoryNames[key] ?? '已删除的分类'
  }
  const labelName = (entry: string): string => {
    for (const skill of results) {
      const label = (skill.labels ?? []).find((item) => item.key + ':' + item.value === entry)
      if (label !== undefined) return label.label
    }
    return entry
  }
  const filterTerms = [...tags.map(filterName), ...labels.map(labelName)]
  const resultTitle = query.trim() !== '' ? '搜索结果' : '筛选结果'
  const resultNote =
    query.trim() !== ''
      ? filterTerms.length > 0
        ? filterTerms.join(' · ')
        : '按相关度排序'
      : filterTerms.join(' · ')

  return (
    <section className="page" id="page-discover">
      <header className={'page-head discover-head' + (landing ? ' is-landing' : '')}>
        {/* The navigation already says 发现; the visible title would only repeat it. */}
        <h1 className="visually-hidden">发现</h1>
        {/* **Search is the header's only control** (2026-11-05): the category row is gone — the
            category on a result row is the way to browse it. Enter submits; there is no button.
            **No result count** (v66, 2026-11-14 用户定): the line holds the result state on the
            left and 清除 on the right; how many the public corpus has is an internal signal.

            **One form, both states (2026-11-07).** Landing and results used to be two forms with two
            drafts: submitting swapped the subtree, so the box was a new node and the caret stayed
            behind; while an answer was in flight the box was hidden outright. One node, one draft;
            the header's `is-landing` class stays what it was. */}
        <div className="discover-search">
          {hasQuery ? (
            <div className="discover-result-meta" id="discoverResultsHead">
              <h2 className="picks-title" id="discoverResultsTitle">
                {resultTitle}
              </h2>
              {resultNote !== '' ? (
                <span className="picks-note" id="discoverResultsNote">
                  {resultNote}
                </span>
              ) : null}
            </div>
          ) : null}
          <form
            onSubmit={(event) => {
              event.preventDefault()
              if (draftQuery.trim() === '') {
                /* **An empty submit on the results screen is 「stop searching」** — it goes back to
                   为你推荐, which is the only way back from inside the page. On the landing screen
                   there is nothing to stop, so it does nothing. */
                if (!landing) {
                  search('', 1)
                }
                return
              }
              search(draftQuery, 1)
            }}
          >
            <SearchBox
              id="discoverQuery"
              value={draftQuery}
              placeholder="搜索名字、描述或适用场景"
              label={landing ? '搜索 skill' : '搜索公开的 skill'}
              onChange={setDraftQuery}
            />
          </form>
          {hasQuery ? (
            <a
              className="discover-clear"
              href="/discover"
              onClick={(event) => {
                event.preventDefault()
                /* Clearing drops the filters held by the address, and this page reads them once
                   at mount (`tags` / `labels` are refs). A whole load is the one way to land on a
                   truly unfiltered 发现 — the same load `href` gives without JavaScript. */
                go('/discover')
              }}
            >
              清除
            </a>
          ) : null}
        </div>
      </header>


      {landing ? (
        <>
          {picksState === 'loading' ? <p className="loading">加载中…</p> : null}
          {picksState === 'failed' ? (
            <div className="banner">无法加载公开的 skill，请稍后重试。</div>
          ) : null}
          <div className="discover-home" hidden={picksState !== 'ready'} id="discoverLanding">
            <section className="picks" id="discoverPicks">
              <div className="picks-head">
                <h2 className="picks-title">为你推荐</h2>
                {/* **The subtitle carries no count** (2026-11-06): the interface has no total, and
                    the number is not what a reader is here to learn. The two clauses say what this
                    block gives you and what to do next. */}
                <p className="picks-note">可直接入库 · 输入你想完成的任务</p>
              </div>
              {picks.length > 0 ? (
                <>
                  <ul className="results">{picks.map(card)}</ul>
                  <div className="picks-more" id="discoverMore" ref={moreRef}>
                    {cursor === null ? (
                      <p className="picks-end" id="discoverEnd">
                        没有更多了。用上面的搜索，或点结果上的分类继续找。
                      </p>
                    ) : moreFailed ? (
                      <div className="banner">无法加载公开的 skill，请稍后重试。</div>
                    ) : moreLoading ? (
                      <p className="picks-loading" id="discoverLoading">
                        正在加载…
                      </p>
                    ) : null}
                  </div>
                </>
              ) : (
                <p className="empty" id="discoverPicksNone">
                  没有可推荐的了。用上面的搜索，或点结果上的分类继续找。
                </p>
              )}
            </section>
          </div>
        </>
      ) : (
        <>
          {resultState === 'loading' ? <p className="loading">加载中…</p> : null}
          {resultState === 'failed' ? (
            <div className="banner">无法加载公开的 skill，请稍后重试。</div>
          ) : null}
        <div className="list-body" data-when="ready" hidden={resultState !== 'ready'}>
          {/* **降级那一态要说出来**：结果是按字面找的，只是这一页本该按意思找。不说，人只会觉得
              "搜得不准"。这是一句说明，不是错误，所以用安静的那一行，不用 banner。 */}
          {resultState === 'ready' && results.length > 0 && note !== null ? (
            <p className="picks-note" id="discoverNote">
              {note}
            </p>
          ) : null}

          {resultState === 'ready' && results.length === 0 ? (
            <p className="empty" id="discoverNone">
              {/* 三种空、三种说法，分哪一种是看**回来的形状**，不是看人有没有打字——开关关掉时
                  也是打字的，而那时走的是字面。服务端给了话（note）就用它的话：只有它知道是分类
                  名字不在词表里，还是语义这一路根本不可用。 */}
              {note ??
                (total !== null
                  ? '没有找到匹配的 skill。换个词，或者换一个分类。'
                  : '没有足够相关的 skill。')}
            </p>
          ) : null}

          <ul className="results" id="discoverResults" hidden={results.length === 0}>
            {results.map(card)}
          </ul>

          {/* **只有带 total 的答案才有页码。** 按意思找给的是"最像的十条"，不是一个第几页的东西；
              降级回落的那一次是按字面找的，所以页码回来。 */}
          {resultState === 'ready' && total !== null ? (
            <Pager
              id="discoverPager"
              total={total}
              page={Math.min(page, Math.max(1, Math.ceil(total / PAGE_SIZE)))}
              pageSize={PAGE_SIZE}
              showTotal={false}
              hrefFor={(target) => addressFor('/discover', hrefParams(target))}
              onGo={goToPage}
            />
          ) : null}
        </div>
        </>
      )}
    </section>
  )
}
