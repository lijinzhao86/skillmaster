/**
 * 导入（v9）: two entries, one sheet.
 *
 * The version list adds a version to the skill you are looking at; 我的 skill brings a whole archive
 * in, and the name comes from SKILL.md. It is not a confirmation — a file is chosen, two things are
 * typed — so the focus lands on 选择文件 rather than on the destructive-looking answer.
 *
 * The refusals and the idempotent hit are addresses, because with no server nothing can produce
 * them: `error`, `mismatch`, `deleted`, `exists`, `exists-draft`, `exists-hidden`.
 */

import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { dialogs, useDialogs } from './store'
import type { ImportMode } from './store'

const SKILL_NAME = 'pdf-tools'

function copyFor(mode: ImportMode) {
  return {
    title: mode === 'list' ? '导入 skill' : '导入新版本',
    intro: mode === 'list'
      ? '压缩包里的 SKILL.md 决定 skill 的名字；同名 skill 会收到下一版。'
        + '内容已经存在时不会新建。导入后是一版草稿，上线仍在它的版本页。'
      : '压缩包里的 SKILL.md 必须声明 name: ' + SKILL_NAME + '。'
        + '导入后是这个 skill 的一版草稿（内容已经存在时不会新建），线上不变。',
    hint: '只接受 .zip。SKILL.md 要在压缩包根部；所有文件都在同一个顶层文件夹里时，'
      + '文件夹名要与 SKILL.md 的 name 相同。最多 512 个文件、解压后合计 16 MiB。',
  }
}

function cap(value: string, max: number): string {
  return Array.from(value).slice(0, max).join('')
}

export default function ImportDialog() {
  const { importSkill } = useDialogs()
  const open = importSkill !== null
  const mode: ImportMode = importSkill?.mode ?? 'skill'
  const asked = importSkill?.asked ?? 'open'
  const copy = copyFor(mode)

  const [filename, setFilename] = useState('未选择文件')
  const [chosen, setChosen] = useState(false)
  const [name, setName] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<ReactNode>(null)
  const [result, setResult] = useState<ReactNode>(null)
  const [busy, setBusy] = useState(false)
  const file = useRef<HTMLInputElement>(null)
  const pick = useRef<HTMLButtonElement>(null)

  /* Opening resets the sheet. The refused states open it filled in with a chosen file — no server,
     so that is how the sheet is shown in the one state that lights the button. */
  useEffect(() => {
    if (!open) return
    const filled = asked !== 'open'
    setFilename(filled ? 'pdf-tools.zip' : '未选择文件')
    setChosen(filled)
    setName(filled ? '补一份参考' : '')
    setNote(filled ? '把 split 的范围语法写成文档。' : '')
    setBusy(false)
    setError(null)
    setResult(null)
    if (asked === 'error') {
      setError('压缩包里没有 SKILL.md。要把它放在压缩包根部。')
    } else if (asked === 'mismatch') {
      /* 地址名与内容名不一致：两个名字都写出来，再说清改哪一边（voice-spec：错误要给出路）。 */
      setError('地址是 pdf-tools，SKILL.md 里写的是 split。把 SKILL.md 的 name 改成 pdf-tools。')
    } else if (asked === 'deleted') {
      setError(<>这个名字属于一个已删除的 skill。 <a href="?page=skills&show=deleted">去恢复</a></>)
    } else if (asked === 'exists-hidden') {
      setResult('这个 skill 已经有一版同样的内容；那一版现在读不到。')
    } else if (asked === 'exists' || asked === 'exists-draft') {
      const draft = asked === 'exists-draft'
      const label = draft ? '7K3M9QX2' : '@1.2.3'
      setResult(
        <>
          {'这个 skill 已经有一版同样的内容（' + label + '）；没有新建。 '}
          <a href={'?page=skill&tab=versions&version=' + (draft ? '7K3M9QX2' : '1.2.3')}>
            {'查看 ' + label}
          </a>
        </>,
      )
    }
    pick.current?.focus()
  }, [open, asked])

  const ready = chosen && name.trim() !== ''

  return (
    <div
      className="confirm"
      id="importDialog"
      hidden={!open}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          dialogs.closeImport()
        }
      }}
    >
      <div className="confirm-sheet" role="dialog" aria-modal="true" aria-labelledby="importTitle" aria-describedby="importIntro importHint">
        <h2 className="confirm-title" id="importTitle">{copy.title}</h2>
        <p className="confirm-body" id="importIntro">{copy.intro}</p>

        <div className="import-field">
          <span className="confirm-label">压缩包</span>
          <div className="import-file">
            <input type="file" id="importFile" accept=".zip,application/zip" hidden ref={file}
              onChange={() => {
                const picked = !!file.current?.files?.length
                setFilename(picked ? (file.current?.files?.[0].name ?? '未选择文件') : '未选择文件')
                setChosen(picked)
              }}
            />
            <button type="button" className="button is-quiet" id="importPick" ref={pick} onClick={() => file.current?.click()}>
              选择文件
            </button>
            <span className={'import-filename' + (chosen ? ' is-chosen' : '')} id="importFilename">{filename}</span>
          </div>
          <p className="import-hint" id="importHint">{copy.hint}</p>
        </div>

        <label className="confirm-field">
          <span className="confirm-label">版本名称</span>
          <input
            className="confirm-input"
            id="importName"
            type="text"
            autoComplete="off"
            spellCheck={false}
            placeholder="版本名称"
            aria-label="版本名称（最多 40 个字符）"
            value={name}
            onChange={(event) => setName(cap(event.target.value, 40))}
          />
        </label>
        <label className="confirm-field">
          <span className="confirm-label">说明（可选）</span>
          <input
            className="confirm-input"
            id="importNote"
            type="text"
            autoComplete="off"
            spellCheck={false}
            placeholder="说明（可选）"
            aria-label="说明"
            value={note}
            onChange={(event) => setNote(cap(event.target.value, 200))}
          />
        </label>

        <p className="confirm-error" id="importError" role="alert" hidden={error === null}>{error}</p>
        <p className="confirm-result" id="importResult" hidden={result === null}>{result}</p>

        <div className="confirm-acts">
          <button
            type="button"
            className="button is-quiet"
            id="importCancel"
            disabled={busy}
            onClick={() => dialogs.closeImport()}
          >
            取消
          </button>
          <button
            type="button"
            className="button"
            id="importAccept"
            disabled={busy || !ready}
            onClick={() => {
              if (!ready) return
              setBusy(true)
              window.setTimeout(() => {
                setBusy(false)
                dialogs.closeImport()
              }, 600)
            }}
          >
            {busy ? '正在导入…' : '导入'}
          </button>
        </div>
      </div>
    </div>
  )
}
