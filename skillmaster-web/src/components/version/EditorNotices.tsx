import type { MouseEvent as ReactMouseEvent, RefObject } from 'react'

/**
 * The three notices between the header and the workspace: the file changed underneath, the content
 * already exists somewhere else, and the last save failed.
 *
 * **Markup only**, and each notice is drawn from one fact the page owns: `conflict`, `duplicate`,
 * `errorBanner`. The refs come from the page because it measures their height (the banners take
 * space away from the editor, and the editor's gutter follows).
 */
export interface EditorNoticesProps {
  hidden: boolean
  conflict: boolean
  duplicate: { title: string; href: string; label: string } | null
  errorBanner: string | null
  errorTitle: string
  conflictBanner: RefObject<HTMLDivElement | null>
  duplicateBanner: RefObject<HTMLDivElement | null>
  errorElement: RefObject<HTMLDivElement | null>
  reload: () => void
  overwrite: () => Promise<void>
  dismissError: () => void
  guardLeave: (
    event: ReactMouseEvent<HTMLAnchorElement>,
    href: string,
    opener: EventTarget | null,
  ) => void
}

export default function EditorNotices({
  hidden,
  conflict,
  duplicate,
  errorBanner,
  errorTitle,
  conflictBanner,
  duplicateBanner,
  errorElement,
  reload,
  overwrite,
  dismissError,
  guardLeave,
}: EditorNoticesProps) {
  return (
    <>
      {conflict && !hidden ? (
        <div ref={conflictBanner} className="editor-banner is-danger" id="edConflict">
          <div className="editor-banner-text">
            <span className="editor-banner-title">该版本在编辑期间已被修改。</span>
            <span className="editor-banner-note">
              重新加载将丢弃此页未保存的改动；覆盖将用当前内容重写。
            </span>
          </div>
          <div className="editor-banner-acts">
            <button type="button" className="button is-quiet" id="edReload" onClick={reload}>
              重新加载
            </button>
            <button type="button" className="button" id="edOverwrite" onClick={() => void overwrite()}>
              覆盖
            </button>
          </div>
        </div>
      ) : null}
      {duplicate !== null && !hidden ? (
        <div ref={duplicateBanner} className="editor-banner" id="edDuplicate">
          <div className="editor-banner-text">
            <span className="editor-banner-title" id="edDuplicateTitle">
              {duplicate.title}
            </span>
            <span className="editor-banner-note">
              同一 skill 下不能存在相同内容。请修改后保存，或查看该版本。
            </span>
          </div>
          <div className="editor-banner-acts">
            <a
              className="button is-quiet"
              id="edDuplicateLink"
              href={duplicate.href}
              onClick={(event) => guardLeave(event, duplicate.href, event.currentTarget)}
            >
              {duplicate.label}
            </a>
          </div>
        </div>
      ) : null}
      {errorBanner !== null && !hidden ? (
        <div ref={errorElement} className="editor-banner is-danger" id="edError">
          <div className="editor-banner-text">
            <span className="editor-banner-title" id="edErrorTitle">
              {errorTitle}
            </span>
            <span className="editor-banner-note" id="edErrorText">
              {errorBanner}
            </span>
          </div>
          <div className="editor-banner-acts">
            <button
              type="button"
              className="link-button"
              id="edErrorDismiss"
              onClick={dismissError}
            >
              关闭
            </button>
          </div>
        </div>
      ) : null}
    </>
  )
}
