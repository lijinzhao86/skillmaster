/**
 * 复制（v54）: 把一个 skill 的一版内容变成自己的 skill。
 *
 * **它不是「入库」的另一种说法。** 入库只收一条引用，内容仍属于原作者；复制会在你的命名空间里
 * 新建一个私有的 skill 和一条草稿，之后可以改、可以上线到你自己这里。所以名字是必填的——它
 * 是新 skill 的地址，不是一次点击的副产物。
 *
 * **版本是一道选择，不只是线上。** 默认线上；也可以选一个你读得到的已发布历史版本。草稿、
 * 已丢弃、已下架的版本不进这个下拉：它们不是一个可以拿走的完整版本，或者作者已经把它收回了。
 *
 * **失败与成功都停在这张弹窗里。** 名字冲突时「复制」置灰，直到名字改掉；成功之后不直接跳走，
 * 先给一句「复制成功」，再让人选去看新 skill（主按钮，推荐）还是留在当前页（次按钮）。
 *
 * 没有后端，所以三种拒绝走地址（`?copy=taken|invalid|done`）；成功也不模拟服务端的行。
 */

import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { dialogs, useDialogs } from './store'

/** 64 与新建 skill 同一个上限；按码点截，不按 UTF-16 单元。 */
function cap(value: string, max: number): string {
  return Array.from(value).slice(0, max).join('')
}

/** SkillMaster 的名字规则（ADR 0160）：1–64，小写字母、数字、连字符，不以连字符开头或结尾，不连续；
    `skillmaster` 是官方保留前缀，客户端先拒，不等服务端 422。 */
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

export default function CopyDialog() {
  const { copySkill } = useDialogs()
  const open = copySkill !== null
  const mode = copySkill?.mode ?? 'open'
  const skillName = copySkill?.skillName ?? ''
  const versions = copySkill?.versions ?? []
  const initialVersion = copySkill?.initialVersion ?? ''

  const [phase, setPhase] = useState<'form' | 'done'>('form')
  const [name, setName] = useState('')
  const [version, setVersion] = useState('')
  /** 服务端退回的那个名字：名字不改回它，复制按钮就保持置灰。 */
  const [refused, setRefused] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const nameInput = useRef<HTMLInputElement>(null)

  /* 每次打开都从源那一份重置：名字预填源名字，版本回到线上；两种拒绝打开就是它们的样子。 */
  useEffect(() => {
    if (!open) return
    const started = mode === 'invalid' ? 'pdf_tools' : skillName
    setPhase(mode === 'done' ? 'done' : 'form')
    setName(started)
    setVersion(initialVersion)
    setRefused(mode === 'taken' ? started : null)
    setBusy(false)
    if (mode !== 'done') nameInput.current?.focus()
  }, [open, mode, skillName, initialVersion])

  const problem = nameProblem(name)
  const conflict = refused !== null && name === refused
  const ready = problem === null && !conflict
  const error = conflict
    ? '这个名字已经有了。换一个名字再复制。'
    : name === '' ? null : problem

  return (
    <div
      className="confirm"
      id="copyDialog"
      hidden={!open}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          dialogs.closeCopy()
        }
      }}
    >
      <div className="confirm-sheet" role="dialog" aria-modal="true" aria-labelledby="copyTitle" aria-describedby="copyIntro">
        <h2 className="confirm-title" id="copyTitle">{phase === 'done' ? '复制成功' : '复制 skill'}</h2>
        {phase === 'done' ? (
          <>
            <p className="confirm-body" id="copyIntro">
              已复制到你的库：<span className="confirm-mono">{name}</span>，并生成了一条草稿。草稿可以继续改，上线仍在它自己的版本页。
            </p>
            <div className="confirm-acts">
              <button
                type="button"
                className="button is-quiet"
                id="copyStay"
                onClick={() => dialogs.closeCopy()}
              >
                留在当前页
              </button>
              <button
                type="button"
                className="button"
                id="copyGo"
                onClick={() => {
                  dialogs.closeCopy()
                  window.location.href = '?page=skill&in=created&copied=1'
                }}
              >
                查看新 skill
              </button>
            </div>
          </>
        ) : (
          <>
            <p className="confirm-body" id="copyIntro">
              把 {skillName} 的一个版本复制到你的库。复制后是一个私有的 skill 和一条草稿，不会上线。
            </p>
            <label className="confirm-field">
              <span className="confirm-label">版本</span>
              <select
                className="confirm-input is-select"
                id="copyVersion"
                aria-label="要复制哪一版"
                value={version}
                onChange={(event) => setVersion(event.target.value)}
              >
                {versions.map((entry) => (
                  <option value={entry.value} key={entry.value}>{entry.label}</option>
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
                onChange={(event) => {
                  const next = cap(event.target.value, 64)
                  setName(next)
                  /* 名字一动就重新判断：改掉了那个冲突的名字，错误与置灰一起走。 */
                  if (refused !== null && next !== refused) setRefused(null)
                }}
              />
            </label>
            <p className="confirm-hint">名字会成为新 skill 地址的一部分；已经有同名 skill 时要换一个名字。</p>
            <p className="confirm-error" id="copyError" role="alert" hidden={error === null}>{error}</p>
            <div className="confirm-acts">
              <button
                type="button"
                className="button is-quiet"
                id="copyCancel"
                disabled={busy}
                onClick={() => dialogs.closeCopy()}
              >
                取消
              </button>
              <button
                type="button"
                className="button"
                id="copyAccept"
                disabled={busy || !ready}
                onClick={() => {
                  if (!ready) return
                  setBusy(true)
                  /* 演示件不模拟建行：真实产品里请求成功后停在同一张弹窗的「复制成功」。 */
                  window.setTimeout(() => {
                    setBusy(false)
                    setPhase('done')
                  }, 600)
                }}
              >
                {busy ? '正在复制…' : '复制'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
