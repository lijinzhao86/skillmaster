import { useCallback, useEffect, useRef, useState } from 'react'
import { addressFor, go, query as pageQuery, replaceAddress } from '../address'
import { codeMessage } from '../api/errors'
import { restoreSkill } from '../api/skills'
import { listCategories, listLibrary, listMyTags, listPublicTagUsages } from '../api/library'
import type {
  CategoryRow,
  LibraryPage,
  MyTagUsage,
  PublicTagUsage,
  SkillCard,
} from '../api/types'
import { loginPath } from '../login'
import { useSession } from '../store'
import { useLibrarySelection } from '../hooks/useLibrarySelection'
import FormBanner from '../components/FormBanner'
import CommandMenu from '../components/CommandMenu'
import type { CommandMenuHandle, CommandMenuItem } from '../components/CommandMenu'
import FilterChips from '../components/FilterChips'
import LibraryRow from '../components/library/LibraryRow'
import SkillPreview from '../components/library/SkillPreview'
import MenuMultiSelect from '../components/MenuMultiSelect'
import MenuSelect from '../components/MenuSelect'
import Pager from '../components/Pager'
import SearchBox from '../components/SearchBox'
import ImportDialog from '../components/skill/ImportDialog'
import NewSkillDialog from '../components/skill/NewSkillDialog'

/**
 * 我的 skill: **the complete library** — what I created, what is shared with me, and what I joined.
 *
 * **The source is a row's inline mark, not a third tab.** The three words describe where the same
 * list's rows came from, not three different lists; the tabs stay 在用 / 已删除 because every row in
 * the second can do exactly one thing — come back.
 *
 * **A public skill I have not joined is not here.** It lives on 发现 until I add it.
 *
 * Search, sort, the page number and the three tag filters are all read by `/web/library`; the
 * address carries them so a filtered list can be sent to somebody else. The three counts the empty
 * states need come back with the list: no entries, all dormant, and nothing deleted are three
 * different sentences (prd.md §空态).
 */
/* **How many rows a page holds (2026-11-01, the user's call): 10 / 20 / 50, default 10.** It lives
   in the address (`?per=20`) like every other fact on this screen, and changing it lands on page 1. */
const PER_OPTIONS = [10, 20, 50]
const PER_DEFAULT = 10
const SORT_GROUPS = [
  {
    options: [
      { value: 'recent', label: '最近更新' },
      { value: 'name', label: '名字' },
    ],
  },
]

type Sheet = 'none' | 'import' | 'create'
type LoadState = 'loading' | 'ready' | 'failed'

export default function SkillsPage() {
  const initial = useRef(pageQuery()).current
  const show: 'all' | 'deleted' = initial.get('show') === 'deleted' ? 'deleted' : 'all'

  const [query, setQuery] = useState(() => initial.get('q') ?? '')
  const [sort, setSort] = useState<'recent' | 'name'>(() =>
    initial.get('sort') === 'name' ? 'name' : 'recent',
  )
  const [tags, setTags] = useState(() => (show === 'deleted' ? [] : initial.getAll('tag')))
  const [myTags, setMyTags] = useState(() => (show === 'deleted' ? [] : initial.getAll('my-tag')))
  const [publicTags, setPublicTags] = useState(() =>
    show === 'deleted' ? [] : initial.getAll('public-tag'),
  )
  const [page, setPage] = useState(() =>
    Math.max(1, Number.parseInt(initial.get('p') ?? '1', 10) || 1),
  )
  const [pageSize, setPageSize] = useState(() => {
    const asked = Number.parseInt(initial.get('per') ?? '', 10)
    return PER_OPTIONS.includes(asked) ? asked : PER_DEFAULT
  })
  /* **Which row's preview is open (v35).** Carried in the address like every other fact on this
     screen — `?sel=ns/name`, the shape a skill is really addressed by — so a previewed row can be
     sent to somebody and survives a reload. */
  const [sel, setSel] = useState<string | null>(() => initial.get('sel'))

  const [data, setData] = useState<LibraryPage | null>(null)
  /** The `listKey` **the rows on screen were read under** — not necessarily the current one: between
   *  a filter change and its answer, `data` is still the previous page's, and reading it as if it
   *  answered the new filters is what let a filtered-out skill keep its preview open. */
  const [dataKey, setDataKey] = useState<string | null>(null)
  const [state, setState] = useState<LoadState>('loading')
  const [definitions, setDefinitions] = useState<MyTagUsage[]>([])
  const [publicNames, setPublicNames] = useState<PublicTagUsage[]>([])
  /** `/web/categories`: the filter's words and the row chips' names. */
  const [categories, setCategories] = useState<CategoryRow[]>([])
  const [banner, setBanner] = useState<string | null>(null)
  const [restoring, setRestoring] = useState<string | null>(null)
  /** Bumped after a restore: the row leaves 已删除 and the counts change, so the page re-reads. */
  const [reloadKey, setReloadKey] = useState(0)

  const { account } = useSession()
  const [sheet, setSheet] = useState<Sheet>('none')
  const createMenu = useRef<CommandMenuHandle | null>(null)
  const createItems: CommandMenuItem[] = [
    { label: '在线编辑', onSelect: () => setSheet('create') },
    { label: '导入本地 skill', onSelect: () => setSheet('import') },
  ]

  function closeSheet(): void {
    setSheet('none')
    requestAnimationFrame(() => createMenu.current?.focusTrigger())
  }

  const tagsKey = tags.join('\u0000')
  const myTagsKey = myTags.join('\u0000')
  const publicTagsKey = publicTags.join('\u0000')
  /* **The filters as one string.** Two things are read against it: which card the preview may keep
     (a remembered card belongs to the filters it was opened under) and whether the rows on screen
     were read under these filters at all. The page number is deliberately not part of it: paging is
     what the pane survives. **`reloadKey` is**: restoring a skill re-reads the same filters, and a
     skill that has just left 已删除 must not keep a preview that says it is still deleted. */
  const listKey = [query, sort, tagsKey, myTagsKey, publicTagsKey, show, String(reloadKey)].join('\u0000')

  /* ------------------------------------------------------------------- the page read */

  useEffect(() => {
    let cancelled = false
    setState('loading')
    void (async () => {
      const result = await listLibrary({
        q: query,
        tag: tags,
        myTag: myTags,
        publicTag: publicTags,
        sort,
        show,
        p: page,
        per: pageSize,
      })
      if (cancelled) {
        return
      }
      if (!result.ok) {
        if (result.code === 'unauthenticated') {
          go(loginPath())
          return
        }
        setState('failed')
        return
      }
      setData(result.data)
      setDataKey(listKey)
      setState('ready')
      // **The transport echoes the page it was asked for and cuts by offset**, so a page past the
      // end is an empty page. The design's rule is that it lands on the last page: correct the
      // address and read that page rather than showing a blank list.
      const pages = Math.max(1, Math.ceil(result.data.filtered_total / pageSize))
      if (page > pages) {
        setPage(pages)
        writeAddress({ page: pages })
      }
    })()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, sort, tagsKey, myTagsKey, publicTagsKey, page, pageSize, show, reloadKey, listKey])

  /** The objects the filter menus need. One read per page load, once the list says they matter. */
  const defsLoaded = useRef(false)
  useEffect(() => {
    if (defsLoaded.current || state !== 'ready' || data === null || show === 'deleted') {
      return
    }
    if (data.total_entries === 0 || data.effective_entries === 0) {
      return
    }
    defsLoaded.current = true
    let cancelled = false
    void (async () => {
      const [mine, publicWords, categoryList] = await Promise.all([
        listMyTags(),
        listPublicTagUsages(),
        listCategories(),
      ])
      if (cancelled) {
        return
      }
      if (mine.ok && Array.isArray(mine.data?.tags)) {
        setDefinitions(mine.data.tags)
      }
      if (publicWords.ok && Array.isArray(publicWords.data?.tags)) {
        setPublicNames(publicWords.data.tags)
      }
      if (categoryList.ok && Array.isArray(categoryList.data?.categories)) {
        setCategories(categoryList.data.categories)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [state, data, show])

  /* `show=deleted` must not leave an address that names a filter it ignores. A shared link may carry
     them; the page opens and takes them off. */
  useEffect(() => {
    if (show !== 'deleted') {
      return
    }
    const stale =
      initial.getAll('tag').length +
      initial.getAll('my-tag').length +
      initial.getAll('public-tag').length
    if (stale === 0) {
      return
    }
    const next = new URLSearchParams(initial)
    next.delete('tag')
    next.delete('my-tag')
    next.delete('public-tag')
    replaceAddress(addressFor('/', next))
    // The address is fixed for the life of the page.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /* ----------------------------------------------------------------------- address */

  function writeAddress(next: {
    query?: string
    sort?: 'recent' | 'name'
    page?: number
    tags?: string[]
    myTags?: string[]
    publicTags?: string[]
    /** Rows per page. Changing it must be sent with `page: 1` — a size change lands on page 1. */
    pageSize?: number
    /** `null` closes the preview; omitted keeps whatever is open. */
    sel?: string | null
  }): void {
    const currentQuery = next.query ?? query
    const currentSort = next.sort ?? sort
    const currentPage = next.page ?? page
    const currentTags = next.tags ?? tags
    const currentMyTags = next.myTags ?? myTags
    const currentPublicTags = next.publicTags ?? publicTags
    const params = new URLSearchParams()
    if (show === 'deleted') {
      params.set('show', 'deleted')
    }
    if (currentQuery.trim() !== '') {
      params.set('q', currentQuery)
    }
    if (currentSort !== 'recent') {
      params.set('sort', currentSort)
    }
    const currentSize = next.pageSize ?? pageSize
    if (currentSize !== PER_DEFAULT) {
      params.set('per', String(currentSize))
    }
    if (currentPage !== 1) {
      params.set('p', String(currentPage))
    }
    const currentSel = next.sel === undefined ? sel : next.sel
    if (currentSel !== null) {
      params.set('sel', currentSel)
    }
    currentTags.forEach((key) => params.append('tag', key))
    currentMyTags.forEach((id) => params.append('my-tag', id))
    currentPublicTags.forEach((key) => params.append('public-tag', key))
    /* **`sel` stays readable.** `URLSearchParams` writes `/` as `%2F`; the address is meant to be
       copied and read (`?sel=dev/pdf-tools`), and a literal `/` in a query value parses back to the
       same string. */
    replaceAddress(addressFor('/', params).replaceAll('%2F', '/'))
  }

  const toggleTag = (key: string) => {
    const next = tags.includes(key) ? tags.filter((item) => item !== key) : tags.concat(key)
    setTags(next)
    setPage(1)
    writeAddress({ tags: next, page: 1 })
  }

  const toggleMyTag = (id: string) => {
    const next = myTags.includes(id) ? myTags.filter((item) => item !== id) : myTags.concat(id)
    setMyTags(next)
    setPage(1)
    writeAddress({ myTags: next, page: 1 })
  }

  const goToPage = (number: number) => {
    setPage(number)
    writeAddress({ page: number })
    window.scrollTo(0, 0)
  }

  const clearFilters = () => {
    setTags([])
    setMyTags([])
    setPublicTags([])
    setPage(1)
    writeAddress({ tags: [], myTags: [], publicTags: [], page: 1 })
  }

  /* ------------------------------------------------------------------- the rows */

  const myTagName = (id: string): string =>
    definitions.find((tag) => tag.id === id)?.name ?? '已删除的标签'
  const publicTagName = (key: string): string =>
    publicNames.find((tag) => tag.key === key)?.name ??
    data?.skills.flatMap((skill) => skill.public_tags).find((tag) => tag.key === key)?.name ??
    '已删除的公开标签'
  /** Before the vocabulary lands a selected key is shown as itself rather than as a wrong name. */
  const categoryName = (key: string): string =>
    categories.find((category) => category.key === key)?.name ?? key

  const chips = tags
    .map((key) => ({ value: 'tag:' + key, label: categoryName(key) }))
    .concat(myTags.map((id) => ({ value: 'my-tag:' + id, label: myTagName(id) })))
    .concat(publicTags.map((key) => ({ value: 'public-tag:' + key, label: '#' + publicTagName(key) })))

  const removeChip = (value: string) => {
    // Split on the **first** colon only: a public tag's key is a free word and may itself contain
    // one, and `split(':')` would drop everything after it while the filter stayed in the address.
    const at = value.indexOf(':')
    const kind = at < 0 ? value : value.slice(0, at)
    const key = at < 0 ? '' : value.slice(at + 1)
    if (kind === 'tag') {
      toggleTag(key ?? '')
    } else if (kind === 'my-tag') {
      toggleMyTag(key ?? '')
    } else {
      const next = publicTags.filter((item) => item !== key)
      setPublicTags(next)
      setPage(1)
      writeAddress({ publicTags: next, page: 1 })
    }
  }

  async function restore(skill: SkillCard): Promise<void> {
    setRestoring(`${skill.namespace}/${skill.name}`)
    const result = await restoreSkill(skill.namespace, skill.name)
    setRestoring(null)
    if (!result.ok) {
      setBanner(codeMessage(result.code, result.message))
      return
    }
    // **Stay on the list.** The design's restore moves the row out of 已删除 in place; following
    // the skill's own page would throw away the search, filters and page the person was in — and
    // restoring several would mean coming back several times.
    setReloadKey((key) => key + 1)
  }

  /* ------------------------------------------------------------------ the states */

  const totalEntries = data?.total_entries ?? 0
  const effectiveEntries = data?.effective_entries ?? 0
  const filteredTotal = data?.filtered_total ?? 0
  const settled = state !== 'loading' && state !== 'failed'
  const libEmpty = state === 'ready' && totalEntries === 0
  const dormant =
    state === 'ready' && show === 'all' && totalEntries > 0 && effectiveEntries === 0
  const ready = state === 'ready'
  // The deleted list still has a search and a sort even when every live entry is dormant.
  const toolbarShown = ready && !libEmpty && (show === 'deleted' || !dormant)
  const rows = data?.skills ?? []
  const pages = Math.max(1, Math.ceil(filteredTotal / pageSize))
  const shownPage = Math.min(data?.page ?? page, pages)
  /** **A card the address names but this page does not hold** (2026-11-01, the user's call): the pane
   *  is meant to survive a reload of `?p=2&sel=…`, and the design's promise about `sel` is about the
   *  **filtered list**, not about the page. This reads that list narrowed by the skill's own name, so
   *  "found" also means "still in this list" — the exact address is then matched again here.
   *  **A search term cannot be replayed this way** (one `q`, and it would replace the person's), so
   *  while `q` is set an address naming another page is still dropped. That limit is written down in
   *  the version's PRD rather than hidden here. */
  const probeAddress = useCallback(
    async (address: string): Promise<SkillCard | null | 'unknown'> => {
      const cut = address.lastIndexOf('/')
      if (cut <= 0) {
        return 'unknown'
      }
      const namespace = address.slice(0, cut)
      const name = address.slice(cut + 1)
      const size = Math.max(...PER_OPTIONS)
      /* **Same filters, then look for the exact address.** With no search term the read is narrowed
         by the skill's own name — a name matches itself, so "found" ⟺ "in this list" both ways, and
         one request answers it. With a search term the person's `q` is used as it stands and the
         pages are walked instead: shortening `q` to the name would answer a different question. */
      const term = query.trim() === '' ? name : query
      for (let p = 1; p <= 5; p += 1) {
        const result = await listLibrary({
          tag: tags,
          myTag: myTags,
          publicTag: publicTags,
          show,
          q: term,
          per: size,
          p,
        })
        if (!result.ok) {
          /* A failed read is not evidence: dropping here would lose the address to a 5xx. */
          return 'unknown'
        }
        const hit = result.data.skills.find(
          (card) => card.namespace === namespace && card.name === name,
        )
        if (hit !== undefined) {
          return hit
        }
        if (p * size >= result.data.filtered_total) {
          return null
        }
      }
      /* Five pages in and still not seen — "cannot tell", not "not there" (audit 2026-11-01). */
      return 'unknown'
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [query, tagsKey, myTagsKey, publicTagsKey, show],
  )

  /* **The pane, its memory and its verdict all live in one place** — see the hook, including why the
     filters are compared before anything is believed and why an address can name a skill this page
     does not hold. A selection that is no longer in the list is dropped from the address there too:
     the rule is the screen's own — the address is this screen's state, with no condition in it that
     does not apply. */
  const selected = useLibrarySelection({
    sel,
    rows,
    fresh: dataKey === listKey && state === 'ready',
    outOfRange: data !== null && (data.page ?? page) > pages,
    listKey,
    probe: probeAddress,
    onDrop: () => {
      setSel(null)
      writeAddress({ sel: null })
    },
  })

  const openPreview = (row: SkillCard) => {
    const key = `${row.namespace}/${row.name}`
    setSel(key)
    writeAddress({ sel: key })
  }

  const closePreview = () => {
    setSel(null)
    writeAddress({ sel: null })
  }

  return (
    <>
      <section className="page is-list" id="page-skills">
        <header className="page-head">
          <h1>我的 skill</h1>
          <span className="count" hidden={!ready || libEmpty}>
            {filteredTotal + ' 个' + (show === 'deleted' ? '已删除' : '')}
          </span>
          <div className="page-actions">
            {toolbarShown ? (
              <div className="toolbar">
                <SearchBox
                  id="skillQuery"
                  value={query}
                  placeholder="搜索名字、描述或适用场景"
                  label="搜索"
                  onChange={(value) => {
                    setQuery(value)
                    setPage(1)
                    writeAddress({ query: value, page: 1 })
                  }}
                />
                {show === 'all' ? (
                  <>
                    <label className="filter-select">
                      <span className="visually-hidden">分类</span>
                      <MenuMultiSelect
                        options={categories.map((category) => ({
                          value: category.key,
                          label: category.name,
                        }))}
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
                    <label className="filter-select">
                      <span className="visually-hidden">我的标签</span>
                      <MenuMultiSelect
                        options={definitions.map((tag) => ({ value: tag.id, label: tag.name }))}
                        values={myTags}
                        config={{
                          placeholder: '我的标签',
                          ariaLabel: '我的标签',
                          onToggle: toggleMyTag,
                          onClear: () => {
                            setMyTags([])
                            setPage(1)
                            writeAddress({ myTags: [], page: 1 })
                          },
                        }}
                      />
                    </label>
                  </>
                ) : null}
                <label className="sort">
                  <span className="visually-hidden">排序</span>
                  <MenuSelect
                    groups={SORT_GROUPS}
                    value={sort}
                    ariaLabel="排序"
                    onSelect={(value) => {
                      const next = value === 'name' ? 'name' : 'recent'
                      setSort(next)
                      setPage(1)
                      writeAddress({ sort: next, page: 1 })
                    }}
                  />
                </label>
              </div>
            ) : null}
            {settled ? (
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

        <nav
          className="skill-tabs list-tabs"
          id="listTabs"
          aria-label="我的 skill 的两份列表"
          hidden={!settled || libEmpty}
        >
          <a
            className={'skill-tab' + (show === 'all' ? ' is-current' : '')}
            data-show="live"
            href="/"
            aria-current={show === 'all' ? 'page' : undefined}
          >
            在用
          </a>
          <a
            className={'skill-tab' + (show === 'deleted' ? ' is-current' : '')}
            data-show="deleted"
            href="/?show=deleted"
            aria-current={show === 'deleted' ? 'page' : undefined}
          >
            已删除
          </a>
        </nav>

        <FilterChips
          id="skillFilters"
          chips={chips}
          onRemove={removeChip}
          onClear={clearFilters}
        />

        <FormBanner message={banner} />

        {state === 'loading' ? <p className="loading">加载中…</p> : null}
        {state === 'failed' ? (
          <div className="banner">读不到你的 skill 列表，稍后再试一次。</div>
        ) : null}

        {libEmpty ? (
          <p className="empty" id="skillEmpty">
            还没有 skill。新建一个，或者<a href="/discover">去发现公开 skill</a>。
            <br />
            也可以运行 <code>skmr skill submit</code>。
          </p>
        ) : null}

        {dormant ? (
          <p className="empty" id="skillDormant">
            这里暂时没有能显示的 skill。它们可能已下线或暂时不可见，恢复后会回来。
          </p>
        ) : null}

        {ready && !libEmpty ? (
          <div className={`list-split${selected === null ? '' : ' is-split'}`}>
            <div className="list-body">
            <ul className="skills" id={show === 'all' ? 'skillList' : 'deletedList'}>
              {rows.map((skill) => (
                <LibraryRow
                  key={skill.id}
                  skill={skill}
                  deleted={show === 'deleted'}
                  selected={selected !== null && selected.id === skill.id}
                  /* **No prefix for your own namespace** (2026-10-31, the user's call): most rows on
                     this page live in it, so writing it every time is one repeated word per row. The
                     rule reads 「no prefix = yours」; team namespaces still show theirs. */
                  mine={account !== null && skill.namespace === account.namespace}
                  restoring={restoring === `${skill.namespace}/${skill.name}`}
                  onSelect={() => openPreview(skill)}
                  onRestore={() => {
                    void restore(skill)
                  }}
                />
              ))}
              {/* **The pager is the card's last row** (2026-11-01, the user's call): it lives inside
                  the `<ul>`, so the border, the radius and the hairline above it are the card's own.
                  When everything fits `<Pager>` returns null and the row is empty — `:empty` folds
                  it away, and the last skill gives its bottom border back. */}
              {rows.length === 0 ? null : (
                <li className="pager-row">
              <Pager
                id="skillPager"
                total={filteredTotal}
                page={shownPage}
                pageSize={pageSize}
                sizeOptions={PER_OPTIONS}
                onPageSize={(size) => {
                  setPageSize(size)
                  setPage(1)
                  writeAddress({ pageSize: size, page: 1 })
                }}
                hrefFor={(target) => {
                  const params = new URLSearchParams()
                  if (show === 'deleted') {
                    params.set('show', 'deleted')
                  }
                  if (query.trim() !== '') {
                    params.set('q', query)
                  }
                  if (sort !== 'recent') {
                    params.set('sort', sort)
                  }
                  tags.forEach((key) => params.append('tag', key))
                  myTags.forEach((id) => params.append('my-tag', id))
                  publicTags.forEach((key) => params.append('public-tag', key))
                  if (pageSize !== PER_DEFAULT) {
                    params.set('per', String(pageSize))
                  }
                  if (target > 1) {
                    params.set('p', String(target))
                  }
                  return addressFor('/', params)
                }}
                onGo={goToPage}
              />
                </li>
              )}
            </ul>

            {/* A filter that matched nothing is its own sentence, not one of the three empty states. */}
            {show === 'all' && !dormant && filteredTotal === 0 ? (
              <p className="empty" id="skillNone">
                没有匹配的 skill。换个词试试，或者清空筛选。
              </p>
            ) : null}
            {show === 'deleted' && filteredTotal === 0 ? (
              <p className="empty" id="deletedNone">
                没有删掉的 skill。
              </p>
            ) : null}

            </div>

            {/* **The preview is the list's neighbour, not another screen.** Wide: side by side
                (the list gives up 380 of the column). Narrow: it floats over the list. Where that
                line falls, and why, is in `styles.css` (`.list-split` / `.skill-preview`). */}
            {selected === null ? null : (
              <SkillPreview
                skill={selected}
                deleted={show === 'deleted'}
                mine={account !== null && selected.namespace === account.namespace}
                onClose={closePreview}
              />
            )}
          </div>
        ) : null}
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
