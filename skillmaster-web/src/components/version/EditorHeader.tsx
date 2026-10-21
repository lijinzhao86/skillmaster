import type { ChangeEvent, MouseEvent as ReactMouseEvent } from 'react'

/**
 * The version's identity above the workspace: its name, its state, its note, who and when, and the
 * two actions that belong to that identity (查看版本详情 and 保存).
 *
 * **Markup only.** The name and note are the page's — they are saved by the page's one save flow —
 * and so is whether saving is possible right now. What stands here is the chrome those facts are
 * drawn into; the props are that chrome's whole reading of the page.
 */
export interface EditorHeaderProps {
  hidden: boolean
  isNew: boolean
  draftName: string
  draftNote: string
  onNameInput: (event: ChangeEvent<HTMLInputElement>) => void
  onNoteInput: (event: ChangeEvent<HTMLInputElement>) => void
  commitName: () => Promise<void>
  commitNote: () => Promise<void>
  onNameEnter: () => void
  onNoteEnter: () => void
  /** 「矩阵草图　·　4 个文件」 — the facts line, already worded by the page. */
  facts: string
  labelStatus: { text: string; danger: boolean } | null
  canSave: boolean
  saving: boolean
  save: () => Promise<void>
  /** The version's record page, when there is a row to view. */
  viewHref: string
  /** A link out asks about unsaved work first; the page owns the question. */
  guardLeave: (
    event: ReactMouseEvent<HTMLAnchorElement>,
    href: string,
    opener: EventTarget | null,
  ) => void
}

export default function EditorHeader({
  hidden,
  isNew,
  draftName,
  draftNote,
  onNameInput,
  onNoteInput,
  commitName,
  commitNote,
  onNameEnter,
  onNoteEnter,
  facts,
  labelStatus,
  canSave,
  saving,
  save,
  viewHref,
  guardLeave,
}: EditorHeaderProps) {
  return (
      <header className="vp-head editor-head" style={{ display: hidden ? 'none' : undefined }}>
        <h1 className="visually-hidden">编辑版本</h1>
        <div className="editor-head-top">
          <div className="vp-title-row">
            <span className="vp-title-name">
              <input
                className="editor-name-input"
                id="edName"
                type="text"
                placeholder="版本名称"
                aria-label="版本名称（最多 40 个字符）"
                title="最多 40 个字符；中文、英文、数字与符号都可以"
                autoComplete="off"
                spellCheck={false}
                value={draftName}
                onChange={onNameInput}
                onBlur={() => void commitName()}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault()
                    onNameEnter()
                  }
                }}
              />
            </span>
            <span className="version-states" id="edStates">
              {isNew ? (
                <span className="chip is-plain">
                  <i className="dot" />
                  <span className="chip-text">尚未保存</span>
                </span>
              ) : (
                <span className="chip is-draft">
                  <i className="dot" />
                  <span className="chip-text">草稿</span>
                </span>
              )}
            </span>
          </div>
          <div className="vp-head-act">
            {!isNew ? (
              <a
                className="button is-quiet"
                id="edView"
                href={viewHref}
                onClick={(event) => guardLeave(event, viewHref, event.currentTarget)}
              >
                查看版本详情
              </a>
            ) : null}
            <button type="button" className="button" id="edSave" disabled={!canSave} onClick={() => void save()}>
              {saving ? '保存中…' : '保存'}
            </button>
          </div>
        </div>
        <div className="editor-label-row">
          <label className="editor-label" htmlFor="edNote">
            说明
          </label>
          <input
            className="control"
            id="edNote"
            type="text"
            placeholder="说明（可选）"
            aria-label="说明"
            autoComplete="off"
            spellCheck={false}
            value={draftNote}
            onChange={onNoteInput}
            onBlur={() => void commitNote()}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                onNoteEnter()
              }
            }}
          />
        </div>
        {labelStatus !== null ? (
          <p
            className={labelStatus.danger ? 'editor-label-status is-danger' : 'editor-label-status'}
            id="edLabelStatus"
          >
            {labelStatus.text}
          </p>
        ) : null}
        <p className="editor-facts">
          <span className="vp-meta" id="edMeta">
            {facts}
          </span>
          {isNew ? (
            <span className="editor-hint" id="edHint">
              首次内容改动时创建版本，随后显示在版本列表中。
            </span>
          ) : null}
        </p>
      </header>
  )
}
