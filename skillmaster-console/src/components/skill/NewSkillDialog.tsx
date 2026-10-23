import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { importFailure, type ImportFailure } from '../../api/errors'
import { createSkill } from '../../api/skills'

/**
 * 「新建 skill › 在线编辑」 (design §新建 skill, ADR 0070).
 *
 * **One action, not two.** The form creates the skill and its first draft together; the editor then
 * opens on that draft, which already has a real {@code SKILL.md}. A skill with no version would be
 * a row every surface has to special-case, and it would exist only because somebody closed a tab.
 *
 * The name and description are {@code SKILL.md}'s two required facts; the version name defaults to
 * 「第一版」 and is editable. All three are required before 创建 is available.
 *
 * **What changed in the port.** `props.open` was watched to reset the form on every opening; here an
 * effect on `open` does the same. The `@input` clamps survive as `onChange` handlers rather than a
 * second listener, because React's controlled input reports every edit through `onChange` anyway.
 */
export interface NewSkillDialogProps {
  open: boolean
  /** The caller's own namespace slug — the only place this sheet's skill can land. */
  namespace: string
  onClose: () => void
  onNavigate: (href: string) => void
}

export default function NewSkillDialog({
  open,
  namespace,
  onClose,
  onNavigate,
}: NewSkillDialogProps) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [versionName, setVersionName] = useState('第一版')
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState<ImportFailure | null>(null)
  const nameInput = useRef<HTMLInputElement | null>(null)

  const acceptDisabled =
    pending || name.trim() === '' || description.trim() === '' || versionName.trim() === ''

  useEffect(() => {
    if (!open) {
      return
    }
    setName('')
    setDescription('')
    setVersionName('第一版')
    setPending(false)
    setFailure(null)
    // The tick lets the sheet leave the DOM and come back before the focus is placed.
    const timer = requestAnimationFrame(() => {
      nameInput.current?.focus()
    })
    return () => {
      cancelAnimationFrame(timer)
    }
  }, [open])

  function cancel(): void {
    if (!pending) {
      onClose()
    }
  }

  function onKeydown(event: ReactKeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault()
      cancel()
    }
  }

  /** The same clamp the design states: characters, not UTF-16 units. */
  function clamp(value: string, limit: number): string {
    const chars = Array.from(value)
    return chars.length > limit ? chars.slice(0, limit).join('') : value
  }

  async function submit(): Promise<void> {
    if (acceptDisabled) {
      return
    }
    setPending(true)
    setFailure(null)
    const answer = await createSkill(name.trim(), description.trim(), versionName.trim())
    setPending(false)

    if (!answer.ok) {
      setFailure(importFailure(answer, 'create'))
      // This sheet knows the name it asked for, so «这个名字已经有了» can lead straight to it.
      if (answer.code === 'skill_already_exists') {
        const typed = name.trim()
        setFailure({
          message: '这个名字已经有了。',
          action: {
            label: `查看 ${typed}`,
            href: `/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(typed)}`,
          },
        })
      }
      return
    }

    const draft = answer.data.version.draft_id
    if (draft !== null) {
      onNavigate(
        `/skills/${encodeURIComponent(answer.data.namespace)}/${encodeURIComponent(answer.data.name)}@${encodeURIComponent(draft)}/edit`,
      )
    }
  }

  if (!open) {
    return null
  }

  return (
    <div className="confirm" onKeyDown={onKeydown}>
      <div
        className="confirm-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="newSkillTitle"
        aria-describedby="newSkillIntro"
      >
        <h2 id="newSkillTitle" className="confirm-title">
          新建 skill
        </h2>
        <p id="newSkillIntro" className="confirm-body">
          名字会成为地址的一部分；描述来自 SKILL.md 的 frontmatter。创建后是一条草稿，不会上线。
        </p>

        <label className="confirm-field">
          <span className="confirm-label">名字</span>
          <input
            ref={nameInput}
            className="confirm-input"
            type="text"
            value={name}
            autoComplete="off"
            spellCheck={false}
            placeholder="skill 的名字"
            aria-label="skill 的名字（最多 64 个字符）"
            disabled={pending}
            onChange={(event) => setName(clamp(event.target.value, 64))}
          />
        </label>
        <label className="confirm-field">
          <span className="confirm-label">描述</span>
          <input
            className="confirm-input"
            type="text"
            value={description}
            autoComplete="off"
            spellCheck={false}
            placeholder="这个 skill 是做什么的"
            aria-label="描述"
            disabled={pending}
            onChange={(event) => setDescription(event.target.value)}
          />
        </label>
        <label className="confirm-field">
          <span className="confirm-label">版本名称</span>
          <input
            className="confirm-input"
            type="text"
            value={versionName}
            autoComplete="off"
            spellCheck={false}
            aria-label="版本名称（最多 40 个字符）"
            disabled={pending}
            onChange={(event) => setVersionName(clamp(event.target.value, 40))}
          />
        </label>

        {failure !== null ? (
          <p className="confirm-error" role="alert">
            {failure.message}
            {failure.action !== undefined ? (
              <a href={failure.action.href}>{failure.action.label}</a>
            ) : null}
          </p>
        ) : null}

        <div className="confirm-acts">
          <button type="button" className="button is-quiet" disabled={pending} onClick={cancel}>
            取消
          </button>
          <button
            type="button"
            className="button"
            disabled={acceptDisabled}
            onClick={() => {
              void submit()
            }}
          >
            {pending ? '正在创建…' : '创建'}
          </button>
        </div>
      </div>
    </div>
  )
}
