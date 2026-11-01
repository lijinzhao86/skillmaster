import { useEffect } from 'react'

/**
 * The listeners the editor adds once at mount, and the first load.
 *
 * Every handler is expected to read refs rather than the values captured in this render — the
 * listener list is never rebuilt, which is what lets a menu opened later still be dismissed by it.
 */
export interface EditorLifecycleOptions {
  load: () => Promise<void>
  syncBannerHeight: () => void
  onKeydown: (event: KeyboardEvent) => void
  onDocumentMouseDown: (event: MouseEvent) => void
  onBeforeUnload: (event: BeforeUnloadEvent) => void
  setTreeCollapsed: (next: boolean) => void
}

export function useEditorLifecycle({
  load,
  syncBannerHeight,
  onKeydown,
  onDocumentMouseDown,
  onBeforeUnload,
  setTreeCollapsed,
}: EditorLifecycleOptions) {
  useEffect(() => {
    document.addEventListener('keydown', onKeydown)
    document.addEventListener('mousedown', onDocumentMouseDown, true)
    window.addEventListener('beforeunload', onBeforeUnload)
    window.addEventListener('resize', syncBannerHeight)
    if (window.innerWidth <= 900) setTreeCollapsed(true)
    void load().then(() => syncBannerHeight())
    return () => {
      document.removeEventListener('keydown', onKeydown)
      document.removeEventListener('mousedown', onDocumentMouseDown, true)
      window.removeEventListener('beforeunload', onBeforeUnload)
      window.removeEventListener('resize', syncBannerHeight)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
}
