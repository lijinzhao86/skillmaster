import { useEffect, useState } from 'react'
import type { AuthoredSkill } from '../../api/types'
import { getSkillUsage, getSkillUsageMine } from '../../api/usage'
import type { SkillUsage, UsageMine } from '../../api/usage'
import { go, param, query, replaceAddress } from '../../address'
import UsageRange from './UsageRange'
import { rangeFromParams, rangeInstants, withRange } from '../../usageRange'
import type { UsageRange as UsageRangeValue } from '../../usageRange'
import { rungLabel } from '../../rungs'
import { formatDay, formatMonthDay, formatTime, fullStamp } from '../../time'
import { statusClass, statusText } from '../../version'
import type { VersionStatus } from '../../version'

/** The version's state as the chip vocabulary spells it. */
function chipOf(state: string): { text: string; className: string } | null {
  if (state === '') return null
  const status = state as VersionStatus
  return { text: statusText(status), className: 'chip ' + statusClass(status) }
}

/** The wire carries the rung key; the column is the interface's word (维护, 所有者, …). */
function roleLabel(role: string): string {
  return rungLabel(role as Parameters<typeof rungLabel>[0])
}

/** `YYYY-MM-DD` for a day or an instant; a date-only value is already one, never re-zoned. */
function dayField(value: string): string {
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value
  return formatDay(value).replaceAll('/', '-')
}

function clockOf(at: string): string {
  return formatTime(at).split(' ')[1] ?? ''
}

/** `MM-DD HH:MM`, the same shape the usage log uses. */
function stamp(at: string): string {
  const [date, time] = formatTime(at).split(' ')
  const parts = (date ?? '').split('/')
  return parts.length === 3 ? `${parts[1]}-${parts[2]} ${time ?? ''}` : formatTime(at)
}

/**
 * skill 详情的「使用」页签（v58）。
 *
 * 所有者：两个视图——全部使用（聚合 + 使用者表）/ 我的使用；公开 skill 只给聚合。
 * 非所有者：只有「我的使用」，三张卡 + 按版本的表，不解释别人看不到什么。
 * 管理档与协作者看到同一版：管人，不管行为。
 */
export default function UsagePane({
  skill,
  namespace,
  name,
}: {
  skill: AuthoredSkill
  namespace: string
  name: string
}) {
  const owner = skill.rung === 'owner'
  const [scope, setScope] = useState<'all' | 'mine'>(
    owner && param('use') !== 'mine' ? 'all' : 'mine',
  )
  /* **A refetch keeps the last answer on screen.** Only the first read of a view draws
     「加载中…」; changing the range swaps the numbers in place, the same rule 使用记录 follows. */
  const [failed, setFailed] = useState(false)
  const [usage, setUsage] = useState<SkillUsage | null>(null)
  const [mine, setMine] = useState<UsageMine | null>(null)

  const [range, setRangeState] = useState<UsageRangeValue>(() => rangeFromParams(query()))
  const { since, until } = rangeInstants(range)

  useEffect(() => {
    let live = true
    setFailed(false)
    const window = { since, until }
    const request =
      owner && scope === 'all'
        ? getSkillUsage(namespace, name, window).then((result) => {
            if (!live) return
            if (result.ok) {
              setUsage(result.data)
            } else {
              setFailed(true)
            }
          })
        : getSkillUsageMine(namespace, name, window).then((result) => {
            if (!live) return
            if (result.ok) {
              setMine(result.data)
            } else {
              setFailed(true)
            }
          })
    void request
    return () => {
      live = false
    }
  }, [owner, scope, namespace, name, since, until])

  const allScope = owner && scope === 'all'
  const loading = (allScope ? usage === null : mine === null) && !failed

  function setRange(next: UsageRangeValue): void {
    setRangeState(next)
    const params = query()
    withRange(params, next)
    replaceAddress(params.toString() === '' ? window.location.pathname : `?${params}`)
  }

  function choose(next: 'all' | 'mine'): void {
    setScope(next)
    const params = query()
    if (next === 'mine') params.set('use', 'mine')
    else params.delete('use')
    const search = params.toString()
    go(window.location.pathname + (search === '' ? '' : `?${search}`))
  }

  return (
    <div className="tab-panel" data-tab="usage" id="skillUsage">
      {/* 视图开关与时间范围同一行：都决定「这一屏看哪一段、哪一份」。非所有者没有开关，
          时间按钮靠右，位置与有开关时一致。 */}
      <div className="usage-tools">
        {owner && (
          <div className="usage-scope" role="group" aria-label="使用范围">
            <button
              type="button"
              className={scope === 'all' ? 'is-current' : undefined}
              aria-pressed={scope === 'all'}
              onClick={() => choose('all')}
            >
              全部使用
            </button>
            <button
              type="button"
              className={scope === 'mine' ? 'is-current' : undefined}
              aria-pressed={scope === 'mine'}
              onClick={() => choose('mine')}
            >
              我的使用
            </button>
          </div>
        )}
        <span className="usage-range">
          <UsageRange
            id="skillUsageRange"
            label="时间范围"
            range={range}
            onChange={setRange}
          />
        </span>
      </div>

      <p className="loading" hidden={!loading}>
        加载中…
      </p>
      <div className="banner" hidden={!failed}>
        使用情况暂时无法加载，请稍后重试。
      </div>

      {allScope && usage !== null && <OwnerAll usage={usage} />}
      {!allScope && mine !== null && <Mine mine={mine} />}
    </div>
  )
}

function OwnerAll({ usage }: { usage: SkillUsage }) {
  return (
    <>
      <div className="stats">
        <div className="stat">
          <span className="stat-head">调用次数</span>
          <span className="stat-body">
            <span className="stat-number">{usage.totals.calls}</span>
          </span>
        </div>
        <div className="stat">
          <span className="stat-head">使用账号数</span>
          <span className="stat-body">
            <span className="stat-number" style={{ fontSize: 18 }}>
              {usage.totals.accounts}
            </span>
          </span>
        </div>
        <div className="stat">
          <span className="stat-head">累计入库</span>
          <span className="stat-body">
            <span className="stat-number">{usage.totals.joins}</span>
          </span>
        </div>
        <div className="stat">
          <span className="stat-head">最近使用</span>
          <span className="stat-body">
            <span className="stat-number" style={{ fontSize: 18 }}>
              {usage.totals.last_used === null ? '—' : formatMonthDay(usage.totals.last_used)}
            </span>
            {usage.totals.last_used !== null && (
              <span className="stat-unit">{clockOf(usage.totals.last_used)}</span>
            )}
          </span>
        </div>
      </div>

      {usage.public ? (
        <p className="empty" id="skillUsagePublic">
          公开 skill 仅提供聚合，不按协作者统计。页头的累计调用是平台信号，
          和这里的区间数不是同一套口径；近 30 天账号分桶在 <code>skmr skill show</code>。
        </p>
      ) : (
        <div className="block">
          <h2>使用者</h2>
          <p className="usage-note" style={{ marginTop: 8, marginBottom: 12 }}>
            调用次数看采用，提交版本看贡献；不展示个人操作明细。三列都按上方区间算。
          </p>
          <table className="usage-table" id="skillUsageUsers">
            <thead>
              <tr>
                <th>使用者</th>
                <th>档位</th>
                <th>调用次数</th>
                <th>提交版本</th>
                <th>最近活跃</th>
              </tr>
            </thead>
            <tbody>
              {usage.users.map((user) => (
                <tr key={user.who}>
                  <td className="usage-handle">{user.who}</td>
                  <td>{roleLabel(user.role)}</td>
                  <td>{user.invoke}</td>
                  <td>{user.submitted}</td>
                  <td className="usage-last">
                    {user.last === null ? '—' : dayField(user.last)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function Mine({ mine }: { mine: UsageMine }) {
  return (
    <>
      <div className="stats is-three">
        <div className="stat">
          <span className="stat-head">调用次数</span>
          <span className="stat-body">
            <span className="stat-number">{mine.calls}</span>
          </span>
        </div>
        <div className="stat">
          <span className="stat-head">使用过的版本</span>
          <span className="stat-body">
            <span className="stat-number">{mine.versions}</span>
          </span>
        </div>
        <div className="stat">
          <span className="stat-head">最近使用</span>
          <span className="stat-body">
            <span className="stat-number" style={{ fontSize: 18 }}>
              {mine.latest === null ? '—' : formatMonthDay(mine.latest)}
            </span>
            {mine.latest !== null && (
              <span className="stat-unit">{clockOf(mine.latest)}</span>
            )}
          </span>
        </div>
      </div>

      {mine.by_version.length > 0 && (
        <div className="block">
          <h2>按版本</h2>
          <table className="usage-table" id="skillUsageMine">
            <thead>
              <tr>
                <th>版本号</th>
                <th>调用次数</th>
                <th>最近使用</th>
              </tr>
            </thead>
            <tbody>
              {mine.by_version.map((row) => {
                const chip = chipOf(row.state)
                return (
                  <tr key={row.version}>
                    <td>
                      <span className="usage-handle">@{row.version}</span>
                      {chip !== null && (
                        <span className={chip.className}>
                          <i className="dot" />
                          <span className="chip-text">{chip.text}</span>
                        </span>
                      )}
                    </td>
                    <td>{row.calls}</td>
                    <td className="usage-last" title={fullStamp(row.last)}>
                      {row.last === null ? '—' : stamp(row.last)}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
