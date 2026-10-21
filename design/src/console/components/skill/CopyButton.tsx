/**
 * 复制与对勾: the same drawing everywhere on the page (24px box, 1.8 stroke, round caps).
 *
 * `file://` does not always have `navigator.clipboard`; the fallback makes one `execCommand`
 * attempt, and when neither path works the button does not pretend it did.
 */

import { useEffect, useRef, useState } from 'react'

const COPY_ICON = (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <rect x="9" y="9" width="11" height="11" rx="2" stroke="currentColor" strokeWidth="1.8" />
    <path d="M5 15V6a2 2 0 0 1 2-2h9" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
  </svg>
)

const CHECK_ICON = (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path d="M5 12.5 9.5 17 19 7.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)

function writeClipboard(text: string, done: () => void, fail: () => void) {
  const fallback = () => {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    let ok = false
    try { ok = document.execCommand('copy') } catch { ok = false }
    document.body.removeChild(area)
    if (ok) done()
    else fail()
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done, fallback)
  } else {
    fallback()
  }
}

export default function CopyButton({ text, label }: { text: string; label: string }) {
  const [done, setDone] = useState(false)
  const [failed, setFailed] = useState(false)
  const timer = useRef<number | null>(null)

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current)
  }, [])

  const title = done ? '已复制' : failed ? '复制失败，请手动选择' : '复制' + label

  return (
    <button
      type="button"
      className={'vp-copy' + (done ? ' is-done' : '')}
      aria-label={done ? '已复制' + label : '复制' + label}
      title={title}
      onClick={() => {
        writeClipboard(text, () => {
          setDone(true)
          setFailed(false)
          if (timer.current !== null) window.clearTimeout(timer.current)
          timer.current = window.setTimeout(() => setDone(false), 1200)
        }, () => {
          setFailed(true)
        })
      }}
    >
      {done ? CHECK_ICON : COPY_ICON}
      <span className="visually-hidden" aria-live="polite">{done ? '已复制' : ''}</span>
    </button>
  )
}
