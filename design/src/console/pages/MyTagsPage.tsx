/**
 * 我的标签：那套**只有本人看得见**的词，以及每个词用在几个 skill 上。
 *
 * **这一页管的不是「贴」，是「定义」。** 贴在某一个 skill 上是信息页的事；这里改名与删除，改的是
 * 词本身，所以每一行是一句话：「用在 N 个 skill 上」。N 只算生效中的贴法（PRD §展示与计数口径），
 * 休眠的那些不算——但定义还在，删掉它的时候会连它们一起清除。
 *
 * **一页两份清单**（2026-10-23 用户定）：`我的标签` 是下面这一份（私有、点得动）；`公开标签` 是
 * 作者在 skill 上贴的召回词，只做集中查看——名字、用在几个自己的 skill 上、通往它们的入口。
 * 贴与摘仍在信息页，改名或合并改的是所有人看到的内容，那是以后与审核一起做的事。
 *
 * **顺序是固定的：计数降序，同数按名字。** 没有排序控件——一个只按「用得多少」读的列表，
 * 排序不是这一页的问题。
 *
 * **清单本身点得动（v14 第三轮，2026-10-22 用户选定「点得动的清单」）。** 页头一个搜索框，筛的是
 * 名字；点名字就地改名，回车保存、Esc 或点到别处取消；「用在 N 个 skill 上」是去库里筛这些 skill
 * 的那条链接；新建也是一行，落在清单最上面，回车新建、接着可以再打一个。**只有删除仍走红色确认**
 * ——它是这一页唯一不可逆的动作。
 *
 * 三种点出来的样子（新建 / 改名 / 删除）仍然能由地址直接打开，点出来的状态也要能单独发出去。
 */

import { useEffect, useRef, useState } from 'react'
import { replace } from '../address'
import SearchBox from '../components/SearchBox'
import { dialogs } from '../components/dialogs/store'
import {
  CATEGORIES,
  MAX_TAG_DESCRIPTION,
  MAX_TAG_NAME,
  MAX_TAGS_PER_ACCOUNT,
  MY_TAGS,
  fullTagSet,
  myTagDormantUsage,
  myTagUsage,
  normalizeTagName,
  sortedMyTags,
  sortedPublicTags,
} from '../fixtures/library'
import type { MyTag } from '../fixtures/library'
import type { DemoPageProps } from './types'

const COUNT = (n: number) => '用在 ' + n + ' 个 skill 上'

/** 按 Unicode 码点算，不是 UTF-16 单元：一个汉字是一个字。 */
const points = (value: string) => Array.from(value)

export default function MyTagsPage({ active, state, params }: DemoPageProps) {
  const [definitions, setDefinitions] = useState<MyTag[]>(
    () => (state === 'empty'
      ? []
      : state === 'full' ? fullTagSet() : MY_TAGS.map((tag) => ({ ...tag }))),
  )
  const [creating, setCreating] = useState(false)
  const [newName, setNewName] = useState('')
  const [newDesc, setNewDesc] = useState('')
  const [newError, setNewError] = useState('')
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editName, setEditName] = useState('')
  const [editError, setEditError] = useState('')
  const [describingId, setDescribingId] = useState<string | null>(null)
  const [descDraft, setDescDraft] = useState('')
  const newNameInput = useRef<HTMLInputElement>(null)
  const newDescInput = useRef<HTMLInputElement>(null)

  const query = params.get('q') ?? ''
  const atLimit = definitions.length >= MAX_TAGS_PER_ACCOUNT
  const settled = state !== 'loading' && state !== 'failed'
  /* **两页签**（2026-10-23 用户定）：我的标签是这一页原来的清单；公开标签只列我在自己的 skill
     上用过的词，贴与摘仍在 skill 的信息页。读不到数据时不画页签——页签是两份答案之间的切换，
     没有答案就没有可切的东西。 */
  const tab = settled && params.get('tab') === 'public' ? 'public' : 'private'
  const listShown = state === 'ready' || state === 'dormant' || state === 'full'
  const dormantOnly = state === 'dormant'
  /* 空态里也能新建：那一行落在清单的位置上，空态那句同时收起来。 */
  const createShown = creating && settled && (listShown || state === 'empty')

  /** 地址是这个演示的状态机：搜索、正在改名的那一行、新建那一行都写进去，点出来的样子能单独发出去。 */
  const writeAddress = (patch: {
    query?: string
    rename?: string | null
    describe?: string | null
    creating?: boolean
  }) => {
    const copy = new URLSearchParams(params)
    copy.set('page', 'my-tags')
    copy.delete('delete')
    if (patch.query !== undefined) {
      if (patch.query === '') copy.delete('q')
      else copy.set('q', patch.query)
    }
    if (patch.rename !== undefined) {
      if (patch.rename === null) copy.delete('rename')
      else copy.set('rename', patch.rename)
    }
    if (patch.describe !== undefined) {
      if (patch.describe === null) copy.delete('describe')
      else copy.set('describe', patch.describe)
    }
    if (patch.creating !== undefined) {
      if (patch.creating) copy.set('new', '1')
      else copy.delete('new')
    }
    if (copy.toString() !== params.toString()) replace('?' + copy.toString())
  }

  /** 名字的拒绝在这一层判：它知道现在有哪些词、公开分类叫什么。 */
  const refusal = (name: string, except: string | null): string | null => {
    if (name === '') return '给这个标签起一个名字。'
    if (points(name).length > MAX_TAG_NAME) return '名字不能超过 ' + MAX_TAG_NAME + ' 个字符。'
    if (CATEGORIES.some((category) => category.name.toLowerCase() === name.toLowerCase())) {
      return '「' + name + '」是一个分类，换一个名字。'
    }
    if (definitions.some((tag) => tag.id !== except && tag.name.toLowerCase() === name.toLowerCase())) {
      return '已经有一个叫「' + name + '」的标签了。'
    }
    return null
  }

  const beginRename = (tag: MyTag) => {
    setCreating(false)
    setNewError('')
    setDescribingId(null)
    setDescDraft('')
    setEditingId(tag.id)
    setEditName(tag.name)
    setEditError('')
  }

  const endRename = () => {
    setEditingId(null)
    setEditName('')
    setEditError('')
    writeAddress({ rename: null })
  }

  const commitRename = (tag: MyTag) => {
    const name = normalizeTagName(editName)
    const refused = refusal(name, tag.id)
    if (refused !== null) {
      setEditError(refused)
      return
    }
    setDefinitions((list) => list.map((row) => (row.id === tag.id ? { ...row, name } : row)))
    endRename()
  }

  /** **说明是标签自己的另一句，不是名字的一部分**：它说什么时候该贴这个标签，改它不影响贴法。 */
  const beginDesc = (tag: MyTag) => {
    setCreating(false)
    setNewError('')
    setEditingId(null)
    setEditError('')
    setDescribingId(tag.id)
    setDescDraft(tag.description ?? '')
  }

  const endDesc = () => {
    setDescribingId(null)
    setDescDraft('')
    writeAddress({ describe: null })
  }

  const commitDesc = (tag: MyTag) => {
    const description = descDraft.trim()
    setDefinitions((list) => list.map((row) => (row.id === tag.id ? { ...row, description } : row)))
    endDesc()
  }

  const beginCreate = () => {
    if (!settled || atLimit) return
    setEditingId(null)
    setEditError('')
    setDescribingId(null)
    setDescDraft('')
    setCreating(true)
    setNewName('')
    setNewDesc('')
    setNewError('')
    writeAddress({ creating: true, rename: null, describe: null })
  }

  const endCreate = () => {
    setCreating(false)
    setNewName('')
    setNewDesc('')
    setNewError('')
    writeAddress({ creating: false })
  }

  /** 新建那一行有两格：名字回车落到说明，说明回车收下；收下之后两格清空、焦点回到名字。 */
  const commitCreate = () => {
    const name = normalizeTagName(newName)
    const refused = refusal(name, null)
    if (refused !== null) {
      setNewError(refused)
      newNameInput.current?.focus()
      return
    }
    if (atLimit) {
      setNewError('标签最多 ' + MAX_TAGS_PER_ACCOUNT + ' 个。删掉一个，才能新建。')
      return
    }
    const description = newDesc.trim()
    setDefinitions((list) => list.concat({ id: 'tag-' + Date.now(), name, description }))
    setNewName('')
    setNewDesc('')
    setNewError('')
    newNameInput.current?.focus()
  }

  const askDelete = (tag: MyTag) => {
    const visible = myTagUsage(tag.id)
    const sleeping = myTagDormantUsage(tag.id)
    /* **先说后果，两个答案各自说清去向。** M 为 0 时那句不写「另有 0 个」——一句话里没有一个
       信息的时候，它只是让句子更长。 */
    /* **只说你会失去什么**（2026-10-23 用户定）：删掉的不只是这一行，还有那些贴法，而且
       找不回来。「名字可以重新使用」是系统那边的性质，不是你会遇到的事——那句话删掉。 */
    const body = sleeping > 0
      ? '删除后，这个标签会从 ' + visible + ' 个你能看到的 skill 上移除，不能恢复；另有 ' + sleeping +
        ' 个 skill 上的这个标签你现在看不到，也会一起删除。那些 skill 本身不会被删除。'
      : '删除后，这个标签会从 ' + visible + ' 个你能看到的 skill 上移除，不能恢复。'
    dialogs.openDanger({
      title: '删除标签？',
      body,
      confirmLabel: '确认删除标签',
      danger: true,
      onConfirm: () => {
        setDefinitions((list) => list.filter((row) => row.id !== tag.id))
      },
    })
  }

  /* **地址能直接打开四种样子**：新建、改名、写说明、删除。只在进入这一页时读一次。 */
  useEffect(() => {
    if (!active || tab !== 'private') return
    if (params.get('new') === '1' && settled && !atLimit) beginCreate()
    const askedRename = params.get('rename')
    if (askedRename !== null) {
      const tag = definitions.find((row) => row.id === askedRename)
      if (tag) beginRename(tag)
    }
    const askedDescribe = params.get('describe')
    if (askedDescribe !== null) {
      const tag = definitions.find((row) => row.id === askedDescribe)
      if (tag) beginDesc(tag)
    }
    const askedDelete = params.get('delete')
    if (askedDelete !== null) {
      const tag = definitions.find((row) => row.id === askedDelete)
      if (tag) askDelete(tag)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active])

  const lower = query.trim().toLowerCase()
  /* **只有休眠那一态按显示计数排序**：每一行显示的「用在」都是 0，再按夹具里的真实计数排，
     就和屏幕上写的数字对不上；此时顺序退回名字（PRD §展示与计数口径）。 */
  const ordered = dormantOnly
    ? [...definitions].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
    : sortedMyTags(definitions)
  const shown = ordered.filter((tag) =>
    lower === '' ||
    tag.name.toLowerCase().includes(lower) ||
    (tag.description ?? '').toLowerCase().includes(lower))
  /* **两份清单各有各的空态**（PRD #11）：0 个我的标签定义，不代表这个账号没有公开标签；
     公开标签这一份始终读自己的数据，「空」由 `tab=public&state=empty` 单独给。 */
  const publicEmpty = params.get('public') === 'empty'
  const publicAll = publicEmpty ? [] : sortedPublicTags()
  const publicShown = publicAll.filter((tag) => lower === '' || tag.name.toLowerCase().includes(lower))

  return (
    <section className="page" id="page-my-tags" hidden={!active}>
      <header className="page-head">
        <h1>标签</h1>
        <span className="count" hidden={tab === 'public' ? !settled : !listShown}>
          {(tab === 'public' ? publicShown.length : shown.length) + ' 个'}
        </span>
        <div className="page-actions">
          {/* 搜索只在读到了清单时才有意义：空态、加载中、失败都没有词可筛。 */}
          {tab === 'public'
            ? settled && (
              <SearchBox
                id="tagQuery"
                value={query}
                placeholder="搜索公开标签"
                label="搜索公开标签"
                onChange={(value) => writeAddress({ query: value })}
              />
            )
            : listShown && (
              <SearchBox
                id="tagQuery"
                value={query}
                placeholder="搜索名字或说明"
                label="搜索名字或说明"
                onChange={(value) => writeAddress({ query: value })}
              />
            )}
          {/* **公开标签不在这一页新建**：它们是作者在 skill 上贴的词，入口在信息页。 */}
          {tab === 'private' && (
            <button
              type="button"
              className="button"
              id="createTag"
              disabled={!settled || atLimit}
              title={atLimit ? '标签最多 ' + MAX_TAGS_PER_ACCOUNT + ' 个' : undefined}
              onClick={beginCreate}
            >
              新建标签
            </button>
          )}
        </div>
      </header>

      {/* **一份管理页，两份清单。** 页头说的是这一整页（「标签」），页签说的是其中哪一份：
          我的标签是私有整理，公开标签是作者的召回词。 */}
      <nav className="skill-tabs list-tabs" id="tagTabs" aria-label="标签的两份清单" hidden={!settled}>
        <a
          className={'skill-tab' + (tab === 'private' ? ' is-current' : '')}
          data-tab="private"
          href="?page=my-tags"
          aria-current={tab === 'private' ? 'page' : undefined}
        >
          我的标签
        </a>
        <a
          className={'skill-tab' + (tab === 'public' ? ' is-current' : '')}
          data-tab="public"
          href="?page=my-tags&tab=public"
          aria-current={tab === 'public' ? 'page' : undefined}
        >
          公开标签
        </a>
      </nav>

      {tab === 'private' && <>

      <p className="tag-note" id="tagLimit" hidden={!atLimit || !settled}>
        标签最多 {MAX_TAGS_PER_ACCOUNT} 个。删掉一个，才能新建。
      </p>

      <p className="loading" data-when="loading" hidden={state !== 'loading'}>加载中…</p>
      <div className="banner" data-when="failed" hidden={state !== 'failed'}>
        无法加载你的标签，请稍后重试。
      </div>

      <p className="empty" data-when="empty" hidden={state !== 'empty' || creating}>
        还没有标签。新建一个，用来归类你库里的 skill；只有你看得到。
      </p>

      <div className="list-body" data-when="ready" hidden={!listShown && !createShown}>
        {/* 只有休眠：定义都在，生效中的 0 个。说人话——不说「全部休眠」。 */}
        <p className="tag-note" id="tagDormant" hidden={!dormantOnly}>
          这些标签对应的 skill 现在都看不到（可能已下线或暂时不可见）。skill 回来时，标签会重新出现。
        </p>

        <ul className="skills" id="tagList" hidden={shown.length === 0 && !createShown}>
          {/* 新建那一行在清单最上面：一行小表单——名字、说明、取消、新建。
              回车仍然一路走到底：名字里回车落到说明，说明里回车收下。**焦点离开整块才收起。** */}
          {createShown && (
            <li
              className="skill tag-row is-new"
              data-tag="new"
              onBlur={(event) => {
                if (event.currentTarget.contains(event.relatedTarget as Node | null)) return
                endCreate()
              }}
            >
              <div className="tag-create">
                <input
                  id="newTagName"
                  className="control tag-create-name"
                  type="text"
                  autoFocus
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="新标签的名字"
                  aria-label="新标签的名字"
                  ref={newNameInput}
                  value={newName}
                  onChange={(event) => {
                    setNewName(points(event.target.value).slice(0, MAX_TAG_NAME).join(''))
                    setNewError('')
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') {
                      event.preventDefault()
                      if (normalizeTagName(newName) === '') setNewError('给这个标签起一个名字。')
                      else newDescInput.current?.focus()
                    } else if (event.key === 'Escape') {
                      event.preventDefault()
                      endCreate()
                    }
                  }}
                />
                <input
                  id="newTagDescription"
                  className="control tag-create-desc"
                  type="text"
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="给 agent 看的一句：什么时候该贴它（选填）"
                  aria-label={'新标签的说明（选填，最多 ' + MAX_TAG_DESCRIPTION + ' 个字符）'}
                  ref={newDescInput}
                  value={newDesc}
                  onChange={(event) => {
                    setNewDesc(points(event.target.value).slice(0, MAX_TAG_DESCRIPTION).join(''))
                    setNewError('')
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') {
                      event.preventDefault()
                      commitCreate()
                    } else if (event.key === 'Escape') {
                      event.preventDefault()
                      endCreate()
                    }
                  }}
                />
                <span className="tag-create-acts">
                  <button type="button" className="button is-quiet" id="tagCreateCancel" onClick={endCreate}>
                    取消
                  </button>
                  <button
                    type="button"
                    className="button"
                    id="tagCreateAccept"
                    disabled={normalizeTagName(newName) === ''}
                    onClick={commitCreate}
                  >
                    新建
                  </button>
                </span>
              </div>
              {newError !== '' && <p className="tag-edit-error" role="alert">{newError}</p>}
            </li>
          )}

          {shown.map((tag) => {
            const description = tag.description ?? ''
            return (
              <li className="skill tag-row" key={tag.id} data-tag={tag.id}>
                {/* **一行四格：名字 / 说明 / 计数 / 删除。** 说明吃掉名字与计数之间那段空白；
                    窄了（或网页缩放）它才落到名字下面那一行——见 styles.css 的 720 断点。 */}
                <div className="skill-line">
                  {editingId === tag.id ? (
                    <input
                      id="tagName"
                      className="tag-name-input"
                      type="text"
                      autoFocus
                      autoComplete="off"
                      spellCheck={false}
                      aria-label="标签的名字"
                      value={editName}
                      onChange={(event) => {
                        setEditName(points(event.target.value).slice(0, MAX_TAG_NAME).join(''))
                        setEditError('')
                      }}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          event.preventDefault()
                          commitRename(tag)
                        } else if (event.key === 'Escape') {
                          event.preventDefault()
                          endRename()
                        }
                      }}
                      onBlur={endRename}
                    />
                  ) : (
                    <button
                      type="button"
                      className="tag-name is-editable"
                      title="点一下改名"
                      onClick={() => {
                        beginRename(tag)
                        writeAddress({ rename: tag.id, describe: null })
                      }}
                    >
                      {tag.name}
                    </button>
                  )}

                  {/* 改名那一刻说明先让开：输入框要借它那一格，够 24 个字的名字。 */}
                  {editingId !== tag.id && (describingId === tag.id ? (
                    <input
                      id="tagDescription"
                      className="tag-desc-input"
                      type="text"
                      autoFocus
                      autoComplete="off"
                      spellCheck={false}
                      placeholder="加一句说明"
                      aria-label={'标签的说明（选填，最多 ' + MAX_TAG_DESCRIPTION + ' 个字符）'}
                      value={descDraft}
                      onChange={(event) => {
                        setDescDraft(points(event.target.value).slice(0, MAX_TAG_DESCRIPTION).join(''))
                      }}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          event.preventDefault()
                          commitDesc(tag)
                        } else if (event.key === 'Escape') {
                          event.preventDefault()
                          endDesc()
                        }
                      }}
                      onBlur={endDesc}
                    />
                  ) : (
                    <button
                      type="button"
                      className={'tag-desc' + (description === '' ? ' is-empty' : '')}
                      title="点一下写说明"
                      onClick={() => {
                        beginDesc(tag)
                        writeAddress({ describe: tag.id, rename: null })
                      }}
                    >
                      {description === '' ? '加一句说明' : description}
                    </button>
                  ))}

                  {/* **计数是入口，不是装饰**：它说的是「这些 skill」，那就把它做成去那些 skill 的链接。 */}
                  <a className="tag-count" href={'?page=skills&my-tag=' + tag.id}>
                    {COUNT(dormantOnly ? 0 : myTagUsage(tag.id))}
                  </a>
                  <span className="tag-acts">
                    <button type="button" className="link-button is-danger" onClick={() => askDelete(tag)}>删除</button>
                  </span>
                </div>

                {editError !== '' && editingId === tag.id && (
                  <p className="tag-edit-error" role="alert">{editError}</p>
                )}
              </li>
            )
          })}
        </ul>

        {/* 「0 个定义」与「0 个生效」是两件事，两句话也不一样。搜索没有命中是第三件事。 */}
        <p className="empty" id="tagNone" hidden={dormantOnly || definitions.length > 0 || state !== 'ready'}>
          还没有标签。新建一个，用来归类你库里的 skill；只有你看得到。
        </p>
        <p className="empty" id="tagNoMatch" hidden={lower === '' || shown.length > 0 || !listShown}>
          没有匹配的标签。换个词试试。
        </p>
      </div>
      </>}

      {/* **公开标签那一份是只读的**：这里只做集中查看与去 skill 的入口，贴与摘在信息页。
          改名或合并会改所有人看到的内容，那是以后与审核一起做的事（PRD §公开标签）。 */}
      {tab === 'public' && (
        <div className="list-body" id="publicTagPanel">
          <p className="tag-note" id="publicTagNote">
            公开标签是你在自己创建的 skill 上贴的词，所有人看得到，搜索靠它找回那个 skill。贴与摘在 skill 的信息页。
          </p>
          <ul className="skills" id="publicTagList" hidden={publicShown.length === 0}>
            {publicShown.map((tag) => (
              <li className="skill" key={tag.id} data-public-tag={tag.id}>
                <div className="skill-line">
                  <span className="tag-name">{'#' + tag.name}</span>
                  <span className="spacer"></span>
                  <a className="tag-count" href={'?page=skills&public-tag=' + tag.id}>
                    {COUNT(tag.count)}
                  </a>
                </div>
              </li>
            ))}
          </ul>
          {publicShown.length === 0 && (
            <p className="empty" id="publicTagNone">
              {publicAll.length === 0
                ? '还没有公开标签。在你创建的 skill 的信息页贴上一个，它会出现在这里。'
                : '没有匹配的公开标签。换个词试试。'}
            </p>
          )}
        </div>
      )}
    </section>
  )
}
