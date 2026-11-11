/**
 * 设置 › 常规: visibility, and the danger zone at the bottom of the same page.
 *
 * Visibility is part of "what this skill is", so it sits with the other general properties. The
 * danger zone stays out of the second-level nav: an action that takes the skill from every reader
 * is not one more section next to 协作者 and 详细信息.
 *
 * **下线有三态（v61）**：在线 →（公开且有入库者）下线中 30 天 → 已下线。下线中仍可调用，
 * 但每次调用带提醒、也不接受新入库；删除要等下线完成。没有入库者的公开 skill 与私有 skill
 * 仍是一步下线。
 */

import { useRef, useState } from 'react'
import type { AtLeast, Visibility } from '../../fixtures/skill'
import { dialogs } from '../dialogs/store'
import { go } from '../../address'

export type OfflinePhase = 'online' | 'pending' | 'offline'

export interface GeneralPaneProps {
  hidden: boolean
  atLeast: AtLeast
  /** 这个命名空间能不能公开（ADR 0146）；非官方只画「私有」。 */
  canPublishPublic: boolean
  visibility: Visibility
  phase: OfflinePhase
  /** 下线中的到期日；只在 `phase === 'pending'` 时有意义。 */
  pendingUntil: string
  /** 入库人数；私有时为 0。 */
  joins: number
  /** 被授权的人数（不含所有者）。 */
  grantees: number
  onVisibility: (value: Visibility) => void
  onOffline: (next: OfflinePhase) => void
}

export default function GeneralPane({
  hidden,
  atLeast,
  canPublishPublic,
  visibility,
  phase,
  pendingUntil,
  joins,
  grantees,
  onVisibility,
  onOffline,
}: GeneralPaneProps) {
  const radios = useRef<Partial<Record<Visibility, HTMLInputElement | null>>>({})
  /* The radio that asked is put back before the sheet is even up: the answer has not been given
     yet, and a control that has already moved is a decision taken for the reader. */
  const [, bump] = useState(0)
  const deleteButton = useRef<HTMLButtonElement>(null)
  const offlineButton = useRef<HTMLButtonElement>(null)
  const browse = atLeast('browse')
  const owner = atLeast('owner')

  const pending = phase === 'pending'
  const offline = phase === 'offline'
  const publicSkill = visibility !== 'private'
  /* 公开且有人入库：下线先走 30 天，删除要等它走完。 */
  const ramped = publicSkill && joins > 0
  const deleteBlocked = ramped && phase !== 'offline'

  const impactParts: string[] = []
  if (grantees > 0) impactParts.push(`${grantees} 人获得授权`)
  if (joins > 0) impactParts.push(`${joins} 人入库`)
  /* A3：确认里要说清三件事——公开/私有、授权人数、入库人数。 */
  const visibilityWord = publicSkill ? '公开' : '私有'
  const impact = impactParts.length > 0
    ? `它是${visibilityWord}的；有 ${impactParts.join('、')}；删除后他们会收到通知。`
    : `它是${visibilityWord}的；没有其他人获得授权或入库。`

  const change = (value: Visibility) => {
    if (!browse) return
    if (value !== 'private' && !canPublishPublic) return
    if (value === 'public-browse') {
      bump((count) => count + 1)
      dialogs.openPublicBrowse({
        onConfirm: () => onVisibility('public-browse'),
        returnFocus: radios.current[visibility] ?? null,
      })
      return
    }
    onVisibility(value)
  }

  return (
    <section className="settings-pane" data-sub="general" hidden={hidden}>
      <h2>常规</h2>

      <section className="settings-block">
        <h3>可见性</h3>
        <p className="hint">
          {canPublishPublic
            ? '公开的 skill 可以被搜索到；私有的 skill 仅你和协作者能访问，其他人不会知道它存在。'
            : '公开目前只对官方 skill 开放。你的 skill 保持私有，只有你和被授权的协作者可以访问。'}
        </p>
        {/* Two answers, because 公开 has two: the bottom rung of the ladder below, or the bottom
            rung plus the listing. */}
        <div className="choices" id="visibilityChoices">
          <label className="choice">
            <input
              type="radio"
              name="skill-visibility"
              value="private"
              checked={browse && visibility === 'private'}
              ref={(node) => { radios.current.private = node }}
              onChange={() => change('private')}
            />
            <span className="choice-body">
              <span className="choice-title">私有</span>
              <span className="choice-note">仅你和被授权的协作者可以访问。</span>
            </span>
          </label>
          {canPublishPublic && (
            <>
          <label className="choice">
            <input
              type="radio"
              name="skill-visibility"
              value="public-invoke"
              checked={browse && visibility === 'public-invoke'}
              ref={(node) => { radios.current['public-invoke'] = node }}
              onChange={() => change('public-invoke')}
            />
            <span className="choice-body">
              <span className="choice-title">公开 · 调用</span>
              <span className="choice-note">任何人都可以调用，也可以按路径读取文件；但无法查看文件清单，只有正文中提到的文件可以按路径获取。</span>
            </span>
          </label>
          <label className="choice">
            <input
              type="radio"
              name="skill-visibility"
              value="public-browse"
              checked={browse && visibility === 'public-browse'}
              ref={(node) => { radios.current['public-browse'] = node }}
              onChange={() => change('public-browse')}
            />
            <span className="choice-body">
              <span className="choice-title">公开 · 浏览</span>
              <span className="choice-note">任何人都可以调用、查看全部文件并下载；这个 skill 也可以被复制。</span>
              {/* **只有这一档要标出代价。** 红字只说灰字没说的那一半：收不回来。 */}
              <span className="choice-warn">已经发生的下载与复制无法收回。</span>
            </span>
          </label>
            </>
          )}
        </div>
      </section>

      <section className="settings-block is-danger" id="dangerZone" hidden={!owner}>
        <h3>危险操作</h3>
        <div className="danger-box">
          <div className="danger-row" id="offlineRow" hidden={!owner}>
            {/* 这一行的文字由 `renderSettings` 重建：确认时问句会把后果那一段换成问题. */}
            {browse ? (
              <>
                <div className="danger-what" id="offlineWhat">
                  <p className="danger-title" id="offlineTitle">
                    {pending ? '取消下线' : offline ? '恢复服务' : '下线这个 skill'}
                  </p>
                  <div id="offlineWhy">
                    <p className="hint" hidden={offline || pending}>
                      {ramped
                        ? `它已公开，有 ${joins} 人入库。下线会先进入 30 天的「下线中」：期间仍可调用，每次调用都会提醒；满 30 天后正式下线。入库与授权的人会收到通知。`
                        : '下线后，所有读者按这个 skill 的地址调用都会得到「服务已下线」；它也不再出现在搜索与公开列表里。版本状态与当前版本都不变，恢复后不需要重新上线。'}
                    </p>
                    <p className="hint" hidden={!pending}>
                      这个 skill 正在下线中：将于 {pendingUntil} 正式下线。期间仍可调用，每次调用都会提醒；现在不接受新入库。
                    </p>
                    <p className="hint" hidden={!offline}>
                      这个 skill 已下线：调用者得到「服务已下线」。恢复后，当前线上版本直接重新对外。版本状态与当前版本都不变。
                    </p>
                  </div>
                </div>
                <div className="danger-acts" id="offlineActs">
                  <button
                    type="button"
                    id="offlineSkill"
                    className={offline || pending ? 'button' : 'button is-danger'}
                    ref={offlineButton}
                    onClick={() => {
                      if (pending) {
                        dialogs.openDanger({
                          title: '取消下线？',
                          body: '取消后恢复正常服务，调用提醒消失；入库与授权的人会收到通知。',
                          confirmLabel: '确认取消下线',
                          pendingLabel: '正在取消下线…',
                          danger: false,
                          onConfirm: () => onOffline('online'),
                          returnFocus: offlineButton.current,
                        })
                        return
                      }
                      dialogs.openDanger({
                        title: offline ? '恢复服务' : '下线这个 skill',
                        body: offline
                          ? '恢复后，当前线上版本直接重新对外，不需要重新上线。'
                          : ramped
                            ? `它已公开，有 ${joins} 人入库、${grantees} 人获得授权。确认后进入 30 天下线中：期间仍可调用，每次调用都会提醒；满 30 天后正式下线。入库与授权的人会收到通知。`
                            : '下线后，所有调用都会失败；版本状态与当前版本都不变。',
                        confirmLabel: offline ? '确认恢复服务' : ramped ? '开始下线' : '确认下线',
                        /* 红=会拿走东西：恢复服务与取消下线都不是。 */
                        danger: !pending && !offline,
                        pendingLabel: offline
                          ? '正在恢复服务…'
                          : ramped ? '正在开始下线…' : '正在下线…',
                        onConfirm: () => onOffline(
                          offline ? 'online' : ramped ? 'pending' : 'offline',
                        ),
                        returnFocus: offlineButton.current,
                      })
                    }}
                  >
                    {pending ? '取消下线' : offline ? '恢复服务' : '下线'}
                  </button>
                </div>
              </>
            ) : (
              <>
                <div className="danger-what" id="offlineWhat"></div>
                <div className="danger-acts" id="offlineActs">
                  <button type="button" className="button is-danger" id="offlineSkill">下线</button>
                </div>
              </>
            )}
          </div>
          <div className="danger-row">
            <div className="danger-what">
              <p className="danger-title">删除这个 skill</p>
              <div id="deleteWhy">
                <p className="hint">
                  {deleteBlocked
                    ? `它已公开，有 ${joins} 人入库。先走完下线，才能删除。`
                    : <>
                      删除后，这个 skill 对所有访问者不可见，当前地址立即失效。
                      名字会立即释放，可以用同一个名字创建新 skill。
                      已删除的 skill 会保留在「我的 skill」的「已删除」中，可以恢复。
                    </>}
                </p>
                {!deleteBlocked && <p className="hint">{impact}</p>}
              </div>
            </div>
            <div className="danger-acts" id="deleteActs">
              <button
                type="button"
                className="button is-danger"
                id="deleteSkill"
                ref={deleteButton}
                disabled={deleteBlocked}
                onClick={() => {
                  if (!browse || deleteBlocked) return
                  dialogs.openDanger({
                    title: '删除这个 skill？',
                    body: `删除后，当前地址立即失效；名字会立即释放，可以用同一个名字创建新 skill。${impact}`,
                    confirmLabel: '确认删除',
                    pendingLabel: '正在删除…',
                    onConfirm: () => go('?page=skills&show=deleted'),
                    returnFocus: deleteButton.current,
                  })
                }}
              >
                删除
              </button>
            </div>
          </div>
        </div>
      </section>
    </section>
  )
}
