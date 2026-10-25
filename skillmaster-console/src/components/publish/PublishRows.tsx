import { Fragment } from 'react'
import MenuSelect from '../MenuSelect'
import {
  RELEASE_LABEL,
  RELEASE_MEANING,
  draftLabel,
  group,
  isPrereleaseVersion,
  needsNumber,
  needsType,
  previewOf,
  selectable,
} from './publishModel'
import type { PendingDraft } from './publishModel'
import type { ReleaseType } from '../../api/types'

/**
 * The queue as a table: **one row per waiting draft**, five fixed columns.
 *
 * 勾选位／折角 ｜ 名字（skill ＋ 草稿）｜ 状态 ｜ 提交于 ｜ 将以…
 *
 * **Every row is its own grid**, so the column widths are fixed pixels rather than `max-content`: a
 * width that follows a row's content would make the row with a prerelease chip wider than its
 * neighbours, and the vertical lines are the only thing this page offers (design's comment on
 * `.publish-row`, 2026-10-25).
 *
 * **A skill with one draft is one row; a skill with several gets a heading.** Nested rows only where
 * there is something to nest — a heading over a single row doubles the height and adds no information.
 *
 * **The checkbox never moves.** Hierarchy is expressed by indenting the *name* cell, never by
 * shifting the checkbox right: every row can be ticked, so the tick has to be the one place on the
 * page that lines up.
 */

const TYPE_GROUPS = [
  {
    options: (['major', 'minor', 'patch'] as ReleaseType[]).map((type) => ({
      value: type,
      label: RELEASE_LABEL[type],
      meta: RELEASE_MEANING[type],
    })),
  },
]

export interface Props {
  /** Already filtered and in the server's order. */
  rows: PendingDraft[]
  picked: string[]
  folded: string[]
  onToggle: (id: string) => void
  onToggleFold: (key: string) => void
  onSetType: (row: PendingDraft, type: ReleaseType) => void
}

/** A skill's own page — where the draft's story continues once it is published. */
function skillHref(namespace: string, name: string): string {
  return `/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`
}

export default function PublishRows({
  rows,
  picked,
  folded,
  onToggle,
  onToggleFold,
  onSetType,
}: Props) {
  /**
   * **One draft per skill per batch** (PRD 验收 24). Two drafts of one skill compute from the same
   * live version, so publishing both would either collide or silently replace what the first just
   * put live — and "which one is live after this batch" is nobody's to decide for the publisher. The
   * second one cannot be ticked, and its row says why.
   */
  const pickedElsewhere = (row: PendingDraft): boolean =>
    picked.some((id) => id !== row.id && id.startsWith(`${row.namespace}/${row.skill}@`))

  const pickBox = (row: PendingDraft) => (
    <input
      type="checkbox"
      className="pick"
      aria-label={`选择 ${row.skill} 的${draftLabel(row)}`}
      checked={picked.includes(row.id)}
      disabled={!selectable(row) || pickedElsewhere(row)}
      onChange={() => onToggle(row.id)}
    />
  )

  /** 上线过但那一版没有号（v11 之前的历史行）。写 `—`，不写「从未上线」——那是另一件事。 */
  const liveChip = (live: string | null) => {
    if (live === null) {
      return (
        <span className="chip is-draft">
          <i className="dot"></i>
          <span className="chip-text">从未上线</span>
        </span>
      )
    }
    if (live === '') {
      // **也不成药丸**：药丸说的是"落在哪一档状态"，而这里恰恰没有那一档——一颗只有破折号的空
      // 药丸读起来像渲染坏了。
      return (
        <span className="publish-nil" title="线上版本没有版本号">
          —
        </span>
      )
    }
    return (
      <span className="chip is-live" title={`已上线 @${live}`}>
        <i className="dot"></i>
        <span className="chip-text">{`已上线 @${live}`}</span>
      </span>
    )
  }

  /**
   * 「会变成多少号」那一格。三种说法，三种控件：
   *
   * - 算不出来 → 一句话（缺号与缺类型是两句不同的话），缺类型时才有行上的选择器；
   * - 首次上线 → `1.0.0` 写死，类型不参与，所以只是一个数字；
   * - 其余 → **数字本身就是那颗菜单的触发器**：点它就是重新选一档，号跟着重算。
   */
  const outcome = (row: PendingDraft) => {
    const preview = previewOf(row)
    if (preview === null) {
      // 顺序照服务端的算号流程：预发布与"没有号"都排在"缺类型"之前——换了类型也照样算不出来。
      // **预发布与"不是一个版本"不是同一句话**：后者说成「基线为预发布版本」就是一句假话。
      const why = !needsNumber(row)
        ? '未指定版本类型'
        : row.live !== null && isPrereleaseVersion(row.live)
          ? '基线为预发布版本，需指定版本号'
          : '线上版本没有号，需指定版本号'
      return <span className="release-need">{why}</span>
    }
    if (row.live === null) {
      return (
        <span className="release-preview">
          将以 <b>{preview}</b> 上线（首次上线）
        </span>
      )
    }
    return (
      <span className="release-preview">
        将以{' '}
        <MenuSelect
          groups={TYPE_GROUPS}
          value={row.release ?? ''}
          ariaLabel={`改${draftLabel(row)}的版本类型`}
          triggerText={preview}
          triggerClass="release-see"
          onSelect={(next) => onSetType(row, next as ReleaseType)}
        />{' '}
        上线（{row.release === null ? '' : RELEASE_LABEL[row.release]}）
      </span>
    )
  }

  /** 需要选类型的那一份，选择器就在它这一行上。选完立刻写回服务端。 */
  const typePicker = (row: PendingDraft) =>
    needsType(row) ? (
      <label className="filter-select release-pick">
        <span className="visually-hidden">{`${row.skill} ${draftLabel(row)} 的版本类型`}</span>
        <MenuSelect
          groups={TYPE_GROUPS}
          value=""
          ariaLabel={`${row.skill} ${draftLabel(row)} 的版本类型`}
          triggerText="选择类型"
          onSelect={(next) => onSetType(row, next as ReleaseType)}
        />
      </label>
    ) : null

  /** 这一份发不出去时，行上要说的话。它占「会变成多少号」那一格，不另起一行。 */
  const blocker = (row: PendingDraft) => {
    if (row.failure !== undefined) {
      return <span className="release-failed">{row.failure}</span>
    }
    if (pickedElsewhere(row)) {
      return <span className="release-need">同批已选择该 skill 的另一份草稿</span>
    }
    return null
  }

  const cells = (row: PendingDraft) => blocker(row) ?? <>{outcome(row)}{typePicker(row)}</>

  /** 只有一份草稿的 skill：名字格里把 skill 与这一份写在一起，一行说完。 */
  const leaf = (row: PendingDraft) => (
    <li
      className={`publish-row is-start${picked.includes(row.id) ? ' is-picked' : ''}`}
      key={row.id}
      data-skill={`${row.namespace}/${row.skill}`}
      data-state={row.live === null ? 'draft' : 'live'}
      data-failed={row.failure === undefined ? undefined : 'true'}
    >
      <span className="pub-cell is-pick">{pickBox(row)}</span>
      <span className="pub-cell is-label">
        <a className="skill-name" href={skillHref(row.namespace, row.skill)} title={row.skill}>
          {row.skill}
        </a>
        <span className="publish-draft" title={row.draft}>
          {draftLabel(row)}
        </span>
      </span>
      <span className="pub-cell is-live">{liveChip(row.live)}</span>
      <span className="pub-cell is-when skill-updated">{`提交于 ${row.submitted}`}</span>
      <span className="pub-cell is-ver">{cells(row)}</span>
    </li>
  )

  /** 组里的一份草稿：名字格缩进一格，勾选位不动。 */
  const child = (row: PendingDraft) => (
    <li
      className={`publish-row is-child${picked.includes(row.id) ? ' is-picked' : ''}`}
      key={row.id}
      data-skill={`${row.namespace}/${row.skill}`}
      data-state={row.live === null ? 'draft' : 'live'}
      data-failed={row.failure === undefined ? undefined : 'true'}
    >
      <span className="pub-cell is-pick">{pickBox(row)}</span>
      <span className="pub-cell is-label">
        <span className="publish-draft" title={row.draft}>
          {draftLabel(row)}
        </span>
      </span>
      <span className="pub-cell is-live"></span>
      <span className="pub-cell is-when skill-updated">{`提交于 ${row.submitted}`}</span>
      <span className="pub-cell is-ver">{cells(row)}</span>
    </li>
  )

  return (
    <ul className="skills" id="publishList">
      {group(rows).map((entry) => {
        if (entry.drafts.length === 1) {
          return leaf(entry.drafts[0] as PendingDraft)
        }
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
                  onClick={() => onToggleFold(entry.key)}
                >
                  <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path
                      d="M6 4l4 4-4 4"
                      stroke="currentColor"
                      strokeWidth="1.6"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                </button>
              </span>
              <span className="pub-cell is-label">
                <a
                  className="skill-name"
                  href={skillHref(entry.namespace, entry.skill)}
                  title={entry.skill}
                >
                  {entry.skill}
                </a>
                {/* **这份单子上有几份**，不是这个 skill 一共有几份：筛选把某一份筛掉时，表头
                    说三份、底下只画两行，是同一张单子上一句自相矛盾的话。 */}
                <span className="chip is-plain">
                  <span className="chip-text">{`${entry.drafts.length} 份草稿`}</span>
                </span>
              </span>
              <span className="pub-cell is-live">{liveChip(entry.live)}</span>
              <span className="pub-cell is-when"></span>
              <span className="pub-cell is-ver"></span>
            </li>
            {open ? entry.drafts.map(child) : null}
          </Fragment>
        )
      })}
    </ul>
  )
}
