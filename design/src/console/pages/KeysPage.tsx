/**
 * 访问凭据: four counts, a filter over them, and one listing whose head carries the action.
 *
 * Borrowed from the reference product is the information architecture — **not the boxes**: the four
 * counts are separated by hairlines inside one surface, because four filled cards is the one thing
 * this direction does not do.
 *
 * **Every link and button on this page carries the list's own state.** The list is described by two
 * parameters besides the page — `filter` and `q` — and the state machine by a third, `state`. A
 * literal `href` cannot know any of them, so the addresses here are built from what is in force.
 *
 * ⚠️ **The keys are fixtures.** `api_key` is designed and the app has the four endpoints; what is
 * not invented is the shape: the string never appears again, revoking is a state rather than a
 * deletion, and an expired key is one whose date has passed.
 */

import { useEffect, useRef, useState } from 'react'
import { replace } from '../address'
import { DEFAULT_STATE } from '../demo'
import { KEY_LABEL, KEYS, KEYS_AS_OF, RENEWED_EXPIRES, madeKey, renewedKey, statusOf } from './keysModel'
import type { ApiKey, KeyStatus } from './keysModel'
import type { DemoPageProps } from './types'

export { keysState } from './keysModel'

const FILTERS = ['active', 'soon', 'revoked', 'expired'] as const

function newSecret(): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
  let out = ''
  for (let index = 0; index < 43; index += 1) {
    out += alphabet.charAt(Math.floor(Math.random() * alphabet.length))
  }
  return 'skm_' + out
}

/** The id belongs to the issuer, not to the person: a name is a label somebody chose and two keys
 *  may share one, while the id is what revoking or renewing *this row* addresses. */
function newKeyId(): string {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789'
  let out = ''
  for (let index = 0; index < 6; index += 1) out += alphabet.charAt(Math.floor(Math.random() * alphabet.length))
  return 'k' + out
}

/** Ninety days out, worked out from the reader's own calendar day: a `min` computed in UTC and a
 *  default computed in UTC disagree for part of every day, and the disagreement shows up as a field
 *  that is invalid the moment the page opens. */
function localDay(offsetDays: number): string {
  const day = new Date()
  day.setDate(day.getDate() + offsetDays)
  return new Date(day.getTime() - day.getTimezoneOffset() * 60000).toISOString().slice(0, 10)
}

function revisitedPage(): boolean {
  const entries = window.performance?.getEntriesByType?.('navigation') as PerformanceNavigationTiming[] | undefined
  const type = entries?.[0]?.type
  return type === 'reload' || type === 'back_forward'
}

export default function KeysPage({ active, state, params }: DemoPageProps) {
  const filter = (() => {
    const asked = params.get('filter')
    return asked && (FILTERS as readonly string[]).concat('all').includes(asked) ? asked : 'active'
  })()
  const query = params.get('q') ?? ''
  const needle = query.trim().toLowerCase()

  /* The list the address describes: the created key adopted first, then a renewal's two changes,
     then a revocation's one. Derived on every render, never stored — the address is the store. */
  const made = madeKey(params)
  const rotated = params.getAll('rotated')
  const revoked = params.getAll('revoked')
  const base: ApiKey[] = state === 'empty' ? [] : KEYS
  const rows: ApiKey[] = [...(made ? [made] : []), ...base].map((key) => {
    const row = { ...key }
    if (rotated.includes(row.id)) {
      row.expires = RENEWED_EXPIRES
      row.used = '从未使用'
    }
    if (revoked.includes(row.id)) row.revokedAt = KEYS_AS_OF
    return row
  })

  const matched = rows.filter((key) => !needle || key.name.toLowerCase().includes(needle))
  const counts: Record<KeyStatus, number> = { active: 0, soon: 0, revoked: 0, expired: 0 }
  matched.forEach((key) => { counts[statusOf(key)] += 1 })
  const shown = matched.filter((key) => filter === 'all' || statusOf(key) === filter)

  /** The address the page is describing, with the three parameters this page owns written from the
   *  live state and everything else — a key just made, a key just revoked — riding along. */
  const address = (overrides: Record<string, string | null> = {}, nextQuery = query): string => {
    const copy = new URLSearchParams(params)
    copy.set('page', 'keys')
    if (state !== DEFAULT_STATE.keys) copy.set('state', state); else copy.delete('state')
    if (filter !== 'active') copy.set('filter', filter); else copy.delete('filter')
    if (nextQuery.trim()) copy.set('q', nextQuery.trim()); else copy.delete('q')
    Object.entries(overrides).forEach(([name, value]) => {
      if (value === null) copy.delete(name); else copy.set(name, value)
    })
    return '?' + copy.toString()
  }
  const filterAddress = (name: string) => address({ filter: name === 'active' ? null : name })

  const goToKeyState = (next: string, key?: ApiKey) => {
    const overrides: Record<string, string | null> = { state: next }
    if (state !== DEFAULT_STATE.keys) overrides.list = state
    let href = address(overrides)
    if (key) {
      const copy = new URLSearchParams(href)
      copy.append('rotated', key.id)
      href = '?' + copy.toString()
    }
    window.location.assign(href)
  }

  const goingBack = address({ state: params.get('list'), list: null })
  const leaving = address({ state: params.get('list'), list: null, filter: 'all', q: null })

  /* ---------------------------------------------------------------- the rows */

  const [asking, setAsking] = useState<{ id: string; act: 'rotate' | 'revoke' } | null>(null)
  const [focusBack, setFocusBack] = useState<{ id: string; act: 'rotate' | 'revoke' } | null>(null)
  const confirmButton = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (asking !== null) confirmButton.current?.focus()
  }, [asking])

  useEffect(() => {
    if (asking === null && focusBack !== null) {
      const again = document.querySelector(
        `[data-key="${focusBack.id}"] [data-act="${focusBack.act}"]`,
      ) as HTMLElement | null
      again?.focus()
      setFocusBack(null)
    }
  }, [asking, focusBack])

  const cancelAsk = (back: { id: string; act: 'rotate' | 'revoke' } | null) => {
    setAsking(null)
    setFocusBack(back)
  }

  const questionOf = (act: 'rotate' | 'revoke') =>
    act === 'rotate'
      ? '续期后，这把密钥立即失效，换上一把新的。'
      : '撤销后，使用该密钥的客户端将立即失去访问权限。'

  const answers = (key: ApiKey, act: 'rotate' | 'revoke') => (
    <span
      className="key-acts"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          cancelAsk({ id: key.id, act })
        }
      }}
    >
      <button
        type="button"
        className="link-button danger"
        ref={confirmButton}
        onClick={() => {
          if (act === 'rotate') goToKeyState('rotated', key)
          else window.location.search = (() => {
            const copy = new URLSearchParams(window.location.search)
            copy.append('revoked', key.id)
            return '?' + copy.toString()
          })()
        }}
      >
        {act === 'rotate' ? '确认续期' : '确认撤销'}
      </button>
      <button type="button" className="link-button" onClick={() => cancelAsk({ id: key.id, act })}>取消</button>
    </span>
  )

  /* -------------------------------------------------------------- the form */

  const rolling = localDay(90)
  const frozen = RENEWED_EXPIRES.replace(/\//g, '-')
  const [formName, setFormName] = useState('')
  const [formNote, setFormNote] = useState('')
  const [scope, setScope] = useState('read')
  const [expiry, setExpiry] = useState(rolling > frozen ? rolling : frozen)
  const today = localDay(0)
  const formReady = formName.trim() !== '' && expiry !== '' && expiry >= today

  /* ------------------------------------------------------------- the moment */

  const momentActive = state === 'created' || state === 'rotated'
  const renewed = renewedKey(params)
  const [revisited] = useState(revisitedPage)
  const [secret] = useState(() => revisited
    ? 'skm_0000000000000000000000000000000000000000000'
    : newSecret())
  const [revealed, setRevealed] = useState(false)
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'selected'>('idle')
  const held = copyState !== 'idle'
  const heldRef = useRef(held)
  heldRef.current = held
  const left = useRef(false)
  const [leavingAsk, setLeavingAsk] = useState<HTMLElement | null>(null)
  const exit = useRef<HTMLDivElement>(null)
  const stayFocus = useRef<HTMLElement | null>(null)

  const createdTitle = made ? `「${made.name}」已创建` : '密钥已创建'
  const rotatedTitle = renewed ? `「${renewed.name}」已续期` : '密钥已续期'

  const saying = state === 'rotated'
    ? (renewed ? ['权限 ' + renewed.scope, '到期 ' + RENEWED_EXPIRES] : null)
    : (made ? ['权限 ' + made.scope, '到期 ' + made.expires, made.note ? '说明：' + made.note : ''].filter(Boolean) : null)

  const handOver = () => setCopyState('copied')

  /** Where the clipboard is not available — any page served over plain http — `execCommand` is the
   *  fallback; selecting stays as the last resort, and there it says what it did. */
  const selectInstead = () => {
    setRevealed(true)
    const range = document.createRange()
    const node = document.getElementById('keySecret')
    if (node) {
      range.selectNodeContents(node)
      const selection = window.getSelection()
      selection?.removeAllRanges()
      selection?.addRange(range)
    }
    let copied = false
    try { copied = document.execCommand('copy') } catch { copied = false }
    if (copied) handOver()
    else setCopyState('selected')
  }

  const copySecret = () => {
    if (!navigator.clipboard) { selectInstead(); return }
    navigator.clipboard.writeText(secret).then(handOver, selectInstead)
  }

  /* Everything on this page that navigates, and nothing else. A modified click opens elsewhere and
     takes nothing with it, so it is not this page's to ask about. */
  useEffect(() => {
    if (!momentActive || revisited) return
    const onClick = (event: MouseEvent) => {
      if (left.current || heldRef.current || event.defaultPrevented) return
      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
      const target = event.target as HTMLElement
      const control = target.closest?.('a[href], #signOutBtn') as HTMLElement | null
      if (!control) return
      event.preventDefault()
      stayFocus.current = control
      setLeavingAsk(control)
    }
    const onSubmit = (event: Event) => {
      if (left.current || heldRef.current || event.defaultPrevented) return
      const form = event.target as HTMLElement
      if (!form.classList?.contains('topbar-search')) return
      event.preventDefault()
      stayFocus.current = form.querySelector('input')
      setLeavingAsk(form)
    }
    document.addEventListener('click', onClick, true)
    document.addEventListener('submit', onSubmit, true)
    return () => {
      document.removeEventListener('click', onClick, true)
      document.removeEventListener('submit', onSubmit, true)
    }
  }, [momentActive, revisited])

  useEffect(() => {
    if (leavingAsk !== null) {
      exit.current?.querySelector<HTMLElement>('.link-button.danger')?.focus()
    }
  }, [leavingAsk])

  const stay = () => {
    setLeavingAsk(null)
    stayFocus.current?.focus()
    stayFocus.current = null
  }

  const replay = () => {
    const control = leavingAsk
    if (!control) return
    left.current = true
    if (control instanceof HTMLFormElement) control.requestSubmit()
    else (control as HTMLElement).click()
  }

  /* ------------------------------------------------------------------ view */

  const countText = rows.length === 0
    ? '暂无密钥'
    : shown.length + ' 个密钥' + (filter === 'all' ? '' : '（' + KEY_LABEL[filter] + '）')

  const noneText = rows.length === 0
    ? '暂无密钥。CLI 与 agent 通过密钥访问你的 skill，无需在浏览器中登录。'
    : matched.length === 0
      ? '没有名称包含「' + query.trim() + '」的密钥。'
      : '该状态下暂无密钥。'

  return (
    <section className="page" id="page-keys" hidden={!active}>
      <nav className="crumbs" aria-label="面包屑">
        <a href="?page=account">设置</a>
        <span className="crumbs-sep" aria-hidden="true">/</span>
        <span className="crumbs-here" data-when="ready empty failed" aria-current="page" hidden={state === 'new' || momentActive}>
          API 密钥
        </span>
        <a href={momentActive ? leaving : goingBack} data-when="new created rotated" hidden={state !== 'new' && !momentActive}>
          API 密钥
        </a>
        <span className="crumbs-sep" data-when="new created rotated" hidden={state !== 'new' && !momentActive} aria-hidden="true">/</span>
        <span className="crumbs-here" data-when="new created" aria-current="page" hidden={state !== 'new' && state !== 'created'}>
          新建 API 密钥
        </span>
        <span className="crumbs-here" data-when="rotated" aria-current="page" hidden={state !== 'rotated'}>
          续期密钥
        </span>
      </nav>

      <header className="page-head">
        <h1>
          <span data-when="ready empty failed" hidden={state === 'new' || momentActive}>API 密钥</span>
          <span data-when="new" hidden={state !== 'new'}>新建 API 密钥</span>
          <span data-when="created" hidden={state !== 'created'} id="keyCreatedTitle">{createdTitle}</span>
          <span data-when="rotated" hidden={state !== 'rotated'} id="keyRotatedTitle">{rotatedTitle}</span>
        </h1>
      </header>

      <p className="page-note" data-when="ready empty failed new" hidden={momentActive}>
        CLI 与 agent 使用这些密钥访问你的 skill。<strong>密钥仅在创建时显示一次</strong>，之后只保留名称、权限与使用记录。
      </p>

      <div className="stats" data-when="ready empty" hidden={state !== 'ready' && state !== 'empty'}>
        {(FILTERS as readonly KeyStatus[]).map((name) => (
          <a
            key={name}
            className={'stat' + (name === filter ? ' is-on' : '')}
            data-filter={name}
            href={filterAddress(name)}
            aria-current={name === filter ? 'true' : undefined}
          >
            <span className="stat-head">{KEY_LABEL[name]}</span>
            <span className="stat-body">
              <span className="stat-number" data-count={name}>{counts[name]}</span>
              <span className="stat-go">筛选列表</span>
            </span>
          </a>
        ))}
      </div>

      <div className="key-search" data-when="ready empty" hidden={state !== 'ready' && state !== 'empty'}>
        <svg className="key-search-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
          <line x1="15.4" y1="15.4" x2="20.6" y2="20.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
        <span className="filter-chip">
          状态：<b id="filterName">{filter === 'all' ? '全部' : KEY_LABEL[filter]}</b>
          <a className="filter-x" id="filterClear" href={address({ filter: 'all' })} title="清除筛选" aria-label="清除筛选" hidden={filter === 'all'}>×</a>
        </span>
        <label className="visually-hidden" htmlFor="keyQuery">搜索名称</label>
        <input
          id="keyQuery"
          className="key-input"
          type="search"
          autoComplete="off"
          spellCheck={false}
          placeholder="搜索名称"
          value={query}
          onChange={(event) => replace(address({}, event.target.value))}
        />
      </div>

      <p className="hint" data-when="failed" hidden={state !== 'failed'}>
        无法加载密钥列表，请稍后重试。此处仅表示列表加载失败，不代表账号异常。
      </p>

      <div className="key-card" data-when="ready empty" hidden={state !== 'ready' && state !== 'empty'}>
        <div className="key-head">
          <span className="key-count" id="keyCount">{countText}</span>
          <button
            type="button"
            className="button"
            id="keyNew"
            data-when="ready empty"
            onClick={() => goToKeyState('new')}
          >
            新建密钥
          </button>
        </div>
        <div className="key-body">
          <div className="key-cols" aria-hidden="true">
            <span>名称</span><span>说明</span><span>权限</span><span>状态</span><span>操作</span>
          </div>
          <ul className="keys" id="keyList">
            {shown.map((key) => {
              const askingThis = asking?.id === key.id ? asking.act : null
              const status = statusOf(key)
              const gone = status === 'revoked'
              return (
                <li data-key={key.id} key={key.id}>
                  <span className="key-name">{key.name}</span>
                  <span className={'key-note' + (key.note ? '' : ' is-empty')}>{key.note || '未填写说明'}</span>
                  <span className="key-scope">{key.scope}</span>
                  <span className={'key-state' + (askingThis ? ' is-warn' : '')}>
                    {askingThis ? questionOf(askingThis) : (
                      <>
                        <span className="key-when">
                          <span className={'chip' + (status === 'active' ? '' : ' is-' + status)} hidden={status === 'active'}>
                            <i className="dot"></i>
                            <span className="chip-text">{KEY_LABEL[status]}</span>
                          </span>
                          <span className="key-fact"
                            ><svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path fillRule="evenodd" clipRule="evenodd" d="M8.175.002a8 8 0 1 0 2.309 15.603.75.75 0 0 0-.466-1.426 6.5 6.5 0 1 1 3.996-8.646.75.75 0 0 0 1.388-.569A8 8 0 0 0 8.175.002ZM8.75 3.75a.75.75 0 0 0-1.5 0v3.94L5.216 9.4a.75.75 0 0 0 .768 1.288l2.39-1.425a.75.75 0 0 0 .376-.648V3.75Z" /></svg
                            ><span className="key-date">{status === 'revoked' ? '撤销于 ' + key.revokedAt : '到期 ' + key.expires}</span
                          ></span>
                        </span>
                        <span className="key-used"
                          ><svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path fillRule="evenodd" clipRule="evenodd" d="M2.75 0a.75.75 0 0 0 0 1.5H3v.593c0 1.26.5 2.468 1.391 3.359L6.94 8l-2.548 2.548A4.75 4.75 0 0 0 3 13.907v.593h-.25a.75.75 0 0 0 0 1.5h10.5a.75.75 0 0 0 0-1.5H13v-.593c0-1.26-.5-2.468-1.391-3.359L9.06 8l2.548-2.548A4.75 4.75 0 0 0 13 2.093V1.5h.25a.75.75 0 0 0 0-1.5H2.75Zm2.75 2.093V1.5h5v.593c0 .729-.29 1.428-.805 1.944L8 5.732 6.305 4.037A2.75 2.75 0 0 1 5.5 2.093ZM8 8.732 4.914 11.82c-.265.265-.414.624-.414.999v1.182h7v-1.182c0-.375-.149-.734-.414-.999L8 8.732Z" /></svg
                          ><span className="key-used-text">{'最后使用 ' + key.used}</span
                        ></span>
                      </>
                    )}
                  </span>
                  {askingThis ? answers(key, askingThis) : (
                    <span className="key-acts">
                      <button type="button" className="link-button" data-act="rotate" hidden={gone}
                        onClick={() => setAsking({ id: key.id, act: 'rotate' })}>续期</button>
                      <button type="button" className="link-button danger" data-act="revoke" hidden={gone}
                        onClick={() => setAsking({ id: key.id, act: 'revoke' })}>撤销</button>
                    </span>
                  )}
                </li>
              )
            })}
          </ul>
          <p className="key-none" id="keyNone" hidden={shown.length > 0}>{noneText}</p>
        </div>
      </div>

      <section className="key-card" data-when="new" hidden={state !== 'new'}>
        <div className="key-head">
          <span className="key-count">基本信息</span>
        </div>
        <div className="key-body">
          <form
            className="key-form"
            id="keyForm"
            noValidate
            onSubmit={(event) => {
              event.preventDefault()
              if (!formReady) return
              window.location.assign(address({
                state: 'created',
                'made-id': newKeyId(),
                'made-name': formName.trim(),
                'made-scope': scope,
                'made-expires': expiry,
                'made-note': formNote.trim() || null,
              }))
            }}
          >
            <div className="field">
              <label htmlFor="keyName">名称</label>
              <input
                id="keyName"
                name="name"
                type="text"
                className="control"
                maxLength={60}
                autoComplete="off"
                placeholder="笔记本"
                aria-describedby="keyNameHint"
                value={formName}
                onChange={(event) => setFormName(event.target.value)}
              />
              <p className="hint" id="keyNameHint">用在哪台机器、哪个用途。名字只给你自己看，之后还能改。</p>
            </div>

            <div className="field">
              <label htmlFor="keyNote">说明</label>
              <textarea
                id="keyNote"
                name="note"
                className="control"
                rows={2}
                maxLength={255}
                placeholder="例如：CI 上读 pdf-tools 用的"
                value={formNote}
                onChange={(event) => setFormNote(event.target.value)}
              ></textarea>
              <p className="hint">会显示在列表里。名称只够认出它是哪一把，说明才是你半年后需要的那句话。</p>
            </div>

            <fieldset className="field">
              <legend>权限</legend>
              <label className="key-choice">
                <input type="radio" name="scope" value="read" checked={scope === 'read'} onChange={() => setScope('read')} />
                <span className="key-choice-body">
                  <span className="key-choice-title">读取</span>
                  <span className="key-choice-note">能读你的 skill 与它们的文件。</span>
                </span>
              </label>
              <label className="key-choice">
                <input type="radio" name="scope" value="write" checked={scope === 'write'} onChange={() => setScope('write')} />
                <span className="key-choice-body">
                  <span className="key-choice-title">读写</span>
                  <span className="key-choice-note">上面全部，另加提交新版本、管理草稿——<strong>上线仍然只能在浏览器里由人点</strong>。</span>
                </span>
              </label>
            </fieldset>

            <div className="field">
              <label htmlFor="keyExpiry">有效期</label>
              <input
                id="keyExpiry"
                name="expires"
                type="date"
                className="control"
                min={today}
                value={expiry}
                onChange={(event) => setExpiry(event.target.value)}
                onClick={(event) => {
                  const field = event.currentTarget
                  if (typeof field.showPicker !== 'function') return
                  try { field.showPicker() } catch { /* already open */ }
                }}
              />
              <p className="hint">已按默认预选 90 天。到期后这把密钥就失效了，用它的机器会收到 401；可以在列表里续期。</p>
            </div>

            <div className="key-form-foot">
              <a className="button is-quiet" href={goingBack}>取消</a>
              <button type="submit" className="button" id="keySubmit" disabled={!formReady}>新建密钥</button>
            </div>
          </form>
        </div>
      </section>

      <section className="key-card" data-when="created rotated" hidden={!momentActive}>
        <div className="key-head">
          <span className="key-count">密钥详情</span>
        </div>
        <div className="key-body key-detail">
          <p className="hint" id="keyWarningCreated" data-when="created" hidden={state !== 'created'}>
            {revisited
              ? '整串已经不在这里了——这一屏是创建那一刻的响应，重新加载之后取不回来。服务端只保存其摘要。'
              : <><strong>密钥仅在此时显示一次</strong>——服务端只保存其摘要，离开本页后将无法再次查看。</>}
          </p>
          <p className="hint" id="keyWarningRotated" data-when="rotated" hidden={state !== 'rotated'}>
            {revisited
              ? '整串已经不在这里了——这一屏是续期那一刻的响应，重新加载之后取不回来。服务端只保存其摘要；原密钥已立即失效。'
              : <>续期完成，<strong>原密钥已立即失效</strong>。新密钥仅在此时显示一次，服务端只保存其摘要。</>}
          </p>
          <p className="hint key-facts" id="keyMomentFacts" hidden={saying === null}>{saying?.join(' · ')}</p>
          <div className="secret" id="keySecretBox" hidden={revisited}>
            <code className={'secret-value' + (revealed ? '' : ' is-masked')} id="keySecret">{secret}</code>
            <button
              type="button"
              className="secret-btn is-reveal"
              id="keyReveal"
              aria-label={revealed ? '隐藏密钥' : '显示密钥'}
              aria-pressed={revealed ? 'true' : 'false'}
              onClick={() => setRevealed((on) => !on)}
            >
              <svg className="i-show" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" ref={(node) => { node?.toggleAttribute('hidden', revealed) }}><path fillRule="evenodd" clipRule="evenodd" d="M0 8s3-6 8-6 8 6 8 6-3 6-8 6-8-6-8-6Zm1.81.13A13.593 13.593 0 0 1 1.73 8l.082-.13c.326-.51.806-1.187 1.42-1.856C4.494 4.635 6.12 3.5 8 3.5c1.878 0 3.506 1.135 4.77 2.514A13.705 13.705 0 0 1 14.27 8a14.021 14.021 0 0 1-1.502 1.986C11.506 11.365 9.88 12.5 8 12.5c-1.878 0-3.506-1.135-4.77-2.514A13.703 13.703 0 0 1 1.81 8.13ZM11 8a3 3 0 1 1-2.117-2.868 1.5 1.5 0 1 0 1.985 1.985A3 3 0 0 1 11 8Z" /></svg>
              <svg className="i-hide" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" ref={(node) => { node?.toggleAttribute('hidden', !revealed) }}><path fillRule="evenodd" clipRule="evenodd" d="M14.776 2.284a.75.75 0 0 0-1.06-1.06l-1.953 1.953C10.707 2.499 9.44 2 8 2 5.506 2 3.533 3.49 2.24 4.86A14.615 14.615 0 0 0 .226 7.576a5.39 5.39 0 0 0-.028.052l-.008.014-.003.005v.002L.85 8l-.663-.35L.002 8l.185.35L.85 8l-.663.35v.002l.002.002.004.007.012.023c.01.02.026.047.046.082a14.417 14.417 0 0 0 .82 1.262c.47.647 1.13 1.447 1.96 2.18L1.22 13.72a.75.75 0 1 0 1.06 1.06L14.776 2.284Zm-10.681 8.56 1.32-1.32a3 3 0 0 1 4.109-4.109l1.148-1.147C9.864 3.8 8.969 3.5 8 3.5c-1.88 0-3.483 1.134-4.67 2.39A13.114 13.114 0 0 0 1.716 8c.13.213.32.508.567.846a11.98 11.98 0 0 0 1.811 1.998Zm9.42-5.166a.75.75 0 0 1 1.053.122c.447.564.901 1.2 1.245 1.85l.185.35-.185.35L15.15 8l.663.351-.001.002-.003.005-.008.014a9.81 9.81 0 0 1-.53.865 14.62 14.62 0 0 1-1.51 1.903C12.467 12.51 10.494 14 8 14a6.939 6.939 0 0 1-1.021-.08l-.02-.002-.006-.001h-.002l.12-.741-.12.74a.75.75 0 0 1 .239-1.48h.002l.011.001a6.024 6.024 0 0 0 .235.03c.158.017.362.033.562.033 1.88 0 3.483-1.134 4.67-2.39a13.11 13.11 0 0 0 1.616-2.115 12.33 12.33 0 0 0-.893-1.263.75.75 0 0 1 .121-1.054Z" /></svg>
            </button>
            <button
              type="button"
              className={'secret-btn is-copy' + (copyState === 'copied' ? ' is-copied' : '')}
              id="keyCopy"
              aria-label={copyState === 'copied' ? '已复制' : copyState === 'selected' ? '已选中，请按你的复制快捷键' : '复制密钥'}
              onClick={copySecret}
            >
              <svg className="i-copy" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" ref={(node) => { node?.toggleAttribute('hidden', copyState === 'copied') }}><path fillRule="evenodd" clipRule="evenodd" d="M4 9.5H2.5v-7h7V4H11V2a1 1 0 0 0-1-1H2a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1h2V9.5Zm9.5 4h-7v-7h7v7ZM5 6a1 1 0 0 1 1-1h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6Z" /></svg>
              <svg className="i-copied" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" ref={(node) => { node?.toggleAttribute('hidden', copyState !== 'copied') }}><path fillRule="evenodd" clipRule="evenodd" d="M12.78 4.62a.75.75 0 0 1 0 1.06l-6.097 6.097a.75.75 0 0 1-1.069-.009L3.211 9.284a.75.75 0 1 1 1.078-1.043l1.873 1.936L11.72 4.62a.75.75 0 0 1 1.06 0Z" /></svg>
            </button>
          </div>
          <p className="hint">如未保存：请撤销该密钥后重新创建，或对原密钥直接续期。</p>
        </div>
      </section>

      <div
        className="key-exit"
        data-when="created rotated"
        hidden={!momentActive}
        ref={exit}
        onKeyDown={(event) => {
          if (leavingAsk === null || event.key !== 'Escape') return
          event.preventDefault()
          stay()
        }}
      >
        <button
          type="button"
          className="button"
          id="keyDone"
          hidden={leavingAsk !== null}
          disabled={!revisited && !held}
          onClick={() => window.location.assign(leaving)}
        >
          {revisited || held ? '完成' : '复制后方可完成'}
        </button>
        {leavingAsk !== null && (
          <>
            <span className="hint">这把密钥还没复制——离开后取不回来，只能撤销后重建。</span>
            <button type="button" className="link-button danger" onClick={replay}>仍然离开</button>
            <button type="button" className="link-button" onClick={stay}>留在这一屏</button>
          </>
        )}
      </div>
    </section>
  )
}
