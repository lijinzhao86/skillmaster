import { useEffect, useRef, useState } from 'react'
import { formatSize } from '../../format'
import type { AuthoredVersion } from '../../api/types'
import { formatTime } from '../../time'
import { statusOf, statusText, versionSuffix } from '../../version'

/**
 * This version's facts, in the overview's sticky rail.
 *
 * **The author and the caller see different facts.** The caller sees the four things the consumption
 * contract carries: version number, status, first publish time and address. The name, submitter,
 * submission time, state-change history, file counts and content digest are author-plane facts; the
 * digest is still returned by the API for pinning, but the page does not draw it for a caller.
 *
 * **Two repeats are deliberately not drawn**: 「最近状态变更」 when it equals 「首次上线」 (it is the
 * same sentence), and the address's version suffix as a row of its own. **「版本码」 replaces the old
 * draft-only 「版本标识」** (v7): every author-plane state draws its row id, third — after 名称 and
 * 版本号 — while the caller keeps four rows and no code. The address already ends in that code for a
 * draft; the row is the id as a single copyable value, not the same string a second time.
 */
export interface Props {
  version: AuthoredVersion
  namespace: string
  name: string
  rung: string
}

interface Row {
  label: string
  value: string
  copy?: string
}

export default function VersionFacts({ version, namespace, name, rung }: Props) {
  const status = statusOf(version)
  const caller = rung === 'invoke'
  const address = `${namespace}/${name}@${versionSuffix(version)}`

  const [copied, setCopied] = useState<string | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const timer = useRef<number | null>(null)

  /**
   * The verb before the state-change time, or null when the API cannot name the transition.
   *
   * **A deliberate deviation.** The design distinguishes 「成为历史版本」 (a rollback displaced this
   * row) from 「重新上架于」 (a take-down was undone) with two demo fixtures, `supersededAt` and
   * `restoredAt`. The server stores one `state_at` and does not record which transition moved it
   * (TD §数据模型), so a restored, still-historical row and a displaced one are indistinguishable here.
   * Rather than choose one and say something false for the other half, a historical row gets the row
   * label alone — 「最近状态变更」 with the time — which is true for both. The remaining four statuses
   * name their transition because their state does.
   */
  function stateChangeText(): string | null {
    switch (status) {
      case 'live':
        return '上线于'
      case 'revoked':
        return '下架于'
      case 'discarded':
        return '丢弃于'
      default:
        return null
    }
  }

  let rows: Row[]
  if (caller) {
    rows = [
      {
        label: '版本号',
        value: version.name === null ? '—' : `@${version.name}`,
        copy: version.name === null ? undefined : `@${version.name}`,
      },
      { label: '状态', value: statusText(status) },
      {
        label: '首次上线',
        value: version.first_published_at === null ? '—' : formatTime(version.first_published_at),
      },
      { label: '地址', value: address, copy: address },
    ]
  } else {
    const list: Row[] = [
      { label: '名称', value: version.draft_name ?? '—', copy: version.draft_name ?? undefined },
      {
        label: '版本号',
        value: version.name === null ? '—' : `@${version.name}`,
        copy: version.name === null ? undefined : `@${version.name}`,
      },
      {
        label: '版本码',
        value: version.code ?? version.draft_id ?? '—',
        copy: version.code ?? version.draft_id ?? undefined,
      },
      { label: '状态', value: statusText(status) },
      { label: '提交', value: `${version.submitted_by ?? '—'} · ${formatTime(version.submitted_at)}` },
      {
        label: '首次上线',
        value: version.first_published_at === null ? '—' : formatTime(version.first_published_at),
      },
    ]
    if (version.state_at !== null && version.state_at !== version.first_published_at) {
      const verb = stateChangeText()
      list.push({
        label: '最近状态变更',
        value: verb === null ? formatTime(version.state_at) : `${verb} ${formatTime(version.state_at)}`,
      })
    }
    list.push({ label: '内容摘要', value: version.digest, copy: version.digest })
    list.push({
      label: '文件',
      value: `${version.file_count} 个文件 · ${formatSize(version.total_bytes)}`,
    })
    list.push({ label: '地址', value: address, copy: address })
    rows = list
  }

  function done(label: string): void {
    setCopied(label)
    if (timer.current !== null) {
      window.clearTimeout(timer.current)
    }
    timer.current = window.setTimeout(() => {
      setCopied(null)
    }, 1200)
  }

  /** Clipboard first (file:// is not always available), then a hidden textarea; never a fake success. */
  function copy(text: string, label: string): void {
    const fallback = (): void => {
      try {
        const area = document.createElement('textarea')
        area.value = text
        area.setAttribute('readonly', '')
        area.style.position = 'fixed'
        area.style.opacity = '0'
        document.body.appendChild(area)
        area.select()
        const ok = document.execCommand('copy')
        area.remove()
        if (ok) {
          done(label)
        } else {
          setFailed(label)
        }
      } catch {
        setFailed(label)
      }
    }
    setFailed(null)
    if (navigator.clipboard?.writeText !== undefined) {
      navigator.clipboard.writeText(text).then(() => done(label), fallback)
    } else {
      fallback()
    }
  }

  useEffect(
    () => () => {
      if (timer.current !== null) {
        window.clearTimeout(timer.current)
      }
    },
    [],
  )

  return (
    <aside className="vp-facts" aria-label="版本事实">
      <h2 className="vp-facts-title">版本事实</h2>
      <dl className="vp-facts-list">
        {rows.map((row) => (
          <div key={row.label}>
            <dt>{row.label}</dt>
            <dd>
              <span className="vp-fact-line">
                <span>{row.value}</span>
                {row.copy !== undefined ? (
                  <button
                    type="button"
                    className={copied === row.label ? 'vp-copy is-done' : 'vp-copy'}
                    aria-label={copied === row.label ? `已复制${row.label}` : `复制${row.label}`}
                    title={
                      failed === row.label
                        ? '复制失败，请手动选择'
                        : copied === row.label
                          ? '已复制'
                          : `复制${row.label}`
                    }
                    onClick={() => copy(row.copy ?? '', row.label)}
                  >
                    {copied !== row.label ? (
                      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                        <rect
                          x="9"
                          y="9"
                          width="11"
                          height="11"
                          rx="2"
                          stroke="currentColor"
                          strokeWidth="1.8"
                        />
                        <path
                          d="M5 15V6a2 2 0 0 1 2-2h9"
                          stroke="currentColor"
                          strokeWidth="1.8"
                          strokeLinecap="round"
                        />
                      </svg>
                    ) : (
                      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                        <path
                          d="M5 12.5 9.5 17 19 7.5"
                          stroke="currentColor"
                          strokeWidth="1.8"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                        />
                      </svg>
                    )}
                  </button>
                ) : null}
                <span className="visually-hidden" aria-live="polite">
                  {copied === row.label ? '已复制' : ''}
                </span>
              </span>
            </dd>
          </div>
        ))}
      </dl>
    </aside>
  )
}
