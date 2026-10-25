/**
 * 一个 skill: the fixtures one skill page is drawn from, and the rules that read them.
 *
 * ⚠️ **These are fixtures, like the credentials and the sessions.** There is no server behind the
 * demo, so the tree, the versions, the grants, the requests and the diffs are all written down
 * here. What is not invented is the shape: a draft has no version number, a revoked version is a
 * state rather than a deletion, and the version a row names is a version pin the CLI can parse.
 *
 * The rules live here rather than in the page because more than one component reads them — the
 * head's count, the picker and the version table all answer from `visibleVersions`, and the diff
 * pane and the actions pane both resolve a baseline through the same order.
 */

import type { MenuOption } from '../components/MenuSelect'

export const ROOT_FILE = 'SKILL.md'
export const SKILL_OWNER = 'dev'
export const SKILL_NAME = 'pdf-tools'
/** 「我」on this screen: the owner. Discarding somebody else's draft is the rule that compares it. */
export const ME = SKILL_OWNER
/** The demo's fixed "now", so an action's date is the same in every screenshot. */
export const DEMO_NOW = '2026/10/20 15:40'

export type AtLeast = (key: string) => boolean

export interface TreeEntry {
  kind: 'file' | 'dir'
  name: string
  size?: string
  changed: string
}

export const TREE: Record<string, TreeEntry[]> = {
  '': [
    { kind: 'file', name: 'SKILL.md', size: '1.8 KB', changed: '1.2.3' },
    { kind: 'dir', name: 'references', changed: '1.2.3' },
    { kind: 'dir', name: 'scripts', changed: '1.2.1' },
  ],
  references: [{ kind: 'file', name: 'split.md', size: '3.2 KB', changed: '1.2.3' }],
  scripts: [{ kind: 'file', name: 'compress.sh', size: '0.6 KB', changed: '1.2.1' }],
}

export interface Version {
  name: string | null
  code: string
  live?: boolean
  draft?: boolean
  pin?: string
  draftName?: string
  note?: string
  by: string
  at: string
  submittedAt?: string
  firstPublishedAt?: string | null
  stateAt?: string | null
  state?: 'published' | 'revoked' | 'discarded'
  reason?: string
  restoredAt?: boolean
  supersededAt?: boolean
}

export const BASE_VERSIONS: Version[] = [
  {
    name: '@1.2.3', code: 'Z8N2VK4Q', live: true, draftName: 'split 范围语法',
    note: '把 split 的范围语法写成文档，并补了「按页」与「按分隔符」两个例子。', by: 'dev', at: '2026/10/07 14:32',
    submittedAt: '2026/10/07 10:12', firstPublishedAt: '2026/10/07 14:32', stateAt: '2026/10/07 14:32',
  },
  /* 提交上来、还没上线的这一版。**它没有版本号**——`name` 是 null，调用者按版本号解析不到它；
     但它有**版本名称**（提交时必填的作者面名字）和稳定的 8 位短码身份（产品里短码就是行 id，
     不是内容摘要，所以改名、改说明都不换地址）。它是别人交的：所以「写入」看得见它、
     却改不动它——那条 `write ∧ 提交人是我` 的例外，第一次有了形状。 */
  {
    name: null, draft: true, pin: '7K3M9QX2', code: '7K3M9QX2', draftName: 'compress 补文档',
    note: '把 compress 的用法补进正文：参数怎么给、输出放在哪，以及扫描件要先 OCR 的那一段。', by: 'yuhan', at: '2026/10/16 10:12',
    submittedAt: '2026/10/16 10:12', firstPublishedAt: null, stateAt: null,
  },
  /* 版本只有两种退役后果：仍可读的就是历史版本（@1.2.2），消费面读不到的叫已下架（@1.2.1）。
     「弃用」不再是状态——它只是对历史版本的一句建议，这一版不做。 */
  {
    name: '@1.2.2', code: 'M5C7T1X9', draftName: 'split 拆页', note: '拆分时保留原文件的元数据，合并的默认行为不变。', by: 'dev', at: '2026/09/28 11:05',
    submittedAt: '2026/09/28 09:05', firstPublishedAt: '2026/09/28 11:05', stateAt: '2026/09/28 11:05',
  },
  {
    name: '@1.2.1', code: 'H3R6B8K2', state: 'revoked', draftName: 'compress 收进 skill',
    note: '把 compress 收进这个 skill，原来单独的那份脚本不再需要。', by: 'dev', at: '2026/09/12 20:41',
    reason: '它引用了已经删除的临时脚本', submittedAt: '2026/09/12 18:41', firstPublishedAt: '2026/09/12 20:41', stateAt: '2026/09/20 11:30',
  },
  /* **已丢弃：从未上线，也不会再上线。** 行留在表里（维护及以上看得见），按钉打开的是只读单版页。 */
  {
    name: null, pin: 'Q4W8E2R6', code: 'Q4W8E2R6', state: 'discarded', draftName: '把分页做成参数',
    note: '想给 split 加分页参数，还没写完就换了个方向。', by: 'yuhan', at: '2026/09/05 16:20',
    submittedAt: '2026/09/05 16:20', firstPublishedAt: null, stateAt: '2026/09/06 09:10',
  },
  /* 0.y.z：上线过的，只是还不承诺稳定——它与状态是两件事，所以并排两枚章。 */
  {
    name: '@0.9.0', code: '01J9Z6Q4M8V2K3X7R5T1B9C4D2', draftName: '第一版', note: '第一版：拆页与合并', by: 'dev', at: '2026/08/30 09:20',
    submittedAt: '2026/08/30 08:20', firstPublishedAt: '2026/08/30 09:20', stateAt: '2026/08/30 09:20',
  },
]

/** **演示：版本很多时下拉要滚。** `?versions=24` 往列表里补出这么多历史版本。 */
export function manyVersions(count: number): Version[] {
  const list: Version[] = []
  for (let index = 1; index <= count; index += 1) {
    const day = String(28 - (index % 28)).padStart(2, '0')
    list.push({
      name: '@9.' + index + '.0',
      code: '9V' + String(index).padStart(2, '0') + 'K3Q2',
      draftName: '演示版本 ' + index,
      note: '演示件补出来的历史版本，用来看版本很多时的下拉。',
      by: 'dev',
      at: '2026/07/' + day + ' 10:00',
      submittedAt: '2026/07/' + day + ' 09:00',
      firstPublishedAt: '2026/07/' + day + ' 10:00',
      stateAt: '2026/07/' + day + ' 10:00',
    })
  }
  return list
}

export interface Frontmatter {
  name: string
  description: string
  license: string
  compatibility: string
  metadata: Record<string, string>
  'allowed-tools': string
}

/** **SKILL.md 的 frontmatter，原样。** `/metadata` returns this layer, and its threshold is `invoke`. */
export const FRONTMATTER: Frontmatter = {
  name: 'pdf-tools',
  description: '把 PDF 拆页、合并与压缩。加载后可用 split 与 merge 两个动作。扫描件里没有文字层，按内容切之前要先 OCR。',
  license: 'MIT',
  compatibility: '需要 ghostscript 10 以上',
  metadata: { owner: 'dev', when_to_use: '手里有 PDF，要拆页、合并或压缩的时候' },
  'allowed-tools': 'Bash Read',
}

/** **标签先不显示。** The slot is here for the day the field ships. */
export const TAGS: string[] = []

export const DIGEST = 'sha256:9f2c4ab70d1e5b8a3c6f2d94e07b15ac33e8d2f61b4c9a07e5d8b3f21a6c4e90'
/** 版本事实里的文件摘要。夹具的 TREE 是 3 个文件、5.6 KB。 */
export const FILE_SUMMARY = '3 个文件 · 5.6 KB'

export interface DiffFile {
  path: string
  lines: string[]
  size?: string
  binary?: boolean
  tooBig?: boolean
}

/* 一版与基线那一版的差别，按文件分组。夹具，形状却是真的：每一行以空格开头的是没动的
   行，`-` 是被换掉的，`+` 是换上去的；文件是产品 `SkillDiff.files` 的同一层。
   **两种基线各有一份**：草稿与历史版本对照线上（「把它上线会改什么」），线上版本对照
   上一版（「这一版带来了什么」）。 */
export const DIFF_FILES: DiffFile[] = [
  {
    path: 'SKILL.md',
    lines: [
      '  # pdf-tools',
      '  ',
      '  把 PDF 拆页、合并与压缩。加载后可用 split 与 merge 两个动作。',
      '  ',
      '  ## 用法',
      '  ',
      '- - compress — 压到目标体积',
      '+ - compress — 压到目标体积，先降采样再压流；目标体积写成 2m 这样的形式',
      '+ - compress --keep-text — 保留文字层，体积会大一些',
      '  ',
      '  ## 注意',
    ],
  },
  {
    path: 'references/split.md',
    lines: [
      '  # split',
      '  ',
      '  按页或按分隔符把一份 PDF 拆成多个文件。',
      '  ',
      '  ## 参数',
      '  ',
      '+ --pages 按页拆，范围写成 1-3,7',
      '+ --by    按分隔符拆，默认是换行',
    ],
  },
]

export const DIFF_FILES_LIVE: DiffFile[] = [
  {
    path: 'SKILL.md',
    lines: [
      '  # pdf-tools',
      '  ',
      '  把 PDF 拆页、合并与压缩。加载后可用 split 与 merge 两个动作。',
      '  ',
      '  ## 用法',
      '  ',
      '- - split — 按页拆开或按分隔符拆开',
      '+ - split --pages <范围> — 按页拆开，范围写成 1-3,7 这样的形式',
      '+ - split --by <分隔符> — 按分隔符拆开，默认是换行',
      '+   例：split report.pdf --pages 1-3,7',
      '+   例：split notes.md --by "---"',
      '  ',
      '  ## 注意',
    ],
  },
  {
    path: 'references/split.md',
    lines: [
      '  # split',
      '  ',
      '  按页或按分隔符把一份 PDF 拆成多个文件。',
      '  ',
      '  ## 参数',
      '  ',
      '+ --pages 按页拆，范围写成 1-3,7',
      '+ --by    按分隔符拆，默认是换行',
    ],
  },
]

let bigCache: DiffFile[] | null = null

/** **大改动的夹具。** 124 个文件: one ordinary SKILL.md, one script, 119 reference chapters,
 *  a 3,200-line file, a binary and an over-limit file. It is opened by `?big=1`. */
export function bigDiffFiles(): DiffFile[] {
  if (bigCache) return bigCache
  const chapter = (n: number): string[] => {
    const lines: string[] = []
    for (let i = 1; i <= 24; i += 1) {
      lines.push('  ## 第 ' + i + ' 节')
      lines.push('  第 ' + n + ' 章的第 ' + i + ' 种切法：先读一遍原始页序，再按目标页数重排。')
      if (i % 6 === 0) lines.push('+ 新增：保留原书签与目录层级，导出时按段落回填。')
      if (i % 9 === 0) lines.push('- 旧写法：直接裁掉页边，页码会跟着偏移。')
      if (i % 5 === 0) lines.push('+ 补充：遇到扫描件先 OCR，再判断页序。')
    }
    return lines
  }
  const files: DiffFile[] = [
    {
      path: 'SKILL.md', size: '6.1 KB', lines: [
        '  # pdf-tools',
        '  ',
        '  把 PDF 拆页、合并与压缩。加载后可用 split 与 merge 两个动作。',
        '  ',
        '- ## 用法',
        '+ ## 用法',
        '+ ',
        '+ 所有子命令都接受 --out 指定输出目录；不给就写在原文件旁边。',
        '  ',
        '- - compress — 压到目标体积',
        '+ - compress — 压到目标体积，先降采样再压流；目标体积写成 2m 这样的形式',
        '+ - compress --keep-text — 保留文字层，体积会大一些',
        '  ',
        '  ## 注意',
      ],
    },
    {
      path: 'scripts/batch.sh', size: '2.4 KB', lines: [
        '  #!/usr/bin/env bash',
        '  set -euo pipefail',
        '  ',
        '- for f in "$@"; do compress "$f"; done',
        '+ out="${OUT:-.}"',
        '+ for f in "$@"; do',
        '+   compress "$f" --out "$out"',
        '+ done',
      ],
    },
  ]
  for (let n = 1; n <= 119; n += 1) {
    files.push({
      path: 'references/chapter-' + String(n).padStart(3, '0') + '.md',
      size: '2.1 KB',
      lines: chapter(n),
    })
  }
  const long: string[] = []
  for (let row = 1; row <= 3200; row += 1) {
    const mark = row % 7 === 0 ? '+' : row % 11 === 0 ? '-' : ' '
    long.push(mark + '  第 ' + row + ' 行：这一行属于长文件，用来验证单文件只画前 400 行。')
  }
  files.push({ path: 'references/large.md', size: '84 KB', lines: long })
  files.push({ path: 'assets/archive.bin', size: '1.2 MB', lines: [], binary: true })
  files.push({ path: 'assets/export.csv', size: '1.4 MB', lines: [], tooBig: true })
  bigCache = files
  return files
}

export const BIG_FIRST = 400
export const BIG_STEP = 500
export const BIG_SINGLE_OVER = 100

export const SUBS = ['general', 'collaborators', 'details'] as const
export type Sub = (typeof SUBS)[number]

export interface Rung {
  name: string
  label: string
}

/* One ladder, five rungs a grant can name, each rung including the ones under it.
   **所有者 is not on this list**: it is whoever created the skill, there is one, and it is not
   handed to anybody. */
export const LADDER: Rung[] = [
  { name: 'invoke', label: '调用' },
  { name: 'browse', label: '浏览' },
  { name: 'write', label: '写入' },
  { name: 'maintain', label: '维护' },
  { name: 'admin', label: '管理' },
]

/** **每一档会多出什么、还缺什么，一句话。** */
export const RUNG_NOTES: Record<string, string> = {
  browse: '可以查看文件清单与已上线的版本，也可以在控制台打开；不可以提交版本。',
  write: '可以提交新版本、改自己草稿的名称与说明、丢弃自己的草稿；上线、修改可见性与管理协作者不属于这一档。',
  maintain: '可以提交并上线版本，也可以丢弃任何人提交的草稿；修改可见性与管理协作者需要「管理」。',
  admin: '可以修改可见性与管理协作者；删除与恢复仅限所有者。',
}

/* Which rung a public skill hands to everybody. Not being in here is what 私有 means. */
export const VISIBILITIES = ['private', 'public-invoke', 'public-browse'] as const
export type Visibility = (typeof VISIBILITIES)[number]
export const PUBLIC_RUNG: Record<string, string> = {
  'public-invoke': 'invoke',
  'public-browse': 'browse',
}

/** **最长的那一个名字放在这里，是给人看边界用的。** 用户名的上限是 30 个字符。 */
export const LONG_HANDLE = 'john-christopher-montgomery-xy'

export interface Grant {
  who: string
  role: string
  type?: string
}

/* **十三个人，正好两页。** 一页十行，所以名单必须是「十行以上」才看得见分页。 */
export const GRANTS: Grant[] = [
  { who: 'yuhan', role: 'browse' },
  { who: 'hexu', role: 'write' },
  { who: LONG_HANDLE, role: 'write' },
  { who: 'zhao', role: 'invoke' },
  { who: 'qian', role: 'browse' },
  { who: 'sun', role: 'write' },
  { who: 'li', role: 'maintain' },
  { who: 'zhou', role: 'browse' },
  { who: 'wu', role: 'invoke' },
  { who: 'zheng', role: 'write' },
  { who: 'wang', role: 'browse' },
  { who: 'chen', role: 'maintain' },
  { who: 'yang', role: 'invoke' },
]

/** **申请不是授权。** 它带着想要的那一档，在批之前谁也不多出什么权力。 */
export const REQUESTS: Array<Grant & { who: string }> = [{ who: 'lin', role: 'write' }]

export const MEMBER_PAGE_SIZE = 10

/* ------------------------------------------------------------------ the rules */

/** Where a rung sits on the ladder. 私有 has no public rung and an unknown role has none either. */
export function rungIndex(role: string | null): number {
  return LADDER.findIndex((rung) => rung.name === role)
}

export function rungLabel(role: string): string {
  return LADDER.find((rung) => rung.name === role)?.label ?? role
}

/** The rungs a public skill gives away, named. */
export function rungsUpTo(role: string | null): string {
  return LADDER
    .filter((rung) => rungIndex(rung.name) <= rungIndex(role))
    .map((rung) => '「' + rung.label + '」')
    .join('、')
}

/** Which rungs may be handed out: the ones above what a public skill already gives everybody. */
export function rungsAbove(publicRung: string | null): Rung[] {
  return LADDER.filter((rung) => rungIndex(rung.name) > rungIndex(publicRung))
}

/** The same list for one row, plus that row's own rung when it falls below the public one. */
export function rungChoices(role: string, publicRung: string | null): Rung[] {
  if (rungIndex(role) < 0 || rungIndex(role) > rungIndex(publicRung)) return rungsAbove(publicRung)
  return [LADDER[rungIndex(role)]].concat(rungsAbove(publicRung))
}

export function liveVersion(list: Version[]): Version | null {
  return list.find((version) => version.live) ?? null
}

/* **每一档看得见哪些行。** 草稿要 `write`；已下架的版本只给 `maintain` 及以上看；
   已上线与历史版本，能打开这一栏的人都看得到。 */
export function visibleVersions(list: Version[], atLeast: AtLeast): Version[] {
  return list.filter((version) => {
    if (version.draft && !atLeast('write')) return false
    if (version.state === 'discarded' && !atLeast('maintain')) return false
    if (version.state === 'revoked' && !atLeast('maintain')) return false
    return true
  })
}

export function versionByPin(list: Version[], pin: string | null, atLeast: AtLeast): Version | null {
  if (!pin) return null
  const wanted = pin.charAt(0) === '@' ? pin : '@' + pin
  return visibleVersions(list, atLeast).find(
    (version) => (version.name !== null && version.name === wanted) || version.pin === pin,
  ) ?? null
}

/** **文件栏现在看的是哪一份快照。** 地址钉住一版且这一档读得到，就是它；否则线上；
 *  只有草稿的 skill，`write` 及以上落到最新一条草稿。 */
export function fileSnapshot(list: Version[], atLeast: AtLeast, params: URLSearchParams): Version | null {
  /* `version=` 在版本页上是「打开哪一版」的钉，不是「文件栏看哪一版」；只有文件栏才把它当快照。 */
  const askTab = params.get('tab')
  const onFilesTab = askTab === 'files' ||
    (!askTab && (params.get('path') !== null || params.get('file') !== null))
  const pinnedName = onFilesTab ? params.get('version') : null
  let pinned: Version | null = null
  const readable = visibleVersions(list, atLeast).filter((version) => version.state !== 'discarded')
  if (pinnedName) {
    for (const version of list) {
      if ((version.name !== null && version.name === '@' + pinnedName) || version.pin === pinnedName) {
        pinned = version
      }
    }
  }
  if (pinned && readable.includes(pinned)) return pinned
  const live = liveVersion(list)
  if (live) return live
  return readable
    .filter((version) => version.state !== 'revoked')
    .sort((a, b) => b.at.localeCompare(a.at))[0] ?? null
}

/** 上一版 = 上线顺序里排在这一版前面的那一版。 */
export function previousPublished(list: Version[], version: Version): Version | null {
  const at = version.firstPublishedAt
  if (!at) return null
  return list
    .filter((other) => !other.draft && !other.live && !!other.firstPublishedAt && other.firstPublishedAt < at)
    .sort((a, b) => (b.firstPublishedAt ?? '').localeCompare(a.firstPublishedAt ?? ''))[0] ?? null
}

/** 可对比的版本：这一版之外、这个档位看得见的那些。 */
export function comparableVersions(list: Version[], target: Version, atLeast: AtLeast): Version[] {
  return list.filter((version) => {
    if (version === target) return false
    if (version.draft && !atLeast('write')) return false
    if (version.state === 'revoked' && !atLeast('maintain')) return false
    if (version.state === 'discarded' && !atLeast('maintain')) return false
    return true
  })
}

/** **默认与哪一版比：上一版。** 没有上一版时退回当前线上。 */
export function defaultBaseline(list: Version[], target: Version, atLeast: AtLeast): Version | null {
  const visible = comparableVersions(list, target, atLeast)
  const previous = previousPublished(list, target)
  if (previous && visible.includes(previous)) return previous
  const live = liveVersion(list)
  return live && live !== target && visible.includes(live) ? live : null
}

/** 地址里的 `from` 可以钉任意一版；它不认得、或指回自己时，退回默认的上一版。 */
export function diffBaseline(
  list: Version[],
  target: Version,
  fromPin: string | null,
  atLeast: AtLeast,
): Version | null {
  if (!atLeast('browse')) return null
  const chosen = versionByPin(list, fromPin, atLeast)
  if (chosen && chosen !== target) return chosen
  return defaultBaseline(list, target, atLeast)
}

/** 回滚的候选：历史版本。已下架与已丢弃都不是能拿回来当线上的状态；草稿还没有版本号。 */
export function rollbackCandidates(list: Version[]): Version[] {
  return list
    .filter((version) => !version.draft && !version.live &&
      (!version.state || version.state === 'published'))
    .sort((a, b) => b.at.localeCompare(a.at))
}

export function canEditDraft(version: Version, atLeast: AtLeast): boolean {
  return atLeast('maintain') || (atLeast('write') && version.by === ME)
}

/** 页头读第一行，所以线上那一版排最前；草稿次之，其余按时间倒序。 */
export function sortVersions(list: Version[]): Version[] {
  return [...list].sort((a, b) => {
    if (a.live !== b.live) return a.live ? -1 : 1
    if (!!a.draft !== !!b.draft) return a.draft ? -1 : 1
    return b.at.localeCompare(a.at)
  })
}

export function stateText(version: Version): string {
  if (version.live) return '线上'
  if (version.draft) return '草稿'
  if (version.state === 'revoked') return '已下架'
  if (version.state === 'discarded') return '已丢弃'
  return '历史版本'
}

/** The state key `stateText` and the filters speak: the address and the chips read the same fact. */
export function versionStateKey(version: Version): string {
  if (version.live) return 'live'
  if (version.draft) return 'draft'
  if (version.state === 'revoked') return 'revoked'
  if (version.state === 'discarded') return 'discarded'
  return 'published'
}

export function stateChipTone(version: Version): string {
  if (version.live) return ' is-live'
  if (version.draft) return ' is-draft'
  if (version.state === 'revoked') return ' is-revoked'
  return ' is-plain'
}

export interface VersionTag {
  tag: string
  tagTone?: string
}

/** **版本的名字在前，版本号次之，状态当一个 tag 跟在后面。** */
export function versionTagOf(version: Version): VersionTag {
  if (version.draft) return { tag: '草稿', tagTone: 'draft' }
  if (version.live) return { tag: '线上', tagTone: 'live' }
  if (version.state === 'revoked') return { tag: '已下架', tagTone: 'revoked' }
  if (version.state === 'discarded') return { tag: '已丢弃' }
  return { tag: '历史版本' }
}

/** **名称是这一版的名字；版本号是上线时分配的地址。** 调用者看到的是版本号。 */
export function versionTitleOf(version: Version, browse: boolean): string {
  if (!browse) return version.name ?? version.pin ?? version.code
  return version.draftName || version.name || version.pin || ''
}

/** 名称与版本号同时出现时用这一种写法：名字在前，上线后才有号。 */
export function versionRefOf(version: Version, browse: boolean): string {
  return version.draftName && version.name
    ? version.draftName + '（' + version.name + '）'
    : versionTitleOf(version, browse)
}

/** The value a version is addressed by in a picker: the version number, or its pin for a draft. */
export function versionOptionValue(version: Version): string {
  return version.name || version.pin || version.code
}

export function versionMenuOption(version: Version, browse: boolean, disabled: boolean): MenuOption {
  const tone = versionTagOf(version)
  const option: MenuOption = {
    value: versionOptionValue(version),
    label: versionTitleOf(version, browse),
    tag: tone.tag,
    tagTone: tone.tagTone,
    disabled,
  }
  /* **上线之后，名称与版本号一起出现在选项里。** 名称在前，版本号以等宽小字跟在后面。 */
  if (!version.draft && version.draftName && version.name) option.number = version.name
  return option
}

export function stateChangeText(version: Version): string {
  if (version.live) return '上线于'
  if (version.state === 'revoked') return '下架于'
  if (version.state === 'discarded') return '丢弃于'
  if (version.restoredAt) return '重新上架于'
  if (version.supersededAt) return '成为历史版本'
  return '上线于'
}

export function diffStatsOf(file: DiffFile): { added: number; removed: number } {
  let added = 0
  let removed = 0
  file.lines.forEach((line) => {
    if (line.charAt(0) === '+') added += 1
    if (line.charAt(0) === '-') removed += 1
  })
  return { added, removed }
}

export interface ChangeSummary {
  files: DiffFile[]
  added: number
  removed: number
}

/** **大改动：正文按需，行数不按需。** 每个文件的 +/− 跟文件清单一起到。 */
export function changeSummary(target: Version, big: boolean): ChangeSummary {
  if (big) {
    const files = bigDiffFiles()
    let added = 0
    let removed = 0
    files.forEach((file) => {
      const stats = diffStatsOf(file)
      added += stats.added
      removed += stats.removed
    })
    return { files, added, removed }
  }
  const files = target.live ? DIFF_FILES_LIVE : DIFF_FILES
  let added = 0
  let removed = 0
  files.forEach((file) => {
    const stats = diffStatsOf(file)
    added += stats.added
    removed += stats.removed
  })
  return { files, added, removed }
}

export function dirOf(file: string): string {
  const cut = file.lastIndexOf('/')
  return cut < 0 ? '' : file.slice(0, cut)
}

/** Each segment escaped, the separators kept: `file=references%2Fsplit.md` is a path nobody recognises. */
export function encodePath(path: string): string {
  return path.split('/').map(encodeURIComponent).join('/')
}

/** The pin a version is addressed by: the version number without `@`, or the row id of a draft. */
export function versionPinOf(version: Version): string {
  return version.name ? version.name.slice(1) : (version.pin ?? version.code)
}

export function versionDetailAddress(version: Version, params: URLSearchParams): string {
  const query = new URLSearchParams()
  query.set('page', 'skill')
  query.set('tab', 'versions')
  query.set('version', versionPinOf(version))
  const as = params.get('as')
  if (as) query.set('as', as)
  /* **列表上的筛选与搜索跟着这一行走**，从这一版回来时还停在原来的列表上。 */
  ;['filter', 'q'].forEach((name) => {
    const value = params.get(name)
    if (value) query.set(name, value)
  })
  return '?' + query.toString()
}

/** The address 版本 goes back to: the list with its filter and search still on it. */
export function backAddress(params: URLSearchParams): string {
  const query = new URLSearchParams()
  query.set('page', 'skill')
  query.set('tab', 'versions')
  const as = params.get('as')
  if (as) query.set('as', as)
  ;['filter', 'q'].forEach((name) => {
    const value = params.get(name)
    if (value) query.set(name, value)
  })
  return '?' + query.toString()
}

export function versionSubAddress(version: Version, sub: string, params: URLSearchParams): string {
  const query = new URLSearchParams(versionDetailAddress(version, params).slice(1))
  query.set('sub', sub)
  return '?' + query.toString()
}

export function versionFilesAddress(version: Version, params: URLSearchParams): string {
  const query = new URLSearchParams()
  query.set('page', 'skill')
  query.set('tab', 'files')
  query.set('version', versionPinOf(version))
  const as = params.get('as')
  if (as) query.set('as', as)
  return '?' + query.toString()
}

export function editorAddress(version: Version, params: URLSearchParams): string {
  const query = new URLSearchParams()
  query.set('page', 'editor')
  query.set('state', 'draft')
  query.set('version', version.pin || version.code)
  const as = params.get('as')
  if (as) query.set('as', as)
  return '?' + query.toString()
}

/** 名单、搜索与分页都在地址里，和「我的 skill」那一页同一条规矩。 */
export function membersAddress(
  params: URLSearchParams,
  query: string,
  page: number,
): string {
  const copy = new URLSearchParams(params)
  copy.set('page', 'skill')
  copy.set('tab', 'settings')
  copy.set('sub', 'collaborators')
  if (query === '') copy.delete('q')
  else copy.set('q', query)
  if (page === 1) copy.delete('p')
  else copy.set('p', String(page))
  return '?' + copy.toString()
}

/** 大改动里「看哪一个文件」的地址。 */
export function bigHref(
  params: URLSearchParams,
  index: number | null,
  opts?: { list?: boolean; unlist?: boolean },
): string {
  const query = new URLSearchParams(params)
  if (index === null) query.delete('file')
  else query.set('file', String(index))
  if (opts?.list) query.set('list', '1')
  if (opts?.unlist) query.delete('list')
  return '?' + query.toString()
}
