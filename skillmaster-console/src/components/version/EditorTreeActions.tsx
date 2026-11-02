import type { RefObject } from 'react'

/**
 * The file tree's three actions: upload, new file, new folder (v39 adds the first).
 *
 * Split out when the upload button pushed this file past its size limit; the markup is the design's,
 * unchanged.
 */
export default function EditorTreeActions({
  inputRef,
  onFiles,
  onCreate,
}: {
  inputRef: RefObject<HTMLInputElement | null>
  onFiles: () => void
  onCreate: (kind: 'file' | 'dir') => void
}) {
  return (
            <span className="editor-tree-acts">
              <button
                type="button"
                className="editor-icon-button"
                id="edUpload"
                aria-label="上传文件"
                title="上传文件"
                onClick={() => inputRef.current?.click()}
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M12 16V4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
                  <path d="M7.5 8.5 12 4l4.5 4.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
                  <path d="M4.5 15v4a1 1 0 0 0 1 1h13a1 1 0 0 0 1-1v-4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
                </svg>
              </button>
              <input
                ref={inputRef}
                type="file"
                multiple
                hidden
                aria-hidden="true"
                tabIndex={-1}
                onChange={onFiles}
              />
              <button
                type="button"
                className="editor-icon-button"
                id="edNewFile"
                aria-label="新建文件"
                title="新建文件"
                onClick={() => onCreate('file')}
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M6 3.5h7L17.5 8v5.5"
                    stroke="currentColor"
                    strokeWidth="1.7"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                  <path
                    d="M13 3.5V8h4.5"
                    stroke="currentColor"
                    strokeWidth="1.7"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                  <path
                    d="M6 3.5V20a1 1 0 0 0 1 1h6"
                    stroke="currentColor"
                    strokeWidth="1.7"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                  <path
                    d="M18 15v6M15 18h6"
                    stroke="currentColor"
                    strokeWidth="1.7"
                    strokeLinecap="round"
                  />
                </svg>
              </button>
              <button
                type="button"
                className="editor-icon-button"
                id="edNewFolder"
                aria-label="新建文件夹"
                title="新建文件夹"
                onClick={() => onCreate('dir')}
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M3 7a2 2 0 0 1 2-2h4l2 2h4a2 2 0 0 1 2 2v4"
                    stroke="currentColor"
                    strokeWidth="1.7"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                  <path
                    d="M3 7v10a2 2 0 0 0 2 2h8"
                    stroke="currentColor"
                    strokeWidth="1.7"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                  <path
                    d="M18 15v6M15 18h6"
                    stroke="currentColor"
                    strokeWidth="1.7"
                    strokeLinecap="round"
                  />
                </svg>
              </button>
            </span>
  )
}
