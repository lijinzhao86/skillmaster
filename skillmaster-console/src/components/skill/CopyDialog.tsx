import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { failureMessage } from '../../api/errors'
import { copySkill } from '../../api/skills'

/**
 * 复制（v54, ADR 0173）: one published version of somebody else's skill becomes a new private
 * skill of my own.
 *
 * **Not another word for 入库.** 入库 keeps a reference and the content stays the author's; a copy
 * creates a skill and a draft in my own namespace, which I can then change and publish from my
 * side. That is why the name is required: it is the new skill's address, not a side effect of a
 * click.
 *
 * **The version is a choice, not just live.** The dialog starts on the live version and offers the
 * published history the caller can read. Drafts, discarded and revoked versions are never in the
 * list — they are either not a complete version to take, or the author has taken it back.
 *
 * **Failure and success both stay in the sheet.** A name conflict greys the button until the name
 * changes; success says 复制成功 and offers the two destinations rather than navigating away on its
 * own. The primary destination is the new skill's information page, because what usually happens
 * next is an agent editing it, not a person in the file editor.
 */
export interface CopyVersion {
  /** The address suffix to send: a version name or `sha256:…`. */
  value: string
  /** What the select shows; the live one is prefixed 线上 ·. */
  label: string
}

export interface CopyDialogProps {
  open: boolean
  /** The source address's two segments — the new skill lands in the caller's own namespace. */
  sourceNamespace: string
  sourceName: string
  versions: CopyVersion[]
  initialVersion: string
  onClose: () => void
  onNavigate: (href: string) => void
}

/** 64, the same ceiling as 新建 skill; counted in code points, not UTF-16 units. */
function cap(value: string, max: number): string {
  return Array.from(value).slice(0, max).join('')
}

/** SkillMaster's name rule (ADR 0160), the same sentence the server's refusal carries. */
function nameProblem(value: string): ReactNode {
  if (value === '') return '名字不能为空。'
  if (Array.from(value).length > 64) return '名字最多 64 个字符。'
  if (!/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(value) || value.includes('--')) {
    return '名字只能用小写字母、数字和连字符，且不能以连字符开头或结尾。'
  }
  if (value === 'skillmaster' || value.startsWith('skillmaster-')) {
    return '名字不能以 skillmaster 开头，这是官方保留的前缀。'
  }
  return null
}

export default function CopyDialog({
  open,
  sourceNamespace,
  sourceName,
  versions,
  initialVersion,
  onClose,
  onNavigate,
}: CopyDialogProps) {
  const [phase, setPhase] = useState<'form' | 'done'>('form')
  const [name, setName] = useState('')
  const [version, setVersion] = useState('')
  /** The name the server refused: while it is unchanged, 复制 stays disabled. */
  const [refused, setRefused] = useState<string | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  /** The new address on success, which is where 查看新 skill goes. */
  const [created, setCreated] = useState<{ namespace: string; name: string } | null>(null)
  const nameInput = useRef<HTMLInputElement | null>(null)

  /* Every opening starts from the source's own name and the live version; a refusal clears with the
     name that earned it. */
  useEffect(() => {
    if (!open) {
      return
    }
    setPhase('form')
    setName(sourceName)
    setVersion(initialVersion)
    setRefused(null)
    setFailure(null)
    setPending(false)
    setCreated(null)
    const timer = requestAnimationFrame(() => {
      nameInput.current?.focus()
    })
    return () => {
      cancelAnimationFrame(timer)
    }
  }, [open, sourceName, initialVersion])

  const problem = nameProblem(name)
  const conflict = refused !== null && name === refused
  const ready = problem === null && !conflict && !pending
  /* A conflict is one sentence, not two: the server's refusal and the inline rule would otherwise
     both draw. A deleted name is a different next step from a live one, so it says so. */
  const error = conflict
    ? '这个名字已经有了。换一个名字再复制。'
    : name === ''
      ? null
      : problem

  function close(): void {
    if (!pending) {
      onClose()
    }
  }

  async function submit(): Promise<void> {
    if (!ready) {
      return
    }
    const submitted = name.trim()
    setPending(true)
    setFailure(null)
    const answer = await copySkill(
      sourceNamespace,
      sourceName,
      submitted,
      version === '' ? undefined : version,
    )
    setPending(false)

    if (!answer.ok) {
      if (answer.code === 'skill_already_exists') {
        /* v61: since deletion releases the name, this is the only conflict left — a live skill
           holds it — and the way out is another name. */
        setRefused(submitted)
        return
      }
      setFailure(failureMessage(answer))
      return
    }

    setCreated(answer.data.skill)
    setPhase('done')
  }

  if (!open) {
    return null
  }

  return (
    <div
      className="confirm"
      id="copyDialog"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          close()
        }
      }}
    >
      <div
        className="confirm-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="copyTitle"
        aria-describedby="copyIntro"
      >
        <h2 className="confirm-title" id="copyTitle">
          {phase === 'done' ? '复制成功' : '复制 skill'}
        </h2>
        {phase === 'done' ? (
          <>
            <p className="confirm-body" id="copyIntro">
              已复制到你的库：<span className="confirm-mono">{created?.name ?? name}</span>
              ，并生成了一条草稿。草稿可以继续改，上线仍在它自己的版本页。
            </p>
            <div className="confirm-acts">
              <button type="button" className="button is-quiet" id="copyStay" onClick={close}>
                留在当前页
              </button>
              <button
                type="button"
                className="button"
                id="copyGo"
                onClick={() => {
                  if (created == null) {
                    onClose()
                    return
                  }
                  onClose()
                  onNavigate(
                    `/skills/${encodeURIComponent(created.namespace)}/${encodeURIComponent(created.name)}`,
                  )
                }}
              >
                查看新 skill
              </button>
            </div>
          </>
        ) : (
          <>
            <p className="confirm-body" id="copyIntro">
              把 {sourceName} 的一个版本复制到你的库。复制后是一个私有的 skill
              和一条草稿，不会上线。
            </p>
            <label className="confirm-field">
              <span className="confirm-label">版本</span>
              <select
                className="confirm-input is-select"
                id="copyVersion"
                aria-label="要复制哪一版"
                value={version}
                disabled={pending}
                onChange={(event) => setVersion(event.target.value)}
              >
                {versions.map((entry) => (
                  <option value={entry.value} key={entry.value}>
                    {entry.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="confirm-field">
              <span className="confirm-label">名字</span>
              <input
                className={'confirm-input' + (error === null ? '' : ' is-invalid')}
                id="copyName"
                type="text"
                autoComplete="off"
                spellCheck={false}
                placeholder="复制后的 skill 名字"
                aria-label="复制后的 skill 名字（最多 64 个字符）"
                aria-invalid={error !== null}
                aria-describedby="copyError"
                ref={nameInput}
                value={name}
                disabled={pending}
                onChange={(event) => {
                  const next = cap(event.target.value, 64)
                  setName(next)
                  /* Any edit re-judges the refusal: changing the name clears both the sentence and
                     the disabled button together. */
                  if (refused !== null && next !== refused) {
                    setRefused(null)
                    setFailure(null)
                  }
                }}
              />
            </label>
            <p className="confirm-hint">
              名字会成为新 skill 地址的一部分；已经有同名 skill 时要换一个名字。
            </p>
            <p className="confirm-error" id="copyError" role="alert" hidden={error === null}>
              {error}
            </p>
            {failure !== null ? (
              <p className="confirm-error" role="alert">
                {failure}
              </p>
            ) : null}
            <div className="confirm-acts">
              <button
                type="button"
                className="button is-quiet"
                id="copyCancel"
                disabled={pending}
                onClick={close}
              >
                取消
              </button>
              <button
                type="button"
                className="button"
                id="copyAccept"
                disabled={!ready}
                onClick={() => {
                  void submit()
                }}
              >
                {pending ? '正在复制…' : '复制'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
