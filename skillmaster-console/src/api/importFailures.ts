import { isNameIssue, issueSentence } from '../nameRules'
import { sizeSentence, textSentence, failureMessage } from './errors'
import type { ErrorDetail } from './types'

/**
 * The detail-level sentences for v9's two dialogs (design §导入与下载).
 *
 * Separate from {@link CODE_MESSAGES} because an import failure is `invalid_upload` for nearly
 * everything, and the envelope's generic sentence must not win over the reason in its details.
 * Only the reasons the design supplies wording for are here; anything else falls back to the
 * server's own sentence rather than inventing one (Q7 in the version's technical design).
 */
const TRANSFER_ISSUES: Record<string, string> = {
  // The design's own import-error screen (design §导入与下载, and the demo's `error` state): the
  // one reason it draws as a fixed picture instead of leaving to the server's sentence.
  missing_skill_md: '压缩包里没有 SKILL.md。要把它放在压缩包根部。',
  reserved_path:
    '压缩包里有 .skillmaster/metadata.json；这个路径留给平台元数据，不能作为 skill 的内容。',
  metadata_unknown_field: '.skillmaster/metadata.json 有无法识别的字段：{field}。',
  metadata_schema_unsupported:
    '.skillmaster/metadata.json 的 schema_version 不认识；这个包可能是更新的版本写的。',
  metadata_field_invalid: '.skillmaster/metadata.json 的 {field} 类型不对。',
  // 路径不规范, in the design's one sentence (design §导入与下载). The ZipReader reports which of the
  // three it found in the issue code; the sentence names all three, so each maps to the same text.
  absolute_path: '压缩包里的路径不合法：不能有绝对路径、`..` 或空段。',
  path_traversal: '压缩包里的路径不合法：不能有绝对路径、`..` 或空段。',
  empty_path_segment: '压缩包里的路径不合法：不能有绝对路径、`..` 或空段。',
  // The create-skill form's five refusals (design §新建 skill). They arrive as issues too — but
  // only that form has fields named 名字 and 描述, which is why they are marked below.
  name_too_long: '名字不能超过 64 个字符。',
  name_not_usable_as_a_directory: '名字里不能有空格或路径分隔符。',
  name_contains_version_separator: '名字里不能有 @。',
  name_contains_unaddressable_char: '名字里不能有 % 或 ;。',
  required_field_missing: '名字和描述都要填。',
}

/**
 * The issues whose sentence belongs to the create form only.
 *
 * `required_field_missing` is emitted by `SkillDocument` for an upload too (a package whose
 * `SKILL.md` omits `name` or `description`), but the import sheet has no such fields to point at —
 * 「名字和描述都要填。」 would tell somebody to fill in boxes that are not on screen. There the
 * server's own sentence is shown instead, until the design supplies one (Q7).
 */
const CREATE_ONLY_ISSUES = new Set(['required_field_missing'])

export interface ImportFailure {
  message: string
  /**
   * Further lines of the same refusal, drawn under {@link message} one per line (v33). Only the
   * name refusal has them: it points at up to three files and then says what to do.
   */
  lines?: string[]
  /** Where the one sensible next step is, when the design gives one. */
  action?: { label: string; href: string }
}

/**
 * The packages refused for their file names (v33).
 *
 * At most three files are named, and the rest is a count: the sheet has no way to rename anything,
 * so its job is to make the problem visible and hand the author back to the archive — a list long
 * enough to scroll would not help them fix it any faster.
 */
function nameIssues(details: ErrorDetail[]): ImportFailure | null {
  if (!details.some((detail) => isNameIssue(detail.issue))) {
    return null
  }
  const problems: { paths: string[]; issue: string }[] = []
  for (let index = 0; index < details.length; index += 1) {
    const detail = details[index]
    if (detail === undefined || !isNameIssue(detail.issue)) continue
    const collision = detail.issue === 'case_collision' || detail.issue === 'unicode_collision'
    const next = details[index + 1]
    if (collision && next !== undefined && next.issue === detail.issue) {
      // The server sends a collision as two adjacent details, one per path (v33 TD §接口).
      problems.push({ paths: [detail.field, next.field], issue: detail.issue })
      index += 1
      continue
    }
    problems.push({ paths: [detail.field], issue: detail.issue })
  }

  const reason = (problem: { paths: string[]; issue: string }): string =>
    issueSentence(problem.issue, problem.paths[0] ?? '') ?? ''
  const label = (problem: { paths: string[] }): string =>
    problem.paths.filter((path) => path !== '').join(' 与 ')

  if (problems.length === 1) {
    const problem = problems[0]
    if (problem === undefined) return null
    if (label(problem) === '') {
      // `invalid_zip_name`: the original spelling was lost in decoding, so there is no path to say.
      return { message: reason(problem), lines: ['改好压缩包再选一次。'] }
    }
    return {
      message: `压缩包里的 ${label(problem)} 不能用在所有系统上：${reason(problem)}`,
      lines: ['改好压缩包再选一次。'],
    }
  }

  // **The header counts files, not lines**: a collision is one line naming two files, and
  // "4 个文件名" over four names in three lines is the sentence this rule exists to get right.
  const namedFiles = (list: { paths: string[] }[]): number =>
    list.reduce((sum, problem) => sum + Math.max(1, problem.paths.filter((path) => path !== '').length), 0)
  const shown = problems.slice(0, 3)
  if (shown.length === 0) return null
  const lines = shown.map((problem) => {
    const name = label(problem)
    const sentence = reason(problem)
    // `invalid_zip_name` has no path to show — the original spelling was lost in decoding — so that
    // line is the sentence alone, the same rule the CLI follows.
    return name === '' ? sentence : `${name}：${sentence}`
  })
  const hidden = namedFiles(problems) - namedFiles(shown)
  if (hidden > 0) {
    lines.push(`还有 ${hidden} 个。`)
  }
  lines.push('改好压缩包再选一次。')
  return {
    message: `压缩包里有 ${namedFiles(problems)} 个文件名不能用在所有系统上，没有导入。`,
    lines,
  }
}

/**
 * What the import or create dialog says for one failed submission.
 *
 * **Whose sentence it is depends on which sheet is open.** The design gives the network sentence to
 * the two import entries, where a file really was being sent; the create sheet sends no file, and
 * 「文件没有导入」 would describe something that never happened. `mode` is that question, and it is
 * the only place the two sheets' error vocabulary differs.
 *
 * `name_mismatch` is assembled from both names the server sends: the point of the sentence is that
 * the person can see which side to change, so quoting only one would be a riddle (PRD 做 #4).
 */
export function importFailure(
  result: {
    code: string
    message: string
    details: ErrorDetail[]
  },
  mode: 'import' | 'create',
): ImportFailure {
  if (result.code === 'network_error' || result.code === 'timeout') {
    return {
      message:
        mode === 'create'
          ? failureMessage(result)
          : '导入失败：服务器没有响应，文件没有导入。重试一次。',
    }
  }
  if (result.code === 'skill_deleted') {
    return {
      message: '这个名字属于一个已删除的 skill。',
      action: { label: '去恢复', href: '/skills?show=deleted' },
    }
  }
  const mismatch = result.details.find((detail) => detail.issue === 'name_mismatch')
  if (mismatch !== undefined) {
    const address = issueValue(result.details, 'address_name') ?? ''
    const content = issueValue(result.details, 'content_name') ?? ''
    return {
      message: `地址是 ${address}，SKILL.md 里写的是 ${content}。把 SKILL.md 的 name 改成 ${address}。`,
    }
  }

  // v33: a package refused for its names gets its own answer — which files, and what to do —
  // before the single-sentence reasons below get a chance to say only the first of them.
  const names = nameIssues(result.details)
  if (names !== null) {
    return names
  }

  // v39's size refusals, in the sheet's own words. Without this branch the envelope's English
  // message reaches the person: the generic `invalid_upload` line below is about size and
  // frontmatter in a sentence no screen has designed (voice-spec §上传与大小的四句话).
  for (const detail of result.details) {
    const sentence = sizeSentence(detail)
    if (sentence !== null) {
      return { message: sentence }
    }
  }

  for (const detail of result.details) {
    if (mode === 'import' && CREATE_ONLY_ISSUES.has(detail.issue)) {
      continue
    }
    const template = TRANSFER_ISSUES[detail.issue]
    if (template !== undefined) {
      const field = issueValue(result.details, 'metadata_field') ?? ''
      return { message: template.replace('{field}', field) }
    }
  }

  // **The size refusal arrives as a detail, not as the envelope's code**: the multipart limit is
  // answered 413 `invalid_upload` with `file=body_too_large` (ApiExceptionHandler), and testing
  // `result.code` alone meant the designed sentence never appeared (T15).
  if (result.details.some((detail) => detail.issue === 'body_too_large')) {
    return { message: '压缩包太大，服务器没有接收。压小一点再试。' }
  }
  // **A reason with no sentence yet is shown as the server said it** (Q7). The generic
  // `invalid_upload` line below talks about size and frontmatter, which is simply not what a
  // top-level folder mismatch or a duplicate path is about — a sentence that is wrong is worse than
  // one that is untranslated, and the design addendum will replace this branch's output.
  if (result.code === 'invalid_upload') {
    // ADR 0181's text refusal is a detail, not a code: without this an over-limit description
    // reaches the import sheet as the server's English sentence.
    for (const detail of result.details) {
      const text = textSentence(detail)
      if (text !== null) return { message: text }
    }
    return { message: result.message }
  }
  return { message: failureMessage(result) }
}

/**
 * A detail's value, under the API's "value in the issue, field names which value" rule — the same
 * convention `Detail("address_name", "pdf-tools")` uses on the wire.
 */
function issueValue(details: ErrorDetail[], field: string): string | undefined {
  return details.find((detail) => detail.field === field)?.issue
}
