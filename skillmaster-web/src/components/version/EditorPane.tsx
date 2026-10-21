import type {
  ChangeEvent,
  KeyboardEvent as ReactKeyboardEvent,
  RefObject,
} from 'react'

/**
 * The text pane — the bar naming the open file, the gutter, the textarea, the binary notice and the
 * cursor line.
 *
 * **It draws; it does not decide.** Every value here is the page's: which file is open, what its
 * buffer holds, whether it differs from disk, where the caret is. The one thing it owns is nothing:
 * refs arrive from the page because the page is what keeps the gutter and the caret in step with a
 * scroll.
 */
export interface EditorPaneProps {
  current: string
  currentDirty: boolean
  currentBinary: boolean
  currentText: string
  gutterText: string
  infoText: string
  caret: { line: number; column: number }
  /** The download address for the open file — the binary notice's only way out. */
  fileHref: string
  gutter: RefObject<HTMLDivElement | null>
  textarea: RefObject<HTMLTextAreaElement | null>
  onTextInput: (event: ChangeEvent<HTMLTextAreaElement>) => void
  onTextScroll: () => void
  onTextKeydown: (event: ReactKeyboardEvent<HTMLTextAreaElement>) => void
  onCaret: () => void
}

export default function EditorPane({
  current,
  currentDirty,
  currentBinary,
  currentText,
  gutterText,
  infoText,
  caret,
  fileHref,
  gutter,
  textarea,
  onTextInput,
  onTextScroll,
  onTextKeydown,
  onCaret,
}: EditorPaneProps) {
  return (
        <div className="editor-pane">
          <div className="editor-bar">
            <svg className="editor-bar-icon" viewBox="0 0 24 24" aria-hidden="true">
              <use href="#fileMark" />
            </svg>
            <span className="editor-path" id="edPath">
              {current}
            </span>
            <span className="editor-unsaved" id="edUnsaved" style={{ display: (!currentDirty) ? 'none' : undefined }}>
              <i className="dot" />
              未保存的改动
            </span>
          </div>
          <div className="editor-body">
            <div
              ref={gutter}
              className="editor-gutter"
              id="edGutter"
              aria-hidden="true"
              style={{ display: (currentBinary) ? 'none' : undefined }}
            >
              {gutterText}
            </div>
            <textarea
              ref={textarea}
              className="editor-text"
              id="edText"
              spellCheck={false}
              aria-label="文件内容"
              value={currentText}
              style={{ display: (currentBinary) ? 'none' : undefined }}
              onChange={onTextInput}
              onScroll={onTextScroll}
              onKeyDown={onTextKeydown}
              onKeyUp={onCaret}
              onClick={onCaret}
              onSelect={onCaret}
            />
            <div className="editor-binary" id="edBinary" style={{ display: (!currentBinary) ? 'none' : undefined }}>
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <use href="#fileMark" />
              </svg>
              <p>
                <strong>二进制文件不能在此编辑。</strong>
                <br />
                请下载到本地修改，再通过 CLI 写回。
              </p>
              <a className="button is-quiet" id="edDownload" href={fileHref}>
                下载文件
              </a>
            </div>
          </div>
          <div className="editor-foot">
            <span id="edCursor">
              {currentBinary ? '只读' : `第 ${caret.line} 行，第 ${caret.column} 列`}
            </span>
            <span id="edInfo">{infoText}</span>
          </div>
        </div>
  )
}
