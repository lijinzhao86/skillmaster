import type { KeyboardEvent as ReactKeyboardEvent, RefObject } from 'react'
import { expiryText, scopeLabel, type Leaving, type Moment } from './keyModel'

/**
 * 密钥已创建 / 密钥已续期 — the moment its string exists, which is the only time it does.
 *
 * **Markup only.** Revealed, copied and selected are the page's, because the page's document
 * listeners ask whether the string is in the reader's hands before letting them leave; what stands
 * here is that state drawn, plus the buttons that change it.
 */
export interface KeyMomentProps {
  moment: Moment
  /** Whether the string is in the reader's hands (`heldOf`) — the way out's own precondition. */
  held: boolean
  copyLabel: string
  copy: () => Promise<void>
  setMoment: (next: Moment) => void
  finishMoment: () => Promise<void>
  /** The leave question, when the shell tried to navigate away. */
  leaving: Leaving | null
  leaveYes: RefObject<HTMLButtonElement | null>
  stayHere: () => void
  leaveAnyway: () => void
}

export default function KeyMoment({
  moment,
  held,
  copyLabel,
  copy,
  setMoment,
  finishMoment,
  leaving,
  leaveYes,
  stayHere,
  leaveAnyway,
}: KeyMomentProps) {
  return (
        <section className="page">
          <nav className="crumbs" aria-label="面包屑">
            {/* Unfiltered, exactly like 完成: this screen has just created a fact, and a list that hides it
               is a list the reader cannot check (see `finishMoment`). Same three segments as the form. */}
            <a href="/account">设置</a>
            <span className="crumbs-sep" aria-hidden="true">
              /
            </span>
            <a href="/account/keys?filter=all">API 密钥</a>
            <span className="crumbs-sep" aria-hidden="true">
              /
            </span>
            <span className="crumbs-here" aria-current="page">
              {moment.kind === 'created' ? '新建 API 密钥' : '续期密钥'}
            </span>
          </nav>

          <header className="page-head">
            <h1>{moment.kind === 'created' ? '密钥已创建' : '密钥已续期'}</h1>
          </header>

          <div className="key-card">
            <div className="key-head">
              <span className="key-count">密钥详情</span>
            </div>
            <div className="key-body key-detail">
              {moment.kind === 'created' ? (
                <p className="hint">
                  <strong>密钥仅在此时显示一次</strong>
                  ——服务端只保存其摘要，离开本页后将无法再次查看。
                </p>
              ) : (
                <p className="hint">
                  续期完成，<strong>原密钥已立即失效</strong>
                  。新密钥仅在此时显示一次，服务端只保存其摘要。
                </p>
              )}

              {/* The key's own facts, standing under the sentence about the string they belong to — and
                 the two the form had just asked for, which is what the card's title promises. **Not the
                 name**: the heading above already named this key, and the deliverable's own line is
                 「权限 … · 到期 …」 (plus 说明 when one was written). */}
              <p className="hint key-facts">
                {`权限 ${scopeLabel(moment.key.scope)} · ${expiryText(moment.key.expires_at)}`}
                {moment.kind === 'created' &&
                moment.key.note !== null &&
                moment.key.note !== ''
                  ? ` · 说明：${moment.key.note}`
                  : null}
              </p>

              <div className="secret">
                <code
                  id="key-secret"
                  className={moment.revealed ? 'secret-value' : 'secret-value is-masked'}
                >
                  {moment.token}
                </code>
                <button
                  type="button"
                  className="secret-btn"
                  aria-label={moment.revealed ? '隐藏密钥' : '显示密钥'}
                  aria-pressed={moment.revealed}
                  onClick={() => setMoment({ ...moment, revealed: !moment.revealed })}
                >
                  {/* Two icons, one shown, and `v-show` rather than `hidden`: `hidden` is a property of
                     HTML elements, and on an `<svg>` setting it changes nothing — the icon stays where
                     the markup left it. The design demo shipped exactly that bug. */}
                  <svg
                    style={{ display: moment.revealed ? 'none' : undefined }}
                    viewBox="0 0 16 16"
                    fill="currentColor"
                    aria-hidden="true"
                  >
                    <path
                      fillRule="evenodd"
                      clipRule="evenodd"
                      d="M0 8s3-6 8-6 8 6 8 6-3 6-8 6-8-6-8-6Zm1.81.13A13.593 13.593 0 0 1 1.73 8l.082-.13c.326-.51.806-1.187 1.42-1.856C4.494 4.635 6.12 3.5 8 3.5c1.878 0 3.506 1.135 4.77 2.514A13.705 13.705 0 0 1 14.27 8a14.021 14.021 0 0 1-1.502 1.986C11.506 11.365 9.88 12.5 8 12.5c-1.878 0-3.506-1.135-4.77-2.514A13.703 13.703 0 0 1 1.81 8.13ZM11 8a3 3 0 1 1-2.117-2.868 1.5 1.5 0 1 0 1.985 1.985A3 3 0 0 1 11 8Z"
                    />
                  </svg>
                  <svg
                    style={{ display: moment.revealed ? undefined : 'none' }}
                    viewBox="0 0 16 16"
                    fill="currentColor"
                    aria-hidden="true"
                  >
                    <path
                      fillRule="evenodd"
                      clipRule="evenodd"
                      d="M14.776 2.284a.75.75 0 0 0-1.06-1.06l-1.953 1.953C10.707 2.499 9.44 2 8 2 5.506 2 3.533 3.49 2.24 4.86A14.615 14.615 0 0 0 .226 7.576a5.39 5.39 0 0 0-.028.052l-.008.014-.003.005v.002L.85 8l-.663-.35L.002 8l.185.35L.85 8l-.663.35v.002l.002.002.004.007.012.023c.01.02.026.047.046.082a14.417 14.417 0 0 0 .82 1.262c.47.647 1.13 1.447 1.96 2.18L1.22 13.72a.75.75 0 1 0 1.06 1.06L14.776 2.284Zm-10.681 8.56 1.32-1.32a3 3 0 0 1 4.109-4.109l1.148-1.147C9.864 3.8 8.969 3.5 8 3.5c-1.88 0-3.483 1.134-4.67 2.39A13.114 13.114 0 0 0 1.716 8c.13.213.32.508.567.846a11.98 11.98 0 0 0 1.811 1.998Zm9.42-5.166a.75.75 0 0 1 1.053.122c.447.564.901 1.2 1.245 1.85l.185.35-.185.35L15.15 8l.663.351-.001.002-.003.005-.008.014a9.81 9.81 0 0 1-.53.865 14.62 14.62 0 0 1-1.51 1.903C12.467 12.51 10.494 14 8 14a6.939 6.939 0 0 1-1.021-.08l-.02-.002-.006-.001h-.002l.12-.741-.12.74a.75.75 0 0 1 .239-1.48h.002l.011.001a6.024 6.024 0 0 0 .235.03c.158.017.362.033.562.033 1.88 0 3.483-1.134 4.67-2.39a13.11 13.11 0 0 0 1.616-2.115 12.33 12.33 0 0 0-.893-1.263.75.75 0 0 1 .121-1.054Z"
                    />
                  </svg>
                </button>
                {/* `is-copy`/`is-copied` rather than a selector on the label: the label changes with the
                   state, and CSS that hangs off a changing string is a rule that stops matching. The
                   design's own class names, for the two states it draws (§那一刻). */}
                <button
                  type="button"
                  className={
                    moment.copied ? 'secret-btn is-copy is-copied' : 'secret-btn is-copy'
                  }
                  data-key-copy=""
                  aria-label={copyLabel}
                  onClick={() => {
                    void copy()
                  }}
                >
                  <svg
                    style={{ display: moment.copied ? 'none' : undefined }}
                    viewBox="0 0 16 16"
                    fill="currentColor"
                    aria-hidden="true"
                  >
                    <path
                      fillRule="evenodd"
                      clipRule="evenodd"
                      d="M4 9.5H2.5v-7h7V4H11V2a1 1 0 0 0-1-1H2a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1h2V9.5Zm9.5 4h-7v-7h7v7ZM5 6a1 1 0 0 1 1-1h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6Z"
                    />
                  </svg>
                  <svg
                    style={{ display: moment.copied ? undefined : 'none' }}
                    viewBox="0 0 16 16"
                    fill="currentColor"
                    aria-hidden="true"
                  >
                    <path
                      fillRule="evenodd"
                      clipRule="evenodd"
                      d="M12.78 4.62a.75.75 0 0 1 0 1.06l-6.097 6.097a.75.75 0 0 1-1.069-.009L3.211 9.284a.75.75 0 1 1 1.078-1.043l1.873 1.936L11.72 4.62a.75.75 0 0 1 1.06 0Z"
                    />
                  </svg>
                </button>
              </div>

              {moment.selected ? (
                <p className="hint" role="status">
                  已选中，请按你的复制快捷键。
                </p>
              ) : null}
              <p className="hint">如未保存：请撤销该密钥后重新创建，或对原密钥直接续期。</p>
            </div>
          </div>

          {/* Outside the card, below it, which is where the reference product puts its own way out. It
             is disabled until the string has been copied, and it says its own precondition rather than
             being a greyed-out "完成" that does not explain itself.

             **And it is where leaving is asked about**, because it is already the block that takes you
             out: a reader who clicks the crumb or the navigation while the string is still here gets this
             block's own question in place of its button. Escape answers 留在这一屏, which is what a
             question of this shape does everywhere else on this page. */}
          <div
            className="key-exit"
            onKeyDown={(event: ReactKeyboardEvent<HTMLDivElement>) => {
              if (event.key === 'Escape') {
                stayHere()
              }
            }}
          >
            {leaving === null ? (
              <button
                type="button"
                className="button"
                disabled={!held}
                onClick={() => {
                  void finishMoment()
                }}
              >
                {held ? '完成' : '复制后方可完成'}
              </button>
            ) : (
              <>
                <span className="hint">这把密钥还没复制——离开后取不回来，只能撤销后重建。</span>
                <button
                  ref={leaveYes}
                  type="button"
                  className="link-button danger"
                  onClick={leaveAnyway}
                >
                  仍然离开
                </button>
                <button type="button" className="link-button" onClick={stayHere}>
                  留在这一屏
                </button>
              </>
            )}
          </div>
        </section>
  )
}
