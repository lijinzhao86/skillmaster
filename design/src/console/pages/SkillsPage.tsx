/**
 * 我的 skill: **我的库**——我创建的 + 共享给我的 + 我加入的，一栏一个。
 *
 * **这一页是完整库，不是可消费的那一份。** 草稿、下线的、作者面的「已删除」都在这里；CLI 的
 * `skills list` 只列其中可消费的那些。两者的差别是这一版最容易被写错的一处，所以它写在第一行。
 *
 * **来源是行内标签，不是第三个页签。** 「我创建的 / 共享给我的 / 我加入的」说的是同一份列表里
 * 这些东西从哪来，不是三种不同的列表；页签仍然是「在用 / 已删除」，因为「已删除」只能做一件事：
 * 恢复。
 *
 * **公开但没加入的不在这里。** 它只在「发现」页出现；要让它进库，必须显式加入（PRD 做 #1/#2）。
 *
 * 搜索、排序、页码与两组标签筛选都在地址里，所以筛完的列表可以直接发给别人；地址里写标签 id，
 * 界面上写名字。
 *
 * ⚠️ **这里不再有调用次数，也没有「调用最多」排序。** 产品今天没有用量信号，设计稿里那一列曾是
 * 前瞻字段（PRD 不做 #23）。排序只剩两种：最近更新、名字。
 */

import { useEffect, useState } from 'react'
import { replace } from '../address'
import FilterChips from '../components/FilterChips'
import MenuMultiSelect from '../components/MenuMultiSelect'
import MenuSelect from '../components/MenuSelect'
import Pager from '../components/Pager'
import SearchBox from '../components/SearchBox'
import CommandMenu from '../components/CommandMenu'
import { dialogs } from '../components/dialogs/store'
import {
  DELETED,
  MY_TAGS,
  SOURCE_LABEL,
  activeLibrary,
  categoryName,
  CATEGORIES,
  matchesLibrary,
  myTagName,
  publicTagName,
} from '../fixtures/library'
import type { LibrarySkill } from '../fixtures/library'
import type { DemoPageProps } from './types'

const PAGE_SIZE = 10
const SORTS = ['recent', 'name']

const SORT_GROUPS = [{
  options: [
    { value: 'recent', label: '最近更新' },
    { value: 'name', label: '名字' },
  ],
}]

export default function SkillsPage({ active, state, params }: DemoPageProps) {
  const [live, setLive] = useState<LibrarySkill[]>(() => activeLibrary().map((skill) => ({ ...skill })))
  const [gone, setGone] = useState<LibrarySkill[]>(() => DELETED.map((skill) => ({ ...skill })))
  /* 地址里带 `new=open` 时把「新建」菜单打开：点得出来的状态也要能单独发出去。 **Only on this page**:
     all pages are mounted, and a skill page's `new=open` belongs to its own 新建版本 menu. */
  const [menuOpen, setMenuOpen] = useState(active && params.get('new') === 'open')
  const [create, setCreate] = useState<HTMLButtonElement | null>(null)

  const showDeleted = params.get('show') === 'deleted'
  const query = params.get('q') ?? ''
  const askedSort = params.get('sort')
  const sort = askedSort && SORTS.includes(askedSort) ? askedSort : 'recent'
  const tags = showDeleted ? [] : params.getAll('tag')
  const myTags = showDeleted ? [] : params.getAll('my-tag')
  /* **公开标签只有一条入口：管理页的那条计数链接。** 它是召回词，不是库的筛选条上的第三把尺子。 */
  const publicTags = showDeleted ? [] : params.getAll('public-tag')
  const askedPage = parseInt(params.get('p') ?? '', 10)

  /* **已删除那一份列表没有标签可筛**（PRD #9）：地址里若还带着它们（分享来的链接、手打），
     页面照常打开，但把这几个参数从地址里清掉——地址是这一屏的状态，不留一个不生效的条件。 */
  useEffect(() => {
    if (!active || !showDeleted) return
    const stale = params.getAll('tag').length + params.getAll('my-tag').length +
      params.getAll('public-tag').length
    if (stale === 0) return
    const copy = new URLSearchParams(params)
    copy.set('page', 'skills')
    copy.delete('tag')
    copy.delete('my-tag')
    copy.delete('public-tag')
    replace('?' + copy.toString())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active])

  let page = Number.isNaN(askedPage) || askedPage < 1 ? 1 : askedPage

  /** 两种顺序。同一个名字的先后是确定的，所以分页不会在两页之间抖。 */
  const ordered = (a: LibrarySkill, b: LibrarySkill) => {
    if (sort === 'name') return a.name < b.name ? -1 : a.name > b.name ? 1 : 0
    return b.updated.localeCompare(a.updated)
  }

  const matched = (showDeleted ? gone : live)
    .filter((skill) => matchesLibrary(skill, query, tags, myTags, publicTags))
    .sort(ordered)
  const pages = Math.max(1, Math.ceil(matched.length / PAGE_SIZE))
  if (page > pages) page = pages
  const start = (page - 1) * PAGE_SIZE
  const shown = matched.slice(start, start + PAGE_SIZE)

  /** The address carries the list, so a filtered, sorted, second-page list can be sent on. */
  const writeAddress = (next: {
    query?: string
    sort?: string
    page?: number
    tags?: string[]
    myTags?: string[]
    publicTags?: string[]
  }) => {
    const copy = new URLSearchParams()
    copy.set('page', 'skills')
    if (showDeleted) copy.set('show', 'deleted')
    const q = next.query ?? query
    const s = next.sort ?? sort
    const p = next.page ?? page
    const chosenTags = next.tags ?? tags
    const chosenMyTags = next.myTags ?? myTags
    const chosenPublicTags = next.publicTags ?? publicTags
    if (q !== '') copy.set('q', q)
    if (s !== 'recent') copy.set('sort', s)
    if (p !== 1) copy.set('p', String(p))
    chosenTags.forEach((id) => copy.append('tag', id))
    chosenMyTags.forEach((id) => copy.append('my-tag', id))
    chosenPublicTags.forEach((id) => copy.append('public-tag', id))
    replace('?' + copy.toString())
  }

  const toggleTag = (id: string) => {
    writeAddress({ tags: tags.includes(id) ? tags.filter((tag) => tag !== id) : tags.concat(id), page: 1 })
  }

  const toggleMyTag = (id: string) => {
    writeAddress({ myTags: myTags.includes(id) ? myTags.filter((tag) => tag !== id) : myTags.concat(id), page: 1 })
  }

  const goToPage = (number: number) => {
    writeAddress({ page: number })
    window.scrollTo(0, 0)
  }

  const restore = (skill: LibrarySkill) => {
    setGone((rows) => rows.filter((row) => row !== skill))
    setLive((rows) => [...rows, skill])
  }

  const chips = tags.map((id) => ({ value: 'tag:' + id, label: categoryName(id) }))
    .concat(myTags.map((id) => ({ value: 'my-tag:' + id, label: myTagName(id) })))
    .concat(publicTags.map((id) => ({ value: 'public-tag:' + id, label: '#' + publicTagName(id) })))

  const removeChip = (value: string) => {
    const [kind, id] = value.split(':')
    if (kind === 'tag') toggleTag(id)
    else if (kind === 'public-tag') {
      writeAddress({ publicTags: publicTags.filter((tag) => tag !== id), page: 1 })
    } else toggleMyTag(id)
  }

  const clearFilters = () => writeAddress({ tags: [], myTags: [], publicTags: [], page: 1 })

  /** 一行左边的身份：名字 + 来源。右边的状态：已上线 / 草稿 / 已下线。 */
  const rowStatus = (skill: LibrarySkill) => {
    if (skill.state === 'offline') {
      return <span className="chip is-offline"><i className="dot"></i><span className="chip-text">已下线</span></span>
    }
    if (skill.state === 'draft') {
      return <span className="chip is-draft"><i className="dot"></i><span className="chip-text">草稿</span></span>
    }
    return (
      <span className="chip is-live">
        <i className="dot"></i>
        <span className="chip-text">{'已上线 @' + skill.version}</span>
      </span>
    )
  }

  const libEmpty = state === 'empty'
  const dormant = state === 'dormant'
  const ready = state === 'ready'
  /* 读到了一个答案（哪怕是「空」）与读不到是两件事：加载中 / 出错时没有页签可点。 */
  const settled = state !== 'loading' && state !== 'failed'

  return (
    <section className="page" id="page-skills" hidden={!active}>
      <header className="page-head">
        <h1>我的 skill</h1>
        <span className="count" data-when="ready" id="skillCount" hidden={!ready}>
          {matched.length + ' 个' + (showDeleted ? '已删除' : '')}
        </span>
        <div className="page-actions">
          <div className="toolbar" data-when="ready" hidden={!ready}>
            <SearchBox
              id="skillQuery"
              value={query}
              placeholder="搜索名字、描述或适用场景"
              label="搜索"
              onChange={(value) => writeAddress({ query: value, page: 1 })}
            />
            {/* **两份筛选都是多选，都取「且」。** 它们只在「在用」上画：已删除的那份列表没有标签
                可筛（PRD #9）。 */}
            {!showDeleted && (
              <>
                <label className="filter-select">
                  <span className="visually-hidden">分类</span>
                  <MenuMultiSelect
                    options={CATEGORIES.map((category) => ({ value: category.id, label: category.name }))}
                    values={tags}
                    config={{ placeholder: '分类', ariaLabel: '分类', onToggle: toggleTag, onClear: () => writeAddress({ tags: [], page: 1 }) }}
                  />
                </label>
                <label className="filter-select">
                  <span className="visually-hidden">我的标签</span>
                  <MenuMultiSelect
                    options={MY_TAGS.map((tag) => ({ value: tag.id, label: tag.name }))}
                    values={myTags}
                    config={{ placeholder: '我的标签', ariaLabel: '我的标签', onToggle: toggleMyTag, onClear: () => writeAddress({ myTags: [], page: 1 }) }}
                  />
                </label>
              </>
            )}
            <label className="sort">
              <span className="visually-hidden">排序</span>
              <span id="skillSortSlot">
                <MenuSelect
                  groups={SORT_GROUPS}
                  value={sort}
                  config={{
                    ariaLabel: '排序',
                    onPick: (next) => {
                      writeAddress({ sort: next, page: 1 })
                    },
                  }}
                />
              </span>
            </label>
          </div>
          {/* **新建 skill 也是一颗带菜单的按钮（v9）。** 搜索与筛选在空态里没有意义，所以它们仍然
              不画；这一颗是空态里唯一的下一步，因此它自己带着 `empty`。 */}
          <button
            type="button"
            className="button menu-button"
            id="skillsCreate"
            data-when="ready empty"
            hidden={!settled}
            aria-haspopup="menu"
            aria-expanded={menuOpen ? 'true' : 'false'}
            ref={setCreate}
            onClick={() => setMenuOpen((open) => !open)}
            onKeyDown={(event) => {
              if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault()
                setMenuOpen(true)
              }
            }}
          >
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
            新建 skill
            <svg className="button-caret" viewBox="0 0 12 12" aria-hidden="true"><path d="M3 4.8 6 7.8 9 4.8" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" /></svg>
          </button>
        </div>
      </header>

      {/* 库本身是空的：没有页签可点，下一步就是新建或去发现。 */}
      <nav
        className="skill-tabs list-tabs"
        id="listTabs"
        aria-label="我的 skill 的两份列表"
        data-when="ready"
        hidden={!settled || libEmpty}
      >
        {/* **Only the page that is up marks a tab current.** All pages are mounted, and a hidden
            page's `is-current` would still be found by `document.querySelector` — that is how the
            skill page's own tab check once read 在用, a word from this page. */}
        <a
          className={'skill-tab' + (active && !showDeleted ? ' is-current' : '')}
          data-show="live"
          href="?page=skills"
          aria-current={active && !showDeleted ? 'page' : undefined}
        >
          在用
        </a>
        <a
          className={'skill-tab' + (active && showDeleted ? ' is-current' : '')}
          data-show="deleted"
          href="?page=skills&show=deleted"
          aria-current={active && showDeleted ? 'page' : undefined}
        >
          已删除
        </a>
      </nav>

      <FilterChips id="skillFilters" chips={chips} onRemove={removeChip} onClear={clearFilters} />

      <p className="loading" data-when="loading" hidden={state !== 'loading'}>加载中…</p>

      <div className="banner" data-when="failed" hidden={state !== 'failed'}>读不到你的 skill 列表，稍后再试一次。</div>

      {/* **库为空 = 没有任何条目（含休眠）**：不画页签，两个下一步都在这儿。 */}
      <p className="empty" data-when="empty" id="skillEmpty" hidden={!libEmpty}>
        还没有 skill。新建一个，或者<a href="?page=discover">去发现公开 skill</a>。
        <br />
        也可以运行 <code>skillmaster skill submit</code>。
      </p>

      {/* **有条目、但一条都显示不出来**：页签还在（库非空），只是这一份列表空着。 */}
      <p className="empty" data-when="dormant" id="skillDormant" hidden={!dormant}>
        这里暂时没有能显示的 skill。它们可能已下线或暂时不可见，恢复后会回来。
      </p>

      <div className="list-body" data-when="ready" hidden={!ready}>
        <ul className="skills" id="skillList" hidden={showDeleted}>
          {!showDeleted && shown.map((skill) => (
            <li className="skill" key={skill.name} data-source={skill.source} data-state={skill.state}>
              <div className="skill-line">
                <a className="skill-name" href="?page=skill">{skill.name}</a>
                <span className="chip is-source">
                  <span className="chip-text">{SOURCE_LABEL[skill.source]}</span>
                </span>
                <span className="spacer"></span>
                {rowStatus(skill)}
              </div>
              <div className="skill-line skill-sub">
                <p className="skill-title">{skill.title}</p>
                <span className="spacer"></span>
                <span className="skill-updated">{'更新于 ' + skill.updated}</span>
              </div>
            </li>
          ))}
        </ul>

        <ul className="skills" id="deletedList" hidden={!showDeleted}>
          {showDeleted && shown.map((skill) => (
            <li className="skill" key={skill.name}>
              <div className="skill-line">
                {/* The name is text, not a link: its address opens nothing now, and a name that looks
                    like a link and goes nowhere is worse than plain text. */}
                <span className="skill-name">{skill.name}</span>
                <span className="chip is-plain">
                  <i className="dot"></i>
                  <span className="chip-text">已删除</span>
                </span>
                <span className="spacer"></span>
                <button type="button" className="link-button restore" onClick={() => restore(skill)}>恢复</button>
              </div>
              <div className="skill-line skill-sub">
                <p className="skill-title">{skill.title}</p>
                <span className="spacer"></span>
                {/* When it went is the only date this row has, and the one a person is asking about. */}
                <span className="skill-updated">{'删除于 ' + skill.deleted}</span>
              </div>
            </li>
          ))}
        </ul>

        {/* No matches is a state of its own rather than an empty list: a rule with nothing under it
            reads as a page that failed to load. */}
        <p className="empty" id="skillNone" hidden={showDeleted || matched.length > 0}>
          没有匹配的 skill。换个词试试，或者清空筛选。
        </p>
        <p className="empty" id="deletedNone" hidden={!showDeleted || matched.length > 0}>
          没有删掉的 skill。
        </p>

        <Pager
          id="skillPager"
          total={matched.length}
          page={page}
          pageSize={PAGE_SIZE}
          hrefFor={(target) => {
            const copy = new URLSearchParams()
            copy.set('page', 'skills')
            if (showDeleted) copy.set('show', 'deleted')
            if (query !== '') copy.set('q', query)
            if (sort !== 'recent') copy.set('sort', sort)
            tags.forEach((id) => copy.append('tag', id))
            myTags.forEach((id) => copy.append('my-tag', id))
            publicTags.forEach((id) => copy.append('public-tag', id))
            if (target > 1) copy.set('p', String(target))
            return '?' + copy.toString()
          }}
          onGo={goToPage}
        />
      </div>

      <CommandMenu
        open={menuOpen}
        trigger={create}
        label="新建 skill"
        items={[
          {
            text: '在线编辑',
            onSelect: (trigger) => dialogs.openNewSkill('open', trigger),
          },
          {
            text: '导入本地 skill',
            onSelect: (trigger) => dialogs.openImport('list', 'open', trigger),
          },
        ]}
        onClose={(refocus) => {
          setMenuOpen(false)
          if (refocus) create?.focus()
        }}
      />
    </section>
  )
}
