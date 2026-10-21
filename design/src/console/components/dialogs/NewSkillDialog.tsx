/**
 * 新建 skill（v9）: one action, two sources.
 *
 * The one way a skill is born without an upload: a name, a description and the first version's
 * name, all at once. The skill and its first draft are one transaction; what lands is a draft,
 * because publishing stays on the version page (ADR 0031).
 *
 * The two refusals — the name is taken, the name belongs to a deleted skill — are addresses here,
 * because with no server there is nothing to click that would produce them.
 */

import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { dialogs, useDialogs } from './store'

/** 64 / 40 are codepoints, not UTF-16 units — the same rule as the editor's version name. */
function cap(value: string, max: number): string {
  return Array.from(value).slice(0, max).join('')
}

export default function NewSkillDialog() {
  const { newSkill } = useDialogs()
  const open = newSkill !== null
  const mode = newSkill?.mode ?? 'open'
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [versionName, setVersionName] = useState('第一版')
  const [error, setError] = useState<ReactNode>(null)
  const [busy, setBusy] = useState(false)
  const nameInput = useRef<HTMLInputElement>(null)

  /* Opening resets the sheet, and the two refusals open it filled in — they are the state, not a
     message after the fact. */
  useEffect(() => {
    if (!open) return
    const refused = mode !== 'open'
    setName(refused ? 'pdf-tools' : '')
    setDescription(refused ? '把 PDF 拆页、合并与压缩。' : '')
    setVersionName('第一版')
    setBusy(false)
    setError(
      mode === 'deleted' ? (
        <>这个名字属于一个已删除的 skill。 <a href="?page=skills&show=deleted">去恢复</a></>
      ) : mode === 'error' ? (
        <>这个名字已经有了。 <a href="?page=skill">查看 pdf-tools</a></>
      ) : null,
    )
    nameInput.current?.focus()
  }, [open, mode])

  const ready = name.trim() !== '' && description.trim() !== '' && versionName.trim() !== ''

  return (
    <div
      className="confirm"
      id="newSkillDialog"
      hidden={!open}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          dialogs.closeNewSkill()
        }
      }}
    >
      <div className="confirm-sheet" role="dialog" aria-modal="true" aria-labelledby="newSkillTitle" aria-describedby="newSkillIntro">
        <h2 className="confirm-title" id="newSkillTitle">新建 skill</h2>
        <p className="confirm-body" id="newSkillIntro">
          名字会成为地址的一部分；描述来自 SKILL.md 的 frontmatter。创建后是一条草稿，不会上线。
        </p>
        <label className="confirm-field">
          <span className="confirm-label">名字</span>
          <input
            className="confirm-input"
            id="newSkillName"
            type="text"
            autoComplete="off"
            spellCheck={false}
            placeholder="skill 的名字"
            aria-label="skill 的名字（最多 64 个字符）"
            ref={nameInput}
            value={name}
            onChange={(event) => setName(cap(event.target.value, 64))}
          />
        </label>
        <label className="confirm-field">
          <span className="confirm-label">描述</span>
          <input
            className="confirm-input"
            id="newSkillDescription"
            type="text"
            autoComplete="off"
            spellCheck={false}
            placeholder="这个 skill 是做什么的"
            aria-label="描述"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
        </label>
        <label className="confirm-field">
          <span className="confirm-label">版本名称</span>
          <input
            className="confirm-input"
            id="newSkillVersionName"
            type="text"
            autoComplete="off"
            spellCheck={false}
            aria-label="版本名称（最多 40 个字符）"
            value={versionName}
            onChange={(event) => setVersionName(cap(event.target.value, 40))}
          />
        </label>
        <p className="confirm-error" id="newSkillError" role="alert" hidden={error === null}>{error}</p>
        <div className="confirm-acts">
          <button
            type="button"
            className="button is-quiet"
            id="newSkillCancel"
            disabled={busy}
            onClick={() => dialogs.closeNewSkill()}
          >
            取消
          </button>
          <button
            type="button"
            className="button"
            id="newSkillAccept"
            disabled={busy || !ready}
            onClick={() => {
              if (!ready) return
              setBusy(true)
              /* 演示件不模拟建行：真实产品里落在这一条草稿的编辑页，这里用空白底稿那一屏代替。 */
              window.setTimeout(() => {
                window.location.href = '?page=editor&state=blank'
              }, 600)
            }}
          >
            {busy ? '正在创建…' : '创建'}
          </button>
        </div>
      </div>
    </div>
  )
}
