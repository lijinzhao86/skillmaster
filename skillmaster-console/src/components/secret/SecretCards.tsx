import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { listGrants } from '../../api/skills'
import FormBanner from '../FormBanner'
import type { Secret, SecretShare } from '../../api/secretTypes'

/**
 * The four screens of 服务凭据 that are not the list (design §服务凭据).
 *
 * **Each is its own screen rather than a form that appears under a row.** The design draws them as
 * a card beside the list with a crumb that names it, because each answers a different question:
 * 添加 makes a value, 更新值 replaces one, 绑定 widens where it is used, 共享 gives it to a person.
 * A leaked cookie needs the second one specifically — creating a second credential would leave the
 * first still bound to both skills while the person believes they replaced it.
 *
 * **The value never round-trips.** Every form here takes it in an `<input type="password">`, sends
 * it once, and the page it lands on has nothing to show afterwards: the server does not return it
 * and there is no endpoint that would.
 */

/** A skill the person could bind to: their own library, as addresses. */
export type SkillOptions = string[]

interface Common {
  busy: boolean
  error: string | null
  onCancel: () => void
}

function AddressSelect({
  id,
  value,
  options,
  onChange,
}: {
  id: string
  value: string
  options: SkillOptions
  onChange: (next: string) => void
}) {
  return (
    <select id={id} className="control" value={value} onChange={(event) => onChange(event.target.value)}>
      <option value="">选择一个 skill</option>
      {options.map((address) => (
        <option key={address} value={address}>{address}</option>
      ))}
    </select>
  )
}

/** 添加：一条值，顺带第一个绑定。 */
export function AddCard({
  skills,
  busy,
  error,
  onCancel,
  onSave,
}: Common & {
  skills: SkillOptions
  onSave: (input: { name: string; value: string; skill: string; env: string }) => void
}) {
  const [name, setName] = useState('')
  const [value, setValue] = useState('')
  const [skill, setSkill] = useState('')
  const [env, setEnv] = useState('')
  const ready = name.trim() !== '' && skill !== '' && env.trim() !== '' && value !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!ready) return
    onSave({ name: name.trim(), value, skill, env: env.trim() })
  }

  return (
    <section className="key-card" id="secretAddCard">
      <div className="key-head">
        <span className="key-count">添加服务凭据</span>
      </div>
      <div className="key-body">
        <FormBanner message={error} />
        <form className="key-form" onSubmit={submit}>
          <div className="field">
            <label htmlFor="secretName">名称</label>
            <input
              id="secretName"
              className="control"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="知乎 cookie"
              autoComplete="off"
            />
            <p className="hint">只给你自己看。同一把值以后还能绑到别的 skill 上。</p>
          </div>
          <div className="field">
            <label htmlFor="secretValue">值</label>
            <input
              id="secretValue"
              className="control"
              type="password"
              value={value}
              onChange={(event) => setValue(event.target.value)}
              autoComplete="off"
              spellCheck={false}
            />
            <p className="hint">粘贴进来即可。保存后不会再显示，也没有「查看」——忘了就更新一次。</p>
          </div>
          <div className="field">
            <label htmlFor="secretSkill">绑定到</label>
            <div className="secret-bind-row">
              <AddressSelect id="secretSkill" value={skill} options={skills} onChange={setSkill} />
              <input
                id="secretEnv"
                className="control"
                value={env}
                onChange={(event) => setEnv(event.target.value)}
                placeholder="环境变量名"
                autoComplete="off"
                spellCheck={false}
              />
            </div>
          </div>
          <div className="key-form-foot">
            <button type="button" className="button is-quiet" onClick={onCancel} disabled={busy}>
              取消
            </button>
            <button type="submit" className="button" id="secretSave" disabled={!ready || busy}>
              保存
            </button>
          </div>
        </form>
      </div>
    </section>
  )
}

/** 更新值：只换值，不碰绑定与共享。 */
export function UpdateCard({
  secret,
  busy,
  error,
  onCancel,
  onUpdate,
}: Common & { secret: Secret; onUpdate: (value: string) => void }) {
  const [value, setValue] = useState('')
  const ready = value !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!ready) return
    onUpdate(value)
  }

  return (
    <section className="key-card" id="secretUpdateCard">
      <div className="key-head">
        <span className="key-count">更新「{secret.name}」的值</span>
      </div>
      <div className="key-body">
        <FormBanner message={error} />
        <form className="key-form" onSubmit={submit}>
          <div className="field">
            <label htmlFor="secretNewValue">新的值</label>
            <input
              id="secretNewValue"
              className="control"
              type="password"
              value={value}
              onChange={(event) => setValue(event.target.value)}
              autoComplete="off"
              spellCheck={false}
            />
            <p className="hint">
              旧值立即作废。绑定与共享不变——绑着它的 {secret.bindings.length} 处，下一次运行就用新值。
            </p>
          </div>
          <div className="key-form-foot">
            <button type="button" className="button is-quiet" onClick={onCancel} disabled={busy}>
              取消
            </button>
            <button type="submit" className="button" id="secretUpdate" disabled={!ready || busy}>
              更新值
            </button>
          </div>
        </form>
      </div>
    </section>
  )
}

/** 绑定：只加一条绑定，不碰值、也不显示值。 */
export function BindCard({
  secret,
  skills,
  busy,
  error,
  onCancel,
  onBind,
}: Common & {
  secret: Secret
  skills: SkillOptions
  onBind: (skill: string, env: string) => void
}) {
  const [skill, setSkill] = useState('')
  const [env, setEnv] = useState('')
  const ready = skill !== '' && env.trim() !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!ready) return
    onBind(skill, env.trim())
  }

  return (
    <section className="key-card" id="secretBindCard">
      <div className="key-head">
        <span className="key-count">绑定「{secret.name}」到另一个 skill</span>
      </div>
      <div className="key-body">
        <FormBanner message={error} />
        <form className="key-form" onSubmit={submit}>
          <div className="field">
            <label htmlFor="bindSkill">绑定到</label>
            <div className="secret-bind-row">
              <AddressSelect id="bindSkill" value={skill} options={skills} onChange={setSkill} />
              <input
                id="bindEnv"
                className="control"
                value={env}
                onChange={(event) => setEnv(event.target.value)}
                placeholder="环境变量名"
                autoComplete="off"
                spellCheck={false}
              />
            </div>
            <p className="hint">值不变，只是多一个 skill 能用它。已经绑过的组合会报错，不会静默覆盖。</p>
          </div>
          <div className="key-form-foot">
            <button type="button" className="button is-quiet" onClick={onCancel} disabled={busy}>
              取消
            </button>
            <button type="submit" className="button" id="bindSubmit" disabled={!ready || busy}>
              绑定
            </button>
          </div>
        </form>
      </div>
    </section>
  )
}

/** 共享：高风险提示常驻，确认在弹窗里，已经共享给谁也列在这里。 */
export function ShareCard({
  secret,
  shares,
  initialSkill,
  initialUser,
  busy,
  error,
  onShare,
  onUnshare,
}: Omit<Common, 'onCancel'> & {
  secret: Secret
  shares: SecretShare[]
  /** From the address — `skmr secret share --skill … --user …` arrives with both chosen. */
  initialSkill: string
  initialUser: string
  onShare: (skill: string, user: string) => void
  onUnshare: (skill: string, user: string) => void
}) {
  /**
   * **共享按「凭据 + skill」算**，所以先要说在哪个 skill 上共享。
   *
   * <p>**An address that names a skill this credential is not bound to does not fall back
   * silently.** Falling back would put the person one click away from granting on a different
   * skill than the command named — the same class of mistake as opening the first row when the id
   * is missing. The select stays on a real binding and a line says what the address asked for.
   */
  const askedSkill = initialSkill
  const askedSkillInvalid = askedSkill !== ''
    && !secret.bindings.some((binding) => binding.skill === askedSkill)
  const [skill, setSkill] = useState(
    askedSkill !== '' && !askedSkillInvalid ? askedSkill : secret.bindings[0]?.skill ?? '',
  )
  const [who, setWho] = useState(initialUser)
  const [people, setPeople] = useState<string[]>([])
  const [peopleState, setPeopleState] = useState<'loading' | 'ready' | 'failed'>('loading')

  // The list follows the skill: a share's recipient must be an accepted collaborator of *that*
  // skill, so a name that was valid for one is not for another.
  useEffect(() => {
    if (skill === '') {
      setPeople([])
      setPeopleState('ready')
      return
    }
    let cancelled = false
    const [namespace, name] = skill.split('/')
    setPeopleState('loading')
    void (async () => {
      // **Every page.** The server pages collaborators at ten, so a skill with eleven had its
      // eleventh person permanently unselectable by the person who owns the credential.
      const all: string[] = []
      for (let page = 1; page <= 20; page++) {
        const result = await listGrants(namespace ?? '', name ?? '', undefined, page)
        if (cancelled) return
        if (!result.ok) {
          // Only an admin may read this list, and a collaborator who owns a credential may
          // legitimately be sharing it here. Falling back to a typed handle keeps that flow
          // possible; the server still refuses anybody who is not an accepted collaborator.
          setPeopleState('failed')
          setPeople(all)
          return
        }
        all.push(...result.data.grants.map((grant) => grant.handle))
        if (all.length >= result.data.total || result.data.grants.length === 0) break
      }
      setPeople(all)
      setPeopleState('ready')
    })()
    return () => {
      cancelled = true
    }
  }, [skill])

  // **One row per person, not per variable.** A grant covers every variable the credential is bound
  // to on that skill (the server returns one row per variable, because that is what the lease hands
  // over), and revoking is by person — so listing the rows raw would offer the same undo four times.
  // **All of this credential's grants, not only the selected skill's.** Filtering by the dropdown
  // would print 「还没有共享给任何人」 while a grant on another skill is live — a sentence that is
  // the opposite of the truth on the screen where sharing is decided.
  const sharedHere = Object.values(shares
    .reduce<Record<string, { user: string; skill: string; envs: string[] }>>((all, entry) => {
      const key = entry.skill + '/' + entry.user
      const row = all[key] ?? { user: entry.user, skill: entry.skill, envs: [] }
      row.envs.push(entry.env)
      all[key] = row
      return all
    }, {}))

  return (
    <section className="key-card" id="secretShareCard">
      <div className="key-head">
        <span className="key-count">共享「{secret.name}」</span>
      </div>
      <div className="key-body">
        <FormBanner message={error} />
        {/* **Shared is high risk, so the sentence is always here** — not only after the click, which
            is the moment it exists to interrupt. */}
        {askedSkillInvalid && (
          <p className="hint" id="secretShareStray">
            地址里的 skill（{askedSkill}）不是这条凭据绑定过的，所以下面没有选它。先在那边绑定，再回来共享。
          </p>
        )}
        <p className="share-risk" id="secretShareRisk">
          高风险操作：共享后，对方能在他的机器上使用这把凭据，也能提取明文。只共享给你信任的人。
        </p>
        <div className="secret-share">
          <label className="visually-hidden" htmlFor="secretShareSkill">在哪个 skill 上共享</label>
          <select
            id="secretShareSkill"
            className="control"
            value={skill}
            onChange={(event) => { setSkill(event.target.value); setWho('') }}
          >
            {secret.bindings.map((binding) => (
              <option key={binding.skill + '/' + binding.env} value={binding.skill}>
                {binding.skill} · {binding.env}
              </option>
            ))}
          </select>
          <label className="visually-hidden" htmlFor="secretWho">共享给</label>
          {peopleState === 'failed' ? (
            <input
              id="secretWho"
              className="control"
              value={who}
              onChange={(event) => setWho(event.target.value)}
              placeholder="对方的用户名"
              autoComplete="off"
              spellCheck={false}
            />
          ) : (
          <select
            id="secretWho"
            className="control"
            value={who}
            onChange={(event) => setWho(event.target.value)}
          >
            <option value="">
              {peopleState === 'loading' ? '正在读取协作者…'
                : people.length === 0 ? '这个 skill 还没有协作者' : '选择一个协作者'}
            </option>
            {/* **The address's person stays visible even before (or without) the list.** A
                controlled select shows nothing for a value it has no option for, and the button
                would still be enabled — the confirmation would name somebody the panel never
                showed. */}
            {who !== '' && !people.includes(who) && <option value={who}>{who}</option>}
            {people.map((person) => <option key={person} value={person}>{person}</option>)}
          </select>
          )}
          <button
            type="button"
            className="button is-danger"
            id="secretShareGo"
            disabled={who === '' || skill === '' || busy}
            onClick={() => onShare(skill, who)}
          >
            共享
          </button>
        </div>
        <p className="key-none" hidden={sharedHere.length > 0}>还没有共享给任何人。</p>
        {sharedHere.length > 0 && (
          <ul className="secrets" id="secretSharedList">
            {sharedHere.map((entry) => (
              <li key={entry.skill + '/' + entry.user}>
                <span className="key-name">{entry.user}</span>
                <span className="secret-where">{entry.skill}</span>
                <span />
                <span />
                <span className="key-acts">
                  <button
                    type="button"
                    className="link-button danger"
                    disabled={busy}
                    onClick={() => onUnshare(entry.skill, entry.user)}
                  >
                    撤销
                  </button>
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}
