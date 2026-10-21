/**
 * The file tree: folders first, files under them, one creating row at the top when a new file or
 * folder is being named.
 *
 * **Every folder starts closed**, the way a repository explorer does. The map only remembers the
 * folders a person opened (or the ones holding the file being edited); a search ignores it and
 * shows its matches expanded.
 *
 * **SKILL.md has no menu.** Both actions are refusals: it is the body, so it cannot be renamed
 * away or removed. A menu whose every item is disabled is a promise broken twice; not drawing it
 * is the honest shape.
 *
 * **An empty folder can be taken away; a folder with files cannot.** Deleting the latter would
 * mean deleting the files inside it, and those are content with their own rows, their own
 * confirmations and their own unsaved state. The menu here only exists for the case this page
 * itself creates: a folder with nothing under it.
 */

import { useEffect, useRef } from 'react'
import { buildTree, hasFilesUnder } from '../../fixtures/editor'
import type { CreateKind, EditorFile, FolderMap, TreeDir, TreeNode } from '../../fixtures/editor'

export interface EditorTreeProps {
  files: EditorFile[]
  current: string
  dirty: Record<string, boolean>
  query: string
  expanded: FolderMap
  emptyFolders: FolderMap
  creating: { kind: CreateKind } | null
  createPreset: string
  createError: string
  createToken: number
  editing: string | null
  renameValue: string
  renameError: string
  renameToken: number
  onSelect: (path: string) => void
  onToggleDir: (path: string) => void
  onMenu: (kind: CreateKind, path: string, anchor: HTMLButtonElement) => void
  onCreateChange: (value: string) => void
  onCreateCommit: (path: string) => void
  onCreateCancel: () => void
  onRenameChange: (value: string) => void
  onRenameCommit: (path: string) => void
  onRenameCancel: () => void
  onClearSearch: () => void
}

function menuIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="5" r="1.7" fill="currentColor" />
      <circle cx="12" cy="12" r="1.7" fill="currentColor" />
      <circle cx="12" cy="19" r="1.7" fill="currentColor" />
    </svg>
  )
}

function dirIcon() {
  return (
    <svg className="editor-file-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
    </svg>
  )
}

function chevron() {
  return (
    <svg className="editor-file-chevron" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path
        d="M6 4l4 4-4 4"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

function fileMark() {
  return (
    <svg className="editor-file-icon" viewBox="0 0 24 24" aria-hidden="true">
      <use href="#fileMark" />
    </svg>
  )
}

export default function EditorTree({
  files,
  current,
  dirty,
  query,
  expanded,
  emptyFolders,
  creating,
  createPreset,
  createError,
  createToken,
  editing,
  renameValue,
  renameError,
  renameToken,
  onSelect,
  onToggleDir,
  onMenu,
  onCreateChange,
  onCreateCommit,
  onCreateCancel,
  onRenameChange,
  onRenameCommit,
  onRenameCancel,
  onClearSearch,
}: EditorTreeProps) {
  const createRef = useRef<HTMLInputElement>(null)
  const renameRef = useRef<HTMLInputElement>(null)

  const open = (path: string) => query.trim() !== '' || expanded[path] === true

  /* The row arrives with the caret in it; without this the person has to find the field they
     just asked for. */
  useEffect(() => {
    if (!creating) return
    const input = createRef.current
    if (!input) return
    input.focus()
    input.setSelectionRange(input.value.length, input.value.length)
  }, [createToken])

  useEffect(() => {
    if (editing === null) return
    const input = renameRef.current
    if (!input) return
    input.focus()
    input.setSelectionRange(input.value.length, input.value.length)
  }, [renameToken])

  const needle = query.trim().toLowerCase()
  const matched = files.filter(
    (file) => !needle || file.path.toLowerCase().indexOf(needle) >= 0,
  )
  /* A search hides empty folders: they have no file whose path could match, and a folder row
     with nothing under it in a filtered tree is a row that says nothing. */
  const tree = buildTree(matched, needle ? {} : emptyFolders)

  const entries: { node: TreeNode; depth: number }[] = []
  const walk = (node: TreeNode, depth: number) => {
    if (node.kind !== 'dir') return
    node.children.forEach((child) => {
      entries.push({ node: child, depth })
      if (child.kind === 'dir' && open(child.path)) walk(child, depth + 1)
    })
  }
  walk(tree, 0)

  const createRow = () => {
    if (!creating) return null
    const isDir = creating.kind === 'dir'
    return (
      <li className="editor-file is-creating" data-kind={creating.kind}>
        <div className="editor-file-open is-creating" style={{ paddingLeft: '6px' }}>
          <span className="editor-file-chevron is-empty" aria-hidden="true"></span>
          {isDir ? dirIcon() : fileMark()}
          <input
            ref={createRef}
            type="text"
            className="editor-create-input"
            autoComplete="off"
            spellCheck={false}
            value={createPreset}
            placeholder={isDir ? '文件夹名或路径' : '文件名或路径'}
            aria-label={isDir ? '新建文件夹的路径' : '新建文件的路径'}
            onInput={(event) => onCreateChange(event.currentTarget.value)}
            onChange={(event) => onCreateChange(event.currentTarget.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                onCreateCommit(event.currentTarget.value)
              }
              if (event.key === 'Escape') {
                event.preventDefault()
                onCreateCancel()
              }
            }}
          />
        </div>
        <p className="editor-create-hint">按 Enter 创建，按 Esc 取消</p>
        <p className="editor-field-error" role="alert" hidden={createError === ''}>
          {createError}
        </p>
      </li>
    )
  }

  const fileRow = (file: EditorFile, depth: number) => {
    if (editing === file.path) {
      return (
        <li
          className={'editor-file' + (file.path === current ? ' is-current' : '') + ' is-editing'}
          data-path={file.path}
          data-kind="file"
          key={file.path}
        >
          <input
            ref={renameRef}
            className="editor-rename-input"
            type="text"
            value={renameValue}
            aria-label="新路径"
            onInput={(event) => onRenameChange(event.currentTarget.value)}
            onChange={(event) => onRenameChange(event.currentTarget.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                onRenameCommit(event.currentTarget.value)
              }
              if (event.key === 'Escape') {
                event.preventDefault()
                onRenameCancel()
              }
            }}
          />
          <span className="editor-rename-acts">
            <button
              type="button"
              className="link-button"
              onClick={() => onRenameCommit(renameValue)}
            >
              重命名
            </button>
            <button type="button" className="link-button" onClick={onRenameCancel}>取消</button>
          </span>
          <p className="editor-field-error" hidden={renameError === ''}>{renameError}</p>
        </li>
      )
    }

    return (
      <li
        className={'editor-file' + (file.path === current ? ' is-current' : '')}
        data-path={file.path}
        data-kind="file"
        key={file.path}
      >
        <button
          type="button"
          className="editor-file-open"
          aria-label={'打开 ' + file.path}
          style={{ paddingLeft: (6 + depth * 14) + 'px' }}
          onClick={() => onSelect(file.path)}
        >
          <span className="editor-file-chevron is-empty" aria-hidden="true"></span>
          {fileMark()}
          <span className="editor-file-path">{file.path.split('/').pop()}</span>
          {dirty[file.path] && <span className="editor-file-dot" title="未保存的改动"></span>}
        </button>
        {file.path !== 'SKILL.md' && editing !== file.path && (
          <button
            type="button"
            className="editor-file-menu"
            aria-label={'操作：' + file.path}
            title="操作"
            onClick={(event) => {
              event.stopPropagation()
              onMenu('file', file.path, event.currentTarget)
            }}
          >
            {menuIcon()}
          </button>
        )}
      </li>
    )
  }

  const dirRow = (node: TreeDir, depth: number) => {
    const isOpen = open(node.path)
    return (
      <li className="editor-file is-dir" data-path={node.path} data-kind="dir" key={node.path}>
        <button
          type="button"
          className="editor-file-open"
          aria-expanded={isOpen ? 'true' : 'false'}
          aria-label={(isOpen ? '收起 ' : '展开 ') + node.path}
          style={{ paddingLeft: (6 + depth * 14) + 'px' }}
          onClick={() => onToggleDir(node.path)}
        >
          {chevron()}
          {dirIcon()}
          <span className="editor-file-path">{node.name}</span>
        </button>
        {!hasFilesUnder(files, node.path) && (
          <button
            type="button"
            className="editor-file-menu"
            aria-label={'操作：' + node.path}
            title="操作"
            onClick={(event) => {
              event.stopPropagation()
              onMenu('dir', node.path, event.currentTarget)
            }}
          >
            {menuIcon()}
          </button>
        )}
      </li>
    )
  }

  return (
    <>
      <ul className="editor-files" id="edFiles">
        {createRow()}
        {entries.map(({ node, depth }) => (
          node.kind === 'dir' ? dirRow(node, depth) : fileRow(node.file, depth)
        ))}
      </ul>
      <p className="editor-empty" id="edEmpty" hidden={entries.length > 0 || creating !== null}>
        没有匹配的文件。{' '}
        <button type="button" className="link-button" id="edEmptyClear" onClick={onClearSearch}>
          清除搜索
        </button>
      </p>
    </>
  )
}
