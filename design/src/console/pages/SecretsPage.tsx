/**
 * 服务凭据：skill 跑起来时要用的服务 key / cookie / token。
 *
 * **一条值，多个绑定。** 值属于人，绑定 `(凭据, skill, 环境变量名)` 才是暴露面——所以同一把
 * key 用在两个 skill 上，列表里仍然只有一行，更新一次两边都生效；而没绑过的 skill 取不到。
 *
 * **与「API 密钥」方向相反。** API 密钥是用户签发给我们、创建那一刻只显示一次；服务凭据是我们
 * 替用户保管、**任何时候都不显示**——所以这一页没有「已创建」那一屏，也没有眼睛。
 *
 * **四个动作各有自己的地址，而且都带着是哪一条**：`&secret=<id>`。不带 id 就落到第一行，等于
 * 让人在「观察者网」那一行点共享、实际共享了「知乎」——那是一次不可逆的凭据泄露。
 */

import { useState } from 'react'
import type { FormEvent } from 'react'
import { dialogs } from '../components/dialogs/store'
import { SECRETS, SECRET_LIMIT, collaboratorsOf, SECRET_SKILLS } from '../fixtures/secrets'
import type { ServiceCredential } from '../fixtures/secrets'
import type { DemoPageProps } from './types'

/** 这一屏解释一次「值不会出现在界面上」——它是与 API 密钥最大的差别，值得一句常驻说明。 */
const NOTE = 'skill 运行需要的凭据。值只在运行 skill 时注入，不会显示在界面上。'

/** 绑定多的时候先只露这么多；剩下的收在「还有 N 个」后面，不把整张表撑高。 */
const SHOWN_BINDINGS = 2

/** 一屏里的四个动作，都从地址上取是哪一条。 */
type CardState = 'add' | 'update' | 'bind' | 'share'

function cappedOf(secret: ServiceCredential): boolean {
  return secret.used >= secret.limit
}

/** 新建：先有一条值，顺带建第一个绑定。值不进地址、不进历史。 */
function FormCard({ onCancel }: { onCancel: () => void }) {
  const [name, setName] = useState('')
  const [skill, setSkill] = useState('')
  const [env, setEnv] = useState('')
  const [value, setValue] = useState('')
  const ready = name.trim() !== '' && skill !== '' && env.trim() !== '' && value !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const next = new URLSearchParams()
    next.set('page', 'secrets')
    next.set('made-name', name.trim())
    next.set('made-skill', skill)
    next.set('made-env', env.trim())
    window.location.assign('?' + next.toString())
  }

  return (
    <section className="key-card" data-when="add" hidden={false} id="secretAddCard">
      <div className="key-head">
        <span className="key-count">添加服务凭据</span>
      </div>
      <div className="key-body">
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
              <select
                id="secretSkill"
                className="control"
                value={skill}
                onChange={(event) => setSkill(event.target.value)}
              >
                <option value="">选择一个 skill</option>
                {SECRET_SKILLS.map((address) => (
                  <option key={address} value={address}>{address}</option>
                ))}
              </select>
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
            <button type="button" className="button is-quiet" onClick={onCancel}>取消</button>
            <button type="submit" className="button" id="secretSave" disabled={!ready}>保存</button>
          </div>
        </form>
      </div>
    </section>
  )
}

/**
 * 更新值：**只换值，不碰绑定与共享**。
 *
 * 与「添加」分开一屏，是因为它们是两件事：一条泄露的 cookie 要的是「把值换掉」，新建一条只会
 * 让旧值继续留在两个 skill 上，而人以为自己已经更新过了。
 */
function UpdateCard({ secret, onCancel }: { secret: ServiceCredential; onCancel: () => void }) {
  const [value, setValue] = useState('')
  const ready = value !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const next = new URLSearchParams()
    next.set('page', 'secrets')
    next.set('updated', secret.id)
    window.location.assign('?' + next.toString())
  }

  return (
    <section className="key-card" data-when="update" hidden={false} id="secretUpdateCard">
      <div className="key-head">
        <span className="key-count">更新「{secret.name}」的值</span>
      </div>
      <div className="key-body">
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
            <button type="button" className="button is-quiet" onClick={onCancel}>取消</button>
            <button type="submit" className="button" id="secretUpdate" disabled={!ready}>更新值</button>
          </div>
        </form>
      </div>
    </section>
  )
}

/** 绑定：只加一条绑定，不碰值、也不显示值。 */
function BindCard({ secret, onCancel }: { secret: ServiceCredential; onCancel: () => void }) {
  const [skill, setSkill] = useState('')
  const [env, setEnv] = useState('')
  const ready = skill !== '' && env.trim() !== ''

  /* **提交要有个结果。** 一个只 `preventDefault()` 的表单，在「权限 › 服务凭据」这条路径上
     是给维护/写入协作者用的主路径——点下去毫无反应，等于告诉他这件事没做成。 */
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const next = new URLSearchParams()
    next.set('page', 'secrets')
    next.set('bound', secret.id)
    window.location.assign('?' + next.toString())
  }

  return (
    <section className="key-card" data-when="bind" hidden={false} id="secretBindCard">
      <div className="key-head">
        <span className="key-count">绑定「{secret.name}」到另一个 skill</span>
      </div>
      <div className="key-body">
        <form className="key-form" onSubmit={submit}>
          <div className="field">
            <label htmlFor="bindSkill">绑定到</label>
            <div className="secret-bind-row">
              <select
                id="bindSkill"
                className="control"
                value={skill}
                onChange={(event) => setSkill(event.target.value)}
              >
                <option value="">选择一个 skill</option>
                {SECRET_SKILLS.map((address) => (
                  <option key={address} value={address}>{address}</option>
                ))}
              </select>
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
            <button type="button" className="button is-quiet" onClick={onCancel}>取消</button>
            <button type="submit" className="button" id="bindSubmit" disabled={!ready}>绑定</button>
          </div>
        </form>
      </div>
    </section>
  )
}

/**
 * 共享一屏：常驻的那句话就是确认弹窗的正文——两处说同一件事，用一个说法。
 *
 * **这里列出已经共享给谁**，否则作者第二天打开页面会看到「还没有共享给任何人」，也没法撤销。
 */
function ShareCard({ secret }: { secret: ServiceCredential }) {
  const [who, setWho] = useState('')
  /** **共享按「凭据 + skill」算**，所以这里要先说在哪个 skill 上共享。 */
  const [skill, setSkill] = useState(secret.bindings[0]?.skill ?? '')
  const [shared, setShared] = useState(secret.shared.slice())

  /* 名单随选中的 skill 变：共享对象必须是**这个 skill** 的已接受协作者。 */
  const people = collaboratorsOf(skill)

  const confirm = () => {
    if (who === '' || skill === '') return
    const already = shared.some((entry) => entry.skill === skill && entry.who === who)
    dialogs.openDanger({
      title: '共享「' + secret.name + '」给 ' + who + '？',
      body:
        '共享后，' + who + ' 能在他的机器上使用这把凭据，也能提取明文。只在这个 skill（' +
        skill + '）上生效——它在这个 skill 上的每个变量都算在内；撤销后对方下一次运行就用不了。',
      confirmLabel: '确认共享',
      returnFocus: document.activeElement as HTMLElement | null,
      onConfirm: () => {
        if (!already) setShared((list) => [...list, { skill, who }])
        setWho('')
        dialogs.closeDanger()
      },
    })
  }

  return (
    <section className="key-card" data-when="share" hidden={false} id="secretShareCard">
      <div className="key-head">
        <span className="key-count">共享「{secret.name}」</span>
      </div>
      <div className="key-body">
        {/* **共享是高风险操作，提示常驻**：不能只在点下去之后才说，因为那一步正是它要拦的地方。 */}
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
          <select
            id="secretWho"
            className="control"
            value={who}
            onChange={(event) => setWho(event.target.value)}
          >
            <option value="">{people.length === 0 ? '这个 skill 还没有协作者' : '选择一个协作者'}</option>
            {people.map((person) => <option key={person} value={person}>{person}</option>)}
          </select>
          <button
            type="button"
            className="button is-danger"
            id="secretShareGo"
            disabled={who === ''}
            onClick={confirm}
          >
            共享
          </button>
        </div>
        <p className="key-none" hidden={shared.length > 0}>还没有共享给任何人。</p>
        {shared.length > 0 && (
          <ul className="secrets" id="secretSharedList">
            {shared.map((entry) => (
              <li key={entry.skill + '/' + entry.who}>
                <span className="key-name">{entry.who}</span>
                <span className="secret-where">{entry.skill}</span>
                <span />
                <span />
                <span className="key-acts">
                  <button
                    type="button"
                    className="link-button danger"
                    onClick={() => dialogs.openDanger({
                      title: '撤销 ' + entry.who + ' 在这把凭据上的共享？',
                      body:
                        '撤销后，' + entry.who + ' 在 ' + entry.skill + ' 上的下一次运行就用不了它。' +
                        '值本身不变，绑定也不变——以后要再共享，重新共享一次即可。',
                      confirmLabel: '确认撤销',
                      // 收回权限不拿走东西：按钮照常，但确认仍然是一张弹窗（ADR 0056）。
                      danger: false,
                      returnFocus: document.activeElement as HTMLElement | null,
                      onConfirm: () => setShared((list) => list.filter((item) =>
                        !(item.skill === entry.skill && item.who === entry.who))),
                    })}
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

export default function SecretsPage({ active, state, params }: DemoPageProps) {
  const madeName = params.get('made-name')
  const updatedId = params.get('updated')
  const boundId = params.get('bound')
  const made: ServiceCredential | null = madeName
    ? {
      id: 'c-new',
      name: madeName,
      bindings: [{ skill: params.get('made-skill') ?? '', env: params.get('made-env') ?? '' }],
      shared: [],
      used: 0,
      limit: SECRET_LIMIT,
      lastUsed: '从未使用',
    }
    : null

  const base = state === 'empty' ? [] : SECRETS
  const all = made ? [made, ...base] : base
  /* 「已达上限」那一态只留下到顶的那一条：它是这一态唯一要给人看的东西。
     **这是演示件的取景，不是产品规则**——真实页面显示全部行，只在到顶那一行加标记。 */
  const rows = state === 'capped' ? all.filter(cappedOf) : all

  const [gone, setGone] = useState<string[]>([])
  const [open, setOpen] = useState<string[]>([])
  const [quotaFor, setQuotaFor] = useState<string | null>(null)
  const [quotaValue, setQuotaValue] = useState('')

  const openOf = (id: string) => open.includes(id)
  const toggleOpen = (id: string) => setOpen((list) => (list.includes(id) ? list.filter((e) => e !== id) : [...list, id]))
  const visibleOf = (secret: ServiceCredential) => (openOf(secret.id) ? secret.bindings : secret.bindings.slice(0, SHOWN_BINDINGS))
  const openQuota = (secret: ServiceCredential) => {
    setQuotaFor(secret.id)
    setQuotaValue(String(secret.limit))
  }
  const shown = rows.filter((secret) => !gone.includes(secret.id))

  /* **哪一条**来自地址。缺了 id 就不打开那一屏，而不是悄悄落到第一行。 */
  const asked = params.get('secret')
  const target = asked ? all.find((secret) => secret.id === asked) ?? null : null

  const remove = (secret: ServiceCredential) => {
    dialogs.openDanger({
      title: '删除「' + secret.name + '」？',
      body:
        '删除后这一条值立即作废：绑着它的 ' + secret.bindings.length +
        ' 处 skill 再也拿不到它，共享给别人的也一起失效。值不保留；数据库备份里可能还有旧副本，' +
        '随备份周期轮转，当前最多 7 天，之后也无法恢复（与隐私政策同一条）。',
      confirmLabel: '确认删除',
      returnFocus: document.activeElement as HTMLElement | null,
      onConfirm: () => {
        setGone((list) => [...list, secret.id])
        dialogs.closeDanger()
      },
    })
  }

  const card: CardState | null = state === 'add' || state === 'update' || state === 'bind' || state === 'share'
    ? state
    : null
  /* **地址上那一条不在这里，就不要把人留在空白页里。** 手敲的地址、过期的链接、少带了
     `&secret=` 的入口都会走到这里：回到列表，并说明为什么会回来。 */
  const missing = card !== null && card !== 'add' && target === null

  return (
    <section className="page" id="page-secrets" hidden={!active}>
      <nav className="crumbs" aria-label="面包屑">
        <a href="?page=account">设置</a>
        <span className="crumbs-sep" aria-hidden="true">/</span>
        {card !== null ? (
          <>
            <a href="?page=secrets">服务凭据</a>
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
        地址里那条凭据不在这里。可能是链接少了 `&secret=`，或者它已经被删掉。下面是你的全部凭据。
      </p>

      <p className="page-note" data-when="ready capped failed" hidden={card !== null || state === 'failed'}>
        {NOTE}
      </p>

      {(made || updatedId || boundId) && card === null && (
        <p className="hint" id="secretMade">
          {updatedId ? '已更新。值不会显示，也不会出现在页面地址里。'
            : boundId ? '已绑定。运行那个 skill 的机器会拿到这个变量的值。'
              : '已保存。值不会显示，也不会出现在页面地址里。'}
        </p>
      )}

      {/* 控制台读不到列表，不等于 skill 跑不起来：运行走的是租约，是另一条路。 */}
      <p className="hint" data-when="failed" hidden={state !== 'failed'}>
        没有读到服务凭据列表，请稍后重试。这只影响这一页，不代表 skill 运行出了问题。
      </p>

      <div className="key-card" data-when="ready empty capped" hidden={(card !== null && !missing) || state === 'failed'}>
        <div className="key-head">
          <span className="key-count" id="secretCount">
            {shown.length === 0 ? '还没有凭据' : shown.length + ' 条凭据'}
          </span>
          <a className="button" id="secretAdd" href="?page=secrets&state=add">添加凭据</a>
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
            {shown.map((secret) => {
              const capped = cappedOf(secret)
              return (
                <li data-secret={secret.id} key={secret.id}>
                  <span className="secret-cell">
                    <span className="key-name">{secret.name}</span>
                    {/* **身份是 id，名称只是标签。** 命令收的是这个 id，所以名称可以起中文。 */}
                    <span className="secret-id">{secret.id}</span>
                  </span>
                  <span className="secret-cell">
                    {visibleOf(secret).map((binding) => (
                      <span className="secret-line" key={binding.skill + '/' + binding.env}>{binding.skill}</span>
                    ))}
                    {secret.bindings.length > SHOWN_BINDINGS && (
                      <button
                        type="button"
                        className="link-button secret-more"
                        data-more={secret.id}
                        onClick={() => toggleOpen(secret.id)}
                      >
                        {openOf(secret.id) ? '收起' : '还有 ' + (secret.bindings.length - SHOWN_BINDINGS) + ' 个'}
                      </button>
                    )}
                  </span>
                  <span className="secret-cell">
                    {visibleOf(secret).map((binding) => (
                      <span className="secret-line secret-env" key={binding.skill + '/' + binding.env}>{binding.env}</span>
                    ))}
                  </span>
                  <span className={'secret-used' + (capped ? ' is-capped' : '')} data-used={secret.id}>
                    <span>{secret.used} / {secret.limit}</span>
                    {capped && <span className="chip is-capped">已达上限</span>}
                    {/* **到顶的时候入口必须还在。** 状态与动作不互斥：这一格最需要「调整」的时刻，
                        正是它显示「已达上限」的时刻——原来把它藏起来，等于在需要它的地方拿走了它。 */}
                    <button
                      type="button"
                      className="link-button secret-adjust"
                      data-adjust={secret.id}
                      onClick={() => openQuota(secret)}
                    >
                      调整
                    </button>
                  </span>
                  <span className="key-acts">
                    <a className="link-button" data-update={secret.id}
                      href={'?page=secrets&state=update&secret=' + secret.id}>更新值</a>
                    <a className="link-button" data-bind={secret.id}
                      href={'?page=secrets&state=bind&secret=' + secret.id}>绑定</a>
                    <a className="link-button" data-share={secret.id}
                      href={'?page=secrets&state=share&secret=' + secret.id}>共享</a>
                    <button
                      type="button"
                      className="link-button danger"
                      data-delete={secret.id}
                      onClick={() => remove(secret)}
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
                      <button type="button" className="button is-quiet" onClick={() => setQuotaFor(null)}>取消</button>
                      <button type="button" className="button" id="secretQuotaSave" onClick={() => setQuotaFor(null)}>保存</button>
                    </div>
                  )}
                </li>
              )
            })}
          </ul>
          {/* **空态只留一句 + 一个入口**：读到这一页的人已经知道凭据是什么。 */}
          <p className="key-none" id="secretNone" hidden={shown.length > 0}>
            还没有服务凭据。
          </p>
          {shown.length > 0 && (
            <p className="hint">用量按取用次数计：每次运行 skill 用一条凭据算一次。默认上限 {SECRET_LIMIT}。</p>
          )}
        </div>
      </div>

      {card === 'add' && <FormCard onCancel={() => window.location.assign('?page=secrets')} />}
      {card === 'update' && target && <UpdateCard secret={target} onCancel={() => window.location.assign('?page=secrets')} />}
      {card === 'bind' && target && <BindCard secret={target} onCancel={() => window.location.assign('?page=secrets')} />}
      {card === 'share' && target && <ShareCard secret={target} />}
    </section>
  )
}
