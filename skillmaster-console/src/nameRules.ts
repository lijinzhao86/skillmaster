/**
 * The names macOS and Windows cannot restore (v33), as pure rules.
 *
 * **Copied from `design/src/console/fixtures/editor.ts`**, which is the authority for the rules and
 * for every sentence here (design §编辑版本的树, v33 PRD §措辞). The editor runs these locally so a
 * typo is answered before a request; the server runs the same rules and is the authority when the
 * page is bypassed (ADR 0011). The import sheet reads the server's issue codes through
 * {@link issueSentence}, so the two surfaces say the same sentence about the same problem.
 *
 * No React, no network: this module only answers questions about strings.
 */

/** Windows refuses these anywhere in a name, not just at the start. */
const INVALID_LABEL: Record<string, string> = {
  '<': '尖括号',
  '>': '尖括号',
  ':': '冒号',
  '"': '引号',
  '|': '竖线',
  '?': '问号',
  '*': '星号',
}

/** Windows treats these as devices, with an extension as much as without (`CON.md`). */
const RESERVED = ['CON', 'PRN', 'AUX', 'NUL']
  .concat(Array.from({ length: 9 }, (_, index) => 'COM' + (index + 1)))
  .concat(Array.from({ length: 9 }, (_, index) => 'LPT' + (index + 1)))

const MAX_SEGMENT_BYTES = 255
const MAX_PATH_BYTES = 1024

/** The reasons the server puts in `details[].issue`, and the only ones this module knows. */
export type NameIssue =
  | 'windows_invalid_char'
  | 'windows_reserved_name'
  | 'trailing_space_or_dot'
  | 'path_too_long'
  | 'case_collision'
  | 'unicode_collision'
  | 'invalid_zip_name'

export function isNameIssue(issue: string): issue is NameIssue {
  switch (issue) {
    case 'windows_invalid_char':
    case 'windows_reserved_name':
    case 'trailing_space_or_dot':
    case 'path_too_long':
    case 'case_collision':
    case 'unicode_collision':
    case 'invalid_zip_name':
      return true
    default:
      return false
  }
}

/** Every segment normalized; what the server stores and what comparisons have to use. */
export function normalizePath(path: string): string {
  return path
    .split('/')
    .map((segment) => segment.normalize('NFC'))
    .join('/')
}

function byteLength(value: string): number {
  return new TextEncoder().encode(value).length
}

/**
 * The first cross-system problem with this path, as a sentence — or null.
 *
 * Separate from the editor's structural checks (`路径不能为空。` and friends) because the server
 * keeps those in `SkillPaths` and these in `PathCanon`; the page runs both, in that order.
 */
export function pathIssue(path: string): string | null {
  for (const segment of path.split('/')) {
    const issue = segmentIssue(segment)
    if (issue !== null) return issue
  }
  const segments = path.split('/')
  if (
    segments.some((segment) => byteLength(segment) > MAX_SEGMENT_BYTES) ||
    byteLength(path) > MAX_PATH_BYTES
  ) {
    return '路径太长（单段超过 255 字节，或整个路径超过 1024 字节）。'
  }
  return null
}

/** Control characters and DEL (U+007F): the server's `PathCanon` refuses both, so this must too. */
function isControl(char: string): boolean {
  const code = char.charCodeAt(0)
  return code < 0x20 || code === 0x7f
}

function segmentIssue(segment: string): string | null {
  for (const char of segment) {
    if (isControl(char)) return '控制字符不能作为文件名（Windows）。'
    const label = INVALID_LABEL[char]
    if (label !== undefined) return label + '不能作为文件名（Windows）。'
  }
  if (/[ .]$/.test(segment)) return '结尾有一个空格（或点），Windows 会去掉它。'
  const stem = (segment.split('.')[0] ?? '').toUpperCase()
  if (RESERVED.indexOf(stem) >= 0) return stem + ' 是 Windows 保留的设备名。'
  return null
}

/**
 * Whether this path is the same file as one already in the version, on macOS or Windows.
 *
 * The sentence names the file that is already there, because that is the half the author cannot
 * see while typing. `ignore` is the path being renamed away from — a rename does not collide with
 * itself.
 */
export function collisionIssue(
  path: string,
  existing: readonly string[],
  ignore?: string,
): string | null {
  const target = normalizePath(path)
  const lower = target.toLowerCase()
  for (const other of existing) {
    if (other === path || other === ignore) continue
    const candidate = normalizePath(other)
    if (candidate === target) {
      return '与已有的 ' + other + ' 只有编码写法不同，macOS 会把它们当成同一个文件。'
    }
    if (candidate.toLowerCase() === lower) {
      return '与已有的 ' + other + ' 在 macOS 和 Windows 上会被当成同一个文件。'
    }
  }
  return null
}

/**
 * The shared sentence for one server-reported issue, given the path it is about.
 *
 * The path is needed even for sentences that do not name it: "冒号不能作为文件名" requires knowing
 * which character, and "CON 是 Windows 保留的设备名" requires the stem. The server only sends the
 * code, on purpose (v33 TD §接口).
 */
export function issueSentence(issue: string, path: string): string | null {
  switch (issue) {
    case 'windows_invalid_char':
      return charLabel(path) + '不能作为文件名（Windows）。'
    case 'windows_reserved_name':
      return reservedStem(path) + ' 是 Windows 保留的设备名。'
    case 'trailing_space_or_dot':
      return '结尾有一个空格（或点），Windows 会去掉它。'
    case 'path_too_long':
      return '路径太长（单段超过 255 字节，或整个路径超过 1024 字节）。'
    case 'case_collision':
      return '两个名字在 macOS 和 Windows 上会被当成同一个文件。'
    case 'unicode_collision':
      return '两个名字只有编码写法不同，macOS 会把它们当成同一个文件。'
    case 'invalid_zip_name':
      return '有一个文件名解不出来（压缩包不是 UTF-8 编码）。'
    default:
      return null
  }
}

function charLabel(path: string): string {
  for (const char of path) {
    if (isControl(char)) return '控制字符'
    const label = INVALID_LABEL[char]
    if (label !== undefined) return label
  }
  return '这个字符'
}

function reservedStem(path: string): string {
  for (const segment of path.split('/')) {
    const stem = (segment.split('.')[0] ?? '').toUpperCase()
    if (RESERVED.indexOf(stem) >= 0) return stem
  }
  return '这个文件名'
}
