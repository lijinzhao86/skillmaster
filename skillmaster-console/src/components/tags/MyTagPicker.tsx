import { useEffect, useRef, useState } from 'react'
import {
  MAX_TAG_NAME,
  MAX_TAGS_PER_SKILL,
  normalizeTagName,
  pointLength,
  slicePoints,
  tagKey,
} from '../../tagRules'

/**
 * The inline input for one group of free tags.
 *
 * It is not a form row but a capsule that can be typed into: the group's 「+」 opens it at the end of
 * the last tag, on the same line and at the same height. Enter turns what was typed into a tag and
 * leaves the input in place. Existing names are offered below it; typing filters them.
 *
 * The same three rules as before: an exact existing word attaches it, no match creates a new one, and
 * a name is 1–24 characters, unique ignoring case, and may not collide with a category.
 */
export default function MyTagPicker({
  chosen,
  definitions,
  onPick,
  onCreate,
  idPrefix = 'myTag',
  max = MAX_TAGS_PER_SKILL,
  label = '添加标签',
  noun = '标签',
  menuTitle = '已有的标签',
  hash = false,
  categoryNames = [],
}: {
  /** The ids already attached. */
  chosen: string[]
  /** Every definition, attached or not. */
  definitions: Array<{ id: string; name: string }>
  onPick: (id: string) => void
  /** No existing match: create one and attach it. */
  onCreate: (name: string) => void
  /** Two inputs may live on one page; their DOM ids must differ. */
  idPrefix?: string
  max?: number
  label?: string
  noun?: string
  menuTitle?: string
  /** Public tags are written `#name`; mine are not. */
  hash?: boolean
  /** The platform vocabulary, for the "this is a category" pre-check. */
  categoryNames?: string[]
}) {
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const [open, setOpen] = useState(true)
  const [active, setActive] = useState(-1)
  const input = useRef<HTMLInputElement>(null)
  const wrap = useRef<HTMLSpanElement>(null)

  useEffect(() => {
    input.current?.focus()
  }, [])

  /* Clicking away closes the suggestions; the input itself stays until the group's ✓ ends editing. */
  useEffect(() => {
    if (!open) {
      return
    }
    const outside = (event: MouseEvent) => {
      if (!wrap.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', outside, true)
    return () => document.removeEventListener('mousedown', outside, true)
  }, [open])

  const full = chosen.length >= max
  const asked = normalizeTagName(name)
  const lower = tagKey(asked)
  const available = definitions.filter((tag) => !chosen.includes(tag.id))
  const exact = available.find((tag) => tagKey(tag.name) === lower)
  const matches = available.filter((tag) => asked === '' || tagKey(tag.name).includes(lower))
  const createRow = asked !== '' && exact === undefined && !full
  const rows = matches.length + (createRow ? 1 : 0)

  const reset = () => {
    setName('')
    setError('')
    setActive(-1)
    setOpen(true)
  }

  const pick = (id: string) => {
    onPick(id)
    reset()
  }

  /** The local name rules, said in the place the candidates would have been. */
  const create = () => {
    if (asked === '') {
      return
    }
    if (pointLength(asked) > MAX_TAG_NAME) {
      setError('名字不能超过 ' + MAX_TAG_NAME + ' 个字符。')
      return
    }
    if (categoryNames.some((name) => tagKey(name) === lower)) {
      setError('「' + asked + '」是一个分类，换一个名字。')
      return
    }
    if (full) {
      setError('每个 skill 最多 ' + max + ' 个' + noun + '。')
      return
    }
    onCreate(asked)
    reset()
  }

  /** Enter: the highlighted row first; otherwise the typed word — exact match attaches, new creates. */
  const submit = () => {
    if (active >= 0 && active < matches.length) {
      pick(matches[active]!.id)
      return
    }
    if (active === matches.length && createRow) {
      create()
      return
    }
    if (exact) {
      pick(exact.id)
      return
    }
    create()
  }

  return (
    <span className="tag-inline" ref={wrap}>
      <input
        ref={input}
        className="tag-chip-input"
        id={idPrefix + 'Input'}
        type="text"
        role="combobox"
        aria-label={label}
        aria-expanded={open ? 'true' : 'false'}
        aria-controls={idPrefix + 'Menu'}
        aria-autocomplete="list"
        autoComplete="off"
        spellCheck={false}
        placeholder={full ? '最多 ' + max + ' 个' : label}
        disabled={full}
        value={name}
        onChange={(event) => {
          setName(slicePoints(event.target.value, MAX_TAG_NAME))
          setError('')
          setActive(-1)
          setOpen(true)
        }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown') {
            event.preventDefault()
            setOpen(true)
            setActive((index) => Math.min(index + 1, rows - 1))
          } else if (event.key === 'ArrowUp') {
            event.preventDefault()
            setActive((index) => Math.max(index - 1, -1))
          } else if (event.key === 'Enter') {
            event.preventDefault()
            submit()
          } else if (event.key === 'Escape') {
            if (open) {
              event.preventDefault()
              event.stopPropagation()
              setOpen(false)
            }
          } else if (event.key === 'Tab') {
            setOpen(false)
          }
        }}
      />

      {open && (error !== '' || rows > 0) ? (
        <div className="tag-menu" id={idPrefix + 'Menu'} role="listbox" aria-label={menuTitle}>
          {error !== '' ? (
            <p className="tag-menu-error" role="alert">
              {error}
            </p>
          ) : (
            <>
              {matches.map((tag, index) => (
                <button
                  key={tag.id}
                  type="button"
                  className={'tag-menu-item' + (index === active ? ' is-active' : '')}
                  role="option"
                  aria-selected={index === active}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => pick(tag.id)}
                >
                  {hash ? '#' + tag.name : tag.name}
                </button>
              ))}
              {createRow ? (
                <button
                  type="button"
                  className={'tag-menu-item is-new' + (active === matches.length ? ' is-active' : '')}
                  role="option"
                  aria-selected={active === matches.length}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={create}
                >
                  新建「{asked}」
                </button>
              ) : null}
            </>
          )}
        </div>
      ) : null}
    </span>
  )
}
