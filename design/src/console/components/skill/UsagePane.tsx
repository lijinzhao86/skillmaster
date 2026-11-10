/**
 * 一个 skill 的「使用」页签（v58）。
 *
 * **同一条事实按关系分读法**（ADR 0179）：
 * - 所有者有**两个视图**（2026-11-10 用户定）：「全部使用」＝聚合（调用 / 账号数 / 入库）＋
 *   共享私有 skill 的协作者统计；「我的使用」＝所有者自己在这条 skill 上的调用。所有者也是
 *   一个使用者，不能因为它是所有者就看不到自己的记录。
 * - 协作者 / 库内用户：只有「我的使用」这一个视图，没有切换。
 * - 公开 skill 的所有者：「全部使用」只有聚合，不按协作者统计；「我的使用」照常。
 */

import { COLLABORATORS, MY_CALLS_FOR_SKILL, OWNER_USAGE } from '../../fixtures/usage'
import { SKILL_OWNER, versionPinOf } from '../../fixtures/skill'
import type { Version } from '../../fixtures/skill'
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
  usage: { bucket: string; calls?: string; lastUsed?: string; joins?: number }
  /** 这一页的版本（不分档位）：按版本那一列要知道「我调的那一版现在是什么状态」。 */
  versions: Version[]
  /** 所有者的两个视图；非所有者忽略它，永远只有「我的」。 */
  scope: 'all' | 'mine'
  onScope: (scope: 'all' | 'mine') => void
}

export default function UsagePane({
  hidden, tier, visibility, usage, versions, scope, onScope,
}: UsagePaneProps) {
  const owner = tier === 'owner'
  /* **管理档不因「管理」而看到别人的用量**（2026-11-10 用户定）：它能管协作者名单与档位，
     按人的用量仍只给所有者。两件事分开——管人，不管行为。页面上不再写这句解释：管理档看到的
     与协作者同一版，差别只在接口（协作者统计对他返回 404）。 */
  const publicSkill = visibility.startsWith('public')

  /* 我在这个 skill 上的调用，按版本归一次。 */
  const mine = MY_CALLS_FOR_SKILL
  const byVersion = new Map<string, { calls: number; last: string }>()
  for (const call of mine) {
    const counted = byVersion.get(call.version)
    if (counted === undefined) byVersion.set(call.version, { calls: 1, last: call.at })
    else {
      counted.calls += 1
      if (call.at > counted.last) counted.last = call.at
    }
  }
  const latest = mine.reduce<string | null>(
    (max, call) => (max === null || call.at > max ? call.at : max),
    null,
  )
  /* 版本号 → 那一版，给「我调的是线上还是历史版本」这一问。找不到就不画章，不猜。 */
  const versionByPin = new Map(versions.map((version) => [versionPinOf(version), version]))

  return (
    <section className="tab-panel" data-tab="usage" id="skillUsage" hidden={hidden}>
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

      {owner && scope === 'all' && (
        <>
          <div className="stats">
            <div className="stat">
              <span className="stat-head">调用次数</span>
              <span className="stat-body"><span className="stat-number">{usage.calls ?? '—'}</span></span>
            </div>
            <div className="stat">
              {/* 分桶的值本身就是一句话（`100+` / `少于 10 个账号`），不再另挂单位——挂上会读成
                  「少于 10 个账号 个账号」。18px 与「最近使用」的日期同档。 */}
              <span className="stat-head">使用账号数</span>
              <span className="stat-body"><span className="stat-number" style={{ fontSize: 18 }}>{usage.bucket}</span></span>
            </div>
            <div className="stat">
              <span className="stat-head">入库人数</span>
              <span className="stat-body"><span className="stat-number">{usage.joins ?? 0}</span></span>
            </div>
            <div className="stat">
              <span className="stat-head">最近使用</span>
              <span className="stat-body"><span className="stat-number" style={{ fontSize: 18 }}>{usage.lastUsed ?? '—'}</span></span>
            </div>
          </div>

          {publicSkill ? (
            <p className="empty" id="skillUsagePublic">
              公开 skill 仅提供聚合，不按协作者统计。
            </p>
          ) : (
            <div className="block">
              {/* **表里第一行是所有者自己**（2026-11-10 用户定）：所有者也用这条 skill，而且
                  「我比团队用得多还是少」正是他会问的。所有者不是协作者（协作者是拿到档位的人），
                  所以表名从「协作者」改成「使用者」，他那一行标「你 · 所有者」。 */}
              <h2>使用者</h2>
              {/* **采用 + 贡献，不是三种动作的次数**（2026-11-10 用户定）：调用看用得多不多，
                  提交版本看有没有在维护；档位解释「提交 0 次」是没权限还是没贡献。读取与
                  文件级写入不指向任何管理动作，不进这张表（原始事件仍全量落库）。 */}
              <p className="usage-note" style={{ marginTop: 8, marginBottom: 12 }}>
                调用次数看采用，提交版本看贡献；不展示个人操作明细。
              </p>
              <table className="usage-table" id="skillUsageUsers">
                <thead>
                  <tr><th>使用者</th><th>档位</th><th>调用次数</th><th>提交版本</th><th>最近活跃</th></tr>
                </thead>
                <tbody>
                  {/* 所有者那一行的「所有者」写在档位列，与「设置 › 协作者」同一个事实、同一套写法。 */}
                  <tr className="is-you">
                    <td className="usage-handle">{SKILL_OWNER}</td>
                    <td>{OWNER_USAGE.role}</td>
                    <td>{OWNER_USAGE.invoke}</td>
                    <td>{OWNER_USAGE.versions}</td>
                    <td className="usage-last">{OWNER_USAGE.last}</td>
                  </tr>
                  {COLLABORATORS.map((row) => (
                    <tr key={row.handle}>
                      <td className="usage-handle">{row.handle}</td>
                      <td>{row.role}</td>
                      <td>{row.invoke}</td>
                      <td>{row.versions}</td>
                      <td className="usage-last">{row.last}</td>
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
                <span className="stat-number" style={{ fontSize: 18 }}>{latest === null ? '—' : when(latest).day}</span>
                {latest !== null && <span className="stat-unit">{when(latest).time}</span>}
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
