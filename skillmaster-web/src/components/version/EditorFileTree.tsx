import type {
  Dispatch,
  KeyboardEvent as ReactKeyboardEvent,
  MouseEvent as ReactMouseEvent,
  SetStateAction,
} from 'react'
import type { CreateState, MenuState, RenameState, TreeRow } from '../../editor/treeState'

/**
 * 文件 — the draft's file tree: the search box, the new-entry input, every row, and each row's menu.
 *
 * **Why the interface is this long.** The tree shares its state with the page on purpose: a menu is
 * dismissed by the page's document listener, a rename writes the draft's file list, a delete opens a
 * confirmation the page owns, and the rows are computed from the page's search terms. What stands
 * here is therefore the tree's *whole* interface made explicit — 27 names, each one a thing this
 * subtree uses — instead of an implicit reach into one 1,900-line scope. The width is the point: it
 * is now a list somebody can read and shrink, and the way to shrink it is to move the draft's state
 * into a reducer/context, which is a rewrite rather than a move.
 */
export interface EditorFileTreeProps {
  rows: TreeRow[]
  /** How many files the draft holds — the two counts beside the two 「文件」 labels. */
  fileCount: number
  /** The page collapses the tree on a narrow window, so this flag is the page's. */
  treeCollapsed: boolean
  setTreeCollapsed: (next: boolean) => void
  current: string
  query: string
  setQuery: (value: string) => void
  onSearchKeydown: (event: ReactKeyboardEvent<HTMLInputElement>) => void
  clearSearch: () => void
  create: CreateState | null
  setCreate: Dispatch<SetStateAction<CreateState | null>>
  createItem: () => Promise<void>
  cancelCreate: () => void
  startCreate: (kind: 'file' | 'dir') => void
  rename: RenameState | null
  setRename: Dispatch<SetStateAction<RenameState | null>>
  commitRename: () => Promise<void>
  cancelRename: () => void
  beginRename: (path: string) => void
  menu: MenuState | null
  toggleMenu: (event: ReactMouseEvent<HTMLButtonElement>, path: string) => Promise<void>
  askDelete: (path: string) => void
  askDeleteFolder: (path: string) => void
  buffers: Record<string, string>
  saved: Record<string, string>
  openFile: (path: string) => void
  toggleFolder: (path: string) => void
}

export default function EditorFileTree({
  rows,
  fileCount,
  treeCollapsed,
  setTreeCollapsed,
  current,
  query,
  setQuery,
  onSearchKeydown,
  clearSearch,
  create,
  setCreate,
  createItem,
  cancelCreate,
  startCreate,
  rename,
  setRename,
  commitRename,
  cancelRename,
  beginRename,
  menu,
  toggleMenu,
  askDelete,
  askDeleteFolder,
  buffers,
  saved,
  openFile,
  toggleFolder,
}: EditorFileTreeProps) {
  return (
        <aside
          className={treeCollapsed ? 'editor-tree is-collapsed' : 'editor-tree'}
          aria-label="文件"
        >
          <button
            type="button"
            className="editor-tree-toggle"
            id="edTreeToggle"
            aria-expanded={!treeCollapsed}
            aria-controls="edFiles"
            onClick={() => setTreeCollapsed(!treeCollapsed)}
          >
            文件 <span className="count" id="edTreeToggleCount">{fileCount}</span>
            <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path
                d="M4 6l4 4 4-4"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </button>
          <div className="editor-tree-head">
            <span className="editor-tree-title">
              文件 <span className="count" id="edFileCount">{fileCount}</span>
            </span>
            <span className="editor-tree-acts">
              <button
                type="button"
                className="editor-icon-button"
                id="edNewFile"
                aria-label="新建文件"
                title="新建文件"
                onClick={() => startCreate('file')}
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
                onClick={() => startCreate('dir')}
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
          </div>
          <label className="search editor-search">
            <span className="visually-hidden">搜索文件</span>
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
              <line
                x1="15.4"
                y1="15.4"
                x2="20.6"
                y2="20.6"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
              />
            </svg>
            <input
              className="control"
              id="edFileQuery"
              type="search"
              autoComplete="off"
              spellCheck={false}
              aria-label="搜索文件路径"
              placeholder="搜索文件"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={onSearchKeydown}
            />
            <button
              style={{ display: (query === '') ? 'none' : undefined }}
              type="button"
              className="search-clear"
              id="edFileClear"
              aria-label="清除搜索"
              onClick={clearSearch}
            >
              ×
            </button>
          </label>
          <ul className="editor-files" id="edFiles">
            {create !== null ? (
              <li className="editor-file is-creating" data-kind={create.kind}>
                <div className="editor-file-open is-creating" style={{ paddingLeft: '6px' }}>
                  <span className="editor-file-chevron is-empty" aria-hidden="true" />
                  {create.kind === 'dir' ? (
                    <svg
                      className="editor-file-icon"
                      viewBox="0 0 24 24"
                      fill="none"
                      aria-hidden="true"
                    >
                      <path
                        d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"
                        stroke="currentColor"
                        strokeWidth="1.6"
                        strokeLinejoin="round"
                      />
                    </svg>
                  ) : (
                    <svg
                      className="editor-file-icon"
                      viewBox="0 0 24 24"
                      aria-hidden="true"
                    >
                      <use href="#fileMark" />
                    </svg>
                  )}
                  <input
                    className="editor-create-input"
                    type="text"
                    autoComplete="off"
                    spellCheck={false}
                    value={create.value}
                    placeholder={create.kind === 'dir' ? '文件夹名或路径' : '文件名或路径'}
                    aria-label={create.kind === 'dir' ? '新建文件夹的路径' : '新建文件的路径'}
                    onChange={(event) =>
                      setCreate({ ...create, value: event.target.value, error: null })
                    }
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') {
                        event.preventDefault()
                        void createItem()
                      } else if (event.key === 'Escape') {
                        event.preventDefault()
                        cancelCreate()
                      }
                    }}
                  />
                </div>
                <p className="editor-create-hint">按 Enter 创建，按 Esc 取消</p>
                {create.error !== null ? (
                  <p className="editor-field-error" role="alert">
                    {create.error}
                  </p>
                ) : null}
              </li>
            ) : null}
            {rows.map((row) => (
              <li
                key={row.path}
                className={[
                  'editor-file',
                  row.dir ? 'is-dir' : '',
                  !row.dir && current === row.path ? 'is-current' : '',
                  rename !== null && rename.path === row.path ? 'is-editing' : '',
                ]
                  .filter((label) => label !== '')
                  .join(' ')}
                data-path={row.path}
                data-kind={row.dir ? 'dir' : 'file'}
              >
                {rename !== null && rename.path === row.path ? (
                  <>
                    <input
                      className="editor-rename-input"
                      type="text"
                      aria-label="新路径"
                      value={rename.value}
                      onChange={(event) =>
                        setRename({ ...rename, value: event.target.value, error: null })
                      }
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          event.preventDefault()
                          void commitRename()
                        } else if (event.key === 'Escape') {
                          event.preventDefault()
                          cancelRename()
                        }
                      }}
                    />
                    <span className="editor-rename-acts">
                      <button
                        type="button"
                        className="link-button"
                        onClick={() => void commitRename()}
                      >
                        重命名
                      </button>
                      <button type="button" className="link-button" onClick={cancelRename}>
                        取消
                      </button>
                    </span>
                    {rename.error !== null ? (
                      <p className="editor-field-error">{rename.error}</p>
                    ) : null}
                  </>
                ) : (
                  <>
                    <button
                      type="button"
                      className="editor-file-open"
                      style={{ paddingLeft: `${6 + row.depth * 14}px` }}
                      aria-expanded={row.dir ? row.expanded : undefined}
                      aria-label={
                        row.dir
                          ? `${row.expanded ? '收起' : '展开'} ${row.path}`
                          : `打开 ${row.path}`
                      }
                      onClick={row.dir ? () => toggleFolder(row.path) : () => openFile(row.path)}
                    >
                      {row.dir ? (
                        <svg
                          className="editor-file-chevron"
                          viewBox="0 0 16 16"
                          fill="none"
                          aria-hidden="true"
                        >
                          <path
                            d="M6 4l4 4-4 4"
                            stroke="currentColor"
                            strokeWidth="1.6"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                          />
                        </svg>
                      ) : (
                        <span className="editor-file-chevron is-empty" aria-hidden="true" />
                      )}
                      {row.dir ? (
                        <svg
                          className="editor-file-icon"
                          viewBox="0 0 24 24"
                          fill="none"
                          aria-hidden="true"
                        >
                          <path
                            d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"
                            stroke="currentColor"
                            strokeWidth="1.6"
                            strokeLinejoin="round"
                          />
                        </svg>
                      ) : (
                        <svg
                          className="editor-file-icon"
                          viewBox="0 0 24 24"
                          aria-hidden="true"
                        >
                          <use href="#fileMark" />
                        </svg>
                      )}
                      <span className="editor-file-path">{row.name}</span>
                      {!row.dir && buffers[row.path] !== saved[row.path] ? (
                        <span className="editor-file-dot" title="未保存的改动" />
                      ) : null}
                    </button>
                    {/* SKILL.md has no menu: both actions are refusals, and a menu whose every item is
                        disabled is a promise broken twice. A folder with files has none either —
                        deleting it would mean deleting content. */}
                    {row.dir ? row.empty : row.path !== 'SKILL.md' ? (
                      <button
                        type="button"
                        className="editor-file-menu"
                        data-menu={row.path}
                        aria-label={`操作：${row.path}`}
                        title="操作"
                        onClick={(event) => void toggleMenu(event, row.path)}
                      >
                        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                          <circle cx="12" cy="5" r="1.7" fill="currentColor" />
                          <circle cx="12" cy="12" r="1.7" fill="currentColor" />
                          <circle cx="12" cy="19" r="1.7" fill="currentColor" />
                        </svg>
                      </button>
                    ) : null}
                    {menu !== null && menu.path === row.path ? (
                      <div
                        className="menu-list editor-file-pop"
                        role="menu"
                        aria-label={`操作：${row.path}`}
                        style={menu.style}
                      >
                        {row.dir ? (
                          <button
                            type="button"
                            className="menu-item"
                            role="menuitem"
                            tabIndex={-1}
                            onClick={() => askDeleteFolder(row.path)}
                          >
                            删除文件夹
                          </button>
                        ) : (
                          <>
                            <button
                              type="button"
                              className="menu-item"
                              role="menuitem"
                              tabIndex={-1}
                              onClick={() => beginRename(row.path)}
                            >
                              重命名
                            </button>
                            <button
                              type="button"
                              className="menu-item"
                              role="menuitem"
                              tabIndex={-1}
                              onClick={() => askDelete(row.path)}
                            >
                              删除
                            </button>
                          </>
                        )}
                      </div>
                    ) : null}
                  </>
                )}
              </li>
            ))}
          </ul>
          <p
            style={{ display: (!(rows.length === 0 && query !== '' && create === null)) ? 'none' : undefined }}
            className="editor-empty"
            id="edEmpty"
          >
            没有匹配的文件。
            <button type="button" className="link-button" onClick={() => setQuery('')}>
              清除搜索
            </button>
          </p>
        </aside>
  )
}
