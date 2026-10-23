import { useEffect, useRef, useState } from 'react'
import { addressFor, go, query as pageQuery, replaceAddress } from '../address'
import { loginPath } from '../login'
import { codeMessage } from '../api/errors'
import { listCategories, listDiscover, listRecommended, joinSkill, leaveSkill } from '../api/library'
import type { CategoryRow, SkillCard } from '../api/types'
import FilterChips from '../components/FilterChips'
import MenuMultiSelect from '../components/MenuMultiSelect'
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
 * changes: the public results, paged at six. The recommendation stream is not redrawn.
 */
const PAGE_SIZE = 6
/** One batch of the recommendation stream. */
const PICK_BATCH = 6

type LoadState = 'loading' | 'ready' | 'failed'

/** A comma-joined key for a dependency: the arrays are equal when their selections are. */
const keyOf = (values: string[]) => values.join('\u0000')

export default function DiscoverPage() {
  const initial = useRef(pageQuery()).current
  const [query, setQuery] = useState(() => initial.get('q') ?? '')
  const [tags, setTags] = useState(() => initial.getAll('tag'))
  const [page, setPage] = useState(() => Math.max(1, Number.parseInt(initial.get('p') ?? '1', 10) || 1))

  const landing = query.trim() === '' && tags.length === 0
  const hasQuery = !landing

  /* ------------------------------------------------------------------ results */

  const [results, setResults] = useState<SkillCard[]>([])
  /** `/web/discover` carries the total, so the design's numbered pager can be drawn. */
  const [total, setTotal] = useState(0)
  const [resultState, setResultState] = useState<LoadState>('loading')

  const tagsKey = keyOf(tags)
  useEffect(() => {
    if (landing) {
      return
    }
    let cancelled = false
    setResultState('loading')
    void (async () => {
      const result = await listDiscover({ q: query, tag: tags, p: page })
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
      setResults(result.data.skills ?? [])
      setTotal(result.data.total ?? 0)
      setResultState('ready')
      /* The transport echoes the page it was asked for and cuts by offset, so a page past the end
         is empty. The design lands on the last page: correct the address and read that one. */
      const pages = Math.max(1, Math.ceil((result.data.total ?? 0) / PAGE_SIZE))
      if (page > pages) {
        setPage(pages)
        writeAddress({ page: pages })
      }
    })()
    return () => {
      cancelled = true
    }
    // `tagsKey` stands in for the array: the same selections are the same question.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [landing, query, tagsKey, page])

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

  const writeAddress = (next: { query?: string; tags?: string[]; page?: number }) => {
    const q = next.query ?? query
    const chosen = next.tags ?? tags
    const p = next.page ?? page
    const params = new URLSearchParams()
    if (q.trim() !== '') {
      params.set('q', q)
    }
    chosen.forEach((tag) => params.append('tag', tag))
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

  const toggleTag = (key: string) => {
    const next = tags.includes(key) ? tags.filter((tag) => tag !== key) : tags.concat(key)
    setTags(next)
    setPage(1)
    writeAddress({ tags: next, page: 1 })
  }

  /** The address for one result page: the same spelling `writeAddress` uses. */
  const hrefParams = (target: number): URLSearchParams => {
    const params = new URLSearchParams()
    if (query.trim() !== '') {
      params.set('q', query)
    }
    tags.forEach((tag) => params.append('tag', tag))
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
        message: codeMessage(result.code, result.message, result.details),
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
        message: codeMessage(result.code, result.message, result.details),
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

  const [heroQuery, setHeroQuery] = useState('')

  /* The platform vocabulary, read rather than carried (technical-design.md §传输契约). A retired
     word never reaches the console; a stopped word still filters, because skills already on that
     shelf must stay findable. */
  const [categories, setCategories] = useState<CategoryRow[]>([])
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const result = await listCategories()
      if (!cancelled && result.ok && Array.isArray(result.data.categories)) {
        setCategories(result.data.categories)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])
  const categoryOptions = categories.map((category) => ({
    value: category.key,
    label: category.name,
    disabled: false,
  }))
  const categoryName = (key: string): string =>
    categories.find((category) => category.key === key)?.name ?? key

  return (
    <section className="page" id="page-discover">
      <header className={'page-head' + (landing ? ' is-landing' : '')}>
        {/* The navigation already says 发现; the visible title would only repeat it. */}
        <h1 className="visually-hidden">发现</h1>
        <span className="count" hidden={!hasQuery || resultState !== 'ready'}>
          {total + ' 个结果'}
        </span>
        <div className="page-actions">
          {landing ? (
            <form
              className="toolbar hero-toolbar"
              onSubmit={(event) => {
                event.preventDefault()
                if (heroQuery.trim() === '') {
                  return
                }
                search(heroQuery, 1)
              }}
            >
              <SearchBox
                id="discoverHeroQuery"
                value={heroQuery}
                placeholder="搜索名字、描述或适用场景"
                label="搜索 skill"
                onChange={setHeroQuery}
              />
              <button
                type="submit"
                className="button"
                id="discoverHeroSubmit"
                disabled={heroQuery.trim() === ''}
              >
                搜索
              </button>
              <label className="filter-select">
                <span className="visually-hidden">分类</span>
                <MenuMultiSelect
                  options={categoryOptions}
                  values={tags}
                  config={{
                    placeholder: '分类',
                    ariaLabel: '分类',
                    onToggle: toggleTag,
                    onClear: () => {
                      setTags([])
                      setPage(1)
                      writeAddress({ tags: [], page: 1 })
                    },
                  }}
                />
              </label>
            </form>
          ) : (
            <div className="toolbar" data-when="ready" hidden={resultState !== 'ready'}>
              <SearchBox
                id="discoverQuery"
                value={query}
                placeholder="搜索名字、描述或适用场景"
                label="搜索公开的 skill"
                onChange={(value) => search(value, 1)}
              />
              <label className="filter-select">
                <span className="visually-hidden">分类</span>
                <MenuMultiSelect
                  options={categoryOptions}
                  values={tags}
                  config={{
                    placeholder: '分类',
                    ariaLabel: '分类',
                    onToggle: toggleTag,
                    onClear: () => {
                      setTags([])
                      setPage(1)
                      writeAddress({ tags: [], page: 1 })
                    },
                  }}
                />
              </label>
            </div>
          )}
        </div>
      </header>

      <FilterChips
        id="discoverFilters"
        chips={tags.map((key) => ({ value: key, label: categoryName(key) }))}
        onRemove={toggleTag}
        onClear={() => {
          setTags([])
          setPage(1)
          writeAddress({ tags: [], page: 1 })
        }}
      />

      {landing ? (
        <>
          {picksState === 'loading' ? <p className="loading">加载中…</p> : null}
          {picksState === 'failed' ? (
            <div className="banner">无法加载公开的 skill，请稍后重试。</div>
          ) : null}
          <div className="discover-home" hidden={picksState !== 'ready'} id="discoverLanding">
            <section className="picks" id="discoverPicks">
              <h2 className="picks-title">为你推荐</h2>
              <p className="picks-note">
                这些是公开、有上线版本、你还没加入的 skill；最近更新的在前，下滑还有更多。
              </p>
              {picks.length > 0 ? (
                <>
                  <ul className="results">{picks.map(card)}</ul>
                  <div className="picks-more" id="discoverMore" ref={moreRef}>
                    {cursor === null ? (
                      <p className="picks-end" id="discoverEnd">
                        没有更多了。用上面的搜索或分类继续找。
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
                  没有可推荐的了。用上面的搜索或分类继续找。
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
          {resultState === 'ready' && results.length === 0 ? (
            <p className="empty" id="discoverNone">
              没有找到匹配的 skill。换个词，或者换一个分类。
            </p>
          ) : null}

          <ul className="results" id="discoverResults" hidden={results.length === 0}>
            {results.map(card)}
          </ul>

          {/* **`/web/discover` carries the total**, so this is the design's numbered pager in the
              same shape the library uses: both ends, the current page and its neighbours. */}
          <Pager
            id="discoverPager"
            total={total}
            page={Math.min(page, Math.max(1, Math.ceil(total / PAGE_SIZE)))}
            pageSize={PAGE_SIZE}
            hrefFor={(target) => addressFor('/discover', hrefParams(target))}
            onGo={goToPage}
          />
        </div>
        </>
      )}
    </section>
  )
}
