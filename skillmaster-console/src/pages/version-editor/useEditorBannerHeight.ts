import { useEffect, useRef } from 'react'
import type { DuplicateNotice, EditorLabelStatus } from './types'

/**
 * 横幅出现时，工作区让出它的高度.
 *
 * The workspace's height is computed from the viewport, and the banners sit between it and the
 * head; measuring the open one rather than writing a fixed number is what keeps the page from
 * growing a scrollbar when a sentence wraps on a narrow screen.
 */
export interface EditorBannerHeightOptions {
  conflict: boolean
  duplicate: DuplicateNotice | null
  errorBanner: string | null
  labelStatus: EditorLabelStatus | null
}

export function useEditorBannerHeight({
  conflict,
  duplicate,
  errorBanner,
  labelStatus,
}: EditorBannerHeightOptions) {
  const conflictBanner = useRef<HTMLDivElement | null>(null)
  const duplicateBanner = useRef<HTMLDivElement | null>(null)
  const errorElement = useRef<HTMLDivElement | null>(null)
  const workspace = useRef<HTMLDivElement | null>(null)

  function syncBannerHeight(): void {
    if (workspace.current === null) return
    const open = [conflictBanner.current, duplicateBanner.current, errorElement.current].find(
      (banner) => banner !== null && banner.offsetParent !== null,
    )
    const height =
      open === undefined || open === null
        ? 0
        : open.getBoundingClientRect().height +
          (Number.parseFloat(getComputedStyle(open).marginTop) || 0)
    workspace.current.style.setProperty('--editor-banner', `${height}px`)
  }

  // Vue's `watch` does not fire on mount; React's effect does.
  const bannersSettled = useRef(false)
  useEffect(() => {
    if (!bannersSettled.current) {
      bannersSettled.current = true
      return
    }
    const frame = requestAnimationFrame(syncBannerHeight)
    return () => {
      cancelAnimationFrame(frame)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conflict, duplicate, errorBanner, labelStatus])

  return {
    conflictBanner,
    duplicateBanner,
    errorElement,
    workspace,
    syncBannerHeight,
  }
}
