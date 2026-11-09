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
 * sentence that says when to remember this skill), then 运行环境 — the three things a reader
 * weighs together. The version is a value, so it sits in the rail. 申请 is the page's only action,
 * drawn while the ladder still has a rung above this one.
 */
export interface InfoTabProps {
  skill: AuthoredSkill
  whenToUse: string
  compatibility: string
  liveLabel: string
  canAdmin: boolean
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

export default function InfoTab({
  skill,
  whenToUse,
  compatibility,
  liveLabel,
  canAdmin,
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
          {compatibility !== '' ? (
            <section className="info-block">
              <span className="info-label">
                <svg className="info-icon" aria-hidden="true">
                  <use href="#envMark" />
                </svg>
                运行环境
              </span>
              <p className="info-text">{compatibility}</p>
            </section>
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
