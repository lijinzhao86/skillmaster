import { useRef, useState } from 'react'
import type { AuthoredVersion } from '../../api/types'
import {
  canEditDraft,
  canMaintain,
  canRevoke,
} from '../../versionActions'
import { useVersionActions } from '../../hooks/useVersionActions'
import { statusOf, versionRef, versionSuffix, versionTitle, type BumpKind } from '../../version'
import ConfirmDialog from '../ConfirmDialog'
import VersionPicker from '../VersionPicker'

/**
 * The 操作 tab: every state-changing action this version and this rung allow.
 *
 * **One action at a time, each with its consequence above it.** Ordinary actions come first;
 * 下架 — the one that takes something away from callers — is alone in the danger block at the foot.
 * A draft's 丢弃 is *not* dangerous (ADR 0060): it ends only the author's own version, the pointer
 * does not move and no consumer can read a draft. Its button is outlined, its confirm button is not
 * red, and the sheet's first sentence says the version cannot be recovered.
 *
 * **The sheet covers publish, rollback, discard and 下架** — 取消 as the default focus, Esc as
 * cancel, and the focus handed back to the button that opened it. Publish/rollback/discard use a
 * normal confirm button; 下架 uses the danger tone. **History 上线 and 重新上架 are the design's
 * two-step in place** (「确认后立即生效。」 / 取消 / 确认…): the first press only expands them.
 */
export interface VersionActionPanelProps {
  namespace: string
  name: string
  version: AuthoredVersion
  versions: AuthoredVersion[]
  rung: string
  username: string | null
  live: AuthoredVersion | null
  /**
   * The address's preselection (`?bump=`), from a CLI handoff. Absent means the design's default,
   * 只是修正. It only seeds the form: the radios stay the author's to change, and nothing writes it
   * back to the address (v11 ADR 0074).
   */
  initialBump?: BumpKind
}

export default function VersionActionPanel({
  namespace,
  name,
  version,
  versions,
  rung,
  username,
  live,
  initialBump,
}: VersionActionPanelProps) {
  const status = statusOf(version)
  const maintain = canMaintain(rung)
  const editableDraft = canEditDraft(version, rung, username)
  const isDraft = status === 'draft'
  /**
   * A draft from before v6 that already declared a number in `SKILL.md`.
   *
   * Such a row keeps its number; the publish request must omit `release_name`, because assigning a
   * second one is refused (TD §版本号的时机). The form would otherwise ask for a number the server
   * will not take.
   */
  const legacyNamedDraft = isDraft && version.name !== null
  const isLive = status === 'live'
  const isHistory = status === 'history'
  const isRevoked = status === 'revoked'

  const candidates = versions
    .filter((v) => statusOf(v) === 'history')
    .sort((a, b) => (b.first_published_at ?? '').localeCompare(a.first_published_at ?? ''))

  const showPublish = isDraft && maintain
  const showDiscard = isDraft && editableDraft
  const showRollback = isLive && maintain
  const showRestore = isRevoked && maintain
  const showRepublish = isHistory && maintain
  const showRevoke = canRevoke(version, rung)
  const hasActions =
    showPublish ||
    showDiscard ||
    showRollback ||
    showRestore ||
    showRepublish

  /* --------------------------------------------------------------- publish form */

  const [rollbackChoice, setRollbackChoice] = useState<string>(() => {
    const first = candidates[0]
    return first === undefined ? '' : versionSuffix(first)
  })

  function suggestion(): Record<'patch' | 'minor' | 'major', string> {
    const liveName = live?.name ?? null
    if (liveName === null) {
      return { patch: '1.0.0', minor: '1.0.0', major: '1.0.0' }
    }
    const base = liveName.split('.').map((part) => Number.parseInt(part, 10) || 0)
    const [major = 0, minor = 0, patch = 0] = base
    return {
      patch: `${major}.${minor}.${patch + 1}`,
      minor: `${major}.${minor + 1}.0`,
      major: `${major + 1}.0.0`,
    }
  }

  type Bump = BumpKind

  const suggested = suggestion()
  const initialBumpKind = initialBump ?? 'patch'
  const [bump, setBump] = useState<Bump>(initialBumpKind)
  const [releaseName, setReleaseName] = useState(
    live === null ? '1.0.0' : suggested[initialBumpKind],
  )
  const [releaseInvalid, setReleaseInvalid] = useState(false)

  /**
   * The live version as the publish block names it: the number first, the author's name in brackets.
   *
   * That is the design's own order in this one sentence (「当前线上是 @1.2.3（split 范围语法）」),
   * the reverse of {@link versionRef}'s "name first" used where the sentence is about the name.
   */
  const liveRef = ((): string => {
    if (live === null) {
      return ''
    }
    return live.name === null
      ? versionRef(live, rung)
      : `@${live.name}（${versionTitle(live, rung)}）`
  })()

  const BUMP_CHOICES: Array<[Bump, string, string]> = [
    ['patch', '只是修正', '修好了问题，调用方式不变'],
    ['minor', '新增能力', '多了新东西，旧调用照旧能用'],
    ['major', '旧调用会失效', '旧的调用要改，才能继续用'],
  ]

  function chooseBump(kind: Bump): void {
    setBump(kind)
    setReleaseName(suggested[kind])
    setReleaseInvalid(false)
  }

  /* ----------------------------------------------------------- confirmation state */

  type ActionKind = 'publish' | 'rollback' | 'discard' | 'revoke'

  interface Ask {
    kind: ActionKind
    title: string
    body: string
    confirmLabel: string
    pendingLabel: string
    danger: boolean
    field?: string
    optionalField?: boolean
    placeholder?: string
    run: (value: string) => Promise<boolean>
  }

  /**
   * The inline two-step, for the two actions the design keeps out of the sheet: 历史版本「上线」 and
   * 已下架「重新上架」. The sheet is for 回滚, 上线草稿, 丢弃 and the dangerous actions; these two
   * expand 「确认后立即生效。取消 / 确认…」 in place and only the second press submits.
   */
  const [inline, setInline] = useState<'republish' | 'restore' | null>(null)

  const [ask, setAsk] = useState<Ask | null>(null)
  const [open, setOpen] = useState(false)
  const opener = useRef<HTMLElement | null>(null)

  const { pending, error, setError, publish, discard, revoke, restore } = useVersionActions(
    namespace,
    name,
  )

  function openAsk(
    kind: ActionKind,
    options: Omit<Ask, 'kind' | 'run'>,
    run: (value: string) => Promise<boolean>,
  ): void {
    opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    setError(null)
    setAsk({ kind, ...options, run })
    setOpen(true)
  }

  function close(): void {
    setOpen(false)
    setAsk(null)
    setError(null)
    requestAnimationFrame(() => opener.current?.focus())
  }

  async function confirm(value: string): Promise<void> {
    if (ask === null) {
      return
    }
    await ask.run(value)
    // A successful run reloads; a failed one stays open with the reason under the body.
  }

  function askPublish(): void {
    const title = versionTitle(version, rung)
    if (legacyNamedDraft) {
      // A pre-v6 draft keeps the number it declared; sending one would be refused as a second name.
      openAsk(
        'publish',
        {
          title: `上线 ${title}？`,
          body: `上线后，这一版沿用版本号 @${version.name}，成为当前版本；不指定版本的调用会立刻读到它。名称与说明在这一刻锁定。`,
          confirmLabel: '确认上线',
          pendingLabel: '正在上线…',
          danger: false,
        },
        () => publish(versionSuffix(version)),
      )
      return
    }
    const release = releaseName.trim()
    // The shape is checked here once; the server owns SemVer 2.0.0 and the collision rules. The
    // pattern is the server's (`common/SemVer.java`), build metadata included: `1.2.3+build.5` is a
    // valid name, and rejecting it here refused a number the server accepts.
    if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/.test(release)) {
      setReleaseInvalid(true)
      return
    }
    openAsk(
      'publish',
      {
        title: `上线 ${title}？`,
        body: `上线后，这一版会拿到版本号 @${release}，成为当前版本；不指定版本的调用会立刻读到它。名称与说明在这一刻锁定——调用者会读到它们，上线后不能再改。`,
        confirmLabel: '确认上线',
        pendingLabel: '正在上线…',
        danger: false,
      },
      () => publish(versionSuffix(version), release),
    )
  }

  function askRollback(): void {
    const target = candidates.find((v) => versionSuffix(v) === rollbackChoice)
    if (target === undefined) {
      return
    }
    openAsk(
      'rollback',
      {
        title: `回滚到 ${versionTitle(target, rung)}？`,
        body: `${versionRef(target, rung)} 会重新成为当前版本，${versionRef(version, rung)} 变成历史版本。不会新建版本，版本号与内容不变；指定 ${version.name === null ? '这一版' : `@${version.name}`} 的调用仍可读。`,
        confirmLabel: '确认回滚',
        pendingLabel: '正在回滚…',
        danger: false,
      },
      // A rollback moves the pointer, not this page: the design keeps this version's page open and
      // re-renders it as history, so the composable reloads in place instead of navigating to the
      // target's number.
      () => publish(versionSuffix(target), undefined, true),
    )
  }

  /** Expanding the two-step clears a failure left by the previous attempt. */
  function openInline(kind: 'republish' | 'restore'): void {
    setError(null)
    setInline(kind)
  }

  async function confirmInline(kind: 'republish' | 'restore'): Promise<void> {
    setError(null)
    // A success reloads the page (publish navigates to the version's number; restore reloads), so
    // only the failure path returns here — with the sentence under the buttons.
    if (kind === 'republish') {
      await publish(versionSuffix(version))
    } else {
      await restore(versionSuffix(version))
    }
  }

  function askDiscard(): void {
    openAsk(
      'discard',
      {
        title: '丢弃这个版本？',
        body: '丢弃后无法找回：它不会再上线；维护及以上仍能在版本表里看到这条记录，没有恢复的入口。',
        confirmLabel: '确认丢弃',
        pendingLabel: '正在丢弃…',
        danger: false,
      },
      () => discard(versionSuffix(version)),
    )
  }

  function askRevoke(): void {
    openAsk(
      'revoke',
      {
        title: `下架 ${versionRef(version, rung)}？`,
        body: '下架后，使用这个版本的调用会立刻失败，直到你重新上架为止。',
        confirmLabel: '确认下架',
        pendingLabel: '正在下架…',
        danger: true,
        field: '原因（可选，只有维护者看得到）',
        optionalField: true,
        placeholder: '例如：这个版本有安全问题，需要让调用立刻失败',
      },
      (reason) => revoke(versionSuffix(version), reason === '' ? undefined : reason),
    )
  }

  return (
    <div className="vp-actions-main">
      {hasActions ? (
        <section className="vp-module" id="vpActions">
          {/* 上线 / 回滚 / 重新上架 / 丢弃, one block each. */}
          {showPublish ? (
            <div className="vp-action">
              <h3>上线</h3>
              {live !== null ? (
                <p>
                  当前线上是 {liveRef}。上线后，不指定版本的调用会立刻读到这一版；指定旧版本的调用不受影响。
                </p>
              ) : (
                <p>还没有线上版本。这是这个 skill 第一次上线，版本号从 1.0.0 起步。</p>
              )}

              {legacyNamedDraft ? (
                <p className="vp-note">
                  这一版在提交时已经带了版本号 @{version.name}，上线时沿用，不再指定。
                </p>
              ) : (
                <>
                  {live !== null ? (
                    <>
                      <span className="confirm-label">这次改动会影响旧的调用吗</span>
                      <div className="choices">
                        {BUMP_CHOICES.map((choice) => (
                          <label key={choice[0]} className="choice">
                            <input
                              type="radio"
                              name="vp-bump"
                              value={choice[0]}
                              checked={bump === choice[0]}
                              onChange={() => chooseBump(choice[0])}
                            />
                            <span className="choice-body">
                              <span className="choice-title">{choice[1]}</span>
                              <span className="choice-note">{choice[2]} · {suggested[choice[0]]}</span>
                            </span>
                          </label>
                        ))}
                      </div>
                    </>
                  ) : null}

                  <label className="confirm-field">
                    <span className="confirm-label">版本号</span>
                    <input
                      className={
                        releaseInvalid
                          ? 'confirm-input is-mono is-invalid'
                          : 'confirm-input is-mono'
                      }
                      type="text"
                      autoComplete="off"
                      spellCheck={false}
                      value={releaseName}
                      onChange={(event) => {
                        setReleaseName(event.target.value)
                        setReleaseInvalid(false)
                      }}
                    />
                  </label>
                </>
              )}

              <div className="vp-action-acts">
                <button type="button" className="button" onClick={askPublish}>
                  {legacyNamedDraft || releaseName.trim() === '' ? '上线' : `上线 @${releaseName.trim()}`}
                </button>
              </div>
            </div>
          ) : null}

          {showRollback ? (
            <div className="vp-action">
              <h3>回滚</h3>
              {candidates.length > 0 ? (
                <p>
                  {`所选版本重新上线，${versionTitle(version, rung)} 变成历史版本。不会新建版本，版本号与内容不变。`}
                </p>
              ) : (
                <p>没有可以回滚的版本——这个 skill 只有当前线上版本。</p>
              )}
              {candidates.length > 0 ? (
                <div className="vp-action-acts">
                  <VersionPicker
                    versions={candidates}
                    selected={rollbackChoice}
                    ariaLabel="回滚到哪一版"
                    onSelect={setRollbackChoice}
                  />
                  <button type="button" className="button" onClick={askRollback}>回滚</button>
                </div>
              ) : null}
            </div>
          ) : null}

          {showRepublish ? (
            <div className="vp-action">
              <h3>上线</h3>
              <p>上线后，这个版本成为当前版本，原来的线上版本变成历史版本；说明与版本号都不再改。</p>
              {inline !== 'republish' ? (
                <div className="vp-action-acts">
                  <button
                    type="button"
                    className="button"
                    onClick={() => openInline('republish')}
                  >
                    上线 {versionTitle(version, rung)}
                  </button>
                </div>
              ) : (
                <div className="vp-action-acts">
                  <span className="is-warn">确认后立即生效。</span>
                  <button
                    type="button"
                    className="button is-quiet"
                    disabled={pending}
                    onClick={() => setInline(null)}
                  >
                    取消
                  </button>
                  <button
                    type="button"
                    className="button"
                    disabled={pending}
                    onClick={() => void confirmInline('republish')}
                  >
                    {pending ? '正在上线…' : '确认上线'}
                  </button>
                </div>
              )}
              {inline !== null && error !== null ? (
                <p className="confirm-error" role="alert">{error}</p>
              ) : null}
            </div>
          ) : null}

          {showRestore ? (
            <div className="vp-action">
              <h3>重新上架</h3>
              <p>重新上架后，它回到历史版本，调用者又能读它了；要让它重新对外，还需要再上线一次。</p>
              {inline !== 'restore' ? (
                <div className="vp-action-acts">
                  <button
                    type="button"
                    className="button"
                    onClick={() => openInline('restore')}
                  >
                    重新上架
                  </button>
                </div>
              ) : (
                <div className="vp-action-acts">
                  <span className="is-warn">确认后立即生效。</span>
                  <button
                    type="button"
                    className="button is-quiet"
                    disabled={pending}
                    onClick={() => setInline(null)}
                  >
                    取消
                  </button>
                  <button
                    type="button"
                    className="button"
                    disabled={pending}
                    onClick={() => void confirmInline('restore')}
                  >
                    {pending ? '正在重新上架…' : '确认重新上架'}
                  </button>
                </div>
              )}
              {inline !== null && error !== null ? (
                <p className="confirm-error" role="alert">{error}</p>
              ) : null}
            </div>
          ) : null}

          {showDiscard ? (
            <div className="vp-action">
              <h3>丢弃</h3>
              <p>丢弃后无法找回：它不会再上线；维护及以上仍能在版本表里看到这条记录，没有恢复的入口。</p>
              <div className="vp-action-acts">
                <button type="button" className="button is-quiet" onClick={askDiscard}>
                  丢弃
                </button>
              </div>
            </div>
          ) : null}
        </section>
      ) : null}

      {showRevoke ? (
        <section className="vp-module is-danger" id="vpDanger">
          <p className="vp-eyebrow is-danger">危险操作</p>
          <div className="danger-box">
            <div className="danger-row">
              <div className="danger-what">
                <p className="danger-title">下架</p>
                <p className="hint">下架后，使用这个版本的调用会立刻失败，直到你重新上架为止。</p>
              </div>
              <div className="danger-acts">
                <button type="button" className="button is-danger" onClick={askRevoke}>下架</button>
              </div>
            </div>
          </div>
        </section>
      ) : null}

      <ConfirmDialog
        open={open}
        title={ask?.title ?? ''}
        body={ask?.body ?? ''}
        confirmLabel={ask?.confirmLabel ?? ''}
        pendingLabel={ask?.pendingLabel}
        danger={ask?.danger ?? true}
        field={ask?.field}
        optionalField={ask?.optionalField ?? false}
        placeholder={ask?.placeholder ?? ''}
        pending={pending}
        error={error}
        onConfirm={confirm}
        onCancel={close}
      />
    </div>
  )
}
