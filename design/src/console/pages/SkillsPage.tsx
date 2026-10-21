/**
 * 我的 skill: a list of names with one state each — a repository list, not a dashboard.
 *
 * **Search, sort and pages are all in the address**, so a filtered, sorted, second-page list is a
 * thing somebody can send to somebody else. Typing re-renders and re-states the address, and never
 * reloads: the list is here, and a round trip per keystroke is what makes a search box feel like a
 * form.
 *
 * **Two lists, two addresses.** 「已删除」 is not a filter on the list above it: every row in it can
 * do exactly one thing — come back — and no row in the other list can do that. A filter would be a
 * control in the toolbar that changes what the page *is*.
 *
 * ⚠️ **The call counts are fixtures, and the product cannot produce them yet.** The table that was
 * to hold them — `skill_stat` — was removed from the baseline in v3. Everything else here is real:
 * the submission times, the live version of each, and the shape of every field.
 */

import { useState } from 'react'
import { replace } from '../address'
import MenuSelect from '../components/MenuSelect'
import CommandMenu from '../components/CommandMenu'
import { dialogs } from '../components/dialogs/store'
import type { DemoPageProps } from './types'

const PAGE_SIZE = 10
const SORTS = ['recent', 'calls', 'name']

interface Skill {
  name: string
  title: string
  live: string
  updated: string
  calls: number
  deleted?: string
}

const SKILLS: Skill[] = [
  { name: 'pdf-tools', title: '把 PDF 拆页、合并与压缩。', live: '1.2.3', updated: '2026/10/07 14:32', calls: 1284 },
  { name: 'commit-lint', title: '按团队约定检查提交信息，不合规的给出改法。', live: '0.4.1', updated: '2026/09/18 09:06', calls: 3160 },
  { name: 'changelog-writer', title: '从提交历史里收拾出一份能给人看的更新日志。', live: '1.0.0', updated: '2026/10/09 08:15', calls: 0 },
  { name: 'sql-review', title: '读一遍要上线的 SQL，把会锁表、会走全表扫的那几句挑出来。', live: '0.1.0', updated: '2026/04/12 10:05', calls: 0 },
  { name: 'api-doc-writer', title: '从路由定义里生成接口文档的草稿。', live: '2.0.0', updated: '2026/08/30 17:41', calls: 842 },
  { name: 'test-namer', title: '给测试用例起一个能说明它在测什么的名字。', live: '1.0.2', updated: '2026/09/02 11:20', calls: 511 },
  { name: 'log-triage', title: '把一段日志按「值得看」和「噪音」分开。', live: '0.9.0', updated: '2026/10/01 22:05', calls: 1930 },
  { name: 'regex-explainer', title: '把一条正则拆成人话。', live: '1.1.0', updated: '2026/07/14 10:02', calls: 4288 },
  { name: 'dockerfile-review', title: '检查 Dockerfile 里会拖慢构建或撑大镜像的写法。', live: '0.3.0', updated: '2026/06/28 16:55', calls: 967 },
  { name: 'i18n-extract', title: '从组件里把待翻译的字符串抽出来。', live: '1.4.0', updated: '2026/09/25 13:12', calls: 655 },
  { name: 'perf-budget', title: '给前端包体定一个不许越过的预算，并在 CI 里守住。', live: '0.1.0', updated: '2026/10/05 19:30', calls: 0 },
  { name: 'incident-notes', title: '把一次故障的排查过程整理成时间线。', live: '0.2.1', updated: '2026/05/19 08:47', calls: 274 },
  { name: 'schema-diff', title: '比较两份数据库结构，说清差在哪。', live: '1.0.0', updated: '2026/08/11 15:09', calls: 1103 },
  { name: 'css-token-audit', title: '找出散落在样式里的颜色和间距，归并成令牌。', live: '0.7.0', updated: '2026/09/09 12:33', calls: 738 },
  { name: 'flaky-test-hunter', title: '从失败记录里找出反复摇摆的那些用例。', live: '0.2.0', updated: '2026/10/08 21:14', calls: 0 },
  { name: 'env-doc', title: '把 .env 里每一项的用途问出来并写成文档。', live: '1.0.3', updated: '2026/07/02 09:58', calls: 486 },
  { name: 'migration-check', title: '上线前检查这条迁移会不会锁表。', live: '0.5.0', updated: '2026/08/22 18:26', calls: 1520 },
  { name: 'prompt-lint', title: '检查提示词里的歧义与自相矛盾。', live: '1.2.0', updated: '2026/10/03 07:44', calls: 2094 },
  { name: 'sdk-snippet', title: '从接口定义生成各语言的最小调用示例。', live: '0.1.0', updated: '2026/04/30 14:11', calls: 132 },
  { name: 'dead-code', title: '找出没人再引用的模块和导出。', live: '1.0.0', updated: '2026/06/06 11:37', calls: 876 },
  { name: 'release-notes', title: '把一版里的变更写给用户看，不是写给提交者看。', live: '2.1.0', updated: '2026/09/14 16:20', calls: 2451 },
  { name: 'proto-review', title: '审一遍 .proto 的改动，看有没有破坏兼容性。', live: '0.6.0', updated: '2026/07/27 13:05', calls: 603 },
  { name: 'bundle-triage', title: '说清这次包体涨的那 40 KB 是谁带来的。', live: '0.3.0', updated: '2026/10/06 23:51', calls: 0 },
  { name: 'cron-translator', title: '把 cron 表达式翻译成一句人话，或者反过来。', live: '1.0.0', updated: '2026/05/08 20:19', calls: 3498 },
]

/** Two rows, because one row says what a row looks like and two say what a list is. */
const DELETED: Skill[] = [
  { name: 'pdf-stamp', title: '在 PDF 的每一页角上盖一个日期。', live: '1.0.4', updated: '2026/08/19 15:40', calls: 96, deleted: '2026/10/11 09:20' },
  { name: 'slug-check', title: '提交前检查目录名与 skill 名是不是同一个。', live: '2.1.0', updated: '2026/05/02 11:08', calls: 0, deleted: '2026/09/30 17:02' },
]

const SPEC = { ariaLabel: '排序' }
const SORT_GROUPS = [{
  options: [
    { value: 'recent', label: '最近更新' },
    { value: 'calls', label: '调用最多' },
    { value: 'name', label: '名称' },
  ],
}]

export default function SkillsPage({ active, state, params }: DemoPageProps) {
  const [live, setLive] = useState<Skill[]>(SKILLS)
  const [gone, setGone] = useState<Skill[]>(DELETED)
  /* 地址里带 `new=open` 时把「新建」菜单打开：点得出来的状态也要能单独发出去。 **Only on this page**:
     all six pages are mounted, and a skill page's `new=open` belongs to its own 新建版本 menu. */
  const [menuOpen, setMenuOpen] = useState(active && params.get('new') === 'open')
  const [create, setCreate] = useState<HTMLButtonElement | null>(null)

  const showDeleted = params.get('show') === 'deleted'
  const query = params.get('q') ?? ''
  const askedSort = params.get('sort')
  const sort = askedSort && SORTS.includes(askedSort) ? askedSort : 'recent'
  const askedPage = parseInt(params.get('p') ?? '', 10)
  let page = Number.isNaN(askedPage) || askedPage < 1 ? 1 : askedPage

  const matches = (skill: Skill) =>
    query === '' || (skill.name + ' ' + skill.title).toLowerCase().includes(query.toLowerCase())

  /** Three orders. Only one needs a rule beyond the field: by calls, a skill nobody has invoked is
   *  not the top of the list — it is the bottom of it. */
  const ordered = (a: Skill, b: Skill) => {
    if (sort === 'calls') return b.calls - a.calls
    if (sort === 'name') return a.name < b.name ? -1 : a.name > b.name ? 1 : 0
    return b.updated.localeCompare(a.updated)
  }

  const matched = (showDeleted ? gone : live).filter(matches).sort(ordered)
  const pages = Math.max(1, Math.ceil(matched.length / PAGE_SIZE))
  if (page > pages) page = pages
  const start = (page - 1) * PAGE_SIZE
  const shown = matched.slice(start, start + PAGE_SIZE)

  /** The address carries the list, so a filtered, sorted, second-page list can be sent on. */
  const writeAddress = (next: { query?: string; sort?: string; page?: number }) => {
    const copy = new URLSearchParams(params)
    copy.set('page', 'skills')
    const q = next.query ?? query
    const s = next.sort ?? sort
    const p = next.page ?? page
    if (q === '') copy.delete('q'); else copy.set('q', q)
    if (s === 'recent') copy.delete('sort'); else copy.set('sort', s)
    if (p === 1) copy.delete('p'); else copy.set('p', String(p))
    replace('?' + copy.toString())
  }

  const goToPage = (number: number) => {
    writeAddress({ page: number })
    window.scrollTo(0, 0)
  }

  const restore = (skill: Skill) => {
    setGone((rows) => rows.filter((row) => row !== skill))
    setLive((rows) => [...rows, skill])
  }

  /* The pages worth showing: the ends, the one you are on, and its neighbours. A run of skipped
     pages is one gap, however many it stands for. */
  const pageNumbers = (): (number | null)[] => {
    const numbers: (number | null)[] = []
    for (let number = 1; number <= pages; number += 1) {
      if (number === 1 || number === pages || Math.abs(number - page) <= 1) numbers.push(number)
      else if (numbers[numbers.length - 1] !== null) numbers.push(null)
    }
    return numbers
  }

  const step = (label: string, target: number, off: boolean) =>
    off ? (
      <span className="is-off">{label}</span>
    ) : (
      <a
        className="pager-step"
        href={'?page=skills&p=' + target}
        onClick={(event) => { event.preventDefault(); goToPage(target) }}
      >
        {label}
      </a>
    )

  return (
    <section className="page" id="page-skills" hidden={!active}>
      <header className="page-head">
        <h1>我的 skill</h1>
        <span className="count" data-when="ready" id="skillCount" hidden={state !== 'ready'}>
          {matched.length + ' 个' + (showDeleted ? '已删除' : '')}
        </span>
        <div className="page-actions">
          <div className="toolbar" data-when="ready" hidden={state !== 'ready'}>
            <label className="search">
              <span className="visually-hidden">搜索</span>
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
                <line x1="15.4" y1="15.4" x2="20.6" y2="20.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              </svg>
              <input
                id="skillQuery"
                className="control"
                type="search"
                autoComplete="off"
                spellCheck={false}
                placeholder="搜索名字或说明"
                value={query}
                onChange={(event) => writeAddress({ query: event.target.value, page: 1 })}
              />
            </label>
            <label className="sort">
              <span className="visually-hidden">排序</span>
              <span id="skillSortSlot">
                <MenuSelect
                  groups={SORT_GROUPS}
                  value={sort}
                  config={{
                    ...SPEC,
                    onPick: (next) => {
                      writeAddress({ sort: next, page: 1 })
                    },
                  }}
                />
              </span>
            </label>
          </div>
          {/* **新建 skill 也是一颗带菜单的按钮（v9）。** 搜索与排序在空态里没有意义，所以它们仍然
              不画；这一颗是空态里唯一的下一步，因此它自己带着 `empty`。 */}
          <button
            type="button"
            className="button menu-button"
            id="skillsCreate"
            data-when="ready empty"
            hidden={state !== 'ready' && state !== 'empty'}
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

      <nav
        className="skill-tabs list-tabs"
        id="listTabs"
        aria-label="我的 skill 的两份列表"
        data-when="ready"
        hidden={state !== 'ready'}
      >
        {/* **Only the page that is up marks a tab current.** All six pages are mounted, and a hidden
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

      <p className="loading" data-when="loading" hidden={state !== 'loading'}>加载中…</p>

      <div className="banner" data-when="failed" hidden={state !== 'failed'}>读不到你的 skill 列表，稍后再试一次。</div>

      <p className="empty" data-when="empty" hidden={state !== 'empty'}>
        还没有 skill。新建一个，或运行 <code>skillmaster skill submit</code>。
      </p>

      <div className="list-body" data-when="ready" hidden={state !== 'ready'}>
        <ul className="skills" id="skillList" hidden={showDeleted}>
          {!showDeleted && shown.map((skill) => (
            <li className="skill" key={skill.name}>
              <div className="skill-line">
                <a className="skill-name" href="?page=skill">{skill.name}</a>
                <span className="chip is-live">
                  <i className="dot"></i>
                  <span className="chip-text">{'已上线 @' + skill.live}</span>
                </span>
                <span className="spacer"></span>
                <span className="skill-calls">{skill.calls.toLocaleString('zh-CN') + ' 次调用'}</span>
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
          没有匹配的 skill。换个词试试，或者清空搜索。
        </p>
        <p className="empty" id="deletedNone" hidden={!showDeleted || matched.length > 0}>
          没有删掉的 skill。
        </p>

        <nav className="pager" id="skillPager" aria-label="分页">
          {matched.length > PAGE_SIZE && (
            <>
              <span className="pager-count">
                {shown.length === 0
                  ? '共 ' + matched.length + ' 个'
                  : '共 ' + matched.length + ' 个，第 ' + (start + 1) + '–' + (start + shown.length) + ' 个'}
              </span>
              {step('上一页', page - 1, page === 1)}
              {pageNumbers().map((number, index) => {
                if (number === null) return <span className="is-gap" key={'gap' + index}>…</span>
                if (number === page) {
                  return <span className="is-current" aria-current="page" key={number}>{String(number)}</span>
                }
                return (
                  <a
                    className="pager-page"
                    key={number}
                    href={'?page=skills' + (showDeleted ? '&show=deleted' : '') + '&p=' + number}
                    onClick={(event) => { event.preventDefault(); goToPage(number) }}
                  >
                    {String(number)}
                  </a>
                )
              })}
              {step('下一页', page + 1, page === pages)}
            </>
          )}
        </nav>
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
