/**
 * 设置 › 服务凭据：这个 skill 用哪把 key、给谁用。
 *
 * **与总列表的分工。** 权限 › 服务凭据 管的是「我一共有哪些凭据」；这一页管的是「这一个
 * skill 用了哪些」，所以它多了一列**来源**——自己的还是别人共享来的，决定这一行能不能共享、
 * 能不能解除绑定。
 *
 * **共享按「凭据 + skill」算**（2026-11-08 定）：在这里共享，只在这一个 skill 上生效；同一条
 * 凭据给另一个 skill 用，要在那个 skill 上再共享一次。
 */

import { useState } from 'react'
import { dialogs } from '../dialogs/store'
import type { Grant } from '../../fixtures/skill'

interface BoundRow {
  id: string
  name: string
  env: string
  /** `you` = 自己绑的；否则是共享给他的那个人的名字。 */
  source: 'you' | string
  /** 已经共享给谁——打开页面就要看得见，否则撤销无从下手。 */
  shared: string[]
  used: number
  limit: number
}

/** ⚠️ 夹具：真实产品里是「这个 skill 的绑定」。 */
const SEED: BoundRow[] = [
  {
    id: 'sec_91d2', name: 'PDF 服务 key', env: 'PDF_API_KEY', source: 'you',
    shared: ['yuhan'], used: 4, limit: 500,
  },
  {
    id: 'sec_5c07', name: '水印模板 key', env: 'WATERMARK_KEY', source: 'hexu',
    shared: [], used: 0, limit: 500,
  },
]

/** 我已经有、但还没绑到这个 skill 上的凭据。 */
const CANDIDATES = ['知乎 cookie', '搜索 key']

export interface SecretsPaneProps {
  hidden: boolean
  enabled: boolean
  grants: Grant[]
}

export default function SecretsPane({ hidden, enabled, grants }: SecretsPaneProps) {
  const [rows, setRows] = useState<BoundRow[]>(SEED)
  const [sharing, setSharing] = useState<string | null>(null)
  const [who, setWho] = useState('')
  const [pick, setPick] = useState('')
  const [env, setEnv] = useState('')

  /* 共享只能给这个 skill 的已接受协作者；所有者本人不算「共享对象」。 */
  const people = grants.filter((grant) => grant.role !== 'owner').map((grant) => grant.who)

  const bind = () => {
    if (pick === '' || env.trim() === '') return
    setRows((list) => [
      ...list,
      { id: 'sec_new', name: pick, env: env.trim(), source: 'you', shared: [], used: 0, limit: 500 },
    ])
    setPick('')
    setEnv('')
  }

  /**
   * **共享要走确认弹窗**（ADR 0056）：它把一把凭据交给另一个人，而对方能提取明文。行内两步
   * 只隔一次误点，正是这条规则要拦的东西。
   */
  const askShare = (row: BoundRow) => {
    if (who === '') return
    dialogs.openDanger({
      title: '共享「' + row.name + '」给 ' + who + '？',
      body:
        '高风险操作：共享后，' + who + ' 能在他的机器上使用这把凭据，也能提取明文。' +
        '只在这个 skill 上生效——这把凭据在这里的每个变量都算在内；撤销后对方下一次运行就用不了。',
      confirmLabel: '确认共享',
      returnFocus: document.activeElement as HTMLElement | null,
      onConfirm: () => {
        setRows((list) => list.map((entry) =>
          entry === row || (entry.id === row.id && entry.env === row.env)
            ? { ...entry, shared: entry.shared.includes(who) ? entry.shared : [...entry.shared, who] }
            : entry))
        setWho('')
        setSharing(null)
        dialogs.closeDanger()
      },
    })
  }

  return (
    <section className="settings-pane" data-sub="secrets" hidden={hidden} id="skillSecretsPane">
      <h2>服务凭据</h2>
      <p className="hint">凭据属于个人，绑定属于这个 skill。运行这个 skill 的机器会自动拿到值，不用再填。</p>

      <section className="settings-block">
        <h3>这个 skill 用到的凭据</h3>
        <ul className="cred-rows" id="credRows">
          {rows.map((row) => {
            const mine = row.source === 'you'
            const sharingThis = sharing === row.id
            return (
              <li key={row.id + row.env} data-cred={row.id}>
                <span className="cred-cell">
                  <span className="key-name">{row.name}</span>
                  <span className="secret-id">{row.id}</span>
                </span>
                <span className="cred-env secret-env">{row.env}</span>
                <span className="cred-source">
                  {mine ? <span className="chip is-plain">你</span> : <span className="chip is-plain">共享自 {row.source}</span>}
                </span>
                <span className="cred-used">{row.used} / {row.limit}</span>
                <span className="key-acts">
                  {mine && !sharingThis && (
                    <>
                      <button
                        type="button"
                        className="link-button"
                        data-share={row.id}
                        onClick={() => { setSharing(row.id); setWho('') }}
                      >
                        共享
                      </button>
                      <button
                        type="button"
                        className="link-button danger"
                        onClick={() => setRows((list) => list.filter((entry) => entry !== row))}
                      >
                        解除绑定
                      </button>
                    </>
                  )}
                </span>
                {sharingThis && (
                  <div className="cred-share" id="credSharePanel">
                    {/* 高风险提示常驻在这个面板上，不只是确认那一步。 */}
                    <p className="share-risk" id="credShareRisk" style={{ flexBasis: '100%', margin: 0 }}>
                      高风险操作：共享后，对方能在他的机器上使用这把凭据，也能提取明文。只共享给你信任的人。
                    </p>
                    <label className="visually-hidden" htmlFor="credWho">共享给</label>
                    <select
                      id="credWho"
                      className="control"
                      value={who}
                      onChange={(event) => setWho(event.target.value)}
                    >
                      <option value="">选择协作者</option>
                      {people.map((person) => <option key={person} value={person}>{person}</option>)}
                    </select>
                    <button
                      type="button"
                      className="button is-danger"
                      id="credShareGo"
                      disabled={who === ''}
                      onClick={() => askShare(row)}
                    >
                      共享
                    </button>
                    <button type="button" className="button is-quiet" onClick={() => setSharing(null)}>取消</button>
                  </div>
                )}
                {row.shared.length > 0 && (
                  <p className="cred-shared hint">
                    已共享给：
                    {row.shared.map((person) => (
                      <span key={person} className="cred-shared-person">
                        {person}
                        <button
                          type="button"
                          className="link-button danger"
                          onClick={() => dialogs.openDanger({
                            title: '撤销 ' + person + ' 在这把凭据上的共享？',
                            body:
                              '撤销后，' + person + ' 下一次运行这个 skill 就用不了它。值本身不变，' +
                              '绑定也不变——以后要再共享，重新共享一次即可。',
                            confirmLabel: '确认撤销',
                            danger: false,
                            returnFocus: document.activeElement as HTMLElement | null,
                            onConfirm: () => setRows((list) => list.map((entry) =>
                              entry === row || (entry.id === row.id && entry.env === row.env)
                                ? { ...entry, shared: entry.shared.filter((name) => name !== person) }
                                : entry)),
                          })}
                        >
                          撤销
                        </button>
                      </span>
                    ))}
                  </p>
                )}
              </li>
            )
          })}
        </ul>
        <p className="key-none" id="credNone" hidden={rows.length > 0 || !enabled}>
          这个 skill 还没有服务凭据。绑定后，运行它的机器会自动拿到值。
        </p>
      </section>

      <section className="settings-block">
        <h3>绑定一条凭据</h3>
        <div className="cred-bind">
          <label className="visually-hidden" htmlFor="credPick">选择凭据</label>
          <select id="credPick" className="control" value={pick} onChange={(event) => setPick(event.target.value)}>
            <option value="">选择我的凭据</option>
            {CANDIDATES.map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
          <input
            id="credEnv"
            className="control"
            value={env}
            onChange={(event) => setEnv(event.target.value)}
            placeholder="环境变量名"
            autoComplete="off"
            spellCheck={false}
          />
          <button type="button" className="button" id="credBind" disabled={pick === '' || env.trim() === ''} onClick={bind}>
            绑定
          </button>
        </div>
        <p className="hint">
          只能绑定你自己的凭据。同一个变量名已经绑过时会被拒绝，换一个变量名即可；全部在
          <a href="?page=secrets">「权限 › 服务凭据」</a>。
        </p>
      </section>
    </section>
  )
}
