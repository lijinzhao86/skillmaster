import type { UsageStats } from '../../api/usage'
import { formatMonthDay, formatTime } from '../../time'

function clock(at: string | null): string {
  if (at === null) return ''
  const formatted = formatTime(at)
  return formatted.split(' ')[1] ?? ''
}

function shortStamp(at: string): string {
  const [date, time] = formatTime(at).split(' ')
  const parts = (date ?? '').split('/')
  return parts.length === 3 ? `${parts[1]}-${parts[2]} ${time ?? ''}` : formatTime(at)
}

/** 统计：用了多少，搜索有没有把人带到 skill（v58）。数由明细现算，页面不再存一份。 */
export default function UsageStatsCards({ stats }: { stats: UsageStats }) {
  return (
    <>
      <div className="stats" id="usageStats">
        <div className="stat">
          <span className="stat-head">调用次数</span>
          <span className="stat-body">
            <span className="stat-number">{stats.calls}</span>
          </span>
        </div>
        <div className="stat">
          <span className="stat-head">搜索次数</span>
          <span className="stat-body">
            <span className="stat-number">{stats.searches}</span>
          </span>
        </div>
        <div className="stat">
          <span className="stat-head">使用过的 skill</span>
          <span className="stat-body">
            <span className="stat-number">{stats.skills}</span>
          </span>
        </div>
        <div className="stat">
          <span className="stat-head">最近使用</span>
          <span className="stat-body">
            <span className="stat-number" style={{ fontSize: 18 }}>
              {stats.latest === null ? '—' : formatMonthDay(stats.latest)}
            </span>
            {stats.latest !== null && (
              <span className="stat-unit">{clock(stats.latest)}</span>
            )}
          </span>
        </div>
      </div>

      {stats.searches > 0 && (
        <p className="usage-note">
          {stats.searches} 次搜索中，{stats.converted} 次最终调用了结果，{stats.missed} 次未调用。
        </p>
      )}

      {stats.top.length > 0 && (
        <div className="block" style={{ marginTop: 8 }}>
          <h2>按调用次数</h2>
          <p className="usage-note">仅统计调用；搜索后未调用的不计入。</p>
          <table className="usage-table usage-top">
            <thead>
              <tr>
                <th>名字</th>
                <th>调用</th>
                <th className="is-bar">
                  <span className="visually-hidden">相对使用量</span>
                </th>
                <th>最近使用</th>
              </tr>
            </thead>
            <tbody>
              {stats.top.map((item) => (
                <tr key={item.skill}>
                  <td>
                    <a className="notice-skill" href={`/skills/${item.skill}`}>
                      {item.skill}
                    </a>
                  </td>
                  <td>{item.calls} 次</td>
                  <td className="is-bar">
                    <span
                      className="usage-bar-track"
                      title={`${item.calls} 次，最多的一个是 ${stats.top[0]?.calls ?? 0} 次`}
                    >
                      <span
                        className="usage-bar-fill"
                        style={{
                          width: `${Math.round((item.calls / (stats.top[0]?.calls || 1)) * 100)}%`,
                        }}
                      />
                    </span>
                  </td>
                  <td className="usage-last">{shortStamp(item.last)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
