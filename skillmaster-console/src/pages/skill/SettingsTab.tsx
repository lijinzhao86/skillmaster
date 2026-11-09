import type { RefObject } from 'react'
import type { AuthoredSkill, Standing } from '../../api/types'
import SkillCollaborators from '../../components/SkillCollaborators'
import SecretsPane from '../../components/skill/SecretsPane'
import type { Subpage } from './skillRoute'
import type { DetailRow } from './skillDetails'

/**
 * 设置: one column of subjects, the one being set beside it. Nothing separates the two but the
 * indent: a rule there would fence off a list of three.
 *
 * 危险区域 stays at the bottom of 常规 rather than in the navigation — an action that takes the
 * skill away from every reader is not one of three equal options. Only the owner reaches it: the
 * service switch and deletion are off the ladder (ADR 0047, ADR 0050).
 */
export interface SettingsTabProps {
  skill: AuthoredSkill
  sub: Subpage
  subHref: (key: Subpage) => string
  requestCount: number
  setRequestCount: (count: number) => void
  rung: Standing
  visibilityPane: RefObject<HTMLElement | null>
  visibilityChoice: string
  onChangeVisibility: (choice: string) => void
  offline: boolean
  offlineButton: RefObject<HTMLButtonElement | null>
  onAskOffline: () => void
  deleteButton: RefObject<HTMLButtonElement | null>
  onAskDelete: () => void
  detailRows: DetailRow[]
}

export default function SettingsTab({
  skill,
  sub,
  subHref,
  requestCount,
  setRequestCount,
  rung,
  visibilityPane,
  visibilityChoice,
  onChangeVisibility,
  offline,
  offlineButton,
  onAskOffline,
  deleteButton,
  onAskDelete,
  detailRows,
}: SettingsTabProps) {
  /* Absent on an older server: show the three-way choice it still honours (ADR 0146). */
  const canPublishPublic = skill.can_publish_public !== false
  return (
    <div className="tab-panel" data-tab="settings">
      <div className="settings">
        {/* One column of subjects, the one being set beside it. Nothing separates the two but
            the indent: a rule there would fence off a list of three. */}
        <nav className="settings-nav" aria-label="设置的分区">
          <a
            className={sub === 'general' ? 'settings-item is-current' : 'settings-item'}
            aria-current={sub === 'general' ? 'page' : undefined}
            href={subHref('general')}
          >
            常规
          </a>
          <a
            className={sub === 'collaborators' ? 'settings-item is-current' : 'settings-item'}
            aria-current={sub === 'collaborators' ? 'page' : undefined}
            href={subHref('collaborators')}
          >
            协作者
            {requestCount > 0 ? <span className="count">{requestCount}</span> : null}
          </a>
          <a
            className={sub === 'secrets' ? 'settings-item is-current' : 'settings-item'}
            aria-current={sub === 'secrets' ? 'page' : undefined}
            href={subHref('secrets')}
          >
            服务凭据
          </a>
          <a
            className={sub === 'details' ? 'settings-item is-current' : 'settings-item'}
            aria-current={sub === 'details' ? 'page' : undefined}
            href={subHref('details')}
          >
            详细信息
          </a>
        </nav>

        <div className="settings-body">
          {sub === 'general' ? (
            <section ref={visibilityPane} className="settings-pane" data-sub="general">
              <h2>常规</h2>

              <section className="settings-block">
                <h3>可见性</h3>
                <p className="hint">
                  {canPublishPublic
                    ? '公开的 skill 可以被搜索到；私有的 skill 仅你和协作者能访问，其他人不会知道它存在。'
                    : '公开目前只对官方 skill 开放。你的 skill 保持私有，只有你和被授权的协作者可以访问。'}
                </p>
                {!canPublishPublic && visibilityChoice !== 'private' ? (
                  <p className="hint">
                    这个 skill 目前是公开的，但所在命名空间已不能再设公开。改回私有不影响已经发生的下载与复制。
                  </p>
                ) : null}
                <div className="choices">
                  <label className="choice">
                    <input
                      type="radio"
                      name="skill-visibility"
                      value="private"
                      checked={visibilityChoice === 'private'}
                      onChange={() => {
                        onChangeVisibility('private')
                      }}
                    />
                    <span className="choice-body">
                      <span className="choice-title">私有</span>
                      <span className="choice-note">仅你和被授权的协作者可以访问。</span>
                    </span>
                  </label>
                  {canPublishPublic ? (
                    <>
                      <label className="choice">
                        <input
                          type="radio"
                          name="skill-visibility"
                          value="public-invoke"
                          checked={visibilityChoice === 'public-invoke'}
                          onChange={() => {
                            onChangeVisibility('public-invoke')
                          }}
                        />
                        <span className="choice-body">
                          <span className="choice-title">公开 · 调用</span>
                          <span className="choice-note">
                            任何人都可以调用，也可以按路径读取文件；但无法查看文件清单，只有正文中提到的文件可以按路径获取。
                          </span>
                        </span>
                      </label>
                      <label className="choice">
                        <input
                          type="radio"
                          name="skill-visibility"
                          value="public-browse"
                          checked={visibilityChoice === 'public-browse'}
                          onChange={() => {
                            onChangeVisibility('public-browse')
                          }}
                        />
                        <span className="choice-body">
                          <span className="choice-title">公开 · 浏览</span>
                          <span className="choice-note">
                            任何人都可以调用、查看全部文件并下载；这个 skill 也可以被复制。
                          </span>
                          <span className="choice-warn">已经发生的下载与复制无法收回。</span>
                        </span>
                      </label>
                    </>
                  ) : null}
                </div>
              </section>

              {/* 危险区域: it stays at the bottom of this page rather than in the navigation —
                  an action that takes the skill away from every reader is not one of three
                  equal options. Only the owner reaches it: the service switch and deletion are
                  off the ladder (ADR 0047, ADR 0050). */}
              {rung === 'owner' ? (
                <section className="settings-block is-danger">
                  <h3>危险操作</h3>
                  <div className="danger-box">
                    {/* **下线是整个 skill 的服务开关，不是某一版的状态。** Versions and the
                        pointer do not move; restoring needs no re-publish. Reversible, so it
                        comes before deletion. */}
                    <div className="danger-row">
                      <div className="danger-what">
                        <p className="danger-title">{offline ? '恢复服务' : '下线这个 skill'}</p>
                        {!offline ? (
                          <p className="hint">
                            下线后，所有读者按这个 skill 的地址调用都会得到「服务已下线」；它也不再出现在搜索与公开列表里。版本状态与当前版本都不变，恢复后不需要重新上线。
                          </p>
                        ) : (
                          <p className="hint">
                            这个 skill 已下线：调用者得到「服务已下线」。恢复后，当前线上版本直接重新对外。版本状态与当前版本都不变。
                          </p>
                        )}
                      </div>
                      <div className="danger-acts">
                        <button
                          ref={offlineButton}
                          type="button"
                          className={offline ? 'button' : 'button is-danger'}
                          onClick={onAskOffline}
                        >
                          {offline ? '恢复服务' : '下线'}
                        </button>
                      </div>
                    </div>

                    <div className="danger-row">
                      {/* 后果写在按钮旁边，不是确认框里才第一次出现：确认框只重复最要紧的那一条。 */}
                      <div className="danger-what">
                        <p className="danger-title">删除这个 skill</p>
                        <p className="hint">
                          删除后，这个 skill 对所有访问者不可见，当前地址立即失效。{' '}
                          <strong>名称不会被释放</strong>——要用同一个名称创建新 skill，必须先恢复这个 skill。
                        </p>
                        <p className="hint">已删除的 skill 会保留在「我的 skill」的「已删除」中，可以恢复。</p>
                      </div>
                      <div className="danger-acts">
                        <button
                          ref={deleteButton}
                          type="button"
                          className="button is-danger"
                          onClick={onAskDelete}
                        >
                          删除
                        </button>
                      </div>
                    </div>
                  </div>
                </section>
              ) : null}
            </section>
          ) : sub === 'collaborators' ? (
            <SkillCollaborators
              namespace={skill.namespace.slug}
              name={skill.name}
              publicRung={skill.public_rung}
              onCount={setRequestCount}
            />
          ) : sub === 'secrets' ? (
            // v52. It reads both lists itself, from the skill's address: the owner's credentials
            // bound here, and the variables other people lend to this skill. The pane is what makes
            // 「这个 skill 为什么能跑」 answerable without opening each credential.
            <SecretsPane
              hidden={false}
              skill={`${skill.namespace.slug}/${skill.name}`}
              /* Anyone who reaches this tab can read the pane: it is the author's own settings,
                 and every rung that gets here at least browses the skill. */
              enabled
            />
          ) : (
            <section className="settings-pane" data-sub="details">
              <h2>详细信息</h2>
              <p className="hint">
                这些是 SKILL.md 中声明的字段。调用者在「信息」页只能看到描述、适用场景、运行环境与版本。
              </p>
              <dl className="info-rows">
                {detailRows.map((row) => (
                  <div key={row.label}>
                    <dt>{row.label}</dt>
                    <dd className={row.mono ? 'is-mono' : undefined}>{row.value}</dd>
                  </div>
                ))}
              </dl>
            </section>
          )}
        </div>
      </div>
    </div>
  )
}
