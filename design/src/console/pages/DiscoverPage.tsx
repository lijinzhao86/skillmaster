/**
 * 发现：**搜索框 + 为你推荐**，公域不整表列。
 *
 * **第一次进入是「搜索 + 一段可以往下走的推荐」。** 页头只有一行：左边一句「找到能直接用的
 * skill」，右边是搜索框、搜索按钮与公开分类多选；下面是**一段有界的「为你推荐」**——只挑公开、
 * 有上线版本、**还没在你库里**的 skill，按最近更新倒序。它是一条排序规则，不是一套推荐算法：
 * 今天没有用量、评分或审核信号，所以推荐的理由必须说得出来。算法以后可以换，这个位置与这张卡
 * 不用动。
 *
 * **它不是一个 landing page。** 居中大标题 + 大搜索框 + 单独一行分类入口那一版占了 330px，
 * 把推荐挤到第二屏以下；这一页的空白是留给卡片的。所以搜索区收进页头这一行——目录型站点的
 * 通行做法（HuggingFace、npm、ClawHub 的浏览页都是「名称在左、搜索在右」），导航里已经有
 * 「发现」这个名字，页头不必再报一次。
 *
 * **它不是「全部公开 skill」。** 公域会多到无法加载，所以这一页不画目录：推荐段是有界的，
 * 再往下要找就靠搜索或分类。这一步是 PRD #8 的口径，也是「推荐」与「列表」的区别。
 *
 * 一旦开始找，页头这一行不换，只换右边那一组：结果是**按回车才搜**（v19，2026-10-25 用户定）——
 * 一次搜索要问一次模型，边打边筛等于每敲一个字付一次钱、还互相抢答。所以结果行的框不画「搜索」
 * 按钮：回车就是它，输入法自带搜索键。
 *
 * 卡片本身在 `components/ResultCard.tsx`——推荐与结果用的是同一张，加入的三种状态也只写一份。
 */

import { useEffect, useRef, useState } from 'react'
import { replace } from '../address'
import FilterChips from '../components/FilterChips'
import MenuMultiSelect from '../components/MenuMultiSelect'
import Pager from '../components/Pager'
import ResultCard from '../components/ResultCard'
import SearchBox from '../components/SearchBox'
import {
  DISCOVER,
  categoryName,
  CATEGORIES,
  searchPublic,
} from '../fixtures/library'
import type { DiscoverSkill, Source } from '../fixtures/library'
import type { DemoPageProps } from './types'

const PAGE_SIZE = 6
/**
 * **有 q 时是「按意思找」，返回最像的十条，没有第二页**（v19）。
 * 语义排序没有页码：相似度不是一个页边界，翻到第二页只是「更不像的十条」。
 */
const SEMANTIC_LIMIT = 10
/** 推荐段一批几张。滚到底取下一批；一批六张是「一屏多半」的量，不会一次把一屏塞满。 */
const PICK_BATCH = 6

export default function DiscoverPage({ active, state, params }: DemoPageProps) {
  const query = params.get('q') ?? ''
  const tags = params.getAll('tag')
  const askedPage = parseInt(params.get('p') ?? '', 10)
  /* 公域只搜不列：没有关键词也没有分类时，检索本身返回空——不是页面把它藏起来。 */
  const results = searchPublic(query, tags)

  /* **两种找法，两种形状。** 有关键词就是按意思找：十条、不分页。只选了分类才是按字面找：
     六条一页、有页码。降级那一态（语义不可用）落回按字面找，所以它也有页码。 */
  const semantic = query.trim() !== ''
  const paginated = !semantic || state === 'degraded'

  let page = Number.isNaN(askedPage) || askedPage < 1 ? 1 : askedPage
  const pages = Math.max(1, Math.ceil(results.length / PAGE_SIZE))
  if (page > pages) page = pages
  const start = (page - 1) * PAGE_SIZE
  const shown = paginated ? results.slice(start, start + PAGE_SIZE) : results.slice(0, SEMANTIC_LIMIT)

  /* 加入的三种中间态。`?join=` 让它们各自有一屏可以发出去；点「加入」走的是同一套状态。 */
  const firstJoinable = results.find((skill) => skill.inLibrary === null)?.name ?? null
  const askedJoin = params.get('join')
  const [busy, setBusy] = useState<string | null>(
    askedJoin === 'busy' ? firstJoinable : null,
  )
  const [failed, setFailed] = useState<string | null>(
    askedJoin === 'failed' ? firstJoinable : null,
  )
  const [joined, setJoined] = useState<string[]>([])

  /** 地址只留这一页的参数：用户一动手，`state` 与 `join` 就该让位给真实查询。 */
  const writeAddress = (next: { query?: string; tags?: string[]; page?: number }) => {
    const copy = new URLSearchParams()
    copy.set('page', 'discover')
    const q = next.query ?? query
    const chosen = next.tags ?? tags
    const p = next.page ?? page
    if (q !== '') copy.set('q', q)
    chosen.forEach((tag) => copy.append('tag', tag))
    if (p > 1) copy.set('p', String(p))
    replace('?' + copy.toString())
  }

  const toggleTag = (id: string) => {
    const next = tags.includes(id) ? tags.filter((tag) => tag !== id) : tags.concat(id)
    writeAddress({ tags: next, page: 1 })
  }

  const join = (name: string) => {
    setFailed(null)
    setBusy(name)
    /* 演示件没有服务端：600ms 之后成功，好让「加入中」那一态真的被看见。 */
    window.setTimeout(() => {
      setBusy(null)
      setJoined((list) => list.concat(name))
    }, 600)
  }

  const sourceOf = (skill: DiscoverSkill): Source | null =>
    joined.includes(skill.name) ? 'joined' : skill.inLibrary

  const joinState = (name: string) => (busy === name ? 'busy' : failed === name ? 'failed' : 'idle')

  /** 刚在这一屏加入的那张可以撤销：卡片回到「加入」，位置不动。库里原有的条目不给这个动作。 */
  const unjoin = (name: string) => setJoined((list) => list.filter((item) => item !== name))
  const undoable = (name: string) => (joined.includes(name) ? () => unjoin(name) : undefined)

  /**
   * **「为你推荐」的排序规则只有一句：还没在你库里的、最近更新的在前。**
   * 库里已有的不推荐——推荐一个你手上有的东西，是把推荐位浪费掉。
   *
   * **刚加入的那一张不走。** 它在原位变成「已在库 · 我加入的」：抽走它会让下面所有卡片跳一格，
   * 人刚点完就找不到自己动过的那张了（2026-10-22 用户在演示里指出）。
   */
  const pool = DISCOVER
    .filter((skill) => skill.inLibrary === null)
    .sort((a, b) => b.updated.localeCompare(a.updated))

  /* **推荐是一条连续的流**：滑到底自动取下一批，没有「加载更多」按钮。批次状态是这一屏之内
     的事，不进地址：地址说的是「你在哪一页」，而不是「你已经滚了多远」。 */
  const [loadedCount, setLoadedCount] = useState(PICK_BATCH)
  const [loadingMore, setLoadingMore] = useState(false)
  const moreRef = useRef<HTMLDivElement>(null)
  /* 首屏补齐是不是已经做过：打开页面那一次不等，之后滑到底的批次才走演示的 600ms。 */
  const filledRef = useRef(false)
  const picks = pool.slice(0, loadedCount)
  const hasMore = picks.length < pool.length

  const loadMore = (immediate = false) => {
    if (loadingMore || !hasMore) return
    /* 首批六张在大屏上不一定铺满一屏：补齐要立刻完成，打开页面时不该先露一条「正在加载…」。 */
    if (immediate) {
      setLoadedCount((count) => count + PICK_BATCH)
      return
    }
    setLoadingMore(true)
    /* 演示件没有服务端：600ms 之后下一批到，好让「正在加载」那一帧真的被看见。 */
    window.setTimeout(() => {
      setLoadedCount((count) => count + PICK_BATCH)
      setLoadingMore(false)
    }, 600)
  }

  const hasQuery = query.trim() !== '' || tags.length > 0
  /* **前台与结果页是两个状态。** 没有关键词也没有分类时，这一页是搜索页加推荐段；一旦开始找，
     它就变成一份结果清单。两种状态不共享工具条——前台的框更大，结果的工具条更密。 */
  const landing = !hasQuery && state !== 'loading' && state !== 'failed'
  const [heroQuery, setHeroQuery] = useState('')
  /* **结果那一行也是回车才搜**（v19）：一次搜索要问一次模型，边打边筛就是每敲一个字付一次钱、
     还互相抢答。框里存的是草稿，地址里那个才是已经搜过的词——按回车才把它交出去。 */
  const [draftQuery, setDraftQuery] = useState(query)
  useEffect(() => {
    setDraftQuery(query)
  }, [query])

  /* **滑到底就取下一批，没有按钮。** 观察的是「更多」那一条本身：它一进视口，就等于滑到了底。
     它挂载时就在视口里也没关系——那就是首屏补齐：六张铺不满一屏时立刻再取一批，推荐才不会在
     按钮上面停住、下面留一大片空。 */
  useEffect(() => {
    if (!landing || !hasMore) return
    const node = moreRef.current
    if (node === null || typeof IntersectionObserver === 'undefined') return
    /* 提前 120px 取：够躲过一次急刹车，又不会在「更多」还远时就连着取两批。 */
    const observer = new IntersectionObserver((entries) => {
      if (!entries.some((entry) => entry.isIntersecting)) return
      const immediate = !filledRef.current
      filledRef.current = true
      loadMore(immediate)
    }, { rootMargin: '120px' })
    observer.observe(node)
    return () => observer.disconnect()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [landing, hasMore, loadingMore, loadedCount, picks.length])

  return (
    <section className="page" id="page-discover" hidden={!active}>
      <header className={'page-head' + (landing ? ' is-landing' : '')}>
        {/* **这一页没有看得见的标题**（2026-10-23 用户定）：导航里已经有「发现」，一句标题在这一行
            只是把它重复一遍，而且压出一句公文。留下的 h1 只给文档结构与读屏。 */}
        <h1 className="visually-hidden">发现</h1>
        <span className="count" hidden={!hasQuery || state === 'loading' || state === 'failed'}>
          {/* 按意思找时报的是**找到几条**（最多十条），不是公域一共有几条——那个数它给不出；
              有页码的那两种（按字面找、降级回落）报的才是总数。 */}
          {(paginated ? results.length : shown.length) + ' 个结果'}
        </span>
        <div className="page-actions">
          {/* **两个状态共用页头这一行，只换右边那一组。** 前台的框以提交为准，旁边多一颗
              「搜索」；结果的框边打边筛。先前各占一块纸，居中的那一块把推荐挤到了第二屏以下。 */}
          {landing ? (
            <form
              className="toolbar hero-toolbar"
              onSubmit={(event) => {
                event.preventDefault()
                if (heroQuery.trim() === '') return
                writeAddress({ query: heroQuery, page: 1 })
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
                  options={CATEGORIES.map((category) => ({
                    value: category.id,
                    label: category.name,
                    /* 停用只阻止**新增**；这个货架上已有的 skill 仍然要筛得出来。 */
                    disabled: false,
                  }))}
                  values={tags}
                  config={{ placeholder: '分类', ariaLabel: '分类', onToggle: toggleTag, onClear: () => writeAddress({ tags: [], page: 1 }) }}
                />
              </label>
            </form>
          ) : (
            <form
              className="toolbar"
              data-when="ready"
              hidden={state === 'loading' || state === 'failed'}
              onSubmit={(event) => {
                event.preventDefault()
                writeAddress({ query: draftQuery, page: 1 })
              }}
            >
              <SearchBox
                id="discoverQuery"
                value={draftQuery}
                placeholder="搜索名字、描述或适用场景"
                label="搜索公开的 skill"
                onChange={setDraftQuery}
              />
              <label className="filter-select">
                <span className="visually-hidden">分类</span>
                <MenuMultiSelect
                  options={CATEGORIES.map((category) => ({
                    value: category.id,
                    label: category.name,
                    /* 停用只阻止**新增**；这个货架上已有的 skill 仍然要筛得出来。 */
                    disabled: false,
                  }))}
                  values={tags}
                  config={{ placeholder: '分类', ariaLabel: '分类', onToggle: toggleTag, onClear: () => writeAddress({ tags: [], page: 1 }) }}
                />
              </label>
            </form>
          )}
        </div>
      </header>

      <FilterChips
        id="discoverFilters"
        chips={tags.map((id) => ({ value: id, label: categoryName(id) }))}
        onRemove={toggleTag}
        onClear={() => writeAddress({ tags: [], page: 1 })}
      />

      <p className="loading" data-when="loading" hidden={state !== 'loading'}>加载中…</p>
      <div className="banner" data-when="failed" hidden={state !== 'failed'}>
        无法加载公开的 skill，请稍后重试。
      </div>

      {/* **前台只有推荐这一块**：搜索在页头，橱窗在下面——橱窗有界，货架不摊开。 */}
      <div className="discover-home" data-when="first" hidden={!landing} id="discoverLanding">
        <section className="picks" id="discoverPicks">
          <h2 className="picks-title">为你推荐</h2>
          <p className="picks-note">这些是公开、有上线版本、你还没加入的 skill；最近更新的在前，下滑还有更多。</p>
          {picks.length > 0 ? (
            <>
              <ul className="results">
                {picks.map((skill) => (
                  <ResultCard
                    key={skill.name}
                    skill={skill}
                    source={sourceOf(skill)}
                    joined={joinState(skill.name)}
                    onJoin={() => join(skill.name)}
                    onRemove={undoable(skill.name)}
                  />
                ))}
              </ul>
              {/* **这里是那条哨兵**，也是取下一批时唯一会变的一行：加载中写一句，到底写一句，
                  其余时候它只是一条空的间距——没有按钮，滑动本身就是入口。 */}
              <div className="picks-more" id="discoverMore" ref={moreRef}>
                {!hasMore ? (
                  <p className="picks-end" id="discoverEnd">没有更多了。用上面的搜索或分类继续找。</p>
                ) : loadingMore ? (
                  <p className="picks-loading" id="discoverLoading">正在加载…</p>
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

      <div className="list-body" data-when="ready" hidden={state === 'loading' || state === 'failed'}>
        {/* **降级那一态要说出来。** 结果是按字面找的，只是这一页本该按意思找——不说，人只会
            觉得"搜得不准"。这是一句说明，不是错误，所以用安静的那一行，不用 banner。
            **字面也没找着时这一行不出现**：那句"下面是按字面找到的结果"会指着空的下面说，
            改由空态那一句把两件事一起说清。 */}
        {state === 'degraded' && semantic && shown.length > 0 && (
          <p className="picks-note" id="discoverNote">
            语义检索暂不可用。下面是按字面找到的结果。
          </p>
        )}

        {hasQuery && shown.length === 0 && state !== 'loading' && state !== 'failed' && (
          <p className="empty" id="discoverNone">
            {/* 三种空，三种说法：按意思找不到、按字面找不到、以及"语义这条路断了、字面也没找到"
                ——最后一种要把两件事一起说，否则人不知道是搜得不准还是这一页坏了。
                分哪一种是看**回来的形状**（有没有页码），不是看人有没有打字。 */}
            {/* 分类名不在词表里只可能来自深链；这一句只有服务端知道，所以它是第四句。 */}
            {state === 'bad-filter'
              ? '分类或标签的名字不在词表里，没有可检索的范围。'
              : state === 'degraded' && semantic
              ? '语义检索暂不可用。按字面找也没有匹配的 skill。'
              : paginated
                ? '没有找到匹配的 skill。换个词，或者换一个分类。'
                : '没有足够相关的 skill。'}
          </p>
        )}

        <ul className="results" id="discoverResults" hidden={shown.length === 0}>
          {shown.map((skill) => (
            <ResultCard
              key={skill.name}
              skill={skill}
              source={sourceOf(skill)}
              joined={joinState(skill.name)}
              onJoin={() => join(skill.name)}
              onRemove={undoable(skill.name)}
            />
          ))}
        </ul>

        {/* 按意思找没有页码：它给的是"最像的十条"，不是"第一页"。 */}
        {paginated && (
          <Pager
            id="discoverPager"
            total={results.length}
            page={page}
            pageSize={PAGE_SIZE}
            hrefFor={(target) => {
              const copy = new URLSearchParams()
              copy.set('page', 'discover')
              if (query !== '') copy.set('q', query)
              tags.forEach((tag) => copy.append('tag', tag))
              if (target > 1) copy.set('p', String(target))
              return '?' + copy.toString()
            }}
            onGo={(target) => {
              writeAddress({ page: target })
              window.scrollTo(0, 0)
            }}
          />
        )}
      </div>
    </section>
  )
}
