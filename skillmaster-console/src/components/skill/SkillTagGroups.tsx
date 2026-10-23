import { useEffect, useState } from 'react'
import MenuSelect from '../MenuSelect'
import MyTagPicker from '../tags/MyTagPicker'
import type { CategoryRow, SkillTagGroups } from '../../api/types'
import { MAX_PUBLIC_TAGS_PER_SKILL, MAX_TAGS_PER_SKILL } from '../../tagRules'

/**
 * The right rail's three word groups, in the order **category → public tags → my tags**.
 *
 * They look alike and say three different things. The category is the platform's narrow shelf, read
 * by anyone who can see the skill and changed by the owner or 管理. A public tag is the author's word
 * for search, also public, written only by the author. My tags are "a person × a skill", visible to
 * that person alone, and **the whole group exists only while the library entry is effective** — a
 * dormant entry does not draw it at all (prd.md #10/#11).
 *
 * Every group is edited in place, never in a dialog: the category from a single-select menu with a
 * 「清除」 row, the two free-tag groups from an inline capsule that grows at the end of the row.
 */
export type EditGroup = 'categories' | 'public-tags' | 'my-tags'

export interface SkillTagGroupsProps {
  /** Null while the read is in flight or after it failed; the sections then draw nothing. */
  groups: SkillTagGroups | null
  /** `/web/categories`: the platform vocabulary, with its state and its public count. */
  categories: CategoryRow[]
  canEditCategories: boolean
  canEditPublicTags: boolean
  /** Candidate definitions, read lazily the first time a group is opened. */
  myTagDefs: Array<{ id: string; name: string }>
  publicTagDefs: Array<{ id: string; name: string }>
  initialEdit: EditGroup | null
  /** A sentence for a dormant entry; empty when the entry is effective. */
  membershipNote: string
  onLoadMyTagDefs: () => void
  onLoadPublicTagDefs: () => void
  onCategoryChange: (key: string | null) => void
  onPublicTagAdd: (name: string) => void
  onPublicTagRemove: (name: string) => void
  onMyTagAdd: (name: string) => void
  onMyTagRemove: (name: string) => void
}

export default function SkillTagGroups({
  groups,
  categories,
  canEditCategories,
  canEditPublicTags,
  myTagDefs,
  publicTagDefs,
  initialEdit,
  membershipNote,
  onLoadMyTagDefs,
  onLoadPublicTagDefs,
  onCategoryChange,
  onPublicTagAdd,
  onPublicTagRemove,
  onMyTagAdd,
  onMyTagRemove,
}: SkillTagGroupsProps) {
  const [editingTags, setEditingTags] = useState(initialEdit === 'my-tags')
  const [editingPublicTags, setEditingPublicTags] = useState(initialEdit === 'public-tags')

  useEffect(() => {
    if (editingTags) {
      onLoadMyTagDefs()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editingTags])

  useEffect(() => {
    if (editingPublicTags) {
      onLoadPublicTagDefs()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editingPublicTags])

  if (groups === null) {
    return null
  }

  const category = groups.category ?? null
  const publicTags = groups.public_tags ?? []
  const myTags = groups.my_tags ?? { readable: false, reason: null, tags: [] }
  const hasCategory = category !== null
  /* **The section exists only while the entry is effective.** The server's `library.active` is that
     fact; `my_tags.readable` says the same thing for the read, and the group's own predicate is the
     one the design names (prd.md #10/#11). */
  const inLibrary = groups.library?.active ?? false
  const categoryNames = categories.map((entry) => entry.name)

  const nameOf = (defs: Array<{ id: string; name: string }>, id: string): string =>
    defs.find((tag) => tag.id === id)?.name ?? ''

  return (
    <>
      {/* **One shelf per skill.** Anyone who can see the skill reads it; the owner and 管理 change it
          from a single-select menu, whose 「清除」 row is the way to no shelf at all. */}
      <section className="info-group" id="infoCategories" hidden={!hasCategory && !canEditCategories}>
        <div className="info-group-head">
          <span className="info-group-title">分类</span>
          {canEditCategories ? (
            <MenuSelect
              id="editCategories"
              initialOpen={initialEdit === 'categories'}
              groups={[
                {
                  options: categories.map((entry) => ({
                    value: entry.key,
                    label: entry.name,
                    /* The count is the public skills on that shelf. */
                    number: String(entry.public_total),
                    /* A stopped word cannot be newly assigned; one already on it stays. */
                    disabled: entry.state === 'stopped' && category?.key !== entry.key,
                  })),
                },
              ]}
              value={category?.key ?? ''}
              ariaLabel="添加分类"
              tool="add"
              menuMinWidth={200}
              triggerText={hasCategory ? category.name : '分类'}
              onSelect={(key) => onCategoryChange(key)}
              onClear={() => onCategoryChange(null)}
            />
          ) : null}
        </div>
        <div className="info-tags" id="infoTags" hidden={!hasCategory}>
          {hasCategory ? <span className="chip is-plain">{category.name}</span> : null}
        </div>
        {!hasCategory && canEditCategories ? <p className="info-empty">还没有分类。</p> : null}
      </section>

      {/* **Public tags: the author's words for search.** Visible to everyone, written only by the
          author. A read-only view with none draws nothing; the author's view says 「还没有」 because
          it has a next step. */}
      <section
        className="info-group"
        id="infoPublicTags"
        hidden={publicTags.length === 0 && !canEditPublicTags}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            setEditingPublicTags(false)
          }
        }}
        onBlur={(event) => {
          const next = event.relatedTarget as Node | null
          if (next !== null && event.currentTarget.contains(next)) {
            return
          }
          setEditingPublicTags(false)
        }}
      >
        <div className="info-group-head">
          <span className="info-group-title">公开标签</span>
          {canEditPublicTags ? (
            <button
              type="button"
              className="icon-add"
              id="editPublicTags"
              aria-label={editingPublicTags ? '完成编辑公开标签' : '添加公开标签'}
              aria-expanded={editingPublicTags}
              aria-controls="publicTagPicker"
              title={editingPublicTags ? '完成' : '添加公开标签'}
              onClick={() => setEditingPublicTags(!editingPublicTags)}
            >
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                {editingPublicTags ? (
                  <path
                    d="M5 12.4l4.4 4.4L19 7.6"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                ) : (
                  <path d="M12 6v12M6 12h12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                )}
              </svg>
            </button>
          ) : null}
        </div>
        <div className="info-tags" id="infoPublicTagsList" hidden={publicTags.length === 0 && !editingPublicTags}>
          {publicTags.map((tag) => (
            <span className="chip is-public" key={tag.key}>
              {'#' + tag.name}
              {editingPublicTags ? (
                <button
                  type="button"
                  className="filter-x"
                  aria-label={'摘掉「' + tag.name + '」'}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => onPublicTagRemove(tag.name)}
                >
                  ×
                </button>
              ) : null}
            </span>
          ))}
          {editingPublicTags ? (
            <MyTagPicker
              chosen={publicTags.map((tag) => tag.key)}
              definitions={publicTagDefs.filter((tag) => !publicTags.some((chosen) => chosen.key === tag.id))}
              onPick={(id) => onPublicTagAdd(nameOf(publicTagDefs, id))}
              onCreate={onPublicTagAdd}
              idPrefix="publicTag"
              max={MAX_PUBLIC_TAGS_PER_SKILL}
              label="添加公开标签"
              noun="公开标签"
              menuTitle="已有的公开标签"
              categoryNames={categoryNames}
              hash
            />
          ) : null}
        </div>
        {publicTags.length === 0 && !editingPublicTags && canEditPublicTags ? (
          <p className="info-empty">还没有公开标签。</p>
        ) : null}
      </section>

      {/* **My tags: only while the entry is effective.** A dormant entry draws no group at all. */}
      {inLibrary ? (
        <section
          className="info-group"
          id="infoMyTags"
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              setEditingTags(false)
            }
          }}
          onBlur={(event) => {
            /* **Leaving the group returns it to the plain state**, whether the focus went from the
               input or from a ×. The × and the ✓ both cancel `mousedown`, so focus does not move. */
            const next = event.relatedTarget as Node | null
            if (next !== null && event.currentTarget.contains(next)) {
              return
            }
            setEditingTags(false)
          }}
        >
          <div className="info-group-head">
            <span className="info-group-title">我的标签</span>
            <button
              type="button"
              className="icon-add"
              id="editMyTags"
              aria-label={editingTags ? '完成编辑标签' : '添加标签'}
              aria-expanded={editingTags}
              aria-controls="myTagPicker"
              title={editingTags ? '完成' : '添加标签'}
              onClick={() => setEditingTags(!editingTags)}
            >
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                {editingTags ? (
                  <path
                    d="M5 12.4l4.4 4.4L19 7.6"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                ) : (
                  <path d="M12 6v12M6 12h12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                )}
              </svg>
            </button>
          </div>
          <div className="info-tags" id="infoMyTagsList" hidden={myTags.tags.length === 0 && !editingTags}>
            {myTags.tags.map((tag) => (
              <span className="chip is-mine" key={tag.id}>
                {tag.name}
                {editingTags ? (
                  <button
                    type="button"
                    className="filter-x"
                    aria-label={'摘掉「' + tag.name + '」'}
                    /* Keep the focus in the input: deleting one tag must not end editing. */
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => onMyTagRemove(tag.name)}
                  >
                    ×
                  </button>
                ) : null}
              </span>
            ))}
            {editingTags ? (
              <MyTagPicker
                chosen={myTags.tags.map((tag) => tag.id)}
                definitions={myTagDefs.filter((tag) => !myTags.tags.some((chosen) => chosen.id === tag.id))}
                onPick={(id) => onMyTagAdd(nameOf(myTagDefs, id))}
                onCreate={onMyTagAdd}
                max={MAX_TAGS_PER_SKILL}
                categoryNames={categoryNames}
              />
            ) : null}
          </div>
          {myTags.tags.length === 0 && !editingTags ? <p className="info-empty">还没有标签。</p> : null}
        </section>
      ) : null}

      {membershipNote !== '' ? (
        <p className="info-note" id="infoMembership">{membershipNote}</p>
      ) : null}
    </>
  )
}
