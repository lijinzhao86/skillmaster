/**
 * The draft's files, as paths and as a tree — the parts of the editor that do not touch React.
 *
 * **Why these live outside the page.** The version editor is the longest screen in the app, and what
 * made it long was not its markup: it is a file tree, a text buffer and a save flow that all edit the
 * same files. Whatever can be answered from the file list alone — "what does the tree look like",
 * "is this path free", "which buffers differ from disk" — is a pure question, and keeping it here
 * leaves the page holding only the state these answers are computed from.
 *
 * Paths are posix relative to the skill's root (`notes/today.md`), which is what the server's file
 * API speaks and what the editor's addresses carry.
 */

import { pathIssue } from '../nameRules'

export interface FileNode {
  name: string
  path: string
  dir: boolean
  children: FileNode[]
}

/**
 * The recursive tree, built from the flat relpaths. Folders first, then files, each sorted by name —
 * and every folder starts closed; the caller's expansion map is what a person opened, and the tree
 * itself remembers nothing.
 *
 * `localFolders` are the empty folders this session made: they exist in the draft the reader is
 * building but not yet in the file list, so they are drawn without any files under them.
 */
export function buildFileTree(
  files: ReadonlyArray<{ relpath: string }>,
  localFolders: readonly string[],
  includeLocalFolders = true,
  includeFile: (relpath: string) => boolean = () => true,
): FileNode[] {
  const root: FileNode = { name: '', path: '', dir: true, children: [] }
  const byPath: Record<string, FileNode> = {}
  const ensureDir = (path: string): FileNode => {
    const parts = path.split('/')
    let parent = root
    for (let index = 0; index < parts.length; index += 1) {
      const dirPath = parts.slice(0, index + 1).join('/')
      let node = byPath[dirPath]
      if (node === undefined) {
        node = { name: parts[index] as string, path: dirPath, dir: true, children: [] }
        byPath[dirPath] = node
        parent.children.push(node)
      }
      parent = node
    }
    return parent
  }
  if (includeLocalFolders) {
    for (const folder of localFolders) ensureDir(folder)
  }
  for (const file of files) {
    if (!includeFile(file.relpath)) continue
    const parts = file.relpath.split('/')
    const parent = parts.length > 1 ? ensureDir(parts.slice(0, -1).join('/')) : root
    parent.children.push({
      name: parts[parts.length - 1] as string,
      path: file.relpath,
      dir: false,
      children: [],
    })
  }
  const sort = (nodes: FileNode[]): void => {
    nodes.sort((a, b) => (a.dir === b.dir ? a.name.localeCompare(b.name) : a.dir ? -1 : 1))
    for (const node of nodes) if (node.dir) sort(node.children)
  }
  sort(root.children)
  return root.children
}

/** Every folder above `path`, outermost first — what has to be open for the file to be on screen. */
export function ancestorsOf(path: string): string[] {
  const parts = path.split('/')
  const out: string[] = []
  for (let index = 1; index < parts.length; index += 1) out.push(parts.slice(0, index).join('/'))
  return out
}

/** The buffers that differ from what was last read or written — the page's only definition of dirty. */
export function dirtyPathsOf(
  buffers: Record<string, string>,
  saved: Record<string, string>,
): string[] {
  return Object.keys(buffers).filter((path) => buffers[path] !== saved[path])
}

/** Whether any file sits under this folder — what tells an empty folder from a populated one. */
export function hasFilesUnder(files: ReadonlyArray<{ relpath: string }>, path: string): boolean {
  return files.some((file) => file.relpath.startsWith(`${path}/`))
}

export function folderExists(
  files: ReadonlyArray<{ relpath: string }>,
  localFolders: readonly string[],
  path: string,
): boolean {
  if (localFolders.includes(path)) return true
  if (localFolders.some((key) => key.startsWith(`${path}/`))) return true
  return hasFilesUnder(files, path)
}

export function fileExists(files: ReadonlyArray<{ relpath: string }>, path: string): boolean {
  return files.some((file) => file.relpath === path)
}

/**
 * Whether a typed path may be created, as a sentence or `null`.
 *
 * The server refuses the same shapes with the same reasons; this is the half that arrives before the
 * request, so a typo does not need a round trip to be told.
 */
export function validatePath(path: string): string | null {
  if (path === '') return '路径不能为空。'
  if (path.startsWith('/')) return '路径要从 skill 根目录开始，不能以 / 开头。'
  if (path.endsWith('/')) return '路径不能以 / 结尾。'
  if (path.includes('\\')) return '路径要用 / 分隔。'
  if (path.split('/').some((part) => part === '' || part === '..')) {
    return '路径里不能有空的一段或 ..。'
  }
  // v33: the half the server keeps in `PathCanon` — a name macOS or Windows cannot restore is
  // refused here, before a request, and again there when the page is bypassed.
  return pathIssue(path)
}
