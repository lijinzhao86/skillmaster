/**
 * 待上线：**所有还在等的草稿**，一处看完、一次发完。
 *
 * **这一页叫「待上线」，不叫「上线」，也不叫「发布」**（2026-10-25 定）。三个词各归各位：
 * 「上线」是**动作**（按钮、确认单、预演句里的那个动词），「待上线」是**这块地方**（导航项与页
 * 标题），而「发布」在界面上**一个都不出现**——[`voice-spec.md`](../../../voice-spec.md) §三 早在
 * 2026-10-16 就把它从名词表里请了出去，因为它与 skill 页写的「上线」是同一件事的两个名字。
 *
 * **是一张表，不是一串各说各的行。** 五列固定：勾选位 ｜ 名字（skill ＋ 草稿）｜ 状态 ｜ 提交于 ｜ 将以…。
 * 每一行都用同一套列宽，所以草稿名、提交时间、版本号各在各的竖线上——扫一列就够，不用横向找。
 * （上一版单份 skill 两行、组里一行，草稿名一个在最左一个缩进，右端两个数也不齐，读起来是锯齿。）
 *
 * **skill 是主列表，草稿是它的子列表——但只在真有子项时才分层。** 攒了两份以上草稿的 skill 多一行
 * 表头（只填前三列，草稿那列写「N 份草稿」），底下每份一行（后三列）。只有一份的 skill 五列全填，
 * 一行说完。搬运完的 33 个 skill 每个只有一份，给它们各配一个表头，只会让高度翻一倍、信息一个不多。
 *
 * **父行不带勾选框。** 这一页的规矩是一批里同一个 skill 只发一份，所以"勾父行"只能表示"发最新那份"
 * ——那是在替人做一次选择。父行只负责收起展开，选择永远发生在具体的那一份上。
 *
 * **勾不上是有话要说的。** 没有号可算的那一份——没选版本类型、线上那一版没有号、或基线是预发布版本——勾选位不可用，
 * 而**行上就地说明缺什么**：前者在行上给一个「选类型」的选择器，选完立刻写回服务端（不是客户端
 * 状态；否则 CLI 与控制台会各说一套）。一个灰掉的勾选位不说话，人只会以为页面坏了。
 *
 * **发不了的 skill 不在这里。** 队列只列我够得着上线的（`maintain` 以上）：别人共享给我、而我只到
 * `invoke` / `browse` / `write` 的 skill，它的草稿不进来。摆一行"你没有上线这一份的权限"，是把一个
 * 我本来就做不了的决定摆在我面前。
 */

import { Fragment, useEffect, useState } from 'react'
import MenuSelect from '../components/MenuSelect'
import SearchBox from '../components/SearchBox'
import { replace } from '../address'
import { dialogs } from '../components/dialogs/store'
import {
  DRAFTS,
  RELEASE_LABEL,
  RELEASE_MEANING,
  afterRun,
  actualOf,
  collisionMessage,
  cutQueue,
  group,
  legacyQueue,
  longNames,
  longQueue,
  needsNumber,
  needsType,
  ordered,
  previewOf,
  selectable,
} from '../fixtures/publish'
import type { PendingDraft, ReleaseType, SkillGroup } from '../fixtures/publish'
import type { DemoPageProps } from './types'

const TYPE_GROUPS = [{
  options: (['major', 'minor', 'patch'] as ReleaseType[]).map((type) => ({
    value: type,
    label: RELEASE_LABEL[type],
    meta: RELEASE_MEANING[type],
  })),
}]

/**
 * 队列的筛选——**两个状态值得单独看**，其余靠扫。
 *
 * 一条队列的活儿就两件：**能发的先发掉**、**不能发的补上**。「缺少版本类型」是后者；
 * 「有多份草稿」是前者里唯一需要人做判断的那一小撮（一个 skill 攒了几份，一批只能发一份）。
 * 「能直接发的」不单列——它就是其余的，筛掉前两种自然剩下。
 *
 * 文案是**名词性的状态**，不是对着人说话的祈使句：筛选器列的是"哪些条目"，不是"你该干什么"。
 */
const ONLY_GROUPS = [{
  options: [
    { value: '', label: '全部' },
    { value: 'step', label: '缺少版本类型' },
    { value: 'pick', label: '有多份草稿' },
  ],
}]

/**
 * **版本名称怎么写**：没有名字的那一份写「未命名」（v6 之前的历史行，td 定的口径）。
 *
 * 单子上、行上、读屏标签里都走这一个函数——同一个东西三处各写一遍，迟早有一处写的是空串。
 */
function draftLabel(row: PendingDraft): string {
  return '「' + (row.draft === '' ? '未命名' : row.draft) + '」'
}

/** 确认单上的一条：skill 名字是身份，草稿名与量级是"发的是哪一份、会变成什么号"。 */
function sheetLines(rows: PendingDraft[]) {
  return rows.map((row) => ({
    name: row.skill + ' · ' + draftLabel(row),
    version: previewOf(row) ?? '',
    note: row.live === null ? '首次上线' : row.release ? RELEASE_LABEL[row.release] : '',
  }))
}

/**
 * **每一条的结果。** 成功的取**实际号**（`actualOf`）——预演只是"按我看到的基线算"，而确认之后
 * 基线可能已经变了（ADR 0091、PRD 验收 16）；撞号的那一条带原因。
 */
function outcomesOf(rows: PendingDraft[]) {
  return rows.map((row) => {
    const message = collisionMessage(row)
    return message !== null
      ? { ok: false, message }
      /* **实际号，不是预演**：预演是"按我看到的基线算"，而确认之后基线可能已经变了（ADR 0091、
         PRD 验收 16）。结算单上写错一个号，就是让人以为发出去的是别的版本。 */
      : { ok: true, version: actualOf(row) ?? '' }
  })
}

/**
 * 确认单 + 执行单 + 结算，**是同一张单子的三段**（v18，2026-10-25 定）。人刚点过的那张清单，
 * 就地变成他要盯的那张清单：一条走完，它自己那一行落定；全部走完，标题换成一句话的结算。
 *
 * `snapshot` 只在演示件的地址里用：`?publish=running` / `=done` 打开就是那一段，不放动画。
 */
function batchSheet(
  rows: PendingDraft[],
  snapshot?: { at: number; finished: boolean },
): Parameters<typeof dialogs.openDanger>[0] {
  return {
    title: '上线 ' + rows.length + ' 份草稿？',
    body: '上线后，未指定版本号的调用将立即读到这些版本；版本名称与说明随即锁定。',
    lines: sheetLines(rows),
    run: {
      outcomes: outcomesOf(rows),
      stepMs: 620,
      runningTitle: '正在上线 ' + rows.length + ' 份草稿',
      doneTitle: (ok, failed) => failed === 0
        ? ok + ' 份已上线'
        : ok + ' 份已上线，' + failed + ' 份失败',
      doneBody: (ok, failed) => {
        /* **号与预演不同，必须说出来**（PRD 验收 16）。写法照 `voice-spec` §四：**时间、对象、事实
           三样都写出来**——「有人抢先上线了更高的版本」正是它列的反例（谁干的、多大的号，全含糊）。 */
        const moved = rows.filter((row) => actualOf(row) !== null && actualOf(row) !== previewOf(row))
        const changed = moved.length === 0
          ? ''
          : moved.length === 1
            ? '确认之后，' + moved[0].skill + ' 已被上线至 ' + previewOf(moved[0]) +
              '，因此这一份的实际版本号为 ' + actualOf(moved[0]) + '。'
            : '确认之后，其中 ' + moved.length + ' 个 skill 的线上版本已被提高，因此这几份的实际版本号大于预演（逐条见对应的行）。'
        /* **不复述标题已经说过的数**（`voice-spec` §四 空态那条同一条理由）：标题里有「1 份失败」，
           正文只说它去哪了。 */
        return changed + (failed === 0 ? '以上草稿已从列表移除。' : '失败的草稿未从列表移除，原因见对应行。')
      },
      doneLabel: '完成',
      snapshot,
    },
    confirmLabel: '确认上线',
    pendingLabel: '正在上线…',
    danger: false,
    onConfirm: () => { /* 演示件没有服务端：留空，由调用处决定走不走地址 */ },
    returnFocus: null,
  }
}

export default function PublishPage({ active, state, params }: DemoPageProps) {
  /* **长队列**（`&many=1`）：24 个 skill。队列在搬运之后就是这个规模，十条以内看不出分组、吸底
     动作条与表头会不会散架。**被截断**（`&cut=1`）是它的上限那一头：500 个 skill，再多就没有了。 */
  const source = params.get('cut') === '1' ? cutQueue()
    : params.get('legacy') === '1' ? legacyQueue()
    : params.get('many') === '1' ? longQueue()
    : params.get('long') === '1' ? longNames()
    : DRAFTS
  const [rows, setRows] = useState<PendingDraft[]>(() => source.map((row) => ({ ...row })))
  const [picked, setPicked] = useState<string[]>(() => (params.get('picked') ?? '').split(',').filter(Boolean))
  /** 收起的那些 skill。**默认全展开**：这是一张要清的活单子，不是一层目录。 */
  const [folded, setFolded] = useState<string[]>([])
  /** 结果那一态：发过一次之后的样子。 */
  const ran = params.get('publish') === 'partial'
  useEffect(() => {
    if (!ran) return
    setRows(afterRun(source, ['d-chg-2', 'd-api-1', 'd-perf-1']))
    setPicked([])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ran])

  /* **菜单也能单独打开**：演示件的规矩是每一种点得出来的样子都有一个地址，否则它只能靠人手点
     出来，也拍不进图（与信息页的 `?edit=` 同一个做法）。 */
  const typesFor = params.get('types')

  const ready = state === 'ready'
  const empty = state === 'empty'

  /* **搜索与筛选在地址里**，与「我的 skill」同一套做法：筛完的队列可以直接发给别人，也能单独
     拍图。`only` 只有两个值——见 `ONLY_GROUPS` 上面那段。 */
  const query = params.get('q') ?? ''
  const only = params.get('only') ?? ''
  /** 这一份队列是被截断的（服务端还有没列进来的 skill）——见下面页头那句话。 */
  const truncated = params.get('cut') === '1'
  const writeAddress = (next: { q?: string; only?: string; ns?: string }) => {
    const copy = new URLSearchParams(params)
    copy.set('page', 'publish')
    if (next.q ?? query) copy.set('q', next.q ?? query)
    else copy.delete('q')
    if (next.only ?? only) copy.set('only', next.only ?? only)
    else copy.delete('only')
    /* 默认那一个不写进地址：`?page=publish` 就是它，多一个 `&ns=dev` 会变成同一个页面的第二个地址。 */
    if ((next.ns ?? ns) && (next.ns ?? ns) !== namespaces[0]) copy.set('ns', next.ns ?? ns)
    else copy.delete('ns')
    replace('?' + copy.toString())
  }

  /** 一个 skill 有几份草稿——**多于一份就是"要挑一份"**，与可不可算号无关。 */
  const draftCount = new Map<string, number>()
  for (const row of rows) {
    const key = row.namespace + '/' + row.skill
    draftCount.set(key, (draftCount.get(key) ?? 0) + 1)
  }
  const multi = (row: PendingDraft) => (draftCount.get(row.namespace + '/' + row.skill) ?? 0) > 1

  /**
   * **命名空间是一个筛选项，单选。** 一个账号可以在几个命名空间下上线（`dev`、`yuhan` 是不同的人
   * 或组织），混在一张单子里，扫的人分不清这一批在往哪里发——所以**一次只看、只发一个**，像阿里云的
   * 地域：换一个再看另一处。
   *
   * 没有"全部"这一个选项：那等于把要分开的东西又合起来。**别处有多少在等，写在菜单里**（每项后
   * 面跟它自己的份数），所以切过去是一眼的事，不是靠猜。
   */
  const namespaces = [...new Set(rows.map((row) => row.namespace))].sort()
  const askedNs = params.get('ns')
  const ns = askedNs !== null && namespaces.includes(askedNs) ? askedNs : (namespaces[0] ?? '')

  const wanted = (row: PendingDraft) => {
    /* 筛的是「缺类型」，不是「号算不出来」：基线是预发布的那一份有类型，它缺的是显式号，
       行上说的也是另一句话——把它筛进来等于给一个错的标签。 */
    if (only === 'step') return needsType(row)
    if (only === 'pick') return multi(row)
    return true
  }
  const needle = query.trim().toLowerCase()
  const matches = (row: PendingDraft) =>
    needle === '' ||
    (row.skill + ' ' + row.draft).toLowerCase().includes(needle)
  const visible = rows.filter((row) => row.namespace === ns && wanted(row) && matches(row))

  /* **计数就是列表里的行**，发失败的那一份也还在等——它留在单子上等你去改号，把它从计数里
     扣掉，页头说的数就和眼睛看到的不一样了。筛过之后说的是筛出来的那些，与「我的 skill」一致。 */
  const waitingSkills = new Set(visible.map((row) => row.namespace + '/' + row.skill)).size

  /**
   * **全选：每个 skill 勾它最新提交的那一份。**
   *
   * 一批里同一个 skill 只能发一份（见下），所以全选**不可能**等于"每一行都勾上"——它总要在几份
   * 里挑一份。挑最新那份，是因为草稿是不可变的行、后来的包含前面的内容；挑完**整行染色**，让人
   * 一眼看见它选的是哪一份，不想要就在行上取消、另点一份。
   *
   * **已经选过的不动。** 你手动挑了一份，再点全选，它不会被换掉——全选加的是"还没有选择"的那些
   * skill。它替没被决定的选，不替已经被决定的改。
   *
   * 号算不出来的（缺版本类型、基线是预发布）不进全选；有多少、为什么，写进动作条。
   */
  const newest = new Map<string, PendingDraft>()
  for (const row of ordered(visible)) {
    const key = row.namespace + '/' + row.skill
    if (!newest.has(key)) newest.set(key, row)
  }
  const candidates = [...newest.values()].filter(selectable)
  /* **「全选」这个词说的是每个 skill 都选了，不是每个候选行都勾上了。** 手动把某个 skill 换成
     第二新那份之后，它照样是"选过了"——若按候选行判，按钮会永远停在「全选」而点下去什么也不
     发生（那一个 skill 已经在 `taken` 里）。所以判据是**覆盖**：这一页里每个能算号的 skill，
     有没有一行被选中，不管选的是哪一份。 */
  const pickable = new Set(candidates.map((row) => row.namespace + '/' + row.skill))
  const covered = new Set(
    rows
      .filter((row) => picked.includes(row.id) && row.namespace === ns)
      .map((row) => row.namespace + '/' + row.skill),
  )
  const allPicked = pickable.size > 0 && [...pickable].every((key) => covered.has(key))
  /* **动作条那句只说两件真事**，而且是关于**已选的这几行**、不是关于整张单子：
     - 其中几个 skill 攒了多份草稿、而选中的正是最新那份（全选挑的就是它）；
     - 还有几份在等，但它们没有版本类型，所以勾不上。
     这两句只在"每个能算号的 skill 都选了"时出现——那正是全选做出来的样子；手挑两行时它们
     解释不了什么，写了反而像在替人总结一个没发生过的动作。 */
  const pickedRows = rows.filter((row) => picked.includes(row.id) && row.namespace === ns)
  const chosenNewest = new Set(
    pickedRows
      .filter((row) => multi(row) && newest.get(row.namespace + '/' + row.skill)?.id === row.id)
      .map((row) => row.namespace + '/' + row.skill),
  ).size
  /* 只数"缺类型"的那一种：基线是预发布的那一份不是缺类型，它要的是人直接给一个号——行上写着。 */
  const toType = visible.filter(needsType).length

  /* **这张单子的三种样子各有一个地址**：确认单、走到一半、结算。演示件的规矩是每一种点得出来的
     样子都能单独打开——否则它只能靠人手点出来（还要掐着秒），也拍不进图。 */
  const asked = params.get('publish')
  useEffect(() => {
    const opening = asked === 'confirm' || asked === 'running' || asked === 'done'
    if (!opening) return
    /* 地址里勾了哪些就发哪些；**长队列的那两格不写 14 个 id**——`&many=1` 时取这个命名空间里
       头 20 份能勾的，不然一个地址要写一百多个字符，还拍不进图。
       **一个 skill 只取一份**（每个 skill 最新提交的那一份），与全选同一条规矩：一批里同一个
       skill 发两份，服务端整批 `duplicate_target` 拒掉——演示用的批量不能是一条发不出去的批量。 */
    const pool = new Map<string, PendingDraft>()
    for (const row of ordered(visible)) {
      const key = row.namespace + '/' + row.skill
      if (!pool.has(key)) pool.set(key, row)
    }
    const chosen = picked.length > 0
      /* **与交互那条路同一条规矩：一批只装一个命名空间的行。** 地址里带着别的命名空间的 id
         时，那些行不该混进这张单子。 */
      ? source.filter((row) => picked.includes(row.id) && row.namespace === ns && selectable(row))
      : params.get('many') === '1'
        ? [...pool.values()].filter(selectable).slice(0, 20)
        : []
    if (chosen.length === 0) return
    const snapshot = asked === 'running'
      /* 走到六成——**这一格要能看见"正在走的那一条在中间"**，第 2 条看不出滚动。 */
      ? { at: Math.min(chosen.length - 1, Math.max(2, Math.round(chosen.length * 0.6))), finished: false }
      : asked === 'done' ? { at: chosen.length, finished: true } : undefined
    dialogs.openDanger(batchSheet(chosen, snapshot))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [asked])

  const setType = (id: string, type: ReleaseType) => {
    setRows((current) => current.map((row) => (row.id === id ? { ...row, release: type } : row)))
  }

  /** 选择写回地址：这一页的状态就在地址里，勾完刷新一次还是勾着的那几份。 */
  const keep = (next: string[]) => {
    setPicked(next)
    const copy = new URLSearchParams(params)
    copy.set('page', 'publish')
    if (next.length) copy.set('picked', next.join(','))
    else copy.delete('picked')
    replace('?' + copy.toString())
  }

  const toggle = (id: string) => {
    keep(picked.includes(id) ? picked.filter((item) => item !== id) : picked.concat(id))
  }

  /**
   * 全选 / 取消全选。**已经选过的 skill 不动**——全选补的是"还没有选择"的那些，不覆盖你自己的判断。
   */
  const selectAll = () => {
    if (allPicked) {
      keep([])
      return
    }
    const taken = new Set(
      picked
        .map((id) => rows.find((row) => row.id === id))
        .filter((row): row is PendingDraft => row !== undefined)
        .map((row) => row.namespace + '/' + row.skill),
    )
    const add = candidates
      .filter((row) => !taken.has(row.namespace + '/' + row.skill))
      .map((row) => row.id)
    keep(picked.concat(add))
  }

  const toggleFold = (key: string) => {
    setFolded((current) => current.includes(key)
      ? current.filter((item) => item !== key)
      : current.concat(key))
  }

  /**
   * **同一个 skill 在一批里只能选一份。** 两份草稿的号都从同一个线上版本算：选中两份，第二份要么
   * 撞号、要么只是把第一份刚发出去的又顶掉——而"这一批发完线上是哪一份"没有任何人有资格替人决定。
   * 所以第二份勾不上，行上说明原因；要发另一份，先取消这一批里的那一份。
   */
  const sameSkillPicked = (row: PendingDraft) => picked.some((id) => {
    const other = rows.find((item) => item.id === id)
    return other !== undefined && other.namespace === row.namespace &&
      other.skill === row.skill && other.id !== row.id
  })

  /* **一批只装一个命名空间的行**（页头的命名空间是单选，一次只发一个）：地址里带着别的
     命名空间的 id 时，那些行不该混进这张单子。 */
  const chosen = rows.filter((row) => picked.includes(row.id) && row.namespace === ns && selectable(row))

  /** 「上线所选」：先列清单，再一条条走，最后结算。**确认单逐条列名字与版本号**（ADR 0090 的可接受性条件）。 */
  const ask = (trigger: HTMLElement | null) => {
    dialogs.openDanger({
      ...batchSheet(chosen),
      returnFocus: trigger,
      onConfirm: () => {
        /* **演示件没有服务端，所以这是一个固定的故事**：不管刚才勾的是哪几份，结算之后一律回到
           「发过的那三份走了、撞号那份留下」那一屏（`afterRun` 里的 id 列表与那句横幅都是写死的）。
           控制台里这两样必须由**逐条的真实结果**驱动——固定的故事搬过去，就成了"发的是 A，报告
           里说的是 B"。 */
        const next = new URLSearchParams(params)
        next.set('page', 'publish')
        next.set('publish', 'partial')
        window.location.search = next.toString()
      },
    })
  }

  /**
   * 「会变成多少号」那一格。三种说法，三种控件：
   *
   * - 算不出来（线上没有号 / 没选类型 / 基线是预发布）→ 一句话，加（只有缺类型时才）行上的选档位；
   * - 首次上线 → `1.0.0` 写死，类型不参与（ADR 0091），**没有可改的东西**，所以是一个数字；
   * - 其余 → **数字本身就是那颗菜单的触发器**：点它就是重新选一档，号跟着重算。
   */
  const outcome = (row: PendingDraft) => {
    const preview = previewOf(row)
    if (preview === null) {
      /* 算不出号的三种原因，三句话——要人做的事一样（给一个号），但缺的东西不一样：
         **线上没有号**（历史行）／**没选类型**（选一档就完事）／**基线是预发布**（换一档没用）。 */
      /* 顺序照服务端的算号流程：预发布与"没有号"都排在"缺类型"之前——那是它们的真实处境，
         换了类型也照样算不出来。 */
      const why = row.live === ''
        ? '线上版本没有号，需指定版本号'
        : needsNumber(row) ? '基线为预发布版本，需指定版本号' : '未指定版本类型'
      return <span className="release-need">{why}</span>
    }
    if (row.live === null) {
      return <span className="release-preview">将以 <b>{preview}</b> 上线（首次上线）</span>
    }
    return (
      <span className="release-preview">
        将以{' '}
        <MenuSelect
          groups={TYPE_GROUPS}
          value={row.release ?? ''}
          initialOpen={typesFor === row.id}
          config={{
            ariaLabel: '改' + draftLabel(row) + '的版本类型',
            triggerText: preview,
            triggerClass: 'release-see',
            onPick: (next) => setType(row.id, next as ReleaseType),
          }}
        />
        {' '}上线（{row.release ? RELEASE_LABEL[row.release] : ''}）
      </span>
    )
  }

  /* **全文放 `title`**：版本名称是人写的、没有长度上限，这一格却定宽——放不下的收成省略号，
     悬停读全文（与名字那一格同一条规矩）。预发布（`@2.0.0-rc.2`）在第 148 里放得下，不会收。 */
  const liveChip = (live: string | null) => live === null ? (
    <span className="chip is-draft"><i className="dot"></i><span className="chip-text">从未上线</span></span>
  ) : live === '' ? (
    /* **上线过，只是那一版没有号**（v11 之前的历史行）。写 `—` 而不是「从未上线」——后者是
       另一件事，而且会让下面的预演跟着算成 `1.0.0`，那是把一个旧版本说成第一版。
       **也不成药丸**：药丸说的是"落在哪一档状态"，而这里恰恰是没有那一档——一颗只有破折号的
       空药丸，读起来像渲染坏了。 */
    <span className="publish-nil" title="线上版本没有版本号">—</span>
  ) : (
    <span className="chip is-live" title={'已上线 @' + live}>
      <i className="dot"></i><span className="chip-text">{'已上线 @' + live}</span>
    </span>
  )

  /** 勾选位。勾不上的原因各有一句话（缺类型 / 线上没号 / 基线预发布 / 同批已选另一份），不靠一个灰方块解释。 */
  const pickBox = (row: PendingDraft) => (
    <input
      type="checkbox"
      className="pick"
      aria-label={'选择 ' + row.skill + ' 的' + draftLabel(row)}
      checked={picked.includes(row.id)}
      disabled={!selectable(row) || sameSkillPicked(row)}
      onChange={() => toggle(row.id)}
    />
  )

  /** 这一份发不出去时，行上要说的话。它占「会变成多少号」那一格，不另起一行。 */
  const blocker = (row: PendingDraft) => {
    if (row.failure !== undefined) return <span className="release-failed">{row.failure}</span>
    if (sameSkillPicked(row)) return <span className="release-need">同批已选择该 skill 的另一份草稿</span>
    return null
  }

  /** 需要选类型的那一份，选择器就在它这一行上。选完立刻写回服务端，不是客户端状态。 */
  /* **只要缺的是号（预发布基线 / 线上没号），就不给选择器**：换一档也算不出号，给一颗菜单等于
     让人点一颗没用的东西（`needsType` 把这条判据收在一处，筛选与动作条的计数也读它）。 */
  const typePicker = (row: PendingDraft) => needsType(row) && (
    <label className="filter-select release-pick">
      <span className="visually-hidden">{row.skill + ' ' + draftLabel(row) + ' 的版本类型'}</span>
      <MenuSelect
        groups={TYPE_GROUPS}
        value=""
        config={{
          ariaLabel: row.skill + ' ' + draftLabel(row) + ' 的版本类型',
          triggerText: '选择类型',
          onPick: (next) => setType(row.id, next as ReleaseType),
        }}
      />
    </label>
  )

  /**
   * **树形表的规矩：作用列在一条线上，缩进只发生在名字那一格里。**
   *
   * | 勾选位／折角 | 名字 | 状态 | 提交于 | 将以… |
   *
   * 勾选位永远在最左、永远是同一列——每一行都能勾，勾的位置就该是同一个点。层级靠**名字那格里的
   * 缩进**表达（子行的草稿名缩进 27px，正好落在表头名字的下方），而不是靠把勾选位往右挪：往右挪
   * 一格，勾选位就不再是一列，页面上最该整齐的东西先乱了。
   *
   * 名字那一格装的是"这一行是谁"：只有一份草稿时是 `skill 「草稿」`，组里的一份只是 `「草稿」`，
   * 表头是 skill 名 + `N 份草稿`。
   */
  const cells = (row: PendingDraft) => (
    <>
      {blocker(row) ?? <>{outcome(row)}{typePicker(row)}</>}
    </>
  )

  /** 只有一份草稿的 skill：名字格里把 skill 与这一份写在一起，一行说完。 */
  const leaf = (row: PendingDraft) => (
    <li
      className={'publish-row is-start' + (picked.includes(row.id) ? ' is-picked' : '')}
      key={row.id}
      data-skill={row.namespace + '/' + row.skill}
      data-state={row.live === null ? 'draft' : 'live'}
      data-failed={row.failure !== undefined ? 'true' : undefined}
    >
      <span className="pub-cell is-pick">{pickBox(row)}</span>
      <span className="pub-cell is-label">
        <a className="skill-name" href="?page=skill" title={row.skill}>{row.skill}</a>
        <span className="publish-draft" title={row.draft}>{draftLabel(row)}</span>
      </span>
      <span className="pub-cell is-live">{liveChip(row.live)}</span>
      <span className="pub-cell is-when skill-updated">{'提交于 ' + row.submitted}</span>
      <span className="pub-cell is-ver">{cells(row)}</span>
    </li>
  )

  /** 组里的一份草稿：名字格缩进一格，**勾选位不动**。 */
  const child = (row: PendingDraft) => (
    <li
      className={'publish-row is-child' + (picked.includes(row.id) ? ' is-picked' : '')}
      key={row.id}
      data-skill={row.namespace + '/' + row.skill}
      data-state={row.live === null ? 'draft' : 'live'}
      data-failed={row.failure !== undefined ? 'true' : undefined}
    >
      <span className="pub-cell is-pick">{pickBox(row)}</span>
      <span className="pub-cell is-label">
        <span className="publish-draft" title={row.draft}>{draftLabel(row)}</span>
      </span>
      <span className="pub-cell is-live"></span>
      <span className="pub-cell is-when skill-updated">{'提交于 ' + row.submitted}</span>
      <span className="pub-cell is-ver">{cells(row)}</span>
    </li>
  )

  return (
    <section className={'page' + (picked.length > 0 ? ' is-selecting' : '')} id="page-publish" hidden={!active}>
      <header className="page-head">
        <h1>待上线</h1>
        <span className="count" data-when="ready" hidden={!ready}>
          {visible.length + ' 份草稿待上线（' + waitingSkills + ' 个 skill）'}
        </span>
        {/* **截断了就说出来**（PRD 验收 19）。静默少列，人会以为"剩下的没有草稿"——那句话说的
            正是上面那个数的边界，所以挨着它，而不进那颗药丸：药丸包的是一个数，不是一句交代。 */}
        <span className="count-note" data-when="ready" hidden={!ready || !truncated}>
          {'只列出了最近 500 个 skill'}
        </span>
        {/* **搜索与筛选在左、全选在右**，与「我的 skill」同一个页头骨架（工具条 + 一颗右侧按钮）。 */}
        <div className="page-actions">
          <div className="toolbar" data-when="ready" hidden={!ready}>
            {/* **命名空间排在最前**：它圈定的是这一整页，搜索与筛选都在它里面用。选项后面跟各自的
                份数——切过去之前就知道那边有多少在等。 */}
            <label className="filter-select is-scope">
              <span className="visually-hidden">命名空间</span>
              <MenuSelect
                groups={[{
                  options: namespaces.map((name) => ({
                    value: name,
                    label: name,
                    number: String(rows.filter((row) => row.namespace === name).length),
                  })),
                }]}
                value={ns}
                config={{
                  ariaLabel: '命名空间',
                  /* 一枚「一层一层装东西」的图标：这个框圈的是**整页**，不是列表的一列。
                     字也比右边那颗筛选重——它是标识（等宽、600），筛选那两颗是条件。 */
                  icon: (
                    <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                      <path d="M8 1.9 14 5.2 8 8.5 2 5.2Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
                      <path d="M2.5 8.6 8 11.6l5.5-3" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  ),
                  triggerClass: 'is-scope',
                  onPick: (next) => writeAddress({ ns: next }),
                }}
              />
            </label>
            <SearchBox
              id="publishQuery"
              value={query}
              placeholder="搜索 skill 或版本名称"
              label="搜索"
              onChange={(value) => writeAddress({ q: value })}
            />
            <label className="filter-select">
              <span className="visually-hidden">按草稿状态筛选</span>
              <MenuSelect
                groups={ONLY_GROUPS}
                value={only}
                config={{ ariaLabel: '按草稿状态筛选', onPick: (next) => writeAddress({ only: next }) }}
              />
            </label>
            {/* **全选不是"每一行都勾上"**（见 `candidates` 上面那段）：一个 skill 攒了几份草稿时，
                挑哪一份只有人知道。所以它勾的是"没什么可挑"的那些，剩下多少写在动作条里。
                它跟着工具条一起折行——窄屏上「只看」与它落在同一行，而不是各占一行。 */}
            {/* **颜色跟着它此刻要做的事走**：`全选` 是这一屏的主操作（与「我的 skill」右端那颗
                「新建 skill」同一个位置、同一档），所以是主色；勾满之后它变成 `取消全选`——那是一个
                撤销，主色该让给底下那条动作条里的「上线所选」。 */}
            <button
              type="button"
              className={'button' + (allPicked ? ' is-quiet' : '')}
              id="publishAll"
              data-when="ready"
              hidden={!ready}
              disabled={!allPicked && candidates.length === 0}
              title={candidates.length === 0 ? '没有可直接勾选的草稿' : undefined}
              onClick={selectAll}
            >
              {allPicked ? '取消全选' : '全选'}
            </button>
          </div>
        </div>
      </header>

      {/* 预演不是承诺：确认之后有人抢先上线了更高的版本，号会比单子上写的大。 */}
      <div className="banner is-note" id="publishChanged" hidden={!ran}>
        确认之后，changelog-writer 已被上线至 1.1.0，因此「补测试」落在 <b>1.2.0</b>；同组还留着的「修错别字」现在以 <b>1.2.1</b> 上线。
      </div>

      <p className="loading" data-when="loading" hidden={state !== 'loading'}>加载中…</p>
      <div className="banner" data-when="failed" hidden={state !== 'failed'}>无法加载待上线的草稿，请稍后重试。</div>

      <p className="empty" data-when="empty" id="publishEmpty" hidden={!empty}>
        没有待上线的草稿。提交新版本后，尚未上线的草稿会出现在这里。
      </p>

      <div className="list-body" data-when="ready" hidden={!ready}>
        {/* **顺序是服务端给的，页面不重排**（TD §接口）。这里那一下 `group()`／`ordered()` 是**替
            服务端排的**——演示件没有服务端，夹具又是按声明顺序堆的；搬到控制台时这一段直接吃响应
            里的顺序，别再排一次（两处各排一次，迟早排出两个顺序）。 */}
        <ul className="skills" id="publishList">
          {group(visible).map((entry) => {
            /* **只有一份的 skill 不分层**：给它配一个只能展开一次的父行，是拿高度换不到的信息。 */
            if (entry.drafts.length === 1) return leaf(entry.drafts[0])
            const open = !folded.includes(entry.key)
            return (
              <Fragment key={entry.key}>
                <li className="publish-row is-start is-head" data-skill={entry.key}>
                  <span className="pub-cell is-pick">
                    <button
                      type="button"
                      className="group-toggle"
                      aria-expanded={open ? 'true' : 'false'}
                      aria-label={(open ? '收起 ' : '展开 ') + entry.skill}
                      onClick={() => toggleFold(entry.key)}
                    >
                      <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                    </button>
                  </span>
                  <span className="pub-cell is-label">
                    <a className="skill-name" href="?page=skill" title={entry.skill}>{entry.skill}</a>
                    <span className="chip is-plain">
                      <span className="chip-text">{entry.drafts.length + ' 份草稿'}</span>
                    </span>
                  </span>
                  <span className="pub-cell is-live">{liveChip(entry.live)}</span>
                  <span className="pub-cell is-when"></span>
                  <span className="pub-cell is-ver"></span>
                </li>
                {open && entry.drafts.map(child)}
              </Fragment>
            )
          })}
        </ul>

        {/* 筛出来是空的，与"队列本来就没有草稿"是两回事：前者是换个词、把「只看」调回全部，
            后者是等有新草稿。两种情况给两句话，不能合成一句「没有草稿」。 */}
        <p className="empty" id="publishNone" hidden={!ready || visible.length > 0}>
          没有符合条件的草稿。请调整搜索词或筛选条件。
        </p>
      </div>

      {/* 动作条：没有选中项时不画，省一行高度。 */}
      <div className="select-bar" id="publishBar" hidden={pickedRows.length === 0}>
        <span className="select-count">{'已选 ' + pickedRows.length + ' 份'}</span>
        {/* **全选做了什么，在这儿说清。** 多份草稿的 skill 只勾了最新的一份，这句话就是那几行
            被选中的理由；发不了的另说一句。 */}
        {allPicked && (chosenNewest > 0 || toType > 0) && (
          <span className="select-note">
            {'· ' + [chosenNewest > 0 ? chosenNewest + ' 个 skill 取最新草稿' : '', toType > 0 ? toType + ' 份未指定版本类型' : '']
              .filter(Boolean).join('、')}
          </span>
        )}
        <span className="spacer"></span>
        <button type="button" className="button is-quiet" onClick={() => keep([])}>取消选择</button>
        <button
          type="button"
          className="button"
          id="publishGo"
          onClick={(event) => ask(event.currentTarget)}
        >
          上线所选
        </button>
      </div>
    </section>
  )
}
