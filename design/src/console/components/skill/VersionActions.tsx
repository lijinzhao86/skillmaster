/**
 * 操作: every state-changing action this version can take.
 *
 * **生命周期动作在这里，不在版本表那一行上.** The page first shows what this version is and what
 * it says; the table only lists entries (ADR 0054). Ordinary actions come first, and the act that
 * takes something away sits at the bottom under 危险操作.
 */

import { useRef, useState } from 'react'
import type { ReactNode } from 'react'
import SkillMenu from './SkillMenu'
import type { AtLeast, Version } from '../../fixtures/skill'
import {
  canEditDraft,
  liveVersion,
  rollbackCandidates,
  versionByPin,
  versionMenuOption,
  versionOptionValue,
  versionRefOf,
  versionTitleOf,
} from '../../fixtures/skill'
import { dialogs } from '../dialogs/store'

export interface VersionActionsProps {
  active: boolean
  target: Version
  versions: Version[]
  atLeast: AtLeast
  focusTitle: () => HTMLElement | null
  onPublish: (key: string, name: string) => void
  onDiscard: (key: string) => void
  onRestore: (key: string) => void
  onRepublish: (key: string) => void
  onRevoke: (key: string, reason: string) => void
}

export default function VersionActions({
  active,
  target,
  versions,
  atLeast,
  focusTitle,
  onPublish,
  onDiscard,
  onRestore,
  onRepublish,
  onRevoke,
}: VersionActionsProps) {
  const draft = !!target.draft
  const maintain = atLeast('maintain')
  const editable = canEditDraft(target, atLeast)
  const hasActions = draft ? (maintain || editable) : target.state === 'discarded' ? false : maintain
  const hasDanger = !draft && !target.live && maintain && (!target.state || target.state === 'published')

  return (
    <div className="vp-pane vp-actions-pane" id="vpActionsPane" data-vpane="actions" hidden={!active}>
      <div className="vp-actions-main">
        <section className={'vp-module' + (hasDanger ? '' : ' is-last')} id="vpActions" hidden={!hasActions}>
          <div id="vpActionBody">
            {draft && maintain && (
              <PublishAction
                target={target}
                versions={versions}
                atLeast={atLeast}
                focusTitle={focusTitle}
                onPublish={onPublish}
              />
            )}
            {draft && editable && <DiscardAction target={target} onDiscard={onDiscard} />}
            {!draft && target.state === 'revoked' && maintain && (
              <TwoStepAction
                title="重新上架"
                text="重新上架后，它回到历史版本，调用者又能读它了；要让它重新对外，还需要再上线一次。"
                label="重新上架"
                confirmLabel="确认重新上架"
                onConfirm={() => onRestore(versionOptionValue(target))}
              />
            )}
            {!draft && target.live && maintain && (
              <RollbackAction
                target={target}
                versions={versions}
                atLeast={atLeast}
                focusTitle={focusTitle}
                onRepublish={onRepublish}
              />
            )}
            {!draft && !target.live && target.state !== 'revoked' && target.state !== 'discarded' && maintain && (
              <TwoStepAction
                title="上线"
                text="上线后，这个版本成为当前版本，原来的线上版本变成历史版本；说明与版本号都不再改。"
                label={'上线 ' + versionTitleOf(target, atLeast('browse'))}
                confirmLabel="确认上线"
                onConfirm={() => onRepublish(versionOptionValue(target))}
              />
            )}
          </div>
        </section>

        <section className="vp-module is-danger" id="vpDanger" hidden={!hasDanger}>
          <p className="vp-eyebrow is-danger">危险操作</p>
          <div className="danger-box" id="vpDangerBody">
            {hasDanger && (
              <RevokeAction
                target={target}
                atLeast={atLeast}
                onRevoke={onRevoke}
              />
            )}
          </div>
        </section>
      </div>
    </div>
  )
}

function ActionBlock({ title, text, children }: { title: string; text: string; children?: ReactNode }) {
  return (
    <div className="vp-action">
      <h3>{title}</h3>
      <p>{text}</p>
      {children}
    </div>
  )
}

function PublishAction({
  target,
  versions,
  atLeast,
  focusTitle,
  onPublish,
}: {
  target: Version
  versions: Version[]
  atLeast: AtLeast
  focusTitle: () => HTMLElement | null
  onPublish: (key: string, name: string) => void
}) {
  const live = liveVersion(versions)
  const base = live?.name ? live.name.slice(1).split('.').map(Number) : [1, 0, 0]
  /* 首个上线版本不是一次 bump：没有线上版本可比，建议直接从 1.0.0 起步。 */
  const suggestions = live
    ? {
      patch: base[0] + '.' + base[1] + '.' + (base[2] + 1),
      minor: base[0] + '.' + (base[1] + 1) + '.0',
      major: (base[0] + 1) + '.0.0',
    }
    : { patch: '1.0.0', minor: '1.0.0', major: '1.0.0' }

  const [release, setRelease] = useState(live ? suggestions.patch : '1.0.0')
  const [bump, setBump] = useState<'patch' | 'minor' | 'major'>('patch')
  const [invalid, setInvalid] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const name = release.trim()

  const ask = () => {
    /* 形状在这里先挡一次；真正的判据在服务端（SemVer 2.0.0）。 */
    if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(name)) {
      setInvalid(true)
      input.current?.focus()
      return
    }
    dialogs.openDanger({
      title: '上线 ' + versionTitleOf(target, atLeast('browse')) + '？',
      body: '上线后，这一版会拿到版本号 @' + name + '，成为当前版本；' +
        '不指定版本的调用会立刻读到它。名称与说明在这一刻锁定——' +
        '调用者会读到它们，上线后不能再改。',
      confirmLabel: '确认上线',
      pendingLabel: '正在上线…',
      danger: false,
      onConfirm: () => onPublish(versionOptionValue(target), name),
      focusAfter: focusTitle(),
      returnFocus: button.current,
    })
  }

  return (
    <ActionBlock
      title="上线"
      text={live
        ? '当前线上是 ' + (live.name ?? '') + '（' + versionTitleOf(live, atLeast('browse')) + '）。上线后，不指定版本的调用会立刻读到这一版；指定旧版本的调用不受影响。'
        : '还没有线上版本。这是这个 skill 第一次上线，版本号从 1.0.0 起步。'}
    >
      {live && (
        <div className="confirm-field">
          <span className="confirm-label">这次改动会影响旧的调用吗</span>
          <div className="choices">
            {([
              ['patch', '只是修正', '修好了问题，调用方式不变'],
              ['minor', '新增能力', '多了新东西，旧调用照旧能用'],
              ['major', '旧调用会失效', '旧的调用要改，才能继续用'],
            ] as const).map((entry) => (
              <label className="choice" key={entry[0]}>
                <input
                  type="radio"
                  name="vp-bump"
                  value={entry[0]}
                  checked={bump === entry[0]}
                  onChange={() => {
                    setBump(entry[0])
                    setRelease(suggestions[entry[0]])
                    setInvalid(false)
                  }}
                />
                <span className="choice-body">
                  <span className="choice-title">{entry[1]}</span>
                  <span className="choice-note">{entry[2] + ' · ' + suggestions[entry[0]]}</span>
                </span>
              </label>
            ))}
          </div>
        </div>
      )}

      <label className="confirm-field">
        <span className="confirm-label">版本号</span>
        <input
          className={'confirm-input is-mono' + (invalid ? ' is-invalid' : '')}
          id="vpRelease"
          ref={input}
          value={release}
          maxLength={64}
          autoComplete="off"
          onInput={(event) => {
            setRelease(event.currentTarget.value)
            setInvalid(false)
          }}
          onChange={(event) => {
            setRelease(event.currentTarget.value)
            setInvalid(false)
          }}
        />
      </label>

      <div className="vp-action-acts">
        <button type="button" className="button" ref={button} onClick={ask}>
          {name ? '上线 @' + name : '上线'}
        </button>
      </div>
    </ActionBlock>
  )
}

function DiscardAction({
  target,
  onDiscard,
}: {
  target: Version
  onDiscard: (key: string) => void
}) {
  const button = useRef<HTMLButtonElement>(null)
  const text = '丢弃后无法找回：它不会再上线；维护及以上仍能在版本表里看到这条记录，没有恢复的入口。'
  return (
    <ActionBlock title="丢弃" text={text}>
      <div className="vp-action-acts">
        <button
          type="button"
          className="button is-quiet"
          ref={button}
          onClick={() => {
            dialogs.openDanger({
              title: '丢弃这个版本？',
              body: text,
              confirmLabel: '确认丢弃',
              pendingLabel: '正在丢弃…',
              failMessage: '丢弃失败：服务器暂时没有响应，页面没有变化，请重试。',
              danger: false,
              onConfirm: () => onDiscard(versionOptionValue(target)),
              returnFocus: button.current,
            })
          }}
        >
          丢弃
        </button>
      </div>
    </ActionBlock>
  )
}

function TwoStepAction({
  title,
  text,
  label,
  confirmLabel,
  onConfirm,
}: {
  title: string
  text: string
  label: string
  confirmLabel: string
  onConfirm: () => void
}) {
  const [armed, setArmed] = useState(false)
  return (
    <ActionBlock title={title} text={text}>
      <div className="vp-action-acts">
        {armed ? (
          <>
            <span className="is-warn">确认后立即生效。</span>
            <button type="button" className="button is-quiet" onClick={() => setArmed(false)}>取消</button>
            <button
              type="button"
              className="button"
              onClick={() => {
                setArmed(false)
                onConfirm()
              }}
            >
              {confirmLabel}
            </button>
          </>
        ) : (
          <button type="button" className="button" onClick={() => setArmed(true)}>{label}</button>
        )}
      </div>
    </ActionBlock>
  )
}

function RollbackAction({
  target,
  versions,
  atLeast,
  focusTitle,
  onRepublish,
}: {
  target: Version
  versions: Version[]
  atLeast: AtLeast
  focusTitle: () => HTMLElement | null
  onRepublish: (key: string) => void
}) {
  const candidates = rollbackCandidates(versions)
  const [selected, setSelected] = useState(versionOptionValue(candidates[0] ?? target))
  const button = useRef<HTMLButtonElement>(null)
  const text = candidates.length === 0
    ? '没有可以回滚的版本——这个 skill 只有当前线上版本。'
    : '所选版本重新上线，' + versionTitleOf(target, atLeast('browse')) + ' 变成历史版本。' +
      '不会新建版本，版本号与内容不变。'

  const ask = () => {
    const chosen = versionByPin(versions, selected.slice(1), atLeast)
    if (!chosen) return
    dialogs.openDanger({
      title: '回滚到 ' + versionTitleOf(chosen, atLeast('browse')) + '？',
      body: versionRefOf(chosen, atLeast('browse')) + ' 会重新成为当前版本，' +
        versionRefOf(target, atLeast('browse')) + ' 变成历史版本。不会新建版本，版本号与内容不变；指定 ' +
        (target.name ?? '') + ' 的调用仍可读。',
      confirmLabel: '确认回滚',
      pendingLabel: '正在回滚…',
      failMessage: '回滚失败：服务器暂时没有响应，页面没有变化，请重试。',
      danger: false,
      onConfirm: () => onRepublish(versionOptionValue(chosen)),
      focusAfter: focusTitle(),
      returnFocus: button.current,
    })
  }

  return (
    <ActionBlock title="回滚" text={text}>
      {candidates.length > 0 && (
        <div className="vp-action-acts">
          <span className="version-picker" id="vpRollbackSlot">
            <SkillMenu
              groups={[{
                options: candidates.map((candidate) => versionMenuOption(candidate, atLeast('browse'), false)),
              }]}
              value={selected}
              config={{
                ariaLabel: '回滚到哪一版',
                search: true,
                searchPlaceholder: '按名称或版本号筛选',
                emptyText: '没有匹配的版本。',
                onPick: setSelected,
              }}
            />
          </span>
          <button type="button" className="button" ref={button} onClick={ask}>回滚</button>
        </div>
      )}
    </ActionBlock>
  )
}

function RevokeAction({
  target,
  atLeast,
  onRevoke,
}: {
  target: Version
  atLeast: AtLeast
  onRevoke: (key: string, reason: string) => void
}) {
  const button = useRef<HTMLButtonElement>(null)
  return (
    <div className="danger-row">
      <div className="danger-what">
        <p className="danger-title">下架</p>
        <p className="hint">下架后，使用这个版本的调用会立刻失败，直到你重新上架为止。</p>
      </div>
      <div className="danger-acts">
        <button
          type="button"
          className="button is-danger"
          ref={button}
          onClick={() => {
            dialogs.openDanger({
              title: '下架 ' + versionRefOf(target, atLeast('browse')) + '？',
              body: '下架后，使用这个版本的调用会立刻失败，直到你重新上架为止。',
              confirmLabel: '确认下架',
              pendingLabel: '正在下架…',
              /* **原因是可选的（ADR 0059）。** 最常见的情形是「这一版没人用了」，那个框没有答案可写。 */
              field: '原因（可选，只有维护者看得到）',
              optionalField: true,
              placeholder: '例如：这个版本有安全问题，需要让调用立刻失败',
              onConfirm: (reason) => onRevoke(versionOptionValue(target), reason ?? ''),
              returnFocus: button.current,
            })
          }}
        >
          下架
        </button>
      </div>
    </div>
  )
}
