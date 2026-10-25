import { formatTime } from '../../time'
import type { PublishQueue, QueueSkill, ReleaseType } from '../../api/types'

/**
 * 待上线's data model: a queue row, the arithmetic that says what number it would become, and the
 * one sentence that explains a number that moved.
 *
 * **The rules are ADR 0091's, written once and read by three places** — the row's own preview, the
 * confirmation sheet's list, and the select-all's idea of what may be selected. They used to be
 * duplicated per surface, and the copies drifted: one said a never-published skill would take its
 * type's digit, another said `1.0.0`.
 *
 * **Nothing here imports React and nothing here sorts what the server answered.** The order is the
 * server's (TD §接口): skills by their newest draft, each skill's drafts newest first. Sorting again
 * here would make two orders exist, and the page would eventually show one while the sheet listed
 * the other.
 */

/** One waiting draft, as the page draws it. */
export interface PendingDraft {
  /** `namespace/name@code` — unique, and what a checkbox and the address carry. */
  id: string
  namespace: string
  skill: string
  /** The draft's bare short code: the pin a publish takes, with no prefix (ADR 0063). */
  code: string
  /** The author-facing name; empty on rows from before drafts had one (drawn as 未命名). */
  draft: string
  /**
   * What is live for this skill. Three states, and the middle one is not "never published":
   *
   * - `null` — never published;
   * - `''` — published, but that version carries no number (a pre-v11 row);
   * - anything else — the live version's number.
   */
  live: string | null
  /** Already formatted for the row: the server sends RFC3339, the page shows local time. */
  submitted: string
  release: ReleaseType | null
  /** Why this row did not publish, once a batch has reported on it. */
  failure?: string
}

/** The three names of the three kinds, shared with the version page's publish choices (v5). */
export const RELEASE_LABEL: Record<ReleaseType, string> = {
  major: '主版本',
  minor: '次版本',
  patch: '修订',
}

/** What each kind means in words — 「次版本」 on its own does not say what it does. */
export const RELEASE_MEANING: Record<ReleaseType, string> = {
  major: '旧调用会失效',
  minor: '新增能力',
  patch: '只是修正',
}

/** A draft's name as the page says it, including the historical rows that have none. */
export function draftLabel(row: PendingDraft): string {
  return `「${row.draft === '' ? '未命名' : row.draft}」`
}

/**
 * Whether a number carries a prerelease marker (`1.0.0-rc.1`).
 *
 * Exported because the row chooses between two sentences with it: a prerelease baseline and a
 * version that is not a version both mean "type in a number", but they are not the same problem, and
 * 「基线为预发布版本」 said about `1.0` would be a false statement.
 */
export function isPrereleaseVersion(version: string): boolean {
  // **Strip the build metadata before looking for the hyphen**: the `-` in `1.2.3+build-7` belongs
  // to the metadata and is not a prerelease marker (SemVer 2.0.0 §9). A plain `includes('-')` would
  // refuse a row that could perfectly well be incremented.
  return version.split('+')[0]!.includes('-')
}

/**
 * The three numbers of a usable baseline, or `null` when this string cannot be one.
 *
 * **Stricter than `parseInt` on purpose.** `Number.parseInt('1a')` is `1`, so a live version of
 * `1a.0.0` would have produced `2.0.0` on this page while the server refused it as "not a version
 * at all" (TD §算号) — the row would offer a number that can never exist. Each part uses the
 * server's own numeric shape (`0|[1-9]\d*`), leading zeros included: `01.2.3` is not a version to
 * `SemVer`, and a draft that carries its own number can put one live without passing that check.
 */
function usableCore(live: string): [number, number, number] | null {
  if (live === '' || isPrereleaseVersion(live)) {
    return null
  }
  const parts = live.split('+')[0]!.split('.')
  if (parts.length !== 3 || parts.some((part) => !/^(0|[1-9]\d*)$/.test(part))) {
    return null
  }
  return [Number(parts[0]), Number(parts[1]), Number(parts[2])]
}

/**
 * One step up from a live number, by kind. SemVer 2.0.0: major moves and the rest fall to zero, minor
 * moves and patch falls to zero, patch moves alone. Build metadata is dropped — it takes no part in
 * precedence.
 */
function bump(live: string, type: ReleaseType): string | null {
  const core = usableCore(live)
  if (core === null) {
    return null
  }
  const [major, minor, patch] = core
  if (type === 'major') {
    return `${major + 1}.0.0`
  }
  if (type === 'minor') {
    return `${major}.${minor + 1}.0`
  }
  return `${major}.${minor}.${patch + 1}`
}

/**
 * What number this draft would get if it were published now — `null` when nothing can be computed.
 *
 * `null` has three causes and the row says which (see {@link needsNumber} and {@link needsType}):
 * the live version carries no number, the live version is not a usable baseline, or no kind was
 * chosen.
 */
export function previewOf(row: PendingDraft): string | null {
  if (row.live === null) {
    // Never published: `1.0.0`, and the kind does not take part (ADR 0091).
    return '1.0.0'
  }
  // **An unusable baseline is not fixed by a kind** (ADR 0091 决定 5: the base must be a release).
  // A number has to be given explicitly, which this page has no control for — the row says so and
  // cannot be selected.
  if (needsNumber(row)) {
    return null
  }
  if (row.release === null) {
    return null
  }
  return bump(row.live, row.release)
}

/**
 * **The missing thing is a number, not a kind**: the live version has none, is a prerelease, or is
 * not a version at all.
 *
 * None is fixed by choosing another kind, and the server reports all three as
 * `release_type_needs_explicit_name` before it looks at the draft's own type (TD §算号), so the row
 * offers no kind picker — a menu that cannot help is a control that wastes a click.
 */
export function needsNumber(row: PendingDraft): boolean {
  if (row.live === null) {
    return false
  }
  return usableCore(row.live) === null
}

/** **The missing thing is a kind**: the baseline is usable and no kind was declared. */
export function needsType(row: PendingDraft): boolean {
  return row.live !== null && !needsNumber(row) && row.release === null
}

/**
 * Whether this row can be selected now: it has a number to compute.
 *
 * Permission is not tested here — a skill this caller cannot publish from is not on the page at all
 * (the read filters it). A row that is visible but unpublishable is only a confirmation somebody
 * reaches before being told no.
 */
export function selectable(row: PendingDraft): boolean {
  return previewOf(row) !== null
}

/** The queue's rows, in the order the server gave them. */
export function rowsOf(queue: PublishQueue): PendingDraft[] {
  const rows: PendingDraft[] = []
  for (const skill of queue.skills) {
    for (const draft of skill.drafts) {
      rows.push({
        id: `${skill.namespace}/${skill.name}@${draft.code}`,
        namespace: skill.namespace,
        skill: skill.name,
        code: draft.code,
        draft: draft.name ?? '',
        live: liveOf(skill),
        submitted: formatTime(draft.submitted_at),
        release: draft.release_type,
      })
    }
  }
  return rows
}

/** The three states of a live version, read off the queue's `current`. */
function liveOf(skill: QueueSkill): string | null {
  if (skill.current === null) {
    return null
  }
  return skill.current.version ?? ''
}

/** A skill and the drafts of it that are on the page. */
export interface SkillGroup {
  key: string
  skill: string
  namespace: string
  live: string | null
  drafts: PendingDraft[]
}

/**
 * Group by skill, **keeping the server's order** — which means grouping runs of equal keys rather
 * than bucketing by key, because that is what "a skill's drafts are adjacent" already means.
 *
 * **Grouping is not nesting.** A skill with one draft is still one row on the page; a skill with
 * three gets a heading and three rows under it.
 */
export function group(rows: PendingDraft[]): SkillGroup[] {
  const out: SkillGroup[] = []
  for (const row of rows) {
    const key = `${row.namespace}/${row.skill}`
    const last = out[out.length - 1]
    if (last !== undefined && last.key === key) {
      last.drafts.push(row)
      continue
    }
    out.push({ key, skill: row.skill, namespace: row.namespace, live: row.live, drafts: [row] })
  }
  return out
}

/** One line of the confirmation sheet: **which draft, and what it becomes** (ADR 0090). */
export interface SheetLine {
  name: string
  note: string
  version: string
}

/** The sheet's lines, in the order the rows are drawn. */
export function sheetLines(rows: PendingDraft[]): SheetLine[] {
  return rows.map((row) => ({
    name: `${row.skill} · ${draftLabel(row)}`,
    version: previewOf(row) ?? '',
    note: row.live === null ? '首次上线' : row.release === null ? '' : RELEASE_LABEL[row.release],
  }))
}

/**
 * A draft whose number the server decided differently from this page's preview.
 *
 * **The namespace travels with it** because two namespaces may each hold a skill called `log-triage`,
 * and the sentence after a batch names the *skill*: an entry that carried only the name would let the
 * banner point at the other namespace's sibling.
 */
export interface MovedEntry {
  namespace: string
  skill: string
  predicted: string
  actual: string
}

/**
 * **「预演不是承诺」said in one sentence** (voice-spec §四: 时间、对象、事实 三样都写出来).
 *
 * A row's number can move between the confirmation and the publish, because somebody else published
 * a higher version in between. When that happened, the settlement says which skill, to what, and
 * therefore what this one actually became — 「有人抢先上线了更高的版本」 is the example the spec
 * rejects, because it names nobody and no number.
 */
export function movedSentence(moved: MovedEntry[]): string {
  if (moved.length === 0) {
    return ''
  }
  if (moved.length === 1) {
    const only = moved[0]!
    return `确认之后，${only.skill} 已被上线至 ${only.predicted}，因此这一份的实际版本号为 ${only.actual}。`
  }
  return `确认之后，其中 ${moved.length} 个 skill 的线上版本已被提高，因此这几份的实际版本号大于预演（逐条见对应的行）。`
}

/**
 * **The page's own sentence after a batch**: what moved, and what that does to what is still waiting.
 *
 * The settlement says what this batch became. The page has one more thing to say, because the live
 * version moved: a draft of the same skill that is still on the list now computes from the *new*
 * live version, so its preview changed too — and a person who looked at 1.0.1 a minute ago would
 * otherwise think the row had not caught up. Its number is read after the re-read, because that is
 * when it is true; the design's sentence names it, and naming the old one would predict a number
 * that will never exist.
 *
 * `remaining` is the queue as it stands after the re-read.
 */
export function noteSentence(moved: MovedEntry[], remaining: PendingDraft[]): string {
  const head = movedSentence(moved)
  if (head === '') {
    return ''
  }
  // **Same namespace *and* same skill.** Matching on the name alone would let a batch in `dev`
  // explain a number belonging to `yuhan`'s identically named skill.
  const stillWaiting = remaining.filter(
    (row) =>
      moved.some((entry) => entry.namespace === row.namespace && entry.skill === row.skill) &&
      selectable(row),
  )
  if (stillWaiting.length === 0) {
    return head
  }
  const tail = stillWaiting
    .map((row) => `同组还留着的${draftLabel(row)}现在以 ${previewOf(row)} 上线`)
    .join('；')
  return `${head.replace(/。$/, '')}；${tail}。`
}

/**
 * The settlement's title: **three facts, each said only when it happened**.
 *
 * Folding failures into 「没有结果」 would take a row that already said why it failed and report it as
 * unknown — and folding an interrupted stream's silent rows into 「失败」 would invent a reason. A
 * clause whose count is zero is not a fact, so it is left out.
 */
export function settlementTitle(ok: number, failed: number, unsettled: number): string {
  return [
    ok > 0 ? `${ok} 份已上线` : '',
    failed > 0 ? `${failed} 份失败` : '',
    unsettled > 0 ? `${unsettled} 份没有结果` : '',
  ]
    .filter((part) => part !== '')
    .join('，')
}

/**
 * The settlement's own sentence: what moved (if anything), then where the drafts went.
 *
 * **The title already carries the count** (`3 份已上线，1 份失败`), so the body does not repeat it —
 * the same rule as the empty states in voice-spec §四.
 */
export function settledBody(moved: MovedEntry[], failed: number): string {
  const changed = movedSentence(moved)
  return changed + (failed === 0 ? '以上草稿已从列表移除。' : '失败的草稿未从列表移除，原因见对应行。')
}
