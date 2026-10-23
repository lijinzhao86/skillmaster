import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { importFailure, type ImportFailure } from '../../api/errors'
import { importSkill, importVersion } from '../../api/skills'
import type { SubmitResponse } from '../../api/types'
import { canMaintain, canWrite } from '../../versionActions'

/**
 * The import sheet (design §导入与下载): one file, a version name, an optional note.
 *
 * **The same sheet for both entries.** The version list's 「导入压缩包」 adds a version to this
 * skill; 「我的 skill」's 「导入本地 skill」 brings the package into the caller's own namespace and
 * creates the skill if the name is new. What differs is the title, the introduction and the target,
 * not the table.
 *
 * **The page does not read the archive.** The version name is whatever the person typed — the
 * package's own metadata is not consulted here, by design; a name left blank is refused before the
 * request rather than sent to be refused by the server (design §导入与下载).
 *
 * **「内容已存在」 is not a failure.** It is an idempotent replay: the sheet stays open, says which
 * version already holds this content, and offers a link when this person can read that version —
 * a link to a 404 would be worse than none.
 *
 * **What changed in the port.** `props.open` was watched to reset the sheet on every opening; here
 * an effect on `open` does the same. The two `@input` clamps survive as `onChange` handlers, which is
 * how a controlled input reports the edit anyway.
 */
export interface ImportDialogProps {
  open: boolean
  mode: 'version' | 'skill'
  /** The skill an import adds to; unused in `skill` mode, where the package names it. */
  namespace: string
  name: string
  /**
   * The caller's standing on the skill an import adds to — `owner` in `skill` mode, where the
   * package lands in the caller's own namespace. Read by exactly one question: whether a replay's
   * version can be linked to (design §导入与下载: a link to a 404 is worse than none).
   */
  rung: string
  onClose: () => void
  onNavigate: (href: string) => void
}

const HINT =
  '只接受 .zip。SKILL.md 要在压缩包根部；所有文件都在同一个顶层文件夹里时，文件夹名要与 SKILL.md 的 name 相同。最多 512 个文件、解压后合计 16 MiB。'

export default function ImportDialog({
  open,
  mode,
  namespace,
  name,
  rung,
  onClose,
  onNavigate,
}: ImportDialogProps) {
  const [file, setFile] = useState<File | null>(null)
  const [fileName, setFileName] = useState('未选择文件')
  const [versionName, setVersionName] = useState('')
  const [note, setNote] = useState('')
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState<ImportFailure | null>(null)
  const [result, setResult] = useState<{ text: string; href: string | null; label: string } | null>(
    null,
  )
  const pickButton = useRef<HTMLButtonElement | null>(null)
  const input = useRef<HTMLInputElement | null>(null)

  const title = mode === 'version' ? '导入新版本' : '导入 skill'
  const intro =
    mode === 'version'
      ? `压缩包里的 SKILL.md 必须声明 name: ${name}。导入后是这个 skill 的一版草稿（内容已经存在时不会新建），线上不变。`
      : '压缩包里的 SKILL.md 决定 skill 的名字；同名 skill 会收到下一版。内容已经存在时不会新建。导入后是一版草稿，上线仍在它的版本页。'
  const acceptDisabled = pending || file === null || versionName.trim() === ''

  useEffect(() => {
    if (!open) {
      return
    }
    setFile(null)
    setFileName('未选择文件')
    setVersionName('')
    setNote('')
    setFailure(null)
    setResult(null)
    setPending(false)
    const timer = requestAnimationFrame(() => {
      pickButton.current?.focus()
    })
    return () => {
      cancelAnimationFrame(timer)
    }
  }, [open])

  function clamp(value: string, limit: number): string {
    const chars = Array.from(value)
    return chars.length > limit ? chars.slice(0, limit).join('') : value
  }

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

  function suffixOf(version: SubmitResponse['version']): string | null {
    if (version.state === 'draft') {
      return version.draft_id
    }
    // A published version — and a revoked one, which was published once — keeps its address: the
    // number, or the digest when it never got one. A discarded row is not addressed at all here: it is
    // never linked (see `canReadReplay`).
    if (version.state === 'discarded') {
      return null
    }
    return version.name ?? version.digest
  }

  /** Whether this caller may open the version a replay landed on — the link's only condition. */
  function canReadReplay(version: SubmitResponse['version']): boolean {
    switch (version.state) {
      case 'discarded':
        // Never linked, whoever is looking: the sentence itself says it cannot be read (design).
        return false
      case 'revoked':
        return canMaintain(rung)
      case 'draft':
        return canWrite(rung)
      default:
        return true
    }
  }

  async function submit(): Promise<void> {
    if (acceptDisabled || file === null) {
      return
    }
    setPending(true)
    setFailure(null)
    setResult(null)
    const answer =
      mode === 'version'
        ? await importVersion(namespace, name, file, versionName.trim(), note.trim())
        : await importSkill(file, versionName.trim(), note.trim())
    setPending(false)

    if (!answer.ok) {
      setFailure(importFailure(answer, 'import'))
      return
    }

    const version = answer.data.version
    if (answer.data.created) {
      const created = suffixOf(version)
      if (created !== null) {
        onNavigate(versionPage(answer.data.namespace, answer.data.name, created))
        return
      }
    }

    const suffix = suffixOf(version)
    if (suffix === null || !canReadReplay(version)) {
      setResult({ text: '这个 skill 已经有一版同样的内容；那一版现在读不到。', href: null, label: '' })
      return
    }
    // **The address is the version's own.** A published version is written `@1.2.3`; a draft is the
    // bare version code, because that is the only spelling a draft answers to (design §导入与下载).
    const address = version.state === 'draft' ? suffix : `@${suffix}`
    setResult({
      text: `这个 skill 已经有一版同样的内容（${address}）；没有新建。`,
      href: versionPage(answer.data.namespace, answer.data.name, suffix),
      label: `查看 ${address}`,
    })
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
        aria-labelledby="importTitle"
        aria-describedby="importIntro importHint"
      >
        <h2 id="importTitle" className="confirm-title">
          {title}
        </h2>
        <p id="importIntro" className="confirm-body">
          {intro}
        </p>

        <div className="import-field">
          <span className="confirm-label">压缩包</span>
          <div className="import-file">
            <input
              ref={input}
              type="file"
              accept=".zip,application/zip"
              hidden
              onChange={(event) => {
                const picked = event.target.files?.[0] ?? null
                setFile(picked)
                setFileName(picked === null ? '未选择文件' : picked.name)
                setFailure(null)
              }}
            />
            <button
              ref={pickButton}
              type="button"
              className="button is-quiet"
              onClick={() => input.current?.click()}
            >
              选择文件
            </button>
            <span className={file !== null ? 'import-filename is-chosen' : 'import-filename'}>
              {fileName}
            </span>
          </div>
          <p id="importHint" className="import-hint">
            {HINT}
          </p>
        </div>

        <label className="confirm-field">
          <span className="confirm-label">版本名称</span>
          <input
            className="confirm-input"
            type="text"
            value={versionName}
            autoComplete="off"
            spellCheck={false}
            placeholder="版本名称"
            aria-label="版本名称（最多 40 个字符）"
            disabled={pending}
            onChange={(event) => setVersionName(clamp(event.target.value, 40))}
          />
        </label>
        <label className="confirm-field">
          <span className="confirm-label">说明（可选）</span>
          <input
            className="confirm-input"
            type="text"
            value={note}
            autoComplete="off"
            spellCheck={false}
            placeholder="说明（可选）"
            aria-label="说明"
            disabled={pending}
            // The design caps the note in the same place it caps the name (「至多 200 个码点」), so
            // the field stops at the limit instead of handing the server a value it will refuse.
            onChange={(event) => setNote(clamp(event.target.value, 200))}
          />
        </label>

        {failure !== null ? (
          <p className="confirm-error" role="alert">
            {failure.message}
            {failure.action !== undefined ? (
              <a href={failure.action.href}>{failure.action.label}</a>
            ) : null}
          </p>
        ) : result !== null ? (
          <p className="confirm-result">
            {result.text}
            {result.href !== null ? <a href={result.href}>{result.label}</a> : null}
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
            {pending ? '正在导入…' : '导入'}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * A **version page**'s address, which is not `/name@pin`.
 *
 * The path form `/skills/{ns}/{name}@{pin}` selects the skill (and the pin's files); the version as
 * a *record* — title, state, actions, and the 「查看文件」 next to it — is `?tab=versions&version=`.
 * Landing on the path form after an import opened 信息, and the replay link labelled 「查看 @1.2.3」
 * did the same (design §导入与下载: 成功后落到新草稿的版本页).
 */
function versionPage(namespace: string, name: string, pin: string): string {
  return `/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}?tab=versions&version=${encodeURIComponent(pin)}`
}
