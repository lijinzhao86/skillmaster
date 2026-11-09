import { useEffect, useState } from 'react'
import { failureMessage } from '../../api/errors'
import {
  bindSecret,
  listIncomingSecrets,
  listSecretShares,
  listSecrets,
  shareSecret,
  unbindSecret,
  unshareSecret,
} from '../../api/secrets'
import { listGrants } from '../../api/skills'
import type { IncomingSecret, Secret } from '../../api/secretTypes'
import ConfirmDialog from '../ConfirmDialog'
import FormBanner from '../FormBanner'

/**
 * 设置 › 服务凭据 — 这个 skill 用哪把 key、给谁用 (v52).
 *
 * **The division of labour with 权限 › 服务凭据.** That page answers 「我一共有哪些凭据」; this one
 * answers 「这一个 skill 用了哪些」, which is why it has a **来源** column the other does not: a row
 * that came from somebody else decides whether it can be shared, unbound or neither.
 *
 * **Sharing is per (credential, skill)** (2026-11-08): sharing here takes effect on this skill
 * only. The same credential on another skill needs its own grant — the screen says so, because the
 * alternative reading ("I shared it, so it is shared everywhere") is the one that leaks.
 *
 * **The grants are listed here, at open time.** Only recording them in the session of the click
 * meant yesterday's grant could not be taken back from the page that made it.
 *
 * Both lists are read from the skill's own address, and the incoming one is a separate call: the
 * owner's listing is rooted in "my credentials" and would never contain somebody else's.
 */
interface Row {
  id: string
  name: string
  env: string
  /** `you`, or the handle of the person who lent it. */
  source: string
  /** The credential's daily count and ceiling — for a lent one that is its owner's, which the
   *  server reports rather than this page computing. */
  used: number
  limit: number
  /** Own rows: the people this credential is shared with **on this skill**. */
  shared: string[]
}

export default function SecretsPane({
  hidden,
  skill,
  enabled,
}: {
  hidden: boolean
  skill: string
  enabled: boolean
}) {
  const [rows, setRows] = useState<Row[]>([])
  /** Every credential of mine — the bind row offers all of them, not just the unbound ones. */
  const [mine, setMine] = useState<Secret[]>([])
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')
  /** The incoming list failed while the own list worked: an empty pane would be a lie about it. */
  const [incomingFailed, setIncomingFailed] = useState(false)
  const [banner, setBanner] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const [sharing, setSharing] = useState<string | null>(null)
  const [who, setWho] = useState('')
  const [people, setPeople] = useState<string[]>([])
  const [peopleFailed, setPeopleFailed] = useState(false)
  const [pick, setPick] = useState('')
  const [env, setEnv] = useState('')

  const [asking, setAsking] = useState<
    | { kind: 'share'; row: Row; user: string }
    | { kind: 'unshare'; row: Row; user: string }
    | null
  >(null)

  async function load(): Promise<void> {
    // **Every credential, not the ones already bound here.** The server's `?skill=` filter keeps
    // only credentials that already have a binding on this skill, so a pane that asked for it could
    // never offer one to bind — the row the design draws would be permanently empty.
    const [own, incoming] = await Promise.all([listSecrets(), listIncomingSecrets(skill)])
    if (!own.ok) {
      setState('failed')
      setBanner(failureMessage(own))
      return
    }
    setMine(own.data.items)
    setIncomingFailed(!incoming.ok)

    const ours: Row[] = []
    for (const secret of own.data.items) {
      const here = secret.bindings.filter((binding) => binding.skill === skill)
      if (here.length === 0) continue
      const grants = await listSecretShares(secret.id, skill)
      const shared = grants.ok
        ? [...new Set(grants.data.items.map((entry) => entry.user))]
        : []
      for (const binding of here) {
        ours.push({
          id: secret.id,
          name: secret.name,
          env: binding.env,
          source: 'you',
          used: secret.used,
          limit: secret.per_day,
          shared,
        })
      }
    }
    const theirs: Row[] = (incoming.ok ? incoming.data.items : []).map(
      (entry: IncomingSecret) => ({
        id: entry.credential_id,
        name: entry.name,
        env: entry.env,
        source: entry.owner,
        used: entry.used,
        limit: entry.per_day,
        shared: [],
      }),
    )
    setRows([...ours, ...theirs])
    setState('ready')
  }

  useEffect(() => {
    if (hidden) return
    void load()
    // The address is the key: a different skill is a different pane.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hidden, skill])

  // The share panel's recipients are this skill's accepted collaborators, read when it opens.
  useEffect(() => {
    if (sharing === null) return
    let cancelled = false
    const [namespace, name] = skill.split('/')
    setPeopleFailed(false)
    void (async () => {
      // **Every page of the list.** The server pages collaborators at ten; a skill with eleven had
      // its eleventh person permanently unselectable, while the server would have accepted them.
      const all: string[] = []
      for (let page = 1; page <= 20; page++) {
        const result = await listGrants(namespace ?? '', name ?? '', undefined, page)
        if (cancelled) return
        if (!result.ok) {
          // A non-admin credential owner reaches this page legitimately, but cannot read the
          // collaborator list (that read is admin-only). Saying "nobody shared yet" would be a lie;
          // the panel says the list could not be read instead.
          setPeopleFailed(true)
          setPeople(all)
          return
        }
        all.push(...result.data.grants.map((grant) => grant.handle))
        if (all.length >= result.data.total || result.data.grants.length === 0) break
      }
      setPeople(all)
    })()
    return () => {
      cancelled = true
    }
  }, [sharing, skill])

  async function confirm(): Promise<void> {
    const question = asking
    if (question === null) return
    setBusy(true)
    setBanner(null)
    const result = question.kind === 'share'
      ? await shareSecret(question.row.id, skill, question.user)
      : await unshareSecret(question.row.id, skill, question.user)
    setBusy(false)
    setAsking(null)
    if (!result.ok) {
      setBanner(failureMessage(result))
      return
    }
    setSharing(null)
    setWho('')
    await load()
  }

  /** 解除绑定 is direct, as the design draws it: nothing is lost that a click cannot bring back. */
  async function unbind(row: Row): Promise<void> {
    setBusy(true)
    setBanner(null)
    const result = await unbindSecret(row.id, skill, row.env)
    setBusy(false)
    if (!result.ok) {
      setBanner(failureMessage(result))
      return
    }
    await load()
  }

  async function bind(): Promise<void> {
    if (pick === '' || env.trim() === '') return
    setBusy(true)
    setBanner(null)
    const result = await bindSecret(pick, skill, env.trim())
    setBusy(false)
    if (!result.ok) {
      setBanner(failureMessage(result))
      return
    }
    setPick('')
    setEnv('')
    await load()
  }

  /** The first row of a credential — its share list belongs to the credential, not to each
   *  variable, so it is drawn once rather than once per binding. */
  const firstEnvOf = (row: Row): string =>
    rows.find((entry) => entry.id === row.id)?.env ?? row.env

  // **Every credential, including one already bound here** — the same value may serve this skill
  // through a second variable name, and the server refuses only the same (skill, variable).
  const candidates = mine

  return (
    <section className="settings-pane" data-sub="secrets" hidden={hidden} id="skillSecretsPane">
      <h2>服务凭据</h2>
      <p className="hint">凭据属于个人，绑定属于这个 skill。运行这个 skill 的机器会自动拿到值，不用再填。</p>

      <FormBanner message={banner} />

      <section className="settings-block">
        <h3>这个 skill 用到的凭据</h3>
        <ul className="cred-rows" id="credRows">
          {rows.map((row) => {
            const isMine = row.source === 'you'
            const sharingThis = sharing === row.id
            return (
              <li key={row.id + row.env} data-cred={row.id}>
                <span className="cred-cell">
                  <span className="key-name">{row.name}</span>
                  <span className="secret-id">{row.id}</span>
                </span>
                <span className="cred-env secret-env">{row.env}</span>
                <span className="cred-source">
                  <span className="chip is-plain">
                    {isMine ? '你' : `共享自 ${row.source}`}
                  </span>
                </span>
                {/* The same five columns for a lent row too: the server reports its owner's count,
                    so the cell is not empty and the rows line up. */}
                <span className="cred-used">
                  {row.used} / {row.limit}
                </span>
                <span className="key-acts">
                  {isMine && !sharingThis && (
                    <>
                      <button
                        type="button"
                        className="link-button"
                        onClick={() => { setSharing(row.id); setWho('') }}
                      >
                        共享
                      </button>
                      <button
                        type="button"
                        className="link-button danger"
                        disabled={busy}
                        onClick={() => void unbind(row)}
                      >
                        解除绑定
                      </button>
                    </>
                  )}
                </span>
                {sharingThis && (
                  <div className="cred-share" id="credSharePanel">
                    {/* The risk stays on the panel, not only in the confirmation: the panel is what
                        a person reads while deciding. */}
                    <p className="share-risk" style={{ flexBasis: '100%', margin: 0 }}>
                      高风险操作：共享后，对方能在他的机器上使用这把凭据，也能提取明文。只共享给你信任的人。
                    </p>
                    <label className="visually-hidden" htmlFor="credWho">共享给</label>
                    <select
                      id="credWho"
                      className="control"
                      value={who}
                      onChange={(event) => setWho(event.target.value)}
                    >
                      <option value="">
                        {peopleFailed ? '没有读到协作者名单'
                          : people.length === 0 ? '这个 skill 还没有协作者' : '选择协作者'}
                      </option>
                      {people.map((person) => <option key={person} value={person}>{person}</option>)}
                    </select>
                    <button
                      type="button"
                      className="button is-danger"
                      id="credShareGo"
                      disabled={who === '' || busy}
                      onClick={() => setAsking({ kind: 'share', row, user: who })}
                    >
                      共享
                    </button>
                    <button type="button" className="button is-quiet" onClick={() => setSharing(null)}>
                      取消
                    </button>
                  </div>
                )}
                {isMine && row.shared.length > 0 && row.env === firstEnvOf(row) && (
                  <p className="cred-shared hint">
                    已共享给：
                    {row.shared.map((person) => (
                      <span key={person} className="cred-shared-person">
                        {person}
                        <button
                          type="button"
                          className="link-button danger"
                          disabled={busy}
                          onClick={() => setAsking({ kind: 'unshare', row, user: person })}
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
        <p className="key-none" id="credNone" hidden={rows.length > 0 || !enabled || state === 'loading'}>
          这个 skill 还没有服务凭据。绑定后，运行它的机器会自动拿到值。
        </p>
        {incomingFailed && (
          <p className="hint">没有读到别人共享给这个 skill 的凭据；上面只有你自己的。可以重试一次。</p>
        )}
        {state === 'failed' && (
          <p className="hint">没有读到这个 skill 的凭据，请稍后重试。这只影响这一块，不代表 skill 运行出了问题。</p>
        )}
      </section>

      <section className="settings-block">
        <h3>绑定一条凭据</h3>
        {/* No credentials at all is not an empty dropdown — it is a sentence and a way to the page
            that makes one. */}
        {state === 'ready' && mine.length === 0 ? (
          <p className="hint">
            你还没有服务凭据。到<a href="/account/secrets">「权限 › 服务凭据」</a>添加一条，再回来绑定。
          </p>
        ) : (
          <>
            <div className="cred-bind">
              <label className="visually-hidden" htmlFor="credPick">选择凭据</label>
              <select
                id="credPick"
                className="control"
                value={pick}
                onChange={(event) => setPick(event.target.value)}
              >
                <option value="">选择我的凭据</option>
                {candidates.map((secret) => (
                  <option key={secret.id} value={secret.id}>{secret.name}</option>
                ))}
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
              <button
                type="button"
                className="button"
                id="credBind"
                disabled={pick === '' || env.trim() === '' || busy}
                onClick={() => void bind()}
              >
                绑定
              </button>
            </div>
            <p className="hint">
              只能绑定你自己的凭据。同一个变量名已经绑过时会被拒绝，换一个变量名即可；全部在
              <a href="/account/secrets">「权限 › 服务凭据」</a>。
            </p>
          </>
        )}
      </section>

      <ConfirmDialog
        open={asking !== null}
        title={asking === null ? ''
          : asking.kind === 'share' ? `共享「${asking.row.name}」给 ${asking.user}？`
            : `撤销 ${asking.user} 在这把凭据上的共享？`}
        body={asking === null ? ''
          : asking.kind === 'share'
            ? `高风险操作：共享后，${asking.user} 能在他的机器上使用这把凭据，也能提取明文。只在这个 skill（${skill}）`
              + '上生效——这把凭据在这里的每个变量都算在内；撤销后对方下一次运行就用不了。'
            : `撤销后，${asking.user} 在 ${skill} 上的下一次运行就用不了它。值本身不变，绑定也不变——`
              + '以后要再共享，重新共享一次即可。'}
        confirmLabel={asking?.kind === 'share' ? '确认共享' : '确认撤销'}
        // 收回权限不拿走东西：按钮照常，但确认仍然是一张弹窗（ADR 0056）。
        danger={asking?.kind === 'share'}
        pending={busy}
        onConfirm={() => void confirm()}
        onCancel={() => setAsking(null)}
      />
    </section>
  )
}
