import { useEffect, useRef, useState } from 'react'
import { addressFor, go, query as pageQuery, replaceAddress } from '../address'
import { codeMessage } from '../api/errors'
import { publishBatch, readPublishQueue, setDraftReleaseType } from '../api/publish'
import type { PublishQueue, ReleaseType } from '../api/types'
import { loginPath } from '../login'
import FormBanner from '../components/FormBanner'
import MenuSelect from '../components/MenuSelect'
import SearchBox from '../components/SearchBox'
import ConfirmDialog from '../components/ConfirmDialog'
import type { BatchState, SheetOutcome } from '../components/ConfirmDialog'
import PublishRows from '../components/publish/PublishRows'
import {
  movedSentence,
  needsType,
  noteSentence,
  previewOf,
  rowsOf,
  selectable,
  settledBody,
  settlementTitle,
  sheetLines,
} from '../components/publish/publishModel'
import type { MovedEntry, PendingDraft } from '../components/publish/publishModel'

/**
 * 待上线：**所有还在等的草稿**，一处看完、一次发完。
 *
 * **这一页叫「待上线」，不叫「上线」，也不叫「发布」**（2026-10-25 定）。三个词各归各位：
 * 「上线」是**动作**（按钮、确认单、预演句里的那个动词），「待上线」是**这块地方**（导航项与页
 * 标题），而「发布」在界面上**一个都不出现**——它与 skill 页写的「上线」是同一件事的两个名字。
 *
 * **一行是一份草稿，不是一个 skill。** 这一页的主语是"等着发的草稿"；一个 skill 攒了三份就是
 * 三行，这也是它不能塞进「我的 skill」的原因——那一页一行是一个 skill。
 *
 * **发不了的 skill 不在这里。** 队列只列我够得着上线的（`maintain` 以上）：别人共享给我、而我
 * 只到 `invoke` / `browse` / `write` 的 skill，它的草稿不进来。摆一行"你没有这一份的上线权限"，
 * 是把一个我本来就做不了的决定摆在我面前。
 *
 * **顺序是服务端的**。页面不重排 (TD §接口)：skill 之间按各自最新那份草稿的时间倒序，skill 内
 * 新的在前——两处各排一次，迟早排出两个顺序。
 */

const ONLY_GROUPS = [
  {
    options: [
      { value: '', label: '全部' },
      // 文案是**名词性的状态**，不是对着人说话的祈使句：筛选器列的是"哪些条目"，不是"你该干什么"。
      { value: 'step', label: '缺少版本类型' },
      { value: 'pick', label: '有多份草稿' },
    ],
  },
]

type LoadState = 'loading' | 'ready' | 'failed'

/** The sheet's three phases plus what it is acting on. `null` while it is closed. */
interface Sheet {
  rows: PendingDraft[]
  phase: 'confirm' | 'running' | 'done'
  /** One entry per row of `rows`, `null` until that row reports. */
  outcomes: (SheetOutcome | null)[]
  error: string | null
  /** The settlement's two sentences, computed once the last row has reported. */
  doneTitle: string
  doneBody: string
  /** Rows whose number moved between the confirmation and the publish — the page says so after. */
  moved: MovedEntry[]
}

export default function PublishPage() {
  const initial = useRef(pageQuery()).current

  const [query, setQuery] = useState(() => initial.get('q') ?? '')
  const [only, setOnly] = useState(() => {
    const asked = initial.get('only')
    return asked === 'step' || asked === 'pick' ? asked : ''
  })
  const [ns, setNs] = useState(() => initial.get('ns') ?? '')
  const [picked, setPicked] = useState<string[]>(() =>
    (initial.get('picked') ?? '').split(',').filter(Boolean),
  )
  /** 收起的那些 skill。**默认全展开**：这是一张要清的活单子，不是一层目录。 */
  const [folded, setFolded] = useState<string[]>([])

  const [queue, setQueue] = useState<PublishQueue | null>(null)
  const [rows, setRows] = useState<PendingDraft[]>([])
  const [state, setState] = useState<LoadState>('loading')
  /** 「号变了」那句解释。**是说明，不是失败**——上线成功了，只是落在的号更大。 */
  const [note, setNote] = useState<string | null>(null)
  /** 一次读或一次写入真的失败了。与上面那句分开，因为它们穿的颜色不同。 */
  const [failure, setFailure] = useState<string | null>(null)
  /**
   * 这一批里有哪几行的号动了，**等重读回来再写那句话**。
   *
   * 结算那一刻还不知道"同组还留着的"几份现在预演成多少——那些数是重读之后才有的事实。用一个
   * ref 而不是 state：它只在下面那次读的回调里被读一次，不需要一次渲染。
   */
  const movedToExplain = useRef<MovedEntry[] | null>(null)
  /** Why a row did not publish, kept across the re-read so the reason stays on its own row. */
  const [failures, setFailures] = useState<Record<string, string>>({})
  const [reloadKey, setReloadKey] = useState(0)
  const [sheet, setSheet] = useState<Sheet | null>(null)

  /* --------------------------------------------------------------------- the page read */

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const result = await readPublishQueue()
      if (cancelled) {
        return
      }
      if (!result.ok) {
        if (result.code === 'unauthenticated') {
          go(loginPath())
          return
        }
        setState('failed')
        return
      }
      setQueue(result.data)
      const next = rowsOf(result.data)
      setRows(next)
      // **基线变了，留在单子上的行也跟着变了**（设计的规矩）：那一句横幅要说的是"这一批发出去
      // 之后线上是几号"，于是同组还没发的那一份的预演也跟着走。只有重读之后才知道那是几，所以
      // 这句话在这里拼，而不是在结算的那一刻。
      const moved = movedToExplain.current
      if (moved !== null) {
        movedToExplain.current = null
        setNote(noteSentence(moved, next))
      }
      // A selected id that is no longer on the page (published, discarded elsewhere) must not stay
      // selected: it would be counted in the bar and sent in the batch.
      const live = new Set(next.map((row) => row.id))
      setPicked((current) => current.filter((id) => live.has(id)))
      setState('ready')
    })()
    return () => {
      cancelled = true
    }
  }, [reloadKey])

  /** The failure reasons belong to the rows; they are re-applied after every re-read. */
  const withFailures = rows.map((row) =>
    failures[row.id] === undefined ? row : { ...row, failure: failures[row.id] },
  )

  /* ------------------------------------------------------------------------ address */

  function writeAddress(next: {
    q?: string
    only?: string
    ns?: string
    picked?: string[]
  }): void {
    const params = new URLSearchParams()
    const nextQuery = next.q ?? query
    const nextOnly = next.only ?? only
    const nextNs = next.ns ?? ns
    const nextPicked = next.picked ?? picked
    // **默认那一个不写进地址**（设计件的规矩）：`/publish` 就是它，`?ns=yuhan` 才是另一个。两处
    // 各写一次，同一个状态就有了两个地址。
    const defaultNs = namespaces[0] ?? ''
    if (nextQuery.trim() !== '') {
      params.set('q', nextQuery)
    }
    if (nextOnly !== '') {
      params.set('only', nextOnly)
    }
    if (nextNs !== '' && nextNs !== defaultNs) {
      params.set('ns', nextNs)
    }
    if (nextPicked.length > 0) {
      params.set('picked', nextPicked.join(','))
    }
    replaceAddress(addressFor('/publish', params))
  }

  /* --------------------------------------------------------------------- selection */

  /** 一个 skill 有几份草稿——**多于一份就是"要挑一份"**，与可不可算号无关。 */
  const draftCounts = new Map<string, number>()
  for (const row of withFailures) {
    const key = `${row.namespace}/${row.skill}`
    draftCounts.set(key, (draftCounts.get(key) ?? 0) + 1)
  }
  const multi = (row: PendingDraft): boolean =>
    (draftCounts.get(`${row.namespace}/${row.skill}`) ?? 0) > 1

  /**
   * **命名空间是一个筛选项，单选。** 一个账号可以在几个命名空间下上线，混在一张单子里，扫的人分
   * 不清这一批在往哪里发——所以一次只看、只发一个。没有"全部"：那等于把要分开的东西又合起来。
   * **别处有多少在等，写在菜单里**，所以切过去是一眼的事。
   */
  const namespaces = [...new Set(withFailures.map((row) => row.namespace))].sort()
  const currentNs = namespaces.includes(ns) ? ns : (namespaces[0] ?? '')

  const wanted = (row: PendingDraft): boolean => {
    // 筛的是「缺类型」，不是「号算不出来」：基线是预发布的那一份有类型，它缺的是显式号。
    if (only === 'step') {
      return needsType(row)
    }
    if (only === 'pick') {
      return multi(row)
    }
    return true
  }
  const needle = query.trim().toLowerCase()
  const matches = (row: PendingDraft): boolean =>
    needle === '' || `${row.skill} ${row.draft}`.toLowerCase().includes(needle)
  const visible = withFailures.filter(
    (row) => row.namespace === currentNs && wanted(row) && matches(row),
  )

  /* **计数就是列表里的行**：发失败的那一份也还在等。筛过之后说的是筛出来的那些。 */
  const waitingSkills = new Set(visible.map((row) => `${row.namespace}/${row.skill}`)).size

  /**
   * **全选：每个 skill 勾它最新提交的那一份。**
   *
   * 一批里同一个 skill 只能发一份，所以全选**不可能**等于"每一行都勾上"——它总要在几份里挑一份。
   * 挑最新那份，是因为草稿是不可变的行、后来的包含前面的内容。**已经选过的不动**：全选加的是
   * "还没有选择"的那些 skill，不替已经被决定的改。
   */
  const newest = new Map<string, PendingDraft>()
  for (const row of visible) {
    const key = `${row.namespace}/${row.skill}`
    if (!newest.has(key)) {
      newest.set(key, row)
    }
  }
  const candidates = [...newest.values()].filter(selectable)
  const pickable = new Set(candidates.map((row) => `${row.namespace}/${row.skill}`))
  const covered = new Set(
    withFailures
      .filter((row) => picked.includes(row.id) && row.namespace === currentNs)
      .map((row) => `${row.namespace}/${row.skill}`),
  )
  const allPicked = pickable.size > 0 && [...pickable].every((key) => covered.has(key))

  const pickedRows = withFailures.filter(
    (row) => picked.includes(row.id) && row.namespace === currentNs,
  )
  const chosenNewest = new Set(
    pickedRows
      .filter(
        (row) => multi(row) && newest.get(`${row.namespace}/${row.skill}`)?.id === row.id,
      )
      .map((row) => `${row.namespace}/${row.skill}`),
  ).size
  /** 只数"缺类型"的那一种：基线是预发布的那一份不是缺类型，它要的是人直接给一个号。 */
  const toType = visible.filter(needsType).length

  const keep = (next: string[]): void => {
    setPicked(next)
    writeAddress({ picked: next })
  }

  const toggle = (id: string): void => {
    keep(picked.includes(id) ? picked.filter((item) => item !== id) : picked.concat(id))
  }

  const selectAll = (): void => {
    if (allPicked) {
      keep([])
      return
    }
    const taken = new Set(
      picked
        .map((id) => withFailures.find((row) => row.id === id))
        .filter((row): row is PendingDraft => row !== undefined)
        .map((row) => `${row.namespace}/${row.skill}`),
    )
    const add = candidates
      .filter((row) => !taken.has(`${row.namespace}/${row.skill}`))
      .map((row) => row.id)
    keep(picked.concat(add))
  }

  const toggleFold = (key: string): void => {
    setFolded((current) =>
      current.includes(key) ? current.filter((item) => item !== key) : current.concat(key),
    )
  }

  /* ------------------------------------------------------------------- release type */

  /**
   * **写回服务端，不是客户端状态。** 类型是下一次上线算号的依据，CLI 与控制台是两条写入的路；
   * 只活在这个标签页里的值，会让 CLI 读到一件事、页面显示另一件。
   */
  async function setType(row: PendingDraft, type: ReleaseType): Promise<void> {
    setFailure(null)
    const result = await setDraftReleaseType(row.namespace, row.skill, row.code, type)
    if (!result.ok) {
      // **写失败要说出来，而且要穿失败的颜色**：它是"这一档没记下"，不是一句说明。
      setFailure(codeMessage(result.code, result.message, result.details))
      return
    }
    setRows((current) =>
      current.map((item) => (item.id === row.id ? { ...item, release: type } : item)),
    )
    // 改过类型之后，之前那条"算不出号"的失败原因就不再成立了。
    setFailures((current) => {
      const next = { ...current }
      delete next[row.id]
      return next
    })
  }

  /* ------------------------------------------------------------------------ the batch */

  /**
   * **上线所选**：先列清单，再一条条走，最后结算。确认单逐条列名字与版本号——一批里发哪些、
   * 各变成什么号，是这个决定唯一有信息量的部分（ADR 0090）。
   */
  const chosen = withFailures.filter(
    (row) => picked.includes(row.id) && row.namespace === currentNs && selectable(row),
  )

  function ask(rows_: PendingDraft[]): void {
    setSheet({
      rows: rows_,
      phase: 'confirm',
      outcomes: rows_.map(() => null),
      error: null,
      doneTitle: '',
      doneBody: '',
      moved: [],
    })
  }

  /**
   * 确认之后，同一张单子不关，就地开始走。
   *
   * **一条一条地落**：结果由服务端逐条流回来，来一条落一条——界面上的顺序就是服务端做事的顺序。
   */
  async function run(sheetRows: PendingDraft[]): Promise<void> {
    setSheet((current) =>
      current === null ? current : { ...current, phase: 'running', error: null },
    )
    const index = new Map(sheetRows.map((row, at) => [`${row.namespace}/${row.skill}`, at]))
    const targets = sheetRows.map((row) => ({
      namespace: row.namespace,
      name: row.skill,
      version: row.code,
      predicted: previewOf(row) ?? undefined,
    }))

    const result = await publishBatch(targets, (row) => {
      const at = index.get(`${row.namespace}/${row.name}`)
      if (at === undefined) {
        return
      }
      const outcome: SheetOutcome = row.ok
        ? { ok: true, version: row.version ?? null }
        : { ok: false, message: codeMessage(row.code ?? '', row.message ?? '') }
      setSheet((current) => {
        if (current === null) {
          return current
        }
        const outcomes = current.outcomes.slice()
        outcomes[at] = outcome
        return { ...current, outcomes }
      })
    })

    setSheet((current) => {
      if (current === null) {
        return current
      }
      const outcomes = current.outcomes
      const ok = outcomes.filter((outcome) => outcome?.ok === true).length
      const failed = outcomes.filter((outcome) => outcome?.ok === false).length
      // **Rows that never reported**: the stream broke after the ones above committed. They are
      // counted, never narrated — 「失败」 would invent a reason, 「已上线」 would report something that
      // did not happen.
      const unsettled = outcomes.filter((outcome) => outcome === null || outcome === undefined).length
      const moved: MovedEntry[] = []
      sheetRows.forEach((row, at) => {
        const outcome = outcomes[at]
        const predicted = previewOf(row)
        if (outcome?.ok === true && predicted !== null && outcome.version !== null &&
            outcome.version !== undefined && outcome.version !== predicted) {
          moved.push({ namespace: row.namespace, skill: row.skill, predicted, actual: outcome.version })
        }
      })

      if (!result.ok && result.code !== 'stream_incomplete') {
        // 请求整体被拒：一条都没有发生，单子回到确认态，把理由写在它上面。
        return {
          ...current,
          phase: 'confirm',
          error: codeMessage(result.code, result.message, result.details),
        }
      }
      // **The criterion is rows that never reported, not the request's flag** (TD §界面). A stream
      // that loses only its `done` line still finished every row, so the ordinary settlement is the
      // true one; a request-level refusal already went back to the confirm phase above.
      const broken = unsettled > 0
      const doneBody = broken
        ? movedSentence(moved) + '连接中断了：已上线的不会撤回，其余的请重新上线。'
        : settledBody(moved, failed)
      return {
        ...current,
        phase: 'done',
        doneTitle: settlementTitle(ok, failed, unsettled),
        doneBody,
        moved,
        error: null,
      }
    })
  }

  /** 结算之后离开：这一批已经发生过，页面要按结果更新。 */
  function finish(): void {
    if (sheet === null) {
      return
    }
    const kept: Record<string, string> = {}
    sheet.rows.forEach((row, at) => {
      const outcome = sheet.outcomes[at]
      if (outcome !== null && outcome !== undefined && !outcome.ok && outcome.message) {
        kept[row.id] = outcome.message
      }
    })
    setFailures((current) => ({ ...current, ...kept }))
    movedToExplain.current = sheet.moved.length === 0 ? null : sheet.moved
    setNote(null)
    setSheet(null)
    keep([])
    setReloadKey((key) => key + 1)
  }

  /* ------------------------------------------------------------------------ the states */

  const ready = state === 'ready'
  const empty = ready && rows.length === 0
  const nothingMatches = ready && !empty && visible.length === 0
  const batchState: BatchState | undefined =
    sheet === null || sheet.phase === 'confirm'
      ? undefined
      : {
          phase: sheet.phase,
          outcomes: sheet.outcomes,
          runningTitle: `正在上线 ${sheet.rows.length} 份草稿`,
          doneTitle: sheet.doneTitle,
          doneBody: sheet.doneBody,
          doneLabel: '完成',
        }

  return (
    <>
      <section className={`page${picked.length > 0 ? ' is-selecting' : ''}`} id="page-publish">
        <header className="page-head">
          <h1>待上线</h1>
          {/* **空态不复述计数**（voice-spec §四）：空态自己那句话已经把"没有"说完了，上面再挂一行
              「0 份草稿待上线（0 个 skill）」是把同一个事实说了两遍。 */}
          {ready && rows.length > 0 ? (
            <span className="count">{`${visible.length} 份草稿待上线（${waitingSkills} 个 skill）`}</span>
          ) : null}
          {/* **截断了就说出来**（PRD 验收 19）：静默少列，人会以为"剩下的没有草稿"。 */}
          {ready && rows.length > 0 && queue?.truncated === true ? (
            <span className="count-note">只列出了最近 500 个 skill</span>
          ) : null}
          <div className="page-actions">
            {ready && !empty ? (
              <div className="toolbar">
                {/* **命名空间排在最前**：它圈定的是这一整页，搜索与筛选都在它里面用。 */}
                <label className="filter-select is-scope">
                  <span className="visually-hidden">命名空间</span>
                  <MenuSelect
                    groups={[
                      {
                        options: namespaces.map((name) => ({
                          value: name,
                          label: name,
                          number: String(
                            withFailures.filter((row) => row.namespace === name).length,
                          ),
                        })),
                      },
                    ]}
                    value={currentNs}
                    ariaLabel="命名空间"
                    triggerClass="is-scope"
                    icon={
                      <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <path
                          d="M8 1.9 14 5.2 8 8.5 2 5.2Z"
                          stroke="currentColor"
                          strokeWidth="1.4"
                          strokeLinejoin="round"
                        />
                        <path
                          d="M2.5 8.6 8 11.6l5.5-3"
                          stroke="currentColor"
                          strokeWidth="1.4"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                        />
                      </svg>
                    }
                    onSelect={(next) => {
                      setNs(next)
                      writeAddress({ ns: next })
                    }}
                  />
                </label>
                <SearchBox
                  id="publishQuery"
                  value={query}
                  placeholder="搜索 skill 或版本名称"
                  label="搜索"
                  onChange={(value) => {
                    setQuery(value)
                    writeAddress({ q: value })
                  }}
                />
                <label className="filter-select">
                  <span className="visually-hidden">按草稿状态筛选</span>
                  <MenuSelect
                    groups={ONLY_GROUPS}
                    value={only}
                    ariaLabel="按草稿状态筛选"
                    onSelect={(next) => {
                      setOnly(next)
                      writeAddress({ only: next })
                    }}
                  />
                </label>
                <button
                  type="button"
                  className={`button${allPicked ? ' is-quiet' : ''}`}
                  id="publishAll"
                  disabled={!allPicked && candidates.length === 0}
                  title={candidates.length === 0 ? '没有可直接勾选的草稿' : undefined}
                  onClick={selectAll}
                >
                  {allPicked ? '取消全选' : '全选'}
                </button>
              </div>
            ) : null}
          </div>
        </header>

        {/* 预演不是承诺：确认之后有人抢先上线了更高的版本，号会比单子上写的大。 */}
        {note !== null ? <div className="banner is-note">{note}</div> : null}
        <FormBanner
          message={state === 'failed' ? '无法加载待上线的草稿，请稍后重试。' : failure}
        />

        {state === 'loading' ? <p className="loading">加载中…</p> : null}

        {empty ? (
          <p className="empty" id="publishEmpty">
            没有待上线的草稿。提交新版本后，尚未上线的草稿会出现在这里。
          </p>
        ) : null}

        {ready && !empty ? (
          <div className="list-body">
            <PublishRows
              rows={visible}
              picked={picked}
              folded={folded}
              onToggle={toggle}
              onToggleFold={toggleFold}
              onSetType={(row, type) => {
                void setType(row, type)
              }}
            />

            {/* 筛出来是空的，与"队列本来就没有草稿"是两回事。 */}
            {nothingMatches ? (
              <p className="empty" id="publishNone">
                没有符合条件的草稿。请调整搜索词或筛选条件。
              </p>
            ) : null}
          </div>
        ) : null}

        {/* 动作条：没有选中项时不画，省一行高度。 */}
        {pickedRows.length > 0 ? (
          <div className="select-bar" id="publishBar">
            <span className="select-count">{`已选 ${pickedRows.length} 份`}</span>
            {/* **全选做了什么，在这儿说清。** 多份草稿的 skill 只勾了最新的一份，这句话就是那几行
                被选中的理由；发不了的另说一句。 */}
            {allPicked && (chosenNewest > 0 || toType > 0) ? (
              <span className="select-note">
                {'· ' +
                  [
                    chosenNewest > 0 ? `${chosenNewest} 个 skill 取最新草稿` : '',
                    toType > 0 ? `${toType} 份未指定版本类型` : '',
                  ]
                    .filter(Boolean)
                    .join('、')}
              </span>
            ) : null}
            <span className="spacer"></span>
            <button type="button" className="button is-quiet" onClick={() => keep([])}>
              取消选择
            </button>
            <button
              type="button"
              className="button"
              id="publishGo"
              disabled={chosen.length === 0}
              onClick={() => ask(chosen)}
            >
              上线所选
            </button>
          </div>
        ) : null}
      </section>

      <ConfirmDialog
        open={sheet !== null}
        title={sheet === null ? '' : `上线 ${sheet.rows.length} 份草稿？`}
        body="上线后，未指定版本号的调用将立即读到这些版本；版本名称与说明随即锁定。"
        confirmLabel="确认上线"
        pendingLabel="正在上线…"
        danger={false}
        error={sheet?.error ?? null}
        lines={sheet === null ? undefined : sheetLines(sheet.rows)}
        batch={batchState}
        onConfirm={() => {
          if (sheet !== null) {
            void run(sheet.rows)
          }
        }}
        onCancel={() => setSheet(null)}
        onDone={finish}
      />
    </>
  )
}
