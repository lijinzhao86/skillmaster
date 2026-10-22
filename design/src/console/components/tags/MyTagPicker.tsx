/**
 * 我的标签的**就地输入框**（v14 第二轮：不再开弹窗）。
 *
 * **它不是一行表单，是一颗可以打字的胶囊。** 点分组标题右边的「+」进入编辑后，它出现在最后一颗
 * 标签的右边，同一行、同一高度；打一个名字、回车，它就变成一颗新标签，输入框留在原处。
 * 已经有名字的标签从它下面的下拉里选：聚焦时列出全部还没贴上的定义，打字就筛。
 *
 * 三条规矩与以前那张纸上的完全相同：**精确命中一个已有的词**就贴上它；**没有命中**就新建；
 * 名字 1–24 个字符、不区分大小写唯一、不得与公开分类重名；每个 skill 最多 20 个。
 *
 * 键盘：↑↓ 在候选里走，回车贴上高亮的那个（没有高亮时就按打出来的字：命中即贴，否则新建），
 * Esc 先关下拉、再按一次才收起编辑；Tab 离开就关下拉。
 *
 * **失焦就回到普通状态**（2026-10-22 用户定）：只要焦点离开这一组，输入框与胶囊上的「×」一起
 * 收起来、标题右边那颗「✓」变回「+」。所以「点×删一颗」不会把编辑关掉——那一颗的 mousedown 被
 * 拦下，焦点一直在输入框里；点到组外才收。
 */

import { useEffect, useRef, useState } from 'react'
import {
  CATEGORIES,
  MAX_TAG_NAME,
  MAX_TAGS_PER_SKILL,
  normalizeTagName,
} from '../../fixtures/library'

const points = (value: string) => Array.from(value)

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
}: {
  /** 已经贴上的标签 id。 */
  chosen: string[]
  /** 所有标签定义（含没贴在这个 skill 上的）。 */
  definitions: Array<{ id: string; name: string }>
  onPick: (id: string) => void
  /** 没有命中任何已有的词：新建一个并贴上。 */
  onCreate: (name: string) => void
  /** 同一页可能有两颗输入框（我的标签、公开标签），DOM id 得分开。 */
  idPrefix?: string
  max?: number
  /** 输入框的占位符与读屏名。 */
  label?: string
  /** 上限那句里的名词：「最多 N 个公开标签」。 */
  noun?: string
  menuTitle?: string
  /** 公开标签在界面上写成 `#名字`；我的标签不带。 */
  hash?: boolean

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

  /* 点到外面就收下下拉；输入框本身留着（编辑态由那一颗「✓」结束）。 */
  useEffect(() => {
    if (!open) return
    const outside = (event: MouseEvent) => {
      if (!wrap.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', outside, true)
    return () => document.removeEventListener('mousedown', outside, true)
  }, [open])

  const full = chosen.length >= max
  const asked = normalizeTagName(name)
  const lower = asked.toLowerCase()
  const available = definitions.filter((tag) => !chosen.includes(tag.id))
  const exact = available.find((tag) => tag.name.toLowerCase() === lower)
  const matches = available.filter((tag) => asked === '' || tag.name.toLowerCase().includes(lower))
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

  /** 新建之前先把名字的规矩过一遍；拒绝时把话说在候选的位置上。 */
  const create = () => {
    if (asked === '') return
    if (points(asked).length > MAX_TAG_NAME) {
      setError('名字不能超过 ' + MAX_TAG_NAME + ' 个字符。')
      return
    }
    if (CATEGORIES.some((category) => category.name.toLowerCase() === lower)) {
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

  /** 回车：高亮的那个优先；没有高亮就按打出来的字——命中即贴，否则新建。 */
  const submit = () => {
    if (active >= 0 && active < matches.length) {
      pick(matches[active].id)
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
          setName(points(event.target.value).slice(0, MAX_TAG_NAME).join(''))
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

      {open && (error !== '' || rows > 0) && (
        <div className="tag-menu" id={idPrefix + 'Menu'} role="listbox" aria-label={menuTitle}>
          {error !== '' ? (
            <p className="tag-menu-error" role="alert">{error}</p>
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
              {createRow && (
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
              )}
            </>
          )}
        </div>
      )}
    </span>
  )
}
