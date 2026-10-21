/**
 * 信息: the smallest complete description of a skill, and the one pane the 调用 rung can open.
 *
 * **左栏是「读」的，右栏是「瞥」的。** Description, when to use it and where it runs are sentences;
 * the version is a value. 标签 is an optional extension this version does not draw, but its slot is
 * here for the day the field ships.
 *
 * The 申请 block is drawn while there is still a rung above the reader: 调用 and 浏览 are not
 * authors, 写入 and 维护 are in the list but want one rung higher, and 管理/所有者 have nothing
 * above them.
 */

import SkillMenu from './SkillMenu'
import type { AtLeast, Version } from '../../fixtures/skill'
import { FRONTMATTER, RUNG_NOTES, TAGS, rungLabel, rungsAbove } from '../../fixtures/skill'

const STANDING: Record<string, string> = {
  invoke: '当前档位为「调用」：可以调用，但无法查看文件清单。',
  browse: '当前档位为「浏览」：可以读取，但无法提交版本。',
  write: '当前档位为「写入」：可以提交版本，但无法上线。',
  maintain: '当前档位为「维护」：可以上线，但无法修改可见性与协作者。',
}

export interface InfoPaneProps {
  hidden: boolean
  tier: string
  atLeast: AtLeast
  live: Version | null
  myRequest: { role: string } | null
  askedRung: string | null
  onAskedRung: (role: string) => void
  onSubmitRequest: (role: string) => void
  onWithdraw: () => void
}

export default function InfoPane({
  hidden,
  tier,
  atLeast,
  live,
  myRequest,
  askedRung,
  onAskedRung,
  onSubmitRequest,
  onWithdraw,
}: InfoPaneProps) {
  const options = rungsAbove(tier)
  const show = tier !== 'owner' && options.length > 0
  const effective = options.some((rung) => rung.name === askedRung)
    ? (askedRung as string)
    : options[0]?.name ?? ''
  const standing = STANDING[tier] ?? ''

  return (
    <div className="tab-panel" data-tab="info" hidden={hidden}>
      <section className="info-pane">
        <div className="info-main">
          <section className="info-block">
            <span className="info-label">
              <svg className="info-icon" aria-hidden="true"><use href="#descMark" /></svg>
              描述
            </span>
            <p className="info-text" id="infoDesc">{FRONTMATTER.description}</p>
          </section>
          <section className="info-block" id="infoWhen" hidden={!FRONTMATTER.metadata.when_to_use}>
            <span className="info-label">
              <svg className="info-icon" aria-hidden="true"><use href="#clockMark" /></svg>
              适用场景
            </span>
            <p className="info-text" id="infoWhenText">{FRONTMATTER.metadata.when_to_use || ''}</p>
          </section>
          <section className="info-block" id="infoEnv" hidden={!FRONTMATTER.compatibility}>
            <span className="info-label">
              <svg className="info-icon" aria-hidden="true"><use href="#envMark" /></svg>
              运行环境
            </span>
            <p className="info-text" id="infoEnvText">{FRONTMATTER.compatibility || ''}</p>
          </section>
        </div>

        <aside className="info-rail">
          <section className="info-rail-item" id="infoVersion">
            <svg className="info-rail-icon" aria-hidden="true"><use href="#versionMark" /></svg>
            <span className="info-rail-label">版本</span>
            <span className="info-rail-value" id="infoVersionText">{live ? live.name : '未上线'}</span>
          </section>
          <div className="info-tags" id="infoTags" hidden={TAGS.length === 0}>
            {TAGS.map((tag) => <span className="chip is-plain" key={tag}>{tag}</span>)}
          </div>
        </aside>
      </section>

      <section className="ask" id="askBlock" hidden={!show}>
        <h3>申请</h3>
        <p className="hint" id="askWhy">
          {show ? (myRequest ? standing : standing + '如需更高的档位，请在下方申请。') : ''}
        </p>
        <div className="ask-form" id="askForm" hidden={!!myRequest}>
          <span className="ask-label" id="askRoleLabel">申请档位</span>
          <span className="menu-slot" id="askRole">
            {show && (
              <SkillMenu
                groups={[{ options: options.map((rung) => ({ value: rung.name, label: rung.label })) }]}
                value={effective}
                config={{ ariaLabel: '申请档位', ariaLabelledBy: 'askRoleLabel', onPick: onAskedRung }}
              />
            )}
          </span>
          <button
            type="button"
            className="button"
            id="askSubmit"
            onClick={() => onSubmitRequest(effective)}
          >
            {show && atLeast('write') ? '申请' : '申请成为协作者'}
          </button>
        </div>
        <p className="ask-note" id="askNote" hidden={!!myRequest}>
          {show ? (RUNG_NOTES[effective] ?? '') : ''}
        </p>
        <div className="ask-pending" id="askPending" hidden={!myRequest}>
          <span className="chip is-plain"><span className="dot"></span><span className="chip-text">等待处理</span></span>
          <span id="askPendingText">
            {myRequest ? '已提交「' + rungLabel(myRequest.role) + '」的申请，由 dev 或「管理」审批。' : ''}
          </span>
          <button type="button" className="link-button" id="askWithdraw" onClick={onWithdraw}>撤回申请</button>
        </div>
      </section>
    </div>
  )
}
