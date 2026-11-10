/**
 * 一个 skill 的「使用」页签（v58；v60 加时间筛选）。
 *
 * **同一条事实按关系分读法**（ADR 0179）：
 * - 所有者有**两个视图**（2026-11-10 用户定）：「全部使用」＝聚合（调用 / 账号数 / 入库）＋
 *   共享私有 skill 的协作者统计；「我的使用」＝所有者自己在这条 skill 上的调用。所有者也是
 *   一个使用者，不能因为它是所有者就看不到自己的记录。
 * - 协作者 / 库内用户：只有「我的使用」这一个视图，没有切换。
 * - 公开 skill 的所有者：「全部使用」只有聚合，不按协作者统计；「我的使用」照常。
 *
 * **这一页是区间视图**（v60，ADR 0182）：数卡、使用者表与按版本表都按上方所选区间现算。
 * 「累计入库」不随区间变——入库是当前名单，不是区间内的事件。公开 skill 的调用与账号仍分桶；
 * 结果行与页头上的平台信号（累计调用）是另一份口径，不在这里冒充；
 * 近 30 天账号分桶只在 `skmr skill show`。
 */

import {
  MY_CALLS_FOR_SKILL,
  PUBLIC_CALLS,
  SKILL_CALLS,
  SKILL_GRANTS,
  SKILL_JOINS,
  SKILL_SUBMISSIONS,
  accountsLabel,
  callsLabel,
  inRange,
} from '../../fixtures/usage'
import type { UsageRange } from '../../fixtures/usage'
import { SKILL_OWNER, versionPinOf } from '../../fixtures/skill'
import type { Version } from '../../fixtures/skill'
import UsageRangePicker from '../UsageRange'
import { StateChip } from './parts'

/** `2026-11-10 09:41` → `11 月 10 日` 与 `09:41`。 */
function when(at: string) {
  const [date, time] = at.split(' ')
  const [, month, day] = date.split('-')
  return { day: `${Number(month)} 月 ${Number(day)} 日`, time }
}

export interface UsagePaneProps {
  hidden: boolean
  tier: string
  visibility: string
  /** 平台信号与累计入库。区间视图只读 `joins`：调用与账号从明细事件现算。 */
  usage: { bucket: string; calls?: string; lastUsed?: string; joins?: number }
  /** 这一页的版本（不分档位）：按版本那一列要知道「我调的那一版现在是什么状态」。 */
  versions: Version[]
  /** 所有者的两个视图；非所有者忽略它，永远只有「我的」。 */
  scope: 'all' | 'mine'
  onScope: (scope: 'all' | 'mine') => void
  /** v60：区间。与使用记录页同一组预设与自定义起止。 */
  range: UsageRange
  onRange: (range: UsageRange) => void
  /** 演示件深链：菜单 / 弹窗开着的那一屏。 */
  rangeOpen?: boolean
  rangeAsk?: boolean
}

export default function UsagePane({
  hidden, tier, visibility, usage, versions, scope, onScope, range, onRange,
  rangeOpen = false, rangeAsk = false,
}: UsagePaneProps) {
  const owner = tier === 'owner'
  /* **管理档不因「管理」而看到别人的用量**（2026-11-10 用户定）：它能管协作者名单与档位，
     按人的用量仍只给所有者。两件事分开——管人，不管行为。页面上不再写这句解释：管理档看到的
     与协作者同一版，差别只在接口（协作者统计对他返回 404）。 */
  const publicSkill = visibility.startsWith('public')

  /* 区间内的调用：私有只看所有者与协作者；公开再加上公域读者（他们不进使用者表）。 */
  const calls = (publicSkill ? SKILL_CALLS.concat(PUBLIC_CALLS) : SKILL_CALLS)
    .filter((call) => inRange(call.at, range))
  const accounts = new Set(calls.map((call) => call.who)).size
  const latest = calls.reduce<string | null>(
    (max, call) => (max === null || call.at > max ? call.at : max),
    null,
  )

  /* 使用者表：档位是当前值；调用、提交版本与最近活跃按区间。第一行是所有者自己。 */
  const people = [{ handle: SKILL_OWNER, role: '所有者' }].concat(
    SKILL_GRANTS.map((grant) => ({ handle: grant.handle as string, role: grant.role as string })),
  )
  const userRows = people.map((person) => {
    const mine = calls.filter((call) => call.who === person.handle)
    const submitted = SKILL_SUBMISSIONS.filter(
      (submission) => submission.who === person.handle && inRange(submission.at, range),
    )
    const active = mine.map((call) => call.at).concat(submitted.map((submission) => submission.at))
      .reduce<string | null>((max, at) => (max === null || at > max ? at : max), null)
    return { ...person, invoke: mine.length, versions: submitted.length, last: active }
  })

  /* 「我的使用」：所有者读自己在 SKILL_CALLS 里的那些调用；其他关系读夹具里的个人记录。
     所有者那一行在使用者表里与这里同源，两个数不会对不上。 */
  const mine = owner
    ? calls.filter((call) => call.who === SKILL_OWNER)
    : MY_CALLS_FOR_SKILL.filter((call) => inRange(call.at, range))
  const byVersion = new Map<string, { calls: number; last: string }>()
  for (const call of mine) {
    const counted = byVersion.get(call.version)
    if (counted === undefined) byVersion.set(call.version, { calls: 1, last: call.at })
    else {
      counted.calls += 1
      if (call.at > counted.last) counted.last = call.at
    }
  }
  const mineLatest = mine.reduce<string | null>(
    (max, call) => (max === null || call.at > max ? call.at : max),
    null,
  )
  /* 版本号 → 那一版，给「我调的是线上还是历史版本」这一问。找不到就不画章，不猜。 */
  const versionByPin = new Map(versions.map((version) => [versionPinOf(version), version]))

  return (
    <section className="tab-panel" data-tab="usage" id="skillUsage" hidden={hidden}>
      {/* **视图开关与时间范围同一行**：都决定「这一屏看哪一段、哪一份」。非所有者没有开关，
          时间按钮靠右，位置与有开关时一致。 */}
      <div className="usage-tools">
        {owner && (
          <div className="usage-scope" role="group" aria-label="使用范围">
            <button
              type="button"
              className={scope === 'all' ? 'is-current' : undefined}
              aria-pressed={scope === 'all'}
              onClick={() => onScope('all')}
            >
              全部使用
            </button>
            <button
              type="button"
              className={scope === 'mine' ? 'is-current' : undefined}
              aria-pressed={scope === 'mine'}
              onClick={() => onScope('mine')}
            >
              我的使用
            </button>
          </div>
        )}
        <span className="usage-range">
          <UsageRangePicker
            id="skillUsageRange"
            label="时间范围"
            range={range}
            initialOpen={rangeOpen}
            initialAsk={rangeAsk}
            onChange={onRange}
          />
        </span>
      </div>

      {owner && scope === 'all' && (
        <>
          <div className="stats">
            <div className="stat">
              <span className="stat-head">调用次数</span>
              <span className="stat-body">
                {/* 公开 skill 只给分桶；私有给精确数。 */}
                <span className="stat-number">
                  {publicSkill ? callsLabel(calls.length) : calls.length}
                </span>
              </span>
            </div>
            <div className="stat">
              {/* 分桶的值本身就是一句话（`100+` / `少于 10 个账号`），不再另挂单位——挂上会读成
                  「少于 10 个账号 个账号」。18px 与「最近使用」的日期同档。 */}
              <span className="stat-head">使用账号数</span>
              <span className="stat-body">
                <span className="stat-number" style={{ fontSize: 18 }}>
                  {publicSkill ? accountsLabel(accounts) : accounts}
                </span>
              </span>
            </div>
            <div className="stat">
              {/* **累计入库不随区间变**（v60）：入库回答「现在有多少人在库里」，不是区间事件；
                  标签把口径写清，免得和上面两张区间卡读成同一段时间。 */}
              <span className="stat-head">累计入库</span>
              <span className="stat-body">
                <span className="stat-number">{usage.joins ?? SKILL_JOINS}</span>
              </span>
            </div>
            <div className="stat">
              <span className="stat-head">最近使用</span>
              <span className="stat-body">
                <span className="stat-number" style={{ fontSize: 18 }}>
                  {latest === null ? '—' : when(latest).day}
                </span>
                {latest !== null && <span className="stat-unit">{when(latest).time}</span>}
              </span>
            </div>
          </div>

          {publicSkill ? (
            <p className="empty" id="skillUsagePublic">
              公开 skill 仅提供聚合，不按协作者统计。页头的累计调用是平台信号，
              和这里的区间数不是同一套口径；近 30 天账号分桶在 <code>skmr skill show</code>。
            </p>
          ) : (
            <div className="block">
              {/* **表里第一行是所有者自己**（2026-11-10 用户定）：所有者也用这条 skill，而且
                  「我比团队用得多还是少」正是他会问的。所有者不是协作者（协作者是拿到档位的人），
                  所以表名从「协作者」改成「使用者」，他那一行标「所有者」。 */}
              <h2>使用者</h2>
              {/* **采用 + 贡献，不是三种动作的次数**（2026-11-10 用户定）：调用看用得多不多，
                  提交版本看有没有在维护；档位解释「提交 0 次」是没权限还是没贡献。读取与
                  文件级写入不指向任何管理动作，不进这张表（原始事件仍全量落库）。 */}
              <p className="usage-note" style={{ marginTop: 8, marginBottom: 12 }}>
                调用次数看采用，提交版本看贡献；不展示个人操作明细。三列都按上方区间算。
              </p>
              <table className="usage-table" id="skillUsageUsers">
                <thead>
                  <tr><th>使用者</th><th>档位</th><th>调用次数</th><th>提交版本</th><th>最近活跃</th></tr>
                </thead>
                <tbody>
                  {userRows.map((row) => (
                    <tr className={row.role === '所有者' ? 'is-you' : undefined} key={row.handle}>
                      <td className="usage-handle">{row.handle}</td>
                      <td>{row.role}</td>
                      <td>{row.invoke}</td>
                      <td>{row.versions}</td>
                      <td className="usage-last">{row.last === null ? '—' : row.last.slice(0, 10)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {(!owner || scope === 'mine') && (
        <>
          {/* **这条 skill 上的「我」：三个数 + 按版本一张表。** 三行同一个 skill 的清单只是在
              重复页头已经写着的那件事；换成「我调过哪几版、各几次、最近一次什么时候」，一次
              读完。逐次的时间戳在「使用记录」页，这里不重复。 */}
          <div className="stats is-three">
            <div className="stat">
              <span className="stat-head">调用次数</span>
              <span className="stat-body"><span className="stat-number">{mine.length}</span></span>
            </div>
            <div className="stat">
              <span className="stat-head">使用过的版本</span>
              <span className="stat-body"><span className="stat-number">{byVersion.size}</span></span>
            </div>
            <div className="stat">
              <span className="stat-head">最近使用</span>
              <span className="stat-body">
                <span className="stat-number" style={{ fontSize: 18 }}>{mineLatest === null ? '—' : when(mineLatest).day}</span>
                {mineLatest !== null && <span className="stat-unit">{when(mineLatest).time}</span>}
              </span>
            </div>
          </div>

          {/* **不再解释「别人看不到」**（2026-11-10 用户定）：这一页只说自己的事，讲不了的部分
              不必写成一句话；「按版本」表是最后一块，`.block:last-child` 也不画页尾那条线。 */}
          {byVersion.size > 0 && (
            <div className="block">
              <h2>按版本</h2>
              <table className="usage-table" id="skillUsageMine">
                <thead>
                  <tr><th>版本号</th><th>调用次数</th><th>最近使用</th></tr>
                </thead>
                <tbody>
                  {[...byVersion.entries()]
                    .sort(([, a], [, b]) => (a.last < b.last ? 1 : a.last > b.last ? -1 : 0))
                    .map(([version, count]) => (
                    <tr key={version}>
                      <td>
                        <span className="usage-handle">@{version}</span>
                        {versionByPin.get(version) !== undefined && <StateChip version={versionByPin.get(version)!} />}
                      </td>
                      <td>{count.calls}</td>
                      <td className="usage-last" title={count.last}>{count.last.slice(5)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  )
}
