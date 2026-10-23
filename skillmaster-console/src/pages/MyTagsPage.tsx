import { useEffect, useRef, useState } from 'react'
import { addressFor, go, query as pageQuery, replaceAddress } from '../address'
import {
  createMyTag,
  deleteMyTag,
  describeMyTag,
  listCategories,
  listMyTags,
  listPublicTagUsages,
  renameMyTag,
} from '../api/library'
import { codeMessage } from '../api/errors'
import type { MyTagUsage, PublicTagUsage } from '../api/types'
import { loginPath } from '../login'
import ConfirmDialog from '../components/ConfirmDialog'
import SearchBox from '../components/SearchBox'
import TagCreateRow from '../components/tags/TagCreateRow'
import TagDefinitionRow from '../components/tags/TagDefinitionRow'
import {
  MAX_TAGS_PER_ACCOUNT,
  MAX_TAG_NAME,
  normalizeTagName,
  pointLength,
  tagKey,
} from '../tagRules'

/**
 * 标签: the private words that organise one person's library, and the author's public words beside
 * them.
 *
 * **This page manages definitions, not attachments.** Attaching a word to a skill happens on that
 * skill's information page; renaming or deleting here changes the word itself, which is why a row
 * says 「用在 N 个 skill 上」 and why only deletion — the one irreversible action — opens a red sheet.
 *
 * One page, two lists. 我的标签 is the private, editable one; 公开标签 is a read-only view of the
 * words written on the caller's own live skills. The page and the navigation stay 「标签」.
 *
 * The order is fixed: attachment count descending, then name. There is no sort control — this list is
 * read by "how much is it used".
 */
const COUNT = (n: number): string => '用在 ' + n + ' 个 skill 上'

type PrivateState = 'loading' | 'ready' | 'failed' | 'empty' | 'dormant' | 'full'
type PublicState = 'loading' | 'ready' | 'failed'
type Tab = 'private' | 'public'

/** The settled state of a private list: empty, all dormant, full, or ordinary. */
function stateFor(tags: MyTagUsage[]): PrivateState {
  if (tags.length === 0) {
    return 'empty'
  }
  if (tags.length >= MAX_TAGS_PER_ACCOUNT) {
    return 'full'
  }
  // "All dormant" needs an actual dormant attachment. A definition nobody has used yet also has
  // zero effective uses, but it is not asleep — saying so would claim its skills are invisible,
  // and there are no skills. The server's `dormant_in` is the fact that separates the two.
  if (tags.every((tag) => tag.used_in === 0) && tags.some((tag) => tag.dormant_in > 0)) {
    return 'dormant'
  }
  return 'ready'
}

/** The address is this page's state machine: search, the row being renamed, the create row, delete. */
function addressOf(patch: {
  query?: string
  tab?: Tab
  rename?: string | null
  describe?: string | null
  creating?: boolean
  deleting?: string | null
}): string {
  const params = new URLSearchParams()
  if (patch.tab === 'public') {
    params.set('tab', 'public')
  }
  if (patch.query !== undefined && patch.query !== '') {
    params.set('q', patch.query)
  }
  if (patch.rename !== undefined && patch.rename !== null) {
    params.set('rename', patch.rename)
  }
  if (patch.describe !== undefined && patch.describe !== null) {
    params.set('describe', patch.describe)
  }
  if (patch.creating === true) {
    params.set('new', '1')
  }
  if (patch.deleting !== undefined && patch.deleting !== null) {
    params.set('delete', patch.deleting)
  }
  return addressFor('/my-tags', params)
}

export default function MyTagsPage() {
  const initial = useRef(pageQuery()).current
  const [query, setQuery] = useState(() => initial.get('q') ?? '')

  const [definitions, setDefinitions] = useState<MyTagUsage[]>([])
  const [state, setState] = useState<PrivateState>('loading')
  const [publicTags, setPublicTags] = useState<PublicTagUsage[]>([])
  const [publicState, setPublicState] = useState<PublicState>('loading')

  const [creating, setCreating] = useState(false)
  const [newName, setNewName] = useState('')
  const [newDesc, setNewDesc] = useState('')
  const [newError, setNewError] = useState('')
  const [createPending, setCreatePending] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editName, setEditName] = useState('')
  // Keyed by row: a refusal belongs to the definition it was about, even if the person has since
  // opened another row's editor.
  const [editError, setEditError] = useState<{ id: string; message: string } | null>(null)
  const [describingId, setDescribingId] = useState<string | null>(null)
  const [descDraft, setDescDraft] = useState('')
  /** The row whose rename/describe request is in flight; its input is read-only until it lands. */
  const [savingId, setSavingId] = useState<string | null>(null)
  const [deleting, setDeleting] = useState<MyTagUsage | null>(null)
  const [deletePending, setDeletePending] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const newNameInput = useRef<HTMLInputElement | null>(null)
  const newDescInput = useRef<HTMLInputElement | null>(null)
  // The row that is open *now*, read by an in-flight save when it lands. State captured in the
  // handler is the row it started on; without these a slow rename would close the editor somebody
  // opened in the meantime.
  const editingIdRef = useRef<string | null>(null)
  const describingIdRef = useRef<string | null>(null)
  // A synchronous "a save is in flight" latch: state updates are asynchronous, so two Enters in
  // one tick would both read the old value and send two PATCHes.
  const savingRef = useRef(false)
  editingIdRef.current = editingId
  describingIdRef.current = describingId

  /** The platform vocabulary, for the "this is a category" pre-check. */
  const [categoryNames, setCategoryNames] = useState<string[]>([])

  const settled = state !== 'loading' && state !== 'failed'
  // The address asks for a tab; the page answers only once the private list has settled. If that
  // read failed there is no second list to switch to (the tab strip is hidden), so the page stays
  // on 我的标签 and shows the reason — the design's rule, and the only way back to it.
  const tab: Tab = settled && initial.get('tab') === 'public' ? 'public' : 'private'
  const atLimit = definitions.length >= MAX_TAGS_PER_ACCOUNT
  const listShown = state === 'ready' || state === 'dormant' || state === 'full'
  const dormantOnly = state === 'dormant'
  const createShown = creating && settled && (listShown || state === 'empty')

  /* ------------------------------------------------------------------- reads */

  const reload = async (): Promise<void> => {
    setState('loading')
    const result = await listMyTags()
    if (!result.ok) {
      if (result.code === 'unauthenticated') {
        go(loginPath())
        return
      }
      setState('failed')
      return
    }
    const tags = Array.isArray(result.data.tags) ? result.data.tags : []
    setDefinitions(tags)
    setState(stateFor(tags))
  }

  useEffect(() => {
    void reload()
    void (async () => {
      const result = await listCategories()
      if (result.ok && Array.isArray(result.data.categories)) {
        setCategoryNames(result.data.categories.map((category) => category.name))
      }
    })()
    // The page's two reads; tab switches are full loads and read their own list.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (tab !== 'public') {
      return
    }
    let cancelled = false
    void (async () => {
      const result = await listPublicTagUsages()
      if (cancelled) {
        return
      }
      if (!result.ok && result.code === 'unauthenticated') {
        go(loginPath())
        return
      }
      setPublicTags(result.ok && Array.isArray(result.data.tags) ? result.data.tags : [])
      setPublicState(result.ok ? 'ready' : 'failed')
    })()
    return () => {
      cancelled = true
    }
  }, [tab])

  /* --------------------------------------------------------------- address opens */

  const opened = useRef(false)
  useEffect(() => {
    if (opened.current || !settled || tab !== 'private') {
      return
    }
    opened.current = true
    if (initial.get('new') === '1' && !atLimit) {
      beginCreate()
    }
    const askedRename = initial.get('rename')
    if (askedRename !== null) {
      const tag = definitions.find((row) => row.id === askedRename)
      if (tag) {
        beginRename(tag)
      }
    }
    const askedDescribe = initial.get('describe')
    if (askedDescribe !== null) {
      const tag = definitions.find((row) => row.id === askedDescribe)
      if (tag) {
        beginDesc(tag)
      }
    }
    const askedDelete = initial.get('delete')
    if (askedDelete !== null) {
      const tag = definitions.find((row) => row.id === askedDelete)
      if (tag) {
        askDelete(tag)
      }
    }
    // Read once, when the list first settles.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settled])

  /* ------------------------------------------------------------------ the edits */

  /** The name rules this layer can judge; the server owns the rest (NFKC and the category list). */
  const refusal = (name: string, except: string | null): string | null => {
    if (name === '') {
      return '给这个标签起一个名字。'
    }
    if (pointLength(name) > MAX_TAG_NAME) {
      return '名字不能超过 ' + MAX_TAG_NAME + ' 个字符。'
    }
    if (categoryNames.some((category) => tagKey(category) === tagKey(name))) {
      return '「' + name + '」是一个分类，换一个名字。'
    }
    const lower = tagKey(name)
    if (definitions.some((tag) => tag.id !== except && tagKey(tag.name) === lower)) {
      return '已经有一个叫「' + name + '」的标签了。'
    }
    return null
  }

  const beginRename = (tag: MyTagUsage) => {
    setCreating(false)
    setNewError('')
    setDescribingId(null)
    setDescDraft('')
    setEditingId(tag.id)
    setEditName(tag.name)
    setEditError(null)
  }

  const endRename = () => {
    const row = editingIdRef.current
    setEditingId(null)
    setEditName('')
    setEditError((current) => (current?.id === row ? null : current))
    replaceAddress(addressOf({ query, tab, describe: describingId, creating }))
  }

  const commitRename = async (tag: MyTagUsage): Promise<void> => {
    if (savingRef.current) {
      return
    }
    const name = normalizeTagName(editName)
    const refused = refusal(name, tag.id)
    if (refused !== null) {
      setEditError({ id: tag.id, message: refused })
      return
    }
    savingRef.current = true
    setSavingId(tag.id)
    const result = await renameMyTag(tag.id, name)
    savingRef.current = false
    setSavingId(null)
    if (!result.ok) {
      setEditError({
        id: tag.id,
        message: codeMessage(result.code, result.message, result.details),
      })
      return
    }
    // The server's spelling is the one it stored (NFKC); echoing the typed word could show a
    // different form until the next reload.
    setDefinitions((list) =>
      list.map((row) => (row.id === tag.id ? { ...row, name: result.data.name ?? name } : row)),
    )
    // Only close the editor this save belongs to: somebody may have opened another row while the
    // request was in flight, and their editor is not this save's to close.
    if (editingIdRef.current === tag.id) {
      endRename()
    }
  }

  const beginDesc = (tag: MyTagUsage) => {
    setCreating(false)
    setNewError('')
    setEditingId(null)
    setEditError(null)
    setDescribingId(tag.id)
    setDescDraft(tag.description ?? '')
  }

  const endDesc = () => {
    const row = describingIdRef.current
    setDescribingId(null)
    setDescDraft('')
    setEditError((current) => (current?.id === row ? null : current))
    replaceAddress(addressOf({ query, tab, rename: editingId, creating }))
  }

  const commitDesc = async (tag: MyTagUsage): Promise<void> => {
    if (savingRef.current) {
      return
    }
    const description = descDraft.trim()
    savingRef.current = true
    setSavingId(tag.id)
    const result = await describeMyTag(tag.id, description)
    savingRef.current = false
    setSavingId(null)
    if (!result.ok) {
      setEditError({
        id: tag.id,
        message: codeMessage(result.code, result.message, result.details),
      })
      return
    }
    setDefinitions((list) =>
      list.map((row) => (row.id === tag.id ? { ...row, description: description === '' ? null : description } : row)),
    )
    if (describingIdRef.current === tag.id) {
      endDesc()
    }
  }

  const beginCreate = () => {
    if (!settled || atLimit) {
      return
    }
    setEditingId(null)
    setEditError(null)
    setDescribingId(null)
    setDescDraft('')
    setCreating(true)
    setNewName('')
    setNewDesc('')
    setNewError('')
    replaceAddress(addressOf({ query, tab, creating: true }))
  }

  const endCreate = () => {
    setCreating(false)
    setNewName('')
    setNewDesc('')
    setNewError('')
    replaceAddress(addressOf({ query, tab }))
  }

  /** Name Enter moves to the description; description Enter accepts, clears, and returns to the name. */
  const commitCreate = async (): Promise<void> => {
    // Two Enters must not create two definitions: one request is in flight until it lands.
    if (createPending) {
      return
    }
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
    setCreatePending(true)
    const result = await createMyTag(name, description === '' ? undefined : description)
    setCreatePending(false)
    if (!result.ok) {
      setNewError(codeMessage(result.code, result.message, result.details))
      return
    }
    const created = result.data
    if (created === undefined || typeof created.id !== 'string') {
      // A server that answers without the row: re-read rather than invent one client-side.
      await reload()
    } else {
      const next = definitions.concat({
        id: created.id,
        name: created.name ?? name,
        description: created.description ?? (description === '' ? null : description),
        used_in: created.used_in ?? 0,
        dormant_in: 0,
      })
      setDefinitions(next)
      setState(stateFor(next))
    }
    // Clear only the row that was submitted: text typed while the request was in flight is the
    // next definition, not this one's leftovers.
    setNewName((current) => (normalizeTagName(current) === name ? '' : current))
    setNewDesc((current) => (current.trim() === description ? '' : current))
    setNewError('')
    newNameInput.current?.focus()
  }

  const askDelete = (tag: MyTagUsage) => {
    setDeleteError(null)
    setDeleting(tag)
    replaceAddress(addressOf({ query, tab, deleting: tag.id }))
  }

  const cancelDelete = () => {
    setDeleting(null)
    setDeletePending(false)
    setDeleteError(null)
    replaceAddress(addressOf({ query, tab }))
  }

  const confirmDelete = async (): Promise<void> => {
    if (deleting === null) {
      return
    }
    setDeletePending(true)
    const result = await deleteMyTag(deleting.id)
    setDeletePending(false)
    if (!result.ok) {
      setDeleteError(codeMessage(result.code, result.message, result.details))
      return
    }
    const next = definitions.filter((row) => row.id !== deleting.id)
    setDefinitions(next)
    setState(stateFor(next))
    cancelDelete()
  }

  /* ------------------------------------------------------------------ the lists */

  const lower = tagKey(query)
  const ordered = dormantOnly
    ? [...definitions].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
    : [...definitions].sort((a, b) => {
        if (b.used_in !== a.used_in) {
          return b.used_in - a.used_in
        }
        return a.name < b.name ? -1 : a.name > b.name ? 1 : 0
      })
  const shown = ordered.filter(
    (tag) =>
      lower === '' ||
      tagKey(tag.name).includes(lower) ||
      tagKey(tag.description ?? '').includes(lower),
  )

  const publicOrdered = [...publicTags].sort((a, b) => {
    if (b.used_in !== a.used_in) {
      return b.used_in - a.used_in
    }
    return a.name < b.name ? -1 : a.name > b.name ? 1 : 0
  })
  const publicShown = publicOrdered.filter((tag) => lower === '' || tagKey(tag.name).includes(lower))

  const deleteBody = (tag: MyTagUsage): string => {
    const sleeping = tag.dormant_in
    return sleeping > 0
      ? '删除后，这个标签会从 ' + tag.used_in + ' 个你能看到的 skill 上移除，不能恢复；另有 ' + sleeping +
          ' 个 skill 上的这个标签你现在看不到，也会一起删除。那些 skill 本身不会被删除。'
      : '删除后，这个标签会从 ' + tag.used_in + ' 个你能看到的 skill 上移除，不能恢复。'
  }

  return (
    <section className="page" id="page-my-tags">
      <header className="page-head">
        <h1>标签</h1>
        <span className="count" hidden={tab === 'public' ? publicState !== 'ready' : !listShown}>
          {(tab === 'public' ? publicShown.length : shown.length) + ' 个'}
        </span>
        <div className="page-actions">
          {tab === 'public' ? (
            publicState === 'ready' ? (
              <SearchBox
                id="tagQuery"
                value={query}
                placeholder="搜索公开标签"
                label="搜索公开标签"
                onChange={(value) => {
                  setQuery(value)
                  replaceAddress(addressOf({ query: value, tab }))
                }}
              />
            ) : null
          ) : listShown ? (
            <SearchBox
              id="tagQuery"
              value={query}
              placeholder="搜索名字或说明"
              label="搜索名字或说明"
              onChange={(value) => {
                setQuery(value)
                replaceAddress(addressOf({ query: value, tab }))
              }}
            />
          ) : null}
          {tab === 'private' ? (
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
          ) : null}
        </div>
      </header>

      <nav className="skill-tabs list-tabs" id="tagTabs" aria-label="标签的两份清单" hidden={!settled}>
        <a
          className={'skill-tab' + (tab === 'private' ? ' is-current' : '')}
          data-tab="private"
          href="/my-tags"
          aria-current={tab === 'private' ? 'page' : undefined}
        >
          我的标签
        </a>
        <a
          className={'skill-tab' + (tab === 'public' ? ' is-current' : '')}
          data-tab="public"
          href="/my-tags?tab=public"
          aria-current={tab === 'public' ? 'page' : undefined}
        >
          公开标签
        </a>
      </nav>

      {tab === 'private' ? (
        <>
          <p className="tag-note" id="tagLimit" hidden={!atLimit || !settled}>
            标签最多 {MAX_TAGS_PER_ACCOUNT} 个。删掉一个，才能新建。
          </p>

          {state === 'loading' ? <p className="loading">加载中…</p> : null}
          {state === 'failed' ? <div className="banner">无法加载你的标签，请稍后重试。</div> : null}

          <p className="empty" hidden={state !== 'empty' || creating}>
            还没有标签。新建一个，用来归类你库里的 skill；只有你看得到。
          </p>

          <div className="list-body" hidden={!listShown && !createShown}>
            <p className="tag-note" id="tagDormant" hidden={!dormantOnly}>
              这些标签对应的 skill 现在都看不到（可能已下线或暂时不可见）。skill 回来时，标签会重新出现。
            </p>

            <ul className="skills" id="tagList" hidden={shown.length === 0 && !createShown}>
              {createShown ? (
                <TagCreateRow
                  name={newName}
                  description={newDesc}
                  error={newError}
                  nameInput={newNameInput}
                  descriptionInput={newDescInput}
                  onName={(value) => {
                    setNewName(value)
                    setNewError('')
                  }}
                  onDescription={(value) => {
                    setNewDesc(value)
                    setNewError('')
                  }}
                  onCancel={endCreate}
                  onAccept={() => void commitCreate()}
                  onEnd={endCreate}
                />
              ) : null}

              {shown.map((tag) => (
                <TagDefinitionRow
                  key={tag.id}
                  tag={tag}
                  editing={editingId === tag.id}
                  editName={editName}
                  editError={editError?.id === tag.id ? editError.message : ''}
                  describing={describingId === tag.id}
                  busy={savingId === tag.id}
                  descriptionDraft={descDraft}
                  dormantOnly={dormantOnly}
                  onBeginRename={() => {
                    beginRename(tag)
                    replaceAddress(addressOf({ query, tab, rename: tag.id }))
                  }}
                  onEndRename={endRename}
                  onRename={(value) => {
                    setEditName(value)
                    setEditError(null)
                  }}
                  onCommitRename={() => void commitRename(tag)}
                  onBeginDescription={() => {
                    beginDesc(tag)
                    replaceAddress(addressOf({ query, tab, describe: tag.id }))
                  }}
                  onEndDescription={endDesc}
                  onDescription={setDescDraft}
                  onCommitDescription={() => void commitDesc(tag)}
                  onDelete={() => askDelete(tag)}
                />
              ))}
            </ul>

            {/* 0 definitions, 0 effective and no match are three different sentences. */}
            <p className="empty" id="tagNone" hidden={dormantOnly || definitions.length > 0 || !listShown}>
              还没有标签。新建一个，用来归类你库里的 skill；只有你看得到。
            </p>
            <p className="empty" id="tagNoMatch" hidden={lower === '' || shown.length > 0 || !listShown}>
              没有匹配的标签。换个词试试。
            </p>
          </div>
        </>
      ) : (
        <div className="list-body" id="publicTagPanel">
          {publicState === 'loading' ? <p className="loading">加载中…</p> : null}
          {publicState === 'failed' ? <div className="banner">无法加载你的标签，请稍后重试。</div> : null}
          {publicState === 'ready' ? (
            <>
              <p className="tag-note" id="publicTagNote">
                公开标签是你在自己创建的 skill 上贴的词，所有人看得到，搜索靠它找回那个 skill。贴与摘在 skill 的信息页。
              </p>
              <ul className="skills" id="publicTagList" hidden={publicShown.length === 0}>
                {publicShown.map((tag) => (
                  <li className="skill" key={tag.key} data-public-tag={tag.key}>
                    <div className="skill-line">
                      <span className="tag-name">{'#' + tag.name}</span>
                      <span className="spacer" />
                      <a className="tag-count" href={`/?public-tag=${encodeURIComponent(tag.key)}`}>
                        {COUNT(tag.used_in)}
                      </a>
                    </div>
                  </li>
                ))}
              </ul>
              {publicShown.length === 0 ? (
                <p className="empty" id="publicTagNone">
                  {publicOrdered.length === 0
                    ? '还没有公开标签。在你创建的 skill 的信息页贴上一个，它会出现在这里。'
                    : '没有匹配的公开标签。换个词试试。'}
                </p>
              ) : null}
            </>
          ) : null}
        </div>
      )}

      <ConfirmDialog
        open={deleting !== null}
        title="删除标签？"
        body={deleting === null ? '' : deleteBody(deleting)}
        confirmLabel="确认删除标签"
        danger
        pending={deletePending}
        error={deleteError}
        onConfirm={() => void confirmDelete()}
        onCancel={cancelDelete}
      />
    </section>
  )
}
