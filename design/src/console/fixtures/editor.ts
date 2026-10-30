/**
 * The editor's fixtures, and the pure rules the tree shares.
 *
 * **The demo's fixtures are its own.** The product reads the base version from
 * `/web/skills/{ns}/{name}@<pin>`; here the three text files are copied from the 文件 tab's
 * content, and the binary is added only by the 二进制文件 state so the ordinary tree still
 * matches the rest of the demo (three files).
 *
 * The draft is the same row the versions page shows: an author-given name, a note, a stable
 * eight-character code, and no version number (it has not been published).
 */

export interface EditorFile {
  path: string
  size: string
  text?: string
  binary?: boolean
}

export type FolderMap = Record<string, boolean>

export interface TreeDir {
  path: string
  name: string
  kind: 'dir'
  children: TreeNode[]
}

export interface TreeFile {
  path: string
  name: string
  kind: 'file'
  file: EditorFile
}

export type TreeNode = TreeDir | TreeFile

export type CreateKind = 'file' | 'dir'

export const ED_TEXT: Record<string, string> = {
  'SKILL.md': [
    '# pdf-tools',
    '',
    '把 PDF 拆页、合并与压缩。加载后可用 split 与 merge 两个动作。',
    '',
    '## 用法',
    '',
    '- split — 按页码范围拆成多个文件',
    '- merge — 把多个 PDF 合成一个',
    '- compress — 压到目标体积，先降采样再压流',
    '',
    '## 注意',
    '',
    '扫描件里没有文字层。拆分仍然能用，但按内容切之前要先 OCR。',
    '',
  ].join('\n'),
  'references/split.md': [
    '# split',
    '',
    '按页码范围把一份 PDF 拆成多个文件。页码从 1 开始，两端都算在内。',
    '',
    '## 用法',
    '',
    '- split 1-3 — 取前三页',
    '- split 4- — 从第四页到结尾',
    '- -2 — 只取最后两页',
    '',
    '## 注意',
    '',
    '拆出来的文件名沿用原文件，后面缀上页码范围。空白页不会自动丢掉。',
    '',
  ].join('\n'),
  'scripts/compress.sh': [
    '#!/bin/sh',
    '# 压到目标体积。降采样和压流分两步做，一次到位会把文字层压花。',
    'set -eu',
    '',
    'input="${1:?用法: compress.sh <文件> [目标体积]}"',
    'target="${2:-2m}"',
    '',
    'gs -sDEVICE=pdfwrite \\',
    '   -dPDFSETTINGS=/ebook \\',
    '   -dDownsampleColorImages=true \\',
    '   -dColorImageResolution=150 \\',
    '   -o "${input%.pdf}.compressed.pdf" "$input"',
    '',
    'ls -l "${input%.pdf}.compressed.pdf" | awk \'{ print $5, $9 }\'',
    '',
  ].join('\n'),
}

/** The saved draft, as the versions page knows it. */
export const DRAFT = {
  pin: '7K3M9QX2',
  draftName: 'compress 补文档',
  note: '把 compress 的用法补进正文：参数怎么给、输出放在哪，以及扫描件要先 OCR 的那一段。',
  by: 'yuhan',
}

/** 空白底稿: one minimal SKILL.md, still a normal parse. */
export const BLANK_TEXT =
  '---\nname: pdf-tools\ndescription: 把 PDF 拆页、合并与压缩。\n---\n\n# pdf-tools\n'

/**
 * **The demo's one static fixture for a conflict's other side.** The real page gets it by
 * re-reading the version; here it is a sentence the second writer left behind.
 */
export const OTHER_WRITER = ED_TEXT['SKILL.md'].replace(
  '压缩。加载后可用 split 与 merge 两个动作。',
  '压缩。加载后可用 split、merge 与 compress 三个动作。',
)

/** Names and notes are capped by code point, not UTF-16 unit: an emoji is one character. */
export function clampPoints(value: string, max: number): string {
  return Array.from(value).slice(0, max).join('')
}

export function lineNumbers(text: string): string {
  const count = text.split('\n').length
  const parts: string[] = []
  for (let index = 1; index <= count; index += 1) parts.push(String(index))
  return parts.join('\n')
}

export function sizeLabel(text: string): string {
  const bytes = new Blob([text]).size
  if (bytes < 1024) return bytes + ' B'
  return (bytes / 1024).toFixed(1) + ' KB'
}

/** Every folder a path sits in, outermost first. `a/b/c.md` → `a`, `a/b`. */
export function ancestorsOf(path: string): string[] {
  const parts = path.split('/')
  const folders: string[] = []
  for (let index = 1; index < parts.length; index += 1) {
    folders.push(parts.slice(0, index).join('/'))
  }
  return folders
}

export function hasFilesUnder(files: EditorFile[], path: string): boolean {
  return files.some((file) => file.path.indexOf(path + '/') === 0)
}

export function folderExists(files: EditorFile[], emptyFolders: FolderMap, path: string): boolean {
  if (emptyFolders[path]) return true
  /* It may also be only an ancestor of an empty folder (creating `guides/advanced` means
     `guides` exists too). */
  if (Object.keys(emptyFolders).some((key) => key.indexOf(path + '/') === 0)) return true
  return files.some((file) => file.path.indexOf(path + '/') === 0)
}

/* **跨系统的名字规则（v33）。** 与「该路径已存在文件。」这一套同一个函数、同一个位置：名字在下
   载到 macOS 或 Windows 上还原不了时，在这里就挡住，而不是等别人解压时才坏。服务端有同一套规
   则兜底，句子也写在同一份 PRD 里（v33 §措辞）。 */
const INVALID_LABEL: Record<string, string> = {
  '<': '尖括号',
  '>': '尖括号',
  ':': '冒号',
  '"': '引号',
  '|': '竖线',
  '?': '问号',
  '*': '星号',
}

/* Windows 把这些名字当成设备，带扩展名也一样（`CON.md` 指的仍是控制台）。 */
const RESERVED = ['CON', 'PRN', 'AUX', 'NUL']
  .concat(Array.from({ length: 9 }, (_, index) => 'COM' + (index + 1)))
  .concat(Array.from({ length: 9 }, (_, index) => 'LPT' + (index + 1)))

function nfc(value: string): string {
  return value.normalize('NFC')
}

function byteLength(value: string): number {
  return new TextEncoder().encode(value).length
}

/** 控制字符与 DEL（U+007F）：服务端的 `PathCanon` 两个都拒，这里必须是同一套。 */
function isControl(char: string): boolean {
  const code = char.charCodeAt(0)
  return code < 0x20 || code === 0x7f
}

/** 这一段的第一个跨系统问题，没有就返回 null。 */
function segmentIssue(segment: string): string | null {
  for (const char of segment) {
    if (isControl(char)) return '控制字符不能作为文件名（Windows）。'
    const label = INVALID_LABEL[char]
    if (label !== undefined) return label + '不能作为文件名（Windows）。'
  }
  if (/[ .]$/.test(segment)) return '结尾有一个空格（或点），Windows 会去掉它。'
  const stem = segment.split('.')[0].toUpperCase()
  if (RESERVED.indexOf(stem) >= 0) return stem + ' 是 Windows 保留的设备名。'
  return null
}

export function validatePath(path: string): string | null {
  if (!path) return '路径不能为空。'
  if (path.charAt(0) === '/') return '路径要从 skill 根目录开始，不能以 / 开头。'
  if (path.charAt(path.length - 1) === '/') return '路径不能以 / 结尾。'
  if (path.indexOf('\\') >= 0) return '路径要用 / 分隔。'
  if (path.split('/').some((part) => part === '' || part === '..')) {
    return '路径里不能有空的一段或 ..。'
  }
  for (const segment of path.split('/')) {
    const issue = segmentIssue(segment)
    if (issue) return issue
  }
  if (
    path.split('/').some((part) => byteLength(part) > 255) ||
    byteLength(path) > 1024
  ) {
    return '路径太长（单段超过 255 字节，或整个路径超过 1024 字节）。'
  }
  return null
}

/**
 * **同一个名字的两种写法。** macOS 与 Windows 上 `A.md` 与 `a.md` 是同一个文件，`é` 的两种编码
 * 写法也是——在这里挡住。`ignore` 是重命名时它自己那一行，改名不该跟自己撞。
 */
export function validateNameCollision(
  files: EditorFile[],
  emptyFolders: FolderMap,
  path: string,
  ignore?: string,
): string | null {
  const existing: string[] = Object.keys(emptyFolders)
  files.forEach((file) => {
    existing.push(...ancestorsOf(file.path), file.path)
  })
  const target = nfc(path)
  const lower = target.toLowerCase()
  for (const other of existing) {
    if (other === path || other === ignore) continue
    const normalized = nfc(other)
    if (normalized === target) {
      return '与已有的 ' + other + ' 只有编码写法不同，macOS 会把它们当成同一个文件。'
    }
    if (normalized.toLowerCase() === lower) {
      return '与已有的 ' + other + ' 在 macOS 和 Windows 上会被当成同一个文件。'
    }
  }
  return null
}

export function validateCreatePath(
  files: EditorFile[],
  emptyFolders: FolderMap,
  path: string,
  kind: CreateKind,
): string | null {
  const issue = validatePath(path)
  if (issue) return issue
  if (kind === 'file') {
    if (files.some((file) => file.path === path)) return '该路径已存在文件。'
    if (folderExists(files, emptyFolders, path)) return '该路径已是文件夹。'
    if (files.some((file) => path.indexOf(file.path + '/') === 0)) return '该路径已是文件。'
  } else {
    if (files.some((file) => file.path === path)) return '该路径已是文件。'
    if (files.some((file) => path.indexOf(file.path + '/') === 0)) return '该路径已是文件。'
    if (folderExists(files, emptyFolders, path)) return '该文件夹已存在。'
  }
  return validateNameCollision(files, emptyFolders, path)
}

/**
 * **A recursive tree, built from the flat relpaths.** Folders first, then files, each sorted by
 * name — the explorer order, because a folder is what you open before you can see what is in it.
 *
 * **A folder can exist with nothing in it.** The manifest has files only, so an empty folder is a
 * grouping this page remembers for as long as it is open; `ensureDir` is how both the files' paths
 * and those remembered folders become the same tree.
 */
export function buildTree(files: EditorFile[], folders: FolderMap): TreeDir {
  const root: TreeDir = { path: '', name: '', kind: 'dir', children: [] }
  const byPath: Record<string, TreeDir> = {}

  function ensureDir(path: string): TreeDir {
    const parts = path.split('/')
    let parent = root
    for (let index = 0; index < parts.length; index += 1) {
      const dirPath = parts.slice(0, index + 1).join('/')
      if (!byPath[dirPath]) {
        const node: TreeDir = { path: dirPath, name: parts[index], kind: 'dir', children: [] }
        byPath[dirPath] = node
        parent.children.push(node)
      }
      parent = byPath[dirPath]
    }
    return parent
  }

  Object.keys(folders).forEach((path) => { ensureDir(path) })
  files.forEach((file) => {
    const parts = file.path.split('/')
    const parent = parts.length > 1 ? ensureDir(parts.slice(0, -1).join('/')) : root
    parent.children.push({
      path: file.path,
      name: parts[parts.length - 1],
      kind: 'file',
      file,
    })
  })

  sortTree(root)
  return root
}

function sortTree(node: TreeDir): void {
  node.children.sort((left, right) => {
    if (left.kind !== right.kind) return left.kind === 'dir' ? -1 : 1
    return left.name.localeCompare(right.name)
  })
  node.children.forEach((child) => {
    if (child.kind === 'dir') sortTree(child)
  })
}
