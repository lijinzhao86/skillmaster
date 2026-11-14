import { useState } from 'react'
import type {
  AuthoredSkill,
  CategoryRow,
  Rung,
  SkillTagGroups as SkillTagGroupsData,
} from '../../api/types'
import type { SignalLicense, SignalOrigin } from '../../api/signals'
import { rungLabel } from '../../rungs'
import RungMenu from '../../components/RungMenu'
import SkillTagGroups from '../../components/skill/SkillTagGroups'
import type { EditGroup } from '../../components/skill/SkillTagGroups'

/**
 * 信息 = the `/metadata` layer. 描述 first as prose, then 适用场景 lifted out (it is the one
 * sentence that says when to remember this skill), then 我的适用场景, 预期结果, 兼容性 and the
 * attribute chips — the things a reader weighs together, in that order (v65). The version is a
 * value, so it sits in the rail. 申请 is the page's only action, drawn while the ladder still has a
 * rung above this one.
 */
export interface InfoTabProps {
  skill: AuthoredSkill
  whenToUse: string
  compatibility: string
  liveLabel: string
  canAdmin: boolean
  /** v59: the sentence is drawn only for an effective library entry. */
  inLibraryActive: boolean
  /** Whether the caller may change the skill itself; the empty state explains the two sets only to them. */
  canWriteWhenToUse: boolean
  /** null until read; '' is the empty state. */
  myWhenToUse: string | null
  myWhenToUseError: string | null
  myWhenToUseSaving: boolean
  onMyWhenToUseSave: (text: string) => Promise<boolean>
  onMyWhenToUseClear: () => Promise<boolean>
  groups: SkillTagGroupsData | null
  categories: CategoryRow[]
  myTagDefs: Array<{ id: string; name: string }>
  publicTagDefs: Array<{ id: string; name: string }>
  initialEdit: EditGroup | null
  membershipNote: string
  onLoadMyTagDefs: () => void
  onLoadPublicTagDefs: () => void
  onCategoryChange: (key: string | null) => void
  onPublicTagAdd: (tagName: string) => void
  onPublicTagRemove: (tagName: string) => void
  onMyTagAdd: (tagName: string) => void
  onMyTagRemove: (tagName: string) => void
  showAsk: boolean
  askWhy: string
  pendingRung: Rung | null
  askedRung: Rung
  above: Array<{ name: Rung; label: string }>
  askNote: string
  askLabel: string
  onAskedRungChange: (rung: Rung) => void
  onSubmitAsk: () => Promise<void>
  onWithdrawAsk: () => Promise<void>
  origin: SignalOrigin | null
  license: SignalLicense | null
  /** v54: the skill this one was copied from, or null when it was written rather than copied. */
  copiedFrom: { address: string; version: string } | null
}

/** A copy's source version: its number, or the abbreviated digest (ADR 0033's spelling). */
function copiedVersionLabel(version: string): string {
  return version.startsWith('sha256:')
    ? `sha256:${version.slice('sha256:'.length, 'sha256:'.length + 8)}…`
    : version
}

/** The console page for a `namespace/name` snapshot. The source may no longer exist; a 404 there
    says so, and the address on screen is still the historical fact. */
function sourceHref(address: string): string {
  const cut = address.indexOf('/')
  const namespace = cut < 0 ? address : address.slice(0, cut)
  const name = cut < 0 ? '' : address.slice(cut + 1)
  return `/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`
}

/* 摘要行只说「是什么许可」：允许与义务是 `skill license` 的事，这一页不替人读条款。
   没有许可名（未声明）就什么都不写，摘要只留来源。 */
function licenseNameOf(license: SignalLicense): string {
  if (license.status === 'missing') return ''
  /* NOASSERTION is SPDX's word for "an unrecognised value", not a license name; a declaration
     that is not text lands here (v50's reader keeps it rather than calling it absent). */
  if (license.spdx === 'NOASSERTION') return '其他许可'
  if (license.status === 'ok' || license.status === 'declared-only') return license.spdx
  if (license.status === 'other') return '其他许可'
  return ''
}

/* **200 Unicode code points, not UTF-16 units**: a Han character, a Latin letter and a punctuation
   mark each count as one, and an emoji is one rather than two. HTML's `maxlength` counts UTF-16
   units, so the clamp is done here instead. */
const WHEN_MAX = 200
const clampWhen = (text: string): string => [...text].slice(0, WHEN_MAX).join('')
const normalizeWhen = (text: string): string => text.replace(/\s+/g, ' ').trim()

function MyWhenToUseBlock({
  value,
  error,
  saving,
  canWrite,
  hasAuthorWhen,
  onSave,
  onClear,
}: {
  value: string
  error: string | null
  saving: boolean
  canWrite: boolean
  /** Whether the skill itself has a 适用场景 above; the extra empty-state sentence points at it. */
  hasAuthorWhen: boolean
  onSave: (text: string) => Promise<boolean>
  onClear: () => Promise<boolean>
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')

  return (
    <section className="info-block" id="infoMyWhen">
      <div className="info-label-row">
        <span className="info-label">
          <svg className="info-icon" aria-hidden="true">
            <use href="#personMark" />
          </svg>
          我的适用场景
        </span>
        {!editing ? (
          <button
            type="button"
            className="info-edit"
            aria-label={value === '' ? '添加我的适用场景' : '编辑我的适用场景'}
            title={value === '' ? '添加我的适用场景' : '编辑我的适用场景'}
            onClick={() => {
              setDraft(value)
              setEditing(true)
            }}
          >
            {value === '' ? (
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M12 6v12M6 12h12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
              </svg>
            ) : (
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M4.5 19.5l.8-3.6L15.8 5.4a1.6 1.6 0 0 1 2.3 0l.5.5a1.6 1.6 0 0 1 0 2.3L8.1 18.7z"
                  stroke="currentColor"
                  strokeWidth="1.7"
                  strokeLinejoin="round"
                />
                <path d="M14.4 6.8l2.8 2.8" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
              </svg>
            )}
          </button>
        ) : null}
      </div>
      {editing ? (
        <div className="info-when-editor">
          <textarea
            className="info-when-input"
            aria-label="我的适用场景"
            rows={4}
            value={draft}
            onChange={(event) => setDraft(clampWhen(event.target.value))}
          />
          <div className="info-when-actions">
            <span className="info-when-count">
              {[...draft].length}/{WHEN_MAX}
            </span>
            <button type="button" className="link-button" onClick={() => setEditing(false)}>
              取消
            </button>
            {value !== '' ? (
              <button
                type="button"
                className="link-button"
                onClick={() => {
                  void (async () => {
                    if (await onClear()) {
                      setEditing(false)
                    }
                  })()
                }}
              >
                清除
              </button>
            ) : null}
            <button
              type="button"
              className="button"
              disabled={saving || normalizeWhen(draft) === ''}
              onClick={() => {
                void (async () => {
                  // Keep the editor open until the write resolves: a field-level refusal is
                  // rendered inside it, and closing first would hide the only sentence that says
                  // what to fix (v59 PRD #10).
                  if (await onSave(normalizeWhen(draft))) {
                    setEditing(false)
                  }
                })()
              }}
            >
              保存
            </button>
          </div>
          {error !== null ? <p className="field-error">{error}</p> : null}
        </div>
      ) : value !== '' ? (
        <p className="info-text" id="infoMyWhenText">
          {value}
        </p>
      ) : (
        <p className="info-empty" id="infoMyWhenEmpty">
          写一句你平时会怎么找它；搜索时先按你的说法找。
          {canWrite && hasAuthorWhen
            ? '上面那句随 skill 发布；这句只影响你自己的搜索，改了不用出新版本。'
            : ''}
          <button
            type="button"
            className="link-button"
            onClick={() => {
              setDraft('')
              setEditing(true)
            }}
          >
            添加
          </button>
        </p>
      )}
    </section>
  )
}

export default function InfoTab({
  skill,
  whenToUse,
  compatibility,
  liveLabel,
  canAdmin,
  inLibraryActive,
  canWriteWhenToUse,
  myWhenToUse,
  myWhenToUseError,
  myWhenToUseSaving,
  onMyWhenToUseSave,
  onMyWhenToUseClear,
  groups,
  categories,
  myTagDefs,
  publicTagDefs,
  initialEdit,
  membershipNote,
  onLoadMyTagDefs,
  onLoadPublicTagDefs,
  onCategoryChange,
  onPublicTagAdd,
  onPublicTagRemove,
  onMyTagAdd,
  onMyTagRemove,
  showAsk,
  askWhy,
  pendingRung,
  askedRung,
  above,
  askNote,
  askLabel,
  onAskedRungChange,
  onSubmitAsk,
  onWithdrawAsk,
  origin,
  license,
  copiedFrom,
}: InfoTabProps) {
  return (
    <div className="tab-panel" data-tab="info">
      <section className="info-pane">
        <div className="info-main">
          <section className="info-block">
            <span className="info-label">
              <svg className="info-icon" aria-hidden="true">
                <use href="#descMark" />
              </svg>
              描述
            </span>
            <p className="info-text">{skill.description}</p>
          </section>
          {whenToUse !== '' ? (
            <section className="info-block">
              <span className="info-label">
                <svg className="info-icon" aria-hidden="true">
                  <use href="#clockMark" />
                </svg>
                适用场景
              </span>
              <p className="info-text">{whenToUse}</p>
            </section>
          ) : null}
          {inLibraryActive && myWhenToUse !== null ? (
            <MyWhenToUseBlock
              value={myWhenToUse}
              error={myWhenToUseError}
              saving={myWhenToUseSaving}
              canWrite={canWriteWhenToUse}
              hasAuthorWhen={whenToUse !== ''}
              onSave={onMyWhenToUseSave}
              onClear={onMyWhenToUseClear}
            />
          ) : null}
          {skill.outcome !== undefined && skill.outcome !== null && skill.outcome !== '' ? (
            /* **预期结果 after the two 适用场景 blocks** (v65, 2026-11-13 用户定): read when to use
               it and when *I* use it first, then what using it gets me. It is the platform's claim,
               not the author's description, and it does not go on the result row. */
            <section className="info-block" id="infoOutcome">
              <span className="info-label">
                <svg className="info-icon" aria-hidden="true">
                  <use href="#outcomeMark" />
                </svg>
                预期结果
              </span>
              <p className="info-text">{skill.outcome}</p>
            </section>
          ) : null}
          {compatibility !== '' ? (
            <section className="info-block">
              <span className="info-label">
                <svg className="info-icon" aria-hidden="true">
                  <use href="#envMark" />
                </svg>
                {/* **Renamed from 「运行环境」** (v65): the platform's attributes now say which
                    runtime a skill needs; this line is the author's own free-form note, so it
                    reads as one. */}
                兼容性
              </span>
              <p className="info-text">{compatibility}</p>
            </section>
          ) : null}
          {(skill.labels ?? []).length > 0 ? (
            /* **Attributes last** (v65, 2026-11-13 用户定): the filterable summary after the prose
               and the author's own words. Short names only — the full sentence stays in the
               vocabulary. Each chip filters the public results by that one attribute. */
            <div className="info-attributes-row" aria-label="属性">
              {(skill.labels ?? []).map((label) =>
                label.filterable === false ? (
                  /* Display-only: the word exists in the vocabulary but cannot be filtered on, so
                     it is drawn as a chip rather than a link (v65 TD §扩展). */
                  <span
                    className="chip is-attr"
                    key={label.key + ':' + label.value}
                    data-group={label.group}
                  >
                    {label.label}
                  </span>
                ) : (
                  <a
                    className="chip is-attr"
                    key={label.key + ':' + label.value}
                    href={'/discover?label=' + encodeURIComponent(label.key + ':' + label.value)}
                    data-group={label.group}
                  >
                    {label.label}
                  </a>
                ),
              )}
            </div>
          ) : null}
        </div>

        <aside className="info-rail">
          <section className="info-rail-item">
            <svg className="info-rail-icon" aria-hidden="true">
              <use href="#versionMark" />
            </svg>
            <span className="info-rail-label">版本</span>
            <span className="info-rail-value">{liveLabel}</span>
          </section>
          {/* Category → public tags → my tags. The last group is drawn only while the
              library entry is effective; the read says which it is. */}
          <SkillTagGroups
            groups={groups}
            categories={categories}
            canEditCategories={canAdmin}
            canEditPublicTags={canAdmin}
            myTagDefs={myTagDefs}
            publicTagDefs={publicTagDefs}
            initialEdit={initialEdit}
            membershipNote={membershipNote}
            onLoadMyTagDefs={onLoadMyTagDefs}
            onLoadPublicTagDefs={onLoadPublicTagDefs}
            onCategoryChange={onCategoryChange}
            onPublicTagAdd={onPublicTagAdd}
            onPublicTagRemove={onPublicTagRemove}
            onMyTagAdd={onMyTagAdd}
            onMyTagRemove={onMyTagRemove}
          />
          {/* **来源与许可：右栏、标签下面的一次展开**（2026-11-06 用户定）：它们是「查一下」
              的那一类，和分类、标签同一个位置。默认收起，只露标题与「GitHub · MIT」。 */}
          {copiedFrom !== null || origin !== null || license !== null ? (
            <details className="info-source" id="infoSource">
              <summary className="info-source-head">
                <span className="info-source-title">来源与许可</span>
                <span className="info-source-hint">
                  {/* A copy's nearer provenance replaces the external origin in the one-line
                      summary; the body still shows both, in full (design's own rule). */}
                  {[
                    copiedFrom === null ? origin?.label : `复制自 ${copiedFrom.address}`,
                    license === null ? '' : licenseNameOf(license),
                  ]
                    .filter((part) => part !== undefined && part !== '')
                    .join(' · ')}
                </span>
                <svg className="info-source-caret" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M7 10l5 5 5-5"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              </summary>
              <div className="info-source-body">
                {copiedFrom !== null ? (
                  /* **A snapshot, not a live link.** The address is what it read at copy time; the
                     source may have been renamed or deleted since, and this page must keep saying
                     what actually happened (ADR 0173 §后果). */
                  <div className="info-fact" id="infoCopiedFrom">
                    <span className="info-fact-label">复制自</span>
                    <span className="info-fact-value">
                      <a href={sourceHref(copiedFrom.address)}>{copiedFrom.address}</a>
                      <span className="info-fact-note">
                        {' · @'}
                        {copiedVersionLabel(copiedFrom.version)}
                      </span>
                    </span>
                  </div>
                ) : null}
                {origin !== null ? (
                  /* **The source is only platform and repository** (2026-11-06): commit, metrics
                     and the check time stay in `skill show` — the rail is "look it up", not audit. */
                  <div className="info-fact" id="infoOrigin">
                    <span className="info-fact-label">来源</span>
                    <span className="info-fact-value">
                      {origin.label}
                      {origin.locator !== null ? (
                        <span className="info-fact-note">
                          {' · '}
                          {origin.url === null ? (
                            origin.locator
                          ) : (
                            <a href={origin.url} target="_blank" rel="noreferrer">
                              {origin.locator}
                            </a>
                          )}
                        </span>
                      ) : null}
                    </span>
                  </div>
                ) : null}
                {license !== null && license.status !== 'missing' ? (
                  <div className="info-fact" id="infoLicense">
                    <span className="info-fact-label">许可证</span>
                    <span className="info-fact-value">
                      {license.status === 'declared-only' && license.spdx !== 'NOASSERTION' ? (
                        <>{license.spdx}</>
                      ) : license.status === 'other' || license.spdx === 'NOASSERTION' ? (
                        <>
                          其他许可
                          {license.url !== null ? (
                            <>
                              {' '}
                              · <a href={license.url} target="_blank" rel="noreferrer">查看许可全文</a>
                            </>
                          ) : null}
                        </>
                      ) : (
                        <>
                          {/* **Only the name, the copyright line and the full-text link**
                              (2026-11-06): what you may do sits in the license itself and in
                              `skmr skill license`; this page answers "which license is it". */}
                          {license.spdx}
                          {license.copyright !== null ? (
                            <span className="info-fact-note"> · {license.copyright}</span>
                          ) : null}
                          {license.url !== null ? (
                            <>
                              {' '}
                              · <a href={license.url} target="_blank" rel="noreferrer">查看许可全文</a>
                            </>
                          ) : null}
                        </>
                      )}
                    </span>
                  </div>
                ) : null}
              </div>
            </details>
          ) : null}
        </aside>
      </section>

      {/* 申请: the page's only action, drawn while the ladder still has a rung above this one.
          **One line that expands** (2026-11-05): it sits at the prose's tail instead of a card of
          its own, so the page's first screen answers only 「它是什么」. */}
      {showAsk ? (
        <details className="ask" id="askBlock">
          <summary className="ask-summary">
            <span className="ask-summary-title">需要更高权限？</span>
            <span className="ask-summary-hint">申请更高的档位</span>
            <svg className="ask-summary-caret" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path
                d="M7 10l5 5 5-5"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </summary>
          <p className="hint">
            {askWhy}
            {pendingRung === null ? '如需更高的档位，请在下方申请。' : null}
          </p>
          {pendingRung === null ? (
            <>
              <div className="ask-form">
                <span id="askRoleLabel" className="ask-label">
                  申请档位
                </span>
                <RungMenu
                  options={above}
                  value={askedRung}
                  label="申请档位"
                  labelledby="askRoleLabel"
                  // The menu's options are `above`, which is a list of rungs — so the value it
                  // reports is one of them.
                  onChange={(value) => onAskedRungChange(value as Rung)}
                />
                <button
                  type="button"
                  className="button"
                  onClick={() => {
                    void onSubmitAsk()
                  }}
                >
                  {askLabel}
                </button>
              </div>
              <p className="ask-note">{askNote}</p>
            </>
          ) : (
            <div className="ask-pending">
              <span className="chip is-plain">
                <span className="dot" />
                <span className="chip-text">等待处理</span>
              </span>
              <span className="ask-pending-text">
                已提交「{rungLabel(pendingRung)}」的申请，由 {skill.namespace.slug}{' '}
                或「管理」审批。
              </span>
              <button
                type="button"
                className="link-button"
                onClick={() => {
                  void onWithdrawAsk()
                }}
              >
                撤回申请
              </button>
            </div>
          )}
        </details>
      ) : null}
    </div>
  )
}
