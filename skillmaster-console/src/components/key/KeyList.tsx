import type { ApiKey } from '../../api/types'
import { formatRelative } from '../../time'
import FormBanner from '../FormBanner'
import {
  LABEL,
  happenedText,
  scopeLabel,
  stateOf,
  type Filter,
  type Question,
  type State,
} from './keyModel'

/**
 * 访问凭据 — the list screen: four counts that are also filters, the search, and one row per key.
 *
 * **Markup only, and the flat props are the honest interface.** Everything it draws — the rows, the
 * counts, the question a row can ask, the sentence for "nothing matched" — is the page's state; what
 * stands here is the screen. `now` is passed rather than read, so a row's state is a function of the
 * clock the page last refreshed, not of whenever this component happened to render.
 */
export interface KeyListProps {
  state: 'loading' | 'ready' | 'failed'
  banner: string | null
  /** The keys that answer the filter and the search — the rows on screen. */
  shown: ApiKey[]
  counts: Record<State, number>
  filter: Filter
  query: string
  /** The page's address for a filter, carrying the search — the cards and the crumb are links. */
  filterHref: (target: Filter) => string
  onQuery: (value: string) => void
  /** The instant `stateOf` reads — refreshed by the page, so all rows agree. */
  now: number
  /** The key an action is in flight for, so only that row disables. */
  busy: string | null
  asking: Question | null
  ask: (id: string, action: 'revoke' | 'rotate') => void
  cancel: () => void
  confirmRevoke: (id: string) => Promise<void>
  confirmRotate: (id: string) => Promise<void>
  startCreate: () => void
  /** The sentence the list shows when nothing answers the filter, already chosen by the page. */
  emptySentence: string
}

export default function KeyList({
  state,
  banner,
  shown,
  counts,
  filter,
  query,
  filterHref,
  onQuery,
  now,
  busy,
  asking,
  ask,
  cancel,
  confirmRevoke,
  confirmRotate,
  startCreate,
  emptySentence,
}: KeyListProps) {
  return (
        <section className="page">
          {/* The trail names the area and then the page, which is what the reference product's own does:
             its sidebar says 「Access」 while its breadcrumb says 「User Settings / …」, because the
             sidebar answers "where can I go" and the breadcrumb answers "where am I". **It is also the
             only place that says this page sits under 设置**: the navigation's disclosure starts
             collapsed, on purpose. */}
          <nav className="crumbs" aria-label="面包屑">
            <a href="/account">设置</a>
            <span className="crumbs-sep" aria-hidden="true">
              /
            </span>
            <span className="crumbs-here" aria-current="page">
              API 密钥
            </span>
          </nav>

          <header className="page-head">
            <h1>API 密钥</h1>
          </header>

          {/* The page's own sentence. **Not on the moment's screens** — it is about the page, and that
             card already says the one thing that matters while a string is on screen. */}
          <p className="page-note">
            CLI 与 agent 使用这些密钥访问你的 skill。
            <strong>密钥仅在创建时显示一次</strong>，之后只保留名称、权限与使用记录。
          </p>

          <FormBanner message={banner} />

          {state === 'ready' ? (
            <>
              {/* Four counts, and every one of them is a filter. A card here is two surfaces — a grey shell
                 with a white body inside it — which is the recipe the listing and the moment use too. */}
              <div className="stats">
                {(['active', 'soon', 'revoked', 'expired'] as State[]).map((one) => (
                  <a
                    key={one}
                    className={filter === one ? 'stat is-on' : 'stat'}
                    href={filterHref(one)}
                  >
                    <span className="stat-head">{LABEL[one]}</span>
                    <span className="stat-body">
                      <span className="stat-number">{counts[one]}</span>
                      <span className="stat-go">筛选列表</span>
                    </span>
                  </a>
                ))}
              </div>

              {/* **The filter and the search are one control**: the token that says which filter is on sits
                 inside the box, next to the magnifier, and the free-text field follows it. */}
              <div className="key-search">
                <svg className="key-search-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
                  <line
                    x1="15.4"
                    y1="15.4"
                    x2="20.6"
                    y2="20.6"
                    stroke="currentColor"
                    strokeWidth="1.8"
                    strokeLinecap="round"
                  />
                </svg>
                <span className="filter-chip">
                  状态：<b>{LABEL[filter]}</b>
                  {filter !== 'all' ? (
                    <>
                      {' '}
                      <a
                        className="filter-x"
                        href={filterHref('all')}
                        title="清除筛选"
                        aria-label="清除筛选"
                      >
                        ×
                      </a>
                    </>
                  ) : null}
                </span>
                <label className="visually-hidden" htmlFor="key-query">
                  搜索名称
                </label>
                <input
                  id="key-query"
                  value={query}
                  className="key-input"
                  type="search"
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="搜索名称"
                  onChange={(event) => onQuery(event.target.value)}
                />
              </div>

              <div className="key-card">
                <div className="key-head">
                  <span className="key-count">{shown.length} 个密钥</span>
                  {/* In the empty state too, and that is the point of it: a day-one account has no keys,
                     and this must not be the one screen where making one has no control at all. */}
                  <button
                    type="button"
                    className="button"
                    data-key-new=""
                    onClick={startCreate}
                  >
                    新建密钥
                  </button>
                </div>
                <div className="key-body">
                  {shown.length > 0 ? (
                    <div className="key-cols" aria-hidden="true">
                      <span>名称</span>
                      <span>说明</span>
                      <span>权限</span>
                      <span>状态</span>
                      <span>操作</span>
                    </div>
                  ) : null}
                  {shown.length > 0 ? (
                    <ul className="keys">
                      {shown.map((key) => (
                        <li key={key.id} data-key-id={key.id}>
                          <span className="key-name">{key.name}</span>
                          {/* A missing note is the reference product's own answer — a grey sentence saying so —
                             rather than a blank cell that reads like a rendering failure. */}
                          <span
                            className={
                              key.note === null || key.note === ''
                                ? 'key-note is-empty'
                                : 'key-note'
                            }
                          >
                            {key.note === null || key.note === '' ? '未填写说明' : key.note}
                          </span>
                          <span className="key-scope">{scopeLabel(key.scope)}</span>

                          {/* The state column, or the question that replaces it. Two lines rather than one, each
                             with its own icon: when it expires and when it was last used belong together, and
                             neither deserves a column of its own. **An active key draws no chip at all** — most
                             rows are active, so a mark on every one of them carries no information. */}
                          {asking !== null && asking.id === key.id ? (
                            <span className="key-state is-warn">
                              {asking.action === 'revoke'
                                ? '撤销后，使用该密钥的客户端将立即失去访问权限。'
                                : '续期后，这把密钥立即失效，换上一把新的。'}
                            </span>
                          ) : (
                            <span className="key-state">
                              <span className="key-when">
                                {stateOf(key, now) !== 'active' ? (
                                  <span className={`chip is-${stateOf(key, now)}`}>
                                    <i className="dot" />
                                    <span className="chip-text">{LABEL[stateOf(key, now)]}</span>
                                  </span>
                                ) : null}
                                <span className="key-fact">
                                  <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                                    <path
                                      fillRule="evenodd"
                                      clipRule="evenodd"
                                      d="M8.175.002a8 8 0 1 0 2.309 15.603.75.75 0 0 0-.466-1.426 6.5 6.5 0 1 1 3.996-8.646.75.75 0 0 0 1.388-.569A8 8 0 0 0 8.175.002ZM8.75 3.75a.75.75 0 0 0-1.5 0v3.94L5.216 9.4a.75.75 0 0 0 .768 1.288l2.39-1.425a.75.75 0 0 0 .376-.648V3.75Z"
                                    />
                                  </svg>
                                  {/* This slot says what happened to *this* key, which is not always when it
                                     expires: a revoked one has a different date entirely, and showing its expiry
                                     there would be true and useless. */}
                                  <span className="key-date">{happenedText(key)}</span>
                                </span>
                              </span>
                              <span className="key-used">
                                <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                                  <path
                                    fillRule="evenodd"
                                    clipRule="evenodd"
                                    d="M2.75 0a.75.75 0 0 0 0 1.5H3v.593c0 1.26.5 2.468 1.391 3.359L6.94 8l-2.548 2.548A4.75 4.75 0 0 0 3 13.907v.593h-.25a.75.75 0 0 0 0 1.5h10.5a.75.75 0 0 0 0-1.5H13v-.593c0-1.26-.5-2.468-1.391-3.359L9.06 8l2.548-2.548A4.75 4.75 0 0 0 13 2.093V1.5h.25a.75.75 0 0 0 0-1.5H2.75Zm2.75 2.093V1.5h5v.593c0 .729-.29 1.428-.805 1.944L8 5.732 6.305 4.037A2.75 2.75 0 0 1 5.5 2.093ZM8 8.732 4.914 11.82c-.265.265-.414.624-.414.999v1.182h7v-1.182c0-.375-.149-.734-.414-.999L8 8.732Z"
                                  />
                                </svg>
                                <span className="key-used-text">
                                  最后使用{' '}
                                  {key.last_used_at === null
                                    ? '从未使用'
                                    : formatRelative(key.last_used_at)}
                                </span>
                              </span>
                            </span>
                          )}

                          <span className="key-acts">
                            {asking !== null && asking.id === key.id ? (
                              <>
                                <button
                                  type="button"
                                  className="link-button danger"
                                  data-key-confirm=""
                                  disabled={busy === key.id}
                                  onClick={() => {
                                    void (asking.action === 'revoke'
                                      ? confirmRevoke(key.id)
                                      : confirmRotate(key.id))
                                  }}
                                >
                                  {asking.action === 'revoke' ? '确认撤销' : '确认续期'}
                                </button>
                                <button
                                  type="button"
                                  className="link-button"
                                  disabled={busy === key.id}
                                  onClick={cancel}
                                >
                                  取消
                                </button>
                              </>
                            ) : (
                              <>
                                {/* 续期 first, which is the design's own order: the row's two actions are not
                                   equally weighted, and the one that keeps the machine working comes first. */}
                                {key.revoked_at === null ? (
                                  <button
                                    type="button"
                                    className="link-button"
                                    data-key-action="rotate"
                                    disabled={busy !== null}
                                    onClick={() => ask(key.id, 'rotate')}
                                  >
                                    续期
                                  </button>
                                ) : null}
                                {/* A revoked key cannot be renewed, and the row does not offer it: there is
                                   nothing left to continue, and renewing it would undo a decision somebody made
                                   on purpose (ADR 0039 §决定 3). */}
                                <button
                                  type="button"
                                  className="link-button danger"
                                  data-key-action="revoke"
                                  disabled={busy !== null}
                                  onClick={() => ask(key.id, 'revoke')}
                                >
                                  撤销
                                </button>
                              </>
                            )}
                          </span>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="key-none">{emptySentence}</p>
                  )}
                </div>
              </div>
            </>
          ) : state === 'failed' ? (
            <p className="hint">
              无法加载密钥列表，请稍后重试。此处仅表示列表加载失败，不代表账号异常。
            </p>
          ) : (
            <p className="loading">加载中…</p>
          )}
        </section>
  )
}
