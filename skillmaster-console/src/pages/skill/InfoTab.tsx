import type {
  AuthoredSkill,
  CategoryRow,
  Rung,
  SkillTagGroups as SkillTagGroupsData,
} from '../../api/types'
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
        </aside>
      </section>

      {/* 申请: the page's only action, drawn while the ladder still has a rung above this one. */}
      {showAsk ? (
        <section className="ask">
          <h3>申请</h3>
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
        </section>
      ) : null}
    </div>
  )
}
