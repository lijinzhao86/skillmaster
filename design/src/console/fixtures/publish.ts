/**
 * 待上线：每一份还在等的草稿，以及「按版本类型算出什么号」的那条规则。
 *
 * **只有我够得着上线的 skill 在这里**（`maintain` 以上）。别人共享给我、而我只到 `invoke` /
 * `browse` / `write` 的 skill，它的草稿不进来——看得见却发不出去的行，只会让人在确认单前才发现。
 *
 * **一行是一份草稿，不是一个 skill。** 这一页的主语是"等着发的草稿"——所以一个 skill 攒了三份草稿
 * 就是三行，不是三个候选挤在一行里让人选。这也是它为什么不能塞进「我的 skill」：那一页一行是一个
 * skill，两种主语混在一屏，tab 就会一半按 skill、一半按版本。
 *
 * ⚠️ **行是夹具，规则不是。** 算号那几行照 ADR 0091 写死在产品里——显式号优先、从未上线过一律
 * `1.0.0`、基线带预发布标记时不给算、撞号不顺延。页面读这里的结果，不自己再推一遍。
 */

export type ReleaseType = 'major' | 'minor' | 'patch'

/** 三个档的名字。与「上线」那一屏的三颗单选是同一组词（v5 起就在产品里）。 */
export const RELEASE_LABEL: Record<ReleaseType, string> = {
  major: '主版本',
  minor: '次版本',
  patch: '修订',
}

/** 每一档在人话里是什么意思——界面上跟着名字一起给，光看「次版本」三个字判断不了。 */
export const RELEASE_MEANING: Record<ReleaseType, string> = {
  major: '旧调用会失效',
  minor: '新增能力',
  patch: '只是修正',
}

export interface PendingDraft {
  /**
   * 这一行在演示件里的选中 id。**线上的 pin 是裸短码**（8 位短码，或 v6 行的裸 ULID）：
   * 批量请求的 `version` 原样传它，不加 `draft:` 前缀（ADR 0063；`SkillAddress` 也不接受
   * `draft:<短码>` 这种拼法）。选中的是这一份，不是这个 skill。
   */
  id: string
  namespace: string
  skill: string
  /** 这份草稿的标签名——人认它的方式，也是上线后在作者面留下的历史。 */
  draft: string
  /**
   * 这个 skill 当前线上的版本。**三态**：
   * - `null` —— 从未上线；
   * - `''` —— **上线过，但那一版没有版本号**（v11 之前发布的行；今天的发布路径一定会分配号，
   *   所以它是防御性的，但 td 要求页面说得出来，不能当成「从未上线」——那是另一件事）；
   * - 其余 —— 线上版本号。
   */
  live: string | null
  submitted: string
  /** 这一份草稿的版本类型；`null` = 没选，上线前必须选。 */
  release: ReleaseType | null
  /** 结果那一态：这一行为什么没发出去。 */
  failure?: string
}

/** 有没有预发布标记（`1.0.0-rc.1`）。基线是它的时候不给算号。 */
export function hasPrerelease(version: string): boolean {
  /* **先摘掉构建元数据再找连字符**：`1.2.3+build-7` 里的那个 `-` 属于元数据，不是预发布标记
     （SemVer 2.0.0 §9）。直接 `includes('-')` 会把一条本可以递增的行判成"算不出来"。 */
  return version.split('+')[0].includes('-')
}

/**
 * 可用的基线那三个数，或 `null`——**比 `parseInt` 严**：`parseInt('1a')` 是 `1`，于是线上的
 * `1a.0.0` 会被这里算成 `2.0.0`，而服务端根本不认它是一个版本（TD §算号 的「不是合法 semver」）。
 * 界面就会预告一个永远不会存在的号。每一位用服务端自己的数字形状（`0|[1-9]\d*`），前导零也算：
 * `01.2.3` 不是版本，而"草稿自带号"那一类可以不经校验地把它写上线。
 */
function usableCore(live: string): [number, number, number] | null {
  if (live === '' || hasPrerelease(live)) return null
  const parts = live.split('+')[0].split('.')
  if (parts.length !== 3 || parts.some((part) => !/^(0|[1-9]\d*)$/.test(part))) return null
  return [Number(parts[0]), Number(parts[1]), Number(parts[2])]
}

/**
 * 从线上版本按类型往上走一格。SemVer 2.0.0：主版本进一位、其余归零；次版本进一位置修订归零；
 * 修订只进一位。构建元数据（`+sha`）不算预发布，递增时丢掉它——它在优先级里本来就不参与。
 */
export function bump(live: string, type: ReleaseType): string | null {
  const core = usableCore(live)
  if (core === null) return null
  const [major, minor, patch] = core
  if (type === 'major') return `${major + 1}.0.0`
  if (type === 'minor') return `${major}.${minor + 1}.0`
  return `${major}.${minor}.${patch + 1}`
}

/**
 * 这一份草稿上线时会拿到什么号。
 *
 * `null` 有三种来源，界面上要**分开说**（`needsNumber` / `needsType` 是那两个判据）：
 * 线上那一版**没有号**（历史行）／基线是**预发布**（ADR 0091 决定 5）——这两种要人直接给一个号；
 * 以及**没选类型**——那一种选一档就完事。
 */
export function previewOf(draft: PendingDraft): string | null {
  if (draft.live === null) return '1.0.0'
  /* **没有可用的基线，就没有号可推**（ADR 0091 决定 5：基线必须是正式版）。这一份要人直接给号。 */
  if (needsNumber(draft)) return null
  if (draft.release === null) return null
  return bump(draft.live, draft.release)
}

/**
 * **这一份缺的是「号」，不是「类型」**：线上那一版没有版本号、不是合法 semver，或基线是预发布。
 *
 * 这几种处境下换一档类型也算不出号——服务端也会先报 `release_type_needs_explicit_name`（TD 的算号
 * 流程把它们排在「草稿没有 `release_type`」之前）。所以行上不给类型选择器，那句「未指定版本类型」
 * 也不该出现在它们身上。
 */
export function needsNumber(draft: PendingDraft): boolean {
  if (draft.live === null) return false
  return usableCore(draft.live) === null
}

/**
 * **这一份缺的是「类型」**：基线可算（有号、且不是预发布），而草稿没声明类型。
 *
 * 筛选「缺少版本类型」、动作条那句「N 份未指定版本类型」、行上那颗选择器，三处都读这一个判断——
 * 各写一遍条件，就是上一轮那种"三处说三件不同的事"。
 */
export function needsType(draft: PendingDraft): boolean {
  return draft.live !== null && !needsNumber(draft) && draft.release === null
}

/**
 * 这一份现在能不能被勾上：**有号可算**。
 *
 * 权限不在这里判——**发不了的 skill 根本不进这张单子**（队列只列我能上线的），所以"够不够
 * `maintain`"是查询的事，不是每一行的事。页面上一行看得见、却发不出去，只是让人多点一次确认单。
 */
export function selectable(draft: PendingDraft): boolean {
  return previewOf(draft) !== null
}

/**
 * 等着发的草稿。
 *
 * **故意让两个 skill 攒了多份**（`changelog-writer` 两份、`log-triage` 三份）：多份草稿是这一页
 * 按草稿成行的理由，只在夹具里留一份齐整的队列，等于把那一态藏起来。
 */
export const DRAFTS: PendingDraft[] = [
  {
    id: 'd-log-3', namespace: 'dev', skill: 'log-triage', draft: '重写分类',
    live: '0.9.0', submitted: '2026/10/25 09:12', release: 'major',
  },
  {
    id: 'd-log-2', namespace: 'dev', skill: 'log-triage', draft: '补样例',
    live: '0.9.0', submitted: '2026/10/24 20:40', release: 'minor',
  },
  {
    id: 'd-log-1', namespace: 'dev', skill: 'log-triage', draft: '加时间戳',
    live: '0.9.0', submitted: '2026/10/23 15:02', release: 'patch',
  },
  {
    id: 'd-perf-1', namespace: 'dev', skill: 'perf-budget', draft: '初版',
    live: null, submitted: '2026/10/25 09:40', release: null,
  },
  {
    id: 'd-chg-2', namespace: 'dev', skill: 'changelog-writer', draft: '补测试',
    live: '1.0.0', submitted: '2026/10/24 18:12', release: 'minor',
  },
  {
    id: 'd-chg-1', namespace: 'dev', skill: 'changelog-writer', draft: '修错别字',
    live: '1.0.0', submitted: '2026/10/22 10:05', release: 'patch',
  },
  {
    id: 'd-sql-1', namespace: 'yuhan', skill: 'sql-review', draft: '加锁表规则',
    live: '0.1.0', submitted: '2026/10/24 16:03', release: null,
  },
  {
    id: 'd-api-1', namespace: 'dev', skill: 'api-doc-writer', draft: '修错别字',
    live: '2.0.0', submitted: '2026/10/23 11:27', release: 'patch',
  },
  {
    id: 'd-i18n-1', namespace: 'yuhan', skill: 'i18n-extract', draft: '支持 Vue',
    live: '1.4.0', submitted: '2026/10/21 13:12', release: null,
  },
  /* **基线是预发布的那一份**（`yuhan` 的 `incident-notes` 线上是 `2.0.0-rc.2`）。它**没有**版本
     类型，可缺的并不是类型——选了类型也照样算不出号，所以行上说的不是「未指定版本类型」，而是
     另一句话、也不给选择器。两种勾不上混成一句，人就会去找那颗并不存在的选择器。放在 `yuhan`
     而不是默认那一屏：默认那一屏讲的是"齐整的队列长什么样"。 */
  {
    /* **故意不给类型**：这一行要证明的是「基线不可用」排在「缺类型」之前——选了类型也还是
       算不出来，所以行上说预发布、也不给选择器。给它一个类型，这一条规则就测不到了。 */
    id: 'd-inc-1', namespace: 'yuhan', skill: 'incident-notes', draft: '补复盘模板',
    live: '2.0.0-rc.2', submitted: '2026/10/20 09:18', release: null,
  },
]

/**
 * 排法：**同一个 skill 的草稿挨在一起，新的在前；skill 之间按各自最新的那一份排**。
 *
 * 按提交时间一条线排会让同一个 skill 的三份草稿散在三个地方——而人在这张单子上想的是
 * "这个 skill 我还欠着什么"，不是"全局最近改了什么"。
 */
export function ordered(rows: PendingDraft[]): PendingDraft[] {
  const newest = new Map<string, string>()
  for (const row of rows) {
    const key = row.namespace + '/' + row.skill
    if (!newest.has(key) || row.submitted > newest.get(key)!) newest.set(key, row.submitted)
  }
  return [...rows].sort((a, b) => {
    const ka = a.namespace + '/' + a.skill
    const kb = b.namespace + '/' + b.skill
    if (ka !== kb) return newest.get(kb)!.localeCompare(newest.get(ka)!)
    return b.submitted.localeCompare(a.submitted)
  })
}

/**
 * **这一份上线时会撞号的那句话**（夹具里只有一条）。
 *
 * `log-triage` 攒了 `major`，从 `0.9.0` 推到 `1.0.0`——而 `1.0.0` 已经在这个 skill 下存在了。
 * 撞号只失败、不顺延（ADR 0091），所以这条的结局是注定的。写成函数而不是散在两处判断：
 * 队列的结果、执行单里那一行的红字、结算的计数，说的是同一件事。
 */
export function collisionMessage(row: PendingDraft): string | null {
  /* **撞号只能从"这个 skill 的历史"来，不能从名字来**：同名的那一行在长队列夹具里是另一个
     skill，它从没上线过，`1.0.0` 是它应得的第一版，不是冲突。
     条件读的是"这一份现在算出来是几号"而不是它的 id——把类型从主版本改成次版本，号就成了
     0.10.0，撞的就不是 1.0.0；写死 id 的版本会在人改完类型之后继续说着一个不存在的冲突。 */
  const preview = previewOf(row)
  if (preview === null || !row.live) return null
  const taken = row.skill === 'log-triage' ? ['1.0.0'] : []
  return taken.includes(preview) ? '版本号 ' + preview + ' 已被占用，不会自动顺延' : null
}

/**
 * **这一份上线时真正拿到的号**——与预演不同的那一条。
 *
 * `changelog-writer` 的「补测试」预演是 `1.1.0`；确认之后有人抢先把这个 skill 上线到 `1.1.0`
 * 于是它实际落在 `1.2.0`（`afterRun` 把同组剩余行的基线跟着写成 `1.2.0`）。**结果屏上写的是这个
 * 数，不是预演**——两者的差别由结算正文那句「确认之后，changelog-writer 已被上线至 1.1.0，因此这
 * 一份的实际版本号为 1.2.0」说出来（`voice-spec` §四 的句式）。
 */
export function actualOf(row: PendingDraft): string | null {
  const preview = previewOf(row)
  if (preview === null) return null
  /* 同一个道理：先抢的那一版是 1.1.0，只有预演正好是 1.1.0 的那一份才会被顶到 1.2.0。 */
  return row.skill === 'changelog-writer' && preview === '1.1.0' ? '1.2.0' : preview
}

/**
 * 「发过一次之后」的样子：成功的那几份离开队列，失败的那一份留下并带着原因。
 *
 * 用一个纯函数造出来，而不是写第二份夹具——两份夹具迟早会说两件不同的事。
 */
export function afterRun(rows: PendingDraft[], done: string[]): PendingDraft[] {
  return rows
    .filter((row) => !done.includes(row.id))
    .map((row) => {
      const failure = collisionMessage(row)
      if (failure !== null) return { ...row, failure }
      /* **线上版本在确认之后变了**：这一批发出去的是 1.2.0（有人抢先发了 1.1.0，于是我们落到
         1.2.0）。同 skill 下还留着的那一份，基线就是 1.2.0——不是上一屏那个 1.0.0，也不是被人
         抢先的 1.1.0。写错一版，它就会预告一个根本不会出现的号。 */
      if (row.skill === 'changelog-writer') return { ...row, live: '1.2.0' }
      return row
    })
}

/**
 * **长队列**：24 个 skill（`&many=1`），用来量这张表滚起来是什么样。
 *
 * 不另写一份夹具的例外——它就是 `DRAFTS` 的同一个形状，只是数量够到"一屏放不下"。真实的队列在
 * 搬运之后就是这个规模，而十条以内看不出分组、吸底动作条与表头这三处会不会散架。
 */
const LONG_SKILLS = [
  'log-triage', 'perf-budget', 'changelog-writer', 'sql-review', 'api-doc-writer',
  'i18n-extract', 'incident-notes', 'release-notes', 'commit-lint', 'pdf-tools',
  'bundle-triage', 'css-token-audit', 'doc-sync', 'schema-guard', 'flaky-finder',
  'onboarding-kit', 'pr-summariser', 'metric-namer', 'retro-helper', 'env-doctor',
  'deploy-checklist', 'test-seeder', 'code-reviewer', 'adr-writer',
]

const LONG_DRAFTS = ['补文档', '修错别字', '补样例', '加校验', '拆函数', '换示例']

export function longQueue(): PendingDraft[] {
  const types: (ReleaseType | null)[] = ['major', 'minor', 'patch', null]
  const stamp = (i: number) => {
    const day = String(25 - Math.floor(i / 2)).padStart(2, '0')
    const hour = String(8 + (i % 9)).padStart(2, '0')
    const minute = String((i * 11) % 60).padStart(2, '0')
    return `2026/10/${day} ${hour}:${minute}`
  }
  const rows = LONG_SKILLS.map((skill, i): PendingDraft => ({
    id: 'd-long-' + i,
    namespace: i % 6 === 5 ? 'yuhan' : 'dev',
    skill,
    draft: LONG_DRAFTS[i % LONG_DRAFTS.length],
    /* 每五个有一个从未上线；其余版本号各不相同，好让「将以 … 上线」那一列看得出是算出来的。 */
    live: i % 5 === 0 ? null : `${1 + (i % 3)}.${i % 4}.${i % 6}`,
    submitted: stamp(i),
    /* 每四个缺一个类型——这一列在长队列里才会显出"卡住的那几条"有多少。 */
    release: types[i % 4],
  }))
  /* **两个 skill 攒了两份**：分组在 24 行里也要能认出来，而不只是在十条的夹具里。 */
  for (const i of [12, 19]) {
    rows.push({
      ...rows[i],
      id: 'd-long-' + i + '-b',
      draft: '补测试',
      submitted: stamp(i + 1),
    })
  }
  return rows
}

/**
 * **名字很长**（`&long=1`）：一个 skill 名 50 多个字符、草稿名一整句中文。
 *
 * 这一页的每一格都是定宽的，所以长名字必须**在自己那格里交代清楚**——是被截断（并留下一个能读到
 * 全文的办法），还是折行把行高撑开？两种都会让"整齐"这件事松动一处，得看着选。
 */
export function longNames(): PendingDraft[] {
  return [
    {
      id: 'd-longname-1',
      namespace: 'dev',
      skill: 'customer-support-ticket-classification-and-routing',
      draft: '把退货流程里跨仓调拨那一节的判定条件重写一遍',
      live: '1.2.3', submitted: '2026/10/25 09:40', release: 'minor',
    },
    {
      id: 'd-longname-2',
      namespace: 'dev',
      skill: 'release-notes',
      draft: '把这一版里所有会改变对外行为的改动都写出来，包括那些只在边缘情况下才会碰到的',
      live: '2.0.1', submitted: '2026/10/24 18:12', release: 'patch',
    },
    {
      id: 'd-longname-3',
      namespace: 'yuhan',
      skill: 'internal-tooling-migration-checklist-for-legacy-services',
      draft: '补测试',
      live: null, submitted: '2026/10/24 11:05', release: null,
    },
    {
      id: 'd-longname-4',
      namespace: 'dev',
      skill: 'pdf-tools',
      draft: '修错别字',
      live: '0.4.0', submitted: '2026/10/23 15:30', release: null,
    },
    {
      id: 'd-longname-5',
      namespace: 'dev',
      skill: 'customer-support-ticket-classification-and-routing',
      draft: '补样例',
      live: '1.2.3', submitted: '2026/10/22 10:20', release: 'patch',
    },
  ]
}

/**
 * **被截断的队列**（`&cut=1`）：作者面一次最多取 500 个 skill，更多的没有列进来。
 *
 * 这一态要画的是**那句提示坐在哪儿**，而它不能挂在一张十条的单子上——挂在十条的单子上，
 * 「只列出了最近 500 个 skill」会变成一句自相矛盾的话。所以它真的铺满 500 行：提示说的是这张
 * 单子的边界，而这张单子确实有边界那么大。
 *
 * **一屏一屏翻不动这件事也要照实看见**：500 行没有分页，只能滚、只能靠上面那颗搜索框找。
 */
export function cutQueue(): PendingDraft[] {
  const START = Date.UTC(2026, 9, 25, 9, 40)
  const pad = (n: number) => String(n).padStart(2, '0')
  const stamp = (ms: number) => {
    const at = new Date(ms)
    return `2026/${pad(at.getUTCMonth() + 1)}/${pad(at.getUTCDate())} ` +
      `${pad(at.getUTCHours())}:${pad(at.getUTCMinutes())}`
  }
  return Array.from({ length: 500 }, (_, i): PendingDraft => ({
    /* 名字由两个真词干拼一个序号：500 行里没人读名字（那一行本来也不同），但一眼要认得出这是
       一个真实的库，而不是 `skill-042` 那种占位符。 */
    id: 'd-cut-' + i,
    namespace: 'dev',
    skill: LONG_SKILLS[i % LONG_SKILLS.length] + '-' + (Math.floor(i / LONG_SKILLS.length) + 1),
    draft: LONG_DRAFTS[i % LONG_DRAFTS.length],
    live: i % 7 === 0 ? null : `${1 + (i % 3)}.${i % 4}.${i % 6}`,
    submitted: stamp(START - i * 37 * 60 * 1000),
    release: 'patch',
  }))
}

/**
 * **两类历史行**（`&legacy=1`）——不是坏数据，是 v11（版本名改成 semver）之前的行。
 *
 * 1. `runbook-sync` 的线上版本**没有号**：上线过，但那一版是"按 digest 寻址"的旧写法。它没有
 *    基线可推，所以算不出号，要人直接给一个；状态那一格写 `—`，**不是「从未上线」**。
 * 2. `capacity-notes` 的草稿**没有名字**：v6 之前没有 `draft_name` 的行。写「未命名」。
 *
 * 两行放在两个 skill 上，各自一行说完——这一点不需要分组来演示。
 */
export function legacyQueue(): PendingDraft[] {
  return [
    {
      id: 'd-legacy-1', namespace: 'dev', skill: 'runbook-sync', draft: '补回滚一节',
      live: '', submitted: '2026/10/24 10:02', release: 'patch',
    },
    {
      id: 'd-legacy-2', namespace: 'dev', skill: 'capacity-notes', draft: '',
      live: '1.6.2', submitted: '2026/10/20 08:31', release: 'patch',
    },
  ]
}

/** 一个 skill 与它等着发的草稿。 */
export interface SkillGroup {
  key: string
  skill: string
  namespace: string
  live: string | null
  drafts: PendingDraft[]
}

/**
 * 按 skill 分组，顺序沿用 {@link ordered}：skill 之间按各自最新那一份排，组内新的在前。
 *
 * **分组不等于分层。** 只有一份草稿的 skill 在页面上仍是一行——搬运完的 33 个 skill 每个只有一份，
 * 给它们各配一个父行，只会让高度翻一倍、信息一个不多。
 */
export function group(rows: PendingDraft[]): SkillGroup[] {
  const out: SkillGroup[] = []
  for (const row of ordered(rows)) {
    const key = row.namespace + '/' + row.skill
    const last = out[out.length - 1]
    if (last && last.key === key) {
      last.drafts.push(row)
      continue
    }
    out.push({ key, skill: row.skill, namespace: row.namespace, live: row.live, drafts: [row] })
  }
  return out
}
