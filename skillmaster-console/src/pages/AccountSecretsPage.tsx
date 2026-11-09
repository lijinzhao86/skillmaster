import { useEffect, useState } from 'react'
import { addressFor, go, param } from '../address'
import { failureMessage } from '../api/errors'
import { listLibrary } from '../api/library'
import {
  bindSecret,
  createSecret,
  deleteSecret,
  listSecretShares,
  listSecrets,
  rotateSecret,
  setSecretQuota,
  shareSecret,
  unshareSecret,
} from '../api/secrets'
import type { Secret, SecretShare } from '../api/secretTypes'
import { loginPath } from '../login'
import ConfirmDialog from '../components/ConfirmDialog'
import FormBanner from '../components/FormBanner'
import { AddCard, BindCard, ShareCard, UpdateCard } from '../components/secret/SecretCards'

/**
 * 权限 › 服务凭据 — the console's half of the vault (v52).
 *
 * **One credential is one row, however many skills use it.** The value belongs to the person; a
 * binding is what exposes it to one skill and one variable name. Rotating replaces the value for
 * every binding at once, and a skill nobody bound it to can never read it.
 *
 * **Nothing here shows a value, and that is the design's point.** The API-key screen has a moment
 * where the string is revealed once; this page has no such moment, because the platform holds the
 * value on the person's behalf and the only place it reappears is inside a running skill.
 *
 * **Which row a card is about comes from the address** (`?state=…&secret=…`). Falling back to the
 * first row when the id is missing would put somebody's 共享 click on a credential they were not
 * looking at — an irreversible leak, so a missing or stale id goes back to the list with a line
 * saying why.
 *
 * The screen itself — the columns, the capped marker, the four cards, the danger dialogs and every
 * sentence — is `design/src/console/pages/SecretsPage.tsx`, which is the authority.
 */

const NOTE = 'skill 运行需要的凭据。值只在运行 skill 时注入，不会显示在界面上。'

/** Bindings are listed with this many before they are folded; the table must not grow unbounded. */
const SHOWN_BINDINGS = 2

type CardState = 'add' | 'update' | 'bind' | 'share'

function readCard(): CardState | null {
  const value = param('state')
  return value === 'add' || value === 'update' || value === 'bind' || value === 'share'
    ? value
    : null
}

function secretsAddress(params: Record<string, string>): string {
  const query = new URLSearchParams(params)
  return addressFor('/account/secrets', query)
}

export default function AccountSecretsPage() {
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [secrets, setSecrets] = useState<Secret[]>([])
  const [skills, setSkills] = useState<string[]>([])
  const [banner, setBanner] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState<string[]>([])
  const [quotaFor, setQuotaFor] = useState<string | null>(null)
  const [quotaValue, setQuotaValue] = useState('')

  /** The share screen's grants; read when it opens, because the list has no room for them. */
  const [shares, setShares] = useState<SecretShare[]>([])

  /** One confirmation at a time: delete, new share, or withdraw a grant. */
  const [asking, setAsking] = useState<
    | { kind: 'delete'; secret: Secret }
    | { kind: 'share'; secret: Secret; skill: string; user: string }
    | { kind: 'unshare'; secret: Secret; skill: string; user: string }
    | null
  >(null)

  const card = readCard()
  const asked = param('secret')
  const target: Secret | null = asked === null
    ? null
    : secrets.find((secret) => secret.id === asked) ?? null
  // **「它不在这里」只有在列表真的读到了之后才是事实。** 首帧和读取失败时 `secrets` 还是空的，
  // 照原样判会把「还没读到」说成「已经被删掉」，而后者是一句错误断言。
  const missing = state === 'ready' && card !== null && card !== 'add' && target === null
  const flash = param('updated') !== null ? 'updated'
    : param('bound') !== null ? 'bound'
      : param('made') !== null ? 'made'
        : null

  useEffect(() => {
    void (async () => {
      const [listed, library] = await Promise.all([listSecrets(), listLibrary({ per: 50 })])
      if (!listed.ok) {
        if (listed.code === 'unauthenticated') {
          go(loginPath())
          return
        }
        setState('failed')
        setBanner(failureMessage(listed))
        return
      }
      setSecrets(listed.data.items)
      // A skill option is an address the person can bind to; a failed library read only costs the
      // selects their options, never the list.
      if (library.ok) {
        setSkills(library.data.skills.map((skill) => `${skill.namespace}/${skill.name}`))
      }
      setState('ready')
    })()
  }, [])

  // The share screen's grants are read whenever it opens — for a different credential, or after a
  // grant was made or withdrawn, the answer above is stale.
  const shareTarget = card === 'share' && target !== null ? target : null
  useEffect(() => {
    if (shareTarget === null) {
      setShares([])
      return
    }
    let cancelled = false
    void (async () => {
      const result = await listSecretShares(shareTarget.id)
      if (!cancelled && result.ok) {
        setShares(result.data.items)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [shareTarget?.id, card])

  /** Reload, and drop a refusal that a successful reload has just answered. */
  async function load(): Promise<void> {
    const result = await listSecrets()
    if (!result.ok) {
      setState('failed')
      setBanner(failureMessage(result))
      return
    }
    setSecrets(result.data.items)
    setState('ready')
    setBanner(null)
  }

  /** A write's answer: reload, then land on the address that says what happened. */
  async function settled(address: string): Promise<void> {
    setBusy(false)
    await load()
    go(address)
  }

  async function save(
    input: { name: string; value: string; skill: string; env: string },
  ): Promise<void> {
    setBusy(true)
    setBanner(null)
    const result = await createSecret(input)
    if (!result.ok) {
      setBusy(false)
      setBanner(failureMessage(result))
      return
    }
    await settled(secretsAddress({ made: result.data.id }))
  }

  async function update(secret: Secret, value: string): Promise<void> {
    setBusy(true)
    setBanner(null)
    const result = await rotateSecret(secret.id, value)
    if (!result.ok) {
      setBusy(false)
      setBanner(failureMessage(result))
      return
    }
    await settled(secretsAddress({ updated: secret.id }))
  }

  async function bind(secret: Secret, skill: string, env: string): Promise<void> {
    setBusy(true)
    setBanner(null)
    const result = await bindSecret(secret.id, skill, env)
    if (!result.ok) {
      setBusy(false)
      setBanner(failureMessage(result))
      return
    }
    await settled(secretsAddress({ bound: secret.id }))
  }

  async function confirmAsking(): Promise<void> {
    const question = asking
    if (question === null) return
    setBusy(true)
    setBanner(null)
    const result = question.kind === 'delete'
      ? await deleteSecret(question.secret.id)
      : question.kind === 'share'
        ? await shareSecret(question.secret.id, question.skill, question.user)
        : await unshareSecret(question.secret.id, question.skill, question.user)
    setBusy(false)
    setAsking(null)
    if (!result.ok) {
      setBanner(failureMessage(result))
      return
    }
    await load()
    if (question.kind !== 'delete' && shareTarget !== null) {
      const refreshed = await listSecretShares(shareTarget.id)
      if (refreshed.ok) {
        setShares(refreshed.data.items)
      }
    }
  }

  async function saveQuota(secret: Secret): Promise<void> {
    const next = Number(quotaValue)
    if (!Number.isFinite(next) || next < 1) return
    setBusy(true)
    const result = await setSecretQuota(secret.id, next)
    setBusy(false)
    setQuotaFor(null)
    if (!result.ok) {
      setBanner(failureMessage(result))
      return
    }
    await load()
  }

  const cardHref = (state: CardState, id: string): string =>
    secretsAddress({ state, secret: id })
  const listHref = '/account/secrets'

  return (
    <section className="page" id="page-secrets">
      <nav className="crumbs" aria-label="面包屑">
        <a href="/account">设置</a>
        <span className="crumbs-sep" aria-hidden="true">/</span>
        {card !== null ? (
          <>
            <a href={listHref}>服务凭据</a>
            <span className="crumbs-sep" aria-hidden="true">/</span>
            <span className="crumbs-here" aria-current="page">
              {card === 'add' ? '添加凭据' : card === 'update' ? '更新值' : card === 'share' ? '共享' : '绑定'}
            </span>
          </>
        ) : (
          <span className="crumbs-here" aria-current="page">服务凭据</span>
        )}
      </nav>

      <header className="page-head">
        <h1>服务凭据</h1>
      </header>

      <p className="hint" id="secretStray" hidden={!missing}>
        地址里那条凭据不在这里。可能是链接少了 <code>&amp;secret=</code>，或者它已经被删掉。下面是你的全部凭据。
      </p>

      {card === null && state === 'ready' && <p className="page-note">{NOTE}</p>}

      {flash !== null && card === null && (
        <p className="hint" id="secretMade">
          {flash === 'updated' ? '已更新。值不会显示，也不会出现在页面地址里。'
            : flash === 'bound' ? '已绑定。运行那个 skill 的机器会拿到这个变量的值。'
              : '已保存。值不会显示，也不会出现在页面地址里。'}
        </p>
      )}

      {/* The list failing is not the skill failing: running goes through the lease, another road. */}
      {state === 'failed' && (
        <p className="hint">
          没有读到服务凭据列表，请稍后重试。这只影响这一页，不代表 skill 运行出了问题。
        </p>
      )}

      {/* A card carries the same refusal under its own form; saying it twice on one screen reads
          as two failures. */}
      {card === null && <FormBanner message={banner} />}

      {state !== 'failed' && (card === null || missing) && (
        <div className="key-card">
          <div className="key-head">
            <span className="key-count" id="secretCount">
              {secrets.length === 0 ? '还没有凭据' : `${secrets.length} 条凭据`}
            </span>
            <a className="button" id="secretAdd" href={secretsAddress({ state: 'add' })}>添加凭据</a>
          </div>
          <div className="key-body">
            <div className="secret-cols" aria-hidden="true">
              <span>名称</span>
              <span>用于</span>
              <span>环境变量</span>
              <span>今日用量</span>
              <span>操作</span>
            </div>
            <ul className="secrets" id="secretList">
              {secrets.map((secret) => {
                const capped = secret.used >= secret.per_day
                const expanded = open.includes(secret.id)
                const shownBindings = expanded ? secret.bindings : secret.bindings.slice(0, SHOWN_BINDINGS)
                return (
                  <li data-secret={secret.id} key={secret.id}>
                    <span className="secret-cell">
                      <span className="key-name">{secret.name}</span>
                      {/* The id is the identity; the name is a label a person can type in Chinese. */}
                      <span className="secret-id">{secret.id}</span>
                    </span>
                    <span className="secret-cell">
                      {shownBindings.map((binding) => (
                        <span className="secret-line" key={binding.skill + '/' + binding.env}>
                          {binding.skill}
                        </span>
                      ))}
                      {secret.bindings.length > SHOWN_BINDINGS && (
                        <button
                          type="button"
                          className="link-button secret-more"
                          onClick={() => setOpen((list) =>
                            list.includes(secret.id)
                              ? list.filter((entry) => entry !== secret.id)
                              : [...list, secret.id])}
                        >
                          {expanded ? '收起' : `还有 ${secret.bindings.length - SHOWN_BINDINGS} 个`}
                        </button>
                      )}
                    </span>
                    <span className="secret-cell">
                      {shownBindings.map((binding) => (
                        <span className="secret-line secret-env" key={binding.skill + '/' + binding.env}>
                          {binding.env}
                        </span>
                      ))}
                    </span>
                    <span className={'secret-used' + (capped ? ' is-capped' : '')}>
                      <span>{secret.used} / {secret.per_day}</span>
                      {capped && <span className="chip is-capped">已达上限</span>}
                      {/* The entry stays while capped: 「已达上限」 is exactly when 调整 is needed. */}
                      <button
                        type="button"
                        className="link-button secret-adjust"
                        onClick={() => { setQuotaFor(secret.id); setQuotaValue(String(secret.per_day)) }}
                      >
                        调整
                      </button>
                    </span>
                    <span className="key-acts">
                      <a className="link-button" href={cardHref('update', secret.id)}>更新值</a>
                      <a className="link-button" href={cardHref('bind', secret.id)}>绑定</a>
                      <a className="link-button" href={cardHref('share', secret.id)}>共享</a>
                      <button
                        type="button"
                        className="link-button danger"
                        onClick={() => setAsking({ kind: 'delete', secret })}
                      >
                        删除
                      </button>
                    </span>
                    {quotaFor === secret.id && (
                      <div className="secret-quota" id="secretQuotaPanel">
                        <label className="secret-quota-label" htmlFor="secretQuotaInput">每日上限</label>
                        <input
                          id="secretQuotaInput"
                          className="control secret-quota-input"
                          type="number"
                          min={1}
                          max={10000}
                          value={quotaValue}
                          onChange={(event) => setQuotaValue(event.target.value)}
                        />
                        <span className="hint">默认 500，最多 10,000。提高之后，这一条凭据每天最多被取用这么多次。</span>
                        <button type="button" className="button is-quiet" onClick={() => setQuotaFor(null)}>
                          取消
                        </button>
                        <button
                          type="button"
                          className="button"
                          id="secretQuotaSave"
                          disabled={busy}
                          onClick={() => void saveQuota(secret)}
                        >
                          保存
                        </button>
                      </div>
                    )}
                  </li>
                )
              })}
            </ul>
            <p className="key-none" id="secretNone" hidden={secrets.length > 0}>还没有服务凭据。</p>
            {secrets.length > 0 && (
              <p className="hint">用量按取用次数计：每次运行 skill 用一条凭据算一次。默认上限 500。</p>
            )}
          </div>
        </div>
      )}

      {card === 'add' && (
        <AddCard
          skills={skills}
          busy={busy}
          error={banner}
          onCancel={() => go(listHref)}
          onSave={(input) => void save(input)}
        />
      )}
      {card === 'update' && target !== null && (
        <UpdateCard
          secret={target}
          busy={busy}
          error={banner}
          onCancel={() => go(listHref)}
          onUpdate={(value) => void update(target, value)}
        />
      )}
      {card === 'bind' && target !== null && (
        <BindCard
          secret={target}
          skills={skills}
          busy={busy}
          error={banner}
          onCancel={() => go(listHref)}
          onBind={(skill, env) => void bind(target, skill, env)}
        />
      )}
      {card === 'share' && target !== null && (
        <ShareCard
          secret={target}
          shares={shares}
          initialSkill={param('share') ?? ''}
          initialUser={param('user') ?? ''}
          busy={busy}
          error={banner}
          onShare={(skill, user) => setAsking({ kind: 'share', secret: target, skill, user })}
          onUnshare={(skill, user) => setAsking({ kind: 'unshare', secret: target, skill, user })}
        />
      )}

      <ConfirmDialog
        open={asking !== null}
        title={asking === null ? ''
          : asking.kind === 'delete' ? `删除「${asking.secret.name}」？`
            : asking.kind === 'share' ? `共享「${asking.secret.name}」给 ${asking.user}？`
              : `撤销 ${asking.user} 在这把凭据上的共享？`}
        body={asking === null ? ''
          : asking.kind === 'delete'
            ? `删除后这一条值立即作废：绑着它的 ${asking.secret.bindings.length} 处 skill 再也拿不到它，`
              + '共享给别人的也一起失效。值不保留；数据库备份里可能还有旧副本，随备份周期轮转，'
              + '当前最多 7 天，之后也无法恢复（与隐私政策同一条）。'
            : asking.kind === 'share'
              ? `共享后，${asking.user} 能在他的机器上使用这把凭据，也能提取明文。只在这个 skill（${asking.skill}）`
                + '上生效——它在这个 skill 上的每个变量都算在内；撤销后对方下一次运行就用不了。'
              : `撤销后，${asking.user} 在 ${asking.skill} 上的下一次运行就用不了它。值本身不变，绑定也不变——`
                + '以后要再共享，重新共享一次即可。'}
        confirmLabel={asking?.kind === 'delete' ? '确认删除'
          : asking?.kind === 'share' ? '确认共享' : '确认撤销'}
        danger={asking?.kind !== 'unshare'}
        pending={busy}
        onConfirm={() => void confirmAsking()}
        onCancel={() => setAsking(null)}
      />
    </section>
  )
}
